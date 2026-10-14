package com.delivery.harness.gateway.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/session")
public class SessionController {
    private final String mode;

    public SessionController(@Value("${harness.mode:demo}") String mode) {
        this.mode = mode;
    }

    @GetMapping
    public Map<String, Object> session(HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        return Map.of(
                "mode",
                mode,
                "principal",
                Map.of(
                        "tenant_id",
                        actor.tenantId(),
                        "user_id",
                        actor.userId(),
                        "roles",
                        actor.roles(),
                        "city_codes",
                        actor.cityCodes(),
                        "station_ids",
                        actor.stationIds()));
    }
}
