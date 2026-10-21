package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
@Order(1)
public class PlatformSecurityFilter extends OncePerRequestFilter {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.delivery.harness.platform.store.PlatformStore store;
    private final String mode;
    private final List<Identity> identities;
    private final AuthContext.Principal demo = new AuthContext.Principal("demo","demo-agent",
            Set.of("AGENT","OPERATOR","REVIEWER","SUPERVISOR","POLICY_EDITOR","POLICY_PUBLISHER","QUALITY","AUDITOR","ADMIN"),Set.of("*"),Set.of("*"));
    private record Identity(byte[] token, AuthContext.Principal principal) {}
    public PlatformSecurityFilter(@Value("${harness.mode:demo}") String mode,
                                  @Value("${harness.security.tokens:[]}") String tokens,
                                  @Value("${spring.datasource.url:}") String jdbc,
                                  @Value("${harness.adapter.order-endpoint:}") String endpoint,
                                  @Value("${harness.adapter.allowed-hosts:}") String allowedHosts) {
        if (!Set.of("demo","pilot","test").contains(mode)) throw new IllegalArgumentException("harness.mode must be demo or pilot");
        this.mode = mode;
        List<Map<String,Object>> claims = JsonUtil.fromJson(tokens,new TypeReference<>() {});
        List<Identity> configuredIdentities = claims.stream().map(claim -> new Identity(required(claim,"token").getBytes(StandardCharsets.UTF_8),
                new AuthContext.Principal(required(claim,"tenant_id"),required(claim,"user_id"),strings(claim.get("roles")),strings(claim.get("city_codes")),strings(claim.get("station_ids"))))).toList();
        List<Identity> accepted=new ArrayList<>(configuredIdentities);if(!"pilot".equals(mode))com.delivery.harness.platform.auth.PrincipalDirectory.demoCredentials().forEach((credential,principal) -> accepted.add(new Identity(credential.getBytes(StandardCharsets.UTF_8),principal)));identities=List.copyOf(accepted);
        if ("pilot".equals(mode) && (identities.isEmpty() || jdbc.isBlank() || jdbc.contains(":mem:") || endpoint.isBlank() || allowedHosts.isBlank())) {
            throw new IllegalStateException("pilot requires configured tokens, persistent JDBC URL, read-only order endpoint and allowed hosts");
        }
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String auth = request.getHeader("Authorization");
            AuthContext.Principal principal;
            if (auth == null && !"pilot".equals(mode)) principal = demo;
            else {
                if (auth == null || !auth.startsWith("Bearer ")) throw new PlatformException(401,"UNAUTHENTICATED","A configured bearer token is required");
                byte[] supplied = auth.substring(7).getBytes(StandardCharsets.UTF_8);
                principal = identities.stream().filter(identity -> MessageDigest.isEqual(identity.token(),supplied)).map(Identity::principal).findFirst()
                        .orElseThrow(() -> new PlatformException(401,"UNAUTHENTICATED","Invalid bearer token"));
            }
            request.setAttribute(PrincipalAccess.ATTRIBUTE,principal);
            if ("pilot".equals(mode) && request.getRequestURI().startsWith("/api/v1/")) throw new PlatformException(410,"V2_REQUIRED","pilot uses /api/v2 with scoped persistent resources");
            request.setAttribute(PrincipalAccess.ATTRIBUTE,principal);
            chain.doFilter(request,response);
            if(Set.of(401,403,404).contains(response.getStatus()))auditDenied(request,response,principal,String.valueOf(request.getAttribute("harness.denial_code")));
        } catch (PlatformException e) {
            response.setStatus(e.status());
            AuthContext.Principal authenticated=request.getAttribute(PrincipalAccess.ATTRIBUTE) instanceof AuthContext.Principal known?known:null;
            try{auditDenied(request,response,authenticated,e.code());}catch(PlatformException unavailable){response.setStatus(503);response.setContentType("application/json");response.getWriter().write(JsonUtil.toJson(Map.of("code","AUDIT_UNAVAILABLE","message","Access denial could not be durably recorded")));return;}
            response.setStatus(e.status()); response.setContentType("application/json");
            response.getWriter().write(JsonUtil.toJson(Map.of("code",e.code(),"message",e.getMessage())));
        }
    }
    private void auditDenied(HttpServletRequest request,HttpServletResponse response,AuthContext.Principal principal,String reason) {
        if(store==null || Boolean.TRUE.equals(request.getAttribute("harness.denial_audited")))return;String tenant=principal==null?"_SECURITY":principal.tenantId(),actor=principal==null?"UNAUTHENTICATED":principal.userId();
        String path=request.getRequestURI();Map<String,Object> details=new LinkedHashMap<>();details.put("path",path);details.put("method",request.getMethod());details.put("reason",reason);details.put("status",response.getStatus());
        if(response.getHeader("X-Trace-Id")!=null)details.put("trace_id",response.getHeader("X-Trace-Id"));
        store.transact(tx -> {tx.audit(tenant,actor,"ACCESS_DENIED","request:"+com.delivery.harness.platform.store.PlatformStore.hash(path),details);return null;});request.setAttribute("harness.denial_audited",true);
    }
    private static String required(Map<String,Object> claims,String key) {
        Object value = claims.get(key); if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing identity " + key); return value.toString();
    }
    private static Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) throw new IllegalArgumentException("Configured identity scopes and roles cannot be empty");
        Set<String> result = new HashSet<>(); collection.forEach(item -> result.add(String.valueOf(item))); return result;
    }
}
