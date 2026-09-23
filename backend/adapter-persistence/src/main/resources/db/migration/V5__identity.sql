-- Additive: existing career, mail, command and audit rows remain intact.
CREATE TABLE users (
  id UUID PRIMARY KEY,
  email TEXT NOT NULL,
  normalized_email TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('PENDING_PASSKEY', 'ACTIVE', 'SUSPENDED')),
  -- Independent of the account ID and email. Public WebAuthn identifier, not a bearer token.
  user_handle BYTEA NOT NULL DEFAULT uuid_send(gen_random_uuid()) UNIQUE CHECK (octet_length(user_handle) = 16),
  CHECK (normalized_email = lower(email COLLATE "C") AND octet_length(email) BETWEEN 3 AND 254
    AND email !~ '[[:space:][:cntrl:]]' AND email LIKE '%@%')
);
CREATE TABLE user_roles (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('USER', 'ADMIN')),
  PRIMARY KEY (user_id, role)
);
CREATE TABLE passkey_credentials (
  credential_id TEXT PRIMARY KEY CHECK (credential_id <> '' AND credential_id !~ '[[:space:][:cntrl:]]'),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  public_key_cose BYTEA NOT NULL CHECK (octet_length(public_key_cose) > 0),
  signature_count BIGINT NOT NULL CHECK (signature_count >= 0),
  transports TEXT[] NOT NULL,
  backup_eligible BOOLEAN NOT NULL,
  backed_up BOOLEAN NOT NULL CHECK (NOT backed_up OR backup_eligible),
  label TEXT NOT NULL CHECK (char_length(label) BETWEEN 1 AND 80 AND label = btrim(label) AND label !~ '[[:cntrl:]]'),
  created_at TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ CHECK (last_used_at >= created_at)
);
CREATE INDEX ix_passkey_user ON passkey_credentials(user_id);

-- Exists before the user; deliberately has no FK to users. One replaceable challenge per purpose,
-- with account failures retained across reissue and expiry. Digests never contain plaintext OTPs.
CREATE TABLE otp_challenges (
  normalized_email TEXT NOT NULL CHECK (normalized_email = lower(normalized_email COLLATE "C") AND normalized_email LIKE '%@%'),
  purpose TEXT NOT NULL CHECK (purpose IN ('ENROLLMENT', 'RECOVERY')),
  consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
  locked_until TIMESTAMPTZ,
  last_issued_at TIMESTAMPTZ,
  delivery_id UUID UNIQUE,
  otp_hash BYTEA CHECK (octet_length(otp_hash) = 32),
  pepper_version INTEGER CHECK (pepper_version > 0),
  expires_at TIMESTAMPTZ,
  consumed_at TIMESTAMPTZ,
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  PRIMARY KEY(normalized_email, purpose),
  CHECK ((delivery_id IS NULL AND otp_hash IS NULL AND pepper_version IS NULL AND expires_at IS NULL AND consumed_at IS NULL)
    OR (delivery_id IS NOT NULL AND otp_hash IS NOT NULL AND pepper_version IS NOT NULL AND expires_at IS NOT NULL)),
  CHECK (consumed_at IS NULL OR consumed_at < expires_at),
  CHECK (last_issued_at IS NULL OR expires_at > last_issued_at)
);
CREATE INDEX ix_otp_expiry ON otp_challenges(expires_at);

CREATE TABLE recovery_codes (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  code_hash BYTEA NOT NULL CHECK (octet_length(code_hash) = 32),
  pepper_version INTEGER NOT NULL CHECK (pepper_version > 0),
  created_at TIMESTAMPTZ NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE TABLE user_sessions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
  authenticated_at TIMESTAMPTZ NOT NULL CHECK (authenticated_at >= created_at AND authenticated_at < expires_at),
  revoked_at TIMESTAMPTZ CHECK (revoked_at >= created_at)
);
CREATE INDEX ix_user_sessions_user ON user_sessions(user_id);
CREATE INDEX ix_user_sessions_expiry ON user_sessions(expires_at);
-- These UUIDs are internal management/binding references, never HTTP bearer cookies. Retain
-- invalidated rows for same-command replay only, at most 24h after ceremony expiry.
CREATE TABLE restricted_sessions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  scope TEXT NOT NULL CHECK (scope IN ('ENROLLMENT', 'RECOVERY', 'ADDITIONAL_PASSKEY')),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
  invalidated_at TIMESTAMPTZ CHECK (invalidated_at >= created_at),
  UNIQUE(id, user_id)
);
CREATE INDEX ix_restricted_sessions_user_scope ON restricted_sessions(user_id, scope);
CREATE INDEX ix_restricted_sessions_expiry ON restricted_sessions(expires_at);

