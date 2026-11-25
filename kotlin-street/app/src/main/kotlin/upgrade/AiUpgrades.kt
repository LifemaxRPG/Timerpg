package com.timerpg.street.upgrade

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.timerpg.street.dialogue.DialogueEngine
import com.timerpg.street.dialogue.ModelDownloads
import com.timerpg.street.dialogue.ModelFiles
import com.timerpg.street.dialogue.ModelPrep
import com.timerpg.street.voice.NpcVoiceService

/**
 * The game's upgrade checks for the AI ([Upgrades]): the light model's MTP file (downloaded by itself on Wi-Fi, [AutoFetch]),
 * its one-time English-first preparation (by itself, [com.timerpg.street.dialogue.ModelPrepJob]), MTP guesses in play
 * (a recommended setting), and the rest of the AI's files. A new one-time step registers a [Upgrades.Check] here (or
 * with [board] from elsewhere) instead of waiting behind a button in the settings.
 *
 * One per process; [attach] gives it the activity's voice service (the downloads run there, with their progress in
 * the AI panel). [tick] (off the main thread, every few seconds once the town is shown) starts the automatic download
 * when it may, and refreshes the board.
 */
object AiUpgrades {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var engine: DialogueEngine? = null
    @Volatile private var voices: NpcVoiceService? = null
    @Volatile private var board0: Upgrades.Board? = null

    // the automatic download, this session
    @Volatile private var meteredOk = false
    @Volatile private var failures = 0
    @Volatile private var ours = false
    @Volatile private var stopLight = false
    @Volatile private var wantAll = false
    @Volatile private var lastGo: AutoFetch.Go = AutoFetch.Go.HERE

    val board: Upgrades.Board get() = board0 ?: error("AiUpgrades.attach first")

    fun attach(ctx: Context, engine: DialogueEngine, voices: NpcVoiceService) {
        app = ctx.applicationContext
        this.engine = engine
        this.voices = voices
        if (board0 == null) synchronized(this) {
            if (board0 == null) board0 = Upgrades.Board(PrefsStore(ctx.applicationContext)).apply {
                register(lightDownload); register(lightPrep); register(liveMtp); register(aiFiles)
            }
        }
    }

    /** Starts the automatic download when it may; reads every check. Returns the toasts due (each once, ever). */
    fun tick(): List<String> {
        val v = voices ?: return emptyList()
        val ctx = app ?: return emptyList()
        // our download ended: a failure counts (a pause on leaving Wi-Fi does not)
        if (ours && v.downloading < 0f && v.downloadingWhat == null) {
            ours = false
            val r = v.lastResult
            if (r is ModelDownloads.Result.Failed && !r.cancelled) failures++
        }
        // the whole set was asked for while the light file downloaded alone: that one stops (its part stays), all start
        if (wantAll) {
            if (v.downloadingWhat == "light") stopLight = true
            else if (v.downloading < 0f) { wantAll = false; stopLight = false; main.post { v.download { engine?.reload() } } }
        }
        val go = AutoFetch.decide(fetchInput(ctx, v))
        lastGo = go
        if (go == AutoFetch.Go.START && !wantAll) startLight(ctx, v)
        return board.refresh()
    }

    private fun fetchInput(ctx: Context, v: NpcVoiceService): AutoFetch.Input {
        val dir = ModelFiles.dir(ctx)
        val (connected, unmetered) = network(ctx)
        return AutoFetch.Input(
            here = ModelPrep.isHere(dir, ModelPrep.SOURCES[0]), downloading = v.downloading >= 0f,
            connected = connected, unmetered = unmetered, freeBytes = dir.usableSpace,
            remainingBytes = ModelDownloads.remainingIn(dir, lightItems), pausedByPlayer = v.pausedByPlayer,
            meteredOk = meteredOk, failures = failures,
        )
    }

    private val lightItems get() = ModelDownloads.ITEMS.filter { it.path == ModelFiles.LIVE_MTP }

    private fun startLight(ctx: Context, v: NpcVoiceService) {
        ours = true; stopLight = false
        com.timerpg.street.shot.Crumbs.add("upgrades: the light model's MTP file downloads by itself (${if (meteredOk) "mobile data, allowed" else "Wi-Fi"}; " +
            "${ModelDownloads.remainingIn(ModelFiles.dir(ctx), lightItems) shr 20} MB to go)")
        main.post { v.downloadLive({ engine?.reload() }, stopIf = { stopLight || (!meteredOk && !unmeteredNow(ctx)) }) }
    }

