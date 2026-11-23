import { B, type Climate } from './climate';
import { CELL_KM, CELL_M, H, N, W, distKm, wrapDx } from './grid';
import { RIVER_FLOW, type Hydro } from './hydro';
import { hashStr, hashU } from './noise';
import type { Place } from './places';
import { GridSearch, gabriel, padCells, pathKm, pinToward, type RoadGraph } from './roads';
import type { Terrain } from './terrain';
import type { TownSite } from '../map';
import { history, type LinkKind, type Pt, type RailGraph, type RailLink, type RailWorld, type TownRank } from '../rail';
import { archetypeOf } from '../../sim/archetype';

// THE SKELETON'S RAILROADS (WORLD_GEN.md section 6). Main lines join the hubs and the major towns of each landmass that
// has a hub: a minimum spanning tree of the least-cost paths along a Gabriel graph, plus a share of the other links
// for loops, routed far stricter on grade than the roads (cost by grade cubed: lines follow the rivers and take the
// passes), pinned to the hubs' rail portals. Branch lines bring towns within 30 km of a main line to its nearest
// station. A line's stations are the places it passes within a cell of; a link joins two stations in a row. The
// history, stations, timetables and the planner are src/world/rail.ts's, on this graph (skeletonRail).

const TILE_M = 2.1;
const cellTiles = CELL_M / TILE_M;

export interface RailLine { id: string; kind: LinkKind; cells: number[]; stations: string[]; /** each station's index in cells */ at: number[] }
export interface RailNet { lines: RailLine[]; links: RailLink[] }

function railCost(t: Terrain, cl: Climate, h: Hydro, bio: Uint8Array): Float32Array {
  const cost = new Float32Array(N);
  for (let c = 0; c < N; c++) {
    if (!t.land[c]) { cost[c] = Infinity; continue; }
    const g = cl.slope[c]! / 18;
    cost[c] = 1 + Math.min(g * g * g, 400) + (h.lake[c] ? 60 : 0) + (h.flow[c]! >= RIVER_FLOW ? 5 : 0) + (bio[c] === B.ice || bio[c] === B.glacier ? 10 : 0);
  }
  return cost;
}

