package com.delivery.harness.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces that HTTP controllers in {@code harness-api} orchestrate via
 * application/orchestrator services rather than calling fixture sources or
 * policy engines directly.
 */
class ApiArchitectureGuardTest {

    private static final Path CONTROLLER_SRC = Path.of(
            "src/main/java/com/delivery/harness/gateway/controller");

    @Test
    void controllersNeverImportFixturesEvidenceSourcesOrPolicyEngineDirectly() throws IOException {
        assertTrue(Files.isDirectory(CONTROLLER_SRC), "Controller source dir must exist");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CONTROLLER_SRC)) {
            for (Path javaFile : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(javaFile);
                if (source.contains("com.delivery.harness.tool.")
                        || source.contains("SyntheticEvidenceSource")
                        || source.contains("SingleSourcePolicyEngine")) {
                    violations.add(javaFile.getFileName() + " bypasses application orchestration");
                }
            }
        }
        assertTrue(violations.isEmpty(), "Controller boundary violations:\n" + String.join("\n", violations));
    }
}
