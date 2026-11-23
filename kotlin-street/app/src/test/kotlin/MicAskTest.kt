package com.timerpg.street

import com.timerpg.street.voice.MicAsk
import com.timerpg.street.voice.MicAsk.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The microphone's permission (voice/MicAsk.kt): when Android will not ask again, the game sees it and opens the app's
 * settings page instead of a tap that does nothing; and the manifest asks for what the uploads' checks need.
 */
class MicAskTest {
    @Test fun grantedIsGranted() {
        assertEquals(Outcome.GRANTED, MicAsk.outcome(granted = true, rationaleBefore = false, rationaleAfter = false, elapsedMs = 5))
        assertEquals(Outcome.GRANTED, MicAsk.outcome(granted = true, rationaleBefore = true, rationaleAfter = false, elapsedMs = 4000))
    }

    @Test fun aFirstNoCanBeAskedAgain() {
        // the first "no" in the dialog: Android will explain and ask again next time (rationale now true)
        assertEquals(Outcome.DENIED, MicAsk.outcome(granted = false, rationaleBefore = false, rationaleAfter = true, elapsedMs = 2500))
        // a dialog dismissed by tapping outside it on the first request: slow, no rationale either side; asked again next tap
        assertEquals(Outcome.DENIED, MicAsk.outcome(granted = false, rationaleBefore = false, rationaleAfter = false, elapsedMs = 3000))
    }

    @Test fun theLastNoBlocks() {
        // the second "no" (Android 11+): the rationale goes from true to false, and Android will not ask again
        assertEquals(Outcome.BLOCKED, MicAsk.outcome(granted = false, rationaleBefore = true, rationaleAfter = false, elapsedMs = 2000))
        // already blocked (the owner's S25): the answer comes back at once with no dialog shown
        assertEquals(Outcome.BLOCKED, MicAsk.outcome(granted = false, rationaleBefore = false, rationaleAfter = false, elapsedMs = 30))
        assertTrue(MicAsk.label(true).contains("settings"))
        assertTrue(MicAsk.label(false).contains("tap to allow"))
    }

    @Test fun theManifestAsksForTheNetworkState() {
        // ConnectivityManager.getActiveNetwork() throws without it: the recorder's uploads read that as "not on Wi-Fi"
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(m.contains("android.permission.RECORD_AUDIO"))
    }
}
