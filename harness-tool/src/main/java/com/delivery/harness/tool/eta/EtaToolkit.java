package com.delivery.harness.tool.eta;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_ETA_QUERY;

/**
 * Mock {@code eta_query} tool.
 *
 * <p>Supplies the counterfactual leg of an attribution: how long the road
 * segment <em>should</em> have taken. The estimate is derived from the
 * requested coordinates rather than returned as a constant, so comparing it
 * against the observed road time is a meaningful signal.
 *
 * <p>The model is deliberately crude — great-circle distance times a winding
 * factor, divided by a fixed courier speed, adjusted for weather. It is not a
 * routing engine and must be replaced by one before any real use.
 */
@Component
@RequiredArgsConstructor
public class EtaToolkit {

    /** Average effective courier speed on a two-wheeler, metres per second. */
    private static final double COURIER_SPEED_MPS = 4.0;

    /** Great-circle distance underestimates road distance; scale it up. */
    private static final double ROAD_WINDING_FACTOR = 1.35;

    /** Fixed overhead for parking, building access and handover, in seconds. */
    private static final int HANDOVER_OVERHEAD_SECONDS = 180;

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_ETA_QUERY, buildDefinition(), this::queryEta);
    }

    private ToolResult queryEta(Map<String, Object> params) {
        Double originLat = asDouble(params.get("origin_lat"));
        Double originLng = asDouble(params.get("origin_lng"));
        Double destLat = asDouble(params.get("dest_lat"));
        Double destLng = asDouble(params.get("dest_lng"));

        if (originLat == null || originLng == null || destLat == null || destLng == null) {
            return ToolResult.builder()
                    .toolName(TOOL_ETA_QUERY)
                    .success(false)
                    .errorMessage("origin_lat, origin_lng, dest_lat and dest_lng are all required")
                    .build();
        }

        double straightLine = haversineMeters(originLat, originLng, destLat, destLng);
        long roadDistance = Math.round(straightLine * ROAD_WINDING_FACTOR);

        String weather = asString(params.get("weather"), "晴");
        double weatherFactor = weatherFactor(weather);
        String traffic = trafficFor(roadDistance, weatherFactor);

        long estimatedSeconds = Math.round(roadDistance / COURIER_SPEED_MPS * weatherFactor)
                + HANDOVER_OVERHEAD_SECONDS;

        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("origin_lat", originLat);
        eta.put("origin_lng", originLng);
        eta.put("dest_lat", destLat);
        eta.put("dest_lng", destLng);
        eta.put("distance_meters", roadDistance);
        eta.put("estimated_seconds", estimatedSeconds);
        eta.put("estimated_minutes", Math.round(estimatedSeconds / 60.0));
        eta.put("traffic_condition", traffic);
        eta.put("weather", weather);
        eta.put("weather_factor", weatherFactor);
        eta.put("model", "great-circle distance x winding factor / fixed speed (synthetic)");
        return ToolResult.builder().toolName(TOOL_ETA_QUERY).success(true).data(eta).build();
    }

    static double weatherFactor(String weather) {
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

    private static String trafficFor(long roadDistanceMeters, double weatherFactor) {
        if (weatherFactor >= 1.5) {
            return "拥堵";
        }
        if (roadDistanceMeters > 3_000 || weatherFactor > 1.0) {
            return "缓行";
        }
        return "畅通";
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private ToolDefinition buildDefinition() {
        Map<String, ToolDefinition.ParameterDef> parameters = new HashMap<>();
        parameters.put("origin_lat", numberParam("origin_lat", true));
        parameters.put("origin_lng", numberParam("origin_lng", true));
        parameters.put("dest_lat", numberParam("dest_lat", true));
        parameters.put("dest_lng", numberParam("dest_lng", true));
        parameters.put("weather", ToolDefinition.ParameterDef.builder()
                .name("weather").type("string").description("下单时天气").required(false).build());

        return ToolDefinition.builder()
                .toolName(TOOL_ETA_QUERY)
                .description("查询两点间预估到达时间、距离、路况（合成模型）")
                .category("geo")
                .parameters(parameters)
                .build();
    }

    private static ToolDefinition.ParameterDef numberParam(String name, boolean required) {
        return ToolDefinition.ParameterDef.builder().name(name).type("number").required(required).build();
    }
}
