--liquibase formatted sql

--changeset transactiq:cycle5-004-add-fraud-case-lifecycle-columns
ALTER TABLE fraud_case.fraud_cases
    ADD COLUMN version BIGINT,
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE fraud_case.fraud_cases
SET version = 0,
    updated_at = created_at;

ALTER TABLE fraud_case.fraud_cases
    ALTER COLUMN version SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT ck_fraud_cases_version_nonnegative CHECK (version >= 0);

CREATE INDEX ix_fraud_cases_status_queue
    ON fraud_case.fraud_cases (status, created_at, case_id);

CREATE INDEX ix_fraud_cases_assignee_queue
    ON fraud_case.fraud_cases (assignee_id, created_at, case_id);

--rollback DROP INDEX fraud_case.ix_fraud_cases_assignee_queue;
--rollback DROP INDEX fraud_case.ix_fraud_cases_status_queue;
--rollback ALTER TABLE fraud_case.fraud_cases DROP CONSTRAINT ck_fraud_cases_version_nonnegative;
--rollback ALTER TABLE fraud_case.fraud_cases DROP COLUMN updated_at, DROP COLUMN version;

--changeset transactiq:cycle5-005-create-fraud-case-lifecycle-events
CREATE TABLE fraud_case.fraud_case_lifecycle_events (
    lifecycle_event_id UUID NOT NULL,
    fraud_case_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    previous_status VARCHAR(16) NOT NULL,
    resulting_status VARCHAR(16) NOT NULL,
    previous_assignee_id VARCHAR(128),
    resulting_assignee_id VARCHAR(128) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    case_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_fraud_case_lifecycle_events PRIMARY KEY (lifecycle_event_id),
    CONSTRAINT fk_fraud_case_lifecycle_events_case FOREIGN KEY (fraud_case_id)
        REFERENCES fraud_case.fraud_cases (case_id),
    CONSTRAINT uk_fraud_case_lifecycle_events_version UNIQUE (fraud_case_id, case_version),
    CONSTRAINT ck_fraud_case_lifecycle_events_type CHECK (event_type = 'CLAIMED'),
    CONSTRAINT ck_fraud_case_lifecycle_events_previous_status CHECK (previous_status = 'NEW'),
    CONSTRAINT ck_fraud_case_lifecycle_events_resulting_status CHECK (resulting_status = 'IN_REVIEW'),
    CONSTRAINT ck_fraud_case_lifecycle_events_version CHECK (case_version > 0),
    CONSTRAINT ck_fraud_case_lifecycle_events_resulting_assignee
        CHECK (LENGTH(TRIM(resulting_assignee_id)) > 0),
    CONSTRAINT ck_fraud_case_lifecycle_events_actor CHECK (LENGTH(TRIM(actor_id)) > 0)
);

CREATE INDEX ix_fraud_case_lifecycle_events_case_time
    ON fraud_case.fraud_case_lifecycle_events (fraud_case_id, occurred_at, lifecycle_event_id);

--rollback DROP TABLE fraud_case.fraud_case_lifecycle_events;
