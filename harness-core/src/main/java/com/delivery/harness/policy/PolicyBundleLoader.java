package com.delivery.harness.policy;

import com.delivery.harness.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Loads and fail-fast validates the single active {@link PolicyBundle} at
 * application startup.
 */
@Slf4j
@Component
public class PolicyBundleLoader {

    public static final String DEFAULT_POLICY_RESOURCE = "policy/policy-v1.0.0.json";

    private final PolicyBundle activeBundle;
    private final String activePolicyHash;

    public PolicyBundleLoader(
            @Value("${harness.policy.resource:" + DEFAULT_POLICY_RESOURCE + "}") String resourcePath) {
        String rawJson = readClasspath(resourcePath);
        PolicyBundle bundle = JsonUtil.fromJson(rawJson, PolicyBundle.class);
        PolicyBundleValidator.validate(bundle);
        this.activeBundle = bundle;
        this.activePolicyHash = sha256(rawJson);
        log.info("Active PolicyBundle loaded and validated: version={}, hash={}, rules={}",
                bundle.policyVersion(), activePolicyHash.substring(0, 12), bundle.compensationRules().size());
    }

    public static PolicyBundleLoader loadDefault() {
        return new PolicyBundleLoader(DEFAULT_POLICY_RESOURCE);
    }

    public PolicyBundle activeBundle() {
        return activeBundle;
    }

    public String activePolicyHash() {
        return activePolicyHash;
    }

    private static String readClasspath(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("PolicyBundle resource not found on classpath: " + resourcePath);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PolicyBundle resource: " + resourcePath, e);
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
