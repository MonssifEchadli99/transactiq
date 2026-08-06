package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.embedding.openai.OpenAiEmbeddingAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Wires the single production {@link EmbeddingPort} strategy: OpenAI through Spring AI's
 * autoconfigured {@link EmbeddingModel}. Tests replace this bean with a deterministic
 * double and never trigger a live OpenAI call.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfiguration {

    static final String OPENAI_SDK_LOG_CONFIGURATION_ERROR =
            "OPENAI_LOG must be absent or set to off";
    static final String OPENAI_API_KEY_CONFIGURATION_ERROR =
            "Effective OpenAI embedding API key must be configured with a nonblank value";
    static final String OPENAI_CHAT_API_KEY_CONFIGURATION_ERROR =
            "Effective OpenAI chat API key must be configured with a nonblank value";

    private static final String OPENAI_SDK_LOG_ENVIRONMENT_VARIABLE = "OPENAI_LOG";
    private static final String OPENAI_SDK_LOG_DISABLED_VALUE = "off";
    private static final String COMMON_API_KEY_PROPERTY = "spring.ai.openai.api-key";
    private static final String EMBEDDING_API_KEY_PROPERTY = "spring.ai.openai.embedding.api-key";
    private static final String CHAT_API_KEY_PROPERTY = "spring.ai.openai.chat.api-key";
    private static final String CHAT_MODEL_PROPERTY = "spring.ai.model.chat";
    private static final String DISABLED_MODEL_VALUE = "none";

    @Bean
    static BeanFactoryPostProcessor requireSafeOpenAiSdkLogging(Environment environment) {
        return beanFactory -> {
            // The OpenAI SDK reads this process environment variable directly; a Spring
            // property must never be allowed to mask an unsafe value from System.getenv().
            String sdkLogLevel = System.getenv(OPENAI_SDK_LOG_ENVIRONMENT_VARIABLE);
            if (sdkLogLevel != null && !OPENAI_SDK_LOG_DISABLED_VALUE.equalsIgnoreCase(sdkLogLevel)) {
                throw new IllegalStateException(OPENAI_SDK_LOG_CONFIGURATION_ERROR);
            }
        };
    }

    @Bean
    @Profile("!demo-offline")
    static BeanFactoryPostProcessor requireOpenAiApiKeys(Environment environment) {
        return beanFactory -> {
            String effectiveApiKey = environment.containsProperty(EMBEDDING_API_KEY_PROPERTY)
                    ? environment.getProperty(EMBEDDING_API_KEY_PROPERTY)
                    : environment.getProperty(COMMON_API_KEY_PROPERTY);
            if (!StringUtils.hasText(effectiveApiKey)) {
                throw new IllegalStateException(OPENAI_API_KEY_CONFIGURATION_ERROR);
            }

            String configuredChatModel = environment.getProperty(CHAT_MODEL_PROPERTY);
            if (!DISABLED_MODEL_VALUE.equalsIgnoreCase(configuredChatModel)) {
                String effectiveChatApiKey = environment.containsProperty(CHAT_API_KEY_PROPERTY)
                        ? environment.getProperty(CHAT_API_KEY_PROPERTY)
                        : environment.getProperty(COMMON_API_KEY_PROPERTY);
                if (!StringUtils.hasText(effectiveChatApiKey)) {
                    throw new IllegalStateException(OPENAI_CHAT_API_KEY_CONFIGURATION_ERROR);
                }
            }
        };
    }

    @Bean
    @Profile("!demo-offline")
    EmbeddingPort embeddingPort(EmbeddingModel embeddingModel) {
        return new OpenAiEmbeddingAdapter(embeddingModel);
    }
}
