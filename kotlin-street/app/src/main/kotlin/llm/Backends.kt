package com.timerpg.street.llm

/**
 * Where the dialogue engine gets its language model from. The integration sets [create] to `{ NativeLlm() }`; until then
 * (or if it returns null or throws, e.g. the native library is missing) every NPC speaks its template lines.
 * Called on the dialogue worker thread, never on the main thread.
 */
object Backends {
    @Volatile var create: () -> LlmBackend? = { null }
}
