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
    Real 8-segment semicircular stone voussoir arch + sill + jambs + recessed glazing!
    """
    b = MeshBucket(name, mat_idx, mat_size, regions)
    if not paired:
        # Single arched window: opening 18 wide x 34 tall (arch spring at y=25, radius=9 -> top=34)
        # Projecting stone sill
        b.add_box(-12, -2, -2, 12, 1, 3.2, "stone_rusticated")
        # Stone side jambs
        b.add_box(-12, 1, -2, -9, 25, 2.2, "stone_rusticated")
        b.add_box(9, 1, -2, 12, 25, 2.2, "stone_rusticated")
        # Real 8-segment semicircular stone arch ring (r_in=9, r_out=12.5)
        b.add_arch_ring_z(0, 25, -2.0, 2.4, 9.0, 12.5, 8, "stone_rusticated")
        # Recessed window glass/infill pane (rectangular lower + arched fanlight upper!)
        b.add_panel_z(-9, 1, 9, 25, -0.6, "+z", "glass_window", glass_region)
        # Semicircular upper arch glazing fan (8 triangles matching arch geometry!)
        # Mapped cleanly inside the top portion of the glass region
        for i in range(8):
            a0 = math.pi * i / 8
            a1 = math.pi * (i + 1) / 8
            p0 = (0, 25, -0.6)
            p1 = (math.cos(a0) * 9.0, 25 + math.sin(a0) * 9.0, -0.6)
            p2 = (math.cos(a1) * 9.0, 25 + math.sin(a1) * 9.0, -0.6)
            b.add_planar_tri_art(p0, p1, p2, "glass_window", glass_region)
    else:
        # Paired 3rd-floor Romanesque arched windows: two 12-wide arches sharing a center colonnette!
        b.add_box(-18, -2, -2, 18, 1, 3.2, "stone_rusticated")
        b.add_box(-18, 1, -2, -15, 22, 2.2, "stone_rusticated")
        b.add_box(-2, 1, -2, 2, 22, 2.4, "stone_rusticated")  # center stone colonnette
        b.add_box(15, 1, -2, 18, 22, 2.2, "stone_rusticated")
        for ox in (-8.5, 8.5):
            b.add_arch_ring_z(ox, 22, -2.0, 2.4, 6.5, 9.5, 8, "stone_rusticated")
            b.add_panel_z(ox - 6.5, 1, ox + 6.5, 22, -0.6, "+z", "glass_window", glass_region)
            for i in range(8):
                a0 = math.pi * i / 8
                a1 = math.pi * (i + 1) / 8
                b.add_planar_tri_art(
                    (ox, 22, -0.6),
                    (ox + math.cos(a0) * 6.5, 22 + math.sin(a0) * 6.5, -0.6),
                    (ox + math.cos(a1) * 6.5, 22 + math.sin(a1) * 6.5, -0.6),
                    "glass_window",
                    glass_region,
                )
    return b


def build_bench_mesh(mat_idx, mat_size, regions):
    """Park bench (22 art px = 1.71 m long), centered at X=0, Z=0, base at Y=0, facing +Z."""
    b = MeshBucket("bench_inst", mat_idx, mat_size, regions)
    # Two cast-iron leg frames
    for lx in (-9, 7):
        b.add_box(lx, 0, -4, lx + 2, 7, 4, "detail_atlas", region_name="dark_iron")
        b.add_box(lx, 7, -4.5, lx + 2, 13, -3, "detail_atlas", region_name="dark_iron")
    # Wood seat & backrest
    b.add_box(-11, 6.5, -3.5, 11, 8, 3.5, "interior_floor")
    b.add_box(-11, 9.5, -4.2, 11, 13.5, -2.8, "interior_floor")
    return b


def build_lamp_mesh(style, mat_idx, mat_size, regions):
    """Era-specific square lamp post (base at Y=0). ONLY the tiny lantern/lens uses glow_lamps!"""
    b = MeshBucket(f"lamp_{style}_inst", mat_idx, mat_size, regions)
    if style == "gas":
        # 1894-1950 ornate cast-iron gas lamp post (height 44 art px = 3.41 m)
        b.add_box(-3, 0, -3, 3, 6, 3, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.5, 6, -1.5, 1.5, 36, 1.5, "detail_atlas", region_name="dark_iron")
        b.add_box(-3.5, 36, -3.5, 3.5, 37.5, 3.5, "detail_atlas", region_name="dark_iron")
        b.add_box(-3, 37.5, -3, 3, 44, 3, "glow_lamps", faces=["+x", "-x", "+z", "-z"], region_name="lamp_gas")
        b.add_cone(0, 44, 48, 0, 4.5, 4, "detail_atlas", region_name="dark_iron", cap_bot=True)
    elif style == "cobra":
        # 1951-1999 taller galvanized cobra-head electric street lamp (height 68 art px = 5.28 m)
        b.add_box(-2.5, 0, -2.5, 2.5, 6, 2.5, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.5, 6, -1.5, 1.5, 66, 1.5, "detail_atlas", region_name="dark_iron")
        # Cantilever arm extending +Z
        b.add_box(-1.2, 64, 0, 1.2, 66.5, 16, "detail_atlas", region_name="dark_iron")
        # Cobra hood + downward glowing lens (ny = -1 spawns Filament light!)
        b.add_box(-3.5, 64, 14, 3.5, 67, 22, "detail_atlas", faces=["+y", "+x", "-x", "+z", "-z"], region_name="dark_iron")
        b.add_panel_y(-3, 14.5, 3, 21.5, 63.9, "-y", "glow_lamps", "lamp_cobra")
    else:
        # 2000+ restored heritage warm LED acorn-globe post (height 48 art px = 3.72 m)
        b.add_box(-3, 0, -3, 3, 8, 3, "detail_atlas", region_name="dark_iron")
        b.add_box(-1.5, 8, -1.5, 1.5, 40, 1.5, "detail_atlas", region_name="dark_iron")
        b.add_box(-3.2, 40, -3.2, 3.2, 47.5, 3.2, "glow_lamps", faces=["+x", "-x", "+z", "-z", "+y"], region_name="lamp_acorn")
        b.add_box(-1.2, 47.5, -1.2, 1.2, 50, 1.2, "detail_atlas", region_name="dark_iron")
    return b


def build_clock_hand_mesh(name, length_art, half_w_art, mat_idx, mat_size, regions):
    """Clock hand pointing straight UP (+Y = 12 o'clock) pivoted at (0, 0, 0)."""
    b = MeshBucket(name, mat_idx, mat_size, regions)
    b.add_box(-half_w_art, -3.0, -0.5, half_w_art, length_art, 0.7, "detail_atlas", region_name="dark_iron")
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
    # 2. COURTHOUSE EXTERIOR SHELL (Cross plan 384 x 384 art px = 29.80 x 29.80 m)
    #    Central block: dx, dz in [-128, 128]
    #    4 projecting pavilions: width 128 ([-64, 64]), projecting out to +/-192
    # ==========================================================================
    # Raised rusticated stone foundation plinth under entire cross footprint (y = 2..14)
    structure.add_box(-128, Y_GROUND, CZ - 128, 128, Y_FL1, CZ + 128, "stone_rusticated")
    structure.add_box(-64, Y_GROUND, CZ + 128, 64, Y_FL1, CZ + 192, "stone_rusticated")
    structure.add_box(-64, Y_GROUND, CZ - 192, 64, Y_FL1, CZ - 128, "stone_rusticated")
    structure.add_box(128, Y_GROUND, CZ - 64, 192, Y_FL1, CZ + 64, "stone_rusticated")
    structure.add_box(-192, Y_GROUND, CZ - 64, -128, Y_FL1, CZ + 64, "stone_rusticated")

    # Helper to build a 3-storey Romanesque wall segment with:
    # - Rusticated stone base (Y_FL1 .. Y_FL2)
    # - Projecting limestone stringcourses at Floor 2 (70..74), Floor 3 (128..132), and cornice (184..190)
    # - LimeZu pressed red brick upper storeys (Y_FL2 .. Y_EAVE)
    # - Full-height collision box (y = 0 .. Y_EAVE)
    def add_ext_wall(x0, z0, x1, z1, name, has_door_gap=None):
        """
        has_door_gap: None or ("z", gx0, gx1, door_top_y) or ("x", gz0, gz1, door_top_y)
        """
        if has_door_gap is None:
            structure.add_box(x0, Y_FL1, z0, x1, Y_FL2, z1, "stone_rusticated")
            structure.add_box(x0, Y_FL2, z0, x1, Y_EAVE, z1, "brick_red")
            # Interior face gets warm plaster + wainscot!
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
    DOOR_TOP = Y_FL1 + 36.0  # Tall arched double doorway (2.79 m tall, 28 art px = 2.17 m wide)

    # 4 Pavilion End Walls (with wide open entrance doorways on ground floor!)
    add_ext_wall(-64, CZ + 192 - T, 64, CZ + 192, "wall_pavilion_south", ("z", -14, 14, DOOR_TOP))
    add_ext_wall(-64, CZ - 192, 64, CZ - 192 + T, "wall_pavilion_north", ("z", -14, 14, DOOR_TOP))
    add_ext_wall(192 - T, CZ - 64, 192, CZ + 64, "wall_pavilion_east", ("x", CZ - 14, CZ + 14, DOOR_TOP))
    add_ext_wall(-192, CZ - 64, -192 + T, CZ + 64, "wall_pavilion_west", ("x", CZ - 14, CZ + 14, DOOR_TOP))

    # 8 Pavilion Cheek Walls (flanking the 4 projecting wings)
    add_ext_wall(-64, CZ + 128, -64 + T, CZ + 192 - T, "wall_pav_s_west")
    add_ext_wall(64 - T, CZ + 128, 64, CZ + 192 - T, "wall_pav_s_east")
    add_ext_wall(-64, CZ - 192 + T, -64 + T, CZ - 128, "wall_pav_n_west")
    add_ext_wall(64 - T, CZ - 192 + T, 64, CZ - 128, "wall_pav_n_east")
    add_ext_wall(128, CZ + 64 - T, 192 - T, CZ + 64, "wall_pav_e_south")
    add_ext_wall(128, CZ - 64, 192 - T, CZ - 64 + T, "wall_pav_e_north")
    add_ext_wall(-192 + T, CZ + 64 - T, -128, CZ + 64, "wall_pav_w_south")
    add_ext_wall(-192 + T, CZ - 64, -128, CZ - 64 + T, "wall_pav_w_north")

    # 8 Central Block Corner Walls (filling the 4 re-entrant corners between pavilions)
    for sx in (-1, 1):
        for sz in (-1, 1):
            x_a = sx * 64 if sx > 0 else -128
            x_b = 128 if sx > 0 else -64
            z_wall0 = CZ + (128 - T if sz > 0 else -128)
            z_wall1 = CZ + (128 if sz > 0 else -128 + T)
            add_ext_wall(x_a, z_wall0, x_b, z_wall1, f"wall_corner_z_{sx}_{sz}")

            z_a = CZ + (64 if sz > 0 else -128 + T)
            z_b = CZ + (128 - T if sz > 0 else -64)
            x_wall0 = (128 - T) if sx > 0 else -128
            x_wall1 = 128 if sx > 0 else (-128 + T)
            add_ext_wall(x_wall0, z_a, x_wall1, z_b, f"wall_corner_x_{sx}_{sz}")

    # Projecting Hollow Exterior-Only Stone Stringcourse Belt Bands at Floor 2, Floor 3, and Eave Cornice!
    # (Strictly exterior perimeter strips so the 3-storey rotunda & double-height courtroom stay 100% open!)
    for by0, by1, out in ((Y_FL2 - 2, Y_FL2 + 2, 2.0), (Y_FL3 - 2, Y_FL3 + 2, 2.0), (Y_EAVE - 4, Y_EAVE + 2, 4.0)):
        # 4 Pavilion End Bands
        structure.add_box(-64 - out, by0, CZ + 192, 64 + out, by1, CZ + 192 + out, "stone_rusticated", faces=["+z", "+y", "-y", "+x", "-x"])
        structure.add_box(-64 - out, by0, CZ - 192 - out, 64 + out, by1, CZ - 192, "stone_rusticated", faces=["-z", "+y", "-y", "+x", "-x"])
        structure.add_box(192, by0, CZ - 64 - out, 192 + out, by1, CZ + 64 + out, "stone_rusticated", faces=["+x", "+y", "-y", "+z", "-z"])
        structure.add_box(-192 - out, by0, CZ - 64 - out, -192, by1, CZ + 64 + out, "stone_rusticated", faces=["-x", "+y", "-y", "+z", "-z"])
        # 8 Pavilion Cheek Bands
        structure.add_box(-64 - out, by0, CZ + 128, -64, by1, CZ + 192, "stone_rusticated", faces=["-x", "+y", "-y"])
        structure.add_box(64, by0, CZ + 128, 64 + out, by1, CZ + 192, "stone_rusticated", faces=["+x", "+y", "-y"])
        structure.add_box(-64 - out, by0, CZ - 192, -64, by1, CZ - 128, "stone_rusticated", faces=["-x", "+y", "-y"])
        structure.add_box(64, by0, CZ - 192, 64 + out, by1, CZ - 128, "stone_rusticated", faces=["+x", "+y", "-y"])
        structure.add_box(128, by0, CZ + 64, 192, by1, CZ + 64 + out, "stone_rusticated", faces=["+z", "+y", "-y"])
        structure.add_box(128, by0, CZ - 64 - out, 192, by1, CZ - 64, "stone_rusticated", faces=["-z", "+y", "-y"])
        structure.add_box(-192, by0, CZ + 64, -128, by1, CZ + 64 + out, "stone_rusticated", faces=["+z", "+y", "-y"])
        structure.add_box(-192, by0, CZ - 64 - out, -128, by1, CZ - 64, "stone_rusticated", faces=["-z", "+y", "-y"])
        # 8 Central Block Re-entrant Corner Bands
        for sx in (-1, 1):
            for sz in (-1, 1):
                xa, xb = (64, 128 + out) if sx > 0 else (-128 - out, -64)
                za = CZ + (128 if sz > 0 else -128 - out)
                zb = CZ + (128 + out if sz > 0 else -128)
                zf = "+z" if sz > 0 else "-z"
                structure.add_box(xa, by0, za, xb, by1, zb, "stone_rusticated", faces=[zf, "+y", "-y"])
                za2 = CZ + (64 if sz > 0 else -128)
                zb2 = CZ + (128 if sz > 0 else -64)
                xa2 = 128 if sx > 0 else -128 - out
                xb2 = 128 + out if sx > 0 else -128
                xf = "+x" if sx > 0 else "-x"
                structure.add_box(xa2, by0, za2, xb2, by1, zb2, "stone_rusticated", faces=[xf, "+y", "-y"])

    # ==========================================================================
    # 3. STEEP HIPPED SLATE ROOFS + 4 GABLED WING DORMERS
    # ==========================================================================
    # Central square hip roof (eave 188 -> ridge 250)
    structure.add_hip_roof(-132, 132, CZ - 132, CZ + 132, Y_EAVE, Y_RIDGE, 80, 80, "roof_slate")
    # Four projecting wing steep hip roofs
    structure.add_hip_roof(-68, 68, CZ + 112, CZ + 196, Y_EAVE, Y_RIDGE - 6, 48, 42, "roof_slate")  # South
    structure.add_hip_roof(-68, 68, CZ - 196, CZ - 112, Y_EAVE, Y_RIDGE - 6, 48, 42, "roof_slate")  # North
    structure.add_hip_roof(112, 196, CZ - 68, CZ + 68, Y_EAVE, Y_RIDGE - 6, 42, 48, "roof_slate")   # East
    structure.add_hip_roof(-196, -112, CZ - 68, CZ + 68, Y_EAVE, Y_RIDGE - 6, 42, 48, "roof_slate") # West

    # 4 Gabled Dormers (one centered on each wing's steep outer hip slope!)
    def add_dormer(cx, cz, facing):
        y0, y1, y_pk = Y_EAVE + 10, Y_EAVE + 30, Y_EAVE + 42
        if facing in ("+z", "-z"):
            s = 1 if facing == "+z" else -1
            z_face = cz + s * 16
            z_back = cz - s * 10
            z0, z1 = min(z_face, z_back), max(z_face, z_back)
            structure.add_box(cx - 12, y0, z0, cx + 12, y1, z1, "stone_rusticated")
            structure.add_panel_z(cx - 7, y0 + 2, cx + 7, y1 - 2, z_face + s * 0.6, facing, "glass_window", "win_arched")
            # Pitched slate dormer gable roof
            structure.add_planar_quad_art((cx - 14, y1, z1), (cx - 14, y1, z0), (cx, y_pk, z0), (cx, y_pk, z1), "roof_slate")
            structure.add_planar_quad_art((cx + 14, y1, z0), (cx + 14, y1, z1), (cx, y_pk, z1), (cx, y_pk, z0), "roof_slate")
            if facing == "+z":
                structure.add_planar_tri_art((cx - 12, y1, z_face), (cx + 12, y1, z_face), (cx, y_pk, z_face), "stone_rusticated")
            else:
                structure.add_planar_tri_art((cx + 12, y1, z_face), (cx - 12, y1, z_face), (cx, y_pk, z_face), "stone_rusticated")
        else:
            s = 1 if facing == "+x" else -1
            x_face = cx + s * 16
            x_back = cx - s * 10
            x0, x1 = min(x_face, x_back), max(x_face, x_back)
            structure.add_box(x0, y0, cz - 12, x1, y1, cz + 12, "stone_rusticated")
            structure.add_panel_x(cz - 7, y0 + 2, cz + 7, y1 - 2, x_face + s * 0.6, facing, "glass_window", "win_arched")
            structure.add_planar_quad_art((x0, y1, cz - 14), (x1, y1, cz - 14), (x1, y_pk, cz), (x0, y_pk, cz), "roof_slate")
            structure.add_planar_quad_art((x1, y1, cz + 14), (x0, y1, cz + 14), (x0, y_pk, cz), (x1, y_pk, cz), "roof_slate")
            if facing == "+x":
                structure.add_planar_tri_art((x_face, y1, cz + 12), (x_face, y1, cz - 12), (x_face, y_pk, cz), "stone_rusticated")
            else:
                structure.add_planar_tri_art((x_face, y1, cz - 12), (x_face, y1, cz + 12), (x_face, y_pk, cz), "stone_rusticated")

    add_dormer(0, CZ + 168, "+z")
    add_dormer(0, CZ - 168, "-z")
    add_dormer(168, CZ, "+x")
    add_dormer(-168, CZ, "-x")

    # ==========================================================================
    # 4. MAIN ENTRANCE (+Z): BROAD FLIGHT OF 6 STONE STEPS + ARCHED PORCH ON 2 COLUMNS
    #    + CARVED FRIEZE "HARLAN COUNTY COURTHOUSE", "1894" DATESTONE, & 2008 PLAQUE
    # ==========================================================================
    # Porch stone platform landing (x in [-62, 62], z in [CZ+192, CZ+232], y = 2..14)
    structure.add_box(-62, Y_GROUND, CZ + 192, 62, Y_FL1, CZ + 232, "stone_rusticated")
    coll.add_box(-62, 0, CZ + 192, 62, Y_FL1, CZ + 232, "main_porch_landing", "exterior")
    # Flanking stone cheek walls beside main steps
    for sx in (-62, 56):
        structure.add_box(sx, Y_GROUND, CZ + 232, sx + 6, Y_FL1 + 4, CZ + 268, "stone_rusticated")
        coll.add_box(sx, 0, CZ + 232, sx + 6, Y_FL1 + 4, CZ + 268, f"main_steps_cheek_{sx}", "exterior")
    # Broad flight of 6 REAL STONE STEPS (y = 2 -> 14, z = CZ+268 -> CZ+232) + stepped collision!
    for st in range(6):
        sy0 = Y_GROUND + st * 2.0
        sy1 = sy0 + 2.0
        sz0 = CZ + 232 + (5 - st) * 6.0
        sz1 = sz0 + 6.0
        structure.add_box(-56, Y_GROUND, sz0, 56, sy1, sz1, "stone_rusticated")
        coll.add_box(-56, 0, sz0, 56, sy1, sz1, f"main_step_{st + 1}", "steps")

    # Two stout 10-sided Romanesque stone columns carrying the arched porch (widely spaced at +/-55 so doorway & plaques are 100% unobstructed!)
    for cx_col in (-55, 55):
        structure.add_box(cx_col - 6, Y_FL1, CZ + 220, cx_col + 6, Y_FL1 + 8, CZ + 232, "stone_rusticated")  # plinth
        structure.add_cylinder(cx_col, Y_FL1 + 8, Y_FL1 + 46, CZ + 226, 4.6, 4.2, 10, "stone_rusticated")  # 10-sided shaft
        structure.add_box(cx_col - 6.5, Y_FL1 + 46, CZ + 219.5, cx_col + 6.5, Y_FL1 + 52, CZ + 232.5, "stone_rusticated")  # capital
        coll.add_box(cx_col - 6, 0, CZ + 220, cx_col + 6, Y_FL2 + 12, CZ + 232, f"main_porch_column_{cx_col}", "exterior")

    # Real 10-segment wide Romanesque stone porch arch spanning cleanly between the 2 columns!
    structure.add_arch_ring_z(0, Y_FL1 + 32, CZ + 222, CZ + 231, 46.0, 52.0, 10, "stone_rusticated", ry_in=20.0, ry_out=26.0)
    # Porch spandrel & entablature beam + flat stone porch deck roof (y = 66..74)
    structure.add_box(-62, Y_FL1 + 52, CZ + 192, 62, Y_FL2 + 2, CZ + 233, "stone_rusticated")
    # Side porch architrave beams (East & West flanks of main porch)
    for sx in (-56, 56):
        structure.add_box(sx - 3, Y_FL1 + 50, CZ + 192, sx + 3, Y_FL2 + 2, CZ + 232, "stone_rusticated")

    # Carved Limestone Frieze Entablature over main porch: "HARLAN COUNTY / COURTHOUSE" (96x20 at exact 1:1 art-pixel scale!)
    structure.add_box(-52, Y_FL2, CZ + 224, 52, Y_FL2 + 22, CZ + 233, "stone_rusticated")
    structure.add_panel_z(-48, Y_FL2 + 1, 48, Y_FL2 + 21, CZ + 233.25, "+z", "sign_plates", "main_frieze")

    # Carved Granite Datestone "1894" (32x12 art px) cleanly unobstructed right of door_main!
    structure.add_panel_z(16, Y_FL1 + 14, 48, Y_FL1 + 26, CZ + 192.4, "+z", "sign_plates", "datestone_1894")

    # 2008 Bronze Restoration Plaque "RESTORED / 2004" (36x16 art px) cleanly unobstructed left of door_main!
    if spec["has_plaque"]:
        structure.add_panel_z(-50, Y_FL1 + 12, -14, Y_FL1 + 28, CZ + 192.4, "+z", "sign_plates", "plaque_restored_2004")

    # Side Entrances (-Z North, +X East, -X West): shorter 4-step stone stairs + recessed stone archways
    def add_side_steps(side):
        if side == "-z":
            if spec["has_annex"]:
                return  # North side connects directly into the 1951-1999 concrete annex!
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
    # 5. CENTRAL CLOCK TOWER (rising to 38.02 m = 490 art px!)
    #    - Square brick shaft with rusticated stone corner quoins (y = 224 .. 362)
    #    - 4 recessed clock faces centered at y = 335 (26.00 m), separate glow_clock_* materials!
    #    - Open belfry with 4 real stone arches (y = 362 .. 408) + cast-bronze bell node!
    #    - Patinated copper-green pyramidal spire (y = 408 .. 472) + finial (472 .. 490)!
    # ==========================================================================
    TW = 46.0  # Half-width of tower shaft (92 x 92 art px = 7.14 m x 7.14 m)
    structure.add_box(-TW, 224, CZ - TW, TW, Y_BELFRY_BOT, CZ + TW, "brick_red")
    coll.add_box(-TW, Y_EAVE, CZ - TW, TW, Y_FINIAL_TOP, CZ + TW, "clock_tower_full_height", "exterior")

    # Stone corner quoins & horizontal cornice rings on tower shaft
    for sx in (-1, 1):
        for sz in (-1, 1):
            qx0 = (TW - 6) if sx > 0 else (-TW - 1.5)
            qx1 = (TW + 1.5) if sx > 0 else (-TW + 6)
            qz0 = CZ + ((TW - 6) if sz > 0 else (-TW - 1.5))
            qz1 = CZ + ((TW + 1.5) if sz > 0 else (-TW + 6))
            structure.add_box(qx0, 232, qz0, qx1, Y_BELFRY_BOT, qz1, "stone_rusticated")
    for ty in (296, 358, 404):
        structure.add_box(-TW - 4, ty, CZ - TW - 4, TW + 4, ty + 4, CZ + TW + 4, "stone_rusticated")

    # Four Clock Faces centered at y = 335 (26.00 m), 44x44 art px (3.41 m x 3.41 m)
    # Each face uses its own glow_clock_<dir> material!
    cr = 22.0
    structure.add_panel_z(-cr, Y_TOWER_CLK - cr, cr, Y_TOWER_CLK + cr, CZ + TW + 0.6, "+z", "glow_clock_south", fit_region=False)
    structure.add_panel_z(-cr, Y_TOWER_CLK - cr, cr, Y_TOWER_CLK + cr, CZ - TW - 0.6, "-z", "glow_clock_north", fit_region=False)
    structure.add_panel_x(CZ - cr, Y_TOWER_CLK - cr, CZ + cr, Y_TOWER_CLK + cr, TW + 0.6, "+x", "glow_clock_east", fit_region=False)
    structure.add_panel_x(CZ - cr, Y_TOWER_CLK - cr, CZ + cr, Y_TOWER_CLK + cr, -TW - 0.6, "-x", "glow_clock_west", fit_region=False)

    # Open Belfry (y = 362 .. 404): 4 corner masonry piers + 4 real arched belfry openings!
    BW = 40.0
    for sx in (-1, 1):
        for sz in (-1, 1):
            px0 = (BW - 12) if sx > 0 else -BW
            px1 = BW if sx > 0 else (-BW + 12)
            pz0 = CZ + ((BW - 12) if sz > 0 else -BW)
            pz1 = CZ + (BW if sz > 0 else (-BW + 12))
            structure.add_box(px0, Y_BELFRY_BOT, pz0, px1, 404, pz1, "stone_rusticated")
    # Belfry floor deck & low stone parapet sill
    structure.add_box(-BW, Y_BELFRY_BOT, CZ - BW, BW, Y_BELFRY_BOT + 4, CZ + BW, "stone_rusticated")
    # 4 Real Arches spanning the open belfry sides (8 segments each!)
    structure.add_arch_ring_z(0, 384, CZ + BW - 6, CZ + BW, 16.0, 20.0, 8, "stone_rusticated")
    structure.add_arch_ring_z(0, 384, CZ - BW, CZ - BW + 6, 16.0, 20.0, 8, "stone_rusticated")
    # East & West belfry arches (built in YZ plane along +/-BW)
    for sx_b in (-1, 1):
        xb0 = (BW - 6) if sx_b > 0 else -BW
        xb1 = BW if sx_b > 0 else (-BW + 6)
        for i in range(8):
            a0 = math.pi * i / 8
            a1 = math.pi * (i + 1) / 8
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            # Outer face
            xf = xb1 if sx_b > 0 else xb0
            xb = xb0 if sx_b > 0 else xb1
            p_in0 = (xf, 384 + s0 * 16.0, CZ + c0 * 16.0)
            p_out0 = (xf, 384 + s0 * 20.0, CZ + c0 * 20.0)
            p_out1 = (xf, 384 + s1 * 20.0, CZ + c1 * 20.0)
            p_in1 = (xf, 384 + s1 * 16.0, CZ + c1 * 16.0)
            b_in0 = (xb, 384 + s0 * 16.0, CZ + c0 * 16.0)
            b_out0 = (xb, 384 + s0 * 20.0, CZ + c0 * 20.0)
            b_out1 = (xb, 384 + s1 * 20.0, CZ + c1 * 20.0)
            b_in1 = (xb, 384 + s1 * 16.0, CZ + c1 * 16.0)
            if sx_b > 0:
                structure.add_planar_quad_art(p_out0, p_in0, p_in1, p_out1, "stone_rusticated")
                structure.add_planar_quad_art(b_in0, b_out0, b_out1, b_in1, "stone_rusticated")
                structure.add_planar_quad_art(p_in0, b_in0, b_in1, p_in1, "stone_rusticated")
            else:
                structure.add_planar_quad_art(p_in0, p_out0, p_out1, p_in1, "stone_rusticated")
                structure.add_planar_quad_art(b_out0, b_in0, b_in1, b_out1, "stone_rusticated")
                structure.add_planar_quad_art(b_in0, p_in0, p_in1, b_in1, "stone_rusticated")

    # Copper-Green Pyramidal Spire (y = 408 .. 472) + Cast-Iron/Copper Finial (472 .. 490 = 38.02 m!)
    spire_r = 44.0 * math.sqrt(2.0)
    structure.add_cone(0, Y_BELFRY_TOP, Y_SPIRE_TOP, CZ, spire_r, 4, "copper_spire", cap_bot=True)
    # Finial orb + needle spike
    structure.add_cylinder(0, Y_SPIRE_TOP - 2, Y_SPIRE_TOP + 4, CZ, 4.0, 4.0, 8, "detail_atlas", region_name="dark_iron")
    structure.add_cone(0, Y_SPIRE_TOP + 4, Y_FINIAL_TOP, CZ, 2.2, 4, "detail_atlas", region_name="dark_iron")

    # 1988 Protest Banner "SAVE OUR COURTHOUSE" hung across the +Z tower face below the clock!
    if spec["has_protest_banner"]:
        structure.add_panel_z(-46, 264, 46, 290, CZ + TW + 1.2, "+z", "sign_plates", "protest_banner_1988")

    # 2008 Warm Floodlight Heads at the 4 corners of the tower base (glow_ ONLY on tiny lamp heads!)
    if spec["has_floodlights"]:
        for sx in (-1, 1):
            for sz in (-1, 1):
                fx = sx * (TW + 6)
                fz = CZ + sz * (TW + 6)
                structure.add_box(fx - 3, 240, fz - 3, fx + 3, 244, fz + 3, "detail_atlas", region_name="dark_iron")
                structure.add_panel_y(fx - 2.5, fz - 2.5, fx + 2.5, fz + 2.5, 244.15, "+y", "glow_lamps", "lamp_flood")

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
    make_floor_mesh("entry_south", -58, CZ + 52, 58, CZ + 186, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("entry_north", -58, CZ - 186, 58, CZ - 52, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("entry_east", 52, CZ - 58, 186, CZ + 58, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("entry_west", -186, CZ - 58, -52, CZ + 58, Y_FL1 + 0.1, "interior_floor")

    # 4 Ground-Floor Corner Offices (Clerk SW, Assessor SE, Treasurer NW, Recorder NE)
    make_floor_mesh("office_clerk", -122, CZ + 58, -58, CZ + 122, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("office_assessor", 58, CZ + 58, 122, CZ + 122, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("office_treasurer", -122, CZ - 122, -58, CZ - 58, Y_FL1 + 0.1, "interior_floor")
    make_floor_mesh("office_recorder", 58, CZ - 122, 122, CZ - 58, Y_FL1 + 0.1, "interior_floor")

    # Ground-Floor Interior Partition Walls (separating offices from corridors, with open doorways!)
    def add_int_wall(x0, y0, z0, x1, y1, z1, name):
        structure.add_box(x0, y0, z0, x1, y1, z1, "interior_wall")
        coll.add_box(x0, y0, z0, x1, y1, z1, name, "interior")

    # Office partitions along X = +/-58 (with 18-art-px doorway gaps centered at CZ +/- 90)
    for sx, side_lbl in ((-1, "w"), (1, "e")):
        wx0 = -60 if sx < 0 else 58
        wx1 = -58 if sx < 0 else 60
        for sz, ns_lbl in ((1, "s"), (-1, "n")):
            za = CZ + (58 if sz > 0 else -122)
            zb = CZ + (122 if sz > 0 else -58)
            mid = (za + zb) * 0.5
            add_int_wall(wx0, Y_FL1, za, wx1, Y_FL2, mid - 9, f"int_wall_fl1_{ns_lbl}{side_lbl}_a")
            add_int_wall(wx0, Y_FL1, mid + 9, wx1, Y_FL2, zb, f"int_wall_fl1_{ns_lbl}{side_lbl}_b")
            structure.add_box(wx0, Y_FL1 + 30, mid - 9, wx1, Y_FL2, mid + 9, "interior_wall")
            # Perpendicular partition along Z = CZ +/- 58
            pza = CZ + (58 if sz > 0 else -60)
            pzb = CZ + (60 if sz > 0 else -58)
            pxa = -122 if sx < 0 else 60
            pxb = -60 if sx < 0 else 122
            add_int_wall(pxa, Y_FL1, pza, pxb, Y_FL2, pzb, f"int_wall_fl1_cross_{ns_lbl}{side_lbl}")

    # Interior oak-wainscot & cream-plaster walls inside Double-Height Courtroom (Floors 2 & 3 in +Z South wing!)
    # Lower 64 art px (Y_FL2..Y_FL2+64): oak wainscot + cream plaster; upper clerestory (Y_FL2+64..Y_EAVE-2): clean cream paneling!
    structure.add_panel_z(-58, Y_FL2, 58, Y_FL2 + 64, CZ + 185.6, "-z", "interior_wall")
    for sx_c in (-58, 0):
        structure.add_panel_z(sx_c, Y_FL2 + 64, sx_c + 58, Y_EAVE - 2, CZ + 185.6, "-z", "detail_atlas", "bandstand_cream")
    for z_a, z_b in ((CZ + 56, CZ + 120), (CZ + 120, CZ + 186)):
        structure.add_panel_x(z_a, Y_FL2, z_b, Y_FL2 + 64, -57.6, "+x", "interior_wall")
        structure.add_panel_x(z_a, Y_FL2, z_b, Y_FL2 + 64, 57.6, "-x", "interior_wall")
    for z_a, z_b in ((CZ + 56, CZ + 100), (CZ + 100, CZ + 144), (CZ + 144, CZ + 186)):
        structure.add_panel_x(z_a, Y_FL2 + 64, z_b, Y_EAVE - 2, -57.6, "+x", "detail_atlas", "bandstand_cream")
        structure.add_panel_x(z_a, Y_FL2 + 64, z_b, Y_EAVE - 2, 57.6, "-x", "detail_atlas", "bandstand_cream")
    # Interior sunlit courtroom windows on the South wall flanking & above the Judge's Bench
    for wy_c in (Y_FL2 + 16, Y_FL3 + 12):
        for wx_c in (-42, 0, 42):
            if wy_c == Y_FL2 + 16 and wx_c == 0:
                continue  # behind carved oak reredos backdrop
            structure.add_panel_z(wx_c - 9, wy_c, wx_c + 9, wy_c + 28, CZ + 185.2, "-z", "glass_window", "win_arched")
    # Overhead coffered ceiling of courtroom at Y_EAVE - 2
    for z_a, z_b in ((CZ + 56, CZ + 100), (CZ + 100, CZ + 144), (CZ + 144, CZ + 186)):
        for sx_c in (-58, 0):
            structure.add_panel_y(sx_c, z_a, sx_c + 58, z_b, Y_EAVE - 2, "-y", "detail_atlas", "bandstand_cream")

    # --- GRAND STAIRCASE (Real stepped geometry + landing + balustrades + stepped collision!) ---
    # Flight 1 (Ground Floor Y_FL1=14 -> Mid-Landing y=43): centered X in [-16, 16], Z from CZ-6 to CZ-36 (10 steps!)
    for st in range(10):
        sy0 = Y_FL1 + st * 2.9
        sy1 = sy0 + 2.9
        sz1 = CZ - 6.0 - st * 3.0
        sz0 = sz1 - 3.0
        structure.add_box(-16, sy0, sz0, 16, sy1, sz1, "interior_floor")
        coll.add_box(-16, Y_FL1, sz0, 16, sy1, sz1, f"grand_stair_lower_step_{st + 1}", "interior_stairs")
    # Mid-Landing (X in [-44, 44], Z in [CZ-50, CZ-36], y = 41..43)
    structure.add_box(-44, 41, CZ - 50, 44, 43, CZ - 36, "interior_floor")
    coll.add_box(-44, 40, CZ - 50, 44, 43, CZ - 36, "grand_stair_mid_landing", "interior_stairs")
    # Twin Return Flights (Mid-Landing y=43 -> Floor 2 Gallery Y_FL2=72): West [-44,-22] & East [22,44], 10 steps returning +Z!
    for sx_f, fl_name in ((-33, "west"), (33, "east")):
        fx0, fx1 = sx_f - 11, sx_f + 11
        for st in range(10):
            sy0 = 43.0 + st * 2.9
            sy1 = sy0 + 2.9
            sz0 = CZ - 36.0 + st * 3.0
            sz1 = sz0 + 3.0
            structure.add_box(fx0, sy0, sz0, fx1, sy1, sz1, "interior_floor")
            coll.add_box(fx0, 40, sz0, fx1, sy1, sz1, f"grand_stair_upper_{fl_name}_step_{st + 1}", "interior_stairs")
        # Return stair side rail
        rail_x = -22 if sx_f < 0 else 20
        structure.add_box(rail_x, 43, CZ - 36, rail_x + 2, Y_FL2 + 12, CZ - 6, "interior_floor")

    # Floor 2 -> Floor 3 Staircase in North Wing (X in [-46, -24], Z from CZ-60 to CZ-124, 16 steps!)
    for st in range(16):
        sy0 = Y_FL2 + st * 3.625
        sy1 = sy0 + 3.625
        sz1 = CZ - 60.0 - st * 4.0
        sz0 = sz1 - 4.0
        structure.add_box(-46, sy0, sz0, -24, sy1, sz1, "interior_floor")
        coll.add_box(-46, Y_FL2, sz0, -24, sy1, sz1, f"fl2_to_fl3_stair_step_{st + 1}", "interior_stairs")

    # --- FLOOR 2 (y = Y_FL2 = 72) ---
    # Wrap-around atrium gallery floor_gallery_2 (ring around open rotunda well dx,dz in [-44, 44]!)
    fb_g2 = MeshBucket("floor_gallery_2", mat_idx, mat_size, regions)
    fb_g2.add_panel_y(-64, CZ + 38, 64, CZ + 56, Y_FL2 + 0.1, "+y", "interior_floor")   # South gallery walk
    fb_g2.add_panel_y(-64, CZ - 56, 64, CZ - 38, Y_FL2 + 0.1, "+y", "interior_floor")   # North gallery walk
    fb_g2.add_panel_y(-64, CZ - 38, -44, CZ + 38, Y_FL2 + 0.1, "+y", "interior_floor")  # West gallery walk
    fb_g2.add_panel_y(44, CZ - 38, 64, CZ + 38, Y_FL2 + 0.1, "+y", "interior_floor")    # East gallery walk
    floor_buckets["floor_gallery_2"] = fb_g2

    # Floor 2 Gallery Balustrade Railings (real geometry + collision!)
    def add_gallery_rails(y_fl, floor_tag):
        rh = 13.0  # ~1.01 m handrail height
        # South rail
        structure.add_box(-44, y_fl, CZ + 38, 44, y_fl + rh, CZ + 40, "interior_floor")
        coll.add_box(-44, y_fl, CZ + 38, 44, y_fl + rh, CZ + 40, f"{floor_tag}_gallery_rail_south", "interior")
        # West & East rails (leave open at stair arrival on Floor 2 north end)
        z_start = CZ - 6 if floor_tag == "fl2" else CZ - 38
        structure.add_box(-44, y_fl, z_start, -42, y_fl + rh, CZ + 38, "interior_floor")
        coll.add_box(-44, y_fl, z_start, -42, y_fl + rh, CZ + 38, f"{floor_tag}_gallery_rail_west", "interior")
        structure.add_box(42, y_fl, z_start, 44, y_fl + rh, CZ + 38, "interior_floor")
        coll.add_box(42, y_fl, z_start, 44, y_fl + rh, CZ + 38, f"{floor_tag}_gallery_rail_east", "interior")
        if floor_tag == "fl3":
            structure.add_box(-44, y_fl, CZ - 40, 44, y_fl + rh, CZ - 38, "interior_floor")
            coll.add_box(-44, y_fl, CZ - 40, 44, y_fl + rh, CZ - 38, "fl3_gallery_rail_north", "interior")
        # 4 Tall Atrium Corner Piers rising from Ground Floor to Skylight!
        if floor_tag == "fl2":
            for sx in (-46, 42):
                for sz in (CZ - 44, CZ + 38):
                    structure.add_box(sx, Y_FL1, sz, sx + 4, Y_EAVE + 24, sz + 4, "interior_wall")

    add_gallery_rails(Y_FL2, "fl2")

    # DOUBLE-HEIGHT COURTROOM (Floor 2 & Floor 3 in +Z South Wing!)
    make_floor_mesh("courtroom", -58, CZ + 56, 58, CZ + 186, Y_FL2 + 0.1, "interior_floor")
    # Partition wall between gallery_2 and courtroom (with wide double doors at X in [-12, +12])
    add_int_wall(-58, Y_FL2, CZ + 54, -12, Y_EAVE, CZ + 56, "courtroom_north_wall_west")
    add_int_wall(12, Y_FL2, CZ + 54, 58, Y_EAVE, CZ + 56, "courtroom_north_wall_east")
    structure.add_box(-12, Y_FL2 + 34, CZ + 54, 12, Y_EAVE, CZ + 56, "interior_wall")

    # Courtroom Architectural Fixtures ONLY (Judge's Bench + Bar Rail — rest placed by game!)
    # 1. Oak Bar Rail separating public gallery from well (Z = CZ + 114, with central swing-gate opening [-8, 8])
    structure.add_box(-56, Y_FL2, CZ + 113, -8, Y_FL2 + 13, CZ + 116, "interior_floor")
    structure.add_box(8, Y_FL2, CZ + 113, 56, Y_FL2 + 13, CZ + 116, "interior_floor")
    # Decorative corner newel posts on bar rail
    for bx in (-56, -9, 7, 54):
        structure.add_box(bx, Y_FL2, CZ + 112.5, bx + 2.5, Y_FL2 + 15, CZ + 116.5, "interior_floor")
    coll.add_box(-56, Y_FL2, CZ + 113, -8, Y_FL2 + 13, CZ + 116, "courtroom_bar_rail_west", "interior")
    coll.add_box(8, Y_FL2, CZ + 113, 56, Y_FL2 + 13, CZ + 116, "courtroom_bar_rail_east", "interior")

    # 2. Raised Judge's Bench (2-step wood dais + paneled high oak desk & carved reredos backdrop!)
    # Step 1 & Step 2 dais (Z in [CZ+148, CZ+182], X in [-30, 30])
    structure.add_box(-30, Y_FL2, CZ + 148, 30, Y_FL2 + 3, CZ + 182, "interior_floor")
    structure.add_box(-26, Y_FL2 + 3, CZ + 152, 26, Y_FL2 + 6, CZ + 182, "interior_floor")
    # Paneled Judge's Bench Desk (front & returns at y = 78..94)
    structure.add_box(-22, Y_FL2 + 6, CZ + 154, 22, Y_FL2 + 22, CZ + 159, "interior_floor")
    structure.add_box(-22, Y_FL2 + 6, CZ + 159, -18, Y_FL2 + 22, CZ + 174, "interior_floor")
    structure.add_box(18, Y_FL2 + 6, CZ + 159, 22, Y_FL2 + 22, CZ + 174, "interior_floor")
    # Bench desk top cap & carved rear paneled wood backdrop against south wall
    structure.add_box(-23, Y_FL2 + 22, CZ + 153, 23, Y_FL2 + 24, CZ + 160, "interior_floor")
    structure.add_box(-24, Y_FL2 + 6, CZ + 180, 24, Y_FL2 + 44, CZ + 184, "interior_floor")
    coll.add_box(-30, Y_FL2, CZ + 148, 30, Y_FL2 + 24, CZ + 184, "courtroom_judges_bench", "interior")

    # Floor 2 Side Offices (Chambers W, Jury Room E, Law Library N)
    make_floor_mesh("chambers", -186, CZ - 58, -64, CZ + 58, Y_FL2 + 0.1, "interior_floor")
    make_floor_mesh("jury", 64, CZ - 58, 186, CZ + 58, Y_FL2 + 0.1, "interior_floor")
    make_floor_mesh("law_library", -58, CZ - 186, 58, CZ - 56, Y_FL2 + 0.1, "interior_floor")

    # Floor 2 Interior Office Partition Walls (with doorway openings)
    add_int_wall(-64, Y_FL2, CZ - 58, -62, Y_FL3, CZ - 9, "int_wall_fl2_chambers_n")
    add_int_wall(-64, Y_FL2, CZ + 9, -62, Y_FL3, CZ + 58, "int_wall_fl2_chambers_s")
    add_int_wall(62, Y_FL2, CZ - 58, 64, Y_FL3, CZ - 9, "int_wall_fl2_jury_n")
    add_int_wall(62, Y_FL2, CZ + 9, 64, Y_FL3, CZ + 58, "int_wall_fl2_jury_s")
    add_int_wall(-16, Y_FL2, CZ - 58, -9, Y_FL3, CZ - 56, "int_wall_fl2_lib_w")
    add_int_wall(9, Y_FL2, CZ - 58, 58, Y_FL3, CZ - 56, "int_wall_fl2_lib_e")

    # --- FLOOR 3 (y = Y_FL3 = 130) ---
    # Wrap-around atrium gallery floor_gallery_3 overlooking rotunda & rising to skylight!
    fb_g3 = MeshBucket("floor_gallery_3", mat_idx, mat_size, regions)
    fb_g3.add_panel_y(-64, CZ + 38, 64, CZ + 54, Y_FL3 + 0.1, "+y", "interior_floor")
    fb_g3.add_panel_y(-64, CZ - 56, 64, CZ - 38, Y_FL3 + 0.1, "+y", "interior_floor")
    fb_g3.add_panel_y(-64, CZ - 38, -44, CZ + 38, Y_FL3 + 0.1, "+y", "interior_floor")
    fb_g3.add_panel_y(44, CZ - 38, 64, CZ + 38, Y_FL3 + 0.1, "+y", "interior_floor")
    floor_buckets["floor_gallery_3"] = fb_g3
    add_gallery_rails(Y_FL3, "fl3")

    # Floor 3 Offices (West Records, East Archives, North Commission; South is open double-height Courtroom!)
    make_floor_mesh("records", -186, CZ - 58, -64, CZ + 58, Y_FL3 + 0.1, "interior_floor")
    make_floor_mesh("archives", 64, CZ - 58, 186, CZ + 58, Y_FL3 + 0.1, "interior_floor")
    make_floor_mesh("commission", -58, CZ - 186, 58, CZ - 56, Y_FL3 + 0.1, "interior_floor")

    add_int_wall(-64, Y_FL3, CZ - 58, -62, Y_EAVE, CZ - 9, "int_wall_fl3_records_n")
    add_int_wall(-64, Y_FL3, CZ + 9, -62, Y_EAVE, CZ + 58, "int_wall_fl3_records_s")
    add_int_wall(62, Y_FL3, CZ - 58, 64, Y_EAVE, CZ - 9, "int_wall_fl3_archives_n")
    add_int_wall(62, Y_FL3, CZ + 9, 64, Y_EAVE, CZ + 58, "int_wall_fl3_archives_s")
    add_int_wall(-58, Y_FL3, CZ - 58, -9, Y_EAVE, CZ - 56, "int_wall_fl3_comm_w")
    add_int_wall(9, Y_FL3, CZ - 58, 58, Y_EAVE, CZ - 56, "int_wall_fl3_comm_e")

    # --- ROTUNDA SKYLIGHT WELL (rising cleanly above Floor 3 to glazed skylight at y = 212!) ---
    # Coffered cream plaster drum walls framing the skylight shaft (split into 44x28 panels inside bandstand_cream quadrant!)
    for seg in (-44, 0):
        structure.add_panel_z(seg, Y_EAVE - 2, seg + 44, 214, CZ - 44, "+z", "detail_atlas", "bandstand_cream")
        structure.add_panel_z(seg, Y_EAVE - 2, seg + 44, 214, CZ + 44, "-z", "detail_atlas", "bandstand_cream")
        structure.add_panel_x(CZ + seg, Y_EAVE - 2, CZ + seg + 44, 214, -44, "+x", "detail_atlas", "bandstand_cream")
        structure.add_panel_x(CZ + seg, Y_EAVE - 2, CZ + seg + 44, 214, 44, "-x", "detail_atlas", "bandstand_cream")
    # Overhead luminous leaded skylight glazing (tiled cleanly in 4 44x44 panes at y = 212)
    for sx in (-44, 0):
        for sz in (-44, 0):
            structure.add_panel_y(sx, CZ + sz, sx + 44, CZ + sz + 44, 212.0, "-y", "glass_window", "skylight_pane")
    # Skylight timber mullion cross beams (split into 44-art-px halves to stay inside 64x64 quadrant)
    for sx in (-44, 0):
        structure.add_box(sx, 209, CZ - 2, sx + 44, 212, CZ + 2, "detail_atlas", region_name="bandstand_cream")
    for sz in (-44, 0):
        structure.add_box(-2, 209, CZ + sz, 2, 212, CZ + sz + 44, "detail_atlas", region_name="bandstand_cream")

    # ==========================================================================
    # 7. THE TOWN SQUARE LANDMARKS: MEMORIAL, BANDSTAND, FLAGPOLE & ERA EXTRAS
    # ==========================================================================
    # A. CIVIL WAR SOLDIER MEMORIAL (SE lawn quadrant, x = 232, z = CZ + 260 = -252)
    mx, mz = 232.0, CZ + 260.0
    # 3-tier stepped granite base
    lot_memorial.add_box(mx - 22, Y_GROUND, mz - 22, mx + 22, 6, mz + 22, "stone_rusticated")
    lot_memorial.add_box(mx - 16, 6, mz - 16, mx + 16, 11, mz + 16, "stone_rusticated")
    lot_memorial.add_box(mx - 11, 11, mz - 11, mx + 11, 24, mz + 11, "stone_rusticated")
    # 8-sided tapered granite column + capital
    lot_memorial.add_cylinder(mx, 24, 68, mz, 7.5, 5.5, 8, "stone_rusticated", cap_top=True)
    lot_memorial.add_box(mx - 8, 68, mz - 8, mx + 8, 72, mz + 8, "stone_rusticated")
    # Pixel-art cast-bronze Civil War Soldier statue standing at parade rest on top (y = 72..98)!
    lot_memorial.add_box(mx - 5, 72, mz - 5, mx + 5, 75, mz + 5, "detail_atlas", region_name="dark_iron")  # plinth
    lot_memorial.add_box(mx - 3.5, 75, mz - 2, mx - 0.8, 84, mz + 2, "detail_atlas", region_name="dark_iron")  # left leg
    lot_memorial.add_box(mx + 0.8, 75, mz - 2, mx + 3.5, 84, mz + 2, "detail_atlas", region_name="dark_iron")  # right leg
    lot_memorial.add_box(mx - 4.5, 83, mz - 3, mx + 4.5, 92, mz + 3, "detail_atlas", region_name="dark_iron")  # greatcoat torso
    lot_memorial.add_box(mx - 2.5, 92, mz - 2.5, mx + 2.5, 96.5, mz + 2.5, "detail_atlas", region_name="dark_iron")  # head
    lot_memorial.add_box(mx - 3, 96.5, mz - 2.5, mx + 3, 98.5, mz + 3.8, "detail_atlas", region_name="dark_iron")    # kepi forage cap
    lot_memorial.add_box(mx + 4.5, 75, mz - 0.8, mx + 6.0, 95, mz + 0.8, "detail_atlas", region_name="dark_iron")    # resting rifle
    coll.add_box(mx - 22, 0, mz - 22, mx + 22, 98.5, mz + 22, "civil_war_memorial", "square")

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
    hand_hour_mesh = build_clock_hand_mesh("clock_hour_mesh", 11.5, 1.3, mat_idx, mat_size, regions)
    hand_min_mesh = build_clock_hand_mesh("clock_minute_mesh", 16.5, 0.9, mat_idx, mat_size, regions)
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

    # Instanced Arched Windows across all 4 facades & 3 storeys!
    win_children = []
    q_south = [0.0, 0.0, 0.0, 1.0]
    q_north = quat_axis_angle(0, 1, 0, math.pi)
    q_east = quat_axis_angle(0, 1, 0, math.pi * 0.5)
    q_west = quat_axis_angle(0, 1, 0, -math.pi * 0.5)

    win_placements = []
    # Ground floor (y = Y_FL1 + 14 = 28), Floor 2 (y = Y_FL2 + 14 = 86), Floor 3 paired (y = Y_FL3 + 14 = 144)
    for fl_idx, wy in enumerate((Y_FL1 + 14, Y_FL2 + 14, Y_FL3 + 14)):
        # South (+Z) pavilion & corners
        for wx in (-96, 96):
            win_placements.append((wx, wy, CZ + 128, q_south, fl_idx, "south_corner"))
        if fl_idx >= 1:
            # Floors 2 & 3 South pavilion windows (Ground floor porch wall holds door_main, datestone 1894, & 2008 plaque!)
            for wx in (-42, 42):
                win_placements.append((wx, wy, CZ + 192, q_south, fl_idx, "south_pav"))
            win_placements.append((0, wy, CZ + 192, q_south, fl_idx, "south_center"))
        # North (-Z) pavilion & corners (skip ground/fl2 north pavilion behind annex in 1962/1988)
        for wx in (-96, 96):
            win_placements.append((wx, wy, CZ - 128, q_north, fl_idx, "north_corner"))
        if not (spec["has_annex"] and fl_idx < 2):
            for wx in (-42, 42):
                win_placements.append((wx, wy, CZ - 192, q_north, fl_idx, "north_pav"))
            if fl_idx >= 1:
                win_placements.append((0, wy, CZ - 192, q_north, fl_idx, "north_center"))
        # East (+X) pavilion & corners
        for wz in (CZ - 96, CZ + 96):
            win_placements.append((128, wy, wz, q_east, fl_idx, "east_corner"))
        for wz in (CZ - 42, CZ + 42):
            win_placements.append((192, wy, wz, q_east, fl_idx, "east_pav"))
        if fl_idx >= 1:
            win_placements.append((192, wy, CZ, q_east, fl_idx, "east_center"))
        # West (-X) pavilion & corners
        for wz in (CZ - 96, CZ + 96):
            win_placements.append((-128, wy, wz, q_west, fl_idx, "west_corner"))
        for wz in (CZ - 42, CZ + 42):
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

    # Cross-shaped courthouse massing: rusticated stone base (Y_GROUND..Y_FL2) + red brick upper (Y_FL2..Y_EAVE)
    for y0, y1, mat in ((Y_GROUND, Y_FL2, "stone_rusticated"), (Y_FL2, Y_EAVE, "brick_red")):
        far.add_box(-128, y0, CZ - 128, 128, y1, CZ + 128, mat, faces=["+x", "-x", "+z", "-z"])
        far.add_box(-64, y0, CZ - 192, 64, y1, CZ + 192, mat, faces=["+x", "-x", "+z", "-z"])
        far.add_box(-192, y0, CZ - 64, 192, y1, CZ + 64, mat, faces=["+x", "-x", "+z", "-z"])

    # Steep hipped slate roofs over central block & 4 wings
    far.add_hip_roof(-132, 132, CZ - 132, CZ + 132, Y_EAVE, Y_RIDGE, 80, 80, "roof_slate")
    far.add_hip_roof(-68, 68, CZ + 112, CZ + 196, Y_EAVE, Y_RIDGE - 6, 48, 42, "roof_slate")
    far.add_hip_roof(-68, 68, CZ - 196, CZ - 112, Y_EAVE, Y_RIDGE - 6, 48, 42, "roof_slate")
    far.add_hip_roof(112, 196, CZ - 68, CZ + 68, Y_EAVE, Y_RIDGE - 6, 42, 48, "roof_slate")
    far.add_hip_roof(-196, -112, CZ - 68, CZ + 68, Y_EAVE, Y_RIDGE - 6, 42, 48, "roof_slate")

    # Central clock tower shaft + belfry + 4 lit clock faces + copper-green spire + finial
    TW = 46.0
    far.add_box(-TW, 224, CZ - TW, TW, Y_BELFRY_BOT, CZ + TW, "brick_red", faces=["+x", "-x", "+z", "-z"])
    far.add_box(-40, Y_BELFRY_BOT, CZ - 40, 40, Y_BELFRY_TOP, CZ + 40, "stone_rusticated", faces=["+x", "-x", "+z", "-z"])
    cr = 22.0
    far.add_panel_z(-cr, Y_TOWER_CLK - cr, cr, Y_TOWER_CLK + cr, CZ + TW + 0.8, "+z", "glow_clock_south")
    far.add_panel_z(-cr, Y_TOWER_CLK - cr, cr, Y_TOWER_CLK + cr, CZ - TW - 0.8, "-z", "glow_clock_north")
    far.add_panel_x(CZ - cr, Y_TOWER_CLK - cr, CZ + cr, Y_TOWER_CLK + cr, TW + 0.8, "+x", "glow_clock_east")
    far.add_panel_x(CZ - cr, Y_TOWER_CLK - cr, CZ + cr, Y_TOWER_CLK + cr, -TW - 0.8, "-x", "glow_clock_west")
    far.add_cone(0, Y_BELFRY_TOP, Y_SPIRE_TOP, CZ, 44.0 * math.sqrt(2.0), 4, "copper_spire", cap_bot=False)
    far.add_cone(0, Y_SPIRE_TOP, Y_FINIAL_TOP, CZ, 2.2, 4, "detail_atlas", region_name="dark_iron", cap_bot=False)

    # Tiny distant silhouette hints of bandstand roof & memorial obelisk
    far.add_cone(-240, 20, 84, CZ + 260, 44.0, 6, "roof_slate", cap_bot=False)
    far.add_cone(232, Y_GROUND, 96, CZ + 260, 12.0, 4, "stone_rusticated", cap_bot=False)

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
