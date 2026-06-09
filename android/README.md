# Companion Android app

Kotlin + Jetpack Compose project, living under
[`solar-monitor-app/`](solar-monitor-app/). See its own
[README](solar-monitor-app/README.md) for build instructions.

## Status

Stage 8 is being built incrementally:

| Slice | Commit | Notes |
|---|---|---|
| Project scaffold (Gradle + Manifest + Compose) | ✅ first cut | |
| BLE scan filtered by firmware's service UUID | ✅ first cut | |
| Add nearby device to saved list, persist to DataStore | ✅ first cut | |
| Remove saved device | ✅ first cut | |
| Tap-to-connect detail screen | ⏳ next | will read Device Info JSON |
| BLE Wi-Fi provisioning UI (scan / pick / save) | ⏳ next | mirrors §5.2 of the spec |
| Pull buffered rows, forward to MilesWeb, ACK back | ⏳ later | the original raison d'être |
| Charts (live from `readings.php`) | ⏳ later | post-backend session |
