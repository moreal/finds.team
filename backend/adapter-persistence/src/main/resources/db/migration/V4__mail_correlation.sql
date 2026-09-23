-- Correlation is opaque diagnostic metadata, never recipient or account information.
ALTER TABLE mail_outbox ADD COLUMN correlation_id UUID;

-- Retained messages predate request correlation: their stable delivery ID is the legacy fallback.
UPDATE mail_outbox SET correlation_id = message_id;
ALTER TABLE mail_outbox ALTER COLUMN correlation_id SET NOT NULL;

-- Older application instances may enqueue during a rolling deployment. Give those new rows an
-- opaque per-row correlation UUID; upgraded writers always supply semantic request metadata.
ALTER TABLE mail_outbox ALTER COLUMN correlation_id SET DEFAULT gen_random_uuid();
