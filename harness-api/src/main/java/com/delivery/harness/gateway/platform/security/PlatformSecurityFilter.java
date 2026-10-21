package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.auth.PrincipalDirectory;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Installed after Spring's JWT signature verification and before anonymous authentication. */
public final class PlatformSecurityFilter extends OncePerRequestFilter {
    private final String mode;
    private final DemoIdentityConfig demo;
    private final PlatformStore store;
    private final PrincipalDirectory directory;

    public PlatformSecurityFilter(
            String mode,
            DemoIdentityConfig demo,
            PlatformStore store,
            PrincipalDirectory directory) {
        this.mode = mode;
        this.demo = Objects.requireNonNull(demo);
        this.store = Objects.requireNonNull(store, "Durable audit storage is required");
        this.directory = Objects.requireNonNull(directory);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthContext.Principal principal = null;
        try {
            principal =
                    "pilot".equals(mode)
                            ? verifiedJwtPrincipal()
                            : demo.authenticate(request.getHeader("Authorization"));
            directory.registerAuthenticated(principal);
            request.setAttribute(PrincipalAccess.ATTRIBUTE, principal);
            if (!"pilot".equals(mode)) {
                var authentication =
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal,
                                null,
                                principal.roles().stream()
                                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                        .toList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            if ("pilot".equals(mode) && request.getRequestURI().startsWith("/api/v1/")) {
                throw new PlatformException(
                        410, "V2_REQUIRED", "Pilot requires scoped /api/v2 resources");
            }
            chain.doFilter(request, response);
            if (Set.of(403, 404).contains(response.getStatus())) {
                auditDenied(request, response.getStatus(), principal, "ACCESS_REFUSED");
            }
        } catch (PlatformException failure) {
            try {
                if (principal != null && Set.of(403, 404).contains(failure.status())) {
                    auditDenied(request, failure.status(), principal, failure.code());
                }
                SecurityResponse.reject(
                        response, failure.status(), failure.code(), failure.getMessage());
            } catch (PlatformException unavailable) {
                SecurityResponse.reject(
                        response, 503, "AUDIT_UNAVAILABLE", "Access refusal could not be recorded");
            }
        }
    }

    private static AuthContext.Principal verifiedJwtPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new PlatformException(
                    401, "UNAUTHENTICATED", "A verified OIDC bearer token is required");
        }
        try {
            String tenant = jwt.getClaimAsString("tenant_id");
            if ("demo".equals(tenant)
                    || jwt.getSubject() == null
                    || jwt.getSubject().startsWith("demo-")) {
                throw new IllegalArgumentException("Synthetic identities are forbidden in pilot");
            }
            return new AuthContext.Principal(
                    tenant,
                    jwt.getSubject(),
                    DemoIdentityConfig.strings(jwt.getClaim("roles")),
                    DemoIdentityConfig.strings(jwt.getClaim("city_codes")),
                    DemoIdentityConfig.strings(jwt.getClaim("station_ids")));
        } catch (IllegalArgumentException invalid) {
            throw new PlatformException(
                    401,
                    "INVALID_IDENTITY_CLAIMS",
                    "JWT has invalid tenant, subject, roles or scopes");
        }
    }

    private void auditDenied(
            HttpServletRequest request,
            int status,
            AuthContext.Principal principal,
            String reason) {
        if (Boolean.TRUE.equals(request.getAttribute("harness.denial_audited"))) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("path", request.getRequestURI());
        details.put("method", request.getMethod());
        details.put("reason", reason);
        details.put("status", status);
        store.transact(
                tx -> {
                    tx.audit(
                            principal.tenantId(),
                            principal.userId(),
                            "ACCESS_DENIED",
                            "request:" + PlatformStore.hash(request.getRequestURI()),
                            details);
                    return null;
                });
        request.setAttribute("harness.denial_audited", true);
    }
}
