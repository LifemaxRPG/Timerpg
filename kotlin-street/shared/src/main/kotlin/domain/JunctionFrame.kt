package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs

/**
 * Where a [Crossroads] plan stands in the town: a rigid 2D transform from the plan's own axes (X along the main street
 * the way the walk goes, Z into the main street's buildings and up the cross street; origin the corner where the two
 * building lines meet) to the town's world (x east, z south), and which way each street's x runs.
 *
 * A right turn (walking +x to a row's end and round its corner building) is the plan as [Crossroads] has always drawn it.
 * A left turn (walking -x to a row's start) is the same plan [mirrored]: both rows are laid out reversed, x' = length - x
 * ([Row.layout] with mirrored), so the walk still goes +X into the mouth and [CornerWalk] runs unchanged; this frame then
 * maps the plan back, its X axis pointing the other way along the row, so the world it gives is the real one.
 */
class JunctionFrame(
    /** The node the turn is at. */
    val node: Int,
    /** The plan's origin in the world. */
    val ox: Float,
    val oz: Float,
    /** The plan's X axis and Z axis in the world (unit vectors). */
    val ex: TownPoint,
    val ez: TownPoint,
    /** True for a left turn: the plan is the mirror image of the world (the rows' layouts are reversed). */
    val mirrored: Boolean,
    /** The main street's row (the one walked into the corner) and the cross street's (the one turned into). */
    val mainRow: String,
    val crossRow: String,
    val mainLength: Float,
    val crossLength: Float,
) {
    /** World point of plan point ([px], [pz]). */
    fun toWorld(px: Float, pz: Float): TownPoint = TownPoint(ox + ex.x * px + ez.x * pz, oz + ex.z * px + ez.z * pz)

    /** Plan point of world point ([wx], [wz]). */
    fun toPlan(wx: Float, wz: Float): TownPoint {
        val dx = wx - ox
        val dz = wz - oz
        return TownPoint(dx * ex.x + dz * ex.z, dx * ez.x + dz * ez.z)
    }

    /** A world direction of plan direction ([dx], [dz]) (a heading, a camera's right). */
    fun dirToWorld(dx: Float, dz: Float): TownPoint = TownPoint(ex.x * dx + ez.x * dz, ex.z * dx + ez.z * dz)

    /** The main street's layout x (the [Crossroads]' street 0) of its row's x, and back (the same map both ways). */
    fun mainLayoutX(rowX: Float): Float = if (mirrored) mainLength - rowX else rowX
    fun mainRowX(layoutX: Float): Float = mainLayoutX(layoutX)

    /** The cross street's layout x (street 1) of its row's x, and back. */
    fun crossLayoutX(rowX: Float): Float = if (mirrored) crossLength - rowX else rowX
    fun crossRowX(layoutX: Float): Float = crossLayoutX(layoutX)
}

/**
 * The crossroads of the town where [fromRow] turns into [toRow] at [node], with both rows' real layouts, so [CornerWalk]
 * runs on it unchanged in its own plan ([Crossroads.frame] maps the plan to the world). [toRow] must be [fromRow]'s
 * turn-in there: at its end (a right turn: the plan as it is) or at its start (a left turn: the plan mirrored).
 *
 * The plan is the real geometry: the main street's building line on Z = 0, the cross street's on X = 0, the node (both
 * roads' centres) at (cross road half, -main road half). The main street is its row from node to node (its layout reaches
 * the node, over the mouth); the cross street starts at the corner (main road half along it), where the walk round
 * arrives and from where the up key walks back.
 */
fun Crossroads.Companion.at(town: Town, node: Int, fromRow: Row, toRow: Row): Crossroads {
    val n = town.node(node)
    val right = fromRow.to == node && fromRow.end.turnIn == toRow.id && toRow.from == node
    val left = fromRow.from == node && fromRow.start.turnIn == toRow.id && toRow.to == node
    require(right || left) { "row ${toRow.id} is not row ${fromRow.id}'s turn-in at node $node" }
    val mainHalf = n.arm(fromRow.road)?.mouthHalf ?: (MOUTH / 2f)
    val crossHalf = n.arm(toRow.road)?.mouthHalf ?: (MOUTH / 2f)
    // the plan's axes: X the way the main street is walked into the corner, Z into its buildings
    val ex: TownPoint
    val ez: TownPoint
    if (right) {
        val a = fromRow.alongAt(fromRow.length)
        ex = a; ez = fromRow.normalAt(fromRow.length)
    } else {
        val a = fromRow.alongAt(0f)
        ex = TownPoint(-a.x, -a.z); ez = fromRow.normalAt(0f)
    }
    // the cross street must run up Z (away from the node) with its buildings at -X
    val ca = if (right) toRow.alongAt(0f) else toRow.alongAt(toRow.length).let { TownPoint(-it.x, -it.z) }
    val cn = if (right) toRow.normalAt(0f) else toRow.normalAt(toRow.length)
    require(abs(ca.x - ez.x) + abs(ca.z - ez.z) < 1e-3f && abs(cn.x + ex.x) + abs(cn.z + ex.z) < 1e-3f) {
        "rows ${fromRow.id} and ${toRow.id} do not meet square at node $node"
    }
    // node = origin + crossHalf * ex - mainHalf * ez
    val frame = JunctionFrame(
        node = node,
        ox = n.x - crossHalf * ex.x + mainHalf * ez.x,
        oz = n.z - crossHalf * ex.z + mainHalf * ez.z,
        ex = ex, ez = ez, mirrored = left,
        mainRow = fromRow.id, crossRow = toRow.id,
        mainLength = fromRow.length, crossLength = toRow.length,
    )
    val main = fromRow.layout(left)
    val cross = toRow.layout(left).let { it.copy(start = mainHalf) }
    return Crossroads(
        main = main,
        // (the mouth is past the main street's last building: no lot of its own)
        junction = main.centers.size,
        cross = cross,
        east = null,
        westEdge = fromRow.length - crossHalf,
        zOff = -mainHalf,
        frame = frame,
    )
}
