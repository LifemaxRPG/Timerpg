package com.timerpg.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cuts a live microphone stream into utterances, cheaply: a voice-activity detector on each [FRAME_MS] frame's loudness
 * (RMS) against a noise floor that follows the room's quiet, with an onset (a few loud frames in a row, so a click or a
 * knock is not speech), a hangover (a short pause inside a sentence does not end it) and a little audio kept from before
 * the onset, so the first sound of a word is not cut. Each finished utterance goes to [onUtterance] (on the thread that
 * calls [feed]); one too short to be a word is dropped, one too long is cut at [maxMs]. Only arithmetic per sample: the
 * speech model runs on what this hands over, never on the stream.
 */
class SpeechSegmenter(
    val rate: Int = 16000,
    /** Loud frames in a row that start an utterance. */
    private val onsetFrames: Int = 4,
    /** Quiet this long ends an utterance. */
    private val hangoverMs: Int = 700,
    /** Audio kept from before the onset. */
    private val prerollMs: Int = 300,
    /** Less voiced sound than this is not a word (a cough, a tap). */
    private val minSpeechMs: Int = 300,
    /** An utterance is cut here (the speech model reads at most this much at once). */
    private val maxMs: Int = 12_000,
    private val onUtterance: (FloatArray) -> Unit,
) {
    private val frame = rate * FRAME_MS / 1000
    private val hangFrames = hangoverMs / FRAME_MS
    private val minVoiced = minSpeechMs / FRAME_MS
    private val maxSamples = rate.toLong() * maxMs / 1000

    /** The room's quiet (RMS), followed while nobody speaks. */
    var floor = START_FLOOR
        private set
    /** Inside an utterance now. */
    var speaking = false
        private set
    /** The last frame's loudness (RMS), for a meter. */
    var level = 0f
        private set

    // the frame being filled
    private val cur = FloatArray(frame)
    private var curN = 0
    // the last frames before an onset (a ring of whole frames)
    private val preFrames = max(onsetFrames, prerollMs / FRAME_MS + onsetFrames)
    private val pre = FloatArray(preFrames * frame)
    private var preHead = 0
    private var preCount = 0
    private var loudRun = 0
    private var quiet = 0
    private var voiced = 0
    private var out = FloatArray(rate * 4)
    private var outN = 0
    private var windowMin = Float.MAX_VALUE
    private var windowN = 0

    /** Takes the next [n] samples of [pcm] (mono, [rate]). */
    fun feed(pcm: FloatArray, n: Int = pcm.size) {
        var i = 0
        while (i < n) {
            val take = minOf(frame - curN, n - i)
            System.arraycopy(pcm, i, cur, curN, take)
            curN += take; i += take
            if (curN == frame) { step(); curN = 0 }
        }
    }

    /** Forgets everything heard so far (an utterance in progress is dropped); the noise floor is kept. */
    fun reset() {
        speaking = false; loudRun = 0; quiet = 0; voiced = 0; outN = 0; preCount = 0; preHead = 0; curN = 0; level = 0f
    }

    private fun step() {
        var s = 0f
        for (k in 0 until frame) s += cur[k] * cur[k]
        val rms = sqrt(s / frame)
        level = rms
        if (!speaking) {
            val loud = rms > max(floor * START_OVER_FLOOR, MIN_START)
            loudRun = if (loud) loudRun + 1 else 0
            if (loudRun >= onsetFrames) {
                // an onset: the utterance starts with what came before it (the onset's own frames are in the ring)
                speaking = true; quiet = 0; voiced = loudRun; outN = 0
                windowMin = Float.MAX_VALUE; windowN = 0
                pushPre()
                for (f in 0 until preCount) append(pre, ((preHead - preCount + f).mod(preFrames)) * frame, frame)
                preCount = 0; loudRun = 0
            } else {
                pushPre()
                // the floor follows the quiet: quickly down, slowly up (a fan switched on is learnt in a second or two; a
                // voice is not, the onset takes it first)
                follow(if (rms < floor) FLOOR_DOWN else FLOOR_UP, rms)
            }
            return
        }
        append(cur, 0, frame)
        // speech dips between syllables and words; a steady noise loud enough to pass for speech (a fan switched on)
        // does not: a whole window without a dip under the going-on level is taken as the room's new quiet, so it ends
        windowMin = minOf(windowMin, rms)
        if (++windowN >= WINDOW_FRAMES) {
            if (windowMin > floor * GO_ON_OVER_FLOOR) floor = (windowMin * .8f).coerceIn(MIN_FLOOR, MAX_FLOOR)
            windowMin = Float.MAX_VALUE; windowN = 0
        }
        if (rms > max(floor * GO_ON_OVER_FLOOR, MIN_GO_ON)) { quiet = 0; voiced++ } else quiet++
        when {
            quiet >= hangFrames -> finish(trimTail = quiet)
            outN >= maxSamples -> finish(trimTail = 0)
        }
    }

    private fun follow(rate: Float, rms: Float) {
        floor = (floor + (rms - floor) * rate).coerceIn(MIN_FLOOR, MAX_FLOOR)
    }

    private fun pushPre() {
        System.arraycopy(cur, 0, pre, preHead * frame, frame)
        preHead = (preHead + 1) % preFrames
        if (preCount < preFrames) preCount++
    }

    private fun append(src: FloatArray, at: Int, len: Int) {
        if (outN + len > out.size) out = out.copyOf(max(out.size * 2, outN + len))
        System.arraycopy(src, at, out, outN, len)
        outN += len
    }

    private fun finish(trimTail: Int) {
        val wasVoiced = voiced
        // the hangover's quiet is dropped, bar a short tail (a word's last sound fades into it)
        val keep = (outN - max(0, trimTail - TAIL_FRAMES) * frame).coerceAtLeast(0)
        val pcm = out.copyOf(keep)
        speaking = false; quiet = 0; voiced = 0; outN = 0; loudRun = 0
        if (out.size > rate * 16) out = FloatArray(rate * 4)   // a long one's buffer is not kept
        if (wasVoiced >= minVoiced) onUtterance(pcm)
    }

    companion object {
        const val FRAME_MS = 20
        /** The floor before anything has been heard (a quiet room through a phone's mic). */
        const val START_FLOOR = .004f
        const val MIN_FLOOR = .0005f
        const val MAX_FLOOR = .08f
        /** Speech starts this far over the floor, and never below [MIN_START] (digital silence has a floor of ~0). */
        const val START_OVER_FLOOR = 3.2f
        const val MIN_START = .01f
        /** And goes on while this far over it. */
        const val GO_ON_OVER_FLOOR = 2f
        const val MIN_GO_ON = .006f
        private const val FLOOR_DOWN = .2f
        private const val FLOOR_UP = .02f
        /** Frames (2 s) in which speech always dips somewhere. */
        private const val WINDOW_FRAMES = 100
        /** Quiet frames kept after the last word. */
        private const val TAIL_FRAMES = 10
    }
}
