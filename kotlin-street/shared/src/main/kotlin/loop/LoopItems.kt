package com.lifemax.rpg.feature.habits.loop

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the loop's shops sell, and what money is worth in the era (kotlin-street/docs/LOOP.md): placeholder stock, a few
 * things a shop of each kind would have, priced in 2025 dollars and scaled to the town's year by a rough price index
 * (about 3.5% a year: 1950 is about 1/13 of 2025, 1978 about 1/5, close to the real CPI). [resale] is what the thing is
 * worth to a pawnbroker as a share of its price (0: perishable, they won't take it); [serial] things carry a serial
 * number (the pawn ledger and the police can trace them).
 */
data class StockItem(
    val key: String,
    val label: String,
    val price2025: Double,
    val resale: Double,
    val serial: Boolean = false,
    /** The years it is sold in (a transistor radio before the 1980s, headphones after). */
    val from: Int = 0,
    val to: Int = 9999,
)

/** Something the player carries: what it is, what it is worth in the era's dollars, and whether it is stolen (hot). */
data class LoopItem(
    val id: String,
    val key: String,
    val label: String,
    val value: Int,
    val serial: String? = null,
    val stolen: Boolean = false,
    /** The crime it was taken in (from `act`), for the pawn shop and the police. */
    val crimeId: String? = null,
    val fromPlace: String? = null,
    val day: Int = 0,
)

object LoopItems {
    /** Prices of [year] as a share of 2025's. */
    fun priceIndex(year: Int): Double = exp((year - 2025) * 0.035).coerceIn(0.02, 3.0)

    /** [price2025] in [year]'s whole dollars (at least 1). */
    fun eraPrice(price2025: Double, year: Int): Int = max(1, (price2025 * priceIndex(year)).roundToInt())

    /** What the player arrives with: a little cash (about $60 in 2025, $12 in 1978). */
    fun startingCash(year: Int): Int = eraPrice(60.0, year).coerceAtLeast(2)

    /** An odd job's hourly pay (about $15 an hour in 2025). */
    fun hourlyWage(year: Int): Double = 15.0 * priceIndex(year)

    /** A pawnbroker pays [StockItem.resale] of an item's price; for something hot this share of that (the risk is theirs). */
    const val HOT_DISCOUNT = .6

    /** What a shift is called at a shop of each kind. */
    fun task(shopKind: String): String = when (shopKind) {
        "market" -> "stocking shelves"
        "pawn" -> "sorting the back room"
        "bakery" -> "kneading and sweeping up"
        "butcher" -> "cleaning the cases"
        "antiques", "general" -> "unpacking crates"
        "ice cream parlor" -> "scooping at the counter"
        else -> "sweeping and stocking"
    }

    /** Stock by shop kind (the store's kind, its reuse's kind, or the small shop's kind lower-cased). */
    val STOCK: Map<String, List<StockItem>> = mapOf(
        "general" to listOf(
            StockItem("peaches", "a tin of peaches", 3.0, 0.0),
            StockItem("flashlight", "a flashlight", 18.0, .3),
            StockItem("pocketknife", "a pocket knife", 28.0, .45, serial = false),
        ),
        "antiques" to listOf(
            StockItem("pocketwatch", "a brass pocket watch", 140.0, .55, serial = true),
            StockItem("cigarcase", "a silver cigarette case", 75.0, .5),
            StockItem("postcards", "a bundle of old postcards", 12.0, .1),
        ),
        "market" to listOf(
            StockItem("groceries", "a bag of groceries", 30.0, 0.0),
            StockItem("whiskey", "a bottle of whiskey", 28.0, .2),
            StockItem("radio", "a transistor radio", 45.0, .4, serial = true, to = 1984),
            StockItem("headphones", "a pair of headphones", 60.0, .4, serial = true, from = 1985),
        ),
        "pawn" to listOf(
            StockItem("guitar", "a used guitar", 160.0, .5, serial = true),
            StockItem("ring", "a gold ring", 220.0, .6),
        ),
        "bakery" to listOf(
            StockItem("bread", "a loaf of bread", 5.0, 0.0),
            StockItem("pastries", "a box of pastries", 14.0, 0.0),
        ),
        "toy store" to listOf(
            StockItem("toyrobot", "a wind-up toy robot", 25.0, .25),
            StockItem("modelkit", "a model airplane kit", 35.0, .25),
        ),
        "butcher" to listOf(StockItem("steak", "a cut of steak", 18.0, 0.0)),
        "clothing store" to listOf(StockItem("jacket", "a wool jacket", 90.0, .3)),
        "dress shop" to listOf(StockItem("scarf", "a silk scarf", 40.0, .3)),
        "music store" to listOf(
            StockItem("harmonica", "a harmonica", 35.0, .35),
            StockItem("records", "a stack of records", 30.0, .2),
        ),
        "bait shop" to listOf(StockItem("reel", "a fishing reel", 65.0, .4, serial = true)),
        "ice cream parlor" to listOf(StockItem("icecream", "an ice cream cone", 5.0, 0.0)),
    )

    fun stockFor(shopKind: String, year: Int): List<StockItem> =
        (STOCK[shopKind] ?: STOCK.getValue("general")).filter { year in it.from..it.to }

    /** The shop kinds the loop opens to Buy, Take and Work (the small shops' street.json kinds, lower-cased). */
    val RETAIL = setOf("bakery", "toy store", "butcher", "clothing store", "dress shop", "music store", "bait shop", "ice cream parlor")
}
