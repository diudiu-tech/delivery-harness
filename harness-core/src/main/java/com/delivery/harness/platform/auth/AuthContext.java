package com.delivery.harness.platform.auth;

import com.delivery.harness.platform.store.PlatformException;
import java.util.Set;

/** Authenticated claims come from server configuration, never request body fields. */
public final class AuthContext {
    private AuthContext() {}
    public record Principal(String tenantId, String userId, Set<String> roles, Set<String> cityCodes, Set<String> stationIds) {
        public Principal {
            if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) throw new IllegalArgumentException("Identity requires tenant and user");
            roles = Set.copyOf(roles); cityCodes = Set.copyOf(cityCodes); stationIds = Set.copyOf(stationIds);
        }
        public void requireRole(String... allowed) {
            for (String role : allowed) if (roles.contains(role)) return;
            throw new PlatformException(403,"FORBIDDEN","This role cannot perform this operation");
        }
        public boolean canAccess(String city, String station) {
            return (cityCodes.contains("*") || city != null && cityCodes.contains(city))
                    && (stationIds.contains("*") || station != null && stationIds.contains(station));
        }
        public void authorizeScope(String city, String station) {
            if (!canAccess(city,station)) throw new PlatformException(404,"NOT_FOUND","Resource not found in authorized scope");
        }
    }
}
