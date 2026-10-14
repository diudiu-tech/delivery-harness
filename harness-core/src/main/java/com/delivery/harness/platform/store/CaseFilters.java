package com.delivery.harness.platform.store;

/** Optional exact-match queue filters. Scope restrictions are applied independently. */
public record CaseFilters(
        String status,
        String orderId,
        String riskLevel,
        String complaintType,
        String assignee,
        String cityCode,
        String stationId) {
    public CaseFilters {
        status = bounded(status);
        orderId = bounded(orderId);
        riskLevel = bounded(riskLevel);
        complaintType = bounded(complaintType);
        assignee = bounded(assignee);
        cityCode = bounded(cityCode);
        stationId = bounded(stationId);
    }

    public static CaseFilters none() {
        return new CaseFilters(null, null, null, null, null, null, null);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > 120) {
            throw PlatformException.invalid("Case filters must not exceed 120 characters");
        }
        return value;
    }
}
