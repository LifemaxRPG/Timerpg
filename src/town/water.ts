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

export function planWater(seed: string, g: WaterGeometry): WaterBody | null {
  const R = (...k: (string | number)[]): number => rand(seed, 'water', ...k);
  const { width: W, height: H } = g;
  const roll = R('kind');
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

  // ---- lake: an organic blob that sits on at least one street (so there is a bridge) ----
  const main = g.roads.find((r) => r.main);
  for (let attempt = 0; attempt < 40; attempt++) {
    const rx = 10 + R('rx', attempt) * 4;
    const ry = rx * (0.62 + R('ry', attempt) * 0.3);
    // Sits between two north-south streets so it only ever interrupts one east-west street.
    const vr = g.roads.filter((r) => !r.horizontal).sort((a, b) => a.x - b.x);
    const gi = Math.floor(R('gi', attempt) * Math.max(1, vr.length - 1));
    if (gi + 1 >= vr.length) return null;
    const lo = vr[gi]!.x + vr[gi]!.w + 3 + rx * 1.3;
    const hi = vr[gi + 1]!.x - 4 - rx * 1.3;
    if (hi < lo) continue;
    const cx = lo + R('cx', attempt) * (hi - lo);
    // Centered near a random east-west street (not the main one) so the lake really crosses it.
    const streets = g.roads.filter((r) => r.horizontal && !r.main && r.y > g.top + 14 && r.y < H - 16);
    if (!streets.length) return null;
    const st = streets[Math.floor(R('st', attempt) * streets.length)]!;
    const cy = st.y + 1 + (R('cy', attempt) - 0.5) * ry * 0.9;
    const p = [R('lp1', attempt) * 6.28, R('lp2', attempt) * 6.28, R('lp3', attempt) * 6.28];
    const raw = new Uint8Array(W * H);
    for (let y = Math.max(0, Math.floor(cy - ry * 1.5)); y < Math.min(H, Math.ceil(cy + ry * 1.5)); y++)
      for (let x = Math.max(0, Math.floor(cx - rx * 1.5)); x < Math.min(W, Math.ceil(cx + rx * 1.5)); x++) {
        const u = (x + 0.5 - cx) / rx, v = (y + 0.5 - cy) / ry;
        const th = Math.atan2(v, u);
        const lim = 1 + 0.16 * Math.sin(2 * th + p[0]!) + 0.12 * Math.sin(3 * th + p[1]!) + 0.07 * Math.sin(5 * th + p[2]!);
        if (Math.hypot(u, v) < lim) raw[y * W + x] = 1;
      }
    smooth(raw, W, H);
    const bridges = findBridges(raw, W, H, g.roads);
    const touchesMain = main && bridges.some((b) => b.horizontal && b.y === main.y - 1);
    if (bridges.length !== 1 || !bridges[0]!.horizontal || touchesMain) continue;
    // Every deck must carry a street that really goes through the water (no streets that merely brush it).
    const wet = (b: Bridge): number => {
      let n = 0;
      for (let y = b.y; y < b.y + b.h; y++) for (let x = b.x; x < b.x + b.w; x++) if (raw[y * W + x]) n++;
      return n;
    };
    if (bridges.some((b) => wet(b) < 6)) continue;
    if (bridges.some((b) => (b.horizontal ? b.w : b.h) > 26)) continue;
    return finish('lake', raw);
  }
  return null;
}
