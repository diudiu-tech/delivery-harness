package com.delivery.harness.tool.gateway;

import com.delivery.harness.common.dto.ToolDefinition;
import com.delivery.harness.common.dto.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolGatewayTest {

    @Test
    void keepsOnlyTheConfiguredNumberOfRecentInvocations() {
        ToolGateway gateway = new ToolGateway(2);
        gateway.register("demo", ToolDefinition.builder().toolName("demo").build(),
                parameters -> ToolResult.builder().toolName("demo").success(true).build());

        gateway.invoke("demo", Collections.emptyMap());
        gateway.invoke("demo", Collections.emptyMap());
        gateway.invoke("demo", Collections.emptyMap());

        assertEquals(2, gateway.getInvocationLog().size());
    }
}
