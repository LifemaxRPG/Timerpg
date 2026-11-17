package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.MoonPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One square layer of the sun or the moon: [size] pixels a side, [pxPerDp] of them to a dp, centred
 * on the body's middle. The sun's layers and the moon's halo are white at every alpha so the hour
 * tints them; the moon's picture carries its light in its colour channels
 * ([SunMoonSprites.moonPicture]).
 */
internal class SkyLayer(val size: Int, val pxPerDp: Float, val pixels: IntArray) {
    /** What the layer comes to in dp. */
    val dp: Float
        get() = size / pxPerDp

    val bytes: Int
        get() = size * size * 4
}

/**
 * The sun, painted once as light rather than as a thing: by day an overexposed [core] blooming
 * through a [bloom] three to four times as wide into the [sky] the sun lights round it (ten times
 * that), with a few long soft [rays]; low in the sky the eye sees its body, the [disc] (soft-edged,
 * flattened by refraction) and the [discLight] over it (its middle brighter than its limb). Every
 * profile falls smoothly from the middle with no edge, rim or ring in it, and all of them are
 * dithered so the long gradients never band.
 */
internal class SunSprite(
    val core: SkyLayer,
    val bloom: SkyLayer,
    val sky: SkyLayer,
    val rays: SkyLayer,
    val disc: SkyLayer,
    val discLight: SkyLayer,
) {
    fun bytes(): Int =
        core.bytes + bloom.bytes + sky.bytes + rays.bytes + disc.bytes + discLight.bytes
}

/**
 * The moon, painted once: its surface ([albedo], the real near side's seas, highlands and ray
 * craters, and the [height] of its craters for the relief the low light shows near the terminator),
 * [size] pixels a side at [pxPerDp], from which the picture of a phase is made
 * ([SunMoonSprites.moonPicture]); and its soft [halo], white for the hour's tint.
 */
internal class MoonSprite(
    val size: Int,
    val pxPerDp: Float,
    val albedo: FloatArray,
    val height: FloatArray,
    val halo: SkyLayer,
) {
    fun bytes(): Int = (albedo.size + height.size) * 4 + halo.bytes
}

/**
 * How the sun is drawn at a moment, layer by layer: the tint (ARGB), the alpha and the size ([grow]
 * times the layer's own). Written in place ([tints] when the sky changes, [alphas] each frame), so
 * drawing it allocates nothing. Pure Kotlin, so the look is tested and looked at off the device
 * exactly as it is drawn.
 */
internal class SunLook {
    val tint = IntArray(LAYERS)
    val alpha = FloatArray(LAYERS)
    val grow = FloatArray(LAYERS)

    /** How far the horizon's band of glow sits from the sun towards the horizon (a share). */
    var bandDrop = 0f

    /**
     * The colours, for how far into dusk the sky is ([dusk], 0 by day to 1) and its [halo] and
     * [orb] colours (ARGB).
     */
    fun tints(dusk: Float, halo: Int, orb: Int) {
        val w = Px.clamp01(dusk)
        val haloC = halo or (0xFF shl 24)
        // (warm light added over a blue sky is what whitens it, as the air near the sun is)
        tint[WIDE] = mixArgb(mixArgb(haloC, 0xFFFFC890.toInt(), .5f), 0xFFFF8A4A.toInt(), w * .55f)
        tint[BAND] = mixArgb(0xFFFFA060.toInt(), 0xFFFF6030.toInt(), w)
        tint[GLOW] = mixArgb(0xFFFFD8A8.toInt(), 0xFFFF6A30.toInt(), w)
        tint[RAYS] = mixArgb(mixArgb(haloC, WHITE, .5f), 0xFFFFB070.toInt(), w)
        // the low sun's limb a bright orange, its middle paler and brighter, the light round it
        // warmer and redder outward: never a darker rim
        tint[DISC] = mixArgb(0xFFFFC060.toInt(), 0xFFFFB050.toInt(), w)
        tint[HUG] = mixArgb(0xFFFFA050.toInt(), 0xFFFF7A3A.toInt(), w)
        // even low, the sun is far brighter than anything else: its middle burns out near white and
        // its bloom spills over the limb, so it shines rather than sitting there as a flat disc
        tint[DISC_LIGHT] = mixArgb(0xFFFFF2CC.toInt(), 0xFFFFF2C4.toInt(), w)
        tint[BLOOM] = mixArgb(mixArgb(orb, WHITE, .7f), 0xFFFFDCA4.toInt(), w)
        tint[CORE] = mixArgb(WHITE, 0xFFFFF8E6.toInt(), w)
        tint[VEIL] = mixArgb(0xFFFFFBF0.toInt(), 0xFFFFD8B0.toInt(), w)
    }

