package io.github.monssifechadli99.transactiq.case_management.adapter.in.kafka;

import io.github.monssifechadli99.transactiq.case_management.configuration.FraudCaseConsumerProperties;
import io.github.monssifechadli99.transactiq.case_management.domain.AuthorizationEventConflictException;
import io.github.monssifechadli99.transactiq.case_management.domain.InvalidAuthorizationEventException;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

public final class KafkaFailurePolicy {

    public BackOff backOff(Exception exception, FraudCaseConsumerProperties properties) {
        if (recoveryCategory(exception) != RecoveryCategory.UNEXPECTED_PROCESSING_FAILURE) {
            return new FixedBackOff(0L, 0L);
        }
        if (isTemporaryResourceFailure(exception)) {
            return exponential(properties, Long.MAX_VALUE);
        }
        return exponential(properties, properties.unexpectedTotalAttempts() - 1L);
    }

    public boolean isTemporaryResourceFailure(Throwable failure) {
        return findCause(failure, TransientDataAccessException.class) != null
                || findCause(failure, DataAccessResourceFailureException.class) != null;
    }

    public RecoveryCategory recoveryCategory(Throwable failure) {
        if (findCause(failure, InvalidAuthorizationEventException.class) != null) {
            return RecoveryCategory.INVALID_EVENT;
        }
        if (findCause(failure, AuthorizationEventConflictException.class) != null) {
            return RecoveryCategory.CONTRACT_CONFLICT;
        }
        return RecoveryCategory.UNEXPECTED_PROCESSING_FAILURE;
    }

    public Throwable diagnosticException(Throwable failure) {
        for (Class<? extends Throwable> type : List.of(
            InvalidAuthorizationEventException.class,
            AuthorizationEventConflictException.class,
            TransientDataAccessException.class,
            DataAccessResourceFailureException.class
        )) {
            Throwable found = findCause(failure, type);
            if (found != null) {
                return found;
            }
        }
        return failure;
    }

    private static ExponentialBackOff exponential(
            FraudCaseConsumerProperties properties, long maximumAttempts) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                properties.retryInitialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(properties.retryMaximumInterval().toMillis());
        backOff.setMaxAttempts(maximumAttempts);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return backOff;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    public enum RecoveryCategory {
        INVALID_EVENT,
        CONTRACT_CONFLICT,
        UNEXPECTED_PROCESSING_FAILURE
    }
}
