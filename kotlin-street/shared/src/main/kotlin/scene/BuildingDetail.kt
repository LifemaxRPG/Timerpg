package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.RuinWindow

/*
 * The architecture of a facade as plain shapes, worked out once per floor and per building and turned into paths by
 * BuildingArt: the surrounds of the windows (frames, lintels, keystones, sills and the shadow under each), the
 * string course at a floor's top, a drainpipe down one side with its brackets, the odd air conditioner or
 * balcony, the cornice's dentils and brackets, what stands on the roof, and what a run-down storey has lost. Pure
 * Kotlin (no Compose), so the same shapes are drawn by the painter and by the JVM harness that looks at them, and
 * the choices (which side the pipe is on, which floor has a balcony) are tested to be the same every time and
 * sparse.
 */

/** What a shape is drawn in: the facade's colours and the shades over them. */
internal enum class DetailInk {
    /** The facade's trim colour (frames, sills, lintels, the string course). */
    TRIM,

    /** White at a low alpha: the sky catching a sill's top, a keystone, a coping. */
    LIGHT,

    /** Black at a low alpha: the shadow line under a sill, a lintel, a bracket. */
    SHADE,

    /** Dark painted iron: pipes, railings, brackets. */
    IRON,

    /** The pale highlight along a pipe, a railing's top rail. */
    IRON_LIGHT,

    /** A pale grey box: an air conditioner, a dish. */
    BOX,

    /** The dark of a grille, a vent. */
    BOX_DARK,

    /** A spray-can tag. */
    SPRAY,

    /** The faces of a reveal (the jambs and the head of an opening): the wall seen in its depth. */
    REVEAL,

    /** A raised surface (a pilaster, a pier) a touch lighter for the sky it catches. */
    RAISED,

    /**
     * Faces turned to the left (a right-hand jamb, a pilaster's left edge): lit when the light
     * comes from the left, in shade when it comes from the right ([FacadeLook]).
     */
    FACE_L,

    /** Faces turned to the right: lit from the right, in shade from the left. */
    FACE_R,

    /** The shadows things cast with the light from the left: lying a little to their right. */
    CAST_FROM_LEFT,

    /** The shadows things cast with the light from the right: lying a little to their left. */
    CAST_FROM_RIGHT,

    /** Soft occlusion at an inside corner (the sky's light blocked), whatever the light. */
    AO,

    /** Faces turned down (an opening's head, a slab's underside): in shade under the sun. */
    SOFFIT,

    /** The head's shadow on the glass set back in an opening. */
    GLASS_TOP,

    /**
     * A jamb's shadow on the glass: the left jamb's with the light from the left, and the right's.
     */
    GLASS_FROM_LEFT,
    GLASS_FROM_RIGHT,

    /** Glazing bars across a pane, and their lit edge. */
    BAR,
    BAR_LIGHT,
}

/**
 * How the facade's inks are drawn for a light: pure, so the painter and the harness that looks at
 * the facades agree. [side] is the light's side times its strength (-1 from the left .. 1 from the
 * right, [com.lifemax.rpg.feature.habits.domain.SceneLight.sunSide]), [direct] how hard and sharp
 * it is (0 under a full overcast or in the dark, about 1 under a high sun), [dark] the sky's dark.
 * A light-dependent ink's value is signed: positive is white at that alpha (lit), negative black at
 * minus that alpha (shade); the others are their own colour at the alpha.
 */
internal object FacadeLook {
    /** Whether [ink] is drawn white or black by the sign of [alpha]. */
    fun signed(ink: DetailInk): Boolean =
        when (ink) {
            DetailInk.FACE_L,
            DetailInk.FACE_R,
            DetailInk.CAST_FROM_LEFT,
            DetailInk.CAST_FROM_RIGHT,
            DetailInk.AO,
            DetailInk.SOFFIT,
            DetailInk.GLASS_TOP,
            DetailInk.GLASS_FROM_LEFT,
            DetailInk.GLASS_FROM_RIGHT,
            DetailInk.BAR_LIGHT -> true
            else -> false
        }

    fun alpha(ink: DetailInk, side: Float, direct: Float, dark: Float): Float =
        when (ink) {
            DetailInk.TRIM -> .95f
            DetailInk.LIGHT -> .35f * (1f - .45f * dark)
            DetailInk.SHADE -> .32f
            DetailInk.SPRAY -> .85f
            DetailInk.RAISED -> .1f * (1f - .5f * dark)
            DetailInk.REVEAL -> 1f
            DetailInk.FACE_L -> face(-side)
            DetailInk.FACE_R -> face(side)
            DetailInk.CAST_FROM_LEFT -> -CAST * maxOf(0f, -side) * (.35f + .65f * direct)
            DetailInk.CAST_FROM_RIGHT -> -CAST * maxOf(0f, side) * (.35f + .65f * direct)
            DetailInk.AO -> -.15f - .05f * dark
            DetailInk.SOFFIT -> -(.12f + .26f * direct) * (1f - .4f * dark)
            DetailInk.GLASS_TOP -> -(.2f + .3f * direct) * (1f - .55f * dark)
            DetailInk.GLASS_FROM_LEFT -> -.34f * maxOf(0f, -side) * (.4f + .6f * direct)
            DetailInk.GLASS_FROM_RIGHT -> -.34f * maxOf(0f, side) * (.4f + .6f * direct)
            DetailInk.BAR -> 1f
            DetailInk.BAR_LIGHT -> .34f * (1f - .6f * dark)
            else -> 1f
        }

    /** A face turned [v] towards the light (-1 away .. 1 into it): lit white, or shade. */
    private fun face(v: Float): Float = if (v >= 0f) .38f * v else .36f * v

    /** The deepest a cast shadow is. */
    const val CAST = .4f

    /**
     * The glass's reflection is this strong (of the sky behind the viewer) on a dark pane by day,
     * fading as the room behind it is lit ([lamp] 0..1, the sky's [dark]).
     */
    fun reflection(lamp: Float, dark: Float): Float = (.92f - .25f * dark) * (1f - .82f * lamp)
}

