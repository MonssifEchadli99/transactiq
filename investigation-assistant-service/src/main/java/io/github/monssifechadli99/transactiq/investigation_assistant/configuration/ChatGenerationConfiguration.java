package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.chat.openai.OpenAiChatGenerationAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvestigationChatProperties.class)
public class ChatGenerationConfiguration {

    @Bean
    ChatGenerationPort chatGenerationPort(
            ChatModel chatModel, ObjectMapper objectMapper, InvestigationChatProperties properties) {
        return new OpenAiChatGenerationAdapter(chatModel, objectMapper, properties);
    }
}
