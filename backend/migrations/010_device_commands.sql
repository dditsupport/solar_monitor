-- Migration 010 — device_commands: actions queued from the web Devices page
--
-- Run once (phpMyAdmin -> SQL). Needed for "Reset PZEM to 0" on the Devices
-- page (dashboard/devices.php), for users without the Android app.
--
-- The device has no inbound connection, so a command waits here and rides on
-- the reply to its next Wi-Fi sync (ingest.php adds "cmd": {id, name}). The
-- firmware (1.2.0+) carries it out once and confirms with "cmd_done": id on
-- its next POST, which sets done_at. One pending row per device and command.
--
-- cmd: 'reset_pzem' = zero the PZEM's cumulative energy counter. The meter
-- reading on the dashboard stays continuous: the counter drop is recorded in
-- energy_resets like any other PZEM reset.
--
-- Until this is run, ingest.php and the Devices page simply skip commands.

CREATE TABLE IF NOT EXISTS device_commands (
  id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id   VARCHAR(32)  NOT NULL,
  cmd         VARCHAR(32)  NOT NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by  INT UNSIGNED NULL,
  done_at     DATETIME     NULL,
  KEY idx_device_pending (device_id, done_at),
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
