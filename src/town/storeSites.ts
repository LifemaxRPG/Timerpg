import { rand } from '../sim/rng';
import type { Grid } from './grid';
import type { Rect, Road } from './kinds';
import { LANDMARK_SPECS, type LandmarkPlot } from './landmarks';
import { generalStoreBuilt, STORE_SIZES, storeNeeds, type StoreSite } from './stores';

// Where the plan puts the STORES (src/town/stores.ts): the general store and the market go in downtown with the
// landmarks, before the building plots are laid (so the plots make room for them); the supermarket and the superstore go
// in after, at the town's edge: beyond the east end of the main street, which runs on out of town past them (the plan
// grows its canvas east and south for them).

interface Box { x: number; y: number; w: number; h: number }
const hit = (a: Box, b: Box, pad = 0): boolean =>
  a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;

/** Extra canvas (tiles, east and south) a town plans on for its stores at the edge, so they and their road fit. */
export const EDGE_CANVAS = { supermarket: { right: 80, bottom: 50 }, superstore: { right: 230, bottom: 110 } };

const plot = (seed: string, site: StoreSite, b: Box, openYear: number): LandmarkPlot => ({
  id: site, name: LANDMARK_SPECS[site].name, blurb: LANDMARK_SPECS[site].blurb, ...b, foot: b.h, openYear,
  look: rand(seed, 'landmark', 'look', site),
});

interface DowntownInput {
  grid: Grid;
  roads: readonly Road[];
  plaza: Rect;
  /** The streets' extent: the downtown stores keep inside it (the town does not shift for them). */
  ext: { x0: number; y0: number; x1: number; y1: number };
}

/**
 * The general store (every town: on the main street, as near the plaza as it fits) and the supermarket (towns that
 * reach MARKET_POPULATION: on the main road if it can, else a side street, near the centre), as landmark plots.
 * [grid] has the roads, water, plaza and landmarks marked; a site that finds no room is left out.
 */
export function placeDowntownStores(seed: string, p: DowntownInput): LandmarkPlot[] {
  const needs = storeNeeds(seed);
  const out: LandmarkPlot[] = [];
  const pcx = p.plaza.x + p.plaza.w / 2, pcy = p.plaza.y + p.plaza.h / 2;
  const sites: StoreSite[] = needs.market !== undefined ? ['generalstore', 'market'] : ['generalstore'];
  for (const site of sites) {
    let best: (Box & { score: number }) | undefined;
    for (const [si, [w, h]] of STORE_SIZES[site].entries()) {
      for (const r of p.roads) {
        if (!r.horizontal || (site === 'generalstore' && !r.main)) continue;
        for (let x = r.x; x + w <= r.x + r.w; x++) {
          for (const y of [r.y - 1 - h, r.y + r.h + 1]) {
            const box = { x, y, w, h };
            if (x < p.ext.x0 || y < p.ext.y0 || x + w > p.ext.x1 || y + h > p.ext.y1) continue;
            if (!p.grid.free(box, 2) || out.some((o) => hit(box, o, 2))) continue;
            // the general store as near the plaza as it fits; the market a little way along, on the main street if it can
            const d = Math.hypot(x + w / 2 - pcx, (y + h / 2 - pcy) * 0.5);
            const score = site === 'generalstore'
              ? d + si * 20 + rand(seed, 'store', 'jit', site, x, y) * 4
              : Math.abs(d - 30) * 0.6 + (r.main ? 0 : 30) + si * 25 + rand(seed, 'store', 'jit', site, x, y) * 6;
            if (!best || score < best.score) best = { ...box, score };
          }
        }
      }
    }
    if (!best) continue;
    const { score: _, ...b } = best;
    out.push(plot(seed, site, b, site === 'generalstore' ? generalStoreBuilt(seed) : needs.market!));
    p.grid.mark(b, 1);
  }
  return out;
}

