package com.delivery.harness.diagnosis;

/**
 * Closed set of five structured candidate factors ranked by
 * {@link DeterministicResidualEngine}. Names are deliberately neutral and never
 * assert rider/merchant legal fault without corroborating telemetry.
 */
public enum CausalFactorCode {
    DISPATCH_WAIT_EXCESS("派单等待偏长"),
    TO_SHOP_EXCESS("骑手到店偏慢"),
    MERCHANT_PREP_EXCESS("商家出餐偏慢"),
    WEATHER_EXPLAINED("恶劣天气路途增量"),
    UNEXPLAINED_ROAD_EXCESS("路途未解释超额耗时");

    private final String label;

    CausalFactorCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
