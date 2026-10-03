#!/usr/bin/env python3
"""Backend API scenarios: ingest, readings, counter resets, seq renumbering,
CSRF, device claiming, admin, time zone. Runs against a live PHP server and the
MariaDB behind it — tests/backend/run.sh sets both up from scratch.

Environment: SM_BASE (http://127.0.0.1:8097), SM_SOCK (MariaDB socket),
SM_DB (database), SM_TOKEN (DEVICE_TOKEN in the test secrets.php).
Expects users admin/adminpass, alice/alicepass, bob/bobpass and an empty
database otherwise.
"""
import json, os, urllib.request, urllib.parse, http.cookiejar, subprocess, datetime, time, sys
BASE  = os.environ.get("SM_BASE", "http://127.0.0.1:8097")
SOCK  = os.environ["SM_SOCK"]
DB    = os.environ.get("SM_DB", "solar_test")
TOKEN = os.environ.get("SM_TOKEN", "testtoken")
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
fails = 0
def check(name, cond, detail=""):
    global fails
    print(("PASS " if cond else "FAIL ") + name + (("  -> " + str(detail)) if (detail != "" and not cond) else ""))
    if not cond: fails += 1
def sql(q):
    return subprocess.run(["mariadb", "-uroot", "--socket=" + SOCK, DB, "-N", "-e", "SET time_zone='+05:30'; " + q],
                          capture_output=True, text=True, check=True).stdout.strip()

class Client:
    def __init__(self):
        self.cj = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj),
                                              urllib.request.HTTPRedirectHandler())
    def req(self, path, data=None, form=None, headers=None, method=None):
        h = dict(headers or {})
        body = None
        if data is not None:
            body = json.dumps(data).encode(); h["Content-Type"] = "application/json"
        elif form is not None:
            body = urllib.parse.urlencode(form).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
        r = urllib.request.Request(BASE + path, data=body, headers=h, method=method)
        try:
            with self.op.open(r) as resp: code, raw = resp.status, resp.read()
        except urllib.error.HTTPError as e: code, raw = e.code, e.read()
        try: return code, json.loads(raw)
        except Exception: return code, raw.decode(errors="replace")[:200]

now = int(time.time())
iso_now = datetime.datetime.fromtimestamp(now, IST).isoformat()
dev = Client()
TOK = {"X-Device-Token": TOKEN}

# ---------- 1. ingest + PZEM counter reset ------------------------------------
def row(seq, bid, sec, wh, v=230.0, t=None):
    r = {"seq": seq, "boot_id": bid, "sec": sec, "V": v, "I": 1.0, "P": 200.0, "Wh": wh, "PF": 0.99, "Hz": 50.0}
    if t is not None: r["t"] = t
    return r
whs = [1000, 1500, 2000, 2300, 2600, 3000, 100, 400, 900, 1200]   # seq 7 = PZEM reset
rows = [row(i+1, 1, 1000*(i+1), (0 if w is None else w), v=(0 if w is None else 230)) for i, w in enumerate(whs)]
code, j = dev.req("/api/ingest.php", data={"device_id": "dev-a", "fw_version": "1.1.0", "sync_wall_time": iso_now,
    "current_boot_id": 1, "current_boot_uptime_sec": 30000, "boot_history": [], "readings": rows}, headers=TOK)
check("ingest batch 1 accepted", code == 200 and j.get("ok"), j)
check("all 10 seqs acked", j.get("acked_up_to_seq") == 10, j.get("acked_up_to_seq"))
check("10 rows stored", sql("SELECT COUNT(*) FROM solar_readings WHERE device_id='dev-a'") == "10")
check("one counter reset recorded at seq 7", sql("SELECT CONCAT(seq_after,':',wh_before,':',wh_after) FROM energy_resets WHERE device_id='dev-a'") == "7:3000.00:100.00",
      sql("SELECT * FROM energy_resets"))
check("ingest pushes default interval (900) when no override", j.get("log_interval_sec") == 900, j.get("log_interval_sec"))

adm = Client()
code, j = adm.req("/api/login.php", form={"username": "admin", "password": "adminpass"})
check("admin API login", code == 200 and j.get("ok"), j)
frm = datetime.datetime.fromtimestamp(now - 86400, IST).strftime("%Y-%m-%dT%H:%M:%S")
code, r = adm.req("/api/readings.php?" + urllib.parse.urlencode({"device_id": "dev-a", "aggregate": "hourly", "from": frm}))
check("readings ok", code == 200 and r.get("ok"), r)
bars = sum(p["kwh"] for p in r["points"])
check("Period total spans the reset: 1000->3000 then 100->1200 = 3.2 kWh", abs(r["total_kwh"] - 3.2) < 1e-9, r["total_kwh"])
check("hourly bars sum to the same 3.2", abs(bars - 3.2) < 1e-6, bars)
check("readings chain: first start 1000, last end 4200 (continuous)", r["points"][0]["wh_start"] == 1000 and r["points"][-1]["wh_end"] == 4200,
      (r["points"][0]["wh_start"], r["points"][-1]["wh_end"]))
