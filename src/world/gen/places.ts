import { B, CR, koppenGroup, type Climate } from './climate';
import { CELL_KM, CELL_M, H, N, W, blur, distKm, latOf, nb, wrapDx, wrapX } from './grid';
import { CAPITAL, GAMMA_PLAN_TILES, HUB_COUNT, LAUNCH, laterHub, type HubSpec, type HubType, type Pin } from './hubs';
import { RIVER_FLOW, type Hydro } from './hydro';
import { hashU, hashStr, stream } from './noise';
import type { Terrain } from './terrain';
import { archetypeOf, type ArchetypeId } from '../../sim/archetype';

// PLACES (WORLD_GEN.md sections 3-4): the hubs by type with their site rules and scores (the capital first, honouring
// gamma's plan), then the generated towns (a Poisson-disk sampler weighted by suitability, 12-25 km apart), each town's
// archetype from its site. Hamlets come later, along the county roads (roads.ts).

export type PlaceKind = 'hub' | 'town' | 'hamlet';
export type Rank = 'small' | 'town' | 'major';

export interface WorldPin { kind: Pin['kind']; name: string; x: number; z: number; cell: number; ox: number; oz: number }

export interface Place {
  /** `h:<hub key>`, `t:<n>`, `m:<n>` (hamlets). */
  id: string;
  kind: PlaceKind;
  /** A hub's type, a town's archetype, or 'hamlet'. */
  type: HubType | ArchetypeId | 'hamlet';
  rank: Rank;
  /** Position in atlas cell units (x on [0, W)). */
  x: number;
  z: number;
  cell: number;
  /** Landmass id (terrain.masses[mass - 1]). */
  mass: number;
  /** The town's own seed (plan and history). */
  seed: string;
  name: string;
  founded: number;
  coastal: boolean;
  /** A hub's spec and pins (world positions). */
  hub?: HubSpec;
  pins?: WorldPin[];
}

export interface SiteFields {
  usable: Uint8Array;
  fertile: Uint8Array;
  flat: Float32Array;
  seaFrac: Float32Array;
  valley: Uint8Array;
  mainDonor: Int32Array;
  /** Flow at the top 5% of river cells (the planet's largest rivers). */
  bigFlow: number;
}

/** Crop belts good for a farm town (not ranching country). */
const FARMING = new Set([CR.wheat, CR.corn, CR.cotton, CR.orchards, CR.rice, CR.dairy]);

export function siteFields(t: Terrain, cl: Climate, h: Hydro, bio: Uint8Array, crops: Uint8Array): SiteFields {
  const usable = new Uint8Array(N), fertile = new Uint8Array(N);
  for (let c = 0; c < N; c++) {
    const b = bio[c]!;
    usable[c] = t.land[c] && !h.lake[c] && b !== B.ice && b !== B.glacier && b !== B.rock && t.elev[c]! < 2400 ? 1 : 0;
    fertile[c] = FARMING.has(crops[c]!) ? 1 : 0;
  }
  const flat = blur(cl.slope, 2, 2, 2);
  const sea = new Float32Array(N);
  for (let c = 0; c < N; c++) sea[c] = t.land[c] ? 0 : 1;
  const seaFrac = blur(sea, 5, 5, 1);
  const mean = blur(t.elev, 6, 6, 2);
  const valley = new Uint8Array(N);
  for (let c = 0; c < N; c++) valley[c] = t.land[c] && t.elev[c]! < mean[c]! - 60 ? 1 : 0;
  const mainDonor = new Int32Array(N).fill(-1);
  for (let c = 0; c < N; c++) {
    const r = h.recv[c]!;
    if (r >= 0 && t.land[r] && (mainDonor[r]! < 0 || h.flow[c]! > h.flow[mainDonor[r]!]!)) mainDonor[r] = c;
  }
  const rf: number[] = [];
  for (let c = 0; c < N; c++) if (t.land[c] && !h.lake[c] && h.flow[c]! >= RIVER_FLOW) rf.push(h.flow[c]!);
  rf.sort((a, b) => a - b);
  return { usable, fertile, flat, seaFrac, valley, mainDonor, bigFlow: rf[Math.floor(rf.length * 0.95)] ?? Infinity };
}

