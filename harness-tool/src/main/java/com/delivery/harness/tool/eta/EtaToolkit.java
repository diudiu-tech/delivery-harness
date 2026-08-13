package com.delivery.harness.tool.eta;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_ETA_QUERY;

@Component
@RequiredArgsConstructor
public class EtaToolkit {

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_ETA_QUERY, buildDefinition(), this::queryEta);
    }

    private ToolResult queryEta(Map<String, Object> params) {
        // MVP: Mock ETA data
        Map<String, Object> eta = new HashMap<>();
        eta.put("origin_lat", params.getOrDefault("origin_lat", 39.9087));
        eta.put("origin_lng", params.getOrDefault("origin_lng", 116.3975));
        eta.put("dest_lat", params.getOrDefault("dest_lat", 39.9150));
        eta.put("dest_lng", params.getOrDefault("dest_lng", 116.4050));
        eta.put("estimated_seconds", 1200);
        eta.put("distance_meters", 1100);
        eta.put("traffic_condition", "拥堵");
        eta.put("weather", "小雨");
        eta.put("route_summary", "沿朝阳路向东，经光华路右转");
        return ToolResult.builder().toolName(TOOL_ETA_QUERY).success(true).data(eta).build();
    }

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_ETA_QUERY)
                .description("查询两点间预估到达时间、距离、路况")
                .category("geo")
                .parameters(new HashMap<String, ToolDefinition.ParameterDef>() {{
                    put("origin_lat", ToolDefinition.ParameterDef.builder().name("origin_lat").type("number").required(true).build());
                    put("origin_lng", ToolDefinition.ParameterDef.builder().name("origin_lng").type("number").required(true).build());
                    put("dest_lat", ToolDefinition.ParameterDef.builder().name("dest_lat").type("number").required(true).build());
                    put("dest_lng", ToolDefinition.ParameterDef.builder().name("dest_lng").type("number").required(true).build());
                }})
                .build();
    }
}
