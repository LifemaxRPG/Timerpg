package com.lifemax.rpg.feature.habits.domain

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The street's own life, with no drawing in it: what the sky and the far city do at any moment. Everything is
 * a function of the seconds the tab has been showing and a seed, so a moment is the same every time it is asked
 * for (a test, a screenshot) and nothing has to be remembered between frames. Nothing here makes an object per
 * frame: a pass through the sky is written into a [Pass] the painter keeps.
 *
 * Positions are shares: x of the view's width (a little past either edge while something enters or leaves), y of
 * the sky's band, 0 under the heading to 1 at the horizon.
 */

/** Something passing through the sky this moment, written by a schedule. */
class Pass {
    /** Whether it is in the sky now. */
    var on = false
    var x = 0f
    var y = 0f

    /** 1 flying right, -1 left. */
    var dir = 1f

    /** How far through its pass, 0 to 1. */
    var t = 0f

    /** Which pass this is (a different one each period), for its own small differences. */
    var pass = 0
}

/** A star: across and down the sky (0 to 1), its radius in dp, its brightness, its own twinkle. */
data class Star(
    val x: Float,
    val y: Float,
    val r: Float,
    val alpha: Float,
    val phase: Float,
    val speed: Float,
)

/** The shapes a cloud takes. */
enum class CloudKind {
    CUMULUS,
    STRATUS,
    PUFF,
    CIRRUS,
}

/**
 * A cloud of the day's sky: where it starts across the view ([x0], a share of the width), which
 * [lane] of the sky it drifts in ([y], a share of the sky's band; every cloud has a lane of its
 * own, so none sits on another and all stay clear of the far towers), its [scale], how far it is
 * ([depth], 0 far to 1 near: the near and the big drift faster), its drift ([speed], shares of the
 * width a second), which [variant] of its kind's sprites it is painted from, and its [haze] (0 near
 * to 1 far: the far ones are smaller, bluer and fainter, the way the air thickens towards the
 * horizon). Its picture is a pre-made sprite; nothing about its shape lives here.
 */
class Cloud(
    val kind: CloudKind,
    val lane: Int,
    val x0: Float,
    val y: Float,
    val scale: Float,
    val depth: Float,
    val speed: Float,
    val variant: Int,
    val haze: Float,
)

/** One building of a far skyline: from [x], [w] wide and [h] tall, in dp. */
data class Building(val x: Float, val w: Float, val h: Float)

object Ambient {
    /** The far city repeats every this many dp, so the street can be scrolled as far as it goes. */
    const val SKYLINE_SPAN = 960f

    /** How fast people in the windows move: steps a second. */
    const val PEOPLE_FPS = 12f

    const val SHOOT_PERIOD = 37f
    const val SHOOT_SECONDS = 1.6f
    const val PLANE_PERIOD = 110f
    const val PLANE_SECONDS = 46f
    const val BIRD_PERIOD = 70f
    const val BIRD_SECONDS = 19f
    const val BLIMP_PERIOD = 210f
    const val BLIMP_SECONDS = 110f
    const val CAR_PERIOD = 13f
    const val CAR_SECONDS = 8f

    /** The birds of a flock. */
    const val FLOCK = 7

