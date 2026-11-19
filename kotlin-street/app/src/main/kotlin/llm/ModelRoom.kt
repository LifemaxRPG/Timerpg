package com.timerpg.street.llm

import android.content.Context
import android.os.Debug
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who holds memory among the game's models, and what can be let go for the dialogue model.
 *
 * Each model registers a [Resident]: its name, what it holds now (MB, 0 when not loaded) and, when it may be let go for
 * the dialogue model's copy on the NPU, how ([Resident.drop], which returns the MB freed). The dialogue engine asks
 * [dropFor] when its copy is just short of room (the phone's build 321: 42 MB short with the speech model resident), and
 * the AI panel and the breadcrumbs show [summary] (where the game's memory went).
 */
object ModelRoom {
    class Resident(val name: String, val mb: () -> Long, val drop: (() -> Long)? = null, val note: () -> String = { "" })

    private val list = CopyOnWriteArrayList<Resident>()

    /** Adds [r]; the returned function takes it off again. */
    fun register(r: Resident): () -> Unit { list.removeAll { it.name == r.name }; list += r; return { list.remove(r) } }

    /** What is loaded now: name to MB (and its note). */
    fun residents(): List<Triple<String, Long, String>> =
        list.mapNotNull { r -> val m = runCatching { r.mb() }.getOrDefault(0L); if (m > 0) Triple(r.name, m, runCatching { r.note() }.getOrDefault("")) else null }

    /** What could be let go for the dialogue model now (MB): the residents with a [Resident.drop]. */
    fun droppableMb(): Long = list.filter { it.drop != null }.sumOf { runCatching { it.mb() }.getOrDefault(0L) }

    /**
     * Lets go of what can go for the dialogue model (the calling thread; each drop blocks until its model is closed).
     * Returns the MB freed and what went.
     */
    fun dropFor(why: String): Pair<Long, List<String>> {
        var freed = 0L
        val went = ArrayList<String>()
        for (r in list) {
            val d = r.drop ?: continue
            val m = runCatching { d() }.getOrDefault(0L)
            if (m > 0) { freed += m; went += "${r.name} $m MB" }
        }
        if (went.isNotEmpty()) com.timerpg.street.shot.Crumbs.add("memory: let go of ${went.joinToString()} for $why")
        return freed to went
    }

    /**
     * One line: each model resident and its MB, then the game's own memory as Android counts it (total PSS and its parts:
     * Java heap, native heap, graphics, code, other). Debug.getMemoryInfo takes some milliseconds: off the main thread.
     */
    fun summary(ctx: Context): String {
        val models = residents().joinToString(" · ") { (n, m, note) -> "$n $m MB" + if (note.isNotEmpty()) " ($note)" else "" }
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        fun stat(k: String) = (mi.getMemoryStat("summary.$k")?.toLongOrNull() ?: 0L) shr 10
        val game = "game PSS ${mi.totalPss shr 10} MB (Java ${stat("java-heap")}, native ${stat("native-heap")}, graphics ${stat("graphics")}, " +
            "code ${stat("code")}, other ${stat("private-other")}, system ${stat("system")})"
        return "Resident: " + (models.ifEmpty { "no model" }) + " · " + game + " · ${Ram.freeMb(ctx)} MB free"
    }
}
