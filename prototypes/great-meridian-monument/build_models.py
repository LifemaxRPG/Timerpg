#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) builder for THE GREAT MERIDIAN MONUMENT (Dedicated 1926).
A grand national monument (~105.5 m tall on a 120 m x 120 m ceremonial acropolis & reflecting pool plaza):
  - Great Reflecting Pool + 12 ceremonial civic flagpoles + Art Deco globe lamps + benches
  - Twin sweeping curved-faceted Peristyle Colonnade Wings (24 fluted 10 m limestone columns + bronze urns)
  - 3-tier Ceremonial Acropolis Plinth with 4 sweeping monumental staircases & 4 Eternal Flame Braziers
  - 4 Colossal Battered Art Deco / Beaux-Arts Cathedral Pylons with fluted ribs, deep-reveal arched windows,
    stepped setbacks, bronze pinnacles, and verdigris-copper corner turrets
  - 4 Coffered Limestone Triumphal Barrel-Vaulted Arches (17 m clear span) with flush carved friezes:
    "THE GREAT MERIDIAN / DEDICATED MCMXXVI", "MERIDIAN EAST", "MERIDIAN WEST"
  - 4 Soaring Eiffel-style Open-Truss Riveted-Steel & Gilded-Bronze Cathedral Arches + 2 Diagonal Vault Ribs
  - Colossal 15.2-metre Suspended Gilded-Bronze & Verdigris-Copper Celestial Armillary Sphere (named node!)
  - Walkable 58.2 m 360-degree Sky Observation Crown (open bronze railing, 16-bay colonnade & brass telescopes)
  - Octagonal Art Deco Cathedral Lantern Drum (8 flying buttress fins + 8 glowing amber lancets)
  - 8-Point Verdigris-Copper & Gilded Starburst Crown + Ribbed Tapered Spire + 105.5 m Astral Beacon Orb
  - Walkable interior halls (Compass Rose Rotunda, West & East Memorial Halls, Observatory Lobby, Sky Deck)
  - Matching distant landmark silhouette LOD: great-meridian-monument-far.glb

Contract compliance:
  - Metres, Y-up, front approach facing +Z, origin (0, 0, 0) at midpoint of +Z front curb, ground y >= 0.
  - Strict 0.0388 m/texel (1 art px = 2x2 texels = 0.0776 m), 0 UV stretch errors, NEAREST + REPEAT.
  - 12 named role materials, full-height collision boxes in both GLB and model-info.json.
