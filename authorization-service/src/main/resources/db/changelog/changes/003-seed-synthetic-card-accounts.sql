--liquibase formatted sql

--changeset transactiq:cycle3-006-seed-synthetic-card-accounts
INSERT INTO "authorization".card_accounts (
    account_id,
    card_token,
    currency,
    posted_balance,
    reserved_amount,
    created_at,
    updated_at
) VALUES
    (
        '00000000-0000-4000-8000-000000000001',
        'tok_A1B2C3D4',
        'EUR',
        1000.00,
        0.00,
        TIMESTAMPTZ '2026-07-19 00:00:00+00',
        TIMESTAMPTZ '2026-07-19 00:00:00+00'
    ),
    (
        '00000000-0000-4000-8000-000000000002',
        'tok_insufficient01',
        'EUR',
        0.00,
        0.00,
        TIMESTAMPTZ '2026-07-19 00:00:00+00',
        TIMESTAMPTZ '2026-07-19 00:00:00+00'
    );

--rollback DELETE FROM "authorization".card_accounts WHERE account_id IN ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002');
