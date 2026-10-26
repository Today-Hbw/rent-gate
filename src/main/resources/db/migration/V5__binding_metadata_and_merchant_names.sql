ALTER TABLE merchant ADD COLUMN name_explicit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device ADD COLUMN hardware_id VARCHAR(12);
UPDATE device SET hardware_id=(SELECT p.hardware_id FROM device_pairing p WHERE p.device_id=device.device_id AND p.approved=TRUE)
WHERE EXISTS (SELECT 1 FROM device_pairing p WHERE p.device_id=device.device_id AND p.approved=TRUE AND p.hardware_id IS NOT NULL);
