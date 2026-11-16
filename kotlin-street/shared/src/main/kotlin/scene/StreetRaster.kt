package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.FigBuilder
import com.lifemax.rpg.feature.habits.domain.Mat
import com.lifemax.rpg.feature.habits.domain.Shape
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The street's people in their own colours, painted as solid forms. Each run of [Shape]s of one
 * colour and stuff is filled anti-aliased and given a surface: its distance in from its own outline
 * against how thick it is there makes it round, as a limb, a torso or a head is (flat towards us
 * along its middle, turning away to its edges), so each form takes the light as a body would. The
 * key light comes from above and a little in front, wrapping softly past the terminator; the sky
 * fills the shadow side cool and the pavement bounces a little warmth up into the under side; a
 * thin cool rim catches the back edge. Each stuff answers in its own way ([Mat]): skin warms in its
 * shadow, hair has its strands and a sheen across them, leather and metal a sharp highlight, nylon
 * a broad one, wool a soft sheen at its turning edges, denim its twill, tweed its flecks, a knit
 * its ribs. Every form throws a soft shadow on what it lies over (a coat's hem on the legs, a
 * collar on the neck, a hat's brim and the hair on the face), and the flat details (seams, folds,
 * an eye) take the light of the form they are on. No glow round the figure: outdoors the scene's
 * light does the rest ([StreetLifePainter] grades them for the hour). Pure Kotlin, any JVM.
 */
internal object StreetRaster {
    private const val SS = PeopleRaster.SS
    private const val PAD = 2

    /** The key light: from above, a little in front (+x, the way the figure faces), towards us. */
    private val LX: Float
    private val LY: Float
    private val LZ: Float

    /** The half vector between the light and the eye, for the highlights. */
    private val HX: Float
    private val HY: Float
    private val HZ: Float

    init {
        val l = sqrt(.16f * .16f + .74f * .74f + .66f * .66f)
        LX = .16f / l
        LY = -.74f / l
        LZ = .66f / l
        val hl = sqrt(LX * LX + LY * LY + (LZ + 1f) * (LZ + 1f))
        HX = LX / hl
        HY = LY / hl
        HZ = (LZ + 1f) / hl
    }

    // By material ([Mat]): how far the light wraps past the terminator, the sky's fill, the key's
    // strength, the highlight's strength and tightness, the sheen at the turning edges, the rim's
    // strength, the texture's strength, and the most a form of it is rounded (units: a bag is
    // flat with rounded edges, a limb round all through)
    private val WRAP =
        floatArrayOf(.38f, .5f, .3f, .22f, .15f, .25f, 0f, .36f, .42f, .26f, .45f, .4f)
    private val AMB =
        floatArrayOf(.46f, .48f, .44f, .42f, .36f, .44f, 1f, .46f, .47f, .44f, .47f, .46f)
    private val DIF = floatArrayOf(.7f, .66f, .7f, .7f, .7f, .62f, 0f, .7f, .68f, .7f, .66f, .7f)
    private val KS = floatArrayOf(.03f, .07f, .16f, .32f, .9f, .1f, 0f, .02f, 0f, .2f, 0f, 0f)
    private val SP = floatArrayOf(6f, 18f, 9f, 34f, 60f, 14f, 1f, 6f, 1f, 7f, 1f, 1f)
    private val SHEEN = floatArrayOf(.06f, 0f, 0f, 0f, 0f, 0f, 0f, .05f, .14f, .04f, .12f, .1f)
    private val RIM = floatArrayOf(.16f, .16f, .2f, .18f, .2f, .12f, 0f, .16f, .2f, .18f, .2f, .18f)
    private val TEX =
        floatArrayOf(.025f, .012f, .11f, .02f, 0f, .02f, 0f, .07f, .035f, .015f, .09f, .1f)
    private val ROUND = floatArrayOf(9f, 9f, 9f, .5f, 9f, 9f, 9f, 9f, 9f, 9f, 9f, 9f)

    /** A shape with no colour of its own: a warm grey by its tone. */
    private fun fallback(tone: Float): Int {
        val v = (40f + 150f * tone.coerceIn(0f, 1f)).toInt()
        return (v shl 16) or ((v * .94f).toInt() shl 8) or (v * .88f).toInt()
    }

