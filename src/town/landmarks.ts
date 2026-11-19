import { rand } from '../sim/rng';
import { pickVariant } from '../game/art';
import { hasCinema, type CinemaEra } from './cinema';
import { isStoreSite, type StoreState, type StoreVariant } from './stores';

// Civic LANDMARKS: big one-off buildings (post office, school, fire station...). Which ones a
// town has, where they stand and the year each opens are all pure functions of the seed.
// `plan.ts` calls placeLandmarks() and reserves one plot per landmark; `layout.ts` decides which
// are open in a given year.

interface Box { x: number; y: number; w: number; h: number }

export type LandmarkKind = 'post' | 'school' | 'fire' | 'police' | 'hospital' | 'hotel' | 'clocktower' | 'cinema'
  | 'generalstore' | 'market' | 'supermarket' | 'superstore';

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
  // A full-size cinema: a lobby front on the street and the auditorium hall behind it, ~24 m wide and 36 m deep. Only
  // towns that grow big enough have one (src/town/cinema.ts hasCinema), and it stands on or just off the main street.
  // No LimeZu sprite: the 3D building is made in the engine, its front by era (landmark_cinema_<era>).
  cinema: { name: 'Cinema', blurb: 'Pictures every night, and a matinee on Saturdays.', w: 24, h: 36, foot: 36, chance: 1, open: [1936, 1949] },
  // The stores (src/town/stores.ts, placed by src/town/storeSites.ts, not chooseLandmarks): sizes and years come from
  // there (STORE_SIZES, storeHistories); no LimeZu sprite, the engine builds each era's building (landmark_store_<variant>).
  generalstore: { name: 'General Store', blurb: 'Groceries, hardware, feed and gossip under one roof.', w: 12, h: 15, foot: 15, chance: 1, open: [1885, 1935] },
  market: { name: 'Market', blurb: 'The town\'s first supermarket: plate glass, bright lights and a little lot.', w: 22, h: 30, foot: 30, chance: 1, open: [1950, 1969] },
  supermarket: { name: 'Supermarket', blurb: 'Long aisles under a flat roof, and a car park out front.', w: 52, h: 40, foot: 40, chance: 1, open: [1970, 2025] },
  superstore: { name: 'Superstore', blurb: 'Everything under one enormous roof, at the edge of town.', w: 128, h: 98, foot: 98, chance: 1, open: [1990, 2025] },
};
export const LANDMARK_KINDS = Object.keys(LANDMARK_SPECS) as LandmarkKind[];
/** The cinema's plots, best first (width x depth in tiles): the full 24 x 36 hall, then wider and shallower ones (the hall turned to run along the street behind the lobby). */
export const CINEMA_SIZES: readonly [number, number][] = [[24, 36], [26, 32], [30, 28], [34, 24], [40, 20], [44, 18]];

const STORE_DEFAULT: Record<'generalstore' | 'market' | 'supermarket' | 'superstore', StoreVariant> = { generalstore: 'general', market: 'market', supermarket: 'supermarket', superstore: 'superstore' };

/** Sprite variants available per kind (see the manifest group `landmark:<kind>`). */
export const LANDMARK_VARIANTS: Record<LandmarkKind, number> = { post: 4, school: 1, fire: 1, police: 3, hospital: 1, hotel: 1, clocktower: 2, cinema: 4, generalstore: 1, market: 1, supermarket: 1, superstore: 1 };

export interface LandmarkPlot extends Box {
  id: LandmarkKind;
  name: string;
  blurb: string;
  openYear: number;
  /** 0..1 stable value for choosing a sprite variant. */
  look: number;
  /** Rows (from the bottom) that block walking. */
  foot: number;
  /** The cinema's front in the layout's year (layout.ts sets it; the plan's cinema has none). */
  era?: CinemaEra;
  /** A store's state in the layout's year (layout.ts sets it: src/town/stores.ts storeState). */
  store?: StoreState;
}

/**
 * The sprite a landmark wears: `landmark_<kind>_<n>` (scripts/extract-assets.mjs), chosen from its manifest group
 * `landmark:<kind>` by its look exactly as the game draws it (src/game/TownView.ts drawLandmark, pickVariant).
 */
