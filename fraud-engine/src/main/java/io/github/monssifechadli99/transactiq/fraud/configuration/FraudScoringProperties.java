package io.github.monssifechadli99.transactiq.fraud.configuration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud.scoring")
public record FraudScoringProperties(List<ContributionProperties> contributions) {

    public FraudScoringProperties {
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    public record ContributionProperties(String ruleCode, String severity, Integer points) {}
}
