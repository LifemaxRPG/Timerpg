#!/usr/bin/env python3
"""
Modular builders for THE GREAT MERIDIAN MONUMENT — Revision 2:
  - 8 m Colossal Art Deco Guardian Statues (DEFENSE, LEARNING, INDUSTRY, HARVEST) on projecting corner ledges
  - Reusable instanced plaza & deck props (lamps, braziers, flagpoles w/ 1926 bunting, bronze benches, planters,
    trash bins, drinking fountain, bike rack, 1 m interpretive panels, telescopes, trees, floodlight fixtures)
  - East Hall 1:35 scale monument model on museum plinth
  - Middle LOD (`great-meridian-mid.glb`, 4,000-6,000 tris) & Far LOD (`great-meridian-far.glb`, < 400 tris)
"""
import math


def _rot_y(pt, cy, sy):
    x, y, z = pt
    return (x * cy + z * sy, y, -x * sy + z * cy)


def _xform_box(bucket, cx, y0, cz, yaw_rad, x0, ly0, z0, x1, ly1, z1, mat_name,
               region_name=None, fit_region=False, faces=None):
    """Adds a local axis-aligned box rotated by yaw_rad around Y and translated to (cx, y0, cz) with edge_aligned UVs."""
    if x1 <= x0 + 1e-5 or ly1 <= ly0 + 1e-5 or z1 <= z0 + 1e-5:
        return
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(lx, ly, lz):
        rx, ry, rz = _rot_y((lx, ly, lz), cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    # 8 corners: 0..3 bottom, 4..7 top
    c000 = wp(x0, ly0, z0)
    c100 = wp(x1, ly0, z0)
    c101 = wp(x1, ly0, z1)
    c001 = wp(x0, ly0, z1)
    c010 = wp(x0, ly1, z0)
    c110 = wp(x1, ly1, z0)
    c111 = wp(x1, ly1, z1)
    c011 = wp(x0, ly1, z1)

    if faces is None:
        faces = ("+z", "-z", "+x", "-x", "+y", "-y")
    ea = True  # Always edge-aligned so diagonal rotated faces stay strictly inside region bounds!
    if "+z" in faces:
        bucket.add_planar_quad_art(c001, c101, c111, c011, mat_name, region_name, fit_region, edge_aligned=ea)
    if "-z" in faces:
        bucket.add_planar_quad_art(c100, c000, c010, c110, mat_name, region_name, fit_region, edge_aligned=ea)
    if "+x" in faces:
        bucket.add_planar_quad_art(c101, c100, c110, c111, mat_name, region_name, fit_region, edge_aligned=ea)
    if "-x" in faces:
        bucket.add_planar_quad_art(c000, c001, c011, c010, mat_name, region_name, fit_region, edge_aligned=ea)
    if "+y" in faces:
        bucket.add_planar_quad_art(c011, c111, c110, c010, mat_name, region_name, fit_region, edge_aligned=ea)
    if "-y" in faces:
        bucket.add_planar_quad_art(c000, c100, c101, c001, mat_name, region_name, fit_region, edge_aligned=ea)


def _xform_frustum_box(bucket, cx, y0, cz, yaw_rad, hx0, hz0, ly0, hx1, hz1, ly1, mat_name, region_name=None):
    """Tapered 4-sided carved Art Deco stone prism (bottom half-extents hx0,hz0 -> top hx1,hz1) rotated by yaw_rad."""
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(lx, ly, lz):
        rx, ry, rz = _rot_y((lx, ly, lz), cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    b0, b1, b2, b3 = wp(-hx0, ly0, hz0), wp(hx0, ly0, hz0), wp(hx0, ly0, -hz0), wp(-hx0, ly0, -hz0)
    t0, t1, t2, t3 = wp(-hx1, ly1, hz1), wp(hx1, ly1, hz1), wp(hx1, ly1, -hz1), wp(-hx1, ly1, -hz1)
    for qa, qb, qc, qd in ((b0, b1, t1, t0), (b1, b2, t2, t1), (b2, b3, t3, t2), (b3, b0, t0, t3)):
        bucket.add_planar_quad_art(qa, qb, qc, qd, mat_name, region_name, edge_aligned=True)
    bucket.add_planar_quad_art(t0, t1, t2, t3, mat_name, region_name, edge_aligned=True)
    bucket.add_planar_quad_art(b3, b2, b1, b0, mat_name, region_name, edge_aligned=True)


def _xform_panel_front(bucket, cx, y0, cz, yaw_rad, x0, ly0, x1, ly1, lz, mat_name, region_name, fit_region=True):
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(lx, ly, zval):
        rx, ry, rz = _rot_y((lx, ly, zval), cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    bucket.add_planar_quad_art(
        wp(x0, ly0, lz), wp(x1, ly0, lz), wp(x1, ly1, lz), wp(x0, ly1, lz),
        mat_name, region_name, fit_region=fit_region, edge_aligned=True
    )


def _xform_beam(bucket, cx, y0, cz, yaw_rad, p0, p1, hw, hh, mat_name, region_name=None, caps=True):
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(pt):
        rx, ry, rz = _rot_y(pt, cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    bucket.add_beam_art(wp(p0), wp(p1), hw, hh, mat_name, region_name=region_name, caps=caps)


def _xform_ring(bucket, cx, y0, cz, yaw_rad, center_l, u_l, v_l, rad, hrad, hthick, segs, mat_name,
                region_name=None, skip_quadrant=False):
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(pt):
        rx, ry, rz = _rot_y(pt, cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    def wd(vec):
        return _rot_y(vec, cy, sy)

    if not skip_quadrant:
        bucket.add_ring_3d(wp(center_l), wd(u_l), wd(v_l), rad, hrad, hthick, segs, mat_name, region_name=region_name)
    else:
        # Chipped gear wheel (1976 Industry damage: missing upper-right quadrant!)
        u = wd(u_l)
        v = wd(v_l)
        n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        nl = math.sqrt(n[0] ** 2 + n[1] ** 2 + n[2] ** 2) or 1.0
        n = (n[0] / nl, n[1] / nl, n[2] / nl)
        c_w = wp(center_l)
        r_in, r_out = rad - hrad, rad + hrad

        def rpt(r, a, sn):
            ca, sa = math.cos(a), math.sin(a)
            return (
                c_w[0] + u[0] * r * ca + v[0] * r * sa + n[0] * hthick * sn,
                c_w[1] + u[1] * r * ca + v[1] * r * sa + n[1] * hthick * sn,
                c_w[2] + u[2] * r * ca + v[2] * r * sa + n[2] * hthick * sn,
            )

        for i in range(segs):
            # Omit segments 1, 2, 3 (upper-right quadrant visibly broken off!)
            if i in (1, 2, 3):
                continue
            a0 = 2.0 * math.pi * i / segs
            a1 = 2.0 * math.pi * ((i + 1) % segs) / segs
            fi0, fo0, fo1, fi1 = rpt(r_in, a0, 1), rpt(r_out, a0, 1), rpt(r_out, a1, 1), rpt(r_in, a1, 1)
            bi0, bo0, bo1, bi1 = rpt(r_in, a0, -1), rpt(r_out, a0, -1), rpt(r_out, a1, -1), rpt(r_in, a1, -1)
            bucket.add_planar_quad_art(fi0, fo0, fo1, fi1, mat_name, region_name)
            bucket.add_planar_quad_art(bi1, bo1, bo0, bi0, mat_name, region_name)
            bucket.add_planar_quad_art(bo0, bo1, fo1, fo0, mat_name, region_name)
            bucket.add_planar_quad_art(fi0, fi1, bi1, bi0, mat_name, region_name)


def _v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _v_add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def _v_scale(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def _v_cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _v_norm(a):
    l = math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) or 1.0
    return (a[0] / l, a[1] / l, a[2] / l)


def _ellipse_ring(rx, rz, segs=8, x_off=0.0, z_off=0.0, front_bias=0.0, phase=None):
    """CCW around +Y: angle 0 at +Z, increasing toward +X, -Z, -X -> outward quad normals!"""
    if phase is None:
        phase = math.pi / segs
    pts = []
    for i in range(segs):
        a = phase + 2.0 * math.pi * i / segs
        sa, ca = math.sin(a), math.cos(a)
        z_mul = (1.0 + front_bias) if ca > 0 else 1.0
        pts.append((x_off + rx * sa, z_off + rz * ca * z_mul))
    return pts


def _xform_loft(bucket, cx, y0, cz, yaw_rad, rings, mat_name, region_name=None,
                cap_bot=True, cap_top=True):
    """
    Sculpted Art Deco loft connecting consecutive rings [(ly, [(lx, lz), ...]), ...].
    Rings must be wound CCW around +Y (0 at +Z -> +X -> -Z -> -X) so every quad faces outward!
    """
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(lx, ly, lz):
        rx, ry, rz = _rot_y((lx, ly, lz), cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    w_rings = []
    for ly, pts in rings:
        w_rings.append([wp(lx, ly, lz) for (lx, lz) in pts])

    n = len(w_rings[0])
    for ri in range(len(w_rings) - 1):
        r0, r1 = w_rings[ri], w_rings[ri + 1]
        for i in range(n):
            j = (i + 1) % n
            bucket.add_planar_quad_art(r0[i], r0[j], r1[j], r1[i], mat_name, region_name, edge_aligned=True)

    if cap_top:
        rt = w_rings[-1]
        ct = (sum(p[0] for p in rt) / n, sum(p[1] for p in rt) / n, sum(p[2] for p in rt) / n)
        for i in range(n):
            j = (i + 1) % n
            bucket.add_planar_tri_art(ct, rt[i], rt[j], mat_name, region_name, edge_aligned=True)

    if cap_bot:
        rb = w_rings[0]
        cb = (sum(p[0] for p in rb) / n, sum(p[1] for p in rb) / n, sum(p[2] for p in rb) / n)
        for i in range(n):
            j = (i + 1) % n
            bucket.add_planar_tri_art(cb, rb[j], rb[i], mat_name, region_name, edge_aligned=True)


def _xform_limb(bucket, cx, y0, cz, yaw_rad, p0, p1, r0, r1, mat_name, region_name=None,
                segs=5, cap0=False, cap1=False):
    """Tapered multi-faceted 3D sculptural limb (upper arm, forearm, thigh, calf, tool shaft) with outward normals."""
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(pt):
        rx, ry, rz = _rot_y(pt, cy, sy)
        return (cx + rx, y0 + ry, cz + rz)

    wp0, wp1 = wp(p0), wp(p1)
    d = _v_sub(wp1, wp0)
    if math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) < 1e-4:
        return
    fwd = _v_norm(d)
    up_ref = (0.0, 0.0, 1.0) if abs(fwd[2]) < 0.90 else (1.0, 0.0, 0.0)
    right = _v_norm(_v_cross(up_ref, fwd))
    up = _v_cross(fwd, right)  # right x up = fwd (right-handed!)

    def make_ring(pt, rad):
        ring = []
        for i in range(segs):
            a = 2.0 * math.pi * i / segs
            ca, sa = math.cos(a), math.sin(a)
            off = _v_add(_v_scale(right, rad * ca), _v_scale(up, rad * sa))
            ring.append(_v_add(pt, off))
        return ring

    q0 = make_ring(wp0, r0)
    q1 = make_ring(wp1, r1)
    for i in range(segs):
        j = (i + 1) % segs
        bucket.add_planar_quad_art(q0[i], q0[j], q1[j], q1[i], mat_name, region_name, edge_aligned=True)
    if cap1:
        for i in range(segs):
            j = (i + 1) % segs
            bucket.add_planar_tri_art(wp1, q1[i], q1[j], mat_name, region_name, edge_aligned=True)
    if cap0:
        for i in range(segs):
            j = (i + 1) % segs
            bucket.add_planar_tri_art(wp0, q0[j], q0[i], mat_name, region_name, edge_aligned=True)


def _xform_joint(bucket, cx, y0, cz, yaw_rad, center_l, rx, ry, rz, mat_name, region_name=None, segs=6):
    """Crisp faceted Art Deco sculptural bipyramid/octahedron joint volume (2*segs tris, outward normals)."""
    cy, sy = math.cos(yaw_rad), math.sin(yaw_rad)

    def wp(lx, ly, lz):
        rrx, rry, rrz = _rot_y((lx, ly, lz), cy, sy)
        return (cx + rrx, y0 + rry, cz + rrz)

    lx, ly, lz = center_l
    mid = [wp(px, ly, pz) for (px, pz) in _ellipse_ring(rx, rz, segs, lx, lz)]
    top = wp(lx, ly + ry, lz)
    bot = wp(lx, ly - ry, lz)
    for i in range(segs):
        j = (i + 1) % segs
        bucket.add_planar_tri_art(top, mid[i], mid[j], mat_name, region_name, edge_aligned=True)
        bucket.add_planar_tri_art(bot, mid[j], mid[i], mat_name, region_name, edge_aligned=True)


def build_guardian_statue(bucket, coll, cx, y_ledge, cz, yaw_rad, kind, state_id):
    """
    8.2 m (106 art px) Colossal 1920s Art Deco Monumental Guardian Statue standing proud on a cantilevered
    dark grey-pink granite corner ledge at y = 44.5 m, facing 45 degrees outward into open sky!
    True sculpted human anatomy (~650-850 tris each):
      - Chiseled 8-sided torso with cinched waist, broad sloped shoulders & slender neck
      - Sculpted 12-faced Art Deco head with tapered jaw, dark shadowed eye sockets, nose wedge, overhanging brow & unique crown/helmet/hair
      - Distinct stances (parted trousers/greaves vs contrapposto pleated robes with forward knee & sandals)
      - Articulated deltoid/upper-arm/elbow/forearm/fist limbs with clear negative space against torso & sky
      - Monumental readable 3D attributes:
          * defense  : 5.2 m diamond-blade broadsword clasped by both fists + sweeping cloak + 2.4 m bossed round shield
          * learning : sculpted 3D open V-book in left arm + right arm thrust high into sky holding a glowing bronze torch
          * industry : parted trouser stance + waist-cinched riveted brown leather apron & suspenders + 3.7 m sledgehammer over shoulder + 1.9 m toothed bronze gear wheel (chipped in 1976!)
          * harvest  : lush multi-bundle golden wheat sheaf in left arm + sweeping sky-silhouetted crescent sickle in right hand + woven harvest basket
    """
    g_reg = "guardian_stone_1976" if state_id == "1976" else "guardian_stone"
    d_reg = "guardian_drape_1976" if state_id == "1976" else "guardian_drape"
    sh_reg = "guardian_shadow"
    g_mat = "inscriptions"

    # 1. Grand Cantilevered Dark Grey-Pink Granite Corner Ledge & Stepped Art Deco Corbel (anchored deep into pier!)
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -16.0, -36.0, -54.0, 16.0, -23.0, 8.0, "stone_granite")
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -23.5, -23.0, -52.0, 23.5, -12.0, 18.0, "stone_granite")
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -30.5, -12.0, -50.0, 30.5, 0.0, 26.0, "stone_granite")
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -32.0, -1.8, -50.5, 32.0, 0.4, 27.4, "stone_ashlar")
    # Small 0.6 m (11 art px tall) carved granite name band on the outer front face of the ledge!
    _xform_panel_front(bucket, cx, y_ledge, cz, yaw_rad, -28.0, -11.5, 28.0, -0.5, 26.35, "inscriptions", f"label_{kind}", True)

    # 2. Stepped Sculptural Plinth (ly = 0..8)
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -21.0, 0.0, -18.0, 21.0, 4.5, 18.0, "stone_granite")
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -17.5, 4.5, -15.0, 17.5, 8.0, 15.0, "stone_ashlar")

    # 3. STANCE & LOWER BODY (ly = 8..54) — DISTINCT HUMAN STANCE PER GUARDIAN (NO COLUMN BLOCKS!)
    if kind == "industry":
        # Wide heroic planted trouser stance — TWO COMPLETELY SEPARATED muscular legs & heavy boots!
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-6.2, 53.0, 0.8), (-9.6, 31.0, 4.4), 5.6, 4.5, g_mat, g_reg, segs=6)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (-9.6, 31.0, 4.4), 4.7, 3.8, 4.7, g_mat, g_reg, segs=6)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-9.6, 31.0, 4.4), (-10.5, 13.0, 5.2), 4.4, 3.6, g_mat, g_reg, segs=6)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -14.2, 8.0, 1.0, -6.8, 13.5, 13.8, g_mat, sh_reg)

        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (6.2, 53.0, -0.6), (9.6, 31.0, -2.4), 5.6, 4.5, g_mat, g_reg, segs=6)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (9.6, 31.0, -2.4), 4.7, 3.8, 4.7, g_mat, g_reg, segs=6)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (9.6, 31.0, -2.4), (10.5, 13.0, -3.2), 4.4, 3.6, g_mat, g_reg, segs=6)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, 6.8, 8.0, -7.2, 14.2, 13.5, 5.5, g_mat, sh_reg)

        # Pelvis / work-trouser hips block uniting legs cleanly into waist
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (46.0, _ellipse_ring(12.2, 7.6, 6, 0.0, 0.2)),
            (53.5, _ellipse_ring(11.6, 7.4, 6, 0.0, 0.2)),
        ], g_mat, g_reg, cap_top=False)

        # Waist-cinched heavy forged saddle-brown Leather Blacksmith Apron + narrow chest suspenders
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -11.4, 53.2, -7.8, 11.4, 56.8, 8.6, "bronze_gilded")
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (25.0, [(-11.2, 8.4), (11.2, 8.4), (10.0, 6.2), (-10.0, 6.2)]),
            (39.0, [(-9.8, 9.0), (9.8, 9.0), (8.8, 6.8), (-8.8, 6.8)]),
            (53.5, [(-8.6, 8.4), (8.6, 8.4), (7.8, 6.2), (-7.8, 6.2)]),
        ], g_mat, "guardian_leather", cap_top=False)
        _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, (-5.5, 56.0, 8.4), (-7.5, 83.5, 8.2), 1.5, 1.0, g_mat, "guardian_leather")
        _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, (5.5, 56.0, 8.4), (7.5, 83.5, 8.2), 1.5, 1.0, g_mat, "guardian_leather")

    elif kind == "defense":
        # Heroic sentinel warrior stance: two parted armored greaved legs + Art Deco battle kilt + sweeping back cloak!
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-6.2, 50.0, 0.6), (-9.2, 30.0, 4.2), 5.2, 4.2, g_mat, g_reg, segs=6)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (-9.2, 30.0, 4.2), 4.7, 3.9, 4.9, "bronze_gilded", segs=6)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-9.2, 30.0, 4.2), (-10.0, 13.0, 4.8), 4.2, 3.4, g_mat, g_reg, segs=6)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -13.6, 8.0, 0.8, -6.4, 13.5, 13.0, g_mat, sh_reg)

        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (6.2, 50.0, -0.4), (9.2, 30.0, -2.0), 5.2, 4.2, g_mat, g_reg, segs=6)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (9.2, 30.0, -2.0), 4.7, 3.9, 4.9, "bronze_gilded", segs=6)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (9.2, 30.0, -2.0), (10.0, 13.0, -2.6), 4.2, 3.4, g_mat, g_reg, segs=6)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, 6.4, 8.0, -6.8, 13.6, 13.5, 5.2, g_mat, sh_reg)

        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (36.0, _ellipse_ring(14.8, 9.6, 8, 0.0, 0.4)),
            (46.0, _ellipse_ring(13.2, 8.6, 8, 0.0, 0.4)),
            (54.5, _ellipse_ring(10.0, 7.2, 8, 0.0, 0.4)),
        ], g_mat, d_reg, cap_top=False)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -11.2, 53.5, -7.8, 11.2, 57.2, 8.8, "bronze_gilded")

        # Sweeping Monumental Cloak draped behind shoulders & flaring behind legs
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (10.0, [(-19.5, -8.5), (19.5, -8.5), (17.5, -13.5), (-17.5, -13.5)]),
            (45.0, [(-17.0, -7.8), (17.0, -7.8), (15.5, -12.0), (-15.5, -12.0)]),
            (81.5, [(-19.0, -6.2), (19.0, -6.2), (17.5, -9.8), (-17.5, -9.8)]),
        ], g_mat, d_reg)

    else:
        # LEARNING & HARVEST: Noble sculpted contrapposto Art Deco draped robe w/ forward knee & sandals!
        knee_x = -5.5 if kind == "learning" else 5.5
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (10.0, _ellipse_ring(15.6, 10.6, 8, 0.0, 0.6)),
            (25.0, _ellipse_ring(14.2, 10.0, 8, knee_x * 0.14, 1.3)),
            (42.0, _ellipse_ring(13.4, 9.0, 8, knee_x * 0.10, 1.0)),
            (54.5, _ellipse_ring(9.2, 6.8, 8, 0.0, 0.4)),
        ], g_mat, d_reg, cap_bot=False, cap_top=False)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (knee_x, 45.0, 4.2), (knee_x * 1.15, 30.0, 9.6), 4.6, 3.8, g_mat, g_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (knee_x * 1.15, 30.0, 9.6), (knee_x * 1.25, 11.5, 8.4), 3.8, 3.2, g_mat, d_reg, segs=5)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -10.5, 8.0, 4.0, -3.5, 12.2, 14.0, g_mat, sh_reg)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, 3.5, 8.0, 2.0, 10.5, 12.2, 12.0, g_mat, sh_reg)
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (53.0, _ellipse_ring(10.0, 7.5, 8, 0.0, 0.4)),
            (57.2, _ellipse_ring(9.6, 7.2, 8, 0.0, 0.4)),
        ], "bronze_gilded", cap_bot=False, cap_top=False)

    # 4. HEROIC TAPERED HUMAN TORSO, BROAD SLOPED SHOULDERS & STRONG SCULPTED NECK (ly = 54..95.0)!
    _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
        (54.0, _ellipse_ring(9.2, 6.9, 8, 0.0, 0.4)),              # cinched waist
        (65.0, _ellipse_ring(13.6, 8.6, 8, 0.0, 0.8)),             # tapered ribcage
        (76.5, _ellipse_ring(19.2, 10.6, 8, 0.0, 1.1, 0.16)),      # broad sculpted chest
        (84.0, _ellipse_ring(20.2, 9.0, 8, 0.0, 0.8)),             # wide heroic Art Deco shoulders
        (89.5, _ellipse_ring(9.2, 6.8, 8, 0.0, 1.0)),              # powerful sloped trapezius collar
        (95.2, _ellipse_ring(5.6, 5.5, 8, 0.0, 1.4)),              # strong monumental sculpted neck!
    ], g_mat, g_reg, cap_bot=False, cap_top=False)
    # Sculpted chiseled pectoral / collarbone relief plane on upper chest
    _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
        (69.5, [(-10.8, 9.4), (10.8, 9.4), (9.2, 7.0), (-9.2, 7.0)]),
        (77.2, [(-14.0, 11.6), (14.0, 11.6), (11.8, 8.4), (-11.8, 8.4)]),
        (83.0, [(-12.0, 9.9), (12.0, 9.9), (9.8, 7.2), (-9.8, 7.2)]),
    ], "stone_ashlar")
    # Heroic sculpted Deltoid Shoulder Caps blending smoothly into torso & arms!
    _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (-19.6, 80.5, 0.8), 6.4, 6.0, 6.2, g_mat, g_reg, segs=6)
    _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (19.6, 80.5, 0.8), 6.4, 6.0, 6.2, g_mat, g_reg, segs=6)

    # 5. NOBLE CHISELED 12-FACED ART DECO HEAD & STERN FACE (ly = 94.5..114, slightly bowed forward)!
    _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
        (94.6, _ellipse_ring(3.2, 4.6, 8, 0.0, 3.0)),              # noble chiseled chin tip
        (98.0, _ellipse_ring(5.8, 6.6, 8, 0.0, 2.4)),              # strong Art Deco jawline
        (102.4, _ellipse_ring(7.1, 7.4, 8, 0.0, 1.9)),             # sculpted angular cheekbones
        (106.5, _ellipse_ring(7.0, 7.6, 8, 0.0, 1.5)),             # stern temples & forehead
        (110.8, _ellipse_ring(6.0, 7.1, 8, 0.0, 0.5)),             # rounded cranium
        (113.8, _ellipse_ring(3.4, 4.4, 8, 0.0, -0.3)),            # crown top
    ], g_mat, g_reg, cap_bot=False, cap_top=True)
    # Softly recessed stone shadow under-chin & subtle stern orbital eye recesses (warm sculpted limestone shadow!)
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -3.4, 93.8, 3.2, 3.4, 95.2, 6.5, g_mat, sh_reg)
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -5.4, 102.4, 6.8, -1.6, 104.5, 9.1, g_mat, sh_reg)
    _xform_box(bucket, cx, y_ledge, cz, yaw_rad, 1.6, 102.4, 6.8, 5.4, 104.5, 9.1, g_mat, sh_reg)
    # Stern overhanging Art Deco Brow Ridge catching crisp sunlight above the shadowed eye planes
    _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
        (104.4, [(-6.3, 9.9), (6.3, 9.9), (5.6, 7.0), (-5.6, 7.0)]),
        (106.7, [(-6.1, 9.5), (6.1, 9.5), (5.6, 7.0), (-5.6, 7.0)]),
    ], "stone_ashlar")
    # Crisp slender classical Art Deco Nose Wedge flowing smoothly down from the brow!
    _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
        (100.0, [(-1.2, 10.5), (1.2, 10.5), (1.6, 7.6), (-1.6, 7.6)]),
        (105.0, [(-0.8, 10.0), (0.8, 10.0), (1.2, 7.6), (-1.2, 7.6)]),
    ], "stone_ashlar")
    # Sculpted noble chin/lip stone facet (catches light rather than a dark box!)
    _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
        (95.4, [(-2.4, 8.6), (2.4, 8.6), (2.6, 6.8), (-2.6, 6.8)]),
        (98.4, [(-2.6, 9.1), (2.6, 9.1), (2.8, 7.0), (-2.8, 7.0)]),
    ], "stone_ashlar")

    # Distinct Sculpted Art Deco Headgear / Hair per Guardian
    if kind == "defense":
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (106.5, _ellipse_ring(7.6, 8.2, 6, 0.0, 0.8)),
            (111.4, _ellipse_ring(7.0, 8.0, 6, 0.0, -0.2)),
            (115.0, _ellipse_ring(4.4, 5.6, 6, 0.0, -1.0)),
        ], g_mat, sh_reg, cap_bot=False)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -7.5, 98.2, 1.5, -5.8, 106.8, 6.4, g_mat, sh_reg)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, 5.8, 98.2, 1.5, 7.5, 106.8, 6.4, g_mat, sh_reg)
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (112.0, [(-1.9, 6.2), (1.9, 6.2), (1.9, -9.4), (-1.9, -9.4)]),
            (120.0, [(-1.3, 3.8), (1.3, 3.8), (1.3, -11.2), (-1.3, -11.2)]),
        ], "bronze_gilded", cap_bot=False)
    elif kind == "learning":
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (99.5, _ellipse_ring(7.7, 7.6, 6, 0.0, -1.5)),
            (107.0, _ellipse_ring(7.8, 8.2, 6, 0.0, -0.2)),
            (113.8, _ellipse_ring(5.6, 6.6, 6, 0.0, -1.0)),
        ], g_mat, sh_reg, cap_bot=False)
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (106.8, _ellipse_ring(7.6, 8.4, 6, 0.0, 1.0)),
            (109.2, _ellipse_ring(7.5, 8.3, 6, 0.0, 0.8)),
        ], "bronze_gilded", cap_bot=False, cap_top=False)
    elif kind == "industry":
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (106.2, _ellipse_ring(7.6, 8.0, 6, 0.0, 0.4)),
            (111.2, _ellipse_ring(7.2, 8.0, 6, 0.0, -0.4)),
            (115.0, _ellipse_ring(5.2, 6.4, 6, 0.0, -1.0)),
        ], g_mat, sh_reg, cap_bot=False)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -7.4, 99.8, -6.4, 7.4, 107.0, -1.0, g_mat, sh_reg)
    elif kind == "harvest":
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (96.8, _ellipse_ring(7.6, 7.0, 6, 0.0, -2.0)),
            (106.5, _ellipse_ring(7.8, 8.2, 6, 0.0, -0.2)),
            (113.8, _ellipse_ring(5.4, 6.4, 6, 0.0, -0.9)),
        ], g_mat, sh_reg, cap_bot=False)
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (107.0, _ellipse_ring(8.0, 8.8, 6, 0.0, 0.9)),
            (110.5, _ellipse_ring(7.8, 8.6, 6, 0.0, 0.7)),
        ], "bronze_gilded", cap_bot=False, cap_top=False)

    # 6. MONUMENTAL SCULPTED ARMS (STRONG PROPORTIONS + CLEAR SILHOUETTE SPACE!) & 3D ATTRIBUTES!
    if kind == "defense":
        for sx in (-1.0, 1.0):
            sh = (sx * 19.6, 80.5, 0.8)
            el = (sx * 24.5, 65.8, 7.0)
            hd = (sx * 4.4, 68.0, 17.6)
            _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, sh, el, 5.2, 4.3, g_mat, g_reg, segs=5)
            _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, el, 4.5, 4.5, 4.5, g_mat, g_reg, segs=5)
            _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, el, hd, 4.2, 3.2, g_mat, g_reg, segs=5)
            _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, hd, 3.6, 3.4, 3.6, g_mat, g_reg, segs=5)

        # 5.3 m (68 art px) Monumental Sculpted Gilded Broadsword planted dead-center point-down into the plinth!
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (0.0, 73.0, 18.0), 4.0, 4.2, 4.0, "bronze_gilded", segs=6)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (0.0, 58.0, 18.0), (0.0, 70.5, 18.0), 2.1, 2.1, g_mat, "guardian_leather", segs=5)
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -13.5, 54.0, 15.8, 13.5, 58.2, 20.2, "bronze_gilded")
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -15.5, 55.2, 16.4, 15.5, 57.6, 19.6, "bronze_gilded")
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (8.2,  [(0.0, 19.2), (1.0, 18.0), (0.0, 16.8), (-1.0, 18.0)]),
            (16.0, [(0.0, 19.8), (3.4, 18.0), (0.0, 16.2), (-3.4, 18.0)]),
            (54.2, [(0.0, 20.4), (4.6, 18.0), (0.0, 15.6), (-4.6, 18.0)]),
        ], "bronze_gilded", cap_top=False)

        # 2.4 m (31 art px) Round Embossed Shield angled 45-deg OUTWARD toward viewer against outer left greave!
        _xform_ring(bucket, cx, y_ledge, cz, yaw_rad, (-17.5, 27.0, 11.5), (0, 1, 0), (0.72, 0, 0.69), 14.2, 3.2, 2.0, 10, "bronze_gilded")
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (-17.5, 27.0, 11.5), 9.6, 12.6, 9.6, "stone_ashlar", segs=6)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (-21.0, 27.0, 15.0), 4.0, 4.6, 4.0, "bronze_gilded", segs=6)

    elif kind == "learning":
        l_sh = (-19.6, 80.5, 0.8)
        l_el = (-24.2, 64.0, 6.4)
        l_hd = (-10.5, 66.5, 16.5)
        # Draped Art Deco upper sleeve + sculpted forearm
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, l_sh, l_el, 5.4, 4.4, g_mat, d_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, l_el, 4.4, 4.4, 4.4, g_mat, d_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, l_el, l_hd, 4.0, 3.0, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, l_hd, 3.3, 3.1, 3.3, g_mat, g_reg, segs=5)

        # True 3D Open V-Book
        _xform_box(bucket, cx, y_ledge, cz, yaw_rad, -9.2, 62.0, 14.5, -6.8, 79.0, 17.5, "bronze_gilded")
        _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, (-8.0, 70.5, 16.2), (-17.5, 70.5, 20.8), 1.4, 8.6, "bronze_gilded", caps=True)
        _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, (-8.0, 70.5, 16.2), (2.0, 70.5, 20.8), 1.4, 8.6, "bronze_gilded", caps=True)
        _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, (-8.2, 70.5, 17.6), (-16.6, 70.5, 21.8), 1.3, 7.5, "stone_ashlar", caps=True)
        _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, (-7.8, 70.5, 17.6), (1.2, 70.5, 21.8), 1.3, 7.5, "stone_ashlar", caps=True)

        # Right arm raised TRIUMPHANTLY HIGH & OUTWARD into open sky holding the gleaming Bronze Torch!
        r_sh = (19.6, 80.5, 0.8)
        r_el = (27.5, 95.0, 4.4)
        r_hd = (24.5, 112.0, 7.8)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, r_sh, r_el, 5.2, 4.2, g_mat, d_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, r_el, 4.3, 4.3, 4.3, g_mat, d_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, r_el, r_hd, 3.9, 2.9, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, r_hd, 3.4, 3.2, 3.4, g_mat, g_reg, segs=5)

        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (24.0, 102.0, 7.4), (25.2, 125.0, 9.2), 1.9, 3.1, "bronze_gilded", segs=6, cap0=True)
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (123.0, _ellipse_ring(3.2, 3.2, 6, 25.2, 9.2)),
            (128.5, _ellipse_ring(6.2, 6.2, 6, 25.4, 9.4)),
            (130.5, _ellipse_ring(5.2, 5.2, 6, 25.4, 9.4)),
        ], "bronze_gilded", cap_bot=False)
        _xform_panel_front(bucket, cx, y_ledge, cz, yaw_rad, 24.1, 130.5, 26.7, 135.0, 9.4, "glow_monument", "flame_brazier", True)
        _xform_panel_front(bucket, cx, y_ledge, cz, yaw_rad + math.pi * 0.5, -10.7, 130.5, -8.1, 135.0, 25.4, "glow_monument", "flame_brazier", True)

    elif kind == "industry":
        l_sh = (-19.6, 80.5, 0.8)
        l_el = (-26.2, 64.2, 5.4)
        l_hd = (-20.8, 41.0, 11.2)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, l_sh, l_el, 5.4, 4.4, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, l_el, 4.5, 4.5, 4.5, g_mat, g_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, l_el, l_hd, 4.2, 3.2, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, l_hd, 3.6, 3.4, 3.6, g_mat, g_reg, segs=5)

        r_sh = (19.6, 80.5, 0.8)
        r_el = (27.6, 71.8, 6.2)
        r_hd = (19.5, 86.5, 13.0)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, r_sh, r_el, 5.4, 4.4, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, r_el, 4.5, 4.5, 4.5, g_mat, g_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, r_el, r_hd, 4.2, 3.2, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, r_hd, 3.6, 3.4, 3.6, g_mat, g_reg, segs=5)

        # 3.7 m Heavy Industrial Sledgehammer resting boldly over right shoulder into open sky!
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (15.5, 64.0, 19.5), (26.5, 108.0, -7.5), 1.9, 1.9, "detail_atlas", "timber_bench", segs=5, cap0=True)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (19.0, 103.5, -12.0), (34.0, 108.5, -3.0), 5.6, 5.6, "detail_atlas", "dark_iron", segs=6, cap0=True, cap1=True)

        # 1.9 m Toothed Spoked Bronze Gear Wheel at left feet (visibly CHIPPED quadrant in 1976!)
        is_chipped = (state_id == "1976")
        gc = (-19.5, 24.0, 11.2)
        _xform_ring(bucket, cx, y_ledge, cz, yaw_rad, gc, (1, 0, 0), (0, 1, 0),
                    11.2, 2.8, 2.1, 12, "bronze_gilded", skip_quadrant=is_chipped)
        for ti in range(12):
            if is_chipped and ti in (1, 2, 3):
                continue
            ta = 2.0 * math.pi * (ti + 0.5) / 12.0
            ca, sa = math.cos(ta), math.sin(ta)
            _xform_beam(bucket, cx, y_ledge, cz, yaw_rad,
                        (gc[0] + ca * 12.5, gc[1] + sa * 12.5, gc[2]),
                        (gc[0] + ca * 15.8, gc[1] + sa * 15.8, gc[2]),
                        1.6, 1.9, "bronze_gilded", caps=True)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, gc, 3.6, 3.6, 2.8, "bronze_gilded", segs=6)
        for si in range(6):
            if is_chipped and si == 1:
                continue
            sa = 2.0 * math.pi * si / 6.0
            _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, gc,
                        (gc[0] + math.cos(sa) * 11.0, gc[1] + math.sin(sa) * 11.0, gc[2]), 1.3, 1.3, "bronze_gilded")

    elif kind == "harvest":
        l_sh = (-19.6, 80.5, 0.8)
        l_el = (-25.2, 65.2, 6.4)
        l_hd = (-12.5, 67.5, 16.5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, l_sh, l_el, 5.3, 4.3, g_mat, d_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, l_el, 4.4, 4.4, 4.4, g_mat, d_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, l_el, l_hd, 4.0, 3.0, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, l_hd, 3.3, 3.1, 3.3, g_mat, g_reg, segs=5)

        # Monumental Sculpted Flaring 3D Golden Wheat Sheaf
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-16.5, 44.0, 12.5), (-13.5, 67.0, 14.5), 5.6, 3.6, "bronze_gilded", segs=6, cap0=True)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-13.5, 67.0, 14.5), (-22.5, 99.0, 14.8), 3.6, 5.2, "bronze_gilded", segs=5, cap1=True)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-13.5, 67.0, 14.5), (-15.0, 104.0, 15.8), 3.6, 5.0, "bronze_gilded", segs=5, cap1=True)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (-13.5, 67.0, 14.5), (-8.5, 96.0, 16.2), 3.2, 4.4, "bronze_gilded", segs=5, cap1=True)
        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (65.0, _ellipse_ring(5.2, 4.6, 6, -13.5, 14.5)),
            (69.5, _ellipse_ring(5.2, 4.6, 6, -13.5, 14.5)),
        ], "stone_ashlar", cap_bot=False, cap_top=False)

        # Right arm swept OUTWARD into open sky holding a gleaming curved Gilded Harvest Sickle!
        r_sh = (19.6, 80.5, 0.8)
        r_el = (26.5, 70.5, 5.8)
        r_hd = (30.2, 76.8, 11.2)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, r_sh, r_el, 5.2, 4.2, g_mat, d_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, r_el, 4.3, 4.3, 4.3, g_mat, d_reg, segs=5)
        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, r_el, r_hd, 3.9, 2.9, g_mat, g_reg, segs=5)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, r_hd, 3.3, 3.1, 3.3, g_mat, g_reg, segs=5)

        _xform_limb(bucket, cx, y_ledge, cz, yaw_rad, (29.8, 70.0, 11.0), (31.0, 80.0, 11.6), 1.6, 1.6, "detail_atlas", "timber_bench", segs=5, cap0=True)
        sickle_pts = [
            (31.0, 79.5, 11.6),
            (38.5, 86.5, 12.0),
            (40.5, 96.5, 12.2),
            (34.5, 104.5, 12.2),
            (24.0, 106.0, 12.0),
            (18.0, 102.5, 11.8),
        ]
        for si in range(len(sickle_pts) - 1):
            w_s = 2.0 - si * 0.25
            _xform_beam(bucket, cx, y_ledge, cz, yaw_rad, sickle_pts[si], sickle_pts[si + 1], w_s, 1.1, "bronze_gilded", caps=True)

        _xform_loft(bucket, cx, y_ledge, cz, yaw_rad, [
            (8.0,  _ellipse_ring(6.4, 6.4, 6, 16.5, 8.0)),
            (22.5, _ellipse_ring(8.8, 8.8, 6, 16.5, 8.0)),
        ], "detail_atlas", "timber_bench", cap_bot=False)
        _xform_joint(bucket, cx, y_ledge, cz, yaw_rad, (16.5, 23.0, 8.0), 8.0, 4.8, 8.0, "bronze_gilded", segs=6)

    # 7. Solid glTF Y-up Collision Box for this Guardian Statue on its projecting corner ledge!
    coll.add_box(cx - 30.0, y_ledge - 14.0, cz - 30.0, cx + 30.0, y_ledge + 120.0, cz + 30.0,
                 f"guardian_statue_{kind}", "exterior")


