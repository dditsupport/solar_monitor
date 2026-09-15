-- Migration 008 — add device_heap_log.source
--
-- REQUIRED if you applied 007 before the BLE-relay heap change. ingest.php now
-- INSERTs a `source` column; against a table without it the INSERT throws, and
-- because heap logging is deliberately wrapped in try/catch so telemetry can
-- never fail a sync, it fails SILENTLY. Heap logging simply stops with nothing
-- in the log to say why.
--
-- Skip this if you applied 007 after that change -- it already includes the
-- column. Check with:  SHOW COLUMNS FROM device_heap_log LIKE 'source';
--
-- 'wifi' = the device sampled its own heap around its own POST.
-- 'ble'  = the Android app read it over GATT during a relay sync. That is a
--          different memory context (radio in another state, Wi-Fi often down)
--          so the two must be charted separately, not as one series.
--
-- Existing rows are all device POSTs, so the DEFAULT backfills them correctly.

ALTER TABLE device_heap_log
  ADD COLUMN source ENUM('wifi','ble') NOT NULL DEFAULT 'wifi' AFTER heap_min;
