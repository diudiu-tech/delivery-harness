package com.delivery.harness.gateway.platform;
import com.delivery.harness.gateway.platform.security.*;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PlatformSecurityFilterTest {
    private static final String CLAIMS="""
            [{"token":"secret-a","tenant_id":"tenant-a","user_id":"agent-a","roles":["AGENT"],"city_codes":["CITY-A"],"station_ids":["STATION-A"]}]
            """;
    @Test void pilotFailsClosedUnlessIdentityStorageAndAdapterAreConfigured(){
        assertThrows(IllegalStateException.class,() -> new PlatformSecurityFilter("pilot","[]","jdbc:h2:file:db","https://orders.example/{order_id}","orders.example"));
        assertThrows(IllegalStateException.class,() -> new PlatformSecurityFilter("pilot",CLAIMS,"jdbc:h2:mem:db","https://orders.example/{order_id}","orders.example"));
        assertThrows(IllegalStateException.class,() -> new PlatformSecurityFilter("pilot",CLAIMS,"jdbc:h2:file:db","","orders.example"));
    }
    @Test void pilotRejectsMissingOrUnrecognizedBearerTokens() throws Exception {
        for(String token:new String[]{"","Bearer wrong"}){var request=request("/api/v2/cases",token);var response=new MockHttpServletResponse();filter().doFilter(request,response,(rq,rs) -> fail("Unauthenticated request reached controller"));assertEquals(401,response.getStatus());assertFalse(response.getContentAsString().contains("secret-a"));}
    }
    @Test void principalComesFromConfiguredServerClaimsRatherThanRequestBody() throws Exception {
        var request=request("/api/v2/cases","Bearer secret-a");request.setContent("{\"submittedBy\":\"admin\",\"tenant_id\":\"tenant-b\",\"roles\":[\"ADMIN\"]}".getBytes());var response=new MockHttpServletResponse();var actor=new AtomicReference<Principal>();
        filter().doFilter(request,response,(rq,rs) -> actor.set(PrincipalAccess.get((jakarta.servlet.http.HttpServletRequest)rq)));
        assertEquals("tenant-a",actor.get().tenantId());assertEquals("agent-a",actor.get().userId());assertFalse(actor.get().roles().contains("ADMIN"));assertFalse(actor.get().canAccess("CITY-B","STATION-A"));
    }
    @Test void legacyV1CannotBypassPilotScopesAndMockBoundary() throws Exception {
        var response=new MockHttpServletResponse();filter().doFilter(request("/api/v1/analyze/abnormal-order","Bearer secret-a"),response,(rq,rs) -> fail("Legacy route reached controller"));assertEquals(410,response.getStatus());assertTrue(response.getContentAsString().contains("V2_REQUIRED"));
    }
    @Test void demoKeepsSyntheticServerIdentityWithoutCredentials() throws Exception {
        var filter=new PlatformSecurityFilter("demo","[]","jdbc:h2:mem:demo","","");var actor=new AtomicReference<Principal>();filter.doFilter(request("/api/v2/session",""),new MockHttpServletResponse(),(rq,rs) -> actor.set(PrincipalAccess.get((jakarta.servlet.http.HttpServletRequest)rq)));assertEquals("demo",actor.get().tenantId());assertEquals("demo-agent",actor.get().userId());
    }
    @Test void authenticatedAccessRefusalsAreDurablyAuditedWithoutCredentials() throws Exception {
        var store=memory();var filter=filter();org.springframework.test.util.ReflectionTestUtils.setField(filter,"store",store);
        var request=request("/api/v2/cases/other-tenant-case","Bearer secret-a");var response=new MockHttpServletResponse();response.setHeader("X-Trace-Id","internal-trace");
        filter.doFilter(request,response,(rq,rs) -> {request.setAttribute("harness.denial_code","NOT_FOUND");((jakarta.servlet.http.HttpServletResponse)rs).setStatus(404);});
        var events=store.audits("tenant-a");assertEquals(1,events.size());assertEquals("agent-a",events.get(0).get("actor"));assertEquals("ACCESS_DENIED",events.get(0).get("action"));assertTrue(events.get(0).get("details").toString().contains("internal-trace"));assertFalse(events.toString().contains("secret-a"));
    }
    @Test void rejectionAuditFailureReturnsUnavailableBeforeWritingTheResponse(){
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:security-failing","sa","");
        var store=new com.delivery.harness.platform.store.PlatformStore(source){@Override public <T>T transact(java.util.function.Function<Tx,T> work){throw new com.delivery.harness.platform.store.PlatformException(503,"STORAGE_UNAVAILABLE","storage unavailable");}};
        var handler=new PlatformErrorHandler();org.springframework.test.util.ReflectionTestUtils.setField(handler,"store",store);var request=request("/api/v2/cases/other-case","Bearer secret-a");request.setAttribute(PrincipalAccess.ATTRIBUTE,new Principal("tenant-a","agent-a",java.util.Set.of("AGENT"),java.util.Set.of("CITY-A"),java.util.Set.of("STATION-A")));
        var response=handler.handle(new com.delivery.harness.platform.store.PlatformException(404,"NOT_FOUND","outside authorized scope"),request);assertEquals(503,response.getStatusCode().value());assertEquals("AUDIT_UNAVAILABLE",response.getBody().get("code"));
    }
    private com.delivery.harness.platform.store.PlatformStore memory(){var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:security-"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");var migrate=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V1__platform_records.sql"),new org.springframework.core.io.ClassPathResource("db/migration/V2__case_queue_projection.sql"));migrate.execute(source);return new com.delivery.harness.platform.store.PlatformStore(source);}
    private PlatformSecurityFilter filter(){return new PlatformSecurityFilter("pilot",CLAIMS,"jdbc:h2:file:db","https://orders.example/{order_id}","orders.example");}
    private MockHttpServletRequest request(String path,String token){var request=new MockHttpServletRequest("GET",path);if(!token.isEmpty())request.addHeader("Authorization",token);return request;}
}