    /**
     * The alphas and sizes: [dusk] as for [tints], [up] how much of the sun is in the sky (it fades
     * into the haze after sunset and out of it before sunrise), [low] how near the horizon it
     * stands (0 high, 1 at the lowest of its arc), [cloud] the overcast's share and [mist] the
     * morning fog's.
     */
    fun alphas(dusk: Float, up: Float, low: Float, cloud: Float, mist: Float) {
        val w = Px.clamp01(dusk)
        val k = Px.clamp01(up)
        val c = Px.clamp01(cloud)
        val m = Px.clamp01(mist)
        // the low sun's body shows as the bloom gives way to it: smoothly through the dusk hour
        val body = Px.smooth(.2f, .9f, w)
        val clear = 1f - c
        // under a thick deck the disc, its core and rays go; a soft brightness stays (the veil below)
        val g = 1f - Px.smooth(.4f, .85f, c)
        alpha[WIDE] = k * Px.mix(.42f, .5f, w) * (1f + .35f * c + .5f * m)
        grow[WIDE] = Px.mix(1f, .8f, w)
        alpha[BAND] = k * w * (.2f + .3f * Px.clamp01(low)) * (1f - .5f * c)
        grow[BAND] = 1f
        bandDrop = .55f
        // the bloom's light added (its energy), then laid over (its whiteness)
        alpha[GLOW] = k * Px.mix(.2f, .38f, w) * (1f - .4f * c)
        grow[GLOW] = Px.mix(1.15f, .8f, w)
        alpha[RAYS] = k * Px.mix(.1f, .07f, w) * clear * (1f - .6f * m) * g
        grow[RAYS] = Px.mix(1f, .8f, w)
        // the light hugging the low sun's body, its own colour laid over the sky, so its limb
        // bleeds into the sky (and nothing round it is brighter than the limb)
        alpha[HUG] = k * body * .75f * (1f - .8f * c) * g
        grow[HUG] = 2.3f
        alpha[DISC] = k * body * (1f - .9f * c) * (1f - .5f * m) * g
        alpha[DISC_LIGHT] = alpha[DISC] * .95f
        grow[DISC] = 1.3f
        grow[DISC_LIGHT] = 1.3f
        alpha[BLOOM] = k * Px.mix(.92f, .55f, body) * (1f - .45f * c - .25f * m) * (.15f + .85f * g)
        grow[BLOOM] = 1f
        alpha[CORE] = k * Px.mix(1f, .88f, body) * (1f - .7f * c - .35f * m) * g
        grow[CORE] = Px.mix(1f, .85f, w)
        // over the overcast's veil: the sun a pale diffuse brightness through it
        alpha[VEIL] = k * c * Px.mix(.24f, .2f, w)
        grow[VEIL] = 2.8f
    }

    companion object {
        /** The layers in the order they are drawn: the sky's light first, the core last. */
        const val WIDE = 0
        const val BAND = 1
        const val GLOW = 2
        const val RAYS = 3
        const val HUG = 4
        const val DISC = 5
        const val DISC_LIGHT = 6
        const val BLOOM = 7
        const val CORE = 8

        /** The bloom again, over the overcast's veil. */
        const val VEIL = 9
        const val LAYERS = 10

        /**
         * The horizon's band of glow is the [WIDE] layer drawn this much wider and this much
         * shorter.
         */
        const val BAND_SX = 1.5f
        const val BAND_SY = .3f

        /** How fast the rays turn, in degrees a second. */
        const val RAY_TURN = 1.2f

        /** How far into dusk a sky is, by how [dark] it is: 0 by day to 1 from dusk on. */
        fun dusk(dark: Float): Float = Px.clamp01(dark / .55f)

        /**
         * How much of the sun is in the sky: all of it until well after sunset, fading out into the
         * haze over the last half hour of dusk (and in again at dawn), so nothing pops.
         */
        fun up(dark: Float): Float = Px.clamp01(1f - 2f * (dark - .55f) / .45f)

        /** How low the sun stands, from how far above the horizon it is (dp): 1 at its lowest. */
        fun low(aboveDp: Float): Float = Px.clamp01(1f - (aboveDp - 44f) / 160f)

        /**
         * The sun's light round it is added to the sky; its bloom, its core and its low body are
         * laid over it, so they whiten (or colour) it smoothly, rolling off as a photograph's
         * highlights do, where light added would clip to a hard edge.
         */
        fun added(layer: Int): Boolean =
            layer == WIDE || layer == BAND || layer == GLOW || layer == RAYS || layer == VEIL

        private const val WHITE = -1
    }
}