/** Rectangles and polylines by ink, in building units. Filled once, read every frame. */
internal class Shapes {
    private val rects = Array(DetailInk.entries.size) { FloatList() }
    private val lines = Array(DetailInk.entries.size) { ArrayList<FloatArray>() }

    fun rect(ink: DetailInk, x: Float, y: Float, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        rects[ink.ordinal].add(x, y, w, h)
    }

    /** A polyline of (x, y) pairs, stroked. */
    fun line(ink: DetailInk, points: FloatArray) {
        lines[ink.ordinal].add(points)
    }

    fun rects(ink: DetailInk): FloatList = rects[ink.ordinal]

    fun lines(ink: DetailInk): List<FloatArray> = lines[ink.ordinal]

    fun isEmpty(ink: DetailInk): Boolean =
        rects[ink.ordinal].size == 0 && lines[ink.ordinal].isEmpty()

    /** The smallest rectangle round everything, as (left, top, right, bottom); nulls when empty. */
    fun bounds(): FloatArray? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var any = false
        for (list in rects) for (i in 0 until list.size / 4) {
            any = true
            l = minOf(l, list[i * 4])
            t = minOf(t, list[i * 4 + 1])
            r = maxOf(r, list[i * 4] + list[i * 4 + 2])
            b = maxOf(b, list[i * 4 + 1] + list[i * 4 + 3])
        }
        for (list in lines) for (p in list) for (i in 0 until p.size / 2) {
            any = true
            l = minOf(l, p[i * 2])
            t = minOf(t, p[i * 2 + 1])
            r = maxOf(r, p[i * 2])
            b = maxOf(b, p[i * 2 + 1])
        }
        return if (any) floatArrayOf(l, t, r, b) else null
    }
}

/** A growable list of floats (four to a rectangle). */
internal class FloatList {
    var data = FloatArray(32)
        private set

    var size = 0
        private set

    fun add(a: Float, b: Float, c: Float, d: Float) {
        if (size + 4 > data.size) data = data.copyOf(data.size * 2)
        data[size] = a
        data[size + 1] = b
        data[size + 2] = c
        data[size + 3] = d
        size += 4
    }

    operator fun get(i: Int): Float = data[i]
}

/** What a floor adds beside its wall and windows, and which window has something in it. */
internal enum class FloorExtra {
    NONE,
    AIR_CON,
    BALCONY,
}

/** What a building keeps up on its roof, besides the water tank (and the sign). */
internal class RoofKit(
    val stack: Boolean,
    val skylight: Boolean,
    val dish: Boolean,
    val railing: Boolean,
)

/**
 * A brick floor's share of the building's fire escape ([BuildingDetail.fireEscape]): its place in
 * the building's run of floors ([index], which sets the way its stair climbs), the height of the
 * floor under it when that one carries the escape too (NaN when this is the escape's lowest
 * platform, which has the drop ladder), and whether the floor over it carries it (the stair climbs
 * to it).
 */
internal class Escape(val index: Int, val below: Float, val above: Boolean)

/** The choices a whole building makes once, from its seed. */
internal class BuildingChoices(
    /** The drainpipe: down the left (-1), the right (1), or none (0). */
    val pipeSide: Int,
    val roof: RoofKit,
    /** Whether its brick floors carry a fire escape up their right-hand bays. */
    val fireEscape: Boolean = false,
) {
    val pipeX: Float
        get() = pipeSide * (B.W / 2f - PIPE_IN)

    companion object {
        const val PIPE_IN = 4.5f
    }
}

internal object BuildingDetail {
    /** How much of the floors get an air conditioner in a window, and a balcony. */
    const val AIR_CON_SHARE = .22f
    const val BALCONY_SHARE = .14f

    /**
     * The sill's height, the painted frame round a pane, and the reveal: how far the opening in the
     * wall reaches past the pane (its jambs and head, seen in their depth, then the frame), in
     * units. A cast shadow lies [CAST_DX] to the side of what casts it and [CAST_LEN] down.
     */
    const val SILL_H = 2f
    const val FRAME = .8f
    const val REVEAL = 2f
    const val CAST_DX = 1.1f
    const val CAST_LEN = 1.7f

    /** How far a sill reaches past its opening each side. */
    const val SILL_REACH = 1.5f

    /** A pilaster's width (stone and gold, between the window bays). */
    const val PILASTER = 3f

    /** A pane taller than this has a transom near its top (a fanlight over the people's heads). */
    const val TRANSOM_FROM = 30f
    const val TRANSOM_AT = .19f

    /** A curtain wall's mullion. */
    const val MULLION = 1.1f

    /** The quoins up a brick floor's corners: a block's height, a long one's width, a short's. */
    const val QUOIN_H = 7f
    const val QUOIN_LONG = 7f
    const val QUOIN_SHORT = 4.5f

    /** How many buildings carry a fire escape up their brick floors. */
    const val FIRE_ESCAPE_SHARE = .5f

    /** The fire escape's railing height, and how far its platform hangs under the sills. */
    const val ESCAPE_RAIL = 9f
    const val ESCAPE_DROP = 2.4f

    fun choices(seed: Int): BuildingChoices {
        val pipe = Ambient.hash(seed, 0, 500)
        val side = if (pipe < .15f) 0 else if (pipe < .55f) -1 else 1
        return BuildingChoices(
            side,
            RoofKit(
                stack = Ambient.hash(seed, 1, 500) < .55f,
                skylight = Ambient.hash(seed, 2, 500) < .4f,
                dish = Ambient.hash(seed, 3, 500) < .45f,
                railing = Ambient.hash(seed, 4, 500) < .5f,
            ),
            fireEscape = Ambient.hash(seed, 5, 500) < FIRE_ESCAPE_SHARE,
        )
    }

