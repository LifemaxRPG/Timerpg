package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Dice
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.WindowSpot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The inks a missed floor's decay is drawn in, in the order they are drawn: [argb] at [alpha], a
 * stroke [width] wide (0 to fill). The [wall] inks lie on the wall and are drawn before the night
 * and the ground's grade go over it, so they take the scene's light as the wall does; the others go
 * over the windows and are darkened by the night where they are [night]. [fine] inks are left out
 * of the zoomed-out street. An [argb] of 0 is the wall's own colour.
 */
internal enum class DecayInk(
    val argb: Long,
    val alpha: Float,
    val width: Float,
    val wall: Boolean,
    val fine: Boolean = false,
    val night: Boolean = false,
) {
    /** A brick or a patch put back: a little lighter and cleaner than the wall round it. */
    PATCH(0xFFFFF4E0, .1f, 0f, true),
    /**
     * Where the face has come away: the darker core behind it, its broken top edge in shade, the
     * lip under it catching the sky.
     */
    SPALL(0xFF000000, .3f, 0f, true),
    /** A flake off a stone or a panel: the fresher face under it, lighter. */
    CHIP(0xFFFFFFFF, .16f, 0f, true),
    SPALL_SHADE(0xFF000000, .38f, .6f, true, fine = true),
    SPALL_LIT(0xFFFFFFFF, .22f, .45f, true, fine = true),
    /** The lower lip of a crack catching the light, a hair under it. */
    CRACK_LIT(0xFFFFFFFF, .16f, .45f, true, fine = true),
    /** A hairline in the joints, and one opened up. */
    CRACK(0xFF1E1610, .58f, .55f, true),
    CRACK_WIDE(0xFF1E1610, .62f, .85f, true),
    /** The crack filled again after the habit was picked back up: pale gold mortar. */
    MEND(0xFFE0BC64, .7f, .9f, true),
    /** Pieces fallen to the floor's foot, in the wall's colour, their tops in the sky's light. */
    RUBBLE(0, .95f, 0f, true),
    RUBBLE_LIT(0xFFFFFFFF, .25f, .4f, true, fine = true),
    /** Broken glass lying at the foot of a curtain wall. */
    SHARD(0xFFDCE8F2, .55f, 0f, true),
    /** The dark room through a pane with a piece out of it, and the cracks across the glass. */
    HOLE(0xFF07090D, .6f, 0f, false),
    PANE_CRACK(0xFFF4F7FA, .5f, .4f, false, fine = true),
    /** A window boarded over, its boards' gaps, a notice stapled to them. */
    BOARD(0xFF8E6E4A, 1f, 0f, false, night = true),
    BOARD_LINE(0xFF5E4630, .9f, .6f, false, night = true),
    NOTICE(0xFFEDE8DA, .95f, 0f, false, night = true),
    NOTICE_INK(0xFFB8452F, .85f, .55f, false, fine = true, night = true),
    /** Tape crossed over a broken pane: the warning a missed-twice floor wears. */
    TAPE(0xFFE3B544, .92f, 1.5f, false, night = true),
    /** A curtain wall's pane put back: its new glazing bead, pale gold. */
    BEAD(0xFFE0BC64, .75f, .6f, false, night = true),
}

/**
 * A floor's decay worked out once: per [DecayInk] the shapes to fill or the polylines to stroke, as
 * (x, y) pairs in building units (x from the front's middle, y down from the floor's top), and the
 * streaks run down the wall from it ([stains]: x, y, length, and 1 for rust or 0 for damp).
 */
internal class DecayPlan(val stage: Int, val mended: Boolean) {
    val shapes = Array(DecayInk.entries.size) { ArrayList<FloatArray>() }
    val stains = ArrayList<FloatArray>()

    fun add(ink: DecayInk, points: FloatArray) {
        if (points.size >= 4) shapes[ink.ordinal] += points
    }

    fun of(ink: DecayInk): List<FloatArray> = shapes[ink.ordinal]
}

