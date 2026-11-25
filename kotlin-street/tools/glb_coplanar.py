"""Z-fights in a landmark's .glb: faces facing the same way in one plane, overlapping (docs/LANDMARKS_GLB.md, "Faces in one plane").

  python3 tools/glb_coplanar.py find MODEL.glb             the fighting parts (world space, the collision node left out)
  python3 tools/glb_coplanar.py fix IN.glb OUT.glb [step]  separates them: in each plane the faces are grouped into
      patches (one primitive's triangles joined by shared vertices) and layered: of two overlapping patches within a
      millimetre, the material LandmarkKit draws later lies on top (what the game already shows: the later one wins a
      tie), then the smaller over the bigger; further apart, the one in front stays in front. Each patch is moved out
      along its normal to at least [step] (default 3 mm) in front of what it covers. Only the vertex positions change;
      meshes drawn by more than one node stay put (what overlaps them steps behind).

ModelCoplanarTest.theSeparatedLandmarksHaveNoFacesFightingInOnePlane checks the separated models.
"""
import sys, json, struct
import numpy as np

def load(path):
    b = open(path, 'rb').read()
    assert b[:4] == b'glTF'
    off = 12; js = None; bin_ = None
    while off < len(b):
        ln, ty = struct.unpack_from('<II', b, off); off += 8
        if ty == 0x4E4F534A: js = json.loads(b[off:off + ln])
        elif ty == 0x004E4942: bin_ = b[off:off + ln]
        off += ln
    return js, bin_

def acc(js, bin_, i):
    a = js['accessors'][i]; bv = js['bufferViews'][a['bufferView']]
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]
    dt = {5126: np.float32, 5123: np.uint16, 5125: np.uint32, 5121: np.uint8}[a['componentType']]
    o = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    stride = bv.get('byteStride', 0)
    isz = np.dtype(dt).itemsize * n
    if stride and stride != isz:
        raw = np.frombuffer(bin_, dtype=np.uint8, count=stride * a['count'], offset=o).reshape(a['count'], stride)[:, :isz]
        return np.frombuffer(raw.tobytes(), dtype=dt).reshape(a['count'], n)
    return np.frombuffer(bin_, dtype=dt, count=a['count'] * n, offset=o).reshape(a['count'], n)

def qmat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])

def local(n):
    if 'matrix' in n: return np.array(n['matrix']).reshape(4, 4).T
    m = np.eye(4)
    s = np.array(n.get('scale', [1, 1, 1])); r = qmat(n.get('rotation', [0, 0, 0, 1])); t = n.get('translation', [0, 0, 0])
    m[:3, :3] = r * s; m[:3, 3] = t
    return m

def triangles(js, bin_):
    out = []  # (verts 3x3, node name, material)
    def walk(ni, parent, path):
        n = js['nodes'][ni]; m = parent @ local(n); name = n.get('name', str(ni))
        if 'mesh' in n:
            mesh = js['meshes'][n['mesh']]
            for pi, p in enumerate(mesh['primitives']):
                if p.get('mode', 4) != 4: continue
                pos = acc(js, bin_, p['attributes']['POSITION']).astype(np.float64)
                idx = acc(js, bin_, p['indices']).reshape(-1).astype(np.int64) if 'indices' in p else np.arange(len(pos))
                w = (np.c_[pos, np.ones(len(pos))] @ m.T)[:, :3]
                mat = js['materials'][p['material']].get('name', str(p['material'])) if 'material' in p else '-'
                out.append((w[idx].reshape(-1, 3, 3), name, mat))
        for c in n.get('children', []): walk(c, m, path + [name])
    sc = js['scenes'][js.get('scene', 0)]
    for r in sc['nodes']: walk(r, np.eye(4), [])
    return out

