package com.delivery.harness.tool.capacity;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_CAPACITY_QUERY;

/**
 * Mock {@code capacity_query} tool.
 *
 * <p>Returns a synthetic capacity snapshot that differs per station, so that
 * "the station was short of couriers" is a claim the evidence can support or
 * contradict rather than a constant every order inherits.
 *
 * <p>Figures are fabricated and deterministic per station ID. Replace with the
 * real dispatch service before any use beyond demonstration.
 */
@Component
@RequiredArgsConstructor
public class CapacityToolkit {

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_CAPACITY_QUERY, buildDefinition(), this::queryCapacity);
    }

    private ToolResult queryCapacity(Map<String, Object> params) {
        Object rawStationId = params.get("station_id");
        if (rawStationId == null || rawStationId.toString().isBlank()) {
            return ToolResult.builder()
                    .toolName(TOOL_CAPACITY_QUERY)
                    .success(false)
                    .errorMessage("station_id is required")
                    .build();
        }

        String stationId = rawStationId.toString();
        StationLoad load = loadFor(stationId);

        int idleRiders = Math.max(0, load.onlineRiders() - load.busyRiders());
        double capacityRatio = load.onlineRiders() == 0
                ? 1.0
                : Math.round((double) load.busyRiders() / load.onlineRiders() * 100) / 100.0;

        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("station_id", stationId);
        capacity.put("station_name", load.stationName());
        capacity.put("online_riders", load.onlineRiders());
        capacity.put("busy_riders", load.busyRiders());
        capacity.put("idle_riders", idleRiders);
        capacity.put("pending_orders", load.pendingOrders());
        capacity.put("capacity_ratio", capacityRatio);
        capacity.put("peak_level", peakLevel(capacityRatio));
        capacity.put("avg_dispatch_wait_minutes", load.avgDispatchWaitMinutes());
        capacity.put("avg_delivery_time_minutes", load.avgDeliveryMinutes());
        capacity.put("strained", capacityRatio >= 0.85);
        capacity.put("synthetic", true);
        return ToolResult.builder().toolName(TOOL_CAPACITY_QUERY).success(true).data(capacity).build();
    }

    /**
     * Synthetic per-station load. S002 is deliberately strained so that the
     * capacity-shortage scenario has supporting evidence and the
     * merchant-slow scenario does not.
     */
    private static StationLoad loadFor(String stationId) {
        return switch (stationId) {
            case "S001" -> new StationLoad("示例一站", 30, 19, 8, 3, 34);
            case "S002" -> new StationLoad("示例二站", 22, 21, 24, 17, 47);
            case "S003" -> new StationLoad("示例三站", 26, 22, 15, 9, 41);
            default -> {
                int seed = Math.floorMod(stationId.hashCode(), 10);
                int online = 20 + seed;
                int busy = Math.min(online, 12 + seed);
                yield new StationLoad("示例站点-" + stationId, online, busy, seed * 2, 4 + seed, 32 + seed);
            }
        };
    }

    private static String peakLevel(double capacityRatio) {
        if (capacityRatio >= 0.9) {
            return "HIGH";
        }
        if (capacityRatio >= 0.7) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private record StationLoad(
            String stationName,
            int onlineRiders,
            int busyRiders,
            int pendingOrders,
            int avgDispatchWaitMinutes,
            int avgDeliveryMinutes) {}

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_CAPACITY_QUERY)
                .description("查询站点运力情况，包括在线骑手数、忙碌数、空闲数、运力比（合成数据）")
                .category("capacity")
                .parameters(Collections.singletonMap("station_id", ToolDefinition.ParameterDef.builder()
                        .name("station_id").type("string").description("站点ID").required(true).build()))
                .build();
    }
}
