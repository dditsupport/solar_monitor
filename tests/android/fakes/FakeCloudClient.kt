// Fakes for DeviceSyncer tests: the class names and packages match the app's,
// so DeviceSyncer.kt compiles against them unchanged.
package com.dangeedums.solar.cloud
/** Fake server: enforces the seq rule like ingest.php; can fail after N accepted POSTs. */
class CloudClient(val acceptChunks: Int = Int.MAX_VALUE, val throwOnFail: Boolean = false, var serverMaxSeq: Long = 0,
                  val clashFromChunk: Int = Int.MAX_VALUE) {
    val posts = mutableListOf<IngestPayload>()
    var calls = 0
    suspend fun ingest(token: String, payload: IngestPayload): IngestResponse {
        calls++
        if (payload.seq_fresh || calls - 1 == clashFromChunk)
            return IngestResponse(ok = false, error = "seq_base_required", seq_base = serverMaxSeq)
        if (posts.size >= acceptChunks) {
            if (throwOnFail) throw java.io.IOException("connection reset")
            return IngestResponse(ok = false, error = "server_error")
        }
        posts += payload
        serverMaxSeq = maxOf(serverMaxSeq, payload.readings.maxOf { it.seq })
        return IngestResponse(ok = true, acked_up_to_seq = payload.readings.maxOf { it.seq })
    }
}
