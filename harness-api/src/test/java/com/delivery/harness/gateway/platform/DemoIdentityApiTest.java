package com.delivery.harness.gateway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.util.JsonUtil;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

@SpringBootTest(
        properties = "spring.datasource.url=jdbc:h2:mem:public-demo-identities;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DemoIdentityApiTest {
    @Autowired MockMvc mvc;

    @Test
    void actualDemoProfileAcceptsFivePublicIdentitiesWithSeparateSmallRoleSets() throws Exception {
        Map<String, Set<String>> expected =
                Map.of(
                        "demo-agent", Set.of("AGENT", "OPERATOR"),
                        "demo-reviewer", Set.of("REVIEWER"),
                        "demo-supervisor", Set.of("REVIEWER", "SUPERVISOR"),
                        "demo-admin", Set.of("POLICY_EDITOR", "QUALITY", "AUDITOR", "ADMIN"),
                        "demo-publisher", Set.of("QUALITY", "POLICY_PUBLISHER"));
        for (var identity : expected.entrySet()) {
            String response =
                    mvc.perform(
                                    get("/api/v2/session")
                                            .header("Host", "localhost:8080")
                                            .header(
                                                    "Authorization",
                                                    "Bearer " + identity.getKey() + "-token"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            Map<?, ?> principal =
                    (Map<?, ?>) JsonUtil.fromJson(response, Map.class).get("principal");
            assertEquals(identity.getKey(), principal.get("user_id"));
            assertEquals(identity.getValue(), Set.copyOf((Collection<?>) principal.get("roles")));
        }
        mvc.perform(get("/api/v2/session").header("Host", "localhost:8080"))
                .andExpect(status().isUnauthorized());
    }
}
