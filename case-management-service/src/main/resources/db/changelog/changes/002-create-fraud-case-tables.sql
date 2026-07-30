--liquibase formatted sql

--changeset transactiq:cycle5-002-create-fraud-cases
CREATE TABLE fraud_case.fraud_cases (
    case_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    source_event_hash CHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    assignee_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    occurred_at_nanos INTEGER NOT NULL,
    card_token_fingerprint CHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    merchant_category_code CHAR(4) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    country CHAR(2) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    transaction_time TIMESTAMPTZ NOT NULL,
    transaction_time_nanos INTEGER NOT NULL,
    non_fraud_result VARCHAR(32) NOT NULL,
    authorization_decision VARCHAR(16) NOT NULL,
    decline_reason VARCHAR(64),
    fraud_assessment VARCHAR(16) NOT NULL,
    risk_score INTEGER NOT NULL,
    case_required BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_fraud_cases PRIMARY KEY (case_id),
    CONSTRAINT uk_fraud_cases_source_event UNIQUE (source_event_id),
    CONSTRAINT uk_fraud_cases_request UNIQUE (request_id),
    CONSTRAINT ck_fraud_cases_source_hash
        CHECK (source_event_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fraud_cases_status CHECK (status IN ('NEW', 'IN_REVIEW', 'RESOLVED')),
    CONSTRAINT ck_fraud_cases_card_fingerprint
        CHECK (card_token_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_fraud_cases_mcc CHECK (merchant_category_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_fraud_cases_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_fraud_cases_occurred_at_nanos
        CHECK (occurred_at_nanos BETWEEN 0 AND 999999999),
    CONSTRAINT ck_fraud_cases_transaction_time_nanos
        CHECK (transaction_time_nanos BETWEEN 0 AND 999999999),
    CONSTRAINT ck_fraud_cases_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fraud_cases_country CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_fraud_cases_channel
        CHECK (channel IN ('ECOMMERCE', 'POINT_OF_SALE')),
    CONSTRAINT ck_fraud_cases_non_fraud_result
        CHECK (non_fraud_result IN ('PASSED', 'INSUFFICIENT_FUNDS')),
    CONSTRAINT ck_fraud_cases_decision
        CHECK (authorization_decision IN ('APPROVED', 'DECLINED')),
    CONSTRAINT ck_fraud_cases_decline_reason CHECK (
        decline_reason IS NULL
        OR decline_reason IN (
            'INSUFFICIENT_FUNDS',
            'HIGH_FRAUD_RISK',
            'FRAUD_REVIEW_REQUIRED'
        )
    ),
    CONSTRAINT ck_fraud_cases_decision_reason CHECK (
        (authorization_decision = 'APPROVED' AND decline_reason IS NULL)
        OR (authorization_decision = 'DECLINED' AND decline_reason IS NOT NULL)
    ),
    CONSTRAINT ck_fraud_cases_assessment
        CHECK (fraud_assessment IN ('REVIEW', 'HIGH_RISK')),
    CONSTRAINT ck_fraud_cases_risk_score CHECK (risk_score BETWEEN 1 AND 100),
    CONSTRAINT ck_fraud_cases_required CHECK (case_required)
);

CREATE INDEX ix_fraud_cases_created_at
    ON fraud_case.fraud_cases (created_at, case_id);

--rollback DROP TABLE fraud_case.fraud_cases;

--changeset transactiq:cycle5-003-create-fraud-case-rule-matches
CREATE TABLE fraud_case.fraud_case_rule_matches (
    case_id UUID NOT NULL,
    match_order INTEGER NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    evidence TEXT NOT NULL,
    score_contribution INTEGER NOT NULL,
    CONSTRAINT pk_fraud_case_rule_matches PRIMARY KEY (case_id, match_order),
    CONSTRAINT fk_fraud_case_rule_matches_case FOREIGN KEY (case_id)
        REFERENCES fraud_case.fraud_cases (case_id) ON DELETE CASCADE,
    CONSTRAINT ck_fraud_case_rule_matches_order CHECK (match_order >= 0),
    CONSTRAINT ck_fraud_case_rule_matches_code CHECK (LENGTH(TRIM(rule_code)) > 0),
    CONSTRAINT ck_fraud_case_rule_matches_severity
        CHECK (severity IN ('REVIEW', 'HIGH_RISK')),
    CONSTRAINT ck_fraud_case_rule_matches_evidence CHECK (LENGTH(TRIM(evidence)) > 0),
    CONSTRAINT ck_fraud_case_rule_matches_contribution
        CHECK (score_contribution BETWEEN 1 AND 100)
);

--rollback DROP TABLE fraud_case.fraud_case_rule_matches;
