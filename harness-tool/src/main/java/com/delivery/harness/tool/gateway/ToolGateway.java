package com.delivery.harness.tool.gateway;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolInvocation;
import com.delivery.harness.common.dto.ToolResult;
import com.delivery.harness.common.exception.ToolException;
import com.delivery.harness.common.util.TraceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolGateway {

    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();
    private final List<ToolInvocation> invocationLog = Collections.synchronizedList(new ArrayList<>());

    public void register(String toolName, ToolDefinition definition, ToolExecutor executor) {
        definitions.put(toolName, definition);
        executors.put(toolName, executor);
        log.info("Tool registered: {}", toolName);
    }

    public ToolResult invoke(String toolName, Map<String, Object> parameters) {
        ToolExecutor executor = executors.get(toolName);
        if (executor == null) {
            throw new ToolException(toolName, "Tool not registered");
        }

        long startTime = System.currentTimeMillis();
        String invocationId = UUID.randomUUID().toString();

        try {
            ToolResult result = executor.execute(parameters);
            long duration = System.currentTimeMillis() - startTime;
            result.setDurationMs(duration);

            logInvocation(invocationId, toolName, parameters, duration, true, null);
            log.info("Tool invoked: name={}, duration={}ms, success=true", toolName, duration);
            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logInvocation(invocationId, toolName, parameters, duration, false, e.getMessage());
            log.error("Tool invocation failed: name={}, error={}", toolName, e.getMessage());
            throw new ToolException(toolName, e.getMessage(), e);
        }
    }

    public ToolDefinition getDefinition(String toolName) {
        return definitions.get(toolName);
    }

    public Map<String, ToolDefinition> listDefinitions() {
        return Collections.unmodifiableMap(new HashMap<>(definitions));
    }

    public List<ToolInvocation> getInvocationLog() {
        return Collections.unmodifiableList(new ArrayList<>(invocationLog));
    }

    private void logInvocation(String invocationId, String toolName, Map<String, Object> parameters,
                               long duration, boolean success, String errorMessage) {
        invocationLog.add(ToolInvocation.builder()
                .invocationId(invocationId)
                .traceId(TraceUtil.getTraceId())
                .toolName(toolName)
                .parameters(parameters)
                .invokedAt(LocalDateTime.now())
                .durationMs(duration)
                .success(success)
                .errorMessage(errorMessage)
                .build());
    }

    @FunctionalInterface
    public interface ToolExecutor {
        ToolResult execute(Map<String, Object> parameters);
    }
}
