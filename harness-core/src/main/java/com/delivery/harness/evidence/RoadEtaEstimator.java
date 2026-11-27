package com.delivery.harness.evidence;

/**
 * Deterministic road ETA estimator used by {@link SyntheticEvidenceSource}.
 *
 * <p>Uses the same great-circle distance x winding factor / fixed courier speed
 * formula adjusted for weather as the legacy {@code eta_query} mock tool so
 * fixture numbers remain 100% consistent across revisions.
 */
public final class RoadEtaEstimator {

    private static final double COURIER_SPEED_MPS = 4.0;
    private static final double ROAD_WINDING_FACTOR = 1.35;
    private static final int HANDOVER_OVERHEAD_SECONDS = 180;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private RoadEtaEstimator() {}

    public static long estimateMinutes(
            double originLat, double originLng, double destLat, double destLng, String weather) {
        double straightLine = haversineMeters(originLat, originLng, destLat, destLng);
        long roadDistance = Math.round(straightLine * ROAD_WINDING_FACTOR);
        double factor = weatherFactor(weather);
        long estimatedSeconds = Math.round(roadDistance / COURIER_SPEED_MPS * factor) + HANDOVER_OVERHEAD_SECONDS;
        return Math.round(estimatedSeconds / 60.0);
    }

    public static double weatherFactor(String weather) {
        if (weather == null) {
            return 1.0;
        }
        if (weather.contains("暴雨") || weather.contains("暴雪")) {
            return 1.6;
        }
        if (weather.contains("雨") || weather.contains("雪")) {
            return 1.3;
        }
        if (weather.contains("大风")) {
            return 1.15;
        }
        return 1.0;
    }

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
