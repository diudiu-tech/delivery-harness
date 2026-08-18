package com.delivery.harness.llm.gateway;

import com.delivery.harness.common.util.JsonUtil;
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

    @SuppressWarnings("unchecked")
    @Override
    public String chat(String model, List<LlmMessage> messages, List<Map<String, Object>> tools,
                       Double temperature, Integer maxTokens) throws IOException {
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
        String jsonBody = JsonUtil.toJson(body);

        long startTime = System.currentTimeMillis();

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        conn.setDoOutput(true);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = conn.getResponseCode();
            long duration = System.currentTimeMillis() - startTime;
            log.info("LLM request: model={}, duration={}ms, status={}", model, duration, statusCode);

            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("LLM request failed with HTTP " + statusCode);
            }

            String responseBody = readStream(conn);
            Map<String, Object> result = JsonUtil.fromJson(responseBody, Map.class);

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

    private String readStream(HttpURLConnection conn) throws IOException {
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
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("LLM response exceeded " + MAX_RESPONSE_BYTES + " bytes");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
