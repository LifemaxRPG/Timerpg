import { rand } from '../sim/rng';

// Civic LANDMARKS: big one-off buildings (post office, school, fire station...). Which ones a
// town has, where they stand and the year each opens are all pure functions of the seed.
// `plan.ts` calls placeLandmarks() and reserves one plot per landmark; `layout.ts` decides which
// are open in a given year.

interface Box { x: number; y: number; w: number; h: number }

export type LandmarkKind = 'post' | 'school' | 'fire' | 'police' | 'hospital' | 'hotel' | 'clocktower';

interface Spec {
  name: string;
  blurb: string;
  /** Sprite size in tiles (mirrors the PNG sizes extracted by scripts/extract-assets.mjs). */
  w: number;
  h: number;
  /** Rows (from the bottom) that block the player; the rest is roof you can walk behind. */
  foot: number;
  /** Chance a town has this landmark at all. */
  chance: number;
  /** Opens in a year drawn from [lo, hi]. */
  open: [number, number];
}

export const LANDMARK_SPECS: Record<LandmarkKind, Spec> = {
  post: { name: 'Post Office', blurb: 'Letters, parcels and gossip.', w: 8, h: 13, foot: 6, chance: 0.92, open: [1950, 1962] },
  school: { name: 'Town School', blurb: 'The town\'s children learn here.', w: 24, h: 23, foot: 9, chance: 0.9, open: [1950, 1968] },
  fire: { name: 'Fire Station', blurb: 'Volunteers and a very red truck.', w: 16, h: 15, foot: 7, chance: 0.8, open: [1952, 1978] },
  police: { name: 'Police Station', blurb: 'Keeps the peace, mostly.', w: 21, h: 18, foot: 8, chance: 0.8, open: [1950, 1985] },
  hospital: { name: 'Hospital', blurb: 'Doctors, nurses and a rooftop helipad.', w: 27, h: 21, foot: 9, chance: 0.7, open: [1965, 2002] },
  hotel: { name: 'Grand Hotel', blurb: 'Rooms for travelers passing through.', w: 17, h: 23, foot: 23, chance: 0.6, open: [1955, 1998] },
  clocktower: { name: 'Clock Tower', blurb: 'Everyone sets their watch by it.', w: 8, h: 15, foot: 4, chance: 0.6, open: [1950, 1990] },
};
export const LANDMARK_KINDS = Object.keys(LANDMARK_SPECS) as LandmarkKind[];

/** Sprite variants available per kind (see the manifest group `landmark:<kind>`). */
export const LANDMARK_VARIANTS: Record<LandmarkKind, number> = { post: 4, school: 1, fire: 1, police: 3, hospital: 1, hotel: 1, clocktower: 2 };

export interface LandmarkPlot extends Box {
  id: LandmarkKind;
  name: string;
  blurb: string;
  openYear: number;
  /** 0..1 stable value for choosing a sprite variant. */
  look: number;
  /** Rows (from the bottom) that block walking. */
  foot: number;
}

interface Params {
  marginL: number;
  marginR: number;
  top: number;
  width: number;
  height: number;
  roads: readonly (Box & { horizontal: boolean; main: boolean })[];
  plaza: Box;
  center: { x: number; y: number };
  /** Plots (any shape with kind) landmarks may replace; the rest of the town must keep its room. */
  plots: readonly (Box & { kind: string })[];
  /** Minimum plots to keep per kind group: [kinds, count]. */
  keep: readonly [readonly string[], number][];
  /** Extra obstacle test (e.g. water): spots where this returns true are skipped. */
  blocked?: (box: Box) => boolean;
  /** Landmarks that find no spot beside a street are dropped when false (default: park them on a civic strip). */
  civicStrip?: boolean;
}

export interface LandmarkResult {
  landmarks: LandmarkPlot[];
  /** Plots that survive (landmarks replaced the ones they cover). */
  plots: Params['plots'];
  /** Extra street (a civic strip below the town) when something did not fit between the blocks. */
  roads: (Box & { horizontal: true; main: false })[];
  height: number;
}

const hit = (a: Box, b: Box, pad = 0): boolean =>
  a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;

