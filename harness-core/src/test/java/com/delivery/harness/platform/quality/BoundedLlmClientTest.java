package com.delivery.harness.platform.quality;

import com.delivery.harness.llm.gateway.HttpLlmClient;
import com.delivery.harness.llm.gateway.LlmMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedLlmClientTest {
    @Test
    void pilotDeniesUnlistedHostsBeforeAnyRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(requests, false, false);
        try {
            HttpLlmClient client = client(server, "");
            IOException error = assertThrows(IOException.class, () -> client.chat("test-model", messages(), List.of(), 0d, 100));
            assertEquals("MODEL_ENDPOINT_NOT_ALLOWED", error.getMessage());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void allowlistedLoopbackReadsProviderUsageAndOnlyRetriesExplicitRetryableStatus() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(requests, true, false);
        try {
            HttpLlmClient client = client(server, "127.0.0.1");
            try (RuntimeBinding ignored = RuntimeBinding.bind(null, null, Map.of("model", "test-model", "max_retries", 1,
                    "timeout_seconds", 2, "pricing", Map.of("version", "prices-v1", "currency", "CNY", "input_per_million", "10", "output_per_million", "20")), false)) {
                assertEquals("{\"ok\":true}", client.chat("test-model", messages(), List.of(), 0d, 100));
                assertEquals(2, requests.get());
                assertEquals(1, RuntimeBinding.callMetadata().get("retry_count"));
                assertEquals("KNOWN", RuntimeBinding.callMetadata().get("usage_status"));
                assertEquals(100L, RuntimeBinding.callMetadata().get("prompt_tokens"));
                assertEquals("provider-revision-1", RuntimeBinding.callMetadata().get("provider_revision"));
                assertEquals("KNOWN", RuntimeBinding.callMetadata().get("cost_status"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modelRedirectIsNotFollowed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(requests, false, true);
        try {
            HttpLlmClient client = client(server, "127.0.0.1");
            IOException error = assertThrows(IOException.class, () -> client.chat("test-model", messages(), List.of(), 0d, 100));
            assertTrue(error.getMessage().contains("302"));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicInteger requests, boolean rateLimited, boolean redirect) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int count = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (redirect) {
                exchange.getResponseHeaders().set("Location", "http://untrusted.invalid/private");
                exchange.sendResponseHeaders(302, -1);
            } else if (rateLimited && count == 1) {
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] response = "{\"model\":\"provider-model-v1\",\"system_fingerprint\":\"provider-revision-1\",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20},\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private HttpLlmClient client(HttpServer server, String allowedHosts) throws Exception {
        HttpLlmClient client = new HttpLlmClient();
        set(client, "defaultEndpoint", "http://127.0.0.1:" + server.getAddress().getPort());
        set(client, "apiKey", "");
        set(client, "timeoutSeconds", 2);
        set(client, "mode", "pilot");
        set(client, "allowedHosts", allowedHosts);
        return client;
    }

    private List<LlmMessage> messages() {
        return List.of(LlmMessage.builder().role("user").content("protocol test").build());
    }

    private void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
