package com.delivery.harness.platform.auth;

import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.RecordScope;

import java.util.List;
import java.util.Set;

/** Claims come from verified JWTs or explicit local demo credentials, never request body fields. */
public final class AuthContext {
    private AuthContext() {}

    public record Principal(
            String tenantId,
            String userId,
            Set<String> roles,
            Set<String> cityCodes,
            Set<String> stationIds)
            implements RecordScope {
        public Principal {
            if (tenantId == null
                    || tenantId.isBlank()
                    || tenantId.length() > 100
                    || userId == null
                    || userId.isBlank()
                    || userId.length() > 200) {
                throw new IllegalArgumentException(
                        "Identity requires bounded tenant and user identifiers");
            }
            for (Set<String> values : List.of(roles, cityCodes, stationIds)) {
                if (values.isEmpty()
                        || values.size() > 100
                        || values.stream()
                                .anyMatch(
                                        value ->
                                                value == null
                                                        || value.isBlank()
                                                        || value.length() > 100)) {
                    throw new IllegalArgumentException(
                            "Identity roles and scopes must be bounded and nonempty");
                }
            }
            roles = Set.copyOf(roles);
            cityCodes = Set.copyOf(cityCodes);
            stationIds = Set.copyOf(stationIds);
        }

        public void requireRole(String... allowed) {
            for (String role : allowed) {
                if (roles.contains(role)) {
                    return;
                }
            }
            throw new PlatformException(
                    403, "FORBIDDEN", "This role cannot perform this operation");
        }

        public boolean canAccess(String city, String station) {
            return (cityCodes.contains("*") || city != null && cityCodes.contains(city))
                    && (stationIds.contains("*")
                            || station != null && stationIds.contains(station));
        }

        public void authorizeScope(String city, String station) {
            if (!canAccess(city, station))
                throw new PlatformException(
                        404, "NOT_FOUND", "Resource not found in authorized scope");
        }
    }
}
