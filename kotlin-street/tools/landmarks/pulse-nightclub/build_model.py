#!/usr/bin/env python3
"""
Procedural 3D glTF 2.0 (.glb) builder for the PULSE 2020s Modern Nightclub & Sky Lounge landmark.
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
        "name": "wall_obsidian",
        "file": "obsidian.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Matte charcoal-black vertical architectural aluminum louvre cladding with bronze reveal lines",
    },
    {
        "name": "wall_slate_dark",
        "file": "wall_slate_dark.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Dark basalt/slate masonry base plinth, service yard, and perimeter privacy walls (LimeZu wall_slate_dark)",
    },
    {
        "name": "wall_travertine",
        "file": "wall_slate_light.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Pale architectural travertine/limestone accent piers and planter cubes (LimeZu wall_slate_light)",
    },
    {
        "name": "wall_block",
        "file": "block_courses.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Acoustic concrete-block rear stage shell and rooftop sound-isolation penthouse (LimeZu block_courses)",
    },
    {
        "name": "wood_teak",
        "file": "plank_horizontal.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Warm modern architectural teak decking for rooftop Sky Lounge terrace and porte-cochere soffit (LimeZu plank_horizontal)",
    },
    {
        "name": "trim_architectural",
        "file": "champagne_trim.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Brushed champagne-gold and anodized bronze architectural fascia, cantilever frames, and sky pergola",
    },
    {
        "name": "roof_membrane",
        "file": "roof_membrane_grey.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Clean commercial flat roof membrane over rear acoustic hall and mechanical deck (LimeZu roof_membrane_grey)",
    },
    {
        "name": "ground_asphalt",
        "file": "asphalt.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Fresh blacktop asphalt VIP valet arrival drive and parking stalls (LimeZu asphalt)",
    },
    {
        "name": "ground_paving",
        "file": "pavement.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Large-format concrete boulevard promenade, valet plaza, loading dock, and main-room slab (LimeZu pavement)",
    },
    {
        "name": "glass_curtain",
        "file": "glass.png",
        "alphaMode": "BLEND",
        "emissive": False,
        "role": "Frameless modern dark-bronze curtain-wall glazing, rooftop glass balustrades, atrium doors, and DJ acoustic shield",
    },
    {
        "name": "glow_led",
        "file": "led_media.png",
        "alphaMode": "OPAQUE",
        "emissive": True,
        "role": "Self-lit PULSE LED signage, exterior kinetic media wall, linear recessed downlights, LED pulse floor, onyx back-bar, and festival DJ video wall",
    },
    {
        "name": "detail_utility",
        "file": "utility.png",
        "alphaMode": "OPAQUE",
        "emissive": False,
        "role": "Four-quadrant utility atlas: brushed titanium steel, matte obsidian acoustic baffles/speakers, royal-plum VIP leather, and ribbed dark ceiling panels",
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
ROOT = MeshBucket("pulse_nightclub")
STRUCTURE = MeshBucket("structure", parent=ROOT)
LOT = MeshBucket("lot", parent=ROOT)

# Optional prop groups (< 500 triangles each)
LED_MONOLITH = MeshBucket("led_monolith", parent=LOT)
SERVICE_YARD = MeshBucket("service_yard", parent=LOT)
VALET_PLAZA = MeshBucket("valet_plaza", parent=LOT)

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


def add_beam(node, p0_art, p1_art, thickness_art, mat_name="detail_utility", patch="steel"):
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
# 1. LOT GROUND, BOULEVARD PROMENADE, VALET ARRIVAL DRIVE & PRIVACY WALLS
#    Lot footprint: 352 x 384 art pixels (27.3152 m x 29.7984 m), all at y >= 0.
# ==============================================================================
def build_lot():
    # Sleek blacktop asphalt pad: x in [-176, 176], z in [-320, 64], y in [0, 1*U]
    add_box(LOT, -176, 0, -320, 176, 1, 64, "ground_asphalt", faces=("+Y", "+Z", "-Z", "+X", "-X"))

    # Large-format concrete boulevard promenade & VIP valet apron: x in [-148, 148], z in [-6, 48]
    add_box(LOT, -148, 0, -6, 148, 2, 48, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))
    # Subtle warm teak arrival runner inset leading toward door_main (flush at y = 2.05? No, keep solid paving at y = 2, flank with travertine curbs!)
    add_box(LOT, -148, 2, 46, -24, 3, 48, "wall_travertine")
    add_box(LOT, 24, 2, 46, 148, 3, 48, "wall_travertine")

    # East VIP side walkway & service apron
    add_box(LOT, 120, 0, -144, 148, 2, -6, "ground_paving", faces=("+Y", "+Z", "-Z", "+X"))

    # West VIP valet parking lane stall dividers & concrete wheel stops
    for pz in (-200, -152, -104, -56, -8):
        add_box(LOT, -168, 1, pz - 1, -134, 2, pz + 1, "ground_paving", faces=("+Y", "+Z", "-Z", "+X", "-X"))
        add_box(LOT, -166, 1, pz + 16, -162, 3, pz + 32, "wall_travertine")

    # Modern dark-slate perimeter privacy walls with brushed champagne-bronze coping
    for z0 in range(-312, 16, 64):
        z1 = min(16, z0 + 60)
        add_box(
            LOT, -176, 1, z0, -172, 24, z1, "wall_slate_dark",
            collision=True, collision_group="lot", source=f"wall_west_{z0}"
        )
        add_box(LOT, -177, 1, z1, -171, 27, z1 + 4, "wall_travertine")
        add_box(LOT, -177, 24, z0, -171, 26, z1, "trim_architectural")

        add_box(
            LOT, 172, 1, z0, 176, 24, z1, "wall_slate_dark",
            collision=True, collision_group="lot", source=f"wall_east_{z0}"
        )
        add_box(LOT, 171, 1, z1, 177, 27, z1 + 4, "wall_travertine")
        add_box(LOT, 171, 24, z0, 177, 26, z1, "trim_architectural")

    # Rear perimeter screen walls flanking backstage production access
    for rx0, rx1, tag in [(-172, -88, "nw"), (88, 172, "ne")]:
        add_box(
            LOT, rx0, 1, -320, rx1, 24, -316, "wall_slate_dark",
            collision=True, collision_group="lot", source=f"wall_north_{tag}"
        )
        add_box(LOT, rx0, 24, -321, rx1, 26, -315, "trim_architectural")


# ==============================================================================
# 2. MAIN MODERN NIGHTCLUB & ROOFTOP SKY LOUNGE (PULSE)
#    Origin (0, 0, 0) at ground level in center of front building edge (z = 0).
#    Building footprint: x in [-120, 120] (240 art px = 18.624 m),
#                        z in [-224, 0]   (224 art px = 17.3824 m).
# ==============================================================================
def build_nightclub():
    # Interior concrete slab (y in [0, 2*U])
    add_box(STRUCTURE, -116, 0, -220, 116, 2, -4, "ground_paving", faces=("+Y",))
    add_box(STRUCTURE, -20, 0, -4, 20, 2, 0, "ground_paving", faces=("+Y", "+Z"))

    # --- EAST CORNER ARCHITECTURAL ATRIUM TOWER (x in [96, 122], z in [-28, 4], y in [2, 74]) ---
    # Asymmetric contemporary signature: travertine plinth + matte obsidian tower + vertical atrium glass slot
    add_box(
        STRUCTURE, 96, 2, -28, 122, 14, 4, "wall_travertine",
        collision=True, collision_group="structure", source="east_atrium_tower"
    )
    add_box(STRUCTURE, 96, 14, -28, 122, 70, 4, "wall_obsidian")
    add_box(STRUCTURE, 95, 70, -29, 123, 74, 5, "trim_architectural")
    add_box(STRUCTURE, 96, 74, -28, 122, 75, 4, "roof_membrane", faces=("+Y",))
    # Slim vertical champagne-bronze fins framing the double-height glass slot
    add_box(STRUCTURE, 99, 2, 4, 101, 70, 6, "trim_architectural")
    add_box(STRUCTURE, 117, 2, 4, 119, 70, 6, "trim_architectural")
    STRUCTURE.add_panel((109, 36, 4.4), 16, 64, "atrium_col", "glass_curtain", facing="+Z")

    # --- WEST CORNER STRUCTURAL PIER (x in [-122, -96], z in [-28, 2], y in [2, 52]) ---
    # Supports the dramatic cantilevered upper media gallery box!
    add_box(
        STRUCTURE, -122, 2, -28, -96, 14, 2, "wall_travertine",
        collision=True, collision_group="structure", source="west_corner_pier"
    )
    add_box(STRUCTURE, -122, 14, -28, -96, 50, 2, "wall_obsidian")
    add_box(STRUCTURE, -123, 2, 1, -121, 50, 3, "trim_architectural")

    # --- FRONT GROUND-LEVEL FACADE (z in [-4, 0], x in [-96, 96]) ---
    # Central doorway opening: x in [-16, 16], y in [2, 38]
    # West lobby wall + frameless curtain-wall glass bay ("LOUNGE", 72x32)
    add_box(
        STRUCTURE, -96, 2, -4, -16, 12, 0, "wall_slate_dark",
        collision=True, collision_group="structure", source="front_wall_west"
    )
    add_box(STRUCTURE, -96, 12, -4, -92, 50, 0, "wall_obsidian")
    add_box(STRUCTURE, -20, 12, -4, -16, 50, 0, "wall_obsidian")
    add_box(STRUCTURE, -92, 44, -4, -20, 50, 0, "wall_obsidian")
    STRUCTURE.add_panel((-56, 28, -2), 72, 32, "front_left", "glass_curtain", facing="+Z")

    # East lobby wall + frameless curtain-wall glass bay ("VIP CLUB", 72x32)
    add_box(
        STRUCTURE, 16, 2, -4, 96, 12, 0, "wall_slate_dark",
        collision=True, collision_group="structure", source="front_wall_east"
    )
    add_box(STRUCTURE, 16, 12, -4, 20, 50, 0, "wall_obsidian")
    add_box(STRUCTURE, 92, 12, -4, 96, 50, 0, "wall_obsidian")
    add_box(STRUCTURE, 20, 44, -4, 92, 50, 0, "wall_obsidian")
    STRUCTURE.add_panel((56, 28, -2), 72, 32, "front_right", "glass_curtain", facing="+Z")

    # Minimalist champagne-bronze & travertine recessed entrance portal framing door_main
    add_box(STRUCTURE, -22, 2, -5, -18, 42, 3, "wall_travertine")
    add_box(STRUCTURE, 18, 2, -5, 22, 42, 3, "wall_travertine")
    add_box(STRUCTURE, -18, 2, -5, -16, 42, 2, "trim_architectural")
    add_box(STRUCTURE, 16, 2, -5, 18, 42, 2, "trim_architectural")
    add_box(STRUCTURE, -16, 42, -4, 16, 50, 0, "wall_obsidian")
    STRUCTURE.add_panel((0, 36, -2), 32, 12, "transom", "glass_curtain", facing="+Z")
    add_box(STRUCTURE, -18, 29, -5, 18, 31, 2, "trim_architectural")

    # Gated front double-leaf frameless glass doors (door_panel_main)
    add_box(
        DOOR_PANEL_MAIN, -16, 2, -3, -14, 30, -1, "trim_architectural",
        collision=True, collision_group="structure", source="door_panel_main_gate", gate_node="door_panel_main"
    )
    add_box(DOOR_PANEL_MAIN, 14, 2, -3, 16, 30, -1, "trim_architectural")
    DOOR_PANEL_MAIN.add_panel((0, 20, -2), 32, 36, "entry", "glass_curtain", facing="+Z")

    # --- CANTILEVERED PORTE-COCHERE WITH WARM TEAK SOFFIT & LED DOWNLIGHTS ---
    # Canopy deck spanning x in [-116, 108], z in [0, 26], y in [42, 49]
    add_box(STRUCTURE, -116, 42, 0, 108, 44, 26, "wood_teak", faces=("-Y", "+Z", "+X", "-X"))
    add_box(STRUCTURE, -118, 44, -2, 110, 49, 28, "trim_architectural")
    add_box(STRUCTURE, -116, 49, 0, 108, 50, 26, "roof_membrane", faces=("+Y",))

    # Central cantilevered "VIP VALET" backlit blade sign box (48x12 = valet_sign)
    add_box(STRUCTURE, -26, 42, 26, 26, 56, 31, "detail_utility", patch="acoustic")
    add_box(STRUCTURE, -28, 56, 25, 28, 58, 32, "trim_architectural")
    STRUCTURE.add_panel((0, 49, 31.4), 48, 12, "valet_sign", "glow_led", facing="+Z")

    # 6 linear architectural LED downlight bars recessed into teak porte-cochere soffit (ny = -1 -> real Filament lights!)
    for lx in (-82, -50, -18, 18, 50, 82):
        STRUCTURE.add_panel((lx, 41.8, 14), 16, 4, "lamp", "glow_led", facing="-Y")

    # Twin sleek architectural bronze & steel V-columns supporting outer corners of porte-cochere
    for sx in (-94, 90):
        add_box(
            STRUCTURE, sx - 3, 2, 17, sx + 3, 8, 23, "wall_travertine",
            collision=True, collision_group="structure", source=f"canopy_pier_{sx}"
        )
        add_beam(STRUCTURE, (sx, 8, 20), (sx - 3, 42, 22), 3, "detail_utility", "steel")
        add_beam(STRUCTURE, (sx, 8, 20), (sx + 3, 42, 14), 3, "trim_architectural", None)

    # --- ASYMMETRIC CANTILEVERED UPPER MEDIA GALLERY BOX (West Front Overhang) ---
    # Projects cleanly above the porte-cochere on the left half: x in [-122, -24], z in [-34, 24], y in [49, 86]
    add_box(STRUCTURE, -122, 49, -34, -24, 84, 24, "wall_obsidian")
    add_box(STRUCTURE, -124, 84, -36, -22, 88, 26, "trim_architectural")
    add_box(STRUCTURE, -122, 88, -34, -24, 89, 24, "roof_membrane", faces=("+Y",))
    # Flush exterior kinetic LED Media Wall embedded in front face of cantilevered box (72x32 = led_media_wall)
    add_box(STRUCTURE, -112, 49, 23, -34, 84, 25, "detail_utility", patch="acoustic")
    STRUCTURE.add_panel((-73, 67, 25.4), 72, 32, "led_media_wall", "glow_led", facing="+Z")

    # --- ROOF DECK, ACOUSTIC PENTHOUSE & OPEN-AIR ROOFTOP SKY LOUNGE TERRACE ---
    add_box(STRUCTURE, -120, 50, -224, 120, 52, 0, "wall_obsidian")
    add_box(STRUCTURE, -122, 52, -226, 122, 56, 2, "trim_architectural")
    add_box(STRUCTURE, -118, 52, -222, 118, 57, -2, "roof_membrane", faces=("+Y",))

    # Open-Air Rooftop Sky Lounge Teak Terrace Deck (front-east roof zone: x in [-22, 108], z in [-96, 18], y in [57, 59])
    add_box(STRUCTURE, -22, 50, -96, 108, 59, 18, "wood_teak", faces=("+Y", "+Z", "+X", "-X"))
    add_box(STRUCTURE, -22, 56, 18, 108, 59, 20, "trim_architectural")
    # Two side-by-side frameless glass safety balustrades along the front Sky Lounge edge (each 64x14 = rail_glass)
    STRUCTURE.add_panel((10, 66, 18.5), 64, 14, "rail_glass", "glass_curtain", facing="+Z")
    STRUCTURE.add_panel((74, 66, 18.5), 64, 14, "rail_glass", "glass_curtain", facing="+Z")
    # East edge glass balustrade (64x14 = rail_glass) along x = 107.5, z in [-80, -16]
    STRUCTURE.add_panel((107.5, 66, -48), 64, 14, "rail_glass", "glass_curtain", facing="+X")

    # Rooftop Sky Lounge Cantilevered Pergola & Open-Air Bar Pavilion (x in [-20, 96], z in [-96, -54], y in [59, 92])
    add_box(STRUCTURE, -18, 59, -96, 94, 88, -88, "wall_obsidian")
    # Rooftop cocktail bar counter & illuminated amber-bronze bar top
    add_box(STRUCTURE, 2, 59, -76, 74, 71, -66, "detail_utility", patch="acoustic")
    add_box(STRUCTURE, 0, 71, -78, 76, 73, -64, "trim_architectural")
    # Rooftop VIP velvet lounge sofa along west edge of terrace (x in [-18, -2], z in [-52, -8])
    add_box(STRUCTURE, -18, 59, -52, -4, 67, -8, "detail_utility", patch="velvet")
    add_box(STRUCTURE, -20, 59, -52, -16, 73, -8, "detail_utility", patch="acoustic")
    # Cantilevered Sky Lounge architectural shade pergola canopy
    for px in (-16, 38, 92):
        add_box(STRUCTURE, px - 2, 59, -58, px + 2, 88, -54, "detail_utility", patch="steel")
    add_box(STRUCTURE, -20, 88, -98, 96, 90, -52, "wood_teak", faces=("-Y", "+Y", "+Z", "-Z", "+X", "-X"))
    add_box(STRUCTURE, -22, 90, -100, 98, 94, -50, "trim_architectural")
    # "SKY LOUNGE" backlit sign on front of rooftop pergola (64x12 = sky_sign)
    add_box(STRUCTURE, 4, 80, -54, 72, 94, -50, "detail_utility", patch="acoustic")
    STRUCTURE.add_panel((38, 87, -49.6), 64, 12, "sky_sign", "glow_led", facing="+Z")
    # 2 downward-facing LED lamps under the rooftop Sky Lounge pergola
    STRUCTURE.add_panel((16, 87.8, -68), 16, 4, "lamp", "glow_led", facing="-Y")
    STRUCTURE.add_panel((60, 87.8, -68), 16, 4, "lamp", "glow_led", facing="-Y")

    # Rear Acoustic Sound-Isolation Penthouse over Main Room (x in [-106, 106], z in [-196, -98], y in [57, 82])
    add_box(STRUCTURE, -106, 57, -196, 106, 78, -98, "wall_block")
    add_box(STRUCTURE, -108, 78, -198, 108, 82, -96, "trim_architectural")
    add_box(STRUCTURE, -104, 82, -194, 104, 84, -100, "roof_membrane", faces=("+Y",))

    # Skyline-defining Double-Sided Rooftop "PULSE" Architectural Backlit Crown Sign
    # Front-facing elevated above Sky Lounge pergola (y in [96, 118], z = -102) + Rear-facing on north penthouse edge (y in [84, 106], z = -192)
    add_box(STRUCTURE, -44, 82, -106, -38, 96, -98, "detail_utility", patch="steel")
    add_box(STRUCTURE, 38, 82, -106, 44, 96, -98, "detail_utility", patch="steel")
    add_box(STRUCTURE, -58, 96, -107, 58, 116, -97, "detail_utility", patch="acoustic")
    add_box(STRUCTURE, -60, 116, -108, 60, 119, -96, "trim_architectural")
    STRUCTURE.add_panel((0, 106, -96.6), 112, 16, "title", "glow_led", facing="+Z")

    add_box(STRUCTURE, -58, 84, -197, 58, 104, -187, "detail_utility", patch="acoustic")
    add_box(STRUCTURE, -60, 104, -198, 60, 107, -186, "trim_architectural")
    STRUCTURE.add_panel((0, 94, -197.4), 112, 16, "title", "glow_led", facing="-Z")

    # Rooftop mechanical HVAC air-handler behind penthouse (x in [58, 106], z in [-218, -198], y in [57, 70])
    add_box(STRUCTURE, 58, 57, -218, 106, 68, -198, "detail_utility", patch="steel")
    add_box(STRUCTURE, 57, 68, -219, 107, 70, -197, "detail_utility", patch="acoustic")

    # --- WEST SIDE WALL (x in [-120, -116], z in [-224, -28]) ---
    add_box(
        STRUCTURE, -120, 2, -224, -116, 12, -28, "wall_slate_dark",
        collision=True, collision_group="structure", source="west_wall"
    )
    add_box(STRUCTURE, -120, 12, -224, -116, 52, -208, "wall_obsidian")
    add_box(STRUCTURE, -120, 44, -208, -116, 52, -160, "wall_obsidian")
    add_box(STRUCTURE, -120, 12, -160, -116, 52, -28, "wall_obsidian")
    # West curtain-wall lounge window ("PULSE", 48x32) at z = -184 (clear of interior bar at z in [-148, -50])
    STRUCTURE.add_panel((-118, 28, -184), 48, 32, "side_win", "glass_curtain", facing="-X")
    # Slim vertical champagne-bronze architectural fins along west elevation
    for wz in (-214, -154, -96, -36):
        add_box(STRUCTURE, -122, 2, wz - 2, -120, 52, wz + 2, "trim_architectural")

    # --- EAST SIDE WALL (x in [116, 120], z in [-224, -28]) ---
    # Side VIP/emergency exit doorway at z in [-120, -104], y in [2, 34] (door_side at x = 122, z = -112)
    add_box(
        STRUCTURE, 116, 2, -224, 120, 12, -120, "wall_slate_dark",
        collision=True, collision_group="structure", source="east_wall_north"
    )
    add_box(
        STRUCTURE, 116, 2, -104, 120, 12, -28, "wall_slate_dark",
        collision=True, collision_group="structure", source="east_wall_south"
    )
    add_box(STRUCTURE, 116, 12, -224, 120, 52, -180, "wall_obsidian")
    add_box(STRUCTURE, 116, 36, -180, 120, 52, -132, "wall_obsidian")
    add_box(STRUCTURE, 116, 12, -132, 120, 52, -120, "wall_obsidian")
    add_box(STRUCTURE, 116, 34, -120, 120, 52, -104, "wall_obsidian")
    add_box(STRUCTURE, 116, 12, -104, 120, 52, -88, "wall_obsidian")
    add_box(STRUCTURE, 116, 12, -40, 120, 52, -28, "wall_obsidian")
    add_box(STRUCTURE, 116, 44, -88, 120, 52, -40, "wall_obsidian")
    # East curtain-wall glazing bays: SKY BAR (48x24) at z = -156 and PULSE (48x32) at z = -64
    STRUCTURE.add_panel((118, 24, -156), 48, 24, "vip_win", "glass_curtain", facing="+X")
    STRUCTURE.add_panel((118, 28, -64), 48, 32, "side_win", "glass_curtain", facing="+X")
    # East doorway bronze frame & gated titanium door panel (door_panel_side)
    add_box(STRUCTURE, 115, 2, -122, 121, 36, -120, "trim_architectural")
    add_box(STRUCTURE, 115, 2, -104, 121, 36, -102, "trim_architectural")
    add_box(STRUCTURE, 115, 34, -122, 121, 36, -102, "trim_architectural")
    add_box(
        DOOR_PANEL_SIDE, 117, 2, -120, 119, 34, -104, "detail_utility", patch="steel",
        collision=True, collision_group="structure", source="door_panel_side_gate", gate_node="door_panel_side"
    )

    # --- REAR WALL & PRODUCTION LOADING DOCK (z in [-224, -220], x in [-120, 120]) ---
    # Rear artist/backstage doorway at x in [-80, -64], y in [2, 34] (door_back at x = -72, z = -226)
    add_box(
        STRUCTURE, -120, 2, -224, -80, 52, -220, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_west"
    )
    add_box(
        STRUCTURE, -64, 2, -224, 120, 52, -220, "wall_block",
        collision=True, collision_group="structure", source="rear_wall_east"
    )
    add_box(STRUCTURE, -80, 34, -224, -64, 52, -220, "wall_block")
    add_box(STRUCTURE, -82, 2, -225, -80, 36, -219, "trim_architectural")
    add_box(STRUCTURE, -64, 2, -225, -62, 36, -219, "trim_architectural")
    add_box(STRUCTURE, -82, 34, -225, -62, 36, -219, "trim_architectural")
    add_box(
        DOOR_PANEL_BACK, -80, 2, -223, -64, 34, -221, "detail_utility", patch="steel",
        collision=True, collision_group="structure", source="door_panel_back_gate", gate_node="door_panel_back"
    )

    # Raised concrete touring production loading dock (x in [-44, 44], z in [-244, -224], y in [1, 10])
    add_box(
        STRUCTURE, -44, 1, -244, 44, 8, -224, "wall_slate_dark",
        collision=True, collision_group="structure", source="loading_dock_base"
    )
    add_box(STRUCTURE, -46, 8, -246, 46, 10, -224, "ground_paving")
    for bx in (-36, -12, 12, 36):
        add_box(STRUCTURE, bx - 4, 2, -248, bx + 4, 9, -246, "detail_utility", patch="acoustic")
    # Dock access stairs on east end
    add_box(STRUCTURE, 46, 1, -242, 54, 7, -228, "ground_paving")
    add_box(STRUCTURE, 54, 1, -242, 62, 4, -228, "ground_paving")

    # Acoustic steel roll-up freight door above loading dock
    add_box(STRUCTURE, -26, 10, -226, 26, 44, -224, "detail_utility", patch="acoustic")
    add_box(STRUCTURE, -24, 10, -226.5, 24, 42, -224.5, "detail_utility", patch="steel")
    # Cantilevered bronze loading dock hood & downlight
    add_box(STRUCTURE, -46, 44, -242, 46, 47, -224, "trim_architectural")
    add_box(STRUCTURE, -44, 47, -240, 44, 48, -224, "roof_membrane", faces=("+Y",))
    STRUCTURE.add_panel((0, 43.8, -233), 16, 4, "lamp", "glow_led", facing="-Y")

    # Rear stage-door VIP VALET / artist access blade sign (48x12 = valet_sign)
    add_box(STRUCTURE, -98, 38, -227, -46, 52, -224, "detail_utility", patch="acoustic")
    STRUCTURE.add_panel((-72, 45, -227.4), 48, 12, "valet_sign", "glow_led", facing="-Z")

    # Twin galvanized steel HVAC risers on rear wall feeding rooftop air-handler
    for dx in (76, 96):
        add_box(STRUCTURE, dx - 4, 14, -230, dx + 4, 62, -224, "detail_utility", patch="steel")
        add_box(STRUCTURE, dx - 5, 28, -231, dx + 5, 30, -224, "detail_utility", patch="acoustic")
        add_box(STRUCTURE, dx - 5, 46, -231, dx + 5, 48, -224, "detail_utility", patch="acoustic")

    # --- INTERIOR MAIN-ROOM SHELL (LED Pulse Floor, Lighting Rig, DJ Video Wall, Onyx Bar & VIP Booths) ---
    # Ribbed dark acoustic ceiling baffles (y in [50, 52])
    add_box(STRUCTURE, -116, 50, -220, 116, 52, -4, "detail_utility", patch="led_panel", faces=("-Y",))

    # 1. Contemporary Geometric LED Pulse Dance Floor (96x64 = dance_led)
    # Centered in main room: x in [-48, 48], z in [-144, -80], y in [2, 3]
    add_box(STRUCTURE, -52, 2, -148, 52, 3, -76, "detail_utility", patch="acoustic")
    STRUCTURE.add_panel((0, 3.15, -112), 96, 64, "dance_led", "glow_led", facing="+Y")

    # 2. Suspended Brushed-Steel & Bronze Concert Lighting Truss Rig above dance floor (y in [42, 44])
    add_box(STRUCTURE, -50, 42, -146, 50, 44, -144, "detail_utility", patch="steel")
    add_box(STRUCTURE, -50, 42, -80, 50, 44, -78, "detail_utility", patch="steel")
    add_box(STRUCTURE, -50, 42, -144, -48, 44, -80, "detail_utility", patch="steel")
    add_box(STRUCTURE, 48, 42, -144, 50, 44, -80, "detail_utility", patch="steel")
    add_box(STRUCTURE, -48, 42, -113, 48, 44, -111, "trim_architectural")
    # 8 downward-facing linear architectural LED club lights (ny = -1 -> real Filament lights)
    for lx in (-72, -24, 24, 72):
        for lz in (-64, -144):
            STRUCTURE.add_panel((lx, 49.85, lz), 16, 4, "lamp", "glow_led", facing="-Y")

    # 3. Elevated Festival-Scale DJ Stage, Line-Array Speaker Towers & Full-Bleed LED Video Wall (z in [-218, -168])
    add_box(
        STRUCTURE, -76, 2, -218, 76, 8, -168, "detail_utility", patch="acoustic",
        collision=True, collision_group="structure", source="dj_stage_platform"
    )
    add_box(STRUCTURE, -76, 8, -218, 76, 10, -168, "trim_architectural")
    # Monolithic matte-obsidian & brushed-steel DJ Booth Console
    add_box(STRUCTURE, -36, 10, -190, 36, 22, -176, "detail_utility", patch="acoustic")
    add_box(STRUCTURE, -38, 22, -192, 38, 24, -174, "trim_architectural")
    # Tinted MAIN ROOM acoustic glass fascia on front of DJ booth (72x16 = dj_glass)
    STRUCTURE.add_panel((0, 19, -173.6), 72, 16, "dj_glass", "glass_curtain", facing="+Z")
    # Flanking Dual Floor-to-Ceiling Line-Array Sub/Top Speaker Towers
    for sx0, sx1 in [(-74, -54), (54, 74)]:
        add_box(STRUCTURE, sx0, 10, -212, sx1, 46, -192, "detail_utility", patch="acoustic")
        add_box(STRUCTURE, sx0 + 2, 12, -191.8, sx1 - 2, 26, -191, "detail_utility", patch="steel")
        add_box(STRUCTURE, sx0 + 2, 28, -191.8, sx1 - 2, 42, -191, "detail_utility", patch="steel")
    # Full-bleed 128x40 kinetic LED Video Wall behind DJ booth (dj_led_wall)
    STRUCTURE.add_panel((0, 30, -217.6), 128, 40, "dj_led_wall", "glow_led", facing="+Z")

    # 4. Built-in Illuminated Onyx & Brushed-Bronze Cocktail Bar along West Interior Wall (x in [-114, -76])
    add_box(
        STRUCTURE, -88, 2, -152, -76, 14, -48, "detail_utility", patch="acoustic",
        collision=True, collision_group="structure", source="cocktail_bar_counter"
    )
    add_box(STRUCTURE, -90, 14, -154, -74, 16, -46, "trim_architectural")
    add_box(
        STRUCTURE, -114, 2, -152, -88, 14, -142, "detail_utility", patch="acoustic",
        collision=True, collision_group="structure", source="cocktail_bar_north_return"
    )
    add_box(
        STRUCTURE, -114, 2, -58, -88, 14, -48, "detail_utility", patch="acoustic",
        collision=True, collision_group="structure", source="cocktail_bar_south_return"
    )
    # Backlit warm amber onyx bottle display wall (96x24 = bar_display)
    add_box(STRUCTURE, -116, 18, -148, -112, 44, -50, "detail_utility", patch="acoustic")
    STRUCTURE.add_panel((-111.6, 31, -99), 96, 24, "bar_display", "glow_led", facing="+X")

    # 5. Built-in Royal-Plum Velvet VIP Bottle-Service Banquette Alcoves along East Wall (x in [84, 114])
    for bz in (-92, -62):
        add_box(
            STRUCTURE, 84, 2, bz, 114, 14, bz + 20, "detail_utility", patch="velvet",
            collision=True, collision_group="structure", source=f"vip_banquette_{bz}"
        )
        add_box(STRUCTURE, 84, 14, bz, 114, 22, bz + 4, "detail_utility", patch="acoustic")
        add_box(STRUCTURE, 84, 14, bz + 16, 114, 22, bz + 20, "detail_utility", patch="acoustic")


# ==============================================================================
# 3. OPTIONAL PROP GROUPS (< 500 triangles each)
#    - led_monolith: Sleek 11.17 m obsidian & bronze architectural street totem
#    - service_yard: Screened dark-slate east mechanical chiller & waste yard
#    - valet_plaza: Modern valet lectern, champagne-bronze stanchions & travertine planters
# ==============================================================================
def build_led_monolith():
    # Placed at front-right boulevard corner: x = 134, z = 34, total height = 144*U = 11.1744 m (<= 12.0 m)
    cx, cz = 134, 34
    add_box(
        LED_MONOLITH, cx - 18, 1, cz - 10, cx + 18, 12, cz + 10, "wall_travertine",
        collision=True, collision_group="lot", source="led_monolith_base"
    )
    add_box(LED_MONOLITH, cx - 20, 12, cz - 12, cx + 20, 15, cz + 12, "trim_architectural")

    # Sculptural monolithic matte-obsidian & brushed-bronze totem shaft rising to y = 118
    add_box(LED_MONOLITH, cx - 26, 15, cz - 6, cx + 26, 118, cz + 6, "wall_obsidian")
    add_box(LED_MONOLITH, cx - 28, 15, cz - 7, cx - 26, 120, cz + 7, "trim_architectural")
    add_box(LED_MONOLITH, cx + 26, 15, cz - 7, cx + 28, 120, cz + 7, "trim_architectural")
    add_box(LED_MONOLITH, cx - 28, 118, cz - 7, cx + 28, 120, cz + 7, "trim_architectural")

    # Lower double-sided VIP / 22-04 LED readout panel (48x24 = monolith_mid)
    LED_MONOLITH.add_panel((cx, 44, cz + 6.5), 48, 24, "monolith_mid", "glow_led", facing="+Z")
    LED_MONOLITH.add_panel((cx, 44, cz - 6.5), 48, 24, "monolith_mid", "glow_led", facing="-Z")

    # Upper double-sided PULSE CLUB architectural LED header (48x32 = monolith_top)
    LED_MONOLITH.add_panel((cx, 92, cz + 6.5), 48, 32, "monolith_top", "glow_led", facing="+Z")
    LED_MONOLITH.add_panel((cx, 92, cz - 6.5), 48, 32, "monolith_top", "glow_led", facing="-Z")

    # Crown kinetic pulse diamond emblem (24x24 = emblem), y in [120, 144] (144*U = 11.1744 m)
    add_box(LED_MONOLITH, cx - 13, 120, cz - 4, cx + 13, 144, cz + 4, "detail_utility", patch="acoustic")
    add_box(LED_MONOLITH, cx - 15, 120, cz - 5, cx - 13, 144, cz + 5, "trim_architectural")
    add_box(LED_MONOLITH, cx + 13, 120, cz - 5, cx + 15, 144, cz + 5, "trim_architectural")
    LED_MONOLITH.add_panel((cx, 132, cz + 4.5), 24, 24, "emblem", "glow_led", facing="+Z")
    LED_MONOLITH.add_panel((cx, 132, cz - 4.5), 24, 24, "emblem", "glow_led", facing="-Z")


def build_service_yard():
    # Screened mechanical chiller & dumpster enclosure on east side: x in [120, 164], z in [-212, -148]
    add_box(SERVICE_YARD, 120, 1, -212, 164, 2, -148, "ground_paving", faces=("+Y", "+Z", "-Z", "+X"))
    add_box(
        SERVICE_YARD, 160, 2, -212, 164, 26, -148, "wall_slate_dark",
        collision=True, collision_group="lot", source="service_yard_east_wall"
    )
    add_box(
        SERVICE_YARD, 120, 2, -212, 160, 26, -208, "wall_slate_dark",
        collision=True, collision_group="lot", source="service_yard_north_wall"
    )
    add_box(
        SERVICE_YARD, 138, 2, -152, 160, 26, -148, "wall_slate_dark",
        collision=True, collision_group="lot", source="service_yard_south_wall"
    )
    add_box(SERVICE_YARD, 159, 26, -213, 165, 28, -147, "trim_architectural")
    add_box(SERVICE_YARD, 120, 26, -213, 165, 28, -207, "trim_architectural")

    for cz in (-198, -176):
        add_box(
            SERVICE_YARD, 122, 2, cz, 138, 20, cz + 16, "detail_utility", patch="steel",
            collision=True, collision_group="lot", source=f"hvac_chiller_{cz}"
        )
        add_box(SERVICE_YARD, 123, 20, cz + 1, 137, 22, cz + 15, "detail_utility", patch="acoustic")

    add_box(
        SERVICE_YARD, 144, 2, -202, 158, 16, -184, "detail_utility", patch="steel",
        collision=True, collision_group="lot", source="club_dumpster"
    )
    add_box(SERVICE_YARD, 143, 16, -203, 159, 18, -183, "detail_utility", patch="acoustic")


def build_valet_plaza():
    """
    Modern valet lectern, brushed-bronze VIP rope stanchions along the east front plaza
    (x in [28, 84], z = 14), and architectural travertine plaza planters — keeping x = 0 clear!
    """
    post_xs = [28, 46, 64, 82]
    for i, px in enumerate(post_xs):
        add_box(VALET_PLAZA, px - 2, 2, 12, px + 2, 3, 16, "detail_utility", patch="steel")
        add_box(VALET_PLAZA, px - 1, 3, 13, px + 1, 14, 15, "trim_architectural")
        if i < len(post_xs) - 1:
            nx = post_xs[i + 1]
            add_box(VALET_PLAZA, px + 1, 10, 13.5, nx - 1, 12, 14.5, "detail_utility", patch="velvet")

    # Modern obsidian & champagne-bronze VIP Valet host podium at x = 23, z = 14
    add_box(
        VALET_PLAZA, 20, 2, 10, 26, 16, 18, "detail_utility", patch="acoustic",
        collision=True, collision_group="lot", source="vip_valet_podium"
    )
    add_box(VALET_PLAZA, 19, 16, 9, 27, 18, 19, "trim_architectural")

    # Twin modern architectural travertine & teak seating planters framing the outer sidewalk
    for px0, px1, tag in [(-126, -108, "west"), (-42, -26, "mid_west")]:
        add_box(
            VALET_PLAZA, px0, 2, 32, px1, 10, 42, "wall_travertine",
            collision=True, collision_group="lot", source=f"plaza_planter_{tag}"
        )
        add_box(VALET_PLAZA, px0 + 1, 10, 33, px1 - 1, 12, 41, "wood_teak")


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
    # 2. door_back: rear artist/backstage door at (-72*U, 0, -226*U), facing -Z ([0, 1, 0, 0])
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
            mat_obj["emissiveFactor"] = [0.22, 0.22, 0.22]
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
            "generator": "PULSE Modern Nightclub & Sky Lounge Procedural Builder (LANDMARKS_GLB contract)",
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

    glb_path = R / "pulse-nightclub.glb"
    with open(glb_path, "wb") as f:
        f.write(glb_header)
        f.write(json_hdr)
        f.write(json_chunk)
        f.write(bin_hdr)
        f.write(bin_chunk)

    model_info = {
        "name": "Club Pulse",
        "slug": "pulse-nightclub",
        "era": "2020s",
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
            "max_height": round(144 * U, 4),
        },
        "triangles": {
            "visible": visible_tris,
            "collision": collision_tris,
            "total": visible_tris + collision_tris,
            "budget_visible_max": 12000,
        },
        "optional_prop_groups": {
            "lot": subtree_tris(LOT),
            "led_monolith": subtree_tris(LED_MONOLITH),
            "service_yard": subtree_tris(SERVICE_YARD),
            "valet_plaza": subtree_tris(VALET_PLAZA),
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
            "The open-air rooftop Sky Lounge terrace (teak decking, glass balustrades, cantilevered bar pergola) is a visual exterior centerpiece visible from elevated, street, and aerial views.",
            "Optional prop groups (led_monolith, service_yard, valet_plaza) can be hidden independently along with their tagged collision boxes.",
        ],
        "swatches_used": [
            "wall_slate_dark",
            "wall_slate_light",
            "block_courses",
            "plank_horizontal",
            "roof_membrane_grey",
            "asphalt",
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
    build_led_monolith()
    build_service_yard()
    build_valet_plaza()
    build_doors()
    export_glb_and_info()


if __name__ == "__main__":
    main()
