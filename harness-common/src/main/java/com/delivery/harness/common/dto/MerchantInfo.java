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
public class MerchantInfo {

    private String merchantId;
    private String merchantName;
    private String category;
    private String stationId;
    private String cityCode;
    private Double lat;
    private Double lng;
    private Integer avgPreparationSeconds;
    private Double onTimeRate;
    private Double complaintRate;
    private Map<String, Object> extraInfo;
}
