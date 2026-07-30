--liquibase formatted sql

--changeset transactiq:cycle4-003-create-authorization-outbox
CREATE TABLE "authorization".authorization_outbox (
    event_id UUID NOT NULL,
    request_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL,
    partition_key VARCHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    publication_state VARCHAR(16) NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    CONSTRAINT pk_authorization_outbox PRIMARY KEY (event_id),
    CONSTRAINT uk_authorization_outbox_request_event_type
        UNIQUE (request_id, event_type),
    CONSTRAINT fk_authorization_outbox_ledger FOREIGN KEY (request_id)
        REFERENCES "authorization".authorization_ledger (request_id) ON DELETE CASCADE,
    CONSTRAINT ck_authorization_outbox_event_type_non_blank
        CHECK (LENGTH(TRIM(event_type)) > 0),
    CONSTRAINT ck_authorization_outbox_event_version_positive
        CHECK (event_version > 0),
    CONSTRAINT ck_authorization_outbox_partition_key_sha256
        CHECK (partition_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_authorization_outbox_payload_non_empty
        CHECK (OCTET_LENGTH(payload) > 0),
    CONSTRAINT ck_authorization_outbox_attempt_count_non_negative
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_authorization_outbox_last_error_code_non_blank
        CHECK (last_error_code IS NULL OR LENGTH(TRIM(last_error_code)) > 0),
    CONSTRAINT ck_authorization_outbox_publication_state
        CHECK (publication_state IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    CONSTRAINT ck_authorization_outbox_state_metadata CHECK (
        (publication_state = 'PENDING'
            AND published_at IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL)
        OR
        (publication_state = 'IN_FLIGHT'
            AND published_at IS NULL
            AND lease_token IS NOT NULL
            AND lease_until IS NOT NULL)
        OR
        (publication_state = 'PUBLISHED'
            AND published_at IS NOT NULL
            AND lease_token IS NULL
            AND lease_until IS NULL)
    )
);

CREATE INDEX ix_authorization_outbox_due
    ON "authorization".authorization_outbox (
        publication_state,
        next_attempt_at,
        lease_until,
        created_at
    );

--rollback DROP TABLE "authorization".authorization_outbox;
