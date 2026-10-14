package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.store.PlatformException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class OrderSourceTest {
    private final Principal actor =
            new Principal("t", "agent", Set.of("AGENT"), Set.of("CITY"), Set.of("STATION"));

    @Test
    void liveNotFoundNeverFallsBackToSyntheticData() throws Exception {
        withServer(
                404,
                Map.of(),
                source -> {
                    PlatformException failure =
                            assertThrows(
                                    PlatformException.class,
                                    () -> source.query(actor, "NONEXISTENT"));
                    assertEquals("NOT_FOUND", failure.code());
                    assertEquals(404, failure.status());
                });
    }

    @Test
    void sourceSchemaDriftIsInvalidData() throws Exception {
        withServer(
                200,
                Map.of("schema_version", "unknown"),
                source ->
                        assertEquals(
                                "INVALID_DATA",
                                assertThrows(
                                                PlatformException.class,
                                                () -> source.query(actor, "ORDER"))
                                        .code()));
    }

    @Test
    void liveQueryNormalizesAndScopesTheOrder() throws Exception {
        Map<String, Object> order =
                Map.of(
                        "orderId",
                        "ORDER",
                        "status",
                        "DELIVERED",
                        "orderAmount",
                        25,
                        "cityCode",
                        "CITY",
                        "stationId",
                        "STATION");
        withServer(
                200,
                Map.of(
                        "schema_version",
                        "1",
                        "source_version",
                        "revision-17",
                        "collected_at",
                        Instant.now().toString(),
                        "source_timezone",
                        "Asia/Shanghai",
                        "currency",
                        "CNY",
                        "order",
                        order),
                source -> {
                    assertEquals("ORDER", source.query(actor, "ORDER").order().getOrderId());
                    assertEquals(
                            "LIVE_READ_ONLY",
                            source.query(actor, "ORDER").metadata().get("source_type"));
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    source.query(
                                            new Principal(
                                                    "t",
                                                    "denied",
                                                    Set.of("AGENT"),
                                                    Set.of("OTHER"),
                                                    Set.of("STATION")),
                                            "ORDER"));
                });
    }

    @Test
    void equivalentOffsetAndLocalTimesNormalizeToTheSameShanghaiInstant() throws Exception {
        Map<String, Object> order =
                new LinkedHashMap<>(
                        Map.of(
                                "orderId",
                                "ORDER",
                                "status",
                                "DELIVERED",
                                "orderAmount",
                                25,
                                "cityCode",
                                "CITY",
                                "stationId",
                                "STATION"));
        order.put("createTime", "2026-09-30T01:00:00Z");
        order.put("acceptTime", "2026-09-30T09:00:00");
        withServer(
                200,
                Map.of(
                        "schema_version",
                        "1",
                        "source_version",
                        "revision-17",
                        "collected_at",
                        Instant.now().toString(),
                        "source_timezone",
                        "Asia/Shanghai",
                        "currency",
                        "CNY",
                        "order",
                        order),
                source -> {
                    var snapshot = source.query(actor, "ORDER");
                    assertEquals(
                            snapshot.order().getCreateTime(), snapshot.order().getAcceptTime());
                    assertEquals(9, snapshot.order().getCreateTime().getHour());
                    assertEquals("Asia/Shanghai", snapshot.metadata().get("normalized_timezone"));
                });
    }

    @Test
    void staleSourcesRemainVisibleAsQualityGaps() throws Exception {
        Map<String, Object> order =
                Map.of(
                        "orderId",
                        "ORDER",
                        "status",
                        "DELIVERED",
                        "orderAmount",
                        25,
                        "cityCode",
                        "CITY",
                        "stationId",
                        "STATION");
        withServer(
                200,
                Map.of(
                        "schema_version",
                        "1",
                        "source_version",
                        "old",
                        "collected_at",
                        Instant.now().minusSeconds(4000).toString(),
                        "source_timezone",
                        "Asia/Shanghai",
                        "currency",
                        "CNY",
                        "order",
                        order),
                source ->
                        assertEquals(
                                "STALE",
                                source.query(actor, "ORDER").metadata().get("quality_status")));
    }

    @Test
    void sourceMetadataAndOrderPresentationCannotLeakUncontractedPersonalFields() throws Exception {
        Map<String, Object> order =
                new LinkedHashMap<>(
                        Map.of(
                                "orderId",
                                "ORDER",
                                "status",
                                "DELIVERED",
                                "orderAmount",
                                25,
                                "cityCode",
                                "CITY",
                                "stationId",
                                "STATION"));
        order.put("riderName", "Private Rider");
        order.put(
                "extraInfo",
                Map.of(
                        "customer_phone",
                        "13800000000",
                        "nested",
                        Map.of("name", "Customer"),
                        "weather",
                        "晴"));
        Map<String, Object> envelope =
                new LinkedHashMap<>(
                        Map.of(
                                "schema_version",
                                "1",
                                "source_version",
                                "revision",
                                "collected_at",
                                Instant.now().toString(),
                                "source_timezone",
                                "Asia/Shanghai",
                                "currency",
                                "CNY",
                                "order",
                                order));
        envelope.put("private_customer", Map.of("name", "Customer"));
        withServer(
                200,
                envelope,
                source -> {
                    var snapshot = source.query(actor, "ORDER");
                    assertFalse(snapshot.metadata().containsKey("private_customer"));
                    String publicOrder =
                            JsonUtil.toJson(Redaction.safe(JsonUtil.toMap(snapshot.order())));
                    assertFalse(publicOrder.contains("Private Rider"));
                    assertFalse(publicOrder.contains("13800000000"));
                    assertFalse(publicOrder.contains("Customer"));
                });
    }

    @Test
    void ambiguousDaylightSavingTimesAndFutureCompletedEventsAreInvalidData() throws Exception {
        for (String time : List.of("2026-11-01T01:30:00", "2026-03-08T02:30:00")) {
            Map<String, Object> order =
                    new LinkedHashMap<>(
                            Map.of(
                                    "orderId",
                                    "ORDER",
                                    "status",
                                    "DELIVERED",
                                    "orderAmount",
                                    25,
                                    "cityCode",
                                    "CITY",
                                    "stationId",
                                    "STATION"));
            order.put("createTime", time);
            withServer(
                    200,
                    Map.of(
                            "schema_version",
                            "1",
                            "source_version",
                            "revision",
                            "collected_at",
                            Instant.now().toString(),
                            "source_timezone",
                            "America/New_York",
                            "currency",
                            "CNY",
                            "order",
                            order),
                    source ->
                            assertEquals(
                                    "INVALID_DATA",
                                    assertThrows(
                                                    PlatformException.class,
                                                    () -> source.query(actor, "ORDER"))
                                            .code()));
        }
        Map<String, Object> order =
                new LinkedHashMap<>(
                        Map.of(
                                "orderId",
                                "ORDER",
                                "status",
                                "DELIVERED",
                                "orderAmount",
                                25,
                                "cityCode",
                                "CITY",
                                "stationId",
                                "STATION"));
        order.put("deliveryTime", Instant.now().plusSeconds(3600).toString());
        withServer(
                200,
                Map.of(
                        "schema_version",
                        "1",
                        "source_version",
                        "revision",
                        "collected_at",
                        Instant.now().toString(),
                        "source_timezone",
                        "Asia/Shanghai",
                        "currency",
                        "CNY",
                        "order",
                        order),
                source ->
                        assertEquals(
                                "INVALID_DATA",
                                assertThrows(
                                                PlatformException.class,
                                                () -> source.query(actor, "ORDER"))
                                        .code()));
    }

    @Test
    void untrustedRemoteHostsAndNonTlsEndpointsFailClosed() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new OrderSource(
                                "pilot",
                                "http://external.example/orders/{order_id}",
                                "external.example",
                                ""));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new OrderSource(
                                "pilot",
                                "https://external.example/orders/{order_id}",
                                "other.example",
                                ""));
    }

    @Test
    void oversizedChunkedBodyIsRejectedAndCancelledBeforeDownloadingWholeResponse()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicLong written = new AtomicLong();
        CountDownLatch ended = new CountDownLatch(1);
        long offered = 16_000_000;
        server.createContext(
                "/orders/",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    byte[] chunk = "x".repeat(16_000).getBytes(StandardCharsets.UTF_8);
                    try (var out = exchange.getResponseBody()) {
                        while (written.get() < offered) {
                            out.write(chunk);
                            out.flush();
                            written.addAndGet(chunk.length);
                        }
                    } catch (IOException clientCancelled) {
                        // The bounded subscriber closes this connection as soon as its byte budget
                        // is exceeded.
                    } finally {
                        ended.countDown();
                    }
                });
        server.start();
        try {
            var source =
                    new OrderSource(
                            "pilot",
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/orders/{order_id}",
                            "127.0.0.1",
                            "");
            var failure = assertThrows(PlatformException.class, () -> source.query(actor, "ORDER"));
            assertEquals("INVALID_DATA", failure.code());
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            assertTrue(
                    written.get() < offered,
                    "Adapter should cancel the response before all offered bytes are downloaded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void absentRefundableBalanceStaysUnknownAndZeroIsExplicitlyKnown() throws Exception {
        for (Object balance : new Object[] {null, BigDecimal.ZERO, new BigDecimal("12.34")}) {
            Map<String, Object> order = balanceOrder();
            if (balance != null) order.put("refundableBalance", balance);
            withServer(
                    200,
                    balanceEnvelope(order),
                    source -> {
                        var snapshot = source.query(actor, "ORDER");
                        assertEquals(balance, snapshot.order().getRefundableBalance());
                        assertEquals(
                                balance == null ? "UNKNOWN" : "KNOWN",
                                snapshot.metadata().get("refundable_balance_status"));
                    });
        }
    }

    @Test
    void illegalRefundableBalanceFailsClosed() throws Exception {
        for (String value : List.of("-0.01", "25.01", "0.001")) {
            Map<String, Object> order = balanceOrder();
            order.put("refundableBalance", new BigDecimal(value));
            withServer(
                    200,
                    balanceEnvelope(order),
                    source ->
                            assertEquals(
                                    "INVALID_DATA",
                                    assertThrows(
                                                    PlatformException.class,
                                                    () -> source.query(actor, "ORDER"))
                                            .code()));
        }
    }

    @Test
    void slowStreamingBodyCannotOutliveTheOverallDeadline() throws Exception {
        try (SlowResponse response = slowResponse()) {
            OrderSource source = response.source(Duration.ofMillis(300));
            long started = System.nanoTime();
            PlatformException failure =
                    assertThrows(PlatformException.class, () -> source.query(actor, "ORDER"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("TIMEOUT", failure.code());
            assertEquals(504, failure.status());
            assertEquals(0, response.streaming().getCount(), "Headers and body must have started");
            assertTrue(elapsedMillis < 1500, "Body streaming must share the request deadline");
            assertTrue(response.ended().await(2, TimeUnit.SECONDS));
            assertTrue(response.written().get() < 150, "The timed-out body must be cancelled");
        }
    }

    @Test
    void interruptingBodyReadCancelsTheExchangeAndPreservesTheInterrupt() throws Exception {
        try (SlowResponse response = slowResponse()) {
            OrderSource source = response.source(Duration.ofSeconds(5));
            AtomicReference<PlatformException> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread caller =
                    new Thread(
                            () -> {
                                try {
                                    source.query(actor, "ORDER");
                                } catch (PlatformException rejected) {
                                    failure.set(rejected);
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                }
                            });
            try {
                caller.start();
                assertTrue(response.streaming().await(2, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(1500);
                assertFalse(caller.isAlive(), "Interrupted reads must not wait for the body");
                assertNotNull(failure.get());
                assertEquals("CANCELLED", failure.get().code());
                assertTrue(interrupted.get());
                assertTrue(response.ended().await(2, TimeUnit.SECONDS));
                assertTrue(response.written().get() < 150);
            } finally {
                caller.interrupt();
                caller.join(1500);
            }
        }
    }

    private record SlowResponse(
            HttpServer server, AtomicLong written, CountDownLatch streaming, CountDownLatch ended)
            implements AutoCloseable {
        private OrderSource source(Duration deadline) {
            return new OrderSource(
                    "pilot",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/orders/{order_id}",
                    "127.0.0.1",
                    "",
                    deadline);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private SlowResponse slowResponse() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicLong written = new AtomicLong();
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        server.createContext(
                "/orders/",
                exchange -> {
                    try {
                        exchange.sendResponseHeaders(200, 0);
                        try (var out = exchange.getResponseBody()) {
                            for (int index = 0; index < 150; index++) {
                                out.write(' ');
                                out.flush();
                                written.incrementAndGet();
                                streaming.countDown();
                                Thread.sleep(20);
                            }
                        }
                    } catch (IOException cancelled) {
                        // Client cancellation closes the stream before all offered chunks are sent.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        ended.countDown();
                    }
                });
        server.start();
        return new SlowResponse(server, written, streaming, ended);
    }

    private Map<String, Object> balanceOrder() {
        return new LinkedHashMap<>(
                Map.of(
                        "orderId",
                        "ORDER",
                        "status",
                        "DELIVERED",
                        "orderAmount",
                        25,
                        "cityCode",
                        "CITY",
                        "stationId",
                        "STATION"));
    }

    private Map<String, Object> balanceEnvelope(Map<String, Object> order) {
        return Map.of(
                "schema_version",
                "1",
                "source_version",
                "revision-balance",
                "collected_at",
                Instant.now().toString(),
                "source_timezone",
                "Asia/Shanghai",
                "currency",
                "CNY",
                "order",
                order);
    }

    private void withServer(int status, Map<String, Object> body, Consumer<OrderSource> test)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/orders/",
                exchange -> {
                    byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            test.accept(
                    new OrderSource(
                            "pilot",
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/orders/{order_id}",
                            "127.0.0.1",
                            ""));
        } finally {
            server.stop(0);
        }
    }
}
