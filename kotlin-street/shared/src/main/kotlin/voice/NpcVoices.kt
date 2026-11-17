package com.timerpg.voice

/**
 * Which voice an NPC speaks with: chosen once from who they are, so the same person always sounds the same. Both engines
 * are used (Kokoro's 28 English voices and Supertonic's 10), and every NPC's voice is a blend of two of one engine's
 * voices of their sex: a gruff person leans on the deeper voices, a warm one on the brighter; age sets the pace.
 */
object NpcVoices {
    enum class Engine { KOKORO, SUPERTONIC }

    /** A voice: the engine, the voices blended (name to weight) and the speaking speed. */
    data class Voice(val engine: Engine, val blend: List<Pair<String, Float>>, val speed: Float) {
        /** A stable key for caching a line in this voice. */
        val key: String get() = engine.name + ":" + blend.joinToString(",") { "${it.first}=${"%.3f".format(it.second)}" } + "@" + "%.3f".format(speed)
    }

    // Kokoro's English voices, deep/gruff first, bright/warm last (by ear and measured pitch; US and UK accents)
    private val KOKORO_MEN = listOf("am_onyx", "bm_george", "am_fenrir", "bm_lewis", "am_michael", "am_adam", "bm_daniel", "am_eric", "bm_fable", "am_liam", "am_echo", "am_puck")
    private val KOKORO_WOMEN = listOf("af_kore", "bf_alice", "af_alloy", "af_sarah", "af_aoede", "bf_emma", "af_river", "af_nicole", "bf_isabella", "af_jessica", "af_bella", "af_heart", "af_nova", "bf_lily", "af_sky")
    private val SUPERTONIC_MEN = listOf("M3", "M5", "M2", "M1", "M4")
    private val SUPERTONIC_WOMEN = listOf("F4", "F2", "F5", "F3", "F1")

    /** A 0..1 value from [id] and [salt], the same every time. */
    private fun h(id: String, salt: Int): Float {
        var x = -0x340d631b7bdddcdbL xor salt.toLong()
        for (c in id) { x = (x xor c.code.toLong()) * 0x100000001b3L }
        x = (x xor (x ushr 29)) * -0x4b47d5b1d1e3a6f5L
        x = x xor (x ushr 32)
        return ((x ushr 11) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
    }

    /**
     * The voice of the person [id], [female] or not, [age] years old, [warmth] 0 (gruff) to 1 (warm). [kokoroOk] and
     * [supertonicOk] say which engines are installed.
     */
    fun assign(id: String, female: Boolean, age: Int, warmth: Float, kokoroOk: Boolean = true, supertonicOk: Boolean = true): Voice? {
        val engine = when {
            kokoroOk && supertonicOk -> if (h(id, 1) < .55f) Engine.KOKORO else Engine.SUPERTONIC
            kokoroOk -> Engine.KOKORO
            supertonicOk -> Engine.SUPERTONIC
            else -> return null
        }
        val pool = when (engine) {
            Engine.KOKORO -> if (female) KOKORO_WOMEN else KOKORO_MEN
            Engine.SUPERTONIC -> if (female) SUPERTONIC_WOMEN else SUPERTONIC_MEN
        }
        // where in the deep-to-bright range they sit: warmth, nudged by age (older a little deeper) and by chance
        val centre = (warmth * .75f + (if (age > 60) -.12f else if (age < 25) .12f else 0f) + (h(id, 2) - .5f) * .5f).coerceIn(0f, 1f)
        val a = (centre * (pool.size - 1)).toInt().coerceIn(0, pool.size - 1)
        var b = (a + 1 + (h(id, 3) * 2.99f).toInt() * (if (h(id, 4) < .5f) 1 else -1)).coerceIn(0, pool.size - 1)
        if (b == a) b = if (a + 1 < pool.size) a + 1 else a - 1
        val w = .55f + .4f * h(id, 5)
        // pace: the old speak slower, the young quicker, everyone a little their own
        val speed = ((if (age > 65) .9f else if (age > 45) .96f else if (age < 20) 1.08f else 1.02f) + (h(id, 6) - .5f) * .08f)
        return Voice(engine, listOf(pool[a] to w, pool[b.coerceIn(0, pool.size - 1)] to (1f - w)), speed)
    }
}
