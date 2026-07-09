package com.dangeedums.solar

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.runBlocking

/**
 * Ends the cloud session when the user actually closes the app.
 *
 * The app deliberately keeps the PHP session cookie across mere backgrounding
 * (home button, app switch) so the user stays signed in while multitasking —
 * they only get logged out when they explicitly sign out in-app or when they
 * swipe the app away from the recent-apps list. That swipe is delivered here as
 * [onTaskRemoved], which runs while the process is still alive.
 *
 * MainActivity starts this service so it is running (idle) for the lifetime of
 * the process; it does no work until the task is removed.
 */
class SessionGuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // START_NOT_STICKY: this is a passive marker service. If the system kills
    // the process for memory (not a user swipe), we do NOT want it — or the
    // logout — resurrected; the persisted cookie should still auto-restore the
    // session on the next launch.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as SolarApp
        // logoutOnExit() clears the local session first (fast, unbounded) and
        // then makes a time-bounded best-effort server logout call, so this
        // blocks only briefly before the process goes away.
        runBlocking { app.cloudClient.logoutOnExit() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