check("every bar: end - start == kwh", all(abs((p["wh_end"]-p["wh_start"])/1000 - p["kwh"]) < 1e-6 for p in r["points"]))
check("meter_wh is continuous (4200, not raw 1200)", r["meter_wh"] == 4200, r["meter_wh"])
code, d = adm.req("/api/readings.php?" + urllib.parse.urlencode({"device_id": "dev-a", "aggregate": "daily", "from": frm}))
check("daily total also 3.2", abs(d["total_kwh"] - 3.2) < 1e-9 and abs(sum(p["kwh"] for p in d["points"]) - 3.2) < 1e-6, d["total_kwh"])
code, rw = adm.req("/api/readings.php?" + urllib.parse.urlencode({"device_id": "dev-a", "aggregate": "raw", "from": frm}))
check("raw Wh continuous after reset", [p["Wh"] for p in rw["points"]] == [1000, 1500, 2000, 2300, 2600, 3000, 3100, 3400, 3900, 4200], [p["Wh"] for p in rw["points"]])
code, bad = adm.req("/api/readings.php?device_id=dev-a&aggregate=daily&from=garbage")
check("bad date -> 400, not 500", code == 400 and bad.get("error") == "bad_date", (code, bad))

# ---------- 2. per-row timestamps from an earlier boot --------------------------
rows2 = [row(11, 2, 50, 1300, t=now-600), row(12, 2, 350, 1400, t=now-300), row(13, 2, 400, 1450), row(14, 3, 100, 1500)]
code, j = dev.req("/api/ingest.php", data={"device_id": "dev-a", "fw_version": "1.1.0", "sync_wall_time": iso_now,
    "current_boot_id": 3, "current_boot_uptime_sec": 200, "boot_history": [], "readings": rows2}, headers=TOK)
check("batch 2 accepted, acked through 14", code == 200 and j.get("acked_up_to_seq") == 14, j)
got = sql("SELECT GROUP_CONCAT(CONCAT(seq,'=',time_confidence,'@',UNIX_TIMESTAMP(wall_time)) ORDER BY seq) FROM solar_readings WHERE device_id='dev-a' AND seq>10")
exp = f"11=exact@{now-600},12=exact@{now-300},14=exact@{now-100}"
check("boot-2 rows placed at their own timestamps; untimed unknown-boot row skipped", got == exp, got)

# ---------- 3. payload hardening ------------------------------------------------
code, j = dev.req("/api/ingest.php", data={"device_id": "dev-a", "current_boot_id": 3, "readings": "x", "boot_history": 5}, headers=TOK)
check("non-array readings -> handled (200, nothing stored), not 500", code == 200 and j.get("ok"), (code, j))

# ---------- 4. CSRF: a session with no token must not pass ---------------------
alice = Client()
code, _ = alice.req("/dashboard/login.php", form={"username": "alice", "password": "alicepass"})
code, j = alice.req("/api/claim_device.php", data={"device_id": "dev-a"})
check("dashboard-only session, no token -> 403 bad_csrf", code == 403 and j.get("error") == "bad_csrf", (code, j))
code, j = alice.req("/api/claim_device.php", data={"device_id": "dev-a"}, headers={"X-CSRF": ""})
check("empty X-CSRF -> 403 bad_csrf", code == 403 and j.get("error") == "bad_csrf", (code, j))
code, t = alice.req("/api/csrf.php")
code, j = alice.req("/api/claim_device.php", data={"device_id": "dev-a", "notes": "roof east"}, headers={"X-CSRF": t["csrf"]})
check("alice claims unowned device with a valid token", code == 200 and j.get("ok"), (code, j))

# ---------- 5. no takeover by another user ---------------------------------------
bob = Client()
code, bl = bob.req("/api/login.php", data={"username": "bob", "password": "bobpass"})
code, j = bob.req("/api/claim_device.php", data={"device_id": "dev-a"}, headers={"X-CSRF": bl["csrf"]})
check("bob cannot take over alice's device", code == 403 and j.get("error") == "device_owned_by_other_user", (code, j))
check("owner still alice", sql("SELECT owner_user_id FROM energy_devices WHERE device_id='dev-a'") == "2")
code, j = bob.req("/api/reset_device_data.php", data={"device_id": "dev-a"}, headers={"X-CSRF": bl["csrf"]})
check("bob cannot wipe it either", code == 403, (code, j))
code, j = alice.req("/api/claim_device.php", data={"device_id": "dev-a", "friendly_name": "Roof"}, headers={"X-CSRF": t["csrf"]})
check("alice re-claiming her own device still works", code == 200 and j.get("ok"), (code, j))

