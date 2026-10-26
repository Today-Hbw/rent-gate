ALTER TABLE device ADD COLUMN key_cipher VARCHAR(256);
ALTER TABLE device ADD COLUMN firmware_version VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE device ADD COLUMN setup_request_id VARCHAR(36);
ALTER TABLE device ADD COLUMN setup_requested_ts BIGINT;

CREATE TABLE device_pairing (
  device_id VARCHAR(64) PRIMARY KEY REFERENCES device(device_id) ON DELETE CASCADE,
  token_hash VARCHAR(64),
  pairing_code VARCHAR(6),
  expires_ts BIGINT NOT NULL,
  approved BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts INTEGER NOT NULL DEFAULT 0
);
