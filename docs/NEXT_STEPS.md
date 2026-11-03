# Next steps (decided with the project owner)

## 1. Replace the current action menu (owner feedback: "not clean, too blunt")
The current panel (click a house -> Kill / Smear / Praise / Gift buttons) is a debug tool, not the game's UI.
Target design:
- **You must go up to a character** (walk next to an NPC, press interact) instead of clicking a building from afar.
- **A dialogue menu per NPC** with several kinds of options: talk / ask about something / persuade / trade / etc.
- **Actions are nuanced and gated**: violence is not a top-level button. It needs proximity, opportunity and intent
  (e.g. a threat first, an argument that escalates), and the option can simply not be offered to a friendly stranger.
- **Consequences**: witnesses, getting caught, becoming "wanted", relationships and reputation changing, the law
  reacting. All of this must be simulated (sim state), not scripted UI.
- Keep the time-travel part: the year slider (or a time-machine device) decides *when* you act.
The sim verbs (kill/rumor/gift/introduce/invest/sabotage) stay as the low-level effects; new layers (relationships,
witnesses, wanted level) go between the UI and those verbs.

## 2. NPCs + LLM dialogue (Qwen)
- NPCs are generated people from the sim (traits, family, wealth, relationships, reputation, and later: secrets/knowledge).
- Add a **knowledge system**: facts ("X took a bribe in 1962"), who knows each fact, trust, gossip spreading (sim rules).
- The LLM only **voices** an NPC from a small structured fact sheet (name, age, job, traits, era, relationship to the
  player, the facts THIS NPC knows). It never decides outcomes. If the model is off, template text is used.
- Backend interface: any OpenAI-compatible chat endpoint (Ollama, LM Studio, llama.cpp server, hosted APIs).
- Model: Qwen3 family. Local dev: `qwen3:8b` (about 5 GB at 4-bit) via Ollama; `qwen3:4b` for weaker machines;
  `qwen3:14b` if the GPU has 12 GB+. Turn thinking mode OFF for dialogue (latency). Phones: use a hosted endpoint.
- Cache replies by (world seed, interventions hash, NPC, topic) so a situation always gets the same answer.

### Decision (owner): fully offline, on the phone, Qwen3-0.6B
- **No server, no network.** The model ships inside the app. Qwen3-0.6B 4-bit GGUF is about 0.4 GB, license Apache-2.0
  (reachable from the cloud sandbox: huggingface.co/Qwen/Qwen3-0.6B-GGUF and unsloth/Qwen3-0.6B-GGUF, so it can be fetched and tested here).
- **Runtime plan:** llama.cpp compiled to WebAssembly (`wllama`) running inside the WebView: no native plugin, and it also
  runs in a desktop/headless browser, so it can be tested in the sandbox. Upgrade path if too slow on phones: a native
  llama.cpp plugin for Capacitor (needs the Android NDK; not testable in the sandbox).
- **Risks to measure first:** speed of WASM on a real phone (multi-threading needs cross-origin isolation inside the Capacitor
  WebView), and whether 0.6B is good enough. Mitigations: very short outputs, tight prompts built from the NPC fact sheet,
  few-shot examples, low temperature, thinking mode off, template text fallback. Swapping to Qwen3-1.7B (~1.1 GB) is a file change.
- Keep one chat interface (`narrate(factSheet, playerLine) -> text`) with backends: templates / wllama on-device / optional OpenAI-compatible server (dev only).

## 3. Still open
- NPCs walking around town (home <-> work), sprites from the Character Generator layers, era-appropriate clothing.
- Cars (era-gated models), 1950s-style houses, recolored villas/shops, curved streets.
- Real-device test of the Android build (docs/ANDROID.md).
