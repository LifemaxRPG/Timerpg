#!/usr/bin/env python3
"""
Builds the composite review sheet (mirage-review.png & mirage-review.jpg at 1304x1654),
interactive preview.html, and self-contained mirage-nightclub-model-and-previews.zip
for prototypes/mirage-nightclub/, and copies mirage-nightclub.glb + model-info.json
into kotlin-street/app/src/main/assets/landmarks/mirage-nightclub/ and registers
landmark_glb_mirage_nightclub in kotlin-street/app/src/main/assets/landmarks/landmarks.json.
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


def draw_label(canvas_rows, cw, ch, x, y, text, fg=(255, 236, 156, 255), scale=2):
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
    CW, CH = 1304, 1654
    bg = bytes((14, 12, 26, 255))
    canvas = [bytearray(bg * CW) for _ in range(CH)]

    # Header bar
    for y in range(10, 66):
        for x in range(12, CW - 12):
            canvas[y][x * 4:(x + 1) * 4] = bytes((28, 18, 48, 255))
    draw_label(canvas, CW, CH, 24, 22, "CLUB MIRAGE - 1980S SYNTHWAVE NIGHTCLUB (LANDMARKS_GLB)", fg=(255, 72, 176, 255), scale=2)
    draw_label(canvas, CW, CH, 24, 44, "27.32M X 29.80M LOT | 11.33M MAX HEIGHT | 2924 VIS TRIS | 12 MATERIALS | 0.0388M/TEXEL", fg=(88, 242, 255, 255), scale=2)

    panels = [
        ("01-day-front-clean.png", "01. DAY FRONT ISOMETRIC", 12, 76),
        ("02-blue-hour-clean.png", "02. NIGHT / BLUE HOUR NEON", 656, 76),
        ("03-rear-clean.png", "03. REAR LOADING DOCK & STAGE DOOR", 12, 598),
        ("04-above-clean.png", "04. ORTHOGRAPHIC ROOF & LOT PLAN", 656, 598),
        ("05-person-height-clean.png", "05. STREET PERSON-HEIGHT (1.75M)", 12, 1120),
        ("06-interior-clean.png", "06. INTERIOR DANCE FLOOR, BAR & DJ STAGE", 656, 1120),
    ]

    for fname, title, px, py in panels:
        w, h, rows = read_png_rgba(RENDERS / fname)
        sw, sh, srows = downscale_2x(w, h, rows)  # 640x480
        # Panel title bar
        for ty in range(py, py + 28):
            for tx in range(px, px + sw):
                canvas[ty][tx * 4:(tx + 1) * 4] = bytes((24, 20, 42, 255))
        draw_label(canvas, CW, CH, px + 10, py + 7, title, fg=(255, 236, 156, 255), scale=2)
        for iy in range(sh):
            dest_y = py + 28 + iy
            if 0 <= dest_y < CH:
                canvas[dest_y][px * 4:(px + sw) * 4] = srows[iy]

    write_png_rgba(R / "mirage-review.png", CW, CH, canvas)
    shutil.copyfile(R / "mirage-review.png", R / "mirage-review.jpg")
    print(f"Wrote mirage-review.png ({CW}x{CH})")


def build_preview_html():
    html = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Club Mirage — 1980s Synthwave Nightclub Landmark Preview</title>
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    body { background: #0d0b18; color: #f4f0ff; font-family: system-ui, sans-serif; margin: 0; padding: 24px; }
    h1 { color: #ff48b0; margin: 0 0 6px; letter-spacing: 0.04em; }
    p.sub { color: #58f2ff; margin: 0 0 24px; font-family: monospace; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(540px, 1fr)); gap: 18px; }
    .card { background: #18142c; border: 1px solid #3c2a68; border-radius: 8px; overflow: hidden; }
    .card h3 { margin: 0; padding: 10px 14px; font-size: 14px; background: #221a3e; color: #ffec9c; font-family: monospace; }
    .card img { width: 100%; display: block; image-rendering: pixelated; }
  </style>
</head>
<body>
  <h1>CLUB MIRAGE — 1980s Synthwave Nightclub (mirage-nightclub.glb)</h1>
  <p class="sub">27.32m × 29.80m lot · 18.62m × 17.38m building · 11.33m max height · 2,924 visible triangles · 12 materials · 0.0388m/texel (2×2 art pixel grid)</p>
  <div class="grid">
    <div class="card"><h3>01. Daylight Front Isometric</h3><img src="renders/01-day-front-clean.png" alt="Day Front" /></div>
    <div class="card"><h3>02. Blue Hour / Night Neon Glow</h3><img src="renders/02-blue-hour-clean.png" alt="Blue Hour" /></div>
    <div class="card"><h3>03. Rear Touring Loading Dock &amp; Stage Door</h3><img src="renders/03-rear-clean.png" alt="Rear View" /></div>
    <div class="card"><h3>04. Orthographic Roof &amp; Lot Plan</h3><img src="renders/04-above-clean.png" alt="Plan View" /></div>
    <div class="card"><h3>05. Street Person-Height (1.75 m)</h3><img src="renders/05-person-height-clean.png" alt="Person Height" /></div>
    <div class="card"><h3>06. Interior Dance Floor, Neon Bar &amp; DJ Stage</h3><img src="renders/06-interior-clean.png" alt="Interior Hall" /></div>
    <div class="card"><h3>07. Illuminated Dance Floor &amp; DJ Synthesizer Booth Close-Up</h3><img src="renders/07-dance-floor-and-stage-clean.png" alt="DJ Stage" /></div>
  </div>
</body>
</html>
"""
    (R / "preview.html").write_text(html, encoding="utf-8")


