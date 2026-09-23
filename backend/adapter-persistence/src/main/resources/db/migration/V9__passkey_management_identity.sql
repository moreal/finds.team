-- The credential ID belongs to WebAuthn ceremonies and is never exposed by account metadata APIs.
ALTER TABLE passkey_credentials ADD COLUMN management_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX ix_passkey_management_id ON passkey_credentials(management_id);

-- Keep the non-cryptographic lookup with audit-retained command results so a committed removal
-- can replay after usable material has been deleted. Neither keys nor authenticator state live here.
CREATE TABLE passkey_management_references (
  management_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  credential_id TEXT NOT NULL
);
INSERT INTO passkey_management_references(management_id, user_id, credential_id)
  SELECT management_id, user_id, credential_id FROM passkey_credentials;
CREATE FUNCTION remember_passkey_management_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO passkey_management_references(management_id, user_id, credential_id)
    VALUES (NEW.management_id, NEW.user_id, NEW.credential_id);
  RETURN NEW;
END;
$$;
CREATE TRIGGER remember_passkey_management AFTER INSERT ON passkey_credentials
  FOR EACH ROW EXECUTE FUNCTION remember_passkey_management_reference();
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN
    GRANT SELECT, INSERT ON passkey_management_references TO finds_app;
  END IF;
END;
$$;
