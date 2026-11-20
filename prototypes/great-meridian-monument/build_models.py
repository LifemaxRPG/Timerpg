#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) builder for THE GREAT MERIDIAN MONUMENT — REVISION 2:
  - Four Historical Near States (<= 30,000 tris, <= 12 materials, < 20 m2 glow, 0 UV stretch errors):
      * great-meridian-1926.glb (1926-1940, Dedication)
      * great-meridian-1941.glb (1941-1975, Mature / Wartime Precautions)
      * great-meridian-1976.glb (1976-1999, Worn / Stalled Repairs)
      * great-meridian-2000.glb (2000+, Restored & Accessible)
  - Middle LOD: great-meridian-mid.glb (4,000-6,000 tris, 500-2,000 m distance)
  - Far LOD:    great-meridian-far.glb (< 400 tris, 2,000 m+ landmark silhouette)
  - model-info.json: states, mid/far LODs, armillary axes, beacon_beam, lift_car, flood_* & flood_sphere_*
    (with direction & 3000K/gold colors), tree_*, walkable floor_*, door_*, and glTF Y-up collision boxes.
"""
import json
import math
import struct
from pathlib import Path

from monument_parts import (
    build_bench_bronze_mesh,
    build_bike_rack_mesh,
    build_brazier_eternal_mesh,
    build_drinking_fountain_mesh,
    build_far_lod,
    build_flagpole_civic_mesh,
    build_flood_fixture_mesh,
    build_guardian_statue,
    build_interp_panel_mesh,
    build_lamp_mesh,
    build_mid_lod,
    build_planter_mesh,
    build_scale_model_east_hall,
    build_telescope_deck_mesh,
    build_trash_bin_mesh,
    build_tree_mesh,
)

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
            if span_u > rw + 0.08 or span_v > rh + 0.08:
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

    def add_planar_tri_art(self, p0, p1, p2, mat_name, region_name=None, fit_region=False, edge_aligned=False):
        n = v_cross(v_sub(p1, p0), v_sub(p2, p0))
        if v_len(n) < 1e-8:
            return
        n = v_norm(n)
        pts = [p0, p1, p2]
        uvs = self._map_uvs_planar(pts, n, mat_name, region_name, fit_region, edge_aligned=edge_aligned)
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

    def add_tiled_panel_z(self, x0, y0, x1, y1, z, facing, mat_name, region_name, tile_w=60.0, tile_h=60.0):
        nx = max(1, int(math.ceil((x1 - x0) / tile_w)))
        ny = max(1, int(math.ceil((y1 - y0) / tile_h)))
        dx, dy = (x1 - x0) / nx, (y1 - y0) / ny
        for ix in range(nx):
            for iy in range(ny):
                self.add_panel_z(x0 + ix * dx, y0 + iy * dy, x0 + (ix + 1) * dx, y0 + (iy + 1) * dy, z, facing, mat_name, region_name)

    def add_tiled_panel_x(self, z0, y0, z1, y1, x, facing, mat_name, region_name, tile_w=60.0, tile_h=60.0):
        nz = max(1, int(math.ceil((z1 - z0) / tile_w)))
        ny = max(1, int(math.ceil((y1 - y0) / tile_h)))
        dz, dy = (z1 - z0) / nz, (y1 - y0) / ny
        for iz in range(nz):
            for iy in range(ny):
                self.add_panel_x(z0 + iz * dz, y0 + iy * dy, z0 + (iz + 1) * dz, y0 + (iy + 1) * dy, x, facing, mat_name, region_name)

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
            ea = (region_name is not None)
            if r_top > 0.05:
                self.add_planar_quad_art(b0, b1, t1, t0, mat_name, region_name, edge_aligned=ea)
            else:
                self.add_planar_tri_art(b0, b1, (cx, y1, cz), mat_name, region_name, edge_aligned=ea)
            if cap_top and r_top > 0.05:
                self.add_planar_tri_art((cx, y1, cz), t0, t1, mat_name, region_name, edge_aligned=ea)
            if cap_bot and r_bot > 0.05:
                self.add_planar_tri_art((cx, y0, cz), b1, b0, mat_name, region_name, edge_aligned=ea)

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
        ea = (region_name is not None)
        for i in range(4):
            j = (i + 1) % 4
            self.add_planar_quad_art(q0[i], q0[j], q1[j], q1[i], mat_name, region_name, edge_aligned=ea)
        if caps:
            self.add_planar_quad_art(q0[3], q0[2], q0[1], q0[0], mat_name, region_name, edge_aligned=ea)
            self.add_planar_quad_art(q1[0], q1[1], q1[2], q1[3], mat_name, region_name, edge_aligned=ea)

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
        ea = (region_name is not None)
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
                    self.add_planar_tri_art((cx, cy - radius, cz), p11, p01, mat_name, region_name, edge_aligned=ea)
                elif r_i == rings - 1:
                    self.add_planar_tri_art(p00, p10, (cx, cy + radius, cz), mat_name, region_name, edge_aligned=ea)
                else:
                    self.add_planar_quad_art(p00, p10, p11, p01, mat_name, region_name, edge_aligned=ea)

    def add_barrel_arch(self, cx, cy, cz, axis, r_in, r_out, half_depth, segs, mat_name,
                        spandrel_top_y=None, keystone=True, coffered=False, voussoirs=False):
        """Deep-reveal coffered triumphal stone arch with alternating voussoir ring & compass-star keystone!"""
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

            # Carved alternating light/dark voussoir ring around both arch faces!
            if voussoirs:
                self.add_planar_quad_art(fi0, fi1, fo1, fo0, "inscriptions", "voussoir_stone", fit_region=True, edge_aligned=True)
                self.add_planar_quad_art(bi1, bi0, bo0, bo1, "inscriptions", "voussoir_stone", fit_region=True, edge_aligned=True)
            else:
                self.add_planar_quad_art(fi0, fo0, fo1, fi1, mat_name)
                self.add_planar_quad_art(bi1, bo1, bo0, bi0, mat_name)

            # Deep coffered arch soffit tiled in <= 56 px slices along depth so ZERO detail_atlas quadrant bleed!
            if coffered:
                n_slices = max(1, int(math.ceil((2.0 * half_depth) / 56.0)))
                for si in range(n_slices):
                    d0 = -half_depth + (2.0 * half_depth) * si / n_slices
                    d1 = -half_depth + (2.0 * half_depth) * (si + 1) / n_slices
                    self.add_planar_quad_art(
                        pt(c0 * r_in, s0 * r_in, d0), pt(c0 * r_in, s0 * r_in, d1),
                        pt(c1 * r_in, s1 * r_in, d1), pt(c1 * r_in, s1 * r_in, d0),
                        "detail_atlas", "arch_coffer", edge_aligned=True
                    )
            else:
                self.add_planar_quad_art(bi0, fi0, fi1, bi1, mat_name)
            self.add_planar_quad_art(fo0, bo0, bo1, fo1, mat_name)

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

        # Projecting carved arch keystone with 8-point gilded compass star sitting cleanly on the voussoir ring!
        if keystone:
            for sgn in (1.0, -1.0):
                d0 = sgn * (half_depth - 1.5)
                d1 = sgn * (half_depth + 4.8)
                da, db = min(d0, d1), max(d0, d1)
                ky0, ky1 = cy + r_in - 3, cy + r_in + 13
                if axis == "z":
                    self.add_box(cx - 9, ky0, cz + da, cx + 9, ky1, cz + db, "stone_ashlar")
                    zf = cz + db + 0.3 if sgn > 0 else cz + da - 0.3
                    self.add_panel_z(cx - 9, ky0, cx + 9, ky1, zf,
                                     "+z" if sgn > 0 else "-z", "inscriptions", "keystone_star", fit_region=True)
                else:
                    self.add_box(cx + da, ky0, cz - 9, cx + db, ky1, cz + 9, "stone_ashlar")
                    xf = cx + db + 0.3 if sgn > 0 else cx + da - 0.3
                    self.add_panel_x(cz - 9, ky0, cz + 9, ky1, xf,
                                     "+x" if sgn > 0 else "-x", "inscriptions", "keystone_star", fit_region=True)

    def add_clean_vault_arch(self, cx, cy, cz, axis, r_in, r_out, half_depth, segs):
        """ONE crisp riveted-steel arch spanning pier to pier high at the vault's top (above the 18 m sphere!)."""
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
            self.add_beam_art(in_f[i], out_f[i + 1], 1.2, 1.2, "steel_truss")
            self.add_beam_art(in_b[i], out_b[i + 1], 1.2, 1.2, "steel_truss")
        for i in range(segs + 1):
            self.add_beam_art(in_f[i], out_f[i], 1.6, 1.6, "steel_truss")
            self.add_beam_art(in_b[i], out_b[i], 1.6, 1.6, "steel_truss")
            self.add_beam_art(in_f[i], in_b[i], 1.4, 1.4, "steel_truss")
            self.add_beam_art(out_f[i], out_b[i], 1.5, 1.5, "steel_truss")

    def tri_count(self):
        return sum(len(pr["idx"]) // 3 for pr in self.prims.values())


# ==============================================================================
# COLLISION RECORDER — STRICTLY IN GLTF Y-UP (+Z FRONT) METRES!
# ==============================================================================
class CollisionRecorder:
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
            "center": [round(cx, 4), round(cy, 4), round(cz, 4)],
            "size": [round(sx, 4), round(sy, 4), round(sz, 4)],
            "height_top_m": round(y1 * U, 4),
        })


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
            pos_b, nrm_b, uv_b, idx_b = bytearray(), bytearray(), bytearray(), bytearray()
            mn, mx = [1e9, 1e9, 1e9], [-1e9, -1e9, -1e9]
            for v in pr["pos"]:
                pos_b.extend(struct.pack("<3f", *v))
                for k in range(3):
                    mn[k] = min(mn[k], v[k])
                    mx[k] = max(mx[k], v[k])
            for n in pr["norm"]:
                nrm_b.extend(struct.pack("<3f", *n))
            for uv in pr["uv"]:
                uv_b.extend(struct.pack("<2f", *uv))
            for ii in pr["idx"]:
                idx_b.extend(struct.pack("<I", ii))

            bv_pos = append_bytes(pos_b, 34962)
            bv_nrm = append_bytes(nrm_b, 34962)
            bv_uv = append_bytes(uv_b, 34962)
            bv_idx = append_bytes(idx_b, 34963)

            acc_pos = len(accessors)
            accessors.append({"bufferView": bv_pos, "componentType": 5126, "count": len(pr["pos"]), "type": "VEC3", "min": mn, "max": mx})
            acc_nrm = len(accessors)
            accessors.append({"bufferView": bv_nrm, "componentType": 5126, "count": len(pr["norm"]), "type": "VEC3"})
            acc_uv = len(accessors)
            accessors.append({"bufferView": bv_uv, "componentType": 5126, "count": len(pr["uv"]), "type": "VEC2"})
            acc_idx = len(accessors)
            accessors.append({"bufferView": bv_idx, "componentType": 5125, "count": len(pr["idx"]), "type": "SCALAR"})
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
        "asset": {"version": "2.0", "generator": "great-meridian-monument-builder-rev2"},
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
# EXACT 12 MATERIALS PER ERA STATE (THREE STONES + 4 COPPER ERAS + WEEDS/TAPE!)
# ==============================================================================
def materials_for_state(state_id):
    return [
        {"name": "stone_limestone", "base": f"stone_limestone_{state_id}.png", "roughness": 0.88},
        {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.84},
        {"name": "stone_ashlar", "base": "stone_ashlar.png", "roughness": 0.86},
        {"name": "steel_truss", "base": "steel_truss.png", "metallic": 0.45, "roughness": 0.58},
        {"name": "copper_spire", "base": f"copper_{state_id}.png", "metallic": 0.30, "roughness": 0.60},
        {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.62, "roughness": 0.40},
        {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
        {"name": "marble_plaza", "base": "marble_plaza_1976.png" if state_id == "1976" else "marble_plaza.png", "roughness": 0.72},
        {"name": "glass_cathedral", "base": "glass_cathedral_1941.png" if state_id == "1941" else "glass_cathedral.png",
         "alphaMode": "BLEND", "roughness": 0.22, "doubleSided": True},
        {"name": "detail_atlas", "base": "detail_atlas.png", "roughness": 0.86},
        {"name": "inscriptions", "base": "inscriptions.png", "roughness": 0.86},
        {"name": "glow_monument", "base": "glow_monument_base.png", "emissive": "glow_monument_mask.png",
         "emissiveFactor": [1.0, 1.0, 1.0], "roughness": 0.45, "doubleSided": True},
    ]


MAT_SIZE = {
    "stone_limestone": (64, 64),
    "stone_granite": (64, 64),
    "stone_ashlar": (64, 64),
    "steel_truss": (64, 64),
    "copper_spire": (64, 64),
    "bronze_gilded": (64, 64),
    "water_pool": (64, 64),
    "marble_plaza": (64, 64),
    "glass_cathedral": (128, 128),
    "detail_atlas": (128, 128),
    "inscriptions": (512, 512),
    "glow_monument": (64, 64),
}


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
    CZ = -773.0
    Y_PLAZA = 2.0      # +0.155 m plaza paving (water right at y = 0.0!)
    Y_T1 = 28.0        # 2.17 m Tier 1 acropolis
    Y_T2 = 56.0        # 4.35 m Tier 2 stylobate
    Y_T3 = 84.0        # 6.52 m Tier 3 Rotunda & Memorial Halls
    Y_CORNICE = 310.0  # 24.06 m Arch Block top & Cornice Gallery
    Y_SET1 = 438.0     # 33.99 m Pier Setback 1
    Y_LEDGE = 574.0    # 44.54 m Guardian Corner Ledges (8.2 m sculpted statues -> 53.3 m, proud against sky!)
    Y_SET2 = 593.0     # 46.02 m Pier Setback 2
    Y_DECK = 748.0     # 58.04 m Square Sky Observation Deck
    Y_L1_TOP = 864.0   # 67.05 m Crown Tier 1 top
    Y_L2_TOP = 935.0   # 72.56 m Crown Tier 2 top
    Y_L3_TOP = 980.0   # 76.05 m Crown Tier 3 top (18 m tall 3-tier lantern!)
    Y_SUN_TOP = 1018.0 # 79.00 m 16-rib Gilded Sunburst Drum top
    Y_SPIRE = 1304.0   # 101.19 m Ribbed Copper Spire top
    Y_BEACON = 1322.0  # 102.59 m Open Beacon Cage Orb center
    Y_TIP = 1368.0     # 106.16 m Lightning Finial tip

    floor_buckets = {}

    # ==========================================================================
    # 1. CEREMONIAL PLAZA, REFLECTING POOL (0.5 m WALKABLE GRANITE COPING, WATER AT Y=0,
    #    3 BASINS x 3 LOW WATER-WHITE FOUNTAIN JETS) + SEMICIRCULAR GRANITE EXEDRA!
    # ==========================================================================
    rp_x, rp_z0, rp_z1 = 96.0, -346.0, -108.0  # 18.5 m x 14.9 m clean pool — zero overlap with South stairs!

    fb_plaza = MeshBucket("floor_plaza", mat_idx, MAT_SIZE, regions)
    fb_plaza.add_panel_y(-HALF, -2 * HALF, HALF, rp_z0 - 7, Y_PLAZA, "+y", "marble_plaza")
    fb_plaza.add_panel_y(-HALF, rp_z1 + 7, HALF, 0, Y_PLAZA, "+y", "marble_plaza")
    fb_plaza.add_panel_y(-HALF, rp_z0 - 7, -rp_x - 7, rp_z1 + 7, Y_PLAZA, "+y", "marble_plaza")
    fb_plaza.add_panel_y(rp_x + 7, rp_z0 - 7, HALF, rp_z1 + 7, Y_PLAZA, "+y", "marble_plaza")
    # Walkable 0.5 m (6.5 art px) dark-granite reflecting pool coping rim!
    fb_plaza.add_panel_y(-rp_x - 7, rp_z0 - 7, rp_x + 7, rp_z0, 6.5, "+y", "stone_granite")
    fb_plaza.add_panel_y(-rp_x - 7, rp_z1, rp_x + 7, rp_z1 + 7, 6.5, "+y", "stone_granite")
    fb_plaza.add_panel_y(-rp_x - 7, rp_z0, -rp_x, rp_z1, 6.5, "+y", "stone_granite")
    fb_plaza.add_panel_y(rp_x, rp_z0, rp_x + 7, rp_z1, 6.5, "+y", "stone_granite")
    # Continuous Inlaid Gilded Bronze MERIDIAN LINE along X = 0 across the entire plaza!
    fb_plaza.add_panel_y(-4.0, rp_z1 + 7, 4.0, -14.0, Y_PLAZA + 0.25, "+y", "bronze_gilded")
    fb_plaza.add_panel_y(-4.0, CZ + 340, 4.0, rp_z0 - 7, Y_PLAZA + 0.25, "+y", "bronze_gilded")
    fb_plaza.add_panel_y(-4.0, -2 * HALF, 4.0, CZ - 360, Y_PLAZA + 0.25, "+y", "bronze_gilded")
    floor_buckets["floor_plaza"] = fb_plaza

    lot_ground.add_box(-HALF, 0, -2 * HALF, HALF, Y_PLAZA, 0, "stone_granite", faces=["+z", "-z", "+x", "-x"])

    # Reflecting Pool Water at y = 0.0 + submerged granite basin below + coping sides
    lot_ground.add_panel_y(-rp_x, rp_z0, rp_x, rp_z1, 0.0, "+y", "water_pool")
    lot_ground.add_panel_y(-rp_x, rp_z0, rp_x, rp_z1, -8.0, "+y", "stone_granite")
    lot_ground.add_box(-rp_x - 7, -8, rp_z0 - 7, rp_x + 7, 6.5, rp_z0, "stone_granite", faces=["+z", "-z", "+x", "-x"])
    lot_ground.add_box(-rp_x - 7, -8, rp_z1, rp_x + 7, 6.5, rp_z1 + 7, "stone_granite", faces=["+z", "-z", "+x", "-x"])
    lot_ground.add_box(-rp_x - 7, -8, rp_z0, -rp_x, 6.5, rp_z1, "stone_granite", faces=["+z", "-z", "+x", "-x"])
    lot_ground.add_box(rp_x, -8, rp_z0, rp_x + 7, 6.5, rp_z1, "stone_granite", faces=["+z", "-z", "+x", "-x"])
    # 3 Basins x 3 Low Water-White Prism Fountain Jets (thin glowing rim at night!)
    for fz in (-298, -227, -156):
        lot_ground.add_cylinder(0, -8.0, 3.0, fz, 12.0, 9.5, 8, "stone_granite", cap_top=True)
        lot_ground.add_cylinder(0, 3.0, 6.5, fz, 9.5, 11.5, 8, "bronze_gilded", cap_top=True)
        for jx in (-6.0, 0.0, 6.0):
            jh = 15.5 if jx == 0.0 else 12.0
            # Low water-white prism jet + ultra-thin glowing rim (~0.54 m2 across all 9 jets!)
            lot_ground.add_cylinder(jx, 6.5, jh, fz, 1.4, 0.6, 4, "stone_ashlar")
            lot_ground.add_panel_z(jx - 0.55, 7.0, jx + 0.55, jh, fz + 0.9, "+z", "glow_monument", "fountain_jet", fit_region=True)
    coll.add_box(-rp_x, 0, rp_z0, rp_x, 6.5, rp_z1, "grand_reflecting_pool_water", "lot")

    # SEMICIRCULAR GRANITE EXEDRA at the far (+Z) end of the Reflecting Pool!
    # Curved bench wall centered at (0, -98), curving southward (+Z) cleanly inside z <= 0!
    ex_cz = -98.0
    ex_r_in, ex_r_out = 74.0, 88.0
    ex_segs = 10
    for si in range(ex_segs):
        a0 = 0.08 * math.pi + 0.84 * math.pi * si / ex_segs
        a1 = 0.08 * math.pi + 0.84 * math.pi * (si + 1) / ex_segs
        c0, s0, c1, s1 = math.cos(a0), math.sin(a0), math.cos(a1), math.sin(a1)
        # Curved granite backrest wall (y = 2..19) + lower curved stone bench seat (y = 2..8.5)
        for (ra, rb, y_hi, mat_e) in ((ex_r_in, ex_r_out, 19.0, "stone_granite"), (ex_r_in - 8.0, ex_r_in, 8.2, "stone_ashlar")):
            p00 = (c0 * ra, Y_PLAZA, ex_cz + s0 * ra)
            p10 = (c1 * ra, Y_PLAZA, ex_cz + s1 * ra)
            p11 = (c1 * rb, Y_PLAZA, ex_cz + s1 * rb)
            p01 = (c0 * rb, Y_PLAZA, ex_cz + s0 * rb)
            t00 = (c0 * ra, y_hi, ex_cz + s0 * ra)
            t10 = (c1 * ra, y_hi, ex_cz + s1 * ra)
            t11 = (c1 * rb, y_hi, ex_cz + s1 * rb)
            t01 = (c0 * rb, y_hi, ex_cz + s0 * rb)
            lot_ground.add_planar_quad_art(t00, t01, t11, t10, mat_e)  # top
            lot_ground.add_planar_quad_art(p10, p00, t00, t10, mat_e)  # inner concave face
            lot_ground.add_planar_quad_art(p01, p11, t11, t01, mat_e)  # outer convex face
        # Collision box along curved exedra wall segment
        xs = [c0 * ex_r_in, c1 * ex_r_in, c0 * ex_r_out, c1 * ex_r_out]
        zs = [ex_cz + s0 * ex_r_in, ex_cz + s1 * ex_r_in, ex_cz + s0 * ex_r_out, ex_cz + s1 * ex_r_out]
        coll.add_box(min(xs), 0, min(zs), max(xs), 20.0, max(zs), f"reflecting_pool_exedra_{si}", "lot")
    # Crisp flush granite dedication lintel at the south center of the Exedra: "ERECTED BY THE PEOPLE OF THE STATE MCMXXVI"!
    lot_ground.add_box(-122, Y_PLAZA, ex_cz + ex_r_out - 4, 122, 23.0, ex_cz + ex_r_out + 4, "stone_granite")
    lot_ground.add_box(-124, 22.0, ex_cz + ex_r_out - 5, 124, 24.5, ex_cz + ex_r_out + 5, "stone_ashlar")
    lot_ground.add_panel_z(-120, 6.0, 120, 22.0, ex_cz + ex_r_out - 4.3, "-z", "inscriptions", "exedra_text", fit_region=True)
    lot_ground.add_panel_z(-120, 6.0, 120, 22.0, ex_cz + ex_r_out + 4.3, "+z", "inscriptions", "exedra_text", fit_region=True)
    coll.add_box(-122, 0, ex_cz + ex_r_out - 4, 122, 24.5, ex_cz + ex_r_out + 4, "exedra_dedication_wall", "lot")

    # 4 Manicured Ceremonial Park Lawns
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

    # Low Dark-Granite Perimeter Wall with broad cardinal openings
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
        lot_ground.add_box(gx - 12, Y_PLAZA, -16, gx + 12, 34, 0, "stone_granite")
        lot_ground.add_box(gx - 14, 34, -18, gx + 14, 39, 2, "stone_ashlar")
        coll.add_box(gx - 12, 0, -16, gx + 12, 39, 0, f"entry_gate_pier_{gx}", "lot")

    # ==========================================================================
    # 2. THREE-TIER DARK GRANITE ACROPOLIS + FOUR GRAND STAIRCASES WITH MID-LANDINGS,
    #    CENTRAL BRONZE HANDRAILS & MASSIVE CHEEK WALLS (+ ERA-SPECIFIC STAIR PROPS!)
    # ==========================================================================
    structure.add_box(-360, Y_PLAZA, CZ - 360, 360, Y_T1, CZ + 340, "stone_granite")
    structure.add_panel_y(-360, CZ - 360, 360, CZ + 340, Y_T1 + 0.1, "+y", "marble_plaza")
    structure.add_box(-312, Y_T1, CZ - 312, 312, Y_T2, CZ + 300, "stone_granite")
    structure.add_panel_y(-312, CZ - 312, 312, CZ + 300, Y_T2 + 0.1, "+y", "marble_plaza")
    structure.add_box(-264, Y_T2, CZ - 264, 264, Y_T3, CZ + 264, "stone_granite")
    structure.add_box(-267, Y_T3 - 4, CZ - 267, 267, Y_T3, CZ + 267, "stone_ashlar", faces=["+z", "-z", "+x", "-x"])
    coll.add_box(-360, 0, CZ - 360, 360, Y_T1, CZ + 340, "acropolis_tier1", "exterior")
    coll.add_box(-312, Y_T1, CZ - 312, 312, Y_T2, CZ + 300, "acropolis_tier2", "exterior")
    coll.add_box(-264, Y_T2, CZ - 264, 264, Y_T3, CZ + 264, "acropolis_tier3", "exterior")

    def add_grand_stair(side, half_w, z_top_dist, steps_n=14, step_run=8.4):
        fb_st = MeshBucket(f"floor_stairs_{side.replace('+', 'p').replace('-', 'm')}", mat_idx, MAT_SIZE, regions)
        dy = (Y_T3 - Y_PLAZA) / steps_n
        mid_st = steps_n // 2
        landing_extra = 14.0

        def dist_for(st):
            # Adds a flat ceremonial mid-landing halfway up the flight!
            d = z_top_dist + (steps_n - st) * step_run
            if st < mid_st:
                d += landing_extra
            return d

        for st in range(steps_n):
            y1 = Y_PLAZA + (st + 1) * dy
            d_outer = dist_for(st)
            d_inner = d_outer - (step_run + (landing_extra if st == mid_st - 1 else 0.0))
            if side == "+z":
                structure.add_box(-half_w, Y_PLAZA, CZ + d_inner, half_w, y1, CZ + d_outer, "stone_granite", faces=["+z", "+x", "-x"])
                fb_st.add_panel_y(-half_w, CZ + d_inner, half_w, CZ + d_outer, y1, "+y", "stone_granite")
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

        d_end = dist_for(0)
        # Bronze handrails down the middle of the wide flights (flanking the Meridian strip on N/S, centered on E/W!)
        rail_offsets = (-32.0, 32.0) if side in ("+z", "-z") else (-26.0, 26.0)
        for roff in rail_offsets:
            if side == "+z":
                structure.add_beam_art((roff, Y_PLAZA + 12, CZ + d_end), (roff, Y_T3 + 12, CZ + z_top_dist), 1.0, 1.0, "bronze_gilded")
                for rp in (0.0, 0.5, 1.0):
                    rz = CZ + d_end - rp * (d_end - z_top_dist)
                    ry = Y_PLAZA + rp * (Y_T3 - Y_PLAZA)
                    structure.add_box(roff - 1.0, ry, rz - 1.0, roff + 1.0, ry + 12, rz + 1.0, "bronze_gilded")
            elif side == "-z":
                structure.add_beam_art((roff, Y_PLAZA + 12, CZ - d_end), (roff, Y_T3 + 12, CZ - z_top_dist), 1.0, 1.0, "bronze_gilded")
                for rp in (0.0, 0.5, 1.0):
                    rz = CZ - d_end + rp * (d_end - z_top_dist)
                    ry = Y_PLAZA + rp * (Y_T3 - Y_PLAZA)
                    structure.add_box(roff - 1.0, ry, rz - 1.0, roff + 1.0, ry + 12, rz + 1.0, "bronze_gilded")
            elif side == "+x":
                structure.add_beam_art((d_end, Y_PLAZA + 12, CZ + roff), (z_top_dist, Y_T3 + 12, CZ + roff), 1.0, 1.0, "bronze_gilded")
                for rp in (0.0, 0.5, 1.0):
                    rx = d_end - rp * (d_end - z_top_dist)
                    ry = Y_PLAZA + rp * (Y_T3 - Y_PLAZA)
                    structure.add_box(rx - 1.0, ry, CZ + roff - 1.0, rx + 1.0, ry + 12, CZ + roff + 1.0, "bronze_gilded")
            elif side == "-x":
                structure.add_beam_art((-d_end, Y_PLAZA + 12, CZ + roff), (-z_top_dist, Y_T3 + 12, CZ + roff), 1.0, 1.0, "bronze_gilded")
                for rp in (0.0, 0.5, 1.0):
                    rx = -d_end + rp * (d_end - z_top_dist)
                    ry = Y_PLAZA + rp * (Y_T3 - Y_PLAZA)
                    structure.add_box(rx - 1.0, ry, CZ + roff - 1.0, rx + 1.0, ry + 12, CZ + roff + 1.0, "bronze_gilded")

        # Massive Granite Cheek Walls flanking each staircase (main South cheek walls carry the 4 Eternal Flame Braziers!)
        cw = 22.0 if side == "+z" else 16.0
        for sgn in (-1, 1):
            c0 = sgn * (half_w + (0 if sgn > 0 else cw))
            c1 = c0 + cw
            if side == "+z":
                structure.add_box(c0, Y_PLAZA, CZ + z_top_dist, c1, Y_T3 + 6, CZ + z_top_dist + 48, "stone_granite")
                structure.add_box(c0, Y_PLAZA, CZ + z_top_dist + 48, c1, Y_T2 + 4, CZ + d_end - 22, "stone_granite")
                structure.add_box(c0, Y_PLAZA, CZ + d_end - 22, c1, Y_T1 + 6, CZ + d_end + 8, "stone_granite")
                coll.add_box(c0, 0, CZ + z_top_dist, c1, Y_T3 + 6, CZ + d_end + 8, f"stair_cheek_s_{sgn}", "exterior")
            elif side == "-z":
                structure.add_box(c0, Y_PLAZA, CZ - z_top_dist - 48, c1, Y_T3 + 6, CZ - z_top_dist, "stone_granite")
                structure.add_box(c0, Y_PLAZA, CZ - d_end, c1, Y_T2 + 4, CZ - z_top_dist - 48, "stone_granite")
                coll.add_box(c0, 0, CZ - d_end, c1, Y_T3 + 6, CZ - z_top_dist, f"stair_cheek_n_{sgn}", "exterior")
            elif side == "+x":
                structure.add_box(z_top_dist, Y_PLAZA, CZ + c0, z_top_dist + 48, Y_T3 + 6, CZ + c1, "stone_granite")
                structure.add_box(z_top_dist + 48, Y_PLAZA, CZ + c0, d_end, Y_T2 + 4, CZ + c1, "stone_granite")
                coll.add_box(z_top_dist, 0, CZ + c0, d_end, Y_T3 + 6, CZ + c1, f"stair_cheek_e_{sgn}", "exterior")
            elif side == "-x":
                structure.add_box(-z_top_dist - 48, Y_PLAZA, CZ + c0, -z_top_dist, Y_T3 + 6, CZ + c1, "stone_granite")
                structure.add_box(-d_end, Y_PLAZA, CZ + c0, -z_top_dist - 48, Y_T2 + 4, CZ + c1, "stone_granite")
                coll.add_box(-d_end, 0, CZ + c0, -z_top_dist, Y_T3 + 6, CZ + c1, f"stair_cheek_w_{sgn}", "exterior")

    add_grand_stair("+z", 94.0, 264.0, steps_n=14, step_run=7.2)
    add_grand_stair("-z", 80.0, 264.0, steps_n=12, step_run=8.4)
    add_grand_stair("+x", 80.0, 264.0, steps_n=12, step_run=8.4)
    add_grand_stair("-x", 80.0, 264.0, steps_n=12, step_run=8.4)

    # ERA-SPECIFIC GRAND STAIRCASE ADDITIONS:
    # 1926: Temporary Timber Dedication Stand (platform, steps, lectern & railings cleanly on forecourt between stairs & pool!)
    if state_id == "1926":
        ds_z0, ds_z1 = -390.0, -360.0
        for sx_a, sx_b in ((-60.0, 0.0), (0.0, 60.0)):
            lot_ground.add_box(sx_a, Y_PLAZA, ds_z0, sx_b, Y_PLAZA + 14.0, ds_z1, "detail_atlas", region_name="timber_bench")
        # Front steps & ceremonial podium + bunting skirt + timber railings
        lot_ground.add_box(-18, Y_PLAZA, ds_z1, 18, Y_PLAZA + 7.0, ds_z1 + 6.5, "detail_atlas", region_name="timber_bench")
        lot_ground.add_box(-10, Y_PLAZA + 14.0, ds_z1 - 12.0, 10, Y_PLAZA + 28.0, ds_z1 - 2.0, "detail_atlas", region_name="timber_bench")
        lot_ground.add_panel_z(-56, Y_PLAZA + 2, 0, Y_PLAZA + 14, ds_z1 + 0.4, "+z", "inscriptions", "bunting_swag", fit_region=True)
        lot_ground.add_panel_z(0, Y_PLAZA + 2, 56, Y_PLAZA + 14, ds_z1 + 0.4, "+z", "inscriptions", "bunting_swag", fit_region=True)
        for rx in (-58, -28, 28, 58):
            lot_ground.add_box(rx - 1.2, Y_PLAZA + 14, ds_z0 + 2, rx + 1.2, Y_PLAZA + 26, ds_z0 + 4.4, "detail_atlas", region_name="timber_bench")
        lot_ground.add_box(-59, Y_PLAZA + 25, ds_z0 + 2, 0, Y_PLAZA + 27, ds_z0 + 4.4, "detail_atlas", region_name="timber_bench")
        lot_ground.add_box(0, Y_PLAZA + 25, ds_z0 + 2, 59, Y_PLAZA + 27, ds_z0 + 4.4, "detail_atlas", region_name="timber_bench")
        coll.add_box(-60, 0, ds_z0, 60, Y_PLAZA + 28, ds_z1 + 6.5, "dedication_stand_1926", "lot")

    # 1941: Stacked Wartime Olive-Tan Sandbag Emplacements flanking the base of the main South stairs!
    if state_id == "1941":
        for si, (sb_x0, sb_x1, sb_z0, sb_z1) in enumerate((
            (-116, -68, -390, -374),
            (68, 116, -390, -374),
            (-128, -112, -422, -398),
            (-128, -112, -398, -374),
            (112, 128, -422, -398),
            (112, 128, -398, -374),
        )):
            lot_ground.add_box(sb_x0, Y_PLAZA, sb_z0, sb_x1, Y_PLAZA + 20.0, sb_z1, "inscriptions", region_name="sandbag_wall", fit_region=True)
            coll.add_box(sb_x0, 0, sb_z0, sb_x1, Y_PLAZA + 20.0, sb_z1, f"wartime_sandbags_1941_{si}", "lot")

    # 1976: Hand-painted "CLOSED FOR REPAIRS" Sign on Timber Sawhorse blocking the North stairs!
    if state_id == "1976":
        nz_s = CZ - 392.0
        for sx in (-20, 20):
            lot_ground.add_beam_art((sx, Y_PLAZA, nz_s - 8), (sx, Y_PLAZA + 22, nz_s), 1.5, 1.5, "detail_atlas", "timber_bench")
            lot_ground.add_beam_art((sx, Y_PLAZA, nz_s + 8), (sx, Y_PLAZA + 22, nz_s), 1.5, 1.5, "detail_atlas", "timber_bench")
        lot_ground.add_box(-26, Y_PLAZA + 14, nz_s - 2.0, 26, Y_PLAZA + 34, nz_s + 2.0, "detail_atlas", region_name="timber_bench")
        lot_ground.add_panel_z(-24, Y_PLAZA + 14, 24, Y_PLAZA + 34, nz_s - 2.3, "-z", "inscriptions", "closed_repairs_sign", fit_region=True)
        lot_ground.add_panel_z(-24, Y_PLAZA + 14, 24, Y_PLAZA + 34, nz_s + 2.3, "+z", "inscriptions", "closed_repairs_sign", fit_region=True)
        coll.add_box(-28, 0, nz_s - 10, 28, Y_PLAZA + 34, nz_s + 10, "closed_for_repairs_sawhorse_1976", "lot")

    # 2000+: Accessibility switchback stone-and-bronze ramp beside the East Grand Staircase!
    if state_id == "2000":
        fb_ramp = MeshBucket("floor_access_ramp", mat_idx, MAT_SIZE, regions)
        rx0, rx1 = 360.0, 412.0
        rz_s, rz_n = CZ + 100.0, CZ + 280.0
        fb_ramp.add_planar_quad_art((388, Y_PLAZA + 0.2, rz_n), (412, Y_PLAZA + 0.2, rz_n),
                                    (412, 43.0, rz_s + 24), (388, 43.0, rz_s + 24), "stone_granite")
        structure.add_box(388, Y_PLAZA, rz_s + 24, 412, 42.5, rz_n, "stone_granite", faces=["+x", "-x", "+z", "-z"])
        structure.add_box(rx0, Y_PLAZA, rz_s, rx1, 43.0, rz_s + 24, "stone_granite")
        fb_ramp.add_panel_y(rx0, rz_s, rx1, rz_s + 24, 43.2, "+y", "marble_plaza")
        fb_ramp.add_planar_quad_art((360, 43.2, rz_s + 24), (386, 43.2, rz_s + 24),
                                    (386, Y_T3, rz_n - 20), (360, Y_T3, rz_n - 20), "stone_granite")
        structure.add_box(360, Y_PLAZA, rz_s + 24, 386, Y_T3 - 0.5, rz_n - 20, "stone_granite", faces=["+x", "-x", "+z", "-z"])
        structure.add_box(264, Y_T2, rz_n - 46, 386, Y_T3, rz_n - 16, "stone_granite")
        fb_ramp.add_panel_y(264, rz_n - 46, 386, rz_n - 16, Y_T3 + 0.2, "+y", "marble_plaza")
        structure.add_beam_art((411, Y_PLAZA + 13, rz_n), (411, 56, rz_s + 24), 1.2, 1.2, "bronze_gilded")
        structure.add_beam_art((361, 56, rz_s + 24), (361, Y_T3 + 13, rz_n - 20), 1.2, 1.2, "bronze_gilded")
        floor_buckets["floor_access_ramp"] = fb_ramp
        coll.add_box(360, 0, rz_s, 412, Y_T3 + 12, rz_n, "accessibility_ramp_east", "exterior")

    # ==========================================================================
    # 3. PART 1 — MONOLITHIC 34.8 m SQUARE ARCH BLOCK (y = 84..310 = 6.52..24.06 m)
    #    Dark grey-pink granite lower 6 m + Pale cream limestone above + Smooth ashlar cornice,
    #    14 m Compass Rose w/ N/E/S/W, 5 m Open 6-Panel Bronze Doors, West Dedication Hall,
    #    East Hall (1:35 Scale Model + State Map), NE Cornerstone "1926"!
    # ==========================================================================
    ARCH_OUTER = 224.0  # 34.76 m square
    ARCH_INNER = 96.0   # 14.90 m wide triumphal arch opening -> 128 art px corner quadrants!
    Y_ARCH_SPRING = 154.0
    Y_GRAN_TOP = Y_T3 + 78.0  # 6.05 m dark grey-pink granite ground storey!

    # Walkable Rotunda Floor (`floor_rotunda_plaza`) with GIANT 14 m COMPASS ROSE (180x180 art px = 13.97 m!)
    fb_rot = MeshBucket("floor_rotunda_plaza", mat_idx, MAT_SIZE, regions)
    fb_rot.add_panel_y(-264, CZ - 264, 264, CZ + 264, Y_T3 + 0.15, "+y", "marble_plaza")
    # 14 m Compass Rose oriented so N points -Z, S points +Z, E points +X, W points -X!
    fb_rot.add_panel_y(-90.0, CZ - 90.0, 90.0, CZ + 90.0, Y_T3 + 0.45, "+y", "inscriptions", "compass_rose_14m", fit_region=True)
    # Continuous Inlaid Gilded Meridian Line running right through the 14 m Compass Rose!
    fb_rot.add_panel_y(-3.2, CZ - 264, 3.2, CZ + 264, Y_T3 + 0.58, "+y", "bronze_gilded")
    fb_rot.add_ring_3d((0, Y_T3 + 0.85, CZ), (1, 0, 0), (0, 0, -1), 91.5, 3.0, 0.8, 20, "bronze_gilded")
    floor_buckets["floor_rotunda_plaza"] = fb_rot

    # Walkable West & East Memorial Halls (interior 112 x 112 art px = 8.69 m square!)
    for tag, sx in (("hall_west", -1), ("hall_east", 1)):
        fb = MeshBucket(f"floor_{tag}", mat_idx, MAT_SIZE, regions)
        px0 = -216 if sx < 0 else 104
        px1 = -104 if sx < 0 else 216
        fb.add_panel_y(px0, CZ + 104, px1, CZ + 216, Y_T3 + 0.25, "+y", "marble_plaza")
        floor_buckets[f"floor_{tag}"] = fb

    # Four Massive Corner Quadrants of the Arch Block
    for sx in (-1, 1):
        for sz in (-1, 1):
            ptag = f"{'e' if sx > 0 else 'w'}{'s' if sz > 0 else 'n'}"
            x0 = ARCH_INNER if sx > 0 else -ARCH_OUTER
            x1 = ARCH_OUTER if sx > 0 else -ARCH_INNER
            z0 = CZ + (ARCH_INNER if sz > 0 else -ARCH_OUTER)
            z1 = CZ + (ARCH_OUTER if sz > 0 else -ARCH_INNER)
            T = 8.0
            zm = (z0 + z1) * 0.5
            xm = (x0 + x1) * 0.5

            def add_quad_wall(wx0, wy0, wz0, wx1, wy1, wz1):
                if wy0 < Y_GRAN_TOP:
                    structure.add_box(wx0, wy0, wz0, wx1, min(Y_GRAN_TOP, wy1), wz1, "stone_granite")
                if wy1 > Y_GRAN_TOP:
                    structure.add_box(wx0, max(Y_GRAN_TOP, wy0), wz0, wx1, wy1, wz1, "stone_limestone")

            # Outer walls + tall hall window openings on SW & SE outer faces (showing 1941 taped X-crosses!)
            if sx > 0:
                if sz > 0:
                    # SE East Memorial Hall outer east wall with tall cathedral hall window!
                    add_quad_wall(x1 - T, Y_T3, z0, x1, 294, zm - 22)
                    add_quad_wall(x1 - T, Y_T3, zm + 22, x1, 294, z1)
                    add_quad_wall(x1 - T, Y_T3, zm - 22, x1, Y_T3 + 18, zm + 22)
                    add_quad_wall(x1 - T, Y_T3 + 82, zm - 22, x1, 294, zm + 22)
                    structure.add_panel_x(zm - 22, Y_T3 + 18, zm + 22, Y_T3 + 82, x1 - 1.5, "+x", "glass_cathedral", "win_cathedral")
                else:
                    add_quad_wall(x1 - T, Y_T3, z0, x1, 294, z1)
                add_quad_wall(x0, Y_T3, z0, x0 + T, 294, zm - 22)
                add_quad_wall(x0, Y_T3, zm + 22, x0 + T, 294, z1)
                add_quad_wall(x0, Y_T3 + 56, zm - 22, x0 + T, 294, zm + 22)
                structure.add_box(x0 - 2, Y_T3, zm - 25, x0 + 2, Y_T3 + 59, zm - 22, "bronze_gilded")
                structure.add_box(x0 - 2, Y_T3, zm + 22, x0 + 2, Y_T3 + 59, zm + 25, "bronze_gilded")
                structure.add_box(x0 - 2, Y_T3 + 56, zm - 25, x0 + 2, Y_T3 + 61, zm + 25, "bronze_gilded")
            else:
                if sz > 0:
                    # SW West Memorial Hall outer south wall has cathedral hall window; inner west wall holds Dedication Wall!
                    add_quad_wall(x0, Y_T3, z0, x0 + T, 294, z1)
                else:
                    add_quad_wall(x0, Y_T3, z0, x0 + T, 294, z1)
                add_quad_wall(x1 - T, Y_T3, z0, x1, 294, zm - 22)
                add_quad_wall(x1 - T, Y_T3, zm + 22, x1, 294, z1)
                add_quad_wall(x1 - T, Y_T3 + 56, zm - 22, x1, 294, zm + 22)
                structure.add_box(x1 - 2, Y_T3, zm - 25, x1 + 2, Y_T3 + 59, zm - 22, "bronze_gilded")
                structure.add_box(x1 - 2, Y_T3, zm + 22, x1 + 2, Y_T3 + 59, zm + 25, "bronze_gilded")
                structure.add_box(x1 - 2, Y_T3 + 56, zm - 25, x1 + 2, Y_T3 + 61, zm + 25, "bronze_gilded")

            # North & South walls of quadrant (with hall windows on SW & SE south +Z facade!)
            add_quad_wall(x0 + T, Y_T3, z0, x1 - T, 294, z0 + T)
            if sz > 0:
                add_quad_wall(x0 + T, Y_T3, z1 - T, xm - 22, 294, z1)
                add_quad_wall(xm + 22, Y_T3, z1 - T, x1 - T, 294, z1)
                add_quad_wall(xm - 22, Y_T3, z1 - T, xm + 22, Y_T3 + 18, z1)
                add_quad_wall(xm - 22, Y_T3 + 82, z1 - T, xm + 22, 294, z1)
                structure.add_panel_z(xm - 22, Y_T3 + 18, xm + 22, Y_T3 + 82, z1 - 1.5, "+z", "glass_cathedral", "win_cathedral")
            else:
                add_quad_wall(x0 + T, Y_T3, z1 - T, x1 - T, 294, z1)

            # Smooth pale ashlar belt dividing granite lower storey (6 m) from pale cream limestone above!
            structure.add_box(x0 - 1.5, Y_GRAN_TOP - 2, z0 - 1.5, x1 + 1.5, Y_GRAN_TOP + 2, z1 + 1.5, "stone_ashlar",
                              faces=["+z", "-z", "+x", "-x"])

            # Cap ceiling inside SW/SE/NE chambers (NW stays open straight up into hollow elevator pier!)
            if not (sx < 0 and sz < 0):
                structure.add_box(x0, 286, z0, x1, 294, z1, "stone_limestone")
            else:
                structure.add_box(x0, 286, z0, -196, 294, z1, "stone_limestone")
                structure.add_box(-196, 286, z0, x1, 294, CZ - 196, "stone_limestone")

            # WEST MEMORIAL HALL (SW): Carved Dedication Wall (112 x 64 art px) + Low Gilded Bronze Rail in front!
            if sx < 0 and sz > 0:
                structure.add_panel_x(zm - 56.0, Y_T3 + 4.0, zm + 56.0, Y_T3 + 68.0, x0 + T + 0.5, "+x",
                                      "inscriptions", "dedication_wall", fit_region=True)
                # Low bronze museum rail standing 1.1 m (14 art px) in front of the Dedication Wall!
                rx_rail = x0 + T + 14.0
                structure.add_box(rx_rail - 1.0, Y_T3 + 11.0, zm - 52.0, rx_rail + 1.0, Y_T3 + 12.6, zm + 52.0, "bronze_gilded")
                for rz_p in (zm - 50, zm - 18, zm + 18, zm + 50):
                    structure.add_box(rx_rail - 1.2, Y_T3, rz_p - 1.2, rx_rail + 1.2, Y_T3 + 12.0, rz_p + 1.2, "bronze_gilded")

            # EAST MEMORIAL HALL (SE): 1:35 Scale Model of the Monument on a Plinth + State Wall Map w/ Meridian Line!
            if sx > 0 and sz > 0:
                build_scale_model_east_hall(structure, xm, Y_T3 + 0.25, zm)
                # Made-up State Wall Map (108 x 64 art px) mounted cleanly on the North inner wall (z0 + T) facing +Z!
                structure.add_box(xm - 56.0, Y_T3 + 4.0, z0 + T, xm + 56.0, Y_T3 + 70.0, z0 + T + 1.5, "bronze_gilded")
                structure.add_panel_z(xm - 54.0, Y_T3 + 5.0, xm + 54.0, Y_T3 + 69.0, z0 + T + 1.85, "+z",
                                      "inscriptions", "state_map", fit_region=True)

            # Original 1926 Dedication Plaque (SW front), 1941-1945 War Plaque (SE front in 1941+), NE Cornerstone "1926"!
            if sz > 0 and sx < 0:
                structure.add_panel_z(x1 - 30, Y_T3 + 14, x1 - 6, Y_T3 + 32, z1 + 0.6, "+z",
                                      "inscriptions", "plaque_dedication", fit_region=True)
            if sz > 0 and sx > 0 and state_id in ("1941", "1976", "2000"):
                structure.add_panel_z(x0 + 6, Y_T3 + 14, x0 + 30, Y_T3 + 32, z1 + 0.6, "+z",
                                      "inscriptions", "plaque_war", fit_region=True)
            if sz < 0 and sx > 0:
                # Eye-height NE Arch Block Cornerstone ("1926" + small carved compass) on NE outer corner!
                structure.add_panel_x(z0 + 4, Y_T3 + 16, z0 + 28, Y_T3 + 36, x1 + 0.6, "+x",
                                      "inscriptions", "cornerstone_1926", fit_region=True)
                structure.add_panel_z(x1 - 28, Y_T3 + 16, x1 - 4, Y_T3 + 36, z0 - 0.6, "-z",
                                      "inscriptions", "cornerstone_1926", fit_region=True)

            coll.add_box(x0, Y_T3, z0, x1, Y_CORNICE, z1, f"arch_block_corner_{ptag}", "exterior")

    # FOUR DEEP-REVEAL COFFERED TRIUMPHAL ARCHES + VOUSSOIRS + COMPASS KEYSTONE + INCISED FRIEZES!
    ARCH_MID = (ARCH_INNER + ARCH_OUTER) * 0.5  # 160.0
    ARCH_HD = (ARCH_OUTER - ARCH_INNER) * 0.5   # 64.0 (4.97 m deep coffered barrel vault!)
    for sz_s in (1, -1):
        az = CZ + sz_s * ARCH_MID
        structure.add_barrel_arch(0.0, Y_ARCH_SPRING, az, "z", ARCH_INNER, 108.0, ARCH_HD, 12,
                                  "stone_limestone", spandrel_top_y=264.0, keystone=True, coffered=True, voussoirs=True)
        # Smooth pale ashlar entablature frieze band (y = 264..294) cleanly above arch & compass keystone!
        structure.add_box(-ARCH_OUTER, 264, az - ARCH_HD, ARCH_OUTER, 294, az + ARCH_HD, "stone_ashlar")
        z_f = az + ARCH_HD + 0.35 if sz_s > 0 else az - ARCH_HD - 0.35
        structure.add_panel_z(-118, 265, 118, 293, z_f, "+z" if sz_s > 0 else "-z", "inscriptions", "frieze_main", fit_region=True)
        coll.add_box(-ARCH_INNER, 250, az - ARCH_HD, ARCH_INNER, Y_CORNICE, az + ARCH_HD, f"arch_lintel_z_{sz_s}", "exterior")

    for sx_s, reg_card in ((1, "frieze_east"), (-1, "frieze_west")):
        ax = sx_s * ARCH_MID
        structure.add_barrel_arch(ax, Y_ARCH_SPRING, CZ, "x", ARCH_INNER, 108.0, ARCH_HD, 12,
                                  "stone_limestone", spandrel_top_y=264.0, keystone=True, coffered=True, voussoirs=True)
        structure.add_box(ax - ARCH_HD, 264, CZ - ARCH_INNER, ax + ARCH_HD, 294, CZ + ARCH_INNER, "stone_ashlar")
        x_f = ax + ARCH_HD + 0.35 if sx_s > 0 else ax - ARCH_HD - 0.35
        structure.add_panel_x(CZ - 90, 268, CZ + 90, 291, x_f, "+x" if sx_s > 0 else "-x", "inscriptions", reg_card, fit_region=True)
        coll.add_box(ax - ARCH_HD, 250, CZ - ARCH_INNER, ax + ARCH_HD, Y_CORNICE, CZ + ARCH_INNER, f"arch_lintel_x_{sx_s}", "exterior")

    # TWO 2.2 m x 5.0 m (28 x 64 art px) EMBOSSED BRONZE DOORS FOLDED OPEN 90 DEG AGAINST SOUTH REVEALS!
    # Displaying all 6 pixel-art relief panels (Train, Plough, Ship, Factory, School, Bridge) to passers-by!
    for sgn in (-1, 1):
        dx_d = sgn * (ARCH_INNER - 2.6)
        z_da, z_db = CZ + ARCH_OUTER - 34.0, CZ + ARCH_OUTER - 6.0  # exact 28 art px = 2.17 m wide!
        y_da, y_db = Y_T3, Y_T3 + 64.0                               # exact 64 art px = 4.97 m tall!
        structure.add_box(dx_d - 2.2, y_da, z_da, dx_d + 2.2, y_db, z_db, "bronze_gilded")
        face_in = "-x" if sgn > 0 else "+x"
        x_pan = dx_d - 2.45 if sgn > 0 else dx_d + 2.45
        structure.add_panel_x(z_da, y_da, z_db, y_db, x_pan, face_in, "inscriptions", "bronze_door_leaf", fit_region=True)
        for hy in (Y_T3 + 12, Y_T3 + 32, Y_T3 + 52):
            structure.add_box(dx_d - 3.6, hy - 1.8, z_db - 2, dx_d + 3.6, hy + 1.8, z_db + 3, "detail_atlas", region_name="dark_iron")
        coll.add_box(dx_d - 2.8, y_da, z_da, dx_d + 2.8, y_db, z_db, f"bronze_door_open_{'e' if sgn > 0 else 'w'}", "exterior")

    # SMOOTH PALE ASHLAR 24 m CORNICE & WALKABLE CORNICE GALLERY (`floor_cornice_gallery`)!
    GAL_OUT = 230.0
    for (x0, z0, x1, z1) in (
        (-GAL_OUT, CZ - GAL_OUT, GAL_OUT, CZ - ARCH_INNER),
        (-GAL_OUT, CZ + ARCH_INNER, GAL_OUT, CZ + GAL_OUT),
        (-GAL_OUT, CZ - ARCH_INNER, -ARCH_INNER, CZ + ARCH_INNER),
        (ARCH_INNER, CZ - ARCH_INNER, GAL_OUT, CZ + ARCH_INNER),
    ):
        structure.add_box(x0, 294, z0, x1, Y_CORNICE, z1, "stone_ashlar")
    structure.add_box(-GAL_OUT - 4, Y_CORNICE - 4, CZ - GAL_OUT - 4, GAL_OUT + 4, Y_CORNICE, CZ - GAL_OUT + 4, "bronze_gilded")
    structure.add_box(-GAL_OUT - 4, Y_CORNICE - 4, CZ + GAL_OUT - 4, GAL_OUT + 4, Y_CORNICE, CZ + GAL_OUT + 4, "bronze_gilded")
    structure.add_box(-GAL_OUT - 4, Y_CORNICE - 4, CZ - GAL_OUT, -GAL_OUT + 4, Y_CORNICE, CZ + GAL_OUT, "bronze_gilded")
    structure.add_box(GAL_OUT - 4, Y_CORNICE - 4, CZ - GAL_OUT, GAL_OUT + 4, Y_CORNICE, CZ + GAL_OUT, "bronze_gilded")

    fb_gal = MeshBucket("floor_cornice_gallery", mat_idx, MAT_SIZE, regions)
    for (x0, z0, x1, z1) in (
        (-GAL_OUT + 4, CZ - GAL_OUT + 4, GAL_OUT - 4, CZ - 196),
        (-GAL_OUT + 4, CZ + 196, GAL_OUT - 4, CZ + GAL_OUT - 4),
        (-GAL_OUT + 4, CZ - 196, -196, CZ + 196),
        (196, CZ - 196, GAL_OUT - 4, CZ + 196),
        (-116, CZ + 100, 116, CZ + 196),
        (-116, CZ - 196, 116, CZ - 100),
        (-196, CZ - 116, -100, CZ + 116),
        (100, CZ - 116, 196, CZ + 116),
    ):
        fb_gal.add_panel_y(x0, z0, x1, z1, Y_CORNICE + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_cornice_gallery"] = fb_gal

    R_GAL = GAL_OUT - 4
    structure.add_box(-R_GAL, Y_CORNICE + 12, CZ + R_GAL - 3, R_GAL, Y_CORNICE + 14.5, CZ + R_GAL, "bronze_gilded")
    structure.add_box(-R_GAL, Y_CORNICE + 12, CZ - R_GAL, R_GAL, Y_CORNICE + 14.5, CZ - R_GAL + 3, "bronze_gilded")
    structure.add_box(-R_GAL, Y_CORNICE + 12, CZ - R_GAL, -R_GAL + 3, Y_CORNICE + 14.5, CZ + R_GAL, "bronze_gilded")
    structure.add_box(R_GAL - 3, Y_CORNICE + 12, CZ - R_GAL, R_GAL, Y_CORNICE + 14.5, CZ + R_GAL, "bronze_gilded")
    for p_off in range(-210, 211, 30):
        for s_z in (CZ + R_GAL - 1.5, CZ - R_GAL + 1.5):
            structure.add_box(p_off - 1.2, Y_CORNICE, s_z - 1.2, p_off + 1.2, Y_CORNICE + 12, s_z + 1.2, "detail_atlas", region_name="dark_iron")
        for s_x in (-R_GAL + 1.5, R_GAL - 1.5):
            structure.add_box(s_x - 1.2, Y_CORNICE, CZ + p_off - 1.2, s_x + 1.2, Y_CORNICE + 12, CZ + p_off + 1.2, "detail_atlas", region_name="dark_iron")
    coll.add_box(-R_GAL, Y_CORNICE, CZ + R_GAL - 4, R_GAL, Y_CORNICE + 15, CZ + R_GAL, "cornice_railing_s", "railing")
    coll.add_box(-R_GAL, Y_CORNICE, CZ - R_GAL, R_GAL, Y_CORNICE + 15, CZ - R_GAL + 4, "cornice_railing_n", "railing")
    coll.add_box(-R_GAL, Y_CORNICE, CZ - R_GAL, -R_GAL + 4, Y_CORNICE + 15, CZ + R_GAL, "cornice_railing_w", "railing")
    coll.add_box(R_GAL - 4, Y_CORNICE, CZ - R_GAL, R_GAL, Y_CORNICE + 15, CZ + R_GAL, "cornice_railing_e", "railing")

    # 1926: Festive Red/White/Blue Bunting Swags draped along the 24 m cornice edge (above the frieze!)
    if state_id == "1926":
        for bi in range(-3, 4):
            bx0, bx1 = bi * 56.0 - 28.0, bi * 56.0 + 28.0
            structure.add_panel_z(bx0, 294, bx1, 312, CZ + GAL_OUT + 4.5, "+z", "inscriptions", "bunting_swag", fit_region=True)
            structure.add_panel_z(bx0, 294, bx1, 312, CZ - GAL_OUT - 4.5, "-z", "inscriptions", "bunting_swag", fit_region=True)
            structure.add_panel_x(CZ + bx0, 294, CZ + bx1, 312, GAL_OUT + 4.5, "+x", "inscriptions", "bunting_swag", fit_region=True)
            structure.add_panel_x(CZ + bx0, 294, CZ + bx1, 312, -GAL_OUT - 4.5, "-x", "inscriptions", "bunting_swag", fit_region=True)

    # ==========================================================================
    # 4. PART 2 — THE SHAFT: FOUR TALL STEPPED CORNER PIERS (24-58 m)
    #    1 m Carved Art Deco Chevron Friezes at BOTH Setbacks (34 m & 46 m),
    #    Smooth Ashlar Setback Caps, ONE Tall Amber Slot per Outer Face,
    #    FOUR 8 m COLOSSAL ART DECO GUARDIAN STATUES on Projecting Corner Ledges (44-52 m),
    #    and HOLLOW NW PIER w/ Open-Cage Elevator (`lift_car`) & Winding Staircase!
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

            tiers = [(Y_CORNICE, Y_SET1, 196.0), (Y_SET1, Y_SET2, 186.0), (Y_SET2, Y_DECK, 176.0)]
            for ti, (y_lo, y_hi, out_r) in enumerate(tiers):
                x0, x1, z0, z1 = pier_rect(out_r)
                if not is_nw:
                    structure.add_box(x0, y_lo, z0, x1, y_hi, z1, "stone_limestone")
                else:
                    wt = 11.0
                    if ti == 0:
                        structure.add_box(x0, y_lo, z1 - wt, -168, y_hi, z1, "stone_limestone")
                        structure.add_box(-142, y_lo, z1 - wt, x1, y_hi, z1, "stone_limestone")
                        structure.add_box(-168, y_lo + 36, z1 - wt, -142, y_hi, z1, "stone_limestone")
                    else:
                        structure.add_box(x0, y_lo, z1 - wt, x1, y_hi, z1, "stone_limestone")
                    structure.add_box(x0, y_lo, z0, x1, y_hi, z0 + wt, "stone_limestone")
                    structure.add_box(x0, y_lo, z0 + wt, x0 + wt, y_hi, z1 - wt, "stone_limestone")
                    structure.add_box(x1 - wt, y_lo, z0 + wt, x1, y_hi, z1 - wt, "stone_limestone")

                # Deep vertical Art Deco fluting ribs
                mx, mz = (x0 + x1) * 0.5, (z0 + z1) * 0.5
                for f_off in (-24, -13, 13, 24):
                    zf0 = z1 if sz > 0 else z0 - 4.0
                    structure.add_box(mx + f_off - 2.0, y_lo, zf0, mx + f_off + 2.0, y_hi, zf0 + 4.0, "stone_limestone")
                    xf0 = x1 if sx > 0 else x0 - 4.0
                    structure.add_box(xf0, y_lo, mz + f_off - 2.0, xf0 + 4.0, y_hi, mz + f_off + 2.0, "stone_limestone")

                # 1.0 m (13 art px) CARVED ART DECO CHEVRON FRIEZE + SMOOTH ASHLAR SETBACK CAP at 34 m & 46 m!
                if ti < 2:
                    structure.add_box(x0 - 3, y_hi - 3, z0 - 3, x1 + 3, y_hi + 2, z1 + 3, "stone_ashlar")
                    # Wrap 13 art px tall carved chevron band around all 4 pier faces cleanly in <= 60 px panels!
                    cy0, cy1 = y_hi - 16.0, y_hi - 3.0
                    structure.add_tiled_panel_z(x0, cy0, x1, cy1, z1 + 0.5, "+z", "inscriptions", "chevron_band", 60.0, 14.0)
                    structure.add_tiled_panel_z(x0, cy0, x1, cy1, z0 - 0.5, "-z", "inscriptions", "chevron_band", 60.0, 14.0)
                    structure.add_tiled_panel_x(z0, cy0, z1, cy1, x1 + 0.5, "+x", "inscriptions", "chevron_band", 60.0, 14.0)
                    structure.add_tiled_panel_x(z0, cy0, z1, cy1, x0 - 0.5, "-x", "inscriptions", "chevron_band", 60.0, 14.0)

            # ONE Tall Narrow Amber Window Slot per outer pier face (y = 336..540)
            x0_m, x1_m, z0_m, z1_m = pier_rect(186.0)
            mx_m, mz_m = (x0_m + x1_m) * 0.5, (z0_m + z1_m) * 0.5
            z_face, z_dir = (z1_m if sz > 0 else z0_m), (1.0 if sz > 0 else -1.0)
            structure.add_box(mx_m - 5.2, 444, min(z_face, z_face + z_dir * 3.8), mx_m - 2.8, 548, max(z_face, z_face + z_dir * 3.8), "stone_ashlar")
            structure.add_box(mx_m + 2.8, 444, min(z_face, z_face + z_dir * 3.8), mx_m + 5.2, 548, max(z_face, z_face + z_dir * 3.8), "stone_ashlar")
            structure.add_panel_z(mx_m - 2.8, 446, mx_m + 2.8, 546, z_face + z_dir * 0.5, "+z" if sz > 0 else "-z", "glass_cathedral", "win_cathedral")
            structure.add_panel_z(mx_m - 0.85, 468, mx_m + 0.85, 496, z_face + z_dir * 0.9, "+z" if sz > 0 else "-z", "glow_monument", "pier_slot_glow", fit_region=True)
            x_face, x_dir = (x1_m if sx > 0 else x0_m), (1.0 if sx > 0 else -1.0)
            structure.add_box(min(x_face, x_face + x_dir * 3.8), 444, mz_m - 5.2, max(x_face, x_face + x_dir * 3.8), 548, mz_m - 2.8, "stone_ashlar")
            structure.add_box(min(x_face, x_face + x_dir * 3.8), 444, mz_m + 2.8, max(x_face, x_face + x_dir * 3.8), 548, mz_m + 5.2, "stone_ashlar")
            structure.add_panel_x(mz_m - 2.8, 446, mz_m + 2.8, 546, x_face + x_dir * 0.5, "+x" if sx > 0 else "-x", "glass_cathedral", "win_cathedral")
            structure.add_panel_x(mz_m - 0.85, 468, mz_m + 0.85, 496, x_face + x_dir * 0.9, "+x" if sx > 0 else "-x", "glow_monument", "pier_slot_glow", fit_region=True)

            # COLOSSAL 8.2 m ART DECO GUARDIAN STATUE standing proud on a cantilevered dark-granite corner ledge
            # (44.5-53.3 m), facing outward diagonally at 45 degrees so it silhouettes dramatically against open sky!
            gx = sx * 210.0
            gz = CZ + sz * 210.0
            yaw = math.atan2(sx, sz)  # SW=-45°, SE=+45°, NW=-135°, NE=+135°
            build_guardian_statue(structure, coll, gx, Y_LEDGE, gz, yaw, g_kind, state_id)

            # Full-height pier collision in glTF Y-up metres (y = 24.06..58.04 m)
            coll.add_box(116.0 if sx > 0 else -196.0, Y_CORNICE, CZ + (116.0 if sz > 0 else -196.0),
                         196.0 if sx > 0 else -116.0, Y_DECK, CZ + (196.0 if sz > 0 else -116.0),
                         f"cathedral_pier_{ptag}", "exterior")

    # ==========================================================================
    # 5. HOLLOW NORTH-WEST PIER: OPEN-CAGE ELEVATOR (`lift_car`) & WINDING STAIRS
    # ==========================================================================
    NW_CX, NW_CZ = -152.0, CZ - 152.0
    for ox in (-11.0, 11.0):
        for oz in (-11.0, 11.0):
            structure.add_box(NW_CX + ox - 1.2, Y_T3, NW_CZ + oz - 1.2, NW_CX + ox + 1.2, Y_DECK + 16, NW_CZ + oz + 1.2, "steel_truss")
    for y_ring in range(110, int(Y_DECK) + 1, 36):
        structure.add_box(NW_CX - 11.5, y_ring, NW_CZ - 11.5, NW_CX + 11.5, y_ring + 1.8, NW_CZ - 10.0, "steel_truss")
        structure.add_box(NW_CX - 11.5, y_ring, NW_CZ + 10.0, NW_CX + 11.5, y_ring + 1.8, NW_CZ + 11.5, "steel_truss")
        structure.add_box(NW_CX - 11.5, y_ring, NW_CZ - 10.0, NW_CX - 10.0, y_ring + 1.8, NW_CZ + 10.0, "steel_truss")
        structure.add_box(NW_CX + 10.0, y_ring, NW_CZ - 10.0, NW_CX + 11.5, y_ring + 1.8, NW_CZ + 10.0, "steel_truss")
    structure.add_box(NW_CX - 1.0, Y_T3, NW_CZ - 1.0, NW_CX + 1.0, Y_DECK + 14, NW_CZ + 1.0, "steel_truss")
    coll.add_box(NW_CX - 12, Y_T3, NW_CZ - 12, NW_CX + 12, Y_DECK, NW_CZ + 12, "nw_elevator_cage", "interior")

    lift_car.add_box(-9.0, 0.0, -9.0, 9.0, 2.2, 9.0, "bronze_gilded")
    lift_car.add_box(-9.0, 28.0, -9.0, 9.0, 31.0, 9.0, "bronze_gilded")
    for ox in (-8.5, 8.5):
        for oz in (-8.5, 8.5):
            lift_car.add_box(ox - 1.0, 2.2, oz - 1.0, ox + 1.0, 28.0, oz + 1.0, "bronze_gilded")
    lift_car.add_panel_z(-7.5, 3.0, 7.5, 27.5, 8.2, "+z", "glass_cathedral", "win_lantern")
    lift_car.add_panel_z(-7.5, 3.0, 7.5, 27.5, -8.2, "-z", "glass_cathedral", "win_lantern")
    lift_car.add_panel_x(-7.5, 3.0, 7.5, 27.5, 8.2, "+x", "glass_cathedral", "win_lantern")
    lift_car.add_panel_x(-7.5, 3.0, 7.5, 27.5, -8.2, "-x", "glass_cathedral", "win_lantern")

    fb_landings = MeshBucket("floor_stair_landings", mat_idx, MAT_SIZE, regions)
    flight_edges = [
        (NW_CX - 22, NW_CZ + 12, NW_CX + 22, NW_CZ + 22),
        (NW_CX + 12, NW_CZ - 22, NW_CX + 22, NW_CZ + 12),
        (NW_CX - 22, NW_CZ - 22, NW_CX + 12, NW_CZ - 12),
        (NW_CX - 22, NW_CZ - 12, NW_CX - 12, NW_CZ + 12),
    ]
    n_flights = 16
    dy_fl = (Y_DECK - Y_T3) / n_flights
    for fi in range(n_flights):
        y_f0 = Y_T3 + fi * dy_fl
        side_i = fi % 4
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
    fb_landings.add_panel_y(NW_CX - 22, NW_CZ + 12, NW_CX + 22, CZ - 116, Y_CORNICE + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_stair_landings"] = fb_landings
    coll.add_box(NW_CX - 23, Y_T3, NW_CZ - 23, NW_CX + 23, Y_DECK, NW_CZ - 12, "nw_stair_north", "interior")
    coll.add_box(NW_CX - 23, Y_T3, NW_CZ + 12, NW_CX + 23, Y_DECK, NW_CZ + 23, "nw_stair_south", "interior")

    # 1976 WORN STATE: SE Pier Restoration Scaffolding + Draped Tarps + Chain-Link Fence at Ground Corner!
    if state_id == "1976":
        sx0, sx1 = 110.0, 212.0
        sz0, sz1 = CZ + 110.0, CZ + 212.0
        for px in (sx0, sx0 + 34, sx0 + 68, sx1):
            structure.add_beam_art((px, Y_CORNICE, sz1), (px, 700, sz1), 1.3, 1.3, "steel_truss")
        for pz in (sz0, sz0 + 34, sz0 + 68, sz1):
            structure.add_beam_art((sx1, Y_CORNICE, pz), (sx1, 700, pz), 1.3, 1.3, "steel_truss")
        for yl in range(int(Y_CORNICE) + 42, 690, 62):
            sxm, szm = (sx0 + sx1) * 0.5, (sz0 + sz1) * 0.5
            structure.add_box(sx0, yl, sz1 - 12, sxm, yl + 2.2, sz1 + 2, "detail_atlas", region_name="timber_bench")
            structure.add_box(sxm, yl, sz1 - 12, sx1, yl + 2.2, sz1 + 2, "detail_atlas", region_name="timber_bench")
            structure.add_box(sx1 - 12, yl, sz0, sx1 + 2, yl + 2.2, szm, "detail_atlas", region_name="timber_bench")
            structure.add_box(sx1 - 12, yl, szm, sx1 + 2, yl + 2.2, sz1, "detail_atlas", region_name="timber_bench")
            structure.add_beam_art((sx0, yl, sz1), (sx1, yl + 56, sz1), 1.0, 1.0, "steel_truss")
            structure.add_beam_art((sx1, yl, sz0), (sx1, yl + 56, sz1), 1.0, 1.0, "steel_truss")
        # Draped weathered blue-grey canvas tarps hanging across mid-pier scaffolding bays!
        for yt in (384, 448, 512):
            structure.add_panel_z(sx0 + 6, yt, sx0 + 68, yt + 60, sz1 + 2.2, "+z", "inscriptions", "scaffold_tarp", fit_region=True)
            structure.add_panel_x(sz0 + 6, yt, sz0 + 68, yt + 60, sx1 + 2.2, "+x", "inscriptions", "scaffold_tarp", fit_region=True)
        coll.add_box(sx0, Y_CORNICE, sz0, sx1 + 4, 704, sz1 + 4, "scaffolding_se_pier_1976", "exterior")

        # Ground-level Steel Chain-Link Fence enclosing the scaffolded SE corner of the monument!
        fx0, fx1, fz0, fz1 = 236.0, 396.0, CZ + 220.0, CZ + 372.0
        for px in range(int(fx0), int(fx1) + 1, 40):
            lot_ground.add_box(px - 1.2, Y_PLAZA, fz1 - 1.2, px + 1.2, Y_PLAZA + 32, fz1 + 1.2, "steel_truss")
        for pz in range(int(fz0), int(fz1) + 1, 38):
            lot_ground.add_box(fx1 - 1.2, Y_PLAZA, pz - 1.2, fx1 + 1.2, Y_PLAZA + 32, pz + 1.2, "steel_truss")
        lot_ground.add_tiled_panel_z(fx0, Y_PLAZA, fx1, Y_PLAZA + 31, fz1, "+z", "inscriptions", "chain_fence", 46.0, 32.0)
        lot_ground.add_tiled_panel_z(fx0, Y_PLAZA, fx1, Y_PLAZA + 31, fz1 - 0.5, "-z", "inscriptions", "chain_fence", 46.0, 32.0)
        lot_ground.add_tiled_panel_x(fz0, Y_PLAZA, fz1, Y_PLAZA + 31, fx1, "+x", "inscriptions", "chain_fence", 46.0, 32.0)
        lot_ground.add_tiled_panel_x(fz0, Y_PLAZA, fz1, Y_PLAZA + 31, fx1 - 0.5, "-x", "inscriptions", "chain_fence", 46.0, 32.0)
        coll.add_box(fx0, 0, fz1 - 3, fx1, Y_PLAZA + 32, fz1 + 3, "chain_fence_se_south_1976", "lot")
        coll.add_box(fx1 - 3, 0, fz0, fx1 + 3, Y_PLAZA + 32, fz1, "chain_fence_se_east_1976", "lot")

    # ==========================================================================
    # 6. FOUR CLEAN TOP VAULT ARCHES (ZERO TRUSS CLUTTER IN FRONT OF SPHERE!)
    #    & THE 18.0 m 3-NODE CELESTIAL ARMILLARY SPHERE (5.0 m GLOBE + 12 ZODIAC PLAQUES!)
    # ==========================================================================
    # ONE riveted steel arch per vault face high at the vault's top (y = 47..57 m, ABOVE the 18 m sphere!)
    Y_TRUSS_SPRING = 608.0
    for sz_s in (1, -1):
        az = CZ + sz_s * 146.0
        structure.add_clean_vault_arch(0.0, Y_TRUSS_SPRING, az, "z", 112.0, 128.0, 10.0, 12)
    for sx_s in (1, -1):
        ax = sx_s * 146.0
        structure.add_clean_vault_arch(ax, Y_TRUSS_SPRING, CZ, "x", 112.0, 128.0, 10.0, 12)

    # Overhead bronze suspension bearing ring & hub under the 58 m deck oculus (NO diagonal crossing ribs!)
    structure.add_ring_3d((0, 722, CZ), (1, 0, 0), (0, 0, -1), 70.0, 6.0, 3.2, 16, "bronze_gilded")
    structure.add_cylinder(0, 706, 724, CZ, 11.0, 15.0, 10, "bronze_gilded", cap_top=True, cap_bot=True)

    # THE STAR: 18.0 m (r = 116 art px!) 3-NODE CELESTIAL ARMILLARY SPHERE suspended in wide open sky!
    Y_ARM = 528.0  # 40.97 m center!
    tilt = math.radians(23.4)
    axis_dir = (-math.sin(tilt), math.cos(tilt), 0.0)

    # 1) `armillary_outer` (FIXED): 18.0 m diameter solid gilded bands (0.62 m wide x 0.31 m deep!) + vertical hanger rods!
    arm_outer.add_ring_3d((0, 0, 0), (0, 0, 1), (0, 1, 0), 116.0, 4.0, 2.0, 28, "bronze_gilded")
    arm_outer.add_ring_3d((0, 0, 0), (1, 0, 0), (0, 1, 0), 110.0, 4.0, 2.0, 28, "bronze_gilded")
    arm_outer.add_ring_3d((0, 0, 0), (1, 0, 0), (0, 0, -1), 113.0, 4.2, 2.0, 28, "bronze_gilded")
    # 12 crisp gilded hour-point finials around equatorial outer ring
    for hi in range(12):
        ang = 2.0 * math.pi * hi / 12.0
        arm_outer.add_beam_art((math.cos(ang) * 116, 0, math.sin(ang) * 116),
                               (math.cos(ang) * 126, 0, math.sin(ang) * 126), 1.8, 1.8, "bronze_gilded", caps=True)
    # Central bronze hanger rod + 4 slender vertical hanger rods straight up to the 58 m deck above (nothing crosses the view!)
    arm_outer.add_cylinder(0, 114.0, 712.0 - Y_ARM, 0, 3.8, 3.8, 8, "bronze_gilded")
    for hx, hz in ((0, 56), (0, -56), (56, 0), (-56, 0)):
        arm_outer.add_cylinder(hx, 99.0, 724.0 - Y_ARM, hz, 1.4, 1.4, 6, "bronze_gilded")

    # 2) `armillary_ecliptic` (TURNS SLOWLY ONCE A GAME DAY):
    #    Wide gilded zodiac band + 12 RAISED 3D ZODIAC PLAQUES + razor-thin glowing star line!
    u_ec = (math.cos(tilt), math.sin(tilt), 0.0)
    v_ec = (0.0, 0.0, -1.0)
    ec_n = v_norm(v_cross(u_ec, v_ec))
    arm_ecliptic.add_ring_3d((0, 0, 0), u_ec, v_ec, 96.0, 4.2, 6.8, 24, "bronze_gilded",
                             outer_mat="inscriptions", outer_region="zodiac_band")
    # 12 raised 3D zodiac relief plaques stepping out radially along the tilted ecliptic band!
    for zi in range(12):
        za = 2.0 * math.pi * zi / 12.0
        r_dir = v_add(v_scale(u_ec, math.cos(za)), v_scale(v_ec, math.sin(za)))
        p_in = v_scale(r_dir, 99.5)
        p_out = v_scale(r_dir, 103.5)
        arm_ecliptic.add_beam_art(p_in, p_out, 5.2, 5.2, "bronze_gilded", caps=True)
    # Razor-thin glowing star line along outer ecliptic rim (~0.95 m2 total!)
    r_gl = 101.0
    segs_gl = 20
    for i in range(segs_gl):
        a0, a1 = 2.0 * math.pi * i / segs_gl, 2.0 * math.pi * ((i + 1) % segs_gl) / segs_gl
        p0 = v_add(v_scale(u_ec, r_gl * math.cos(a0)), v_scale(v_ec, r_gl * math.sin(a0)))
        p1 = v_add(v_scale(u_ec, r_gl * math.cos(a1)), v_scale(v_ec, r_gl * math.sin(a1)))
        arm_ecliptic.add_planar_quad_art(
            v_add(p0, v_scale(ec_n, -0.32)), v_add(p1, v_scale(ec_n, -0.32)),
            v_add(p1, v_scale(ec_n, 0.32)), v_add(p0, v_scale(ec_n, 0.32)),
            "glow_monument", "ecliptic_glow", fit_region=True, edge_aligned=True
        )

    # 3) `armillary_globe` (TURNS ONCE A DAY ON 23.4° POLAR AXIS):
    #    5.0 m diameter (r = 32.2 art px) Verdigris-ocean & Pale-bronze-continent Earth Globe + 0.4 m Polar Axis Rod w/ Bearings!
    arm_globe.add_sphere_faceted((0, 0, 0), 32.2, 8, 16, "inscriptions", "earth_globe")
    arm_globe.add_ring_3d((0, 0, 0), u_ec, v_ec, 35.0, 1.8, 1.5, 16, "bronze_gilded")
    # 0.4 m (5.2 art px wide -> half_w = 2.6) Polar Axis Rod with bronze end bearings!
    arm_globe.add_beam_art(v_scale(axis_dir, -124.0), v_scale(axis_dir, 126.0), 2.6, 2.6, "bronze_gilded", caps=True)
    arm_globe.add_sphere_faceted(v_scale(axis_dir, -118.0), 6.2, 4, 8, "bronze_gilded")
    arm_globe.add_sphere_faceted(v_scale(axis_dir, 118.0), 6.2, 4, 8, "bronze_gilded")
    arm_globe.add_beam_art(v_scale(axis_dir, 122.0), v_scale(axis_dir, 144.0), 4.6, 1.5, "bronze_gilded", caps=True)

    # ==========================================================================
    # 7. PART 3 — THE ART DECO CROWN (58-106 m, ~1/3 TOTAL HEIGHT!):
    #    Square 58 m Deck -> 3-Tier Stepped Octagonal Lantern (13 m, 10.5 m, 8 m across, 18 m tall)
    #    + 8 Stepped Flying Buttress Fins + 16-Rib Integrated Gilded Sunburst Drum
    #    + Ribbed Tapered Copper Spire (4 gilded corner ribs) + 8-Strut Open Beacon Cage & Finial!
    # ==========================================================================
    DECK_OUT = 190.0
    for (x0, z0, x1, z1) in (
        (-DECK_OUT, CZ - DECK_OUT, DECK_OUT, CZ - 68),
        (-DECK_OUT, CZ + 68, DECK_OUT, CZ + DECK_OUT),
        (-DECK_OUT, CZ - 68, -68, CZ + 68),
        (68, CZ - 68, DECK_OUT, CZ + 68),
    ):
        structure.add_box(x0, 724, z0, x1, 744, z1, "stone_ashlar")
    structure.add_box(-DECK_OUT - 4, 744, CZ - DECK_OUT - 4, DECK_OUT + 4, Y_DECK, CZ - DECK_OUT + 4, "bronze_gilded")
    structure.add_box(-DECK_OUT - 4, 744, CZ + DECK_OUT - 4, DECK_OUT + 4, Y_DECK, CZ + DECK_OUT + 4, "bronze_gilded")
    structure.add_box(-DECK_OUT - 4, 744, CZ - DECK_OUT, -DECK_OUT + 4, Y_DECK, CZ + DECK_OUT, "bronze_gilded")
    structure.add_box(DECK_OUT - 4, 744, CZ - DECK_OUT, DECK_OUT + 4, Y_DECK, CZ + DECK_OUT, "bronze_gilded")

    fb_deck = MeshBucket("floor_observation_deck", mat_idx, MAT_SIZE, regions)
    for (x0, z0, x1, z1) in (
        (-DECK_OUT + 4, CZ - DECK_OUT + 4, DECK_OUT - 4, CZ - 68),
        (-DECK_OUT + 4, CZ + 68, DECK_OUT - 4, CZ + DECK_OUT - 4),
        (-DECK_OUT + 4, CZ - 68, -68, CZ + 68),
        (68, CZ - 68, DECK_OUT - 4, CZ + 68),
    ):
        fb_deck.add_panel_y(x0, z0, x1, z1, Y_DECK + 0.15, "+y", "marble_plaza")
    floor_buckets["floor_observation_deck"] = fb_deck

    DR = DECK_OUT - 4
    for s_z in (CZ + DR, CZ - DR):
        structure.add_box(-DR, Y_DECK, s_z - 2.5, DR, Y_DECK + 3.5, s_z + 2.5, "stone_ashlar")
        structure.add_box(-DR, Y_DECK + 13.5, s_z - 2.0, DR, Y_DECK + 16.0, s_z + 2.0, "bronze_gilded")
    for s_x in (-DR, DR):
        structure.add_box(s_x - 2.5, Y_DECK, CZ - DR, s_x + 2.5, Y_DECK + 3.5, CZ + DR, "stone_ashlar")
        structure.add_box(s_x - 2.0, Y_DECK + 13.5, CZ - DR, s_x + 2.0, Y_DECK + 16.0, CZ + DR, "bronze_gilded")
    for p_off in range(-168, 169, 28):
        for s_z in (CZ + DR, CZ - DR):
            structure.add_box(p_off - 1.3, Y_DECK + 3.5, s_z - 1.3, p_off + 1.3, Y_DECK + 13.5, s_z + 1.3, "detail_atlas", region_name="dark_iron")
        for s_x in (-DR, DR):
            structure.add_box(s_x - 1.3, Y_DECK + 3.5, CZ + p_off - 1.3, s_x + 1.3, Y_DECK + 13.5, CZ + p_off + 1.3, "detail_atlas", region_name="dark_iron")
    structure.add_ring_3d((0, Y_DECK + 14.5, CZ), (1, 0, 0), (0, 0, -1), 70.0, 2.5, 2.5, 16, "bronze_gilded")
    coll.add_box(-DR, Y_DECK, CZ + DR - 3, DR, Y_DECK + 16, CZ + DR + 3, "deck_railing_s", "railing")
    coll.add_box(-DR, Y_DECK, CZ - DR - 3, DR, Y_DECK + 16, CZ - DR + 3, "deck_railing_n", "railing")
    coll.add_box(-DR - 3, Y_DECK, CZ - DR, -DR + 3, Y_DECK + 16, CZ + DR, "deck_railing_w", "railing")
    coll.add_box(DR - 3, Y_DECK, CZ - DR, DR + 3, Y_DECK + 16, CZ + DR, "deck_railing_e", "railing")

    # Four 58 m Sky Deck Engraved Brass Direction Plates
    for (cx_p, cz_p, reg_dir, face_south) in (
        (-DR + 36, CZ + DR - 6, "dir_capitol", True),
        (DR - 36, CZ + DR - 6, "dir_river", True),
        (-DR + 36, CZ - DR + 6, "dir_mountains", False),
        (DR - 36, CZ - DR + 6, "dir_sea", False),
    ):
        structure.add_box(cx_p - 29, Y_DECK + 15.0, cz_p - 7.5, cx_p + 29, Y_DECK + 17.5, cz_p + 7.5, "bronze_gilded")
        y_pl = Y_DECK + 17.7
        if face_south:
            structure.add_planar_quad_art((cx_p + 28, y_pl, cz_p - 7.0), (cx_p - 28, y_pl, cz_p - 7.0),
                                          (cx_p - 28, y_pl, cz_p + 7.0), (cx_p + 28, y_pl, cz_p + 7.0),
                                          "inscriptions", reg_dir, fit_region=True, edge_aligned=True)
        else:
            structure.add_planar_quad_art((cx_p - 28, y_pl, cz_p + 7.0), (cx_p + 28, y_pl, cz_p + 7.0),
                                          (cx_p + 28, y_pl, cz_p - 7.0), (cx_p - 28, y_pl, cz_p - 7.0),
                                          "inscriptions", reg_dir, fit_region=True, edge_aligned=True)

    # THREE-TIER STEPPED OCTAGONAL ART DECO LANTERN (58-76 m, 18 m tall: 13 m -> 10.5 m -> 8 m!)
    R_L1, R_L2, R_L3 = 84.0, 68.0, 52.0  # Diameters: 13.04 m, 10.55 m, 8.07 m!

    # Bronze access ladder climbing from 58 m deck to Lantern Machinery Chamber (y = 748..774)
    for lx in (-4, 4):
        structure.add_box(-64 + lx, Y_DECK, CZ + 24, -62 + lx, 774, CZ + 26, "bronze_gilded")
    for ly in range(int(Y_DECK) + 4, 772, 6):
        structure.add_box(-64, ly, CZ + 24, -58, ly + 1.4, CZ + 26, "detail_atlas", region_name="dark_iron")

    fb_lan = MeshBucket("floor_lantern_chamber", mat_idx, MAT_SIZE, regions)
    fb_lan.add_cylinder(0, 770, 774, CZ, R_L1 - 4, R_L1 - 4, 8, "stone_ashlar", cap_top=True, cap_bot=True, phase=math.pi / 8.0)
    floor_buckets["floor_lantern_chamber"] = fb_lan

    # TIER 1 (y = 748..864, 13.0 m across): 8 flat vertical piers w/ chevron caps + 8 tall narrow amber lancets + 8 STEPPED FLYING BUTTRESS FINS!
    for i in range(8):
        a_cor = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        ca, sa = math.cos(a_cor), math.sin(a_cor)
        # Flat vertical octagonal corner pier rising cleanly from 58 m deck to Tier 1 cornice
        structure.add_beam_art((ca * 64, (Y_DECK + Y_L1_TOP - 8) * 0.5, CZ + sa * 64),
                               (ca * R_L1, (Y_DECK + Y_L1_TOP - 8) * 0.5, CZ + sa * R_L1),
                               9.2, (Y_L1_TOP - 8 - Y_DECK) * 0.5, "stone_limestone", caps=True)
        # Art Deco Chevron Cap atop each Tier 1 pier!
        structure.add_beam_art((ca * 66, Y_L1_TOP - 13, CZ + sa * 66),
                               (ca * (R_L1 + 4), Y_L1_TOP - 13, CZ + sa * (R_L1 + 4)),
                               10.0, 5.0, "stone_ashlar", caps=True)
        # STEPPED FLYING BUTTRESS FIN sweeping cleanly down from Tier 1 corner to the 58 m deck perimeter post!
        for r_in, r_out, ys, ye in ((78, 114, Y_DECK, 846), (112, 146, Y_DECK, 810), (144, 178, Y_DECK, 776)):
            structure.add_beam_art((ca * r_in, (ys + ye) * 0.5, CZ + sa * r_in),
                                   (ca * r_out, (ys + ye) * 0.5, CZ + sa * r_out),
                                   5.2, (ye - ys) * 0.5, "stone_limestone", caps=True)
            # Bleached ashlar step cap atop each flying buttress step!
            structure.add_beam_art((ca * (r_in - 1), ye + 1.5, CZ + sa * (r_in - 1)),
                                   (ca * (r_out + 1.5), ye + 1.5, CZ + sa * (r_out + 1.5)),
                                   6.0, 1.8, "stone_ashlar", caps=True)

        # Tall narrow amber lancet window on each of the 8 flat faces of Tier 1!
        a_face = 2.0 * math.pi * i / 8.0
        fc, fs = math.cos(a_face), math.sin(a_face)
        tx, tz = -fs, fc
        r_win = 76.0 * math.cos(math.pi / 8.0)
        hw_w = 17.5
        structure.add_planar_quad_art(
            (fc * r_win + tx * hw_w, 776, CZ + fs * r_win + tz * hw_w),
            (fc * r_win - tx * hw_w, 776, CZ + fs * r_win - tz * hw_w),
            (fc * r_win - tx * hw_w, 852, CZ + fs * r_win - tz * hw_w),
            (fc * r_win + tx * hw_w, 852, CZ + fs * r_win + tz * hw_w),
            "glass_cathedral", "win_lantern"
        )
        # Glowing amber lantern lancet core strip (~3.6 m2 across all 8 faces, well within < 20 m2 cap!)
        r_gw = r_win + 0.9
        gw_hw = 1.6
        structure.add_planar_quad_art(
            (fc * r_gw + tx * gw_hw, 792, CZ + fs * r_gw + tz * gw_hw),
            (fc * r_gw - tx * gw_hw, 792, CZ + fs * r_gw - tz * gw_hw),
            (fc * r_gw - tx * gw_hw, 838, CZ + fs * r_gw - tz * gw_hw),
            (fc * r_gw + tx * gw_hw, 838, CZ + fs * r_gw + tz * gw_hw),
            "glow_monument", "lantern_lancet_glow", fit_region=True
        )
    # Strong projecting smooth-ashlar Tier 1 Cornice
    structure.add_cylinder(0, Y_L1_TOP - 8, Y_L1_TOP, CZ, R_L1 + 7.5, R_L1 + 7.5, 8, "stone_ashlar", cap_top=True, cap_bot=True, phase=math.pi / 8.0)

    # Interior Beacon Machinery Chamber inside Tier 1 (gears & brass Fresnel lens visible through amber glass!)
    structure.add_cylinder(0, 774, 980, CZ, 5.2, 5.2, 8, "steel_truss")
    structure.add_ring_3d((0, 796, CZ), (1, 0, 0), (0, 0, -1), 28.0, 6.0, 4.0, 12, "bronze_gilded")
    structure.add_ring_3d((32, 810, CZ), (0, 1, 0), (0, 0, 1), 15.0, 3.8, 3.0, 10, "bronze_gilded")
    structure.add_cylinder(0, 816, 852, CZ, 24.0, 24.0, 8, "glass_cathedral", "win_lantern")
    for fy in (816, 834, 852):
        structure.add_ring_3d((0, fy, CZ), (1, 0, 0), (0, 0, -1), 25.5, 2.2, 1.5, 8, "bronze_gilded")

    # TIER 2 (y = 864..935, 10.5 m across) + Strong Ashlar Cornice & Vertical Art Deco Flutes!
    structure.add_cylinder(0, Y_L1_TOP, Y_L2_TOP - 7, CZ, R_L2, R_L2, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    for i in range(8):
        ac = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        structure.add_beam_art((math.cos(ac) * (R_L2 - 2), (Y_L1_TOP + Y_L2_TOP - 7) * 0.5, CZ + math.sin(ac) * (R_L2 - 2)),
                               (math.cos(ac) * (R_L2 + 4.5), (Y_L1_TOP + Y_L2_TOP - 7) * 0.5, CZ + math.sin(ac) * (R_L2 + 4.5)),
                               4.5, (Y_L2_TOP - 7 - Y_L1_TOP) * 0.5, "stone_ashlar", caps=True)
    structure.add_cylinder(0, Y_L2_TOP - 7, Y_L2_TOP, CZ, R_L2 + 6.5, R_L2 + 6.5, 8, "stone_ashlar", cap_top=True, cap_bot=True, phase=math.pi / 8.0)

    # TIER 3 (y = 935..980, 8.0 m across) + Strong Ashlar Cornice!
    structure.add_cylinder(0, Y_L2_TOP, Y_L3_TOP - 6, CZ, R_L3, R_L3, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    structure.add_cylinder(0, Y_L3_TOP - 6, Y_L3_TOP, CZ, R_L3 + 5.5, R_L3 + 5.5, 8, "stone_ashlar", cap_top=True, cap_bot=True, phase=math.pi / 8.0)
    coll.add_box(-R_L1, Y_DECK, CZ - R_L1, R_L1, Y_L3_TOP, CZ + R_L1, "octagonal_lantern_3tier", "exterior")

    # INTEGRATED GILDED SUNBURST DRUM (y = 980..1018, 16 crisp vertical sunburst ribs worked into the structure!)
    structure.add_cylinder(0, Y_L3_TOP, Y_SUN_TOP, CZ, 45.0, 43.0, 16, "bronze_gilded", cap_top=True)
    for i in range(16):
        ar = 2.0 * math.pi * i / 16.0
        ca, sa = math.cos(ar), math.sin(ar)
        structure.add_beam_art((ca * 45.5, Y_L3_TOP, CZ + sa * 45.5),
                               (ca * 48.0, Y_SUN_TOP + 3.0, CZ + sa * 48.0), 1.8, 1.8, "bronze_gilded", caps=True)

    # RIBBED TAPERED COPPER SPIRE (y = 1018..1304 = 22.2 m, 4 bold gilded corner ribs + era copper patina!)
    structure.add_cylinder(0, Y_SUN_TOP, Y_SPIRE, CZ, 42.0, 10.0, 8, "copper_spire", cap_top=True, phase=math.pi / 8.0)
    for i in range(4):
        ar = math.pi * 0.25 + math.pi * 0.5 * i
        structure.add_beam_art((math.cos(ar) * 43.5, Y_SUN_TOP, CZ + math.sin(ar) * 43.5),
                               (math.cos(ar) * 11.5, Y_SPIRE, CZ + math.sin(ar) * 11.5), 2.5, 2.5, "bronze_gilded")
    for y_col, r_col in ((1105, 34.0), (1205, 22.0)):
        structure.add_cylinder(0, y_col, y_col + 4.5, CZ, r_col, r_col, 8, "bronze_gilded", phase=math.pi / 8.0)

    # OPEN 8-STRUT BRONZE BEACON CAGE (101.2-103.7 m), GLOWING WHITE ORB (OFF IN 1976!) & LIGHTNING FINIAL (106.2 m)!
    structure.add_cylinder(0, Y_SPIRE - 2, Y_SPIRE + 4, CZ, 15.5, 15.5, 8, "bronze_gilded", cap_top=True)
    for i in range(8):
        ang = 2.0 * math.pi * i / 8.0
        structure.add_beam_art((math.cos(ang) * 12.5, Y_SPIRE + 4, CZ + math.sin(ang) * 12.5),
                               (math.cos(ang) * 8.5, 1338.0, CZ + math.sin(ang) * 8.5), 1.2, 1.2, "bronze_gilded")
    structure.add_cylinder(0, 1336, 1342, CZ, 11.0, 4.5, 8, "bronze_gilded", cap_top=True)
    if state_id != "1976":
        structure.add_sphere_faceted((0, Y_BEACON, CZ), 5.2, 6, 10, "glow_monument", "beacon_orb")
    else:
        structure.add_sphere_faceted((0, Y_BEACON, CZ), 5.2, 6, 10, "detail_atlas", "dark_iron")
    structure.add_cylinder(0, 1342, Y_TIP, CZ, 3.0, 0.4, 6, "bronze_gilded")

    # ==========================================================================
    # 8. 2000+ RESTORED STATE: SW VISITOR CENTRE PAVILION
    # ==========================================================================
    if state_id == "2000":
        vx0, vx1, vz0, vz1 = -680.0, -474.0, -250.0, -118.0
        vy0, vy1 = Y_PLAZA, Y_PLAZA + 54.0
        fb_vc = MeshBucket("floor_visitor_center", mat_idx, MAT_SIZE, regions)
        fb_vc.add_panel_y(vx0, vz0, vx1, vz1, vy0 + 2.2, "+y", "marble_plaza")
        floor_buckets["floor_visitor_center"] = fb_vc
        lot_ground.add_box(vx0 - 6, vy0, vz0 - 6, vx1 + 6, vy0 + 2.0, vz1 + 6, "stone_granite")
        lot_ground.add_box(vx0, vy0 + 2, vz0, vx0 + 22, vy1, vz1, "stone_limestone")
        lot_ground.add_box(vx1 - 22, vy0 + 2, vz0, vx1, vy1, vz1, "stone_limestone")
        lot_ground.add_box(vx0 + 22, vy0 + 2, vz0, vx1 - 22, vy1, vz0 + 10, "stone_limestone")
        lot_ground.add_panel_z(vx0 + 22, vy0 + 2, -595, vy1 - 8, vz1 - 4, "+z", "glass_cathedral", "win_cathedral")
        lot_ground.add_panel_z(-559, vy0 + 2, vx1 - 22, vy1 - 8, vz1 - 4, "+z", "glass_cathedral", "win_cathedral")
        lot_ground.add_box(-595, vy0 + 2, vz1 - 6, -592, vy1 - 8, vz1 - 2, "bronze_gilded")
        lot_ground.add_box(-562, vy0 + 2, vz1 - 6, -559, vy1 - 8, vz1 - 2, "bronze_gilded")
        lot_ground.add_box(vx0 - 10, vy1 - 8, vz0 - 10, vx1 + 10, vy1 + 10, vz1 + 10, "stone_ashlar")
        lot_ground.add_box(vx0 - 12, vy1 + 10, vz0 - 12, vx1 + 12, vy1 + 13, vz1 + 12, "bronze_gilded")
        lot_ground.add_panel_z(-625, vy1 - 7, -529, vy1 + 11, vz1 + 10.3, "+z", "inscriptions", "visitor_sign", fit_region=True)
        coll.add_box(vx0 - 6, 0, vz0 - 6, vx1 + 6, vy1 + 13, vz1 + 6, "visitor_center_pavilion", "lot")

    # ==========================================================================
    # 9. INSTANCED PROPS, FLOODLIGHTS (WITH 3000K/GOLD COLORS!), DOORS & PACK GLB
    # ==========================================================================
    lamp_style = "gas" if state_id == "1926" else "electric"
    idx_map = {}
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
    idx_lamp_lit = reg(build_lamp_mesh(MeshBucket, "lamp_lit_inst", mat_idx, MAT_SIZE, regions, style=lamp_style, broken=False))
    idx_lamp_broken = reg(build_lamp_mesh(MeshBucket, "lamp_broken_inst", mat_idx, MAT_SIZE, regions, style=lamp_style, broken=True))
    idx_brazier = reg(build_brazier_eternal_mesh(MeshBucket, mat_idx, MAT_SIZE, regions))
    idx_flag = reg(build_flagpole_civic_mesh(MeshBucket, mat_idx, MAT_SIZE, regions, bunting_1926=(state_id == "1926")))
    idx_bench = reg(build_bench_bronze_mesh(MeshBucket, mat_idx, MAT_SIZE, regions))
    idx_planter = reg(build_planter_mesh(MeshBucket, mat_idx, MAT_SIZE, regions))
    idx_drink = reg(build_drinking_fountain_mesh(MeshBucket, mat_idx, MAT_SIZE, regions))
    idx_tele = reg(build_telescope_deck_mesh(MeshBucket, mat_idx, MAT_SIZE, regions, missing=False))
    idx_tele_miss = reg(build_telescope_deck_mesh(MeshBucket, mat_idx, MAT_SIZE, regions, missing=True))
    idx_tree = reg(build_tree_mesh(MeshBucket, "tree_plaza_inst", mat_idx, MAT_SIZE, regions, mature=(state_id != "1926")))
    idx_flood_fix = reg(build_flood_fixture_mesh(MeshBucket, mat_idx, MAT_SIZE, regions))
    idx_trash = reg(build_trash_bin_mesh(MeshBucket, mat_idx, MAT_SIZE, regions)) if state_id == "2000" else None
    idx_interp = reg(build_interp_panel_mesh(MeshBucket, mat_idx, MAT_SIZE, regions)) if state_id == "2000" else None
    idx_bike = reg(build_bike_rack_mesh(MeshBucket, mat_idx, MAT_SIZE, regions)) if state_id == "2000" else None

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

    # 3-NODE INDEPENDENT 18 m CELESTIAL ARMILLARY SPHERE
    polar_axis_vec = [round(-math.sin(tilt), 5), round(math.cos(tilt), 5), 0.0]
    n_ao = add_node("armillary_outer", mesh=idx_arm_outer,
                    extras={"motion": "fixed", "rotation_axis": [0.0, 0.0, 0.0], "diameter_m": 18.0})
    n_ae = add_node("armillary_ecliptic", mesh=idx_arm_ecliptic,
                    extras={"motion": "rotate_slow_once_per_game_day", "rotation_axis": polar_axis_vec, "tilt_deg": 23.4})
    n_ag = add_node("armillary_globe", mesh=idx_arm_globe,
                    extras={"motion": "rotate_on_polar_axis_once_per_game_day", "rotation_axis": polar_axis_vec, "globe_diameter_m": 5.0})
    add_node("armillary_sphere", translation=[0.0, Y_ARM * U, CZ * U], children=[n_ao, n_ae, n_ag],
             extras={"role": "suspended_celestial_armillary_sphere", "diameter_m": 18.0})

    add_node("lift_car", mesh=idx_lift_car, translation=[NW_CX * U, Y_CORNICE * U, NW_CZ * U],
             extras={"role": "movable_elevator_cab", "stops_y_m": [round(Y_T3 * U, 2), round(Y_CORNICE * U, 2), round(Y_DECK * U, 2)]})

    add_node("beacon_beam", translation=[0.0, Y_BEACON * U, CZ * U],
             extras={"role": "rotating_aviation_beacon", "direction": [0.0, -0.05, 1.0],
                     "color": "#FFFFFF", "rotation_period_s": 10.0, "active": (state_id != "1976")})

    # Floodlight Nodes: flood_01..12 (warm white 3000K #FFE4B5 on stone) + flood_sphere_01..04 (warm gold #FFD88A on inner piers aimed at 18 m sphere!)
    flood_defs = [
        ("flood_01", [-210, Y_T3, CZ + 210], [0.22, 0.95, -0.22], "pier_sw_uplight", "#FFE4B5", 3000),
        ("flood_02", [210, Y_T3, CZ + 210], [-0.22, 0.95, -0.22], "pier_se_uplight", "#FFE4B5", 3000),
        ("flood_03", [-210, Y_T3, CZ - 210], [0.22, 0.95, 0.22], "pier_nw_uplight", "#FFE4B5", 3000),
        ("flood_04", [210, Y_T3, CZ - 210], [-0.22, 0.95, 0.22], "pier_ne_uplight", "#FFE4B5", 3000),
        ("flood_05", [-84, Y_T3, CZ + 84], [0.18, 0.96, -0.18], "rotunda_uplight_sw", "#FFE4B5", 3000),
        ("flood_06", [84, Y_T3, CZ + 84], [-0.18, 0.96, -0.18], "rotunda_uplight_se", "#FFE4B5", 3000),
        ("flood_07", [-84, Y_T3, CZ - 84], [0.18, 0.96, 0.18], "rotunda_uplight_nw", "#FFE4B5", 3000),
        ("flood_08", [84, Y_T3, CZ - 84], [-0.18, 0.96, 0.18], "rotunda_uplight_ne", "#FFE4B5", 3000),
        ("flood_09", [-150, Y_PLAZA, CZ + 440], [0.24, 0.62, -0.75], "plaza_south_west_flood", "#FFE4B5", 3000),
        ("flood_10", [150, Y_PLAZA, CZ + 440], [-0.24, 0.62, -0.75], "plaza_south_east_flood", "#FFE4B5", 3000),
        ("flood_11", [-440, Y_PLAZA, CZ], [0.78, 0.62, 0.0], "plaza_west_flood", "#FFE4B5", 3000),
        ("flood_12", [440, Y_PLAZA, CZ], [-0.78, 0.62, 0.0], "plaza_east_flood", "#FFE4B5", 3000),
        # NEW REVISION 2: 4 Inner-Pier Sphere Uplights aimed straight at the 18 m Armillary Sphere (y = 528)!
        ("flood_sphere_01", [-116, 360, CZ + 116], [116, Y_ARM - 360, -116], "sphere_inner_pier_sw", "#FFD88A", 2800),
        ("flood_sphere_02", [116, 360, CZ + 116], [-116, Y_ARM - 360, -116], "sphere_inner_pier_se", "#FFD88A", 2800),
        ("flood_sphere_03", [-116, 360, CZ - 116], [116, Y_ARM - 360, 116], "sphere_inner_pier_nw", "#FFD88A", 2800),
        ("flood_sphere_04", [116, 360, CZ - 116], [-116, Y_ARM - 360, 116], "sphere_inner_pier_ne", "#FFD88A", 2800),
    ]
    floods_meta = []
    for fname, fpos_art, fdir_raw, frole, fcol, fkelv in flood_defs:
        fn = v_norm(tuple(fdir_raw))
        fpos_m = [round(fpos_art[0] * U, 4), round(fpos_art[1] * U, 4), round(fpos_art[2] * U, 4)]
        fdir_v = [round(x, 4) for x in fn]
        add_node(fname, mesh=(idx_flood_fix if state_id == "2000" else None),
                 translation=fpos_m, extras={"role": frole, "direction": fdir_v, "color": fcol, "color_kelvin": fkelv})
        floods_meta.append({"node": fname, "role": frole, "position_m": fpos_m, "direction": fdir_v, "color": fcol, "color_kelvin": fkelv})

    for fn, mi in floor_mesh_idx.items():
        add_node(fn, mesh=mi)

    # 4 Brass Viewing Telescopes on 58 m Deck (East missing in 1976!)
    for ti, (tx, tz, rot_y) in enumerate(((0, CZ + 174, 0.0), (0, CZ - 174, math.pi), (174, CZ, math.pi * 0.5), (-174, CZ, -math.pi * 0.5))):
        is_miss = (state_id == "1976" and ti == 2)
        add_node(f"sky_telescope_{ti + 1:02d}", mesh=(idx_tele_miss if is_miss else idx_tele),
                 translation=[tx * U, Y_DECK * U, tz * U], rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None)

    lot_children = [add_node("lot_ground", mesh=idx_lot_ground)]

    # 4 ETERNAL-FLAME BRONZE TRIPOD BRAZIERS perched on top & foot of the MAIN SOUTH STAIRCASE CHEEK WALLS!
    for bi, (bx, by, bz) in enumerate((
        (-105.0, Y_T3 + 6.0, CZ + 286.0), (105.0, Y_T3 + 6.0, CZ + 286.0),
        (-105.0, Y_T1 + 6.0, CZ + 372.0), (105.0, Y_T1 + 6.0, CZ + 372.0),
    )):
        lot_children.append(add_node(f"eternal_brazier_{bi + 1:02d}", mesh=idx_brazier,
                                     translation=[bx * U, by * U, bz * U]))
        coll.add_box(bx - 12, by, bz - 12, bx + 12, by + 34, bz + 12, f"eternal_brazier_{bi + 1}", "lot")

    # 12 Civic Flagpoles flanking the Reflecting Pool (with 1926 red/white/blue base fabric wraps!)
    f_idx = 0
    for sx in (-168, 168):
        for fz in (-342, -296, -250, -204, -158, -112):
            f_idx += 1
            lot_children.append(add_node(f"civic_flagpole_{f_idx:02d}", mesh=idx_flag, translation=[sx * U, Y_PLAZA * U, fz * U]))
            coll.add_box(sx - 7, 0, fz - 7, sx + 7, 184, fz + 7, f"civic_flagpole_{f_idx:02d}", "lot")

    # 18 Regular-Rhythm Plaza & Exedra Lamp Standards (2 flanking Exedra + 16 along pool & paths; 3 broken in 1976!)
    lamp_coords = [
        (-86, -92), (86, -92),  # Two bronze lamp standards flanking the semicircular Exedra!
        (-126, -340), (126, -340), (-126, -240), (126, -240), (-126, -140), (126, -140),
        (-260, -456), (260, -456), (-430, CZ + 180), (430, CZ + 180),
        (-430, CZ - 180), (430, CZ - 180), (-260, CZ - 440), (260, CZ - 440),
        (-130, CZ - 560), (130, CZ - 560),
    ]
    broken_lamps = {4, 9, 13} if state_id == "1976" else set()
    for li, (lx, lz) in enumerate(lamp_coords):
        lot_children.append(add_node(f"plaza_lamp_{li + 1:02d}",
                                     mesh=(idx_lamp_broken if li in broken_lamps else idx_lamp_lit),
                                     translation=[lx * U, Y_PLAZA * U, lz * U]))

    # 16 Bronze Benches (4 per side along the reflecting pool + 4 each on E/W/N sides!)
    bench_coords = [
        # 4 along West side of reflecting pool
        (-134, -318, math.pi * 0.5), (-134, -264, math.pi * 0.5), (-134, -210, math.pi * 0.5), (-134, -156, math.pi * 0.5),
        # 4 along East side of reflecting pool
        (134, -318, -math.pi * 0.5), (134, -264, -math.pi * 0.5), (134, -210, -math.pi * 0.5), (134, -156, -math.pi * 0.5),
        # 4 flanking West & East monument walks + 4 along North plaza
        (-412, CZ + 110, math.pi * 0.5), (-412, CZ - 110, math.pi * 0.5),
        (412, CZ + 110, -math.pi * 0.5), (412, CZ - 110, -math.pi * 0.5),
        (-240, CZ - 440, math.pi), (-150, CZ - 440, math.pi), (150, CZ - 440, math.pi), (240, CZ - 440, math.pi),
    ]
    for bi, (bx, bz, rot_y) in enumerate(bench_coords):
        lot_children.append(add_node(f"plaza_bench_{bi + 1:02d}", mesh=idx_bench,
                                     translation=[bx * U, Y_PLAZA * U, bz * U],
                                     rotation=quat_y(rot_y) if abs(rot_y) > 1e-4 else None))

    # Planters at all 4 Staircase Feet (8 planters) + Civic Drinking Fountain!
    planter_coords = [
        (-136, CZ + 382), (136, CZ + 382), (-118, CZ - 392), (118, CZ - 392),
        (392, CZ - 118), (392, CZ + 118), (-392, CZ - 118), (-392, CZ + 118),
    ]
    for pi, (px, pz) in enumerate(planter_coords):
        lot_children.append(add_node(f"stair_planter_{pi + 1:02d}", mesh=idx_planter, translation=[px * U, Y_PLAZA * U, pz * U]))
    lot_children.append(add_node("drinking_fountain_01", mesh=idx_drink, translation=[-148 * U, Y_PLAZA * U, -372 * U]))

    # 2000+ Restored Plaza Life: 1 m Interpretive Sign Panels along Reflecting Pool, Trash Bins, and Bike Rack!
    if state_id == "2000":
        for ii, (ix, iz, ry) in enumerate((
            (-114, -292, math.pi * 0.5), (114, -292, -math.pi * 0.5),
            (-114, -182, math.pi * 0.5), (114, -182, -math.pi * 0.5),
        )):
            lot_children.append(add_node(f"interp_panel_{ii + 1:02d}", mesh=idx_interp,
                                         translation=[ix * U, Y_PLAZA * U, iz * U], rotation=quat_y(ry)))
        for ti, (tx, tz) in enumerate(((-146, -240), (146, -240), (-146, -130), (146, -130), (-462, -130))):
            lot_children.append(add_node(f"trash_bin_{ti + 1:02d}", mesh=idx_trash, translation=[tx * U, Y_PLAZA * U, tz * U]))
        lot_children.append(add_node("bike_rack_01", mesh=idx_bike, translation=[-440 * U, Y_PLAZA * U, -172 * U]))
        coll.add_box(-464, 0, -178, -416, 14, -166, "bike_rack_2000", "lot")

    # 8 Formal-Row Ceremonial Plaza Trees (`tree_01..tree_08`)
    tree_coords = [
        (-620, -420), (-360, -420), (360, -420), (620, -420),
        (-620, CZ - 420), (-360, CZ - 420), (360, CZ - 420), (620, CZ - 420),
    ]
    for tri, (tx, tz) in enumerate(tree_coords):
        lot_children.append(add_node(f"tree_{tri + 1:02d}", mesh=idx_tree, translation=[tx * U, Y_PLAZA * U, tz * U]))
        coll.add_box(tx - 6, 0, tz - 6, tx + 6, 96 if state_id != "1926" else 56, tz + 6, f"tree_{tri + 1:02d}", "lot")

    add_node("lot", children=lot_children)

    # Named Door Nodes (same origin & coordinates across all 4 states!)
    door_specs = [
        ("door_main", [0.0, Y_PLAZA * U, -370.0 * U], 0.0),
        ("door_rotunda_south", [0.0, Y_T3 * U, (CZ + ARCH_OUTER) * U], 0.0),
        ("door_north", [0.0, Y_T3 * U, (CZ - ARCH_OUTER) * U], math.pi),
        ("door_east", [ARCH_OUTER * U, Y_T3 * U, CZ * U], math.pi * 0.5),
        ("door_west", [-ARCH_OUTER * U, Y_T3 * U, CZ * U], -math.pi * 0.5),
        ("door_hall_west", [-ARCH_INNER * U, Y_T3 * U, (CZ + 160.0) * U], math.pi * 0.5),
        ("door_hall_east", [ARCH_INNER * U, Y_T3 * U, (CZ + 160.0) * U], -math.pi * 0.5),
        ("door_observatory_lift", [-ARCH_INNER * U, Y_T3 * U, (CZ - 160.0) * U], math.pi * 0.5),
        ("door_cornice_gallery", [NW_CX * U, Y_CORNICE * U, (CZ - 116.0) * U], 0.0),
        ("door_sky_deck", [NW_CX * U, Y_DECK * U, (CZ - 116.0) * U], 0.0),
        ("door_lantern_chamber", [-60.0 * U, 774.0 * U, (CZ + 24.0) * U], 0.0),
    ]
    if state_id == "2000":
        door_specs.append(("door_visitor_center", [-577.0 * U, (Y_PLAZA + 2.2) * U, -118.0 * U], 0.0))
    for dname, dpos, drot in door_specs:
        add_node(dname, translation=dpos, rotation=quat_y(drot) if abs(drot) > 1e-4 else None)

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


def main():
    regions = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    state_meta = {}
    state_years = {
        "1926": {"years": "1926-1940", "label": "Dedication",
                 "summary": "Clean limestone, glowing fresh orange-brown copper spire, cornice bunting swags, flagpole-base bunting, timber dedication stand, gas lamps, young trees."},
        "1941": {"years": "1941-1975", "label": "Mature / Wartime",
                 "summary": "Slightly darkened stone with faint drips, streaky brown-green copper, 1941-1945 bronze war plaque, sandbags at main stairs, taped X-cross hall windows, mature trees."},
        "1976": {"years": "1976-1999", "label": "Worn / Stalled Repairs",
                 "summary": "Heavy dark cornice drips, deep black-streaked green copper, paving weeds, chipped Industry gear, tarped SE pier scaffolding + chain-link fence, 'CLOSED FOR REPAIRS' North stair sawhorse, beacon off, 3 broken lamps, East telescope missing."},
        "2000": {"years": "2000+", "label": "Restored & Accessible",
                 "summary": "Cleaned pale stone, restored streak-free bright verdigris copper, visible LED floodlight fixtures, East accessibility ramp, SW Visitor Centre, 1 m interpretive panels, trash bins, bike rack."},
    }
    state_coll = {}
    ref_res = None
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
        state_coll[sid] = res["collision_boxes"]
        if sid == "2000":
            ref_res = res

    mid_info = build_mid_lod(MeshBucket, write_glb, R, MAT_SIZE, regions)
    far_info = build_far_lod(MeshBucket, write_glb, R, MAT_SIZE, regions)
    tilt = math.radians(23.4)
    polar_axis_vec = [round(-math.sin(tilt), 5), round(math.cos(tilt), 5), 0.0]

    model_info = {
        "name": "The Great Meridian Monument",
        "slug": "great-meridian-monument",
        "revision": 2,
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
            "armillary_sphere_center": round(528 * U, 2),
            "guardian_ledges_44m": round(574 * U, 2),
            "pier_setback_2": round(593 * U, 2),
            "sky_observation_deck_58m": round(748 * U, 2),
            "lantern_chamber_floor": round(774 * U, 2),
            "lantern_tier1_top": round(864 * U, 2),
            "lantern_tier2_top": round(935 * U, 2),
            "lantern_tier3_top": round(980 * U, 2),
            "sunburst_drum_top": round(1018 * U, 2),
            "spire_top": round(1304 * U, 2),
            "aviation_beacon_orb": round(1322 * U, 2),
            "finial_tip_total": round(1368 * U, 2),
        },
        "states": state_meta,
        "mid_lod": mid_info,
        "far_lod": far_info,
        "materials": [m["name"] for m in materials_for_state("2000")],
        "lighting_palette": {
            "stone_floods": {"color": "#FFE4B5", "kelvin": 3000, "description": "Warm white 3,000 K stone wash"},
            "sphere_floods": {"color": "#FFD88A", "kelvin": 2800, "description": "Warm gold uplighting on 18 m sphere"},
            "beacon_beam": {"color": "#FFFFFF", "description": "Sweeping white aviation beam"},
            "lantern_lancets": {"color": "#FFB347", "description": "Amber crown lancet glow"},
            "eternal_flames": {"color": "#FF7A28", "description": "Orange eternal tripod brazier flames"},
        },
        "armillary_nodes": {
            "parent": "armillary_sphere",
            "diameter_m": 18.0,
            "outer_fixed": {"node": "armillary_outer", "rotation_axis": [0.0, 0.0, 0.0]},
            "ecliptic_rotating": {"node": "armillary_ecliptic", "rotation_axis": polar_axis_vec, "tilt_deg": 23.4, "period": "1 game day"},
            "earth_globe_rotating": {"node": "armillary_globe", "rotation_axis": polar_axis_vec, "polar_axis_tilt_deg": 23.4, "globe_diameter_m": 5.0, "period": "1 game day"},
        },
        "beacon_beam": {
            "node": "beacon_beam",
            "position_m": [0.0, round(1322 * U, 4), round(-773 * U, 4)],
            "direction": [0.0, -0.05, 1.0],
            "color": "#FFFFFF",
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
            "per_state_extras": {
                sid: [b for b in state_coll[sid] if any(k in b["source"] for k in ("1926", "1941", "1976", "visitor", "ramp", "bike"))]
                for sid in ("1926", "1941", "1976", "2000")
            },
        },
    }
    (R / "model-info.json").write_text(json.dumps(model_info, indent=2) + "\n", encoding="utf-8")
    print("Wrote model-info.json for Revision 2 (all 4 states + mid LOD + far LOD).")


if __name__ == "__main__":
    main()
