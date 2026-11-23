package com.timerpg.street.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The port of validate() gives exactly what the TypeScript one gives (vectors from tools/validate-vectors.ts). */
class ValidateTest {
    @Test
    fun matchesTheTypeScriptValidate() {
        val wrong = ValidateVectors.all.mapNotNull { v ->
            val got = Dialogue.validate(v.raw, v.must, ValidateVectors.SELF, v.known, v.recent)
            if (got == v.expected) null else "validate(${v.raw.take(60)}) = $got, expected ${v.expected}"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun theTypeScriptTestsCases() {
        val self = "Ann Lee"
        assertEquals("I'm married to Bob.", Dialogue.validate("\"I'm married to Bob.\" <|im_end|>", emptyList(), self))
        assertNull(Dialogue.validate("As an AI language model, I cannot", emptyList(), self))
        assertEquals("One. Two.", Dialogue.validate("One. Two. Three. Four. Five.", emptyList(), self))
        assertNull(Dialogue.validate("I am Ann Lee and I live here.", emptyList(), self))
        assertEquals("Between us, Ann is broke.", Dialogue.validate("Between us, Ann is broke.", listOf("Ann"), ""))
        assertNull(Dialogue.validate("I heard nothing.", listOf("Ann"), ""))
    }

    @Test
    fun previewTidiesAStreamingReply() {
        assertEquals("Hello there", Dialogue.preview("\"Hello  there"))
        assertEquals("Hi.", Dialogue.preview("Hi.<|im_"))
        assertEquals("", Dialogue.preview("<think>\nstill thinking"))
        assertEquals("Well", Dialogue.preview("<think>\n\n</think>\n\nWell"))
    }
}
