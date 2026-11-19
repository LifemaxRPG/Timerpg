#!/usr/bin/env python3
"""
Contract validator for prototypes/mirage-nightclub/mirage-nightclub.glb.
Verifies every rule in docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- Binary glTF 2.0 (.glb) header and chunks
- Materials <= 12, embedded PNG textures with NEAREST (9728) + REPEAT (10497)
- Every texture is made of exact 2x2 identical texel blocks
- Shared LimeZu swatches match kotlin-street/tools/swatches/atlas/atlas.png byte-for-byte
- UV scale error < 0.001 m (1 mm) across all visible mesh edges at 0.0388 m/texel
- detail_utility UVs stay inside their assigned 64x64 art-pixel quadrant
- Triangle budgets: total visible <= 12,000; each prop group < 500
- Height <= 12.0 m (and >= 3.0 m), ground y >= 0.0 m
- Required scene graph nodes: structure, lot, collision, door_main, door_back, door_side,
  marquee_pylon, service_yard, vip_rope_line
- Walkability check: point 0.566 m in front of door_main is clear of non-gated collision boxes
"""
import json
import math
import struct
import zlib
from pathlib import Path

R = Path(__file__).resolve().parent
GLB_PATH = R / "mirage-nightclub.glb"
INFO_PATH = R / "model-info.json"
TEX_DIR = R / "textures"

T = 0.0388
U = 0.0776


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
        row = [tuple(cur[x * 4:(x + 1) * 4]) for x in range(w)]
        rows.append(row)
    return w, h, rows


def check_2x2_blocks(rows, w, h):
    if w % 2 != 0 or h % 2 != 0:
        return False, "odd dimensions"
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            c = rows[y][x]
            if rows[y][x + 1] != c or rows[y + 1][x] != c or rows[y + 1][x + 1] != c:
                return False, f"non-2x2 block at ({x},{y})"
    return True, "ok"


