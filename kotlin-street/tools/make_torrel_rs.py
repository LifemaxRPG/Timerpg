#!/usr/bin/env python3
"""Builds the race Torrel (torrel-1988-rs, docs/queue/tasks/C046.md) from the road Torrel's glTF (torrel-1988, C017).

    python3 -I tools/make_torrel_rs.py [assets/vehicles dir]

The same car after a racing shop got hold of it: the road car's body, lamps and cabin kept, lowered 50 mm, and bolted-on
race parts added as simple low-poly geometry in the game's pixel-art style (nearest-filtered textures on the Torrel's
0.0388 m texel grid, its own palette): a big two-element rear wing on tall uprights (the road car's small wing goes),
riveted bolt-on arches front and rear over a wider track with wider, lower tyres on light multi-spoke race wheels, a
deeper front air dam with larger intakes, a splitter and canards, a stripped interior (both road seats out: a race bucket
with a harness, a roll cage, a fire extinguisher), quick-release bonnet pins, a fuel filler on the bonnet, tow hooks,
a centre exhaust (the road car's twin pipes go) and two liveries of our own (stripes and blocks, number roundels, a sun
strip naming a made-up shop) as decals the game shows one of (`livery_<id>_*` nodes; model-info.json paint.liveries).

Writes torrel-1988-rs/{torrel-1988-rs.glb, -mid.glb, -far.glb, model-info.json, textures/} next to torrel-1988 (or the
given dir). The mid and far models are the road car's own, changed the same way with fewer triangles. Deterministic: the
same input gives the same files. Needs numpy, Pillow and pygltflib (pip install pygltflib).
"""
import copy
import io
import json
import math
import os
import random
import sys

import numpy as np
import pygltflib
from PIL import Image

TEX = 0.0388            # the Torrel's texel (m)
TILE = 64 * TEX         # its paint tile (64 texels) in metres
DROP = 0.05             # lowered (m)
SRC, KEY = "torrel-1988", "torrel-1988-rs"

# the race running gear: hubs (x, y, z) and tyres (radius, width); the road car's are (0.739, 0.311, 1.18) r .311 w .205
# and (0.765, 0.323, -1.18) r .323 w .235
FRONT = dict(x=0.800, z=1.18, r=0.300, w=0.245)
REAR = dict(x=0.830, z=-1.18, r=0.312, w=0.285)

# ---------------------------------------------------------------- the palette (sampled from the Torrel's own textures)
P = dict(
    frp=[(42, 53, 53), (49, 59, 57), (57, 65, 62), (62, 66, 63)],          # dark trim / black fibreglass (finish atlas)
    frp_hi=(74, 80, 75), frp_lo=(34, 42, 43),
    alu=[(133, 142, 148), (149, 158, 164), (145, 152, 158)], alu_hi=(190, 198, 202), alu_lo=(91, 100, 105),
    rubber=[(30, 33, 34), (36, 39, 40), (33, 36, 37)], rubber_hi=(52, 56, 57),
    mesh_dark=(20, 24, 26), mesh=(57, 65, 62),
    fabric=[(64, 70, 74), (57, 65, 62), (68, 74, 78)], stitch=(91, 100, 105),
    red=[(184, 75, 71), (170, 66, 63), (196, 86, 80)], red_lo=(130, 50, 48),
    cage=[(214, 210, 196), (205, 201, 186), (222, 218, 204)], cage_lo=(160, 156, 142),
    white=(232, 228, 214), black=(26, 30, 32),
    navy=[(35, 50, 74), (40, 56, 80), (31, 45, 68)],
    amber=[(224, 163, 58), (213, 152, 50), (232, 172, 68)],
    orange=[(206, 94, 49), (196, 86, 42), (216, 104, 58)],
    yellow=(228, 196, 72),
)


def noise(w, h, shades, seed, run=(2, 5)):
    """The Torrel's paint style: horizontal runs of a few close shades (its 64 px tiles), base mostly."""
    rnd = random.Random(seed)
    a = np.zeros((h, w, 3), np.uint8)
    a[:] = shades[0]
    for y in range(h):
        x = rnd.randrange(0, 3)
        while x < w:
            n = rnd.randint(*run)
            if rnd.random() < .42:
                a[y, x:x + n] = shades[rnd.randrange(1, len(shades))]
            x += n + rnd.randint(1, 4)
    return a


# ---------------------------------------------------------------- the race kit's atlas (one picture, packed by shelf)
class Atlas:
    W = 256

    def __init__(self):
        self.h = 512
        self.img = np.zeros((self.h, self.W, 4), np.uint8)
        self.x = self.y = self.shelf = 0
        self.seed = 0

    def alloc(self, w, h):
        w = max(1, int(w)); h = max(1, int(h))
        if self.x + w > self.W:
            self.x = 0; self.y += self.shelf + 1; self.shelf = 0
        if self.y + h > self.h:
            raise RuntimeError("race kit atlas full")
        r = (self.x, self.y, w, h)
        self.x += w + 1; self.shelf = max(self.shelf, h)
        return r

    def put(self, pic):
        """Packs a picture (h, w, 3 or 4) and returns its rect."""
        h, w = pic.shape[:2]
        r = self.alloc(w, h)
        if pic.shape[2] == 3:
            pic = np.concatenate([pic, np.full((h, w, 1), 255, np.uint8)], 2)
        self.img[r[1]:r[1] + h, r[0]:r[0] + w] = pic
        # a texel of bleed round it (nearest sampling at a face's edge stays inside its own colours)
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            x0, y0 = r[0] + dx, r[1] + dy
            xs = slice(max(0, x0), min(self.W, x0 + w)); ys = slice(max(0, y0), min(self.h, y0 + h))
            sub = self.img[ys, xs]
            src = pic[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0]
            empty = sub[..., 3] == 0
            sub[empty] = src[empty]
        return r

    def uv(self, r, s, t):
        """Atlas uv of a point at (s, t) in 0..1 of rect r."""
        return ((r[0] + s * r[2]) / self.W, (r[1] + t * r[3]) / self.h)

    def png(self):
        used = self.y + self.shelf + 2
        h = 1 << max(6, int(math.ceil(math.log2(used))))
        self.h_out = h
        return self.img[:h]


# ---------------------------------------------------------------- the model: nodes with meshes per material
class Prim:
    def __init__(self, mat, pos, nrm, uv, idx):
        self.mat = mat; self.pos = np.asarray(pos, np.float32).reshape(-1, 3); self.nrm = np.asarray(nrm, np.float32).reshape(-1, 3)
        self.uv = np.asarray(uv, np.float32).reshape(-1, 2); self.idx = np.asarray(idx, np.int64).reshape(-1, 3)

    @property
    def tris(self): return len(self.idx)


class Node:
    def __init__(self, name, t=(0, 0, 0), parent=-1, extras=None):
        self.name = name; self.t = np.array(t, float); self.parent = parent; self.prims = []; self.extras = extras or {}


