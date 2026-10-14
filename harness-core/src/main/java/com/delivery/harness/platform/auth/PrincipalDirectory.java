package com.delivery.harness.platform.auth;

import com.delivery.harness.platform.store.PlatformException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assignment candidates are learned from authenticated identities, never from declared request
 * roles.
 */
@Service
public class PrincipalDirectory {
    private record Key(String tenant, String user) {}

    private final Map<Key, AuthContext.Principal> identities = new ConcurrentHashMap<>();

    public PrincipalDirectory(@Value("${harness.mode:demo}") String mode) {
        if (!Set.of("demo", "pilot", "test").contains(mode)) {
            throw new IllegalArgumentException("Unsupported authentication mode");
        }
        if (!"pilot".equals(mode)) {
            demoPrincipals().values().forEach(this::registerAuthenticated);
        }
    }

    public void registerAuthenticated(AuthContext.Principal principal) {
        Key key = new Key(principal.tenantId(), principal.userId());
        if (identities.size() >= 10_000 && !identities.containsKey(key)) {
            throw new PlatformException(
                    503, "IDENTITY_DIRECTORY_FULL", "Identity directory capacity reached");
        }
        identities.put(key, principal);
    }

    public AuthContext.Principal reviewer(String tenant, String user, String city, String station) {
        AuthContext.Principal principal = identities.get(new Key(tenant, user));
        if (principal == null
                || Collections.disjoint(principal.roles(), Set.of("REVIEWER", "SUPERVISOR"))
                || !principal.canAccess(city, station)) {
            throw new PlatformException(
                    404, "REVIEWER_NOT_FOUND", "No authenticated reviewer exists in this scope");
        }
        return principal;
    }

    public List<AuthContext.Principal> reviewers(String tenant, String city, String station) {
        return identities.values().stream()
                .filter(
                        principal ->
                                principal.tenantId().equals(tenant)
                                        && !Collections.disjoint(
                                                principal.roles(), Set.of("REVIEWER", "SUPERVISOR"))
                                        && principal.canAccess(city, station))
                .limit(100)
                .toList();
    }

    public static Map<String, AuthContext.Principal> demoPrincipals() {
        return Map.of(
                "demo-agent",
                        new AuthContext.Principal(
                                "demo",
                                "demo-agent",
                                Set.of("AGENT", "OPERATOR"),
                                Set.of("*"),
                                Set.of("*")),
                "demo-reviewer",
                        new AuthContext.Principal(
                                "demo",
                                "demo-reviewer",
                                Set.of("REVIEWER"),
                                Set.of("*"),
                                Set.of("*")),
                "demo-supervisor",
                        new AuthContext.Principal(
                                "demo",
                                "demo-supervisor",
                                Set.of("REVIEWER", "SUPERVISOR"),
                                Set.of("*"),
                                Set.of("*")),
                "demo-publisher",
                        new AuthContext.Principal(
                                "demo",
                                "demo-publisher",
                                Set.of("QUALITY", "POLICY_PUBLISHER"),
                                Set.of("*"),
                                Set.of("*")),
                "demo-admin",
                        new AuthContext.Principal(
                                "demo",
                                "demo-admin",
                                Set.of("POLICY_EDITOR", "QUALITY", "AUDITOR", "ADMIN"),
                                Set.of("*"),
                                Set.of("*")));
    }
}
