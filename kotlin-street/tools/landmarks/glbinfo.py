"""Reads a .glb (JSON chunk only) and reports nodes, materials, and the bounds of the lot group and the building (metres, glTF)."""
import json, struct, sys
import numpy as np

def load(path):
    b = open(path, 'rb').read()
    n = struct.unpack('<I', b[12:16])[0]
    j = json.loads(b[20:20 + n])
    return j

def mat_of(node):
    t = np.eye(4)
    if 'matrix' in node:
        t = np.array(node['matrix']).reshape(4, 4).T
    else:
        s = node.get('scale', [1, 1, 1]); r = node.get('rotation', [0, 0, 0, 1]); tr = node.get('translation', [0, 0, 0])
        x, y, z, w = r
        R = np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
        t[:3, :3] = R * np.array(s)
        t[:3, 3] = tr
    return t

def walk(j):
    """Yield (name, path-of-names, world matrix, node) for every node."""
    nodes = j['nodes']
    out = []
    def rec(i, M, path):
        nd = nodes[i]
        W = M @ mat_of(nd)
        p = path + [nd.get('name', f'n{i}')]
        out.append((nd.get('name', f'n{i}'), p, W, nd))
        for c in nd.get('children', []): rec(c, W, p)
    scene = j['scenes'][j.get('scene', 0)]
    for r in scene['nodes']: rec(r, np.eye(4), [])
    return out

def mesh_bounds(j, nd, W):
    if 'mesh' not in nd: return None
    lo = np.full(3, 1e9); hi = np.full(3, -1e9)
    for p in j['meshes'][nd['mesh']]['primitives']:
        a = j['accessors'][p['attributes']['POSITION']]
        mn = np.array(a['min']); mx = np.array(a['max'])
        for cx in (mn[0], mx[0]):
            for cy in (mn[1], mx[1]):
                for cz in (mn[2], mx[2]):
                    q = (W @ np.array([cx, cy, cz, 1]))[:3]
                    lo = np.minimum(lo, q); hi = np.maximum(hi, q)
    return lo, hi

def tris(j, nd):
    n = 0
    if 'mesh' not in nd: return 0
    for p in j['meshes'][nd['mesh']]['primitives']:
        n += j['accessors'][p['indices']]['count'] // 3 if 'indices' in p else j['accessors'][p['attributes']['POSITION']]['count'] // 3
    return n

def report(path):
    j = load(path)
    ws = walk(j)
    lo = np.full(3, 1e9); hi = np.full(3, -1e9)
    llo = np.full(3, 1e9); lhi = np.full(3, -1e9)
    T = 0
    names = []
    for name, p, W, nd in ws:
        names.append(name)
        if 'collision' in p or nd.get('extras', {}).get('collision_only') or nd.get('extras', {}).get('render') is False: continue
        b = mesh_bounds(j, nd, W)
        if b is None: continue
        T += tris(j, nd)
        inlot = 'lot' in p
        if inlot:
            llo = np.minimum(llo, b[0]); lhi = np.maximum(lhi, b[1])
        lo = np.minimum(lo, b[0]); hi = np.maximum(hi, b[1])
    return dict(file=path.split('/')[-1], tris=T, bounds=[lo.round(2).tolist(), hi.round(2).tolist()], lot=[llo.round(2).tolist(), lhi.round(2).tolist()],
                mats=[m.get('name') for m in j.get('materials', [])], doors=[n for n in names if n.startswith('door')], top=[n for n in names][:0], nnodes=len(names))

if __name__ == '__main__':
    for f in sys.argv[1:]:
        r = report(f)
        print(json.dumps(r))
