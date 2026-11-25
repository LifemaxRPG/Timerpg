package com.timerpg.street.audio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The sound's settings (the Settings tab's "Sound" rows; docs/SOUND.md): the footstep set (A..E), engines made live or
 * from recordings, and the volumes (all, ambience, effects, vehicles, music). Kept in the "sound" preferences.
 */
object SoundSettings {
    var footsteps by mutableStateOf(Footsteps.DEFAULT)
    /** Engines from recorded loops instead of made live (docs/SOUND.md: the comparison). */
    var recordedEngines by mutableStateOf(false)
    var master by mutableFloatStateOf(1f)
    var ambience by mutableFloatStateOf(1f)
    var effects by mutableFloatStateOf(1f)
    var vehicles by mutableFloatStateOf(1f)
    var music by mutableFloatStateOf(1f)
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("sound", Context.MODE_PRIVATE)
        footsteps = p.getString("footsteps", Footsteps.DEFAULT)?.takeIf { s -> Footsteps.SETS.any { it.id == s } } ?: Footsteps.DEFAULT
        recordedEngines = p.getBoolean("recordedEngines", false)
        master = p.getFloat("master", 1f); ambience = p.getFloat("ambience", 1f); effects = p.getFloat("effects", 1f)
        vehicles = p.getFloat("vehicles", 1f); music = p.getFloat("music", 1f)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("sound", Context.MODE_PRIVATE).edit().putString("footsteps", footsteps).putBoolean("recordedEngines", recordedEngines)
            .putFloat("master", master).putFloat("ambience", ambience).putFloat("effects", effects).putFloat("vehicles", vehicles).putFloat("music", music).apply()
    }
}
