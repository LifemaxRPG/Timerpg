package com.lifemax.rpg.feature.habits.domain

/*
 * Not everyone on the pavement walks alike. Each walker has a manner: someone brisk on their way somewhere (quick
 * steps, the arms pumping, a lean into it, little time for anything else), an average walker, someone strolling
 * with nowhere to be (slow, loose arms, looking about, stopping at the shop windows), someone young and quick with
 * their phone, and the older people, who have their own looks and their own short, stooped steps ([StreetGait]).
 * The legs' frames are the look's; a manner changes how quickly they come (the cadence), and the walk's phase still
 * comes from the ground covered, so the feet stay planted whatever the pace. A manner also says how often and how
 * long someone stops on the way ([Halt]) and what they do with their hands as they go ([StreetActs]).
 */

/** How someone walks. */
enum class Manner {
    BRISK,
    AVERAGE,
    STROLLER,
    ELDERLY,
    YOUNG,
}

/** Why someone has stopped on the pavement. */
enum class Halt {
    NONE,
    /** To read their phone. */
    PHONE,
    /** At a shop window, looking in. */
    WINDOW,
    /** To look round (to get their bearings, to wait for someone, to rest). */
    LOOK,
    /** To tie a shoe. */
    SHOE,
    /** Two walking together, to talk. */
    CHAT,
}

object StreetManners {
    /** The cadence of [m]'s steps, of the standard walk's. */
    fun tempo(m: Manner): Float =
        when (m) {
            Manner.BRISK -> 1.17f
            Manner.AVERAGE -> 1f
            Manner.STROLLER -> .82f
            Manner.ELDERLY -> .86f
            Manner.YOUNG -> 1.07f
        }

    /** How far [m]'s arms swing, of the walk's own swing. */
    fun swing(m: Manner): Float =
        when (m) {
            Manner.BRISK -> 1.28f
            Manner.STROLLER -> .78f
            Manner.YOUNG -> 1.1f
            else -> 1f
        }

    /**
     * How far [m] leans into the walk, degrees forward (the older people's stoop is in their own
     * frames).
     */
    fun lean(m: Manner): Float =
        when (m) {
            Manner.BRISK -> 2.6f
            Manner.STROLLER -> -1f
            Manner.YOUNG -> .8f
            else -> 0f
        }

    /** How long [m] goes between the things they do with their hands, of the usual gap. */
    fun gap(m: Manner): Float =
        when (m) {
            Manner.BRISK -> 1.5f
            Manner.STROLLER -> .85f
            Manner.ELDERLY -> 1.25f
            Manner.YOUNG -> .8f
            else -> 1f
        }

    /** How much more (or less) [m] does [act] than most. */
    fun liking(m: Manner, act: Act): Float =
        when (m) {
            Manner.BRISK ->
                when (act) {
                    Act.GLANCE,
                    Act.POCKETS -> .5f
                    Act.PHONE,
                    Act.TEXT -> .6f
                    Act.CALL -> 1.8f
                    Act.WATCH,
                    Act.FAR_WATCH -> 2.2f
                    else -> 1f
                }
            Manner.STROLLER ->
                when (act) {
                    Act.GLANCE -> 2.6f
                    Act.POCKETS -> 1.8f
                    Act.WATCH,
                    Act.FAR_WATCH -> .4f
                    Act.CALL -> .6f
                    else -> 1f
                }
            Manner.ELDERLY ->
                when (act) {
                    Act.GLANCE -> 1.8f
                    Act.PHONE,
                    Act.FAR_PHONE -> .35f
                    Act.TEXT,
                    Act.WAVE -> 0f
                    else -> 1f
                }
            Manner.YOUNG ->
                when (act) {
                    Act.PHONE,
                    Act.FAR_PHONE -> 2.4f
                    Act.TEXT -> 3f
                    Act.HAIR -> 1.5f
                    Act.WATCH,
                    Act.FAR_WATCH -> .3f
                    else -> 1f
                }
            Manner.AVERAGE -> 1f
        }

    /**
     * How many walkers in a hundred wear each street look ([StreetLooks.ALL]): the older people's
     * two looks rarer than the rest.
     */
    private val LOOK_SHARE = floatArrayOf(.205f, .205f, .205f, .205f, .09f, .09f)

    /**
     * For each of the first four looks, how its wearers walk: brisk, average, strolling, young (the
     * older looks are always [Manner.ELDERLY]). The long camel coat is usually in a hurry, the
     * beanie and backpack and the tee and cap are young, the skirt and handbag take their time.
     */
    private val MANNER_SHARE =
        arrayOf(
            floatArrayOf(.45f, .35f, .2f, 0f),
            floatArrayOf(.15f, .25f, .1f, .5f),
            floatArrayOf(.25f, .4f, .35f, 0f),
            floatArrayOf(0f, .3f, .35f, .35f),
        )

