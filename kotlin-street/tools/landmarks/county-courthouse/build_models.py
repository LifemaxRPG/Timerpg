#!/usr/bin/env python3
"""
Code-only glTF 2.0 (.glb) builder for the Harlan County Courthouse & Square across four
historical states plus a distant LOD silhouette model:
  - courthouse-1894.glb (1894-1950: as built, gas lamps, bandstand, hitching rails)
  - courthouse-1962.glb (1951-1975: bricked-in ground arches, 2-storey concrete annex, cobra lamps, bandstand pad)
  - courthouse-1988.glb (1976-1999: clock stopped at 4:17 & dark, stained annex, boarded windows, SAVE OUR COURTHOUSE banner, darker spire)
  - courthouse-2008.glb (2000 onward: restored windows & bandstand, annex removed w/ brick patch, working clock + tower floodlights, ramp, Restored 2004 plaque)
  - courthouse-far.glb  (< 400 tris landmark silhouette in the same pixel textures)

Strict compliance with docs/CHATGPT_MODEL_GUIDE.md & docs/GEMINI_NEXT_COURTHOUSE.md:
- Metres, Y up, +Z main entrance, origin (0, 0, 0) at midpoint of +Z curb edge, ground y >= 0.
- 0.0388 m/texel (U = 0.0776 m per 2x2 art pixel), NEAREST filtering, planar orthonormal UVs on all
  curved/sloped/octagonal/pyramidal geometry for 0 UV stretching errors.
- <= 15,000 total instanced visible triangles per near state; < 400 tris for courthouse-far.glb.
- 12 base materials + 4 glow_clock_* materials = 16 max (strictly within spec).
- Reusable glTF mesh instancing for arched windows, park benches, and lamp posts.
- Real 3-storey walkable interior shell with named floor_<room> meshes, door_room_* nodes,
  grand staircase, double-height courtroom with judge's bench & bar rail, and overhead skylight.
- Full-height exterior & interior collision boxes (including stepped stairs/steps/ramp & square props).
"""
import json
import math
import struct
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
ART_MANIFEST = json.loads((R / "art-regions.json").read_text(encoding="utf-8"))

TEXEL = 0.0388
U = TEXEL * 2.0  # 0.0776 m per art pixel

# Square dimensions: 1024 x 1024 art px = 79.4624 m x 79.4624 m
# Origin (0,0,0) = midpoint of +Z front curb edge (x in [-512, 512], z in [-1024, 0])
HALF_SQ = 512
CX = 0.0
CZ = -512.0  # Courthouse center in art px (z = -39.7312 m)

# Vertical levels in art px (1 art px = 0.0776 m)
Y_GROUND = 2.0     # Sidewalk & lawn surface (0.1552 m)
Y_FL1 = 14.0       # Ground floor deck (1.0864 m; 6 steps up from plaza)
Y_FL2 = 72.0       # Floor 2 deck (5.5872 m; 4.50 m storey height)
Y_FL3 = 130.0      # Floor 3 deck (10.0880 m; 4.50 m storey height)
Y_EAVE = 188.0     # Roof eave / Floor 3 ceiling (14.5888 m; 4.50 m storey height)
Y_RIDGE = 250.0    # Steep hipped roof ridge (19.4000 m)
Y_TOWER_CLK = 335.0  # Clock dial center (~26.00 m)
Y_BELFRY_BOT = 362.0 # Open belfry sill (28.09 m)
Y_BELFRY_TOP = 408.0 # Spire eave (31.66 m)
Y_SPIRE_TOP = 472.0  # Spire tip (36.63 m)
Y_FINIAL_TOP = 490.0 # Finial peak (38.02 m)

STATES = [
    {
        "year": 1894,
        "id": "as_built_1894",
        "era_years": [1894, 1950],
        "file": "courthouse-1894.glb",
        "title": "Harlan County Courthouse (1894-1950: As Built)",
        "clock_active": True,
        "clock_time": "10:10",
        "has_bandstand": True,
        "has_hitching_rails": True,
        "has_annex": False,
        "annex_stained": False,
        "bricked_ground_windows": False,
        "boarded_windows": False,
        "has_protest_banner": False,
        "darker_spire": False,
        "has_brick_patch": False,
        "has_ramp": False,
        "has_plaque": False,
        "has_floodlights": False,
        "lamp_style": "gas",
    },
    {
        "year": 1962,
        "id": "modernised_1962",
        "era_years": [1951, 1975],
        "file": "courthouse-1962.glb",
        "title": "Harlan County Courthouse (1951-1975: Modernised)",
        "clock_active": True,
        "clock_time": "10:10",
        "has_bandstand": False,
        "has_hitching_rails": False,
        "has_annex": True,
        "annex_stained": False,
        "bricked_ground_windows": True,
        "boarded_windows": False,
        "has_protest_banner": False,
        "darker_spire": False,
        "has_brick_patch": False,
        "has_ramp": False,
        "has_plaque": False,
        "has_floodlights": False,
        "lamp_style": "cobra",
    },
    {
        "year": 1988,
        "id": "neglect_1988",
        "era_years": [1976, 1999],
        "file": "courthouse-1988.glb",
        "title": "Harlan County Courthouse (1976-1999: Neglect & Protest)",
        "clock_active": False,
        "clock_time": "04:17",
        "has_bandstand": False,
        "has_hitching_rails": False,
        "has_annex": True,
        "annex_stained": True,
        "bricked_ground_windows": True,
        "boarded_windows": True,
        "has_protest_banner": True,
        "darker_spire": True,
        "has_brick_patch": False,
        "has_ramp": False,
        "has_plaque": False,
        "has_floodlights": False,
        "lamp_style": "cobra",
    },
    {
        "year": 2008,
        "id": "restored_2008",
        "era_years": [2000, 2035],
        "file": "courthouse-2008.glb",
        "title": "Harlan County Courthouse (2000+: Restored)",
        "clock_active": True,
        "clock_time": "10:10",
        "has_bandstand": True,
        "has_hitching_rails": False,
        "has_annex": False,
        "annex_stained": False,
        "bricked_ground_windows": False,
        "boarded_windows": False,
        "has_protest_banner": False,
        "darker_spire": False,
        "has_brick_patch": True,
        "has_ramp": True,
        "has_plaque": True,
        "has_floodlights": True,
        "lamp_style": "acorn",
    },
]


