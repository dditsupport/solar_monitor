-- Migration 011 — remove readings no PZEM can produce, and rebuild the counter
-- resets they caused.
--
-- Run once (phpMyAdmin -> SQL). Safe to run again.
--
-- Why: a garbled PZEM UART frame was logged and uploaded as a real reading
-- (e.g. 6,170 V, current >99,999 A, 1.3 MW, PF 9.999, 243 Hz, 68,451 kWh on
-- solar-6da978, 21 Nov 2026). The energy counter in those rows jumped up by
-- ~68 MWh and back down; every fall is recorded in energy_resets as a PZEM
-- reset and its height is added to all later readings, so the device's meter
-- reading and every range spanning that day were inflated by ~137 MWh.
-- Firmware 1.2.0+ and ingest.php now reject such readings; this removes the
-- ones already stored. The limits are the same as the firmware's and
-- ingest.php's (PZEM-004T-100A ratings with a margin).
--
-- Optional preview first (what will be deleted):
--   SELECT id, device_id, seq, wall_time, voltage, current_a, power_w,
--          energy_wh, power_factor, frequency_hz
--     FROM solar_readings
--    WHERE voltage < 0 OR voltage > 300
--       OR current_a < 0 OR current_a > 100
--       OR power_w < 0 OR power_w > 23000 OR power_w > voltage * current_a * 1.1 + 10
--       OR energy_wh < 0 OR energy_wh > 9999990
--       OR power_factor < 0 OR power_factor > 1
--       OR (frequency_hz IS NOT NULL AND (frequency_hz < 0 OR frequency_hz > 65
--                                         OR (voltage >= 50 AND frequency_hz < 45)));

DELETE FROM solar_readings
 WHERE voltage < 0 OR voltage > 300
    OR current_a < 0 OR current_a > 100
    OR power_w < 0 OR power_w > 23000 OR power_w > voltage * current_a * 1.1 + 10
    OR energy_wh < 0 OR energy_wh > 9999990
    OR power_factor < 0 OR power_factor > 1
    OR (frequency_hz IS NOT NULL AND (frequency_hz < 0 OR frequency_hz > 65
                                      OR (voltage >= 50 AND frequency_hz < 45)));

-- Rebuild energy_resets from the clean rows: readings.php rescans a device in
-- full (stale drop records included) the next time its data is viewed.
UPDATE device_meta SET resets_scanned = 0;

-- Keep the admin "Rows" count honest after the delete.
UPDATE device_meta m
   SET total_readings = (SELECT COUNT(*) FROM solar_readings r WHERE r.device_id = m.device_id);
