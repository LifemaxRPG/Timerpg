package com.timerpg.street.night

/**
 * What the overnight writer reads every few seconds: the switch in the AI panel, the charger and battery, whether the
 * game is open (it writes its own lines then) or the NPU is being tuned, the phone's heat (PowerManager's thermal
 * status, 0 none to 6 shutdown, -1 not known; its headroom, 1.0 = throttling, NaN not known), the lines still to write
 * (-1 while the model is not loaded yet) and a model that would not load.
 */
data class NightInputs(
    val enabled: Boolean = true,
    val plugged: Boolean = true,
    val battery: Int = 100,
    val gameOpen: Boolean = false,
    val tuning: Boolean = false,
    val thermal: Int = 0,
    val headroom: Float = Float.NaN,
    val left: Int = -1,
    val modelGaveUp: String? = null,
)

/** What the writer does now. */
sealed class NightStep {
    /** Stop for [reason]; [again]: start again at the next charge (the job is scheduled again). */
    data class Stop(val reason: String, val again: Boolean) : NightStep()
    /** Write nothing new for now (the line being written finishes unless [Kind.HOT]). */
    data class Rest(val reason: String, val kind: Kind) : NightStep()
    object Write : NightStep() { override fun toString() = "Write" }

    enum class Kind { BATTERY, HOT, COOL, BURST }
}

/**
 * The overnight writer's decisions (pure: tested on the JVM). Heat is a scheduling problem, not a reason to stop:
 * - it writes in bursts of [burstMs], resting [restMs] between them (the phone sheds heat while charging);
 * - at a moderate thermal status, or headroom past [COOL_AT], it cools down until the status is light again and the
 *   headroom under [RESUME_AT];
 * - at severe it pauses (the line being written is stopped too) until the status is light again;
 * - at critical or above it stops for the night (started again at the next charge).
 * The battery must be at [minBattery]% or more (charging comes first below that). Unplugging, the game opening, the
 * switch turned off or the NPU's tuning stop it; so does a complete bank (nothing left to write).
 * The numbers are first guesses for the S25 Ultra, not measured: the night report records every pause.
 */
class NightRules(
    val burstMs: Long = 8 * 60_000L,
    val restMs: Long = 2 * 60_000L,
    val minBattery: Int = 30,
) {
    private var hot = false
    private var cooling = false
    private var burstStart = -1L
    private var restUntil = -1L

    /** Times it cooled down (moderate heat or low headroom) and paused (severe), and the hottest status seen. */
    var coolDowns = 0; private set
    var hotPauses = 0; private set
    var maxThermal = -1; private set

    fun step(i: NightInputs, now: Long): NightStep {
        if (i.thermal > maxThermal) maxThermal = i.thermal
        if (!i.enabled) return NightStep.Stop("switched off in the AI panel", again = false)
        if (i.gameOpen) return NightStep.Stop("the game opened (it writes its own lines)", again = false)
        if (!i.plugged) return NightStep.Stop("unplugged", again = true)
        if (i.tuning) return NightStep.Stop("the NPU is being tuned", again = false)
        if (i.thermal >= CRITICAL) return NightStep.Stop("too hot (thermal status ${i.thermal})", again = true)
        i.modelGaveUp?.let { return NightStep.Stop(it, again = true) }
        if (i.left == 0) return NightStep.Stop("the bank is complete", again = false)
        if (i.battery < minBattery) { burstStart = -1; return NightStep.Rest("charging to $minBattery% first", NightStep.Kind.BATTERY) }
        if (i.thermal >= SEVERE) {
            if (!hot) { hot = true; hotPauses++ }
            burstStart = -1
            return NightStep.Rest("hot (thermal status ${i.thermal}): paused", NightStep.Kind.HOT)
        }
        if (hot) {
            if (i.thermal in 0..LIGHT) hot = false
            else return NightStep.Rest("hot: waiting for it to cool", NightStep.Kind.HOT)
        }
        val warm = i.thermal >= MODERATE || (!i.headroom.isNaN() && i.headroom >= COOL_AT)
        if (warm) {
            if (!cooling) { cooling = true; coolDowns++ }
            burstStart = -1
            return NightStep.Rest("warm: cooling down", NightStep.Kind.COOL)
        }
        if (cooling) {
            if (i.thermal <= LIGHT && (i.headroom.isNaN() || i.headroom < RESUME_AT)) cooling = false
            else return NightStep.Rest("warm: cooling down", NightStep.Kind.COOL)
        }
        if (now < restUntil) return NightStep.Rest("resting between bursts", NightStep.Kind.BURST)
        if (burstStart < 0) burstStart = now
        if (now - burstStart >= burstMs) {
            restUntil = now + restMs
            burstStart = -1
            return NightStep.Rest("resting between bursts", NightStep.Kind.BURST)
        }
        return NightStep.Write
    }

    companion object {
        /** PowerManager's thermal statuses. */
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4
        /** Headroom (1.0 = the phone starts throttling) at which it cools down, and under which it writes again. */
        const val COOL_AT = .90f
        const val RESUME_AT = .75f
    }
}
