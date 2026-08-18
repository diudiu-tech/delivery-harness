package com.delivery.harness.common.config;

public final class HarnessConstants {

    private HarnessConstants() {}

    // Scenarios
    public static final String SCENARIO_ABNORMAL_ORDER = "abnormal_order_analysis";
    public static final String SCENARIO_COMPENSATION = "compensation_suggestion";

    // Workflow step types
    public static final String STEP_RETRIEVAL = "retrieval";
    public static final String STEP_TOOL_CALL = "tool_call";
    public static final String STEP_LLM_CALL = "llm_call";
    public static final String STEP_GUARDRAIL = "guardrail";
    public static final String STEP_FORMAT = "format";

    // Execution status
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    // Confidence levels
    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    // Tool names
    public static final String TOOL_ORDER_QUERY = "order_query";
    public static final String TOOL_ETA_QUERY = "eta_query";
    public static final String TOOL_CAPACITY_QUERY = "capacity_query";
    public static final String TOOL_COMPENSATION_RULE = "compensation_rule";

    // Eval metrics
    public static final String METRIC_RULE_ACCURACY = "rule_accuracy";
    public static final String METRIC_EXPERT_ALIGNMENT = "expert_alignment";
    public static final String METRIC_TOOL_EXECUTION = "tool_execution";
    public static final String METRIC_HALLUCINATION = "hallucination_rate";
}
