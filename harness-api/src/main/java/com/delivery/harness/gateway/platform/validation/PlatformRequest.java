package com.delivery.harness.gateway.platform.validation;

import com.delivery.harness.common.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public interface PlatformRequest {
    default Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>(JsonUtil.toMap(this));
        values.values().removeIf(value -> value == null);
        return values;
    }
}
