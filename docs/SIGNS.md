# Building Sign Format (`model-info.json`)

Every Timerpg building model declares whether its signboards can be repainted per town instance with the owner's business name (`"naming": "owner"`) or stay untouched as baked (`"naming": "fixed"`).

The Kotlin sign painter (`com.lifemax.rpg.feature.habits.domain.signs`) reads this metadata at load time and paints crisp art-pixel lettering directly onto an instance copy of the sign texture—zero blur, whole-pixel integer scales (`1×`, `2×`, `3×`), and an optional emissive glow mask.

---

## Top-level `"naming"` field

| Value | Meaning | Typical buildings |
|---|---|---|
| `"owner"` | Eligible for per-instance repainting using `SignNames.nameFor(...)` and `SignPainter.paintIfAllowed(...)`. | Barbershop, diner, five-and-dime, salon, funeral home, pharmacy, hardware, motel, laundromat, gas station, bakery, bowling alley, video store, phone repair. |
| `"fixed"` | **Never repainted.** The painter skips the model completely. | Courthouse, post office, fire station, library, school, church, monuments, ballpark, grain elevator, approved fixed landmarks. |

**Default:** If `"naming"` is omitted, `SignSpec.parseModelInfo` defaults to `"fixed"` so existing civic landmarks are never accidentally overwritten.

---

## `"signs"` array schema

Add a `"signs"` array to `model-info.json` listing every repaintable signboard rect on the model's textures.

```json
{
  "model": "diner_midcentury",
  "naming": "owner",
  "signs": [
    {
      "id": "roof_marquee",
      "texture": "signs.png",
      "rect": [4, 6, 138, 36],
      "lines": 1,
      "maxChars": 22,
      "face": "rounded",
      "ink": "#FF5A4E",
      "paper": "#1B2E3C",
      "outline": "#0D171F",
      "align": "center",
      "glow": true,
      "glowColor": "#FF6A58"
    },
    {
      "id": "window_banner",
      "texture": "signs.png",
      "rect": [4, 48, 96, 24],
      "lines": 2,
      "face": "auto",
      "ink": "#B8261E",
      "paper": "#F4ECD8",
      "align": "center",
      "glow": false
    }
  ]
}
```

### Field reference

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `id` | string | yes | `"sign_N"` | Stable name for the signboard (e.g. `"front_fascia"`, `"blade_sign"`, `"roof_marquee"`). |
| `texture` | string | no | `"signs.png"` | Texture filename inside the model directory that holds this sign rect. |
| `rect` | `[x, y, w, h]` | yes | — | Texel rectangle (`x >= 0`, `y >= 0`, `w > 0`, `h > 0`) on `texture` covering the full painted board area (including any baked default text so `paper` completely paints over it). |
| `lines` | `1` or `2` | no | `1` | Maximum line count. Values outside `1..2` are clamped. |
| `maxChars` | integer | no | `0` (unlimited) | Optional character ceiling. When exceeded, the painter shortens (`and` → `&`, business suffix contractions, then initials) before fitting. |
| `face` | string | no | `"auto"` | Bitmap face: `"auto"`, `"serif-block"`, `"rounded"`, or `"sans"`. |
| `ink` | color | no | `"#1A1614"` | Letter color. Accepts `"#RRGGBB"`, `"#AARRGGBB"`, `"0xAARRGGBB"`, or `[r, g, b]` / `[r, g, b, a]`. |
| `paper` | color | no | `"#F2ECE0"` | Signboard background color painted across the entire `rect` first to erase the baked default name. |
| `outline` | color or `null` | no | `null` | Optional outline/shadow color behind letters. Renders as a 1-texel drop shadow at `1×` scale (keeping adjacent glyphs separated) and a crisp 1-texel outline at `2×`/`3×`. |
| `align` | string | no | `"center"` | Horizontal alignment inside `rect`: `"left"`, `"center"`, or `"right"`. |
| `glow` | boolean | no | `false` | If `true`, paints a matching emissive glow mask (lit letter pixels against `0xFF000000` black) when a glow buffer is supplied. |
| `glowColor` | color | no | same as `ink` | Optional override color for lit pixels in the glow mask (useful when night neon should bloom warmer/brighter than daytime paint). |

---

## Era bitmap faces (`face: "auto"`)

When `"face"` is `"auto"` (or omitted), the painter picks a face from the simulation year `era`:

| Era | Face value | Height | Case | Character |
|---|---|---|---|---|
| `< 1950` (1900–1940s) | `"serif-block"` | `7 px` | Uppercase | Bracketed architectural serif-block (brick fascias, transoms, painted wood). |
| `1950–1969` (1950s–60s) | `"rounded"` | `7 px` | Uppercase | Chunky mid-century rounded neon/enamel lettering (diners, motels, drive-ins). |
| `1970+` (1970s–today) | `"sans"` | `9 px` (`7 px` cap + `2 px` descender) | Upper + lower | Clean modern sans-serif (strip malls, salons, pharmacies, video/phone shops). |

All three faces cover `A–Z`, `0–9`, `& . , ' - ! : / +`, and space (`SANS` adds `a–z`). Uppercase-only faces fold lowercase input cleanly.

---

## Fit & abbreviation guarantees

The painter never blurs, stretches non-uniformly, or paints outside `rect`:

1. **Erase first:** fills the full `rect` with `paper` (and clears `rect` to opaque black in the glow mask when `glow: true`).
2. **Whole-pixel scaling:** tries scales `3×`, `2×`, `1×` (1-line first, then balanced 2-line wrap if `lines == 2`).
3. **Abbreviation ladder** (only if the name still overflows at `1×`):
   - replaces standalone `and` with `&`;
   - shortens long business suffixes (`Department Store` → `Store`, `General Store` → `Store`, `Funeral Home` → `Funeral`, `Beauty Parlor` → `Beauty`, `Motor Court` → `Motel`, `Barber Shop` → `Barber`, `Company` → `Co.`, etc.);
   - contracts earlier full words to initials (`Pruitt` → `P.`);
   - clamps to the inner padded box as a last resort so zero glyph pixels can ever escape `rect`.

---

## Legacy format compatibility

`SignSpec.parseJson(jsonText)` also accepts two existing shapes without changes to older assets:

1. **`sign-regions.json`** (`{ "front_sign": [x, y, w, h], ... }`): parsed as `naming = "owner"`, `texture = "signs.png"`, `lines = 2` when `h >= 26` else `1`.
2. **`sign_panels`** inside `model-info.json` (e.g. `barbershop/model-info.json` with `rect_texels`): read automatically as fallback when `"signs"` is absent.

---

## Authoring tips for model makers

- **Match `paper` to the flat board color** inside `rect`, and keep decorative moulding/frames at least 1–2 texels *outside* `rect` so repainting never clips the trim.
- **Recommended board sizes** (at `1×`–`2×` texels on the art-pixel grid):
  - Main fascia: `120–140 × 28–36` texels (`lines: 2` fits long family names cleanly at `1×` and short names at `2×` or `3×`).
  - Blade / side sign: `72–96 × 20–24` texels (`lines: 2`, `maxChars: 24`).
- **Keep the UV island axis-aligned and un-mirrored** on the signboard face so painted lettering reads left-to-right on both sides of a double-sided blade sign (give the back face its own upright UV island or point both faces to the same rect with upright UVs).
- **Still bake a tasteful default name** into the shipped texture—if a building is viewed standalone in a model viewer or preview before runtime repainting, it still looks finished.
