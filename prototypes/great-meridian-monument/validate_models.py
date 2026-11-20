#!/usr/bin/env python3
"""
Strict contract validator for THE GREAT MERIDIAN MONUMENT (4 historical states + Far LOD).
Verifies every requirement from docs/CHATGPT_MODEL_GUIDE.md & docs/GEMINI_MERIDIAN_REVISION.md:
- All PNG textures strictly on 2x2 texel art-pixel grid (0.0388 m/texel).
- glow_monument_mask.png has pure #000000 black outside glowing pixels (>= 50%).
- All 4 states (1926, 1941, 1976, 2000):
  * <= 30,000 visible instanced triangles
  * <= 12 named role materials, NEAREST + REPEAT samplers
  * 0 UV scale stretch violations (0.0388 m/texel planar check on every triangle edge)
  * 0 detail_atlas quadrant cross-bleeds
  * Emissive total surface area STRICTLY < 20.0 m2 (and 1976 < 2000 since beacon off & 3 lamps broken!)
  * Plaza ground >= 0.0, reflecting pool water EXACTLY at y = 0.0 (basin below)
  * Required nodes: structure, armillary_sphere, armillary_outer, armillary_ecliptic, armillary_globe,
    lift_car, beacon_beam, flood_01..12, tree_01..08, lot, collision, door_*, floor_*
  * Full-height glTF Y-up (+Z front) collision boxes listed in model-info.json
- Far LOD (great-meridian-far.glb): STRICTLY < 400 triangles, 0 UV stretch errors, includes glowing beacon orb.
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


def validate_state_glb(state_id, glb_path):
    gltf, bin_data = parse_glb(glb_path)
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
        "structure", "armillary_sphere", "armillary_outer", "armillary_ecliptic", "armillary_globe",
        "lift_car", "beacon_beam", "lot", "collision",
        "door_main", "door_rotunda_south", "door_north", "door_east", "door_west",
        "door_hall_west", "door_hall_east", "door_observatory_lift",
        "door_cornice_gallery", "door_sky_deck", "door_lantern_chamber",
        "floor_plaza", "floor_stairs_pz", "floor_stairs_mz", "floor_stairs_px", "floor_stairs_mx",
        "floor_rotunda_plaza", "floor_hall_west", "floor_hall_east",
        "floor_stair_landings", "floor_cornice_gallery", "floor_observation_deck", "floor_lantern_chamber",
    }
    for i in range(1, 13):
        required_nodes.add(f"flood_{i:02d}")
    for i in range(1, 9):
        required_nodes.add(f"tree_{i:02d}")
    if state_id == "2000":
        required_nodes.update({"floor_access_ramp", "floor_visitor_center", "door_visitor_center"})
    missing_nodes = sorted(required_nodes - node_names)
    req_ok = len(missing_nodes) == 0

    img_sizes = []
    for img in images:
        bv = gltf["bufferViews"][img["bufferView"]]
        raw = bin_data[bv.get("byteOffset", 0):bv.get("byteOffset", 0) + bv["byteLength"]]
        w, h = struct.unpack(">II", raw[16:24])
        img_sizes.append((w, h))

    # Build parent lookup so child nodes inherit parent translation (e.g. armillary_* under armillary_sphere)
    parent_map = {}
    for pi, pn in enumerate(nodes):
        for ci in pn.get("children", []):
            parent_map[ci] = pi

    def world_ty(ni):
        ty = nodes[ni].get("translation", [0.0, 0.0, 0.0])[1]
        cur = ni
        while cur in parent_map:
            cur = parent_map[cur]
            ty += nodes[cur].get("translation", [0.0, 0.0, 0.0])[1]
        return ty

    mesh_tris = []
    mesh_glow_area = []
    mesh_min_y = []
    mesh_max_y = []
    water_ys = []
    plaza_min_y = 1e9
    tot_uv_edges = 0
    bad_uv_edges = 0
    max_uv_err = 0.0
    quad_viol = 0

    for mesh in meshes:
        mt = 0
        mg = 0.0
        my0 = 1e9
        my1 = -1e9
        mname = mesh.get("name", "")
        is_coll = (mname == "collision")
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
                if mname == "floor_plaza":
                    plaza_min_y = min(plaza_min_y, y)
                if mat_name == "water_pool":
                    water_ys.append(y)

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
    for ni, n in enumerate(nodes):
        if "mesh" not in n:
            continue
        mi = n["mesh"]
        if n["name"] == "collision" or n.get("extras", {}).get("collision_only"):
            coll_tris += mesh_tris[mi]
        else:
            vis_tris += mesh_tris[mi]
            glow_area_m2 += mesh_glow_area[mi]
            ty = world_ty(ni)
            if mesh_min_y[mi] < 1e8:
                world_min_y = min(world_min_y, mesh_min_y[mi] + ty)
                world_max_y = max(world_max_y, mesh_max_y[mi] + ty)

    water_y_ok = len(water_ys) > 0 and all(abs(y) < 1e-4 for y in water_ys)
    plaza_y_ok = plaza_min_y >= 0.0
    basin_y_ok = world_min_y >= -0.75  # Sunken reflecting pool basin below water at y = 0.0
    height_ok = 102.0 <= world_max_y <= 108.0
    tris_ok = vis_tris <= 30000
    glow_ok = glow_area_m2 < 20.0

    passed = (
        mats_ok and sampler_ok and req_ok and tris_ok and glow_ok
        and bad_uv_edges == 0 and quad_viol == 0
        and water_y_ok and plaza_y_ok and basin_y_ok and height_ok
    )
    return {
        "file": glb_path.name,
        "passed": passed,
        "materials_count": len(mats),
        "sampler_nearest_repeat_ok": sampler_ok,
        "required_nodes_ok": req_ok,
        "missing_nodes": missing_nodes,
        "visible_triangles_instanced": vis_tris,
        "collision_triangles": coll_tris,
        "uv_edges_checked": tot_uv_edges,
        "bad_uv_edges": bad_uv_edges,
        "max_uv_error_m": round(max_uv_err, 6),
        "detail_quadrant_violations": quad_viol,
        "glow_area_m2": round(glow_area_m2, 2),
        "plaza_min_y_m": round(plaza_min_y, 4),
        "water_at_y0_ok": water_y_ok,
        "basin_min_y_m": round(world_min_y, 4),
        "max_y_m": round(world_max_y, 4),
    }


def validate_far_glb(glb_path):
    gltf, bin_data = parse_glb(glb_path)
    mats = gltf.get("materials", [])
    textures = gltf.get("textures", [])
    images = gltf.get("images", [])
    img_sizes = []
    for img in images:
        bv = gltf["bufferViews"][img["bufferView"]]
        raw = bin_data[bv.get("byteOffset", 0):bv.get("byteOffset", 0) + bv["byteLength"]]
        w, h = struct.unpack(">II", raw[16:24])
        img_sizes.append((w, h))

    tris = 0
    bad_uv = 0
    max_uv_err = 0.0
    has_beacon_glow = False
    for m in gltf["meshes"]:
        for p in m["primitives"]:
            pos = read_accessor(gltf, bin_data, p["attributes"]["POSITION"])
            uvs = read_accessor(gltf, bin_data, p["attributes"]["TEXCOORD_0"])
            idxs = read_accessor(gltf, bin_data, p["indices"])
            mat = mats[p["material"]]
            t_idx = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            tw, th = img_sizes[textures[t_idx]["source"]]
            tris += len(idxs) // 3
            if mat["name"] == "glow_monument":
                has_beacon_glow = True
            for t in range(0, len(idxs), 3):
                i0, i1, i2 = idxs[t], idxs[t + 1], idxs[t + 2]
                for a, b in ((i0, i1), (i1, i2), (i2, i0)):
                    d3 = math.sqrt(sum((pos[b][k] - pos[a][k]) ** 2 for k in range(3)))
                    du = (uvs[b][0] - uvs[a][0]) * tw * TEXEL
                    dv = (uvs[b][1] - uvs[a][1]) * th * TEXEL
                    err = abs(math.hypot(du, dv) - d3)
                    max_uv_err = max(max_uv_err, err)
                    if err > 0.025:
                        bad_uv += 1
    return {
        "file": glb_path.name,
        "passed": (0 < tris < 400) and (bad_uv == 0) and has_beacon_glow,
        "visible_triangles": tris,
        "bad_uv_edges": bad_uv,
        "max_uv_error_m": round(max_uv_err, 6),
        "has_beacon_glow": has_beacon_glow,
    }


def main():
    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))

    # 1. Verify every PNG texture is on a strict 2x2 art-pixel grid
    all_2x2 = True
    tex_count = 0
    for p in sorted(TEX_DIR.glob("*.png")):
        tex_count += 1
        w, h, rows = read_png_rgba(p)
        ok, msg = check_2x2_blocks(rows, w, h)
        if not ok:
            print(f"2x2 FAIL on {p.name}: {msg}")
            all_2x2 = False

    mw, mh, mrows = read_png_rgba(TEX_DIR / "glow_monument_mask.png")
    black_ratio = round(
        sum(1 for r in mrows for px in r if px[0] == 0 and px[1] == 0 and px[2] == 0) / (mw * mh),
        3,
    )

    # 2. Validate all 4 historical states
    state_results = {}
    for sid in ("1926", "1941", "1976", "2000"):
        sfile = R / info["states"][sid]["file"]
        res = validate_state_glb(sid, sfile)
        state_results[sid] = res
        print(
            f"State {sid}: passed={res['passed']} | vis={res['visible_triangles_instanced']}/30000 tris | "
            f"mats={res['materials_count']}/12 | bad_uv={res['bad_uv_edges']} (max={res['max_uv_error_m']}m) | "
            f"glow={res['glow_area_m2']} m2 (<20) | water@0={res['water_at_y0_ok']} | h={res['max_y_m']}m"
        )

    # Verify 1976 worn state actually has lower emissive area than 2000 restored (beacon off + 3 lamps broken!)
    worn_dimmer_ok = state_results["1976"]["glow_area_m2"] < state_results["2000"]["glow_area_m2"]

    # 3. Validate Far LOD (< 400 tris!)
    far_res = validate_far_glb(R / info["far_lod"]["file"])
    print(
        f"Far LOD: passed={far_res['passed']} | tris={far_res['visible_triangles']}/400 | "
        f"bad_uv={far_res['bad_uv_edges']} | beacon_glow={far_res['has_beacon_glow']}"
    )

    # 4. Validate glTF Y-up (+Z front) collision boxes in model-info.json
    boxes = info["collision"]["boxes_gltf_y_up"]
    pier_boxes = [b for b in boxes if b["source"].startswith("cathedral_pier_")]
    full_height_piers_ok = (
        len(pier_boxes) == 4
        and all(b["size"][1] >= 33.0 and b["center"][1] > 35.0 for b in pier_boxes)
    )

    passed = (
        all_2x2
        and black_ratio >= 0.50
        and all(r["passed"] for r in state_results.values())
        and worn_dimmer_ok
        and far_res["passed"]
        and full_height_piers_ok
    )

    out = {
        "model": "The Great Meridian Monument (4 historical states + Far LOD)",
        "passed": passed,
        "textures_count": tex_count,
        "textures_2x2_ok": all_2x2,
        "emissive_mask_black_ratio": black_ratio,
        "collision_coordinate_frame": info["collision"]["coordinate_frame"],
        "collision_boxes_count": len(boxes),
        "full_height_pier_collision_ok": full_height_piers_ok,
        "worn_1976_dimmer_than_2000_ok": worn_dimmer_ok,
        "states": state_results,
        "far_lod": far_res,
    }
    (R / "validation.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(f"OVERALL VALIDATION: passed={passed} | textures={tex_count} | black_ratio={black_ratio} | coll={len(boxes)} boxes (glTF Y-up)")
    assert passed, f"Validation failed:\n{json.dumps(out, indent=2)}"


if __name__ == "__main__":
    main()
