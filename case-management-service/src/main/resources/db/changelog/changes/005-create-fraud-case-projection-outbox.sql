--liquibase formatted sql

--changeset transactiq:cycle5-008-create-fraud-case-projection-outbox
CREATE TABLE fraud_case.fraud_case_projection_outbox (
    event_id UUID PRIMARY KEY,
    fraud_case_id UUID NOT NULL REFERENCES fraud_case.fraud_cases(case_id),
    aggregate_version BIGINT NOT NULL CHECK (aggregate_version >= 0),
    topic_partition INTEGER NOT NULL DEFAULT 0 CHECK (topic_partition >= 0),
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('CREATED','CLAIMED','RESOLVED')),
    snapshot_hash CHAR(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    payload BYTEA NOT NULL CHECK (octet_length(payload) > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    publication_state VARCHAR(16) NOT NULL CHECK (publication_state IN ('PENDING','IN_FLIGHT','PUBLISHED')),
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    CONSTRAINT uk_fraud_case_projection_version UNIQUE (fraud_case_id, aggregate_version),
    CONSTRAINT ck_fraud_case_projection_delivery_state CHECK (
      (publication_state='PENDING' AND published_at IS NULL AND lease_token IS NULL AND lease_until IS NULL) OR
      (publication_state='IN_FLIGHT' AND published_at IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR
      (publication_state='PUBLISHED' AND published_at IS NOT NULL AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX ix_fraud_case_projection_outbox_due
ON fraud_case.fraud_case_projection_outbox (topic_partition, publication_state, next_attempt_at, lease_until, created_at);

CREATE TABLE fraud_case.projection_partition_ownership (
    topic VARCHAR(249) NOT NULL,
    partition_number INTEGER NOT NULL CHECK (partition_number >= 0),
    owner_token UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    lease_until TIMESTAMPTZ NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    renewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (topic, partition_number)
);

--changeset transactiq:cycle5-009-protect-projection-outbox-payload splitStatements:false
CREATE FUNCTION fraud_case.reject_projection_outbox_identity_update() RETURNS trigger AS $$
BEGIN
  IF NEW.event_id <> OLD.event_id OR NEW.fraud_case_id <> OLD.fraud_case_id
     OR NEW.aggregate_version <> OLD.aggregate_version OR NEW.topic_partition <> OLD.topic_partition
     OR NEW.event_type <> OLD.event_type
     OR NEW.snapshot_hash <> OLD.snapshot_hash OR NEW.payload <> OLD.payload
     OR NEW.occurred_at <> OLD.occurred_at OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'Fraud Case projection outbox identity and payload are immutable';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

--changeset transactiq:cycle5-010-install-projection-outbox-immutability-trigger
CREATE TRIGGER fraud_case_projection_outbox_immutable
BEFORE UPDATE ON fraud_case.fraud_case_projection_outbox
FOR EACH ROW EXECUTE FUNCTION fraud_case.reject_projection_outbox_identity_update();

--rollback DROP TRIGGER fraud_case_projection_outbox_immutable ON fraud_case.fraud_case_projection_outbox;
--rollback DROP FUNCTION fraud_case.reject_projection_outbox_identity_update();
--rollback DROP TABLE fraud_case.projection_partition_ownership;
--rollback DROP TABLE fraud_case.fraud_case_projection_outbox;
