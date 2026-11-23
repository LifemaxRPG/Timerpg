package com.lifemax.rpg.feature.habits.domain.signs

import com.lifemax.rpg.feature.habits.domain.WorldRand

/**
 * Generates authentic, era-appropriate business names from the simulation's owner facts.
 *
 * Pure and deterministic via [WorldRand.rand] (matching `src/sim/rng.ts`). Contains zero real
 * brand names.
 */
object SignNames {
    private enum class EraBand {
        EARLY,   // pre-1950 (e.g. 1900–1949)
        MID,     // 1950–1969
        MODERN,  // 1970–today
    }

    private fun eraBand(era: Int): EraBand = when {
        era < 1950 -> EraBand.EARLY
        era < 1970 -> EraBand.MID
        else -> EraBand.MODERN
    }

    private fun possessive(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith('s', ignoreCase = true)) "$trimmed'" else "$trimmed's"
    }

    private fun initial(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) "" else "${trimmed[0].uppercaseChar()}."
    }

    private fun normalizeKind(kind: String): String =
        kind.trim().lowercase().replace('-', '_').replace(' ', '_')

    private fun pick(seed: String, kind: String, ownerSurname: String, ownerFirst: String, era: Int, count: Int): Int {
        val band = eraBand(era).name
        val r = WorldRand.rand(seed, "sign-name", kind, band, ownerSurname, ownerFirst)
        return (r * count).toInt().coerceIn(0, count - 1)
    }

    /**
     * Generates a deterministic business name for a building of [kind] owned by
     * `[ownerFirst] [ownerSurname]` in simulation year [era] under [seed].
     */
    fun nameFor(
        kind: String,
        ownerSurname: String,
        ownerFirst: String,
        era: Int,
        seed: String,
    ): String {
        val k = normalizeKind(kind)
        val sur = ownerSurname.trim().ifEmpty { "Hollis" }
        val first = ownerFirst.trim().ifEmpty { "Bob" }
        val surPoss = possessive(sur)
        val firstPoss = possessive(first)
        val fInit = initial(first)
        val band = eraBand(era)

        val patterns: Array<String> = when (k) {
            "barber", "barbershop", "barber_shop" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Barber Shop",
                    "$fInit $sur Tonsorial",
                    "$sur & Sons Barber",
                    "$surPoss Barber Shop",
                )
                EraBand.MID -> arrayOf(
                    "$sur Barber Shop",
                    "$firstPoss Barber Shop",
                    "$sur Barber",
                    "$first & $sur Barber",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Barber Shop",
                    "$sur Cuts",
                    "$firstPoss Barbershop",
                    "$sur Hair & Shave",
                )
            }

            "salon", "hair_salon", "nail_salon", "beauty_salon" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Beauty Parlor",
                    "$firstPoss Beauty Shop",
                    "$sur Ladies Salon",
                )
                EraBand.MID -> arrayOf(
                    "$firstPoss Beauty Salon",
                    "$sur Beauty Shop",
                    "$sur Coiffure",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Hair Co.",
                    "$sur Nail Room",
                    "$firstPoss Hair Studio",
                    "$sur Hair & Nails",
                )
            }

            "funeral_home", "funeral", "mortuary" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur & Sons Funeral Home",
                    "$sur Mortuary",
                    "$fInit $sur Undertaking",
                )
                EraBand.MID -> arrayOf(
                    "$sur & Sons Funeral Home",
                    "$sur Funeral Home",
                    "$sur Memorial Chapel",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Funeral Home",
                    "$sur Memorial Chapel",
                    "$sur Family Funeral Home",
                )
            }

            "diner", "cafe", "coffee_shop" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Lunch Room",
                    "$firstPoss Cafe",
                    "$sur Dining Room",
                    "$sur Coffee House",
                )
                EraBand.MID -> arrayOf(
                    "$firstPoss Diner",
                    "$sur Diner",
                    "$firstPoss Counter",
                    "$sur Cafe & Grill",
                )
                EraBand.MODERN -> arrayOf(
                    "$firstPoss Diner",
                    "$sur Family Diner",
                    "$firstPoss Coffee Shop",
                    "$sur Kitchen",
                )
            }

            "carhop", "drive_in", "burger_stand" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$firstPoss Refreshments",
                    "$sur Sandwich Stand",
                )
                EraBand.MID -> arrayOf(
                    "$firstPoss Drive-In",
                    "$sur Carhop",
                    "$firstPoss Burger Stand",
                )
                EraBand.MODERN -> arrayOf(
                    "$firstPoss Drive-In",
                    "$sur Burgers",
                    "$firstPoss Grill",
                )
            }

            "five_and_dime", "variety", "dime_store" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$surPoss Five and Dime",
                    "$sur 5 & 10 Store",
                    "$sur Dry Goods",
                )
                EraBand.MID -> arrayOf(
                    "$surPoss Five and Dime",
                    "$sur Variety Store",
                    "$surPoss 5 & 10",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Variety",
                    "$surPoss Discount Goods",
                    "$sur General Goods",
                )
            }

            "drugstore", "pharmacy", "corner_drugstore" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Apothecary",
                    "$sur & Sons Drugs",
                    "$fInit $sur Chemist",
                )
                EraBand.MID -> arrayOf(
                    "$sur Drugstore",
                    "$surPoss Pharmacy",
                    "$sur Corner Drugs",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Pharmacy",
                    "$sur Drugstore",
                    "$sur Family Pharmacy",
                )
            }

            "gas_station", "service_station", "truck_stop", "garage" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Garage",
                    "$sur Motor Service",
                    "$sur & Sons Garage",
                )
                EraBand.MID -> arrayOf(
                    "$firstPoss Service Station",
                    "$sur Gas & Oil",
                    "$sur Auto Service",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Fuel & Mart",
                    "$firstPoss Auto & Gas",
                    "$sur Service Center",
                )
            }

            "motel", "inn", "hotel" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Hotel",
                    "$sur Boarding House",
                    "$sur Inn",
                )
                EraBand.MID -> arrayOf(
                    "$sur Motor Court",
                    "$sur Motel",
                    "$sur Motor Lodge",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Motel",
                    "$sur Inn & Suites",
                    "$sur Roadside Inn",
                )
            }

            "laundromat", "coin_laundromat", "laundry" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Steam Laundry",
                    "$sur Hand Laundry",
                )
                EraBand.MID -> arrayOf(
                    "$sur Coin Laundry",
                    "$firstPoss Wash & Dry",
                    "$sur Laundromat",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Laundromat",
                    "$sur Wash & Fold",
                    "$firstPoss Coin Laundry",
                )
            }

            "hardware", "hardware_store" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur & Sons Hardware",
                    "$fInit $sur Hardware",
                    "$sur Iron & Hardware",
                )
                EraBand.MID -> arrayOf(
                    "$sur Hardware Store",
                    "$surPoss Hardware",
                    "$sur Hardware & Supply",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Hardware",
                    "$sur Supply Co.",
                    "$sur Tool & Hardware",
                )
            }

            "store", "general_store", "grocery", "market" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur General Store",
                    "$sur & Sons Mercantile",
                    "$surPoss Provision Co.",
                )
                EraBand.MID -> arrayOf(
                    "$surPoss Market",
                    "$sur Family Grocery",
                    "$firstPoss Food Mart",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Market",
                    "$sur Corner Store",
                    "$surPoss Groceries",
                )
            }

            "bakery", "bake_shop" -> when (band) {
                EraBand.EARLY -> arrayOf(
                    "$sur Bakery",
                    "$sur & Sons Bakers",
                    "$firstPoss Bake Shop",
                )
                EraBand.MID -> arrayOf(
                    "$firstPoss Bakery",
                    "$sur Bake Shop",
                    "$sur Pastry & Bread",
                )
                EraBand.MODERN -> arrayOf(
                    "$sur Bakery",
                    "$firstPoss Bakehouse",
                    "$sur Bread Co.",
                )
            }

            "bowling", "bowling_alley" -> when (band) {
                EraBand.EARLY -> arrayOf("$sur Bowling Parlor", "$sur Alleys")
                EraBand.MID -> arrayOf("$sur Lanes", "$firstPoss Bowl", "$sur Bowling")
                EraBand.MODERN -> arrayOf("$sur Lanes", "$sur Family Bowl", "$firstPoss Bowling")
            }

            "video_store", "video" -> arrayOf(
                "$firstPoss Video",
                "$sur Video & Tape",
                "$sur Movie Rental",
            )

            "phone_repair", "electronics" -> arrayOf(
                "$sur Phone Repair",
                "$firstPoss Tech Repair",
                "$sur Wireless Fix",
            )

            "nightclub", "tavern", "bar", "lounge" -> when (band) {
                EraBand.EARLY -> arrayOf("$sur Tavern", "$firstPoss Taproom", "$sur Hall")
                EraBand.MID -> arrayOf("$sur Lounge", "$firstPoss Supper Club", "The $sur Club")
                EraBand.MODERN -> arrayOf("$sur Lounge", "$firstPoss Bar & Grill", "The $sur")
            }

            "feed_mill", "grain_elevator", "mill" -> arrayOf(
                "$sur Feed & Seed",
                "$sur & Sons Milling",
                "$sur Grain & Feed",
            )

            "antiques", "thrift" -> arrayOf(
                "$sur Antiques",
                "$firstPoss Curios",
                "$surPoss Thrift Shop",
            )

            else -> {
                val label = k.split('_').filter { it.isNotEmpty() }.joinToString(" ") { w ->
                    w.replaceFirstChar { c -> c.uppercaseChar() }
                }.ifEmpty { "Shop" }
                when (band) {
                    EraBand.EARLY -> arrayOf("$sur & Sons $label", "$fInit $sur $label", "$surPoss $label")
                    EraBand.MID -> arrayOf("$firstPoss $label", "$sur $label", "$surPoss $label")
                    EraBand.MODERN -> arrayOf("$sur $label Co.", "$sur $label", "$firstPoss $label")
                }
            }
        }

        val idx = pick(seed, k, sur, first, era, patterns.size)
        return patterns[idx]
    }

    fun nameFor(
        kind: String,
        ownerSurname: String,
        ownerFirst: String,
        era: Int,
        seed: Int,
    ): String = nameFor(kind, ownerSurname, ownerFirst, era, seed.toString())
}
