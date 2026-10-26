package com.dangeedums.solar.ui

import com.dangeedums.solar.cloud.ReadingPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// The Cloud dashboard's ranges and number formatting, kept in step with the
// web dashboard (backend/public_html/dashboard/index.php). Plain Kotlin, no
// Android, so the JVM tests in tests/android cover it.

enum class Range(val label: String) {
    Today("Today"),
    Last7d("7 days"),
    Last30d("30 days"),
    Last12m("12 months"),
    Custom("Custom"),
}

enum class XUnit { Hour, Day, Month }

/** What one range asks readings.php for, and how it is drawn. */
data class RangeSpec(
    val aggregate: String,
    /** A finer series for the power chart ("raw"), or null to use the energy buckets' P_avg. */
    val powerAggregate: String?,
    val from: LocalDateTime,
    val to: LocalDateTime?,
    val unit: XUnit,
    val title: String,
    /** Today only: the hours drawn on the X axis, 06:00–19:00 as on the web. */
    val dayHours: IntRange?,
)

object DashboardRanges {

    /** The window for [range]; null for Custom with dates missing or reversed. */
    fun spec(range: Range, now: LocalDateTime, customFrom: LocalDate?, customTo: LocalDate?): RangeSpec? {
        val today = now.toLocalDate().atStartOfDay()
        return when (range) {
            // Energy per hour; power from raw rows, at the device's real logging
            // resolution instead of one flat hourly average.
            Range.Today -> RangeSpec("hourly", "raw", today, null, XUnit.Hour,
                                     "Today's energy (per hour)", 6..18)
            // Whole calendar days / months, today's (this month's) included.
            Range.Last7d  -> RangeSpec("daily", null, today.minusDays(6), null, XUnit.Day, "Last 7 days", null)
            Range.Last30d -> RangeSpec("daily", null, today.minusDays(29), null, XUnit.Day, "Last 30 days", null)
            Range.Last12m -> RangeSpec("monthly", null, today.withDayOfMonth(1).minusMonths(11), null,
                                       XUnit.Month, "Last 12 months", null)
            Range.Custom -> if (customFrom == null || customTo == null) null else custom(customFrom, customTo)
        }
    }

    /**
     * A range from the two date pickers, From 00:00 to To 23:59:59. The bucket
     * size follows the span — hours for up to two days, days up to about a
     * quarter, months beyond — so it is drawn like the fixed range of that length.
     */
    fun custom(from: LocalDate, to: LocalDate): RangeSpec? {
        if (to.isBefore(from)) return null
        val start = from.atStartOfDay()
        val end = to.atTime(23, 59, 59)
        val days = ChronoUnit.SECONDS.between(start, end) / 86400.0
        val (agg, unit) = when {
            days <= 2  -> "hourly" to XUnit.Hour
            days <= 92 -> "daily" to XUnit.Day
            else       -> "monthly" to XUnit.Month
        }
        val title = DAY_TEXT.format(from) + " – " + DAY_TEXT.format(to)
        return RangeSpec(agg, if (agg == "hourly") "raw" else null, start, end, unit, title, null)
    }

    /** The unit of an energy bar, for the chart's Y axis title and the table head. */
    fun bucketWord(unit: XUnit): String = when (unit) {
        XUnit.Hour -> "Hour"; XUnit.Day -> "Day"; XUnit.Month -> "Month"
    }

    /**
     * Drops leading/trailing buckets where the meter didn't move (the night
     * hours on Today), keeping everything in between so the rows still sum to
     * the range's generation. A range with no generation at all is kept whole.
     */
    fun trimIdleEdges(points: List<ReadingPoint>): List<ReadingPoint> {
        val first = points.indexOfFirst { (it.kwh ?: 0.0) > 0 }
        if (first < 0) return points
        val last = points.indexOfLast { (it.kwh ?: 0.0) > 0 }
        return points.subList(first, last + 1)
    }

    /** A server timestamp ("2026-10-26T06:00:00+05:30") in the phone's time zone. */
    fun localTime(iso: String): LocalDateTime? =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }
            .getOrNull()
            ?: runCatching { LocalDateTime.parse(iso.replace(' ', 'T').take(19)) }.getOrNull()

    /** A table row's label: "Oct 26, 11 AM", "Oct 26" or "Oct 2026". */
    fun bucketLabel(iso: String, unit: XUnit): String {
        val t = localTime(iso) ?: return iso
        return when (unit) {
            XUnit.Hour  -> ROW_DAY.format(t) + ", " + ROW_HOUR.format(t)
            XUnit.Day   -> ROW_DAY.format(t)
            XUnit.Month -> ROW_MONTH.format(t)
        }
    }

    /** An X axis label: "6AM", "Oct 26" or "Oct". */
    fun axisLabel(t: LocalDateTime, unit: XUnit): String = when (unit) {
        XUnit.Hour  -> AXIS_HOUR.format(t)
        XUnit.Day   -> ROW_DAY.format(t)
        XUnit.Month -> AXIS_MONTH.format(t)
    }

    /** Grouped thousands, two decimals: "21,809.72". */
    fun fmtKwh(kwh: Double, locale: Locale): String = String.format(locale, "%,.2f", kwh)

    /** A meter reading in kWh (three decimals) with the old-meter offset; "—" if unknown. */
    fun fmtReading(wh: Double?, offsetKwh: Double, locale: Locale): String =
        if (wh == null) "—" else String.format(locale, "%.3f", wh / 1000 + offsetKwh)

    /** "45s ago", "16 min ago", "3 h ago", "2 d ago" — as the web page's Last sync. */
    fun ago(then: LocalDateTime, now: LocalDateTime): String {
        val secs = maxOf(0L, ChronoUnit.SECONDS.between(then, now))
        return when {
            secs < 60     -> "${secs}s ago"
            secs < 3600   -> "${Math.round(secs / 60.0)} min ago"
            secs < 86400  -> "${Math.round(secs / 3600.0)} h ago"
            else          -> "${Math.round(secs / 86400.0)} d ago"
        }
    }

    private val DAY_TEXT   = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val ROW_DAY    = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private val ROW_HOUR   = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)
    private val ROW_MONTH  = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val AXIS_HOUR  = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH)
    private val AXIS_MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
}
