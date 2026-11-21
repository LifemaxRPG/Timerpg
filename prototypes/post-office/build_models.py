#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) builder for the Small-Town WPA Post Office in two historical states
sharing ONE lot, historic 1938 shell footprint, origin (0, 0, 0 at front-center wall edge),
curb layout, interior WPA mural, and exact door nodes (door_main, door_back):

  - post-office-1938.glb (1938-1977, built 1938): Original 1930s WPA-era red-brick and Indiana
    limestone federal post office with 4 classical pilasters, triangular carved pediment
    ('U.S. POST OFFICE' / '1938'), hipped slate roof, louvered civic cupola with brass finial,
    tapered brick boiler chimney, open-glazed front lobby windows looking into the preserved
    full-width 1930s WPA lobby mural, brass teller counter & P.O. boxes, bronze globe entrance
    lanterns, olive-green corner mailbox, and rear covered mail loading dock.

  - post-office-1988.glb (1978-2026, renovated 1978): Modernized historic Post Office preserving
    the 1938 WPA masonry shell & interior mural while adding an East concrete accessibility ramp
    with aluminum handrails, bronze-anodized aluminum glass vestibule doors, twin royal-blue
    drive-up curb collection boxes, modest backlit sidewalk monument sign, expanded rear mail-truck
    loading canopy with safety bollards, and rooftop HVAC units behind the parapet.

