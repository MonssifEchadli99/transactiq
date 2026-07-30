package io.github.monssifechadli99.transactiq.authorization.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud.grpc.client")
public record FraudGrpcClientProperties(
        String host,
        int port,
        Duration deadline,
        boolean plaintext) {

    public FraudGrpcClientProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("fraud gRPC host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("fraud gRPC port must be between 1 and 65535");
        }
        if (deadline == null || deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("fraud gRPC deadline must be positive");
        }
    }
}
