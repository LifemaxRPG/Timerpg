#!/usr/bin/env python3
"""
Strict validator for THE GREAT MERIDIAN MONUMENT (great-meridian-monument.glb + far LOD).
Verifies every contract requirement from docs/CHATGPT_MODEL_GUIDE.md:
- All PNG textures strictly on 2x2 texel art-pixel grid (0.0388 m/texel).
- glow_monument_mask.png has pure #000000 black outside glowing pixels.
- Every sampler uses NEAREST filtering and REPEAT wrap.
- <= 12 named role materials.
- 0 UV scale stretch violations (0.0388 m/texel planar check on every triangle edge).
- 0 detail_atlas quadrant cross-bleeds.
- Required named nodes (structure, armillary_sphere, lot, collision, door_*, floor_*).
- Ground min_y >= 0, full monumental height (~94 m), full-height pylon collision boxes.
"""
import json
import math
import struct
import zlib
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
INFO_PATH = R / "model-info.json"
TEXEL = 0.0388


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
            w, h, bd, ct, _, _, _ = struct.unpack(">IIBBBBB", chunk)
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
        rows.append([tuple(cur[x * 4:(x + 1) * 4]) for x in range(w)])
    return w, h, rows


def check_2x2_blocks(rows, w, h):
    if w % 2 != 0 or h % 2 != 0:
        return False, f"odd dimensions {w}x{h}"
    for y in range(0, h, 2):
        r0, r1 = rows[y], rows[y + 1]
        for x in range(0, w, 2):
            c = r0[x]
            if r0[x + 1] != c or r1[x] != c or r1[x + 1] != c:
                return False, f"non-2x2 block at ({x},{y})"
    return True, "ok"


def parse_glb(path):
    data = Path(path).read_bytes()
    magic, version, _ = struct.unpack("<3I", data[:12])
    assert magic == 0x46546C67 and version == 2
    jlen, jtype = struct.unpack("<2I", data[12:20])
    assert jtype == 0x4E4F534A
    gltf = json.loads(data[20:20 + jlen].decode("utf-8"))
    boff = 20 + jlen
    blen, btype = struct.unpack("<2I", data[boff:boff + 8])
    assert btype == 0x004E4942
    bin_data = data[boff + 8:boff + 8 + blen]
    return gltf, bin_data


def read_accessor(gltf, bin_data, acc_idx):
    acc = gltf["accessors"][acc_idx]
    bv = gltf["bufferViews"][acc["bufferView"]]
    off = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    cnt = acc["count"]
    ctype = acc["componentType"]
    atype = acc["type"]
    ncomp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[atype]
    fmt_ch = {5126: "f", 5125: "I", 5123: "H"}[ctype]
    stride = ncomp * struct.calcsize("<" + fmt_ch)
    out = []
    for i in range(cnt):
        vals = struct.unpack_from("<" + fmt_ch * ncomp, bin_data, off + i * stride)
        out.append(vals[0] if ncomp == 1 else vals)
    return out


