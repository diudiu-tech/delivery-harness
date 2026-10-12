package com.delivery.harness.llm.gateway;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.quality.RuntimeBinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
public class HttpLlmClient implements LlmClient {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    @Value("${harness.llm.default-endpoint:http://localhost:11434}")
    private String defaultEndpoint;

    @Value("${harness.llm.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${harness.llm.api-key:}")
    private String apiKey;
    @Value("${harness.mode:demo}")
    private String mode = "demo";
    @Value("${harness.llm.allowed-hosts:}")
    private String allowedHosts = "";

    @SuppressWarnings("unchecked")
    @Override
    public String chat(String model, List<LlmMessage> messages, List<Map<String, Object>> tools,
                       Double temperature, Integer maxTokens) throws IOException {
        RuntimeBinding.Snapshot config = RuntimeBinding.current();
        int seconds = config == null ? timeoutSeconds : config.timeoutSeconds();
        int retries = config == null ? 0 : config.maxRetries();
        long deadline = System.nanoTime() + Math.max(1, seconds) * 1_000_000_000L;
        for (int attempt = 0; ; attempt++) {
            try {
                return chatOnce(model, messages, tools, temperature, maxTokens, deadline);
            } catch (IOException e) {
                boolean retryable = e instanceof java.net.ConnectException
                        || e instanceof java.net.SocketTimeoutException
                        || (e instanceof RetryableHttpException);
                if (!retryable || attempt >= retries || System.nanoTime() >= deadline) {
                    throw e;
                }
                RuntimeBinding.recordCall(Map.of("retry_count", attempt + 1));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String chatOnce(String model, List<LlmMessage> messages, List<Map<String, Object>> tools,
                            Double temperature, Integer maxTokens, long deadline) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        body.put("stream", false);

        String endpoint = defaultEndpoint.endsWith("/")
                ? defaultEndpoint.substring(0, defaultEndpoint.length() - 1)
                : defaultEndpoint;
        String urlStr = endpoint + "/v1/chat/completions";
        validateEndpoint(urlStr);
        String jsonBody = JsonUtil.toJson(body);

        long startTime = System.currentTimeMillis();

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        conn.setConnectTimeout(Math.min(30000, remainingMs(deadline)));
        conn.setReadTimeout(remainingMs(deadline));
        conn.setDoOutput(true);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = conn.getResponseCode();
            long duration = System.currentTimeMillis() - startTime;
            log.info("LLM request: model={}, duration={}ms, status={}", model, duration, statusCode);

            if (statusCode < 200 || statusCode >= 300) {
                if (Set.of(429, 502, 503, 504).contains(statusCode)) {
                    throw new RetryableHttpException("LLM request failed with HTTP " + statusCode);
                }
                throw new IOException("LLM request failed with HTTP " + statusCode);
            }

            String responseBody = readStream(conn, deadline);
            Map<String, Object> result = JsonUtil.fromJson(responseBody, Map.class);
            Object usage = result.get("usage");
            if (usage instanceof Map<?, ?> values && values.get("prompt_tokens") instanceof Number prompt
                    && values.get("completion_tokens") instanceof Number completion) {
                RuntimeBinding.recordCall(Map.of("usage_status", "KNOWN", "prompt_tokens", prompt.longValue(),
                        "completion_tokens", completion.longValue(), "cost_status", "UNKNOWN"));
            }
            if (result.get("model") != null) {
                RuntimeBinding.recordCall(Map.of("provider_model", result.get("model")));
            }
            if (result.get("system_fingerprint") != null) {
                RuntimeBinding.recordCall(Map.of("provider_revision", result.get("system_fingerprint")));
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                if (message != null && message.get("content") != null) {
                    return String.valueOf(message.get("content"));
                }
            }

            throw new IOException("LLM response did not contain choices[0].message.content");
        } finally {
            conn.disconnect();
        }
    }

    private String readStream(HttpURLConnection conn, long deadline) throws IOException {
        InputStream stream;
        try {
            stream = conn.getInputStream();
        } catch (IOException e) {
            stream = conn.getErrorStream();
            if (stream == null) {
                return "";
            }
        }
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while (true) {
                conn.setReadTimeout(remainingMs(deadline));
                count = input.read(buffer);
                if (count == -1) {
                    break;
                }
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("LLM response exceeded " + MAX_RESPONSE_BYTES + " bytes");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int remainingMs(long deadline) throws java.net.SocketTimeoutException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) {
            throw new java.net.SocketTimeoutException("Model total deadline exceeded");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private void validateEndpoint(String endpoint) throws IOException {
        if (!"pilot".equals(mode)) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            String host = uri.getHost();
            Set<String> allowed = new HashSet<>();
            for (String item : allowedHosts.split(",")) {
                if (!item.isBlank()) {
                    allowed.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
            boolean loopback = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host);
            if (host == null || !allowed.contains(host.toLowerCase(Locale.ROOT)) || uri.getUserInfo() != null
                    || uri.getFragment() != null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback)) {
                throw new IllegalArgumentException("Rejected model endpoint");
            }
        } catch (RuntimeException e) {
            RuntimeBinding.recordCall(Map.of("error_code", "MODEL_ENDPOINT_NOT_ALLOWED"));
            throw new IOException("MODEL_ENDPOINT_NOT_ALLOWED");
        }
    }

    private static class RetryableHttpException extends IOException {
        RetryableHttpException(String message) {
            super(message);
        }
    }
}
