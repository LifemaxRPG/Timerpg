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
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "npc-memory").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val file = File(ctx.filesDir, "memories.json")
    private val idle = Runnable { worker.execute { embedder.close() } }
    @Volatile private var loadedFile = false

    val modelFile: File get() = File(ModelDownloads.dir(ctx), MODEL)
    val installed: Boolean get() = modelFile.isFile
    val count: Int get() = memory.size

    private fun ensure(): Boolean {
        if (!loadedFile) {
            loadedFile = true
            runCatching { if (file.isFile) memory.load(file.readText()) }.onFailure { Log.w(TAG, "memories unreadable: $it") }
        }
        if (!embedder.loaded && installed) embedder.load(modelFile)
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
                    if (q == null) emptyList() else NpcMemory.asFacts(memory.recall(npc, q, System.currentTimeMillis()))
                }
            } catch (t: Throwable) { Log.w(TAG, "recall: $t"); emptyList() }
            main.post { onFacts(facts) }
        }
    }

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

    fun release() { main.removeCallbacks(idle); worker.execute { embedder.close() } }

    companion object {
        private const val TAG = "NpcMemory"
        const val MODEL = "embed/Qwen3-Embedding-0.6B-Q8_0.gguf"
        private const val IDLE_MS = 60_000L
        /** Qwen3-Embedding's query instruction (stored memories are plain text). */
        const val QUERY = "Instruct: Given what the player says now, find what this person remembers about it\nQuery: "
    }
}
