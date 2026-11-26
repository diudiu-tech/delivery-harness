package com.delivery.harness.gateway.platform.adapter;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.adapter.OrderSource;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Source contract metadata; this endpoint neither probes an order nor writes records. */
@RestController
@RequestMapping("/api/v2/adapters")
public class AdapterController {
    private final OrderSource orders;

    public AdapterController(OrderSource orders) {
        this.orders = orders;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        var actor = PrincipalAccess.get(request);
        actor.requireRole(
                "AGENT", "OPERATOR", "REVIEWER", "SUPERVISOR", "QUALITY", "AUDITOR", "ADMIN");
        return orders.status();
    }
}
