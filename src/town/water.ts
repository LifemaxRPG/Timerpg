import { archetypeOf } from '../sim/archetype';
import { rand } from '../sim/rng';
import type { Rect, Road } from './plan';

// WATER: a per-seed river or lake. Pure function of the seed (keyed randomness).
//
// A river flows north-south through the gap between two neighbouring north-south streets, so it
// only crosses the east-west streets (each crossing becomes a bridge). A lake is an organic blob
// that sits on at least one (non-main) street, which then bridges over it. Some seeds have no water.
// Plots near the water are dropped by plan.ts (see `hitsWater`); the renderer autotiles the shore.

export interface Bridge extends Rect {
  /** True if the road it carries runs east-west. */
  horizontal: boolean;
}

export interface WaterBody {
  kind: 'river' | 'lake';
  width: number;
  height: number;
  /** Row-major (y * width + x): 1 = visible, impassable water. Never covers a road or sidewalk. */
  mask: Uint8Array;
  /** Footprint before roads were laid over it (includes road cells); used to keep plots/props clear. */
  raw: Uint8Array;
  /** Decks (road + sidewalks) where a street crosses or touches the water. */
  bridges: Bridge[];
}

export interface WaterGeometry {
  width: number;
  height: number;
  marginL: number;
  marginR: number;
  top: number;
  /** North-south street x positions (also present in `roads`). */
  vroads: number[];
  /** ALL streets, east-west and north-south. */
  roads: Road[];
}

export const isWater = (w: WaterBody | null | undefined, tx: number, ty: number): boolean =>
  !!w && tx >= 0 && ty >= 0 && tx < w.width && ty < w.height && w.mask[ty * w.width + tx] === 1;

/** Is there water (including the part hidden under bridges) within `pad` tiles of tile (tx,ty)? */
export function waterNear(w: WaterBody | null | undefined, tx: number, ty: number, pad: number): boolean {
  if (!w) return false;
  for (let y = Math.max(0, ty - pad); y <= Math.min(w.height - 1, ty + pad); y++)
    for (let x = Math.max(0, tx - pad); x <= Math.min(w.width - 1, tx + pad); x++) if (w.raw[y * w.width + x]) return true;
  return false;
}

/** Does a tile rectangle come within `pad` tiles of the water? */
export function hitsWater(w: WaterBody | null | undefined, r: Rect, pad: number): boolean {
  if (!w) return false;
  for (let y = Math.max(0, r.y - pad); y < Math.min(w.height, r.y + r.h + pad); y++)
    for (let x = Math.max(0, r.x - pad); x < Math.min(w.width, r.x + r.w + pad); x++) if (w.raw[y * w.width + x]) return true;
  return false;
}

/** Is tile (tx,ty) on a bridge deck? */
export const onBridge = (w: WaterBody | null | undefined, tx: number, ty: number): boolean =>
  !!w && w.bridges.some((b) => tx >= b.x && tx < b.x + b.w && ty >= b.y && ty < b.y + b.h);

/**
 * Removes shapes the shore tileset cannot draw (1-tile channels, dents with water on opposite
 * sides, single-tile spikes) so every shore cell is a plain edge, corner or inner corner.
 */
function smooth(m: Uint8Array, W: number, H: number): void {
  const at = (x: number, y: number, oob: number): number => (x < 0 || y < 0 || x >= W || y >= H ? oob : m[y * W + x]!);
  for (let iter = 0; iter < 12; iter++) {
    let changed = false;
    for (let y = 0; y < H; y++)
      for (let x = 0; x < W; x++) {
        const i = y * W + x;
        if (!m[i]) {
          const n = at(x, y - 1, 0), e = at(x + 1, y, 0), s = at(x, y + 1, 0), w = at(x - 1, y, 0);
          const diag = at(x - 1, y - 1, 0) + at(x + 1, y - 1, 0) + at(x - 1, y + 1, 0) + at(x + 1, y + 1, 0);
          const n4 = n + e + s + w;
          if ((n && s) || (e && w) || n4 >= 3 || (n4 === 0 && diag >= 2)) {
            m[i] = 1;
            changed = true;
          }
        } else {
          const n4 = at(x, y - 1, 1) + at(x + 1, y, 1) + at(x, y + 1, 1) + at(x - 1, y, 1);
          if (n4 <= 1) {
            m[i] = 0;
            changed = true;
          }
        }
      }
    if (!changed) break;
  }
}

const corridor = (r: Road): Rect => ({ x: r.x - 1, y: r.y - 1, w: r.w + 2, h: r.h + 2 });

