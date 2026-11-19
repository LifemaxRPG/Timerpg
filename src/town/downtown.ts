import { rand } from '../sim/rng';
import { firstYearReaching, populationIn, POPULATION_BY } from './population';
import { Grid } from './grid';
import { hitsWater } from './water';
import type { Rect } from './kinds';
import type { Candidate, TownPlan } from './plan';

// The DOWNTOWN through the decades: how tall the town's centre stands, from its population (src/town/population.ts) and
// the year, a pure function of the seed like the stores (src/town/stores.ts). The game's plan already has the main
// street's shop plots (7 x 13 tiles, the shops' fronts on the street); this decides what stands on and behind them:
//  - every shop plot carries a MAIN STREET BLOCK built 1885-1935 (2-4 storeys, a shop at street level, older and taller
//    nearer the centre); a block at a corner of the main street may be a red-brick corner block;
//  - behind the shops (each plot is 13 tiles deep, the shop's building about 2), the back lots of two neighbouring plots
//    make a TOWER SITE: the 1960s-70s urban renewal clears some for SURFACE PARKING, and as the town grows past
//    MIDRISE_POPULATION it builds 7-11 storey offices and hotels on them (1958 on), past HIGHRISE_POPULATION 13-19
//    storey glass towers (1982 on), which may replace an early mid-rise (demolished, a year of parking between);
//  - the CIVIC buildings of the old town: a Victorian town hall with its clock tower at the back of the plaza (built
//    1880-1910, in most towns) and a chapel (built 1870-1915) on a street near the centre where a plot of woodland is free.
// The 3D town (kotlin-street/tools/town-downtown.ts) builds them from the pack's kits; the classic 2D game does not draw them.

/** The town's people (src/town/population.ts) from which its centre builds mid-rises, and the first year it may. */
export const MIDRISE_POPULATION = 175;
export const MIDRISE_FROM = 1958;
/** ... and glass towers. */
export const HIGHRISE_POPULATION = 235;
export const HIGHRISE_FROM = 1982;
/** Each further mid-rise (high-rise) waits for this many more people. */
const MIDRISE_STEP = 15;
const HIGHRISE_STEP = 20;
const MAX_MIDRISE = 4;
const MAX_HIGHRISE = 4;
/** Tower sites only in the core: plots whose mean rank (tiles from the centre, src/town/plan.ts) is below this. */
export const CORE_RANK = 72;

export type DowntownTier = 'main' | 'midrise' | 'highrise';

/** How tall the town's centre may stand in [year]: its tier and the storeys of its tallest building standing. */
export function downtownTier(plan: TownPlan, year: number): DowntownTier {
  const standing = downtownStanding(plan, year).filter((b) => b.kind !== 'parking');
  if (standing.some((b) => b.tier === 'highrise')) return 'highrise';
  if (standing.some((b) => b.tier === 'midrise')) return 'midrise';
  return 'main';
}

// ------------------------------------------------------------------------------------------------ main street blocks

export interface StreetBlock {
  slot: string;
  /** The year the block was built (1885-1935). */
  built: number;
  /** Storeys over the shop (1-3: a 2-4 storey block). */
  middles: number;
  /** It stands at a corner of the main street (a side street meets the road within two tiles of its end). */
  corner: boolean;
  /** At a corner, a red-brick corner block (the pack's octagonal one) instead of the plain front. */
  cornerBlock: boolean;
  /**
   * On a block face out past the old core (its plots' mean rank CORE_RANK and more: [stripFace]): a post-war stand-alone shop (built 1950-1985), one or two storeys,
   * set back behind its own car park with a way in from the street, not a block at the pavement.
   */
  standalone: boolean;
}

/** The shop plots of the plan, by plot id. */
const shopSlots = (plan: TownPlan): Candidate[] => plan.candidates.filter((c) => c.kind === 'shop');

/** Whether a side street meets the road the plot fronts within two tiles of either end of plot [c]. */
function atCorner(plan: TownPlan, c: Rect): boolean {
  return plan.roads.some((r) => !r.horizontal
    && r.y <= c.y + c.h + 3 && r.y + r.h >= c.y - 3
    && (Math.abs(r.x + r.w - c.x) <= 2 || Math.abs(r.x - (c.x + c.w)) <= 2));
}

