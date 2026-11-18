// Yards for the whole town (kotlin-street/docs/TOWN.md, T9; the owner's request: neighbourhoods that feel real, every home
// with a front yard AND a backyard, built complete from every side for a possible open-world camera). From street.json
// v2's graph (tools/town-graph.ts: rows, buildings, lots) and our game's own households (src/sim), deterministic with the
// game's rand(seed, ...keys):
//
//  - Each lot gets a PARCEL: its rect, widened along its row into the free ground the rigid placement left between
//    groups (shared half and half with the neighbour's parcel, at most SIDE_MAX each side). The rest of such a gap
//    becomes a VERGE (a planted strip), and where two blocks' backyards leave a strip between them a BACK LANE (when it
//    reaches a street at an end) or a verge.
//  - A home's FRONT YARD (building line to front wall) gets its lawn (gravel in the old town), the path from the
//    pavement to the door, the driveway where a side of the house has room for one (from a dropped kerb across the
//    pavement to a parking pad, carport or garage behind the house; in front of the building's own garage door if it
//    has one), and its edge by the neighbourhood's yard style: a low wall (compact), a picket fence (flower), a hedge
//    (formal), a post-and-rail fence or nothing (natural), open at the path and the driveway.
//  - The BACKYARD (back wall to the parcel's back) gets its lawn (a meadow at the back in natural ones), side and back
//    fences (one fence on a boundary two parcels share), side gates, a patio or deck at the back door with a table,
//    chairs and a grill, and what the household has: a play set, trampoline or sandpit for children, vegetable beds,
//    a compost bin and a greenhouse for gardeners, a pool for the rich (villas, estates, wealthy families with room),
//    a shed, a washing line, bins, trees, bushes and flower beds by style (a fire pit and log pile in natural yards, a
//    birdbath and pond in flower ones, a gazebo in formal ones).
//  - Apartment blocks get a shared garden (bins, bike shed, washing lines, benches); shops a service yard (bins,
//    pallets, staff parking).
//
// Every item is an axis-aligned world rect [x0, z0, x1, z1] (rows run along the map's axes), a facing `face` (unit
// [dx, dz]: where its front or door looks) and a `variant` (0..9, for the renderer to pick a model). Items of the
// ground layer (lawn, meadow, gravel) lie under the rest; surfaces (paths, driveways, patios, decks, pads) under objects
// standing on them (`on`). Nothing overlaps a building, a road, another yard or another item of its layer.
import { rand } from '../../src/sim/rng';
import type { Lot } from '../../src/town/layout';
import { MOUTH, type RowJson, type TownGraph } from './town-graph';

type P = [number, number];
export type Rect4 = [number, number, number, number];
export type Layer = 'ground' | 'surface' | 'object' | 'edge';
export interface YardItem { kind: string; rect: Rect4; face: P; variant: number; layer: Layer; on?: string; zone: 'front' | 'side' | 'back' | 'street' }
export interface YardJson {
  lot: string; sprite: string; row: string; neighbourhood: string; style: string; kind: string;
  /** The lot's ground (lot rect widened along the row), the building's own footprint, the front and back yards. */
  parcel: Rect4; house: Rect4; front: Rect4 | null; back: Rect4;
  household: { residents: number; children: number; elders: number; gardener: boolean; wealth: number; pool: boolean; drive: 'garage' | 'carport' | 'pad' | 'none' };
  items: YardItem[];
}
export interface LaneJson { id: string; kind: 'lane' | 'verge'; rect: Rect4; axis: 'x' | 'z'; reaches: number; items: YardItem[] }
export interface Yards { yards: YardJson[]; lanes: LaneJson[]; props: TownGraph['graph']['props']; propsDropped: number }

/** Pavement and kerb from the building line to the carriageway (Crossroads.PAVE + KERB). */
const PAVE = 38;
/** How far a parcel may widen into the free ground on each side. */
const SIDE_MAX = 200;
const DRIVE_W = 72, PATH_W = 26, FENCE = 4, HEDGE = 16, WALL = 8;
const LANE_MIN = 56, LANE_W = 80;

const r3 = (v: number): number => Math.round(v * 1000) / 1000;
const hit = (a: Rect4, b: Rect4, pad = 0.01): boolean => a[0] < b[2] - pad && b[0] < a[2] - pad && a[1] < b[3] - pad && b[1] < a[3] - pad;
const cumOf = (pts: P[]): number[] => { const c = [0]; for (let i = 1; i < pts.length; i++) c.push(c[i - 1]! + Math.hypot(pts[i]![0] - pts[i - 1]![0], pts[i]![1] - pts[i - 1]![1])); return c; };
function frameAt(pts: P[], cum: number[], s: number): { p: P; along: P; normal: P } {
  let i = 0; while (i + 2 < pts.length && s > cum[i + 1]!) i++;
  const a = pts[i]!, b = pts[i + 1]!, L = cum[i + 1]! - cum[i]!;
  const ux = (b[0] - a[0]) / L, uy = (b[1] - a[1]) / L, t = s - cum[i]!;
  return { p: [a[0] + ux * t, a[1] + uy * t], along: [ux, uy], normal: [uy, -ux] };
}

/** A lot's own frame: u along its row (the row's x), d back from the building line (+normal). */
class Frame {
  constructor(readonly p: P, readonly a: P, readonly n: P, readonly s: number) {}
  world(u: number, d: number): P { return [this.p[0] + this.a[0] * (u - this.s) + this.n[0] * d, this.p[1] + this.a[1] * (u - this.s) + this.n[1] * d]; }
  rect(u0: number, u1: number, d0: number, d1: number): Rect4 {
    const c = [this.world(u0, d0), this.world(u1, d1)];
    return [r3(Math.min(c[0]![0], c[1]![0])), r3(Math.min(c[0]![1], c[1]![1])), r3(Math.max(c[0]![0], c[1]![0])), r3(Math.max(c[0]![1], c[1]![1]))];
  }
  local(r: Rect4): L {
    const us = [r[0], r[2]].flatMap((x) => [r[1], r[3]].map((z) => this.s + (x - this.p[0]) * this.a[0] + (z - this.p[1]) * this.a[1]));
    const ds = [r[0], r[2]].flatMap((x) => [r[1], r[3]].map((z) => (x - this.p[0]) * this.n[0] + (z - this.p[1]) * this.n[1]));
    return { u0: Math.min(...us), u1: Math.max(...us), d0: Math.min(...ds), d1: Math.max(...ds) };
  }
  face(fu: number, fd: number): P { return [r3(this.a[0] * fu + this.n[0] * fd) + 0, r3(this.a[1] * fu + this.n[1] * fd) + 0]; }
}
/** A local rect: u along the row, d back from the building line. */
interface L { u0: number; u1: number; d0: number; d1: number }
const lhit = (a: L, b: L, pad = 0): boolean => a.u0 < b.u1 + pad - 1e-6 && b.u0 < a.u1 + pad - 1e-6 && a.d0 < b.d1 + pad - 1e-6 && b.d0 < a.d1 + pad - 1e-6;
const inside = (a: L, b: L): boolean => a.u0 >= b.u0 - 1e-6 && a.u1 <= b.u1 + 1e-6 && a.d0 >= b.d0 - 1e-6 && a.d1 <= b.d1 + 1e-6;