/**
 * How the moon is drawn: the colour matrix that turns its picture's channels into light (the lit
 * side's silver, the earthshine on the dark side, the sky behind it), the halo's tint and alphas.
 */
internal class MoonLook {
    /** A 4x5 colour matrix, as [androidx.compose.ui.graphics.ColorMatrix] takes it. */
    val matrix = FloatArray(20)
    var haloTint = 0

    /** How much of the moon is in the sky: it fades in after the sun has gone (and out before). */
    var body = 0f
    var halo = 0f
    var haze = 0f
    var veil = 0f

    /**
     * The colours for a moon [illumination] lit (0 new to 1 full), [warm] how low it stands (a
     * little warmer near the horizon), over a [sky] (ARGB) of that colour where it stands.
     */
    fun colours(illumination: Float, warm: Float, sky: Int, haloColour: Int) {
        val wm = Px.clamp01(warm)
        val lit = mixArgb(0xFFE4EAF4.toInt(), 0xFFF2DEC2.toInt(), wm * .8f)
        // the earthshine is brightest when the earth, seen from the moon, is full: a thin moon
        // faint ash-grey, and only on a thin moon: about a tenth of the lit side's light on a
        // tenth-lit crescent, nothing from about a quarter on (nor at new, in the sun's glare)
        val es = .1f * Px.smooth(.3f, .05f, illumination) * Px.smooth(0f, .04f, illumination)
        val earth = 0xFF8FA6D8.toInt()
        matrix.fill(0f)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            matrix[c * 5] = ((lit ushr shift) and 0xFF) / 255f
            matrix[c * 5 + 1] = ((earth ushr shift) and 0xFF) / 255f * es
            matrix[c * 5 + 4] = ((sky ushr shift) and 0xFF).toFloat()
        }
        matrix[18] = 1f
        haloTint = mixArgb(haloColour or (0xFF shl 24), 0xFFFFE2C0.toInt(), wm * .5f)
    }

    companion object {
        /** How much of the moon is in the sky, by how [dark] it is: in as the sun goes out. */
        fun up(dark: Float): Float = Px.clamp01((dark - .775f) / .1125f)

        /** How warm the moon is, by how high it stands ([high], 0 at the horizon to 1). */
        fun warm(high: Float): Float = Px.clamp01(1f - high / .5f)
    }

    /**
     * The halo's strength for the [illumination], how much of the moon is [up] ([up]), the
     * overcast's [cloud] and the [mist].
     */
    fun alphas(illumination: Float, up: Float, cloud: Float, mist: Float) {
        val l = Px.clamp01(illumination)
        val k = Px.clamp01(up)
        body = k
        halo = k * (.03f + .12f * l * l)
        haze = k * l * (.04f + .25f * Px.clamp01(mist + cloud * .5f))
        veil = k * Px.clamp01(cloud) * (.08f + .3f * l)
    }
}

/**
 * The sun and moon sprite generator: fields of light and albedo written once into ARGB, with no
 * edge that is not feathered by the field itself. Pure Kotlin; [SceneSprites] turns the layers into
 * images and the hour's colours are given when they are drawn ([SunLook], [MoonLook]).
 */
internal object SunMoonSprites {
    /** The sun's disc, in dp: by day it reads only as the size of its bloom. */
    const val SUN_R = 13f

    /** The moon's disc, in dp. */
    const val MOON_R = 11f

    /** The moon is painted at this many pixels to a dp, and this many a side. */
    const val MOON_PPD = 5f
    val MOON_SIZE = side(MOON_PPD)

    private const val SEED = 7919

    private fun side(ppd: Float): Int = (ceil((MOON_R * 2f + 2.4f) * ppd).toInt() + 1) and 1.inv()

    /**
     * Bytes the pool holds: the sun's six layers, the moon's surface (its albedo and its height)
     * and halo, and one picture of its phase.
     */
    fun bytes(): Int =
        (128 * 128 + 128 * 128 + 160 * 160 + 160 * 160 + 2 * 120 * 120) * 4 +
            MOON_SIZE * MOON_SIZE * 4 * 3 +
            128 * 128 * 4

    // ---- the sun

    /** The day sun's light at [r] dp from its middle, the sum of its three layers' profiles. */
    fun coreAt(r: Float): Float = exp(-(r / 10f).pow(2.4f)) * Px.smooth(21f, 17f, r)