/** Decks for every street that crosses (or runs right beside) the water. */
function findBridges(raw: Uint8Array, W: number, H: number, roads: readonly Road[]): Bridge[] {
  const out: Bridge[] = [];
  const hit = (x: number, y: number): boolean => {
    for (let dy = -1; dy <= 1; dy++)
      for (let dx = -1; dx <= 1; dx++) {
        const px = x + dx, py = y + dy;
        if (px >= 0 && py >= 0 && px < W && py < H && raw[py * W + px]) return true;
      }
    return false;
  };
  for (const r of roads) {
    const c = corridor(r);
    const lo = r.horizontal ? Math.max(0, c.x) : Math.max(0, c.y);
    const hi = r.horizontal ? Math.min(W, c.x + c.w) : Math.min(H, c.y + c.h);
    const cross = r.horizontal ? c.h : c.w;
    const hits: number[] = [];
    for (let t = lo; t < hi; t++) {
      let any = false;
      for (let k = 0; k < cross && !any; k++) any = r.horizontal ? hit(t, c.y + k) : hit(c.x + k, t);
      if (any) hits.push(t);
    }
    // Group into runs (a gap of <= 2 tiles is bridged over as well).
    for (let a = 0; a < hits.length; ) {
      let b = a;
      while (b + 1 < hits.length && hits[b + 1]! - hits[b]! <= 3) b++;
      const start = hits[a]!, len = hits[b]! - start + 1;
      out.push(r.horizontal ? { x: start, y: c.y, w: len, h: c.h, horizontal: true } : { x: c.x, y: start, w: c.w, h: len, horizontal: false });
      a = b + 1;
    }
  }
  return out;
}

/** What water a seed gets (decided up front so the street network can make room for a river). */
export function waterKind(seed: string): 'none' | 'river' | 'lake' {
  const roll = waterRoll(seed);
  return roll < 0.28 ? 'none' : roll < 0.72 ? 'river' : 'lake';
}

/**
 * The seed's water roll, bent by the town's type: harbor and resort towns always have water (harbors
 * lean to a river or the sea-like lake, resorts to a lake), mill towns always sit on a river.
 * Below 0.28 = none, below 0.72 = river, otherwise lake.
 */
function waterRoll(seed: string): number {
  const r = rand(seed, 'water', 'kind');
  const t = archetypeOf(seed).id;
  if (t === 'harbor') return 0.28 + r * 0.72;
  if (t === 'resort') return 0.72 + r * 0.28;
  if (t === 'mill') return 0.28 + r * 0.44;
  return r;
}

