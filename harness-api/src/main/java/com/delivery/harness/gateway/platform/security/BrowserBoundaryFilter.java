package com.delivery.harness.gateway.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The bundled workbench uses same-origin resources and never embeds third-party scripts. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BrowserBoundaryFilter extends OncePerRequestFilter {
    private final Set<String> hosts;
    private final Set<String> origins;

    public BrowserBoundaryFilter(
            @Value("${harness.security.allowed-hosts:localhost,127.0.0.1,[::1]}") String hosts,
            @Value(
                            "${harness.security.allowed-origins:http://localhost:8080,"
                                    + "http://127.0.0.1:8080,http://[::1]:8080}")
                    String origins,
            @Value("${harness.mode:demo}") String mode) {
        this.hosts = values(hosts);
        this.origins = values(origins);
        if (this.hosts.isEmpty()
                || this.origins.isEmpty()
                || this.hosts.contains("*")
                || this.origins.contains("*")) {
            throw new IllegalStateException(
                    "Explicit browser host and origin allowlists are required");
        }
        if (!"pilot".equals(mode)
                && !Set.of("localhost", "127.0.0.1", "[::1]").containsAll(this.hosts)) {
            throw new IllegalStateException("Demo browser hosts must be literal loopback hosts");
        }
        for (String origin : this.origins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !this.hosts.contains(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPath() != null && !uri.getPath().isEmpty()) {
                throw new IllegalStateException(
                        "Browser origins must exactly match an allowed host and scheme");
            }
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                        + " connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri"
                        + " 'self'; form-action 'self'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        if (request.getRequestURI().startsWith("/api/"))
            response.setHeader("Cache-Control", "no-store");
        if (!allowedHost(request) || !allowedOrigin(request)) {
            SecurityResponse.reject(
                    response,
                    403,
                    "BROWSER_BOUNDARY_REJECTED",
                    "Host or Origin is outside the allowlist");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowedHost(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Host"));
        if (values.size() != 1) {
            return false;
        }
        try {
            URI uri = URI.create("http://" + values.get(0));
            return hosts.contains(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean allowedOrigin(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Origin"));
        return values.isEmpty()
                || values.size() == 1 && origins.contains(values.get(0).toLowerCase(Locale.ROOT));
    }

    private static Set<String> values(String text) {
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
