package com.dangeedums.solar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dangeedums.solar.SolarApp
import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.cloud.CloudDevice
import com.dangeedums.solar.cloud.CloudSignIn
import com.dangeedums.solar.cloud.DevicesResponse
import com.dangeedums.solar.cloud.ReadingPoint
import com.dangeedums.solar.data.CloudCredentialStore
import com.dangeedums.solar.data.CloudSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class CloudUi(
    val loggedIn: Boolean = false,
    val username: String = "",
    val baseUrl: String = "https://solar.aromen.biz",
    val devices: List<CloudDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val range: Range = Range.Today,
    // Custom: the From/To filter shows only after tapping Custom, and Apply
    // loads it (the chart doesn't change while dates are being picked).
    val customOpen: Boolean = false,
    val customFrom: LocalDate = LocalDate.now().minusDays(6),
    val customTo: LocalDate = LocalDate.now(),
    /** The window the data below belongs to (null until the first load). */
    val spec: RangeSpec? = null,
    /** Energy buckets: kwh, wh_start / wh_end, P_avg / P_peak. */
    val points: List<ReadingPoint> = emptyList(),
    /** The power chart's series: raw rows (P) on Today / short custom ranges, else the buckets (P_avg). */
    val powerPoints: List<ReadingPoint> = emptyList(),
    val powerRaw: Boolean = false,
    /** Period total: what the meter generated inside the window (end - start reading). */
    val totalKwh: Double? = null,
    /** Old-meter baseline + manual adjustment (kWh), added to every printed meter reading. */
    val readingOffsetKwh: Double = 0.0,
    /** The device's newest cumulative reading (Wh, before the offset): the Meter reading card. */
    val meterWh: Double? = null,
    /** Latest raw power in the last hour (W): the Current card. */
    val currentW: Double? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class CloudViewModel(
    application: Application,
    private val client: CloudClient,
    private val session: CloudSessionStore,
    private val signIn: CloudSignIn,
    private val credentials: CloudCredentialStore,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(CloudUi())
    val ui: StateFlow<CloudUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val s = session.settings.first()
            client.setBaseUrl(s.baseUrl)
            _ui.value = _ui.value.copy(baseUrl = s.baseUrl, username = s.username)
            // A still-valid session cookie, or one renewed with the saved
            // sign-in, opens straight on the dashboard; otherwise the user
            // lands on the login screen.
            tryAutoFetch()
        }
    }

    private suspend fun tryAutoFetch() {
        // Also refreshes the CSRF token, which a process restart wipes.
        if (signIn.ensureSignedIn() != CloudSignIn.Status.SignedIn) return
        runCatching { client.devices() }
            .onSuccess { resp ->
                if (resp.ok) {
                    _ui.value = _ui.value.copy(
                        loggedIn = true,
                        devices = resp.devices,
                        selectedDeviceId = resp.devices.firstOrNull()?.deviceId,
                    )
                    refreshChart()
                }
            }
    }

    fun setBaseUrl(url: String) {
        _ui.value = _ui.value.copy(baseUrl = url)
        client.setBaseUrl(url)
        viewModelScope.launch { session.update(baseUrl = url) }
    }

    /**
     * @param remember keep the sign-in (password encrypted with a Keystore
     *   key) so syncs can renew an expired session without asking again.
     */
    fun login(username: String, password: String, remember: Boolean) {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { client.login(username, password) }
                .onSuccess { resp ->
                    if (resp.ok) {
                        session.update(username = username)
                        if (remember) credentials.save(username, password) else credentials.clear()
                        _ui.value = _ui.value.copy(loggedIn = true, username = username, loading = false)
                        refreshDevices()
                    } else {
                        _ui.value = _ui.value.copy(loading = false, error = resp.error ?: "login failed")
                    }
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "network error") }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Signing out also forgets the saved sign-in, so no sync renews it.
            credentials.clear()
            runCatching { client.logout() }
            _ui.value = CloudUi(baseUrl = _ui.value.baseUrl)
        }
    }

    fun refreshDevices() {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { devicesRenewingSession() }
                .onSuccess { resp ->
                    if (!resp.ok) {
                        // Session cookie is missing/expired server-side. The request
                        // itself succeeded (valid JSON came back), so this would
                        // otherwise silently look like "you have zero devices" —
                        // kick back to the login screen instead.
                        _ui.value = CloudUi(
                            baseUrl = _ui.value.baseUrl,
                            username = _ui.value.username,
                            error = resp.error ?: "session expired, please sign in again",
                        )
                        return@onSuccess
                    }
                    val selected = _ui.value.selectedDeviceId
                        ?: resp.devices.firstOrNull()?.deviceId
                    _ui.value = _ui.value.copy(
                        devices = resp.devices,
                        selectedDeviceId = selected,
                        loading = false,
                    )
                    refreshChart()
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "fetch failed") }
        }
    }

    /** devices.php, renewing an expired session with the saved sign-in once. */
    private suspend fun devicesRenewingSession(): DevicesResponse {
        val first = client.devices()
        if (first.ok) return first
        return if (signIn.ensureSignedIn() == CloudSignIn.Status.SignedIn) client.devices() else first
    }

    fun selectDevice(id: String) {
        _ui.value = _ui.value.copy(selectedDeviceId = id)
        refreshChart()
    }

    /** A fixed range loads straight away and puts the date filter away; Custom only opens it. */
    fun selectRange(r: Range) {
        if (r == Range.Custom) {
            _ui.value = _ui.value.copy(customOpen = !_ui.value.customOpen)
            return
        }
        _ui.value = _ui.value.copy(range = r, customOpen = false)
        refreshChart()
    }

    fun setCustomFrom(d: LocalDate) { _ui.value = _ui.value.copy(customFrom = d) }
    fun setCustomTo(d: LocalDate) { _ui.value = _ui.value.copy(customTo = d) }

    /** Apply on the date filter. Reversed dates leave the current view alone. */
    fun applyCustom() {
        if (DashboardRanges.custom(_ui.value.customFrom, _ui.value.customTo) == null) {
            _ui.value = _ui.value.copy(error = "Pick a From and To date, with From on or before To.")
            return
        }
        _ui.value = _ui.value.copy(range = Range.Custom)
        refreshChart()
    }

    // Bumped on every refreshChart(): a reply that lands after a newer range
    // was asked for is dropped, so tapping 30 days then Today quickly can't
    // draw the slower 30-day data under "Today".
    private var loadSeq = 0

    fun refreshChart() {
        val dev  = _ui.value.selectedDeviceId ?: return
        val now  = LocalDateTime.now()
        val spec = DashboardRanges.spec(_ui.value.range, now, _ui.value.customFrom, _ui.value.customTo) ?: return
        val seq  = ++loadSeq
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val from = spec.from.format(LocalIso)
                val to   = spec.to?.format(LocalIso)
                val energy = client.readings(dev, spec.aggregate, fromIso = from, toIso = to)
                // Power from its own, finer query where the range asks for it.
                val power = spec.powerAggregate?.let { agg ->
                    runCatching { client.readings(dev, agg, fromIso = from, toIso = to) }.getOrNull()
                        ?.takeIf { it.ok && it.points.isNotEmpty() }
                }
                // Current: the newest raw reading of the last hour.
                val live = runCatching {
                    client.readings(dev, "raw", fromIso = now.minusHours(1).format(LocalIso))
                }.getOrNull()
                Triple(energy, power, live)
            }.onSuccess { (energy, power, live) ->
                if (seq != loadSeq) return@onSuccess
                if (!energy.ok) {
                    _ui.value = _ui.value.copy(loading = false, error = energy.error ?: "fetch failed")
                    return@onSuccess
                }
                _ui.value = _ui.value.copy(
                    loading = false,
                    spec = spec,
                    points = energy.points,
                    powerPoints = power?.points ?: energy.points,
                    powerRaw = power != null,
                    totalKwh = energy.totalKwh,
                    readingOffsetKwh = (energy.capacityKw ?: 0.0) + (energy.adjustmentKwh ?: 0.0),
                    meterWh = energy.meterWh,
                    currentW = live?.takeIf { it.ok }?.points?.lastOrNull()?.power,
                )
            }.onFailure {
                if (seq == loadSeq) _ui.value = _ui.value.copy(loading = false, error = it.message ?: "fetch failed")
            }
        }
    }

    companion object {
        private val LocalIso: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application as SolarApp
                CloudViewModel(application, app.cloudClient, app.cloudSessionStore,
                               app.cloudSignIn, app.cloudCredentials)
            }
        }
    }
}