export function planWater(seed: string, g: WaterGeometry): WaterBody | null {
  const R = (...k: (string | number)[]): number => rand(seed, 'water', ...k);
  const { width: W, height: H } = g;
  const roll = waterRoll(seed);
  if (roll < 0.28) return null;
  const finish = (kind: 'river' | 'lake', raw: Uint8Array): WaterBody | null => {
    smooth(raw, W, H);
    const bridges = findBridges(raw, W, H, g.roads);
    const mask = raw.slice();
    for (const r of g.roads) {
      const c = corridor(r);
      for (let y = Math.max(0, c.y); y < Math.min(H, c.y + c.h); y++) for (let x = Math.max(0, c.x); x < Math.min(W, c.x + c.w); x++) mask[y * W + x] = 0;
    }
    return { kind, width: W, height: H, mask, raw, bridges };
  };

  if (roll < 0.72) {
    // ---- river: flows north-south between two neighbouring north-south streets ----
    const vr = g.roads.filter((r) => !r.horizontal).sort((a, b) => a.x - b.x);
    const gaps: { l: number; r: number }[] = [];
    for (let i = 0; i + 1 < vr.length; i++) {
      const l = vr[i]!.x + vr[i]!.w + 2;
      const r = vr[i + 1]!.x - 3;
      if (r - l + 1 >= 22) gaps.push({ l, r });
    }
    if (!gaps.length) return null;
    const gap = gaps[Math.min(gaps.length - 1, Math.floor(R('gap') * gaps.length))]!;
    const mid = (gap.l + gap.r) / 2;
    const amp = Math.min((gap.r - gap.l) / 2 - 4.5, 5 + R('amp') * 4);
    const ph = [R('p1') * 6.28, R('p2') * 6.28, R('p3') * 6.28, R('p4') * 6.28];
    const f1 = 6.28 / (46 + R('f1') * 30);
    const f2 = 6.28 / (19 + R('f2') * 12);
    const f3 = 6.28 / (23 + R('f3') * 20);
    const raw = new Uint8Array(W * H);
    let prev = mid;
    for (let y = 0; y < H; y++) {
      let c = mid + amp * (0.65 * Math.sin(y * f1 + ph[0]!) + 0.35 * Math.sin(y * f2 + ph[1]!));
      c = Math.max(prev - 1.1, Math.min(prev + 1.1, c)); // gentle slope so rows stay connected
      prev = c;
      const wd = 3.2 + 2.8 * (0.5 + 0.5 * Math.sin(y * f3 + ph[2]!)) + 0.6 * Math.sin(y * 0.9 + ph[3]!);
      let a = Math.round(c - wd / 2 + (R('jl', y) - 0.5) * 2.2);
      let b = Math.round(c + wd / 2 + (R('jr', y) - 0.5) * 2.2);
      a = Math.max(gap.l, a);
      b = Math.min(gap.r, Math.max(b, a + 2));
      for (let x = a; x <= b; x++) raw[y * W + x] = 1;
    }
    return finish('river', raw);
  }

  // ---- lake: an organic blob just beyond the edge of the town, clear of every street ----
  // (A pond in the middle of a dense street web would need long bridges; at the edge it is scenery
  // you walk up to, and the town's streets simply stop short of it.)
  let bx0 = Infinity, by0 = Infinity, bx1 = -Infinity, by1 = -Infinity;
  for (const r of g.roads) { bx0 = Math.min(bx0, r.x); by0 = Math.min(by0, r.y); bx1 = Math.max(bx1, r.x + r.w); by1 = Math.max(by1, r.y + r.h); }
  const ccx = (bx0 + bx1) / 2, ccy = (by0 + by1) / 2;
  const clearOfRoads = (cx: number, cy: number, rx: number, ry: number): boolean =>
    !g.roads.some((r) => r.x - 4 < cx + rx * 1.4 && r.x + r.w + 4 > cx - rx * 1.4 && r.y - 4 < cy + ry * 1.4 && r.y + r.h + 4 > cy - ry * 1.4);
  for (let attempt = 0; attempt < 60; attempt++) {
    const rx = 9 + R('rx', attempt) * 5;
    const ry = rx * (0.65 + R('ry', attempt) * 0.3);
    const th = R('th', attempt) * 6.283;
    let cx = 0, cy = 0, found = false;
    for (let t = 10; t < 260; t++) {
      cx = ccx + Math.cos(th) * t;
      cy = ccy + Math.sin(th) * t * 0.8;
      if (cx - rx * 1.5 < 2 || cx + rx * 1.5 > W - 2 || cy - ry * 1.5 < 2 || cy + ry * 1.5 > H - 2) break;
      if (clearOfRoads(cx, cy, rx, ry)) { found = true; break; }
    }
    if (!found) continue;
    const p = [R('lp1', attempt) * 6.28, R('lp2', attempt) * 6.28, R('lp3', attempt) * 6.28];
    const raw = new Uint8Array(W * H);
    for (let y = Math.max(0, Math.floor(cy - ry * 1.5)); y < Math.min(H, Math.ceil(cy + ry * 1.5)); y++)
      for (let x = Math.max(0, Math.floor(cx - rx * 1.5)); x < Math.min(W, Math.ceil(cx + rx * 1.5)); x++) {
        const u = (x + 0.5 - cx) / rx, v = (y + 0.5 - cy) / ry;
        const a = Math.atan2(v, u);
        const lim = 1 + 0.16 * Math.sin(2 * a + p[0]!) + 0.12 * Math.sin(3 * a + p[1]!) + 0.07 * Math.sin(5 * a + p[2]!);
        if (Math.hypot(u, v) < lim) raw[y * W + x] = 1;
      }
    smooth(raw, W, H);
    if (findBridges(raw, W, H, g.roads).length) continue;
    return finish('lake', raw);
  }
  return null;
}

/** The same water body on a canvas that was cropped/shifted: new size W x H, old (x,y) -> (x+dx, y+dy). */
export function shiftWater(w: WaterBody | null, dx: number, dy: number, W: number, H: number): WaterBody | null {
  if (!w) return null;
  const move = (src: Uint8Array): Uint8Array => {
    const out = new Uint8Array(W * H);
    for (let y = 0; y < w.height; y++)
      for (let x = 0; x < w.width; x++) {
        if (!src[y * w.width + x]) continue;
        const nx = x + dx, ny = y + dy;
        if (nx >= 0 && ny >= 0 && nx < W && ny < H) out[ny * W + nx] = 1;
      }
    return out;
  };
  return { ...w, width: W, height: H, mask: move(w.mask), raw: move(w.raw), bridges: w.bridges.map((b) => ({ ...b, x: b.x + dx, y: b.y + dy })) };
}