/** Where the door is across a front (fraction of the art's width from its left, as seen from the street). */
function doorAt(key: string): number {
  if (key.startsWith('house_small_')) return 0.42;
  if (key.startsWith('house_medium_')) return 0.31;
  if (key.startsWith('villa_')) return 0.27;
  const t = key.match(/^house_terraced_(\d)/);
  if (t) return ({ 1: 0.29, 2: 0.29, 4: 0.67, 5: 0.69, 6: 0.69 } as Record<string, number>)[t[1]!] ?? 0.5;
  return ({ estate_one_story_house: 0.72, estate_country_house: 0.22, estate_japanese_house: 0.34, estate_modern_house: 0.5 } as Record<string, number>)[key] ?? 0.5;
}
/** A building's own garage door (fractions of its width), where the art has one. */
const GARAGE_DOOR: Record<string, [number, number]> = { estate_one_story_house: [0.05, 0.47] };

interface Parcel {
  lot: string; sprite: string; key: string; row: RowJson; kind: string; tier: string; neighbourhood: string; style: string;
  f: Frame; lotL: L; p: L; b: L; layoutLot?: Lot; index: number;
}

export function townYards(g: TownGraph): Yards {
  const graph = g.graph, seed = g.seed, year = g.year;
  const R = (...k: (string | number)[]): number => rand(seed, 'yard', ...k);
  const keyOf = new Map<string, string>();
  for (const e of g.json.shops as { sprite: string; parts: { key: string }[] }[]) keyOf.set(e.sprite, e.parts[0]?.key ?? '');
  const lotById = new Map(graph.lots.map((l) => [l.id, l]));
  const layoutLot = new Map(g.layout.lots.map((l) => [l.slot, l]));

  // ------------------------------------------------------------------------------------------- obstacles (world)
  const roads: Rect4[] = graph.roads.flatMap((r) => r.pts.slice(1).map((b, i) => {
    const a = r.pts[i]!, h = MOUTH / 2;
    return [Math.min(a[0], b[0]) - h, Math.min(a[1], b[1]) - h, Math.max(a[0], b[0]) + h, Math.max(a[1], b[1]) + h] as Rect4;
  }));
  const water: Rect4[] = (graph.water?.polys ?? []).map((p) => [p[0]![0], p[0]![1], p[2]![0], p[2]![1]] as Rect4);
  const carriageways: Rect4[] = graph.roads.flatMap((r) => r.pts.slice(1).map((b, i) => {
    const a = r.pts[i]!, h = MOUTH / 2 - PAVE;
    return [Math.min(a[0], b[0]) - h, Math.min(a[1], b[1]) - h, Math.max(a[0], b[0]) + h, Math.max(a[1], b[1]) + h] as Rect4;
  }));
  const fixed: Rect4[] = [...roads, ...water, graph.plaza.rect, ...graph.bridges.map((b) => b.rect), ...graph.landmarks.map((l) => l.rect)];

  // ------------------------------------------------------------------------------------------- parcels
  const parcels: Parcel[] = [];
  const bodies: Rect4[] = [];
  for (const row of graph.rows) {
    const pts = row.pts, cum = cumOf(pts);
    for (const b of row.buildings) {
      const s = (b.x0 + b.x1) / 2;
      const fr = frameAt(pts, cum, Math.max(0, Math.min(cum[cum.length - 1]!, s)));
      const f = new Frame(fr.p, fr.along, fr.normal, s);
      const body: L = { u0: b.x0, u1: b.x1, d0: b.setback, d1: b.setback + b.depth };
      bodies.push(f.rect(body.u0, body.u1, body.d0, body.d1));
      const lj = lotById.get(b.lot);
      if (!lj) continue;
      const lotL = f.local(lj.rect);
      parcels.push({ lot: b.lot, sprite: b.sprite, key: keyOf.get(b.sprite) ?? '', row, kind: b.kind, tier: lj.kind, neighbourhood: lj.neighbourhood, style: lj.yard,
        f, lotL, p: { ...lotL }, b: body, layoutLot: layoutLot.get(b.lot), index: parcels.length });
    }
  }
  const lotRects = parcels.map((p) => p.f.rect(p.lotL.u0, p.lotL.u1, p.lotL.d0, p.lotL.d1));
  /** How far (up to SIDE_MAX) a parcel's band can widen on side `dir` before it meets something, and what it meets. */
  const reach = (pc: Parcel, dir: 1 | -1): { t: number; by: number } => {
    const edge = dir > 0 ? pc.lotL.u1 : pc.lotL.u0;
    const band = (t: number): Rect4 => (dir > 0 ? pc.f.rect(edge, edge + t, pc.lotL.d0, pc.lotL.d1) : pc.f.rect(edge - t, edge, pc.lotL.d0, pc.lotL.d1));
    const blocker = (r: Rect4): number => {
      for (let i = 0; i < lotRects.length; i++) if (i !== pc.index && hit(r, lotRects[i]!)) return i;
      if (fixed.some((o) => hit(r, o)) || bodies.some((o) => hit(r, o))) return -2;
      return -1;
    };
    let lo = 0, hi = SIDE_MAX * 2 + 8;
    if (blocker(band(hi)) === -1) return { t: hi, by: -1 };
    while (hi - lo > 0.5) { const m = (lo + hi) / 2; if (blocker(band(m)) === -1) lo = m; else hi = m; }
    return { t: Math.floor(lo), by: blocker(band(hi)) };
  };
  for (const pc of parcels) {
    if (pc.kind === 'shop') continue;                          // the old town's shops stand shoulder to shoulder on their lots
    for (const dir of [-1, 1] as const) {
      const { t, by } = reach(pc, dir);
      // a gap to another home's lot is shared; a shop's lot does not widen, so a home takes the gap up to it
      const share = by >= 0 && parcels[by]!.kind !== 'shop' ? t / 2 : t;
      const w = Math.max(0, Math.min(SIDE_MAX, Math.floor(share) - (by === -2 ? 6 : 0)));
      if (dir > 0) pc.p.u1 = pc.lotL.u1 + w; else pc.p.u0 = pc.lotL.u0 - w;
    }
  }
  const parcelRect = (pc: Parcel): Rect4 => pc.f.rect(pc.p.u0, pc.p.u1, pc.p.d0, pc.p.d1);

  // which boundaries two parcels share (fenced once, by the parcel on the lower u side / the lower index)
  const touches = (a: Rect4, b: Rect4): boolean => !hit(a, b, -0.6) ? false : !hit(a, b, 0.6);
  const neighbourAt = (pc: Parcel, side: 'u0' | 'u1' | 'back'): Parcel | undefined => {
    const L0 = pc.p;
    const probe = side === 'u0' ? pc.f.rect(L0.u0 - 2, L0.u0, L0.d0 + 4, L0.d1 - 4) : side === 'u1' ? pc.f.rect(L0.u1, L0.u1 + 2, L0.d0 + 4, L0.d1 - 4) : pc.f.rect(L0.u0 + 4, L0.u1 - 4, L0.d1, L0.d1 + 2);
    return parcels.find((q) => q !== pc && hit(probe, parcelRect(q)));
  };
  void touches;

  // ------------------------------------------------------------------------------------------- one yard
  const yards: YardJson[] = [];
  for (const pc of parcels) {
    const items: YardItem[] = [];
    const f = pc.f, P = pc.p, B = pc.b;
    const id = pc.lot;
    const lot = pc.layoutLot;
    const people = (lot?.residents ?? []).map((r) => g.w.people[r]).filter((x) => x !== undefined);
    const age = (birth: number): number => year - birth;
    const children = people.filter((q) => age(q!.birthYear) < 14).length;
    const elders = people.filter((q) => age(q!.birthYear) >= 65).length;
    const head = lot ? g.w.people[lot.refId] : undefined;
    const wealth = Math.round(head?.wealth ?? 0);
    const diligence = head?.traits.diligence ?? 0.5;
    const style = pc.style as 'compact' | 'flower' | 'formal' | 'natural';
    const home = pc.kind === 'home';
    const flats = pc.tier === 'apartment' || (home && !/^(house_|villa_|estate_)/.test(pc.key) && pc.tier !== 'small' && pc.tier !== 'medium');
    const gardener = home && !flats && R(id, 'garden') < 0.15 + 0.3 * Math.min(1, elders) + (style === 'flower' ? 0.3 : style === 'natural' ? 0.1 : 0) + 0.2 * diligence;

    const add = (kind: string, l: L, layer: Layer, zone: YardItem['zone'], face: [number, number] = [0, -1], variant = 0, on?: string): YardItem | undefined => {
      if (l.u1 - l.u0 < 1 || l.d1 - l.d0 < 1) return undefined;
      const it: YardItem = { kind, rect: f.rect(l.u0, l.u1, l.d0, l.d1), face: f.face(face[0], face[1]), variant, layer, zone };
      if (on) it.on = on;
      items.push(it); localOf.set(it, l);
      return it;
    };
    const localOf = new Map<YardItem, L>();
    const v = (k: string, n = 10): number => Math.floor(R(id, 'variant', k) * n);

    const front: L | null = B.d0 - P.d0 >= 8 ? { u0: P.u0, u1: P.u1, d0: P.d0, d1: B.d0 } : null;
    const back: L = { u0: P.u0, u1: P.u1, d0: B.d1, d1: P.d1 };
    const leftW = B.u0 - P.u0, rightW = P.u1 - B.u1;
    const fenceIn = FENCE + 2;

    // ---- the driveway: in front of the building's own garage door, else down the roomier side to the back
    const gd = GARAGE_DOOR[pc.key];
    let drive: YardJson['household']['drive'] = 'none';
    let driveL: L | null = null, padL: L | null = null, driveSide: 'u0' | 'u1' | null = null;
    const rich = pc.tier === 'villa' || pc.tier === 'estate' || wealth >= 400;
    const backDepth = back.d1 - back.d0;
    if (gd && front) {
      const W = B.u1 - B.u0;
      driveL = { u0: B.u0 + gd[0] * W, u1: B.u0 + gd[1] * W, d0: P.d0, d1: B.d0 };
      drive = 'garage';
    } else if (!flats) {
      // the roomier side first; a side only if the drive fits and its dropped kerb meets the carriageway there
      const first: 'u0' | 'u1' = rightW > leftW || (rightW === leftW && R(id, 'side') < 0.5) ? 'u1' : 'u0';
      for (const side of [first, first === 'u1' ? 'u0' : 'u1'] as const) {
        const room = side === 'u1' ? rightW : leftW;
        if (room < DRIVE_W + fenceIn + 4 || backDepth < 150 || pc.kind === 'shop') continue;
        const u0 = side === 'u1' ? B.u1 + 4 : P.u0 + fenceIn, u1 = side === 'u1' ? P.u1 - fenceIn : B.u0 - 4;
        const w = Math.min(u1 - u0, DRIVE_W + 10);
        const du0 = side === 'u1' ? u0 : u1 - w, du1 = du0 + w;
        const kerb = f.rect(du0, du1, -PAVE - 0.5, P.d0);
        if (!carriageways.some((c) => hit(kerb, c, 0))) continue;
        driveSide = side;
        // a garage for the well off with a deep enough yard, a carport for some, else a parking pad
        const big = backDepth >= 260;
        drive = rich && big ? 'garage' : big && R(id, 'carport') < 0.35 ? 'carport' : 'pad';
        const padD = drive === 'pad' ? 150 : 170;
        driveL = { u0: du0, u1: du1, d0: P.d0, d1: B.d1 + 8 };
        padL = { u0: du0, u1: du1, d0: B.d1 + 8, d1: B.d1 + 8 + padD };
        if (drive === 'garage') { const gw = Math.min(u1 - u0, 110); padL = side === 'u1' ? { ...padL, u1: du0 + gw } : { ...padL, u0: du1 - gw }; }
        break;
      }
    }
    if (driveL) {
      add('driveway', driveL, 'surface', 'front', [0, -1], v('drive', 3));
      add('kerbCut', { u0: driveL.u0, u1: driveL.u1, d0: -PAVE, d1: P.d0 }, 'surface', 'street', [0, -1]);
    }
    if (padL) {
      if (drive === 'garage') add('garage', padL, 'object', 'back', [0, -1], v('garage', 4));
      else if (drive === 'carport') { add('parkingPad', padL, 'surface', 'back', [0, -1], v('pad', 3)); add('carport', padL, 'object', 'back', [0, -1], v('carport', 3), 'parkingPad'); }
      else add('parkingPad', padL, 'surface', 'back', [0, -1], v('pad', 3));
    }

    // ---- the front yard
    const W = B.u1 - B.u0;
    const doorU = B.u0 + doorAt(pc.key) * W;
    const pathL: L | null = front && home ? { u0: Math.max(B.u0, doorU - PATH_W / 2), u1: Math.min(B.u1, doorU + PATH_W / 2), d0: P.d0, d1: B.d0 } : null;
    if (front && home) {
      const groundKind = style === 'compact' && R(id, 'gravel') < 0.4 ? 'gravel' : 'lawn';
      add(groundKind, front, 'ground', 'front', [0, -1], v('frontLawn', 4));
      if (pathL) add('frontPath', pathL, 'surface', 'front', [0, -1], v('path', 3));
      // the edge along the pavement, open at the path and the driveway
      const edge = style === 'compact' ? 'lowWall' : style === 'flower' ? 'frontFence' : style === 'formal' ? 'hedge' : R(id, 'rail') < 0.5 ? 'postRail' : '';
      const ew = edge === 'hedge' ? HEDGE : edge === 'lowWall' ? WALL : FENCE;
      if (edge && front.d1 - front.d0 >= ew + 10) {
        const gaps = [pathL, driveL].filter((x): x is L => !!x).map((x) => [x.u0 - 2, x.u1 + 2] as [number, number]).sort((a, b) => a[0] - b[0]);
        let u = P.u0;
        for (const [g0, g1] of [...gaps, [P.u1, P.u1] as [number, number]]) {
          if (g0 - u >= 6) add(edge, { u0: u, u1: g0, d0: P.d0, d1: P.d0 + ew }, 'edge', 'front', [0, -1], v('edge', 3));
          u = Math.max(u, g1);
        }
        if (pathL && edge !== 'hedge') add('gate', { u0: pathL.u0 - 1, u1: pathL.u1 + 1, d0: P.d0, d1: P.d0 + FENCE }, 'edge', 'front', [0, -1], v('gate', 3), 'frontPath');
      }
      // a mailbox by the path, flower beds along the front wall (not across the path), a bush or two
      if (pathL && front.d1 - front.d0 >= 30) {
        const mu = pathL.u1 + 4;
        if (mu + 10 < B.u1) add('mailbox', { u0: mu, u1: mu + 10, d0: P.d0 + 16, d1: P.d0 + 24 }, 'object', 'front', [0, -1], v('mail', 4));
      }
      if (front.d1 - front.d0 >= 34 && (style === 'flower' || style === 'formal' || R(id, 'fbed') < 0.5)) {
        const bd0 = B.d0 - 14, bd1 = B.d0 - 2;
        if (pathL) {
          if (pathL.u0 - 4 - (B.u0 + 4) >= 16) add('flowerBed', { u0: B.u0 + 4, u1: pathL.u0 - 4, d0: bd0, d1: bd1 }, 'object', 'front', [0, -1], v('fb1', 6));
          if (B.u1 - 4 - (pathL.u1 + 4) >= 16) add('flowerBed', { u0: pathL.u1 + 4, u1: B.u1 - 4, d0: bd0, d1: bd1 }, 'object', 'front', [0, -1], v('fb2', 6));
        }
      }
    } else if (front) {
      // apartments and stand-in fronts: a paved forecourt
      add('gravel', front, 'ground', 'front', [0, -1], 1);
      if (front.d1 - front.d0 >= 12) add('frontPath', { u0: B.u0 + W / 2 - PATH_W, u1: B.u0 + W / 2 + PATH_W, d0: P.d0, d1: B.d0 }, 'surface', 'front', [0, -1], 2);
    }
    // the side strips beside the house: lawn (or the driveway), bins on the side away from the drive
    for (const side of ['u0', 'u1'] as const) {
      const sl: L = side === 'u0' ? { u0: P.u0, u1: B.u0, d0: B.d0, d1: B.d1 } : { u0: B.u1, u1: P.u1, d0: B.d0, d1: B.d1 };
      if (sl.u1 - sl.u0 < 2) continue;
      add(style === 'compact' && pc.kind !== 'home' ? 'gravel' : 'lawn', sl, 'ground', 'side', [0, -1], v('sideLawn' + side, 4));
    }

    // ---- the backyard
    const occ: L[] = [];
    const block = (l: L | null): void => { if (l) occ.push(l); };
    block(driveL && driveL.d1 > B.d1 ? { ...driveL, d0: B.d1 } : null); block(padL);
    // the ground: lawn near the house, a meadow at the back of a natural yard
    if (style === 'natural' && backDepth > 220 && home) {
      const cut = back.d0 + backDepth * 0.6;
      add('lawn', { ...back, d1: cut }, 'ground', 'back', [0, -1], v('lawn', 4));
      add('meadow', { ...back, d0: cut }, 'ground', 'back', [0, -1], v('meadow', 4));
    } else add(pc.kind === 'shop' ? 'gravel' : 'lawn', back, 'ground', 'back', [0, -1], v('lawn', 4));

    // fences: sides (from the front wall back; a boundary shared with the next parcel is fenced once) and the back
    const nb0 = neighbourAt(pc, 'u0'), nb1 = neighbourAt(pc, 'u1'), nbB = neighbourAt(pc, 'back');
    const backFenceKind = style === 'formal' && home ? 'hedge' : style === 'natural' ? 'postRail' : pc.kind === 'shop' ? 'backFence' : 'backFence';
    const bfw = backFenceKind === 'hedge' ? HEDGE : FENCE;
    const fenceD0 = Math.min(B.d1, B.d0 + (B.d1 - B.d0) / 2);
    const sideFence = (side: 'u0' | 'u1'): void => {
      const nb = side === 'u0' ? nb0 : nb1;
      if (nb && nb.index < pc.index) return;                           // the neighbour fences it
      const u0 = side === 'u0' ? P.u0 : P.u1 - FENCE, u1 = u0 + FENCE;
      const d1 = P.d1 - (nbB && nbB.index < pc.index ? 0 : bfw);
      // from the middle of the house's side back, or from its back corner where the house fills the lot's width
      const d0 = (side === 'u0' ? B.u0 - P.u0 : P.u1 - B.u1) >= FENCE + 0.01 ? fenceD0 : B.d1;
      add('sideFence', { u0, u1, d0, d1 }, 'edge', 'side', side === 'u0' ? [1, 0] : [-1, 0], v('sf', 3));
    };
    sideFence('u0'); sideFence('u1');
    if (!(nbB && nbB.index < pc.index)) add(backFenceKind, { u0: P.u0 + (nb0 && nb0.index < pc.index ? 0 : FENCE), u1: P.u1 - FENCE, d0: P.d1 - bfw, d1: P.d1 }, 'edge', 'back', [0, -1], v('bf', 3));
    // across each side strip at the back corner of the house: a gate (over the driveway, or a garden gate by the house)
    // and fence for the rest of the strip
    for (const side of ['u0', 'u1'] as const) {
      const sw = side === 'u0' ? leftW : rightW;
      if (sw < 20 || pc.kind === 'shop') continue;
      const d0 = Math.max(B.d0 + 4, B.d1 - 30), d1 = d0 + FENCE;
      const s0 = side === 'u0' ? P.u0 + FENCE : B.u1, s1 = side === 'u0' ? B.u0 : P.u1 - FENCE;
      const onDrive = driveSide === side && driveL !== null;
      const g0 = onDrive ? driveL!.u0 : side === 'u0' ? Math.max(s0, s1 - 36) : s0;
      const g1 = onDrive ? driveL!.u1 : side === 'u0' ? s1 : Math.min(s1, s0 + 36);
      add('sideGate', { u0: g0, u1: g1, d0, d1 }, 'edge', 'side', [0, -1], v('sg' + side, 3) + (onDrive ? 5 : 0), onDrive ? 'driveway' : undefined);
      if (g0 - s0 >= 4) add('sideFence', { u0: s0, u1: g0, d0, d1 }, 'edge', 'side', [0, -1], v('sgf' + side, 3));
      if (s1 - g1 >= 4) add('sideFence', { u0: g1, u1: s1, d0, d1 }, 'edge', 'side', [0, -1], v('sgf' + side, 3));
    }
    const inner: L = { u0: P.u0 + fenceIn, u1: P.u1 - fenceIn, d0: back.d0, d1: P.d1 - bfw - 2 };

    /** Puts an object of w x h (along x deep) in the backyard where `score` likes best (free, inside `region`). */
    const place = (kind: string, w: number, h: number, score: (c: { u: number; d: number; t: number; s: number }) => number,
      opts: { region?: L; turn?: boolean; gap?: number; layer?: Layer; face?: [number, number]; variant?: number; key?: string } = {}): L | undefined => {
      const region = opts.region ?? inner, gap = opts.gap ?? 6;
      let best: { l: L; sc: number } | undefined;
      const dims: [number, number][] = opts.turn ? [[w, h], [h, w]] : [[w, h]];
      for (const [ww, hh] of dims) {
        for (let u = region.u0; u + ww <= region.u1 + 1e-6; u += 6) {
          for (let d = region.d0; d + hh <= region.d1 + 1e-6; d += 6) {
            const l: L = { u0: u, u1: u + ww, d0: d, d1: d + hh };
            if (occ.some((o) => lhit(l, o, gap))) continue;
            const cu = u + ww / 2, cd = d + hh / 2;
            const t = (cd - inner.d0) / Math.max(1, inner.d1 - inner.d0);
            const s = (cu - inner.u0) / Math.max(1, inner.u1 - inner.u0);
            const sc = score({ u: cu, d: cd, t, s }) + 0.08 * R(id, 'jit', kind, u, d);
            if (!best || sc > best.sc) best = { l, sc };
          }
        }
      }
      if (!best) return undefined;
      occ.push(best.l);
      add(kind, best.l, opts.layer ?? 'object', 'back', opts.face ?? [0, -1], opts.variant ?? v(opts.key ?? kind));
      return best.l;
    };
    /** 0 on the driveway's side of the yard, 1 on the far side. */
    const away = (s: number): number => (driveSide === 'u1' ? 1 - s : driveSide === 'u0' ? s : 0.5);
    const near = (t: number, want: number): number => -Math.abs(t - want);

    if (pc.kind === 'shop') {
      place('bins', 60, 26, (c) => near(c.t, 0.05) + 0.3 * near(c.s, 0.9));
      place('pallets', 48, 40, (c) => near(c.t, 0.1) + 0.3 * near(c.s, 0.1), { turn: true });
      if (backDepth >= 200) place('parkingPad', 80, 150, (c) => near(c.t, 0.4) + 0.2 * near(c.s, 0.5), { layer: 'surface', key: 'pad' });
      if (R(id, 'tree') < 0.6) place('tree', 60, 60, (c) => near(c.t, 0.95) + near(c.s, R(id, 'ts')), { key: 'tree' });
    } else if (flats) {
      // a shared garden: bins by the house, a bike shed, washing lines, benches, a tree or two
      const pat = place('patio', Math.min(W * 0.6, 140), 50, (c) => near(c.t, 0) + near(c.s, 0.5), { layer: 'surface', gap: 0 });
      place('bins', 90, 26, (c) => near(c.t, 0) + 0.5 * near(c.s, 0.95));
      place('bikeShed', 90, 50, (c) => near(c.t, 0.15) + 0.5 * near(c.s, 0.05), { face: [1, 0] });
      for (let i = 0; i < 2; i++) place('washingLine', 70, 70, (c) => near(c.t, 0.55) + 0.3 * near(c.s, 0.35 + 0.3 * i));
      place('bench', 50, 18, (c) => near(c.t, 0.3) + near(c.s, 0.75), { face: [0, -1] });
      place('playSet', 120, 80, (c) => near(c.t, 0.75) + near(c.s, 0.5));
      for (let i = 0; i < 3; i++) place('tree', 64, 64, (c) => near(c.t, 1) + near(c.s, (i + 0.5) / 3), { key: 'tree' + i });
      for (let i = 0; i < 4; i++) place('bush', 28, 28, (c) => -Math.min(c.s, 1 - c.s) + 0.3 * c.t, { key: 'bush' + i });
      if (pat) {
        const cu = (pat.u0 + pat.u1) / 2, cd = (pat.d0 + pat.d1) / 2;
        add('table', { u0: cu - 20, u1: cu + 20, d0: cd - 16, d1: cd + 16 }, 'object', 'back', [0, 1], v('table', 4), 'patio');
      }
    } else {
      // the patio or deck at the back door, the table, chairs and grill on it
      const deck = style === 'natural' || (style === 'flower' && R(id, 'deck') < 0.5) || pc.key.startsWith('house_small_') || pc.key.startsWith('house_medium_') && R(id, 'deck') < 0.6;
      const pw = Math.min(W * 0.6, rich ? 180 : 130), pd = rich ? 80 : 60;
      const pu0 = Math.max(B.u0, Math.min(B.u1 - pw, doorU - pw / 2));
      let patL: L | null = { u0: pu0, u1: pu0 + pw, d0: B.d1, d1: B.d1 + pd };
      if (occ.some((o) => lhit(patL!, o, 2)) || !inside(patL, inner)) patL = null;
      if (patL) {
        add(deck ? 'deck' : 'patio', patL, 'surface', 'back', [0, 1], v('patio', 4));
        occ.push(patL);
        const cu = (patL.u0 + patL.u1) / 2, cd = (patL.d0 + patL.d1) / 2 + 4;
        add('table', { u0: cu - 20, u1: cu + 20, d0: cd - 16, d1: cd + 16 }, 'object', 'back', [0, 1], v('table', 4), deck ? 'deck' : 'patio');
        const chairs = Math.min(6, Math.max(2, people.length));
        const seats: [number, number][] = [[-34, 0], [34, 0], [0, -30], [0, 30], [-34, -20], [34, 20]];
        for (let i = 0; i < chairs; i++) {
          const [du, dd] = seats[i]!;
          const c: L = { u0: cu + du - 8, u1: cu + du + 8, d0: cd + dd - 8, d1: cd + dd + 8 };
          if (inside(c, patL)) add('chair', c, 'object', 'back', [-Math.sign(du), -Math.sign(dd)], v('chair', 4), deck ? 'deck' : 'patio');
        }
        const gu = patL.u1 - 20 > cu + 46 ? patL.u1 - 24 : null;
        if (gu !== null) add('grill', { u0: gu, u1: gu + 18, d0: patL.d0 + 6, d1: patL.d0 + 20 }, 'object', 'back', [0, 1], v('grill', 4), deck ? 'deck' : 'patio');
        else if (R(id, 'grill') < 0.8) { const pl = patL; place('grill', 18, 14, (c) => -Math.hypot(c.u - (c.u < cu ? pl.u0 : pl.u1), c.d - (pl.d0 + 20)) / 50, { gap: 3 }); }
      }
      // bins: by the side of the house away from the drive (or the driveway's side by the pad if that is all there is)
      const binSide = driveSide === 'u1' ? 'u0' : 'u1';
      const sw = binSide === 'u0' ? leftW : rightW;
      if (sw >= 34) {
        const u0 = binSide === 'u0' ? P.u0 + fenceIn + 2 : P.u1 - fenceIn - 28;
        const bl: L = { u0, u1: u0 + 26, d0: B.d1 - 30 - 64, d1: B.d1 - 34 };
        add('bins', bl, 'object', 'side', binSide === 'u0' ? [1, 0] : [-1, 0], v('bins', 4)); occ.push(bl);
      } else place('bins', 56, 24, (c) => near(c.t, 0) + 0.4 * near(c.s, driveSide === 'u0' ? 0 : 1), { key: 'bins' });
      // the pool for the rich, with room
      const wantPool = home && backDepth >= 260 && (inner.u1 - inner.u0) >= 170 &&
        (pc.tier === 'villa' || pc.tier === 'estate' ? R(id, 'pool') < 0.85 : wealth >= 400 ? R(id, 'pool') < (style === 'formal' ? 0.6 : 0.35) : wealth >= 250 && style === 'formal' ? R(id, 'pool') < 0.2 : false);
      let pool = false;
      if (wantPool) {
        const pdk = place('poolDeck', 150, 230, (c) => near(c.t, 0.45) + 0.3 * near(c.s, away(0.5) ? 0.5 : 0.5), { layer: 'surface', turn: true, key: 'pool' });
        if (pdk) {
          add('pool', { u0: pdk.u0 + 18, u1: pdk.u1 - 18, d0: pdk.d0 + 18, d1: pdk.d1 - 18 }, 'object', 'back', [0, -1], v('poolShape', 3), 'poolDeck');
          pool = true;
          if (R(id, 'lounger') < 0.9) add('chair', { u0: pdk.u0 + 2, u1: pdk.u0 + 16, d0: pdk.d0 + 30, d1: pdk.d0 + 64 }, 'object', 'back', [1, 0], 9, 'poolDeck');
        }
      }
      // the shed in a back corner, away from the drive
      if (backDepth >= 120) place('shed', 70, 58, (c) => 1.2 * c.t + 0.8 * away(c.s) - (Math.min(c.s, 1 - c.s) > 0.1 ? 0.3 : 0), { face: [0, -1] });
      // children
      if (children > 0 && backDepth >= 150) {
        const yng = people.filter((q) => age(q!.birthYear) < 7).length;
        if (R(id, 'tramp') < 0.55 || children >= 2) place('trampoline', 96, 96, (c) => near(c.t, 0.55) + 0.4 * away(c.s));
        if (R(id, 'play') < 0.7 || children >= 2) place('playSet', 120, 70, (c) => near(c.t, 0.7) + 0.3 * near(c.s, 0.5), { turn: true });
        if (yng > 0) place('sandpit', 48, 48, (c) => near(c.t, 0.3) + 0.2 * away(c.s));
        if (R(id, 'swing') < 0.3) place('swing', 60, 30, (c) => near(c.t, 0.85));
      }
      // gardeners: vegetable beds in the sun at the back, a compost bin, a greenhouse if there is room
      if (gardener && backDepth >= 140) {
        const nBeds = 2 + Math.floor(R(id, 'beds') * 3);
        for (let i = 0; i < nBeds; i++) place('vegBed', 36, 96, (c) => 0.8 * c.t + 0.5 * near(c.s, 0.25 + 0.15 * i), { key: 'veg' + i });
        place('compost', 30, 30, (c) => c.t + 0.5 * away(c.s) - 0.5 * Math.min(c.s, 1 - c.s));
        if (backDepth >= 220 && R(id, 'greenhouse') < 0.6) place('greenhouse', 70, 110, (c) => 0.7 * c.t + 0.4 * near(c.s, 0.2), { turn: true, face: [1, 0] });
        if (R(id, 'fruit') < 0.8) for (let i = 0; i < 2; i++) place('fruitTree', 56, 56, (c) => c.t + near(c.s, i ? 0.9 : 0.1), { key: 'fruit' + i });
      }
      // the washing line, mostly not by the rich
      if (!rich || R(id, 'line') < 0.3) {
        if (R(id, 'rotary') < 0.6) place('washingLine', 64, 64, (c) => near(c.t, 0.35) + 0.4 * away(c.s), { key: 'line' });
        else place('washingLine', 16, 140, (c) => near(c.t, 0.4) + 0.4 * near(c.s, 0.15), { turn: true, key: 'line' });
      }
      // by style
      if (style === 'natural') {
        if (R(id, 'fire') < 0.7) place('firePit', 40, 40, (c) => near(c.t, 0.65) + 0.3 * near(c.s, 0.5));
        if (R(id, 'logs') < 0.6) place('logPile', 50, 20, (c) => c.t + 0.5 * away(c.s), { turn: true });
        if (R(id, 'hammock') < 0.35) place('hammock', 30, 100, (c) => near(c.t, 0.8), { turn: true });
      }
      if (style === 'flower') {
        place('birdbath', 16, 16, (c) => near(c.t, 0.5) + near(c.s, 0.5));
        if (R(id, 'pond') < 0.4 && backDepth >= 200) place('pond', 70, 50, (c) => near(c.t, 0.6) + 0.3 * away(c.s));
        // beds along both side fences
        for (const s of [0, 1]) place('flowerBed', 20, Math.min(160, backDepth * 0.5), (c) => -Math.abs(c.s - s) * 3 + near(c.t, 0.3), { key: 'fbs' + s, gap: 2 });
      }
      if (style === 'formal') {
        if (rich && R(id, 'gazebo') < 0.6 && backDepth >= 260) place('gazebo', 90, 90, (c) => c.t + 0.4 * away(c.s));
        for (let i = 0; i < 4; i++) place('bush', 26, 26, (c) => near(c.t, 0.05) - 2 * Math.min(Math.abs(c.s - 0.02), Math.abs(c.s - 0.98)), { key: 'topiary' + i });
        place('bench', 50, 18, (c) => c.t, { face: [0, -1] });
      }
      if (style === 'compact') {
        place('planter', 40, 16, (c) => near(c.t, 0.05) + near(c.s, 0.9));
        if (R(id, 'bench') < 0.5) place('bench', 50, 18, (c) => near(c.t, 0.6), { face: [0, -1] });
      }
      if (R(id, 'dog') < 0.25) place('doghouse', 32, 36, (c) => near(c.t, 0.25) + 0.3 * away(c.s), { face: [0, -1] });
      // a wide side strip (the parcel widened into the gap beside the house) gets a tree and bushes of its own
      for (const side of ['u0', 'u1'] as const) {
        if (side === driveSide) continue;
        const sl: L = side === 'u0' ? { u0: P.u0 + fenceIn, u1: B.u0 - 6, d0: B.d0 + 4, d1: B.d1 - 38 } : { u0: B.u1 + 6, u1: P.u1 - fenceIn, d0: B.d0 + 4, d1: B.d1 - 38 };
        if (sl.u1 - sl.u0 < 70 || sl.d1 - sl.d0 < 30) continue;
        const sz = Math.min(64, sl.d1 - sl.d0);
        place('tree', sz, sz, (c) => -Math.abs(c.u - (side === 'u0' ? sl.u0 : sl.u1)) / 100, { region: sl, key: 'stree' + side, gap: 4 });
        for (let i = 0; i < 2; i++) place('bush', 26, 26, (c) => -Math.abs(c.u - (sl.u0 + sl.u1) / 2) / 100 + 0.1 * i, { region: sl, key: 'sbush' + side + i, gap: 4 });
      }
      // trees along the back, bushes along the sides
      // (more in a big yard: one more for every 250 x 250 of it beyond the first)
      const extra = Math.floor(((inner.u1 - inner.u0) * (inner.d1 - inner.d0)) / 62500) - 1;
      const nTrees = Math.max(0, extra) + (style === 'natural' ? 3 + Math.floor(R(id, 'nt') * 3) : style === 'compact' ? Math.floor(R(id, 'nt') * 2) : 1 + Math.floor(R(id, 'nt') * 3));
      for (let i = 0; i < nTrees; i++) {
        const sz = 50 + Math.floor(R(id, 'tsz', i) * 30);
        place('tree', sz, sz, (c) => (style === 'natural' ? 0.6 : 1) * c.t + near(c.s, R(id, 'tpos', i)) * 0.8, { key: 'tree' + i, gap: 4 });
      }
      const nBush = style === 'compact' ? 2 : 3 + Math.floor(R(id, 'nb') * 4);
      for (let i = 0; i < nBush; i++) place('bush', 24 + Math.floor(R(id, 'bsz', i) * 12), 24 + Math.floor(R(id, 'bsz', i) * 12), (c) => -2 * Math.min(c.s, 1 - c.s) + 0.3 * near(c.t, R(id, 'bpos', i)), { key: 'bush' + i, gap: 4 });
      yards.push({
        lot: id, sprite: pc.sprite, row: pc.row.id, neighbourhood: pc.neighbourhood, style, kind: pc.tier,
        parcel: parcelRect(pc), house: f.rect(B.u0, B.u1, B.d0, B.d1), front: front ? f.rect(front.u0, front.u1, front.d0, front.d1) : null, back: f.rect(back.u0, back.u1, back.d0, back.d1),
        household: { residents: people.length, children, elders, gardener, wealth, pool, drive },
        items,
      });
      continue;
    }
    yards.push({
      lot: id, sprite: pc.sprite, row: pc.row.id, neighbourhood: pc.neighbourhood, style, kind: pc.tier,
      parcel: parcelRect(pc), house: f.rect(B.u0, B.u1, B.d0, B.d1), front: front ? f.rect(front.u0, front.u1, front.d0, front.d1) : null, back: f.rect(back.u0, back.u1, back.d0, back.d1),
      household: { residents: people.length, children, elders, gardener, wealth, pool: false, drive },
      items,
    });
  }

  // ------------------------------------------------------------------------------------------- lanes and verges
  const lanes = backLanes(g, parcels.map(parcelRect), parcels.map((p) => p.f), parcels.map((p) => p.p), fixed, bodies, roads, R);
  // gaps left along a row between parcels: verges
  const taken: Rect4[] = [...parcels.map(parcelRect), ...lanes.map((l) => l.rect)];
  for (const row of graph.rows) {
    const mine = parcels.filter((p) => p.row === row).sort((a, b) => a.p.u0 - b.p.u0);
    for (let i = 1; i < mine.length; i++) {
      const a = mine[i - 1]!, b = mine[i]!;
      if (b.p.u0 - a.p.u1 < 20) continue;
      const d1 = Math.min(a.p.d1, b.p.d1, 240);
      const f = b.f;
      const r = f.rect(a.p.u1, b.p.u0, 0, d1);
      if (fixed.some((o) => hit(r, o)) || bodies.some((o) => hit(r, o)) || taken.some((o) => hit(r, o))) continue;
      const items: YardItem[] = [];
      const len = b.p.u0 - a.p.u1;
      const n = Math.floor(len / 110);
      for (let k = 0; k < n; k++) {
        const u = a.p.u1 + (k + 0.5) * len / n, sz = 56;
        if (len < 70) break;
        items.push({ kind: 'tree', rect: f.rect(u - sz / 2, u + sz / 2, 60, 60 + sz), face: f.face(0, -1), variant: Math.floor(R('verge', row.id, i, k) * 10), layer: 'object', zone: 'street' });
      }
      lanes.push({ id: `verge:${row.id}:${i}`, kind: 'verge', rect: r, axis: Math.abs(row.along[0]) > 0.5 ? 'x' : 'z', reaches: 0, items });
      taken.push(r);
    }
  }

  // props (the forest's trees and bushes) standing in a parcel or a lane belong to it now
  const occupied = [...parcels.map(parcelRect), ...lanes.map((l) => l.rect)];
  let dropped = 0;
  const props = graph.props.filter((p) => {
    const inIt = occupied.some((r) => p.x > r[0] && p.x < r[2] && p.z > r[1] && p.z < r[3]);
    if (inIt) dropped++;
    return !inIt;
  });
  return { yards, lanes, props, propsDropped: dropped };
}