/**
 * The run of shop plots [c] is in: those on its side of the street touching it end to end (the same row, plot after plot),
 * a block face. Its frontage is one kind all along: a street wall at the pavement or a strip of shops behind their lots.
 */
export function runOf(plan: TownPlan, c: Candidate): Candidate[] {
  const shops = shopSlots(plan).filter((q) => q.y === c.y && q.h === c.h);
  const run = [c];
  for (let x = c.x; ;) { const q = shops.find((s) => s.x + s.w === x); if (!q) break; run.unshift(q); x = q.x; }
  for (let x = c.x + c.w; ;) { const q = shops.find((s) => s.x === x); if (!q) break; run.push(q); x = q.x + q.w; }
  return run;
}

/** Whether the block face of [c] lies out past the old core (its plots' mean rank): a post-war strip. */
export function stripFace(plan: TownPlan, c: Candidate): boolean {
  // the planned town: by district (a core face is always a street wall, a strip face always car parks in front)
  if (c.district) return c.district !== 'core';
  const run = runOf(plan, c);
  return run.reduce((s, q) => s + q.rank, 0) / run.length >= CORE_RANK;
}

/** The year the street block on shop plot [slot] is built: a core block 1885-1935 (the centre first), a strip's stand-alone shop 1950-85. */
export function streetBlockBuilt(seed: string, slot: string, rank: number, strip: boolean): number {
  const R = (...k: (string | number)[]): number => rand(seed, 'downtown', 'block', slot, ...k);
  return strip ? 1950 + Math.floor(R('postwar') * 36) : Math.min(1935, 1885 + Math.floor(rank / 3) + Math.floor(R('built') * 12));
}

/** The main street block on shop plot [slot] (its whole life: it stands from [StreetBlock.built] on). */
export function streetBlock(plan: TownPlan, slot: string): StreetBlock | undefined {
  const c = plan.candidates.find((q) => q.id === slot && q.kind === 'shop');
  if (!c) return undefined;
  const R = (...k: (string | number)[]): number => rand(plan.seed, 'downtown', 'block', slot, ...k);
  // the centre was built first and tallest: rank ~15 by the plaza, ~100 at the main street's ends
  const built = streetBlockBuilt(plan.seed, slot, c.rank, false);
  const middles = Math.max(1, Math.min(3, 1 + Math.floor(R('middles') * 2) + (c.rank < 45 ? 1 : 0)));
  const corner = atCorner(plan, c);
  if (stripFace(plan, c)) {
    return { slot, built: streetBlockBuilt(plan.seed, slot, c.rank, true), middles: R('middles') < 0.7 ? 0 : 1, corner, cornerBlock: false, standalone: true };
  }
  return { slot, built, middles, corner, cornerBlock: corner && R('corner') < 0.55, standalone: false };
}

// ------------------------------------------------------------------------------------------------ the tower sites

export interface DowntownSite {
  id: string;
  /** The two neighbouring shop plots whose back lots it takes, left to right along the street (map x). */
  slots: [string, string];
  /** The two plots' rectangle (tiles): the tower stands in its back part, behind the shops. */
  rect: Rect;
  /** The plots' mean rank (nearer the centre is lower). */
  rank: number;
}

/** Every pair of neighbouring shop plots on one side of a street (the same row, touching), nearest the centre first. */
export function downtownSites(plan: TownPlan): DowntownSite[] {
  const shops = shopSlots(plan);
  const out: DowntownSite[] = [];
  for (const a of shops) {
    const b = shops.find((q) => q.y === a.y && q.h === a.h && q.x === a.x + a.w);
    if (!b) continue;
    out.push({ id: `site:${a.id}`, slots: [a.id, b.id], rect: { x: a.x, y: a.y, w: a.w + b.w, h: a.h }, rank: (a.rank + b.rank) / 2 });
  }
  return out.sort((p, q) => p.rank - q.rank || (p.id < q.id ? -1 : 1));
}

// ------------------------------------------------------------------------------------------------ what stands there, year by year

