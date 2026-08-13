package com.delivery.harness.llm.gateway;

import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.common.exception.LlmException;
import com.delivery.harness.llm.parser.StructuredOutputParser;
import com.delivery.harness.llm.prompt.PromptRegistry;
import com.delivery.harness.llm.router.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGateway {

    private final ModelRouter modelRouter;
    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;
    private final StructuredOutputParser outputParser;

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

    public LlmChatResponse chatWithPromptTemplate(String templateId, Map<String, Object> variables, String scenario) {
        String renderedPrompt = promptRegistry.render(templateId, variables);
        LlmChatRequest request = LlmChatRequest.builder()
                .scenario(scenario)
                .messages(Arrays.asList(
                        LlmMessage.system("You are a delivery industry expert assistant."),
                        LlmMessage.user(renderedPrompt)
                ))
                .build();
        return chat(request);
    }

    public <T> T chatAndParse(LlmChatRequest request, Class<T> resultType) {
        LlmChatResponse response = chat(request);
        return outputParser.parse(response.getContent(), resultType);
    }
}
