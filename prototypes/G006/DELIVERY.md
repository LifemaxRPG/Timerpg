# G006 — Sign Painter Delivery (`gemini/G006-sign-painter`)

## What was built

Plain Kotlin (zero Android, Filament, or Compose dependencies) under `kotlin-street/shared/src/main/kotlin/domain/signs/` (`package com.lifemax.rpg.feature.habits.domain.signs`):

1. **`PixelFont.kt`** — Three era-styled bitmap faces on the art-pixel grid matching the 7-pixel cap-height proportions of `kotlin-street/tools/landmarks/pixel_font.py`:
   - `SERIF_BLOCK` (1900–1940s, 7 px tall, uppercase)
   - `ROUNDED` (1950s–1960s, 7 px tall, uppercase)
   - `SANS` (1970s–today, 9 px tall: 7 px cap + 2 px descender, uppercase + lowercase)
   - Full coverage for `A–Z`, `0–9`, `& . , ' - ! : / +`, space (plus `a–z` on `SANS`), flat 128-entry ASCII lookup table for zero-allocation O(1) lookup, and strict height/width validation on every glyph.
2. **`SignSpec.kt`** — Parses `model-info.json` (`"naming": "owner" | "fixed"`, defaulting to `FIXED` when omitted so civic landmarks are never repainted), the `"signs"` array (`id`, `texture`, `rect`, `lines`, `maxChars`, `face`, `ink`, `paper`, `outline`, `align`, `glow`, `glowColor`), legacy `sign-regions.json` (`{ "name": [x,y,w,h] }`), and `sign_panels` (`rect_texels`) fallback.
3. **`SignPainter.kt`** — Zero-allocation ARGB (`0xAARRGGBB`) rasterizer writing crisp whole-pixel integer blocks (`1×`, `2×`, `3×`), never blurred:
   - Always paints `paper` across the full `rect` first to cover any old baked name, and clears the glow mask rect to opaque black (`0xFF000000`) before painting lit letter pixels when `glow = true`.
   - Picks the largest integer scale (`3× → 2× → 1×`) across 1-line or balanced 2-line layouts; uses optical visual line height so `SANS` lines without descenders center cleanly and fit `2×` on two-line boards.
   - Falls back cleanly through an in-place abbreviation ladder (`and` → `&`, business suffix contractions, word-to-initial contraction, then clamped fit) so no glyph pixel can ever escape `rect`.
   - Uses reusable `PaintScratch` buffers (`ThreadLocal` default or caller-provided) for 0 bytes allocated per `paint()` call after warm-up.
   - `SignPainter.paintIfAllowed(config, ...)` enforces `naming == NamingMode.OWNER` at the call boundary.
4. **`SignNames.kt`** — Deterministic era-styled business name generator via `WorldRand.rand`, covering Timerpg's business roles (barber, funeral home, diner, five-and-dime, salon/nails, drugstore/pharmacy, hardware, general store, grocery, motel, laundromat, gas station, auto repair, cafe, bakery, bowling, video store, phone repair, tavern/lounge, mill/feed, antiques, plus generic fallback). Zero real brand names.
5. **`SignPainterTest.kt`** (`kotlin-street/desktop/src/crossroadsTest/kotlin/SignPainterTest.kt`) — JUnit 4 suite covering glyph heights & coverage, strict rect clipping & old-name overwrite + glow-mask parity, shrink/wrap/abbreviation on long names, `model-info.json` (`owner` vs `fixed`) + legacy `sign-regions.json` parsing, deterministic `SignNames` across seeds/owners/eras, zero-allocation `ThreadMXBean` check (1,000 calls = 0 B), and 20-sign proof sheet PNG generation.
6. **`docs/SIGNS.md`** — Model-maker schema guide with full worked JSON example, era face table, legacy compatibility notes, and UV/authoring checklist.
7. **`prototypes/G006/proof-sheet.png`** — 1:1 art-pixel proof sheet (`484×480`) showing 20 painted signs across all 3 faces, `1×`/`2×`/`3×` scales, short and long names, 1- and 2-line wraps, left/center alignment, outlines/drop shadows, tight-board abbreviation, and 6 side-by-side glow masks.

---

## Tests

- **tests not run here:** no JDK, `javac`, `kotlinc`, or Gradle is installed in this container (`which java javac kotlinc gradle` all return none; per `docs/queue/README.md` we do not install heavy toolchains here).
- **Command for build server:** `gradle :desktop:crossroadsTest --tests 'SignPainterTest'`
- **What was checked directly here:**
  - Parsed every glyph definition directly out of `PixelFont.kt` in Python and validated all 164 glyphs (`SERIF_BLOCK`: 46, `ROUNDED`: 46, `SANS`: 72) for exact height, uniform row widths, valid pixel characters, and required character coverage.
  - Audited all 5 Kotlin files for balanced delimiters, valid Kotlin syntax (caught and fixed a local `data class` in tests, a `ProofProofDemo` class-name typo, a `const val` with `.toInt()`, and a glow-pixel counter typo in `SignPainterTest.kt`).
  - Rendered `prototypes/G006/proof-sheet.png` using a Python mirror of `SignPainter.kt` reading the exact bitmaps from `PixelFont.kt`, visually inspected 3 iterations at 2× zoom, and fixed real legibility defects before delivering:
    - Fixed `ROUNDED` `'N'` (3 solid middle rows read like `'M'` in `BOB'S DINER`), `'M'`, `'W'`, `SERIF_BLOCK` `'O'`/`'0'`, and `SANS` `'V'`/`'v'` (read like `'U'` in `Video`).
    - Fixed 1× outline crowding (`MABEL'S DRIVE-IN` touched across letters) by using a crisp 1-texel drop shadow at `1×` and a full 1-texel outline at `2×`/`3×`.
    - Fixed two-line `2×` scaling for `SANS` (`Kessler / Nail Room` popped from cramped `1×` to crisp `2×` on `138×36` once line-1 descenderless cap height was measured optically).
  - Note on provenance: `prototypes/G006/proof-sheet.png` was produced from `PixelFont.kt`'s exact glyph bitmaps via that Python script because no JVM is available here; running `SignPainterTest.rendersTwentySignProofSheetPng` on the build server regenerates the same 20-sign layout directly from Kotlin `SignPainter.paint`.

