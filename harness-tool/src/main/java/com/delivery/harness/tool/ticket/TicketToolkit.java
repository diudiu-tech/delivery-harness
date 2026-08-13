package com.delivery.harness.tool.ticket;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_TICKET_QUERY;

@Component
@RequiredArgsConstructor
public class TicketToolkit {

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_TICKET_QUERY, buildDefinition(), this::queryTicket);
    }

    private ToolResult queryTicket(Map<String, Object> params) {
        String orderId = (String) params.getOrDefault("order_id", "unknown");
        // MVP: Mock ticket data
        Map<String, Object> ticketItem = new HashMap<>();
        ticketItem.put("ticket_id", "T20260324001");
        ticketItem.put("order_id", orderId);
        ticketItem.put("type", "USER_COMPLAINT");
        ticketItem.put("sub_type", "OVERTIME");
        ticketItem.put("status", "PENDING");
        ticketItem.put("content", "订单超时未送达，用户要求赔偿");
        ticketItem.put("created_at", "2026-03-24 10:30:00");
        ticketItem.put("priority", "HIGH");

        Map<String, Object> ticket = new HashMap<>();
        ticket.put("tickets", Collections.singletonList(ticketItem));
        ticket.put("total", 1);
        return ToolResult.builder().toolName(TOOL_TICKET_QUERY).success(true).data(ticket).build();
    }

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_TICKET_QUERY)
                .description("查询订单相关工单")
                .category("ticket")
                .parameters(Collections.singletonMap("order_id", ToolDefinition.ParameterDef.builder()
                        .name("order_id").type("string").description("订单号").required(true).build()))
                .build();
    }
}