    /**
     * What floor [seed] has beside its windows: an air conditioner (brick and stone floors), a
     * balcony (stone and gold), or nothing; only where a window is never lit, so nothing stands in
     * front of a room ([lit] of [count] windows light up; the last in the order never does unless
     * all do).
     */
    fun extra(seed: Int, material: HabitMaterial, lit: Int, count: Int): FloorExtra {
        if (lit >= count || count == 0) return FloorExtra.NONE
        val v = Ambient.hash(seed, 0, 510)
        return when (material) {
            HabitMaterial.CLAY -> if (v < AIR_CON_SHARE) FloorExtra.AIR_CON else FloorExtra.NONE
            HabitMaterial.STONE ->
                if (v < AIR_CON_SHARE * .6f) FloorExtra.AIR_CON
                else if (v < AIR_CON_SHARE * .6f + BALCONY_SHARE) FloorExtra.BALCONY
                else FloorExtra.NONE
            HabitMaterial.GOLD -> if (v < BALCONY_SHARE) FloorExtra.BALCONY else FloorExtra.NONE
            else -> FloorExtra.NONE
        }
    }

    /**
     * The shapes of one floor of [material] (a run-down storey when [ruin]), its front from [top]
     * to [bottom] (building units, y down), its [windows] as x, y (from the top), w, h in their
     * lighting order, [count] of them, [lit] the most that light. [wall] is drawn before the
     * windows (surrounds and their reveals, the shadows they cast, pilasters or a curtain wall's
     * mullions, the string course, the pipe), [over] over the panes and the rooms behind them (the
     * shadows on the glass, the glazing bars), [front] over everything (an air conditioner, a
     * balcony, a tag on a boarded window). [pipeX] is the drainpipe's x, NaN for none.
     */
    fun floor(
        material: HabitMaterial,
        ruin: Boolean,
        top: Float,
        bottom: Float,
        windows: FloatArray,
        count: Int,
        lit: Int,
        hall: Int,
        seed: Int,
        storey: Int,
        pipeX: Float,
        ruins: Array<RuinWindow>?,
        wall: Shapes,
        front: Shapes,
        over: Shapes = Shapes(),
        escape: Escape? = null,
    ) {
        val half = B.W / 2f
        val gold = material == HabitMaterial.GOLD && !ruin
        val stone = (material == HabitMaterial.STONE || gold) && !ruin
        val glass = material == HabitMaterial.GLASS && !ruin
        // the string course along the floor's top: a band, the sky on its top, its shadow under it
        val course = if (gold) 3.2f else if (stone) 2.4f else 1.6f
        wall.rect(DetailInk.TRIM, -half - 1f, top, B.W + 2f, course)
        wall.rect(DetailInk.LIGHT, -half - 1f, top, B.W + 2f, .5f)
        wall.rect(DetailInk.SOFFIT, -half - 1f, top + course - .5f, B.W + 2f, .5f)
        wall.rect(DetailInk.SHADE, -half, top + course, B.W, if (stone) 1.4f else 1f)
        wall.rect(DetailInk.AO, -half, top + course, B.W, 2.6f)
        wall.rect(DetailInk.CAST_FROM_LEFT, -half + CAST_DX, top + course, B.W - CAST_DX, CAST_LEN)
        wall.rect(DetailInk.CAST_FROM_RIGHT, -half, top + course, B.W - CAST_DX, CAST_LEN)
        if (glass)
            for (c in 0..B.COLS) mullion(-half + B.MARGIN + c * B.PITCH, top + course, bottom, wall)
        else if (stone) pilasters(top + course, bottom, gold, wall)
        else if (material == HabitMaterial.CLAY && !ruin) quoins(top + course, bottom, wall)
        val firstRow = if (count > 0) windows[1] else 0f
        for (k in 0 until count) {
            val x = windows[k * 4]
            val y = top + windows[k * 4 + 1]
            val w = windows[k * 4 + 2]
            val h = windows[k * 4 + 3]
            if (glass) mullioned(x, y, w, h, wall)
            else surround(material, ruin, x, y, w, h, windows[k * 4 + 1] <= firstRow + .01f, wall)
            if (!ruin) pane(x, y, w, h, glass, over)
        }
        // the drainpipe, with a bracket near each end; a run-down storey has lost some of it
        if (!pipeX.isNaN() && !glass) {
            val keep = !ruin || Ambient.hash(seed, 1, 520) < .6f
            if (keep) pipe(pipeX, top, bottom, wall)
            else if (storey == 0) {
                // the bottom storey's length hangs loose from its top bracket
                val away = if (pipeX < 0f) 1f else -1f
                wall.rect(DetailInk.IRON, pipeX - 2f, top + 3f, 4f, 1.4f)
                wall.line(
                    DetailInk.IRON,
                    floatArrayOf(pipeX, top + 3f, pipeX + away * 7f, top + 15f),
                )
            }
        }
        if (ruin) {
            // tags sprayed on some of the boarded windows
            if (ruins != null)
                for (k in 0 until count) {
                    if (ruins[k] != RuinWindow.BOARDED) continue
                    if (Ambient.hash(seed, k, 530) >= .3f) continue
                    tag(
                        windows[k * 4],
                        top + windows[k * 4 + 1],
                        windows[k * 4 + 2],
                        windows[k * 4 + 3],
                        seed + k,
                        front,
                    )
                }
            return
        }
        if (escape != null && material == HabitMaterial.CLAY && count > 0) {
            fireEscape(top, bottom, windows, count, escape, wall, front)
            return
        }
        when (extra(seed, material, lit, count)) {
            FloorExtra.AIR_CON -> {
                val k = count - 1
                if (k != hall)
                    airCon(
                        windows[k * 4],
                        top + windows[k * 4 + 1],
                        windows[k * 4 + 2],
                        windows[k * 4 + 3],
                        front,
                    )
            }
            FloorExtra.BALCONY -> {
                val k = count - 1
                if (k != hall)
                    balcony(
                        windows[k * 4],
                        top + windows[k * 4 + 1],
                        windows[k * 4 + 2],
                        windows[k * 4 + 3],
                        gold,
                        front,
                    )
            }
            FloorExtra.NONE -> {}
        }
    }

    /** Where the snow lies on a window's sill: the sill's top, at the pane's foot. */
    fun sillTop(y: Float, h: Float): Float = y + h