# ==============================================================================
# REUSABLE INSTANCED PROPS (LAMPS, BRAZIERS, FLAGS, BRONZE BENCHES, PLAZA LIFE)
# ==============================================================================
def build_lamp_mesh(MeshBucket, name, mat_idx, mat_size, regions, style="electric", broken=False):
    """Plaza lamp standard: 1926 ornate gas lantern vs 1941+ Art Deco bronze electric globe vs 1976 broken dark shell."""
    b = MeshBucket(name, mat_idx, mat_size, regions)
    b.add_box(-4.8, 0, -4.8, 4.8, 7, 4.8, "stone_granite")
    b.add_box(-3.4, 7, -3.4, 3.4, 13, 3.4, "bronze_gilded")
    b.add_cylinder(0, 13, 52, 0, 2.0, 1.5, 8, "detail_atlas", region_name="dark_iron")
    if style == "gas":
        b.add_box(-6.0, 48, -1.2, 6.0, 50.5, 1.2, "bronze_gilded")
        b.add_box(-3.2, 52, -3.2, 3.2, 54.5, 3.2, "bronze_gilded")
        if not broken:
            b.add_box(-1.25, 54.5, -1.25, 1.25, 58.0, 1.25, "glow_monument",
                      faces=["+z", "-z", "+x", "-x"], region_name="lamp_globe", fit_region=True)
        else:
            b.add_box(-1.25, 54.5, -1.25, 1.25, 57.0, 1.25, "detail_atlas", region_name="dark_iron")
        b.add_cylinder(0, 58.0, 63.2, 0, 3.4, 0.4, 6, "detail_atlas", region_name="dark_iron")
    else:
        b.add_box(-2.8, 52, -2.8, 2.8, 54.5, 2.8, "bronze_gilded")
        if not broken:
            b.add_box(-1.20, 54.5, -1.20, 1.20, 57.6, 1.20, "glow_monument",
                      faces=["+z", "-z", "+x", "-x"], region_name="lamp_globe", fit_region=True)
            b.add_cylinder(0, 57.6, 60.6, 0, 2.4, 0.3, 6, "bronze_gilded")
        else:
            b.add_box(-1.20, 54.5, -1.20, 1.20, 56.5, 1.20, "detail_atlas", region_name="dark_iron")
    return b


