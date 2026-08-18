package com.delivery.harness.gateway.support;

import com.delivery.harness.llm.gateway.LlmClient;
import com.delivery.harness.llm.gateway.LlmMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Test double for the model transport.
 *
 * <p>Records the prompts it receives and returns whatever the test tells it
 * to. Hand-written rather than mocked because the build excludes
 * mockito-core: the JDK's agent-attachment restrictions made it unreliable
 * here, and a stub of this size does not need a framework.
 *
 * <p>Recording the prompt is the point, not an extra. The property most worth
 * testing about this system is that the evidence it assembles depends on the
 * order it was asked about, and the prompt is where that evidence ends up.
 */
public class StubLlmClient implements LlmClient {

    private final List<String> userPrompts = new CopyOnWriteArrayList<>();
    private final List<String> systemPrompts = new CopyOnWriteArrayList<>();

    private volatile Function<String, String> responder = prompt -> "{}";
    private volatile IOException failure;

    /** Always return this content, whatever the prompt. */
    public void respondWith(String content) {
        this.failure = null;
        this.responder = prompt -> content;
    }

    /** Derive the response from the user prompt. */
    public void respondWith(Function<String, String> responder) {
        this.failure = null;
        this.responder = responder;
    }

    /** Simulate an unreachable or failing model endpoint. */
    public void failWith(String message) {
        this.failure = new IOException(message);
    }

    public void reset() {
        userPrompts.clear();
        systemPrompts.clear();
        responder = prompt -> "{}";
        failure = null;
    }

    public List<String> userPrompts() {
        return new ArrayList<>(userPrompts);
    }

    public List<String> systemPrompts() {
        return new ArrayList<>(systemPrompts);
    }

    public String lastUserPrompt() {
        return userPrompts.isEmpty() ? null : userPrompts.get(userPrompts.size() - 1);
    }

    public int callCount() {
        return userPrompts.size();
    }

    @Override
    public String chat(
            String model,
            List<LlmMessage> messages,
            List<Map<String, Object>> tools,
            Double temperature,
            Integer maxTokens) throws IOException {

        for (LlmMessage message : messages) {
            if ("system".equals(message.getRole())) {
                systemPrompts.add(message.getContent());
            } else {
                userPrompts.add(message.getContent());
            }
        }
        if (failure != null) {
            throw failure;
        }
        return responder.apply(lastUserPrompt());
    }
}
