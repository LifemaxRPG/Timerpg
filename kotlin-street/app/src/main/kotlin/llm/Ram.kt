package com.timerpg.street.llm

import android.app.ActivityManager
import android.content.Context

/**
 * The phone's free memory as Android counts it (what its low-memory killer watches). On the NPU the dialogue model's
 * 5.4 GB sits in memory shared with the NPU, outside the game's own count, so the other models check this before they
 * load rather than trusting the game's size.
 */
object Ram {
    fun freeMb(ctx: Context): Long = try {
        val am = ctx.getSystemService(ActivityManager::class.java)
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem shr 20
    } catch (e: Exception) { Long.MAX_VALUE }

    /** What must stay free beside anything we load (Android starts killing the app on screen not far below this). */
    const val MARGIN_MB = 900L
}
