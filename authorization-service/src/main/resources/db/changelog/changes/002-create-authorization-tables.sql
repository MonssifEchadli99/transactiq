--liquibase formatted sql

--changeset transactiq:cycle3-002-create-card-accounts
CREATE TABLE "authorization".card_accounts (
    account_id UUID NOT NULL,
    card_token VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    posted_balance NUMERIC(14,2) NOT NULL,
    reserved_amount NUMERIC(14,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_card_accounts PRIMARY KEY (account_id),
    CONSTRAINT uk_card_accounts_card_token UNIQUE (card_token),
    CONSTRAINT ck_card_accounts_posted_balance_non_negative CHECK (posted_balance >= 0),
    CONSTRAINT ck_card_accounts_reserved_amount_non_negative CHECK (reserved_amount >= 0),
    CONSTRAINT ck_card_accounts_reserved_amount_within_balance CHECK (reserved_amount <= posted_balance)
);

--rollback DROP TABLE "authorization".card_accounts;

--changeset transactiq:cycle3-003-create-authorization-requests
CREATE TABLE "authorization".authorization_requests (
    request_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_authorization_requests PRIMARY KEY (request_id),
    CONSTRAINT ck_authorization_requests_status CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT ck_authorization_requests_completion CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL)
    )
);

--rollback DROP TABLE "authorization".authorization_requests;

--changeset transactiq:cycle3-004-create-authorization-ledger
CREATE TABLE "authorization".authorization_ledger (
    request_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL,
    decline_reason VARCHAR(64),
    fraud_assessment VARCHAR(32) NOT NULL,
    non_fraud_check_result VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_authorization_ledger PRIMARY KEY (request_id),
    CONSTRAINT fk_authorization_ledger_request FOREIGN KEY (request_id)
        REFERENCES "authorization".authorization_requests (request_id),
    CONSTRAINT ck_authorization_ledger_decision CHECK (decision IN ('APPROVED', 'DECLINED')),
    CONSTRAINT ck_authorization_ledger_decline_reason CHECK (
        (decision = 'APPROVED' AND decline_reason IS NULL)
        OR (decision = 'DECLINED' AND decline_reason IS NOT NULL)
    )
);

--rollback DROP TABLE "authorization".authorization_ledger;

--changeset transactiq:cycle3-005-create-balance-reservations
CREATE TABLE "authorization".balance_reservations (
    reservation_id UUID NOT NULL,
    request_id UUID NOT NULL,
    account_id UUID NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_balance_reservations PRIMARY KEY (reservation_id),
    CONSTRAINT uk_balance_reservations_request UNIQUE (request_id),
    CONSTRAINT fk_balance_reservations_request FOREIGN KEY (request_id)
        REFERENCES "authorization".authorization_ledger (request_id),
    CONSTRAINT fk_balance_reservations_account FOREIGN KEY (account_id)
        REFERENCES "authorization".card_accounts (account_id),
    CONSTRAINT ck_balance_reservations_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_balance_reservations_status_active CHECK (status = 'ACTIVE')
);

--rollback DROP TABLE "authorization".balance_reservations;
