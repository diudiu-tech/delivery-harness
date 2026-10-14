package com.delivery.harness.gateway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:disabled-demo-identities;DB_CLOSE_DELAY=-1",
            "harness.security.demo-identities=[]"
        })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DisabledDemoIdentityApiTest {
    @Autowired MockMvc mvc;
    @Autowired PlatformStore store;

    @Test
    void explicitEmptyIdentityListDisablesPublicCredentialsWithoutWritingUnauthenticatedAudits()
            throws Exception {
        int audits = store.audits("demo").size();
        for (String identity : new String[] {"demo-agent", "demo-publisher"}) {
            mvc.perform(
                            get("/api/v2/session")
                                    .header("Host", "localhost:8080")
                                    .header("Authorization", "Bearer " + identity + "-token"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(audits, store.audits("demo").size());
    }
}
