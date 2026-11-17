package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The scene's geometry, with no drawing in it: one street, one building per foundation. How a building is put
 * together (its lobby, a floor per habit, its roof), where it stands on the street, how the street follows a
 * finger and settles, where the camera stands, what a tap hits, which windows are lit and who is in them, and
 * what the sky does at an hour. Lengths are in dp at scale 1; a building's x runs from the middle of its front
 * face, its y up from the pavement.
 */

/**
 * A building is drawn face on with its roof and right side showing (a cabinet projection), so a
 * habit's name sits flat on its floor. [W] is the front's width, [DX] and [DY] how far the back
 * edge is drawn to the right and up.
 */
object BuildingSpec {
    const val W = 184f
    const val DX = 14f
    const val DY = 10f

    /** A floor's height at each size: two minutes, normal, stretch. */
    val HEIGHTS = floatArrayOf(44f, 60f, 84f)

    /** One storey of a habit being broken (a run-down floor), and how many are drawn at most. */
    const val RUIN = 26f
    const val MAX_RUINS = 12

    /** The ground floor: the foundation's lobby, its shopfront, awning and sign. */
    const val LOBBY_H = 54f

    /** The parapet over the top floor. */
    const val CORNICE = 7f

    /** The rooftop reward's sign, on its legs. */
    const val SIGN_H = 34f
    const val SIGN_W = 118f

    /** The "add a floor" outline above the roof, and the gap under it. */
    const val GHOST_H = 40f
    const val GHOST_GAP = 10f

    /** Air kept above a building when the camera frames it. */
    const val HEADROOM = 30f

    /** The pocket park beside a building, where a habit that is gone stands as a tree. */
    const val PARK_W = 70f
    const val TREE_H = 60f

    /** The street between two lots: room for a lamp, a tree or a bench. */
    const val GAP = 52f

    /**
     * A floor's windows: [COLS] columns between [MARGIN]s, a band over them and, under them, the
     * band its name plaque is set in ([PLAQUE_BAND]). A run-down storey has a thin band each side
     * ([RUIN_BAND]) and its plaque, on the top storey, is hung over its boarded windows.
     */
    const val COLS = 6
    const val MARGIN = 9f
    const val WIN_W = 17f
    const val BAND_TOP = 6f
    const val PLAQUE_BAND = 17f
    const val RUIN_BAND = 5f

    /** Floors this tall have two rows of windows. */
    const val TWO_ROWS = 76f

    /** Where a floor's name plaque starts, from the left edge of the front, and its height. */
    const val PLAQUE_X = 7f
    const val PLAQUE_H = 13f

    val PITCH: Float
        get() = (W - 2 * MARGIN) / COLS
}

enum class PieceKind {
    /** The ground floor: the foundation. */
    LOBBY,

    /** A habit being built. */
    FLOOR,

    /** One storey of a habit being broken. */
    RUIN,

    /** The rooftop reward's sign. */
    SIGN,

    /** Where the next habit would go. */
    GHOST,
}

/**
 * One drawn part of a building: [bottom] and [height] above the pavement, [width] of its front.
 * [id] is the habit's id (the stack's for the lobby, the sign and the ghost). [labelled] is the
 * part that carries the habit's name (the top storey of a habit being broken). A part that stands
 * back from the pavement (one of our houses, in its yard) leans with the view: its front is
 * [lean] times the side shift to the shown side, and its side face [deep] as deep as a shop's.
 * [roofFace]: a roof face lies on it (a house's roof part already reaches its ridge).
 */
data class Piece(
    val kind: PieceKind,
    val id: String,
    val bottom: Float,
    val height: Float,
    val width: Float,
    val labelled: Boolean = true,
    val block: HabitBlock? = null,
    val lean: Float = 0f,
    val deep: Float = 1f,
    val roofFace: Boolean = true,
) {
    val top: Float
        get() = bottom + height

    val middle: Float
        get() = bottom + height / 2f
}

/** A habit that is gone: a tree in the pocket park, [x] from the building's middle. */
data class TreeSpot(val id: String, val name: String, val x: Float)

/**
 * A building from its lobby up. [height] reaches the top of its highest part's roof face; [roof] is
 * the top of its highest storey (the lobby's when nothing stands on it), where the parapet runs;
 * [lot] is the width it takes on the street, its park included.
 */
