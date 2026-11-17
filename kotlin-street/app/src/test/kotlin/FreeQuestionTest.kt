package com.timerpg.street.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the player says out loud goes to the right topic, and into the prompt in place of the menu's question. */
class FreeQuestionTest {
    private fun topic(intent: String, prompt: String? = "<|im_start|>system\nFACTS<|im_end|>\n<|im_start|>user\nNice day, isn't it?<|im_end|>\n<|im_start|>assistant\n") =
        Topic(intent, intent, 0, "template $intent", prompt, emptyList(), "Ann Lee")
    private val npc = Npc("p1", "Ann Lee", "Ann", 40, "b1", "Shop", 0, 0, "owner", "baker",
        listOf(topic("greet", null), topic("chat"), topic("work"), topic("family"), topic("rumors"), topic("secret")))

    @Test
    fun wordsFindTheirTopic() {
        assertEquals("family", DialogueEngine.topicFor(npc, "Are you married? Do you have kids?")?.intent)
        assertEquals("work", DialogueEngine.topicFor(npc, "What do you do for work around here")?.intent)
        assertEquals("rumors", DialogueEngine.topicFor(npc, "Heard anything interesting lately?")?.intent)
        assertEquals("secret", DialogueEngine.topicFor(npc, "What are you hiding from me?")?.intent)
        assertEquals("chat", DialogueEngine.topicFor(npc, "Lovely weather today.")?.intent)
        // a topic this person has no prompt for falls back to chat
        assertEquals("chat", DialogueEngine.topicFor(npc, "Who is the mayor of this town?")?.intent)
    }

    @Test
    fun theQuestionReplacesTheMenuLine() {
        val p = DialogueEngine.withQuestion(npc.topics[1].prompt!!, "Where can I buy bread?")
        assertTrue(p.contains("<|im_start|>user\nWhere can I buy bread?<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue(!p.contains("Nice day"))
        assertTrue(p.startsWith("<|im_start|>system\nFACTS<|im_end|>"))
    }
}
