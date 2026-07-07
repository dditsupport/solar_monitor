-- Migration: add energy_devices.adjustment_kwh — a signed manual correction
-- (kWh) added to the dashboard Period total so the displayed cumulative
-- (Old kWh + generated + adjustment) matches the physical solar generation
-- meter. Set it to (actual meter reading - what the dashboard currently shows).
--
-- Run once (ADD COLUMN is not idempotent). Apply via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

ALTER TABLE energy_devices
  ADD COLUMN adjustment_kwh DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER capacity_kw;
