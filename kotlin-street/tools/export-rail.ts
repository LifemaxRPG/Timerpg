// Exports the railroad round the app's town (kotlin-street/docs/RAIL.md) as rail.json: the town's own track, depot,
// siding, elevator site and level crossings (src/town/rail.ts), and the world's network for RADIUS cells round it
// (src/world/rail.ts): every town (name, cell, rank, its station's status in every year 1900-2025), every link (history,
// company, length and the track's route), the timetables' phases, and for the towns nearest the plan's streets, lots and
// track (what a ride passes). Positions are in the town's frame, the street.json graph's: x east, z south, street
// units, (world tile - the plan's origin) x SCALE. The Kotlin side (domain/RailNet.kt) plans routes on it exactly as
// src/world/rail.ts planRoute does (checked by the desktop RailNetTest against routes written here with --checks).
// Usage: npx tsx kotlin-street/tools/export-rail.ts [out.json] [--checks checks.json]
import { readFileSync, writeFileSync } from 'fs';
import { archetypeOf } from '../../src/sim/archetype';
import { rand } from '../../src/sim/rng';
import { planTown } from '../../src/town/plan';
import { townRail, type TownRail } from '../../src/town/rail';
import { originOf } from '../../src/world/map';
import { wildChunk } from '../../src/world/wild';
import {
  CHANGE_PENALTY, METERS_PER_TILE, ROAD_FACTOR, ROAD_KMH, ROAD_WAIT, PASSENGER_SERVICE, SERVICE_FROM, SERVICE_HOURS, TRANSFER_MIN, alignment, linkLength, linksAt,
  alongLine, planRoute, railSite, stationAt, townRank, type RailLink, type RailWorld,
} from '../../src/world/rail';
import { SCALE } from './town-graph';
import { WORLD } from './town-config';

/** Cells round the town whose towns and lines are exported (the route planner's reach). */
export const RADIUS = 7;
/** Cells round the town whose plans (streets, lots, track) are exported for the ride's views of them. */
export const PLAN_RADIUS = 2;
const FROM = 1900, TO = 2025;

type P = [number, number];
const r1 = (v: number): number => Math.round(v * 10) / 10;