    /**
     * A window's opening, frame, lintel and sill, by the material: the opening reaches [REVEAL]
     * past the pane, its jambs and head seen in depth ([DetailInk.REVEAL], the jambs turned to the
     * light or away from it, the head in shade), the frame round the pane, the sill under it with
     * its lit top and the shadows it throws down the wall, and the lintel over the opening on the
     * top row of a floor ([firstRow]; on a lower row the sill above stands in for it).
     */
    private fun surround(
        material: HabitMaterial,
        ruin: Boolean,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        firstRow: Boolean,
        s: Shapes,
    ) {
        val gold = material == HabitMaterial.GOLD && !ruin
        val stone = (material == HabitMaterial.STONE || gold) && !ruin
        val r = REVEAL
        val f = FRAME
        val depth = r - f
        // the reveal: the head across the top, a jamb each side down to the sill
        s.rect(DetailInk.REVEAL, x - r, y - r, w + 2f * r, depth)
        s.rect(DetailInk.REVEAL, x - r, y - f, depth, h + f)
        s.rect(DetailInk.REVEAL, x + w + f, y - f, depth, h + f)
        s.rect(DetailInk.SOFFIT, x - r, y - r, w + 2f * r, depth)
        s.rect(DetailInk.FACE_R, x - r, y - f, depth, h + f)
        s.rect(DetailInk.FACE_L, x + w + f, y - f, depth, h + f)
        // the inside corners where the reveal meets the frame
        s.rect(DetailInk.AO, x - f - .5f, y - f, .5f, h + f)
        s.rect(DetailInk.AO, x + w + f, y - f, .5f, h + f)
        s.rect(DetailInk.AO, x - r, y - f - .5f, w + 2f * r, .5f)
        // the painted frame round the pane (its foot is the sill)
        s.rect(DetailInk.TRIM, x - f, y - f, f, h + f)
        s.rect(DetailInk.TRIM, x + w, y - f, f, h + f)
        s.rect(DetailInk.TRIM, x, y - f, w, f)
        // the sill: a shelf under the opening, the sky on its top, its shadows on the wall under it
        val sill = if (stone) SILL_H + .4f else SILL_H
        val sx = x - r - SILL_REACH
        val sw = w + 2f * (r + SILL_REACH)
        val foot = sillTop(y, h)
        s.rect(DetailInk.TRIM, sx, foot, sw, sill)
        s.rect(DetailInk.LIGHT, sx, foot, sw, .5f)
        s.rect(DetailInk.SOFFIT, sx, foot + sill - .5f, sw, .5f)
        s.rect(DetailInk.FACE_R, sx, foot, .5f, sill)
        s.rect(DetailInk.FACE_L, sx + sw - .5f, foot, .5f, sill)
        s.rect(DetailInk.SHADE, sx, foot + sill, sw, if (stone) 1f else .8f)
        s.rect(DetailInk.CAST_FROM_LEFT, sx + CAST_DX, foot + sill, sw, CAST_LEN)
        s.rect(DetailInk.CAST_FROM_RIGHT, sx - CAST_DX, foot + sill, sw, CAST_LEN)
        if (!firstRow && !ruin) return
        // the lintel over the opening: a flat arch with a keystone in stone, a soldier course in
        // brick, a plain beam on a run-down storey
        val head = y - r
        when {
            stone -> {
                val lx = x - r - 2f
                val lw = w + 2f * r + 4f
                s.rect(DetailInk.TRIM, lx, head - 2.4f, lw, 2.4f)
                s.rect(DetailInk.LIGHT, lx, head - 2.4f, lw, .5f)
                s.rect(DetailInk.FACE_R, lx, head - 2.4f, .5f, 2.4f)
                s.rect(DetailInk.FACE_L, lx + lw - .5f, head - 2.4f, .5f, 2.4f)
                s.rect(DetailInk.CAST_FROM_LEFT, lx + lw, head - 1.8f, CAST_DX * .8f, 2.2f)
                s.rect(
                    DetailInk.CAST_FROM_RIGHT,
                    lx - CAST_DX * .8f,
                    head - 1.8f,
                    CAST_DX * .8f,
                    2.2f,
                )
                // the keystone stands proud of the arch
                val kx = x + w / 2f - 1.6f
                s.rect(DetailInk.TRIM, kx, head - 3.2f, 3.2f, 3.6f)
                s.rect(DetailInk.LIGHT, kx, head - 3.2f, 3.2f, .9f)
                s.rect(DetailInk.FACE_R, kx, head - 3.2f, .6f, 3.6f)
                s.rect(DetailInk.FACE_L, kx + 2.6f, head - 3.2f, .6f, 3.6f)
                s.rect(DetailInk.CAST_FROM_LEFT, kx + 3.2f, head - 2.4f, CAST_DX * .7f, 2.8f)
                s.rect(
                    DetailInk.CAST_FROM_RIGHT,
                    kx - CAST_DX * .7f,
                    head - 2.4f,
                    CAST_DX * .7f,
                    2.8f,
                )
                s.rect(DetailInk.CAST_FROM_LEFT, kx + CAST_DX * .7f, head + .4f, 3.2f, 1f)
                s.rect(DetailInk.CAST_FROM_RIGHT, kx - CAST_DX * .7f, head + .4f, 3.2f, 1f)
            }
            ruin -> {
                s.rect(DetailInk.TRIM, x - r - .6f, head - 1.6f, w + 2f * r + 1.2f, 1.6f)
                s.rect(DetailInk.SHADE, x - r - .6f, head, w + 2f * r + 1.2f, .6f)
            }
            else -> {
                // brick: a course of bricks on end over the opening, the joints between them
                val lx = x - r - .8f
                val lw = w + 2f * r + 1.6f
                s.rect(DetailInk.TRIM, lx, head - 2.6f, lw, 2.6f)
                s.rect(DetailInk.LIGHT, lx, head - 2.6f, lw, .4f)
                var bx = lx + 2.6f
                while (bx < lx + lw - .6f) {
                    s.rect(DetailInk.SHADE, bx, head - 2.6f, .5f, 2.6f)
                    bx += 2.6f
                }
            }
        }
    }