class Model:
    def __init__(self):
        self.nodes = []; self.gltf = None; self.images = {}  # material name -> png bytes

    @staticmethod
    def read(path):
        g = pygltflib.GLTF2().load(path)
        blob = g.binary_blob()
        m = Model(); m.gltf = g

        def acc(i):
            a = g.accessors[i]; bv = g.bufferViews[a.bufferView]
            n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a.type]
            dt = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}[a.componentType]
            return np.frombuffer(blob, dtype=dt, count=a.count * n, offset=bv.byteOffset + (a.byteOffset or 0)).reshape(-1, n).copy()
        parent = {}
        for i, n in enumerate(g.nodes):
            for c in n.children: parent[c] = i
        for i, n in enumerate(g.nodes):
            assert not n.rotation and not n.scale, n.name
            nd = Node(n.name, n.translation or (0, 0, 0), parent.get(i, -1), dict(n.extras or {}))
            if n.mesh is not None:
                for p in g.meshes[n.mesh].primitives:
                    nd.prims.append(Prim(g.materials[p.material].name, acc(p.attributes.POSITION), acc(p.attributes.NORMAL),
                                         acc(p.attributes.TEXCOORD_0), acc(p.indices)))
            m.nodes.append(nd)
        for mat in g.materials:
            tex = mat.pbrMetallicRoughness.baseColorTexture
            if tex is not None:
                im = g.images[g.textures[tex.index].source]; bv = g.bufferViews[im.bufferView]
                m.images[mat.name] = blob[bv.byteOffset:bv.byteOffset + bv.byteLength]
            if mat.emissiveTexture is not None:
                im = g.images[g.textures[mat.emissiveTexture.index].source]; bv = g.bufferViews[im.bufferView]
                m.images[mat.name + ":emissive"] = blob[bv.byteOffset:bv.byteOffset + bv.byteLength]
        return m

    def find(self, name):
        for i, n in enumerate(self.nodes):
            if n.name == name: return i
        return -1

    @property
    def root(self): return next(i for i, n in enumerate(self.nodes) if n.parent < 0 and n.name.startswith("torrel"))

    def world(self, i):
        t = np.zeros(3)
        while i >= 0: t += self.nodes[i].t; i = self.nodes[i].parent
        return t

    def add(self, name, parent=None, t=(0, 0, 0), extras=None):
        self.nodes.append(Node(name, t, self.root if parent is None else parent, extras))
        return len(self.nodes) - 1

    def children(self, i): return [k for k, n in enumerate(self.nodes) if n.parent == i]

    def tris(self, skip_livery=None):
        n = 0
        for i, nd in enumerate(self.nodes):
            if skip_livery is not None and self.livery_of(i) not in (None, skip_livery): continue
            n += sum(p.tris for p in nd.prims)
        return n

    def livery_of(self, i):
        while i >= 0:
            nm = self.nodes[i].name
            if nm.startswith("livery_"): return nm.split("_")[1]
            i = self.nodes[i].parent
        return None

    def bounds(self):
        lo = np.full(3, 1e9); hi = np.full(3, -1e9)
        for i, nd in enumerate(self.nodes):
            w = self.world(i)
            for p in nd.prims:
                if len(p.pos): lo = np.minimum(lo, p.pos.min(0) + w); hi = np.maximum(hi, p.pos.max(0) + w)
        return lo, hi

    def write(self, path, kit_png):
        """A .glb: the road car's materials (and pictures) plus `race_kit`, one nearest sampler, our nodes."""
        g0 = self.gltf
        g = pygltflib.GLTF2(asset=pygltflib.Asset(version="2.0", generator="Timerpg tools/make_torrel_rs.py"))
        g.extensionsUsed = list(g0.extensionsUsed or [])
        g.samplers = [pygltflib.Sampler(magFilter=9728, minFilter=9984, wrapS=10497, wrapT=10497)]
        used = []
        for nd in self.nodes:
            for p in nd.prims:
                if p.mat not in used: used.append(p.mat)
        order = [m.name for m in g0.materials if m.name in used] + (["race_kit"] if "race_kit" in used else [])
        blob = bytearray()

        def view(data, target=None):
            while len(blob) % 4: blob.append(0)
            off = len(blob); blob.extend(data)
            g.bufferViews.append(pygltflib.BufferView(buffer=0, byteOffset=off, byteLength=len(data), target=target))
            return len(g.bufferViews) - 1

        def image(name, png):
            g.images.append(pygltflib.Image(name=name, mimeType="image/png", bufferView=view(png)))
            g.textures.append(pygltflib.Texture(sampler=0, source=len(g.images) - 1))
            return len(g.textures) - 1
        mat_index = {}
        for name in order:
            if name == "race_kit":
                buf = io.BytesIO(); Image.fromarray(kit_png, "RGBA").save(buf, "PNG", optimize=True)
                ti = image(KEY + "-race-kit", buf.getvalue())
                g.materials.append(pygltflib.Material(name="race_kit", doubleSided=True, alphaMode="MASK", alphaCutoff=.5,
                    pbrMetallicRoughness=pygltflib.PbrMetallicRoughness(baseColorTexture=pygltflib.TextureInfo(index=ti), metallicFactor=0., roughnessFactor=.8)))
            else:
                src = next(m for m in g0.materials if m.name == name)
                m = copy.deepcopy(src)
                if name in self.images:
                    m.pbrMetallicRoughness.baseColorTexture = pygltflib.TextureInfo(index=image(KEY + "-" + name, self.images[name]))
                if name + ":emissive" in self.images:
                    m.emissiveTexture = pygltflib.TextureInfo(index=image(KEY + "-" + name + "-emission", self.images[name + ":emissive"]))
                g.materials.append(m)
            mat_index[name] = len(g.materials) - 1

        def accessor(arr, kind, target, minmax=False):
            arr = np.ascontiguousarray(arr)
            ct = {np.dtype(np.float32): 5126, np.dtype(np.uint16): 5123, np.dtype(np.uint32): 5125}[arr.dtype]
            a = pygltflib.Accessor(bufferView=view(arr.tobytes(), target), componentType=ct, count=len(arr), type=kind)
            if minmax: a.min = arr.min(0).tolist(); a.max = arr.max(0).tolist()
            g.accessors.append(a)
            return len(g.accessors) - 1
        kids = {i: [] for i in range(len(self.nodes))}
        for i, nd in enumerate(self.nodes):
            if nd.parent >= 0: kids[nd.parent].append(i)
        for i, nd in enumerate(self.nodes):
            n = pygltflib.Node(name=nd.name, children=kids[i])
            if np.any(nd.t != 0): n.translation = [round(float(v), 6) for v in nd.t]
            if nd.extras: n.extras = nd.extras
            prims = [p for p in nd.prims if p.tris > 0]
            if prims:
                mesh = pygltflib.Mesh(name=nd.name, primitives=[])
                for p in prims:
                    idx = p.idx.ravel().astype(np.uint16 if len(p.pos) < 65536 else np.uint32)
                    mesh.primitives.append(pygltflib.Primitive(
                        attributes=pygltflib.Attributes(POSITION=accessor(p.pos.astype(np.float32), "VEC3", 34962, True),
                                                        NORMAL=accessor(p.nrm.astype(np.float32), "VEC3", 34962),
                                                        TEXCOORD_0=accessor(p.uv.astype(np.float32), "VEC2", 34962)),
                        indices=accessor(idx, "SCALAR", 34963), material=mat_index[p.mat]))
                g.meshes.append(mesh); n.mesh = len(g.meshes) - 1
            g.nodes.append(n)
        g.scenes = [pygltflib.Scene(nodes=[i for i, nd in enumerate(self.nodes) if nd.parent < 0])]
        g.scene = 0
        while len(blob) % 4: blob.append(0)
        g.buffers = [pygltflib.Buffer(byteLength=len(blob))]
        g.set_binary_blob(bytes(blob))
        g.save_binary(path)


# ---------------------------------------------------------------- building geometry
class Builder:
    """Faces into a node's prims (positions in the node's frame, given in the car's frame), flat-shaded."""

    def __init__(self, model, atlas):
        self.m = model; self.atlas = atlas

    def prim(self, node, mat):
        nd = self.m.nodes[node]
        for p in nd.prims:
            if p.mat == mat: return p
        p = Prim(mat, np.zeros((0, 3)), np.zeros((0, 3)), np.zeros((0, 2)), np.zeros((0, 3)))
        nd.prims.append(p)
        return p

    def poly(self, node, pts, uvs, mat, normal=None):
        """A convex polygon (car frame, any winding: it faces [normal] or its own winding's way)."""
        pts = np.asarray(pts, float)
        n = np.cross(pts[1] - pts[0], pts[2] - pts[0])
        if np.linalg.norm(n) < 1e-12:
            n = np.cross(pts[2] - pts[0], pts[-1] - pts[0])
        n = n / max(1e-12, np.linalg.norm(n))
        if normal is not None and np.dot(n, normal) < 0:
            pts = pts[::-1]; uvs = list(uvs)[::-1]; n = -n
        p = self.prim(node, mat)
        base = len(p.pos)
        w = self.m.world(node)
        p.pos = np.vstack([p.pos, pts - w]); p.nrm = np.vstack([p.nrm, np.tile(n, (len(pts), 1))])
        p.uv = np.vstack([p.uv, np.asarray(uvs, float)])
        p.idx = np.vstack([p.idx, [[base, base + k, base + k + 1] for k in range(1, len(pts) - 1)]])

    def face(self, node, pts, mat, style=None, normal=None, udir=None, pic=None):
        """A convex face with its texture: race_kit faces get their own picture packed at the true texel size (by
        [style] (w, h) -> picture, or [pic]); paint and trim faces get the tiling paint's uv, projected."""
        pts = np.asarray(pts, float)
        n = np.cross(pts[1] - pts[0], pts[2] - pts[0])
        if np.linalg.norm(n) < 1e-12: n = np.cross(pts[2] - pts[0], pts[-1] - pts[0])
        n /= np.linalg.norm(n)
        if normal is not None and np.dot(n, normal) < 0: n = -n
        u = np.array(udir if udir is not None else (pts[1] - pts[0]), float)
        u = u - n * np.dot(u, n); u /= np.linalg.norm(u)
        v = np.cross(n, u)
        s = (pts - pts[0]) @ u; t = (pts - pts[0]) @ v
        if mat == "race_kit":
            s0, s1, t0, t1 = s.min(), s.max(), t.min(), t.max()
            w = max(1, int(round((s1 - s0) / TEX))); h = max(1, int(round((t1 - t0) / TEX)))
            if pic is None:
                self.atlas.seed += 1
                pic = style(w, h, self.atlas.seed)
            r = self.atlas.put(pic)
            uvs = [self.atlas.uv(r, (a - s0) / max(1e-9, s1 - s0), 1 - (b - t0) / max(1e-9, t1 - t0)) for a, b in zip(s, t)]
        else:
            uvs = [(a / TILE, b / TILE) for a, b in zip(s, t)]
        self.poly(node, pts, uvs, mat, n)

    def box(self, node, c, size, mat, style=None, styles=None, axes=None):
        """A box (centre, size) in the car's frame, turned by [axes] (3 unit vectors: its x, y, z)."""
        c = np.asarray(c, float); h = np.asarray(size, float) / 2
        ax = np.eye(3) if axes is None else np.asarray(axes, float)
        corner = lambda sx, sy, sz: c + ax[0] * sx * h[0] + ax[1] * sy * h[1] + ax[2] * sz * h[2]
        faces = {
            "+x": ([corner(1, -1, -1), corner(1, -1, 1), corner(1, 1, 1), corner(1, 1, -1)], ax[0], ax[2]),
            "-x": ([corner(-1, -1, 1), corner(-1, -1, -1), corner(-1, 1, -1), corner(-1, 1, 1)], -ax[0], -ax[2]),
            "+y": ([corner(-1, 1, -1), corner(1, 1, -1), corner(1, 1, 1), corner(-1, 1, 1)], ax[1], ax[0]),
            "-y": ([corner(-1, -1, 1), corner(1, -1, 1), corner(1, -1, -1), corner(-1, -1, -1)], -ax[1], ax[0]),
            "+z": ([corner(1, -1, 1), corner(-1, -1, 1), corner(-1, 1, 1), corner(1, 1, 1)], ax[2], -ax[0]),
            "-z": ([corner(-1, -1, -1), corner(1, -1, -1), corner(1, 1, -1), corner(-1, 1, -1)], -ax[2], ax[0]),
        }
        for k, (pts, nrm, ud) in faces.items():
            st = (styles or {}).get(k, style)
            if st is False: continue
            self.face(node, pts, mat if not isinstance(st, tuple) else st[0], st if not isinstance(st, tuple) else st[1], nrm, ud)

    def tube(self, node, a, b, size, mat, style):
        """A square tube from a to b (no end caps): the roll cage's."""
        a = np.asarray(a, float); b = np.asarray(b, float)
        z = b - a; L = np.linalg.norm(z); z /= L
        x = np.cross((0, 1, 0), z) if abs(z[1]) < .9 else np.cross((1, 0, 0), z)
        x /= np.linalg.norm(x); y = np.cross(z, x)
        self.box(node, (a + b) / 2, (size, size, L), mat, style, styles={"+z": False, "-z": False}, axes=(x, y, z))

    def prism(self, node, profile, x0, x1, mat_sides, caps=True, cap_mat=None, cap_style=None):
        """A profile in (z, y) (convex, in order) extruded along x from x0 to x1; mat_sides: per edge (mat, style)."""
        k = len(profile)
        for i in range(k):
            (z0, y0), (z1, y1) = profile[i], profile[(i + 1) % k]
            mat, st = mat_sides[i] if isinstance(mat_sides, list) else mat_sides
            if mat is None: continue
            pts = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z1), (x0, y1, z1)]
            cz, cy = np.mean(profile, 0)
            out = np.array([0, (y0 + y1) / 2 - cy, (z0 + z1) / 2 - cz])
            self.face(node, pts, mat, st, out, udir=(1, 0, 0))
        if caps:
            for x, s in ((x0, -1), (x1, 1)):
                self.face(node, [(x, y, z) for z, y in profile], cap_mat or mat_sides[0][0], cap_style, (s, 0, 0), udir=(0, 0, 1))