const temperate = (T: number): number => Math.exp(-((T - 13) / 6) * ((T - 13) / 6));

/** The direction a river runs at cell [c] over about [n] cells up and down: (dx, dz) in cells, downstream. */
export function riverDir(h: Hydro, f: SiteFields, c: number, n = 6): [number, number] {
  let up = c, dn = c;
  for (let k = 0; k < n; k++) { const u = f.mainDonor[up]!; if (u >= 0) up = u; const d = h.recv[dn]!; if (d >= 0) dn = d; }
  return [wrapDx((dn % W) - (up % W)), ((dn / W) | 0) - ((up / W) | 0)];
}

export interface HubRuleCheck { ok: boolean; why: string }

/** The hard site rule of a hub type at cell [c] (also what the tests check). */
export function hubRule(type: HubType, t: Terrain, cl: Climate, h: Hydro, f: SiteFields, c: number): HubRuleCheck {
  const e = t.elev[c]!, T = cl.temp[c]!, lat = Math.abs(latOf(((c / W) | 0) + 0.5));
  const no = (why: string): HubRuleCheck => ({ ok: false, why });
  if (!f.usable[c]) return no('not usable land');
  switch (type) {
    case 'capital': {
      if (h.flow[c]! < RIVER_FLOW * 1.5 || h.flow[c]! > 9000) return no('no river of the plan\'s size');
      if (T < 5 || T > 21) return no('not temperate');
      { const g = koppenGroup(cl.koppen[c]!); if (g !== 'C' && g !== 'D') return no('not a temperate climate class'); }
      if (f.flat[c]! > 30) return no('not flat');
      if (t.distSea[c]! < 12) return no('too near the coast');
      const [dx, dz] = riverDir(h, f, c);
      if (Math.abs(dz) < 1.6 * Math.abs(dx) || Math.abs(dz) < 3) return no('river not north-south');
      // the plan's pad and its growth margin: land, no lake, not steep
      const hw = Math.ceil((CAPITAL.padM.w / 2 + CAPITAL.growthM) / CELL_M), hh = Math.ceil((CAPITAL.padM.h / 2 + CAPITAL.growthM) / CELL_M);
      const i0 = c % W, j0 = (c / W) | 0;
      for (let j = j0 - hh; j <= j0 + hh; j++) for (let i = i0 - hw; i <= i0 + hw; i++) {
        if (j < 0 || j >= H) return no('pad off the map');
        const q = j * W + wrapX(i);
        if (!t.land[q] || h.lake[q] || cl.slope[q]! > 70) return no('pad not clear');
      }
      return { ok: true, why: '' };
    }
    case 'port':
      if (!h.coast[c] || lat > 60) return no('not a coast');
      if (h.coast[c] === 3) return no('cliff coast');
      if (f.seaFrac[c]! < 0.12 || f.seaFrac[c]! > 0.55) return no('not sheltered');
      return deepNear(t, c) ? { ok: true, why: '' } : no('no deep water close in');
    case 'river city':
      if (h.flow[c]! < f.bigFlow || e > 400 || t.distSea[c]! < 8 || f.flat[c]! > 25) return no('not on a big river, low and flat');
      if (h.steady[c]! < 0.08) return no('the river runs dry part of the year');
      return { ok: true, why: '' };
    case 'mountain town':
      if (e < 800 || e > 2000 || !f.valley[c]) return no('not a high valley');
      return { ok: true, why: '' };
    case 'mill town': {
      const r = h.recv[c]!;
      const fall = r >= 0 ? e - Math.max(0, t.elev[r]!) : 0;
      if (h.flow[c]! < RIVER_FLOW * 1.3 || h.flow[c]! > 6000 || fall < 8) return no('no river with fall');
      if (h.steady[c]! < 0.25) return no('the river is not reliable all year');
      return { ok: true, why: '' };
    }
    case 'farm town':
      if (!f.fertile[c] || f.flat[c]! > 12 || cl.rain[c]! < 420 || e > 600) return no('not flat fertile plains');
      if (koppenGroup(cl.koppen[c]!) === 'B' && cl.rain[c]! < 500) return no('too dry to farm');
      return { ok: true, why: '' };
  }
}

