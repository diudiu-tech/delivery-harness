package com.delivery.harness.knowledge.rule;

import com.delivery.harness.common.dto.RuleInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RuleBaseService {

    private final Map<String, RuleInfo> ruleStore = new ConcurrentHashMap<>();

    public void save(RuleInfo rule) {
        ruleStore.put(rule.getRuleId(), rule);
        log.info("Rule saved: id={}, name={}", rule.getRuleId(), rule.getRuleName());
    }

    public Optional<RuleInfo> findById(String ruleId) {
        return Optional.ofNullable(ruleStore.get(ruleId));
    }

    public List<RuleInfo> findByType(String ruleType) {
        return ruleStore.values().stream()
                .filter(r -> ruleType.equals(r.getRuleType()))
                .filter(RuleInfo::getEnabled)
                .sorted(Comparator.comparingInt(RuleInfo::getPriority).reversed())
                .collect(Collectors.toList());
    }

    public List<RuleInfo> findByCategory(String category) {
        return ruleStore.values().stream()
                .filter(r -> category.equals(r.getCategory()))
                .filter(RuleInfo::getEnabled)
                .collect(Collectors.toList());
    }

    public List<RuleInfo> searchByKeyword(String keyword) {
        String lower = keyword.toLowerCase();
        return ruleStore.values().stream()
                .filter(r -> r.getContent().toLowerCase().contains(lower)
                        || r.getRuleName().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }

    public List<RuleInfo> findAll() {
        return new ArrayList<>(ruleStore.values());
    }

    public void deleteById(String ruleId) {
        ruleStore.remove(ruleId);
    }
}