    /**
     * The glass of a pane, over the room behind it: the head's shadow along its top, the jamb's on
     * the side the light comes from, a fine shade at its foot, and a transom near the top of a tall
     * pane ([TRANSOM_FROM]), clear of the people's heads. A curtain wall's vision glass has no
     * transom (its mullions are the wall's).
     */
    private fun pane(x: Float, y: Float, w: Float, h: Float, glass: Boolean, s: Shapes) {
        val jamb = if (glass) .9f else 1.4f
        s.rect(DetailInk.GLASS_TOP, x, y, w, if (glass) 1.1f else 1.6f)
        s.rect(DetailInk.GLASS_FROM_LEFT, x, y, jamb, h)
        s.rect(DetailInk.GLASS_FROM_RIGHT, x + w - jamb, y, jamb, h)
        s.rect(DetailInk.AO, x, y + h - .6f, w, .6f)
        if (glass || h < TRANSOM_FROM) return
        val ty = y + h * TRANSOM_AT
        s.rect(DetailInk.BAR, x, ty, w, .8f)
        s.rect(DetailInk.BAR_LIGHT, x, ty, w, .3f)
        s.rect(DetailInk.GLASS_TOP, x, ty + .8f, w, .7f)
    }

    /**
     * Pilasters between a stone or gold floor's window bays, from under its string course ([top])
     * to its foot: a raised strip catching a little more sky, its edges turned to the light or
     * away, the shadow it casts beside it, a small capital; gold ones edged in gold.
     */
    private fun pilasters(top: Float, bottom: Float, gold: Boolean, s: Shapes) {
        val half = B.W / 2f
        val p = PILASTER
        val h = bottom - top
        for (c in 1 until B.COLS) {
            val x = -half + B.MARGIN + c * B.PITCH - p / 2f
            s.rect(DetailInk.RAISED, x, top, p, h)
            s.rect(DetailInk.FACE_L, x, top, .55f, h)
            s.rect(DetailInk.FACE_R, x + p - .55f, top, .55f, h)
            s.rect(DetailInk.CAST_FROM_LEFT, x + p, top + 1.2f, CAST_DX, h - 1.2f)
            s.rect(DetailInk.CAST_FROM_RIGHT, x - CAST_DX, top + 1.2f, CAST_DX, h - 1.2f)
            s.rect(DetailInk.TRIM, x - .6f, top, p + 1.2f, 1.4f)
            s.rect(DetailInk.LIGHT, x - .6f, top, p + 1.2f, .4f)
            s.rect(DetailInk.SHADE, x - .6f, top + 1.4f, p + 1.2f, .6f)
            if (gold) {
                s.rect(DetailInk.TRIM, x + .55f, top + 1.4f, .35f, h - 1.4f)
                s.rect(DetailInk.TRIM, x + p - .9f, top + 1.4f, .35f, h - 1.4f)
            }
        }
    }

    /**
     * Quoins up both corners of a brick floor, from under its string course ([top]) to its foot:
     * dressed stone blocks in the trim's colour, long and short in turn, each standing proud of the
     * brick (its top catching the sky, its underside and the joint under it in shade, its inner
     * edge turned to the light or away, the shadow it throws on the brick beside it).
     */
    private fun quoins(top: Float, bottom: Float, s: Shapes) {
        val half = B.W / 2f
        val j = .35f
        var y = top
        var k = 0
        while (y < bottom - 1.5f) {
            val h = minOf(QUOIN_H, bottom - y)
            val w = if (k % 2 == 0) QUOIN_LONG else QUOIN_SHORT
            for (side in 0..1) {
                val x = if (side == 0) -half else half - w
                s.rect(DetailInk.TRIM, x, y + j, w, h - 2f * j)
                s.rect(DetailInk.LIGHT, x, y + j, w, .45f)
                s.rect(DetailInk.SOFFIT, x, y + h - j - .5f, w, .5f)
                s.rect(DetailInk.SHADE, x, y + h - j, w, j)
                if (side == 0) {
                    s.rect(DetailInk.FACE_R, x + w - .5f, y + j, .5f, h - 2f * j)
                    s.rect(
                        DetailInk.CAST_FROM_LEFT,
                        x + w,
                        y + j + .5f,
                        CAST_DX * .7f,
                        h - 2f * j - .5f,
                    )
                } else {
                    s.rect(DetailInk.FACE_L, x, y + j, .5f, h - 2f * j)
                    s.rect(
                        DetailInk.CAST_FROM_RIGHT,
                        x - CAST_DX * .7f,
                        y + j + .5f,
                        CAST_DX * .7f,
                        h - 2f * j - .5f,
                    )
                }
            }
            y += QUOIN_H
            k++
        }
    }

