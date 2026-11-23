#!/usr/bin/env python3
"""World-generation sketch (kotlin-street/docs/WORLD_GEN.md, "The prototype").

A quick prototype of the design's pipeline on a coarse atlas grid, to judge the shapes and the scales, not the game's
generator (that is phases W1-W3, in TypeScript under src/world/gen). Pure in the seed.

  python3 kotlin-street/tools/world-sketch.py seed-a seed-b seed-c --out maps.jpg [--stats stats.json]

The planet is a cylinder (x wraps) 3,712 chunks round (1,995.6 km) and 1,856 chunks pole to pole (997.8 km); this sketch's
atlas cell is 4 chunks (2.15 km): 928 x 464 cells.
"""
import argparse, hashlib, heapq, json, math, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import dijkstra, minimum_spanning_tree
from scipy.spatial import Delaunay

W, H = 928, 464
KM = 2.1504  # km per cell (4 chunks of 537.6 m)
LAND = 0.30

# ---------------------------------------------------------------- keyed randomness and noise

def seed_int(*keys):
    return int.from_bytes(hashlib.sha256('/'.join(map(str, keys)).encode()).digest()[:8], 'little')

def rng(*keys):
    return np.random.default_rng(seed_int(*keys))

def vnoise(seed, key, nx, X, Y):
    """Value noise, periodic in x over the planet (nx lattice cells round it), isotropic."""
    ny = int(math.ceil(H / W * nx)) + 3
    lat = rng(seed, 'noise', key, nx).random((ny, nx)).astype(np.float32)
    u = X / W * nx; v = Y / W * nx + 1
    i0 = np.floor(u).astype(np.int64); j0 = np.clip(np.floor(v).astype(np.int64), 0, ny - 2)
    fu = u - np.floor(u); fv = np.clip(v - j0, 0, 1)
    fu = fu * fu * fu * (fu * (fu * 6 - 15) + 10); fv = fv * fv * fv * (fv * (fv * 6 - 15) + 10)
    i0 %= nx; i1 = (i0 + 1) % nx
    a, b = lat[j0, i0], lat[j0, i1]; c, d = lat[j0 + 1, i0], lat[j0 + 1, i1]
    return (a + (b - a) * fu) * (1 - fv) + (c + (d - c) * fu) * fv

def fbm(seed, key, nx, octaves, X, Y, gain=0.5):
    s = 0.0; amp = 1.0; tot = 0.0
    for o in range(octaves):
        s = s + amp * vnoise(seed, f'{key}{o}', nx * 2 ** o, X, Y); tot += amp; amp *= gain
    return s / tot

def wrapdx(dx):
    return (dx + W / 2) % W - W / 2

# ---------------------------------------------------------------- terrain, climate, biomes

def terrain(seed):
    Y, X = np.mgrid[0:H, 0:W].astype(np.float32) + 0.5
    lat = (Y / H) * 180 - 90
    r = rng(seed, 'continents')
    n = int(r.choice([3, 4, 4, 4, 5]))
    # continent cores: spread round the planet, in the warm and temperate bands
    x0 = r.random() * W; gaps = r.uniform(0.75, 1.25, n); gaps = gaps / gaps.sum() * W
    xs = (x0 + np.concatenate([[0], np.cumsum(gaps)[:-1]])) % W
    ys = H / 2 + r.uniform(-0.2, 0.2, n) * H
    share = np.concatenate([[1.0], r.uniform(0.45, 0.8, n - 1)]); share /= share.sum()
    target = LAND * W * H * 0.62  # islands and the ragged coast make up the rest
    # warped coordinates for ragged coasts
    wx = X + 38 * (fbm(seed, 'wx', 6, 4, X, Y) - 0.5); wy = Y + 38 * (fbm(seed, 'wy', 6, 4, X, Y) - 0.5)
    blob = np.zeros((H, W), np.float32); dists = []
    for c in range(n):
        area = target * share[c]; ab = r.uniform(1.3, 2.0); ang = r.uniform(0, math.pi)
        a = math.sqrt(area * ab / math.pi); b = a / ab
        g = np.zeros((H, W), np.float32)
        for k in range(r.integers(2, 5)):
            off = r.uniform(-0.55, 0.55); side = r.uniform(-0.3, 0.3)
            cx = xs[c] + math.cos(ang) * off * a - math.sin(ang) * side * b
            cy = ys[c] + math.sin(ang) * off * a + math.cos(ang) * side * b
            dx = wrapdx(wx - cx); dy = wy - cy
            u = (dx * math.cos(ang) + dy * math.sin(ang)) / (a * r.uniform(0.6, 0.9))
            v = (-dx * math.sin(ang) + dy * math.cos(ang)) / (b * r.uniform(0.7, 1.0))
            g = np.maximum(g, np.exp(-(u * u + v * v) ** 1.3))
        blob += g
        dists.append(np.hypot(wrapdx(wx - xs[c]), wy - ys[c]) / math.sqrt(area / math.pi))
    D = np.sort(np.stack(dists), axis=0)
    trench = np.exp(-((D[1] - D[0]) / 0.35) ** 2) if n > 1 else 0
    isl = 3.0 * np.clip(fbm(seed, 'isl', 22, 5, wx, wy) - 0.57, 0, None)
    mask = blob + 1.0 * (fbm(seed, 'coast', 12, 6, wx, wy) - 0.5) + isl - 0.55 * trench
    mask -= 0.6 * np.clip((np.abs(lat) - 58) / 12, 0, 1)  # little land towards the poles
    thr = np.quantile(mask, 1 - LAND)
    land = mask > thr
    # plates: nearest and second-nearest of a warped Voronoi
    K = 14
    px = r.random(K) * W; py = r.uniform(0.08, 0.92, K) * H
    vel = r.normal(0, 1, (K, 2)); maxh = r.uniform(1800, 3600, (K, K))
    pd = np.stack([np.hypot(wrapdx(wx - px[k]), wy - py[k]) for k in range(K)])
    order = np.argsort(pd, axis=0); p1, p2 = order[0], order[1]
    d1 = np.take_along_axis(pd, order[:1], 0)[0]; d2 = np.take_along_axis(pd, order[1:2], 0)[0]
    bd = (d2 - d1) / 2
    ddx = wrapdx(px[p2] - px[p1]); ddy = py[p2] - py[p1]; nn = np.hypot(ddx, ddy) + 1e-6
    conv = ((vel[p1, 0] - vel[p2, 0]) * ddx + (vel[p1, 1] - vel[p2, 1]) * ddy) / nn
    width = 5 + 8 * vnoise(seed, 'rw', 8, X, Y)
    ridged = 1 - np.abs(2 * fbm(seed, 'ridge', 16, 5, X, Y) - 1)
    mount = np.clip(conv, 0, 1.2) * np.exp(-(bd / width) ** 2) * maxh[p1, p2] * (0.35 + 0.9 * ridged ** 2)
    # elevation
    pad = lambda a: np.concatenate([a, a, a], axis=1)
    dl = ndimage.distance_transform_edt(pad(land))[:, W:2 * W]
    ds = ndimage.distance_transform_edt(pad(~land))[:, W:2 * W]
    hills = fbm(seed, 'hills', 12, 6, X, Y)
    elev = np.where(land, 20 + 380 * (1 - np.exp(-dl / 30)) * (0.4 + hills) + 260 * (hills - 0.4).clip(0) + mount,
                    -(80 + 4200 * (1 - np.exp(-ds / 14))) * (0.75 + 0.5 * hills))
    # climate
    T = 27 - 45 * (np.abs(lat) / 90) ** 1.6 - 0.0065 * np.maximum(elev, 0) + np.where(land, 0, 2)
    rain = moisture(land, elev, T, lat)
    return dict(n=n, X=X, Y=Y, lat=lat, mask=mask, thr=thr, land=land, elev=elev.astype(np.float32), T=T.astype(np.float32),
                rain=rain.astype(np.float32), dl=dl, ds=ds, cores=(xs, ys), plates=(p1, p2))

