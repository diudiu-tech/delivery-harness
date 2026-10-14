package com.delivery.harness.runtime;

import java.util.Map;

/**
 * Frozen executable configurations supplied independently of queue and version-management
 * implementations.
 */
public interface ExecutionRuntimeProvider {
    Map<String, Object> resolve(String tenant, String id);

    Map<String, Object> route(
            String tenant, String scenario, String city, String station, String caseId);
}