# ---------------------------------------------------------------- styles (pictures for race-kit faces)
def st_frp(w, h, seed):
    a = noise(w, h, P["frp"], seed)
    return a

def st_frp_rivets(w, h, seed, row=None):
    a = noise(w, h, P["frp"], seed)
    if h >= 3:
        a[0, :] = P["frp_hi"]
        r = 1 if row is None else row
        a[r, 1::3] = P["alu_hi"]
    elif h == 2:
        a[0, 1::3] = P["alu_hi"]
    return a

def st_alu(w, h, seed):
    a = noise(w, h, P["alu"], seed)
    if h > 1: a[0, :] = P["alu_hi"]
    return a

def st_rubber(w, h, seed):
    a = noise(w, h, P["rubber"], seed, run=(1, 3))
    return a

def st_tread(w, h, seed):
    a = noise(w, h, P["rubber"], seed, run=(1, 2))
    a[:, ::3] = P["rubber_hi"] if h > 2 else a[:, ::3]
    return a

def st_mesh(w, h, seed):
    a = np.zeros((h, w, 3), np.uint8); a[:] = P["mesh_dark"]
    a[::2, ::2] = P["mesh"]; a[1::2, 1::2] = P["mesh"]
    a[0, :] = P["frp_hi"] if h > 2 else a[0, :]
    return a

