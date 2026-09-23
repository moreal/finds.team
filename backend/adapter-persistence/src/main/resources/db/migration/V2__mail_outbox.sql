CREATE TABLE mail_outbox (
  message_id UUID PRIMARY KEY,
  purpose TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  payload_ciphertext BYTEA,
  payload_nonce BYTEA,
  key_version INTEGER NOT NULL CHECK (key_version > 0),
  state TEXT NOT NULL DEFAULT 'PENDING',
  lease_owner TEXT,
  lease_token UUID,
  lease_expires_at TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  provider TEXT,
  provider_message_id TEXT,
  completed_at TIMESTAMPTZ,
  CONSTRAINT ck_mail_outbox_purpose CHECK (purpose ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  CONSTRAINT ck_mail_outbox_expiry CHECK (expires_at > created_at),
  CONSTRAINT ck_mail_outbox_state CHECK (
    state IN ('PENDING', 'LEASED', 'ACCEPTED', 'FAILED', 'INDETERMINATE', 'EXPIRED')
  ),
  CONSTRAINT ck_mail_outbox_payload CHECK (
    (state IN ('PENDING', 'LEASED', 'INDETERMINATE') AND
      payload_ciphertext IS NOT NULL AND octet_length(payload_ciphertext) >= 16 AND
      payload_nonce IS NOT NULL AND octet_length(payload_nonce) = 12) OR
    (state IN ('ACCEPTED', 'FAILED', 'EXPIRED') AND payload_ciphertext IS NULL AND payload_nonce IS NULL)
  ),
  CONSTRAINT ck_mail_outbox_lease CHECK (
    (state = 'LEASED' AND lease_owner IS NOT NULL AND lease_owner <> '' AND
      lease_token IS NOT NULL AND lease_expires_at IS NOT NULL) OR
    (state <> 'LEASED' AND lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
  ),
  CONSTRAINT ck_mail_outbox_completion CHECK (
    (state IN ('PENDING', 'LEASED') AND completed_at IS NULL) OR
    (state IN ('ACCEPTED', 'FAILED', 'INDETERMINATE', 'EXPIRED') AND completed_at IS NOT NULL)
  ),
  CONSTRAINT ck_mail_outbox_receipt CHECK (
    (state = 'ACCEPTED' AND provider IS NOT NULL AND provider_message_id IS NOT NULL) OR
    (state <> 'ACCEPTED' AND provider_message_id IS NULL)
  ),
  CONSTRAINT uq_mail_outbox_nonce UNIQUE (key_version, payload_nonce)
);

CREATE TABLE mail_delivery_attempts (
  message_id UUID NOT NULL REFERENCES mail_outbox(message_id),
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  attempted_at TIMESTAMPTZ NOT NULL,
  provider TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'INDETERMINATE')),
  failure TEXT,
  retryable BOOLEAN,
  provider_message_id TEXT,
  PRIMARY KEY (message_id, attempt_number),
  CONSTRAINT ck_mail_attempt_result CHECK (
    (outcome = 'ACCEPTED' AND failure IS NULL AND retryable IS NULL AND provider_message_id IS NOT NULL) OR
    (outcome = 'REJECTED' AND failure IS NOT NULL AND retryable IS NOT NULL AND provider_message_id IS NULL) OR
    (outcome = 'INDETERMINATE' AND failure IS NOT NULL AND retryable IS NULL AND provider_message_id IS NULL)
  )
);

CREATE INDEX ix_mail_outbox_due ON mail_outbox(next_attempt_at, created_at, message_id)
  WHERE state IN ('PENDING', 'LEASED');
CREATE INDEX ix_mail_outbox_expiry ON mail_outbox(expires_at) WHERE payload_ciphertext IS NOT NULL;
