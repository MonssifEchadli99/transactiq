--liquibase formatted sql

--changeset transactiq:cycle5-006-reject-incomplete-resolved-cases splitStatements:false
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM fraud_case.fraud_cases WHERE status = 'RESOLVED'
    ) THEN
        RAISE EXCEPTION 'Cannot add resolution metadata: existing RESOLVED fraud cases require review';
    END IF;
END $$;

--changeset transactiq:cycle5-007-add-fraud-case-resolution

ALTER TABLE fraud_case.fraud_cases
    ADD COLUMN resolution_outcome VARCHAR(32),
    ADD COLUMN resolution_rationale TEXT,
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD COLUMN resolved_by VARCHAR(128),
    ADD CONSTRAINT ck_fraud_cases_resolution_outcome CHECK (
        resolution_outcome IS NULL
        OR resolution_outcome IN ('CONFIRMED_FRAUD', 'FALSE_POSITIVE')
    ),
    ADD CONSTRAINT ck_fraud_cases_resolution_state CHECK (
        (status = 'RESOLVED'
            AND resolution_outcome IS NOT NULL
            AND resolution_rationale IS NOT NULL
            AND resolved_at IS NOT NULL
            AND resolved_by IS NOT NULL)
        OR (status <> 'RESOLVED'
            AND resolution_outcome IS NULL
            AND resolution_rationale IS NULL
            AND resolved_at IS NULL
            AND resolved_by IS NULL)
    );

ALTER TABLE fraud_case.fraud_case_lifecycle_events
    DROP CONSTRAINT ck_fraud_case_lifecycle_events_type,
    DROP CONSTRAINT ck_fraud_case_lifecycle_events_previous_status,
    DROP CONSTRAINT ck_fraud_case_lifecycle_events_resulting_status,
    ADD COLUMN resolution_outcome VARCHAR(32),
    ADD COLUMN resolution_rationale TEXT,
    ADD CONSTRAINT ck_fraud_case_lifecycle_events_type
        CHECK (event_type IN ('CLAIMED', 'RESOLVED')),
    ADD CONSTRAINT ck_fraud_case_lifecycle_events_transition CHECK (
        (event_type = 'CLAIMED'
            AND previous_status = 'NEW'
            AND resulting_status = 'IN_REVIEW'
            AND resolution_outcome IS NULL
            AND resolution_rationale IS NULL)
        OR (event_type = 'RESOLVED'
            AND previous_status = 'IN_REVIEW'
            AND resulting_status = 'RESOLVED'
            AND resolution_outcome IN ('CONFIRMED_FRAUD', 'FALSE_POSITIVE')
            AND resolution_rationale IS NOT NULL
            AND previous_assignee_id = resulting_assignee_id
            AND actor_id = resulting_assignee_id)
    );

--rollback ALTER TABLE fraud_case.fraud_case_lifecycle_events DROP CONSTRAINT ck_fraud_case_lifecycle_events_transition;
--rollback ALTER TABLE fraud_case.fraud_case_lifecycle_events DROP CONSTRAINT ck_fraud_case_lifecycle_events_type;
--rollback ALTER TABLE fraud_case.fraud_case_lifecycle_events DROP COLUMN resolution_rationale, DROP COLUMN resolution_outcome;
--rollback ALTER TABLE fraud_case.fraud_cases DROP CONSTRAINT ck_fraud_cases_resolution_state;
--rollback ALTER TABLE fraud_case.fraud_cases DROP CONSTRAINT ck_fraud_cases_resolution_outcome;
--rollback ALTER TABLE fraud_case.fraud_cases DROP COLUMN resolved_by, DROP COLUMN resolved_at, DROP COLUMN resolution_rationale, DROP COLUMN resolution_outcome;
