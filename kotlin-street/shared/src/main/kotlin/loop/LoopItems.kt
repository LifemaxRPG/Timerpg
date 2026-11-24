package com.lifemax.rpg.feature.habits.loop

/**
 * What the loop's shops sell: the era economy's shelves (src/economy, docs/ECONOMY.md), read through the bundled sim
 * (`economy.stockOf`, in [LoopGame.stockFor]), in integer nominal cents for the town's year. [key] is the economy's
 * item id; [serial] things carry a serial number (the pawn ledger and the police can trace them); [quantity] is the
 * shelf count the economy rolled (the game does not deplete it yet).
 */
data class StockItem(
    val key: String,
    val label: String,
    val priceCents: Int,
    val serial: Boolean = false,
    val quantity: Int = 1,
    /** [priceCents] as the era writes it ("23c", "$29.95"), made on the sim's thread by `economy.formatMoney`. */
    val priceText: String = "",
)

/** Something the player carries: what it is, what it is worth in the era's cents, and whether it is stolen (hot). */
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
    /** What the player arrives with, in 2025 cents (about $60); scaled by the era's price index ([LoopGame.startingCash]). */
    const val START_CASH_2025 = 6000

    /** The economy's shop kind (`SHOP_KINDS`) for a loop store kind (the store's kind, its reuse's kind, or a small shop's street.json kind). */
    fun econKind(shopKind: String): String = when (shopKind) {
        "general" -> "general"
        "antiques", "pawn" -> "pawn" // (used goods: the economy has no antiques dealer)
        "market", "bakery", "butcher" -> "market"
        "toy store", "music store" -> "five_and_dime"
        "clothing store", "dress shop" -> "clothing"
        "bait shop" -> "hardware"
        "ice cream parlor" -> "diner"
        else -> "general"
    }

    /** The economy's wage schedule (`wage(job, year)`) a shift at a shop of this kind is paid by. */
    fun job(shopKind: String): String = when (shopKind) {
        "market", "bakery", "ice cream parlor", "clothing store", "dress shop", "toy store", "music store", "general" -> "shop_clerk"
        else -> "odd_jobs" // (crates, the back room, the cases: casual labour)
    }

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

    /** The shop kinds the loop opens to Buy, Take and Work (the small shops' street.json kinds, lower-cased). */
    val RETAIL = setOf("bakery", "toy store", "butcher", "clothing store", "dress shop", "music store", "bait shop", "ice cream parlor")
}
