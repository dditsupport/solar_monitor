# Backend (MilesWeb PHP + MySQL)

Not yet implemented. The plan and full schema live in the top-level
project spec; see also stages 5 and 9 in the root `README.md`.

Files expected here in a later session:

```
backend/
  schema.sql
  public_html/
    _config/
      secrets.php
      .htaccess
    api/solar/
      _db.php
      ingest.php
      readings.php
    dashboard/
      index.php
      assets/style.css
```

Until then, use `tools/fake_ingest.py` from the repo root for bench testing
the firmware's Wi-Fi sync path.

## Required response shape

`POST /api/solar/ingest.php` must return JSON with at least:

```json
{
  "ok": true,
  "acked_up_to_seq": 1248,
  "server_time": "2026-05-19T14:32:11+05:30"
}
```

- `ok`: true on success.
- `acked_up_to_seq`: the highest `seq` from the request the server has
  durably persisted. The firmware truncates `/log.csv` up to and
  including this value.
- `server_time` *(new, required when the firmware lacks NTP and RTC)*:
  an ISO 8601 timestamp the firmware uses to seed its wall clock if no
  other source has produced one yet. Format with `Z` or `+HH:MM`
  offset. Sourced from `date('c')` in PHP after `date_default_timezone_set(APP_TIMEZONE)`.
