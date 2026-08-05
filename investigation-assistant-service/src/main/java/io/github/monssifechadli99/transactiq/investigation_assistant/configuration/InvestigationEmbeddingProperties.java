package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code expectedDimensions} must match the configured OpenAI embedding model's output
 * size (1,536 for text-embedding-3-small). Changing the model or its dimensions requires
 * a new index version and a full evidence rebuild; see docs/business/ai-investigation-assistant.md.
 */
@ConfigurationProperties("investigation-assistant.embedding")
public record InvestigationEmbeddingProperties(int expectedDimensions) {
}
