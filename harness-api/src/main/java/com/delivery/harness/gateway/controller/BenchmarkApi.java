package com.delivery.harness.gateway.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a v0.3 harness-benchmark controller. These keep cases in an unscoped in-memory store with
 * no tenant or city/station checks, so like {@link LegacyV1Api} they are not registered in pilot
 * mode; scoped production cases live under /api/v2.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${harness.mode:demo}' != 'pilot'")
public @interface BenchmarkApi {}
