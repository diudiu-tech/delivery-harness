package com.delivery.harness.tool.order;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_ORDER_QUERY;

/**
 * Mock {@code order_query} tool.
 *
 * <p>Returns synthetic orders from {@link OrderFixtures}. The returned order
 * varies with {@code order_id}, which is what allows the rest of the pipeline
 * — tool parameters, rule matching, evaluation — to depend on its input.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderToolkit {

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_ORDER_QUERY, buildDefinition(), this::queryOrder);
    }

    private ToolResult queryOrder(Map<String, Object> params) {
        Object rawOrderId = params.get("order_id");
        if (rawOrderId == null || rawOrderId.toString().isBlank()) {
            return ToolResult.builder()
                    .toolName(TOOL_ORDER_QUERY)
                    .success(false)
                    .errorMessage("order_id is required")
                    .build();
        }

        String orderId = rawOrderId.toString();
        Optional<OrderInfo> order = OrderFixtures.find(orderId, LocalDateTime.now());
        if (order.isEmpty()) {
            log.debug("order_query: orderId={} not found", orderId);
            return ToolResult.builder()
                    .toolName(TOOL_ORDER_QUERY)
                    .success(false)
                    .errorMessage("NOT_FOUND")
                    .build();
        }
        log.debug("order_query: orderId={}, stationId={}", orderId, order.get().getStationId());

        return ToolResult.builder()
                .toolName(TOOL_ORDER_QUERY)
                .success(true)
                .data(order.get())
                .build();
    }

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_ORDER_QUERY)
                .description("查询订单详情，包括订单状态、时间节点、骑手商家信息（合成数据）")
                .category("order")
                .parameters(Collections.singletonMap("order_id", ToolDefinition.ParameterDef.builder()
                        .name("order_id").type("string").description("订单号").required(true).build()))
                .build();
    }
}
