--liquibase formatted sql

--changeset transactiq:cycle4-002-add-fraud-risk-score
ALTER TABLE "authorization".authorization_ledger
    ADD COLUMN risk_score INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_authorization_ledger_risk_score
        CHECK (risk_score BETWEEN 0 AND 100);

ALTER TABLE "authorization".authorization_ledger
    ALTER COLUMN risk_score DROP DEFAULT;

ALTER TABLE "authorization".fraud_rule_matches
    ADD COLUMN score_contribution INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_fraud_rule_matches_score_contribution
        CHECK (score_contribution BETWEEN 1 AND 100);

ALTER TABLE "authorization".fraud_rule_matches
    ALTER COLUMN score_contribution DROP DEFAULT;

--rollback ALTER TABLE "authorization".fraud_rule_matches DROP COLUMN score_contribution;
--rollback ALTER TABLE "authorization".authorization_ledger DROP COLUMN risk_score;