    fun bloomAt(r: Float): Float = exp(-(sqrt(r * r + 9f) - 3f) / 13f) * Px.smooth(42f, 22f, r)

    fun skyAt(r: Float): Float =
        (.6f / (1f + (r / 34f) * (r / 34f)) + .4f * exp(-(r / 105f) * (r / 105f))) *
            Px.smooth(226f, 120f, r)

    /** The low sun's body is this much shorter than it is wide: the air near the horizon. */
    const val FLATTEN = .9f

    /** The low sun's disc, before [SunLook.grow]: a little under [SUN_R]. */
    const val DISC_R = 12.6f

    fun sun(): SunSprite {
        val core = layer(128, 3f, dither = true) { _, _, r, _ -> coreAt(r) }
        val bloom = layer(128, 1.5f, dither = true) { _, _, r, _ -> bloomAt(r) }
        val sky = layer(160, .35f, dither = true) { _, _, r, _ -> skyAt(r) }
        val rays =
            layer(160, 1f, dither = true) { _, _, r, th ->
                // a few long, soft, broad shafts and fainter ones between them, beginning out in
                // the bloom (where the angle turns too fast, and the core is anyway)
                var sum = 0f
                for (k in 0 until 11) {
                    val strong = k < 5
                    val n = if (strong) 5 else 6
                    val j = if (strong) k else k - 5
                    val a =
                        j * (2f * PI.toFloat() / n) +
                            (if (strong) .3f else .9f) +
                            (Noise.value(k * 1.7f, 0f, SEED) - .5f) * .6f
                    val da = wrap(th - a)
                    val width = (if (strong) .1f else .06f) + .05f * Noise.value(k * 3.1f, 1f, SEED)
                    val len = (if (strong) 62f else 42f) + 14f * Noise.value(k * 2.3f, 2f, SEED)
                    val t = Px.clamp01(r / len)
                    val along = (1f - t) * (1f - t)
                    sum += exp(-(da / width) * (da / width)) * along * (if (strong) 1f else .55f)
                }
                Px.clamp01(sum) * Px.smooth(12f, 32f, r) * Px.smooth(79f, 62f, r)
            }
        // the low sun: its body soft at the edge, flattened, its middle brighter than its limb
        val r0 = DISC_R
        val disc =
            layer(120, 4f) { x, y, _, _ ->
                val e = sqrt(x * x + (y / FLATTEN) * (y / FLATTEN))
                Px.smooth(r0 + 1.3f, r0 - 1.3f, e)
            }
        val discLight =
            layer(120, 4f) { x, y, _, _ ->
                val e = sqrt(x * x + (y / FLATTEN) * (y / FLATTEN))
                val q = min(1f, e / r0)
                val mu = sqrt(max(0f, 1f - q * q))
                Px.smooth(r0 + 1.3f, r0 - 1.3f, e) * mu.pow(.6f)
            }
        return SunSprite(core, bloom, sky, rays, disc, discLight)
    }

    // ---- the moon

    /** A sea: where it is (degrees of latitude, north positive, and longitude, east positive). */
    private class Sea(val lat: Float, val lon: Float, val rad: Float, val k: Float)

    /** A crater of the relief, and of the albedo if [bright]. */
    private class Crater(
        val lat: Float,
        val lon: Float,
        val r: Float,
        val rays: Int = 0,
        val reach: Float = 0f,
        val width: Float = .6f,
        val bright: Float = 0f,
    )

