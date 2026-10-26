package com.dangeedums.solar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangeedums.solar.cloud.ReadingPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChart
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

// Laid out like the web dashboard (backend/public_html/dashboard/index.php):
// controls card with Last sync, four stat cards, the energy chart with the
// meter readings it spans, the per-bar readings table, then power.

private const val DAY_MS = 86_400_000L

/** The web dashboard's green and orange, lighter on a dark background. */
private data class DashColors(val green: Color, val bar: Color, val power: Color)

@Composable
private fun dashColors(): DashColors =
    if (isSystemInDarkTheme()) DashColors(Color(0xFF8BD08F), Color(0xB38BD08F), Color(0xFFE8A45A))
    else DashColors(Color(0xFF1F6E2A), Color(0xB31F6E2A), Color(0xFFC97A1A))

@Composable
fun CloudDashboardScreen(vm: CloudViewModel, onSignOut: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Observable locale: numbers re-format if the user changes language.
    val locale = LocalLocale.current.platformLocale
    val colors = dashColors()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(ui.username, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }

        ControlsCard(ui, vm)

        // Stats, two by two as on the web.
        val spec = ui.spec
        val peakW = ui.points.maxOfOrNull { it.powerPeak ?: 0.0 } ?: 0.0
        val period = ui.totalKwh ?: ui.points.sumOf { it.kwh ?: 0.0 }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Current", ui.currentW?.let { String.format(locale, "%.0f", it) } ?: "—", "W",
                     colors.green, Modifier.weight(1f))
            StatCard("Peak", String.format(locale, "%.0f", peakW), "W", colors.green, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Period total", String.format(locale, "%.2f", period), "kWh", colors.green, Modifier.weight(1f))
            StatCard("Meter reading",
                     ui.meterWh?.let { DashboardRanges.fmtKwh(it / 1000 + ui.readingOffsetKwh, locale) } ?: "—",
                     "kWh", colors.green, Modifier.weight(1f))
        }

        // Energy per bucket, and the meter readings the window spans.
        DashCard {
            Text(spec?.title ?: ui.range.label, style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
            if (spec != null) {
                Caption("kWh / " + DashboardRanges.bucketWord(spec.unit).lowercase())
                EnergyChart(ui.points, spec, colors)
                MeterSpan(ui.points, ui.readingOffsetKwh, locale, colors)
            }
        }

        if (spec != null) ReadingsTable(ui.points, spec, ui.readingOffsetKwh, locale, colors)

        DashCard {
            Text("Power", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (spec != null) {
                Caption("W")
                PowerChart(ui.powerPoints, ui.powerRaw, spec, colors)
            }
        }

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/* ---------- controls: device, ranges, custom dates, last sync ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlsCard(ui: CloudUi, vm: CloudViewModel) {
    DashCard {
        var expanded by remember { mutableStateOf(false) }
        val current = ui.devices.firstOrNull { it.deviceId == ui.selectedDeviceId }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = current?.friendlyName ?: (ui.selectedDeviceId ?: "— pick a device —"),
                onValueChange = {},
                readOnly = true,
                label = { Text("Device") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ui.devices.forEach { d ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(d.friendlyName, fontWeight = FontWeight.Medium)
                                Text(d.deviceId, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { vm.selectDevice(d.deviceId); expanded = false },
                    )
                }
                if (ui.devices.isEmpty()) {
                    DropdownMenuItem(text = { Text("No devices bound to you") }, onClick = { expanded = false })
                }
            }
        }

        // Range buttons in two rows, as on the web: Today / 7 / 30 days, then 12 months / Custom.
        val rows = listOf(listOf(Range.Today, Range.Last7d, Range.Last30d), listOf(Range.Last12m, Range.Custom))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { r ->
                    val on = ui.range == r
                    val mod = Modifier.weight(1f)
                    if (on) FilledTonalButton(onClick = { vm.selectRange(r) }, modifier = mod) { Text(r.label) }
                    else    OutlinedButton(onClick = { vm.selectRange(r) }, modifier = mod) { Text(r.label) }
                }
            }
        }

        if (ui.customOpen) CustomDates(ui, vm)

        LastSync(current?.lastSyncAt)
    }
}

/** Last sync, on its own line: the device's last upload and how long ago. */
@Composable
private fun LastSync(last: String?) {
    // Ticks the "(x min ago)"; only this line recomposes.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30.seconds); now = LocalDateTime.now() }
    }
    val lastAt = last?.let { DashboardRanges.localTime(it) }
    Text(
        buildAnnotatedString {
            append("Last sync: ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                append(lastAt?.format(LAST_SYNC) ?: (last ?: "never"))
            }
            if (lastAt != null) append("  (" + DashboardRanges.ago(lastAt, now) + ")")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDates(ui: CloudUi, vm: CloudViewModel) {
    var picking by remember { mutableStateOf<String?>(null) }   // "from" / "to" / null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) {
            Text("From  " + ui.customFrom.format(PICKED))
        }
        OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) {
            Text("To  " + ui.customTo.format(PICKED))
        }
    }
    Button(onClick = { vm.applyCustom() }, modifier = Modifier.fillMaxWidth()) { Text("Apply") }

    val which = picking ?: return
    // Keyed by field, so the To picker doesn't open on the date just picked for From.
    key(which) { DatePickerFor(which, if (which == "from") ui.customFrom else ui.customTo, vm) { picking = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerFor(which: String, initial: LocalDate, vm: CloudViewModel, onDone: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toEpochDay() * DAY_MS,
        // Can't be pointed at the future, as on the web.
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = Math.floorDiv(utcTimeMillis, DAY_MS) <= today
        },
    )
    DatePickerDialog(
        onDismissRequest = onDone,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    val d = LocalDate.ofEpochDay(Math.floorDiv(ms, DAY_MS))
                    if (which == "from") vm.setCustomFrom(d) else vm.setCustomTo(d)
                }
                onDone()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

/* ---------- stats ---------- */

@Composable
private fun StatCard(label: String, value: String, unit: String, green: Color, modifier: Modifier) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = green)) { append(value) }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = MaterialTheme.typography.bodyMedium.fontSize)) { append(" $unit") }
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/* ---------- energy ---------- */

