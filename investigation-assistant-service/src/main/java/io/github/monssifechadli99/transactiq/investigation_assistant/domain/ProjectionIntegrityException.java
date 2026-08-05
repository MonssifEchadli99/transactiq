package io.github.monssifechadli99.transactiq.investigation_assistant.domain;

/** A permanent failure: one case version was published with two different snapshots. */
public final class ProjectionIntegrityException extends RuntimeException {
    public ProjectionIntegrityException(String message) {
        super(message);
    }
}
