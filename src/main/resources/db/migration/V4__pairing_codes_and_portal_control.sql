ALTER TABLE device_pairing ADD COLUMN enrollment_code_hash VARCHAR(64) UNIQUE;
ALTER TABLE device_pairing ADD COLUMN hardware_id VARCHAR(12);
ALTER TABLE device ADD COLUMN setup_active BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device ADD COLUMN setup_close_id VARCHAR(36);
ALTER TABLE device ADD COLUMN setup_close_ts BIGINT;
