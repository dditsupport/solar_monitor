-- Migration 009
--   (a) record PZEM counter drops, so totals survive a reset / swap / rollover
--   (b) "0 = server default" for the per-device log interval
--   (c) remove the all-zero rows older firmware logged on a failed PZEM read
--
-- Run once (ADD COLUMN is not idempotent). Apply via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql
-- The PHP keeps working if this has not been applied yet — it just cannot
-- correct for counter resets until it has.

-- ---------------------------------------------------------------------------
-- (a) Counter drops
--
-- The PZEM's cumulative Wh counter restarts at 0 on the app's "Reset PZEM"
-- command or a PZEM swap, and rolls over at 9,999.99 kWh. Every total is a
-- difference of two counter readings, so one drop inside a window made that
-- window's total garbage. ingest.php now records every drop between two
-- readings adjacent in seq order here, and readings.php adds wh_before back to
-- every later reading — the counter as if it had never restarted.
CREATE TABLE IF NOT EXISTS energy_resets (
  device_id        VARCHAR(32)     NOT NULL,
  seq_after        BIGINT UNSIGNED NOT NULL,   -- first reading after the drop
  wall_time_after  DATETIME        NOT NULL,
  wh_before        DECIMAL(14,2)   NOT NULL,   -- last reading before the drop
  wh_after         DECIMAL(14,2)   NOT NULL,
  PRIMARY KEY (device_id, seq_after),
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Readings already in the database have never been scanned for drops.
-- readings.php scans a device once, the first time its data is asked for,
-- when this flag is 0; ingest.php keeps every device current after that.
-- New devices start at 1 because ingest scans them from their first row.
ALTER TABLE device_meta
  ADD COLUMN resets_scanned TINYINT(1) NOT NULL DEFAULT 1;
UPDATE device_meta SET resets_scanned = 0;

-- ---------------------------------------------------------------------------
-- (b) Log interval: 0 means "follow DEFAULT_LOG_INTERVAL_SEC"
--
-- The column defaulted to 900 and the admin page stored 900 for "use default",
-- so no device ever followed the global default. 900 was also that default, so
-- resetting those rows to 0 changes nothing today — it only lets a later change
-- to DEFAULT_LOG_INTERVAL_SEC take effect. (An explicit 900 override, if anyone
-- set one deliberately, becomes "default", which is the same 900 s.)
ALTER TABLE device_meta
  MODIFY log_interval_sec INT UNSIGNED NOT NULL DEFAULT 0;
UPDATE device_meta SET log_interval_sec = 0 WHERE log_interval_sec = 900;

-- ---------------------------------------------------------------------------
-- (c) All-zero rows
--
-- Firmware before 1.1 logged a row of zeros when the PZEM read failed at the
-- moment a row was due. 0 Wh then became that day's "first reading", so the
-- whole lifetime counter showed up as one day's generation. A real reading
-- always has voltage (the PZEM is powered by the mains it measures), so a row
-- with neither voltage nor energy is never a measurement.
DELETE FROM solar_readings WHERE voltage <= 0 AND energy_wh <= 0;
