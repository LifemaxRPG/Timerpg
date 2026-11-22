#!/usr/bin/env python3
"""
Builds a 1-to-1 pixel-art 3D GLB model of "Rex" — the iconic 1964 Jacksonville, FL
Beach Boulevard dinosaur statue (10150 Beach Blvd, former Goony Golf mascot, 2007 restoration)
directly matched to real-world photographs from multiple angles:
  - Total height: ~6.30 m (20.7 ft) | Total length: ~7.10 m (23.3 ft)
  - Smooth vermilion-orange face, snout (with bold dark-ringed 'OO' nostrils), front throat & arms
  - Heavy pebbled/scaly vermilion-orange stucco hood over crown, back of neck, back, chunky legs & tail
  - Smooth teal/turquoise teardrop belly patch with subtle weathered ochre patina
  - Small narrow horizontal wedge grin with neat 3D white triangular teeth & recessed glowing red eyes
  - Giant ~3.05 m (10 ft) vertical white femur bone cane under Rex's left hand; right arm reaching forward
  - Chunky wide hind legs with 3 broad pale yellow-cream toenails on each foot
  - Roadside lot ('lot' node): dark pine-bark mulch, tall 1.68 m black wrought-iron picket fence,
    angled historical plaque inside the left fence, grass apron, concrete curb & Beach Blvd asphalt.

Follows docs/CHATGPT_MODEL_GUIDE.md:
  - glTF 2.0 binary (.glb), metres, Y-up, front facing +Z, origin (0,0,0) at front center edge
  - Exact 0.0388 m/texel (2x2 texel art-pixels = 0.0776 m/art-px)
  - Nearest filtering (9728), repeat wrapping (10497), 9 materials (<=12), <12k tris
  - Separated nodes: rex_statue, lot, collision, door_main
"""
import json
import math
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TEX_DIR = ROOT / "textures"
REGIONS = json.loads((ROOT / "art-regions.json").read_text())

TEXEL_M = 0.0388
ART_PX_M = TEXEL_M * 2.0  # 0.0776 m per art pixel


def v_add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


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
    return (a[0] / l, a[1] / l, a[2] / l) if l > 1e-8 else (0.0, 1.0, 0.0)