    private val PICKED = arrayOf(Manner.BRISK, Manner.AVERAGE, Manner.STROLLER, Manner.YOUNG)

    /** The look of a walker at [h] (0 to 1) of the looks' shares. */
    fun look(h: Float): Int {
        var acc = 0f
        for (i in 0 until StreetLooks.COUNT) {
            acc += LOOK_SHARE[i]
            if (h < acc) return i
        }
        return StreetLooks.COUNT - 1
    }

    /** How the wearer of [look] walks, at [h] (0 to 1). */
    fun manner(look: Int, h: Float): Manner {
        if (StreetLooks.ALL[look].elder) return Manner.ELDERLY
        val shares = MANNER_SHARE[look]
        var acc = 0f
        for (i in shares.indices) {
            acc += shares[i]
            if (h < acc) return PICKED[i]
        }
        return Manner.AVERAGE
    }

    /** How many times [m] stops on a walk, at [h] (0 to 1): most walk straight through. */
    fun stops(m: Manner, pair: Boolean, h: Float): Int {
        if (pair) return if (h < .55f) 1 else 0
        return when (m) {
            Manner.BRISK -> if (h < .12f) 1 else 0
            Manner.AVERAGE -> if (h < .35f) 1 else 0
            Manner.STROLLER -> if (h < .4f) 2 else if (h < .85f) 1 else 0
            Manner.ELDERLY -> if (h < .5f) 1 else 0
            Manner.YOUNG -> if (h < .45f) 1 else 0
        }
    }

    /**
     * Why [m] stops, at [h] (0 to 1); a [pair] stops to talk. Someone with a [stick] does not kneel
     * to a shoe.
     */
    fun halt(m: Manner, pair: Boolean, stick: Boolean, h: Float): Halt {
        if (pair) return Halt.CHAT
        val k =
            when (m) {
                Manner.BRISK -> if (h < .6f) Halt.PHONE else Halt.LOOK
                Manner.AVERAGE ->
                    if (h < .35f) Halt.PHONE
                    else if (h < .6f) Halt.WINDOW else if (h < .8f) Halt.LOOK else Halt.SHOE
                Manner.STROLLER ->
                    if (h < .5f) Halt.WINDOW
                    else if (h < .75f) Halt.LOOK else if (h < .9f) Halt.PHONE else Halt.SHOE
                Manner.ELDERLY ->
                    if (h < .45f) Halt.LOOK else if (h < .9f) Halt.WINDOW else Halt.PHONE
                Manner.YOUNG -> if (h < .65f) Halt.PHONE else if (h < .85f) Halt.LOOK else Halt.SHOE
            }
        return if (stick && k == Halt.SHOE) Halt.LOOK else k
    }

    /** How long [m] stands for [halt], seconds, at [h] (0 to 1). */
    fun hold(m: Manner, halt: Halt, h: Float): Float {
        val base =
            when (halt) {
                Halt.PHONE -> 1.6f + 2.6f * h
                Halt.WINDOW -> 3f + 4f * h
                Halt.LOOK -> 2f + 2.5f * h
                Halt.SHOE -> 3.4f + 1.6f * h
                Halt.CHAT -> 4f + 4f * h
                Halt.NONE -> 0f
            }
        return when (m) {
            Manner.BRISK -> base * .7f
            Manner.STROLLER,
            Manner.ELDERLY -> base * 1.2f
            else -> base
        }
    }

    /**
     * What the hands and the head do through [halt], the near hand [busy] (a lead, a bag, a cup, a
     * stick) or not.
     */
    fun act(halt: Halt, busy: Boolean): Act =
        when (halt) {
            Halt.PHONE -> if (busy) Act.FAR_PHONE else Act.PHONE
            Halt.WINDOW -> Act.PEER
            Halt.LOOK -> Act.LOOK_ROUND
            Halt.SHOE -> Act.TIE
            Halt.CHAT -> Act.TALK
            Halt.NONE -> Act.RELAX
        }

    /**
     * Where along the pavement [m] walks (y, the wall at 0, the kerb at 16), at [h] (0 to 1): the
     * slow keep to the shop fronts, the quick to the outside, where they can pass.
     */
    fun lane(m: Manner, h: Float): Float =
        when (m) {
            Manner.BRISK -> 10.6f + 2f * h
            Manner.AVERAGE -> 9f + 2.6f * h
            Manner.STROLLER -> 7.8f + 2.2f * h
            Manner.ELDERLY -> 7.8f + 1.7f * h
            Manner.YOUNG -> 9.6f + 2.4f * h
        }
}
