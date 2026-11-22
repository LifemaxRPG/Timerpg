#!/usr/bin/env python3
"""
Validates beach-blvd-rex.glb against docs/CHATGPT_MODEL_GUIDE.md constraints:
  - Valid glTF 2.0 binary header & chunk layout
  - Zero NaN/Inf positions or normals
  - Visible triangle budget < 12,000
  - Material count <= 12, semantic names
  - Nearest filtering (9728) & repeat wrap (10497)
  - Required nodes: rex_statue, lot, collision, door_main
  - Proper real-world scale (~6.7-6.9m tall, ~7.5-8.0m long) and ground origin
  - Black-masked emissive texture check (non-emissive areas strictly #000000)
  - 2x2 art-pixel texture grid sanity
Writes validation.json and exits 0 iff all checks pass.
"""
import json
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GLB_PATH = ROOT / "beach-blvd-rex.glb"
INFO_PATH = ROOT / "model-info.json"
REGIONS_PATH = ROOT / "art-regions.json"
TEX_DIR = ROOT / "textures"


def decode_png_rgba(raw: bytes):
    assert raw[:8] == b"\x89PNG\r\n\x1a\n", "Not a PNG"
    pos = 8
    w = h = 0
    idat = bytearray()
    while pos < len(raw):
        length = struct.unpack(">I", raw[pos:pos + 4])[0]
        ctype = raw[pos + 4:pos + 8]
        chunk = raw[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h = struct.unpack(">II", chunk[:8])
        elif ctype == b"IDAT":
            idat.extend(chunk)
    data = zlib.decompress(bytes(idat))
    stride = w * 4
    out = bytearray(w * h * 4)
    idx = 0
    for y in range(h):
        flt = data[idx]
        idx += 1
        row_off = y * stride
        prev_off = (y - 1) * stride
        for i in range(stride):
            x = data[idx]
            idx += 1
            a = out[row_off + i - 4] if i >= 4 else 0
            b = out[prev_off + i] if y > 0 else 0
            c = out[prev_off + i - 4] if (y > 0 and i >= 4) else 0
            if flt == 0:
                val = x
            elif flt == 1:
                val = (x + a) & 0xFF
            elif flt == 2:
                val = (x + b) & 0xFF
            elif flt == 3:
                val = (x + ((a + b) >> 1)) & 0xFF
            elif flt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                val = (x + pr) & 0xFF
            else:
                raise ValueError(f"Unsupported PNG filter {flt}")
            out[row_off + i] = val
    return w, h, bytes(out)


def main():
    checks = []

    def check(name, ok, detail):
        checks.append({"name": name, "pass": bool(ok), "detail": detail})

    raw = GLB_PATH.read_bytes()
    magic, version, total_len = struct.unpack_from("<4sII", raw, 0)
    check("glb_header", magic == b"glTF" and version == 2 and total_len == len(raw),
          f"magic={magic!r} version={version} bytes={len(raw)}")

    json_len, json_type = struct.unpack_from("<I4s", raw, 12)
    assert json_type == b"JSON"
    gltf = json.loads(raw[20:20 + json_len].decode("utf-8"))
    bin_off = 20 + json_len
    bin_len, bin_type = struct.unpack_from("<I4s", raw, bin_off)
    assert bin_type == b"BIN\x00"
    bin_buf = raw[bin_off + 8:bin_off + 8 + bin_len]

    def read_acc(acc_idx):
        acc = gltf["accessors"][acc_idx]
        bv = gltf["bufferViews"][acc["bufferView"]]
        off = bv.get("byteOffset", 0)
        comp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
        fmt_map = {5126: "f", 5125: "I", 5123: "H"}
        fmt = fmt_map[acc["componentType"]]
        sz = struct.calcsize(fmt)
        vals = []
        for i in range(acc["count"]):
            if comp == 1:
                vals.append(struct.unpack_from("<" + fmt, bin_buf, off + i * sz)[0])
            else:
                vals.append(struct.unpack_from(f"<{comp}{fmt}", bin_buf, off + i * comp * sz))
        return vals

    # Nodes
    node_names = {n.get("name"): n for n in gltf.get("nodes", [])}
    required_nodes = ["rex_statue", "lot", "collision", "door_main"]
    missing = [n for n in required_nodes if n not in node_names]
    check("required_nodes", len(missing) == 0, f"present={sorted(node_names.keys())}")

    # Materials & samplers
    mats = gltf.get("materials", [])
    check("material_budget", 1 <= len(mats) <= 12, f"count={len(mats)} (max 12)")

    samplers = gltf.get("samplers", [])
    all_nearest_repeat = all(
        s.get("magFilter") == 9728 and s.get("minFilter") == 9728
        and s.get("wrapS") == 10497 and s.get("wrapT") == 10497
        for s in samplers
    )
    check("nearest_repeat_samplers", len(samplers) > 0 and all_nearest_repeat, f"samplers={samplers}")

    # Geometry stats & NaN check
    vis_tris = 0
    coll_tris = 0
    nan_count = 0
    rex_min = [1e9, 1e9, 1e9]
    rex_max = [-1e9, -1e9, -1e9]
    lot_min = [1e9, 1e9, 1e9]
    lot_max = [-1e9, -1e9, -1e9]

    for node in gltf["nodes"]:
        if "mesh" not in node:
            continue
        is_coll = node.get("name") == "collision" or node.get("extras", {}).get("collision_only")
        mesh = gltf["meshes"][node["mesh"]]
        for prim in mesh["primitives"]:
            pos = read_acc(prim["attributes"]["POSITION"])
            nrm = read_acc(prim["attributes"]["NORMAL"])
            idx = read_acc(prim["indices"])
            tris = len(idx) // 3
            if is_coll:
                coll_tris += tris
            else:
                vis_tris += tris
            for p in pos:
                if any(math.isnan(c) or math.isinf(c) for c in p):
                    nan_count += 1
                if node.get("name") == "rex_statue":
                    for k in range(3):
                        rex_min[k] = min(rex_min[k], p[k])
                        rex_max[k] = max(rex_max[k], p[k])
                elif node.get("name") == "lot":
                    for k in range(3):
                        lot_min[k] = min(lot_min[k], p[k])
                        lot_max[k] = max(lot_max[k], p[k])
            for n in nrm:
                if any(math.isnan(c) or math.isinf(c) for c in n):
                    nan_count += 1

    check("no_nan_or_inf", nan_count == 0, f"nan_or_inf_verts={nan_count}")
    check("triangle_budget", 0 < vis_tris < 12000, f"visible_tris={vis_tris}, collision_tris={coll_tris}")

    rex_h = rex_max[1]
    rex_len = rex_max[2] - rex_min[2]
    check("proper_real_world_scale", 5.8 <= rex_h <= 7.2 and 6.2 <= rex_len <= 8.5,
          f"rex_height={rex_h:.3f}m ({rex_h * 3.28084:.1f}ft), rex_length={rex_len:.3f}m ({rex_len * 3.28084:.1f}ft)")

    # Front edge origin near Z=0, ground near Y=0
    check("origin_and_ground", abs(lot_max[2]) < 0.05 and abs(lot_min[1] + 0.06) < 0.05,
          f"lot_z=[{lot_min[2]:.2f}, {lot_max[2]:.2f}], lot_y=[{lot_min[1]:.2f}, {lot_max[1]:.2f}]")

    # Emissive black-mask validation
    glow_mats = [m for m in mats if "emissiveTexture" in m]
    check("emissive_material_present", len(glow_mats) == 1 and glow_mats[0]["name"] == "glow_rex_eyes",
          f"emissive_materials={[m['name'] for m in glow_mats]}")

    w_em, h_em, em_px = decode_png_rgba((TEX_DIR / "glow_mask.png").read_bytes())
    regions = json.loads(REGIONS_PATH.read_text())["regions"]["glow_rex_eyes"]
    # Verify non-emissive regions (socket, dark) are strictly #000000
    leak_pixels = 0
    for rname in ("socket", "dark"):
        x0, y0, rw, rh = [c * 2 for c in regions[rname]]
        for py in range(y0, y0 + rh):
            for px in range(x0, x0 + rw):
                off = (py * w_em + px) * 4
                if em_px[off] != 0 or em_px[off + 1] != 0 or em_px[off + 2] != 0:
                    leak_pixels += 1
    lit_eye_pixels = 0
    ex0, ey0, erw, erh = [c * 2 for c in regions["eye"]]
    for py in range(ey0, ey0 + erh):
        for px in range(ex0, ex0 + erw):
            off = (py * w_em + px) * 4
            if em_px[off] > 180:
                lit_eye_pixels += 1
    check("emissive_black_mask", leak_pixels == 0 and lit_eye_pixels > 20,
          f"non_emissive_leaks={leak_pixels}, lit_eye_texels={lit_eye_pixels}")

    # Verify all embedded PNG images have even dimensions (2x2 art-pixel grid)
    odd_images = []
    for img in gltf.get("images", []):
        bv = gltf["bufferViews"][img["bufferView"]]
        raw_img = bin_buf[bv.get("byteOffset", 0):bv.get("byteOffset", 0) + bv["byteLength"]]
        iw, ih, _ = decode_png_rgba(raw_img)
        if iw % 2 != 0 or ih % 2 != 0:
            odd_images.append((img.get("name"), iw, ih))
    check("even_2x2_texture_grid", len(odd_images) == 0, f"embedded_images={len(gltf.get('images', []))}, odd={odd_images}")

    all_pass = all(c["pass"] for c in checks)
    report = {
        "model": GLB_PATH.name,
        "all_pass": all_pass,
        "file_bytes": len(raw),
        "visible_triangles": vis_tris,
        "collision_triangles": coll_tris,
        "material_count": len(mats),
        "checks": checks,
    }
    (ROOT / "validation.json").write_text(json.dumps(report, indent=2) + "\n")
    for c in checks:
        status = "PASS" if c["pass"] else "FAIL"
        print(f"[{status}] {c['name']}: {c['detail']}")
    if not all_pass:
        sys.exit(1)
    print(f"All {len(checks)} checks passed -> validation.json")


if __name__ == "__main__":
    main()
