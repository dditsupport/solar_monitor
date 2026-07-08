-- Migration: log Wi-Fi signal strength alongside the RTC drift sample.
-- rssi_dbm is negative dBm (e.g. -67) captured at the same hourly sample;
-- NULL for older rows / firmware that don't report it.
--
-- Run once (ADD COLUMN is not idempotent). Apply via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

ALTER TABLE rtc_drift_log
  ADD COLUMN rssi_dbm INT NULL AFTER drift_sec;
