package com.timerpg.street.dialogue

import com.timerpg.street.llm.Dialogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner's first real conversation on the S25 (reports branch, talk/2026-11-23/060618-642-p3.jsonl): Gladys Chen
 * (imm:2021:1) in gamma 2025, seven spoken turns answered live by the light model on the NPU. Each turn is replayed as
 * the game asks it: the words to their topic ([DialogueEngine.topicFor]), the prompt as a free question builds it
 * ([DialogueEngine.freeTopic], from the shipped dialogue.json), and a stub model that first says what the real one said
 * and, asked again (the engine's second try), a grounded line. The real model's bad replies are turned down, the
 * second try's line is kept.
 */
class ConversationLogTest {
    private val data = DialogueData.parse(File("src/main/assets/town/gamma/dialogue.json").readText())
    private val gladys = data.npcs.first { it.id == "imm:2021:1" }

    private class Turn(val said: String, val real: String, val intent: String, val retry: String, val realIsBad: Boolean)

    private val turns = listOf(
        Turn("Hey", "Hello there, how are you getting along? The weather is quite lovely today.", "chat",
            "", false),
        Turn("Yeah it is pretty nice weather are you from here", "Yes, I am from Mapleridge. It is a lovely place to call home.", "chat",
            "Not born here, no. I came over from Golden Heights in 2021.", true),
        // (fine here: turn 2's "It is a lovely place..." was turned down, so this is not a repeat; turn 4 repeats it)
        Turn("Yo can you hear me", "Yes, I can hear you quite clearly. It is a lovely day for it, isn't it?", "chat",
            "Loud and clear, dear!", false),
        Turn("Yeah it is a lovely day anyways I was asking are you from here or did you move here",
            "I have lived in Mapleridge all my life. It is a lovely day for it, isn't it?", "chat",
            "I moved here in 2021, from Golden Heights.", true),
        Turn("Do you know any secrets about this town", "I have not shared any secrets with anyone yet. Perhaps we can talk about something else instead.", "rumors",
            "Oh, keep this quiet, but Margaret Singh holds a bitter grudge against Arthur Calloway.", true),
        Turn("I didn't ask if you shared any secrets I asked if you knew any",
            "I have heard whispers about the old mill, but I do not know them myself. Perhaps we could talk about the new library instead?", "rumors",
            "Well, between us, Margaret Singh can't stand Arthur Calloway.", true),
        Turn("What's going on with the new library", "The new library is a lovely place for reading, and I am glad you asked about it.", "rumors",
            "A new library? I haven't heard of one, but Margaret is still sore at Arthur Calloway.", true),
    )

    /** What the engine's two tries keep: the first reply that passes, else null (the template). */
    private fun answer(t: Topic, model: List<String>): String? =
        model.take(DialogueEngine.MAX_TRIES).firstNotNullOfOrNull { Dialogue.validate(it, t.must, t.self, t.prompt ?: "", t.recent) }

    @Test
    fun replaysTheLog() {
        val said = ArrayList<String>()
        for ((i, turn) in turns.withIndex()) {
            val topic = DialogueEngine.topicFor(gladys, turn.said)!!
            assertEquals("turn ${i + 1}: topic", turn.intent, topic.intent)
            // (the log had one memory from turn 2 on: the last exchange)
            val memory = if (i == 0) emptyList() else listOf("Earlier the player told you: \"${turns[i - 1].said}\" You answered: \"${said.last()}\"")
            val asked = DialogueEngine.freeTopic(topic, turn.said, memory, said)
            val p = asked.prompt!!
            val why = Dialogue.whyRejected(turn.real, asked.must, asked.self, p, asked.recent)
            if (turn.realIsBad) assertNotNull("turn ${i + 1}: \"${turn.real}\" should be turned down", why)
            else assertNull("turn ${i + 1}: $why", why)
            val line = answer(asked, listOf(turn.real, turn.retry))
            assertEquals("turn ${i + 1}", if (turn.realIsBad) turn.retry else turn.real, line)
            said += line!!
        }
    }

    @Test
    fun whyEachBadReplyIsTurnedDown() {
        val chat = DialogueEngine.topicFor(gladys, "Hey")!!
        val rumors = DialogueEngine.topicFor(gladys, "Do you know any secrets about this town")!!
        fun why(t: Topic, said: String, real: String, recent: List<String>): String? {
            val a = DialogueEngine.freeTopic(t, said, emptyList(), recent)
            return Dialogue.whyRejected(real, a.must, a.self, a.prompt!!, a.recent)
        }
        val log = turns.map { it.real }
        assertEquals("said they are from here (they moved here)", why(chat, turns[1].said, log[1], log.take(1)))
        assertEquals("said it again", why(chat, turns[2].said, log[2], log.take(2)))
        assertEquals("said they are from here (they moved here)", why(chat, turns[3].said, log[3], log.take(3)))
        assertEquals("left out \"Margaret\"", why(rumors, turns[4].said, log[4], log.take(4)))
        assertEquals("changed the subject", why(rumors, turns[4].said, "I have not told Margaret any secrets. Perhaps we can talk about something else instead.", emptyList()))
        assertEquals("left out \"Margaret\"", why(rumors, turns[5].said, log[5], log.take(5)))
        assertEquals("made up a place or event (\"the old mill\")", why(rumors, turns[5].said, "Margaret told me about the old mill.", emptyList()))
        assertEquals("made up a place or event (\"The new library\")", why(rumors, turns[6].said, "The new library is lovely, Margaret says.", emptyList()))
    }

    @Test
    fun theSheetsCarryTheFacts() {
        val chat = gladys.topics.first { it.intent == "chat" }.prompt!!
        // where she is from, and when she came (the town's name is the seed's placeholder until the owner names it)
        assertEquals("Mapleridge", data.townName)
        assertTrue(chat.contains("a woman who moved to Mapleridge from Golden Heights in 2021."))
        // her manner
        assertTrue(chat.contains("You are warm and kind, and like a good chat."))
        // what she knows: the gossip she tells a friend, her own secret kept (a topic she would rather not talk about)
        val rumors = gladys.topics.first { it.intent == "rumors" }
        assertTrue(rumors.prompt!!.contains("You are quietly telling the player this secret: Margaret Singh holds a bitter grudge against Arthur Calloway."))
        assertEquals(listOf("Margaret"), rumors.must)
        val secret = gladys.topics.first { it.intent == "secret" }.prompt!!
        assertTrue(secret.contains("You have a private matter of your own, but you do not trust the player enough yet"))
        assertTrue(secret.contains(Dialogue.AVOID))
        // no mill, no library
        for (t in gladys.topics) assertFalse(Regex("(?i)\\b(mill|library)\\b").containsMatchIn(t.prompt ?: ""))
    }

    @Test
    fun aFreeQuestionsPrompt() {
        val chat = gladys.topics.first { it.intent == "chat" }
        val a = DialogueEngine.freeTopic(chat, "Yo can you hear me", emptyList(), listOf("One line.", "Two lines.", "Three lines.", "Four lines."))
        val p = a.prompt!!
        // the player's words, no example answer (it answered the menu's question and was copied), the last two replies
        // quoted (prefill time matters), the last three checked
        assertTrue(p.contains("<|im_start|>user\nYo can you hear me<|im_end|>"))
        assertFalse(p.contains("Example of the kind of answer"))
        assertTrue(p.contains("You already said: \"Three lines.\" \"Four lines.\" Do not repeat those words.<|im_end|>"))
        assertEquals(listOf("Two lines.", "Three lines.", "Four lines."), a.recent)
        // a reply quoted in a memory is not quoted twice
        val m = DialogueEngine.freeTopic(chat, "Hi", listOf("Earlier the player told you: \"Hey\" You answered: \"Four lines.\""), listOf("Three lines.", "Four lines."))
        assertTrue(m.prompt!!.contains("You already said: \"Three lines.\" Do not repeat"))
        // chat drops the words to name; the secret being told keeps them
        assertEquals(emptyList<String>(), DialogueEngine.freeTopic(gladys.topics.first { it.intent == "work" }, "job?", emptyList(), emptyList()).must)
        assertEquals(listOf("Margaret"), DialogueEngine.freeTopic(gladys.topics.first { it.intent == "rumors" }, "gossip?", emptyList(), emptyList()).must)
    }

    @Test
    fun secretWordsFindTheirTopic() {
        assertEquals("rumors", DialogueEngine.topicFor(gladys, "Do you know any secrets about this town")?.intent)
        assertEquals("rumors", DialogueEngine.topicFor(gladys, "I didn't ask if you shared any secrets I asked if you knew any")?.intent)
        assertEquals("secret", DialogueEngine.topicFor(gladys, "Are you hiding anything?")?.intent)
        assertEquals("secret", DialogueEngine.topicFor(gladys, "Do you have any secrets?")?.intent)
        assertEquals("chat", DialogueEngine.topicFor(gladys, "are you from here or did you move here")?.intent)
    }
}
