#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) builder for the Small-Town Fire Station in two historical states
sharing ONE lot, origin (0, 0, 0 at front-center curb/wall), street apron, and door layout:
  - fire-station-1924.glb (1924-1983, built 1924): "Engine Co. No. 2" — classic 1920s two-bay
    red-brick firehouse with arched stone trim, double red timber carriage doors, tall brick
    hose-drying tower with louvered vents & pyramidal slate roof, brass fire pole inside the
    clean apparatus bay shell, red entrance globe lamps, street call box, and fire hydrant.
  - fire-station-1988.glb (1984-2026, rebuilt 1985): "Station No. 2" — modern municipal
    two-bay split-face concrete-block fire station with wide red/glass sectional roll-up bay
    doors, cantilevered dark-metal lobby canopy, modest backlit municipal sign, setback upper
    crew quarters, rooftop HVAC & VHF radio mast, and rear backup generator.

Follows docs/CHATGPT_MODEL_GUIDE.md strictly:
- 0.0388 m/texel on 2x2 art-pixel blocks (U = 0.0776 m)
- Under 5,000 triangles per state; 11 materials (<= 12) named by role
- Shared LimeZu swatches + LimeZu-palette bespoke textures
- Black-masked emissive glow strictly on lamps, globes, and the modest 1988 sign face
- Full-height glTF Y-up collision boxes listed in model-info.json
- Lot dressing separated cleanly under the 'lot' parent node
"""
import json
import math
import struct
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
ART_MANIFEST = json.loads((R / "art-regions.json").read_text(encoding="utf-8"))

T = 0.0388       # 1 texel in metres
U = 0.0776       # 1 art pixel (2x2 texels) in metres

STATE_SPECS = [
    {
        "year": 1924,
        "id": "1924",
        "filename": "fire-station-1924.glb",
        "title": "Engine Co. No. 2 — 1920s Brick Firehouse (1924)",
        "era": {"from": 1924, "to": 1983, "built": 1924},
        "summary": (
            "Two-storey 1924 red-brick small-town firehouse with two double-timber carriage bays, "
            "carved Indiana limestone tablet ('ENGINE CO. NO. 2' / '1924'), tall NW brick hose-drying "
            "tower with louvered vents and pyramidal slate roof, clean apparatus hall shell with brass "
            "fire pole, glowing red entrance globes, fire hydrant, and street fire-alarm pull box."
        ),
    },
    {
        "year": 1988,
        "id": "1988",
        "filename": "fire-station-1988.glb",
        "title": "Fire Station No. 2 — Modern Municipal Station (1988)",
        "era": {"from": 1984, "to": 2026, "built": 1985},
        "summary": (
            "Rebuilt modern split-face concrete-block fire station sharing the same lot, apron, "
            "and door layout: wide aluminum-and-glass sectional roll-up doors, cantilevered lobby "
            "canopy with modest backlit 'STATION NO. 2' sign, setback second-storey crew wing, "
            "rooftop HVAC units, galvanized VHF radio mast, safety bollards, and rear backup generator."
        ),
    },
]


def read_png_size(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    return w, h


def get_material_defs(year):
    return [
        {"name": "wall_brick", "file": "brick_red.png", "rough": 0.92, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "wall_block", "file": "block_courses.png", "rough": 0.92, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "wall_stone", "file": "stone_trim.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "roof_gravel", "file": "ballast.png", "rough": 0.96, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "roof_slate", "file": "roof_slate.png", "rough": 0.86, "metal": 0.02, "alpha": "OPAQUE"},
        {"name": "roof_cap", "file": "roof_membrane_grey.png", "rough": 0.84, "metal": 0.08, "alpha": "OPAQUE"},
        {"name": "ground_asphalt", "file": "asphalt_worn.png", "rough": 0.95, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "ground_pavement", "file": "pavement.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "glass_windows", "file": f"glass_{year}.png", "rough": 0.16, "metal": 0.05, "alpha": "BLEND", "doubleSided": True},
        {"name": "sign_civic", "file": f"signs_{year}.png", "rough": 0.74, "metal": 0.04, "alpha": "OPAQUE"},
        {"name": "detail_firehouse", "file": f"details_{year}.png", "rough": 0.72, "metal": 0.16, "alpha": "OPAQUE"},
        {
            "name": "glow_lamps",
            "file": f"glow_base_{year}.png",
            "emissive_file": f"glow_mask_{year}.png",
            "rough": 0.38,
            "metal": 0.0,
            "alpha": "OPAQUE",
            "emissive": [1.0, 1.0, 1.0],
        },
    ]


def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def v_cross(a, b):
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def v_dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def v_len(a):
    return math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])


def v_norm(a):
    l = v_len(a)
    return (a[0] / l, a[1] / l, a[2] / l) if l > 1e-12 else (0.0, 1.0, 0.0)


class MeshBucket:
    def __init__(self, name, mat_idx, mat_size):
        self.name = name
        self.mat_idx = mat_idx
        self.mat_size = mat_size
        self.prims = {}

    def _prim(self, mat_name):
        mi = self.mat_idx[mat_name]
        if mi not in self.prims:
            self.prims[mi] = {"pos": [], "nrm": [], "uv": [], "idx": []}
        return self.prims[mi]

    def add_polygon(self, pts_art, mat_name, u_axis=None, v_axis=None, uv_rect_art=None):
        pts_m = [(p[0] * U, p[1] * U, p[2] * U) for p in pts_art]
        n = v_norm(v_cross(v_sub(pts_m[1], pts_m[0]), v_sub(pts_m[2], pts_m[0])))
        W, H = self.mat_size[mat_name]

        e_u = v_norm(v_sub(pts_m[1], pts_m[0])) if u_axis is None else v_norm(u_axis)
        e_v = v_norm(v_cross(n, e_u)) if v_axis is None else v_norm(v_axis)

        prim = self._prim(mat_name)
        base = len(prim["pos"])

        if uv_rect_art is not None:
            rx, ry, rw, rh = uv_rect_art
            us_m = [v_dot(v_sub(p, pts_m[0]), e_u) for p in pts_m]
            vs_m = [v_dot(v_sub(p, pts_m[0]), e_v) for p in pts_m]
            min_u = min(us_m)
            max_v = max(vs_m)
            u0_tex = rx * 2.0
            v0_tex = ry * 2.0
            for p, um, vm in zip(pts_m, us_m, vs_m):
                du_tex = (um - min_u) / T
                dv_tex = (max_v - vm) / T
                u = (u0_tex + du_tex) / W
                v = (v0_tex + dv_tex) / H
                prim["pos"].append(p)
                prim["nrm"].append(n)
                prim["uv"].append((u, v))
        else:
            for p in pts_m:
                um = v_dot(p, e_u)
                vm = v_dot(p, e_v)
                u = (um / T) / W
                v = (-vm / T) / H
                prim["pos"].append(p)
                prim["nrm"].append(n)
                prim["uv"].append((u, v))

        for k in range(1, len(pts_m) - 1):
            prim["idx"].extend([base, base + k, base + k + 1])

    def add_quad(self, p0, p1, p2, p3, mat_name, u_axis=None, v_axis=None, uv_rect_art=None):
        self.add_polygon([p0, p1, p2, p3], mat_name, u_axis=u_axis, v_axis=v_axis, uv_rect_art=uv_rect_art)

    def add_box(self, x0, y0, z0, x1, y1, z1, mat_name, faces=None, uv_rect_art=None, face_mats=None):
        if faces is None:
            faces = ("+z", "-z", "+x", "-x", "+y", "-y")
        face_defs = {
            "+z": ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (1, 0, 0), (0, 1, 0)),
            "-z": ([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (-1, 0, 0), (0, 1, 0)),
            "+x": ([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (0, 0, -1), (0, 1, 0)),
            "-x": ([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (0, 0, 1), (0, 1, 0)),
            "+y": ([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (1, 0, 0), (0, 0, -1)),
            "-y": ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (1, 0, 0), (0, 0, 1)),
        }
        for f in faces:
            pts, ua, va = face_defs[f]
            fm = face_mats.get(f, mat_name) if face_mats else mat_name
            rect = uv_rect_art.get(f) if isinstance(uv_rect_art, dict) else uv_rect_art
            self.add_quad(pts[0], pts[1], pts[2], pts[3], fm, u_axis=ua, v_axis=va, uv_rect_art=rect)

    def add_cylinder_y(self, cx, y0, y1, cz, radius, segs, mat_name, uv_rect_art=None, cap_top=True, cap_bot=False):
        ring_b = []
        ring_t = []
        for i in range(segs):
            a = 2.0 * math.pi * i / segs
            px = cx + math.cos(a) * radius
            pz = cz + math.sin(a) * radius
            ring_b.append((px, y0, pz))
            ring_t.append((px, y1, pz))
        for i in range(segs):
            ni = (i + 1) % segs
            # CCW outward looking from outside
            self.add_quad(ring_b[ni], ring_b[i], ring_t[i], ring_t[ni], mat_name, uv_rect_art=uv_rect_art)
        if cap_top:
            self.add_polygon(list(reversed(ring_t)), mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=uv_rect_art)
        if cap_bot:
            self.add_polygon(ring_b, mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, 1), uv_rect_art=uv_rect_art)

    def add_pyramid(self, x0, z0, x1, z1, y0, apex_x, apex_y, apex_z, mat_name):
        p00 = (x0, y0, z1)
        p10 = (x1, y0, z1)
        p11 = (x1, y0, z0)
        p01 = (x0, y0, z0)
        ap = (apex_x, apex_y, apex_z)
        self.add_polygon([p00, p10, ap], mat_name)
        self.add_polygon([p10, p11, ap], mat_name)
        self.add_polygon([p11, p01, ap], mat_name)
        self.add_polygon([p01, p00, ap], mat_name)

    def tri_count(self):
        return sum(len(p["idx"]) // 3 for p in self.prims.values())


class CollisionCollector:
    def __init__(self, bucket):
        self.bucket = bucket
        self.boxes = []

    def add(self, label, x0, y0, z0, x1, y1, z1):
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "wall_block")
        self.boxes.append({
            "label": label,
            "min_gltf_m": [round(x0 * U, 4), round(y0 * U, 4), round(z0 * U, 4)],
            "max_gltf_m": [round(x1 * U, 4), round(y1 * U, 4), round(z1 * U, 4)],
        })


def add_tiled_interior_wall(bucket, x0, y0, z0, x1, y1, z1, facing, det):
    """Tiles clean interior_wall region (64x48 art px) across a wall without exceeding region bounds."""
    rect = det["interior_wall"]  # 64 x 48
    rw, rh = rect[2], rect[3]
    if facing in ("+z", "-z"):
        span_x = abs(x1 - x0)
        span_y = abs(y1 - y0)
        nx = max(1, math.ceil(span_x / rw))
        ny = max(1, math.ceil(span_y / rh))
        for ix in range(nx):
            xa = x0 + ix * rw
            xb = min(x1, xa + rw)
            for iy in range(ny):
                ya = y0 + iy * rh
                yb = min(y1, ya + rh)
                if facing == "+z":
                    bucket.add_quad((xa, ya, z0), (xb, ya, z0), (xb, yb, z0), (xa, yb, z0), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
                else:
                    bucket.add_quad((xb, ya, z0), (xa, ya, z0), (xa, yb, z0), (xb, yb, z0), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
    else:
        span_z = abs(z1 - z0)
        span_y = abs(y1 - y0)
        nz = max(1, math.ceil(span_z / rw))
        ny = max(1, math.ceil(span_y / rh))
        for iz in range(nz):
            za = z0 + iz * rw
            zb = min(z1, za + rw)
            for iy in range(ny):
                ya = y0 + iy * rh
                yb = min(y1, ya + rh)
                if facing == "+x":
                    bucket.add_quad((x0, ya, zb), (x0, ya, za), (x0, yb, za), (x0, yb, zb), "detail_firehouse", u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=rect)
                else:
                    bucket.add_quad((x0, ya, za), (x0, ya, zb), (x0, yb, zb), (x0, yb, za), "detail_firehouse", u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=rect)


def add_tiled_cylinder_y(bucket, cx, y0, y1, cz, radius, segs, mat_name, uv_rect_art, cap_top=True):
    """Subdivides tall cylinders vertically so height never exceeds atlas region height."""
    max_h = max(4.0, uv_rect_art[3] - 2.0)
    span = y1 - y0
    steps = max(1, math.ceil(span / max_h))
    dy = span / steps
    for i in range(steps):
        ya = y0 + i * dy
        yb = y0 + (i + 1) * dy
        bucket.add_cylinder_y(cx, ya, yb, cz, radius, segs, mat_name, uv_rect_art=uv_rect_art, cap_top=(cap_top and i == steps - 1))


def add_tiled_box_x(bucket, x0, y0, z0, x1, y1, z1, mat_name, uv_rect_art, faces=("+z", "-z", "+x", "-x", "+y", "-y")):
    """Subdivides wide boxes along X so face width never exceeds atlas region width."""
    max_w = max(4.0, uv_rect_art[2] - 2.0)
    span = x1 - x0
    steps = max(1, math.ceil(span / max_w))
    dx = span / steps
    for i in range(steps):
        xa = x0 + i * dx
        xb = x0 + (i + 1) * dx
        f_list = [f for f in faces if not (f == "-x" and i > 0) and not (f == "+x" and i < steps - 1)]
        if f_list:
            bucket.add_box(xa, y0, z0, xb, y1, z1, mat_name, faces=tuple(f_list), uv_rect_art=uv_rect_art)


def add_tiled_box_z(bucket, x0, y0, z0, x1, y1, z1, mat_name, uv_rect_art, faces=("+z", "-z", "+x", "-x", "+y", "-y")):
    """Subdivides deep boxes along Z so face width never exceeds atlas region width."""
    max_w = max(4.0, uv_rect_art[2] - 2.0)
    span = z1 - z0
    steps = max(1, math.ceil(span / max_w))
    dz = span / steps
    for i in range(steps):
        za = z0 + i * dz
        zb = z0 + (i + 1) * dz
        f_list = [f for f in faces if not (f == "-z" and i > 0) and not (f == "+z" and i < steps - 1)]
        if f_list:
            bucket.add_box(x0, y0, za, x1, y1, zb, mat_name, faces=tuple(f_list), uv_rect_art=uv_rect_art)


def add_perimeter_rim(bucket, x0, y0, z0, x1, y1, z1, thick, mat_name, uv_rect_art=None):
    """Hollow 4-sided cornice or parapet coping rim leaving the recessed roof open from above."""
    if uv_rect_art is None:
        bucket.add_box(x0, y0, z1 - thick, x1, y1, z1, mat_name)
        bucket.add_box(x0, y0, z0, x1, y1, z0 + thick, mat_name)
        bucket.add_box(x0, y0, z0 + thick, x0 + thick, y1, z1 - thick, mat_name)
        bucket.add_box(x1 - thick, y0, z0 + thick, x1, y1, z1 - thick, mat_name)
    else:
        add_tiled_box_x(bucket, x0, y0, z1 - thick, x1, y1, z1, mat_name, uv_rect_art, faces=("+z", "-z", "+x", "-x", "-y"))
        add_tiled_box_x(bucket, x0, y0, z0, x1, y1, z0 + thick, mat_name, uv_rect_art, faces=("+z", "-z", "+x", "-x", "-y"))
        add_tiled_box_z(bucket, x0, y0, z0 + thick, x0 + thick, y1, z1 - thick, mat_name, uv_rect_art, faces=("+x", "-x", "-y"))
        add_tiled_box_z(bucket, x1 - thick, y0, z0 + thick, x1, y1, z1 - thick, mat_name, uv_rect_art, faces=("+x", "-x", "-y"))


def build_shared_lot(lot, parking, rear, props, coll, year, det, sgn):
    """Shared lot paving, curb, front apparatus apron, East crew parking, rear service yard, and props."""
    # Ground plane layout (all y >= 0):
    # - Main front apparatus apron & sidewalk: x in [-180, 180], z in [0, 80]
    lot.add_quad((-180, 0, 80), (180, 0, 80), (180, 0, 0), (-180, 0, 0), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    # Front curb lip at z = 76..80 (split along x where apron drops to curb)
    lot.add_box(-180, 0, 76, -112, 2.0, 80, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
    lot.add_box(112, 0, 76, 180, 2.0, 80, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))

    # West concrete walk strip: x in [-180, -112], z in [-160, 0]
    lot.add_quad((-180, 0, 0), (-112, 0, 0), (-112, 0, -160), (-180, 0, -160), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # East employee parking lot asphalt: x in [112, 180], z in [-160, 0]
    parking.add_quad((112, 0, 0), (180, 0, 0), (180, 0, -160), (112, 0, -160), "ground_asphalt", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    # Rear asphalt service yard: x in [-180, 180], z in [-248, -160]
    parking.add_quad((-180, 0, -160), (180, 0, -160), (180, 0, -248), (-180, 0, -248), "ground_asphalt", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # Concrete rear service pad behind back door
    rear.add_box(-36, 0, -184, 36, 1.0, -160, "ground_pavement", faces=("+z", "-z", "+x", "-x", "+y"))

    # Painted yellow fire-lane stripes on front apparatus apron (z = 12..68, max 56 <= 64 art px!)
    ys = det["yellow_stripe"]
    for sx in (-104, 102):
        lot.add_quad((sx, 0.35, 68), (sx + 2, 0.35, 68), (sx + 2, 0.35, 12), (sx, 0.35, 12), "detail_firehouse", u_axis=(0, 0, -1), v_axis=(1, 0, 0), uv_rect_art=ys)
    # Yellow curb apron edge bar split into 4 segments (each 56 <= 64 art px)
    for cx0 in (-112, -56, 0, 56):
        lot.add_quad((cx0, 0.35, 79), (cx0 + 56, 0.35, 79), (cx0 + 56, 0.35, 76), (cx0, 0.35, 76), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=ys)

    # 4 East-side crew parking stripes (each 44 art px wide, well under 64)
    for pz in (-20, -56, -92, -128, -156):
        parking.add_quad((126, 0.35, pz + 1.5), (174, 0.35, pz + 1.5), (174, 0.35, pz), (126, 0.35, pz), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=ys)

    # Classic red cast-iron Fire Hydrant near SW front curb (x = -132, z = 62)
    hx, hz = -132, 62
    props.add_cylinder_y(hx, 0, 11, hz, 3.0, 8, "detail_firehouse", uv_rect_art=det["red_metal"], cap_top=True)
    props.add_cylinder_y(hx, 11, 13.5, hz, 2.0, 8, "detail_firehouse", uv_rect_art=det["brass_gold"], cap_top=True)
    # Hydrant side nozzle caps
    props.add_box(hx - 4.5, 6.5, hz - 1.5, hx + 4.5, 9.0, hz + 1.5, "detail_firehouse", uv_rect_art=det["dark_metal"])
    coll.add("fire_hydrant", hx - 4, 0, hz - 4, hx + 4, 14, hz + 4)

    # Flagpole on East front sidewalk corner (x = 148, z = 54)
    fx, fz = 148, 54
    props.add_box(fx - 4, 0, fz - 4, fx + 4, 4, fz + 4, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
    add_tiled_cylinder_y(props, fx, 4, 124, fz, 1.1, 6, "detail_firehouse", det["dark_metal"], cap_top=False)
    props.add_cylinder_y(fx, 124, 127, fz, 1.8, 6, "detail_firehouse", uv_rect_art=det["brass_gold"], cap_top=True)
    props.add_quad((fx + 1.2, 102, fz), (fx + 33.2, 102, fz), (fx + 33.2, 122, fz), (fx + 1.2, 122, fz), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["fire_flag"])
    props.add_quad((fx + 33.2, 102, fz - 0.1), (fx + 1.2, 102, fz - 0.1), (fx + 1.2, 122, fz - 0.1), (fx + 33.2, 122, fz - 0.1), "sign_civic", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["fire_flag"])
    coll.add("flagpole", fx - 4, 0, fz - 4, fx + 4, 127, fz + 4)

    # Rear green municipal dumpster on concrete pad (NW rear yard: x = -158..-122, z = -234..-206)
    rear.add_box(-162, 0, -238, -118, 1.0, -202, "ground_pavement", faces=("+z", "-z", "+x", "-x", "+y"))
    rear.add_box(-158, 1, -232, -122, 20, -206, "detail_firehouse", uv_rect_art=det["dumpster_green"])
    add_tiled_box_x(rear, -159, 20, -233, -121, 22, -205, "detail_firehouse", det["dark_metal"])
    coll.add("rear_dumpster", -159, 0, -233, -121, 22, -205)

    # Rear perimeter low wheel-stops / fence posts along back boundary (z = -246)
    for bx in range(-164, 170, 30):
        rear.add_box(bx - 1.5, 0, -247, bx + 1.5, 16, -244, "detail_firehouse", uv_rect_art=det["dark_metal"])
    add_tiled_box_x(rear, -164, 13, -246.5, 164, 15, -244.5, "detail_firehouse", det["dark_metal"])
    add_tiled_box_x(rear, -164, 6, -246.5, 164, 8, -244.5, "detail_firehouse", det["dark_metal"])
    coll.add("rear_fence", -166, 0, -248, 166, 16, -244)

    if year == 1924:
        # Classic red street Fire-Alarm Pull Box on iron post near SE sidewalk (x = 128, z = 62)
        cx, cz = 128, 62
        props.add_cylinder_y(cx, 0, 16, cz, 1.4, 6, "detail_firehouse", uv_rect_art=det["dark_metal"], cap_top=True)
        props.add_box(cx - 3.5, 15, cz - 3.0, cx + 3.5, 25, cz + 3.0, "detail_firehouse", uv_rect_art=det["red_metal"])
        props.add_box(cx - 2.0, 25, cz - 2.0, cx + 2.0, 27, cz + 2.0, "detail_firehouse", uv_rect_art=det["brass_gold"])
        coll.add("fire_alarm_callbox", cx - 4, 0, cz - 4, cx + 4, 27, cz + 4)

        # Wooden bench beside West walkway
        props.add_box(-156, 5, -52, -132, 7.5, -44, "detail_firehouse", uv_rect_art=det["wood_trim"])
        props.add_box(-156, 7.5, -52, -132, 13, -50, "detail_firehouse", uv_rect_art=det["wood_trim"])
        for lx in (-154, -136):
            props.add_box(lx, 0, -51, lx + 2, 5, -45, "detail_firehouse", uv_rect_art=det["dark_metal"])
        coll.add("side_bench", -156, 0, -52, -132, 13, -44)

        # Rear timber hose-washing drying rack in service yard
        for rx in (54, 102):
            rear.add_box(rx, 0, -228, rx + 3, 14, -225, "detail_firehouse", uv_rect_art=det["wood_trim"])
            rear.add_box(rx, 0, -214, rx + 3, 11, -211, "detail_firehouse", uv_rect_art=det["wood_trim"])
        for sx in (54, 78):
            rear.add_box(sx, 11, -227, sx + 26, 13.5, -212, "detail_firehouse", uv_rect_art=det["wood_trim"])
        coll.add("rear_hose_rack", 54, 0, -228, 105, 14, -211)
    else:
        # 1988 modern yellow steel safety bollards flanking the two wide apparatus bays
        for bx in (-104, -28, 28, 104):
            props.add_cylinder_y(bx, 0, 14, 6, 1.8, 8, "detail_firehouse", uv_rect_art=det["yellow_stripe"], cap_top=True)
            coll.add(f"bollard_{bx}", bx - 2, 0, 4, bx + 2, 14, 8)

        # 1988 rear diesel backup generator enclosure on concrete pad (SE rear yard: x = 48..108, z = -232..-198)
        rear.add_box(44, 0, -236, 112, 2, -194, "ground_pavement", faces=("+z", "-z", "+x", "-x", "+y"))
        rear.add_box(50, 2, -230, 106, 26, -200, "detail_firehouse", uv_rect_art=det["dumpster_green"])
        # Louvered intake grilles on front/back of generator
        rear.add_quad((60, 8, -199.7), (96, 8, -199.7), (96, 22, -199.7), (60, 22, -199.7), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["louver_vent"])
        rear.add_quad((96, 8, -230.3), (60, 8, -230.3), (60, 22, -230.3), (96, 22, -230.3), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["louver_vent"])
        # Exhaust muffler stack on top
        rear.add_cylinder_y(100, 26, 38, -215, 1.6, 6, "detail_firehouse", uv_rect_art=det["dark_metal"], cap_top=True)
        coll.add("backup_generator", 48, 0, -232, 108, 38, -198)


def build_1924_structure(struct_b, coll, det, sgn, glw):
    """1920s two-bay red-brick firehouse ('Engine Co. No. 2') with NW hose-drying tower."""
    # Main two-storey brick shell: x in [-112, 112] (17.38m), z in [-160, 0] (12.42m), y in [0, 106] (8.23m)
    # 1) Ground stone plinth base (y = 0..8) wrapping sides & rear + front piers
    struct_b.add_box(-112, 0, -160, 112, 8, -158, "wall_stone", faces=("-z", "+x", "-x"))
    struct_b.add_box(-112, 0, -160, -110, 8, 0, "wall_stone", faces=("-x",))
    struct_b.add_box(110, 0, -160, 112, 8, 0, "wall_stone", faces=("+x",))
    for px0, px1 in [(-112, -84), (-26, -10), (10, 26), (84, 112)]:
        struct_b.add_box(px0, 0, -4, px1, 8, 0, "wall_stone", faces=("+z", "+x", "-x"))

    # 2) Main red brick walls (y = 8..106) with recessed front bays & entry
    # Back wall (-z = -160), Left wall (-x = -112), Right wall (+x = +112)
    struct_b.add_box(-112, 8, -160, 112, 106, -154, "wall_brick", faces=("-z", "+x", "-x"))
    struct_b.add_box(-112, 8, -154, -106, 106, 0, "wall_brick", faces=("-x",))
    struct_b.add_box(106, 8, -154, 112, 106, 0, "wall_brick", faces=("+x",))

    # Front wall (+z = 0):
    # - 4 ground-floor brick piers between & flanking the two apparatus bays & center door:
    #   Left outer pier: [-112, -84], Left bay opening: [-84, -28] (56 wide x 50 tall),
    #   Center-left pier: [-28, -9], Center entry door: [-9, +9] (18 wide x 32 tall),
    #   Center-right pier: [+9, +28], Right bay opening: [+28, +84] (56 wide x 50 tall),
    #   Right outer pier: [+84, +112]
    for px0, px1 in [(-112, -84), (-28, -9), (9, 28), (84, 112)]:
        struct_b.add_box(px0, 8, -6, px1, 56, 0, "wall_brick", faces=("+z", "+x", "-x"))
    # Brick spandrel above center entry door (y = 34..56)
    struct_b.add_box(-9, 34, -6, 9, 56, 0, "wall_brick", faces=("+z", "-y"))
    # Stone lintels over both apparatus bays and center pedestrian door
    struct_b.add_box(-86, 50, -6, -26, 56, 1.2, "wall_stone", faces=("+z", "+x", "-x", "+y", "-y"))
    struct_b.add_box(26, 50, -6, 86, 56, 1.2, "wall_stone", faces=("+z", "+x", "-x", "+y", "-y"))
    struct_b.add_box(-12, 32, -6, 12, 36, 1.2, "wall_stone", faces=("+z", "+x", "-x", "+y", "-y"))
    # Stone doorstep under main entry door
    struct_b.add_box(-12, 0, 0, 12, 2.0, 5, "wall_stone", faces=("+z", "+x", "-x", "+y"))

    # Upper storey front wall (y = 56..106)
    struct_b.add_box(-112, 56, -6, 112, 106, 0, "wall_brick", faces=("+z", "+x", "-x"))

    # Horizontal carved stone belt course between 1st & 2nd floor (y = 56..60) wrapping all 4 sides
    struct_b.add_box(-113.5, 56, -161.5, 113.5, 60, 1.5, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y", "-y"))

    # Classical corner stone quoins on front corners (subtle relief blocks)
    for qy in (12, 28, 44, 66, 82, 96):
        struct_b.add_box(-113, qy, -6, -106, qy + 7, 0.8, "wall_stone", faces=("+z", "-x", "+y", "-y"))
        struct_b.add_box(106, qy, -6, 113, qy + 7, 0.8, "wall_stone", faces=("+z", "+x", "+y", "-y"))

    # Stepped brick roof parapet & central civic pediment (y = 106..126)
    # Hollow 4-sided stone cornice band at y = 104..108 so recessed tar-and-gravel roof shows from above!
    add_perimeter_rim(struct_b, -114, 104, -162, 114, 108, 2.5, 8.5, "wall_stone")
    # Side & rear parapets (y = 108..114)
    struct_b.add_box(-112, 108, -160, 112, 114, -154, "wall_brick", faces=("+z", "-z", "+x", "-x"))
    struct_b.add_box(-112, 108, -154, -106, 114, 0, "wall_brick", faces=("+x", "-x"))
    struct_b.add_box(106, 108, -154, 112, 114, 0, "wall_brick", faces=("+x", "-x"))
    struct_b.add_box(-112, 108, -6, 112, 114, 0, "wall_brick", faces=("+z", "-z"))
    # Stepped central pediment rising above the main cornice (x in [-72, +72], y = 114..128)
    struct_b.add_box(-72, 114, -6, 72, 126, 0, "wall_brick", faces=("+z", "-z", "+x", "-x"))
    struct_b.add_box(-30, 126, -6, 30, 140, 0, "wall_brick", faces=("+z", "-z", "+x", "-x"))
    # Stone coping caps on all parapets and stepped pediment
    struct_b.add_box(-113, 114, -161, 113, 116, -153, "wall_stone")
    struct_b.add_box(-113, 114, -153, -105, 116, 1, "wall_stone")
    struct_b.add_box(105, 114, -153, 113, 116, 1, "wall_stone")
    struct_b.add_box(-113, 114, -7, -72, 116, 1, "wall_stone")
    struct_b.add_box(72, 114, -7, 113, 116, 1, "wall_stone")
    struct_b.add_box(-73, 126, -7, -30, 128, 1, "wall_stone")
    struct_b.add_box(30, 126, -7, 73, 128, 1, "wall_stone")
    struct_b.add_box(-31, 140, -7, 31, 142.5, 1.5, "wall_stone")

    # Main flat tar-and-gravel roof deck (y = 104)
    struct_b.add_quad((-106, 104, -6), (106, 104, -6), (106, 104, -154), (-106, 104, -154), "roof_gravel", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    # Rooftop brick chimney flue (NE roof) & roof hatch + 2 cast-iron rotary ventilators
    struct_b.add_box(72, 104, -142, 88, 134, -126, "wall_brick")
    struct_b.add_box(71, 134, -143, 89, 136.5, -125, "wall_stone")
    struct_b.add_box(16, 104, -110, 38, 110, -88, "roof_cap")
    for vx in (-16, 48):
        struct_b.add_cylinder_y(vx, 104, 113, -72, 4.0, 8, "detail_firehouse", uv_rect_art=det["dark_metal"], cap_top=True)

    # 3) ICONIC HOSE-DRYING TOWER (NW corner: x in [-112, -56], z in [-160, -104], rising y = 104..186 + slate roof to 214 = 16.6 m!)
    tx0, tx1, tz0, tz1 = -112, -56, -160, -104
    struct_b.add_box(tx0, 104, tz0, tx1, 184, tz1, "wall_brick", faces=("+z", "-z", "+x", "-x"))
    # Stone belt courses on tower
    struct_b.add_box(tx0 - 1.5, 138, tz0 - 1.5, tx1 + 1.5, 141, tz1 + 1.5, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y", "-y"))
    struct_b.add_box(tx0 - 2.0, 180, tz0 - 2.0, tx1 + 2.0, 185, tz1 + 2.0, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y", "-y"))
    # Tall louvered hose-drying vents on all 4 upper tower faces (22 x 34 art px inside 40x40 louver_vent)
    lv = det["louver_vent"]
    # +z face
    struct_b.add_box(-96, 143, tz1, -72, 178, tz1 + 1.2, "wall_stone", faces=("+x", "-x", "+y", "-y"))
    struct_b.add_quad((-95, 144, tz1 + 0.8), (-73, 144, tz1 + 0.8), (-73, 177, tz1 + 0.8), (-95, 177, tz1 + 0.8), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=lv)
    # -z face
    struct_b.add_quad((-73, 144, tz0 - 0.8), (-95, 144, tz0 - 0.8), (-95, 177, tz0 - 0.8), (-73, 177, tz0 - 0.8), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=lv)
    # -x face
    struct_b.add_quad((tx0 - 0.8, 144, -143), (tx0 - 0.8, 144, -121), (tx0 - 0.8, 177, -121), (tx0 - 0.8, 177, -143), "detail_firehouse", u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=lv)
    # +x face
    struct_b.add_quad((tx1 + 0.8, 144, -121), (tx1 + 0.8, 144, -143), (tx1 + 0.8, 177, -143), (tx1 + 0.8, 177, -121), "detail_firehouse", u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=lv)
    # Pyramidal slate hipped roof + brass weathervane finial
    struct_b.add_pyramid(tx0 - 3, tz0 - 3, tx1 + 3, tz1 + 3, 185, -84, 212, -132, "roof_slate")
    struct_b.add_cylinder_y(-84, 211, 222, -132, 1.0, 6, "detail_firehouse", uv_rect_art=det["brass_gold"], cap_top=True)

    # 4) TWO RECESSED DOUBLE-TIMBER APPARATUS BAY DOORS + PEDESTRIAN ENTRY + BACK DOOR
    bd = det["bay_door"]
    # Left Bay [-84, -28], Right Bay [28, 84] — recessed slightly at z = -3.0
    for bx0, bx1 in [(-84, -28), (28, 84)]:
        struct_b.add_quad((bx0, 1, -3.0), (bx1, 1, -3.0), (bx1, 50, -3.0), (bx0, 50, -3.0), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=bd)
        # Inner reveals framing the recessed bay
        struct_b.add_quad((bx0, 0, 0), (bx0, 0, -3.0), (bx0, 50, -3.0), (bx0, 50, 0), "wall_stone")
        struct_b.add_quad((bx1, 0, -3.0), (bx1, 0, 0), (bx1, 50, 0), (bx1, 50, -3.0), "wall_stone")
        # Glass transom overlay over the upper bay door windows so bay windows glow softly at night!
        struct_b.add_quad((bx0 + 3, 33, -2.5), (bx1 - 3, 33, -2.5), (bx1 - 3, 46, -2.5), (bx0 + 3, 46, -2.5), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))

    # Center pedestrian entry door [-9, 9], y = 2..32 at z = -3.0
    struct_b.add_quad((-9, 2, -3.0), (9, 2, -3.0), (9, 32, -3.0), (-9, 32, -3.0), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["entry_door"])

    # Rear steel service door at door_back (x in [-9, 9], y = 1..31, split cleanly into 2 panels <= 24 art px tall)
    struct_b.add_box(-11, 31, -161.2, 11, 34, -160, "wall_stone")
    struct_b.add_quad((9, 1, -160.5), (-9, 1, -160.5), (-9, 16, -160.5), (9, 16, -160.5), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["dark_metal"])
    struct_b.add_quad((9, 16, -160.5), (-9, 16, -160.5), (-9, 31, -160.5), (9, 31, -160.5), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["dark_metal"])

    # 5) SECOND-FLOOR & SIDE/REAR DOUBLE-HUNG WINDOWS (with stone sills & lintels!)
    def add_window(x0, y0, z, w, h, facing):
        if facing == "+z":
            struct_b.add_box(x0 - 1, y0 - 2.5, z, x0 + w + 1, y0, z + 1.8, "wall_stone")
            struct_b.add_box(x0 - 1, y0 + h, z, x0 + w + 1, y0 + h + 3, z + 1.4, "wall_stone")
            struct_b.add_quad((x0, y0, z + 0.6), (x0 + w, y0, z + 0.6), (x0 + w, y0 + h, z + 0.6), (x0, y0 + h, z + 0.6), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
        elif facing == "-z":
            struct_b.add_box(x0 - 1, y0 - 2.5, z - 1.8, x0 + w + 1, y0, z, "wall_stone")
            struct_b.add_box(x0 - 1, y0 + h, z - 1.4, x0 + w + 1, y0 + h + 3, z, "wall_stone")
            struct_b.add_quad((x0 + w, y0, z - 0.6), (x0, y0, z - 0.6), (x0, y0 + h, z - 0.6), (x0 + w, y0 + h, z - 0.6), "glass_windows", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))
        elif facing == "+x":
            struct_b.add_box(z, y0 - 2.5, x0 - 1, z + 1.8, y0, x0 + w + 1, "wall_stone")
            struct_b.add_box(z, y0 + h, x0 - 1, z + 1.4, y0 + h + 3, x0 + w + 1, "wall_stone")
            struct_b.add_quad((z + 0.6, y0, x0 + w), (z + 0.6, y0, x0), (z + 0.6, y0 + h, x0), (z + 0.6, y0 + h, x0 + w), "glass_windows", u_axis=(0, 0, -1), v_axis=(0, 1, 0))
        elif facing == "-x":
            struct_b.add_box(z - 1.8, y0 - 2.5, x0 - 1, z, y0, x0 + w + 1, "wall_stone")
            struct_b.add_box(z - 1.4, y0 + h, x0 - 1, z, y0 + h + 3, x0 + w + 1, "wall_stone")
            struct_b.add_quad((z - 0.6, y0, x0), (z - 0.6, y0, x0 + w), (z - 0.6, y0 + h, x0 + w), (z - 0.6, y0 + h, x0), "glass_windows", u_axis=(0, 0, 1), v_axis=(0, 1, 0))

    # 5 classic front 2nd-floor bunkroom windows
    for wx in (-94, -54, -10, 34, 74):
        add_window(wx, 68, 0.0, 20, 28, "+z")
    # 2 ground-floor outer watch-room windows on front corners
    for wx in (-104, 90):
        add_window(wx, 18, 0.0, 14, 24, "+z")
    # East & West side windows (both floors)
    for wz in (-34, -84, -134):
        add_window(wz, 20, 112.0, 18, 24, "+x")
        add_window(wz, 68, 112.0, 18, 28, "+x")
        add_window(wz, 20, -112.0, 18, 24, "-x")
        if wz > -110:  # clear of hose tower on West side
            add_window(wz, 68, -112.0, 18, 28, "-x")
    # Rear windows (both floors)
    for wx in (-86, -42, 24, 68):
        add_window(wx, 68, -160.0, 18, 26, "-z")
    for wx in (-86, 68):
        add_window(wx, 20, -160.0, 18, 24, "-z")

    # 6) RESTRAINED 1924 CIVIC STONE SIGNAGE (NO NEON!)
    # Carved limestone main tablet ('ENGINE CO. NO. 2', 128 x 20 art px) set into stepped pediment
    struct_b.add_box(-65, 108, 0, 65, 128, 2.2, "wall_stone", faces=("+x", "-x", "+y", "-y"))
    struct_b.add_quad((-64, 108, 2.2), (64, 108, 2.2), (64, 128, 2.2), (-64, 128, 2.2), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["main_tablet"])
    # Carved date stone ('1924', 44 x 16 art px) in upper pediment peak
    struct_b.add_quad((-22, 126, 0.8), (22, 126, 0.8), (22, 142, 0.8), (-22, 142, 0.8), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["date_stone"])
    # Modest bay plates ('BAY 1' / 'BAY 2', 36x12 art px) centered cleanly on the lintel above each carriage bay
    struct_b.add_quad((-74, 49, 1.6), (-38, 49, 1.6), (-38, 61, 1.6), (-74, 61, 1.6), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["bay1_plate"])
    struct_b.add_quad((38, 49, 1.6), (74, 49, 1.6), (74, 61, 1.6), (38, 61, 1.6), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["bay2_plate"])

    # 7) GLOWING RED FIREHOUSE GLOBE LANTERNS (flanking center entrance + warm rear sconce)
    for lx in (-16, 16):
        struct_b.add_box(lx - 1.5, 24, 0, lx + 1.5, 27, 4, "detail_firehouse", uv_rect_art=det["dark_metal"])
        struct_b.add_box(lx - 2.2, 27, 1.5, lx + 2.2, 32.5, 5.5, "glow_lamps", uv_rect_art=glw["red_globe"])
    # Small warm bulb above rear service door
    struct_b.add_box(-2.5, 35, -163, 2.5, 39, -160, "glow_lamps", uv_rect_art=glw["warm_bulb"])

    # 8) CLEAN INTERIOR APPARATUS BAY SHELL (concrete floor, tiled walls, ceiling, brass fire pole!)
    struct_b.add_quad((-106, 1.0, -6), (106, 1.0, -6), (106, 1.0, -154), (-106, 1.0, -154), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    struct_b.add_quad((-106, 54, -154), (106, 54, -154), (106, 54, -6), (-106, 54, -6), "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 0, 1))
    add_tiled_interior_wall(struct_b, -106, 1, -154, 106, 54, -154, "+z", det)
    add_tiled_interior_wall(struct_b, -106, 1, -154, -106, 54, -6, "+x", det)

    # Rear electrical/meter boxes on West rear exterior wall
    struct_b.add_box(-114, 14, -148, -112, 26, -134, "detail_firehouse", uv_rect_art=det["dark_metal"])
    struct_b.add_box(-114, 14, -128, -112, 22, -120, "detail_firehouse", uv_rect_art=det["dark_metal"])

    # Brass fire pole inside apparatus hall beside center entrance (x = 18, z = -34)
    add_tiled_cylinder_y(struct_b, 18, 1.0, 54, -34, 1.0, 8, "detail_firehouse", det["brass_gold"], cap_top=False)
    struct_b.add_cylinder_y(18, 1.0, 2.5, -34, 3.0, 8, "detail_firehouse", uv_rect_art=det["dark_metal"], cap_top=True)

    # Full-height collision boxes for 1924 structure
    coll.add("firehouse_main_body", -112, 0, -160, 112, 116, 0)
    coll.add("firehouse_pediment", -72, 116, -6, 72, 142.5, 1.5)
    coll.add("hose_drying_tower", -112, 0, -160, -56, 222, -104)
    coll.add("chimney_flue", 71, 104, -143, 89, 136.5, -125)


def build_1988_structure(struct_b, coll, det, sgn, glw):
    """1988 modern split-face block municipal fire station ('Station No. 2') on the exact same lot & doors."""
    # 1) Ground-floor wide two-bay apparatus & lobby block: x in [-112, 112], z in [-160, 0], y in [0, 74] (5.74 m)
    # Concrete base plinth (y = 0..6)
    struct_b.add_box(-112, 0, -160, 112, 6, -154, "wall_stone", faces=("-z", "+x", "-x"))
    struct_b.add_box(-112, 0, -154, -106, 6, 0, "wall_stone", faces=("-x",))
    struct_b.add_box(106, 0, -154, 112, 6, 0, "wall_stone", faces=("+x",))

    # Main split-face concrete block walls (y = 6..74)
    struct_b.add_box(-112, 6, -160, 112, 74, -154, "wall_block", faces=("-z", "+x", "-x"))
    struct_b.add_box(-112, 6, -154, -106, 74, 0, "wall_block", faces=("-x",))
    struct_b.add_box(106, 6, -154, 112, 74, 0, "wall_block", faces=("+x",))

    # Front piers flanking the two EXTRA-WIDE modern roll-up bays [-100, -36] (64 wide x 54 tall!) and [+36, +100]
    # and the central glazed lobby vestibule [-36, +36]
    for px0, px1 in [(-112, -100), (-36, -24), (24, 36), (100, 112)]:
        struct_b.add_box(px0, 0, -6, px1, 74, 0, "wall_block", faces=("+z", "+x", "-x"))
    # Upper front fascia wall above apparatus bays (y = 56..74)
    struct_b.add_box(-100, 56, -6, -36, 74, 0, "wall_block", faces=("+z", "-y"))
    struct_b.add_box(36, 56, -6, 100, 74, 0, "wall_block", faces=("+z", "-y"))
    # Lobby upper wall above glass vestibule (y = 42..74)
    struct_b.add_box(-24, 42, -6, 24, 74, 0, "wall_block", faces=("+z", "-y"))

    # Crisp modern safety-red metal fascia accent band wrapping all 4 sides at y = 68..74 (strictly tiled <= 30 art px!)
    add_perimeter_rim(struct_b, -113.5, 68, -161.5, 113.5, 74, 1.5, 4.0, "detail_firehouse", uv_rect_art=det["red_metal"])
    add_perimeter_rim(struct_b, -114, 74, -162, 114, 76.5, 2.0, 6.0, "roof_cap")

    # Main 1st-floor flat gravel roof deck (y = 73) — open and visible inside hollow parapet rim!
    struct_b.add_quad((-108, 73, -4), (108, 73, -4), (108, 73, -156), (-108, 73, -156), "roof_gravel", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # 2) SETBACK SECOND-STOREY CREW QUARTERS & DISPATCH WING (x in [-64, 64], z in [-150, -34], y = 73..114 = 8.85 m)
    struct_b.add_box(-64, 73, -150, 64, 114, -34, "wall_block", faces=("+z", "-z", "+x", "-x"))
    add_perimeter_rim(struct_b, -65.5, 108, -151.5, 65.5, 113, -32.5, 4.0, "detail_firehouse", uv_rect_art=det["red_metal"])
    add_perimeter_rim(struct_b, -66, 113, -152, 66, 115.5, -32, 6.0, "roof_cap")
    struct_b.add_quad((-60, 113, -38), (60, 113, -38), (60, 113, -146), (-60, 113, -146), "roof_gravel", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # Continuous horizontal ribbon windows on 2nd-storey crew wing
    struct_b.add_quad((-54, 84, -33.3), (54, 84, -33.3), (54, 102, -33.3), (-54, 102, -33.3), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
    struct_b.add_quad((54, 84, -150.7), (-54, 84, -150.7), (-54, 102, -150.7), (54, 102, -150.7), "glass_windows", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))
    struct_b.add_quad((64.7, 84, -50), (64.7, 84, -134), (64.7, 102, -134), (64.7, 102, -50), "glass_windows", u_axis=(0, 0, -1), v_axis=(0, 1, 0))
    struct_b.add_quad((-64.7, 84, -134), (-64.7, 84, -50), (-64.7, 102, -50), (-64.7, 102, -134), "glass_windows", u_axis=(0, 0, 1), v_axis=(0, 1, 0))

    # 3) SLEEK CANTILEVERED FRONT LOBBY CANOPY (x in [-34, 34], z in [0, 20], y = 38..44)
    struct_b.add_box(-34, 38, 0, 34, 43, 20, "roof_cap")
    add_tiled_box_x(struct_b, -34.5, 41, 18.5, 34.5, 44, 20.5, "detail_firehouse", det["red_metal"], faces=("+z", "+x", "-x"))
    add_tiled_box_z(struct_b, -34.5, 41, 0, -32.5, 44, 18.5, "detail_firehouse", det["red_metal"], faces=("-x",))
    add_tiled_box_z(struct_b, 32.5, 41, 0, 34.5, 44, 18.5, "detail_firehouse", det["red_metal"], faces=("+x",))
    # Two slender steel canopy columns flanking the sidewalk entrance (tiled vertically <= 20 art px!)
    for cx in (-30, 30):
        struct_b.add_box(cx - 1.5, 0, 16, cx + 1.5, 19, 19, "detail_firehouse", uv_rect_art=det["dark_metal"])
        struct_b.add_box(cx - 1.5, 19, 16, cx + 1.5, 38, 19, "detail_firehouse", uv_rect_art=det["dark_metal"])
        coll.add(f"canopy_col_{cx}", cx - 2, 0, 15, cx + 2, 38, 20)

    # Glazed lobby storefront flanking center entry door (x in [-24, -9] & [9, 24], y = 4..38)
    struct_b.add_box(-24, 0, -5, -9, 6, 0, "wall_stone")
    struct_b.add_box(9, 0, -5, 24, 6, 0, "wall_stone")
    struct_b.add_quad((-24, 6, -1.5), (-9, 6, -1.5), (-9, 38, -1.5), (-24, 38, -1.5), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
    struct_b.add_quad((9, 6, -1.5), (24, 6, -1.5), (24, 38, -1.5), (9, 38, -1.5), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
    # Modern aluminum-and-glass main door at center [-9, 9], y = 1..33
    struct_b.add_quad((-9, 1, -2.0), (9, 1, -2.0), (9, 33, -2.0), (-9, 33, -2.0), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["entry_door"])
    struct_b.add_quad((-9, 33, -2.0), (9, 33, -2.0), (9, 38, -2.0), (-9, 38, -2.0), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))

    # 4) TWO WIDE MODERN SECTIONAL ROLL-UP APPARATUS DOORS ([-100, -36] & [36, 100], 64 x 54 art px!)
    bd = det["bay_door"]
    for bx0, bx1 in [(-100, -36), (36, 100)]:
        struct_b.add_quad((bx0, 1, -3.0), (bx1, 1, -3.0), (bx1, 55, -3.0), (bx0, 55, -3.0), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=bd)
        struct_b.add_quad((bx0, 0, 0), (bx0, 0, -3.0), (bx0, 56, -3.0), (bx0, 56, 0), "roof_cap")
        struct_b.add_quad((bx1, 0, -3.0), (bx1, 0, 0), (bx1, 56, 0), (bx1, 56, -3.0), "roof_cap")
        # Glowing middle vision-glass band overlay matching the sectional glass rows!
        struct_b.add_quad((bx0 + 3, 24, -2.4), (bx1 - 3, 24, -2.4), (bx1 - 3, 44, -2.4), (bx0 + 3, 44, -2.4), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))

    # Ground-floor side office windows & rear steel service door + rear drive-through exhaust louvers
    for wz in (-38, -94):
        struct_b.add_quad((112.6, 20, wz + 24), (112.6, 20, wz), (112.6, 44, wz), (112.6, 44, wz + 24), "glass_windows", u_axis=(0, 0, -1), v_axis=(0, 1, 0))
        struct_b.add_quad((-112.6, 20, wz), (-112.6, 20, wz + 24), (-112.6, 44, wz + 24), (-112.6, 44, wz), "glass_windows", u_axis=(0, 0, 1), v_axis=(0, 1, 0))
    # Rear steel back door (exact same door_back x=0, z=-160, split into 2 panels <= 24 art px tall!)
    struct_b.add_quad((9, 1, -160.5), (-9, 1, -160.5), (-9, 16, -160.5), (9, 16, -160.5), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["dark_metal"])
    struct_b.add_quad((9, 16, -160.5), (-9, 16, -160.5), (-9, 31, -160.5), (9, 31, -160.5), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["dark_metal"])
    # Rear apparatus bay exhaust louvers
    for rx0 in (-82, 46):
        struct_b.add_quad((rx0 + 36, 28, -160.6), (rx0, 28, -160.6), (rx0, 52, -160.6), (rx0 + 36, 52, -160.6), "detail_firehouse", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["louver_vent"])

    # 5) RESTRAINED MODERN SIGNAGE & NIGHT GLOW (ZERO OVERLAP!)
    # One modest backlit 'STATION NO. 2' box sign centered at x in [-44, +44], y = 48..64 (88 x 16 art px)
    add_tiled_box_x(struct_b, -44, 48, 0, 44, 64, 3.5, "detail_firehouse", det["dark_metal"], faces=("+x", "-x", "+y", "-y"))
    struct_b.add_quad((-44, 48, 3.5), (44, 48, 3.5), (44, 64, 3.5), (-44, 64, 3.5), "glow_lamps", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=glw["backlit_sign"])
    # Modest red bay plates ('BAY 1' / 'BAY 2', 36 x 12 art px) cleanly separated at x in [-92, -56] & [56, 92]!
    struct_b.add_quad((-92, 55, 0.8), (-56, 55, 0.8), (-56, 67, 0.8), (-92, 67, 0.8), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["bay1_plate"])
    struct_b.add_quad((56, 55, 0.8), (92, 55, 0.8), (92, 67, 0.8), (56, 67, 0.8), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["bay2_plate"])
    # Small red flashing-style warning beacons on outer bay piers + canopy soffit bulbs + rear sconce
    for lx in (-106, 106):
        struct_b.add_box(lx - 2.0, 52, 0, lx + 2.0, 56.5, 4.0, "glow_lamps", uv_rect_art=glw["red_globe"])
    for lx in (-16, 16):
        struct_b.add_box(lx - 2.0, 36.5, 8, lx + 2.0, 38.0, 12, "glow_lamps", uv_rect_art=glw["warm_bulb"])
    struct_b.add_box(-2.5, 34, -163, 2.5, 38, -160, "glow_lamps", uv_rect_art=glw["warm_bulb"])

    # 6) ROOFTOP HVAC UNITS & GALVANIZED MUNICIPAL VHF RADIO MAST (modern counterpart to 1924 hose tower!)
    # Twin HVAC packaged units on rear roof (max 24 x 28 -> tile along Z <= 28 inside 32x24 dark_metal!)
    for hx0 in (-96, 72):
        struct_b.add_box(hx0, 73, -138, hx0 + 24, 87, -116, "detail_firehouse", uv_rect_art=det["dark_metal"])
        struct_b.add_quad((hx0 + 2, 76, -115.6), (hx0 + 22, 76, -115.6), (hx0 + 22, 85, -115.6), (hx0 + 2, 85, -115.6), "detail_firehouse", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["louver_vent"])
    # Galvanized VHF communications mast on NW roof of 2nd-storey crew wing (tiled vertically <= 22 art px!)
    mx, mz = -46, -126
    struct_b.add_box(mx - 6, 113, mz - 6, mx + 6, 120, mz + 6, "roof_cap")
    add_tiled_cylinder_y(struct_b, mx, 120, 198, mz, 2.2, 6, "detail_firehouse", det["dark_metal"], cap_top=True)
    # Crossbar & dipole whips + top red aviation warning bulb
    struct_b.add_box(mx - 12, 178, mz - 1, mx + 12, 180, mz + 1, "detail_firehouse", uv_rect_art=det["dark_metal"])
    struct_b.add_box(mx - 1, 178, mz - 12, mx + 1, 180, mz + 12, "detail_firehouse", uv_rect_art=det["dark_metal"])
    for dx in (-11, 11):
        struct_b.add_cylinder_y(mx + dx, 180, 194, mz, 0.7, 4, "detail_firehouse", uv_rect_art=det["dark_metal"], cap_top=True)
    struct_b.add_box(mx - 1.8, 198, mz - 1.8, mx + 1.8, 202, mz + 1.8, "glow_lamps", uv_rect_art=glw["red_globe"])

    # 7) CLEAN INTERIOR APPARATUS BAY SHELL
    struct_b.add_quad((-106, 1.0, -6), (106, 1.0, -6), (106, 1.0, -154), (-106, 1.0, -154), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    struct_b.add_quad((-106, 62, -154), (106, 62, -154), (106, 62, -6), (-106, 62, -6), "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 0, 1))
    add_tiled_interior_wall(struct_b, -106, 1, -154, 106, 62, -154, "+z", det)
    add_tiled_interior_wall(struct_b, -106, 1, -154, -106, 62, -6, "+x", det)
    add_tiled_interior_wall(struct_b, 106, 1, -154, 106, 62, -6, "-x", det)

    # Full-height collision boxes for 1988 structure
    coll.add("station_ground_block", -112, 0, -160, 112, 76.5, 0)
    coll.add("station_crew_upper", -66, 73, -152, 66, 115.5, -32)
    coll.add("lobby_canopy", -34.5, 38, 0, 34.5, 44, 20.5)
    coll.add("vhf_radio_mast", mx - 6, 113, mz - 6, mx + 6, 202, mz + 6)


def build_state(spec):
    year = spec["year"]
    mat_defs = get_material_defs(year)
    mat_idx = {m["name"]: i for i, m in enumerate(mat_defs)}
    mat_size = {m["name"]: read_png_size(TEX_DIR / m["file"]) for m in mat_defs}

    regs = ART_MANIFEST["regions"][str(year)]
    det, sgn, glw = regs["details"], regs["signs"], regs["glow"]

    buckets = {
        k: MeshBucket(k, mat_idx, mat_size)
        for k in ("structure", "lot", "parking_lot", "rear_service", "curb_props", "collision")
    }
    coll = CollisionCollector(buckets["collision"])

    build_shared_lot(
        buckets["lot"], buckets["parking_lot"], buckets["rear_service"], buckets["curb_props"],
        coll, year, det, sgn,
    )
    if year == 1924:
        build_1924_structure(buckets["structure"], coll, det, sgn, glw)
        # Fix inward interior side walls cleanly on 1924
        add_tiled_interior_wall(buckets["structure"], 106, 1, -154, 106, 54, -6, "-x", det)
    else:
        build_1988_structure(buckets["structure"], coll, det, sgn, glw)

    # Pack glTF 2.0 binary
    bin_buf = bytearray()
    buffer_views = []
    accessors = []
    images = []
    textures = []
    materials = []
    samplers = [{"magFilter": 9728, "minFilter": 9728, "wrapS": 10497, "wrapT": 10497}]

    def append_bytes(data_bytes, target=None):
        while len(bin_buf) % 4 != 0:
            bin_buf.append(0)
        off = len(bin_buf)
        bin_buf.extend(data_bytes)
        bv = {"buffer": 0, "byteOffset": off, "byteLength": len(data_bytes)}
        if target is not None:
            bv["target"] = target
        idx = len(buffer_views)
        buffer_views.append(bv)
        return idx

    img_cache = {}

    def add_image_tex(filename, label):
        if filename in img_cache:
            return img_cache[filename]
        raw = (TEX_DIR / filename).read_bytes()
        bv = append_bytes(raw)
        i_idx = len(images)
        images.append({"name": label, "mimeType": "image/png", "bufferView": bv})
        t_idx = len(textures)
        textures.append({"sampler": 0, "source": i_idx})
        img_cache[filename] = t_idx
        return t_idx

    for m in mat_defs:
        bc_tex = add_image_tex(m["file"], m["name"])
        mat_obj = {
            "name": m["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": bc_tex},
                "metallicFactor": m["metal"],
                "roughnessFactor": m["rough"],
            },
            "alphaMode": m["alpha"],
        }
        if m.get("doubleSided"):
            mat_obj["doubleSided"] = True
        if "emissive_file" in m:
            em_tex = add_image_tex(m["emissive_file"], m["name"] + "_emissive")
            mat_obj["emissiveTexture"] = {"index": em_tex}
            mat_obj["emissiveFactor"] = m["emissive"]
        materials.append(mat_obj)

    meshes = []
    mesh_idx_by_name = {}

    def pack_bucket(bname, bucket):
        prims = []
        for mat_idx_i, pdata in sorted(bucket.prims.items()):
            pos = pdata["pos"]
            nrm = pdata["nrm"]
            uv = pdata["uv"]
            idx = pdata["idx"]
            if not idx:
                continue

            pos_bytes = bytearray()
            mn = [1e9, 1e9, 1e9]
            mx = [-1e9, -1e9, -1e9]
            for x, y, z in pos:
                pos_bytes.extend(struct.pack("<fff", x, y, z))
                if x < mn[0]: mn[0] = x
                if y < mn[1]: mn[1] = y
                if z < mn[2]: mn[2] = z
                if x > mx[0]: mx[0] = x
                if y > mx[1]: mx[1] = y
                if z > mx[2]: mx[2] = z
            pos_bv = append_bytes(pos_bytes, target=34962)
            pos_acc = len(accessors)
            accessors.append({
                "bufferView": pos_bv, "componentType": 5126, "count": len(pos),
                "type": "VEC3", "min": mn, "max": mx,
            })

            nrm_bytes = bytearray()
            for x, y, z in nrm:
                nrm_bytes.extend(struct.pack("<fff", x, y, z))
            nrm_bv = append_bytes(nrm_bytes, target=34962)
            nrm_acc = len(accessors)
            accessors.append({
                "bufferView": nrm_bv, "componentType": 5126, "count": len(nrm), "type": "VEC3",
            })

            uv_bytes = bytearray()
            for u, v in uv:
                uv_bytes.extend(struct.pack("<ff", u, v))
            uv_bv = append_bytes(uv_bytes, target=34962)
            uv_acc = len(accessors)
            accessors.append({
                "bufferView": uv_bv, "componentType": 5126, "count": len(uv), "type": "VEC2",
            })

            idx_bytes = bytearray()
            for ii in idx:
                idx_bytes.extend(struct.pack("<I", ii))
            idx_bv = append_bytes(idx_bytes, target=34963)
            idx_acc = len(accessors)
            accessors.append({
                "bufferView": idx_bv, "componentType": 5125, "count": len(idx), "type": "SCALAR",
            })

            prims.append({
                "attributes": {"POSITION": pos_acc, "NORMAL": nrm_acc, "TEXCOORD_0": uv_acc},
                "indices": idx_acc,
                "material": mat_idx_i,
            })
        if not prims:
            return None
        midx = len(meshes)
        meshes.append({"name": bname, "primitives": prims})
        mesh_idx_by_name[bname] = midx
        return midx

    for bname in ("structure", "lot", "parking_lot", "rear_service", "curb_props", "collision"):
        pack_bucket(bname, buckets[bname])

    lot_children = []
    nodes = [
        {"name": "fire_station", "children": [1, 2, 3, 4, 5]},
        {"name": "structure", "mesh": mesh_idx_by_name["structure"]},
        {"name": "lot", "mesh": mesh_idx_by_name["lot"], "children": lot_children},
        {"name": "collision", "mesh": mesh_idx_by_name["collision"], "extras": {"collision_only": True}},
        {
            "name": "door_main",
            "translation": [0.0, 0.0, round(2.0 * U, 6)],
            "rotation": [0.0, 0.0, 0.0, 1.0],
        },
        {
            "name": "door_back",
            "translation": [0.0, 0.0, round(-162.0 * U, 6)],
            "rotation": [0.0, 1.0, 0.0, 0.0],
        },
    ]
    for child_name in ("parking_lot", "rear_service", "curb_props"):
        if child_name in mesh_idx_by_name:
            idx_n = len(nodes)
            nodes.append({"name": child_name, "mesh": mesh_idx_by_name[child_name]})
            lot_children.append(idx_n)

    while len(bin_buf) % 4 != 0:
        bin_buf.append(0)

    gltf = {
        "asset": {"version": "2.0", "generator": f"Small-Town Fire Station Builder ({year})"},
        "scene": 0,
        "scenes": [{"name": f"fire_station_{year}", "nodes": [0]}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": materials,
        "textures": textures,
        "images": images,
        "samplers": samplers,
        "accessors": accessors,
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(bin_buf)}],
    }

    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(json_bytes) % 4 != 0:
        json_bytes += b" "

    total_len = 12 + 8 + len(json_bytes) + 8 + len(bin_buf)
    header = struct.pack("<III", 0x46546C67, 2, total_len)
    json_chunk = struct.pack("<II", len(json_bytes), 0x4E4F534A) + json_bytes
    bin_chunk = struct.pack("<II", len(bin_buf), 0x004E4942) + bytes(bin_buf)

    glb_path = R / spec["filename"]
    glb_path.write_bytes(header + json_chunk + bin_chunk)

    vis_tris = sum(buckets[k].tri_count() for k in ("structure", "lot", "parking_lot", "rear_service", "curb_props"))
    coll_tris = buckets["collision"].tri_count()
    print(f"Wrote {spec['filename']} ({glb_path.stat().st_size} bytes) | vis={vis_tris} tris | coll={len(coll.boxes)} boxes ({coll_tris} tris)")

    return {
        "id": spec["id"],
        "year": year,
        "title": spec["title"],
        "file": spec["filename"],
        "era": spec["era"],
        "summary": spec["summary"],
        "triangles": {
            "visible": vis_tris,
            "collision": coll_tris,
            "by_node": {
                k: buckets[k].tri_count()
                for k in ("structure", "lot", "parking_lot", "rear_service", "curb_props", "collision")
            },
        },
        "materials": [
            {
                "name": m["name"],
                "file": m["file"],
                "emissive_file": m.get("emissive_file"),
                "size": list(mat_size[m["name"]]),
                "alpha": m["alpha"],
                "emissive": "emissive_file" in m,
            }
            for m in mat_defs
        ],
        "collision": {
            "coordinate_note": "Full-height glTF Y-up boxes in metres from ground y=0 to full structure/tower/mast top.",
            "boxes_gltf_y_up": coll.boxes,
        },
    }


def main():
    states_info = [build_state(spec) for spec in STATE_SPECS]
    model_info = {
        "name": "Small-Town Fire Station (Engine Co. No. 2 / Station No. 2)",
        "kind": "civic_glb_fire_station",
        "role": "fire_station",
        "room": "apparatus_bay",
        "built_year": 1924,
        "era": {"from": 1924, "to": 2026, "built": 1924},
        "texel_size_m": T,
        "art_pixel_m": U,
        "footprint_m": {
            "lot_width": round(360 * U, 4),
            "lot_depth": round(328 * U, 4),
            "building_width": round(224 * U, 4),
            "building_depth": round(160 * U, 4),
            "front_apron_depth": round(80 * U, 4),
            "height_1924_main": round(116 * U, 4),
            "height_1924_pediment": round(142.5 * U, 4),
            "height_1924_hose_tower": round(222 * U, 4),
            "height_1988_main": round(76.5 * U, 4),
            "height_1988_crew_wing": round(115.5 * U, 4),
            "height_1988_radio_mast": round(202 * U, 4),
        },
        "doors": {
            "door_main": {
                "translation_gltf": [0.0, 0.0, round(2.0 * U, 4)],
                "outward": [0, 0, 1],
            },
            "door_back": {
                "translation_gltf": [0.0, 0.0, round(-162.0 * U, 4)],
                "outward": [0, 0, -1],
            },
        },
        "swatches_used": ART_MANIFEST["shared_swatches"],
        "states": states_info,
    }
    (R / "model-info.json").write_text(json.dumps(model_info, indent=2) + "\n", encoding="utf-8")
    print("Wrote model-info.json for Fire Station (1924 & 1988 states).")


if __name__ == "__main__":
    main()