def build_brazier_eternal_mesh(MeshBucket, mat_idx, mat_size, regions):
    """Monumental eternal-flame bronze tripod brazier mounted on main South staircase cheek walls."""
    b = MeshBucket("brazier_eternal_inst", mat_idx, mat_size, regions)
    b.add_box(-11, 0, -11, 11, 5, 11, "stone_granite")
    b.add_box(-9, 5, -9, 9, 9, 9, "bronze_gilded")
    for ang in (0, 2.0944, 4.1888):
        lx, lz = math.cos(ang) * 6.5, math.sin(ang) * 6.5
        b.add_beam_art((lx, 9, lz), (lx * 1.15, 22, lz * 1.15), 1.4, 1.4, "detail_atlas", "dark_iron")
    b.add_cylinder(0, 18, 24, 0, 6.5, 11.5, 10, "bronze_gilded", cap_top=True, cap_bot=True)
    b.add_cylinder(0, 23, 25.5, 0, 12.2, 12.2, 10, "detail_atlas", region_name="dark_iron", cap_top=True)
    # 2 double-sided crossed vertical orange eternal flame planes (~1.68 m2 across all 4 braziers!)
    b.add_panel_z(-2.4, 25.5, 2.4, 32.5, 0.0, "+z", "glow_monument", "flame_brazier", fit_region=True)
    b.add_panel_x(-2.4, 25.5, 2.4, 32.5, 0.0, "+x", "glow_monument", "flame_brazier", fit_region=True)
    return b


