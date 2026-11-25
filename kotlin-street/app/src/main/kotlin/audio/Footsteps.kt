package com.timerpg.street.audio

import com.lifemax.rpg.feature.habits.presentation.scene.Surface

/**
 * Which footstep sound plays: the set the player chose in Settings (A..E, docs/SOUND.md "Footsteps"), the surface underfoot
 * (pavement, road, lawn, gravel, a deck, a room's floor or carpet, snow in winter), a variation never the same as the last
 * two, a little pitch and loudness jitter, the feet alternating left and right, and running a touch louder and brighter.
 * Pure (no Android), so it is unit-tested; [SoundScape] plays what it picks. Recorded sets are files
 * assets/audio/step/<set>/<surface>_<nn>.ogg; set E is made live ([StepSynth]).
 */
object Footsteps {
    /** The sets, by their letter in Settings: what each is (the label) and where it comes from. */
    class Set(val id: String, val label: String, val source: String, val synth: Boolean = false)

    val SETS = listOf(
        Set("a", "A: field recordings", "Freesound CC0 field recordings (florianreichelt, kyles, Fission9 and others)"),
        Set("b", "B: studio foley", "swuing's foley via congusbongus (CC-BY 3.0) and Ali_6868's gravel (CC0)"),
        Set("c", "C: game packs", "OpenGameArt CC0 packs (Kenney, Fantozzi, TinyWorlds, rubberduck, Corsica_S)"),
        Set("d", "D: the old steps", "the steps made with MOSS-SoundEffect (the game's first set)"),
        Set("e", "E: synthesized", "made live by the game's own code (StepSynth): never the same step twice", synth = true),
    )

    /** The set used until the player picks one (docs/SOUND.md: why). */
    const val DEFAULT = "a"

    /** The surfaces a step can sound of. */
    val SURFACES = listOf("concrete", "asphalt", "grass", "gravel", "wood", "floor", "carpet", "snow")

    /** What is underfoot as a sound: the street's [surface] ([Surface]), indoors (a floor or carpet), snow outdoors in winter. */
    fun surfaceName(surface: Int, indoors: Boolean, carpet: Boolean, snow: Boolean): String = when {
        indoors -> if (carpet) "carpet" else "floor"
        snow && surface != Surface.WOOD -> "snow"
        surface == Surface.GRASS -> "grass"
        surface == Surface.WOOD -> "wood"
        surface == Surface.GRAVEL -> "gravel"
        surface == Surface.ASPHALT -> "asphalt"
        surface == Surface.CARPET -> "carpet"
        else -> "concrete"
    }

    /** The nearest surfaces to try when a set has no sounds of one (the first with any plays). */
    fun fallbacks(surface: String): List<String> = when (surface) {
        "asphalt" -> listOf("asphalt", "concrete")
        "floor" -> listOf("floor", "wood", "concrete")
        "carpet" -> listOf("carpet", "floor", "grass")
        "gravel" -> listOf("gravel", "snow", "grass")
        "snow" -> listOf("snow", "gravel", "grass")
        "wood" -> listOf("wood", "floor", "concrete")
        "grass" -> listOf("grass", "gravel")
        else -> listOf(surface, "concrete")
    }

    /** The file of variation [n] (0-based) of [surface] in [set]. */
    fun file(set: String, surface: String, n: Int) = "audio/step/$set/${surface}_%02d.ogg".format(n + 1)

    /** One step to play: which variation, its loudness, its pitch (a playback rate), its pan. */
    class Step(val surface: String, val variation: Int, val gain: Float, val rate: Float, val pan: Float)

    /**
     * Picks the steps: a variation unlike the last two of that surface (no machine-gun repeats), the loudness within about
     * +-1.5 dB, the pitch within about +-5 % (and a little higher running), the feet alternating, each its own weight.
     */
    class Picker(seed: Long = 0x5EED) {
        private var rng = seed xor 0x2545F4914F6CDD1DL
        private fun rnd(): Float { rng = rng * 6364136223846793005L + 1442695040888963407L; return ((rng ushr 40).toInt() and 0xFFFFFF) / 16777216f }
        private val recent = HashMap<String, IntArray>()
        private var foot = 0

        /** The next step on [surface] among [count] variations; [running] steps land harder. */
        fun next(surface: String, count: Int, running: Boolean): Step {
            val last = recent.getOrPut(surface) { intArrayOf(-1, -1) }
            var v = 0
            if (count > 1) {
                // a variation unlike the last two (unlike the last one when there are only two)
                for (tries in 0 until 16) {
                    v = (rnd() * count).toInt().coerceAtMost(count - 1)
                    if (v != last[0] && (count < 3 || v != last[1])) break
                }
            }
            last[1] = last[0]; last[0] = v
            foot = 1 - foot
            // the leading foot a touch heavier: the ear hears a stride, not a patter of equal steps
            val weight = if (foot == 0) 1f else .82f
            val db = (rnd() * 2f - 1f) * 1.5f
            val gain = (if (running) .8f else .62f) * weight * Math.pow(10.0, db / 20.0).toFloat()
            val rate = (1f + (rnd() * 2f - 1f) * .05f) * (if (running) 1.03f else 1f)
            val pan = if (foot == 0) -.1f else .1f
            return Step(surface, v, gain, rate, pan)
        }
    }
}
