package com.timerpg.street.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The street's sound: ambience beds looping under everything (their loudness follows [SoundRules] from the hour and the
 * weather), music that crossfades when the mood changes, and one-shot sounds (footsteps), mixed in one low-latency
 * AudioTrack on its own thread. When an NPC speaks ([duck]) the music and beds dip under the voice. Assets load lazily
 * from assets/audio; missing files are silent, so the game runs with any subset of them.
 */
class SoundScape(private val ctx: Context) {
    private val beds = HashMap<String, Pcm?>()
    private val bedPos = HashMap<String, Int>()
    private val bedGain = HashMap<String, Float>()
    @Volatile private var bedTarget: Map<String, Float> = emptyMap()

    private var music: Pcm? = null; private var musicPos = 0; private var musicGain = 0f
    private var fading: Pcm? = null; private var fadingPos = 0; private var fadingGain = 0f
    @Volatile private var musicKey: String? = null
    @Volatile private var musicTarget = .5f
    private val tracks = HashMap<String, Pcm?>()

    private class Shot(val pcm: Pcm, val gain: Float, val pan: Float) { var pos = 0 }
    private val shots = ConcurrentLinkedQueue<Shot>()
    private val playing = ArrayList<Shot>()
    private val sfx = HashMap<String, Pcm?>()

    private val synth = Synth()
    private var rnd = java.util.Random(7)
    @Volatile private var scatter: Map<String, Float> = emptyMap()

    @Volatile private var ducked = false
    @Volatile var enabled = true
    @Volatile var musicOn = true
    @Volatile private var running = false
    private var thread: Thread? = null

    /** The mood now (from [SoundRules.at]); called as often as the game likes. */
    fun set(mix: SoundRules.Mix) {
        bedTarget = mix.beds
        scatter = mix.scatter
        musicTarget = mix.musicGain
        if (mix.music != musicKey) musicKey = mix.music
    }

    /** An NPC is speaking: the rest dips under the voice. */
    fun duck(on: Boolean) { ducked = on }

    /** A one-shot from assets/audio/sfx/NAME.ogg ([pan] -1 left .. 1 right). */
    fun play(name: String, gain: Float = 1f, pan: Float = 0f) {
        if (!enabled) return
        val p = synchronized(sfx) { sfx.getOrPut(name) { Pcm.load(ctx.assets, "audio/sfx/$name.ogg") } } ?: return
        shots.add(Shot(p, gain, pan))
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "soundscape").apply { start() }
    }

    fun stop() { running = false; thread?.join(500); thread = null }

    private fun bed(name: String): Pcm? = synchronized(beds) { beds.getOrPut(name) { Pcm.load(ctx.assets, "audio/amb/$name.ogg") } }
    /** music/TOWN_MOOD.ogg, else music/MOOD.ogg (a town's own music when there is one). */
    private fun track(name: String): Pcm? = synchronized(tracks) {
        tracks.getOrPut(name) { Pcm.load(ctx.assets, "audio/music/$name.ogg") ?: name.substringAfter('_', "").takeIf { it.isNotEmpty() }?.let { Pcm.load(ctx.assets, "audio/music/$it.ogg") } }
    }

    private fun loop() {
        try { mix() } catch (t: Throwable) { Log.e("SoundScape", "sound stopped", t); running = false }
    }

    private fun mix() {
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
        var curKey: String? = null
        while (running) {
            java.util.Arrays.fill(buf, 0f)
            if (enabled) {
                val duck = if (ducked) .35f else 1f
                // beds: each glides towards its loudness (about a second), loops seamlessly
                for (name in SoundRules.BEDS) {
                    val target = (bedTarget[name] ?: 0f) * duck
                    var g = bedGain[name] ?: 0f
                    if (g < .002f && target < .002f) continue
                    // a recorded bed if there is one, else made live
                    val p = bed(name)
                    if (p == null) {
                        g += (target - g) * (1f - Math.pow(1.0 - .00004, frames.toDouble()).toFloat())
                        synth.render(name, buf, frames, g)
                        bedGain[name] = g
                        continue
                    }
                    var pos = bedPos[name] ?: 0
                    for (k in 0 until frames) {
                        g += (target - g) * .00004f
                        buf[2 * k] += p.data[2 * pos] * g; buf[2 * k + 1] += p.data[2 * pos + 1] * g
                        pos++; if (pos >= p.frames) pos = 0
                    }
                    bedGain[name] = g; bedPos[name] = pos
                }
                // the town's scattered sounds: each kind at its rate a minute, somewhere left or right, near or far
                for ((name, perMinute) in scatter) {
                    if (perMinute <= 0f || rnd.nextFloat() > perMinute * frames / (Pcm.RATE * 60f)) continue
                    val variants = SoundRules.SCATTER_FILES[name] ?: listOf(name)
                    val file = variants[rnd.nextInt(variants.size)]
                    val pc = synchronized(sfx) { sfx.getOrPut(file) { Pcm.load(ctx.assets, "audio/sfx/$file.ogg") } } ?: continue
                    playing += Shot(pc, (.25f + rnd.nextFloat() * .35f) * duck, rnd.nextFloat() * 1.6f - .8f)
                }
                // music: a new mood crossfades over a few seconds
                val key = musicKey
                if (key != curKey) {
                    curKey = key
                    fading = music; fadingPos = musicPos; fadingGain = musicGain
                    music = key?.let { track(it) }; musicPos = 0; musicGain = 0f
                }
                val mt = (if (musicOn) musicTarget else 0f) * (if (ducked) .3f else 1f)
                music?.let { m ->
                    for (k in 0 until frames) {
                        musicGain += (mt - musicGain) * .00003f
                        buf[2 * k] += m.data[2 * musicPos] * musicGain; buf[2 * k + 1] += m.data[2 * musicPos + 1] * musicGain
                        musicPos++; if (musicPos >= m.frames) musicPos = 0
                    }
                }
                fading?.let { m ->
                    for (k in 0 until frames) {
                        fadingGain *= .99994f
                        buf[2 * k] += m.data[2 * fadingPos] * fadingGain; buf[2 * k + 1] += m.data[2 * fadingPos + 1] * fadingGain
                        fadingPos++; if (fadingPos >= m.frames) fadingPos = 0
                    }
                    if (fadingGain < .001f) fading = null
                }
                // one-shots
                while (true) { playing += shots.poll() ?: break }
                val it = playing.iterator()
                while (it.hasNext()) {
                    val s = it.next()
                    val gl = s.gain * (1f - maxOf(0f, s.pan)); val gr = s.gain * (1f + minOf(0f, s.pan))
                    var k = 0
                    while (k < frames && s.pos < s.pcm.frames) {
                        buf[2 * k] += s.pcm.data[2 * s.pos] * gl; buf[2 * k + 1] += s.pcm.data[2 * s.pos + 1] * gr
                        k++; s.pos++
                    }
                    if (s.pos >= s.pcm.frames) it.remove()
                }
                // a soft limit, never a hard clip
                for (i in buf.indices) { val x = buf[i]; buf[i] = x / (1f + kotlin.math.abs(x) * .25f) }
            }
            at.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        }
        at.stop(); at.release()
    }
}
