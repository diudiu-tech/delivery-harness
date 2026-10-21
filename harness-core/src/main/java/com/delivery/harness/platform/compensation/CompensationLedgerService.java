package com.delivery.harness.platform.compensation;

import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in execution ledger. An approval is never itself an instruction to move money. */
@Service
public class CompensationLedgerService {
    private final PlatformStore store;
    private final CaseService cases;
    private final PaymentChannel channel;
    private final boolean enabled;
    private final String callbackSecret;
    @Value("${harness.guardrail.max-compensation-amount:50.00}")
    private BigDecimal maximumPerOrder = new BigDecimal("50.00");
    private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread worker = new Thread(r, "compensation-outbox"); worker.setDaemon(true); return worker;
    });

    public CompensationLedgerService(PlatformStore store, CaseService cases, PaymentChannel channel,
            @Value("${harness.compensation.execution-enabled:false}") boolean enabled,
            @Value("${harness.compensation.callback-secret:}") String callbackSecret) {
        this.store = store;
        this.cases = cases;
        this.channel = channel;
        this.enabled = enabled;
        this.callbackSecret = callbackSecret;
    }

    @PostConstruct public void startRecovery() {
        if (enabled) recovery.scheduleWithFixedDelay(this::recoverOutbox, 10, 10, TimeUnit.SECONDS);
    }
    @PreDestroy public void stopRecovery() { recovery.shutdownNow(); }
    /** PENDING is the durable outbox; dispatched/uncertain records are queried and never resubmitted. */
    public void recoverOutbox() {
        if (!enabled) return;
        try {
            for (String tenant : store.tenants()) {
                Principal worker = new Principal(tenant, "FUNDS_RECOVERY", Set.of("FUNDS", "AUDITOR"), Set.of("*"), Set.of("*"));
                int processed = 0;
                for (PlatformStore.Entity entry : store.list(tenant, "compensation")) {
                    String status = String.valueOf(entry.data().get("status"));
                    if (!Set.of("PENDING", "SUBMITTED", "UNKNOWN").contains(status)) continue;
                    Object checked = entry.data().get("last_checked_at");
                    if (checked != null && Instant.parse(checked.toString()).isAfter(Instant.now().minusSeconds(60))) continue;
                    try {
                        if ("PENDING".equals(status)) submit(worker, Map.of("case_id", entry.data().get("case_id"),
                                "compensation_id", entry.id(), "decision_id", entry.data().get("decision_id"), "decision_version", entry.data().get("decision_version")));
                        else reconcile(worker, entry.id());
                    } catch (RuntimeException unavailable) {
                        recordRecoveryFailure(worker, entry.id(), unavailable);
                    }
                    if (++processed >= 10) break;
                }
            }
        } catch (RuntimeException unavailable) {
            // Database/channel recovery is retried on the next scan; it cannot create a new compensation.
        }
    }

    public PlatformStore.Entity submit(Principal actor, Map<String, Object> request) {
        actor.requireRole("FUNDS");
        try { return submitInternal(actor, request); }
        catch (PlatformException failure) {
            if (enabled && Set.of(404,409).contains(failure.status()) && request.get("compensation_id") instanceof String id) {
                Optional<PlatformStore.Entity> reserved=store.get(actor.tenantId(),"compensation",id);
                if (reserved.isPresent() && "PENDING".equals(reserved.get().data().get("status"))) {
                    validateRetry(reserved.get(),request);cases.require(actor,CaseService.text(reserved.get().data(),"case_id"));
                    return failUndispatched(actor,id,request,"Undispatched reservation failed reauthorization: "+failure.code());
                }
            }
            throw failure;
        }
    }

    private PlatformStore.Entity submitInternal(Principal actor, Map<String, Object> request) {
        actor.requireRole("FUNDS");
        if (!enabled) throw new PlatformException(409, "EXECUTION_DISABLED", "Compensation execution is disabled; an approved suggestion does not authorize a payment");
        String caseId = CaseService.text(request, "case_id");
        PlatformStore.Entity initialCase = cases.require(actor, caseId);
        String key = CaseService.text(request, "compensation_id");
        if (!key.matches("[A-Za-z0-9._:-]{1,100}")) throw PlatformException.invalid("compensation_id must be a stable business identifier");
        Optional<PlatformStore.Entity> duplicate = store.get(actor.tenantId(), "compensation", key);
        if (duplicate.isPresent()) {
            validateRetry(duplicate.get(), request);
            if (!"PENDING".equals(duplicate.get().data().get("status"))) return duplicate.get();
        }
        String orderId = CaseService.text(initialCase.data(), "order_id");
        PaymentChannel.Eligibility eligibility;
        try { eligibility = channel.eligibility(actor.tenantId(), orderId); }
        catch (RuntimeException e) { throw new PlatformException(503, "CHANNEL_ELIGIBILITY_UNAVAILABLE", "Execution requires current verified order state and remaining funds"); }
        if (!orderId.equals(eligibility.orderId()) || !"CNY".equals(eligibility.currency())
                || !Set.of("DELIVERED", "COMPLETED", "FINISHED").contains(eligibility.orderStatus())
                || eligibility.observedAt() == null || eligibility.observedAt().isBefore(Instant.now().minusSeconds(180))
                || eligibility.observedAt().isAfter(Instant.now().plusSeconds(30)) || eligibility.version() == null
                || eligibility.version().isBlank() || "null".equals(eligibility.version())) {
            throw PlatformException.conflict("Channel eligibility is stale or does not bind an eligible order");
        }
        PlatformStore.Entity reserved = store.transact(tx -> {
            Optional<PlatformStore.Entity> existing = tx.get(actor.tenantId(), "compensation", key);
            if (existing.isPresent()) {
                validateRetry(existing.get(), request);
                if (!"PENDING".equals(existing.get().data().get("status"))) return existing.get();
            }
            PlatformStore.Entity c = tx.require(actor.tenantId(), "case", caseId);
            CaseService.authorize(actor, c);
            if ("CLOSED".equals(c.data().get("status"))) throw PlatformException.conflict("Closed cases cannot authorize a new funds instruction");
            if (!"APPROVED".equals(c.data().get("review_status"))) throw PlatformException.conflict("A current human approval is required");
            // Lock before any ledger scans: a later CAS alone cannot protect an earlier balance snapshot.
            String guardId = PlatformStore.hash(Map.of("order_id", orderId));
            Optional<PlatformStore.Entity> existingGuard = tx.getForUpdate(actor.tenantId(), "compensation_order", guardId);
            if (existingGuard.isEmpty()) tx.create(actor.tenantId(), "compensation_order", guardId,
                    Map.of("order_id", orderId, "reserved_total", "0.00"), actor.userId(), "ORDER_FUNDS_GUARD_CREATED");
            PlatformStore.Entity guard = tx.lock(actor.tenantId(), "compensation_order", guardId);
            String decisionId = CaseService.text(c.data(), "current_decision_id");
            PlatformStore.Entity decision = tx.require(actor.tenantId(), "decision", decisionId);
            validatePolicy(tx, actor.tenantId(), decision.data());
            if (!"DETERMINED".equals(decision.data().get("decision_status"))) throw PlatformException.conflict("The decision is not determined");
            if (!Objects.equals(c.data().get("evidence_snapshot_id"), decision.data().get("evidence_snapshot_id"))) throw PlatformException.conflict("Approval has been invalidated by evidence changes");
            if (!decisionId.equals(request.get("decision_id")) || decision.version() != CaseService.number(request, "decision_version")) throw PlatformException.conflict("Execution must bind the approved immutable decision");
            PlatformStore.Entity review = tx.require(actor.tenantId(), "review", CaseService.text(c.data(), "review_id"));
            if (!"APPROVED".equals(review.data().get("status")) || !decisionId.equals(review.data().get("decision_id"))) throw PlatformException.conflict("Review approval does not bind the current decision");
            if (tx.list(actor.tenantId(), "compensation").stream().anyMatch(e -> !key.equals(e.id()) && decisionId.equals(e.data().get("decision_id")))) throw PlatformException.conflict("This decision already has a compensation ledger entry");
            Object amountValue = decision.data().containsKey("authoritative_amount") ? decision.data().get("authoritative_amount") : decision.data().get("suggested_amount");
            if (decision.data().get("amount") instanceof Map<?, ?> amount) amountValue = amount.get("value");
            else if (decision.data().get("amount") != null) amountValue = decision.data().get("amount");
            BigDecimal amount;
            try { amount = new BigDecimal(String.valueOf(amountValue)).setScale(2, java.math.RoundingMode.UNNECESSARY); }
            catch (RuntimeException e) { throw PlatformException.invalid("Approved decision has no valid monetary amount"); }
            if (amount.signum() <= 0) throw PlatformException.invalid("Compensation amount must be positive");
            String currency = String.valueOf(decision.data().getOrDefault("currency", "CNY"));
            if (!"CNY".equals(currency)) throw PlatformException.invalid("Only CNY execution is supported");
            String method = canonicalMethod(String.valueOf(decision.data().getOrDefault("method", decision.data().getOrDefault("suggested_method", "REFUND"))));
            if (!Set.of("REFUND", "COUPON", "RED_PACKET").contains(method)) throw PlatformException.invalid("Unsupported monetary execution method");
            BigDecimal available = "REFUND".equals(method) ? eligibility.remainingRefund() : eligibility.remainingCompensation();
            if (available == null || amount.compareTo(available) > 0 || amount.compareTo(maximumPerOrder) > 0) throw PlatformException.conflict("Approved amount exceeds the current channel balance or execution ceiling");
            if (existing.isPresent()) return existing.get();
            BigDecimal unresolved = tx.list(actor.tenantId(), "compensation").stream()
                    .filter(e -> orderId.equals(e.data().get("order_id")) && Set.of("PENDING", "SUBMITTED", "UNKNOWN").contains(e.data().get("status")))
                    .filter(e -> "REFUND".equals(method) == "REFUND".equals(e.data().get("method")))
                    .map(e -> new BigDecimal(e.data().get("amount").toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (unresolved.add(amount).compareTo(available) > 0) throw PlatformException.conflict("Unresolved reservations plus this amount exceed the channel's remaining order balance");
            BigDecimal total = tx.list(actor.tenantId(), "compensation").stream()
                    .filter(e -> orderId.equals(e.data().get("order_id")) && !"FAILED".equals(e.data().get("status")))
                    .map(e -> new BigDecimal(String.valueOf(e.data().get("amount")))).reduce(BigDecimal.ZERO, BigDecimal::add).add(amount);
            if (total.compareTo(maximumPerOrder) > 0) throw PlatformException.conflict("Cumulative order compensation exceeds the execution ceiling");
            Map<String, Object> guardData = Map.of("order_id", orderId, "reserved_total", total.toPlainString());
            tx.update(actor.tenantId(), "compensation_order", guardId, guard.version(), guardData, actor.userId(), "ORDER_FUNDS_RESERVED");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tenant_id", actor.tenantId()); data.put("eligibility_version", eligibility.version()); data.put("eligibility_checked_at", eligibility.observedAt().toString());
            data.put("case_id", caseId); data.put("order_id", c.data().get("order_id")); data.put("decision_id", decisionId); data.put("decision_version", decision.version());
            data.put("evidence_snapshot_id", c.data().get("evidence_snapshot_id")); data.put("amount", amount.toPlainString());
            data.put("currency", currency); data.put("method", method);
            data.put("policy_version", decision.data().get("policy_version")); data.put("status", "PENDING");
            data.put("submitted_by", actor.userId()); data.put("channel", channel.name());
            data.put("synthetic", channel.synthetic()); data.put("callback_events", new ArrayList<>());
            // CAS on the case serializes two different identifiers against the same decision.
            Map<String, Object> cd = new LinkedHashMap<>(c.data()); cd.put("payment_status", "PENDING"); cd.put("compensation_id", key);
            tx.update(actor.tenantId(), "case", caseId, c.version(), cd, actor.userId(), "COMPENSATION_RESERVED");
            return tx.create(actor.tenantId(), "compensation", key, data, actor.userId(), "COMPENSATION_PENDING");
        });
        if (!"PENDING".equals(reserved.data().get("status"))) return reserved;
        // Claim before making an external call. A crash leaves SUBMITTED for reconciliation, never a blind resubmission.
        PlatformStore.Entity claimed;
        try {
            claimed = store.transact(tx -> {
                PlatformStore.Entity c = tx.require(actor.tenantId(), "case", caseId);
                if ("CLOSED".equals(c.data().get("status")) || !"APPROVED".equals(c.data().get("review_status"))
                        || !Objects.equals(c.data().get("current_decision_id"), reserved.data().get("decision_id"))
                        || !Objects.equals(c.data().get("evidence_snapshot_id"), reserved.data().get("evidence_snapshot_id"))) {
                    throw PlatformException.conflict("Approval changed before dispatch; reconcile the reservation");
                }
                validatePolicy(tx, actor.tenantId(), tx.require(actor.tenantId(), "decision", reserved.data().get("decision_id").toString()).data());
                Map<String, Object> data = new LinkedHashMap<>(reserved.data()); data.put("status", "SUBMITTED");
                data.put("submitted_at", Instant.now().toString());
                PlatformStore.Entity result = tx.update(actor.tenantId(), "compensation", key, reserved.version(), data, actor.userId(), "COMPENSATION_SUBMITTED");
                updateCase(tx, actor, c, result, "SUBMITTED");
                return result;
            });
        } catch (PlatformException conflict) {
            if (conflict.status() != 409) throw conflict;
            PlatformStore.Entity current=store.require(actor.tenantId(), "compensation", key);
            if("PENDING".equals(current.data().get("status")))throw conflict;
            return current;
        }
        try { return finish(actor, claimed.id(), channel.submit(claimed.id(), claimed.data())); }
        catch (RuntimeException unavailable) { return finish(actor, claimed.id(), new PaymentChannel.Result("UNKNOWN", null, "Channel result unavailable; reconcile before retry")); }
    }

    public PlatformStore.Entity reconcile(Principal actor, String id) {
        actor.requireRole("FUNDS", "AUDITOR");
        PlatformStore.Entity entry = get(actor, id);
        if (Set.of("SUCCEEDED", "FAILED").contains(entry.data().get("status"))) return entry;
        return finish(actor, id, channel.query(id, entry.data()));
    }

    public PlatformStore.Entity get(Principal actor, String id) {
        actor.requireRole("FUNDS", "AUDITOR", "SUPERVISOR");
        PlatformStore.Entity entry = store.require(actor.tenantId(), "compensation", id);
        cases.require(actor, CaseService.text(entry.data(), "case_id"));
        return entry;
    }

    public PlatformStore.Entity callback(Principal actor, String id, String eventId, String timestamp,
            String signature, Map<String, Object> payload) {
        actor.requireRole("PAYMENT_CALLBACK");
        if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,200}")) throw PlatformException.invalid("Invalid callback event identifier");
        if (callbackSecret.isBlank()) throw new PlatformException(409, "CALLBACK_DISABLED", "Signed callbacks have not been configured");
        Instant at;
        try { at = Instant.parse(timestamp); }
        catch (RuntimeException e) { throw PlatformException.invalid("Invalid callback timestamp"); }
        if (Math.abs(Instant.now().getEpochSecond() - at.getEpochSecond()) > 300) throw new PlatformException(401, "STALE_CALLBACK", "Callback timestamp is outside its validity window");
        String expected = sign(callbackSecret, actor.tenantId() + "\n" + timestamp + "\n" + eventId + "\n" + id + "\n" + PlatformStore.hash(payload));
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) throw new PlatformException(401, "INVALID_SIGNATURE", "Callback signature is invalid");
        return store.transact(tx -> {
            PlatformStore.Entity entry = tx.require(actor.tenantId(), "compensation", id);
            PlatformStore.Entity c = tx.require(actor.tenantId(), "case", CaseService.text(entry.data(), "case_id")); CaseService.authorize(actor, c);
            List<String> events = new ArrayList<>();
            if (entry.data().get("callback_events") instanceof List<?> values) values.forEach(value -> events.add(String.valueOf(value)));
            if (events.contains(eventId)) return entry;
            if (events.size() >= 1000) throw new PlatformException(429, "CALLBACK_EVENT_LIMIT", "Callback event retention limit reached");
            String status = CaseService.text(payload, "status");
            if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "SUBMITTED").contains(status)) throw PlatformException.invalid("Invalid channel status");
            BigDecimal received;
            try { received = new BigDecimal(String.valueOf(payload.get("amount"))).setScale(2, java.math.RoundingMode.UNNECESSARY); }
            catch (RuntimeException e) { throw PlatformException.invalid("Invalid callback amount"); }
            if (new BigDecimal(entry.data().get("amount").toString()).compareTo(received) != 0 || !Objects.equals(entry.data().get("currency"), payload.get("currency"))) throw PlatformException.conflict("Callback amount or currency does not match the ledger");
            events.add(eventId);
            Map<String, Object> data = new LinkedHashMap<>(entry.data()); data.put("callback_events", events);
            String old = String.valueOf(data.get("status"));
            if (Set.of("SUCCEEDED", "FAILED").contains(old)) {
                if (Set.of("SUCCEEDED", "FAILED").contains(status) && !old.equals(status)) throw PlatformException.conflict("Conflicting terminal result requires manual reconciliation");
            } else { data.put("status", status); data.put("external_id", payload.get("external_id")); }
            PlatformStore.Entity result = tx.update(actor.tenantId(), "compensation", id, entry.version(), data, actor.userId(), "SIGNED_CHANNEL_CALLBACK");
            updateCase(tx, actor, c, result, String.valueOf(data.get("status")));
            return result;
        });
    }

    private PlatformStore.Entity finish(Principal actor, String id, PaymentChannel.Result result) {
        if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "SUBMITTED").contains(result.status())) throw PlatformException.invalid("Invalid payment channel response");
        return store.transact(tx -> {
            PlatformStore.Entity entry = tx.require(actor.tenantId(), "compensation", id);
            if (Set.of("SUCCEEDED", "FAILED").contains(entry.data().get("status"))) return entry;
            Map<String, Object> data = new LinkedHashMap<>(entry.data()); data.put("status", result.status());
            data.put("external_id", result.externalId()); data.put("channel_note", result.note()); data.put("last_checked_at", Instant.now().toString());
            PlatformStore.Entity updated = tx.update(actor.tenantId(), "compensation", id, entry.version(), data, actor.userId(), "COMPENSATION_CHANNEL_RESULT");
            PlatformStore.Entity c = tx.require(actor.tenantId(), "case", CaseService.text(data, "case_id"));
            updateCase(tx, actor, c, updated, result.status());
            return updated;
        });
    }

    /** A stale observer must never release funds that another request has already dispatched. */
    PlatformStore.Entity failUndispatched(Principal actor, String id, Map<String,Object> request, String reason) {
        return store.transact(tx -> {
            PlatformStore.Entity entry = tx.lock(actor.tenantId(), "compensation", id);
            validateRetry(entry, request);
            PlatformStore.Entity c = tx.require(actor.tenantId(), "case", CaseService.text(entry.data(), "case_id"));
            CaseService.authorize(actor, c);
            if (!"PENDING".equals(entry.data().get("status"))) return entry;
            Map<String,Object> data = new LinkedHashMap<>(entry.data());
            data.put("status", "FAILED"); data.put("channel_note", reason); data.put("last_checked_at", Instant.now().toString());
            PlatformStore.Entity released = tx.update(actor.tenantId(), "compensation", id, entry.version(), data,
                    actor.userId(), "COMPENSATION_RESERVATION_RELEASED");
            updateCase(tx, actor, c, released, "FAILED");
            return released;
        });
    }

    private static void updateCase(PlatformStore.Tx tx, Principal actor, PlatformStore.Entity c, PlatformStore.Entity ledger, String status) {
        if (!ledger.id().equals(c.data().get("compensation_id"))
                || !Objects.equals(c.data().get("current_decision_id"), ledger.data().get("decision_id"))
                || !Objects.equals(c.data().get("evidence_snapshot_id"), ledger.data().get("evidence_snapshot_id"))) return;
        Map<String, Object> data = new LinkedHashMap<>(c.data()); data.put("payment_status", status);
        tx.update(actor.tenantId(), "case", c.id(), c.version(), data, actor.userId(), "CASE_PAYMENT_STATE");
    }

    private static void validateRetry(PlatformStore.Entity entry, Map<String, Object> request) {
        if (!Objects.equals(entry.data().get("case_id"), request.get("case_id"))
                || !Objects.equals(entry.data().get("decision_id"), request.get("decision_id"))
                || CaseService.number(entry.data(), "decision_version") != CaseService.number(request, "decision_version")) {
            throw PlatformException.conflict("Compensation identifier cannot be reused for different approval content");
        }
    }

    private void recordRecoveryFailure(Principal actor,String id,RuntimeException failure) {
        try { store.transact(tx -> {
            PlatformStore.Entity current=tx.require(actor.tenantId(),"compensation",id);
            if(Set.of("SUCCEEDED","FAILED").contains(current.data().get("status")))return null;
            Map<String,Object> data=new LinkedHashMap<>(current.data());data.put("last_checked_at",Instant.now().toString());
            data.put("recovery_error_code",failure instanceof PlatformException e?e.code():"CHANNEL_UNAVAILABLE");
            tx.update(actor.tenantId(),"compensation",id,current.version(),data,actor.userId(),"COMPENSATION_RECOVERY_DEFERRED");return null;
        }); } catch(RuntimeException databaseUnavailable) { /* The next scan retries once the store recovers. */ }
    }

    private static String canonicalMethod(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "REFUND", "原路退款", "退款" -> "REFUND";
            case "COUPON", "发放优惠券", "优惠券" -> "COUPON";
            case "RED_PACKET", "红包", "发放红包" -> "RED_PACKET";
            default -> value;
        };
    }

    private static void validatePolicy(PlatformStore.Tx tx, String tenant, Map<String, Object> decision) {
        if (Boolean.TRUE.equals(decision.get("manual_exception"))) {
            CaseService.text(decision, "authorization_basis");
            return;
        }
        Object version = decision.get("policy_version");
        if (version == null || !"PUBLISHED".equals(tx.lock(tenant, "policy", version.toString()).data().get("state"))) {
            throw PlatformException.conflict("The approved policy is no longer published; re-authorize before funds execution");
        }
    }

    public static String sign(String secret, String text) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