/**
 * How a floor that was missed falls into disrepair, the way a real wall does, its own for each key:
 * once ([stage] 1) hairline cracks open in its joints from a sill's end (stepped along the mortar
 * on brick, a fine fracture through the blocks on stone and gold) and one pane cracks; twice the
 * cracks widen and branch, bits of the face come away, rust runs from the cracks, a pane is broken
 * and taped across; three times or more more of the face is gone, the window is boarded with a
 * notice on it, its lintel has dropped and rubble lies at the floor's foot. A floor picked back up
 * has the first crack filled with pale gold mortar. Pure geometry, made once when the habits
 * change; the painter only draws it.
 */
internal object FloorDecay {
    /** How deep a streak of damp and one of rust run ([DecayPlan.stains]), and rust's colour. */
    const val DAMP_ALPHA = .45f
    const val RUST_ALPHA = .6f
    const val RUST = 0xFF8C4B25

    fun stage(miss: Int): Int = miss.coerceIn(0, 3)

    /**
     * The decay of a floor of [material] [height] tall with its windows [spots] (x from the front's
     * middle, y from its top), missed [miss] days running, [scar]red by a come-back; null when it
     * has none.
     */
    fun plan(
        material: HabitMaterial,
        key: String,
        miss: Int,
        scar: Boolean,
        height: Float,
        spots: List<WindowSpot>,
    ): DecayPlan? {
        val stage = stage(miss)
        if (stage == 0 && !scar) return null
        if (spots.isEmpty()) return null
        val plan = DecayPlan(stage, stage == 0)
        val dice = Dice(key + "/decay")
        val glass = material == HabitMaterial.GLASS
        // the window it starts from: on the right (the plaque is on the left), on the bottom row,
        // so the cracks run down into the band under it
        val row = spots.maxOf { it.row }
        val col = 3 + (dice.next() * 3f).toInt().coerceAtMost(2)
        val focus = spots.first { it.row == row && it.col == col }
        val near = spots.first { it.row == row && it.col == col - 1 }
        val dir = if (col == B.COLS - 1 || dice.next() < .5f) -1 else 1
        val wall = Wall(material, height, spots)
        val foot = height - 2.5f
        if (glass) glassDecay(plan, stage, focus, near, spots, height, dice)
        else {
            // the main crack: from under the sill's end, down and away into the band below
            val sx = if (dir > 0) focus.x + focus.w + SILL_END else focus.x - SILL_END
            val sy = focus.y + focus.h + B_SILL
            val main = wall.walk(sx, sy, dir, foot, dice)
            val cracks = arrayListOf(main)
            if (stage >= 2) {
                // the pier beside the window gives too: a near-upright crack down its middle
                val px = if (dir > 0) focus.x + focus.w + PIER / 2f else focus.x - PIER / 2f
                val py = focus.y + focus.h * (.15f + .2f * dice.next())
                val pier = wall.pier(px, py, py + focus.h * (if (stage >= 3) .7f else .45f), dice)
                if (pier.size >= 6) cracks += pier
            }
            if (stage >= 3) {
                // and the other sill end
                val ox = if (dir > 0) focus.x - SILL_END else focus.x + focus.w + SILL_END
                cracks += wall.walk(ox, sy, -dir, foot, dice)
            }
            if (stage == 0) {
                plan.add(DecayInk.MEND, main)
                // a brick or two put back along it, a touch fresher than the rest
                for (p in wall.cells(main, 1 + (dice.next() * 2f).toInt(), dice)) plan.add(
                    DecayInk.PATCH,
                    p.poly,
                )
            } else {
                val ink = if (stage >= 2) DecayInk.CRACK_WIDE else DecayInk.CRACK
                for (c in cracks) {
                    plan.add(ink, c)
                    plan.add(DecayInk.CRACK_LIT, shifted(c, .2f, .55f))
                }
                if (stage >= 2) {
                    // the cracks branch: short hairlines off their turns
                    for (c in cracks) for (b in wall.branches(c, stage, dice)) plan.add(
                        DecayInk.CRACK,
                        b,
                    )
                    // pieces of the face come away along them
                    val spalls =
                        wall.cells(main, if (stage >= 3) 3 else 1, dice) +
                            cracks.drop(1).flatMap { wall.cells(it, 1, dice) }
                    for (s in spalls) {
                        plan.add(if (s.light) DecayInk.CHIP else DecayInk.SPALL, s.poly)
                        plan.add(DecayInk.SPALL_SHADE, s.top)
                        plan.add(DecayInk.SPALL_LIT, s.bottom)
                    }
                    // the sill's end is chipped where the crack starts
                    val cx =
                        if (dir > 0) focus.x + focus.w + SILL_END + .2f
                        else focus.x - SILL_END - .2f
                    val cy = focus.y + focus.h
                    plan.add(
                        DecayInk.SPALL,
                        floatArrayOf(
                            cx,
                            cy + .3f,
                            cx - dir * 1.4f,
                            cy + .3f,
                            cx - dir * .5f,
                            cy + 1.1f,
                            cx,
                            cy + 2f,
                        ),
                    )
                }
                // water gets in at the sill's end and runs down the wall: damp, then rust
                plan.stains +=
                    floatArrayOf(
                        sx - dir * .6f,
                        sy - .8f,
                        if (stage >= 2) 16f else 10f,
                        if (stage >= 2) 1f else 0f,
                    )
                if (stage >= 3) {
                    val o = cracks.last()
                    plan.stains += floatArrayOf(o[0], o[1] - .8f, 14f, 0f)
                }
                // the window it starts from: a cracked pane, then broken and taped, then boarded
                when (stage) {
                    1 -> paneCrack(plan, focus, dice, false)
                    2 -> {
                        brokenPane(plan, focus, dice)
                        tape(plan, focus)
                    }
                    else -> {
                        boarded(plan, focus, dice)
                        brokenPane(plan, near, dice)
                        tape(plan, near)
                        if (focus.row == 0) lintelDrop(plan, focus, wall)
                        rubble(
                            plan,
                            if (dir > 0) focus.x + focus.w + 4f else focus.x - 4f,
                            height,
                            dice,
                            false,
                        )
                    }
                }
            }
        }
        return plan
    }

