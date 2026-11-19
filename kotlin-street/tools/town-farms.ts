// The farms round the town in street.json v2 (kotlin-street/docs/TOWN.md "Farms"): `graph.farms`, from src/town/farms.ts
// (planFarms: country roads, farmsteads, fields; the year's view layout.farms; fieldLives: each field's life over the years),
// in world units (x east, z south, a tile of position SCALE units, as tools/town-graph.ts places everything).
//
//  - roads: the country roads (out from the streets' ends, and the section road round the town) as centre lines, the
//    export year's run in along a street not built yet included; drawn by kotlin-street TownFarms (asphalt, gravel verges).
//  - farms: each farmstead's yard, family (a rural household), the things standing in it (a model of assets/farm, its front's
//    middle and the way it faces; doors on the farmhouse and barn; the stand only in summer), its lane.
//  - fields: every field that is farmed at some time from 1950 to 2025 (`from`, `sold`: built over that year, `becomes`),
//    its crops in rotation (`rotation`, `phase`: the crop harvested in year Y is rotation[(Y - 1950 + phase) mod n]; `crop`
//    the export year's), its rows' direction and its gate. The engine grows each by the date (crops.json's calendar).
//  - The river runs on past the map (graph.water gains its polys) under the country roads' bridges (graph.bridges); the
//    forest's props standing in a field, a yard, a lane or on a road are dropped, and each farmstead's windbreak trees join
//    graph.props (the world's woods, TownGround's feather and belt, stand past the farthest of them).
import { runTimeline, worldAt } from '../../src/sim';
import { cropIn, fieldLives, planFarms, roadRect, RING, type Developed, type FarmField } from '../../src/town/farms';
import { layoutTown } from '../../src/town/layout';
import { townRail } from '../../src/town/rail';
import { railSite } from '../../src/world/rail';
import { yardRectsOf } from './export-rail';
import { WORLD } from './town-config';
import type { Rect } from '../../src/town/plan';
import { SCALE, type TownGraph } from './town-graph';

type P = [number, number];
type R4 = [number, number, number, number];
const r3 = (v: number): number => Math.round(v * 1000) / 1000;
const w = (v: number): number => r3(v * SCALE);
const wp = (p: readonly [number, number]): P => [w(p[0]), w(p[1])];
const wr = (r: Rect): R4 => [w(r.x), w(r.y), w(r.x + r.w), w(r.y + r.h)];
const inR = (x: number, z: number, r: R4, pad = 0): boolean => x > r[0] - pad && x < r[2] + pad && z > r[1] - pad && z < r[3] + pad;

/** The country road's asphalt and its verges (street units across). */
export const COUNTRY_ROAD = { asphalt: 96, verge: 22 };
/** Months the roadside stands are out (June to September). */
export const STAND_MONTHS: [number, number] = [6, 9];

export interface FarmsJson {
  ring: number; extent: R4; standMonths: [number, number];
  roads: { id: string; kind: string; pts: P[]; width: number; verge: number }[];
  farms: {
    id: string; family: string; founded: number; household: { surname: string; rural: true; members: number }; working: boolean;
    rect: R4; ground: R4; front: string; produce: boolean; lane: { a: P; b: P; width: number };
    things: { model: string; x: number; z: number; face: P; door?: boolean; summer?: boolean }[];
  }[];
  fields: {
    id: string; farm: string; use: string; rect: R4; rowsX: boolean; gate: { x: number; z: number; side: string };
    rotation: string[]; phase: number; crop: string; from: number; sold?: number; becomes?: string;
  }[];
}