    /** A number from 0 up to but not 1 for ([a], [b], [c]), the same every time. */
    fun hash(a: Int, b: Int = 0, c: Int = 0): Float {
        var h = a * 374761393 + b * 668265263 + c * -1028477379
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h.toLong() and 0xFFFFFFL).toFloat() / 16777216f
    }

    /** [count] stars, the same every time for [seed]: each twinkles at its own pace. */
    fun stars(count: Int, seed: Int = 7): List<Star> =
        List(count) { i ->
            val big = hash(seed, i, 9) < .14f
            Star(
                hash(seed, i, 1),
                hash(seed, i, 2) * .92f,
                if (big) 1.3f else .7f + hash(seed, i, 3) * .3f,
                .35f + hash(seed, i, 4) * .6f,
                hash(seed, i, 5) * 2f * PI.toFloat(),
                .7f + hash(seed, i, 6) * 1.9f,
            )
        }

    /** How bright [star] is at [seconds]: between .4 and 1 of its own brightness. */
    fun twinkle(star: Star, seconds: Float): Float =
        .7f + .3f * sin(seconds * star.speed + star.phase)

    /**
     * A row of buildings from [from] to [to], the same for the same [seed]: each 12 to 31 wide and
     * between [lo] and [hi] tall.
     */
    fun skyline(seed: Int, lo: Int, hi: Int, from: Float, to: Float): List<Building> {
        var r = seed
        var x = from
        val out = ArrayList<Building>()
        while (x < to) {
            r = (r * 73 + 19) % 997
            val w = 12 + r % 20
            r = (r * 73 + 19) % 997
            out += Building(x, w.toFloat(), (lo + r % (hi - lo)).toFloat())
            x += w
        }
        return out
    }

    /** Where a skyline building starting at [x] is drawn while the row has slid by [slide]. */
    fun wrap(x: Float, slide: Float): Float {
        val v = (x - slide) % SKYLINE_SPAN
        return if (v < -SKYLINE_SPAN / 2f) v + SKYLINE_SPAN else v
    }

    /**
     * Whether window [w] of far building [b] has its light on at [seconds]: each switches on or off
     * on its own slow clock (half a minute to a minute and a half), and about [share] of them are
     * on at any time.
     */
    fun farWindowLit(b: Int, w: Int, seconds: Float, share: Float): Boolean {
        val period = 30f + 60f * hash(b, w, 11)
        val k = floor((seconds + period * hash(b, w, 12)) / period).toInt()
        return hash(b * 31 + w, k, 13) < share
    }

    /**
     * An aircraft-warning light at [seconds]: on for a moment every 1.6 s, each its own [index] out
     * of step with the others. 0 off to 1 on, with a short fade.
     */
    fun warning(index: Int, seconds: Float): Float {
        val period = 1.6f
        val at = ((seconds + index * .53f) % period + period) % period
        return when {
            at < .08f -> at / .08f
            at < .3f -> 1f
            at < .42f -> 1f - (at - .3f) / .12f
            else -> 0f
        }
    }

    /**
     * A pass of [period] seconds' schedule: in some periods (a [chance] of them) something crosses
     * for [seconds] at a time of its own within the period. Writes [out]; true while it is up.
     */
    private fun pass(
        now: Float,
        seed: Int,
        salt: Int,
        period: Float,
        seconds: Float,
        chance: Float,
        out: Pass,
    ): Boolean {
        out.on = false
        if (now <= 0f) return false
        val k = floor(now / period).toInt()
        out.pass = k
        if (hash(seed, k, salt) >= chance) return false
        val start = hash(seed, k, salt + 1) * (period - seconds)
        val t = (now - k * period - start) / seconds
        if (t < 0f || t >= 1f) return false
        out.on = true
        out.t = t
        out.dir = if (hash(seed, k, salt + 2) < .5f) -1f else 1f
        return true
    }

    /** Across the view at [t] of a pass, going [dir]: from just past one edge to past the other. */
    private fun across(t: Float, dir: Float): Float =
        if (dir > 0f) -.12f + 1.24f * t else 1.12f - 1.24f * t

    /**
     * A shooting star, now and then on a clear night: a meteor high in the sky. [Pass.x] and
     * [Pass.y] are its head, which burns for the first [METEOR_HEAD_END] of the pass and then goes
     * out where it is, its train fading behind it for the rest ([meteorHead], [meteorTrain]).
     */
    fun shootingStar(seconds: Float, seed: Int, out: Pass): Boolean {
        if (!pass(seconds, seed, 20, SHOOT_PERIOD, SHOOT_SECONDS, .5f, out)) return false
        val k = out.pass
        val m = min(out.t, METEOR_HEAD_END)
        out.x = .18f + .64f * hash(seed, k, 23) + out.dir * METEOR_RUN * m
        out.y = .04f + .3f * hash(seed, k, 24) + meteorSlope(seed, k) * m
        return true
    }

    // ---- the meteor: a pass is its head burning, then its train fading where the head went out

    /** The share of a shooting star's pass its head burns for. */
    const val METEOR_HEAD_END = .7f

    /** How far across the view (a share of the width) a meteor's head runs in a whole pass. */
    const val METEOR_RUN = .26f

    /** The sparkles a meteor sheds in a pass. */
    const val SPARKS = 7

    /** How long a sparkle lives, as a share of the pass. */
    const val SPARK_LIFE = .3f

    /**
     * How far down the sky's band a meteor's head drops in a whole pass: a steeper one or a
     * flatter.
     */
    fun meteorSlope(seed: Int, pass: Int): Float = .08f + .14f * hash(seed, pass, 25)

    /**
     * How bright a meteor's head is at [t] of its pass, 0 to 1: it flares up quickly, is brightest
     * in the middle of its run and is gone by [METEOR_HEAD_END].
     */
    fun meteorHead(t: Float): Float {
        val u = t / METEOR_HEAD_END
        if (u <= 0f || u >= 1f) return 0f
        return sqrt(4f * u * (1f - u))
    }

    /**
     * How strong the train (the streak behind the head) is at [t], 0 to 1: it is drawn out over the
     * first part of the run, holds while the head burns and fades after the head is gone, to
     * nothing by the end of the pass.
     */
    fun meteorTrain(t: Float): Float = smooth(0f, .25f, t) * (1f - smooth(.55f, 1f, t))

    /** How much of a meteor's full streak has been drawn out at [t], 0 to 1. */
    fun meteorReach(t: Float): Float = smooth(0f, .45f, t)

    /**
     * Whether the meteor of [pass] is a fireball: rarely, a bigger, brighter one with colour in it.
     */
    fun fireball(seed: Int, pass: Int): Boolean = hash(seed, pass, 26) < .12f

    /** How long the meteor of [pass] is, 1 for the usual one: a fireball is much longer. */
    fun meteorSize(seed: Int, pass: Int): Float =
        (.8f + .5f * hash(seed, pass, 27)) * (if (fireball(seed, pass)) 1.45f else 1f)

    /** How bright the meteor of [pass] is, up to 1: a fireball always is. */
    fun meteorBright(seed: Int, pass: Int): Float =
        if (fireball(seed, pass)) 1f else .7f + .3f * hash(seed, pass, 28)

    /**
     * When sparkle [i] of the meteor of [pass] is shed, as a share of the pass: while the head
     * still burns, so every one is shed where the head has been.
     */
    fun sparkBirth(seed: Int, pass: Int, i: Int): Float =
        .06f + (METEOR_HEAD_END - SPARK_LIFE - .06f) * hash(seed, pass, 90 + i)

    /** Which way sparkle [i] drifts off the streak, -1 to 1. */
    fun sparkSide(seed: Int, pass: Int, i: Int): Float = hash(seed, pass, 110 + i) * 2f - 1f

    /**
     * How bright a sparkle shed at [birth] is at [t], 0 to 1: it winks on and fades out over
     * [SPARK_LIFE] of the pass.
     */
    fun sparkAlpha(birth: Float, t: Float): Float {
        val age = (t - birth) / SPARK_LIFE
        if (age <= 0f || age >= 1f) return 0f
        val fade = 1f - age
        return smooth(0f, .1f, age) * fade * fade
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** An airplane crossing slowly, climbing a little: about every other minute. */
    fun plane(seconds: Float, seed: Int, out: Pass): Boolean {
        if (!pass(seconds, seed, 30, PLANE_PERIOD, PLANE_SECONDS, .6f, out)) return false
        out.x = across(out.t, out.dir)
        out.y = .1f + .28f * hash(seed, out.pass, 33) - .05f * out.t
        return true
    }

    private val flockScratch = FlockPose()

    /**
     * A flock of birds, by day and at dusk, on the [Flight] of its pass ([Flight.variant]): in at
     * an edge and away into the distance, or the other way round. [Pass.x] is the flock's centre,
     * [Pass.y] the share of the sky's band it starts at (it sinks from there as it recedes),
     * [Pass.dir] the way it heads across the screen as it comes in.
     */
    fun birds(seconds: Float, seed: Int, out: Pass): Boolean {
        if (!pass(seconds, seed, 40, BIRD_PERIOD, BIRD_SECONDS, .6f, out)) return false
        val variant = Flight.variant(seed, out.pass)
        Flight.flock(out.t, variant, flockScratch)
        out.x = flockScratch.x
        out.y = birdsHeight(seed, out.pass)
        out.dir = if (Flight.towards(variant)) -Flight.side(variant) else Flight.side(variant)
        return true
    }

    /** The share of the sky's band (under the heading) the flock of [pass] flies in at. */
    fun birdsHeight(seed: Int, pass: Int): Float = .26f + .16f * hash(seed, pass, 43)

    /** How far bird [i]'s wings are raised at [seconds], -1 down to 1 up: each its own beat. */
    fun wing(i: Int, seconds: Float): Float = sin(seconds * birdRate(i) + i * 1.7f)

    /** How far bird [i] is: 0 near, 1 further, 2 far (smaller, fainter, slower on the wing). */
    fun birdTier(i: Int): Int = BIRD_TIERS[i % BIRD_TIERS.size]

    private val BIRD_TIERS = intArrayOf(0, 1, 1, 2, 0, 2, 1)

    /** Bird [i]'s size, 1 for a near bird. */
    fun birdSize(i: Int): Float = 1f - .2f * birdTier(i)

    /** How hard bird [i] is to see, 1 for a near bird: the far ones fade into the air. */
    fun birdFade(i: Int): Float = 1f - .22f * birdTier(i)

    /** Bird [i]'s wingbeat, radians a second: the far ones slower. */
    fun birdRate(i: Int): Float = 8.5f - 1.1f * birdTier(i) + .3f * hash(i, 0, 91)

    /**
     * How much bird [i] is gliding at [seconds], 0 beating to 1 wings held still: now and then one
     * holds its wings for a second or two, easing in and out of it. At most a couple glide at once.
     */
    fun glide(i: Int, seconds: Float): Float {
        val slot = 5.5f + 2.5f * hash(i, 0, 92)
        val t = seconds / slot + hash(i, 0, 93)
        val k = floor(t).toInt()
        if (hash(i, k, 94) >= .4f) return 0f
        val u = t - k
        val start = .1f + .35f * hash(i, k, 95)
        val len = (1.4f + 1.2f * hash(i, k, 96)) / slot
        val x = (u - start) / len
        if (x <= 0f || x >= 1f) return 0f
        val ease = minOf(1f, minOf(x, 1f - x) * 3.5f)
        return ease * ease * (3f - 2f * ease)
    }

    /**
     * Where bird [i]'s wings are at [seconds], -1 down to 1 up, with the beat skewed (the wings
     * rise quickly and fall slowly) and a glide held with the wings a little above level.
     */
    fun flap(i: Int, seconds: Float): Float {
        val beat = wing(i, seconds)
        val skew = (beat + .35f * beat * beat * beat).coerceIn(-1f, 1f)
        val g = glide(i, seconds)
        return skew + (.28f - skew) * g
    }

    /** Bird [i]'s rise and fall on its own path at [seconds], in dp: a slow undulation. */
    fun birdBob(i: Int, seconds: Float): Float =
        .7f * sin(seconds * (1.1f + .25f * hash(i, 0, 97)) + i * 2.1f) +
            .3f * sin(seconds * 2.7f + i * .9f)

    /**
     * A blimp drifting across, rarely, by day or (its panel lit) by night. [Pass.y] is where in the
     * open sky above the roofs it flies: 0 as high as it goes, 1 as low.
     */
    fun blimp(seconds: Float, seed: Int, out: Pass): Boolean {
        if (!pass(seconds, seed, 50, BLIMP_PERIOD, BLIMP_SECONDS, .4f, out)) return false
        out.x = across(out.t, out.dir)
        out.y = hash(seed, out.pass, 53)
        return true
    }

    /** A car along the far road, going one way or the other. */
    fun car(seconds: Float, seed: Int, out: Pass): Boolean {
        if (!pass(seconds, seed, 60, CAR_PERIOD, CAR_SECONDS, .8f, out)) return false
        out.x = across(out.t, out.dir)
        out.y = 1f
        return true
    }

    /**
     * Which of [count] vehicles the [k]th car of [pass] is (the first, then the one following it):
     * a different one each pass.
     */
    fun vehicle(seed: Int, pass: Int, k: Int, count: Int): Int =
        (hash(seed, pass, 61 + k) * count).toInt().coerceIn(0, count - 1)

    /**
     * How far behind the car of [pass] a second one follows, as a share of the width, or 0 when it
     * drives alone (most do).
     */
    fun carFollow(seed: Int, pass: Int): Float =
        if (hash(seed, pass, 64) < .3f) .045f + .035f * hash(seed, pass, 65) else 0f

    /** A train on the elevated line comes most of these periods (s), taking this long to cross. */
    const val TRAIN_PERIOD = 64f
    const val TRAIN_SECONDS = 24f

    /**
     * The elevated train across the middle distance at [seconds]: [Pass.x] is where its front is,
     * as a share of the width, from well past one edge (it is long) to past the other.
     */
    fun train(seconds: Float, seed: Int, out: Pass): Boolean {
        if (!pass(seconds, seed, 70, TRAIN_PERIOD, TRAIN_SECONDS, .85f, out)) return false
        out.x = if (out.dir > 0f) -.05f + 1.6f * out.t else 1.05f - 1.6f * out.t
        out.y = 0f
        return true
    }

    /**
     * Where cloud [i] (of a few) is across the view at [seconds]: they drift right at their own
     * slow speeds and come round again. At 0 seconds they stand where they always stand.
     */
    fun cloudX(i: Int, seconds: Float, seed: Int): Float {
        val start = .12f + i * .41f + hash(seed, i, 70) * .1f
        val speed = .0035f + .004f * hash(seed, i, 71)
        val span = 1.5f
        return ((start + seconds * speed) % span + span) % span - .25f
    }

    /**
     * The share of the sky's band each cloud lane drifts at, high to low: the lowest stays well
     * above the far towers.
     */
    val CLOUD_LANES = floatArrayOf(.04f, .17f, .3f, .43f, .55f, .66f)

    /**
     * The lane each cloud takes, in the order they are filled in (a night sky has the first three,
     * dusk four): the big near cumulus and the high cirrus first, the small far ones last.
     */
    private val LANE_ORDER = intArrayOf(1, 0, 3, 2, 4, 5)

    private val CLOUD_KINDS =
        arrayOf(
            CloudKind.CUMULUS,
            CloudKind.CIRRUS,
            CloudKind.STRATUS,
            CloudKind.CUMULUS,
            CloudKind.PUFF,
            CloudKind.PUFF,
        )

    /** The most clouds a sky holds. */
    const val CLOUDS = 6

    /** How many sprites each kind of cloud has to choose from. */
    const val CLOUD_VARIANTS = 2

    /**
     * The sky's [CLOUDS] clouds, the same for the same [seed]. Each has a lane of its own; the
     * lower the lane, the further the cloud: smaller, slower and hazier.
     */
    fun clouds(seed: Int): List<Cloud> =
        List(CLOUDS) { i ->
            val kind = CLOUD_KINDS[i]
            val lane = LANE_ORDER[i]
            val far = lane / (CLOUD_LANES.size - 1f)
            val size =
                when (kind) {
                    CloudKind.CUMULUS -> (if (i == 0) 1.35f else .95f) + .25f * hash(seed, i, 72)
                    CloudKind.STRATUS -> 1.2f + .3f * hash(seed, i, 72)
                    CloudKind.CIRRUS -> 1.3f + .4f * hash(seed, i, 72)
                    CloudKind.PUFF -> 1.05f + .3f * hash(seed, i, 72)
                } * (1f - .3f * far)
            val depth = (1f - far).coerceIn(0f, 1f)
            Cloud(
                kind,
                lane,
                .08f + i * .37f + hash(seed, i, 70) * .12f,
                CLOUD_LANES[lane],
                size,
                depth,
                .0022f + .0058f * depth,
                (hash(seed, i, 75) * CLOUD_VARIANTS).toInt().coerceIn(0, CLOUD_VARIANTS - 1),
                far,
            )
        }

    /**
     * Where [cloud] is across the view at [seconds] (a share of the width, a little off either
     * edge).
     */
    fun cloudAt(cloud: Cloud, seconds: Float): Float {
        val span = 1.5f
        return ((cloud.x0 + seconds * cloud.speed) % span + span) % span - .25f
    }

    /** A tree's lean at [seconds], in degrees: a slow sway with a gust in it. */
    fun sway(seed: Int, seconds: Float): Float =
        1.4f * sin(seconds * .9f + seed) + .5f * sin(seconds * 2.3f + seed * 1.7f)

    /** A flag's ripple at [seconds], -1 to 1. */
    fun flag(seconds: Float): Float = sin(seconds * 4.2f)

    /**
     * How far on the light with [seed] is at [hour], 0 to 1: each light has its own moment across
     * the dusk hour and ramps up over a few minutes there, off in reverse around dawn ([Dusk]).
     */
    fun lightsOn(seed: Int, hour: Float, kind: Int = Dusk.HOME): Float =
        Dusk.lightsOn(seed, hour, kind)

    /**
     * The step the people in the windows are on at [seconds]: they move [PEOPLE_FPS] times a
     * second.
     */
    fun step(seconds: Float): Int = floor(seconds * PEOPLE_FPS).toInt()

    /**
     * A failing light at [seconds]: for a floor missed once ([sputter] false) a light that holds
     * steady and now and then dips for a moment; missed twice, a sputter that dips far more often.
     */
    fun flicker(seed: Int, seconds: Float, sputter: Boolean): Boolean =
        flickerLevel(seed, seconds, sputter) > .5f

    /**
     * How bright a failing light is at [seconds], 0 to 1. It is slow and eased, never a strobe:
     * time is cut into slots (about a second and a half, or under a second when sputtering); in
     * some of them the light dips, dimming down and brightening back over a fraction of a second.
     */
    fun flickerLevel(seed: Int, seconds: Float, sputter: Boolean): Float {
        val slotLen = if (sputter) .9f else 1.6f
        val t = seconds / slotLen + hash(seed, 0, 82)
        val k = floor(t).toInt()
        if (hash(seed, k, 83) >= (if (sputter) .7f else .3f)) return 1f
        val u = t - k
        val start = hash(seed, k, 84) * .4f
        val len = if (sputter) .5f else .16f
        val x = (u - start) / len
        if (x <= 0f || x >= 1f) return 1f
        // a smooth dip: 1 down to the floor and back, flat at the bottom
        val depth = if (sputter) .85f else .8f
        val ease = minOf(1f, minOf(x, 1f - x) * 4f)
        return 1f - depth * ease * ease * (3f - 2f * ease)
    }
}
