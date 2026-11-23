// A WORLD PLACE'S TOWN SITE AND FRAME (kotlin-street/docs/TOWNS_EVERYWHERE.md "The recipe"): what the world says about
// the ground round a place (src/world/gen: its facts, its rivers, roads and rail lines as polylines, the height and land
// round it), the town recipe that follows (src/town/recipe.ts), and where the planned town lies on the planet: its
// frame (a quarter turn and an anchor), its bounds, and the points where the world's roads cross its ground's edge.
//
// Frames. A town is planned in its own tiles (src/town/plan.ts: x east, y south, 2.1 m a tile, Main Street along x, a
// river down its own column along y). On the planet it is turned by k quarter turns clockwise (k 0: plan east is world
// east) and moved so its anchor (a plan point) stands on the anchor's world point. World points here are metres from
// the place's own point (x east, z south). The town's ground is its plan and the farm ring round it (RING tiles).
//
// Pure: a Site is plain data (tools/towns-bake.ts writes one per place from the world and hands it to a worker), and
// everything else is computed from it and the town's plan.
import { CELL_M, W as GRID_W, wrapDx } from '../../src/world/gen/grid';
import { BIOMES, KOPPEN } from '../../src/world/gen/climate';
import type { World } from '../../src/world/gen/world';
import type { Paths } from '../../src/world/gen/paths';
import type { Place } from '../../src/world/gen/places';
import type { ArchetypeId } from '../../src/sim/archetype';
import { hashStr, hashU } from '../../src/world/gen/noise';
import { segAt } from '../../src/world/gen/highways';
import { PEOPLE, SIZE, type RecipeEntry, type Side, type TownRank, type TownRecipe } from '../../src/town/recipe';
import { RING } from '../../src/town/farms';
import type { TownPlan } from '../../src/town/plan';

/** Metres a plan tile. */
export const TILE_M = 2.1;
/** Polylines kept round a place (m). */
const KEEP_M = 9000;
/** The height grid's half width round a place (cells). */
const GRID_R = 4;

/** A line near the place (m from its point). Roads are Highways v2 segments (src/world/gen/highways.ts): [kind] their class
 * in the bake year (an expressway, beltway or radial is 'expressway'; a connector a 'highway'), [n] the year built,
 * [gone] abandoned from, [seg] the segment's kind, [number] its route number then. */
export interface Poly {
  kind: 'expressway' | 'highway' | 'county' | 'local' | 'rail' | 'river'; id: string; /** river order, road built year */ n: number; pts: [number, number][];
  gone?: number; seg?: string; number?: string | null; /** a road's class by year ([year, class] steps) */ cls?: [number, string][];
}
/** The year the town is baked at (its roads as they are then; the town's own layout runs by year inside it). */
export const BAKE_YEAR = 2025;
/** The highways' facts for a town (highways.ts TownRoads) and the interchanges and roadside sites on its ground. */
export interface SiteRoads {
  mainStreet?: string;
  bypassed?: { year: number; by: string; seg: string };
  interchange?: { id: string; year: number; km: number; type: string; at: [number, number] };
  sites: { id: string; kind: string; from: number; to?: number; at: [number, number]; models: { from: number; to?: number; id: string }[] }[];
}

export interface Site {
  world: string;
  place: {
    id: string; kind: Place['kind']; type: string; rank: Place['rank']; setting?: string; ore?: string; name: string; seed: string;
    founded: number; coastal: boolean; mass: number; x: number; z: number; cell: number;
  };
  /** Region and climate (the place's landmass, biome, Koppen class and climate hooks). */
  region: { mass: number; biome: string; koppen: string; tags: string[] };
  /** A rail line has a station here. */
  station: boolean;
  /** The nearest bigger place (a hub or major town) within 40 km, for the county seat. */
  seatRival: boolean;
  polys: Poly[];
  /** Highways v2 at the place: its bypass, its interchange, the roadside sites near it. */
  roads?: SiteRoads;
  /** Cells round the place (2 GRID_R + 1 square, the place's cell in the middle): height (m), land (1/0), lake (1/0). */
  grid: { r: number; elev: number[]; land: number[]; lake: number[] };
}