/** Adds `graph.farms` to [g]'s json (after tools/town-yards.ts withYards), with the river, bridges and props it changes. */
export function withFarms(g: TownGraph, json: TownGraph['json'] = g.json): TownGraph['json'] {
  const plan = g.layout.plan;
  const fp = planFarms(plan);
  const year = g.year;
  const tl = runTimeline({ seed: g.seed, endYear: Math.max(2025, year) });
  const devOf = new Map<number, Developed & { shops: Rect[] }>();
  const dev = (y: number): Developed & { shops: Rect[] } => {
    let d = devOf.get(y);
    if (!d) {
      const L = layoutTown(worldAt(tl, y));
      const lots = [...L.lots, ...L.vacantLots];
      d = { roads: L.roads, lots, landmarks: L.landmarks, shops: lots.filter((l) => l.kind === 'shop') };
      devOf.set(y, d);
    }
    return d;
  };
  const lives = new Map(fieldLives(plan, dev, Math.max(2025, year)).map((l) => [l.id, l]));
  const now = g.layout.farms;
  const graph = json.graph as TownGraph['graph'] & { yards?: { parcel: R4 }[]; lanes?: { rect: R4 }[] };
  // a field the export year's yards reach into (a parcel widened between groups) is not farmed that year either
  const parcels: R4[] = [...(graph.yards ?? []).map((y) => y.parcel), ...(graph.lanes ?? []).map((l) => l.rect)];
  const hitR = (a: R4, b: R4): boolean => a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
  const fieldJson = (f: FarmField, base = f.id): FarmsJson['fields'][number] | null => {
    const life = lives.get(base);
    if (!life) return null;
    const rect = wr(f);
    // (a field a yard reaches into in the export year is someone's garden by then: sold that year at the latest)
    const yarded = (life.sold === undefined || life.sold > year) && parcels.some((p) => hitR(rect, p));
    const sold = yarded ? year : life.sold;
    const becomes = yarded ? 'yards' : life.becomes;
    return {
      id: f.id, farm: f.farm, use: f.use, rect, rowsX: f.rowsX, gate: { x: w(f.gate.x), z: w(f.gate.y), side: f.gate.side },
      rotation: f.rotation, phase: f.phase, crop: cropIn(f, year), from: life.from,
      ...(sold !== undefined ? { sold } : {}), ...(becomes ? { becomes } : {}),
    };
  };
  // the railroad (the app's town: tools/export-rail.ts, the same track as rail.json) splits the fields it runs through: the
  // pieces either side, each fenced with its own gate (the field's own where it is on the piece, else facing the yard)
  const rail = g.seed === WORLD.home ? townRail(WORLD, railSite(WORLD, 0, 0), plan, yardRectsOf(graph as Parameters<typeof yardRectsOf>[0])) : null;
  const cuts = new Map((rail?.fieldCuts ?? []).map((c) => [c.field, c.pieces]));
  const yardOf = new Map(fp.farms.map((f) => [f.id, f.rect]));
  const pieceOf = (f: FarmField, q: Rect, i: number): FarmField => {
    const onEdge = (f.gate.side === 'N' && f.gate.y === q.y) || (f.gate.side === 'S' && f.gate.y === q.y + q.h) || (f.gate.side === 'W' && f.gate.x === q.x) || (f.gate.side === 'E' && f.gate.x === q.x + q.w);
    const along = f.gate.side === 'N' || f.gate.side === 'S' ? f.gate.x > q.x + 2 && f.gate.x < q.x + q.w - 2 : f.gate.y > q.y + 2 && f.gate.y < q.y + q.h - 2;
    let gate = f.gate;
    if (!(onEdge && along)) {
      const y = yardOf.get(f.farm) ?? f;
      const ddx = y.x + y.w / 2 - (q.x + q.w / 2), ddy = y.y + y.h / 2 - (q.y + q.h / 2);
      const side = Math.abs(ddx) / q.w > Math.abs(ddy) / q.h ? (ddx > 0 ? 'E' : 'W') : ddy > 0 ? 'S' : 'N';
      gate = { side, x: side === 'E' ? q.x + q.w : side === 'W' ? q.x : q.x + q.w / 2, y: side === 'S' ? q.y + q.h : side === 'N' ? q.y : q.y + q.h / 2 };
    }
    return { ...f, ...q, id: `${f.id}~${i}`, rowsX: f.rowsX, gate };
  };
  const fields = fp.fields.flatMap((f) => {
    const pieces = cuts.get(f.id);
    if (!pieces) return [fieldJson(f)];
    return pieces.map((q, i) => fieldJson(pieceOf(f, q, i), f.id));
  }).filter((f): f is NonNullable<typeof f> => !!f);
  const roads = now.roads.map((r) => ({
    id: r.id, kind: r.kind, pts: [wp(r.inner ?? r.a), wp(r.b)], width: COUNTRY_ROAD.asphalt, verge: COUNTRY_ROAD.verge,
  }));
  const farms = now.farms.map((f) => ({
    id: f.id, family: f.family, founded: f.founded, household: f.household, working: f.working, rect: wr(f.rect), ground: wr(f.ground), front: f.front, produce: f.produce,
    lane: { a: wp(f.lane.a), b: wp(f.lane.b), width: w(f.lane.w) },
    things: f.things.map((t) => ({ model: t.model, x: w(t.x), z: w(t.y), face: t.face, ...(t.door ? { door: true } : {}), ...(t.summer ? { summer: true } : {}) })),
  }));
  const farmsJson: FarmsJson = {
    ring: RING, extent: wr(fp.extent), standMonths: STAND_MONTHS, roads, farms, fields,
  };

  // the river past the map, and the bridges over it
  const water = graph.water ? {
    ...graph.water,
    polys: [...graph.water.polys, ...fp.water.map((r): P[] => { const q = wr(r); return [[q[0], q[1]], [q[2], q[1]], [q[2], q[3]], [q[0], q[3]]]; })],
  } : graph.water;
  const bridges = [...graph.bridges, ...fp.bridges.map((b) => ({ rect: wr(b.rect), horizontal: b.horizontal }))];

  // props: none in a field farmed this year, a yard, a lane, on a country road or in the river past the map; the windbreaks join
  const clear: R4[] = [
    ...fields.filter((f) => f.from <= year && (f.sold === undefined || f.sold > year)).map((f) => f.rect),
    // (the ground between a split field's pieces and the track stays open: the forest was cleared for the whole field)
    ...fp.fields.filter((f) => { const l = lives.get(f.id); return cuts.has(f.id) && !!l && l.from <= year && (l.sold === undefined || l.sold > year); }).map(wr),
    ...farms.map((f) => f.rect),
    ...now.roads.map((r) => wr(roadRect({ a: r.inner ?? r.a, b: r.b }, 2 + 2 * COUNTRY_ROAD.verge / SCALE + 1))),
    ...fp.water.map(wr),
  ];
  const lanes = farms.map((f) => { const x0 = Math.min(f.lane.a[0], f.lane.b[0]), x1 = Math.max(f.lane.a[0], f.lane.b[0]), z0 = Math.min(f.lane.a[1], f.lane.b[1]), z1 = Math.max(f.lane.a[1], f.lane.b[1]); return [x0 - 60, z0 - 60, x1 + 60, z1 + 60] as R4; });
  const props = graph.props.filter((p) => !clear.some((r) => inR(p.x, p.z, r, 30)) && !lanes.some((r) => inR(p.x, p.z, r)));
  const fieldRects = fields.map((f) => f.rect);
  for (const f of fp.farms) {
    for (const [i, t] of f.trees.entries()) {
      const x = w(t[0]), z = w(t[1]);
      if (fieldRects.some((r) => inR(x, z, r, 20)) || now.roads.some((r) => inR(x, z, wr(roadRect({ a: r.inner ?? r.a, b: r.b }, 4))))) continue;
      props.push({ kind: 'tree', x, z, look: r3(0.55 + ((i * 0.618) % 0.45)) });
    }
  }
  return { ...json, graph: { ...graph, water, bridges, props, farms: farmsJson } as TownGraph['graph'] };
}
