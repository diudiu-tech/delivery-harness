package com.delivery.harness.gateway.platform.security;
import com.delivery.harness.platform.adapter.OrderSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/session")
public class SessionController {
    private final OrderSource source;
    public SessionController(OrderSource source){this.source=source;}
    @GetMapping public Map<String,Object> session(HttpServletRequest request){var actor=PrincipalAccess.get(request);return Map.of("mode",source.status().get("mode"),"principal",Map.of("tenant_id",actor.tenantId(),"user_id",actor.userId(),"roles",actor.roles(),"city_codes",actor.cityCodes(),"station_ids",actor.stationIds()),"adapter",source.status());}
}
