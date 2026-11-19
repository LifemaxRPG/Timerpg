import { rand } from '../sim/rng';
import { originOf, type TownSite } from '../world/map';
import { alignment, linksAt, otherEnd, stationAt, trackIn, type RailLink, type RailWorld, type Pt, type StationStatus } from '../world/rail';
import { planFarms, roadRect, RING } from './farms';
import { planTown, type TownPlan } from './plan';
import type { Rect, Road } from './kinds';

// THE RAILROAD IN TOWN: where the world's lines (src/world/rail.ts) enter and leave a town, the track between them (a
// path round the town's lots, landmarks and plaza, over the streets it must cross and the river on a bridge, drawn
// towards downtown), the level crossings where it meets a street, the depot beside the track nearest the plaza, a
// freight siding and the site of a grain elevator or mill beside it. Pure in the world and the town's cell; computed
// from the finished plan (src/town/plan.ts is not changed: the track threads the town as planned, through its
// woodland edge and the gaps between its blocks), and on out through the farmland round it (src/town/farms.ts: RING
// tiles past the map) to the farmland's edge: round the farmsteads, over the country roads square (they are the roads
// out of town), across a field only where it must (straight, splitting the field in two: `fieldCuts`), the river past
// the map on a bridge. Everything in tiles of the plan (x east, y south; the farmland reaches below 0 and past the map).

/** Tiles kept clear between the track's centre line and any lot, landmark or the plaza. */
export const RAIL_CLEAR = 3;
/** The corridor's half width (ballast, ties and the shoulders: no tree, prop or yard thing stands in it). */
export const RAIL_HALF = 2.5;

export interface TownTrack {
  /** The world link it belongs to (`ew:cx,cy` ...); a through track is split where it changes link (at the depot). */
  link: string;
  kind: 'main' | 'branch' | 'siding';
  /** The centre line, plan tiles. */
  pts: Pt[];
}

export type CrossingGear = 'crossbuck' | 'lights' | 'gates';

export interface LevelCrossing {
  /** The crossing's middle (plan tiles) and the track's direction there (unit). */
  x: number;
  y: number;
  dx: number;
  dy: number;
  /** Index into plan.roads, or past its end into [TownRail.roadsOut] (the road out of town). */
  road: number;
  link: string;
  /** From when each kind of protection stands (crossbucks from the line's opening). */
  lightsFrom: number;
  gatesFrom?: number;
}

export interface TownRail {
  siteId: string;
  /** Every track ever laid in the town (which are there in a year: [railIn]). */
  tracks: TownTrack[];
  crossings: LevelCrossing[];
  /** Where each link leaves the plan (plan tiles), and the world alignment beyond it (world tiles, from the plan's edge
   * outwards to the next town's line point). */
  portals: { link: string; to: string; at: Pt; beyond: Pt[] }[];
  /** The depot: its rect (plan tiles), the point on the track in front of it, the track's direction there, and which
   * side it stands (+1: left of the track's direction, -1 right). Null in a town without a railroad. */
  depot: { rect: Rect; at: Pt; dx: number; dy: number; side: 1 | -1; built: number } | null;
  /** A grain elevator or mill site at the end of the siding (plan tiles), or null. */
  elevator: Rect | null;
  /** The roads out of town: the farms' country roads (src/town/farms.ts: out from the streets' ends, and the section
   * road round the town), as rects in plan tiles; crossings' road indices past plan.roads point here. */
  roadsOut: (Rect & { horizontal: boolean; busy: boolean })[];
  /** The ground the town's track is laid on (plan tiles: the map and the farmland round it); the portals are on its edge. */
  bounds: Rect;
  /** The fields the track runs through, each split in two (or more) along it: the pieces left either side (plan tiles). */
  fieldCuts: { field: string; pieces: Rect[] }[];
  /** What the track could not avoid (a lot or landmark it runs over): empty when all went well. */
  conflicts: string[];
}

const CELL = 2; // the path search's cell, tiles
/** Tiles kept between the track's centre line and a farmstead's yard (the barns stand back from the trains). */
const FARM_CLEAR = 6;
/** Tiles cut out of a field either side of the track's centre line (the fence stands this far off). */
export const FIELD_CUT = 3;

