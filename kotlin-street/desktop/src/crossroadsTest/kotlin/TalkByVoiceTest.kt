import com.lifemax.rpg.feature.habits.presentation.scene.TalkRange
import com.timerpg.voice.SpeechSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Talking to people by voice: the speech segmenter on made-up audio, and who the words are for. */
class TalkByVoiceTest {
    private val rate = 16000

    /** A room's hiss at [noise] RMS-ish, with tones (speech stand-ins) at [amp] for each (start s, length s). */
    private fun audio(seconds: Float, noise: Float, amp: Float = .1f, vararg bursts: Pair<Float, Float>): FloatArray {
        val r = java.util.Random(7)
        return FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toFloat() / rate
            var v = (r.nextGaussian() * noise).toFloat()
            // a voice-like tone: 180 Hz with a wobble in loudness (syllables)
            if (bursts.any { t >= it.first && t < it.first + it.second }) v += amp * sin(2 * PI * 180 * t).toFloat() * (.6f + .4f * sin(2 * PI * 4 * t).toFloat())
            v
        }
    }

    /** When each utterance was handed over (seconds into the stream). */
    private val endedAt = ArrayList<Float>()

    private fun cut(pcm: FloatArray, chunk: Int = 800, hangoverMs: Int = 700): List<FloatArray> {
        val got = ArrayList<FloatArray>()
        endedAt.clear()
        var i = 0
        val s = SpeechSegmenter(rate, hangoverMs = hangoverMs) { got += it; endedAt += i.toFloat() / rate }
        // fed as the microphone hands it over: in pieces
        while (i < pcm.size) { val n = minOf(chunk, pcm.size - i); s.feed(pcm.copyOfRange(i, i + n)); i += n }
        return got
    }

    @Test fun silenceIsNothing() {
        assertEquals(0, cut(audio(10f, .002f)).size)
    }

    @Test fun oneSentenceIsOneUtterance() {
        val got = cut(audio(5f, .002f, .1f, 1f to 1.2f))
        assertEquals(1, got.size)
        val secs = got[0].size.toFloat() / rate
        // the speech, the preroll before it, and a short tail after it
        assertTrue("length $secs", secs > 1.2f && secs < 2.1f)
    }

    @Test fun aShortPauseDoesNotSplitIt() {
        assertEquals(1, cut(audio(6f, .002f, .1f, 1f to 1f, 2.3f to 1f)).size)
    }

    @Test fun theGamesShorterTailEndsSoonerAndKeepsShortPauses() {
        // the app's tail (500 ms): the utterance is handed over within ~0.6 s of the end of speech (about 0.2 s sooner than with 700)
        assertEquals(1, cut(audio(5f, .002f, .1f, 1f to 1.2f), hangoverMs = 500).size)
        val end = 2.2f
        val short = endedAt[0]
        assertTrue("ended at $short", short - end < .65f)
        cut(audio(5f, .002f, .1f, 1f to 1.2f))
        assertTrue("500 ms: $short, 700 ms: ${endedAt[0]}", endedAt[0] - short > .15f)
        // a 300 ms breath between words is still one utterance
        assertEquals(1, cut(audio(6f, .002f, .1f, 1f to 1f, 2.3f to 1f), hangoverMs = 500).size)
    }

    @Test fun twoSentencesApartAreTwo() {
        assertEquals(2, cut(audio(8f, .002f, .1f, 1f to 1f, 3.5f to 1.5f)).size)
    }

    @Test fun aClickIsNotSpeech() {
        assertEquals(0, cut(audio(4f, .002f, .3f, 1f to .06f, 2f to .1f)).size)
    }

    @Test fun aLongMonologueIsCut() {
        val got = cut(audio(30f, .002f, .1f, 1f to 27f))
        assertTrue("${got.size} pieces", got.size >= 2)
        assertTrue(got.all { it.size <= rate * 12 + rate / 10 })
    }

    @Test fun aSteadyNoiseIsLearnt() {
        // a fan switched on loud enough to pass for a voice at first: at most once, then it is the room's quiet
        val pcm = audio(40f, .002f).also { a -> val r = java.util.Random(3); for (i in rate * 2 until a.size) a[i] += (r.nextGaussian() * .025f).toFloat() }
        assertTrue(cut(pcm).size <= 1)
        // and someone speaking over the fan is still heard (once it has been learnt: the fan's first seconds may count once)
        cut(audio(30f, .025f, .2f, 20f to 1.5f))
        assertEquals(1, endedAt.count { it > 10f })
        assertTrue(endedAt.count { it <= 10f } <= 1)
    }

    @Test fun quietSpeechInAQuietRoom() {
        assertEquals(1, cut(audio(5f, .001f, .03f, 1f to 1f)).size)
    }

    // ---- who the words are for

    private val centers = listOf(100f, 300f, 500f, 700f)

    @Test fun nearestWithinRange() {
        // people at buildings 1 and 2 (standing at 330 and 530)
        val doors = listOf(1, 2)
        assertEquals(0, TalkRange.nearest(320f, 5f, doors, centers, 70f))
        assertEquals(1, TalkRange.nearest(520f, 5f, doors, centers, 70f))
        // between them: the nearer
        assertEquals(1, TalkRange.nearest(440f, 4f, doors, centers, 200f))
        assertEquals(0, TalkRange.nearest(420f, 4f, doors, centers, 200f))
    }

    @Test fun nobodyOutOfRange() {
        val doors = listOf(1, 2)
        assertEquals(-1, TalkRange.nearest(430f, 5f, doors, centers, 70f))
        assertEquals(-1, TalkRange.nearest(100f, 5f, doors, centers, 70f))
        // far out at the kerb is further than beside them along the wall (the pavement is deep ground)
        assertEquals(0, TalkRange.nearest(380f, 3.6f, doors, centers, 70f))
        assertEquals(-1, TalkRange.nearest(380f, 14f, doors, centers, 70f))
        // nobody there at all, or a door past the street's end
        assertEquals(-1, TalkRange.nearest(330f, 4f, emptyList(), centers, 70f))
        assertEquals(-1, TalkRange.nearest(330f, 4f, listOf(9), centers, 70f))
    }
}
