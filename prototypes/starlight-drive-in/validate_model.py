#!/usr/bin/env python3
"""
Automated contract validator for Starlight Drive-In (`starlight-drive-in.glb` and `model-info.json`).
Verifies every rule in docs/CHATGPT_MODEL_GUIDE.md, kotlin-street/docs/LANDMARKS_GLB.md,
and LandmarkGlbTest.kt (pure Python standard library).
"""
import io
import json
import math
import struct
import zlib
from pathlib import Path

R = Path(__file__).resolve().parent
GLB_PATH = R / "starlight-drive-in.glb"
INFO_PATH = R / "model-info.json"
T = 0.0388


def decode_png_rgba_bytes(data):
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = depth = ctype = 0
    plte = []
    trns = b""
    idat = bytearray()
    while pos < len(data):
        length, ctype_bytes = struct.unpack(">I4s", data[pos : pos + 8])
        chunk = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        t = ctype_bytes.decode("latin1")
        if t == "IHDR":
            w, h, depth, ctype, _, _, _ = struct.unpack(">IIBBBBB", chunk)
        elif t == "PLTE":
            plte = [(chunk[i], chunk[i + 1], chunk[i + 2]) for i in range(0, len(chunk), 3)]
        elif t == "tRNS":
            trns = chunk
        elif t == "IDAT":
            idat.extend(chunk)
        elif t == "IEND":
            break
    raw = zlib.decompress(bytes(idat))
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bpp = max(1, (ch * depth) // 8)
    stride = (w * ch * depth + 7) // 8
    prev = bytearray(stride)
    cur = bytearray(stride)
    out = [[(0, 0, 0, 255)] * w for _ in range(h)]
    idx = 0
    for y in range(h):
        f = raw[idx]
        idx += 1
        for i in range(stride):
            x = raw[idx]
            idx += 1
            a = cur[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 0:
                v = x
            elif f == 1:
                v = (x + a) & 0xFF
            elif f == 2:
                v = (x + b) & 0xFF
            elif f == 3:
                v = (x + ((a + b) >> 1)) & 0xFF
            else:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                v = (x + pr) & 0xFF
            cur[i] = v
        for x in range(w):
            if ctype == 6:
                out[y][x] = (cur[x * 4], cur[x * 4 + 1], cur[x * 4 + 2], cur[x * 4 + 3])
            elif ctype == 2:
                out[y][x] = (cur[x * 3], cur[x * 3 + 1], cur[x * 3 + 2], 255)
            elif ctype == 3:
                per = 8 // depth
                bval = cur[x // per]
                shift = 8 - depth * (x % per + 1)
                pi = (bval >> shift) & ((1 << depth) - 1)
                r, g, b_ = plte[pi]
                al = trns[pi] if pi < len(trns) else 255
                out[y][x] = (r, g, b_, al)
        prev[:] = cur
    return w, h, out


def check_block_2x2(w, h, px):
    if w % 2 != 0 or h % 2 != 0:
        return False
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            c = px[y][x]
            if px[y][x + 1] != c or px[y + 1][x] != c or px[y + 1][x + 1] != c:
                return False
    return True


def quat_rotate_z(q):
    """Rotates local +Z vector (0, 0, 1) by quaternion [x, y, z, w]."""
    x, y, z, w = q
    out_x = 2.0 * (x * z + w * y)
    out_y = 2.0 * (y * z - w * x)
    out_z = 1.0 - 2.0 * (x * x + y * y)
    return (out_x, out_y, out_z)


def validate():
    raw = GLB_PATH.read_bytes()
    magic, ver, length = struct.unpack_from("<III", raw, 0)
    jlen, jtype = struct.unpack_from("<II", raw, 12)
    g = json.loads(raw[20 : 20 + jlen].decode("utf-8"))
    blen, btype = struct.unpack_from("<II", raw, 20 + jlen)
    bin_blob = raw[28 + jlen : 28 + jlen + blen]

    def read_acc(idx):
        a = g["accessors"][idx]
        bv = g["bufferViews"][a["bufferView"]]
        off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        count = a["count"]
        ctype = a["componentType"]
        atype = a["type"]
        ncomp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[atype]
        fmt_ch = {5126: "f", 5123: "H", 5125: "I", 5121: "B"}[ctype]
        elem_size = struct.calcsize("<" + fmt_ch * ncomp)
        stride = bv.get("byteStride", elem_size)
        out = []
        for i in range(count):
            vals = struct.unpack_from("<" + fmt_ch * ncomp, bin_blob, off + i * stride)
            out.append(vals[0] if ncomp == 1 else vals)
        return out

    # 1. Decode embedded textures & verify 2x2 art-pixel block alignment
    tex_checks = []
    img_sizes = {}
    for i, img in enumerate(g["images"]):
        bv = g["bufferViews"][img["bufferView"]]
        png_bytes = bin_blob[bv.get("byteOffset", 0) : bv.get("byteOffset", 0) + bv["byteLength"]]
        w, h, px = decode_png_rgba_bytes(png_bytes)
        b2 = check_block_2x2(w, h, px)
        img_sizes[i] = (w, h)
        tex_checks.append({
            "name": img.get("name"),
            "size": [w, h],
            "even": (w % 2 == 0 and h % 2 == 0),
            "block2x2": b2,
        })

    # Map material index -> (W, H)
    mat_sizes = {}
    for mi, m in enumerate(g["materials"]):
        ti = m["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        si = g["textures"][ti]["source"]
        mat_sizes[mi] = img_sizes[si]

    # 2. Traverse meshes for triangle counts, bounds, UV scale errors, and utility quadrant checks
    visible_tris = 0
    collision_tris = 0
    uv_errors = []
    bad_uv = 0
    utility_patch_bad = 0
    min_y = float("inf")
    max_y = float("-inf")
    node_tris = {}

    for ni, node in enumerate(g["nodes"]):
        if "mesh" not in node:
            node_tris[ni] = 0
            continue
        is_col = (
            node.get("name") == "collision"
            or (node.get("extras") or {}).get("collision_only") is True
            or (node.get("extras") or {}).get("render") is False
        )
        t_pos = node.get("translation", [0.0, 0.0, 0.0])
        m = g["meshes"][node["mesh"]]
        nt = 0
        for p in m["primitives"]:
            pos = read_acc(p["attributes"]["POSITION"])
            uv = read_acc(p["attributes"]["TEXCOORD_0"])
            idx = read_acc(p["indices"])
            nt += len(idx) // 3
            if is_col:
                collision_tris += len(idx) // 3
                continue
            visible_tris += len(idx) // 3
            W, H = mat_sizes[p["material"]]
            mname = g["materials"][p["material"]]["name"]

            for vx, vy, vz in pos:
                wy = vy + t_pos[1]
                if wy < min_y:
                    min_y = wy
                if wy > max_y:
                    max_y = wy

            for k in range(0, len(idx), 3):
                f = (idx[k], idx[k + 1], idx[k + 2])
                if mname == "detail_utility":
                    corners = [(uv[v][0] * W, uv[v][1] * H) for v in f]
                    mean_u = sum(c[0] for c in corners) / 3.0
                    mean_v = sum(c[1] for c in corners) / 3.0
                    tile_u = math.floor((mean_u + 1e-4) / 128.0) * 128.0
                    tile_v = math.floor((mean_v + 1e-4) / 128.0) * 128.0
                    if not all(
                        tile_u - 0.001 <= c[0] <= tile_u + 128.001
                        and tile_v - 0.001 <= c[1] <= tile_v + 128.001
                        for c in corners
                    ):
                        utility_patch_bad += 1

                for a, b in ((0, 1), (1, 2), (2, 0)):
                    va, vb = f[a], f[b]
                    pa, pb = pos[va], pos[vb]
                    ua, ub = uv[va], uv[vb]
                    world_len = math.sqrt(
                        (pa[0] - pb[0]) ** 2 + (pa[1] - pb[1]) ** 2 + (pa[2] - pb[2]) ** 2
                    )
                    if world_len > 1e-6:
                        uv_tex_len = math.sqrt(((ua[0] - ub[0]) * W) ** 2 + ((ua[1] - ub[1]) * H) ** 2)
                        err = abs(world_len - uv_tex_len * T)
                        uv_errors.append(err)
                        if err >= 0.001:
                            bad_uv += 1
        node_tris[ni] = nt

    def subtree_tris(ni):
        tot = node_tris.get(ni, 0)
        for ch in g["nodes"][ni].get("children", []):
            tot += subtree_tris(ch)
        return tot

    name_to_idx = {n.get("name"): i for i, n in enumerate(g["nodes"])}
    prop_counts = {}
    for pname in ("marquee_pylon", "service_yard", "speaker_posts"):
        if pname in name_to_idx:
            prop_counts[pname] = subtree_tris(name_to_idx[pname])

    # 3. Verify doors and front doorway collision clearance
    info = json.loads(INFO_PATH.read_text(encoding="utf-8"))
    doors = {}
    for dname in ("door_main", "door_back", "door_side"):
        if dname in name_to_idx:
            dn = g["nodes"][name_to_idx[dname]]
            tr = dn.get("translation", [0.0, 0.0, 0.0])
            rot = dn.get("rotation", [0.0, 0.0, 0.0, 1.0])
            out_dir = quat_rotate_z(rot)
            doors[dname] = {
                "translation": tr,
                "rotation": rot,
                "out_dir": [round(c, 4) for c in out_dir],
                "extras": dn.get("extras"),
                "is_empty_leaf": ("mesh" not in dn and not dn.get("children")),
            }

    # Check that the point 0.566 m (12 street units) in front of door_main is clear of all collision boxes
    dm = doors["door_main"]
    probe_x = dm["translation"][0] + dm["out_dir"][0] * 0.566
    probe_z = dm["translation"][2] + dm["out_dir"][2] * 0.566
    door_main_blocked = False
    for b in info["collision"]["boxes_blender_z_up"]:
        # Blender Z-up center (bx, by, bz) -> glTF (gx, gy, gz) = (bx, bz, -by)
        # Blender size (bsx, bsy, bsz) -> glTF (gsx, gsy, gsz) = (bsx, bsz, bsy)
        gx, gy, gz = b["center"][0], b["center"][2], -b["center"][1]
        gsx, gsy, gsz = b["size"][0], b["size"][2], b["size"][1]
        if (
            gx - gsx / 2.0 <= probe_x <= gx + gsx / 2.0
            and gz - gsz / 2.0 <= probe_z <= gz + gsz / 2.0
        ):
            door_main_blocked = True

    samplers_ok = all(
        s.get("magFilter") == 9728
        and s.get("minFilter") in (9728, 9984)
        and s.get("wrapS") == 10497
        and s.get("wrapT") == 10497
        for s in g["samplers"]
    )

    report = {
        "header": {
            "magic": "glTF" if magic == 0x46546C67 else str(magic),
            "version": ver,
            "byte_length": length,
        },
        "materials": {
            "count": len(g["materials"]),
            "max": 12,
            "pass": len(g["materials"]) <= 12,
        },
        "samplers": {
            "values": g["samplers"],
            "pass": samplers_ok,
        },
        "textures": {
            "images": tex_checks,
            "pass": all(t["even"] and t["block2x2"] for t in tex_checks),
        },
        "triangles": {
            "visible": visible_tris,
            "collision": collision_tris,
            "visible_max": 12000,
            "props": prop_counts,
            "pass": visible_tris <= 12000 and all(v < 500 for v in prop_counts.values()),
        },
        "uv_scale": {
            "edges_checked": len(uv_errors),
            "max_error_m": round(max(uv_errors), 7) if uv_errors else 0.0,
            "bad_edges_over_1mm": bad_uv,
            "pass": bad_uv == 0,
        },
        "utility_uv_patch_checks": {
            "bad_faces": utility_patch_bad,
            "pass": utility_patch_bad == 0,
        },
        "scale_and_bounds": {
            "min_y_m": round(min_y, 4),
            "max_y_m": round(max_y, 4),
            "ground_non_negative": min_y >= -1e-5,
            "height_in_3_to_12m": 3.0 <= max_y <= 12.0,
            "pass": (min_y >= -1e-5) and (3.0 <= max_y <= 12.0),
        },
        "doors": {
            "nodes": doors,
            "door_main_front_probe_clear": not door_main_blocked,
            "pass": (
                set(doors.keys()) == {"door_main", "door_back", "door_side"}
                and all(d["is_empty_leaf"] for d in doors.values())
                and doors["door_main"]["out_dir"][2] > 0.9
                and not door_main_blocked
            ),
        },
        "collision": {
            "boxes_count": len(info["collision"]["boxes_blender_z_up"]),
            "pass": "collision" in name_to_idx and len(info["collision"]["boxes_blender_z_up"]) > 0,
        },
    }
    report["all_passed"] = all(
        v["pass"] for k, v in report.items() if isinstance(v, dict) and "pass" in v
    )

    (R / "validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    assert report["all_passed"], "Validation failed!"


if __name__ == "__main__":
    validate()
