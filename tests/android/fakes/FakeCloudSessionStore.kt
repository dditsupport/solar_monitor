// Fakes for DeviceSyncer tests: the class names and packages match the app's,
// so DeviceSyncer.kt compiles against them unchanged.
package com.dangeedums.solar.data
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
class CloudSessionStore {
    data class Settings(val deviceToken: String)
    val settings: Flow<Settings> = flowOf(Settings(""))
}