/** The rank a town is planned at. */
export function rankOf(p: Pick<Place, 'kind' | 'rank'>): TownRank {
  if (p.kind === 'hub') return 'hub';
  if (p.kind === 'hamlet') return 'hamlet';
  return p.rank === 'major' ? 'major' : p.rank === 'town' ? 'town' : 'village';
}

/** A place's archetype (src/sim/archetype.ts) from its type: the towns' are archetypes already. */
export function archetypeFor(p: Site['place']): ArchetypeId {
  const t = p.type;
  if (['farm', 'harbor', 'mill', 'mining', 'resort', 'suburb'].includes(t)) return t as ArchetypeId;
  if (t === 'port') return 'harbor';
  if (t === 'river city' || t === 'mill town') return 'mill';
  if (t === 'mountain town') return 'mining';
  if (p.setting === 'pass') return 'resort';
  if (p.setting === 'mining') return 'mining';
  return p.coastal ? 'harbor' : 'farm';
}

/** Every place's site from the world (cheap: the polylines near each place, a small height grid). */
export function sitesOf(w: World, paths: Paths): Site[] {
  const stations = new Set<string>();
  for (const l of w.rail.lines) for (const s of l.stations) stations.add(s);
  type Raw = { kind: Poly['kind']; id: string; n: number; pts: [number, number][]; bx0: number; bx1: number; bz0: number; bz1: number };
  const raws: Raw[] = [];
  const add = (kind: Poly['kind'], id: string, n: number, pts: [number, number][]): void => {
    if (pts.length < 2) return;
    let bx0 = Infinity, bx1 = -Infinity, bz0 = Infinity, bz1 = -Infinity;
    for (const [x, z] of pts) { bx0 = Math.min(bx0, x); bx1 = Math.max(bx1, x); bz0 = Math.min(bz0, z); bz1 = Math.max(bz1, z); }
    raws.push({ kind, id, n, pts, bx0, bx1, bz0, bz1 });
  };
  paths.rivers.forEach((pts, k) => add('river', `river:${w.hydro.rivers[k]!.id}`, w.hydro.rivers[k]!.order, pts));
  // the roads: Highways v2's segments as they stand in the bake year (abandoned ones left out; the old network's roads
  // are pieces of these)
  void paths.roads;
  const segRaw = new Map<Raw, Poly>();
  for (const sg of w.highways.segs) {
    const st = segAt(sg, BAKE_YEAR);
    if (!st || st.cls === 'abandoned' || st.cls === 'track') continue;
    const kind: Poly['kind'] = st.cls === 'expressway' ? 'expressway' : st.cls === 'highway' ? 'highway' : st.cls === 'county' ? 'county' : 'local';
    add(kind, sg.id, sg.built, sg.path.map(([x, z]) => [x, z] as [number, number]));
    const r = raws[raws.length - 1]!;
    segRaw.set(r, { kind, id: sg.id, n: sg.built, pts: [], ...(sg.gone ? { gone: sg.gone } : {}), seg: sg.kind, number: st.number, cls: sg.cls.map(([y, c]) => [y, c]) });
  }
  paths.rail.forEach((pts, k) => add('rail', w.rail.lines[k]!.id, 0, pts));
  const keepC = KEEP_M / CELL_M;
  const icById = new Map(w.highways.interchanges.map((q) => [q.id, q]));
  const big = w.places.filter((q) => q.kind === 'hub' || q.rank === 'major');
  const out: Site[] = [];
  for (const p of w.places) {
    const polys: Poly[] = [];
    for (const r of raws) {
      // (x unwrapped along the line: test the box with the place's x moved next to it)
      const px = r.bx0 + wrapDx(p.x - r.bx0);
      if (px < r.bx0 - keepC || px > r.bx1 + keepC || p.z < r.bz0 - keepC || p.z > r.bz1 + keepC) continue;
      const pts: [number, number][] = r.pts.map(([x, z]) => [Math.round(wrapDx(x - p.x) * CELL_M * 10) / 10, Math.round((z - p.z) * CELL_M * 10) / 10]);
      // the run of points near the place (one point past each end of it)
      let a = -1, b = -1;
      pts.forEach(([x, z], i) => { if (Math.hypot(x, z) <= KEEP_M) { if (a < 0) a = i; b = i; } });
      if (a < 0) {
        // (a long segment passing through without a point near: keep the segment nearest the place)
        let best = -1, bd = Infinity;
        for (let i = 1; i < pts.length; i++) { const d = segDist(pts[i - 1]!, pts[i]!, [0, 0]); if (d < bd) { bd = d; best = i; } }
        if (bd > KEEP_M) continue;
        a = best - 1; b = best;
      }
      const extra = segRaw.get(r);
      polys.push({ ...(extra ?? {}), kind: r.kind, id: r.id, n: r.n, pts: pts.slice(Math.max(0, a - 1), Math.min(pts.length, b + 2)) });
    }
    const i0 = p.cell % GRID_W, j0 = Math.floor(p.cell / GRID_W);
    const elev: number[] = [], land: number[] = [], lake: number[] = [];
    for (let j = j0 - GRID_R; j <= j0 + GRID_R; j++) for (let i = i0 - GRID_R; i <= i0 + GRID_R; i++) {
      const c = Math.max(0, Math.min(w.terrain.elev.length / GRID_W - 1, j)) * GRID_W + (((i % GRID_W) + GRID_W) % GRID_W);
      elev.push(Math.round(w.terrain.elev[c]!)); land.push(w.terrain.land[c]!); lake.push(w.hydro.lake[c]! > 0 ? 1 : 0);
    }
    // the highways' facts for it, its interchange and the roadside sites within 4 km
    const tr = w.highways.towns.get(p.id);
    const rel = (x: number, z: number): [number, number] => [Math.round(wrapDx(x - p.x) * CELL_M), Math.round((z - p.z) * CELL_M)];
    const ic = tr?.interchange ? icById.get(tr.interchange) : undefined;
    const roadsAt: SiteRoads = {
      ...(tr?.mainStreet ? { mainStreet: tr.mainStreet } : {}),
      ...(tr?.bypassed ? { bypassed: { year: tr.bypassed.year, by: tr.bypassed.by, seg: tr.bypassed.seg } } : {}),
      ...(ic ? { interchange: { id: ic.id, year: tr!.interchangeYear ?? ic.year, km: tr!.interchangeKm ?? 0, type: ic.type, at: rel(ic.x, ic.z) } } : {}),
      sites: w.highways.sites.filter((q) => Math.hypot(wrapDx(q.x - p.x), q.z - p.z) * CELL_M < 4000)
        .map((q) => ({ id: q.id, kind: q.kind, from: q.from, ...(q.to ? { to: q.to } : {}), at: rel(q.x, q.z), models: q.models })),
    };
    const seatRival = big.some((q) => q !== p && Math.hypot(wrapDx(q.x - p.x), q.z - p.z) * CELL_M < 40000 && (q.kind === 'hub' || p.rank !== 'major'));
    out.push({
      world: w.seed,
      place: { id: p.id, kind: p.kind, type: p.type, rank: p.rank, ...(p.setting ? { setting: p.setting } : {}), ...(p.ore ? { ore: p.ore } : {}), name: p.name, seed: p.seed, founded: p.founded, coastal: p.coastal, mass: p.mass, x: p.x, z: p.z, cell: p.cell },
      region: { mass: p.mass, biome: BIOMES[w.biome[p.cell]!] ?? '?', koppen: KOPPEN[w.climate.koppen[p.cell]!] ?? '?', tags: w.tags.get(p.id) ?? [] },
      station: stations.has(p.id), seatRival, polys, roads: roadsAt, grid: { r: GRID_R, elev, land, lake },
    });
  }
  return out;
}