export function railJson(w: RailWorld = WORLD): Record<string, unknown> {
  const home = railSite(w, 0, 0);
  const homePlan = planTown(home.seed);
  const o = originOf(home, homePlan);
  // the town's frame: world tiles less the home plan's origin, times SCALE
  const fr = (x: number, y: number): P => [r1((x - o.x) * SCALE), r1((y - o.y) * SCALE)];
  const local = (ox: number, oy: number) => (x: number, y: number): P => fr(x + ox, y + oy);
  const towns: Record<string, unknown>[] = [];
  const links = new Map<string, RailLink>();
  const code = { none: 'n', passenger: 'p', freight: 'f', gone: 'g' } as const;
  for (let cy = -RADIUS; cy <= RADIUS; cy++) for (let cx = -RADIUS; cx <= RADIUS; cx++) {
    const s = railSite(w, cx, cy);
    let status = '';
    for (let y = FROM; y <= TO; y++) status += code[stationAt(w, cx, cy, y).status];
    const t: Record<string, unknown> = { id: s.id, cx, cy, tile: [s.center.x, s.center.y], name: s.name, kind: archetypeOf(s.seed).label, rank: townRank(s), founded: s.founded, center: fr(s.center.x, s.center.y), status };
    if (Math.max(Math.abs(cx), Math.abs(cy)) <= PLAN_RADIUS) {
      const plan = cx === 0 && cy === 0 ? homePlan : planTown(s.seed);
      const po = originOf(s, plan);
      const L = local(po.x, po.y);
      const rect = (q: { x: number; y: number; w: number; h: number }): number[] => [...L(q.x, q.y), ...L(q.x + q.w, q.y + q.h)];
      const rail = cx === 0 && cy === 0 ? townRail(w, s, plan, yardRects(s.seed)) : townRail(w, s, plan);
      t.plan = {
        rect: rect({ x: 0, y: 0, w: plan.width, h: plan.height }),
        // (the ground its own track is laid on: the map and the farmland round it, the portals on its edge)
        bounds: rect(rail.bounds),
        roads: plan.roads.map(rect),
        lots: plan.candidates.map((c) => [...rect(c), c.kind === 'shop' ? 1 : c.kind === 'apartment' ? 2 : 0]),
        landmarks: plan.landmarks.map(rect),
      };
      t.rail = railBlock(rail, L, rect);
    }
    towns.push(t);
    for (const l of linksAt(w, cx, cy)) links.set(l.id, l);
  }
  const lines = new Map<string, Record<string, unknown>>();
  const linkJson = [...links.values()].sort((a, b) => (a.id < b.id ? -1 : 1)).map((l) => {
    if (!lines.has(l.line)) {
      const ph: Record<string, unknown> = {};
      for (const dir of [1, -1]) for (const era of ['p', 'n']) ph[`${era}${dir}`] = rand(w.seed, 'rail-phase', l.line, dir, era);
      // tiles along the line from its position 0 to each position of the region (the timetables' clock: alongLine)
      const along: Record<string, number> = {};
      for (let i = -RADIUS - 1; i <= RADIUS + 1; i++) along[String(i)] = alongLine(w, l.line, i);
      ph.along = along;
      lines.set(l.line, ph);
    }
    return {
      id: l.id, a: l.a, b: l.b, dir: l.dir, kind: l.kind, line: l.line, built: l.built,
      ...(l.passengerEnd !== undefined ? { passengerEnd: l.passengerEnd } : {}), ...(l.abandoned !== undefined ? { abandoned: l.abandoned } : {}),
      ...(l.trail !== undefined ? { trail: l.trail } : {}), company: l.company, length: linkLength(w, l),
      pts: alignment(w, l).map((p) => fr(p.x, p.y)),
    };
  });
  // (the 3D town's yards and lanes reach past the plan's plots: the track keeps off them too)
  const homeRail = townRail(w, home, homePlan, yardRects(home.seed));
  const L0 = local(o.x, o.y);
  return {
    version: 1, town: home.seed, name: home.name, world: { seed: w.seed, home: w.home ?? null }, radius: RADIUS, from: FROM, to: TO,
    scale: Math.round(SCALE * 1e4) / 1e4, metersPerTile: METERS_PER_TILE, origin: [o.x, o.y],
    service: { ...PASSENGER_SERVICE, transferMin: TRANSFER_MIN, changePenalty: CHANGE_PENALTY, serviceFrom: SERVICE_FROM, serviceHours: SERVICE_HOURS, roadFactor: ROAD_FACTOR, roadKmh: ROAD_KMH, roadWait: ROAD_WAIT },
    rail: railBlock(homeRail, L0, (q) => [...L0(q.x, q.y), ...L0(q.x + q.w, q.y + q.h)]),
    towns, links: linkJson, lines: Object.fromEntries(lines),
  };
}

/** The shipped 3D town's yard parcels and back lanes (street.json graph; not the verges along the streets), in the plan's tiles; none for another town. */
export function yardRects(seed: string): { x: number; y: number; w: number; h: number }[] {
  let g: YardsGraph | undefined;
  try { g = JSON.parse(readFileSync(new URL(`../app/src/main/assets/town/${seed}/street.json`, import.meta.url), 'utf8')).graph; } catch { return []; }
  return yardRectsOf(g);
}

type YardsGraph = { yards?: { parcel: number[] }[]; lanes?: { rect: number[]; kind: string }[] };
/** [yardRects] of a street.json graph in hand (tools/town-farms.ts, while the export is made). */
export function yardRectsOf(g: YardsGraph | undefined): { x: number; y: number; w: number; h: number }[] {
  const rect = (r: number[]) => ({ x: Math.floor(r[0]! / SCALE), y: Math.floor(r[1]! / SCALE), w: Math.ceil(r[2]! / SCALE) - Math.floor(r[0]! / SCALE), h: Math.ceil(r[3]! / SCALE) - Math.floor(r[1]! / SCALE) });
  return [...(g?.yards ?? []).map((y) => rect(y.parcel)), ...(g?.lanes ?? []).filter((l) => l.kind === 'lane').map((l) => rect(l.rect))];
}

