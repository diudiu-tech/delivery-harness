package com.delivery.harness.agent.workflow;

import com.delivery.harness.common.dto.WorkflowExecution;

import java.util.Collections;
import java.util.Map;

/**
 * Appends timed steps to a {@link WorkflowExecution}.
 *
 * <p>Exists because both workflows previously recorded
 * {@code .durationMs(0L)} on every step while computing — and discarding — a
 * start timestamp. Step timings were therefore always zero, which made the
 * per-step latency in an API response actively misleading and left the system
 * unable to say where its own time went.
 *
 * <p>Durations are measured with {@link System#nanoTime()} rather than wall
 * clock, so they are unaffected by clock adjustments.
 */
final class StepRecorder {

    private final WorkflowExecution execution;

    StepRecorder(WorkflowExecution execution) {
        this.execution = execution;
    }

    /** Milliseconds elapsed since a {@link System#nanoTime()} reading. */
    static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    void record(
            String stepType,
            String name,
            String toolName,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            long durationMs,
            String errorMessage) {
        execution.getSteps().add(WorkflowExecution.StepExecution.builder()
                .order(execution.getSteps().size() + 1)
                .stepType(stepType)
                .name(name)
                .toolName(toolName)
                .status(status)
                .input(input == null ? Collections.emptyMap() : input)
                .output(output == null ? Collections.emptyMap() : output)
                .durationMs(durationMs)
                .errorMessage(errorMessage)
                .build());
    }

    void record(
            String stepType,
            String name,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            long durationMs) {
        record(stepType, name, null, status, input, output, durationMs, null);
    }
}
