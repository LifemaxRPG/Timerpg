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

    @Test
    fun echoesSayTheQuestionBackFromTheirSide() {
        assertTrue(Fillers.echo("who's the wealthiest person in this town", .9f, 0)!!.startsWith("Who's the wealthiest person in this town? "))
        assertTrue(Fillers.echo("how old are you", .9f, 0)!!.startsWith("How old am I? "))
        assertTrue(Fillers.echo("were you born here", .2f, 0)!!.startsWith("Was I born here? "))
        assertTrue(Fillers.echo("what do you think of my hat", .9f, 0)!!.startsWith("What do I think of your hat? "))
        assertTrue(Fillers.echo("do you know me", .9f, 0)!!.startsWith("Do I know you? "))
        assertTrue(Fillers.echo("tell me about the mill", .9f, 0) == null)   // not a question
        assertTrue(Fillers.echo("what", .9f, 0) == null)
    }
}
