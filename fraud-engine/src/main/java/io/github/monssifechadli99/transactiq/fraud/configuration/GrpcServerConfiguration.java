package io.github.monssifechadli99.transactiq.fraud.configuration;

import io.github.monssifechadli99.transactiq.fraud.adapter.in.grpc.FraudAssessmentGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GrpcServerConfiguration {

    @Bean
    GrpcServerLifecycle grpcServerLifecycle(
            FraudAssessmentGrpcService fraudAssessmentGrpcService,
            @Value("${fraud.grpc.port:9090}") int port) {
        Server server = ServerBuilder.forPort(port)
                .addService(fraudAssessmentGrpcService)
                .build();
        return new GrpcServerLifecycle(server);
    }
}
