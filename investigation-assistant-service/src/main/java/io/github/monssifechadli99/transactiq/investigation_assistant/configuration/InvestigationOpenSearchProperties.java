package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("investigation-assistant.opensearch")
public record InvestigationOpenSearchProperties(
        String url,
        Duration requestTimeout,
        String physicalIndex,
        String readAlias,
        String writeAlias,
        String hybridPipeline) {
}