def clip_area(a2, b2):
    def ar(p): return 0.5 * sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p)))
    poly = list(a2); clip = list(b2)
    if ar(poly) < 0: poly = poly[::-1]
    if ar(clip) < 0: clip = clip[::-1]
    for i in range(len(clip)):
        s = clip[i]; e = clip[(i + 1) % len(clip)]
        def inside(q): return (e[0] - s[0]) * (q[1] - s[1]) - (e[1] - s[1]) * (q[0] - s[0]) >= -1e-12
        res = []
        for k in range(len(poly)):
            p = poly[k]; q = poly[(k + 1) % len(poly)]
            pi, qi = inside(p), inside(q)
            if pi: res.append(p)
            if pi != qi:
                dx, dy = q[0] - p[0], q[1] - p[1]
                den = (e[0] - s[0]) * dy - (e[1] - s[1]) * dx
                if abs(den) > 1e-15:
                    t = ((e[1] - s[1]) * (p[0] - s[0]) - (e[0] - s[0]) * (p[1] - s[1])) / den
                    res.append((p[0] + t * dx, p[1] + t * dy))
        poly = res
        if len(poly) < 3: return 0.0
    return abs(ar(poly))

def fights(path, tol=0.002, min_area=1e-4):
    js, bin_ = load(path)
    tris = []
    for (T, node, mat) in triangles(js, bin_):
        e1 = T[:, 1] - T[:, 0]; e2 = T[:, 2] - T[:, 0]
        c = np.cross(e1, e2); l = np.linalg.norm(c, axis=1)
        ok = l > 1e-9
        for t, cc, ll in zip(T[ok], c[ok], l[ok]):
            n = cc / ll
            tris.append((t, n, float(n @ t[0]), node, mat))
    # bucket by normal (1e-3) and plane offset (tol bins; neighbours checked)
    buckets = {}
    for i, (t, n, d, node, mat) in enumerate(tris):
        key = (round(n[0] * 500), round(n[1] * 500), round(n[2] * 500), int(np.floor(d / tol)))
        buckets.setdefault(key, []).append(i)
    found = {}
    for key, ids in buckets.items():
        cand = list(ids)
        nk = (key[0], key[1], key[2], key[3] + 1)
        other = buckets.get(nk, [])
        for ii, i in enumerate(cand):
            for j in cand[ii + 1:] + other:
                ti, ni, di, nodi, mi = tris[i]; tj, nj, dj, nodj, mj = tris[j]
                if abs(di - dj) > tol or ni @ nj < .9999: continue
                ax = int(np.argmax(np.abs(ni))); uv = [k for k in range(3) if k != ax]
                a = clip_area([tuple(p[uv]) for p in ti], [tuple(p[uv]) for p in tj]) / abs(ni[ax])
                if a > min_area:
                    k = (nodi, mi, nodj, mj, round(di, 3))
                    f = found.setdefault(k, [0.0, 0, ti.mean(0), ni, abs(di - dj)])
                    f[0] += a; f[1] += 1
    return found


PRIORITY = {}

