-- LOCAL DEVELOPMENT ONLY: creates the least-privilege application role expected by
-- V1__tenancy_and_ledger.sql. In real environments this is done by infrastructure
-- bootstrap with a password from the secret store.
CREATE ROLE mfi_app LOGIN PASSWORD 'dev-only-change-me' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE EXTENSION IF NOT EXISTS btree_gist;
