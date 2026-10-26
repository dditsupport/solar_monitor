import com.dangeedums.solar.cloud.ReadingPoint
import com.dangeedums.solar.ui.DashboardRanges
import com.dangeedums.solar.ui.Range
import com.dangeedums.solar.ui.XUnit
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.TimeZone
import kotlin.test.*

/** The app's Cloud dashboard must window and label data like the web dashboard. */
class DashboardRangesTest {
    init { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")) }
    private val now = LocalDateTime.of(2026, 3, 31, 14, 20)

    @Test fun `fixed ranges start where the web dashboard's do`() {
        val today = DashboardRanges.spec(Range.Today, now, null, null)!!
        assertEquals(listOf("hourly", "raw"), listOf(today.aggregate, today.powerAggregate))
        assertEquals(LocalDateTime.of(2026, 3, 31, 0, 0), today.from)
        assertEquals(6..18, today.dayHours, "X axis 06:00-19:00")
        // whole days, today included: local midnight 6 / 29 days back
        assertEquals(LocalDateTime.of(2026, 3, 25, 0, 0), DashboardRanges.spec(Range.Last7d, now, null, null)!!.from)
        assertEquals(LocalDateTime.of(2026, 3, 2, 0, 0), DashboardRanges.spec(Range.Last30d, now, null, null)!!.from)
        // the 1st of the month 11 months back, not "Mar 31 minus 11 months"
        val m12 = DashboardRanges.spec(Range.Last12m, now, null, null)!!
        assertEquals(LocalDateTime.of(2025, 4, 1, 0, 0), m12.from)
        assertEquals("monthly", m12.aggregate)
    }

    @Test fun `custom bucket size follows the span, reversed dates are refused`() {
        val d = LocalDate.of(2026, 10, 1)
        assertEquals("hourly", DashboardRanges.custom(d, d)!!.aggregate)
        assertEquals("raw", DashboardRanges.custom(d, d)!!.powerAggregate)
        assertEquals("hourly", DashboardRanges.custom(d, d.plusDays(1))!!.aggregate)
        assertEquals("daily", DashboardRanges.custom(d, d.plusDays(2))!!.aggregate)
        // span = From 00:00 .. To 23:59:59, as the web computes it: From+91 days
        // is 91.99 days (daily), From+92 is 92.99 (monthly)
        assertEquals("daily", DashboardRanges.custom(d, d.plusDays(91))!!.aggregate)
        assertEquals("monthly", DashboardRanges.custom(d, d.plusDays(92))!!.aggregate)
        assertNull(DashboardRanges.custom(d, d.minusDays(1)))
        assertNull(DashboardRanges.spec(Range.Custom, now, d, d.minusDays(1)))
        val c = DashboardRanges.custom(d, d.plusDays(6))!!
        assertEquals("1 Oct 2026 – 7 Oct 2026", c.title)
        assertEquals(LocalDateTime.of(2026, 10, 7, 23, 59, 59), c.to)
    }

    @Test fun `idle night hours are trimmed from the table, generation in between is kept`() {
        fun p(k: Double) = ReadingPoint(t = "x", kwh = k)
        val pts = listOf(p(0.0), p(0.0), p(0.4), p(0.0), p(1.1), p(0.0))
        assertEquals(listOf(0.4, 0.0, 1.1), DashboardRanges.trimIdleEdges(pts).map { it.kwh })
        val none = listOf(p(0.0), p(0.0))
        assertEquals(none, DashboardRanges.trimIdleEdges(none))
    }

    @Test fun `labels and numbers read like the web page`() {
        assertEquals("Oct 26, 11 AM", DashboardRanges.bucketLabel("2026-10-26T11:00:00+05:30", XUnit.Hour))
        assertEquals("Oct 5", DashboardRanges.bucketLabel("2026-10-05T00:00:00+05:30", XUnit.Day))
        assertEquals("Oct 2026", DashboardRanges.bucketLabel("2026-10-01T00:00:00+05:30", XUnit.Month))
        assertEquals("6AM", DashboardRanges.axisLabel(LocalDateTime.of(2026, 10, 26, 6, 0), XUnit.Hour))
        assertEquals("21,809.72", DashboardRanges.fmtKwh(21809.7249, Locale.US))
        assertEquals("21809.720", DashboardRanges.fmtReading(21_809_720.0, 0.0, Locale.US))
        assertEquals("21811.220", DashboardRanges.fmtReading(21_809_720.0, 1.5, Locale.US))
        assertEquals("—", DashboardRanges.fmtReading(null, 0.0, Locale.US))
        // Last sync from devices.php ("Y-m-d H:i:s", server time)
        val last = DashboardRanges.localTime("2026-10-26 18:27:31")!!
        assertEquals("16 min ago", DashboardRanges.ago(last, last.plusMinutes(16)))
        assertEquals("45s ago", DashboardRanges.ago(last, last.plusSeconds(45)))
        assertEquals("3 h ago", DashboardRanges.ago(last, last.plusHours(3)))
        assertEquals("2 d ago", DashboardRanges.ago(last, last.plusDays(2)))
    }
}
