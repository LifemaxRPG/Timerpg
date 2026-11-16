package com.lifemax.rpg.feature.habits.domain

/*
 * What the people on the pavement do with their hands and their heads while their legs walk: most of the time
 * nothing (the arms swing), and now and then something, as people do: a look at the phone (walking a little slower
 * for it), a call, a scratch of the cheek or the back of the head, a hand to the hair or the cap, a look at the
 * watch, a glance across the street, a small wave, the hands in the pockets for a while, a hand on the bag's strap;
 * and some carry a coffee all the way, sipping it now and then. Each walker has their own habits (a few things they
 * do more than the rest). Like the rest of the street it is a function of the seconds and a seed, worked out from
 * the start of the walk each time it is asked for, with no allocation; [StreetLayers] turns it into the arms' and
 * the head's pose, eased in and out so nothing pops.
 */

/** What a walker's hands and head are doing. */
enum class Act {
    /** Walking, the arms swinging (or the coffee carried). */
    RELAX,
    /** Reading the phone held in the near hand, the head bowed over it. */
    PHONE,
    /** Typing on it with both hands. */
    TEXT,
    /** The phone at the ear. */
    CALL,
    /** Scratching the cheek. */
    SCRATCH,
    /** Rubbing the back of the head. */
    RUB,
    /** A hand to the hair, or a tug at the cap or the beanie. */
    HAIR,
    /** A look at the watch on the near wrist. */
    WATCH,
    /** A small wave to someone across the street. */
    WAVE,
    /** A glance round, across the street. */
    GLANCE,
    /** Both hands in the pockets. */
    POCKETS,
    /** The near hand on the bag's strap. */
    STRAP,
    /** A sip of the coffee carried in the near hand. */
    SIP,
    /** Reading the phone held in the far hand (the near one is busy: a lead, a bag, a cup). */
    FAR_PHONE,
    /** A look at the watch on the far wrist. */
    FAR_WATCH,
    /** Stopped at a shop window: the head turned away from us, to the glass. */
    PEER,
    /** Stopped, looking round: towards the street, then the other way, then on. */
    LOOK_ROUND,
    /** Down on one knee tying the near shoe. */
    TIE,
    /** Stopped to talk to the one beside them: the head turned to them, a hand at work. */
    TALK,
}

/** What one walker is doing this moment, written by [StreetActs.at]. */
class Gesture {
    var act = Act.RELAX

    /** How far into it they are, 0 (not at all) to 1 (fully), eased in and out. */
    var w = 0f

    /** Seconds since it began, and how long it lasts. */
    var t = 0f
    var dur = 1f

    /** A number of its own, 0 to 1 (which way a hand sweeps, how high a wave goes). */
    var vary = 0f

    /** They carry a coffee in the near hand, the whole walk. */
    var cup = false

    /** Seconds of walking lost so far to slowing down over the phone. */
    var lost = 0f

    /** How far their arms swing, of the walk's own swing ([StreetManners.swing]). */
    var swing = 1f

    fun clear() {
        act = Act.RELAX
        w = 0f
        t = 0f
        dur = 1f
        vary = 0f
        cup = false
        lost = 0f
        swing = 1f
    }
}

object StreetActs {
    /** How much slower someone walks reading their phone (of their pace). */
    const val SLOW = .16f

    /** Easing into and out of an act, a glance, and a reach up to the head, in seconds. */
    const val BLEND = .45f
    const val QUICK = .32f
    const val REACH = .5f

    /** How long walkers go between acts, seconds: [GAP] to [GAP] + [GAP_MORE]. */
    const val GAP = 3f
    const val GAP_MORE = 5.5f

    /** A coffee is carried by about this share of the walkers whose near hand is free. */
    const val CUP = .22f

    /** The most acts looked through (a walk is a minute or two). */
    private const val MOST = 64

    private val ACTS = Act.values()

    /** Whether [act] slows the walk. */
    fun slows(act: Act): Boolean = act == Act.PHONE || act == Act.TEXT || act == Act.FAR_PHONE

    /** Whether [act] is quick (a gesture) rather than held. */
    fun quick(act: Act): Boolean =
        when (act) {
            Act.SCRATCH,
            Act.RUB,
            Act.HAIR,
            Act.WATCH,
            Act.FAR_WATCH,
            Act.WAVE,
            Act.GLANCE,
            Act.SIP -> true
            else -> false
        }

    /**
     * How long [act] takes to ease in and out: a glance is quick, a hand raised to the head takes a
     * little longer than one brought to the chest.
     */
    fun blend(act: Act): Float =
        when (act) {
            Act.GLANCE -> QUICK
            Act.TIE -> 1f
            Act.PEER,
            Act.LOOK_ROUND,
            Act.TALK -> .6f
            Act.CALL,
            Act.SCRATCH,
            Act.RUB,
            Act.HAIR,
            Act.WAVE,
            Act.SIP -> REACH
            else -> BLEND
        }

    /** How long [act] lasts at [h] (0 to 1) of its range, seconds. */
    fun duration(act: Act, h: Float): Float =
        when (act) {
            Act.PHONE,
            Act.TEXT,
            Act.FAR_PHONE -> 4f + 6f * h
            Act.CALL -> 6f + 6f * h
            Act.SCRATCH -> 1.1f + .6f * h
            Act.RUB,
            Act.HAIR -> 1.2f + .7f * h
            Act.WATCH,
            Act.FAR_WATCH -> 1.4f + .8f * h
            Act.WAVE -> 1.6f + .7f * h
            Act.GLANCE -> 1.0f + .9f * h
            Act.POCKETS -> 5f + 7f * h
            Act.STRAP -> 4f + 5f * h
            Act.SIP -> 1.8f + .8f * h
            Act.PEER,
            Act.LOOK_ROUND,
            Act.TIE,
            Act.TALK -> 3f + 3f * h
            Act.RELAX -> 1f
        }