data class BuildingLayout(
    val stackId: String,
    val pieces: List<Piece>,
    val trees: List<TreeSpot>,
    val height: Float,
    val roof: Float,
    val lot: Float,
) {
    /** The part that carries habit [id]'s name. */
    fun labelled(id: String): Piece? =
        pieces.firstOrNull { it.id == id && it.labelled && it.kind != PieceKind.LOBBY }

    /** How high the middle of habit [id] is; a tree's is near the ground. */
    fun aim(id: String): Float? = span(id)?.let { (it.first + it.second) / 2f }

    /**
     * From where to where habit [id] stands above the pavement (all its storeys, for a habit being
     * broken), for the camera; a tree's is from the ground up.
     */
    fun span(id: String): Pair<Float, Float>? {
        var from = Float.NaN
        var to = Float.NaN
        for (p in pieces) {
            if (p.id != id || (p.kind != PieceKind.FLOOR && p.kind != PieceKind.RUIN)) continue
            if (from.isNaN()) from = p.bottom
            to = p.top
        }
        if (!from.isNaN()) return from to to
        return trees.firstOrNull { it.id == id }?.let { 0f to BuildingSpec.TREE_H }
    }

    /**
     * How much room the camera would like over habit [id]: the height of the habit stacked on it
     * (so that floor shows whole too) and a little more, between [AIM_AIR] and [AIM_AIR_MAX]. A
     * habit with nothing above it, or a tree, wants [AIM_AIR].
     */
    fun aimAir(id: String): Float {
        val last = pieces.indexOfLast { it.id == id && it.kind != PieceKind.LOBBY }
        if (last < 0) return AIM_AIR
        val next = pieces.getOrNull(last + 1)
        if (next == null || (next.kind != PieceKind.FLOOR && next.kind != PieceKind.RUIN))
            return AIM_AIR
        val above = span(next.id) ?: return AIM_AIR
        return (above.second - above.first + AIM_ABOVE).coerceIn(AIM_AIR, AIM_AIR_MAX)
    }
}

/**
 * Builds [stack] up: its lobby, then each habit in order (a habit being built as a floor of its
 * size, one being broken as its storeys still standing, a cleared one as a tree in the park beside
 * it), the rooftop sign when the stack has a reward, and, with [ghost], the outline where the next
 * habit would go. [live] is today: only today has the sign and the ghost.
 */
fun buildingLayout(
    stack: HabitStack,
    live: Boolean = true,
    ghost: Boolean = false,
): BuildingLayout {
    val b = BuildingSpec
    // where the side street opens: an empty lot, as wide (with its two gaps) as the side street in the turn's scene
    if (stack.id == JUNCTION_ID) return BuildingLayout(stack.id, listOf(Piece(PieceKind.LOBBY, stack.id, 0f, 0f, JUNCTION_LOT)), emptyList(), 0f, 0f, JUNCTION_LOT)
    com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt.buildings[stack.id]?.let { return spriteLayout(stack, it) }
    val pieces = ArrayList<Piece>()
    pieces += Piece(PieceKind.LOBBY, stack.id, 0f, b.LOBBY_H, b.W)
    var y = b.LOBBY_H
    for (h in stack.blocks) {
        if (h.cleared) continue
        if (h.fade) {
            val storeys = min(h.left, b.MAX_RUINS)
            for (f in 0 until storeys) {
                pieces +=
                    Piece(
                        PieceKind.RUIN,
                        h.id,
                        y,
                        b.RUIN,
                        b.W,
                        labelled = f == storeys - 1,
                        block = h,
                    )
                y += b.RUIN
            }
        } else {
            val tall = b.HEIGHTS[h.size.coerceIn(0, 2)]
            pieces += Piece(PieceKind.FLOOR, h.id, y, tall, b.W, labelled = com.lifemax.rpg.feature.habits.presentation.scene.StageTuning.plaques, block = h)
            y += tall
        }
    }
    val roof = y
    y += b.CORNICE
    if (live && stack.treat.isNotEmpty() && stack.blocks.any { !it.cleared }) {
        pieces += Piece(PieceKind.SIGN, stack.id, y, b.SIGN_H, b.SIGN_W)
        y += b.SIGN_H
    }
    if (live && ghost) {
        y += b.GHOST_GAP
        pieces += Piece(PieceKind.GHOST, stack.id, y, b.GHOST_H, b.W * .82f)
        y += b.GHOST_H
    }
    val gone = stack.blocks.filter { it.cleared }.take(MAX_TREES)
    val trees =
        gone.mapIndexed { i, h ->
            TreeSpot(
                h.id,
                h.title,
                b.W / 2f + b.DX + 18f + i * (b.PARK_W - 30f) / max(1, MAX_TREES - 1),
            )
        }
    val lot = b.W + b.DX + if (trees.isEmpty()) 0f else b.PARK_W
    return BuildingLayout(stack.id, pieces, trees, y + b.DY, roof, lot)
}

