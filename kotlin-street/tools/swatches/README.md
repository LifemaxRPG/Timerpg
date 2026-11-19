# tools/swatches: the swatch library

Seamless tiles cut from the LimeZu art (32 px, the art's pixel is 2 x 2 texels) for anything we build in code.
See docs/STORES.md "Textures cut from the art". `swatches.py` (cutter, atlas, Kotlin data), `swatches.json` (the
entries, catalogue format), `pack_stores.py` (the pack's store buildings), `atlas/` (atlas.png, atlas.json).
No raw pack files here: the cutter reads an extracted pack (`--root exteriors=DIR`).
