// Fakes for DeviceSyncer tests: the class names and packages match the app's,
// so DeviceSyncer.kt compiles against them unchanged.
package com.dangeedums.solar.cloud
/** Fake server: enforces the seq rule like ingest.php; can fail after N accepted POSTs. */
class CloudClient(val acceptChunks: Int = Int.MAX_VALUE, val throwOnFail: Boolean = false, var serverMaxSeq: Long = 0,
                  val clashFromChunk: Int = Int.MAX_VALUE) {
    val posts = mutableListOf<IngestPayload>()
    var calls = 0

    // Session side, for CloudSignIn: the server's password, whether the
    // session cookie is still alive, and whether the server is reachable.
    var serverPassword = "pw"
    var sessionAlive = true
    var reachable = true
    var csrf = ""
        private set
    val logins = mutableListOf<String>()
    suspend fun devices(): DevicesResponse {
        if (!reachable) throw java.io.IOException("no route to host")
        return if (sessionAlive) DevicesResponse(ok = true) else DevicesResponse(ok = false, error = "login_required")
    }
    suspend fun login(username: String, password: String): LoginResponse {
        if (!reachable) throw java.io.IOException("no route to host")
        logins += username
        if (password != serverPassword) return LoginResponse(ok = false, error = "invalid_credentials")
        sessionAlive = true; csrf = "tok"
        return LoginResponse(ok = true, username = username, csrf = "tok")
    }
    suspend fun refreshCsrf(): String? { csrf = "tok"; return csrf }
    suspend fun ingest(token: String, payload: IngestPayload): IngestResponse {
        calls++
        if (payload.seqFresh || calls - 1 == clashFromChunk)
            return IngestResponse(ok = false, error = "seq_base_required", seqBase = serverMaxSeq)
        if (posts.size >= acceptChunks) {
            if (throwOnFail) throw java.io.IOException("connection reset")
            return IngestResponse(ok = false, error = "server_error")
        }
        posts += payload
        serverMaxSeq = maxOf(serverMaxSeq, payload.readings.maxOf { it.seq })
        return IngestResponse(ok = true, ackedUpToSeq = payload.readings.maxOf { it.seq })
    }
}