/**
 * The layout of one of OUR buildings: a storefront, then one floor per middle sprite, as tall as the sprites are
 * (scaled to the lot's width), and room above for the rooftop sign.
 */
private fun spriteLayout(stack: HabitStack, sb: com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding): BuildingLayout {
    val b = BuildingSpec
    val k = b.W / com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt.SRC_W
    sb.house?.let { h ->
        // a pitched-roof house: one storey, back in its yard; its roofline is the eave's edge, and above it stand the
        // lift of its yard, the roof's rise, the ridge cap and the flue
        val sh = h.shape
        val lift = b.DY * sh.setback / HouseView.DEPTH
        val roof = sh.h * k
        val top = sh.screenTop
        // what a tap finds: the house's own front (its picture's width, standing on its yard's lift, not the whole lot,
        // so the sky and the yard beside it are not the house), then its roof up to the ridge
        val w = sb.front.width * k
        val ridge = (sh.ridge + 2f) * k + b.DY * (sh.setback + sh.depth / 2f) / HouseView.DEPTH
        // each leaning with the view as far as it stands back: the yard in front of the wall (its porch or steps) by half
        // its depth, the wall by the yard's, the roof by the yard's and a quarter of the house's (eave to ridge)
        val back = sh.setback / HouseView.DEPTH
        val deep = sh.depth / HouseView.DEPTH
        val pieces = listOf(
            Piece(PieceKind.LOBBY, stack.id, 0f, lift, w, lean = back / 2f, deep = 0f, roofFace = false),
            Piece(PieceKind.LOBBY, stack.id, lift, roof, w, lean = back, deep = deep, roofFace = false),
            Piece(PieceKind.LOBBY, stack.id, lift + roof, (ridge - lift - roof).coerceAtLeast(1f), w, lean = back + deep / 4f, deep = deep / 2f, roofFace = false),
        )
        return BuildingLayout(stack.id, pieces, emptyList(), maxOf(top, roof + lift + b.DY), roof, b.W + b.DX)
    }
    val pieces = ArrayList<Piece>()
    // the floors come from the art (the caller's block count may differ): the front's own middles, its own roofline
    val floors = stack.blocks.filter { !it.cleared }.take(sb.middles)
    val bodyH = (sb.front.height - com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt.MID_H * sb.middles) * k
    pieces += Piece(PieceKind.LOBBY, stack.id, 0f, bodyH, b.W)
    var y = bodyH
    for (h in floors) {
        val tall = com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt.MID_H * k
        pieces += Piece(PieceKind.FLOOR, h.id, y, tall, b.W, labelled = false, block = h)
        y += tall
    }
    val roof = bodyH + com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt.MID_H * k * sb.middles
    y = roof
    val signH = ((sb.sign?.height ?: 0) * k).coerceAtLeast((sb.prop?.height ?: 0) * k * .8f)
    return BuildingLayout(stack.id, pieces, emptyList(), y + b.DY + signH, roof, b.W + b.DX)
}

/** How many trees the park beside a building holds. */
const val MAX_TREES = 3

enum class HitKind {
    LOBBY,
    FLOOR,
    SIGN,
    GHOST,
    TREE,
}

/** What a tap landed on: a floor or tree by its habit's id, the others by the stack's. */
data class Hit(val kind: HitKind, val id: String)

/**
 * What stands at ([x], [at]) of this building, in its own units: the part whose front, roof or side
 * face holds the point, the highest one first (a part hides the roof face of the one under it),
 * else a tree in its park. [shift] is where the back edge is drawn against the front
 * ([Perspective.shift]): the right side face when positive, the left when negative, none at 0.
 */