def read_png_size(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    return w, h


def get_material_defs(spec):
    """12 base materials + 4 glow_clock_* materials = 16 total (within spec limit)."""
    detail_file = "detail_atlas_1988.png" if spec["annex_stained"] else "detail_atlas.png"
    copper_file = "copper_spire_dark.png" if spec["darker_spire"] else "copper_spire.png"
    clk_base = "clock_dial_lit.png" if spec["clock_active"] else "clock_dial_dark.png"
    clk_emissive = "clock_dial_mask.png" if spec["clock_active"] else None
    clk_ef = [1.0, 1.0, 1.0] if spec["clock_active"] else [0.0, 0.0, 0.0]

    mats = [
        {"name": "stone_rusticated", "file": "stone_rusticated.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "brick_red", "file": "swatch_brick_red.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "roof_slate", "file": "swatch_roof_slate.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "copper_spire", "file": copper_file, "alpha": "OPAQUE", "doubleSided": False},
        {"name": "ground_lawn", "file": "ground_lawn.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "ground_pavement", "file": "swatch_pavement.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "interior_wall", "file": "interior_wall.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "interior_floor", "file": "swatch_plank_horizontal.png", "alpha": "OPAQUE", "doubleSided": False},
        {"name": "detail_atlas", "file": detail_file, "alpha": "OPAQUE", "doubleSided": False},
        {"name": "glass_window", "file": "glass_atlas.png", "alpha": "BLEND", "doubleSided": True},
        {"name": "sign_plates", "file": "sign_plates.png", "alpha": "OPAQUE", "doubleSided": False},
        {
            "name": "glow_lamps",
            "file": "glow_lamps_base.png",
            "emissive_file": "glow_lamps_mask.png",
            "emissive_factor": [1.0, 1.0, 1.0],
            "alpha": "OPAQUE",
            "doubleSided": False,
        },
    ]
    for face_name in ("south", "north", "east", "west"):
        m = {
            "name": f"glow_clock_{face_name}",
            "file": clk_base,
            "emissive_factor": clk_ef,
            "alpha": "OPAQUE",
            "doubleSided": False,
        }
        if clk_emissive:
            m["emissive_file"] = clk_emissive
        mats.append(m)
    return mats


# Vector math helpers
def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def v_cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def v_dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def v_len(a):
    return math.sqrt(v_dot(a, a))


def v_norm(a):
    l = v_len(a)
    return (a[0] / l, a[1] / l, a[2] / l) if l > 1e-9 else (0.0, 1.0, 0.0)


def quat_axis_angle(ax, ay, az, rad):
    s = math.sin(rad * 0.5)
    c = math.cos(rad * 0.5)
    l = math.sqrt(ax * ax + ay * ay + az * az)
    if l < 1e-9:
        return [0.0, 0.0, 0.0, 1.0]
    return [round(ax / l * s, 6), round(ay / l * s, 6), round(az / l * s, 6), round(c, 6)]


def quat_mul(q1, q2):
    x1, y1, z1, w1 = q1
    x2, y2, z2, w2 = q2
    return [
        round(w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2, 6),
        round(w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2, 6),
        round(w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2, 6),
        round(w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2, 6),
    ]


class MeshBucket:
    """Builds pixel-grid-accurate glTF mesh primitives grouped by material."""

    def __init__(self, name, mat_idx, mat_size, regions):
        self.name = name
        self.mat_idx = mat_idx
        self.mat_size = mat_size
        self.regions = regions
        self.prims = {}

    def _prim(self, mat_name):
        if mat_name not in self.prims:
            self.prims[mat_name] = {"pos": [], "norm": [], "uv": [], "idx": []}
        return self.prims[mat_name]

    def _uv_scale(self, mat_name):
        tw, th = self.mat_size[mat_name]
        return (tw * TEXEL, th * TEXEL)

    def _region_info(self, mat_name, region_name):
        r = self.regions[region_name]
        rx, ry, rw, rh = r["rect_art"]
        tw, th = self.mat_size[mat_name]
        art_w = tw / 2.0
        art_h = th / 2.0
        return rx, ry, rw, rh, art_w, art_h

    def add_quad_art(self, pts_art, nrm, mat_name, region_name=None, fit_region=False):
        """pts_art: 4 corners (bl, br, tr, tl) in art-pixel coords, CCW from front."""
        p = self._prim(mat_name)
        base = len(p["pos"])
        pts_m = [(x * U, y * U, z * U) for (x, y, z) in pts_art]
        e_u = v_sub(pts_m[1], pts_m[0])
        e_v = v_sub(pts_m[3], pts_m[0])
        w_m = v_len(e_u)
        h_m = v_len(e_v)
        if w_m < 1e-6 or h_m < 1e-6:
            return
        t_axis = v_norm(e_u)
        b_axis = v_norm(v_cross(nrm, t_axis))

        if region_name:
            rx, ry, rw, rh, art_w, art_h = self._region_info(mat_name, region_name)
            u0 = rx / art_w
            v0 = ry / art_h
            if fit_region:
                u1 = (rx + rw) / art_w
                v1 = (ry + rh) / art_h
                uvs = [(u0, v1), (u1, v1), (u1, v0), (u0, v0)]
            else:
                # Exact orthonormal planar projection anchored cleanly inside the region
                proj = [(v_dot(v_sub(pt, pts_m[0]), t_axis) / U, v_dot(v_sub(pt, pts_m[0]), b_axis) / U) for pt in pts_m]
                min_px = min(a for a, _ in proj)
                max_py = max(b for _, b in proj)
                span_x = max(a for a, _ in proj) - min_px
                span_y = max_py - min(b for _, b in proj)
                assert span_x <= rw + 1e-3 and span_y <= rh + 1e-3, (
                    f"Quad {span_x:.2f}x{span_y:.2f} exceeds region '{region_name}' {rw}x{rh}"
                )
                uvs = [((rx + (ax - min_px)) / art_w, (ry + (max_py - ay)) / art_h) for (ax, ay) in proj]
        else:
            su, sv = self._uv_scale(mat_name)
            uvs = []
            for pt in pts_m:
                u_val = v_dot(v_sub(pt, pts_m[0]), t_axis) / su
                v_val = (h_m - v_dot(v_sub(pt, pts_m[0]), b_axis)) / sv
                uvs.append((u_val, v_val))

        for pt, uv in zip(pts_m, uvs):
            p["pos"].append(pt)
            p["norm"].append(nrm)
            p["uv"].append(uv)
        p["idx"].extend([base, base + 1, base + 2, base, base + 2, base + 3])

    def add_planar_tri_art(self, p0_art, p1_art, p2_art, mat_name, region_name=None):
        """Any arbitrary 3D triangle with exact orthonormal planar pixel-grid UVs (0 UV distortion!)."""
        p0 = (p0_art[0] * U, p0_art[1] * U, p0_art[2] * U)
        p1 = (p1_art[0] * U, p1_art[1] * U, p1_art[2] * U)
        p2 = (p2_art[0] * U, p2_art[1] * U, p2_art[2] * U)
        e1 = v_sub(p1, p0)
        e2 = v_sub(p2, p0)
        n = v_cross(e1, e2)
        nl = v_len(n)
        if nl < 1e-8:
            return
        nrm = (n[0] / nl, n[1] / nl, n[2] / nl)
        t_axis = v_norm(e1)
        b_axis = v_norm(v_cross(nrm, t_axis))

        pts = (p0, p1, p2)
        proj_art = [(v_dot(v_sub(pt, p0), t_axis) / U, v_dot(v_sub(pt, p0), b_axis) / U) for pt in pts]
        min_x = min(a for a, _ in proj_art)
        max_y = max(b for _, b in proj_art)

        if region_name:
            rx, ry, rw, rh, art_w, art_h = self._region_info(mat_name, region_name)
            span_x = max(a for a, _ in proj_art) - min_x
            span_y = max_y - min(b for _, b in proj_art)
            assert span_x <= rw + 1e-3 and span_y <= rh + 1e-3, (
                f"Tri {span_x:.2f}x{span_y:.2f} exceeds region '{region_name}' {rw}x{rh}"
            )
            uvs = [((rx + (ax - min_x)) / art_w, (ry + (max_y - ay)) / art_h) for (ax, ay) in proj_art]
        else:
            su, sv = self._uv_scale(mat_name)
            uvs = [((ax - min_x) * U / su, (max_y - ay) * U / sv) for (ax, ay) in proj_art]

        p = self._prim(mat_name)
        base = len(p["pos"])
        for pt, uv in zip(pts, uvs):
            p["pos"].append(pt)
            p["norm"].append(nrm)
            p["uv"].append(uv)
        p["idx"].extend([base, base + 1, base + 2])

    def add_planar_quad_art(self, p0, p1, p2, p3, mat_name, region_name=None):
        """Arbitrary planar quad (p0,p1,p2,p3 CCW) with unified planar UVs."""
        self.add_planar_tri_art(p0, p1, p2, mat_name, region_name)
        self.add_planar_tri_art(p0, p2, p3, mat_name, region_name)

    def add_box(self, x0, y0, z0, x1, y1, z1, mat_name, faces=None, region_name=None):
        if faces is None:
            faces = ["+x", "-x", "+y", "-y", "+z", "-z"]
        if "+z" in faces:
            self.add_quad_art([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1), mat_name, region_name)
        if "-z" in faces:
            self.add_quad_art([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0, 0, -1), mat_name, region_name)
        if "+x" in faces:
            self.add_quad_art([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1, 0, 0), mat_name, region_name)
        if "-x" in faces:
            self.add_quad_art([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0), mat_name, region_name)
        if "+y" in faces:
            self.add_quad_art([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (0, 1, 0), mat_name, region_name)
        if "-y" in faces:
            self.add_quad_art([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0), mat_name, region_name)

    def add_panel_z(self, x0, y0, x1, y1, z, side, mat_name, region_name=None, fit_region=False):
        if side == "+z":
            self.add_quad_art([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)], (0, 0, 1), mat_name, region_name, fit_region)
        else:
            self.add_quad_art([(x1, y0, z), (x0, y0, z), (x0, y1, z), (x1, y1, z)], (0, 0, -1), mat_name, region_name, fit_region)

    def add_panel_x(self, z0, y0, z1, y1, x, side, mat_name, region_name=None, fit_region=False):
        if side == "+x":
            self.add_quad_art([(x, y0, z1), (x, y0, z0), (x, y1, z0), (x, y1, z1)], (1, 0, 0), mat_name, region_name, fit_region)
        else:
            self.add_quad_art([(x, y0, z0), (x, y0, z1), (x, y1, z1), (x, y1, z0)], (-1, 0, 0), mat_name, region_name, fit_region)

    def add_panel_y(self, x0, z0, x1, z1, y, side, mat_name, region_name=None, fit_region=False):
        if side == "+y":
            self.add_quad_art([(x0, y, z1), (x1, y, z1), (x1, y, z0), (x0, y, z0)], (0, 1, 0), mat_name, region_name, fit_region)
        else:
            self.add_quad_art([(x0, y, z0), (x1, y, z0), (x1, y, z1), (x0, y, z1)], (0, -1, 0), mat_name, region_name, fit_region)

    def add_cylinder(self, cx, y0, y1, cz, r0, r1, sides, mat_name, region_name=None, cap_top=True, cap_bot=False):
        """Tapered N-sided prism (8-12 sides) with exact orthonormal planar UVs."""
        ring0 = []
        ring1 = []
        for i in range(sides):
            ang = 2.0 * math.pi * i / sides
            ca, sa = math.cos(ang), math.sin(ang)
            ring0.append((cx + ca * r0, y0, cz + sa * r0))
            ring1.append((cx + ca * r1, y1, cz + sa * r1))
        for i in range(sides):
            j = (i + 1) % sides
            # Outward winding: ring0[j], ring0[i], ring1[i], ring1[j]
            self.add_planar_quad_art(ring0[j], ring0[i], ring1[i], ring1[j], mat_name, region_name)
        if cap_top and r1 > 1e-4:
            ct = (cx, y1, cz)
            for i in range(sides):
                j = (i + 1) % sides
                self.add_planar_tri_art(ct, ring1[j], ring1[i], mat_name, region_name)
        if cap_bot and r0 > 1e-4:
            cb = (cx, y0, cz)
            for i in range(sides):
                j = (i + 1) % sides
                self.add_planar_tri_art(cb, ring0[i], ring0[j], mat_name, region_name)

    def add_cone(self, cx, y0, y1, cz, r0, sides, mat_name, region_name=None, cap_bot=False):
        """N-sided pyramid / conical roof with exact planar UVs."""
        ring = []
        for i in range(sides):
            ang = 2.0 * math.pi * i / sides + (math.pi / sides if sides == 4 else 0.0)
            ring.append((cx + math.cos(ang) * r0, y0, cz + math.sin(ang) * r0))
        apex = (cx, y1, cz)
        for i in range(sides):
            j = (i + 1) % sides
            self.add_planar_tri_art(ring[j], ring[i], apex, mat_name, region_name)
        if cap_bot:
            cb = (cx, y0, cz)
            for i in range(sides):
                j = (i + 1) % sides
                self.add_planar_tri_art(cb, ring[i], ring[j], mat_name, region_name)

    def add_arch_ring_z(self, cx, cy, z_back, z_front, r_in, r_out, segs, mat_name, region_name=None, ry_in=None, ry_out=None):
        """Real 3D semicircular or elliptical stone arch ring in XY plane spanning z_back..z_front."""
        ryi = r_in if ry_in is None else ry_in
        ryo = r_out if ry_out is None else ry_out
        for i in range(segs):
            a0 = math.pi * i / segs
            a1 = math.pi * (i + 1) / segs
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            # Front (+z) face of arch ring
            p_in0 = (cx + c0 * r_in, cy + s0 * ryi, z_front)
            p_out0 = (cx + c0 * r_out, cy + s0 * ryo, z_front)
            p_out1 = (cx + c1 * r_out, cy + s1 * ryo, z_front)
            p_in1 = (cx + c1 * r_in, cy + s1 * ryi, z_front)
            self.add_planar_quad_art(p_in0, p_out0, p_out1, p_in1, mat_name, region_name)
            # Back (-z) face of arch ring
            b_in0 = (cx + c0 * r_in, cy + s0 * ryi, z_back)
            b_out0 = (cx + c0 * r_out, cy + s0 * ryo, z_back)
            b_out1 = (cx + c1 * r_out, cy + s1 * ryo, z_back)
            b_in1 = (cx + c1 * r_in, cy + s1 * ryi, z_back)
            self.add_planar_quad_art(b_out0, b_in0, b_in1, b_out1, mat_name, region_name)
            # Inner soffit (intrados)
            self.add_planar_quad_art(b_in0, p_in0, p_in1, b_in1, mat_name, region_name)
            # Outer rim (extrados)
            self.add_planar_quad_art(p_out0, b_out0, b_out1, p_out1, mat_name, region_name)

    def add_clock_disc_and_bezel(self, cx, cy, cz, facing, r_dial, r_bezel, bezel_depth, dial_mat):
        """12-sided circular glowing clock dial disc (exact 0.0388 m/texel UV) + stone bezel ring!"""
        segs = 12
        tw, th = self.mat_size[dial_mat]
        art_w, art_h = tw / 2.0, th / 2.0
        p = self._prim(dial_mat)
        # Dial facing normal & tangent axes
        if facing == "+z":
            nrm = (0.0, 0.0, 1.0)
            to_pt = lambda dx, dy, dz=0.0: (cx + dx, cy + dy, cz + dz)
        elif facing == "-z":
            nrm = (0.0, 0.0, -1.0)
            to_pt = lambda dx, dy, dz=0.0: (cx - dx, cy + dy, cz - dz)
        elif facing == "+x":
            nrm = (1.0, 0.0, 0.0)
            to_pt = lambda dx, dy, dz=0.0: (cx + dz, cy + dy, cz - dx)
        else:  # "-x"
            nrm = (-1.0, 0.0, 0.0)
            to_pt = lambda dx, dy, dz=0.0: (cx - dz, cy + dy, cz + dx)

        base = len(p["pos"])
        c_pt = to_pt(0.0, 0.0, 0.6)
        p["pos"].append((c_pt[0] * U, c_pt[1] * U, c_pt[2] * U))
        p["norm"].append(nrm)
        p["uv"].append((0.5, 0.5))
        for i in range(segs):
            ang = 2.0 * math.pi * i / segs
            dx = math.cos(ang) * r_dial
            dy = math.sin(ang) * r_dial
            pt = to_pt(dx, dy, 0.6)
            p["pos"].append((pt[0] * U, pt[1] * U, pt[2] * U))
            p["norm"].append(nrm)
            p["uv"].append((0.5 + dx / art_w, 0.5 - dy / art_h))
        for i in range(segs):
            j = (i + 1) % segs
            p["idx"].extend([base, base + 1 + i, base + 1 + j])

        # Sculpted circular rusticated stone bezel ring projecting around the circular dial (non-glowing!)
        for i in range(segs):
            a0 = 2.0 * math.pi * i / segs
            a1 = 2.0 * math.pi * ((i + 1) % segs) / segs
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            f_in0 = to_pt(c0 * (r_dial - 0.3), s0 * (r_dial - 0.3), bezel_depth)
            f_out0 = to_pt(c0 * r_bezel, s0 * r_bezel, bezel_depth)
            f_out1 = to_pt(c1 * r_bezel, s1 * r_bezel, bezel_depth)
            f_in1 = to_pt(c1 * (r_dial - 0.3), s1 * (r_dial - 0.3), bezel_depth)
            b_in0 = to_pt(c0 * (r_dial - 0.3), s0 * (r_dial - 0.3), 0.0)
            b_out0 = to_pt(c0 * r_bezel, s0 * r_bezel, 0.0)
            b_out1 = to_pt(c1 * r_bezel, s1 * r_bezel, 0.0)
            b_in1 = to_pt(c1 * (r_dial - 0.3), s1 * (r_dial - 0.3), 0.0)
            self.add_planar_quad_art(f_in0, f_out0, f_out1, f_in1, "stone_rusticated")
            self.add_planar_quad_art(b_in0, f_in0, f_in1, b_in1, "stone_rusticated")
            self.add_planar_quad_art(f_out0, b_out0, b_out1, f_out1, "stone_rusticated")

    def add_hip_roof(self, x0, x1, z0, z1, y_eave, y_ridge, ridge_inset_x, ridge_inset_z, mat_name):
        """Steep 4-slope hipped roof over rectangle [x0,x1] x [z0,z1] with exact planar UVs."""
        rx0 = x0 + ridge_inset_x
        rx1 = x1 - ridge_inset_x
        rz0 = z0 + ridge_inset_z
        rz1 = z1 - ridge_inset_z
        # 4 eave corners
        sw = (x0, y_eave, z1)
        se = (x1, y_eave, z1)
        ne = (x1, y_eave, z0)
        nw = (x0, y_eave, z0)
        # 4 ridge corners (collapses to ridge line if rx0==rx1 or rz0==rz1)
        r_sw = (rx0, y_ridge, rz1)
        r_se = (rx1, y_ridge, rz1)
        r_ne = (rx1, y_ridge, rz0)
        r_nw = (rx0, y_ridge, rz0)
        # South (+Z) slope
        self.add_planar_quad_art(sw, se, r_se, r_sw, mat_name)
        # North (-Z) slope
        self.add_planar_quad_art(ne, nw, r_nw, r_ne, mat_name)
        # East (+X) slope
        self.add_planar_quad_art(se, ne, r_ne, r_se, mat_name)
        # West (-X) slope
        self.add_planar_quad_art(nw, sw, r_sw, r_nw, mat_name)
        # Flat deck cap if truncated hip
        if rx1 > rx0 + 0.1 and rz1 > rz0 + 0.1:
            self.add_planar_quad_art(r_sw, r_se, r_ne, r_nw, mat_name)

    def tri_count(self):
        return sum(len(pr["idx"]) // 3 for pr in self.prims.values())


class CollisionRecorder:
    """Records axis-aligned collision boxes into both the invisible collision mesh and model-info.json."""

    def __init__(self, mat_idx, mat_size, regions):
        self.bucket = MeshBucket("collision", mat_idx, mat_size, regions)
        self.boxes = []

    def add_box(self, x0, y0, z0, x1, y1, z1, source, category="exterior", gate_node=None):
        if x1 <= x0 + 1e-4 or y1 <= y0 + 1e-4 or z1 <= z0 + 1e-4:
            return
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "stone_rusticated")
        cx = (x0 + x1) * 0.5 * U
        cy_g = (y0 + y1) * 0.5 * U
        cz_g = (z0 + z1) * 0.5 * U
        sx = (x1 - x0) * U
        sy_g = (y1 - y0) * U
        sz_g = (z1 - z0) * U
        entry = {
            "source": source,
            "category": category,
            "center": [round(cx, 4), round(-cz_g, 4), round(cy_g, 4)],  # Blender Z-up convention
            "size": [round(sx, 4), round(sz_g, 4), round(sy_g, 4)],
            "height_top_m": round(y1 * U, 4),
        }
        if gate_node:
            entry["gate_node"] = gate_node
        self.boxes.append(entry)


# ==============================================================================
# REUSABLE INSTANCED MESH BUILDERS (1 mesh definition -> many glTF nodes!)
# ==============================================================================
def build_arched_window_mesh(name, mat_idx, mat_size, regions, glass_region="win_arched", paired=False):
    """
    Local space: centered at X=0, sill at Y=0, outer wall face at Z=0, facing +Z.
    Deep 0.33 m reveal (4.2 art px from outer stone surround +3.2 to recessed glass -1.0),
    8-segment semicircular stone arch ring + projecting keystone + heavy stone sill!
    """
    b = MeshBucket(name, mat_idx, mat_size, regions)
    if not paired:
        # Deeply projecting stone sill (z = -1.2 .. +4.4)
        b.add_box(-13, -2.5, -1.2, 13, 1.0, 4.4, "stone_rusticated", faces=["+z", "+y", "-y", "+x", "-x"])
        # Heavy stone jamb surrounds (z = -1.2 .. +3.2 -> 0.34 m deep reveal to glass at -1.0!)
        b.add_box(-13, 1.0, -1.2, -9.0, 25.0, 3.2, "stone_rusticated", faces=["+z", "+x", "-x", "-y"])
        b.add_box(9.0, 1.0, -1.2, 13, 25.0, 3.2, "stone_rusticated", faces=["+z", "+x", "-x", "-y"])
        # Real 6-segment deep stone arch ring (r_in=9, r_out=13.2, z = -1.2 .. +3.4)
        b.add_arch_ring_z(0, 25.0, -1.2, 3.4, 9.0, 13.2, 6, "stone_rusticated")
        # Proud projecting stone keystone at the crown of the arch (z = +0.5 .. +4.5!)
        b.add_box(-2.5, 33.5, 0.5, 2.5, 39.5, 4.5, "stone_rusticated", faces=["+z", "+y", "-y", "+x", "-x"])
        # Deeply recessed window glass/infill pane at z = -1.0
        b.add_panel_z(-9.0, 1.0, 9.0, 25.0, -1.0, "+z", "glass_window", glass_region)
        for i in range(6):
            a0 = math.pi * i / 6
            a1 = math.pi * (i + 1) / 6
            p0 = (0, 25.0, -1.0)
            p1 = (math.cos(a0) * 9.0, 25.0 + math.sin(a0) * 9.0, -1.0)
            p2 = (math.cos(a1) * 9.0, 25.0 + math.sin(a1) * 9.0, -1.0)
            b.add_planar_tri_art(p0, p1, p2, "glass_window", glass_region)
    else:
        # Paired 3rd-floor Romanesque arched windows with deep reveals, center colonnette & twin keystones!
        b.add_box(-19, -2.5, -1.2, 19, 1.0, 4.4, "stone_rusticated", faces=["+z", "+y", "-y", "+x", "-x"])
        b.add_box(-19, 1.0, -1.2, -15.0, 22.0, 3.2, "stone_rusticated", faces=["+z", "+x", "-x", "-y"])
        b.add_box(-2.0, 1.0, -1.2, 2.0, 22.0, 3.6, "stone_rusticated", faces=["+z", "+x", "-x", "-y"])
        b.add_box(15.0, 1.0, -1.2, 19, 22.0, 3.2, "stone_rusticated", faces=["+z", "+x", "-x", "-y"])
        for ox in (-8.5, 8.5):
            b.add_arch_ring_z(ox, 22.0, -1.2, 3.4, 6.5, 10.0, 6, "stone_rusticated")
            b.add_box(ox - 2.0, 28.0, 0.5, ox + 2.0, 33.2, 4.4, "stone_rusticated", faces=["+z", "+y", "-y", "+x", "-x"])
            b.add_panel_z(ox - 6.5, 1.0, ox + 6.5, 22.0, -1.0, "+z", "glass_window", glass_region)
            for i in range(6):
                a0 = math.pi * i / 6
                a1 = math.pi * (i + 1) / 6
                b.add_planar_tri_art(
                    (ox, 22.0, -1.0),
                    (ox + math.cos(a0) * 6.5, 22.0 + math.sin(a0) * 6.5, -1.0),
                    (ox + math.cos(a1) * 6.5, 22.0 + math.sin(a1) * 6.5, -1.0),
                    "glass_window",
                    glass_region,
                )
    return b


def build_bench_mesh(mat_idx, mat_size, regions):
    """Park bench (22 art px = 1.71 m long), centered at X=0, Z=0, base at Y=0, facing +Z."""
    b = MeshBucket("bench_inst", mat_idx, mat_size, regions)
    for lx in (-9, 7):
        b.add_box(lx, 0, -4, lx + 2, 7, 4, "detail_atlas", region_name="dark_iron")
        b.add_box(lx, 7, -4.5, lx + 2, 13, -3, "detail_atlas", region_name="dark_iron")
    b.add_box(-11, 6.5, -3.5, 11, 8, 3.5, "interior_floor")
    b.add_box(-11, 9.5, -4.2, 11, 13.5, -2.8, "interior_floor")
    return b


def build_lamp_mesh(style, mat_idx, mat_size, regions):
    """Era-specific lamp post (base at Y=0). ONLY the compact ~0.28 m lamp head uses glow_lamps!"""
    b = MeshBucket(f"lamp_{style}_inst", mat_idx, mat_size, regions)
    if style == "gas":
        # 1894-1950 cast-iron gas lamp post (height 44 art px = 3.41 m, lantern head ~0.27 m!)
        b.add_box(-2.8, 0, -2.8, 2.8, 6, 2.8, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.3, 6, -1.3, 1.3, 36.5, 1.3, "detail_atlas", region_name="dark_iron")
        b.add_box(-2.4, 36.5, -2.4, 2.4, 38.0, 2.4, "detail_atlas", region_name="dark_iron")
        # Glowing lantern glass ONLY: 3.4 x 3.8 x 3.4 art px (~0.26 x 0.29 m)
        b.add_box(-1.7, 38.0, -1.7, 1.7, 41.8, 1.7, "glow_lamps", faces=["+x", "-x", "+z", "-z"], region_name="lamp_gas")
        b.add_cone(0, 41.8, 45.5, 0, 3.2, 4, "detail_atlas", region_name="dark_iron", cap_bot=True)
    elif style == "cobra":
        # 1951-1999 galvanized cobra-head electric street lamp (height 68 art px = 5.28 m)
        b.add_box(-2.5, 0, -2.5, 2.5, 6, 2.5, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.4, 6, -1.4, 1.4, 66, 1.4, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.1, 64, 0, 1.1, 66.2, 15, "detail_atlas", region_name="dark_iron")
        b.add_box(-2.6, 64, 13.5, 2.6, 66.8, 20.5, "detail_atlas", faces=["+y", "+x", "-x", "+z", "-z"], region_name="dark_iron")
        # Compact downward glowing lens (~0.28 x 0.31 m)
        b.add_panel_y(-1.8, 15.0, 1.8, 19.0, 63.9, "-y", "glow_lamps", "lamp_cobra")
    else:
        # 2000+ restored heritage warm LED acorn-globe post (height 46 art px = 3.57 m, globe ~0.27 m!)
        b.add_box(-2.8, 0, -2.8, 2.8, 7, 2.8, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.3, 7, -1.3, 1.3, 39.5, 1.3, "detail_atlas", region_name="dark_iron")
        b.add_box(-2.2, 39.5, -2.2, 2.2, 40.5, 2.2, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.7, 40.5, -1.7, 1.7, 44.3, 1.7, "glow_lamps", faces=["+x", "-x", "+z", "-z"], region_name="lamp_acorn")
        b.add_box(-1.0, 44.3, -1.0, 1.0, 46.5, 1.0, "detail_atlas", region_name="dark_iron")
    return b


def build_clock_hand_mesh(name, length_art, half_w_art, mat_idx, mat_size, regions):
    """Clock hand pointing straight UP (+Y = 12 o'clock) pivoted at (0, 0, 0)."""
    b = MeshBucket(name, mat_idx, mat_size, regions)
    b.add_box(-half_w_art, -1.6, -0.3, half_w_art, length_art, 0.5, "detail_atlas", region_name="dark_iron")
    return b


def build_bell_mesh(mat_idx, mat_size, regions):
    """10-sided cast-bronze belfry bell pivoted at its yoke (0, 0, 0), hanging downward (-Y)."""
    b = MeshBucket("bell", mat_idx, mat_size, regions)
    # Timber yoke beam
    b.add_box(-12, -2, -3, 12, 2, 3, "interior_floor")
    # Flared 10-sided bronze bell body (crown -> waist -> lip)
    b.add_cylinder(0, -10, -2, 0, 6.5, 4.0, 10, "detail_atlas", region_name="dark_iron", cap_top=True)
    b.add_cylinder(0, -18, -10, 0, 10.5, 6.5, 10, "detail_atlas", region_name="dark_iron", cap_top=False)
    b.add_cylinder(0, -20, -18, 0, 11.5, 10.5, 10, "detail_atlas", region_name="dark_iron", cap_top=False)
    # Clapper sphere/peg
    b.add_box(-1.5, -23, -1.5, 1.5, -14, 1.5, "detail_atlas", region_name="dark_iron")
    return b


# ==============================================================================
# GLB SERIALIZER WITH MULTI-NODE INSTANCING SUPPORT
# ==============================================================================
def pack_glb(out_path, mat_defs, meshes_list, nodes_list):
    """
    meshes_list: list of (MeshBucket, extras_dict_or_None)
    nodes_list: list of glTF node dicts (mesh index refers to meshes_list)
    """
    bin_buf = bytearray()
    buffer_views = []
    accessors = []
    images = []
    textures = []
    materials = []
    gltf_meshes = []

    def add_bv(data, target=None):
        while len(bin_buf) % 4 != 0:
            bin_buf.append(0)
        off = len(bin_buf)
        bin_buf.extend(data)
        bv = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            bv["target"] = target
        idx = len(buffer_views)
        buffer_views.append(bv)
        return idx

    def add_acc(bv, comp_type, count, acc_type, mn=None, mx=None):
        acc = {"bufferView": bv, "componentType": comp_type, "count": count, "type": acc_type}
        if mn is not None:
            acc["min"] = mn
        if mx is not None:
            acc["max"] = mx
        idx = len(accessors)
        accessors.append(acc)
        return idx

    # Deduplicate embedded PNG images
    img_cache = {}
    tex_cache = {}

    def get_texture(fname):
        if fname in tex_cache:
            return tex_cache[fname]
        if fname not in img_cache:
            raw = (TEX_DIR / fname).read_bytes()
            bv = add_bv(raw)
            img_idx = len(images)
            images.append({"bufferView": bv, "mimeType": "image/png", "name": fname})
            img_cache[fname] = img_idx
        t_idx = len(textures)
        textures.append({"sampler": 0, "source": img_cache[fname]})
        tex_cache[fname] = t_idx
        return t_idx

    for m in mat_defs:
        base_tex = get_texture(m["file"])
        mat_obj = {
            "name": m["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": base_tex},
                "metallicFactor": 0.0,
                "roughnessFactor": 0.92,
            },
            "alphaMode": m["alpha"],
            "doubleSided": m["doubleSided"],
        }
        if m.get("emissive_file"):
            em_tex = get_texture(m["emissive_file"])
            mat_obj["emissiveTexture"] = {"index": em_tex}
            mat_obj["emissiveFactor"] = m.get("emissive_factor", [1.0, 1.0, 1.0])
        elif "emissive_factor" in m:
            mat_obj["emissiveFactor"] = m["emissive_factor"]
        materials.append(mat_obj)

    for bucket, _ in meshes_list:
        prims = []
        for mat_name, p in bucket.prims.items():
            if not p["idx"]:
                continue
            pos_b = bytearray()
            nrm_b = bytearray()
            uv_b = bytearray()
            idx_b = bytearray()
            xs = [v[0] for v in p["pos"]]
            ys = [v[1] for v in p["pos"]]
            zs = [v[2] for v in p["pos"]]
            for vx, vy, vz in p["pos"]:
                pos_b.extend(struct.pack("<3f", vx, vy, vz))
            for nx, ny, nz in p["norm"]:
                nrm_b.extend(struct.pack("<3f", nx, ny, nz))
            for u, v in p["uv"]:
                uv_b.extend(struct.pack("<2f", u, v))
            for ii in p["idx"]:
                idx_b.extend(struct.pack("<I", ii))
            bv_p = add_bv(pos_b, 34962)
            bv_n = add_bv(nrm_b, 34962)
            bv_t = add_bv(uv_b, 34962)
            bv_i = add_bv(idx_b, 34963)
            acc_p = add_acc(bv_p, 5126, len(p["pos"]), "VEC3", [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)])
            acc_n = add_acc(bv_n, 5126, len(p["norm"]), "VEC3")
            acc_t = add_acc(bv_t, 5126, len(p["uv"]), "VEC2")
            acc_i = add_acc(bv_i, 5125, len(p["idx"]), "SCALAR")
            prims.append({
                "attributes": {"POSITION": acc_p, "NORMAL": acc_n, "TEXCOORD_0": acc_t},
                "indices": acc_i,
                "material": bucket.mat_idx[mat_name],
            })
        gltf_meshes.append({"name": bucket.name, "primitives": prims})

    while len(bin_buf) % 4 != 0:
        bin_buf.append(0)

    gltf = {
        "asset": {"version": "2.0", "generator": "harlan-county-courthouse-builder"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "samplers": [{"magFilter": 9728, "minFilter": 9728, "wrapS": 10497, "wrapT": 10497}],
        "images": images,
        "textures": textures,
        "materials": materials,
        "bufferViews": buffer_views,
        "accessors": accessors,
        "buffers": [{"byteLength": len(bin_buf)}],
        "meshes": gltf_meshes,
        "nodes": nodes_list,
    }
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(json_bytes) % 4 != 0:
        json_bytes += b" "

    total_len = 12 + 8 + len(json_bytes) + 8 + len(bin_buf)
    header = struct.pack("<3I", 0x46546C67, 2, total_len)
    j_chunk = struct.pack("<2I", len(json_bytes), 0x4E4F534A) + json_bytes
    b_chunk = struct.pack("<2I", len(bin_buf), 0x004E4942) + bytes(bin_buf)
    Path(out_path).write_bytes(header + j_chunk + b_chunk)


# ==============================================================================
# MAIN NEAR-STATE BUILDER (1894, 1962, 1988, 2008)
# ==============================================================================
def build_near_state(spec):
    year = spec["year"]
    mat_defs = get_material_defs(spec)
    mat_idx = {m["name"]: i for i, m in enumerate(mat_defs)}
    mat_size = {m["name"]: read_png_size(TEX_DIR / m["file"]) for m in mat_defs}

    regions = {}
    regions.update(ART_MANIFEST["regions"]["detail_1988" if spec["annex_stained"] else "detail_clean"])
    regions.update(ART_MANIFEST["regions"]["glass"])
    regions.update(ART_MANIFEST["regions"]["signs"])
    regions.update(ART_MANIFEST["regions"]["lamps"])

    structure = MeshBucket("structure", mat_idx, mat_size, regions)
    lot_ground = MeshBucket("lot_ground", mat_idx, mat_size, regions)
    lot_memorial = MeshBucket("memorial", mat_idx, mat_size, regions)
    lot_bandstand = MeshBucket("bandstand", mat_idx, mat_size, regions)
    lot_flagpole = MeshBucket("flagpole", mat_idx, mat_size, regions)
    lot_extras = MeshBucket("lot_extras", mat_idx, mat_size, regions)  # hitching rails / ramp / annex
    coll = CollisionRecorder(mat_idx, mat_size, regions)

    # ==========================================================================
    # 1. THE TOWN SQUARE (80 x 80 m -> 1024 x 1024 art px, x in [-512,512], z in [-1024,0])
    # ==========================================================================
    # Base lawn slab (y in [0, 2])
    lot_ground.add_box(-512, 0, -1024, 512, Y_GROUND, 0, "ground_lawn", faces=["+y", "+z", "-z", "+x", "-x"])
    # Full perimeter sidewalk around all 4 curbs (40 art px = 3.10 m wide, y = 2.15)
    sw_w = 40
    lot_ground.add_panel_y(-512, -sw_w, 512, 0, Y_GROUND + 0.15, "+y", "ground_pavement")          # South +Z
    lot_ground.add_panel_y(-512, -1024, 512, -1024 + sw_w, Y_GROUND + 0.15, "+y", "ground_pavement")  # North -Z
    lot_ground.add_panel_y(-512, -1024 + sw_w, -512 + sw_w, -sw_w, Y_GROUND + 0.15, "+y", "ground_pavement")  # West
    lot_ground.add_panel_y(512 - sw_w, -1024 + sw_w, 512, -sw_w, Y_GROUND + 0.15, "+y", "ground_pavement")    # East

    # Central stone plaza apron surrounding the cross-shaped courthouse (dx, dz in [-220, 220])
    lot_ground.add_panel_y(CX - 220, CZ - 220, CX + 220, CZ + 220, Y_GROUND + 0.18, "+y", "ground_pavement")

    # Cardinal cross paths (48 art px = 3.72 m wide) from all 4 street midpoints to central plaza
    lot_ground.add_panel_y(-24, CZ + 220, 24, -sw_w, Y_GROUND + 0.16, "+y", "ground_pavement")      # South
    lot_ground.add_panel_y(-24, -1024 + sw_w, 24, CZ - 220, Y_GROUND + 0.16, "+y", "ground_pavement")  # North
    lot_ground.add_panel_y(-512 + sw_w, CZ - 24, CX - 220, CZ + 24, Y_GROUND + 0.16, "+y", "ground_pavement")  # West
    lot_ground.add_panel_y(CX + 220, CZ - 24, 512 - sw_w, CZ + 24, Y_GROUND + 0.16, "+y", "ground_pavement")   # East

    # 4 Diagonal paths (32 art px wide) from each corner sidewalk into the central plaza
    diag_hw = 16.0 / math.sqrt(2.0)
    for sx, sz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        # From corner inner sidewalk (sx*472, CZ + sz*472) to plaza corner (sx*185, CZ + sz*185)
        ax, az = sx * 472.0, CZ + sz * 472.0
        bx, bz = sx * 185.0, CZ + sz * 185.0
        # Perpendicular offset vector (-sz, sx) normalized * diag_hw
        px, pz = -sz * diag_hw, sx * diag_hw
        p0 = (ax - px, Y_GROUND + 0.17, az - pz)
        p1 = (ax + px, Y_GROUND + 0.17, az + pz)
        p2 = (bx + px, Y_GROUND + 0.17, bz + pz)
        p3 = (bx - px, Y_GROUND + 0.17, bz - pz)
        # Ensure +Y upward winding
        e1 = v_sub(p1, p0)
        e2 = v_sub(p2, p0)
        if v_cross(e1, e2)[1] < 0:
            p0, p1, p2, p3 = p0, p3, p2, p1
        lot_ground.add_planar_quad_art(p0, p1, p2, p3, "ground_pavement")

    # Granite low perimeter wall (y = 2..11, thickness 6 art px) at inner sidewalk edge with 8 path gaps!
    # Gaps: corners (within 56 art px of +/-472) and cardinal midpoints ([-28, +28])
    wall_seg_spans = [(-416, -28), (28, 416)]
    for s0, s1 in wall_seg_spans:
        # South (+Z) wall at z in [-46, -40]
        lot_ground.add_box(s0, Y_GROUND, -46, s1, 11, -40, "stone_rusticated")
        coll.add_box(s0, 0, -46, s1, 11, -40, f"perimeter_granite_wall_south_{s0}", "square")
        # North (-Z) wall at z in [-984, -978]
        lot_ground.add_box(s0, Y_GROUND, -984, s1, 11, -978, "stone_rusticated")
        coll.add_box(s0, 0, -984, s1, 11, -978, f"perimeter_granite_wall_north_{s0}", "square")
        # West (-X) wall at x in [-472, -466]
        lot_ground.add_box(-472, Y_GROUND, CZ + s0, -466, 11, CZ + s1, "stone_rusticated")
        coll.add_box(-472, 0, CZ + s0, -466, 11, CZ + s1, f"perimeter_granite_wall_west_{s0}", "square")
        # East (+X) wall at x in [466, 472]
        lot_ground.add_box(466, Y_GROUND, CZ + s0, 472, 11, CZ + s1, "stone_rusticated")
        coll.add_box(466, 0, CZ + s0, 472, 11, CZ + s1, f"perimeter_granite_wall_east_{s0}", "square")

    # ==========================================================================
    # 2. COURTHOUSE EXTERIOR SHELL (Crisp Cross Plan: 29.80 m x 29.80 m)
    #    Central block: dx, dz in [-116, +116] (18.00 m x 18.00 m)
    #    4 boldly projecting entrance pavilions: width 124 ([-62, +62]),
    #    projecting 76 art px (5.90 m!) out to +/-192 on all four sides!
    # ==========================================================================
    CB = 116.0  # Central block half-width (18.00 m)
    PW = 62.0   # Projecting pavilion half-width (9.62 m)

    # Raised rusticated stone foundation plinth under entire cross footprint (y = 2..14)
    structure.add_box(-CB, Y_GROUND, CZ - CB, CB, Y_FL1, CZ + CB, "stone_rusticated")
    structure.add_box(-PW, Y_GROUND, CZ + CB, PW, Y_FL1, CZ + 192, "stone_rusticated")
    structure.add_box(-PW, Y_GROUND, CZ - 192, PW, Y_FL1, CZ - CB, "stone_rusticated")
    structure.add_box(CB, Y_GROUND, CZ - PW, 192, Y_FL1, CZ + PW, "stone_rusticated")
    structure.add_box(-192, Y_GROUND, CZ - PW, -CB, Y_FL1, CZ + PW, "stone_rusticated")

    def add_ext_wall(x0, z0, x1, z1, name, has_door_gap=None):
        if has_door_gap is None:
            structure.add_box(x0, Y_FL1, z0, x1, Y_FL2, z1, "stone_rusticated")
            structure.add_box(x0, Y_FL2, z0, x1, Y_EAVE, z1, "brick_red")
            coll.add_box(x0, 0, z0, x1, Y_EAVE, z1, name, "exterior")
        elif has_door_gap[0] == "z":
            _, gx0, gx1, gy_top = has_door_gap
            structure.add_box(x0, Y_FL1, z0, gx0, Y_FL2, z1, "stone_rusticated")
            structure.add_box(gx1, Y_FL1, z0, x1, Y_FL2, z1, "stone_rusticated")
            structure.add_box(gx0, gy_top, z0, gx1, Y_FL2, z1, "stone_rusticated")
            structure.add_box(x0, Y_FL2, z0, x1, Y_EAVE, z1, "brick_red")
            coll.add_box(x0, 0, z0, gx0, Y_EAVE, z1, f"{name}_left", "exterior")
            coll.add_box(gx1, 0, z0, x1, Y_EAVE, z1, f"{name}_right", "exterior")
        elif has_door_gap[0] == "x":
            _, gz0, gz1, gy_top = has_door_gap
            structure.add_box(x0, Y_FL1, z0, x1, Y_FL2, gz0, "stone_rusticated")
            structure.add_box(x0, Y_FL1, gz1, x1, Y_FL2, z1, "stone_rusticated")
            structure.add_box(x0, gy_top, gz0, x1, Y_FL2, gz1, "stone_rusticated")
            structure.add_box(x0, Y_FL2, z0, x1, Y_EAVE, z1, "brick_red")
            coll.add_box(x0, 0, z0, x1, Y_EAVE, gz0, f"{name}_north", "exterior")
            coll.add_box(x0, 0, gz1, x1, Y_EAVE, z1, f"{name}_south", "exterior")

    T = 6.0  # Exterior masonry wall thickness (0.466 m)
    DOOR_TOP = Y_FL1 + 40.0  # Open walk-in entrance doorway (3.10 m tall, 32 art px = 2.48 m wide)

    # 4 Pavilion End Walls (with open walk-in entrance doorways — ZERO closed panels!)
    add_ext_wall(-PW, CZ + 192 - T, PW, CZ + 192, "wall_pavilion_south", ("z", -16, 16, DOOR_TOP))
    add_ext_wall(-PW, CZ - 192, PW, CZ - 192 + T, "wall_pavilion_north", ("z", -14, 14, DOOR_TOP))
    add_ext_wall(192 - T, CZ - PW, 192, CZ + PW, "wall_pavilion_east", ("x", CZ - 14, CZ + 14, DOOR_TOP))
    add_ext_wall(-192, CZ - PW, -192 + T, CZ + PW, "wall_pavilion_west", ("x", CZ - 14, CZ + 14, DOOR_TOP))

    # 8 Deep Pavilion Cheek Walls (projecting 5.90 m out from central block!)
    add_ext_wall(-PW, CZ + CB, -PW + T, CZ + 192 - T, "wall_pav_s_west")
    add_ext_wall(PW - T, CZ + CB, PW, CZ + 192 - T, "wall_pav_s_east")
    add_ext_wall(-PW, CZ - 192 + T, -PW + T, CZ - CB, "wall_pav_n_west")
    add_ext_wall(PW - T, CZ - 192 + T, PW, CZ - CB, "wall_pav_n_east")
    add_ext_wall(CB, CZ + PW - T, 192 - T, CZ + PW, "wall_pav_e_south")
    add_ext_wall(CB, CZ - PW, 192 - T, CZ - PW + T, "wall_pav_e_north")
    add_ext_wall(-192 + T, CZ + PW - T, -CB, CZ + PW, "wall_pav_w_south")
    add_ext_wall(-192 + T, CZ - PW, -CB, CZ - PW + T, "wall_pav_w_north")

    # 8 Central Block Re-Entrant Corner Walls (54 x 76 art px crisp recessed corners!)
    for sx in (-1, 1):
        for sz in (-1, 1):
            x_a = PW if sx > 0 else -CB
            x_b = CB if sx > 0 else -PW
            z_wall0 = CZ + (CB - T if sz > 0 else -CB)
            z_wall1 = CZ + (CB if sz > 0 else -CB + T)
            add_ext_wall(x_a, z_wall0, x_b, z_wall1, f"wall_corner_z_{sx}_{sz}")

            z_a = CZ + (PW if sz > 0 else -CB + T)
            z_b = CZ + (CB - T if sz > 0 else -PW)
            x_wall0 = (CB - T) if sx > 0 else -CB
            x_wall1 = CB if sx > 0 else (-CB + T)
            add_ext_wall(x_wall0, z_a, x_wall1, z_b, f"wall_corner_x_{sx}_{sz}")

    # Stone Corner Quoins on all 8 outer pavilion corners + 4 central corners (crisp Romanesque vertical emphasis!)
    for sx in (-1, 1):
        for sz in (-1, 1):
            # Central block outer corner pilasters
            cx0 = (CB - 5) if sx > 0 else (-CB - 1.2)
            cx1 = (CB + 1.2) if sx > 0 else (-CB + 5)
            cz0 = CZ + ((CB - 5) if sz > 0 else (-CB - 1.2))
            cz1 = CZ + ((CB + 1.2) if sz > 0 else (-CB + 5))
            structure.add_box(cx0, Y_FL2, cz0, cx1, Y_EAVE, cz1, "stone_rusticated")
            # Pavilion outer end corners (South/North)
            px0 = (PW - 5) if sx > 0 else (-PW - 1.2)
            px1 = (PW + 1.2) if sx > 0 else (-PW + 5)
            pz0 = CZ + ((192 - 5) if sz > 0 else (-192 - 1.2))
            pz1 = CZ + ((192 + 1.2) if sz > 0 else (-192 + 5))
            structure.add_box(px0, Y_FL2, pz0, px1, Y_EAVE, pz1, "stone_rusticated")
            # Pavilion outer end corners (East/West)
            ex0 = (192 - 5) if sx > 0 else (-192 - 1.2)
            ex1 = (192 + 1.2) if sx > 0 else (-192 + 5)
            ez0 = CZ + ((PW - 5) if sz > 0 else (-PW - 1.2))
            ez1 = CZ + ((PW + 1.2) if sz > 0 else (-PW + 5))
            structure.add_box(ex0, Y_FL2, ez0, ex1, Y_EAVE, ez1, "stone_rusticated")

    # Projecting Hollow Exterior-Only Stone Stringcourse Belt Bands at Floor 2, Floor 3, and Eave Cornice!
    for by0, by1, out in ((Y_FL2 - 2, Y_FL2 + 2, 2.2), (Y_FL3 - 2, Y_FL3 + 2, 2.2), (Y_EAVE - 4, Y_EAVE + 2, 4.0)):
        structure.add_box(-PW - out, by0, CZ + 192, PW + out, by1, CZ + 192 + out, "stone_rusticated", faces=["+z", "+y", "-y", "+x", "-x"])
        structure.add_box(-PW - out, by0, CZ - 192 - out, PW + out, by1, CZ - 192, "stone_rusticated", faces=["-z", "+y", "-y", "+x", "-x"])
        structure.add_box(192, by0, CZ - PW - out, 192 + out, by1, CZ + PW + out, "stone_rusticated", faces=["+x", "+y", "-y", "+z", "-z"])
        structure.add_box(-192 - out, by0, CZ - PW - out, -192, by1, CZ + PW + out, "stone_rusticated", faces=["-x", "+y", "-y", "+z", "-z"])
        structure.add_box(-PW - out, by0, CZ + CB, -PW, by1, CZ + 192, "stone_rusticated", faces=["-x", "+y", "-y"])
        structure.add_box(PW, by0, CZ + CB, PW + out, by1, CZ + 192, "stone_rusticated", faces=["+x", "+y", "-y"])
        structure.add_box(-PW - out, by0, CZ - 192, -PW, by1, CZ - CB, "stone_rusticated", faces=["-x", "+y", "-y"])
        structure.add_box(PW, by0, CZ - 192, PW + out, by1, CZ - CB, "stone_rusticated", faces=["+x", "+y", "-y"])
        structure.add_box(CB, by0, CZ + PW, 192, by1, CZ + PW + out, "stone_rusticated", faces=["+z", "+y", "-y"])
        structure.add_box(CB, by0, CZ - PW - out, 192, by1, CZ - PW, "stone_rusticated", faces=["-z", "+y", "-y"])
        structure.add_box(-192, by0, CZ + PW, -CB, by1, CZ + PW + out, "stone_rusticated", faces=["+z", "+y", "-y"])
        structure.add_box(-192, by0, CZ - PW - out, -CB, by1, CZ - PW, "stone_rusticated", faces=["-z", "+y", "-y"])
        for sx in (-1, 1):
            for sz in (-1, 1):
                xa, xb = (PW, CB + out) if sx > 0 else (-CB - out, -PW)
                za = CZ + (CB if sz > 0 else -CB - out)
                zb = CZ + (CB + out if sz > 0 else -CB)
                structure.add_box(xa, by0, za, xb, by1, zb, "stone_rusticated", faces=["+z" if sz > 0 else "-z", "+y", "-y"])
                za2 = CZ + (PW if sz > 0 else -CB)
                zb2 = CZ + (CB if sz > 0 else -PW)
                xa2 = CB if sx > 0 else -CB - out
                xb2 = CB + out if sx > 0 else -CB
                structure.add_box(xa2, by0, za2, xb2, by1, zb2, "stone_rusticated", faces=["+x" if sx > 0 else "-x", "+y", "-y"])

    # ==========================================================================
    # 3. CRISP CROSS-PLAN ROOF ARCHITECTURE:
    #    - Steep Central Hipped Slate Roof over [-120,+120] (eave 188 -> deck 246)
    #    - 4 Bold Steep Gabled Pavilion Roofs meeting the central hip in clear 45° valleys!
    #    - Brick Gable Pediments + Stone Raking Coping + Attic Louver Windows
    #    - 8 Gabled Dormers on the Main Central Roof!
    # ==========================================================================
    # Central square steep hipped slate roof (eave 188 -> ridge deck 246 around tower base)
    structure.add_hip_roof(-122, 122, CZ - 122, CZ + 122, Y_EAVE, 246, 68, 68, "roof_slate")

    # Helper: Steep Gabled Pavilion Roof with brick pediment, stone raking coping, & gable window!
    G_EAVE = PW + 4.0  # 66 art px overhang
    G_PEAK = 244.0     # Crisp tall ridge meeting central hip cleanly in sharp valleys!

    def add_pavilion_gable(side):
        if side in ("+z", "-z"):
            s = 1 if side == "+z" else -1
            z_out = CZ + s * 196.0
            z_wall = CZ + s * 192.0
            z_in = CZ + s * 62.0  # runs cleanly into central roof forming sharp valleys!
            za, zb = min(z_in, z_out), max(z_in, z_out)
            # Two steep slate roof slopes
            structure.add_planar_quad_art((-G_EAVE, Y_EAVE, zb), (-G_EAVE, Y_EAVE, za), (0, G_PEAK, za), (0, G_PEAK, zb), "roof_slate")
            structure.add_planar_quad_art((G_EAVE, Y_EAVE, za), (G_EAVE, Y_EAVE, zb), (0, G_PEAK, zb), (0, G_PEAK, za), "roof_slate")
            # Brick gable pediment triangle at outer wall
            if s > 0:
                structure.add_planar_tri_art((-PW, Y_EAVE, z_wall), (PW, Y_EAVE, z_wall), (0, G_PEAK - 2, z_wall), "brick_red")
                # Stone raking coping strips along both gable verges
                structure.add_planar_quad_art((-G_EAVE, Y_EAVE - 2, z_out), (-PW + 3, Y_EAVE - 2, z_out), (0, G_PEAK - 3, z_out), (0, G_PEAK + 2, z_out), "stone_rusticated")
                structure.add_planar_quad_art((PW - 3, Y_EAVE - 2, z_out), (G_EAVE, Y_EAVE - 2, z_out), (0, G_PEAK + 2, z_out), (0, G_PEAK - 3, z_out), "stone_rusticated")
            else:
                structure.add_planar_tri_art((PW, Y_EAVE, z_wall), (-PW, Y_EAVE, z_wall), (0, G_PEAK - 2, z_wall), "brick_red")
                structure.add_planar_quad_art((G_EAVE, Y_EAVE - 2, z_out), (PW - 3, Y_EAVE - 2, z_out), (0, G_PEAK - 3, z_out), (0, G_PEAK + 2, z_out), "stone_rusticated")
                structure.add_planar_quad_art((-PW + 3, Y_EAVE - 2, z_out), (-G_EAVE, Y_EAVE - 2, z_out), (0, G_PEAK + 2, z_out), (0, G_PEAK - 3, z_out), "stone_rusticated")
            # Arched stone attic oculus/window centered inside the brick gable pediment!
            structure.add_box(-10, Y_EAVE + 10, z_wall + s * 0.2, 10, Y_EAVE + 28, z_wall + s * 2.4, "stone_rusticated")
            structure.add_panel_z(-7, Y_EAVE + 12, 7, Y_EAVE + 26, z_wall + s * 2.6, side, "glass_window", "win_arched")
        else:
            s = 1 if side == "+x" else -1
            x_out = s * 196.0
            x_wall = s * 192.0
            x_in = s * 62.0
            xa, xb = min(x_in, x_out), max(x_in, x_out)
            structure.add_planar_quad_art((xa, Y_EAVE, CZ - G_EAVE), (xb, Y_EAVE, CZ - G_EAVE), (xb, G_PEAK, CZ), (xa, G_PEAK, CZ), "roof_slate")
            structure.add_planar_quad_art((xb, Y_EAVE, CZ + G_EAVE), (xa, Y_EAVE, CZ + G_EAVE), (xa, G_PEAK, CZ), (xb, G_PEAK, CZ), "roof_slate")
            if s > 0:
                structure.add_planar_tri_art((x_wall, Y_EAVE, CZ + PW), (x_wall, Y_EAVE, CZ - PW), (x_wall, G_PEAK - 2, CZ), "brick_red")
                structure.add_planar_quad_art((x_out, Y_EAVE - 2, CZ + G_EAVE), (x_out, Y_EAVE - 2, CZ + PW - 3), (x_out, G_PEAK - 3, CZ), (x_out, G_PEAK + 2, CZ), "stone_rusticated")
                structure.add_planar_quad_art((x_out, Y_EAVE - 2, CZ - PW + 3), (x_out, Y_EAVE - 2, CZ - G_EAVE), (x_out, G_PEAK + 2, CZ), (x_out, G_PEAK - 3, CZ), "stone_rusticated")
            else:
                structure.add_planar_tri_art((x_wall, Y_EAVE, CZ - PW), (x_wall, Y_EAVE, CZ + PW), (x_wall, G_PEAK - 2, CZ), "brick_red")
                structure.add_planar_quad_art((x_out, Y_EAVE - 2, CZ - G_EAVE), (x_out, Y_EAVE - 2, CZ - PW + 3), (x_out, G_PEAK - 3, CZ), (x_out, G_PEAK + 2, CZ), "stone_rusticated")
                structure.add_planar_quad_art((x_out, Y_EAVE - 2, CZ + PW - 3), (x_out, Y_EAVE - 2, CZ + G_EAVE), (x_out, G_PEAK + 2, CZ), (x_out, G_PEAK - 3, CZ), "stone_rusticated")
            structure.add_box(x_wall + s * 0.2, Y_EAVE + 10, CZ - 10, x_wall + s * 2.4, Y_EAVE + 28, CZ + 10, "stone_rusticated")
            structure.add_panel_x(CZ - 7, Y_EAVE + 12, CZ + 7, Y_EAVE + 26, x_wall + s * 2.6, side, "glass_window", "win_arched")

    for g_side in ("+z", "-z", "+x", "-x"):
        add_pavilion_gable(g_side)

    # 8 Crisp Gabled Dormers on the Main Central Hip Roof (flanking the 4 pavilion gables on all 4 facades!)
    def add_dormer(cx, cz, facing):
        y0, y1, y_pk = Y_EAVE + 6, Y_EAVE + 24, Y_EAVE + 35
        if facing in ("+z", "-z"):
            s = 1 if facing == "+z" else -1
            z_face = cz + s * 12
            z_back = cz - s * 12
            z0, z1 = min(z_face, z_back), max(z_face, z_back)
            structure.add_box(cx - 10, y0, z0, cx + 10, y1, z1, "stone_rusticated")
            structure.add_panel_z(cx - 6, y0 + 2, cx + 6, y1 - 2, z_face + s * 0.6, facing, "glass_window", "win_arched")
            structure.add_planar_quad_art((cx - 12, y1, z1), (cx - 12, y1, z0), (cx, y_pk, z0), (cx, y_pk, z1), "roof_slate")
            structure.add_planar_quad_art((cx + 12, y1, z0), (cx + 12, y1, z1), (cx, y_pk, z1), (cx, y_pk, z0), "roof_slate")
            if facing == "+z":
                structure.add_planar_tri_art((cx - 10, y1, z_face), (cx + 10, y1, z_face), (cx, y_pk, z_face), "stone_rusticated")
            else:
                structure.add_planar_tri_art((cx + 10, y1, z_face), (cx - 10, y1, z_face), (cx, y_pk, z_face), "stone_rusticated")
        else:
            s = 1 if facing == "+x" else -1
            x_face = cx + s * 12
            x_back = cx - s * 12
            x0, x1 = min(x_face, x_back), max(x_face, x_back)
            structure.add_box(x0, y0, cz - 10, x1, y1, cz + 10, "stone_rusticated")
            structure.add_panel_x(cz - 6, y0 + 2, cz + 6, y1 - 2, x_face + s * 0.6, facing, "glass_window", "win_arched")
            structure.add_planar_quad_art((x0, y1, cz - 12), (x1, y1, cz - 12), (x1, y_pk, cz), (x0, y_pk, cz), "roof_slate")
            structure.add_planar_quad_art((x1, y1, cz + 12), (x0, y1, cz + 12), (x0, y_pk, cz), (x1, y_pk, cz), "roof_slate")
            if facing == "+x":
                structure.add_planar_tri_art((x_face, y1, cz + 10), (x_face, y1, cz - 10), (x_face, y_pk, cz), "stone_rusticated")
            else:
                structure.add_planar_tri_art((x_face, y1, cz - 10), (x_face, y1, cz + 10), (x_face, y_pk, cz), "stone_rusticated")

    for dx_d in (-89, 89):
        add_dormer(dx_d, CZ + 96, "+z")
        add_dormer(dx_d, CZ - 96, "-z")
    for dz_d in (-89, 89):
        add_dormer(96, CZ + dz_d, "+x")
        add_dormer(-96, CZ + dz_d, "-x")

    # ==========================================================================
    # 4. MAIN ENTRANCE (+Z): BROAD 6-STEP FLIGHT + ARCHED PORCH ON 2 COLUMNS
    #    + OPEN WALK-IN DOORWAY + CARVED-IN-STONE FRIEZE + SINGLE-STONE 1894 + SMALL BRONZE PLAQUE
    # ==========================================================================
    # Porch stone platform landing (x in [-56, 56], z in [CZ+192, CZ+232], y = 2..14)
    structure.add_box(-56, Y_GROUND, CZ + 192, 56, Y_FL1, CZ + 232, "stone_rusticated")
    coll.add_box(-56, 0, CZ + 192, 56, Y_FL1, CZ + 232, "main_porch_landing", "exterior")
    # Flanking stone cheek walls beside main steps
    for sx in (-56, 50):
        structure.add_box(sx, Y_GROUND, CZ + 232, sx + 6, Y_FL1 + 4, CZ + 268, "stone_rusticated")
        coll.add_box(sx, 0, CZ + 232, sx + 6, Y_FL1 + 4, CZ + 268, f"main_steps_cheek_{sx}", "exterior")
    # Broad flight of 6 REAL STONE STEPS (y = 2 -> 14, z = CZ+268 -> CZ+232) + stepped collision!
    for st in range(6):
        sy0 = Y_GROUND + st * 2.0
        sy1 = sy0 + 2.0
        sz0 = CZ + 232 + (5 - st) * 6.0
        sz1 = sz0 + 6.0
        structure.add_box(-50, Y_GROUND, sz0, 50, sy1, sz1, "stone_rusticated")
        coll.add_box(-50, 0, sz0, 50, sy1, sz1, f"main_step_{st + 1}", "steps")

    # Two stout 10-sided Romanesque stone columns carrying the arched porch (spaced at +/-46!)
    for cx_col in (-46, 46):
        structure.add_box(cx_col - 5.5, Y_FL1, CZ + 221, cx_col + 5.5, Y_FL1 + 8, CZ + 232, "stone_rusticated")
        structure.add_cylinder(cx_col, Y_FL1 + 8, Y_FL1 + 46, CZ + 226.5, 4.3, 3.9, 10, "stone_rusticated")
        structure.add_box(cx_col - 6.0, Y_FL1 + 46, CZ + 220.5, cx_col + 6.0, Y_FL1 + 52, CZ + 232.5, "stone_rusticated")
        coll.add_box(cx_col - 6, 0, CZ + 220, cx_col + 6, Y_FL2 + 12, CZ + 232, f"main_porch_column_{cx_col}", "exterior")

    # Real 10-segment Romanesque stone porch arch spanning between the 2 columns!
    structure.add_arch_ring_z(0, Y_FL1 + 33, CZ + 222, CZ + 231, 36.0, 42.0, 10, "stone_rusticated", ry_in=19.0, ry_out=25.0)
    # Continuous stone porch entablature band (y = 66..92) with CARVED-IN-STONE frieze flush on face!
    structure.add_box(-56, Y_FL1 + 52, CZ + 192, 56, Y_FL2 + 20, CZ + 233, "stone_rusticated")
    # Flush carved-into-stone frieze "HARLAN COUNTY / COURTHOUSE" (NO raised sign plate!)
    structure.add_panel_z(-48, Y_FL2, 48, Y_FL2 + 20, CZ + 233.08, "+z", "sign_plates", "main_frieze")
    # Molded stone cornice cap topping the porch entablature
    structure.add_box(-58, Y_FL2 + 20, CZ + 192, 58, Y_FL2 + 23, CZ + 235, "stone_rusticated")

    # Open doorway recessed stone jambs & lintel (ZERO closed panels or boxes filling door_main!)
    structure.add_box(-18, Y_FL1, CZ + 191.2, -16, DOOR_TOP, CZ + 192.6, "stone_rusticated")
    structure.add_box(16, Y_FL1, CZ + 191.2, 18, DOOR_TOP, CZ + 192.6, "stone_rusticated")
    structure.add_box(-18, DOOR_TOP, CZ + 191.2, 18, DOOR_TOP + 3, CZ + 192.8, "stone_rusticated")

    # Single dressed-granite "1894" datestone block flush in the wall (~1.01 m x 0.466 m) right of door_main!
    structure.add_panel_z(20, Y_FL1 + 16, 33, Y_FL1 + 22, CZ + 192.10, "+z", "sign_plates", "datestone_1894")

    # 2008 Small Bronze Restoration Plaque "RESTORED 2004" (~0.62 m x 0.466 m) at eye height beside door_main!
    if spec["has_plaque"]:
        structure.add_panel_z(-28, Y_FL1 + 16, -20, Y_FL1 + 22, CZ + 192.25, "+z", "sign_plates", "plaque_restored_2004")

    # Side Entrances (-Z North, +X East, -X West): shorter 4-step stone stairs
    def add_side_steps(side):
        if side == "-z":
            if spec["has_annex"]:
                return
            structure.add_box(-30, Y_GROUND, CZ - 210, 30, Y_FL1, CZ - 192, "stone_rusticated")
            coll.add_box(-30, 0, CZ - 210, 30, Y_FL1, CZ - 192, "north_landing", "steps")
            for st in range(4):
                sy1 = Y_GROUND + (st + 1) * 3.0
                sz0 = CZ - 210 - (4 - st) * 5.0
                structure.add_box(-30, Y_GROUND, sz0, 30, sy1, sz0 + 5.0, "stone_rusticated")
                coll.add_box(-30, 0, sz0, 30, sy1, sz0 + 5.0, f"north_step_{st + 1}", "steps")
        elif side == "+x":
            structure.add_box(192, Y_GROUND, CZ - 30, 210, Y_FL1, CZ + 30, "stone_rusticated")
            coll.add_box(192, 0, CZ - 30, 210, Y_FL1, CZ + 30, "east_landing", "steps")
            for st in range(4):
                sy1 = Y_GROUND + (st + 1) * 3.0
                sx0 = 210 + (3 - st) * 5.0
                structure.add_box(sx0, Y_GROUND, CZ - 30, sx0 + 5.0, sy1, CZ + 30, "stone_rusticated")
                coll.add_box(sx0, 0, CZ - 30, sx0 + 5.0, sy1, CZ + 30, f"east_step_{st + 1}", "steps")
        elif side == "-x":
            structure.add_box(-210, Y_GROUND, CZ - 30, -192, Y_FL1, CZ + 30, "stone_rusticated")
            coll.add_box(-210, 0, CZ - 30, -192, Y_FL1, CZ + 30, "west_landing", "steps")
            for st in range(4):
                sy1 = Y_GROUND + (st + 1) * 3.0
                sx0 = -210 - (4 - st) * 5.0
                structure.add_box(sx0, Y_GROUND, CZ - 30, sx0 + 5.0, sy1, CZ + 30, "stone_rusticated")
                coll.add_box(sx0, 0, CZ - 30, sx0 + 5.0, sy1, CZ + 30, f"west_step_{st + 1}", "steps")

    for s in ("-z", "+x", "-x"):
        add_side_steps(s)

    # ==========================================================================
    # 5. CENTRAL CLOCK TOWER ON VISIBLE SQUARE MASONRY BASE (rising to 38.02 m!)
    #    - Proud Square Tower Base Drum (TW_BASE=54, y=216..282) visibly clearing the roof ridge!
    #    - Recessed Brick Clock Shaft (TW=44, y=282..362) with stone corner quoins
    #    - 4 Circular Backlit Clock Dials (r=11 art px = 1.71 m dia, 8.74 m² total!) in sculpted stone bezels
    #    - Open 4-Arch Stone Belfry (y=362..408) + cast-bronze bell + Copper Spire + Finial (490)
    # ==========================================================================
    TW_BASE = 54.0  # Visible square stone-trimmed tower base (8.38 m x 8.38 m)
    TW = 44.0       # Upper brick clock tower shaft (6.83 m x 6.83 m)

    # Proud square tower base rising cleanly through and ABOVE the central roof ridge (y=246 -> 282)!
    structure.add_box(-TW_BASE, 216, CZ - TW_BASE, TW_BASE, 252, CZ + TW_BASE, "stone_rusticated")
    structure.add_box(-TW_BASE - 3, 250, CZ - TW_BASE - 3, TW_BASE + 3, 256, CZ + TW_BASE + 3, "stone_rusticated")
    structure.add_box(-TW_BASE, 256, CZ - TW_BASE, TW_BASE, 280, CZ + TW_BASE, "brick_red")
    # Stone corner pilasters on the square tower base
    for sx in (-1, 1):
        for sz in (-1, 1):
            bx0 = (TW_BASE - 7) if sx > 0 else (-TW_BASE - 1.5)
            bx1 = (TW_BASE + 1.5) if sx > 0 else (-TW_BASE + 7)
            bz0 = CZ + ((TW_BASE - 7) if sz > 0 else (-TW_BASE - 1.5))
            bz1 = CZ + ((TW_BASE + 1.5) if sz > 0 else (-TW_BASE + 7))
            structure.add_box(bx0, 256, bz0, bx1, 280, bz1, "stone_rusticated")
    # Heavy projecting limestone watertable cornice separating base from clock shaft (y = 278..284)
    structure.add_box(-TW_BASE - 4, 278, CZ - TW_BASE - 4, TW_BASE + 4, 284, CZ + TW_BASE + 4, "stone_rusticated")

    # Brick Clock Tower Shaft rising atop the square base (y = 284 .. 362)
    structure.add_box(-TW, 284, CZ - TW, TW, Y_BELFRY_BOT, CZ + TW, "brick_red")
    coll.add_box(-TW_BASE, Y_EAVE, CZ - TW_BASE, TW_BASE, Y_FINIAL_TOP, CZ + TW_BASE, "clock_tower_full_height", "exterior")

    # Stone corner quoins & horizontal cornice rings on clock shaft
    for sx in (-1, 1):
        for sz in (-1, 1):
            qx0 = (TW - 6) if sx > 0 else (-TW - 1.5)
            qx1 = (TW + 1.5) if sx > 0 else (-TW + 6)
            qz0 = CZ + ((TW - 6) if sz > 0 else (-TW - 1.5))
            qz1 = CZ + ((TW + 1.5) if sz > 0 else (-TW + 6))
            structure.add_box(qx0, 284, qz0, qx1, Y_BELFRY_BOT, qz1, "stone_rusticated")
    for ty in (306, 358, 404):
        structure.add_box(-TW - 4, ty, CZ - TW - 4, TW + 4, ty + 4, CZ + TW + 4, "stone_rusticated")

    # Four Circular Clock Dials (radius 11 art px = 1.71 m dia, 8.74 m² combined glow!) + sculpted stone bezels!
    # Centered at Y_TOWER_CLK = 335 (26.00 m), each using its own glow_clock_<dir> material!
    structure.add_clock_disc_and_bezel(0.0, Y_TOWER_CLK, CZ + TW, "+z", 11.0, 15.0, 2.4, "glow_clock_south")
    structure.add_clock_disc_and_bezel(0.0, Y_TOWER_CLK, CZ - TW, "-z", 11.0, 15.0, 2.4, "glow_clock_north")
    structure.add_clock_disc_and_bezel(TW, Y_TOWER_CLK, CZ, "+x", 11.0, 15.0, 2.4, "glow_clock_east")
    structure.add_clock_disc_and_bezel(-TW, Y_TOWER_CLK, CZ, "-x", 11.0, 15.0, 2.4, "glow_clock_west")

    # Open Belfry (y = 362 .. 404): 4 corner masonry piers + 4 real arched belfry openings!
    BW = 38.0
    for sx in (-1, 1):
        for sz in (-1, 1):
            px0 = (BW - 11) if sx > 0 else -BW
            px1 = BW if sx > 0 else (-BW + 11)
            pz0 = CZ + ((BW - 11) if sz > 0 else -BW)
            pz1 = CZ + (BW if sz > 0 else (-BW + 11))
            structure.add_box(px0, Y_BELFRY_BOT, pz0, px1, 404, pz1, "stone_rusticated")
    structure.add_box(-BW, Y_BELFRY_BOT, CZ - BW, BW, Y_BELFRY_BOT + 4, CZ + BW, "stone_rusticated")
    structure.add_arch_ring_z(0, 384, CZ + BW - 6, CZ + BW, 15.0, 19.0, 8, "stone_rusticated")
    structure.add_arch_ring_z(0, 384, CZ - BW, CZ - BW + 6, 15.0, 19.0, 8, "stone_rusticated")
    for sx_b in (-1, 1):
        xb0 = (BW - 6) if sx_b > 0 else -BW
        xb1 = BW if sx_b > 0 else (-BW + 6)
        for i in range(8):
            a0 = math.pi * i / 8
            a1 = math.pi * (i + 1) / 8
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            xf = xb1 if sx_b > 0 else xb0
            xb = xb0 if sx_b > 0 else xb1
            p_in0 = (xf, 384 + s0 * 15.0, CZ + c0 * 15.0)
            p_out0 = (xf, 384 + s0 * 19.0, CZ + c0 * 19.0)
            p_out1 = (xf, 384 + s1 * 19.0, CZ + c1 * 19.0)
            p_in1 = (xf, 384 + s1 * 15.0, CZ + c1 * 15.0)
            b_in0 = (xb, 384 + s0 * 15.0, CZ + c0 * 15.0)
            b_out0 = (xb, 384 + s0 * 19.0, CZ + c0 * 19.0)
            b_out1 = (xb, 384 + s1 * 19.0, CZ + c1 * 19.0)
            b_in1 = (xb, 384 + s1 * 15.0, CZ + c1 * 15.0)
            if sx_b > 0:
                structure.add_planar_quad_art(p_out0, p_in0, p_in1, p_out1, "stone_rusticated")
                structure.add_planar_quad_art(b_in0, b_out0, b_out1, b_in1, "stone_rusticated")
                structure.add_planar_quad_art(p_in0, b_in0, b_in1, p_in1, "stone_rusticated")
            else:
                structure.add_planar_quad_art(p_in0, p_out0, p_out1, p_in1, "stone_rusticated")
                structure.add_planar_quad_art(b_out0, b_in0, b_in1, b_out1, "stone_rusticated")
                structure.add_planar_quad_art(b_in0, p_in0, p_in1, b_in1, "stone_rusticated")

    # Copper-Green Pyramidal Spire (y = 408 .. 472) + Cast-Iron Finial (472 .. 490 = 38.02 m!)
    spire_r = 42.0 * math.sqrt(2.0)
    structure.add_cone(0, Y_BELFRY_TOP, Y_SPIRE_TOP, CZ, spire_r, 4, "copper_spire", cap_bot=True)
    structure.add_cylinder(0, Y_SPIRE_TOP - 2, Y_SPIRE_TOP + 4, CZ, 4.0, 4.0, 8, "detail_atlas", region_name="dark_iron")
    structure.add_cone(0, Y_SPIRE_TOP + 4, Y_FINIAL_TOP, CZ, 2.2, 4, "detail_atlas", region_name="dark_iron")

    # 1988 Protest Banner "SAVE OUR / COURTHOUSE" hung across the square tower base below the clock!
    if spec["has_protest_banner"]:
        structure.add_panel_z(-46, 254, 46, 280, CZ + TW_BASE + 1.2, "+z", "sign_plates", "protest_banner_1988")

    # 2008 Compact Warm Floodlight Heads on the 4 corners of the tower base ledge (glow_ ONLY on tiny ~0.2 m lenses!)
    if spec["has_floodlights"]:
        for sx in (-1, 1):
            for sz in (-1, 1):
                fx = sx * (TW_BASE - 4)
                fz = CZ + sz * (TW_BASE - 4)
                structure.add_box(fx - 1.8, 284, fz - 1.8, fx + 1.8, 286.5, fz + 1.8, "detail_atlas", region_name="dark_iron")
                structure.add_panel_y(fx - 1.3, fz - 1.3, fx + 1.3, fz + 1.3, 286.65, "+y", "glow_lamps", "lamp_flood")

    # 2008 Patched-Brick Scar on the -Z North Wing showing where the 1951-1999 concrete annex stood!
    if spec["has_brick_patch"]:
        structure.add_panel_z(-48, Y_FL2 + 2, 48, Y_FL2 + 50, CZ - 192.35, "-z", "sign_plates", "patched_brick_2008")

    # ==========================================================================
    # 6. REAL 3-STOREY WALKABLE INTERIOR SHELL + NAMED floor_<room> MESHES + door_room_*
    # ==========================================================================
    floor_buckets = {}

    def make_floor_mesh(room_name, x0, z0, x1, z1, y, mat_name, region_name=None):
        fb = MeshBucket(f"floor_{room_name}", mat_idx, mat_size, regions)
        fb.add_panel_y(x0, z0, x1, z1, y, "+y", mat_name, region_name)
        # Underside ceiling finish for upper floors
        if y > Y_FL1 + 1.0:
            fb.add_panel_y(x0, z0, x1, z1, y - 2.0, "-y", "interior_wall")
        floor_buckets[f"floor_{room_name}"] = fb

    # --- GROUND FLOOR (y = Y_FL1 = 14) ---
    # Central Rotunda Hall (marble checkerboard tiled in 64x64 chunks so no triangle exceeds Q2!)
    fb_rot = MeshBucket("floor_rotunda", mat_idx, mat_size, regions)
    for gx in (-52, 0):
        for gz in (-52, 0):
            fb_rot.add_panel_y(gx, CZ + gz, gx + 52, CZ + gz + 52, Y_FL1 + 0.1, "+y", "detail_atlas", "rotunda_marble")
    floor_buckets["floor_rotunda"] = fb_rot

    # 4 Cross Entry Corridors
    make_floor_mesh("entry_south", -56, CZ + 52, 56, CZ + 186, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("entry_north", -56, CZ - 186, 56, CZ - 52, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("entry_east", 52, CZ - 56, 186, CZ + 56, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("entry_west", -186, CZ - 56, -52, CZ + 56, Y_FL1 + 0.1, "interior_floor")

    # 4 Ground-Floor Corner Offices (Clerk SW, Assessor SE, Treasurer NW, Recorder NE)
    make_floor_mesh("office_clerk", -110, CZ + 58, -58, CZ + 110, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("office_assessor", 58, CZ + 58, 110, CZ + 110, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("office_treasurer", -110, CZ - 110, -58, CZ - 58, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("office_recorder", 58, CZ - 110, 110, CZ - 58, Y_FL1 + 0.1, "interior_floor")

    # Ground-Floor Interior Partition Walls (separating offices from corridors, with open doorways!)
    def add_int_wall(x0, y0, z0, x1, y1, z1, name):
        structure.add_box(x0, y0, z0, x1, y1, z1, "interior_wall")
        coll.add_box(x0, y0, z0, x1, y1, z1, name, "interior")

    # Office partitions along X = +/-58 (with 18-art-px doorway gaps centered at CZ +/- 90)
    for sx, side_lbl in ((-1, "w"), (1, "e")):
        wx0 = -60 if sx < 0 else 58
        wx1 = -58 if sx < 0 else 60
        for sz, ns_lbl in ((1, "s"), (-1, "n")):
            za = CZ + (58 if sz > 0 else -110)
            zb = CZ + (110 if sz > 0 else -58)
            mid = CZ + (90 if sz > 0 else -90)
            add_int_wall(wx0, Y_FL1, za, wx1, Y_FL2, mid - 9, f"int_wall_fl1_{ns_lbl}{side_lbl}_a")
            add_int_wall(wx0, Y_FL1, mid + 9, wx1, Y_FL2, zb, f"int_wall_fl1_{ns_lbl}{side_lbl}_b")
            structure.add_box(wx0, Y_FL1 + 30, mid - 9, wx1, Y_FL2, mid + 9, "interior_wall")
            # Perpendicular partition along Z = CZ +/- 58
            pza = CZ + (58 if sz > 0 else -60)
            pzb = CZ + (60 if sz > 0 else -58)
            pxa = -110 if sx < 0 else 60
            pxb = -60 if sx < 0 else 110
            add_int_wall(pxa, Y_FL1, pza, pxb, Y_FL2, pzb, f"int_wall_fl1_cross_{ns_lbl}{side_lbl}")

    # Unified Cream-Plaster & Oak-Wainscot Interior Wall Linings (matching courtroom across ALL 3 floors of stair hall!)
    # Ground-floor entry vestibule side walls (N/S & E/W corridors finished in oak wainscot + cream plaster!)
    for z_a, z_b in ((CZ + 110, CZ + 186), (CZ - 186, CZ - 110)):
        structure.add_panel_x(z_a, Y_FL1, z_b, Y_FL2, -55.6, "+x", "interior_wall")
        structure.add_panel_x(z_a, Y_FL1, z_b, Y_FL2, 55.6, "-x", "interior_wall")
    for x_a, x_b in ((-186, -110), (110, 186)):
        structure.add_panel_z(x_a, Y_FL1, x_b, Y_FL2, CZ + 55.6, "-z", "interior_wall")
        structure.add_panel_z(x_a, Y_FL1, x_b, Y_FL2, CZ - 55.6, "+z", "interior_wall")
    # Rotunda North backing wall behind grand stair mid-landing (clean plaster & wainscot on Ground Floor!)
    structure.add_panel_z(-56, Y_FL1, -18, Y_FL2, CZ - 56.0, "+z", "interior_wall")
    structure.add_panel_z(18, Y_FL1, 56, Y_FL2, CZ - 56.0, "+z", "interior_wall")
    structure.add_panel_z(-18, Y_FL1 + 30, 18, Y_FL2, CZ - 56.0, "+z", "interior_wall")
    # Clean plaster ceilings over the 4 Ground-Floor entry corridors (<=44 art px chunks)
    for z_a, z_b in ((CZ + 56, CZ + 100), (CZ + 100, CZ + 144), (CZ + 144, CZ + 186), (CZ - 186, CZ - 144), (CZ - 144, CZ - 100), (CZ - 100, CZ - 56)):
        for sx_c in (-56, 0):
            structure.add_panel_y(sx_c, z_a, sx_c + 56, z_b, Y_FL2 - 2.0, "-y", "detail_atlas", "bandstand_cream")

    # Double-Height Courtroom Walls (Floors 2 & 3 in +Z South wing!)
    structure.add_panel_z(-56, Y_FL2, 56, Y_FL2 + 64, CZ + 185.6, "-z", "interior_wall")
    for sx_c in (-56, 0):
        structure.add_panel_z(sx_c, Y_FL2 + 64, sx_c + 56, Y_EAVE - 2, CZ + 185.6, "-z", "detail_atlas", "bandstand_cream")
    for z_a, z_b in ((CZ + 56, CZ + 120), (CZ + 120, CZ + 186)):
        structure.add_panel_x(z_a, Y_FL2, z_b, Y_FL2 + 64, -55.6, "+x", "interior_wall")
        structure.add_panel_x(z_a, Y_FL2, z_b, Y_FL2 + 64, 55.6, "-x", "interior_wall")
    for z_a, z_b in ((CZ + 56, CZ + 100), (CZ + 100, CZ + 144), (CZ + 144, CZ + 186)):
        structure.add_panel_x(z_a, Y_FL2 + 64, z_b, Y_EAVE - 2, -55.6, "+x", "detail_atlas", "bandstand_cream")
        structure.add_panel_x(z_a, Y_FL2 + 64, z_b, Y_EAVE - 2, 55.6, "-x", "detail_atlas", "bandstand_cream")
    for wy_c in (Y_FL2 + 16, Y_FL3 + 12):
        for wx_c in (-40, 0, 40):
            if wy_c == Y_FL2 + 16 and wx_c == 0:
                continue
            structure.add_panel_z(wx_c - 9, wy_c, wx_c + 9, wy_c + 28, CZ + 185.2, "-z", "glass_window", "win_arched")
    for z_a, z_b in ((CZ + 56, CZ + 100), (CZ + 100, CZ + 144), (CZ + 144, CZ + 186)):
        for sx_c in (-56, 0):
            structure.add_panel_y(sx_c, z_a, sx_c + 56, z_b, Y_EAVE - 2, "-y", "detail_atlas", "bandstand_cream")

    # --- CLEAN GRAND STAIRCASE (Step flight -> Mid-Landing -> Twin Return Flights -> Floor 2 Gallery!) ---
    # Flight 1 (Ground Floor Y_FL1=14 -> Mid-Landing y=43): X in [-14, 14], Z from CZ-14 to CZ-42 (10 steps)
    for st in range(10):
        sy0 = Y_FL1 + st * 2.9
        sy1 = sy0 + 2.9
        sz1 = CZ - 14.0 - st * 2.8
        sz0 = sz1 - 2.8
        structure.add_box(-14, sy0, sz0, 14, sy1, sz1, "interior_floor")
        coll.add_box(-14, Y_FL1, sz0, 14, sy1, sz1, f"grand_stair_lower_step_{st + 1}", "interior_stairs")
    # Clean low sloped oak stringer rails on lower flight (NO obstructing vertical walls!)
    for rx in (-15.2, 14.0):
        structure.add_planar_quad_art((rx, Y_FL1 + 8, CZ - 14), (rx, Y_FL1 + 11, CZ - 14), (rx, 54, CZ - 42), (rx, 51, CZ - 42), "interior_floor")
        structure.add_planar_quad_art((rx, 51, CZ - 42), (rx, 54, CZ - 42), (rx, Y_FL1 + 11, CZ - 14), (rx, Y_FL1 + 8, CZ - 14), "interior_floor")
    # Mid-Landing (X in [-42, 42], Z in [CZ-55, CZ-42], y = 41..43)
    structure.add_box(-42, 41, CZ - 55, 42, 43, CZ - 42, "interior_floor")
    coll.add_box(-42, 40, CZ - 55, 42, 43, CZ - 42, "grand_stair_mid_landing", "interior_stairs")
    # Twin Return Flights (Mid-Landing y=43 -> Floor 2 Gallery Y_FL2=72): West [-42,-22] & East [22,42]
    for sx_f, fl_name in ((-32, "west"), (32, "east")):
        fx0, fx1 = sx_f - 10, sx_f + 10
        for st in range(10):
            sy0 = 43.0 + st * 2.9
            sy1 = sy0 + 2.9
            sz0 = CZ - 42.0 + st * 2.8
            sz1 = sz0 + 2.8
            structure.add_box(fx0, sy0, sz0, fx1, sy1, sz1, "interior_floor")
            coll.add_box(fx0, 40, sz0, fx1, sy1, sz1, f"grand_stair_upper_{fl_name}_step_{st + 1}", "interior_stairs")
        # Clean low sloped handrail along inner edge of return flight (NO tall wall slab!)
        rx = -21.8 if sx_f < 0 else 21.8
        structure.add_planar_quad_art((rx, 51, CZ - 42), (rx, 54, CZ - 42), (rx, Y_FL2 + 12, CZ - 14), (rx, Y_FL2 + 9, CZ - 14), "interior_floor")
        structure.add_planar_quad_art((rx, Y_FL2 + 9, CZ - 14), (rx, Y_FL2 + 12, CZ - 14), (rx, 54, CZ - 42), (rx, 51, CZ - 42), "interior_floor")

    # Floor 2 -> Floor 3 Staircase tucked cleanly in North Wing (X in [-46, -24], Z from CZ-60 to CZ-124)
    for st in range(16):
        sy0 = Y_FL2 + st * 3.625
        sy1 = sy0 + 3.625
        sz1 = CZ - 60.0 - st * 4.0
        sz0 = sz1 - 4.0
        structure.add_box(-46, sy0, sz0, -24, sy1, sz1, "interior_floor")
        coll.add_box(-46, Y_FL2, sz0, -24, sy1, sz1, f"fl2_to_fl3_stair_step_{st + 1}", "interior_stairs")

    # --- FLOOR 2 (y = Y_FL2 = 72) & FLOOR 3 (y = Y_FL3 = 130) OPEN-WELL GALLERIES ---
    # Open central well: X in [-42, 42], Z in [CZ-42, CZ+42] — ZERO crossing beams or corner piers!
    fb_g2 = MeshBucket("floor_gallery_2", mat_idx, mat_size, regions)
    fb_g2.add_panel_y(-62, CZ + 40, 62, CZ + 56, Y_FL2 + 0.1, "+y", "interior_floor")
    fb_g2.add_panel_y(-62, CZ - 56, 62, CZ - 40, Y_FL2 + 0.1, "+y", "interior_floor")
    fb_g2.add_panel_y(-62, CZ - 40, -42, CZ + 40, Y_FL2 + 0.1, "+y", "interior_floor")
    fb_g2.add_panel_y(42, CZ - 40, 62, CZ + 40, Y_FL2 + 0.1, "+y", "interior_floor")
    # Clean cream-plaster underside ceilings beneath Floor 2 gallery walkways (zero see-through to brick!)
    for sx_c in (-62, 0):
        fb_g2.add_panel_y(sx_c, CZ + 40, sx_c + 62, CZ + 56, Y_FL2 - 3.0, "-y", "detail_atlas", "bandstand_cream")
        fb_g2.add_panel_y(sx_c, CZ - 56, sx_c + 62, CZ - 40, Y_FL2 - 3.0, "-y", "detail_atlas", "bandstand_cream")
    for sz_c in (-40, 0):
        fb_g2.add_panel_y(-62, CZ + sz_c, -42, CZ + sz_c + 40, Y_FL2 - 3.0, "-y", "detail_atlas", "bandstand_cream")
        fb_g2.add_panel_y(42, CZ + sz_c, 62, CZ + sz_c + 40, Y_FL2 - 3.0, "-y", "detail_atlas", "bandstand_cream")
    floor_buckets["floor_gallery_2"] = fb_g2

    def add_clean_gallery_balustrade(y_fl, floor_tag):
        """Crisp cream-plaster fascia + open oak handrail + neat balusters round the open well!"""
        rh = 12.0  # ~0.93 m handrail height
        # Plaster fascia edge trim under each gallery walkway (split <=42 art px to stay in 64x64 detail_atlas quadrant)
        for seg in (-42, 0):
            structure.add_box(seg, y_fl - 3, CZ + 40, seg + 42, y_fl, CZ + 42, "detail_atlas", region_name="bandstand_cream")
            if floor_tag == "fl3":
                structure.add_box(seg, y_fl - 3, CZ - 42, seg + 42, y_fl, CZ - 40, "detail_atlas", region_name="bandstand_cream")
        for seg in (-40, 0):
            structure.add_box(-44, y_fl - 3, CZ + seg, -42, y_fl, CZ + seg + 40, "detail_atlas", region_name="bandstand_cream")
            structure.add_box(42, y_fl - 3, CZ + seg, 44, y_fl, CZ + seg + 40, "detail_atlas", region_name="bandstand_cream")

        def add_rail_run(x0, z0, x1, z1, axis, r_name):
            # Bottom shoe rail + crisp top oak handrail + evenly spaced vertical posts (open & airy!)
            structure.add_box(x0, y_fl, z0, x1, y_fl + 2.0, z1, "interior_floor")
            structure.add_box(x0, y_fl + rh - 2.0, z0, x1, y_fl + rh, z1, "interior_floor")
            if axis == "x":
                span = x1 - x0
                n_p = max(2, int(span // 14))
                for pi in range(n_p + 1):
                    px = x0 + (span - 1.6) * (pi / n_p)
                    structure.add_box(px, y_fl + 2.0, z0, px + 1.6, y_fl + rh - 2.0, z1, "interior_floor")
            else:
                span = z1 - z0
                n_p = max(2, int(span // 14))
                for pi in range(n_p + 1):
                    pz = z0 + (span - 1.6) * (pi / n_p)
                    structure.add_box(x0, y_fl + 2.0, pz, x1, y_fl + rh - 2.0, pz + 1.6, "interior_floor")
            coll.add_box(x0, y_fl, z0, x1, y_fl + rh, z1, r_name, "interior")

        # South gallery railing
        add_rail_run(-42, CZ + 40, 42, CZ + 42, "x", f"{floor_tag}_gallery_rail_south")
        # West & East railings (open at Floor 2 north stair arrival)
        z_start = CZ - 14 if floor_tag == "fl2" else CZ - 40
        add_rail_run(-44, z_start, -42, CZ + 40, "z", f"{floor_tag}_gallery_rail_west")
        add_rail_run(42, z_start, 44, CZ + 40, "z", f"{floor_tag}_gallery_rail_east")
        if floor_tag == "fl3":
            add_rail_run(-42, CZ - 42, 42, CZ - 40, "x", "fl3_gallery_rail_north")

    add_clean_gallery_balustrade(Y_FL2, "fl2")

    # DOUBLE-HEIGHT COURTROOM (Floor 2 & Floor 3 in +Z South Wing!)
    make_floor_mesh("courtroom", -56, CZ + 56, 56, CZ + 186, Y_FL2 + 0.1, "interior_floor")
    add_int_wall(-62, Y_FL2, CZ + 54, -12, Y_EAVE, CZ + 56, "courtroom_north_wall_west")
    add_int_wall(12, Y_FL2, CZ + 54, 62, Y_EAVE, CZ + 56, "courtroom_north_wall_east")
    structure.add_box(-12, Y_FL2 + 34, CZ + 54, 12, Y_EAVE, CZ + 56, "interior_wall")

    # Courtroom Architectural Fixtures ONLY (Oak Bar Rail + Raised Judge's Bench)
    structure.add_box(-54, Y_FL2, CZ + 113, -8, Y_FL2 + 13, CZ + 116, "interior_floor")
    structure.add_box(8, Y_FL2, CZ + 113, 54, Y_FL2 + 13, CZ + 116, "interior_floor")
    for bx in (-54, -9, 7, 52):
        structure.add_box(bx, Y_FL2, CZ + 112.5, bx + 2.5, Y_FL2 + 15, CZ + 116.5, "interior_floor")
    coll.add_box(-54, Y_FL2, CZ + 113, -8, Y_FL2 + 13, CZ + 116, "courtroom_bar_rail_west", "interior")
    coll.add_box(8, Y_FL2, CZ + 113, 54, Y_FL2 + 13, CZ + 116, "courtroom_bar_rail_east", "interior")

    structure.add_box(-30, Y_FL2, CZ + 148, 30, Y_FL2 + 3, CZ + 182, "interior_floor")
    structure.add_box(-26, Y_FL2 + 3, CZ + 152, 26, Y_FL2 + 6, CZ + 182, "interior_floor")
    structure.add_box(-22, Y_FL2 + 6, CZ + 154, 22, Y_FL2 + 22, CZ + 159, "interior_floor")
    structure.add_box(-22, Y_FL2 + 6, CZ + 159, -18, Y_FL2 + 22, CZ + 174, "interior_floor")
    structure.add_box(18, Y_FL2 + 6, CZ + 159, 22, Y_FL2 + 22, CZ + 174, "interior_floor")
    structure.add_box(-23, Y_FL2 + 22, CZ + 153, 23, Y_FL2 + 24, CZ + 160, "interior_floor")
    structure.add_box(-24, Y_FL2 + 6, CZ + 180, 24, Y_FL2 + 44, CZ + 184, "interior_floor")
    coll.add_box(-30, Y_FL2, CZ + 148, 30, Y_FL2 + 24, CZ + 184, "courtroom_judges_bench", "interior")

    # Floor 2 Side Offices (Chambers W, Jury Room E, Law Library N)
    make_floor_mesh("chambers", -186, CZ - 56, -64, CZ + 56, Y_FL2 + 0.1, "interior_floor")
    make_floor_mesh("jury", 64, CZ - 56, 186, CZ + 56, Y_FL2 + 0.1, "interior_floor")
    make_floor_mesh("law_library", -56, CZ - 186, 56, CZ - 56, Y_FL2 + 0.1, "interior_floor")

    # Floor 2 Rotunda Perimeter Walls (unified oak wainscot + cream plaster enclosing gallery_2!)
    add_int_wall(-64, Y_FL2, CZ - 58, -62, Y_FL3, CZ - 9, "int_wall_fl2_chambers_n")
    add_int_wall(-64, Y_FL2, CZ + 9, -62, Y_FL3, CZ + 58, "int_wall_fl2_chambers_s")
    structure.add_box(-64, Y_FL2 + 30, CZ - 9, -62, Y_FL3, CZ + 9, "interior_wall")
    add_int_wall(62, Y_FL2, CZ - 58, 64, Y_FL3, CZ - 9, "int_wall_fl2_jury_n")
    add_int_wall(62, Y_FL2, CZ + 9, 64, Y_FL3, CZ + 58, "int_wall_fl2_jury_s")
    structure.add_box(62, Y_FL2 + 30, CZ - 9, 64, Y_FL3, CZ + 9, "interior_wall")
    add_int_wall(-62, Y_FL2, CZ - 58, -46, Y_FL3, CZ - 56, "int_wall_fl2_lib_farw")
    add_int_wall(-22, Y_FL2, CZ - 58, -9, Y_FL3, CZ - 56, "int_wall_fl2_lib_w")
    add_int_wall(9, Y_FL2, CZ - 58, 62, Y_FL3, CZ - 56, "int_wall_fl2_lib_e")
    structure.add_box(-9, Y_FL2 + 30, CZ - 58, 9, Y_FL3, CZ - 56, "interior_wall")

    # --- FLOOR 3 (y = Y_FL3 = 130) ---
    fb_g3 = MeshBucket("floor_gallery_3", mat_idx, mat_size, regions)
    fb_g3.add_panel_y(-62, CZ + 40, 62, CZ + 54, Y_FL3 + 0.1, "+y", "interior_floor")
    fb_g3.add_panel_y(-62, CZ - 56, 62, CZ - 40, Y_FL3 + 0.1, "+y", "interior_floor")
    fb_g3.add_panel_y(-62, CZ - 40, -42, CZ + 40, Y_FL3 + 0.1, "+y", "interior_floor")
    fb_g3.add_panel_y(42, CZ - 40, 62, CZ + 40, Y_FL3 + 0.1, "+y", "interior_floor")
    # Clean cream-plaster underside ceilings beneath Floor 3 gallery walkways!
    for sx_c in (-62, 0):
        fb_g3.add_panel_y(sx_c, CZ + 40, sx_c + 62, CZ + 54, Y_FL3 - 3.0, "-y", "detail_atlas", "bandstand_cream")
        fb_g3.add_panel_y(sx_c, CZ - 56, sx_c + 62, CZ - 40, Y_FL3 - 3.0, "-y", "detail_atlas", "bandstand_cream")
    for sz_c in (-40, 0):
        fb_g3.add_panel_y(-62, CZ + sz_c, -42, CZ + sz_c + 40, Y_FL3 - 3.0, "-y", "detail_atlas", "bandstand_cream")
        fb_g3.add_panel_y(42, CZ + sz_c, 62, CZ + sz_c + 40, Y_FL3 - 3.0, "-y", "detail_atlas", "bandstand_cream")
    floor_buckets["floor_gallery_3"] = fb_g3
    add_clean_gallery_balustrade(Y_FL3, "fl3")

    # Floor 3 Offices (West Records, East Archives, North Commission; South is open double-height Courtroom!)
    make_floor_mesh("records", -186, CZ - 56, -64, CZ + 56, Y_FL3 + 0.1, "interior_floor")
    make_floor_mesh("archives", 64, CZ - 56, 186, CZ + 56, Y_FL3 + 0.1, "interior_floor")
    make_floor_mesh("commission", -56, CZ - 186, 56, CZ - 56, Y_FL3 + 0.1, "interior_floor")

    add_int_wall(-64, Y_FL3, CZ - 58, -62, Y_EAVE, CZ - 9, "int_wall_fl3_records_n")
    add_int_wall(-64, Y_FL3, CZ + 9, -62, Y_EAVE, CZ + 58, "int_wall_fl3_records_s")
    structure.add_box(-64, Y_FL3 + 30, CZ - 9, -62, Y_EAVE, CZ + 9, "interior_wall")
    add_int_wall(62, Y_FL3, CZ - 58, 64, Y_EAVE, CZ - 9, "int_wall_fl3_archives_n")
    add_int_wall(62, Y_FL3, CZ + 9, 64, Y_EAVE, CZ + 58, "int_wall_fl3_archives_s")
    structure.add_box(62, Y_FL3 + 30, CZ - 9, 64, Y_EAVE, CZ + 9, "interior_wall")
    add_int_wall(-62, Y_FL3, CZ - 58, -9, Y_EAVE, CZ - 56, "int_wall_fl3_comm_w")
    add_int_wall(9, Y_FL3, CZ - 58, 62, Y_EAVE, CZ - 56, "int_wall_fl3_comm_e")
    structure.add_box(-9, Y_FL3 + 30, CZ - 58, 9, Y_EAVE, CZ - 56, "interior_wall")

    # --- ONE CLEAR FRAMED OVERHEAD SKYLIGHT (ZERO beams crossing the open well!) ---
    # Clean cream-plaster ceiling soffit border over the Floor 3 gallery ring (y = Y_EAVE - 2)
    for sx_c in (-62, 0):
        structure.add_panel_y(sx_c, CZ + 42, sx_c + 62, CZ + 56, Y_EAVE - 2, "-y", "detail_atlas", "bandstand_cream")
        structure.add_panel_y(sx_c, CZ - 56, sx_c + 62, CZ - 42, Y_EAVE - 2, "-y", "detail_atlas", "bandstand_cream")
    for sz_c in (-42, 0):
        structure.add_panel_y(-62, CZ + sz_c, -42, CZ + sz_c + 42, Y_EAVE - 2, "-y", "detail_atlas", "bandstand_cream")
        structure.add_panel_y(42, CZ + sz_c, 62, CZ + sz_c + 42, Y_EAVE - 2, "-y", "detail_atlas", "bandstand_cream")
    # Clean short recessed plaster skylight curb (y = 186 .. 198, open [-42, +42])
    for seg in (-42, 0):
        structure.add_panel_z(seg, Y_EAVE - 2, seg + 42, 198, CZ - 42, "+z", "detail_atlas", "bandstand_cream")
        structure.add_panel_z(seg, Y_EAVE - 2, seg + 42, 198, CZ + 42, "-z", "detail_atlas", "bandstand_cream")
        structure.add_panel_x(CZ + seg, Y_EAVE - 2, CZ + seg + 42, 198, -42, "+x", "detail_atlas", "bandstand_cream")
        structure.add_panel_x(CZ + seg, Y_EAVE - 2, CZ + seg + 42, 198, 42, "-x", "detail_atlas", "bandstand_cream")
    # ONE clear overhead framed skylight grid (4 crisp 42x42 glass tiles at y = 198 — ZERO obstructing beams!)
    for sx in (-42, 0):
        for sz in (-42, 0):
            structure.add_panel_y(sx, CZ + sz, sx + 42, CZ + sz + 42, 198.0, "-y", "glass_window", "skylight_pane")

    # ==========================================================================
    # 7. THE TOWN SQUARE LANDMARKS: MEMORIAL, BANDSTAND, FLAGPOLE & ERA EXTRAS
    # ==========================================================================
    # A. CIVIL WAR SOLDIER MEMORIAL (SE lawn quadrant, x = 232, z = CZ + 260 = -252)
    #    Stepped granite base + short stout pedestal ("1861 - 1865", no names) + 1.8 m weathered bronze-green soldier!
    mx, mz = 232.0, CZ + 260.0
    # 3-tier stepped granite base (y = 2 .. 16)
    lot_memorial.add_box(mx - 22, Y_GROUND, mz - 22, mx + 22, 6, mz + 22, "stone_rusticated")
    lot_memorial.add_box(mx - 19, 6, mz - 19, mx + 19, 11, mz + 19, "stone_rusticated")
    lot_memorial.add_box(mx - 17, 11, mz - 17, mx + 17, 16, mz + 17, "stone_rusticated")
    # Short stout granite pedestal die (y = 16 .. 44, 32x32 art px = 2.48 m wide x 2.17 m tall) + cornice cap (44..49)
    lot_memorial.add_box(mx - 16, 16, mz - 16, mx + 16, 44, mz + 16, "stone_rusticated")
    lot_memorial.add_box(mx - 18, 44, mz - 18, mx + 18, 47.5, mz + 18, "stone_rusticated")
    lot_memorial.add_box(mx - 13, 47.5, mz - 13, mx + 13, 49.5, mz + 13, "stone_rusticated")
    # Plain carved granite inscription panel on front (+Z) of pedestal: "1861 - 1865" (NO names!)
    lot_memorial.add_panel_z(mx - 16, 25, mx + 16, 34, mz + 16.25, "+z", "sign_plates", "memorial_1861_1865")
    # Standing ~1.84 m Civil War Soldier Statue (23.7 art px tall, y = 49.5 .. 73.2) in weathered bronze-green!
    lot_memorial.add_box(mx - 6.5, 49.5, mz - 5.5, mx + 6.5, 51.5, mz + 5.5, "sign_plates", region_name="bronze_soldier")    # bronze base plinth
    # Boots & separated trouser legs (clear silhouette gap between legs!)
    lot_memorial.add_box(mx - 3.0, 51.5, mz - 1.5, mx - 0.8, 53.5, mz + 2.4, "sign_plates", region_name="bronze_soldier")  # left boot
    lot_memorial.add_box(mx + 0.8, 51.5, mz - 1.5, mx + 3.0, 53.5, mz + 2.4, "sign_plates", region_name="bronze_soldier")  # right boot
    lot_memorial.add_box(mx - 2.7, 53.5, mz - 1.4, mx - 0.8, 58.8, mz + 1.4, "sign_plates", region_name="bronze_soldier")  # left leg
    lot_memorial.add_box(mx + 0.8, 53.5, mz - 1.4, mx + 2.7, 58.8, mz + 1.4, "sign_plates", region_name="bronze_soldier")  # right leg
    # Flared Civil War greatcoat skirt & belted waist + shoulder cape
    lot_memorial.add_box(mx - 3.9, 58.5, mz - 2.3, mx + 3.9, 62.6, mz + 2.3, "sign_plates", region_name="bronze_soldier")  # greatcoat tails
    lot_memorial.add_box(mx - 3.2, 62.6, mz - 2.0, mx + 3.2, 64.0, mz + 2.2, "detail_atlas", region_name="dark_iron")      # dark bronze belt
    lot_memorial.add_box(mx - 3.7, 64.0, mz - 2.2, mx + 3.7, 68.8, mz + 2.2, "sign_plates", region_name="bronze_soldier")  # chest & uniform coat
    lot_memorial.add_box(mx - 4.3, 66.8, mz - 2.4, mx + 4.3, 68.8, mz + 2.4, "sign_plates", region_name="bronze_soldier")  # broad greatcoat cape shoulders
    # Arms (left arm resting at side; right arm holding tall musket at parade rest)
    lot_memorial.add_box(mx - 5.1, 61.5, mz - 1.2, mx - 3.7, 67.6, mz + 1.4, "sign_plates", region_name="bronze_soldier")  # left arm
    lot_memorial.add_box(mx + 3.7, 63.0, mz - 1.0, mx + 5.1, 67.6, mz + 2.4, "sign_plates", region_name="bronze_soldier")  # right arm reaching to rifle
    # Neck, sculpted head & distinct Civil War kepi cap with forward-projecting visor brim
    lot_memorial.add_box(mx - 1.1, 68.8, mz - 1.1, mx + 1.1, 69.8, mz + 1.1, "sign_plates", region_name="bronze_soldier")  # neck
    lot_memorial.add_box(mx - 1.8, 69.8, mz - 1.8, mx + 1.8, 72.3, mz + 1.9, "sign_plates", region_name="bronze_soldier")  # head
    lot_memorial.add_box(mx - 2.1, 72.3, mz - 2.0, mx + 2.1, 73.7, mz + 2.0, "sign_plates", region_name="bronze_soldier")  # kepi crown
    lot_memorial.add_box(mx - 2.1, 72.1, mz + 1.6, mx + 2.1, 72.8, mz + 3.5, "detail_atlas", region_name="dark_iron")      # kepi forward visor brim
    # Tall Springfield musket & bayonet standing cleanly beside right leg (butt y=51.5 -> muzzle y=71.5 -> bayonet y=75.0)
    lot_memorial.add_box(mx + 4.3, 51.5, mz + 1.5, mx + 5.5, 58.0, mz + 3.0, "sign_plates", region_name="bronze_soldier")  # rifle stock/butt
    lot_memorial.add_box(mx + 4.5, 58.0, mz + 1.8, mx + 5.3, 71.5, mz + 2.6, "detail_atlas", region_name="dark_iron")      # musket barrel
    lot_memorial.add_box(mx + 4.7, 71.5, mz + 2.0, mx + 5.1, 75.2, mz + 2.4, "detail_atlas", region_name="dark_iron")      # bayonet tip
    coll.add_box(mx - 22, 0, mz - 22, mx + 22, 75.2, mz + 22, "civil_war_memorial", "square")

    # B. OCTAGONAL BANDSTAND (SW lawn quadrant, x = -240, z = CZ + 260 = -252)
    bx, bz = -240.0, CZ + 260.0
    # Octagonal stone/concrete base pad (ALWAYS present: concrete pad remains in 1962/1988 when bandstand is gone!)
    lot_bandstand.add_cylinder(bx, Y_GROUND, 5, bz, 44.0, 44.0, 8, "ground_pavement", cap_top=True)
    if spec["has_bandstand"]:
        # Raised octagonal timber deck (y = 5..14) + front steps facing +X toward center path
        lot_bandstand.add_cylinder(bx, 5, 14, bz, 38.0, 38.0, 8, "stone_rusticated", cap_top=True)
        for st in range(3):
            lot_bandstand.add_box(bx + 36 + st * 4, Y_GROUND, bz - 10, bx + 40 + st * 4, 14 - st * 3, bz + 10, "stone_rusticated")
        # 8 cream-painted perimeter columns + railings (leave east side open for steps!)
        r_post = 34.0
        for i in range(8):
            ang = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
            px = bx + math.cos(ang) * r_post
            pz = bz + math.sin(ang) * r_post
            lot_bandstand.add_box(px - 2, 14, pz - 2, px + 2, 50, pz + 2, "detail_atlas", region_name="bandstand_cream")
            # Perimeter railing between adjacent posts (skip step entrance segment i == 7)
            if i != 7:
                ang2 = 2.0 * math.pi * ((i + 1) % 8) / 8.0 + math.pi / 8.0
                px2 = bx + math.cos(ang2) * r_post
                pz2 = bz + math.sin(ang2) * r_post
                # Lower baseboard & top handrail
                for ry0, ry1 in ((15, 18), (24, 27)):
                    lot_bandstand.add_planar_quad_art((px, ry0, pz), (px2, ry0, pz2), (px2, ry1, pz2), (px, ry1, pz), "detail_atlas", "bandstand_cream")
                    lot_bandstand.add_planar_quad_art((px2, ry0, pz2), (px, ry0, pz), (px, ry1, pz), (px2, ry1, pz2), "detail_atlas", "bandstand_cream")
        # Octagonal cream cornice ring + steep 8-sided conical slate roof + finial!
        lot_bandstand.add_cylinder(bx, 48, 53, bz, 39.0, 39.0, 8, "detail_atlas", region_name="bandstand_cream", cap_top=True, cap_bot=True)
        lot_bandstand.add_cone(bx, 53, 84, bz, 44.0, 8, "roof_slate", cap_bot=True)
        lot_bandstand.add_cone(bx, 84, 94, bz, 2.5, 4, "copper_spire" if year == 2008 else "detail_atlas",
                               region_name=None if year == 2008 else "dark_iron")
        coll.add_box(bx - 42, 0, bz - 42, bx + 42, 94, bz + 42, "octagonal_bandstand", "square")
    else:
        # 1962 & 1988: Low collision only for concrete pad edge (walkable/low curb)
        coll.add_box(bx - 42, 0, bz - 42, bx + 42, 5, bz + 42, "bandstand_concrete_pad", "square")

    # C. FLAGPOLE (front lawn beside +Z main path, x = -72, z = -140)
    fx, fz = -72.0, -140.0
    lot_flagpole.add_box(fx - 10, Y_GROUND, fz - 10, fx + 10, 7, fz + 10, "stone_rusticated")
    lot_flagpole.add_cylinder(fx, 7, 61, fz, 2.2, 1.8, 8, "detail_atlas", region_name="dark_iron", cap_top=False)
    lot_flagpole.add_cylinder(fx, 61, 115, fz, 1.8, 1.4, 8, "detail_atlas", region_name="dark_iron", cap_top=False)
    lot_flagpole.add_cylinder(fx, 115, 168, fz, 1.4, 1.0, 8, "detail_atlas", region_name="dark_iron", cap_top=True)
    # Stylized civic striped flag waving east from top of pole (double-sided panel)
    lot_flagpole.add_panel_z(fx + 1, 144, fx + 37, 166, fz + 0.4, "+z", "sign_plates", "flag_banner")
    lot_flagpole.add_panel_z(fx + 1, 144, fx + 37, 166, fz - 0.4, "-z", "sign_plates", "flag_banner")
    coll.add_box(fx - 10, 0, fz - 10, fx + 10, 168, fz + 10, "square_flagpole", "square")

    # D. 1894 HITCHING RAILS (4 timber & iron rails along the +Z street sidewalk curb!)
    if spec["has_hitching_rails"]:
        for hx in (-280, -160, 160, 280):
            lot_extras.add_box(hx - 20, Y_GROUND, -14, hx - 18, 15, -12, "detail_atlas", region_name="dark_iron")
            lot_extras.add_box(hx + 18, Y_GROUND, -14, hx + 20, 15, -12, "detail_atlas", region_name="dark_iron")
            lot_extras.add_box(hx - 21, 13, -14, hx + 21, 15, -12, "interior_floor")
            coll.add_box(hx - 21, 0, -14, hx + 21, 15, -12, f"hitching_rail_{hx}", "square")

    # E. 1951-1999 TWO-STOREY CONCRETE ANNEX (15.5 m x 19.8 m -> 200 x 256 art px attached to -Z North wing!)
    if spec["has_annex"]:
        # Attached directly to the North (-Z) wing: x in [-100, 100] (15.52 m), z in [CZ-448, CZ-192] (19.87 m), y = 2..124 (9.62 m)
        ax0, ax1 = -100.0, 100.0
        az0, az1 = CZ - 448.0, CZ - 192.0
        # Build annex perimeter in 50-art-px panels so NO triangle exceeds the 64x64 Q1 concrete_annex quadrant!
        for seg_x in range(-100, 100, 50):
            for seg_y in (Y_GROUND, 62):
                lot_extras.add_box(seg_x, seg_y, az0, seg_x + 50, seg_y + 60, az0 + 6, "detail_atlas",
                                   faces=["-z", "+y"], region_name="concrete_annex")
        for seg_z in range(int(az0), int(az1), 64):
            for seg_y in (Y_GROUND, 62):
                lot_extras.add_box(ax0, seg_y, seg_z, ax0 + 6, seg_y + 60, seg_z + 64, "detail_atlas",
                                   faces=["-x", "+y"], region_name="concrete_annex")
                lot_extras.add_box(ax1 - 6, seg_y, seg_z, ax1, seg_y + 60, seg_z + 64, "detail_atlas",
                                   faces=["+x", "+y"], region_name="concrete_annex")
        # South closure cheeks where annex widens past the 128-wide North pavilion
        for sx0, sx1 in ((-100, -64), (64, 100)):
            for seg_y in (Y_GROUND, 62):
                lot_extras.add_box(sx0, seg_y, az1 - 6, sx1, seg_y + 60, az1, "detail_atlas",
                                   faces=["+z", "+y"], region_name="concrete_annex")
        # Flat roof slab & dark coping band
        lot_extras.add_box(ax0, 122, az0, ax1, 126, az1, "roof_slate", faces=["+y", "+x", "-x", "+z", "-z"])
        # Horizontal 1950s ribbon windows on annex (some boarded up in 1988!)
        for wy in (24, 74):
            for idx, wx in enumerate((-70, -24, 24, 70)):
                wreg = "win_boarded" if (spec["boarded_windows"] and (idx + wy) % 2 == 0) else "win_annex"
                lot_extras.add_panel_z(wx - 15, wy, wx + 15, wy + 26, az0 - 0.6, "-z", "glass_window", wreg)
            for idx, wz in enumerate((CZ - 416, CZ - 352, CZ - 288, CZ - 224)):
                wreg = "win_boarded" if (spec["boarded_windows"] and idx == 1) else "win_annex"
                lot_extras.add_panel_x(wz - 15, wy, wz + 15, wy + 26, ax1 + 0.6, "+x", "glass_window", wreg)
                lot_extras.add_panel_x(wz - 15, wy, wz + 15, wy + 26, ax0 - 0.6, "-x", "glass_window", wreg)
        coll.add_box(ax0, 0, az0, ax1, 126, az1, "concrete_annex_1951_1999", "exterior")

    # F. 2008 ACCESSIBILITY RAMP at East (+X) Side Entrance (real sloped geometry + handrails + stepped collision!)
    if spec["has_ramp"]:
        # Starts at plaza level (y=2) at z = CZ+126, climbs gently North along x in [194, 212] up to East landing (y=14) at z = CZ+30!
        rx0, rx1 = 194.0, 212.0
        rz_bot, rz_top = CZ + 126.0, CZ + 30.0
        # Sloped concrete deck surface
        lot_extras.add_planar_quad_art((rx0, Y_GROUND, rz_bot), (rx1, Y_GROUND, rz_bot), (rx1, Y_FL1, rz_top), (rx0, Y_FL1, rz_top), "ground_pavement")
        # Side skirt walls under ramp
        lot_extras.add_planar_tri_art((rx1, Y_GROUND, rz_bot), (rx1, Y_GROUND, rz_top), (rx1, Y_FL1, rz_top), "stone_rusticated")
        lot_extras.add_planar_tri_art((rx0, Y_GROUND, rz_top), (rx0, Y_GROUND, rz_bot), (rx0, Y_FL1, rz_top), "stone_rusticated")
        # Galvanized steel handrails & posts along outer edge (rx1 - 1)
        for p_i in range(5):
            t = p_i / 4.0
            pz = rz_bot + t * (rz_top - rz_bot)
            py = Y_GROUND + t * (Y_FL1 - Y_GROUND)
            lot_extras.add_box(rx1 - 2, py, pz - 1, rx1, py + 12, pz + 1, "detail_atlas", region_name="dark_iron")
        for r_i in range(2):
            ta, tb = r_i * 0.5, (r_i + 1) * 0.5
            za = rz_bot + ta * (rz_top - rz_bot)
            zb = rz_bot + tb * (rz_top - rz_bot)
            ya = Y_GROUND + ta * (Y_FL1 - Y_GROUND)
            yb = Y_GROUND + tb * (Y_FL1 - Y_GROUND)
            lot_extras.add_planar_quad_art((rx1 - 1.5, ya + 11, za), (rx1 - 1.5, ya + 12.5, za),
                                           (rx1 - 1.5, yb + 12.5, zb), (rx1 - 1.5, yb + 11, zb), "detail_atlas", "dark_iron")
        # Stepped walkable collision boxes along ramp slope
        for st in range(4):
            t0, t1 = st / 4.0, (st + 1) / 4.0
            z_a = rz_bot + t1 * (rz_top - rz_bot)
            z_b = rz_bot + t0 * (rz_top - rz_bot)
            y_hi = Y_GROUND + t1 * (Y_FL1 - Y_GROUND)
            coll.add_box(rx0, 0, z_a, rx1, y_hi, z_b, f"east_accessibility_ramp_seg_{st + 1}", "steps")

    # ==========================================================================
    # 8. ASSEMBLE REUSABLE MESHES & GLTF SCENE GRAPH NODES
    # ==========================================================================
    win_single_mesh = build_arched_window_mesh("arched_window_single", mat_idx, mat_size, regions, "win_arched", paired=False)
    win_paired_mesh = build_arched_window_mesh("arched_window_paired", mat_idx, mat_size, regions, "win_arched", paired=True)
    win_bricked_mesh = build_arched_window_mesh("arched_window_bricked", mat_idx, mat_size, regions, "win_bricked", paired=False)
    win_boarded_mesh = build_arched_window_mesh("arched_window_boarded", mat_idx, mat_size, regions, "win_boarded", paired=False)
    bench_mesh = build_bench_mesh(mat_idx, mat_size, regions)
    lamp_mesh = build_lamp_mesh(spec["lamp_style"], mat_idx, mat_size, regions)
    hand_hour_mesh = build_clock_hand_mesh("clock_hour_mesh", 6.2, 0.85, mat_idx, mat_size, regions)
    hand_min_mesh = build_clock_hand_mesh("clock_minute_mesh", 9.0, 0.60, mat_idx, mat_size, regions)
    bell_mesh = build_bell_mesh(mat_idx, mat_size, regions)

    meshes_list = []
    mesh_index = {}

    def register_mesh(bucket, extras=None):
        idx = len(meshes_list)
        meshes_list.append((bucket, extras))
        mesh_index[bucket.name] = idx
        return idx

    idx_structure = register_mesh(structure)
    idx_lot_ground = register_mesh(lot_ground)
    idx_memorial = register_mesh(lot_memorial)
    idx_bandstand = register_mesh(lot_bandstand)
    idx_flagpole = register_mesh(lot_flagpole)
    idx_lot_extras = register_mesh(lot_extras) if lot_extras.tri_count() > 0 else None
    idx_collision = register_mesh(coll.bucket, {"collision_only": True})

    floor_mesh_indices = {}
    for fname, fb in floor_buckets.items():
        floor_mesh_indices[fname] = register_mesh(fb)

    idx_win_single = register_mesh(win_single_mesh)
    idx_win_paired = register_mesh(win_paired_mesh)
    idx_win_bricked = register_mesh(win_bricked_mesh)
    idx_win_boarded = register_mesh(win_boarded_mesh)
    idx_bench = register_mesh(bench_mesh)
    idx_lamp = register_mesh(lamp_mesh)
    idx_hand_hour = register_mesh(hand_hour_mesh)
    idx_hand_min = register_mesh(hand_min_mesh)
    idx_bell = register_mesh(bell_mesh)

    nodes = []

    def add_node(name, mesh=None, translation=None, rotation=None, children=None, extras=None):
        n = {"name": name}
        if mesh is not None:
            n["mesh"] = mesh
        if translation is not None:
            n["translation"] = [round(v, 4) for v in translation]
        if rotation is not None:
            n["rotation"] = rotation
        if children is not None:
            n["children"] = children
        if extras is not None:
            n["extras"] = extras
        idx = len(nodes)
        nodes.append(n)
        return idx

    # Root placeholder at index 0
    root_idx = add_node(f"harlan_county_courthouse_{year}", children=[])

    # Structure node
    struct_idx = add_node("structure", mesh=idx_structure)
    nodes[root_idx]["children"].append(struct_idx)

    # Instanced Arched Windows across all 4 facades & 3 storeys (centered on crisp cross-plan bays!)
    win_children = []
    q_south = [0.0, 0.0, 0.0, 1.0]
    q_north = quat_axis_angle(0, 1, 0, math.pi)
    q_east = quat_axis_angle(0, 1, 0, math.pi * 0.5)
    q_west = quat_axis_angle(0, 1, 0, -math.pi * 0.5)

    win_placements = []
    # Ground floor (y = Y_FL1 + 14 = 28), Floor 2 (y = Y_FL2 + 14 = 86), Floor 3 paired (y = Y_FL3 + 14 = 144)
    for fl_idx, wy in enumerate((Y_FL1 + 14, Y_FL2 + 14, Y_FL3 + 14)):
        # South (+Z) pavilion & central block corners
        for wx in (-89, 89):
            win_placements.append((wx, wy, CZ + CB, q_south, fl_idx, "south_corner"))
        if fl_idx >= 1:
            for wx in (-40, 40):
                win_placements.append((wx, wy, CZ + 192, q_south, fl_idx, "south_pav"))
            win_placements.append((0, wy, CZ + 192, q_south, fl_idx, "south_center"))
        # North (-Z) pavilion & corners (skip ground/fl2 north pavilion behind annex in 1962/1988)
        for wx in (-89, 89):
            win_placements.append((wx, wy, CZ - CB, q_north, fl_idx, "north_corner"))
        if not (spec["has_annex"] and fl_idx < 2):
            for wx in (-40, 40):
                win_placements.append((wx, wy, CZ - 192, q_north, fl_idx, "north_pav"))
            if fl_idx >= 1:
                win_placements.append((0, wy, CZ - 192, q_north, fl_idx, "north_center"))
        # East (+X) pavilion & corners
        for wz in (CZ - 89, CZ + 89):
            win_placements.append((CB, wy, wz, q_east, fl_idx, "east_corner"))
        for wz in (CZ - 40, CZ + 40):
            win_placements.append((192, wy, wz, q_east, fl_idx, "east_pav"))
        if fl_idx >= 1:
            win_placements.append((192, wy, CZ, q_east, fl_idx, "east_center"))
        # West (-X) pavilion & corners
        for wz in (CZ - 89, CZ + 89):
            win_placements.append((-CB, wy, wz, q_west, fl_idx, "west_corner"))
        for wz in (CZ - 40, CZ + 40):
            win_placements.append((-192, wy, wz, q_west, fl_idx, "west_pav"))
        if fl_idx >= 1:
            win_placements.append((-192, wy, CZ, q_west, fl_idx, "west_center"))

    for wi, (wx, wy, wz, wq, fl_idx, tag) in enumerate(win_placements):
        if fl_idx == 2:
            m_idx = idx_win_paired
        elif fl_idx == 0 and spec["boarded_windows"] and wi % 3 == 0:
            m_idx = idx_win_boarded
        elif fl_idx == 0 and spec["bricked_ground_windows"]:
            m_idx = idx_win_bricked
        elif fl_idx == 1 and spec["boarded_windows"] and wi % 5 == 0:
            m_idx = idx_win_boarded
        else:
            m_idx = idx_win_single
        win_children.append(add_node(f"win_{tag}_{fl_idx + 1}_{wi}", mesh=m_idx, translation=[wx * U, wy * U, wz * U], rotation=wq))

    windows_grp = add_node("arched_windows", children=win_children)
    nodes[root_idx]["children"].append(windows_grp)

    # Named Interior Floor Meshes (floor_<room>)
    floor_nodes = []
    for fname, f_midx in floor_mesh_indices.items():
        floor_nodes.append(add_node(fname, mesh=f_midx))
    interior_grp = add_node("interior", children=floor_nodes)
    nodes[root_idx]["children"].append(interior_grp)

    # Clock Hands (pivoted at exact dial center, rotated to 10:10 or 4:17 in 1988!) & Bell
    if spec["clock_time"] == "04:17":
        # 4:17 -> hour = 128.5 deg CW, minute = 102.0 deg CW
        hour_cw_rad = math.radians(128.5)
        min_cw_rad = math.radians(102.0)
    else:
        # 10:10 -> hour = 305.0 deg CW, minute = 60.0 deg CW
        hour_cw_rad = math.radians(305.0)
        min_cw_rad = math.radians(60.0)

    q_hour_local = quat_axis_angle(0, 0, 1, -hour_cw_rad)
    q_min_local = quat_axis_angle(0, 0, 1, -min_cw_rad)

    clock_hand_nodes_info = {}
    clock_faces = [
        ("south", "", [0.0, Y_TOWER_CLK * U, (CZ + TW + 1.5) * U], q_south),
        ("north", "_north", [0.0, Y_TOWER_CLK * U, (CZ - TW - 1.5) * U], q_north),
        ("east", "_east", [(TW + 1.5) * U, Y_TOWER_CLK * U, CZ * U], q_east),
        ("west", "_west", [(-TW - 1.5) * U, Y_TOWER_CLK * U, CZ * U], q_west),
    ]
    for face_lbl, suffix, pos_m, base_q in clock_faces:
        h_name = f"clock_hour{suffix}"
        m_name = f"clock_minute{suffix}"
        h_q = quat_mul(base_q, q_hour_local)
        m_q = quat_mul(base_q, q_min_local)
        # Minute hand sits 0.04 m further out than hour hand to avoid z-fighting
        nrm_out = {"south": (0, 0, 0.04), "north": (0, 0, -0.04), "east": (0.04, 0, 0), "west": (-0.04, 0, 0)}[face_lbl]
        m_pos = [pos_m[0] + nrm_out[0], pos_m[1], pos_m[2] + nrm_out[2]]
        h_idx = add_node(h_name, mesh=idx_hand_hour, translation=pos_m, rotation=h_q,
                         extras={"clock_face": face_lbl, "time": spec["clock_time"], "active": spec["clock_active"]})
        m_idx = add_node(m_name, mesh=idx_hand_min, translation=m_pos, rotation=m_q,
                         extras={"clock_face": face_lbl, "time": spec["clock_time"], "active": spec["clock_active"]})
        nodes[root_idx]["children"].extend([h_idx, m_idx])
        clock_hand_nodes_info[h_name] = {"position_m": [round(v, 4) for v in pos_m], "time": spec["clock_time"]}
        clock_hand_nodes_info[m_name] = {"position_m": [round(v, 4) for v in m_pos], "time": spec["clock_time"]}

    bell_pos = [0.0, 396.0 * U, CZ * U]
    bell_idx = add_node("bell", mesh=idx_bell, translation=bell_pos, extras={"belfry_y_m": round(bell_pos[1], 4)})
    nodes[root_idx]["children"].append(bell_idx)

    # Exterior & Interior Door Nodes (IDENTICAL across all 4 states!)
    door_nodes_info = {}
    ext_doors = [
        ("door_main", [0.0, Y_FL1 * U, (CZ + 192.0) * U], q_south, "+Z"),
        ("door_north", [0.0, Y_FL1 * U, (CZ - 192.0) * U], q_north, "-Z"),
        ("door_east", [192.0 * U, Y_FL1 * U, CZ * U], q_east, "+X"),
        ("door_west", [-192.0 * U, Y_FL1 * U, CZ * U], q_west, "-X"),
    ]
    for dname, dpos, dq, dfacing in ext_doors:
        didx = add_node(dname, translation=dpos, rotation=dq, extras={"facing": dfacing})
        nodes[root_idx]["children"].append(didx)
        door_nodes_info[dname] = {"position_m": [round(v, 4) for v in dpos], "facing": dfacing}

    # Interior Room Doorways (door_room_* facing INTO each room!)
    int_doors = [
        ("door_room_clerk", [-59.0 * U, Y_FL1 * U, (CZ + 90.0) * U], q_west, "-X"),
        ("door_room_assessor", [59.0 * U, Y_FL1 * U, (CZ + 90.0) * U], q_east, "+X"),
        ("door_room_treasurer", [-59.0 * U, Y_FL1 * U, (CZ - 90.0) * U], q_west, "-X"),
        ("door_room_recorder", [59.0 * U, Y_FL1 * U, (CZ - 90.0) * U], q_east, "+X"),
        ("door_room_courtroom", [0.0, Y_FL2 * U, (CZ + 55.0) * U], q_south, "+Z"),
        ("door_room_chambers", [-63.0 * U, Y_FL2 * U, CZ * U], q_west, "-X"),
        ("door_room_jury", [63.0 * U, Y_FL2 * U, CZ * U], q_east, "+X"),
        ("door_room_law_library", [0.0, Y_FL2 * U, (CZ - 57.0) * U], q_north, "-Z"),
        ("door_room_records", [-63.0 * U, Y_FL3 * U, CZ * U], q_west, "-X"),
        ("door_room_archives", [63.0 * U, Y_FL3 * U, CZ * U], q_east, "+X"),
        ("door_room_commission", [0.0, Y_FL3 * U, (CZ - 57.0) * U], q_north, "-Z"),
    ]
    for dname, dpos, dq, dfacing in int_doors:
        didx = add_node(dname, translation=dpos, rotation=dq, extras={"facing_into_room": dfacing})
        nodes[root_idx]["children"].append(didx)
        door_nodes_info[dname] = {"position_m": [round(v, 4) for v in dpos], "facing_into_room": dfacing}

    # LOT GROUP (ground, memorial, bandstand, flagpole, extras, 8 benches, 12 lamps, 16 tree_* empties!)
    lot_children = [
        add_node("lot_ground", mesh=idx_lot_ground),
        add_node("memorial", mesh=idx_memorial),
        add_node("bandstand", mesh=idx_bandstand),
        add_node("flagpole", mesh=idx_flagpole),
    ]
    if idx_lot_extras is not None:
        lot_children.append(add_node("lot_extras", mesh=idx_lot_extras))

    # 8 Instanced Iron & Wood Park Benches along the cardinal & diagonal paths (+ collision!)
    bench_coords = [
        (-40, CZ + 340, q_east), (40, CZ + 340, q_west),
        (-40, CZ + 410, q_east), (40, CZ + 410, q_west),
        (-340, CZ - 40, q_south), (340, CZ - 40, q_south),
        (-340, CZ + 40, q_north), (340, CZ + 40, q_north),
    ]
    bench_nodes = []
    for bi, (bx_c, bz_c, bq) in enumerate(bench_coords):
        bench_nodes.append(add_node(f"bench_{bi + 1:02d}", mesh=idx_bench, translation=[bx_c * U, Y_GROUND * U, bz_c * U], rotation=bq))
        coll.add_box(bx_c - 10, 0, bz_c - 10, bx_c + 10, 15, bz_c + 10, f"park_bench_{bi + 1:02d}", "square")
    lot_children.append(add_node("benches", children=bench_nodes))

    # 12 Instanced Era-Specific Lamp Posts around the paths & corners (+ collision!)
    lamp_coords = [
        (-36, -68, q_east), (36, -68, q_west),
        (-72, CZ + 278, q_east), (72, CZ + 278, q_west),
        (-440, CZ - 36, q_south), (-440, CZ + 36, q_north),
        (440, CZ - 36, q_south), (440, CZ + 36, q_north),
        (-360, CZ + 360, q_south), (360, CZ + 360, q_south),
        (-360, CZ - 360, q_north), (360, CZ - 360, q_north),
    ]
    lamp_nodes = []
    for li, (lx_c, lz_c, lq) in enumerate(lamp_coords):
        lamp_nodes.append(add_node(f"lamp_{li + 1:02d}", mesh=idx_lamp, translation=[lx_c * U, Y_GROUND * U, lz_c * U], rotation=lq))
        coll.add_box(lx_c - 4, 0, lz_c - 4, lx_c + 4, 52, lz_c + 4, f"lamp_post_{li + 1:02d}", "square")
    lot_children.append(add_node("lamps", children=lamp_nodes))

    # 16 Shade Tree Position Empties (tree_01 .. tree_16 — game plants its own trees!)
    tree_coords = [
        (-380, CZ + 180), (-380, CZ + 330), (-150, CZ + 390), (-310, CZ + 400),  # SW quadrant
        (380, CZ + 180), (380, CZ + 330), (150, CZ + 390), (310, CZ + 400),      # SE quadrant
        (-380, CZ - 180), (-380, CZ - 340), (-220, CZ - 390), (-310, CZ - 260),  # NW quadrant
        (380, CZ - 180), (380, CZ - 340), (220, CZ - 390), (310, CZ - 260),      # NE quadrant
    ]
    tree_info = []
    tree_nodes = []
    for ti, (tx, tz) in enumerate(tree_coords):
        tname = f"tree_{ti + 1:02d}"
        tpos = [round(tx * U, 4), round(Y_GROUND * U, 4), round(tz * U, 4)]
        tree_nodes.append(add_node(tname, translation=tpos, extras={"tree_slot": True}))
        tree_info.append({"name": tname, "position_m": tpos})
    lot_children.append(add_node("trees", children=tree_nodes))

    lot_idx = add_node("lot", children=lot_children)
    nodes[root_idx]["children"].append(lot_idx)

    # Collision node (invisible collision_only=true)
    coll_idx = add_node("collision", mesh=idx_collision, extras={"collision_only": True})
    nodes[root_idx]["children"].append(coll_idx)

    out_path = R / spec["file"]
    pack_glb(out_path, mat_defs, meshes_list, nodes)

    # Compute exact triangle breakdown per part (counting ALL visible instances!)
    mesh_tri_counts = [b.tri_count() for b, _ in meshes_list]
    part_tris = {
        "structure": structure.tri_count(),
        "arched_windows_instanced": sum(mesh_tri_counts[nodes[ni]["mesh"]] for ni in win_children),
        "interior_floors": sum(fb.tri_count() for fb in floor_buckets.values()),
        "clock_hands_and_bell": 4 * (hand_hour_mesh.tri_count() + hand_min_mesh.tri_count()) + bell_mesh.tri_count(),
        "lot_ground": lot_ground.tri_count(),
        "memorial": lot_memorial.tri_count(),
        "bandstand": lot_bandstand.tri_count(),
        "flagpole": lot_flagpole.tri_count(),
        "lot_extras": lot_extras.tri_count(),
        "benches_instanced": len(bench_nodes) * bench_mesh.tri_count(),
        "lamps_instanced": len(lamp_nodes) * lamp_mesh.tri_count(),
    }
    vis_total = sum(part_tris.values())
    coll_total = coll.bucket.tri_count()
    print(f"Wrote {spec['file']} ({out_path.stat().st_size} bytes) | vis={vis_total} tris | coll={coll_total} tris")

    return {
        "year": year,
        "id": spec["id"],
        "era_years": spec["era_years"],
        "file": spec["file"],
        "title": spec["title"],
        "clock_active": spec["clock_active"],
        "clock_time": spec["clock_time"],
        "materials": len(mat_defs),
        "visible_triangles": vis_total,
        "collision_triangles": coll_total,
        "triangles_per_part": part_tris,
        "footprint_building_m": [round(384 * U, 4), round(384 * U, 4)],
        "footprint_lot_m": [round(1024 * U, 4), round(1024 * U, 4)],
        "height_peak_m": round(Y_FINIAL_TOP * U, 4),
        "doors": door_nodes_info,
        "rooms": sorted(floor_buckets.keys()),
        "clock_hands": clock_hand_nodes_info,
        "bell": {"node": "bell", "position_m": [round(v, 4) for v in bell_pos]},
        "trees": tree_info,
        "collision": {
            "count": len(coll.boxes),
            "boxes_blender_z_up": coll.boxes,
        },
    }


# ==============================================================================
# DISTANT LOD SILHOUETTE BUILDER (courthouse-far.glb, STRICTLY < 400 TRIANGLES!)
# ==============================================================================
def build_far_lod():
    """
    Landmark distant silhouette in the exact same pixel textures, dimensions, and origin:
    cross-shaped wings, rusticated stone base, red brick upper mass, steep hipped slate roofs,
    central clock tower shaft, 4 lit clock faces, belfry band, copper-green pyramidal spire + finial,
    and town-square lawn/path plinth — strictly under 400 triangles!
    """
    spec = STATES[3]  # Restored classic landmark look
    mat_defs = get_material_defs(spec)
    mat_idx = {m["name"]: i for i, m in enumerate(mat_defs)}
    mat_size = {m["name"]: read_png_size(TEX_DIR / m["file"]) for m in mat_defs}
    regions = {}
    regions.update(ART_MANIFEST["regions"]["detail_clean"])
    regions.update(ART_MANIFEST["regions"]["signs"])

    far = MeshBucket("courthouse_far", mat_idx, mat_size, regions)

    # Square lawn + cross path base (6 tris)
    far.add_panel_y(-512, -1024, 512, 0, Y_GROUND, "+y", "ground_lawn")
    far.add_panel_y(-32, -1024, 32, 0, Y_GROUND + 0.3, "+y", "ground_pavement")
    far.add_panel_y(-512, CZ - 32, 512, CZ + 32, Y_GROUND + 0.3, "+y", "ground_pavement")

    # Cross-shaped courthouse massing (CB=116 central block + PW=62 projecting wings!)
    CB, PW = 116.0, 62.0
    for y0, y1, mat in ((Y_GROUND, Y_FL2, "stone_rusticated"), (Y_FL2, Y_EAVE, "brick_red")):
        far.add_box(-CB, y0, CZ - CB, CB, y1, CZ + CB, mat, faces=["+x", "-x", "+z", "-z"])
        far.add_box(-PW, y0, CZ - 192, PW, y1, CZ + 192, mat, faces=["+x", "-x", "+z", "-z"])
        far.add_box(-192, y0, CZ - PW, 192, y1, CZ + PW, mat, faces=["+x", "-x", "+z", "-z"])

    # Central steep hipped roof + 4 projecting gabled pavilion roofs meeting in crisp valleys!
    far.add_hip_roof(-122, 122, CZ - 122, CZ + 122, Y_EAVE, 246, 68, 68, "roof_slate")
    GE, GP = PW + 4.0, 244.0
    for s in (1, -1):
        za, zb = min(CZ + s * 62.0, CZ + s * 196.0), max(CZ + s * 62.0, CZ + s * 196.0)
        zw = CZ + s * 192.0
        far.add_planar_quad_art((-GE, Y_EAVE, zb), (-GE, Y_EAVE, za), (0, GP, za), (0, GP, zb), "roof_slate")
        far.add_planar_quad_art((GE, Y_EAVE, za), (GE, Y_EAVE, zb), (0, GP, zb), (0, GP, za), "roof_slate")
        if s > 0:
            far.add_planar_tri_art((-PW, Y_EAVE, zw), (PW, Y_EAVE, zw), (0, GP - 2, zw), "brick_red")
        else:
            far.add_planar_tri_art((PW, Y_EAVE, zw), (-PW, Y_EAVE, zw), (0, GP - 2, zw), "brick_red")
        xa, xb = min(s * 62.0, s * 196.0), max(s * 62.0, s * 196.0)
        xw = s * 192.0
        far.add_planar_quad_art((xa, Y_EAVE, CZ - GE), (xb, Y_EAVE, CZ - GE), (xb, GP, CZ), (xa, GP, CZ), "roof_slate")
        far.add_planar_quad_art((xb, Y_EAVE, CZ + GE), (xa, Y_EAVE, CZ + GE), (xa, GP, CZ), (xb, GP, CZ), "roof_slate")
        if s > 0:
            far.add_planar_tri_art((xw, Y_EAVE, CZ + PW), (xw, Y_EAVE, CZ - PW), (xw, GP - 2, CZ), "brick_red")
        else:
            far.add_planar_tri_art((xw, Y_EAVE, CZ - PW), (xw, Y_EAVE, CZ + PW), (xw, GP - 2, CZ), "brick_red")

    # Visible square tower base + clock shaft + belfry + 4 lit clock faces + copper spire + finial
    TW_BASE, TW = 54.0, 44.0
    far.add_box(-TW_BASE, 238, CZ - TW_BASE, TW_BASE, 282, CZ + TW_BASE, "brick_red", faces=["+x", "-x", "+z", "-z", "+y"])
    far.add_box(-TW, 282, CZ - TW, TW, Y_BELFRY_BOT, CZ + TW, "brick_red", faces=["+x", "-x", "+z", "-z"])
    far.add_box(-38, Y_BELFRY_BOT, CZ - 38, 38, Y_BELFRY_TOP, CZ + 38, "stone_rusticated", faces=["+x", "-x", "+z", "-z"])
    cr = 12.0
    far.add_panel_z(-cr, Y_TOWER_CLK - cr, cr, Y_TOWER_CLK + cr, CZ + TW + 0.8, "+z", "glow_clock_south")
    far.add_panel_z(-cr, Y_TOWER_CLK - cr, cr, Y_TOWER_CLK + cr, CZ - TW - 0.8, "-z", "glow_clock_north")
    far.add_panel_x(CZ - cr, Y_TOWER_CLK - cr, CZ + cr, Y_TOWER_CLK + cr, TW + 0.8, "+x", "glow_clock_east")
    far.add_panel_x(CZ - cr, Y_TOWER_CLK - cr, CZ + cr, Y_TOWER_CLK + cr, -TW - 0.8, "-x", "glow_clock_west")
    far.add_cone(0, Y_BELFRY_TOP, Y_SPIRE_TOP, CZ, 42.0 * math.sqrt(2.0), 4, "copper_spire", cap_bot=False)
    far.add_cone(0, Y_SPIRE_TOP, Y_FINIAL_TOP, CZ, 2.2, 4, "detail_atlas", region_name="dark_iron", cap_bot=False)

    # Tiny distant silhouette hints of bandstand roof & memorial pedestal
    far.add_cone(-240, 20, 84, CZ + 260, 44.0, 6, "roof_slate", cap_bot=False)
    far.add_box(216, Y_GROUND, CZ + 244, 248, 52, CZ + 276, "stone_rusticated", faces=["+x", "-x", "+z", "-z", "+y"])

    nodes = [
        {"name": "harlan_county_courthouse_far", "children": [1]},
        {"name": "structure", "mesh": 0},
    ]
    out_path = R / "courthouse-far.glb"
    pack_glb(out_path, mat_defs, [(far, None)], nodes)
    tris = far.tri_count()
    print(f"Wrote courthouse-far.glb ({out_path.stat().st_size} bytes) | vis={tris} tris (budget < 400)")
    assert tris < 400, f"courthouse-far.glb exceeded 400 tris: {tris}"
    return {"file": "courthouse-far.glb", "visible_triangles": tris, "height_peak_m": round(Y_FINIAL_TOP * U, 4)}


def main():
    states_info = [build_near_state(st) for st in STATES]
    far_info = build_far_lod()

    model_info = {
        "name": "Harlan County Courthouse and Square",
        "built_year": 1894,
        "architecture": "Three-storey Romanesque Revival county courthouse on a full town-square block",
        "origin": "midpoint of +Z front curb edge (0, 0, 0); +Z main entrance under clock tower; Y up",
        "texel_size_m": TEXEL,
        "art_pixel_size_m": U,
        "shared_swatches": list(ART_MANIFEST["shared_swatches"].keys()),
        "far_lod": far_info,
        "states": states_info,
    }
    (R / "model-info.json").write_text(json.dumps(model_info, indent=2) + "\n", encoding="utf-8")
    print("Wrote model-info.json covering all 4 states + courthouse-far.glb.")


if __name__ == "__main__":
    main()