export function segDist(a: [number, number], b: [number, number], p: [number, number]): number {
  const dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz || 1e-9;
  const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / l2));
  return Math.hypot(a[0] + dx * t - p[0], a[1] + dz * t - p[1]);
}

/** The grid's value at a world point (m from the place), bilinear between cell centres. */
export function gridAt(s: Site, v: 'elev' | 'land' | 'lake', x: number, z: number): number {
  const n = 2 * s.grid.r + 1, a = s.grid[v];
  const gx = Math.max(0, Math.min(n - 1.001, x / CELL_M + s.grid.r)), gz = Math.max(0, Math.min(n - 1.001, z / CELL_M + s.grid.r));
  const i = Math.floor(gx), j = Math.floor(gz), fx = gx - i, fz = gz - j;
  const at = (ii: number, jj: number): number => a[jj * n + ii]!;
  return (at(i, j) * (1 - fx) + at(i + 1, j) * fx) * (1 - fz) + (at(i, j + 1) * (1 - fx) + at(i + 1, j + 1) * fx) * fz;
}

/** The recipe before the plan is known (no entries): the place's facts, its rank's size and people, the world's water. */
export function baseRecipe(s: Site, water: TownRecipe['water'] = waterGuess(s)): TownRecipe {
  const p = s.place;
  const rank = rankOf(p);
  const countySeat = rank === 'hub' || rank === 'major' || (rank === 'town' && !s.seatRival);
  return {
    seed: p.seed, place: p.id, name: p.name, rank, size: SIZE[rank], type: archetypeFor(p), ...(p.setting ? { setting: p.setting as TownRecipe['setting'] } : {}),
    ...(p.ore ? { ore: p.ore } : {}), water, rail: s.station, countySeat, founded: p.founded, people: PEOPLE[rank],
    ...(s.roads?.bypassed ? { bypassed: { year: s.roads.bypassed.year, by: s.roads.bypassed.by } } : {}),
    ...(s.roads?.interchange ? { interchange: { year: s.roads.interchange.year, km: s.roads.interchange.km } } : {}),
  };
}

