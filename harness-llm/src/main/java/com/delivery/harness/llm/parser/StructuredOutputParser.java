package com.delivery.harness.llm.parser;

import com.delivery.harness.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class StructuredOutputParser {

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(\\{.*})", Pattern.DOTALL);

    public <T> T parse(String llmOutput, Class<T> targetType) {
        String json = extractJson(llmOutput);
        if (json == null) {
            throw new IllegalArgumentException("No valid JSON found in LLM output");
        }
        return JsonUtil.fromJson(json, targetType);
    }

    public String extractJson(String llmOutput) {
        if (llmOutput == null || llmOutput.trim().isEmpty()) {
            return null;
        }

        // Try ```json ... ``` block first
        Matcher blockMatcher = JSON_BLOCK_PATTERN.matcher(llmOutput);
        if (blockMatcher.find()) {
            String candidate = blockMatcher.group(1).trim();
            return isValidJson(candidate) ? candidate : null;
        }

        // Try raw JSON object
        Matcher objectMatcher = JSON_OBJECT_PATTERN.matcher(llmOutput);
        if (objectMatcher.find()) {
            String candidate = objectMatcher.group(1).trim();
            if (isValidJson(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private boolean isValidJson(String str) {
        try {
            JsonUtil.getMapper().readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
