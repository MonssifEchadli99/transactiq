package io.github.monssifechadli99.transactiq.authorization.adapter.out.grpc;

import io.github.monssifechadli99.transactiq.authorization.configuration.FraudGrpcClientProperties;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class ManagedFraudGrpcChannel implements AutoCloseable {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ManagedChannel channel;

    public ManagedFraudGrpcChannel(FraudGrpcClientProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(properties.host(), properties.port())
                .disableRetry();
        if (properties.plaintext()) {
            builder.usePlaintext();
        } else {
            builder.useTransportSecurity();
        }
        channel = builder.build();
    }

    public Channel channel() {
        return channel;
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                channel.shutdownNow();
                channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
