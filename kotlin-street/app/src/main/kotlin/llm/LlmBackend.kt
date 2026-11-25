package com.timerpg.street.llm

/**
 * The on-device language model that gives NPCs their words (Qwen3.5-9B through llama.cpp, natively). It only ever voices
 * facts the simulation already decided: the prompts come ready-made from our game logic (tools/export-dialogue.ts), and
 * every reply is checked ([Dialogue.validate]) before it is shown, with the template line as the fallback.
 */
interface LlmBackend {
    /** True once a model is loaded and [generate] can run. */
    val loaded: Boolean

    /** Why the last [load] failed, or null. */
    val error: String?

    /**
     * Loads a GGUF model file. Blocking (seconds): call it off the main thread. Returns false, with [error] set, on failure.
     * [threads] defaults to the phone's big cores; [contextTokens] is the KV cache size.
     */
    fun load(path: String, threads: Int = 0, contextTokens: Int = 1024): Boolean

    /**
     * Completes the raw [prompt] (ChatML text, already formatted by the exporter) with at most [maxTokens] tokens, handing
     * each new piece of text to [onToken] as it comes (return false from it to stop early). Blocking; calls are serialised
     * (one generation at a time). The KV cache is reused for the longest common prefix with the previous prompt, so the
     * shared rules and an NPC's fact sheet are only processed once. Returns the whole reply text.
     */
    fun generate(prompt: String, maxTokens: Int = 56, temperature: Float = .3f, onToken: (String) -> Boolean = { true }): String

    /** Asks a running [generate] to stop as soon as it can. Safe from any thread. */
    fun cancel()

    /** Timings of the last [load] and [generate], for the speed panel. */
    val stats: LlmStats

    fun close()
}

/** What the model took: the numbers the player's phone decides whether pre-generation is needed. */
data class LlmStats(
    val loadMs: Long = 0,
    /** Tokens in the last prompt, and how many of them were already in the KV cache (not processed again). */
    val promptTokens: Int = 0,
    val reusedTokens: Int = 0,
    /** Time to process the new prompt tokens. */
    val prefillMs: Long = 0,
    /** Tokens generated and the time it took (excluding prefill). */
    val genTokens: Int = 0,
    val genMs: Long = 0,
    /** From the call to the first token of the reply. */
    val firstTokenMs: Long = 0,
    val threads: Int = 0,
    /** Speculative decoding: tokens guessed ahead (by the model's MTP head or by n-gram lookup), and how many were right. */
    val drafted: Int = 0,
    val accepted: Int = 0,
    /** Of those, the n-gram lookup's guesses (copied from the prompt, the reply so far or the bank's lines). */
    val ngramDrafted: Int = 0,
    val ngramAccepted: Int = 0,
    /** Guess-and-check passes; with fixed-shape steps, the filler tokens added to short guesses and how many were right. */
    val steps: Int = 0,
    val padded: Int = 0,
    val padAccepted: Int = 0,
    /** validate()-aware sampling: candidate tokens it took away, and steps where every candidate broke a rule. */
    val guideBanned: Int = 0,
    val guideFallbacks: Int = 0,
) {
    val prefillPerSec: Float get() = if (prefillMs > 0) (promptTokens - reusedTokens) * 1000f / prefillMs else 0f
    val genPerSec: Float get() = if (genMs > 0) genTokens * 1000f / genMs else 0f
}