    /**
     * A brick floor's part of the fire escape up the building's two right-hand bays: a slatted iron
     * platform hung just under the sills on two braces, its railing of balusters with a top and a
     * middle rail, the shadow it throws on the brick; the stair climbing from it towards the floor
     * above (the part inside this floor; the floor above lays down the rest), the last part of the
     * stair from the floor below arriving on it, and under the escape's lowest platform the drop
     * ladder. Each flight runs the other way from the one under it, so the stairs zigzag up the
     * building. All of it inside the floor's front.
     */
    fun fireEscape(
        top: Float,
        bottom: Float,
        windows: FloatArray,
        count: Int,
        e: Escape,
        wall: Shapes,
        front: Shapes,
    ) {
        val half = B.W / 2f
        var sill = top
        for (k in 0 until count) sill = maxOf(sill, top + windows[k * 4 + 1] + windows[k * 4 + 3])
        val x0 = -half + B.MARGIN + 4 * B.PITCH + (B.PITCH - B.WIN_W) / 2f - REVEAL - 4f
        val x1 = -half + B.MARGIN + 5 * B.PITCH + (B.PITCH + B.WIN_W) / 2f + REVEAL + 4f
        val pw = x1 - x0
        val p = sill + ESCAPE_DROP
        // how far the platform stands above the floor's foot (the same on every brick floor)
        val lift = bottom - p
        if (lift < 6f) return
        // the platform: a slab of slats, its lit edge, its shadow on the wall
        front.rect(DetailInk.IRON, x0, p, pw, 1.1f)
        front.rect(DetailInk.IRON_LIGHT, x0, p, pw, .35f)
        wall.rect(DetailInk.AO, x0, p + 1.1f, pw, 1.6f)
        wall.rect(DetailInk.CAST_FROM_LEFT, x0 + CAST_DX, p + 1.1f, pw - CAST_DX, 2.4f)
        wall.rect(DetailInk.CAST_FROM_RIGHT, x0, p + 1.1f, pw - CAST_DX, 2.4f)
        // the braces under it, back to the wall
        val brace = minOf(6f, lift - 1f)
        front.line(DetailInk.IRON, floatArrayOf(x0 + 1.5f, p + brace, x0 + 6f, p + 1.1f))
        front.line(DetailInk.IRON, floatArrayOf(x1 - 1.5f, p + brace, x1 - 6f, p + 1.1f))
        // the railing
        val rt = p - ESCAPE_RAIL
        front.rect(DetailInk.IRON, x0, rt, pw, .7f)
        front.rect(DetailInk.IRON_LIGHT, x0, rt, pw, .3f)
        front.rect(DetailInk.IRON, x0, p - ESCAPE_RAIL / 2f, pw, .45f)
        front.rect(DetailInk.IRON, x0, rt, .8f, ESCAPE_RAIL)
        front.rect(DetailInk.IRON, x1 - .8f, rt, .8f, ESCAPE_RAIL)
        var bx = x0 + 2.4f
        while (bx < x1 - 1.5f) {
            front.rect(DetailInk.IRON, bx, rt, .45f, ESCAPE_RAIL)
            bx += 2.4f
        }
        // the stair up from this platform, to where it passes into the floor above
        if (e.above) flight(e.index, x0, x1, p, p - (bottom - top), top, p, front)
        // the stair from the floor below arriving here
        if (!e.below.isNaN()) flight(e.index - 1, x0, x1, p + e.below, p, p, bottom - .5f, front)
        else {
            // the drop ladder hanging under the lowest platform, ready to slide down
            val lx = if (e.index % 2 == 0) x0 + 4f else x1 - 8.6f
            val foot = minOf(p + 13f, bottom - .5f)
            front.rect(DetailInk.IRON, lx, p + 1.1f, .6f, foot - p - 1.1f)
            front.rect(DetailInk.IRON, lx + 4f, p + 1.1f, .6f, foot - p - 1.1f)
            var ry = p + 3f
            while (ry < foot - .4f) {
                front.rect(DetailInk.IRON, lx, ry, 4.6f, .4f)
                ry += 2.2f
            }
        }
    }

    /**
     * A flight of the fire escape from a platform at [from] to the next one up at [to] (y down),
     * run left to right on an even [index] and right to left on an odd one, between the platforms'
     * ends [x0] and [x1]; only its part between [y0] and [y1] is laid down: the stringer, the
     * handrail over it, a tread every few units.
     */
    private fun flight(
        index: Int,
        x0: Float,
        x1: Float,
        from: Float,
        to: Float,
        y0: Float,
        y1: Float,
        s: Shapes,
    ) {
        val rise = from - to
        if (rise <= 1f) return
        val xa = if (index % 2 == 0) x0 + 4f else x1 - 4f
        val xb = if (index % 2 == 0) x1 - 4f else x0 + 4f
        val run = xb - xa
        val lo = minOf(y1, from)
        val hi = maxOf(y0, to)
        if (lo - hi > .5f)
            s.line(
                DetailInk.IRON,
                floatArrayOf(xa + run * (from - lo) / rise, lo, xa + run * (from - hi) / rise, hi),
            )
        // the handrail, a rail's height over the stringer
        val r = ESCAPE_RAIL * .8f
        val rlo = minOf(y1, from - r)
        val rhi = maxOf(y0 + .8f, to - r)
        if (rlo - rhi > .5f)
            s.line(
                DetailInk.IRON,
                floatArrayOf(
                    xa + run * (from - rlo - r) / rise,
                    rlo,
                    xa + run * (from - rhi - r) / rise,
                    rhi,
                ),
            )
        var y = from - 2.5f
        while (y > to + 1f) {
            if (y >= y0 + .3f && y + .5f <= y1)
                s.rect(DetailInk.IRON, xa + run * (from - y) / rise - 1.8f, y, 3.6f, .5f)
            y -= 2.5f
        }
    }

    /**
     * A curtain wall's mullion at [cx] from [top] to [bottom]: steel standing proud of the glass,
     * an edge turned to each side of the light, the thin shadow it throws beside it.
     */
    private fun mullion(cx: Float, top: Float, bottom: Float, s: Shapes) {
        val m = MULLION
        val x = cx - m / 2f
        s.rect(DetailInk.TRIM, x, top, m, bottom - top)
        s.rect(DetailInk.FACE_L, x, top, .35f, bottom - top)
        s.rect(DetailInk.FACE_R, x + m - .35f, top, .35f, bottom - top)
        s.rect(DetailInk.CAST_FROM_LEFT, x + m, top, .8f, bottom - top)
        s.rect(DetailInk.CAST_FROM_RIGHT, x - .8f, top, .8f, bottom - top)
    }

    /** A curtain wall's vision pane: mullions at its edges, transoms over and under it. */
    private fun mullioned(x: Float, y: Float, w: Float, h: Float, s: Shapes) {
        val m = MULLION
        mullion(x - m / 2f, y - m, y + h + m, s)
        mullion(x + w + m / 2f, y - m, y + h + m, s)
        for (ty in floatArrayOf(y - m, y + h)) {
            s.rect(DetailInk.TRIM, x - m, ty, w + 2f * m, m)
            s.rect(DetailInk.LIGHT, x - m, ty, w + 2f * m, .35f)
            s.rect(DetailInk.SOFFIT, x - m, ty + m - .3f, w + 2f * m, .3f)
            s.rect(DetailInk.CAST_FROM_LEFT, x - m + .8f, ty + m, w + 2f * m, .9f)
            s.rect(DetailInk.CAST_FROM_RIGHT, x - m - .8f, ty + m, w + 2f * m, .9f)
        }
    }

