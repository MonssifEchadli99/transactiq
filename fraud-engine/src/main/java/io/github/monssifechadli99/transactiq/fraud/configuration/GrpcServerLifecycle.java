package io.github.monssifechadli99.transactiq.fraud.configuration;

import io.grpc.Server;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

public final class GrpcServerLifecycle implements SmartLifecycle {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final String TERMINATION_WAITER_THREAD_NAME =
            "fraud-engine-grpc-termination-waiter";

    private final Server server;
    private volatile boolean running;
    private volatile Thread terminationWaiter;

    GrpcServerLifecycle(Server server) {
        this.server = Objects.requireNonNull(server, "server must not be null");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        Thread waiter = new Thread(this::awaitTermination, TERMINATION_WAITER_THREAD_NAME);
        waiter.setDaemon(false);
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start fraud-engine gRPC server", e);
        }

        terminationWaiter = waiter;
        running = true;
        try {
            waiter.start();
        } catch (RuntimeException | Error failure) {
            running = false;
            terminationWaiter = null;
            try {
                server.shutdownNow();
                WaitResult forcedWait = awaitTerminationWithinTimeout();
                if (forcedWait.interrupted()) {
                    Thread.currentThread().interrupt();
                }
            } catch (RuntimeException | Error shutdownFailure) {
                failure.addSuppressed(shutdownFailure);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        Thread waiter = terminationWaiter;
        if (!running && (waiter == null || !waiter.isAlive())) {
            terminationWaiter = null;
            return;
        }

        running = false;
        boolean interrupted = false;
        try {
            server.shutdown();
            WaitResult gracefulWait = awaitTerminationWithinTimeout();
            interrupted = gracefulWait.interrupted();
            if (!gracefulWait.terminated()) {
                server.shutdownNow();
                WaitResult forcedWait = awaitTerminationWithinTimeout();
                interrupted |= forcedWait.interrupted();
            }
        } finally {
            interrupted |= stopTerminationWaiter(waiter);
            if (waiter == null || !waiter.isAlive()) {
                terminationWaiter = null;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int port() {
        return server.getPort();
    }

    private void awaitTermination() {
        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    private WaitResult awaitTerminationWithinTimeout() {
        try {
            return new WaitResult(
                    server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    false);
        } catch (InterruptedException e) {
            return new WaitResult(false, true);
        }
    }

    private static boolean stopTerminationWaiter(Thread waiter) {
        if (waiter == null || waiter == Thread.currentThread()) {
            return false;
        }

        waiter.interrupt();
        boolean interrupted = false;
        long remainingNanos = TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        long deadline = System.nanoTime() + remainingNanos;
        while (waiter.isAlive() && remainingNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.timedJoin(waiter, remainingNanos);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        if (waiter.isAlive()) {
            waiter.interrupt();
        }
        return interrupted;
    }

    private record WaitResult(boolean terminated, boolean interrupted) {}
}
