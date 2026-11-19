package com.timerpg.street.night

/**
 * What the writer reads every few seconds (the overnight writer and Writer mode): the AI panel's switch, the charger
 * (plugged, and charging now) and battery, whether the game is open (it writes its own lines then) or the NPU is being
 * tuned, the phone's heat (PowerManager's thermal status, 0 none to 6 shutdown, -1 not known; its headroom, 1.0 =
 * throttling, NaN not known), the lines still to write (-1 while the model is not loaded yet), a model that would not
 * load, and Writer mode's own choices: writing on battery ([onBattery], the owner's choice) and the Pause button.
 */
data class NightInputs(
    val enabled: Boolean = true,
    val plugged: Boolean = true,
    val charging: Boolean = true,
    val battery: Int = 100,
    val gameOpen: Boolean = false,
    val tuning: Boolean = false,
    val thermal: Int = 0,
    val headroom: Float = Float.NaN,
    val left: Int = -1,
    val modelGaveUp: String? = null,
    val onBattery: Boolean = false,
    val paused: Boolean = false,
)

/** What the writer does now. */
sealed class NightStep {
    /** Stop for [reason]; [again]: start again at the next charge (the job is scheduled again). */
    data class Stop(val reason: String, val again: Boolean) : NightStep()
    /** Write nothing new for now (the line being written finishes unless [Kind.HOT]). */
    data class Rest(val reason: String, val kind: Kind) : NightStep()
    object Write : NightStep() { override fun toString() = "Write" }

    /** BATTERY: charging first; HOT: too hot, paused; COOL: warm, resting between short bursts; BURST: resting between
     *  ordinary bursts; USER: paused in Writer mode; GAME: the game's process is being closed to free its memory. */
    enum class Kind { BATTERY, HOT, COOL, BURST, USER, GAME }
}

/**
 * The writer's decisions (pure: tested on the JVM). Heat is a scheduling problem, not a reason to stop or to write
 * worse lines:
 * - cool (thermal status light or less): it starts at once, no cool-down first; it writes in bursts of [burstMs] with
 *   [restMs] between them, and with no rest at all while the headroom is known and well clear ([RESUME_AT]);
 * - warm (moderate, or headroom at [COOL_AT] or more): it goes on, slower: bursts of [warmBurstMs], rests of
 *   [warmRestMs] (a warm rest ends early once the phone is cool again);
 * - severe: it pauses (the line being written is stopped too) and goes on in warm bursts once it is moderate again;
 * - critical or above: it stops (the overnight writer starts again at the next charge).
 * Battery: plugged in, it writes at any level while the battery charges; under [minBattery]% it waits only when the
 * battery is not charging or falls (a weak charger losing to the writing). On battery (Writer mode, the owner's choice),
 * it writes down to [minOnBattery]% and then waits for the charger.
 * Unplugging (unless writing on battery), the game opening, the switch turned off or the NPU's tuning stop it; so does a
 * complete bank (nothing left to write). The numbers are first guesses for the S25 Ultra, not measured: the report
 * records every pause and the hottest status.
 */
