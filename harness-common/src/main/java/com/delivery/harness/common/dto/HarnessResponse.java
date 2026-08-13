package com.delivery.harness.common.dto;

import com.delivery.harness.common.util.TraceUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarnessResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> HarnessResponse<T> success(T data) {
        return HarnessResponse.<T>builder()
                .code(0)
                .message("success")
                .data(data)
                .traceId(TraceUtil.getTraceId())
                .build();
    }

    public static <T> HarnessResponse<T> success(T data, String traceId) {
        return HarnessResponse.<T>builder()
                .code(0)
                .message("success")
                .data(data)
                .traceId(traceId)
                .build();
    }

    public static <T> HarnessResponse<T> error(int code, String message) {
        return HarnessResponse.<T>builder()
                .code(code)
                .message(message)
                .traceId(TraceUtil.getTraceId())
                .build();
    }

    public static <T> HarnessResponse<T> error(int code, String message, String traceId) {
        return HarnessResponse.<T>builder()
                .code(code)
                .message(message)
                .traceId(traceId)
                .build();
    }

    public static <T> HarnessResponse<T> error(int code, String message, T data, String traceId) {
        return HarnessResponse.<T>builder()
                .code(code)
                .message(message)
                .data(data)
                .traceId(traceId)
                .build();
    }
}
