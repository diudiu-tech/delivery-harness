package com.delivery.harness.platform.compensation;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

/** Generic opt-in channel contract: idempotent POST, read-only GET, and signed callbacks. */
@Component
public class ConfiguredPaymentChannel implements PaymentChannel {
    private final String endpoint;
    private final String token;
    private final boolean demo;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public ConfiguredPaymentChannel(@Value("${harness.mode:demo}") String mode,
            @Value("${harness.compensation.execution-enabled:false}") boolean enabled,
            @Value("${harness.compensation.channel-endpoint:}") String endpoint,
            @Value("${harness.compensation.channel-token:}") String token,
            @Value("${harness.compensation.allowed-hosts:}") String hosts,
            @Value("${harness.compensation.callback-secret:}") String callbackSecret) {
        this.endpoint = endpoint.replaceAll("/+$", ""); this.token = token; this.demo = !"pilot".equals(mode);
        if (enabled && !demo) {
            URI uri;
            try { uri = URI.create(endpoint); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid payment channel endpoint"); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || !Arrays.asList(hosts.split(",")).contains(uri.getHost()) || token.isBlank() || callbackSecret.length() < 32) {
                throw new IllegalStateException("Enabled pilot execution requires an allowlisted HTTPS channel, credential and at least 32-character callback secret");
            }
        }
    }
    @Override public Result submit(String id, Map<String, Object> approved) {
        if (demo) return new Result("SUCCEEDED", "synthetic-" + id, "Synthetic demonstration only; no money moved");
        return call("POST", id, approved);
    }
    @Override public Result query(String id, Map<String, Object> approved) {
        if (demo) return new Result("SUCCEEDED", "synthetic-" + id, "Synthetic demonstration only; no money moved");
        return call("GET", id, approved);
    }
    @Override public String name() { return demo ? "SYNTHETIC" : "CONFIGURED_HTTP"; }
    @Override public boolean synthetic() { return demo; }
    @Override public Eligibility eligibility(String tenantId, String orderId) {
        if (demo) return PaymentChannel.super.eligibility(tenantId, orderId);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/orders/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8) + "/eligibility"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token)
                    .header("X-Tenant-ID", tenantId).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 65536) throw new IllegalStateException("Eligibility unavailable");
            Map<String, Object> data = JsonUtil.fromJson(response.body(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (!Boolean.TRUE.equals(data.get("idempotent_submit")) || !Boolean.TRUE.equals(data.get("query_supported"))) {
                throw new IllegalStateException("Channel must guarantee idempotent submission and query");
            }
            return new Eligibility(String.valueOf(data.get("order_id")), String.valueOf(data.get("currency")), String.valueOf(data.get("order_status")),
                    new BigDecimal(String.valueOf(data.get("remaining_refund"))), new BigDecimal(String.valueOf(data.get("remaining_compensation"))),
                    String.valueOf(data.get("version")), Instant.parse(String.valueOf(data.get("observed_at"))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new PlatformException(503, "CHANNEL_ELIGIBILITY_UNAVAILABLE", "Eligibility request interrupted; no payment reserved");
        } catch (java.io.IOException | RuntimeException e) {
            throw new PlatformException(503, "CHANNEL_ELIGIBILITY_UNAVAILABLE", "Current order state and remaining funds could not be verified; no payment reserved");
        }
    }
    private Result call(String method, String id, Map<String, Object> approved) {
        try {
            String path = endpoint + "/compensations" + ("GET".equals(method) ? "/" + URLEncoder.encode(id, StandardCharsets.UTF_8) : "");
            Map<String, Object> payload = new LinkedHashMap<>();
            for (String key : List.of("amount", "currency", "method", "order_id", "decision_id", "decision_version", "eligibility_version")) payload.put(key, approved.get(key));
            payload.put("compensation_id", id); payload.put("approval_hash", PlatformStore.hash(approved));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token).header("X-Tenant-ID", String.valueOf(approved.get("tenant_id"))).header("Accept", "application/json");
            if ("POST".equals(method)) request.header("Content-Type", "application/json").header("Idempotency-Key", approved.get("tenant_id") + ":" + id).POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(payload)));
            else request.GET();
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > 65536) return new Result("UNKNOWN", null, "Channel must be reconciled before any retry");
            Map<String, Object> data = JsonUtil.fromJson(response.body(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (!id.equals(data.get("compensation_id")) || new BigDecimal(String.valueOf(payload.get("amount"))).compareTo(new BigDecimal(String.valueOf(data.get("amount")))) != 0 || !Objects.equals(payload.get("currency"), data.get("currency"))) return new Result("UNKNOWN", null, "Channel returned mismatched monetary fields or identifier");
            return new Result(String.valueOf(data.get("status")), data.get("external_id") == null ? null : data.get("external_id").toString(), "Channel response recorded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return new Result("UNKNOWN", null, "Channel request interrupted");
        } catch (java.io.IOException | RuntimeException e) { return new Result("UNKNOWN", null, "Channel unavailable; reconcile before retry"); }
    }
}
