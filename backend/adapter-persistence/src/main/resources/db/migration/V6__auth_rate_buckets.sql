-- Shared anonymous abuse controls survive restarts; only purpose-separated HMAC keys are stored.
CREATE TABLE auth_rate_buckets (
  bucket_key TEXT PRIMARY KEY CHECK (bucket_key ~ '^v[1-9][0-9]*:[0-9a-f]{64}$'),
  attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 1001),
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_auth_rate_buckets_expiry ON auth_rate_buckets(expires_at);
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'finds_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON auth_rate_buckets TO finds_app;
  END IF;
END;
$$;
