package com.delivery.harness.eval.seed;

import com.delivery.harness.common.dto.EvalCase;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.eval.casemanager.EvalCaseManager;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads the starter evaluation set at startup, so {@code POST /api/v1/eval/run}
 * has something to run against a freshly started process.
 *
 * <p>These six cases are a smoke set, not a benchmark. Six synthetic cases
 * cannot establish that the system is accurate; they establish that the
 * evaluation path is wired and that a regression in attribution or tier
 * selection is visible. Any real quality claim needs a labelled set drawn from
 * production traffic.
 *
 * <p>One case (EC-004) is an on-time order. It exists so that a model which
 * always answers "overtime" cannot score perfectly — without a negative case,
 * the suite measures nothing about discrimination.
 *
 * <p>Each case pins an order ID whose scenario is fixed in
 * {@code OrderFixtures}. Changing a pinned fixture changes what these cases
 * measure; the two files must be kept in step.
 *
 * <p>Set {@code harness.eval.seed.enabled=false} to start with no cases.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalSeedLoader {

    private static final String CASES_RESOURCE = "seed/eval-cases.json";

    private final EvalCaseManager caseManager;

    @Value("${harness.eval.seed.enabled:true}")
    private boolean seedEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void loadSeedCases() {
        if (!seedEnabled) {
            log.info("Evaluation seed disabled; case store starts empty");
            return;
        }

        ClassPathResource resource = new ClassPathResource(CASES_RESOURCE);
        if (!resource.exists()) {
            log.warn("Seed resource not found on classpath: {}", CASES_RESOURCE);
            return;
        }

        try (InputStream in = resource.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<EvalCase> cases = JsonUtil.fromJson(json, new TypeReference<List<EvalCase>>() {});
            if (cases == null) {
                return;
            }
            cases.forEach(caseManager::save);
            log.info("Evaluation seed loaded: {} cases (synthetic)", cases.size());
        } catch (IOException | RuntimeException e) {
            // A malformed seed file must not stop the application; the
            // evaluation API simply starts with no cases.
            log.error("Failed to load seed resource {}: {}", CASES_RESOURCE, e.getMessage());
        }
    }
}