export interface DowntownBuilding {
  id: string;
  site: string;
  /** An office block, a hotel, a glass tower, or the surface parking of an urban-renewal clearance. */
  kind: 'office' | 'hotel' | 'tower' | 'parking';
  /** The tier whose growth built it (parking: the tier of the town when it was cleared). */
  tier: DowntownTier;
  built: number;
  /** The year it is gone (demolished, or a parking lot built over); undefined: still standing. */
  demolished?: number;
  /** Storeys, the ground floor included (0 for parking). */
  floors: number;
  /** The pack kit's look: an office style 1-4, or a facade-kit palette. */
  style: string;
  seed: number;
  name: string;
}

const OFFICE_NAMES = ['Commerce Building', 'Federal Savings Building', 'Professional Center', 'Union Trust Building', 'Merchants Tower',
  'County Bank Building', 'Lincoln Plaza', 'Heritage Tower', 'Riverside Center', 'Main Street Plaza', 'Founders Tower', 'Summit Building'];
const HOTEL_NAMES = ['Motor Inn', 'Plaza Hotel', 'Downtowner Hotel', 'Travelers Hotel'];

const cache = new Map<string, DowntownBuilding[]>();

/**
 * Every downtown building the town ever has behind its main street (towers and parking), with the year it was built and
 * the year it went. Needs the plan (src/town/plan.ts); deterministic from the seed.
 */
