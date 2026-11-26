package com.delivery.harness.common.dto;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/** Policy method identifiers shared by calculation, review and any execution adapter. */
public enum CompensationMethod {
    REFUND("原路退款", true, true),
    COUPON("优惠券", false, true),
    RED_PACKET("红包", false, true),
    RESEND("补送", false, false),
    RESEND_AND_COUPON("补送及优惠券", false, true),
    NONE("无需赔付", false, false),
    MANUAL("需人工判断", false, false);

    private final String displayName;
    private final boolean cashRefund;
    private final boolean monetary;

    CompensationMethod(String displayName, boolean cashRefund, boolean monetary) {
        this.displayName = displayName;
        this.cashRefund = cashRefund;
        this.monetary = monetary;
    }

    /**
     * Accepts historical seed aliases at ingestion; unknown methods never default to a cash refund.
     */
    public static CompensationMethod fromPolicy(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Compensation method is required");
        }
        return switch (text.trim().toUpperCase(Locale.ROOT)) {
            case "REFUND", "原路退款", "退款" -> REFUND;
            case "COUPON", "发放优惠券", "优惠券", "券" -> COUPON;
            case "RED_PACKET", "红包", "赠送红包" -> RED_PACKET;
            case "RESEND", "补送" -> RESEND;
            case "RESEND_AND_COUPON", "补送并发放优惠券" -> RESEND_AND_COUPON;
            case "NONE", "无需赔付" -> NONE;
            case "MANUAL", "需人工判断" -> MANUAL;
            default -> throw new IllegalArgumentException("Unsupported compensation method");
        };
    }

    /** Method semantics must agree with the policy formula before publication or calculation. */
    public static CompensationMethod fromAction(Map<String, Object> action) {
        CompensationMethod method = fromPolicy(action.get("method"));
        if (method == MANUAL && !"MANUAL".equals(action.get("amount_type"))) {
            throw new IllegalArgumentException(
                    "Manual compensation requires a manual amount policy");
        }
        if ("MANUAL".equals(action.get("amount_type")) && method != MANUAL) {
            throw new IllegalArgumentException(
                    "An undetermined policy must use the manual compensation method");
        }
        if (method == NONE || method == RESEND) {
            if (!"FIXED".equals(action.get("amount_type"))
                    || action.get("amount") == null
                    || new BigDecimal(action.get("amount").toString()).signum() != 0) {
                throw new IllegalArgumentException(
                        "A nonmonetary policy must have a fixed zero amount");
            }
        }
        return method;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isCashRefund() {
        return cashRefund;
    }

    public boolean isMonetary() {
        return monetary;
    }
}
