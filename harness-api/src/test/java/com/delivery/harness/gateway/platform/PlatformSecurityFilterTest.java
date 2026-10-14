package com.delivery.harness.gateway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.delivery.harness.gateway.platform.security.ApiSecurityConfiguration;
import com.delivery.harness.gateway.platform.security.AuthenticationThrottle;
import com.delivery.harness.gateway.platform.security.BrowserBoundaryFilter;
import com.delivery.harness.gateway.platform.security.DemoIdentityConfig;
import com.delivery.harness.gateway.platform.security.PlatformErrorHandler;
import com.delivery.harness.gateway.platform.security.PlatformSecurityFilter;
import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.auth.PrincipalDirectory;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class PlatformSecurityFilterTest {
    private static final String CLAIMS =
            "[{\"token_sha256\":"
                    + "\"8766b9cb08e6040b704f1e3ee1e186efccf2635b1d2634d6525333007e6aeae1\","
                    + "\"tenant_id\":\"tenant-a\",\"user_id\":\"agent-a\",\"roles\":[\"AGENT\"],"
                    + "\"city_codes\":[\"CITY-A\"],\"station_ids\":[\"STATION-A\"]}]";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publicDemoCredentialsHaveSmallSeparateRoleSetsAndAnIndependentPublisher() {
        var demo = new DemoIdentityConfig(DemoIdentityConfig.publicDemoIdentities());
        assertEquals(
                Set.of("AGENT", "OPERATOR"), demo.authenticate("Bearer demo-agent-token").roles());
        assertEquals(Set.of("REVIEWER"), demo.authenticate("Bearer demo-reviewer-token").roles());
        assertEquals(
                Set.of("REVIEWER", "SUPERVISOR"),
                demo.authenticate("Bearer demo-supervisor-token").roles());
        assertEquals(
                Set.of("QUALITY", "POLICY_PUBLISHER"),
                demo.authenticate("Bearer demo-publisher-token").roles());
        assertEquals(
                Set.of("POLICY_EDITOR", "QUALITY", "AUDITOR", "ADMIN"),
                demo.authenticate("Bearer demo-admin-token").roles());
    }

    @Test
    void pilotFailsClosedWithoutOidcOrWithStaticDemoCredentials() {
        assertThrows(
                IllegalStateException.class,
                () -> new ApiSecurityConfiguration("pilot", "[]", "[]", "", "harness-api"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ApiSecurityConfiguration(
                                "pilot", "[]", "[]", "http://idp.example", "harness-api"));
        assertThrows(
                IllegalStateException.class,
                () -> new ApiSecurityConfiguration("pilot", "[]", "[]", "https://idp.example", ""));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ApiSecurityConfiguration(
                                "pilot", CLAIMS, "[]", "https://idp.example", "harness-api"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ApiSecurityConfiguration(
                                "pilot",
                                "[]",
                                "[{\"token\":\"weak\"}]",
                                "https://idp.example",
                                "harness-api"));
    }

    @Test
    void demoRequiresAnExplicitRecognizedBearerAndAnonymousFailuresDoNotCreateAuditRows()
            throws Exception {
        PlatformStore store = memory();
        var filter = filter(store);
        for (String token : new String[] {"", "Bearer wrong"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request("/api/v2/session", token),
                    response,
                    (request, result) -> fail("Anonymous request reached controller"));
            assertEquals(401, response.getStatus());
        }
        assertTrue(store.audits("_SECURITY").isEmpty());
        assertTrue(store.audits("tenant-a").isEmpty());
    }

    @Test
    void signedServerIdentityCannotBeReplacedBySubmittedBodyClaims() throws Exception {
        var request = request("/api/v2/session", "Bearer secret-a");
        request.setContent("{\"tenant_id\":\"other\",\"roles\":[\"ADMIN\"]}".getBytes());
        var captured = new AtomicReference<Principal>();
        filter(memory())
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (incoming, result) ->
                                captured.set(
                                        PrincipalAccess.get((MockHttpServletRequest) incoming)));
        assertEquals("tenant-a", captured.get().tenantId());
        assertEquals("agent-a", captured.get().userId());
        assertFalse(captured.get().roles().contains("ADMIN"));
    }

    @Test
    void authenticatedScopeRefusalsRequireDurableAuditStorage() throws Exception {
        PlatformStore store = memory();
        var request = request("/api/v2/cases/other", "Bearer secret-a");
        filter(store)
                .doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (incoming, response) ->
                                ((MockHttpServletResponse) response).setStatus(404));
        assertEquals(1, store.audits("tenant-a").size());
        assertFalse(store.audits("tenant-a").toString().contains("secret-a"));
        assertThrows(
                NullPointerException.class,
                () ->
                        new PlatformSecurityFilter(
                                "demo",
                                new DemoIdentityConfig(CLAIMS),
                                null,
                                new PrincipalDirectory("demo")));
    }

    @Test
    void anonymousRejectionBudgetIsBoundedAndEventuallyReturns429() throws Exception {
        var throttle = new AuthenticationThrottle();
        for (int attempt = 0; attempt < 21; attempt++) {
            var response = new MockHttpServletResponse();
            throttle.doFilter(
                    request("/api/v2/session", ""),
                    response,
                    (incoming, outgoing) -> ((MockHttpServletResponse) outgoing).setStatus(401));
            assertEquals(attempt < 20 ? 401 : 429, response.getStatus());
        }
    }

    @Test
    void hostAndOriginMustMatchLiteralLoopbackAllowlists() throws Exception {
        var boundary =
                new BrowserBoundaryFilter(
                        "localhost,127.0.0.1,[::1]",
                        "http://localhost:8080,http://127.0.0.1:8080,http://[::1]:8080",
                        "demo");
        for (String host :
                new String[] {
                    "attacker.example", "localhost.attacker.example", "localhost@attacker.example"
                }) {
            var request = request("/api/v2/session", "Bearer secret-a");
            request.removeHeader("Host");
            request.addHeader("Host", host);
            var response = new MockHttpServletResponse();
            boundary.doFilter(
                    request, response, (incoming, outgoing) -> fail("DNS-rebinding host accepted"));
            assertEquals(403, response.getStatus());
        }
        for (String origin :
                new String[] {"null", "https://attacker.example", "http://localhost:9999"}) {
            var request = request("/api/v2/session", "Bearer secret-a");
            request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            boundary.doFilter(
                    request,
                    response,
                    (incoming, outgoing) -> fail("Cross-origin request accepted"));
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void durableAuditFailureFailsClosed() {
        PlatformStore store =
                new PlatformStore(
                        new DriverManagerDataSource("jdbc:h2:mem:audit-failure", "sa", "")) {
                    @Override
                    public <T> T transact(java.util.function.Function<Tx, T> work) {
                        throw new PlatformException(503, "STORAGE_UNAVAILABLE", "unavailable");
                    }
                };
        var request = request("/api/v2/cases/other", "Bearer secret-a");
        request.setAttribute(
                PrincipalAccess.ATTRIBUTE,
                new Principal(
                        "tenant-a",
                        "agent-a",
                        Set.of("AGENT"),
                        Set.of("CITY-A"),
                        Set.of("STATION-A")));
        var response =
                new PlatformErrorHandler(store)
                        .handle(new PlatformException(404, "NOT_FOUND", "scope"), request);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("AUDIT_UNAVAILABLE", response.getBody().get("code"));
    }

    private PlatformStore memory() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:security-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        return new PlatformStore(source);
    }

    private PlatformSecurityFilter filter(PlatformStore store) {
        return new PlatformSecurityFilter(
                "demo", new DemoIdentityConfig(CLAIMS), store, new PrincipalDirectory("demo"));
    }

    private MockHttpServletRequest request(String path, String token) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Host", "localhost:8080");
        if (!token.isEmpty()) {
            request.addHeader("Authorization", token);
        }
        return request;
    }
}
