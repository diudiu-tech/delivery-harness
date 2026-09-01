package com.delivery.harness.common.config;

public final class HarnessConstants {

    private HarnessConstants() {}

    // Scenarios
    public static final String SCENARIO_ABNORMAL_ORDER = "abnormal_order_analysis";
    public static final String SCENARIO_COMPENSATION = "compensation_suggestion";

    // Workflow step types
    public static final String STEP_RETRIEVAL = "retrieval";
    public static final String STEP_TOOL_CALL = "tool_call";
    /** Deterministic computation over already-retrieved evidence. */
    public static final String STEP_ANALYSIS = "analysis";
    public static final String STEP_LLM_CALL = "llm_call";
    public static final String STEP_GUARDRAIL = "guardrail";
    public static final String STEP_FORMAT = "format";

    // Execution status
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** Deterministic work completed, but an optional dependency was unavailable. */
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PARTIAL_SUCCESS = "PARTIAL_SUCCESS";

    // Workflow output markers
    public static final String OUTPUT_DEGRADED = "degraded";

    // Tool names
    public static final String TOOL_ORDER_QUERY = "order_query";
    public static final String TOOL_ETA_QUERY = "eta_query";
    public static final String TOOL_CAPACITY_QUERY = "capacity_query";
    public static final String TOOL_COMPENSATION_RULE = "compensation_rule";

}
