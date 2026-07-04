-- Migration: per-device RTC drift log.
-- Idempotent: CREATE TABLE IF NOT EXISTS.
-- Apply once via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

CREATE TABLE IF NOT EXISTS rtc_drift_log (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id   VARCHAR(32)   NOT NULL,
  measured_at DATETIME      NOT NULL,             -- device wall time of the sample
  drift_sec   INT           NOT NULL,             -- signed: + = RTC ahead of NTP
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_device_time (device_id, measured_at),
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
