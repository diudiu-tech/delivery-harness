package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class BoundedJsonValidator implements ConstraintValidator<BoundedJson, Object> {
    private int maxBytes;
    private int maxDepth;
    private int maxEntries;

    @Override
    public void initialize(BoundedJson constraint) {
        maxBytes = constraint.maxBytes();
        maxDepth = constraint.maxDepth();
        maxEntries = constraint.maxEntries();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String json = JsonUtil.toJson(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return false;
        }
        JsonNode node = JsonUtil.getMapper().valueToTree(value);
        return bounded(node, 0);
    }

    private boolean bounded(JsonNode node, int depth) {
        if (depth > maxDepth
                || node.size() > maxEntries
                || node.isTextual() && node.textValue().length() > 10_000) {
            return false;
        }
        var children = node.elements();
        while (children.hasNext()) {
            if (!bounded(children.next(), depth + 1)) {
                return false;
            }
        }
        return true;
    }
}
