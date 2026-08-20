package com.delivery.harness.llm.gateway;

import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.llm.router.ModelRouter;
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
        String model = modelRouter.resolveModel(request.getScenario());
        log.info("LLM chat: scenario={}, model={}", request.getScenario(), model);

        try {
            long startTime = System.currentTimeMillis();
            String response = llmClient.chat(
                    model,
                    request.getMessages(),
                    request.getTools(),
                    request.getTemperature(),
                    request.getMaxTokens());
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