interface EdgeInput {
  width: number;
  height: number;
  roads: readonly Road[];
  /** Everything else on the plan that must stay: landmarks, the plaza. */
  taken: readonly Box[];
  /** The building plots: the stores keep clear of them, the road out of town may take a few (at a T where the main street ends). */
  lots: readonly (Box & { id: string })[];
  /** Water (the raw mask, width x height), if any. */
  water?: Uint8Array;
}

/**
 * The supermarket (from the 1970s) and the superstore (from 1990), beside the main street where it runs on out of town
 * past the east end of the town: the supermarket nearest town, the superstore across the road from it or beyond, each
 * south of the road or north, clear of water. Returns the plots and the main street's last segment run on past them
 * (it replaces road [index]) with the ids of the plots it runs through ([dropped]: a main street ending at a T runs on
 * across the side street) and the side streets it crosses ([split]: each to be cut in two at the junction); a site with
 * no room is left out.
 */
export function placeEdgeStores(seed: string, p: EdgeInput): { plots: LandmarkPlot[]; road?: Road; index: number; dropped: string[]; split: number[] } {
  const needs = storeNeeds(seed);
  const mains = p.roads.map((r, i) => [r, i] as const).filter(([r]) => r.main && r.horizontal);
  const plots: LandmarkPlot[] = [];
  if (!mains.length) return { plots, index: -1, dropped: [], split: [] };
  const [end, index] = mains.reduce((a, b) => (b[0].x + b[0].w > a[0].x + a[0].w ? b : a));
  const wet = (b: Box): boolean => {
    if (!p.water) return false;
    for (let y = Math.max(0, b.y - 2); y < Math.min(p.height, b.y + b.h + 2); y++)
      for (let x = Math.max(0, b.x - 2); x < Math.min(p.width, b.x + b.w + 2); x++) if (p.water[y * p.width + x]) return true;
    return false;
  };
  /** A north-south street [r] runs right across the road [ext] (so the junction splits it). */
  const crosses = (r: Road, ext: Box): boolean => r.y < ext.y - 1 && r.y + r.h > ext.y + ext.h + 1;
  let reach = end.x + end.w;
  const todo: [StoreSite, number | undefined][] = [['supermarket', needs.supermarket], ['superstore', needs.superstore]];
  for (const [site, year] of todo) {
    if (year === undefined) continue;
    const [[w, h]] = STORE_SIZES[site] as [[number, number]];
    const sides = rand(seed, 'store', site, 'side') < 0.7 ? ['S', 'N'] : ['N', 'S'];
    let found: Box | undefined;
    for (let gap = 4; gap <= 160 && !found; gap += 2) {
      for (const side of sides) {
        const x = end.x + end.w + gap;
        const y = side === 'S' ? end.y + end.h + 1 : end.y - 1 - h;
        const box = { x, y, w, h };
        const ext = { x: end.x + end.w, y: end.y, w: Math.max(reach, x + w + 6) - end.x - end.w, h: end.h };
        if (x + w + 8 > p.width - 2 || y < 4 || y + h > p.height - 4) continue;
        if ([...p.taken, ...plots].some((t) => hit(box, t, 1) || hit(ext, t, 1)) || p.lots.some((t) => hit(box, t, 1))) continue;
        // (the road out of town may cross a side street, which is split for the junction, but not run into one)
        if (p.roads.some((r) => r !== end && (hit(box, r, 1) || (r.horizontal ? hit(ext, r, 1) : hit(ext, r) && !crosses(r, ext)))) || wet(box) || wet(ext)) continue;
        found = box;
        break;
      }
    }
    if (!found) continue;
    plots.push(plot(seed, site, found, year));
    reach = Math.max(reach, found.x + found.w + 6);
  }
  if (!plots.length) return { plots, index, dropped: [], split: [] };
  const road = { ...end, w: reach - end.x };
  const ext = { x: end.x + end.w, y: end.y, w: reach - end.x - end.w, h: end.h };
  const split = p.roads.map((r, i) => [r, i] as const).filter(([r]) => !r.horizontal && hit(ext, r)).map(([, i]) => i);
  return { plots, road, index, dropped: p.lots.filter((l) => hit(ext, l, 1)).map((l) => l.id), split };
}
