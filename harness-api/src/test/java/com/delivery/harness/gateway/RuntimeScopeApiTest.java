package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:runtime-scopes;DB_CLOSE_DELAY=-1",
            "harness.security.demo-identities=" + ScopedRuntimeApiSupport.IDENTITIES
        })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class RuntimeScopeApiTest extends ScopedRuntimeApiSupport {
    @Test
    void globalPolicyDoesNotExposeOtherStationsEmbeddedKnowledgeInDetailsOrLists()
            throws Exception {
        var runtime = mixedScopeRuntime();
        assertFalse(
                getJson("/runtime-configs/" + runtime.id(), "scope-st1-token", 404)
                        .contains(SECRET));
        String list = getJson("/runtime-configs?limit=100", "scope-st1-token", 200);
        assertFalse(list.contains(SECRET));
        assertFalse(list.contains(runtime.id()));
        assertTrue(
                getJson("/runtime-configs/" + runtime.id(), "scope-st2-token", 200)
                        .contains(SECRET));
    }
}