/** The nearest river to the place: its distance (m) and its direction there (unit), or null. */
export function nearestRiver(s: Site, at: [number, number] = [0, 0]): { d: number; at: [number, number]; dir: [number, number]; id: string } | null {
  return nearestOf(s, at, (k) => k === 'river');
}
/** The nearest road to the place (a highway when one is nearly as near as the nearest county road). */
export function nearestRoad(s: Site, at: [number, number] = [0, 0]): { d: number; at: [number, number]; dir: [number, number]; id: string } | null {
  const h = nearestOf(s, at, (k) => k === 'highway'), c = nearestOf(s, at, (k) => k === 'county');
  return h && (!c || h.d <= c.d * 1.5 + 200) ? h : c;
}
function nearestOf(s: Site, at: [number, number], kind: (k: Poly['kind']) => boolean): { d: number; at: [number, number]; dir: [number, number]; id: string } | null {
  let best: { d: number; at: [number, number]; dir: [number, number]; id: string } | null = null;
  for (const r of s.polys) {
    if (!kind(r.kind)) continue;
    for (let i = 1; i < r.pts.length; i++) {
      const a = r.pts[i - 1]!, b = r.pts[i]!;
      const d = segDist(a, b, at);
      if (best && d >= best.d) continue;
      const dx = b[0] - a[0], dz = b[1] - a[1], l = Math.hypot(dx, dz) || 1;
      const t = Math.max(0, Math.min(1, ((at[0] - a[0]) * dx + (at[1] - a[1]) * dz) / (l * l)));
      best = { d, at: [a[0] + dx * t, a[1] + dz * t], dir: [dx / l, dz / l], id: r.id };
    }
  }
  return best;
}

/** The world's water at the place: a river through it (one within 700 m, or 1.5 km for a mill or a river city), a lake
 * beside it (a lake cell next to its own), or none. */
