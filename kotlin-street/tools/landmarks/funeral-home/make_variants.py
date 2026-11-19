#!/usr/bin/env python3
"""Paints the town's family name on the funeral home's two sign faces and writes one .glb per name.

The model (source/funeral-home.glb, copied from the modeller's branch with `git show`) has one sign atlas, signs.png, used
by both `sign_name` and `sign_name_back` (art region [0,0,48,24]: a cream-outlined plate, two 5x7 lines and a 3x5 line).
Here the plate's two big lines become "<SURNAME>" and "& SONS" (the small line, FUNERAL HOME, is kept). Only that image
is replaced in the .glb (its bytes in the binary chunk); the geometry is untouched. Names are made-up ordinary surnames:
src/town/glbSites.ts FUNERAL_NAMES lists them in this order, landmarks.json `variants` too.

Run in this folder: python3 make_variants.py [OUT_DIR]   (default: ../../../app/src/main/assets/landmarks/funeral-home)
"""
import io, json, struct, sys
from pathlib import Path
from PIL import Image, ImageDraw
from pixel_font import FONT

HERE = Path(__file__).resolve().parent
SRC = HERE / "source" / "funeral-home.glb"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "../../../app/src/main/assets/landmarks/funeral-home"
CREAM, INK = "#e3dcc5", "#3d4f4b"
SURNAMES = ["HOLLIS", "PRUITT", "MARLOW", "WHELAN", "ASHBY", "KESSLER"]  # at most 7 letters: 6 px a letter in 48


def text5x7(d, cx, y, s):
    w = 6 * len(s) - 1
    x = cx - w // 2
    for ch in s:
        if ch == " ":
            x += 3
            continue
        for j, row in enumerate(FONT[ch]):
            for i, b in enumerate(row):
                if b == "1":
                    d.point((x + i, y + j), fill=CREAM)
        x += 6


def paint(png: bytes, surname: str) -> bytes:
    im = Image.open(io.BytesIO(png)).convert("RGBA")
    art = im.resize((im.width // 2, im.height // 2), Image.Resampling.NEAREST)
    d = ImageDraw.Draw(art)
    d.rectangle((0, 0, 47, 17), fill=INK)  # the two big lines; the tiny FUNERAL HOME line (y 18..22) stays
    d.rectangle((0, 0, 47, 23), outline=CREAM)
    text5x7(d, 24, 2, surname)
    text5x7(d, 24, 10, "& SONS")
    return _save(art.resize((art.width * 2, art.height * 2), Image.Resampling.NEAREST))


def _save(im):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=False)
    return b.getvalue()


def read_glb(path):
    d = path.read_bytes()
    jl = struct.unpack("<I", d[12:16])[0]
    j = json.loads(d[20:20 + jl])
    off = 20 + jl
    bl = struct.unpack("<I", d[off:off + 4])[0]
    return j, d[off + 8:off + 8 + bl]


def write_glb(path, j, binc):
    binc += b"\0" * (-len(binc) % 4)
    j["buffers"][0]["byteLength"] = len(binc)
    js = json.dumps(j, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    total = 12 + 8 + len(js) + 8 + len(binc)
    path.write_bytes(b"glTF" + struct.pack("<II", 2, total) + struct.pack("<I", len(js)) + b"JSON" + js + struct.pack("<I", len(binc)) + b"BIN\0" + binc)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    j0, b0 = read_glb(SRC)
    mat = next(m for m in j0["materials"] if m["name"] == "sign_name")
    img_i = j0["textures"][mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]
    for name in SURNAMES:
        j = json.loads(json.dumps(j0))
        views = j["bufferViews"]
        tgt = views[j["images"][img_i]["bufferView"]]
        old = b0[tgt["byteOffset"]:tgt["byteOffset"] + tgt["byteLength"]]
        new = paint(old, name)
        # rebuild the binary chunk: every view keeps its bytes (4-aligned), the sign's picture is the new one
        order = sorted(range(len(views)), key=lambda i: views[i].get("byteOffset", 0))
        out = bytearray()
        for i in order:
            v = views[i]
            raw = new if v is tgt else b0[v.get("byteOffset", 0):v.get("byteOffset", 0) + v["byteLength"]]
            out += b"\0" * (-len(out) % 4)
            v["byteOffset"] = len(out)
            v["byteLength"] = len(raw)
            out += raw
        write_glb(OUT / f"funeral-home-{name.lower()}.glb", j, bytes(out))
        print("wrote", OUT / f"funeral-home-{name.lower()}.glb")


if __name__ == "__main__":
    main()
