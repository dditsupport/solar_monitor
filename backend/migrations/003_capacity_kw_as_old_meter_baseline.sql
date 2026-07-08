-- Migration: repurpose energy_devices.capacity_kw as the replaced meter's
-- last reading (kWh) at install, and widen it to fit a real cumulative reading.
--
-- The column name is intentionally kept: the ingest endpoint and the Android
-- app already read/write `capacity_kw`, and renaming it would break that API
-- contract. Only the meaning (now "Old kWh") and the width change.
--
-- Apply once via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

ALTER TABLE energy_devices
  MODIFY capacity_kw DECIMAL(12,2) NULL;