function deepNear(t: Terrain, c: number): boolean {
  const i0 = c % W, j0 = (c / W) | 0;
  for (let j = j0 - 6; j <= j0 + 6; j++) for (let i = i0 - 6; i <= i0 + 6; i++) {
    if (j < 0 || j >= H) continue;
    if (t.elev[j * W + wrapX(i)]! < -150) return true;
  }
  return false;
}

function hubScore(type: HubType, t: Terrain, cl: Climate, h: Hydro, f: SiteFields, c: number, central: number): number {
  const T = cl.temp[c]!, e = t.elev[c]!;
  const rflat = Math.exp(-f.flat[c]! / 25);
  switch (type) {
    case 'capital': {
      const dl = t.distSea[c]!;
      return temperate(T) * rflat * (1 + central) * (0.3 + Math.exp(-((dl - 40) / 25) * ((dl - 40) / 25))) * (1 + f.fertile[c]!);
    }
    case 'port': {
      let mouth = 0;
      for (let k = 0; k < 8; k++) { const m = nb(c, k); if (m >= 0 && h.flow[m]! >= RIVER_FLOW) mouth = 1; }
      return Math.exp(-((f.seaFrac[c]! - 0.33) / 0.15) * ((f.seaFrac[c]! - 0.33) / 0.15)) * (1 + mouth) * (0.3 + temperate(T)) * rflat;
    }
    case 'river city': {
      let conf = 0;
      for (let k = 0; k < 8; k++) { const m = nb(c, k); if (m >= 0 && h.recv[m] === c && h.flow[m]! >= RIVER_FLOW * 3) conf++; }
      return Math.log1p(h.flow[c]!) * rflat * (0.4 + temperate(T)) * (conf >= 2 ? 1.5 : 1);
    }
    case 'mountain town': {
      let riv = 0;
      for (let k = 0; k < 8; k++) { const m = nb(c, k); if (m >= 0 && h.flow[m]! >= RIVER_FLOW) riv = 1; }
      return (0.5 + riv) * Math.exp(-((e - 1300) / 400) * ((e - 1300) / 400)) * (0.3 + temperate(T + 6));
    }
    case 'mill town': {
      const r = h.recv[c]!;
      const fall = r >= 0 ? e - Math.max(0, t.elev[r]!) : 0;
      return Math.log1p(fall) * (0.5 + temperate(T));
    }
    case 'farm town': {
      const R = cl.rain[c]!;
      return rflat * Math.exp(-((R - 750) / 300) * ((R - 750) / 300)) * (0.4 + temperate(T)) * (t.distSea[c]! / (t.distSea[c]! + 15)) * (t.mount[c]! < 50 ? 1.2 : 0.8);
    }
  }
}

/** Minimum km between hubs (ports among themselves: PORT_SEP). */
export const HUB_SEP = 70;
export const PORT_SEP = 120;

export interface HubPlacement { hubs: Place[]; startMass: number; neighbourMass: number }

/**
 * The hubs in a fixed order, each placed with the earlier ones fixed (so hubs 7-22 never move the launch hubs): the
 * capital on the start continent (the largest), the launch hubs on it or its nearest neighbour, a port on that
 * neighbour, a port on every continent, then the later hubs by type round the continents, to [count] in all.
 */
