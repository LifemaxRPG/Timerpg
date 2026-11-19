#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) generator for THE FOUNDRY — Adaptive-Reuse Industrial-Brutalist
Sawtooth-Roof Modern Nightclub & Open-Air Courtyard Venue.

Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- 0.0388 m/texel (2x2 art pixel blocks -> 1 art pixel U = 0.0776 m)
- 12 materials: 7 shared LimeZu swatches + 5 bespoke industrial-brutalist textures
- NEAREST magnification (9728), NEAREST_MIPMAP_NEAREST minification (9984), REPEAT wrap (10497)
- Zero UV stretch (<= 1 mm error on every edge, including pitched sawtooth roofs & gables)
- Complete 360-degree exterior + walkable warehouse main hall with 360-degree center DJ island,
  elevated rear reclaimed-timber VIP mezzanine, east craft bar, and west coral-leather booths
- Door empties: door_main (+Z), door_back (-Z), door_side (-X)
- Prop sub-groups (< 500 tris each): street_gantry, courtyard_lounge, service_yard
"""
import json
import math
import struct
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
ART_REGIONS = json.loads((R / "art-regions.json").read_text(encoding="utf-8"))

T = 0.0388       # 1 texel in meters
U = 0.0776       # 1 art pixel (2x2 texels) in meters


def read_png_size(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    return w, h


MATERIAL_DEFS = [
    {"name": "wall_brick", "file": "brick_red.png", "rough": 0.90, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "wall_concrete", "file": "wall_slate_light.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "wall_block", "file": "block_courses.png", "rough": 0.92, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "roof_sawtooth", "file": "roof_slate.png", "rough": 0.82, "metal": 0.10, "alpha": "OPAQUE"},
    {"name": "deck_timber", "file": "plank_horizontal.png", "rough": 0.80, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "ground_asphalt", "file": "asphalt_worn.png", "rough": 0.94, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "ground_concrete", "file": "pavement.png", "rough": 0.84, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "wall_steel", "file": "corten_steel.png", "rough": 0.56, "metal": 0.35, "alpha": "OPAQUE"},
    {"name": "wall_trim", "file": "concrete_rib.png", "rough": 0.78, "metal": 0.08, "alpha": "OPAQUE"},
    {"name": "glass_industrial", "file": "glass.png", "rough": 0.14, "metal": 0.05, "alpha": "BLEND", "doubleSided": True},
    {"name": "glow_laser", "file": "led_industrial.png", "rough": 0.28, "metal": 0.0, "alpha": "OPAQUE", "emissive": [1.0, 1.0, 1.0]},
    {"name": "detail_utility", "file": "utility.png", "rough": 0.68, "metal": 0.15, "alpha": "OPAQUE"},
    # (the hall's LED floor, light wall, bar and truss lamps: the same picture, not emissive; the town lights only what
    # shows outside, and a glowing ceiling would become a real spot light inside a closed shell)
    {"name": "detail_led", "file": "led_industrial.png", "rough": 0.28, "metal": 0.0, "alpha": "OPAQUE"},
]

MAT_IDX = {m["name"]: i for i, m in enumerate(MATERIAL_DEFS)}
MAT_SIZE = {}
for m in MATERIAL_DEFS:
    MAT_SIZE[m["name"]] = read_png_size(TEX_DIR / m["file"])


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
    """Collects geometry partitioned by material index for one glTF node."""

    def __init__(self, name):
        self.name = name
        self.prims = {}  # mat_idx -> {"pos": [], "nrm": [], "uv": [], "idx": []}

    def _prim(self, mat_name):
        mi = MAT_IDX[mat_name]
        if mi not in self.prims:
            self.prims[mi] = {"pos": [], "nrm": [], "uv": [], "idx": []}
        return self.prims[mi]

    def add_polygon(self, pts_art, mat_name, u_axis=None, v_axis=None, uv_rect_art=None):
        """
        Adds a convex polygon specified in art-pixel coordinates (x, y, z) in CCW outward order.
        Computes exact 1:1 texel-accurate UVs using an orthonormal tangent basis (e_u, e_v).
        """
        pts_m = [(p[0] * U, p[1] * U, p[2] * U) for p in pts_art]
        n = v_norm(v_cross(v_sub(pts_m[1], pts_m[0]), v_sub(pts_m[2], pts_m[0])))
        W, H = MAT_SIZE[mat_name]

        if u_axis is None:
            e_u = v_norm(v_sub(pts_m[1], pts_m[0]))
        else:
            e_u = v_norm(u_axis)
        if v_axis is None:
            e_v = v_norm(v_cross(n, e_u))
        else:
            e_v = v_norm(v_axis)

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
                u = v_dot(p, e_u) / (W * T)
                v = -v_dot(p, e_v) / (H * T)
                prim["pos"].append(p)
                prim["nrm"].append(n)
                prim["uv"].append((u, v))

        for i in range(1, len(pts_m) - 1):
            prim["idx"].extend((base, base + i, base + i + 1))

    def add_quad_art(self, pts_art, mat_name, region_name=None, u_axis=None, v_axis=None):
        """Adds an exact 4-corner quad mapped to a named region in art-regions.json."""
        rect = ART_REGIONS[region_name]["rect_art"] if region_name else None
        self.add_polygon(pts_art, mat_name, u_axis=u_axis, v_axis=v_axis, uv_rect_art=rect)

    def add_box(self, x0, y0, z0, x1, y1, z1, mat_name, faces="all", region_name=None):
        """Adds an axis-aligned box in art-pixel coordinates. faces can be 'all' or iterable of +x,-x,+y,-y,+z,-z."""
        if faces == "all":
            fset = {"+x", "-x", "+y", "-y", "+z", "-z"}
        else:
            fset = set(faces)
        rect = ART_REGIONS[region_name]["rect_art"] if region_name else None

        if "+z" in fset:
            self.add_polygon(
                [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
                mat_name, u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect,
            )
        if "-z" in fset:
            self.add_polygon(
                [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)],
                mat_name, u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect,
            )
        if "+x" in fset:
            self.add_polygon(
                [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)],
                mat_name, u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=rect,
            )
        if "-x" in fset:
            self.add_polygon(
                [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
                mat_name, u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=rect,
            )
        if "+y" in fset:
            self.add_polygon(
                [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)],
                mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=rect,
            )
        if "-y" in fset:
            self.add_polygon(
                [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
                mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, 1), uv_rect_art=rect,
            )

    def add_panel_z(self, x0, y0, x1, y1, z, facing, mat_name, region_name):
        if facing == "+z":
            self.add_quad_art([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)],
                              mat_name, region_name, u_axis=(1, 0, 0), v_axis=(0, 1, 0))
        else:
            self.add_quad_art([(x1, y0, z), (x0, y0, z), (x0, y1, z), (x1, y1, z)],
                              mat_name, region_name, u_axis=(-1, 0, 0), v_axis=(0, 1, 0))

    def add_panel_x(self, z0, y0, z1, y1, x, facing, mat_name, region_name):
        if facing == "+x":
            self.add_quad_art([(x, y0, z1), (x, y0, z0), (x, y1, z0), (x, y1, z1)],
                              mat_name, region_name, u_axis=(0, 0, -1), v_axis=(0, 1, 0))
        else:
            self.add_quad_art([(x, y0, z0), (x, y0, z1), (x, y1, z1), (x, y1, z0)],
                              mat_name, region_name, u_axis=(0, 0, 1), v_axis=(0, 1, 0))

    def add_panel_y(self, x0, z0, x1, z1, y, facing, mat_name, region_name):
        if facing == "+y":
            self.add_quad_art([(x0, y, z1), (x1, y, z1), (x1, y, z0), (x0, y, z0)],
                              mat_name, region_name, u_axis=(1, 0, 0), v_axis=(0, 0, -1))
        else:
            self.add_quad_art([(x0, y, z0), (x1, y, z0), (x1, y, z1), (x0, y, z1)],
                              mat_name, region_name, u_axis=(1, 0, 0), v_axis=(0, 0, 1))

    def tri_count(self):
        return sum(len(p["idx"]) // 3 for p in self.prims.values())


class CollisionRecorder:
    def __init__(self):
        self.bucket = MeshBucket("collision")
        self.boxes = []

    def add_box(self, x0, y0, z0, x1, y1, z1, source, gate_node=None):
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "wall_concrete")
        cx = (x0 + x1) * 0.5 * U
        cy_g = (y0 + y1) * 0.5 * U
        cz_g = (z0 + z1) * 0.5 * U
        sx = (x1 - x0) * U
        sy_g = (y1 - y0) * U
        sz_g = (z1 - z0) * U
        entry = {
            "source": source,
            "center": [round(cx, 4), round(-cz_g, 4), round(cy_g, 4)],
            "size": [round(sx, 4), round(sz_g, 4), round(sy_g, 4)],
        }
        if gate_node:
            entry["gate_node"] = gate_node
        self.boxes.append(entry)


def build_scene():
    lot = MeshBucket("lot")
    structure = MeshBucket("structure")
    street_gantry = MeshBucket("street_gantry")
    courtyard_lounge = MeshBucket("courtyard_lounge")
    service_yard = MeshBucket("service_yard")
    coll = CollisionRecorder()

    # ==========================================================================
    # 1. LOT PAVING (368 x 384 art px = 28.56 m x 29.80 m, x in [-184, 184], z in [-304, 80])
    # ==========================================================================
    # Front sidewalk & open-air courtyard polished concrete slab
    lot.add_box(-184, 0, 0, 184, 2, 80, "ground_concrete", faces=["+y", "+z", "-x", "+x"])
    # Side & rear worn industrial load-in asphalt drives
    lot.add_box(-184, 0, -304, -128, 2, 0, "ground_asphalt", faces=["+y", "-x", "-z"])
    lot.add_box(128, 0, -304, 184, 2, 0, "ground_asphalt", faces=["+y", "+x", "-z"])
    lot.add_box(-128, 0, -304, 128, 2, -232, "ground_asphalt", faces=["+y", "-z"])
    # Main warehouse hall interior polished concrete floor slab (y = 2)
    lot.add_box(-128, 0, -232, 128, 2, 0, "ground_concrete", faces=["+y"])

    # Concrete lot border curbs (west & east driveway edge markers)
    lot.add_box(-184, 2, -300, -180, 5, 76, "wall_trim", faces=["+y", "+x", "-x", "+z", "-z"])
    lot.add_box(180, 2, -300, 184, 5, 76, "wall_trim", faces=["+y", "+x", "-x", "+z", "-z"])
    lot.add_box(-180, 2, -304, 180, 5, -300, "wall_trim", faces=["+y", "+z", "-z"])

    # Subtle industrial entry threshold runner from street gantry straight to main warehouse doors (2 slabs <= 64 art px)
    lot.add_box(-26, 2, 2, 26, 2.25, 39, "detail_utility", faces=["+y"], region_name="concrete_dark")
    lot.add_box(-26, 2, 39, 26, 2.25, 76, "detail_utility", faces=["+y"], region_name="concrete_dark")

    # ==========================================================================
    # 2. MAIN WAREHOUSE SHELL (x in [-128, 128], z in [-232, 0], eave y = 56)
    # ==========================================================================
    # --- FRONT HERITAGE RED-BRICK FACADE (z in [-4, 0], y in [2, 56]) ---
    # Left brick wall around front_left window (x in [-98, -26], y in [10, 42])
    structure.add_box(-104, 2, -4, -98, 56, 0, "wall_brick")
    structure.add_box(-98, 2, -4, -26, 10, 0, "wall_brick")
    structure.add_box(-98, 42, -4, -26, 56, 0, "wall_brick")
    structure.add_box(-26, 2, -4, -16, 56, 0, "wall_brick")
    # Entry portal header above transom (x in [-16, 16], y in [50, 56])
    structure.add_box(-16, 50, -4, 16, 56, 0, "wall_steel")
    # Right brick wall around front_right window (x in [36, 108], y in [10, 42])
    structure.add_box(16, 2, -4, 36, 56, 0, "wall_brick")
    structure.add_box(36, 2, -4, 108, 10, 0, "wall_brick")
    structure.add_box(36, 42, -4, 108, 56, 0, "wall_brick")
    structure.add_box(108, 2, -4, 128, 56, 0, "wall_brick")

    # Blackened-steel structural facade pilasters & concrete lintels
    for px0, px1 in ((-104, -100), (-26, -22), (22, 26), (120, 128)):
        structure.add_box(px0, 2, 0, px1, 56, 2, "wall_steel", faces=["+z", "+x", "-x", "+y"])
    # Entry surround frame (heavy blackened steel portal framing door_main)
    structure.add_box(-22, 2, 0, -16, 54, 3, "wall_steel", faces=["+z", "+x", "-x", "+y"])
    structure.add_box(16, 2, 0, 22, 54, 3, "wall_steel", faces=["+z", "+x", "-x", "+y"])
    structure.add_box(-26, 50, 0, 26, 63, 4, "wall_steel", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    # MAIN HALL glowing lintel badge (48x12) centered cleanly above entry doors
    structure.add_panel_z(-24, 50.5, 24, 62.5, 4.3, "+z", "glow_laser", "entry_badge")
    # Recessed downward entry LED lamp under portal header (ny = -1 -> Filament light!)
    structure.add_panel_y(-8, 0.2, 8, 4.2, 49.85, "-y", "glow_laser", "lamp")

    # Front Crittall glass windows & double steel warehouse doors (BLEND glass)
    structure.add_panel_z(-98, 10, -26, 42, -1.6, "+z", "glass_industrial", "front_left")
    structure.add_panel_z(36, 10, 108, 42, -1.6, "+z", "glass_industrial", "front_right")
    structure.add_panel_z(-16, 2, 16, 38, -0.4, "+z", "glass_industrial", "entry")
    structure.add_panel_z(-16, 38, 16, 50, -0.4, "+z", "glass_industrial", "transom")

    # Concrete window sills & steel lintels on front facade
    structure.add_box(-99, 8, 0, -25, 10, 2, "wall_trim", faces=["+z", "+y", "-y", "+x", "-x"])
    structure.add_box(-99, 42, 0, -25, 44, 2, "wall_steel", faces=["+z", "+y", "-y", "+x", "-x"])
    structure.add_box(35, 8, 0, 109, 10, 2, "wall_trim", faces=["+z", "+y", "-y", "+x", "-x"])
    structure.add_box(35, 42, 0, 109, 44, 2, "wall_steel", faces=["+z", "+y", "-y", "+x", "-x"])

    # Front & rear horizontal roof gutter parapets (y in [56, 62])
    structure.add_box(-104, 56, -8, 128, 62, 0, "wall_trim", faces=["+z", "-z", "+y", "+x", "-x"])
    structure.add_box(-128, 56, -232, 128, 62, -224, "wall_trim", faces=["+z", "-z", "+y", "+x", "-x"])

    # --- WEST ELEVATION (x in [-128, -124], z in [-232, -4], y in [2, 56]) ---
    # Segments leaving openings for side_win (z in [-80, -32], y in [14, 46]),
    # door_side (z in [-112, -88], y in [2, 34]), and vip_win (z in [-176, -128], y in [18, 42])
    structure.add_box(-128, 2, -32, -124, 56, -4, "wall_brick")
    structure.add_box(-128, 2, -80, -124, 14, -32, "wall_brick")
    structure.add_box(-128, 46, -80, -124, 56, -32, "wall_brick")
    structure.add_box(-128, 2, -88, -124, 56, -80, "wall_brick")
    structure.add_box(-128, 34, -112, -124, 56, -88, "wall_brick")
    structure.add_box(-128, 2, -128, -124, 56, -112, "wall_brick")
    structure.add_box(-128, 2, -176, -124, 18, -128, "wall_brick")
    structure.add_box(-128, 42, -176, -124, 56, -128, "wall_brick")
    structure.add_box(-128, 2, -232, -124, 56, -176, "wall_brick")
    # West Crittall glass windows & side door frame
    structure.add_panel_x(-80, 14, -32, 46, -126.4, "-x", "glass_industrial", "side_win")
    structure.add_panel_x(-176, 18, -128, 42, -126.4, "-x", "glass_industrial", "vip_win")
    structure.add_box(-130, 2, -114, -128, 36, -111, "wall_steel", faces=["-x", "+z", "-z", "+y"])
    structure.add_box(-130, 2, -89, -128, 36, -86, "wall_steel", faces=["-x", "+z", "-z", "+y"])
    structure.add_box(-132, 34, -116, -128, 38, -84, "wall_steel", faces=["-x", "+z", "-z", "+y", "-y"])
    # West side steel exit door leaf (recessed at x = -126)
    structure.add_box(-127, 2, -111, -125, 34, -89, "wall_steel", faces=["-x", "+x"])
    # West structural brick/steel pilasters
    for pz0, pz1 in ((-86, -82), (-124, -120), (-184, -180), (-232, -226)):
        structure.add_box(-130, 2, pz0, -128, 56, pz1, "wall_steel", faces=["-x", "+z", "-z", "+y"])

    # --- EAST ELEVATION (x in [124, 128], z in [-232, -4], y in [2, 56]) ---
    structure.add_box(124, 2, -32, 128, 56, -4, "wall_brick")
    structure.add_box(124, 2, -80, 128, 14, -32, "wall_brick")
    structure.add_box(124, 46, -80, 128, 56, -32, "wall_brick")
    structure.add_box(124, 2, -128, 128, 56, -80, "wall_brick")
    structure.add_box(124, 2, -176, 128, 18, -128, "wall_brick")
    structure.add_box(124, 42, -176, 128, 56, -128, "wall_brick")
    structure.add_box(124, 2, -232, 128, 56, -176, "wall_brick")
    structure.add_panel_x(-80, 14, -32, 46, 126.4, "+x", "glass_industrial", "side_win")
    structure.add_panel_x(-176, 18, -128, 42, 126.4, "+x", "glass_industrial", "vip_win")
    for pz0, pz1 in ((-28, -24), (-86, -82), (-124, -120), (-184, -180), (-232, -226)):
        structure.add_box(128, 2, pz0, 130, 56, pz1, "wall_steel", faces=["+x", "+z", "-z", "+y"])

    # --- REAR ELEVATION (z in [-232, -228], x in [-124, 124], y in [2, 56]) ---
    # Acoustic concrete blockcourses with artist door opening (door_back at x in [40, 64], y in [2, 34])
    structure.add_box(-124, 2, -232, 40, 56, -228, "wall_block")
    structure.add_box(40, 34, -232, 64, 56, -228, "wall_block")
    structure.add_box(64, 2, -232, 124, 56, -228, "wall_block")
    # Rear artist door steel leaf
    structure.add_box(40, 2, -231, 64, 34, -229, "wall_steel", faces=["-z", "+z"])

    # ==========================================================================
    # 3. 3-BAY PITCHED SAWTOOTH SKYLIGHT ROOF (y in [56, 76], z in [-224, -8])
    # ==========================================================================
    # Full-tiling dark slate industrial warehouse ceiling deck at y = 54..56
    structure.add_box(-124, 54, -228, 124, 56, -4, "roof_sawtooth", faces=["-y", "+y"])

    sawtooth_bays = [
        (-80, -8),     # Front bay (z_back, z_front)
        (-152, -80),   # Middle bay
        (-224, -152),  # Rear bay
    ]
    for z_back, z_front in sawtooth_bays:
        # Dark slate acoustic backing baffle behind north-light clerestory glass so glass pops richly
        structure.add_box(-126, 56, z_front - 3, 126, 74, z_front - 1.5, "roof_sawtooth", faces=["+z"])
        # Steel sill & ridge coping on front (+Z) vertical clerestory face
        structure.add_box(-128, 56, z_front - 1, 128, 57.5, z_front + 0.5, "wall_steel", faces=["+z", "+y", "+x", "-x"])
        structure.add_box(-128, 73.5, z_front - 3, 128, 76, z_front + 1, "wall_steel",
                          faces=["+z", "-z", "+y", "-y", "+x", "-x"])
        # Full-width north-light Crittall clerestory glazing (two 128x16 panels spanning x in [-128, 128])
        structure.add_panel_z(-128, 57.5, 0, 73.5, z_front, "+z", "glass_industrial", "clerestory")
        structure.add_panel_z(0, 57.5, 128, 73.5, z_front, "+z", "glass_industrial", "clerestory")

        # Sloped sawtooth standing-seam slate roof plane sloping down from (y=75, z_front-2) to (y=56, z_back)
        # CCW outward normal pointing (+Y, -Z):
        # v0=(-128, 75, z_front-2), v1=(128, 75, z_front-2), v2=(128, 56, z_back), v3=(-128, 56, z_back)
        # Check cross((256,0,0), (256,-19,-70)) = (0, 256*70, -256*19) -> +Y and -Z! Outward!
        structure.add_polygon(
            [
                (-128, 75, z_front - 2),
                (128, 75, z_front - 2),
                (128, 56, z_back),
                (-128, 56, z_back),
            ],
            "roof_sawtooth",
            u_axis=(1, 0, 0),
        )

        # Triangular red-brick sawtooth gable end walls at x = -128 (-X) and x = +128 (+X)
        # Left (-X): v0=(-128, 56, z_back), v1=(-128, 56, z_front), v2=(-128, 75, z_front)
        # e1=(0,0,+72), e2=(0,+19,+72) -> e1 x e2 = (-1368, 0, 0) -> -X outward!
        structure.add_polygon(
            [(-128, 56, z_back), (-128, 56, z_front), (-128, 75, z_front)],
            "wall_brick",
            u_axis=(0, 0, 1),
            v_axis=(0, 1, 0),
        )
        # Right (+X): v0=(128, 56, z_front), v1=(128, 56, z_back), v2=(128, 75, z_front)
        # e1=(0,0,-72), e2=(0,+19,0) -> e1 x e2 = (+1368, 0, 0) -> +X outward!
        structure.add_polygon(
            [(128, 56, z_front), (128, 56, z_back), (128, 75, z_front)],
            "wall_brick",
            u_axis=(0, 0, -1),
            v_axis=(0, 1, 0),
        )
        # Thin sloped steel fascia bargeboards along west and east sawtooth gable rakes
        # West rake strip (x in [-129.2, -128])
        structure.add_polygon(
            [(-129.2, 56, z_back), (-128, 56, z_back), (-128, 75.5, z_front), (-129.2, 75.5, z_front)],
            "wall_steel",
        )
        # East rake strip (x in [128, 129.2])
        structure.add_polygon(
            [(128, 56, z_back), (129.2, 56, z_back), (129.2, 75.5, z_front), (128, 75.5, z_front)],
            "wall_steel",
        )

    # ==========================================================================
    # 4. BRUTALIST VERTICAL CORNER LIGHT TOWER (West-Front Corner, max y = 148 = 11.48 m)
    # ==========================================================================
    # Raw architectural concrete monolith shaft (x in [-134, -104], z in [-28, 4], y in [2, 114])
    structure.add_box(-134, 2, -28, -104, 114, 4, "wall_concrete")
    # Fluted brutalist horizontal shadow-reveal collars around the tower
    for cy0, cy1 in ((2, 10), (40, 44), (110, 114)):
        structure.add_box(-135, cy0, -29, -103, cy1, 5, "wall_trim", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    # Vertical blackened-steel architectural corner fins on the tower
    for fx0, fx1, fz0, fz1 in (
        (-135, -133, 3, 5),
        (-105, -103, 3, 5),
        (-135, -133, -29, -27),
        (-105, -103, -29, -27),
    ):
        structure.add_box(fx0, 10, fz0, fx1, 110, fz1, "wall_steel", faces=["+z", "-z", "+x", "-x"])

    # Vertical Tokyo/Berlin-style "FOUNDRY" Blade Sign (24x64) on front (+Z) & west (-X) tower faces
    structure.add_box(-131.5, 45.5, 4, -106.5, 110.5, 5.2, "wall_steel", faces=["+z", "+x", "-x", "+y", "-y"])
    structure.add_panel_z(-131, 46, -107, 110, 5.6, "+z", "glow_laser", "tower_blade")
    structure.add_box(-135.2, 45.5, -24.5, -134, 110.5, 0.5, "wall_steel", faces=["-x", "+z", "-z", "+y", "-y"])
    structure.add_panel_x(-24, 46, 0, 110, -135.6, "-x", "glow_laser", "tower_blade")

    # Illuminated Architectural Light-Cube Crown (32x32 beacon_panel on all 4 sides, y in [114, 146])
    structure.add_box(-135, 114, -28, -103, 146, 4, "wall_steel", faces=["+z", "-z", "+x", "-x"])
    structure.add_panel_z(-135, 114, -103, 146, 4.4, "+z", "glow_laser", "beacon_panel")
    structure.add_panel_z(-135, 114, -103, 146, -28.4, "-z", "glow_laser", "beacon_panel")
    structure.add_panel_x(-28, 114, 4, 146, -135.4, "-x", "glow_laser", "beacon_panel")
    structure.add_panel_x(-28, 114, 4, 146, -102.6, "+x", "glow_laser", "beacon_panel")
    # Blackened-steel crown coping cap (max height = 148 art px = 11.4848 m <= 12.0 m)
    structure.add_box(-136, 146, -29, -102, 148, 5, "wall_steel")

    # ==========================================================================
    # 5. PROP GROUP 1: MONUMENTAL STREET-LINE ENTRY GANTRY ARCH (< 500 tris)
    # ==========================================================================
    # Twin brutalist concrete & blackened-steel piers flanking the open 96-art-px-wide arrival plaza
    for px0, px1 in ((-64, -48), (48, 64)):
        street_gantry.add_box(px0, 2, 48, px1, 50, 60, "wall_concrete")
        street_gantry.add_box(px0 - 1, 2, 47, px1 + 1, 10, 61, "wall_trim", faces=["+z", "-z", "+x", "-x", "+y"])
        street_gantry.add_box(px0 - 1, 46, 47, px1 + 1, 50, 61, "wall_steel", faces=["+z", "-z", "+x", "-x", "-y"])

    # Overhead spanning blackened-steel & concrete header truss (x in [-66, 66], y in [50, 70], z in [48, 60])
    street_gantry.add_box(-66, 50, 48, 66, 70, 60, "wall_steel")
    street_gantry.add_box(-67, 68, 47, 67, 71, 61, "wall_trim", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    # Double-sided 112x16 "THE FOUNDRY" illuminated marquee sign on front (+Z) and courtyard (-Z) faces
    street_gantry.add_panel_z(-56, 52, 56, 68, 60.4, "+z", "glow_laser", "gantry_sign")
    street_gantry.add_panel_z(-56, 52, 56, 68, 47.6, "-z", "glow_laser", "gantry_sign")
    # 2 downward-facing LED gantry downlights (ny = -1 -> spawns Filament lights!)
    street_gantry.add_panel_y(-32, 52, -16, 56, 49.85, "-y", "glow_laser", "lamp")
    street_gantry.add_panel_y(16, 52, 32, 56, 49.85, "-y", "glow_laser", "lamp")

    # ==========================================================================
    # 6. PROP GROUP 2: OPEN-AIR FRONT COURTYARD LOUNGE & YARD BAR (< 500 tris)
    # ==========================================================================
    # Heritage brick perimeter courtyard walls joining cleanly into the gantry piers (x=+-64)
    # West courtyard walls
    courtyard_lounge.add_box(-128, 2, 4, -122, 22, 56, "wall_brick")
    courtyard_lounge.add_box(-129, 22, 4, -121, 24, 57, "wall_trim")
    courtyard_lounge.add_box(-128, 2, 50, -64, 22, 56, "wall_brick")
    courtyard_lounge.add_box(-129, 22, 49, -64, 24, 57, "wall_trim")
    # East courtyard walls
    courtyard_lounge.add_box(122, 2, 0, 128, 22, 56, "wall_brick")
    courtyard_lounge.add_box(121, 22, 0, 129, 24, 57, "wall_trim")
    courtyard_lounge.add_box(64, 2, 50, 128, 22, 56, "wall_brick")
    courtyard_lounge.add_box(64, 22, 49, 129, 24, 57, "wall_trim")

    # Flanking street-facing courtyard illuminated signage: "OPEN AIR" (64x16) west & "YARD BAR" (64x12) east
    courtyard_lounge.add_panel_z(-128, 4, -64, 20, 56.4, "+z", "glow_laser", "courtyard_light")
    courtyard_lounge.add_panel_z(64, 6, 128, 18, 56.4, "+z", "glow_laser", "yard_sign")

    # West Courtyard: Reclaimed-Timber Outdoor Lounge Deck + Steel Pergola + Concrete/Coral Benches
    courtyard_lounge.add_box(-120, 2, 8, -48, 4, 48, "deck_timber", faces=["+y", "+x", "-x", "+z", "-z"])
    # Open industrial blackened-steel pergola frame over west deck (4 posts + 2 side beams + 3 crossbeams)
    for px0, px1, pz0, pz1 in (
        (-118, -115, 10, 13), (-54, -51, 10, 13),
        (-118, -115, 43, 46), (-54, -51, 43, 46),
    ):
        courtyard_lounge.add_box(px0, 4, pz0, px1, 34, pz1, "wall_steel")
    courtyard_lounge.add_box(-118, 34, 10, -51, 36, 13, "wall_steel")
    courtyard_lounge.add_box(-118, 34, 43, -51, 36, 46, "wall_steel")
    for bx in (-104, -84, -64):
        courtyard_lounge.add_box(bx, 34, 13, bx + 3, 36, 43, "wall_steel")
    # Two outdoor concrete-framed coral-leather lounge benches + central low steel table
    for bz0, bz1 in ((14, 22), (34, 42)):
        courtyard_lounge.add_box(-108, 4, bz0, -64, 8, bz1, "wall_concrete")
        courtyard_lounge.add_box(-106, 8, bz0 + 1, -66, 11, bz1 - 1, "detail_utility", region_name="coral")
    courtyard_lounge.add_box(-98, 4, 25, -74, 9, 31, "wall_steel")

    # East Courtyard: Open-Air "YARD BAR" Counter + Cantilevered Blackened-Steel Canopy + Bar Stools
    courtyard_lounge.add_box(102, 2, 12, 120, 14, 42, "wall_concrete")
    courtyard_lounge.add_box(100, 14, 11, 121, 16, 43, "deck_timber")
    # Cantilevered steel bar canopy & posts
    courtyard_lounge.add_box(118, 16, 12, 121, 32, 15, "wall_steel")
    courtyard_lounge.add_box(118, 16, 39, 121, 32, 42, "wall_steel")
    courtyard_lounge.add_box(94, 32, 10, 122, 35, 44, "wall_steel")
    # Downward LED light under the outdoor Yard Bar canopy (ny = -1)
    courtyard_lounge.add_panel_y(98, 25, 114, 29, 31.85, "-y", "glow_laser", "lamp")
    # Three minimalist concrete/coral courtyard high-top bar stools
    for sz in (17, 27, 37):
        courtyard_lounge.add_box(90, 2, sz - 2, 95, 10, sz + 2, "wall_steel")
        courtyard_lounge.add_box(89.5, 10, sz - 2.5, 95.5, 11.5, sz + 2.5, "detail_utility", region_name="coral")

    # ==========================================================================
    # 7. PROP GROUP 3: REAR PRODUCTION LOADING DOCK & SERVICE YARD (< 500 tris)
    # ==========================================================================
    # Raised concrete loading dock behind west-rear wall
    service_yard.add_box(-112, 2, -264, -12, 10, -232, "ground_concrete")
    service_yard.add_box(-112, 2, -266, -12, 10, -264, "wall_trim")
    # Steel dock bumpers
    for bx in (-96, -64, -32):
        service_yard.add_box(bx, 6, -268, bx + 4, 10, -266, "wall_steel")
    # Loading dock concrete access steps on west end
    for step in range(4):
        sy = 2 + step * 2
        sx0 = -124 + step * 3
        service_yard.add_box(sx0, 2, -252, sx0 + 3, sy + 2, -238, "wall_concrete")
    # Large roll-up industrial freight steel door on rear wall
    service_yard.add_box(-90, 10, -234, -34, 44, -232, "detail_utility", region_name="steel")
    service_yard.add_box(-92, 44, -236, -32, 47, -232, "wall_steel")

    # Backstage / Artist entry canopy & step over door_back (x in [40, 64])
    service_yard.add_box(36, 34, -242, 68, 37, -232, "wall_steel")
    service_yard.add_panel_y(44, -239, 60, -235, 33.85, "-y", "glow_laser", "lamp")
    service_yard.add_box(38, 2, -240, 66, 3.5, -232, "wall_concrete")

    # Twin industrial galvanized HVAC & acoustic exhaust risers on rear wall
    for rx0, rx1 in ((82, 96), (104, 118)):
        service_yard.add_box(rx0, 2, -246, rx1, 52, -232, "detail_utility", region_name="steel")
        service_yard.add_box(rx0 - 1, 52, -247, rx1 + 1, 55, -232, "wall_steel")

    # East rear enclosed acoustic chiller & generator compound
    service_yard.add_box(134, 2, -220, 174, 20, -217, "wall_steel")
    service_yard.add_box(171, 2, -217, 174, 20, -158, "wall_steel")
    service_yard.add_box(134, 2, -161, 174, 20, -158, "wall_steel")
    service_yard.add_box(142, 2, -210, 164, 16, -190, "detail_utility", region_name="steel")
    service_yard.add_box(142, 2, -184, 164, 14, -168, "detail_utility", region_name="steel")

    # ==========================================================================
    # 8. COMPLETELY NEW WALKABLE WAREHOUSE INTERIOR
    #    - 360-DEGREE CENTER BOILER-ROOM DJ ISLAND + RADIAL LASER FLOOR
    #    - ELEVATED REAR RECLAIMED-TIMBER VIP MEZZANINE + 128x40 KINETIC MATRIX WALL
    #    - EAST INDUSTRIAL CRAFT BAR + WEST OXBLOOD-CORAL LOUNGE BOOTHS
    # ==========================================================================
    # 8A. Illuminated 96x64 Radial Laser-Grid Dance Floor centered at (0, -104)
    structure.add_panel_y(-48, -136, 48, -72, 2.25, "+y", "detail_led", "laser_floor")

    # 8B. 360-Degree Center-Stage Boiler-Room DJ Island (x in [-24, 24], z in [-116, -92])
    # Low square concrete/steel plinth
    structure.add_box(-24, 2, -116, 24, 5, -92, "wall_steel")
    # Matte-black & coral DJ desk console inside the 360 island
    structure.add_box(-16, 5, -110, 16, 15, -98, "detail_utility", region_name="speaker")
    structure.add_box(-17, 15, -111, 17, 16.5, -97, "detail_utility", region_name="coral")
    # Turntable / controller decks on top of DJ desk
    structure.add_box(-12, 16.5, -107, -4, 17.5, -101, "wall_steel")
    structure.add_box(4, 16.5, -107, 12, 17.5, -101, "wall_steel")
    structure.add_box(-3, 16.5, -108, 3, 18.0, -100, "detail_utility", region_name="steel")
    # 360 DJ smoked-glass industrial surround shields (48x16) on front (+Z) & rear (-Z) of the DJ island
    structure.add_panel_z(-24, 5, 24, 21, -92.1, "+z", "glass_industrial", "dj_shield")
    structure.add_panel_z(-24, 5, 24, 21, -115.9, "-z", "glass_industrial", "dj_shield")

    # 8C. 4 Structural Steel Lighting Pillars & Overhead Square Industrial Laser Rig
    for px0, px1, pz0, pz1 in (
        (-48, -44, -76, -72), (44, 48, -76, -72),
        (-48, -44, -136, -132), (44, 48, -136, -132),
    ):
        structure.add_box(px0, 2, pz0, px1, 54, pz1, "wall_steel")
    # Square overhead steel lighting truss framing the 360 DJ floor (y in [45, 49])
    structure.add_box(-48, 45, -76, 48, 49, -72, "wall_steel")
    structure.add_box(-48, 45, -136, 48, 49, -132, "wall_steel")
    structure.add_box(-48, 45, -132, -44, 49, -76, "wall_steel")
    structure.add_box(44, 45, -132, 48, 49, -76, "wall_steel")
    # 8 downward-facing industrial LED light fixtures (ny = -1 -> spawns Filament lights!)
    for lx in (-28, 12):
        structure.add_panel_y(lx, -76, lx + 16, -72, 44.85, "-y", "detail_led", "lamp")
        structure.add_panel_y(lx, -136, lx + 16, -132, 44.85, "-y", "detail_led", "lamp")
    for lz in (-118, -94):
        structure.add_panel_y(-48, lz, -44, lz + 16, 44.85, "-y", "detail_led", "lamp")
        structure.add_panel_y(44, lz, 48, lz + 16, 44.85, "-y", "detail_led", "lamp")

    # 8D. Elevated Rear Reclaimed-Timber VIP Mezzanine Balcony (z in [-228, -172], y in [18, 20])
    structure.add_box(-124, 18, -228, 124, 20, -172, "deck_timber")
    structure.add_box(-124, 16, -175, 124, 18, -172, "wall_steel")
    # Mezzanine support steel I-columns
    for mx in (-84, -28, 28, 84):
        structure.add_box(mx - 2, 2, -176, mx + 2, 16, -172, "wall_steel")
    # Industrial glass & steel mezzanine overlook balustrade (3 x 64x14 mezz_rail panels)
    structure.add_panel_z(-96, 20, -32, 34, -172.1, "+z", "glass_industrial", "mezz_rail")
    structure.add_panel_z(-32, 20, 32, 34, -172.1, "+z", "glass_industrial", "mezz_rail")
    structure.add_panel_z(32, 20, 96, 34, -172.1, "+z", "glass_industrial", "mezz_rail")
    # West industrial steel & timber stair ascending to the VIP mezzanine (x in [-122, -100])
    for s in range(6):
        sy = 2 + s * 3
        sz0 = -172 + (5 - s) * 3
        structure.add_box(-122, 2, sz0, -100, sy + 3, sz0 + 3, "wall_concrete")
        structure.add_box(-122, sy + 2.5, sz0, -100, sy + 3.2, sz0 + 3, "deck_timber", faces=["+y"])

    # 8E. Massive 128x40 Kinetic Strobe & Pixel-Matrix Light Wall + Concert Speaker Stacks
    # Mounted centered on the rear wall above the mezzanine deck (x in [-64, 64], y in [16, 56], z = -226.4)
    structure.add_box(-66, 15, -228, 66, 56, -226.8, "wall_steel", faces=["+z", "+x", "-x", "+y", "-y"])
    structure.add_panel_z(-64, 16, 64, 56, -226.4, "+z", "detail_led", "matrix_wall")
    # Dual Bass-Reflex Concert Speaker Towers flanking the matrix wall
    for sx0, sx1 in ((-116, -78), (78, 116)):
        structure.add_box(sx0, 2, -228, sx1, 18, -208, "detail_utility", region_name="speaker")
        structure.add_box(sx0 + 2, 20, -228, sx1 - 2, 52, -210, "detail_utility", region_name="speaker")

    # 8F. Long Industrial Concrete & Copper-Accented Craft Bar (EAST WALL: x in [90, 124], z in [-156, -52])
    structure.add_box(90, 2, -156, 102, 15, -52, "wall_concrete")
    structure.add_box(88, 15, -158, 103, 17, -50, "deck_timber")
    structure.add_box(116, 2, -154, 124, 14, -54, "wall_steel")
    # 96x24 backlit emerald-mint craft spirits wall facing -X across the dance hall
    structure.add_box(122.4, 15, -152, 124, 39, -56, "wall_steel", faces=["-x", "+z", "-z", "+y", "-y"])
    structure.add_panel_x(-152, 15, -56, 39, 122.0, "-x", "detail_led", "craft_bar")
    # 5 industrial concrete/coral bar stools along the east bar
    for bz in (-144, -124, -104, -84, -64):
        structure.add_box(78, 2, bz - 2, 83, 10, bz + 2, "wall_steel")
        structure.add_box(77.5, 10, bz - 2.5, 83.5, 11.5, bz + 2.5, "detail_utility", region_name="coral")

    # 8G. Oxblood-Coral Leather Industrial Lounge Booths (WEST WALL: x in [-124, -90], z in [-140, -40])
    for bz0, bz1 in ((-136, -110), (-102, -76), (-68, -42)):
        # Concrete booth divider plinths + tufted oxblood-coral leather banquettes
        structure.add_box(-124, 2, bz0, -98, 7, bz1, "wall_concrete")
        structure.add_box(-124, 7, bz0 + 1, -114, 20, bz1 - 1, "detail_utility", region_name="coral")
        structure.add_box(-114, 7, bz0 + 1, -100, 11, bz1 - 1, "detail_utility", region_name="coral")
        # Blackened-steel low industrial cocktail table
        structure.add_box(-96, 2, bz0 + 7, -86, 10, bz1 - 7, "wall_steel")

    # ==========================================================================
    # 9. COLLISION BOXES (Doorways strictly clear / gated; interior walkable!)
    # ==========================================================================
    # Front wall segments leaving door_main (x in [-16, 16]) completely clear
    coll.add_box(-134, 2, -28, -104, 148, 4, "corner_light_tower")
    coll.add_box(-104, 2, -4, -16, 56, 0, "front_wall_west")
    coll.add_box(16, 2, -4, 128, 56, 0, "front_wall_east")
    # Gated collision box on door_main so runtime door opens cleanly
    coll.add_box(-16, 2, -2, 16, 38, 0, "door_main_gate", gate_node="door_main")

    # West wall segments leaving door_side (z in [-112, -88]) gated
    coll.add_box(-128, 2, -88, -124, 56, -28, "west_wall_front")
    coll.add_box(-128, 2, -112, -124, 34, -88, "door_side_gate", gate_node="door_side")
    coll.add_box(-128, 2, -232, -124, 56, -112, "west_wall_rear")

    # East wall
    coll.add_box(124, 2, -232, 128, 56, -4, "east_wall")

    # Rear wall leaving door_back (x in [40, 64]) gated
    coll.add_box(-124, 2, -232, 40, 56, -228, "rear_wall_west")
    coll.add_box(40, 2, -232, 64, 34, -228, "door_back_gate", gate_node="door_back")
    coll.add_box(64, 2, -232, 124, 56, -228, "rear_wall_east")

    # Street gantry piers ONLY (central x in [-48, 48] walkway is 100% open!)
    coll.add_box(-64, 2, 48, -48, 50, 60, "gantry_pier_west")
    coll.add_box(48, 2, 48, 64, 50, 60, "gantry_pier_east")

    # Courtyard perimeter walls & furniture
    coll.add_box(-128, 2, 4, -122, 24, 56, "courtyard_wall_west")
    coll.add_box(-128, 2, 50, -64, 24, 56, "courtyard_wall_front_west")
    coll.add_box(122, 2, 0, 128, 24, 56, "courtyard_wall_east")
    coll.add_box(64, 2, 50, 128, 24, 56, "courtyard_wall_front_east")
    coll.add_box(100, 2, 11, 121, 16, 43, "yard_bar_counter")
    coll.add_box(-108, 4, 14, -64, 11, 22, "courtyard_bench_north")
    coll.add_box(-108, 4, 34, -64, 11, 42, "courtyard_bench_south")

    # Rear loading dock, HVAC risers & chiller yard
    coll.add_box(-112, 2, -266, -12, 10, -232, "rear_loading_dock")
    coll.add_box(82, 2, -246, 118, 55, -232, "rear_hvac_risers")
    coll.add_box(134, 2, -220, 174, 20, -158, "east_chiller_compound")

    # Interior solid obstacles (center 360 DJ island, east craft bar, speaker stacks, west booths)
    coll.add_box(-24, 2, -116, 24, 21, -92, "center_360_dj_island")
    coll.add_box(88, 2, -158, 124, 17, -50, "east_craft_bar")
    coll.add_box(-116, 2, -228, -78, 24, -208, "rear_speaker_west")
    coll.add_box(78, 2, -228, 116, 24, -208, "rear_speaker_east")
    coll.add_box(-124, 2, -136, -98, 20, -42, "west_lounge_booths")

    buckets = {
        "lot": lot,
        "structure": structure,
        "street_gantry": street_gantry,
        "courtyard_lounge": courtyard_lounge,
        "service_yard": service_yard,
        "collision": coll.bucket,
    }
    return buckets, coll


def write_glb(buckets, coll):
    bin_buf = bytearray()
    buffer_views = []
    accessors = []
    images = []
    textures = []
    materials = []

    def append_bytes(data, target=None):
        while len(bin_buf) % 4 != 0:
            bin_buf.append(0)
        off = len(bin_buf)
        bin_buf.extend(data)
        bv = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target is not None:
            bv["target"] = target
        bv_idx = len(buffer_views)
        buffer_views.append(bv)
        return bv_idx

    # Sampler: NEAREST mag (9728), NEAREST_MIPMAP_NEAREST min (9984), REPEAT wrap (10497)
    samplers = [{
        "magFilter": 9728,
        "minFilter": 9984,
        "wrapS": 10497,
        "wrapT": 10497,
    }]

    for i, mdef in enumerate(MATERIAL_DEFS):
        png_bytes = (TEX_DIR / mdef["file"]).read_bytes()
        bv_idx = append_bytes(png_bytes)
        images.append({
            "name": mdef["name"],
            "mimeType": "image/png",
            "bufferView": bv_idx,
        })
        textures.append({"sampler": 0, "source": i})
        mat = {
            "name": mdef["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": i},
                "metallicFactor": mdef["metal"],
                "roughnessFactor": mdef["rough"],
            },
            "alphaMode": mdef["alpha"],
        }
        if mdef.get("doubleSided"):
            mat["doubleSided"] = True
        if "emissive" in mdef:
            mat["emissiveTexture"] = {"index": i}
            mat["emissiveFactor"] = mdef["emissive"]
        materials.append(mat)

    meshes = []
    mesh_idx_by_name = {}

    def pack_bucket(bname, bucket):
        prims = []
        for mat_idx, pdata in sorted(bucket.prims.items()):
            pos = pdata["pos"]
            nrm = pdata["nrm"]
            uv = pdata["uv"]
            idx = pdata["idx"]
            if not idx:
                continue

            pos_bytes = bytearray()
            min_p = [1e9, 1e9, 1e9]
            max_p = [-1e9, -1e9, -1e9]
            for x, y, z in pos:
                pos_bytes.extend(struct.pack("<fff", x, y, z))
                min_p[0] = min(min_p[0], x)
                min_p[1] = min(min_p[1], y)
                min_p[2] = min(min_p[2], z)
                max_p[0] = max(max_p[0], x)
                max_p[1] = max(max_p[1], y)
                max_p[2] = max(max_p[2], z)
            pos_bv = append_bytes(pos_bytes, target=34962)
            pos_acc = len(accessors)
            accessors.append({
                "bufferView": pos_bv,
                "componentType": 5126,
                "count": len(pos),
                "type": "VEC3",
                "min": min_p,
                "max": max_p,
            })

            nrm_bytes = bytearray()
            for x, y, z in nrm:
                nrm_bytes.extend(struct.pack("<fff", x, y, z))
            nrm_bv = append_bytes(nrm_bytes, target=34962)
            nrm_acc = len(accessors)
            accessors.append({
                "bufferView": nrm_bv,
                "componentType": 5126,
                "count": len(nrm),
                "type": "VEC3",
            })

            uv_bytes = bytearray()
            for u, v in uv:
                uv_bytes.extend(struct.pack("<ff", u, v))
            uv_bv = append_bytes(uv_bytes, target=34962)
            uv_acc = len(accessors)
            accessors.append({
                "bufferView": uv_bv,
                "componentType": 5126,
                "count": len(uv),
                "type": "VEC2",
            })

            idx_bytes = bytearray()
            for ii in idx:
                idx_bytes.extend(struct.pack("<I", ii))
            idx_bv = append_bytes(idx_bytes, target=34963)
            idx_acc = len(accessors)
            accessors.append({
                "bufferView": idx_bv,
                "componentType": 5125,
                "count": len(idx),
                "type": "SCALAR",
            })

            prims.append({
                "attributes": {
                    "POSITION": pos_acc,
                    "NORMAL": nrm_acc,
                    "TEXCOORD_0": uv_acc,
                },
                "indices": idx_acc,
                "material": mat_idx,
            })
        midx = len(meshes)
        meshes.append({"name": bname, "primitives": prims})
        mesh_idx_by_name[bname] = midx
        return midx

    for bname in ("structure", "lot", "street_gantry", "courtyard_lounge", "service_yard", "collision"):
        pack_bucket(bname, buckets[bname])

    # Door empty leaf nodes:
    # door_main (+Z): [0, 0, 0, 1]
    # door_back (-Z): [0, 1, 0, 0]
    # door_side (-X on west wall): rotate -90 deg around Y -> [0, -sin(pi/4), 0, cos(pi/4)]
    s45 = math.sin(math.pi / 4.0)
    c45 = math.cos(math.pi / 4.0)

    nodes = [
        {
            "name": "foundry_nightclub",
            "children": [1, 2, 3, 4, 5, 6, 7, 8, 9],
        },
        {"name": "structure", "mesh": mesh_idx_by_name["structure"]},
        {"name": "lot", "mesh": mesh_idx_by_name["lot"]},
        {"name": "street_gantry", "mesh": mesh_idx_by_name["street_gantry"]},
        {"name": "courtyard_lounge", "mesh": mesh_idx_by_name["courtyard_lounge"]},
        {"name": "service_yard", "mesh": mesh_idx_by_name["service_yard"]},
        {
            "name": "collision",
            "mesh": mesh_idx_by_name["collision"],
            "extras": {"collision_only": True},
        },
        {
            "name": "door_main",
            "translation": [0.0, 0.0, round(2.0 * U, 6)],
            "rotation": [0.0, 0.0, 0.0, 1.0],
        },
        {
            "name": "door_back",
            "translation": [round(52.0 * U, 6), 0.0, round(-234.0 * U, 6)],
            "rotation": [0.0, 1.0, 0.0, 0.0],
        },
        {
            "name": "door_side",
            "translation": [round(-130.0 * U, 6), 0.0, round(-100.0 * U, 6)],
            "rotation": [0.0, round(-s45, 6), 0.0, round(c45, 6)],
        },
    ]

    while len(bin_buf) % 4 != 0:
        bin_buf.append(0)

    gltf = {
        "asset": {
            "version": "2.0",
            "generator": "THE FOUNDRY Procedural Landmark Builder",
        },
        "scene": 0,
        "scenes": [{"name": "foundry_nightclub_scene", "nodes": [0]}],
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

    glb_path = R / "foundry-nightclub.glb"
    glb_path.write_bytes(header + json_chunk + bin_chunk)

    vis_tris = sum(
        buckets[k].tri_count()
        for k in ("structure", "lot", "street_gantry", "courtyard_lounge", "service_yard")
    )
    coll_tris = buckets["collision"].tri_count()

    info = {
        "name": "The Foundry",
        "kind": "landmark_glb_foundry_nightclub",
        "role": "nightclub",
        "room": "hall",
        "era": {"from": 2016, "to": 2040, "built": 2024},
        "glb": "foundry-nightclub.glb",
        "texel_size_m": T,
        "art_pixel_m": U,
        "footprint_m": {
            "lot_width": round(368 * U, 4),
            "lot_depth": round(384 * U, 4),
            "building_width": round(256 * U, 4),
            "building_depth": round(232 * U, 4),
            "max_height": round(148 * U, 4),
        },
        "triangles": {
            "visible": vis_tris,
            "collision": coll_tris,
            "by_node": {
                k: buckets[k].tri_count()
                for k in ("structure", "lot", "street_gantry", "courtyard_lounge", "service_yard", "collision")
            },
        },
        "doors": {
            "door_main": {"translation_gltf": [0.0, 0.0, round(2.0 * U, 4)], "outward": [0, 0, 1]},
            "door_back": {"translation_gltf": [round(52.0 * U, 4), 0.0, round(-234.0 * U, 4)], "outward": [0, 0, -1]},
            "door_side": {"translation_gltf": [round(-130.0 * U, 4), 0.0, round(-100.0 * U, 4)], "outward": [-1, 0, 0]},
        },
        "materials": [
            {
                "name": m["name"],
                "file": m["file"],
                "size": list(MAT_SIZE[m["name"]]),
                "alpha": m["alpha"],
                "emissive": "emissive" in m,
            }
            for m in MATERIAL_DEFS
        ],
        "collision": {
            "coordinate_note": "Blender Z-up convention (x, y_blender=-z_gltf, z_blender=y_gltf)",
            "boxes_blender_z_up": coll.boxes,
        },
    }
    (R / "model-info.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote foundry-nightclub.glb ({glb_path.stat().st_size} bytes)")
    print(f"  Visible triangles: {vis_tris} | Collision triangles: {coll_tris}")
    for k in ("structure", "lot", "street_gantry", "courtyard_lounge", "service_yard"):
        print(f"    {k}: {buckets[k].tri_count()} tris")


def main():
    buckets, coll = build_scene()
    write_glb(buckets, coll)


if __name__ == "__main__":
    main()
