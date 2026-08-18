package com.delivery.harness.eval.casemanager;

import com.delivery.harness.common.dto.EvalCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EvalCaseManager {

    private final Map<String, EvalCase> caseStore = new ConcurrentHashMap<>();

    public void save(EvalCase evalCase) {
        validateCase(evalCase);
        caseStore.put(evalCase.getCaseId(), evalCase);
        log.info("EvalCase saved: id={}, scenario={}", evalCase.getCaseId(), evalCase.getScenario());
    }

    public void saveAll(List<EvalCase> cases) {
        cases.forEach(this::save);
    }

    public Optional<EvalCase> findById(String caseId) {
        return Optional.ofNullable(caseStore.get(caseId));
    }

    public List<EvalCase> findByScenario(String scenario) {
        return caseStore.values().stream()
                .filter(c -> scenario.equals(c.getScenario()))
                .collect(Collectors.toList());
    }

    public List<EvalCase> findByIds(List<String> caseIds) {
        if (caseIds == null || caseIds.stream().anyMatch(this::isBlank)) {
            throw new IllegalArgumentException("caseIds must contain only non-blank values");
        }
        return caseIds.stream()
                .map(caseStore::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<EvalCase> findAll() {
        return new ArrayList<>(caseStore.values());
    }

    public void deleteById(String caseId) {
        caseStore.remove(caseId);
    }

    public int count() {
        return caseStore.size();
    }

    private void validateCase(EvalCase evalCase) {
        if (evalCase == null) {
            throw new IllegalArgumentException("evalCase must not be null");
        }
        if (isBlank(evalCase.getCaseId())) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (isBlank(evalCase.getScenario())) {
            throw new IllegalArgumentException("scenario must not be blank");
        }
        if (evalCase.getInput() == null) {
            throw new IllegalArgumentException("input must not be null");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