export function placeHubs(t: Terrain, cl: Climate, h: Hydro, f: SiteFields, count = HUB_COUNT): HubPlacement {
  const seed = t.seed;
  const conts = t.masses.filter((m) => m.kind === 'continent');
  const start = conts[0]!;
  const others = conts.slice(1).sort((a, b) => distKm(start.cx, start.cz, a.cx, a.cz) - distKm(start.cx, start.cz, b.cx, b.cz) || a.id - b.id);
  const neighbour = others[0] ?? start;
  const hubs: Place[] = [];
  const cand = (mass: number): Int32Array => { const out: number[] = []; for (let c = 0; c < N; c++) if (t.mass[c] === mass) out.push(c); return Int32Array.from(out); };
  const cellsOf = new Map<number, Int32Array>();
  const cellsFor = (m: number): Int32Array => { let a = cellsOf.get(m); if (!a) { a = cand(m); cellsOf.set(m, a); } return a; };

  const place = (spec: HubSpec, mass: number): Place | null => {
    if (hubs.length >= count) return null;
    const m = t.masses[mass - 1]!;
    const hs = hashStr(`${seed}|site|${spec.key}`);
    let best = -1, bc = -1;
    for (const c of cellsFor(mass)) {
      const x = (c % W) + 0.5, z = ((c / W) | 0) + 0.5;
      let far = true;
      for (const o of hubs) {
        const d = distKm(x, z, o.x, o.z);
        if (d < (spec.type === 'port' && o.type === 'port' ? PORT_SEP : HUB_SEP)) { far = false; break; }
      }
      if (!far) continue;
      if (!hubRule(spec.type, t, cl, h, f, c).ok) continue;
      const central = Math.exp(-((distKm(x, z, m.cx, m.cz) / 90) ** 2));
      const s = hubScore(spec.type, t, cl, h, f, c, central) * (0.85 + 0.3 * hashU(hs, c));
      if (s > best || (s === best && c < bc)) { best = s; bc = c; }
    }
    if (bc < 0) return null;
    let x = (bc % W) + 0.5, z = ((bc / W) | 0) + 0.5;
    // the capital's plan: its river column (plan x 706.5) on the river cell's centre
    if (spec.type === 'capital') x += ((GAMMA_PLAN_TILES.cx - 706.5) * 2.1) / CELL_M;
    const p: Place = {
      id: `h:${spec.key}`, kind: 'hub', type: spec.type, rank: 'major', x: wrapX(x), z, cell: bc, mass, seed: spec.plan ?? `${seed}/h/${spec.key}`,
      name: '', founded: 0, coastal: h.coast[bc]! > 0, hub: spec, pins: [],
    };
    p.pins = pinsOf(t, h, f, p, spec);
    hubs.push(p);
    return p;
  };

  if (!place(CAPITAL, start.id)) {
    // (no site meets the capital's rules on the start continent: take the best on any continent, then say so in the stats)
    for (const o of others) if (place(CAPITAL, o.id)) break;
  }
  const capMass = hubs[0]?.mass ?? start.id;
  const nb2 = capMass === neighbour.id ? start.id : neighbour.id;
  let onStart = 0;
  for (const spec of LAUNCH) {
    if (spec.key === 'launch/port2') { if (!place(spec, nb2)) for (const o of others) if (o.id !== capMass && place(spec, o.id)) break; continue; }
    // the capital's continent holds the capital and about three launch hubs (the port first); the rest on the neighbour
    const first = spec.type === 'port' || onStart < 3 ? capMass : nb2;
    const second = first === capMass ? nb2 : capMass;
    const p = place(spec, first) ?? place(spec, second) ?? others.reduce<Place | null>((got, o) => got ?? (o.id !== first && o.id !== second ? place(spec, o.id) : null), null);
    if (p && p.mass === capMass) onStart++;
  }
  let n = hubs.length;
  for (const m of conts) if (!hubs.some((q) => q.mass === m.id && q.type === 'port')) place(laterHub(n++, 'port'), m.id);
  const cyc: HubType[] = ['river city', 'farm town', 'mountain town', 'mill town', 'port'];
  for (let k = 0; hubs.length < count && k < 120; k++) place(laterHub(n++, cyc[Math.floor(k / conts.length) % cyc.length]!), conts[k % conts.length]!.id);
  return { hubs, startMass: capMass, neighbourMass: nb2 };
}

