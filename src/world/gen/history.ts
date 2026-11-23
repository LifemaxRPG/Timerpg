import { CELL_KM2, H, N, W, distKm } from './grid';
import type { Hydro } from './hydro';
import { hashStr, hashU } from './noise';
import type { Place, SiteFields } from './places';
import type { Road } from './roads';
import type { TownRoads } from './highways';
import type { SeaRoute } from './sea';
import type { Terrain } from './terrain';
import type { RailLink } from '../rail';

// HISTORY BY YEAR (WORLD_GEN.md section 9): the world is f(seed, year). Roads carry their own years (roads.ts: built,
// paved, expressway); the railroads theirs (rail.ts's rules on the skeleton's links); sea routes their eras (sea.ts).
// This adds the dams and their reservoirs, the ports' eras, and the places' populations by year from their site and
// their connections (towns on the expressway grow, towns whose branch line closed or that the expressway bypassed
// shrink, a few hamlets become ghost towns).

export interface Dam {
  id: string;
  /** The dam's cell, the river's flow there, the crest (m above the river bed) and the reservoir's level. */
  cell: number;
  flow: number;
  crest: number;
  level: number;
  year: number;
  /** The reservoir's cells (flooded from [year]) and its area. */
  cells: number[];
  areaKm2: number;
  /** Hamlets drowned under it (a legend ready for lore.ts). */
  drowned: string[];
}

export function makeDams(t: Terrain, h: Hydro, f: SiteFields, places: readonly Place[]): Dam[] {
  const hs = hashStr(`${t.seed}|dams`);
  const donors = new Map<number, number[]>();
  for (let c = 0; c < N; c++) { const r = h.recv[c]!; if (r >= 0 && t.land[c]) { const a = donors.get(r); if (a) a.push(c); else donors.set(r, [c]); } }
  const cand: { c: number; s: number }[] = [];
  for (let c = 0; c < N; c++) {
    if (!t.land[c] || h.lake[c] || h.flow[c]! < f.bigFlow * 0.25) continue;
    // where a big river leaves a gorge: it fell steeply over the last 8 cells upstream
    let up = c;
    for (let k = 0; k < 8; k++) { const u = f.mainDonor[up]!; if (u < 0) break; up = u; }
    const drop = t.elev[up]! - t.elev[c]!;
    if (drop < 80) continue;
    cand.push({ c, s: drop * Math.log1p(h.flow[c]!) * (0.8 + 0.4 * hashU(hs, c)) });
  }
  cand.sort((a, b) => b.s - a.s || a.c - b.c);
  const dams: Dam[] = [];
  const perMass = new Map<number, number>();
  const placeCells = new Map<number, Place>();
  for (const p of places) placeCells.set(p.cell, p);
  for (const { c } of cand) {
    if (dams.length >= 24) break;
    const m = t.mass[c]!;
    if ((perMass.get(m) ?? 0) >= 2) continue;
    const x = (c % W) + 0.5, z = ((c / W) | 0) + 0.5;
    if (dams.some((d) => distKm(x, z, (d.cell % W) + 0.5, ((d.cell / W) | 0) + 0.5) < 60)) continue;
    if (places.some((p) => (p.kind === 'hub' && distKm(x, z, p.x, p.z) < 15) || (p.kind !== 'hamlet' && p.cell === c))) continue;
    const crest = 30 + Math.floor(hashU(hs, c, 1) * 50);
    const level = t.elev[c]! + crest;
    // the reservoir: upstream cells below the crest
    const cells: number[] = [];
    const stack = [c];
    const seen = new Set([c]);
    let bad = false;
    while (stack.length && cells.length < 400) {
      const q = stack.pop()!;
      cells.push(q);
      const pl = placeCells.get(q);
      if (pl && pl.kind !== 'hamlet' && q !== c) { bad = true; break; }
      for (const d of donors.get(q) ?? []) if (!seen.has(d) && t.elev[d]! < level) { seen.add(d); stack.push(d); }
    }
    if (bad || cells.length < 6) continue;
    const drowned = cells.map((q) => placeCells.get(q)).filter((p): p is Place => !!p && p.kind === 'hamlet').map((p) => p.id);
    dams.push({ id: `dam:${dams.length}`, cell: c, flow: Math.round(h.flow[c]!), crest, level: Math.round(level), year: 1930 + Math.floor(hashU(hs, c, 2) * 41), cells, areaKm2: Math.round(cells.length * CELL_KM2), drowned });
    perMass.set(m, (perMass.get(m) ?? 0) + 1);
  }
  return dams;
}