fun BuildingLayout.hit(x: Float, at: Float, shift: Float = BuildingSpec.DX): Hit? {
    val b = BuildingSpec
    val depth = abs(shift)
    for (p in pieces.asReversed()) {
        val half = p.width / 2f
        // from the middle of this part's own front (which leans with the view when it stands back)
        val px = x - p.lean * shift
        val front = abs(px) <= half && at >= p.bottom && at <= p.top
        // the roof face leans with the shown side as it goes up; the side face rises as it goes out
        val up = at - p.top
        val onTop =
            p.roofFace && up > 0f && up <= b.DY && (px - up * shift / b.DY).let { it >= -half && it <= half }
        val out = if (shift < 0f) -half - px else px - half
        val onSide =
            depth > 0f &&
                out > 0f &&
                out <= depth * p.deep &&
                (at - out * b.DY / depth).let { it >= p.bottom && it <= p.top }
        if (front || onTop || onSide)
            return Hit(
                when (p.kind) {
                    PieceKind.LOBBY -> HitKind.LOBBY
                    PieceKind.FLOOR,
                    PieceKind.RUIN -> HitKind.FLOOR
                    PieceKind.SIGN -> HitKind.SIGN
                    PieceKind.GHOST -> HitKind.GHOST
                },
                p.id,
            )
    }
    return trees
        .firstOrNull { abs(x - it.x) <= 16f && at >= -8f && at <= BuildingSpec.TREE_H }
        ?.let { Hit(HitKind.TREE, it.id) }
}

/* ---------------------------------------------------------------- the street */

enum class FurnitureKind {
    LAMP,
    TREE,
    BENCH,
}

/** Something on the pavement: [x] along the street, in street units. */
data class Furniture(val kind: FurnitureKind, val x: Float, val seed: Int)

/**
 * The street: each building's middle ([centers]) and the left edge of its lot, side by side with a
 * [BuildingSpec.GAP] between lots; the furniture standing in the gaps and at both ends; and the
 * street's own extent, from [start] to [end].
 */
data class StreetLayout(
    val centers: List<Float>,
    val lefts: List<Float>,
    val furniture: List<Furniture>,
    val start: Float,
    val end: Float,
) {
    val length: Float
        get() = end - start
}

/**
 * Lays [buildings] along one street, the first one's middle at 0. Each gap holds one piece of
 * furniture, by turns a lamp, a tree, a lamp, a bench; a lamp and a tree finish either end.
 */
/** The stack id of the gap where the side street opens off the main street. */
const val JUNCTION_ID = "junction"
/**
 * Its lot: with the gap on either side the opening is 164 street units, the turn's 200 sprite pixels (its side street,
 * x 0 to 200, between the corner buildings) at 184 units per 224 pixels.
 */
const val JUNCTION_LOT = 164f - 2f * BuildingSpec.GAP

fun streetLayout(buildings: List<BuildingLayout>): StreetLayout {
    val b = BuildingSpec
    val centers = ArrayList<Float>(buildings.size)
    val lefts = ArrayList<Float>(buildings.size)
    val furniture = ArrayList<Furniture>()
    var left = -b.W / 2f
    for ((i, l) in buildings.withIndex()) {
        lefts += left
        centers += left + b.W / 2f
        left += l.lot + b.GAP
        // (no lamp or tree in the mouth of the side street)
        if (i < buildings.size - 1 && l.stackId != JUNCTION_ID && buildings[i + 1].stackId != JUNCTION_ID)
            furniture += Furniture(CYCLE[i % CYCLE.size], left - b.GAP / 2f, i)
    }
    val start = -b.W / 2f - b.GAP
    val end = if (buildings.isEmpty()) b.W / 2f + b.GAP else left
    furniture += Furniture(FurnitureKind.LAMP, start + b.GAP / 2f, -1)
    furniture += Furniture(FurnitureKind.TREE, start - b.GAP / 2f, -2)
    if (buildings.isNotEmpty()) {
        // tree then lamp at the far end: the lamp's head hangs out over the open pavement, not into the tree
        furniture += Furniture(FurnitureKind.TREE, end - b.GAP / 2f, -4)
        furniture += Furniture(FurnitureKind.LAMP, end + b.GAP / 2f, -3)
    }
    return StreetLayout(centers, lefts, furniture, start - b.GAP, end + b.GAP)
}