function pinsOf(t: Terrain, h: Hydro, f: SiteFields, p: Place, spec: HubSpec): WorldPin[] {
  // the capital's river pins follow the world river's direction: the upstream pin is where it enters the plan
  let pins = spec.pins;
  if (spec.type === 'capital') {
    const [, dz] = riverDir(h, f, p.cell);
    if (dz < 0) pins = pins.map((q) => (q.kind === 'river' ? { ...q, name: q.name === 'river north' ? 'river out (north)' : 'river in (south)' } : q));
    else pins = pins.map((q) => (q.kind === 'river' ? { ...q, name: q.name === 'river north' ? 'river in (north)' : 'river out (south)' } : q));
  }
  return pins.map((q) => {
    const x = wrapX(p.x + q.dx / CELL_M), z = Math.min(H - 0.5, Math.max(0.5, p.z + q.dz / CELL_M));
    return { kind: q.kind, name: q.name, x, z, cell: Math.floor(z) * W + Math.floor(x), ox: q.ox, oz: q.oz };
  });
  void t;
}

// ---------------------------------------------------------------- towns

/** Town spacing (km): 12 on the best land, up to 25 on poor land; none within TOWN_HUB_KM of a hub. */
export const TOWN_MIN_KM = 12;
export const TOWN_MAX_KM = 25;
export const TOWN_HUB_KM = 15;

export function placeTowns(t: Terrain, cl: Climate, h: Hydro, f: SiteFields, bio: Uint8Array, hubs: Place[]): Place[] {
  const seed = t.seed;
  const suit = new Float32Array(N);
  const near = blur(Float32Array.from(h.flow, (v, c) => (t.land[c] && v >= RIVER_FLOW ? 1 : 0)), 1.5, 1.5, 1);
  for (let c = 0; c < N; c++) {
    if (!f.usable[c] || bio[c] === B.alpine) continue;
    const rflat = Math.exp(-f.flat[c]! / 25);
    suit[c] = rflat * (0.3 + f.fertile[c]!) * (1 + 0.8 * Math.min(1, near[c]! * 3)) * (1 + 0.5 * (h.coast[c]! > 0 ? 1 : 0)) * (0.3 + temperate(cl.temp[c]!));
  }
  const hs = hashStr(`${seed}|towns`);
  const keys: { c: number; k: number }[] = [];
  for (let c = 0; c < N; c++) if (suit[c]! > 0.02) keys.push({ c, k: Math.pow(hashU(hs, c), 1 / suit[c]!) });
  keys.sort((a, b) => b.k - a.k || a.c - b.c);
  const BK = 24; // bucket (cells)
  const nbx = Math.ceil(W / BK);
  const buckets = new Map<number, Place[]>();
  const bkey = (x: number, z: number): number => Math.floor(z / BK) * nbx + (Math.floor(x / BK) % nbx);
  const nearAny = (x: number, z: number, km: number, list: Map<number, Place[]>): boolean => {
    const bx = Math.floor(x / BK), bz = Math.floor(z / BK);
    for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
      const a = list.get((bz + dz) * nbx + (((bx + dx) % nbx) + nbx) % nbx);
      if (a) for (const p of a) if (distKm(x, z, p.x, p.z) < km) return true;
    }
    return false;
  };
  const hubB = new Map<number, Place[]>();
  for (const p of hubs) { const k = bkey(p.x, p.z); hubB.set(k, [...(hubB.get(k) ?? []), p]); }
  const towns: Place[] = [];
  const rr = stream(seed, 'town-rank');
  for (const { c } of keys) {
    const x = (c % W) + 0.5, z = ((c / W) | 0) + 0.5;
    const km = TOWN_MIN_KM + (TOWN_MAX_KM - TOWN_MIN_KM) * (1 - Math.min(1, suit[c]! / 1.5));
    if (nearAny(x, z, TOWN_HUB_KM, hubB) || nearAny(x, z, km, buckets)) continue;
    const n = towns.length;
    const r = hashU(hashStr(`${seed}|rank`), c);
    void rr;
    const rank: Rank = r < 0.06 ? 'major' : r < 0.45 ? 'town' : 'small';
    const type = townType(t, cl, h, f, c, hubs);
    const p: Place = { id: `t:${n}`, kind: 'town', type, rank, x, z, cell: c, mass: t.mass[c]!, seed: townSeed(seed, n, type), name: '', founded: 0, coastal: h.coast[c]! > 0 };
    towns.push(p);
    const k = bkey(x, z);
    let a = buckets.get(k); if (!a) { a = []; buckets.set(k, a); } a.push(p);
  }
  return towns;
}

