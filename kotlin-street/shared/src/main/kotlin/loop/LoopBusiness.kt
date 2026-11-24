package com.lifemax.rpg.feature.habits.loop

/*
 * Menus, lodging and small businesses for the loop (docs/LOOP.md "Menus, lodging and small businesses"): the views the UI
 * reads (made on the sim's thread in LoopGame.publish) and the tables that tie the loop's shop kinds to src/places' venues.
 * The rules are src/places and src/business, read in LoopGame.
 */

/** One thing to order at a food place (`places.menuOf`): its price in cents of the town's year and the meal kind that feeds `needs.eat`. */
class MenuItemView(val id: String, val label: String, val cents: Int, val text: String, val meal: String)

/** What a food place serves ([kind]: the places module's venue id); [note] says why there is nothing ("No legal drink: Prohibition."). */
class MenuView(val kind: String, val items: List<MenuItemView>, val note: String = "")

/** A shop for sale or to let (`business.businessesForSale`), with what taking it costs now ([buyDueCents]: the price and the till float; [leaseDueCents]: the opening stock and the float). */
class SaleView(
    val askingCents: Int, val askingText: String, val leaseMonthlyCents: Int, val leaseText: String,
    val buyDueCents: Int, val buyDueText: String, val leaseDueCents: Int, val leaseDueText: String, val takingsText: String, val line: String,
)

/** The player's own shop as the panel shows it (made from the saved `Business` and the day log). */
class OwnedShopView(
    val tenure: String, val cashCents: Int, val cashText: String, val staff: Int, val maxStaff: Int, val clerkWageText: String,
    val lastDayText: String, val weekText: String, val valuationCents: Int, val valuationText: String, val warning: String,
)

/** The speakeasy's back door as the panel shows it: [open] now, the [hoursText], the [menu]. */
class SpeakeasyView(val name: String, val open: Boolean, val hoursText: String, val menu: List<MenuItemView>)

/** An unmarked back door behind a shop in 1920-1933 (docs/LOOP.md): [x], [z] in street units, [fx], [fz] the way the back faces. */
class LoopSpeakeasy(val placeId: String, val name: String, val x: Float, val z: Float, val fx: Float, val fz: Float)

object LoopPlaces {
    /** The places module's venue kinds a loop shop kind is served as, in order of preference, and whether only its snacks are kept (an ice cream parlor); null: no menu (a shop that sells goods). */
    class Spec(val kinds: List<String>, val snacksOnly: Boolean = false)

    fun spec(shopKind: String): Spec? = when (shopKind) {
        "diner" -> Spec(listOf("diner"))
        "ice cream parlor" -> Spec(listOf("drive_in", "diner"), snacksOnly = true)
        "bakery" -> Spec(listOf("bakery"))
        "cafe", "cafeteria" -> Spec(listOf("cafeteria", "lunch_counter"))
        "bar" -> Spec(listOf("bar"))
        "tavern" -> Spec(listOf("tavern"))
        else -> null
    }

    /** Venues whose hours come from `places.placeHours` (the others keep the economy's by shop kind); bakery stays on its market hours. */
    fun hoursVenue(shopKind: String): String? = when (shopKind) {
        "diner", "ice cream parlor" -> "diner"
        "bar" -> "bar"; "tavern" -> "tavern"
        "cafe", "cafeteria" -> "cafeteria"
        else -> null
    }

    fun isBar(shopKind: String) = shopKind == "bar" || shopKind == "tavern"

    /** The hotels and the like `lodgingOffers` knows, with their names. */
    val LODGING = listOf("grand_hotel" to "Grand hotel", "commercial_hotel" to "Commercial hotel", "station_hotel" to "Station hotel", "flophouse" to "Flophouse", "motel" to "Motel")
}
