-- TEST ONLY: container bootstrap equivalent to infrastructure bootstrap in real environments.
CREATE ROLE mfi_app LOGIN PASSWORD 'it-only-not-a-secret' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE ROLE mfi_relay LOGIN PASSWORD 'it-only-relay' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
