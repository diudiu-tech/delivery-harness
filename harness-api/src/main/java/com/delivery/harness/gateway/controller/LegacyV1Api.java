package com.delivery.harness.gateway.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an unscoped /api/v1 controller. These controllers have no role or tenant checks, so they
 * are not registered at all in pilot mode: no request spelling can reach them there.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("'${harness.mode:demo}' != 'pilot'")
public @interface LegacyV1Api {}
