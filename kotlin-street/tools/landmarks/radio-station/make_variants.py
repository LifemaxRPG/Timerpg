#!/usr/bin/env python3
"""Paints the town's station name on the radio station's illuminated nameplate and writes one .glb per name.

The model (source/radio-station.glb, copied from the modeller's branch with `git show`) has one sign atlas, signs.png, used
by `sign_name` for both its picture and its glow (art region [0,0,94,18]: a dark green plate with a cream border and the
name in cream letters; the RADIO blade, the emblem and the static ON AIR plate share the atlas and are kept). Only that
image is replaced in the .glb (its bytes in the binary chunk); the geometry is untouched. The first name is the
modeller's own plate (the file is copied as it is); the others are made-up station names (no real call letters):
src/town/glbSites.ts RADIO_NAMES lists them in this order, landmarks.json `variants` too.

Run in this folder: python3 make_variants.py [OUT_DIR]   (default: ../../../app/src/main/assets/landmarks/radio-station)
"""
import io, json, shutil, struct, sys
from pathlib import Path
from PIL import Image, ImageDraw
from pixel_font import FONT

HERE = Path(__file__).resolve().parent
SRC = HERE / "source" / "radio-station.glb"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else HERE / "../../../app/src/main/assets/landmarks/radio-station"
PLATE, INK = (53, 75, 76, 255), (229, 223, 201, 255)
NAMES = ["VALLEY RADIO", "PRAIRIE 1340 AM", "RIVERBEND RADIO", "HOMETOWN RADIO", "CEDAR 1400 AM", "CROSSROADS 1450"]  # at most 15: 6 px a letter in 94


def slug(n):
    return n.lower().replace(" ", "-")


def paint(png: bytes, name: str) -> bytes:
    im = Image.open(io.BytesIO(png)).convert("RGBA")
    art = im.resize((im.width // 2, im.height // 2), Image.Resampling.NEAREST)
    d = ImageDraw.Draw(art)
    d.rectangle((1, 1, 92, 16), fill=PLATE)  # the cream border (row/column 0 and 93/17) stays
    w = 6 * len(name) - 1
    x = 47 - w // 2
    for ch in name:
        if ch != " ":
            for j, row in enumerate(FONT[ch]):
                for i, b in enumerate(row):
                    if b == "1":
                        d.point((x + i, 5 + j), fill=INK)
        x += 6
    out = art.resize((art.width * 2, art.height * 2), Image.Resampling.NEAREST)
    b = io.BytesIO()
    out.save(b, "PNG", optimize=False)
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
    for k, name in enumerate(NAMES):
        dst = OUT / f"radio-station-{slug(name)}.glb"
        if k == 0:
            shutil.copyfile(SRC, dst)
            print("wrote", dst)
            continue
        j = json.loads(json.dumps(j0))
        views = j["bufferViews"]
        tgt = views[j["images"][img_i]["bufferView"]]
        new = paint(b0[tgt.get("byteOffset", 0):tgt.get("byteOffset", 0) + tgt["byteLength"]], name)
        order = sorted(range(len(views)), key=lambda i: views[i].get("byteOffset", 0))
        out = bytearray()
        for i in order:
            v = views[i]
            raw = new if v is tgt else b0[v.get("byteOffset", 0):v.get("byteOffset", 0) + v["byteLength"]]
            out += b"\0" * (-len(out) % 4)
            v["byteOffset"] = len(out)
            v["byteLength"] = len(raw)
            out += raw
        write_glb(dst, j, bytes(out))
        print("wrote", dst)


if __name__ == "__main__":
    main()