export function waterGuess(s: Site): TownRecipe['water'] {
  const r = nearestRiver(s);
  const t = s.place.type;
  if (r && r.d < (t === 'mill' || t === 'mill town' || t === 'river city' ? 1500 : 700)) return 'river';
  const n = 2 * s.grid.r + 1, c = s.grid.r;
  for (let j = c - 1; j <= c + 1; j++) for (let i = c - 1; i <= c + 1; i++) if (s.grid.lake[j * n + i]) return 'lake';
  return 'none';
}

// ------------------------------------------------------------------------------------------------ the frame

export interface Frame {
  /** The turn (degrees clockwise, x east and z south): the way plan east (Main Street) runs on the planet, 0 east, 90 south. */
  deg: number;
  /** The anchor: a plan point (tiles) and where it stands (m from the place's point). */
  plan: [number, number];
  world: [number, number];
}

/** Plan vector (tiles) to world vector (m): plan east is (cos, sin), plan south (-sin, cos). */
export function rotFwd(px: number, py: number, deg: number): [number, number] {
  const t = (deg * Math.PI) / 180, c = Math.cos(t), sn = Math.sin(t);
  return [(px * c - py * sn) * TILE_M, (px * sn + py * c) * TILE_M];
}
/** World vector (m) to plan vector (tiles). */
export function rotInv(x: number, z: number, deg: number): [number, number] {
  const t = (deg * Math.PI) / 180, c = Math.cos(t), sn = Math.sin(t);
  return [(x * c + z * sn) / TILE_M, (-x * sn + z * c) / TILE_M];
}
export const toWorld = (f: Frame, px: number, py: number): [number, number] => {
  const [x, z] = rotFwd(px - f.plan[0], py - f.plan[1], f.deg);
  return [f.world[0] + x, f.world[1] + z];
};
export const toPlan = (f: Frame, x: number, z: number): [number, number] => {
  const [a, b] = rotInv(x - f.world[0], z - f.world[1], f.deg);
  return [f.plan[0] + a, f.plan[1] + b];
};
/** The angle (degrees clockwise from east) of a world direction. */
const degOf = (x: number, z: number): number => (Math.atan2(z, x) * 180) / Math.PI;
/** An angle in [0, 360), to a tenth of a degree. */
const norm = (d: number): number => Math.round((((d % 360) + 360) % 360) * 10) / 10 % 360;

/** The town's ground (plan tiles): the plan and the farm ring round it. */
export const groundOf = (plan: Pick<TownPlan, 'width' | 'height'>): { x0: number; y0: number; x1: number; y1: number } => ({ x0: -RING, y0: -RING, x1: plan.width + RING, y1: plan.height + RING });

/** Where a road leaves a circle round [c] towards (a unit vector from [c], and the road's weight). */
function roadWays(s: Site, radius: number, c: [number, number]): { dir: [number, number]; w: number; id: string }[] {
  const out: { dir: [number, number]; w: number; id: string }[] = [];
  for (const r of s.polys) {
    if (r.kind !== 'highway' && r.kind !== 'county') continue;
    for (let i = 1; i < r.pts.length; i++) {
      const a = r.pts[i - 1]!, b = r.pts[i]!;
      const da = Math.hypot(a[0] - c[0], a[1] - c[1]), db = Math.hypot(b[0] - c[0], b[1] - c[1]);
      if ((da < radius) === (db < radius)) continue;
      const x = (da < radius ? b : a)[0] - c[0], z = (da < radius ? b : a)[1] - c[1];
      const l = Math.hypot(x, z) || 1;
      out.push({ dir: [x / l, z / l], w: r.kind === 'highway' ? 3 : 1, id: r.id });
    }
  }
  return out;
}

export interface Placed {
  frame: Frame;
  /** The world's river runs through the plan's river column (the town's river is the world's). */
  river: { id: string; offM: number } | null;
  /** The town was moved off its place's point to keep off a river and the sea (m). */
  shiftM: number;
  /** What could not be met (empty when all went well). */
  problems: string[];
}

/** The plan's river column's middle (plan tiles, x along its row at the plan's centre) and the plan's own way down it. */
function planRiverX(plan: TownPlan): number | null {
  const w = plan.water;
  if (!w || w.kind !== 'river') return null;
  const y = Math.round(plan.center.y);
  let n = 0, sx = 0;
  for (let x = 0; x < w.width; x++) if (w.raw[y * w.width + x]) { n++; sx += x; }
  return n ? sx / n + 0.5 : null;
}

