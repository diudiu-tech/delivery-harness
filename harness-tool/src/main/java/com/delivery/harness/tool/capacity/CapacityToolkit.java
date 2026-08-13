package com.delivery.harness.tool.capacity;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_CAPACITY_QUERY;

@Component
@RequiredArgsConstructor
public class CapacityToolkit {

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_CAPACITY_QUERY, buildDefinition(), this::queryCapacity);
    }

    private ToolResult queryCapacity(Map<String, Object> params) {
        String stationId = (String) params.getOrDefault("station_id", "S001");
        // MVP: Mock capacity data. Production: gRPC call to Go dispatch service
        Map<String, Object> capacity = new HashMap<>();
        capacity.put("station_id", stationId);
        capacity.put("station_name", "朝阳一站");
        capacity.put("online_riders", 25);
        capacity.put("busy_riders", 20);
        capacity.put("idle_riders", 5);
        capacity.put("pending_orders", 15);
        capacity.put("capacity_ratio", 0.8);
        capacity.put("peak_level", "HIGH");
        capacity.put("avg_delivery_time_minutes", 42);
        capacity.put("suggestion", "运力紧张，建议减少新单分配或启动临时调度");
        return ToolResult.builder().toolName(TOOL_CAPACITY_QUERY).success(true).data(capacity).build();
    }

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_CAPACITY_QUERY)
                .description("查询站点运力情况，包括在线骑手数、忙碌数、空闲数、运力比")
                .category("capacity")
                .parameters(Collections.singletonMap("station_id", ToolDefinition.ParameterDef.builder()
                        .name("station_id").type("string").description("站点ID").required(true).build()))
                .build();
    }
}
