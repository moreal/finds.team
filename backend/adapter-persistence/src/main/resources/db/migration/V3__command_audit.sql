-- Stored results are a versioned semantic envelope, never a serialized HTTP response.
CREATE FUNCTION valid_command_result(result JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
  resource RECORD;
BEGIN
  IF jsonb_typeof(result) <> 'object'
    OR NOT result ?& ARRAY['version', 'kind', 'outcome', 'resourceIds']
    OR result - ARRAY['version', 'kind', 'outcome', 'resourceIds'] <> '{}'::jsonb
    OR jsonb_typeof(result->'version') <> 'number'
    OR (result->>'version') !~ '^[1-9][0-9]*$'
    OR (result->>'version')::numeric > 2147483647
    OR jsonb_typeof(result->'kind') <> 'string'
    OR (result->>'kind') !~ '^[a-z][a-z0-9_.]{0,95}$'
    OR jsonb_typeof(result->'outcome') <> 'string'
    OR (result->>'outcome') !~ '^[A-Z][A-Z0-9_]{0,63}$'
    OR jsonb_typeof(result->'resourceIds') <> 'object' THEN
    RETURN FALSE;
  END IF;
  FOR resource IN SELECT * FROM jsonb_each(result->'resourceIds') LOOP
    IF resource.key !~ '^[a-z][a-z0-9_]{0,63}$'
      OR resource.key ~ '(^|_)(otp|token|cookie|credential|session|challenge|password|email|secret|csrf|recovery_code)(_|$)'
      OR jsonb_typeof(resource.value) <> 'object'
      OR NOT resource.value ?& ARRAY['type', 'value']
      OR resource.value - ARRAY['type', 'value'] <> '{}'::jsonb THEN
      RETURN FALSE;
    END IF;
    IF resource.value->>'type' = 'number' THEN
      IF jsonb_typeof(resource.value->'value') <> 'number'
        OR resource.value->>'value' !~ '^[1-9][0-9]*$'
        OR (resource.value->>'value')::numeric > 9223372036854775807 THEN
        RETURN FALSE;
      END IF;
    ELSIF resource.value->>'type' = 'uuid' THEN
      IF jsonb_typeof(resource.value->'value') <> 'string'
        OR resource.value->>'value' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RETURN FALSE;
      END IF;
    ELSE
      RETURN FALSE;
    END IF;
  END LOOP;
  RETURN TRUE;
END;
$$;

CREATE TABLE command_requests (
  scope TEXT NOT NULL CHECK (scope ~ '^[A-Za-z0-9:_-]{1,160}$'),
  operation TEXT NOT NULL CHECK (operation ~ '^[a-z][a-z0-9_.]{0,95}$'),
  idempotency_key UUID NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL,
  retention TEXT NOT NULL DEFAULT 'ORDINARY',
  expires_at TIMESTAMPTZ,
  result JSONB CHECK (valid_command_result(result)),
  completed_at TIMESTAMPTZ,
  PRIMARY KEY (scope, operation, idempotency_key),
  CONSTRAINT ck_command_retention CHECK (
    (retention = 'ORDINARY' AND expires_at IS NOT NULL AND expires_at = created_at + INTERVAL '24 hours') OR
    (retention = 'AUDIT' AND expires_at IS NULL)
  ),
  CONSTRAINT ck_command_completion CHECK (
    (result IS NULL AND completed_at IS NULL) OR
    (result IS NOT NULL AND completed_at IS NOT NULL AND completed_at >= created_at)
  )
);
CREATE INDEX ix_command_requests_expiry ON command_requests (expires_at)
  WHERE retention = 'ORDINARY';

CREATE TABLE audit_events (
  event_id UUID PRIMARY KEY,
  schema_version INTEGER NOT NULL CHECK (schema_version > 0),
  occurred_at TIMESTAMPTZ NOT NULL,
  actor_kind TEXT NOT NULL,
  actor_user_id UUID,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type ~ '^[a-z][a-z0-9_]*$'),
  target_id TEXT NOT NULL CHECK (target_id ~ '^[A-Za-z0-9:_-]{1,128}$'),
  request_id UUID NOT NULL,
  correlation_id UUID NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome = 'succeeded'),
  details JSONB NOT NULL DEFAULT '{}',
  CONSTRAINT ck_audit_actor CHECK (
    (actor_kind = 'USER' AND actor_user_id IS NOT NULL) OR
    (actor_kind = 'SYSTEM' AND actor_user_id IS NULL)
  ),
  CONSTRAINT ck_audit_details CHECK (
    jsonb_typeof(details) = 'object' AND CASE
      WHEN action IN ('role.granted', 'role.revoked') THEN
        details - 'role' = '{}'::jsonb AND
        (NOT details ? 'role' OR details->'role' IN ('"USER"'::jsonb, '"ADMIN"'::jsonb))
      WHEN action = 'career_site.registered' THEN
        details - 'provider' = '{}'::jsonb AND
        (NOT details ? 'provider' OR details->'provider' IN ('"FLEX"'::jsonb, '"GREETING"'::jsonb, '"NINEHIRE"'::jsonb))
      WHEN action = 'career_site.settings_changed' THEN
        details - 'enabled' = '{}'::jsonb AND
        (NOT details ? 'enabled' OR details->'enabled' IN ('"true"'::jsonb, '"false"'::jsonb))
      WHEN action IN ('crawl.manually_triggered', 'passkey.registered', 'passkey.removed',
        'recovery_code.rotated', 'recovery.completed', 'session.revoked', 'admin_configuration.changed') THEN
        details = '{}'::jsonb
      ELSE FALSE
    END
  )
);

