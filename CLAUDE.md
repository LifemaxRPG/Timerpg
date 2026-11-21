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
- The owner is the main player: the game is built for them first; success with others is an afterthought. **Size is not
  a concern** (a 10 GB install is fine if that is what it takes to be as good as possible): ship the big models, many
  voices, high-resolution assets. When several good options exist, use them together for more variety (e.g. voices from
  both Kokoro and Supertonic) rather than picking one to save space.

## Decisions so far

- **Renderer: Filament** (the owner's decision, 2026-11-18). The game is drawn by Filament
  (kotlin-street/shared/src/filament, kotlin-street/filament/TRIAL.md). The classic Compose Canvas renderer is frozen: do
  not fix, tune or extend it any more; it stays only as a fallback until Filament covers everything, then goes. New
  visual work (buildings, ground, light, weather, people, the turn) is done in Filament only, and judged in Filament
  renders (`gradle :desktop:preview -Dmode=filament`).
- **Engine: stay with Filament, not Godot** (considered and decided 2026-11-18): our world is procedural and code-driven,
  so Godot's editor buys little, and switching would mean rewriting the whole app (dialogue model, voices, ASR, tooling).
  The one thing it would give us is physics: when vehicles and knock-over props come, add **Jolt Physics** (C++, through
  the same native layer as llama.cpp) rather than changing engines. Revisit only if a PC version becomes a goal.
- **Art direction: the owner loves the look** of our 2D LimeZu sprites cut into simple 3D geometry (sharp nearest-filtered
  pixels on low-poly boxes, gables, curved walls, lit and shadowed, windows glowing at night): "almost PlayStation 2
  quality", or better, in the owner's words, "a cross between SNES and PlayStation 2, an era of gaming that never existed":
  stylized pixel art as the basis instead of PS2-era realism. Keep new work in this style (props, cars, yards, the town): pixel-art textures on simple real geometry,
  not smooth high-poly models or blurry filtering.
- **Open world with a player-controlled camera** (the owner's decision, 2026-11-19, replacing the side-scrolling street view as
  the default): the player walks anywhere (streets, yards, alleys) with collision, and turns/zooms the camera themselves; the
  camera follows the player's position but never turns on its own (the owner disliked the camera moving around). The old
  street view stays only as an option for comparison. Exception (the owner's request, 2026-11-19): while riding the
  bicycle the camera follows behind the rider by default; touching the camera gives full manual control, and after a
  few seconds without camera input it eases back to following (a setting can turn it off).
- **Hand-made hub towns plus procedural fill** (the owner's decision, 2026-11-21): the world has a set of pre-made,
  hand-authored towns and cities, each with its own big landmarks that make them worth travelling to, and
  procedural generation fills the gaps between them: terrain, countryside, farms and small towns. The town generator
  becomes an authoring tool (draft a layout from a seed, then edit it and save it as data); people, households and
  history are still simulated on top. Travel between hubs by road, rail and, later, ocean liner.
- **Build the world complete from every side**: nothing is built only for one view. Buildings have real backs and
  roofs, every lot has a front yard AND a backyard (fences, patios, sheds, gardens, play sets, pools for the rich
  homes, clotheslines, bins), blocks have alleys and back lanes, and the player's position is kept in world
  coordinates (kotlin-street/docs/TOWN.md). Judge new work from behind and above too, not only from the street.
- Dialogue: **Qwen3.5-9B** (Q4_K_M, the strongest Qwen that fits a phone; Qwen3.6/3.8 are 27B+) through native llama.cpp (kotlin-street/app/src/main/cpp), prompts and facts from our game logic,
  pre-generated by distance with template fallbacks; see kotlin-street/docs/CONVERSATION.md.
- Do not spend time testing weaker models when the S25 can run a better one: pick the best that fits, measure, and only
  fall back (as a quality setting) if it proves too slow or hot. Qwen3.5-9B on a 4-core x86 server: 97% of replies pass
  validate() (0.6B: 89%), ~44 tok/s prefill, ~2.5-5 tok/s generation (server numbers, not the S25).
- Art the LimeZu sprites draw flat but that should stand out (the gym's balcony) is rebuilt as engine geometry (boxes lit
  and shadowed like the buildings), not faked from the 2D picture.
- NPC voices: **Kokoro-82M and Supertonic**, both on device (ONNX Runtime), voices blended per NPC from their seed and traits.

- **Models for agents** (the owner's decision, 2026-11-19): repetitive bulk work (converting pack sprites to 3D with the
  existing pipeline, cutting textures, routine checks, follow-up fixes with a known cause) runs on Sonnet agents; the
  main session and hard work (diagnosis, architecture, judging renders, orchestration) stay on Opus. Check Sonnet's
  output closely and send anything off back for a fix.

- **Usage efficiency** (the owner's decision, 2026-11-19, from the next usage reset; track results in docs/USAGE_LOG.md):
  get more finished work per token. Opus only for diagnosis, design of new systems and judging hard visual problems;
  everything else on Sonnet (building conversions, follow-up fixes with a known cause, merges, routine checks). Agents
  render only a few key views and judge one downscaled contact sheet, not dozens of full-size images; the main session
  views small previews. Sharp briefs with exact files; read only errors from build/test logs; targeted tests while
  working, the full set and the APK once at the end. Avoid rework: verify the real cause and check side/back/above
  views before calling work done. Don't run parallel jobs that edit the same files (sequence them instead of paying
  for merge jobs). Batch the owner's feedback into one job per batch. Log tokens and finished items per window.

## The project

- `src/` - the TypeScript game: the deterministic simulation (`rand(seed, ...keys)`), towns, people, schedules, dialogue
  logic and fact sheets for the language model. It is the source of truth for the world.
- `kotlin-street/` - the Android-only Kotlin/Compose renderer and app (the direction of the project): the 2.5D lit street
  engine (taken from the owner's habits street scene) wearing our LimeZu pixel art, the faked corner turn, the on-device
  language model (Qwen via native llama.cpp) and pre-generated NPC dialogue. See `kotlin-street/STATUS.md`,
  `kotlin-street/README.md` and `kotlin-street/docs/`.
- Work autonomously; the owner prefers that work continues without waiting for prompts when the next step is clear.
- **Send screenshots.** The owner often prompts from work and can't test right away: whenever something visual lands
  (a milestone, a fix, a new building or scene), send preview renders/screenshots unprompted, from the angles that
  matter, with a one-line caption. Say plainly that they are desktop renders, not S25 captures.
