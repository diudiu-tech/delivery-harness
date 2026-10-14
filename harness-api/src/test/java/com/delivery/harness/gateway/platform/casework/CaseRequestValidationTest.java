package com.delivery.harness.gateway.platform.casework;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

class CaseRequestValidationTest {
    private MockMvc mvc;
    private PlatformStore store;
    private final Principal actor =
            new Principal("tenant", "agent", Set.of("AGENT"), Set.of("*"), Set.of("*"));

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        store = new PlatformStore(source);
        var cases = new CaseService(store, new OrderSource("demo", "", "", ""));
        mvc =
                MockMvcBuilders.standaloneSetup(new CaseController(cases, store))
                        .setControllerAdvice(new PlatformErrorHandler(store))
                        .build();
    }

    @Test
    void textFieldsRejectNonStringValuesAndLengthLimitsBeforeCaseCreation() throws Exception {
        for (String invalid :
                new String[] {"42", "true", "[]", "{}", "\"" + "A".repeat(121) + "\""}) {
            mvc.perform(
                            post("/api/v2/cases")
                                    .requestAttr("harness.principal", actor)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"order_id\":" + invalid + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        post("/api/v2/cases")
                                .requestAttr("harness.principal", actor)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"order_id\":\"DTO-ORDER\",\"title\":\""
                                                + "T".repeat(201)
                                                + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void versionFieldsRejectStringAndFractionalCoercionBeforeReadingReview() throws Exception {
        for (String version : new String[] {"\"1\"", "1.5", "0", "-1", "null"}) {
            mvc.perform(
                            post("/api/v2/reviews/missing/claim")
                                    .requestAttr("harness.principal", actor)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"expected_version\":" + version + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void queueAcceptsEveryDocumentedFilterAndRejectsOverlongValues() throws Exception {
        store.create(
                "tenant",
                "case",
                "wanted",
                Map.of(
                        "status",
                        "IN_REVIEW",
                        "risk_level",
                        "HIGH",
                        "city_code",
                        "CITY",
                        "station_id",
                        "STATION",
                        "order_id",
                        "ORDER",
                        "complaint_type",
                        "DAMAGED",
                        "assignee",
                        "reviewer"),
                "agent",
                "CREATE");
        mvc.perform(
                        get("/api/v2/cases")
                                .requestAttr("harness.principal", actor)
                                .param("limit", "1")
                                .param("status", "IN_REVIEW")
                                .param("order_id", "ORDER")
                                .param("risk_level", "HIGH")
                                .param("complaint_type", "DAMAGED")
                                .param("assignee", "reviewer")
                                .param("city_code", "CITY")
                                .param("station_id", "STATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("wanted"));
        mvc.perform(
                        get("/api/v2/cases")
                                .requestAttr("harness.principal", actor)
                                .param("order_id", "x".repeat(121)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void typedRequestRetainsServerOwnedCaseInitiator() throws Exception {
        mvc.perform(
                        post("/api/v2/cases")
                                .requestAttr("harness.principal", actor)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"order_id\":\"DTO-ORDER\",\"created_by\":\"forged-user\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created_by").value("agent"));
    }
}