def st_fabric(w, h, seed):
    a = noise(w, h, P["fabric"], seed, run=(1, 3))
    if h > 4: a[h // 2, :] = P["stitch"]
    return a

def st_red(w, h, seed):
    a = noise(w, h, P["red"], seed)
    return a

def st_cage(w, h, seed):
    return noise(w, h, P["cage"], seed)

def st_black(w, h, seed):
    a = np.zeros((h, w, 3), np.uint8); a[:] = P["black"]; return a

def solid(c):
    def f(w, h, seed):
        a = np.zeros((h, w, 3), np.uint8); a[:] = c; return a
    return f


# a 3 x 5 pixel font for the decals (digits and the capitals we use)
FONT = {
    "0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111", "4": "101101111001001",
    "5": "111100111001111", "6": "111100111101111", "7": "111001010010010", "8": "111101111101111", "9": "111101111001111",
    "A": "111101111101101", "D": "110101101101110", "E": "111100110100111", "H": "101101111101101", "L": "100100100100111",
    "N": "110101101101101", "O": "111101101101111", "R": "110101110101101", "S": "111100111001111", "T": "111010010010010",
    "V": "101101101101010", "W": "101101111111101", "K": "101110100110101", "-": "000000111000000", " ": "000000000000000",
}

def text(a, s, x, y, colour):
    for ch in s:
        g = FONT[ch]
        for k, bit in enumerate(g):
            if bit == "1":
                yy, xx = y + k // 3, x + k % 3
                if 0 <= yy < a.shape[0] and 0 <= xx < a.shape[1]: a[yy, xx, :3] = colour
        x += 4

def text_w(s): return 4 * len(s) - 1


# ---------------------------------------------------------------- the liveries (our own: no real makers, sponsors or racing colours)
LIVERIES = [
    # Halvane Speedshop (made up): race white with twin navy stripes over the car, red pinstripes, white roundels
    dict(id="a", name="Halvane stripes", body="#ece4cc", number="27", shop="HALVANE",
         stripe=P["navy"], pin=P["red"][0], roundel=P["white"], ink=P["black"], strip_bg=P["navy"][0], strip_ink=P["white"]),
    # Ostrand Motorsport (made up): dark slate with an amber side flash under an orange band, an orange nose, yellow roundels
    dict(id="b", name="Ostrand flash", body="#2c3a46", number="7", shop="OSTRAND",
         flash=P["amber"], band=P["orange"], roundel=P["yellow"], ink=P["black"], strip_bg=P["amber"][0], strip_ink=P["black"]),
]


# ---------------------------------------------------------------- decals: the car's own skin clipped to a region
def clip_poly(poly, region):
    """Sutherland-Hodgman: a 2D polygon (list of (x, y, payload...)) against a convex region (counter-clockwise)."""
    out = poly
    for i in range(len(region)):
        a = np.array(region[i]); b = np.array(region[(i + 1) % len(region)])
        inp = out; out = []
        if not inp: break
        def inside(p): return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-12
        def cut(p, q):
            d1 = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
            d2 = (b[0] - a[0]) * (q[1] - a[1]) - (b[1] - a[1]) * (q[0] - a[0])
            t = d1 / (d1 - d2)
            return tuple(np.array(p) + t * (np.array(q) - np.array(p)))
        for k in range(len(inp)):
            p = inp[k]; q = inp[(k + 1) % len(inp)]
            if inside(q):
                if not inside(p): out.append(cut(p, q))
                out.append(q)
            elif inside(p): out.append(cut(p, q))
    return out


def ccw(region):
    r = np.asarray(region, float)
    area = np.sum(r[:, 0] * np.roll(r[:, 1], -1) - np.roll(r[:, 0], -1) * r[:, 1])
    return [tuple(p) for p in (r if area > 0 else r[::-1])]


class Skin:
    """The car's outer surfaces (paint and glass) in the car's frame, for decals."""

    def __init__(self, model, mats=("paint_primary", "paint_secondary", "glass_vehicle")):
        self.m = model; self.tris = []  # (node, a, b, c, normal)
        for i, nd in enumerate(model.nodes):
            if model.livery_of(i) is not None: continue
            w = model.world(i)
            for p in nd.prims:
                if p.mat not in mats: continue
                for t in p.idx:
                    a, b, c = p.pos[t] + w
                    n = np.cross(b - a, c - a); l = np.linalg.norm(n)
                    if l < 1e-10: continue
                    n = n / l
                    # (the mesh's own normals say which way is out: double-sided shells keep their outward one)
                    if np.dot(n, p.nrm[t].mean(0)) < 0: n = -n
                    self.tris.append((i, a, b, c, n, p.mat))
        self.A = np.array([t[1] for t in self.tris]); self.B = np.array([t[2] for t in self.tris]); self.C = np.array([t[3] for t in self.tris])

    def hit(self, o, d, skip):
        """Distances along d from o to every skin triangle (inf where it misses), Moller-Trumbore."""
        e1 = self.B - self.A; e2 = self.C - self.A
        pv = np.cross(d, e2); det = np.einsum('ij,ij->i', e1, pv)
        ok = np.abs(det) > 1e-12
        inv = np.where(ok, 1 / np.where(ok, det, 1), 0)
        tv = o - self.A
        u = np.einsum('ij,ij->i', tv, pv) * inv
        qv = np.cross(tv, e1)
        v = (qv @ d) * inv
        t = np.einsum('ij,ij->i', e2, qv) * inv
        good = ok & (u >= 0) & (v >= 0) & (u + v <= 1) & (t > 1e-4)
        good[skip] = False
        return np.where(good, t, np.inf)


def decal(builder, skin, livery_id, region, plane, pic, offset=.007, mats=("paint_primary", "paint_secondary"), occlude=.15, min_dot=.3):
    """Clips the skin's triangles facing [plane]'s direction to [region] (2D, in the plane's (s, t)) and lays a copy of
    them [offset] out along their normals, wearing [pic] (packed once, stretched over the region's bounds), as nodes
    `livery_<id>_<node>` under each piece's node."""
    # (each plane's (s, t) as someone looking at it sees it: s to their right, t up; the top as seen from in front)
    axes = dict(top=((1, 0, 0), (0, 0, -1), (0, 1, 0)), left=((0, 0, -1), (0, 1, 0), (1, 0, 0)), right=((0, 0, 1), (0, 1, 0), (-1, 0, 0)),
                front=((1, 0, 0), (0, 1, 0), (0, 0, 1)), back=((-1, 0, 0), (0, 1, 0), (0, 0, -1)))[plane]
    S, T, D = (np.array(a, float) for a in axes)
    region = ccw(region)
    r = np.asarray(region); s0, t0 = r.min(0); s1, t1 = r.max(0)
    rect = builder.atlas.put(pic)
    made = 0
    for k, (node, a, b, c, n, mat) in enumerate(skin.tris):
        if mat not in mats or np.dot(n, D) < min_dot: continue
        tri = [a, b, c]
        st = [(p @ S, p @ T) for p in tri]
        q = np.array(st);
        if q[:, 0].max() < s0 or q[:, 0].min() > s1 or q[:, 1].max() < t0 or q[:, 1].min() > t1: continue
        # (skip the skin's inner faces: something else of the skin just outside it)
        cen = (a + b + c) / 3
        if occlude and np.min(skin.hit(cen + n * 1e-3, D, k)) < occlude: continue
        poly = clip_poly(st, region)
        if len(poly) < 3: continue
        # back to 3D on the triangle's plane: p = a + solve for (s, t) along the plane's own axes
        M = np.array([[(b - a) @ S, (c - a) @ S], [(b - a) @ T, (c - a) @ T]])
        if abs(np.linalg.det(M)) < 1e-10: continue
        Mi = np.linalg.inv(M)
        pts = []
        for s, t in poly:
            l = Mi @ np.array([s - st[0][0], t - st[0][1]])
            pts.append(a + l[0] * (b - a) + l[1] * (c - a) + n * offset)
        uvs = [builder.atlas.uv(rect, (s - s0) / max(1e-9, s1 - s0), 1 - (t - t0) / max(1e-9, t1 - t0)) for s, t in poly]
        name = "livery_%s_%s" % (livery_id, builder.m.nodes[node].name)
        ln = builder.m.find(name)
        if ln < 0: ln = builder.m.add(name, node)
        builder.poly(ln, pts, uvs, "race_kit", n)
        made += 1
    return made


def stripe_pic(liv, length_m, width_m, pins=True):
    w = max(2, int(round(width_m / TEX))); h = max(2, int(round(length_m / TEX)))
    a = np.zeros((h, w, 3), np.uint8)
    a[:] = noise(w, h, liv["stripe"], 7, run=(1, 3)).transpose(0, 1, 2)
    if pins: a[:, 0] = liv["pin"]; a[:, -1] = liv["pin"]
    return a


def roundel_pic(liv, d_m):
    n = max(8, int(round(d_m / TEX)))
    a = np.zeros((n, n, 4), np.uint8)
    yy, xx = np.mgrid[0:n, 0:n]
    r = (n - 1) / 2
    disc = (xx - r) ** 2 + (yy - r) ** 2 <= (r + .35) ** 2
    a[disc, :3] = liv["roundel"]; a[disc, 3] = 255
    ring = disc & ((xx - r) ** 2 + (yy - r) ** 2 > (r - .8) ** 2)
    a[ring, :3] = liv["ink"]
    num = liv["number"]
    text(a, num, int(round(r - text_w(num) / 2 + .5)), int(round(r - 2)), liv["ink"])
    return a


def strip_pic(liv, w_m, h_m):
    w = max(8, int(round(w_m / TEX))); h = max(7, int(round(h_m / TEX)))
    a = np.zeros((h, w, 3), np.uint8); a[:] = liv["strip_bg"]
    s = liv["shop"]
    text(a, s, (w - text_w(s)) // 2, (h - 5) // 2, liv["strip_ink"])
    return a


def band_pic(colours, w_m, h_m, seed):
    w = max(2, int(round(w_m / TEX))); h = max(2, int(round(h_m / TEX)))
    return noise(w, h, colours, seed)


# ---------------------------------------------------------------- the race parts
def drop_components(model, node_name, mat, boxes):
    """Removes the connected pieces of a node's mesh that lie wholly inside any of [boxes] ((min xyz), (max xyz), car frame)."""
    top = model.find(node_name)
    for i in [top] + model.children(top):
      nd = model.nodes[i]; w = model.world(i)
      for p in nd.prims:
        if p.mat != mat: continue
        key = {}
        rep = np.array([key.setdefault(tuple(np.round(v, 4)), k) for k, v in enumerate(p.pos)])
        parent = list(range(len(p.pos)))
        def f(a):
            while parent[a] != a: parent[a] = parent[parent[a]]; a = parent[a]
            return a
        for t in p.idx:
            a, b, c = (f(rep[k]) for k in t); parent[b] = a; parent[f(c)] = f(a)
        comp = {}
        for ti, t in enumerate(p.idx): comp.setdefault(f(rep[t[0]]), []).append(ti)
        drop = set()
        for ts in comp.values():
            v = p.pos[p.idx[ts].ravel()] + w
            lo, hi = v.min(0), v.max(0)
            if any(np.all(lo >= np.array(b0) - 1e-3) and np.all(hi <= np.array(b1) + 1e-3) for b0, b1 in boxes): drop.update(ts)
        p.idx = p.idx[[k for k in range(len(p.idx)) if k not in drop]]
    return model


def lower(model):
    """The body down by DROP: every node under the root but the wheels' and the exits (on the ground)."""
    r = model.root
    for k in model.children(r):
        nm = model.nodes[k].name
        if nm.startswith(("steer_", "wheel_", "exit_")): continue
        model.nodes[k].t = model.nodes[k].t - (0, DROP, 0)


def set_hubs(model):
    for side, sx in (("l", 1), ("r", -1)):
        model.nodes[model.find("steer_f" + side)].t = np.array((sx * FRONT["x"], FRONT["r"], FRONT["z"]))
        model.nodes[model.find("wheel_r" + side)].t = np.array((sx * REAR["x"], REAR["r"], REAR["z"]))
        for k, ax in (("wheel_f" + side, FRONT), ("wheel_r" + side, REAR)):
            e = model.nodes[model.find(k)].extras
            e["tyre_width_m"] = ax["w"]; e["tyre_radius_m"] = ax["r"]


def wheel(builder, node, ax, side, detail):
    """A race wheel in its node's frame (axis x): a low tyre, a deep dish, ten thin spokes, a centre nut (near); a
    simpler one for the mid model (tyre, the face as one painted disc)."""
    for k in [node] + builder.m.children(node):
        if k == node or "__" in builder.m.nodes[k].name: builder.m.nodes[k].prims = []
    w0 = builder.m.world(node)
    r, w = ax["r"], ax["w"]
    o = 1 if side == "l" else -1          # the outboard side's x sign
    rr = r * .66                           # the rim (16 in on a 0.30 m tyre)
    N = 12 if detail == "near" else 8
    def ring(rad, x, a): return w0 + np.array((x, rad * math.cos(a), rad * math.sin(a)))
    ang = [2 * math.pi * k / N + math.pi / N for k in range(N)]
    # the tread
    tread = builder.atlas.put(st_tread(max(2, int(round(w / TEX))), max(4, int(round(2 * math.pi * r / TEX))), 3))
    for k in range(N):
        a0, a1 = ang[k], ang[(k + 1) % N]
        pts = [ring(r, -w / 2, a0), ring(r, w / 2, a0), ring(r, w / 2, a1), ring(r, -w / 2, a1)]
        v0, v1 = k / N, (k + 1) / N
        uv = [builder.atlas.uv(tread, 0, v0), builder.atlas.uv(tread, 1, v0), builder.atlas.uv(tread, 1, v1), builder.atlas.uv(tread, 0, v1)]
        mid = (a0 + a1) / 2
        builder.poly(node, pts, uv, "race_kit", np.array((0, math.cos(mid), math.sin(mid))))
    # the inboard side: a closed disc
    side_rect = builder.atlas.put(st_rubber(max(2, int(round(2 * r / TEX))), max(2, int(round(2 * r / TEX))), 5))
    pts = [ring(r, -o * w / 2, a) for a in ang]
    builder.poly(node, pts, [builder.atlas.uv(side_rect, .5 + .5 * math.cos(a), .5 + .5 * math.sin(a)) for a in ang], "race_kit", np.array((-o, 0, 0)))
    xo = o * w / 2
    if detail != "near":
        # (the mid model: the sidewall and the face as one painted disc)
        n = max(6, int(round(2 * r / TEX)))
        pic = np.zeros((n, n, 3), np.uint8); pic[:] = P["rubber"][0]
        yy, xx = np.mgrid[0:n, 0:n]; c = (n - 1) / 2; d = np.hypot(xx - c, yy - c)
        pic[d <= rr / r * c + .3] = P["alu"][0]
        pic[(d <= rr / r * c - .7) & (d > 1.6) & (((np.degrees(np.arctan2(yy - c, xx - c)) + 360) % 36) > 24)] = P["mesh_dark"]
        pic[d <= 1.0] = P["alu_hi"]
        face = builder.atlas.put(pic)
        builder.poly(node, [ring(r, xo, a) for a in ang], [builder.atlas.uv(face, .5 + .5 * math.cos(a), .5 + .5 * math.sin(a)) for a in ang], "race_kit", np.array((o, 0, 0)))
        return
    # the outboard sidewall (a ring) and the rim's lip
    sw = builder.atlas.put(st_rubber(max(2, int(round(2 * math.pi * r / TEX))), max(1, int(round((r - rr) / TEX))), 9))
    lip = builder.atlas.put(np.array([[P["alu_hi"]] * 30], np.uint8))
    barrel = builder.atlas.put(noise(30, 2, P["alu"], 11))
    dish = builder.atlas.put(noise(12, 12, [P["mesh_dark"], (30, 36, 38), (26, 31, 33)], 13))
    rl = rr - .022
    for k in range(N):
        a0, a1 = ang[k], ang[(k + 1) % N]
        v0, v1 = k / N, (k + 1) / N
        builder.poly(node, [ring(rr, xo, a0), ring(r, xo, a0), ring(r, xo, a1), ring(rr, xo, a1)],
                     [builder.atlas.uv(sw, v0, 1), builder.atlas.uv(sw, v0, 0), builder.atlas.uv(sw, v1, 0), builder.atlas.uv(sw, v1, 1)], "race_kit", np.array((o, 0, 0)))
        builder.poly(node, [ring(rl, xo, a0), ring(rr, xo, a0), ring(rr, xo, a1), ring(rl, xo, a1)],
                     [builder.atlas.uv(lip, v0, 0), builder.atlas.uv(lip, v0, 1), builder.atlas.uv(lip, v1, 1), builder.atlas.uv(lip, v1, 0)], "race_kit", np.array((o, 0, 0)))
        # the barrel: the dish's inner wall, deep
        mid = (a0 + a1) / 2
        builder.poly(node, [ring(rl, xo, a0), ring(rl, xo - o * .07, a0), ring(rl, xo - o * .07, a1), ring(rl, xo, a1)],
                     [builder.atlas.uv(barrel, v0, 0), builder.atlas.uv(barrel, v0, 1), builder.atlas.uv(barrel, v1, 1), builder.atlas.uv(barrel, v1, 0)], "race_kit",
                     np.array((0, -math.cos(mid), -math.sin(mid))))
    # the dish's floor, dark
    xd = xo - o * .07
    builder.poly(node, [ring(rl, xd, a) for a in ang], [builder.atlas.uv(dish, .5 + .5 * math.cos(a), .5 + .5 * math.sin(a)) for a in ang], "race_kit", np.array((o, 0, 0)))
    # ten spokes, light alloy, from the hub out to the lip (thin flat bars slanting out of the dish)
    spoke = builder.atlas.put(noise(1, 5, P["alu"], 17))
    for k in range(10):
        a = 2 * math.pi * k / 10
        cs, sn = math.cos(a), math.sin(a)
        tang = np.array((0, -sn, cs)) * .017
        p_in = w0 + np.array((xo - o * .03, .045 * cs, .045 * sn)); p_out = w0 + np.array((xo - o * .006, (rl - .004) * cs, (rl - .004) * sn))
        builder.poly(node, [p_in - tang, p_out - tang * .8, p_out + tang * .8, p_in + tang],
                     [builder.atlas.uv(spoke, 0, 1), builder.atlas.uv(spoke, 0, 0), builder.atlas.uv(spoke, 1, 0), builder.atlas.uv(spoke, 1, 1)], "race_kit", np.array((o, 0, 0)))
    # the centre-lock nut, a hexagon proud of the spokes
    hexr = .042
    ha = [2 * math.pi * k / 6 for k in range(6)]
    nut = builder.atlas.put(np.array([[P["red"][0], P["alu_hi"], P["red"][0]]] * 3, np.uint8))
    builder.poly(node, [w0 + np.array((xo - o * .02, hexr * math.cos(a), hexr * math.sin(a))) for a in ha],
                 [builder.atlas.uv(nut, .5 + .5 * math.cos(a), .5 + .5 * math.sin(a)) for a in ha], "race_kit", np.array((o, 0, 0)))


def arch(builder, node, ax, side, segs, x_in, x_out, detail="near"):
    """A riveted bolt-on arch over a wheel: an arc from just above the hub at the front, over the top, to the back."""
    o = 1 if side == "l" else -1
    hub = np.array((0, ax["r"], ax["z"]))
    ri = ax["r"] + .05; ro = ri + .115
    th = np.radians(np.linspace(6, 174, segs + 1))
    def p(rad, x, t): return np.array((o * x, hub[1] + rad * math.sin(t), hub[2] + rad * math.cos(t)))
    rivet = builder.atlas.put(np.array([[P["alu_hi"], P["alu_lo"]]], np.uint8))
    for k in range(segs):
        t0, t1 = th[k], th[k + 1]
        mid = (t0 + t1) / 2
        out = np.array((0, math.sin(mid), math.cos(mid)))
        # the outer face (the flank, slightly raked in at the top), body colour like the panels it bolts to
        builder.face(node, [p(ri, x_out, t0), p(ro, x_out - .015, t0), p(ro, x_out - .015, t1), p(ri, x_out, t1)], "paint_primary", normal=np.array((o, 0, 0)), udir=(0, 0, 1))
        # its top, back into the body side
        builder.face(node, [p(ro, x_out - .015, t0), p(ro, x_in, t0), p(ro, x_in, t1), p(ro, x_out - .015, t1)], "paint_primary", normal=out, udir=(1, 0, 0))
        if detail == "near":
            # the lip under it, over the tyre (raw fibreglass, dark)
            builder.face(node, [p(ri, x_out, t0), p(ri, x_in, t0), p(ri, x_in, t1), p(ri, x_out, t1)], "race_kit", st_frp, normal=-out, udir=(1, 0, 0))
            # the rivets: small alloy heads along the flank, one a segment and one at each joint
            for t in ((t0 + t1) / 2,) + ((t0,) if k == 0 else ()) + (t1,):
                c = p((ri + ro) / 2 + .01, x_out + .004, t)
                dz, dy = .011, .011
                builder.poly(node, [c + (0, -dy, -dz), c + (0, -dy, dz), c + (0, dy, dz), c + (0, dy, -dz)],
                             [builder.atlas.uv(rivet, 0, 0), builder.atlas.uv(rivet, 0, 1), builder.atlas.uv(rivet, 1, 1), builder.atlas.uv(rivet, 1, 0)], "race_kit", np.array((o, 0, 0)))
    if detail == "near":
        for t in (th[0], th[-1]):
            cap = [p(ri, x_out, t), p(ro, x_out - .015, t), p(ro, x_in, t), p(ri, x_in, t)]
            builder.face(node, cap, "paint_primary")


def surface_y(skin, x, z, nodes=None):
    """The skin's top at (x, z) (the highest hit of a ray down), and its node."""
    hits = skin.hit(np.array((x, 3., z)), np.array((0, -1., 0)), [])
    k = int(np.argmin(hits))
    if not np.isfinite(hits[k]): return None, None
    return 3. - hits[k], skin.tris[k][0]


def race_parts(model, b, detail):
    """Everything bolted on, for the near ('near') or mid ('mid') model."""
    near = detail == "near"
    root = model.root
    # ---- the wheels: race wheels, the arches over them
    for side in "lr":
        for ax, nm in ((FRONT, "wheel_f" + side), (REAR, "wheel_r" + side)):
            wheel(b, model.find(nm), ax, side, detail)
    for side in "lr":
        for ax, key, xi, xo in ((FRONT, "f", .835, .985), (REAR, "r", .875, 1.035)):
            n = model.add("rs_arch_%s%s" % (key, side))
            arch(b, n, ax, side, 7 if near else 4, xi, xo, detail)
    # ---- the rear wing: two elements between endplates, on tall uprights from the deck
    wing = model.add("rs_wing")
    span = .86
    zc, yc = -1.80, 1.17        # the main plane's middle (over the tail, above the roof line: the roof is at 1.107)
    aoa = math.radians(9)       # the trailing edge up (an upside-down wing: it presses down)
    chord, thick = .34, .042
    def foil(c, th, a, z0, y0):
        # a flat-bottomed foil, nose forward (+z), turned trailing edge up
        pts = [(c / 2, 0), (c / 2 - .03, th * .7), (-c / 2 + .05, th), (-c / 2, th * .35), (-c / 2, 0), (c / 2 - .06, -th * .25)]
        ca, sa = math.cos(a), math.sin(a)
        return [(z0 + z * ca + y * sa, y0 + y * ca - z * sa) for z, y in pts]
    main = foil(chord, thick, aoa, zc, yc)
    paint = ("paint_primary", None); kit = ("race_kit", st_frp)
    if near:
        b.prism(wing, main, -span + .02, span - .02, [paint, paint, paint, kit, kit, kit], caps=False)
        flap = foil(.15, .026, math.radians(28), zc - .19, yc + .085)
        b.prism(wing, flap, -span + .02, span - .02, [paint, paint, paint, kit, kit, kit], caps=False)
        # the endplates (black, a rivet line) and the uprights (black, swept back), with their feet on the deck
        for s in (1, -1):
            plate = [(zc + .22, yc - .12), (zc + .22, yc + .10), (zc - .30, yc + .22), (zc - .30, yc - .09)]
            b.prism(wing, plate, s * span - .012, s * span + .012, [("race_kit", st_frp_rivets)] * 4, caps=True, cap_mat="race_kit", cap_style=st_frp_rivets)
            deck_y, _ = surface_y(SKIN_NOW[0], s * .42, -1.86)
            deck_y = deck_y if deck_y is not None else .67
            foot = np.array((s * .42, deck_y - .01, -1.83)); head = np.array((s * .42, yc - .02, -1.76))
            b.tube(wing, foot, head, .03, "race_kit", st_frp)
            b.box(wing, (s * .42, head[1] + .005, head[2]), (.035, .02, .16), "race_kit", st_alu)
            b.box(wing, (s * .42, deck_y + .005, -1.86), (.07, .02, .12), "race_kit", st_alu)
    else:
        b.prism(wing, main, -span, span, [paint, paint, paint, kit, kit, kit], caps=False)
        for s in (1, -1):
            plate = [(zc + .22, yc - .12), (zc + .22, yc + .10), (zc - .30, yc + .22), (zc - .30, yc - .09)]
            b.face(wing, [(s * span, y, z) for z, y in plate], "race_kit", st_frp, normal=np.array((s, 0, 0)), udir=(0, 0, 1))
            b.box(wing, (s * .42, (yc + .66) / 2, -1.80), (.03, yc - .68, .09), "race_kit", st_frp, styles={"+y": False, "-y": False})
    # ---- the front: a deeper air dam (body colour, three big intakes), a splitter, canards
    dam = model.add("rs_front")
    y0, y1 = .115, .265
    plan = [(.70, 2.075), (.88, 1.93), (.89, 1.70), (-.89, 1.70), (-.88, 1.93), (-.70, 2.075)]
    # (a prism in plan view: faces round it, top and bottom)
    for i in range(len(plan)):
        (x0, z0), (x1, z1) = plan[i], plan[(i + 1) % len(plan)]
        if i == 2: continue   # (the back, under the car: unseen)
        out = np.array((((x0 + x1) / 2), 0, (z0 + z1) / 2 - 1.95))
        b.face(dam, [(x0, y0, z0), (x1, y0, z1), (x1, y1, z1), (x0, y1, z0)], "paint_primary", normal=out)
    b.face(dam, [(x, y1, z) for x, z in plan], "paint_primary", normal=np.array((0, 1, 0)), udir=(1, 0, 0))
    b.face(dam, [(x, y0, z) for x, z in plan], "race_kit", st_frp, normal=np.array((0, -1, 0)), udir=(1, 0, 0))
    # the intakes: dark mesh panels just proud of the dam's face
    for xa, xb in ((-.36, .36), (.46, .66), (-.66, -.46)):
        zf = 2.075 + .004
        if abs(xa) > .4:  # (the side ones on the chamfer's start: still on the front face)
            zf = 2.075 + .004
        b.face(dam, [(xa, y0 + .03, zf), (xb, y0 + .03, zf), (xb, y1 - .025, zf), (xa, y1 - .025, zf)], "race_kit", st_mesh, normal=np.array((0, 0, 1)), udir=(-1, 0, 0))
    # the splitter: a black blade, proud of the dam all round the front
    sp = [(.78, 2.16), (.95, 1.98), (.95, 1.70), (-.95, 1.70), (-.95, 1.98), (-.78, 2.16)]
    if near:
        for i in range(len(sp)):
            (x0, z0), (x1, z1) = sp[i], sp[(i + 1) % len(sp)]
            if i == 2: continue
            b.face(dam, [(x0, .09, z0), (x1, .09, z1), (x1, .112, z1), (x0, .112, z0)], "race_kit", st_frp, normal=np.array(((x0 + x1) / 2, 0, (z0 + z1) / 2 - 1.95)))
    b.face(dam, [(x, .112, z) for x, z in sp], "race_kit", st_frp, normal=np.array((0, 1, 0)), udir=(1, 0, 0))
    b.face(dam, [(x, .09, z) for x, z in sp], "race_kit", st_frp, normal=np.array((0, -1, 0)), udir=(1, 0, 0))
    if near:
        # canards: two small fins a corner, raked up towards the back
        for s in (1, -1):
            for k, (yy, zz) in enumerate(((.30, 1.99), (.39, 1.97))):
                xa = s * .835; xb = s * .955
                axz = np.array((0, math.sin(math.radians(14)), -math.cos(math.radians(14))))
                axx = np.array((1., 0, 0)); axy = np.cross(axz, axx)
                b.box(dam, (s * .9, yy, zz - .04), (.13, .014, .11), "race_kit", st_frp, axes=(axx, axy, axz))
    # ---- small race details on the bonnet, the tail and underneath (near model only)
    if near:
        hood = model.find("hood")
        for s in (1, -1):
            y, _ = surface_y(SKIN_NOW[0], s * .46, 1.86)
            if y is None: continue
            b.box(hood, (s * .46, y + .008, 1.86), (.045, .02, .045), "race_kit", st_alu)
            b.box(hood, (s * .46 + s * .035, y + .004, 1.86), (.03, .008, .012), "race_kit", st_red)
        # the fuel filler: an alloy cap on the bonnet, left of centre (the tank is in the nose of a mid-engined car)
        y, _ = surface_y(SKIN_NOW[0], .28, 1.20)
        if y is not None:
            ha = [2 * math.pi * k / 8 for k in range(8)]
            cap = [(.28 + .06 * math.cos(a), y + .022, 1.20 + .06 * math.sin(a)) for a in ha]
            b.face(hood, cap, "race_kit", st_alu, normal=np.array((0, 1, 0)), udir=(1, 0, 0))
            for k in range(8):
                a0, a1 = ha[k], ha[(k + 1) % 8]
                b.face(hood, [(.28 + .06 * math.cos(a0), y - .01, 1.20 + .06 * math.sin(a0)), (.28 + .06 * math.cos(a1), y - .01, 1.20 + .06 * math.sin(a1)),
                              (.28 + .06 * math.cos(a1), y + .022, 1.20 + .06 * math.sin(a1)), (.28 + .06 * math.cos(a0), y + .022, 1.20 + .06 * math.sin(a0))],
                       "race_kit", st_alu, normal=np.array((math.cos((a0 + a1) / 2), 0, math.sin((a0 + a1) / 2))))
        tail = model.add("rs_tail")
        # tow hooks: red straps, front right and rear left
        b.box(dam, (-.40, .205, 2.10), (.05, .06, .07), "race_kit", st_red)
        b.box(tail, (.42, .30, -2.135), (.05, .07, .07), "race_kit", st_red)
        # the centre exhaust: two big pipes out of the rear valance (the road car's corner pipes are gone)
        for xx in (.085, -.085):
            k8 = [2 * math.pi * k / 8 for k in range(8)]
            rad = .045
            for k in range(8):
                a0, a1 = k8[k], k8[(k + 1) % 8]
                b.face(tail, [(xx + rad * math.cos(a0), .235 + rad * math.sin(a0), -1.95), (xx + rad * math.cos(a1), .235 + rad * math.sin(a1), -1.95),
                              (xx + rad * math.cos(a1), .235 + rad * math.sin(a1), -2.17), (xx + rad * math.cos(a0), .235 + rad * math.sin(a0), -2.17)],
                       "race_kit", st_alu, normal=np.array((math.cos((a0 + a1) / 2), math.sin((a0 + a1) / 2), 0)), udir=(0, 0, 1))
            b.face(tail, [(xx + (rad - .006) * math.cos(a), .235 + (rad - .006) * math.sin(a), -2.16) for a in k8], "race_kit", solid(P["mesh_dark"]), normal=np.array((0, 0, -1)))
        # a rear diffuser's fins under the tail
        for xx in (-.5, -.2, .2, .5):
            b.box(tail, (xx, .17, -1.98), (.02, .09, .26), "race_kit", st_frp)
        # ---- the cabin: a bucket seat with a harness, a roll cage, a fire extinguisher
        cab = model.add("rs_cabin")
        sx, sz = .335, -.235          # the driver's seat anchor (its x, z), the floor ~ .30 - DROP
        fy = .30 - DROP
        bk = np.radians(14)           # the back raked
        bz = np.array((0, math.sin(bk), -math.cos(bk))) * -1   # (up the back: y up, z back)
        up = np.array((0, math.cos(bk), -math.sin(bk))); fwd = np.array((0, math.sin(bk), math.cos(bk)))
        shell = ("race_kit", st_frp)
        b.box(cab, (sx, fy + .09, sz + .02), (.40, .10, .48), "race_kit", st_fabric)                # the cushion
        b.box(cab, (sx, fy + .05, sz + .02), (.42, .06, .50), "race_kit", st_frp)                     # its shell
        back_c = np.array((sx, fy + .43, sz - .30))
        b.box(cab, back_c, (.42, .66, .07), "race_kit", st_frp, axes=((1, 0, 0), up, fwd))            # the back shell
        b.box(cab, back_c + fwd * .045, (.30, .56, .03), "race_kit", st_fabric, axes=((1, 0, 0), up, fwd))
        for s in (1, -1):
            # the side bolsters and the head wings
            b.box(cab, back_c + np.array((s * .19, 0, 0)) + fwd * .09 - up * .12, (.04, .34, .16), "race_kit", st_frp, axes=((1, 0, 0), up, fwd))
            b.box(cab, back_c + np.array((s * .17, 0, 0)) + fwd * .07 + up * .26, (.04, .12, .13), "race_kit", st_frp, axes=((1, 0, 0), up, fwd))
            b.box(cab, (sx + s * .19, fy + .15, sz + .06), (.04, .10, .40), "race_kit", st_frp)
        # the harness: two red shoulder straps, a lap belt, the buckle
        for s in (1, -1):
            top = back_c + up * .30 + fwd * .07 + np.array((s * .07, 0, 0))
            low = np.array((sx + s * .07, fy + .20, sz + .05))
            b.tube(cab, top, low, .045, "race_kit", st_red)
        b.box(cab, (sx, fy + .17, sz + .08), (.34, .035, .04), "race_kit", st_red)
        b.box(cab, (sx, fy + .18, sz + .10), (.07, .05, .02), "race_kit", st_alu)
        # the fire extinguisher where the passenger's seat was, and a bare floor pan
        b.tube(cab, (-.33, fy + .04, -.05), (-.33, fy + .04, -.45), .11, "race_kit", st_red)
        b.box(cab, (-.33, fy + .02, -.25), (.42, .015, .62), "race_kit", st_alu)
        # the cage: a main hoop behind the seats (with a diagonal and a harness bar), a front hoop up the screen
        # pillars, roof rails, a driver's door X
        T = .042
        cy0, cy1 = fy - .01, 1.035 - DROP
        mz, fz0, fz1 = -.74, .52, .17
        for s in (1, -1):
            b.tube(cab, (s * .50, cy0, mz), (s * .50, cy1 - .06, mz), T, "race_kit", st_cage)
            b.tube(cab, (s * .50, cy1 - .06, mz), (s * .44, cy1, mz), T, "race_kit", st_cage)
            b.tube(cab, (s * .50, cy0, fz0), (s * .47, cy1 - .02, fz1), T, "race_kit", st_cage)
            b.tube(cab, (s * .44, cy1, mz), (s * .45, cy1 - .01, fz1 + .02), T, "race_kit", st_cage)
        b.tube(cab, (.44, cy1, mz), (-.44, cy1, mz), T, "race_kit", st_cage)
        b.tube(cab, (.45, cy1 - .01, fz1 + .02), (-.45, cy1 - .01, fz1 + .02), T, "race_kit", st_cage)
        b.tube(cab, (.48, cy1 - .08, mz), (-.50, cy0 + .06, mz), T, "race_kit", st_cage)
        b.tube(cab, (.50, .70 - DROP, mz), (-.50, .70 - DROP, mz), T, "race_kit", st_cage)
        b.tube(cab, (.505, .48 - DROP, mz + .03), (.505, .62 - DROP, fz0 - .05), T, "race_kit", st_cage)
        b.tube(cab, (.505, .66 - DROP, mz + .03), (.505, .44 - DROP, fz0 - .05), T, "race_kit", st_cage)


def liveries(model, b, detail):
    """Both liveries as decals on the skin (the game shows the one the car's colour names)."""
    near = detail == "near"
    skin = Skin(model)
    # regions given in the car's frame: (x, z) on top, (z, y) on the sides, (x, y) in front, turned to each plane's (s, t)
    def top(r): return [(x, -z) for x, z in r]
    def side(r, plane): return [(-z if plane == "left" else z, y) for z, y in r]
    for liv in LIVERIES:
        lid = liv["id"]
        if lid == "a":
            # twin stripes over the bonnet, roof, deck and wing, a red pinstripe each side
            for s in (1, -1):
                x0, x1 = s * .05, s * .27
                reg = [(min(x0, x1), -2.3), (max(x0, x1), -2.3), (max(x0, x1), 2.3), (min(x0, x1), 2.3)]
                decal(b, skin, lid, top(reg), "top", stripe_pic(liv, 4.6, .22, pins=near), min_dot=.25)
        else:
            # the side flash: amber from the front arch's back to the rear arch, rising, an orange band along its top
            for plane in ("left", "right"):
                flash = side([(.70, .26), (.52, .52), (-.85, .62), (-.85, .26)], plane)
                band = side([(.52, .52), (.46, .58), (-.85, .68), (-.85, .62)], plane)
                decal(b, skin, lid, flash, plane, band_pic(liv["flash"], 1.6, .4, 41), min_dot=.45)
                decal(b, skin, lid, band, plane, band_pic(liv["band"], 1.4, .1, 43), min_dot=.45)
            # an orange nose: the bonnet's front third
            decal(b, skin, lid, top([(-.70, 1.45), (.70, 1.45), (.70, 2.3), (-.70, 2.3)]), "top", band_pic(liv["band"], 1.4, .9, 47), min_dot=.25)
        # the number roundels on the doors (and on the bonnet)
        if near or True:
            n = 16
            for plane in ("left", "right"):
                circ = side([(.04 + .22 * math.cos(2 * math.pi * k / n), .48 + .22 * math.sin(2 * math.pi * k / n)) for k in range(n)], plane)
                decal(b, skin, lid, circ, plane, roundel_pic(liv, .44), offset=.011, min_dot=.45)
            circ = top([(.17 * math.cos(2 * math.pi * k / n), 1.62 + .17 * math.sin(2 * math.pi * k / n)) for k in range(n)])
            if lid == "b":
                decal(b, skin, lid, circ, "top", roundel_pic(liv, .34), offset=.011, min_dot=.25)
        # the sun strip naming the shop, across the top of the windscreen
        if near:
            decal(b, skin, lid, [(-.62, .93), (.62, .93), (.62, 1.06), (-.62, 1.06)], "front", strip_pic(liv, 1.24, .27), offset=.006,
                  mats=("glass_vehicle",), occlude=0, min_dot=.2)


SKIN_NOW = [None]


def build(src_dir, out_dir):
    near = Model.read(os.path.join(src_dir, SRC + ".glb"))
    mid = Model.read(os.path.join(src_dir, SRC + "-mid.glb"))
    far = Model.read(os.path.join(src_dir, SRC + "-far.glb"))
    for m in (near, mid, far):
        r = m.root
        m.nodes[r].name = KEY
    # ---- out: the road wing, the road seats, the road car's corner exhaust pipes
    road_wing = [((-.80, .76, -1.95), (.80, 1.01, -1.69))]
    drop_components(near, "body", "paint_secondary", road_wing)
    drop_components(near, "body", "hardware_and_trim", [((-.52, .77, -1.87), (.52, .95, -1.73)),         # the wing's posts
                                                        ((-.58, .34, -.68), (.58, .89, .06)),            # both seats
                                                        ((-.66, .22, -2.12), (-.53, .34, -1.91)), ((.53, .22, -2.12), (.66, .34, -1.91))])
    drop_components(mid, "body", "paint_secondary", road_wing)
    drop_components(mid, "body", "hardware_and_trim", [((-.52, .77, -1.87), (.52, .95, -1.73)), ((-.58, .34, -.68), (.58, .89, .06)),
                                                       ((-.66, .22, -2.12), (-.53, .34, -1.91)), ((.53, .22, -2.12), (.66, .34, -1.91))])
    drop_components(far, "body", "paint_secondary", road_wing)
    out = {}
    for detail, m in (("near", near), ("mid", mid)):
        atlas = Atlas(); b = Builder(m, atlas)
        lower(m); set_hubs(m)
        SKIN_NOW[0] = Skin(m)
        race_parts(m, b, detail)
        liveries(m, b, detail)
        out[detail] = (m, atlas)
    # ---- the far model (under 150 triangles): its own body lowered, box wheels, the wing and the arches as a few quads
    atlas = Atlas(); b = Builder(far, atlas)
    lower(far); set_hubs(far)
    for side in "lr":
        for ax, nm in ((FRONT, "wheel_f" + side), (REAR, "wheel_r" + side)):
            node = far.find(nm)
            for k in [node] + far.children(node): far.nodes[k].prims = []
            w0 = far.world(node); r, w = ax["r"], ax["w"]
            ang = [2 * math.pi * k / 6 + math.pi / 6 for k in range(6)]
            ring = lambda x, a: w0 + np.array((x, r * math.cos(a), r * math.sin(a)))
            tr = atlas.put(st_rubber(4, 8, 1))
            for k in range(6):
                a0, a1 = ang[k], ang[(k + 1) % 6]
                b.poly(node, [ring(-w / 2, a0), ring(w / 2, a0), ring(w / 2, a1), ring(-w / 2, a1)], [atlas.uv(tr, 0, k / 6), atlas.uv(tr, 1, k / 6), atlas.uv(tr, 1, (k + 1) / 6), atlas.uv(tr, 0, (k + 1) / 6)],
                       "race_kit", np.array((0, math.cos((a0 + a1) / 2), math.sin((a0 + a1) / 2))))
            o = 1 if side == "l" else -1
            pic = np.zeros((8, 8, 3), np.uint8); pic[:] = P["rubber"][0]; pic[2:6, 2:6] = P["alu"][0]
            fr = atlas.put(pic)
            b.poly(node, [ring(o * w / 2, a) for a in ang], [atlas.uv(fr, .5 + .5 * math.cos(a), .5 + .5 * math.sin(a)) for a in ang], "race_kit", np.array((o, 0, 0)))
    wing = far.add("rs_wing")
    zc, yc = -1.80, 1.17
    b.face(wing, [(-.86, yc + .03, zc + .17), (.86, yc + .03, zc + .17), (.86, yc + .06, zc - .17), (-.86, yc + .06, zc - .17)], "paint_primary", normal=np.array((0, 1, 0)), udir=(1, 0, 0))
    b.face(wing, [(-.86, yc - .01, zc + .17), (.86, yc - .01, zc + .17), (.86, yc + .02, zc - .17), (-.86, yc + .02, zc - .17)], "race_kit", st_frp, normal=np.array((0, -1, 0)), udir=(1, 0, 0))
    for s in (1, -1):
        b.face(wing, [(s * .86, yc - .12, zc + .22), (s * .86, yc + .10, zc + .22), (s * .86, yc + .22, zc - .30), (s * .86, yc - .09, zc - .30)], "race_kit", st_frp, normal=np.array((s, 0, 0)), udir=(0, 0, 1))
    for side in "lr":
        for ax, key, xo in ((FRONT, "f", .985), (REAR, "r", 1.035)):
            n = far.add("rs_arch_%s%s" % (key, side)); o = 1 if side == "l" else -1
            hub = (ax["r"], ax["z"]); ri, ro = ax["r"] + .05, ax["r"] + .165
            pts = [(o * xo, hub[0] + .1, hub[1] + ri), (o * xo, hub[0] + ro * .7, hub[1] + ro * .7), (o * xo, hub[0] + ro, hub[1]),
                   (o * xo, hub[0] + ro * .7, hub[1] - ro * .7), (o * xo, hub[0] + .1, hub[1] - ri)]
            b.face(n, pts, "paint_primary", normal=np.array((o, 0, 0)), udir=(0, 0, 1))
    front = far.add("rs_front")
    b.face(front, [(-.95, .10, 1.70), (.95, .10, 1.70), (.95, .10, 2.10), (-.95, .10, 2.10)], "race_kit", st_frp, normal=np.array((0, 1, 0)), udir=(1, 0, 0))
    out["far"] = (far, atlas)
    return out


# ---------------------------------------------------------------- model-info.json
def info(src_dir, models):
    road = json.load(open(os.path.join(src_dir, SRC, "model-info.json")))
    v = copy.deepcopy(road["vehicle"])
    v["key"] = KEY
    v["name"] = "Rillhaven Torrel RS (Halvane race build)"
    v["based_on"] = SRC
    v["years"] = [1988, 1996]
    # the race car's numbers (estimates for a late-1980s club racer built from the road car, not measured on anything)
    mass = 968.0
    top = 278.0
    v.update({
        "estimated_mass_kg": mass,
        "estimated_top_speed_kmh": top,
        "wheelbase_m": 2.36,
        "track_m": round(2 * FRONT["x"], 3),
        "wheel_radius_m": FRONT["r"],
        "weight_distribution": {"front": .41, "rear": .59},
        "wheel_axles": {"front": {"track_m": round(2 * FRONT["x"], 3), "radius_m": FRONT["r"], "width_m": FRONT["w"]},
                        "rear": {"track_m": round(2 * REAR["x"], 3), "radius_m": REAR["r"], "width_m": REAR["w"]}},
        "estimated_0_100_kmh_s": 4.9,
        "suggested_power_kw": 196,
        "suggested_lod_m": {"mid": 38, "far": 95},
    })
    # the race setup the game's physics reads (VehicleSpec.Race): engine, gears, suspension, tyres, aero
    gears = [3.00, 2.10, 1.60, 1.30, 1.08, .92]
    max_rpm = 8200.0
    final = (max_rpm * 2 * math.pi / 60) / (gears[-1] * (top / 3.6 / FRONT["r"]))
    v["suggested_final_drive"] = round(final, 2)
    v["suggested_top_gear_ratio"] = gears[-1]
    v["race"] = {
        "note": "the race build's setup, read by the game's physics (VehicleSpec.Race); the road car's in brackets",
        "engine_torque_nm": 330, "engine_rpm": [900, max_rpm], "gears": gears, "reverse": -3.3,
        "final_drive": round(final, 2),
        "brake_torque_nm": 2400, "handbrake_torque_nm": 2400, "lock_deg": 30,
        "suspension_travel_m": [.035, .15], "spring_hz": 2.9, "damping": .62,
        "tyre_grip": 1.34, "drag": .016, "com_y_m": .36,
        "downforce_n_at_100_kmh": 650, "downforce_n_at_200_kmh": 2600, "downforce_front": .38,
        "ride_height_m": round(.154 - DROP, 3), "splitter_height_m": .09,
        "road_car": {"mass_kg": 1088, "top_speed_kmh": 224, "0_100_kmh_s": 6.4, "engine_torque_nm": 260, "engine_rpm": [800, 7000],
                     "gears": [3.3, 2.1, 1.5, 1.2, 1.0], "tyre_grip": 1.05, "spring_hz": 2.0, "damping": .52,
                     "suspension_travel_m": [.05, .22], "com_y_m": .42, "ride_height_m": .154, "downforce_n_at_200_kmh": 0},
    }
    # collision: the lowered body (wider: the arches), the cabin, the wing, the four arches
    v["collision_boxes"] = [
        {"center_gltf_m": [0, .44, 0], "size_gltf_m": [1.66, .6, 4.1]},
        {"center_gltf_m": [0, .902 - DROP, -.225], "size_gltf_m": [1.09, .453, 1.239]},
        {"center_gltf_m": [0, 1.17, -1.82], "size_gltf_m": [1.76, .14, .44]},
        {"center_gltf_m": [.91, .45, 1.18], "size_gltf_m": [.15, .36, .92]},
        {"center_gltf_m": [-.91, .45, 1.18], "size_gltf_m": [.15, .36, .92]},
        {"center_gltf_m": [.955, .46, -1.18], "size_gltf_m": [.16, .36, .96]},
        {"center_gltf_m": [-.955, .46, -1.18], "size_gltf_m": [.16, .36, .96]},
    ]
    v["roof_panel"]["mount_gltf_m"][1] = round(v["roof_panel"]["mount_gltf_m"][1] - DROP, 3)
    paint = v["paint"]
    plain = ["#d5a843", "#b84b47", "#417458", "#ce5e31"]
    paint["preset_body_colours"] = [l["body"] for l in LIVERIES] + plain
    paint["named_presets"] = {"halvane_stripes": LIVERIES[0]["body"], "ostrand_flash": LIVERIES[1]["body"],
                              "yellow": "#d5a843", "red": "#b84b47", "racing_green": "#417458", "orange": "#ce5e31"}
    paint["liveries"] = [{"id": l["id"], "name": l["name"], "body": l["body"], "number": l["number"],
                          "nodes": "livery_%s_*" % l["id"]} for l in LIVERIES]
    paint["livery_note"] = ("Each livery is decals (race_kit) under nodes livery_<id>_<part>, on the part they move with; "
                            "the game draws a livery's nodes when the car's colour is that livery's body colour and none for the plain colours.")
    paint["masks"] = {"primary": "textures/%s-primary-mask.png" % KEY, "secondary": "textures/%s-secondary-mask.png" % KEY,
                      "race_kit": "textures/%s-race-kit.png" % KEY}
    lods = []
    files = {"near": KEY + ".glb", "mid": KEY + "-mid.glb", "far": KEY + "-far.glb"}
    road_lods = {l["lod"]: l for l in road["vehicle"]["lods"]}
    for detail in ("near", "mid", "far"):
        m, _ = models[detail]
        lo, hi = m.bounds()
        l = copy.deepcopy(road_lods[detail])
        l["file"] = files[detail]
        l["triangles"] = m.tris()
        l["triangles_drawn"] = m.tris(skip_livery="a")
        l["bounds_gltf_m"] = [[round(float(lo[k]), 4), round(float(hi[k]), 4)] for k in range(3)]
        mats = sorted({p.mat for nd in m.nodes for p in nd.prims if p.tris})
        l["materials"] = mats
        nodes = {}
        for name, nd in l["nodes"].items():
            i = m.find(name)
            if i < 0: continue
            c = m.world(i)
            nd = dict(nd); nd["parent"] = m.nodes[m.nodes[i].parent].name if m.nodes[i].parent >= 0 else None
            nd["center_gltf_m"] = [round(float(x), 4) for x in c]
            nodes[name] = nd
        l["nodes"] = nodes
        lods.append(l)
    v["lods"] = lods
    v["rs_parts"] = ["rs_wing", "rs_arch_fl", "rs_arch_fr", "rs_arch_rl", "rs_arch_rr", "rs_front", "rs_tail", "rs_cabin"]
    return {"task": "C046", "format": road["format"], "origin": road["origin"], "texel_m": TEX, "vehicle": v}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    vdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "app", "src", "main", "assets", "vehicles")
    src_dir = os.path.join(vdir, SRC); out_dir = os.path.join(vdir, KEY)
    os.makedirs(os.path.join(out_dir, "textures"), exist_ok=True)
    models = build(src_dir, out_dir)
    for detail, suffix in (("near", ""), ("mid", "-mid"), ("far", "-far")):
        m, atlas = models[detail]
        png = atlas.png()
        # (the atlas was laid out on its full height: its uv's v follows the picture cut to the rows used)
        for nd in m.nodes:
            for p in nd.prims:
                if p.mat == "race_kit": p.uv[:, 1] *= atlas.h / atlas.h_out
        m.write(os.path.join(out_dir, KEY + suffix + ".glb"), png)
        if detail == "near":
            Image.fromarray(png, "RGBA").save(os.path.join(out_dir, "textures", KEY + "-race-kit.png"), optimize=True)
        print("%-5s %5d triangles (%d drawn with one livery)" % (detail, m.tris(), m.tris(skip_livery="a")))
    for k in ("primary", "secondary"):
        Image.open(os.path.join(src_dir, "textures", "%s-%s-mask.png" % (SRC, k))).save(os.path.join(out_dir, "textures", "%s-%s-mask.png" % (KEY, k)))
    with open(os.path.join(out_dir, "model-info.json"), "w") as f:
        json.dump(info(vdir, models), f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
