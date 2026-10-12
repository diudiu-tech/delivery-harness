package com.delivery.harness.platform.auth;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.store.PlatformException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

/** Server-managed identities for assignment; request bodies cannot declare a recipient's roles or scope. */
@Service
public class PrincipalDirectory {
    private record Key(String tenant,String user) {}
    private final Map<Key,AuthContext.Principal> identities=new LinkedHashMap<>();
    public PrincipalDirectory(@Value("${harness.security.tokens:[]}") String configured,@Value("${harness.mode:demo}") String mode) {
        List<Map<String,Object>> claims=JsonUtil.fromJson(configured,new TypeReference<>(){});
        for(Map<String,Object> claim:claims){AuthContext.Principal principal=new AuthContext.Principal(claim.get("tenant_id").toString(),claim.get("user_id").toString(),strings(claim.get("roles")),strings(claim.get("city_codes")),strings(claim.get("station_ids")));register(principal);}
        if(!"pilot".equals(mode))demoCredentials().values().forEach(p -> identities.putIfAbsent(new Key(p.tenantId(),p.userId()),p));
    }
    private void register(AuthContext.Principal principal){Key key=new Key(principal.tenantId(),principal.userId());AuthContext.Principal previous=identities.putIfAbsent(key,principal);if(previous!=null && !previous.equals(principal))throw new IllegalStateException("Configured user identity has conflicting roles or scopes");}
    public AuthContext.Principal reviewer(String tenant,String user,String city,String station){AuthContext.Principal principal=identities.get(new Key(tenant,user));if(principal==null || Collections.disjoint(principal.roles(),Set.of("REVIEWER","SUPERVISOR")) || !principal.canAccess(city,station))throw new PlatformException(404,"REVIEWER_NOT_FOUND","No authorized reviewer exists for this tenant and case scope");return principal;}
    public List<AuthContext.Principal> reviewers(String tenant,String city,String station){return identities.values().stream().filter(p -> p.tenantId().equals(tenant) && !Collections.disjoint(p.roles(),Set.of("REVIEWER","SUPERVISOR")) && p.canAccess(city,station)).toList();}
    public static Map<String,AuthContext.Principal> demoCredentials(){return Map.of(
            "demo-agent-token",new AuthContext.Principal("demo","demo-agent",Set.of("AGENT","OPERATOR","REVIEWER","SUPERVISOR","POLICY_EDITOR","POLICY_PUBLISHER","QUALITY","AUDITOR","ADMIN"),Set.of("*"),Set.of("*")),
            "demo-reviewer-token",new AuthContext.Principal("demo","demo-reviewer",Set.of("REVIEWER"),Set.of("*"),Set.of("*")),
            "demo-supervisor-token",new AuthContext.Principal("demo","demo-supervisor",Set.of("REVIEWER","SUPERVISOR","POLICY_PUBLISHER","QUALITY","AUDITOR"),Set.of("*"),Set.of("*")));
    }
    private static Set<String> strings(Object value){if(!(value instanceof Collection<?> list) || list.isEmpty())throw new IllegalArgumentException("Configured roles and scopes are required");Set<String> result=new HashSet<>();list.forEach(v -> result.add(v.toString()));return result;}
}
