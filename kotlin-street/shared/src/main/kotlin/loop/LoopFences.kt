package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A fence (src/economy/fence.ts, docs/ECONOMY.md) as the loop knows them: the economy's `Fence` ([json], passed back to
 * `fenceDeal` / `fenceRisk` as it is), the person, and the place they are met ([spotX], [spotZ], street units; [facingX],
 * [facingZ]) during their hours ([start] to [end] o'clock, 20 to 2). [whereText] is where, in the town's own words.
 */
class LoopFence(
    val json: JSONObject,
    val personId: String,
    val name: String,
    val cover: String,
    val whereText: String,
    val start: Int,
    val end: Int,
    val spotX: Float,
    val spotZ: Float,
    val facingX: Float,
    val facingZ: Float,
) {
    /** At their spot at [hour] (0..24): from [start] round midnight to [end]. */
    fun atHours(hour: Double): Boolean = (hour % 24.0).let { h -> if (start > end) h >= start || h < end else h >= start && h < end }
    val hoursText: String get() = "%02d:00-%02d:00".format(start, end)
    val first: String get() = name.substringBefore(' ')
}

/** What a fence would pay for an item now: [cents] and its [text] when [accepted], else a [reason] ("category", "too_hot", "no_offer") and [text] says it. */
class FenceQuote(val accepted: Boolean, val cents: Int, val text: String, val reason: String?, val heat: Double)

/**
 * The fence layer of the first loop (docs/LOOP.md, "The fence"): who the town's fences are, where they stand at night, and
 * the numbers handed to the economy. Pure: the sim's calls are made by [LoopGame].
 */
object LoopFences {
    /** Deterministic 0..1 from [parts] (the JVM's stable String.hashCode, mixed). */
    fun unit(vararg parts: Any): Double {
        var h = parts.joinToString("|").hashCode().toLong() and 0xffffffffL
        h = (h xor (h shr 16)) * 0x45d9f3bL and 0xffffffffL
        h = (h xor (h shr 16)) * 0x45d9f3bL and 0xffffffffL
        h = h xor (h shr 16)
        return (h and 0xffffffffL) / 4294967296.0
    }

    private fun clamp01(d: Double) = d.coerceIn(0.0, 1.0)

    /** The shop kind a cover story works from (src/economy/fence.ts `covers`): the loop's [LoopPlace.shopKind], or "pawn_shop" by place kind. */
    private fun coverMatches(cover: String, p: LoopPlace): Boolean = p.kind != "home" && when (cover) {
        "hardware clerk" -> p.shopKind == "bait shop"
        "repair clerk" -> p.shopKind == "music store"
        else -> p.kind == "pawn_shop" // secondhand dealer
    }

    /** The cover job a person's real workplace suggests ("" when it suggests none: townFences then picks one by seed). */
    private fun jobOf(town: LoopTown, p: LoopPerson): String = town.placeById[p.workplacePlaceId]?.let { w ->
        when { w.shopKind == "bait shop" -> "hardware clerk"; w.shopKind == "music store" -> "repair clerk"; w.kind == "pawn_shop" || w.shopKind == "antiques" -> "secondhand dealer"; else -> "" }
    } ?: ""

    /**
     * The town's people as the economy's `FencePerson`s. The town's data has only warmth (charm), so:
     * sociability = warmth; honesty = .15 + .75 u1 + .2 (warmth - .5), 0.05 less under 30; greed = .1 + .85 u2 - .1 (warmth - .5);
     * u1 and u2 deterministic 0..1 per person and town seed. job = the cover their real workplace suggests (bait shop: hardware
     * clerk, music store: repair clerk, pawn or antiques: secondhand dealer), else "" (the seed picks a cover).
     */
    fun persons(town: LoopTown): JSONArray = JSONArray().also { a ->
        for (p in town.people.distinctBy { it.id }) { // (the export repeats some ids)
            val w = p.charm
            val honesty = clamp01(.15 + .75 * unit(town.seed, p.id, "honesty") + .2 * (w - .5) - if (p.age < 30) .05 else 0.0)
            val greed = clamp01(.1 + .85 * unit(town.seed, p.id, "greed") - .1 * (w - .5))
            a.put(JSONObject().put("id", p.id).put("age", p.age).put("job", jobOf(town, p)).put("honesty", r3(honesty)).put("greed", r3(greed)).put("sociability", r3(w)))
        }
    }

    private fun r3(d: Double) = (d * 1000).roundToInt() / 1000.0

    /** The fence [json] as a [LoopFence], standing at the back of the nearest shop their cover works from (else behind their home). */
    fun place(town: LoopTown, json: JSONObject): LoopFence {
        val id = json.getString("personId")
        val person = town.personById[id]
        val home = person?.let { town.placeById[it.homePlaceId] }
        val cover = json.optString("cover")
        val hx = home?.doorX ?: 0f; val hz = home?.doorZ ?: 0f
        val shop = town.places.filter { coverMatches(cover, it) }.minByOrNull { hypot(it.doorX - hx, it.doorZ - hz) }
        val target = shop ?: home
        var x = target?.doorX ?: 0f; var z = target?.doorZ ?: 0f
        var fx = 0f; var fz = 1f
        target?.let { t ->
            town.town.placement(t.sprite)?.let { (r, b) ->
                // the back of the building: past its front wall, its depth and a little more, facing away from it
                val front = r.pointAt(b.mid, 0f); val n = r.normalAt(b.mid)
                val d = b.setback + b.depth + 2f * VehicleWorld.U
                x = front.x + n.x * d; z = front.z + n.z * d; fx = n.x; fz = n.z
            }
        }
        val where = if (shop != null) "behind ${shop.name}" else "behind their home"
        val hours = json.optJSONArray("hours")
        return LoopFence(json, id, person?.name ?: id, cover, where, hours?.optInt(0, 20) ?: 20, hours?.optInt(1, 2) ?: 2, x, z, fx, fz)
    }

    /** Heat of [item] on the economy's 0..1 scale: 0 for a clean one; stolen: .9 within a day of the theft, .6 within a week, .3 after. */
    fun heat(item: LoopItem, today: Int): Double = if (!item.stolen) 0.0 else (today - item.day).let { age -> if (age <= 1) .9 else if (age <= 7) .6 else .3 }

    /** The lead's line for a source person's fact sheet. */
    fun leadFact(f: LoopFence): String = "You know ${f.name} buys things, no questions asked, ${f.whereText}, after dark."
}
