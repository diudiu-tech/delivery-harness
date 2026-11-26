package com.delivery.harness.gateway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.gateway.controller.AnalyzeController;
import com.delivery.harness.gateway.controller.EvalController;
import com.delivery.harness.gateway.controller.KnowledgeController;
import com.delivery.harness.gateway.controller.ObserveController;
import com.delivery.harness.gateway.platform.security.ApiSecurityConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Sends raw request lines to a real Tomcat because MockMvc does not decode paths like the servlet
 * container. Spring MVC matches decoded segments, so encoded spellings must never reach v1 code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PilotLegacyApiPathTest.TestKeys.class)
@TestPropertySource(
        properties = {
            "harness.mode=pilot",
            "harness.security.demo-identities=[]",
            "harness.security.oidc.issuer=https://idp.example.test",
            "harness.security.oidc.audience=harness-api",
            "harness.security.oidc.decoder-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:pilot-legacy-path;DB_CLOSE_DELAY=-1",
            "harness.adapter.order-endpoint=https://orders.example.test/{order_id}",
            "harness.adapter.allowed-hosts=orders.example.test",
            "harness.knowledge.seed.enabled=false",
            "harness.eval.seed.enabled=false"
        })
class PilotLegacyApiPathTest {
    private static final String ISSUER = "https://idp.example.test";
    private static final RSAKey KEY = key();
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestKeys {
        @Bean
        JwtDecoder signedJwtDecoder() throws Exception {
            var decoder =
                    NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                            .signatureAlgorithm(SignatureAlgorithm.RS256)
                            .build();
            decoder.setJwtValidator(ApiSecurityConfiguration.validators(ISSUER, "harness-api"));
            return decoder;
        }
    }

    @Test
    void encodedAndCaseVariantsOfV1ReturnGoneInsteadOfReachingLegacyControllers()
            throws Exception {
        String token = token();
        for (String path :
                List.of(
                        "/api/v1/observe/traces",
                        "/api/%761/observe/traces",
                        "/api/v%31/observe/traces",
                        "/%61pi/v1/observe/traces",
                        "/api/v1/%6fbserve/traces",
                        "/api/v1/observe/metrics",
                        "/api/v1/eval/cases",
                        "/api/V1/observe/traces",
                        "/api/v1")) {
            String response = get(path, token);
            assertTrue(response.startsWith("HTTP/1.1 410 "), path + " => " + response);
            assertTrue(response.contains("\"V2_REQUIRED\""), path + " => " + response);
        }
        // Outside the case-sensitive /api/** chain and without any matching handler.
        assertTrue(get("/API/V1/observe/traces", token).startsWith("HTTP/1.1 404 "));
        for (String path :
                List.of(
                        "/api/%76%31/knowledge/search",
                        "/api/v%31/eval/run",
                        "/api/v%31/analyze/abnormal-order")) {
            String response = post(path, token);
            assertTrue(response.startsWith("HTTP/1.1 410 "), path + " => " + response);
        }
    }

    @Test
    void nonCanonicalPathsAreRejectedByTheFirewallBeforeAnyHandler() throws Exception {
        String token = token();
        for (String path :
                List.of(
                        "//api/v1/observe/traces",
                        "/api//v1/observe/traces",
                        "/api/./v1/observe/traces",
                        "/api/v2/../v1/observe/traces",
                        "/api/v1;x=y/observe/traces",
                        "/api/%2e/v1/observe/traces",
                        "/api/v1%2Fobserve/traces",
                        "/api%5Cv1/observe/traces")) {
            String response = get(path, token);
            assertTrue(response.startsWith("HTTP/1.1 400 "), path + " => " + response);
            assertFalse(response.contains("\"success\""), path + " => " + response);
        }
    }

    @Test
    void legacyControllersAreNotRegisteredInPilot() throws Exception {
        for (Class<?> legacy :
                List.of(
                        AnalyzeController.class,
                        EvalController.class,
                        KnowledgeController.class,
                        ObserveController.class)) {
            assertEquals(0, context.getBeanNamesForType(legacy).length, legacy.getName());
        }
        mappings.getHandlerMethods()
                .forEach(
                        (route, handler) ->
                                route.getPatternValues()
                                        .forEach(
                                                pattern ->
                                                        assertFalse(
                                                                pattern.toLowerCase()
                                                                        .startsWith("/api/v1"),
                                                                pattern + " -> " + handler)));
        assertTrue(get("/api/v2/session", token()).startsWith("HTTP/1.1 200 "));
        assertTrue(get("/api/%76%32/session", "").startsWith("HTTP/1.1 401 "), "anonymous");
    }

    private String get(String path, String token) throws IOException {
        return send("GET " + path, token, "");
    }

    private String post(String path, String token) throws IOException {
        return send("POST " + path, token, "{}");
    }

    private String send(String requestLine, String token, String body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            StringBuilder request =
                    new StringBuilder(requestLine)
                            .append(" HTTP/1.1\r\nHost: localhost:")
                            .append(port)
                            .append("\r\nConnection: close\r\n");
            if (!token.isEmpty()) {
                request.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            if (!body.isEmpty()) {
                request.append("Content-Type: application/json\r\nContent-Length: ")
                        .append(body.length())
                        .append("\r\n");
            }
            request.append("\r\n").append(body);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String token() throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("user-a")
                        .audience("harness-api")
                        .issueTime(Date.from(Instant.now().minusSeconds(30)))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("tenant_id", "tenant-a")
                        .claim("roles", List.of("AGENT"))
                        .claim("city_codes", List.of("CITY-A"))
                        .claim("station_ids", List.of("STATION-A"))
                        .build();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                        claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
