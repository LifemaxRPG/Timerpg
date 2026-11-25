package com.lifemax.rpg.feature.habits.vehicle

/**
 * The car button's choice (docs/CARS.md, "Choosing the car"): which car the player wants beside them. A tap on the button
 * opens the list of every car the world has loaded ([entries], in the catalogue's order, the race Torrel
 * last), a pick spawns it and is remembered ([pick]); a long press brings the same car again ([again]: the last pick, or
 * the first car when there is none). [last] is what the app keeps between games (a preference); a remembered key the
 * catalogue no longer has is forgotten.
 */
class CarPicker(keys: List<String>, last: String? = null) {
    /** The cars on the list, by key, in order. */
    val keys: List<String> = VehicleSpec.CATALOGUE.filter { it in keys } + keys.filter { it !in VehicleSpec.CATALOGUE }

    /** The last car picked (a key on the list), or null. */
    var last: String? = last?.takeIf { it in this.keys }
        private set

    /** The list's lines: each car's key and its short name ("Torrel 1988 RS"), the last pick marked. */
    class Entry(val key: String, val label: String, val last: Boolean)

    val entries: List<Entry> get() = keys.map { Entry(it, VehicleSpec.labelOf(it), it == last) }

    /** The player picked [key]: remembered and returned (null if it is not on the list). */
    fun pick(key: String): String? = key.takeIf { it in keys }?.also { last = it }

    /** "Same again": the last pick, else the first car on the list (null: an empty list). */
    fun again(): String? = (last ?: keys.firstOrNull())?.also { last = it }

    /** The quick option's label: "Same again: Torrel 1988 RS". */
    val againLabel: String? get() = (last ?: keys.firstOrNull())?.let { "Same again: " + VehicleSpec.labelOf(it) }

    companion object {
        /** The list for the cars a world has loaded ([VehicleWorld.specs]). */
        fun of(specs: List<VehicleSpec>, last: String?): CarPicker = CarPicker(specs.map { it.key }, last)
    }
}