def build_flagpole_civic_mesh(MeshBucket, mat_idx, mat_size, regions, bunting_1926=False):
    """Ceremonial 14 m monument flagpole with blue-and-gold compass-star civic banner (+ 1926 red/white/blue base wrap!)."""
    b = MeshBucket("flagpole_civic_inst", mat_idx, mat_size, regions)
    b.add_box(-7, 0, -7, 7, 8, 7, "stone_granite")
    b.add_box(-5, 8, -5, 5, 14, 5, "bronze_gilded")
    if bunting_1926:
        # Red, white & blue festive fabric wrapped cleanly around flagpole base!
        b.add_box(-5.5, 14, -5.5, 5.5, 42, 5.5, "inscriptions", region_name="bunting_pole", fit_region=True)
    b.add_cylinder(0, 14, 70, 0, 2.2, 1.8, 8, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 70, 126, 0, 1.8, 1.4, 8, "detail_atlas", region_name="dark_iron")
    b.add_cylinder(0, 126, 178, 0, 1.4, 1.0, 8, "detail_atlas", region_name="dark_iron")
    b.add_sphere_faceted((0, 181, 0), 3.0, 4, 8, "bronze_gilded")
    b.add_panel_z(1.2, 150, 45.2, 176, 0.4, "+z", "inscriptions", "civic_flag", fit_region=True)
    b.add_panel_z(1.2, 150, 45.2, 176, -0.4, "-z", "inscriptions", "civic_flag", fit_region=True)
    return b


