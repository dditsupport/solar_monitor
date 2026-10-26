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

enum class Range(val label: String, val aggregate: String) {
    Today  ("Today",   "hourly"),
    Last24h("24 h",    "hourly"),
    Last7d ("7 days",  "daily"),
    Last30d("30 days", "daily"),
    Last12m("12 mo",   "monthly"),
}

data class CloudUi(
    val loggedIn: Boolean = false,
    val username: String = "",
    val baseUrl: String = "https://solar.aromen.biz",
    val devices: List<CloudDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val range: Range = Range.Today,
    val points: List<ReadingPoint> = emptyList(),
    // Period total = generated (total_kwh) + old-meter baseline (capacity_kw) +
    // adjustment (adjustment_kwh), mirroring the web dashboard. totalKwh is null
    // if the server didn't return it (older backend) — fall back to summing bars.
    val totalKwh: Double? = null,
    val baselineKwh: Double = 0.0,
    val adjustmentKwh: Double = 0.0,
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

    fun selectRange(r: Range) {
        _ui.value = _ui.value.copy(range = r)
        refreshChart()
    }

    fun refreshChart() {
        val dev = _ui.value.selectedDeviceId ?: return
        val r   = _ui.value.range
        val fromIso = when (r) {
            Range.Today   -> LocalDate.now().atStartOfDay().format(LocalIso)
            Range.Last24h -> LocalDateTime.now().minusHours(24).format(LocalIso)
            Range.Last7d  -> LocalDateTime.now().minusDays(7).format(LocalIso)
            Range.Last30d -> LocalDateTime.now().minusDays(30).format(LocalIso)
            Range.Last12m -> LocalDateTime.now().minusMonths(12).format(LocalIso)
        }
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { client.readings(dev, r.aggregate, fromIso = fromIso) }
                .onSuccess { resp ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        points = resp.points,
                        totalKwh = resp.totalKwh,
                        baselineKwh = resp.capacityKw ?: 0.0,
                        adjustmentKwh = resp.adjustmentKwh ?: 0.0,
                    )
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "fetch failed") }
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