Follows docs/CHATGPT_MODEL_GUIDE.md strictly:
- 0.0388 m/texel on 2x2 art-pixel blocks (U = 0.0776 m)
- Under 5,000 triangles per state; 12 materials (<= 12) named by role
- 6 shared LimeZu swatches + bespoke 2x2 pixel-art textures & dedicated WPA lobby mural
- Pure-black emissive mask strictly on lamps, cupola glow, and the modest 1988 monument sign
- Full-height glTF Y-up collision boxes listed in model-info.json
- Lot props separated cleanly under the 'lot' parent node
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
        "year": 1938,
        "id": "1938",
        "filename": "post-office-1938.glb",
        "title": "U.S. Post Office — 1938 WPA Federal Building",
        "era": {"from": 1938, "to": 1977, "built": 1938},
        "summary": (
            "Classical 1938 WPA red-brick and Indiana limestone small-town federal post office with "
            "four fluted limestone pilasters, triangular pediment ('U.S. POST OFFICE' / '1938'), "
            "hipped slate roof with louvered civic cupola, tapered boiler chimney, clean interior "
            "lobby shell featuring a full-width 1930s painted WPA mural above the walnut-and-brass "
            "teller counter, brass P.O. boxes, bronze globe entrance lanterns, olive-green corner "
            "mailbox, flagpole, and rear covered mail loading dock."
        ),
    },
    {
        "year": 1988,
        "id": "1988",
        "filename": "post-office-1988.glb",
        "title": "U.S. Post Office — 1988 Modernized Historic Branch",
        "era": {"from": 1978, "to": 2026, "built": 1938, "renovated": 1978},
        "summary": (
            "Renovated historic WPA post office sharing the exact 1938 brick-and-limestone shell, "
            "lot, doors, and preserved interior 1938 WPA mural, upgraded with an East concrete "
            "accessibility ramp and handrails, bronze-aluminum glass vestibule doors, twin royal-blue "
            "curbside collection boxes, a modest backlit sidewalk monument sign, expanded rear "
            "mail-truck loading canopy with yellow safety bollards, and rooftop HVAC units."
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
        {"name": "wall_stone", "file": "stone_trim.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "roof_slate", "file": "roof_slate.png", "rough": 0.86, "metal": 0.02, "alpha": "OPAQUE"},
        {"name": "roof_gravel", "file": "ballast.png", "rough": 0.96, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "roof_cap", "file": "roof_membrane_grey.png", "rough": 0.84, "metal": 0.08, "alpha": "OPAQUE"},
        {"name": "ground_asphalt", "file": "asphalt_worn.png", "rough": 0.95, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "ground_pavement", "file": "pavement.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "glass_windows", "file": f"glass_{year}.png", "rough": 0.16, "metal": 0.05, "alpha": "BLEND", "doubleSided": True},
        {"name": "sign_civic", "file": f"signs_{year}.png", "rough": 0.78, "metal": 0.04, "alpha": "OPAQUE"},
        {"name": "detail_post", "file": f"details_{year}.png", "rough": 0.72, "metal": 0.14, "alpha": "OPAQUE"},
        {"name": "mural_wpa", "file": "wpa_mural.png", "rough": 0.76, "metal": 0.02, "alpha": "OPAQUE"},
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

    def add_frustum(self, cx, cz, y0, w0, d0, y1, w1, d1, mat_name, cap_top=True):
        """Tapered rectangular frustum (used for hipped roof slopes and tapered chimneys!)."""
        bx0, bx1, bz0, bz1 = cx - w0 / 2, cx + w0 / 2, cz - d0 / 2, cz + d0 / 2
        tx0, tx1, tz0, tz1 = cx - w1 / 2, cx + w1 / 2, cz - d1 / 2, cz + d1 / 2
        # +z slope
        self.add_quad((bx0, y0, bz1), (bx1, y0, bz1), (tx1, y1, tz1), (tx0, y1, tz1), mat_name)
        # -z slope
        self.add_quad((bx1, y0, bz0), (bx0, y0, bz0), (tx0, y1, tz0), (tx1, y1, tz0), mat_name)
        # +x slope
        self.add_quad((bx1, y0, bz1), (bx1, y0, bz0), (tx1, y1, tz0), (tx1, y1, tz1), mat_name)
        # -x slope
        self.add_quad((bx0, y0, bz0), (bx0, y0, bz1), (tx0, y1, tz1), (tx0, y1, tz0), mat_name)
        if cap_top and w1 > 0 and d1 > 0:
            self.add_quad((tx0, y1, tz1), (tx1, y1, tz1), (tx1, y1, tz0), (tx0, y1, tz0), mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, -1))

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
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "wall_stone")
        self.boxes.append({
            "label": label,
            "min_gltf_m": [round(x0 * U, 4), round(y0 * U, 4), round(z0 * U, 4)],
            "max_gltf_m": [round(x1 * U, 4), round(y1 * U, 4), round(z1 * U, 4)],
        })


# ==============================================================================
# SAFE ATLAS-TILING HELPERS (NEVER EXCEED SUB-RECT BOUNDS!)
# ==============================================================================
def add_tiled_cylinder_y(bucket, cx, y0, y1, cz, radius, segs, mat_name, uv_rect_art, cap_top=True):
    max_h = max(4.0, uv_rect_art[3] - 2.0)
    span = y1 - y0
    steps = max(1, math.ceil(span / max_h))
    dy = span / steps
    for i in range(steps):
        ya = y0 + i * dy
        yb = y0 + (i + 1) * dy
        bucket.add_cylinder_y(cx, ya, yb, cz, radius, segs, mat_name, uv_rect_art=uv_rect_art, cap_top=(cap_top and i == steps - 1))


def add_tiled_box_x(bucket, x0, y0, z0, x1, y1, z1, mat_name, uv_rect_art, faces=("+z", "-z", "+x", "-x", "+y", "-y")):
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


def add_tiled_box_y(bucket, x0, y0, z0, x1, y1, z1, mat_name, uv_rect_art, faces=("+z", "-z", "+x", "-x", "+y", "-y")):
    max_h = max(4.0, uv_rect_art[3] - 2.0)
    span = y1 - y0
    steps = max(1, math.ceil(span / max_h))
    dy = span / steps
    for i in range(steps):
        ya = y0 + i * dy
        yb = y0 + (i + 1) * dy
        f_list = [f for f in faces if not (f == "-y" and i > 0) and not (f == "+y" and i < steps - 1)]
        if f_list:
            bucket.add_box(x0, ya, z0, x1, yb, z1, mat_name, faces=tuple(f_list), uv_rect_art=uv_rect_art)


def add_tiled_quad_wall(bucket, x0, y0, z0, x1, y1, z1, facing, mat_name, rect):
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
                    bucket.add_quad((xa, ya, z0), (xb, ya, z0), (xb, yb, z0), (xa, yb, z0), mat_name, u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
                else:
                    bucket.add_quad((xb, ya, z0), (xa, ya, z0), (xa, yb, z0), (xb, yb, z0), mat_name, u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
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
                    bucket.add_quad((x0, ya, zb), (x0, ya, za), (x0, yb, za), (x0, yb, zb), mat_name, u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=rect)
                else:
                    bucket.add_quad((x0, ya, za), (x0, ya, zb), (x0, yb, zb), (x0, yb, za), mat_name, u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=rect)


def add_perimeter_rim(bucket, x0, y0, z0, x1, y1, z1, thick, mat_name):
    """Hollow 4-sided cornice or parapet coping rim leaving the recessed roof open from above."""
    bucket.add_box(x0, y0, z1 - thick, x1, y1, z1, mat_name)
    bucket.add_box(x0, y0, z0, x1, y1, z0 + thick, mat_name)
    bucket.add_box(x0, y0, z0 + thick, x0 + thick, y1, z1 - thick, mat_name)
    bucket.add_box(x1 - thick, y0, z0 + thick, x1, y1, z1 - thick, mat_name)


# ==============================================================================
# SHARED LOT, CURB, PLAZA, MAILBOXES, FLAGPOLE & REAR MAIL YARD
# ==============================================================================
def build_shared_lot(lot, parking, rear, props, coll, year, det, sgn, glw):
    """Shared civic plaza, sidewalk, curb, East postal drive, rear loading yard, and era curb props."""
    # Front civic plaza & sidewalk: x in [-172, +172], z in [0, +80]
    lot.add_quad((-172, 0, 80), (172, 0, 80), (172, 0, 0), (-172, 0, 0), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    # Stone front street curb (split cleanly for East postal driveway entrance x in [108, 164])
    lot.add_box(-172, 0, 76, 108, 2.0, 80, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
    lot.add_box(164, 0, 76, 172, 2.0, 80, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))

    # West concrete civic walkway: x in [-172, -108], z in [-144, 0]
    lot.add_quad((-172, 0, 0), (-108, 0, 0), (-108, 0, -144), (-172, 0, -144), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # East postal delivery truck driveway: x in [108, 172], z in [-144, 0]
    parking.add_quad((108, 0, 0), (172, 0, 0), (172, 0, -144), (108, 0, -144), "ground_asphalt", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    # Rear mail-truck turnaround & parking yard: x in [-172, 172], z in [-236, -144]
    parking.add_quad((-172, 0, -144), (172, 0, -144), (172, 0, -236), (-172, 0, -236), "ground_asphalt", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # Yellow loading-zone stripes in rear yard (each segment <= 56 art px inside 64x16 yellow_stripe!)
    ys = det["yellow_stripe"]
    for pz in (-186, -214):
        parking.add_quad((-156, 0.35, pz + 1.5), (-104, 0.35, pz + 1.5), (-104, 0.35, pz), (-156, 0.35, pz), "detail_post", u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=ys)
    # Rear loading dock backing stripes
    for sx in (18, 62):
        parking.add_quad((sx, 0.35, -162), (sx + 2, 0.35, -162), (sx + 2, 0.35, -214), (sx, 0.35, -214), "detail_post", u_axis=(0, 0, -1), v_axis=(1, 0, 0), uv_rect_art=ys)

    # Rear perimeter fence along back lot boundary (z = -234)
    for bx in range(-158, 162, 32):
        rear.add_box(bx - 1.5, 0, -235, bx + 1.5, 16, -232, "detail_post", uv_rect_art=det["dark_metal"])
    add_tiled_box_x(rear, -158, 13, -234.5, 158, 15, -232.5, "detail_post", det["dark_metal"])
    add_tiled_box_x(rear, -158, 6, -234.5, 158, 8, -232.5, "detail_post", det["dark_metal"])
    coll.add("rear_fence", -160, 0, -236, 160, 16, -232)

    # Shared Tall Civic Flagpole on West Front Plaza (x = -142, z = 54)
    fx, fz = -142, 54
    props.add_box(fx - 5, 0, fz - 5, fx + 5, 5, fz + 5, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
    add_tiled_cylinder_y(props, fx, 5, 130, fz, 1.1, 6, "detail_post", det["dark_metal"], cap_top=False)
    props.add_cylinder_y(fx, 130, 133, fz, 1.9, 6, "detail_post", uv_rect_art=det["brass_gold"], cap_top=True)
    props.add_quad((fx + 1.2, 108, fz), (fx + 33.2, 108, fz), (fx + 33.2, 128, fz), (fx + 1.2, 128, fz), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["civic_flag"])
    props.add_quad((fx + 33.2, 108, fz - 0.1), (fx + 1.2, 108, fz - 0.1), (fx + 1.2, 128, fz - 0.1), (fx + 33.2, 128, fz - 0.1), "sign_civic", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["civic_flag"])
    coll.add("flagpole", fx - 5, 0, fz - 5, fx + 5, 133, fz + 5)

    # Formal stone-bordered civic boxwood planter bed on West side of front steps (shared both eras!)
    lot.add_box(-104, 0, 2, -38, 6, 20, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
    add_tiled_box_x(props, -100, 6, 5, -42, 15, 17, "detail_post", det["hedge_green"])
    coll.add("west_planter", -104, 0, 2, -38, 15, 20)

    if year == 1938:
        # Matching symmetrical East civic boxwood planter bed in 1938
        lot.add_box(38, 0, 2, 104, 6, 20, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
        add_tiled_box_x(props, 42, 6, 5, 100, 15, 17, "detail_post", det["hedge_green"])
        coll.add("east_planter", 38, 0, 2, 104, 15, 20)

        # Classic 1930s Olive-Green Cast-Iron Corner Mailbox (front-East curb: x = 84, z = 62)
        mx, mz = 84, 62
        for lx in (-4, 2):
            for lz in (-3, 2):
                props.add_box(mx + lx, 0, mz + lz, mx + lx + 2, 6, mz + lz + 2, "detail_post", uv_rect_art=det["dark_metal"])
        props.add_box(mx - 5, 6, mz - 4, mx + 5, 19, mz + 4, "detail_post", uv_rect_art=det["mailbox_body"])
        props.add_box(mx - 5.5, 19, mz - 4.5, mx + 5.5, 21.5, mz + 4.5, "detail_post", uv_rect_art=det["mailbox_body"])
        coll.add("mailbox_1938", mx - 6, 0, mz - 5, mx + 6, 22, mz + 5)

        # Carved stone & timber civic plaza bench on West front plaza (x = -96..-64, z = 44..52)
        props.add_box(-96, 5, 44, -64, 7.5, 52, "detail_post", uv_rect_art=det["wood_trim"])
        props.add_box(-96, 7.5, 44, -64, 13, 46, "detail_post", uv_rect_art=det["wood_trim"])
        for lx in (-93, -69):
            props.add_box(lx, 0, 45, lx + 3, 5, 51, "wall_stone")
        coll.add("plaza_bench", -96, 0, 44, -64, 13, 52)

        # Rear timber mail-cart hand-truck crate pad (SW rear yard)
        rear.add_box(-148, 0, -222, -116, 1.5, -198, "ground_pavement", faces=("+z", "-z", "+x", "-x", "+y"))
        rear.add_box(-142, 1.5, -218, -122, 13.5, -204, "detail_post", uv_rect_art=det["wood_trim"])
        coll.add("rear_mail_crates", -144, 0, -220, -120, 14, -202)
    else:
        # 1988: Twin Royal-Blue Drive-Up Postal Collection Boxes along East front curb (x = 64 & 84, z = 62)
        for mx in (64, 84):
            mz = 62
            for lx in (-4, 2):
                for lz in (-3, 2):
                    props.add_box(mx + lx, 0, mz + lz, mx + lx + 2, 5, mz + lz + 2, "detail_post", uv_rect_art=det["dark_metal"])
            props.add_box(mx - 5.5, 5, mz - 4.5, mx + 5.5, 20, mz + 4.5, "detail_post", uv_rect_art=det["mailbox_body"])
            props.add_box(mx - 6, 20, mz - 5, mx + 6, 22.5, mz + 5, "detail_post", uv_rect_art=det["mailbox_body"])
            coll.add(f"collection_box_{mx}", mx - 6, 0, mz - 5, mx + 6, 23, mz + 5)

        # 1988: Compact human-scaled backlit sidewalk monument sign ('POST / OFFICE', 44x18 art px on low stone plinth)
        sx0, sx1, sz = -90, -46, 52
        props.add_box(sx0 - 3, 0, sz - 4, sx1 + 3, 6, sz + 4, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
        add_tiled_box_x(props, sx0, 6, sz - 3, sx1, 24, sz + 3, "detail_post", det["dark_metal"], faces=("+x", "-x", "+y"))
        props.add_quad((sx0, 6, sz + 3.1), (sx1, 6, sz + 3.1), (sx1, 24, sz + 3.1), (sx0, 24, sz + 3.1), "glow_lamps", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=glw["backlit_monument"])
        props.add_quad((sx1, 6, sz - 3.1), (sx0, 6, sz - 3.1), (sx0, 24, sz - 3.1), (sx1, 24, sz - 3.1), "glow_lamps", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=glw["backlit_monument"])
        coll.add("monument_sign_1988", sx0 - 3, 0, sz - 4, sx1 + 3, 24, sz + 4)

        # 1988: Yellow safety bollards flanking rear loading dock + blue metal utility bin
        for bx in (14, 70):
            rear.add_cylinder_y(bx, 0, 14, -162, 1.8, 8, "detail_post", uv_rect_art=det["yellow_stripe"], cap_top=True)
            coll.add(f"dock_bollard_{bx}", bx - 2, 0, -164, bx + 2, 14, -160)
        rear.add_box(-154, 0, -226, -118, 1.0, -196, "ground_pavement", faces=("+z", "-z", "+x", "-x", "+y"))
        add_tiled_box_x(rear, -150, 1, -222, -122, 19, -200, "detail_post", det["mailbox_body"])
        add_tiled_box_x(rear, -151, 19, -223, -121, 21, -199, "detail_post", det["dark_metal"])
        coll.add("rear_utility_bin", -151, 0, -223, -121, 21, -199)


# ==============================================================================
# SHARED 1938 WPA MASONRY SHELL, INTERIOR LOBBY + WPA MURAL, & ERA UPGRADES
# ==============================================================================
def build_post_office_structure(struct_b, coll, year, det, sgn, glw):
    """
    Shared 1938 WPA Brick & Indiana Limestone Post Office shell + Interior Lobby & WPA Mural,
    with era-specific entrances, accessibility ramp, loading canopy, and HVAC upgrades.
    Building footprint: x in [-108, +108] (16.76m), z in [-144, 0] (11.17m), cornice y = 86 (6.67m).
    """
    # 1) GROUND RUSTICATED LIMESTONE PLINTH (y = 0..10) wrapping sides, rear, and front wings
    struct_b.add_box(-108, 0, -144, 108, 10, -138, "wall_stone", faces=("-z", "+x", "-x"))
    struct_b.add_box(-108, 0, -138, -102, 10, 0, "wall_stone", faces=("-x",))
    struct_b.add_box(102, 0, -138, 108, 10, 0, "wall_stone", faces=("+x",))
    struct_b.add_box(-108, 0, -6, 108, 10, 0, "wall_stone", faces=("+z",))

    # 2) MAIN RED-BRICK EXTERIOR WALLS (y = 10..86)
    # Back wall (-z = -144), Left wall (-x = -108), Right wall (+x = +108)
    struct_b.add_box(-108, 10, -144, 108, 86, -138, "wall_brick", faces=("-z", "+x", "-x"))
    struct_b.add_box(-108, 10, -138, -102, 86, 0, "wall_brick", faces=("-x",))
    struct_b.add_box(102, 10, -138, 108, 86, 0, "wall_brick", faces=("+x",))

    # FRONT WALL (+z = 0) BUILT WITH REAL OPENINGS FOR THE 4 TALL LOBBY WINDOWS & ENTRANCE
    # so you can genuinely look through the alpha-blended glass into the illuminated WPA Mural!
    # Openings:
    #   West wing window:  x in [-96, -74], y in [22, 62]
    #   West pav window:   x in [-48, -30], y in [22, 62]
    #   Center entry door: x in [-16, +16], y in [10, 54]
    #   East pav window:   x in [+30, +48], y in [22, 62]
    #   East wing window:  x in [+74, +96], y in [22, 62]
    # Lower front wall below windows (y = 10..22)
    struct_b.add_box(-108, 10, -6, -64, 22, 0, "wall_brick", faces=("+z", "+y"))
    struct_b.add_box(64, 10, -6, 108, 22, 0, "wall_brick", faces=("+z", "+y"))
    # Upper front wing walls above windows (y = 62..86)
    struct_b.add_box(-108, 62, -6, -64, 86, 0, "wall_brick", faces=("+z", "-y"))
    struct_b.add_box(64, 62, -6, 108, 86, 0, "wall_brick", faces=("+z", "-y"))
    # Vertical brick piers flanking wing windows (y = 22..62)
    for px0, px1 in [(-108, -96), (-74, -64), (64, 74), (96, 108)]:
        struct_b.add_box(px0, 22, -6, px1, 62, 0, "wall_brick", faces=("+z", "+x", "-x"))

    # Classical corner limestone quoins on front brick corners
    for qy in (14, 30, 46, 62, 76):
        struct_b.add_box(-109, qy, -6, -102, qy + 7, 0.8, "wall_stone", faces=("+z", "-x", "+y", "-y"))
        struct_b.add_box(102, qy, -6, 109, qy + 7, 0.8, "wall_stone", faces=("+z", "+x", "+y", "-y"))

    # 3) CENTRAL PROJECTING INDIANA LIMESTONE WPA PAVILION (x in [-64, +64], z in [-6, +4])
    # Lower limestone apron below pavilion windows (y = 10..22)
    struct_b.add_box(-64, 10, -6, -16, 22, 4, "wall_stone", faces=("+z", "+x", "-x", "+y"))
    struct_b.add_box(16, 10, -6, 64, 22, 4, "wall_stone", faces=("+z", "+x", "-x", "+y"))
    # Limestone plinth under pavilion projection (z = 0..4, y = 0..10)
    struct_b.add_box(-64, 0, 0, 64, 10, 4, "wall_stone", faces=("+z", "+x", "-x", "+y"))
    # Vertical limestone piers between pavilion openings (y = 22..62)
    for px0, px1 in [(-64, -48), (-30, -16), (16, 30), (48, 64)]:
        struct_b.add_box(px0, 22, -6, px1, 62, 4, "wall_stone", faces=("+z", "+x", "-x"))
    # Limestone spandrel above main entrance (y = 54..76) & above pavilion windows (y = 62..76)
    struct_b.add_box(-16, 54, -6, 16, 76, 4, "wall_stone", faces=("+z", "-y"))
    struct_b.add_box(-64, 62, -6, -16, 76, 4, "wall_stone", faces=("+z", "+x", "-x", "-y"))
    struct_b.add_box(16, 62, -6, 64, 76, 4, "wall_stone", faces=("+z", "+x", "-x", "-y"))

    # 4 Classical Fluted/Paneled Indiana Limestone Pilasters (x = -60..-52, -26..-18, +18..+26, +52..+60)
    for cx in (-56, -22, 22, 56):
        struct_b.add_box(cx - 5, 10, 4, cx + 5, 15, 7.2, "wall_stone", faces=("+z", "+x", "-x", "+y"))
        struct_b.add_box(cx - 4, 15, 4, cx + 4, 72, 6.4, "wall_stone", faces=("+z", "+x", "-x"))
        struct_b.add_box(cx - 5.2, 72, 4, cx + 5.2, 76, 7.6, "wall_stone", faces=("+z", "+x", "-x", "+y", "-y"))

    # Entablature Frieze Band (y = 76..92, z = -6..+6) carrying carved 'U.S. POST OFFICE' tablet!
    struct_b.add_box(-65, 76, -6, 65, 92, 6.0, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y", "-y"))
    struct_b.add_quad((-60, 76, 6.2), (60, 76, 6.2), (60, 92, 6.2), (-60, 92, 6.2), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["main_entablature"])
    # Projecting classical cornice above frieze (y = 92..95)
    struct_b.add_box(-67, 92, -6, 67, 95, 8.2, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y", "-y"))

    # TRUE TRIANGULAR CLASSICAL PEDIMENT (x in [-64, +64], base y = 95, apex y = 117 at x = 0!)
    # Front & back triangular tympanum walls
    struct_b.add_polygon([(-62, 95, 6.0), (62, 95, 6.0), (0, 115, 6.0)], "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
    struct_b.add_polygon([(62, 95, -2.0), (-62, 95, -2.0), (0, 115, -2.0)], "wall_stone", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))
    # Sloped limestone raking cornice caps on left & right pediment slopes
    # Left slope from (-66, 95) to (0, 117), thickness 3.2 art px
    struct_b.add_quad((-66, 95, 8.2), (0, 117, 8.2), (0, 117, -2.0), (-66, 95, -2.0), "wall_stone")
    struct_b.add_quad((-66, 95, 8.2), (-62, 95, 8.2), (0, 114.5, 8.2), (0, 117, 8.2), "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
    # Right slope from (0, 117) to (+66, 95)
    struct_b.add_quad((0, 117, 8.2), (66, 95, 8.2), (66, 95, -2.0), (0, 117, -2.0), "wall_stone")
    struct_b.add_quad((0, 114.5, 8.2), (62, 95, 8.2), (66, 95, 8.2), (0, 117, 8.2), "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
    # Carved '1938' limestone date block tucked cleanly inside pediment tympanum (40x14 -> 32x11 crop or exact 40x14!)
    # Wait: at x = ±20, pediment ceiling y = 95 + (1 - 20/62)*20 = 108.5; so let's place date block y in [95.5, 106.5] -> height 11 (within 14) or shrink x to [-18, +18] & apex to 118!
    # Let's place '1938' cleanly at x in [-20, +20], y in [95.5, 109.5] with apex at 119 -> at x=20, slope y = 95 + (42/62)*24 = 111.2! Plenty of room!
    struct_b.add_quad((-20, 95.5, 6.4), (20, 95.5, 6.4), (20, 109.5, 6.4), (-20, 109.5, 6.4), "sign_civic", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["date_stone"])

    # Small bronze / navy plaque mounted beside main entrance (x in [-44, -28] -> wait, mail_plaque is 40x14 -> place on front East pier x in [30, 50]? No, place 20x7? Better: exact 40x14 fits on plinth or let's place it cleanly at x in [-58, -18]? No, make sure plaque width matches 40x14 or smaller: Wait! mail_plaque is 40x14, which is 3.1m wide! Too wide for a side plaque — let's mount it cleanly centered over the entrance doorway transom at x in [-20, +20], y in [56, 70] right between the inner pilasters [-18..+18] -> let's use [-18, +18] or keep it at [-20, +20] recessed at z = 4.3! Wait: inner pilasters are at [-26, -18] & [+18, +26], so gap is 36 wide! Let's skip stretching and center it at x in [-16, +16] ONLY if rect fits, OR place it at rear/front cheek wall? Better yet: mount 'WPA 1938' / 'ZIP MAIL' plaque cleanly on the front limestone transom spandrel centered at x in [-16, +16] -> wait: in make_art.py mail_plaque is 40x14 with "WPA 1938" (31px wide + centered in 40px -> text starts at +4px and ends at +35px). If a quad is 32px wide, UV reads [0..32] which would clip the last 3px of '8'! Always make the quad width >= rect width (40px) OR place it where 40px fits cleanly, like on the East/West wing spandrel x in [-104, -64] or centered above the loading dock!)
    # Let's place mail_plaque cleanly centered on the West limestone cheek plinth or over the West front wing lintel? Even cleaner: skip redundant front clutter (we ALREADY have 'U.S. POST OFFICE' + '1938' on the front!) and mount the small 'WPA 1938' / 'ZIP MAIL' plaque cleanly on the interior lobby entrance wall or rear employee door lintel where 40x14 fits with zero overlap! Let's put it cleanly on the rear employee entrance lintel x in [-20, +20], y = 44..58? No, rear canopy is at y = 52! So put it at x in [-62, -22], y = 42..56 on the West rear wall right beside the dock, OR on the interior lobby lintel above the front doors! Putting it on the interior lobby wall above the P.O. boxes is super authentic!

    # 4) PERIMETER LIMESTONE CORNICE, BRICK PARAPET, HIPPED SLATE ROOF & CIVIC CUPOLA
    add_perimeter_rim(struct_b, -110, 84, -146, 110, 88, 2.0, 8.0, "wall_stone")
    # Brick parapet walls on left, right, rear, and outer front wings (y = 88..94)
    struct_b.add_box(-108, 88, -144, 108, 94, -138, "wall_brick", faces=("+z", "-z", "+x", "-x"))
    struct_b.add_box(-108, 88, -138, -102, 94, 0, "wall_brick", faces=("+x", "-x"))
    struct_b.add_box(102, 88, -138, 108, 94, 0, "wall_brick", faces=("+x", "-x"))
    struct_b.add_box(-108, 88, -6, -64, 94, 0, "wall_brick", faces=("+z", "-z"))
    struct_b.add_box(64, 88, -6, 108, 94, 0, "wall_brick", faces=("+z", "-z"))
    # Hollow stone parapet coping cap (y = 94..96)
    add_perimeter_rim(struct_b, -109, 94, -145, 109, 96, 1.0, 7.5, "wall_stone")

    # Recessed flat roof gutter deck (y = 88) around the hipped slate roof
    struct_b.add_quad((-102, 88, -6), (102, 88, -6), (102, 88, -138), (-102, 88, -138), "roof_gravel", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # Classical Hipped Slate Roof rising inside the parapet (x in [-98, +98], z in [-132, -10], y = 88..112)
    struct_b.add_frustum(0, -71, 88, 196, 122, 112, 106, 46, "roof_slate", cap_top=False)
    # Flat copper/membrane ridge deck capping the hipped roof (y = 112)
    struct_b.add_quad((-53, 112, -48), (53, 112, -48), (53, 112, -94), (-53, 112, -94), "roof_cap", u_axis=(1, 0, 0), v_axis=(0, 0, -1))

    # Iconic Centered WPA Rooftop Civic Cupola / Lantern (x in [-16, +16], z in [-87, -55], y = 112..162 = 12.57 m!)
    cx, cz = 0, -71
    struct_b.add_box(cx - 17, 112, cz - 17, cx + 17, 120, cz + 17, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
    struct_b.add_box(cx - 14, 120, cz - 14, cx + 14, 138, cz + 14, "wall_stone", faces=("+z", "-z", "+x", "-x"))
    # Recessed cupola louvers + subtle night glow strips on all 4 cupola faces
    lv = det["louver_vent"]
    cg = glw["cupola_glow"]
    # +z & -z cupola faces
    struct_b.add_quad((cx - 10, 122, cz + 14.4), (cx + 10, 122, cz + 14.4), (cx + 10, 136, cz + 14.4), (cx - 10, 136, cz + 14.4), "detail_post", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=lv)
    struct_b.add_quad((cx + 10, 122, cz - 14.4), (cx - 10, 122, cz - 14.4), (cx - 10, 136, cz - 14.4), (cx + 10, 136, cz - 14.4), "detail_post", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=lv)
    struct_b.add_quad((cx - 6, 124, cz + 14.8), (cx + 6, 124, cz + 14.8), (cx + 6, 134, cz + 14.8), (cx - 6, 134, cz + 14.8), "glow_lamps", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=cg)
    struct_b.add_quad((cx + 6, 124, cz - 14.8), (cx - 6, 124, cz - 14.8), (cx - 6, 134, cz - 14.8), (cx + 6, 134, cz - 14.8), "glow_lamps", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=cg)
    # +x & -x cupola faces
    struct_b.add_quad((cx + 14.4, 122, cz + 10), (cx + 14.4, 122, cz - 10), (cx + 14.4, 136, cz - 10), (cx + 14.4, 136, cz + 10), "detail_post", u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=lv)
    struct_b.add_quad((cx - 14.4, 122, cz - 10), (cx - 14.4, 122, cz + 10), (cx - 14.4, 136, cz + 10), (cx - 14.4, 136, cz - 10), "detail_post", u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=lv)
    struct_b.add_quad((cx + 14.8, 124, cz + 6), (cx + 14.8, 124, cz - 6), (cx + 14.8, 134, cz - 6), (cx + 14.8, 134, cz + 6), "glow_lamps", u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=cg)
    struct_b.add_quad((cx - 14.8, 124, cz - 6), (cx - 14.8, 124, cz + 6), (cx - 14.8, 134, cz + 6), (cx - 14.8, 134, cz - 6), "glow_lamps", u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=cg)
    # Cupola cornice, slate pyramid spire & brass orb finial
    struct_b.add_box(cx - 16.5, 138, cz - 16.5, cx + 16.5, 141, cz + 16.5, "wall_stone")
    struct_b.add_pyramid(cx - 16, cz - 16, cx + 16, cz + 16, 141, cx, 156, cz, "roof_slate")
    struct_b.add_cylinder_y(cx, 155, 163, cz, 1.2, 6, "detail_post", uv_rect_art=det["brass_gold"], cap_top=True)

    # Properly TAPERED Brick Boiler Chimney (NW rear corner: x = -78, z = -124, narrows 14x14 -> 10x10!)
    struct_b.add_frustum(-78, -124, 88, 14, 14, 132, 10, 10, "wall_brick", cap_top=True)
    struct_b.add_box(-84, 132, -130, -72, 135, -118, "wall_stone")
    struct_b.add_cylinder_y(-78, 135, 139, -124, 2.5, 6, "detail_post", uv_rect_art=det["dark_metal"], cap_top=True)

    # 5) FRONT DOORS, TALL CLASSICAL LOBBY WINDOWS (REAL GLAZED OPENINGS!), & SIDE/REAR WINDOWS
    # Recessed double front entrance doors (x in [-16, +16], y in [10, 54], at z = -3.0)
    struct_b.add_quad((-16, 10, -3.0), (16, 10, -3.0), (16, 54, -3.0), (-16, 54, -3.0), "detail_post", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["entry_doors"])
    # Stone doorframe surround & keystone over entrance
    struct_b.add_box(-18, 54, 4, 18, 58, 6.2, "wall_stone")
    struct_b.add_box(-4, 53, 4, 4, 60, 7.0, "wall_stone")
    # Glass fanlight overlay over upper transom so entrance glows warmly at night
    struct_b.add_quad((-14, 44, -2.5), (14, 44, -2.5), (14, 52, -2.5), (-14, 52, -2.5), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))

    # 4 Tall Front Lobby Windows (mounted in the REAL wall openings!) with limestone sills, headers & keystones
    for wx0, wx1, wz_face in [(-96, -74, 0.0), (-48, -30, 4.0), (30, 48, 4.0), (74, 96, 0.0)]:
        # Stone sill + classical header + keystone
        struct_b.add_box(wx0 - 2, 19.5, wz_face - 1, wx1 + 2, 22, wz_face + 2.2, "wall_stone")
        struct_b.add_box(wx0 - 2, 62, wz_face - 1, wx1 + 2, 66, wz_face + 2.0, "wall_stone")
        cxw = 0.5 * (wx0 + wx1)
        struct_b.add_box(cxw - 3, 61, wz_face, cxw + 3, 67.5, wz_face + 2.8, "wall_stone")
        # Transparent alpha-blended multi-pane glass set cleanly in the open reveal
        struct_b.add_quad((wx0, 22, wz_face - 2.0), (wx1, 22, wz_face - 2.0), (wx1, 62, wz_face - 2.0), (wx0, 62, wz_face - 2.0), "glass_windows", u_axis=(1, 0, 0), v_axis=(0, 1, 0))

    # Side & Rear tall federal windows (mounted cleanly on brick exterior with stone sills & headers)
    def add_exterior_window(p0, y0, plane, w, h, facing):
        if facing == "+x":
            struct_b.add_box(plane, y0 - 2.5, p0 - 1.5, plane + 2.0, y0, p0 + w + 1.5, "wall_stone")
            struct_b.add_box(plane, y0 + h, p0 - 1.5, plane + 1.8, y0 + h + 3.5, p0 + w + 1.5, "wall_stone")
            struct_b.add_quad((plane + 0.6, y0, p0 + w), (plane + 0.6, y0, p0), (plane + 0.6, y0 + h, p0), (plane + 0.6, y0 + h, p0 + w), "glass_windows", u_axis=(0, 0, -1), v_axis=(0, 1, 0))
        elif facing == "-x":
            struct_b.add_box(plane - 2.0, y0 - 2.5, p0 - 1.5, plane, y0, p0 + w + 1.5, "wall_stone")
            struct_b.add_box(plane - 1.8, y0 + h, p0 - 1.5, plane, y0 + h + 3.5, p0 + w + 1.5, "wall_stone")
            struct_b.add_quad((plane - 0.6, y0, p0), (plane - 0.6, y0, p0 + w), (plane - 0.6, y0 + h, p0 + w), (plane - 0.6, y0 + h, p0), "glass_windows", u_axis=(0, 0, 1), v_axis=(0, 1, 0))
        elif facing == "-z":
            struct_b.add_box(p0 - 1.5, y0 - 2.5, plane - 2.0, p0 + w + 1.5, y0, plane, "wall_stone")
            struct_b.add_box(p0 - 1.5, y0 + h, plane - 1.8, p0 + w + 1.5, y0 + h + 3.5, plane, "wall_stone")
            struct_b.add_quad((p0 + w, y0, plane - 0.6), (p0, y0, plane - 0.6), (p0, y0 + h, plane - 0.6), (p0 + w, y0 + h, plane - 0.6), "glass_windows", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))

    # 3 tall windows on each side wall
    for wz in (-38, -80, -122):
        add_exterior_window(wz, 24, 108.0, 20, 38, "+x")
        add_exterior_window(wz, 24, -108.0, 20, 38, "-x")
    # Rear sorting-room windows (upper & West rear)
    for wx in (-92, -58):
        add_exterior_window(wx, 26, -144.0, 20, 34, "-z")
    for wx in (-12, 34, 76):
        add_exterior_window(wx, 60, -144.0, 20, 18, "-z")

    # 6) GRAND FRONT ENTRANCE STEPS, CHEEK WALLS, BRONZE GLOBE LANTERNS & 1988 ADA RAMP
    # 3 broad carved Indiana limestone steps descending from entrance landing (y = 10, z = 4..8) to plaza (y = 0, z = 16)
    struct_b.add_box(-26, 0, 4, 26, 10, 8.5, "wall_stone", faces=("+z", "+x", "-x", "+y"))
    struct_b.add_box(-26, 0, 8.5, 26, 6.7, 12.2, "wall_stone", faces=("+z", "+x", "-x", "+y"))
    struct_b.add_box(-26, 0, 12.2, 26, 3.3, 16.0, "wall_stone", faces=("+z", "+x", "-x", "+y"))
    # Low flanking limestone cheek walls & twin bronze globe lantern pedestals
    for cx0, cx1, lx in [(-33, -26, -29.5), (26, 33, 29.5)]:
        struct_b.add_box(cx0, 0, 4, cx1, 14, 17, "wall_stone", faces=("+z", "-z", "+x", "-x", "+y"))
        # Classical bronze pedestal & glowing warm lantern globe + dark_metal stepped cap
        struct_b.add_cylinder_y(lx, 14, 24, 13.5, 1.3, 6, "detail_post", uv_rect_art=det["dark_metal"], cap_top=True)
        struct_b.add_box(lx - 2.4, 24, 11.1, lx + 2.4, 30, 15.9, "glow_lamps", uv_rect_art=glw["warm_lantern"])
        struct_b.add_box(lx - 3.0, 30, 10.5, lx + 3.0, 31.6, 16.5, "detail_post", uv_rect_art=det["dark_metal"])
        struct_b.add_box(lx - 1.6, 31.6, 11.9, lx + 1.6, 33.2, 15.1, "detail_post", uv_rect_art=det["dark_metal"])

    if year == 1988:
        # 1988 Modern East Concrete Accessibility Ramp (rises smoothly from x = 104, y = 0 up to x = 33, y = 10 along z = 4..18!)
        struct_b.add_box(33, 0, 4, 42, 10, 18, "ground_pavement", faces=("+z", "-z", "+x", "-x", "+y"))
        struct_b.add_quad((42, 10, 18), (104, 0, 18), (104, 0, 4), (42, 10, 4), "ground_pavement")
        struct_b.add_polygon([(42, 0, 18), (104, 0, 18), (42, 10, 18)], "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 1, 0))
        struct_b.add_polygon([(104, 0, 4), (42, 0, 4), (42, 10, 4)], "wall_stone", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))
        # Brushed-metal handrail posts & segmented sloped top rail (segments <= 31 art px inside 32x24 dark_metal!)
        for rx in (40, 56, 72, 88, 102):
            ry_base = 10.0 if rx <= 42 else 10.0 * (104.0 - rx) / 62.0
            struct_b.add_box(rx - 0.8, ry_base, 16.2, rx + 0.8, ry_base + 11, 17.8, "detail_post", uv_rect_art=det["dark_metal"])
        for xa, ya, xb, yb in [(40, 20.0, 71, 15.1), (71, 15.1, 102, 10.2)]:
            struct_b.add_quad((xa, ya, 17.8), (xb, yb, 17.8), (xb, yb + 1.2, 17.8), (xa, ya + 1.2, 17.8), "detail_post", uv_rect_art=det["dark_metal"])
            struct_b.add_quad((xb, yb, 16.2), (xa, ya, 16.2), (xa, ya + 1.2, 16.2), (xb, yb + 1.2, 16.2), "detail_post", uv_rect_art=det["dark_metal"])
        coll.add("accessibility_ramp", 33, 0, 4, 104, 21, 18)

    # 7) REAR MAIL LOADING DOCK, FREIGHT BAY DOOR, EMPLOYEE DOOR & CANOPY
    # Concrete raised loading platform (x in [-24, +72], z in [-156, -144], y = 0..10)
    struct_b.add_box(-24, 0, -156, 72, 10, -144, "ground_pavement", faces=("-z", "+x", "-x", "+y"))
    # Rear employee steps beside door_back (x in [-24, -10], z = -156..-144) — cleanly accessible at z = -158!
    struct_b.add_box(-10, 0, -160, 10, 5.0, -156, "wall_stone", faces=("-z", "+x", "-x", "+y"))
    # Rear employee door centered at x = 0 (aligns directly with door_back!)
    add_tiled_box_y(struct_b, -9, 10, -145, 9, 42, -144, "detail_post", det["dark_metal"], faces=("-z",))
    struct_b.add_box(-11, 42, -146, 11, 45, -144, "wall_stone")
    # Warm rear loading dock sconce light
    struct_b.add_box(-2.5, 46, -147.5, 2.5, 50, -144, "glow_lamps", uv_rect_art=glw["warm_lantern"])
    # Rear mail-truck freight bay overhead door (x in [18, 66], y = 10..52, 48x42 exact fit inside loading_door 48x44!)
    struct_b.add_quad((66, 10, -144.6), (18, 10, -144.6), (18, 52, -144.6), (66, 52, -144.6), "detail_post", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["loading_door"])
    # Rear 'MAIL DOCK / AUTHORIZED' notice sign cleanly to the West of rear door (x in [-94, -30]? Wait: rear windows are at -92..-72 & -58..-38; put notice cleanly on dock canopy fascia or rear right wall x in [74, 106]? Better: 64x24 fits cleanly on the rear wall below upper windows OR shrink? Let's place it cleanly at x in [12, 76], y = 55..79 -> wait: upper windows at 34..54 y=60..78! Put it on the West rear wall at x in [-96, -32], y = 62..82 (20 tall <= 24) cleanly ABOVE the rear West windows (y = 26..60)!! Zero overlap!)
    struct_b.add_quad((-32, 62, -144.6), (-96, 62, -144.6), (-96, 84, -144.6), (-32, 84, -144.6), "sign_civic", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=sgn["loading_sign"])

    if year == 1938:
        # 1938 Modest cantilevered dark-metal & timber loading dock canopy (z = -158..-144, y = 52..56)
        struct_b.add_box(-24, 52, -158, 72, 56, -144, "roof_cap")
        for px in (-20, 70):
            add_tiled_box_y(struct_b, px - 1.5, 10, -156, px + 1.5, 52, -153, "detail_post", det["dark_metal"])
        coll.add("rear_loading_dock", -24, 0, -158, 72, 56, -144)
    else:
        # 1988 Expanded deep mail-truck canopy (z = -168..-144, y = 52..57) + rubber dock bumpers + rooftop HVAC!
        struct_b.add_box(-26, 52, -168, 74, 57, -144, "roof_cap")
        for px in (-22, 16, 70):
            add_tiled_box_y(struct_b, px - 1.8, 0 if px > 0 else 10, -166, px + 1.8, 52, -162.5, "detail_post", det["dark_metal"])
        # Black rubber dock bumpers
        for bx in (20, 62):
            struct_b.add_box(bx, 5, -158, bx + 4, 11, -156, "detail_post", uv_rect_art=det["dark_metal"])
        coll.add("rear_loading_canopy_1988", -26, 0, -168, 74, 57, -144)

        # 1988 Rooftop HVAC condenser units tucked cleanly behind the rear parapet (x = +58..+92, z = -132..-106)
        for hx in (56, 78):
            struct_b.add_box(hx, 88, -134, hx + 18, 102, -114, "detail_post", uv_rect_art=det["dark_metal"])
            struct_b.add_quad((hx + 16, 91, -134.4), (hx + 2, 91, -134.4), (hx + 2, 100, -134.4), (hx + 16, 100, -134.4), "detail_post", u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["louver_vent"])
        coll.add("rooftop_hvac_1988", 56, 88, -134, 96, 102, -114)

    # 8) CLEAN INTERIOR LOBBY SHELL + SIGNATURE 1930s WPA MURAL!
    # Interior lobby spans: x in [-102, +102], z in [-84, -6], y in [10, 70]
    # Terrazzo/pavement lobby floor at y = 10.2
    struct_b.add_quad((-102, 10.2, -6), (102, 10.2, -6), (102, 10.2, -84), (-102, 10.2, -84), "ground_pavement", u_axis=(1, 0, 0), v_axis=(0, 0, -1))
    # Clean plaster ceiling at y = 70
    struct_b.add_quad((-102, 70, -84), (102, 70, -84), (102, 70, -6), (-102, 70, -6), "wall_stone", u_axis=(1, 0, 0), v_axis=(0, 0, 1))
    # Interior West & East tiled plaster+marble wainscot walls
    add_tiled_quad_wall(struct_b, -101.8, 10, -84, -101.8, 70, -6, "+x", "detail_post", det["interior_wall"])
    add_tiled_quad_wall(struct_b, 101.8, 10, -84, 101.8, 70, -6, "-x", "detail_post", det["interior_wall"])
    # Interior front wall inner plaster surround (facing -z around windows/doorway)
    struct_b.add_quad((102, 10, -6.2), (-102, 10, -6.2), (-102, 22, -6.2), (102, 22, -6.2), "wall_stone", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))
    struct_b.add_quad((102, 62, -6.2), (-102, 62, -6.2), (-102, 70, -6.2), (102, 70, -6.2), "wall_stone", u_axis=(-1, 0, 0), v_axis=(0, 1, 0))

    # Built-in West Lobby P.O. Box Bank (x = -100.8, z in [-74, -18], y = 20..52, tiled cleanly with 32x16 po_boxes!)
    add_tiled_quad_wall(struct_b, -101.0, 20, -76, -101.0, 52, -18, "+x", "detail_post", det["po_boxes"])
    # Walnut surround trim framing P.O. box bank
    struct_b.add_box(-101.5, 18, -77, -99.5, 20, -17, "detail_post", uv_rect_art=det["wood_trim"])
    struct_b.add_box(-101.5, 52, -77, -99.5, 54, -17, "detail_post", uv_rect_art=det["wood_trim"])
    # Small historic 'WPA 1938' / 'ZIP MAIL' plaque mounted cleanly on East interior wall (x = 101.0, z in [-64, -24], y = 34..48)
    struct_b.add_quad((101.0, 34, -24), (101.0, 34, -64), (101.0, 48, -64), (101.0, 48, -24), "sign_civic", u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=sgn["mail_plaque"])

    # Built-in Architectural Postal Teller Counter Partition on North Lobby Wall (z = -83.5, y = 10..34)
    # Tiled cleanly in 3 bays of EXACT 64x24 (matching counter_front 64x24!) across x in [-96, +96]
    for cx0 in (-96, -32, 32):
        struct_b.add_quad((cx0, 10, -83.5), (cx0 + 64, 10, -83.5), (cx0 + 64, 34, -83.5), (cx0, 34, -83.5), "detail_post", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=det["counter_front"])
    # Protruding marble/walnut counter writing ledge aligned right on the marble band (y = 21..23, z = -83.5..-79.0)
    struct_b.add_box(-98, 21, -83.5, 98, 23.0, -79.0, "wall_stone", faces=("+z", "+x", "-x", "+y", "-y"))
    # Flanking plaster wall panels on North wall beside mural (x in [-102, -64] & [+64, +102], y = 34..70)
    add_tiled_quad_wall(struct_b, -102, 34, -83.5, -64, 70, -83.5, "+z", "detail_post", det["interior_wall"])
    add_tiled_quad_wall(struct_b, 64, 34, -83.5, 102, 70, -83.5, "+z", "detail_post", det["interior_wall"])

    # *** THE SIGNATURE FULL-WIDTH 1930s WPA LOBBY MURAL (x in [-64, +64], y in [34, 70], z = -82.8) ***
    # Exact 128 x 36 art px = 256 x 72 texels = 9.93 m x 2.79 m -> 0.000000 m UV error!
    struct_b.add_quad((-64, 34, -82.8), (64, 34, -82.8), (64, 70, -82.8), (-64, 70, -82.8), "mural_wpa", u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=[0, 0, 128, 36])
    # Carved walnut protruding architectural frame molding around the mural
    add_tiled_box_x(struct_b, -66, 32.5, -83.5, 66, 34.2, -82.0, "detail_post", det["wood_trim"])
    add_tiled_box_x(struct_b, -66, 69.2, -83.5, 66, 70.5, -82.0, "detail_post", det["wood_trim"])
    add_tiled_box_y(struct_b, -66, 34, -83.5, -64, 70, -82.0, "detail_post", det["wood_trim"])
    add_tiled_box_y(struct_b, 64, 34, -83.5, 66, 70, -82.0, "detail_post", det["wood_trim"])

    # Warm Lobby Ceiling Pendant Globes (flanking the mural cleanly so center sightline is 100% unobstructed!)
    for px in (-52, 52):
        struct_b.add_cylinder_y(px, 65, 70, -44, 0.7, 4, "detail_post", uv_rect_art=det["brass_gold"], cap_top=False)
        struct_b.add_box(px - 2.6, 60.5, -46.6, px + 2.6, 65, -41.4, "glow_lamps", uv_rect_art=glw["warm_lantern"])

    # Full-height collision boxes for Post Office structure
    coll.add("post_office_main_body", -108, 0, -144, 108, 96, 4)
    coll.add("classical_pediment", -66, 92, -6, 66, 117, 8.2)
    coll.add("hipped_slate_roof", -98, 88, -132, 98, 112, -10)
    coll.add("rooftop_civic_cupola", -17, 112, -88, 17, 163, -54)
    coll.add("tapered_boiler_chimney", -85, 88, -131, -71, 139, -117)
    coll.add("front_steps_and_cheeks", -33, 0, 4, 33, 33, 17)


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
        coll, year, det, sgn, glw,
    )
    build_post_office_structure(buckets["structure"], coll, year, det, sgn, glw)

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
        {"name": "post_office", "children": [1, 2, 3, 4, 5]},
        {"name": "structure", "mesh": mesh_idx_by_name["structure"]},
        {"name": "lot", "mesh": mesh_idx_by_name["lot"], "children": lot_children},
        {"name": "collision", "mesh": mesh_idx_by_name["collision"], "extras": {"collision_only": True}},
        {
            "name": "door_main",
            "translation": [0.0, 0.0, round(18.0 * U, 6)],
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
        "asset": {"version": "2.0", "generator": f"Small-Town WPA Post Office Builder ({year})"},
        "scene": 0,
        "scenes": [{"name": f"post_office_{year}", "nodes": [0]}],
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
            "coordinate_note": "Full-height glTF Y-up boxes in metres from ground y=0 to cupola finial top.",
            "boxes_gltf_y_up": coll.boxes,
        },
    }


def main():
    states_info = [build_state(spec) for spec in STATE_SPECS]
    model_info = {
        "name": "Small-Town WPA Post Office (1938 Federal Building / 1988 Modernized Branch)",
        "kind": "civic_glb_post_office",
        "role": "post_office",
        "room": "postal_lobby",
        "built_year": 1938,
        "era": {"from": 1938, "to": 2026, "built": 1938},
        "texel_size_m": T,
        "art_pixel_m": U,
        "interior_features": {
            "wpa_mural": {
                "file": "textures/wpa_mural.png",
                "title": "The Mail Unites Farm, Rail, and Town (1938)",
                "dimensions_m": [round(128 * U, 4), round(36 * U, 4)],
                "wall_location_gltf_m": [0.0, round(51 * U, 4), round(-82.8 * U, 4)],
            },
            "teller_counter_z_m": round(-83.5 * U, 4),
            "po_box_bank_wall": "West lobby wall",
        },
        "footprint_m": {
            "lot_width": round(344 * U, 4),
            "lot_depth": round(316 * U, 4),
            "building_width": round(216 * U, 4),
            "building_depth": round(144 * U, 4),
            "front_plaza_depth": round(80 * U, 4),
            "height_cornice": round(88 * U, 4),
            "height_pediment": round(117 * U, 4),
            "height_cupola_finial": round(163 * U, 4),
        },
        "doors": {
            "door_main": {
                "translation_gltf": [0.0, 0.0, round(18.0 * U, 4)],
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
    print("Wrote model-info.json for WPA Post Office (1938 & 1988 states).")


if __name__ == "__main__":
    main()
