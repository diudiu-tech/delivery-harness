package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.quality.QualityData;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Authorization and bounded page helpers; reads never create defaults or audit success. */
abstract class QualityAccess {
    protected final PlatformStore store;

    protected QualityAccess(PlatformStore store) {
        this.store = store;
    }

    protected AuthContext.Principal read(HttpServletRequest request) {
        AuthContext.Principal actor = PrincipalAccess.get(request);
        actor.requireRole(
                "AGENT",
                "OPERATOR",
                "REVIEWER",
                "POLICY_EDITOR",
                "POLICY_PUBLISHER",
                "QUALITY",
                "AUDITOR",
                "ADMIN");
        return actor;
    }

    protected PlatformStore.Entity resource(AuthContext.Principal actor, String kind, String id) {
        PlatformStore.Entity entity = store.require(actor.tenantId(), kind, id);
        authorizePublicationScope(actor, entity.data());
        return entity;
    }

    protected static void authorizePublicationScope(
            AuthContext.Principal actor, Map<String, Object> data) {
        List<String> cities = QualityData.strings(data.get("city_codes"));
        List<String> stations = QualityData.strings(data.get("station_ids"));
        // Unscoped content is visible globally within its tenant, but a scoped editor cannot
        // publish globally.
        if ((!cities.isEmpty()
                        && !actor.cityCodes().contains("*")
                        && !actor.cityCodes().containsAll(cities))
                || (!stations.isEmpty()
                        && !actor.stationIds().contains("*")
                        && !actor.stationIds().containsAll(stations))) {
            throw new PlatformException(404, "NOT_FOUND", "Content not found in authorized scope");
        }
    }

    protected static void authorizeCreateScope(
            AuthContext.Principal actor, Map<String, Object> data) {
        authorizePublicationScope(actor, data);
        if ((QualityData.strings(data.get("city_codes")).isEmpty()
                        && !actor.cityCodes().contains("*"))
                || (QualityData.strings(data.get("station_ids")).isEmpty()
                        && !actor.stationIds().contains("*"))) {
            throw new PlatformException(
                    403,
                    "FORBIDDEN",
                    "Scoped authors must explicitly limit publication to authorized cities and"
                            + " stations");
        }
    }

    protected static boolean allowed(AuthContext.Principal actor, Map<String, Object> data) {
        if (Boolean.FALSE.equals(data.get("summary_scope_valid"))) return false;
        try {
            authorizePublicationScope(actor, data);
            return true;
        } catch (PlatformException e) {
            return false;
        }
    }

    protected static long version(Map<String, Object> body) {
        if (!(body.get("expected_version") instanceof Number version) || version.longValue() < 1) {
            throw new IllegalArgumentException("expected_version must be a positive version");
        }
        return version.longValue();
    }

    protected Map<String, Object> dbPage(
            AuthContext.Principal actor,
            String kind,
            int limit,
            String cursor,
            Predicate<PlatformStore.Entity> filter) {
        List<PlatformStore.Entity> records =
                store.summaryPage(actor.tenantId(), kind, cursor, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", records.stream().filter(filter).toList());
        result.put(
                "next_cursor",
                records.size() == limit ? records.get(records.size() - 1).id() : null);
        return result;
    }
}
