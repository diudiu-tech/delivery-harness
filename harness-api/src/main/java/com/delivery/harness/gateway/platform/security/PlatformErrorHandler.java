package com.delivery.harness.gateway.platform.security;
import com.delivery.harness.platform.store.PlatformException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformErrorHandler {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.delivery.harness.platform.store.PlatformStore store;
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<Map<String,Object>> handle(PlatformException exception,jakarta.servlet.http.HttpServletRequest request) {
        request.setAttribute("harness.denial_code",exception.code());
        if(store!=null && java.util.Set.of(403,404).contains(exception.status()) && request.getAttribute(PrincipalAccess.ATTRIBUTE) instanceof com.delivery.harness.platform.auth.AuthContext.Principal actor) {
            try{store.transact(tx -> {tx.audit(actor.tenantId(),actor.userId(),"ACCESS_DENIED","request:"+com.delivery.harness.platform.store.PlatformStore.hash(request.getRequestURI()),Map.of("path",request.getRequestURI(),"method",request.getMethod(),"status",exception.status(),"reason",exception.code()));return null;});request.setAttribute("harness.denial_audited",true);}
            catch(PlatformException failure){return ResponseEntity.status(503).body(Map.of("code","AUDIT_UNAVAILABLE","message","Access refusal cannot be durably recorded","retryable",true));}
        }
        return ResponseEntity.status(exception.status()).body(Map.of("code",exception.code(),"message",exception.getMessage(),"retryable",exception.status()==429 || exception.status()==503));
    }
}
