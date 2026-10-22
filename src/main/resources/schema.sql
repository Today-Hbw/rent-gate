CREATE TABLE IF NOT EXISTS device (
  device_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  key_hash VARCHAR(64) NOT NULL,
  status VARCHAR(8) NOT NULL DEFAULT 'off' CHECK (status IN ('on', 'off')),
  expire_ts BIGINT NOT NULL DEFAULT 0,
  last_seen_ts BIGINT,
  relay_on BOOLEAN,
  created_ts BIGINT NOT NULL
);
-- 第一阶段只预建订单表，手动放行永远不会写订单。
CREATE TABLE IF NOT EXISTS rental_order (
  order_no VARCHAR(64) PRIMARY KEY,
  device_id VARCHAR(64) NOT NULL REFERENCES device(device_id),
  user_id BIGINT,
  merchant_id BIGINT,
  duration_min INTEGER NOT NULL CHECK (duration_min > 0),
  amount DECIMAL(12,2) NOT NULL CHECK (amount >= 0),
  pay_channel VARCHAR(20),
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  pay_transaction_id VARCHAR(128),
  created_ts BIGINT NOT NULL
);
