package com.delivery.harness.gateway.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.gateway.platform.security.ApiSecurityConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@SpringBootTest
@AutoConfigureMockMvc
@Import(OidcSecurityApiTest.TestKeys.class)
@TestPropertySource(
        properties = {
            "harness.mode=pilot",
            "harness.security.demo-identities=[]",
            "harness.security.oidc.issuer=https://idp.example.test",
            "harness.security.oidc.audience=harness-api",
            "harness.security.oidc.decoder-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:oidc-security;DB_CLOSE_DELAY=-1",
            "harness.adapter.order-endpoint=https://orders.example.test/{order_id}",
            "harness.adapter.allowed-hosts=orders.example.test",
            "harness.knowledge.seed.enabled=false",
            "harness.eval.seed.enabled=false"
        })
class OidcSecurityApiTest {
    private static final String ISSUER = "https://idp.example.test";
    private static final RSAKey KEY = key();
    @Autowired MockMvc mvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestKeys {
        @Bean
        JwtDecoder signedJwtDecoder() throws Exception {
            var decoder =
                    NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey())
                            .signatureAlgorithm(SignatureAlgorithm.RS256)
                            .build();
            decoder.setJwtValidator(ApiSecurityConfiguration.validators(ISSUER, "harness-api"));
            return decoder;
        }
    }

    @Test
    void realRsaSignatureEstablishesTenantAndUserFromVerifiedClaims() throws Exception {
        mvc.perform(
                        get("/api/v2/session")
                                .header("Host", "localhost:8080")
                                .header("Authorization", "Bearer " + token(KEY, Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal.tenant_id").value("tenant-a"))
                .andExpect(jsonPath("$.principal.user_id").value("user-a"))
                .andExpect(jsonPath("$.principal.roles[0]").value("AGENT"));
    }

    @Test
    void signatureIssuerAudienceExpirationAndRequiredClaimsAreActuallyValidated() throws Exception {
        List<String> rejected =
                List.of(
                        token(key(), Map.of()),
                                token(KEY, Map.of("issuer", "https://wrong.example.test")),
                        token(KEY, Map.of("audience", "another-service")),
                                token(KEY, Map.of("expires", -120L)),
                        token(KEY, Map.of("no_exp", true)), token(KEY, Map.of("tenant_id", "")),
                        token(KEY, Map.of("tenant_id", "demo")),
                                token(KEY, Map.of("roles", List.of())),
                        token(KEY, Map.of("subject", "demo-agent")), "demo-agent-token");
        for (String token : rejected) {
            mvc.perform(
                            get("/api/v2/session")
                                    .header("Host", "localhost:8080")
                                    .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void bearerCannotBeReadFromCookiesQueryParametersOrAnAmbientSession() throws Exception {
        String token = token(KEY, Map.of());
        mvc.perform(
                        get("/api/v2/session")
                                .header("Host", "localhost:8080")
                                .param("access_token", token)
                                .cookie(new jakarta.servlet.http.Cookie("access_token", token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v2/session")
                                .header("Host", "localhost:8080")
                                .contentType("application/x-www-form-urlencoded")
                                .param("access_token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pilotRejectsV1EvenForAValidSignedIdentity() throws Exception {
        mvc.perform(
                        get("/api/v1/observe/traces")
                                .header("Host", "localhost:8080")
                                .header("Authorization", "Bearer " + token(KEY, Map.of())))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("V2_REQUIRED"));
    }

    private static String token(RSAKey key, Map<String, Object> overrides) throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(overrides.getOrDefault("issuer", ISSUER).toString())
                        .subject(overrides.getOrDefault("subject", "user-a").toString())
                        .audience(overrides.getOrDefault("audience", "harness-api").toString())
                        .issueTime(Date.from(Instant.now().minusSeconds(30)))
                        .claim("tenant_id", overrides.getOrDefault("tenant_id", "tenant-a"))
                        .claim("roles", overrides.getOrDefault("roles", List.of("AGENT")))
                        .claim("city_codes", List.of("CITY-A"))
                        .claim("station_ids", List.of("STATION-A"));
        if (!Boolean.TRUE.equals(overrides.get("no_exp"))) {
            claims.expirationTime(
                    Date.from(
                            Instant.now()
                                    .plusSeconds(
                                            ((Number) overrides.getOrDefault("expires", 300L))
                                                    .longValue())));
        }
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                        claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
