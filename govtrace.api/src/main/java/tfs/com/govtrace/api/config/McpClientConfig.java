package tfs.com.govtrace.api.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import tfs.com.govtrace.api.agent.AuditorAgente;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpClientConfig {

    @Value("${govtrace.ai.gemini.api-key}")
    private String geminiApiKey;

    @Bean
    public ChatLanguageModel geminiChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName("gemini-1.5-flash")
                .build();
    }

    @Bean
    public AuditorAgente auditorAgente(ChatLanguageModel chatLanguageModel) {
        return AiServices.builder(AuditorAgente.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }
}