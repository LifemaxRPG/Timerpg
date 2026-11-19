#!/usr/bin/env python3
"""
Contract validator for all 4 Reel Time Video strip-centre .glb files (1984, 1996, 2011, 2020).
Checks:
- Material count <= 12, NEAREST + REPEAT samplers, 2x2 texel blocks on every PNG
- Visible triangles < 5,000 per state; lot prop sub-groups < 500 tris each
- UV scale accuracy: 0 edges > 1 mm error; 0 detail_utility quadrant violations
- Emissive glow discipline: glow_mask_*.png is predominantly pure black (0,0,0) with only lit signs/lamps glowing
- Shared shell consistency: identical door_main & door_back nodes across all 4 states
- Full-height collision boxes listed in model-info.json + unobstructed door_main walkability
"""
import json
import math
import struct
import zlib
from pathlib import Path

R = Path(__file__).resolve().parent
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
        rows.append([tuple(cur[x * 4:(x + 1) * 4]) for x in range(w)])
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


def validate_one_state(state_info):
    glb_path = R / state_info["file"]
    glb_bytes = glb_path.read_bytes()
    magic, version, total_len = struct.unpack("<III", glb_bytes[:12])
    assert magic == 0x46546C67 and version == 2 and total_len == len(glb_bytes)

    json_len, json_type = struct.unpack("<II", glb_bytes[12:20])
    assert json_type == 0x4E4F534A
    gltf = json.loads(glb_bytes[20:20 + json_len].decode("utf-8"))

    bin_off = 20 + json_len
    bin_len, bin_type = struct.unpack("<II", glb_bytes[bin_off:bin_off + 8])
    assert bin_type == 0x004E4942
    bin_blob = glb_bytes[bin_off + 8:bin_off + 8 + bin_len]

    mats = gltf["materials"]
    samplers = gltf["samplers"]
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

    parent_map = {}
    for idx, n in enumerate(nodes):
        for c in n.get("children", []):
            parent_map[c] = idx

    node_by_name = {n["name"]: (i, n) for i, n in enumerate(nodes)}
    required = ["reel_time_video", "structure", "lot", "collision", "door_main", "door_back"]
    req_ok = all(k in node_by_name for k in required)

    # Doors check
    door_ok = True
    door_coords = {}
    for dname, exp in [("door_main", (0.0, 0.0, 1.0)), ("door_back", (0.0, 0.0, -1.0))]:
        _, dn = node_by_name[dname]
        qx, qy, qz, qw = dn.get("rotation", [0.0, 0.0, 0.0, 1.0])
        wx = 2.0 * (qx * qz + qw * qy)
        wy = 2.0 * (qy * qz - qw * qx)
        wz = 1.0 - 2.0 * (qx * qx + qy * qy)
        dot = wx * exp[0] + wy * exp[1] + wz * exp[2]
        if ("mesh" in dn) or dn.get("children") or dot < 0.999:
            door_ok = False
        door_coords[dname] = dn.get("translation")

    mat_tex_size = {}
    for i, m in enumerate(mats):
        tex_idx = m["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        img_name = gltf["images"][gltf["textures"][tex_idx]["source"]]["name"]
        for mi in state_info["materials"]:
            if mi["name"] == img_name:
                mat_tex_size[i] = mi["size"]

    def is_coll(n_idx):
        cur = n_idx
        while cur is not None:
            n = nodes[cur]
            if n.get("name") == "collision" or n.get("extras", {}).get("collision_only"):
                return True
            cur = parent_map.get(cur)
        return False

    min_p = [1e9, 1e9, 1e9]
    max_p = [-1e9, -1e9, -1e9]
    vis_tris = 0
    coll_tris = 0
    bad_uv = 0
    tot_uv = 0
    max_uv_err = 0.0
    quad_viol = 0
    glow_area_m2 = 0.0
    node_tris = {}

    for n_idx, n in enumerate(nodes):
        if "mesh" not in n:
            node_tris[n["name"]] = 0
            continue
        mesh = meshes[n["mesh"]]
        coll_flag = is_coll(n_idx)
        ntris = 0
        for prim in mesh["primitives"]:
            pos = read_accessor(prim["attributes"]["POSITION"])
            uvs = read_accessor(prim["attributes"]["TEXCOORD_0"])
            idxs = read_accessor(prim["indices"])
            mat_i = prim["material"]
            mat_name = mats[mat_i]["name"]
            W, H = mat_tex_size[mat_i]
            tc = len(idxs) // 3
            ntris += tc

            if not coll_flag:
                for p in pos:
                    for k in range(3):
                        min_p[k] = min(min_p[k], p[k])
                        max_p[k] = max(max_p[k], p[k])
                for t in range(tc):
                    i0, i1, i2 = idxs[3 * t], idxs[3 * t + 1], idxs[3 * t + 2]
                    if mats[mat_i].get("emissiveTexture"):
                        a, b, c = pos[i0], pos[i1], pos[i2]
                        e1 = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
                        e2 = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
                        cx = e1[1] * e2[2] - e1[2] * e2[1]
                        cy = e1[2] * e2[0] - e1[0] * e2[2]
                        cz = e1[0] * e2[1] - e1[1] * e2[0]
                        glow_area_m2 += 0.5 * math.sqrt(cx * cx + cy * cy + cz * cz)
                    for a, b in ((i0, i1), (i1, i2), (i2, i0)):
                        pa, pb = pos[a], pos[b]
                        ua, ub = uvs[a], uvs[b]
                        d3 = math.sqrt(sum((pa[k] - pb[k]) ** 2 for k in range(3)))
                        duv = math.sqrt(((ua[0] - ub[0]) * W) ** 2 + ((ua[1] - ub[1]) * H) ** 2) * T
                        err = abs(d3 - duv)
                        tot_uv += 1
                        if err > max_uv_err:
                            max_uv_err = err
                        if err > 0.001:
                            bad_uv += 1
                    if mat_name == "detail_utility":
                        us = [uvs[i0][0], uvs[i1][0], uvs[i2][0]]
                        vs = [uvs[i0][1], uvs[i1][1], uvs[i2][1]]
                        eps = 1e-4
                        u_ok = (min(us) >= -eps and max(us) <= 0.5 + eps) or (min(us) >= 0.5 - eps and max(us) <= 1.0 + eps)
                        v_ok = (min(vs) >= -eps and max(vs) <= 0.5 + eps) or (min(vs) >= 0.5 - eps and max(vs) <= 1.0 + eps)
                        if not (u_ok and v_ok):
                            quad_viol += 1
        node_tris[n["name"]] = ntris
        if coll_flag:
            coll_tris += ntris
        else:
            vis_tris += ntris

    # Verify door_main walkability
    dm_x, _, dm_z = door_coords["door_main"]
    probe_x, probe_z = dm_x, dm_z + 0.566
    blocked = []
    for b in state_info["collision"]["boxes_blender_z_up"]:
        if b.get("gate_node"):
            continue
        bx, by_b, _ = b["center"]
        sx, sy_b, _ = b["size"]
        gz = -by_b
        if abs(probe_x - bx) <= sx * 0.5 + 0.05 and abs(probe_z - gz) <= sy_b * 0.5 + 0.05:
            blocked.append(b["source"])

    # Verify full-height collision on main perimeter walls (>= 4.0 m)
    wall_heights_ok = all(
        b["size"][2] >= 4.0
        for b in state_info["collision"]["boxes_blender_z_up"]
        if "wall_" in b["source"] and "interior" not in b["source"]
    )

    # Verify glow mask is mostly pure black (non-glowing pixels == (0,0,0))
    gm_w, gm_h, gm_rows = read_png_rgba(TEX_DIR / f"glow_mask_{state_info['year']}.png")
    black_px = sum(1 for row in gm_rows for r, g, b, _ in row if r == 0 and g == 0 and b == 0)
    black_ratio = black_px / (gm_w * gm_h)

    sampler_ok = all(
        s.get("magFilter") == 9728 and s.get("minFilter") in (9728, 9984)
        and s.get("wrapS", 10497) == 10497 and s.get("wrapT", 10497) == 10497
        for s in samplers
    )
    props_ok = all(node_tris.get(p, 0) < 500 for p in ("parking_lot", "rear_service", "curb_props"))
    height_m = max_p[1] - min_p[1]

    passed = (
        len(mats) <= 12
        and sampler_ok
        and req_ok
        and door_ok
        and vis_tris < 5000
        and props_ok
        and bad_uv == 0
        and quad_viol == 0
        and min_p[1] >= -1e-5
        and (3.0 <= height_m <= 12.0)
        and len(blocked) == 0
        and wall_heights_ok
        and glow_area_m2 <= 18.0
        and black_ratio >= 0.60
    )

    return {
        "year": state_info["year"],
        "file": state_info["file"],
        "passed": passed,
        "materials": len(mats),
        "visible_triangles": vis_tris,
        "collision_triangles": coll_tris,
        "lot_prop_tris": {p: node_tris.get(p, 0) for p in ("parking_lot", "rear_service", "curb_props")},
        "uv_edges_checked": tot_uv,
        "bad_uv_edges": bad_uv,
        "max_uv_error_m": round(max_uv_err, 7),
        "utility_quadrant_violations": quad_viol,
        "height_m": round(height_m, 4),
        "min_y_m": round(min_p[1], 4),
        "glow_mesh_area_m2": round(glow_area_m2, 2),
        "glow_mask_black_ratio": round(black_ratio, 3),
        "full_height_wall_collision": wall_heights_ok,
        "door_coords": door_coords,
        "door_main_blocked_by": blocked,
    }


def main():
    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))

    # Verify all textures are strict 2x2 art-pixel grids
    all_2x2 = True
    tex_report = {}
    for p in sorted(TEX_DIR.glob("*.png")):
        w, h, rows = read_png_rgba(p)
        ok, msg = check_2x2_blocks(rows, w, h)
        if not ok:
            all_2x2 = False
        tex_report[p.name] = {"size": [w, h], "2x2": ok, "note": msg}

    state_reports = [validate_one_state(st) for st in info["states"]]

    # Verify identical door_main and door_back coordinates across all 4 states
    ref_doors = state_reports[0]["door_coords"]
    doors_consistent = all(sr["door_coords"] == ref_doors for sr in state_reports)

    all_passed = all_2x2 and doors_consistent and all(sr["passed"] for sr in state_reports)
    out = {
        "model": "reel-time-video (4 states)",
        "all_passed": all_passed,
        "textures_2x2_ok": all_2x2,
        "shared_door_nodes_identical": doors_consistent,
        "states": state_reports,
    }
    (R / "validation.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(f"Validation complete: all_passed={all_passed} | shared_doors_identical={doors_consistent}")
    for sr in state_reports:
        print(
            f"  [{sr['year']}] passed={sr['passed']} | vis={sr['visible_triangles']}/5000 | "
            f"coll={sr['collision_triangles']} | bad_uv={sr['bad_uv_edges']} | "
            f"glow_area={sr['glow_mesh_area_m2']} m2 (mask black={sr['glow_mask_black_ratio']*100:.1f}%) | "
            f"h={sr['height_m']} m"
        )
    assert all_passed, f"Validation failed: {json.dumps(out, indent=2)}"


if __name__ == "__main__":
    main()
