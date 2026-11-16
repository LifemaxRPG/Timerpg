package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.abs
import kotlin.math.sqrt

/** A baked piece of the street's small life, [w] by [h] pixels of ARGB. */
internal class DuskLayer(val w: Int, val h: Int, val pixels: IntArray) {
    val bytes: Int
        get() = w * h * 4
}

/**
 * The small life's painted pieces, made once from pure Kotlin ([Noise], [Px]) and drawn as images:
 * three soft puffs of steam (white at every alpha, so they take the sky's tint; the plume is
 * several of them rising and growing), and a pigeon in five frames (standing, pecking, looking
 * round, and two wing beats), in its own grey, drawn facing left and mirrored to face right.
 * Nothing here is called per frame.
 */
internal object DuskSprites {
    /** Pixels in a street unit for the pigeon (it is about 5 units long). */
    const val PIGEON_PPU = 3f
    const val PIGEON_W = 18
    const val PIGEON_H = 15
    const val PIGEON_FRAMES = 5

    const val PUFFS = 3
    const val PUFF = 28

    fun bytes(): Int = PUFFS * PUFF * PUFF * 4 + PIGEON_FRAMES * PIGEON_W * PIGEON_H * 4

    /**
     * Puff [variant]: a round heap of noise, dense in the middle and ragged at its edge, clear a
     * pixel inside its border.
     */
    fun puff(variant: Int): DuskLayer {
        val n = PUFF
        val px = IntArray(n * n)
        val c = (n - 1) / 2f
        val r = c - 1.5f
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x - c) / r
            val dy = (y - c) / r
            val d = sqrt(dx * dx + dy * dy)
            if (d >= 1f) continue
            val rag = Noise.fbm(x * .16f + variant * 7.1f, y * .16f, 61 + variant, 3)
            // the edge is eaten by the noise, the middle stays solid
            val edge = Px.smooth(1f, .45f, d + (rag - .5f) * .5f)
            val body = .55f + .45f * Noise.billow(x * .11f, y * .11f, 91 + variant, 2)
            val a = Px.clamp01(edge * body * .9f)
            if (a > .004f) px[y * n + x] = Px.argbF(a, 1f, 1f, 1f)
        }
        return DuskLayer(n, n, px)
    }

    /**
     * Pigeon frame [frame], facing left (its head at the left): 0 standing, 1 pecking (head down to
     * the ground), 2 looking round (head turned back over its shoulder), 3 wings up, 4 wings down.
     */
    fun pigeon(frame: Int): DuskLayer {
        val w = PIGEON_W
        val h = PIGEON_H
        val px = IntArray(w * h)
        val body = Px.argb(255, 0x7E, 0x82, 0x8E)
        val bodyLit = Px.argb(255, 0x9A, 0x9E, 0xA8)
        val head = Px.argb(255, 0x4E, 0x52, 0x60)
        val neck = Px.argb(255, 0x5E, 0x7A, 0x6A)
        val wingBar = Px.argb(255, 0x3C, 0x40, 0x4C)
        val beak = Px.argb(255, 0xE0, 0xA8, 0x60)
        val foot = Px.argb(255, 0xB8, 0x66, 0x5A)
        val eye = Px.argb(255, 0x10, 0x10, 0x14)
        val flying = frame >= 3
        // the ground is the last row; the body is an egg lying along the row above the legs
        val groundY = h - 1.5f
        val legs = if (flying) 1.6f else 2f
        val by = if (flying) h - 8.5f else groundY - legs - 3.4f
        val bx = 9.4f
        val bw = 6f
        val bh = if (flying) 2.6f else 3.4f
        fun put(x: Int, y: Int, c: Int) {
            if (x in 1 until w - 1 && y in 1 until h - 1) px[y * w + x] = c
        }
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, c: Int, lit: Int) {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val dx = (x + .5f - cx) / rx
                val dy = (y + .5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) put(x, y, if (dy < -.45f) lit else c)
            }
        }
        // the tail: a short wedge trailing right, a little up
        for (i in 0 until 4) {
            val x = (bx + bw - 1f + i).toInt()
            put(x, (by - .4f - i * .25f).toInt(), wingBar)
            put(x, (by + .6f - i * .25f).toInt(), body)
        }
        ellipse(bx, by, bw, bh, body, bodyLit)
        // the folded wing's dark bar along the body (standing) or the wings (flying)
        if (!flying) {
            for (i in 0 until 5) put((bx - 1.5f + i).toInt(), (by + .4f).toInt(), wingBar)
            put((bx + 2.5f).toInt(), (by - .6f).toInt(), wingBar)
        } else {
            // a wing swept up over the back or down below the belly, two pixels thick, with a
            // dark trailing edge
            val up = frame == 3
            for (i in 0 until 9) {
                val t = i / 8f
                val lift = if (up) -(1.5f + 6f * t) else (1.2f + 3.6f * t)
                val x = (bx - 3f + i * 1f).toInt()
                val y = (by + lift).toInt()
                put(x, y, body)
                put(x + 1, y, body)
                put(x, y + (if (up) 1 else -1), bodyLit)
                put(x, y + (if (up) -1 else 1), wingBar)
            }
        }
        // the head: forward (standing, flying), down to the ground (pecking), back (looking round)
        val hx: Float
        val hy: Float
        when (frame) {
            1 -> {
                hx = bx - bw - .2f
                hy = groundY - 1.4f
            }
            2 -> {
                hx = bx - bw + 3.2f
                hy = by - bh - 1f
            }
            else -> {
                hx = bx - bw + .4f
                hy = by - bh + .6f
            }
        }
        // the neck between body and head, iridescent
        val nx = (bx - bw + 1.2f + (hx - (bx - bw + 1.2f)) * .5f)
        val ny = (by - bh * .4f + (hy - (by - bh * .4f)) * .5f)
        ellipse(nx, ny, 1.6f, 1.5f, neck, neck)
        ellipse(hx, hy, 1.7f, 1.5f, head, head)
        val facing = if (frame == 2) 1f else -1f
        put((hx + facing * 2.1f).toInt(), (hy + .2f).toInt(), beak)
        put((hx + facing * .9f).toInt(), (hy - .4f).toInt(), eye)
        // the legs and feet, on the ground or tucked in flight
        for (k in 0 until 2) {
            val lx = (bx - 1.2f + k * 2.4f).toInt()
            if (flying) {
                put(lx, (by + bh + .3f).toInt(), foot)
            } else {
                for (j in 0 until legs.toInt()) put(lx, (by + bh + j).toInt(), foot)
                put(lx - 1, (groundY - .5f).toInt(), foot)
                put(lx + 1, (groundY - .5f).toInt(), foot)
            }
        }
        // a soft shadow under a standing pigeon
        if (!flying)
            for (i in -3..3) {
                val x = (bx + i).toInt()
                val a = (.35f * (1f - abs(i) / 4f))
                if (px[(h - 2) * w + x] == 0) px[(h - 2) * w + x] = Px.argbF(a, 0f, 0f, 0f)
            }
        return DuskLayer(w, h, px)
    }
}