/** A port's era in [year] (WORLD_GEN.md 9, "Ports"). */
export function portEra(p: Place, year: number, routes: readonly SeaRoute[]): 'harbour' | 'liner port' | 'container port' | 'ferry port' | 'marina' {
  const mine = routes.filter((r) => r.a === p.id || r.b === p.id);
  const liner = mine.some((r) => (r.kind === 'liner' || r.kind === 'round') && year >= r.opened && (r.ends === undefined || year < r.ends));
  if (p.kind === 'hub' && year >= 1972 + Math.floor(hashU(hashStr(p.id), 1) * 8)) return year >= 1990 && hashU(hashStr(p.id), 2) < 0.3 ? 'marina' : 'container port';
  if (liner) return 'liner port';
  return mine.some((r) => r.kind === 'ferry') ? 'ferry port' : 'harbour';
}

export interface GrowthFacts { expressway?: number; bypassed?: number; branchEnd?: number; hubKm: number; dam?: number; ghost?: number }

/** What a place's growth depends on (computed once per world). */
export function growthFacts(p: Place, roads: readonly Road[], links: readonly RailLink[], hubs: readonly Place[], dams: readonly Dam[], tr?: TownRoads): GrowthFacts {
  void roads;
  const out: GrowthFacts = { hubKm: Math.min(999, ...hubs.filter((q) => q.mass === p.mass && q.id !== p.id).map((q) => distKm(p.x, p.z, q.x, q.z))) };
  // (Highways v2: a town grows from its interchange's year when that is within 6 km; a bypassed Main Street declines)
  if (tr?.interchangeYear !== undefined && (tr.interchangeKm ?? 99) <= 6) out.expressway = tr.interchangeYear;
  if (tr?.bypassed) out.bypassed = tr.bypassed.year;
  const rl = links.filter((l) => l.a === p.id || l.b === p.id);
  if (rl.length && rl.every((l) => l.kind === 'branch')) out.branchEnd = Math.min(...rl.map((l) => l.passengerEnd ?? 9999));
  const d = dams.find((q) => distKm(p.x, p.z, (q.cell % W) + 0.5, ((q.cell / W) | 0) + 0.5) < 8);
  if (d) out.dam = d.year;
  if (p.kind === 'hamlet' && !rl.length && hashU(hashStr(p.seed), 9) < 0.08) out.ghost = 1935 + Math.floor(hashU(hashStr(p.seed), 10) * 50);
  void H;
  return out;
}

/** A place's population in [year]: a cheap curve from its kind, rank and connections (hubs' plans simulate theirs). */
export function population(p: Place, g: GrowthFacts, year: number): number {
  if (year < p.founded) return 0;
  const r = hashU(hashStr(p.seed), 3);
  const base = p.kind === 'hub' ? (p.type === 'capital' ? 60000 : 18000 + 30000 * r) : p.kind === 'hamlet' ? 40 + 160 * r : p.rank === 'major' ? 6000 + 14000 * r : p.rank === 'town' ? 1200 + 3800 * r : 250 + 900 * r;
  // settlement, then slow growth to 1950, then the post-war decades decide
  const age = Math.min(1, (year - p.founded) / 60);
  let v = base * (0.15 + 0.85 * age) * (1 + 0.004 * Math.max(0, Math.min(year, 1950) - 1900));
  const after = (y: number | undefined): number => (y === undefined ? 0 : Math.max(0, year - y));
  if (g.expressway !== undefined) v *= 1 + 0.012 * after(g.expressway);
  if (g.bypassed !== undefined) v *= Math.max(0.8, 1 - 0.004 * after(g.bypassed));
  if (g.branchEnd !== undefined) v *= Math.max(0.55, 1 - 0.008 * after(g.branchEnd));
  if (p.kind !== 'hub' && g.hubKm < 30) v *= 1 + 0.015 * after(1950); // the suburbs
  if (g.dam !== undefined) v *= 1 + 0.01 * Math.min(30, after(g.dam));
  if (g.ghost !== undefined && year >= g.ghost) v = Math.max(0, v * (1 - (year - g.ghost) / 15));
  if (p.kind === 'hub' && year > 1950) v *= 1 + 0.01 * (year - 1950);
  return Math.round(v);
}
