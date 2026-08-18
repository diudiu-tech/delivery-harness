package com.delivery.harness.tool.order;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.delivery.harness.common.config.HarnessConstants.*;

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
        String orderId = (String) params.get("order_id");
        // MVP: Mock data
        OrderInfo order = OrderInfo.builder()
                .orderId(orderId)
                .merchantId("M001")
                .merchantName("示例餐厅")
                .riderId("R001")
                .riderName("示例骑手")
                .userId("U001")
                .status("DELIVERED")
                .pickupAddress("北京市朝阳区示例商圈A座1层")
                .deliveryAddress("北京市朝阳区示例小区3号楼")
                .pickupLat(39.9087)
                .pickupLng(116.3975)
                .deliveryLat(39.9150)
                .deliveryLng(116.4050)
                .createTime(LocalDateTime.now().minusMinutes(65))
                .acceptTime(LocalDateTime.now().minusMinutes(60))
                .arriveShopTime(LocalDateTime.now().minusMinutes(50))
                .pickupTime(LocalDateTime.now().minusMinutes(40))
                .deliveryTime(LocalDateTime.now().minusMinutes(5))
                .expectedDeliveryTime(LocalDateTime.now().minusMinutes(15))
                .estimatedDurationSeconds(2400)
                .actualDurationSeconds(3600)
                .orderAmount(new BigDecimal("35.50"))
                .deliveryFee(new BigDecimal("5.00"))
                .stationId("S001")
                .stationName("朝阳一站")
                .cityCode("010")
                .isAbnormal(true)
                .abnormalType("OVERTIME")
                .events(Arrays.asList(
                        OrderInfo.OrderEvent.builder()
                                .eventType("ORDER_CREATED").description("订单创建")
                                .eventTime(LocalDateTime.now().minusMinutes(65)).build(),
                        OrderInfo.OrderEvent.builder()
                                .eventType("RIDER_ACCEPTED").description("骑手接单")
                                .eventTime(LocalDateTime.now().minusMinutes(60)).build(),
                        OrderInfo.OrderEvent.builder()
                                .eventType("RIDER_ARRIVED_SHOP").description("骑手到店")
                                .eventTime(LocalDateTime.now().minusMinutes(50)).build(),
                        OrderInfo.OrderEvent.builder()
                                .eventType("ORDER_PICKED_UP").description("骑手取餐")
                                .eventTime(LocalDateTime.now().minusMinutes(40)).build(),
                        OrderInfo.OrderEvent.builder()
                                .eventType("ORDER_DELIVERED").description("订单送达")
                                .eventTime(LocalDateTime.now().minusMinutes(5)).build()
                ))
                .build();

        return ToolResult.builder()
                .toolName(TOOL_ORDER_QUERY)
                .success(true)
                .data(order)
                .build();
    }

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_ORDER_QUERY)
                .description("查询订单详情，包括订单状态、时间节点、骑手商家信息")
                .category("order")
                .parameters(Collections.singletonMap("order_id", ToolDefinition.ParameterDef.builder()
                        .name("order_id").type("string").description("订单号").required(true).build()))
                .build();
    }
}
