package com.timerpg.street.upgrade

/**
 * Whether the light model's MTP file (the in-play model, 2.67 GB) downloads by itself now: on Wi-Fi (an unmetered
 * network) when there is room, resuming its part file; on mobile data, with too little room, or after the player
 * paused it, it waits for the owner (the boot card asks). Pure, unit tested (UpgradesTest).
 */
object AutoFetch {
    enum class Go {
        /** In place: nothing to do. */
        HERE,
        /** Downloading now. */
        RUNNING,
        /** Start (or resume) it now. */
        START,
        /** No network: it starts when there is Wi-Fi (nothing to ask). */
        WAIT_NETWORK,
        /** Only mobile data: asks before using it. */
        ASK_METERED,
        /** Not enough room: [Input.remainingBytes] plus a margin needed. */
        ASK_SPACE,
        /** The player paused it this session (the AI panel's "Pause"): not started again until asked or the next start. */
        PAUSED,
        /** The last try failed this session ([Input.failures] of [MAX_FAILURES]): waits for the owner's "Do it". */
        FAILED,
    }

    data class Input(
        val here: Boolean,
        val downloading: Boolean,
        val connected: Boolean,
        val unmetered: Boolean,
        val freeBytes: Long,
        val remainingBytes: Long,
        val pausedByPlayer: Boolean = false,
        /** The owner said yes to mobile data (the card's "Do it"), this session. */
        val meteredOk: Boolean = false,
        /** Automatic tries that failed this session. */
        val failures: Int = 0,
    )

    /** Kept free beside the download (as [com.timerpg.street.dialogue.ModelDownloads.download] checks). */
    const val MARGIN_BYTES = 128L shl 20
    /** A download that fails (four connection attempts each) is tried again by itself this many times a session. */
    const val MAX_FAILURES = 3

    fun decide(i: Input): Go = when {
        i.here -> Go.HERE
        i.downloading -> Go.RUNNING
        i.pausedByPlayer -> Go.PAUSED
        i.freeBytes < i.remainingBytes + MARGIN_BYTES -> Go.ASK_SPACE
        i.failures >= MAX_FAILURES -> Go.FAILED
        !i.connected -> Go.WAIT_NETWORK
        !i.unmetered && !i.meteredOk -> Go.ASK_METERED
        else -> Go.START
    }
}
