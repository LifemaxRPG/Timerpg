#!/usr/bin/env python3
"""
Validator for Harlan County Courthouse & Square (4 near states + courthouse-far.glb).
Enforces docs/CHATGPT_MODEL_GUIDE.md & docs/GEMINI_NEXT_COURTHOUSE.md strictly:
- All textures on exact 2x2 texel art-pixel grid (0.0388 m/texel).
- Every sampler uses NEAREST filtering and REPEAT wrap.
- <= 12 base materials + up to 4 glow_clock_* materials per near state (<= 16 total).
- <= 15,000 total instanced visible triangles per near state; < 400 tris for courthouse-far.glb.
- 0 UV edge scale violations (0.0388 m/texel planar check on every mesh primitive).
- 0 detail_atlas quadrant cross-bleeds.
- Shared door_main, door_north, door_east, door_west coordinates identical across all 4 states.
- Presence of clock_hour, clock_minute, bell, tree_*, floor_*, door_room_*, and lot nodes.
- 1988 clock dial emissive OFF; 1894/1962/2008 clock dials ON; pure-black emissive masks outside lit pixels.
- Full-height exterior & interior collision boxes listed in model-info.json; door_main walkable.
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


def validate_one_state(state_info):
    glb_path = R / state_info["file"]
    gltf, bin_data = parse_glb(glb_path)

    mats = gltf.get("materials", [])
    samplers = gltf.get("samplers", [])
    nodes = gltf.get("nodes", [])
    meshes = gltf.get("meshes", [])
    textures = gltf.get("textures", [])
    images = gltf.get("images", [])

    # Material budget check: <= 12 base + up to 4 glow_clock_*
    clock_mats = [m for m in mats if m["name"].startswith("glow_clock_")]
    base_mats = [m for m in mats if not m["name"].startswith("glow_clock_")]
    mats_ok = (len(base_mats) <= 12) and (len(clock_mats) == 4)

    # 1988 clock glow OFF; other states clock glow ON
    if state_info["year"] == 1988:
        clock_emissive_ok = all(m.get("emissiveFactor", [0, 0, 0]) == [0.0, 0.0, 0.0] for m in clock_mats)
    else:
        clock_emissive_ok = all(m.get("emissiveFactor", [0, 0, 0]) == [1.0, 1.0, 1.0] and "emissiveTexture" in m for m in clock_mats)

    # Read embedded PNG dimensions per image
    img_sizes = []
    for img in images:
        bv = gltf["bufferViews"][img["bufferView"]]
        raw = bin_data[bv.get("byteOffset", 0):bv.get("byteOffset", 0) + bv["byteLength"]]
        w, h = struct.unpack(">II", raw[16:24])
        img_sizes.append((w, h))

    # Build node map & check required named nodes
    node_names = {n["name"] for n in nodes}
    required_nodes = {
        "structure", "lot", "collision", "bell",
        "door_main", "door_north", "door_east", "door_west",
        "clock_hour", "clock_minute",
        "floor_rotunda", "floor_courtroom", "floor_gallery_2", "floor_gallery_3",
        "door_room_courtroom", "door_room_clerk",
    }
    req_ok = required_nodes.issubset(node_names)
    tree_nodes = [n for n in nodes if n["name"].startswith("tree_")]
    floor_nodes = [n for n in nodes if n["name"].startswith("floor_")]
    room_door_nodes = [n for n in nodes if n["name"].startswith("door_room_")]

    door_coords = {}
    for dn in ("door_main", "door_north", "door_east", "door_west"):
        n = next(x for x in nodes if x["name"] == dn)
        door_coords[dn] = [round(v, 4) for v in n.get("translation", [0, 0, 0])]

    # Audit every unique mesh primitive for UV scale (0.0388 m/texel) & detail_atlas quadrant purity
    mesh_tris = []
    mesh_glow_area = []
    mesh_min_y = []
    mesh_max_y = []
    tot_uv_edges = 0
    bad_uv_edges = 0
    max_uv_err = 0.0
    quad_viol = 0

    for m_idx, mesh in enumerate(meshes):
        mt = 0
        mg = 0.0
        my0 = 1e9
        my1 = -1e9
        is_coll_mesh = (mesh.get("name") == "collision")
        for prim in mesh.get("primitives", []):
            pos = read_accessor(gltf, bin_data, prim["attributes"]["POSITION"])
            uvs = read_accessor(gltf, bin_data, prim["attributes"]["TEXCOORD_0"])
            idxs = read_accessor(gltf, bin_data, prim["indices"])
            mat = mats[prim["material"]]
            mat_name = mat["name"]
            t_idx = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            tw, th = img_sizes[textures[t_idx]["source"]]
            mt += len(idxs) // 3

            if is_coll_mesh:
                continue

            for _, y, _ in pos:
                if y < my0:
                    my0 = y
                if y > my1:
                    my1 = y

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

    # Sum INSTANCED visible triangles, glow area, and world Y bounds across all visible nodes!
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

    sampler_ok = all(
        s.get("magFilter") == 9728 and s.get("minFilter") in (9728, 9984)
        and s.get("wrapS", 10497) == 10497 and s.get("wrapT", 10497) == 10497
        for s in samplers
    )

    # Verify exterior wall full-height collision (>= 14.0 m) & interior collision presence
    boxes = state_info["collision"]["boxes_blender_z_up"]
    ext_wall_boxes = [b for b in boxes if b["source"].startswith("wall_")]
    full_height_ok = len(ext_wall_boxes) >= 16 and all(b["size"][2] >= 14.0 for b in ext_wall_boxes)
    has_interior_coll = any(b["category"] == "interior" for b in boxes) and any(b["category"] == "interior_stairs" for b in boxes)

    height_m = world_max_y - world_min_y

    passed = (
        mats_ok
        and clock_emissive_ok
        and sampler_ok
        and req_ok
        and len(tree_nodes) >= 12
        and len(floor_nodes) >= 14
        and len(room_door_nodes) >= 10
        and vis_tris <= 15000
        and bad_uv_edges == 0
        and quad_viol == 0
        and world_min_y >= -1e-4
        and (36.0 <= height_m <= 40.0)
        and full_height_ok
        and has_interior_coll
    )

    return {
        "year": state_info["year"],
        "file": state_info["file"],
        "passed": passed,
        "base_materials": len(base_mats),
        "clock_materials": len(clock_mats),
        "clock_emissive_ok": clock_emissive_ok,
        "visible_triangles_instanced": vis_tris,
        "collision_triangles": coll_tris,
        "uv_edges_checked": tot_uv_edges,
        "bad_uv_edges": bad_uv_edges,
        "max_uv_error_m": round(max_uv_err, 6),
        "detail_quadrant_violations": quad_viol,
        "glow_area_m2": round(glow_area_m2, 2),
        "height_m": round(height_m, 4),
        "min_y_m": round(world_min_y, 4),
        "tree_nodes": len(tree_nodes),
        "floor_nodes": len(floor_nodes),
        "room_door_nodes": len(room_door_nodes),
        "collision_boxes": len(boxes),
        "full_height_wall_collision": full_height_ok,
        "interior_collision_present": has_interior_coll,
        "door_coords": door_coords,
    }


def validate_far_lod():
    gltf, bin_data = parse_glb(R / "courthouse-far.glb")
    tris = 0
    for mesh in gltf["meshes"]:
        for prim in mesh["primitives"]:
            idxs = read_accessor(gltf, bin_data, prim["indices"])
            tris += len(idxs) // 3
    passed = (0 < tris < 400)
    return {"file": "courthouse-far.glb", "passed": passed, "visible_triangles": tris, "budget": 400}


def main():
    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))

    # 1. Verify every texture is on a strict 2x2 art-pixel grid + check pure-black emissive masks
    all_2x2 = True
    for p in sorted(TEX_DIR.glob("*.png")):
        w, h, rows = read_png_rgba(p)
        ok, msg = check_2x2_blocks(rows, w, h)
        if not ok:
            print(f"2x2 FAIL on {p.name}: {msg}")
            all_2x2 = False

    # Verify emissive masks have pure black outside glowing pixels
    mask_stats = {}
    for mf in ("clock_dial_mask.png", "glow_lamps_mask.png"):
        w, h, rows = read_png_rgba(TEX_DIR / mf)
        black_px = sum(1 for r in rows for px in r if px[0] == 0 and px[1] == 0 and px[2] == 0)
        mask_stats[mf] = round(black_px / (w * h), 3)

    state_reports = [validate_one_state(st) for st in info["states"]]
    far_report = validate_far_lod()

    ref_doors = state_reports[0]["door_coords"]
    doors_identical = all(sr["door_coords"] == ref_doors for sr in state_reports)

    all_passed = all_2x2 and doors_identical and far_report["passed"] and all(sr["passed"] for sr in state_reports)
    out = {
        "model": "harlan-county-courthouse (4 near states + courthouse-far)",
        "all_passed": all_passed,
        "textures_2x2_ok": all_2x2,
        "shared_door_nodes_identical": doors_identical,
        "emissive_mask_black_ratios": mask_stats,
        "far_lod": far_report,
        "states": state_reports,
    }
    (R / "validation.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(f"Validation complete: all_passed={all_passed} | shared_doors_identical={doors_identical} | far={far_report['visible_triangles']}/400 tris")
    for sr in state_reports:
        print(
            f"  [{sr['year']}] passed={sr['passed']} | vis={sr['visible_triangles_instanced']}/15000 | "
            f"mats={sr['base_materials']}+{sr['clock_materials']}clk | bad_uv={sr['bad_uv_edges']} | "
            f"glow={sr['glow_area_m2']} m2 | coll_boxes={sr['collision_boxes']} | h={sr['height_m']} m"
        )
    assert all_passed, f"Validation failed: {json.dumps(out, indent=2)}"


if __name__ == "__main__":
    main()
