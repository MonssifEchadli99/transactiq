package io.github.monssifechadli99.transactiq.fraud.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GrpcServerLifecycleTest {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    @Test
    void startReturnsWhileNonDaemonTerminationWaiterKeepsServerAlive() throws Exception {
        Server server = mock(Server.class);
        when(server.start()).thenReturn(server);
        WaiterProbe waiterProbe = blockTerminationWaiter(server);
        when(server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .thenAnswer(invocation -> {
                    waiterProbe.release();
                    return true;
                });
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        try {
            org.junit.jupiter.api.Assertions.assertTimeout(
                    Duration.ofSeconds(1), lifecycle::start);

            assertTrue(waiterProbe.awaitStarted());
            Thread waiter = waiterProbe.thread();
            assertFalse(waiter.isDaemon());
            assertTrue(waiter.isAlive());
            assertTrue(lifecycle.isRunning());

            lifecycle.stop();

            waiter.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(waiter.isAlive());
            assertFalse(lifecycle.isRunning());
            verify(server).shutdown();
            verify(server).awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            verify(server, never()).shutdownNow();
        } finally {
            waiterProbe.release();
            lifecycle.stop();
        }
    }

    @Test
    void stopForcesShutdownAfterGracefulTimeoutAndDoesNotLeakWaiter() throws Exception {
        Server server = mock(Server.class);
        when(server.start()).thenReturn(server);
        WaiterProbe waiterProbe = blockTerminationWaiter(server);
        when(server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .thenReturn(false, true);
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        try {
            lifecycle.start();
            assertTrue(waiterProbe.awaitStarted());
            Thread waiter = waiterProbe.thread();

            lifecycle.stop();

            waiter.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(waiter.isAlive());
            assertFalse(lifecycle.isRunning());
            verify(server).shutdown();
            verify(server).shutdownNow();
            verify(server, times(2))
                    .awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            waiterProbe.release();
            lifecycle.stop();
        }
    }

    @Test
    void interruptedShutdownForcesTerminationAndRestoresInterruptStatus() throws Exception {
        Server server = mock(Server.class);
        when(server.start()).thenReturn(server);
        WaiterProbe waiterProbe = blockTerminationWaiter(server);
        when(server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException("synthetic interruption"))
                .thenReturn(true);
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        try {
            lifecycle.start();
            assertTrue(waiterProbe.awaitStarted());

            lifecycle.stop();

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(lifecycle.isRunning());
            verify(server).shutdownNow();
        } finally {
            Thread.interrupted();
            waiterProbe.release();
            lifecycle.stop();
        }
    }

    @Test
    void startupFailurePropagatesWithoutMarkingLifecycleRunning() throws Exception {
        Server server = mock(Server.class);
        IOException startupFailure = new IOException("synthetic bind failure");
        when(server.start()).thenThrow(startupFailure);
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, lifecycle::start);

        assertTrue(exception.getCause() == startupFailure);
        assertFalse(lifecycle.isRunning());
        verify(server, never()).awaitTermination();
        verify(server, never()).shutdown();
        verify(server, never()).shutdownNow();
    }

    @Test
    void unexpectedServerTerminationClearsRunningState() throws Exception {
        Server server = mock(Server.class);
        when(server.start()).thenReturn(server);
        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        CountDownLatch waiterFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
                    waiterThread.set(Thread.currentThread());
                    waiterFinished.countDown();
                    return null;
                })
                .when(server)
                .awaitTermination();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(server);

        lifecycle.start();

        assertTrue(waiterFinished.await(1, TimeUnit.SECONDS));
        waiterThread.get().join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(lifecycle.isRunning());
    }

    private static WaiterProbe blockTerminationWaiter(Server server) throws InterruptedException {
        WaiterProbe probe = new WaiterProbe();
        doAnswer(invocation -> {
                    probe.recordCurrentThread();
                    probe.awaitRelease();
                    return null;
                })
                .when(server)
                .awaitTermination();
        return probe;
    }

    private static final class WaiterProbe {

        private final AtomicReference<Thread> thread = new AtomicReference<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void recordCurrentThread() {
            thread.set(Thread.currentThread());
            started.countDown();
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        private void awaitRelease() throws InterruptedException {
            release.await();
        }

        private void release() {
            release.countDown();
        }

        private Thread thread() {
            return thread.get();
        }
    }
}
