package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Activity
import com.lifemax.rpg.feature.habits.domain.PeopleActivities
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The people's pre-rendered frames: every (look, activity, frame) is a small image, made on first
 * use from the rig ([PeopleActivities.figure]) and lit by [PeopleRaster]. All the frames of an
 * activity share one box (the union of its reach over every look and frame, so nobody jitters
 * between frames), cut as tight as the activity allows. Pure: it makes pixels; [SceneSprites] turns
 * them into images, keeps the pool and counts its bytes.
 */
internal object PeopleSprites {
    /**
     * Pixels to a unit: a little under what a phone shows at the focus camera (about 2.6 to 3.6);
     * the glow is soft, so it stands being drawn larger.
     */
    const val PPU = 1.9f

    /** Pixels left round a figure for its glow. */
    private const val PAD = 3

    /** One activity's frame box: its top-left from the feet's middle, in pixels, and its size. */
    class Box(val left: Int, val top: Int, val w: Int, val h: Int)

    private val boxes = arrayOfNulls<Box>(Activity.entries.size)

    fun box(a: Activity): Box =
        boxes[a.ordinal]
            ?: run {
                val r = PeopleActivities.reach(a)
                val left = floor(r[0] * PPU).toInt() - PAD
                val top = floor(r[1] * PPU).toInt() - PAD
                val right = ceil(r[2] * PPU).toInt() + PAD
                val bottom = ceil(r[3] * PPU).toInt() + PAD
                Box(left, top, right - left, bottom - top).also { boxes[a.ordinal] = it }
            }

    /** The frame [frame] of [a] for [look]. */
    fun render(look: Int, a: Activity, frame: Int): Sprite {
        val spec = PeopleActivities.of(a)
        val b = box(a)
        val fig = PeopleActivities.figure(look, a, frame)
        val shapes = fig.shapes()
        return PeopleRaster.figure(
            shapes,
            b.w,
            b.h,
            PPU,
            -b.left.toFloat(),
            -b.top.toFloat(),
            spec.glows,
        )
    }

    /**
     * [render] cut down to the pixels that have anything in them (so a walk's narrow passing frame
     * does not keep the room its wide contact frame needs): the same picture, less memory. The
     * origin stays the feet's middle, so [Sprite.ox] and [Sprite.oy] say where it is in the crop.
     */
    fun bake(look: Int, a: Activity, frame: Int): Sprite = crop(render(look, a, frame))

    fun crop(sp: Sprite): Sprite {
        var x0 = sp.w
        var y0 = sp.h
        var x1 = -1
        var y1 = -1
        for (y in 0 until sp.h) for (x in 0 until sp.w) if (
            (sp.pixels[y * sp.w + x] ushr 24) != 0
        ) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        if (x1 < 0) return Sprite(1, 1, sp.ppu, sp.ox - sp.w / 2, sp.oy - sp.h / 2, IntArray(1))
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(sp.pixels, (y + y0) * sp.w + x0, out, y * w, w)
        return Sprite(w, h, sp.ppu, sp.ox - x0, sp.oy - y0, out)
    }

    /**
     * What every frame of [a] takes in the box, for every look, in bytes (what [bake] saves is on
     * top).
     */
    fun bytes(a: Activity): Int {
        val b = box(a)
        return b.w * b.h * 4 * PeopleActivities.of(a).totalFrames * PeopleActivities.looksOf(a)
    }

    /**
     * What the whole atlas would take if every frame of every look were made in its box, in bytes.
     */
    fun worstCaseBytes(): Int = Activity.entries.sumOf { bytes(it) }

    /**
     * What every frame of every look takes as [bake] keeps it, in bytes: what the atlas really
     * grows to if every one of them is made (they are made as they are first drawn).
     */
    fun bakedBytes(): Int {
        var total = 0
        for (a in Activity.entries) {
            val spec = PeopleActivities.of(a)
            for (l in 0 until PeopleActivities.looksOf(a)) for (f in 0 until spec.totalFrames) {
                val s = bake(l, a, f)
                total += s.w * s.h * 4
            }
        }
        return total
    }
}