    /**
     * The seas of the near side as the eye sees them, north up, east (Crisium) on the right:
     * Imbrium, Serenitatis, Tranquillitatis, Crisium, Fecunditatis, Nectaris, Nubium, Humorum, the
     * long Oceanus Procellarum down the west, Frigoris across the north, the small bays between
     * them, and the dark floors of Plato and Grimaldi. They run into one another where they do.
     */
    private val SEAS =
        listOf(
            Sea(33f, -16f, 17f, 1f), // Imbrium
            Sea(28f, 17.5f, 10.5f, 1f), // Serenitatis
            Sea(8.5f, 31f, 11f, 1.05f), // Tranquillitatis
            Sea(3f, 22f, 7f, .9f),
            Sea(15f, 38f, 6f, .9f),
            Sea(17f, 59f, 8.5f, 1.1f), // Crisium
            Sea(-5f, 52f, 8f, .9f), // Fecunditatis
            Sea(-12f, 55f, 6f, .8f),
            Sea(-15f, 35f, 5.5f, .9f), // Nectaris
            Sea(-20f, -16f, 10f, .85f), // Nubium
            Sea(-12f, -21f, 6f, .8f), // Cognitum
            Sea(-24f, -38.6f, 6.5f, .95f), // Humorum
            Sea(38f, -52f, 9f, .85f), // Procellarum, north to south
            Sea(24f, -56f, 12f, 1f),
            Sea(10f, -55f, 13f, 1f),
            Sea(-3f, -52f, 10f, .9f),
            Sea(16f, -38f, 10f, .95f),
            Sea(-10f, -44f, 6f, .75f),
            Sea(7f, -30f, 7f, .8f), // Insularum
            Sea(56f, -38f, 5f, .75f), // Frigoris
            Sea(58f, -18f, 5f, .8f),
            Sea(57f, 2f, 5f, .8f),
            Sea(56f, 22f, 4.5f, .7f),
            Sea(54f, -55f, 4.5f, .7f), // Sinus Roris
            Sea(13f, 4f, 4f, .8f), // Vaporum
            Sea(11f, -8f, 3.5f, .75f), // Sinus Aestuum
            Sea(2f, 1f, 2.5f, .6f), // Sinus Medii
            Sea(38f, 29f, 4.5f, .65f), // Lacus Somniorum
            Sea(13f, 86f, 4.5f, .7f), // Marginis
            Sea(-1f, 87f, 5f, .7f), // Smythii
            Sea(-39f, 88f, 7f, .5f), // Australe
            Sea(51.6f, -9.4f, 1.8f, 1.3f), // Plato's floor
            Sea(-5.2f, -68.6f, 2.6f, 1.3f), // Grimaldi's floor
        )

    /**
     * The ray craters (Tycho, its rays across the southern highlands; Copernicus; Kepler; bright
     * Aristarchus; Proclus; Anaxagoras; Stevinus; Langrenus) and the large craters the low light
     * shows along the terminator.
     */
    private val CRATERS =
        listOf(
            Crater(-43.3f, -11.2f, 1.5f, 22, 60f, 1.1f, .3f), // Tycho
            Crater(9.6f, -20.1f, 1.6f, 13, 17f, .6f, .3f), // Copernicus
            Crater(8.1f, -38f, .6f, 10, 11f, .45f, .28f), // Kepler
            Crater(23.7f, -47.4f, .7f, 5, 6f, .4f, .42f), // Aristarchus
            Crater(16.1f, 46.8f, .55f, 6, 10f, .45f, .22f), // Proclus
            Crater(73.4f, -10.1f, .9f, 8, 12f, .5f, .18f), // Anaxagoras
            Crater(-32.5f, 54.2f, .6f, 6, 9f, .4f, .16f), // Stevinus
            Crater(-8.9f, 61f, 2.2f, 0, 0f, .6f, .12f), // Langrenus
            Crater(-58.4f, -14.4f, 3.7f), // Clavius
            Crater(-9.3f, -1.9f, 2.6f), // Ptolemaeus
            Crater(-13.4f, -3f, 1.9f), // Alphonsus
            Crater(-18.2f, -1.9f, 1.6f), // Arzachel
            Crater(-11.4f, 26.4f, 1.7f), // Theophilus
            Crater(-13.2f, 24f, 1.6f), // Cyrillus
            Crater(-18f, 23.6f, 1.7f), // Catharina
            Crater(-49.6f, -21.7f, 2.4f), // Longomontanus
            Crater(-50.5f, -6.2f, 2.8f), // Maginus
            Crater(14.5f, -11.3f, 1f), // Eratosthenes
            Crater(29.7f, -4f, 1.4f), // Archimedes
            Crater(50.2f, 17.4f, 1.4f), // Aristoteles
            Crater(31.8f, 29.9f, 1.6f), // Posidonius
            Crater(-25.3f, 60.4f, 3f), // Petavius
            Crater(-17.5f, -40f, 1.8f), // Gassendi
            Crater(-44.3f, -54.6f, 3.3f), // Schickard
            Crater(-41.8f, 14f, 2f), // Maurolycus
            Crater(-5.1f, 5.2f, 2.5f), // Hipparchus
            Crater(-11.2f, 4.1f, 2.2f), // Albategnius
            Crater(-33f, .7f, 2.3f), // Walter
            Crater(-33f, -5f, 3.6f), // Deslandres
            Crater(-29f, 26f, 2.4f), // Piccolomini? (Janssen's region)
            Crater(-38f, 33f, 2.6f),
        )

