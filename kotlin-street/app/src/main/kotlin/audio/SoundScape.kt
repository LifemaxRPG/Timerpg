package com.timerpg.street.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The game's sound (docs/SOUND.md): one [Mixer] fed into one low-latency AudioTrack on its own thread. Over it: the
 * ambience beds ([SoundRules]: recorded loops, or made live by [Synth]), the places that sound (the shore, a crowd, a
 * fountain), the far traffic of the era, the police's siren while they look, the bells on the hour, scattered dogs and
 * horses, the footsteps (the set chosen in Settings), every car's engine and tyres ([CarAudio]), the bike, and music that
 * crossfades with the mood. When an NPC speaks ([duck]) the rest dips under the voice. Files load in the background
 * (never on the audio thread); a missing one is silent, so the game runs with any subset of them.
 */
class SoundScape(private val ctx: Context) {
    val mixer = Mixer()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "sound-loader").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val cache = ConcurrentHashMap<String, Pcm>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** A decoded asset, or null while it loads (asked for in the background) or if there is none. */
    fun asset(path: String): Pcm? {
        cache[path]?.let { return it }
        if (path in missing || !pending.add(path)) return null
        loader.execute {
            val p = runCatching { Pcm.load(ctx.assets, path) }.getOrNull()
            if (p != null) cache[path] = p else missing += path
            pending -= path
        }
        return null
    }

    private fun preload(paths: List<String>) { for (p in paths) asset(p) }

    // ---------------------------------------------------------------- the ambience
    private val synth = Synth()
    private val beds = SoundRules.BEDS.associateWith { name ->
        LoopSource(Mixer.AMB, null).also { s -> if (name in SoundRules.SYNTH_BEDS) s.synth = { buf, n, g -> synth.render(name, buf, n, g) } }
    }
    private val water = LoopSource(Mixer.AMB, null, refM = 60f).apply { world = true }
    private val crowd = LoopSource(Mixer.AMB, null, refM = 8f).apply { world = true }
    private val fountain = LoopSource(Mixer.AMB, null, refM = 6f).apply { world = true }
    val traffic = TrafficSource()
    val siren = SirenSource()
    /** Every car's engine and tyres. */
    val cars = CarAudio { loopBank }
    /** The bicycle's tyres and freewheel (set by the game each frame while riding). */
    val bike = BikeSound()
    private val bikeSource = object : Mixer.Source(Mixer.FX) {
        private var mono = FloatArray(0)
        override fun render(m: Mixer, out: FloatArray, frames: Int) { bike.render(out, frames, 1f) }
    }
    private val bellPcm by lazy { BikeSound.bell() }
    private val towerBell by lazy { Pcm(BellSynth.tower()) }

    // music: a new mood crossfades over a few seconds
    @Volatile private var musicKey: String? = null
    @Volatile private var musicTarget = .5f
    private val musicSource = object : Mixer.Source(Mixer.MUSIC) {
        var cur: String? = null
        var music: Pcm? = null; var pos = 0; var gain = 0f
        var fading: Pcm? = null; var fpos = 0; var fgain = 0f
        override fun render(m: Mixer, out: FloatArray, frames: Int) {
            val key = musicKey
            if (key != cur) { cur = key; fading = music; fpos = pos; fgain = gain; music = null; pos = 0; gain = 0f }
            if (music == null && key != null) music = track(key)
            val mt = if (musicOn) musicTarget else 0f
            music?.let { p ->
                for (k in 0 until frames) {
                    gain += (mt - gain) * .00003f
                    out[2 * k] += p[2 * pos] * gain; out[2 * k + 1] += p[2 * pos + 1] * gain
                    pos++; if (pos >= p.frames) pos = 0
                }
            }
            fading?.let { p ->
                for (k in 0 until frames) {
                    fgain *= .99994f
                    out[2 * k] += p[2 * fpos] * fgain; out[2 * k + 1] += p[2 * fpos + 1] * fgain
                    fpos++; if (fpos >= p.frames) fpos = 0
                }
                if (fgain < .001f) fading = null
            }
        }
    }
    /** music/TOWN_MOOD.ogg, else music/MOOD.ogg (a town's own music when there is one). */
    private fun track(name: String): Pcm? = asset("audio/music/$name.ogg")
        ?: name.substringAfter('_', "").takeIf { it.isNotEmpty() && "audio/music/$name.ogg" in missing }?.let { asset("audio/music/$it.ogg") }

    @Volatile var enabled = true
    @Volatile var musicOn = true
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        for (s in beds.values) mixer.add(s)
        mixer.add(water); mixer.add(crowd); mixer.add(fountain)
        mixer.add(traffic); mixer.add(siren); mixer.add(cars); mixer.add(bikeSource); mixer.add(musicSource)
        // (the steps and the cars' one-shots decoded ahead, in the background; the beds when first wanted)
        preload(listOf("car_door_open", "car_door_close_1", "car_door_close_2", "car_door_close_3", "crash_clank", "crash_glass", "crash_big").map { "audio/sfx/$it.ogg" })
        loader.execute { stepCounts() }
    }

    // ---------------------------------------------------------------- the state the game sets

    private var lastSet = 0L
    private var prevHour = Float.NaN
    private val strikesDue = ArrayList<Long>()
    private val lastWanted = HashMap<String, Long>()
    private val rnd = java.util.Random(7)
    @Volatile private var mix: SoundRules.Mix? = null
    /** Where the town's bell hangs (a clock tower or a church), street units; null: no bell. */
    @Volatile var bellAt: FloatArray? = null

    /** The mood now (from [SoundRules.at]); called as often as the game likes (each frame). [hour] rings the bells. */
    fun set(m: SoundRules.Mix, hour: Float = Float.NaN) {
        val now = System.nanoTime()
        val dt = if (lastSet == 0L) 0f else ((now - lastSet) / 1e9f).coerceIn(0f, .25f)
        lastSet = now
        mix = m
        for ((name, s) in beds) {
            s.target = m.beds[name] ?: 0f
            if (name in SoundRules.SYNTH_BEDS) continue
            val path = "audio/amb/$name.ogg"
            if (s.target > .002f) { lastWanted[name] = now; if (s.pcm == null) s.pcm = asset(path) }
            // a bed unheard for two minutes gives its memory back (it decodes again when wanted)
            else if (s.pcm != null && s.level < .001f && now - (lastWanted[name] ?: 0L) > 120_000_000_000L) { s.pcm = null; cache.remove(path) }
        }
        musicTarget = m.musicGain
        if (m.music != musicKey) musicKey = m.music
        traffic.perMinute = m.trafficPerMinute; traffic.year = m.year
        siren.on = m.siren; siren.style = m.sirenStyle
        mixer.muffle = m.muffle
        water.target = m.water; crowd.target = m.crowd; fountain.target = m.fountain
        if (water.target > 0f && water.pcm == null) water.pcm = asset("audio/amb/${waterKind}.ogg")
        if (crowd.target > 0f && crowd.pcm == null) crowd.pcm = asset("audio/amb/crowd.ogg")
        if (fountain.target > 0f && fountain.pcm == null) fountain.pcm = asset("audio/amb/fountain.ogg")
        if (!enabled) return
        // the scattered sounds: each kind at its rate a minute, somewhere round the listener, near or far
        for ((name, perMinute) in m.scatter) {
            if (perMinute <= 0f || rnd.nextFloat() > perMinute * dt / 60f) continue
            val files = SoundRules.SCATTER_FILES[name] ?: continue
            val p = asset("audio/sfx/${files[rnd.nextInt(files.size)]}.ogg") ?: continue
            val ang = rnd.nextFloat() * 6.283f; val dist = (if (name == "thunder") 300f else 30f + rnd.nextFloat() * 110f) * Mixer.U
            mixer.play(Mixer.Voice(p, if (name == "thunder") .9f else .7f, Mixer.AMB, 1, .94f + rnd.nextFloat() * .12f, world = true,
                x = mixer.lx + kotlin.math.cos(ang) * dist, z = mixer.lz + kotlin.math.sin(ang) * dist, refM = if (name == "thunder") 400f else 12f))
        }
        // the bells on the hour
        if (!hour.isNaN()) {
            val n = SoundRules.strikes(prevHour, hour)
            prevHour = hour
            if (n > 0 && bellAt != null) for (i in 0 until n) strikesDue += now + (i * 2.6e9).toLong()
        }
        if (strikesDue.isNotEmpty() && now >= strikesDue[0]) {
            strikesDue.removeAt(0)
            bellAt?.let { b -> mixer.play(Mixer.Voice(towerBell, 1f, Mixer.AMB, 4, world = true, x = b[0], z = b[1], refM = 60f)) }
        }
    }

    /** The water near: the sea's "waves" or a "river" (which recording the shore plays) and where its nearest point is. */
    @Volatile var waterKind = "waves"
    fun waterAt(x: Float, z: Float, kind: String) { if (kind != waterKind) { waterKind = kind; water.pcm = null }; water.x = x; water.z = z }
    fun crowdAt(x: Float, z: Float) { crowd.x = x; crowd.z = z }
    fun fountainAt(x: Float, z: Float) { fountain.x = x; fountain.z = z }
    fun sirenAt(x: Float, z: Float) { siren.x = x; siren.z = z }

    /** Where the listener is (street units), the camera's heading (degrees) and the listener's velocity (m/s: the driven car's). */
    fun listener(x: Float, z: Float, yawDeg: Float, vx: Float = 0f, vz: Float = 0f) {
        mixer.lx = x; mixer.lz = z; mixer.yawDeg = yawDeg; mixer.lvx = vx; mixer.lvz = vz
    }

    /** The volumes (Settings) and the engine mode. */
    fun apply(s: SoundSettings) {
        mixer.master = s.master
        mixer.volume[Mixer.AMB] = s.ambience; mixer.volume[Mixer.FX] = s.effects; mixer.volume[Mixer.CAR] = s.vehicles; mixer.volume[Mixer.MUSIC] = s.music
        cars.recorded = s.recordedEngines
        if (s.recordedEngines && loopBank == null && !loopBankAsked) { loopBankAsked = true; loadLoopBank() }
    }

    @Volatile private var voiceDuck = false
    @Volatile private var talkDuck = false
    /** An NPC is speaking: the rest dips under the voice. */
    fun duck(on: Boolean) { voiceDuck = on; mixer.ducked = voiceDuck || talkDuck }
    /** A conversation is open (its lines on screen, spoken or not): the street dips too. */
    fun duckTalk(on: Boolean) { talkDuck = on; mixer.ducked = voiceDuck || talkDuck }

    // ---------------------------------------------------------------- one-shots

    /** A one-shot from assets/audio/sfx/NAME.ogg ([pan] -1 left .. 1 right). */
    fun play(name: String, gain: Float = 1f, pan: Float = 0f) {
        if (!enabled) return
        val file = when (name) { "door_bell" -> if (rnd.nextBoolean()) "shop_bell_1" else "shop_bell_2"; else -> name }
        val p = asset("audio/sfx/$file.ogg") ?: return
        mixer.play(Mixer.Voice(p, gain, Mixer.FX, 3, pan = pan))
    }

    /** A one-shot from where it happens (street units). */
    fun playAt(name: String, x: Float, z: Float, gain: Float = 1f, refM: Float = 5f, priority: Int = 3, bus: Int = Mixer.FX) {
        if (!enabled) return
        val p = asset("audio/sfx/$name.ogg") ?: return
        mixer.play(Mixer.Voice(p, gain, bus, priority, .97f + rnd.nextFloat() * .06f, world = true, x = x, z = z, refM = refM))
    }

    /** The bicycle's bell. */
    fun bell() { if (enabled) mixer.play(Mixer.Voice(bellPcm, .5f, Mixer.FX, 4, pan = .1f)) }

    // ---------------------------------------------------------------- footsteps

    private val picker = Footsteps.Picker()
    private val stepSynth = StepSynth()
    /** Set E's steps, made ahead in the background (a pool per surface, refreshed as they are used). */
    private val synthSteps = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Pcm>>()
    /** How many variations each set has of each surface (from the assets' listing). */
    @Volatile private var counts: Map<String, Int> = emptyMap()

    private fun stepCounts() {
        val m = HashMap<String, Int>()
        for (s in Footsteps.SETS) if (!s.synth) runCatching { ctx.assets.list("audio/step/${s.id}")?.forEach { f -> val surf = f.substringBeforeLast('_'); m["${s.id}/$surf"] = (m["${s.id}/$surf"] ?: 0) + 1 } }
        counts = m
        prepareSteps(SoundSettings.footsteps)
    }

    /** Decodes the set's steps ahead (on choosing it in Settings, and at the start). */
    fun prepareSteps(set: String) {
        val c = counts
        for ((key, n) in c) if (key.startsWith("$set/")) for (i in 0 until n) asset(Footsteps.file(set, key.substringAfter('/'), i))
    }

    /** A footstep on [surface] ([Footsteps.surfaceName]) in the chosen set. */
    fun footstep(surface: String, running: Boolean) {
        if (!enabled) return
        val set = SoundSettings.footsteps
        val def = Footsteps.SETS.firstOrNull { it.id == set } ?: Footsteps.SETS[0]
        if (def.synth) {
            val q = synthSteps.getOrPut(surface) { java.util.concurrent.ConcurrentLinkedQueue() }
            val pcm = q.poll()
            // (keep a few ready: made on the loader thread, a few milliseconds each)
            if (q.size < 4) loader.execute { repeat(6 - q.size) { q.add(Pcm(stereo(stepSynth.make(surface, running)))) } }
            pcm ?: return
            val st = picker.next(surface, 1, running)
            mixer.play(Mixer.Voice(pcm, st.gain * 1.1f, Mixer.FX, 3, st.rate, st.pan))
            return
        }
        val c = counts
        val surf = Footsteps.fallbacks(surface).firstOrNull { (c["${def.id}/$it"] ?: 0) > 0 } ?: return
        val st = picker.next(surf, c["${def.id}/$surf"] ?: 1, running)
        val pcm = asset(Footsteps.file(def.id, surf, st.variation))
        if (pcm == null) { for (i in 0 until (c["${def.id}/$surf"] ?: 0)) asset(Footsteps.file(def.id, surf, i)); return }
        mixer.play(Mixer.Voice(pcm, st.gain, Mixer.FX, 3, st.rate, st.pan))
    }

    private fun stereo(m: FloatArray) = FloatArray(m.size * 2).also { for (i in m.indices) { it[2 * i] = m[i]; it[2 * i + 1] = m[i] } }

    // ---------------------------------------------------------------- the cars' one-shots

    @Volatile private var loopBank: EngineLoopBank? = null
    @Volatile private var loopBankAsked = false
    private fun loadLoopBank() {
        loader.execute {
            if (loopBank != null) return@execute
            val idle = listOf("idle_v8", "idle_old", "idle_car", "idle_four").mapNotNull { n -> Pcm.load(ctx.assets, "audio/engine/$n.ogg")?.let { n to it } }.toMap()
            val load = (0 until 6).mapNotNull { i -> Pcm.load(ctx.assets, "audio/engine/load_$i.ogg")?.let { it to EngineLoopBank.LOAD_HZ[i] } }
            if (idle.isNotEmpty() || load.isNotEmpty()) loopBank = EngineLoopBank(idle, load)
        }
    }

    /** A car door at ([x], [z]): opening, or shutting. */
    fun carDoor(x: Float, z: Float, open: Boolean) {
        playAt(if (open) "car_door_open" else "car_door_close_${1 + rnd.nextInt(3)}", x, z, .8f, 4f, 3, Mixer.CAR)
    }

    /** A hard stop of a car at ([x], [z]): [drop] the speed lost in a moment (m/s): a bump, a crunch, a smash with glass. */
    fun impact(x: Float, z: Float, drop: Float) {
        val name = when { drop < 6f -> "crash_clank"; drop < 12f -> if (rnd.nextBoolean()) "crash_glass" else "crash_clank"; else -> "crash_big" }
        playAt(name, x, z, (.45f + drop / 20f).coerceAtMost(1f), 8f, 5, Mixer.CAR)
    }

    // ---------------------------------------------------------------- the thread

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "soundscape").apply { start() }
    }

    fun stop() { running = false; thread?.join(500); thread = null }

    private fun loop() {
        try { run() } catch (t: Throwable) { Log.e("SoundScape", "sound stopped", t); running = false }
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frames = 960                                   // 20 ms blocks
        val min = AudioTrack.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Pcm.RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, frames * 8 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { Log.w("SoundScape", "no audio output: $e"); running = false; return }
        at.play()
        val buf = FloatArray(frames * 2)
        while (running) {
            if (enabled) mixer.render(buf, frames) else java.util.Arrays.fill(buf, 0f)
            at.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
        at.stop(); at.release()
    }
}
