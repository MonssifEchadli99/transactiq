package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("investigation-assistant.retrieval")
public record InvestigationRetrievalProperties(
        int candidatePoolSize,
        int focalTextMaxLength,
        int excerptMaxLength) {
}