    // (the download asks between reads: the network is looked at once a second at most)
    @Volatile private var netAt = 0L
    @Volatile private var netFree = true
    private fun unmeteredNow(ctx: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - netAt > 1000) { netAt = now; netFree = network(ctx).second }
        return netFree
    }

    /** (connected, unmetered) for the network the phone uses now. */
    private fun network(ctx: Context): Pair<Boolean, Boolean> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false to false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false to false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false to false)

    private fun openStorage(ctx: Context) = main.post {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun mb(b: Long) = "%,d MB".format(b shr 20)

    // ---------------------------------------------------------------------------------------------- the checks

    /** The light model's MTP file: downloads by itself on Wi-Fi when there is room. */
    private val lightDownload = object : Upgrades.Check {
        override val id = "light-mtp"
        override fun status(): Upgrades.Status {
            val ctx = app ?: return Upgrades.Status(Upgrades.State.NONE)
            val v = voices ?: return Upgrades.Status(Upgrades.State.NONE)
            val dir = ModelFiles.dir(ctx)
            if (ModelPrep.isHere(dir, ModelPrep.SOURCES[0]))
                return Upgrades.Status(Upgrades.State.DONE, "The light model is the 4B with MTP guesses.", doneKey = "4b-mtp",
                    doneNote = "AI light model downloaded: the 4B with MTP guesses")
            val left = ModelDownloads.remainingIn(dir, lightItems)
            return when (lastGo) {
                AutoFetch.Go.RUNNING, AutoFetch.Go.START -> Upgrades.Status(Upgrades.State.RUNNING,
                    "Downloading the light model (4B with MTP guesses) by itself: ${v.downloadNote}")
                AutoFetch.Go.WAIT_NETWORK -> Upgrades.Status(Upgrades.State.RUNNING, "The light model (4B with MTP guesses, ${mb(left)} to go) downloads when there is Wi-Fi.")
                AutoFetch.Go.PAUSED -> Upgrades.Status(Upgrades.State.RUNNING, "The light model's download is paused (it goes on by itself at the next start).")
                AutoFetch.Go.ASK_METERED -> Upgrades.Status(Upgrades.State.ASK,
                    "The new light model (4B with MTP guesses, ${mb(left)} to go) waits for Wi-Fi. Download it now on mobile data?",
                    action = "Download on mobile data", askKey = "metered")
                AutoFetch.Go.ASK_SPACE -> Upgrades.Status(Upgrades.State.ASK,
                    "Not enough storage for the new light model: ${mb(left + AutoFetch.MARGIN_BYTES)} needed, ${mb(dir.usableSpace)} free. " +
                        "Free some space and it downloads by itself.", action = "Open storage settings", askKey = "space")
                AutoFetch.Go.FAILED -> Upgrades.Status(Upgrades.State.FAILED,
                    "The light model's download stopped: ${(v.lastResult as? ModelDownloads.Result.Failed)?.reason ?: "it failed"}",
                    action = "Try again", askKey = "failed")
                AutoFetch.Go.HERE -> Upgrades.Status(Upgrades.State.NONE)
            }
        }
        override fun act() {
            val ctx = app ?: return
            when (lastGo) {
                AutoFetch.Go.ASK_METERED -> meteredOk = true
                AutoFetch.Go.ASK_SPACE -> openStorage(ctx)
                AutoFetch.Go.FAILED -> failures = 0
                else -> {}
            }
        }
    }

    /** The English-first preparation of the light model's file: by itself, in the background. */
    private val lightPrep = object : Upgrades.Check {
        override val id = "light-prep"
        override fun status(): Upgrades.Status {
            val ctx = app ?: return Upgrades.Status(Upgrades.State.NONE)
            val e = engine ?: return Upgrades.Status(Upgrades.State.NONE)
            val dir = ModelFiles.dir(ctx)
            val live = ModelPrep.live(dir) ?: return Upgrades.Status(Upgrades.State.NONE, "No light model on the phone yet.")
            val k = e.headKWanted
            val which = if (live.mtp) "4B with MTP" else "old 4B"
            val step = ModelPrep.next(dir, k, { com.timerpg.street.llm.NativeLlm.headVocab(it) }, dir.usableSpace)
            val last = e.prepLast
            if (step == ModelPrep.Step.Idle && live.prepared) {
                val secs = last?.takeIf { it.ok && it.file == live.file.name }?.let { ", %.1f s".format(java.util.Locale.US, it.seconds) } ?: ""
                return Upgrades.Status(Upgrades.State.DONE, "The light model ($which) is prepared: English-first, K ${"%,d".format(k)}.",
                    doneKey = "${live.file.name}:$k", doneNote = "AI model prepared: English-first ($which, K ${"%,d".format(k)})$secs")
            }
            if (step is ModelPrep.Step.NoSpace) return Upgrades.Status(Upgrades.State.ASK,
                "Not enough storage to prepare the light model (English-first): ${mb(step.needBytes)} needed, ${mb(step.freeBytes)} free. " +
                    "Free some space and it prepares by itself.", action = "Open storage settings", askKey = "space")
            val failed = last != null && !last.ok && !last.noSpace && last.stopped?.let {
                it.startsWith("failed") || it.startsWith("could not") || it.startsWith("K not changed")
            } == true
            if (failed && !e.prepBusy) return Upgrades.Status(Upgrades.State.FAILED,
                "Preparing the light model (English-first) failed: ${last!!.stopped}", action = "Try again", askKey = "failed")
            return Upgrades.Status(Upgrades.State.RUNNING, e.status.value.prep ?: "The light model ($which) prepares by itself in the background (never during a conversation).")
        }
        override fun act() {
            val ctx = app ?: return
            val dir = ModelFiles.dir(ctx)
            if (ModelPrep.next(dir, engine?.headKWanted ?: ModelPrep.K_DEFAULT, { com.timerpg.street.llm.NativeLlm.headVocab(it) }, dir.usableSpace) is ModelPrep.Step.NoSpace)
                openStorage(ctx)
            else engine?.prepNow()
        }
    }

    /** MTP guesses for the light model in play (recommended: on, 2 ahead). */
    private val liveMtp = object : Upgrades.Check {
        override val id = "mtp-guesses"
        override fun status(): Upgrades.Status {
            val e = engine ?: return Upgrades.Status(Upgrades.State.NONE)
            val n = e.liveMtpWanted
            return if (n > 0) Upgrades.Status(Upgrades.State.DONE, "MTP guesses in play: on, $n ahead.")
            else Upgrades.Status(Upgrades.State.ASK, "MTP guesses for the light model are off. On, replies come faster with the same words. Turn them on?",
                action = "Turn on", askKey = "off")
        }
        override fun act() { engine?.setLiveMtp(2) }
    }

    /** The rest of the AI's files (the main model, the voices, speech, memory): the full download, on the owner's say-so. */
    private val aiFiles = object : Upgrades.Check {
        override val id = "ai-files"
        override fun status(): Upgrades.Status {
            val ctx = app ?: return Upgrades.Status(Upgrades.State.NONE)
            val v = voices ?: return Upgrades.Status(Upgrades.State.NONE)
            val dir = ModelFiles.dir(ctx)
            val rest = ModelDownloads.ITEMS.filter { it.path != ModelFiles.LIVE_MTP }
            val missing = ModelDownloads.missingIn(dir, rest)
            if (missing.isEmpty()) return Upgrades.Status(Upgrades.State.DONE, "The main model, voices, speech and memory models are installed.")
            if (v.downloadingWhat == "all" || wantAll) return Upgrades.Status(Upgrades.State.RUNNING, "Downloading the AI's files: ${v.downloadNote}")
            val left = ModelDownloads.remainingIn(dir, rest)
            val what = missing.map { kind(it.path) }.distinct().joinToString(", ")
            val metered = !network(ctx).second
            return Upgrades.Status(Upgrades.State.ASK,
                "${missing.size} AI file${if (missing.size == 1) "" else "s"} not downloaded (${mb(left)}: $what)." +
                    (if (metered) " You are on mobile data." else "") + " Download them?",
                action = "Download (${mb(left)})", askKey = "missing")
        }
        override fun act() { wantAll = true }
    }

    private fun kind(path: String) = when {
        path == ModelFiles.NAME -> "the main model"
        path.startsWith("kokoro/") || path.startsWith("supertonic/") -> "voices"
        path.contains("ASR", ignoreCase = true) || path.contains("asr") -> "speech"
        else -> "memory"
    }

    /** The stored answers (the "upgrades" prefs): questions not to ask again, finished things already announced. */
    private class PrefsStore(ctx: Context) : Upgrades.Store {
        private val p = ctx.getSharedPreferences("upgrades", Context.MODE_PRIVATE)
        override fun never(): Set<String> = p.getStringSet("never", emptySet())!!.toSet()
        override fun setNever(keys: Set<String>) { p.edit().putStringSet("never", HashSet(keys)).apply() }
        override fun announced(): Set<String> = p.getStringSet("announced", emptySet())!!.toSet()
        override fun setAnnounced(keys: Set<String>) { p.edit().putStringSet("announced", HashSet(keys)).apply() }
    }

    fun neverKeys(): Set<String> = board0?.neverKeys ?: emptySet()

    /** For the AI panel: the light model in plain words. */
    data class LightState(val file: String, val prepared: Boolean, val k: Long, val mtpOn: Int)

    fun lightState(ctx: Context, engine: DialogueEngine): LightState {
        val live = ModelPrep.live(ModelFiles.dir(ctx))
        return LightState(
            file = when { live == null -> "none yet"; live.mtp -> "4B MTP"; else -> "old 4B (no MTP layer)" } + (live?.file?.name?.let { " · $it" } ?: ""),
            prepared = live?.prepared == true,
            k = if (live?.prepared == true) com.timerpg.street.llm.NativeLlm.headVocab(live.file) else 0L,
            mtpOn = engine.liveMtpWanted,
        )
    }
}
