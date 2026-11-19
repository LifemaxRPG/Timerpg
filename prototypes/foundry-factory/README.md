# THE FOUNDRY IRONWORKS (1938) — Historic Working Factory (Pre-Conversion)

Drop-in 3D Filament landmark (`foundry-factory.glb`) depicting **THE FOUNDRY** during its original working industrial era (`1924–2015`, built `1938`) before its 2024 adaptive-reuse conversion into a modern nightclub.

## Historical Continuity with `foundry-nightclub.glb`
Shares the **exact same `28.56 m × 29.80 m` lot footprint, `19.87 m × 18.00 m` red-brick sawtooth warehouse shell, 3-bay pitched sawtooth roof, front-west tower, front brick yard walls, and 3 door locations** (`door_main`, `door_back`, `door_side`), showing how every modern club feature originated in the working factory:
- **Corrugated-Tin Sawtooth Roof & Ridge Ventilators** -> later restored to dark architectural slate.
- **Red-Brick Furnace Smokestack & Iron Cupola Hood** (`11.48 m`) with vintage vertical `FOUNDRY` enamel board -> later clad in raw brutalist concrete & neon light-cube.
- **Brick & Riveted-Iron Gatehouse Gantry (`yard_gantry`)** with `THE FOUNDRY` vintage porcelain-enamel header -> later converted into the concrete club entry arch.
- **Working Front Yard (`foundry_yard`)**: `IRONWORKS` & `SHIPPING` street signs, west corrugated-iron crate & pig-iron shelter shed (later the timber pergola lounge), and east brick weigh-scale booth with oil drums (later the outdoor `YARD BAR`).
- **Working Foundry Interior**: Center molten-iron crucible casting pit (`HOT IRON`) with overhead safety-yellow travelling bridge crane & hoist (later the 360° center DJ island), elevated rear oiled-timber foreman's catwalk overlooking the floor in front of a glowing `128×40` `IRON FOUNDRY` blast-furnace hearth & blower tanks (later the VIP mezzanine & LED matrix wall), east machinist workbench & pegboard tool crib (later the east craft bar), and west casting-flask pattern benches (later the coral lounge booths).
- **Rear Freight Dock & Coal/Coke Bunker (`service_yard`)**: Raised concrete loading dock with wooden freight crates, roll-up iron freight door, twin blast-furnace exhaust stacks, and east brick coal/coke storage bunker (later the chiller compound).

## Build & Verify
```bash
python3 prototypes/foundry-factory/make_art.py
python3 prototypes/foundry-factory/build_model.py
python3 prototypes/foundry-factory/validate_model.py
node prototypes/foundry-factory/render_views.mjs
python3 prototypes/foundry-factory/package_preview.py
```
