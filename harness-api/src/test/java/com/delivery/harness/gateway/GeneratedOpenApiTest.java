package com.delivery.harness.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.gateway.platform.quality.GeneratedOpenApiController;
import com.delivery.harness.platform.quality.QualityData;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:h2:mem:openapi-contract;DB_CLOSE_DELAY=-1"})
class GeneratedOpenApiTest {
    @Autowired GeneratedOpenApiController contract;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void generatedOperationsMatchEveryRegisteredV2RouteIncludingAliases() throws IOException {
        Set<String> expected = new TreeSet<>();
        mappings.getHandlerMethods()
                .forEach(
                        (route, method) -> {
                            for (String pattern : route.getPatternValues()) {
                                if (pattern.startsWith("/api/v2/")) {
                                    for (String path :
                                            GeneratedOpenApiController.expandPath(pattern)) {
                                        route.getMethodsCondition()
                                                .getMethods()
                                                .forEach(
                                                        verb ->
                                                                expected.add(
                                                                        verb.name() + " " + path));
                                    }
                                }
                            }
                        });
        Map<String, Object> specification = contract.generate();
        Map<String, Object> paths = QualityData.map(specification.get("paths"));
        Set<String> actual = new TreeSet<>();
        paths.forEach(
                (path, item) ->
                        QualityData.map(item)
                                .keySet()
                                .forEach(verb -> actual.add(verb.toUpperCase() + " " + path)));
        assertEquals(expected, actual);
        assertTrue(actual.contains("POST /api/v2/knowledge/versions/search"));
        assertTrue(actual.contains("POST /api/v2/knowledge/search"));
        assertFalse(actual.contains("GET /api/v2/cases/{id}/export"));
        assertTrue(actual.contains("POST /api/v2/cases/{id}/export"));
        Path artifact = Path.of("target", "generated-api", "openapi.v2.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, JsonUtil.toPrettyJson(specification), StandardCharsets.UTF_8);
    }

    @Test
    void schemaReflectsActualDtoNamesTypesAndValidationLimits() {
        Map<String, Object> schemas =
                QualityData.map(
                        QualityData.map(contract.generate().get("components")).get("schemas"));
        Map<String, Object> knowledge = QualityData.map(schemas.get("KnowledgeRequest"));
        Map<String, Object> properties = QualityData.map(knowledge.get("properties"));
        assertEquals(256, QualityData.map(properties.get("title")).get("maxLength"));
        assertEquals(100_000, QualityData.map(properties.get("content")).get("maxLength"));
        assertTrue(properties.containsKey("source_type"));
        assertEquals(30, QualityData.map(properties.get("tags")).get("maxItems"));
        assertEquals(
                100,
                QualityData.map(QualityData.map(properties.get("tags")).get("items"))
                        .get("maxLength"));
        assertTrue(properties.containsKey("effective_from"));
        assertEquals("date-time", QualityData.map(properties.get("effective_from")).get("format"));
        Map<String, Object> lifecycle =
                QualityData.map(QualityData.map(schemas.get("LifecycleRequest")).get("properties"));
        assertEquals("integer", QualityData.map(lifecycle.get("expected_version")).get("type"));
        assertEquals(1L, QualityData.map(lifecycle.get("expected_version")).get("minimum"));
        Map<String, Object> runtime =
                QualityData.map(QualityData.map(schemas.get("RuntimeRequest")).get("properties"));
        assertEquals("number", QualityData.map(runtime.get("temperature")).get("type"));
        assertEquals("boolean", QualityData.map(runtime.get("deterministic_only")).get("type"));
    }
}