    // ---------------------------------------------------------------- glass and steel

    private fun glassDecay(
        plan: DecayPlan,
        stage: Int,
        focus: WindowSpot,
        near: WindowSpot,
        spots: List<WindowSpot>,
        height: Float,
        dice: Dice,
    ) {
        if (stage == 0) {
            // the pane replaced: its new glazing bead, pale gold
            val x = focus.x + .4f
            val y = focus.y + .4f
            plan.add(
                DecayInk.BEAD,
                floatArrayOf(
                    x,
                    y,
                    x + focus.w - .8f,
                    y,
                    x + focus.w - .8f,
                    y + focus.h - .8f,
                    x,
                    y + focus.h - .8f,
                    x,
                    y,
                ),
            )
            return
        }
        when (stage) {
            1 -> paneCrack(plan, focus, dice, true)
            2 -> {
                brokenPane(plan, focus, dice)
                tape(plan, focus)
                paneCrack(plan, near, dice, false)
            }
            else -> {
                boarded(plan, focus, dice)
                brokenPane(plan, near, dice)
                tape(plan, near)
                val third = spots.firstOrNull { it.row == focus.row && it.col == focus.col - 2 }
                if (third != null) paneCrack(plan, third, dice, true)
                rubble(plan, focus.x + focus.w / 2f, height, dice, true)
            }
        }
        // the frame's fixings rust and run
        plan.stains +=
            floatArrayOf(focus.x - 2f, focus.y + focus.h + 1f, if (stage >= 2) 14f else 8f, 1f)
        if (stage >= 2)
            plan.stains += floatArrayOf(focus.x + focus.w + 2f, focus.y + focus.h + 1f, 10f, 1f)
    }

    // ---------------------------------------------------------------- the windows

