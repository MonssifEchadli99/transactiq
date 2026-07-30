--liquibase formatted sql

--changeset transactiq:cycle4-001-create-fraud-rule-matches
CREATE TABLE "authorization".fraud_rule_matches (
    request_id UUID NOT NULL,
    match_order INTEGER NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    evidence TEXT NOT NULL,
    CONSTRAINT pk_fraud_rule_matches PRIMARY KEY (request_id, match_order),
    CONSTRAINT fk_fraud_rule_matches_ledger FOREIGN KEY (request_id)
        REFERENCES "authorization".authorization_ledger (request_id) ON DELETE CASCADE,
    CONSTRAINT ck_fraud_rule_matches_order_non_negative CHECK (match_order >= 0),
    CONSTRAINT ck_fraud_rule_matches_rule_code_non_blank CHECK (LENGTH(TRIM(rule_code)) > 0),
    CONSTRAINT ck_fraud_rule_matches_severity CHECK (severity IN ('REVIEW', 'HIGH_RISK')),
    CONSTRAINT ck_fraud_rule_matches_evidence_non_blank CHECK (LENGTH(TRIM(evidence)) > 0)
);

--rollback DROP TABLE "authorization".fraud_rule_matches;