# ---------- 6. admin: Save keeps notes; interval 0 = default ---------------------
code, ac = adm.req("/api/csrf.php")
code, j = adm.req("/api/admin_devices.php", form={"action": "rename", "csrf": ac["csrf"], "device_id": "dev-a",
    "friendly_name": "Roof A", "location": "", "capacity_kw": "21120", "adjustment_kwh": ""})
check("admin rename ok", code == 200 and j.get("ok"), (code, j))
check("notes survive an admin Save", sql("SELECT notes FROM energy_devices WHERE device_id='dev-a'") == "roof east")
code, j = adm.req("/api/admin_devices.php", form={"action": "set_interval", "csrf": ac["csrf"], "device_id": "dev-a", "log_interval_sec": "300"})
code, j2 = dev.req("/api/ingest.php", data={"device_id": "dev-a", "current_boot_id": 3, "readings": []}, headers=TOK)
check("override 300 is pushed", j2.get("log_interval_sec") == 300, j2.get("log_interval_sec"))
code, j = adm.req("/api/admin_devices.php", form={"action": "set_interval", "csrf": ac["csrf"], "device_id": "dev-a", "log_interval_sec": "0"})
check("interval 0 stored as 0 (not 900)", sql("SELECT log_interval_sec FROM device_meta WHERE device_id='dev-a'") == "0")
code, j2 = dev.req("/api/ingest.php", data={"device_id": "dev-a", "current_boot_id": 3, "readings": []}, headers=TOK)
check("0 -> device follows DEFAULT_LOG_INTERVAL_SEC (900)", j2.get("log_interval_sec") == 900, j2.get("log_interval_sec"))

# ---------- 7. MySQL session time zone pinned to IST ------------------------------
ls = sql("SELECT UNIX_TIMESTAMP(last_sync_at) FROM device_meta WHERE device_id='dev-a'")
lsd = sql("SELECT last_sync_at FROM device_meta WHERE device_id='dev-a'")
check("last_sync_at (NOW() on the app connection) is IST wall time", abs(int(ls) - now) < 120, (lsd, int(ls) - now))

# ---------- 8. lazy backfill for pre-009 data ------------------------------------
sql("INSERT INTO energy_devices (device_id, friendly_name) VALUES ('dev-b','B')")
sql("INSERT INTO device_meta (device_id, resets_scanned) VALUES ('dev-b', 0)")
vals = []
for i, wh in enumerate([500, 800, 50, 300]):
    ts = datetime.datetime.fromtimestamp(now - 3600*4 + 600*i, IST).strftime("%Y-%m-%d %H:%M:%S")
    vals.append(f"('dev-b',{i+1},'{ts}','exact',1,{i},230,1,200,{wh},0.99,50)")
sql("INSERT INTO solar_readings (device_id,seq,wall_time,time_confidence,boot_id,sec_since_boot,voltage,current_a,power_w,energy_wh,power_factor,frequency_hz) VALUES " + ",".join(vals))
code, rb = adm.req("/api/readings.php?" + urllib.parse.urlencode({"device_id": "dev-b", "aggregate": "daily", "from": frm}))
check("pre-009 device scanned on first read", sql("SELECT resets_scanned FROM device_meta WHERE device_id='dev-b'") == "1"
      and sql("SELECT seq_after FROM energy_resets WHERE device_id='dev-b'") == "3")
check("its total spans the old reset: 500->800, then 0->300 after it = 0.6 kWh", abs(rb["total_kwh"] - 0.6) < 1e-9, rb["total_kwh"])

# ---------- 9. reset clears the drop records too ----------------------------------
code, j = alice.req("/api/reset_device_data.php", data={"device_id": "dev-a"}, headers={"X-CSRF": t["csrf"]})
check("owner reset ok, readings + resets cleared", code == 200 and sql("SELECT COUNT(*) FROM energy_resets WHERE device_id='dev-a'") == "0"
      and sql("SELECT COUNT(*) FROM solar_readings WHERE device_id='dev-a'") == "0", (code, j))

# ---------- 10. seq renumbering + PZEM swap ------------------------------------
def post(body):
    r = urllib.request.Request(BASE+"/api/ingest.php", data=json.dumps(body).encode(), headers={"Content-Type":"application/json","X-Device-Token":TOKEN})
    with urllib.request.urlopen(r) as resp: return json.loads(resp.read())
