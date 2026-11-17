package com.timerpg.street.audio

/**
 * What the street sounds like now: how loud each ambience bed is and which music plays, from the hour, the weather and
 * the season. Pure (no Android), so it is unit-tested. Beds and tracks are files in assets/audio (amb/NAME.ogg,
 * music/NAME.ogg); a missing one is simply silent.
 */
object SoundRules {
    /** Ambience beds that can play together. */
    val BEDS = listOf("city_day", "night_quiet", "birds_morning", "crickets_night", "wind", "rain_light", "rain_heavy")

    /** Music by mood: one plays at a time, crossfading when it changes. */
    val TRACKS = listOf("morning", "day", "evening", "night", "rain")

    /** The town kinds (src/sim/archetype.ts), each with its own music: music/KIND_MOOD.ogg, else music/MOOD.ogg. */
    val TOWNS = listOf("farm", "harbor", "mill", "mining", "resort", "suburb")

    /** The town kind from the street's label ("Harbor town", "Commuter suburb" ...), or null. */
    fun townOf(label: String): String? {
        val l = label.lowercase()
        return TOWNS.firstOrNull { l.contains(it) } ?: if (l.contains("suburb")) "suburb" else null
    }

    /**
     * Short sounds scattered over the beds ([Mix.scatter]: how many a minute), each picked from its files in
     * assets/audio/sfx (made with MOSS-SoundEffect).
     */
    val SCATTER_FILES = mapOf(
        "birds" to listOf("bird_1", "bird_2", "bird_3"),
        "dog" to listOf("dog_far"),
        "car" to listOf("car_pass"),
        "bell" to listOf("bell_church"),
        "chatter" to listOf("chatter_1"),
        "thunder" to listOf("thunder"),
    )

    class Mix(val beds: Map<String, Float>, val music: String, val musicGain: Float, val scatter: Map<String, Float> = emptyMap())

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** [hour] 0..24, [rain] / [cloud] / [mist] 0..1, [season] 0 spring .. 3 winter. */
    fun at(hour: Float, rain: Float, cloud: Float, mist: Float, season: Int, town: String? = null): Mix {
        val day = smooth(5.5f, 8f, hour) * (1f - smooth(19.5f, 22f, hour))          // the town awake
        val dawn = smooth(4.5f, 6.5f, hour) * (1f - smooth(8.5f, 11f, hour))         // the birds' hour
        val night = 1f - day
        val warm = if (season == 3) 0f else 1f                                       // no crickets in winter
        val dry = 1f - smooth(.15f, .5f, rain)
        val beds = mapOf(
            // (the day's hum and the night's hush need recordings: made from noise they read as static on a phone)
            "city_day" to 0f,
            "night_quiet" to 0f,
            "birds_morning" to .6f * dawn * dry * (if (season == 3) .3f else 1f),
            "crickets_night" to .45f * night * warm * dry * (if (season == 1) 1f else .6f),
            "wind" to (.15f + .35f * cloud + .25f * mist).coerceAtMost(.7f) * (if (season == 3) 1.3f else 1f),
            "rain_light" to .7f * smooth(.02f, .35f, rain) * (1f - smooth(.55f, .85f, rain)),
            "rain_heavy" to .85f * smooth(.5f, .85f, rain),
        ).mapValues { it.value.coerceIn(0f, 1f) }
        val music = when {
            rain > .35f -> "rain"
            hour < 5f || hour >= 21.5f -> "night"
            hour < 10.5f -> "morning"
            hour < 17.5f -> "day"
            else -> "evening"
        }
        val scatter = mapOf(
            "birds" to 9f * (dawn + .25f * day) * dry * (if (season == 3) .2f else 1f),
            "dog" to .6f * (.4f + .6f * night),
            "car" to 2.2f * day * (1f - .4f * rain),
            "chatter" to 1.5f * day * dry,
            "bell" to (if (hour in 11.9f..12.2f || hour in 17.9f..18.2f) 6f else .08f) * day,
            "thunder" to 1.2f * smooth(.7f, .95f, rain),
        )
        return Mix(beds, if (town != null) "${town}_$music" else music, if (music == "night") .45f else .55f, scatter)
    }
}
