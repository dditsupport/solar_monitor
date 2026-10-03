// Host-side tests for the firmware's pure logic: calendar maths, ISO-8601
// parsing, and the /log.csv row format (parse, format, renumber round trip).
// Built and run by tests/firmware/run.sh for each firmware variant.
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <ctime>
#include <string>
struct String { std::string s; String(const char *c) : s(c) {} const char *c_str() const { return s.c_str(); } };
struct RowFields { uint64_t seq; uint32_t boot_id; uint32_t sec_since_boot; float V, I, P, Wh, PF, Hz; uint32_t epoch; };
// Pulled verbatim from the firmware by run.sh (no Arduino/ESP-IDF needed):
// days_from_civil / utc_from_fields / parse_iso8601 from time_source.cpp,
// parse_row / format_row from storage.cpp.
#include "extracted.inc"
int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL " __VA_ARGS__); printf("\n"); } } while (0)
int main() {
  // days_from_civil vs timegm for every day 1970..2105
  setenv("TZ", "UTC0", 1); tzset();
  int n = 0;
  for (int y = 1970; y <= 2105; ++y) for (int m = 1; m <= 12; ++m) for (int d = 1; d <= 31; ++d) {
    struct tm t = {}; t.tm_year = y - 1900; t.tm_mon = m - 1; t.tm_mday = d; t.tm_hour = 13; t.tm_min = 7; t.tm_sec = 59;
    time_t ref = timegm(&t);
    struct tm back; gmtime_r(&ref, &back);
    if (back.tm_mday != d) continue;          // skip invalid dates like Feb 30
    time_t got = utc_from_fields(y, m, d, 13, 7, 59);
    CHECK(got == ref, "%04d-%02d-%02d: %lld != %lld", y, m, d, (long long)got, (long long)ref);
    n++;
  }
  printf("calendar: %d dates compared\n", n);
  // parse_iso8601 with offsets (device TZ = IST)
  setenv("TZ", "IST-5:30", 1); tzset();
  CHECK(parse_iso8601("2026-10-03T13:54:10+05:30") == 1791015850, "+05:30");
  CHECK(parse_iso8601("2026-10-03T08:24:10Z") == 1791015850, "Z");
  CHECK(parse_iso8601("2026-10-03T03:24:10-05:00") == 1791015850, "-05:00");
  CHECK(parse_iso8601("2026-10-03T13:54:10.123+05:30") == 1791015850, "fractional");
  CHECK(parse_iso8601("2026-10-03T13:54:10") == 1791015850, "no offset = local (IST)");
  CHECK(parse_iso8601("garbage") == 0, "garbage");
  // parse_row: v1, v2, v3
  RowFields r;
  CHECK(!parse_row(String("12,3,900,231.50,1.200,250.00,206123.45,0.990"), r), "8-field row rejected");
  CHECK(!parse_row(String("12,3,900,231.50,1.200,250.00,206123.45,0.990,50.01"), r), "9-field row rejected");
  CHECK(!parse_row(String("12,3,900,231.50,1.200,250.00,206123.45,0.990,50.01,1791015850,7"), r), "11-field row rejected");
  CHECK(parse_row(String("12,3,900,231.50,1.200,250.00,206123.45,0.990,50.01,1791015850"), r) && r.epoch == 1791015850u && r.seq == 12 && r.Hz > 50.0f && r.Wh > 206123.4f, "10-field row");
  CHECK(parse_row(String("12,3,900,231.50,1.200,250.00,206123.45,0.990,50.01,0"), r) && r.epoch == 0, "unknown clock");
  CHECK(parse_row(String("12,3,900,231.50,1.200,250.00,206123.45,0.990,50.01,0\r"), r), "trailing CR ok");
  CHECK(!parse_row(String("12,3,900,231.50,1.200"), r), "truncated row rejected");
  // the exact line append_row() now writes, round-tripped
  char line[128];
  snprintf(line, sizeof line, "%llu,%u,%u,%.2f,%.3f,%.2f,%.2f,%.3f,%.2f,%lu",
           12ULL, 3u, 900u, 231.5, 1.2, 250.0, 9999990.0, 0.99, 50.0, 4294967295UL);
  CHECK(parse_row(String(line), r) && r.epoch == 4294967295u, "max epoch round-trip: %s", line);
  {
    RowFields a{}; a.seq = 3; a.boot_id = 1; a.sec_since_boot = 1900; a.V = 231.5f; a.I = 1.2f; a.P = 250.f;
    a.Wh = 9999990.0f; a.PF = 0.99f; a.Hz = 50.01f; a.epoch = 1791015850u;
    char buf[128]; RowFields b;
    int n = format_row(a, buf, sizeof buf);
    CHECK(n > 0 && parse_row(String(buf), b) && b.seq == 3, "format->parse");
    b.seq += 18446744073709551000ULL - 3;      // shift near the top of uint64
    char buf2[128]; RowFields c;
    CHECK(format_row(b, buf2, sizeof buf2) > 0 && parse_row(String(buf2), c) && c.seq == 18446744073709551000ULL
          && c.epoch == a.epoch && c.boot_id == 1 && c.sec_since_boot == 1900 && c.Wh == a.Wh, "renumber round-trip: %s", buf2);
    char tiny[20];
    CHECK(format_row(a, tiny, sizeof tiny) == -1, "too-small buffer reported");
  }
  printf("%d failure(s)\n", fails);
  return fails != 0;
}
