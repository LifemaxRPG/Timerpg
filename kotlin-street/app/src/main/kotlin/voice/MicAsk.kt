package com.timerpg.street.voice

/**
 * Asking for the microphone, and telling when Android will no longer ask. On Android 11 and later, once the player has
 * said no twice (or ticked "don't ask again" on older versions), `requestPermission` returns "denied" at once and shows
 * nothing: the game's "tap to allow" did nothing on the owner's S25 (Android 17). Android gives no direct way to know
 * this, so the game tells it from what it can see:
 *
 * - [rationaleBefore] / [rationaleAfter]: `shouldShowRequestPermissionRationale` before and after the request. It is
 *   true only after one "no" while Android would still ask again; after the last "no" it is false again.
 * - [elapsedMs]: how long the request took. With no dialog shown the answer comes back within a frame or two; a person
 *   reading and tapping a dialog takes far longer.
 *
 * Blocked: denied, no rationale after, and either the answer came too fast for a dialog to have been shown or the
 * rationale went from true to false (that "no" was the last one Android allows). A dialog dismissed by tapping outside
 * it on a first request (no rationale before or after, but slow) is not blocked: the next tap asks again.
 * Pure (tested on the JVM: MicAskTest); the activity supplies the readings.
 */
object MicAsk {
    enum class Outcome { GRANTED, DENIED, BLOCKED }

    /** An answer faster than this (ms) came without any dialog on screen. */
    const val NO_DIALOG_MS = 400L

    fun outcome(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean, elapsedMs: Long): Outcome = when {
        granted -> Outcome.GRANTED
        rationaleAfter -> Outcome.DENIED
        elapsedMs < NO_DIALOG_MS -> Outcome.BLOCKED
        rationaleBefore -> Outcome.BLOCKED
        else -> Outcome.DENIED
    }

    /** What the HUD says, and what a tap does next ([blocked]: the app's settings page, with this explained). */
    fun label(blocked: Boolean): String =
        if (blocked) "🎤 microphone blocked: tap to open settings" else "🎤 needs the microphone: tap to allow"

    const val SETTINGS_HELP = "Android will not ask again for the microphone. In the settings that open: Permissions, " +
        "Microphone, Allow only while using the app. Then come back to the game."
}
