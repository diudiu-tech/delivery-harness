package com.delivery.harness.llm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.runtime.RuntimeBinding;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class HttpLlmClientTest {
    @Test
    void chunkedMultibyteResponseIsBoundedByActualBytesAndIsNotRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        byte[] oversized =
                ("{\"choices\":[{\"message\":{\"content\":\"" + "元".repeat(360_000) + "\"}}]}")
                        .getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(requests, 200, oversized);
        try (RuntimeBinding ignored = binding(2, 2)) {
            IOException failure = assertThrows(IOException.class, () -> chat(client(server)));
            assertTrue(failure.getMessage().contains("byte limit"));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void slowBodyCannotExtendTheTotalBudgetByContinuouslySendingSmallChunks() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        for (int i = 0; i < 50; i++) {
                            exchange.getResponseBody().write(' ');
                            exchange.getResponseBody().flush();
                            Thread.sleep(80);
                        }
                    } catch (IOException disconnected) {
                        // The client is expected to close the stream once the shared deadline
                        // expires.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (RuntimeBinding ignored = binding(1, 2)) {
            assertTimeout(
                    Duration.ofMillis(2500),
                    () -> assertThrows(SocketTimeoutException.class, () -> chat(client(server))));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledUploadAndHeadersCannotOutliveTheSharedDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch accepted = new CountDownLatch(1);
            Thread provider =
                    new Thread(
                            () -> {
                                try (Socket connection = server.accept()) {
                                    accepted.countDown();
                                    // A provider that never reads the request exhausts the client's
                                    // upload buffer.
                                    Thread.sleep(5000);
                                } catch (IOException closed) {
                                    // Closing the test server releases an unfinished accept during
                                    // cleanup.
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            provider.start();
            try (RuntimeBinding ignored = binding(1, 2)) {
                HttpLlmClient client = new HttpLlmClient();
                ReflectionTestUtils.setField(
                        client, "defaultEndpoint", "http://127.0.0.1:" + server.getLocalPort());
                ReflectionTestUtils.setField(client, "timeoutSeconds", 1);
                ReflectionTestUtils.setField(client, "mode", "pilot");
                ReflectionTestUtils.setField(client, "allowedHosts", "127.0.0.1");
                assertTimeout(
                        Duration.ofMillis(2500),
                        () ->
                                assertThrows(
                                        IOException.class,
                                        () ->
                                                client.chat(
                                                        "test-model",
                                                        List.of(
                                                                LlmMessage.user(
                                                                        "x"
                                                                                .repeat(
                                                                                        8 * 1024
                                                                                                * 1024))),
                                                        List.of(),
                                                        0d,
                                                        100)));
                assertTrue(accepted.await(100, TimeUnit.MILLISECONDS));
            } finally {
                provider.interrupt();
                provider.join(1000);
            }
        }
    }

    @Test
    void onlyExplicitRetryableStatusesCanConsumeTheRetryBudget() throws Exception {
        for (int status : List.of(429, 502, 503, 504)) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = server(requests, status, new byte[0]);
            try (RuntimeBinding ignored = binding(2, 1)) {
                assertThrows(IOException.class, () -> chat(client(server)));
                assertEquals(2, requests.get(), "status=" + status);
                assertEquals(1, RuntimeBinding.callMetadata().get("retry_count"));
            } finally {
                server.stop(0);
            }
        }
        for (int status : List.of(302, 400, 401, 403, 404, 422, 500, 501)) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = server(requests, status, new byte[0]);
            try (RuntimeBinding ignored = binding(2, 2)) {
                assertThrows(IOException.class, () -> chat(client(server)));
                assertEquals(1, requests.get(), "status=" + status);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void unlistedEndpointsAndCancellationFailBeforeAnyNetworkRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(requests, 200, success());
        try {
            HttpLlmClient denied = client(server);
            ReflectionTestUtils.setField(denied, "allowedHosts", "other.example");
            assertEquals(
                    "MODEL_ENDPOINT_NOT_ALLOWED",
                    assertThrows(IOException.class, () -> chat(denied)).getMessage());
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> chat(client(server)));
            } finally {
                Thread.interrupted();
            }
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedResponseIsNotRetriedAndDoesNotEchoProviderText() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server =
                server(requests, 200, "not-json 13812345678".getBytes(StandardCharsets.UTF_8));
        try (RuntimeBinding ignored = binding(2, 2)) {
            IOException failure = assertThrows(IOException.class, () -> chat(client(server)));
            assertEquals("Invalid model response payload", failure.getMessage());
            assertEquals(null, failure.getCause());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static RuntimeBinding binding(int timeout, int retries) {
        return RuntimeBinding.bind(
                null,
                null,
                Map.of("model", "test-model", "timeout_seconds", timeout, "max_retries", retries),
                false);
    }

    private static String chat(HttpLlmClient client) throws IOException {
        return client.chat(
                "test-model", List.of(LlmMessage.user("Protocol test")), List.of(), 0d, 100);
    }

    private static HttpLlmClient client(HttpServer server) {
        HttpLlmClient client = new HttpLlmClient();
        ReflectionTestUtils.setField(
                client, "defaultEndpoint", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "timeoutSeconds", 2);
        ReflectionTestUtils.setField(client, "apiKey", "");
        ReflectionTestUtils.setField(client, "mode", "pilot");
        ReflectionTestUtils.setField(client, "allowedHosts", "127.0.0.1");
        return client;
    }

    private static HttpServer server(AtomicInteger requests, int status, byte[] body)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    if (status == 302) {
                        exchange.getResponseHeaders()
                                .set("Location", "http://untrusted.invalid/private");
                    }
                    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : 0);
                    try {
                        if (body.length > 0) {
                            exchange.getResponseBody().write(body);
                        }
                    } catch (IOException disconnected) {
                        // Bounded-response tests deliberately disconnect before the provider
                        // finishes writing.
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        return server;
    }

    private static byte[] success() {
        return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
    }
}
