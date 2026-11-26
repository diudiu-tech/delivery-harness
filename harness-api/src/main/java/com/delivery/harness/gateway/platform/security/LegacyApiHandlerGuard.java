package com.delivery.harness.gateway.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Locale;

/**
 * Pilot backstop keyed off the resolved handler rather than the request spelling: if any route
 * under /api/v1 is ever registered in pilot, it is refused before the handler executes.
 */
@Component
@ConditionalOnProperty(name = "harness.mode", havingValue = "pilot")
public class LegacyApiHandlerGuard implements HandlerInterceptor, WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? "" : pattern.toString().toLowerCase(Locale.ROOT);
        if (route.equals("/api/v1") || route.startsWith("/api/v1/")) {
            SecurityResponse.reject(
                    response, 410, "V2_REQUIRED", "Pilot requires scoped /api/v2 resources");
            return false;
        }
        return true;
    }
}