private val CYCLE =
    arrayOf(FurnitureKind.LAMP, FurnitureKind.TREE, FurnitureKind.LAMP, FurnitureKind.BENCH)

/**
 * Where along the street [x] is, in buildings: 2.4 is four tenths of the way from the third
 * building's middle to the fourth's. Past either end it carries on at the end's own spacing.
 */
fun StreetLayout.position(x: Float): Float {
    val n = centers.size
    if (n == 0) return 0f
    if (n == 1) return (x - centers[0]) / SPACING_FALLBACK
    if (x <= centers[0]) return (x - centers[0]) / (centers[1] - centers[0])
    if (x >= centers[n - 1]) return n - 1 + (x - centers[n - 1]) / (centers[n - 1] - centers[n - 2])
    var i = 0
    while (i < n - 2 && x > centers[i + 1]) i++
    return i + (x - centers[i]) / (centers[i + 1] - centers[i])
}

/** The street's x at [position] (in buildings), the inverse of [position]. */
fun StreetLayout.xAt(position: Float): Float {
    val n = centers.size
    if (n == 0) return 0f
    if (n == 1) return centers[0] + position * SPACING_FALLBACK
    val i = floor(position).toInt().coerceIn(0, n - 2)
    return lerp(centers[i], centers[i + 1], position - i)
}

/** The building whose middle is nearest [x]: the one in front, which the controls act on. */
fun StreetLayout.centred(x: Float): Int =
    if (centers.isEmpty()) 0 else position(x).roundToInt().coerceIn(0, centers.size - 1)

/** The spacing used for a street of one building, which has no neighbour to measure by. */
private const val SPACING_FALLBACK = BuildingSpec.W + BuildingSpec.DX + BuildingSpec.GAP

/** [x] with the pull past either end of the street held back to a third. */
fun StreetLayout.rubberBand(x: Float): Float {
    if (centers.isEmpty()) return x
    val lo = centers.first()
    val hi = centers.last()
    return when {
        x < lo -> lo + (x - lo) * RUBBER
        x > hi -> hi + (x - hi) * RUBBER
        else -> x
    }
}

private const val RUBBER = .32f

/**
 * The building a release settles on: the street coasts on at [velocity] (street units a second,
 * positive towards later buildings) as on ice that slows it, and the building whose middle is
 * nearest where it would stop is the one it snaps to, softly.
 */
fun StreetLayout.settle(x: Float, velocity: Float): Int {
    if (centers.isEmpty()) return 0
    return centred(x + coast(velocity))
}

/** How far the street coasts after a release at [velocity]. */
fun coast(velocity: Float): Float = velocity * COAST_SECONDS

const val COAST_SECONDS = .32f

/**
 * The part of the view the buildings may use, in dp from the top of a view [height] tall: under the
 * [top] band (the heading and its buttons) and above the [bottom] band (the rough-day switch, the
 * big button and the dots, or the time-lapse). The pavement line is [GROUND_MARGIN] above the
 * bottom band; the buildings' window runs from [windowTop] to [ground].
 */
data class SceneWindow(val height: Float, val top: Float, val bottom: Float) {
    val ground: Float
        get() = height - bottom - GROUND_MARGIN

    /** The room for a building, never under [MIN_AVAIL] (a view too small to hold both bands). */
    val avail: Float
        get() = max(MIN_AVAIL, ground - top)

    val windowTop: Float
        get() = ground - avail

    /** Where a point [above] the pavement line (in dp) is drawn, from the top of the view. */
    fun y(above: Float): Float = ground - above

    /**
     * The top of the bottom band (the rough-day switch, the big button, the dots), from the top.
     */
    val bandTop: Float
        get() = height - bottom

    /**
     * The lowest the pavement line may be drawn, from the top: [LOBBY_AIR] over the bottom band, so
     * a building's ground floor, its sign and its awning always stand clear of the controls.
     */
    val lowest: Float
        get() = bandTop - LOBBY_AIR

    /** How far a camera may lower the street from [ground]: what is left of the margin. */
    val panRoom: Float
        get() = lowest - ground

