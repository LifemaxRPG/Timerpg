package com.timerpg.street

import com.timerpg.street.dialogue.DialogueEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reply streamed a whole sentence at a time: only the sentences the model has gone on past count as complete. */
class StreamSentencesTest {
    private fun c(t: String) = DialogueEngine.completeSentences(t)

    @Test fun nothingCompleteUntilTheModelGoesOn() {
        assertNull(c("Oh, the bakery"))
        // a full stop at the very end may still be "Mr." or the start of "..."
        assertNull(c("The bakery is on Main Street."))
    }

    @Test fun oneSentenceThenTwo() {
        assertEquals("The bakery is on Main Street." to 1, c("The bakery is on Main Street. It opens"))
        assertEquals("Yes! Right there." to 2, c("Yes! Right there. And"))
    }

    @Test fun closingQuotesStayWithTheirSentence() {
        assertEquals("She said \"no.\"" to 1, c("She said \"no.\" Then"))
    }

    @Test fun decimalsAreNotSentenceEnds() {
        assertNull(c("It costs 3.50 at the"))
    }
}
