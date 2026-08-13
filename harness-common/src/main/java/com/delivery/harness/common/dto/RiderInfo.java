package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderInfo {

    private String riderId;
    private String riderName;
    private String stationId;
    private String status;
    private Double currentLat;
    private Double currentLng;
    private Integer currentOrderCount;
    private Integer todayDeliveredCount;
    private Double avgDeliveryDuration;
    private Double onTimeRate;
    private Map<String, Object> extraInfo;
}