CREATE TABLE security_events (
  event_id UUID PRIMARY KEY,
  schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
  occurred_at TIMESTAMPTZ NOT NULL,
  actor_user_id UUID,
  action TEXT NOT NULL CHECK (action ~ '^[a-z][a-z0-9_.]{0,95}$'),
  target_type TEXT CHECK (target_type ~ '^[a-z][a-z0-9_]*$'),
  target_id TEXT CHECK (target_id ~ '^[A-Za-z0-9:_-]{1,128}$'),
  request_id UUID NOT NULL,
  correlation_id UUID NOT NULL,
  details JSONB NOT NULL DEFAULT '{}',
  CONSTRAINT ck_security_target CHECK ((target_type IS NULL) = (target_id IS NULL)),
  CONSTRAINT ck_security_details CHECK (
    jsonb_typeof(details) = 'object' AND details - 'reason' = '{}'::jsonb AND
    (NOT details ? 'reason' OR details->'reason' IN (
      '"FORBIDDEN"'::jsonb, '"INVALID_OTP"'::jsonb, '"CHALLENGE_REPLAY"'::jsonb,
      '"THROTTLED"'::jsonb, '"AUTHENTICATION_FAILED"'::jsonb
    ))
  )
);

CREATE INDEX ix_audit_time ON audit_events (occurred_at DESC, event_id DESC);
CREATE INDEX ix_audit_action ON audit_events (action, occurred_at DESC, event_id DESC);
CREATE INDEX ix_audit_actor ON audit_events (actor_user_id, occurred_at DESC, event_id DESC);
CREATE INDEX ix_audit_target ON audit_events (target_type, target_id, occurred_at DESC, event_id DESC);
CREATE INDEX ix_security_time ON security_events (occurred_at DESC, event_id DESC);
CREATE INDEX ix_security_action ON security_events (action, occurred_at DESC, event_id DESC);
CREATE INDEX ix_security_actor ON security_events (actor_user_id, occurred_at DESC, event_id DESC);
CREATE INDEX ix_security_target ON security_events (target_type, target_id, occurred_at DESC, event_id DESC);

CREATE FUNCTION reject_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Audit events are append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER audit_events_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_events
  FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();
ALTER TABLE audit_events ENABLE ALWAYS TRIGGER audit_events_immutable;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON audit_events, security_events FROM PUBLIC;
-- Ephemeral code-generation databases deliberately have no runtime login. Deployed databases
-- provision finds_app before Flyway; the application never owns its schema or migrations.
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN
    GRANT USAGE ON SCHEMA public TO finds_app;
    REVOKE ALL ON ALL TABLES IN SCHEMA public FROM finds_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON career_sites, job_postings, posting_skills,
      crawl_runs, crawl_leases, mail_outbox, mail_delivery_attempts, command_requests TO finds_app;
    GRANT SELECT, INSERT ON audit_events, security_events TO finds_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO finds_app;
  END IF;
END;
$$;
