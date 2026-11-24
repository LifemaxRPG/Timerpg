package com.timerpg.street.dialogue

/**
 * The two dialogue models and what each is for.
 * - [BANK]: the main model, the strongest that fits the phone. It writes the bank of lines ahead of time (overnight,
 *   with the game closed) and answers in play when there is room for it.
 * - [LIVE]: a lighter model of the same family. Beside the built town (about 4 GB free on the S25 Ultra, measured)
 *   the main model's NPU copy (about 6.6 GB with the margin) does not fit and on the CPU it is much too slow, so the
 *   light one answers in play on the NPU: replies to what the player says and lines the bank does not have yet.
 *
 * The owner's rule (2026-11-23, after a playtest with the light model on the CPU: "useless, far too slow"): **in play,
 * dialogue never runs on the CPU.** It runs on the NPU, or nothing loads ([Wait] with [Wait.device]) and people speak
 * their lines written ahead and their built-in ones until the NPU copy fits. Writing the bank (Writer mode, overnight)
 * and the game behind keep their own rules.
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
        /** The bank is being written (Writer mode, the overnight writer): the main model's copy on its device, else the
         *  light one's there, never the CPU from the file while there is a device. */
        val writing: Boolean = false,
        /** Each model's own memory beside its weights (KV cache, recurrent state, compute): what its last load measured,
         *  else [WORK_BYTES]. */
        val bankWorkBytes: Long = WORK_BYTES,
        val liveWorkBytes: Long = WORK_BYTES,
        /** What Android is expected to find beyond [freeBytes] for a copy on the device when asked (cached apps ended,
         *  swap): [com.timerpg.street.llm.NpuGate]'s figure. Only copies count it, and a load that needs it is a try. */
        val reclaimBytes: Long = 0,
        /** The phone (and build) has the NPU for the model in play. Without it nothing loads in play (no CPU). */
        val npu: Boolean = accelerated,
        /** Without [npu]: why, specifically (the NPU library not in the build, no HTP session, ...), for the HUD and reports. */
        val npuWhy: String = "",
        /** What each model's device copy really takes of its file (weights copied, as its last load on this device
         *  measured them), else -1: the whole file. The light model with its tied output head kept mapped copies
         *  ~2004 of its 2463 MB (NativeLlm.headMapped). */
        val bankCopyBytes: Long = -1,
        val liveCopyBytes: Long = -1,
        /** The free memory kept beside a device copy (the adaptive margin, [com.timerpg.street.llm.NpuGate.margin]); a
         *  load on the CPU from the file keeps the flat [com.timerpg.street.llm.Ram.MARGIN_MB]. */
        val marginBytes: Long = com.timerpg.street.llm.Ram.MARGIN_MB shl 20,
        /** The session is starting and the town is not built yet: the light model's NPU copy may load first, when it fits
         *  (MEMORY_AUDIT.md, M1: at app start 3665 MB free plus what Android finds covers it; the town is then built
         *  beside it). Only the light model: the main one's copy is never loaded ahead of the town. */
        val early: Boolean = false,
    )

    sealed interface Choice
    /** Load [role]'s model: [copied] on the chosen device, else on the CPU from the file's mapping. [tried]: the copy
     *  fits only with what Android is expected to find ([Input.reclaimBytes]): the load is watched, and falls back. */
    data class Load(val role: ModelRole, val copied: Boolean, val tried: Boolean = false) : Choice
    /** Load nothing now ([why]); try again later. [device]: in play, waiting for room for the NPU copy (never the CPU):
     *  the engine keeps looking every 30 s with no limit, and the HUD says so. */
    data class Wait(val why: String, val needBytes: Long = 0, val device: Boolean = false, val noNpu: Boolean = false) : Choice
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
    fun needBytes(fileBytes: Long, copied: Boolean, workBytes: Long = WORK_BYTES, marginBytes: Long = com.timerpg.street.llm.Ram.MARGIN_MB shl 20,
                  copyBytes: Long = -1): Long =
        (if (copied) (if (copyBytes > 0) copyBytes else fileBytes) else fileBytes / 2) + workBytes + marginBytes

    /** The share of the light model's own memory that is compute buffers, measured on the S25 at batch 512 (0.1.598:
     *  KV 19 + state 50 + compute 318 = 388 MB): for a measure that did not keep its parts apart. */
    const val COMPUTE_SHARE_512 = 318.0 / 388.0

    /**
     * A model's own memory ([workBytes], of which [computeBytes] compute buffers) measured at prompt batch [fromBatch],
     * estimated for [toBatch] until a load there measures it. The compute buffers hold a batch's activations, so they
     * follow the batch; a tenth of them is taken as fixed (graph overhead, the one row of logits), so the estimate errs
     * high: 318 MB at 512 is estimated 103 MB at 128 (MEMORY_AUDIT.md, M2, expected ~80-100). The cache and the
     * recurrent state do not depend on the batch.
     */
    fun scaleWork(workBytes: Long, computeBytes: Long, fromBatch: Int, toBatch: Int): Long {
        if (fromBatch <= 0 || toBatch <= 0 || fromBatch == toBatch) return workBytes
        val c = computeBytes.coerceIn(0, workBytes)
        return workBytes - c + (c * (0.1 + 0.9 * toBatch / fromBatch.toDouble())).toLong()
    }

    fun pick(i: Input): Choice {
        if (i.bankBytes <= 0 && i.liveBytes <= 0) return NoModel
        // the town's build has the memory and the cores to itself (unless the light model's NPU copy goes first: [Input.early])
        if (i.foreground && !i.sceneBuilt && !i.writing && !(i.early && i.liveBytes > 0 && i.npu && i.accelerated)) return Wait("The town is being built.")
        val early = i.foreground && !i.sceneBuilt && !i.writing
        val play = i.foreground && !i.writing
        val bank = i.bankBytes > 0 && !early
        val live = i.liveBytes > 0
        fun need(t: Load) = if (t.role == ModelRole.BANK) needBytes(i.bankBytes, t.copied, i.bankWorkBytes, if (t.copied) i.marginBytes else com.timerpg.street.llm.Ram.MARGIN_MB shl 20, i.bankCopyBytes)
            else needBytes(i.liveBytes, t.copied, i.liveWorkBytes, if (t.copied) i.marginBytes else com.timerpg.street.llm.Ram.MARGIN_MB shl 20, i.liveCopyBytes)
        val tries = ArrayList<Load>()
        fun add(role: ModelRole, copied: Boolean) { if (copied && !i.accelerated) return; tries += Load(role, copied) }
        when {
            // in play without the light model: the main model only where its copy fits (on its device), else nothing:
            // the panel asks for the light model rather than the main one crawling on the CPU from its file
            play && !live && !i.forceBank -> {
                val t = Load(ModelRole.BANK, copied = true)
                if (i.npu && !i.lowMemory && i.freeBytes >= need(t)) return t
                if (i.npu && !i.lowMemory && i.reclaimBytes > 0 && i.freeBytes + i.reclaimBytes >= need(t)) return t.copy(tried = true)
                return NeedLive("The light model for replies in play is not downloaded, and the main model does not fit beside the town " +
                    "(${i.freeBytes shr 20} MB free, ${need(t) shr 20} MB needed).", need(t))
            }
            // writing the bank (Writer mode, or overnight with the game closed) on a phone with an NPU: the main model's
            // copy there, else the light model's copy there (its lines are marked and rewritten by the main one later),
            // never either from its file on the CPU (several times slower: the owner's nights wrote nothing that way)
            i.writing && i.accelerated -> {
                if (bank) add(ModelRole.BANK, true)
                if (live) add(ModelRole.LIVE, true)
            }
            // in play: the NPU copy or nothing (the owner's rule: never the CPU). The main model's copy when there is room
            // for it beside the town (the best replies; the panel's switch: only it), else the light one's, tried with what
            // Android finds when asked; else wait for room, people speaking their lines written ahead meanwhile
            play -> {
                // (not memory: no NPU found at all. The engine says which part is missing and looks again)
                if (!i.npu) return Wait("No NPU found for the model in play" + (if (i.npuWhy.isNotEmpty()) " (${i.npuWhy})" else "") +
                    ", and in play it never runs on the CPU: people speak their lines written ahead and their built-in ones.",
                    device = true, noNpu = true)
                if (bank) tries += Load(ModelRole.BANK, true)
                if (live && !(i.forceBank && bank)) tries += Load(ModelRole.LIVE, true)
            }
            // the game behind (or no accelerator): the main model, on the CPU from its file if its copy will not fit; the
            // light one only when it is all there is
            !play || !live || (i.forceBank && bank) -> {
                val r = if (bank) ModelRole.BANK else ModelRole.LIVE
                add(r, true); add(r, false)
            }
            else -> {}
        }
        if (tries.isEmpty()) return Wait("Nothing can load here.", device = play)
        // each in order: fits in the free memory; else (a copy) fits with what Android finds when asked: tried (the light
        // model's NPU copy, tried, is far better than it on the CPU from the file; writing, the main model's copy tried
        // is better than the light one's). In play with the light model there, the main model's copy is never a try (the
        // riskiest load beside the town): the light one's goes first
        // (and only with the game in front or writing: a process behind is the first Android ends)
        fun tryable(t: Load) = t.copied && i.reclaimBytes > 0 && i.freeBytes + i.reclaimBytes >= need(t) &&
            (i.foreground || i.writing) && !(play && live && t.role == ModelRole.BANK && !i.forceBank)
        if (!i.lowMemory) for (t in tries) {
            if (i.freeBytes >= need(t)) return t
            if (tryable(t)) return t.copy(tried = true)
        }
        val last = tries.last()
        // (the NPU is there: it is memory, now; the engine looks again every 30 s)
        if (play) return Wait("The NPU is there, but the ${if (last.role == ModelRole.LIVE) "light" else "main"} model's copy does not fit yet: " +
            "waiting for NPU memory (${i.freeBytes shr 20} MB free, ${need(last) shr 20} MB needed" +
            (if (i.reclaimBytes > 0) " counting ${i.reclaimBytes shr 20} MB Android is expected to find" else "") +
            (if (i.lowMemory) "; Android says memory is low" else "") + "): in play the model never runs on the CPU.", need(last), device = true)
        return Wait("Not enough free memory to load the model (${i.freeBytes shr 20} MB free, about ${need(last) shr 20} MB needed with room for the game).", need(last))
    }

    /** What [role]'s copy on its device needs (for the breadcrumbs and the panel). */
    fun copyNeed(i: Input, role: ModelRole): Long =
        if (role == ModelRole.BANK) needBytes(i.bankBytes, true, i.bankWorkBytes, i.marginBytes, i.bankCopyBytes)
        else needBytes(i.liveBytes, true, i.liveWorkBytes, i.marginBytes, i.liveCopyBytes)
}
