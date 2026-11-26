package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.auth.PrincipalDirectory;
import com.delivery.harness.platform.store.PlatformException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Only local demo mode accepts these hashes. Pilot never consults this directory. */
public final class DemoIdentityConfig {
    private record Identity(byte[] hash, AuthContext.Principal principal) {}

    private final List<Identity> identities;

    public DemoIdentityConfig(String json) {
        if (json.length() > 100_000) {
            throw new IllegalArgumentException("Demo identity configuration is too large");
        }
        List<Map<String, Object>> claims = JsonUtil.fromJson(json, new TypeReference<>() {});
        if (claims.size() > 100) {
            throw new IllegalArgumentException("At most 100 demo identities are allowed");
        }
        identities =
                claims.stream()
                        .map(
                                claim -> {
                                    if (claim.containsKey("token")) {
                                        throw new IllegalArgumentException(
                                                "Cleartext static tokens are unsupported; use demo"
                                                    + " token_sha256");
                                    }
                                    String hash = required(claim, "token_sha256");
                                    if (!hash.matches("[a-fA-F0-9]{64}")) {
                                        throw new IllegalArgumentException(
                                                "Demo credentials require a SHA-256 hash");
                                    }
                                    return new Identity(
                                            HexFormat.of().parseHex(hash),
                                            new AuthContext.Principal(
                                                    required(claim, "tenant_id"),
                                                    required(claim, "user_id"),
                                                    strings(claim.get("roles")),
                                                    strings(claim.get("city_codes")),
                                                    strings(claim.get("station_ids"))));
                                })
                        .toList();
    }

    public static String publicDemoIdentities() {
        List<Map<String, Object>> claims =
                PrincipalDirectory.demoPrincipals().entrySet().stream()
                        .map(
                                entry -> {
                                    AuthContext.Principal principal = entry.getValue();
                                    return Map.<String, Object>of(
                                            "token_sha256",
                                            HexFormat.of()
                                                    .formatHex(digest(entry.getKey() + "-token")),
                                            "tenant_id",
                                            principal.tenantId(),
                                            "user_id",
                                            principal.userId(),
                                            "roles",
                                            principal.roles(),
                                            "city_codes",
                                            principal.cityCodes(),
                                            "station_ids",
                                            principal.stationIds());
                                })
                        .toList();
        return JsonUtil.toJson(claims);
    }

    public AuthContext.Principal authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 4096) {
            throw unauthenticated();
        }
        String token = header.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw unauthenticated();
        }
        byte[] hash = digest(token);
        AuthContext.Principal matched = null;
        for (Identity identity : identities) {
            if (MessageDigest.isEqual(identity.hash(), hash)) {
                matched = identity.principal();
            }
        }
        if (matched == null) {
            throw unauthenticated();
        }
        return matched;
    }

    public static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PlatformException unauthenticated() {
        return new PlatformException(
                401, "UNAUTHENTICATED", "An explicit valid bearer credential is required");
    }

    private static String required(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200) {
            throw new IllegalArgumentException("Invalid demo identity " + key);
        }
        return text;
    }

    static Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)
                || collection.isEmpty()
                || collection.size() > 100
                || collection.stream()
                        .anyMatch(
                                item ->
                                        !(item instanceof String text)
                                                || text.isBlank()
                                                || text.length() > 100)) {
            throw new IllegalArgumentException("Identity claims require bounded roles and scopes");
        }
        return collection.stream().map(String.class::cast).collect(Collectors.toUnmodifiableSet());
    }
}