    companion object {
        const val GROUND_MARGIN = 30f
        const val MIN_AVAIL = 120f

        /** Air kept between the pavement line and the bottom band, however far the camera tilts. */
        const val LOBBY_AIR = 10f

        /** The same, with the usual margin: the default of a camera's room. */
        const val PAN_ROOM = GROUND_MARGIN - LOBBY_AIR
    }
}

/**
 * Where the camera stands on the building in front: its [scale], and [pan], how far the whole
 * street is lowered so the upper floors of a building too tall for the window show, in dp, between
 * 0 (the pavement on its line) and [maxPan] (the top of the building at the top of the window).
 */
data class Camera(val scale: Float, val pan: Float, val maxPan: Float) {
    /** How far above the pavement line a point [y] of the building is drawn, in dp. */
    fun above(y: Float): Float = scale * y - pan
}

const val CAMERA_MIN = .7f
const val CAMERA_MAX = 1.2f

/**
 * The smallest a building too tall for [CAMERA_MIN] is drawn, so that the floor to build next can
 * still be reached without the street dropping into the bottom band. Names stay readable above it.
 */
const val CAMERA_TALL_MIN = .6f

/** Air the camera keeps above the floor it aims at, so what comes after it shows too. */
const val AIM_AIR = 30f

/** What the camera would like over the floor it aims at, at most: the floor above it, whole. */
const val AIM_AIR_MAX = 70f

/** Extra room over the floor above the aimed one. */
const val AIM_ABOVE = 8f

/**
 * How far under the top of the window a building too tall for it fades out, in dp, so it never runs
 * under the heading.
 */
const val TOP_FADE = 20f

/**
 * Frames a building [height] tall in a window [avail] tall. A short one is enlarged to fill it, up
 * to [CAMERA_MAX]; a tall one is made smaller until it fits, but never under [CAMERA_MIN], where
 * its names would stop being readable: then the street is lowered instead, as a head tilts back.
 * The lowering never passes [panRoom] (the window's [SceneWindow.PAN_ROOM]): the ground floor stays
 * clear of the bottom band. It is [pan] when the owner dragged it; else no further than it takes
 * for [aim] (the floor to build next, from its bottom to its top) to show whole with [air] over it
 * (the floor above it too), and never so far that its foot drops out of the window; else, with
 * nothing to aim at, as far as it may. A building too tall for that is drawn a little smaller, down
 * to [CAMERA_TALL_MIN], until the aimed floor can be reached; under that [air] gives way to
 * [AIM_AIR]. When the whole building fits by lowering the street it shows whole.
 */
fun focusCamera(
    height: Float,
    avail: Float,
    aim: Pair<Float, Float>? = null,
    pan: Float? = null,
    panRoom: Float = SceneWindow.PAN_ROOM,
    air: Float = AIM_AIR,
): Camera {
    val full = height + BuildingSpec.HEADROOM
    val fit = avail / full
    var scale = fit.coerceIn(CAMERA_MIN, CAMERA_MAX)
    var over = AIM_AIR
    if (fit < CAMERA_MIN && aim != null) {
        val reach = avail + panRoom
        val wish = max(air, AIM_AIR)
        val wide = reach / (aim.second + wish)
        if (wide >= CAMERA_TALL_MIN) over = wish
        scale =
            (if (over == wish) wide else reach / (aim.second + AIM_AIR)).coerceIn(
                CAMERA_TALL_MIN,
                CAMERA_MIN,
            )
    }
    val top = scale * full - avail
    val maxPan = min(max(0f, top), panRoom)
    val wanted =
        pan
            ?: if (top in 0f..panRoom) top
            else
                aim?.let { (bottom, to) -> min(scale * (to + over) - avail, scale * bottom) }
                    ?: maxPan
    return Camera(scale, wanted.coerceIn(0f, maxPan), maxPan)
}

/** The camera between two buildings' cameras, [t] of the way from [a] to [b]. */
fun Camera.towards(b: Camera, t: Float): Camera =
    Camera(lerp(scale, b.scale, t), lerp(pan, b.pan, t), lerp(maxPan, b.maxPan, t))

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * The whole street at once: the [scale] that fits every lot and the tallest building into a view
 * [width] wide and [avail] tall, between [STREET_MIN_SCALE] and [STREET_MAX_SCALE], and the
 * street's [middle], which stands in the middle of the view.
 */
data class StreetView(val scale: Float, val middle: Float)