    private fun pipe(px: Float, top: Float, bottom: Float, s: Shapes) {
        s.rect(DetailInk.IRON, px - 1f, top, 2f, bottom - top)
        s.rect(DetailInk.IRON_LIGHT, px - .7f, top, .6f, bottom - top)
        s.rect(DetailInk.IRON, px - 2f, top + 5f, 4f, 1.4f)
        s.rect(DetailInk.IRON, px - 2f, bottom - 7f, 4f, 1.4f)
    }

    /** An air conditioner in the lower part of a window: a box with a grille, its drip tray. */
    private fun airCon(x: Float, y: Float, w: Float, h: Float, s: Shapes) {
        val top = y + h * .52f
        s.rect(DetailInk.IRON, x + .6f, top - .6f, w - 1.2f, h * .48f + .6f)
        s.rect(DetailInk.BOX, x + 1.2f, top, w - 2.4f, h * .48f - .8f)
        s.rect(DetailInk.LIGHT, x + 1.2f, top, w - 2.4f, .6f)
        var gy = top + 2f
        while (gy < y + h - 3f) {
            s.rect(DetailInk.BOX_DARK, x + 2.6f, gy, w - 5.2f, .6f)
            gy += 1.5f
        }
        s.rect(DetailInk.BOX_DARK, x + w - 5f, top + 1.4f, 2.4f, 2.4f)
    }

    /** A balcony across the lower part of a window: a slab, a railing of balusters. */
    private fun balcony(x: Float, y: Float, w: Float, h: Float, gold: Boolean, s: Shapes) {
        val rail = if (gold) DetailInk.TRIM else DetailInk.IRON
        val top = y + h * .55f
        s.rect(DetailInk.TRIM, x - 3f, y + h - 1f, w + 6f, 2.4f)
        s.rect(DetailInk.LIGHT, x - 3f, y + h - 1f, w + 6f, .5f)
        s.rect(DetailInk.SHADE, x - 3f, y + h + 1.4f, w + 6f, 1.4f)
        s.rect(rail, x - 2.6f, top, w + 5.2f, .9f)
        s.rect(if (gold) DetailInk.LIGHT else DetailInk.IRON_LIGHT, x - 2.6f, top, w + 5.2f, .35f)
        var bx = x - 2.4f
        while (bx <= x + w + 2.4f) {
            s.rect(rail, bx, top, .7f, y + h - 1f - top)
            bx += 2.8f
        }
    }