/** The railroad in the town of [site] (plan given or planned here). */
export function townRail(w: RailWorld, site: TownSite, plan: TownPlan = planTown(site.seed), extra: readonly Rect[] = []): TownRail {
  const links = linksAt(w, site.cx, site.cy);
  // the farmland round the town: the track is laid out to its edge (the search's grid starts PAD tiles before the plan's 0)
  const fp = planFarms(plan);
  const PAD = RING;
  const out: TownRail = { siteId: site.id, tracks: [], crossings: [], portals: [], depot: null, elevator: null, roadsOut: [], bounds: { x: -PAD, y: -PAD, w: plan.width + 2 * PAD, h: plan.height + 2 * PAD }, fieldCuts: [], conflicts: [] };
  if (!links.length) return out;
  const W = plan.width + 2 * PAD, H = plan.height + 2 * PAD;
  const sh = <T extends Rect>(r: T): T => ({ ...r, x: r.x + PAD, y: r.y + PAD });
  // (whole tiles: the farms' rects may sit on half tiles)
  const shI = (r: Rect): Rect => { const x0 = Math.floor(r.x + PAD), y0 = Math.floor(r.y + PAD); return { x: x0, y: y0, w: Math.ceil(r.x + r.w + PAD) - x0, h: Math.ceil(r.y + r.h + PAD) - y0 }; };
  const lots = plan.candidates.map(sh), marks = plan.landmarks.map(sh), plaza = sh(plan.plaza);
  const o0 = originOf(site, plan);
  const o = { x: o0.x - PAD, y: o0.y - PAD };
  const toPlan = (p: Pt): Pt => ({ x: p.x - o.x, y: p.y - o.y });

  // ---- what the track may not cross (lots, landmarks, plaza, kept RAIL_CLEAR off; farmsteads FARM_CLEAR off), and what
  // it pays for (water, roads, fields)
  const blocked = new Uint8Array(W * H);
  const markRect = (r: Rect, pad: number): void => {
    for (let y = Math.max(0, r.y - pad); y < Math.min(H, r.y + r.h + pad); y++) for (let x = Math.max(0, r.x - pad); x < Math.min(W, r.x + r.w + pad); x++) blocked[y * W + x] = 1;
  };
  // (hard: the plots themselves and a tile round them; the rounding of the corners may cut into the clearance, not these)
  const hard = new Uint8Array(W * H);
  const markHard = (r: Rect): void => {
    for (let y = Math.max(0, r.y - 1); y < Math.min(H, r.y + r.h + 1); y++) for (let x = Math.max(0, r.x - 1); x < Math.min(W, r.x + r.w + 1); x++) hard[y * W + x] = 1;
  };
  // ([extra]: more ground to keep off, as the plan's plots: the 3D town's yards, which reach past their plots)
  for (const r of [...lots, ...marks, plaza, ...extra.map(shI)]) { markRect(r, RAIL_CLEAR); markHard(r); }
  for (const f of fp.farms) { const r = shI(f.rect); markRect(r, FARM_CLEAR); markHard(r); }
  // the fields (a tile round each for the fence): crossed only where it must be, straight
  const fieldAt = new Int16Array(W * H).fill(-1);
  const fields = fp.fields.map(shI);
  fields.forEach((r, i) => { for (let y = Math.max(0, r.y - 1); y < Math.min(H, r.y + r.h + 1); y++) for (let x = Math.max(0, r.x - 1); x < Math.min(W, r.x + r.w + 1); x++) fieldAt[y * W + x] = i; });
  const roadAt = new Int16Array(W * H).fill(-1);
  // the roads out of town (the farms' country roads: out from the streets' ends, and the section road round the town):
  // the line crosses them like any street, square
  const mainRoot = (i: number): boolean => i >= 0 && !!plan.roads[i]?.main;
  out.roadsOut = fp.roads.map((r) => ({ ...roadRect(r, 2), horizontal: r.a[1] === r.b[1], busy: r.kind === 'spoke' && mainRoot(r.root) }));
  const roads: Road[] = [...plan.roads.map(sh), ...out.roadsOut.map((r) => ({ ...shI(r), main: r.busy, horizontal: r.horizontal }))];
  const busyRoad = (i: number): boolean => roads[i]!.main;
  roads.forEach((r, i) => { for (let y = Math.max(0, r.y); y < Math.min(H, r.y + r.h); y++) for (let x = Math.max(0, r.x); x < Math.min(W, r.x + r.w); x++) roadAt[y * W + x] = i; });
  // (a road's own tiles are never blocked: a lot's clearance reaching over a street is not what stops a crossing)
  for (let i = 0; i < W * H; i++) if (roadAt[i]! >= 0) { blocked[i] = 0; fieldAt[i] = -1; }
  const farmWater = fp.water.map(shI);
  const wet = (x: number, y: number): boolean => {
    const px = x - PAD, py = y - PAD;
    if (px >= 0 && py >= 0 && px < plan.width && py < plan.height) return !!plan.water && plan.water.raw[py * plan.width + px] === 1;
    return farmWater.some((r) => x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h);
  };

  // ---- the portals: where each link's alignment crosses into the plan
  const portal = (l: RailLink): { at: Pt; beyond: Pt[] } => {
    const al = alignment(w, l, 64);
    const pts = l.a === site.id ? al : [...al].reverse(); // from this town outwards
    const inside = (p: Pt): boolean => { const q = toPlan(p); return q.x >= 1 && q.y >= 1 && q.x <= W - 2 && q.y <= H - 2; };
    let i = pts.length - 1;
    while (i > 0 && !inside(pts[i]!)) i--;
    // (on the edge the line faces; an alignment that never enters lands on the nearest edge point)
    const q = toPlan(pts[i]!);
    const at = { x: Math.min(W - 2, Math.max(1, q.x)), y: Math.min(H - 2, Math.max(1, q.y)) };
    if (l.dir === 'ew') at.x = l.a === site.id ? W - 2 : 1; else at.y = l.a === site.id ? H - 2 : 1;
    const beyond = pts.slice(i + 1);
    return { at, beyond: [{ x: at.x + o.x, y: at.y + o.y }, ...beyond] };
  };
  const ports = new Map(links.map((l) => [l.id, portal(l)]));
  for (const l of links) out.portals.push({ link: l.id, to: otherEnd(l, site.id), at: ports.get(l.id)!.at, beyond: ports.get(l.id)!.beyond });

  // ---- the path search: 8 directions on a 2-tile grid, turns cost, roads crossed square, drawn towards downtown
  const GW = Math.ceil(W / CELL), GH = Math.ceil(H / CELL);
  const cellBlocked = new Uint8Array(GW * GH), cellRoad = new Int16Array(GW * GH).fill(-1), cellWet = new Uint8Array(GW * GH), cellField = new Uint8Array(GW * GH);
  for (let gy = 0; gy < GH; gy++) for (let gx = 0; gx < GW; gx++) {
    for (let y = gy * CELL; y < Math.min(H, gy * CELL + CELL); y++) for (let x = gx * CELL; x < Math.min(W, gx * CELL + CELL); x++) {
      if (blocked[y * W + x]) cellBlocked[gy * GW + gx] = 1;
      if (roadAt[y * W + x]! >= 0) cellRoad[gy * GW + gx] = roadAt[y * W + x]!;
      if (wet(x, y)) cellWet[gy * GW + gx] = 1;
      if (fieldAt[y * W + x]! >= 0) cellField[gy * GW + gx] = 1;
    }
  }
  const taken = new Uint8Array(GW * GH); // cells of tracks already laid (a second line crosses them, never runs along them)
  // the built-up area (plots, landmarks, plaza, a little round them): inside it the line keeps to the town's grid (runs
  // east-west or north-south, turns square, never near a street, so every crossing in town is square); outside it, in
  // the woods and fields, it may curve as it likes
  let bx0 = Infinity, by0 = Infinity, bx1 = -Infinity, by1 = -Infinity;
  for (const r of [...lots, ...marks, plaza]) { bx0 = Math.min(bx0, r.x); by0 = Math.min(by0, r.y); bx1 = Math.max(bx1, r.x + r.w); by1 = Math.max(by1, r.y + r.h); }
  const BOX_PAD = 4;
  const inBox = (x: number, y: number): boolean => x >= bx0 - BOX_PAD && x <= bx1 + BOX_PAD && y >= by0 - BOX_PAD && y <= by1 + BOX_PAD;
  const cellIn = (gx: number, gy: number): boolean => inBox(gx * CELL + CELL / 2, gy * CELL + CELL / 2);
  const nearRoadCell = new Uint8Array(GW * GH);
  for (let gy = 0; gy < GH; gy++) for (let gx = 0; gx < GW; gx++) if (cellRoad[gy * GW + gx]! >= 0)
    for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) { const x = gx + dx, y = gy + dy; if (x >= 0 && y >= 0 && x < GW && y < GH) nearRoadCell[y * GW + x] = 1; }
  // (near a field: the track had best be straight already, so a field is crossed square to its fence)
  const nearFieldCell = new Uint8Array(GW * GH);
  for (let gy = 0; gy < GH; gy++) for (let gx = 0; gx < GW; gx++) if (cellField[gy * GW + gx])
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) { const x = gx + dx, y = gy + dy; if (x >= 0 && y >= 0 && x < GW && y < GH) nearFieldCell[y * GW + x] = 1; }
  const cx0 = plaza.x + plaza.w / 2, cy0 = plaza.y + plaza.h / 2;
  const DX = [1, 1, 0, -1, -1, -1, 0, 1], DY = [0, 1, 1, 1, 0, -1, -1, -1];
  const search = (from: Pt, goal: (gx: number, gy: number) => boolean, goalPt: Pt | null): Pt[] | null => {
    const sx = Math.min(GW - 1, Math.floor(from.x / CELL)), sy = Math.min(GH - 1, Math.floor(from.y / CELL));
    const N = GW * GH * 8;
    const g = new Float64Array(N).fill(Infinity);
    const prev = new Int32Array(N).fill(-1);
    const heap: [number, number][] = [];
    const push = (s: number, f: number): void => {
      heap.push([f, s]);
      let i = heap.length - 1;
      while (i > 0) { const p = (i - 1) >> 1; if (heap[p]![0] <= heap[i]![0]) break; [heap[p], heap[i]] = [heap[i]!, heap[p]!]; i = p; }
    };
    const pop = (): [number, number] => {
      const top = heap[0]!, last = heap.pop()!;
      if (heap.length) {
        heap[0] = last;
        let i = 0;
        for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l]![0] < heap[m]![0]) m = l; if (r < heap.length && heap[r]![0] < heap[m]![0]) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i]!, heap[m]!]; i = m; }
      }
      return top;
    };
    const h = (gx: number, gy: number): number => (goalPt ? Math.hypot(gx - goalPt.x / CELL, gy - goalPt.y / CELL) : 0);
    for (let d = 0; d < 8; d++) { const s = (sy * GW + sx) * 8 + d; g[s] = 0; push(s, h(sx, sy)); }
    let end = -1;
    while (heap.length) {
      const [f, s] = pop();
      const c = s >> 3, d = s & 7, gx = c % GW, gy = (c / GW) | 0;
      if (f - h(gx, gy) > g[s]! + 1e-9) continue;
      if (goal(gx, gy) && !(gx === sx && gy === sy)) { end = s; break; }
      // (in town and in a field the track keeps to the grid's ways; out in the woods it may curve)
      const here = cellIn(gx, gy) || cellField[c] === 1;
      for (const t of [-2, -1, 0, 1, 2]) {
        // (in town: square turns only (two steps of the eight ways), and only on the grid's ways; outside, gentle turns)
        if (here && (t === 1 || t === -1) && (d & 1) === 0) continue;
        if (!here && (t === 2 || t === -2)) continue;
        const nd = (d + t + 8) & 7;
        const nx = gx + DX[nd]!, ny = gy + DY[nd]!;
        if (nx < 0 || ny < 0 || nx >= GW || ny >= GH) continue;
        const nc = ny * GW + nx;
        if (cellBlocked[nc]) continue;
        const there = cellIn(nx, ny) || cellField[nc] === 1;
        if (there && (nd & 1)) continue;
        // (no turning by a street or a country road: the crossing would come out skewed; none in a field: it is split straight)
        if (t !== 0 && (cellIn(gx, gy) || cellIn(nx, ny)) && (nearRoadCell[c] || nearRoadCell[nc])) continue;
        const turnByRoad = t !== 0 && (nearRoadCell[c] === 1 || nearRoadCell[nc] === 1);
        if (t !== 0 && (cellField[c] || cellField[nc])) continue;
        // (and a road is never crossed on the slant)
        if ((nd & 1) && cellRoad[nc]! >= 0) continue;
        const diag = nd & 1;
        let cost = diag ? 1.414 : 1;
        // drawn towards downtown (the depot wants to be near the plaza)
        const dist = Math.hypot(nx * CELL - cx0, ny * CELL - cy0);
        cost *= 1 + 1.6 * Math.min(1, dist / 160);
        // (a river is bridged, square if it can be: never run along it)
        if (cellWet[nc]) cost += 40;
        // (a field is crossed only where going round it costs more: it is split in two)
        if (cellField[nc]) cost += 4;
        if (turnByRoad) cost += 30;
        if (t !== 0 && nearFieldCell[nc]) cost += 10;
        const r = cellRoad[nc]!;
        if (r >= 0) {
          const rd = roads[r]!;
          // (a street is crossed square or not at all along it)
          const along = rd.horizontal ? nd === 0 || nd === 4 : nd === 2 || nd === 6;
          cost += along ? 400 : diag ? 30 : 4;
        }
        if (taken[nc]) cost += (nd === d ? 3 : 200);
        if (t !== 0) cost += here || there ? 14 : 2.5;
        const ns = nc * 8 + nd;
        const ng = g[s]! + cost;
        if (ng < g[ns]!) { g[ns] = ng; prev[ns] = s; push(ns, ng + h(nx, ny)); }
      }
    }
    if (end < 0) return null;
    const cells: Pt[] = [];
    for (let s = end; s >= 0; s = prev[s]!) { const c = s >> 3; cells.unshift({ x: (c % GW) * CELL + CELL / 2, y: ((c / GW) | 0) * CELL + CELL / 2 }); if (prev[s]! < 0) break; }
    return cells;
  };

  // ---- straighten and round the path: pull the string tight where the ground allows, then round the corners
  const okTile = (x: number, y: number): boolean => { const tx = Math.floor(x), ty = Math.floor(y); return tx >= 0 && ty >= 0 && tx < W && ty < H && !blocked[ty * W + tx]; };
  const roadsOn = (a: Pt, b: Pt): number => { let n = 0; const L = Math.hypot(b.x - a.x, b.y - a.y); for (let t = 0; t <= L; t += 0.5) { const x = Math.floor(a.x + ((b.x - a.x) * t) / L), y = Math.floor(a.y + ((b.y - a.y) * t) / L); if (roadAt[y * W + x]! >= 0) n++; } return n; };
  const clearSeg = (a: Pt, b: Pt): boolean => { const L = Math.hypot(b.x - a.x, b.y - a.y); for (let t = 0; t <= L; t += 0.5) if (!okTile(a.x + ((b.x - a.x) * t) / Math.max(L, 1e-9), a.y + ((b.y - a.y) * t) / Math.max(L, 1e-9))) return false; return true; };
  const hardClear = (a: Pt, b: Pt): boolean => {
    const L = Math.max(Math.hypot(b.x - a.x, b.y - a.y), 1e-9);
    for (let t = 0; t <= L; t += 0.5) { const x = Math.floor(a.x + ((b.x - a.x) * t) / L), y = Math.floor(a.y + ((b.y - a.y) * t) / L); if (x < 0 || y < 0 || x >= W || y >= H || hard[y * W + x]) return false; }
    return true;
  };
  /** Is tile ([x], [y]) on a road or in a field (or its fence)? (the track crosses those only on the grid's ways) */
  const square = (x: number, y: number): boolean => {
    const tx = Math.floor(x), ty = Math.floor(y);
    return tx >= 0 && ty >= 0 && tx < W && ty < H && (roadAt[ty * W + tx]! >= 0 || fieldAt[ty * W + tx]! >= 0);
  };
  /** A straight piece is fine if it runs on the grid's ways, or keeps out of the built-up area, the fields and off the roads. */
  const gridOk = (a: Pt, b: Pt): boolean => {
    if (Math.abs(a.x - b.x) < 0.01 || Math.abs(a.y - b.y) < 0.01) return true;
    const L = Math.hypot(b.x - a.x, b.y - a.y);
    // (within 3 degrees of the grid's ways is on them: the half tile a strip's end sits off the search's cells)
    if (Math.min(Math.abs(a.x - b.x), Math.abs(a.y - b.y)) / L < 0.05 && !inBox(a.x, a.y) && !inBox(b.x, b.y)) return true;
    for (let t = 0; t <= L; t += 0.5) { const x = a.x + ((b.x - a.x) * t) / L, y = a.y + ((b.y - a.y) * t) / L; if (inBox(x, y) || square(x, y)) return false; }
    return true;
  };
  const tighten = (p: Pt[]): Pt[] => {
    const outP: Pt[] = [p[0]!];
    let i = 0;
    while (i < p.length - 1) {
      let j = p.length - 1;
      for (; j > i + 1; j--) {
        if (!clearSeg(p[i]!, p[j]!) || !gridOk(p[i]!, p[j]!)) continue;
        let orig = 0; for (let k = i; k < j; k++) orig += roadsOn(p[k]!, p[k + 1]!);
        if (roadsOn(p[i]!, p[j]!) <= orig + 1) break;
      }
      outP.push(p[j]!);
      i = j;
    }
    return outP;
  };
  /** Rounds each corner with a curve: tight in town (the grid's square turns), wide out in the country; a corner whose
   * curve would cut into a plot keeps its point. */
  const round = (p: Pt[]): Pt[] => {
    const out: Pt[] = [p[0]!];
    for (let i = 1; i + 1 < p.length; i++) {
      const a = out[out.length - 1]!, v = p[i]!, b = p[i + 1]!;
      const la = Math.hypot(a.x - v.x, a.y - v.y), lb = Math.hypot(b.x - v.x, b.y - v.y);
      const r0 = Math.min(inBox(v.x, v.y) ? 10 : 30, la / 2.2, lb / 2.2);
      if (r0 < 0.5) { out.push(v); continue; }
      // (the widest curve that cuts into no plot, and stays out of the fields and off the roads it does not run along)
      let done = false;
      // (failing that, one that keeps off the roads only: a sharp corner is worse than a curve over a field's edge)
      for (const [r, soft] of [[r0, false], [r0 * 0.7, false], [r0 * 0.45, false], [r0 * 0.25, false], [r0, true], [r0 * 0.5, true]] as [number, boolean][]) {
        if (r < 0.5) continue;
        const p1 = { x: v.x + ((a.x - v.x) / la) * r, y: v.y + ((a.y - v.y) / la) * r };
        const p2 = { x: v.x + ((b.x - v.x) / lb) * r, y: v.y + ((b.y - v.y) / lb) * r };
        const arc: Pt[] = [];
        for (let k = 0; k <= 6; k++) { const t = k / 6, u = 1 - t; arc.push({ x: u * u * p1.x + 2 * u * t * v.x + t * t * p2.x, y: u * u * p1.y + 2 * u * t * v.y + t * t * p2.y }); }
        let ok = hardClear(a, arc[0]!);
        for (let k = 0; ok && k + 1 < arc.length; k++) ok = hardClear(arc[k]!, arc[k + 1]!);
        for (let k = 0; ok && k < 6; k++) {
          const p = arc[k]!, q = arc[k + 1]!, L = Math.hypot(q.x - p.x, q.y - p.y);
          for (let t = 0; ok && t <= L; t += 0.5) { const x = p.x + ((q.x - p.x) * t) / Math.max(L, 1e-9), y = p.y + ((q.y - p.y) * t) / Math.max(L, 1e-9); if (soft ? roadAt[Math.floor(y) * W + Math.floor(x)]! >= 0 : square(x, y)) ok = false; }
        }
        if (ok) { out.push(...arc); done = true; break; }
      }
      if (!done) out.push(v);
    }
    out.push(p[p.length - 1]!);
    return out;
  };
  const mark = (c: Pt[]): void => { for (const q of c) taken[Math.min(GH - 1, Math.floor(q.y / CELL)) * GW + Math.min(GW - 1, Math.floor(q.x / CELL))] = 1; };
  /** A path from [from] to the goal, straightened and rounded; [commit]: mark it taken (a later line crosses, never follows it). */
  const lay = (from: Pt, goal: (gx: number, gy: number) => boolean, goalPt: Pt | null, endPt: Pt | null, commit = true): Pt[] | null => {
    const c = search(from, goal, goalPt);
    if (!c) return null;
    const p = [from, ...c.slice(1, -1), endPt ?? c[c.length - 1]!];
    if (commit) mark(c);
    return tighten(p);
  };
  const cellGoal = (b: Pt) => { const gx = Math.floor(b.x / CELL), gy = Math.floor(b.y / CELL); return (x: number, y: number): boolean => x === gx && y === gy; };

  // ---- the depot's site: a straight stretch of track on clear ground with the depot beside it, near a street, nearest
  // the plaza; a through line is laid through the best such stretch it can reach without a long detour
  const freeRect = (r: Rect): boolean => {
    if (r.x < 1 || r.y < 1 || r.x + r.w > W - 1 || r.y + r.h > H - 1) return false;
    for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) if (blocked[y * W + x] || roadAt[y * W + x]! >= 0 || fieldAt[y * W + x]! >= 0 || wet(x, y) || taken[Math.floor(y / CELL) * GW + Math.floor(x / CELL)]) return false;
    return true;
  };
  const nearRoad = (r: Rect, pad: number): boolean => {
    for (let y = Math.max(0, r.y - pad); y < Math.min(H, r.y + r.h + pad); y++) for (let x = Math.max(0, r.x - pad); x < Math.min(W, r.x + r.w + pad); x++) if (roadAt[y * W + x]! >= 0) return true;
    return false;
  };
  const DEPOT = { len: 12, deep: 6, off: 3 };
  const STRIP = 20, LEAD = 4;
  interface Strip { s0: Pt; s1: Pt; dx: number; dy: number; rect: Rect; side: 1 | -1; d: number }
  const strips = (): Strip[] => {
    const all: Strip[] = [];
    for (let y = 3; y < H - 3; y += 2) for (let x = 3; x < W - 3; x += 2) for (const horiz of [true, false]) {
      const dx = horiz ? 1 : 0, dy = horiz ? 0 : 1;
      const a = { x: x + 0.5 - dx * LEAD, y: y + 0.5 - dy * LEAD }, b = { x: x + 0.5 + dx * (STRIP + LEAD), y: y + 0.5 + dy * (STRIP + LEAD) };
      if (b.x > W - 2 || b.y > H - 2 || a.x < 1 || a.y < 1) continue;
      if (!clearSeg(a, b) || roadsOn(a, b) > 0) continue;
      let dry = true; for (let t = 0; t <= STRIP + 2 * LEAD; t++) if (wet(Math.floor(a.x + dx * t), Math.floor(a.y + dy * t)) || square(a.x + dx * t, a.y + dy * t)) dry = false;
      if (!dry) continue;
      const mid = { x: x + 0.5 + dx * STRIP / 2, y: y + 0.5 + dy * STRIP / 2 };
      for (const side of [1, -1] as const) {
        const nx = dy * side, ny = -dx * side;
        const c = { x: mid.x + nx * (DEPOT.off + DEPOT.deep / 2), y: mid.y + ny * (DEPOT.off + DEPOT.deep / 2) };
        const rw = horiz ? DEPOT.len : DEPOT.deep, rh = horiz ? DEPOT.deep : DEPOT.len;
        const rect = { x: Math.round(c.x - rw / 2), y: Math.round(c.y - rh / 2), w: rw, h: rh };
        if (!freeRect(rect) || !nearRoad(rect, 8)) continue;
        all.push({ s0: a, s1: b, dx, dy, rect, side, d: Math.hypot(c.x - cx0, c.y - cy0) });
      }
    }
    all.sort((p, q) => p.d - q.d);
    const picked: Strip[] = [];
    for (const s of all) { if (picked.length >= 10) break; if (picked.every((q) => Math.hypot(q.rect.x - s.rect.x, q.rect.y - s.rect.y) > 12)) picked.push(s); }
    return picked;
  };
  /** A depot site beside [pts] where a stretch runs near a street's way (within 12 degrees of east-west or north-south), nearest the plaza. */
  const depotAlong = (pts: Pt[], roadPad = 8): TownRail['depot'] => {
    let best: { d: number; v: NonNullable<TownRail['depot']> } | null = null;
    for (let i = 0; i + 1 < pts.length; i++) {
      const a = pts[i]!, b = pts[i + 1]!, L = Math.hypot(b.x - a.x, b.y - a.y);
      if (L < DEPOT.len + 2) continue;
      const dx = (b.x - a.x) / L, dy = (b.y - a.y) / L;
      const horiz = Math.abs(dx) >= Math.abs(dy);
      if (Math.abs(horiz ? dy : dx) > 0.2) continue;
      for (let t = DEPOT.len / 2 + 1; t <= L - DEPOT.len / 2 - 1; t += 2) {
        const at = { x: a.x + dx * t, y: a.y + dy * t };
        for (const side of [1, -1] as const) {
          const nx = dy * side, ny = -dx * side;
          const c = { x: at.x + nx * (DEPOT.off + DEPOT.deep / 2), y: at.y + ny * (DEPOT.off + DEPOT.deep / 2) };
          const rw = horiz ? DEPOT.len : DEPOT.deep, rh = horiz ? DEPOT.deep : DEPOT.len;
          const rect = { x: Math.round(c.x - rw / 2), y: Math.round(c.y - rh / 2), w: rw, h: rh };
          if (!freeRect(rect) || !nearRoad(rect, roadPad)) continue;
          const d = Math.hypot(c.x - cx0, c.y - cy0);
          if (!best || d < best.d) best = { d, v: { rect, at, dx, dy, side, built: builtYear } };
        }
      }
    }
    return best?.v ?? null;
  };
  const len = (p: Pt[]): number => p.reduce((t, q, i) => (i ? t + Math.hypot(q.x - p[i - 1]!.x, q.y - p[i - 1]!.y) : 0), 0);

  // ---- through lines first (the main lines, east-west then north-south), then the lines that end here
  const byDir = (d: 'ew' | 'ns'): RailLink[] => links.filter((l) => l.dir === d);
  const paths: { pts: Pt[]; links: RailLink[] }[] = [];
  const pairs = (['ew', 'ns'] as const).map((d) => byDir(d)).filter((ls) => ls.length === 2).sort((p, q) => (q[0]!.kind === 'main' ? 1 : 0) - (p[0]!.kind === 'main' ? 1 : 0));
  const builtYear = Math.min(...links.map((l) => l.built));
  for (const ls of pairs) {
    const [l0, l1] = ls[0]!.b === site.id ? [ls[0]!, ls[1]!] : [ls[1]!, ls[0]!]; // l0 comes in from the west / north
    const a = ports.get(l0.id)!.at, b = ports.get(l1.id)!.at;
    const direct = lay(a, cellGoal(b), b, b, false);
    let p: Pt[] | null = null;
    if (!out.depot && direct) {
      for (const st of strips()) {
        // (the stretch is run the way that needs no doubling back: each leg meets it along its own way, and of the two
        // ways that do, the shorter)
        let best: { leg0: Pt[]; leg1: Pt[]; c0: Pt[]; c1: Pt[]; s0: Pt; s1: Pt; L: number } | null = null;
        for (const flip of [false, true]) {
          const s0 = flip ? st.s1 : st.s0, s1 = flip ? st.s0 : st.s1;
          const c0 = search(a, cellGoal(s0), s0); if (!c0) continue;
          const c1 = search(s1, cellGoal(b), b); if (!c1) continue;
          const leg0 = tighten([a, ...c0.slice(1, -1), s0]), leg1 = tighten([s1, ...c1.slice(1, -1), b]);
          const sx = s1.x - s0.x, sy = s1.y - s0.y, sl = Math.hypot(sx, sy);
          // (the legs' ways in and out, past the half-tile jog onto the search's cells)
          const q = [...leg0].reverse().find((v) => Math.hypot(v.x - s0.x, v.y - s0.y) >= 2) ?? leg0[leg0.length - 2]!;
          const r = leg1.find((v) => Math.hypot(v.x - s1.x, v.y - s1.y) >= 2) ?? leg1[1]!;
          const inDot = ((s0.x - q.x) * sx + (s0.y - q.y) * sy) / (Math.hypot(s0.x - q.x, s0.y - q.y) * sl || 1);
          const outDot = ((r.x - s1.x) * sx + (r.y - s1.y) * sy) / (Math.hypot(r.x - s1.x, r.y - s1.y) * sl || 1);
          if (inDot < -0.05 || outDot < -0.05) continue;
          const L = len(leg0) + len(leg1);
          if (!best || L < best.L) best = { leg0, leg1, c0, c1, s0, s1, L };
        }
        if (!best) continue;
        const { leg0, leg1, c0, c1, s0, s1 } = best;
        const whole = [...leg0, ...leg1];
        if (len(whole) > len(direct) * 1.7 + 80) continue;
        mark(c0); mark(c1);
        const mid = { x: (s0.x + s1.x) / 2, y: (s0.y + s1.y) / 2 };
        const dx = (s1.x - s0.x) / Math.hypot(s1.x - s0.x, s1.y - s0.y), dy = (s1.y - s0.y) / Math.hypot(s1.x - s0.x, s1.y - s0.y);
        // which side of the line's own direction the depot stands (+1 left)
        const rc = { x: st.rect.x + st.rect.w / 2 - mid.x, y: st.rect.y + st.rect.h / 2 - mid.y };
        const side: 1 | -1 = rc.x * dy - rc.y * dx > 0 ? 1 : -1;
        out.depot = { rect: st.rect, at: mid, dx, dy, side, built: builtYear };
        // (the stretch itself is kept straight through the rounding: corners are rounded on either side of it)
        // (the legs' half-tile jogs onto the search's cells by the stretch's ends are dropped: the legs run straight on; the
        // corners where they meet the stretch are rounded too, short of the platform)
        const r0 = leg0.slice(0, -1), r1 = leg1.slice(1);
        while (r0.length > 1 && Math.hypot(r0[r0.length - 1]!.x - s0.x, r0[r0.length - 1]!.y - s0.y) < 2) r0.pop();
        while (r1.length > 1 && Math.hypot(r1[0]!.x - s1.x, r1[0]!.y - s1.y) < 2) r1.shift();
        p = round([...r0, s0, mid, s1, ...r1]);
        break;
      }
    }
    if (!p && direct) {
      p = round(lay(a, cellGoal(b), b, b)!);
      // no stretch near the plaza to run through: the depot beside the line as laid, on a straight piece of it
      if (!out.depot) out.depot = depotAlong(p);
    }
    if (p) paths.push({ pts: p, links: [l0, l1] }); else out.conflicts.push(`no way through for ${l0.id} and ${l1.id}`);
  }

  // ---- lines that end here (a branch's terminus, a spur): to the depot's track if there is one, else to a depot of their own
  for (const l of links) {
    if (paths.some((p) => p.links.includes(l))) continue;
    const a = ports.get(l.id)!.at;
    let p: Pt[] | null = null;
    if (paths.length) {
      // join the nearest through track (a junction's turnout)
      const onTrack = new Uint8Array(GW * GH);
      for (const q of paths) for (const t of q.pts) onTrack[Math.min(GH - 1, Math.floor(t.y / CELL)) * GW + Math.min(GW - 1, Math.floor(t.x / CELL))] = 1;
      p = lay(a, (x, y) => onTrack[y * GW + x] === 1, null, null);
      if (p) p = round(p);
    } else {
      // a terminus: a depot site near the plaza and a street first, then the track to it
      let best: { d: number; r: Rect; at: Pt; dx: number; dy: number } | null = null;
      for (let y = 4; y + 20 < H; y += 2) for (let x = 4; x + 20 < W; x += 2) {
        for (const horiz of [true, false]) {
          const r = horiz ? { x, y, w: DEPOT.len, h: DEPOT.deep } : { x, y, w: DEPOT.deep, h: DEPOT.len };
          if (!freeRect(r) || !nearRoad(r, 10)) continue;
          const d = Math.hypot(x + r.w / 2 - cx0, y + r.h / 2 - cy0);
          if (best && d >= best.d) continue;
          // the track's end beside it, on the side away from the plaza
          const at = horiz ? { x: x + r.w / 2, y: y + (y + r.h / 2 < cy0 ? -DEPOT.off : r.h + DEPOT.off) } : { x: x + (x + r.w / 2 < cx0 ? -DEPOT.off : r.w + DEPOT.off), y: y + r.h / 2 };
          if (!okTile(at.x, at.y)) continue;
          best = { d, r, at, dx: horiz ? 1 : 0, dy: horiz ? 0 : 1 };
        }
      }
      if (best) {
        const gx = Math.floor(best.at.x / CELL), gy = Math.floor(best.at.y / CELL);
        p = lay(a, (x, y) => x === gx && y === gy, best.at, best.at);
        if (p) p = round(p);
        if (p) {
          const side = (best.dy * (best.r.x + best.r.w / 2 - best.at.x) - best.dx * (best.r.y + best.r.h / 2 - best.at.y)) >= 0 ? 1 : -1;
          out.depot = { rect: best.r, at: best.at, dx: best.dx, dy: best.dy, side, built: builtYear };
        }
      }
    }
    if (p) paths.push({ pts: p, links: [l] }); else out.conflicts.push(`no way in for ${l.id}`);
  }

  // ---- no depot yet (no stretch for it near a street): beside any of the lines, further from a street if need be
  if (!out.depot) for (const pad of [8, 16, 40]) {
    for (const q of paths) { const d = depotAlong(q.pts, pad); if (d && (!out.depot || Math.hypot(d.at.x - cx0, d.at.y - cy0) < Math.hypot(out.depot.at.x - cx0, out.depot.at.y - cy0))) out.depot = d; }
    if (out.depot) break;
  }

  // ---- a track's end at a portal runs straight out: the search's cells may reach a tile past the portal at the edge
  // (the half cell), and the track would step out and back
  const outside = (q: Pt, e: Pt): boolean =>
    (e.x <= 1.01 && q.x < e.x - 0.01) || (e.x >= W - 2.01 && q.x > e.x + 0.01) || (e.y <= 1.01 && q.y < e.y - 0.01) || (e.y >= H - 2.01 && q.y > e.y + 0.01);
  for (const p of paths) {
    while (p.pts.length > 2 && outside(p.pts[1]!, p.pts[0]!)) p.pts.splice(1, 1);
    while (p.pts.length > 2 && outside(p.pts[p.pts.length - 2]!, p.pts[p.pts.length - 1]!)) p.pts.splice(p.pts.length - 2, 1);
  }

  // ---- tracks by link: a through track splits at the depot (or its middle)
  for (const p of paths) {
    if (p.links.length === 1) { out.tracks.push({ link: p.links[0]!.id, kind: p.links[0]!.kind, pts: p.pts }); continue; }
    let k = Math.floor(p.pts.length / 2);
    if (out.depot) { let bd = Infinity; p.pts.forEach((q, i) => { const d = Math.hypot(q.x - out.depot!.at.x, q.y - out.depot!.at.y); if (d < bd) { bd = d; k = i; } }); }
    k = Math.max(1, Math.min(p.pts.length - 2, k));
    out.tracks.push({ link: p.links[0]!.id, kind: p.links[0]!.kind, pts: p.pts.slice(0, k + 1) });
    out.tracks.push({ link: p.links[1]!.id, kind: p.links[1]!.kind, pts: p.pts.slice(k) });
  }

  // ---- the freight siding beside the depot's track (the far side from the depot), and the elevator's site at its end
  if (out.depot) {
    const d = out.depot;
    const nx = -d.dy * d.side, ny = d.dx * d.side; // away from the depot
    const OFF = 4, LEN = 30;
    for (const shift of [6, -6 - LEN, 14, -14 - LEN]) {
      const a = { x: d.at.x + d.dx * shift + nx * OFF, y: d.at.y + d.dy * shift + ny * OFF };
      const b = { x: a.x + d.dx * LEN, y: a.y + d.dy * LEN };
      if (!clearSeg(a, b) || roadsOn(a, b) > 0) continue;
      const lead0 = { x: a.x - d.dx * 8 - nx * OFF, y: a.y - d.dy * 8 - ny * OFF };
      if (!clearSeg(lead0, a) || roadsOn(lead0, a) > 0) continue;
      const pts = [lead0, a, b];
      const ec = { x: b.x - d.dx * 6 + nx * 7, y: b.y - d.dy * 6 + ny * 7 };
      const er = { x: Math.round(ec.x - 4), y: Math.round(ec.y - 4), w: 8, h: 8 };
      // (the siding belongs to the track it leaves: the one passing the depot)
      const near = (t: TownTrack): number => Math.min(...t.pts.map((q) => Math.hypot(q.x - d.at.x, q.y - d.at.y)));
      const own = out.tracks.filter((t) => t.kind !== 'siding').sort((p, q) => near(p) - near(q))[0]!;
      out.tracks.push({ link: own.link, kind: 'siding', pts });
      if (freeRect(er)) out.elevator = er;
      break;
    }
  }

  // ---- level crossings: wherever a track runs over a street
  for (const t of out.tracks) {
    const link = links.find((l) => l.id === t.link)!;
    let inRoad = -1, enter: Pt | null = null;
    const seen = new Set<number>();
    for (let i = 0; i + 1 < t.pts.length; i++) {
      const a = t.pts[i]!, b = t.pts[i + 1]!, L = Math.hypot(b.x - a.x, b.y - a.y);
      for (let s = 0; s < L; s += 0.25) {
        const x = a.x + ((b.x - a.x) * s) / L, y = a.y + ((b.y - a.y) * s) / L;
        const tx = Math.floor(x), ty = Math.floor(y);
        const r = tx >= 0 && ty >= 0 && tx < W && ty < H ? roadAt[ty * W + tx]! : -1;
        if (r !== inRoad) {
          if (inRoad >= 0 && enter && !seen.has(inRoad)) {
            seen.add(inRoad);
            const mx = (enter.x + x) / 2, my = (enter.y + y) / 2;
            out.crossings.push(crossing(w, link, inRoad, busyRoad(inRoad), inRoad >= plan.roads.length, mx, my, (b.x - a.x) / L, (b.y - a.y) / L));
          }
          inRoad = r; enter = { x, y };
        }
      }
    }
  }
  // what the track runs over that it should not (checked by the tests: nothing)
  for (const t of out.tracks) for (let i = 0; i + 1 < t.pts.length; i++) if (!hardClear(t.pts[i]!, t.pts[i + 1]!)) { out.conflicts.push(`${t.link} runs over a plot near ${Math.round(t.pts[i]!.x - PAD)},${Math.round(t.pts[i]!.y - PAD)}`); break; }

  // ---- the fields the track runs through: split along it (it crosses a field straight, on the grid's ways), the pieces
  // either side FIELD_CUT off its centre line; a piece too small to farm is left to the railroad's grass
  const cutsOf = new Map<number, Rect[]>();
  for (const t of out.tracks) for (let i = 0; i + 1 < t.pts.length; i++) {
    const a = t.pts[i]!, b = t.pts[i + 1]!, L = Math.max(Math.hypot(b.x - a.x, b.y - a.y), 1e-9);
    const across = Math.abs(b.x - a.x) <= Math.abs(b.y - a.y); // the track runs north-south: the cut is a band of x
    fields.forEach((f, k) => {
      // (the stretch of this piece of track within FIELD_CUT of the field)
      let s0 = Infinity, s1 = -Infinity;
      for (let u = 0; u <= L; u += 0.5) {
        const x = a.x + ((b.x - a.x) * u) / L, y = a.y + ((b.y - a.y) * u) / L;
        if (x > f.x - FIELD_CUT && x < f.x + f.w + FIELD_CUT && y > f.y - FIELD_CUT && y < f.y + f.h + FIELD_CUT) { s0 = Math.min(s0, u); s1 = Math.max(s1, u); }
      }
      if (s0 > s1) return;
      const p = { x: a.x + ((b.x - a.x) * s0) / L, y: a.y + ((b.y - a.y) * s0) / L }, q = { x: a.x + ((b.x - a.x) * s1) / L, y: a.y + ((b.y - a.y) * s1) / L };
      const x0 = Math.min(p.x, q.x) - FIELD_CUT, x1 = Math.max(p.x, q.x) + FIELD_CUT, y0 = Math.min(p.y, q.y) - FIELD_CUT, y1 = Math.max(p.y, q.y) + FIELD_CUT;
      const pieces = cutsOf.get(k) ?? [f];
      const next: Rect[] = [];
      for (const r of pieces) {
        if (!(x0 < r.x + r.w && r.x < x1 && y0 < r.y + r.h && r.y < y1)) { next.push(r); continue; }
        if (across) next.push({ x: r.x, y: r.y, w: Math.floor(x0) - r.x, h: r.h }, { x: Math.ceil(x1), y: r.y, w: r.x + r.w - Math.ceil(x1), h: r.h });
        else next.push({ x: r.x, y: r.y, w: r.w, h: Math.floor(y0) - r.y }, { x: r.x, y: Math.ceil(y1), w: r.w, h: r.y + r.h - Math.ceil(y1) });
      }
      cutsOf.set(k, next.filter((r) => r.w > 0 && r.h > 0));
    });
  }
  for (const [k, pieces] of [...cutsOf.entries()].sort((p, q) => p[0] - q[0])) {
    out.fieldCuts.push({ field: fp.fields[k]!.id, pieces: pieces.filter((q) => q.w >= 8 && q.h >= 8).map((q) => ({ x: q.x - PAD, y: q.y - PAD, w: q.w, h: q.h })) });
  }

  // ---- back to the plan's tiles
  const un = (p: Pt): Pt => ({ x: Math.round((p.x - PAD) * 1000) / 1000, y: Math.round((p.y - PAD) * 1000) / 1000 });
  for (const t of out.tracks) t.pts = t.pts.map(un);
  for (const c of out.crossings) { c.x = Math.round((c.x - PAD) * 100) / 100; c.y = Math.round((c.y - PAD) * 100) / 100; }
  for (const p of out.portals) p.at = { x: p.at.x - PAD, y: p.at.y - PAD };
  if (out.depot) out.depot = { ...out.depot, rect: { ...out.depot.rect, x: out.depot.rect.x - PAD, y: out.depot.rect.y - PAD }, at: un(out.depot.at) };
  if (out.elevator) out.elevator = { ...out.elevator, x: out.elevator.x - PAD, y: out.elevator.y - PAD };
  return out;
}

