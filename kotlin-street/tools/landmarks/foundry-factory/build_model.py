#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) generator for THE FOUNDRY (1938 Historic Working Iron & Steel Factory) —
the original pre-conversion working foundry sharing the exact footprint, red-brick sawtooth
warehouse shell, corner tower, and front yard geometry later adapted into THE FOUNDRY nightclub.

Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- 0.0388 m/texel (2x2 art pixel blocks -> 1 art pixel U = 0.0776 m)
- 12 materials: 7 shared LimeZu swatches + 5 bespoke historic industrial foundry textures
- NEAREST magnification (9728), NEAREST_MIPMAP_NEAREST minification (9984), REPEAT wrap (10497)
- Zero UV stretch (<= 1 mm error on every edge, including pitched sawtooth roofs & gables)
- Complete 360-degree exterior + walkable working foundry floor with center molten crucible pit,
  overhead yellow travelling bridge crane, rear timber foreman's catwalk & blast-furnace hearth,
  east machinist tool-crib benches, and west casting-flask & pattern benches
- Door empties: door_main (+Z), door_back (-Z), door_side (-X)
- Prop sub-groups (< 500 tris each): yard_gantry, foundry_yard, service_yard
"""
import json
import os
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
    {"name": "wall_brick", "file": "brick_red.png", "rough": 0.92, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "wall_stone", "file": "wall_slate_dark.png", "rough": 0.90, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "wall_block", "file": "block_courses.png", "rough": 0.92, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "roof_corrugated", "file": "roof_tin_brown.png", "rough": 0.84, "metal": 0.18, "alpha": "OPAQUE"},
    {"name": "wood_timber", "file": "plank_horizontal.png", "rough": 0.84, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "ground_yard", "file": "asphalt_brown.png", "rough": 0.95, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "ground_concrete", "file": "pavement.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
    {"name": "wall_iron", "file": "riveted_iron.png", "rough": 0.62, "metal": 0.32, "alpha": "OPAQUE"},
    {"name": "wall_trim", "file": "concrete_aged.png", "rough": 0.84, "metal": 0.04, "alpha": "OPAQUE"},
    {"name": "glass_factory", "file": "glass_factory.png", "rough": 0.24, "metal": 0.06, "alpha": "BLEND", "doubleSided": True},
    {"name": "glow_furnace", "file": "factory_glow.png", "rough": 0.36, "metal": 0.0, "alpha": "OPAQUE", "emissive": [1.0, 1.0, 1.0]},
    {"name": "detail_utility", "file": "utility_factory.png", "rough": 0.72, "metal": 0.16, "alpha": "OPAQUE"},
    # (the hall's casting pit, hearth wall, tool wall and truss lamps: the same picture, not emissive; the town lights only
    # what shows outside, and a glowing ceiling would become a real spot light inside a closed shell)
    {"name": "detail_hall", "file": "factory_glow.png", "rough": 0.36, "metal": 0.0, "alpha": "OPAQUE"},
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
    def __init__(self, name):
        self.name = name
        self.prims = {}

    def _prim(self, mat_name):
        mi = MAT_IDX[mat_name]
        if mi not in self.prims:
            self.prims[mi] = {"pos": [], "nrm": [], "uv": [], "idx": []}
        return self.prims[mi]

    def add_polygon(self, pts_art, mat_name, u_axis=None, v_axis=None, uv_rect_art=None):
        pts_m = [(p[0] * U, p[1] * U, p[2] * U) for p in pts_art]
        n = v_norm(v_cross(v_sub(pts_m[1], pts_m[0]), v_sub(pts_m[2], pts_m[0])))
        W, H = MAT_SIZE[mat_name]

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
                u = v_dot(p, e_u) / (W * T)
                v = -v_dot(p, e_v) / (H * T)
                prim["pos"].append(p)
                prim["nrm"].append(n)
                prim["uv"].append((u, v))

        for i in range(1, len(pts_m) - 1):
            prim["idx"].extend((base, base + i, base + i + 1))

    def add_quad_art(self, pts_art, mat_name, region_name=None, u_axis=None, v_axis=None):
        rect = ART_REGIONS[region_name]["rect_art"] if region_name else None
        self.add_polygon(pts_art, mat_name, u_axis=u_axis, v_axis=v_axis, uv_rect_art=rect)

    def add_box(self, x0, y0, z0, x1, y1, z1, mat_name, faces="all", region_name=None):
        fset = {"+x", "-x", "+y", "-y", "+z", "-z"} if faces == "all" else set(faces)
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
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "wall_stone")
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
    yard_gantry = MeshBucket("yard_gantry")
    foundry_yard = MeshBucket("foundry_yard")
    service_yard = MeshBucket("service_yard")
    coll = CollisionRecorder()

    # ==========================================================================
    # 1. LOT PAVING (Exact same 368 x 384 art px = 28.56 m x 29.80 m footprint!)
    # ==========================================================================
    # Front receiving apron & industrial sidewalk concrete
    lot.add_box(-184, 0, 0, 184, 2, 80, "ground_concrete", faces=["+y", "+z", "-x", "+x"])
    # Oil-stained brown asphalt freight truck lanes & rear yard
    lot.add_box(-184, 0, -304, -128, 2, 0, "ground_yard", faces=["+y", "-x", "-z"])
    lot.add_box(128, 0, -304, 184, 2, 0, "ground_yard", faces=["+y", "+x", "-z"])
    lot.add_box(-128, 0, -304, 128, 2, -232, "ground_yard", faces=["+y", "-z"])
    # Main foundry floor poured concrete slab (y = 2)
    lot.add_box(-128, 0, -232, 128, 2, 0, "ground_concrete", faces=["+y"])

    # Cast-stone lot perimeter curbs
    lot.add_box(-184, 2, -300, -180, 5, 76, "wall_trim", faces=["+y", "+x", "-x", "+z", "-z"])
    lot.add_box(180, 2, -300, 184, 5, 76, "wall_trim", faces=["+y", "+x", "-x", "+z", "-z"])
    lot.add_box(-180, 2, -304, 180, 5, -300, "wall_trim", faces=["+y", "+z", "-z"])

    # Heavy steel-plate handcart & rail-trolley floor runway through yard gate into main factory doors (2 slabs <= 64 art px)
    lot.add_box(-24, 2, 2, 24, 2.25, 39, "detail_utility", faces=["+y"], region_name="iron_plate")
    lot.add_box(-24, 2, 39, 24, 2.25, 76, "detail_utility", faces=["+y"], region_name="iron_plate")

    # ==========================================================================
    # 2. ORIGINAL 1938 RED-BRICK FOUNDRY SHELL (x in [-128, 128], z in [-232, 0], eave y = 56)
    # ==========================================================================
    # --- FRONT RED-BRICK FACADE (z in [-4, 0], y in [2, 56]) ---
    structure.add_box(-104, 2, -4, -98, 56, 0, "wall_brick")
    structure.add_box(-98, 2, -4, -26, 10, 0, "wall_brick")
    structure.add_box(-98, 42, -4, -26, 56, 0, "wall_brick")
    structure.add_box(-26, 2, -4, -16, 56, 0, "wall_brick")
    structure.add_box(-16, 50, -4, 16, 56, 0, "wall_iron")
    structure.add_box(16, 2, -4, 36, 56, 0, "wall_brick")
    structure.add_box(36, 2, -4, 108, 10, 0, "wall_brick")
    structure.add_box(36, 42, -4, 108, 56, 0, "wall_brick")
    structure.add_box(108, 2, -4, 128, 56, 0, "wall_brick")

    # Riveted-iron structural pilasters & industrial main freight entry portal
    for px0, px1 in ((-104, -100), (-26, -22), (22, 26), (120, 128)):
        structure.add_box(px0, 2, 0, px1, 56, 2, "wall_iron", faces=["+z", "+x", "-x", "+y"])
    structure.add_box(-22, 2, 0, -16, 54, 3, "wall_iron", faces=["+z", "+x", "-x", "+y"])
    structure.add_box(16, 2, 0, 22, 54, 3, "wall_iron", faces=["+z", "+x", "-x", "+y"])
    structure.add_box(-26, 50, 0, 26, 63, 4, "wall_iron", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    # The works' name painted on the brick over the windows, faded (a ghost sign; one variant per build: make_art.py)
    structure.add_panel_z(-98, 45, -26, 55, 0.3, "+z", "detail_hall", "ghost_left")
    structure.add_panel_z(36, 45, 108, 55, 0.3, "+z", "detail_hall", "ghost_right")
    # Warm incandescent factory entrance downlight under portal header (ny = -1 -> Filament light!)
    structure.add_panel_y(-8, 0.2, 8, 4.2, 49.85, "-y", "glow_furnace", "lamp")

    # Multi-pane factory steel sash windows & steel-framed freight double doors
    structure.add_panel_z(-98, 10, -26, 42, -1.6, "+z", "glass_factory", "front_left")
    structure.add_panel_z(36, 10, 108, 42, -1.6, "+z", "glass_factory", "front_right")
    structure.add_panel_z(-16, 2, 16, 38, -0.4, "+z", "glass_factory", "entry")
    structure.add_panel_z(-16, 38, 16, 50, -0.4, "+z", "glass_factory", "transom")

    # Aged cast-stone sills & riveted iron I-beam window lintels
    structure.add_box(-99, 8, 0, -25, 10, 2, "wall_trim", faces=["+z", "+y", "-y", "+x", "-x"])
    structure.add_box(-99, 42, 0, -25, 44, 2, "wall_iron", faces=["+z", "+y", "-y", "+x", "-x"])
    structure.add_box(35, 8, 0, 109, 10, 2, "wall_trim", faces=["+z", "+y", "-y", "+x", "-x"])
    structure.add_box(35, 42, 0, 109, 44, 2, "wall_iron", faces=["+z", "+y", "-y", "+x", "-x"])

    # Aged stone-coped brick roof gutter parapets
    structure.add_box(-104, 56, -8, 128, 62, 0, "wall_trim", faces=["+z", "-z", "+y", "+x", "-x"])
    structure.add_box(-128, 56, -232, 128, 62, -224, "wall_trim", faces=["+z", "-z", "+y", "+x", "-x"])

    # --- WEST ELEVATION (x in [-128, -124], z in [-232, -4], y in [2, 56]) ---
    structure.add_box(-128, 2, -32, -124, 56, -4, "wall_brick")
    structure.add_box(-128, 2, -80, -124, 14, -32, "wall_brick")
    structure.add_box(-128, 46, -80, -124, 56, -32, "wall_brick")
    structure.add_box(-128, 2, -88, -124, 56, -80, "wall_brick")
    structure.add_box(-128, 34, -112, -124, 56, -88, "wall_brick")
    structure.add_box(-128, 2, -128, -124, 56, -112, "wall_brick")
    structure.add_box(-128, 2, -176, -124, 18, -128, "wall_brick")
    structure.add_box(-128, 42, -176, -124, 56, -128, "wall_brick")
    structure.add_box(-128, 2, -232, -124, 56, -176, "wall_brick")
    structure.add_panel_x(-80, 14, -32, 46, -126.4, "-x", "glass_factory", "side_win")
    structure.add_panel_x(-176, 18, -128, 42, -126.4, "-x", "glass_factory", "shop_win")
    structure.add_box(-130, 2, -114, -128, 36, -111, "wall_iron", faces=["-x", "+z", "-z", "+y"])
    structure.add_box(-130, 2, -89, -128, 36, -86, "wall_iron", faces=["-x", "+z", "-z", "+y"])
    structure.add_box(-132, 34, -116, -128, 38, -84, "wall_iron", faces=["-x", "+z", "-z", "+y", "-y"])
    structure.add_box(-127, 2, -111, -125, 34, -89, "wall_iron", faces=["-x", "+x"])
    for pz0, pz1 in ((-86, -82), (-124, -120), (-184, -180), (-232, -226)):
        structure.add_box(-130, 2, pz0, -128, 56, pz1, "wall_iron", faces=["-x", "+z", "-z", "+y"])

    # --- EAST ELEVATION (x in [124, 128], z in [-232, -4], y in [2, 56]) ---
    structure.add_box(124, 2, -32, 128, 56, -4, "wall_brick")
    structure.add_box(124, 2, -80, 128, 14, -32, "wall_brick")
    structure.add_box(124, 46, -80, 128, 56, -32, "wall_brick")
    structure.add_box(124, 2, -128, 128, 56, -80, "wall_brick")
    structure.add_box(124, 2, -176, 128, 18, -128, "wall_brick")
    structure.add_box(124, 42, -176, 128, 56, -128, "wall_brick")
    structure.add_box(124, 2, -232, 128, 56, -176, "wall_brick")
    structure.add_panel_x(-80, 14, -32, 46, 126.4, "+x", "glass_factory", "side_win")
    structure.add_panel_x(-176, 18, -128, 42, 126.4, "+x", "glass_factory", "shop_win")
    for pz0, pz1 in ((-28, -24), (-86, -82), (-124, -120), (-184, -180), (-232, -226)):
        structure.add_box(128, 2, pz0, 130, 56, pz1, "wall_iron", faces=["+x", "+z", "-z", "+y"])

    # --- REAR ELEVATION (z in [-232, -228], x in [-124, 124], y in [2, 56]) ---
    structure.add_box(-124, 2, -232, 40, 56, -228, "wall_block")
    structure.add_box(40, 34, -232, 64, 56, -228, "wall_block")
    structure.add_box(64, 2, -232, 124, 56, -228, "wall_block")
    structure.add_box(40, 2, -231, 64, 34, -229, "wall_iron", faces=["-z", "+z"])

    # ==========================================================================
    # 3. 3-BAY WEATHERED CORRUGATED-IRON SAWTOOTH SKYLIGHT ROOF + RIDGE VENTILATORS
    # ==========================================================================
    # Full-tiling dark corrugated-iron ceiling deck over factory floor (y = 54..56)
    structure.add_box(-124, 54, -228, 124, 56, -4, "wall_iron", faces=["-y", "+y"])

    sawtooth_bays = [
        (-80, -8),     # Front bay
        (-152, -80),   # Middle bay
        (-224, -152),  # Rear bay
    ]
    for z_back, z_front in sawtooth_bays:
        # Dark iron interior baffle behind north-light clerestory sash
        structure.add_box(-126, 56, z_front - 3, 126, 74, z_front - 1.5, "wall_iron", faces=["+z"])
        structure.add_box(-128, 56, z_front - 1, 128, 57.5, z_front + 0.5, "wall_iron", faces=["+z", "+y", "+x", "-x"])
        structure.add_box(-128, 73.5, z_front - 3, 128, 76, z_front + 1, "wall_iron",
                          faces=["+z", "-z", "+y", "-y", "+x", "-x"])
        # Warm-lit dusty north-light factory skylight bands
        structure.add_panel_z(-128, 57.5, 0, 73.5, z_front, "+z", "glass_factory", "clerestory")
        structure.add_panel_z(0, 57.5, 128, 73.5, z_front, "+z", "glass_factory", "clerestory")

        # Pitched rusted corrugated-tin factory roof slope (roof_tin_brown)
        structure.add_polygon(
            [
                (-128, 75, z_front - 2),
                (128, 75, z_front - 2),
                (128, 56, z_back),
                (-128, 56, z_back),
            ],
            "roof_corrugated",
            u_axis=(1, 0, 0),
        )

        # Red-brick triangular sawtooth gable end walls
        structure.add_polygon(
            [(-128, 56, z_back), (-128, 56, z_front), (-128, 75, z_front)],
            "wall_brick",
            u_axis=(0, 0, 1),
            v_axis=(0, 1, 0),
        )
        structure.add_polygon(
            [(128, 56, z_front), (128, 56, z_back), (128, 75, z_front)],
            "wall_brick",
            u_axis=(0, 0, -1),
            v_axis=(0, 1, 0),
        )
        # Riveted iron bargeboards along sawtooth rakes
        structure.add_polygon(
            [(-129.2, 56, z_back), (-128, 56, z_back), (-128, 75.5, z_front), (-129.2, 75.5, z_front)],
            "wall_iron",
        )
        structure.add_polygon(
            [(128, 56, z_back), (129.2, 56, z_back), (129.2, 75.5, z_front), (128, 75.5, z_front)],
            "wall_iron",
        )
        # Galvanized factory ridge turbine/exhaust ventilator hoods along each sawtooth peak!
        for vx in (-64, 48):
            structure.add_box(vx - 5, 75.5, z_front - 6, vx + 5, 81, z_front + 0.5,
                              "detail_utility", region_name="iron_plate")
            structure.add_box(vx - 7, 81, z_front - 7.5, vx + 7, 83, z_front + 2, "wall_iron")

    # ==========================================================================
    # 4. ORIGINAL BRICK FURNACE SMOKESTACK & CUPOLA TOWER (Exact same west-front corner!)
    #    Before 2024 concrete cladding: Heritage Red Brick + Dark Stone Collars + Iron Cupola Hood
    # ==========================================================================
    # Heavy soot-darkened cut-stone base plinth (y in [2, 24])
    structure.add_box(-135, 2, -29, -103, 24, 5, "wall_stone")
    # A tall round-shouldered brick chimney narrowing to the top (stepped a pixel each side every 20 px, as the art's grid
    # draws a batter): 30 x 32 px at its foot, 14 x 16 at its head, its corbelled cap at 204 px = 15.8 m (the club's
    # square tower, 11.5 m, is what it was cut down to)
    STACK_TOP = 204
    for k in range(9):
        y0, y1 = 24 + k * 20, 24 + (k + 1) * 20
        structure.add_box(-134 + k, y0, -28 + k, -104 - k, y1, 4 - k, "wall_brick")
    # cast-stone collar at its foot, an iron band two-thirds up, the corbelled cap and its soot-black lip
    structure.add_box(-135.5, 22, -29.5, -102.5, 25, 5.5, "wall_trim", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    structure.add_box(-129.5, 140, -23.5, -108.5, 142, 0.5, "wall_iron", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    structure.add_box(-127, STACK_TOP - 4, -21, -111, STACK_TOP, -5, "wall_trim", faces=["+z", "-z", "+x", "-x", "+y", "-y"])
    structure.add_box(-125, STACK_TOP, -19, -113, STACK_TOP + 2, -7, "wall_iron", faces=["+z", "-z", "+x", "-x", "+y"])

    # ==========================================================================
    # 5. PROP GROUP 1: RIVETED-IRON & BRICK FACTORY GATEHOUSE GANTRY (< 500 tris)
    #    Exact same street-line piers & span (z in [48, 60]) later converted to club gantry!
    # ==========================================================================
    for px0, px1 in ((-64, -48), (48, 64)):
        yard_gantry.add_box(px0, 2, 48, px1, 14, 60, "wall_stone")
        yard_gantry.add_box(px0, 14, 48, px1, 50, 60, "wall_brick")
        # Industrial safety-yellow & cast-stone pier bumper guards
        yard_gantry.add_box(px0 - 1, 2, 47, px1 + 1, 10, 61, "wall_trim", faces=["+z", "-z", "+x", "-x", "+y"])
        yard_gantry.add_box(px0 - 1, 46, 47, px1 + 1, 50, 61, "wall_iron", faces=["+z", "-z", "+x", "-x", "-y"])

    # Heavy riveted-iron overhead gatehouse bridge girder (x in [-66, 66], y in [50, 70], z in [48, 60])
    yard_gantry.add_box(-66, 50, 48, 66, 70, 60, "wall_iron")
    for gx0, gx1 in ((-67, -22), (-22, 22), (22, 67)):
        yard_gantry.add_box(gx0, 68, 47, gx1, 71, 61, "detail_utility", faces=["+z", "-z", "+x", "-x", "+y", "-y"],
                            region_name="hazard_yellow")
    # One plain painted board over the gate, unlit
    yard_gantry.add_panel_z(-32, 55, 32, 67, 60.4, "+z", "detail_hall", "gate_board")
    # 2 warm incandescent factory gate lamps under the bridge girder (ny = -1 -> Filament lights!)
    yard_gantry.add_panel_y(-32, 52, -16, 56, 49.85, "-y", "glow_furnace", "lamp")
    yard_gantry.add_panel_y(16, 52, 32, 56, 49.85, "-y", "glow_furnace", "lamp")

    # ==========================================================================
    # 6. PROP GROUP 2: WORKING FRONT RECEIVING YARD, CRATE SHED & WEIGH BOOTH (< 500 tris)
    #    Same brick yard walls later converted into the nightclub's open-air courtyard!
    # ==========================================================================
    # Perimeter red-brick factory yard walls with cast-stone coping
    foundry_yard.add_box(-128, 2, 4, -122, 22, 56, "wall_brick")
    foundry_yard.add_box(-129, 22, 4, -121, 24, 57, "wall_trim")
    foundry_yard.add_box(-128, 2, 50, -64, 22, 56, "wall_brick")
    foundry_yard.add_box(-129, 22, 49, -64, 24, 57, "wall_trim")

    foundry_yard.add_box(122, 2, 0, 128, 22, 56, "wall_brick")
    foundry_yard.add_box(121, 22, 0, 129, 24, 57, "wall_trim")
    foundry_yard.add_box(64, 2, 50, 128, 22, 56, "wall_brick")
    foundry_yard.add_box(64, 22, 49, 129, 24, 57, "wall_trim")


    # West Front Yard: Timber Shipping Pallets, Nailed Wooden Crates, Pig-Iron Ingots & Corrugated Iron Shelter
    foundry_yard.add_box(-120, 2, 8, -48, 4, 48, "wood_timber", faces=["+y", "+x", "-x", "+z", "-z"])
    # Open corrugated-iron rain shelter frame on 4 iron posts (later stripped to the nightclub pergola!)
    for px0, px1, pz0, pz1 in (
        (-118, -115, 10, 13), (-54, -51, 10, 13),
        (-118, -115, 43, 46), (-54, -51, 43, 46),
    ):
        foundry_yard.add_box(px0, 4, pz0, px1, 34, pz1, "wall_iron")
    foundry_yard.add_box(-119, 34, 9, -50, 36.5, 47, "roof_corrugated")
    # Stacked nailed wooden foundry crates & steel ingot bundles under the west shelter
    foundry_yard.add_box(-112, 4, 14, -92, 16, 28, "detail_utility", region_name="crate_wood")
    foundry_yard.add_box(-108, 16, 16, -94, 24, 26, "detail_utility", region_name="crate_wood")
    foundry_yard.add_box(-86, 4, 14, -62, 13, 28, "detail_utility", region_name="iron_plate")
    foundry_yard.add_box(-110, 4, 32, -88, 14, 42, "detail_utility", region_name="crate_wood")
    foundry_yard.add_box(-82, 4, 33, -62, 11, 41, "detail_utility", region_name="coal_slag")

    # East Front Yard: Foreman's Weigh-Scale & Tool-Check Booth + Oil Drums (exact spot of future YARD BAR!)
    foundry_yard.add_box(102, 2, 12, 120, 14, 42, "wall_brick")
    foundry_yard.add_box(100, 14, 11, 121, 16, 43, "wood_timber")
    foundry_yard.add_box(118, 16, 12, 121, 32, 15, "wall_iron")
    foundry_yard.add_box(118, 16, 39, 121, 32, 42, "wall_iron")
    foundry_yard.add_box(94, 32, 10, 122, 35, 44, "roof_corrugated")
    foundry_yard.add_panel_y(98, 25, 114, 29, 31.85, "-y", "glow_furnace", "lamp")
    # Three 55-gallon industrial steel oil/coolant drums lined up by the receiving counter
    for sz in (17, 27, 37):
        foundry_yard.add_box(88, 2, sz - 3, 95, 13, sz + 3, "detail_utility", region_name="coal_slag")
        foundry_yard.add_box(88, 6, sz - 3.2, 95, 8.5, sz + 3.2, "detail_utility", region_name="hazard_yellow")

    # ==========================================================================
    # 7. PROP GROUP 3: REAR FREIGHT LOADING DOCK, DUST CYCLONES & COAL BUNKER (< 500 tris)
    # ==========================================================================
    # Raised concrete freight loading dock & bumper guards
    service_yard.add_box(-112, 2, -264, -12, 10, -232, "ground_concrete")
    service_yard.add_box(-112, 2, -266, -12, 10, -264, "wall_trim")
    for bx in (-96, -64, -32):
        service_yard.add_box(bx, 6, -268, bx + 4, 10, -266, "wall_iron")
    for step in range(4):
        sy = 2 + step * 2
        sx0 = -124 + step * 3
        service_yard.add_box(sx0, 2, -252, sx0 + 3, sy + 2, -238, "wall_stone")
    # Corrugated-iron roll-up freight door + wooden shipping crate waiting on dock
    service_yard.add_box(-90, 10, -234, -34, 44, -232, "detail_utility", region_name="iron_plate")
    service_yard.add_box(-92, 44, -236, -32, 47, -232, "wall_iron")
    service_yard.add_box(-108, 10, -256, -94, 20, -242, "detail_utility", region_name="crate_wood")
    # "SHIPPING" stencilled on the brick over the dock door
    service_yard.add_panel_z(-86, 47, -38, 57, -232.3, "-z", "detail_hall", "stencil")
    # a lamp over the dock door
    service_yard.add_panel_y(-70, -236, -54, -233, 43.85, "-y", "glow_furnace", "lamp")

    # Rear employee timeclock entrance canopy over door_back (x in [40, 64])
    service_yard.add_box(36, 34, -242, 68, 37, -232, "wall_iron")
    service_yard.add_panel_y(44, -239, 60, -235, 33.85, "-y", "glow_furnace", "lamp")
    service_yard.add_box(38, 2, -240, 66, 3.5, -232, "wall_stone")

    # Twin galvanized blast-furnace exhaust stacks & dust-collector risers on rear wall
    for rx0, rx1 in ((82, 96), (104, 118)):
        service_yard.add_box(rx0, 2, -246, rx1, 52, -232, "detail_utility", region_name="iron_plate")
        service_yard.add_box(rx0 - 1, 52, -247, rx1 + 1, 55, -232, "wall_iron")

    # East rear walled Coal / Coke & Scrap-Iron Storage Bunker (exact compound later converted to chiller yard!)
    service_yard.add_box(134, 2, -220, 174, 18, -217, "wall_brick")
    service_yard.add_box(171, 2, -217, 174, 18, -158, "wall_brick")
    service_yard.add_box(134, 2, -161, 174, 18, -158, "wall_brick")
    # Mound of dark foundry coke/coal + stacked raw iron beams inside the bunker
    service_yard.add_box(140, 2, -214, 168, 14, -186, "detail_utility", region_name="coal_slag")
    service_yard.add_box(144, 14, -210, 164, 19, -190, "detail_utility", region_name="coal_slag")
    service_yard.add_box(140, 2, -182, 168, 12, -164, "detail_utility", region_name="iron_plate")

    # ==========================================================================
    # 8. WALKABLE 1938 WORKING FOUNDRY INTERIOR
    #    - CENTER MOLTEN CRUCIBLE CASTING PIT + OVERHEAD SAFETY-YELLOW BRIDGE CRANE
    #    - REAR TIMBER FOREMAN'S CATWALK MEZZANINE + 128x40 BLAST FURNACE HEARTH WALL
    #    - EAST MACHINIST WORKBENCH & TOOL-CRIB WALL + WEST CASTING-FLASK & CRATE BENCHES
    # ==========================================================================
    # 8A. Illuminated 96x64 Foundry Casting Floor Pit with Molten Slag Channels at (0, -104)
    structure.add_panel_y(-48, -136, 48, -72, 2.25, "+y", "detail_hall", "casting_pit")

    # 8B. Center Riveted-Iron Crucible Pouring Furnace & Sand-Casting Mold Frame (x in [-24, 24], z in [-116, -92])
    structure.add_box(-24, 2, -116, 24, 5, -92, "wall_iron")
    # Heavy soot-blackened iron crucible vat & glowing molten iron charge on top
    structure.add_box(-16, 5, -110, 16, 15, -98, "detail_utility", region_name="coal_slag")
    structure.add_box(-17, 15, -111, 17, 16.5, -97, "detail_utility", region_name="hazard_yellow")
    # Cast-iron mold flasks & glowing slag spout on top of the casting stand
    structure.add_box(-12, 16.5, -107, -3, 19.0, -101, "wall_iron")
    structure.add_box(3, 16.5, -107, 12, 19.0, -101, "wall_iron")
    structure.add_box(-2, 16.5, -108, 2, 18.2, -100, "detail_utility", region_name="iron_plate")
    # Steel mesh & safety-yellow slag guard screens ("HOT IRON", 48x16) on front & rear of casting pit
    structure.add_panel_z(-24, 5, 24, 21, -92.1, "+z", "glass_factory", "slag_shield")
    structure.add_panel_z(-24, 5, 24, 21, -115.9, "-z", "glass_factory", "slag_shield")

    # 8C. 4 Heavy Riveted-Iron Crane Stanchions & Overhead Safety-Yellow Travelling Bridge Crane
    for px0, px1, pz0, pz1 in (
        (-48, -44, -76, -72), (44, 48, -76, -72),
        (-48, -44, -136, -132), (44, 48, -136, -132),
    ):
        structure.add_box(px0, 2, pz0, px1, 54, pz1, "wall_iron")
    # Safety-yellow overhead crane runway rails & central hoist bridge girder spanning cleanly between stanchions (segments <= 64 art px)
    for gx0, gx1 in ((-44, 0), (0, 44)):
        structure.add_box(gx0, 45, -76, gx1, 49, -72, "detail_utility", region_name="hazard_yellow")
        structure.add_box(gx0, 45, -136, gx1, 49, -132, "detail_utility", region_name="hazard_yellow")
    structure.add_box(-48, 45, -132, -44, 49, -76, "wall_iron")
    structure.add_box(44, 45, -132, 48, 49, -76, "wall_iron")
    # Travelling hoist trolley & iron chain block suspended over the center molten crucible!
    for gx0, gx1 in ((-44, 0), (0, 44)):
        structure.add_box(gx0, 44, -107, gx1, 48, -101, "detail_utility", region_name="hazard_yellow")
    structure.add_box(-6, 36, -108, 6, 44, -100, "wall_iron")
    structure.add_box(-2, 26, -105, 2, 36, -103, "detail_utility", region_name="iron_plate")
    # 8 warm incandescent high-bay factory lamps along the crane rig (ny = -1 -> spawns Filament lights!)
    for lx in (-28, 12):
        structure.add_panel_y(lx, -76, lx + 16, -72, 44.85, "-y", "detail_hall", "lamp")
        structure.add_panel_y(lx, -136, lx + 16, -132, 44.85, "-y", "detail_hall", "lamp")
    for lz in (-118, -94):
        structure.add_panel_y(-48, lz, -44, lz + 16, 44.85, "-y", "detail_hall", "lamp")
        structure.add_panel_y(44, lz, 48, lz + 16, 44.85, "-y", "detail_hall", "lamp")

    # 8D. Elevated Rear Oiled-Timber Foreman's Catwalk Mezzanine (z in [-228, -172], y in [18, 20])
    structure.add_box(-124, 18, -228, 124, 20, -172, "wood_timber")
    structure.add_box(-124, 16, -175, 124, 18, -172, "wall_iron")
    for mx in (-84, -28, 28, 84):
        structure.add_box(mx - 2, 2, -176, mx + 2, 16, -172, "wall_iron")
    # Open safety-yellow & iron-pipe foreman catwalk guardrails (3 x 64x14 mezz_rail panels)
    structure.add_panel_z(-96, 20, -32, 34, -172.1, "+z", "glass_factory", "mezz_rail")
    structure.add_panel_z(-32, 20, 32, 34, -172.1, "+z", "glass_factory", "mezz_rail")
    structure.add_panel_z(32, 20, 96, 34, -172.1, "+z", "glass_factory", "mezz_rail")
    # West industrial steel & timber stair ascending to the foreman's catwalk
    for s in range(6):
        sy = 2 + s * 3
        sz0 = -172 + (5 - s) * 3
        structure.add_box(-122, 2, sz0, -100, sy + 3, sz0 + 3, "wall_stone")
        structure.add_box(-122, sy + 2.5, sz0, -100, sy + 3.2, sz0 + 3, "wood_timber", faces=["+y"])

    # 8E. Massive 128x40 Rear Brick & Iron Blast-Furnace Hearth Bank + Twin Quench & Blower Tanks
    structure.add_box(-66, 15, -228, 66, 56, -226.8, "wall_iron", faces=["+z", "+x", "-x", "+y", "-y"])
    structure.add_panel_z(-64, 16, 64, 56, -226.4, "+z", "detail_hall", "furnace_wall")
    # Twin riveted-iron furnace air-blower & quench tanks flanking the hearth
    for sx0, sx1 in ((-116, -78), (78, 116)):
        structure.add_box(sx0, 2, -228, sx1, 18, -208, "detail_utility", region_name="coal_slag")
        structure.add_box(sx0 + 2, 20, -228, sx1 - 2, 52, -210, "detail_utility", region_name="iron_plate")

    # 8F. East Wall Heavy Machinist Workbench & Tool-Crib Rack (x in [90, 124], z in [-156, -52])
    structure.add_box(90, 2, -156, 102, 15, -52, "wall_iron")
    structure.add_box(88, 15, -158, 103, 17, -50, "wood_timber")
    structure.add_box(116, 2, -154, 124, 14, -54, "wall_iron")
    # 96x24 illuminated machinist pegboard tool rack & blueprints facing -X across the factory floor
    structure.add_box(122.4, 15, -152, 124, 39, -56, "wall_iron", faces=["-x", "+z", "-z", "+y", "-y"])
    structure.add_panel_x(-152, 15, -56, 39, 122.0, "-x", "detail_hall", "tool_wall")
    # Heavy cast-iron anvils / shop work stools along the machinist bench
    for bz in (-144, -124, -104, -84, -64):
        structure.add_box(78, 2, bz - 2.5, 83, 10, bz + 2.5, "wall_iron")
        structure.add_box(77.5, 10, bz - 3, 83.5, 12, bz + 3, "wood_timber")

    # 8G. West Wall Sand-Casting Flask & Wooden Pattern Benches (x in [-124, -90], z in [-140, -40])
    for bz0, bz1 in ((-136, -110), (-102, -76), (-68, -42)):
        structure.add_box(-124, 2, bz0, -98, 11, bz1, "detail_utility", region_name="crate_wood")
        structure.add_box(-124, 11, bz0 + 2, -112, 20, bz1 - 2, "detail_utility", region_name="crate_wood")
        # Heavy raw pig-iron billet table in front of each workbay
        structure.add_box(-96, 2, bz0 + 6, -86, 10, bz1 - 6, "wall_iron")

    # ==========================================================================
    # 9. COLLISION BOXES (Doorways strictly clear / gated; factory floor walkable!)
    # ==========================================================================
    coll.add_box(-135, 2, -29, -103, 204, 5, "brick_smokestack_tower")
    coll.add_box(-104, 2, -4, -16, 56, 0, "front_wall_west")
    coll.add_box(16, 2, -4, 128, 56, 0, "front_wall_east")
    coll.add_box(-16, 2, -2, 16, 38, 0, "door_main_gate", gate_node="door_main")

    coll.add_box(-128, 2, -88, -124, 56, -28, "west_wall_front")
    coll.add_box(-128, 2, -112, -124, 34, -88, "door_side_gate", gate_node="door_side")
    coll.add_box(-128, 2, -232, -124, 56, -112, "west_wall_rear")

    coll.add_box(124, 2, -232, 128, 56, -4, "east_wall")

    coll.add_box(-124, 2, -232, 40, 56, -228, "rear_wall_west")
    coll.add_box(40, 2, -232, 64, 34, -228, "door_back_gate", gate_node="door_back")
    coll.add_box(64, 2, -232, 124, 56, -228, "rear_wall_east")

    # Gatehouse gantry piers ONLY (central x in [-48, 48] freight driveway is 100% open!)
    coll.add_box(-64, 2, 48, -48, 50, 60, "gantry_pier_west")
    coll.add_box(48, 2, 48, 64, 50, 60, "gantry_pier_east")

    # Front yard perimeter walls, crate stacks & weigh booth
    coll.add_box(-128, 2, 4, -122, 24, 56, "yard_wall_west")
    coll.add_box(-128, 2, 50, -64, 24, 56, "yard_wall_front_west")
    coll.add_box(122, 2, 0, 128, 24, 56, "yard_wall_east")
    coll.add_box(64, 2, 50, 128, 24, 56, "yard_wall_front_east")
    coll.add_box(100, 2, 11, 121, 16, 43, "weigh_booth_counter")
    coll.add_box(-112, 4, 14, -62, 24, 42, "west_yard_crate_stacks")

    # Rear freight loading dock, exhaust stacks & east coal bunker
    coll.add_box(-112, 2, -266, -12, 10, -232, "rear_loading_dock")
    coll.add_box(82, 2, -246, 118, 55, -232, "rear_exhaust_stacks")
    coll.add_box(134, 2, -220, 174, 19, -158, "east_coal_bunker")

    # Interior solid foundry obstacles
    coll.add_box(-24, 2, -116, 24, 21, -92, "center_molten_crucible_pit")
    coll.add_box(88, 2, -158, 124, 17, -50, "east_machinist_bench")
    coll.add_box(-116, 2, -228, -78, 18, -208, "rear_quench_tank_west")
    coll.add_box(78, 2, -228, 116, 18, -208, "rear_quench_tank_east")
    coll.add_box(-124, 2, -136, -98, 20, -42, "west_casting_benches")

    buckets = {
        "lot": lot,
        "structure": structure,
        "yard_gantry": yard_gantry,
        "foundry_yard": foundry_yard,
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

    for bname in ("structure", "lot", "yard_gantry", "foundry_yard", "service_yard", "collision"):
        pack_bucket(bname, buckets[bname])

    s45 = math.sin(math.pi / 4.0)
    c45 = math.cos(math.pi / 4.0)

    nodes = [
        {
            "name": "foundry_factory",
            "children": [1, 2, 3, 4, 5, 6, 7, 8, 9],
        },
        {"name": "structure", "mesh": mesh_idx_by_name["structure"]},
        {"name": "lot", "mesh": mesh_idx_by_name["lot"]},
        {"name": "yard_gantry", "mesh": mesh_idx_by_name["yard_gantry"]},
        {"name": "foundry_yard", "mesh": mesh_idx_by_name["foundry_yard"]},
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
            "generator": "THE FOUNDRY (1938 Historic Factory) Procedural Landmark Builder",
        },
        "scene": 0,
        "scenes": [{"name": "foundry_factory_scene", "nodes": [0]}],
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

    glb_path = R / os.environ.get("FACTORY_OUT", "foundry-factory.glb")
    glb_path.write_bytes(header + json_chunk + bin_chunk)

    vis_tris = sum(
        buckets[k].tri_count()
        for k in ("structure", "lot", "yard_gantry", "foundry_yard", "service_yard")
    )
    coll_tris = buckets["collision"].tri_count()

    info = {
        "name": "The Foundry Ironworks",
        "kind": "landmark_glb_foundry_factory",
        "role": "factory",
        "room": "hall",
        "era": {"from": 1924, "to": 2015, "built": 1938},
        "glb": "foundry-factory.glb",
        "texel_size_m": T,
        "art_pixel_m": U,
        "footprint_m": {
            "lot_width": round(368 * U, 4),
            "lot_depth": round(384 * U, 4),
            "building_width": round(256 * U, 4),
            "building_depth": round(232 * U, 4),
            "max_height": round(206 * U, 4),
        },
        "triangles": {
            "visible": vis_tris,
            "collision": coll_tris,
            "by_node": {
                k: buckets[k].tri_count()
                for k in ("structure", "lot", "yard_gantry", "foundry_yard", "service_yard", "collision")
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
    print(f"Wrote foundry-factory.glb ({glb_path.stat().st_size} bytes)")
    print(f"  Visible triangles: {vis_tris} | Collision triangles: {coll_tris}")
    for k in ("structure", "lot", "yard_gantry", "foundry_yard", "service_yard"):
        print(f"    {k}: {buckets[k].tri_count()} tris")


def main():
    buckets, coll = build_scene()
    write_glb(buckets, coll)


if __name__ == "__main__":
    main()
