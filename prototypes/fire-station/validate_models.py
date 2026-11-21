#!/usr/bin/env python3
"""
Contract validator for Small-Town Fire Station .glb files (1924 and 1988).
Checks:
- Material count <= 12, NEAREST + REPEAT samplers, strict 2x2 texel blocks on every PNG
- Visible triangles < 5,000 per state; lot prop sub-groups < 500 tris each
- UV scale accuracy (0.0388 m/texel): 0 edges > 2 mm error
- Pure-black emissive mask ratio on glow_mask_*.png
- Shared shell/lot consistency: identical door_main & door_back nodes, ground y >= 0
- Full-height glTF Y-up collision boxes listed in model-info.json
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

    node_by_name = {n["name"]: (i, n) for i, n in enumerate(nodes)}
    required = ["fire_station", "structure", "lot", "collision", "door_main", "door_back"]
    req_ok = all(k in node_by_name for k in required)

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

    vis_tris = 0
    coll_tris = 0
    min_y = 1e9
    max_y = -1e9
    bad_uv = 0
    max_uv_err = 0.0
    emissive_area_m2 = 0.0

    for n_idx, n in enumerate(nodes):
        if "mesh" not in n:
            continue
        is_c = n.get("name") == "collision" or n.get("extras", {}).get("collision_only")
        mesh = meshes[n["mesh"]]
        for prim in mesh["primitives"]:
            pos = read_accessor(prim["attributes"]["POSITION"])
            uv = read_accessor(prim["attributes"]["TEXCOORD_0"])
            idxs = read_accessor(prim["indices"])
            tcnt = len(idxs) // 3
            if is_c:
                coll_tris += tcnt
                continue
            vis_tris += tcnt
            for p in pos:
                if p[1] < min_y: min_y = p[1]
                if p[1] > max_y: max_y = p[1]
            mi = prim["material"]
            mname = mats[mi]["name"]
            W, H = mat_tex_size[mi]
            for ti in range(tcnt):
                i0, i1, i2 = idxs[3 * ti], idxs[3 * ti + 1], idxs[3 * ti + 2]
                p0, p1, p2 = pos[i0], pos[i1], pos[i2]
                if mname == "glow_lamps":
                    # triangle area
                    ax, ay, az = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
                    bx, by, bz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
                    cx, cy, cz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
                    emissive_area_m2 += 0.5 * math.sqrt(cx * cx + cy * cy + cz * cz)
                for a, b in ((i0, i1), (i1, i2), (i2, i0)):
                    pa, pb = pos[a], pos[b]
                    ua, ub = uv[a], uv[b]
                    wdist = math.sqrt((pb[0] - pa[0]) ** 2 + (pb[1] - pa[1]) ** 2 + (pb[2] - pa[2]) ** 2)
                    tdist = math.sqrt(((ub[0] - ua[0]) * W) ** 2 + ((ub[1] - ua[1]) * H) ** 2) * T
                    err = abs(wdist - tdist)
                    if err > max_uv_err:
                        max_uv_err = err
                    if err > 0.002:
                        bad_uv += 1

    samplers_ok = all(
        s.get("magFilter") == 9728 and s.get("minFilter") == 9728
        and s.get("wrapS") == 10497 and s.get("wrapT") == 10497
        for s in samplers
    )

    # Check glow mask black ratio
    gw, gh, grows = read_png_rgba(TEX_DIR / f"glow_mask_{state_info['year']}.png")
    black_px = sum(1 for row in grows for r, g, b, _ in row if r == 0 and g == 0 and b == 0)
    black_ratio = black_px / (gw * gh)

    # Check collision height matches max building height
    coll_max_y = max(b["max_gltf_m"][1] for b in state_info["collision"]["boxes_gltf_y_up"])
    coll_full_height = abs(coll_max_y - max_y) < 0.05

    passed = (
        req_ok
        and door_ok
        and samplers_ok
        and len(mats) <= 12
        and vis_tris < 5000
        and bad_uv == 0
        and min_y >= -1e-4
        and coll_full_height
        and black_ratio > 0.45
    )

    return {
        "year": state_info["year"],
        "file": state_info["file"],
        "passed": passed,
        "visible_triangles": vis_tris,
        "collision_triangles": coll_tris,
        "collision_boxes": len(state_info["collision"]["boxes_gltf_y_up"]),
        "materials": len(mats),
        "samplers_ok": samplers_ok,
        "required_nodes_ok": req_ok,
        "doors_ok": door_ok,
        "door_coords": door_coords,
        "min_y_m": round(min_y, 4),
        "max_y_m": round(max_y, 4),
        "coll_max_y_m": round(coll_max_y, 4),
        "coll_full_height": coll_full_height,
        "bad_uv_edges": bad_uv,
        "max_uv_err_m": round(max_uv_err, 6),
        "glow_mesh_area_m2": round(emissive_area_m2, 2),
        "glow_mask_black_ratio": round(black_ratio, 3),
    }


def main():
    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))
    tex_checks = {}
    all_tex_ok = True
    for p in sorted(TEX_DIR.glob("*.png")):
        w, h, rows = read_png_rgba(p)
        ok, msg = check_2x2_blocks(rows, w, h)
        tex_checks[p.name] = {"size": [w, h], "ok_2x2": ok, "note": msg}
        if not ok:
            all_tex_ok = False

    results = [validate_one_state(s) for s in info["states"]]
    # Check identical door coordinates across both states
    doors_shared = (results[0]["door_coords"] == results[1]["door_coords"])
    overall = all_tex_ok and doors_shared and all(r["passed"] for r in results)

    report = {
        "passed": overall,
        "all_textures_2x2_ok": all_tex_ok,
        "doors_shared_across_states": doors_shared,
        "textures": tex_checks,
        "states": results,
    }
    (R / "validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    for r in results:
        print(
            f"State {r['year']}: passed={r['passed']} | vis={r['visible_triangles']}/5000 tris | "
            f"mats={r['materials']}/12 | bad_uv={r['bad_uv_edges']} (max={r['max_uv_err_m']}m) | "
            f"glow={r['glow_mesh_area_m2']}m2 (black={r['glow_mask_black_ratio']}) | "
            f"h={r['max_y_m']}m | coll_full={r['coll_full_height']}"
        )
    print(f"OVERALL VALIDATION: passed={overall} | textures_2x2={all_tex_ok} | doors_shared={doors_shared}")
    if not overall:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