CREATE TABLE webauthn_challenges (
  id UUID PRIMARY KEY,
  purpose TEXT NOT NULL CHECK (purpose IN ('REGISTRATION', 'AUTHENTICATION')),
  rp_id TEXT NOT NULL CHECK (rp_id <> '' AND rp_id !~ '[[:space:][:cntrl:]]'),
  challenge_hash BYTEA NOT NULL CHECK (octet_length(challenge_hash) = 32),
  pepper_version INTEGER NOT NULL CHECK (pepper_version > 0),
  session_binding_hash BYTEA NOT NULL CHECK (octet_length(session_binding_hash) = 32),
  session_pepper_version INTEGER NOT NULL CHECK (session_pepper_version > 0),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  restricted_session_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
  consumed_at TIMESTAMPTZ CHECK (consumed_at >= created_at AND consumed_at < expires_at),
  FOREIGN KEY(restricted_session_id, user_id) REFERENCES restricted_sessions(id, user_id) ON DELETE CASCADE,
  CHECK ((purpose = 'REGISTRATION' AND user_id IS NOT NULL AND restricted_session_id IS NOT NULL)
    OR (purpose = 'AUTHENTICATION' AND restricted_session_id IS NULL))
);
CREATE INDEX ix_webauthn_challenges_expiry ON webauthn_challenges(expires_at);
CREATE INDEX ix_webauthn_challenges_user ON webauthn_challenges(user_id);

-- A consumed ceremony/OTP cannot be restored by a stale update. Reissue uses a fresh delivery ID.
CREATE FUNCTION reject_identity_reactivation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'otp_challenges' THEN
    IF NEW.delivery_id = OLD.delivery_id AND OLD.consumed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'Consumed OTP cannot change' USING ERRCODE = '23514';
    END IF;
  ELSIF TG_TABLE_NAME = 'webauthn_challenges' THEN
    IF OLD.consumed_at IS NOT NULL OR
      (to_jsonb(NEW) - 'consumed_at') IS DISTINCT FROM (to_jsonb(OLD) - 'consumed_at') THEN
      RAISE EXCEPTION 'Ceremony binding is immutable' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER otp_consumption BEFORE UPDATE ON otp_challenges FOR EACH ROW EXECUTE FUNCTION reject_identity_reactivation();
CREATE TRIGGER webauthn_consumption BEFORE UPDATE ON webauthn_challenges FOR EACH ROW EXECUTE FUNCTION reject_identity_reactivation();

ALTER TABLE audit_events DROP CONSTRAINT ck_audit_details;
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_details CHECK (
  jsonb_typeof(details) = 'object' AND CASE
    WHEN action IN ('role.granted', 'role.revoked') THEN
      details - 'role' = '{}'::jsonb AND (NOT details ? 'role' OR details->'role' IN ('"USER"'::jsonb, '"ADMIN"'::jsonb))
    WHEN action = 'career_site.registered' THEN
      details - 'provider' = '{}'::jsonb AND (NOT details ? 'provider' OR details->'provider' IN ('"FLEX"'::jsonb, '"GREETING"'::jsonb, '"NINEHIRE"'::jsonb))
    WHEN action = 'career_site.settings_changed' THEN
      details - 'enabled' = '{}'::jsonb AND (NOT details ? 'enabled' OR details->'enabled' IN ('"true"'::jsonb, '"false"'::jsonb))
    WHEN action IN ('crawl.manually_triggered', 'passkey.registered', 'passkey.removed', 'passkey.renamed',
      'recovery_code.rotated', 'recovery.completed', 'session.revoked', 'admin_configuration.changed') THEN details = '{}'::jsonb
    ELSE FALSE
  END
);
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON users, user_roles, passkey_credentials, otp_challenges,
      recovery_codes, user_sessions, restricted_sessions, webauthn_challenges TO finds_app;
  END IF;
END;
$$;