/** A town's own railroad in the town frame ([L] maps the plan's tiles to it). */
function railBlock(rail: TownRail, L: (x: number, y: number) => P, rect: (q: { x: number; y: number; w: number; h: number }) => number[]): Record<string, unknown> {
  return {
    tracks: rail.tracks.map((t) => ({ link: t.link, kind: t.kind, pts: t.pts.map((p) => L(p.x, p.y)) })),
    crossings: rail.crossings.map((c) => ({ at: L(c.x, c.y), dir: [c.dx, c.dy], road: c.road, link: c.link, lightsFrom: c.lightsFrom, ...(c.gatesFrom !== undefined ? { gatesFrom: c.gatesFrom } : {}) })),
    depot: rail.depot ? { rect: rect(rail.depot.rect), at: L(rail.depot.at.x, rail.depot.at.y), dir: [Math.round(rail.depot.dx * 1e3) / 1e3, Math.round(rail.depot.dy * 1e3) / 1e3], side: rail.depot.side, built: rail.depot.built } : null,
    elevator: rail.elevator ? rect(rail.elevator) : null,
    // the roads out of town (the farms' country roads, drawn by the farms: src/town/farms.ts) as rects
    roadsOut: rail.roadsOut.map(rect),
    // the fields the track splits: the pieces left either side (the farms' export draws these instead: tools/town-farms.ts)
    fieldCuts: rail.fieldCuts.map((c) => ({ field: c.field, pieces: c.pieces.map(rect) })),
    // (the world tile beyond each portal is converted by the caller's frame: the portal's own point first)
    portals: rail.portals.map((p) => ({ link: p.link, to: p.to, at: L(p.at.x, p.at.y) })),
  };
}

/** Routes the Kotlin planner must agree with (desktop RailNetTest). */
export function railChecks(w: RailWorld = WORLD): unknown[] {
  const out: unknown[] = [];
  const pairs: [string, string][] = [['0,0', '3,2'], ['0,0', '-2,1'], ['1,-1', '-1,2'], ['0,0', '0,3'], ['2,2', '-3,-1'], ['-1,0', '1,0'], ['0,0', '4,-2'], ['0,1', '0,-1']];
  for (const [i, year] of [1935, 1952, 1958, 1966, 1972, 1990, 2010, 2025].entries()) for (const [a, b] of pairs) {
    const time = i % 2 ? 7 * 60 : 15 * 60 + 30;
    out.push({ from: a, to: b, year, time, route: planRoute(w, a, b, year, time) });
  }
  return out;
}

if (process.argv[1]?.endsWith('export-rail.ts')) {
  const out = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : null;
  const json = JSON.stringify(railJson());
  if (out) writeFileSync(out, json); else console.log(json);
  const ci = process.argv.indexOf('--checks');
  if (ci > 0) {
    // and the keyed randomness and the wilderness, which the Kotlin side grows itself (domain/WorldRand.kt)
    const rands = [['world-6', 'wild', 12, -7], ['gamma', 'x'], ['w', 'rail-phase', 'ew:0', -1, 'n'], ['Ünïcode', 3, 'é']].map((k) => ({ keys: k, v: rand(String(k[0]), ...(k.slice(1) as (string | number)[])) }));
    const wild = [[0, 0], [5, -3], [-4, 7]].map(([cx, cy]) => ({ cx, cy, props: wildChunk(WORLD.seed, cx!, cy!) }));
    writeFileSync(process.argv[ci + 1]!, JSON.stringify({ world: WORLD.seed, routes: railChecks(), rands, wild }));
  }
}
