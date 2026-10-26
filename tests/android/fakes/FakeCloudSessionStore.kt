// Fakes for DeviceSyncer tests: the class names and packages match the app's,
// so DeviceSyncer.kt compiles against them unchanged.
package com.dangeedums.solar.data
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
class CloudSessionStore(deviceToken: String = "") {
    data class Settings(val deviceToken: String)
    val settings: Flow<Settings> = flowOf(Settings(deviceToken))
}

/** Saved sign-in, in memory (the app's is Keystore-encrypted). */
class CloudCredentialStore(var saved: Credentials? = null) {
    data class Credentials(val username: String, val password: String)
    suspend fun load(): Credentials? = saved
    suspend fun clear() { saved = null }
}