/**
 * Where the planned town lies (see the top of the file): turned so Main Street follows the main road through the place
 * (or, on a river, the plan's river follows the world's), its strip and bypass towards the busiest road out; its centre
 * (or its river) on the place's point, moved up to 1.8 km where the built town would stand on another river or the sea.
 */
export function placeTown(s: Site, plan: TownPlan, flipX: boolean): Placed {
  const problems: string[] = [];
  const strip: [number, number] = [flipX ? -1 : 1, 0];
  const riverX = planRiverX(plan);
  const wr = riverX !== null ? nearestRiver(s) : null;
  // a town grows from its road: a town not on a river stands on the road nearest its point (a hamlet up to 3 km off, the
  // skeleton's place being a cell's middle and the road's drawn line winding round the hills; a town 1.5 km), Main
  // Street along it
  const road = wr ? null : nearestRoad(s);
  const onRoad = road && road.d < (s.place.kind === 'hamlet' ? 3000 : 1500) ? road : null;
  const centre: [number, number] = wr ? wr.at : onRoad ? onRoad.at : [0, 0];
  const ways = roadWays(s, 1500, centre);
  // the busiest way out (highways first), for the strip's end
  let best: { dir: [number, number]; w: number } | undefined = [...ways].sort((a, b) => b.w - a.w || Math.atan2(a.dir[1], a.dir[0]) - Math.atan2(b.dir[1], b.dir[0]))[0];
  // (a town with an interchange turns its strip towards it: the strip grows along the road out to the exit)
  const ic = s.roads?.interchange;
  if (ic && ic.km <= 4) { const dx = ic.at[0] - centre[0], dz = ic.at[1] - centre[1], l = Math.hypot(dx, dz); if (l > 100) best = { dir: [dx / l, dz / l], w: 9 }; }
  // the axis Main Street runs along (degrees; it and its opposite are the two choices)
  /** The main axis of some ways (doubled angles averaged, so opposite ways agree). */
  const axisOf = (ws: { dir: [number, number]; w: number }[]): number => {
    let cx = 0, cz = 0;
    for (const w of ws) { const a = 2 * Math.atan2(w.dir[1], w.dir[0]); cx += w.w * Math.cos(a); cz += w.w * Math.sin(a); }
    return (Math.atan2(cz, cx) * 90) / Math.PI;
  };
  let axis: number;
  if (wr) axis = degOf(wr.dir[0], wr.dir[1]) - 90; // (the plan's river runs down plan y: plan east is the river's way turned back a quarter)
  else if (onRoad) {
    // the road's own way through the town: where it leaves a 600 m circle round the town's point, both ways
    const own = roadWays(s, 600, centre).filter((w) => w.id === onRoad.id);
    axis = own.length ? axisOf(own) : degOf(onRoad.dir[0], onRoad.dir[1]);
  } else axis = ways.length ? axisOf(ways) : 0;
  // on a slope (over 6% across 500 m round the town's point) a mountain place's Main Street runs along the contour, the
  // way a hill village's street does: its houses on the terrace above and below it, not up and down the fall line
  {
    const e = (x: number, z: number): number => gridAt(s, 'elev', centre[0] + x, centre[1] + z);
    const gx = (e(500, 0) - e(-500, 0)) / 1000, gz = (e(0, 500) - e(0, -500)) / 1000;
    const steep = Math.hypot(gx, gz);
    if (!wr && steep > 0.06 && (s.place.setting || s.place.kind === 'hamlet' || s.place.rank === 'small')) axis = degOf(gx, gz) + 90;
  }
  let deg: number;
  if (best) {
    const dots = [axis, axis + 180].map((a) => { const [x, z] = rotFwd(strip[0], strip[1], a); return x * best.dir[0] + z * best.dir[1]; });
    deg = norm(dots[0]! >= dots[1]! ? axis : axis + 180);
  } else deg = norm(hashU(hashStr(s.place.seed), 7) < 0.5 ? axis : axis + 180);

  const W = plan.width, H = plan.height;
  // the built town's outline (plan tiles), sampled, and the ground's
  const inner: [number, number][] = [];
  for (let i = 0; i <= 8; i++) for (let j = 0; j <= 8; j++) inner.push([(W * i) / 8, (H * j) / 8]);
  // (the anchor: the river's middle on Main Street's line, or the civic block's front on Main Street's middle line)
  const anchorPlan: [number, number] = wr ? [riverX!, plan.mainY + 1] : [plan.center.x, plan.mainY + 1];
  const ok = (f: Frame): string | null => {
    for (const [px, py] of inner) {
      const [x, z] = toWorld(f, px, py);
      if (gridAt(s, 'land', x, z) < 0.5) return 'sea';
      if (gridAt(s, 'lake', x, z) >= 0.5) return 'lake';
    }
    // another river through the built town (its own river is the one in its column)
    for (const r of s.polys) {
      if (r.kind !== 'river' || (wr && r.id === wr.id)) continue;
      for (let i = 1; i < r.pts.length; i++) {
        const a = toPlan(f, ...r.pts[i - 1]!), b = toPlan(f, ...r.pts[i]!);
        if (segHitsRect(a, b, -12, -12, W + 12, H + 12)) return `river ${r.id}`;
      }
    }
    return null;
  };
  const base: [number, number] = centre;
  let frame: Frame = { deg, plan: anchorPlan, world: base };
  let why = ok(frame), shift = 0;
  if (why) {
    // moved off the river or the sea: a spiral of tries out to 1.8 km, nearest first
    const tries: [number, number][] = [];
    for (let rr = 150; rr <= 1800; rr += 150) for (let a = 0; a < 16; a++) {
      const t = (a / 16) * 2 * Math.PI;
      tries.push(wr ? [wr.dir[0] * rr * (a % 2 ? -1 : 1), wr.dir[1] * rr * (a % 2 ? -1 : 1)] : [Math.cos(t) * rr, Math.sin(t) * rr]);
    }
    let found = false;
    for (const [dx, dz] of tries) {
      const f: Frame = { deg, plan: anchorPlan, world: [base[0] + dx, base[1] + dz] };
      // (along a river, the town slides down its course: its anchor stays on the river)
      if (wr) { const nr = nearestRiver(s, f.world); if (!nr || nr.id !== wr.id) continue; f.world = nr.at; }
      if (!ok(f)) { frame = f; shift = Math.hypot(f.world[0] - base[0], f.world[1] - base[1]); found = true; break; }
    }
    if (!found) problems.push(`no site clear of ${why} within 1.8 km`);
  }
  return { frame, river: wr ? { id: wr.id, offM: Math.round(wr.d) } : null, shiftM: Math.round(shift), problems };
}

