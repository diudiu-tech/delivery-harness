package com.delivery.harness.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderInfo {

    private String orderId;
    private String merchantId;
    private String merchantName;
    private String riderId;
    private String riderName;
    private String userId;
    private String status;

    private String pickupAddress;
    private String deliveryAddress;
    private Double pickupLat;
    private Double pickupLng;
    private Double deliveryLat;
    private Double deliveryLng;

    private LocalDateTime createTime;
    private LocalDateTime acceptTime;
    private LocalDateTime arriveShopTime;
    private LocalDateTime pickupTime;
    private LocalDateTime deliveryTime;
    private LocalDateTime expectedDeliveryTime;

    private Integer estimatedDurationSeconds;
    private Integer actualDurationSeconds;
    private BigDecimal orderAmount;
    private BigDecimal deliveryFee;

    private String stationId;
    private String stationName;
    private String cityCode;

    private Boolean isAbnormal;
    private String abnormalType;
    private List<OrderEvent> events;
    private Map<String, Object> extraInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderEvent {
        private String eventType;
        private String description;
        private LocalDateTime eventTime;
        private Map<String, Object> payload;
    }
}
