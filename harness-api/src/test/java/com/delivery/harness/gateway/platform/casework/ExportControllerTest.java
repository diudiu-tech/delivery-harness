package com.delivery.harness.gateway.platform.casework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.gateway.platform.security.PlatformErrorHandler;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

class ExportControllerTest {
    @Test
    void getCannotAppendExportAuditAndPostProducesExactlyOneReceipt() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var store = new PlatformStore(source);
        var cases = new CaseService(store, new OrderSource("demo", "", "", ""));
        var actor =
                new Principal(
                        "t", "operator", Set.of("AGENT", "OPERATOR"), Set.of("*"), Set.of("*"));
        var c = cases.create(actor, Map.of("order_id", "EXPORT-ORDER"));
        var mvc =
                MockMvcBuilders.standaloneSetup(new ExportController(cases, store))
                        .setControllerAdvice(new PlatformErrorHandler(store))
                        .build();
        int before = store.audits("t").size();
        String path = "/api/v2/cases/" + c.id() + "/export";
        mvc.perform(get(path).requestAttr("harness.principal", actor))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(before, store.audits("t").size());
        mvc.perform(post(path).requestAttr("harness.principal", actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_id").value("EXPORT-ORDER"));
        assertEquals(before + 1, store.audits("t").size());
        assertEquals("CASE_EXPORTED", store.audits("t").get(before).get("action"));
    }
}
