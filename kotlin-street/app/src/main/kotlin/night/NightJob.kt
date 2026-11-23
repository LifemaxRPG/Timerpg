package com.timerpg.street.night

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Runs when the phone is plugged in (JobScheduler: requires charging, at any battery level; kept across reboots; in the
 * writer's own process): it starts
 * [NightService]. Android 12+ allows that from the background only when the app's battery use is "Unrestricted";
 * otherwise this job writes itself for as long as Android lets a job run (about 10 minutes), then is scheduled again.
 */
class NightJob : JobService() {
    @Volatile private var mine: NightSession? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val no = when {
            !NightWriter.enabled(this) -> "switched off in the AI panel"
            NightWriter.gameVisible(this) -> "the game is open"
            NightWriter.session != null -> null   // (one already writes: nothing to say)
            WriterLock.heldElsewhere(this) -> "another writer holds the bank"
            else -> ""
        }
        if (no != "") { if (no != null) NightWriter.noteNotStarted(this, "job", no); return false }
        if (NightWriter.startService(this)) return false
        val s = NightWriter.begin(this, "job") { stop -> runCatching { jobFinished(params, false) } } ?: return false
        mine = s
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // the job's time is up, or charging stopped: the session ends; JobScheduler runs the job again later
        mine?.stop("the system ended the job (its time was up, or a condition changed)")
        mine = null
        return true
    }
}
