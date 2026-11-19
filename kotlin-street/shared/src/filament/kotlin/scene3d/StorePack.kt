package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder.Side
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * The pack's own store buildings as variants of ours (tools/swatches/pack_stores.py packs them): the 9_Shopping_Center
 * and Markets fronts (Market_Small 'STORE', Market_Medium / Market_Big 'MARKET', 3 colours each, the Mall) and the
 * Hardware Store, built in the store builder: the sprite's roof (seen from above) laid on a flat or pitched roof, its
 * front elevation on the front, the sides and back in the front's own wall colour at the art's texel. A town picks
 * one by its seed (`style` = "pack:<id>": src/town/stores.ts); the grounds, cars and lights are the store kind's.
 *
 * Open, the sprite is shown as drawn (its windows lit at night); closed, its glass is boarded and its sign plate blank;
 * reused, the plate carries the new use's word.
 */
internal object PackStores {
    private val pics = HashMap<String, PixImg>()

    fun ids(): List<String> = PackStoreData.ALL.map { it.id }
    fun sprite(id: String): PackStoreData.Sprite = PackStoreData.ALL.first { it.id == id }
    fun isPack(style: String) = style.startsWith("pack:") && PackStoreData.ALL.any { it.id == style.removePrefix("pack:") }

    /** The sprite's picture (a fresh copy: it is painted on for the store's state). */
    fun picture(id: String): PixImg {
        val s = sprite(id)
        val src = pics.getOrPut(id) {
            val z = Base64.getDecoder().decode(s.data.joinToString(""))
            val inf = Inflater().apply { setInput(z) }
            val buf = ByteArray(1 + 256 * 4 + s.w * s.h)
            var n = 0
            while (!inf.finished() && n < buf.size) { val k = inf.inflate(buf, n, buf.size - n); if (k == 0 && inf.needsInput()) break; n += k }
            inf.end()
            val pn = buf[0].toInt() and 255
            val pal = IntArray(pn) { i ->
                val r = buf[1 + i * 4].toInt() and 255; val g = buf[2 + i * 4].toInt() and 255; val b = buf[3 + i * 4].toInt() and 255; val a = buf[4 + i * 4].toInt() and 255
                if (a < 128) 0 else (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            PixImg(s.w, s.h).also { im -> for (i in 0 until s.w * s.h) im.px[i] = pal[buf[1 + pn * 4 + i].toInt() and 255] }
        }
        return PixImg(src.w, src.h).also { src.px.copyInto(it.px) }
    }

    /** The sprite's rows [y0] until [y1] as a picture of their own. */
    private fun rows(im: PixImg, y0: Int, y1: Int) = PixImg(im.w, y1 - y0).also { o -> for (y in y0 until y1) for (x in 0 until im.w) o[x, y - y0] = im[x, y] }

    private fun lum(c: Int) = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 4 + (c and 255)) / 8
    /** Pale blue glass: what lights up at night (the sprites draw every window this way). */
    private fun isGlass(c: Int): Boolean { val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255; return c ushr 24 == 255 && b > r + 14 && b >= g && lum(c) > 130 }

    /** The wall colour behind the front (the commonest saturated mid tone of the elevation): the sides and back wear it. */
    private fun wallColour(f: PixImg): Int {
        val count = HashMap<Int, Int>()
        for (c in f.px) { if (c ushr 24 != 255) continue; val l = lum(c); if (l in 70..200 && !isGlass(c)) count.merge(c, 1) { a, b -> a + b } }
        return count.maxByOrNull { it.value }?.key ?: Paint.c(0x9A8A7A)
    }

    private fun ramp(c: Int): Ramp {
        fun m(f: Float) = Pix.mix(c, if (f < 1f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), if (f < 1f) 1f - f else f - 1f)
        return Ramp(m(.45f), m(.72f), c, m(1.18f), m(1.4f))
    }

    /** The short word the plate carries once the store is something else. */
    private fun useWord(r: StoreReuse?): String = when (r) {
        StoreReuse.CHURCH -> "CHAPEL"; StoreReuse.GYM -> "GYM"; StoreReuse.THRIFT -> "THRIFT"; StoreReuse.FLEA -> "FLEA"; StoreReuse.OUTLET -> "OUTLET"
        StoreReuse.CAFE -> "CAFE"; StoreReuse.ANTIQUES -> "ANTIQUES"; StoreReuse.MUSEUM -> "MUSEUM"; StoreReuse.FEED -> "FEED"; StoreReuse.COMMUNITY -> "HALL"; null -> ""
    }

    fun build(b: StoreB, info: StoreInfo, id: String): StoreKit.Site {
        val s = sprite(id)
        val im = picture(id)
        val closed = info.state == StoreState.CLOSED
        val reused = info.state == StoreState.REUSED
        val mall = id == "mall"
        // the sign plate: blank when shut, the new use's word when reused (only where the art has its plate at the family's place)
        val p = s.plate
        if ((closed || reused) && p[2] > 0) {
            val base = im[p[0] + 3, p[1] + 3]
            val onPlate = (p[0] + 3..p[2] - 3).count { x -> val c = im[x, p[1] + 3]; Math.abs(lum(c) - lum(base)) < 14 } > (p[2] - p[0] - 6) * 7 / 10
            if (onPlate) {
                im.rect(p[0] + 3, p[1] + 3, p[2] - 3, p[3] - 3, base)
                if (reused) StoreSigns.letters(im, useWord(info.reuse), p[0] + 4, p[1] + 4, p[2] - 4, p[3] - 4, null, null, 2) { _, _ -> Paint.c(0xF4F0E4) }
            }
        }
        // glass: lit at night when open; boarded and dark when shut
        var anyGlass = false
        for (i in im.px.indices) {
            val c = im.px[i]
            if (!isGlass(c)) continue
            if (closed) im.px[i] = if ((i / im.w / 2 + i % im.w / 4) % 3 == 0) Paint.c(0x3A3430) else Paint.c(0x5A4C3C)
            else { anyGlass = true }
        }
        if (closed) for (i in im.px.indices) { val c = im.px[i]; if (c ushr 24 == 255 && !isGlass(c)) im.px[i] = Pix.mix(c, Paint.c(0x707070), .22f) }
        val facade = rows(im, s.roofEnd, s.bottom)
        val roof = rows(im, s.top, s.roofEnd)
        if (!closed && anyGlass) for (i in facade.px.indices) if (isGlass(facade.px[i])) { facade.glow(0, 0, 0, 0, 0); facade.mask[i] = (255 shl 24) or (170 shl 16) or 255 }
        val W = s.w * TEXEL
        val Hf = (s.bottom - s.roofEnd) * TEXEL
        val Dp = (s.roofEnd - s.top) * TEXEL * (if (mall) .62f else 1.15f)
        val L = info.lot; val D = info.deep
        // where it stands: as the kind's own building does
        val cx = when (info.kind) { StoreKind.GENERAL -> -L / 2f + 32f + W / 2f; StoreKind.SUPERMARKET -> -L / 2f + 80f + W / 2f; else -> 0f }
        val x0 = cx - W / 2f; val x1 = cx + W / 2f
        val zf = when (info.kind) { StoreKind.GENERAL -> -104f; StoreKind.MARKET -> -min(320f, D * .3f); else -> -min(640f, D * .38f) }
        val zb = zf - Dp
        val det = b.det()
        val wall = wallColour(facade)
        val sideRamp = ramp(Pix.mix(wall, Paint.c(0x000000), .12f))
        // (plain walls with a few specks, in whole art pixels: the pack draws its walls flat)
        val side = b.tile(b.pic("packSide${sideRamp.b}") { PixImg(32, 32).also { w -> w.fill(sideRamp.b); val r = Paint.Rnd(info.seed + 5); repeat(10) { val x = r.int(16) * 2; val y = r.int(16) * 2; w.rect(x, y, x + 1, y + 1, if (r.next() < .5f) sideRamp.l else sideRamp.d) } } })
        val frontM = if (closed) b.det("front") else b.lit("front")
        StoreKit.front(frontM, facade, x0, x1, 0f, Hf, zf)
        if (!mall) {
            side.box(x0, 0f, zb, x1, Hf, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
            // the roof from above: the art's own picture, laid on the flat top
            val m = b.det("roof")
            val rt = MeshBuilder.Companion::v
            m.quad(rt(x0, Hf, zb), rt(x1, Hf, zb), rt(x1, Hf, zf), rt(x0, Hf, zf), roof)
            if (id == "hardware" || id.startsWith("market_b")) StoreMarkets.roofPlant(b, x0 + 20f, x1 - 20f, zb + 24f, zf - 40f, Hf, info.seed, 120f, 100f, false)
        } else {
            // the mall: a long glazed front under a low pitched roof (the sprite's two slopes from above), gable ends in the wall
            val rise = 34f; val ry = Hf + rise
            val zr = zb + (zf - zb) * .38f   // the ridge, nearer the back
            side.box(x0, 0f, zb, x1, Hf, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
            val far = rows(roof, 0, ((s.roofEnd - s.top) * .38f).toInt().let { it - it % 2 })
            val near = rows(roof, far.h, roof.h)
            val v = MeshBuilder.Companion::v
            val m = b.det("roof")
            m.quad(v(x0 - 4f, Hf - 2f, zb - 3f), v(x1 + 4f, Hf - 2f, zb - 3f), v(x1 + 4f, ry, zr), v(x0 - 4f, ry, zr), far)
            m.quad(v(x0 - 4f, ry, zr), v(x1 + 4f, ry, zr), v(x1 + 4f, Hf - 2f, zf + 4f), v(x0 - 4f, Hf - 2f, zf + 4f), near)
            StoreMarkets.roofPlant(b, x0 + 40f, x1 - 40f, zb + 30f, zr - 20f, Hf + 2f, info.seed, 150f, 100f, false)
        }
        // a lit sign wash on the front at night, as the other stores have
        if (!closed && info.kind != StoreKind.GENERAL) b.lights += CinemaLight(cx, Hf * .7f, zf + 60f, 1f, .92f, .75f, 5200f, 260f, true, 2.2f)
        return StoreKit.Site(x0, x1, zf, zb, emptyList())
    }
}
