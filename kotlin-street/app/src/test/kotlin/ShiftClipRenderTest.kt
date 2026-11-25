package com.timerpg.street.audio

import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The owner's clips of a full-throttle run through the gears (2026-11-25: "decent, except when it's topped out before it
 * shifts gears"): with SHIFT_TRACES (a folder of the Jolt runs ShiftPointTest writes, KEY.csv: t, rpm, gear, throttle,
 * speed) and SOUND_OUT set, each car's made-live engine and tyres played along its physics run, heard from the driver's
 * seat, as SOUND_OUT/shift_KEY.wav (the first 20 s). Skipped otherwise.
 */
class ShiftClipRenderTest {
    private fun wav(f: File, st: FloatArray) {
        val n = st.size
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(Pcm.RATE).putInt(Pcm.RATE * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (x in st) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(b.array())
    }

    /** The clip of [rows] (t, rpm, gear, throttle, speed every physics step) for the car [spec]: stereo at Pcm.RATE. */
    fun clip(spec: VehicleSpec, rows: List<FloatArray>, seconds: Float = 20f): FloatArray {
        val m = Mixer()
        val cars = CarAudio { null }; m.add(cars)
        val f = cars.feed("car") { EngineProfile.forSpec(spec) }
        // (idle a second first, the engine running)
        val frames = ((seconds + 1f) * Pcm.RATE).toInt()
        val buf = FloatArray(frames * 2)
        var k = 0; var i = 0
        val block = 480
        while (k < frames) {
            val t = k / Pcm.RATE.toFloat() - 1f
            while (i < rows.size - 1 && rows[i + 1][0] <= t) i++
            val r = if (t < 0f) null else rows[i]
            f.wantRun = true; f.canRun = true
            f.rpm = r?.get(1) ?: 0f; f.gear = r?.get(2)?.toInt() ?: 0; f.throttle = r?.get(3) ?: 0f; f.speed = r?.get(4) ?: 0f
            f.slip = 0f
            val n = minOf(block, frames - k)
            val tmp = FloatArray(n * 2); m.render(tmp, n); System.arraycopy(tmp, 0, buf, k * 2, n * 2); k += n
        }
        return buf
    }

    @Test
    fun renderTheShiftClips() {
        val traces = System.getenv("SHIFT_TRACES")?.let { File(it) }
        val out = System.getenv("SOUND_OUT")?.let { File(it).apply { mkdirs() } }
        assumeTrue("SHIFT_TRACES and SOUND_OUT not set", traces != null && out != null)
        for (csv in traces!!.listFiles { x -> x.name.endsWith(".csv") }!!.sorted()) {
            val key = csv.name.removeSuffix(".csv")
            val spec = VehicleSpec.parse(File("src/main/assets/vehicles/$key/model-info.json").readText())
            val rows = csv.readLines().filter { it.isNotBlank() }.map { l -> l.split(',').map { it.toFloat() }.toFloatArray() }
            wav(File(out!!, "shift_$key.wav"), clip(spec, rows))
        }
    }

    /**
     * The top of the revs on its own, for each car (SOUND_OUT/limiter_KEY.wav): flat out, the revs climbing from 55% to
     * the car's governed speed over 2.5 s, held there 2 s (what the old gearbox did while the tyres spun), then a shift
     * (down to 65%) and a second climb to 90%: the limiter's sound and the shift's, side by side for every family.
     */
    @Test
    fun renderTheLimiterClips() {
        val out = System.getenv("SOUND_OUT")?.let { File(it).apply { mkdirs() } }
        assumeTrue("SOUND_OUT not set", out != null)
        for (key in listOf("alderwick-1928", "fenmere-1938", "northcairn-1956", "rowanvale-1970", "tallmere-1972", "torrel-1968", "torrel-1988", "torrel-1988-rs")) {
            val spec = VehicleSpec.parse(File("src/main/assets/vehicles/$key/model-info.json").readText())
            val max = spec.drivetrain.maxRpm
            val rows = ArrayList<FloatArray>()
            var t = 0f
            while (t < 8f) {
                val rpm = when {
                    t < 2.5f -> max * (.55f + .45f * t / 2.5f)
                    t < 4.5f -> max
                    t < 4.7f -> max * (1f - .35f * (t - 4.5f) / .2f)
                    else -> max * (.65f + .25f * ((t - 4.7f) / 3.3f))
                }
                rows += floatArrayOf(t, rpm, if (t < 4.5f) 2f else 3f, 1f, 10f + 3f * t)
                t += 1f / 60f
            }
            wav(File(out!!, "limiter_$key.wav"), clip(spec, rows, 8f))
        }
    }
}