def moisture(land, elev, T, lat):
    """Moisture carried along the prevailing wind of each row; rain on rising ground: rain shadows behind the ranges."""
    row_lat = np.abs(lat[:, 0])
    dirn = np.where((row_lat > 30) & (row_lat < 60), 1, -1)  # westerlies (to the east) / trades and polar easterlies
    rows = np.arange(H); start = np.where(dirn > 0, 0, W - 1)
    el = np.where(land, elev, 0)
    m = np.zeros(H); rec = np.zeros((H, W))
    cap = np.clip((T + 8) / 36, 0.1, 1)
    for lap in range(2):
        for step in range(W):
            x = (start + dirn * step) % W; xp = (x - dirn) % W
            sea = ~land[rows, x]
            c = cap[rows, x]
            m = np.where(sea, m + (c - m) * 0.12, m)
            up = np.clip((el[rows, x] - el[rows, xp]) / 220, 0, 1)
            rn = np.where(sea, 0, m * (0.007 + 0.35 * up))
            m = m - rn
            if lap: rec[rows, x] = rn
    rec = ndimage.gaussian_filter(rec, (5, 2.5), mode=('nearest', 'wrap'))
    a = np.abs(lat)
    band = 0.55 + 0.9 * np.exp(-(lat / 11) ** 2) - 0.35 * np.exp(-((a - 27) / 8) ** 2) + 0.2 * np.exp(-((a - 50) / 10) ** 2)
    r = rec * band
    return np.clip(800 * r / np.median(r[land]), 0, 4500)

BIOMES = ['ocean', 'ice', 'tundra', 'taiga', 'temperate forest', 'temperate rainforest', 'prairie', 'steppe', 'desert',
          'savanna', 'tropical forest', 'rainforest', 'alpine', 'marsh', 'lake']
BCOL = {'ice': (236, 242, 248), 'tundra': (150, 158, 130), 'taiga': (58, 92, 70), 'temperate forest': (66, 120, 62),
        'temperate rainforest': (40, 98, 70), 'prairie': (150, 172, 88), 'steppe': (176, 170, 110), 'desert': (214, 190, 132),
        'savanna': (178, 168, 84), 'tropical forest': (70, 140, 60), 'rainforest': (30, 110, 52), 'alpine': (140, 132, 122),
        'marsh': (92, 124, 92), 'lake': (70, 120, 170)}

def biomes(land, elev, T, rain, lat, slope):
    b = np.full(land.shape, 0, np.int8)
    def put(cond, name): b[land & cond & (b == 0)] = BIOMES.index(name)
    put((T < -9) | (np.abs(lat) > 72), 'ice')
    put((elev > 1700) & (T < 6), 'alpine')
    put(T < -2, 'tundra')
    put((T < 4) & (rain < 300), 'tundra')
    put(T < 4, 'taiga')
    put((elev < 80) & (slope < 4) & (rain > 900), 'marsh')
    put((T < 17) & (rain < 250), 'desert')
    put((T < 17) & (rain < 450), 'steppe')
    put((T < 17) & (rain < 760), 'prairie')
    put((T < 17) & (rain < 1700), 'temperate forest')
    put(T < 17, 'temperate rainforest')
    put(rain < 330, 'desert')
    put(rain < 850, 'savanna')
    put(rain < 1700, 'tropical forest')
    put(land, 'rainforest')
    return b

# ---------------------------------------------------------------- hydrology

N8 = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]

def hydrology(land, elev, rain):
    """Priority-flood from the sea (x wraps): every land cell drains to the sea; receivers from the flood; accumulation by rain."""
    lf = land.ravel(); ef = elev.ravel().astype(np.float64)
    filled = ef.copy(); recv = np.full(W * H, -1, np.int64); seen = ~lf.copy()
    heap = []
    sea = ~land
    near_sea = land & ndimage.binary_dilation(np.concatenate([sea, sea, sea], 1), np.ones((3, 3)))[:, W:2 * W]
    for i in np.flatnonzero(near_sea.ravel()):
        y, x = divmod(int(i), W)
        best, bj = 1e9, -1
        for dy, dx in N8:
            yy = y + dy
            if 0 <= yy < H:
                j = yy * W + (x + dx) % W
                if not lf[j] and ef[j] < best: best, bj = ef[j], j
        recv[i] = bj; seen[i] = True; heapq.heappush(heap, (ef[i], int(i)))
    order = []
    sl = seen.tolist(); el = ef.tolist(); fl = filled.tolist(); rl = recv.tolist()
    while heap:
        e, i = heapq.heappop(heap); order.append(i)
        y, x = divmod(i, W)
        for dy, dx in N8:
            yy = y + dy
            if 0 <= yy < H:
                j = yy * W + (x + dx) % W
                if not sl[j]:
                    sl[j] = True; fe = max(el[j], e + 0.05); fl[j] = fe; rl[j] = i
                    heapq.heappush(heap, (fe, j))
    filled = np.array(fl); recv = np.array(rl)
    acc = (np.clip(rain.ravel(), 0, None) / 1000.0 * lf).tolist()
    for i in reversed(order):
        rcv = rl[i]
        if rcv >= 0 and lf[rcv]: acc[rcv] += acc[i]
    acc = np.array(acc).reshape(H, W)
    depth = (filled - ef).reshape(H, W)
    lake = land & (depth > 25)
    lab, nl = ndimage.label(lake)
    sizes = ndimage.sum(lake, lab, range(1, nl + 1))
    lake = np.isin(lab, 1 + np.flatnonzero(sizes >= 3))
    return acc, recv.reshape(H, W), lake