    /**
     * How likely [act] is for [look], with the near hand [busy] (a lead, a handbag) or holding a
     * [cup]: 0 for what they cannot do.
     */
    fun weight(
        act: Act,
        look: Int,
        busy: Boolean,
        cup: Boolean,
        manner: Manner = Manner.AVERAGE,
    ): Float = plain(act, look, busy, cup) * StreetManners.liking(manner, act)

    private fun plain(act: Act, look: Int, busy: Boolean, cup: Boolean): Float {
        val l = StreetLooks.ALL[look]
        if (busy || cup) {
            return when (act) {
                Act.SIP -> if (cup) .45f else 0f
                Act.FAR_PHONE -> if (cup) .15f else .35f
                Act.GLANCE -> .25f
                Act.FAR_WATCH -> .12f
                else -> 0f
            }
        }
        return when (act) {
            Act.PHONE -> .17f
            Act.TEXT -> .08f
            Act.CALL -> .07f
            Act.SCRATCH -> .09f
            Act.RUB -> .05f
            Act.HAIR -> .07f
            Act.WATCH -> .08f
            Act.WAVE -> .03f
            Act.GLANCE -> .14f
            Act.POCKETS -> if (l.coat != Coat.NONE) .14f else .07f
            Act.STRAP -> if (l.bag == Bag.SHOULDER || l.bag == Bag.BACKPACK) .1f else 0f
            else -> 0f
        }
    }

    /** Whether the walker of slot [k] ([who]: 0 the walker, 1 a companion) carries a coffee. */
    fun cup(seed: Int, k: Int, who: Int, busy: Boolean): Boolean =
        !busy && Ambient.hash(seed * 131 + who * 17, k, 299) < CUP

    /**
     * What the walker of slot [k] ([who]: 0 the walker, 1 a companion) of [look] is doing [t]
     * seconds into their walk, their near hand [busy] or not, written into [out].
     */
    fun at(
        seed: Int,
        k: Int,
        who: Int,
        t: Float,
        look: Int,
        busy: Boolean,
        out: Gesture,
        manner: Manner = Manner.AVERAGE,
    ) {
        out.clear()
        val base = seed * 131 + who * 17
        val cup = cup(seed, k, who, busy)
        out.cup = cup
        if (t <= 0f) return
        // the walker's own habits: each act's likelihood scaled by a factor of their own
        var total = 0f
        for (a in ACTS) total += weight(a, look, busy, cup, manner) * like(base, k, a)
        if (total <= 0f) return
        var start = 1f + 4f * Ambient.hash(base, k, 300)
        var lost = 0f
        var i = 0
        while (i < MOST && start <= t) {
            val act = pick(base, k, i, look, busy, cup, total, manner)
            val dur = duration(act, Ambient.hash(base, k, 400 + i * 4))
            val b = blend(act)
            val end = start + dur
            if (t < end) {
                val x = t - start
                out.act = act
                out.t = x
                out.dur = dur
                out.vary = Ambient.hash(base, k, 401 + i * 4)
                out.w = envelope(x, dur, b)
                if (slows(act)) lost += SLOW * integral(x, dur, b)
                out.lost = lost
                return
            }
            if (slows(act)) lost += SLOW * (dur - b)
            val gap =
                StreetManners.gap(manner) *
                    if (cup) 2.5f + 4f * Ambient.hash(base, k, 402 + i * 4)
                    else GAP + GAP_MORE * Ambient.hash(base, k, 402 + i * 4)
            start = end + gap
            i++
        }
        out.lost = lost
    }

    /** A walker's liking for [act], .35 to 1.65. */
    private fun like(base: Int, k: Int, act: Act): Float =
        .35f + 1.3f * Ambient.hash(base, k, 350 + act.ordinal)

    private fun pick(
        base: Int,
        k: Int,
        i: Int,
        look: Int,
        busy: Boolean,
        cup: Boolean,
        total: Float,
        manner: Manner,
    ): Act {
        var h = Ambient.hash(base, k, 403 + i * 4) * total
        var last = Act.GLANCE
        for (a in ACTS) {
            val wt = weight(a, look, busy, cup, manner) * like(base, k, a)
            if (wt <= 0f) continue
            last = a
            if (h < wt) return a
            h -= wt
        }
        return last
    }

    private fun smooth(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** How far into an act [dur] long, eased in and out over [b], they are [x] seconds in. */
    fun envelope(x: Float, dur: Float, b: Float): Float =
        when {
            x <= 0f || x >= dur -> 0f
            x < b -> smooth(x / b)
            x > dur - b -> smooth((dur - x) / b)
            else -> 1f
        }

    /** The [envelope] summed from the act's start to [x] seconds in. */
    fun integral(x: Float, dur: Float, b: Float): Float {
        fun s(v: Float): Float {
            val c = v.coerceIn(0f, 1f)
            return b * (c * c * c - c * c * c * c / 2f)
        }
        return when {
            x <= 0f -> 0f
            x < b -> s(x / b)
            x <= dur - b -> b / 2f + (x - b)
            x < dur -> dur - b - s((dur - x) / b)
            else -> dur - b
        }
    }
}