/** A town's archetype from its site (WORLD_GEN.md 4): harbour on the coast, mining in the mountains, mill on a river with
 * fall, resort by a lake or beach, suburb near a hub, farm on the plains. */
function townType(t: Terrain, cl: Climate, h: Hydro, f: SiteFields, c: number, hubs: Place[]): ArchetypeId {
  const x = (c % W) + 0.5, z = ((c / W) | 0) + 0.5;
  const roll = hashU(hashStr(`${t.seed}|town-type`), c);
  let lakeNear = false;
  const i0 = c % W, j0 = (c / W) | 0;
  for (let j = j0 - 3; j <= j0 + 3 && !lakeNear; j++) for (let i = i0 - 3; i <= i0 + 3; i++) if (j >= 0 && j < H && h.lake[j * W + wrapX(i)]) { lakeNear = true; break; }
  if (h.coast[c] && roll < 0.75) return h.coast[c] === 1 && roll < 0.15 ? 'resort' : 'harbor';
  if (lakeNear && roll < 0.6) return 'resort';
  if (t.elev[c]! > 750 || t.mount[c]! > 300) return 'mining';
  const r = h.recv[c]!;
  if (h.flow[c]! >= RIVER_FLOW && r >= 0 && t.elev[c]! - Math.max(0, t.elev[r]!) > 5) return 'mill';
  if (hubs.some((p) => distKm(x, z, p.x, p.z) < 25)) return 'suburb';
  void cl; void f;
  return 'farm';
}

/** A town's seed whose archetype (src/sim/archetype.ts, from the seed) is the site's: `<world>/t/<n>`, re-keyed until it
 * matches, so the simulated town is the kind of town its site says. */
export function townSeed(world: string, n: number, type: ArchetypeId): string {
  for (let k = 0; k < 60; k++) {
    const s = k ? `${world}/t/${n}#${k}` : `${world}/t/${n}`;
    if (archetypeOf(s).id === type) return s;
  }
  return `${world}/t/${n}`;
}

/** The year a place was first settled: the land was settled from the coasts inland (older near the sea and the ports). */
export function foundedYear(t: Terrain, p: Place, hubs: readonly Place[]): number {
  const ports = hubs.filter((q) => q.type === 'port' && q.mass === p.mass);
  const dPort = ports.length ? Math.min(...ports.map((q) => distKm(p.x, p.z, q.x, q.z))) : 200;
  const inland = Math.min(1, t.distSea[p.cell]! / 150) * 0.5 + Math.min(1, dPort / 300) * 0.5;
  const base = p.kind === 'hub' ? 1760 : p.kind === 'town' ? 1790 : 1820;
  const span = p.kind === 'hub' ? 60 : p.kind === 'town' ? 90 : 90;
  const jitter = hashU(hashStr(`${t.seed}|founded`), p.cell) * 30;
  return Math.min(1935, Math.round(base + span * inland + jitter));
}
void CELL_KM;
