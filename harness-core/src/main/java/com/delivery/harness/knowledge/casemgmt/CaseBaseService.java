package com.delivery.harness.knowledge.casemgmt;

import com.delivery.harness.common.dto.CaseInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CaseBaseService {

    private final Map<String, CaseInfo> caseStore = new ConcurrentHashMap<>();

    public void save(CaseInfo caseInfo) {
        caseStore.put(caseInfo.getCaseId(), caseInfo);
        log.info("Case saved: id={}, type={}", caseInfo.getCaseId(), caseInfo.getCaseType());
    }

    public Optional<CaseInfo> findById(String caseId) {
        return Optional.ofNullable(caseStore.get(caseId));
    }

    public List<CaseInfo> findByScenario(String scenario) {
        return caseStore.values().stream()
                .filter(c -> scenario.equals(c.getScenario()))
                .collect(Collectors.toList());
    }

    public List<CaseInfo> findByTags(List<String> tags) {
        return caseStore.values().stream()
                .filter(c -> c.getTags() != null && !Collections.disjoint(c.getTags(), tags))
                .collect(Collectors.toList());
    }

    public List<CaseInfo> findAll() {
        return new ArrayList<>(caseStore.values());
    }

    public void deleteById(String caseId) {
        caseStore.remove(caseId);
    }
}
