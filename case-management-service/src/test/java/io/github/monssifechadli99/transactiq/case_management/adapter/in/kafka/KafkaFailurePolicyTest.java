package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseConsumerProperties;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventConflictException;
import io.github.monssifechadli99.transactiq.case_management.domain.InvalidAuthorizationEventException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

class KafkaFailurePolicyTest {

    private final KafkaFailurePolicy policy = new KafkaFailurePolicy();
    private final FraudCaseConsumerProperties properties = new FraudCaseConsumerProperties(
            "source", "group", Duration.ofSeconds(1), Duration.ofSeconds(30), 5,
            Duration.ofSeconds(1), "dlt", 1);

    @Test
    void permanentFailuresGoDirectlyToRecovery() {
        assertEquals(BackOffExecution.STOP,
                policy.backOff(new InvalidAuthorizationEventException("invalid"), properties)
                        .start().nextBackOff());
        assertEquals(BackOffExecution.STOP,
                policy.backOff(new AuthorizationEventConflictException("conflict"), properties)
                        .start().nextBackOff());
    }

    @Test
    void temporaryResourceFailureRetriesIndefinitelyWithCappedExponentialIntervals() {
        BackOffExecution execution = policy.backOff(
                new DataAccessResourceFailureException("unavailable"), properties).start();

        assertArrayEquals(new long[] {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000},
                next(execution, 7));
        assertNotEquals(BackOffExecution.STOP, execution.nextBackOff());
    }

    @Test
    void unexpectedAndNonTransientDatabaseFailuresHaveFiveTotalAttempts() {
        assertArrayEquals(new long[] {1_000, 2_000, 4_000, 8_000, BackOffExecution.STOP},
                next(policy.backOff(new IllegalStateException("unexpected"), properties).start(), 5));
        assertArrayEquals(new long[] {1_000, 2_000, 4_000, 8_000, BackOffExecution.STOP},
                next(policy.backOff(new BadSqlGrammarException("task", "sql", null), properties)
                        .start(), 5));
    }

    private static long[] next(BackOffExecution execution, int count) {
        long[] values = new long[count];
        for (int index = 0; index < count; index++) {
            values[index] = execution.nextBackOff();
        }
        return values;
    }
}
