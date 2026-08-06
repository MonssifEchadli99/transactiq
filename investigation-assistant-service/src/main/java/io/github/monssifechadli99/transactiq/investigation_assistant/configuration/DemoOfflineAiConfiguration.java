package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.demo.DemoOfflineChatGenerationAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.adapter.out.demo.DemoOfflineEmbeddingAdapter;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.ChatGenerationPort;
import io.github.monssifechadli99.transactiq.investigation_assistant.application.port.out.EmbeddingPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Offline fixtures that can only be activated by explicitly selecting {@code demo-offline}. */
@Configuration(proxyBeanMethods = false)
@Profile("demo-offline")
@EnableConfigurationProperties(InvestigationEmbeddingProperties.class)
public class DemoOfflineAiConfiguration {

    @Bean
    EmbeddingPort demoOfflineEmbeddingPort(InvestigationEmbeddingProperties properties) {
        return new DemoOfflineEmbeddingAdapter(properties.expectedDimensions());
    }

    @Bean
    ChatGenerationPort demoOfflineChatGenerationPort() {
        return new DemoOfflineChatGenerationAdapter();
    }
}
