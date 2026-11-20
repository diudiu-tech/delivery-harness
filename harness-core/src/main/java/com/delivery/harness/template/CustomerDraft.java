package com.delivery.harness.template;

/**
 * Advisory customer communication draft rendered strictly from deterministic
 * templates keyed by adjudication status and external execution receipt state.
 */
public record CustomerDraft(
        String templateId,
        boolean executionCompleted,
        boolean sendableDirectly,
        String message,
        String disclaimer) {}