    fun moon(ppd: Float = MOON_PPD): MoonSprite {
        val r0 = MOON_R
        val size = side(ppd)
        val mid = (size - 1) / 2f
        val albedo = FloatArray(size * size)
        val height = FloatArray(size * size)
        val deg = (180.0 / PI).toFloat()
        val seaV = Array(SEAS.size) { unit(SEAS[it].lat, SEAS[it].lon) }
        val craterV = Array(CRATERS.size) { unit(CRATERS[it].lat, CRATERS[it].lon) }
        val p = FloatArray(3)
        for (j in 0 until size) for (i in 0 until size) {
            var u = (i - mid) / ppd / r0
            var v = (j - mid) / ppd / r0
            val rr = u * u + v * v
            if (rr > 1.1f) continue
            if (rr > .996f) {
                // past the limb: the limb's own surface, for the feathered edge to show
                val s = sqrt(.996f / rr)
                u *= s
                v *= s
            }
            val z = sqrt(max(0f, 1f - u * u - v * v))
            p[0] = u
            p[1] = -v
            p[2] = z
            val lat = asin(-v) * deg
            val lon = atan2(u, z) * deg
            // the seas: a field of them with warped, irregular shores, so neighbours run together
            val wl = (Noise.fbm(lon / 9f, lat / 9f, SEED + 10, 3) - .5f) * 8f
            val wb = (Noise.fbm(lon / 9f + 7f, lat / 9f, SEED + 11, 3) - .5f) * 8f
            var field = (Noise.fbm(lon / 15f, lat / 15f, SEED + 12, 3) - .5f) * .3f
            val cl = cos((lat + wb) / deg)
            val wx = cl * sin((lon + wl) / deg)
            val wy = sin((lat + wb) / deg)
            val wz = cl * cos((lon + wl) / deg)
            for (s in SEAS.indices) {
                val c = seaV[s]
                val d = acos((wx * c[0] + wy * c[1] + wz * c[2]).coerceIn(-1f, 1f)) * deg
                field += Px.smooth(1.5f, .65f, d / SEAS[s].rad) * SEAS[s].k
            }
            val sea = Px.smooth(.22f, .85f, field)
            // the seas' own tones, mottled; the highlands brighter and rougher
            val tone = Noise.fbm(lon / 7f, lat / 7f, SEED + 13, 3) - .5f
            val fine = Noise.fbm(lon / 2.2f, lat / 2.2f, SEED + 14, 3) - .5f
            val mare = .36f + tone * .14f + fine * .05f + Px.smooth(1.2f, 2.2f, field) * -.03f
            val high = .66f + tone * .14f + fine * .12f
            var a = Px.mix(high, mare, sea)
            var h = fine * .012f * (1f - .75f * sea) - sea * .01f
            // the small craters, more of them on the highlands: a few fresh and bright
            h += craters(lat, lon, 6f, .55f, .7f, 2.4f, SEED + 20, sea) { fresh -> a += fresh }
            h += craters(lat, lon, 2.6f, .5f, .3f, 1f, SEED + 30, sea) { fresh -> a += fresh }
            // the named craters, their relief, and the ray craters' bright floors and rays
            for (k in CRATERS.indices) {
                val c = CRATERS[k]
                val cv = craterV[k]
                val dot = (p[0] * cv[0] + p[1] * cv[1] + p[2] * cv[2]).coerceIn(-1f, 1f)
                val d = acos(dot) * deg
                val rho = d / c.r
                if (rho < 2.5f) h += relief(rho) * c.r / deg
                if (c.bright <= 0f) continue
                a += c.bright * (Px.smooth(1.15f, .5f, rho) * .7f + exp(-(rho / 3.2f).pow(2)) * .5f)
                if (c.rays == 0 || d > c.reach) continue
                // the bearing from the crater to here
                val cosLat = cos(c.lat / deg)
                val ex = cos(c.lon / deg)
                val ez = -sin(c.lon / deg)
                val sl = sin(c.lat / deg)
                val nx = -sl * sin(c.lon / deg)
                val ny = cosLat
                val nz = -sl * cos(c.lon / deg)
                val th = atan2(p[0] * ex + p[2] * ez, p[0] * nx + p[1] * ny + p[2] * nz)
                var ray = 0f
                for (q in 0 until c.rays) {
                    val ang =
                        q * (2f * PI.toFloat() / c.rays) +
                            (Noise.value(q * 2.2f, k * 3f, SEED) - .5f) * 1.4f
                    val dth = wrap(th - ang)
                    if (abs(dth) > 1.2f) continue
                    val perp = d * sin(abs(dth))
                    val w = c.width * (.6f + .8f * Noise.value(q * 1.3f, k + 5f, SEED))
                    val len = c.reach * (.45f + .55f * Noise.value(q * 1.9f, k + 7f, SEED))
                    val t = Px.clamp01(d / len)
                    val streak =
                        Px.clamp01(
                            .3f + 1.1f * Noise.fbm(d / 3f + q * 7f, q * 3.1f + k, SEED + 40, 2)
                        )
                    ray += exp(-(perp / w) * (perp / w)) * (1f - t).pow(1.4f) * streak
                }
                a += c.bright * .45f * Px.clamp01(ray) * Px.smooth(c.r * 2f, c.r * 9f, d)
            }
            albedo[j * size + i] = Px.clamp01(a)
            height[j * size + i] = h
        }
        val halo =
            layer(128, 1f, dither = true) { _, _, r, _ ->
                // flat over the disc (it is the air in front of the moon, over its dark side too),
                // falling from the limb outwards, with no edge anywhere
                val out = max(0f, r - r0)
                (exp(-out / 6f) * .55f + exp(-(out / 24f) * (out / 24f)) * .45f) *
                    Px.smooth(63f, 44f, r)
            }
        return MoonSprite(size, ppd, albedo, height, halo)
    }