/** Which landmarks this town has (always at least three), in a stable order. */
export function chooseLandmarks(seed: string): LandmarkKind[] {
  const roll = (k: LandmarkKind): number => rand(seed, 'landmark', 'has', k) / LANDMARK_SPECS[k].chance;
  const sorted = [...LANDMARK_KINDS].sort((a, b) => roll(a) - roll(b));
  return sorted.filter((k, i) => i < 3 || roll(k) < 1).sort((a, b) => LANDMARK_KINDS.indexOf(a) - LANDMARK_KINDS.indexOf(b));
}

export function placeLandmarks(seed: string, p: Params): LandmarkResult {
  const kinds = chooseLandmarks(seed).sort((a, b) => LANDMARK_SPECS[b].w * LANDMARK_SPECS[b].h - LANDMARK_SPECS[a].w * LANDMARK_SPECS[a].h);
  const done: LandmarkPlot[] = [];
  const unplaced: LandmarkKind[] = [];
  let plots = p.plots;
  const make = (k: LandmarkKind, x: number, y: number): LandmarkPlot => {
    const s = LANDMARK_SPECS[k];
    return {
      id: k, name: s.name, blurb: s.blurb, x, y, w: s.w, h: s.h, foot: s.foot,
      openYear: s.open[0] + Math.floor(rand(seed, 'landmark', 'year', k) * (s.open[1] - s.open[0] + 1)),
      look: rand(seed, 'landmark', 'look', k),
    };
  };
  const enough = (left: readonly (Box & { kind: string })[]): boolean =>
    p.keep.every(([ks, n]) => left.filter((c) => ks.includes(c.kind)).length >= n);

  for (const k of kinds) {
    const s = LANDMARK_SPECS[k];
    let best: { x: number; y: number; score: number; left: typeof plots } | undefined;
    // Front door faces a street: the building sits just north of an east-west road's sidewalk.
    // Side streets only: the main street's frontage is for shops.
    for (const r of p.roads.filter((r) => r.horizontal && !r.main)) {
      const y = r.y - 1 - s.h;
      if (y < p.top) continue;
      for (let x = Math.max(p.marginL + 2, r.x); x + s.w <= Math.min(p.width - p.marginR - 2, r.x + r.w); x++) {
        const box = { x, y, w: s.w, h: s.h };
        if (p.roads.some((o) => hit(box, o, 1)) || hit(box, p.plaza, 1) || done.some((d) => hit(box, d, 1)) || p.blocked?.(box)) continue;
        const score = Math.hypot(x + s.w / 2 - p.center.x, (y + s.h / 2 - p.center.y) * 1.3)
          + rand(seed, 'landmark', 'jit', k, x, y) * 10 ;
        if (best && score >= best.score) continue;
        const left = plots.filter((c) => !hit(box, c));
        if (enough(left)) best = { x, y, score, left };
      }
    }
    if (best) {
      done.push(make(k, best.x, best.y));
      plots = best.left;
    } else unplaced.push(k);
  }

  // Whatever did not fit between the blocks gets a civic strip with its own street below the town.
  const roads: LandmarkResult['roads'] = [];
  let height = p.height;
  if (unplaced.length && p.civicStrip !== false) {
    const tallest = Math.max(...unplaced.map((k) => LANDMARK_SPECS[k].h));
    const roadY = p.height + tallest + 2;
    roads.push({ x: p.marginL - 3, y: roadY, w: p.width - p.marginL - p.marginR + 6, h: 2, main: false, horizontal: true });
    let x = p.marginL + 2;
    for (const k of unplaced) {
      const s = LANDMARK_SPECS[k];
      done.push(make(k, x, roadY - 1 - s.h));
      x += s.w + 2;
    }
    height = roadY + 5;
  }
  done.sort((a, b) => LANDMARK_KINDS.indexOf(a.id) - LANDMARK_KINDS.indexOf(b.id));
  return { landmarks: done, plots, roads, height };
}

/** Footprint that blocks the player (the lower part of the sprite). */
export const landmarkFootprint = (l: Box & { foot: number }): Box => {
  const f = Math.min(l.h, l.foot);
  return { x: l.x, y: l.y + l.h - f, w: l.w, h: f };
};