def sync_into_kotlin_street_and_zip():
    repo_root = R.parent.parent
    ks_landmarks = repo_root / "kotlin-street" / "app" / "src" / "main" / "assets" / "landmarks"
    target_dir = ks_landmarks / "mirage-nightclub"
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(R / "mirage-nightclub.glb", target_dir / "mirage-nightclub.glb")
    shutil.copyfile(R / "model-info.json", target_dir / "model-info.json")

    landmarks_json_path = ks_landmarks / "landmarks.json"
    if landmarks_json_path.exists():
        manifest = json.loads(landmarks_json_path.read_text(encoding="utf-8"))
        entries = [e for e in manifest.get("landmarks", []) if e.get("id") != "landmark_glb_mirage_nightclub"]
        entries.append({
            "id": "landmark_glb_mirage_nightclub",
            "name": "Club Mirage",
            "role": "nightclub",
            "era": "1980s",
            "asset": "landmarks/mirage-nightclub/mirage-nightclub.glb",
            "info": "landmarks/mirage-nightclub/model-info.json",
            "building": [18.62, 17.38],
            "lot": [27.32, 29.80],
            "doors": ["door_main", "door_back", "door_side"],
            "props": ["marquee_pylon", "service_yard", "vip_rope_line"],
            "door_panels": ["door_panel_main", "door_panel_back", "door_panel_side"],
        })
        manifest["landmarks"] = entries
        landmarks_json_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    zip_path = R / "mirage-nightclub-model-and-previews.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for rel in [
            "mirage-nightclub.glb",
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
            "mirage-review.png",
            "README.md",
        ]:
            p = R / rel
            if p.exists():
                zf.write(p, arcname=f"mirage-nightclub/{rel}")
        for sub in ("textures", "source-materials", "renders"):
            for p in sorted((R / sub).glob("*")):
                if p.is_file():
                    zf.write(p, arcname=f"mirage-nightclub/{sub}/{p.name}")
    print(f"Packaged {zip_path.name} ({zip_path.stat().st_size} bytes)")


if __name__ == "__main__":
    build_review_sheet()
    build_preview_html()
    sync_into_kotlin_street_and_zip()