    /**
     * A crack across a pane: from a struck point a star of [star] arms, else one long run from a
     * corner.
     */
    private fun paneCrack(plan: DecayPlan, s: WindowSpot, dice: Dice, star: Boolean) {
        val cx = s.x + s.w * (.3f + .4f * dice.next())
        val cy = s.y + s.h * (.25f + .35f * dice.next())
        if (star) {
            val arms = 6 + (dice.next() * 2f).toInt()
            val ring = FloatArray(arms * 2)
            for (i in 0 until arms) {
                val a = (i + .5f * dice.next()) * 2f * PI.toFloat() / arms
                val len = (.35f + .55f * dice.next()) * minOf(s.w, s.h) * .6f
                plan.add(DecayInk.PANE_CRACK, arm(s, cx, cy, a, len, dice))
                ring[i * 2] = cx + cos(a) * len * .35f
                ring[i * 2 + 1] = cy + sin(a) * len * .35f
            }
            // part of a ring round the strike
            val from = (dice.next() * arms).toInt()
            val n = 3 + (dice.next() * 2f).toInt()
            plan.add(
                DecayInk.PANE_CRACK,
                FloatArray(n * 2) { ring[((from + it / 2) % arms) * 2 + it % 2] },
            )
        } else {
            // a run from the bottom corner to the top, with a fork
            val left = dice.next() < .5f
            val x0 = if (left) s.x else s.x + s.w
            val sign = if (left) 1f else -1f
            val pts = FloatArray(10)
            for (i in 0 until 5) {
                val t = i / 4f
                pts[i * 2] = x0 + sign * s.w * (.05f + .75f * t + .1f * (dice.next() - .5f))
                pts[i * 2 + 1] = s.y + s.h * (1f - t * (.75f + .2f * dice.next()))
            }
            pts[0] = x0
            pts[1] = s.y + s.h
            plan.add(DecayInk.PANE_CRACK, clampTo(s, pts))
            plan.add(
                DecayInk.PANE_CRACK,
                clampTo(
                    s,
                    floatArrayOf(pts[4], pts[5], pts[4] + sign * s.w * .35f, pts[5] + s.h * .12f),
                ),
            )
        }
    }

    private fun arm(
        s: WindowSpot,
        cx: Float,
        cy: Float,
        a: Float,
        len: Float,
        dice: Dice,
    ): FloatArray {
        val pts = FloatArray(8)
        var x = cx
        var y = cy
        var t = a
        pts[0] = x
        pts[1] = y
        for (i in 1..3) {
            t += (dice.next() - .5f) * .5f
            x += cos(t) * len / 3f
            y += sin(t) * len / 3f
            pts[i * 2] = x
            pts[i * 2 + 1] = y
        }
        return clampTo(s, pts)
    }

    /** A pane with a piece out of one top corner: the dark room through it, cracks running off. */
    private fun brokenPane(plan: DecayPlan, s: WindowSpot, dice: Dice) {
        val right = dice.next() < .5f
        val x0 = if (right) s.x + s.w else s.x
        val sign = if (right) -1f else 1f
        val across = s.w * (.45f + .3f * dice.next())
        val down = s.h * (.35f + .25f * dice.next())
        // the jagged edge of what is left, from the top edge round to the side
        val edge = FloatArray(10)
        for (i in 0 until 5) {
            val t = i / 4f
            val bulge = if (i == 0 || i == 4) 0f else (dice.next() - .3f) * .25f
            edge[i * 2] = x0 + sign * across * (1f - t) * (1f - bulge)
            edge[i * 2 + 1] = s.y + down * t * (1f - bulge)
        }
        edge[8] = x0
        val hole = FloatArray(12)
        hole[0] = x0
        hole[1] = s.y
        edge.copyInto(hole, 2)
        plan.add(DecayInk.HOLE, hole)
        // a crack from each of the edge's points into the glass left
        for (i in 1..3) {
            val ex = edge[i * 2]
            val ey = edge[i * 2 + 1]
            val a =
                (if (right) PI.toFloat() * .75f else PI.toFloat() * .25f) +
                    (dice.next() - .5f) * 1.1f
            plan.add(DecayInk.PANE_CRACK, arm(s, ex, ey, a, s.h * (.2f + .3f * dice.next()), dice))
        }
    }

