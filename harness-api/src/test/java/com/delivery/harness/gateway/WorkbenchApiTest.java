package com.delivery.harness.gateway;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WorkbenchApiTest {
    @Autowired MockMvc mvc;

    @Test
    void publicShellLoadsWithoutImplicitApiCredentials() throws Exception {
        mvc.perform(get("/workbench/index.html").header("Host", "localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("案件工作台")));
        mvc.perform(get("/workbench/app.js").header("Host", "localhost:8080"))
                .andExpect(status().isOk());
        mvc.perform(get("/workbench/styles.css").header("Host", "localhost:8080"))
                .andExpect(status().isOk());
        mvc.perform(get("/workbench/index.html").header("Host", "evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v2/session").header("Host", "localhost:8080"))
                .andExpect(status().isUnauthorized());
    }
}
