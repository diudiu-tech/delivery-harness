package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.common.exception.RuntimeBoundaryException;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestControllerAdvice(basePackages = "com.delivery.harness.gateway.platform")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformErrorHandler {
    private final PlatformStore store;

    public PlatformErrorHandler(PlatformStore store) {
        this.store = Objects.requireNonNull(store, "Access refusals require durable audit storage");
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<Map<String, Object>> handle(
            PlatformException exception, HttpServletRequest request) {
        request.setAttribute("harness.denial_code", exception.code());
        if (Set.of(403, 404).contains(exception.status())
                && request.getAttribute(PrincipalAccess.ATTRIBUTE)
                        instanceof AuthContext.Principal actor) {
            try {
                store.transact(
                        tx -> {
                            tx.audit(
                                    actor.tenantId(),
                                    actor.userId(),
                                    "ACCESS_DENIED",
                                    "request:" + PlatformStore.hash(request.getRequestURI()),
                                    Map.of(
                                            "path",
                                            request.getRequestURI(),
                                            "method",
                                            request.getMethod(),
                                            "status",
                                            exception.status(),
                                            "reason",
                                            exception.code()));
                            return null;
                        });
                request.setAttribute("harness.denial_audited", true);
            } catch (PlatformException unavailable) {
                return ResponseEntity.status(503)
                        .body(
                                Map.of(
                                        "code",
                                        "AUDIT_UNAVAILABLE",
                                        "message",
                                        "Access refusal cannot be durably recorded",
                                        "retryable",
                                        true));
            }
        }
        return ResponseEntity.status(exception.status())
                .body(
                        Map.of(
                                "code",
                                exception.code(),
                                "message",
                                exception.getMessage(),
                                "retryable",
                                exception.status() == 429 || exception.status() == 503));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        ConstraintViolationException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> invalidRequest(Exception exception) {
        // BindingResult and parser messages can embed rejected values, including private evidence.
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "code",
                                "INVALID_REQUEST",
                                "message",
                                "Invalid request",
                                "retryable",
                                false));
    }

    @ExceptionHandler(RuntimeBoundaryException.class)
    public ResponseEntity<Map<String, Object>> runtimeBoundary(RuntimeBoundaryException exception) {
        return ResponseEntity.status(exception.getCode())
                .body(
                        Map.of(
                                "code",
                                exception.getReasonCode(),
                                "message",
                                exception.getMessage(),
                                "retryable",
                                false));
    }
}
