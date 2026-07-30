package io.github.monssifechadli99.transactiq.fraud.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud.velocity")
public record FraudVelocityProperties(Duration deduplicationRetention) {}
