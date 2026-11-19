package com.timerpg.street.night

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Runs when the phone is plugged in (JobScheduler: requires charging, battery not low; kept across reboots): it starts
 * [NightService]. Android 12+ allows that from the background only when the app's battery use is "Unrestricted";
 * otherwise this job writes itself for as long as Android lets a job run (about 10 minutes), then is scheduled again.
 */
class NightJob : JobService() {
    @Volatile private var mine: NightSession? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!NightWriter.enabled(this) || NightWriter.gameOpen || NightWriter.session != null) return false
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