export function makeRail(t: Terrain, cl: Climate, h: Hydro, bio: Uint8Array, places: Place[], w: RailWorld): RailNet {
  const seed = t.seed;
  const base = railCost(t, cl, h, bio);
  const cost = Float32Array.from(base);
  const hubs = places.filter((p) => p.kind === 'hub');
  for (const [c] of padCells(hubs)) if (Number.isFinite(cost[c]!)) cost[c] = cost[c]! * 40;
  const search = new GridSearch();
  const lines: RailLine[] = [];
  const hs = hashStr(`${seed}|rail`);
  const byMass = new Map<number, Place[]>();
  for (const p of places) { const a = byMass.get(p.mass); if (a) a.push(p); else byMass.set(p.mass, [p]); }
  const mainCells = new Uint8Array(N);
  for (const m of [...byMass.keys()].sort((a, b) => a - b)) {
    const all = byMass.get(m)!;
    if (!all.some((p) => p.kind === 'hub')) continue;
    const nodes = all.filter((p) => p.kind === 'hub' || p.rank === 'major');
    if (nodes.length < 2) continue;
    const cand = gabriel(nodes).map(([a, b]) => {
      const A = nodes[a]!, Bp = nodes[b]!;
      const s = A.hub ? pinToward(A, 'rail', Bp) : A.cell, g = Bp.hub ? pinToward(Bp, 'rail', A) : Bp.cell;
      const sv = cost[s]!, gv = cost[g]!;
      cost[s] = base[s]!; cost[g] = base[g]!;
      const p = search.path(cost, s, g, 0.7);
      cost[s] = sv; cost[g] = gv;
      if (!p) return null;
      let k = 0;
      for (let q = 1; q < p.length; q++) k += cost[p[q]!]! * Math.hypot(wrapDx((p[q]! % W) - (p[q - 1]! % W)), ((p[q]! / W) | 0) - ((p[q - 1]! / W) | 0));
      return { a, b, cells: [...(A.hub ? [A.cell] : []), ...p, ...(Bp.hub ? [Bp.cell] : [])], cost: k };
    }).filter((e): e is NonNullable<typeof e> => e !== null).sort((p, q) => p.cost - q.cost || p.a - q.a || p.b - q.b);
    // Kruskal, then loops
    const parent = nodes.map((_, i) => i);
    const find = (i: number): number => (parent[i] === i ? i : (parent[i] = find(parent[i]!)));
    for (const e of cand) {
      const ra = find(e.a), rb = find(e.b);
      const tree = ra !== rb;
      if (tree) parent[ra] = rb;
      if (tree || hashU(hs, hashStr(`loop|${nodes[e.a]!.id}|${nodes[e.b]!.id}`)) < 0.22) {
        lines.push({ id: `line:${lines.length}`, kind: 'main', cells: e.cells, stations: [], at: [] });
        for (const c of e.cells) mainCells[c] = 1;
      }
    }
  }
  // stations of the main lines
  const placeAt = new Map<number, Place[]>();
  for (const p of places) { const a = placeAt.get(p.cell); if (a) a.push(p); else placeAt.set(p.cell, [p]); }
  const stationsOf = (cells: number[], ends: [string | null, string | null]): { stations: string[]; at: number[] } => {
    const st: string[] = [], at: number[] = [];
    const seen = new Set<string>();
    const add = (id: string, q: number): void => { if (!seen.has(id)) { seen.add(id); st.push(id); at.push(q); } };
    if (ends[0]) add(ends[0], 0);
    for (let q = 0; q < cells.length; q++) {
      const c = cells[q]!, i0 = c % W, j0 = (c / W) | 0;
      for (let dj = -1; dj <= 1; dj++) for (let di = -1; di <= 1; di++) {
        const j = j0 + dj;
        if (j < 0 || j >= H) continue;
        for (const p of placeAt.get(j * W + ((i0 + di + W) % W)) ?? []) if (p.kind !== 'hub' || p.cell === c) add(p.id, q);
      }
    }
    if (ends[1]) { if (seen.has(ends[1])) { const k = st.indexOf(ends[1]); st.splice(k, 1); at.splice(k, 1); } st.push(ends[1]); at.push(cells.length - 1); }
    return { stations: st, at };
  };
  const endPlace = (c: number): string | null => (placeAt.get(c) ?? [])[0]?.id ?? null;
  for (const l of lines) Object.assign(l, stationsOf(l.cells, [endPlace(l.cells[0]!), endPlace(l.cells[l.cells.length - 1]!)]));
  // ---- branches: towns of rank town or major within 30 km of a main line (most), a few small ones, to the nearest station
  const mainStations = new Set<string>();
  for (const l of lines) for (const s of l.stations) mainStations.add(s);
  const srcs: number[] = [];
  for (const p of places) if (mainStations.has(p.id)) srcs.push(p.cell);
  const pred = search.flood(cost, srcs);
  const nmains = lines.length;
  const onBranch = new Set<string>();
  for (const p of places) {
    if (p.kind !== 'town' || mainStations.has(p.id) || onBranch.has(p.id) || pred[p.cell] === -2) continue;
    const share = p.rank === 'small' ? 0.15 : 0.7;
    if (hashU(hs, hashStr(`branch|${p.id}`)) >= share) continue;
    const cells: number[] = [];
    for (let c = p.cell; c >= 0; c = pred[c]!) cells.push(c);
    if (cells.length < 2 || pathKm(cells) > 30) continue;
    const end = cells[cells.length - 1]!;
    const to = (placeAt.get(end) ?? []).find((q) => mainStations.has(q.id));
    if (!to) continue;
    const l: RailLine = { id: `lineb:${lines.length - nmains}`, kind: 'branch', cells, stations: [], at: [] };
    Object.assign(l, stationsOf(cells, [p.id, to.id]));
    for (const s of l.stations) onBranch.add(s);
    lines.push(l);
  }
  // ---- links between stations in a row, with their history (rail.ts's rules) and company
  const links: RailLink[] = [];
  const siteOf = new Map(places.map((p) => [p.id, p]));
  const DIRS = ['Western', 'Northern', 'Southern', 'Eastern', 'Central', 'Pacific', 'Atlantic', 'Valley', 'Midland', 'Lakes'];
  const firstWord = (n: string): string => { const q = n.split(' '); return (q[0] === 'Port' || q[0] === 'Mount' || q[0] === 'North' || q[0] === 'South' || q[0] === 'East' || q[0] === 'West' || q[0] === 'New' || q[0] === 'Old' || q[0] === 'Upper' || q[0] === 'Lower') && q[1] ? q[1] : q[0]!; };
  for (const l of lines) {
    const mid = siteOf.get(l.stations[Math.floor(l.stations.length / 2)]!)!;
    const tail = DIRS[Math.floor(hashU(hs, hashStr(`co|${l.id}`)) * DIRS.length)]!;
    const company = l.kind === 'main' ? `${firstWord(mid.name)} & ${tail} Railroad` : `${firstWord(mid.name)} Valley Railway`;
    for (let k = 0; k + 1 < l.stations.length; k++) {
      const a = siteOf.get(l.stations[k]!)!, b = siteOf.get(l.stations[k + 1]!)!;
      const id = `${l.id}/${k}`;
      const dir = Math.abs(wrapDx(b.x - a.x)) >= Math.abs(b.z - a.z) ? 'ew' : 'ns';
      const sa = toSite(a), sb = toSite(b);
      links.push({ id, a: a.id, b: b.id, dir, kind: l.kind, line: l.id, ...history(w, id, l.kind, sa, sb), company });
    }
  }
  return { lines, links };
}

