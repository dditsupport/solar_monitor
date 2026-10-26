package com.dangeedums.solar.cloud

import com.dangeedums.solar.data.CloudCredentialStore
import com.dangeedums.solar.data.CloudSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes sure the app holds a live cloud session before anything is read off a
 * device. Relayed rows are uploaded with the signed-in session, so without one
 * a sync could only connect, stream and then fail at the upload.
 *
 * A dead session (expired on the server) is renewed with the saved sign-in,
 * if the user chose to remember it.
 */
class CloudSignIn(
    private val client: CloudClient,
    private val credentials: CloudCredentialStore,
    private val session: CloudSessionStore,
) {
    enum class Status { SignedIn, NeedsSignIn, Unreachable }

    private val mutex = Mutex()

    suspend fun ensureSignedIn(): Status = mutex.withLock {
        // A device token authorises uploads on its own, no session needed.
        if (session.settings.first().deviceToken.isNotBlank()) return Status.SignedIn

        val probe = try {
            client.devices()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            return Status.Unreachable
        }
        if (probe.ok) {
            if (client.csrf.isBlank()) client.refreshCsrf()
            return Status.SignedIn
        }

        val saved = credentials.load() ?: return Status.NeedsSignIn
        val login = try {
            client.login(saved.username, saved.password)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            return Status.Unreachable
        }
        when {
            login.ok -> Status.SignedIn
            login.error == "invalid_credentials" -> {
                // Password changed on the server: stop retrying it on every sync.
                credentials.clear()
                Status.NeedsSignIn
            }
            else -> Status.NeedsSignIn
        }
    }
}
