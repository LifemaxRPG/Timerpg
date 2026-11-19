#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) generator for Reel Time Video — a 4-bay small-town strip centre
exported in four historical states sharing ONE building shell, lot, origin, and door layout:
  - reel-time-video-1984.glb (1982-1992): 1-bay family video rental store, quiet neighbours, vacant Bay 3
  - reel-time-video-1996.glb (1993-2006): Expanded 2-bay Reel Time Video, brighter backlit sign, curb drop box
  - reel-time-video-2011.glb (2007-2016): STORE CLOSING / EVERYTHING MUST GO, unlit faded sign, half-empty shelves
  - reel-time-video-2020.glb (2017-2030): Reused as Cell Fix phone repair with cheap vinyl banner over old fascia ghost & bolt holes

Follows docs/CHATGPT_MODEL_GUIDE.md strictly:
- 0.0388 m/texel on 2x2 art-pixel blocks (U = 0.0776 m)
- Under 5,000 triangles per state; 11 materials (<= 12)
- Emissive glow strictly limited to lit box-sign faces and tiny walkway soffit bulbs (pure black mask elsewhere)
- Full-height collision boxes (y = 0 to full top) listed in model-info.json
- Lot dressing separated cleanly under the 'lot' parent node
"""
import json
import math
import struct
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
ART_MANIFEST = json.loads((R / "art-regions.json").read_text(encoding="utf-8"))

T = 0.0388       # 1 texel in meters
U = 0.0776       # 1 art pixel (2x2 texels) in meters

STATE_SPECS = [
    {
        "year": 1984,
        "id": "1984",
        "filename": "reel-time-video-1984.glb",
        "title": "Reel Time Video (1984)",
        "era": {"from": 1982, "to": 1992, "built": 1981},
        "summary": "Family-run single-bay video rental store in Bay 2 with hand-lettered fictional film posters, NEW RELEASES banner, wall VHS return slot, and plain lit box sign; Bay 3 vacant FOR LEASE.",
    },
    {
        "year": 1996,
        "id": "1996",
        "filename": "reel-time-video-1996.glb",
        "title": "Reel Time Video (1996)",
        "era": {"from": 1993, "to": 2006, "built": 1981},
        "summary": "Expanded into Bay 2 and Bay 3 with a wider brighter backlit fascia sign, 2 NIGHT RENTALS window banner, full double-bay rental aisles, and a curbside VHS return drop box.",
    },
    {
        "year": 2011,
        "id": "2011",
        "filename": "reel-time-video-2011.glb",
        "title": "Reel Time Video — Store Closing (2011)",
        "era": {"from": 2007, "to": 2016, "built": 1981},
        "summary": "Liquidation state: unlit sun-faded fascia sign, yellow/red STORE CLOSING and EVERYTHING MUST GO window paper posters, taped-shut curb drop box, and half-empty shelves visible through the glass.",
    },
    {
        "year": 2020,
        "id": "2020",
        "filename": "reel-time-video-2020.glb",
        "title": "Cell Fix / Strip Centre Reuse (2020)",
        "era": {"from": 2017, "to": 2030, "built": 1981},
        "summary": "Re-divided and reused: Bay 2 houses a budget phone repair shop (CELL FIX) with an unlit vinyl banner strapped over the old sign frame, leaving the ghost outline and rusted bolt holes exposed across the fascia; Bay 3 back to FOR LEASE.",
    },
]


def read_png_size(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    return w, h


def get_material_defs(year):
    return [
        {"name": "wall_block", "file": "block_courses.png", "rough": 0.92, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "wall_stucco", "file": "stucco_fascia.png", "rough": 0.90, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "roof_gravel", "file": "ballast.png", "rough": 0.96, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "roof_cap", "file": "roof_membrane_grey.png", "rough": 0.85, "metal": 0.05, "alpha": "OPAQUE"},
        {"name": "ground_asphalt", "file": "asphalt_worn.png", "rough": 0.95, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "ground_pavement", "file": "pavement.png", "rough": 0.88, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "wood_shelf", "file": "plank_horizontal.png", "rough": 0.84, "metal": 0.0, "alpha": "OPAQUE"},
        {"name": "glass_storefront", "file": f"glass_{year}.png", "rough": 0.16, "metal": 0.05, "alpha": "BLEND", "doubleSided": True},
        {"name": "sign_fascia", "file": f"signs_{year}.png", "rough": 0.72, "metal": 0.04, "alpha": "OPAQUE"},
        {
            "name": "glow_signs",
            "file": f"glow_base_{year}.png",
            "emissive_file": f"glow_mask_{year}.png",
            "rough": 0.42,
            "metal": 0.0,
            "alpha": "OPAQUE",
            "emissive": [1.0, 1.0, 1.0],
        },
        {"name": "detail_utility", "file": "utility.png", "rough": 0.72, "metal": 0.14, "alpha": "OPAQUE"},
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
    def __init__(self, name, mat_idx, mat_size, regions):
        self.name = name
        self.mat_idx = mat_idx
        self.mat_size = mat_size
        self.regions = regions
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
                u = v_dot(p, e_u) / (W * T)
                v = -v_dot(p, e_v) / (H * T)
                prim["pos"].append(p)
                prim["nrm"].append(n)
                prim["uv"].append((u, v))

        for i in range(1, len(pts_m) - 1):
            prim["idx"].extend((base, base + i, base + i + 1))

    def _get_rect(self, region_name):
        if region_name is None:
            return None
        return self.regions[region_name]["rect_art"]

    def add_box(self, x0, y0, z0, x1, y1, z1, mat_name, faces="all", region_name=None):
        fset = {"+x", "-x", "+y", "-y", "+z", "-z"} if faces == "all" else set(faces)
        rect = self._get_rect(region_name)

        if "+z" in fset:
            self.add_polygon([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
                             mat_name, u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
        if "-z" in fset:
            self.add_polygon([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)],
                             mat_name, u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
        if "+x" in fset:
            self.add_polygon([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)],
                             mat_name, u_axis=(0, 0, -1), v_axis=(0, 1, 0), uv_rect_art=rect)
        if "-x" in fset:
            self.add_polygon([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
                             mat_name, u_axis=(0, 0, 1), v_axis=(0, 1, 0), uv_rect_art=rect)
        if "+y" in fset:
            self.add_polygon([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)],
                             mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=rect)
        if "-y" in fset:
            self.add_polygon([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
                             mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, 1), uv_rect_art=rect)

    def add_panel_z(self, x0, y0, x1, y1, z, facing, mat_name, region_name):
        rect = self._get_rect(region_name)
        if facing == "+z":
            self.add_polygon([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)],
                             mat_name, u_axis=(1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)
        else:
            self.add_polygon([(x1, y0, z), (x0, y0, z), (x0, y1, z), (x1, y1, z)],
                             mat_name, u_axis=(-1, 0, 0), v_axis=(0, 1, 0), uv_rect_art=rect)

    def add_panel_y(self, x0, z0, x1, z1, y, facing, mat_name, region_name):
        rect = self._get_rect(region_name)
        if facing == "+y":
            self.add_polygon([(x0, y, z1), (x1, y, z1), (x1, y, z0), (x0, y, z0)],
                             mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, -1), uv_rect_art=rect)
        else:
            self.add_polygon([(x0, y, z0), (x1, y, z0), (x1, y, z1), (x0, y, z1)],
                             mat_name, u_axis=(1, 0, 0), v_axis=(0, 0, 1), uv_rect_art=rect)

    def tri_count(self):
        return sum(len(p["idx"]) // 3 for p in self.prims.values())


class CollisionRecorder:
    """Records FULL-HEIGHT collision boxes (y = 0 to full top) in both glTF mesh and model-info.json."""

    def __init__(self, mat_idx, mat_size, regions):
        self.bucket = MeshBucket("collision", mat_idx, mat_size, regions)
        self.boxes = []

    def add_box(self, x0, y0, z0, x1, y1, z1, source, gate_node=None):
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "wall_block")
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
            "height_top_m": round(y1 * U, 4),
        }
        if gate_node:
            entry["gate_node"] = gate_node
        self.boxes.append(entry)


def build_state(spec):
    year = spec["year"]
    mat_defs = get_material_defs(year)
    mat_idx = {m["name"]: i for i, m in enumerate(mat_defs)}
    mat_size = {m["name"]: read_png_size(TEX_DIR / m["file"]) for m in mat_defs}

    regions = {}
    regions.update(ART_MANIFEST["regions"]["utility"])
    regions.update(ART_MANIFEST["regions"]["states"][str(year)])

    structure = MeshBucket("structure", mat_idx, mat_size, regions)
    lot_paving = MeshBucket("lot", mat_idx, mat_size, regions)
    parking_lot = MeshBucket("parking_lot", mat_idx, mat_size, regions)
    rear_service = MeshBucket("rear_service", mat_idx, mat_size, regions)
    curb_props = MeshBucket("curb_props", mat_idx, mat_size, regions)
    coll = CollisionRecorder(mat_idx, mat_size, regions)

    # ==========================================================================
    # 1. LOT PAVING (464 x 368 art px = 36.01 m x 28.56 m; building front at z = 0)
    # ==========================================================================
    # Front 12-space parking lot asphalt (z in [32, 152])
    lot_paving.add_box(-232, 0, 32, 232, 2, 152, "ground_asphalt", faces=["+y", "+z", "-x", "+x"])
    # Side access strips (west & east) + rear service lane (z in [-216, -144])
    lot_paving.add_box(-232, 0, -216, -208, 2, 32, "ground_asphalt", faces=["+y", "-x", "-z"])
    lot_paving.add_box(208, 0, -216, 232, 2, 32, "ground_asphalt", faces=["+y", "+x", "-z"])
    lot_paving.add_box(-208, 0, -216, 208, 2, -144, "ground_asphalt", faces=["+y", "-z"])
    # Covered front walkway concrete sidewalk (z in [0, 32])
    lot_paving.add_box(-212, 0, 0, 212, 2, 32, "ground_pavement", faces=["+y", "+z", "-x", "+x"])
    # Clean interior poured-concrete floor shell at entrance level (y = 2)
    lot_paving.add_box(-208, 0, -144, 208, 2, 0, "ground_pavement", faces=["+y"])

    # Perimeter concrete parking-lot curbs (west, east, rear lane edge)
    lot_paving.add_box(-232, 2, -216, -228, 4, 152, "wall_block", faces=["+y", "+x", "-x", "+z", "-z"])
    lot_paving.add_box(228, 2, -216, 232, 4, 152, "wall_block", faces=["+y", "+x", "-x", "+z", "-z"])
    lot_paving.add_box(-228, 2, -216, 228, 4, -212, "wall_block", faces=["+y", "+z", "-z"])

    # ==========================================================================
    # 2. LOT DRESSING: 12-SPACE FRONT PARKING LOT (child of 'lot', < 500 tris)
    #    12 stalls x 32 art px wide = 384 art px (x in [-192, +192]), depth 64 art px (z in [36, 100])
    # ==========================================================================
    for i in range(13):
        sx = -192 + i * 32
        parking_lot.add_panel_y(sx - 1, 36, sx + 1, 100, 2.2, "+y", "detail_utility", "lot_paint")
    # 12 concrete wheel stops at the front of each stall
    for i in range(12):
        cx = -176 + i * 32
        parking_lot.add_box(cx - 10, 2, 40, cx + 10, 4.2, 44, "detail_utility",
                            faces=["+y", "+z", "-z", "+x", "-x"], region_name="lot_paint")

    # ==========================================================================
    # 3. MAIN 4-BAY STRIP-CENTRE SHELL (32.28 m x 11.17 m, x in [-208, 208], z in [-144, 0])
    #    4 bays of 104 art px each:
    #      Bay 1 [-208, -104]: Suds Laundry
    #      Bay 2 [-104,    0]: Reel Time Video (1984-2011) / Cell Fix (2020)
    #      Bay 3 [   0,  104]: FOR LEASE (1984/2020) / Expanded Reel Time Video (1996/2011)
    #      Bay 4 [ 104,  208]: Ruby Nails
    # ==========================================================================
    # --- FRONT WALL BULKHEADS, PIERS & STOREFRONT GLAZING (z in [-4, 0], y in [2, 42]) ---
    # Solid piers between bays & corners
    for px0, px1 in ((-208, -200), (-112, -104), (0, 8), (96, 112), (200, 208)):
        structure.add_box(px0, 2, -4, px1, 42, 0, "wall_block")
    # Lower block bulkheads beneath display windows (y in [2, 8]) & upper lintel band (y in [38, 42])
    for wx0, wx1 in ((-200, -112), (-104, -36), (8, 96), (112, 200)):
        structure.add_box(wx0, 2, -4, wx1, 8, 0, "wall_block")
        structure.add_box(wx0, 38, -4, wx1, 42, 0, "wall_block")
    # Bay 2 pier beside hero_door (x in [-36, -22]) + transom above hero_door (x in [-22, 0], y in [32, 42])
    structure.add_box(-36, 2, -4, -22, 42, 0, "wall_block")
    structure.add_box(-22, 32, -4, 0, 42, 0, "wall_block")

    # Recessed metal VHS wall return slot beside Bay 2 door (1984, 1996, 2011)
    if year in (1984, 1996, 2011):
        structure.add_panel_z(-36, 12, -22, 28, 0.3, "+z", "sign_fascia", "vhs_return_slot")

    # Aluminum-framed storefront glazing (BLEND, lit by Filament at night via glass_* material!)
    structure.add_panel_z(-200, 8, -112, 38, -1.2, "+z", "glass_storefront", "bay1_win")
    structure.add_panel_z(-104, 8, -36, 38, -1.2, "+z", "glass_storefront", "bay2_glass")
    structure.add_panel_z(-22, 2, 0, 32, -0.6, "+z", "glass_storefront", "hero_door")
    structure.add_panel_z(8, 8, 96, 38, -1.2, "+z", "glass_storefront", "bay3_glass")
    structure.add_panel_z(112, 8, 200, 38, -1.2, "+z", "glass_storefront", "bay4_win")

    # --- SIDE & REAR SPLIT-FACE CONCRETE BLOCK WALLS (full parapet height y = 50) ---
    # West wall (x in [-208, -204]) & East wall (x in [204, 208])
    structure.add_box(-208, 2, -144, -204, 50, 0, "wall_block")
    structure.add_box(204, 2, -144, 208, 50, 0, "wall_block")

    # Rear wall (z in [-144, -140], y in [2, 50]) with 4 metal back service doors (one per bay!)
    # Doors at: Bay 1 [-164,-148], Bay 2 (door_back) [-19,-3], Bay 3 [44,60], Bay 4 [148,164], y in [2, 30]
    rear_doors = [(-164, -148), (-19, -3), (44, 60), (148, 164)]
    cur_x = -204
    for dx0, dx1 in rear_doors:
        structure.add_box(cur_x, 2, -144, dx0, 50, -140, "wall_block")
        structure.add_box(dx0, 30, -144, dx1, 50, -140, "wall_block")
        # Metal back service door leaf (recessed at z = -142.5) + galvanized steel rain hood
        structure.add_box(dx0, 2, -143, dx1, 30, -141, "detail_utility",
                          faces=["-z", "+z"], region_name="galvanized_metal")
        structure.add_box(dx0 - 2, 30, -148, dx1 + 2, 32, -144, "detail_utility",
                          region_name="galvanized_metal")
        cur_x = dx1
    structure.add_box(cur_x, 2, -144, 204, 50, -140, "wall_block")

    # --- COVERED FRONT WALKWAY CANOPY, STUCCO FASCIA & 5 WALKWAY COLUMNS ---
    # Cantilevered stucco fascia beam (x in [-210, 210], z in [0, 32], y in [38, 56])
    structure.add_box(-210, 38, 0, 210, 56, 32, "wall_stucco", faces=["+z", "+x", "-x", "-z"])
    # Walkway underside soffit (y = 38) & top coping cap (y = 56..58)
    structure.add_box(-210, 37.5, 0, 210, 38.5, 32, "roof_cap", faces=["-y"])
    structure.add_box(-211, 56, -2, 211, 58, 33, "roof_cap")
    # 5 slender split-face block walkway columns supporting the front canopy
    for cx in (-204, -104, 0, 104, 204):
        structure.add_box(cx - 2, 2, 26, cx + 2, 38, 30, "wall_block")
    # 4 small recessed walkway soffit bulbs (6x4 art px, ny = -1 -> spawns Filament lights!)
    for lx in (-156, -52, 52, 156):
        structure.add_panel_y(lx - 3, 14, lx + 3, 18, 37.35, "-y", "glow_signs", "soffit_lamp")

    # --- FLAT TAR-AND-GRAVEL ROOF, PARAPET COPING & 4 ROOFTOP HVAC UNITS ---
    # Interior clean ceiling at y = 42, flat tar-and-gravel roof deck at y = 44 (ballast swatch!)
    structure.add_box(-204, 42, -140, 204, 44, 0, "roof_cap", faces=["-y"])
    structure.add_box(-204, 43.5, -140, 204, 44.5, 0, "roof_gravel", faces=["+y"])
    # Metal parapet coping along west, east, and rear roof walls (y in [50, 52])
    structure.add_box(-209, 50, -145, -203, 52, 0, "roof_cap")
    structure.add_box(203, 50, -145, 209, 52, 0, "roof_cap")
    structure.add_box(-203, 50, -145, 203, 52, -139, "roof_cap")
    # 4 compact galvanized rooftop HVAC units (1 per bay, peak height y = 62 = 4.81 m)
    for hx, hz in ((-158, -92), (-54, -96), (54, -88), (156, -94)):
        structure.add_box(hx - 12, 44.5, hz - 12, hx + 12, 58, hz + 12, "detail_utility", region_name="galvanized_metal")
        structure.add_box(hx - 10, 58, hz - 10, hx + 10, 60, hz + 10, "roof_cap")
    # Two small galvanized roof exhaust vent stacks (peak y = 64 = 4.97 m)
    for vx, vz in ((-132, -52), (178, -60)):
        structure.add_box(vx - 3, 44.5, vz - 3, vx + 3, 62, vz + 3, "detail_utility", region_name="galvanized_metal")
        structure.add_box(vx - 4.5, 62, vz - 4.5, vx + 4.5, 64, vz + 4.5, "roof_cap")

    # ==========================================================================
    # 4. RESTRAINED FASCIA SIGNAGE (ONE simple sign per occupied bay; NO clutter!)
    # ==========================================================================
    # Quiet neighbour Bay 1: SUDS LAUNDRY (76x11)
    structure.add_box(-194, 41.5, 32, -118, 52.5, 32.8, "roof_cap", faces=["+x", "-x", "+y", "-y"])
    structure.add_panel_z(-194, 41.5, -118, 52.5, 32.9, "+z", "glow_signs", "bay1_sign")
    # Quiet neighbour Bay 4: RUBY NAILS (64x11)
    structure.add_box(124, 41.5, 32, 188, 52.5, 32.8, "roof_cap", faces=["+x", "-x", "+y", "-y"])
    structure.add_panel_z(124, 41.5, 188, 52.5, 32.9, "+z", "glow_signs", "bay4_sign")

    # Hero store fascia across the 4 decades:
    if year == 1984:
        # Plain warm-lit 1-bay box sign centered over Bay 2 (96x12)
        structure.add_box(-100, 41, 32, -4, 53, 33.4, "roof_cap", faces=["+x", "-x", "+y", "-y"])
        structure.add_panel_z(-100, 41, -4, 53, 33.5, "+z", "glow_signs", "hero_sign_1984")
    elif year == 1996:
        # Wider, brighter backlit 2-bay box sign centered across Bay 2 & Bay 3 (110x12)
        structure.add_box(-55, 41, 32, 55, 53, 33.8, "roof_cap", faces=["+x", "-x", "+y", "-y"])
        structure.add_panel_z(-55, 41, 55, 53, 33.9, "+z", "glow_signs", "hero_sign_1996")
    elif year == 2011:
        # Same 2-bay box sign, now UNLIT & sun-faded (opaque sign_fascia — ZERO emissive!)
        structure.add_box(-55, 41, 32, 55, 53, 33.8, "roof_cap", faces=["+x", "-x", "+y", "-y"])
        structure.add_panel_z(-55, 41, 55, 53, 33.9, "+z", "sign_fascia", "hero_sign_2011_faded")
    elif year == 2020:
        # Old sign box removed: weathered ghost outline & rusted bolt holes scarred across the stucco fascia (110x12)
        structure.add_panel_z(-55, 41, 55, 53, 32.25, "+z", "sign_fascia", "fascia_ghost_2020")
        # Cheap unlit vinyl banner "CELL FIX" (68x12) strapped over Bay 2's portion of the old frame
        structure.add_box(-86, 41, 32.25, -18, 53, 32.85, "roof_cap", faces=["+x", "-x", "+y", "-y"])
        structure.add_panel_z(-86, 41, -18, 53, 32.95, "+z", "sign_fascia", "banner_2020")

    # ==========================================================================
    # 5. LOT DRESSING: CURBSIDE DROP BOX (1996 active / 2011 taped shut)
    # ==========================================================================
    if year in (1996, 2011):
        # Freestanding curbside VHS return drop box near Bay 2/3 walkway edge (x in [6, 22], z in [20, 30], y in [2, 24])
        curb_props.add_box(6, 2, 20, 22, 24, 29.8, "detail_utility",
                           faces=["-z", "+x", "-x", "+y"], region_name="dumpster_green")
        curb_props.add_panel_z(6, 2, 22, 24, 30.0, "+z", "sign_fascia", "dropbox_front")
        # Small angled steel rain hood atop the drop box
        curb_props.add_box(5, 24, 19, 23, 25.5, 31, "roof_cap")
        coll.add_box(5, 0, 19, 23, 25.5, 31, "curbside_vhs_drop_box")
    elif year == 2020:
        # Subtle concrete anchor patch on sidewalk where the drop box used to sit
        curb_props.add_box(7, 2, 21, 21, 2.25, 29, "roof_cap", faces=["+y"])

    # ==========================================================================
    # 6. LOT DRESSING: REAR SERVICE BINS, ENCLOSURE & GREASE TRAP (child of 'lot', < 500 tris)
    # ==========================================================================
    # Concrete dumpster pad & timber/block rear enclosure behind Bay 1 (x in [-198, -132], z in [-206, -172])
    rear_service.add_box(-198, 2, -206, -132, 3, -172, "ground_pavement", faces=["+y", "+z", "-z", "+x", "-x"])
    rear_service.add_box(-198, 3, -206, -194, 22, -172, "wall_block")
    rear_service.add_box(-194, 3, -176, -136, 22, -172, "wall_block")
    rear_service.add_box(-136, 3, -206, -132, 22, -172, "wall_block")
    # Two commercial muted-green steel dumpsters inside the enclosure
    for dx0, dx1 in ((-190, -166), (-162, -138)):
        rear_service.add_box(dx0, 3, -200, dx1, 18, -180, "detail_utility", region_name="dumpster_green")
        rear_service.add_box(dx0 - 0.5, 18, -200.5, dx1 + 0.5, 19.5, -179.5, "roof_cap")
    coll.add_box(-198, 0, -206, -132, 22, -172, "rear_dumpster_enclosure")

    # Concrete & galvanized cast-iron Grease Trap + vent pipe behind Bay 4 (x in [118, 146], z in [-186, -164])
    rear_service.add_box(118, 2, -186, 146, 4.5, -164, "wall_block")
    rear_service.add_box(122, 4.5, -182, 142, 5.2, -168, "detail_utility",
                         faces=["+y", "+z", "-z", "+x", "-x"], region_name="galvanized_metal")
    # Vertical galvanized vent pipe & electrical meter bank on rear wall
    rear_service.add_box(142, 2, -148, 146, 52, -144, "detail_utility", region_name="galvanized_metal")
    rear_service.add_box(74, 12, -147, 94, 26, -144, "detail_utility", region_name="galvanized_metal")
    coll.add_box(118, 0, -186, 146, 5.2, -164, "rear_grease_trap")

    # ==========================================================================
    # 7. CLEAN INTERIOR SHELL + STORE-STATE SHELVES ONLY (game furnishes the rest!)
    # ==========================================================================
    # Interior demising walls between bays (y in [2, 42])
    structure.add_box(-106, 2, -140, -102, 42, -4, "wall_block")
    structure.add_box(102, 2, -140, 106, 42, -4, "wall_block")

    if year in (1984, 2020):
        # Solid demising wall at x = 0 separating Bay 2 from vacant Bay 3
        structure.add_box(-2, 2, -140, 2, 42, -4, "wall_block")
        coll.add_box(-2, 0, -140, 2, 42, -4, "interior_demising_wall_bay2_bay3")
    else:
        # 1996 & 2011: Expanded 2-bay open floor plan — structural beam + center column only!
        structure.add_box(-2, 34, -140, 2, 42, -4, "wall_block")
        structure.add_box(-2, 2, -74, 2, 34, -68, "wall_block")
        coll.add_box(-2, 0, -74, 2, 42, -68, "interior_center_support_column")

    # Store-state shelves & checkout counter (visible through storefront glass!)
    if year == 1984:
        # Compact family video counter + 3 full stocked VHS rental shelf aisles inside Bay 2
        structure.add_box(-94, 2, -36, -54, 14, -26, "wood_shelf")
        coll.add_box(-94, 0, -36, -54, 14, -26, "store_checkout_counter")
        for idx, sz in enumerate((-56, -84, -112)):
            structure.add_box(-94, 2, sz - 4, -38, 22, sz + 4, "detail_utility",
                              faces=["+z", "-z"], region_name="vhs_shelves")
            structure.add_box(-94, 2, sz - 4, -38, 22, sz + 4, "wood_shelf", faces=["+y", "+x", "-x"])
            coll.add_box(-94, 0, sz - 4, -38, 22, sz + 4, f"vhs_shelf_bay2_{idx + 1}")

    elif year == 1996:
        # Expanded 2-bay store: checkout counter in Bay 2 + 6 full stocked VHS aisles across Bay 2 & Bay 3
        structure.add_box(-94, 2, -36, -48, 14, -26, "wood_shelf")
        coll.add_box(-94, 0, -36, -48, 14, -26, "store_checkout_counter")
        for idx, sz in enumerate((-56, -84, -112)):
            # Bay 2 aisle
            structure.add_box(-94, 2, sz - 4, -38, 22, sz + 4, "detail_utility",
                              faces=["+z", "-z"], region_name="vhs_shelves")
            structure.add_box(-94, 2, sz - 4, -38, 22, sz + 4, "wood_shelf", faces=["+y", "+x", "-x"])
            coll.add_box(-94, 0, sz - 4, -38, 22, sz + 4, f"vhs_shelf_bay2_{idx + 1}")
            # Bay 3 expansion aisle (visible right through Bay 3 windows!)
            structure.add_box(22, 2, sz - 4, 82, 22, sz + 4, "detail_utility",
                              faces=["+z", "-z"], region_name="vhs_shelves")
            structure.add_box(22, 2, sz - 4, 82, 22, sz + 4, "wood_shelf", faces=["+y", "+x", "-x"])
            coll.add_box(22, 0, sz - 4, 82, 22, sz + 4, f"vhs_shelf_bay3_{idx + 1}")

    elif year == 2011:
        # STORE CLOSING: bare counter + half-empty / partially cleared wood shelves visible through glass!
        structure.add_box(-94, 2, -36, -48, 14, -26, "wood_shelf")
        coll.add_box(-94, 0, -36, -48, 14, -26, "store_checkout_counter")
        # Bay 2: only 2 remaining aisles, half bare wood / half sparse tapes
        for idx, (x0, x1, sz, has_tapes) in enumerate([
            (-94, -64, -58, True),
            (-64, -38, -58, False),
            (-86, -44, -96, False),
            (22, 54, -56, False),
            (54, 82, -56, True),
            (26, 74, -96, False),
        ]):
            if has_tapes:
                structure.add_box(x0, 2, sz - 4, x1, 18, sz + 4, "detail_utility",
                                  faces=["+z", "-z"], region_name="vhs_shelves")
                structure.add_box(x0, 2, sz - 4, x1, 18, sz + 4, "wood_shelf", faces=["+y", "+x", "-x"])
            else:
                # Empty bare wood shelving carcass (end uprights + top + 2 bare shelves, see-through gaps!)
                structure.add_box(x0, 2, sz - 4, x0 + 2, 20, sz + 4, "wood_shelf")
                structure.add_box(x1 - 2, 2, sz - 4, x1, 20, sz + 4, "wood_shelf")
                structure.add_box(x0, 10, sz - 3.5, x1, 11.5, sz + 3.5, "wood_shelf")
                structure.add_box(x0, 18.5, sz - 4, x1, 20, sz + 4, "wood_shelf")
            coll.add_box(x0, 0, sz - 4, x1, 20, sz + 4, f"closing_shelf_unit_{idx + 1}")

    elif year == 2020:
        # CELL FIX reuse: video shelves completely removed; clean shell + single simple repair counter in Bay 2
        structure.add_box(-92, 2, -44, -46, 14, -34, "wood_shelf")
        coll.add_box(-92, 0, -44, -46, 14, -34, "cell_fix_repair_counter")

    # ==========================================================================
    # 8. FULL-HEIGHT COLLISION BOXES (Ground y = 0 to FULL building top y = 58 / 52!)
    #    Doorways (door_main and door_back) strictly gated so walkers enter cleanly!
    # ==========================================================================
    # Front wall segments to FULL parapet top (y = 58 -> 4.50 m!) leaving door_main (x in [-22, 0]) gated
    coll.add_box(-208, 0, -4, -22, 58, 0, "front_wall_west_full_height")
    coll.add_box(-22, 0, -4, 0, 32, 0, "door_main_gate", gate_node="door_main")
    coll.add_box(0, 0, -4, 208, 58, 0, "front_wall_east_full_height")

    # Side walls to FULL parapet top (y = 52 -> 4.04 m)
    coll.add_box(-208, 0, -144, -204, 52, -4, "west_wall_full_height")
    coll.add_box(204, 0, -144, 208, 52, -4, "east_wall_full_height")

    # Rear wall to FULL parapet top (y = 52 -> 4.04 m) leaving door_back (x in [-19, -3]) gated
    coll.add_box(-204, 0, -144, -19, 52, -140, "rear_wall_west_full_height")
    coll.add_box(-19, 0, -144, -3, 30, -140, "door_back_gate", gate_node="door_back")
    coll.add_box(-3, 0, -144, 204, 52, -140, "rear_wall_east_full_height")

    # Interior demising walls Bay 1 & Bay 4 to full ceiling height (y = 42)
    coll.add_box(-106, 0, -140, -102, 42, -4, "interior_demising_wall_bay1")
    coll.add_box(102, 0, -140, 106, 42, -4, "interior_demising_wall_bay4")

    # 5 Covered walkway columns from ground y = 0 to FULL fascia top y = 58
    for idx, cx in enumerate((-204, -104, 0, 104, 204)):
        coll.add_box(cx - 2, 0, 26, cx + 2, 58, 30, f"walkway_column_{idx + 1}")

    buckets = {
        "structure": structure,
        "lot": lot_paving,
        "parking_lot": parking_lot,
        "rear_service": rear_service,
        "curb_props": curb_props,
        "collision": coll.bucket,
    }
    return write_glb(spec, mat_defs, mat_size, buckets, coll)


def write_glb(spec, mat_defs, mat_size, buckets, coll):
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

    def add_image_tex(img_name, fname):
        png_bytes = (TEX_DIR / fname).read_bytes()
        bv_idx = append_bytes(png_bytes)
        img_idx = len(images)
        images.append({
            "name": img_name,
            "mimeType": "image/png",
            "bufferView": bv_idx,
        })
        tex_idx = len(textures)
        textures.append({"sampler": 0, "source": img_idx})
        return tex_idx

    for mdef in mat_defs:
        base_tex_idx = add_image_tex(mdef["name"], mdef["file"])
        mat = {
            "name": mdef["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": base_tex_idx},
                "metallicFactor": mdef["metal"],
                "roughnessFactor": mdef["rough"],
            },
            "alphaMode": mdef["alpha"],
        }
        if mdef.get("doubleSided"):
            mat["doubleSided"] = True
        if "emissive_file" in mdef:
            em_tex_idx = add_image_tex(mdef["name"] + "_emissive", mdef["emissive_file"])
            mat["emissiveTexture"] = {"index": em_tex_idx}
            mat["emissiveFactor"] = mdef["emissive"]
        materials.append(mat)

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

    # Node hierarchy: lot dressing (parking_lot, rear_service, curb_props) are children of 'lot'!
    lot_children = []
    nodes = [
        {"name": "reel_time_video", "children": [1, 2, 3, 4, 5]},  # updated below
        {"name": "structure", "mesh": mesh_idx_by_name["structure"]},
        {"name": "lot", "mesh": mesh_idx_by_name["lot"], "children": lot_children},
        {
            "name": "collision",
            "mesh": mesh_idx_by_name["collision"],
            "extras": {"collision_only": True},
        },
        {
            "name": "door_main",
            "translation": [round(-11.0 * U, 6), 0.0, round(2.0 * U, 6)],
            "rotation": [0.0, 0.0, 0.0, 1.0],
        },
        {
            "name": "door_back",
            "translation": [round(-11.0 * U, 6), 0.0, round(-146.0 * U, 6)],
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
        "asset": {
            "version": "2.0",
            "generator": f"Reel Time Video Strip Centre Builder ({spec['year']})",
        },
        "scene": 0,
        "scenes": [{"name": f"reel_time_video_{spec['year']}", "nodes": [0]}],
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

    vis_tris = sum(
        buckets[k].tri_count()
        for k in ("structure", "lot", "parking_lot", "rear_service", "curb_props")
    )
    coll_tris = buckets["collision"].tri_count()

    print(f"Wrote {spec['filename']} ({glb_path.stat().st_size} bytes) | vis={vis_tris} tris | coll={coll_tris} tris")
    return {
        "id": spec["id"],
        "year": spec["year"],
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
            "coordinate_note": "Full-height boxes from ground y=0 to top, Blender Z-up convention (x, y_blender=-z_gltf, z_blender=y_gltf)",
            "boxes_blender_z_up": coll.boxes,
        },
    }


def main():
    states_info = [build_state(spec) for spec in STATE_SPECS]
    model_info = {
        "name": "Reel Time Video",
        "kind": "landmark_glb_reel_time_video",
        "role": "video_store",
        "room": "hall",
        "built_year": 1981,
        "era": {"from": 1982, "to": 2030, "built": 1981},
        "texel_size_m": T,
        "art_pixel_m": U,
        "footprint_m": {
            "lot_width": round(464 * U, 4),
            "lot_depth": round(368 * U, 4),
            "building_width": round(416 * U, 4),
            "building_depth": round(144 * U, 4),
            "canopy_depth": round(32 * U, 4),
            "parapet_front_height": round(58 * U, 4),
            "parapet_rear_height": round(52 * U, 4),
            "max_height": round(64 * U, 4),
        },
        "doors": {
            "door_main": {
                "translation_gltf": [round(-11.0 * U, 4), 0.0, round(2.0 * U, 4)],
                "outward": [0, 0, 1],
            },
            "door_back": {
                "translation_gltf": [round(-11.0 * U, 4), 0.0, round(-146.0 * U, 4)],
                "outward": [0, 0, -1],
            },
        },
        "swatches_used": ART_MANIFEST["shared_swatches"],
        "states": states_info,
    }
    (R / "model-info.json").write_text(json.dumps(model_info, indent=2) + "\n", encoding="utf-8")
    print("Wrote model-info.json covering all 4 states (1984, 1996, 2011, 2020).")


if __name__ == "__main__":
    main()