/** Back lanes and verges: strips between two backyards that face each other across free ground. */
function backLanes(g: TownGraph, parcels: Rect4[], frames: Frame[], local: L[], fixed: Rect4[], bodies: Rect4[], roads: Rect4[], R: (...k: (string | number)[]) => number): LaneJson[] {
  type Cand = { axis: 'x' | 'z'; lo: number; hi: number; a: number; b: number };
  const cands: Cand[] = [];
  for (let i = 0; i < parcels.length; i++) {
    const f = frames[i]!, n = f.n, P = parcels[i]!;
    // the back edge in world terms: along the axis of n, at the parcel's far side
    const backC = n[0] !== 0 ? (n[0] > 0 ? P[2] : P[0]) : (n[1] > 0 ? P[3] : P[1]);
    const axisN: 'x' | 'z' = n[0] !== 0 ? 'x' : 'z';
    const sgn = (n[0] || n[1]) > 0 ? 1 : -1;
    const span: [number, number] = axisN === 'x' ? [P[1], P[3]] : [P[0], P[2]];
    let best: { gap: number; j: number } | undefined;
    for (let j = 0; j < parcels.length; j++) {
      if (j === i) continue;
      const fj = frames[j]!;
      if (fj.n[0] !== -n[0] || fj.n[1] !== -n[1]) continue;
      const Q = parcels[j]!;
      const backJ = axisN === 'x' ? (sgn > 0 ? Q[0] : Q[2]) : (sgn > 0 ? Q[1] : Q[3]);
      const gap = (backJ - backC) * sgn;
      const spanJ: [number, number] = axisN === 'x' ? [Q[1], Q[3]] : [Q[0], Q[2]];
      const ov = Math.min(span[1], spanJ[1]) - Math.max(span[0], spanJ[0]);
      if (gap <= 1 || gap > 400 || ov < 40) continue;
      if (!best || gap < best.gap) best = { gap, j };
    }
    if (!best) continue;
    const Q = parcels[best.j]!;
    const spanJ: [number, number] = axisN === 'x' ? [Q[1], Q[3]] : [Q[0], Q[2]];
    const lo = Math.max(span[0], spanJ[0]), hi = Math.min(span[1], spanJ[1]);
    const a = sgn > 0 ? backC : backC - best.gap, b = a + best.gap;
    // the lane runs perpendicular to n: along the other axis
    cands.push({ axis: axisN === 'x' ? 'z' : 'x', lo, hi, a, b });
  }
  void local; void R;
  // merge the candidates that share both edges and overlap or touch along the lane
  const key = (c: Cand): string => `${c.axis}:${c.a.toFixed(1)}:${c.b.toFixed(1)}`;
  const groups = new Map<string, Cand[]>();
  for (const c of cands) { const k = key(c); groups.set(k, [...(groups.get(k) ?? []), c]); }
  const rectOf = (axis: 'x' | 'z', lo: number, hi: number, a: number, b: number): Rect4 => (axis === 'x' ? [lo, a, hi, b] : [a, lo, b, hi]);
  const blocked = (r: Rect4): boolean => parcels.some((p) => hit(r, p)) || bodies.some((p) => hit(r, p)) || fixed.filter((f) => !roads.includes(f)).some((p) => hit(r, p));
  const out: LaneJson[] = [];
  const ks = [...groups.keys()].sort();
  for (const k of ks) {
    const cs = groups.get(k)!.sort((x, y) => x.lo - y.lo);
    const merged: Cand[] = [];
    for (const c of cs) {
      const m = merged[merged.length - 1];
      if (m && c.lo <= m.hi + 1 && !blocked(rectOf(c.axis, m.hi, c.lo, c.a, c.b))) m.hi = Math.max(m.hi, c.hi);
      else merged.push({ ...c });
    }
    for (const m of merged) {
      if (blocked(rectOf(m.axis, m.lo, m.hi, m.a, m.b))) continue;
      // reach for a street at each end, through free ground
      let reaches = 0;
      for (const dir of [-1, 1]) {
        const step = 8;
        let t = 0, found = false;
        while (t < 700) {
          const r = dir < 0 ? rectOf(m.axis, m.lo - t - step, m.lo - t, m.a, m.b) : rectOf(m.axis, m.hi + t, m.hi + t + step, m.a, m.b);
          if (roads.some((q) => hit(r, q))) { found = true; break; }
          if (blocked(r)) break;
          t += step;
        }
        if (found) { reaches++; if (dir < 0) m.lo -= t; else m.hi += t; }
      }
      const width = m.b - m.a;
      const lane = reaches > 0 && width >= LANE_MIN;
      if (lane && width > LANE_W + 40) {
        const c = (m.a + m.b) / 2;
        out.push({ id: `lane:${out.length}`, kind: 'lane', rect: rectOf(m.axis, m.lo, m.hi, c - LANE_W / 2, c + LANE_W / 2), axis: m.axis, reaches, items: [] });
        out.push({ id: `verge:${out.length}`, kind: 'verge', rect: rectOf(m.axis, m.lo, m.hi, m.a, c - LANE_W / 2), axis: m.axis, reaches, items: [] });
        out.push({ id: `verge:${out.length}`, kind: 'verge', rect: rectOf(m.axis, m.lo, m.hi, c + LANE_W / 2, m.b), axis: m.axis, reaches, items: [] });
      } else out.push({ id: `${lane ? 'lane' : 'verge'}:${out.length}`, kind: lane ? 'lane' : 'verge', rect: rectOf(m.axis, m.lo, m.hi, m.a, m.b), axis: m.axis, reaches, items: [] });
    }
  }
  void g;
  return out;
}

/** street.json v2 with the yards: `graph.yards`, `graph.lanes`, and `graph.props` less those now in a yard or lane. */
export function withYards(g: TownGraph): TownGraph['json'] {
  const y = townYards(g);
  return { ...g.json, graph: { ...g.graph, props: y.props, yards: y.yards, lanes: y.lanes } };
}