---

## Self-review

1. **What is the single weakest part of this delivery, and what did you do about it?**
   Because this container has no JDK/Gradle, the Kotlin code and the `ThreadMXBean` zero-allocation test have not been compiled or executed by `kotlinc`/JVM here yet. To reduce risk as far as possible without a compiler, I audited every public signature and import across all 5 `.kt` files against `WorldRand.kt` and `desktop/build.gradle.kts`, verified all 164 glyph bitmaps programmatically from `PixelFont.kt`, caught and fixed four real compile/test bugs (`const val ...toInt()`, local `data class`, `ProofProofDemo` typo, glow counter assertion), and visually inspected three iterations of the 20-sign proof sheet to fix real font readability defects.
2. **What did you check by looking (renders) or by running (tests), and what did you only reason about?**
   - *Checked by looking:* all 20 proof-sheet signs and 6 glow masks at 2× magnification (legibility of every face, `1×`/`2×`/`3×` integer scaling, descender clearance on `Ashby`/`Pharmacy`/`Tape`, outline separation on `MABEL'S DRIVE-IN` and `BOB'S DINER`, two-line wrap on `Bartholomew Henderson Funeral Home`, and tight-board abbreviation on `PRUITT & SONS STORE`).
   - *Checked by running scripts:* bitmap dimensions/coverage of all 164 glyphs in `PixelFont.kt`, delimiter balance, and cross-file symbol checks.
   - *Only reasoned about:* Kotlin JVM compilation, JUnit test pass status, and `ThreadMXBean` reporting `0L` bytes allocated on HotSpot 21 (the hot path uses `CharArray` scratch buffers, primitive loops, and a 128-entry `Array<PixelGlyph?>` with zero boxing or `String` creation, but HotSpot must confirm it on the build server).
3. **If the owner looked at this for 10 seconds, what would they notice first?**
   The contrast across eras on the proof sheet—the classic 1920s gilt/parchment serif-block boards (`HOLLIS BARBER SHOP`, `PRUITT & SONS FUNERAL HOME`), the chunky mid-century neon `BOB'S DINER` and 3× `MOTEL` with their matching night glow masks, and the crisp mixed-case modern `Ashby Hair Co.` and `Kessler Nail Room`—every board reads cleanly at art-pixel resolution without blurry upscaling.

---

## Ideas for the team

- **Painted-over "ghost signs" on ownership/era turnover:** Because `SignPainter` knows both the previous owner's sign and the new owner's `paper` color, when a business changes hands across decades (say, `Hollis Barber Shop` in 1925 becomes `Ashby Hair Co.` in 1984), a subtle weathered variant could optionally blend 8–12% of the *previous* owner's text ink into the fresh `paper` coat before painting the new name—real small-town brick buildings often show faint ghost lettering of the old shop underneath, turning Timerpg's century of town history into something the player spots just walking down Main Street.

---

## Usage (wiring into `LandmarkKit` / per-instance sign textures)

```kotlin
import com.lifemax.rpg.feature.habits.domain.signs.SignNames
import com.lifemax.rpg.feature.habits.domain.signs.SignPainter
import com.lifemax.rpg.feature.habits.domain.signs.SignSpec

// 1. Parse model-info.json (or legacy sign-regions.json) once when loading a building template:
val signConfig = SignSpec.parseJson(modelInfoJsonText)

// 2. For a placed building instance, check whether this model takes owner names:
if (signConfig.repaintable) {
    val businessName = SignNames.nameFor(
        kind = building.kind,          // e.g. "barber", "diner", "funeral_home", "salon"
        ownerSurname = owner.surname,  // e.g. "Hollis"
        ownerFirst = owner.firstName,  // e.g. "Bob"
        era = world.year,              // e.g. 1956
        seed = world.seed,             // deterministic via WorldRand
    )

    // 3. Clone the template's ARGB texture pixels (IntArray, 0xAARRGGBB) and optional glow buffer:
    val signPixels = templatePixels.copyOf()
    val glowPixels = templateGlowPixels?.copyOf()

    for (spec in signConfig.signsForTexture("signs.png")) {
        SignPainter.paintIfAllowed(
            config = signConfig,
            spec = spec,
            text = businessName,
            era = world.year,
            out = signPixels,
            stride = textureWidth,
            glowOut = glowPixels,
            glowStride = textureWidth,
        )
    }
    // Upload signPixels (and glowPixels if present) to the instance's Filament texture.
}
```
