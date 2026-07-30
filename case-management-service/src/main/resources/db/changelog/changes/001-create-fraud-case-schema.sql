--liquibase formatted sql

--changeset transactiq:cycle5-001-create-fraud-case-schema
CREATE SCHEMA fraud_case;

--rollback DROP SCHEMA fraud_case;
