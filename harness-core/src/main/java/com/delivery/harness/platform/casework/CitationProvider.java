package com.delivery.harness.platform.casework;

import java.util.Map;

/** Optional versioned citations; case evidence itself does not require the quality subsystem. */
public interface CitationProvider {
    Map<String, Object> citation(String tenant, String id);
}