    /**
     * The moon at [step] of [MoonPhase.STEPS] (0 new, a quarter of them first quarter, lit on the
     * right as seen from the north, half full), from its [m] surface: the alpha is the disc (crisp
     * at the limb), red the sunlit surface (soft at the terminator on the night side, with the
     * relief of the craters along it), green the surface as the earthshine shows it;
     * [MoonLook.matrix] turns them into light.
     */
    fun moonPicture(m: MoonSprite, step: Int): SkyLayer {
        val r0 = MOON_R
        val size = m.size
        val ppd = m.pxPerDp
        val mid = (size - 1) / 2f
        val f = step.toFloat() / MoonPhase.STEPS
        // the sun's longitude on the moon: 0 at full, 90 degrees (to the right) at first quarter
        val sunLon = (PI - f * 2.0 * PI).toFloat()
        val lx = sin(sunLon)
        val lz = cos(sunLon)
        // how a step of height over a pixel tilts the ground, in moon radii
        val step1 = 1f / (ppd * r0)
        val px = IntArray(size * size)
        for (j in 0 until size) for (i in 0 until size) {
            val x = (i - mid) / ppd
            val y = (j - mid) / ppd
            val r = sqrt(x * x + y * y)
            val cover = Px.smooth(r0 + .5f / ppd, r0 - .5f / ppd, r)
            if (cover <= 0f) continue
            val k = j * size + i
            var u = x / r0
            var v = y / r0
            val rr = u * u + v * v
            if (rr > .996f) {
                val s = sqrt(.996f / rr)
                u *= s
                v *= s
            }
            val z = sqrt(max(0f, 1f - u * u - v * v))
            val hx =
                if (i in 1 until size - 1) (m.height[k + 1] - m.height[k - 1]) / (2f * step1)
                else 0f
            val hy =
                if (j in 1 until size - 1) (m.height[k + size] - m.height[k - size]) / (2f * step1)
                else 0f
            // the ground's normal, tilted by its slopes (less so towards the limb, where the
            // slopes are foreshortened)
            var nx = u - hx * 1.2f * z
            var ny = -v + hy * 1.2f * z
            var nz = z
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= nl
            ny /= nl
            nz /= nl
            // Lommel-Seeliger, as the moon's dust reflects: as bright at the limb of a full moon
            // as in its middle, dimming towards the terminator; the light runs on a little past
            // it onto the night side, raggedly, as it catches the peaks
            val ragged = (Noise.fbm(u * 9f, v * 9f, SEED + 50, 2) - .5f) * .02f
            val mu0 = nx * lx + nz * lz + ragged
            // the sunlit ground is as bright at every phase (a crescent's limb as bright as the
            // full moon), soft only across the terminator; the relief shows near it
            val shade = if (mu0 <= 0f) 0f else min(1f, 2f * mu0 / (mu0 + max(nz, .02f)))
            val lit = Px.smooth(-.09f, .05f, mu0) * (.8f + .2f * shade)
            val a = m.albedo[k]
            val light = Px.clamp01(a * lit * (.86f + .14f * z) * 1.04f)
            // the earthshine's surface: only the faintest hint of the seas in it
            px[k] = Px.argbF(cover, light, Px.mix(.55f, a, .4f), 0f)
        }
        return SkyLayer(size, ppd, px)
    }

