# Timerpg: notes for every session

## Ambition and target hardware (the owner's standing instruction - very important)

Build this game to be as AAA as possible. The development and test phone is a **Samsung Galaxy S25 Ultra** (Snapdragon 8
Elite for Galaxy: 8 fast Oryon cores, no little cores; Adreno 830 GPU; Hexagon NPU; 12 GB RAM; Arm v9 with dotprod/i8mm).

- Design for that phone's full power first. Do not shoot an idea down because a cheap phone could not run it, and do not
  start with limitations we may not need. Build it as well as possible, measure on the S25, and only cut back later if it
  turns out to be necessary (and then preferably as a quality setting, not by removing the feature).
- Use the hardware: all CPU cores, the GPU (e.g. llama.cpp's Adreno/OpenCL backend), the NPU where it pays off, the RAM
  (bigger language models such as Qwen3-1.7B or 4B, high-quality voices, large caches), CPU features detected at runtime.
- "Not every device can run it" is acceptable. The game may ship with a high-end target and scale down later.
- Still care about what a high-end phone can't escape: heat and battery under sustained load. Treat those as scheduling
  problems (bursts, pauses during heavy animation, background work while idle or charging), not as reasons to cut quality.
- Be honest about measurements: say what was measured on the S25 and what is an estimate.

## The project

- `src/` - the TypeScript game: the deterministic simulation (`rand(seed, ...keys)`), towns, people, schedules, dialogue
  logic and fact sheets for the language model. It is the source of truth for the world.
- `kotlin-street/` - the Android-only Kotlin/Compose renderer and app (the direction of the project): the 2.5D lit street
  engine (taken from the owner's habits street scene) wearing our LimeZu pixel art, the faked corner turn, the on-device
  language model (Qwen via native llama.cpp) and pre-generated NPC dialogue. See `kotlin-street/STATUS.md`,
  `kotlin-street/README.md` and `kotlin-street/docs/`.
- Work autonomously; the owner prefers that work continues without waiting for prompts when the next step is clear.
