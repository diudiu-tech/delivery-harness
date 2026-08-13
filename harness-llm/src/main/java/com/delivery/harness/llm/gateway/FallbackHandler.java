package com.delivery.harness.llm.gateway;

import com.delivery.harness.llm.router.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackHandler {

    private final ModelRouter modelRouter;

    public LlmChatResponse handleFallback(LlmChatRequest request, Exception originalException) {
        log.warn("LLM fallback triggered for scenario={}, error={}", request.getScenario(), originalException.getMessage());
        return LlmChatResponse.builder()
                .model("fallback")
                .content(buildFallbackMessage(request.getScenario()))
                .success(false)
                .errorMessage(originalException.getMessage())
                .build();
    }

    private String buildFallbackMessage(String scenario) {
        return String.format(
                "当前模型服务暂时不可用，请稍后重试。场景：%s。如需紧急处理，请联系值班专家。",
                scenario != null ? scenario : "unknown"
        );
    }
}