    // ---- the brush

    /** A crater's height at [rho] of its radius out: a bowl, a raised rim, its ejecta fading. */
    private fun relief(rho: Float): Float {
        val bowl = if (rho < 1f) (rho * rho - 1f) * .22f else 0f
        val e = (rho - 1f) / .22f
        return bowl + exp(-e * e) * .07f + (if (rho > 1f) exp(-(rho - 1f) * 2.2f) * .02f else 0f)
    }

    /**
     * The scattered craters of one size (a grid of [cell] degrees, one crater in [chance] of the
     * cells, [rMin] to [rMax] degrees across): their height, and through [fresh] the brightness of
     * the few fresh ones. Fewer on the seas.
     */
    private inline fun craters(
        lat: Float,
        lon: Float,
        cell: Float,
        chance: Float,
        rMin: Float,
        rMax: Float,
        seed: Int,
        sea: Float,
        fresh: (Float) -> Unit,
    ): Float {
        var h = 0f
        val ci = floor(lon / cell).toInt()
        val cj = floor(lat / cell).toInt()
        val deg = (180.0 / PI).toFloat()
        val cl = cos(lat / deg)
        for (dj in -1..1) for (di in -1..1) {
            val gi = ci + di
            val gj = cj + dj
            val hit = Noise.value(gi * 1.618f + .5f, gj * 1.618f + .5f, seed)
            if (hit > chance * (1f - .7f * sea)) continue
            val ox = (gi + Noise.value(gi + .5f, gj + .5f, seed + 1)) * cell
            val oy = (gj + Noise.value(gi + .5f, gj + .5f, seed + 2)) * cell
            val rc = rMin + (rMax - rMin) * Noise.value(gi + .5f, gj + .5f, seed + 3).pow(2)
            val dx = (lon - ox) * cl
            val dy = lat - oy
            val rho = sqrt(dx * dx + dy * dy) / rc
            if (rho > 2.5f) continue
            h += relief(rho) * rc / deg
            val f = Noise.value(gi + .5f, gj + .5f, seed + 4)
            if (f > .86f) {
                val e = (rho - 1f) / .35f
                fresh((exp(-e * e) * .16f + exp(-rho * rho / 4f) * .05f) * (f - .86f) / .14f)
            }
        }
        return h
    }

    private fun unit(lat: Float, lon: Float): FloatArray {
        val deg = (180.0 / PI).toFloat()
        val c = cos(lat / deg)
        return floatArrayOf(c * sin(lon / deg), sin(lat / deg), c * cos(lon / deg))
    }

    /** An angle brought into -pi..pi. */
    private fun wrap(a: Float): Float {
        val tau = 2f * PI.toFloat()
        return ((a % tau) + tau + PI.toFloat()) % tau - PI.toFloat()
    }

    /**
     * A white layer whose alpha is [f] of the point's place from the middle (in dp: across, down,
     * the distance and the angle), feathered to nothing at the border; [dither]ed, for the long
     * soft gradients, by a fine noise under one level so they never step into bands.
     */
    private inline fun layer(
        size: Int,
        pxPerDp: Float,
        dither: Boolean = false,
        f: (x: Float, y: Float, r: Float, theta: Float) -> Float,
    ): SkyLayer {
        val px = IntArray(size * size)
        val mid = (size - 1) / 2f
        for (j in 0 until size) for (i in 0 until size) {
            val x = (i - mid) / pxPerDp
            val y = (j - mid) / pxPerDp
            val border = min(min(i, size - 1 - i), min(j, size - 1 - j)).toFloat()
            var a =
                Px.clamp01(f(x, y, sqrt(x * x + y * y), atan2(y, x))) * Px.smooth(0f, 4f, border)
            if (a <= 0f) continue
            if (dither) a = Px.clamp01(a + (hash(i, j) - .5f) / 255f)
            px[j * size + i] = Px.argbF(a, 1f, 1f, 1f)
        }
        return SkyLayer(size, pxPerDp, px)
    }

    /** A fixed pseudo-random number, 0 to 1, for a pixel. */
    private fun hash(i: Int, j: Int): Float {
        var n = i * 73856093 xor j * 19349663 xor SEED
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65536f
    }
}

/** Two ARGB colours mixed, [t] of the way from [a] to [b]. */
internal fun mixArgb(a: Int, b: Int, t: Float): Int {
    fun ch(c: Int, s: Int) = (c ushr s) and 0xFF
    fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t + .5f).toInt().coerceIn(0, 255)
    return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
}