/** Does the segment [a]-[b] cross the rectangle? (plan tiles) */
export function segHitsRect(a: [number, number], b: [number, number], x0: number, y0: number, x1: number, y1: number): boolean {
  let t0 = 0, t1 = 1;
  const dx = b[0] - a[0], dy = b[1] - a[1];
  for (const [p, q] of [[-dx, a[0] - x0], [dx, x1 - a[0]], [-dy, a[1] - y0], [dy, y1 - a[1]]] as const) {
    if (p === 0) { if (q < 0) return false; continue; }
    const r = q / p;
    if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r; } else { if (r < t0) return false; if (r < t1) t1 = r; }
  }
  return true;
}

/** Where the world's roads and rail lines cross the town's ground's edge (plan tiles along that side), one entry per crossing. */
const RANK: Record<string, number> = { local: 0, county: 1, highway: 2, expressway: 3, rail: -1 };
export function entriesOf(s: Site, plan: TownPlan, f: Frame): RecipeEntry[] {
  const g = groundOf(plan);
  const out: RecipeEntry[] = [];
  for (const r of s.polys) {
    if (r.kind === 'river') continue;
    const pts = r.pts.map(([x, z]) => toPlan(f, x, z));
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1]!, b = pts[i]!;
      const ia = a[0] > g.x0 && a[0] < g.x1 && a[1] > g.y0 && a[1] < g.y1, ib = b[0] > g.x0 && b[0] < g.x1 && b[1] > g.y0 && b[1] < g.y1;
      // (both ends outside: the segment may still cut a corner of the ground)
      const hits = edgeHits(a, b, g);
      if (ia && ib) continue;
      for (const h of hits) {
        if (out.some((e) => e.road === r.id && e.side === h.side && Math.abs(e.at - h.at) < 15)) continue;
        // (two pieces of one road meeting at the edge, or a road and its business route, are one way in: the higher class kept)
        const twin = r.kind !== 'rail' ? out.find((e) => e.kind !== 'rail' && e.side === h.side && Math.abs(e.at - h.at) < 20) : undefined;
        if (twin) { if (RANK[r.kind]! > RANK[twin.kind]!) Object.assign(twin, { kind: r.kind, road: r.id, year: r.n, ...(r.number ? { number: r.number } : {}) }); continue; }
        out.push({ kind: r.kind, side: h.side, at: Math.round(h.at * 10) / 10, road: r.id, year: r.n, ...(r.number ? { number: r.number } : {}) });
      }
    }
  }
  return out.sort((a, b) => (a.side < b.side ? -1 : a.side > b.side ? 1 : a.at - b.at || (a.road < b.road ? -1 : 1)));
}