    /** Tape crossed over a window. */
    private fun tape(plan: DecayPlan, s: WindowSpot) {
        val i = 1.2f
        plan.add(DecayInk.TAPE, floatArrayOf(s.x + i, s.y + i, s.x + s.w - i, s.y + s.h - i))
        plan.add(DecayInk.TAPE, floatArrayOf(s.x + s.w - i, s.y + i, s.x + i, s.y + s.h - i))
    }

    /** Boards nailed over a window, each a little askew, and a notice stapled to them. */
    private fun boarded(plan: DecayPlan, s: WindowSpot, dice: Dice) {
        val n = if (s.h > 30f) 5 else if (s.h > 20f) 4 else 3
        val bh = (s.h + 1f) / n
        for (i in 0 until n) {
            val y = s.y - .5f + i * bh
            val tilt = (dice.next() - .5f) * 1.6f
            val l = s.x - .8f - dice.next() * .8f
            val r = s.x + s.w + .8f + dice.next() * .8f
            plan.add(
                DecayInk.BOARD,
                floatArrayOf(
                    l,
                    y + tilt,
                    r,
                    y - tilt,
                    r,
                    y - tilt + bh - .35f,
                    l,
                    y + tilt + bh - .35f,
                ),
            )
            plan.add(
                DecayInk.BOARD_LINE,
                floatArrayOf(l, y + tilt + bh - .35f, r, y - tilt + bh - .35f),
            )
        }
        // the notice: a sheet with a red heading and two lines of print
        val nw = minOf(7f, s.w * .45f)
        val nh = nw * 1.25f
        val nx = s.x + s.w * (.15f + .4f * dice.next())
        val ny = s.y + s.h * .18f
        plan.add(
            DecayInk.NOTICE,
            floatArrayOf(nx, ny, nx + nw, ny + .2f, nx + nw, ny + nh, nx - .1f, ny + nh - .1f),
        )
        plan.add(DecayInk.NOTICE_INK, floatArrayOf(nx + .9f, ny + 1.4f, nx + nw - .9f, ny + 1.5f))
        plan.add(
            DecayInk.NOTICE_INK,
            floatArrayOf(nx + .9f, ny + nh * .55f, nx + nw - 1.2f, ny + nh * .55f),
        )
        plan.add(
            DecayInk.NOTICE_INK,
            floatArrayOf(nx + .9f, ny + nh * .75f, nx + nw * .6f, ny + nh * .75f),
        )
    }

    /** The lintel over a window has dropped in its middle: a dark wedge opened above it. */
    private fun lintelDrop(plan: DecayPlan, s: WindowSpot, wall: Wall) {
        val l = s.x - wall.headEnd + .4f
        val r = s.x + s.w + wall.headEnd - .4f
        val y = s.y - 2f - wall.headDepth
        plan.add(DecayInk.SPALL, floatArrayOf(l, y, r, y, (l + r) / 2f + 1.5f, y + 1.3f))
        plan.add(DecayInk.CRACK_WIDE, floatArrayOf(l, y - .1f, l + 1.2f, y - 1.6f))
        plan.add(DecayInk.CRACK_WIDE, floatArrayOf(r, y - .1f, r - 1.2f, y - 1.6f))
    }

    /** A little heap of fallen pieces at the floor's foot about [x]: bits of wall, or glass. */
    private fun rubble(plan: DecayPlan, x: Float, height: Float, dice: Dice, glass: Boolean) {
        val base = height - 1.6f
        val n = 9
        for (i in 0 until n) {
            val off = (dice.next() + dice.next() - 1f) * 7f
            val heap = 1.4f * (1f - abs(off) / 7f)
            val w = .9f + 1.5f * dice.next()
            val h = .6f + 1f * dice.next()
            val l = x + off - w / 2f
            val b = base - heap * dice.next()
            if (glass) {
                plan.add(
                    DecayInk.SHARD,
                    floatArrayOf(l, b, l + w * (.3f + .5f * dice.next()), b - h, l + w, b),
                )
            } else {
                val lean = (dice.next() - .5f) * .6f
                plan.add(
                    DecayInk.RUBBLE,
                    floatArrayOf(
                        l,
                        b,
                        l + .2f + lean,
                        b - h,
                        l + w - .2f + lean,
                        b - h * .8f,
                        l + w,
                        b,
                    ),
                )
                plan.add(
                    DecayInk.RUBBLE_LIT,
                    floatArrayOf(l + .2f + lean, b - h, l + w - .2f + lean, b - h * .8f),
                )
            }
        }
    }

