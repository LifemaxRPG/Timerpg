package com.timerpg.street.dialogue

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The bank of lines already decided, keyed by their fingerprint: a hash of the whole prompt (the person's fact sheet, the
 * topic's question and the example answer) with the generation settings ([keyOf]). Each entry holds the model's
 * validated lines (up to [VARIANTS], the extra ones written overnight, rotated so a repeat visit hears another), or
 * "template" when the model's reply failed the check (so it is not tried again and again).
 *
 * Staleness: an entry also records its slot ("town/year/npc/intent", [BankPlan.slotOf]), what it is the line for. When
 * that person's facts change (another year, a changed relationship, a new export), the slot's prompt and so its
 * fingerprint change: the old entry is no longer found under the new key (it is not used), the new key has no line (it
 * is queued again), and every other person's lines keep their keys (they stay valid). [bank] counts such entries as
 * stale; they are kept a while ([retain]) because the world may come back to those facts.
 *
 * Kept in memory and in [file] (JSON), so lines survive restarts. Reads are safe from any thread; [load] and [save] run
 * on the dialogue worker. Two processes may hold the bank (the game, and Writer mode or the overnight writer in their
 * own process): [save] takes a lock on the file and first merges what the other one saved,
 * so neither loses the other's lines. Pure Kotlin and org.json (tested on the JVM).
 *
 * [Entry.light]: a line written by the light model (Writer mode, when the main model's copy does not fit): used like any
 * other, and rewritten by the main model when it writes ([replaceLine]).
 */
class ReplyCache(private val file: File) {
    /**
     * [lines] empty: use the template. [slot]: what it is the line for ("" when not known, from an old cache).
     * [tries]: variant attempts that failed (rejected, or the same as a line already there). [shown]: times a line of
     * this entry was given out (the rotation), [last] the variant given out last (-1 none).
     */
    class Entry(lines: List<String>, @Volatile var slot: String = "", @Volatile var tries: Int = 0, @Volatile var shown: Int = 0, @Volatile var last: Int = -1,
                @Volatile var light: Boolean = false) {
        @Volatile var lines: List<String> = lines
            internal set
        /** The first line (the one written first), or null for the template. */
        val line: String? get() = lines.firstOrNull()
    }

    private val map = ConcurrentHashMap<String, Entry>()

    /** Told of a damaged or unwritable file (the engine logs it). */
    var warn: (String) -> Unit = {}

    fun get(key: String): Entry? = map[key]
    val size: Int get() = map.size

    fun putLine(key: String, line: String, slot: String = "", light: Boolean = false) {
        val e = map[key]
        if (e != null && e.lines.isNotEmpty()) { if (slot.isNotEmpty()) e.slot = slot; return }
        if (e == null && map.size >= MAX) return
        map[key] = Entry(listOf(line), slot.ifEmpty { e?.slot ?: "" }, light = light)
        dropped.remove(key)
    }

    /** The main model's line in place of the light model's (its variants go too: the main model writes its own). */
    fun replaceLine(key: String, line: String, slot: String = "") {
        val e = map[key]
        if (e == null) return putLine(key, line, slot)
        synchronized(e) { e.lines = listOf(line); e.light = false; e.tries = 0; e.last = -1; if (slot.isNotEmpty()) e.slot = slot }
    }

    fun putTemplate(key: String, slot: String = "") {
        if (map.containsKey(key) || map.size >= MAX) return
        map[key] = Entry(emptyList(), slot)
        dropped.remove(key)
    }

    /** Records [slot] for a key whose entry has none yet (entries from before slots were kept). */
    fun setSlot(key: String, slot: String) { map[key]?.let { if (it.slot.isEmpty()) it.slot = slot } }

    // ---- variants

    /** [line] said in other words than every line already there (case, spacing and punctuation aside)? */
    fun isNew(key: String, line: String): Boolean {
        val n = norm(line)
        return map[key]?.lines?.none { norm(it) == n } ?: true
    }

    /** Adds another line for [key] (an entry with a model line only); false if it is not new or the entry is full. */
    fun addVariant(key: String, line: String, cap: Int = VARIANTS): Boolean {
        val e = map[key] ?: return false
        synchronized(e) {
            if (e.lines.isEmpty() || e.lines.size >= cap || !isNew(key, line)) return false
            e.lines = e.lines + line
        }
        return true
    }

    /** A variant attempt for [key] failed (rejected, or a line already there). */
    fun variantFailed(key: String) { map[key]?.let { synchronized(it) { it.tries++ } } }

    /** [key] should get another variant: it has a model line, fewer than [cap] lines, and has not failed [maxTries] times. */
    fun wantsVariant(key: String, cap: Int = VARIANTS, maxTries: Int = VARIANT_TRIES): Boolean {
        val e = map[key] ?: return false
        return e.lines.isNotEmpty() && e.lines.size < cap && e.tries < maxTries
    }

    /** No more variants will be written for [key] (full, or given up, or the template). */
    fun variantsDone(key: String, cap: Int = VARIANTS, maxTries: Int = VARIANT_TRIES): Boolean {
        val e = map[key] ?: return false
        return e.lines.isEmpty() || e.lines.size >= cap || e.tries >= maxTries
    }

    /**
     * The line to give out for [key] now, rotating through its variants (each visit the next, never the same twice in a
     * row while there are two or more), or null for the template / no entry.
     */
    fun next(key: String): String? {
        val e = map[key] ?: return null
        synchronized(e) {
            val ls = e.lines
            if (ls.isEmpty()) return null
            val i = pick(ls.size, e.shown, e.last)
            e.shown++
            e.last = i
            return ls[i]
        }
    }

    // ---- counts

    /** Entries with a model line, entries that stay the template, model lines in all (variants included), and entries
     *  written by the light model. */
    val lines: Int get() = map.values.count { it.lines.isNotEmpty() }
    val allLines: Int get() = map.values.sumOf { it.lines.size }
    val light: Int get() = map.values.count { it.light && it.lines.isNotEmpty() }
    val templates: Int get() = map.values.count { it.lines.isEmpty() }

    /** What the bank holds for a [plan] (key to slot of every line the game can ask for now), see [Bank]. */
    fun bank(plan: Map<String, String>, cap: Int = VARIANTS, maxTries: Int = VARIANT_TRIES): Bank {
        val planSlots = plan.values.toHashSet()
        var ready = 0; var templ = 0; var missing = 0; var variants = 0; var full = 0; var stale = 0; var light = 0
        val years = sortedSetOf<Int>()
        val staleSlots = sortedSetOf<String>()
        for ((k, slot) in plan) {
            val e = map[k]
            when {
                e == null -> missing++
                e.lines.isEmpty() -> templ++
                else -> {
                    ready++
                    if (e.light) light++
                    variants += e.lines.size
                    if (variantsDone(k, cap, maxTries)) full++
                    BankPlan.yearOf(slot)?.let { years += it }
                }
            }
        }
        for ((k, e) in map) if (e.slot.isNotEmpty() && e.slot in planSlots && plan[k] != e.slot) { stale++; staleSlots += e.slot }
        return Bank(plan.size, ready, templ, missing, variants, full, stale, years.toList(), staleSlots.toList(), light)
    }

    /** Forget the rejected ones (a new model gets its own try). */
    fun dropTemplates() { map.entries.removeIf { e -> e.value.lines.isEmpty().also { if (it) dropped += e.key } } }

    /** Keys this cache let go of on purpose ([retain], [dropTemplates]): a merge does not bring them back. */
    private val dropped = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Keep only the lines of these prompts (the dialogue files changed), and the stale ones of [slots] still in the game
     * (their facts may come back: another year, a relationship undone), at most [MAX_STALE] of those.
     */
    fun retain(keys: Set<String>, slots: Set<String> = emptySet()) {
        var kept = 0
        map.entries.removeIf { (k, e) ->
            if (k in keys) false
            else if (e.slot.isNotEmpty() && e.slot in slots && e.lines.isNotEmpty() && kept < MAX_STALE) { kept++; false }
            else { dropped += k; true }
        }
    }

    fun load() {
        if (!file.isFile) return
        try {
            map.putAll(read(file.readText()))
        } catch (e: Exception) {
            // a damaged cache is only lost lines: start again
            warn("reply cache unreadable, starting empty: $e")
            map.clear()
        }
    }

    private fun read(text: String): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        val j = JSONObject(text)
        when (j.optInt("v")) {
            1 -> {
                // the first cache: one line per prompt, no slots
                val lines = j.optJSONObject("lines")
                if (lines != null) for (k in lines.keys()) lines.optString(k).takeIf { it.isNotEmpty() }?.let { out[k] = Entry(listOf(it)) }
                val templates = j.optJSONArray("templates")
                if (templates != null) for (i in 0 until templates.length()) templates.optString(i).takeIf { it.isNotEmpty() }?.let { out[it] = Entry(emptyList()) }
            }
            VERSION -> {
                val es = j.optJSONObject("entries") ?: return out
                for (k in es.keys()) {
                    val o = es.optJSONObject(k) ?: continue
                    val ls = o.optJSONArray("l")
                    val lines = if (ls == null) emptyList() else (0 until ls.length()).mapNotNull { ls.optString(it).takeIf { s -> s.isNotEmpty() } }
                    out[k] = Entry(lines, o.optString("s"), o.optInt("t"), o.optInt("n"), o.optInt("last", -1), o.optInt("w") == 1)
                }
            }
        }
        return out
    }

    /**
     * What another process saved since this one last read the file: its new entries and its longer ones (more variants,
     * a model line where this one has the template, the main model's line over the light one's) come in; keys this one
     * let go of on purpose stay out. Returns the entries taken.
     */
    internal fun merge(other: Map<String, Entry>): Int {
        var n = 0
        for ((k, d) in other) {
            if (k in dropped) continue
            val e = map[k]
            if (e == null) { if (map.size < MAX) { map[k] = d; n++ }; continue }
            synchronized(e) {
                when {
                    d.lines.isEmpty() -> {}
                    e.lines.isEmpty() || (e.light && !d.light) -> { e.lines = d.lines; e.light = d.light; e.tries = d.tries; n++ }
                    e.light == d.light && d.lines.any { l -> e.lines.none { norm(it) == norm(l) } } -> {
                        val add = d.lines.filter { l -> e.lines.none { norm(it) == norm(l) } }
                        e.lines = (e.lines + add).take(maxOf(VARIANTS, e.lines.size)); n++
                    }
                }
                e.tries = maxOf(e.tries, d.tries)
                if (d.shown > e.shown) { e.shown = d.shown; e.last = d.last }
                if (e.slot.isEmpty()) e.slot = d.slot
            }
        }
        return n
    }

    /**
     * Writes the cache (to a temporary file, then renamed over the old one, so a crash never leaves half a file), under a
     * lock on the file shared with the other process, after merging what that one saved since ([merge]).
     */
    fun save() {
        try {
            file.parentFile?.mkdirs()
            synchronized(LOCK) {
                java.io.RandomAccessFile(File(file.path + ".lock"), "rw").use { raf ->
                    raf.channel.lock().use {
                        // (always: a file time cannot tell two saves within the same tick apart)
                        if (file.isFile) runCatching { merge(read(file.readText())) }
                            .onFailure { warn("the saved reply cache could not be merged: $it") }
                        write()
                    }
                }
            }
        } catch (e: Exception) {
            warn("could not save the reply cache: $e")
        }
    }

    private fun write() {
        val es = JSONObject()
        for ((k, e) in map) {
            val o = JSONObject().put("l", JSONArray(e.lines))
            if (e.slot.isNotEmpty()) o.put("s", e.slot)
            if (e.tries > 0) o.put("t", e.tries)
            if (e.shown > 0) o.put("n", e.shown).put("last", e.last)
            if (e.light) o.put("w", 1)
            es.put(k, o)
        }
        val text = JSONObject().put("v", VERSION).put("entries", es).toString()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) tmp.delete() }
    }

    /** What the bank holds for the game's lines now (see [bank]). */
    data class Bank(
        /** Lines the game can ask for (prompts). */
        val prompts: Int = 0,
        /** Prompts with a model line, prompts that stay the template, prompts not written yet. */
        val ready: Int = 0,
        val templates: Int = 0,
        val missing: Int = 0,
        /** Model lines in all (variants included), and prompts with all the variants they will get. */
        val variants: Int = 0,
        val variantsDone: Int = 0,
        /** Entries written from facts that have changed since (not used; their prompts are queued again), and whose. */
        val stale: Int = 0,
        val years: List<Int> = emptyList(),
        val staleSlots: List<String> = emptyList(),
        /** Prompts whose line the light model wrote (the main model rewrites them when it writes). */
        val light: Int = 0,
    )

    companion object {
        private const val VERSION = 2
        /** One save at a time in this process (a file lock is per process). */
        private val LOCK = Any()
        private const val MAX = 20_000
        private const val MAX_STALE = 4_000
        /** Lines per prompt, the first included (the extra ones written overnight, see com.timerpg.street.night). */
        const val VARIANTS = 4
        /** Failed variant attempts before a prompt gets no more (some prompts allow only one way of saying it). */
        const val VARIANT_TRIES = 4

        /** The cache key of a prompt (with the generation settings, so changing them starts afresh). */
        fun keyOf(prompt: String, settings: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest((settings + "\n" + prompt).toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString { for (b in d.take(16)) { val v = b.toInt() and 0xff; append(hex[v shr 4]).append(hex[v and 15]) } }
        }

        /** The variant to give out of [count], the [shown]-th time, [last] the one given out before (-1 none). */
        fun pick(count: Int, shown: Int, last: Int): Int {
            if (count <= 1) return 0
            val i = Math.floorMod(shown, count)
            return if (i == last) (i + 1) % count else i
        }

        private val NON_WORD = Regex("[^a-z0-9]+")
        private fun norm(s: String) = s.lowercase().replace(NON_WORD, " ").trim()
    }
}

