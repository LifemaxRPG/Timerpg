import { rand } from '../sim/rng';
import type { Road } from './plan';

// The STREET NETWORK. A dense but irregular web of streets inside an organic, blob-shaped outline:
//  - a main street that jogs sideways once or twice,
//  - cross streets at uneven spacing that stop short of the edge at different points,
//  - side streets at uneven spacing that sometimes break in the middle (dead ends),
//  - an irregular outline (a blend of sine waves), so the town is round-ish, not a rectangle.
// A pure function of (seed, extra, river); `extra` grows the town if it needs more room, and `river`
// removes one cross street so there is room for a river to take its place.

export interface Network {
  /** Canvas size in tiles (roads plus room for lots and forest). */
  width: number;
  height: number;
  roads: Road[];
  /** Row of the main street's top edge at the town center. */
  mainY: number;
  center: { x: number; y: number };
  /** Roughly the town's horizontal radius in tiles. */
  radius: number;
}

const PAD = 46; // room around the roads for lots (up to ~24 deep), a lake, and the forest

export function planNetwork(seed: string, extra: number, river = false): Network {
  const R = (...k: (string | number)[]): number => rand(seed, 'net', ...k);
  const rx = Math.round(66 + extra * 7 + R('rx') * 10);
  const ry = Math.round(rx * (0.72 + R('ry') * 0.1));
  const ph = [R('bp1') * 6.28, R('bp2') * 6.28, R('bp3') * 6.28];

  /** Is (x,y) (relative to the center) inside the town outline? */
  const inside = (x: number, y: number): boolean => {
    const u = x / rx, v = y / ry;
    const th = Math.atan2(v, u);
    return Math.hypot(u, v) < 1 + 0.16 * Math.sin(2 * th + ph[0]!) + 0.1 * Math.sin(3 * th + ph[1]!) + 0.06 * Math.sin(5 * th + ph[2]!);
  };
  const extent = (fixed: number, horizontal: boolean): [number, number] | null => {
    let lo = Infinity, hi = -Infinity;
    const lim = Math.ceil(Math.max(rx, ry) * 1.3);
    for (let t = -lim; t <= lim; t++) if (horizontal ? inside(t, fixed) : inside(fixed, t)) { lo = Math.min(lo, t); hi = Math.max(hi, t); }
    return lo === Infinity ? null : [lo, hi];
  };

  // ---- main street: horizontal segments joined by short vertical jogs ----
  const [mLo, mHi] = extent(0, true)!;
  const nJogs = 1 + Math.floor(R('jogs') * 2);
  const jogX: number[] = [];
  const level: number[] = [0];
  for (let j = 0; j < nJogs; j++) {
    jogX.push(Math.round(mLo + ((j + 1) * (mHi - mLo)) / (nJogs + 1) + (R('jx', j) - 0.5) * 12));
    const dy = (R('jd', j) < 0.5 ? -1 : 1) * (3 + Math.floor(R('jm', j) * 4));
    level.push(Math.max(-8, Math.min(8, level[j]! + dy)));
  }
  const roads: Road[] = [];
  const edges = [mLo, ...jogX, mHi];
  for (let s = 0; s < edges.length - 1; s++) roads.push({ x: edges[s]!, y: level[s]!, w: edges[s + 1]! - edges[s]! + 2, h: 2, main: true, horizontal: true });
  for (let j = 0; j < nJogs; j++) {
    const a = level[j]!, b = level[j + 1]!;
    roads.push({ x: jogX[j]!, y: Math.min(a, b), w: 2, h: Math.abs(a - b) + 2, main: true, horizontal: false });
  }
  const yAt = (x: number): number => {
    for (let s = 0; s < edges.length - 1; s++) if (x >= edges[s]! && x < edges[s + 1]! + 2) return level[s]!;
    return level[level.length - 1]!;
  };

  // ---- cross streets (north-south) at uneven spacing ----
  const xs: number[] = [];
  const x0 = Math.round(R('v0') * 8) - 4;
  for (const dir of [1, -1]) {
    for (let x = dir === 1 ? x0 : x0 - (19 + Math.floor(R('vl', 0) * 8)), i = 1; Math.abs(x) < rx * 1.05; i++) {
      if (!jogX.some((jx) => Math.abs(x - jx) < 6)) xs.push(x);
      x += dir * (19 + Math.floor(R('vstep', dir, i) * 8));
    }
  }
  xs.sort((a, b) => a - b);
  if (river) {
    // A river takes the place of one cross street, away from the very center.
    const cands = xs.filter((x) => Math.abs(x) > rx * 0.2 && Math.abs(x) < rx * 0.65);
    if (cands.length) xs.splice(xs.indexOf(cands[Math.floor(R('riverx') * cands.length)]!), 1);
  }
  const horizontals: Road[] = [];
  const verticals: Road[] = [];

  // ---- side streets (east-west) at uneven spacing ----
  const levels: number[] = [];
  for (const side of [-1, 1]) {
    for (let off = 27 + Math.floor(R('hl', side) * 6), k = 0; off < ry * 1.05; off += 16 + Math.floor(R('hs', side, k) * 8), k++) levels.push(side * off);
  }
  for (const y of levels) {
    const ext = extent(y, true);
    if (!ext || R('hx', y) > 0.92) continue;
    const a = ext[0] + Math.floor(R('hta', y) * 7), b = ext[1] - Math.floor(R('htb', y) * 7);
    if (b - a < 28) continue;
    // Sometimes the street breaks in the middle, leaving two dead ends.
    const segs: [number, number][] = R('hbreak', y) < 0.3
      ? (() => { const g = a + 12 + Math.floor(R('hbp', y) * Math.max(1, b - a - 36)); const gl = 9 + Math.floor(R('hbl', y) * 6); return [[a, g], [g + gl, b]] as [number, number][]; })()
      : [[a, b]];
    for (const [s0, s1] of segs) if (s1 - s0 >= 22) horizontals.push({ x: s0, y, w: s1 - s0, h: 2, main: false, horizontal: true });
  }
  for (const x of xs) {
    const ext = extent(x, false);
    if (!ext) continue;
    const yM = yAt(x);
    const a = Math.min(ext[0] + Math.floor(R('vta', x) * 8), yM - 12), b = Math.max(ext[1] - Math.floor(R('vtb', x) * 8), yM + 12);
    const segs: [number, number][] = R('vbreak', x) < 0.25
      ? (() => { const g = a + 14 + Math.floor(R('vbp', x) * Math.max(1, b - a - 40)); const gl = 9 + Math.floor(R('vbl', x) * 6); return [[a, g], [g + gl, b]] as [number, number][]; })()
      : [[a, b]];
    for (const [s0, s1] of segs) if (s1 - s0 >= 22) verticals.push({ x, y: s0, w: 2, h: s1 - s0, main: false, horizontal: false });
  }
  roads.push(...horizontals, ...verticals);

  // ---- keep only streets connected to the main street ----
  const touches = (p: Road, q: Road): boolean => p.x <= q.x + q.w + 1 && q.x <= p.x + p.w + 1 && p.y <= q.y + q.h + 1 && q.y <= p.y + p.h + 1;
  const keep = new Set<Road>(roads.filter((r) => r.main));
  for (let grew = true; grew; ) {
    grew = false;
    for (const r of roads) if (!keep.has(r) && [...keep].some((k) => touches(r, k))) { keep.add(r); grew = true; }
  }
  const final = roads.filter((r) => keep.has(r));

  // ---- move into canvas coordinates ----
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const r of final) {
    minX = Math.min(minX, r.x); minY = Math.min(minY, r.y);
    maxX = Math.max(maxX, r.x + r.w); maxY = Math.max(maxY, r.y + r.h);
  }
  const dx = PAD - minX, dy = PAD - minY;
  for (const r of final) { r.x += dx; r.y += dy; }
  return {
    width: maxX - minX + 2 * PAD,
    height: maxY - minY + 2 * PAD,
    roads: final,
    mainY: yAt(0) + dy,
    center: { x: dx, y: yAt(0) + dy + 1 },
    radius: rx,
  };
}
