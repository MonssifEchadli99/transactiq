package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class InvestigationChatPropertiesTest {

    @Test
    void keepsTheConfiguredModelAndTimeout() {
        InvestigationChatProperties properties =
                new InvestigationChatProperties("  portfolio-chat-model  ", Duration.ofSeconds(10));

        assertEquals("portfolio-chat-model", properties.model());
        assertEquals(Duration.ofSeconds(10), properties.timeout());
    }

    @Test
    void rejectsABlankModelWithASafeMessage() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new InvestigationChatProperties("  ", Duration.ofSeconds(10)));

        assertEquals(InvestigationChatProperties.MODEL_CONFIGURATION_ERROR, failure.getMessage());
    }

    @Test
    void rejectsANonPositiveTimeoutWithASafeMessage() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new InvestigationChatProperties("portfolio-chat-model", Duration.ZERO));

        assertEquals(InvestigationChatProperties.TIMEOUT_CONFIGURATION_ERROR, failure.getMessage());
    }

    @Test
    void disablesTheSpringAiChatModelLoggerThatCanRenderPrompts() throws Exception {
        Object configuredLevel = applicationProperty(
                "logging.level.org.springframework.ai.openai.OpenAiChatModel");

        assertEquals("OFF", configuredLevel);
    }

    @Test
    void mapsTheEnvironmentBackedTimeoutToTheOpenAiHttpClient() throws Exception {
        Object moduleTimeout = applicationProperty("investigation-assistant.chat.timeout");
        Object providerTimeout = applicationProperty("spring.ai.openai.chat.timeout");

        assertEquals("${TRANSACTIQ_CHAT_TIMEOUT:10s}", moduleTimeout);
        assertEquals(moduleTimeout, providerTimeout);
    }

    private static Object applicationProperty(String name) throws Exception {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