    private fun clampTo(s: WindowSpot, pts: FloatArray): FloatArray {
        for (i in 0 until pts.size / 2) {
            pts[i * 2] = pts[i * 2].coerceIn(s.x + .3f, s.x + s.w - .3f)
            pts[i * 2 + 1] = pts[i * 2 + 1].coerceIn(s.y + .3f, s.y + s.h - .3f)
        }
        return pts
    }

    private fun shifted(p: FloatArray, dx: Float, dy: Float) =
        FloatArray(p.size) { p[it] + if (it % 2 == 0) dx else dy }

    /** How far a sill reaches past its opening, and how far down its foot is. */
    private const val SILL_END = BuildingDetail.REVEAL + BuildingDetail.SILL_REACH - .4f
    private const val B_SILL = BuildingDetail.SILL_H + 1.6f

    /** The wall between two windows. */
    private val PIER = B.PITCH - B.WIN_W

    // ---------------------------------------------------------------- the wall

    /** A piece of the face come away: its outline, its broken top edge, the lip under it. */
    class Spall(
        val poly: FloatArray,
        val top: FloatArray,
        val bottom: FloatArray,
        val light: Boolean,
    )

    /**
     * A floor's wall of [material]: where its joints are (the tile is laid from the floor's
     * top-left corner, [FacadeSprites]), what is window, and how a crack runs across it.
     */
    private class Wall(
        val material: HabitMaterial,
        val height: Float,
        val spots: List<WindowSpot>,
    ) {
        val brick = material == HabitMaterial.CLAY
        val half = B.W / 2f

        /** How far past the opening a lintel reaches, and how deep it is. */
        val headEnd = if (brick) BuildingDetail.REVEAL + 1.3f else BuildingDetail.REVEAL + 2.4f
        val headDepth = if (brick) 2.6f else 2.4f

        /** Whether ([x], [y]) is on an opening, its surround or its sill, or off the wall. */
        fun blocked(x: Float, y: Float): Boolean {
            if (x < -half + 2f || x > half - 2f || y < 1.5f || y > height - 1.5f) return true
            for (s in spots) {
                if (x > s.x - 2.6f && x < s.x + s.w + 2.6f && y > s.y - 5f && y < s.y + s.h + 3.2f)
                    return true
                if (
                    x > s.x - 4f &&
                        x < s.x + s.w + 4f &&
                        y > s.y + s.h - .2f &&
                        y < s.y + s.h + 3.2f
                )
                    return true
            }
            return false
        }

        /**
         * A crack from ([x0], [y0]) running down (or, past [to] above it, up) and [dir]ward until
         * [to], an opening or the wall's edge: up the joints in steps on brick, a fine broken line
         * through the blocks otherwise.
         */
        fun walk(x0: Float, y0: Float, dir: Int, to: Float, dice: Dice): FloatArray {
            val out = Points()
            out.add(x0, y0)
            if (brick) stepped(out, x0, y0, dir, to, dice)
            else fracture(out, x0, y0, dir, to, dice, 1.05f, 1.3f)
            return out.toArray()
        }

        /**
         * A crack down a pier from ([x0], [y0]) to [to]: near upright, through whatever it meets.
         */
        fun pier(x0: Float, y0: Float, to: Float, dice: Dice): FloatArray {
            val out = Points()
            out.add(x0, y0)
            fracture(out, x0, y0, if (dice.next() < .5f) -1 else 1, to, dice, 1.32f, 1.57f)
            return out.toArray()
        }

        private fun stepped(out: Points, x0: Float, y0: Float, dir: Int, to: Float, dice: Dice) {
            val c = FacadeSprites.COURSE
            val bw = FacadeSprites.BRICK_W
            var u = x0 + half
            var v = y0
            // into the nearest head joint in this course, and down it
            var row = floor(v / c).toInt()
            var head = headJoint(row, u - dir * 2f, dir)
            val bed = (row + 1) * c + .5f
            if (!line(out, head - half, v + .4f, dice, 0f)) return
            if (!line(out, head - half, bed, dice)) return
            u = head
            v = bed
            while (v < to) {
                row++
                if (dice.next() < .18f) {
                    // through the brick, straight down a course
                    head = u
                } else {
                    // along the bed joint to the next course's head joint
                    head = u + dir * bw / 2f
                    if (!line(out, head - half, v, dice)) return
                }
                val next = minOf(to, (row + 1) * c + .5f)
                if (!line(out, head - half, next, dice)) return
                u = head
                v = next
            }
        }

        private fun headJoint(row: Int, u: Float, dir: Int): Float {
            val bw = FacadeSprites.BRICK_W
            val off = if (row % 2 == 0) .5f else bw / 2f + .5f
            val k = (u - off) / bw
            val i = if (dir > 0) kotlin.math.ceil(k) else floor(k)
            return off + i.toFloat() * bw
        }

        private fun fracture(
            out: Points,
            x0: Float,
            y0: Float,
            dir: Int,
            to: Float,
            dice: Dice,
            low: Float,
            high: Float,
        ) {
            var x = x0
            var y = y0
            // mostly steep, a little this way and that, now and then along a bed joint
            var a = low + (high - low) * dice.next()
            val bed =
                if (material == HabitMaterial.STONE) FacadeSprites.STONE_H
                else FacadeSprites.PANEL_H
            var run = 0f
            while (y < to) {
                val joint = (y % bed) < .9f || (y % bed) > bed - .3f
                val step: Float
                if (joint && run <= 0f && high < 1.5f && dice.next() < .3f)
                    run = 2f + 3f * dice.next()
                if (run > 0f) {
                    step = 1.5f
                    x += dir * step
                    y += (dice.next() - .5f) * .2f
                    run -= step
                } else {
                    step = 1.2f + 1.4f * dice.next()
                    a = (a + (dice.next() - .5f) * .6f).coerceIn(low - .25f, high + .1f)
                    x += dir * cos(a) * step
                    y += sin(a) * step
                }
                if (!line(out, x, minOf(y, to), dice, 0f)) return
            }
        }

        /** Adds a point unless it is on an opening; false once the crack has met one. */
        private fun line(
            out: Points,
            x: Float,
            y: Float,
            dice: Dice,
            wobble: Float = .3f,
        ): Boolean {
            val px = out[out.size - 2]
            val py = out[out.size - 1]
            // a joint's crack wanders inside it: a kink midway along a long run
            val mx = (px + x) / 2f + (dice.next() - .5f) * wobble
            val my = (py + y) / 2f + (dice.next() - .5f) * wobble
            if (blocked(mx, my)) return false
            if (wobble > 0f) out.add(mx, my)
            if (blocked(x, y)) return false
            out.add(x + (dice.next() - .5f) * wobble * .6f, y + (dice.next() - .5f) * wobble * .6f)
            return true
        }

        /** Short hairlines off a crack's turns. */
        fun branches(c: FloatArray, stage: Int, dice: Dice): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            val n = c.size / 2
            val want = if (stage >= 3) 3 else 2
            var i = 2
            while (i < n - 1 && out.size < want) {
                if (dice.next() < .45f) {
                    val x = c[i * 2]
                    val y = c[i * 2 + 1]
                    val a = (dice.next() - .5f) * 2.4f + if (dice.next() < .5f) 0f else PI.toFloat()
                    val len = 2.5f + 3.5f * dice.next()
                    val ex = x + cos(a) * len
                    val ey = y + abs(sin(a)) * len * .7f + 1f
                    if (!blocked(ex, ey))
                        out +=
                            floatArrayOf(
                                x,
                                y,
                                (x + ex) / 2f + (dice.next() - .5f),
                                (y + ey) / 2f,
                                ex,
                                ey,
                            )
                }
                i += 2
            }
            return out
        }