/** The slots of the lines the game can ask for (pure: tested on the JVM). */
object BankPlan {
    /** What a line is for: the town, the year, the person, the topic. */
    fun slotOf(town: String, year: Int, npc: String, intent: String) = "$town/$year/$npc/$intent"

    fun yearOf(slot: String): Int? = slot.split('/').getOrNull(1)?.toIntOrNull()

    /** The lines still to write, counted by year, person (most first) and topic (most first). */
    data class Queue(val byYear: List<Pair<String, Int>>, val byPerson: List<Pair<String, Int>>, val byTopic: List<Pair<String, Int>>) {
        val total: Int get() = byYear.sumOf { it.second }
    }

    /**
     * [slots]: one per line still to write ("town/year/npc/topic", repeated for each variant still wanted); [names]:
     * a person's name by id. Topics: "theme:x" counts as "your themes", "next" as "follow-ups". Persons beyond [people]
     * are summed as "others".
     */
    fun queue(slots: List<String>, names: (String) -> String? = { null }, people: Int = 8): Queue {
        val y = java.util.TreeMap<String, Int>(); val p = HashMap<String, Int>(); val t = HashMap<String, Int>()
        for (s in slots) {
            val parts = s.split('/')
            y.merge(parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "?", 1, Int::plus)
            val id = parts.getOrNull(2) ?: "?"
            p.merge(names(id) ?: id, 1, Int::plus)
            val topic = parts.getOrNull(3) ?: "?"
            t.merge(when { topic.startsWith("theme:") -> "your themes"; topic == "next" -> "follow-ups"; else -> topic }, 1, Int::plus)
        }
        val ps = p.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key to it.value }
        val top = ps.take(people) + if (ps.size > people) listOf("others (${ps.size - people})" to ps.drop(people).sumOf { it.second }) else emptyList()
        return Queue(y.entries.map { it.key to it.value }, top, t.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key to it.value })
    }

    /** Every prompt of [d] (fingerprint by [keyOf]) and its slot; one key per distinct prompt, the first slot kept. */
    fun of(d: DialogueData, keyOf: (String) -> String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (n in d.npcs) for (t in n.topics) {
            val p = t.prompt ?: continue
            m.putIfAbsent(keyOf(p), slotOf(d.town, d.year, n.id, t.intent))
        }
        return m
    }
}
