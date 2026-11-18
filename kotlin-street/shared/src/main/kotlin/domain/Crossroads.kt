package com.lifemax.rpg.feature.habits.domain

/**
 * The crossroads as one place, with no drawing in it: the main street's buildings and the cross street's laid out on one
 * ground plan, so the stage can draw either street's own view, the side street seen through the main street's gap, and
 * every view between them as the camera swings round the corner, all from the same buildings.
 *
 * World axes, in street units: X east along the main street, Z north (away from the main street's camera and up the
 * cross street), Y up. The origin is the west corner building's front east corner. The main street's row stands on
 * Z = 0 facing south (its fronts' depth runs to +Z); the cross street's west row on X = 0 facing east (its depth runs to
 * -X); the cross street's east row, when there is one, on X = [MOUTH] facing west.
 *
 * The stage's own cabinet view is kept exact at rest: a building lot is its front ([BuildingSpec.W]) plus the side face's
 * screen width ([BuildingSpec.DX]), so two fronts stand DX + GAP apart in the world; the pavement's lanes are [LANE]
 * world units each, so its 16 screen units are the 33 a real pavement is.
 */
class Crossroads(
    /** The main street, with the side street's mouth as the lot at [junction]. */
    val main: StreetLayout,
    val junction: Int,
    /** The cross street: its own buildings, the first one nearest the main street. */
    val cross: StreetLayout,
    /** The cross street's other side, if the town has one: laid out the same way, facing west. */
    val east: StreetLayout? = null,
) {
    /** The main street's x of the origin: the west corner building's front east edge. */
    val westEdge: Float = main.lefts[junction - 1] + BuildingSpec.W

    /** The middle of the mouth along the main street (main street x). */
    val mouthMid: Float = westEdge + MOUTH / 2f

    /** The cross street's x to world Z: its first front starts one building depth and one gap north of the origin. */
    val zOff: Float = DEPTH + SPACING - (cross.lefts.firstOrNull() ?: -BuildingSpec.W / 2f)

    fun mainX(x: Float): Float = x - westEdge
    fun mainZ(lane: Float): Float = -LANE * lane
    fun crossX(lane: Float): Float = LANE * lane
    fun crossZ(x: Float): Float = x + zOff

    /** The main street x and lane of world point ([wx], [wz]). */
    fun toMain(wx: Float, wz: Float): Pair<Float, Float> = (wx + westEdge) to (-wz / LANE)

    /** The cross street x and lane of world point ([wx], [wz]). */
    fun toCross(wx: Float, wz: Float): Pair<Float, Float> = (wz - zOff) to (wx / LANE)

    /** Where the walk round the corner ends on the cross street (its x): a few steps in from its start. */
    val arriveX: Float get() = cross.start + 20f

    /** The same for the walk back: on the main street, a few steps past the west corner into the mouth. */
    val returnX: Float get() = westEdge + 20f

    /** True if main street x [x] is in the mouth, where the up key turns the corner. */
    fun inMouth(x: Float): Boolean = kotlin.math.abs(x - mouthMid) <= MOUTH_ZONE

    /** True if cross street x [x] is at its start, where the up key (or walking on against the end) turns back. */
    fun atCrossStart(x: Float): Boolean = x < cross.start + 20f + START_ZONE

    /**
     * A box standing on the plan: its footprint [x0]..[x1] by [z0]..[z1] (world units), which street and building it is
     * (street 0 the main street, 1 the cross street's west row, 2 its east row; [index] into that street's layout).
     */
    class Box(val street: Int, val index: Int, val x0: Float, val x1: Float, val z0: Float, val z1: Float)

    /** Every building on the plan (the mouth's lot is not one). */
    val boxes: List<Box> = buildList {
        for (i in main.lefts.indices) {
            if (i == junction) continue
            val x0 = mainX(main.lefts[i])
            add(Box(0, i, x0, x0 + BuildingSpec.W, 0f, DEPTH))
        }
        for (i in cross.lefts.indices) {
            val z0 = crossZ(cross.lefts[i])
            add(Box(1, i, -DEPTH, 0f, z0, z0 + BuildingSpec.W))
        }
        if (east != null) for (i in east.lefts.indices) {
            val z0 = crossZ(east.lefts[i])
            add(Box(2, i, MOUTH, MOUTH + DEPTH, z0, z0 + BuildingSpec.W))
        }
    }

    companion object {
        /** A building's depth: its side sprite's 120 pixels at the fronts' scale. */
        const val DEPTH = 120f * BuildingSpec.W / 224f
        /** World units between two fronts in a row: the lot's side-face width and the gap. */
        const val SPACING = BuildingSpec.DX + BuildingSpec.GAP
        /** World units across the pavement per lane (the stage's 16-unit pavement is 33 units deep). */
        const val LANE = 2.06f
        /** The cross street's width between the corner buildings: pavement, kerb, road, kerb, pavement. */
        const val MOUTH = 178f
        const val PAVE = 33f
        const val KERB = 5f
        /** How far either side of the mouth's middle the up key turns the corner (the mouth's half, less a step). */
        const val MOUTH_ZONE = 77f
        /** How far past its walkable start the cross street's turn-back zone reaches. */
        const val START_ZONE = 40f
    }
}
