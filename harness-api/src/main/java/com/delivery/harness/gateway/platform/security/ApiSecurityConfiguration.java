package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.platform.auth.PrincipalDirectory;
import com.delivery.harness.platform.store.PlatformStore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class ApiSecurityConfiguration {
    private final String mode;
    private final DemoIdentityConfig demo;

    public ApiSecurityConfiguration(
            @Value("${harness.mode:demo}") String mode,
            @Value("${harness.security.demo-identities:}") String demoIdentities,
            @Value("${harness.security.tokens:${HARNESS_AUTH_TOKENS:[]}}") String legacyTokens,
            @Value("${harness.security.oidc.issuer:}") String issuer,
            @Value("${harness.security.oidc.audience:}") String audience) {
        if (!Set.of("demo", "pilot", "test").contains(mode)) {
            throw new IllegalArgumentException("Unsupported authentication mode");
        }
        if (!"[]".equals(legacyTokens.trim())) {
            throw new IllegalStateException("Static cleartext identity tokens are unsupported");
        }
        if ("pilot".equals(mode)) {
            if (!demoIdentities.isBlank() && !"[]".equals(demoIdentities.trim())) {
                throw new IllegalStateException("Pilot must not configure demo credentials");
            }
            requireHttps(issuer, "OIDC issuer");
            if (audience.isBlank() || audience.length() > 200) {
                throw new IllegalStateException("Pilot requires an explicit OIDC audience");
            }
        }
        this.mode = mode;
        String resolvedDemo =
                demoIdentities.isBlank()
                        ? "demo".equals(mode) ? DemoIdentityConfig.publicDemoIdentities() : "[]"
                        : demoIdentities;
        this.demo = new DemoIdentityConfig(resolvedDemo);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurity(
            HttpSecurity http,
            PlatformStore store,
            PrincipalDirectory directory,
            ObjectProvider<JwtDecoder> decoder)
            throws Exception {
        AuthenticationThrottle throttle = new AuthenticationThrottle();
        PlatformSecurityFilter platform = new PlatformSecurityFilter(mode, demo, store, directory);
        http.securityMatcher("/api/**")
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        SecurityResponse.reject(
                                                                response,
                                                                401,
                                                                "UNAUTHENTICATED",
                                                                "An explicit valid bearer"
                                                                    + " credential is required"))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        SecurityResponse.reject(
                                                                response,
                                                                403,
                                                                "FORBIDDEN",
                                                                "Access is forbidden")))
                // This matcher accepts only explicitly supplied Authorization headers. No
                // cookie/session auth exists.
                // CSRF remains enabled for every other request; it is never disabled globally.
                .csrf(
                        csrf ->
                                csrf.ignoringRequestMatchers(
                                        ApiSecurityConfiguration::explicitBearer))
                .addFilterBefore(throttle, CsrfFilter.class)
                .addFilterAfter(new BearerRequirementFilter(), AuthenticationThrottle.class)
                .addFilterBefore(platform, AnonymousAuthenticationFilter.class);
        if ("pilot".equals(mode)) {
            DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
            resolver.setAllowFormEncodedBodyParameter(false);
            resolver.setAllowUriQueryParameter(false);
            http.oauth2ResourceServer(
                    resource ->
                            resource.bearerTokenResolver(resolver)
                                    .jwt(jwt -> jwt.decoder(decoder.getObject()))
                                    .authenticationEntryPoint(
                                            (request, response, failure) ->
                                                    SecurityResponse.reject(
                                                            response,
                                                            401,
                                                            "UNAUTHENTICATED",
                                                            "OIDC bearer verification failed")));
        }
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain browserSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        // The default CSRF configuration is intentionally retained for any future browser form
        // endpoint.
        return http.build();
    }

    @Bean
    public UserDetailsService noPasswordAuthentication() {
        return username -> {
            throw new UsernameNotFoundException("Password authentication is unsupported");
        };
    }

    @Bean
    @ConditionalOnProperty(name = "harness.mode", havingValue = "pilot")
    @ConditionalOnMissingBean(JwtDecoder.class)
    @Conditional(DefaultDecoderEnabledCondition.class)
    public JwtDecoder oidcDecoder(
            @Value("${harness.security.oidc.issuer:}") String issuer,
            @Value("${harness.security.oidc.audience:}") String audience,
            @Value("${harness.security.oidc.jwk-set-uri:}") String jwks) {
        requireHttps(issuer, "OIDC issuer");
        NimbusJwtDecoder decoder;
        if (jwks.isBlank()) {
            decoder =
                    NimbusJwtDecoder.withIssuerLocation(issuer)
                            .jwsAlgorithm(SignatureAlgorithm.RS256)
                            .build();
        } else {
            requireHttps(jwks, "OIDC JWK set");
            decoder =
                    NimbusJwtDecoder.withJwkSetUri(jwks)
                            .jwsAlgorithm(SignatureAlgorithm.RS256)
                            .build();
        }
        decoder.setJwtValidator(validators(issuer, audience));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> requiredClaims =
                jwt -> {
                    Instant now = Instant.now();
                    if (jwt.getExpiresAt() == null
                            || !jwt.getExpiresAt().isAfter(now)
                            || jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now)
                            || !jwt.getAudience().contains(audience)) {
                        return OAuth2TokenValidatorResult.failure(
                                new OAuth2Error(
                                        "invalid_token",
                                        "Token requires an unexpired exp and the expected audience",
                                        null));
                    }
                    return OAuth2TokenValidatorResult.success();
                };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims);
    }

    private static boolean explicitBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null
                && header.startsWith("Bearer ")
                && header.length() > 7
                && header.length() <= 4096
                && request.getHeaders("Authorization").asIterator().hasNext()
                && !header.substring(7).chars().anyMatch(Character::isWhitespace);
    }

    private static void requireHttps(String value, String label) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(label + " must be a valid HTTPS URL");
        }
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null) {
            throw new IllegalStateException(
                    label + " must be a valid HTTPS URL without credentials or fragments");
        }
    }

    private static final class BearerRequirementFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!explicitBearer(request)
                    || Collections.list(request.getHeaders("Authorization")).size() != 1) {
                SecurityResponse.reject(
                        response, 401, "UNAUTHENTICATED", "An explicit bearer header is required");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    public static final class DefaultDecoderEnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment()
                    .getProperty("harness.security.oidc.decoder-enabled", Boolean.class, true);
        }
    }
}