/** One bar: its X position and kWh, and the X axis label for it. */
private data class Bars(val x: List<Double>, val y: List<Double>, val labels: List<String>)

private fun energyBars(points: List<ReadingPoint>, spec: RangeSpec): Bars {
    val hours = spec.dayHours
    if (hours != null) {
        // Today: X is the hour of day, drawn 06:00-19:00 like the web chart.
        val byHour = points.mapNotNull { p ->
            DashboardRanges.localTime(p.t)?.let { it.hour to (p.kwh ?: 0.0) }
        }.filter { it.first in hours }
        return Bars(byHour.map { it.first.toDouble() }, byHour.map { it.second }, emptyList())
    }
    val labels = points.map { p ->
        DashboardRanges.localTime(p.t)?.let { DashboardRanges.axisLabel(it, spec.unit) } ?: ""
    }
    return Bars(points.indices.map { it.toDouble() }, points.map { it.kwh ?: 0.0 }, labels)
}

@Composable
private fun EnergyChart(points: List<ReadingPoint>, spec: RangeSpec, colors: DashColors) {
    val bars = remember(points, spec) { energyBars(points, spec) }
    val producer = remember { CartesianChartModelProducer() }
    if (bars.x.isEmpty()) {
        Text("No data in range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LaunchedEffect(bars) { producer.runTransaction { columnModel { series(bars.x, bars.y) } } }

    val hours = spec.dayHours
    val formatter = remember(bars, hours) {
        CartesianValueFormatter { _, value, _ ->
            if (hours != null) hourLabel(value.toInt()) else indexLabel(bars.labels, value)
        }
    }
    val spacing = if (hours != null) 2 else labelSpacing(bars.x.size)
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(fill = Fill(colors.bar), thickness = 14.dp),
            ),
            rangeProvider = remember(hours) {
                if (hours != null) CartesianLayerRangeProvider.fixed(minX = hours.first.toDouble(),
                                                                     maxX = hours.last.toDouble())
                else CartesianLayerRangeProvider.auto()
            },
        ),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom(
            valueFormatter = formatter,
            itemPlacer = remember(spacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }) },
        ),
        getXStep = { _, _, _ -> 1.0 },
    )
    FittedChart(chart, producer)
}

