package com.timerpg.street.audio

/**
 * What the street sounds like now (docs/SOUND.md "Ambience"): how loud each ambience bed is, which music plays, how often
 * the scattered sounds come, how busy the far traffic is, how loud the places that sound (the shore, a fountain, a crowd)
 * are, from the hour, the weather, the season, the era and the place the player stands in. Pure (no Android), so it is
 * unit-tested. Beds are files in assets/audio/amb (or made live, [Synth]); a missing one is simply silent.
 */
object SoundRules {
    /** Ambience beds that can play together (recorded, or made live by [Synth]). */
    val BEDS = listOf(
        "city_day", "city_morning", "residential", "night_town", "highway_far",
        "birds_day", "birds_park", "birds_garden", "crickets_night",
        "wind", "rain_light", "rain_heavy", "night_quiet",
    )

    /** Beds made live (no recording): [Synth]. */
    val SYNTH_BEDS = setOf("crickets_night", "wind", "rain_light", "rain_heavy", "night_quiet")

    /** Music by mood: one plays at a time, crossfading when it changes. */
    val TRACKS = listOf("morning", "day", "evening", "night", "rain")

    /** The town kinds (src/sim/archetype.ts), each with its own music: music/KIND_MOOD.ogg, else music/MOOD.ogg. */
    val TOWNS = listOf("farm", "harbor", "mill", "mining", "resort", "suburb")

    /** The town kind from the street's label ("Harbor town", "Commuter suburb" ...), or null. */
    fun townOf(label: String): String? {
        val l = label.lowercase()
        return TOWNS.firstOrNull { l.contains(it) } ?: if (l.contains("suburb")) "suburb" else null
    }

    /** Short sounds scattered over the beds ([Mix.scatter]: how many a minute), each picked from its files in assets/audio/sfx. */
    val SCATTER_FILES = mapOf(
        "dog" to listOf("dog_far_1", "dog_far_2", "dog_far_3", "dog_near_1"),
        "thunder" to listOf("thunder_1"),
        "horses" to listOf("horses_pass"),
    )

    /**
     * Where the player is, as the sound needs it: the [year] (the era), how [green] it is round them (trees, lawns: 0..1),
     * how [urban] (shops and landmarks: 0..1), how many people are within earshot ([crowd]), the metres to the nearest
     * water, indoors or in a car, whether the police are out looking ([police]), the wind (0..1), the cars moving near.
     */
    class Place(
        val year: Int = 2025, val green: Float = .5f, val urban: Float = .3f, val crowd: Int = 0, val waterM: Float = 1e9f,
        val indoors: Boolean = false, val inCar: Boolean = false, val police: Boolean = false, val wind: Float = 0f, val movingCars: Int = 0,
    )

    class Mix(
        val beds: Map<String, Float>, val music: String, val musicGain: Float, val scatter: Map<String, Float> = emptyMap(),
        /** Far traffic: passing cars a minute, and the year their engines come from. */
        val trafficPerMinute: Float = 0f,
        /** The places that sound: the shore or a river (by distance), a crowd, a fountain (positional: their own gains). */
        val water: Float = 0f, val crowd: Float = 0f, val fountain: Float = 0f,
        /** 0 open air .. 1 indoors: the ambience muffled. */
        val muffle: Float = 0f,
        /** A siren now and then (the police out looking), and its kind ([sirenStyle]). */
        val siren: Boolean = false, val sirenStyle: String = "electronic",
        val year: Int = 2025,
    )

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** How much the recorded town beds (full of modern cars) belong to [year]: none before the motor age, all by the sixties. */
    fun modernTraffic(year: Int): Float = smooth(1946f, 1966f, year.toFloat())

    /** The police's siren in [year]: a bell (before sirens), a hand-cranked or motor wail, the electronic wail and yelp. */
    fun sirenStyle(year: Int): String = when { year < 1915 -> "bell"; year < 1966 -> "mechanical"; else -> "electronic" }