def build_bench_bronze_mesh(MeshBucket, mat_idx, mat_size, regions):
    """Art Deco cast-bronze & slatted timber plaza bench."""
    b = MeshBucket("bench_plaza_inst", mat_idx, mat_size, regions)
    for sx in (-12, 9):
        b.add_box(sx, 0, -5, sx + 3, 7.5, 5, "bronze_gilded")
        b.add_box(sx + 0.5, 7.5, -5.5, sx + 2.5, 15.0, -3.5, "bronze_gilded")
    b.add_box(-14, 6.5, -4.5, 14, 8.5, 4.5, "detail_atlas", region_name="timber_bench")
    b.add_box(-14, 10.5, -5.0, 14, 14.8, -3.6, "detail_atlas", region_name="timber_bench")
    return b


def build_planter_mesh(MeshBucket, mat_idx, mat_size, regions):
    """Sculpted dark-granite & ashlar ceremonial planter urn at staircase feet."""
    b = MeshBucket("plaza_planter_inst", mat_idx, mat_size, regions)
    b.add_box(-11, 0, -11, 11, 11, 11, "stone_granite")
    b.add_box(-12.5, 11, -12.5, 12.5, 13.5, 12.5, "stone_ashlar")
    b.add_box(-9.5, 13.5, -9.5, 9.5, 22.0, 9.5, "detail_atlas", region_name="lawn_turf")
    b.add_box(-6.5, 22.0, -6.5, 6.5, 27.0, 6.5, "detail_atlas", region_name="lawn_turf")
    return b


