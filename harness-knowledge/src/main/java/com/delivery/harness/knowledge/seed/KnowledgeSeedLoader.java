package com.delivery.harness.knowledge.seed;

import com.delivery.harness.common.dto.CaseInfo;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.casemgmt.CaseBaseService;
import com.delivery.harness.knowledge.rule.RuleBaseService;
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
 * Loads the synthetic rule and case base into memory at startup.
 *
 * <p>Before this existed, {@code RuleBaseService.save} and
 * {@code CaseBaseService.save} had no callers anywhere in the repository and
 * no API endpoint wrote to them. Both stores were therefore permanently empty,
 * which meant every analysis prompt carried an empty "适用规则与历史案例"
 * section and every response carried {@code "citations": []}. The knowledge
 * module contributed nothing to the default path.
 *
 * <p>The content is the same policy set that
 * {@code db/migration/V2__init_data.sql} described. That file was never
 * executed — the implementation is in memory and no migration runner was
 * configured — so it has been replaced by these JSON resources, which are
 * actually read.
 *
 * <p>Set {@code harness.knowledge.seed.enabled=false} to start with an empty
 * knowledge base, for example when loading a real policy set through the
 * ingestion API instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSeedLoader {

    private static final String RULES_RESOURCE = "seed/rules.json";
    private static final String CASES_RESOURCE = "seed/cases.json";

    private final RuleBaseService ruleBaseService;
    private final CaseBaseService caseBaseService;

    @Value("${harness.knowledge.seed.enabled:true}")
    private boolean seedEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void loadSeedData() {
        if (!seedEnabled) {
            log.info("Knowledge seed disabled; rule and case base start empty");
            return;
        }

        List<RuleInfo> rules = read(RULES_RESOURCE, new TypeReference<List<RuleInfo>>() {});
        rules.forEach(ruleBaseService::save);

        List<CaseInfo> cases = read(CASES_RESOURCE, new TypeReference<List<CaseInfo>>() {});
        cases.forEach(caseBaseService::save);

        log.info("Knowledge seed loaded: {} rules, {} cases (synthetic)", rules.size(), cases.size());
    }

    private <T> List<T> read(String resourcePath, TypeReference<List<T>> typeRef) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("Seed resource not found on classpath: {}", resourcePath);
            return List.of();
        }
        try (InputStream in = resource.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<T> items = JsonUtil.fromJson(json, typeRef);
            return items == null ? List.of() : items;
        } catch (IOException | RuntimeException e) {
            // A malformed seed file must not stop the application: the API
            // still works with an empty knowledge base, and the log says so.
            log.error("Failed to load seed resource {}: {}", resourcePath, e.getMessage());
            return List.of();
        }
    }
}