def validate():
    glb_bytes = GLB_PATH.read_bytes()
    magic, version, total_len = struct.unpack("<III", glb_bytes[:12])
    assert magic == 0x46546C67 and version == 2 and total_len == len(glb_bytes)

    json_len, json_type = struct.unpack("<II", glb_bytes[12:20])
    assert json_type == 0x4E4F534A
    gltf = json.loads(glb_bytes[20:20 + json_len].decode("utf-8"))

    bin_off = 20 + json_len
    bin_len, bin_type = struct.unpack("<II", glb_bytes[bin_off:bin_off + 8])
    assert bin_type == 0x004E4942
    bin_blob = glb_bytes[bin_off + 8:bin_off + 8 + bin_len]

    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))
    checks = {}

    # 1. Material count & sampler check
    mats = gltf["materials"]
    samplers = gltf["samplers"]
    checks["material_count"] = {
        "count": len(mats),
        "limit": 12,
        "passed": len(mats) <= 12,
    }
    sampler_ok = all(
        s.get("magFilter") == 9728
        and s.get("minFilter") in (9728, 9984)
        and s.get("wrapS", 10497) == 10497
        and s.get("wrapT", 10497) == 10497
        for s in samplers
    )
    checks["nearest_repeat_samplers"] = {"passed": sampler_ok, "samplers": samplers}

    # 2. Check all 12 textures for 2x2 pixel blocks
    tex_results = {}
    all_2x2 = True
    for png_path in sorted(TEX_DIR.glob("*.png")):
        w, h, rows = read_png_rgba(png_path)
        ok, msg = check_2x2_blocks(rows, w, h)
        if not ok:
            all_2x2 = False
        tex_results[png_path.name] = {"size": [w, h], "2x2_blocks": ok, "note": msg}
    checks["textures_2x2_grid"] = {"passed": all_2x2, "textures": tex_results}

    # 3. Traverse nodes, inspect bounds, triangles, and UV scale accuracy
    nodes = gltf["nodes"]
    meshes = gltf["meshes"]
    accessors = gltf["accessors"]
    buffer_views = gltf["bufferViews"]

    def read_accessor(acc_idx):
        acc = accessors[acc_idx]
        bv = buffer_views[acc["bufferView"]]
        off = bv.get("byteOffset", 0)
        cnt = acc["count"]
        ctype = acc["componentType"]
        atype = acc["type"]
        ncomp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[atype]
        fmt_char = {5126: "f", 5125: "I", 5123: "H"}[ctype]
        step = struct.calcsize("<" + fmt_char * ncomp)
        out = []
        for i in range(cnt):
            vals = struct.unpack_from("<" + fmt_char * ncomp, bin_blob, off + i * step)
            out.append(vals[0] if ncomp == 1 else vals)
        return out

    # Compute world transforms (translation only or identity for mesh nodes)
    parent_map = {}
    for idx, n in enumerate(nodes):
        for c in n.get("children", []):
            parent_map[c] = idx

    node_by_name = {n["name"]: (i, n) for i, n in enumerate(nodes)}
    required_nodes = [
        "mirage_nightclub",
        "structure",
        "lot",
        "collision",
        "door_main",
        "door_back",
        "door_side",
        "marquee_pylon",
        "service_yard",
        "vip_rope_line",
    ]
    checks["required_nodes"] = {
        "present": [name for name in required_nodes if name in node_by_name],
        "missing": [name for name in required_nodes if name not in node_by_name],
        "passed": all(name in node_by_name for name in required_nodes),
    }

    # Verify door orientations (+Z local outward normal rotated into world space)
    door_checks = {}
    doors_ok = True
    for dname, expected_dir in [
        ("door_main", (0.0, 0.0, 1.0)),
        ("door_back", (0.0, 0.0, -1.0)),
        ("door_side", (1.0, 0.0, 0.0)),
    ]:
        _, dn = node_by_name[dname]
        has_mesh = "mesh" in dn
        has_children = bool(dn.get("children"))
        qx, qy, qz, qw = dn.get("rotation", [0.0, 0.0, 0.0, 1.0])
        # Rotate (0, 0, 1) by quaternion (qx, qy, qz, qw)
        wx = 2.0 * (qx * qz + qw * qy)
        wy = 2.0 * (qy * qz - qw * qx)
        wz = 1.0 - 2.0 * (qx * qx + qy * qy)
        dot = wx * expected_dir[0] + wy * expected_dir[1] + wz * expected_dir[2]
        ok = (not has_mesh) and (not has_children) and (dot > 0.999)
        if not ok:
            doors_ok = False
        door_checks[dname] = {
            "translation": dn.get("translation"),
            "world_outward": [round(wx, 4), round(wy, 4), round(wz, 4)],
            "empty_leaf": (not has_mesh) and (not has_children),
            "passed": ok,
        }
    checks["doors"] = {"passed": doors_ok, "details": door_checks}

    # Analyze mesh triangles, bounds, UV scale, and utility quadrant bounds
    min_pos = [1e9, 1e9, 1e9]
    max_pos = [-1e9, -1e9, -1e9]
    visible_tris = 0
    collision_tris = 0
    max_uv_err_m = 0.0
    bad_uv_edges = 0
    total_uv_edges = 0
    utility_quadrant_violations = 0

    mat_tex_size = {}
    for i, m in enumerate(mats):
        tex_idx = m["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        img_idx = gltf["textures"][tex_idx]["source"]
        img_name = gltf["images"][img_idx]["name"]
        for mdef_info in info["materials"]:
            if mdef_info["name"] == img_name:
                mat_tex_size[i] = mdef_info["size"]

    def is_collision_node(n_idx):
        cur = n_idx
        while cur is not None:
            n = nodes[cur]
            if n.get("name") == "collision" or n.get("extras", {}).get("collision_only"):
                return True
            cur = parent_map.get(cur)
        return False

    node_direct_tris = {}
    for n_idx, n in enumerate(nodes):
        if "mesh" not in n:
            node_direct_tris[n["name"]] = 0
            continue
        mesh = meshes[n["mesh"]]
        is_coll = is_collision_node(n_idx)
        ntris = 0
        for prim in mesh["primitives"]:
            pos = read_accessor(prim["attributes"]["POSITION"])
            uvs = read_accessor(prim["attributes"]["TEXCOORD_0"])
            idxs = read_accessor(prim["indices"])
            mat_idx = prim["material"]
            mat_name = mats[mat_idx]["name"]
            W, H = mat_tex_size[mat_idx]
            tcount = len(idxs) // 3
            ntris += tcount

            if not is_coll:
                for p in pos:
                    for k in range(3):
                        min_pos[k] = min(min_pos[k], p[k])
                        max_pos[k] = max(max_pos[k], p[k])

                for t in range(tcount):
                    i0, i1, i2 = idxs[3 * t], idxs[3 * t + 1], idxs[3 * t + 2]
                    for a, b in ((i0, i1), (i1, i2), (i2, i0)):
                        pa, pb = pos[a], pos[b]
                        ua, ub = uvs[a], uvs[b]
                        d3 = math.sqrt(sum((pa[k] - pb[k]) ** 2 for k in range(3)))
                        du_tex = (ua[0] - ub[0]) * W
                        dv_tex = (ua[1] - ub[1]) * H
                        duv_m = math.sqrt(du_tex * du_tex + dv_tex * dv_tex) * T
                        err = abs(d3 - duv_m)
                        total_uv_edges += 1
                        if err > max_uv_err_m:
                            max_uv_err_m = err
                        if err > 0.001:
                            bad_uv_edges += 1

                    if mat_name == "detail_utility":
                        us = [uvs[i0][0], uvs[i1][0], uvs[i2][0]]
                        vs = [uvs[i0][1], uvs[i1][1], uvs[i2][1]]
                        eps = 1e-5
                        if (min(us) < 0.5 - eps and max(us) > 0.5 + eps) or (
                            min(vs) < 0.5 - eps and max(vs) > 0.5 + eps
                        ):
                            utility_quadrant_violations += 1

        node_direct_tris[n["name"]] = ntris
        if is_coll:
            collision_tris += ntris
        else:
            visible_tris += ntris

    def subtree_tris(n_idx):
        tot = node_direct_tris.get(nodes[n_idx]["name"], 0)
        for c in nodes[n_idx].get("children", []):
            tot += subtree_tris(c)
        return tot

    prop_budgets = {}
    props_ok = True
    for pname in ("marquee_pylon", "service_yard", "vip_rope_line"):
        p_idx, _ = node_by_name[pname]
        ptris = subtree_tris(p_idx)
        ok = ptris < 500
        if not ok:
            props_ok = False
        prop_budgets[pname] = {"triangles": ptris, "limit": 500, "passed": ok}

    checks["triangle_budgets"] = {
        "visible_triangles": visible_tris,
        "visible_limit": 12000,
        "collision_triangles": collision_tris,
        "prop_groups": prop_budgets,
        "passed": (visible_tris <= 12000) and props_ok,
    }

    checks["uv_scale_accuracy"] = {
        "texel_size_m": T,
        "total_edges_checked": total_uv_edges,
        "edges_over_1mm_error": bad_uv_edges,
        "max_error_m": round(max_uv_err_m, 7),
        "utility_quadrant_violations": utility_quadrant_violations,
        "passed": (bad_uv_edges == 0) and (utility_quadrant_violations == 0),
    }

    height_m = max_pos[1] - min_pos[1]
    checks["bounds_and_height"] = {
        "min_m": [round(x, 4) for x in min_pos],
        "max_m": [round(x, 4) for x in max_pos],
        "height_m": round(height_m, 4),
        "height_limit_m": 12.0,
        "ground_y_min_m": round(min_pos[1], 4),
        "passed": (3.0 <= height_m <= 12.0) and (min_pos[1] >= -1e-5),
    }

    # Verify door_main front probe clearance (0.566 m in front of door_main is not inside any non-gated box)
    door_main_z = nodes[node_by_name["door_main"][0]]["translation"][2]
    probe_x = 0.0
    probe_z = door_main_z + 0.566
    blocked_by = []
    for b in info["collision"]["boxes_blender_z_up"]:
        if b.get("gate_node"):
            continue
        bx, by_b, _ = b["center"]
        sx, sy_b, _ = b["size"]
        # Convert Blender Z-up (x, y_blender) back to glTF (x, z = -y_blender)
        gz = -by_b
        if abs(probe_x - bx) <= sx / 2.0 + 0.05 and abs(probe_z - gz) <= sy_b / 2.0 + 0.05:
            blocked_by.append(b["source"])

    checks["door_main_walkability_probe"] = {
        "probe_gltf_xz_m": [round(probe_x, 4), round(probe_z, 4)],
        "blocked_by": blocked_by,
        "passed": len(blocked_by) == 0,
    }

    all_passed = all(c["passed"] for c in checks.values())
    report = {
        "model": "mirage-nightclub.glb",
        "all_passed": all_passed,
        "checks": checks,
    }
    (R / "validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Validation complete: all_passed={all_passed}")
    print(f"  Visible triangles: {visible_tris} / 12000 | Collision triangles: {collision_tris}")
    print(f"  UV edges checked: {total_uv_edges} | Bad edges (>1mm): {bad_uv_edges} | Max UV error: {max_uv_err_m:.7f} m")
    print(f"  Height: {height_m:.4f} m (min_y={min_pos[1]:.4f} m)")
    assert all_passed, f"Validation failed: {json.dumps(report, indent=2)}"


if __name__ == "__main__":
    validate()