export function downtownHistory(plan: TownPlan): DowntownBuilding[] {
  const hit = cache.get(plan.seed);
  if (hit) return hit;
  const seed = plan.seed;
  const R = (...k: (string | number)[]): number => rand(seed, 'downtown', ...k);
  const sites = downtownSites(plan);
  const out: DowntownBuilding[] = [];
  const named = new Set<string>();
  /** A name from [list] (by the roll [r]) not given yet. */
  const nameOf = (list: string[], r: number, tower = false): string => {
    for (let k = 0; k < list.length; k++) {
      const n = list[(Math.floor(r * list.length) + k) % list.length]!.replace('Building', tower ? 'Tower' : 'Building');
      if (!named.has(n)) { named.add(n); return n; }
    }
    return list[0]!;
  };
  /** Sites taken (by plot: two sites may share a plot), and when each site's last building went (for reuse). */
  const usedSlot = new Set<string>();
  const free = (s: DowntownSite): boolean => !usedSlot.has(s.slots[0]) && !usedSlot.has(s.slots[1]);
  const take = (s: DowntownSite): void => { usedSlot.add(s.slots[0]); usedSlot.add(s.slots[1]); };
  // the best free site: nearest the centre, a little shuffled
  const pick = (salt: string, from: DowntownSite[] = sites): DowntownSite | undefined =>
    from.filter((s) => free(s) && s.rank < CORE_RANK && !stripFace(plan, shopSlots(plan).find((q) => q.id === s.slots[0])!)).map((s) => ({ s, k: s.rank + R('pick', salt, s.id) * 18 })).sort((a, b) => a.k - b.k)[0]?.s;

  // the mid-rises: one each time the town passes another step of people
  const mids: DowntownBuilding[] = [];
  for (let i = 0; i < MAX_MIDRISE; i++) {
    const y = firstYearReaching(seed, MIDRISE_POPULATION + MIDRISE_STEP * i, MIDRISE_FROM);
    if (y === undefined) break;
    const built = y + 1 + Math.floor(R('mid', i, 'lag') * 3);
    if (built > POPULATION_BY) break;
    const s = pick(`mid${i}`); if (!s) break;
    take(s);
    const hotel = i === 1 || R('mid', i, 'hotel') < 0.2;
    const blocks = 3 + Math.min(2, Math.floor(R('mid', i, 'h') * 2) + (i >= 2 ? 1 : 0));
    const b: DowntownBuilding = {
      id: `dt:mid${i}`, site: s.id, kind: hotel ? 'hotel' : 'office', tier: 'midrise', built,
      floors: hotel ? 5 + blocks : 2 * blocks + 1,
      style: hotel ? ['blue', 'grey', 'brick'][Math.floor(R('mid', i, 'pal') * 3)]! : String(built < 1972 ? 1 : 3),
      seed: Math.floor(R('mid', i, 'seed') * 1e6),
      name: nameOf(hotel ? HOTEL_NAMES : OFFICE_NAMES, R('mid', i, 'name')),
    };
    mids.push(b); out.push(b);
  }
  // the glass towers: the best site left, or (40%) an early mid-rise pulled down for it
  for (let j = 0; j < MAX_HIGHRISE; j++) {
    const y = firstYearReaching(seed, HIGHRISE_POPULATION + HIGHRISE_STEP * j, HIGHRISE_FROM);
    if (y === undefined) break;
    const built = y + 2 + Math.floor(R('high', j, 'lag') * 3);
    if (built > POPULATION_BY) break;
    // (the oldest mid-rise standing goes for it if the core has no free site left, an early office 40% of the time anyway)
    const standing = mids.filter((m) => m.demolished === undefined && m.built < built - 6).sort((a, b) => a.built - b.built);
    const early = standing.find((m) => m.built < 1975 && m.kind === 'office');
    let site: string | undefined;
    const free1 = pick(`high${j}`);
    const old = early && R('high', j, 'replace') < 0.4 ? early : !free1 ? standing[0] : undefined;
    if (old) { old.demolished = built - 2; site = old.site; }
    else if (free1) { take(free1); site = free1.id; }
    if (!site) break;
    const blocks = 6 + Math.min(3, j + Math.floor(R('high', j, 'h') * 2));
    out.push({
      id: `dt:high${j}`, site, kind: 'tower', tier: 'highrise', built, floors: 2 * blocks + 1,
      style: R('high', j, 'style') < 0.6 ? '4' : '2', seed: Math.floor(R('high', j, 'seed') * 1e6),
      name: nameOf(OFFICE_NAMES, R('high', j, 'name'), true),
    });
  }
  // urban renewal: the old back lots and stables cleared for surface parking in 1962-1978, where the town has people enough
  // to drive downtown (a tower site is parking from its clearing until its tower goes up; a pulled-down mid-rise's site
  // is parking for the years between)
  const renewal = (k: string): number => 1962 + Math.floor(R('renewal', k) * 17);
  for (const b of [...out]) {
    const s = sites.find((q) => q.id === b.site)!;
    const cleared = renewal(s.id);
    const prev = out.filter((o) => o.site === b.site && o !== b && o.demolished !== undefined && o.demolished <= b.built);
    const from = prev.length ? Math.max(...prev.map((o) => o.demolished!)) : cleared;
    if (from < b.built && !out.some((o) => o.kind === 'parking' && o.site === b.site && o.built === from)) {
      out.push({ id: `dt:park:${s.id}:${from}`, site: s.id, kind: 'parking', tier: 'main', built: from, demolished: b.built,
        floors: 0, style: 'asphalt', seed: Math.floor(R('park', s.id) * 1e6), name: 'Parking' });
    }
  }
  // and in a town big enough for traffic but not for towers, one or two cleared lots that stay parking
  if (populationIn(seed, 1975) >= 120) {
    const n = 1 + (R('renewal', 'count') < 0.5 ? 1 : 0);
    for (let k = 0; k < n; k++) {
      const s = pick(`park${k}`); if (!s) break;
      take(s);
      out.push({ id: `dt:park${k}`, site: s.id, kind: 'parking', tier: 'main', built: renewal(s.id), floors: 0, style: 'asphalt',
        seed: Math.floor(R('park', s.id) * 1e6), name: 'Parking' });
    }
  }
  out.sort((a, b) => a.built - b.built || (a.id < b.id ? -1 : 1));
  cache.set(seed, out);
  return out;
}

/** The downtown buildings standing in [year] (built by then, not yet gone). */
export function downtownStanding(plan: TownPlan, year: number): DowntownBuilding[] {
  return downtownHistory(plan).filter((b) => b.built <= year && (b.demolished === undefined || year < b.demolished));
}

// ------------------------------------------------------------------------------------------------ the civic buildings

export interface CivicBuilding {
  kind: 'townhall' | 'chapel';
  name: string;
  built: number;
  /** Tiles: the town hall's is the plaza's back (it stands there facing the main street); the chapel's its own plot. */
  rect: Rect;
}

