#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) builder for THE GREAT MERIDIAN MONUMENT — Four Historical States + Far LOD:
  - great-meridian-1926.glb (1926-1940, Dedication: clean stone, fresh copper spire, cornice bunting, gas lamps, young trees)
  - great-meridian-1941.glb (1941-1975, Mature: slightly darkened stone, green verdigris spire, electric lamps, war plaque, mature trees)
  - great-meridian-1976.glb (1976-1999, Worn: water-streaked stone, broken lamps, missing east telescope, beacon off, SE pier scaffolding)
  - great-meridian-2000.glb (2000+, Restored: clean stone, beacon relit, visible floodlight fixtures, East accessibility ramp, SW Visitor Centre pavilion)
  - great-meridian-far.glb  (Distant landmark silhouette LOD, < 400 tris, matching 3-part outline + glowing beacon orb)

Iconic 3-Part Silhouette (105.54 m tall on 120 m x 120 m plaza):
  1. Base (0-24 m): Three-tier granite acropolis + ONE monolithic 34 x 34 m square arch block pierced by four
     deep-reveal (1.86 m) 17 m triumphal arches, 4 m open bronze doors, West Hall dedication wall, bronze Meridian Line,
     flush carved friezes, and walkable 24 m exterior Cornice Gallery.
  2. Shaft (24-58 m): Four tall slender corner piers (6.2 -> 5.4 -> 4.65 m setbacks at 34 m & 46 m) with full-height
     fluting, one tall narrow amber window slot per face, Four Colossal 7 m Art Deco Guardians (DEFENSE, LEARNING,
     INDUSTRY, HARVEST), hollow NW pier with open-cage elevator (`lift_car`) & winding staircase, riveted-steel
     open-truss cathedral arches, and the 3-node independent 15.2 m Celestial Armillary Sphere
     (`armillary_outer`, `armillary_ecliptic`, `armillary_globe`).
  3. Crown (58-105.5 m): Square walkable 58 m Observation Deck (open bronze railing, oculus, 4 telescopes, 4 corner
     brass direction plates), stepped Octagonal Lantern (12.4 m wide, 16 m tall, interior machinery chamber with gears
     & Fresnel lens), slender tapered copper spire to 101 m, and open beacon cage + `beacon_beam` + finial at 105.54 m.