/** "Meter reading: a → b kWh (d kWh)" — the window's first start and last end reading. */
@Composable
private fun MeterSpan(points: List<ReadingPoint>, offset: Double, locale: Locale, colors: DashColors) {
    val startWh = points.firstOrNull()?.whStart ?: return
    val endWh = points.lastOrNull()?.whEnd ?: return
    val start = startWh / 1000 + offset
    val end = endWh / 1000 + offset
    Text(
        buildAnnotatedString {
            append("Meter reading: ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                append(DashboardRanges.fmtKwh(start, locale))
            }
            append(" → ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                append(DashboardRanges.fmtKwh(end, locale))
            }
            append(" kWh ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.green)) {
                append("(" + DashboardRanges.fmtKwh(end - start, locale) + " kWh)")
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/* ---------- meter readings per bar ---------- */

@Composable
private fun ReadingsTable(points: List<ReadingPoint>, spec: RangeSpec, offset: Double, locale: Locale,
                          colors: DashColors) {
    DashCard {
        Text("Meter readings per bar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "The cumulative meter reading at the start and end of each bar above — continued from the " +
                "old meter's baseline, so it reads like the physical meter. Generated = end − start, and " +
                "the newest end reading is the Meter reading card above. All values in kWh.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (points.isEmpty()) {
            Text("No readings in this range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@DashCard
        }
        val head = MaterialTheme.typography.labelSmall
        TableRow(DashboardRanges.bucketWord(spec.unit).uppercase(), "GENERATED", "START", "END",
                 style = head, color = MaterialTheme.colorScheme.onSurfaceVariant, mono = false)
        HorizontalDivider()
        // Newest first: with 30 rows the day you care about is the one on top.
        val rows = DashboardRanges.trimIdleEdges(points).asReversed()
        rows.forEach { p ->
            TableRow(
                DashboardRanges.bucketLabel(p.t, spec.unit),
                String.format(locale, "%.3f", p.kwh ?: 0.0),
                DashboardRanges.fmtReading(p.whStart, offset, locale),
                DashboardRanges.fmtReading(p.whEnd, offset, locale),
                genColor = colors.green,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        TableRow("Total", String.format(locale, "%.3f", rows.sumOf { it.kwh ?: 0.0 }), "", "",
                 genColor = colors.green, bold = true)
    }
}

@Composable
private fun TableRow(
    label: String, gen: String, start: String, end: String,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
    genColor: Color = color,
    bold: Boolean = false,
    mono: Boolean = true,
) {
    val numbers = if (mono) FontFamily.Monospace else null
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = color, modifier = Modifier.weight(1.25f),
             fontWeight = if (bold) FontWeight.Bold else null)
        Text(gen, style = style, color = genColor, fontFamily = numbers, fontWeight = FontWeight.SemiBold,
             textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(start, style = style, color = color, fontFamily = numbers, textAlign = TextAlign.End,
             modifier = Modifier.weight(1.2f))
        Text(end, style = style, color = color, fontFamily = numbers, textAlign = TextAlign.End,
             modifier = Modifier.weight(1.2f))
    }
}

/* ---------- power ---------- */

private fun powerSeries(points: List<ReadingPoint>, raw: Boolean, spec: RangeSpec): Bars {
    if (!raw) {
        val labels = points.map { p ->
            DashboardRanges.localTime(p.t)?.let { DashboardRanges.axisLabel(it, spec.unit) } ?: ""
        }
        return Bars(points.indices.map { it.toDouble() }, points.map { it.powerAvg ?: 0.0 }, labels)
    }
    // Raw rows: X is minutes since the window start, so the line keeps the
    // device's real logging resolution. Today is drawn 06:00-19:00.
    val window = spec.dayHours?.let { (it.first * 60)..((it.last + 1) * 60) }
    val pts = points.mapNotNull { p ->
        val t = DashboardRanges.localTime(p.t) ?: return@mapNotNull null
        val m = ChronoUnit.MINUTES.between(spec.from, t).toInt()
        if (window != null && m !in window) null else m.toDouble() to (p.power ?: p.powerAvg ?: 0.0)
    }.distinctBy { it.first }
    return Bars(pts.map { it.first }, pts.map { it.second }, emptyList())
}

@Composable
private fun PowerChart(points: List<ReadingPoint>, raw: Boolean, spec: RangeSpec, colors: DashColors) {
    val series = remember(points, raw, spec) { powerSeries(points, raw, spec) }
    val producer = remember { CartesianChartModelProducer() }
    if (series.x.isEmpty()) {
        Text("No data in range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LaunchedEffect(series) { producer.runTransaction { lineModel { series(series.x, series.y) } } }

    val window = if (raw) spec.dayHours?.let { (it.first * 60).toDouble() to ((it.last + 1) * 60).toDouble() } else null
    val formatter = remember(series, raw, spec) {
        CartesianValueFormatter { _, value, _ ->
            // Labels sit on whole hours (the item placer's spacing); Vico also
            // formats other x values to measure label widths, so never blank.
            if (raw) DashboardRanges.axisLabel(spec.from.plusMinutes(value.toLong()), XUnit.Hour)
            else indexLabel(series.labels, value)
        }
    }
    // Raw: one label every 2 h on Today (X step is one minute), every 6 h on a custom day or two.
    val spacing = when {
        raw && spec.dayHours != null -> 120
        raw -> 360
        else -> labelSpacing(series.x.size)
    }
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(colors.power))),
            ),
            rangeProvider = remember(window) {
                if (window != null) CartesianLayerRangeProvider.fixed(minX = window.first, maxX = window.second)
                else CartesianLayerRangeProvider.auto()
            },
        ),
        startAxis = VerticalAxis.rememberStart(),
        bottomAxis = HorizontalAxis.rememberBottom(
            valueFormatter = formatter,
            itemPlacer = remember(spacing) { HorizontalAxis.ItemPlacer.aligned(spacing = { spacing }) },
        ),
        getXStep = { _, _, _ -> 1.0 },
    )
    FittedChart(chart, producer)
}

/* ---------- shared bits ---------- */

/** The whole range on screen at once, as on the web: no sideways scrolling or zooming. */
@Composable
private fun FittedChart(chart: CartesianChart, producer: CartesianChartModelProducer) {
    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(200.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}

/**
 * The label for bar [value]. Vico throws if an axis formatter returns a blank
 * string (it formats in-between values too, to measure label widths), so a
 * missing label falls back to the bar's number.
 */
private fun indexLabel(labels: List<String>, value: Double): String {
    val i = value.toInt()
    return labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: (i + 1).toString()
}

/** Every bar labelled up to 7 bars, then every few (30 days: every 5th). */
private fun labelSpacing(n: Int): Int = maxOf(1, ceil(n / 7.0).toInt())

private fun hourLabel(h: Int): String =
    DashboardRanges.axisLabel(LocalDate.now().atTime(h.coerceIn(0, 23), 0), XUnit.Hour)

@Composable
private fun DashCard(content: @Composable () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val LAST_SYNC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val PICKED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