        /**
         * [count] pieces of the face come away next to crack [c]: whole or half brick faces on
         * brick, chips round the fracture on stone and gold. None on an opening.
         */
        fun cells(c: FloatArray, count: Int, dice: Dice): List<Spall> {
            val out = ArrayList<Spall>()
            val n = c.size / 2
            var tries = 0
            while (out.size < count && tries < 12) {
                tries++
                val i = 1 + (dice.next() * (n - 1)).toInt().coerceAtMost(n - 2)
                val x = c[i * 2]
                val y = c[i * 2 + 1]
                val s = if (brick) brickFace(x, y, dice) else chip(x, y, dice)
                if (s == null) continue
                if (
                    out.any { abs(it.poly[0] - s.poly[0]) < 3f && abs(it.poly[1] - s.poly[1]) < 3f }
                )
                    continue
                out += s
            }
            return out
        }

        private fun brickFace(x: Float, y: Float, dice: Dice): Spall? {
            val c = FacadeSprites.COURSE
            val bw = FacadeSprites.BRICK_W
            // the brick beside the point, this side of it or that
            val v = y + (if (dice.next() < .5f) -1.5f else 1.5f)
            val row = floor(v / c).toInt()
            val u = x + half + (if (dice.next() < .5f) -3f else 3f)
            val start = headJoint(row, u, -1)
            val l = start + .6f - half
            val r = start + bw - .4f - half
            val t = row * c + 1f
            val b = (row + 1) * c
            if (blocked(l, t) || blocked(r, t) || blocked(l, b) || blocked(r, b)) return null
            // most come away only in part: one end broken off short, the rest still there
            val whole = dice.next() < .3f
            val cut = if (whole) r else l + (r - l) * (.4f + .35f * dice.next())
            val from = dice.next() < .5f
            val x0 = if (from) l else r - (cut - l)
            val x1 = if (from) cut else r
            val jag = (dice.next() - .5f) * 1.6f
            val poly =
                if (whole) floatArrayOf(x0, t, x1, t, x1, b, x0, b)
                else if (from)
                    floatArrayOf(
                        x0,
                        t,
                        x1 + jag,
                        t,
                        x1 - jag * .5f + .4f,
                        (t + b) / 2f,
                        x1 - jag,
                        b,
                        x0,
                        b,
                    )
                else
                    floatArrayOf(
                        x0 + jag,
                        t,
                        x1,
                        t,
                        x1,
                        b,
                        x0 - jag,
                        b,
                        x0 + jag * .5f - .4f,
                        (t + b) / 2f,
                    )
            return Spall(
                poly,
                floatArrayOf(x0, t + .3f, x1, t + .3f),
                floatArrayOf(x0, b - .2f, x1, b - .2f),
                false,
            )
        }