def read_png_size(path: Path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    return w, h


def _ortho_frame(tangent):
    t = v_norm(tangent)
    ref = (1.0, 0.0, 0.0) if abs(t[0]) < 0.85 else (0.0, 0.0, 1.0)
    up = v_norm(v_cross(t, ref))
    if up[1] < 0.0 and abs(t[1]) < 0.92:
        up = v_scale(up, -1.0)
    right = v_norm(v_cross(up, t))
    up = v_norm(v_cross(t, right))
    return right, up, t


class MeshBucket:
    def __init__(self, name, mat_sizes):
        self.name = name
        self.mat_sizes = mat_sizes
        self.prims = {}

    def _p(self, mat):
        if mat not in self.prims:
            self.prims[mat] = {"pos": [], "nrm": [], "uv": [], "idx": []}
        return self.prims[mat]

    def _world_uv(self, pt, mat, u_axis, v_axis, uv_rect_art=None):
        tw, th = self.mat_sizes[mat]
        tile_w_m = tw * TEXEL_M
        tile_h_m = th * TEXEL_M
        u = v_dot(pt, u_axis) / tile_w_m
        v = -v_dot(pt, v_axis) / tile_h_m
        if uv_rect_art is not None:
            rx, ry, rw, rh = uv_rect_art
            aw, ah = tw / 2.0, th / 2.0
            rw_m = rw * ART_PX_M
            rh_m = rh * ART_PX_M
            fu = (v_dot(pt, u_axis) / rw_m) % 1.0
            fv = (-v_dot(pt, v_axis) / rh_m) % 1.0
            u = (rx + fu * rw) / aw
            v = (ry + fv * rh) / ah
        return (u, v)

    def _rect_uv(self, mat, uv_rect_art, fu, fv):
        tw, th = self.mat_sizes[mat]
        aw, ah = tw / 2.0, th / 2.0
        rx, ry, rw, rh = uv_rect_art
        return ((rx + fu * rw) / aw, (ry + fv * rh) / ah)

    def add_tri_uv(self, p0, p1, p2, uv0, uv1, uv2, mat):
        n = v_norm(v_cross(v_sub(p1, p0), v_sub(p2, p0)))
        pr = self._p(mat)
        base = len(pr["pos"])
        pr["pos"].extend([p0, p1, p2])
        pr["nrm"].extend([n, n, n])
        pr["uv"].extend([uv0, uv1, uv2])
        pr["idx"].extend([base, base + 1, base + 2])

    def add_quad_uv(self, p0, p1, p2, p3, uv0, uv1, uv2, uv3, mat):
        n = v_norm(v_cross(v_sub(p1, p0), v_sub(p2, p0)))
        if v_len(v_cross(v_sub(p1, p0), v_sub(p2, p0))) < 1e-8:
            n = v_norm(v_cross(v_sub(p2, p0), v_sub(p3, p0)))
        pr = self._p(mat)
        base = len(pr["pos"])
        pr["pos"].extend([p0, p1, p2, p3])
        pr["nrm"].extend([n, n, n, n])
        pr["uv"].extend([uv0, uv1, uv2, uv3])
        pr["idx"].extend([base, base + 1, base + 2, base, base + 2, base + 3])

    def add_tri(self, p0, p1, p2, mat, uv_rect_art=None, fit_rect=False):
        n = v_norm(v_cross(v_sub(p1, p0), v_sub(p2, p0)))
        if fit_rect and uv_rect_art is not None:
            uv0 = self._rect_uv(mat, uv_rect_art, 0.5, 0.06)
            uv1 = self._rect_uv(mat, uv_rect_art, 0.08, 0.94)
            uv2 = self._rect_uv(mat, uv_rect_art, 0.92, 0.94)
            self.add_tri_uv(p0, p1, p2, uv0, uv1, uv2, mat)
            return
        if abs(n[1]) > 0.72:
            ua, va = (1.0, 0.0, 0.0), (0.0, 0.0, -1.0)
        else:
            ua = v_norm(v_cross((0.0, 1.0, 0.0), n))
            va = (0.0, 1.0, 0.0)
        if uv_rect_art is not None:
            # Anchor tri locally inside rect so UVs stay strictly inside the atlas sub-rect
            rx, ry, rw, rh = uv_rect_art
            tw, th = self.mat_sizes[mat]
            aw, ah = tw / 2.0, th / 2.0
            us = [v_dot(p, ua) for p in (p0, p1, p2)]
            vs = [-v_dot(p, va) for p in (p0, p1, p2)]
            u0, v0 = min(us), min(vs)
            rw_m, rh_m = rw * ART_PX_M, rh * ART_PX_M
            uvs = [
                ((rx + min(0.98, max(0.02, (uu - u0) / rw_m)) * rw) / aw,
                 (ry + min(0.98, max(0.02, (vv - v0) / rh_m)) * rh) / ah)
                for uu, vv in zip(us, vs)
            ]
            self.add_tri_uv(p0, p1, p2, uvs[0], uvs[1], uvs[2], mat)
            return
        uv0 = self._world_uv(p0, mat, ua, va)
        uv1 = self._world_uv(p1, mat, ua, va)
        uv2 = self._world_uv(p2, mat, ua, va)
        self.add_tri_uv(p0, p1, p2, uv0, uv1, uv2, mat)

    def add_quad(self, p0, p1, p2, p3, mat, u_axis=None, v_axis=None, uv_rect_art=None, fit_rect=False):
        n = v_norm(v_cross(v_sub(p1, p0), v_sub(p2, p0)))
        if fit_rect and uv_rect_art is not None:
            uv0 = self._rect_uv(mat, uv_rect_art, 0.02, 0.98)
            uv1 = self._rect_uv(mat, uv_rect_art, 0.98, 0.98)
            uv2 = self._rect_uv(mat, uv_rect_art, 0.98, 0.02)
            uv3 = self._rect_uv(mat, uv_rect_art, 0.02, 0.02)
            self.add_quad_uv(p0, p1, p2, p3, uv0, uv1, uv2, uv3, mat)
            return
        if u_axis is None or v_axis is None:
            if abs(n[1]) > 0.72:
                u_axis, v_axis = (1.0, 0.0, 0.0), (0.0, 0.0, -1.0)
            else:
                u_axis = v_norm(v_cross((0.0, 1.0, 0.0), n))
                v_axis = (0.0, 1.0, 0.0)
        if uv_rect_art is not None:
            rx, ry, rw, rh = uv_rect_art
            tw, th = self.mat_sizes[mat]
            aw, ah = tw / 2.0, th / 2.0
            pts = (p0, p1, p2, p3)
            us = [v_dot(p, u_axis) for p in pts]
            vs = [-v_dot(p, v_axis) for p in pts]
            u0, v0 = min(us), min(vs)
            du = max(us) - u0
            dv = max(vs) - v0
            rw_m, rh_m = rw * ART_PX_M, rh * ART_PX_M
            # Tile cleanly inside rect if oversized, or map proportionally at texel scale
            uvs = []
            for uu, vv in zip(us, vs):
                fu = (uu - u0) / max(rw_m, du, 1e-5) if du > rw_m else (uu - u0) / rw_m
                fv = (vv - v0) / max(rh_m, dv, 1e-5) if dv > rh_m else (vv - v0) / rh_m
                fu = min(0.98, max(0.02, fu))
                fv = min(0.98, max(0.02, fv))
                uvs.append(((rx + fu * rw) / aw, (ry + fv * rh) / ah))
            self.add_quad_uv(p0, p1, p2, p3, uvs[0], uvs[1], uvs[2], uvs[3], mat)
            return
        uv0 = self._world_uv(p0, mat, u_axis, v_axis)
        uv1 = self._world_uv(p1, mat, u_axis, v_axis)
        uv2 = self._world_uv(p2, mat, u_axis, v_axis)
        uv3 = self._world_uv(p3, mat, u_axis, v_axis)
        self.add_quad_uv(p0, p1, p2, p3, uv0, uv1, uv2, uv3, mat)

    def add_tiled_strip(self, p0, p1, p2, p3, mat, uv_rect_art, tile_u_m, tile_v_m=None):
        """Subdivides a long wall/fence strip into exact tile_u_m segments so sub-rect textures repeat seamlessly!"""
        width = v_len(v_sub(p1, p0))
        height = v_len(v_sub(p3, p0))
        segs = max(1, int(math.ceil(width / tile_u_m)))
        for s in range(segs):
            t0 = s / segs
            t1 = (s + 1) / segs
            a0 = v_add(p0, v_scale(v_sub(p1, p0), t0))
            a1 = v_add(p0, v_scale(v_sub(p1, p0), t1))
            b1 = v_add(p3, v_scale(v_sub(p2, p3), t1))
            b0 = v_add(p3, v_scale(v_sub(p2, p3), t0))
            seg_w = v_len(v_sub(a1, a0))
            fu1 = min(0.99, max(0.05, seg_w / tile_u_m))
            fv0 = 0.01
            fv1 = 0.99 if tile_v_m is None else min(0.99, max(0.05, height / tile_v_m))
            uv0 = self._rect_uv(mat, uv_rect_art, 0.01, fv1)
            uv1 = self._rect_uv(mat, uv_rect_art, fu1, fv1)
            uv2 = self._rect_uv(mat, uv_rect_art, fu1, fv0)
            uv3 = self._rect_uv(mat, uv_rect_art, 0.01, fv0)
            self.add_quad_uv(a0, a1, b1, b0, uv0, uv1, uv2, uv3, mat)

    def add_box(self, x0, y0, z0, x1, y1, z1, mat, faces=("+x", "-x", "+y", "-y", "+z", "-z"),
                uv_rect_art=None, fit_rect=False):
        if "+z" in faces:
            self.add_quad((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
                          mat, (1, 0, 0), (0, 1, 0), uv_rect_art, fit_rect)
        if "-z" in faces:
            self.add_quad((x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0),
                          mat, (-1, 0, 0), (0, 1, 0), uv_rect_art, fit_rect)
        if "+x" in faces:
            self.add_quad((x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1),
                          mat, (0, 0, -1), (0, 1, 0), uv_rect_art, fit_rect)
        if "-x" in faces:
            self.add_quad((x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0),
                          mat, (0, 0, 1), (0, 1, 0), uv_rect_art, fit_rect)
        if "+y" in faces:
            self.add_quad((x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0),
                          mat, (1, 0, 0), (0, 0, -1), uv_rect_art, fit_rect)
        if "-y" in faces:
            self.add_quad((x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1),
                          mat, (1, 0, 0), (0, 0, 1), uv_rect_art, fit_rect)

    def tri_count(self):
        return sum(len(p["idx"]) // 3 for p in self.prims.values())


class CollisionCollector:
    def __init__(self):
        self.boxes = []

    def add(self, source, x0, y0, z0, x1, y1, z1):
        self.boxes.append({
            "source": source,
            "min": [round(x0, 3), round(y0, 3), round(z0, 3)],
            "max": [round(x1, 3), round(y1, 3), round(z1, 3)],
            "center": [round((x0 + x1) * 0.5, 3), round((y0 + y1) * 0.5, 3), round((z0 + z1) * 0.5, 3)],
            "size": [round(x1 - x0, 3), round(y1 - y0, 3), round(z1 - z0, 3)],
        })


def add_curved_tube(bucket, stations, segs, mat_fn, cap_start=True, cap_end=True, uv_rect_fn=None):
    """
    Lofts a smooth faceted organic tube along spine stations:
      station = (x, y, z, rx, ry_top, ry_bot)
    Ring angle a=0 starts at dorsal top (+U), proceeds clockwise around right (+R),
    ventral bottom (-U at a=pi), and left (-R).
    mat_fn(k, i, segs) -> material name for quad between station k and k+1, ring edge i..i+1.
    """
    rings = []
    arc_len = [0.0]
    for k in range(1, len(stations)):
        arc_len.append(arc_len[-1] + v_len(v_sub(stations[k][:3], stations[k - 1][:3])))

    for k, st in enumerate(stations):
        cx, cy, cz, rx, ry_top, ry_bot = st
        if k == 0:
            tang = v_sub(stations[1][:3], stations[0][:3])
        elif k == len(stations) - 1:
            tang = v_sub(stations[-1][:3], stations[-2][:3])
        else:
            tang = v_sub(stations[k + 1][:3], stations[k - 1][:3])
        R, U, _ = _ortho_frame(tang)
        ring = []
        for i in range(segs):
            a = 2.0 * math.pi * i / segs
            sa = math.sin(a)
            ca = math.cos(a)
            ry = ry_top if ca >= 0.0 else ry_bot
            # Slightly flatten belly/throat underside gently if needed
            pt = v_add((cx, cy, cz), v_add(v_scale(R, rx * sa), v_scale(U, ry * ca)))
            ring.append(pt)
        rings.append(ring)

    for k in range(len(rings) - 1):
        r0, r1 = rings[k], rings[k + 1]
        v0_m = arc_len[k]
        v1_m = arc_len[k + 1]
        circ_m = 2.0 * math.pi * 0.5 * (stations[k][3] + stations[k + 1][3] + stations[k][4] + stations[k + 1][4]) * 0.5
        for i in range(segs):
            j = (i + 1) % segs
            mat = mat_fn(k, i, segs)
            rect = uv_rect_fn(k, i, segs) if uv_rect_fn else None
            tw, th = bucket.mat_sizes[mat]
            if rect is not None:
                rx, ry, rw, rh = rect
                aw, ah = tw / 2.0, th / 2.0
                rw_m, rh_m = rw * ART_PX_M, rh * ART_PX_M
                u_a = ((i / segs) * circ_m / rw_m) % 1.0
                u_b = u_a + (circ_m / segs) / rw_m
                if u_b > 0.98:
                    u_a = max(0.02, u_a - (u_b - 0.98))
                    u_b = 0.98
                v_a = (v0_m / rh_m) % 1.0
                v_b = v_a + (v1_m - v0_m) / rh_m
                if v_b > 0.98:
                    v_a = max(0.02, v_a - (v_b - 0.98))
                    v_b = 0.98
                uv0 = ((rx + u_a * rw) / aw, (ry + v_a * rh) / ah)
                uv1 = ((rx + u_a * rw) / aw, (ry + v_b * rh) / ah)
                uv2 = ((rx + u_b * rw) / aw, (ry + v_b * rh) / ah)
                uv3 = ((rx + u_b * rw) / aw, (ry + v_a * rh) / ah)
            else:
                tile_w = tw * TEXEL_M
                tile_h = th * TEXEL_M
                u_a = (i / segs) * circ_m / tile_w
                u_b = ((i + 1) / segs) * circ_m / tile_w
                v_a = v0_m / tile_h
                v_b = v1_m / tile_h
                uv0 = (u_a, v_a)
                uv1 = (u_a, v_b)
                uv2 = (u_b, v_b)
                uv3 = (u_b, v_a)
            bucket.add_quad_uv(r0[i], r1[i], r1[j], r0[j], uv0, uv1, uv2, uv3, mat)

    if cap_start:
        c0 = stations[0][:3]
        t0 = v_norm(v_sub(stations[1][:3], stations[0][:3]))
        tip = v_sub(c0, v_scale(t0, min(stations[0][3], stations[0][4]) * 0.55))
        for i in range(segs):
            j = (i + 1) % segs
            mat = mat_fn(0, i, segs)
            rect = uv_rect_fn(0, i, segs) if uv_rect_fn else None
            bucket.add_tri(tip, rings[0][i], rings[0][j], mat, uv_rect_art=rect)

    if cap_end:
        c1 = stations[-1][:3]
        t1 = v_norm(v_sub(stations[-1][:3], stations[-2][:3]))
        tip = v_add(c1, v_scale(t1, min(stations[-1][3], stations[-1][4]) * 0.55))
        for i in range(segs):
            j = (i + 1) % segs
            mat = mat_fn(len(rings) - 2, i, segs)
            rect = uv_rect_fn(len(rings) - 2, i, segs) if uv_rect_fn else None
            bucket.add_tri(tip, rings[-1][j], rings[-1][i], mat, uv_rect_art=rect)


def add_ellipsoid(bucket, center, rx, ry, rz, mat, segs=8, stacks=5, uv_rect_art=None, fit_rect=False):
    cx, cy, cz = center
    rings = []
    for st in range(1, stacks):
        phi = math.pi * st / stacks
        sp, cp = math.sin(phi), math.cos(phi)
        ring = []
        for sg in range(segs):
            th = 2.0 * math.pi * sg / segs
            ring.append((cx + rx * sp * math.sin(th), cy + ry * cp, cz + rz * sp * math.cos(th)))
        rings.append(ring)

    top = (cx, cy + ry, cz)
    bot = (cx, cy - ry, cz)
    for sg in range(segs):
        nxt = (sg + 1) % segs
        bucket.add_tri(top, rings[0][sg], rings[0][nxt], mat, uv_rect_art=uv_rect_art, fit_rect=fit_rect)
        bucket.add_tri(bot, rings[-1][nxt], rings[-1][sg], mat, uv_rect_art=uv_rect_art, fit_rect=fit_rect)
    for st in range(len(rings) - 1):
        r0, r1 = rings[st], rings[st + 1]
        for sg in range(segs):
            nxt = (sg + 1) % segs
            bucket.add_quad(r0[sg], r1[sg], r1[nxt], r0[nxt], mat, uv_rect_art=uv_rect_art, fit_rect=fit_rect)


def add_mapped_eye_bulb(bucket, center, radius, mat, uv_rect_art, look_dir, segs=10, stacks=6):
    """Single-projected glowing ruby-red lightbulb eye inside Rex's recessed socket."""
    cx, cy, cz = center
    fwd = v_norm(look_dir)
    right = v_norm(v_cross((0.0, 1.0, 0.0), fwd))
    up = v_norm(v_cross(fwd, right))
    tw, th = bucket.mat_sizes[mat]
    aw, ah = tw / 2.0, th / 2.0
    rx, ry, rw, rh = uv_rect_art

    def pt_uv(p):
        d = v_norm(v_sub(p, center))
        u_proj = v_dot(d, right)
        v_proj = v_dot(d, up)
        fu = max(0.03, min(0.97, 0.5 + 0.46 * u_proj))
        fv = max(0.03, min(0.97, 0.5 - 0.46 * v_proj))
        return ((rx + fu * rw) / aw, (ry + fv * rh) / ah)

    rings = []
    for st in range(1, stacks):
        phi = math.pi * st / stacks
        sp, cp = math.sin(phi), math.cos(phi)
        ring = []
        for sg in range(segs):
            th = 2.0 * math.pi * sg / segs
            ring.append((cx + radius * sp * math.sin(th), cy + radius * cp, cz + radius * sp * math.cos(th)))
        rings.append(ring)

    top = (cx, cy + radius, cz)
    bot = (cx, cy - radius, cz)
    for sg in range(segs):
        nxt = (sg + 1) % segs
        bucket.add_tri_uv(top, rings[0][sg], rings[0][nxt], pt_uv(top), pt_uv(rings[0][sg]), pt_uv(rings[0][nxt]), mat)
        bucket.add_tri_uv(bot, rings[-1][nxt], rings[-1][sg], pt_uv(bot), pt_uv(rings[-1][nxt]), pt_uv(rings[-1][sg]), mat)
    for st in range(len(rings) - 1):
        r0, r1 = rings[st], rings[st + 1]
        for sg in range(segs):
            nxt = (sg + 1) % segs
            bucket.add_quad_uv(
                r0[sg], r1[sg], r1[nxt], r0[nxt],
                pt_uv(r0[sg]), pt_uv(r1[sg]), pt_uv(r1[nxt]), pt_uv(r0[nxt]),
                mat,
            )


def add_tooth(bucket, base_center, tip_pos, half_w, half_d, mat, uv_rect_art):
    """Crisp small 4-sided white triangular tooth matching Rex's neat sawtooth grin."""
    t_dir = v_norm(v_sub(tip_pos, base_center))
    R, U, _ = _ortho_frame(t_dir)
    c0 = v_add(base_center, v_add(v_scale(R,  half_w), v_scale(U,  half_d)))
    c1 = v_add(base_center, v_add(v_scale(R, -half_w), v_scale(U,  half_d)))
    c2 = v_add(base_center, v_add(v_scale(R, -half_w), v_scale(U, -half_d)))
    c3 = v_add(base_center, v_add(v_scale(R,  half_w), v_scale(U, -half_d)))
    bucket.add_tri(c0, tip_pos, c1, mat, uv_rect_art=uv_rect_art, fit_rect=True)
    bucket.add_tri(c1, tip_pos, c2, mat, uv_rect_art=uv_rect_art, fit_rect=True)
    bucket.add_tri(c2, tip_pos, c3, mat, uv_rect_art=uv_rect_art, fit_rect=True)
    bucket.add_tri(c3, tip_pos, c0, mat, uv_rect_art=uv_rect_art, fit_rect=True)


def add_rex_statue(bucket, coll, regs):
    """
    Exact 1-to-1 sculptural reconstruction of Jacksonville's Beach Blvd Rex:
      - Standing on mulch at Y = 0.0, facing +Z, height = 6.32 m (20.7 ft)
      - Long slanted tubular neck (smooth front throat, rough pebbled back!)
      - Rounded chunky blunt head (rough pebbled hood on crown/back, smooth orange face,
        bold dark-ringed 'OO' nostrils, small narrow wedge grin with neat white teeth,
        recessed dark sockets with small glowing ruby-red lightbulb eyes)
      - Smooth teal/turquoise teardrop belly patch on the front of the torso
      - Smooth orange cylindrical arms: LEFT arm (+X) resting on top of the 3.05 m white
        femur bone cane; RIGHT arm (-X) reaching out forward
      - Chunky rough-stucco hind legs with 3 broad pale yellow-cream toenails per foot
      - Thick rounded ground-resting tail sweeping straight back along the mulch
    """
    reg_sm = regs["skin_rex_smooth"]
    reg_btt = regs["bone_toes_teeth"]
    reg_glow = regs["glow_rex_eyes"]

    # =========================================================================
    # 1. TAIL + HIPS + TEAL-BELLIED TORSO + STOCKY CURVED S-NECK
    # =========================================================================
    # Measured directly against real Flickr photos (rex-photo-1, rex-ref-front, rex-ref-angle3, rex-ref-angle4):
    # - Total height ~6.25 m (20.5 ft), with the BIG chunky head filling ~23% of total height!
    # - Plump pear-shaped lower belly/hips with teal teardrop patch strictly below shoulders/arms
    # - Sturdy graceful S-curved neck (NOT skinny/stiff!) with smooth front throat & rough pebbled back!
    spine = [
        # (x, y, z, rx, ry_top, ry_bot)
        (0.00, 0.16, -8.55, 0.16, 0.16, 0.16),   # 0: blunt rounded tail tip on mulch
        (0.00, 0.26, -7.82, 0.28, 0.29, 0.24),   # 1: rear tail resting on mulch
        (0.00, 0.40, -7.02, 0.42, 0.44, 0.35),   # 2: mid tail resting on mulch
        (0.00, 0.64, -6.22, 0.56, 0.58, 0.46),   # 3: tail rising off mulch
        (0.00, 1.08, -5.54, 0.72, 0.74, 0.58),   # 4: tail root / heavy rump
        (0.00, 1.66, -4.92, 0.94, 0.90, 0.80),   # 5: wide hips / lower pelvis
        (0.00, 2.26, -4.38, 1.00, 0.92, 0.94),   # 6: full plump pear-shaped teal belly!
        (0.00, 2.86, -3.96, 0.90, 0.85, 0.88),   # 7: mid-upper teal belly
        (0.00, 3.42, -3.58, 0.74, 0.74, 0.72),   # 8: shoulders / upper chest (teal stops below arms!)
        (0.00, 3.96, -3.26, 0.62, 0.64, 0.60),   # 9: lower sturdy curved neck
        (0.00, 4.46, -2.92, 0.57, 0.60, 0.56),   # 10: mid S-curved neck
        (0.00, 4.94, -2.62, 0.56, 0.60, 0.55),   # 11: upper neck curving gracefully up under jaw
        (0.00, 5.34, -2.44, 0.60, 0.64, 0.58),   # 12: neck-skull junction
    ]

    # Ring has 16 segments: i=0 top/dorsal (+U), i=4 right (+R), i=8 bottom/ventral (-U), i=12 left (-R)
    # Teal belly patch sits strictly on front-ventral torso (stations 5..7, neatly below the shoulders/arms!)
    # Smooth vermilion front throat sits on front-ventral upper chest & neck (stations 8..12, edges 5..10!)
    # Everything else (tail, back, hips, flanks, nape of neck) is HEAVY ROUGH PEBBLED ORANGE-RED!
    def body_mat(k, i, segs):
        if 5 <= k <= 7 and i in (6, 7, 8, 9):
            return "skin_rex_belly"
        if k >= 8 and i in (5, 6, 7, 8, 9, 10):
            return "skin_rex_smooth"
        return "skin_rex_rough"

    def body_rect(k, i, segs):
        if body_mat(k, i, segs) == "skin_rex_smooth":
            return reg_sm["plain"]
        return None

    add_curved_tube(bucket, spine, segs=16, mat_fn=body_mat, cap_start=True, cap_end=True, uv_rect_fn=body_rect)

    # =========================================================================
    # 2. BIG CHUNKY 1-TO-1 HEAD (MATCHING PHOTOS rex-ref-angle2 & rex-ref-angle4!)
    # =========================================================================
    # Sized to exact ~23% of total Rex height (~1.42 m tall x 2.02 m long) matching real photos!
    hx, hy, hz = 0.0, 5.34, -2.42
    pitch = math.radians(3.0)
    cp, sp = math.cos(pitch), math.sin(pitch)

    def hp(lx, ly, lz):
        ry = ly * cp + lz * sp
        rz = -ly * sp + lz * cp
        return (hx + lx, hy + ry, hz + rz)

    # 2a. Upper Head / High Domed Rough Crown / Blunt Smooth Snout (lz = -0.68 .. +1.28)
    upper_stations = [
        # (lz, y_bot, y_top, rx_top, rx_bot)
        (-0.68, -0.42, 0.66, 0.54, 0.52),   # 0: rear occiput (rough hood)
        (-0.26, -0.34, 0.90, 0.66, 0.65),   # 1: high domed back crown (rough hood)
        ( 0.18, -0.18, 0.92, 0.65, 0.66),   # 2: crown peak (rough hood sweeps right over top!)
        ( 0.58, -0.10, 0.80, 0.59, 0.62),   # 3: eye socket plane (top rough brow edge, smooth cheeks!)
        ( 0.98, -0.02, 0.64, 0.51, 0.56),   # 4: mid blunt snout (smooth orange)
        ( 1.26,  0.04, 0.49, 0.41, 0.46),   # 5: blunt flat-rounded nose front (smooth + proud 'OO' nostrils!)
    ]

    def upper_ring(lz, yb, yt, rxt, rxb):
        ym = yb + (yt - yb) * 0.44
        pts = [
            ( 0.00,       yt),
            ( rxt * 0.56, yt * 0.94 + yb * 0.06),
            ( rxt * 0.94, ym + (yt - ym) * 0.50),
            ( rxb * 1.00, ym),
            ( rxb * 0.94, yb),
            ( rxb * 0.48, yb + 0.03),
            (-rxb * 0.48, yb + 0.03),
            (-rxb * 0.94, yb),
            (-rxb * 1.00, ym),
            (-rxt * 0.94, ym + (yt - ym) * 0.50),
            (-rxt * 0.56, yt * 0.94 + yb * 0.06),
        ]
        return [hp(x, y, lz) for (x, y) in pts]

    u_rings = [upper_ring(*st) for st in upper_stations]
    un = len(u_rings[0])
    for k in range(len(u_rings) - 1):
        r0, r1 = u_rings[k], u_rings[k + 1]
        for i in range(un):
            j = (i + 1) % un
            if k >= 2 and i in (4, 5, 6):
                bucket.add_quad(r0[i], r1[i], r1[j], r0[j], "bone_toes_teeth", uv_rect_art=reg_btt["mouth"], fit_rect=True)
            elif (k == 0) or (k == 1 and i in (0, 1, 2, 8, 9, 10)) or (k == 2 and i in (0, 1, 2, 8, 9, 10)) or (k == 3 and i in (0, 10)):
                # EXACT ROUGH PEBBLED HOOD sweeping forward over the domed crown just like rex-ref-angle4!
                bucket.add_quad(r0[i], r1[i], r1[j], r0[j], "skin_rex_rough")
            else:
                bucket.add_quad(r0[i], r1[i], r1[j], r0[j], "skin_rex_smooth", uv_rect_art=reg_sm["plain"])

    # Back skull cap (rough pebbled)
    c_back = hp(0.0, 0.16, upper_stations[0][0] - 0.14)
    for i in range(un):
        j = (i + 1) % un
        bucket.add_tri(c_back, u_rings[0][i], u_rings[0][j], "skin_rex_rough")

    # Blunt flat-rounded front nose cap (flush at lz = 1.262 so curved nostril face hugs it tightly with zero overhang!)
    c_nose = hp(0.0, 0.24, upper_stations[-1][0] + 0.002)
    for i in range(un):
        j = (i + 1) % un
        bucket.add_tri(c_nose, u_rings[-1][j], u_rings[-1][i], "skin_rex_smooth", uv_rect_art=reg_sm["plain"])

    # TIGHT CURVED 2-PANEL FRONT NOSTRIL FACE hugging the blunt snout cleanly (lz = 1.275..1.308, zero overhang!)
    # Matches rex-ref-angle2.png: BOTH bold dark-outlined 'OO' nostrils cleanly visible across the blunt nose!
    nr = reg_sm["snout"]
    tw_s, th_s = bucket.mat_sizes["skin_rex_smooth"]
    aw_s, ah_s = tw_s / 2.0, th_s / 2.0
    nx0, ny0, nw, nh = nr

    def snout_uv(fu, fv):
        return ((nx0 + fu * nw) / aw_s, (ny0 + fv * nh) / ah_s)

    # Hugs u_rings[-1] (lz=1.26) & c_nose (lz=1.262) cleanly proud with zero edge overhang from 3/4 angles!
    bl_L = hp(-0.34, 0.12, 1.278)
    bl_M = hp( 0.00, 0.12, 1.312)
    bl_R = hp( 0.34, 0.12, 1.278)
    tl_L = hp(-0.30, 0.44, 1.272)
    tl_M = hp( 0.00, 0.44, 1.304)
    tl_R = hp( 0.30, 0.44, 1.272)
    bucket.add_quad_uv(bl_L, bl_M, tl_M, tl_L, snout_uv(0.02, 0.96), snout_uv(0.50, 0.96), snout_uv(0.50, 0.04), snout_uv(0.02, 0.04), "skin_rex_smooth")
    bucket.add_quad_uv(bl_M, bl_R, tl_R, tl_M, snout_uv(0.50, 0.96), snout_uv(0.98, 0.96), snout_uv(0.98, 0.04), snout_uv(0.50, 0.04), "skin_rex_smooth")

    # 2b. Thick Heavy Squared Smooth Lower Jaw / Chin (exact stepped silhouette of rex-ref-angle2 & rex-ref-angle4!)
    lower_stations = [
        # (lz, y_bot, y_top, rx_top, rx_bot)
        (-0.34, -0.64, -0.15, 0.59, 0.53),   # 0: jaw root stepping cleanly into smooth throat
        ( 0.18, -0.58, -0.16, 0.58, 0.52),   # 1: rear cheek / sharp back corner of mouth slit
        ( 0.60, -0.50, -0.18, 0.53, 0.47),   # 2: mid lower jaw (narrow dark wedge grin above!)
        ( 0.96, -0.42, -0.20, 0.44, 0.39),   # 3: front lower jaw
        ( 1.16, -0.34, -0.20, 0.33, 0.28),   # 4: blunt chin tip tucked cleanly under overhanging upper snout!
    ]

    def lower_ring(lz, yb, yt, rxt, rxb):
        ym = yb + (yt - yb) * 0.50
        pts = [
            ( 0.00,       yt - 0.02),
            ( rxt * 0.58, yt - 0.01),
            ( rxt * 0.94, yt),
            ( rxt * 1.00, ym),
            ( rxb * 0.66, yb + 0.04),
            ( 0.00,       yb),
            (-rxb * 0.66, yb + 0.04),
            (-rxt * 1.00, ym),
            (-rxt * 0.94, yt),
            (-rxt * 0.58, yt - 0.01),
        ]
        return [hp(x, y, lz) for (x, y) in pts]

    l_rings = [lower_ring(*st) for st in lower_stations]
    ln = len(l_rings[0])
    for k in range(len(l_rings) - 1):
        r0, r1 = l_rings[k], l_rings[k + 1]
        for i in range(ln):
            j = (i + 1) % ln
            if i in (0, 1, 8, 9):
                bucket.add_quad(r0[i], r1[i], r1[j], r0[j], "bone_toes_teeth", uv_rect_art=reg_btt["mouth"], fit_rect=True)
            else:
                bucket.add_quad(r0[i], r1[i], r1[j], r0[j], "skin_rex_smooth", uv_rect_art=reg_sm["plain"])

    # Dark throat back wall inside the narrow triangular wedge mouth
    bucket.add_quad(
        hp(-0.44, -0.20, 0.24),
        hp( 0.44, -0.20, 0.24),
        hp( 0.44,  0.08, 0.24),
        hp(-0.44,  0.08, 0.24),
        "bone_toes_teeth", uv_rect_art=reg_btt["mouth"], fit_rect=True,
    )
    cf_l = hp(0.0, -0.27, lower_stations[-1][0] + 0.06)
    for i in range(ln):
        j = (i + 1) % ln
        bucket.add_tri(cf_l, l_rings[-1][j], l_rings[-1][i], "skin_rex_smooth", uv_rect_art=reg_sm["plain"])

    # 2c. Dense Uniform Sawtooth Row of Crisp White Upper Teeth + Neat Shorter Lower Teeth (rex-ref-angle2.png!)
    upper_teeth_pts = [
        (0.00,  0.04, 1.22, 0.088),
        (0.10,  0.03, 1.21, 0.086), (-0.10,  0.03, 1.21, 0.086),
        (0.19,  0.02, 1.18, 0.084), (-0.19,  0.02, 1.18, 0.084),
        (0.28,  0.01, 1.13, 0.082), (-0.28,  0.01, 1.13, 0.082),
        (0.37, -0.01, 1.06, 0.078), (-0.37, -0.01, 1.06, 0.078),
        (0.44, -0.02, 0.96, 0.074), (-0.44, -0.02, 0.96, 0.074),
        (0.49, -0.04, 0.85, 0.068), (-0.49, -0.04, 0.85, 0.068),
        (0.53, -0.06, 0.74, 0.062), (-0.53, -0.06, 0.74, 0.062),
        (0.56, -0.08, 0.62, 0.056), (-0.56, -0.08, 0.62, 0.056),
        (0.57, -0.10, 0.50, 0.048), (-0.57, -0.10, 0.50, 0.048),
    ]
    for lx, ly, lz, drop in upper_teeth_pts:
        add_tooth(bucket, hp(lx, ly, lz), hp(lx * 0.98, ly - drop, lz + 0.004),
                  0.038, 0.024, "bone_toes_teeth", reg_btt["teeth"])

    lower_teeth_pts = [
        (0.05, -0.20, 1.12, 0.050), (-0.05, -0.20, 1.12, 0.050),
        (0.15, -0.20, 1.09, 0.048), (-0.15, -0.20, 1.09, 0.048),
        (0.25, -0.19, 1.03, 0.046), (-0.25, -0.19, 1.03, 0.046),
        (0.35, -0.19, 0.94, 0.044), (-0.35, -0.19, 0.94, 0.044),
        (0.43, -0.18, 0.84, 0.040), (-0.43, -0.18, 0.84, 0.040),
        (0.49, -0.18, 0.72, 0.036), (-0.49, -0.18, 0.72, 0.036),
    ]
    for lx, ly, lz, rise in lower_teeth_pts:
        add_tooth(bucket, hp(lx, ly, lz), hp(lx * 0.98, ly + rise, lz),
                  0.028, 0.018, "bone_toes_teeth", reg_btt["teeth"])

    # 2d. Deep-Set Dark Eye Sockets + Small Glowing Ruby-Red Lightbulb Eyes (rex-ref-angle2 & rex-ref-front!)
    for side in (-1.0, 1.0):
        # Distinct deep dark shadowed socket pocket nestled high on the smooth upper cheek!
        sock_c = hp(side * 0.53, 0.39, 0.60)
        add_ellipsoid(
            bucket, sock_c, 0.105, 0.145, 0.155, "glow_rex_eyes", segs=8, stacks=4,
            uv_rect_art=reg_glow["socket"], fit_rect=True,
        )
        # Small glowing ruby-red lightbulb nestled snugly inside the recessed dark hollow!
        eye_c = hp(side * 0.58, 0.38, 0.65)
        look_d = (side * 0.76, 0.00, 0.65)
        add_mapped_eye_bulb(bucket, eye_c, 0.072, "glow_rex_eyes", reg_glow["eye"], look_d, segs=10, stacks=6)

    # =========================================================================
    # 3. SMOOTH ORANGE CYLINDRICAL ARMS + SCULPTED 10-FOOT WHITE FEMUR BONE CANE!
    # =========================================================================
    # LEFT ARM (+X): chunky smooth orange tube angled ~42 deg down-forward onto the plump bone top (rex-ref-angle4!)
    left_arm = [
        (0.56, 3.74, -3.48, 0.24, 0.25, 0.25),   # full sturdy shoulder
        (0.58, 3.48, -3.14, 0.195, 0.20, 0.20),  # upper arm
        (0.58, 3.22, -2.86, 0.175, 0.175, 0.175),# forearm
        (0.58, 3.04, -2.68, 0.175, 0.145, 0.145),# hand gripping cleanly right OVER the bone top knob!
    ]
    add_curved_tube(
        bucket, left_arm, segs=12,
        mat_fn=lambda k, i, s: "skin_rex_smooth",
        uv_rect_fn=lambda k, i, s: reg_sm["plain"],
    )
    # 3 plump red stubby fingers curling over the front-left lip of the white femur bone knob!
    for fx, fz in ((0.46, -2.56), (0.58, -2.51), (0.70, -2.57)):
        add_ellipsoid(bucket, (fx, 2.94, fz), 0.064, 0.105, 0.064, "skin_rex_smooth",
                      segs=6, stacks=4, uv_rect_art=reg_sm["plain"])

    # RIGHT ARM (-X): angled down-forward beside/just forward of the bone top (matches rex-ref-front & rex-ref-angle4!)
    right_arm = [
        (-0.56, 3.74, -3.48, 0.23, 0.24, 0.24),  # shoulder
        (-0.58, 3.52, -3.02, 0.18, 0.185, 0.185),# upper arm
        (-0.58, 3.28, -2.58, 0.16, 0.165, 0.165),# forearm reaching forward of bone top
        (-0.56, 3.08, -2.30, 0.15, 0.135, 0.135),# downward-curled rounded paw
    ]
    add_curved_tube(
        bucket, right_arm, segs=12,
        mat_fn=lambda k, i, s: "skin_rex_smooth",
        uv_rect_fn=lambda k, i, s: reg_sm["plain"],
    )
    for fx, fz in ((-0.65, -2.22), (-0.56, -2.18), (-0.47, -2.22)):
        add_ellipsoid(bucket, (fx, 2.98, fz), 0.056, 0.092, 0.058, "skin_rex_smooth",
                      segs=6, stacks=4, uv_rect_art=reg_sm["plain"])

    # GIANT VERTICAL WHITE FEMUR BONE CANE (X = +0.58, Z = -2.68, Y = 0.0 -> 3.04 m = 10.0 ft tall!)
    # Sculpted hourglass cartoon femur profile matching rex-ref-front & rex-ref-angle3!
    bx, bz = 0.58, -2.68
    bone_shaft = [
        (bx, 0.06, bz, 0.28, 0.28, 0.28),
        (bx, 0.42, bz, 0.175, 0.175, 0.175),
        (bx, 0.96, bz, 0.130, 0.130, 0.130),
        (bx, 1.56, bz, 0.122, 0.122, 0.122),
        (bx, 2.22, bz, 0.145, 0.145, 0.145),
        (bx, 2.66, bz, 0.210, 0.210, 0.210),
        (bx, 2.94, bz, 0.275, 0.275, 0.275),
    ]
    add_curved_tube(
        bucket, bone_shaft, segs=14,
        mat_fn=lambda k, i, s: "bone_toes_teeth",
        uv_rect_fn=lambda k, i, s: reg_btt["bone"],
    )
    # Big plump double-lobed white condyle knobs at TOP right under Rex's left hand!
    add_ellipsoid(bucket, (bx - 0.15, 2.90, bz + 0.11), 0.24, 0.26, 0.24, "bone_toes_teeth",
                  segs=10, stacks=6, uv_rect_art=reg_btt["bone"])
    add_ellipsoid(bucket, (bx + 0.15, 2.86, bz - 0.10), 0.24, 0.26, 0.24, "bone_toes_teeth",
                  segs=10, stacks=6, uv_rect_art=reg_btt["bone"])
    # Wide flaring double-lobed white condyle base at BOTTOM planted solidly in the mulch!
    add_ellipsoid(bucket, (bx - 0.17, 0.19, bz + 0.11), 0.26, 0.23, 0.26, "bone_toes_teeth",
                  segs=10, stacks=6, uv_rect_art=reg_btt["bone"])
    add_ellipsoid(bucket, (bx + 0.17, 0.19, bz - 0.10), 0.26, 0.23, 0.26, "bone_toes_teeth",
                  segs=10, stacks=6, uv_rect_art=reg_btt["bone"])

    # =========================================================================
    # 4. CHUNKY MEATY ROUGH-STUCCO HIND LEGS & BIG PALE CREAM-YELLOW TOENAILS!
    # =========================================================================
    # Matches rex-photo-1, rex-ref-front, rex-ref-angle3: massive thighs + 3 big cream dome toenails!
    for side in (-1.0, 1.0):
        leg = [
            (side * 0.66, 2.04, -4.54, 0.54, 0.62, 0.60),  # massive rough-stucco upper thigh
            (side * 0.80, 1.42, -4.16, 0.48, 0.52, 0.50),  # strong forward rounded knee bulge
            (side * 0.78, 0.76, -4.28, 0.42, 0.44, 0.42),  # thick sturdy calf/shin
            (side * 0.76, 0.24, -4.16, 0.44, 0.42, 0.40),  # broad solid ankle
        ]
        add_curved_tube(
            bucket, leg, segs=12,
            mat_fn=lambda k, i, s: "skin_rex_rough",
            cap_start=True, cap_end=False,
        )
        # Wide rounded orange-red foot pad planted solidly on dark mulch (Y = 0.0 .. 0.38)
        add_ellipsoid(bucket, (side * 0.76, 0.18, -3.96), 0.50, 0.24, 0.60, "skin_rex_rough", segs=10, stacks=5)

        # 3 BIG, broad, rounded PALE CREAM-YELLOW dome toenails prominently visible through the black fence!
        for toe_dx, toe_dz, toe_r in ((-0.26, 0.49, 0.17), (0.0, 0.56, 0.185), (0.26, 0.49, 0.17)):
            tc = (side * 0.76 + toe_dx, 0.14, -3.96 + toe_dz)
            add_ellipsoid(
                bucket, tc, toe_r * 0.96, 0.155, toe_r * 1.15, "bone_toes_teeth",
                segs=8, stacks=5, uv_rect_art=reg_btt["toes"], fit_rect=True,
            )

    # Collision boxes for Rex statue + vertical bone cane + ground tail
    coll.add("rex_legs_and_bone", -1.30, 0.0, -5.20, 1.30, 3.10, -2.40)
    coll.add("rex_tail_ground", -0.62, 0.0, -8.70, 0.62, 1.20, -5.20)


def add_lot_dressing(lot_bucket, coll, regs):
    """
    Actual 10150 Beach Boulevard roadside site matching rex-photo-1 & rex-ref-angle3:
      - Dark pine-bark mulch bed under Rex & his bone (X: -2.35..+2.35, Z: -2.05..-9.15)
      - Tall 1.80 m (5.9 ft) black wrought-iron picket fence encircling the mulch bed!
      - Modest angled bronze historical plaque on a thin black post inside the left fence
        (matching the exact plaque visible right next to Rex's left thigh in rex-ref-angle3!)
      - Florida green grass apron outside the fence (X: -3.85..+3.85, Z: -1.10..-10.20)
      - Precast concrete roadside street curb + Beach Blvd asphalt border (total lot 9.6 m x 11.4 m)
      - Two low ground uplights tucked in front corners of the mulch bed
    """
    reg_fence = regs["lot_fence"]
    reg_gnd = regs["lot_ground"]
    reg_glow = regs["glow_rex_eyes"]

    # 1. Surrounding Beach Blvd asphalt pad (9.6 m wide x 11.4 m deep, origin at front center Z=0)
    lot_bucket.add_box(-4.80, -0.06, -11.40, 4.80, -0.02, 0.0, "ground_asphalt", faces=("+y", "+z", "-z", "-x", "+x"))

    # 2. Precast concrete roadside curb & front sidewalk strip framing the grass island
    # Front concrete street curb (Z: -1.20..-0.50) + outer island curb trim
    lot_bucket.add_box(-4.15, -0.02, -10.50, 4.15, 0.04, -0.50, "ground_pavement", faces=("+y", "+z", "-z", "-x", "+x"))

    # 3. Florida grass apron around the fence (X: -3.85..+3.85, Z: -1.10..-10.20)
    lot_bucket.add_box(-3.85, 0.04, -10.20, 3.85, 0.06, -1.10, "lot_ground", faces=("+y",), uv_rect_art=reg_gnd["grass"])

    # 4. Dark pine-bark mulch bed inside the wrought-iron fence (slightly raised Y=0.06..0.09)
    fx0, fx1 = -2.35, 2.35
    fz0, fz1 = -9.15, -2.05
    lot_bucket.add_box(fx0, 0.04, fz0, fx1, 0.07, fz1, "lot_ground", faces=("+y",), uv_rect_art=reg_gnd["mulch"])

    # 5. TALL 1.80 m BLACK WROUGHT-IRON PICKET FENCE ENCLOSURE (matching real Beach Blvd photos!)
    fh = 1.80
    fy0 = 0.06
    fy1 = fy0 + fh
    tile_w = 32 * ART_PX_M  # 2.48 m per fence texture repeat

    # 4 alpha-cutout wrought-iron picket perimeter walls (doubleSided: true)
    # Front wall (+Z)
    lot_bucket.add_tiled_strip((fx0, fy0, fz1), (fx1, fy0, fz1), (fx1, fy1, fz1), (fx0, fy1, fz1),
                               "lot_fence", reg_fence["panel"], tile_w)
    # Back wall (-Z)
    lot_bucket.add_tiled_strip((fx1, fy0, fz0), (fx0, fy0, fz0), (fx0, fy1, fz0), (fx1, fy1, fz0),
                               "lot_fence", reg_fence["panel"], tile_w)
    # Left wall (+X, near camera in hero left 3/4 view!)
    lot_bucket.add_tiled_strip((fx1, fy0, fz1), (fx1, fy0, fz0), (fx1, fy1, fz0), (fx1, fy1, fz1),
                               "lot_fence", reg_fence["panel"], tile_w)
    # Right wall (-X)
    lot_bucket.add_tiled_strip((fx0, fy0, fz0), (fx0, fy0, fz1), (fx0, fy1, fz1), (fx0, fy1, fz0),
                               "lot_fence", reg_fence["panel"], tile_w)

    # Solid 3D black iron corner & intermediate fence posts (gives real 3D crispness from every angle!)
    post_pts = [
        (fx0, fz1), (0.0, fz1), (fx1, fz1),
        (fx0, fz0), (0.0, fz0), (fx1, fz0),
        (fx0, -4.40), (fx0, -6.78),
        (fx1, -4.40), (fx1, -6.78),
    ]
    pr = 0.042
    for px, pz in post_pts:
        lot_bucket.add_box(px - pr, fy0, pz - pr, px + pr, fy1 + 0.05, pz + pr,
                           "lot_fence", uv_rect_art=reg_fence["iron"], fit_rect=True)

    # 6. Modest angled bronze plaque inside the left fence beside Rex's left thigh (exact spot in rex-ref-angle3!)
    qx, qz = 1.62, -5.55
    # Thin black iron pedestal post
    lot_bucket.add_box(qx - 0.035, fy0, qz - 0.035, qx + 0.035, 1.38, qz + 0.035,
                       "lot_fence", uv_rect_art=reg_fence["iron"], fit_rect=True)
    # Angled bronze plaque tilted ~35 deg up-and-out toward +X (viewer on Beach Blvd side!)
    pw_h = 0.24
    # Plaque tilted face facing +X and slightly +Y
    p0 = (qx + 0.06, 1.32, qz + pw_h)
    p1 = (qx + 0.06, 1.32, qz - pw_h)
    p2 = (qx - 0.14, 1.62, qz - pw_h)
    p3 = (qx - 0.14, 1.62, qz + pw_h)
    lot_bucket.add_quad(p0, p1, p2, p3, "lot_ground", uv_rect_art=reg_gnd["plaque"], fit_rect=True)
    # Solid dark iron backing directly behind the angled plaque
    lot_bucket.add_quad(
        (qx + 0.03, 1.30, qz - pw_h),
        (qx + 0.03, 1.30, qz + pw_h),
        (qx - 0.17, 1.60, qz + pw_h),
        (qx - 0.17, 1.60, qz - pw_h),
        "lot_fence", uv_rect_art=reg_fence["iron"], fit_rect=True,
    )

    # 7. Two small ground floodlight uplights tucked low inside front fence corners aimed up at Rex
    for ux in (-1.45, 1.55):
        uz = -2.42
        lot_bucket.add_box(ux - 0.12, fy0, uz - 0.12, ux + 0.12, fy0 + 0.16, uz + 0.12,
                           "glow_rex_eyes", uv_rect_art=reg_glow["dark"], fit_rect=True)
        lot_bucket.add_quad(
            (ux - 0.10, fy0 + 0.17, uz + 0.09),
            (ux + 0.10, fy0 + 0.17, uz + 0.09),
            (ux + 0.10, fy0 + 0.25, uz - 0.09),
            (ux - 0.10, fy0 + 0.25, uz - 0.09),
            "glow_rex_eyes", uv_rect_art=reg_glow["uplight"], fit_rect=True,
        )

    # Collision box for the wrought-iron fence enclosure
    coll.add("fence_enclosure", fx0 - 0.08, 0.0, fz0 - 0.08, fx1 + 0.08, fy1, fz1 + 0.08)


def export_glb(out_path, mat_defs, buckets, door_nodes, collision_boxes):
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

    mat_index = {}
    for m in mat_defs:
        bc_tex = add_image_tex(m["file"], m["name"])
        mat_obj = {
            "name": m["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": bc_tex},
                "metallicFactor": m["metal"],
                "roughnessFactor": m["rough"],
            },
        }
        if m.get("emissive_file"):
            em_tex = add_image_tex(m["emissive_file"], m["name"] + "_emissive")
            mat_obj["emissiveTexture"] = {"index": em_tex}
            mat_obj["emissiveFactor"] = m.get("emissive_factor", [1.0, 1.0, 1.0])
        if m.get("alpha_mode"):
            mat_obj["alphaMode"] = m["alpha_mode"]
            if m["alpha_mode"] == "MASK":
                mat_obj["alphaCutoff"] = 0.5
        if m.get("double_sided"):
            mat_obj["doubleSided"] = True
        mat_index[m["name"]] = len(materials)
        materials.append(mat_obj)

    meshes = []
    nodes = []

    def add_acc(data_bytes, comp_type, acc_type, count, target, mins=None, maxs=None):
        bv = append_bytes(data_bytes, target=target)
        acc = {
            "bufferView": bv,
            "componentType": comp_type,
            "count": count,
            "type": acc_type,
        }
        if mins is not None and maxs is not None:
            acc["min"] = mins
            acc["max"] = maxs
        idx = len(accessors)
        accessors.append(acc)
        return idx

    for bucket in buckets:
        prim_list = []
        for mat_name, p in bucket.prims.items():
            if not p["idx"]:
                continue
            pos = p["pos"]
            nrm = p["nrm"]
            uv = p["uv"]
            idx = p["idx"]
            pos_b = struct.pack(f"<{len(pos) * 3}f", *[c for v in pos for c in v])
            nrm_b = struct.pack(f"<{len(nrm) * 3}f", *[c for v in nrm for c in v])
            uv_b = struct.pack(f"<{len(uv) * 2}f", *[c for v in uv for c in v])
            use_u32 = len(pos) > 65535
            idx_b = struct.pack(f"<{len(idx)}{'I' if use_u32 else 'H'}", *idx)
            mins = [min(v[k] for v in pos) for k in range(3)]
            maxs = [max(v[k] for v in pos) for k in range(3)]
            p_acc = add_acc(pos_b, 5126, "VEC3", len(pos), 34962, mins, maxs)
            n_acc = add_acc(nrm_b, 5126, "VEC3", len(nrm), 34962)
            u_acc = add_acc(uv_b, 5126, "VEC2", len(uv), 34962)
            i_acc = add_acc(idx_b, 5125 if use_u32 else 5123, "SCALAR", len(idx), 34963)
            prim_list.append({
                "attributes": {"POSITION": p_acc, "NORMAL": n_acc, "TEXCOORD_0": u_acc},
                "indices": i_acc,
                "material": mat_index[mat_name],
            })
        m_idx = len(meshes)
        meshes.append({"name": bucket.name, "primitives": prim_list})
        nodes.append({"name": bucket.name, "mesh": m_idx})

    # Collision mesh node (invisible collision_only box geometry)
    c_pos, c_idx = [], []
    for cb in collision_boxes:
        x0, y0, z0 = cb["min"]
        x1, y1, z1 = cb["max"]
        b = len(c_pos)
        c_pos.extend([
            (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
            (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
        ])
        quads = [
            (4, 5, 6, 7), (1, 0, 3, 2), (5, 1, 2, 6),
            (0, 4, 7, 3), (7, 6, 2, 3), (0, 1, 5, 4),
        ]
        for q0, q1, q2, q3 in quads:
            c_idx.extend([b + q0, b + q1, b + q2, b + q0, b + q2, b + q3])
    cp_b = struct.pack(f"<{len(c_pos) * 3}f", *[c for v in c_pos for c in v])
    cn_b = struct.pack(f"<{len(c_pos) * 3}f", *([0.0, 1.0, 0.0] * len(c_pos)))
    ci_b = struct.pack(f"<{len(c_idx)}H", *c_idx)
    c_mins = [min(v[k] for v in c_pos) for k in range(3)]
    c_maxs = [max(v[k] for v in c_pos) for k in range(3)]
    cp_acc = add_acc(cp_b, 5126, "VEC3", len(c_pos), 34962, c_mins, c_maxs)
    cn_acc = add_acc(cn_b, 5126, "VEC3", len(c_pos), 34962)
    ci_acc = add_acc(ci_b, 5123, "SCALAR", len(c_idx), 34963)
    cm_idx = len(meshes)
    meshes.append({"name": "collision", "primitives": [{"attributes": {"POSITION": cp_acc, "NORMAL": cn_acc}, "indices": ci_acc}]})
    nodes.append({"name": "collision", "mesh": cm_idx, "extras": {"collision_only": True}})

    for d_name, d_pos in door_nodes.items():
        nodes.append({"name": d_name, "translation": d_pos})

    while len(bin_buf) % 4 != 0:
        bin_buf.append(0)

    gltf = {
        "asset": {"version": "2.0", "generator": "Timerpg Beach Blvd Rex 1-to-1 Builder"},
        "scene": 0,
        "scenes": [{"name": "beach_blvd_rex", "nodes": list(range(len(nodes)))}],
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
    header = struct.pack("<4sII", b"glTF", 2, total_len)
    j_chunk = struct.pack("<I4s", len(json_bytes), b"JSON") + json_bytes
    b_chunk = struct.pack("<I4s", len(bin_buf), b"BIN\x00") + bytes(bin_buf)
    out_path.write_bytes(header + j_chunk + b_chunk)
    return len(c_idx) // 3


def main():
    mat_defs = [
        {"name": "skin_rex_rough",  "file": "skin_rex_rough.png",  "metal": 0.0, "rough": 0.88},
        {"name": "skin_rex_smooth", "file": "skin_rex_smooth.png", "metal": 0.0, "rough": 0.62},
        {"name": "skin_rex_belly",  "file": "skin_rex_belly.png",  "metal": 0.0, "rough": 0.66},
        {"name": "bone_toes_teeth", "file": "bone_toes_teeth.png", "metal": 0.0, "rough": 0.68},
        {
            "name": "glow_rex_eyes",
            "file": "glow_base.png",
            "emissive_file": "glow_mask.png",
            "emissive_factor": [1.0, 1.0, 1.0],
            "metal": 0.05,
            "rough": 0.45,
        },
        {"name": "lot_fence",       "file": "lot_fence.png",       "metal": 0.45, "rough": 0.55, "alpha_mode": "MASK", "double_sided": True},
        {"name": "lot_ground",      "file": "lot_ground.png",      "metal": 0.0,  "rough": 0.94},
        {"name": "ground_pavement", "file": "pavement.png",        "metal": 0.0,  "rough": 0.92},
        {"name": "ground_asphalt",  "file": "asphalt_worn.png",    "metal": 0.0,  "rough": 0.95},
    ]
    mat_sizes = {m["name"]: read_png_size(TEX_DIR / m["file"]) for m in mat_defs}

    statue_bucket = MeshBucket("rex_statue", mat_sizes)
    lot_bucket = MeshBucket("lot", mat_sizes)
    coll = CollisionCollector()

    regs = REGIONS["regions"]
    add_rex_statue(statue_bucket, coll, regs)
    add_lot_dressing(lot_bucket, coll, regs)

    # Measure exact statue and lot bounds
    s_pts = [p for pr in statue_bucket.prims.values() for p in pr["pos"]]
    l_pts = [p for pr in lot_bucket.prims.values() for p in pr["pos"]]
    all_pts = s_pts + l_pts
    sx0, sx1 = min(p[0] for p in s_pts), max(p[0] for p in s_pts)
    sy0, sy1 = min(p[1] for p in s_pts), max(p[1] for p in s_pts)
    sz0, sz1 = min(p[2] for p in s_pts), max(p[2] for p in s_pts)
    ax0, ax1 = min(p[0] for p in all_pts), max(p[0] for p in all_pts)
    az0, az1 = min(p[2] for p in all_pts), max(p[2] for p in all_pts)

    doors = {"door_main": [0.0, 0.0, -1.25]}
    glb_path = ROOT / "beach-blvd-rex.glb"
    coll_tris = export_glb(glb_path, mat_defs, [statue_bucket, lot_bucket], doors, coll.boxes)

    rex_h = sy1
    rex_len = sz1 - sz0
    rex_w = sx1 - sx0
    info = {
        "name": "Rex — Beach Blvd Dinosaur",
        "kind": "landmark_glb_beach_blvd_rex",
        "role": "monument",
        "inspiration": "10150 Beach Blvd, Jacksonville, FL — 1964 former Goony Golf mascot (2007 restoration: vermilion orange-red rough back / smooth face, teal belly, 10 ft white bone cane, black wrought-iron fence)",
        "era": {"from": 1964, "to": 1972, "built": 1964},
        "glb": glb_path.name,
        "file": glb_path.name,
        "texel_size_m": TEXEL_M,
        "art_pixel_m": ART_PX_M,
        "texel_m": TEXEL_M,
        "art_px_m": ART_PX_M,
        "statue_dimensions_m": {
            "height_from_ground": round(rex_h, 3),
            "length_nose_to_tail": round(rex_len, 3),
            "width_x": round(rex_w, 3),
            "height_feet": round(rex_h * 3.28084, 1),
            "length_feet": round(rex_len * 3.28084, 1),
        },
        "footprint_m": {
            "lot_width": round(ax1 - ax0, 3),
            "lot_depth": round(az1 - az0, 3),
            "building_width": 4.86,
            "building_depth": 7.26,
            "max_height": round(rex_h, 3),
            "width_x": round(ax1 - ax0, 3),
            "depth_z": round(az1 - az0, 3),
            "height_y": round(rex_h, 3),
        },
        "triangles": {
            "visible": statue_bucket.tri_count() + lot_bucket.tri_count(),
            "rex_statue": statue_bucket.tri_count(),
            "lot": lot_bucket.tri_count(),
            "visible_total": statue_bucket.tri_count() + lot_bucket.tri_count(),
            "collision": coll_tris,
            "by_node": {
                "rex_statue": statue_bucket.tri_count(),
                "lot": lot_bucket.tri_count(),
                "collision": coll_tris,
            },
        },
        "materials": [m["name"] for m in mat_defs],
        "swatches_used": REGIONS["swatches"],
        "doors": {
            "door_main": {
                "translation_gltf": doors["door_main"],
                "outward": [0, 0, 1],
            },
        },
        "collision_boxes": [{"source": b["source"], "center": b["center"], "size": b["size"]} for b in coll.boxes],
        "collision": {
            "coordinate_note": "Blender Z-up convention (x, y_blender=-z_gltf, z_blender=y_gltf)",
            "boxes_blender_z_up": [
                {
                    "source": b["source"],
                    "center": [b["center"][0], round(-b["center"][2], 3), b["center"][1]],
                    "size": [b["size"][0], b["size"][2], b["size"][1]],
                }
                for b in coll.boxes
            ],
        },
    }
    (ROOT / "model-info.json").write_text(json.dumps(info, indent=2) + "\n")
    print(f"Built {glb_path.name} ({glb_path.stat().st_size // 1024} KiB)")
    print(f"  Rex height: {rex_h:.3f} m ({rex_h * 3.28084:.1f} ft)")
    print(f"  Rex length: {rex_len:.3f} m ({rex_len * 3.28084:.1f} ft)")
    print(f"  Triangles:  statue={statue_bucket.tri_count()}, lot={lot_bucket.tri_count()}, total={info['triangles']['visible_total']}")


if __name__ == "__main__":
    main()
