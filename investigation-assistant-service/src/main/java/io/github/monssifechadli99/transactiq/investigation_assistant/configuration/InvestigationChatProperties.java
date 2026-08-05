package io.github.monssifechadli99.transactiq.investigation_assistant.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties("investigation-assistant.chat")
public record InvestigationChatProperties(String model, Duration timeout) {

    static final String MODEL_CONFIGURATION_ERROR =
            "Investigation chat model must be configured with a nonblank value";
    static final String TIMEOUT_CONFIGURATION_ERROR =
            "Investigation chat timeout must be greater than zero";

    public InvestigationChatProperties {
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException(MODEL_CONFIGURATION_ERROR);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(TIMEOUT_CONFIGURATION_ERROR);
        }
        model = model.trim();
    }
}