        private fun chip(x: Float, y: Float, dice: Dice): Spall? {
            // a flake off a block's face beside the fracture: the fresher stone under it lighter,
            // its broken top edge in shade
            val w = 1.8f + 2.4f * dice.next()
            val h = 1.2f + 1.4f * dice.next()
            val l = x - w * (.2f + .6f * dice.next())
            val t = y - h * .5f
            if (blocked(l, t) || blocked(l + w, t) || blocked(l, t + h) || blocked(l + w, t + h))
                return null
            val k = (dice.next() - .5f) * .8f
            val poly =
                floatArrayOf(
                    l,
                    t + h * .4f,
                    l + w * .35f,
                    t + k * .5f,
                    l + w * .8f,
                    t,
                    l + w,
                    t + h * .55f,
                    l + w * .55f,
                    t + h,
                    l + w * .15f,
                    t + h * .85f,
                )
            return Spall(
                poly,
                floatArrayOf(l, t + h * .4f, l + w * .35f, t + k * .5f, l + w * .8f, t),
                floatArrayOf(),
                true,
            )
        }
    }

    /** A growable list of points. */
    private class Points {
        private var a = FloatArray(32)
        var size = 0
            private set

        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x
            a[size++] = y
        }

        operator fun get(i: Int) = a[i]

        fun toArray(): FloatArray = a.copyOf(size)
    }
}
