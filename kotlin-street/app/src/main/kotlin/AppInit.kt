package com.timerpg.street

import android.content.Context

/**
 * What every start of the app's process needs once, whoever starts it (the game, or the overnight writer with the game
 * closed): the breadcrumbs and the crash handler, and before the native library loads, the NPU's code path and its
 * tuned settings ([NpuTune.apply]).
 */
object AppInit {
    private var basics = false
    private var native = false

    @Synchronized
    fun basics(ctx: Context) {
        if (basics) return
        basics = true
        com.timerpg.street.shot.Crumbs.init(ctx.applicationContext)
        CrashReport.install(ctx.applicationContext)
    }

    /** Before the dialogue model's library loads (it reads these once per process). Not while the NPU is being tuned
     *  by someone else than the game (the tuner's own start does this). */
    @Synchronized
    fun native(ctx: Context) {
        if (native) return
        native = true
        // the Hexagon NPU's code (libggml-htp-v79.so) is in this app's native library folder: tell the DSP loader
        runCatching {
            val dsp = ctx.applicationInfo.nativeLibraryDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"
            android.system.Os.setenv("ADSP_LIBRARY_PATH", dsp, true)
        }
        // the NPU backend's settings (the tuned ones, or the one being tried while tuning)
        NpuTune.apply(ctx.applicationContext)
    }
}