Strict contract compliance:
  - Metres, Y-up, +Z front, origin (0, 0, 0) at midpoint of +Z front curb.
  - Plaza ground y >= 0, reflecting pool water at y = 0.0 with submerged granite basin below.
  - Strict 0.0388 m/texel (2x2 texels per art px, 0 UV stretch errors), NEAREST + REPEAT, <= 12 materials.
  - Emissive area strictly < 20.0 m2 (ONLY beacon orb, 8 lantern lancets, pier slots, lamp heads, flames, fountain jets, thin ecliptic line).
  - Collision recorded in glTF Y-up (+Z front) metres — the exact same coordinate frame as door_* nodes!
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
# MESH BUCKET WITH EXACT 0.0388 m/TEXEL PLANAR UV PROJECTION (0 STRETCH!)
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

    def _map_uvs_planar(self, pts_art, n, mat_name, region_name=None, fit_region=False, edge_aligned=False):
        art_w, art_h = self.mat_size[mat_name]
        if region_name is not None:
            rx, ry, rw, rh = self.regions[mat_name][region_name]
        else:
            rx, ry, rw, rh = 0.0, 0.0, art_w, art_h

        if edge_aligned and len(pts_art) >= 2 and v_len(v_sub(pts_art[1], pts_art[0])) > 1e-6:
            t1 = v_norm(v_sub(pts_art[1], pts_art[0]))
            t2 = v_norm(v_cross(t1, n))
        else:
            t1, t2 = self._ortho_axes(n)
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
            if span_u > rw + 0.05 or span_v > rh + 0.05:
                raise AssertionError(
                    f"Poly {span_u:.2f}x{span_v:.2f} art px exceeds region '{region_name}' {rw}x{rh} in {mat_name}"
                )
            ox = rx + (rw - span_u) * 0.5 if fit_region else rx
            oy = ry + (rh - span_v) * 0.5 if fit_region else ry
            for ur, vr in zip(us_raw, vs_raw):
                uvs.append(((ox + (ur - u0)) / art_w, (oy + (vr - v0)) / art_h))
        else:
            for ur, vr in zip(us_raw, vs_raw):
                uvs.append((ur / art_w, vr / art_h))
        return uvs

    def add_planar_tri_art(self, p0, p1, p2, mat_name, region_name=None, fit_region=False):
        n = v_cross(v_sub(p1, p0), v_sub(p2, p0))
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

    def add_planar_quad_art(self, p0, p1, p2, p3, mat_name, region_name=None, fit_region=False, edge_aligned=False):
        n = v_cross(v_sub(p1, p0), v_sub(p2, p0))
        if v_len(n) < 1e-8:
            n = v_cross(v_sub(p2, p0), v_sub(p3, p0))
            if v_len(n) < 1e-8:
                return
        n = v_norm(n)
        pts = [p0, p1, p2, p3]
        uvs = self._map_uvs_planar(pts, n, mat_name, region_name, fit_region, edge_aligned=edge_aligned)
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
        for i in range(segs):
            a0 = phase + 2.0 * math.pi * i / segs
            a1 = phase + 2.0 * math.pi * ((i + 1) % segs) / segs
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

        def ring(pt):
            return [
                v_add(pt, v_sub(v_scale(rw, -1), uh)),
                v_add(pt, v_sub(rw, uh)),
                v_add(pt, v_add(rw, uh)),
                v_add(pt, v_add(v_scale(rw, -1), uh)),
            ]
        q0 = ring(p0)
        q1 = ring(p1)
        for i in range(4):
            j = (i + 1) % 4
            self.add_planar_quad_art(q0[i], q0[j], q1[j], q1[i], mat_name, region_name)
        if caps:
            self.add_planar_quad_art(q0[3], q0[2], q0[1], q0[0], mat_name, region_name)
            self.add_planar_quad_art(q1[0], q1[1], q1[2], q1[3], mat_name, region_name)

    def add_ring_3d(self, center, u_axis, v_axis, radius, half_rad, half_thick, segs, mat_name,
                    region_name=None, outer_region=None, outer_mat=None):
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
            fi0, fo0, fo1, fi1 = pt(r_in, a0, 1), pt(r_out, a0, 1), pt(r_out, a1, 1), pt(r_in, a1, 1)
            bi0, bo0, bo1, bi1 = pt(r_in, a0, -1), pt(r_out, a0, -1), pt(r_out, a1, -1), pt(r_in, a1, -1)
            self.add_planar_quad_art(fi0, fo0, fo1, fi1, mat_name, region_name)
            self.add_planar_quad_art(bi1, bo1, bo0, bi0, mat_name, region_name)
            self.add_planar_quad_art(bo0, bo1, fo1, fo0, outer_mat or mat_name, outer_region or region_name,
                                     fit_region=(outer_region is not None), edge_aligned=(outer_region is not None))
            self.add_planar_quad_art(fi0, fi1, bi1, bi0, mat_name, region_name)

    def add_sphere_faceted(self, center, radius, rings, segs, mat_name, region_name=None):
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
                        spandrel_top_y=None, keystone=True, reveal_recess=22.0):
        """Deep-reveal (>= 1.7 m) coffered triumphal stone arch cut cleanly through the monolithic arch block."""
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
            fi0 = pt(c0 * r_in, s0 * r_in, half_depth)
            fo0 = pt(c0 * r_out, s0 * r_out, half_depth)
            fo1 = pt(c1 * r_out, s1 * r_out, half_depth)
            fi1 = pt(c1 * r_in, s1 * r_in, half_depth)
            bi0 = pt(c0 * r_in, s0 * r_in, -half_depth)
            bo0 = pt(c0 * r_out, s0 * r_out, -half_depth)
            bo1 = pt(c1 * r_out, s1 * r_out, -half_depth)
            bi1 = pt(c1 * r_in, s1 * r_in, -half_depth)
            self.add_planar_quad_art(fi0, fo0, fo1, fi1, mat_name)
            self.add_planar_quad_art(bi1, bo1, bo0, bi0, mat_name)
            # Coffered intrados soffit
            self.add_planar_quad_art(bi0, fi0, fi1, bi1, mat_name)
            # Raised coffer rib ring recessed cleanly inside vault!
            if i % 2 == 0:
                ri_a = pt(c0 * (r_in - 2.2), s0 * (r_in - 2.2), half_depth - reveal_recess * 0.45)
                ri_b = pt(c1 * (r_in - 2.2), s1 * (r_in - 2.2), half_depth - reveal_recess * 0.45)
                ri_c = pt(c1 * (r_in - 2.2), s1 * (r_in - 2.2), -half_depth + reveal_recess * 0.45)
                ri_d = pt(c0 * (r_in - 2.2), s0 * (r_in - 2.2), -half_depth + reveal_recess * 0.45)
                self.add_planar_quad_art(ri_d, ri_a, ri_b, ri_c, "stone_granite")
            self.add_planar_quad_art(fo0, bo0, bo1, fo1, mat_name)

            # Flush outer & inner spandrel masonry walls connecting arch ring cleanly to cornice!
            if spandrel_top_y is not None:
                top_v = spandrel_top_y - cy
                if top_v > max(s0 * r_out, s1 * r_out) + 0.2:
                    p_f_a = pt(c0 * r_out, s0 * r_out, half_depth)
                    p_f_b = pt(c1 * r_out, s1 * r_out, half_depth)
                    p_f_ta = pt(c0 * r_out, top_v, half_depth)
                    p_f_tb = pt(c1 * r_out, top_v, half_depth)
                    self.add_planar_quad_art(p_f_a, p_f_ta, p_f_tb, p_f_b, mat_name)
                    p_b_a = pt(c0 * r_out, s0 * r_out, -half_depth)
                    p_b_b = pt(c1 * r_out, s1 * r_out, -half_depth)
                    p_b_ta = pt(c0 * r_out, top_v, -half_depth)
                    p_b_tb = pt(c1 * r_out, top_v, -half_depth)
                    self.add_planar_quad_art(p_b_b, p_b_tb, p_b_ta, p_b_a, mat_name)

        if keystone:
            for sgn in (1.0, -1.0):
                d0 = sgn * (half_depth - 2.0)
                d1 = sgn * (half_depth + 5.5)
                da, db = min(d0, d1), max(d0, d1)
                if axis == "z":
                    self.add_box(cx - 9, cy + r_in - 5, cz + da, cx + 9, cy + r_out + 6, cz + db, "bronze_gilded")
                else:
                    self.add_box(cx + da, cy + r_in - 5, cz - 9, cx + db, cy + r_out + 6, cz + 9, "bronze_gilded")

    def add_open_truss_arch(self, cx, cy, cz, axis, r_in, r_out, half_depth, segs):
        """Eiffel-style open-lattice riveted-steel & gilded-bronze cathedral arch spanning between piers."""
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
            self.add_beam_art(in_f[i], in_f[i + 1], 2.2, 2.2, "steel_truss")
            self.add_beam_art(out_f[i], out_f[i + 1], 2.5, 2.5, "steel_truss")
            self.add_beam_art(in_b[i], in_b[i + 1], 2.2, 2.2, "steel_truss")
            self.add_beam_art(out_b[i], out_b[i + 1], 2.5, 2.5, "steel_truss")
            self.add_beam_art(in_f[i], out_f[i + 1], 1.3, 1.3, "steel_truss")
            self.add_beam_art(out_f[i], in_f[i + 1], 1.3, 1.3, "steel_truss")
            self.add_beam_art(in_b[i], out_b[i + 1], 1.3, 1.3, "steel_truss")
            self.add_beam_art(out_b[i], in_b[i + 1], 1.3, 1.3, "steel_truss")

        for i in range(segs + 1):
            self.add_beam_art(in_f[i], out_f[i], 1.6, 1.6, "steel_truss")
            self.add_beam_art(in_b[i], out_b[i], 1.6, 1.6, "steel_truss")
            self.add_beam_art(in_f[i], in_b[i], 1.5, 1.5, "steel_truss")
            self.add_beam_art(out_f[i], out_b[i], 1.6, 1.6, "steel_truss")

    def tri_count(self):
        return sum(len(pr["idx"]) // 3 for pr in self.prims.values())


# ==============================================================================
# COLLISION RECORDER — STRICTLY IN GLTF Y-UP (+Z FRONT) METRES!
# ==============================================================================
class CollisionRecorder:
    """Records full-height collision boxes in glTF Y-up (+Z front) metres — matching door_* nodes!"""

    def __init__(self, mat_idx, mat_size, regions):
        self.bucket = MeshBucket("collision", mat_idx, mat_size, regions)
        self.boxes = []

    def add_box(self, x0, y0, z0, x1, y1, z1, source, category="exterior"):
        if x1 <= x0 + 1e-4 or y1 <= y0 + 1e-4 or z1 <= z0 + 1e-4:
            return
        self.bucket.add_box(x0, y0, z0, x1, y1, z1, "stone_granite")
        cx = (x0 + x1) * 0.5 * U
        cy = (y0 + y1) * 0.5 * U
        cz = (z0 + z1) * 0.5 * U
        sx = (x1 - x0) * U
        sy = (y1 - y0) * U
        sz = (z1 - z0) * U
        self.boxes.append({
            "source": source,
            "category": category,
            "center": [round(cx, 4), round(cy, 4), round(cz, 4)],  # glTF Y-up, +Z front!
            "size": [round(sx, 4), round(sy, 4), round(sz, 4)],
            "height_top_m": round(y1 * U, 4),
        })


# ==============================================================================
# REUSABLE INSTANCED PROPS (LAMPS, BRAZIERS, FLAGS, BENCHES, TREES, TELESCOPES)
# ==============================================================================
def build_lamp_mesh(name, mat_idx, mat_size, regions, style="electric", broken=False):
    """Plaza lamp: 1926 ornate gas lantern vs 1941+ Art Deco electric globe vs 1976 broken dark shell."""
    b = MeshBucket(name, mat_idx, mat_size, regions)
    b.add_box(-5.0, 0, -5.0, 5.0, 7, 5.0, "stone_granite")
    b.add_box(-3.6, 7, -3.6, 3.6, 13, 3.6, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 13, 52, 0, 2.0, 1.5, 8, "detail_atlas", region_name="dark_iron")
    if style == "gas":
        # 1926 ornate gas-lantern crossbars & flared hood (only 4 visible vertical glass sides glow!)
        b.add_box(-6.0, 48, -1.2, 6.0, 50.5, 1.2, "detail_atlas", region_name="dark_iron")
        b.add_box(-3.2, 52, -3.2, 3.2, 54.5, 3.2, "bronze_gilded")
        if not broken:
            b.add_box(-1.35, 54.5, -1.35, 1.35, 58.1, 1.35, "glow_monument",
                      faces=["+z", "-z", "+x", "-x"], region_name="lamp_globe", fit_region=True)
        else:
            b.add_box(-1.35, 54.5, -1.35, 1.35, 57.2, 1.35, "detail_atlas", region_name="dark_iron")
        b.add_cylinder(0, 58.1, 63.5, 0, 3.4, 0.4, 6, "detail_atlas", region_name="dark_iron")
    else:
        b.add_box(-2.8, 52, -2.8, 2.8, 54.5, 2.8, "bronze_gilded")
        if not broken:
            b.add_box(-1.30, 54.5, -1.30, 1.30, 57.7, 1.30, "glow_monument",
                      faces=["+z", "-z", "+x", "-x"], region_name="lamp_globe", fit_region=True)
            b.add_cylinder(0, 57.7, 60.8, 0, 2.4, 0.3, 6, "bronze_gilded")
        else:
            # Broken unlit lamp socket (1976 worn state)
            b.add_box(-1.30, 54.5, -1.30, 1.30, 56.5, 1.30, "detail_atlas", region_name="dark_iron")
    return b


def build_brazier_eternal_mesh(mat_idx, mat_size, regions):
    """Monumental eternal-flame bronze tripod brazier (~4.5 m tall, tight emissive budget)."""
    b = MeshBucket("brazier_eternal_inst", mat_idx, mat_size, regions)
    b.add_box(-15, 0, -15, 15, 6, 15, "stone_granite")
    b.add_box(-12, 6, -12, 12, 12, 12, "stone_granite")
    b.add_box(-9, 12, -9, 9, 30, 9, "stone_limestone")
    b.add_box(-11, 30, -11, 11, 34, 11, "bronze_gilded")
    for ang in (0, 2.0944, 4.1888):
        lx, lz = math.cos(ang) * 7.0, math.sin(ang) * 7.0
        b.add_beam_art((lx, 34, lz), (lx * 1.15, 43, lz * 1.15), 1.5, 1.5, "detail_atlas", "dark_iron")
    b.add_cylinder(0, 39, 45, 0, 7.0, 12.0, 10, "bronze_gilded", cap_top=True, cap_bot=True)
    b.add_cylinder(0, 44, 46.5, 0, 12.6, 12.6, 10, "detail_atlas", region_name="dark_iron", cap_top=True)
    # 2 double-sided crossed vertical glowing flame planes (~1.68 m2 across all 4 braziers!)
    b.add_panel_z(-2.5, 46.5, 2.5, 53.5, 0.0, "+z", "glow_monument", "flame_brazier", fit_region=True)
    b.add_panel_x(-2.5, 46.5, 2.5, 53.5, 0.0, "+x", "glow_monument", "flame_brazier", fit_region=True)
    return b


def build_flagpole_civic_mesh(mat_idx, mat_size, regions):
    """Ceremonial 14 m monument flagpole with made-up blue-and-gold compass-star civic banner."""
    b = MeshBucket("flagpole_civic_inst", mat_idx, mat_size, regions)
    b.add_box(-7, 0, -7, 7, 8, 7, "stone_granite")
    b.add_box(-5, 8, -5, 5, 14, 5, "bronze_gilded")
    b.add_cylinder(0, 14, 70, 0, 2.2, 1.8, 8, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 70, 126, 0, 1.8, 1.4, 8, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 126, 178, 0, 1.4, 1.0, 8, "detail_atlas", region_name="dark_iron")
    b.add_sphere_faceted((0, 181, 0), 3.0, 4, 8, "bronze_gilded")
    b.add_panel_z(1.2, 150, 45.2, 176, 0.4, "+z", "inscriptions", "civic_flag", fit_region=True)
    b.add_panel_z(1.2, 150, 45.2, 176, -0.4, "-z", "inscriptions", "civic_flag", fit_region=True)
    return b


def build_bench_plaza_mesh(mat_idx, mat_size, regions):
    b = MeshBucket("bench_plaza_inst", mat_idx, mat_size, regions)
    for sx in (-12, 9):
        b.add_box(sx, 0, -5, sx + 3, 7, 5, "stone_limestone")
    b.add_box(-14, 6.5, -4.5, 14, 8.5, 4.5, "detail_atlas", region_name="timber_bench")
    b.add_box(-14, 10.5, -5.2, 14, 15.0, -3.6, "detail_atlas", region_name="timber_bench")
    return b


def build_telescope_deck_mesh(mat_idx, mat_size, regions, missing=False):
    b = MeshBucket("telescope_deck_missing" if missing else "telescope_deck_inst", mat_idx, mat_size, regions)
    b.add_cylinder(0, 0, 2.5, 0, 4.0, 3.2, 8, "detail_atlas", region_name="dark_iron", cap_top=True)
    if not missing:
        b.add_cylinder(0, 2.5, 17.0, 0, 1.4, 1.1, 8, "detail_atlas", region_name="dark_iron")
        b.add_beam_art((0, 18.0, -5.5), (0, 19.2, 6.5), 1.6, 1.6, "bronze_gilded", caps=True)
    return b


def build_tree_mesh(name, mat_idx, mat_size, regions, mature=False):
    """Stylized low-poly architectural plaza tree (young 1926 sapling vs mature 1941+ canopy)."""
    b = MeshBucket(name, mat_idx, mat_size, regions)
    if not mature:
        b.add_cylinder(0, 0, 28, 0, 2.6, 1.8, 6, "detail_atlas", region_name="timber_bench")
        b.add_box(-12, 24, -12, 12, 46, 12, "detail_atlas", region_name="lawn_turf")
        b.add_box(-8, 46, -8, 8, 56, 8, "detail_atlas", region_name="lawn_turf")
    else:
        b.add_cylinder(0, 0, 40, 0, 4.4, 3.0, 6, "detail_atlas", region_name="timber_bench")
        b.add_box(-24, 36, -24, 24, 68, 24, "detail_atlas", region_name="lawn_turf")
        b.add_box(-18, 68, -18, 18, 86, 18, "detail_atlas", region_name="lawn_turf")
        b.add_box(-10, 86, -10, 10, 96, 10, "detail_atlas", region_name="lawn_turf")
    return b


def build_flood_fixture_mesh(mat_idx, mat_size, regions):
    """Visible modern LED/halogen architectural floodlight housing mounted at flood_* nodes in 2000+ state."""
    b = MeshBucket("flood_fixture_inst", mat_idx, mat_size, regions)
    b.add_box(-4.0, 0, -4.0, 4.0, 3.0, 4.0, "stone_granite")
    b.add_box(-3.2, 3.0, -2.5, 3.2, 8.5, 2.5, "detail_atlas", region_name="dark_iron")
    b.add_box(-2.4, 8.5, -2.0, 2.4, 9.6, 2.0, "bronze_gilded")
    return b


def build_guardian_sculpture(bucket, cx, y_bot, cz, facing, kind):
    """
    Colossal ~7.0 m (90 art px tall!) Stylized Art Deco Guardian Figure carved into the upper corner pier!
    Combines crisp stepped blocky 3D Art Deco stone silhouette (head/crown, broad shoulders, draped body,
    projecting iconic attribute & pedestal) with high-detail pixel relief texture (`guardians_atlas`) +
    flush carved stone name band (`DEFENSE`, `LEARNING`, `INDUSTRY`, `HARVEST`) right beneath!
    """
    reg_body = f"guardian_{kind}"
    reg_label = f"label_{kind}"
    hw = 26.0  # 52 art px wide (4.03 m)
    h_fig = 88.0  # 88 art px tall (6.83 m)

    def add_rel_box(u0, v0, d0, u1, v1, d1, mat, reg=None, fit=False):
        if facing == "+z":
            bucket.add_box(cx + u0, y_bot + v0, cz + d0, cx + u1, y_bot + v1, cz + d1, mat, region_name=reg, fit_region=fit)
        elif facing == "-z":
            bucket.add_box(cx - u1, y_bot + v0, cz - d1, cx - u0, y_bot + v1, cz - d0, mat, region_name=reg, fit_region=fit)
        elif facing == "+x":
            bucket.add_box(cx + d0, y_bot + v0, cz - u1, cx + d1, y_bot + v1, cz - u0, mat, region_name=reg, fit_region=fit)
        elif facing == "-x":
            bucket.add_box(cx - d1, y_bot + v0, cz + u0, cx - d0, y_bot + v1, cz + u1, mat, region_name=reg, fit_region=fit)

    def add_rel_panel(u0, v0, u1, v1, d, mat, reg, fit=True):
        if facing == "+z":
            bucket.add_panel_z(cx + u0, y_bot + v0, cx + u1, y_bot + v1, cz + d, "+z", mat, reg, fit)
        elif facing == "-z":
            bucket.add_panel_z(cx - u1, y_bot + v0, cx - u0, y_bot + v1, cz - d, "-z", mat, reg, fit)
        elif facing == "+x":
            bucket.add_panel_x(cz - u1, y_bot + v0, cz - u0, y_bot + v1, cx + d, "+x", mat, reg, fit)
        elif facing == "-x":
            bucket.add_panel_x(cz + u0, y_bot + v0, cz + u1, y_bot + v1, cx - d, "-x", mat, reg, fit)

    # 1. Carved stone pedestal plinth & flush engraved Guardian Name Band beneath!
    add_rel_box(-hw - 2, -16, 0.0, hw + 2, 0.0, 6.5, "stone_limestone")
    add_rel_panel(-26.0, -15.0, 26.0, -1.0, 6.75, "inscriptions", reg_label, fit=True)

    # 2. Stepped 3D Art Deco sculptural stone relief massing (deep niche + blocky shoulders + head/crown!)
    add_rel_box(-hw, 0.0, 0.0, hw, h_fig, 4.0, "stone_limestone")
    # Projecting sculpted head/crown block + broad Art Deco shoulders + central attribute relief!
    add_rel_box(-10, 66, 4.0, 10, 82, 9.5, "stone_limestone")
    add_rel_box(-18, 44, 4.0, 18, 66, 8.2, "stone_limestone")
    add_rel_box(-13, 6, 4.0, 13, 44, 7.5, "stone_limestone")
    # Iconic 3D gilded-bronze sculptural attribute accents projecting cleanly in front of chest/legs!
    if kind == "defense":
        add_rel_box(-10, 44, 8.2, 10, 48, 10.6, "bronze_gilded")  # broadsword crossguard
        add_rel_box(-2.5, 10, 7.5, 2.5, 53, 10.2, "bronze_gilded")  # vertical broadsword blade
    elif kind == "learning":
        add_rel_box(-12, 34, 7.5, 12, 52, 11.0, "bronze_gilded")  # open sculpted tome
    elif kind == "industry":
        add_rel_box(-11, 20, 7.5, 11, 42, 10.8, "bronze_gilded")  # monumental industrial cog wheel
    elif kind == "harvest":
        add_rel_box(-9, 14, 7.5, 9, 52, 10.6, "bronze_gilded")  # golden wheat sheaf

    # 3. Crisp full-height Art Deco relief panel (52x88 art px inside 56x96 region!) cleanly overlaid!
    add_rel_panel(-hw, 0.0, hw, h_fig, 4.25, "guardians_atlas", reg_body, fit=True)


# ==============================================================================
# GLTF 2.0 BINARY (.GLB) PACKER
# ==============================================================================
def write_glb(path, materials_spec, meshes_list, nodes_list, scene_name="GreatMeridianMonument"):
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

    root_nodes = [i for i, _ in enumerate(nodes_list) if not any(i in p.get("children", []) for p in nodes_list)]
    gltf = {
        "asset": {"version": "2.0", "generator": "great-meridian-monument-builder-v2"},
        "scene": 0,
        "scenes": [{"name": scene_name, "nodes": root_nodes}],
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
# MATERIAL TABLE (EXACTLY 12 MATERIALS PER STATE!)
# ==============================================================================
def materials_for_state(state_id):
    stone_tex = {
        "1926": "stone_limestone_clean.png",
        "1941": "stone_limestone_mature.png",
        "1976": "stone_limestone_worn.png",
        "2000": "stone_limestone_clean.png",
    }[state_id]
    copper_tex = "copper_fresh.png" if state_id == "1926" else "copper_verdigris.png"
    return [
        {"name": "stone_limestone", "base": stone_tex, "roughness": 0.88},
        {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.90},
        {"name": "steel_truss", "base": "steel_truss.png", "metallic": 0.45, "roughness": 0.58},
        {"name": "copper_spire", "base": copper_tex, "metallic": 0.30, "roughness": 0.60},
        {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.62, "roughness": 0.42},
        {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
        {"name": "marble_plaza", "base": "marble_plaza.png", "roughness": 0.72},
        {"name": "glass_cathedral", "base": "glass_cathedral.png", "alphaMode": "BLEND", "roughness": 0.22, "doubleSided": True},
        {"name": "detail_atlas", "base": "detail_atlas.png", "roughness": 0.86},
        {"name": "guardians_atlas", "base": "guardians_atlas.png", "roughness": 0.86},
        {"name": "inscriptions", "base": "inscriptions.png", "roughness": 0.86},
        {"name": "glow_monument", "base": "glow_monument_base.png", "emissive": "glow_monument_mask.png",
         "emissiveFactor": [1.0, 1.0, 1.0], "roughness": 0.45, "doubleSided": True},
    ]


MAT_SIZE = {
    "stone_limestone": (64, 64),
    "stone_granite": (64, 64),
    "steel_truss": (64, 64),
    "copper_spire": (64, 64),
    "bronze_gilded": (64, 64),
    "water_pool": (64, 64),
    "marble_plaza": (64, 64),
    "glass_cathedral": (128, 128),
    "detail_atlas": (128, 128),
    "guardians_atlas": (256, 128),
    "inscriptions": (256, 256),
    "glow_monument": (64, 64),
}


# ==============================================================================
# BUILD ONE HISTORICAL STATE OF THE GREAT MERIDIAN MONUMENT
# ==============================================================================
def build_state(state_id):
    regions = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    mats = materials_for_state(state_id)
    mat_idx = {m["name"]: i for i, m in enumerate(mats)}

    structure = MeshBucket("structure", mat_idx, MAT_SIZE, regions)
    arm_outer = MeshBucket("armillary_outer", mat_idx, MAT_SIZE, regions)
    arm_ecliptic = MeshBucket("armillary_ecliptic", mat_idx, MAT_SIZE, regions)
    arm_globe = MeshBucket("armillary_globe", mat_idx, MAT_SIZE, regions)
    lift_car = MeshBucket("lift_car", mat_idx, MAT_SIZE, regions)
    lot_ground = MeshBucket("lot_ground", mat_idx, MAT_SIZE, regions)
    coll = CollisionRecorder(mat_idx, MAT_SIZE, regions)

    # Footprint: 120.0 m x 120.0 m (1546 x 1546 art px), origin (0,0,0) at +Z front curb midpoint!
    HALF = 773.0
    CZ = -773.0  # -59.9848 m
    Y_PLAZA = 2.0     # +0.155 m plaza paving (ground >= 0; reflecting pool water right at y = 0.0!)
    Y_T1 = 28.0       # 2.17 m Tier 1 acropolis
    Y_T2 = 56.0       # 4.35 m Tier 2 stylobate
    Y_T3 = 84.0       # 6.52 m Tier 3 Rotunda & Memorial Halls
    Y_CORNICE = 310.0 # 24.06 m Arch Block top & Cornice Gallery!
    Y_SET1 = 438.0    # 33.99 m Pier Setback 1
    Y_SET2 = 593.0    # 46.02 m Pier Setback 2
    Y_DECK = 748.0    # 58.04 m Square Sky Observation Deck!
    Y_LAN_TOP = 954.0 # 74.03 m Octagonal Lantern top!
    Y_SPIRE = 1302.0  # 101.04 m Verdigris Spire top!
    Y_BEACON = 1318.0 # 102.28 m Aviation Beacon Orb center!
    Y_TIP = 1360.0    # 105.54 m Finial tip!

    floor_buckets = {}

    # ==========================================================================
    # 1. CEREMONIAL PLAZA (y >= 0), REFLECTING POOL (water AT y = 0.0, basin below),
    #    THE MERIDIAN LINE, PARK LAWNS & PERIMETER WALL
    # ==========================================================================
    rp_x, rp_z0, rp_z1 = 96.0, -430.0, -56.0

    # Walkable Plaza Floor (`floor_plaza` at y = Y_PLAZA = 0.155 m, cleanly framing the Reflecting Pool!)
    fb_plaza = MeshBucket("floor_plaza", mat_idx, MAT_SIZE, regions)
    fb_plaza.add_panel_y(-HALF, -2 * HALF, HALF, rp_z0 - 8, Y_PLAZA, "+y", "marble_plaza")
    fb_plaza.add_panel_y(-HALF, rp_z1 + 8, HALF, 0, Y_PLAZA, "+y", "marble_plaza")
    fb_plaza.add_panel_y(-HALF, rp_z0 - 8, -rp_x - 8, rp_z1 + 8, Y_PLAZA, "+y", "marble_plaza")
    fb_plaza.add_panel_y(rp_x + 8, rp_z0 - 8, HALF, rp_z1 + 8, Y_PLAZA, "+y", "marble_plaza")
    # THE MERIDIAN LINE — continuous gleaming inlaid bronze strip along X = 0 across the entire plaza!
    fb_plaza.add_panel_y(-4.0, rp_z1 + 8, 4.0, 0.0, Y_PLAZA + 0.25, "+y", "bronze_gilded")
    fb_plaza.add_panel_y(-4.0, CZ + 340, 4.0, rp_z0 - 8, Y_PLAZA + 0.25, "+y", "bronze_gilded")
    fb_plaza.add_panel_y(-4.0, -2 * HALF, 4.0, CZ - 360, Y_PLAZA + 0.25, "+y", "bronze_gilded")
    floor_buckets["floor_plaza"] = fb_plaza

    # Outer granite plaza curb apron down to y = 0.0
    lot_ground.add_box(-HALF, 0, -2 * HALF, HALF, Y_PLAZA, 0, "stone_granite", faces=["+z", "-z", "+x", "-x"])

    # REFLECTING POOL: Water surface EXACTLY at y = 0.0, sunken granite basin below (y = -8..0)!
    lot_ground.add_panel_y(-rp_x, rp_z0, rp_x, rp_z1, 0.0, "+y", "water_pool")
    # Submerged basin floor & side walls below y = 0 + raised granite pool coping rim (y = -8..+7)
    lot_ground.add_panel_y(-rp_x, rp_z0, rp_x, rp_z1, -8.0, "+y", "stone_granite")
    lot_ground.add_box(-rp_x - 8, -8, rp_z0 - 8, rp_x + 8, 7.0, rp_z0, "stone_granite")
    lot_ground.add_box(-rp_x - 8, -8, rp_z1, rp_x + 8, 7.0, rp_z1 + 8, "stone_granite")
    lot_ground.add_box(-rp_x - 8, -8, rp_z0, -rp_x, 7.0, rp_z1, "stone_granite")
    lot_ground.add_box(rp_x, -8, rp_z0, rp_x + 8, 7.0, rp_z1, "stone_granite")
    # 3 Fountain basins down the Meridian axis + thin illuminated fountain-jet strips (emissive!)
    for fz in (-340, -243, -146):
        lot_ground.add_cylinder(0, -8.0, 4.0, fz, 10.0, 6.0, 8, "stone_granite", cap_top=True)
        lot_ground.add_cylinder(0, 4.0, 9.0, fz, 4.0, 8.5, 8, "bronze_gilded", cap_top=True)
        # Thin sparkling illuminated fountain jet strip (~0.51 m2 across all 3 fountains!)
        lot_ground.add_panel_z(-0.7, 9.0, 0.7, 19.0, fz, "+z", "glow_monument", "fountain_jet", fit_region=True)
        lot_ground.add_panel_x(fz - 0.7, 9.0, fz + 0.7, 19.0, 0.0, "+x", "glow_monument", "fountain_jet", fit_region=True)
    coll.add_box(-rp_x - 8, 0, rp_z0 - 8, rp_x + 8, 10, rp_z1 + 8, "grand_reflecting_pool", "lot")

    # 4 Manicured Ceremonial Park Lawns (SW corner partially trimmed in 2000+ for Visitor Centre Pavilion!)
    lawn_rects = [
        (-740, -520, -230, -40) if state_id != "2000" else (-740, -520, -230, -290),
        (230, -520, 740, -40),
        (-740, -1506, -230, -1040),
        (230, -1506, 740, -1040),
        (-740, -1040, -470, -520),
        (470, -1040, 740, -520),
    ]
    for lx0, lz0, lx1, lz1 in lawn_rects:
        lot_ground.add_tiled_panel_y(lx0, lz0, lx1, lz1, Y_PLAZA + 0.35, "+y", "detail_atlas", "lawn_turf", 60.0)
        lot_ground.add_box(lx0 - 4, Y_PLAZA, lz0 - 4, lx1 + 4, Y_PLAZA + 3.0, lz0, "stone_granite")
        lot_ground.add_box(lx0 - 4, Y_PLAZA, lz1, lx1 + 4, Y_PLAZA + 3.0, lz1 + 4, "stone_granite")
        lot_ground.add_box(lx0 - 4, Y_PLAZA, lz0, lx0, Y_PLAZA + 3.0, lz1, "stone_granite")
        lot_ground.add_box(lx1, Y_PLAZA, lz0, lx1 + 4, Y_PLAZA + 3.0, lz1, "stone_granite")

    # Low perimeter granite boundary wall with broad entrance openings on all 4 axes
    for seg0, seg1 in ((-773, -210), (210, 773)):
        lot_ground.add_box(seg0, Y_PLAZA, -10, seg1, 14, 0, "stone_granite")
        coll.add_box(seg0, 0, -10, seg1, 14, 0, f"perimeter_wall_s_{seg0}", "lot")
        lot_ground.add_box(seg0, Y_PLAZA, -1546, seg1, 14, -1536, "stone_granite")
        coll.add_box(seg0, 0, -1546, seg1, 14, -1536, f"perimeter_wall_n_{seg0}", "lot")
        lot_ground.add_box(-773, Y_PLAZA, CZ + seg0, -763, 14, CZ + seg1, "stone_granite")
        coll.add_box(-773, 0, CZ + seg0, -763, 14, CZ + seg1, f"perimeter_wall_w_{seg0}", "lot")
        lot_ground.add_box(763, Y_PLAZA, CZ + seg0, 773, 14, CZ + seg1, "stone_granite")
        coll.add_box(763, 0, CZ + seg0, 773, 14, CZ + seg1, f"perimeter_wall_e_{seg0}", "lot")
    for gx in (-214, 214):
        lot_ground.add_box(gx - 12, Y_PLAZA, -16, gx + 12, 34, 0, "stone_limestone")
        lot_ground.add_box(gx - 14, 34, -18, gx + 14, 40, 2, "bronze_gilded")
        coll.add_box(gx - 12, 0, -16, gx + 12, 40, 0, f"entry_gate_pier_{gx}", "lot")

    # ==========================================================================
    # 2. THREE-TIER GRANITE ACROPOLIS + FOUR GRAND STAIRCASES (+ 2000+ ACCESS RAMP!)
    # ==========================================================================
    structure.add_box(-360, Y_PLAZA, CZ - 360, 360, Y_T1, CZ + 340, "stone_granite")
    structure.add_panel_y(-360, CZ - 360, 360, CZ + 340, Y_T1 + 0.1, "+y", "marble_plaza")
    structure.add_box(-312, Y_T1, CZ - 312, 312, Y_T2, CZ + 300, "stone_granite")
    structure.add_panel_y(-312, CZ - 312, 312, CZ + 300, Y_T2 + 0.1, "+y", "marble_plaza")
    structure.add_box(-264, Y_T2, CZ - 264, 264, Y_T3, CZ + 264, "stone_limestone")
    structure.add_box(-267, Y_T3 - 4, CZ - 267, 267, Y_T3, CZ + 267, "bronze_gilded", faces=["+z", "-z", "+x", "-x"])
    coll.add_box(-360, 0, CZ - 360, 360, Y_T1, CZ + 340, "acropolis_tier1", "exterior")
    coll.add_box(-312, Y_T1, CZ - 312, 312, Y_T2, CZ + 300, "acropolis_tier2", "exterior")
    coll.add_box(-264, Y_T2, CZ - 264, 264, Y_T3, CZ + 264, "acropolis_tier3", "exterior")

    def add_grand_stair(side, half_w, z_top_dist, steps_n=14, step_run=9.2):
        fb_st = MeshBucket(f"floor_stairs_{side.replace('+', 'p').replace('-', 'm')}", mat_idx, MAT_SIZE, regions)
        dy = (Y_T3 - Y_PLAZA) / steps_n
        for st in range(steps_n):
            y1 = Y_PLAZA + (st + 1) * dy
            d_outer = z_top_dist + (steps_n - st) * step_run
            d_inner = d_outer - step_run
            if side == "+z":
                structure.add_box(-half_w, Y_PLAZA, CZ + d_inner, half_w, y1, CZ + d_outer, "stone_granite", faces=["+z", "+x", "-x"])
                fb_st.add_panel_y(-half_w, CZ + d_inner, half_w, CZ + d_outer, y1, "+y", "stone_granite")
                # Meridian bronze strip climbing dead-center up the south steps!
                fb_st.add_panel_y(-4.0, CZ + d_inner, 4.0, CZ + d_outer, y1 + 0.25, "+y", "bronze_gilded")
                if st % 2 == 0:
                    coll.add_box(-half_w, 0, CZ + d_inner, half_w, y1, CZ + d_outer + step_run, f"stair_s_{st}", "steps")
            elif side == "-z":
                structure.add_box(-half_w, Y_PLAZA, CZ - d_outer, half_w, y1, CZ - d_inner, "stone_granite", faces=["-z", "+x", "-x"])
                fb_st.add_panel_y(-half_w, CZ - d_outer, half_w, CZ - d_inner, y1, "+y", "stone_granite")
                fb_st.add_panel_y(-4.0, CZ - d_outer, 4.0, CZ - d_inner, y1 + 0.25, "+y", "bronze_gilded")
                if st % 2 == 0:
                    coll.add_box(-half_w, 0, CZ - d_outer - step_run, half_w, y1, CZ - d_inner, f"stair_n_{st}", "steps")
            elif side == "+x":
                structure.add_box(d_inner, Y_PLAZA, CZ - half_w, d_outer, y1, CZ + half_w, "stone_granite", faces=["+x", "+z", "-z"])
                fb_st.add_panel_y(d_inner, CZ - half_w, d_outer, CZ + half_w, y1, "+y", "stone_granite")
                if st % 2 == 0:
                    coll.add_box(d_inner, 0, CZ - half_w, d_outer + step_run, y1, CZ + half_w, f"stair_e_{st}", "steps")
            elif side == "-x":
                structure.add_box(-d_outer, Y_PLAZA, CZ - half_w, -d_inner, y1, CZ + half_w, "stone_granite", faces=["-x", "+z", "-z"])
                fb_st.add_panel_y(-d_outer, CZ - half_w, -d_inner, CZ + half_w, y1, "+y", "stone_granite")
                if st % 2 == 0:
                    coll.add_box(-d_outer - step_run, 0, CZ - half_w, -d_inner, y1, CZ + half_w, f"stair_w_{st}", "steps")
        floor_buckets[fb_st.name] = fb_st

        d_end = z_top_dist + steps_n * step_run
        for sgn in (-1, 1):
            c0 = sgn * (half_w + (0 if sgn > 0 else 15))
            c1 = c0 + 15
            if side == "+z":
                structure.add_box(c0, Y_PLAZA, CZ + z_top_dist, c1, Y_T3 + 5, CZ + z_top_dist + 44, "stone_limestone")
                structure.add_box(c0, Y_PLAZA, CZ + z_top_dist + 44, c1, Y_T2 + 4, CZ + d_end, "stone_limestone")
                coll.add_box(c0, 0, CZ + z_top_dist, c1, Y_T3 + 5, CZ + d_end, f"stair_cheek_s_{sgn}", "exterior")
            elif side == "-z":
                structure.add_box(c0, Y_PLAZA, CZ - z_top_dist - 44, c1, Y_T3 + 5, CZ - z_top_dist, "stone_limestone")
                structure.add_box(c0, Y_PLAZA, CZ - d_end, c1, Y_T2 + 4, CZ - z_top_dist - 44, "stone_limestone")
                coll.add_box(c0, 0, CZ - d_end, c1, Y_T3 + 5, CZ - z_top_dist, f"stair_cheek_n_{sgn}", "exterior")
            elif side == "+x":
                structure.add_box(z_top_dist, Y_PLAZA, CZ + c0, z_top_dist + 44, Y_T3 + 5, CZ + c1, "stone_limestone")
                structure.add_box(z_top_dist + 44, Y_PLAZA, CZ + c0, d_end, Y_T2 + 4, CZ + c1, "stone_limestone")
                coll.add_box(z_top_dist, 0, CZ + c0, d_end, Y_T3 + 5, CZ + c1, f"stair_cheek_e_{sgn}", "exterior")
            elif side == "-x":
                structure.add_box(-z_top_dist - 44, Y_PLAZA, CZ + c0, -z_top_dist, Y_T3 + 5, CZ + c1, "stone_limestone")
                structure.add_box(-d_end, Y_PLAZA, CZ + c0, -z_top_dist - 44, Y_T2 + 4, CZ + c1, "stone_limestone")
                coll.add_box(-d_end, 0, CZ + c0, -z_top_dist, Y_T3 + 5, CZ + c1, f"stair_cheek_w_{sgn}", "exterior")

    add_grand_stair("+z", 94.0, 264.0, steps_n=14, step_run=9.0)
    add_grand_stair("-z", 80.0, 264.0, steps_n=12, step_run=9.0)
    add_grand_stair("+x", 80.0, 264.0, steps_n=12, step_run=9.0)
    add_grand_stair("-x", 80.0, 264.0, steps_n=12, step_run=9.0)

    # 2000+ RESTORED STATE: Accessibility switchback stone-and-bronze ramp beside the East Grand Staircase!
    if state_id == "2000":
        fb_ramp = MeshBucket("floor_access_ramp", mat_idx, MAT_SIZE, regions)
        # Lower flight climbing from plaza (y=2) along +Z -> mid landing (y=43) -> upper flight along -Z -> Tier 3 (y=84)!
        rx0, rx1 = 360.0, 412.0
        rz_s, rz_n = CZ + 100.0, CZ + 280.0
        # Lower sloped deck (x in 388..412, z from rz_n -> rz_s, y from 2 -> 43)
        fb_ramp.add_planar_quad_art((388, Y_PLAZA + 0.2, rz_n), (412, Y_PLAZA + 0.2, rz_n),
                                    (412, 43.0, rz_s + 24), (388, 43.0, rz_s + 24), "stone_granite")
        structure.add_box(388, Y_PLAZA, rz_s + 24, 412, 42.5, rz_n, "stone_granite", faces=["+x", "-x", "+z", "-z"])
        # Mid switchback landing (x in 360..412, z in rz_s..rz_s+24, y = 43)
        structure.add_box(rx0, Y_PLAZA, rz_s, rx1, 43.0, rz_s + 24, "stone_granite")
        fb_ramp.add_panel_y(rx0, rz_s, rx1, rz_s + 24, 43.2, "+y", "marble_plaza")
        # Upper sloped deck (x in 360..388, z from rz_s+24 -> rz_n-20, y from 43 -> 84) + bridge into Tier 3!
        fb_ramp.add_planar_quad_art((360, 43.2, rz_s + 24), (386, 43.2, rz_s + 24),
                                    (386, Y_T3, rz_n - 20), (360, Y_T3, rz_n - 20), "stone_granite")
        structure.add_box(360, Y_PLAZA, rz_s + 24, 386, Y_T3 - 0.5, rz_n - 20, "stone_granite", faces=["+x", "-x", "+z", "-z"])
        structure.add_box(264, Y_T2, rz_n - 46, 386, Y_T3, rz_n - 16, "stone_limestone")
        fb_ramp.add_panel_y(264, rz_n - 46, 386, rz_n - 16, Y_T3 + 0.2, "+y", "marble_plaza")
        # Sleek bronze handrails along ramp edges
        structure.add_beam_art((411, Y_PLAZA + 13, rz_n), (411, 56, rz_s + 24), 1.2, 1.2, "bronze_gilded")
        structure.add_beam_art((361, 56, rz_s + 24), (361, Y_T3 + 13, rz_n - 20), 1.2, 1.2, "bronze_gilded")
        floor_buckets["floor_access_ramp"] = fb_ramp
        coll.add_box(360, 0, rz_s, 412, Y_T3 + 12, rz_n, "accessibility_ramp_east", "exterior")

    # ==========================================================================
    # 3. PART 1 — MONOLITHIC 34 x 34 m SQUARE ARCH BLOCK (y = 84..310 = 6.52..24.06 m)
    #    Reads as ONE solid square stone mass cleanly pierced by four 17 m arches!
    # ==========================================================================
    ARCH_OUTER = 219.0  # 438 art px = 33.99 m square!
    ARCH_INNER = 110.0  # 220 art px = 17.07 m clear triumphal arch opening!
    Y_ARCH_SPRING = 174.0  # Arch crown = 174 + 110 = 284 (22.04 m), spandrel solid to cornice at 310 (24.06 m)!

    # Walkable Compass Rose Rotunda Floor (`floor_rotunda_plaza` at y = Y_T3 = 6.52 m)
    fb_rot = MeshBucket("floor_rotunda_plaza", mat_idx, MAT_SIZE, regions)
    fb_rot.add_panel_y(-264, CZ - 264, 264, CZ + 264, Y_T3 + 0.15, "+y", "marble_plaza")
    for gx in (-96, -32, 32):
        for gz in (-96, -32, 32):
            if gx == -32 and gz == -32:
                fb_rot.add_panel_y(-32, CZ - 32, 32, CZ + 32, Y_T3 + 0.45, "+y", "detail_atlas", "compass_mosaic")
            else:
                fb_rot.add_panel_y(gx, CZ + gz, gx + 64, CZ + gz + 64, Y_T3 + 0.30, "+y", "detail_atlas", "dark_iron")
    # THE MERIDIAN LINE cutting dead straight across the Compass Rose Rotunda!
    fb_rot.add_panel_y(-4.5, CZ - 264, 4.5, CZ + 264, Y_T3 + 0.50, "+y", "bronze_gilded")
    fb_rot.add_panel_y(-264, CZ - 4.5, 264, CZ + 4.5, Y_T3 + 0.42, "+y", "bronze_gilded")
    fb_rot.add_ring_3d((0, Y_T3 + 0.8, CZ), (1, 0, 0), (0, 0, -1), 98.0, 4.5, 0.8, 16, "bronze_gilded")
    floor_buckets["floor_rotunda_plaza"] = fb_rot

    # Walkable Memorial Halls (SW = West Memorial Hall w/ Dedication Wall, SE = East Memorial Hall)
    for tag, sx in (("hall_west", -1), ("hall_east", 1)):
        fb = MeshBucket(f"floor_{tag}", mat_idx, MAT_SIZE, regions)
        px0 = -202 if sx < 0 else 126
        px1 = -126 if sx < 0 else 202
        fb.add_panel_y(px0, CZ + 126, px1, CZ + 202, Y_T3 + 0.25, "+y", "marble_plaza")
        floor_buckets[f"floor_{tag}"] = fb

    # Build the 4 massive corner quadrants of the 34 x 34 m Arch Block (y = 84..310)!
    #   SW & SE: hollowed cleanly inside for West & East Memorial Halls!
    #   NW: hollowed FULL-HEIGHT for the Open-Cage Elevator & Winding Staircase!
    #   NE: solid limestone structural core with interior stairwell lobby!
    for sx in (-1, 1):
        for sz in (-1, 1):
            ptag = f"{'e' if sx > 0 else 'w'}{'s' if sz > 0 else 'n'}"
            x0 = ARCH_INNER if sx > 0 else -ARCH_OUTER
            x1 = ARCH_OUTER if sx > 0 else -ARCH_INNER
            z0 = CZ + (ARCH_INNER if sz > 0 else -ARCH_OUTER)
            z1 = CZ + (ARCH_OUTER if sz > 0 else -ARCH_INNER)
            T = 16.0
            zm = (z0 + z1) * 0.5

            # Outer rusticated granite base plinth (y = 84..144) & upper dressed limestone mass (y = 144..294)
            def add_quad_wall(wx0, wy0, wz0, wx1, wy1, wz1):
                if wy0 < 144.0:
                    structure.add_box(wx0, wy0, wz0, wx1, min(144.0, wy1), wz1, "stone_granite")
                if wy1 > 144.0:
                    structure.add_box(wx0, max(144.0, wy0), wz0, wx1, wy1, wz1, "stone_limestone")

            # Outer walls + hollow interior chamber (open doorway facing central rotunda!)
            if sx > 0:
                add_quad_wall(x1 - T, Y_T3, z0, x1, 294, z1)
                add_quad_wall(x0, Y_T3, z0, x0 + T, 294, zm - 18)
                add_quad_wall(x0, Y_T3, zm + 18, x0 + T, 294, z1)
                add_quad_wall(x0, Y_T3 + 52, zm - 18, x0 + T, 294, zm + 18)
                # Gilded bronze interior portal trim
                structure.add_box(x0 - 2, Y_T3, zm - 21, x0 + 2, Y_T3 + 55, zm - 18, "bronze_gilded")
                structure.add_box(x0 - 2, Y_T3, zm + 18, x0 + 2, Y_T3 + 55, zm + 21, "bronze_gilded")
                structure.add_box(x0 - 2, Y_T3 + 52, zm - 21, x0 + 2, Y_T3 + 57, zm + 21, "bronze_gilded")
            else:
                add_quad_wall(x0, Y_T3, z0, x0 + T, 294, z1)
                add_quad_wall(x1 - T, Y_T3, z0, x1, 294, zm - 18)
                add_quad_wall(x1 - T, Y_T3, zm + 18, x1, 294, z1)
                add_quad_wall(x1 - T, Y_T3 + 52, zm - 18, x1, 294, zm + 18)
                structure.add_box(x1 - 2, Y_T3, zm - 21, x1 + 2, Y_T3 + 55, zm - 18, "bronze_gilded")
                structure.add_box(x1 - 2, Y_T3, zm + 18, x1 + 2, Y_T3 + 55, zm + 21, "bronze_gilded")
                structure.add_box(x1 - 2, Y_T3 + 52, zm - 21, x1 + 2, Y_T3 + 57, zm + 21, "bronze_gilded")
            add_quad_wall(x0 + T, Y_T3, z0, x1 - T, 294, z0 + T)
            add_quad_wall(x0 + T, Y_T3, z1 - T, x1 - T, 294, z1)

            # Cap ceiling inside SW/SE/NE chambers (NW stays OPEN straight up into the hollow NW elevator pier!)
            if not (sx < 0 and sz < 0):
                structure.add_box(x0, 286, z0, x1, 294, z1, "stone_limestone")
            else:
                # Cap only the outer ring outside the NW pier shaft [-196..-116, CZ-196..CZ-116]
                structure.add_box(x0, 286, z0, -196, 294, z1, "stone_limestone")
                structure.add_box(-196, 286, z0, x1, 294, CZ - 196, "stone_limestone")

            # WEST MEMORIAL HALL DEDICATION WALL (SW chamber inner west wall facing +X toward the doorway!)
            if sx < 0 and sz > 0:
                zm_w = (z0 + z1) * 0.5
                structure.add_panel_x(zm_w - 36.0, Y_T3 + 4.0, zm_w + 36.0, Y_T3 + 56.0, x0 + T + 0.5, "+x",
                                      "inscriptions", "dedication_wall", fit_region=True)

            # Original 1926 Dedication Plaque (SW front face) & 1941-1945 War Years Plaque (SE front face in 1941+!)
            if sz > 0 and sx < 0:
                structure.add_panel_z(x1 - 38, Y_T3 + 14, x1 - 14, Y_T3 + 32, z1 + 0.6, "+z",
                                      "inscriptions", "plaque_dedication", fit_region=True)
            if sz > 0 and sx > 0 and state_id in ("1941", "1976", "2000"):
                structure.add_panel_z(x0 + 14, Y_T3 + 14, x0 + 38, Y_T3 + 32, z1 + 0.6, "+z",
                                      "inscriptions", "plaque_war", fit_region=True)

            coll.add_box(x0, Y_T3, z0, x1, Y_CORNICE, z1, f"arch_block_corner_{ptag}", "exterior")

    # FOUR 17 m DEEP-REVEAL TRIUMPHAL ARCHES + CONTINUOUS 24 m CORNICE + FLUSH CARVED FRIEZES!
    ARCH_MID = (ARCH_INNER + ARCH_OUTER) * 0.5  # 164.5
    ARCH_HD = (ARCH_OUTER - ARCH_INNER) * 0.5   # 54.5 (4.23 m deep barrel vault!)
    for sz_s in (1, -1):
        az = CZ + sz_s * ARCH_MID
        structure.add_barrel_arch(0.0, Y_ARCH_SPRING, az, "z", ARCH_INNER, 130.0, ARCH_HD, 12,
                                  "stone_limestone", spandrel_top_y=294.0, keystone=True)
        # Solid entablature & projecting 24 m cornice spanning seamlessly across the monolithic arch block!
        structure.add_box(-ARCH_OUTER, 284, az - ARCH_HD, ARCH_OUTER, 294, az + ARCH_HD, "stone_limestone")
        z_f = az + ARCH_HD + 0.35 if sz_s > 0 else az - ARCH_HD - 0.35
        structure.add_panel_z(-116, 260, 116, 292, z_f, "+z" if sz_s > 0 else "-z", "inscriptions", "frieze_main", fit_region=True)
        coll.add_box(-ARCH_INNER, 260, az - ARCH_HD, ARCH_INNER, Y_CORNICE, az + ARCH_HD, f"arch_lintel_z_{sz_s}", "exterior")

    for sx_s, reg_card in ((1, "frieze_east"), (-1, "frieze_west")):
        ax = sx_s * ARCH_MID
        structure.add_barrel_arch(ax, Y_ARCH_SPRING, CZ, "x", ARCH_INNER, 130.0, ARCH_HD, 12,
                                  "stone_limestone", spandrel_top_y=294.0, keystone=True)
        structure.add_box(ax - ARCH_HD, 284, CZ - ARCH_INNER, ax + ARCH_HD, 294, CZ + ARCH_INNER, "stone_limestone")
        x_f = ax + ARCH_HD + 0.35 if sx_s > 0 else ax - ARCH_HD - 0.35
        structure.add_panel_x(CZ - 90, 264, CZ + 90, 288, x_f, "+x" if sx_s > 0 else "-x", "inscriptions", reg_card, fit_region=True)
        coll.add_box(ax - ARCH_HD, 260, CZ - ARCH_INNER, ax + ARCH_HD, Y_CORNICE, CZ + ARCH_INNER, f"arch_lintel_x_{sx_s}", "exterior")

    # TWO HUGE 4.0 m (52 art px) OPEN BRONZE DOORS AT THE SOUTH MAIN ARCH, FOLDED BACK AGAINST REVEALS!
    for sgn in (-1, 1):
        # Folded open flush inside the deep south arch reveal (X = +/-106, Z = CZ+156..CZ+188, height = 52 art px = 4.04 m!)
        dx_d = sgn * (ARCH_INNER - 3.0)
        z_da, z_db = CZ + ARCH_OUTER - 44.0, CZ + ARCH_OUTER - 12.0
        structure.add_box(dx_d - 2.2, Y_T3, z_da, dx_d + 2.2, Y_T3 + 52.0, z_db, "bronze_gilded")
        face_in = "-x" if sgn > 0 else "+x"
        x_pan = dx_d - 2.4 if sgn > 0 else dx_d + 2.4
        structure.add_panel_x(z_da, Y_T3, z_db, Y_T3 + 52.0, x_pan, face_in, "inscriptions", "bronze_door_panel", fit_region=True)
        # Heavy bronze hinges anchoring open door leaf to reveal wall
        for hy in (Y_T3 + 10, Y_T3 + 42):
            structure.add_box(dx_d - 3.5, hy - 2, z_db - 2, dx_d + 3.5, hy + 2, z_db + 3, "detail_atlas", region_name="dark_iron")

    # CONTINUOUS PROJECTING 24 m CORNICE & WALKABLE CORNICE GALLERY (`floor_cornice_gallery`, y = 310 = 24.06 m!)
    GAL_OUT = 226.0
    for (x0, z0, x1, z1) in (
        (-GAL_OUT, CZ - GAL_OUT, GAL_OUT, CZ - ARCH_INNER),
        (-GAL_OUT, CZ + ARCH_INNER, GAL_OUT, CZ + GAL_OUT),
        (-GAL_OUT, CZ - ARCH_INNER, -ARCH_INNER, CZ + ARCH_INNER),
        (ARCH_INNER, CZ - ARCH_INNER, GAL_OUT, CZ + ARCH_INNER),
    ):
        structure.add_box(x0, 294, z0, x1, Y_CORNICE, z1, "stone_limestone")
    # Projecting gilded-bronze cornice lip around all 4 sides of the 24 m cornice!
    structure.add_box(-GAL_OUT - 4, Y_CORNICE - 5, CZ - GAL_OUT - 4, GAL_OUT + 4, Y_CORNICE, CZ - GAL_OUT + 4, "bronze_gilded")
    structure.add_box(-GAL_OUT - 4, Y_CORNICE - 5, CZ + GAL_OUT - 4, GAL_OUT + 4, Y_CORNICE, CZ + GAL_OUT + 4, "bronze_gilded")
    structure.add_box(-GAL_OUT - 4, Y_CORNICE - 5, CZ - GAL_OUT, -GAL_OUT + 4, Y_CORNICE, CZ + GAL_OUT, "bronze_gilded")
    structure.add_box(GAL_OUT - 4, Y_CORNICE - 5, CZ - GAL_OUT, GAL_OUT + 4, Y_CORNICE, CZ + GAL_OUT, "bronze_gilded")

    # Walkable Cornice Gallery floor + open bronze perimeter railing (y = 310..324)!
    fb_gal = MeshBucket("floor_cornice_gallery", mat_idx, MAT_SIZE, regions)
    for (x0, z0, x1, z1) in (
        (-GAL_OUT + 4, CZ - GAL_OUT + 4, GAL_OUT - 4, CZ - 196),
        (-GAL_OUT + 4, CZ + 196, GAL_OUT - 4, CZ + GAL_OUT - 4),
        (-GAL_OUT + 4, CZ - 196, -196, CZ + 196),
        (196, CZ - 196, GAL_OUT - 4, CZ + 196),
        (-116, CZ + 114, 116, CZ + 196),
        (-116, CZ - 196, 116, CZ - 114),
        (-196, CZ - 116, -114, CZ + 116),
        (114, CZ - 116, 196, CZ + 116),
    ):
        fb_gal.add_panel_y(x0, z0, x1, z1, Y_CORNICE + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_cornice_gallery"] = fb_gal

    # Cornice Gallery outer safety bronze railing + collision
    R_GAL = GAL_OUT - 4
    structure.add_box(-R_GAL, Y_CORNICE + 12, CZ + R_GAL - 3, R_GAL, Y_CORNICE + 14.5, CZ + R_GAL, "bronze_gilded")
    structure.add_box(-R_GAL, Y_CORNICE + 12, CZ - R_GAL, R_GAL, Y_CORNICE + 14.5, CZ - R_GAL + 3, "bronze_gilded")
    structure.add_box(-R_GAL, Y_CORNICE + 12, CZ - R_GAL, -R_GAL + 3, Y_CORNICE + 14.5, CZ + R_GAL, "bronze_gilded")
    structure.add_box(R_GAL - 3, Y_CORNICE + 12, CZ - R_GAL, R_GAL, Y_CORNICE + 14.5, CZ + R_GAL, "bronze_gilded")
    for p_off in range(-210, 211, 28):
        for s_z in (CZ + R_GAL - 1.5, CZ - R_GAL + 1.5):
            structure.add_box(p_off - 1.2, Y_CORNICE, s_z - 1.2, p_off + 1.2, Y_CORNICE + 12, s_z + 1.2, "detail_atlas", region_name="dark_iron")
        for s_x in (-R_GAL + 1.5, R_GAL - 1.5):
            structure.add_box(s_x - 1.2, Y_CORNICE, CZ + p_off - 1.2, s_x + 1.2, Y_CORNICE + 12, CZ + p_off + 1.2, "detail_atlas", region_name="dark_iron")
    coll.add_box(-R_GAL, Y_CORNICE, CZ + R_GAL - 4, R_GAL, Y_CORNICE + 15, CZ + R_GAL, "cornice_railing_s", "railing")
    coll.add_box(-R_GAL, Y_CORNICE, CZ - R_GAL, R_GAL, Y_CORNICE + 15, CZ - R_GAL + 4, "cornice_railing_n", "railing")
    coll.add_box(-R_GAL, Y_CORNICE, CZ - R_GAL, -R_GAL + 4, Y_CORNICE + 15, CZ + R_GAL, "cornice_railing_w", "railing")
    coll.add_box(R_GAL - 4, Y_CORNICE, CZ - R_GAL, R_GAL, Y_CORNICE + 15, CZ + R_GAL, "cornice_railing_e", "railing")

    # 1926 DEDICATION STATE: Festive Red, White & Blue Bunting Swags draped cleanly below the 24 m cornice!
    if state_id == "1926":
        for bi in range(-3, 4):
            bx0, bx1 = bi * 56.0 - 28.0, bi * 56.0 + 28.0
            structure.add_panel_z(bx0, 276, bx1, 298, CZ + GAL_OUT + 4.5, "+z", "inscriptions", "bunting_swag", fit_region=True)
            structure.add_panel_z(bx0, 276, bx1, 298, CZ - GAL_OUT - 4.5, "-z", "inscriptions", "bunting_swag", fit_region=True)
            structure.add_panel_x(CZ + bx0, 276, CZ + bx1, 298, GAL_OUT + 4.5, "+x", "inscriptions", "bunting_swag", fit_region=True)
            structure.add_panel_x(CZ + bx0, 276, CZ + bx1, 298, -GAL_OUT - 4.5, "-x", "inscriptions", "bunting_swag", fit_region=True)

    # ==========================================================================
    # 4. PART 2 — THE SHAFT: FOUR TALL SLENDER CORNER PIERS (24-58 m)
    #    Stepped setbacks at 34 m & 46 m, deep full-height vertical fluting,
    #    ONE tall narrow amber window slot per outer face, FOUR 7 m ART DECO GUARDIANS,
    #    and HOLLOW NW PIER with Open-Cage Elevator (`lift_car`) & Winding Staircase!
    # ==========================================================================
    GUARDIAN_MAP = {
        (-1, 1): "defense",   # SW pier
        (1, 1): "learning",   # SE pier
        (-1, -1): "industry", # NW pier
        (1, -1): "harvest",   # NE pier
    }

    for sx in (-1, 1):
        for sz in (-1, 1):
            ptag = f"{'e' if sx > 0 else 'w'}{'s' if sz > 0 else 'n'}"
            is_nw = (sx < 0 and sz < 0)
            g_kind = GUARDIAN_MAP[(sx, sz)]

            def pier_rect(outer):
                xa = 116.0 if sx > 0 else -outer
                xb = outer if sx > 0 else -116.0
                za = CZ + (116.0 if sz > 0 else -outer)
                zb = CZ + (outer if sz > 0 else -116.0)
                return xa, xb, za, zb

            # 3 Clean Art Deco Setback Tiers: 196 (24-34 m) -> 186 (34-46 m) -> 176 (46-58 m)
            tiers = [
                (Y_CORNICE, Y_SET1, 196.0),
                (Y_SET1, Y_SET2, 186.0),
                (Y_SET2, Y_DECK, 176.0),
            ]
            for ti, (y_lo, y_hi, out_r) in enumerate(tiers):
                x0, x1, z0, z1 = pier_rect(out_r)
                if not is_nw:
                    structure.add_box(x0, y_lo, z0, x1, y_hi, z1, "stone_limestone")
                else:
                    # HOLLOW NORTH-WEST PIER: 4 real masonry walls around the open elevator & winding stair shaft!
                    wt = 11.0
                    # Leave doorway opening on south face at 24 m Cornice Gallery & 58 m Sky Deck!
                    if ti == 0:
                        # Doorway onto 24 m Cornice Gallery on +Z side (y = 310..346, x = -168..-142)
                        structure.add_box(x0, y_lo, z1 - wt, -168, y_hi, z1, "stone_limestone")
                        structure.add_box(-142, y_lo, z1 - wt, x1, y_hi, z1, "stone_limestone")
                        structure.add_box(-168, y_lo + 36, z1 - wt, -142, y_hi, z1, "stone_limestone")
                    else:
                        structure.add_box(x0, y_lo, z1 - wt, x1, y_hi, z1, "stone_limestone")
                    # Outer -Z and -X walls with small recessed stairwell lookout slot windows!
                    structure.add_box(x0, y_lo, z0, x1, y_hi, z0 + wt, "stone_limestone")
                    structure.add_box(x0, y_lo, z0 + wt, x0 + wt, y_hi, z1 - wt, "stone_limestone")
                    structure.add_box(x1 - wt, y_lo, z0 + wt, x1, y_hi, z1 - wt, "stone_limestone")

                # Deep continuous vertical Art Deco fluting ribs running cleanly up both outer faces!
                mx = (x0 + x1) * 0.5
                mz = (z0 + z1) * 0.5
                for f_off in (-26, -15, 15, 26):
                    # +Z / -Z outer face vertical ribs
                    zf0 = z1 if sz > 0 else z0 - 4.5
                    structure.add_box(mx + f_off - 2.2, y_lo, zf0, mx + f_off + 2.2, y_hi, zf0 + 4.5, "stone_limestone")
                    # +X / -X outer face vertical ribs
                    xf0 = x1 if sx > 0 else x0 - 4.5
                    structure.add_box(xf0, y_lo, mz + f_off - 2.2, xf0 + 4.5, y_hi, mz + f_off + 2.2, "stone_limestone")

                # Crisp bronze setback trim belt at 34 m & 46 m
                if ti < 2:
                    structure.add_box(x0 - 3, y_hi - 4, z0 - 3, x1 + 3, y_hi + 2, z1 + 3, "bronze_gilded")

            # ONE TALL NARROW AMBER WINDOW SLOT per exterior face (y = 338..568)!
            # Sleek vertical Art Deco frame + compact glowing amber core strip (~0.45 m2 per face -> 3.6 m2 total!)
            x0_m, x1_m, z0_m, z1_m = pier_rect(186.0)
            mx_m, mz_m = (x0_m + x1_m) * 0.5, (z0_m + z1_m) * 0.5
            # Z-facing outer pier slot
            z_face = z1_m if sz > 0 else z0_m
            z_dir = 1.0 if sz > 0 else -1.0
            structure.add_box(mx_m - 5.5, 446, min(z_face, z_face + z_dir * 4.2), mx_m - 3.0, 566, max(z_face, z_face + z_dir * 4.2), "stone_limestone")
            structure.add_box(mx_m + 3.0, 446, min(z_face, z_face + z_dir * 4.2), mx_m + 5.5, 566, max(z_face, z_face + z_dir * 4.2), "stone_limestone")
            structure.add_panel_z(mx_m - 3.0, 448, mx_m + 3.0, 564, z_face + z_dir * 0.5, "+z" if sz > 0 else "-z", "glass_cathedral", "win_cathedral")
            structure.add_panel_z(mx_m - 0.9, 470, mx_m + 0.9, 498, z_face + z_dir * 0.9, "+z" if sz > 0 else "-z", "glow_monument", "pier_slot_glow", fit_region=True)
            # X-facing outer pier slot
            x_face = x1_m if sx > 0 else x0_m
            x_dir = 1.0 if sx > 0 else -1.0
            structure.add_box(min(x_face, x_face + x_dir * 4.2), 446, mz_m - 5.5, max(x_face, x_face + x_dir * 4.2), 566, mz_m - 3.0, "stone_limestone")
            structure.add_box(min(x_face, x_face + x_dir * 4.2), 446, mz_m + 3.0, max(x_face, x_face + x_dir * 4.2), 566, mz_m + 5.5, "stone_limestone")
            structure.add_panel_x(mz_m - 3.0, 448, mz_m + 3.0, 564, x_face + x_dir * 0.5, "+x" if sx > 0 else "-x", "glass_cathedral", "win_cathedral")
            structure.add_panel_x(mz_m - 0.9, 470, mz_m + 0.9, 498, x_face + x_dir * 0.9, "+x" if sx > 0 else "-x", "glow_monument", "pier_slot_glow", fit_region=True)

            # COLOSSAL 7.0 m ART DECO GUARDIAN FIGURE near top of pier just below the 58 m deck (y = 634..722)!
            x0_u, x1_u, z0_u, z1_u = pier_rect(176.0)
            mx_u, mz_u = (x0_u + x1_u) * 0.5, (z0_u + z1_u) * 0.5
            build_guardian_sculpture(structure, mx_u, 634.0, z1_u if sz > 0 else z0_u, "+z" if sz > 0 else "-z", g_kind)
            build_guardian_sculpture(structure, x1_u if sx > 0 else x0_u, 634.0, mz_u, "+x" if sx > 0 else "-x", g_kind)

            # Full-height pier collision in glTF Y-up metres (y = 24.06..58.04 m)!
            coll.add_box(116.0 if sx > 0 else -196.0, Y_CORNICE, CZ + (116.0 if sz > 0 else -196.0),
                         196.0 if sx > 0 else -116.0, Y_DECK, CZ + (196.0 if sz > 0 else -116.0),
                         f"cathedral_pier_{ptag}", "exterior")

    # ==========================================================================
    # 5. INSIDE THE HOLLOW NORTH-WEST PIER: OPEN-CAGE ELEVATOR (`lift_car`) & WINDING STAIRCASE!
    # ==========================================================================
    NW_CX, NW_CZ = -152.0, CZ - 152.0
    # Open wrought-iron elevator cage running cleanly from y = 84 (6.52 m) to y = 748 (58.04 m)!
    for ox in (-11.0, 11.0):
        for oz in (-11.0, 11.0):
            structure.add_box(NW_CX + ox - 1.2, Y_T3, NW_CZ + oz - 1.2, NW_CX + ox + 1.2, Y_DECK + 16, NW_CZ + oz + 1.2,
                              "steel_truss")
    for y_ring in range(110, int(Y_DECK) + 1, 32):
        structure.add_box(NW_CX - 11.5, y_ring, NW_CZ - 11.5, NW_CX + 11.5, y_ring + 1.8, NW_CZ - 10.0, "steel_truss")
        structure.add_box(NW_CX - 11.5, y_ring, NW_CZ + 10.0, NW_CX + 11.5, y_ring + 1.8, NW_CZ + 11.5, "steel_truss")
        structure.add_box(NW_CX - 11.5, y_ring, NW_CZ - 10.0, NW_CX - 10.0, y_ring + 1.8, NW_CZ + 10.0, "steel_truss")
        structure.add_box(NW_CX + 10.0, y_ring, NW_CZ - 10.0, NW_CX + 11.5, y_ring + 1.8, NW_CZ + 10.0, "steel_truss")
    # Counterweight & hoist cable guides
    structure.add_box(NW_CX - 1.0, Y_T3, NW_CZ - 1.0, NW_CX + 1.0, Y_DECK + 14, NW_CZ + 1.0, "steel_truss")
    coll.add_box(NW_CX - 12, Y_T3, NW_CZ - 12, NW_CX + 12, Y_DECK, NW_CZ + 12, "nw_elevator_cage", "interior")

    # Separate movable `lift_car` node (local origin at bottom center of bronze-and-glass Art Deco cab!)
    lift_car.add_box(-9.0, 0.0, -9.0, 9.0, 2.2, 9.0, "bronze_gilded")
    lift_car.add_box(-9.0, 28.0, -9.0, 9.0, 31.0, 9.0, "bronze_gilded")
    for ox in (-8.5, 8.5):
        for oz in (-8.5, 8.5):
            lift_car.add_box(ox - 1.0, 2.2, oz - 1.0, ox + 1.0, 28.0, oz + 1.0, "bronze_gilded")
    lift_car.add_panel_z(-7.5, 3.0, 7.5, 27.5, 8.2, "+z", "glass_cathedral", "win_lantern")
    lift_car.add_panel_z(-7.5, 3.0, 7.5, 27.5, -8.2, "-z", "glass_cathedral", "win_lantern")
    lift_car.add_panel_x(-7.5, 3.0, 7.5, 27.5, 8.2, "+x", "glass_cathedral", "win_lantern")
    lift_car.add_panel_x(-7.5, 3.0, 7.5, 27.5, -8.2, "-x", "glass_cathedral", "win_lantern")

    # Winding Staircase & Walkable Landings (`floor_stair_landings`) spiraling around the NW elevator cage!
    fb_landings = MeshBucket("floor_stair_landings", mat_idx, MAT_SIZE, regions)
    # 16 spiral flights climbing smoothly from y = 84 -> y = 748 around the cage!
    flight_edges = [
        (NW_CX - 22, NW_CZ + 12, NW_CX + 22, NW_CZ + 22),  # South flight (+Z)
        (NW_CX + 12, NW_CZ - 22, NW_CX + 22, NW_CZ + 12),  # East flight (+X)
        (NW_CX - 22, NW_CZ - 22, NW_CX + 12, NW_CZ - 12),  # North flight (-Z)
        (NW_CX - 22, NW_CZ - 12, NW_CX - 12, NW_CZ + 12),  # West flight (-X)
    ]
    n_flights = 16
    dy_fl = (Y_DECK - Y_T3) / n_flights
    for fi in range(n_flights):
        y_f0 = Y_T3 + fi * dy_fl
        y_f1 = y_f0 + dy_fl
        side_i = fi % 4
        # 4 stepped treads per flight + corner landing platform
        fx0, fz0, fx1, fz1 = flight_edges[side_i]
        for st in range(4):
            yt = y_f0 + (st + 1) * (dy_fl / 4.0)
            if side_i == 0:
                tx0 = fx0 + st * ((fx1 - fx0) / 4.0)
                tx1 = tx0 + ((fx1 - fx0) / 4.0)
                structure.add_box(tx0, yt - 2.2, fz0, tx1, yt, fz1, "steel_truss")
                fb_landings.add_panel_y(tx0, fz0, tx1, fz1, yt + 0.1, "+y", "stone_granite")
            elif side_i == 1:
                tz1 = fz1 - st * ((fz1 - fz0) / 4.0)
                tz0 = tz1 - ((fz1 - fz0) / 4.0)
                structure.add_box(fx0, yt - 2.2, tz0, fx1, yt, tz1, "steel_truss")
                fb_landings.add_panel_y(fx0, tz0, fx1, tz1, yt + 0.1, "+y", "stone_granite")
            elif side_i == 2:
                tx1 = fx1 - st * ((fx1 - fx0) / 4.0)
                tx0 = tx1 - ((fx1 - fx0) / 4.0)
                structure.add_box(tx0, yt - 2.2, fz0, tx1, yt, fz1, "steel_truss")
                fb_landings.add_panel_y(tx0, fz0, tx1, fz1, yt + 0.1, "+y", "stone_granite")
            else:
                tz0 = fz0 + st * ((fz1 - fz0) / 4.0)
                tz1 = tz0 + ((fz1 - fz0) / 4.0)
                structure.add_box(fx0, yt - 2.2, tz0, fx1, yt, tz1, "steel_truss")
                fb_landings.add_panel_y(fx0, tz0, fx1, tz1, yt + 0.1, "+y", "stone_granite")
        # Bronze handrail post along stair flight
        structure.add_box(fx0, y_f1, fz0, fx0 + 1.5, y_f1 + 12, fz0 + 1.5, "bronze_gilded")
    # Explicit flat landing deck at 24.06 m (Y_CORNICE) connecting NW lift & stair to Cornice Gallery doorway!
    fb_landings.add_panel_y(NW_CX - 22, NW_CZ + 12, NW_CX + 22, CZ - 116, Y_CORNICE + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_stair_landings"] = fb_landings
    coll.add_box(NW_CX - 23, Y_T3, NW_CZ - 23, NW_CX + 23, Y_DECK, NW_CZ - 12, "nw_stair_north", "interior")
    coll.add_box(NW_CX - 23, Y_T3, NW_CZ + 12, NW_CX + 23, Y_DECK, NW_CZ + 23, "nw_stair_south", "interior")

    # 1976 WORN STATE: Timber-plank & steel-tube restoration scaffolding covering the SE pier (y = 24..55 m)!
    if state_id == "1976":
        sx0, sx1 = 110.0, 212.0
        sz0, sz1 = CZ + 110.0, CZ + 212.0
        for px in (sx0, sx0 + 34, sx0 + 68, sx1):
            structure.add_beam_art((px, Y_CORNICE, sz1), (px, 710, sz1), 1.3, 1.3, "steel_truss")
        for pz in (sz0, sz0 + 34, sz0 + 68, sz1):
            structure.add_beam_art((sx1, Y_CORNICE, pz), (sx1, 710, pz), 1.3, 1.3, "steel_truss")
        for yl in range(int(Y_CORNICE) + 38, 700, 56):
            # Steel ledger rails + timber scaffold deck planks (split <= 51 px so strictly within 64x64 timber_bench!) + X-bracing
            sxm, szm = (sx0 + sx1) * 0.5, (sz0 + sz1) * 0.5
            structure.add_box(sx0, yl, sz1 - 12, sxm, yl + 2.2, sz1 + 2, "detail_atlas", region_name="timber_bench")
            structure.add_box(sxm, yl, sz1 - 12, sx1, yl + 2.2, sz1 + 2, "detail_atlas", region_name="timber_bench")
            structure.add_box(sx1 - 12, yl, sz0, sx1 + 2, yl + 2.2, szm, "detail_atlas", region_name="timber_bench")
            structure.add_box(sx1 - 12, yl, szm, sx1 + 2, yl + 2.2, sz1, "detail_atlas", region_name="timber_bench")
            structure.add_beam_art((sx0, yl, sz1), (sx1, yl + 52, sz1), 1.0, 1.0, "steel_truss")
            structure.add_beam_art((sx1, yl, sz0), (sx1, yl + 52, sz1), 1.0, 1.0, "steel_truss")
        coll.add_box(sx0, Y_CORNICE, sz0, sx1 + 4, 712, sz1 + 4, "scaffolding_se_pier_1976", "exterior")

    # ==========================================================================
    # 6. FOUR OPEN RIVETED-STEEL CATHEDRAL TRUSS ARCHES & 3-NODE ARMILLARY SPHERE!
    # ==========================================================================
    Y_TRUSS_SPRING = 582.0
    for sz_s in (1, -1):
        az = CZ + sz_s * 146.0
        structure.add_open_truss_arch(0.0, Y_TRUSS_SPRING, az, "z", 112.0, 134.0, 14.0, 12)
        for ox in (-88, -52, -20, 20, 52, 88):
            y_arch = Y_TRUSS_SPRING + math.sqrt(max(1.0, 134.0 ** 2 - ox ** 2))
            if y_arch < 726.0:
                structure.add_beam_art((ox, y_arch, az + 14), (ox, 728, az + 14), 1.5, 1.5, "steel_truss")
                structure.add_beam_art((ox, y_arch, az - 14), (ox, 728, az - 14), 1.5, 1.5, "steel_truss")
    for sx_s in (1, -1):
        ax = sx_s * 146.0
        structure.add_open_truss_arch(ax, Y_TRUSS_SPRING, CZ, "x", 112.0, 134.0, 14.0, 12)
        for oz in (-88, -52, -20, 20, 52, 88):
            y_arch = Y_TRUSS_SPRING + math.sqrt(max(1.0, 134.0 ** 2 - oz ** 2))
            if y_arch < 726.0:
                structure.add_beam_art((ax + 14, y_arch, CZ + oz), (ax + 14, 728, CZ + oz), 1.5, 1.5, "steel_truss")
                structure.add_beam_art((ax - 14, y_arch, CZ + oz), (ax - 14, 728, CZ + oz), 1.5, 1.5, "steel_truss")

    # Two Diagonal Crossing Vault Ribs & Overhead Bronze Suspension Bearing at (0, 720, CZ)
    for diag_sgn in (-1.0, 1.0):
        segs_d = 12
        r_diag = 166.0
        pts_d = [(math.cos(math.pi * i / segs_d) * r_diag * 0.7071,
                  552.0 + math.sin(math.pi * i / segs_d) * r_diag,
                  CZ + diag_sgn * math.cos(math.pi * i / segs_d) * r_diag * 0.7071) for i in range(segs_d + 1)]
        for i in range(segs_d):
            structure.add_beam_art(pts_d[i], pts_d[i + 1], 3.0, 3.0, "steel_truss")
    # Overhead bronze suspension bearing ring & hub
    structure.add_ring_3d((0, 722, CZ), (1, 0, 0), (0, 0, -1), 72.0, 7.0, 3.8, 16, "bronze_gilded")
    structure.add_cylinder(0, 706, 724, CZ, 12.0, 16.0, 10, "bronze_gilded", cap_top=True, cap_bot=True)

    # THE STAR: 15.2 m 3-NODE INDEPENDENT CELESTIAL ARMILLARY SPHERE (centered at y = 520 = 40.35 m!)
    Y_ARM = 520.0
    tilt = math.radians(23.4)
    axis_dir = (-math.sin(tilt), math.cos(tilt), 0.0)

    # 1) `armillary_outer` (FIXED): 2 gilded-bronze meridian rings + equatorial ring + overhead hanger rod!
    arm_outer.add_ring_3d((0, 0, 0), (0, 0, 1), (0, 1, 0), 98.0, 5.6, 4.2, 24, "bronze_gilded")
    arm_outer.add_ring_3d((0, 0, 0), (1, 0, 0), (0, 1, 0), 92.0, 5.0, 3.8, 24, "bronze_gilded")
    arm_outer.add_ring_3d((0, 0, 0), (1, 0, 0), (0, 0, -1), 95.0, 5.8, 4.4, 24, "bronze_gilded")
    for hi in range(12):
        ang = 2.0 * math.pi * hi / 12.0
        arm_outer.add_beam_art((math.cos(ang) * 98, 0, math.sin(ang) * 98),
                               (math.cos(ang) * 112, 0, math.sin(ang) * 112), 2.2, 2.2, "bronze_gilded", caps=True)
    # Rigid vertical bronze suspension shaft hanging directly from the overhead truss bearing!
    arm_outer.add_cylinder(0, 96.0, 710.0 - Y_ARM, 0, 4.5, 4.5, 8, "bronze_gilded")
    # 4 Subtle tension steel guy cables from outer ring into the 4 piers so nothing floats!
    for sx in (-1, 1):
        for sz in (-1, 1):
            structure.add_beam_art((sx * 68, Y_ARM + 56, CZ + sz * 68), (sx * 118, 608, CZ + sz * 118), 1.8, 1.8, "steel_truss")

    # 2) `armillary_ecliptic` (TURNS SLOWLY ONCE A GAME DAY):
    #    Tilted 23.4-degree wide gilded zodiac band with engraved pixel stars + razor-thin glowing ecliptic star line!
    u_ec = (math.cos(tilt), math.sin(tilt), 0.0)
    v_ec = (0.0, 0.0, -1.0)
    arm_ecliptic.add_ring_3d((0, 0, 0), u_ec, v_ec, 82.0, 4.6, 6.8, 20, "bronze_gilded",
                             outer_mat="inscriptions", outer_region="zodiac_band")
    # Razor-thin glowing star band along outer ecliptic rim (~0.95 m2 total!)
    ec_n = v_norm(v_cross(u_ec, v_ec))
    r_gl = 87.2
    segs_gl = 20
    for i in range(segs_gl):
        a0, a1 = 2.0 * math.pi * i / segs_gl, 2.0 * math.pi * ((i + 1) % segs_gl) / segs_gl
        c0, s0, c1, s1 = math.cos(a0), math.sin(a0), math.cos(a1), math.sin(a1)
        p0 = v_add(v_scale(u_ec, r_gl * c0), v_scale(v_ec, r_gl * s0))
        p1 = v_add(v_scale(u_ec, r_gl * c1), v_scale(v_ec, r_gl * s1))
        arm_ecliptic.add_planar_quad_art(
            v_add(p0, v_scale(ec_n, -0.35)), v_add(p1, v_scale(ec_n, -0.35)),
            v_add(p1, v_scale(ec_n, 0.35)), v_add(p0, v_scale(ec_n, 0.35)),
            "glow_monument", "ecliptic_glow", fit_region=True, edge_aligned=True
        )

    # 3) `armillary_globe` (TURNS ONCE A DAY ON THE INCLINED 23.4-DEGREE POLAR AXIS):
    #    Verdigris-copper Earth globe (4.35 m diameter!) + inclined gilded polar axis rod!
    arm_globe.add_sphere_faceted((0, 0, 0), 28.0, 6, 12, "inscriptions", "earth_globe")
    arm_globe.add_ring_3d((0, 0, 0), u_ec, v_ec, 31.0, 1.8, 1.5, 16, "bronze_gilded")
    arm_globe.add_beam_art(v_scale(axis_dir, -116.0), v_scale(axis_dir, 118.0), 2.8, 2.8, "bronze_gilded", caps=True)
    arm_globe.add_sphere_faceted(v_scale(axis_dir, -110.0), 6.5, 4, 8, "copper_spire")
    arm_globe.add_beam_art(v_scale(axis_dir, 108.0), v_scale(axis_dir, 132.0), 4.4, 1.4, "bronze_gilded", caps=True)

    # ==========================================================================
    # 7. PART 3 — THE CROWN: SQUARE 58 m OBSERVATION DECK, ONE STEPPED OCTAGONAL
    #    LANTERN WITH MACHINERY CHAMBER, SLENDER VERDIGRIS SPIRE & 105.5 m BEACON!
    # ==========================================================================
    DECK_OUT = 190.0  # 29.5 m square deck bridging the 4 piers cleanly!
    for (x0, z0, x1, z1) in (
        (-DECK_OUT, CZ - DECK_OUT, DECK_OUT, CZ - 68),
        (-DECK_OUT, CZ + 68, DECK_OUT, CZ + DECK_OUT),
        (-DECK_OUT, CZ - 68, -68, CZ + 68),
        (68, CZ - 68, DECK_OUT, CZ + 68),
    ):
        structure.add_box(x0, 724, z0, x1, 744, z1, "stone_limestone")
    # Crisp gilded-bronze deck cornice trim
    structure.add_box(-DECK_OUT - 4, 744, CZ - DECK_OUT - 4, DECK_OUT + 4, Y_DECK, CZ - DECK_OUT + 4, "bronze_gilded")
    structure.add_box(-DECK_OUT - 4, 744, CZ + DECK_OUT - 4, DECK_OUT + 4, Y_DECK, CZ + DECK_OUT + 4, "bronze_gilded")
    structure.add_box(-DECK_OUT - 4, 744, CZ - DECK_OUT, -DECK_OUT + 4, Y_DECK, CZ + DECK_OUT, "bronze_gilded")
    structure.add_box(DECK_OUT - 4, 744, CZ - DECK_OUT, DECK_OUT + 4, Y_DECK, CZ + DECK_OUT, "bronze_gilded")

    # Walkable 58 m Observation Deck Floor (`floor_observation_deck`)
    fb_deck = MeshBucket("floor_observation_deck", mat_idx, MAT_SIZE, regions)
    for (x0, z0, x1, z1) in (
        (-DECK_OUT + 4, CZ - DECK_OUT + 4, DECK_OUT - 4, CZ - 68),
        (-DECK_OUT + 4, CZ + 68, DECK_OUT - 4, CZ + DECK_OUT - 4),
        (-DECK_OUT + 4, CZ - 68, -68, CZ + 68),
        (68, CZ - 68, DECK_OUT - 4, CZ + 68),
    ):
        fb_deck.add_panel_y(x0, z0, x1, z1, Y_DECK + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_observation_deck"] = fb_deck

    # Open Wrought-Iron & Bronze Deck Safety Railing + Collision + Inner Oculus Railing!
    DR = DECK_OUT - 4
    for s_z in (CZ + DR, CZ - DR):
        structure.add_box(-DR, Y_DECK, s_z - 2.5, DR, Y_DECK + 3.5, s_z + 2.5, "stone_limestone")
        structure.add_box(-DR, Y_DECK + 13.5, s_z - 2.0, DR, Y_DECK + 16.0, s_z + 2.0, "bronze_gilded")
    for s_x in (-DR, DR):
        structure.add_box(s_x - 2.5, Y_DECK, CZ - DR, s_x + 2.5, Y_DECK + 3.5, CZ + DR, "stone_limestone")
        structure.add_box(s_x - 2.0, Y_DECK + 13.5, CZ - DR, s_x + 2.0, Y_DECK + 16.0, CZ + DR, "bronze_gilded")
    for p_off in range(-168, 169, 24):
        for s_z in (CZ + DR, CZ - DR):
            structure.add_box(p_off - 1.3, Y_DECK + 3.5, s_z - 1.3, p_off + 1.3, Y_DECK + 13.5, s_z + 1.3, "detail_atlas", region_name="dark_iron")
        for s_x in (-DR, DR):
            structure.add_box(s_x - 1.3, Y_DECK + 3.5, CZ + p_off - 1.3, s_x + 1.3, Y_DECK + 13.5, CZ + p_off + 1.3, "detail_atlas", region_name="dark_iron")
    structure.add_ring_3d((0, Y_DECK + 14.5, CZ), (1, 0, 0), (0, 0, -1), 70.0, 2.5, 2.5, 16, "bronze_gilded")
    coll.add_box(-DR, Y_DECK, CZ + DR - 3, DR, Y_DECK + 16, CZ + DR + 3, "deck_railing_s", "railing")
    coll.add_box(-DR, Y_DECK, CZ - DR - 3, DR, Y_DECK + 16, CZ - DR + 3, "deck_railing_n", "railing")
    coll.add_box(-DR - 3, Y_DECK, CZ - DR, -DR + 3, Y_DECK + 16, CZ + DR, "deck_railing_w", "railing")
    coll.add_box(DR - 3, Y_DECK, CZ - DR, DR + 3, Y_DECK + 16, CZ + DR, "deck_railing_e", "railing")

    # FOUR ENGRAVED BRASS DIRECTION PLATES at the 4 corners of the 58 m Sky Deck railing!
    # Oriented right-side up when a walker on the deck looks outward over the railing!
    for (cx_p, cz_p, reg_dir, face_south) in (
        (-DR + 36, CZ + DR - 6, "dir_capitol", True),
        (DR - 36, CZ + DR - 6, "dir_river", True),
        (-DR + 36, CZ - DR + 6, "dir_mountains", False),
        (DR - 36, CZ - DR + 6, "dir_sea", False),
    ):
        structure.add_box(cx_p - 29, Y_DECK + 15.0, cz_p - 7.5, cx_p + 29, Y_DECK + 17.5, cz_p + 7.5, "bronze_gilded")
        y_pl = Y_DECK + 17.7
        if face_south:
            # Walker faces +Z: CCW from above (+Y normal), t1 = -X (left->right), t2 = t1 x n = -Z (top->bottom)!
            structure.add_planar_quad_art(
                (cx_p + 28, y_pl, cz_p - 7.0), (cx_p - 28, y_pl, cz_p - 7.0),
                (cx_p - 28, y_pl, cz_p + 7.0), (cx_p + 28, y_pl, cz_p + 7.0),
                "inscriptions", reg_dir, fit_region=True, edge_aligned=True
            )
        else:
            # Walker faces -Z: CCW from above (+Y normal), t1 = +X (left->right), t2 = t1 x n = +Z (top->bottom)!
            structure.add_planar_quad_art(
                (cx_p - 28, y_pl, cz_p + 7.0), (cx_p + 28, y_pl, cz_p + 7.0),
                (cx_p + 28, y_pl, cz_p - 7.0), (cx_p - 28, y_pl, cz_p - 7.0),
                "inscriptions", reg_dir, fit_region=True, edge_aligned=True
            )

    # ONE ELEMENT ONLY ABOVE THE DECK: STEPPED OCTAGONAL LANTERN (58-74 m, ~12.4 m wide, 16 m tall!)
    # Hollow upper chamber (`floor_lantern_chamber` at y = 796 = 61.8 m) reached by bronze ladder from 58 m deck!
    R_LAN = 80.0
    # Lower stepped octagonal stone plinth base (y = 748..796) with 4 open archways over the oculus rim
    for i in range(8):
        a0 = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        px = math.cos(a0) * R_LAN
        pz = CZ + math.sin(a0) * R_LAN
        structure.add_box(px - 10, Y_DECK, pz - 10, px + 10, 796, pz + 10, "stone_limestone")
    # Bronze access ladder climbing from 58 m deck into the Lantern Machinery Chamber (y = 748..798)!
    for lx in (-4, 4):
        structure.add_box(-64 + lx, Y_DECK, CZ + 24, -62 + lx, 798, CZ + 26, "bronze_gilded")
    for ly in range(int(Y_DECK) + 4, 796, 6):
        structure.add_box(-64, ly, CZ + 24, -58, ly + 1.5, CZ + 26, "detail_atlas", region_name="dark_iron")

    # Walkable Lantern Machinery Chamber Floor (`floor_lantern_chamber` at y = 796 = 61.77 m)
    fb_lan = MeshBucket("floor_lantern_chamber", mat_idx, MAT_SIZE, regions)
    fb_lan.add_cylinder(0, 793, 796, CZ, R_LAN - 2, R_LAN - 2, 8, "stone_limestone", cap_top=True, cap_bot=True, phase=math.pi / 8.0)
    floor_buckets["floor_lantern_chamber"] = fb_lan

    # 8 Stepped Octagonal Corner Piers + 8 Tall Amber Glass Lancets + Slender Glowing Lancet Strips!
    for i in range(8):
        a_cor = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        ca, sa = math.cos(a_cor), math.sin(a_cor)
        # Clean stepped octagonal corner rib (y = 796..954)
        for r_hi, y_lo, y_hi in ((86, 796, 876), (80, 876, 944), (76, 944, 958)):
            p_a = (ca * 64, (y_lo + y_hi) * 0.5, CZ + sa * 64)
            p_b = (ca * r_hi, (y_lo + y_hi) * 0.5, CZ + sa * r_hi)
            structure.add_beam_art(p_a, p_b, 8.2, (y_hi - y_lo) * 0.5, "stone_limestone", caps=True)

        # Tall semi-transparent amber lancet window on each of the 8 flat octagon faces (seeing gears & lens inside!)
        a_face = 2.0 * math.pi * i / 8.0
        fc, fs = math.cos(a_face), math.sin(a_face)
        tx, tz = -fs, fc
        r_win = 74.0 * math.cos(math.pi / 8.0)
        hw_w = 18.0
        structure.add_planar_quad_art(
            (fc * r_win + tx * hw_w, 804, CZ + fs * r_win + tz * hw_w),
            (fc * r_win - tx * hw_w, 804, CZ + fs * r_win - tz * hw_w),
            (fc * r_win - tx * hw_w, 932, CZ + fs * r_win - tz * hw_w),
            (fc * r_win + tx * hw_w, 932, CZ + fs * r_win + tz * hw_w),
            "glass_cathedral", "win_lantern"
        )
        # Slender glowing amber lantern lancet core strip (2.2 x 30 art px -> ~3.17 m2 across all 8 faces!)
        r_gw = r_win + 0.8
        gw_hw = 1.1
        structure.add_planar_quad_art(
            (fc * r_gw + tx * gw_hw, 850, CZ + fs * r_gw + tz * gw_hw),
            (fc * r_gw - tx * gw_hw, 850, CZ + fs * r_gw - tz * gw_hw),
            (fc * r_gw - tx * gw_hw, 880, CZ + fs * r_gw - tz * gw_hw),
            (fc * r_gw + tx * gw_hw, 880, CZ + fs * r_gw + tz * gw_hw),
            "glow_monument", "lantern_lancet_glow", fit_region=True
        )

    # INTERIOR BEACON MACHINERY CHAMBER (y = 796..938): Interlocking Gilded-Bronze Drive Gears & Big Fresnel Lens!
    # Vertical steel drive column rising up toward the 102 m aviation beacon!
    structure.add_cylinder(0, 796, 954, CZ, 5.5, 5.5, 8, "steel_truss")
    # Primary horizontal bull gear (r = 34) + meshing bronze bevel drive gear + flywheel!
    structure.add_ring_3d((0, 824, CZ), (1, 0, 0), (0, 0, -1), 30.0, 7.0, 4.5, 12, "bronze_gilded")
    structure.add_ring_3d((34, 838, CZ), (0, 1, 0), (0, 0, 1), 16.0, 4.0, 3.5, 10, "bronze_gilded")
    structure.add_ring_3d((-32, 832, CZ), (0, 1, 0), (0, 0, 1), 14.0, 3.5, 3.0, 10, "detail_atlas", "dark_iron")
    for gi in range(8):
        ga = 2.0 * math.pi * gi / 8.0
        structure.add_beam_art((0, 824, CZ), (math.cos(ga) * 34, 824, CZ + math.sin(ga) * 34), 2.0, 2.0, "bronze_gilded")
    # Big faceted brass-framed Fresnel Lens assembly around the upper lantern core (y = 858..918)!
    structure.add_cylinder(0, 858, 916, CZ, 26.0, 26.0, 8, "glass_cathedral", "win_lantern")
    for fy in (858, 878, 898, 916):
        structure.add_ring_3d((0, fy, CZ), (1, 0, 0), (0, 0, -1), 27.5, 2.5, 1.8, 8, "bronze_gilded")

    # Stepped Octagonal Lantern Cap Cornice (y = 944..960) & Lantern Collision!
    structure.add_cylinder(0, 944, 954, CZ, 82.0, 82.0, 8, "stone_limestone", cap_top=True, cap_bot=True, phase=math.pi / 8.0)
    structure.add_cylinder(0, 954, 962, CZ, 85.0, 72.0, 8, "bronze_gilded", cap_top=True, phase=math.pi / 8.0)
    coll.add_box(-R_LAN, Y_DECK, CZ - R_LAN, R_LAN, Y_LAN_TOP, CZ + R_LAN, "octagonal_lantern_drum", "exterior")

    # SLENDER TAPERED COPPER NEEDLE SPIRE (74-101 m, y = 962..1302 = 26.4 m tall!)
    # Warm fresh copper in 1926, fully patinated verdigris green in 1941+!
    structure.add_cylinder(0, 962, Y_SPIRE, CZ, 60.0, 11.0, 8, "copper_spire", cap_top=True, phase=math.pi / 8.0)
    for i in range(8):
        ang = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        structure.add_beam_art(
            (math.cos(ang) * 61.0, 962.0, CZ + math.sin(ang) * 61.0),
            (math.cos(ang) * 12.0, Y_SPIRE, CZ + math.sin(ang) * 12.0),
            1.8, 1.8, "bronze_gilded"
        )
    for y_col, r_col in ((1048, 48.0), (1142, 34.0), (1230, 21.0)):
        structure.add_cylinder(0, y_col, y_col + 5, CZ, r_col, r_col, 8, "bronze_gilded", phase=math.pi / 8.0)

    # OPEN AVIATION BEACON CAGE, BEACON ORB (OFF IN 1976!) & LIGHTNING FINIAL AT 105.54 m!
    structure.add_cylinder(0, Y_SPIRE - 2, Y_SPIRE + 4, CZ, 18.0, 18.0, 8, "bronze_gilded", cap_top=True)
    for i in range(8):
        ang = 2.0 * math.pi * i / 8.0
        structure.add_beam_art(
            (math.cos(ang) * 14.0, Y_SPIRE + 4, CZ + math.sin(ang) * 14.0),
            (math.cos(ang) * 9.0, 1334.0, CZ + math.sin(ang) * 9.0),
            1.4, 1.4, "bronze_gilded"
        )
    structure.add_cylinder(0, 1332, 1338, CZ, 12.0, 5.0, 8, "bronze_gilded", cap_top=True)
    if state_id != "1976":
        # Lit radiant Aviation Beacon Orb (r = 5.2 art px -> ~2.05 m2)
        structure.add_sphere_faceted((0, Y_BEACON, CZ), 5.2, 6, 10, "glow_monument", "beacon_orb")
    else:
        # 1976 WORN STATE: Beacon OFF (unlit dark glass/iron housing, 0 beacon emissive!)
        structure.add_sphere_faceted((0, Y_BEACON, CZ), 5.2, 6, 10, "detail_atlas", "dark_iron")
    structure.add_cylinder(0, 1338, Y_TIP, CZ, 3.2, 0.4, 6, "bronze_gilded")

    # ==========================================================================
    # 8. 2000+ RESTORED STATE: ONE-STOREY GLASS-AND-STONE VISITOR CENTRE PAVILION
    #    Tucked cleanly at the SW plaza corner (X in [-680..-474], Z in [-250..-118] = 16.0 x 10.2 m!)
    # ==========================================================================
    if state_id == "2000":
        vx0, vx1 = -680.0, -474.0
        vz0, vz1 = -250.0, -118.0
        vy0, vy1 = Y_PLAZA, Y_PLAZA + 54.0
        # Walkable Visitor Centre Floor (`floor_visitor_center`)
        fb_vc = MeshBucket("floor_visitor_center", mat_idx, MAT_SIZE, regions)
        fb_vc.add_panel_y(vx0, vz0, vx1, vz1, vy0 + 2.2, "+y", "marble_plaza")
        floor_buckets["floor_visitor_center"] = fb_vc
        # Plinth + limestone end piers + wide architectural curtain-wall glass + cantilevered stone roof!
        lot_ground.add_box(vx0 - 6, vy0, vz0 - 6, vx1 + 6, vy0 + 2.0, vz1 + 6, "stone_granite")
        lot_ground.add_box(vx0, vy0 + 2, vz0, vx0 + 22, vy1, vz1, "stone_limestone")
        lot_ground.add_box(vx1 - 22, vy0 + 2, vz0, vx1, vy1, vz1, "stone_limestone")
        lot_ground.add_box(vx0 + 22, vy0 + 2, vz0, vx1 - 22, vy1, vz0 + 10, "stone_limestone")
        # Curtain-wall glass front (+Z) with central open glass entrance doorway!
        lot_ground.add_panel_z(vx0 + 22, vy0 + 2, -595, vy1 - 8, vz1 - 4, "+z", "glass_cathedral", "win_cathedral")
        lot_ground.add_panel_z(-559, vy0 + 2, vx1 - 22, vy1 - 8, vz1 - 4, "+z", "glass_cathedral", "win_cathedral")
        lot_ground.add_box(-595, vy0 + 2, vz1 - 6, -592, vy1 - 8, vz1 - 2, "bronze_gilded")
        lot_ground.add_box(-562, vy0 + 2, vz1 - 6, -559, vy1 - 8, vz1 - 2, "bronze_gilded")
        # Clean cantilevered limestone & bronze roof fascia + flush "VISITOR CENTER" inscription!
        lot_ground.add_box(vx0 - 10, vy1 - 8, vz0 - 10, vx1 + 10, vy1 + 10, vz1 + 10, "stone_limestone")
        lot_ground.add_box(vx0 - 12, vy1 + 10, vz0 - 12, vx1 + 12, vy1 + 13, vz1 + 12, "bronze_gilded")
        lot_ground.add_panel_z(-625, vy1 - 7, -529, vy1 + 11, vz1 + 10.3, "+z", "inscriptions", "visitor_sign", fit_region=True)
        coll.add_box(vx0 - 6, 0, vz0 - 6, vx1 + 6, vy1 + 13, vz1 + 6, "visitor_center_pavilion", "lot")

    # ==========================================================================
    # 9. ASSEMBLE REUSABLE INSTANCED PROPS, EMPTY FLOOD/BEACON/DOOR NODES & PACK GLB
    # ==========================================================================
    lamp_style = "gas" if state_id == "1926" else "electric"
    lamp_lit_mesh = build_lamp_mesh("lamp_lit_inst", mat_idx, MAT_SIZE, regions, style=lamp_style, broken=False)
    lamp_broken_mesh = build_lamp_mesh("lamp_broken_inst", mat_idx, MAT_SIZE, regions, style=lamp_style, broken=True)
    brazier_mesh = build_brazier_eternal_mesh(mat_idx, MAT_SIZE, regions)
    flag_mesh = build_flagpole_civic_mesh(mat_idx, MAT_SIZE, regions)
    bench_mesh = build_bench_plaza_mesh(mat_idx, MAT_SIZE, regions)
    tele_mesh = build_telescope_deck_mesh(mat_idx, MAT_SIZE, regions, missing=False)
    tele_miss_mesh = build_telescope_deck_mesh(mat_idx, MAT_SIZE, regions, missing=True)
    tree_mesh = build_tree_mesh("tree_plaza_inst", mat_idx, MAT_SIZE, regions, mature=(state_id != "1926"))
    flood_fix_mesh = build_flood_fixture_mesh(mat_idx, MAT_SIZE, regions)

    meshes_list = []

    def reg(bucket, extras=None):
        idx = len(meshes_list)
        meshes_list.append((bucket, extras))
        return idx

    idx_structure = reg(structure)
    idx_arm_outer = reg(arm_outer)
    idx_arm_ecliptic = reg(arm_ecliptic)
    idx_arm_globe = reg(arm_globe)
    idx_lift_car = reg(lift_car)
    idx_lot_ground = reg(lot_ground)
    idx_collision = reg(coll.bucket, {"collision_only": True})
    idx_lamp_lit = reg(lamp_lit_mesh)
    idx_lamp_broken = reg(lamp_broken_mesh)
    idx_brazier = reg(brazier_mesh)
    idx_flag = reg(flag_mesh)
    idx_bench = reg(bench_mesh)
    idx_tele = reg(tele_mesh)
    idx_tele_miss = reg(tele_miss_mesh)
    idx_tree = reg(tree_mesh)
    idx_flood_fix = reg(flood_fix_mesh)

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

    add_node("structure", mesh=idx_structure)

    # 3-NODE INDEPENDENT CELESTIAL ARMILLARY SPHERE under parent `armillary_sphere`!
    n_ao = add_node("armillary_outer", mesh=idx_arm_outer,
                    extras={"motion": "fixed", "role": "outer_meridian_and_equator_rings"})
    n_ae = add_node("armillary_ecliptic", mesh=idx_arm_ecliptic,
                    extras={"motion": "rotate_slow_once_per_game_day", "tilt_deg": 23.4})
    n_ag = add_node("armillary_globe", mesh=idx_arm_globe,
                    extras={"motion": "rotate_on_polar_axis_once_per_game_day", "polar_axis": [round(-math.sin(tilt), 5), round(math.cos(tilt), 5), 0.0]})
    add_node("armillary_sphere", translation=[0.0, Y_ARM * U, CZ * U], children=[n_ao, n_ae, n_ag],
             extras={"role": "suspended_celestial_armillary_sphere", "diameter_m": round(196 * U, 2)})

    # Movable Elevator Car inside NW Pier (`lift_car`)
    add_node("lift_car", mesh=idx_lift_car, translation=[NW_CX * U, Y_CORNICE * U, NW_CZ * U],
             extras={"role": "movable_elevator_cab", "stops_y_m": [round(Y_T3 * U, 2), round(Y_CORNICE * U, 2), round(Y_DECK * U, 2)]})

    # Rotating Aviation Beacon Beam Empty Node (`beacon_beam`)
    add_node("beacon_beam", translation=[0.0, Y_BEACON * U, CZ * U],
             extras={"role": "rotating_aviation_beacon", "direction": [0.0, -0.05, 1.0],
                     "rotation_period_s": 10.0, "active": (state_id != "1976")})

    # Empty Spotlight Floodlight Nodes `flood_01..12` (visible fixtures attached in 2000+ restored state!)
    flood_defs = [
        # 4 pier-base spotlights aimed up at the 4 piers & Guardians
        ("flood_01", [-210, Y_T3, CZ + 210], [0.22, 0.95, -0.22], "pier_sw_uplight"),
        ("flood_02", [210, Y_T3, CZ + 210], [-0.22, 0.95, -0.22], "pier_se_uplight"),
        ("flood_03", [-210, Y_T3, CZ - 210], [0.22, 0.95, 0.22], "pier_nw_uplight"),
        ("flood_04", [210, Y_T3, CZ - 210], [-0.22, 0.95, 0.22], "pier_ne_uplight"),
        # 4 rotunda inner uplights illuminating the 15.2 m Celestial Armillary Sphere from below!
        ("flood_05", [-84, Y_T3, CZ + 84], [0.18, 0.96, -0.18], "armillary_uplight_sw"),
        ("flood_06", [84, Y_T3, CZ + 84], [-0.18, 0.96, -0.18], "armillary_uplight_se"),
        ("flood_07", [-84, Y_T3, CZ - 84], [0.18, 0.96, 0.18], "armillary_uplight_nw"),
        ("flood_08", [84, Y_T3, CZ - 84], [-0.18, 0.96, 0.18], "armillary_uplight_ne"),
        # 4 grand plaza facade floodlights highlighting the 34 m Arch Block & Crown
        ("flood_09", [-150, Y_PLAZA, CZ + 440], [0.24, 0.62, -0.75], "plaza_south_west_flood"),
        ("flood_10", [150, Y_PLAZA, CZ + 440], [-0.24, 0.62, -0.75], "plaza_south_east_flood"),
        ("flood_11", [-440, Y_PLAZA, CZ], [0.78, 0.62, 0.0], "plaza_west_flood"),
        ("flood_12", [440, Y_PLAZA, CZ], [-0.78, 0.62, 0.0], "plaza_east_flood"),
    ]
    floods_meta = []
    for fname, fpos_art, fdir_raw, frole in flood_defs:
        fn = v_norm(tuple(fdir_raw))
        fpos_m = [round(fpos_art[0] * U, 4), round(fpos_art[1] * U, 4), round(fpos_art[2] * U, 4)]
        add_node(fname, mesh=(idx_flood_fix if state_id == "2000" else None),
                 translation=fpos_m, extras={"role": frole, "direction": [round(x, 4) for x in fn]})
        floods_meta.append({"node": fname, "role": frole, "position_m": fpos_m, "direction": [round(x, 4) for x in fn]})

    # Named Walkable Floor Nodes
    for fn, mi in floor_mesh_idx.items():
        add_node(fn, mesh=mi)

    # 4 Brass Viewing Telescopes on the 58 m Sky Observation Deck (East telescope missing in 1976 worn state!)
    for ti, (tx, tz, rot_y) in enumerate((
        (0, CZ + 174, 0.0),
        (0, CZ - 174, math.pi),
        (174, CZ, math.pi * 0.5),
        (-174, CZ, -math.pi * 0.5),
    )):
        is_miss = (state_id == "1976" and ti == 2)
        add_node(f"sky_telescope_{ti + 1:02d}", mesh=(idx_tele_miss if is_miss else idx_tele),
                 translation=[tx * U, Y_DECK * U, tz * U],
                 rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None)

    # Lot Group Children
    lot_children = [add_node("lot_ground", mesh=idx_lot_ground)]

    # 4 Corner Eternal-Flame Bronze Braziers on Tier 1 Acropolis corners
    for bi, (bx, bz) in enumerate((
        (-330, CZ + 312), (330, CZ + 312), (-330, CZ - 330), (330, CZ - 330),
    )):
        lot_children.append(add_node(f"eternal_brazier_{bi + 1:02d}", mesh=idx_brazier,
                                     translation=[bx * U, Y_T1 * U, bz * U]))
        coll.add_box(bx - 15, 0, bz - 15, bx + 15, Y_T1 + 56, bz + 15, f"eternal_brazier_{bi + 1}", "lot")

    # 12 Civic Flagpoles (made-up blue-and-gold compass-star civic flag!)
    f_idx = 0
    for sx in (-168, 168):
        for fz in (-396, -330, -264, -198, -132, -66):
            f_idx += 1
            lot_children.append(add_node(f"civic_flagpole_{f_idx:02d}", mesh=idx_flag,
                                         translation=[sx * U, Y_PLAZA * U, fz * U]))
            coll.add_box(sx - 7, 0, fz - 7, sx + 7, 184, fz + 7, f"civic_flagpole_{f_idx:02d}", "lot")

    # 16 Plaza Globe Lamps (3 broken/unlit in 1976 worn state!)
    lamp_coords = [
        (-126, -380), (126, -380), (-126, -244), (126, -244), (-126, -108), (126, -108),
        (-260, -456), (260, -456), (-430, CZ + 180), (430, CZ + 180),
        (-430, CZ - 180), (430, CZ - 180), (-260, CZ - 440), (260, CZ - 440),
        (-130, CZ - 560), (130, CZ - 560),
    ]
    broken_lamps = {2, 7, 11} if state_id == "1976" else set()
    for li, (lx, lz) in enumerate(lamp_coords):
        lot_children.append(add_node(f"plaza_lamp_{li + 1:02d}",
                                     mesh=(idx_lamp_broken if li in broken_lamps else idx_lamp_lit),
                                     translation=[lx * U, Y_PLAZA * U, lz * U]))

    # 12 Plaza Benches
    bench_coords = [
        (-132, -312, math.pi * 0.5), (-132, -176, math.pi * 0.5),
        (132, -312, -math.pi * 0.5), (132, -176, -math.pi * 0.5),
        (-310, -450, 0.0), (310, -450, 0.0),
        (-410, CZ + 80, math.pi * 0.5), (-410, CZ - 80, math.pi * 0.5),
        (410, CZ + 80, -math.pi * 0.5), (410, CZ - 80, -math.pi * 0.5),
        (-220, CZ - 440, math.pi), (220, CZ - 440, math.pi),
    ]
    for bi, (bx, bz, rot_y) in enumerate(bench_coords):
        lot_children.append(add_node(f"plaza_bench_{bi + 1:02d}", mesh=idx_bench,
                                     translation=[bx * U, Y_PLAZA * U, bz * U],
                                     rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None))

    # 8 Named Ceremonial Plaza Trees (`tree_01..tree_08`: young in 1926, mature in 1941+!)
    tree_coords = [
        (-620, -420), (-360, -420), (360, -420), (620, -420),
        (-620, CZ - 420), (-360, CZ - 420), (360, CZ - 420), (620, CZ - 420),
    ]
    for tri, (tx, tz) in enumerate(tree_coords):
        lot_children.append(add_node(f"tree_{tri + 1:02d}", mesh=idx_tree,
                                     translation=[tx * U, Y_PLAZA * U, tz * U]))
        coll.add_box(tx - 6, 0, tz - 6, tx + 6, 96 if state_id != "1926" else 56, tz + 6, f"tree_{tri + 1:02d}", "lot")

    add_node("lot", children=lot_children)

    # Named Door & Landing Portal Nodes (exact glTF Y-up metres!)
    door_specs = [
        ("door_main", [0.0, Y_PLAZA * U, -432.0 * U], 0.0),
        ("door_rotunda_south", [0.0, Y_T3 * U, (CZ + ARCH_OUTER) * U], 0.0),
        ("door_north", [0.0, Y_T3 * U, (CZ - ARCH_OUTER) * U], math.pi),
        ("door_east", [ARCH_OUTER * U, Y_T3 * U, CZ * U], math.pi * 0.5),
        ("door_west", [-ARCH_OUTER * U, Y_T3 * U, CZ * U], -math.pi * 0.5),
        ("door_hall_west", [-ARCH_INNER * U, Y_T3 * U, (CZ + 164.0) * U], math.pi * 0.5),
        ("door_hall_east", [ARCH_INNER * U, Y_T3 * U, (CZ + 164.0) * U], -math.pi * 0.5),
        ("door_observatory_lift", [-ARCH_INNER * U, Y_T3 * U, (CZ - 164.0) * U], math.pi * 0.5),
        ("door_cornice_gallery", [NW_CX * U, Y_CORNICE * U, (CZ - 116.0) * U], 0.0),
        ("door_sky_deck", [NW_CX * U, Y_DECK * U, (CZ - 116.0) * U], 0.0),
        ("door_lantern_chamber", [-60.0 * U, 796.0 * U, (CZ + 24.0) * U], 0.0),
    ]
    if state_id == "2000":
        door_specs.append(("door_visitor_center", [-577.0 * U, (Y_PLAZA + 2.2) * U, -118.0 * U], 0.0))
    for dname, dpos, drot in door_specs:
        add_node(dname, translation=dpos, rotation=quat_y(drot) if abs(drot) > 1e-4 else None)

    # Finalize collision node
    meshes_list[idx_collision] = (coll.bucket, {"collision_only": True})
    add_node("collision", mesh=idx_collision, extras={"collision_only": True})

    out_name = f"great-meridian-{state_id}.glb"
    out_path = R / out_name
    fsize = write_glb(out_path, mats, meshes_list, nodes, scene_name=f"GreatMeridian_{state_id}")

    mesh_tris = [b.tri_count() for b, _ in meshes_list]
    vis_tris = sum(
        mesh_tris[n["mesh"]] for n in nodes
        if "mesh" in n and n["name"] != "collision" and not n.get("extras", {}).get("collision_only")
    )
    print(f"Wrote {out_name} ({fsize} bytes) | vis={vis_tris} tris | coll={len(coll.boxes)} boxes")
    return {
        "state": state_id,
        "file": out_name,
        "visible_triangles_instanced": vis_tris,
        "collision_boxes": coll.boxes,
        "doors": {n: [round(v, 4) for v in p] for n, p, _ in door_specs},
        "floods": floods_meta,
        "walkable_floors": sorted(floor_buckets.keys()),
    }


# ==============================================================================
# DISTANT LANDMARK SILHOUETTE LOD: great-meridian-far.glb (< 400 TRIS!)
# Built strictly from the new 3-part iconic silhouette + glowing beacon orb!
# ==============================================================================
def build_far_lod():
    regions = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    far_mats = [
        {"name": "stone_limestone", "base": "stone_limestone_clean.png", "roughness": 0.90},
        {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.92},
        {"name": "copper_spire", "base": "copper_verdigris.png", "metallic": 0.25, "roughness": 0.65},
        {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.55, "roughness": 0.45},
        {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
        {"name": "glow_monument", "base": "glow_monument_base.png", "emissive": "glow_monument_mask.png",
         "emissiveFactor": [1.0, 1.0, 1.0], "roughness": 0.45},
    ]
    f_idx = {m["name"]: i for i, m in enumerate(far_mats)}
    b = MeshBucket("great_meridian_far", f_idx, MAT_SIZE, regions)

    CZ = -773.0
    # 1. Acropolis plinth + reflecting pool stripe
    b.add_box(-360, 0, CZ - 360, 360, 56, CZ + 340, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-264, 56, CZ - 264, 264, 84, CZ + 264, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_panel_y(-96, -430, 96, -56, 0.0, "+y", "water_pool")

    # 2. Monolithic 34 x 34 m Arch Block with 4 cut-through arch openings (y = 84..310)!
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (110, 219) if sx > 0 else (-219, -110)
            z0, z1 = (CZ + 110, CZ + 219) if sz > 0 else (CZ - 219, CZ - 110)
            b.add_box(x0, 84, z0, x1, 310, z1, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
    # 4 Arch bridge lintels completing the square arch block with clear open arches underneath!
    b.add_box(-110, 260, CZ + 110, 110, 310, CZ + 219, "stone_limestone")
    b.add_box(-110, 260, CZ - 219, 110, 310, CZ - 110, "stone_limestone")
    b.add_box(110, 260, CZ - 110, 219, 310, CZ + 110, "stone_limestone")
    b.add_box(-219, 260, CZ - 110, -110, 310, CZ + 110, "stone_limestone")

    # 3. Four tall slender corner piers with clean Art Deco setback (y = 310..748)!
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (116, 196) if sx > 0 else (-196, -116)
            z0, z1 = (CZ + 116, CZ + 196) if sz > 0 else (CZ - 196, CZ - 116)
            b.add_box(x0, 310, z0, x1, 460, z1, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
            x0b, x1b = (116, 176) if sx > 0 else (-176, -116)
            z0b, z1b = (CZ + 116, CZ + 176) if sz > 0 else (CZ - 176, CZ - 116)
            b.add_box(x0b, 460, z0b, x1b, 748, z1b, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])

    # 4. Suspended Golden Armillary Sphere silhouette (bold low-poly diamond/octahedron disc in open void!)
    b.add_sphere_faceted((0, 520, CZ), 86.0, 2, 6, "bronze_gilded")

    # 5. Square 58 m Deck Slab, Stepped Octagonal Lantern, Slender Verdigris Needle & Glowing Beacon Orb!
    b.add_box(-190, 724, CZ - 190, 190, 748, CZ + 190, "bronze_gilded")
    b.add_cylinder(0, 748, 954, CZ, 80.0, 76.0, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 954, 1302, CZ, 60.0, 11.0, 8, "copper_spire", cap_top=True, phase=math.pi / 8.0)
    # Glowing beacon orb on far model + finial tip at 105.54 m!
    b.add_box(-6.5, 1310, CZ - 6.5, 6.5, 1326, CZ + 6.5, "glow_monument", region_name="beacon_orb", fit_region=True)
    b.add_cylinder(0, 1326, 1360, CZ, 4.5, 0.4, 4, "bronze_gilded")

    nodes = [{"name": "great_meridian_far", "mesh": 0}]
    out_path = R / "great-meridian-far.glb"
    fsize = write_glb(out_path, far_mats, [(b, None)], nodes, scene_name="GreatMeridianFar")
    print(f"Wrote {out_path.name} ({fsize} bytes) | vis={b.tri_count()} tris")
    return {"file": out_path.name, "visible_triangles": b.tri_count()}


def main():
    # Remove obsolete v1 single-state GLB files so repo stays 100% lean
    for old_f in ("great-meridian-monument.glb", "great-meridian-monument-far.glb"):
        p = R / old_f
        if p.exists():
            p.unlink()

    state_meta = {}
    state_years = {
        "1926": {"years": "1926-1940", "label": "Dedication", "summary": "Clean pale limestone, fresh copper spire, cornice bunting swags, gas globe lamps, young plaza trees."},
        "1941": {"years": "1941-1975", "label": "Mature", "summary": "Darkened limestone, green verdigris spire, electric globe lamps, 1941-1945 war plaque beside main doors, mature trees."},
        "1976": {"years": "1976-1999", "label": "Worn", "summary": "Rainwater streaks from cornices, 3 broken lamps, East telescope missing, beacon off, stalled SE pier scaffolding."},
        "2000": {"years": "2000+", "label": "Restored", "summary": "Cleaned limestone, beacon relit, visible floodlight fixtures, East accessibility ramp, SW Visitor Centre pavilion."},
    }
    for sid in ("1926", "1941", "1976", "2000"):
        res = build_state(sid)
        state_meta[sid] = {
            **state_years[sid],
            "file": res["file"],
            "visible_triangles_instanced": res["visible_triangles_instanced"],
            "doors": res["doors"],
            "walkable_floors": res["walkable_floors"],
            "collision_box_count": len(res["collision_boxes"]),
        }
        if sid == "2000":
            ref_res = res

    far_info = build_far_lod()
    model_info = {
        "name": "The Great Meridian Monument",
        "slug": "great-meridian-monument",
        "pixel_grid_m_per_texel": TEXEL,
        "art_pixel_m": U,
        "coordinate_frame": "glTF metres, Y-up, +Z front, origin (0, 0, 0) at midpoint of +Z front curb",
        "footprint_m": {"width_x": round(1546 * U, 2), "depth_z": round(1546 * U, 2)},
        "heights_m": {
            "reflecting_pool_water": 0.0,
            "plaza_ground": round(2 * U, 3),
            "acropolis_rotunda_floor": round(84 * U, 2),
            "cornice_gallery_24m": round(310 * U, 2),
            "pier_setback_1": round(438 * U, 2),
            "armillary_sphere_center": round(520 * U, 2),
            "pier_setback_2": round(593 * U, 2),
            "sky_observation_deck_58m": round(748 * U, 2),
            "lantern_chamber_floor": round(796 * U, 2),
            "lantern_top": round(954 * U, 2),
            "spire_top": round(1302 * U, 2),
            "aviation_beacon_orb": round(1318 * U, 2),
            "finial_tip_total": round(1360 * U, 2),
        },
        "states": state_meta,
        "far_lod": far_info,
        "materials": [m["name"] for m in materials_for_state("2000")],
        "armillary_nodes": {
            "parent": "armillary_sphere",
            "outer_fixed": "armillary_outer",
            "ecliptic_rotating": {"node": "armillary_ecliptic", "tilt_deg": 23.4, "period": "1 game day"},
            "earth_globe_rotating": {"node": "armillary_globe", "polar_axis_tilt_deg": 23.4, "period": "1 game day"},
        },
        "beacon_beam": {
            "node": "beacon_beam",
            "position_m": [0.0, round(1318 * U, 4), round(-773 * U, 4)],
            "direction": [0.0, -0.05, 1.0],
            "rotation_period_s": 10.0,
        },
        "lift_car": {
            "node": "lift_car",
            "shaft_xz_m": [round(-152 * U, 4), round((-773 - 152) * U, 4)],
            "stops_y_m": [round(84 * U, 2), round(310 * U, 2), round(748 * U, 2)],
        },
        "floodlights": ref_res["floods"],
        "tree_nodes": [f"tree_{i:02d}" for i in range(1, 9)],
        "collision": {
            "coordinate_frame": "glTF metres (Y-up, +Z front — same frame as doors)",
            "count": len(ref_res["collision_boxes"]),
            "boxes_gltf_y_up": ref_res["collision_boxes"],
        },
    }
    (R / "model-info.json").write_text(json.dumps(model_info, indent=2) + "\n", encoding="utf-8")
    print("Wrote model-info.json for all 4 Great Meridian states + far LOD.")


if __name__ == "__main__":
    main()