/** The old town hall at the back of the plaza (most towns), built 1880-1910. */
export function townHall(plan: TownPlan): CivicBuilding | undefined {
  const R = (...k: (string | number)[]): number => rand(plan.seed, 'downtown', 'townhall', ...k);
  if (R('has') >= 0.75) return undefined;
  return { kind: 'townhall', name: 'Town Hall', built: 1880 + Math.floor(R('built') * 31), rect: { ...plan.plaza } };
}

/** The chapel's plot: tiles along the street by tiles deep. */
export const CHAPEL_PLOT = { w: 5, h: 9 };
/** The chapel stands no further than this from the town's centre (tiles), or the town has none (yet). */
export const CHAPEL_REACH = 80;

/**
 * The town's chapel (built 1870-1915): on the nearest free stretch of woodland beside a street, where no plot, landmark,
 * plaza or water is, a tile clear of them, its front a tile from the road (as every plot's). Undefined if none is free.
 */
export function chapel(plan: TownPlan): CivicBuilding | undefined {
  const grid = new Grid(plan.width, plan.height);
  for (const r of plan.roads) grid.mark(r, 1);
  for (const c of plan.candidates) grid.mark(c, 1);
  for (const l of plan.landmarks) grid.mark(l, 1);
  grid.mark(plan.plaza, 1);
  const R = (...k: (string | number)[]): number => rand(plan.seed, 'downtown', 'chapel', ...k);
  let best: (Rect & { score: number }) | undefined;
  for (const r of plan.roads) {
    // its front a tile from the road (the pavement), along the road; its plot clear of others by a tile
    const { w, h } = CHAPEL_PLOT;
    const tries: Rect[] = [];
    if (r.horizontal) for (let x = r.x + 2; x + w <= r.x + r.w - 2; x++) tries.push({ x, y: r.y - 1 - h, w, h }, { x, y: r.y + r.h + 1, w, h });
    else for (let y = r.y + 2; y + w <= r.y + r.h - 2; y++) tries.push({ x: r.x - 1 - h, y, w: h, h: w }, { x: r.x + r.w + 1, y, w: h, h: w });
    for (const t of tries) {
      if (!grid.inBounds(t, 3)) continue;
      // (the pavement's own tile is the road's mark: test the plot alone, and a tile round it but on the road's side)
      if (!grid.free(t, 0)) continue;
      const pad = { x: t.x - 1, y: t.y - 1, w: t.w + 2, h: t.h + 2 };
      let clear = true;
      for (let y = pad.y; y < pad.y + pad.h && clear; y++) for (let x = pad.x; x < pad.x + pad.w; x++) {
        if (x >= t.x && x < t.x + t.w && y >= t.y && y < t.y + t.h) continue;
        if (!grid.at(x, y)) continue;
        // a marked tile next to the plot is fine only if it is the road's own pavement band
        const onRoad = plan.roads.some((q) => x >= q.x - 1 && x < q.x + q.w + 1 && y >= q.y - 1 && y < q.y + q.h + 1);
        if (!onRoad) { clear = false; break; }
      }
      if (!clear || hitsWater(plan.water, t, 2)) continue;
      // no other road may cross its plot's pavement band either (a corner)
      // (beside a north-south street, no east-west one within two tiles of its top or foot either: the export fronts a plot
      // on the road below or above it first)
      const py = (q: typeof r): number => (!r.horizontal && q.horizontal ? 3 : 2);
      if (plan.roads.some((q) => q !== r && q.x < t.x + t.w + 2 && t.x - 2 < q.x + q.w && q.y < t.y + t.h + py(q) && t.y - py(q) < q.y + q.h)) continue;
      const d = Math.hypot(t.x + t.w / 2 - plan.center.x, t.y + t.h / 2 - plan.center.y);
      if (d > CHAPEL_REACH) continue;
      const score = d + (r.main ? 0 : 6) + R('jit', t.x, t.y) * 3;
      if (!best || score < best.score) best = { ...t, score };
    }
  }
  if (!best) return undefined;
  const { score: _s, ...rect } = best;
  return { kind: 'chapel', name: 'Grace Chapel', built: 1870 + Math.floor(R('built') * 46), rect };
}
