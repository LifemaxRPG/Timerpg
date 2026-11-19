package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * More pictures for the posters ([CinemaPosters]): a composition for every motif the town's films carry in each decade
 * (a rider, a sword and a castle, a knife over a lake, a group of teens, two partners back to back, a kung fu kick, a vault
 * door, a tidal wave, an astronaut's visor, a walled city...), and seeded variants within a motif (which creature, how the
 * gunfighters stand, which symbol a 2020s horror bill carries), so a bill of eight or twelve never repeats a picture.
 * Every shape is generic: no real character, logo, prop or person.
 */
internal object CinemaPosterArt {
    private const val W = CinemaPosters.W
    private const val H = CinemaPosters.H
    private fun c(rgb: Long) = Paint.c(rgb)
    private val INK = c(0x1A1A28)

    /** A variant 0 until [n] for [f]: its poster seed and title mixed, so films of one motif differ. */
    fun alt(f: CinemaFilm, n: Int, salt: Int = 0): Int {
        // (a film given its place among its motif's films on a bill steps through the motif's compositions in turn)
        if (f.look >= 0) {
            var g = f.motif.ifEmpty { f.genre }.hashCode() * 31 + salt * 977
            g = g xor (g ushr 15); g *= 0x2c1b3c6d; g = g xor (g ushr 12)
            return ((g and 0x7fffffff) + f.look) % n
        }
        var h = f.seed * -0x61c88647 + f.title.hashCode() * 31 + salt * 977
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12)
        return ((h and 0x7fffffff) % n)
    }

    // ------------------------------------------------------------------ 1950s (painted on paper: rows 2..52, the title below)

    /** A giant spider straddling the town, its legs over the roofs, the searchlights crossing. */
    fun spider(im: PixImg, f: CinemaFilm, r: Paint.Rnd, sky: Int, body: Int) {
        Pix.bands(im, 2, 2, W - 3, 52, sky, Pix.mix(sky, c(0xD86A3A), .6f), 5)
        for (k in 0 until 2) Pix.tri(im, 6f + k * 34f, 52f, 2f + k * 40f, 4f, 10f + k * 30f, 4f, Pix.mix(sky, c(0xFFFFE0), .25f))
        Pix.skyline(im, 2, W - 3, 52, 12, c(0x1A1A28), r, lit = c(0xF6C83A))
        val cx = 24f + (alt(f, 9, 2) - 4); val cy = 22f
        for (s in listOf(-1, 1)) for (k in 0 until 4) {
            val kx = (cx + s * (6 + k * 4)).toInt(); val ky = (cy + 6 + k).toInt()
            Pix.line(im, cx.toInt(), cy.toInt() + 2, kx, (cy - 6 + k * 3).toInt(), body)
            Pix.line(im, kx, (cy - 6 + k * 3).toInt(), (kx + s * 5), ky + 18, body)
        }
        Pix.ellipse(im, cx, cy + 4, 7f, 6f, body); Pix.disc(im, cx, cy - 3, 4f, Pix.mix(body, c(0xFFFFFF), .15f))
        for (k in -1..1 step 2) { im[(cx + k * 1.5f).toInt(), (cy - 4).toInt()] = c(0xF6283A); im[(cx + k * 2.5f).toInt(), (cy - 3).toInt()] = c(0xF6283A) }
        Pix.figure(im, 34f, 56f, 9f, c(0xF0E0D0), pose = 4)
        im.rect(2, 53, W - 3, 56, c(0x2A2A3A))
    }

    /** Tentacles out of a night sea round a little steamer, a lighthouse's beam. */
    fun sea(im: PixImg, f: CinemaFilm, r: Paint.Rnd, sky: Int, body: Int) {
        Pix.bands(im, 2, 2, W - 3, 34, c(0x0E1830), sky, 4)
        im.rect(2, 34, W - 3, 52, c(0x14304A)); for (k in 0 until 30) im[2 + r.int(W - 4), 35 + r.int(17)] = c(0x3A6A8A)
        Pix.disc(im, 36f, 10f, 5f, c(0xF0E8C0))
        // the lighthouse and its beam
        im.rect(4, 18, 7, 34, c(0xE8E0D0)); im.rect(4, 22, 7, 23, c(0xC8302A)); Pix.tri(im, 7f, 18f, 30f, 12f, 30f, 22f, Pix.mix(sky, c(0xFFF6C0), .45f))
        // the steamer
        im.rect(14, 34, 30, 37, c(0x1A1A22)); im.rect(19, 30, 26, 34, c(0xE8E0D0)); im.rect(22, 26, 23, 30, c(0xC8302A))
        val n = 3 + alt(f, 3, 3)
        for (k in 0 until n) {
            val x0 = 6 + k * (36 / n) + r.int(4); val top = 10 + r.int(14)
            for (y in top..50) { val xx = x0 + (2.5f * sin((y - top) * .35f + k)).toInt(); val w = 1 + (y - top) / 12; im.rect(xx - w, y, xx + w, y, body) }
            for (y in top + 4..50 step 4) im[x0 + (2.5f * sin((y - top) * .35f + k)).toInt(), y] = Pix.mix(body, c(0xF0D0D0), .5f)
        }
        im.rect(2, 53, W - 3, 56, c(0x2A2A3A))
    }

    /** Two gunfighters facing off down a main street at sundown, their long shadows. */
    fun duel(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 2, 2, W - 3, 50, col(f, 0, c(0xF6C85A)), col(f, 2, c(0xC8402A)), 6)
        Pix.disc(im, 24f, 30f, 9f, c(0xFFF0A0))
        // the false fronts either side
        for (s in listOf(0, 1)) {
            val x0 = if (s == 0) 2 else W - 13
            im.rect(x0, 18 + s * 3, x0 + 10, 50, c(0x5A2A1E)); im.rect(x0, 16 + s * 3, x0 + 10, 18 + s * 3, c(0x3A1A14))
            for (y in 24..44 step 8) im.rect(x0 + 3, y, x0 + 6, y + 4, c(0xF6C85A))
        }
        im.rect(2, 44, W - 3, 54, c(0xA8683A))
        val sx = 16f + alt(f, 3, 4); val ex = 33f - alt(f, 3, 5)
        Pix.figure(im, sx, 50f, 14f, INK, pose = 3); Pix.figure(im, ex, 50f, 13f, INK, pose = 3)
        Pix.line(im, sx.toInt(), 50, 2, 54, Pix.mix(c(0xA8683A), INK, .5f)); Pix.line(im, ex.toInt(), 50, W - 3, 54, Pix.mix(c(0xA8683A), INK, .5f))
        // a tumbleweed
        Pix.ring(im, 24f, 51f, 2.2f, 1f, c(0x7A4A2A))
    }

    /** A chorus line kicking under a proscenium, footlights, a star over them. */
    fun chorus(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        val curtain = col(f, 2, c(0xC8283A))
        Pix.bands(im, 2, 2, W - 3, 54, c(0x2A1A3A), c(0x5A2A5A), 3)
        for (x in 2 until W - 2) { val h = 10 + (if (x < 10 || x > W - 12) 30 else 0); for (y in 2 until 2 + h) im[x, y] = if (x % 3 == 0) Pix.mix(curtain, c(0x000000), .3f) else curtain }
        for (k in 0 until 3) { val cx = 12f + k * 12f; Pix.tri(im, cx, 12f, cx - 8f, 50f, cx + 8f, 50f, Pix.mix(c(0x5A2A5A), c(0xFFF0C0), .25f)) }
        val n = 4 + alt(f, 2, 6)
        for (k in 0 until n) Pix.figure(im, 8f + k * (32f / (n - 1)), 50f, 17f, if (k % 2 == 0) col(f, 1, c(0xF6C83A)) else c(0xF0E8E0), pose = 4)
        for (k in 0 until n) Pix.line(im, (8 + k * (32 / (n - 1)) + 1), 44, (8 + k * (32 / (n - 1)) + 6), 38, c(0xF0E0D0))
        im.rect(2, 50, W - 3, 54, c(0x3A1A2A)); for (x in 4 until W - 4 step 4) im[x, 51] = c(0xFFF0A0)
        Pix.disc(im, 24f, 16f, 3f, c(0xFFF6C0)); for (k in 0 until 4) Pix.line(im, 24, 16, 24 + listOf(-6, 6, 0, 0)[k], 16 + listOf(0, 0, -6, 6)[k], c(0xFFF6C0))
    }

    /** A figure in a trench coat under a street lamp, the blinds' shadow across a wall, rain. */
    fun lampPost(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 2, 2, W - 3, 54, c(0x2A2A40), c(0x101018), 4)
        for (y in 6 until 40 step 5) im.rect(26, y, W - 3, y + 2, c(0x3A3A54))
        im.rect(12, 8, 13, 54, c(0x0A0A10)); im.rect(10, 7, 16, 9, c(0x0A0A10)); im.rect(11, 9, 15, 10, c(0xF6E8A0))
        Pix.tri(im, 13f, 10f, 2f, 54f, 26f, 54f, Pix.mix(c(0x2A2A40), c(0xF6E8A0), .3f))
        Pix.figure(im, 18f, 54f, 20f, c(0x0A0A10), pose = 3)
        for (k in 0 until 40) { val x = 2 + r.int(W - 4); val y = 2 + r.int(50); im[x, y] = c(0x6A6A8A); im[x, y + 1] = c(0x4A4A6A) }
        Pix.disc(im, 36f, 46f, 1.6f, col(f, 2, c(0xD8342C)))
    }

    // ------------------------------------------------------------------ 1960s (flat bold colour, the title band from 48)

    /** The 60s western: a lone rider on a ridge against a huge flat sun, a red sky. */
    fun western60(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        val sky = col(f, 0, c(0xE8682A)).let { if (Pix.lum(it) < .25f) c(0xE8682A) else it }
        im.rect(0, 0, W - 1, 47, sky)
        val sx = 14f + alt(f, 20, 7)
        Pix.disc(im, sx, 22f, 13f, Pix.mix(sky, c(0xFFF0A0), .7f))
        im.rect(0, 36, W - 1, 47, c(0x2A1410)); Pix.tri(im, -4f, 47f, 18f, 30f, 40f, 47f, c(0x2A1410))
        rider(im, sx - 2f, 33f, c(0x140A08))
        im.rect(0, 48, W - 1, H - 1, c(0x1A0E0A))
    }

    /** A horse and its rider standing at ([x], [y]) (the hooves), facing right, in [c]. */
    fun rider(im: PixImg, x: Float, y: Float, c: Int) {
        Pix.ellipse(im, x, y - 6f, 6f, 3f, c)
        Pix.line(im, (x + 5).toInt(), (y - 7).toInt(), (x + 8).toInt(), (y - 12).toInt(), c); Pix.line(im, (x + 6).toInt(), (y - 7).toInt(), (x + 9).toInt(), (y - 12).toInt(), c)
        Pix.ellipse(im, x + 9.5f, y - 12f, 2f, 1.2f, c)
        for (lx in listOf(-4, -2, 3, 5)) Pix.line(im, (x + lx).toInt(), (y - 4).toInt(), (x + lx + (if (lx < 0) -1 else 1)).toInt(), y.toInt(), c)
        Pix.line(im, (x - 6).toInt(), (y - 7).toInt(), (x - 8).toInt(), (y - 2).toInt(), c)
        Pix.figure(im, x, y - 6f, 11f, c, pose = 3)
    }

    /** The 60s spy, other cut: a figure in evening dress on a staircase of light, searchlights over a casino-less skyline. */
    fun spyStairs(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        val a = col(f, 2, c(0xF6C83A)); val bg = c(0x101018)
        im.fill(bg)
        for (k in 0 until 3) Pix.tri(im, 10f + k * 14f, 47f, 4f + k * 18f, 0f, 12f + k * 14f, 0f, Pix.mix(bg, a, .25f))
        Pix.skyline(im, 0, W - 1, 47, 14, c(0x1E1E2E), r, lit = a)
        for (k in 0 until 6) im.rect(10 + k * 4, 47 - k * 3, W - 1, 47 - k * 3 + 1, Pix.mix(a, c(0xFFFFFF), .3f))
        Pix.figure(im, 34f, 32f, 18f, c(0xF0E8E0), pose = 0); Pix.figure(im, 28f, 35f, 16f, a, pose = 4)
        im.rect(0, 48, W - 1, 49, a)
    }

    /** The 60s epic, other cut: a colossus statue astride the harbour, galleys under it. */
    fun colossus(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 46, col(f, 0, c(0xC8A46A)).let { if (Pix.lum(it) < .3f) c(0xC8A46A) else it }, c(0x7A3A1A), 5)
        im.rect(0, 36, W - 1, 47, c(0x1E4A6A)); for (k in 0 until 20) im[r.int(W), 37 + r.int(10)] = c(0x5A8AAA)
        val bronze = c(0x8A5A2A)
        Pix.figure(im, 24f, 40f, 36f, bronze, pose = 1)
        im.rect(14, 38, 18, 46, c(0x5A4A3A)); im.rect(30, 38, 34, 46, c(0x5A4A3A))
        Pix.disc(im, 37f, 6f, 2f, c(0xFFE880))
        for (k in 0 until 3) { val gx = 4 + k * 15; im.rect(gx, 42, gx + 8, 43, c(0x3A1A0A)); Pix.tri(im, gx + 4f, 34f, gx + 4f, 41f, gx + 9f, 41f, c(0xE8D8B0)) }
        im.rect(0, 48, W - 1, H - 1, c(0x1A1A28))
    }

    // ------------------------------------------------------------------ 1970s (airbrush; the title from ~44)

    /** The kung fu picture: a flying kick against a huge sun, a temple roof's curve, a bamboo fringe. */
    fun kungfu(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 46, c(0xF6D85A), c(0xE8502A), 5)
        Pix.disc(im, 28f, 22f, 14f, c(0xFFF0C0))
        // the roof: a long sweep with upturned ends
        im.rect(0, 38, W - 1, 46, c(0x2A0A08)); for (x in 0 until W) { val y = 36 - (if (x < 6) 6 - x else if (x > W - 7) x - (W - 7) else 0) / 2; im.rect(x, y, x, 38, c(0x2A0A08)) }
        val k = alt(f, 2, 8)
        val fx = 22f + k * 4
        // the kicker: body leaning back, one leg out straight
        Pix.disc(im, fx - 6f, 14f, 2.4f, INK); Pix.line(im, (fx - 6).toInt(), 16, fx.toInt(), 24, INK); Pix.line(im, (fx - 5).toInt(), 16, (fx + 1).toInt(), 24, INK)
        Pix.line(im, fx.toInt(), 24, (fx + 14).toInt(), 18, INK); Pix.line(im, fx.toInt(), 25, (fx + 14).toInt(), 19, INK)
        Pix.line(im, fx.toInt(), 24, (fx - 6).toInt(), 33, INK); Pix.line(im, (fx - 6).toInt(), 33, (fx - 2).toInt(), 36, INK)
        Pix.line(im, (fx - 5).toInt(), 18, (fx - 12).toInt(), 14, INK); Pix.line(im, (fx - 4).toInt(), 18, (fx + 3).toInt(), 12, INK)
        for (x in 0 until W step 5) im.vline(x, 0, 5 + (x * 7) % 6, c(0x3A6A2A))
        im.rect(0, 46, W - 1, H - 1, c(0x1A0A08))
    }

    /** The 70s chase, other cut: a big rig thundering at you down a desert highway, its headlights. */
    fun rig(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 26, c(0xE8B86A), c(0xC8682A), 3)
        im.rect(0, 26, W - 1, 52, c(0xA8784A)); Pix.tri(im, 24f, 26f, -12f, 52f, 60f, 52f, c(0x4A3A2E))
        val cab = listOf(c(0xC8282A), c(0x2A5AC8), c(0xE8C83A), c(0xE8E8E0))[alt(f, 4, 9)]
        im.rect(13, 22, 35, 44, cab); im.rect(15, 24, 33, 30, c(0x8AC8E8)); im.vline(24, 24, 30, cab)
        im.rect(15, 33, 33, 41, c(0xB8B8C0)); for (y in 34..40 step 2) im.hline(16, 32, y, c(0x7A7A88))
        Pix.disc(im, 16f, 37f, 2f, c(0xFFF6D0)); Pix.disc(im, 32f, 37f, 2f, c(0xFFF6D0))
        im.rect(9, 16, 11, 30, c(0xC8C8D0)); im.rect(37, 16, 39, 30, c(0xC8C8D0))
        im.rect(13, 44, 18, 48, INK); im.rect(30, 44, 35, 48, INK)
        for (k in 0 until 20) im[r.int(W), 40 + r.int(12)] = c(0xC8A87A)
        im.rect(0, 52, W - 1, H - 1, c(0x3A2A1A))
    }

    // ------------------------------------------------------------------ 1980s (picture rows 0..50; the chrome title after)

    /** The slasher: a cabin across a black lake under the moon, a blade's glint over it all, one lit window. */
    fun slasher(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(c(0x06060E))
        val moon = col(f, 2, c(0xE8E0C0)).let { if (Pix.lum(it) < .4f) c(0xE8E0C0) else it }
        val mx = 10f + alt(f, 28, 10)
        Pix.disc(im, mx, 12f, 6f, moon)
        for (x in 0 until W) { val h = 4 + ((x * 13) % 7); im.vline(x, 30 - h, 30, c(0x0A140E)) }
        im.rect(0, 30, W - 1, 50, c(0x0A1420)); for (k in 0 until 12) im.hline((mx - 4 + r.int(9)).toInt(), (mx + r.int(4)).toInt(), 32 + r.int(16), Pix.mix(moon, c(0x0A1420), .5f))
        im.rect(30, 22, 42, 30, c(0x1A120E)); Pix.tri(im, 28f, 23f, 36f, 16f, 44f, 23f, c(0x1A120E)); im.rect(33, 25, 35, 27, c(0xF6C83A))
        // the blade: a long tapering shape down the left, lit along its edge
        val bx = 8 + alt(f, 6, 11)
        Pix.tri(im, bx.toFloat(), 2f, bx + 5f, 2f, bx + 1f, 40f, c(0xC8D0D8)); im.vline(bx, 3, 38, c(0xFFFFFF))
        im.rect(bx - 2, 0, bx + 7, 2, c(0x2A1A14))
    }

    /** The teen comedy: a hot pink sky, a gang of four in a row, shades and a boombox, palm stripes. */
    fun teens(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 50, col(f, 1, c(0xFF5AB8)), col(f, 2, c(0x5AE8FF)), 5)
        for (k in 0 until 4) Pix.disc(im, 6f + r.int(36), 6f + r.int(16), 1.2f, c(0xFFFFFF))
        val n = 3 + alt(f, 3, 12)
        val cols = listOf(c(0xF6E83A), c(0x3A2A8A), c(0xE83A3A), c(0x2AA86A), c(0xFFFFFF))
        for (k in 0 until n) {
            val x = 8f + k * (32f / (n - 1)); val h = 22f + (k * 5 % 4)
            Pix.figure(im, x, 49f, h, cols[(k + alt(f, 5, 13)) % cols.size], pose = if (k % 2 == 0) 1 else 0)
            im.rect((x - 2).toInt(), (49 - h + 1).toInt(), (x + 2).toInt(), (49 - h + 2).toInt(), INK)
        }
        im.rect(20, 44, 27, 48, INK); Pix.disc(im, 22f, 46f, 1.4f, c(0xC8C8D0)); Pix.disc(im, 26f, 46f, 1.4f, c(0xC8C8D0))
    }

    /** The fantasy: a hero holding a sword high on a crag, a castle on the far peak, two moons. */
    fun fantasy(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 50, c(0x1A0A3A), col(f, 0, c(0x5A2A8A)).let { if (Pix.lum(it) < .1f) c(0x5A2A8A) else it }, 5)
        Pix.disc(im, 36f, 10f, 5f, c(0xE8E0F6)); Pix.disc(im, 28f, 6f, 2.4f, c(0xF6C8A0))
        // the far peak and its castle
        Pix.tri(im, 26f, 20f, 12f, 50f, 46f, 50f, c(0x2A1A4A)); im.rect(23, 14, 30, 22, c(0x2A1A4A)); for (x in listOf(23, 26, 29)) im.rect(x, 12, x + 1, 14, c(0x2A1A4A)); im[26, 18] = c(0xF6C83A)
        // the crag and the hero
        val hx = 12f + alt(f, 2, 14) * 18f
        Pix.tri(im, hx, 36f, hx - 16f, 50f, hx + 16f, 50f, c(0x0A0814))
        Pix.figure(im, hx, 37f, 16f, c(0x0A0814), pose = 2, cape = c(0x6A1A2A))
        Pix.line(im, (hx + 2).toInt(), 25, (hx + 5).toInt(), 12, c(0xE8F0FF)); im[(hx + 5).toInt(), 11] = c(0xFFFFFF)
        for (k in 0 until 8) { val a = k * .785f; im[(hx + 5 + cos(a) * 3).toInt(), (11 + sin(a) * 3).toInt()] = c(0xFFF6C0) }
    }

    /** The buddy cop picture: two partners back to back, a light bar's red and blue across the city. */
    fun buddies(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(c(0x0A0A18))
        for (x in 0 until W) { val t = x / (W - 1f); im.vline(x, 0, 24, Pix.mix(c(0xC81E2A), c(0x1E3AC8), t)) }
        for (y in 0 until 24) for (x in 0 until W) if ((x + y) % 3 != 0) im[x, y] = Pix.mix(im[x, y], c(0x0A0A18), .5f)
        Pix.skyline(im, 0, W - 1, 36, 14, c(0x101020), r, lit = c(0xF6E8A0))
        im.rect(0, 36, W - 1, 50, c(0x0A0A18))
        val s = alt(f, 2, 15)
        Pix.figure(im, 18f, 50f, 30f, c(0x2A2A3A), pose = 0); Pix.figure(im, 30f, 50f, 29f, c(0x6A3A2A), pose = 0)
        im.rect(14, 22, 22, 25, if (s == 0) c(0xF6F6F0) else c(0x2A2A3A))
        Pix.disc(im, 18f, 23f, 2.6f, c(0xE8B890)); Pix.disc(im, 30f, 24f, 2.6f, c(0xC88A60))
    }

    /** The 80s action, other cut: a headband commando in the jungle, a helicopter against the sun. */
    fun jungle(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 50, c(0xF6A83A), c(0xA81E1A), 5)
        Pix.disc(im, 14f, 14f, 9f, c(0xFFE8A0))
        im.rect(8, 12, 22, 14, INK); Pix.disc(im, 15f, 15f, 2.5f, INK); im.hline(6, 24, 10, INK); im.rect(21, 15, 26, 16, INK)
        for (k in 0 until 6) { val x = k * 9 + r.int(4); Pix.tri(im, x.toFloat(), 50f, x + 4f, 28f + r.int(6), x + 8f, 50f, c(0x0A2A14)) }
        Pix.figure(im, 32f, 50f, 34f, c(0x0A0A0A), pose = 0)
        im.rect(28, 21, 36, 22, col(f, 2, c(0xE83A3A)))
    }

    // ------------------------------------------------------------------ 1990s

    /** The romantic comedy: two people back to back on white, grinning, a city's skyline in a heart's colour. */
    fun romcom(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(c(0xF8F6F0))
        val acc = col(f, 2, c(0xE85A8A)).let { if (Pix.lum(it) > .85f) c(0xE85A8A) else it }
        Pix.skyline(im, 0, W - 1, 46, 10, Pix.mix(acc, c(0xFFFFFF), .6f), r)
        val s = alt(f, 2, 16)
        val a = if (s == 0) 18f else 30f; val b = if (s == 0) 30f else 18f
        Pix.figure(im, a, 46f, 34f, c(0x2A3A6A), pose = 0); Pix.figure(im, b, 46f, 32f, acc, pose = 4)
        Pix.disc(im, a, 46f - 34f + 3f, 3f, c(0xE8B890)); Pix.disc(im, b, 46f - 32f + 3f, 3f, c(0xF0C8A0))
        Pix.ellipse(im, b, 46f - 32f + 1f, 3.5f, 2f, c(0x8A3A1A))
        im.rect(0, 47, W - 1, H - 1, c(0xF8F6F0))
    }

    /** The crime picture: a case spilling money, four in suits walking away from it. */
    fun heist(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(c(0x101410))
        Pix.bands(im, 0, 0, W - 1, 30, c(0x1A2A1A), c(0x3A5A2A), 4)
        im.rect(8, 30, 40, 44, c(0x2A1A10)); im.rect(8, 29, 40, 31, c(0x4A3A2A)); im.rect(22, 27, 26, 29, c(0xC8A050))
        for (k in 0 until 18) { val x = 4 + r.int(40); val y = 24 + r.int(22); im.rect(x, y, x + 3, y + 1, if (k % 3 == 0) c(0x8AC88A) else c(0x5AA85A)) }
        val n = 3 + alt(f, 2, 17)
        for (k in 0 until n) Pix.figure(im, 8f + k * (32f / (n - 1)), 26f, 18f, c(0x0A0A0A), pose = 0)
    }

    // ------------------------------------------------------------------ 2000s (bands bg; the title from 55)

    /** The epic: a host of spears and banners under a burning sky, a citadel on its rock. */
    fun army(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 54, c(0x2A1A10), col(f, 1, c(0xE8781A)).let { if (Pix.lum(it) < .2f) c(0xE8781A) else it }, 6)
        Pix.tri(im, 34f, 12f, 22f, 40f, 46f, 40f, c(0x1A120C)); im.rect(30, 6, 38, 16, c(0x1A120C)); im.rect(33, 2, 35, 6, c(0x1A120C)); im[34, 10] = c(0xF6C83A)
        for (row in 0 until 3) for (k in 0 until 16) {
            val x = k * 3 + row; val y = 44 + row * 4
            im.vline(x, y - 10 - r.int(3), y, c(0x0A0806)); im.rect(x - 1, y - 2, x + 1, y + 3, c(0x0A0806))
        }
        for (k in 0 until 4) { val x = 6 + k * 11 + r.int(3); im.vline(x, 26, 44, c(0x0A0806)); im.rect(x + 1, 26, x + 5, 30, col(f, 2, c(0xC82A2A))) }
    }

    /** The heist: a huge round vault door ajar, light from inside, a figure's shadow on it. */
    fun vault(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(c(0x0A0E14))
        Pix.disc(im, 24f, 28f, 20f, c(0x5A6A7A)); Pix.disc(im, 24f, 28f, 17f, c(0x8A9AAA)); Pix.ring(im, 24f, 28f, 12f, 1.5f, c(0x5A6A7A))
        for (k in 0 until 8) { val a = k * .785f; Pix.disc(im, 24 + cos(a) * 15, 28 + sin(a) * 15, 1.2f, c(0x3A4A5A)) }
        Pix.disc(im, 24f, 28f, 4f, c(0xC8D0D8)); for (k in 0 until 3) Pix.line(im, 24, 28, (24 + cos(k * 2.1f) * 9).toInt(), (28 + sin(k * 2.1f) * 9).toInt(), c(0x3A4A5A))
        Pix.tri(im, 44f, 8f, 44f, 48f, 36f, 52f, c(0xF6D890))
        Pix.figure(im, 14f, 52f, 22f, c(0x05080C), pose = 0)
    }

    /** The disaster: a tidal wave curling over a skyline, a tiny figure on a roof. */
    fun wave(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        Pix.bands(im, 0, 0, W - 1, 54, c(0x1A2A3A), c(0x5A7A8A), 5)
        Pix.skyline(im, 0, W - 1, 54, 20, c(0x101820), r, lit = c(0xF6E8A0))
        val crest = 8 + alt(f, 6, 18)
        for (x in 0 until W) { val top = crest + (x * x) / 70; for (y in top until 54) if (y < top + 40) im[x, y] = if ((x + y) % 5 == 0) c(0x8AC8E8) else c(0x2A6A8A) }
        for (x in 0 until W step 2) im[x, crest + (x * x) / 70] = c(0xF0F8FF)
        Pix.figure(im, 40f, 34f, 6f, c(0x05080C), pose = 1)
    }

    // ------------------------------------------------------------------ 2010s (dark bands; the title from 57)

    /** The space picture: a helmet's visor filling the sheet, a planet and a tiny ship mirrored in it. */
    fun astronaut(im: PixImg, f: CinemaFilm, r: Paint.Rnd) {
        im.fill(c(0x05060A))
        for (k in 0 until 40) im[r.int(W), r.int(56)] = c(0xFFFFFF)
        Pix.disc(im, 24f, 28f, 21f, c(0xD8D8E0)); Pix.disc(im, 24f, 28f, 18f, c(0x9A9AA8))
        Pix.ellipse(im, 24f, 27f, 15f, 12f, c(0x0A1A2A))
        val pc = col(f, 1, c(0xE8781A)).let { if (Pix.lum(it) < .2f) c(0xE8781A) else it }
        val side = if (alt(f, 2, 19) == 0) -1 else 1
        Pix.disc(im, 24f + side * 5, 30f, 6f, pc); Pix.disc(im, 24f + side * 4, 29f, 4f, Pix.mix(pc, c(0xFFFFFF), .3f))
        im[24 - side * 6, 22] = c(0xFFFFFF); im[24 - side * 7, 22] = c(0x8AC8FF)
        Pix.line(im, 13, 20, 17, 17, c(0xFFFFFF))
        im.rect(14, 47, 34, 56, c(0xC8C8D0)); im.rect(20, 50, 28, 53, col(f, 2, c(0x3AC0D0)))
    }

    /** The dystopia: one girl before a towering wall, a ring of light over its gate, searchlights. */
    fun dystopia(im: PixImg, f: CinemaFilm, r: Paint.Rnd, top: Int, bot: Int, flare: Int) {
        Pix.bands(im, 0, 0, W - 1, 56, top, bot, 5)
        im.rect(0, 14, W - 1, 50, c(0x14141C)); for (x in 0 until W step 6) im.vline(x, 14, 50, c(0x1E1E28)); for (y in 18 until 50 step 8) im.hline(0, W - 1, y, c(0x1E1E28))
        im.rect(18, 26, 30, 50, c(0x0A0A10))
        Pix.ring(im, 24f, 20f, 5f, 1.4f, flare)
        for (k in 0 until 2) Pix.tri(im, 6f + k * 36f, 14f, 2f + k * 36f, 0f, 10f + k * 36f, 0f, Pix.mix(top, flare, .3f))
        Pix.figure(im, 24f + alt(f, 5, 20) - 2, 56f, 14f, c(0x05050A), pose = 0)
        im.rect(0, 50, W - 1, 56, c(0x0A0A10))
    }

    /** The 2010s action: a figure in a long coat walking from a burning car, rain. */
    fun rainWalk(im: PixImg, f: CinemaFilm, r: Paint.Rnd, top: Int, bot: Int, flare: Int) {
        Pix.bands(im, 0, 0, W - 1, 56, top, bot, 5)
        Pix.skyline(im, 0, W - 1, 40, 16, Pix.mix(top, bot, .5f), r, lit = Pix.mix(flare, top, .4f))
        im.rect(0, 40, W - 1, 56, c(0x0A0C10))
        val cx = if (alt(f, 2, 21) == 0) 34 else 10
        im.rect(cx - 7, 42, cx + 7, 47, c(0x1A1A20)); im.rect(cx - 4, 39, cx + 4, 42, c(0x1A1A20))
        for (k in 0 until 16) { val x = cx - 6 + r.int(12); val y = 28 + r.int(13); im[x, y] = if (r.next() < .5f) c(0xF6A83A) else c(0xE8501A) }
        Pix.figure(im, 24f, 54f, 26f, c(0x05060A), pose = 0)
        im.rect(19, 38, 29, 54, c(0x05060A))
        for (k in 0 until 60) { val x = r.int(W); val y = r.int(54); im[x, y] = Pix.mix(im[x, y], c(0xC8D8E8), .4f) }
    }

    // ------------------------------------------------------------------ 2020s (a flat field; small title at the foot)

    /** A 2020s horror symbol: one of antlers, a hand, an eye, a tree, a house with one lit window, a thorn crown. */
    fun symbol(im: PixImg, f: CinemaFilm, ink: Int, acc: Int, bg: Int) {
        when (alt(f, 6, 22)) {
            0 -> { // antlers
                im.rect(23, 26, 24, 40, ink)
                for (s in listOf(-1, 1)) { Pix.line(im, 24, 28, 24 + s * 10, 12, ink); Pix.line(im, 24 + s * 5, 21, 24 + s * 12, 20, ink); Pix.line(im, 24 + s * 8, 16, 24 + s * 6, 8, ink); Pix.line(im, 24 + s * 10, 12, 24 + s * 14, 10, ink) }
                Pix.disc(im, 24f, 40f, 3f, acc)
            }
            1 -> { // an open hand
                im.rect(18, 26, 30, 40, ink); for (k in 0 until 4) im.rect(18 + k * 3, 14 + (if (k == 1 || k == 2) 0 else 3), 19 + k * 3, 26, ink); Pix.line(im, 18, 30, 12, 24, ink)
                Pix.disc(im, 24f, 32f, 2.5f, acc)
            }
            2 -> { // an eye in a triangle
                Pix.tri(im, 24f, 10f, 10f, 38f, 38f, 38f, ink); Pix.tri(im, 24f, 14f, 13f, 36f, 35f, 36f, bg)
                Pix.ellipse(im, 24f, 28f, 7f, 3.5f, ink); Pix.disc(im, 24f, 28f, 2.4f, acc)
            }
            3 -> { // a bare tree, a swing
                im.rect(23, 20, 25, 42, ink); for (s in listOf(-1, 1)) { Pix.line(im, 24, 26, 24 + s * 12, 14, ink); Pix.line(im, 24, 22, 24 + s * 7, 10, ink) }
                im.vline(14, 18, 34, ink); im.vline(19, 18, 34, ink); im.hline(13, 20, 34, acc); im.hline(4, W - 5, 42, ink)
            }
            4 -> { // a house, one window lit
                im.rect(14, 26, 34, 42, ink); Pix.tri(im, 11f, 27f, 24f, 14f, 37f, 27f, ink); im.rect(27, 30, 30, 34, acc); im.hline(4, W - 5, 42, ink)
            }
            else -> { // a crown of thorns
                Pix.ring(im, 24f, 26f, 9f, 2f, ink)
                for (k in 0 until 12) { val a = k * .5236f; Pix.line(im, (24 + cos(a) * 9).toInt(), (26 + sin(a) * 9).toInt(), (24 + cos(a) * 14).toInt(), (26 + sin(a) * 14).toInt(), ink) }
                Pix.disc(im, 24f, 37f, 1.6f, acc)
            }
        }
    }

    /** A 2020s multiverse door: one door ajar, doors inside doors, a ring with a figure, or stairs to a door in the sky. */
    fun portal(im: PixImg, f: CinemaFilm, ink: Int, acc: Int, bg: Int) {
        when (alt(f, 4, 23)) {
            0 -> { val x = 14 + alt(f, 14, 31); im.rect(x, 8, x + 7, 46, acc); im.rect(x + 2, 10, x + 5, 44, bg); Pix.figure(im, x + 4f, 46f, 7f, ink); im.hline(4, W - 5, 46, Pix.mix(bg, ink, .5f)) }
            1 -> { for (k in 0 until 5) { val m = k * 3; im.rect(10 + m, 8 + m, 37 - m, 46, if (k % 2 == 0) acc else Pix.mix(bg, ink, .2f + k * .1f)) }; Pix.figure(im, 24f, 46f, 6f, ink) }
            2 -> { Pix.ring(im, 24f, 26f, 14f, 2.5f, acc); Pix.ring(im, 24f, 26f, 10f, 1f, Pix.mix(acc, bg, .5f)); Pix.figure(im, 24f, 36f, 12f, ink, pose = 1) }
            else -> { for (k in 0 until 8) im.rect(8 + k * 4, 44 - k * 4, 12 + k * 4, 46 - k * 4, ink); im.rect(37, 4, 42, 14, acc); Pix.figure(im, 18f, 34f, 6f, ink) }
        }
    }

    /** A 2020s drama portrait: a duotone face (left or right), a profile, or two faces close. */
    fun portrait(im: PixImg, f: CinemaFilm, ink: Int, acc: Int, bg: Int) {
        when (alt(f, 3, 24)) {
            0 -> {
                val cx = if (alt(f, 2, 25) == 0) 22f else 26f
                for (y in 6..50) for (x in 6..41) { val dx = (x - cx) / 15f; val dy = (y - 28f) / 20f; if (dx * dx + dy * dy < 1f) im[x, y] = if ((x + y) % 2 == 0 && x > cx + 2) ink else Pix.mix(acc, ink, ((x - 6) / 36f) * .7f) }
                im.rect((cx - 10).toInt(), 24, (cx - 4).toInt(), 25, ink); im.rect((cx + 4).toInt(), 24, (cx + 10).toInt(), 25, bg)
            }
            1 -> { // a profile facing right
                Pix.disc(im, 22f, 24f, 12f, acc); Pix.tri(im, 32f, 22f, 37f, 30f, 32f, 31f, acc); im.rect(14, 34, 28, 50, acc)
                Pix.ellipse(im, 18f, 18f, 11f, 8f, ink); im.rect(28, 25, 30, 26, ink)
            }
            else -> { // two faces close, one lit, one in shadow
                Pix.ellipse(im, 17f, 28f, 9f, 13f, acc); Pix.ellipse(im, 31f, 28f, 9f, 13f, Pix.mix(ink, bg, .3f))
                im.rect(12, 25, 15, 26, ink); im.rect(33, 25, 36, 26, bg)
            }
        }
    }

    /** The 2020s action: a figure in a slab of light, or a team of three in the light, or a running figure on a rooftop. */
    fun hero20(im: PixImg, f: CinemaFilm, ink: Int, acc: Int, bg: Int) {
        when (alt(f, 3, 26)) {
            0 -> { Pix.tri(im, 24f, 0f, 10f, 46f, 38f, 46f, Pix.mix(bg, acc, .35f)); Pix.figure(im, 24f, 46f, 16f, ink); im.hline(4, W - 5, 46, ink) }
            1 -> { Pix.tri(im, 24f, 0f, 4f, 46f, 44f, 46f, Pix.mix(bg, acc, .3f)); for (k in -1..1) Pix.figure(im, 24f + k * 9, 46f, if (k == 0) 17f else 14f, ink); im.hline(4, W - 5, 46, ink) }
            else -> {
                val s = alt(f, 2, 28); val sx = if (s == 0) 34f else 13f
                im.rect(4, 36 - alt(f, 3, 29) * 3, W - 5, 46, ink); Pix.disc(im, sx, 14f, 5f + alt(f, 4, 30), acc)
                Pix.figure(im, 48f - sx + (if (s == 0) -2f else 2f), 36f - alt(f, 3, 29) * 3, 14f, ink, pose = 5)
            }
        }
    }

    /** The 2020s biopic: a microphone on its stand, a guitar's silhouette, or a roller skate. */
    fun biopic(im: PixImg, f: CinemaFilm, ink: Int, acc: Int, bg: Int) {
        when (alt(f, 3, 27)) {
            0 -> { Pix.disc(im, 24f, 16f, 5f, ink); Pix.disc(im, 24f, 16f, 3.5f, acc); for (y in 12..20 step 2) im.hline(21, 27, y, ink); im.rect(23, 21, 24, 44, ink); im.rect(17, 44, 30, 45, ink) }
            1 -> { Pix.ellipse(im, 22f, 36f, 9f, 8f, ink); Pix.ellipse(im, 25f, 26f, 6f, 6f, ink); Pix.disc(im, 23f, 34f, 2.4f, acc); im.rect(27, 6, 29, 24, ink); im.rect(26, 4, 30, 7, ink) }
            else -> { im.rect(14, 18, 24, 34, ink); im.rect(14, 30, 34, 36, ink); Pix.disc(im, 17f, 40f, 3f, acc); Pix.disc(im, 31f, 40f, 3f, acc); im.rect(16, 20, 22, 22, acc) }
        }
    }

    private fun col(f: CinemaFilm, i: Int, default: Int): Int = f.colours.getOrNull(i) ?: default
}