    /** A spray-can tag on a boarded window: a loose scrawl of three or four strokes. */
    private fun tag(x: Float, y: Float, w: Float, h: Float, seed: Int, s: Shapes) {
        val n = 3 + (Ambient.hash(seed, 0, 540) * 2f).toInt()
        val pts = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            pts[i * 2] =
                x + 2f + (w - 4f) * (i.toFloat() / n) + (Ambient.hash(seed, i, 541) - .5f) * 3f
            pts[i * 2 + 1] = y + h * (.3f + .4f * Ambient.hash(seed, i, 542))
        }
        s.line(DetailInk.SPRAY, pts)
    }

    /**
     * The cornice of a building whose top storey is [crown]: its coping (a lit arris, the drip's
     * underside in shade, its ends turned to the light or away), the dentils or brackets under it
     * with their lit edges and the shadows they cast, in a band [B.CORNICE] tall over [y] (the roof
     * line), [half] + 3 each side.
     */
    fun cornice(crown: HabitMaterial, y: Float, s: Shapes) {
        val half = B.W / 2f + 3f
        val top = y - B.CORNICE
        val stone = crown == HabitMaterial.STONE || crown == HabitMaterial.GOLD
        s.rect(DetailInk.LIGHT, -half, top, 2f * half, 1.4f)
        s.rect(DetailInk.LIGHT, -half, top, 2f * half, .45f)
        s.rect(DetailInk.SOFFIT, -half, top + 1.4f, 2f * half, .6f)
        s.rect(DetailInk.SHADE, -half, top + 1.4f, 2f * half, .6f)
        s.rect(DetailInk.FACE_L, -half, top, .8f, B.CORNICE)
        s.rect(DetailInk.FACE_R, half - .8f, top, .8f, B.CORNICE)
        if (stone) {
            // brackets under the slab, the shade between them
            s.rect(DetailInk.SHADE, -half, y - 3.2f, 2f * half, 2.6f)
            s.rect(DetailInk.AO, -half, y - 3.6f, 2f * half, 1f)
            var x = -half + 4f
            while (x <= half - 4f) {
                s.rect(DetailInk.TRIM, x - 1.6f, y - 3.6f, 3.2f, 3.2f)
                s.rect(DetailInk.LIGHT, x - 1.6f, y - 3.6f, 3.2f, .6f)
                s.rect(DetailInk.FACE_L, x - 1.6f, y - 3.6f, .55f, 3.2f)
                s.rect(DetailInk.FACE_R, x + 1.05f, y - 3.6f, .55f, 3.2f)
                s.rect(DetailInk.CAST_FROM_LEFT, x + 1.6f, y - 3f, CAST_DX, 2.6f)
                s.rect(DetailInk.CAST_FROM_RIGHT, x - 1.6f - CAST_DX, y - 3f, CAST_DX, 2.6f)
                x += 11.5f
            }
        } else {
            // dentils: small blocks along the fascia's foot
            s.rect(DetailInk.SHADE, -half, y - 2.6f, 2f * half, 2.2f)
            var x = -half + 2f
            while (x <= half - 3f) {
                s.rect(DetailInk.TRIM, x, y - 2.6f, 2.2f, 2f)
                s.rect(DetailInk.FACE_L, x, y - 2.6f, .4f, 2f)
                s.rect(DetailInk.FACE_R, x + 1.8f, y - 2.6f, .4f, 2f)
                s.rect(DetailInk.CAST_FROM_LEFT, x + 2.2f, y - 2.2f, .7f, 1.8f)
                s.rect(DetailInk.CAST_FROM_RIGHT, x - .7f, y - 2.2f, .7f, 1.8f)
                x += 4.4f
            }
        }
        s.rect(DetailInk.AO, -half, y - .6f, 2f * half, .6f)
    }

    /**
     * The lobby's shopfront: two windows [SHOP_W] wide from [SHOP_X] (and its mirror), the door.
     */
    const val SHOP_X = 13f
    const val DOOR = 9f
    const val SHOP_TOP = 35f
    const val SHOP_FOOT = -4f
    val SHOP_W: Float
        get() = B.W / 2f - 9f - SHOP_X

    /** Where the lobby's wall lamps hang, either side of the door, and how high (from its top). */
    const val LAMP_X = 11f
    const val LAMP_Y = 39f

    /**
     * The lobby's shapes on its wall: the plinth's joints, the pipe's foot, the awning's brackets,
     * the door's reveals and the wall lamps' brackets; its wall runs from [top] (negative)
     * to 0. [pipeX] NaN for no pipe.
     */
    fun lobby(top: Float, pipeX: Float, s: Shapes) {
        val half = B.W / 2f
        // the plinth: a stone base with its joints, a chamfer catching the sky
        s.rect(DetailInk.LIGHT, -half, -10f, B.W, .7f)
        s.rect(DetailInk.AO, -half, -9.3f, B.W, 1f)
        var x = -half + 11.5f
        while (x < half) {
            s.rect(DetailInk.SHADE, x, -9.3f, .7f, 9.3f)
            s.rect(DetailInk.LIGHT, x + .7f, -9.3f, .35f, 9.3f)
            x += 23f
        }
        s.rect(DetailInk.SHADE, -half, -5.2f, B.W, .6f)
        if (!pipeX.isNaN()) {
            s.rect(DetailInk.IRON, pipeX - 1f, top, 2f, B.LOBBY_H - 6f)
            s.rect(DetailInk.IRON_LIGHT, pipeX - .7f, top, .6f, B.LOBBY_H - 6f)
            s.rect(DetailInk.IRON, pipeX - 2f, top + 4f, 4f, 1.4f)
            // the shoe at its foot, turned out to the pavement
            s.rect(DetailInk.IRON, pipeX - 1.6f, -6.5f, 3.2f, 3f)
        }
        // the awning's brackets: a strut from the wall to the awning's front edge at each end
        val a0 = top + 21f
        for (side in intArrayOf(-1, 1)) {
            val bx = side * (half - 10f)
            s.line(DetailInk.IRON, floatArrayOf(bx, a0 + 13f, bx - side * 1f, a0 + 1.5f))
        }
        // the door stands back in its opening: jambs turned to the light or away, a head in shade
        val d0 = top + SHOP_TOP - 2f
        val dh = -d0
        s.rect(DetailInk.REVEAL, -DOOR, d0, 1.3f, dh)
        s.rect(DetailInk.REVEAL, DOOR - 1.3f, d0, 1.3f, dh)
        s.rect(DetailInk.REVEAL, -DOOR, d0, 2f * DOOR, 1.3f)
        s.rect(DetailInk.FACE_R, -DOOR, d0, 1.3f, dh)
        s.rect(DetailInk.FACE_L, DOOR - 1.3f, d0, 1.3f, dh)
        s.rect(DetailInk.SOFFIT, -DOOR, d0, 2f * DOOR, 1.3f)
        // the lamps' brackets and back plates
        for (side in intArrayOf(-1, 1)) {
            val lx = side * LAMP_X
            s.rect(DetailInk.IRON, lx - .9f, top + LAMP_Y - 3.4f, 1.8f, 1.1f)
            s.rect(DetailInk.IRON, lx - .35f, top + LAMP_Y - 2.4f, .7f, 1.8f)
        }
    }

    /**
     * What goes over the lobby's glass, after what is behind it: each shop window's reveal (inside
     * its opening, the jambs and the head), the shadows on its glass, its glazing bars (two
     * mullions and a transom) with their lit edges; [top] as for [lobby].
     */
    fun lobbyOver(top: Float, s: Shapes) {
        val half = B.W / 2f
        val w0 = top + SHOP_TOP
        val h = SHOP_FOOT - w0
        val w = SHOP_W
        val r = 2f
        for (x0 in floatArrayOf(-half + 9f, SHOP_X)) {
            s.rect(DetailInk.REVEAL, x0, w0, r, h)
            s.rect(DetailInk.REVEAL, x0 + w - r, w0, r, h)
            s.rect(DetailInk.REVEAL, x0, w0, w, r)
            s.rect(DetailInk.FACE_R, x0, w0, r, h)
            s.rect(DetailInk.FACE_L, x0 + w - r, w0, r, h)
            s.rect(DetailInk.SOFFIT, x0, w0, w, r)
            s.rect(DetailInk.AO, x0 + r, w0 + r, .6f, h - r)
            s.rect(DetailInk.AO, x0 + w - r - .6f, w0 + r, .6f, h - r)
            s.rect(DetailInk.AO, x0 + r, w0 + r, w - 2f * r, .6f)
            s.rect(DetailInk.GLASS_TOP, x0 + r, w0 + r, w - 2f * r, 1.5f)
            s.rect(DetailInk.GLASS_FROM_LEFT, x0 + r, w0 + r, 1.8f, h - r)
            s.rect(DetailInk.GLASS_FROM_RIGHT, x0 + w - r - 1.8f, w0 + r, 1.8f, h - r)
            val inner = w - 2f * r
            for (m in 1..2) {
                val mx = x0 + r + inner * m / 3f - .6f
                s.rect(DetailInk.BAR, mx, w0 + r, 1.2f, h - r)
                s.rect(DetailInk.BAR_LIGHT, mx, w0 + r, .35f, h - r)
                s.rect(DetailInk.GLASS_FROM_LEFT, mx + 1.2f, w0 + r, .8f, h - r)
                s.rect(DetailInk.GLASS_FROM_RIGHT, mx - .8f, w0 + r, .8f, h - r)
            }
            s.rect(DetailInk.BAR, x0 + r, w0 + 7f, inner, 1f)
            s.rect(DetailInk.BAR_LIGHT, x0 + r, w0 + 7f, inner, .3f)
            s.rect(DetailInk.GLASS_TOP, x0 + r, w0 + 8f, inner, .7f)
        }
    }
}
