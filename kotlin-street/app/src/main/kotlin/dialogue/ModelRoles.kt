package com.timerpg.street.dialogue

/**
 * The two dialogue models and what each is for.
 * - [BANK]: the main model, the strongest that fits the phone. It writes the bank of lines ahead of time (overnight,
 *   with the game closed) and answers in play when there is room for it.
 * - [LIVE]: a lighter model of the same family. Beside the built town (about 4 GB free on the S25 Ultra, measured)
 *   the main model's NPU copy (about 6.6 GB with the margin) does not fit and on the CPU it is much too slow, so the
 *   light one answers in play on the NPU: replies to what the player says and lines the bank does not have yet.
 */
enum class ModelRole { LIVE, BANK }

/**
 * Which model loads, and how, for the phone's state: a pure decision (no Android), so it is unit tested
 * (ModelRolesTest). [DialogueEngine.loadModel] asks it before every load.
 */
object ModelRoles {
    class Input(
        /** The game is in front. */
        val foreground: Boolean,
        /** The town (or street) is built: nothing holds the model's load any more. */
        val sceneBuilt: Boolean,
        /** Android's available memory now, in bytes ([android.app.ActivityManager.MemoryInfo.availMem]). */
        val freeBytes: Long,
        /** Android says memory is low. */
        val lowMemory: Boolean = false,
        /** The main model's file to load, its size (0: not on the phone). */
        val bankBytes: Long,
        /** The light model's file, its size (0: not on the phone). */
        val liveBytes: Long,
        /** The chosen device copies the whole model into memory the system cannot take back (NPU, GPU, repacked CPU). */
        val accelerated: Boolean,
        /** The AI panel's switch: the main model for live replies too (for comparison). */
        val forceBank: Boolean = false,
        /** The overnight writer is writing the bank: the main model, whatever else holds. */
        val writing: Boolean = false,
        /** Each model's own memory beside its weights (KV cache, recurrent state, compute): what its last load measured,
         *  else [WORK_BYTES]. */
        val bankWorkBytes: Long = WORK_BYTES,
        val liveWorkBytes: Long = WORK_BYTES,
    )

    sealed interface Choice
    /** Load [role]'s model: [copied] on the chosen device, else on the CPU from the file's mapping. */
    data class Load(val role: ModelRole, val copied: Boolean) : Choice
    /** Load nothing now ([why]); try again later. */
    data class Wait(val why: String, val needBytes: Long = 0) : Choice
    object NoModel : Choice
    /**
     * In play without the light model on the phone, and the main model's copy does not fit beside the town: nothing
     * loads (the main model from its file on the CPU writes a line in tens of seconds, which reads as broken); the AI
     * panel says the light model is missing and offers its download. [needBytes]: what the main model's copy needs.
     */
    data class NeedLive(val why: String, val needBytes: Long) : Choice

    /** Room for the model's KV cache, recurrent state and compute buffers beside its weights, until a load has measured
     *  the real size (llama.cpp's own buffer sizes, [com.timerpg.street.llm.NativeLlm.workBytes]). */
    const val WORK_BYTES = 300L shl 20

    /**
     * The free memory a load needs: the whole file when it is [copied], else about half of it (the mapped weights the
     * system can drop and read again), plus [WORK_BYTES] and [com.timerpg.street.llm.Ram.MARGIN_MB] for the game, the
     * town's GPU memory and Android (both crash reports of build 302 were the game killed beside a fresh model).
     */
    fun needBytes(fileBytes: Long, copied: Boolean, workBytes: Long = WORK_BYTES): Long =
        (if (copied) fileBytes else fileBytes / 2) + workBytes + (com.timerpg.street.llm.Ram.MARGIN_MB shl 20)

    fun pick(i: Input): Choice {
        if (i.bankBytes <= 0 && i.liveBytes <= 0) return NoModel
        // the town's build has the memory and the cores to itself
        if (i.foreground && !i.sceneBuilt && !i.writing) return Wait("The town is being built.")
        val play = i.foreground && i.sceneBuilt && !i.writing
        val bank = i.bankBytes > 0
        val live = i.liveBytes > 0
        fun need(t: Load) = if (t.role == ModelRole.BANK) needBytes(i.bankBytes, t.copied, i.bankWorkBytes) else needBytes(i.liveBytes, t.copied, i.liveWorkBytes)
        val tries = ArrayList<Load>()
        fun add(role: ModelRole, copied: Boolean) { if (copied && !i.accelerated) return; tries += Load(role, copied) }
        when {
            // in play without the light model: the main model only where its copy fits (on its device), else nothing:
            // the panel asks for the light model rather than the main one crawling on the CPU from its file
            play && !live && !i.forceBank -> {
                val t = Load(ModelRole.BANK, copied = true)
                if (i.accelerated && !i.lowMemory && i.freeBytes >= need(t)) return t
                return NeedLive("The light model for replies in play is not downloaded, and the main model does not fit beside the town " +
                    "(${i.freeBytes shr 20} MB free, ${need(t) shr 20} MB needed).", need(t))
            }
            // writing the bank (the game behind, overnight): the main model, on the CPU from its file if its copy will
            // not fit; the light one only when it is all there is
            !play || !live || (i.forceBank && bank) -> {
                val r = if (bank) ModelRole.BANK else ModelRole.LIVE
                add(r, true); add(r, false)
            }
            // in play: the main model on the NPU when there is room for it beside the town (the best replies), else the
            // light one there, else the light one on the CPU (still about twice as fast there as the main one)
            else -> {
                if (bank) add(ModelRole.BANK, true)
                add(ModelRole.LIVE, true)
                add(ModelRole.LIVE, false)
            }
        }
        for (t in tries) if (!i.lowMemory && i.freeBytes >= need(t)) return t
        val last = tries.last()
        return Wait("Not enough free memory to load the model (${i.freeBytes shr 20} MB free, about ${need(last) shr 20} MB needed with room for the game).", need(last))
    }

    /** What [role]'s copy on its device needs (for the breadcrumbs and the panel). */
    fun copyNeed(i: Input, role: ModelRole): Long =
        if (role == ModelRole.BANK) needBytes(i.bankBytes, true, i.bankWorkBytes) else needBytes(i.liveBytes, true, i.liveWorkBytes)
}
