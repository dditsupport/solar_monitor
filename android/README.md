# Companion Android app

Not yet implemented. The plan lives in §5 of the project spec.

When started, this directory will hold an Android Studio Kotlin project
that:

1. Scans for BLE devices advertising the `Solar-*` name prefix.
2. Connects, reads Device Info, sets wall-clock from the phone,
   subscribes to the Data Stream characteristic.
3. Parses accumulated CSV rows up to the `"END\n"` terminator.
4. POSTs them to the MilesWeb `/api/solar/ingest.php` endpoint with the
   `X-Device-Token` header.
5. Writes the highest acked seq back to the **Sync ACK** characteristic so
   the firmware can truncate its log.

Custom GATT UUIDs are listed in `firmware/solar_monitor/config.h`.
