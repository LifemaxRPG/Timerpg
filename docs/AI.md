# Dialogue AI (Qwen3-0.6B, fully on-device)

People's lines can be voiced by a small language model that runs inside the game (WebAssembly via
[wllama](https://github.com/ngxson/wllama)). Nothing is sent anywhere. The model only gives words to facts the
simulation already decided (`src/llm/factsheet.ts`); it never chooses outcomes. If it is off, slow, or its reply
fails the fact check, the game uses the built-in template line (`narrate` in `src/game/dialogue.ts`).

## Turn it on
1. Get `Qwen3-0.6B-Q4_K_M.gguf` (about 0.4 GB, Apache-2.0), e.g. from
   `https://huggingface.co/unsloth/Qwen3-0.6B-GGUF`.
2. Either put it at `models/qwen3-0.6b-q4_k_m.gguf` next to the game (the dev server serves it), or press
   **🧠 AI** in the game and choose the file. It is copied into the browser's private storage once and loaded
   from there afterward, with no network.

## Where it works
- `npm run dev` / `npm run preview` (served over http): yes. These send the headers that allow multi-threading.
- The Android (Capacitor) app: yes, single-threaded (the WebView is served from `localhost`).
- `export/timerpg.html` opened as a plain file: **no**. Browsers refuse module workers on `file://`, so the game
  detects this and uses the built-in lines.

## What is voiced
Greetings, chat, family, work, praise, gifts, and sharing rumors/secrets. "Ask about the town" stays a template
(a 0.6B model only produces filler there). Replies are checked: no stage directions, no assistant-speak, no
self-introductions, and family/work/rumor answers must contain the real name from the facts.

## Speed
Measured in a slow 4-core sandbox with rendering paused: about 8 s to the first word and roughly 10 tokens/s.
Phones should be faster, but this is unmeasured on a real device. After two timeouts the game switches the AI
off ("too slow") and uses templates. Tunables: `Llm.timeoutMs`, `nPredict` and `n_ctx` in `src/llm/backend.ts`.
