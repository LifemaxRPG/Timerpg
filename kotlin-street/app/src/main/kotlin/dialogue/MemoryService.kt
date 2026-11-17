package com.timerpg.street.dialogue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.timerpg.street.llm.AiLane
import com.timerpg.street.llm.NativeEmbedder
import java.io.File
import java.util.concurrent.Executors

/**
 * The people's memories, on the phone: [NpcMemory] saved in filesDir/memories.json, the memory model (Qwen3-Embedding)
 * loaded ahead of need ([prewarm], when someone to talk to is near) and let go after a minute idle. Its runs take turns
 * with the other models ([AiLane]). Callbacks come on the main thread.
 */
class MemoryService(private val ctx: Context) {
    private val memory = NpcMemory()
    private val embedder = NativeEmbedder()
    private val judge = com.timerpg.street.llm.NativeReranker()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "npc-memory").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val file = File(ctx.filesDir, "memories.json")
    private val idle = Runnable { worker.execute { embedder.close(); judge.close() } }
    @Volatile private var loadedFile = false

    val modelFile: File get() = File(ModelDownloads.dir(ctx), MODEL)
    val installed: Boolean get() = modelFile.isFile
    val judgeFile: File get() = File(ModelDownloads.dir(ctx), JUDGE)
    val count: Int get() = memory.size

    private fun ensure(): Boolean {
        if (!loadedFile) {
            loadedFile = true
            runCatching { if (file.isFile) memory.load(file.readText()) }.onFailure { Log.w(TAG, "memories unreadable: $it") }
        }
        // only with room for it (on the NPU the dialogue model holds most of the phone's memory)
        if (!embedder.loaded && installed && freeMb() >= (modelFile.length() shr 20) + com.timerpg.street.llm.Ram.MARGIN_MB)
            AiLane.run("memory") { embedder.load(modelFile) }
        return embedder.loaded
    }

    private fun touch() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }

    /** Someone to talk to is near: get the model ready. */
    fun prewarm() { touch(); worker.execute { ensure() } }

    /** What [npc] remembers that bears on [said] (as fact sheet lines), to [onFacts]. Empty without the model. */
    fun recall(npc: String, said: String, onFacts: (List<String>) -> Unit) {
        touch()
        worker.execute {
            val facts = try {
                if (!ensure()) emptyList()
                else {
                    val q = AiLane.run("memory") { embedder.embed(QUERY + said) }
                    if (q == null) emptyList() else NpcMemory.asFacts(pick(npc, said, q))
                }
            } catch (t: Throwable) { Log.w(TAG, "recall: $t"); emptyList() }
            main.post { onFacts(facts) }
        }
    }

    /**
     * The memories to hand the model: the embedder's closest few, judged by the reranker (Qwen3-Reranker-4B: yes to at
     * most two), plus the last exchange. Measured (tools/memory-eval, 30 memories with look-alikes, 30 lines): 20 of 20
     * references found, nothing recalled for 10 of 10 unrelated lines; the embedder alone recalled something for 8 of the
     * 10. When nothing comes close the judge is not asked (small talk stays quick). Without the judge: the embedder's rule.
     */
    private fun pick(npc: String, said: String, q: FloatArray): List<NpcMemory.Entry> {
        val now = System.currentTimeMillis()
        val cands = memory.candidates(npc, q, 4).filter { it.second >= .2f && (it.third >= 1f || memory.countOf(npc) < 6) }
        // the judge only when something comes close and the phone has room for it (it is 2.5 GB); it goes again right after
        if (cands.isEmpty() || !judgeFile.isFile || freeMb() < 3500) return memory.recall(npc, q, now)
        if (!judge.loaded && !AiLane.run("memory") { judge.load(judgeFile) }) return memory.recall(npc, q, now)
        val yes = cands.mapNotNull { (e, _, _) ->
            val p = AiLane.run("memory") { judge.score(JUDGE_TASK, said, "The player said: ${e.said} You answered: ${e.answered}") }
            if (p >= .5f) e to p else null
        }.sortedByDescending { it.second }.take(2).map { it.first }
        judge.close()
        val last = memory.last(npc, now)
        return if (last != null && last !in yes) (listOf(last) + yes).take(3) else yes
    }

    private fun freeMb(): Long = try {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem shr 20
    } catch (e: Exception) { 0L }

    /** [npc] will remember that the player said [said] and they answered [answered]. */
    fun remember(npc: String, said: String, answered: String) {
        touch()
        worker.execute {
            try {
                if (!ensure()) return@execute
                val v = AiLane.run("memory") { embedder.embed("The player said: $said You answered: $answered") } ?: return@execute
                memory.add(NpcMemory.Entry(npc, System.currentTimeMillis(), said, answered, v))
                val tmp = File(file.path + ".part")
                tmp.writeText(memory.toJson())
                tmp.renameTo(file)
            } catch (t: Throwable) { Log.w(TAG, "remember: $t") }
        }
    }

    fun release() { main.removeCallbacks(idle); worker.execute { embedder.close(); judge.close() } }

    companion object {
        private const val TAG = "NpcMemory"
        const val MODEL = "embed/Qwen3-Embedding-0.6B-Q8_0.gguf"
        const val JUDGE = "rerank/Qwen3-Reranker-4B.Q4_K_M.gguf"
        const val JUDGE_TASK = "Given what the player says now, find what this person remembers about it"
        private const val IDLE_MS = 60_000L
        /** Qwen3-Embedding's query instruction (stored memories are plain text). */
        const val QUERY = "Instruct: Given what the player says now, find what this person remembers about it\nQuery: "
    }
}
