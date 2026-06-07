#pragma once

// ---------- Identity / build ----------
#define FW_VERSION              "1.0.0"

// ---------- Backend ----------
// TODO(user): set INGEST_URL to your MilesWeb domain when backend is live.
// For Stage 6 bench testing, point this at the laptop running tools/fake_ingest.py
// e.g. "http://192.168.4.2:8080/ingest".
#define INGEST_URL              "https://example.com/api/solar/ingest.php"
#define DEVICE_TOKEN            "hs2AfGYZqZSFbb_rp-t3zy_I_rXb5TJISpn6Okih4pg"

// ---------- Timing ----------
#define LOG_INTERVAL_SEC        900       // 15 minutes between flash log rows
#define DISPLAY_REFRESH_MS      1000      // 1 Hz OLED refresh & PZEM sample
#define WIFI_SCAN_INTERVAL_SEC  120       // 2 minutes between Wi-Fi cycles
#define NTP_SYNC_TIMEOUT_MS     5000
#define WIFI_CONNECT_TIMEOUT_MS 15000
#define HTTP_TIMEOUT_MS         10000

// ---------- Storage ----------
#define SYNC_BATCH_SIZE         100       // rows per POST
#define MAX_BOOT_HISTORY        32        // circular buffer entries
#define MAX_WIFI_CREDS          1         // only one network at a time
#define SEQ_HWM_STRIDE          10        // NVS write batching for last_seq
#define BUFFER_FREE_MIN_BYTES   (150 * 1024UL)
#define BUFFER_FREE_MIN_PCT     10        // also keep >= 10% free
#define MAX_SCAN_RESULTS        12        // top-N APs returned over BLE

// ---------- Fault thresholds ----------
#define PZEM_FAIL_THRESHOLD     3         // consecutive Modbus fails -> PZEM ERROR
#define SENSOR_LOW_V_THRESHOLD  50.0f     // V < this for SENSOR_FAULT_WINDOW = SENSOR? fault
#define SENSOR_FAULT_WINDOW_SEC 60

// ---------- Boot-loop guard ----------
#define BOOTLOOP_WINDOW_SEC     60
#define BOOTLOOP_THRESHOLD      5         // boots inside the window -> BLE-only mode

// ---------- Pin map (ESP32 DevKit V1) ----------
#define PIN_PZEM_RX             16        // ESP32 RX2 <- PZEM TX
#define PIN_PZEM_TX             17        // ESP32 TX2 -> PZEM RX
#define PZEM_BAUD               9600

#define PIN_OLED_MOSI           23
#define PIN_OLED_SCK            18
#define PIN_OLED_CS             5
#define PIN_OLED_DC             4
#define PIN_OLED_RST            2

// ---------- Time ----------
#define TZ_INFO                 "IST-5:30"   // POSIX TZ, used by setenv()
#define NTP_SERVER_1            "pool.ntp.org"
#define NTP_SERVER_2            "time.nist.gov"

// ---------- BLE UUIDs (generated once, do not change) ----------
#define BLE_SERVICE_UUID        "5f12b3bc-8ef3-4b48-a971-f70a38f519ec"
#define BLE_UUID_DEVICE_INFO    "56c4fe7d-1c7d-4042-9547-6170ec5c243c"
#define BLE_UUID_SET_WALL_TIME  "b90e068f-8856-4cba-a043-841081fbd1a1"
#define BLE_UUID_BOOT_HISTORY   "d155756b-566e-4aa3-9fe5-c898f78fda8b"
#define BLE_UUID_DATA_STREAM    "1199716e-692b-4d47-bd00-72792988364d"
#define BLE_UUID_SYNC_ACK       "a4b32253-c2e3-42e8-93c3-a008325540b6"
#define BLE_UUID_WIFI_CONFIG    "41310027-c18e-4452-a50e-861e77cf2743"
#define BLE_UUID_WIFI_STATUS    "28c3fa43-a1b5-4e0e-a51c-a1e979609d28"
#define BLE_UUID_WIFI_SCAN      "d4346c1c-6e36-4a0f-a164-84cd396a4697"

// ---------- Files ----------
#define LOG_PATH                "/log.csv"
#define LOG_TMP_PATH            "/log.tmp"

// ---------- Task config ----------
#define SAMPLING_TASK_STACK     6144
#define CONN_TASK_STACK         12288
#define SAMPLING_TASK_PRIO      3
#define CONN_TASK_PRIO          2