"""
import json
import math
import struct
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"
REGIONS_PATH = R / "art-regions.json"

TEXEL = 0.0388
U = 2.0 * TEXEL  # 0.0776 m per art pixel

# ==============================================================================
# 3D VECTOR & QUATERNION MATH
# ==============================================================================
def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])

def v_add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])

def v_scale(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)

def v_dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

def v_cross(a, b):
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

def v_len(a):
    return math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

def v_norm(a):
    l = v_len(a)
    return (a[0] / l, a[1] / l, a[2] / l) if l > 1e-12 else (0.0, 1.0, 0.0)

def quat_y(rad):
    return [0.0, math.sin(rad * 0.5), 0.0, math.cos(rad * 0.5)]


# ==============================================================================
# MESH BUCKET WITH EXACT 0.0388 m/TEXEL PLANAR UV PROJECTION
# ==============================================================================
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
            self.prims[mi] = {"pos": [], "norm": [], "uv": [], "idx": []}
        return self.prims[mi]

    def _ortho_axes(self, n):
        nx, ny, nz = n
        if abs(ny) < 0.9:
            t1 = v_norm(v_cross((0.0, 1.0, 0.0), n))
        else:
            t1 = v_norm(v_cross((0.0, 0.0, 1.0), n))
        t2 = v_cross(n, t1)
        return t1, t2

    def _map_uvs_planar(self, pts_art, n, mat_name, region_name=None, fit_region=False):
        art_w, art_h = self.mat_size[mat_name]
        if region_name is not None:
            rx, ry, rw, rh = self.regions[mat_name][region_name]
        else:
            rx, ry, rw, rh = 0.0, 0.0, art_w, art_h

        t1, t2 = self._ortho_axes(n)
        # Keep horizontal faces aligned cleanly with world X/Z and vertical faces with horizontal/Y
        nx, ny, nz = n
        if abs(ny) > 0.9999:
            t1 = (1.0, 0.0, 0.0)
            t2 = (0.0, 0.0, 1.0) if ny > 0 else (0.0, 0.0, -1.0)
        elif abs(nz) > 0.9999:
            t1 = (1.0, 0.0, 0.0) if nz > 0 else (-1.0, 0.0, 0.0)
            t2 = (0.0, -1.0, 0.0)
        elif abs(nx) > 0.9999:
            t1 = (0.0, 0.0, -1.0) if nx > 0 else (0.0, 0.0, 1.0)
            t2 = (0.0, -1.0, 0.0)

        us_raw = [v_dot(p, t1) for p in pts_art]
        vs_raw = [v_dot(p, t2) for p in pts_art]
        u0, u1 = min(us_raw), max(us_raw)
        v0, v1 = min(vs_raw), max(vs_raw)
        span_u = u1 - u0
        span_v = v1 - v0

        uvs = []
        if region_name is not None:
            # Strict region containment check (no cross-bleed on atlases!)
            if span_u > rw + 0.05 or span_v > rh + 0.05:
                raise AssertionError(
                    f"Poly {span_u:.2f}x{span_v:.2f} art px exceeds region '{region_name}' {rw}x{rh} in {mat_name}"
                )
            ox = rx + (rw - span_u) * 0.5 if fit_region else rx
            oy = ry + (rh - span_v) * 0.5 if fit_region else ry
            for ur, vr in zip(us_raw, vs_raw):
                uvs.append(((ox + (ur - u0)) / art_w, (oy + (vr - v0)) / art_h))
        else:
            # Seamless repeating texture: exact world-aligned planar UVs (zero UV stretch!)
            for ur, vr in zip(us_raw, vs_raw):
                uvs.append((ur / art_w, vr / art_h))
        return uvs

    def add_planar_tri_art(self, p0, p1, p2, mat_name, region_name=None, fit_region=False):
        e1 = v_sub(p1, p0)
        e2 = v_sub(p2, p0)
        n = v_cross(e1, e2)
        if v_len(n) < 1e-8:
            return
        n = v_norm(n)
        pts = [p0, p1, p2]
        uvs = self._map_uvs_planar(pts, n, mat_name, region_name, fit_region)
        p = self._prim(mat_name)
        base = len(p["pos"])
        for pt, uv in zip(pts, uvs):
            p["pos"].append((pt[0] * U, pt[1] * U, pt[2] * U))
            p["norm"].append(n)
            p["uv"].append(uv)
        p["idx"].extend([base, base + 1, base + 2])

    def add_planar_quad_art(self, p0, p1, p2, p3, mat_name, region_name=None, fit_region=False):
        e1 = v_sub(p1, p0)
        e2 = v_sub(p2, p0)
        n = v_cross(e1, e2)
        if v_len(n) < 1e-8:
            n = v_cross(v_sub(p2, p0), v_sub(p3, p0))
            if v_len(n) < 1e-8:
                return
        n = v_norm(n)
        pts = [p0, p1, p2, p3]
        uvs = self._map_uvs_planar(pts, n, mat_name, region_name, fit_region)
        p = self._prim(mat_name)
        base = len(p["pos"])
        for pt, uv in zip(pts, uvs):
            p["pos"].append((pt[0] * U, pt[1] * U, pt[2] * U))
            p["norm"].append(n)
            p["uv"].append(uv)
        p["idx"].extend([base, base + 1, base + 2, base, base + 2, base + 3])

    def add_panel_z(self, x0, y0, x1, y1, z, facing, mat_name, region_name=None, fit_region=False):
        if facing == "+z":
            self.add_planar_quad_art((x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z), mat_name, region_name, fit_region)
        else:
            self.add_planar_quad_art((x1, y0, z), (x0, y0, z), (x0, y1, z), (x1, y1, z), mat_name, region_name, fit_region)

    def add_panel_x(self, z0, y0, z1, y1, x, facing, mat_name, region_name=None, fit_region=False):
        if facing == "+x":
            self.add_planar_quad_art((x, y0, z1), (x, y0, z0), (x, y1, z0), (x, y1, z1), mat_name, region_name, fit_region)
        else:
            self.add_planar_quad_art((x, y0, z0), (x, y0, z1), (x, y1, z1), (x, y1, z0), mat_name, region_name, fit_region)

    def add_panel_y(self, x0, z0, x1, z1, y, facing, mat_name, region_name=None, fit_region=False):
        if facing == "+y":
            self.add_planar_quad_art((x0, y, z1), (x1, y, z1), (x1, y, z0), (x0, y, z0), mat_name, region_name, fit_region)
        else:
            self.add_planar_quad_art((x0, y, z0), (x1, y, z0), (x1, y, z1), (x0, y, z1), mat_name, region_name, fit_region)

    def add_box(self, x0, y0, z0, x1, y1, z1, mat_name, faces=None, region_name=None, fit_region=False):
        if x1 <= x0 + 1e-5 or y1 <= y0 + 1e-5 or z1 <= z0 + 1e-5:
            return
        if faces is None:
            faces = ("+z", "-z", "+x", "-x", "+y", "-y")
        if "+z" in faces:
            self.add_panel_z(x0, y0, x1, y1, z1, "+z", mat_name, region_name, fit_region)
        if "-z" in faces:
            self.add_panel_z(x0, y0, x1, y1, z0, "-z", mat_name, region_name, fit_region)
        if "+x" in faces:
            self.add_panel_x(z0, y0, z1, y1, x1, "+x", mat_name, region_name, fit_region)
        if "-x" in faces:
            self.add_panel_x(z0, y0, z1, y1, x0, "-x", mat_name, region_name, fit_region)
        if "+y" in faces:
            self.add_panel_y(x0, z0, x1, z1, y1, "+y", mat_name, region_name, fit_region)
        if "-y" in faces:
            self.add_panel_y(x0, z0, x1, z1, y0, "-y", mat_name, region_name, fit_region)

    def add_tiled_panel_y(self, x0, z0, x1, z1, y, facing, mat_name, region_name, tile_max=60.0):
        """Tile a large horizontal area into <= tile_max quads so region atlases never overflow."""
        nx = max(1, int(math.ceil((x1 - x0) / tile_max)))
        nz = max(1, int(math.ceil((z1 - z0) / tile_max)))
        dx = (x1 - x0) / nx
        dz = (z1 - z0) / nz
        for ix in range(nx):
            for iz in range(nz):
                self.add_panel_y(
                    x0 + ix * dx, z0 + iz * dz, x0 + (ix + 1) * dx, z0 + (iz + 1) * dz,
                    y, facing, mat_name, region_name
                )

    def add_cylinder(self, cx, y0, y1, cz, r_bot, r_top, segs, mat_name, region_name=None,
                     cap_top=False, cap_bot=False, phase=0.0):
        """Vertical polygonal cylinder / frustum (right-handed CCW outward winding)."""
        for i in range(segs):
            a0 = phase + 2.0 * math.pi * i / segs
            a1 = phase + 2.0 * math.pi * ((i + 1) % segs) / segs
            # Stepping clockwise in XZ (a0 -> a1 with x=cos, z=sin? Wait: check cross product!)
            # With p0=(cx+r*cos(a0), y0, cz+r*sin(a0)) -> p1=(a1, y0) -> p2=(a1, y1) -> p3=(a0, y1):
            # e1 = (-sin, 0, cos), e2 = (0, +1, 0) -> e1 x e2 = (-cos, 0, -sin) INWARD!
            # So swap a0/a1 for outward normal (+cos, 0, +sin)!
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            b0 = (cx + r_bot * c1, y0, cz + r_bot * s1)
            b1 = (cx + r_bot * c0, y0, cz + r_bot * s0)
            t1 = (cx + r_top * c0, y1, cz + r_top * s0)
            t0 = (cx + r_top * c1, y1, cz + r_top * s1)
            if r_top > 0.05:
                self.add_planar_quad_art(b0, b1, t1, t0, mat_name, region_name)
            else:
                self.add_planar_tri_art(b0, b1, (cx, y1, cz), mat_name, region_name)
            if cap_top and r_top > 0.05:
                self.add_planar_tri_art((cx, y1, cz), t0, t1, mat_name, region_name)
            if cap_bot and r_bot > 0.05:
                self.add_planar_tri_art((cx, y0, cz), b1, b0, mat_name, region_name)

    def add_beam_art(self, p0, p1, half_w, half_h, mat_name, region_name=None, caps=False):
        """True 3D structural girder / rod / strut between any two 3D points p0 -> p1."""
        d = v_sub(p1, p0)
        l = v_len(d)
        if l < 1e-4:
            return
        fwd = v_norm(d)
        up_ref = (0.0, 1.0, 0.0) if abs(fwd[1]) < 0.92 else (1.0, 0.0, 0.0)
        right = v_norm(v_cross(fwd, up_ref))
        up = v_cross(right, fwd)
        rw = v_scale(right, half_w)
        uh = v_scale(up, half_h)

        # 4 corners at p0 and p1: 0=(-r,-u), 1=(+r,-u), 2=(+r,+u), 3=(-r,+u)
        def ring(pt):
            return [
                v_add(pt, v_sub(v_scale(rw, -1), uh)),
                v_add(pt, v_sub(rw, uh)),
                v_add(pt, v_add(rw, uh)),
                v_add(pt, v_add(v_scale(rw, -1), uh)),
            ]
        q0 = ring(p0)
        q1 = ring(p1)
        # Check outward winding: right x fwd = -up; for bottom (0->1->q1[1]->q1[0]):
        # e1=(q0[1]-q0[0])=+r, e2=(q1[1]-q0[0])=+r+fwd -> r x fwd = -u (outward bottom!) ✓
        for i in range(4):
            j = (i + 1) % 4
            self.add_planar_quad_art(q0[i], q0[j], q1[j], q1[i], mat_name, region_name)
        if caps:
            self.add_planar_quad_art(q0[3], q0[2], q0[1], q0[0], mat_name, region_name)
            self.add_planar_quad_art(q1[0], q1[1], q1[2], q1[3], mat_name, region_name)

    def add_ring_3d(self, center, u_axis, v_axis, radius, half_rad, half_thick, segs, mat_name, region_name=None):
        """True 3D thick sculpted ring oriented in any 3D plane (u_axis, v_axis)."""
        u = v_norm(u_axis)
        v = v_norm(v_axis)
        n = v_norm(v_cross(u, v))
        r_in = radius - half_rad
        r_out = radius + half_rad
        nh = v_scale(n, half_thick)

        def pt(rad, ang, s_n):
            ca, sa = math.cos(ang), math.sin(ang)
            base = v_add(center, v_add(v_scale(u, rad * ca), v_scale(v, rad * sa)))
            return v_add(base, v_scale(nh, s_n))

        for i in range(segs):
            a0 = 2.0 * math.pi * i / segs
            a1 = 2.0 * math.pi * ((i + 1) % segs) / segs
            # Front (+n) face: (in0 -> out0 -> out1 -> in1) -> e1=rad, e2=tan -> rad x tan = +n ✓
            fi0, fo0, fo1, fi1 = pt(r_in, a0, 1), pt(r_out, a0, 1), pt(r_out, a1, 1), pt(r_in, a1, 1)
            bi0, bo0, bo1, bi1 = pt(r_in, a0, -1), pt(r_out, a0, -1), pt(r_out, a1, -1), pt(r_in, a1, -1)
            self.add_planar_quad_art(fi0, fo0, fo1, fi1, mat_name, region_name)
            self.add_planar_quad_art(bi1, bo1, bo0, bi0, mat_name, region_name)
            # Outer rim (+radial)
            self.add_planar_quad_art(bo0, bo1, fo1, fo0, mat_name, region_name)
            # Inner rim (-radial)
            self.add_planar_quad_art(fi0, fi1, bi1, bi0, mat_name, region_name)

    def add_sphere_faceted(self, center, radius, rings, segs, mat_name, region_name=None):
        """Faceted architectural sphere (for armillary sun core & 94 m Astral Beacon orb!)."""
        cx, cy, cz = center
        for r_i in range(rings):
            phi0 = -math.pi * 0.5 + math.pi * r_i / rings
            phi1 = -math.pi * 0.5 + math.pi * (r_i + 1) / rings
            y0, rr0 = cy + radius * math.sin(phi0), radius * math.cos(phi0)
            y1, rr1 = cy + radius * math.sin(phi1), radius * math.cos(phi1)
            for s_i in range(segs):
                a0 = 2.0 * math.pi * s_i / segs
                a1 = 2.0 * math.pi * ((s_i + 1) % segs) / segs
                c0, sn0 = math.cos(a0), math.sin(a0)
                c1, sn1 = math.cos(a1), math.sin(a1)
                p00 = (cx + rr0 * c1, y0, cz + rr0 * sn1)
                p10 = (cx + rr0 * c0, y0, cz + rr0 * sn0)
                p11 = (cx + rr1 * c0, y1, cz + rr1 * sn0)
                p01 = (cx + rr1 * c1, y1, cz + rr1 * sn1)
                if r_i == 0:
                    self.add_planar_tri_art((cx, cy - radius, cz), p11, p01, mat_name, region_name)
                elif r_i == rings - 1:
                    self.add_planar_tri_art(p00, p10, (cx, cy + radius, cz), mat_name, region_name)
                else:
                    self.add_planar_quad_art(p00, p10, p11, p01, mat_name, region_name)

    def add_barrel_arch(self, cx, cy, cz, axis, r_in, r_out, half_depth, segs, mat_name,
                        spandrel_top_y=None, keystone=True):
        """
        Real 3D thick semicircular triumphal stone barrel vault along axis ('z' or 'x').
        Optionally fills the spandrel masonry cleanly up to spandrel_top_y with ZERO sky gaps!
        """
        def pt(h_coord, v_coord, d_coord):
            if axis == "z":
                return (cx + h_coord, cy + v_coord, cz + d_coord)
            else:
                return (cx + d_coord, cy + v_coord, cz - h_coord)

        for i in range(segs):
            a0 = math.pi * i / segs
            a1 = math.pi * (i + 1) / segs
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            # Front (+d) arch face
            fi0 = pt(c0 * r_in, s0 * r_in, half_depth)
            fo0 = pt(c0 * r_out, s0 * r_out, half_depth)
            fo1 = pt(c1 * r_out, s1 * r_out, half_depth)
            fi1 = pt(c1 * r_in, s1 * r_in, half_depth)
            # Back (-d) arch face
            bi0 = pt(c0 * r_in, s0 * r_in, -half_depth)
            bo0 = pt(c0 * r_out, s0 * r_out, -half_depth)
            bo1 = pt(c1 * r_out, s1 * r_out, -half_depth)
            bi1 = pt(c1 * r_in, s1 * r_in, -half_depth)

            # Outward winding check (axis='z'): h=+x, v=+y, d=+z -> (+x) x (+y) = +z ✓
            # (axis='x'): h=-z, v=+y, d=+x -> (-z) x (+y) = +x ✓
            self.add_planar_quad_art(fi0, fo0, fo1, fi1, mat_name)
            self.add_planar_quad_art(bi1, bo1, bo0, bi0, mat_name)
            # Coffered underside intrados soffit
            self.add_planar_quad_art(bi0, fi0, fi1, bi1, mat_name)
            # Top extrados
            self.add_planar_quad_art(fo0, bo0, bo1, fo1, mat_name)

            # Solid spandrel fill between outer arch ring and rectangular entablature above!
            if spandrel_top_y is not None:
                top_v = spandrel_top_y - cy
                # Outer top corners aligned with x = c0*r_out .. c1*r_out (clamped cleanly to [-r_in, +r_in])
                x0_s = max(-r_in, min(r_in, c0 * r_out))
                x1_s = max(-r_in, min(r_in, c1 * r_out))
                # Use exact arch outer points if within [-r_in, +r_in]
                p_f_a = pt(c0 * r_out, s0 * r_out, half_depth - 2.0)
                p_f_b = pt(c1 * r_out, s1 * r_out, half_depth - 2.0)
                p_f_ta = pt(c0 * r_out, top_v, half_depth - 2.0)
                p_f_tb = pt(c1 * r_out, top_v, half_depth - 2.0)
                if top_v > max(s0 * r_out, s1 * r_out) + 0.2:
                    self.add_planar_quad_art(p_f_a, p_f_ta, p_f_tb, p_f_b, mat_name)
                    p_b_a = pt(c0 * r_out, s0 * r_out, -(half_depth - 2.0))
                    p_b_b = pt(c1 * r_out, s1 * r_out, -(half_depth - 2.0))
                    p_b_ta = pt(c0 * r_out, top_v, -(half_depth - 2.0))
                    p_b_tb = pt(c1 * r_out, top_v, -(half_depth - 2.0))
                    self.add_planar_quad_art(p_b_b, p_b_tb, p_b_ta, p_b_a, mat_name)

        # Sculpted projecting gilded-bronze keystone on BOTH outer & inner faces of the triumphal arch!
        if keystone:
            for sgn in (1.0, -1.0):
                d0 = sgn * (half_depth - 2.0)
                d1 = sgn * (half_depth + 6.0)
                da, db = min(d0, d1), max(d0, d1)
                if axis == "z":
                    self.add_box(cx - 10, cy + r_in - 6, cz + da, cx + 10, cy + r_out + 8, cz + db, "bronze_gilded")
                else:
                    self.add_box(cx + da, cy + r_in - 6, cz - 10, cx + db, cy + r_out + 8, cz + 10, "bronze_gilded")

    def add_open_truss_arch(self, cx, cy, cz, axis, r_in, r_out, half_depth, segs):
        """
        Eiffel-style open-lattice riveted-steel & gilded-bronze structural cathedral arch!
        Twin front/back arched lattice planes tied together with lateral steel cross-braces!
        """
        def pt(h_coord, v_coord, d_coord):
            if axis == "z":
                return (cx + h_coord, cy + v_coord, cz + d_coord)
            else:
                return (cx + d_coord, cy + v_coord, cz - h_coord)

        in_f, out_f, in_b, out_b = [], [], [], []
        for i in range(segs + 1):
            a = math.pi * i / segs
            ca, sa = math.cos(a), math.sin(a)
            in_f.append(pt(ca * r_in, sa * r_in, half_depth))
            out_f.append(pt(ca * r_out, sa * r_out, half_depth))
            in_b.append(pt(ca * r_in, sa * r_in, -half_depth))
            out_b.append(pt(ca * r_out, sa * r_out, -half_depth))

        for i in range(segs):
            # Curved inner & outer steel chords (front and back truss planes)
            self.add_beam_art(in_f[i], in_f[i + 1], 2.4, 2.4, "steel_truss")
            self.add_beam_art(out_f[i], out_f[i + 1], 2.8, 2.8, "steel_truss")
            self.add_beam_art(in_b[i], in_b[i + 1], 2.4, 2.4, "steel_truss")
            self.add_beam_art(out_b[i], out_b[i + 1], 2.8, 2.8, "steel_truss")
            # X-braced diagonal web struts inside every arch bay!
            self.add_beam_art(in_f[i], out_f[i + 1], 1.4, 1.4, "steel_truss")
            self.add_beam_art(out_f[i], in_f[i + 1], 1.4, 1.4, "steel_truss")
            self.add_beam_art(in_b[i], out_b[i + 1], 1.4, 1.4, "steel_truss")
            self.add_beam_art(out_b[i], in_b[i + 1], 1.4, 1.4, "steel_truss")

        for i in range(segs + 1):
            # Radial steel web posts + lateral cross-ties connecting front & back planes
            self.add_beam_art(in_f[i], out_f[i], 1.8, 1.8, "steel_truss")
            self.add_beam_art(in_b[i], out_b[i], 1.8, 1.8, "steel_truss")
            self.add_beam_art(in_f[i], in_b[i], 1.6, 1.6, "steel_truss")
            self.add_beam_art(out_f[i], out_b[i], 1.8, 1.8, "steel_truss")
            # Gilded bronze rivet rosette boss on outer truss joints!
            if 1 <= i <= segs - 1:
                for p_j in (out_f[i], out_b[i]):
                    self.add_box(p_j[0] - 3.2, p_j[1] - 3.2, p_j[2] - 3.2,
                                 p_j[0] + 3.2, p_j[1] + 3.2, p_j[2] + 3.2, "bronze_gilded")

        # Gilded bronze crown crest medallion at apex of open truss arch
        top_f = pt(0.0, r_out + 4.0, half_depth)
        top_b = pt(0.0, r_out + 4.0, -half_depth)
        for tp in (top_f, top_b):
            self.add_box(tp[0] - 6.0, tp[1] - 6.0, tp[2] - 4.0, tp[0] + 6.0, tp[1] + 8.0, tp[2] + 4.0, "bronze_gilded")

    def tri_count(self):
        return sum(len(pr["idx"]) // 3 for pr in self.prims.values())


class CollisionRecorder:
    """Records full-height axis-aligned collision boxes into both the GLB collision mesh and model-info.json."""

    def __init__(self, mat_idx, mat_size, regions):
        self.bucket = MeshBucket("collision", mat_idx, mat_size, regions)
        self.boxes = []

    def add_box(self, x0, y0, z0, x1, y1, z1, source, category="exterior"):
        if x1 <= x0 + 1e-4 or y1 <= y0 + 1e-4 or z1 <= z0 + 1e-4:
            return
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "stone_granite")
        cx = (x0 + x1) * 0.5 * U
        cy_g = (y0 + y1) * 0.5 * U
        cz_g = (z0 + z1) * 0.5 * U
        sx = (x1 - x0) * U
        sy_g = (y1 - y0) * U
        sz_g = (z1 - z0) * U
        self.boxes.append({
            "source": source,
            "category": category,
            "center": [round(cx, 4), round(-cz_g, 4), round(cy_g, 4)],  # Blender Z-up convention
            "size": [round(sx, 4), round(sz_g, 4), round(sy_g, 4)],
            "height_top_m": round(y1 * U, 4),
        })


# ==============================================================================
# REUSABLE INSTANCED ARCHITECTURAL PROPS & WINDOWS
# ==============================================================================
def build_cathedral_window_mesh(name, w_half, h_rect, mat_idx, mat_size, regions, paired=False):
    """Deep-reveal monumental arched cathedral window (facing +Z, sill at y=0, outer wall at z=0).
    Glass sits cleanly at z=+0.55 in front of the solid pylon face so it is NEVER occluded,
    while dressed limestone jambs, sills & arch rings project to z=+6.0 (+0.47 m deep architectural reveal!)."""
    b = MeshBucket(name, mat_idx, mat_size, regions)
    if not paired:
        # Projecting dressed limestone sill + deep stone jambs (0.47 m reveal!) + stone arch + gilded keystone
        b.add_box(-w_half - 5.5, -4, 0.0, w_half + 5.5, 1.8, 7.2, "stone_limestone", faces=["+z", "+y", "-y", "+x", "-x"])
        b.add_box(-w_half - 5.5, 1.8, 0.0, -w_half, h_rect, 5.8, "stone_limestone", faces=["+z", "+x", "-x"])
        b.add_box(w_half, 1.8, 0.0, w_half + 5.5, h_rect, 5.8, "stone_limestone", faces=["+z", "+x", "-x"])
        # Gilded bronze Art Deco transom & central mullion standing cleanly in front of leaded glass
        b.add_box(-w_half, h_rect * 0.50 - 1.4, 0.8, w_half, h_rect * 0.50 + 1.4, 3.4, "bronze_gilded",
                  faces=["+z", "+y", "-y"])
        b.add_box(-1.5, 1.8, 0.8, 1.5, h_rect + w_half - 1.0, 3.2, "bronze_gilded", faces=["+z", "+x", "-x"])
        # 8-segment semicircular stone arch ring
        segs = 8
        r_in, r_out = w_half, w_half + 5.5
        for i in range(segs):
            a0, a1 = math.pi * i / segs, math.pi * (i + 1) / segs
            c0, s0, c1, s1 = math.cos(a0), math.sin(a0), math.cos(a1), math.sin(a1)
            fi0 = (c0 * r_in, h_rect + s0 * r_in, 5.8)
            fo0 = (c0 * r_out, h_rect + s0 * r_out, 5.8)
            fo1 = (c1 * r_out, h_rect + s1 * r_out, 5.8)
            fi1 = (c1 * r_in, h_rect + s1 * r_in, 5.8)
            bi0 = (c0 * r_in, h_rect + s0 * r_in, 0.0)
            bo0 = (c0 * r_out, h_rect + s0 * r_out, 0.0)
            bo1 = (c1 * r_out, h_rect + s1 * r_out, 0.0)
            bi1 = (c1 * r_in, h_rect + s1 * r_in, 0.0)
            b.add_planar_quad_art(fi0, fo0, fo1, fi1, "stone_limestone")
            b.add_planar_quad_art(bi0, fi0, fi1, bi1, "stone_limestone")
            b.add_planar_quad_art(fo0, bo0, bo1, fo1, "stone_limestone")
            # Un-occluded cathedral glass fan at z = +0.55
            b.add_planar_tri_art((0, h_rect, 0.55), (c0 * r_in, h_rect + s0 * r_in, 0.55),
                                 (c1 * r_in, h_rect + s1 * r_in, 0.55), "glass_cathedral", "win_cathedral")
        # Gilded bronze Art Deco keystone
        b.add_box(-3.6, h_rect + r_in - 1.5, 1.0, 3.6, h_rect + r_out + 2.8, 7.2, "bronze_gilded",
                  faces=["+z", "+y", "-y", "+x", "-x"])
        # Un-occluded recessed leaded cathedral glass pane at z = +0.55
        b.add_panel_z(-w_half, 1.8, w_half, h_rect, 0.55, "+z", "glass_cathedral", "win_cathedral")
    else:
        # Paired lancet cathedral windows with center bronze mullion pier!
        tw = w_half * 2 + 4
        b.add_box(-tw - 4.5, -3.5, 0.0, tw + 4.5, 1.8, 6.6, "stone_limestone", faces=["+z", "+y", "-y", "+x", "-x"])
        b.add_box(-tw - 4.5, 1.8, 0.0, -tw, h_rect, 5.4, "stone_limestone", faces=["+z", "+x", "-x"])
        b.add_box(tw, 1.8, 0.0, tw + 4.5, h_rect, 5.4, "stone_limestone", faces=["+z", "+x", "-x"])
        b.add_box(-2.8, 1.8, 0.0, 2.8, h_rect, 6.0, "bronze_gilded", faces=["+z", "+x", "-x"])
        for ox in (-(w_half + 2.5), +(w_half + 2.5)):
            segs = 6
            r_in, r_out = w_half - 1.0, w_half + 3.4
            for i in range(segs):
                a0, a1 = math.pi * i / segs, math.pi * (i + 1) / segs
                c0, s0, c1, s1 = math.cos(a0), math.sin(a0), math.cos(a1), math.sin(a1)
                fi0 = (ox + c0 * r_in, h_rect + s0 * r_in, 5.4)
                fo0 = (ox + c0 * r_out, h_rect + s0 * r_out, 5.4)
                fo1 = (ox + c1 * r_out, h_rect + s1 * r_out, 5.4)
                fi1 = (ox + c1 * r_in, h_rect + s1 * r_in, 5.4)
                bi0 = (ox + c0 * r_in, h_rect + s0 * r_in, 0.0)
                bo0 = (ox + c0 * r_out, h_rect + s0 * r_out, 0.0)
                bo1 = (ox + c1 * r_out, h_rect + s1 * r_out, 0.0)
                bi1 = (ox + c1 * r_in, h_rect + s1 * r_in, 0.0)
                b.add_planar_quad_art(fi0, fo0, fo1, fi1, "stone_limestone")
                b.add_planar_quad_art(bi0, fi0, fi1, bi1, "stone_limestone")
                b.add_planar_quad_art(fo0, bo0, bo1, fo1, "stone_limestone")
                b.add_planar_tri_art((ox, h_rect, 0.55), (ox + c0 * r_in, h_rect + s0 * r_in, 0.55),
                                     (ox + c1 * r_in, h_rect + s1 * r_in, 0.55), "glass_cathedral", "win_cathedral")
            b.add_box(ox - 2.4, h_rect + r_in - 1, 1.0, ox + 2.4, h_rect + r_out + 2.2, 6.6, "bronze_gilded",
                      faces=["+z", "+y", "-y", "+x", "-x"])
            b.add_panel_z(ox - r_in, 1.8, ox + r_in, h_rect, 0.55, "+z", "glass_cathedral", "win_cathedral")
    return b


def build_lamp_artdeco_mesh(mat_idx, mat_size, regions):
    """Art Deco bronze-and-iron pedestal globe lamp (4.8 m tall, compact glowing globe)."""
    b = MeshBucket("lamp_artdeco_inst", mat_idx, mat_size, regions)
    b.add_box(-5.5, 0, -5.5, 5.5, 8, 5.5, "stone_granite")
    b.add_box(-4.0, 8, -4.0, 4.0, 14, 4.0, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 14, 54, 0, 2.2, 1.6, 8, "detail_atlas", region_name="dark_iron")
    b.add_box(-3.2, 53, -3.2, 3.2, 56, 3.2, "bronze_gilded")
    # Compact frosted Art Deco glowing globe (~0.40 m across)
    b.add_box(-2.4, 56, -2.4, 2.4, 61.5, 2.4, "glow_monument", region_name="lamp_globe", fit_region=True)
    b.add_cylinder(0, 61.5, 64.5, 0, 2.8, 0.3, 6, "bronze_gilded")
    return b


def build_brazier_eternal_mesh(mat_idx, mat_size, regions):
    """Monumental eternal-flame bronze tripod brazier on stepped granite pedestal (~5.0 m tall)."""
    b = MeshBucket("brazier_eternal_inst", mat_idx, mat_size, regions)
    b.add_box(-16, 0, -16, 16, 6, 16, "stone_granite")
    b.add_box(-13, 6, -13, 13, 12, 13, "stone_granite")
    b.add_box(-10, 12, -10, 10, 32, 10, "stone_limestone")
    b.add_box(-12, 32, -12, 12, 36, 12, "bronze_gilded")
    # Sculpted dark iron tripod legs & flared gilded bronze fire bowl
    for ang in (0, 2.0944, 4.1888):
        lx, lz = math.cos(ang) * 7.5, math.sin(ang) * 7.5
        b.add_beam_art((lx, 36, lz), (lx * 1.15, 45, lz * 1.15), 1.6, 1.6, "detail_atlas", "dark_iron")
    b.add_cylinder(0, 41, 47, 0, 7.5, 13.0, 10, "bronze_gilded", cap_top=True, cap_bot=True)
    b.add_cylinder(0, 46, 48.5, 0, 13.6, 13.6, 10, "detail_atlas", region_name="dark_iron", cap_top=True)
    # Crossed vertical glowing eternal-flame planes (15x16 art px, pure-black mask outside flame!)
    b.add_panel_z(-7.5, 48.0, 7.5, 64.0, 0.0, "+z", "glow_monument", "flame_brazier", fit_region=True)
    b.add_panel_z(-7.5, 48.0, 7.5, 64.0, 0.0, "-z", "glow_monument", "flame_brazier", fit_region=True)
    b.add_panel_x(-7.5, 48.0, 7.5, 64.0, 0.0, "+x", "glow_monument", "flame_brazier", fit_region=True)
    b.add_panel_x(-7.5, 48.0, 7.5, 64.0, 0.0, "-x", "glow_monument", "flame_brazier", fit_region=True)
    return b


def build_flagpole_civic_mesh(mat_idx, mat_size, regions):
    """Ceremonial 14 m monument flagpole with bronze finial & waving navy-crimson-gold civic banner."""
    b = MeshBucket("flagpole_civic_inst", mat_idx, mat_size, regions)
    b.add_box(-7, 0, -7, 7, 8, 7, "stone_granite")
    b.add_box(-5, 8, -5, 5, 14, 5, "bronze_gilded")
    b.add_cylinder(0, 14, 70, 0, 2.2, 1.8, 8, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 70, 126, 0, 1.8, 1.4, 8, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 126, 178, 0, 1.4, 1.0, 8, "detail_atlas", region_name="dark_iron")
    b.add_sphere_faceted((0, 181, 0), 3.2, 4, 8, "bronze_gilded")
    # Ceremonial flag waving cleanly in +X (double-sided exact 44x26 art px!)
    b.add_panel_z(1.2, 150, 45.2, 176, 0.4, "+z", "inscriptions", "civic_flag", fit_region=True)
    b.add_panel_z(1.2, 150, 45.2, 176, -0.4, "-z", "inscriptions", "civic_flag", fit_region=True)
    return b


def build_bench_plaza_mesh(mat_idx, mat_size, regions):
    """Monumental limestone-end & cedar-slat plaza bench (2.2 m long, facing +Z)."""
    b = MeshBucket("bench_plaza_inst", mat_idx, mat_size, regions)
    for sx in (-12, 9):
        b.add_box(sx, 0, -5, sx + 3, 7, 5, "stone_limestone")
    b.add_box(-14, 6.5, -4.5, 14, 8.5, 4.5, "detail_atlas", region_name="timber_bench")
    b.add_box(-14, 10.5, -5.2, 14, 15.0, -3.6, "detail_atlas", region_name="timber_bench")
    return b


def build_urn_bronze_mesh(mat_idx, mat_size, regions):
    """Sculpted classical bronze-and-verdigris ceremonial urn topping peristyle end piers."""
    b = MeshBucket("urn_bronze_inst", mat_idx, mat_size, regions)
    b.add_box(-9, 0, -9, 9, 4, 9, "stone_limestone")
    b.add_cylinder(0, 4, 9, 0, 7.5, 4.5, 8, "bronze_gilded", cap_top=True)
    b.add_cylinder(0, 9, 20, 0, 4.8, 9.5, 10, "copper_verdigris")
    b.add_cylinder(0, 20, 25, 0, 9.5, 7.2, 10, "bronze_gilded")
    b.add_cylinder(0, 25, 29, 0, 7.2, 9.2, 10, "bronze_gilded", cap_top=True)
    return b


def build_telescope_deck_mesh(mat_idx, mat_size, regions):
    """Brass observation deck coin-op viewing telescope (1.5 m tall, facing +Z)."""
    b = MeshBucket("telescope_deck_inst", mat_idx, mat_size, regions)
    b.add_cylinder(0, 0, 2.5, 0, 4.0, 3.2, 8, "detail_atlas", region_name="dark_iron", cap_top=True)
    b.add_cylinder(0, 2.5, 17.0, 0, 1.4, 1.1, 8, "detail_atlas", region_name="dark_iron")
    b.add_beam_art((0, 18.0, -5.5), (0, 19.2, 6.5), 1.6, 1.6, "bronze_gilded", caps=True)
    return b


# ==============================================================================
# GLTF 2.0 BINARY (.GLB) PACKER
# ==============================================================================
def write_glb(path, materials_spec, meshes_list, nodes_list):
    bin_buf = bytearray()
    buffer_views = []
    accessors = []
    images_gltf = []
    textures_gltf = []
    materials_gltf = []

    def append_bytes(raw_bytes, target=None):
        while len(bin_buf) % 4 != 0:
            bin_buf.append(0)
        off = len(bin_buf)
        bin_buf.extend(raw_bytes)
        bv = {"buffer": 0, "byteOffset": off, "byteLength": len(raw_bytes)}
        if target is not None:
            bv["target"] = target
        idx = len(buffer_views)
        buffer_views.append(bv)
        return idx

    img_cache = {}

    def register_tex(png_name):
        if png_name in img_cache:
            return img_cache[png_name]
        raw = (TEX_DIR / png_name).read_bytes()
        bv_idx = append_bytes(raw)
        img_idx = len(images_gltf)
        images_gltf.append({"bufferView": bv_idx, "mimeType": "image/png"})
        tex_idx = len(textures_gltf)
        textures_gltf.append({"sampler": 0, "source": img_idx})
        img_cache[png_name] = tex_idx
        return tex_idx

    for m in materials_spec:
        base_tex = register_tex(m["base"])
        mat = {
            "name": m["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": base_tex},
                "metallicFactor": m.get("metallic", 0.0),
                "roughnessFactor": m.get("roughness", 0.90),
            },
        }
        if m.get("alphaMode"):
            mat["alphaMode"] = m["alphaMode"]
        if m.get("doubleSided"):
            mat["doubleSided"] = True
        if m.get("emissive"):
            em_tex = register_tex(m["emissive"])
            mat["emissiveTexture"] = {"index": em_tex}
            mat["emissiveFactor"] = m.get("emissiveFactor", [1.0, 1.0, 1.0])
        materials_gltf.append(mat)

    meshes_gltf = []
    for bucket, extras in meshes_list:
        prims_gltf = []
        for mat_i in sorted(bucket.prims.keys()):
            pr = bucket.prims[mat_i]
            if not pr["idx"]:
                continue
            pos_b = bytearray()
            nrm_b = bytearray()
            uv_b = bytearray()
            mn = [1e9, 1e9, 1e9]
            mx = [-1e9, -1e9, -1e9]
            for v in pr["pos"]:
                pos_b.extend(struct.pack("<3f", *v))
                for k in range(3):
                    mn[k] = min(mn[k], v[k])
                    mx[k] = max(mx[k], v[k])
            for n in pr["norm"]:
                nrm_b.extend(struct.pack("<3f", *n))
            for uv in pr["uv"]:
                uv_b.extend(struct.pack("<2f", *uv))
            idx_b = bytearray()
            for ii in pr["idx"]:
                idx_b.extend(struct.pack("<I", ii))

            bv_pos = append_bytes(pos_b, 34962)
            bv_nrm = append_bytes(nrm_b, 34962)
            bv_uv = append_bytes(uv_b, 34962)
            bv_idx = append_bytes(idx_b, 34963)

            acc_pos = len(accessors)
            accessors.append({
                "bufferView": bv_pos, "componentType": 5126, "count": len(pr["pos"]),
                "type": "VEC3", "min": mn, "max": mx,
            })
            acc_nrm = len(accessors)
            accessors.append({
                "bufferView": bv_nrm, "componentType": 5126, "count": len(pr["norm"]), "type": "VEC3",
            })
            acc_uv = len(accessors)
            accessors.append({
                "bufferView": bv_uv, "componentType": 5126, "count": len(pr["uv"]), "type": "VEC2",
            })
            acc_idx = len(accessors)
            accessors.append({
                "bufferView": bv_idx, "componentType": 5125, "count": len(pr["idx"]), "type": "SCALAR",
            })
            prims_gltf.append({
                "attributes": {"POSITION": acc_pos, "NORMAL": acc_nrm, "TEXCOORD_0": acc_uv},
                "indices": acc_idx,
                "material": mat_i,
            })
        m_obj = {"name": bucket.name, "primitives": prims_gltf}
        if extras:
            m_obj["extras"] = extras
        meshes_gltf.append(m_obj)

    while len(bin_buf) % 4 != 0:
        bin_buf.append(0)

    root_nodes = [i for i, n in enumerate(nodes_list) if not any(i in p.get("children", []) for p in nodes_list)]
    gltf = {
        "asset": {"version": "2.0", "generator": "great-meridian-monument-builder"},
        "scene": 0,
        "scenes": [{"name": "GreatMeridianMonument", "nodes": root_nodes}],
        "nodes": nodes_list,
        "meshes": meshes_gltf,
        "materials": materials_gltf,
        "textures": textures_gltf,
        "images": images_gltf,
        "samplers": [{"magFilter": 9728, "minFilter": 9728, "wrapS": 10497, "wrapT": 10497}],
        "accessors": accessors,
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(bin_buf)}],
    }

    j_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(j_bytes) % 4 != 0:
        j_bytes += b" "
    total_len = 12 + 8 + len(j_bytes) + 8 + len(bin_buf)
    header = struct.pack("<3I", 0x46546C67, 2, total_len)
    j_chunk = struct.pack("<2I", len(j_bytes), 0x4E4F534A) + j_bytes
    b_chunk = struct.pack("<2I", len(bin_buf), 0x004E4942) + bytes(bin_buf)
    Path(path).write_bytes(header + j_chunk + b_chunk)
    return total_len


# ==============================================================================
# MATERIAL TABLE (12 MATERIALS, STRICTLY NAMED BY ROLE)
# ==============================================================================
MATERIALS_SPEC = [
    {"name": "stone_limestone", "base": "stone_limestone.png", "roughness": 0.88},
    {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.90},
    {"name": "steel_truss", "base": "steel_truss.png", "metallic": 0.45, "roughness": 0.58},
    {"name": "copper_verdigris", "base": "copper_verdigris.png", "metallic": 0.28, "roughness": 0.62},
    {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.62, "roughness": 0.42},
    {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
    {"name": "marble_plaza", "base": "marble_plaza.png", "roughness": 0.72},
    {"name": "roof_slate", "base": "roof_slate.png", "roughness": 0.82},
    {"name": "glass_cathedral", "base": "glass_cathedral.png", "alphaMode": "BLEND", "roughness": 0.22, "doubleSided": True},
    {"name": "detail_atlas", "base": "detail_atlas.png", "roughness": 0.86},
    {"name": "inscriptions", "base": "inscriptions.png", "roughness": 0.86},
    {"name": "glow_monument", "base": "glow_monument_base.png", "emissive": "glow_monument_mask.png",
     "emissiveFactor": [1.0, 1.0, 1.0], "roughness": 0.45, "doubleSided": True},
]

MAT_SIZE = {
    "stone_limestone": (64, 64),
    "stone_granite": (64, 64),
    "steel_truss": (64, 64),
    "copper_verdigris": (64, 64),
    "bronze_gilded": (64, 64),
    "water_pool": (64, 64),
    "marble_plaza": (64, 64),
    "roof_slate": (64, 64),
    "glass_cathedral": (128, 128),
    "detail_atlas": (128, 128),
    "inscriptions": (256, 128),
    "glow_monument": (64, 32),
}


# ==============================================================================
# BUILD FULL MONUMENT: great-meridian-monument.glb
# ==============================================================================
def build_monument():
    regions = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    mat_idx = {m["name"]: i for i, m in enumerate(MATERIALS_SPEC)}

    structure = MeshBucket("structure", mat_idx, MAT_SIZE, regions)
    armillary = MeshBucket("armillary_sphere", mat_idx, MAT_SIZE, regions)
    lot_ground = MeshBucket("lot_ground", mat_idx, MAT_SIZE, regions)
    lot_peristyles = MeshBucket("lot_peristyles", mat_idx, MAT_SIZE, regions)
    coll = CollisionRecorder(mat_idx, MAT_SIZE, regions)

    # Footprint: 1546 x 1546 art px (120.0 m x 120.0 m), origin (0,0,0) at +Z front curb midpoint!
    HALF = 773.0
    CZ = -773.0  # -59.9848 m exact center of monument & grounds
    Y_GND = 2.0
    Y_T1 = 28.0  # Tier 1 terrace (2.17 m)
    Y_T2 = 56.0  # Tier 2 stylobate (4.35 m)
    Y_T3 = 84.0  # Tier 3 Grand Compass Rose Rotunda floor (6.52 m)

    # ==========================================================================
    # 1. CEREMONIAL GROUNDS, PARK LAWNS & GRAND REFLECTING POOL
    # ==========================================================================
    # Base ceremonial plinth apron (y = 0..2 so nothing floats below y=0!)
    lot_ground.add_box(-HALF, 0, -2 * HALF, HALF, Y_GND, 0, "stone_granite", faces=["+z", "-z", "+x", "-x"])
    # Outer marble perimeter promenade + broad central cross avenues
    lot_ground.add_panel_y(-HALF, -2 * HALF, HALF, 0, Y_GND, "+y", "marble_plaza")

    # 4 Manicured Ceremonial Park Lawns framing the outer corners (tiled <=60 art px for detail_atlas!)
    lawn_rects = [
        (-740, -520, -220, -40),     # SW front lawn
        (220, -520, 740, -40),       # SE front lawn
        (-740, -1506, -220, -1040),  # NW rear lawn
        (220, -1506, 740, -1040),    # NE rear lawn
        (-740, -1040, -610, -520),   # Far West lawn strip
        (610, -1040, 740, -520),     # Far East lawn strip
    ]
    for lx0, lz0, lx1, lz1 in lawn_rects:
        lot_ground.add_tiled_panel_y(lx0, lz0, lx1, lz1, Y_GND + 0.35, "+y", "detail_atlas", "lawn_turf", 60.0)
        # Low dressed granite lawn curb trim
        lot_ground.add_box(lx0 - 4, Y_GND, lz0 - 4, lx1 + 4, Y_GND + 3.0, lz0, "stone_granite")
        lot_ground.add_box(lx0 - 4, Y_GND, lz1, lx1 + 4, Y_GND + 3.0, lz1 + 4, "stone_granite")
        lot_ground.add_box(lx0 - 4, Y_GND, lz0, lx0, Y_GND + 3.0, lz1, "stone_granite")
        lot_ground.add_box(lx1, Y_GND, lz0, lx1 + 4, Y_GND + 3.0, lz1, "stone_granite")

    # Low ceremonial perimeter granite boundary wall with broad entrance openings on all 4 sides
    for seg0, seg1 in ((-773, -210), (210, 773)):
        lot_ground.add_box(seg0, Y_GND, -10, seg1, 14, 0, "stone_granite")
        coll.add_box(seg0, 0, -10, seg1, 14, 0, f"perimeter_wall_s_{seg0}", "lot")
        lot_ground.add_box(seg0, Y_GND, -1546, seg1, 14, -1536, "stone_granite")
        coll.add_box(seg0, 0, -1546, seg1, 14, -1536, f"perimeter_wall_n_{seg0}", "lot")
        lot_ground.add_box(-773, Y_GND, CZ + seg0, -763, 14, CZ + seg1, "stone_granite")
        coll.add_box(-773, 0, CZ + seg0, -763, 14, CZ + seg1, f"perimeter_wall_w_{seg0}", "lot")
        lot_ground.add_box(763, Y_GND, CZ + seg0, 773, 14, CZ + seg1, "stone_granite")
        coll.add_box(763, 0, CZ + seg0, 773, 14, CZ + seg1, f"perimeter_wall_e_{seg0}", "lot")
    # Monumental entrance piers flanking the +Z main avenue gate
    for gx in (-214, 214):
        lot_ground.add_box(gx - 12, Y_GND, -16, gx + 12, 34, 0, "stone_limestone")
        lot_ground.add_box(gx - 14, 34, -18, gx + 14, 40, 2, "bronze_gilded")
        coll.add_box(gx - 12, 0, -16, gx + 12, 40, 0, f"entry_gate_pier_{gx}", "lot")

    # GRAND REFLECTING POOL (South approach axis: X in [-96, +96], Z in [-430, -56] -> 14.9 m x 29.0 m!)
    rp_x, rp_z0, rp_z1 = 96.0, -430.0, -56.0
    lot_ground.add_box(-rp_x - 10, Y_GND, rp_z0 - 10, rp_x + 10, 10.5, rp_z0, "stone_granite")
    lot_ground.add_box(-rp_x - 10, Y_GND, rp_z1, rp_x + 10, 10.5, rp_z1 + 10, "stone_granite")
    lot_ground.add_box(-rp_x - 10, Y_GND, rp_z0, -rp_x, 10.5, rp_z1, "stone_granite")
    lot_ground.add_box(rp_x, Y_GND, rp_z0, rp_x + 10, 10.5, rp_z1, "stone_granite")
    # Deep cerulean reflecting water surface + 3 sculptural gilded-bronze fountain basins down pool axis!
    lot_ground.add_panel_y(-rp_x, rp_z0, rp_x, rp_z1, 7.2, "+y", "water_pool")
    for fz in (-340, -243, -146):
        lot_ground.add_cylinder(0, 7.2, 13.0, fz, 10.0, 6.0, 8, "stone_granite", cap_top=True)
        lot_ground.add_cylinder(0, 13.0, 19.0, fz, 4.0, 9.0, 8, "bronze_gilded", cap_top=True)
        lot_ground.add_cylinder(0, 19.0, 25.0, fz, 2.0, 0.5, 6, "bronze_gilded")
    coll.add_box(-rp_x - 10, 0, rp_z0 - 10, rp_x + 10, 12, rp_z1 + 10, "grand_reflecting_pool", "lot")

    # ==========================================================================
    # 2. TWIN SWEEPING PERISTYLE COLONNADE WINGS (West & East embracing arcs!)
    #    Each wing: 7-bay classical curved arc of 12 fluted 10 m columns + entablature + bronze urns!
    # ==========================================================================
    urn_positions = []
    for side_s, tag in ((-1, "west"), (1, "east")):
        # Arc center offset so the concave classical arms embrace the central acropolis!
        arc_cx = side_s * 340.0
        arc_cz = CZ
        r_mid = 235.0
        n_bays = 7
        a_start = math.radians(-58.0) if side_s > 0 else math.radians(122.0)
        a_end = math.radians(58.0) if side_s > 0 else math.radians(238.0)
        col_pts = []
        for i in range(n_bays + 1):
            ang = a_start + (a_end - a_start) * (i / n_bays)
            col_pts.append((arc_cx + math.cos(ang) * r_mid, arc_cz + math.sin(ang) * r_mid, ang))

        for i in range(n_bays):
            x0, z0, a0 = col_pts[i]
            x1, z1, a1 = col_pts[i + 1]
            # Radial outward vectors for thick curved stylobate steps & curved entablature lintel!
            n0 = (math.cos(a0), math.sin(a0))
            n1 = (math.cos(a1), math.sin(a1))

            def add_arc_tier(r_in_off, r_out_off, y_lo, y_hi, mat_n):
                p0_in = (x0 + n0[0] * r_in_off, y_lo, z0 + n0[1] * r_in_off)
                p1_in = (x1 + n1[0] * r_in_off, y_lo, z1 + n1[1] * r_in_off)
                p0_out = (x0 + n0[0] * r_out_off, y_lo, z0 + n0[1] * r_out_off)
                p1_out = (x1 + n1[0] * r_out_off, y_lo, z1 + n1[1] * r_out_off)
                t0_in = (p0_in[0], y_hi, p0_in[2])
                t1_in = (p1_in[0], y_hi, p1_in[2])
                t0_out = (p0_out[0], y_hi, p0_out[2])
                t1_out = (p1_out[0], y_hi, p1_out[2])
                # Winding: for east (a increases from -58 to +58, clockwise in XZ -> tangent x +y = +outward!)
                # Let's determine order cleanly from side_s!
                # Actually a increases monotonically in both west and east (a_end > a_start)!
                # Tangent d/da = (-sin a, 0, +cos a); cross(tangent, +y) = (+cos a, 0, +sin a) = +outward!
                # Wait: (p0_out -> p1_out -> t1_out -> t0_out): e1=tangent, e2=+y -> e1 x e2 = +outward ✓
                # Wait: check (p0 -> p1) x (p2 - p0) = tangent x (+y) = (-sin,0,cos) x (0,1,0) = (-cos,0,-sin) = INWARD!!
                # So outward face MUST go (p1_out -> p0_out -> t0_out -> t1_out)!
                lot_peristyles.add_planar_quad_art(p1_out, p0_out, t0_out, t1_out, mat_n)
                lot_peristyles.add_planar_quad_art(p0_in, p1_in, t1_in, t0_in, mat_n)
                # Top (+y): (t0_in -> t1_in -> t1_out -> t0_out)? e1=tangent, e2=outward -> tangent x outward = -y!
                # So +y top goes (t0_out -> t1_out -> t1_in -> t0_in)!
                lot_peristyles.add_planar_quad_art(t0_out, t1_out, t1_in, t0_in, mat_n)
                lot_peristyles.add_planar_quad_art(p0_in, p1_in, p1_out, p0_out, mat_n)

            # 3-step granite stylobate base (y = 2..16)
            add_arc_tier(-34, 34, Y_GND, 7, "stone_granite")
            add_arc_tier(-28, 28, 7, 12, "stone_granite")
            add_arc_tier(-22, 22, 12, 16, "stone_granite")
            # Curved limestone entablature + bronze frieze band + verdigris copper crest cap!
            add_arc_tier(-22, 22, 146, 168, "stone_limestone")
            add_arc_tier(-24, 24, 168, 175, "bronze_gilded")
            add_arc_tier(-18, 18, 175, 184, "copper_verdigris")

        # Fluted 10-sided limestone columns & heavy monumental end piers with bronze urns!
        for i, (cx_p, cz_p, ang_p) in enumerate(col_pts):
            if i == 0 or i == n_bays:
                # Heavy monumental limestone end pylon anchoring each peristyle wing
                lot_peristyles.add_box(cx_p - 22, Y_GND, cz_p - 22, cx_p + 22, 172, cz_p + 22, "stone_limestone")
                lot_peristyles.add_box(cx_p - 25, 172, cz_p - 25, cx_p + 25, 180, cz_p + 25, "bronze_gilded")
                lot_peristyles.add_box(cx_p - 19, 180, cz_p - 19, cx_p + 19, 190, cz_p + 19, "stone_limestone")
                urn_positions.append((cx_p, 190.0, cz_p))
                coll.add_box(cx_p - 22, 0, cz_p - 22, cx_p + 22, 218, cz_p + 22, f"peristyle_{tag}_endpier_{i}", "lot")
            else:
                # Tall fluted limestone column (y = 16..146 = 10.1 m!) + base + gilded capital!
                lot_peristyles.add_box(cx_p - 12, 16, cz_p - 12, cx_p + 12, 24, cz_p + 12, "stone_granite")
                lot_peristyles.add_cylinder(cx_p, 24, 138, cz_p, 8.8, 7.6, 10, "stone_limestone")
                lot_peristyles.add_box(cx_p - 12, 138, cz_p - 12, cx_p + 12, 146, cz_p + 12, "bronze_gilded")
                coll.add_box(cx_p - 12, 0, cz_p - 12, cx_p + 12, 184, cz_p + 12, f"peristyle_{tag}_col_{i}", "lot")

    # ==========================================================================
    # 3. THREE-TIER CEREMONIAL ACROPOLIS PLINTH + 4 SWEEPING MONUMENTAL STAIRCASES
    # ==========================================================================
    # Tier 1 Terrace (y = 2..28): X in [-390, 390], Z in [CZ-390, CZ+340]
    structure.add_box(-390, Y_GND, CZ - 390, 390, Y_T1, CZ + 340, "stone_granite")
    structure.add_panel_y(-390, CZ - 390, 390, CZ + 340, Y_T1 + 0.1, "+y", "marble_plaza")
    # Tier 2 Stylobate (y = 28..56): X in [-340, 340], Z in [CZ-340, CZ+305]
    structure.add_box(-340, Y_T1, CZ - 340, 340, Y_T2, CZ + 305, "stone_granite")
    structure.add_panel_y(-340, CZ - 340, 340, CZ + 305, Y_T2 + 0.1, "+y", "marble_plaza")
    # Tier 3 Grand Sanctuary Platform (y = 56..84): X in [-294, 294], Z in [CZ-294, CZ+294]
    structure.add_box(-294, Y_T2, CZ - 294, 294, Y_T3, CZ + 294, "stone_limestone")
    # Dark bronze trim molding along top of Tier 3 sanctuary plinth
    structure.add_box(-297, Y_T3 - 4, CZ - 297, 297, Y_T3, CZ + 297, "bronze_gilded", faces=["+z", "-z", "+x", "-x"])

    # Perimeter stone balustrades along Tier 1 terrace corners (leaving clear openings for the 4 grand staircases!)
    for sx_a, sx_b in ((-390, -110), (110, 390)):
        structure.add_box(sx_a, Y_T1, CZ + 332, sx_b, Y_T1 + 14, CZ + 340, "stone_limestone")
        structure.add_box(sx_a, Y_T1, CZ - 390, sx_b, Y_T1 + 14, CZ - 382, "stone_limestone")
        coll.add_box(sx_a, 0, CZ + 332, sx_b, Y_T1 + 14, CZ + 340, f"acropolis_balustrade_s_{sx_a}", "exterior")
        coll.add_box(sx_a, 0, CZ - 390, sx_b, Y_T1 + 14, CZ - 382, f"acropolis_balustrade_n_{sx_a}", "exterior")
    for sz_a, sz_b in ((CZ - 390, CZ - 110), (CZ + 110, CZ + 340)):
        structure.add_box(-390, Y_T1, sz_a, -382, Y_T1 + 14, sz_b, "stone_limestone")
        structure.add_box(382, Y_T1, sz_a, 390, Y_T1 + 14, sz_b, "stone_limestone")
        coll.add_box(-390, 0, sz_a, -382, Y_T1 + 14, sz_b, f"acropolis_balustrade_w_{int(sz_a)}", "exterior")
        coll.add_box(382, 0, sz_a, 390, Y_T1 + 14, sz_b, f"acropolis_balustrade_e_{int(sz_a)}", "exterior")

    # 4 SWEEPING MONUMENTAL STAIRCASES (South +Z front is extra wide 180 art px = 14.0 m!)
    def add_grand_stair(side, half_w, z_top_dist, steps_n=14, step_run=9.5):
        dy = (Y_T3 - Y_GND) / steps_n
        for st in range(steps_n):
            y1 = Y_GND + (st + 1) * dy
            d_outer = z_top_dist + (steps_n - st) * step_run
            d_inner = d_outer - step_run
            if side == "+z":
                structure.add_box(-half_w, Y_GND, CZ + d_inner, half_w, y1, CZ + d_outer, "stone_granite")
                if st % 2 == 0:
                    coll.add_box(-half_w, 0, CZ + d_inner, half_w, y1, CZ + d_outer + step_run, f"stair_s_{st}", "steps")
            elif side == "-z":
                structure.add_box(-half_w, Y_GND, CZ - d_outer, half_w, y1, CZ - d_inner, "stone_granite")
                if st % 2 == 0:
                    coll.add_box(-half_w, 0, CZ - d_outer - step_run, half_w, y1, CZ - d_inner, f"stair_n_{st}", "steps")
            elif side == "+x":
                structure.add_box(d_inner, Y_GND, CZ - half_w, d_outer, y1, CZ + half_w, "stone_granite")
                if st % 2 == 0:
                    coll.add_box(d_inner, 0, CZ - half_w, d_outer + step_run, y1, CZ + half_w, f"stair_e_{st}", "steps")
            elif side == "-x":
                structure.add_box(-d_outer, Y_GND, CZ - half_w, -d_inner, y1, CZ + half_w, "stone_granite")
                if st % 2 == 0:
                    coll.add_box(-d_outer - step_run, 0, CZ - half_w, -d_inner, y1, CZ + half_w, f"stair_w_{st}", "steps")
        # Sculpted flanking granite cheek walls with bronze caps along each grand staircase!
        d_end = z_top_dist + steps_n * step_run
        for sgn in (-1, 1):
            c0 = sgn * (half_w + (0 if sgn > 0 else 16))
            c1 = c0 + 16
            if side == "+z":
                structure.add_box(c0, Y_GND, CZ + z_top_dist, c1, Y_T3 + 6, CZ + z_top_dist + 44, "stone_limestone")
                structure.add_box(c0, Y_GND, CZ + z_top_dist + 44, c1, Y_T2 + 4, CZ + z_top_dist + 90, "stone_limestone")
                structure.add_box(c0, Y_GND, CZ + z_top_dist + 90, c1, Y_T1 + 4, CZ + d_end, "stone_limestone")
                coll.add_box(c0, 0, CZ + z_top_dist, c1, Y_T3 + 6, CZ + d_end, f"stair_cheek_s_{sgn}", "exterior")
            elif side == "-z":
                structure.add_box(c0, Y_GND, CZ - z_top_dist - 44, c1, Y_T3 + 6, CZ - z_top_dist, "stone_limestone")
                structure.add_box(c0, Y_GND, CZ - d_end, c1, Y_T2 + 4, CZ - z_top_dist - 44, "stone_limestone")
                coll.add_box(c0, 0, CZ - d_end, c1, Y_T3 + 6, CZ - z_top_dist, f"stair_cheek_n_{sgn}", "exterior")
            elif side == "+x":
                structure.add_box(z_top_dist, Y_GND, CZ + c0, z_top_dist + 44, Y_T3 + 6, CZ + c1, "stone_limestone")
                structure.add_box(z_top_dist + 44, Y_GND, CZ + c0, d_end, Y_T2 + 4, CZ + c1, "stone_limestone")
                coll.add_box(z_top_dist, 0, CZ + c0, d_end, Y_T3 + 6, CZ + c1, f"stair_cheek_e_{sgn}", "exterior")
            elif side == "-x":
                structure.add_box(-z_top_dist - 44, Y_GND, CZ + c0, -z_top_dist, Y_T3 + 6, CZ + c1, "stone_limestone")
                structure.add_box(-d_end, Y_GND, CZ + c0, -z_top_dist - 44, Y_T2 + 4, CZ + c1, "stone_limestone")
                coll.add_box(-d_end, 0, CZ + c0, -z_top_dist, Y_T3 + 6, CZ + c1, f"stair_cheek_w_{sgn}", "exterior")

    add_grand_stair("+z", 94.0, 294.0, steps_n=14, step_run=9.5)
    add_grand_stair("-z", 80.0, 294.0, steps_n=12, step_run=9.5)
    add_grand_stair("+x", 80.0, 294.0, steps_n=12, step_run=9.5)
    add_grand_stair("-x", 80.0, 294.0, steps_n=12, step_run=9.5)

    # ==========================================================================
    # 4. WALKABLE FLOOR MESHES (COMPASS ROSE ROTUNDA + PYLON HALLS + 53 m SKY DECK!)
    # ==========================================================================
    floor_buckets = {}

    # A. Open-Air Grand Compass Rose Rotunda Floor (y = Y_T3 = 84 = 6.52 m!)
    fb_rot = MeshBucket("floor_rotunda_plaza", mat_idx, MAT_SIZE, regions)
    fb_rot.add_panel_y(-294, CZ - 294, 294, CZ + 294, Y_T3 + 0.15, "+y", "marble_plaza")
    # Giant inlaid 8-Point Gilded-Bronze & Navy-Slate Compass Rose Medallion (192x192 art px = 14.9 m!) right under the Armillary Sphere!
    for gx in (-96, -32, 32):
        for gz in (-96, -32, 32):
            if gx == -32 and gz == -32:
                fb_rot.add_panel_y(-32, CZ - 32, 32, CZ + 32, Y_T3 + 0.45, "+y", "detail_atlas", "compass_mosaic")
            else:
                fb_rot.add_panel_y(gx, CZ + gz, gx + 64, CZ + gz + 64, Y_T3 + 0.30, "+y", "roof_slate")
    # Four radial inlaid gilded-bronze meridian compass rays extending across the rotunda floor!
    fb_rot.add_panel_y(-6, CZ - 280, 6, CZ + 280, Y_T3 + 0.40, "+y", "bronze_gilded")
    fb_rot.add_panel_y(-280, CZ - 6, 280, CZ + 6, Y_T3 + 0.40, "+y", "bronze_gilded")
    # Decorative octagonal raised gilded compass curb ring around center star
    fb_rot.add_ring_3d((0, Y_T3 + 0.8, CZ), (1, 0, 0), (0, 0, -1), 104.0, 5.0, 0.8, 16, "bronze_gilded")
    floor_buckets["floor_rotunda_plaza"] = fb_rot

    # B. Walkable West & East Memorial Halls inside SW/SE Pylon Bases (y = Y_T3)
    for tag, sx in (("hall_west", -1), ("hall_east", 1)):
        fb = MeshBucket(f"floor_{tag}", mat_idx, MAT_SIZE, regions)
        px0 = -270 if sx < 0 else 130
        px1 = -130 if sx < 0 else 270
        fb.add_panel_y(px0, CZ + 130, px1, CZ + 270, Y_T3 + 0.25, "+y", "marble_plaza")
        floor_buckets[f"floor_{tag}"] = fb

    # C. Walkable Observatory Lift Lobbies inside NW/NE Pylon Bases (y = Y_T3)
    for tag, sx in (("stairwell_nw", -1), ("stairwell_ne", 1)):
        fb = MeshBucket(f"floor_{tag}", mat_idx, MAT_SIZE, regions)
        px0 = -270 if sx < 0 else 130
        px1 = -130 if sx < 0 else 270
        fb.add_panel_y(px0, CZ - 270, px1, CZ - 130, Y_T3 + 0.25, "+y", "marble_plaza")
        floor_buckets[f"floor_{tag}"] = fb

    # ==========================================================================
    # 5. FOUR COLOSSAL BATTERED ART DECO / BEAUX-ARTS CATHEDRAL PYLONS
    #    Inner edges fixed at +/-110 (creating a 17.07 m clear triumphal crossing on all 4 axes!)
    #    Outer flanks taper dramatically in 4 setbacks: 290 -> 278 -> 256 -> 236 up to y = 668 (51.8 m)!
    # ==========================================================================
    window_instances = []

    for sx in (-1, 1):
        for sz in (-1, 1):
            ptag = f"{'e' if sx > 0 else 'w'}{'s' if sz > 0 else 'n'}"

            def p_rect(outer):
                xa = 110.0 if sx > 0 else -outer
                xb = outer if sx > 0 else -110.0
                za = CZ + (110.0 if sz > 0 else -outer)
                zb = CZ + (outer if sz > 0 else -110.0)
                return xa, xb, za, zb

            # --- TIER A: Rusticated Granite Base Socle (y = 84..168, outer = 290) ---
            # Hollowed cleanly inside (walls thickness 20 art px) with open arched doorway facing the central rotunda!
            x0, x1, z0, z1 = p_rect(290.0)
            T = 20.0
            # Outer walls (solid)
            if sx > 0:
                structure.add_box(x1 - T, Y_T3, z0, x1, 168, z1, "stone_granite")
                # Inner X wall (facing central rotunda) with open arched bronze portal (width 40, height 56)!
                zm = (z0 + z1) * 0.5
                structure.add_box(x0, Y_T3, z0, x0 + T, 168, zm - 20, "stone_granite")
                structure.add_box(x0, Y_T3, zm + 20, x0 + T, 168, z1, "stone_granite")
                structure.add_box(x0, Y_T3 + 56, zm - 20, x0 + T, 168, zm + 20, "stone_granite")
                # Bronze portal frame
                structure.add_box(x0 - 2, Y_T3, zm - 23, x0 + 2, Y_T3 + 59, zm - 20, "bronze_gilded")
                structure.add_box(x0 - 2, Y_T3, zm + 20, x0 + 2, Y_T3 + 59, zm + 23, "bronze_gilded")
                structure.add_box(x0 - 2, Y_T3 + 56, zm - 23, x0 + 2, Y_T3 + 61, zm + 23, "bronze_gilded")
            else:
                structure.add_box(x0, Y_T3, z0, x0 + T, 168, z1, "stone_granite")
                zm = (z0 + z1) * 0.5
                structure.add_box(x1 - T, Y_T3, z0, x1, 168, zm - 20, "stone_granite")
                structure.add_box(x1 - T, Y_T3, zm + 20, x1, 168, z1, "stone_granite")
                structure.add_box(x1 - T, Y_T3 + 56, zm - 20, x1, 168, zm + 20, "stone_granite")
                structure.add_box(x1 - 2, Y_T3, zm - 23, x1 + 2, Y_T3 + 59, zm - 20, "bronze_gilded")
                structure.add_box(x1 - 2, Y_T3, zm + 20, x1 + 2, Y_T3 + 59, zm + 23, "bronze_gilded")
                structure.add_box(x1 - 2, Y_T3 + 56, zm - 23, x1 + 2, Y_T3 + 61, zm + 23, "bronze_gilded")
            # North & South base walls of each pylon
            structure.add_box(x0 + T, Y_T3, z0, x1 - T, 168, z0 + T, "stone_granite")
            structure.add_box(x0 + T, Y_T3, z1 - T, x1 - T, 168, z1, "stone_granite")
            # Interior ceiling & molded bronze cornice belt at y = 168..178
            structure.add_box(x0 - 4, 166, z0 - 4, x1 + 4, 176, z1 + 4, "bronze_gilded")
            structure.add_box(x0, 176, z0, x1, 184, z1, "stone_limestone")

            # Eye-height cast bronze historical plaque + chiseled "1926" cornerstone on front South pylons!
            if sz > 0 and sx < 0:
                structure.add_panel_z(x1 - 36, Y_T3 + 14, x1 - 20, Y_T3 + 26, z1 + 0.5, "+z",
                                      "inscriptions", "plaque_dedication", fit_region=True)
            if sz > 0 and sx > 0:
                structure.add_panel_z(x0 + 20, Y_T3 + 14, x0 + 44, Y_T3 + 26, z1 + 0.5, "+z",
                                      "inscriptions", "cornerstone_1926", fit_region=True)

            # Tiny warm architectural uplight lenses tucked at outer base corners of each pylon!
            ux = x1 + 4 if sx > 0 else x0 - 4
            uz = z1 + 4 if sz > 0 else z0 - 4
            structure.add_box(ux - 4, Y_T3, uz - 4, ux + 4, Y_T3 + 3.5, uz + 4, "detail_atlas", region_name="dark_iron")
            structure.add_panel_y(ux - 3, uz - 3, ux + 3, uz + 3, Y_T3 + 3.6, "+y", "glow_monument", "flood_lens", fit_region=True)

            # Full-height collision for each colossal pylon (up to Sky Deck y = 736 = 57.1 m)!
            coll.add_box(x0, 0, z0, x1, 736, z1, f"cathedral_pylon_{ptag}", "exterior")

            # --- TIER B: Lower Fluted Limestone Shaft (y = 184..394, outer = 278) ---
            bx0, bx1, bz0, bz1 = p_rect(278.0)
            structure.add_box(bx0, 184, bz0, bx1, 394, bz1, "stone_limestone")
            # Sculpted vertical Art Deco fluted limestone pilasters flanking the tall cathedral windows!
            for off_f in (18, 46, 122, 150):
                fx = bx0 + off_f
                fz = bz0 + off_f
                # +Z/-Z outer face flutes
                z_out = bz1 if sz > 0 else bz0 - 6
                structure.add_box(fx - 5, 184, z_out, fx + 5, 394, z_out + 6, "stone_limestone")
                # +X/-X outer face flutes
                x_out = bx1 if sx > 0 else bx0 - 6
                structure.add_box(x_out, 184, fz - 5, x_out + 6, 394, fz + 5, "stone_limestone")
            # Bold molded limestone & gilded-bronze setback cornice at y = 390..405
            structure.add_box(bx0 - 6, 390, bz0 - 6, bx1 + 6, 398, bz1 + 6, "stone_limestone")
            structure.add_box(bx0 - 8, 398, bz0 - 8, bx1 + 8, 405, bz1 + 8, "bronze_gilded")

            # Tall deep-reveal arched cathedral windows on BOTH outer faces of Tier B!
            mid_bx = (bx0 + bx1) * 0.5
            mid_bz = (bz0 + bz1) * 0.5
            window_instances.append(("tall", (mid_bx, 224.0, bz1 if sz > 0 else bz0), 0.0 if sz > 0 else math.pi))
            window_instances.append(("tall", (bx1 if sx > 0 else bx0, 224.0, mid_bz), math.pi * 0.5 if sx > 0 else -math.pi * 0.5))

            # Carved limestone & gilded bronze Art Deco sunburst medallions on upper outer cheeks!
            if sz > 0:
                structure.add_panel_z(mid_bx - 28, 326, mid_bx + 28, 382, bz1 + 0.6, "+z", "inscriptions", "relief_sunburst", fit_region=True)
            else:
                structure.add_panel_z(mid_bx - 28, 326, mid_bx + 28, 382, bz0 - 0.6, "-z", "inscriptions", "relief_sunburst", fit_region=True)

            # --- TIER C: Slenderized Middle Cathedral Gallery Shaft (y = 405..588, outer = 238) ---
            cx0, cx1, cz0, cz1 = p_rect(238.0)
            structure.add_box(cx0, 405, cz0, cx1, 588, cz1, "stone_limestone")
            # Corner Art Deco gilded-bronze & verdigris obelisk pinnacle standing proudly on Tier B outer shoulder!
            px_pin = (bx1 - 16) if sx > 0 else (bx0 + 16)
            pz_pin = (bz1 - 16) if sz > 0 else (bz0 + 16)
            structure.add_box(px_pin - 12, 405, pz_pin - 12, px_pin + 12, 464, pz_pin + 12, "stone_limestone")
            structure.add_box(px_pin - 13, 464, pz_pin - 13, px_pin + 13, 472, pz_pin + 13, "bronze_gilded")
            structure.add_cylinder(px_pin, 472, 508, pz_pin, 12.5, 0.4, 4, "copper_verdigris", phase=math.pi * 0.25)
            # Paired arched lancet cathedral windows on outer faces of Tier C!
            mid_cx = (cx0 + cx1) * 0.5
            mid_cz = (cz0 + cz1) * 0.5
            window_instances.append(("paired", (mid_cx, 452.0, cz1 if sz > 0 else cz0), 0.0 if sz > 0 else math.pi))
            window_instances.append(("paired", (cx1 if sx > 0 else cx0, 452.0, mid_cz), math.pi * 0.5 if sx > 0 else -math.pi * 0.5))
            # Tier C setback cornice
            structure.add_box(cx0 - 5, 584, cz0 - 5, cx1 + 5, 592, cz1 + 5, "bronze_gilded")

            # --- TIER D: Slenderized Upper Crown Pier Shaft (y = 592..736, outer = 216) ---
            dx0, dx1, dz0, dz1 = p_rect(216.0)
            structure.add_box(dx0, 592, dz0, dx1, 736, dz1, "stone_limestone")
            # Corner vertical bronze fins accentuating the soaring Art Deco height!
            fx_c = (dx1 - 4) if sx > 0 else (dx0 + 4)
            fz_c = (dz1 - 4) if sz > 0 else (dz0 + 4)
            structure.add_box(fx_c - 6, 592, fz_c - 6, fx_c + 6, 748, fz_c + 6, "bronze_gilded")
            # Sculpted Verdigris-Copper Corner Turret Caps framing the 58.2 m Sky Observation Deck (y = 736..838)!
            tx_c = (dx1 - 20) if sx > 0 else (dx0 + 20)
            tz_c = (dz1 - 20) if sz > 0 else (dz0 + 20)
            structure.add_box(tx_c - 20, 736, tz_c - 20, tx_c + 20, 762, tz_c + 20, "stone_limestone")
            structure.add_box(tx_c - 22, 762, tz_c - 22, tx_c + 22, 768, tz_c + 22, "bronze_gilded")
            structure.add_cylinder(tx_c, 768, 818, tz_c, 22.0, 1.8, 4, "copper_verdigris", phase=math.pi * 0.25)
            structure.add_cylinder(tx_c, 818, 838, tz_c, 2.2, 0.3, 4, "bronze_gilded", phase=math.pi * 0.25)

    # ==========================================================================
    # 6. FOUR COLOSSAL COFFERED TRIUMPHAL BARREL-VAULTED ARCHES + CARVED FRIEZES
    #    17.07 m clear opening (r_in = 110) springing at y = 216 -> crown y = 326!
    # ==========================================================================
    Y_ARCH_SPRING = 216.0
    Y_FRIEZE_BOT = 354.0
    Y_FRIEZE_TOP = 398.0
    ARCH_Z_OFF = 194.0  # centered on outer pylon thickness (110..278 -> mid = 194, half_depth = 78!)

    # South (+Z) & North (-Z) Triumphal Barrel Vaults + Flush Carved Limestone Friezes!
    for sz_s in (1, -1):
        az = CZ + sz_s * ARCH_Z_OFF
        structure.add_barrel_arch(0.0, Y_ARCH_SPRING, az, "z", 110.0, 134.0, 78.0, 12,
                                  "stone_limestone", spandrel_top_y=Y_FRIEZE_BOT, keystone=True)
        # Continuous monumental entablature lintel & bronze deck band spanning between pylons (y = 354..405)
        structure.add_box(-112, Y_FRIEZE_BOT, az - 78, 112, Y_FRIEZE_TOP, az + 78, "stone_limestone")
        structure.add_box(-118, Y_FRIEZE_TOP, az - 82, 118, Y_FRIEZE_TOP + 7, az + 82, "bronze_gilded")
        # Flush chiseled-into-limestone frieze: "THE GREAT MERIDIAN / DEDICATED MCMXXVI" (NO sign plate frame!)
        z_f = az + 78.25 if sz_s > 0 else az - 78.25
        structure.add_panel_z(-118, Y_FRIEZE_BOT + 6, 118, Y_FRIEZE_BOT + 38, z_f,
                              "+z" if sz_s > 0 else "-z", "inscriptions", "frieze_main", fit_region=True)

    # East (+X) & West (-X) Triumphal Barrel Vaults + Cardinal Friezes!
    for sx_s, reg_card in ((1, "frieze_east"), (-1, "frieze_west")):
        ax = sx_s * ARCH_Z_OFF
        structure.add_barrel_arch(ax, Y_ARCH_SPRING, CZ, "x", 110.0, 134.0, 78.0, 12,
                                  "stone_limestone", spandrel_top_y=Y_FRIEZE_BOT, keystone=True)
        structure.add_box(ax - 78, Y_FRIEZE_BOT, CZ - 112, ax + 78, Y_FRIEZE_TOP, CZ + 112, "stone_limestone")
        structure.add_box(ax - 82, Y_FRIEZE_TOP, CZ - 118, ax + 82, Y_FRIEZE_TOP + 7, CZ + 118, "bronze_gilded")
        x_f = ax + 78.25 if sx_s > 0 else ax - 78.25
        structure.add_panel_x(CZ - 90, Y_FRIEZE_BOT + 10, CZ + 90, Y_FRIEZE_BOT + 34, x_f,
                              "+x" if sx_s > 0 else "-x", "inscriptions", reg_card, fit_region=True)

    # ==========================================================================
    # 7. FOUR SOARING EIFFEL-STYLE OPEN-TRUSS RIVETED-STEEL CATHEDRAL ARCHES
    #    + TWO DIAGONAL CROSSING VAULT RIBS & GILDED CELESTIAL OCULUS RING!
    #    Huge sky-filled open upper cathedral vault (y = 405..736 = 25.7 m clear airy height!)
    # ==========================================================================
    Y_TRUSS_SPRING = 582.0
    for sz_s in (1, -1):
        az = CZ + sz_s * 164.0
        structure.add_open_truss_arch(0.0, Y_TRUSS_SPRING, az, "z", 108.0, 134.0, 18.0, 12)
        # Vertical steel tension posts tying upper arch crown cleanly to Sky Deck entablature (y = 732)
        for ox in (-88, -54, -22, 22, 54, 88):
            y_arch = Y_TRUSS_SPRING + math.sqrt(max(1.0, 134.0 ** 2 - ox ** 2))
            if y_arch < 728.0:
                structure.add_beam_art((ox, y_arch, az + 18), (ox, 730, az + 18), 1.8, 1.8, "steel_truss")
                structure.add_beam_art((ox, y_arch, az - 18), (ox, 730, az - 18), 1.8, 1.8, "steel_truss")

    for sx_s in (1, -1):
        ax = sx_s * 164.0
        structure.add_open_truss_arch(ax, Y_TRUSS_SPRING, CZ, "x", 108.0, 134.0, 18.0, 12)
        for oz in (-88, -54, -22, 22, 54, 88):
            y_arch = Y_TRUSS_SPRING + math.sqrt(max(1.0, 134.0 ** 2 - oz ** 2))
            if y_arch < 728.0:
                structure.add_beam_art((ax + 18, y_arch, CZ + oz), (ax + 18, 730, CZ + oz), 1.8, 1.8, "steel_truss")
                structure.add_beam_art((ax - 18, y_arch, CZ + oz), (ax - 18, 730, CZ + oz), 1.8, 1.8, "steel_truss")

    # TWO COLOSSAL DIAGONAL CROSSING VAULT RIBS leaping from pylon to pylon across the open cathedral!
    for diag_sgn in (-1.0, 1.0):
        segs_d = 14
        r_diag = 168.0
        pts_d = []
        for i in range(segs_d + 1):
            a = math.pi * i / segs_d
            h_d = math.cos(a) * r_diag
            v_d = 550.0 + math.sin(a) * r_diag
            pts_d.append((h_d * 0.7071, v_d, CZ + diag_sgn * h_d * 0.7071))
        for i in range(segs_d):
            structure.add_beam_art(pts_d[i], pts_d[i + 1], 3.4, 3.4, "steel_truss")
            if i % 2 == 1:
                structure.add_box(pts_d[i][0] - 4, pts_d[i][1] - 4, pts_d[i][2] - 4,
                                  pts_d[i][0] + 4, pts_d[i][1] + 4, pts_d[i][2] + 4, "bronze_gilded")

    # Sculpted Gilded-Bronze Celestial Oculus Ring suspended in the crossing ceiling (y = 720)
    structure.add_ring_3d((0, 720, CZ), (1, 0, 0), (0, 0, -1), 76.0, 8.0, 4.0, 16, "bronze_gilded")

    # ==========================================================================
    # 8. THE HEART OF THE MONUMENT: 15.2-METRE SUSPENDED CELESTIAL ARMILLARY SPHERE!
    #    Separate named node `armillary_sphere` pivoted at (0, 528 art px = 40.97 m, CZ)!
    #    196 art px (15.21 m) outer diameter — floating cleanly in the sky-filled upper steel vault!
    # ==========================================================================
    Y_ARM = 528.0
    # Built in LOCAL space centered at (0, 0, 0) so the game can even slowly rotate it!
    # 1) Outer Prime Meridian Ring (vertical N-S plane: u=(0,0,1), v=(0,1,0)) — Verdigris Copper!
    armillary.add_ring_3d((0, 0, 0), (0, 0, 1), (0, 1, 0), 96.0, 5.8, 4.4, 24, "copper_verdigris")
    # 2) East-West Colure Ring (vertical E-W plane: u=(1,0,0), v=(0,1,0)) — Gilded Bronze!
    armillary.add_ring_3d((0, 0, 0), (1, 0, 0), (0, 1, 0), 90.0, 5.2, 4.0, 24, "bronze_gilded")
    # 3) Horizontal Equatorial Ring (u=(1,0,0), v=(0,0,-1)) — Wide Gilded Bronze with 12 compass spurs!
    armillary.add_ring_3d((0, 0, 0), (1, 0, 0), (0, 0, -1), 94.0, 6.2, 4.6, 24, "bronze_gilded")
    for hi in range(12):
        ang = 2.0 * math.pi * hi / 12.0
        p_in = (math.cos(ang) * 98.0, 0.0, math.sin(ang) * 98.0)
        p_out = (math.cos(ang) * 114.0, 0.0, math.sin(ang) * 114.0)
        armillary.add_beam_art(p_in, p_out, 2.4, 2.4, "bronze_gilded", caps=True)
    # 4) Tilted 23.5-degree Ecliptic Zodiac Band (wide gilded bronze belt + 12 verdigris star bosses!)
    tilt = math.radians(23.5)
    u_ec = (math.cos(tilt), math.sin(tilt), 0.0)
    v_ec = (0.0, 0.0, -1.0)
    armillary.add_ring_3d((0, 0, 0), u_ec, v_ec, 82.0, 4.6, 8.8, 24, "bronze_gilded")
    for zi in range(12):
        ang = 2.0 * math.pi * zi / 12.0
        pt_z = v_add(v_scale(u_ec, 86.5 * math.cos(ang)), v_scale(v_ec, 86.5 * math.sin(ang)))
        armillary.add_box(pt_z[0] - 3.6, pt_z[1] - 3.6, pt_z[2] - 3.6,
                          pt_z[0] + 3.6, pt_z[1] + 3.6, pt_z[2] + 3.6, "copper_verdigris")
    # 5) Inner Astrolabe Ring (tilted counter-angle, verdigris copper)
    u_in = (math.cos(-tilt), math.sin(-tilt), 0.0)
    armillary.add_ring_3d((0, 0, 0), u_in, (0, 1, 0), 56.0, 4.0, 3.2, 20, "copper_verdigris")
    # 6) Central Faceted Gilded Bronze Sun Core (r = 17 art px = 1.32 m) + 8 Sunburst Rays!
    armillary.add_sphere_faceted((0, 0, 0), 17.0, 6, 12, "bronze_gilded")
    for si in range(8):
        ang = 2.0 * math.pi * si / 8.0
        armillary.add_beam_art((math.cos(ang) * 15, 0, math.sin(ang) * 15),
                               (math.cos(ang) * 34, 0, math.sin(ang) * 34), 2.0, 2.0, "bronze_gilded", caps=True)
    # 7) Colossal 20 m Tilted Polar Axis Spear piercing cleanly through the sphere!
    axis_dir = (-math.sin(tilt), math.cos(tilt), 0.0)
    p_bot_ax = v_scale(axis_dir, -126.0)
    p_top_ax = v_scale(axis_dir, 128.0)
    armillary.add_beam_art(p_bot_ax, p_top_ax, 3.0, 3.0, "bronze_gilded", caps=True)
    # Sculpted arrow-finial spearhead at polar top + counterweight orb at bottom!
    armillary.add_sphere_faceted(v_scale(axis_dir, -120.0), 8.0, 4, 8, "copper_verdigris")
    armillary.add_beam_art(v_scale(axis_dir, 116.0), v_scale(axis_dir, 146.0), 5.2, 1.5, "bronze_gilded", caps=True)
    armillary.add_beam_art(v_scale(axis_dir, 116.0), v_scale(axis_dir, 146.0), 1.5, 5.2, "bronze_gilded", caps=True)

    # 8 Rigid Riveted-Steel Suspension Tie-Rods anchoring the Armillary Sphere cleanly into the 4 Pylons!
    for sx in (-1, 1):
        for sz in (-1, 1):
            structure.add_beam_art((sx * 64, Y_ARM + 64, CZ + sz * 64), (sx * 116, 608, CZ + sz * 116), 2.2, 2.2, "steel_truss")
            structure.add_beam_art((sx * 64, Y_ARM - 44, CZ + sz * 64), (sx * 116, 460, CZ + sz * 116), 2.0, 2.0, "steel_truss")

    # ==========================================================================
    # 9. GRAND CROSSING CORNICE, 58.2 m WALKABLE SKY OBSERVATION DECK,
    #    OCTAGONAL ART DECO SKY LANTERN, STARBURST CROWN & 105.5 m ASTRAL BEACON!
    # ==========================================================================
    Y_DECK = 750.0  # 58.20 m Sky Observation Deck floor!

    # Heavy cantilevered limestone & bronze crossing entablature spanning all 4 pylons (y = 724..750)
    # Left with an open central viewing oculus (X, Z in [-70, +70]) looking straight down onto the Armillary Sphere!
    for (x0, z0, x1, z1) in (
        (-224, CZ - 224, 224, CZ - 70),
        (-224, CZ + 70, 224, CZ + 224),
        (-224, CZ - 70, -70, CZ + 70),
        (70, CZ - 70, 224, CZ + 70),
    ):
        structure.add_box(x0, 724, z0, x1, 744, z1, "stone_limestone")
    # Projecting gilded-bronze cornice band around the entire Sky Deck edge!
    structure.add_box(-230, 744, CZ - 230, 230, 750, CZ - 218, "bronze_gilded")
    structure.add_box(-230, 744, CZ + 218, 230, 750, CZ + 230, "bronze_gilded")
    structure.add_box(-230, 744, CZ - 218, -218, 750, CZ + 218, "bronze_gilded")
    structure.add_box(218, 744, CZ - 218, 230, 750, CZ + 218, "bronze_gilded")

    # Walkable 360-degree Sky Observation Deck floor mesh (`floor_observation_deck` at y = 750 = 58.20 m!)
    fb_deck = MeshBucket("floor_observation_deck", mat_idx, MAT_SIZE, regions)
    for (x0, z0, x1, z1) in (
        (-218, CZ - 218, 218, CZ - 70),
        (-218, CZ + 70, 218, CZ + 218),
        (-218, CZ - 70, -70, CZ + 70),
        (70, CZ - 70, 218, CZ + 70),
    ):
        fb_deck.add_panel_y(x0, z0, x1, z1, Y_DECK + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_observation_deck"] = fb_deck

    # OPEN WROUGHT-IRON & GILDED-BRONZE SKY DECK SAFETY RAILING (unobstructed 360-degree views!)
    # Low 4 art px limestone curb + sleek gilded top handrail at Y_DECK + 14.5 + slender balusters every 16 art px!
    for seg_a, seg_b in ((-216, 216),):
        structure.add_box(seg_a, Y_DECK, CZ + 212, seg_b, Y_DECK + 4.0, CZ + 218, "stone_limestone")
        structure.add_box(seg_a, Y_DECK + 13.5, CZ + 213, seg_b, Y_DECK + 16.0, CZ + 217, "bronze_gilded")
        structure.add_box(seg_a, Y_DECK, CZ - 218, seg_b, Y_DECK + 4.0, CZ - 212, "stone_limestone")
        structure.add_box(seg_a, Y_DECK + 13.5, CZ - 217, seg_b, Y_DECK + 16.0, CZ - 213, "bronze_gilded")
        structure.add_box(-218, Y_DECK, CZ + seg_a, -212, Y_DECK + 4.0, CZ + seg_b, "stone_limestone")
        structure.add_box(-217, Y_DECK + 13.5, CZ + seg_a, -213, Y_DECK + 16.0, CZ + seg_b, "bronze_gilded")
        structure.add_box(212, Y_DECK, CZ + seg_a, 218, Y_DECK + 4.0, CZ + seg_b, "stone_limestone")
        structure.add_box(213, Y_DECK + 13.5, CZ + seg_a, 217, Y_DECK + 16.0, CZ + seg_b, "bronze_gilded")
    for p_off in range(-192, 193, 24):
        for s_z in (CZ + 215, CZ - 215):
            structure.add_box(p_off - 1.4, Y_DECK + 4.0, s_z - 1.4, p_off + 1.4, Y_DECK + 13.5, s_z + 1.4,
                              "detail_atlas", region_name="dark_iron")
        for s_x in (-215, 215):
            structure.add_box(s_x - 1.4, Y_DECK + 4.0, CZ + p_off - 1.4, s_x + 1.4, Y_DECK + 13.5, CZ + p_off + 1.4,
                              "detail_atlas", region_name="dark_iron")
    # Inner oculus circular railing (visitors can look right down onto the giant 15.2 m Armillary Sphere!)
    structure.add_ring_3d((0, Y_DECK + 14.5, CZ), (1, 0, 0), (0, 0, -1), 72.0, 2.5, 2.5, 16, "bronze_gilded")
    for oi in range(16):
        oa = 2.0 * math.pi * oi / 16.0
        ox, oz = math.cos(oa) * 72.0, CZ + math.sin(oa) * 72.0
        structure.add_box(ox - 1.5, Y_DECK, oz - 1.5, ox + 1.5, Y_DECK + 14.5, oz + 1.5, "detail_atlas", region_name="dark_iron")

    # 360-DEGREE ARCHED SKY OBSERVATION COLONNADE DRUM (y = 750..892, 16 fluted octagonal-ring piers!)
    R_OBS = 166.0
    for i in range(16):
        ang = 2.0 * math.pi * i / 16.0 + math.pi / 16.0
        px = math.cos(ang) * R_OBS
        pz = CZ + math.sin(ang) * R_OBS
        # Fluted limestone observation colonnade pier + gilded capital
        structure.add_cylinder(px, Y_DECK, 868, pz, 10.5, 9.4, 8, "stone_limestone")
        structure.add_box(px - 12, 868, pz - 12, px + 12, 878, pz + 12, "bronze_gilded")
        # Stone arch tie lintel to next pier
        ang2 = 2.0 * math.pi * ((i + 1) % 16) / 16.0 + math.pi / 16.0
        px2, pz2 = math.cos(ang2) * R_OBS, CZ + math.sin(ang2) * R_OBS
        structure.add_beam_art((px, 872, pz), (px2, 872, pz2), 7.0, 5.8, "stone_limestone")
    # Central octagonal elevator/stair core inside the Observation Deck
    structure.add_cylinder(0, Y_DECK + 48, 890, CZ, 106.0, 106.0, 8, "stone_limestone", phase=math.pi / 8.0)
    # Stepped Octagonal Observation Crown Entablature & Verdigris-Copper Collar Roof (y = 876..940)
    structure.add_cylinder(0, 876, 898, CZ, 182.0, 182.0, 16, "stone_limestone", cap_top=True, cap_bot=True)
    structure.add_cylinder(0, 898, 906, CZ, 186.0, 186.0, 16, "bronze_gilded", cap_top=True)
    structure.add_cylinder(0, 906, 940, CZ, 174.0, 124.0, 8, "copper_verdigris", cap_top=True, phase=math.pi / 8.0)

    # OCTAGONAL ART DECO CATHEDRAL SKY LANTERN DRUM (y = 940..1100 = 72.9 m..85.4 m!)
    # 8 soaring glowing golden-amber stained-glass lancets framed by 8 dramatic stepped flying buttress fins!
    R_LAN = 110.0
    structure.add_cylinder(0, 940, 1094, CZ, R_LAN - 4, R_LAN - 4, 8, "stone_limestone", phase=math.pi / 8.0)
    for i in range(8):
        # Corner flying buttress fin angle
        a_cor = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        ca, sa = math.cos(a_cor), math.sin(a_cor)
        # Stepped Art Deco limestone + gilded-bronze radial buttress fin flaring dramatically outward!
        for r_lo, r_hi, y_lo, y_hi, mat_f in (
            (96, 140, 934, 1006, "stone_limestone"),
            (96, 128, 1006, 1064, "stone_limestone"),
            (94, 118, 1064, 1118, "bronze_gilded"),
        ):
            p_a = (ca * r_lo, (y_lo + y_hi) * 0.5, CZ + sa * r_lo)
            p_b = (ca * r_hi, (y_lo + y_hi) * 0.5, CZ + sa * r_hi)
            structure.add_beam_art(p_a, p_b, 8.2, (y_hi - y_lo) * 0.5, mat_f, caps=True)
        # Tall golden-amber leaded Art Deco lancet window centered on each of the 8 flat octagon faces!
        a_face = 2.0 * math.pi * i / 8.0
        fc, fs = math.cos(a_face), math.sin(a_face)
        tx, tz = -fs, fc
        r_win = (R_LAN - 4) * math.cos(math.pi / 8.0) + 1.2
        hw_w = 22.0
        p_bl = (fc * r_win + tx * hw_w, 954, CZ + fs * r_win + tz * hw_w)
        p_br = (fc * r_win - tx * hw_w, 954, CZ + fs * r_win - tz * hw_w)
        p_tr = (fc * r_win - tx * hw_w, 1082, CZ + fs * r_win - tz * hw_w)
        p_tl = (fc * r_win + tx * hw_w, 1082, CZ + fs * r_win + tz * hw_w)
        structure.add_planar_quad_art(p_bl, p_br, p_tr, p_tl, "glass_cathedral", "win_lantern")
        # Gilded bronze Art Deco chevron crown + compact glowing diamond lantern jewel on each face!
        p_peak = (fc * (r_win + 6), 1106, CZ + fs * (r_win + 6))
        structure.add_beam_art((p_tl[0], 1082, p_tl[2]), p_peak, 3.2, 3.2, "bronze_gilded", caps=True)
        structure.add_beam_art((p_tr[0], 1082, p_tr[2]), p_peak, 3.2, 3.2, "bronze_gilded", caps=True)
        j_r = r_win + 2.4
        j_hw = 5.5
        structure.add_planar_quad_art(
            (fc * j_r + tx * j_hw, 1084, CZ + fs * j_r + tz * j_hw),
            (fc * j_r - tx * j_hw, 1084, CZ + fs * j_r - tz * j_hw),
            (fc * j_r - tx * j_hw, 1096, CZ + fs * j_r - tz * j_hw),
            (fc * j_r + tx * j_hw, 1096, CZ + fs * j_r + tz * j_hw),
            "glow_monument", "lamp_globe", fit_region=True
        )

    # 8-POINT VERDIGRIS-COPPER & GILDED STARBURST CROWN (y = 1094..1168 = 84.9 m..90.6 m!)
    structure.add_cylinder(0, 1094, 1114, CZ, 116.0, 116.0, 8, "bronze_gilded", cap_top=True, phase=math.pi / 8.0)
    structure.add_cylinder(0, 1114, 1152, CZ, 104.0, 68.0, 8, "copper_verdigris", cap_top=True, phase=math.pi / 8.0)
    # 8 dramatic projecting gilded-bronze Art Deco crown rays / starburst horns!
    for i in range(8):
        ang = 2.0 * math.pi * i / 8.0
        p0_r = (math.cos(ang) * 86.0, 1116.0, CZ + math.sin(ang) * 86.0)
        p1_r = (math.cos(ang) * 120.0, 1174.0, CZ + math.sin(ang) * 120.0)
        structure.add_beam_art(p0_r, p1_r, 4.2, 4.2, "bronze_gilded", caps=True)

    # SOARING TAPERED VERDIGRIS-COPPER RIBBED NEEDLE SPIRE (y = 1152..1294 = 89.4 m..100.4 m!)
    structure.add_cylinder(0, 1152, 1294, CZ, 66.0, 14.0, 8, "copper_verdigris", cap_top=True, phase=math.pi / 8.0)
    # 8 raised gilded-bronze vertical corner spire ribs + 3 horizontal bronze collar rings!
    for i in range(8):
        ang = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        structure.add_beam_art(
            (math.cos(ang) * 67.0, 1152.0, CZ + math.sin(ang) * 67.0),
            (math.cos(ang) * 15.0, 1294.0, CZ + math.sin(ang) * 15.0),
            2.4, 2.4, "bronze_gilded"
        )
    for y_col, r_col in ((1188, 54.0), (1226, 39.0), (1262, 25.0)):
        structure.add_cylinder(0, y_col, y_col + 5, CZ, r_col, r_col, 8, "bronze_gilded", phase=math.pi / 8.0)

    # OPEN BRONZE BEACON CAGE, 105.5 m ASTRAL BEACON ORB & LIGHTNING FINIAL (y = 1294..1360 = 105.54 m!)
    structure.add_cylinder(0, 1292, 1298, CZ, 22.0, 22.0, 8, "bronze_gilded", cap_top=True)
    for i in range(8):
        ang = 2.0 * math.pi * i / 8.0
        structure.add_beam_art(
            (math.cos(ang) * 18.0, 1298.0, CZ + math.sin(ang) * 18.0),
            (math.cos(ang) * 11.0, 1328.0, CZ + math.sin(ang) * 11.0),
            1.6, 1.6, "bronze_gilded"
        )
    structure.add_cylinder(0, 1326, 1332, CZ, 14.0, 6.0, 8, "bronze_gilded", cap_top=True)
    # Radiant Glowing Astral Beacon Jewel Orb (r = 11.5 art px = 0.89 m!)
    structure.add_sphere_faceted((0, 1312, CZ), 11.5, 6, 10, "glow_monument", "beacon_orb")
    # Slender gilded bronze lightning spearhead finial reaching 1360 art px = 105.54 m!
    structure.add_cylinder(0, 1332, 1360, CZ, 3.8, 0.4, 6, "bronze_gilded")

    # ==========================================================================
    # 10. ASSEMBLE REUSABLE INSTANCED MESHES & SCENE GRAPH NODES
    # ==========================================================================
    win_tall_mesh = build_cathedral_window_mesh("win_cathedral_tall", 20.0, 62.0, mat_idx, MAT_SIZE, regions, paired=False)
    win_paired_mesh = build_cathedral_window_mesh("win_cathedral_paired", 13.0, 52.0, mat_idx, MAT_SIZE, regions, paired=True)
    lamp_mesh = build_lamp_artdeco_mesh(mat_idx, MAT_SIZE, regions)
    brazier_mesh = build_brazier_eternal_mesh(mat_idx, MAT_SIZE, regions)
    flag_mesh = build_flagpole_civic_mesh(mat_idx, MAT_SIZE, regions)
    bench_mesh = build_bench_plaza_mesh(mat_idx, MAT_SIZE, regions)
    urn_mesh = build_urn_bronze_mesh(mat_idx, MAT_SIZE, regions)
    tele_mesh = build_telescope_deck_mesh(mat_idx, MAT_SIZE, regions)

    meshes_list = []

    def reg(bucket, extras=None):
        idx = len(meshes_list)
        meshes_list.append((bucket, extras))
        return idx

    idx_structure = reg(structure)
    idx_armillary = reg(armillary)
    idx_lot_ground = reg(lot_ground)
    idx_lot_peristyles = reg(lot_peristyles)
    idx_collision = reg(coll.bucket, {"collision_only": True})
    idx_win_tall = reg(win_tall_mesh)
    idx_win_paired = reg(win_paired_mesh)
    idx_lamp = reg(lamp_mesh)
    idx_brazier = reg(brazier_mesh)
    idx_flag = reg(flag_mesh)
    idx_bench = reg(bench_mesh)
    idx_urn = reg(urn_mesh)
    idx_tele = reg(tele_mesh)

    floor_mesh_idx = {fn: reg(fb) for fn, fb in floor_buckets.items()}

    nodes = []

    def add_node(name, mesh=None, translation=None, rotation=None, children=None, extras=None):
        n = {"name": name}
        if mesh is not None:
            n["mesh"] = mesh
        if translation is not None:
            n["translation"] = [round(v, 5) for v in translation]
        if rotation is not None:
            n["rotation"] = [round(v, 6) for v in rotation]
        if children is not None:
            n["children"] = children
        if extras is not None:
            n["extras"] = extras
        idx = len(nodes)
        nodes.append(n)
        return idx

    # Primary structure + suspended Armillary Sphere (separate pivoted node!) + collision
    add_node("structure", mesh=idx_structure)
    add_node("armillary_sphere", mesh=idx_armillary, translation=[0.0, Y_ARM * U, CZ * U],
             extras={"role": "suspended_celestial_armillary_sphere", "diameter_m": round(196 * U, 2)})
    add_node("collision", mesh=idx_collision, extras={"collision_only": True})

    # Named walkable floor nodes
    for fn, mi in floor_mesh_idx.items():
        add_node(fn, mesh=mi)

    # Instanced deep-reveal cathedral windows on all 4 pylons
    for wi, (kind, (wx, wy, wz), rot_y) in enumerate(window_instances):
        mi = idx_win_tall if kind == "tall" else idx_win_paired
        add_node(f"cathedral_window_{wi + 1:02d}", mesh=mi,
                 translation=[wx * U, wy * U, wz * U],
                 rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None)

    # 4 Brass Viewing Telescopes on the 58.2 m Sky Observation Deck (facing N, S, E, W)
    for ti, (tx, tz, rot_y) in enumerate((
        (0, CZ + 196, 0.0),
        (0, CZ - 196, math.pi),
        (196, CZ, math.pi * 0.5),
        (-196, CZ, -math.pi * 0.5),
    )):
        add_node(f"sky_telescope_{ti + 1:02d}", mesh=idx_tele,
                 translation=[tx * U, Y_DECK * U, tz * U],
                 rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None)

    # Lot group children (reflecting pool grounds, peristyle wings, 4 eternal flame braziers,
    # 12 civic flagpoles, 16 Art Deco globe lamps, 12 plaza benches, 4 bronze peristyle urns)
    lot_children = [
        add_node("lot_ground", mesh=idx_lot_ground),
        add_node("lot_peristyles", mesh=idx_lot_peristyles),
    ]

    # 4 Corner Eternal-Flame Bronze Braziers on Tier 1 Acropolis corners!
    for bi, (bx, bz) in enumerate((
        (-360, CZ + 312), (360, CZ + 312), (-360, CZ - 360), (360, CZ - 360),
    )):
        lot_children.append(add_node(f"eternal_brazier_{bi + 1:02d}", mesh=idx_brazier,
                                     translation=[bx * U, Y_T1 * U, bz * U]))
        coll.add_box(bx - 16, 0, bz - 16, bx + 16, Y_T1 + 60, bz + 16, f"eternal_brazier_{bi + 1}", "lot")

    # 12 Tall Ceremonial Civic Flagpoles lining the Grand Reflecting Pool avenue!
    f_idx = 0
    for sx in (-168, 168):
        for fz in (-396, -330, -264, -198, -132, -66):
            f_idx += 1
            lot_children.append(add_node(f"civic_flagpole_{f_idx:02d}", mesh=idx_flag,
                                         translation=[sx * U, Y_GND * U, fz * U]))
            coll.add_box(sx - 7, 0, fz - 7, sx + 7, 184, fz + 7, f"civic_flagpole_{f_idx:02d}", "lot")

    # 16 Art Deco Pedestal Globe Lamps along the reflecting pool & perimeter ceremonial plazas!
    lamp_coords = [
        (-126, -380), (126, -380), (-126, -244), (126, -244), (-126, -108), (126, -108),
        (-260, -456), (260, -456), (-450, CZ + 180), (450, CZ + 180),
        (-450, CZ - 180), (450, CZ - 180), (-260, CZ - 456), (260, CZ - 456),
        (-130, CZ - 560), (130, CZ - 560),
    ]
    for li, (lx, lz) in enumerate(lamp_coords):
        lot_children.append(add_node(f"artdeco_lamp_{li + 1:02d}", mesh=idx_lamp,
                                     translation=[lx * U, Y_GND * U, lz * U]))

    # 12 Monumental Plaza Benches facing the Reflecting Pool & North/Side Promenades
    bench_coords = [
        (-132, -312, math.pi * 0.5), (-132, -176, math.pi * 0.5),
        (132, -312, -math.pi * 0.5), (132, -176, -math.pi * 0.5),
        (-310, -450, 0.0), (310, -450, 0.0),
        (-430, CZ + 80, math.pi * 0.5), (-430, CZ - 80, math.pi * 0.5),
        (430, CZ + 80, -math.pi * 0.5), (430, CZ - 80, -math.pi * 0.5),
        (-220, CZ - 440, math.pi), (220, CZ - 440, math.pi),
    ]
    for bi, (bx, bz, rot_y) in enumerate(bench_coords):
        lot_children.append(add_node(f"plaza_bench_{bi + 1:02d}", mesh=idx_bench,
                                     translation=[bx * U, Y_GND * U, bz * U],
                                     rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None))

    # 4 Classical Bronze Urns crowning the ends of the Twin Sweeping Peristyle Colonnades!
    for ui, (ux, uy, uz) in enumerate(urn_positions):
        lot_children.append(add_node(f"peristyle_urn_{ui + 1:02d}", mesh=idx_urn,
                                     translation=[ux * U, uy * U, uz * U]))

    add_node("lot", children=lot_children)

    # Named Door & Interactive Portal Nodes!
    door_specs = [
        ("door_main", [0.0, Y_GND * U, -432.0 * U], 0.0),
        ("door_rotunda_south", [0.0, Y_T3 * U, (CZ + 294.0) * U], 0.0),
        ("door_north", [0.0, Y_T3 * U, (CZ - 294.0) * U], math.pi),
        ("door_east", [294.0 * U, Y_T3 * U, CZ * U], math.pi * 0.5),
        ("door_west", [-294.0 * U, Y_T3 * U, CZ * U], -math.pi * 0.5),
        ("door_hall_west", [-110.0 * U, Y_T3 * U, (CZ + 200.0) * U], math.pi * 0.5),
        ("door_hall_east", [110.0 * U, Y_T3 * U, (CZ + 200.0) * U], -math.pi * 0.5),
        ("door_observatory_lift", [-110.0 * U, Y_T3 * U, (CZ - 200.0) * U], math.pi * 0.5),
        ("door_sky_deck", [0.0, Y_DECK * U, (CZ + 114.0) * U], 0.0),
    ]
    for dname, dpos, drot in door_specs:
        add_node(dname, translation=dpos, rotation=quat_y(drot) if abs(drot) > 1e-4 else None)

    # Re-pack collision mesh after adding braziers & flagpoles!
    meshes_list[idx_collision] = (coll.bucket, {"collision_only": True})

    out_path = R / "great-meridian-monument.glb"
    fsize = write_glb(out_path, MATERIALS_SPEC, meshes_list, nodes)

    # Compute instanced visible triangle count
    mesh_tris = [b.tri_count() for b, _ in meshes_list]
    vis_tris = sum(
        mesh_tris[n["mesh"]] for n in nodes
        if "mesh" in n and n["name"] != "collision" and not n.get("extras", {}).get("collision_only")
    )
    print(f"Wrote {out_path.name} ({fsize} bytes) | vis={vis_tris} tris | coll={coll.bucket.tri_count()} tris ({len(coll.boxes)} boxes)")

    return {
        "file": out_path.name,
        "visible_triangles_instanced": vis_tris,
        "collision_triangles": coll.bucket.tri_count(),
        "collision_boxes": coll.boxes,
        "doors": {n: [round(v, 4) for v in p] for n, p, _ in door_specs},
    }


# ==============================================================================
# DISTANT LANDMARK SILHOUETTE LOD: great-meridian-monument-far.glb (< 600 tris!)
# ==============================================================================
def build_far_lod():
    regions = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    far_mats = [
        {"name": "stone_limestone", "base": "stone_limestone.png", "roughness": 0.90},
        {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.92},
        {"name": "copper_verdigris", "base": "copper_verdigris.png", "metallic": 0.25, "roughness": 0.65},
        {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.55, "roughness": 0.45},
        {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
        {"name": "detail_atlas", "base": "detail_atlas.png", "roughness": 0.88},
    ]
    f_idx = {m["name"]: i for i, m in enumerate(far_mats)}
    b = MeshBucket("great_meridian_far", f_idx, MAT_SIZE, regions)

    CZ = -773.0
    # Broad acropolis plinth + reflecting pool stripe + twin sweeping peristyle wing silhouettes
    b.add_box(-390, 0, CZ - 390, 390, 56, CZ + 340, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-294, 56, CZ - 294, 294, 84, CZ + 294, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_panel_y(-96, -430, 96, -56, 8.0, "+y", "water_pool")
    for side_s in (-1, 1):
        b.add_box(side_s * 545 - 24, 0, CZ - 190, side_s * 545 + 24, 176, CZ + 190, "stone_limestone",
                  faces=["+z", "-z", "+x", "-x", "+y"])
        b.add_box(side_s * 545 - 20, 176, CZ - 190, side_s * 545 + 20, 188, CZ + 190, "copper_verdigris",
                  faces=["+z", "-z", "+x", "-x", "+y"])
    # 4 tapered cathedral pylons matching exact 105.5 m soaring proportions
    for sx in (-1, 1):
        for sz in (-1, 1):
            xa, xb = (110, 278) if sx > 0 else (-278, -110)
            za, zb = (CZ + 110, CZ + 278) if sz > 0 else (CZ - 278, CZ - 110)
            b.add_box(xa, 84, za, xb, 405, zb, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
            xa2, xb2 = (110, 224) if sx > 0 else (-224, -110)
            za2, zb2 = (CZ + 110, CZ + 224) if sz > 0 else (CZ - 224, CZ - 110)
            b.add_box(xa2, 405, za2, xb2, 736, zb2, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
            tx = 196 if sx > 0 else -196
            tz = CZ + (196 if sz > 0 else -196)
            b.add_cylinder(tx, 736, 828, tz, 22.0, 2.0, 4, "copper_verdigris", phase=math.pi * 0.25)
    # 4 bridge entablatures + central suspended bronze armillary silhouette at y = 528!
    b.add_box(-110, 336, CZ + 120, 110, 405, CZ + 270, "stone_limestone")
    b.add_box(-110, 336, CZ - 270, 110, 405, CZ - 120, "stone_limestone")
    b.add_box(120, 336, CZ - 110, 270, 405, CZ + 110, "stone_limestone")
    b.add_box(-270, 336, CZ - 110, -120, 405, CZ + 110, "stone_limestone")
    b.add_sphere_faceted((0, 528, CZ), 90.0, 4, 8, "bronze_gilded")
    # Crossing deck, Sky Observation Crown, Octagonal Lantern, Soaring Verdigris Spire & 105.5 m Finial!
    b.add_box(-224, 724, CZ - 224, 224, 750, CZ + 224, "bronze_gilded")
    b.add_cylinder(0, 750, 898, CZ, 178.0, 178.0, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 898, 940, CZ, 174.0, 122.0, 8, "copper_verdigris", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 940, 1114, CZ, 112.0, 112.0, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 1114, 1294, CZ, 100.0, 14.0, 8, "copper_verdigris", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 1294, 1360, CZ, 12.0, 0.4, 6, "bronze_gilded")

    nodes = [{"name": "great_meridian_far", "mesh": 0}]
    out_path = R / "great-meridian-monument-far.glb"
    fsize = write_glb(out_path, far_mats, [(b, None)], nodes)
    print(f"Wrote {out_path.name} ({fsize} bytes) | vis={b.tri_count()} tris")
    return {"file": out_path.name, "visible_triangles": b.tri_count()}


def main():
    near_info = build_monument()
    far_info = build_far_lod()
    model_info = {
        "name": "The Great Meridian Monument",
        "slug": "great-meridian-monument",
        "era": "1926-present (Beaux-Arts & Art Deco National Monument)",
        "pixel_grid_m_per_texel": TEXEL,
        "art_pixel_m": U,
        "origin": "Ground level (0, 0, 0) at midpoint of +Z front ceremonial approach curb",
        "facing": "+Z",
        "footprint_m": {"width_x": round(1546 * U, 2), "depth_z": round(1546 * U, 2)},
        "heights_m": {
            "acropolis_rotunda_floor": round(84 * U, 2),
            "triumphal_arch_crown": round(326 * U, 2),
            "armillary_sphere_center": round(528 * U, 2),
            "sky_observation_deck": round(750 * U, 2),
            "lantern_crown": round(1114 * U, 2),
            "astral_beacon_orb": round(1312 * U, 2),
            "finial_tip_total": round(1360 * U, 2),
        },
        "file": near_info["file"],
        "far_lod": far_info,
        "visible_triangles_instanced": near_info["visible_triangles_instanced"],
        "materials": [m["name"] for m in MATERIALS_SPEC],
        "doors": near_info["doors"],
        "walkable_floors": [
            "floor_rotunda_plaza",
            "floor_hall_west",
            "floor_hall_east",
            "floor_stairwell_nw",
            "floor_stairwell_ne",
            "floor_observation_deck",
        ],
        "special_nodes": ["armillary_sphere", "lot", "collision"],
        "collision": {
            "count": len(near_info["collision_boxes"]),
            "boxes_blender_z_up": near_info["collision_boxes"],
        },
    }
    (R / "model-info.json").write_text(json.dumps(model_info, indent=2) + "\n", encoding="utf-8")
    print("Wrote model-info.json for The Great Meridian Monument.")


if __name__ == "__main__":
    main()
