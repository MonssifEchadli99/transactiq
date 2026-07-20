--liquibase formatted sql

--changeset transactiq:cycle3-001-create-authorization-schema
CREATE SCHEMA "authorization";

--rollback DROP SCHEMA "authorization";