# ---------------------------------------------------------------- grids for paths

def grid_graph(nodes_mask, cost):
    """8-neighbour graph over the masked cells (x wraps); edge weight = step length * mean cell cost."""
    idx = np.full(H * W, -1, np.int64); cells = np.flatnonzero(nodes_mask.ravel()); idx[cells] = np.arange(len(cells))
    ys, xs = np.divmod(cells, W)
    R, C, D = [], [], []
    for dy, dx in N8:
        yy = ys + dy; ok = (yy >= 0) & (yy < H)
        j = np.where(ok, yy.clip(0, H - 1) * W + (xs + dx) % W, 0)
        ok &= idx[j] >= 0
        R.append(np.arange(len(cells))[ok]); C.append(idx[j[ok]]); D.append(np.full(ok.sum(), math.hypot(dy, dx)))
    R = np.concatenate(R); C = np.concatenate(C); D = np.concatenate(D)
    cf = cost.ravel()[cells]
    G = csr_matrix((D * (cf[R] + cf[C]) / 2, (R, C)), shape=(len(cells), len(cells)))
    G.sort_indices()
    rows = np.repeat(np.arange(len(cells)), np.diff(G.indptr))
    steps = G.data / ((cf[rows] + cf[G.indices]) / 2)
    return dict(G=G, idx=idx, cells=cells, rows=rows, steps=steps)

def set_cost(g, cost):
    cf = cost.ravel()[g['cells']]
    g['G'].data[:] = g['steps'] * (cf[g['rows']] + cf[g['G'].indices]) / 2

def trace(g, pred, target):
    out = []
    t = target
    while t >= 0:
        out.append(int(g['cells'][t])); t = pred[t]
    return out[::-1]

def path_km(path):
    s = 0.0
    for a, b in zip(path, path[1:]):
        ya, xa = divmod(a, W); yb, xb = divmod(b, W)
        s += math.hypot(wrapdx(xb - xa), yb - ya)
    return s * KM

# ---------------------------------------------------------------- names (as src/world/names.ts, in spirit)

FIRST = ['Maple', 'Oak', 'Cedar', 'Elm', 'Ash', 'Pine', 'Birch', 'Willow', 'Brook', 'Stone', 'River', 'Fair', 'Green', 'Hill',
         'Lake', 'Clear', 'Silver', 'Golden', 'Red', 'White', 'Black', 'Blue', 'Bell', 'Mill', 'Rock', 'Spring', 'Sun', 'Wolf',
         'Fox', 'Deer', 'Eagle', 'Crow', 'Iron', 'Copper', 'Salt', 'Harbor', 'Frost', 'Amber', 'Rose', 'Thorn']
ENDINGS = ['field', 'ton', 'ford', 'dale', 'wood', 'haven', 'ville', 'burg', 'port', 'mont', 'stead', 'crest', 'view', 'ridge',
           'vale', 'bury', 'ham']
SECOND = ['Falls', 'Springs', 'Heights', 'Crossing', 'Junction', 'Hollow', 'Landing', 'Bluffs', 'Meadows', 'Corners', 'Grove', 'Point']

class Names:
    def __init__(self, seed): self.seed = seed; self.used = set()
    def make(self, key, kind):
        for n in range(50):
            r = rng(self.seed, 'name', key, n); f = FIRST[r.integers(len(FIRST))]; s = r.random()
            if kind == 'port' and s < 0.4: name = f'Port {f}'
            elif kind == 'mountain' and s < 0.4: name = f'Mount {f}'
            elif s < 0.55: name = f + [e for e in ENDINGS if e not in f.lower()][r.integers(len(ENDINGS) - 1)]
            else: name = f'{f} {SECOND[r.integers(len(SECOND))]}'
            if name not in self.used: self.used.add(name); return name
        return f'{key}'

# ---------------------------------------------------------------- the world

HUB_TYPES = ['capital', 'port', 'river city', 'mountain town', 'mill town', 'farm town']
HUB_CODE = {'capital': 'C', 'port': 'P', 'river city': 'R', 'mountain town': 'Mt', 'mill town': 'Mi', 'farm town': 'F'}

