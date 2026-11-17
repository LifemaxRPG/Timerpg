package com.timerpg.street.dialogue

import org.junit.Assert.assertTrue
import org.junit.Test

class FillersTest {
    @Test
    fun picksFitTheQuestionAndThePerson() {
        val warmBorn = (0 until 20).map { Fillers.pick("Were you born here?", .8f, it) }.toSet()
        assertTrue(warmBorn.any { it.startsWith("Born here?") })
        val curtWhere = (0 until 20).map { Fillers.pick("Where did you grow up?", .1f, it) }.toSet()
        assertTrue(curtWhere.any { it.startsWith("Where?") })
        assertTrue(curtWhere.none { it.contains("Oh, now that's") })   // a curt person is not gushing
    }

    @Test
    fun everyPickIsAmongWhatIsVoicedAhead() {
        for (w in listOf(.1f, .9f)) {
            val all = Fillers.all(w).toSet()
            for (q in listOf("Were you born here?", "Who runs the bank?", "Why is the mill closed?", "Tell me about your family", "", "Their work"))
                for (seed in 0 until 30) assertTrue(Fillers.pick(q, w, seed) in all)
        }
    }

    @Test
    fun noFillerAnswersTheQuestion() {
        for (w in listOf(.1f, .9f)) for (f in Fillers.all(w)) assertTrue(f, !f.contains("don't know") && !f.contains("Yes") && !f.contains("No,"))
    }
}
