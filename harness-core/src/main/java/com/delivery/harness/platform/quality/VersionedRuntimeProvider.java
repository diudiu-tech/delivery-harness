package com.delivery.harness.platform.quality;

import com.delivery.harness.runtime.ExecutionRuntimeProvider;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The quality module supplies evaluated immutable configs to the independently usable online queue.
 */
@Service
@Primary
public class VersionedRuntimeProvider implements ExecutionRuntimeProvider {
    private final RuntimeConfigService configs;

    public VersionedRuntimeProvider(RuntimeConfigService configs) {
        this.configs = configs;
    }

    @Override
    public Map<String, Object> resolve(String tenant, String id) {
        return configs.resolve(tenant, id);
    }

    @Override
    public Map<String, Object> route(
            String tenant, String scenario, String city, String station, String caseId) {
        return configs.route(tenant, scenario, city, station, caseId);
    }
}