const val STREET_MAX_SCALE = .7f
const val STREET_MIN_SCALE = .08f

/** Room kept clear over the zoomed-out street: the sun or the moon stands there. */
const val STREET_LABEL = 34f

fun streetView(street: StreetLayout, heights: List<Float>, width: Float, avail: Float): StreetView {
    val b = BuildingSpec
    if (street.centers.isEmpty()) return StreetView(STREET_MAX_SCALE, 0f)
    val from = street.lefts.first() - b.GAP / 2f
    val to = street.end - b.GAP / 2f
    val tallest = (heights.maxOrNull() ?: 0f) + b.HEADROOM / 2f
    val scale =
        min(STREET_MAX_SCALE, min((width - 24f) / (to - from), (avail - STREET_LABEL) / tallest))
            .coerceAtLeast(STREET_MIN_SCALE)
    return StreetView(scale, (from + to) / 2f)
}

/** The building of the zoomed-out street under [x] (street units), if any. */
fun StreetLayout.buildingAt(x: Float, buildings: List<BuildingLayout>): Int? =
    lefts.indices.firstOrNull { x >= lefts[it] - 8f && x <= lefts[it] + buildings[it].lot + 8f }

/** The weeks ago a time-lapse slider shows at [fraction] of its track: the right end is today. */
fun lapseWeeks(fraction: Float, max: Int): Int =
    ((1f - fraction.coerceIn(0f, 1f)) * max).roundToInt()

/** And back: where the slider's thumb is for [weeks] ago. */
fun lapseFraction(weeks: Int, max: Int): Float =
    if (max <= 0) 1f else 1f - weeks.coerceIn(0, max) / max.toFloat()

/* ---------------------------------------------------------------- windows and the people in them */

