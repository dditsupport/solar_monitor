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
