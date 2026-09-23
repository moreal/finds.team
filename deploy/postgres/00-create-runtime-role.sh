#!/bin/sh
# For both initdb and existing volumes. Run as the database administrator; never reset PGDATA.
set -eu
set +x
: "${FINDS_MIGRATION_DB_PASSWORD:?Set FINDS_MIGRATION_DB_PASSWORD}"
: "${FINDS_DB_PASSWORD:?Set FINDS_DB_PASSWORD}"
: "${POSTGRES_USER:?Set POSTGRES_USER}"
: "${POSTGRES_DB:?Set POSTGRES_DB}"

# Passwords enter psql from its environment, not argv or interpolated shell SQL. Disable SQL
# logging for this administrator session before handling credentials; do not enable psql echo.
psql -X --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
SET log_min_duration_statement = -1;
SET log_min_duration_sample = -1;
SET log_transaction_sample_rate = 0;
\getenv migration_password FINDS_MIGRATION_DB_PASSWORD
\getenv runtime_password FINDS_DB_PASSWORD
BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_migrator') THEN
    CREATE ROLE finds_migrator;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN
    CREATE ROLE finds_app;
  END IF;
  IF EXISTS (SELECT FROM pg_auth_members WHERE member IN (
    SELECT oid FROM pg_roles WHERE rolname IN ('finds_app', 'finds_migrator')
  )) THEN
    RAISE EXCEPTION 'Application roles must not inherit other roles';
  END IF;
END;
$$;
ALTER ROLE finds_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'migration_password';
ALTER ROLE finds_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  PASSWORD :'runtime_password';
ALTER SCHEMA public OWNER TO finds_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC, finds_app;
GRANT USAGE ON SCHEMA public TO finds_app;

-- This database's public schema belongs to finds.team. Transfer existing V1/V2 and Flyway
-- objects without touching their data, checksums, or migration versions. Flyway alone adds V3.
DO $$
DECLARE item RECORD;
BEGIN
  FOR item IN SELECT c.oid::regclass AS name FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') LOOP
    EXECUTE format('ALTER TABLE %s OWNER TO finds_migrator', item.name);
  END LOOP;
  FOR item IN SELECT c.oid::regclass AS name FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'S' LOOP
    EXECUTE format('ALTER SEQUENCE %s OWNER TO finds_migrator', item.name);
  END LOOP;
  FOR item IN SELECT p.oid::regprocedure AS name FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public'
    AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass
      AND d.objid = p.oid AND d.deptype = 'e') LOOP
    EXECUTE format('ALTER FUNCTION %s OWNER TO finds_migrator', item.name);
  END LOOP;
END;
$$;
COMMIT;
SQL
