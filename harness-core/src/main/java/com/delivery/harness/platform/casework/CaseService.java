package com.delivery.harness.platform.casework;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class CaseService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.delivery.harness.platform.auth.PrincipalDirectory directory;
    @org.springframework.beans.factory.annotation.Value("${harness.guardrail.max-compensation-amount:50.0}") private java.math.BigDecimal approvalCeiling=new java.math.BigDecimal("50");
    private final PlatformStore store;
    private final OrderSource orders;
    public CaseService(PlatformStore store,OrderSource orders) {this.store=store;this.orders=orders;}
    public PlatformStore.Entity create(Principal actor,Map<String,Object> input) {
        actor.requireRole("AGENT","OPERATOR");
        String orderId=text(input,"order_id"); String complaint=String.valueOf(input.getOrDefault("complaint_type","OVERTIME"));
        if(!Set.of("OVERTIME","DAMAGED","MISSING_ITEM","WRONG_ORDER").contains(complaint)) throw PlatformException.invalid("Unsupported complaint_type");
        OrderSource.Snapshot source=orders.query(actor,orderId);
        return store.transact(tx -> {
            String id=UUID.randomUUID().toString(); String snapshotId=UUID.randomUUID().toString();
            List<String> duplicates=tx.list(actor.tenantId(),"case").stream().filter(c -> orderId.equals(c.data().get("order_id")) && complaint.equals(c.data().get("complaint_type")))
                    .filter(c -> actor.canAccess(string(c.data(),"city_code"),string(c.data(),"station_id"))).map(PlatformStore.Entity::id).toList();
            Map<String,Object> snapshot=snapshot(source,id);
            tx.create(actor.tenantId(),"order_snapshot",snapshotId,snapshot,actor.userId(),"ORDER_SNAPSHOT_CAPTURED");
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("order_id",orderId); data.put("complaint_type",complaint); data.put("source_ticket_id",input.getOrDefault("source_ticket_id",""));
            data.put("title",input.getOrDefault("title",orderId + " " + complaint)); data.put("city_code",source.order().getCityCode()); data.put("station_id",source.order().getStationId());
            data.put("created_by",actor.userId());data.put("assignee",null); data.put("status","OPEN"); data.put("review_status","PENDING"); data.put("risk_level","NORMAL");
            data.put("evidence_snapshot_id",snapshotId); data.put("evidence_revision",1); data.put("duplicate_case_ids",duplicates);
            data.put("payment_status","NOT_EXECUTED"); data.put("due_at",Instant.now().plusSeconds(86400).toString());
            return tx.create(actor.tenantId(),"case",id,data,actor.userId(),"CASE_CREATED");
        });
    }
    public PlatformStore.Entity require(Principal actor,String id) {
        actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR","QUALITY","AUDITOR","FUNDS");
        PlatformStore.Entity entity=store.require(actor.tenantId(),"case",id);authorize(actor,entity);return entity;
    }
    public List<PlatformStore.Entity> list(Principal actor) {
        actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR","AUDITOR");
        return store.list(actor.tenantId(),"case").stream().filter(c -> actor.canAccess(string(c.data(),"city_code"),string(c.data(),"station_id"))).toList();
    }
    public Map<String,Object> detail(Principal actor,String id) {
        PlatformStore.Entity entity=require(actor,id); Map<String,Object> result=view(entity);
        result.put("materials",store.list(actor.tenantId(),"material").stream().filter(m -> id.equals(m.data().get("case_id"))).map(CaseService::publicMaterial).toList());
        result.put("review_history",store.list(actor.tenantId(),"review_action").stream().filter(m -> id.equals(m.data().get("case_id"))).map(CaseService::view).toList());
        result.put("evidence_snapshot",com.delivery.harness.platform.auth.Redaction.map(view(store.require(actor.tenantId(),"order_snapshot",entity.data().get("evidence_snapshot_id").toString()))));
        if(entity.data().get("current_decision_id")!=null) result.put("decision",view(store.require(actor.tenantId(),"decision",entity.data().get("current_decision_id").toString())));
        if(entity.data().get("review_id")!=null) result.put("review",view(store.require(actor.tenantId(),"review",entity.data().get("review_id").toString())));
        return result;
    }
    public PlatformStore.Entity addEvidence(Principal actor,String caseId,Map<String,Object> input) {
        actor.requireRole("AGENT","OPERATOR"); require(actor,caseId);
        String hash=text(input,"file_hash"); if(!hash.matches("[a-fA-F0-9]{64}")) throw PlatformException.invalid("file_hash must be SHA-256 hex");
        String mime=text(input,"mime_type"); if(!Set.of("image/png","image/jpeg","application/pdf","text/plain").contains(mime)) throw PlatformException.invalid("Unsupported material type");
        long size=number(input,"size_bytes"); if(size<1 || size>10_000_000) throw PlatformException.invalid("Material size must be between 1 and 10 MB");
        Map<String,Object> data=new LinkedHashMap<>(); data.put("case_id",caseId); data.put("file_hash",hash.toLowerCase(Locale.ROOT)); data.put("mime_type",mime); data.put("size_bytes",size);
        data.put("source",input.getOrDefault("source","MANUAL")); data.put("description",input.getOrDefault("description","")); data.put("uploaded_by",actor.userId()); data.put("status","UPLOADED");
        if(input.get("content_base64")!=null) {
            byte[] bytes;try {bytes=Base64.getDecoder().decode(input.get("content_base64").toString());}catch(IllegalArgumentException e){throw PlatformException.invalid("Invalid material encoding");}
            if(bytes.length!=size || size>1_000_000) throw PlatformException.invalid("Inline material limit is 1 MB and size must match");
            try {
                String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                if(!digest.equalsIgnoreCase(hash)) throw PlatformException.invalid("Material hash does not match content");
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            validateContent(mime,bytes); data.put("content_base64",input.get("content_base64"));data.put("content_checked",true);
        } else {
            String reference=text(input,"reference_uri");
            if(!reference.startsWith("https://")) throw PlatformException.invalid("Material reference must use HTTPS");
            data.put("reference_uri",reference);data.put("content_checked",false);
        }
        if(input.get("damage_level")!=null) {
            if(!Set.of("PARTIAL","FULL").contains(input.get("damage_level"))) throw PlatformException.invalid("Invalid damage_level");
            data.put("damage_level",input.get("damage_level"));
        }
        return store.transact(tx -> {
            PlatformStore.Entity c=tx.require(actor.tenantId(),"case",caseId); authorize(actor,c);requireNoActivePayment(c);
            PlatformStore.Entity material=tx.create(actor.tenantId(),"material",UUID.randomUUID().toString(),data,actor.userId(),"MATERIAL_UPLOADED");
            invalidateEvidence(tx,actor,c,"MATERIAL_ADDED"); return material;
        });
    }
    public PlatformStore.Entity reviewMaterial(Principal actor,String materialId,Map<String,Object> input) {
        actor.requireRole("REVIEWER","SUPERVISOR"); PlatformStore.Entity material=store.require(actor.tenantId(),"material",materialId);
        require(actor,text(material.data(),"case_id")); String action=text(input,"action"); String reason=text(input,"reason");
        if(!Set.of("VERIFY","REJECT").contains(action)) throw PlatformException.invalid("Material action must be VERIFY or REJECT");
        return store.transact(tx -> {
            PlatformStore.Entity m=tx.require(actor.tenantId(),"material",materialId);
            PlatformStore.Entity related=tx.require(actor.tenantId(),"case",text(m.data(),"case_id"));authorize(actor,related);requireNoActivePayment(related);
            if("VERIFY".equals(action) && (!Boolean.TRUE.equals(m.data().get("content_checked")) || m.data().get("content_base64")==null))throw PlatformException.conflict("External material must be uploaded and frozen with verified content hash before verification");
            if(actor.userId().equals(m.data().get("uploaded_by"))) throw new PlatformException(403,"SEPARATION_OF_DUTIES","A different reviewer must verify uploaded material");
            Map<String,Object> data=new LinkedHashMap<>(m.data()); data.put("status","VERIFY".equals(action)?"VERIFIED":"REJECTED");data.put("reviewed_by",actor.userId());data.put("review_note",reason);
            PlatformStore.Entity updated=tx.update(actor.tenantId(),"material",materialId,number(input,"expected_version"),data,actor.userId(),"MATERIAL_" + action);
            invalidateEvidence(tx,actor,tx.require(actor.tenantId(),"case",text(data,"case_id")),"MATERIAL_REVIEWED"); return updated;
        });
    }
    public PlatformStore.Entity claim(Principal actor,String reviewId,long expected) {
        actor.requireRole("REVIEWER","SUPERVISOR");
        return store.transact(tx -> {
            PlatformStore.Entity review=tx.require(actor.tenantId(),"review",reviewId); PlatformStore.Entity c=tx.require(actor.tenantId(),"case",text(review.data(),"case_id"));authorize(actor,c);
            if(!"PENDING".equals(review.data().get("status"))) throw PlatformException.conflict("Review has already been claimed or superseded");
            Map<String,Object> data=new LinkedHashMap<>(review.data()); data.put("status","IN_REVIEW");data.put("owner",actor.userId());data.put("claimed_at",Instant.now().toString());
            PlatformStore.Entity result=tx.update(actor.tenantId(),"review",reviewId,expected,data,actor.userId(),"REVIEW_CLAIMED");
            Map<String,Object> cd=new LinkedHashMap<>(c.data());cd.put("status","IN_REVIEW");cd.put("review_status","IN_REVIEW");cd.put("assignee",actor.userId());tx.update(actor.tenantId(),"case",c.id(),c.version(),cd,actor.userId(),"CASE_REVIEW_STARTED");return result;
        });
    }
    public PlatformStore.Entity act(Principal actor,String reviewId,Map<String,Object> input) {
        actor.requireRole("REVIEWER","SUPERVISOR"); String action=text(input,"action"); String reason=text(input,"reason");
        if(!Set.of("APPROVE","REJECT","REQUEST_EVIDENCE","REASSIGN").contains(action)) throw PlatformException.invalid("Unsupported review action");
        return store.transact(tx -> {
            PlatformStore.Entity review=tx.require(actor.tenantId(),"review",reviewId); PlatformStore.Entity c=tx.require(actor.tenantId(),"case",text(review.data(),"case_id"));authorize(actor,c);requireNoActivePayment(c);
            if(!"IN_REVIEW".equals(review.data().get("status")) || !actor.userId().equals(review.data().get("owner"))) throw PlatformException.conflict("Only the current review owner may submit an action");
            if(!Objects.equals(c.data().get("current_decision_id"),input.get("decision_id")) || !Objects.equals(c.data().get("evidence_snapshot_id"),input.get("evidence_snapshot_id"))
                    || !Objects.equals(c.data().get("review_id"),reviewId)) throw PlatformException.conflict("Decision or evidence changed; refresh before review");
            PlatformStore.Entity decision=tx.require(actor.tenantId(),"decision",text(input,"decision_id"));
            if(decision.version()!=number(input,"decision_version") || !Objects.equals(decision.data().get("evidence_snapshot_id"),input.get("evidence_snapshot_id"))) throw PlatformException.conflict("Approval must bind the current immutable decision and evidence");
            if("APPROVE".equals(action)) {
                if(!"DETERMINED".equals(decision.data().get("decision_status"))) throw PlatformException.conflict("Insufficient or conflicting evidence cannot be approved");
                Object amount=decision.data().get("amount");if(amount instanceof Map<?,?> money)amount=money.get("value");if(amount==null)amount=decision.data().get("authoritative_amount");
                if(amount!=null){java.math.BigDecimal monetary=new java.math.BigDecimal(amount.toString());if(monetary.signum()<0 || monetary.compareTo(approvalCeiling)>0 || monetary.stripTrailingZeros().scale()>2)throw PlatformException.conflict("Approval amount is outside the configured monetary policy; create an authorized exception");}
                else if("compensation_suggestion".equals(decision.data().get("analysis_type")))throw PlatformException.conflict("A monetary suggestion with no authoritative amount cannot be approved");
                if("HIGH".equals(c.data().get("risk_level"))) actor.requireRole("SUPERVISOR");
                if(Boolean.TRUE.equals(decision.data().get("manual_exception")) && actor.userId().equals(decision.data().get("created_by"))) throw new PlatformException(403,"SEPARATION_OF_DUTIES","An exception author cannot approve their own exception");
            }
            String status=switch(action){case "APPROVE" -> "APPROVED";case "REJECT" -> "REJECTED";case "REQUEST_EVIDENCE" -> "NEEDS_EVIDENCE";default -> "IN_REVIEW";};
            Map<String,Object> rd=new LinkedHashMap<>(review.data());rd.put("status",status);rd.put("last_action",action);
            if("REASSIGN".equals(action)) {
                if(directory==null)throw new PlatformException(503,"IDENTITY_DIRECTORY_UNAVAILABLE","Reviewer identity directory is unavailable");
                directory.reviewer(actor.tenantId(),text(input,"target_user_id"),string(c.data(),"city_code"),string(c.data(),"station_id"));
                rd.put("owner",text(input,"target_user_id"));
            }
            tx.update(actor.tenantId(),"review",reviewId,number(input,"expected_version"),rd,actor.userId(),"REVIEW_"+action);
            Map<String,Object> cd=new LinkedHashMap<>(c.data());cd.put("status",status);cd.put("review_status",status);cd.put("payment_status","NOT_EXECUTED");if("REASSIGN".equals(action))cd.put("assignee",input.get("target_user_id"));
            tx.update(actor.tenantId(),"case",c.id(),c.version(),cd,actor.userId(),"CASE_"+action);
            Map<String,Object> record=new LinkedHashMap<>(); record.put("case_id",c.id());record.put("review_id",reviewId);record.put("action",action);record.put("reason",reason);record.put("actor",actor.userId());
            if("REASSIGN".equals(action)){record.put("from_user_id",review.data().get("owner"));record.put("target_user_id",input.get("target_user_id"));}
            record.put("decision_id",decision.id());record.put("decision_version",decision.version());record.put("evidence_snapshot_id",input.get("evidence_snapshot_id"));record.put("payment_status","NOT_EXECUTED");
            return tx.create(actor.tenantId(),"review_action",UUID.randomUUID().toString(),record,actor.userId(),"REVIEW_ACTION_RECORDED");
        });
    }
    public List<Map<String,Object>> reviewers(Principal actor,String caseId){actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR");PlatformStore.Entity c=require(actor,caseId);if(directory==null)throw new PlatformException(503,"IDENTITY_DIRECTORY_UNAVAILABLE","Reviewer identity directory is unavailable");return directory.reviewers(actor.tenantId(),string(c.data(),"city_code"),string(c.data(),"station_id")).stream().map(p -> Map.<String,Object>of("user_id",p.userId(),"roles",p.roles(),"city_codes",p.cityCodes(),"station_ids",p.stationIds())).toList();}
    public PlatformStore.Entity close(Principal actor,String caseId,Map<String,Object> input) {
        actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR");require(actor,caseId);
        String reason=text(input,"reason");
        return store.transact(tx -> {
            PlatformStore.Entity c=tx.require(actor.tenantId(),"case",caseId);authorize(actor,c);requireNoActivePayment(c);
            if(!Set.of("APPROVED","REJECTED").contains(c.data().get("status")))throw PlatformException.conflict("Only reviewed cases may be closed");
            Map<String,Object> data=new LinkedHashMap<>(c.data());data.put("status","CLOSED");data.put("closed_by",actor.userId());data.put("closed_at",Instant.now().toString());data.put("close_reason",reason);
            if(input.get("external_receipt")!=null){data.put("external_receipt",input.get("external_receipt"));data.put("external_handling_status","MANUAL_EXTERNAL_CONFIRMATION");}
            return tx.update(actor.tenantId(),"case",caseId,number(input,"expected_version"),data,actor.userId(),"CASE_CLOSED");
        });
    }
    public boolean verifiedDamage(Principal actor,String caseId,String damageLevel) {
        return damageLevel!=null && store.list(actor.tenantId(),"material").stream().anyMatch(m -> caseId.equals(m.data().get("case_id")) && "VERIFIED".equals(m.data().get("status")) && Boolean.TRUE.equals(m.data().get("content_checked")) && m.data().get("content_base64")!=null && damageLevel.equals(m.data().get("damage_level")));
    }
    private void invalidateEvidence(PlatformStore.Tx tx,Principal actor,PlatformStore.Entity c,String action) {
        requireNoActivePayment(c);
        Map<String,Object> data=new LinkedHashMap<>(c.data());data.put("evidence_revision",((Number)data.getOrDefault("evidence_revision",1)).longValue()+1);data.put("status","NEEDS_EVIDENCE");data.put("review_status","NEEDS_EVIDENCE");data.put("payment_status","NOT_EXECUTED");
        // A new immutable evidence aggregate invalidates approval binding immediately.
        PlatformStore.Entity previous=tx.require(actor.tenantId(),"order_snapshot",text(c.data(),"evidence_snapshot_id")); Map<String,Object> snapshot=new LinkedHashMap<>(previous.data());
        snapshot.put("material_versions",tx.list(actor.tenantId(),"material").stream().filter(m -> c.id().equals(m.data().get("case_id"))).map(m -> Map.of("id",m.id(),"version",m.version(),"status",m.data().get("status"))).toList());
        String snapshotId=UUID.randomUUID().toString();tx.create(actor.tenantId(),"order_snapshot",snapshotId,snapshot,actor.userId(),"EVIDENCE_SNAPSHOT_CREATED");data.put("evidence_snapshot_id",snapshotId);
        if(c.data().get("review_id")!=null) {PlatformStore.Entity r=tx.require(actor.tenantId(),"review",c.data().get("review_id").toString());Map<String,Object> rd=new LinkedHashMap<>(r.data());rd.put("status","SUPERSEDED");tx.update(actor.tenantId(),"review",r.id(),r.version(),rd,actor.userId(),"APPROVAL_INVALIDATED");}
        tx.update(actor.tenantId(),"case",c.id(),c.version(),data,actor.userId(),action);
    }
    public static void requireNoActivePayment(PlatformStore.Entity entity) {
        if(Set.of("PENDING","SUBMITTED","UNKNOWN","SUCCEEDED").contains(entity.data().get("payment_status")))throw PlatformException.conflict("Compensation is in progress or completed; preserve its approved snapshot and reconcile before modifying the case");
    }
    public static void authorize(Principal actor,PlatformStore.Entity entity) {actor.authorizeScope(string(entity.data(),"city_code"),string(entity.data(),"station_id"));}
    public static Map<String,Object> snapshot(OrderSource.Snapshot source,String caseId) {Map<String,Object> data=new LinkedHashMap<>(source.metadata());data.put("case_id",caseId);data.put("order",JsonUtil.toMap(source.order()));return data;}
    public static Map<String,Object> view(PlatformStore.Entity entity) {Map<String,Object> view=new LinkedHashMap<>(entity.data());view.put("id",entity.id());view.put("version",entity.version());view.put("created_at",entity.createdAt());view.put("updated_at",entity.updatedAt());return view;}
    public static Map<String,Object> publicMaterial(PlatformStore.Entity entity) {Map<String,Object> view=view(entity);view.remove("content_base64");return view;}
    public static String text(Map<String,Object> input,String key) {Object value=input.get(key);if(value==null || value.toString().isBlank())throw PlatformException.invalid(key + " is required");return value.toString();}
    public static String string(Map<String,Object> input,String key) {Object value=input.get(key);return value==null?null:value.toString();}
    public static long number(Map<String,Object> input,String key) {try{return Long.parseLong(text(input,key));}catch(NumberFormatException e){throw PlatformException.invalid(key + " must be an integer");}}
    private static void validateContent(String mime,byte[] content) {
        boolean valid=switch(mime) {case "image/png" -> content.length>=8 && content[0]==(byte)137 && content[1]==80 && content[2]==78 && content[3]==71;
            case "image/jpeg" -> content.length>=3 && content[0]==(byte)255 && content[1]==(byte)216 && content[2]==(byte)255;
            case "application/pdf" -> content.length>=5 && new String(content,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
            case "text/plain" -> new String(content,java.nio.charset.StandardCharsets.UTF_8).chars().noneMatch(c -> c==0); default -> false;};
        if(!valid) throw PlatformException.invalid("Material content does not match declared type");
        String text=new String(content,java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if(text.contains("<script") || text.contains("/javascript") || text.contains("/launch")) throw PlatformException.invalid("Active material content is not supported");
    }
}
