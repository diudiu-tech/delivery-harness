package com.delivery.harness.gateway.config;

import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.exception.BizException;
import com.delivery.harness.common.exception.HarnessException;
import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.common.exception.ToolException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<HarnessResponse<?>> handleBizException(BizException e) {
        log.warn("BizException: {}", e.getMessage());
        return ResponseEntity.badRequest().body(HarnessResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ToolException.class)
    public ResponseEntity<HarnessResponse<?>> handleToolException(ToolException e) {
        log.error("ToolException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(HarnessResponse.error(e.getCode(), "Tool execution failed"));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<HarnessResponse<?>> handleLlmException(LlmException e) {
        log.error("LlmException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(HarnessResponse.error(e.getCode(), "Model service unavailable"));
    }

    @ExceptionHandler(HarnessException.class)
    public ResponseEntity<HarnessResponse<?>> handleHarnessException(HarnessException e) {
        log.error("HarnessException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(HarnessResponse.error(e.getCode(), "Harness operation failed"));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<HarnessResponse<?>> handleInvalidRequest(Exception e) {
        log.warn("Invalid request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(HarnessResponse.error(400, "Invalid request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<HarnessResponse<?>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(HarnessResponse.error(500, "Internal server error"));
    }
}
