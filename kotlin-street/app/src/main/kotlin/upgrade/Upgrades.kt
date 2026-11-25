package com.timerpg.street.upgrade

/**
 * One-time steps the game wants in place ("upgrade checks"): a new model file, a preparation of it, a setting that is
 * recommended. The owner's request (2026-11-25): nothing to go hunting for in the settings. Each step registers a
 * [Check] that says where it stands ([Status]) and what "Do it" does; the boot card lists what needs the owner's say-so,
 * what runs by itself shows nothing until it finishes, and then a one-line toast once ("AI model prepared: English-first,
 * 3.9 s"). Plain Kotlin (no Android), so the whole list logic is unit tested with fake checks (UpgradesTest); the
 * game's own checks are in [AiUpgrades], the card and the toast in GameActivity.
 */
object Upgrades {
    enum class State {
        /** In place. With a [Status.doneKey] and [Status.doneNote], the toast says so once. */
        DONE,
        /** Going, or will go by itself (a download on Wi-Fi, a preparation waiting for a conversation to end): nothing shown. */
        RUNNING,
        /** Needs the owner's say-so (a download on mobile data, low storage, a recommended setting that is off). */
        ASK,
        /** Tried and failed: "Do it" tries again. */
        FAILED,
        /** Does not apply now (nothing to prepare yet). */
        NONE,
    }

    /**
     * Where a check stands. [text]: plain words for the card and the AI panel; [action]: what "Do it" does, in words
     * ("Download on mobile data"); [askKey]: which question this is ("Don't ask again" silences this question, not the
     * check: a later, different one still asks); [doneKey]: which finished thing this is (the toast is said once per
     * key: a second preparation, of a new file, gets its own); [doneNote]: the toast's line.
     */
    data class Status(
        val state: State,
        val text: String = "",
        val action: String = "Do it",
        val askKey: String = "",
        val doneKey: String = "",
        val doneNote: String = "",
    )

    interface Check {
        /** Stable: the stored answers are keyed by it. */
        val id: String
        /** Where it stands now (cheap: called every few seconds off the main thread). */
        fun status(): Status
        /** The card's "Do it". */
        fun act()
    }

    /** A line of the card: [check]'s [status]; [key] is what "Later" and "Don't ask again" remember. */
    data class Item(val check: Check, val status: Status) {
        val key: String get() = key(check.id, status)
    }

    fun key(id: String, s: Status) = if (s.askKey.isEmpty()) id else "$id/${s.askKey}"

    /** What is remembered across runs: questions not to ask again, finished things already announced. */
    interface Store {
        fun never(): Set<String>
        fun setNever(keys: Set<String>)
        fun announced(): Set<String>
        fun setAnnounced(keys: Set<String>)
    }

    class MemoryStore : Store {
        private var n = emptySet<String>()
        private var a = emptySet<String>()
        override fun never() = n
        override fun setNever(keys: Set<String>) { n = keys }
        override fun announced() = a
        override fun setAnnounced(keys: Set<String>) { a = keys }
    }

    /** The items for the card from [statuses]: what asks or failed, less those answered "never" or "later". */
    fun card(statuses: List<Pair<Check, Status>>, never: Set<String>, later: Set<String>): List<Item> =
        statuses.filter { (_, s) -> s.state == State.ASK || s.state == State.FAILED }
            .map { (c, s) -> Item(c, s) }
            .filter { it.key !in never && it.key !in later }

    /** The toasts due: done with a note, its key not yet announced. */
    fun toasts(statuses: List<Pair<Check, Status>>, announced: Set<String>): List<Pair<String, String>> =
        statuses.mapNotNull { (c, s) ->
            val k = if (s.doneKey.isEmpty()) "" else "${c.id}:${s.doneKey}"
            if (s.state == State.DONE && k.isNotEmpty() && s.doneNote.isNotEmpty() && k !in announced) k to s.doneNote else null
        }

    /**
     * The game's list of checks and the card's state. [refresh] (off the main thread) reads every check; the first refresh
     * after start that finds something to ask opens the card ([open]): the boot card. After that the card opens again by
     * itself only for a new failure (a question that comes up in play, such as leaving Wi-Fi, waits for the next start,
     * or the AI panel, where every check is listed). "Later" hides an item until the next start; "Don't ask again" for
     * good (the AI panel still lists it, with its button).
     */
    class Board(private val store: Store) {
        private val checks = ArrayList<Check>()
        private val later = HashSet<String>()
        private var booted = false
        private var shownFailures = HashSet<String>()

        /** The card is open, and its items. */
        @Volatile var open = false
            private set
        @Volatile var items: List<Item> = emptyList()
            private set
        /** Every check's last status (the AI panel's list). */
        @Volatile var all: List<Pair<Check, Status>> = emptyList()
            private set

        fun register(c: Check) { synchronized(this) { if (checks.none { it.id == c.id }) checks += c } }

        /** Reads every check; returns the toasts due now (each said once: marked announced here). */
        fun refresh(): List<String> = synchronized(this) {
            val st = checks.map { c -> c to runCatching { c.status() }.getOrElse { Status(State.NONE, "check failed: ${it.message}") } }
            all = st
            val now = card(st, store.never(), later)
            if (!booted) { if (now.isNotEmpty()) open = true; booted = true; shownFailures += now.filter { it.status.state == State.FAILED }.map { it.key } }
            else {
                val fresh = now.filter { it.status.state == State.FAILED && it.key !in shownFailures }
                if (fresh.isNotEmpty()) { open = true; shownFailures += fresh.map { it.key } }
            }
            if (now.isEmpty()) open = false
            items = now
            val due = toasts(st, store.announced())
            if (due.isNotEmpty()) store.setAnnounced(store.announced() + due.map { it.first })
            due.map { it.second }
        }

        /** The card's buttons. [doIt] runs the check's action (the caller refreshes after). */
        fun doIt(item: Item) { runCatching { item.check.act() }; hide(item.key) }
        fun later(item: Item) = hide(item.key)
        fun never(item: Item) { synchronized(this) { store.setNever(store.never() + item.key) }; hide(item.key) }
        /** Close the card without answering: everything on it counts as "Later". */
        fun close() { synchronized(this) { items.forEach { later += it.key }; items = emptyList(); open = false } }
        /** The questions answered "Don't ask again". */
        val neverKeys: Set<String> get() = store.never()
        /** The AI panel's "ask again" for answers given "never". */
        fun forgetNever() { synchronized(this) { store.setNever(emptySet()) } }

        private fun hide(key: String) = synchronized(this) {
            later += key
            items = items.filter { it.key != key }
            if (items.isEmpty()) open = false
        }
    }
}