    /** The picture being painted: straight colour, coverage, what lies where, how lit. */
    private class Canvas(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val a = FloatArray(w * h)
        val base = IntArray(w * h) { -1 }
        val lit = FloatArray(w * h) { 1f }
    }

    /** Bakes [shapes] (units) at [scale] pixels a unit, the origin at the units' 0, 0. */
    fun bake(shapes: List<Shape>, scale: Float): Sprite {
        val bd = FigBuilder.bounds(shapes)
        val pad = PAD + shadowReach(scale)
        val x0 = floor(bd[0] * scale).toInt() - pad
        val y0 = floor(bd[1] * scale).toInt() - pad
        val x1 = ceil(bd[2] * scale).toInt() + pad
        val y1 = ceil(bd[3] * scale).toInt() + pad
        val w = x1 - x0
        val h = y1 - y0
        val ox = -x0.toFloat()
        val oy = -y0.toFloat()
        val c = Canvas(w, h)
        // runs of shapes of one colour and stuff (a leg's thigh and shin, a coat's panels) are
        // one form, so no seam shows where they join
        var from = 0
        while (from < shapes.size) {
            val s = shapes[from]
            var to = from + 1
            while (
                to < shapes.size &&
                    s.mat != Mat.FLAT &&
                    shapes[to].rgb == s.rgb &&
                    shapes[to].mat == s.mat &&
                    shapes[to].alpha == s.alpha
            ) to++
            group(shapes.subList(from, to), scale, ox, oy, c)
            from = to
        }
        // a little more light at the head than at the feet: the sky above, the pavement below
        var top = h
        var bottom = 0
        for (y in 0 until h) for (x in 0 until w) if (c.a[y * w + x] > .05f) {
            top = min(top, y)
            bottom = max(bottom, y)
        }
        val span = max(1, bottom - top).toFloat()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val up = 1f - ((y - top) / span).coerceIn(0f, 1f)
            val k = .95f + .07f * up
            for (x in 0 until w) {
                val i = y * w + x
                if (c.a[i] <= .004f) continue
                out[i] = PeopleRaster.argb(c.a[i], c.r[i] * k, c.g[i] * k, c.b[i] * k)
            }
        }
        return PeopleSprites.crop(Sprite(w, h, scale, ox, oy, out))
    }

    /** How far a form's shadow reaches past it, pixels. */
    private fun shadowReach(scale: Float): Int = ceil(.42f * scale).toInt() + 2

    private fun group(group: List<Shape>, scale: Float, ox: Float, oy: Float, c: Canvas) {
        val w = c.w
        val h = c.h
        var minX = 1e9f
        var minY = 1e9f
        var maxX = -1e9f
        var maxY = -1e9f
        for (q in group) {
            var i = 0
            while (i < q.pts.size) {
                minX = min(minX, q.pts[i] * scale + ox)
                maxX = max(maxX, q.pts[i] * scale + ox)
                minY = min(minY, q.pts[i + 1] * scale + oy)
                maxY = max(maxY, q.pts[i + 1] * scale + oy)
                i += 2
            }
        }
        val s = group[0]
        val reach = shadowReach(scale)
        val bx0 = max(0, floor(minX).toInt() - reach)
        val by0 = max(0, floor(minY).toInt() - reach)
        val bx1 = min(w, ceil(maxX).toInt() + reach)
        val by1 = min(h, ceil(maxY).toInt() + reach)
        val bw = bx1 - bx0
        val bh = by1 - by0
        if (bw <= 0 || bh <= 0) return
        val plane = PeopleRaster.Planes(bw * SS, bh * SS)
        for (q in group) fill(plane, q.pts, ox - bx0, oy - by0, scale)
        val n = bw * bh
        val cov = FloatArray(n)
        val inv = 1f / (SS * SS)
        for (y in 0 until bh) for (x in 0 until bw) {
            var sa = 0f
            for (j in 0 until SS) for (k in 0 until SS) sa +=
                plane.alpha[(y * SS + j) * plane.w + x * SS + k]
            cov[y * bw + x] = sa * inv
        }
        val rgb = if (s.rgb >= 0) s.rgb else fallback(s.tone)
        val cr = (rgb shr 16 and 255).toFloat()
        val cg = (rgb shr 8 and 255).toFloat()
        val cb = (rgb and 255).toFloat()
        val mat = s.mat.coerceIn(0, WRAP.size - 1)
        if (mat == Mat.FLAT) {
            flat(cov, bw, bh, bx0, by0, cr, cg, cb, s.alpha, c)
            return
        }

        // ---- the soft shadow this form throws on what is already there: down and a little back
        // from it, as the light falls
        val sr = max(1, (.26f * scale + .5f).toInt())
        val soft = PeopleRaster.blur(PeopleRaster.blur(cov, bw, bh, sr), bw, bh, max(1, sr / 2))
        val sdx = -(.06f * scale + .5f).toInt()
        val sdy = (.2f * scale + .5f).toInt()
        for (y in 0 until bh) for (x in 0 until bw) {
            val gi = (y + by0) * w + x + bx0
            if (c.a[gi] <= 0f || c.base[gi] == rgb) continue
            val qx = x + sdx
            val qy = y - sdy
            val m = if (qx in 0 until bw && qy in 0 until bh) soft[qy * bw + qx] else 0f
            // and a close, darker contact where the two meet (an occlusion, not a light's shadow)
            val shade = (m * .4f + soft[y * bw + x] * .18f) * s.alpha
            if (shade <= .004f) continue
            val k = 1f - shade.coerceAtMost(.6f)
            c.r[gi] *= k
            c.g[gi] *= k
            c.b[gi] *= k * .98f + .02f
            c.lit[gi] *= k
        }

        // ---- the form: how far in from its outline each pixel is, against how thick it is there
        val dist = inside(plane, bw, bh)
        var most = 0f
        for (v in dist) most = max(most, v)
        val roundCap = ROUND[mat] * scale
        val win = min(ceil(most * .75f).toInt(), (2.2f * scale).toInt()).coerceAtLeast(1)
        val thick = maxFilter(dist, bw, bh, win)
        val tex = TEX[mat]
        for (y in 0 until bh) for (x in 0 until bw) {
            val li = y * bw + x
            val cv = cov[li] * s.alpha
            if (cv <= 0f) continue
            val gi = (y + by0) * w + x + bx0
            val d = dist[li]
            val rad = min(max(thick[li], 1f), roundCap)
            val gx = (if (x + 1 < bw) dist[li + 1] else 0f) - (if (x > 0) dist[li - 1] else 0f)
            val gy = (if (y + 1 < bh) dist[li + bw] else 0f) - (if (y > 0) dist[li - bw] else 0f)
            val gl = sqrt(gx * gx + gy * gy)
            // the outward direction in the picture, and how far round towards it the surface has
            // turned (0 facing us along the form's middle, 1 at its edge)
            val ox2 = if (gl > 1e-4f) -gx / gl else 0f
            val oy2 = if (gl > 1e-4f) -gy / gl else 0f
            // (a pixel the edge cuts through is still mostly turned away)
            val t0 = (1f - (d + (1f - cov[li]) * -.5f) / rad).coerceIn(0f, 1f)
            val t = t0 * t0 * (.35f + .65f * t0) + t0 * .35f * (1f - t0)
            val nx = ox2 * t
            val ny = oy2 * t
            val nz = sqrt(max(0f, 1f - t * t))
            val up = (-ny * .5f + .5f)
            val ndl = nx * LX + ny * LY + nz * LZ
            val wrap = WRAP[mat]
            val diff = ((ndl + wrap) / (1f + wrap)).coerceIn(0f, 1f)
            var kr = AMB[mat] * (.74f + .4f * up) + DIF[mat] * diff
            var kg = kr
            var kb = kr
            // the sky's cool on what faces up and away from the key, the pavement's warmth under
            val shadow = 1f - diff
            kr *= 1f - .04f * shadow * up
            kb *= 1f + .07f * shadow * up
            val under = max(0f, ny) * .07f
            kr += under
            kg += under * .85f
            kb += under * .6f
            if (mat == Mat.SKIN) {
                // light through the skin: its shadow warm and red, never grey
                val sss = shadow * (1f - shadow) * 4f
                kr *= 1f + .1f * sss
                kg *= 1f - .03f * sss
                kb *= 1f - .08f * sss - .05f * shadow
            }
            // the stuff's grain
            var grain = 0f
            if (tex > 0f) {
                val px = (x + bx0).toFloat()
                val py = (y + by0).toFloat()
                val u = px / scale * 7.2f
                val v = py / scale * 7.2f
                grain =
                    when (mat) {
                        Mat.DENIM -> (sin((u + v) * 2.6f) * .6f + noise(u * 2f, v * 2f) - .5f) * tex
                        Mat.KNIT -> (sin(u * 2.2f) * .7f + (noise(u, v * 3f) - .5f) * .6f) * tex
                        Mat.TWEED -> {
                            val f = noise(u * 2.3f, v * 2.3f)
                            ((if (f > .78f) 1.3f else if (f < .2f) -1f else 0f) +
                                (noise(u * .7f, v * .7f) - .5f)) * tex
                        }
                        Mat.HAIR -> {
                            // fine strands combed back and down, gathered into locks
                            val across = (px * .8f + py * .6f) / scale
                            val along = (-px * .6f + py * .8f) / scale
                            ((noise(across * 9f, along * 1.2f) - .5f) * 1.4f +
                                (noise(across * 2.5f, along * .5f) - .5f) * .8f) * tex
                        }
                        else -> (noise(u, v) - .5f) * 2f * tex
                    }
            }
            kr *= 1f + grain
            kg *= 1f + grain
            kb *= 1f + grain
            // the highlight (a band across the strands for hair), the sheen at the turning edge,
            // the cool rim on the back edge
            var spec = 0f
            if (KS[mat] > 0f) {
                val ndh = max(0f, nx * HX + ny * HY + nz * HZ)
                spec = KS[mat] * ndh.pow(SP[mat]) * 255f
                if (mat == Mat.HAIR) spec *= (1f + grain * 3f).coerceAtLeast(0f)
            }
            val edge = t * t
            val sheen = SHEEN[mat] * edge * (.4f + .6f * up) * 255f
            val back = (-.82f * ox2 - .45f * oy2).coerceIn(0f, 1f)
            val rim = RIM[mat] * edge * t * back
            var fr = cr * kr + spec + sheen + rim * (70f + .5f * cr)
            var fg = cg * kg + spec + sheen + rim * (78f + .5f * cg)
            var fb = cb * kb + spec * 1.03f + sheen + rim * (92f + .5f * cb)
            if (fr < 0f) fr = 0f
            if (fg < 0f) fg = 0f
            if (fb < 0f) fb = 0f
            // over what is there
            val da = c.a[gi]
            val oa = cv + da * (1f - cv)
            c.r[gi] = (fr * cv + c.r[gi] * da * (1f - cv)) / oa
            c.g[gi] = (fg * cv + c.g[gi] * da * (1f - cv)) / oa
            c.b[gi] = (fb * cv + c.b[gi] * da * (1f - cv)) / oa
            c.a[gi] = oa
            if (cv > .5f) {
                c.base[gi] = rgb
                c.lit[gi] = (kr + kg + kb) / 3f
            }
        }
    }

    /**
     * A flat detail (a seam, a fold, an eye, a glint): its colour in the light of the form under
     * it.
     */
    private fun flat(
        cov: FloatArray,
        bw: Int,
        bh: Int,
        bx0: Int,
        by0: Int,
        cr: Float,
        cg: Float,
        cb: Float,
        alpha: Float,
        c: Canvas,
    ) {
        for (y in 0 until bh) for (x in 0 until bw) {
            val cv = cov[y * bw + x] * alpha
            if (cv <= 0f) continue
            val gi = (y + by0) * c.w + x + bx0
            val l = if (c.a[gi] > .3f) (.3f + .7f * c.lit[gi]).coerceIn(.45f, 1.12f) else 1f
            val da = c.a[gi]
            val oa = cv + da * (1f - cv)
            c.r[gi] = (cr * l * cv + c.r[gi] * da * (1f - cv)) / oa
            c.g[gi] = (cg * l * cv + c.g[gi] * da * (1f - cv)) / oa
            c.b[gi] = (cb * l * cv + c.b[gi] * da * (1f - cv)) / oa
            c.a[gi] = oa
        }
    }

    /**
     * How far each pixel of the shape in [p] is in from its outline, in pixels (0 outside): the
     * squared distance transform (Felzenszwalb) at the planes' resolution, averaged to the pixel.
     */
    private fun inside(p: PeopleRaster.Planes, bw: Int, bh: Int): FloatArray {
        val W = p.w
        val H = p.h
        val big = 1e8f
        val f = FloatArray(W * H) { if (p.alpha[it] > .5f) big else 0f }
        val n = max(W, H)
        val buf = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        for (x in 0 until W) {
            for (y in 0 until H) buf[y] = f[y * W + x]
            edt(buf, H, d, v, z)
            for (y in 0 until H) f[y * W + x] = d[y]
        }
        for (y in 0 until H) {
            for (x in 0 until W) buf[x] = f[y * W + x]
            edt(buf, W, d, v, z)
            for (x in 0 until W) f[y * W + x] = d[x]
        }
        val out = FloatArray(bw * bh)
        val inv = 1f / (SS * SS * SS)
        for (y in 0 until bh) for (x in 0 until bw) {
            var s = 0f
            for (j in 0 until SS) for (k in 0 until SS) s += sqrt(f[(y * SS + j) * W + x * SS + k])
            out[y * bw + x] = s * inv
        }
        return out
    }

    /** The one-dimensional squared distance transform of [f] (its first [n]) into [d]. */
    private fun edt(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -1e20f
        z[1] = 1e20f
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val p = v[k]
                s = ((f[q] + q * q) - (f[p] + p * p)) / (2f * (q - p))
                if (s <= z[k] && k > 0) k-- else break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e20f
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = q - v[k]
            d[q] = dq * dq + f[v[k]]
        }
    }

    /** The most of [src] within [r] pixels (a square), in two passes. */
    private fun maxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, x - r)..min(w - 1, x + r)) m = max(m, src[y * w + k])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, y - r)..min(h - 1, y + r)) m = max(m, tmp[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** Smooth value noise in 0..1 at ([x], [y]): a lattice of hashed values, eased between. */
    private fun noise(x: Float, y: Float): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /**
     * Fills [pts] (units) into [p] at [SS] times the pixels, by the non-zero rule: a limb whose
     * round ends overlap its sides is solid, and a ring wound back on itself has its hole.
     */
    private fun fill(p: PeopleRaster.Planes, pts: FloatArray, ox: Float, oy: Float, scale: Float) {
        val n = pts.size / 2
        if (n < 3) return
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var minY = 1e9f
        var maxY = -1e9f
        for (i in 0 until n) {
            xs[i] = (pts[i * 2] * scale + ox) * SS
            ys[i] = (pts[i * 2 + 1] * scale + oy) * SS
            minY = min(minY, ys[i])
            maxY = max(maxY, ys[i])
        }
        val y0 = max(0, ceil(minY - .5f).toInt())
        val y1 = min(p.h - 1, floor(maxY - .5f).toInt())
        val cut = FloatArray(n)
        val dir = IntArray(n)
        for (y in y0..y1) {
            val yc = y + .5f
            var c = 0
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val ya = ys[i]
                val yb = ys[j]
                if ((ya <= yc && yb > yc) || (yb <= yc && ya > yc)) {
                    cut[c] = xs[i] + (yc - ya) / (yb - ya) * (xs[j] - xs[i])
                    dir[c] = if (yb > ya) 1 else -1
                    c++
                }
            }
            for (k in 1 until c) {
                val v = cut[k]
                val d = dir[k]
                var m = k - 1
                while (m >= 0 && cut[m] > v) {
                    cut[m + 1] = cut[m]
                    dir[m + 1] = dir[m]
                    m--
                }
                cut[m + 1] = v
                dir[m + 1] = d
            }
            var wind = 0
            for (k in 0 until c - 1) {
                wind += dir[k]
                if (wind == 0) continue
                val xa = max(0, ceil(cut[k] - .5f).toInt())
                val xb = min(p.w - 1, floor(cut[k + 1] - .5f).toInt())
                for (x in xa..xb) p.alpha[y * p.w + x] = 1f
            }
        }
    }
}
