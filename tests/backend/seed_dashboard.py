#!/usr/bin/env python3
"""SQL (on stdout) for device dev-c: 35 days of 15-minute readings ending now,
a solar-shaped power curve, a 2543 W peak 3 days ago at 13:00, and a PZEM
counter reset 10 days ago at 03:00. Used by ui_test.js."""
import math, datetime
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
now = datetime.datetime.now(IST).replace(second=0, microsecond=0)
now = now.replace(minute=now.minute - now.minute % 15)
start = (now - datetime.timedelta(days=35)).replace(hour=0, minute=0)
reset_at = (now - datetime.timedelta(days=10)).replace(hour=3, minute=0)
peak_at = (now - datetime.timedelta(days=3)).replace(hour=13, minute=0)
rows, wh, seq, t = [], 150000.0, 0, start
while t <= now:
    h = t.hour + t.minute / 60
    p = max(0.0, 2400 * math.sin(math.pi * (h - 6) / 13)) if 6 <= h <= 19 else 0.0
    if t == peak_at: p = 2543.0
    wh += p * 0.25
    if t == reset_at: wh = 0.0
    seq += 1
    rows.append(f"('dev-c',{seq},'{t:%Y-%m-%d %H:%M:%S}','exact',1,{seq*900},231.5,{p/231.5:.3f},{p:.2f},{wh:.2f},0.99,50)")
    t += datetime.timedelta(minutes=15)
print("INSERT INTO energy_devices (device_id, friendly_name, capacity_kw, adjustment_kwh) VALUES ('dev-c','Roof C',21120,0);")
print("INSERT INTO device_meta (device_id, last_sync_at, resets_scanned) VALUES ('dev-c', NOW(), 0);")
for i in range(0, len(rows), 500):
    print("INSERT INTO solar_readings (device_id,seq,wall_time,time_confidence,boot_id,sec_since_boot,voltage,current_a,"
          "power_w,energy_wh,power_factor,frequency_hz) VALUES " + ",".join(rows[i:i+500]) + ";")
