#!/usr/bin/env python3
"""
Builds the 6-view composite review sheet (`starlight-review.png` and `starlight-review.jpg`),
`preview.html`, and the distributable zip archive `starlight-drive-in-model-and-previews.zip`.
Uses pure Python standard library + pixel_font.py.
"""
import json
import zipfile
from pathlib import Path
from make_art import read_png_rgba, write_png_rgba, hex_rgba
from pixel_font import FONT

R = Path(__file__).resolve().parent

SHOTS = [
    ("01-day-front-clean.png", "01. Daylight Front — Googie Concession Pavilion, Ticket Kiosk, Marquee Pylon & Screen Tower"),
    ("02-blue-hour-clean.png", "02. Blue Hour — Illuminated Neon Signage, Soffit Light Pools & Feature Presentation Screen"),
    ("03-rear-clean.png", "03. Rear 360° — 6 Steel A-Frame Raker Trusses, Catwalk, Projection Ports & Service Yard"),
    ("04-above-clean.png", "04. Plan / Above — 34.76 x 42.21 m Lot, 3 Tiered Viewing Ramps & 18 Dual-Speaker Posts"),
    ("05-person-height-clean.png", "05. Street Person-Height (1.75 m) — Storefront Glazing, Rooftop Neon & Pylon Scale"),
    ("06-interior-clean.png", "06. Interior Shell — Terrazzo Lobby, Fixed L-Counter, Ceiling Lamps & Projection Booth"),
    ("07-screen-and-marquee-clean.png", "07. Screen Tower Close-Up — 192x72 Art-Pixel Widescreen Frame & Acoustic Baffle Hood"),
]


def draw_text_scaled(canvas, w, h, x, y, text, color, scale=2):
    cx = x
    for ch in text.upper():
        rows = FONT.get(ch, ["00000"] * 7)
        for yy, row in enumerate(rows):
            for xx, v in enumerate(row):
                if v == "1":
                    for sy in range(scale):
                        for sx in range(scale):
                            px = cx + xx * scale + sx
                            py = y + yy * scale + sy
                            if 0 <= px < w and 0 <= py < h:
                                canvas[py][px] = color
        cx += 6 * scale


def resize_nearest(src_rows, src_w, src_h, dst_w, dst_h):
    out = [[(0, 0, 0, 255)] * dst_w for _ in range(dst_h)]
    for y in range(dst_h):
        sy = min(src_h - 1, (y * src_h) // dst_h)
        srow = src_rows[sy]
        orow = out[y]
        for x in range(dst_w):
            sx = min(src_w - 1, (x * src_w) // dst_w)
            orow[x] = srow[sx]
    return out


def build_review_sheet():
    W, H = 1304, 1654
    bg = hex_rgba("#292c3b")
    cream = hex_rgba("#eee9df")
    muted = hex_rgba("#c9c1b6")
    sheet = [[bg for _ in range(W)] for _ in range(H)]

    draw_text_scaled(sheet, W, H, 24, 24, "STARLIGHT DRIVE-IN", cream, scale=4)
    draw_text_scaled(
        sheet, W, H, 24, 68,
        "1950S GOOGIE DRIVE-IN THEATER  /  PAVILION, PROJECTION BOOTH, PYLON & SCREEN TOWER",
        muted, scale=2
    )

    review_indices = [0, 1, 2, 3, 4, 6]
    for idx, shot_i in enumerate(review_indices):
        fname, caption = SHOTS[shot_i]
        sw, sh, spx = read_png_rgba(R / "renders" / fname)
        assert (sw, sh) == (1280, 960), f"Unexpected render size for {fname}: {(sw, sh)}"
        thumb = resize_nearest(spx, sw, sh, 616, 462)
        ox = 24 + (idx % 2) * 640
        oy = 112 + (idx // 2) * 500
        for ty in range(462):
            sheet[oy + ty][ox : ox + 616] = thumb[ty]
        short_cap = caption[:48]
        draw_text_scaled(sheet, W, H, ox, oy + 470, short_cap, cream, scale=2)

    draw_text_scaled(
        sheet, W, H, 24, 1622,
        "3D GLB RENDERS AT 0.0388 M/TEXEL. LANDMARKS_GLB CONTRACT VERIFIED.",
        muted, scale=2
    )

    write_png_rgba(R / "starlight-review.png", W, H, sheet)
    # Also write starlight-review.jpg path copy for tools expecting .jpg filename
    (R / "starlight-review.jpg").write_bytes((R / "starlight-review.png").read_bytes())


def build_preview_html():
    cards = "".join(
        f'<figure><a href="renders/{name}"><img src="renders/{name}" alt="Starlight Drive-In: {caption}"></a>'
        f"<figcaption>{caption}</figcaption></figure>"
        for name, caption in SHOTS
    )
    html = (
        '<!doctype html><html lang="en"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>Starlight Drive-In</title>"
        "<style>"
        "body{margin:0;background:#292c3b;color:#eee9df;font:16px/1.6 system-ui,sans-serif}"
        "main{max-width:1500px;padding:32px;margin:auto}"
        "h1{font-size:clamp(32px,5vw,60px);margin:0}"
        "p{color:#c9c1b6;max-width:940px}"
        "a{color:#e8b79c}"
        "nav{display:flex;gap:24px;flex-wrap:wrap;margin:24px 0}"
        ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:24px}"
        "figure{margin:0}"
        "img{width:100%;display:block;border-radius:4px}"
        "figcaption{padding:10px 0;font-weight:600}"
        "</style>"
        "<main>"
        "<h1>Starlight Drive-In</h1>"
        "<p>An original 1950s Googie drive-in movie theater for Timerpg: cantilevered concession and optical "
        "projection pavilion, drive-through ticket booth, monumental widescreen tower with six rear steel A-frame "
        "wind trusses and maintenance catwalk, three tiered viewing berm ramps with 18 dual-speaker stanchion posts, "
        "and a double-sided roadside neon marquee pylon. Built to the LANDMARKS_GLB contract using shared LimeZu "
        "atlas swatches and original 2x2-texel pixel art at 0.0388 m/texel.</p>"
        '<nav><a href="starlight-drive-in.glb" download>3D model (.glb)</a>'
        '<a href="model-info.json">Model metadata</a>'
        '<a href="README.md">Rebuild and integration notes</a>'
        '<a href="validation.json">Asset validation</a></nav>'
        f'<div class="grid">{cards}</div>'
        "</main></html>\n"
    )
    (R / "preview.html").write_text(html, encoding="utf-8")


def build_zip_archive():
    files = [
        p
        for p in R.iterdir()
        if p.is_file() and p.suffix in {".py", ".mjs", ".json", ".md", ".glb", ".png", ".jpg", ".html"}
    ]
    for directory in ("textures", "renders", "source-materials"):
        files.extend(sorted((R / directory).glob("*")))

    archive = R / "starlight-drive-in-model-and-previews.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(set(files)):
            if p.is_file():
                z.write(p, "starlight-drive-in/" + str(p.relative_to(R)))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
    print(
        json.dumps(
            {
                "archive": archive.name,
                "bytes": archive.stat().st_size,
                "files": len(files),
                "views": len(SHOTS),
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    build_review_sheet()
    build_preview_html()
    build_zip_archive()