class NightRules(
    val burstMs: Long = 8 * 60_000L,
    val restMs: Long = 2 * 60_000L,
    val warmBurstMs: Long = 90_000L,
    val warmRestMs: Long = 4 * 60_000L,
    val minBattery: Int = 15,
    val minOnBattery: Int = 20,
) {
    private var hot = false
    private var warmNow = false
    private var burstStart = -1L
    private var restUntil = -1L
    private var restWarm = false
    /** Plugged in under [minBattery]: the lowest level seen, and held until [minBattery] (it fell, or is not charging). */
    private var lowMark = -1
    private var batteryHold = false

    /** Warm spells (moderate heat or low headroom: short bursts) and hot pauses (severe), and the hottest status seen. */
    var coolDowns = 0; private set
    var hotPauses = 0; private set
    var maxThermal = -1; private set

    fun step(i: NightInputs, now: Long): NightStep {
        if (i.thermal > maxThermal) maxThermal = i.thermal
        if (!i.enabled) return NightStep.Stop("the overnight writer was switched off in the AI panel", again = false)
        if (i.gameOpen) return NightStep.Stop("the game was opened (it writes its own lines)", again = false)
        if (!i.plugged && !i.onBattery) return NightStep.Stop("unplugged", again = true)
        if (i.tuning) return NightStep.Stop("the NPU is being tuned", again = false)
        if (i.thermal >= CRITICAL) return NightStep.Stop("too hot (thermal status ${i.thermal}, critical)", again = true)
        i.modelGaveUp?.let { return NightStep.Stop(it, again = true) }
        if (i.left == 0) return NightStep.Stop("the bank is complete", again = false)
        if (i.paused) { burstStart = -1; return NightStep.Rest("paused", NightStep.Kind.USER) }
        battery(i)?.let { burstStart = -1; return it }
        if (i.thermal >= SEVERE) {
            if (!hot) { hot = true; hotPauses++ }
            burstStart = -1
            return NightStep.Rest("too hot (thermal status ${i.thermal}): paused until it is moderate again", NightStep.Kind.HOT)
        }
        hot = false
        val warm = i.thermal >= MODERATE || (!i.headroom.isNaN() && i.headroom >= COOL_AT)
        if (warm && !warmNow) coolDowns++
        warmNow = warm
        if (now < restUntil) {
            val cool = i.thermal <= LIGHT && (i.headroom.isNaN() || i.headroom < RESUME_AT)
            if (restWarm && cool) restUntil = -1
            else return rest(restWarm, i)
        }
        if (burstStart < 0) burstStart = now
        if (now - burstStart >= if (warm) warmBurstMs else burstMs) {
            // (the headroom known and well clear: no rest needed, the next burst starts now)
            val roomy = !warm && !i.headroom.isNaN() && i.headroom < RESUME_AT
            if (roomy) { burstStart = now; return NightStep.Write }
            restUntil = now + if (warm) warmRestMs else restMs
            restWarm = warm
            burstStart = -1
            return rest(warm, i)
        }
        return NightStep.Write
    }

    private fun rest(warm: Boolean, i: NightInputs) =
        if (warm) NightStep.Rest("warm (thermal status ${i.thermal}${if (i.headroom.isNaN()) "" else ", headroom %.2f".format(java.util.Locale.US, i.headroom)}): short bursts with longer rests", NightStep.Kind.COOL)
        else NightStep.Rest("resting between bursts", NightStep.Kind.BURST)

    private fun battery(i: NightInputs): NightStep.Rest? {
        if (!i.plugged) {
            lowMark = -1; batteryHold = false
            return if (i.battery in 0 until minOnBattery)
                NightStep.Rest("battery at ${i.battery}%: plug the phone in to go on (on battery it writes down to $minOnBattery%)", NightStep.Kind.BATTERY)
            else null
        }
        if (i.battery < 0 || i.battery >= minBattery) { lowMark = -1; batteryHold = false; return null }
        if (lowMark < 0) lowMark = i.battery
        if (!i.charging || i.battery < lowMark) batteryHold = true
        lowMark = minOf(lowMark, i.battery)
        return if (batteryHold) NightStep.Rest("battery at ${i.battery}% and " +
            (if (i.charging) "falling while it writes" else "not charging") + ": charging to $minBattery% first", NightStep.Kind.BATTERY)
        else null
    }

    companion object {
        /** PowerManager's thermal statuses. */
        const val LIGHT = 1
        const val MODERATE = 2
        const val SEVERE = 3
        const val CRITICAL = 4
        /** Headroom (1.0 = the phone starts throttling) at which it writes in short bursts, and under which it is cool. */
        const val COOL_AT = .90f
        const val RESUME_AT = .75f
    }
}
