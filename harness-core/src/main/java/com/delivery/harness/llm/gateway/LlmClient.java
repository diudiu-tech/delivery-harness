package com.delivery.harness.llm.gateway;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Transport to an OpenAI-compatible chat-completions endpoint.
 *
 * <p>Extracted from the concrete HTTP implementation so the workflows can be
 * exercised end to end without a running model. Previously the only
 * implementation opened an {@code HttpURLConnection} in its {@code chat}
 * method, which meant no test could reach either workflow's logic: the suite
 * covered text splitting, JSON extraction and request validation, but nothing
 * that produced an answer.
 *
 * <p>{@link HttpLlmClient} is the production implementation. Tests supply a
 * stub that returns canned content and records the prompts it was given.
 */
public interface LlmClient {

    /**
     * Sends a chat completion request and returns the assistant's content.
     *
     * @throws IOException when the endpoint is unreachable, returns a non-2xx
     *                     status, or returns a body without
     *                     {@code choices[0].message.content}
     */
    String chat(
            String model,
            List<LlmMessage> messages,
            List<Map<String, Object>> tools,
            Double temperature,
            Integer maxTokens) throws IOException;
}