def build_trash_bin_mesh(MeshBucket, mat_idx, mat_size, regions):
    """2000+ civic architectural iron-and-bronze waste receptacle."""
    b = MeshBucket("trash_bin_inst", mat_idx, mat_size, regions)
    b.add_cylinder(0, 0, 12.0, 0, 4.5, 4.8, 8, "detail_atlas", region_name="dark_iron", cap_top=True)
    b.add_cylinder(0, 11.5, 13.5, 0, 5.2, 4.2, 8, "bronze_gilded", cap_top=True)
    return b


def build_drinking_fountain_mesh(MeshBucket, mat_idx, mat_size, regions):
    """Granite pedestal & bronze basin civic drinking fountain."""
    b = MeshBucket("drinking_fountain_inst", mat_idx, mat_size, regions)
    b.add_box(-5, 0, -5, 5, 11, 5, "stone_granite")
    b.add_cylinder(0, 11, 14.5, 0, 4.5, 6.2, 8, "bronze_gilded", cap_top=True)
    b.add_box(-1.5, 14.5, 2.5, 1.5, 16.5, 5.0, "bronze_gilded")
    return b


def build_interp_panel_mesh(MeshBucket, mat_idx, mat_size, regions):
    """2000+ 1.0 m museum interpretive plaque along the Reflecting Pool."""
    b = MeshBucket("interp_panel_inst", mat_idx, mat_size, regions)
    b.add_box(-8.5, 0, -1.5, -6.5, 13.5, 1.5, "detail_atlas", region_name="dark_iron")
    b.add_box(6.5, 0, -1.5, 8.5, 13.5, 1.5, "detail_atlas", region_name="dark_iron")
    b.add_box(-10.5, 11.5, -2.0, 10.5, 23.0, 2.0, "bronze_gilded")
    b.add_panel_z(-9.5, 12.5, 9.5, 22.0, 2.25, "+z", "inscriptions", "interp_panel", fit_region=True)
    return b


def build_bike_rack_mesh(MeshBucket, mat_idx, mat_size, regions):
    """2000+ modern steel U-loop civic bicycle rack beside the Visitor Centre."""
    b = MeshBucket("bike_rack_inst", mat_idx, mat_size, regions)
    b.add_box(-24, 0, -4, 24, 1.5, 4, "detail_atlas", region_name="dark_iron")
    for ox in (-18, -9, 0, 9, 18):
        b.add_box(ox - 1.0, 1.5, -3.5, ox + 1.0, 11.0, -2.0, "steel_truss")
        b.add_box(ox - 1.0, 1.5, 2.0, ox + 1.0, 11.0, 3.5, "steel_truss")
        b.add_box(ox - 1.0, 9.5, -3.5, ox + 1.0, 11.5, 3.5, "steel_truss")
    return b


def build_telescope_deck_mesh(MeshBucket, mat_idx, mat_size, regions, missing=False):
    b = MeshBucket("telescope_deck_missing" if missing else "telescope_deck_inst", mat_idx, mat_size, regions)
    b.add_cylinder(0, 0, 2.5, 0, 4.0, 3.2, 8, "detail_atlas", region_name="dark_iron", cap_top=True)
    if not missing:
        b.add_cylinder(0, 2.5, 17.0, 0, 1.4, 1.1, 8, "detail_atlas", region_name="dark_iron")
        b.add_beam_art((0, 18.0, -5.5), (0, 19.2, 6.5), 1.6, 1.6, "bronze_gilded", caps=True)
    return b


def build_tree_mesh(MeshBucket, name, mat_idx, mat_size, regions, mature=False):
    """Stylized architectural plaza tree (young 1926 sapling vs mature 1941+ canopy)."""
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


def build_flood_fixture_mesh(MeshBucket, mat_idx, mat_size, regions):
    """Visible 2000+ modern LED architectural floodlight housing mounted at flood_* nodes."""
    b = MeshBucket("flood_fixture_inst", mat_idx, mat_size, regions)
    b.add_box(-3.6, 0, -3.6, 3.6, 2.8, 3.6, "stone_granite")
    b.add_box(-2.8, 2.8, -2.2, 2.8, 7.8, 2.2, "detail_atlas", region_name="dark_iron")
    b.add_box(-2.2, 7.8, -1.8, 2.2, 8.8, 1.8, "bronze_gilded")
    return b


def build_scale_model_east_hall(bucket, cx, y0, cz):
    """
    1:35 Architectural Scale Model of The Great Meridian Monument displayed on a granite-and-bronze
    museum plinth in the center of the East Memorial Hall (reusing the exact 3-part silhouette!).
    """
    # Museum display plinth (1.9 m square x 1.0 m tall)
    bucket.add_box(cx - 14, y0, cz - 14, cx + 14, y0 + 11, cz + 14, "stone_granite")
    bucket.add_box(cx - 15, y0 + 11, cz - 15, cx + 15, y0 + 13, cz + 15, "bronze_gilded")
    ym = y0 + 13.0
    s = 1.0 / 32.0  # ~42.5 art px (~3.3 m tall miniature on plinth!)

    def mb(x0, ya, z0, x1, yb, z1, mat):
        bucket.add_box(cx + x0 * s, ym + ya * s, cz + z0 * s, cx + x1 * s, ym + yb * s, cz + z1 * s, mat)

    # Mini Acropolis & Monolithic Arch Block
    mb(-360, 0, -360, 360, 84, 360, "stone_granite")
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (96, 224) if sx > 0 else (-224, -96)
            z0, z1 = (96, 224) if sz > 0 else (-224, -96)
            mb(x0, 84, z0, x1, 310, z1, "stone_limestone")
    mb(-96, 250, 96, 96, 310, 224, "stone_limestone")
    mb(-96, 250, -224, 96, 310, -96, "stone_limestone")
    mb(96, 250, -96, 224, 310, 96, "stone_limestone")
    mb(-224, 250, -96, -96, 310, 96, "stone_limestone")
    # Mini 4 Corner Piers + Tiny Golden Sphere
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (116, 186) if sx > 0 else (-186, -116)
            z0, z1 = (116, 186) if sz > 0 else (-186, -116)
            mb(x0, 310, z0, x1, 748, z1, "stone_limestone")
    bucket.add_sphere_faceted((cx, ym + 528 * s, cz), 108 * s, 3, 6, "bronze_gilded")
    # Mini 58 m Deck, 3-Tier Lantern & Verdigris Spire!
    mb(-190, 724, -190, 190, 748, 190, "stone_ashlar")
    bucket.add_cylinder(cx, ym + 748 * s, ym + 864 * s, cz, 84 * s, 84 * s, 8, "stone_limestone", cap_top=True)
    bucket.add_cylinder(cx, ym + 864 * s, ym + 935 * s, cz, 68 * s, 68 * s, 8, "stone_limestone", cap_top=True)
    bucket.add_cylinder(cx, ym + 935 * s, ym + 980 * s, cz, 52 * s, 52 * s, 8, "stone_ashlar", cap_top=True)
    bucket.add_cylinder(cx, ym + 980 * s, ym + 1018 * s, cz, 44 * s, 44 * s, 8, "bronze_gilded", cap_top=True)
    bucket.add_cylinder(cx, ym + 1018 * s, ym + 1304 * s, cz, 42 * s, 8 * s, 8, "copper_spire", cap_top=True)
    bucket.add_cylinder(cx, ym + 1304 * s, ym + 1368 * s, cz, 4 * s, 0.5 * s, 4, "bronze_gilded")