def main(src, dst, step=.003, tol=.008, min_area=1e-4):
    raw = open(src, 'rb').read()
    js, bin_ = load(src)
    binarr = bytearray(bin_)
    # world matrices
    nodes = js['nodes']
    parent = {}
    for i, n in enumerate(nodes):
        for c in n.get('children', []): parent[c] = i
    world = {}
    def W(i):
        if i in world: return world[i]
        m = local(nodes[i])
        if i in parent: m = W(parent[i]) @ m
        world[i] = m; return m
    uses = {}
    for i, n in enumerate(nodes):
        if 'mesh' in n: uses.setdefault(n['mesh'], []).append(i)
    # every triangle: (prim key, tri index, world verts, normal, offset, vertex indices)
    prims = {}  # key -> dict(pos(local, float64), idx, node, world matrix, accessor)
    tris = []
    for i, n in enumerate(nodes):
        if 'mesh' not in n: continue
        ex = n.get('extras') or {}
        if n.get('name') == 'collision' or ex.get('collision_only') or ('render' in ex and not ex['render']): continue
        fixed = len(uses[n['mesh']]) > 1
        m = W(i)
        for pi, p in enumerate(js['meshes'][n['mesh']]['primitives']):
            if p.get('mode', 4) != 4: continue
            ai = p['attributes']['POSITION']
            pos = acc(js, bin_, ai).astype(np.float64).copy()
            idx = acc(js, bin_, p['indices']).reshape(-1, 3).astype(np.int64) if 'indices' in p else np.arange(len(pos)).reshape(-1, 3)
            key = (i, pi)
            prims[key] = dict(pos=pos, idx=idx, m=m, acc=ai, moved=np.zeros(len(pos), bool), fixed=fixed)
            wp = (np.c_[pos, np.ones(len(pos))] @ m.T)[:, :3]
            T = wp[idx]
            c = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]); l = np.linalg.norm(c, axis=1)
            for t in np.nonzero(l > 1e-9)[0]:
                nn = c[t] / l[t]
                tris.append((key, int(t), T[t], nn, float(nn @ T[t][0])))
    # plane clusters
    buckets = {}
    for k, (key, t, T, n, d) in enumerate(tris):
        buckets.setdefault((round(n[0] * 500), round(n[1] * 500), round(n[2] * 500), int(np.floor(d / tol))), []).append(k)
    pairs = []
    for bk, ids in buckets.items():
        other = buckets.get((bk[0], bk[1], bk[2], bk[3] + 1), [])
        for a_i, a in enumerate(ids):
            for b in ids[a_i + 1:] + other:
                A = tris[a]; B = tris[b]
                if abs(A[4] - B[4]) > tol or A[3] @ B[3] < .9999: continue
                ax = int(np.argmax(np.abs(A[3]))); uv = [q for q in range(3) if q != ax]
                ar = clip_area([tuple(p[uv]) for p in A[2]], [tuple(p[uv]) for p in B[2]]) / abs(A[3][ax])
                if ar > min_area: pairs.append((a, b))
    if not pairs:
        print('no fights'); open(dst, 'wb').write(raw); return 0
    # patches: triangles of one primitive in one plane joined by shared vertices
    involved = sorted(set([a for a, b in pairs] + [b for a, b in pairs]))
    def plane_key(k):
        n = tris[k][3]; return (round(n[0] * 500), round(n[1] * 500), round(n[2] * 500), round(tris[k][4] / .0005))
    patch_of = {}
    patches = []  # list of (prim key, set of tri indices, plane normal, area)
    by_prim_plane = {}
    for k, (key, t, T, n, d) in enumerate(tris):
        by_prim_plane.setdefault((key, plane_key(k)), []).append(k)
    for (key, pk), ks in by_prim_plane.items():
        if not any(k in set(involved) for k in ks): continue
        idx = prims[key]['idx']
        # union-find over shared vertices
        par = {k: k for k in ks}
        def f(x):
            while par[x] != x: par[x] = par[par[x]]; x = par[x]
            return x
        owner = {}
        for k in ks:
            for v in idx[tris[k][1]]:
                if v in owner: par[f(k)] = f(owner[v])
                else: owner[v] = k
        groups = {}
        for k in ks: groups.setdefault(f(k), []).append(k)
        for gk in groups.values():
            pid = len(patches)
            area = sum(.5 * np.linalg.norm(np.cross(tris[k][2][1] - tris[k][2][0], tris[k][2][2] - tris[k][2][0])) for k in gk)
            patches.append((key, gk, tris[gk[0]][3], area))
            for k in gk: patch_of[k] = pid
    over = {}
    for a, b in pairs:
        pa, pb = patch_of[a], patch_of[b]
        if pa == pb: continue
        over.setdefault(pa, set()).add(pb); over.setdefault(pb, set()).add(pa)
    # (a mesh drawn by several nodes cannot move: its patches stay put, and what overlaps them steps back behind)
    fixedp = {p for p in over if prims[patches[p][0]]['fixed']}
    # (which lies on top: the material's rank first (PRIORITY: the higher drawn over the lower, as the model's own renders
    # show it), then the smaller patch over the bigger (a trim or a cap on the wall it lies on))
    def matname(p):
        key = patches[p][0]
        return js['materials'][js['meshes'][nodes[key[0]]['mesh']]['primitives'][key[1]]['material']]['name']
    # (by default the order LandmarkKit draws the materials in: by first appearance over the nodes; a later one drawn at
    # the same depth wins, which is what every render of the model so far has shown)
    draw = []
    for nn in nodes:
        if "mesh" not in nn or nn.get("name") == "collision": continue
        for pp in js["meshes"][nn["mesh"]]["primitives"]:
            mn = js["materials"][pp["material"]]["name"] if "material" in pp else "-"
            if mn not in draw: draw.append(mn)
    def rank(p): return PRIORITY.get(matname(p), PRIORITY.get("*", draw.index(matname(p)) if matname(p) in draw else 0))
    # each patch's plane (its distance along its normal), and which of two overlapping ones lies above: within a
    # millimetre (coplanar: the fight), the material's rank, then the smaller over the bigger; further apart, the one
    # in front already. Then each is put at least a step in front of everything it must cover (its own plane if it
    # already is): the layers only ever in the order they are meant to be, nothing overtakes what was in front of it
    dist = {p: float(np.mean([tris[k][4] for k in patches[p][1]])) for p in over}
    edges = set()
    for p in over:
        for q in over[p]:
            if p >= q: continue
            gap = dist[q] - dist[p]
            if abs(gap) <= .001:
                kp = (rank(p), -patches[p][3], p); kq = (rank(q), -patches[q][3], q)
                edges.add((p, q) if kp < kq else (q, p))
            else:
                edges.add((p, q) if gap > 0 else (q, p))
    final = dict(dist)
    for _ in range(64):
        changed = False
        for lo, hi in edges:
            if hi in fixedp: continue
            want = final[lo] + step
            if final[hi] < want - 1e-7: final[hi] = want; changed = True
        if not changed: break
    for lo, hi in edges:
        if hi in fixedp and lo not in fixedp and final[lo] > final[hi] - step: final[lo] = final[hi] - step
    layer = {p: (final[p] - dist[p]) / step for p in over}
    moved = 0
    for p, L in layer.items():
        if abs(L) < 1e-6 or p in fixedp: continue
        key, ks, n, area = patches[p]
        pr = prims[key]
        lin = pr['m'][:3, :3]
        dl = np.linalg.solve(lin, n * step * L)   # the world offset in the node's own frame
        vs = set()
        for k in ks: vs.update(int(v) for v in pr['idx'][tris[k][1]])
        for v in vs:
            if pr['moved'][v]: continue
            pr['pos'][v] += dl; pr['moved'][v] = True
        moved += 1
    # write the positions back
    for key, pr in prims.items():
        if not pr['moved'].any(): continue
        a = js['accessors'][pr['acc']]; bv = js['bufferViews'][a['bufferView']]
        o = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        stride = bv.get('byteStride', 0) or 12
        arr = pr['pos'].astype(np.float32)
        for v in np.nonzero(pr['moved'])[0]:
            struct.pack_into('<3f', binarr, o + v * stride, *arr[v])
        a['min'] = [float(x) for x in arr.min(0)]; a['max'] = [float(x) for x in arr.max(0)]
    # the file: the JSON chunk (padded with spaces), the BIN chunk
    jb = json.dumps(js, separators=(',', ':')).encode()
    jb += b' ' * ((4 - len(jb) % 4) % 4)
    bb = bytes(binarr); bb += b'\0' * ((4 - len(bb) % 4) % 4)
    out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(jb) + 8 + len(bb)) + struct.pack('<II', len(jb), 0x4E4F534A) + jb + struct.pack('<II', len(bb), 0x004E4942) + bb
    open(dst, 'wb').write(out)
    print('%s: %d fighting triangle pairs; %d patches layered, %d moved (up to %.1f mm)' % (src.split('/')[-1], len(pairs), len(layer), moved, max(layer.values()) * step * 1000))
    return moved

if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'find':
        f = fights(sys.argv[2])
        f = {k: v for k, v in f.items() if k[0] != 'collision' and k[2] != 'collision'}
        for k, v in sorted(f.items(), key=lambda kv: -kv[1][0]):
            print('%s/%s - %s/%s plane %.3f: %.4f m2 in %d pairs at (%.2f, %.2f, %.2f)' % (k[0], k[1], k[2], k[3], k[4], v[0], v[1], *v[2]))
        print('fighting part pairs:', len(f))
    elif len(sys.argv) > 2 and sys.argv[1] == 'fix':
        if len(sys.argv) > 5: PRIORITY.update(json.loads(sys.argv[5]))
        main(sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else .003)
    else:
        print(__doc__)
