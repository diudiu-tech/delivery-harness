package com.delivery.harness.gateway.platform.quality;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.gateway.platform.validation.StrictLong;
import com.delivery.harness.gateway.platform.validation.StrictString;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Shared typed lifecycle requests and serialization used by independent quality endpoints. */
public final class QualityRequests {
    private QualityRequests() {}

    public interface Request {
        default Map<String, Object> toMap() {
            return JsonUtil.toMap(this);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LifecycleRequest(
            @JsonDeserialize(using = StrictLong.class) @NotNull @Min(1) Long expectedVersion,
            @Size(max = 2000) @JsonDeserialize(using = StrictString.class) String reason)
            implements Request {}
}
