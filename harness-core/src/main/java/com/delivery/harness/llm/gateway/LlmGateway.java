package com.delivery.harness.llm.gateway;

import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.llm.router.ModelRouter;
import com.delivery.harness.platform.quality.QualityData;
import com.delivery.harness.platform.quality.RuntimeBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGateway {

    private final ModelRouter modelRouter;
    private final LlmClient llmClient;

    public LlmChatResponse chat(LlmChatRequest request) {
        RuntimeBinding.Snapshot config = RuntimeBinding.current();
        if (config != null && config.deterministicOnly()) {
            throw new LlmException("DETERMINISTIC_ONLY", "Model disabled by runtime configuration");
        }
        String model = config == null || "configured-default".equals(config.model())
                ? modelRouter.resolveModel(request.getScenario()) : config.model();
        java.util.List<LlmMessage> messages = request.getMessages();
        if (config != null) {
            messages = new java.util.ArrayList<>();
            messages.add(LlmMessage.builder().role("system").content(config.systemPrompt()).build());
            for (LlmMessage message : request.getMessages()) {
                if (!"system".equals(message.getRole())) {
                    messages.add(message);
                } else {
                    messages.add(LlmMessage.builder().role("user")
                            .content("任务输出契约与约束：\n" + message.getContent()).build());
                }
            }
            RuntimeBinding.recordCall(java.util.Map.of("actual_model", model,
                    "actual_prompt_hash", QualityData.hash(config.systemPrompt()),
                    "request_hash", QualityData.hash(messages), "config_hash", config.configHash()));
        }
        log.info("LLM chat: scenario={}, model={}", request.getScenario(), model);

        try {
            long startTime = System.currentTimeMillis();
            String response = llmClient.chat(
                    model,
                    messages,
                    request.getTools(),
                    config == null ? request.getTemperature() : Double.valueOf(config.temperature()),
                    config == null ? request.getMaxTokens() : Integer.valueOf(config.maxTokens()));
            return LlmChatResponse.builder()
                    .model(model)
                    .content(response)
                    .success(true)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("LLM call failed: model={}", model, e);
            throw new LlmException(model, e.getMessage(), e);
        }
    }
}