function crossing(w: RailWorld, l: RailLink, road: number, busyRoad: boolean, country: boolean, x: number, y: number, dx: number, dy: number): LevelCrossing {
  const r = (k: string): number => rand(w.seed, 'rail-xing', l.id, road, k);
  // (a quiet country road (the section road, a road out from a side street) keeps its crossbucks into the 1960s and never
  // gets gates; the main street's road out and every street on a main line get lights and gates)
  const quiet = country && !busyRoad;
  const busy = !quiet && (l.kind === 'main' || busyRoad);
  const lightsFrom = Math.max(l.built, quiet ? 1964 + Math.floor(r('lights') * 26) : (busy ? 1946 : 1956) + Math.floor(r('lights') * 22));
  const gates = !quiet && (busy || r('g') < 0.3) ? Math.max(lightsFrom + 4, 1962 + Math.floor(r('gates') * 25)) : undefined;
  return { x: Math.round(x * 100) / 100, y: Math.round(y * 100) / 100, dx: Math.round(dx * 1000) / 1000, dy: Math.round(dy * 1000) / 1000, road, link: l.id, lightsFrom, ...(gates !== undefined ? { gatesFrom: gates } : {}) };
}

/** What stands of the town's railroad in [year]: each track's state, each crossing's gear, the depot's state. */
export function railIn(w: RailWorld, site: TownSite, rail: TownRail, year: number): {
  tracks: { track: TownTrack; state: 'track' | 'bed' | 'trail' | 'none' }[];
  crossings: { crossing: LevelCrossing; gear: CrossingGear | null }[];
  depot: 'none' | 'open' | 'closed' | 'reused';
  station: StationStatus;
} {
  const links = new Map(linksAt(w, site.cx, site.cy).map((l) => [l.id, l]));
  const state = (id: string): 'track' | 'bed' | 'trail' | 'none' => {
    const l = links.get(id);
    if (!l || l.built > year) return 'none';
    if (trackIn(l, year)) return 'track';
    return l.trail !== undefined && l.trail <= year ? 'trail' : 'bed';
  };
  const st = stationAt(w, site.cx, site.cy, year);
  return {
    tracks: rail.tracks.map((t) => ({ track: t, state: state(t.link) })),
    crossings: rail.crossings.map((c) => {
      const s = state(c.link);
      return { crossing: c, gear: s !== 'track' ? null : c.gatesFrom !== undefined && year >= c.gatesFrom ? 'gates' : year >= c.lightsFrom ? 'lights' : 'crossbuck' };
    }),
    depot: !rail.depot || st.status === 'none' ? 'none' : st.status === 'passenger' ? 'open' : st.status === 'freight' ? 'closed' : 'reused',
    station: st.status,
  };
}