function edgeHits(a: [number, number], b: [number, number], g: ReturnType<typeof groundOf>): { side: Side; at: number }[] {
  const out: { side: Side; at: number }[] = [];
  const dx = b[0] - a[0], dy = b[1] - a[1];
  const cross = (side: Side, t: number): void => { if (t >= 0 && t <= 1) { const x = a[0] + dx * t, y = a[1] + dy * t; if ((side === 'N' || side === 'S') ? x >= g.x0 && x <= g.x1 : y >= g.y0 && y <= g.y1) out.push({ side, at: side === 'N' || side === 'S' ? x : y }); } };
  if (dy !== 0) { cross('N', (g.y0 - a[1]) / dy); cross('S', (g.y1 - a[1]) / dy); }
  if (dx !== 0) { cross('W', (g.x0 - a[0]) / dx); cross('E', (g.x1 - a[0]) / dx); }
  return out;
}

/** The town's ground on the planet: its axis-aligned box in atlas cell units (x unwrapped near the place). */
export function boundsOf(s: Site, plan: TownPlan, f: Frame): [number, number, number, number] {
  const g = groundOf(plan);
  let x0 = Infinity, z0 = Infinity, x1 = -Infinity, z1 = -Infinity;
  for (const [px, py] of [[g.x0, g.y0], [g.x1, g.y0], [g.x0, g.y1], [g.x1, g.y1]] as const) {
    const [x, z] = toWorld(f, px, py);
    x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
  }
  const r = (v: number): number => Math.round(v * 1e4) / 1e4;
  return [r(s.place.x + x0 / CELL_M), r(s.place.z + z0 / CELL_M), r(s.place.x + x1 / CELL_M), r(s.place.z + z1 / CELL_M)];
}

/** The ground under the built town (skeleton heights, m): mean, lowest, highest, and the relief (highest less lowest). */
export function groundStats(s: Site, plan: TownPlan, f: Frame): { m: number; min: number; max: number; relief: number } {
  let n = 0, sum = 0, lo = Infinity, hi = -Infinity;
  for (let i = 0; i <= 10; i++) for (let j = 0; j <= 10; j++) {
    const [x, z] = toWorld(f, (plan.width * i) / 10, (plan.height * j) / 10);
    const e = gridAt(s, 'elev', x, z);
    n++; sum += e; lo = Math.min(lo, e); hi = Math.max(hi, e);
  }
  return { m: Math.round(sum / n), min: Math.round(lo), max: Math.round(hi), relief: Math.round(hi - lo) };
}
