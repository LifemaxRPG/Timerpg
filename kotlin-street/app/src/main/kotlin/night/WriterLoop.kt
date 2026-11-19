package com.timerpg.street.night

import com.timerpg.street.dialogue.ModelState
import com.timerpg.street.dialogue.NightCount

/** What the writer's loop drives: the dialogue engine in the app ([NightSession]), a fake one in the tests. */
interface WriterEngine {
    val state: ModelState
    /** Why the model is not ready (the engine's own words). */
    val detail: String
    /** Lines still to write (every stage), -1 before the plan is known. */
    fun left(): Int
    fun count(): NightCount
    /** Holds the writing ([on]); [stopRunning]: the line being written is stopped too (too hot). */
    fun hold(on: Boolean, stopRunning: Boolean)
    /** Loads the model again (after a memory unload, or a load put off). */
    fun reload()
    /** What else holds the writing (the engine's own pauses, e.g. "memory"), null when nothing does. */
    fun held(): String? = null
}

/**
 * One writing session's loop (pure: tested on the JVM with a fake engine): every tick it asks [NightRules] what to do
 * with the phone's state, holds or lets the engine write, keeps the time spent each way, watches the model (loaded
 * once; after an unload or a load put off it is tried again now and then, then given up with the reason in plain words)
 * and estimates the time left. [NightSession] runs it on the phone.
 */
class WriterLoop(
    val rules: NightRules,
    private val engine: WriterEngine,
    val start: Long,
    private val reloadMs: Long = 3 * 60_000L,
    private val maxReloads: Int = 5,
    private val giveUpMs: Long = 20 * 60_000L,
) {
    /** Time spent resting, by [NightStep.Kind]; time allowed to write with the model ready; time waiting for the model. */
    val spent = LongArray(NightStep.Kind.values().size)
    var writingMs = 0L; private set
    var loadingMs = 0L; private set
    /** Time the rules let it write with the model ready but the engine held it anyway, and by what (last seen). */
    var heldMs = 0L; private set
    var heldBy: String? = null; private set
    /** The model's last problem, if it never got ready (for the report). */
    var modelProblem: String? = null; private set
    var step: NightStep = NightStep.Write; private set
    private var last = start
    private var reloads = 0
    private var lastReload = Long.MIN_VALUE / 2
    private var notLoadedSince = -1L
    private var gaveUp: String? = null

    fun spent(k: NightStep.Kind) = spent[k.ordinal]

    /** One tick at [now] with the phone's state [phone] ([NightInputs.left] and the model are filled in here); [askedStop]: a
     *  stop asked from outside (the notification, the game opened, the system ending a job). */
    fun tick(phone: NightInputs, now: Long, askedStop: String? = null): NightStep {
        val st = engine.state
        when (st) {
            ModelState.NO_MODEL, ModelState.NO_ENGINE -> gaveUp = "there is no dialogue model on the phone" + engine.detail.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            ModelState.READY -> { notLoadedSince = -1L; modelProblem = null }
            else -> {
                if (notLoadedSince < 0) notLoadedSince = now
                if (st == ModelState.UNLOADED || st == ModelState.ERROR || (st == ModelState.WAITING && engine.detail.contains("memory"))) modelProblem = engine.detail.take(200)
                if ((st == ModelState.UNLOADED || st == ModelState.ERROR) && now - lastReload > reloadMs) {
                    if (++reloads > maxReloads) gaveUp = "the model would not load (${engine.detail.take(120)})"
                    else { lastReload = now; engine.reload() }
                }
                if (now - notLoadedSince > giveUpMs) gaveUp = "the model was not ready for ${giveUpMs / 60_000} minutes (${engine.detail.take(120)})"
            }
        }
        val s = rules.step(phone.copy(left = engine.left(), modelGaveUp = gaveUp), now)
        val dt = (now - last).coerceAtLeast(0); last = now
        if (askedStop != null) return NightStep.Stop(askedStop, again = askedStop.startsWith("the system")).also { step = it }
        when (s) {
            is NightStep.Stop -> {}
            is NightStep.Rest -> { spent[s.kind.ordinal] += dt; engine.hold(true, stopRunning = s.kind == NightStep.Kind.HOT) }
            NightStep.Write -> {
                val h = if (st == ModelState.READY) engine.held() else null
                when {
                    st != ModelState.READY -> loadingMs += dt
                    h != null -> { heldMs += dt; heldBy = h }
                    else -> writingMs += dt
                }
                engine.hold(false, false)
            }
        }
        step = s
        return s
    }

    /** The estimated time to write the [left] lines at this session's pace (ms), or null before there is a pace. */
    fun etaMs(now: Long): Long? = etaMs(engine.left(), engine.count().let { it.lines + it.variants }, now - start, writingMs)

    companion object {
        /**
         * The time to write [left] lines: at the whole session's pace (pauses included) once it has written 3 lines in 10
         * minutes, else at the writing pace with a fifth more for the pauses; null with nothing written yet.
         */
        fun etaMs(left: Int, written: Int, elapsedMs: Long, writingMs: Long): Long? = when {
            left == 0 -> 0L
            left < 0 -> null
            written >= 3 && elapsedMs >= 10 * 60_000L -> left.toLong() * elapsedMs / written
            written >= 1 && writingMs > 0 -> left.toLong() * writingMs * 5 / (written * 4L)
            else -> null
        }

        /** "3 h 20 min", "12 min", "under a minute". */
        fun duration(ms: Long): String {
            val m = ms / 60_000L
            return when {
                m < 1 -> "under a minute"
                m < 60 -> "$m min"
                m < 48 * 60 -> "${m / 60} h ${m % 60} min"
                else -> "${m / (24 * 60)} days ${(m / 60) % 24} h"
            }
        }
    }
}
