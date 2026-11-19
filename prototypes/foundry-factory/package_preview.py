#!/usr/bin/env python3
"""
Builds the composite review sheet (foundry-factory-review.png & .jpg at 1304x1654),
interactive preview.html, and self-contained foundry-factory-model-and-previews.zip
for prototypes/foundry-factory/, copies foundry-factory.glb + model-info.json into
kotlin-street/app/src/main/assets/landmarks/foundry-factory/, and registers
landmark_glb_foundry_factory in kotlin-street/app/src/main/assets/landmarks/landmarks.json.
"""
import json
import shutil
import struct
import zipfile
import zlib
from pathlib import Path

from pixel_font import FONT

R = Path(__file__).resolve().parent
RENDERS = R / "renders"


def read_png_rgba(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h, bd, ct, comp, filt, inter = struct.unpack(">IIBBBBB", chunk)
            assert bd == 8 and ct == 6
        elif ctype == b"IDAT":
            idat.extend(chunk)
    raw = zlib.decompress(bytes(idat))
    stride = w * 4
    rows = []
    idx = 0
    prev = bytearray(stride)
    for _ in range(h):
        f = raw[idx]
        idx += 1
        cur = bytearray(raw[idx:idx + stride])
        idx += stride
        if f == 1:
            for i in range(4, stride):
                cur[i] = (cur[i] + cur[i - 4]) & 0xFF
        elif f == 2:
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = cur[i - 4] if i >= 4 else 0
                cur[i] = (cur[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = cur[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 0xFF
        prev = cur
        rows.append(cur)
    return w, h, rows


def write_png_rgba(path, w, h, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        raw.extend(row)

    def chunk(ctype, payload):
        crc = zlib.crc32(ctype + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + ctype + payload + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), 6)
    out = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
    Path(path).write_bytes(out)


def downscale_2x(w, h, rows):
    nw, nh = w // 2, h // 2
    out = []
    for ny in range(nh):
        r0 = rows[ny * 2]
        r1 = rows[ny * 2 + 1]
        nrow = bytearray(nw * 4)
        for nx in range(nw):
            o0 = (nx * 2) * 4
            o1 = (nx * 2 + 1) * 4
            for c in range(4):
                nrow[nx * 4 + c] = (r0[o0 + c] + r0[o1 + c] + r1[o0 + c] + r1[o1 + c]) >> 2
        out.append(nrow)
    return nw, nh, out


def draw_label(canvas_rows, cw, ch, x, y, text, fg=(248, 234, 198, 255), scale=2):
    cx = x
    for ch_char in text.upper():
        glyph = FONT.get(ch_char)
        if glyph:
            for gy, grow in enumerate(glyph):
                for gx, bit in enumerate(grow):
                    if bit in ("1", "#"):
                        for dy in range(scale):
                            for dx in range(scale):
                                px = cx + gx * scale + dx
                                py = y + gy * scale + dy
                                if 0 <= px < cw and 0 <= py < ch:
                                    off = px * 4
                                    canvas_rows[py][off:off + 4] = bytes(fg)
        cx += 6 * scale


def build_review_sheet():
    info = json.loads((R / "model-info.json").read_text(encoding="utf-8"))
    vis_tris = info["triangles"]["visible"]
    max_h = info["footprint_m"]["max_height"]

    CW, CH = 1304, 1654
    bg = bytes((20, 18, 16, 255))
    canvas = [bytearray(bg * CW) for _ in range(CH)]

    for y in range(10, 66):
        for x in range(12, CW - 12):
            canvas[y][x * 4:(x + 1) * 4] = bytes((32, 28, 24, 255))
    draw_label(canvas, CW, CH, 24, 20, "THE FOUNDRY IRONWORKS (1938) - PRE-CLUB WORKING FACTORY", fg=(255, 164, 54, 255), scale=2)
    draw_label(
        canvas, CW, CH, 24, 42,
        f"28.56M X 29.80M LOT | {max_h:.2f}M HEIGHT | {vis_tris} VIS TRIS | 12 MATERIALS | 0.0388M/TEXEL",
        fg=(248, 234, 198, 255), scale=2,
    )

    panels = [
        ("01-day-front-clean.png", "01. DAY FRONT - BRICK SMOKESTACK & TIN SAWTOOTH ROOF", 12, 76),
        ("02-dusk-shift-clean.png", "02. NIGHT SHIFT - GATEHOUSE GANTRY & FURNACE GLOW", 656, 76),
        ("03-rear-clean.png", "03. REAR FREIGHT DOCK, EXHAUST STACKS & COAL BUNKER", 12, 598),
        ("04-above-clean.png", "04. ORTHOGRAPHIC CORRUGATED ROOF & WORKING YARD PLAN", 656, 598),
        ("05-person-height-clean.png", "05. STREET PERSON-HEIGHT GATEHOUSE ARRIVAL (1.75M)", 12, 1120),
        ("06-interior-clean.png", "06. MOLTEN CRUCIBLE PIT, BRIDGE CRANE & BLAST FURNACE", 656, 1120),
    ]

    for fname, title, px, py in panels:
        w, h, rows = read_png_rgba(RENDERS / fname)
        sw, sh, srows = downscale_2x(w, h, rows)
        for ty in range(py, py + 28):
            for tx in range(px, px + sw):
                canvas[ty][tx * 4:(tx + 1) * 4] = bytes((30, 26, 22, 255))
        draw_label(canvas, CW, CH, px + 10, py + 7, title, fg=(248, 234, 198, 255), scale=2)
        for iy in range(sh):
            dest_y = py + 28 + iy
            if 0 <= dest_y < CH:
                canvas[dest_y][px * 4:(px + sw) * 4] = srows[iy]

    write_png_rgba(R / "foundry-factory-review.png", CW, CH, canvas)
    shutil.copyfile(R / "foundry-factory-review.png", R / "foundry-factory-review.jpg")
    print(f"Wrote foundry-factory-review.png ({CW}x{CH})")


def build_preview_html():
    info = json.loads((R / "model-info.json").read_text(encoding="utf-8"))
    vis_tris = info["triangles"]["visible"]
    html = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>THE FOUNDRY IRONWORKS (1938) — Historic Working Factory Landmark Preview</title>
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    body {{ background: #141210; color: #f8eac6; font-family: system-ui, sans-serif; margin: 0; padding: 24px; }}
    h1 {{ color: #ffa436; margin: 0 0 6px; letter-spacing: 0.05em; }}
    p.sub {{ color: #d8c296; margin: 0 0 24px; font-family: monospace; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(540px, 1fr)); gap: 18px; }}
    .card {{ background: #1e1b18; border: 1px solid #3c342c; border-radius: 8px; overflow: hidden; }}
    .card h3 {{ margin: 0; padding: 10px 14px; font-size: 14px; background: #28231e; color: #f8eac6; font-family: monospace; }}
    .card img {{ width: 100%; display: block; image-rendering: pixelated; }}
  </style>
</head>
<body>
  <h1>THE FOUNDRY IRONWORKS (1938) — Pre-Conversion Working Factory (foundry-factory.glb)</h1>
  <p class="sub">28.56m × 29.80m lot · 19.87m × 18.00m building · 11.48m max height · {vis_tris:,} visible triangles · 12 materials · 0.0388m/texel (2×2 art pixel grid)</p>
  <div class="grid">
    <div class="card"><h3>01. Daylight Front — Brick Smokestack &amp; Corrugated Sawtooth Roof</h3><img src="renders/01-day-front-clean.png" alt="Day Front" /></div>
    <div class="card"><h3>02. Night Shift — Gatehouse Gantry, Shipping Yard &amp; Furnace Glow</h3><img src="renders/02-dusk-shift-clean.png" alt="Night Shift" /></div>
    <div class="card"><h3>03. Rear Freight Loading Dock, Exhaust Stacks &amp; Coal Bunker</h3><img src="renders/03-rear-clean.png" alt="Rear View" /></div>
    <div class="card"><h3>04. Orthographic Corrugated Roof, Ventilators &amp; Working Yard Plan</h3><img src="renders/04-above-clean.png" alt="Plan View" /></div>
    <div class="card"><h3>05. Street Person-Height Gatehouse Arrival (1.75 m)</h3><img src="renders/05-person-height-clean.png" alt="Person Height" /></div>
    <div class="card"><h3>06. Working Foundry Floor — Molten Crucible Pit, Bridge Crane &amp; Tool Crib</h3><img src="renders/06-interior-clean.png" alt="Interior Hall" /></div>
    <div class="card"><h3>07. Molten Crucible Casting Pit, Overhead Hoist &amp; Rear Blast Furnace</h3><img src="renders/07-crucible-and-furnace-clean.png" alt="Crucible & Blast Furnace" /></div>
  </div>
</body>
</html>
"""
    (R / "preview.html").write_text(html, encoding="utf-8")


def sync_into_kotlin_street_and_zip():
    repo_root = R.parent.parent
    ks_landmarks = repo_root / "kotlin-street" / "app" / "src" / "main" / "assets" / "landmarks"
    target_dir = ks_landmarks / "foundry-factory"
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(R / "foundry-factory.glb", target_dir / "foundry-factory.glb")
    shutil.copyfile(R / "model-info.json", target_dir / "model-info.json")

    landmarks_json_path = ks_landmarks / "landmarks.json"
    if landmarks_json_path.exists():
        manifest = json.loads(landmarks_json_path.read_text(encoding="utf-8"))
        entries = [
            e for e in manifest.get("landmarks", [])
            if e.get("kind") != "landmark_glb_foundry_factory" and e.get("id") != "landmark_glb_foundry_factory"
        ]
        entries.append({
            "kind": "landmark_glb_foundry_factory",
            "name": "The Foundry Ironworks",
            "role": "factory",
            "room": "hall",
            "file": "foundry-factory/foundry-factory.glb",
            "info": "foundry-factory/model-info.json",
            "era": {
                "from": 1924,
                "to": 2015,
                "built": 1938,
            },
            "footprint": {
                "lot": [28.56, 29.8],
                "building": [19.87, 18.0],
            },
        })
        manifest["landmarks"] = entries
        landmarks_json_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    zip_path = R / "foundry-factory-model-and-previews.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for rel in [
            "foundry-factory.glb",
            "model-info.json",
            "validation.json",
            "art-regions.json",
            "make_art.py",
            "build_model.py",
            "validate_model.py",
            "pixel_font.py",
            "render_views.mjs",
            "package_preview.py",
            "preview.html",
            "foundry-factory-review.png",
            "README.md",
        ]:
            p = R / rel
            if p.exists():
                zf.write(p, arcname=f"foundry-factory/{rel}")
        for sub in ("textures", "source-materials", "renders"):
            for p in sorted((R / sub).glob("*")):
                if p.is_file():
                    zf.write(p, arcname=f"foundry-factory/{sub}/{p.name}")
    print(f"Packaged {zip_path.name} ({zip_path.stat().st_size} bytes)")


if __name__ == "__main__":
    build_review_sheet()
    build_preview_html()
    sync_into_kotlin_street_and_zip()
