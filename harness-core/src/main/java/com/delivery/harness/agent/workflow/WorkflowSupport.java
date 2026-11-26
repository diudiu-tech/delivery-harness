package com.delivery.harness.agent.workflow;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_ORDER_QUERY;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.exception.ToolException;
import com.delivery.harness.common.util.JsonUtil;

import java.util.Map;

final class WorkflowSupport {
    private WorkflowSupport() {}

    static OrderInfo readOrder(ToolResult result, String expectedId, StepRecorder steps) {
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new ToolException(
                    TOOL_ORDER_QUERY,
                    result.getErrorMessage() == null
                            ? "order lookup failed"
                            : result.getErrorMessage());
        }
        try {
            if (result.getData() == null) {
                throw new IllegalArgumentException("Missing order data");
            }
            OrderInfo order = JsonUtil.fromMap(JsonUtil.toMap(result.getData()), OrderInfo.class);
            if (order == null || !expectedId.equals(order.getOrderId())) {
                throw new IllegalArgumentException("Order identifier mismatch");
            }
            return order;
        } catch (RuntimeException failure) {
            steps.record(
                    "data_validation",
                    "订单数据契约核验",
                    "FAILED",
                    Map.of("order_id", expectedId),
                    Map.of("required", true, "error_code", "INVALID_DATA"),
                    0);
            // Decoder diagnostics can quote private source fields; do not expose their cause.
            throw new ToolException(TOOL_ORDER_QUERY, "Invalid order source response");
        }
    }
}
