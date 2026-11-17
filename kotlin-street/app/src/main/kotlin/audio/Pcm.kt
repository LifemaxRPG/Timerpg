package com.timerpg.street.audio

import android.content.res.AssetManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/** A sound decoded to interleaved stereo float at [Pcm.RATE] (mono files are doubled; other rates resampled). */
class Pcm(val data: FloatArray) {
    val frames: Int get() = data.size / 2

    companion object {
        const val RATE = 48000

        /** Decodes an asset (ogg, wav, mp3, m4a: whatever the phone's codecs read), or null. Blocking. */
        fun load(assets: AssetManager, path: String): Pcm? = try {
            assets.openFd(path).use { fd ->
                val ex = MediaExtractor()
                ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ex.selectTrack(track)
                val fmt = ex.getTrackFormat(track)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val out = java.io.ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inDone = false; var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(5000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5000)
                    if (o >= 0) {
                        val b = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size); b.position(info.offset); b.get(bytes)
                        out.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                codec.stop(); codec.release(); ex.release()
                val sb = java.nio.ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val n = sb.remaining() / ch
                // to stereo float, then to the mixer's rate (linear: these are beds and music, not hi-fi masters)
                val st = FloatArray(n * 2)
                for (k in 0 until n) {
                    val l = sb.get(k * ch) / 32768f
                    val r = if (ch > 1) sb.get(k * ch + 1) / 32768f else l
                    st[2 * k] = l; st[2 * k + 1] = r
                }
                Pcm(if (rate == RATE) st else resample(st, rate))
            }
        } catch (e: Exception) { null }

        private fun resample(st: FloatArray, from: Int): FloatArray {
            val n = st.size / 2
            val m = (n.toLong() * RATE / from).toInt()
            val out = FloatArray(m * 2)
            for (k in 0 until m) {
                val x = k.toDouble() * from / RATE
                val a = x.toInt().coerceAtMost(n - 1); val b = (a + 1).coerceAtMost(n - 1); val t = (x - a).toFloat()
                out[2 * k] = st[2 * a] + (st[2 * b] - st[2 * a]) * t
                out[2 * k + 1] = st[2 * a + 1] + (st[2 * b + 1] - st[2 * a + 1]) * t
            }
            return out
        }
    }
}
