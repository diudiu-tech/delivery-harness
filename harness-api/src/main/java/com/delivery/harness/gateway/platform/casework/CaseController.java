package com.delivery.harness.gateway.platform.casework;
import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/v2")
public class CaseController {
    private final CaseService cases;
    private final PlatformStore store;
    public CaseController(CaseService cases,PlatformStore store){this.cases=cases;this.store=store;}
    @PostMapping("/cases") public ResponseEntity<?> create(@RequestBody Map<String,Object> input,HttpServletRequest request){return ResponseEntity.status(201).body(CaseService.view(cases.create(PrincipalAccess.get(request),input)));}
    @GetMapping("/cases") public Map<String,Object> list(@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="50") int limit,HttpServletRequest request){
        var actor=PrincipalAccess.get(request);actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR","AUDITOR");
        PlatformStore.Page page=store.caseQueue(actor,cursor,limit);
        return Map.of("items",page.items().stream().map(CaseService::view).toList(),"cursor",page.cursor());
    }
    @GetMapping("/cases/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest request){return Redaction.map(cases.detail(PrincipalAccess.get(request),id));}
    @PostMapping("/cases/{id}/evidence") public ResponseEntity<?> evidence(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ResponseEntity.status(201).body(CaseService.publicMaterial(cases.addEvidence(PrincipalAccess.get(request),id,input)));}
    @PostMapping("/cases/{id}/close") public Map<String,Object> close(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request){return CaseService.view(cases.close(PrincipalAccess.get(request),id,input));}
    @PostMapping("/materials/{id}/review") public Map<String,Object> verify(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request){return CaseService.publicMaterial(cases.reviewMaterial(PrincipalAccess.get(request),id,input));}
    @GetMapping("/materials/{id}/content") public ResponseEntity<byte[]> content(@PathVariable String id,HttpServletRequest request){
        var actor=PrincipalAccess.get(request);var material=store.require(actor.tenantId(),"material",id);cases.require(actor,CaseService.text(material.data(),"case_id"));
        if(!Boolean.TRUE.equals(material.data().get("content_checked")) || material.data().get("content_base64")==null)throw new com.delivery.harness.platform.store.PlatformException(409,"MATERIAL_CONTENT_NOT_FROZEN","Upload and verify immutable content before downloading this material");
        String base64=CaseService.text(material.data(),"content_base64");
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=material-"+id).contentType(MediaType.parseMediaType(CaseService.text(material.data(),"mime_type"))).body(Base64.getDecoder().decode(base64));
    }
    @GetMapping("/reviewers") public Map<String,Object> reviewers(@RequestParam("case_id") String caseId,HttpServletRequest request){return Map.of("items",cases.reviewers(PrincipalAccess.get(request),caseId));}
    @GetMapping("/reviews") public Map<String,Object> reviews(@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="50") int limit,HttpServletRequest request){
        var actor=PrincipalAccess.get(request);actor.requireRole("REVIEWER","SUPERVISOR","AUDITOR");var page=store.page(actor.tenantId(),"review",cursor,limit);
        List<Map<String,Object>> items=page.stream().filter(r -> {try{cases.require(actor,CaseService.text(r.data(),"case_id"));return true;}catch(com.delivery.harness.platform.store.PlatformException denied){return false;}}).map(CaseService::view).toList();
        return Map.of("items",items,"cursor",page.size()==limit?page.get(page.size()-1).id():"");
    }
    @PostMapping("/reviews/{id}/claim") public Map<String,Object> claim(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request){return CaseService.view(cases.claim(PrincipalAccess.get(request),id,CaseService.number(input,"expected_version")));}
    @PostMapping("/reviews/{id}/actions") public Map<String,Object> action(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request){return CaseService.view(cases.act(PrincipalAccess.get(request),id,input));}
}
