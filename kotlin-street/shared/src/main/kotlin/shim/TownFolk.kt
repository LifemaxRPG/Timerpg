package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap
import com.lifemax.rpg.feature.habits.domain.Ambient
import org.json.JSONObject
import kotlin.math.exp

/**
 * The townspeople's own character sheets (docs/TOWN.md, "Townsfolk"): baked from the pack's Character Generator by
 * tools/make_townsfolk.py (body and skin tone, eyes, outfit, hairstyle, beards, glasses, hats) into compact sheets of
 * the two rows the town draws (768 x 128: idle, then walk), each with its traits. A person wears the sheet [pick] gives
 * for their seed, sex and age, so a woman of 70 never wears a boy's look, and clothes too modern for the town's year are
 * left to later years. The player keeps their own [SpriteSheets.sheets].
 */
object TownFolk {
    /** A baked sheet's traits: [female], [age] band (0 kid, 1 adult, 2 elder), [modern] clothes (not before 1990). */
    class Traits(val female: Boolean, val age: Int, val modern: Boolean)

    @Volatile var sheets: List<ImageBitmap> = emptyList()
        private set
    @Volatile var traits: List<Traits> = emptyList()
        private set
    // the sheets by band and sex (kid, adult, elder x female, male), and the modern-free subsets for older years
    private var pools: Array<IntArray> = Array(6) { IntArray(0) }
    private var oldPools: Array<IntArray> = Array(6) { IntArray(0) }

    /** Reads folk.json's [json] and each sheet through [image] (its file name). */
    fun load(json: String, image: (String) -> ImageBitmap?) {
        val people = JSONObject(json).getJSONArray("people")
        val imgs = ArrayList<ImageBitmap>()
        val ts = ArrayList<Traits>()
        for (i in 0 until people.length()) {
            val o = people.getJSONObject(i)
            val img = image(o.getString("file")) ?: continue
            imgs += img
            ts += Traits(o.optString("sex") == "F", when (o.optString("age")) { "kid" -> 0; "elder" -> 2; else -> 1 }, o.optBoolean("modern"))
        }
        set(imgs, ts)
    }

    fun set(imgs: List<ImageBitmap>, ts: List<Traits>) {
        pools = Array(6) { k -> ts.indices.filter { ts[it].age == k / 2 && ts[it].female == (k % 2 == 0) }.toIntArray() }
        oldPools = Array(6) { k -> pools[k].filter { !ts[it].modern }.toIntArray() }
        traits = ts
        sheets = imgs
    }

    /** Which sheet a person of [seed] wears: [female], [age] in years (-1: unknown, an adult), in the town's [year]. */
    fun pick(seed: Int, female: Boolean, age: Int, year: Int = 2025): Int {
        val n = sheets.size
        if (n == 0) return -1
        val band = when { age in 0..14 -> 0; age >= 66 -> 2; else -> 1 }
        val k = band * 2 + (if (female) 0 else 1)
        var pool = if (year < 1990) oldPools[k] else pools[k]
        if (pool.isEmpty()) pool = pools[k]
        if (pool.isEmpty()) pool = pools[2 + (k % 2)]
        if (pool.isEmpty()) return (Ambient.hash(seed, 3, 977) * n).toInt().coerceIn(0, n - 1)
        return pool[(Ambient.hash(seed, 5, 977) * pool.size).toInt().coerceIn(0, pool.size - 1)]
    }

    /** A passer's sex and age from their [seed] alone (never changing mid-walk): about half women, one in seven a child, [elder] when their walk is an old person's. */
    fun passer(seed: Int, elder: Boolean): Pair<Boolean, Int> {
        val female = Ambient.hash(seed, 11, 977) < .5f
        if (elder) return female to 72
        val r = Ambient.hash(seed, 13, 977)
        return female to when { r < KIDS -> 10; r < KIDS + .16f -> 70; else -> 35 }
    }

    /** One passer in this many is a child (left indoors in school hours and at night: [kidsOut]). */
    const val KIDS = .14f

    /** Whether children are out at [hour]: not at school (8:30 to 15:00) nor after dark. */
    fun kidsOut(hour: Float): Boolean { val h = ((hour % 24f) + 24f) % 24f; return !(h in 8.5f..15f || h < 7f || h > 20.5f) }
}

/**
 * How busy the open town's pavements are (the people of src/town/schedule.ts: at work, at school or at home most of the
 * day, out on the commute, the school run, errands and evening strolls). A row's passers are thinned to [share] of
 * StreetLife's stream, by the hour, what the row is (shops or houses) and the weather ([TownPlay.outdoors]).
 */
object TownCrowd {
    private fun bump(h: Float, at: Float, wide: Float): Float { val d = (h - at) / wide; return exp(-d * d) }

    /** The share of a house row's residents' stream out at [hour]: the commute both ways, the school run, evening strolls. */
    fun homes(hour: Float): Float {
        val h = ((hour % 24f) + 24f) % 24f
        val day = if (h in 7f..22f) .04f else .01f
        return (day + .42f * bump(h, 7.9f, 1f) + .1f * bump(h, 12.3f, 1.1f) + .16f * bump(h, 15.4f, .6f) +
            .3f * bump(h, 17.4f, 1.1f) + .16f * bump(h, 20f, .9f)).coerceAtMost(1f)
    }

    /** The share on a row of shops at [hour]: open hours busy, the evening thinner, the night nearly empty. */
    fun shops(hour: Float): Float {
        val h = ((hour % 24f) + 24f) % 24f
        fun s(x: Float) = (x.coerceIn(0f, 1f)).let { it * it * (3f - 2f * it) }
        val open = s((h - 7.5f) / 1.5f) * (1f - s((h - 18f) / 1.5f))
        val evening = s((h - 17f) / 1f) * (1f - s((h - 21.5f) / 1.5f))
        return (.04f + .86f * open + .4f * evening).coerceAtMost(.95f)
    }

    /** A row of [shops] shops, [homes] homes and [others] landmarks at [hour], the weather letting [outdoors] of them out. */
    fun share(hour: Float, shops: Int, homes: Int, others: Int, outdoors: Float): Float {
        val total = shops + homes + others
        // (a row with no buildings is a park's edge or a bridge: half as busy as a street of houses)
        val f = if (total == 0) 0f else (shops + .5f * others) / total
        val quiet = if (total == 0) .5f else 1f
        return ((f * shops(hour) + (1f - f) * HOME_SHARE * homes(hour)) * quiet * outdoors).coerceIn(0f, 1f)
    }

    /** A house row draws this much of the stream a shopping street would at the same share (few walk past houses). */
    const val HOME_SHARE = .6f
}