export function landmarkArtKey(l: Pick<LandmarkPlot, 'id' | 'look'> & { era?: CinemaEra; store?: { variant: StoreVariant } }): string {
  // the cinema has no sprite variants: its front is the era's (built in the engine)
  if (l.id === 'cinema') return `landmark_cinema_${l.era ?? 'palace'}`;
  // nor do the stores: the building of the year (the supermarket site's market or bigger store)
  if (isStoreSite(l.id)) return `landmark_store_${l.store?.variant ?? STORE_DEFAULT[l.id]}`;
  const variants = Array.from({ length: LANDMARK_VARIANTS[l.id] }, (_, i) => i + 1);
  return `landmark_${l.id}_${pickVariant(variants, l.look)}`;
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

/** Which landmarks this town has (always at least three civic ones, and a cinema if it grows big enough), in a stable order. */
export function chooseLandmarks(seed: string): LandmarkKind[] {
  const roll = (k: LandmarkKind): number => rand(seed, 'landmark', 'has', k) / LANDMARK_SPECS[k].chance;
  const sorted = LANDMARK_KINDS.filter((k) => k !== 'cinema' && !isStoreSite(k)).sort((a, b) => roll(a) - roll(b));
  const civic = sorted.filter((k, i) => i < 3 || roll(k) < 1).sort((a, b) => LANDMARK_KINDS.indexOf(a) - LANDMARK_KINDS.indexOf(b));
  return hasCinema(seed) ? [...civic, 'cinema'] : civic;
}

export function placeLandmarks(seed: string, p: Params): LandmarkResult {
  // Biggest first; the cinema last of all, so the civic landmarks keep the places they had before towns had cinemas.
  const kinds = chooseLandmarks(seed).sort((a, b) => Number(a === 'cinema') - Number(b === 'cinema')
    || LANDMARK_SPECS[b].w * LANDMARK_SPECS[b].h - LANDMARK_SPECS[a].w * LANDMARK_SPECS[a].h);
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
  /** The streets' extent (and the plaza's): the cinema keeps inside it. */
  const ext = {
    x0: Math.min(...p.roads.map((r) => r.x)), y0: Math.min(...p.roads.map((r) => r.y), p.plaza.y),
    x1: Math.max(...p.roads.map((r) => r.x + r.w)), y1: Math.max(...p.roads.map((r) => r.y + r.h), p.plaza.y + p.plaza.h),
  };
  /** The road a box's front (its bottom edge, else its top) is on. */
  const r0 = (b: Box): Params['roads'][number] | undefined =>
    p.roads.find((r) => r.horizontal && b.x < r.x + r.w && r.x < b.x + b.w && (r.y === b.y + b.h + 1 || r.y + r.h + 1 === b.y));
  const enough = (left: readonly (Box & { kind: string })[]): boolean =>
    p.keep.every(([ks, n]) => left.filter((c) => ks.includes(c.kind)).length >= n);

  for (const k of kinds) {
    const spec = LANDMARK_SPECS[k];
    let best: { x: number; y: number; w: number; h: number; score: number; left: typeof plots } | undefined;
    // The cinema is the exception: it belongs downtown, on the main street or just off it, and where no block is deep
    // enough for its hall it takes a wider, shallower plot (CINEMA_SIZES) rather than none.
    const cinema = k === 'cinema';
    for (const [si, s] of (cinema ? CINEMA_SIZES.map(([w, h]) => ({ w, h })) : [spec]).entries()) {
      // The building stands beside a side street (never the main street, whose frontage is for shops):
      // on either side of an east-west street, or either side of a north-south one.
      const spots: { x: number; y: number }[] = [];
      for (const r of p.roads.filter((r) => cinema || !r.main)) {
        if (r.horizontal) {
          for (let x = r.x; x + s.w <= r.x + r.w; x++) {
            spots.push({ x, y: r.y - 1 - s.h }, { x, y: r.y + r.h + 1 });
          }
        } else {
          for (let y = r.y; y + s.h <= r.y + r.h; y++) {
            spots.push({ x: r.x - 1 - s.w, y }, { x: r.x + r.w + 1, y });
          }
        }
      }
      for (const { x, y } of spots) {
        if (y < p.top || x < p.marginL + 2 || x + s.w > p.width - p.marginR - 2 || y + s.h > p.height - p.top) continue;
        // the cinema keeps inside the streets' extent, so the town does not grow (or shift) for it
        if (cinema && (x < ext.x0 || y < ext.y0 || x + s.w > ext.x1 || y + s.h > ext.y1)) continue;
        const box = { x, y, w: s.w, h: s.h };
        // Keep clear of the main street's frontage (a 13-deep shop plus its sidewalk): that is downtown.
        if (p.roads.some((o) => hit(box, o, o.main && !cinema ? 15 : 1)) || hit(box, p.plaza, 1) || done.some((d) => hit(box, d, 1)) || p.blocked?.(box)) continue;
        const score = cinema
          // the cinema: as near the plaza as it fits, its front on the main street if it can be
          ? Math.hypot(x + s.w / 2 - (p.plaza.x + p.plaza.w / 2), (y + s.h / 2 - (p.plaza.y + p.plaza.h / 2)) * 0.5) - (r0(box)?.main ? 30 : 0) + si * 40 + rand(seed, 'landmark', 'jit', k, x, y) * 6
          : Math.hypot(x + s.w / 2 - p.center.x, (y + s.h / 2 - p.center.y) * 1.3) + rand(seed, 'landmark', 'jit', k, x, y) * 10;
        if (best && score >= best.score) continue;
        const left = plots.filter((c) => !hit(box, c));
        if (enough(left)) best = { x, y, w: s.w, h: s.h, score, left };
      }
    }
    if (best) {
      done.push({ ...make(k, best.x, best.y), w: best.w, h: best.h, ...(cinema ? { foot: best.h } : {}) });
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
