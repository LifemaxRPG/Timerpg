# THE FOUNDRY — Industrial-Brutalist Sawtooth Modern Nightclub & Open-Air Courtyard

Drop-in 3D Filament landmark (`foundry-nightclub.glb`) built to `docs/CHATGPT_MODEL_GUIDE.md` and `kotlin-street/docs/LANDMARKS_GLB.md`.

## Architectural Identity
- **Adaptive-Reuse Sawtooth Warehouse**: 3-bay pitched standing-seam slate sawtooth roof with full-width north-light Crittall factory clerestory skylights and red-brick gable end walls.
- **Brutalist Vertical Corner Light Tower**: Front-west raw architectural concrete & blackened-steel shaft carrying double-face vertical `FOUNDRY` architectural blade signage and a 360° illuminated geometric light-cube crown (`11.48 m` peak).
- **Monumental Spanning Street-Line Entry Gantry (`street_gantry`)**: Raw concrete piers and overhead blackened-steel truss spanning a wide open central plaza with double-sided `THE FOUNDRY` marquee signage and downward LED downlights.
- **Open-Air Front Courtyard Lounge (`courtyard_lounge`)**: Heritage brick courtyard walls (`OPEN AIR` & `YARD BAR` street signs), west reclaimed-timber lounge deck under an open steel pergola with concrete & oxblood-coral seating, and east outdoor `YARD BAR` counter with cantilevered steel canopy.
- **360° Boiler-Room Warehouse Interior**: Center-stage square DJ island on a radial laser-grid dance floor surrounded by 4 structural steel lighting pillars & overhead LED truss, overlooked by a rear reclaimed-timber VIP mezzanine balcony in front of a `128×40` `THE FOUNDRY` kinetic strobe & pixel-matrix light wall, plus an east backlit emerald-mint craft bar and west coral-leather lounge booths.

## Build & Verify
```bash
python3 prototypes/foundry-nightclub/make_art.py
python3 prototypes/foundry-nightclub/build_model.py
python3 prototypes/foundry-nightclub/validate_model.py
node prototypes/foundry-nightclub/render_views.mjs
python3 prototypes/foundry-nightclub/package_preview.py
```