now = int(time.time()); iso = datetime.datetime.fromtimestamp(now, IST).isoformat()
def row(seq, bid, sec, wh): return {"seq":seq,"boot_id":bid,"sec":sec,"V":231.0,"I":1.0,"P":200.0,"Wh":wh,"PF":0.99,"Hz":50.0,"t":now-36000+sec}
def body(dev, bid, up, rows, fresh=False):
    b = {"device_id":dev,"fw_version":"1.1.0","sync_wall_time":iso,"current_boot_id":bid,"current_boot_uptime_sec":up,"boot_history":[],"readings":rows}
    if fresh: b["seq_fresh"] = True
    return b

# history from the first life of the board: seq 1..5, boot 7
j = post(body("dev-e", 7, 30000, [row(i, 7, 1000*i, 5000+500*(i-1)) for i in range(1, 6)]))
check("first life stored, acked 5", j["ok"] and j["acked_up_to_seq"] == 5 and sql("SELECT COUNT(*) FROM solar_readings WHERE device_id='dev-e'") == "5", j)

# genuine re-send of the same rows is still a harmless duplicate
j = post(body("dev-e", 7, 30100, [row(i, 7, 1000*i, 5000+500*(i-1)) for i in range(4, 6)]))
check("exact re-send: ok, acked, no conflict", j["ok"] and j["acked_up_to_seq"] == 5, j)

# full flash erase: counter restarts at 1, device says it is fresh
fresh_rows = [row(1, 1, 100, 7100), row(2, 1, 1000, 7300), row(3, 1, 1900, 7600)]
j = post(body("dev-e", 1, 2000, fresh_rows, fresh=True))
check("fresh counter -> seq_base_required with server max 5, ok:false", (not j["ok"]) and j.get("error") == "seq_base_required" and j.get("seq_base") == 5, j)
check("nothing stored from the fresh batch", sql("SELECT COUNT(*) FROM solar_readings WHERE device_id='dev-e'") == "5")
check("logged in ingest_log as seq_base", sql("SELECT status FROM ingest_log WHERE device_id='dev-e' ORDER BY id DESC LIMIT 1") == "seq_base")
# device renumbers (+5) and resends without the flag
j = post(body("dev-e", 1, 2000, [dict(r, seq=r["seq"]+5) for r in fresh_rows]))
check("renumbered rows accepted, acked 8", j["ok"] and j["acked_up_to_seq"] == 8, j)
check("all 8 readings kept (5 old + 3 new)", sql("SELECT COUNT(*) FROM solar_readings WHERE device_id='dev-e'") == "8")

# backstop: a seq reused for a different reading WITHOUT the fresh flag
j = post(body("dev-e", 1, 2100, [row(2, 1, 2100, 7700)]))
check("seq clash with a different reading -> seq_base_required, base 8", (not j["ok"]) and j.get("seq_base") == 8, j)

# brand-new device id with a fresh counter: base 0
j = post(body("dev-f", 1, 500, [row(1, 1, 100, 10)], fresh=True))
check("fresh device with no history -> base 0", j.get("error") == "seq_base_required" and j.get("seq_base") == 0, j)

# ---- new fresh PZEM fitted: counter drops to ~0 between seq 8 and 9 ----
j = post(body("dev-e", 1, 9000, [row(9, 1, 8000, 20), row(10, 1, 8900, 320)]))
check("post-swap rows accepted", j["ok"] and j["acked_up_to_seq"] == 10, j)
check("swap recorded as a counter drop at seq 9", sql("SELECT CONCAT(seq_after,':',wh_before,':',wh_after) FROM energy_resets WHERE device_id='dev-e'") == "9:7600.00:20.00",
      sql("SELECT * FROM energy_resets WHERE device_id='dev-e'"))
# log in as admin and read the totals
cj = http.cookiejar.CookieJar(); op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
op.open(urllib.request.Request(BASE+"/api/login.php", data=urllib.parse.urlencode({"username":"admin","password":"adminpass"}).encode()))
frm = datetime.datetime.fromtimestamp(now-2*86400, IST).strftime("%Y-%m-%dT%H:%M:%S")
r = json.loads(op.open(BASE+"/api/readings.php?"+urllib.parse.urlencode({"device_id":"dev-e","aggregate":"daily","from":frm})).read())
# old PZEM 5000->7600 (2.6 kWh), new PZEM counted 0->320 (0.32 kWh)
check("Period total = old PZEM 2.6 + new PZEM 0.32 = 2.92 kWh", abs(r["total_kwh"] - 2.92) < 1e-9, r["total_kwh"])
check("Meter reading continues across the swap (7600 + 320 = 7920 Wh)", r["meter_wh"] == 7920, r["meter_wh"])
check("bars still sum to the total", abs(sum(p["kwh"] for p in r["points"]) - 2.92) < 1e-6, [p["kwh"] for p in r["points"]])

print("\n%d failure(s)" % fails)
sys.exit(1 if fails else 0)
