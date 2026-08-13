package com.delivery.harness.llm.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PromptRegistry {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    public void register(String templateId, String name, String content) {
        templates.put(templateId, new PromptTemplate(templateId, name, content, 1));
        log.info("Prompt template registered: id={}, name={}", templateId, name);
    }

    public String render(String templateId, Map<String, Object> variables) {
        PromptTemplate template = templates.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Prompt template not found: " + templateId);
        }
        return renderTemplate(template.getContent(), variables);
    }

    public PromptTemplate get(String templateId) {
        return templates.get(templateId);
    }

    public Map<String, PromptTemplate> listAll() {
        return Collections.unmodifiableMap(new HashMap<>(templates));
    }

    private String renderTemplate(String template, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = variables.getOrDefault(varName, "{{" + varName + "}}");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PromptTemplate {
        private String id;
        private String name;
        private String content;
        private Integer version;
    }
}
