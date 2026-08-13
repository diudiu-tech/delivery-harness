package com.delivery.harness.tool.compensation;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.tool.gateway.ToolGateway;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.delivery.harness.common.config.HarnessConstants.TOOL_COMPENSATION_RULE;

@Component
@RequiredArgsConstructor
public class CompensationRuleToolkit {

    private final ToolGateway toolGateway;

    @PostConstruct
    public void init() {
        toolGateway.register(TOOL_COMPENSATION_RULE, buildDefinition(), this::matchRules);
    }

    private ToolResult matchRules(Map<String, Object> params) {
        String complaintType = (String) params.getOrDefault("complaint_type", "OVERTIME");
        int overtimeMinutes = ((Number) params.getOrDefault("overtime_minutes", 10)).intValue();

        // MVP: Simple rule matching logic
        Map<String, Object> result;
        if ("OVERTIME".equals(complaintType)) {
            if (overtimeMinutes > 30) {
                Map<String, Object> rule = new HashMap<>();
                rule.put("rule_id", "COMP-001");
                rule.put("rule_name", "严重超时赔付");
                rule.put("content", "超时30分钟以上，赔付订单金额的50%，最高不超过20元");
                rule.put("confidence", "HIGH");

                result = new HashMap<>();
                result.put("matched_rules", Collections.singletonList(rule));
                result.put("suggested_amount", 17.75);
                result.put("suggested_method", "原路退款");
                result.put("approval_required", true);
                result.put("approval_reason", "赔付金额超过10元需人工审批");
            } else if (overtimeMinutes > 15) {
                Map<String, Object> rule = new HashMap<>();
                rule.put("rule_id", "COMP-002");
                rule.put("rule_name", "一般超时赔付");
                rule.put("content", "超时15-30分钟，赔付3-5元优惠券");
                rule.put("confidence", "HIGH");

                result = new HashMap<>();
                result.put("matched_rules", Collections.singletonList(rule));
                result.put("suggested_amount", 5.0);
                result.put("suggested_method", "发放优惠券");
                result.put("approval_required", false);
                result.put("approval_reason", "");
            } else {
                Map<String, Object> rule = new HashMap<>();
                rule.put("rule_id", "COMP-003");
                rule.put("rule_name", "轻微超时");
                rule.put("content", "超时15分钟以内，致歉并赠送1元红包");
                rule.put("confidence", "MEDIUM");

                result = new HashMap<>();
                result.put("matched_rules", Collections.singletonList(rule));
                result.put("suggested_amount", 1.0);
                result.put("suggested_method", "红包");
                result.put("approval_required", false);
                result.put("approval_reason", "");
            }
        } else {
            result = new HashMap<>();
            result.put("matched_rules", Collections.emptyList());
            result.put("suggested_amount", 0);
            result.put("suggested_method", "需人工判断");
            result.put("approval_required", true);
            result.put("approval_reason", "非标准投诉类型，需人工审核");
        }

        return ToolResult.builder().toolName(TOOL_COMPENSATION_RULE).success(true).data(result).build();
    }

    private ToolDefinition buildDefinition() {
        return ToolDefinition.builder()
                .toolName(TOOL_COMPENSATION_RULE)
                .description("匹配赔付规则，根据投诉类型和超时时长给出赔付建议")
                .category("compensation")
                .parameters(new HashMap<String, ToolDefinition.ParameterDef>() {{
                    put("complaint_type", ToolDefinition.ParameterDef.builder()
                                .name("complaint_type").type("string").description("投诉类型").required(true)
                                .enumValues(Arrays.asList("OVERTIME", "WRONG_ORDER", "DAMAGED", "MISSING_ITEM")).build());
                    put("overtime_minutes", ToolDefinition.ParameterDef.builder()
                                .name("overtime_minutes").type("integer").description("超时分钟数").required(false).build());
                }})
                .build();
    }
}