def build(seed):
    w = terrain(seed)
    land, elev, T, rain, lat = w['land'], w['elev'], w['T'], w['rain'], w['lat']
    gy, gx = np.gradient(np.maximum(elev, 0))
    slope = np.hypot(gx, gy) / KM  # metres per km
    acc, recv, lake = hydrology(land, elev, rain)
    bio = biomes(land, elev, T, rain, lat, slope)
    bio[lake] = BIOMES.index('lake')
    ice = land & (bio == BIOMES.index('ice'))
    # continents: connected land (x wraps), the large ones
    lab3, _ = ndimage.label(np.concatenate([land, land, land], 1), np.ones((3, 3)))
    lab = lab3[:, W:2 * W].copy()
    # merge labels that continue across the seam: labels in the middle copy that equal ones in the left copy
    ids, sizes = np.unique(lab[land], return_counts=True)
    order = ids[np.argsort(-sizes)]; sizes = np.sort(sizes)[::-1]
    cont = np.zeros((H, W), np.int16)
    big = [i for i, s in zip(order, sizes) if s > 0.04 * land.sum()]
    for k, i in enumerate(big): cont[lab == i] = k + 1
    island = land & (cont == 0)
    # continent sizes
    conts = []
    for k in range(1, len(big) + 1):
        ys, xs = np.nonzero(cont == k)
        ang = np.angle(np.exp(2j * np.pi * xs / W).mean()); cxm = (ang / (2 * np.pi) * W) % W
        dx = wrapdx(xs - cxm) * KM; dy = (ys - ys.mean()) * KM
        cov = np.cov(np.stack([dx, dy])); ev, evec = np.linalg.eigh(cov)
        proj = np.stack([dx, dy]).T @ evec
        conts.append(dict(id=k, area_km2=round(len(xs) * KM * KM), cx=float(cxm), cy=float(ys.mean()),
                          long_km=round(float(np.ptp(proj[:, 1]))), short_km=round(float(np.ptp(proj[:, 0])))))
    # site fields
    sea = ~land
    coast = land & ndimage.binary_dilation(np.concatenate([sea] * 3, 1), np.ones((3, 3)))[:, W:2 * W]
    seafrac = ndimage.uniform_filter(sea.astype(np.float32), 11, mode=('nearest', 'wrap'))
    deep = ndimage.minimum_filter(elev, 7, mode=('nearest', 'wrap')) < -150
    valley = elev < ndimage.uniform_filter(elev, 13, mode=('nearest', 'wrap')) - 60
    rflat = np.exp(-slope / 25)
    usable = land & ~ice & ~lake & (elev < 2600)
    fertile = np.isin(bio, [BIOMES.index(b) for b in ['prairie', 'temperate forest', 'savanna', 'tropical forest', 'steppe']])
    riv = acc > 40
    near_riv = ndimage.binary_dilation(riv, np.ones((3, 3)))
    temperate = np.exp(-((T - 13) / 6) ** 2)
    names = Names(seed)
    hubs = []
    start = 1
    second = 2 if len(big) > 1 else 1

    def pick(kind, k, launch, tag):
        c = cont == k
        if kind == 'capital':
            hard = c & usable & riv & (acc < 2500) & (slope < 30) & (w['dl'] > 8)
            ccx, ccy = conts[k - 1]['cx'], conts[k - 1]['cy']
            central = np.exp(-(np.hypot(wrapdx(w['X'] - ccx), w['Y'] - ccy) / 70) ** 2)
            # gamma's river runs north-south through the plan: a receiver straight north or south scores
            ry, rx = np.divmod(recv, W); ns = (np.abs(wrapdx(rx - w['X'] + 0.5)) < 0.5) & (np.abs(ry - (w['Y'] - 0.5)) > 0.5)
            score = temperate * rflat * (1 + central) * np.exp(-((w['dl'] - 22) / 14) ** 2) * (1 + 0.5 * ns) * (1 + fertile)
        elif kind == 'port':
            hard = c & coast & ~ice & deep & (np.abs(lat) < 60)
            score = np.exp(-((seafrac - 0.35) / 0.15) ** 2) * (1 + (acc > 30)) * (0.3 + temperate) * rflat
        elif kind == 'river city':
            mx = acc[c].max() if c.any() else 1
            hard = c & usable & (acc > max(400, 0.18 * mx)) & ~coast & (elev < 400)
            score = np.log1p(acc) * rflat * (0.4 + temperate)
        elif kind == 'mountain town':
            hard = c & usable & (elev > 800) & (elev < 2000) & valley
            score = (0.5 + near_riv) * np.exp(-((elev - 1300) / 400) ** 2) * (0.3 + temperate)
        elif kind == 'mill town':
            fall = np.maximum(elev - np.where(recv >= 0, elev.ravel()[recv.clip(0)], elev), 0)
            hard = c & usable & (acc > 60) & (acc < 1500) & (fall > 15)
            score = np.log1p(fall) * (0.5 + temperate)
        else:  # farm town
            hard = c & usable & fertile & (slope < 12) & (rain > 420) & (elev < 600)
            score = rflat * np.exp(-((rain - 750) / 300) ** 2) * (0.4 + temperate) * (w['dl'] / (w['dl'] + 15))
        score = np.where(hard, score * (0.85 + 0.3 * rng(seed, 'site', tag).random(score.shape)), -1)
        for h in hubs:  # separation
            d = np.hypot(wrapdx(w['X'] - h['x']), w['Y'] - h['y']) * KM
            sep = 120 if (kind == 'port' and h['type'] == 'port') else 70
            score[d < sep] = -1
        i = int(np.argmax(score))
        if score.ravel()[i] <= 0: return None
        y, x = divmod(i, W)
        name = 'The capital (gamma)' if kind == 'capital' else names.make(f'hub/{tag}', kind.split()[0])
        h = dict(type=kind, x=x + 0.5, y=y + 0.5, cont=k, launch=launch, name=name, cell=i)
        hubs.append(h); return h

    pick('capital', start, True, 'capital')
    for kind in ['port', 'river city', 'mountain town', 'mill town', 'farm town']:
        if not pick(kind, start, True, f'launch/{kind}'):
            pick(kind, second, True, f'launch2/{kind}')
    if len(big) > 1: pick('port', second, True, 'launch/port2')
    # later hubs: every continent a port, then by type round the continents, to 22 in all
    for k in range(1, len(big) + 1):
        if not any(h['cont'] == k and h['type'] == 'port' for h in hubs): pick('port', k, False, f'port/{k}')
    cyc = ['port', 'river city', 'mountain town', 'mill town', 'farm town']
    t = 0
    while len(hubs) < 22 and t < 80:
        k = 1 + t % len(big); kind = cyc[(t // len(big)) % len(cyc)]
        pick(kind, k, False, f'later/{t}'); t += 1
    # towns: weighted Poisson disk
    suit = np.where(usable, rflat * (0.3 + fertile) * (1 + 0.8 * near_riv) * (1 + 0.5 * coast) * (0.3 + temperate), 0)
    r = rng(seed, 'towns')
    key = np.where(suit > 0, r.random(suit.shape) ** (1 / np.maximum(suit, 1e-6)), -1).ravel()
    towns = []
    B = 12; buckets = {}
    def near_any(x, y, rad, lst_buckets):
        bx, by = int(x // B), int(y // B)
        for dby in (-1, 0, 1):
            for dbx in (-1, 0, 1):
                for (tx, ty) in lst_buckets.get(((bx + dbx) % (W // B + 1), by + dby), ()):
                    if math.hypot(wrapdx(tx - x), ty - y) < rad: return True
        return False
    hb = {}
    for h in hubs: hb.setdefault((int(h['x'] // B) % (W // B + 1), int(h['y'] // B)), []).append((h['x'], h['y']))
    for i in np.argsort(-key):
        if key[i] < 0: break
        y, x = divmod(int(i), W); x += 0.5; y += 0.5
        rad = (12 + 13 * (1 - min(1, suit.ravel()[i] / 1.5))) / KM
        if near_any(x, y, 15 / KM, hb) or near_any(x, y, rad, buckets): continue
        buckets.setdefault((int(x // B) % (W // B + 1), int(y // B)), []).append((x, y))
        rr = rng(seed, 'rank', int(i)).random()
        towns.append(dict(x=x, y=y, cell=int(i), cont=int(cont.ravel()[i]),
                          rank='major' if rr < 0.06 else 'town' if rr < 0.45 else 'small'))
    w.update(slope=slope, acc=acc, recv=recv, lake=lake, bio=bio, ice=ice, cont=cont, island=island, conts=conts,
             hubs=hubs, towns=towns, coast=coast)
    networks(seed, w)
    return w

def rng_edges(pts):
    """Relative-neighbourhood graph of points (already unwrapped near each other)."""
    if len(pts) < 2: return []
    if len(pts) == 2: return [(0, 1)]
    P = np.array(pts)
    try: tri = Delaunay(P)
    except Exception: return [(i, i + 1) for i in range(len(pts) - 1)]
    E = set()
    for s in tri.simplices:
        for a, b in ((s[0], s[1]), (s[1], s[2]), (s[0], s[2])): E.add((min(a, b), max(a, b)))
    out = []
    for a, b in E:
        dab = np.hypot(*(P[a] - P[b]))
        da = np.hypot(*(P - P[a]).T); db = np.hypot(*(P - P[b]).T)
        m = np.maximum(da, db); m[[a, b]] = 1e9
        if m.min() >= dab: out.append((a, b))
    return out

def networks(seed, w):
    land, elev, slope, lake, acc, ice = w['land'], w['elev'], w['slope'], w['lake'], w['acc'], w['ice']
    riv = acc > 40
    base_road = 1 + (slope / 45) ** 2 + 25 * lake + 2.5 * riv + 4 * ice
    g = grid_graph(land, base_road)
    hubs, towns = w['hubs'], w['towns']
    roads, rails, county, branches = [], [], [], []
    road_cells = np.zeros(H * W, bool)
    stats = {}
    for k in range(1, len(w['conts']) + 1):
        nodes = [h for h in hubs if h['cont'] == k] + [t for t in towns if t['cont'] == k and t['rank'] == 'major']
        if len(nodes) < 2: continue
        cx = w['conts'][k - 1]['cx']
        pts = [(wrapdx(n['x'] - cx), n['y']) for n in nodes]
        edges = [(a, b) for a, b in rng_edges(pts) if math.hypot(pts[a][0] - pts[b][0], pts[a][1] - pts[b][1]) * KM < 170]
        cost = base_road.copy()
        for a, b in sorted(edges, key=lambda e: math.hypot(pts[e[0]][0] - pts[e[1]][0], pts[e[0]][1] - pts[e[1]][1])):
            sa, sb = g['idx'][nodes[a]['cell']], g['idx'][nodes[b]['cell']]
            if sa < 0 or sb < 0: continue
            dist, pred = dijkstra(g['G'], indices=sa, return_predecessors=True, limit=900)
            if not np.isfinite(dist[sb]): continue
            p = trace(g, pred, sb); roads.append(dict(path=p, a=a, b=b, cont=k, km=path_km(p)))
            road_cells[p] = True
            cost.ravel()[p] = np.minimum(cost.ravel()[p], 0.35 * base_road.ravel()[p])
            set_cost(g, cost)
        # rail: MST of the hubs and major towns on grade-strict costs, plus a few loops
        rc = 1 + np.minimum((slope / 18) ** 3, 400) + 60 * lake + 5 * riv + 10 * ice
        gr = grid_graph(land, rc)
        srcs = [gr['idx'][n['cell']] for n in nodes]
        if min(srcs) < 0: continue
        dist, pred = dijkstra(gr['G'], indices=srcs, return_predecessors=True, limit=6000)
        M = np.zeros((len(nodes), len(nodes)))
        for a, b in edges: M[a, b] = dist[a, srcs[b]] if np.isfinite(dist[a, srcs[b]]) else 0
        mst = minimum_spanning_tree(csr_matrix(M)).tocoo()
        chosen = set(zip(mst.row.tolist(), mst.col.tolist()))
        r = rng(seed, 'rail-loops', k)
        for a, b in edges:
            if (a, b) not in chosen and M[a, b] > 0 and r.random() < 0.25: chosen.add((a, b))
        for a, b in chosen:
            p = trace(gr, pred[a], srcs[b]); rails.append(dict(path=p, cont=k, km=path_km(p)))
        rail_cells = np.zeros(H * W, bool)
        for l in rails:
            if l['cont'] == k: rail_cells[l['path']] = True
        # branches to towns within 30 km of a main line (half of them)
        rs = gr['idx'][np.flatnonzero(rail_cells)]
        if len(rs):
            dist, pred, srcm = dijkstra(gr['G'], indices=rs, return_predecessors=True, min_only=True, limit=60)
            for t in towns:
                if t['cont'] != k or t['rank'] == 'small': continue
                j = gr['idx'][t['cell']]
                if j < 0 or not np.isfinite(dist[j]) or dist[j] == 0: continue
                if path_km(trace(gr, pred, j)) < 30 and rng(seed, 'branch', t['cell']).random() < 0.5:
                    branches.append(trace(gr, pred, j))
        # the drive across: the longest shortest highway trip between two hubs of this continent
        hubn = [i for i, n in enumerate(nodes) if 'type' in n]
        if len(hubn) >= 2:
            HG = np.zeros((len(nodes), len(nodes)))
            for rd in roads:
                if rd['cont'] == k: HG[rd['a'], rd['b']] = HG[rd['b'], rd['a']] = rd['km']
            D = dijkstra(csr_matrix(HG), indices=hubn)
            D = D[:, hubn]; fin = D[np.isfinite(D)]
            stats[k] = dict(longest_hub_drive_km=round(float(fin.max())) if len(fin) else None)
    # county roads: every town to the nearest highway
    rs = g['idx'][np.flatnonzero(road_cells)]
    rs = rs[rs >= 0]
    if len(rs):
        set_cost(g, base_road)
        dist, pred, _ = dijkstra(g['G'], indices=rs, return_predecessors=True, min_only=True, limit=200)
        for t in towns:
            j = g['idx'][t['cell']]
            if j >= 0 and np.isfinite(dist[j]) and dist[j] > 0: county.append(trace(g, pred, j))
    # sea routes between ports: MST of sea distances plus each port's nearest foreign port
    sea_ok = ~land & (np.abs(w['lat']) < 63)
    sc = np.where(w['ds'] < 2, 1.6, np.where(w['ds'] < 6, 1.25, 1.0))  # liners keep off the coasts
    gs = grid_graph(sea_ok, sc)
    ports = [h for h in hubs if h['type'] == 'port']
    sea_routes = []
    if len(ports) >= 2:
        def sea_cell(h):
            y, x = divmod(h['cell'], W)
            best = None
            for rr in range(1, 6):
                for dy in range(-rr, rr + 1):
                    for dx in range(-rr, rr + 1):
                        yy = y + dy
                        if 0 <= yy < H and sea_ok[yy, (x + dx) % W]:
                            d = math.hypot(dx, dy)
                            if best is None or d < best[0]: best = (d, yy * W + (x + dx) % W)
                if best: return best[1]
            return None
        sc_ = [sea_cell(p) for p in ports]
        keep = [i for i, c in enumerate(sc_) if c is not None and gs['idx'][c] >= 0]
        ports = [ports[i] for i in keep]; srcs = [gs['idx'][sc_[i]] for i in keep]
        dist, pred = dijkstra(gs['G'], indices=srcs, return_predecessors=True)
        n = len(ports); M = np.zeros((n, n))
        for a in range(n):
            for b in range(n):
                if a != b and np.isfinite(dist[a, srcs[b]]): M[a, b] = dist[a, srcs[b]]
        pairs = set()
        mst = minimum_spanning_tree(csr_matrix(M)).tocoo()
        pairs |= {(min(a, b), max(a, b)) for a, b in zip(mst.row.tolist(), mst.col.tolist())}
        for a in range(n):
            f = [b for b in range(n) if ports[b]['cont'] != ports[a]['cont'] and M[a, b] > 0]
            if f: b = min(f, key=lambda b: M[a, b]); pairs.add((min(a, b), max(a, b)))
        sight = sea_sight(w)
        for a, b in pairs:
            p = trace(gs, pred[a], srcs[b])
            km = path_km(p)
            hours, open_km = 0.0, 0.0
            for u, v in zip(p, p[1:]):
                yu, xu = divmod(u, W); yv, xv = divmod(v, W)
                seg = math.hypot(wrapdx(xv - xu), yv - yu) * KM
                d = (sight.ravel()[u] + sight.ravel()[v]) / 2
                c = compression(d)
                hours += seg / (11.1 * c)
                if c >= 30: open_km += seg
            sea_routes.append(dict(path=p, a=ports[a]['name'], b=ports[b]['name'], km=round(km), open_km=round(open_km),
                                   hours=round(hours, 1), true_hours=round(km / 11.1, 1),
                                   cross=ports[a]['cont'] != ports[b]['cont']))
    w.update(roads=roads, rails=rails, county=county, branches=branches, sea_routes=sea_routes, drive=stats)

def smooth(a, b, x):
    t = min(1.0, max(0.0, (x - a) / (b - a))); return t * t * (3 - 2 * t)

D0 = 1.0  # km: the nearest land never seems to move faster than it does from 1 km off at true speed

def compression(d):
    """WORLD_GEN.md section 10: c = distance to the nearest land / D0, from 1 (harbour, close inshore) to 30 (out of sight)."""
    return min(30.0, max(1.0, d / D0))

def sea_sight(w):
    """Distance to land (km), less a bonus for tall land: how far out of sight of land each sea cell is."""
    land = w['land']
    pad = np.concatenate([land] * 3, 1)
    d, ind = ndimage.distance_transform_edt(~pad, return_indices=True)
    d = d[:, W:2 * W] * KM
    iy, ix = ind[0][:, W:2 * W], ind[1][:, W:2 * W] % W
    h = np.maximum(w['elev'][iy, ix], 0)
    return d  # (h: a later refinement may count tall land as nearer)

# ---------------------------------------------------------------- drawing

def shade(elev, km_per_px):
    gy, gx = np.gradient(np.maximum(elev, 0), km_per_px * 1000)
    n = np.dstack([-gx * 6, -gy * 6, np.ones_like(gx)]); n /= np.linalg.norm(n, axis=2, keepdims=True)
    l = np.array([-0.6, -0.6, 0.55]); l /= np.linalg.norm(l)
    return np.clip(n @ l, 0, 1)

def colour(bio, elev, land, lat, ice, sh):
    img = np.zeros(bio.shape + (3,), np.float32)
    for name, c in BCOL.items(): img[bio == BIOMES.index(name)] = c
    snow = land & (elev > 3100)
    img[snow] = (230, 234, 240)
    img *= (0.55 + 0.75 * sh)[..., None] * np.where(land, 1, 0)[..., None] / 1.0
    depth = np.clip(-elev / 4500, 0, 1)
    sea = np.stack([60 - 40 * depth, 120 - 70 * depth, 170 - 60 * depth], -1)
    a = np.abs(lat)
    sice = np.clip((a - 62) / 8, 0, 1)[..., None]
    sea = sea * (1 - sice) + np.array([214, 226, 236]) * sice
    img = np.where(land[..., None], img, sea)
    img[bio == BIOMES.index('lake')] = (72, 124, 176)
    return np.clip(img, 0, 255).astype(np.uint8)

def font(size, bold=False):
    try: return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), size)
    except Exception: return ImageFont.load_default()

def draw_poly(d, pts, fill, width, tf):
    """Cell centres -> pixels through tf; split where the path wraps across the edge."""
    seg = []
    prev = None
    for c in pts:
        y, x = divmod(c, W); x += 0.5; y += 0.5
        if prev is not None and abs(x - prev[0]) > W / 2:
            if len(seg) > 1: d.line(seg, fill=fill, width=width)
            seg = []
        q = tf(x, y)
        if q is not None: seg.append(q)
        prev = (x, y)
    if len(seg) > 1: d.line(seg, fill=fill, width=width)

HUB_FILL = {'capital': (220, 40, 40), 'port': (40, 90, 210), 'river city': (30, 170, 200), 'mountain town': (120, 80, 50),
            'mill town': (200, 120, 30), 'farm town': (230, 200, 40)}

def draw_hub(d, p, h, f, label=None, big=False, left=None):
    x, y = p; r = 6 if big else 4
    if h['launch']: d.ellipse([x - r - 2, y - r - 2, x + r + 2, y + r + 2], outline=(255, 215, 0), width=2)
    d.rectangle([x - r, y - r, x + r, y + r], fill=HUB_FILL[h['type']], outline=(0, 0, 0))
    t = label if label is not None else HUB_CODE[h['type']]
    tx = x + r + 2 if left is None or x + r + 2 + d.textlength(t, font=f) < left else x - r - 2 - d.textlength(t, font=f)
    d.text((tx, y - r - 3), t, fill=(0, 0, 0), font=f, stroke_width=2, stroke_fill=(255, 255, 255))

def render_planet(w):
    sh = shade(w['elev'], KM)
    img = Image.fromarray(colour(w['bio'], w['elev'], w['land'], w['lat'], w['ice'], sh))
    d = ImageDraw.Draw(img)
    tf = lambda x, y: (x, y)
    acc = w['acc']; recv = w['recv']
    for i in np.flatnonzero((acc > 120).ravel() & w['land'].ravel()):
        y, x = divmod(int(i), W); r = recv.ravel()[i]
        if r < 0: continue
        ry, rx = divmod(int(r), W)
        if abs(rx - x) > 1: continue
        d.line([(x + .5, y + .5), (rx + .5, ry + .5)], fill=(40, 90, 200), width=1 if acc.ravel()[i] < 1500 else 2)
    for p in w['county']: draw_poly(d, p, (150, 130, 110), 1, tf)
    for rl in w['rails']: draw_poly(d, rl['path'], (20, 20, 20), 1, tf)
    for rd in w['roads']: draw_poly(d, rd['path'], (240, 130, 40), 1, tf)
    for s in w['sea_routes']: draw_poly(d, s['path'], (255, 255, 255), 1, tf)
    for t in w['towns']:
        if t['rank'] != 'small': d.point((t['x'], t['y']), fill=(30, 30, 30))
    f = font(10, True)
    for h in w['hubs']: draw_hub(d, (h['x'], h['y']), h, f)
    return img

def render_zoom(seed, w, maxw, maxh):
    c0 = w['conts'][0]
    R = W / 2 - c0['cx']  # roll so the start continent is in the middle
    ys, xs = np.nonzero(w['cont'] == 1)
    xr = (xs + R) % W
    x0, x1 = max(0, xr.min() - 8), min(W, xr.max() + 8); y0, y1 = max(0, ys.min() - 8), min(H, ys.max() + 8)
    s = min(maxw / (x1 - x0), maxh / (y1 - y0))
    pw, ph = int((x1 - x0) * s), int((y1 - y0) * s)
    PY, PX = np.mgrid[0:ph, 0:pw].astype(np.float32)
    CX = x0 + (PX + 0.5) / s; CY = y0 + (PY + 0.5) / s  # rolled cell coords
    WX = (CX - R) % W  # world cell coords
    def samp(a, order=1):
        ar = np.roll(a, int(round(R)), axis=1).astype(np.float32)
        return ndimage.map_coordinates(ar, [CY - 0.5, CX - 0.5], order=order, mode='nearest')
    mask = samp(w['mask']) + 0.05 * (fbm(seed, 'fine-coast', 160, 3, WX, CY) - 0.5)
    land = mask > w['thr']
    elev = samp(w['elev']) + np.where(land, 160 * (fbm(seed, 'fine-hills', 200, 3, WX, CY) - 0.5), 0)
    elev = np.where(land, np.maximum(elev, 4), np.minimum(elev, -5))
    T = samp(w['T']); rain = samp(w['rain']); lat = (CY / H) * 180 - 90
    gy, gx = np.gradient(np.maximum(elev, 0)); slope = np.hypot(gx, gy) / (KM / s)
    bio = biomes(land, elev, T, rain, lat, slope)
    lake = samp(w['lake'].astype(np.float32)) > 0.5
    bio[lake & land] = BIOMES.index('lake')
    img = Image.fromarray(colour(bio, elev, land, lat, None, shade(elev, KM / s)))
    d = ImageDraw.Draw(img)
    def tf(x, y):
        return (((x + R) % W - x0) * s, (y - y0) * s)
    def tfc(x, y):
        q = tf(x, y); return q if -20 < q[0] < pw + 20 else None
    acc, recv = w['acc'], w['recv']
    for i in np.flatnonzero((acc > 40).ravel() & w['land'].ravel()):
        y, x = divmod(int(i), W); r = recv.ravel()[i]
        if r < 0: continue
        ry, rx = divmod(int(r), W)
        if abs(rx - x) > 1: continue
        a, b = tfc(x + .5, y + .5), tfc(rx + .5, ry + .5)
        if a and b: d.line([a, b], fill=(46, 98, 205), width=1 + int(acc.ravel()[i] > 300) + int(acc.ravel()[i] > 1500))
    for p in w['county']: draw_poly(d, p, (120, 100, 80), 1, tfc)
    for p in w['branches']: draw_poly(d, p, (60, 60, 60), 1, tfc)
    for rl in w['rails']: draw_poly(d, rl['path'], (15, 15, 15), 3, tfc)
    for rd in w['roads']: draw_poly(d, rd['path'], (240, 130, 40), 2, tfc)
    for sr in w['sea_routes']: draw_poly(d, sr['path'], (255, 255, 255), 1, tfc)
    # hamlets (4-8 km apart) on the continent, sketched
    r = rng(seed, 'hamlets')
    cells = np.flatnonzero(((w['cont'] == 1) & ~w['ice'] & ~w['lake']).ravel())
    pts = []
    for i in r.permutation(cells)[:6000]:
        y, x = divmod(int(i), W)
        px_, py_ = x + r.random(), y + r.random()
        if all(math.hypot(wrapdx(px_ - a), py_ - b) > 2.5 for a, b in pts[-400:]) and \
           all(math.hypot(wrapdx(px_ - t['x']), py_ - t['y']) > 2.5 for t in w['towns'] if t['cont'] == 1 and abs(t['y'] - py_) < 3):
            pts.append((px_, py_))
    for a, b in pts:
        q = tfc(a, b)
        if q: d.point(q, fill=(70, 50, 40))
    for t in w['towns']:
        if t['cont'] != 1: continue
        q = tfc(t['x'], t['y'])
        if not q: continue
        rr = {'major': 3, 'town': 2, 'small': 1}[t['rank']]
        d.rectangle([q[0] - rr, q[1] - rr, q[0] + rr, q[1] + rr], fill=(250, 250, 250), outline=(20, 20, 20))
    f = font(11, True)
    for h in w['hubs']:
        q = tfc(h['x'], h['y'])
        if q and 0 <= q[0] < pw: draw_hub(d, q, h, f, f"{h['name']} ({h['type']})", big=True, left=pw)
    # scale bar: 100 km
    L = 100 / KM * s
    d.rectangle([10, ph - 22, 10 + L, ph - 16], fill=(255, 255, 255), outline=(0, 0, 0))
    d.text((14 + L, ph - 26), '100 km', fill=(0, 0, 0), font=font(11), stroke_width=2, stroke_fill=(255, 255, 255))
    return img

def legend(width):
    img = Image.new('RGB', (width, 64), (245, 243, 238)); d = ImageDraw.Draw(img); f = font(12); fb = font(14, True)
    d.text((8, 4), 'World-gen sketch (kotlin-street/tools/world-sketch.py): planet 1,996 x 998 km, x wraps; left: the unwrapped planet '
           '(2.15 km a pixel), right: the start continent', fill=(0, 0, 0), font=fb)
    x = 8; y = 26
    for k, c in HUB_FILL.items():
        d.rectangle([x, y, x + 10, y + 10], fill=c, outline=(0, 0, 0)); d.text((x + 14, y - 2), f'{HUB_CODE[k]} {k}', fill=(0, 0, 0), font=f)
        x += 14 + d.textlength(f'{HUB_CODE[k]} {k}', font=f) + 14
    d.ellipse([x, y - 2, x + 14, y + 12], outline=(255, 215, 0), width=2); d.text((x + 18, y - 2), 'launch hub', fill=(0, 0, 0), font=f)
    x += 100
    for name, c, wd in [('rail', (15, 15, 15), 3), ('highway', (240, 130, 40), 2), ('county road', (120, 100, 80), 1),
                        ('sea route', (255, 255, 255), 2), ('river', (46, 98, 205), 2)]:
        d.line([(x, y + 5), (x + 22, y + 5)], fill=c, width=wd); d.text((x + 26, y - 2), name, fill=(0, 0, 0), font=f)
        x += 32 + d.textlength(name, font=f) + 12
    x = 8; y = 46
    for name in ['ice', 'tundra', 'taiga', 'temperate forest', 'temperate rainforest', 'prairie', 'steppe', 'desert', 'savanna',
                 'tropical forest', 'rainforest', 'alpine', 'marsh']:
        d.rectangle([x, y, x + 10, y + 10], fill=BCOL[name], outline=(80, 80, 80)); d.text((x + 13, y - 2), name, fill=(0, 0, 0), font=f)
        x += 13 + d.textlength(name, font=f) + 10
    d.text((x, y - 2), 'white squares: towns', fill=(0, 0, 0), font=f)
    return img

def summary(seed, w):
    land = w['land']; nonice = np.abs(w['lat']) < 90
    sr = w['sea_routes']; cross = [s for s in sr if s['cross']]
    rivers = int(((w['acc'] > 120) & land).sum())
    return dict(seed=seed, continents=len(w['conts']), land_pct=round(100 * land.mean(), 1),
                island_pct_of_land=round(100 * w['island'].sum() / land.sum(), 1),
                conts=w['conts'], hubs=[{k: h[k] for k in ('type', 'name', 'cont', 'launch')} for h in w['hubs']],
                towns=len(w['towns']), towns_by_rank={r: sum(t['rank'] == r for t in w['towns']) for r in ('major', 'town', 'small')},
                highways=len(w['roads']), highway_km=round(sum(r['km'] for r in w['roads'])),
                rail_lines=len(w['rails']), rail_km=round(sum(r['km'] for r in w['rails'])), branches=len(w['branches']),
                lakes_cells=int(w['lake'].sum()), river_cells=rivers, drive=w['drive'],
                sea_routes=[{k: s[k] for k in ('a', 'b', 'km', 'open_km', 'hours', 'true_hours', 'cross')} for s in sr],
                crossing_hours=[s['hours'] for s in cross])

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('seeds', nargs='+'); ap.add_argument('--out', required=True); ap.add_argument('--stats')
    ap.add_argument('--land', type=float, default=0.30, help='land fraction (the owner\'s 30%%; try 0.2 for smaller continents)')
    a = ap.parse_args()
    global LAND
    LAND = a.land
    rows, stats = [], []
    SW = 1600; zw = SW - W - 24
    for seed in a.seeds:
        print('seed', seed, file=sys.stderr)
        w = build(seed)
        s = summary(seed, w); stats.append(s)
        planet = render_planet(w); zoom = render_zoom(seed, w, zw, H)
        row = Image.new('RGB', (SW, H + 26), (245, 243, 238)); d = ImageDraw.Draw(row)
        c1 = s['conts'][0]
        dr = s['drive'].get(1, {}).get('longest_hub_drive_km')
        cr = s['crossing_hours']
        d.text((8, 5), f"seed '{seed}': {s['continents']} continents, land {s['land_pct']}% ({s['island_pct_of_land']}% of it islands), "
               f"{len(s['hubs'])} hubs ({sum(h['launch'] for h in s['hubs'])} at launch), {s['towns']} towns; start continent "
               f"{c1['long_km']} x {c1['short_km']} km, longest hub-to-hub drive {dr} km; ocean crossings "
               f"{min(cr) if cr else '-'}-{max(cr) if cr else '-'} h at sea", fill=(0, 0, 0), font=font(12, True))
        row.paste(planet, (8, 24)); row.paste(zoom, (W + 16, 24))
        rows.append(row)
    sheet = Image.new('RGB', (SW, 64 + sum(r.height for r in rows)), (245, 243, 238))
    sheet.paste(legend(SW), (0, 0)); y = 64
    for r in rows: sheet.paste(r, (0, y)); y += r.height
    sheet.save(a.out, quality=88)
    if a.stats:
        with open(a.stats, 'w') as fh: json.dump(stats, fh, indent=1)
    for s in stats:
        print(json.dumps({k: s[k] for k in ('seed', 'continents', 'land_pct', 'island_pct_of_land', 'towns', 'highway_km', 'rail_km',
                                            'drive', 'crossing_hours')}))
        for c in s['conts']: print('  continent', c)
        for h in s['hubs']: print('  hub', h)

if __name__ == '__main__':
    main()