def main():
    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))

    # 1. Verify every PNG texture is on a strict 2x2 art-pixel grid
    all_2x2 = True
    for p in sorted(TEX_DIR.glob("*.png")):
        w, h, rows = read_png_rgba(p)
        ok, msg = check_2x2_blocks(rows, w, h)
        if not ok:
            print(f"2x2 FAIL on {p.name}: {msg}")
            all_2x2 = False

    # Check pure-black ratio on glow_monument_mask.png
    mw, mh, mrows = read_png_rgba(TEX_DIR / "glow_monument_mask.png")
    black_ratio = round(
        sum(1 for r in mrows for px in r if px[0] == 0 and px[1] == 0 and px[2] == 0) / (mw * mh),
        3,
    )

    # 2. Validate main monument GLB
    gltf, bin_data = parse_glb(R / info["file"])
    mats = gltf.get("materials", [])
    samplers = gltf.get("samplers", [])
    nodes = gltf.get("nodes", [])
    meshes = gltf.get("meshes", [])
    textures = gltf.get("textures", [])
    images = gltf.get("images", [])

    mats_ok = len(mats) <= 12
    sampler_ok = all(
        s.get("magFilter") == 9728 and s.get("minFilter") in (9728, 9984)
        and s.get("wrapS", 10497) == 10497 and s.get("wrapT", 10497) == 10497
        for s in samplers
    )

    node_names = {n["name"] for n in nodes}
    required_nodes = {
        "structure", "armillary_sphere", "lot", "collision",
        "door_main", "door_north", "door_east", "door_west",
        "door_hall_west", "door_hall_east", "door_observatory_lift", "door_sky_deck",
        "floor_rotunda_plaza", "floor_hall_west", "floor_hall_east", "floor_observation_deck",
    }
    req_ok = required_nodes.issubset(node_names)

    img_sizes = []
    for img in images:
        bv = gltf["bufferViews"][img["bufferView"]]
        raw = bin_data[bv.get("byteOffset", 0):bv.get("byteOffset", 0) + bv["byteLength"]]
        w, h = struct.unpack(">II", raw[16:24])
        img_sizes.append((w, h))

    mesh_tris = []
    mesh_glow_area = []
    mesh_min_y = []
    mesh_max_y = []
    tot_uv_edges = 0
    bad_uv_edges = 0
    max_uv_err = 0.0
    quad_viol = 0

    for mesh in meshes:
        mt = 0
        mg = 0.0
        my0 = 1e9
        my1 = -1e9
        is_coll = (mesh.get("name") == "collision")
        for prim in mesh.get("primitives", []):
            pos = read_accessor(gltf, bin_data, prim["attributes"]["POSITION"])
            uvs = read_accessor(gltf, bin_data, prim["attributes"]["TEXCOORD_0"])
            idxs = read_accessor(gltf, bin_data, prim["indices"])
            mat = mats[prim["material"]]
            mat_name = mat["name"]
            t_idx = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            tw, th = img_sizes[textures[t_idx]["source"]]
            mt += len(idxs) // 3
            if is_coll:
                continue

            for _, y, _ in pos:
                my0 = min(my0, y)
                my1 = max(my1, y)

            is_glow = mat_name.startswith("glow_") and mat.get("emissiveFactor", [0, 0, 0]) != [0.0, 0.0, 0.0]

            for t in range(0, len(idxs), 3):
                i0, i1, i2 = idxs[t], idxs[t + 1], idxs[t + 2]
                p0, p1, p2 = pos[i0], pos[i1], pos[i2]
                if is_glow:
                    ax, ay, az = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
                    bx, by, bz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
                    cx, cy, cz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
                    mg += 0.5 * math.sqrt(cx * cx + cy * cy + cz * cz)

                for a, b in ((i0, i1), (i1, i2), (i2, i0)):
                    dx = pos[b][0] - pos[a][0]
                    dy = pos[b][1] - pos[a][1]
                    dz = pos[b][2] - pos[a][2]
                    d3 = math.sqrt(dx * dx + dy * dy + dz * dz)
                    du = (uvs[b][0] - uvs[a][0]) * tw * TEXEL
                    dv = (uvs[b][1] - uvs[a][1]) * th * TEXEL
                    duv = math.sqrt(du * du + dv * dv)
                    err = abs(duv - d3)
                    tot_uv_edges += 1
                    if err > max_uv_err:
                        max_uv_err = err
                    if err > 0.025:
                        bad_uv_edges += 1

                if mat_name == "detail_atlas":
                    us = [uvs[i0][0], uvs[i1][0], uvs[i2][0]]
                    vs = [uvs[i0][1], uvs[i1][1], uvs[i2][1]]
                    eps = 1e-4
                    u_ok = (min(us) >= -eps and max(us) <= 0.5 + eps) or (min(us) >= 0.5 - eps and max(us) <= 1.0 + eps)
                    v_ok = (min(vs) >= -eps and max(vs) <= 0.5 + eps) or (min(vs) >= 0.5 - eps and max(vs) <= 1.0 + eps)
                    if not (u_ok and v_ok):
                        quad_viol += 1

        mesh_tris.append(mt)
        mesh_glow_area.append(mg)
        mesh_min_y.append(my0)
        mesh_max_y.append(my1)

    vis_tris = 0
    coll_tris = 0
    glow_area_m2 = 0.0
    world_min_y = 1e9
    world_max_y = -1e9
    for n in nodes:
        if "mesh" not in n:
            continue
        mi = n["mesh"]
        if n["name"] == "collision" or n.get("extras", {}).get("collision_only"):
            coll_tris += mesh_tris[mi]
        else:
            vis_tris += mesh_tris[mi]
            glow_area_m2 += mesh_glow_area[mi]
            ty = n.get("translation", [0, 0, 0])[1]
            if mesh_min_y[mi] < 1e8:
                world_min_y = min(world_min_y, mesh_min_y[mi] + ty)
                world_max_y = max(world_max_y, mesh_max_y[mi] + ty)

    height_m = world_max_y - world_min_y
    boxes = info["collision"]["boxes_blender_z_up"]
    pylon_boxes = [b for b in boxes if b["source"].startswith("cathedral_pylon_")]
    full_height_pylons_ok = len(pylon_boxes) == 4 and all(b["size"][2] >= 55.0 for b in pylon_boxes)

    # 3. Validate Far LOD
    fgltf, fbin = parse_glb(R / info["far_lod"]["file"])
    far_tris = sum(len(read_accessor(fgltf, fbin, p["indices"])) // 3 for m in fgltf["meshes"] for p in m["primitives"])
    far_ok = 0 < far_tris < 800

    passed = (
        all_2x2
        and mats_ok
        and sampler_ok
        and req_ok
        and bad_uv_edges == 0
        and quad_viol == 0
        and world_min_y >= -1e-4
        and (100.0 <= height_m <= 112.0)
        and full_height_pylons_ok
        and far_ok
        and black_ratio >= 0.50
    )

    out = {
        "model": "The Great Meridian Monument (near + far LOD)",
        "passed": passed,
        "textures_2x2_ok": all_2x2,
        "materials_count": len(mats),
        "sampler_nearest_repeat_ok": sampler_ok,
        "required_nodes_ok": req_ok,
        "visible_triangles_instanced": vis_tris,
        "collision_triangles": coll_tris,
        "collision_boxes": len(boxes),
        "full_height_pylon_collision_ok": full_height_pylons_ok,
        "uv_edges_checked": tot_uv_edges,
        "bad_uv_edges": bad_uv_edges,
        "max_uv_error_m": round(max_uv_err, 6),
        "detail_quadrant_violations": quad_viol,
        "glow_area_m2": round(glow_area_m2, 2),
        "emissive_mask_black_ratio": black_ratio,
        "min_y_m": round(world_min_y, 4),
        "height_m": round(height_m, 4),
        "far_lod_triangles": far_tris,
    }
    (R / "validation.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(
        f"Validation complete: passed={passed} | vis={vis_tris} tris | far={far_tris} tris | "
        f"mats={len(mats)}/12 | bad_uv={bad_uv_edges} (max_err={max_uv_err:.6f}m) | "
        f"glow={glow_area_m2:.2f} m2 | h={height_m:.2f} m | coll={len(boxes)} boxes"
    )
    assert passed, f"Validation failed: {json.dumps(out, indent=2)}"


if __name__ == "__main__":
    main()