/** A place as rail.ts's TownSite (cell = atlas cell; centre in world tiles). */
export function toSite(p: Place): TownSite {
  return { id: p.id, seed: p.seed, cx: Math.floor(p.x), cy: Math.floor(p.z), name: p.name, founded: p.founded, center: { x: Math.round(p.x * cellTiles), y: Math.round(p.z * cellTiles) } };
}

/** The skeleton's network as a RailGraph for src/world/rail.ts. */
export function skeletonRail(seed: string, net: RailNet, places: readonly Place[], roads: RoadGraph): RailGraph {
  const sites = new Map<string, TownSite>();
  const byId = new Map<string, Place>();
  const byName = new Map<string, TownSite>();
  for (const p of places) { const s = toSite(p); sites.set(p.id, s); byId.set(p.id, p); byName.set(p.name.toLowerCase(), s); }
  const at = new Map<string, RailLink[]>();
  for (const l of net.links) for (const id of [l.a, l.b]) { const a = at.get(id); if (a) a.push(l); else at.set(id, [l]); }
  const lineOf = new Map(net.lines.map((l) => [l.id, l]));
  const linkIdx = new Map(net.links.map((l) => [l.id, Number(l.id.split('/')[1])]));
  const lenCache = new Map<string, number>();
  const cum = new Map<string, number[]>();
  const segCells = (l: RailLink): number[] => {
    const line = lineOf.get(l.line)!, k = linkIdx.get(l.id)!;
    return line.cells.slice(line.at[k]!, line.at[k + 1]! + 1);
  };
  const linkLength = (l: RailLink): number => {
    let v = lenCache.get(l.id);
    if (v === undefined) {
      const cells = segCells(l);
      // (from the station's own position to the next one's, along the cells between)
      v = Math.max(cellTiles * 0.5, (pathKm(cells) * 1000) / TILE_M);
      lenCache.set(l.id, v);
    }
    return v;
  };
  const near = new Map<string, { site: TownSite; km: number }[]>();
  const rank = (s: TownSite): TownRank => {
    const p = byId.get(s.id);
    if (!p) return 'small';
    if (p.kind === 'hub') return 'major';
    if (p.kind === 'hamlet') return 'small';
    // (a junction is at least a town)
    const lines = new Set((at.get(p.id) ?? []).map((l) => l.line));
    return p.rank === 'small' && lines.size >= 2 ? 'town' : p.rank;
  };
  void archetypeOf;
  return {
    key: `skeleton|${seed}`,
    site: (id) => sites.get(id) ?? null,
    linksAt: (id) => at.get(id) ?? [],
    linkLength,
    alignment: (l) => {
      const cells = segCells(l);
      const pts: Pt[] = cells.map((c) => ({ x: ((c % W) + 0.5) * cellTiles, y: (((c / W) | 0) + 0.5) * cellTiles }));
      const a = sites.get(l.a)!.center, b = sites.get(l.b)!.center;
      if (pts.length) { pts[0] = { ...a }; pts[pts.length - 1] = { ...b }; }
      return pts;
    },
    pos: (line, id) => lineOf.get(line)!.stations.indexOf(id),
    alongLine: (line, i) => {
      let c = cum.get(line);
      if (!c) {
        const ln = lineOf.get(line)!;
        c = [0];
        for (let k = 0; k + 1 < ln.stations.length; k++) c.push(c[k]! + linkLength(net.links.find((x) => x.id === `${line}/${k}`)!));
        cum.set(line, c);
      }
      return c[Math.max(0, Math.min(c.length - 1, i))]!;
    },
    lineKind: (line) => lineOf.get(line)?.kind ?? 'branch',
    rank,
    findTown: (q) => byName.get(q.trim().toLowerCase()) ?? null,
    searchTowns: (prefix, _near, limit) => {
      const q = prefix.trim().toLowerCase();
      const out: TownSite[] = [];
      for (const s of sites.values()) if (s.name.toLowerCase().startsWith(q) || s.name.toLowerCase().includes(` ${q}`)) out.push(s);
      return out.sort((a, b) => a.name.localeCompare(b.name)).slice(0, limit);
    },
    nearby: (s) => {
      let v = near.get(s.id);
      if (!v) {
        const p = byId.get(s.id)!;
        // places on the same landmass within 60 km, by road (the road graph's drive, else the straight line x 1.3)
        const cands = places.filter((q) => q.mass === p.mass && q.id !== p.id && distKm(p.x, p.z, q.x, q.z) < 60)
          .map((q) => ({ q, d: distKm(p.x, p.z, q.x, q.z) })).sort((a, b) => a.d - b.d || (a.q.id < b.q.id ? -1 : 1)).slice(0, 40);
        v = cands.map(({ q, d }) => ({ site: sites.get(q.id)!, km: roads.drive(p.id, q.id, 2000)?.km ?? d * 1.3 })).sort((a, b) => a.km - b.km || (a.site.id < b.site.id ? -1 : 1));
        near.set(s.id, v);
      }
      return v;
    },
    inSearch: () => () => true,
    roadKm: (a, b) => roads.drive(a.id, b.id, 2000)?.km ?? null,
  };
}
void CELL_KM;
