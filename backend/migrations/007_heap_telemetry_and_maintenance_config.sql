-- Migration 007
--   (a) store the per-POST heap telemetry the firmware now reports
--   (b) let the server push the nightly-reboot / radio-rest config per device
--
-- Run once (ADD COLUMN is not idempotent). Apply via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

-- ---------------------------------------------------------------------------
-- (a) Heap telemetry
--
-- The firmware sends heap_free / heap_largest / heap_min on EVERY ingest POST.
-- A time series rather than three columns because only the TREND distinguishes
-- the two failure modes: free falling steadily is a LEAK, while free holding
-- flat as largest decays is FRAGMENTATION. boot_id and uptime_sec ride along so
-- the sawtooth across reboots is visible without joining another table.
CREATE TABLE IF NOT EXISTS device_heap_log (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id    VARCHAR(32)     NOT NULL,
  sampled_at   DATETIME        NOT NULL,
  boot_id      INT UNSIGNED    NOT NULL DEFAULT 0,
  uptime_sec   INT UNSIGNED    NOT NULL DEFAULT 0,
  heap_free    INT UNSIGNED    NOT NULL,
  heap_largest INT UNSIGNED    NOT NULL,
  heap_min     INT UNSIGNED    NOT NULL,
  created_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_device_time (device_id, sampled_at),
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Latest sample denormalised onto device_meta so the admin list does not need a
-- correlated subquery per row.
ALTER TABLE device_meta
  ADD COLUMN heap_free    INT UNSIGNED NULL AFTER log_interval_sec,
  ADD COLUMN heap_largest INT UNSIGNED NULL AFTER heap_free,
  ADD COLUMN heap_min     INT UNSIGNED NULL AFTER heap_largest,
  ADD COLUMN heap_at      DATETIME     NULL AFTER heap_min;

-- ---------------------------------------------------------------------------
-- (b) Server-pushed maintenance config
--
-- Mirrors how log_interval_sec already works: NULL means "say nothing", and the
-- device stays on the compiled default baked into its config.h. ingest.php only
-- emits a field that is actually set, and the firmware leaves an absent field
-- alone -- so a half-configured row is safe.
--
-- nightly_reboot_*  the daily restart window in LOCAL device time. The exact
--                   minute inside it is derived from the device MAC so a fleet
--                   staggers instead of all returning at once.
-- radio_rest_*      interval 0 disables the periodic radio rest; the stuck-Wi-Fi
--                   escalation still forces one on demand, which is the path
--                   that matters.
ALTER TABLE device_meta
  ADD COLUMN nightly_reboot_enable     TINYINT(1)       NULL AFTER heap_at,
  ADD COLUMN nightly_reboot_start_hour TINYINT UNSIGNED NULL AFTER nightly_reboot_enable,
  ADD COLUMN nightly_reboot_end_hour   TINYINT UNSIGNED NULL AFTER nightly_reboot_start_hour,
  ADD COLUMN radio_rest_interval_sec   INT UNSIGNED     NULL AFTER nightly_reboot_end_hour,
  ADD COLUMN radio_rest_duration_sec   INT UNSIGNED     NULL AFTER radio_rest_interval_sec;