    /**
     * The bell's strikes when the hour turns between [prevHour] and [hour] (0..24): the hour's count on a twelve-hour
     * clock, none overnight (from 22:00 to 7:00 the town's bells are quiet), 0 when no hour turned.
     */
    fun strikes(prevHour: Float, hour: Float): Int {
        if (prevHour.isNaN()) return 0
        val a = prevHour.toInt(); val b = hour.toInt()
        // (only a step forward of under an hour: not the debug slider dragged across the day)
        val step = (hour - prevHour + 24f) % 24f
        if (a == b || step > 1f) return 0
        val h = b % 24
        if (h < 7 || h > 22) return 0
        return if (h % 12 == 0) 12 else h % 12
    }

    /** [hour] 0..24, [rain] / [cloud] / [mist] 0..1, [season] 0 spring .. 3 winter, at [place]. */
    fun at(hour: Float, rain: Float, cloud: Float, mist: Float, season: Int, town: String? = null, place: Place = Place()): Mix {
        val day = smooth(5.5f, 8f, hour) * (1f - smooth(19.5f, 22f, hour))          // the town awake
        val dawn = smooth(4.5f, 6.5f, hour) * (1f - smooth(8.5f, 11f, hour))         // the birds' hour
        val rush = smooth(6.5f, 8f, hour) * (1f - smooth(9f, 10f, hour)) + smooth(16f, 17f, hour) * (1f - smooth(18.5f, 19.5f, hour))
        val night = 1f - day
        val warm = if (season == 3) 0f else 1f                                       // no crickets in winter
        val dry = 1f - smooth(.15f, .5f, rain)
        val green = place.green.coerceIn(0f, 1f); val urban = place.urban.coerceIn(0f, 1f)
        val modern = modernTraffic(place.year)
        val share = EngineProfile.trafficShare(place.year)
        val beds = mapOf(
            // the town's hum (recordings full of modern traffic: only once the era has it), by day downtown, the
            // residential streets' far traffic, the night's hush with a car now and then
            "city_day" to .5f * day * urban * modern * (1f - .3f * rain),
            "city_morning" to .45f * dawn * urban * modern,
            "residential" to .45f * day * (1f - urban * .6f) * modern,
            "night_town" to .4f * night * modern,
            "highway_far" to .12f * (day + .4f * night) * modern * (1f - urban),
            // (before the motor age the town is quiet under its birds: a soft made-live hush keeps it from dead silence)
            "night_quiet" to .25f * (1f - modern) * (.4f + .6f * night),
            "birds_day" to .55f * day * dry * (.25f + .75f * green) * (1f - .6f * dawn) * (if (season == 3) .25f else 1f),
            "birds_park" to .45f * day * dry * green * green * (if (season == 3) .2f else 1f),
            "birds_garden" to .6f * dawn * dry * (.3f + .7f * green) * (if (season == 3) .3f else 1f),
            "crickets_night" to .55f * night * warm * dry * (.3f + .7f * green) * (if (season == 1) 1f else .6f),
            "wind" to (.35f * cloud + .25f * mist + .5f * place.wind).coerceAtMost(.7f) * (if (season == 3) 1.3f else 1f),
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
            "dog" to 1.2f * (.3f + .7f * green) * (.5f + .5f * night) * (1f - .5f * urban),
            "thunder" to 1.2f * smooth(.7f, .95f, rain),
            "horses" to 1.5f * EngineProfile.horseShare(place.year) * day * (.4f + .6f * urban),
        )
        val traffic = (2f + 10f * rush + 4f * day) * (.2f + .8f * day) * share * (.35f + .65f * urban) * (1f - .3f * rain) +
            place.movingCars * .5f
        val water = if (place.waterM < 400f) (1f - place.waterM / 400f).let { it * it } * .9f else 0f
        val crowd = (place.crowd / 8f).coerceIn(0f, 1f) * (.4f + .6f * day) * .8f
        val muffle = if (place.indoors) .85f else if (place.inCar) .55f else 0f
        return Mix(beds, if (town != null) "${town}_$music" else music, if (music == "night") .45f else .55f, scatter,
            traffic, water, crowd, .6f, muffle, place.police, sirenStyle(place.year), place.year)
    }
}
