import { rand } from '../sim/rng';
import type { Rect, Road } from '../town/kinds';
import type { TownPlan } from '../town/plan';
import { townFounded, townName } from './names';

// THE WORLD MAP. Towns sit one per cell of a (jittered) grid that extends forever in every
// direction. A town's seed comes from its cell, so the same world seed always has the same towns.
// Cells are spaced so that even the biggest town plans never touch (checked by tests).

/** Cell size in tiles. Towns reach at most ~150 tiles from their center sideways and ~120 up/down. */
export const CELL_W = 360;
export const CELL_H = 320;
const JITTER = 20;

export interface TownSite {
  id: string;
  /** Seed of the town itself (plan + history). */
  seed: string;
  cx: number;
  cy: number;
  name: string;
  founded: number;
  /** Where the town's center (plan.center) stands, in world tiles. */
  center: { x: number; y: number };
}

export function siteAt(worldSeed: string, cx: number, cy: number): TownSite {
  const seed = `${worldSeed}/${cx},${cy}`;
  const jx = Math.round((rand(worldSeed, 'site-x', cx, cy) - 0.5) * 2 * JITTER);
  const jy = Math.round((rand(worldSeed, 'site-y', cx, cy) - 0.5) * 2 * JITTER);
  return { id: `${cx},${cy}`, seed, cx, cy, name: townName(seed), founded: townFounded(seed), center: { x: cx * CELL_W + jx, y: cy * CELL_H + jy } };
}

/** All town sites whose cell is within `radius` cells of the cell containing world tile (tx,ty). */
export function sitesNear(worldSeed: string, tx: number, ty: number, radius: number): TownSite[] {
  const cx0 = Math.round(tx / CELL_W), cy0 = Math.round(ty / CELL_H);
  const out: TownSite[] = [];
  for (let cy = cy0 - radius; cy <= cy0 + radius; cy++) for (let cx = cx0 - radius; cx <= cx0 + radius; cx++) out.push(siteAt(worldSeed, cx, cy));
  return out;
}

/** World tile of the plan's top-left corner, so the plan's center lands on the site's center. */
export const originOf = (site: TownSite, plan: Pick<TownPlan, 'center'>): { x: number; y: number } => ({
  x: site.center.x - plan.center.x,
  y: site.center.y - plan.center.y,
});

/** A town's plan rectangle in world tiles. */
export const mapRectOf = (site: TownSite, plan: Pick<TownPlan, 'center' | 'width' | 'height'>): Rect => {
  const o = originOf(site, plan);
  return { x: o.x, y: o.y, w: plan.width, h: plan.height };
};

/** Where the main street leaves the town to the west and to the east, in world tiles. */
export function mainStreetEnds(site: TownSite, plan: TownPlan): { west: { x: number; y: number }; east: { x: number; y: number } } {
  const o = originOf(site, plan);
  const mains = plan.roads.filter((r) => r.main && r.horizontal).sort((a, b) => a.x - b.x);
  const w = mains[0]!, e = mains[mains.length - 1]!;
  return { west: { x: o.x + w.x, y: o.y + w.y }, east: { x: o.x + e.x + e.w, y: o.y + e.y } };
}

/**
 * The road joining a town to its eastern neighbour: out of the east end of this town's main street, a
 * jog if the two main streets are at different heights, and into the west end of the neighbour's.
 * All rectangles in world tiles; 2 tiles wide like every street.
 */
export function linkRoad(a: { site: TownSite; plan: TownPlan }, b: { site: TownSite; plan: TownPlan }): Road[] {
  const from = mainStreetEnds(a.site, a.plan).east;
  const to = mainStreetEnds(b.site, b.plan).west;
  const x0 = from.x - 2, x1 = to.x + 2;
  if (from.y === to.y) return [{ x: x0, y: from.y, w: x1 - x0, h: 2, main: true, horizontal: true }];
  const mid = Math.round((x0 + x1) / 2);
  return [
    { x: x0, y: from.y, w: mid - x0 + 2, h: 2, main: true, horizontal: true },
    { x: mid, y: Math.min(from.y, to.y), w: 2, h: Math.abs(to.y - from.y) + 2, main: true, horizontal: false },
    { x: mid, y: to.y, w: x1 - mid, h: 2, main: true, horizontal: true },
  ];
}
