package com.timerpg.street.llm

/**
 * Where the dialogue engine gets its language model from: [NativeLlm] (llama.cpp in the APK). If this returns null or
 * throws, every NPC speaks its template lines (the AI panel then says why and offers no model picker, so nobody copies
 * a 400 MB file the phone cannot run). Called on the dialogue worker thread, never on the main thread, and again on Retry.
 *
 * No Context is passed to NativeLlm on purpose: [com.timerpg.street.dialogue.DialogueEngine] handles memory pressure
 * itself (it unloads the model and reloads it when the game comes back), so NativeLlm must not free it behind its back.
 */
object Backends {
    @Volatile var create: () -> LlmBackend? = {
        val llm = NativeLlm()
        // loads the native libraries now (worker thread), so a phone that cannot run them is known before a model is picked
        llm.unavailable?.let { throw IllegalStateException(it) }
        llm
    }
}
