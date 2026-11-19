#!/usr/bin/env python3
"""
Procedural 3D glTF 2.0 (.glb) builder for the Club Mirage 1980s Nightclub landmark.
Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- 1 texel = 0.0388 m (T = 0.0388)
- 1 art pixel = 2x2 texels = 0.0776 m (U = 0.0776)
- glTF 2.0 Y-up, metres, front entrance facing +Z, origin (0, 0, 0) at center of front building edge.
- Pure Python standard library implementation.
"""
import json
import math
import struct
from pathlib import Path

R = Path(__file__).resolve().parent
TEX_DIR = R / "textures"

T = 0.0388
U = 0.0776  # 1 art pixel = 2 texels = 0.0776 m

with open(R / "art-regions.json", "r", encoding="utf-8") as f:
    REGIONS = json.load(f)


def png_dimensions(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    w, h = struct.unpack(">II", data[16:24])
    return (w, h)


# Material definitions (12 materials, <= 12 contract limit)
MATERIAL_DEFS = [
    {
        "name": "wall_midnight",
        "file": "midnight.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "1980s fluted midnight-indigo Art-Deco stucco panels with cyan speed-rib bands",
    },
    {
        "name": "wall_slate",
        "file": "wall_slate_dark.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Dark slate stone masonry base plinth, corner piers, and perimeter privacy walls (LimeZu wall_slate_dark)",
    },
    {
        "name": "wall_block",
        "file": "block_courses.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Acoustic concrete-block rear stage shell and rooftop penthouse walls (LimeZu block_courses)",
    },
    {
        "name": "wall_brick",
        "file": "brick_red.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Rear loading dock apron masonry and service enclosure walls (LimeZu brick_red)",
    },
    {
        "name": "trim_neon",
        "file": "neon_trim.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "1980s electric magenta and cyan enameled Art-Deco ziggurat portal, canopy fascia, and pylon fins",
    },
    {
        "name": "roof_membrane",
        "file": "roof_membrane_grey.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Commercial grey membrane flat roof decking over main hall and canopy (LimeZu roof_membrane_grey)",
    },
    {
        "name": "roof_ballast",
        "file": "ballast.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Aggregate gravel roof ballast on upper skylight penthouse deck (LimeZu ballast)",
    },
    {
        "name": "ground_asphalt",
        "file": "asphalt_worn.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Nightclub parking lot and VIP drop-off drive lane asphalt (LimeZu asphalt_worn)",
    },
    {
        "name": "ground_paving",
        "file": "pavement.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Concrete front boulevard sidewalk, VIP plaza, loading dock deck, and interior floor slab (LimeZu pavement)",
    },
    {
        "name": "glass_club",
        "file": "glass.png",
        "alphaMode": "BLEND",
        "emissive": False,
        "role": "Semi-transparent 1980s illuminated glass-block bay windows, corner light columns, and VIP entry doors",
    },
    {
        "name": "glow_signage",
        "file": "signs.png",
        "alphaMode": "OPAQUE",
        "emissive": True,
        "role": "Self-lit neon marquee signage, reader board, soffit/truss downlights, light-up disco dance floor, and synthwave stage backdrop",
    },
    {
        "name": "detail_utility",
        "file": "utility.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Four-quadrant utility atlas: chrome/steel truss & bar counter, matte black speaker stacks, hot-magenta upholstery, electric-cyan paneling",
    },
]

for m in MATERIAL_DEFS:
    m["size"] = png_dimensions(TEX_DIR / m["file"])

MAT_INDEX = {m["name"]: i for i, m in enumerate(MATERIAL_DEFS)}


def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


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
    if l < 1e-12:
        return (0.0, 1.0, 0.0)
    return (a[0] / l, a[1] / l, a[2] / l)


class MeshBucket:
    """Accumulates triangles per material for a given scene graph node."""

    def __init__(self, name, parent=None, translation=None, rotation=None, extras=None):
        self.name = name
        self.parent = parent
        self.children = []
        if parent is not None:
            parent.children.append(self)
        self.translation = translation
        self.rotation = rotation
        self.extras = extras
        self.prims = {}

    def _get_prim(self, mat_name):
        if mat_name not in self.prims:
            self.prims[mat_name] = {"pos": [], "nrm": [], "uv": [], "idx": []}
        return self.prims[mat_name]

    def add_polygon(self, verts_m, mat_name, patch=None):
        if len(verts_m) < 3:
            return
        n = v_norm(v_cross(v_sub(verts_m[1], verts_m[0]), v_sub(verts_m[2], verts_m[0])))
        if len(verts_m) > 3:
            non_planar = any(abs(v_dot(v_sub(p, verts_m[0]), n)) > 1e-6 for p in verts_m[3:])
            if non_planar:
                for i in range(1, len(verts_m) - 1):
                    self.add_polygon([verts_m[0], verts_m[i], verts_m[i + 1]], mat_name, patch)
                return

        if abs(n[1]) < 0.9:
            e_u = v_norm(v_cross((0.0, 1.0, 0.0), n))
            e_v = v_norm(v_cross(n, e_u))
        else:
            e_v = v_norm(v_cross(n, (1.0, 0.0, 0.0)))
            e_u = v_norm(v_cross(e_v, n))

        W, H = MATERIAL_DEFS[MAT_INDEX[mat_name]]["size"]
        local_u = [v_dot(p, e_u) for p in verts_m]
        local_v = [v_dot(p, e_v) for p in verts_m]
        u_min = min(local_u)
        v_max = max(local_v)

        px0, py0 = 0.0, 0.0
        if patch is not None:
            r = REGIONS[patch]["rect_art"]
            px0 = r[0] * 2.0
            py0 = r[1] * 2.0

        prim = self._get_prim(mat_name)
        base_idx = len(prim["pos"]) // 3
        for p, su, sv in zip(verts_m, local_u, local_v):
            du_tex = (su - u_min) / T
            dv_tex = (v_max - sv) / T
            u_coord = (px0 + du_tex) / W
            v_coord = (py0 + dv_tex) / H
            prim["pos"].extend([p[0], p[1], p[2]])
            prim["nrm"].extend([n[0], n[1], n[2]])
            prim["uv"].extend([u_coord, v_coord])

        for i in range(1, len(verts_m) - 1):
            prim["idx"].extend([base_idx, base_idx + i, base_idx + i + 1])

    def add_panel(self, center_art, w_art, h_art, region_key, mat_name, facing="+Z"):
        r = REGIONS[region_key]["rect_art"]
        assert abs(w_art - r[2]) < 1e-5 and abs(h_art - r[3]) < 1e-5, (
            f"Panel {region_key} size mismatch: got ({w_art}, {h_art}), expected ({r[2]}, {r[3]})"
        )
        cx, cy, cz = [c * U for c in center_art]
        w = w_art * U
        h = h_art * U
        hw = w / 2.0
        hh = h / 2.0

        if facing == "+Z":
            verts = [
                (cx - hw, cy - hh, cz),
                (cx + hw, cy - hh, cz),
                (cx + hw, cy + hh, cz),
                (cx - hw, cy + hh, cz),
            ]
        elif facing == "-Z":
            verts = [
                (cx + hw, cy - hh, cz),
                (cx - hw, cy - hh, cz),
                (cx - hw, cy + hh, cz),
                (cx + hw, cy + hh, cz),
            ]
        elif facing == "+X":
            verts = [
                (cx, cy - hh, cz + hw),
                (cx, cy - hh, cz - hw),
                (cx, cy + hh, cz - hw),
                (cx, cy + hh, cz + hw),
            ]
        elif facing == "-X":
            verts = [
                (cx, cy - hh, cz - hw),
                (cx, cy - hh, cz + hw),
                (cx, cy + hh, cz + hw),
                (cx, cy + hh, cz - hw),
            ]
        elif facing == "+Y":
            verts = [
                (cx - hw, cy, cz + hh),
                (cx + hw, cy, cz + hh),
                (cx + hw, cy, cz - hh),
                (cx - hw, cy, cz - hh),
            ]
        elif facing == "-Y":
            verts = [
                (cx - hw, cy, cz - hh),
                (cx + hw, cy, cz - hh),
                (cx + hw, cy, cz + hh),
                (cx - hw, cy, cz + hh),
            ]
        else:
            raise ValueError(f"Unknown facing: {facing}")

        n = v_norm(v_cross(v_sub(verts[1], verts[0]), v_sub(verts[2], verts[0])))
        W, H = MATERIAL_DEFS[MAT_INDEX[mat_name]]["size"]
        u0 = (r[0] * 2.0) / W
        u1 = ((r[0] + r[2]) * 2.0) / W
        v0 = (r[1] * 2.0) / H
        v1 = ((r[1] + r[3]) * 2.0) / H
        uvs = [(u0, v1), (u1, v1), (u1, v0), (u0, v0)]

        prim = self._get_prim(mat_name)
        base_idx = len(prim["pos"]) // 3
        for p, uv in zip(verts, uvs):
            prim["pos"].extend([p[0], p[1], p[2]])
            prim["nrm"].extend([n[0], n[1], n[2]])
            prim["uv"].extend([uv[0], uv[1]])
        prim["idx"].extend([base_idx, base_idx + 1, base_idx + 2, base_idx, base_idx + 2, base_idx + 3])


# Scene Graph Nodes
ROOT = MeshBucket("mirage_nightclub")
STRUCTURE = MeshBucket("structure", parent=ROOT)
LOT = MeshBucket("lot", parent=ROOT)

# Optional prop groups (< 500 triangles each)
MARQUEE_PYLON = MeshBucket("marquee_pylon", parent=LOT)
SERVICE_YARD = MeshBucket("service_yard", parent=LOT)
VIP_ROPE_LINE = MeshBucket("vip_rope_line", parent=LOT)

# Gated door panel geometry nodes
DOOR_PANEL_MAIN = MeshBucket("door_panel_main", parent=STRUCTURE)
DOOR_PANEL_BACK = MeshBucket("door_panel_back", parent=STRUCTURE)
DOOR_PANEL_SIDE = MeshBucket("door_panel_side", parent=STRUCTURE)

# Collision mesh node
COLLISION_NODE = MeshBucket(
    "collision",
    parent=ROOT,
    extras={"collision_only": True, "render": False},
)

COLL_BOXES_BLENDER = []


def add_collision_box(x0_art, y0_art, z0_art, x1_art, y1_art, z1_art, group, source, attachment, gate_node=None):
    eps = 0.25
    ax0 = min(x0_art, x1_art) + eps
    ax1 = max(x0_art, x1_art) - eps
    ay0 = min(y0_art, y1_art)
    ay1 = max(y0_art, y1_art)
    az0 = min(z0_art, z1_art) + eps
    az1 = max(z0_art, z1_art) - eps

    x0, y0, z0 = ax0 * U, ay0 * U, az0 * U
    x1, y1, z1 = ax1 * U, ay1 * U, az1 * U

    c000 = (x0, y0, z0)
    c100 = (x1, y0, z0)
    c101 = (x1, y0, z1)
    c001 = (x0, y0, z1)
    c010 = (x0, y1, z0)
    c110 = (x1, y1, z0)
    c111 = (x1, y1, z1)
    c011 = (x0, y1, z1)

    faces = [
        [c001, c101, c111, c011],
        [c100, c000, c010, c110],
        [c101, c100, c110, c111],
        [c000, c001, c011, c010],
        [c011, c111, c110, c010],
        [c000, c100, c101, c001],
    ]
    for f in faces:
        COLLISION_NODE.add_polygon(f, "wall_block")

    gx = (x0 + x1) / 2.0
    gy = (y0 + y1) / 2.0
    gz = (z0 + z1) / 2.0
    gsx = x1 - x0
    gsy = y1 - y0
    gsz = z1 - z0

    entry = {
        "center": [round(gx, 4), round(-gz, 4), round(gy, 4)],
        "size": [round(gsx, 4), round(gsz, 4), round(gsy, 4)],
        "group": group,
        "source": source,
        "attachment": attachment,
    }
    if gate_node:
        entry["gate_node"] = gate_node
    COLL_BOXES_BLENDER.append(entry)


def add_box(
    node,
    x0_art,
    y0_art,
    z0_art,
    x1_art,
    y1_art,
    z1_art,
    mat_name,
    patch=None,
    faces=("+Z", "-Z", "+X", "-X", "+Y", "-Y"),
    collision=False,
    collision_group="structure",
    source="box",
    gate_node=None,
):
    ax0, ax1 = min(x0_art, x1_art), max(x0_art, x1_art)
    ay0, ay1 = min(y0_art, y1_art), max(y0_art, y1_art)
    az0, az1 = min(z0_art, z1_art), max(z0_art, z1_art)

    if collision:
        add_collision_box(ax0, ay0, az0, ax1, ay1, az1, collision_group, source, node.name, gate_node)

    max_step = 64 if mat_name == "detail_utility" else 10000

    def split_axis(a0, a1):
        pts = [a0]
        cur = a0
        while a1 - cur > max_step:
            cur += max_step
            pts.append(cur)
        pts.append(a1)
        return list(zip(pts[:-1], pts[1:]))

    xs = split_axis(ax0, ax1)
    ys = split_axis(ay0, ay1)
    zs = split_axis(az0, az1)

    for ix, (sx0, sx1) in enumerate(xs):
        for iy, (sy0, sy1) in enumerate(ys):
            for iz, (sz0, sz1) in enumerate(zs):
                x0, y0, z0 = sx0 * U, sy0 * U, sz0 * U
                x1, y1, z1 = sx1 * U, sy1 * U, sz1 * U
                if "+Z" in faces and iz == len(zs) - 1:
                    node.add_polygon([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], mat_name, patch)
                if "-Z" in faces and iz == 0:
                    node.add_polygon([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], mat_name, patch)
                if "+X" in faces and ix == len(xs) - 1:
                    node.add_polygon([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], mat_name, patch)
                if "-X" in faces and ix == 0:
                    node.add_polygon([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], mat_name, patch)
                if "+Y" in faces and iy == len(ys) - 1:
                    node.add_polygon([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], mat_name, patch)
                if "-Y" in faces and iy == 0:
                    node.add_polygon([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], mat_name, patch)


def add_beam(node, p0_art, p1_art, thickness_art, mat_name="detail_utility", patch="metal"):
    dx = p1_art[0] - p0_art[0]
    dy = p1_art[1] - p0_art[1]
    dz = p1_art[2] - p0_art[2]
    dist = math.sqrt(dx * dx + dy * dy + dz * dz)
    if dist < 1e-6:
        return
    steps = max(1, int(math.ceil(dist / 56.0)))
    for s in range(steps):
        t0 = s / steps
        t1 = (s + 1) / steps
        q0 = [p0_art[i] + (p1_art[i] - p0_art[i]) * t0 for i in range(3)]
        q1 = [p0_art[i] + (p1_art[i] - p0_art[i]) * t1 for i in range(3)]
        _add_beam_segment(node, q0, q1, thickness_art, mat_name, patch)


def _add_beam_segment(node, p0_art, p1_art, thickness_art, mat_name, patch):
    p0 = [c * U for c in p0_art]
    p1 = [c * U for c in p1_art]
    t_dir = v_norm(v_sub(p1, p0))
    up = (0.0, 1.0, 0.0) if abs(t_dir[1]) < 0.9 else (1.0, 0.0, 0.0)
    a = v_norm(v_cross(up, t_dir))
    b = v_norm(v_cross(t_dir, a))
    r = (thickness_art * U) / 2.0

    def ring(pt):
        return [
            (pt[0] - r * a[0] - r * b[0], pt[1] - r * a[1] - r * b[1], pt[2] - r * a[2] - r * b[2]),
            (pt[0] + r * a[0] - r * b[0], pt[1] + r * a[1] - r * b[1], pt[2] + r * a[2] - r * b[2]),
            (pt[0] + r * a[0] + r * b[0], pt[1] + r * a[1] + r * b[1], pt[2] + r * a[2] + r * b[2]),
            (pt[0] - r * a[0] + r * b[0], pt[1] - r * a[1] + r * b[1], pt[2] - r * a[2] + r * b[2]),
        ]

    r0 = ring(p0)
    r1 = ring(p1)
    for k in range(4):
        kn = (k + 1) % 4
        node.add_polygon([r0[k], r0[kn], r1[kn], r1[k]], mat_name, patch)
    node.add_polygon([r0[3], r0[2], r0[1], r0[0]], mat_name, patch)
    node.add_polygon([r1[0], r1[1], r1[2], r1[3]], mat_name, patch)


# ==============================================================================
# 1. LOT GROUND, BOULEVARD SIDEWALK, VIP DROP-OFF LANE & PERIMETER SLATE WALLS
#    Lot footprint: 352 x 384 art pixels (27.3152 m x 29.7984 m), all at y >= 0.
# ==============================================================================
def build_lot():
    # Main asphalt lot pad: x in [-176, 176], z in [-320, 64], y in [0, 1*U]
    add_box(LOT, -176, 0, -320, 176, 1, 64, "ground_asphalt", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Front concrete boulevard sidewalk & VIP entry promenade: x in [-148, 148], z in [-6, 48], y in [0, 2*U]
    add_box(LOT, -148, 0, -6, 148, 2, 48, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # East side exit walk & service apron
    add_box(LOT, 120, 0, -144, 148, 2, -6, "ground_paving", faces=("+Y", "+Z", "-Z", "+X"))

    # West parking lane concrete wheel stops & painted stall dividers (x in [-168, -132])
    for pz in (-200, -152, -104, -56, -8):
        add_box(LOT, -168, 1, pz - 1, -134, 2, pz + 1, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))
        add_box(LOT, -166, 1, pz + 16, -162, 3, pz + 32, "ground_paving")

    # Perimeter dark slate masonry privacy walls with electric-neon coping along West, East, and Rear lot edges
    for z0 in range(-312, 16, 64):
        z1 = min(16, z0 + 60)
        # West wall (x in [-176, -172])
        add_box(
            LOT, -176, 1, z0, -172, 26, z1, "wall_slate",
            collision=True, collision_group="lot", source=f"wall_west_{z0}"
        )
        add_box(LOT, -177, 1, z1, -171, 29, z1 + 4, "trim_neon")
        add_box(LOT, -177, 26, z0, -171, 28, z1, "trim_neon")

        # East wall (x in [172, 176])
        add_box(
            LOT, 172, 1, z0, 176, 26, z1, "wall_slate",
            collision=True, collision_group="lot", source=f"wall_east_{z0}"
        )
        add_box(LOT, 171, 1, z1, 177, 29, z1 + 4, "trim_neon")
        add_box(LOT, 171, 26, z0, 177, 28, z1, "trim_neon")

    # Rear perimeter wall segments flanking the rear alley loading zone (z in [-320, -316])
    for rx0, rx1, tag in [(-172, -88, "nw"), (88, 172, "ne")]:
        add_box(
            LOT, rx0, 1, -320, rx1, 26, -316, "wall_slate",
            collision=True, collision_group="lot", source=f"wall_north_{tag}"
        )
        add_box(LOT, rx0, 26, -321, rx1, 28, -315, "trim_neon")


# ==============================================================================
# 2. MAIN 1980S NIGHTCLUB BUILDING (CLUB MIRAGE)
#    Origin (0, 0, 0) at ground level in center of front building edge (z = 0).
#    Building footprint: x in [-120, 120] (240 art px = 18.624 m),
#                        z in [-224, 0]   (224 art px = 17.3824 m).
# ==============================================================================
def build_nightclub():
    # Interior floor slab (y in [0, 2*U])
    add_box(STRUCTURE, -116, 0, -220, 116, 2, -4, "ground_paving", faces=("+Y",))
    add_box(STRUCTURE, -20, 0, -4, 20, 2, 0, "ground_paving", faces=("+Y", "+Z"))

    # --- STEPPED ART-DECO CORNER TOWERS (x in [-122, -96] and [96, 122], z in [-28, 2], y in [2, 68]) ---
    for side_sign, tx0, tx1 in [(-1, -122, -96), (1, 96, 122)]:
        add_box(
            STRUCTURE, tx0, 2, -28, tx1, 14, 2, "wall_slate",
            collision=True, collision_group="structure", source=f"corner_tower_{side_sign}"
        )
        add_box(STRUCTURE, tx0, 14, -28, tx1, 64, 2, "wall_midnight")
        add_box(STRUCTURE, tx0 - 1, 64, -29, tx1 + 1, 68, 3, "trim_neon")
        add_box(STRUCTURE, tx0, 68, -28, tx1, 70, 2, "roof_membrane", faces=("+Y",))
        # Vertical 1980s speed-fin pilasters on the corner tower
        cx = (tx0 + tx1) // 2
        add_box(STRUCTURE, cx - 10, 2, 2, cx - 8, 64, 4, "trim_neon")
        add_box(STRUCTURE, cx + 8, 2, 2, cx + 10, 64, 4, "trim_neon")
        # Vertical illuminated glass-block light column inset into the front face of each corner tower (16x64 art px)
        STRUCTURE.add_panel((cx, 34, 2.2), 16, 64, "tower_col", "glass_club", facing="+Z")

    # --- FRONT FACADE (z in [-4, 0], x in [-96, 96]) ---
    # Central doorway opening: x in [-16, 16], y in [2, 38]
    # West front wall (x in [-96, -16]):
    add_box(
        STRUCTURE, -96, 2, -4, -16, 12, 0, "wall_slate",
        collision=True, collision_group="structure", source="front_wall_west"
    )
    add_box(STRUCTURE, -96, 12, -4, -92, 52, 0, "wall_midnight")
    add_box(STRUCTURE, -20, 12, -4, -16, 52, 0, "wall_midnight")
    add_box(STRUCTURE, -92, 44, -4, -20, 52, 0, "wall_midnight")
    # West illuminated glass-block bay window: LIVE DJ (72x32 art px = front_left)
    STRUCTURE.add_panel((-56, 28, -2), 72, 32, "front_left", "glass_club", facing="+Z")

    # East front wall (x in [16, 96]):
    add_box(
        STRUCTURE, 16, 2, -4, 96, 12, 0, "wall_slate",
        collision=True, collision_group="structure", source="front_wall_east"
    )
    add_box(STRUCTURE, 16, 12, -4, 20, 52, 0, "wall_midnight")
    add_box(STRUCTURE, 92, 12, -4, 96, 52, 0, "wall_midnight")
    add_box(STRUCTURE, 20, 44, -4, 92, 52, 0, "wall_midnight")
    # East illuminated glass-block bay window: COCKTAILS (72x32 art px = front_right)
    STRUCTURE.add_panel((56, 28, -2), 72, 32, "front_right", "glass_club", facing="+Z")

    # Central stepped Art-Deco Ziggurat Portal framing door_main (x in [-24, 24], z in [-5, 3])
    add_box(STRUCTURE, -22, 2, -5, -18, 52, 2, "trim_neon")
    add_box(STRUCTURE, 18, 2, -5, 22, 52, 2, "trim_neon")
    add_box(STRUCTURE, -18, 2, -5, -16, 48, 3, "detail_utility", patch="magenta")
    add_box(STRUCTURE, 16, 2, -5, 18, 48, 3, "detail_utility", patch="magenta")
    add_box(STRUCTURE, -16, 42, -4, 16, 52, 0, "wall_midnight")
    # Glass-block transom above main entrance (32x12 art px = transom)
    STRUCTURE.add_panel((0, 36, -2), 32, 12, "transom", "glass_club", facing="+Z")
    add_box(STRUCTURE, -18, 29, -5, 18, 31, 2, "trim_neon")

    # Gated front double-leaf glass VIP door panel (door_panel_main, x in [-16, 16], y in [2, 38], z in [-3, -1])
    add_box(
        DOOR_PANEL_MAIN, -16, 2, -3, -14, 30, -1, "trim_neon",
        collision=True, collision_group="structure", source="door_panel_main_gate", gate_node="door_panel_main"
    )
    add_box(DOOR_PANEL_MAIN, 14, 2, -3, 16, 30, -1, "trim_neon")
    DOOR_PANEL_MAIN.add_panel((0, 20, -2), 32, 36, "entry", "glass_club", facing="+Z")

    # --- SWEPT 1980S CANTILEVERED NEON CANOPY MARQUEE & ROOFTOP BEACON ---
    # Main canopy deck projecting out to z = 24 (x in [-104, 104], z in [0, 22], y in [42, 48])
    add_box(STRUCTURE, -104, 42, 0, 104, 44, 22, "wall_midnight")
    add_box(STRUCTURE, -106, 44, -2, 106, 48, 24, "trim_neon")
    add_box(STRUCTURE, -104, 48, 0, 104, 49, 22, "roof_membrane", faces=("+Y",))
    # Central projecting triangular/stepped VIP marquee proscenium (x in [-32, 32], z in [22, 30], y in [42, 56])
    add_box(STRUCTURE, -32, 42, 22, 32, 44, 28, "wall_midnight")
    add_box(STRUCTURE, -34, 44, 22, 34, 58, 30, "trim_neon")
    add_box(STRUCTURE, -32, 58, 22, 32, 59, 28, "roof_membrane", faces=("+Y",))
    # VIP ENTRY neon sign on front proscenium tip (48x12 art px = vip_sign)
    STRUCTURE.add_panel((0, 51, 30.2), 48, 12, "vip_sign", "glow_signage", facing="+Z")

    # Flanking DANCE CLUB neon fascia badges on left and right canopy wings (64x12 art px = lounge_sign)
    add_box(STRUCTURE, -90, 48, 18, -24, 62, 22, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((-57, 55, 22.2), 64, 12, "lounge_sign", "glow_signage", facing="+Z")
    add_box(STRUCTURE, 24, 48, 18, 90, 62, 22, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((57, 55, 22.2), 64, 12, "lounge_sign", "glow_signage", facing="+Z")

    # 6 downward-facing soffit lamps under the front canopy (16x4 art px = lamp, ny = -1 -> spawns real lights!)
    for lx in (-78, -46, -16, 16, 46, 78):
        STRUCTURE.add_panel((lx, 41.9, 12), 16, 4, "lamp", "glow_signage", facing="-Y")

    # Twin chrome & neon V-columns supporting outer corners of front canopy at x = -88 and x = +88, z = 18
    for sx in (-88, 88):
        add_box(
            STRUCTURE, sx - 3, 2, 15, sx + 3, 8, 21, "wall_slate",
            collision=True, collision_group="structure", source=f"canopy_pier_{sx}"
        )
        add_beam(STRUCTURE, (sx, 8, 18), (sx - 3, 42, 20), 3, "detail_utility", "metal")
        add_beam(STRUCTURE, (sx, 8, 18), (sx + 3, 42, 12), 3, "trim_neon", None)

    # Raised rooftop double-sided CLUB MIRAGE neon sign + Synthwave Sunburst crest (y in [66, 108])
    add_box(STRUCTURE, -44, 54, -14, -38, 66, -8, "trim_neon")
    add_box(STRUCTURE, 38, 54, -14, 44, 66, -8, "trim_neon")
    add_box(STRUCTURE, -58, 66, -15, 58, 86, -7, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -60, 64, -16, -58, 88, -6, "trim_neon")
    add_box(STRUCTURE, 58, 64, -16, 60, 88, -6, "trim_neon")
    add_box(STRUCTURE, -60, 86, -16, 60, 88, -6, "trim_neon")
    STRUCTURE.add_panel((0, 76, -6.8), 112, 16, "title", "glow_signage", facing="+Z")
    STRUCTURE.add_panel((0, 76, -15.2), 112, 16, "title", "glow_signage", facing="-Z")
    # Synthwave sunburst emblem crowning the rooftop sign (24x24 art px = sunburst, y in [88, 112])
    add_box(STRUCTURE, -14, 88, -14, 14, 114, -8, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -16, 88, -15, -14, 116, -7, "trim_neon")
    add_box(STRUCTURE, 14, 88, -15, 16, 116, -7, "trim_neon")
    add_box(STRUCTURE, -16, 114, -15, 16, 116, -7, "trim_neon")
    STRUCTURE.add_panel((0, 101, -7.8), 24, 24, "sunburst", "glow_signage", facing="+Z")
    STRUCTURE.add_panel((0, 101, -14.2), 24, 24, "sunburst", "glow_signage", facing="-Z")

    # --- WEST SIDE WALL (x in [-120, -116], z in [-224, -28]) ---
    add_box(
        STRUCTURE, -120, 2, -224, -116, 12, -28, "wall_slate",
        collision=True, collision_group="structure", source="west_wall"
    )
    add_box(STRUCTURE, -120, 12, -224, -116, 52, -208, "wall_midnight")
    add_box(STRUCTURE, -120, 44, -208, -116, 52, -160, "wall_midnight")
    add_box(STRUCTURE, -120, 12, -160, -116, 52, -28, "wall_midnight")
    # West glass-block lounge window: MIRAGE (48x32) at z = -184 (clear of interior back-bar at z in [-148, -50])
    STRUCTURE.add_panel((-118, 28, -184), 48, 32, "side_win", "glass_club", facing="-X")
    # Vertical neon accent fins along west wall
    for wz in (-214, -154, -96, -36):
        add_box(STRUCTURE, -122, 2, wz - 2, -120, 52, wz + 2, "trim_neon")

    # --- EAST SIDE WALL (x in [116, 120], z in [-224, -28]) ---
    # Side VIP/emergency exit doorway at z in [-120, -104], y in [2, 34] (door_side at x = 122, z = -112)
    add_box(
        STRUCTURE, 116, 2, -224, 120, 12, -120, "wall_slate",
        collision=True, collision_group="structure", source="east_wall_north"
    )
    add_box(
        STRUCTURE, 116, 2, -104, 120, 12, -28, "wall_slate",
        collision=True, collision_group="structure", source="east_wall_south"
    )
    add_box(STRUCTURE, 116, 12, -224, 120, 52, -180, "wall_midnight")
    add_box(STRUCTURE, 116, 36, -180, 120, 52, -132, "wall_midnight")
    add_box(STRUCTURE, 116, 12, -132, 120, 52, -120, "wall_midnight")
    add_box(STRUCTURE, 116, 34, -120, 120, 52, -104, "wall_midnight")
    add_box(STRUCTURE, 116, 12, -104, 120, 52, -88, "wall_midnight")
    add_box(STRUCTURE, 116, 12, -40, 120, 52, -28, "wall_midnight")
    add_box(STRUCTURE, 116, 44, -88, 120, 52, -40, "wall_midnight")
    # East glass-block windows: VIP CLUB (48x24) at z = -156 and MIRAGE (48x32) at z = -64
    STRUCTURE.add_panel((118, 24, -156), 48, 24, "vip_win", "glass_club", facing="+X")
    STRUCTURE.add_panel((118, 28, -64), 48, 32, "side_win", "glass_club", facing="+X")
    # East doorway frame & gated door panel (door_panel_side)
    add_box(STRUCTURE, 115, 2, -122, 121, 36, -120, "trim_neon")
    add_box(STRUCTURE, 115, 2, -104, 121, 36, -102, "trim_neon")
    add_box(STRUCTURE, 115, 34, -122, 121, 36, -102, "trim_neon")
    add_box(
        DOOR_PANEL_SIDE, 117, 2, -120, 119, 34, -104, "detail_utility", patch="metal",
        collision=True, collision_group="structure", source="door_panel_side_gate", gate_node="door_panel_side"
    )

    # --- REAR WALL & TOURING BAND LOADING DOCK (z in [-224, -220], x in [-120, 120]) ---
    # Rear stage doorway at x in [-80, -64], y in [2, 34] (door_back at x = -72, z = -226)
    add_box(
        STRUCTURE, -120, 2, -224, -80, 52, -220, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_west"
    )
    add_box(
        STRUCTURE, -64, 2, -224, 120, 52, -220, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_east"
    )
    add_box(STRUCTURE, -80, 34, -224, -64, 52, -220, "wall_block")
    add_box(STRUCTURE, -82, 2, -225, -80, 36, -219, "trim_neon")
    add_box(STRUCTURE, -64, 2, -225, -62, 36, -219, "trim_neon")
    add_box(STRUCTURE, -82, 34, -225, -62, 36, -219, "trim_neon")
    # Gated rear stage door panel (door_panel_back)
    add_box(
        DOOR_PANEL_BACK, -80, 2, -223, -64, 34, -221, "detail_utility", patch="metal",
        collision=True, collision_group="structure", source="door_panel_back_gate", gate_node="door_panel_back"
    )

    # Raised concrete touring-gear loading dock platform (x in [-44, 44], z in [-244, -224], y in [1, 10])
    add_box(
        STRUCTURE, -44, 1, -244, 44, 8, -224, "wall_brick",
        collision=True, collision_group="structure", source="loading_dock_base"
    )
    add_box(STRUCTURE, -46, 8, -246, 46, 10, -224, "ground_paving")
    # Rubber truck dock bumpers on -Z face of loading dock
    for bx in (-36, -12, 12, 36):
        add_box(STRUCTURE, bx - 4, 2, -248, bx + 4, 9, -246, "detail_utility", patch="rubber")
    # Concrete steps on east end of loading dock (x in [46, 62], z in [-242, -228])
    add_box(STRUCTURE, 46, 1, -242, 54, 7, -228, "ground_paving")
    add_box(STRUCTURE, 54, 1, -242, 62, 4, -228, "ground_paving")

    # Steel roll-up acoustic freight door above loading dock (x in [-24, 24], y in [10, 42], z in [-226, -224])
    add_box(STRUCTURE, -26, 10, -226, 26, 44, -224, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -24, 10, -226.5, 24, 42, -224.5, "detail_utility", patch="metal")
    # Protective canopy hood & downlight over rear loading dock
    add_box(STRUCTURE, -46, 44, -242, 46, 47, -224, "trim_neon")
    add_box(STRUCTURE, -44, 47, -240, 44, 48, -224, "roof_membrane", faces=("+Y",))
    STRUCTURE.add_panel((0, 43.9, -233), 16, 4, "lamp", "glow_signage", facing="-Y")

    # Rear stage-door DANCE CLUB neon sign (64x12 art px = lounge_sign) and rear rooftop CLUB MIRAGE banner (112x16)
    add_box(STRUCTURE, -106, 38, -227, -38, 52, -224, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((-72, 45, -227.2), 64, 12, "lounge_sign", "glow_signage", facing="-Z")

    # Twin galvanized HVAC exhaust risers on rear wall (x = 76 and x = 96) feeding rooftop chiller penthouse
    for dx in (76, 96):
        add_box(STRUCTURE, dx - 4, 14, -230, dx + 4, 62, -224, "detail_utility", patch="metal")
        add_box(STRUCTURE, dx - 5, 28, -231, dx + 5, 30, -224, "detail_utility", patch="rubber")
        add_box(STRUCTURE, dx - 5, 46, -231, dx + 5, 48, -224, "detail_utility", patch="rubber")

    # --- ROOF DECK, PARAPETS & STEPPED DANCE-HALL SKYLIGHT PENTHOUSE ---
    add_box(STRUCTURE, -120, 50, -224, 120, 52, 0, "wall_midnight")
    add_box(STRUCTURE, -122, 52, -226, 122, 56, 2, "trim_neon")
    add_box(STRUCTURE, -118, 52, -222, 118, 57, -2, "roof_membrane", faces=("+Y",))

    # Central stepped Art-Deco skylight penthouse over the dance floor (x in [-56, 56], z in [-164, -64], y in [57, 74])
    add_box(STRUCTURE, -56, 57, -164, 56, 68, -64, "wall_block")
    add_box(STRUCTURE, -58, 68, -166, 58, 72, -62, "trim_neon")
    add_box(STRUCTURE, -54, 72, -162, 54, 74, -66, "roof_ballast", faces=("+Y",))
    # Rear rooftop CLUB MIRAGE neon sign mounted on the north face of the penthouse (112x16 art px = title)
    add_box(STRUCTURE, -58, 72, -168, 58, 92, -162, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -60, 92, -169, 60, 94, -161, "trim_neon")
    STRUCTURE.add_panel((0, 82, -168.2), 112, 16, "title", "glow_signage", facing="-Z")

    # Rooftop mechanical blower unit behind penthouse (x in [58, 106], z in [-216, -180], y in [57, 70])
    add_box(STRUCTURE, 58, 57, -216, 106, 68, -180, "detail_utility", patch="metal")
    add_box(STRUCTURE, 57, 68, -217, 107, 70, -179, "detail_utility", patch="rubber")

    # --- INTERIOR 1980S NIGHTCLUB SHELL (Dance Floor, Lighting Truss, DJ Stage, Neon Bar & VIP Booths) ---
    # Interior ceiling soffit (y in [50, 52])
    add_box(STRUCTURE, -116, 50, -220, 116, 52, -4, "detail_utility", patch="cyan", faces=("-Y",))

    # 1. Illuminated 1980s Light-Up Disco / Synthwave Checkerboard Dance Floor (96x64 art px = dance_floor)
    # Centered in the main hall: x in [-48, 48], z in [-144, -80], y in [2, 3]
    add_box(STRUCTURE, -52, 2, -148, 52, 3, -76, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((0, 3.1, -112), 96, 64, "dance_floor", "glow_signage", facing="+Y")

    # 2. Overhead Chrome Concert Lighting Truss Grid above the dance floor (y in [42, 45])
    add_box(STRUCTURE, -50, 42, -146, 50, 44, -144, "detail_utility", patch="metal")
    add_box(STRUCTURE, -50, 42, -80, 50, 44, -78, "detail_utility", patch="metal")
    add_box(STRUCTURE, -50, 42, -144, -48, 44, -80, "detail_utility", patch="metal")
    add_box(STRUCTURE, 48, 42, -144, 50, 44, -80, "detail_utility", patch="metal")
    add_box(STRUCTURE, -48, 42, -113, 48, 44, -111, "detail_utility", patch="metal")
    # 8 downward-facing club spotlights on the ceiling & truss rig (16x4 art px = lamp, ny = -1)
    for lx in (-72, -24, 24, 72):
        for lz in (-64, -144):
            STRUCTURE.add_panel((lx, 49.9, lz), 16, 4, "lamp", "glow_signage", facing="-Y")

    # 3. Elevated Rear DJ Booth, Dual Speaker Stacks & Synthwave Laser-Grid Stage Backdrop (z in [-218, -168])
    # Raised stage platform (x in [-76, 76], z in [-218, -168], y in [2, 10])
    add_box(
        STRUCTURE, -76, 2, -218, 76, 8, -168, "detail_utility", patch="rubber",
        collision=True, collision_group="structure", source="dj_stage_platform"
    )
    add_box(STRUCTURE, -76, 8, -218, 76, 10, -168, "trim_neon")
    # Built-in DJ Synthesizer & Turntable Console shell (x in [-36, 36], z in [-190, -176], y in [10, 24])
    add_box(STRUCTURE, -36, 10, -190, 36, 22, -176, "detail_utility", patch="magenta")
    add_box(STRUCTURE, -38, 22, -192, 38, 24, -174, "detail_utility", patch="metal")
    # Tinted SOUND BOOTH acoustic glass shield on front of DJ console (72x16 art px = dj_glass)
    STRUCTURE.add_panel((0, 26, -173.8), 72, 16, "dj_glass", "glass_club", facing="+Z")
    # Flanking floor-to-ceiling Dual Bass-Reflex Concert Speaker Stacks (x in [-74, -54] and [54, 74], y in [10, 46])
    for sx0, sx1 in [(-74, -54), (54, 74)]:
        add_box(STRUCTURE, sx0, 10, -212, sx1, 46, -192, "detail_utility", patch="rubber")
        add_box(STRUCTURE, sx0 + 2, 12, -191.8, sx1 - 2, 26, -191, "detail_utility", patch="metal")
        add_box(STRUCTURE, sx0 + 2, 28, -191.8, sx1 - 2, 42, -191, "detail_utility", patch="metal")
    # Glowing 128x40 CLUB MIRAGE Synthwave Sunset & Laser Grid Stage Backdrop on rear stage wall!
    STRUCTURE.add_panel((0, 30, -217.8), 128, 40, "stage_backdrop", "glow_signage", facing="+Z")

    # 4. Built-in Fixed Neon Cocktail Bar Counter Shell along West Wall (x in [-114, -76], z in [-156, -44])
    add_box(
        STRUCTURE, -88, 2, -152, -76, 14, -48, "detail_utility", patch="magenta",
        collision=True, collision_group="structure", source="cocktail_bar_counter"
    )
    add_box(STRUCTURE, -90, 14, -154, -74, 16, -46, "detail_utility", patch="metal")
    add_box(
        STRUCTURE, -114, 2, -152, -88, 14, -142, "detail_utility", patch="magenta",
        collision=True, collision_group="structure", source="cocktail_bar_north_return"
    )
    add_box(
        STRUCTURE, -114, 2, -58, -88, 14, -48, "detail_utility", patch="magenta",
        collision=True, collision_group="structure", source="cocktail_bar_south_return"
    )
    # Illuminated Back-Bar Neon Mirror & Bottle Display on west interior wall (96x24 art px = back_bar)
    add_box(STRUCTURE, -116, 18, -148, -112, 44, -50, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((-111.8, 31, -99), 96, 24, "back_bar", "glow_signage", facing="+X")

    # 5. Built-in Fixed VIP Banquette Lounge Booths along East Interior Wall (x in [82, 114], z in [-96, -36])
    for bz in (-92, -62):
        add_box(
            STRUCTURE, 84, 2, bz, 114, 14, bz + 20, "detail_utility", patch="magenta",
            collision=True, collision_group="structure", source=f"vip_banquette_{bz}"
        )
        add_box(STRUCTURE, 84, 14, bz, 114, 22, bz + 4, "detail_utility", patch="cyan")
        add_box(STRUCTURE, 84, 14, bz + 16, 114, 22, bz + 20, "detail_utility", patch="cyan")


# ==============================================================================
# 3. OPTIONAL PROP GROUPS (< 500 triangles each)
#    - marquee_pylon: 1980s Miami-Synthwave roadside neon pylon (11.33 m tall)
#    - service_yard: Fenced east chiller, transformer & dumpster enclosure
#    - vip_rope_line: Chrome velvet-rope stanchions & lit VIP podium along front plaza
# ==============================================================================
def build_marquee_pylon():
    # Placed at front-right corner of lot: x = 132, z = 34, total height = 146*U = 11.3296 m (<= 12.0 m)
    cx, cz = 132, 34
    add_box(
        MARQUEE_PYLON, cx - 20, 1, cz - 12, cx + 20, 12, cz + 12, "wall_slate",
        collision=True, collision_group="lot", source="marquee_pylon_base"
    )
    add_box(MARQUEE_PYLON, cx - 22, 12, cz - 14, cx + 22, 15, cz + 14, "trim_neon")

    # Twin 1980s midnight-indigo & neon-trimmed pylon columns rising to y = 120
    add_box(MARQUEE_PYLON, cx - 14, 15, cz - 4, cx - 6, 120, cz + 4, "wall_midnight")
    add_box(MARQUEE_PYLON, cx + 6, 15, cz - 4, cx + 14, 120, cz + 4, "wall_midnight")
    # Stepped magenta & cyan horizontal speed-fins
    for fy in (28, 36, 84, 92):
        add_box(MARQUEE_PYLON, cx - 24, fy, cz - 2, cx + 24, fy + 3, cz + 2, "trim_neon")

    # Lower LIVE DJ 86 / SYNTH WAVE double-sided reader board cabinet (72x24 art px = reader)
    add_box(MARQUEE_PYLON, cx - 38, 46, cz - 6, cx + 38, 74, cz + 6, "detail_utility", patch="rubber")
    add_box(MARQUEE_PYLON, cx - 40, 44, cz - 7, cx + 40, 46, cz + 7, "trim_neon")
    add_box(MARQUEE_PYLON, cx - 40, 74, cz - 7, cx + 40, 76, cz + 7, "trim_neon")
    MARQUEE_PYLON.add_panel((cx, 60, cz + 6.5), 72, 24, "reader", "glow_signage", facing="+Z")
    MARQUEE_PYLON.add_panel((cx, 60, cz - 6.5), 72, 24, "reader", "glow_signage", facing="-Z")

    # Upper MIRAGE neon header cabinet (72x16 art px = pylon_top)
    add_box(MARQUEE_PYLON, cx - 38, 98, cz - 5, cx + 38, 118, cz + 5, "detail_utility", patch="rubber")
    add_box(MARQUEE_PYLON, cx - 40, 96, cz - 6, cx + 40, 98, cz + 6, "trim_neon")
    add_box(MARQUEE_PYLON, cx - 40, 118, cz - 6, cx + 40, 120, cz + 6, "trim_neon")
    MARQUEE_PYLON.add_panel((cx, 108, cz + 5.5), 72, 16, "pylon_top", "glow_signage", facing="+Z")
    MARQUEE_PYLON.add_panel((cx, 108, cz - 5.5), 72, 16, "pylon_top", "glow_signage", facing="-Z")

    # Crown 1980s Synthwave Sunburst badge (24x24 art px = sunburst), y in [120, 146] (146*U = 11.3296 m)
    add_box(MARQUEE_PYLON, cx - 13, 120, cz - 3, cx + 13, 146, cz + 3, "detail_utility", patch="rubber")
    add_box(MARQUEE_PYLON, cx - 15, 120, cz - 4, cx - 13, 146, cz + 4, "trim_neon")
    add_box(MARQUEE_PYLON, cx + 13, 120, cz - 4, cx + 15, 146, cz + 4, "trim_neon")
    MARQUEE_PYLON.add_panel((cx, 133, cz + 3.5), 24, 24, "sunburst", "glow_signage", facing="+Z")
    MARQUEE_PYLON.add_panel((cx, 133, cz - 3.5), 24, 24, "sunburst", "glow_signage", facing="-Z")


def build_service_yard():
    # Fenced mechanical chiller & dumpster enclosure on east side: x in [120, 164], z in [-212, -148]
    add_box(SERVICE_YARD, 120, 1, -212, 164, 2, -148, "ground_paving", faces=("+Y", "+Z", "-Z", "+X"))
    add_box(
        SERVICE_YARD, 160, 2, -212, 164, 26, -148, "wall_slate",
        collision=True, collision_group="lot", source="service_yard_east_wall"
    )
    add_box(
        SERVICE_YARD, 120, 2, -212, 160, 26, -208, "wall_slate",
        collision=True, collision_group="lot", source="service_yard_north_wall"
    )
    add_box(
        SERVICE_YARD, 138, 2, -152, 160, 26, -148, "wall_slate",
        collision=True, collision_group="lot", source="service_yard_south_wall"
    )
    add_box(SERVICE_YARD, 159, 26, -213, 165, 28, -147, "trim_neon")
    add_box(SERVICE_YARD, 120, 26, -213, 165, 28, -207, "trim_neon")

    # Twin heavy-duty acoustic HVAC chiller units for the dance hall
    for cz in (-198, -176):
        add_box(
            SERVICE_YARD, 122, 2, cz, 138, 20, cz + 16, "detail_utility", patch="metal",
            collision=True, collision_group="lot", source=f"hvac_chiller_{cz}"
        )
        add_box(SERVICE_YARD, 123, 20, cz + 1, 137, 22, cz + 15, "detail_utility", patch="rubber")

    # Commercial steel waste bin
    add_box(
        SERVICE_YARD, 144, 2, -202, 158, 16, -184, "detail_utility", patch="metal",
        collision=True, collision_group="lot", source="club_dumpster"
    )
    add_box(SERVICE_YARD, 143, 16, -203, 159, 18, -183, "detail_utility", patch="rubber")


def build_vip_rope_line():
    """
    Builds the 1980s chrome stanchion & magenta velvet-rope queue barrier along the east front
    sidewalk (x in [28, 84], z = 14) — keeping x = 0 completely clear for door_main!
    """
    post_xs = [28, 46, 64, 82]
    for i, px in enumerate(post_xs):
        # Chrome base & vertical post
        add_box(VIP_ROPE_LINE, px - 2, 2, 12, px + 2, 3, 16, "detail_utility", patch="metal")
        add_box(VIP_ROPE_LINE, px - 1, 3, 13, px + 1, 14, 15, "detail_utility", patch="metal")
        if i < len(post_xs) - 1:
            nx = post_xs[i + 1]
            # Magenta velvet rope between stanchions
            add_box(VIP_ROPE_LINE, px + 1, 10, 13.5, nx - 1, 12, 14.5, "detail_utility", patch="magenta")

    # Bouncer VIP podium with glowing neon badge at x = 24, z = 14
    add_box(
        VIP_ROPE_LINE, 20, 2, 10, 26, 16, 18, "detail_utility", patch="rubber",
        collision=True, collision_group="lot", source="vip_bouncer_podium"
    )
    add_box(VIP_ROPE_LINE, 19, 16, 9, 27, 18, 19, "trim_neon")


# ==============================================================================
# 4. DOORS (Empty leaf nodes with exact +Z outward local orientation)
# ==============================================================================
def build_doors():
    # 1. door_main: front center VIP entrance at (0, 0, 2*U), facing +Z ([0, 0, 0, 1])
    MeshBucket(
        "door_main",
        parent=ROOT,
        translation=[0.0, 0.0, round(2 * U, 4)],
        rotation=[0.0, 0.0, 0.0, 1.0],
        extras={"interaction": "Go inside"},
    )
    # 2. door_back: rear performer stage door at (-72*U, 0, -226*U), facing -Z ([0, 1, 0, 0])
    MeshBucket(
        "door_back",
        parent=ROOT,
        translation=[round(-72 * U, 4), 0.0, round(-226 * U, 4)],
        rotation=[0.0, 1.0, 0.0, 0.0],
        extras={"interaction": "Go inside"},
    )
    # 3. door_side: east side VIP exit door at (122*U, 0, -112*U), facing +X ([0, +sin(pi/4), 0, cos(pi/4)])
    s45 = round(math.sin(math.pi / 4.0), 7)
    c45 = round(math.cos(math.pi / 4.0), 7)
    MeshBucket(
        "door_side",
        parent=ROOT,
        translation=[round(122 * U, 4), 0.0, round(-112 * U, 4)],
        rotation=[0.0, s45, 0.0, c45],
        extras={"interaction": "Go inside"},
    )


# ==============================================================================
# 5. BINARY GLTF 2.0 (.GLB) PACKAGER & MODEL-INFO.JSON EXPORTER
# ==============================================================================
def export_glb_and_info():
    all_nodes = []

    def collect_nodes(n):
        all_nodes.append(n)
        for c in n.children:
            collect_nodes(c)

    collect_nodes(ROOT)
    node_to_idx = {n: i for i, n in enumerate(all_nodes)}

    bin_chunks = []
    buffer_views = []
    accessors = []
    images = []
    textures = []
    materials = []
    meshes = []
    gltf_nodes = []

    def append_bin(data_bytes, target=None):
        pad = (4 - (len(data_bytes) % 4)) % 4
        offset = sum(len(c) for c in bin_chunks)
        bin_chunks.append(data_bytes + (b"\x00" * pad))
        bv = {"buffer": 0, "byteOffset": offset, "byteLength": len(data_bytes)}
        if target is not None:
            bv["target"] = target
        bv_idx = len(buffer_views)
        buffer_views.append(bv)
        return bv_idx

    for i, mdef in enumerate(MATERIAL_DEFS):
        png_bytes = (TEX_DIR / mdef["file"]).read_bytes()
        bv_idx = append_bin(png_bytes)
        images.append({"name": mdef["name"], "mimeType": "image/png", "bufferView": bv_idx})
        textures.append({"sampler": 0, "source": i})
        mat_obj = {
            "name": mdef["name"],
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": i},
                "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
                "metallicFactor": 0.0,
                "roughnessFactor": 1.0,
            },
            "alphaMode": mdef["alphaMode"],
            "doubleSided": mdef["alphaMode"] == "BLEND",
        }
        if mdef["emissive"]:
            mat_obj["emissiveTexture"] = {"index": i}
            mat_obj["emissiveFactor"] = [0.2, 0.2, 0.2]
        materials.append(mat_obj)

    visible_tris = 0
    collision_tris = 0
    node_tri_counts = {}

    for n in all_nodes:
        node_obj = {"name": n.name}
        if n.children:
            node_obj["children"] = [node_to_idx[c] for c in n.children]
        if n.translation is not None:
            node_obj["translation"] = n.translation
        if n.rotation is not None:
            node_obj["rotation"] = n.rotation
        if n.extras is not None:
            node_obj["extras"] = n.extras

        n_tris = 0
        if n.prims:
            gltf_prims = []
            for mat_name, prim in n.prims.items():
                if not prim["idx"]:
                    continue
                pos = prim["pos"]
                nrm = prim["nrm"]
                uv = prim["uv"]
                idx = prim["idx"]
                v_count = len(pos) // 3
                t_count = len(idx) // 3
                n_tris += t_count

                pos_bytes = struct.pack(f"<{len(pos)}f", *pos)
                xs = pos[0::3]
                ys = pos[1::3]
                zs = pos[2::3]
                pos_bv = append_bin(pos_bytes, target=34962)
                pos_acc = len(accessors)
                accessors.append({
                    "bufferView": pos_bv,
                    "componentType": 5126,
                    "count": v_count,
                    "type": "VEC3",
                    "min": [min(xs), min(ys), min(zs)],
                    "max": [max(xs), max(ys), max(zs)],
                })

                nrm_bytes = struct.pack(f"<{len(nrm)}f", *nrm)
                nrm_bv = append_bin(nrm_bytes, target=34962)
                nrm_acc = len(accessors)
                accessors.append({
                    "bufferView": nrm_bv,
                    "componentType": 5126,
                    "count": v_count,
                    "type": "VEC3",
                })

                uv_bytes = struct.pack(f"<{len(uv)}f", *uv)
                uv_bv = append_bin(uv_bytes, target=34962)
                uv_acc = len(accessors)
                accessors.append({
                    "bufferView": uv_bv,
                    "componentType": 5126,
                    "count": v_count,
                    "type": "VEC2",
                })

                idx_bytes = struct.pack(f"<{len(idx)}I", *idx)
                idx_bv = append_bin(idx_bytes, target=34963)
                idx_acc = len(accessors)
                accessors.append({
                    "bufferView": idx_bv,
                    "componentType": 5125,
                    "count": len(idx),
                    "type": "SCALAR",
                })

                gltf_prims.append({
                    "attributes": {
                        "POSITION": pos_acc,
                        "NORMAL": nrm_acc,
                        "TEXCOORD_0": uv_acc,
                    },
                    "indices": idx_acc,
                    "material": MAT_INDEX[mat_name],
                    "mode": 4,
                })

            if gltf_prims:
                mesh_idx = len(meshes)
                meshes.append({"name": f"{n.name}_mesh", "primitives": gltf_prims})
                node_obj["mesh"] = mesh_idx

        node_tri_counts[n.name] = n_tris
        if n.name == "collision":
            collision_tris += n_tris
        else:
            visible_tris += n_tris

        gltf_nodes.append(node_obj)

    def subtree_tris(node):
        total = node_tri_counts.get(node.name, 0)
        for c in node.children:
            total += subtree_tris(c)
        return total

    gltf_json = {
        "asset": {
            "version": "2.0",
            "generator": "Club Mirage 1980s Nightclub Procedural Builder (LANDMARKS_GLB contract)",
        },
        "scene": 0,
        "scenes": [{"name": "Scene", "nodes": [0]}],
        "nodes": gltf_nodes,
        "meshes": meshes,
        "materials": materials,
        "textures": textures,
        "images": images,
        "samplers": [
            {
                "magFilter": 9728,
                "minFilter": 9984,
                "wrapS": 10497,
                "wrapT": 10497,
            }
        ],
        "bufferViews": buffer_views,
        "accessors": accessors,
        "buffers": [{"byteLength": sum(len(c) for c in bin_chunks)}],
    }

    json_bytes = json.dumps(gltf_json, separators=(",", ":")).encode("utf-8")
    json_pad = (4 - (len(json_bytes) % 4)) % 4
    json_chunk = json_bytes + (b" " * json_pad)

    bin_blob = b"".join(bin_chunks)
    bin_pad = (4 - (len(bin_blob) % 4)) % 4
    bin_chunk = bin_blob + (b"\x00" * bin_pad)

    total_len = 12 + 8 + len(json_chunk) + 8 + len(bin_chunk)
    glb_header = struct.pack("<III", 0x46546C67, 2, total_len)
    json_hdr = struct.pack("<II", len(json_chunk), 0x4E4F534A)
    bin_hdr = struct.pack("<II", len(bin_chunk), 0x004E4942)

    glb_path = R / "mirage-nightclub.glb"
    with open(glb_path, "wb") as f:
        f.write(glb_header)
        f.write(json_hdr)
        f.write(json_chunk)
        f.write(bin_hdr)
        f.write(bin_chunk)

    model_info = {
        "name": "Club Mirage",
        "slug": "mirage-nightclub",
        "era": "1980s",
        "Category": "landmark_entertainment",
        "role": "nightclub",
        "units": "metres",
        "up_axis": "Y",
        "front_axis": "+Z",
        "origin": "Ground level (y = 0) at horizontal center of the front nightclub facade edge (z = 0)",
        "pixel_grid": {
            "art_pixel_m": U,
            "art_pixel_texels": 2,
            "texel_m": T,
        },
        "footprint_m": {
            "building_width": round(240 * U, 4),
            "building_depth": round(224 * U, 4),
            "lot_width": round(352 * U, 4),
            "lot_depth": round(384 * U, 4),
            "max_height": round(146 * U, 4),
        },
        "triangles": {
            "visible": visible_tris,
            "collision": collision_tris,
            "total": visible_tris + collision_tris,
            "budget_visible_max": 12000,
        },
        "optional_prop_groups": {
            "lot": subtree_tris(LOT),
            "marquee_pylon": subtree_tris(MARQUEE_PYLON),
            "service_yard": subtree_tris(SERVICE_YARD),
            "vip_rope_line": subtree_tris(VIP_ROPE_LINE),
            "door_panel_main": subtree_tris(DOOR_PANEL_MAIN),
            "door_panel_back": subtree_tris(DOOR_PANEL_BACK),
            "door_panel_side": subtree_tris(DOOR_PANEL_SIDE),
        },
        "doors": {
            "door_main": {
                "position": [0.0, 0.0, round(2 * U, 4)],
                "facing": "+Z",
                "panel_node": "door_panel_main",
            },
            "door_back": {
                "position": [round(-72 * U, 4), 0.0, round(-226 * U, 4)],
                "facing": "-Z",
                "panel_node": "door_panel_back",
            },
            "door_side": {
                "position": [round(122 * U, 4), 0.0, round(-112 * U, 4)],
                "facing": "+X",
                "panel_node": "door_panel_side",
            },
        },
        "materials": [
            {
                "name": m["name"],
                "texture": f"textures/{m['file']}",
                "size": list(m["size"]),
                "alphaMode": m["alphaMode"],
                "emissive": m["emissive"],
                "role": m["role"],
            }
            for m in MATERIAL_DEFS
        ],
        "collision": {
            "node": "collision",
            "boxes_count": len(COLL_BOXES_BLENDER),
            "boxes_blender_z_up": COLL_BOXES_BLENDER,
        },
        "open_questions_and_risks": [
            "Door panels (door_panel_main, door_panel_back, door_panel_side) are visible and collidable by default; hide the panel node and drop its gated collision box when an interaction opens a doorway.",
            "The illuminated 1980s checkerboard dance floor (glow_signage dance_floor) sits at y = 0.24 m (3.1 art pixels) in the center of the hall; loose tables and chairs remain driven by the game furniture system.",
            "Optional prop groups (marquee_pylon, service_yard, vip_rope_line) can be hidden independently along with their tagged collision boxes.",
        ],
        "swatches_used": [
            "wall_slate_dark",
            "block_courses",
            "brick_red",
            "roof_membrane_grey",
            "ballast",
            "asphalt_worn",
            "pavement",
        ],
        "licence": "LimeZu swatches: Timerpg game use only, credit required. Original mesh and supplemental pixel artwork.",
    }

    with open(R / "model-info.json", "w", encoding="utf-8") as f:
        json.dump(model_info, f, indent=2)
        f.write("\n")

    print(
        json.dumps(
            {
                "glb": str(glb_path.name),
                "bytes": glb_path.stat().st_size,
                "visible_triangles": visible_tris,
                "collision_triangles": collision_tris,
                "materials": len(MATERIAL_DEFS),
                "collision_boxes": len(COLL_BOXES_BLENDER),
            }
        )
    )


def main():
    build_lot()
    build_nightclub()
    build_marquee_pylon()
    build_service_yard()
    build_vip_rope_line()
    build_doors()
    export_glb_and_info()


if __name__ == "__main__":
    main()
