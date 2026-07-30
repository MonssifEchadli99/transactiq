package io.github.monssifechadli99.transactiq.authorization.configuration;

import io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc.FraudGrpcClientAdapter;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc.FraudGrpcMapper;
import io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc.ManagedFraudGrpcChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FraudGrpcClientProperties.class)
@ConditionalOnProperty(
        prefix = "fraud.grpc.client",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FraudGrpcClientConfiguration {

    @Bean
    ManagedFraudGrpcChannel managedFraudGrpcChannel(FraudGrpcClientProperties properties) {
        return new ManagedFraudGrpcChannel(properties);
    }

    @Bean
    FraudGrpcClientAdapter fraudAssessmentAdapter(
            ManagedFraudGrpcChannel managedChannel,
            FraudGrpcClientProperties properties) {
        return new FraudGrpcClientAdapter(
                managedChannel.channel(), properties.deadline(), new FraudGrpcMapper());
    }
}