/** A window of a floor: its column and row, and its rectangle on the floor's front. */
data class WindowSpot(
    val col: Int,
    val row: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

/**
 * The windows of a floor [height] tall: x from the front's middle, y down from the floor's top. The
 * columns keep one grid up the whole building; a floor of [BuildingSpec.TWO_ROWS] or more has two
 * rows. A habit's floor keeps the band under its windows for its name plaque; a run-down storey
 * ([ruin]) only a thin band.
 */
fun floorWindows(height: Float, ruin: Boolean = false): List<WindowSpot> {
    val b = BuildingSpec
    val rows = if (height >= b.TWO_ROWS) 2 else 1
    val over = if (ruin) b.RUIN_BAND else b.BAND_TOP
    val under = if (ruin) b.RUIN_BAND else b.PLAQUE_BAND
    val gap = 6f
    val h = (height - over - under - gap * (rows - 1)) / rows
    val out = ArrayList<WindowSpot>()
    for (r in 0 until rows) for (c in 0 until b.COLS) {
        val x = -b.W / 2f + b.MARGIN + c * b.PITCH + (b.PITCH - b.WIN_W) / 2f
        out += WindowSpot(c, r, x, over + r * (h + gap), b.WIN_W, h)
    }
    return out
}

/**
 * How lived-in a floor is: [lit] of its windows have their lights on (people take turns in them).
 */
data class Occupancy(val lit: Int)

/**
 * The lights of a floor with [windows] windows, done today, after [reps] repetitions: one window
 * while it is new (under [HabitRules.clay]), two from then, most of them from [HabitRules.stone],
 * every window from [HabitRules.gold]. Who is in them, and when, is [Presence].
 */
fun occupancy(reps: Int, windows: Int, rules: HabitRules = HabitsRules.RULES): Occupancy {
    if (windows <= 0) return Occupancy(0)
    val lit =
        when {
            reps >= rules.gold -> windows
            reps >= rules.stone -> ceil(windows * .7f).toInt()
            reps >= rules.clay -> 2
            else -> 1
        }
    return Occupancy(lit.coerceIn(1, windows))
}

/**
 * The order a floor's [count] windows light up in, its own for [key]: the first [Occupancy.lit] of
 * it are lit.
 */
fun lightOrder(key: String, count: Int): IntArray {
    val order = IntArray(count) { it }
    val dice = Dice(key + "o")
    for (i in count - 1 downTo 1) {
        val j = (dice.next() * (i + 1)).toInt().coerceAtMost(i)
        val t = order[i]
        order[i] = order[j]
        order[j] = t
    }
    return order
}

/** A run-down storey's window: boarded up, dimly lit, or dark. */
enum class RuinWindow {
    BOARDED,
    DIM,
    DARK,
}

/**
 * Window [index] of storey [storey] of a habit being broken, the same every time: the higher the
 * storey (the nearer it is to coming down), the more of them are boarded up.
 */
fun ruinWindow(key: String, storey: Int, storeys: Int, index: Int): RuinWindow {
    val v = Dice("$key/$storey/$index").next()
    val boarded = .25f + .45f * (storey + 1) / max(1, storeys)
    return when {
        v < boarded -> RuinWindow.BOARDED
        v < boarded + .25f -> RuinWindow.DIM
        else -> RuinWindow.DARK
    }
}

/* ---------------------------------------------------------------- the sky */

enum class SkyPhase {
    NIGHT,
    DUSK,
    DAY,
}

/** Night until 5:30 and from 20:30; the hours around sunrise and sunset are dusk. */
fun skyPhase(hour: Float): SkyPhase =
    when {
        hour < 5.5f || hour >= 20.5f -> SkyPhase.NIGHT
        hour < 7.5f || hour >= 18f -> SkyPhase.DUSK
        else -> SkyPhase.DAY
    }

/**
 * The sky's tone at [hour], for colours that blend: 0 is night, 1 dusk, 2 day, with an hour's blend
 * from one to the next around dawn and dusk.
 */
fun skyTone(hour: Float): Float {
    val h = ((hour % 24f) + 24f) % 24f
    return when {
        h < 5f -> 0f
        h < 6f -> h - 5f
        h < 7f -> 1f
        h < 8f -> 1f + (h - 7f)
        h < 17.5f -> 2f
        h < 18.5f -> 2f - (h - 17.5f)
        h < 20f -> 1f
        h < 21f -> 1f - (h - 20f)
        else -> 0f
    }
}

/** How bright the stars are at a sky [tone]: all of it at night, faint at dusk, none by day. */
fun starsShown(tone: Float): Float = ((1.5f - tone) / 1.5f).coerceIn(0f, 1f)

/**
 * Where the sun (by day) or the moon (by night) stands at [hour]: how far across the sky, 0 to 1,
 * and how high, 0 at the horizon ends of its arc to 1 at the top of it.
 */
fun orbAt(hour: Float): Pair<Float, Float> {
    val night = skyPhase(hour) == SkyPhase.NIGHT
    val t = (if (night) ((hour + 3.5f) % 24f) / 9f else (hour - 5.5f) / 15f).coerceIn(0f, 1f)
    return t to sin(t * Math.PI).toFloat()
}

/**
 * Where the sun or the moon is drawn, in dp from the left and the top of a view [width] wide:
 * beside the building in front (left before the middle of its arc, right after; [column] is half
 * its width), never in the heading: between [top] (the top of the buildings' window) and [horizon].
 * Zoomed out ([street] 0 to 1) it stands in the strip kept clear over the street.
 */
fun orbSpot(
    hour: Float,
    width: Float,
    top: Float,
    horizon: Float,
    column: Float,
    street: Float = 0f,
): Pair<Float, Float> {
    val (across, up) = orbAt(hour)
    val lane = max(0f, width / 2f - column)
    val fromEdge = max(ORB_EDGE, lane / 2f)
    val x = if (across < .5f) fromEdge else width - fromEdge
    val high = top + ORB_TOP
    val low = max(high, horizon - ORB_LOW)
    return x to lerp(lerp(low, high, up), high, street.coerceIn(0f, 1f))
}

/**
 * The orb's middle is kept this far from the view's edge, under the window's top and over the
 * horizon.
 */
const val ORB_EDGE = 34f
const val ORB_TOP = 18f
const val ORB_LOW = 44f

/** The same few random numbers for the same key, every time: what makes a floor's marks its own. */
class Dice(key: String) {
    private var h: Int = FNV

    init {
        for (c in key) h = (h xor c.code) * 16777619
    }

    /** The next number, from 0 up to but not 1. */
    fun next(): Float {
        h = h * 1664525 + 1013904223
        return (h.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }

    private companion object {
        const val FNV = -2128831035 // 2166136261 as a 32-bit integer
    }
}
