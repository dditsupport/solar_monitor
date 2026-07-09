-- Migration: log the RTC backup coin-cell (CR2032) voltage alongside the
-- RTC drift sample. coin_cell_v is the cell voltage in volts (e.g. 3.012),
-- captured at the same hourly sample as drift_sec / rssi_dbm. A sagging value
-- (toward ~2.5 V) predicts RTC time loss before the DS3231/DS1307 lost-power
-- flag ever trips. NULL for older rows / firmware that don't report it.
--
-- Run once (ADD COLUMN is not idempotent). Apply via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

ALTER TABLE rtc_drift_log
  ADD COLUMN coin_cell_v DECIMAL(5,3) NULL AFTER rssi_dbm;
