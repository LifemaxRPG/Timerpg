package com.lifemax.rpg.feature.habits.loop

/*
 * Needs, the sky and the player's cars for the loop (docs/LOOP.md "Needs, weather and cars"): the views the UI reads (made on
 * the sim's thread in LoopGame.publish) and the tables that tie src/cars' bodies to the drivable models. The rules are
 * src/needs, src/weather and src/cars, read in LoopGame.
 */

/** One injury as the pharmacy shows it: its kind (`sprain`, `cut`, `bruise`), hours left, whether it is treated, and what treating it costs. */
class InjuryView(val kind: String, val hoursLeft: Int, val treated: Boolean, val costCents: Int, val costText: String)

/** The player's needs as the HUD shows them: [labels] ("hungry", "tired", "unwashed", an injury), [walk] the speed multiplier they allow, [look] the note others get, the raw 0..1 values. */
class NeedsView(
    val labels: List<String>, val hunger: Double, val fatigue: Double, val hygiene: Double, val health: Double,
    val walk: Double, val social: Double, val look: String, val injuries: List<InjuryView>,
)

/** What a shop offers to eat: [label] ("Eat a meal"), the price, and the meal's kind (`needs.eat`: diner, snack). */
class EatView(val label: String, val cents: Int, val text: String, val meal: String)

/** The sky now (`weather.weatherAt`) and what it does (`weather.weatherEffects`): the face recognition range, the walking, street activity and grip multipliers. */
class SkyView(
    val sky: String, val tempC: Int, val windKph: Int, val visibilityM: Int, val faceM: Double, val walk: Double, val crowd: Double, val grip: Double, val text: String,
)

/** A car on the lot: what it is, its round asking price. */
class LotCar(val id: String, val body: String, val label: String, val modelYear: Int, val mileage: Int, val conditionPct: Int, val askingCents: Int, val askingText: String, val specKey: String)

/** A paper to buy (`cars.papersFor`): its price, whether the player has it, and whether the era asks for it. */
class PaperView(val kind: String, val label: String, val cents: Int, val text: String, val have: Boolean)

/** A car the player owns: where it is, how it runs, what stops it ([blocked]: why the engine will not run, null when it will), and what fixing or towing costs. */
class OwnedCarView(
    val id: String, val label: String, val specKey: String, val colour: Int, val body: String, val mileage: Int, val conditionPct: Int,
    val fuel: Double, val tank: Double, val mpg: Int, val breakdown: String?, val breakdownText: String?,
    val repairCents: Int, val repairText: String, val roadside: Boolean, val towCents: Int, val towText: String, val serviceCents: Int, val serviceText: String,
    /** At the lot (just bought, or towed): the app puts it there. Else where it was parked last (street units) and the way it faces. */
    val atLot: Boolean, val x: Float, val z: Float, val fx: Float, val fz: Float, val blocked: String?,
)

/** The cars part of the view: the lot's listings, the papers, the fuel price, the player's cars. */
class CarsView(
    val lot: List<LotCar> = emptyList(), val papers: List<PaperView> = emptyList(), val papersLine: String = "",
    val fuelCents: Int = 0, val fuelText: String = "", val owned: List<OwnedCarView> = emptyList(),
)

object LoopCars {
    /** The V1 models (assets/vehicles) a car of [body] and [modelYear] is drawn as: sedans, coupes and wagons by era, pickups the 1972 truck (older ones as sedans), sports cars the Torrels. */
    fun specFor(body: String, modelYear: Int): String = when (body) {
        "sports_car" -> if (modelYear >= 1980) "torrel-1988" else "torrel-1968"
        "pickup" -> if (modelYear >= 1960) "tallmere-1972" else sedanFor(modelYear)
        else -> sedanFor(modelYear)
    }

    private fun sedanFor(y: Int) = when { y < 1935 -> "alderwick-1928"; y < 1950 -> "fenmere-1938"; y < 1966 -> "northcairn-1956"; else -> "rowanvale-1970" }

    /** The fuel tank (US gallons) by body (the module has miles per gallon only). */
    fun tank(body: String): Double = when (body) { "sports_car" -> 13.0; "coupe" -> 14.0; "pickup" -> 18.0; "wagon" -> 17.0; else -> 16.0 }

    /** "1946 sedan" from a body and model year. */
    fun label(body: String, modelYear: Int): String = "$modelYear ${body.replace('_', ' ')}"

    /** An asking price in whole round steps ($5 under $100, $10 under $1,000, $50 under $5,000, else $100). */
    fun round(cents: Int): Int {
        val step = when { cents < 10000 -> 500; cents < 100000 -> 1000; cents < 500000 -> 5000; else -> 10000 }
        return maxOf(100, Math.round(cents.toDouble() / step).toInt() * step)
    }
}
