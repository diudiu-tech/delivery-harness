package com.delivery.harness.llm.gateway;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.runtime.RuntimeBinding;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class HttpLlmClient implements LlmClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Set<Integer> RETRYABLE_HTTP_STATUSES = Set.of(429, 502, 503, 504);
    private static final Set<String> LOOPBACK_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

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

    @Override
    public String chat(
            String model,
            List<LlmMessage> messages,
            List<Map<String, Object>> tools,
            Double temperature,
            Integer maxTokens)
            throws IOException {
        RuntimeBinding.Snapshot config = RuntimeBinding.current();
        int seconds = config == null ? timeoutSeconds : config.timeoutSeconds();
        int retries = config == null ? 0 : config.maxRetries();
        if (seconds < 1 || seconds > 120 || retries < 0 || retries > 2) {
            throw new IOException("Invalid model timeout or retry budget");
        }
        long deadline =
                RuntimeBinding.requestDeadline(System.nanoTime() + seconds * 1_000_000_000L);
        for (int attempt = 0; ; attempt++) {
            remainingNanos(deadline);
            try {
                return chatOnce(model, messages, tools, temperature, maxTokens, deadline);
            } catch (IOException failure) {
                boolean retryable =
                        failure instanceof ConnectException
                                || failure instanceof SocketTimeoutException
                                || failure instanceof HttpTimeoutException
                                || failure instanceof RetryableHttpException;
                if (!retryable || attempt >= retries || System.nanoTime() >= deadline) {
                    throw failure;
                }
                RuntimeBinding.recordCall(Map.of("retry_count", attempt + 1));
            }
        }
    }

    private String chatOnce(
            String model,
            List<LlmMessage> messages,
            List<Map<String, Object>> tools,
            Double temperature,
            Integer maxTokens,
            long deadline)
            throws IOException {
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
        String endpoint =
                defaultEndpoint.endsWith("/")
                        ? defaultEndpoint.substring(0, defaultEndpoint.length() - 1)
                        : defaultEndpoint;
        String target = endpoint + "/v1/chat/completions";
        validateEndpoint(target);
        byte[] requestBody = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofNanos(remainingNanos(deadline)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        long start = System.nanoTime();
        // Waiting on the complete future bounds upload, headers and body with one shared deadline.
        // HttpRequest.timeout alone does not bound a slowly streamed response body.
        CompletableFuture<HttpResponse<String>> pending =
                HTTP_CLIENT.sendAsync(
                        builder.build(),
                        response ->
                                new BoundedBodySubscriber(
                                        response.headers()
                                                .firstValueAsLong("Content-Length")
                                                .orElse(-1)));
        HttpResponse<String> response;
        try {
            response = pending.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Model request cancelled");
        } catch (TimeoutException timeout) {
            throw new SocketTimeoutException("Model total deadline exceeded");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Model HTTP request failed");
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
        remainingNanos(deadline);
        int status = response.statusCode();
        RuntimeBinding.recordCall(Map.of("http_status", status));
        log.info(
                "LLM request: model={}, duration={}ms, status={}",
                model,
                (System.nanoTime() - start) / 1_000_000,
                status);
        if (status < 200 || status >= 300) {
            if (RETRYABLE_HTTP_STATUSES.contains(status)) {
                throw new RetryableHttpException("LLM request failed with HTTP " + status);
            }
            throw new IOException("LLM request failed with HTTP " + status);
        }
        String content = content(response.body());
        remainingNanos(deadline);
        return content;
    }

    private static String content(String response) throws IOException {
        Map<String, Object> result;
        try {
            result = JsonUtil.fromJson(response, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException invalid) {
            // Jackson's diagnostic may quote private provider output. Keep it outside the exception
            // chain.
            throw new IOException("Invalid model response payload");
        }
        if (result == null) {
            throw new IOException("Invalid model response payload");
        }
        if (result.get("usage") instanceof Map<?, ?> usage
                && usage.get("prompt_tokens") instanceof Number prompt
                && usage.get("completion_tokens") instanceof Number completion
                && prompt.longValue() >= 0
                && completion.longValue() >= 0) {
            RuntimeBinding.recordCall(
                    Map.of(
                            "usage_status",
                            "KNOWN",
                            "prompt_tokens",
                            prompt.longValue(),
                            "completion_tokens",
                            completion.longValue(),
                            "cost_status",
                            "UNKNOWN"));
        }
        if (result.get("model") instanceof String providerModel) {
            RuntimeBinding.recordCall(Map.of("provider_model", providerModel));
        }
        if (result.get("system_fingerprint") instanceof String revision) {
            RuntimeBinding.recordCall(Map.of("provider_revision", revision));
        }
        if (result.get("choices") instanceof List<?> choices
                && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") instanceof String text) {
            return text;
        }
        throw new IOException("LLM response did not contain choices[0].message.content");
    }

    private static long remainingNanos(long deadline) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model request cancelled");
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Model total deadline exceeded");
        }
        return remaining;
    }

    private void validateEndpoint(String endpoint) throws IOException {
        if (!"pilot".equals(mode)) {
            return;
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            Set<String> allowed = new HashSet<>();
            for (String item : allowedHosts.split(",")) {
                if (!item.isBlank()) {
                    allowed.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
            boolean loopback =
                    host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
            boolean scheme =
                    "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback;
            if (host == null
                    || !allowed.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !scheme) {
                throw new IllegalArgumentException("Rejected model endpoint");
            }
        } catch (RuntimeException rejected) {
            RuntimeBinding.recordCall(Map.of("error_code", "MODEL_ENDPOINT_NOT_ALLOWED"));
            throw new IOException("MODEL_ENDPOINT_NOT_ALLOWED");
        }
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final boolean oversizedHeader;
        private Flow.Subscription subscription;
        private int total;

        private BoundedBodySubscriber(long contentLength) {
            oversizedHeader = contentLength > MAX_RESPONSE_BYTES;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscribed) {
            subscription = subscribed;
            if (oversizedHeader) {
                rejectOversizedResponse();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                int bytes = chunk.remaining();
                if (bytes > MAX_RESPONSE_BYTES - total) {
                    rejectOversizedResponse();
                    return;
                }
                byte[] received = new byte[bytes];
                chunk.get(received);
                output.writeBytes(received);
                total += bytes;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(new IOException("Model response stream failed"));
        }

        @Override
        public void onComplete() {
            body.complete(output.toString(StandardCharsets.UTF_8));
        }

        private void rejectOversizedResponse() {
            subscription.cancel();
            body.completeExceptionally(new IOException("LLM response exceeded byte limit"));
        }
    }

    private static final class RetryableHttpException extends IOException {
        private RetryableHttpException(String message) {
            super(message);
        }
    }
}
