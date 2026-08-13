package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleInfo {

    private String ruleId;
    private String ruleName;
    private String ruleType;
    private String category;
    private String content;
    private Integer priority;
    private Boolean enabled;
    private List<String> tags;
    private Map<String, Object> conditions;
    private Map<String, Object> actions;
}