# ==============================================================================
# MIDDLE LEVEL OF DETAIL: great-meridian-mid.glb (4,000 - 6,000 TRIANGLES!)
# For 500-2,000 m distance: full 3-part silhouette with arches, piers, statues as
# simple blocks, sphere's rings as simple bands, 3-tier lantern + 8 buttress fins,
# sunburst drum, ribbed spire, and plaza as one textured slab — no interior, no props!
# ==============================================================================
def build_mid_lod(MeshBucket, write_glb, R, MAT_SIZE, regions):
    mid_mats = [
        {"name": "stone_limestone", "base": "stone_limestone_2000.png", "roughness": 0.88},
        {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.90},
        {"name": "stone_ashlar", "base": "stone_ashlar.png", "roughness": 0.86},
        {"name": "steel_truss", "base": "steel_truss.png", "metallic": 0.45, "roughness": 0.58},
        {"name": "copper_spire", "base": "copper_2000.png", "metallic": 0.30, "roughness": 0.60},
        {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.62, "roughness": 0.42},
        {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
        {"name": "marble_plaza", "base": "marble_plaza.png", "roughness": 0.72},
        {"name": "glow_monument", "base": "glow_monument_base.png", "emissive": "glow_monument_mask.png",
         "emissiveFactor": [1.0, 1.0, 1.0], "roughness": 0.45},
    ]
    m_idx = {m["name"]: i for i, m in enumerate(mid_mats)}
    b = MeshBucket("great_meridian_mid", m_idx, MAT_SIZE, regions)

    HALF = 773.0
    CZ = -773.0
    Y_PLAZA = 2.0
    Y_T1, Y_T2, Y_T3 = 28.0, 56.0, 84.0
    Y_CORNICE = 310.0
    Y_SET1, Y_SET2, Y_DECK = 438.0, 593.0, 748.0
    ARCH_OUTER, ARCH_INNER = 224.0, 96.0
    ARCH_MID = (ARCH_OUTER + ARCH_INNER) * 0.5
    ARCH_HD = (ARCH_OUTER - ARCH_INNER) * 0.5

    # 1. Plaza as ONE textured slab + clean Reflecting Pool + Semicircular Exedra curve
    b.add_box(-HALF, 0, -2 * HALF, HALF, Y_PLAZA, 0, "marble_plaza", faces=["+y", "+z", "-z", "+x", "-x"])
    b.add_panel_y(-96, -346, 96, -108, 0.0, "+y", "water_pool")
    b.add_box(-104, 0, -354, 104, 7.0, -346, "stone_granite")
    b.add_box(-104, 0, -108, 104, 7.0, -100, "stone_granite")
    b.add_box(-104, 0, -346, -96, 7.0, -108, "stone_granite")
    b.add_box(96, 0, -346, 104, 7.0, -108, "stone_granite")
    for si in range(8):
        a0 = 0.08 * math.pi + 0.84 * math.pi * si / 8.0
        a1 = 0.08 * math.pi + 0.84 * math.pi * (si + 1) / 8.0
        b.add_beam_art((math.cos(a0) * 82, 10.0, -98 + math.sin(a0) * 82),
                       (math.cos(a1) * 82, 10.0, -98 + math.sin(a1) * 82), 6.5, 9.0, "stone_granite", caps=True)

    # 2. Three-tier Dark Granite Acropolis + 4 solid stair ramps + cheek walls
    b.add_box(-360, Y_PLAZA, CZ - 360, 360, Y_T1, CZ + 340, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-312, Y_T1, CZ - 312, 312, Y_T2, CZ + 300, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-264, Y_T2, CZ - 264, 264, Y_T3, CZ + 264, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-94, Y_PLAZA, CZ + 264, 94, Y_T2, CZ + 390, "stone_granite")
    b.add_box(-80, Y_PLAZA, CZ - 372, 80, Y_T2, CZ - 264, "stone_granite")
    b.add_box(264, Y_PLAZA, CZ - 80, 372, Y_T2, CZ + 80, "stone_granite")
    b.add_box(-372, Y_PLAZA, CZ - 80, -264, Y_T2, CZ + 80, "stone_granite")

    # 3. Monolithic 34.8 m Arch Block (dark granite lower 6 m + pale cream limestone upper + 4 true arches + ashlar cornice!)
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (ARCH_INNER, ARCH_OUTER) if sx > 0 else (-ARCH_OUTER, -ARCH_INNER)
            z0, z1 = (CZ + ARCH_INNER, CZ + ARCH_OUTER) if sz > 0 else (CZ - ARCH_OUTER, CZ - ARCH_INNER)
            b.add_box(x0, Y_T3, z0, x1, Y_T3 + 78, z1, "stone_granite", faces=["+z", "-z", "+x", "-x"])
            b.add_box(x0, Y_T3 + 78, z0, x1, 294, z1, "stone_limestone", faces=["+z", "-z", "+x", "-x"])
    for sz_s in (1, -1):
        az = CZ + sz_s * ARCH_MID
        b.add_barrel_arch(0.0, 178.0, az, "z", ARCH_INNER, 112.0, ARCH_HD, 12, "stone_limestone", spandrel_top_y=294.0, keystone=False)
        b.add_box(-ARCH_OUTER, 286, az - ARCH_HD, ARCH_OUTER, 294, az + ARCH_HD, "stone_ashlar")
    for sx_s in (1, -1):
        ax = sx_s * ARCH_MID
        b.add_barrel_arch(ax, 178.0, CZ, "x", ARCH_INNER, 112.0, ARCH_HD, 12, "stone_limestone", spandrel_top_y=294.0, keystone=False)
        b.add_box(ax - ARCH_HD, 286, CZ - ARCH_INNER, ax + ARCH_HD, 294, CZ + ARCH_INNER, "stone_ashlar")
    b.add_box(-228, 294, CZ - 228, 228, Y_CORNICE, CZ + 228, "stone_ashlar")

    # 4. Four Stepped Corner Piers + Art Deco Fluting Ribs + Simple Block Guardian Statues on Projecting Ledges!
    for sx in (-1, 1):
        for sz in (-1, 1):
            for (y_lo, y_hi, out_r) in ((Y_CORNICE, Y_SET1, 196.0), (Y_SET1, Y_SET2, 186.0), (Y_SET2, Y_DECK, 176.0)):
                x0, x1 = (116.0, out_r) if sx > 0 else (-out_r, -116.0)
                z0, z1 = (CZ + 116.0, CZ + out_r) if sz > 0 else (CZ - out_r, CZ - 116.0)
                b.add_box(x0, y_lo, z0, x1, y_hi, z1, "stone_limestone")
                mx, mz = (x0 + x1) * 0.5, (z0 + z1) * 0.5
                for f_off in (-20, 0, 20):
                    zf0 = z1 if sz > 0 else z0 - 3.5
                    b.add_box(mx + f_off - 2.0, y_lo, zf0, mx + f_off + 2.0, y_hi, zf0 + 3.5, "stone_limestone")
                    xf0 = x1 if sx > 0 else x0 - 3.5
                    b.add_box(xf0, y_lo, mz + f_off - 2.0, xf0 + 3.5, y_hi, mz + f_off + 2.0, "stone_limestone")
                if y_hi < Y_DECK:
                    b.add_box(x0 - 3, y_hi - 6, z0 - 3, x1 + 3, y_hi + 2, z1 + 3, "stone_ashlar")
            # Cantilevered corner ledge & unmistakable human-silhouette 8.2 m Guardian statue (y = 574..690)!
            gx = sx * 210.0
            gz = CZ + sz * 210.0
            yaw = math.atan2(sx, sz)
            _xform_box(b, gx, 574.0, gz, yaw, -28, -14, -50, 28, 0, 24, "stone_granite")
            _xform_frustum_box(b, gx, 574.0, gz, yaw, 15, 10, 8, 9.5, 7, 54, "stone_limestone")
            _xform_frustum_box(b, gx, 574.0, gz, yaw, 9.5, 7, 54, 19.5, 10, 83, "stone_limestone")
            _xform_frustum_box(b, gx, 574.0, gz, yaw, 19.5, 9, 83, 5.0, 5.0, 95, "stone_limestone")
            _xform_frustum_box(b, gx, 574.0, gz, yaw, 6.2, 6.6, 95, 4.8, 5.6, 114, "stone_ashlar")
            if sx > 0 and sz > 0:  # Learning's sky-raised torch arm silhouette!
                _xform_beam(b, gx, 574.0, gz, yaw, (18, 80, 1), (25, 128, 9), 3.0, 3.0, "bronze_gilded")

    # 5. Four Clean Top Steel Vault Arches + 18 m Armillary Sphere (Simple Solid Bands + 5 m Globe!)
    Y_ARM = 528.0
    tilt = math.radians(23.4)
    u_ec = (math.cos(tilt), math.sin(tilt), 0.0)
    v_ec = (0.0, 0.0, -1.0)
    axis_dir = (-math.sin(tilt), math.cos(tilt), 0.0)
    for sz_s in (1, -1):
        az = CZ + sz_s * 146.0
        for i in range(12):
            a0, a1 = math.pi * i / 12.0, math.pi * (i + 1) / 12.0
            b.add_beam_art((math.cos(a0) * 120, 608 + math.sin(a0) * 120, az),
                           (math.cos(a1) * 120, 608 + math.sin(a1) * 120, az), 4.0, 5.0, "steel_truss")
    for sx_s in (1, -1):
        ax = sx_s * 146.0
        for i in range(12):
            a0, a1 = math.pi * i / 12.0, math.pi * (i + 1) / 12.0
            b.add_beam_art((ax, 608 + math.sin(a0) * 120, CZ - math.cos(a0) * 120),
                           (ax, 608 + math.sin(a1) * 120, CZ - math.cos(a1) * 120), 4.0, 5.0, "steel_truss")
    # 18 m (r = 116 art px) Solid Gilded Armillary Rings (28 segs -> crisp mid silhouette!)
    b.add_ring_3d((0, Y_ARM, CZ), (0, 0, 1), (0, 1, 0), 116.0, 4.0, 2.2, 28, "bronze_gilded")
    b.add_ring_3d((0, Y_ARM, CZ), (1, 0, 0), (0, 1, 0), 110.0, 4.0, 2.2, 28, "bronze_gilded")
    b.add_ring_3d((0, Y_ARM, CZ), (1, 0, 0), (0, 0, -1), 113.0, 4.2, 2.2, 28, "bronze_gilded")
    b.add_ring_3d((0, Y_ARM, CZ), u_ec, v_ec, 96.0, 4.0, 4.5, 24, "bronze_gilded")
    for hi in range(12):
        ang = 2.0 * math.pi * hi / 12.0
        b.add_beam_art((math.cos(ang) * 116, Y_ARM, CZ + math.sin(ang) * 116),
                       (math.cos(ang) * 126, Y_ARM, CZ + math.sin(ang) * 126), 1.8, 1.8, "bronze_gilded", caps=True)
    b.add_sphere_faceted((0, Y_ARM, CZ), 32.2, 10, 16, "copper_spire")
    b.add_beam_art((axis_dir[0] * -122, Y_ARM + axis_dir[1] * -122, CZ),
                   (axis_dir[0] * 122, Y_ARM + axis_dir[1] * 122, CZ), 2.6, 2.6, "bronze_gilded")
    b.add_cylinder(0, Y_ARM + 114, 728, CZ, 4.0, 4.0, 8, "bronze_gilded")

    # 6. Crown (58-106 m): 58 m Deck + 3-Tier Stepped Octagonal Lantern + 8 Stepped Flying Buttress Fins
    #    + 16-Rib Gilded Sunburst Drum + Ribbed Copper Spire + 8-Strut Beacon Cage & Glowing Orb!
    b.add_box(-190, 724, CZ - 190, 190, Y_DECK, CZ + 190, "stone_ashlar")
    # 3 Stepped Octagonal Lantern Tiers (13 m, 10.5 m, 8 m across -> R = 84, 68, 52 art px!)
    for y_lo, y_hi, r_t in ((748, 864, 84.0), (864, 935, 68.0), (935, 980, 52.0)):
        b.add_cylinder(0, y_lo, y_hi - 8, CZ, r_t, r_t, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
        b.add_cylinder(0, y_hi - 8, y_hi, CZ, r_t + 6.0, r_t + 6.0, 8, "stone_ashlar", cap_top=True, cap_bot=True, phase=math.pi / 8.0)
    # 8 Glowing Amber Lantern Lancets on Tier 1
    for i in range(8):
        af = 2.0 * math.pi * i / 8.0
        fc, fs = math.cos(af), math.sin(af)
        tx, tz = -fs, fc
        rw = 84.5 * math.cos(math.pi / 8.0)
        b.add_planar_quad_art((fc * rw + tx * 2.5, 788, CZ + fs * rw + tz * 2.5),
                              (fc * rw - tx * 2.5, 788, CZ + fs * rw - tz * 2.5),
                              (fc * rw - tx * 2.5, 834, CZ + fs * rw - tz * 2.5),
                              (fc * rw + tx * 2.5, 834, CZ + fs * rw + tz * 2.5),
                              "glow_monument", "lantern_lancet_glow", fit_region=True)
    # 8 Stepped Flying Buttress Fins sweeping from Tier 1 corners to 58 m Deck posts!
    for i in range(8):
        ac = 2.0 * math.pi * i / 8.0 + math.pi / 8.0
        ca, sa = math.cos(ac), math.sin(ac)
        for st_i, (r_in, r_out, ys, ye) in enumerate(((80, 114, 748, 848), (110, 146, 748, 810), (142, 178, 748, 774))):
            b.add_beam_art((ca * r_in, (ys + ye) * 0.5, CZ + sa * r_in),
                           (ca * r_out, (ys + ye) * 0.5, CZ + sa * r_out), 5.5, (ye - ys) * 0.5, "stone_limestone", caps=True)
    # Integrated Gilded Sunburst Drum (y = 980..1018) with 16 vertical sunburst ribs!
    b.add_cylinder(0, 980, 1018, CZ, 45.0, 44.0, 16, "bronze_gilded", cap_top=True)
    for i in range(16):
        ar = 2.0 * math.pi * i / 16.0
        b.add_beam_art((math.cos(ar) * 46.5, 980, CZ + math.sin(ar) * 46.5),
                       (math.cos(ar) * 48.0, 1020, CZ + math.sin(ar) * 48.0), 1.8, 1.8, "bronze_gilded", caps=True)
    # Ribbed Tapered Copper Spire (y = 1018..1304, 22.2 m tall) with 4 gilded corner ribs!
    b.add_cylinder(0, 1018, 1304, CZ, 42.0, 10.0, 8, "copper_spire", cap_top=True, phase=math.pi / 8.0)
    for i in range(4):
        ar = math.pi * 0.25 + math.pi * 0.5 * i
        b.add_beam_art((math.cos(ar) * 43.0, 1018, CZ + math.sin(ar) * 43.0),
                       (math.cos(ar) * 11.0, 1304, CZ + math.sin(ar) * 11.0), 2.4, 2.4, "bronze_gilded")
    # Open 8-Strut Bronze Beacon Cage, Glowing White Orb & Finial (~106 m!)
    b.add_cylinder(0, 1302, 1307, CZ, 15.0, 15.0, 8, "bronze_gilded", cap_top=True)
    for i in range(8):
        ar = 2.0 * math.pi * i / 8.0
        b.add_beam_art((math.cos(ar) * 12.0, 1307, CZ + math.sin(ar) * 12.0),
                       (math.cos(ar) * 8.0, 1336, CZ + math.sin(ar) * 8.0), 1.2, 1.2, "bronze_gilded")
    b.add_sphere_faceted((0, 1322, CZ), 5.6, 8, 12, "glow_monument", "beacon_orb")
    b.add_cylinder(0, 1336, 1368, CZ, 3.2, 0.4, 6, "bronze_gilded")

    nodes = [{"name": "great_meridian_mid", "mesh": 0}]
    out_path = R / "great-meridian-mid.glb"
    fsize = write_glb(out_path, mid_mats, [(b, None)], nodes, scene_name="GreatMeridianMid")
    print(f"Wrote {out_path.name} ({fsize} bytes) | vis={b.tri_count()} tris")
    return {"file": out_path.name, "visible_triangles": b.tri_count(), "distance_range_m": "500-2000"}


# ==============================================================================
# FAR LANDMARK SILHOUETTE LOD: great-meridian-far.glb (< 400 TRIANGLES!)
# Matches the updated Revision 2 silhouette (3-tier crown, 18 m sphere, glowing orb)
# ==============================================================================
def build_far_lod(MeshBucket, write_glb, R, MAT_SIZE, regions):
    far_mats = [
        {"name": "stone_limestone", "base": "stone_limestone_2000.png", "roughness": 0.90},
        {"name": "stone_granite", "base": "stone_granite.png", "roughness": 0.92},
        {"name": "copper_spire", "base": "copper_2000.png", "metallic": 0.25, "roughness": 0.65},
        {"name": "bronze_gilded", "base": "bronze_gilded.png", "metallic": 0.55, "roughness": 0.45},
        {"name": "water_pool", "base": "water_pool.png", "roughness": 0.25},
        {"name": "glow_monument", "base": "glow_monument_base.png", "emissive": "glow_monument_mask.png",
         "emissiveFactor": [1.0, 1.0, 1.0], "roughness": 0.45},
    ]
    f_idx = {m["name"]: i for i, m in enumerate(far_mats)}
    b = MeshBucket("great_meridian_far", f_idx, MAT_SIZE, regions)

    CZ = -773.0
    # 1. Acropolis plinth + reflecting pool
    b.add_box(-360, 0, CZ - 360, 360, 56, CZ + 340, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-264, 56, CZ - 264, 264, 84, CZ + 264, "stone_granite", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_panel_y(-96, -346, 96, -108, 0.0, "+y", "water_pool")

    # 2. Monolithic 34.8 m Arch Block with 4 cut-through arches
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (96, 224) if sx > 0 else (-224, -96)
            z0, z1 = (CZ + 96, CZ + 224) if sz > 0 else (CZ - 224, CZ - 96)
            b.add_box(x0, 84, z0, x1, 310, z1, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
    b.add_box(-96, 256, CZ + 96, 96, 310, CZ + 224, "stone_limestone")
    b.add_box(-96, 256, CZ - 224, 96, 310, CZ - 96, "stone_limestone")
    b.add_box(96, 256, CZ - 96, 224, 310, CZ + 96, "stone_limestone")
    b.add_box(-224, 256, CZ - 96, -96, 310, CZ + 96, "stone_limestone")

    # 3. Four tall stepped corner piers (y = 310..748)
    for sx in (-1, 1):
        for sz in (-1, 1):
            x0, x1 = (116, 196) if sx > 0 else (-196, -116)
            z0, z1 = (CZ + 116, CZ + 196) if sz > 0 else (CZ - 196, CZ - 116)
            b.add_box(x0, 310, z0, x1, 460, z1, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])
            x0b, x1b = (116, 176) if sx > 0 else (-176, -116)
            z0b, z1b = (CZ + 116, CZ + 176) if sz > 0 else (CZ - 176, CZ - 116)
            b.add_box(x0b, 460, z0b, x1b, 748, z1b, "stone_limestone", faces=["+z", "-z", "+x", "-x", "+y"])

    # 4. Bold 18 m Golden Sphere silhouette suspended in open sky void!
    b.add_sphere_faceted((0, 528, CZ), 108.0, 2, 6, "bronze_gilded")

    # 5. Square 58 m Deck, 3-Tier Octagonal Crown (buttress profile!), Sunburst Drum, Spire & Glowing Beacon Orb!
    b.add_box(-190, 724, CZ - 190, 190, 748, CZ + 190, "bronze_gilded")
    b.add_cylinder(0, 748, 864, CZ, 118.0, 84.0, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 864, 935, CZ, 68.0, 68.0, 8, "stone_limestone", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 935, 1018, CZ, 52.0, 44.0, 8, "bronze_gilded", cap_top=True, phase=math.pi / 8.0)
    b.add_cylinder(0, 1018, 1304, CZ, 42.0, 10.0, 8, "copper_spire", cap_top=True, phase=math.pi / 8.0)
    b.add_box(-6.5, 1314, CZ - 6.5, 6.5, 1330, CZ + 6.5, "glow_monument", region_name="beacon_orb", fit_region=True)
    b.add_cylinder(0, 1330, 1368, CZ, 4.0, 0.4, 4, "bronze_gilded")

    nodes = [{"name": "great_meridian_far", "mesh": 0}]
    out_path = R / "great-meridian-far.glb"
    fsize = write_glb(out_path, far_mats, [(b, None)], nodes, scene_name="GreatMeridianFar")
    print(f"Wrote {out_path.name} ({fsize} bytes) | vis={b.tri_count()} tris")
    return {"file": out_path.name, "visible_triangles": b.tri_count(), "distance_range_m": "2000+"}
