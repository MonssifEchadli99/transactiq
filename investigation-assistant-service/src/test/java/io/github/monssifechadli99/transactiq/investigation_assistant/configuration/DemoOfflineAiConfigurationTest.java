package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.ChatGenerationRequest;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.EvidenceSourceType;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingSource;
import io.github.monssifechadli99.transactiq.investigation_assistant.domain.GroundingStatus;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class DemoOfflineAiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DemoOfflineAiConfiguration.class,
                    EmbeddingConfiguration.class,
                    ChatGenerationConfiguration.class)
            .withPropertyValues("investigation-assistant.embedding.expected-dimensions=1536");

    @Test
    void explicitDemoProfileProvidesOnlyDeterministicOfflinePorts() {
        contextRunner.withPropertyValues("spring.profiles.active=demo-offline").run(context -> {
            assertEquals(1, context.getBeansOfType(EmbeddingPort.class).size());
            assertEquals(1, context.getBeansOfType(ChatGenerationPort.class).size());
            assertEquals(0, context.getBeansOfType(EmbeddingModel.class).size());
            assertEquals(0, context.getBeansOfType(ChatModel.class).size());
            assertTrue(context.containsBean("requireSafeOpenAiSdkLogging"),
                    "OPENAI_LOG protection must remain active in the offline profile");
            assertFalse(context.containsBean("requireOpenAiApiKeys"),
                    "only provider API-key validation is bypassed in the offline profile");

            EmbeddingPort embeddings = context.getBean(EmbeddingPort.class);
            float[] first = embeddings.embed("synthetic demo evidence");
            float[] repeated = embeddings.embed("synthetic demo evidence");
            float[] different = embeddings.embed("different synthetic demo evidence");
            assertEquals(1536, first.length);
            assertArrayEquals(first, repeated);
            assertFalse(Arrays.equals(first, different));

            String caseId = "00000000-0000-4000-8000-000000000801";
            String sourceId = "case:" + caseId + ":evidence";
            var answer = context.getBean(ChatGenerationPort.class).generate(new ChatGenerationRequest(
                    caseId,
                    "What does the published evidence support?",
                    List.of(new GroundingSource(
                            sourceId,
                            EvidenceSourceType.CASE_EVIDENCE,
                            caseId,
                            "Synthetic evidence fixture."))));
            assertEquals(GroundingStatus.GROUNDED, answer.groundingStatus());
            assertEquals(List.of(sourceId), answer.findings().getFirst().citationIds());
        });
    }

    @Test
    void profileResourceDisablesProviderBackedModels() throws IOException {
        var propertySources = new YamlPropertySourceLoader().load(
                "demo-offline", new ClassPathResource("application-demo-offline.yml"));

        assertEquals("demo-offline", propertySources.getFirst().getProperty("spring.config.activate.on-profile"));
        assertEquals("none", propertySources.getFirst().getProperty("spring.ai.model.chat"));
        assertEquals("none", propertySources.getFirst().getProperty("spring.ai.model.embedding"));
    }

    @Test
    void offlinePortsRemainAbsentWithoutTheExplicitDemoProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(DemoOfflineAiConfiguration.class)
                .withPropertyValues("spring.profiles.active=portfolio")
                .run(context -> {
                    assertFalse(context.containsBean("demoOfflineEmbeddingPort"));
                    assertFalse(context.containsBean("demoOfflineChatGenerationPort"));
                });
    }
}
