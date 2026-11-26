package com.delivery.harness.gateway.platform.security;

import com.delivery.harness.common.util.JsonUtil;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

final class SecurityResponse {
    private SecurityResponse() {}

    static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.getWriter().write(JsonUtil.toJson(Map.of("code", code, "message", message)));
    }
}
