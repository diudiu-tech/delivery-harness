package com.delivery.harness.agent.workflow;

import com.delivery.harness.common.config.HarnessConstants;
import com.delivery.harness.common.dto.WorkflowExecution;
import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.common.util.TraceUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowEngineTest {

    private final WorkflowEngine engine = new WorkflowEngine();

    @AfterEach
    void clearTrace() {
        TraceUtil.clear();
    }

    @Test
    void carriesRequestTraceIntoSuccessfulExecution() {
        TraceUtil.setTraceId("trace-test-1");
        engine.registerHandler("test", (input, execution) -> Collections.singletonMap("ok", true));

        WorkflowExecution execution = engine.execute("test", Collections.emptyMap());

        assertEquals(HarnessConstants.STATUS_SUCCESS, execution.getStatus());
        assertEquals("trace-test-1", execution.getTraceId());
        assertEquals(true, execution.getOutput().get("ok"));
        assertNotNull(execution.getFinishedAt());
    }

    @Test
    void recordsHandlerFailureWithoutReportingSuccess() {
        engine.registerHandler("failing", (input, execution) -> {
            throw new IllegalStateException("synthetic failure");
        });

        WorkflowExecution execution = engine.execute("failing", Collections.emptyMap());

        assertEquals(HarnessConstants.STATUS_FAILED, execution.getStatus());
        assertEquals("Workflow execution failed", execution.getOutput().get("error"));
    }

    @Test
    void propagatesDependencyFailuresForHttpStatusMapping() {
        engine.registerHandler("llm-failure", (input, execution) -> {
            throw new LlmException("example-model", "provider unavailable");
        });

        assertThrows(LlmException.class,
                () -> engine.execute("llm-failure", Collections.emptyMap()));
    }
}
