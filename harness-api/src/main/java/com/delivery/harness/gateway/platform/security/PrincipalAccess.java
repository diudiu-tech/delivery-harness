package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.platform.auth.AuthContext;
import com.delivery.harness.platform.store.PlatformException;

import jakarta.servlet.http.HttpServletRequest;

public final class PrincipalAccess {
    public static final String ATTRIBUTE = "harness.principal";

    private PrincipalAccess() {}

    public static AuthContext.Principal get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof AuthContext.Principal principal) {
            return principal;
        }
        throw new PlatformException(
                401, "UNAUTHENTICATED", "An authenticated principal is required");
    }
}
