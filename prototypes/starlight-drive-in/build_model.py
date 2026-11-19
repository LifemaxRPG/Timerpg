#!/usr/bin/env python3
"""
Procedural 3D glTF 2.0 (.glb) builder for the Starlight Drive-In Theater landmark.
Follows docs/CHATGPT_MODEL_GUIDE.md and kotlin-street/docs/LANDMARKS_GLB.md:
- 1 texel = 0.0388 m (T = 0.0388)
- 1 art pixel = 2x2 texels = 0.0776 m (U = 0.0776)
- glTF 2.0 Y-up, metres, front entrance facing +Z, origin (0, 0, 0) at center of front building edge.
- Pure Python standard library implementation (no external Blender/numpy required).
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
        "name": "wall_cream",
        "file": "cream.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Sun-warmed cream painted stucco and porcelain fascia panels",
    },
    {
        "name": "wall_brick",
        "file": "brick_red.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Red running-bond masonry wainscoting, piers, and planter bases (LimeZu brick_red swatch)",
    },
    {
        "name": "wall_block",
        "file": "block_courses.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Painted concrete block projection wing and screen tower vault base (LimeZu block_courses swatch)",
    },
    {
        "name": "wall_wood_siding",
        "file": "plank_vertical.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Weathered timber cladding on screen tower rear backing and perimeter acoustic light-shield fence (LimeZu plank_vertical)",
    },
    {
        "name": "trim_teal",
        "file": "teal.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "1950s Googie turquoise/teal enameled structural steel fascia, pylon fins, and canopy trim",
    },
    {
        "name": "roof_tin_blue",
        "file": "roof_tin_blue.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Blue ribbed metal roof decking over canopy, screen crown, and projection booth (LimeZu roof_tin_blue)",
    },
    {
        "name": "roof_ballast",
        "file": "ballast.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Aggregate gravel roof ballast on main flat roof deck (LimeZu ballast)",
    },
    {
        "name": "ground_asphalt",
        "file": "asphalt_worn.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Compacted dark drive-in lot asphalt and tiered viewing berm ramps (LimeZu asphalt_worn)",
    },
    {
        "name": "ground_paving",
        "file": "pavement.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Concrete front sidewalk, ticket island, and central pedestrian plaza walk (LimeZu pavement)",
    },
    {
        "name": "glass_concession",
        "file": "glass.png",
        "alphaMode": "BLEND",
        "emissive": False,
        "role": "Semi-transparent concession storefront, ticket booth, and optical projection port glazing",
    },
    {
        "name": "glow_signage",
        "file": "signs.png",
        "alphaMode": "OPAQUE",
        "emissive": True,
        "role": "Self-lit neon marquee signage, NOW SHOWING reader board, soffit downlights, and drive-in feature screen",
    },
    {
        "name": "detail_utility",
        "file": "utility.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Four-quadrant utility atlas: brushed steel trusses/speakers, dark rubber/tar, coral accent, interior cream",
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
        # material_name -> {"pos": [], "nrm": [], "uv": [], "idx": []}
        self.prims = {}

    def _get_prim(self, mat_name):
        if mat_name not in self.prims:
            self.prims[mat_name] = {"pos": [], "nrm": [], "uv": [], "idx": []}
        return self.prims[mat_name]

    def add_polygon(self, verts_m, mat_name, patch=None):
        """
        Adds a planar polygon (3 or more vertices in metres, CCW winding) with exact
        physical pixel-grid UVs (1 texel = T = 0.0388 m).
        """
        if len(verts_m) < 3:
            return
        n = v_norm(v_cross(v_sub(verts_m[1], verts_m[0]), v_sub(verts_m[2], verts_m[0])))
        # Check planarity for quads/ngons; if non-planar, split into triangles
        if len(verts_m) > 3:
            non_planar = any(abs(v_dot(v_sub(p, verts_m[0]), n)) > 1e-6 for p in verts_m[3:])
            if non_planar:
                for i in range(1, len(verts_m) - 1):
                    self.add_polygon([verts_m[0], verts_m[i], verts_m[i + 1]], mat_name, patch)
                return

        # Strictly orthonormal 2D basis (e_u, e_v) in the polygon's plane for any normal n
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
        """
        Adds an axis-aligned rectangular panel whose size (w_art, h_art) exactly matches
        REGIONS[region_key]['rect_art'][2:4], mapped upright without UV seams.
        """
        r = REGIONS[region_key]["rect_art"]
        assert abs(w_art - r[2]) < 1e-5 and abs(h_art - r[3]) < 1e-5, (
            f"Panel {region_key} size mismatch: got ({w_art}, {h_art}), expected ({r[2]}, {r[3]})"
        )
        cx, cy, cz = [c * U for c in center_art]
        w = w_art * U
        h = h_art * U
        hw = w / 2.0
        hh = h / 2.0

        # Vertices ordered: [bottom-left, bottom-right, top-right, top-left] when viewed from front of panel
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
            # Downward-facing ceiling/soffit light panel (ny = -1.0 -> triggers ceilingLights in LandmarkKit!)
            verts = [
                (cx - hw, cy, cz - hh),
                (cx + hw, cy, cz - hh),
                (cx + hw, cy, cz + hh),
                (cx - hw, cy, cz + hh),
            ]
        else:
            raise ValueError(f"Unknown facing: {facing}")

        n = v_norm(v_cross(v_sub(verts[1], verts_m_0 := verts[0]), v_sub(verts[2], verts_m_0)))
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
ROOT = MeshBucket("starlight_drive_in")
STRUCTURE = MeshBucket("structure", parent=ROOT)
LOT = MeshBucket("lot", parent=ROOT)

# Optional prop groups under LOT / STRUCTURE
MARQUEE_PYLON = MeshBucket("marquee_pylon", parent=LOT)
SERVICE_YARD = MeshBucket("service_yard", parent=LOT)
SPEAKER_POSTS = MeshBucket("speaker_posts", parent=LOT)

# Gated door panel geometry nodes
DOOR_PANEL_MAIN = MeshBucket("door_panel_main", parent=STRUCTURE)
DOOR_PANEL_BACK = MeshBucket("door_panel_back", parent=STRUCTURE)
DOOR_PANEL_SIDE = MeshBucket("door_panel_side", parent=STRUCTURE)

# Collision mesh node (hidden from rendering, converted to walkable solids by LandmarkKit)
COLLISION_NODE = MeshBucket(
    "collision",
    parent=ROOT,
    extras={"collision_only": True, "render": False},
)

COLL_BOXES_BLENDER = []


def add_collision_box(x0_art, y0_art, z0_art, x1_art, y1_art, z1_art, group, source, attachment, gate_node=None):
    """
    Adds a closed 12-triangle box to COLLISION_NODE and records its metadata in
    model-info.json (boxes_blender_z_up).
    Insets horizontal ends by 0.25 art pixels (0.0194 m) so adjacent wall boxes never share
    a corner vertex when LandmarkKit unions triangles by rounded corner coordinates.
    """
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
        [c001, c101, c111, c011],  # +Z
        [c100, c000, c010, c110],  # -Z
        [c101, c100, c110, c111],  # +X
        [c000, c001, c011, c010],  # -X
        [c011, c111, c110, c010],  # +Y
        [c000, c100, c101, c001],  # -Y
    ]
    for f in faces:
        COLLISION_NODE.add_polygon(f, "wall_block")

    gx = (x0 + x1) / 2.0
    gy = (y0 + y1) / 2.0
    gz = (z0 + z1) / 2.0
    gsx = x1 - x0
    gsy = y1 - y0
    gsz = z1 - z0

    # Blender Z-up mapping: glTF (gx, gy, gz) -> Blender (bx, by, bz) = (gx, -gz, gy)
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
    """
    Adds an axis-aligned box in art-pixel coordinates [x0..x1, y0..y1, z0..z1].
    If mat_name == 'detail_utility', automatically subdivides any axis > 64 art pixels
    so every triangle stays strictly inside its 128x128 texel quadrant.
    """
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
    """
    Adds a rectangular structural beam/strut from p0_art to p1_art, automatically split
    into segments <= 56 art pixels long so utility UVs stay strictly within their quadrant.
    """
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
# 1. LOT GROUND, SIDEWALK, AMPHITHEATER VIEWING RAMPS & PERIMETER FENCE (y >= 0)
# ==============================================================================
def build_lot():
    # Main asphalt lot pad: 448 x 544 art pixels (34.7648 m x 42.2144 m), y in [0, 1*U]
    add_box(LOT, -224, 0, -496, 224, 1, 48, "ground_asphalt", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Front concrete sidewalk & forecourt promenade: x in [-160, 160], z in [-8, 36], y in [0, 2*U]
    add_box(LOT, -160, 0, -8, 160, 2, 36, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Rear concession patio & central walk aisle through the amphitheater ramps
    add_box(LOT, -104, 0, -124, 104, 2, -96, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))
    add_box(LOT, -20, 0, -408, 20, 2, -124, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Concrete island curb under the drive-through Ticket Kiosk (left lane x in [-196, -152])
    add_box(LOT, -196, 0, -44, -152, 2, 16, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Three stepped drive-in viewing berm ramps across the amphitheater field
    ramp_z_centers = [-176, -264, -352]
    for zc in ramp_z_centers:
        for rx0, rx1 in [(-192, -24), (24, 192)]:
            add_box(LOT, rx0, 1, zc - 22, rx1, 2, zc + 22, "ground_asphalt", faces=("+Y", "+Z", "-Z", "+X", "-X"))
            add_box(LOT, rx0, 1, zc - 24, rx1, 3, zc - 22, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Perimeter timber acoustic & headlight-shield fence along left (-X), right (+X), and rear (-Z) lot edges
    for z0 in range(-476, 16, 60):
        z1 = min(16, z0 + 56)
        add_box(
            LOT, -222, 1, z0, -218, 29, z1, "wall_wood_siding",
            collision=True, collision_group="lot", source=f"fence_west_{z0}"
        )
        add_box(LOT, -223, 1, z1, -217, 31, z1 + 4, "trim_teal")
        add_box(LOT, -223, 29, z0, -217, 31, z1, "trim_teal")

        add_box(
            LOT, 218, 1, z0, 222, 29, z1, "wall_wood_siding",
            collision=True, collision_group="lot", source=f"fence_east_{z0}"
        )
        add_box(LOT, 217, 1, z1, 223, 31, z1 + 4, "trim_teal")
        add_box(LOT, 217, 29, z0, 223, 31, z1, "trim_teal")

    add_box(
        LOT, -218, 1, -464, -162, 29, -460, "wall_wood_siding",
        collision=True, collision_group="lot", source="fence_north_west"
    )
    add_box(LOT, -218, 29, -465, -162, 31, -459, "trim_teal")
    add_box(
        LOT, 162, 1, -464, 218, 29, -460, "wall_wood_siding",
        collision=True, collision_group="lot", source="fence_north_east"
    )
    add_box(LOT, 162, 29, -465, 218, 31, -459, "trim_teal")


# ==============================================================================
# 2. FRONT GOOGIE CONCESSION PAVILION, TICKET KIOSK & PROJECTION BOOTH
#    Origin (0, 0, 0) is at ground level in the center of the front building edge (z = 0).
# ==============================================================================
def build_pavilion():
    # Interior floor slab: x in [-100, 100], z in [-92, -4], y in [0, 2*U]
    add_box(STRUCTURE, -100, 0, -92, 100, 2, -4, "ground_paving", faces=("+Y",))
    # Threshold aprons under front and rear doors
    add_box(STRUCTURE, -16, 0, -4, 16, 2, 0, "ground_paving", faces=("+Y", "+Z"))
    add_box(STRUCTURE, 58, 0, -96, 82, 2, -92, "ground_paving", faces=("+Y", "-Z"))

    # --- FRONT WALL (z in [-4, 0]) ---
    # Doorway opening at center: x in [-16, 16], y in [2, 38] (width 32 art px = 2.48 m)
    # Left front wing (x in [-104, -16]):
    add_box(
        STRUCTURE, -104, 2, -4, -16, 10, 0, "wall_brick",
        collision=True, collision_group="structure", source="front_wall_west"
    )
    add_box(STRUCTURE, -104, 10, -4, -92, 42, 0, "wall_brick")
    add_box(STRUCTURE, -20, 10, -4, -16, 42, 0, "wall_brick")
    # Glazed SNACK BAR window bay (x in [-92, -20], y in [10, 42], 72x32 art px = front_left)
    STRUCTURE.add_panel((-56, 26, -2), 72, 32, "front_left", "glass_concession", facing="+Z")

    # Right front wing (x in [16, 104]):
    add_box(
        STRUCTURE, 16, 2, -4, 104, 10, 0, "wall_brick",
        collision=True, collision_group="structure", source="front_wall_east"
    )
    add_box(STRUCTURE, 16, 10, -4, 20, 42, 0, "wall_brick")
    add_box(STRUCTURE, 92, 10, -4, 104, 42, 0, "wall_brick")
    # Glazed BOX OFFICE window bay (x in [20, 92], y in [10, 42], 72x32 art px = front_right)
    STRUCTURE.add_panel((56, 26, -2), 72, 32, "front_right", "glass_concession", facing="+Z")

    # Center doorway transom window (x in [-16, 16], y in [30, 42], 32x12 art px = transom)
    STRUCTURE.add_panel((0, 36, -2), 32, 12, "transom", "glass_concession", facing="+Z")
    add_box(STRUCTURE, -18, 2, -5, -16, 42, 1, "trim_teal")
    add_box(STRUCTURE, 16, 2, -5, 18, 42, 1, "trim_teal")
    add_box(STRUCTURE, -18, 30, -5, 18, 32, 1, "trim_teal")

    # Gated front double-leaf door panel (door_panel_main, x in [-16, 16], y in [2, 38], z in [-3, -1])
    add_box(
        DOOR_PANEL_MAIN, -16, 2, -3, -14, 30, -1, "trim_teal",
        collision=True, collision_group="structure", source="door_panel_main_gate", gate_node="door_panel_main"
    )
    add_box(DOOR_PANEL_MAIN, 14, 2, -3, 16, 30, -1, "trim_teal")
    DOOR_PANEL_MAIN.add_panel((0, 20, -2), 32, 36, "entry", "glass_concession", facing="+Z")

    # --- EAST SIDE WALL (x in [100, 104], z in [-96, 0]) ---
    add_box(
        STRUCTURE, 100, 2, -96, 104, 10, -4, "wall_brick",
        collision=True, collision_group="structure", source="east_wall"
    )
    add_box(STRUCTURE, 100, 10, -96, 104, 42, -72, "wall_cream")
    add_box(STRUCTURE, 100, 10, -24, 104, 42, -4, "wall_cream")
    # East side DRIVE-IN window (z in [-72, -24], y in [10, 42], 48x32 art px = side_win)
    STRUCTURE.add_panel((102, 26, -48), 48, 32, "side_win", "glass_concession", facing="+X")

    # --- WEST SIDE WALL (x in [-104, -100], z in [-96, 0]) ---
    add_box(
        STRUCTURE, -104, 2, -96, -100, 10, -4, "wall_brick",
        collision=True, collision_group="structure", source="west_wall"
    )
    add_box(STRUCTURE, -104, 10, -96, -100, 42, -72, "wall_cream")
    add_box(STRUCTURE, -104, 10, -24, -100, 42, -4, "wall_cream")
    STRUCTURE.add_panel((-102, 26, -48), 48, 32, "side_win", "glass_concession", facing="-X")

    # --- REAR WALL (z in [-96, -92], facing the amphitheater ramps & screen tower) ---
    # West rear concession wall (x in [-100, -40]): walk-up POPCORN service window (48x24 art px = walkup)
    add_box(
        STRUCTURE, -100, 2, -96, -40, 14, -92, "wall_brick",
        collision=True, collision_group="structure", source="rear_wall_west"
    )
    add_box(STRUCTURE, -100, 14, -96, -94, 42, -92, "wall_cream")
    add_box(STRUCTURE, -46, 14, -96, -40, 42, -92, "wall_cream")
    add_box(STRUCTURE, -94, 38, -96, -46, 42, -92, "wall_cream")
    STRUCTURE.add_panel((-70, 26, -94), 48, 24, "walkup", "glass_concession", facing="-Z")
    # Stainless steel outdoor service counter ledge & teal striped awning over walk-up window
    add_box(STRUCTURE, -94, 12, -100, -46, 14, -95, "detail_utility", patch="metal")
    add_box(STRUCTURE, -96, 36, -106, -44, 39, -95, "trim_teal")
    add_box(STRUCTURE, -96, 39, -106, -44, 40, -95, "roof_tin_blue")

    # Center Optical Projection Booth outer rear wall (x in [-40, 40], z in [-96, -92]):
    add_box(
        STRUCTURE, -40, 2, -96, 40, 22, -92, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_projection_booth"
    )
    add_box(STRUCTURE, -40, 22, -96, -36, 52, -92, "wall_block")
    add_box(STRUCTURE, 36, 22, -96, 40, 52, -92, "wall_block")
    add_box(STRUCTURE, -36, 38, -96, 36, 52, -92, "wall_block")
    # Optical projection ports assembly (72x16 art px = proj_ports) aiming -Z directly at the Screen Tower!
    STRUCTURE.add_panel((0, 30, -94), 72, 16, "proj_ports", "glass_concession", facing="-Z")
    add_box(STRUCTURE, -38, 38, -100, 38, 40, -95, "detail_utility", patch="rubber")

    # East rear wall framing door_back (x in [40, 62] and [78, 100]):
    add_box(
        STRUCTURE, 40, 2, -96, 62, 42, -92, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_east_inner"
    )
    add_box(
        STRUCTURE, 78, 2, -96, 100, 42, -92, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_east_outer"
    )
    add_box(STRUCTURE, 62, 32, -96, 78, 42, -92, "wall_block")
    add_box(STRUCTURE, 60, 2, -97, 62, 34, -91, "trim_teal")
    add_box(STRUCTURE, 78, 2, -97, 80, 34, -91, "trim_teal")
    add_box(STRUCTURE, 60, 32, -97, 80, 34, -91, "trim_teal")

    # Gated rear door panel (door_panel_back, x in [62, 78], y in [2, 32], z in [-95, -93])
    add_box(
        DOOR_PANEL_BACK, 62, 2, -95, 78, 32, -93, "detail_utility", patch="metal",
        collision=True, collision_group="structure", source="door_panel_back_gate", gate_node="door_panel_back"
    )

    # --- INTERIOR SHELL (Concession Lobby, Built-In Counter Shell, Optical Projection Room) ---
    # Interior ceiling soffit (y in [42, 44]) and interior wall linings
    add_box(STRUCTURE, -100, 42, -92, 100, 44, -4, "detail_utility", patch="paint", faces=("-Y",))
    # 6 warm recessed ceiling light panels inside the concession hall (16x4 art px = lamp, ny = -1)
    for lx in (-64, 0, 64):
        for lz in (-22, -48):
            STRUCTURE.add_panel((lx, 41.9, lz), 16, 4, "lamp", "glow_signage", facing="-Y")

    # Built-in fixed L-shaped concession service counter shell on the west side of the lobby
    add_box(
        STRUCTURE, -88, 2, -52, -16, 13, -40, "detail_utility", patch="coral",
        collision=True, collision_group="structure", source="concession_counter_front"
    )
    add_box(STRUCTURE, -90, 13, -54, -14, 15, -38, "detail_utility", patch="metal")
    add_box(
        STRUCTURE, -88, 2, -88, -76, 13, -52, "detail_utility", patch="coral",
        collision=True, collision_group="structure", source="concession_counter_return"
    )
    add_box(STRUCTURE, -90, 13, -88, -74, 15, -52, "detail_utility", patch="metal")

    # Interior Optical Projection Room partition walls (x in [-12, 40], z in [-92, -62], y in [2, 42])
    add_box(
        STRUCTURE, -12, 2, -92, -8, 42, -76, "wall_block",
        collision=True, collision_group="structure", source="proj_booth_west_wall"
    )
    add_box(
        STRUCTURE, 36, 2, -92, 40, 42, -62, "wall_block",
        collision=True, collision_group="structure", source="proj_booth_east_wall"
    )
    add_box(
        STRUCTURE, -12, 2, -66, 40, 42, -62, "wall_block",
        collision=True, collision_group="structure", source="proj_booth_south_wall"
    )
    # Built-in twin dual-reel projector pedestals inside the projection booth
    add_box(STRUCTURE, -2, 2, -86, 10, 18, -74, "detail_utility", patch="rubber")
    add_box(STRUCTURE, 0, 18, -88, 8, 28, -72, "detail_utility", patch="metal")
    add_box(STRUCTURE, 16, 2, -86, 28, 18, -74, "detail_utility", patch="rubber")
    add_box(STRUCTURE, 18, 18, -88, 26, 28, -72, "detail_utility", patch="metal")

    # --- ROOF DECK, PARAPET, FOLDED-WING GOOGIE FRONT CANOPY & ROOFTOP NEON SIGN ---
    add_box(STRUCTURE, -106, 42, -98, 106, 46, 2, "wall_cream")
    add_box(STRUCTURE, -108, 46, -100, 108, 50, 4, "trim_teal")
    add_box(STRUCTURE, -104, 46, -96, 104, 51, 0, "roof_ballast", faces=("+Y",))

    # Raised clerestory projection penthouse roof
    add_box(STRUCTURE, -42, 50, -98, 42, 56, -58, "wall_block")
    add_box(STRUCTURE, -44, 56, -100, 44, 59, -56, "trim_teal")
    add_box(STRUCTURE, -42, 59, -98, 42, 60, -58, "roof_tin_blue", faces=("+Y",))

    # Swept Googie front cantilever canopy (x in [-112, 112], z in [0, 24], y in [42, 49])
    add_box(STRUCTURE, -112, 42, 0, 112, 44, 24, "wall_cream")
    add_box(STRUCTURE, -114, 44, -2, 114, 49, 26, "trim_teal")
    add_box(STRUCTURE, -112, 49, 0, 112, 50, 24, "roof_tin_blue", faces=("+Y",))

    # Front fascia illuminated SNACK BAR neon sign centered above main entrance (64x12 art px = snack_sign)
    add_box(STRUCTURE, -34, 44, 25, 34, 58, 28, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((0, 51, 28.2), 64, 12, "snack_sign", "glow_signage", facing="+Z")
    # Rear penthouse illuminated SNACK BAR neon sign facing the viewing ramps (64x12 art px)
    add_box(STRUCTURE, -34, 58, -101, 34, 72, -98, "detail_utility", patch="rubber")
    STRUCTURE.add_panel((0, 65, -101.2), 64, 12, "snack_sign", "glow_signage", facing="-Z")

    # 4 downward-facing soffit lamps under the front canopy (16x4 art px = lamp, ny = -1)
    for lx in (-78, -26, 26, 78):
        STRUCTURE.add_panel((lx, 41.9, 12), 16, 4, "lamp", "glow_signage", facing="-Y")

    # Twin V-angled Googie steel front canopy support columns at x = -88 and x = +88, z = 18
    for sx in (-88, 88):
        add_box(
            STRUCTURE, sx - 3, 2, 15, sx + 3, 8, 21, "wall_brick",
            collision=True, collision_group="structure", source=f"canopy_pier_{sx}"
        )
        add_beam(STRUCTURE, (sx, 8, 18), (sx - 4, 42, 20), 3, "trim_teal", None)
        add_beam(STRUCTURE, (sx, 8, 18), (sx + 4, 42, 12), 3, "trim_teal", None)

    # Rooftop double-sided STARLIGHT DRIVE-IN neon marquee cabinet raised on twin teal steel stanchions
    # (y in [60, 78] so it stands clearly above the front canopy SNACK BAR fascia even from person height!)
    add_box(STRUCTURE, -44, 50, -12, -38, 60, -8, "trim_teal")
    add_box(STRUCTURE, 38, 50, -12, 44, 60, -8, "trim_teal")
    add_box(STRUCTURE, -58, 60, -14, 58, 78, -6, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -60, 58, -15, -58, 80, -5, "trim_teal")
    add_box(STRUCTURE, 58, 58, -15, 60, 80, -5, "trim_teal")
    add_box(STRUCTURE, -60, 78, -15, 60, 80, -5, "trim_teal")
    STRUCTURE.add_panel((0, 69, -5.8), 112, 14, "title", "glow_signage", facing="+Z")
    STRUCTURE.add_panel((0, 69, -14.2), 112, 14, "title", "glow_signage", facing="-Z")

    # --- DRIVE-THROUGH CANOPY & GLAZED TICKET KIOSK (West lane x in [-196, -104]) ---
    add_box(STRUCTURE, -196, 40, -32, -104, 43, 8, "wall_cream")
    add_box(STRUCTURE, -198, 43, -34, -104, 47, 10, "trim_teal")
    add_box(STRUCTURE, -196, 47, -32, -104, 48, 8, "roof_tin_blue", faces=("+Y",))
    for lx in (-126, -150):
        STRUCTURE.add_panel((lx, 39.9, -12), 16, 4, "lamp", "glow_signage", facing="-Y")

    # Ticket Kiosk Cabin on the raised island (x in [-190, -162], z in [-36, 4], y in [2, 40])
    add_box(STRUCTURE, -186, 2, -32, -166, 4, 0, "ground_paving", faces=("+Y",))
    add_box(
        STRUCTURE, -166, 2, -36, -162, 14, 4, "wall_brick",
        collision=True, collision_group="structure", source="kiosk_east_wall"
    )
    add_box(STRUCTURE, -166, 14, -36, -162, 40, -28, "wall_cream")
    add_box(STRUCTURE, -166, 14, -4, -162, 40, 4, "wall_cream")
    add_box(STRUCTURE, -166, 38, -28, -162, 40, -4, "wall_cream")
    # Glazed 50C ticket booth window on +X face (24x24 art px = ticket_front)
    STRUCTURE.add_panel((-161.8, 26, -16), 24, 24, "ticket_front", "glass_concession", facing="+X")

    add_box(
        STRUCTURE, -190, 2, 0, -162, 14, 4, "wall_brick",
        collision=True, collision_group="structure", source="kiosk_south_wall"
    )
    add_box(STRUCTURE, -190, 14, 0, -162, 40, 4, "wall_cream")
    add_box(
        STRUCTURE, -190, 2, -36, -162, 14, -32, "wall_brick",
        collision=True, collision_group="structure", source="kiosk_north_wall"
    )
    add_box(STRUCTURE, -190, 14, -36, -162, 40, -32, "wall_cream")
    # West wall of kiosk (x in [-190, -186]) with side doorway at z in [-20, -4]
    add_box(
        STRUCTURE, -190, 2, -36, -186, 40, -20, "wall_brick",
        collision=True, collision_group="structure", source="kiosk_west_wall_north"
    )
    add_box(
        STRUCTURE, -190, 2, -4, -186, 40, 4, "wall_brick",
        collision=True, collision_group="structure", source="kiosk_west_wall_south"
    )
    add_box(STRUCTURE, -190, 30, -20, -186, 40, -4, "wall_cream")
    # Gated side door panel (door_panel_side, x in [-189, -187], z in [-20, -4], y in [2, 30])
    add_box(
        DOOR_PANEL_SIDE, -189, 2, -20, -187, 30, -4, "trim_teal",
        collision=True, collision_group="structure", source="door_panel_side_gate", gate_node="door_panel_side"
    )

    # Double-sided illuminated TICKETS neon badge on ticket kiosk canopy (48x12 art px = tickets)
    add_box(STRUCTURE, -198, 47, 7, -146, 61, 11, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -200, 47, 6, -198, 63, 12, "trim_teal")
    add_box(STRUCTURE, -146, 47, 6, -144, 63, 12, "trim_teal")
    add_box(STRUCTURE, -200, 61, 6, -144, 63, 12, "trim_teal")
    STRUCTURE.add_panel((-172, 54, 11.2), 48, 12, "tickets", "glow_signage", facing="+Z")
    STRUCTURE.add_panel((-172, 54, 6.8), 48, 12, "tickets", "glow_signage", facing="-Z")


# ==============================================================================
# 3. MONUMENTAL DRIVE-IN SCREEN TOWER (Height 146*U = 11.33 m <= 12.0 m contract max)
# ==============================================================================
def build_screen_tower():
    # Screen tower base pedestal & masonry equipment vault:
    add_box(
        STRUCTURE, -112, 1, -448, 112, 16, -424, "wall_brick",
        collision=True, collision_group="structure", source="screen_tower_base_vault"
    )
    add_box(STRUCTURE, -112, 16, -448, 112, 46, -424, "wall_block")
    add_box(STRUCTURE, -114, 14, -425, 114, 17, -422, "trim_teal")
    add_box(STRUCTURE, -114, 44, -425, 114, 48, -422, "trim_teal")
    for px in (-104, -64, -24, 24, 64, 104):
        add_box(STRUCTURE, px - 3, 1, -424, px + 3, 46, -421, "trim_teal")

    # Upper screen structural housing cabinet:
    add_box(STRUCTURE, -106, 46, -436, 106, 138, -426, "wall_cream", faces=("+Z", "+X", "-X", "+Y"))
    add_box(STRUCTURE, -106, 46, -444, 106, 138, -436, "wall_wood_siding", faces=("-Z", "+X", "-X", "+Y"))

    # Matte black theatrical mask border framing the widescreen canvas (+Z face at z = -424)
    add_box(STRUCTURE, -100, 56, -426, 100, 60, -423, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -100, 132, -426, 100, 136, -423, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -100, 60, -426, -96, 132, -423, "detail_utility", patch="rubber")
    add_box(STRUCTURE, 96, 60, -426, 100, 132, -423, "detail_utility", patch="rubber")

    # THE WIDESCREEN CINEMA CANVAS: 192 x 72 art pixels (14.8992 m x 5.5872 m), y in [60, 132], z = -423.8
    STRUCTURE.add_panel((0, 96, -423.8), 192, 72, "screen_canvas", "glow_signage", facing="+Z")

    # Angled Googie top light-shield hood & flanking acoustic cheek wings on the +Z screen face
    add_box(STRUCTURE, -112, 46, -446, -106, 140, -412, "trim_teal")
    add_box(STRUCTURE, 106, 46, -446, 112, 140, -412, "trim_teal")
    add_box(STRUCTURE, -115, 64, -426, -112, 124, -414, "detail_utility", patch="coral")
    add_box(STRUCTURE, 112, 64, -426, 115, 124, -414, "detail_utility", patch="coral")

    # Top cantilevered shadow hood & blue ribbed tin roof cap (height up to 146*U = 11.3296 m)
    add_box(STRUCTURE, -112, 138, -446, 112, 143, -408, "trim_teal")
    add_box(STRUCTURE, -110, 143, -444, 110, 146, -410, "roof_tin_blue", faces=("+Y", "+Z", "-Z", "+X", "-X"))
    for lx in (-72, -24, 24, 72):
        STRUCTURE.add_panel((lx, 137.9, -416), 16, 4, "lamp", "glow_signage", facing="-Y")

    # --- REAR (-Z) 360-DEGREE STRUCTURAL ENGINEERING: 6 A-FRAME RAKER TRUSSES, CATWALK & NEON BANNER ---
    truss_xs = (-90, -54, -18, 18, 54, 90)
    for tx in truss_xs:
        add_box(
            STRUCTURE, tx - 5, 1, -482, tx + 5, 10, -470, "wall_block",
            collision=True, collision_group="structure", source=f"screen_truss_pier_{tx}"
        )
        add_box(STRUCTURE, tx - 2, 16, -448, tx + 2, 138, -444, "detail_utility", patch="metal")
        add_beam(STRUCTURE, (tx, 10, -476), (tx, 134, -445), 3.5, "detail_utility", "metal")
        add_beam(STRUCTURE, (tx, 48, -445), (tx, 48, -466), 2.5, "detail_utility", "metal")
        add_beam(STRUCTURE, (tx, 84, -445), (tx, 84, -457), 2.5, "detail_utility", "metal")
        add_beam(STRUCTURE, (tx, 48, -466), (tx, 84, -445), 2.0, "detail_utility", "metal")
        add_beam(STRUCTURE, (tx, 10, -476), (tx, 48, -445), 2.0, "detail_utility", "metal")

    for pair_x0, pair_x1 in [(-90, -54), (-18, 18), (54, 90)]:
        add_beam(STRUCTURE, (pair_x0, 50, -465), (pair_x1, 84, -457), 1.8, "detail_utility", "metal")
        add_beam(STRUCTURE, (pair_x1, 50, -465), (pair_x0, 84, -457), 1.8, "detail_utility", "metal")

    # Elevated steel maintenance catwalk at y = 84 along rear of screen tower
    add_box(STRUCTURE, -96, 83, -456, 96, 85, -444, "detail_utility", patch="metal")
    add_box(STRUCTURE, -96, 96, -456, 96, 98, -454, "trim_teal")
    add_box(STRUCTURE, -96, 90, -456, 96, 91, -455, "detail_utility", patch="metal")
    for px in range(-96, 97, 32):
        add_box(STRUCTURE, px - 1, 85, -456, px + 1, 98, -454, "detail_utility", patch="metal")

    # Vertical steel access ladder from ground (y = 2) to catwalk (y = 85) at east end
    add_box(STRUCTURE, 97, 2, -452, 99, 96, -450, "detail_utility", patch="metal")
    add_box(STRUCTURE, 97, 2, -446, 99, 96, -444, "detail_utility", patch="metal")
    for ry in range(8, 88, 8):
        add_box(STRUCTURE, 97, ry, -450, 99, ry + 1, -446, "detail_utility", patch="metal")

    # Rear highway-facing STARLIGHT DRIVE-IN neon marquee sign mounted on the outer catwalk fascia (z in [-460, -456])
    add_box(STRUCTURE, -58, 98, -460, 58, 116, -456, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -60, 96, -461, 60, 98, -456, "trim_teal")
    add_box(STRUCTURE, -60, 116, -461, 60, 118, -456, "trim_teal")
    STRUCTURE.add_panel((0, 107, -460.2), 112, 14, "title", "glow_signage", facing="-Z")

    # Rear lower transformer & speaker-amplifier access doors on the masonry vault (-Z face at z = -448)
    add_box(STRUCTURE, -24, 2, -450, 24, 34, -448, "detail_utility", patch="rubber")
    add_box(STRUCTURE, -22, 4, -450.5, -2, 32, -448.5, "detail_utility", patch="metal")
    add_box(STRUCTURE, 2, 4, -450.5, 22, 32, -448.5, "detail_utility", patch="metal")


# ==============================================================================
# 4. OPTIONAL PROP GROUPS (< 500 triangles each)
# ==============================================================================
def build_marquee_pylon():
    # Placed at front-right highway corner of lot: x = 164, z = 12, total height = 148*U = 11.4848 m (<= 12.0 m)
    cx, cz = 164, 12
    add_box(
        MARQUEE_PYLON, cx - 20, 1, cz - 14, cx + 20, 12, cz + 14, "wall_brick",
        collision=True, collision_group="lot", source="marquee_pylon_base"
    )
    add_box(MARQUEE_PYLON, cx - 22, 12, cz - 16, cx + 22, 15, cz + 16, "ground_paving")

    # Twin tapered Googie teal steel pylon columns rising to y = 136
    add_box(MARQUEE_PYLON, cx - 14, 15, cz - 4, cx - 6, 136, cz + 4, "trim_teal")
    add_box(MARQUEE_PYLON, cx + 6, 15, cz - 4, cx + 14, 136, cz + 4, "trim_teal")
    add_box(MARQUEE_PYLON, cx - 22, 32, cz - 2, cx + 22, 36, cz + 2, "detail_utility", patch="coral")
    add_box(MARQUEE_PYLON, cx - 26, 86, cz - 2, cx + 26, 90, cz + 2, "detail_utility", patch="coral")

    # Lower NOW SHOWING / NIGHT REEL double-sided reader board cabinet (72x24 art px = reader)
    add_box(MARQUEE_PYLON, cx - 38, 46, cz - 6, cx + 38, 74, cz + 6, "detail_utility", patch="rubber")
    add_box(MARQUEE_PYLON, cx - 40, 44, cz - 7, cx + 40, 46, cz + 7, "trim_teal")
    add_box(MARQUEE_PYLON, cx - 40, 74, cz - 7, cx + 40, 76, cz + 7, "trim_teal")
    MARQUEE_PYLON.add_panel((cx, 60, cz + 6.2), 72, 24, "reader", "glow_signage", facing="+Z")
    MARQUEE_PYLON.add_panel((cx, 60, cz - 6.2), 72, 24, "reader", "glow_signage", facing="-Z")

    # Upper STARLIGHT neon header cabinet (72x14 art px = pylon_top)
    add_box(MARQUEE_PYLON, cx - 38, 98, cz - 5, cx + 38, 116, cz + 5, "detail_utility", patch="rubber")
    add_box(MARQUEE_PYLON, cx - 40, 96, cz - 6, cx + 40, 98, cz + 6, "detail_utility", patch="coral")
    add_box(MARQUEE_PYLON, cx - 40, 116, cz - 6, cx + 40, 118, cz + 6, "detail_utility", patch="coral")
    MARQUEE_PYLON.add_panel((cx, 107, cz + 5.2), 72, 14, "pylon_top", "glow_signage", facing="+Z")
    MARQUEE_PYLON.add_panel((cx, 107, cz - 5.2), 72, 14, "pylon_top", "glow_signage", facing="-Z")

    # Crown Googie starburst neon badge (24x24 art px = starburst), y in [124, 148] (top = 148*U = 11.4848 m)
    add_box(MARQUEE_PYLON, cx - 12, 124, cz - 3, cx + 12, 148, cz + 3, "detail_utility", patch="rubber")
    MARQUEE_PYLON.add_panel((cx, 136, cz + 3.2), 24, 24, "starburst", "glow_signage", facing="+Z")
    MARQUEE_PYLON.add_panel((cx, 136, cz - 3.2), 24, 24, "starburst", "glow_signage", facing="-Z")


def build_service_yard():
    add_box(SERVICE_YARD, 104, 1, -92, 152, 2, -44, "ground_paving", faces=("+Y", "+Z", "-Z", "+X"))
    add_box(
        SERVICE_YARD, 148, 2, -92, 152, 26, -44, "wall_wood_siding",
        collision=True, collision_group="lot", source="service_yard_east_wall"
    )
    add_box(
        SERVICE_YARD, 104, 2, -92, 148, 26, -88, "wall_wood_siding",
        collision=True, collision_group="lot", source="service_yard_north_wall"
    )
    add_box(
        SERVICE_YARD, 124, 2, -48, 148, 26, -44, "wall_wood_siding",
        collision=True, collision_group="lot", source="service_yard_south_wall"
    )
    add_box(SERVICE_YARD, 147, 26, -93, 153, 28, -43, "trim_teal")
    add_box(SERVICE_YARD, 104, 26, -93, 153, 28, -87, "trim_teal")

    for dz in (-82, -64):
        add_box(
            SERVICE_YARD, 128, 2, dz, 144, 16, dz + 12, "detail_utility", patch="metal",
            collision=True, collision_group="lot", source=f"dumpster_{dz}"
        )
        add_box(SERVICE_YARD, 127, 16, dz - 1, 145, 18, dz + 13, "detail_utility", patch="rubber")

    add_box(
        SERVICE_YARD, 104, 2, -84, 116, 22, -70, "detail_utility", patch="metal",
        collision=True, collision_group="lot", source="transformer_cabinet"
    )
    add_box(SERVICE_YARD, 104, 22, -85, 117, 24, -69, "detail_utility", patch="rubber")
    add_box(SERVICE_YARD, 104, 18, -66, 110, 38, -58, "detail_utility", patch="metal")


def build_speaker_posts():
    ramp_zs = [-176, -264, -352]
    slot_xs = [-156, -104, -52, 52, 104, 156]
    post_num = 1
    for r_idx, rz in enumerate(ramp_zs, start=1):
        for s_idx, sx in enumerate(slot_xs, start=1):
            name = f"speaker_post_{post_num:02d}"
            post_num += 1
            node = MeshBucket(
                name,
                parent=SPEAKER_POSTS,
                translation=[round(sx * U, 4), round(2 * U, 4), round(rz * U, 4)],
                extras={"gameplay_prop": "speaker_post", "ramp": r_idx, "post_slot": s_idx},
            )
            add_box(node, -1, 0, -1, 1, 12, 1, "detail_utility", patch="metal")
            add_box(node, -4, 12, -2, 4, 17, 2, "detail_utility", patch="paint")


# ==============================================================================
# 5. DOORS (Empty leaf nodes with exact +Z outward local orientation)
# ==============================================================================
def build_doors():
    MeshBucket(
        "door_main",
        parent=ROOT,
        translation=[0.0, 0.0, round(2 * U, 4)],
        rotation=[0.0, 0.0, 0.0, 1.0],
        extras={"interaction": "Go inside"},
    )
    MeshBucket(
        "door_back",
        parent=ROOT,
        translation=[round(70 * U, 4), 0.0, round(-98 * U, 4)],
        rotation=[0.0, 1.0, 0.0, 0.0],
        extras={"interaction": "Go inside"},
    )
    s45 = round(math.sin(math.pi / 4.0), 7)
    c45 = round(math.cos(math.pi / 4.0), 7)
    MeshBucket(
        "door_side",
        parent=ROOT,
        translation=[round(-192 * U, 4), 0.0, round(-12 * U, 4)],
        rotation=[0.0, -s45, 0.0, c45],
        extras={"interaction": "Go inside"},
    )


# ==============================================================================
# 6. BINARY GLTF 2.0 (.GLB) PACKAGER & MODEL-INFO.JSON EXPORTER
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
            "generator": "Starlight Drive-In Procedural Builder (LANDMARKS_GLB contract)",
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

    glb_path = R / "starlight-drive-in.glb"
    with open(glb_path, "wb") as f:
        f.write(glb_header)
        f.write(json_hdr)
        f.write(json_chunk)
        f.write(bin_hdr)
        f.write(bin_chunk)

    model_info = {
        "name": "Starlight Drive-In",
        "slug": "starlight-drive-in",
        "era": "1950s",
        "Category": "landmark_entertainment",
        "role": "cinema_drive_in",
        "units": "metres",
        "up_axis": "Y",
        "front_axis": "+Z",
        "origin": "Ground level (y = 0) at horizontal center of the front concession pavilion edge (z = 0)",
        "pixel_grid": {
            "art_pixel_m": U,
            "art_pixel_texels": 2,
            "texel_m": T,
        },
        "footprint_m": {
            "building_width": round(208 * U, 4),
            "building_depth": round(96 * U, 4),
            "screen_tower_width": round(230 * U, 4),
            "screen_tower_depth": round(74 * U, 4),
            "lot_width": round(448 * U, 4),
            "lot_depth": round(544 * U, 4),
            "max_height": round(148 * U, 4),
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
            "speaker_posts": subtree_tris(SPEAKER_POSTS),
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
                "position": [round(70 * U, 4), 0.0, round(-98 * U, 4)],
                "facing": "-Z",
                "panel_node": "door_panel_back",
            },
            "door_side": {
                "position": [round(-192 * U, 4), 0.0, round(-12 * U, 4)],
                "facing": "-X",
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
            "Drive-in speaker posts (speaker_post_01..18 under speaker_posts) carry ramp and post_slot extras for vehicle parking alignment; no cars are bundled in the landmark mesh.",
            "The widescreen feature presentation panel (glow_signage screen_canvas) is authored at 192x72 art pixels (14.90 m x 5.59 m) and can be swapped at runtime for dynamic movie frames.",
        ],
        "swatches_used": [
            "brick_red",
            "block_courses",
            "ballast",
            "roof_tin_blue",
            "plank_vertical",
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
    build_pavilion()
    build_screen_tower()
    build_marquee_pylon()
    build_service_yard()
    build_speaker_posts()
    build_doors()
    export_glb_and_info()


if __name__ == "__main__":
    main()
