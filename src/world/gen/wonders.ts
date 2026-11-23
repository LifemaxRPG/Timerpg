import { B, KOPPEN, MONTHS, koppenGroup, type Climate } from './climate';
import { CELL_KM, H, N, W, blur, distKm, latOf, nb, wrapDx, wrapX } from './grid';
import { RIVER_FLOW, type Hydro } from './hydro';
import type { NameRegistry } from './names';
import { Fbm, hashStr, hashU } from './noise';
import type { Place, SiteFields } from './places';
import { RANGE_GREAT, type Terrain } from './terrain';
import { regionOf, type Weather } from './weather';

// NATURAL WONDERS (WORLD_GEN.md "Natural wonders", the owner's request of 2026-11-23): a pass after hydrology, biomes and
// the places. Every wonder stands where the generator already models its natural cause (plates and ranges, height,
// climate, rivers, the coast), so it appears where it makes sense and is the same for every seed's run. Each has a stable
// id, a kind, a rarity tier, a position, an extent, a unique name from the place registry and the parameters a renderer
// will need (a waterfall's drop and river, a blossom grove's colour and bloom months, a canyon's depth and rock bands...).
// Nothing is drawn here: W5b and later draw them.
//
// Rarity: common (pleasant, everywhere it fits), uncommon (a few per region: one per kind within 40-150 km), rare (a few
// per continent; every continent gets at least one), legendary (1-3 on the planet, the best of their kind, spoken of
// everywhere).
//
// Two geological fields are new here (the skeleton had no rock types): limestone and sandstone country as slow noises,
// limestone kept off the young ranges (sedimentary platforms), sandstone where the land is dry and high. Volcanoes stand
// on the converging ranges near the coast (arcs over a subducting plate) and over a few hotspots.

export type WonderTier = 'common' | 'uncommon' | 'rare' | 'legendary';
export const TIERS: readonly WonderTier[] = ['common', 'uncommon', 'rare', 'legendary'];

export type WonderKind =
  | 'blossom valley' | 'meadow' | 'superbloom' | 'waterfall' | 'canyon' | 'arches' | 'karst' | 'table mountain' | 'volcano'
  | 'hot springs' | 'glacier' | 'fjord' | 'giant trees' | 'salt flat' | 'sea stacks' | 'atoll' | 'glowing bay' | 'fireflies'
  | 'aurora' | 'cave';

export type WonderParam = number | string | boolean | number[] | string[];

export interface Wonder {
  /** `w:<kind>:<n>` (the kind's words joined by '-'), stable for the seed. */
  id: string;
  kind: WonderKind;
  tier: WonderTier;
  name: string;
  /** Position in atlas cell units (x on [0, W)), its cell and landmass (0 at sea). */
  x: number;
  z: number;
  cell: number;
  mass: number;
  /** How far it reaches from its position (km): a grove's radius, half a canyon's length. */
  extentKm: number;
  /** Who knows it: everyone on the planet (legendary), the continent (rare), the county (uncommon), locals (common). */
  fame: 'planet' | 'continent' | 'county' | 'local';
  /** What a renderer needs (by kind; see WONDER_PARAMS). */
  params: Record<string, WonderParam>;
}

/** The parameters each kind carries (documented in WORLD_GEN.md "The wonders pass"). */
export const WONDER_PARAMS: Record<WonderKind, string[]> = {
  'blossom valley': ['colour', 'tree', 'bloomMonths', 'ringM'],
  meadow: ['flowers', 'bloomMonths'],
  superbloom: ['flowers', 'bloomMonths', 'wetYearChance'],
  waterfall: ['river', 'dropM', 'flow', 'widthM', 'facingDeg', 'peakMonth', 'steady', 'style', 'mist'],
  canyon: ['river', 'depthM', 'lengthKm', 'widthKm', 'headingDeg', 'bands', 'from', 'to'],
  arches: ['formations', 'count', 'rock'],
  karst: ['towerM', 'towersPerKm2', 'cenotes', 'rice'],
  'table mountain': ['cliffM', 'topKm2', 'falls', 'fallDropM'],
  volcano: ['coneM', 'type', 'craterLake', 'active', 'lastEruption', 'hotSprings'],
  'hot springs': ['geysers', 'terraces', 'waterC', 'colour'],
  glacier: ['lengthKm', 'headM', 'snoutM', 'lakeColour', 'iceCave'],
  fjord: ['lengthKm', 'wallM', 'headingDeg'],
  'giant trees': ['heightM', 'girthM', 'fogDays'],
  'salt flat': ['areaKm2', 'mirrorMonths', 'lakeColour', 'lake'],
  'sea stacks': ['count', 'arch', 'caves', 'blackSand', 'facingDeg'],
  atoll: ['lagoonKm', 'islets'],
  'glowing bay': ['months', 'colour'],
  fireflies: ['months'],
  aurora: ['months', 'clearNights'],
  cave: ['lengthKm', 'undergroundRiver', 'rock'],
};

/** The rare kinds (one of them is promoted on any continent left without a rare wonder). */
const RARE_KINDS: readonly WonderKind[] = ['canyon', 'arches', 'karst', 'volcano', 'fjord', 'giant trees', 'salt flat'];

interface Cand { c: number; s: number }

const FAME: Record<WonderTier, Wonder['fame']> = { common: 'local', uncommon: 'county', rare: 'continent', legendary: 'planet' };

export function makeWonders(t: Terrain, cl: Climate, h: Hydro, bio: Uint8Array, wx: Weather, f: SiteFields, places: readonly Place[], reg: NameRegistry): Wonder[] {
  const seed = t.seed;
  const hs = hashStr(`${seed}|wonders`);
  const keyHash = new Map<string, number>();
  const jit = (c: number, k: string): number => { let kh = keyHash.get(k); if (kh === undefined) { kh = hashStr(k); keyHash.set(k, kh); } return hashU(hs, c, kh); };
  const land = t.land, elev = t.elev;
  const kg = (c: number): string => KOPPEN[cl.koppen[c]!]!;
  const latC = (c: number): number => latOf(((c / W) | 0) + 0.5);
  const cx = (c: number): number => (c % W) + 0.5, cz = (c: number): number => ((c / W) | 0) + 0.5;
  // ---- fields
  const mountRing = blur(t.mount, 10, 10, 2); // mountains round a place (a valley ringed by them)
  const meanE = blur(Float32Array.from(elev, (e) => Math.max(0, e)), 7, 7, 2);
  const limeN = new Fbm(seed, 'limestone', 14, 4), sandN = new Fbm(seed, 'sandstone', 18, 4);
  const lime = (c: number): number => (t.mount[c]! < 400 ? limeN.at(cx(c), cz(c)) : 0);
  const sand = (c: number): number => sandN.at(cx(c), cz(c));
  // the hubs and towns keep their ground (no wonder on a town's site)
  const placeMask = new Uint8Array(N);
  const placeAt = { add: (c: number): void => { placeMask[c] = 1; }, has: (c: number): boolean => placeMask[c] === 1 };
  for (const p of places) if (p.kind !== 'hamlet') for (let k = -1; k < 8; k++) { const m = k < 0 ? p.cell : nb(p.cell, k); if (m >= 0) placeAt.add(m); }
  for (const p of places) if (p.hub) {
    const r = Math.ceil((Math.max(p.hub.padM.w, p.hub.padM.h) / 2 + p.hub.growthM) / 1075.2);
    for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) { const j = Math.floor(p.z) + dj; if (j >= 0 && j < H) placeAt.add(j * W + (((Math.floor(p.x) + di) % W) + W) % W); }
  }
  const out: Wonder[] = [];
  const counts = new Map<string, number>();
  const taken: { x: number; z: number; kind: WonderKind }[] = [];
  const add = (kind: WonderKind, tier: WonderTier, c: number, extentKm: number, params: Record<string, WonderParam>, at?: [number, number]): Wonder => {
    const slug = kind.replace(/ /g, '-');
    const n = counts.get(slug) ?? 0;
    counts.set(slug, n + 1);
    const id = `w:${slug}:${n}`;
    const [x, z] = at ?? [cx(c), cz(c)];
    const wd: Wonder = { id, kind, tier, name: reg.makeWonder(id, kind), x, z, cell: c, mass: t.mass[c]!, extentKm: Math.round(extentKm * 10) / 10, fame: FAME[tier], params };
    out.push(wd);
    taken.push({ x, z, kind });
    disc(x, z, 3, (q) => { anyMask[q] = 1; });
    return wd;
  };
  /** The best candidates [cs] kept [sepKm] apart (from their own kind, and 3 km from any wonder), at most [perMass] on a
   * landmass and [total] in all. */
  // (spacing by masks: a stamp per choice round where a disc of the kind's spacing round each of its wonders is blocked,
  // and 3 km round every wonder of any kind)
  const kindMask = new Int32Array(N), anyMask = new Uint8Array(N);
  let round = 0;
  const disc = (x: number, z: number, km: number, mark: (c: number) => void): void => {
    const r = Math.ceil(km / CELL_KM);
    for (let dj = -r; dj <= r; dj++) {
      const j = Math.floor(z) + dj;
      if (j < 0 || j >= H) continue;
      for (let di = -r; di <= r; di++) { const i = ((Math.floor(x) + di) % W + W) % W; if (distKm(x, z, i + 0.5, j + 0.5) < km) mark(j * W + i); }
    }
  };
  const choose = (kind: WonderKind, cs: Cand[], sepKm: number, perMass: number, total: number): number[] => {
    cs.sort((a, b) => b.s - a.s || a.c - b.c);
    const gen = ++round;
    const block = (x: number, z: number): void => disc(x, z, sepKm, (q) => { kindMask[q] = gen; });
    for (const q of taken) if (q.kind === kind) block(q.x, q.z);
    const got: number[] = [];
    const per = new Map<number, number>();
    for (const { c } of cs) {
      if (got.length >= total) break;
      if (kindMask[c] === gen || anyMask[c]) continue;
      const m = t.mass[c]!;
      if ((per.get(m) ?? 0) >= perMass) continue;
      got.push(c);
      block(cx(c), cz(c));
      disc(cx(c), cz(c), 3, (q) => { anyMask[q] = 1; });
      per.set(m, (per.get(m) ?? 0) + 1);
    }
    return got;
  };
  const scan = (score: (c: number) => number): Cand[] => {
    const cs: Cand[] = [];
    for (let c = 0; c < N; c++) {
      if (!land[c] || h.lake[c] || placeAt.has(c)) continue;
      const s = score(c);
      if (s > 0) cs.push({ c, s: s * (0.8 + 0.4 * jit(c, 'j')) });
    }
    return cs;
  };
  const months = (pred: (m: number) => boolean): number[] => { const a: number[] = []; for (let m = 0; m < MONTHS; m++) if (pred(m)) a.push(m); return a; };
  const tM = (c: number, m: number): number => cl.tempM[m * N + c]!;
  const rM = (c: number, m: number): number => cl.rainM[m * N + c]!;
  const south = (c: number): boolean => latC(c) > 0;
  const pick = <T,>(c: number, k: string, a: readonly T[]): T => a[Math.floor(jit(c, k) * a.length)]!;
  const riverOf = new Int32Array(N).fill(-1), riverPos = new Int32Array(N).fill(-1);
  h.rivers.forEach((r, k) => r.cells.forEach((c, q) => { if (q < r.cells.length - 1 && riverOf[c]! < 0) { riverOf[c] = k; riverPos[c] = q; } }));
  const isRiver = (c: number): boolean => land[c] === 1 && !h.lake[c] && h.flow[c]! >= RIVER_FLOW && riverOf[c]! >= 0;
  const dirDeg = (a: number, b: number): number => { const d = Math.atan2(wrapDx(cx(b) - cx(a)), cz(a) - cz(b)) * 180 / Math.PI; return Math.round((d + 360) % 360); };

  // ---- blossom valleys: sheltered temperate valleys ringed by mountains (uncommon; the finest on the planet legendary)
  const blossomScore = (c: number): number => {
    const k = kg(c), e = elev[c]!;
    if (!/^(C[fws][ab]|D[fw][ab])$/.test(k) || e < 60 || e > 1700 || !f.valley[c] || bio[c] === B.rock || bio[c] === B.alpine) return 0;
    const ring = mountRing[c]!;
    if (ring < 350) return 0;
    return Math.min(1, ring / 1200) * (1 + (meanE[c]! - e) / 300) * (cl.rain[c]! > 500 ? 1 : 0.4);
  };
  const blossoms = scan(blossomScore);
  const bloomOf = (c: number): Record<string, WonderParam> => {
    const k = kg(c), warm = cl.tmin[c]! > 7;
    // the month the spring warms through 9 C (cherries, plums), or the autumn's turn under 9 C for the golden stands
    const sp = months((m) => tM(c, m) >= 9 && tM(c, (m + 11) % 12) < 9);
    const au = months((m) => tM(c, m) < 9 && tM(c, (m + 11) % 12) >= 9);
    const spring = sp[0] ?? (south(c) ? 8 : 2), autumn = au[0] ?? (south(c) ? 4 : 10);
    let colour: string, tree: string, bloom: number[];
    if (warm && (k.startsWith('Cw') || k === 'Cfa' || k === 'Csa')) { colour = 'purple'; tree = 'jacaranda'; const m = (south(c) ? 10 : 4); bloom = [m, (m + 1) % 12]; }
    else if (k[0] === 'D' || cl.tmax[c]! < 17) { colour = 'golden'; tree = jit(c, 'gold') < 0.5 ? 'aspen' : 'larch'; bloom = [(autumn + 11) % 12, autumn]; }
    else if (k[1] === 's') { colour = 'white'; tree = 'almond'; const m = (spring + 11) % 12; bloom = [m, spring]; }
    else { colour = jit(c, 'pink') < 0.75 ? 'pink' : 'white'; tree = colour === 'pink' ? 'cherry' : 'plum'; bloom = [spring]; }
    return { colour, tree, bloomMonths: bloom, ringM: Math.round(mountRing[c]!) };
  };
  const bloomPick = choose('blossom valley', blossoms, 70, 4, 60);
  // (the legendary valley is the finest that flowers: pink, white or purple, not a golden autumn stand)
  const star = bloomPick.find((c) => bloomOf(c).colour !== 'golden') ?? bloomPick[0];
  for (const c of bloomPick) add('blossom valley', c === star ? 'legendary' : 'uncommon', c, c === star ? 9 : 2.5 + 3 * jit(c, 'ext'), bloomOf(c));

  // ---- meadows (common: alpine meadows in summer) and superblooms (uncommon: arid basins after a wet year)
  const meadowPick = choose('meadow', scan((c) => (bio[c] === B.alpine && cl.tmax[c]! >= 5 && cl.tmax[c]! <= 15 && cl.slope[c]! < 200 ? 1 : 0)), 30, 30, 400);
  for (const c of meadowPick) add('meadow', 'common', c, 1 + 2 * jit(c, 'ext'), { flowers: pick(c, 'fl', [['gentian', 'edelweiss'], ['lupin', 'paintbrush'], ['poppy', 'saxifrage'], ['aster', 'columbine']]), bloomMonths: months((m) => tM(c, m) >= Math.max(4, cl.tmax[c]! - 3)) });
  const superPick = choose('superbloom', scan((c) => {
    const k = kg(c);
    if ((k[0] !== 'B') || cl.rain[c]! < 90 || cl.rain[c]! > 420 || f.flat[c]! > 20 || !f.valley[c]) return 0;
    return wx.regions[regionOf(cx(c), cz(c))]!.drought + 0.2;
  }), 90, 3, 80);
  for (const c of superPick) {
    const wet = months((m) => rM(c, m) >= cl.rain[c]! / 12);
    add('superbloom', 'uncommon', c, 4 + 6 * jit(c, 'ext'), { flowers: pick(c, 'fl', [['poppy', 'goldfield'], ['desert lily', 'verbena'], ['sand primrose', 'lupin']]), bloomMonths: wet.length ? [wet[wet.length - 1]!, (wet[wet.length - 1]! + 1) % 12] : [south(c) ? 9 : 3], wetYearChance: Math.round((0.12 + 0.2 * jit(c, 'wy')) * 100) / 100 });
  }

  // ---- waterfalls: rivers falling over an escarpment, a range's face or a glacial valley's lip (uncommon; the greatest
  // drop on the planet legendary). The drop is most of the river's fall over its next one or two cells.
  const falls: Cand[] = [];
  const fallOf = new Map<number, { drop: number; next: number }>();
  for (let c = 0; c < N; c++) {
    if (!isRiver(c) || placeAt.has(c)) continue;
    const r = h.rivers[riverOf[c]!]!, q = riverPos[c]!;
    const n1 = r.cells[q + 1]!, n2 = r.cells[q + 2];
    const e = elev[c]!, f1 = e - Math.max(0, elev[n1]!), f2 = n2 !== undefined && land[n2] ? e - Math.max(0, elev[n2]!) : f1;
    const fall = Math.max(f1, Math.min(f2, f1 * 1.8));
    if (f1 < 140 || !land[n1]) continue;
    const drop = Math.round(0.8 * fall);
    fallOf.set(c, { drop, next: n1 });
    falls.push({ c, s: drop * Math.sqrt(h.flow[c]! / RIVER_FLOW) });
  }
  const fallPick = choose('waterfall', falls, 45, 12, 120);
  fallPick.forEach((c, q) => {
    const fo = fallOf.get(c)!, fl = h.flow[c]!;
    const legendary = q === 0 && fo.drop >= 300;
    add('waterfall', legendary ? 'legendary' : 'uncommon', c, 0.5 + fo.drop / 400, {
      river: riverOf[c]!, dropM: fo.drop, flow: Math.round(fl), widthM: Math.round(4 + 6 * Math.sqrt(fl / RIVER_FLOW)), facingDeg: dirDeg(c, fo.next),
      peakMonth: h.peak[c]!, steady: Math.round(h.steady[c]! * 100) / 100, style: fo.drop > 250 ? 'plunge' : cl.slope[c]! > 300 ? 'horsetail' : 'cascade', mist: fo.drop * Math.sqrt(fl / RIVER_FLOW) > 250,
    }, [wrapX(cx(c) + wrapDx(cx(fo.next) - cx(c)) / 2), (cz(c) + cz(fo.next)) / 2]);
  });

  // ---- canyons: rivers cutting high dry country (rare)
  const canyonScore = (c: number): number => {
    if (!isRiver(c)) return 0;
    const k = kg(c), e = elev[c]!;
    const dry = k[0] === 'B' || cl.rain[c]! < 450;
    if (!dry || e < 550 || e > 2600 || t.range[c]! >= 0 && t.rangeCls[t.range[c]!] === RANGE_GREAT) return 0;
    return (e / 1000) * (1 + sand(c)) * Math.log1p(h.flow[c]! / RIVER_FLOW);
  };
  const canyonParams = (c: number): [Record<string, WonderParam>, number] => {
    const r = h.rivers[riverOf[c]!]!, q = riverPos[c]!;
    const a = r.cells[Math.max(0, q - 12)]!, b = r.cells[Math.min(r.cells.length - 1, q + 12)]!;
    const lengthKm = Math.round(distKm(cx(a), cz(a), cx(b), cz(b)) * 1.3);
    const depthM = Math.round(Math.min(1800, Math.max(300, elev[c]! * 0.55)));
    const bands = pick(c, 'bands', [['#B8503A', '#D9844A', '#E8C48A', '#9A3A2C'], ['#C86A3E', '#F0D2A0', '#A04A34', '#E09A5A'], ['#8A6A8A', '#D8B88A', '#B85A3A', '#F2E2C2'], ['#D2A46A', '#9C5A3A', '#EAD8B0', '#7A4A3A']]);
    return [{ river: riverOf[c]!, depthM, lengthKm, widthKm: Math.round((2 + depthM / 300 + 4 * jit(c, 'w')) * 10) / 10, headingDeg: dirDeg(a, b), bands, from: a, to: b }, lengthKm / 2];
  };

  // ---- arches, hoodoos and spires: arid sandstone country (rare)
  const archScore = (c: number): number => {
    const k = kg(c), e = elev[c]!, sd = sand(c);
    if ((k !== 'BWh' && k !== 'BWk' && k !== 'BSk') || e < 600 || e > 2400 || sd < 0.55 || f.flat[c]! < 8) return 0;
    return (sd - 0.5) * Math.min(1, f.flat[c]! / 40);
  };
  // ---- karst towers: humid subtropical limestone over rice-green valleys (rare)
  const karstScore = (c: number): number => {
    const k = kg(c), lm = lime(c);
    if (!['Cfa', 'Cwa', 'Am', 'Aw', 'Af'].includes(k) || cl.rain[c]! < 1100 || elev[c]! > 900 || lm < 0.6) return 0;
    return (lm - 0.55) * (1 + f.flat[c]! / 30);
  };
  // ---- volcanoes: arcs on the converging ranges near the coast, and a few hotspots (rare)
  const volcScore = (c: number): number => {
    const mt = t.mount[c]!, e = elev[c]!;
    if (mt < 700 || t.distSea[c]! > 140 || e < 800) return 0;
    for (let k = 0; k < 8; k++) { const m = nb(c, k); if (m >= 0 && elev[m]! > e) return 0; } // a summit
    return (mt / 2000) * (1.3 - t.distSea[c]! / 200);
  };
  const hot: number[] = [];
  for (let q = 0; q < 5; q++) {
    // (a hotspot: a point on the planet; the nearest land within 60 km gets a shield volcano)
    const x = hashU(hs, 900 + q, 1) * W, z = H * (0.2 + 0.6 * hashU(hs, 900 + q, 2));
    let best = -1, bd = 60;
    for (let dj = -60; dj <= 60; dj++) for (let di = -60; di <= 60; di++) {
      const j = Math.floor(z) + dj, i = ((Math.floor(x) + di) % W + W) % W;
      if (j < 0 || j >= H) continue;
      const c = j * W + i, d = distKm(x, z, i + 0.5, j + 0.5);
      if (land[c] && !h.lake[c] && !placeAt.has(c) && d < bd) { bd = d; best = c; }
    }
    if (best >= 0) hot.push(best);
  }
  const volcParams = (c: number, hotspot: boolean): Record<string, WonderParam> => {
    const active = jit(c, 'act') < 0.35;
    return { coneM: Math.round(hotspot ? 800 + 1400 * jit(c, 'cone') : 900 + 1800 * jit(c, 'cone')), type: hotspot ? 'shield' : 'stratovolcano', craterLake: !active && jit(c, 'cl') < 0.5,
      active, lastEruption: active ? 1850 + Math.floor(170 * jit(c, 'ly')) : -Math.floor(500 + 9000 * jit(c, 'ly')), hotSprings: jit(c, 'hs') < 0.7 };
  };
  // ---- fjords: glaciated high-latitude coasts under high ground (rare)
  const fjordScore = (c: number): number => {
    if (!h.coast[c] || Math.abs(latC(c)) < 46 || cl.tmax[c]! > 17) return 0;
    let hi = 0;
    const i0 = c % W, j0 = (c / W) | 0;
    for (let dj = -8; dj <= 8; dj++) for (let di = -8; di <= 8; di++) { const j = j0 + dj; if (j < 0 || j >= H) continue; hi = Math.max(hi, elev[j * W + (((i0 + di) % W) + W) % W]!); }
    return hi > 800 ? (hi / 2000) * (h.coast[c]! >= 2 ? 1.4 : 1) : 0;
  };
  // ---- giant trees: foggy temperate coastal rainforest (rare)
  const treeScore = (c: number): number => {
    const k = kg(c), fog = wx.regions[regionOf(cx(c), cz(c))]!.fogDays;
    if (!(bio[c] === B['temperate rainforest'] || ((k === 'Cfb' || k === 'Cfc' || k === 'Csb') && cl.rain[c]! > 1300 && bio[c] === B['temperate forest'])) || t.distSea[c]! > 45 || elev[c]! > 900) return 0;
    return (1 + fog / 40) * (cl.rain[c]! / 2000);
  };
  // ---- salt flats and coloured lakes: closed basins in arid climates (rare)
  const saltLakeAt = new Map<number, number>();
  for (const l of h.lakes) if (l.salt) saltLakeAt.set(l.outlet, l.id);
  const saltScore = (c: number): number => {
    const k = kg(c);
    if (k[0] !== 'B' || f.flat[c]! > 6 || !f.valley[c] || cl.rain[c]! > 350) return 0;
    return (1 + (saltLakeAt.has(c) ? 2 : 0)) * (400 - cl.rain[c]!) / 400;
  };
  const rares: Record<string, { score: (c: number) => number; sep: number; per: number; total: number; make: (c: number, tier: WonderTier) => void }> = {
    canyon: { score: canyonScore, sep: 160, per: 2, total: 14, make: (c, tier) => { const [p, e] = canyonParams(c); add('canyon', tier, c, e, p); } },
    arches: { score: archScore, sep: 160, per: 2, total: 12, make: (c, tier) => add('arches', tier, c, 3 + 5 * jit(c, 'ext'), { formations: pick(c, 'fm', [['arch', 'hoodoo'], ['arch', 'spire'], ['hoodoo', 'spire'], ['arch', 'hoodoo', 'spire']]), count: 20 + Math.floor(200 * jit(c, 'n')), rock: pick(c, 'rk', ['red sandstone', 'cream sandstone', 'banded sandstone', 'orange sandstone']) }) },
    karst: { score: karstScore, sep: 160, per: 2, total: 12, make: (c, tier) => add('karst', tier, c, 4 + 8 * jit(c, 'ext'), { towerM: Math.round(80 + 220 * jit(c, 'tw')), towersPerKm2: Math.round((2 + 10 * jit(c, 'td')) * 10) / 10, cenotes: Math.floor(1 + 8 * jit(c, 'ce')), rice: kg(c)[0] === 'A' || kg(c) === 'Cwa' || kg(c) === 'Cfa' }) },
    volcano: { score: volcScore, sep: 150, per: 2, total: 16, make: (c, tier) => add('volcano', tier, c, 4 + 4 * jit(c, 'ext'), volcParams(c, false)) },
    fjord: { score: fjordScore, sep: 140, per: 2, total: 12, make: (c, tier) => { const sea = seaward(t, c); add('fjord', tier, c, 6 + 12 * jit(c, 'ext'), { lengthKm: Math.round(10 + 40 * jit(c, 'len')), wallM: Math.round(400 + 900 * jit(c, 'wall')), headingDeg: sea }); } },
    'giant trees': { score: treeScore, sep: 160, per: 2, total: 10, make: (c, tier) => add('giant trees', tier, c, 1.5 + 3 * jit(c, 'ext'), { heightM: Math.round(85 + 30 * jit(c, 'ht')), girthM: Math.round(6 + 5 * jit(c, 'gt')), fogDays: wx.regions[regionOf(cx(c), cz(c))]!.fogDays }) },
    'salt flat': { score: saltScore, sep: 180, per: 1, total: 10, make: (c, tier) => {
      const wet = months((m) => rM(c, m) >= (cl.rain[c]! / 12) * 1.3);
      add('salt flat', tier, c, 6 + 14 * jit(c, 'ext'), { areaKm2: Math.round(60 + 900 * jit(c, 'a')), mirrorMonths: wet.length ? wet : [south(c) ? 6 : 0], lakeColour: pick(c, 'lc', ['white', 'pink', 'turquoise']), lake: saltLakeAt.get(c) ?? 0 });
    } },
  };
  const rareCands = new Map<string, Cand[]>();
  for (const [kind, r] of Object.entries(rares)) {
    const cs = scan(r.score);
    rareCands.set(kind, cs);
    for (const c of choose(kind as WonderKind, cs.slice(), r.sep, r.per, r.total)) r.make(c, 'rare');
  }
  for (const c of hot) if (!taken.some((q) => distKm(cx(c), cz(c), q.x, q.z) < 20)) add('volcano', 'rare', c, 6, volcParams(c, true));
  // ---- table mountains: old plateaus in the wet tropics with sheer edges and falls off them (legendary, the best one)
  const table = choose('table mountain', scan((c) => {
    const k = kg(c), e = elev[c]!;
    if ((k !== 'Af' && k !== 'Am') || cl.rain[c]! < 1800 || e < 500 || e > 2800) return 0;
    const rise = e - meanE[c]!;
    return rise > 120 ? rise / 300 : 0;
  }), 400, 1, 1);
  for (const c of table) add('table mountain', 'legendary', c, 5 + 5 * jit(c, 'ext'), { cliffM: Math.round(400 + 600 * jit(c, 'cl')), topKm2: Math.round(30 + 300 * jit(c, 'top')), falls: 2 + Math.floor(6 * jit(c, 'f')), fallDropM: Math.round(300 + 600 * jit(c, 'fd')) });

  // ---- every continent gets at least one rare wonder: the best candidate of any rare kind there, with the kinds' own
  // scores (a wonder that lost only to the spacing or the per-continent cap); failing that a gorge on its steepest river
  for (const m of t.masses) {
    if (m.kind !== 'continent' || out.some((q) => q.mass === m.id && (q.tier === 'rare' || q.tier === 'legendary'))) continue;
    let best: { kind: string; c: number; s: number } | null = null;
    for (const kind of RARE_KINDS) for (const cd of rareCands.get(kind) ?? []) {
      if (t.mass[cd.c] !== m.id || taken.some((q) => distKm(cx(cd.c), cz(cd.c), q.x, q.z) < 20)) continue;
      if (!best || cd.s > best.s) best = { kind, c: cd.c, s: cd.s };
    }
    if (best) { rares[best.kind]!.make(best.c, 'rare'); continue; }
    let gc = -1, gs = 0;
    for (let c = 0; c < N; c++) if (t.mass[c] === m.id && isRiver(c) && !placeAt.has(c)) { const s = cl.slope[c]! * Math.log1p(h.flow[c]! / RIVER_FLOW); if (s > gs) { gs = s; gc = c; } }
    if (gc >= 0) { const [p, e] = canyonParams(gc); add('canyon', 'rare', gc, e, { ...p, depthM: Math.round(Math.max(250, Math.min(900, cl.slope[gc]! * 3))), bands: ['#6A6460', '#8A8070', '#5A524C', '#A09484'] }); }
  }

  // ---- hot springs, geysers and terraces: by the volcanoes and on the high ranges (uncommon)
  const volcanoes = out.filter((q) => q.kind === 'volcano');
  const springPick = choose('hot springs', scan((c) => {
    const near = volcanoes.some((v) => distKm(cx(c), cz(c), v.x, v.z) < 45);
    return near ? 2 : t.mount[c]! > 1500 && jit(c, 'hs') < 0.02 ? 1 : 0;
  }), 70, 6, 60);
  for (const c of springPick) add('hot springs', 'uncommon', c, 0.6 + jit(c, 'ext'), { geysers: jit(c, 'gy') < 0.45, terraces: jit(c, 'tr') < 0.4, waterC: Math.round(40 + 55 * jit(c, 'wc')), colour: pick(c, 'col', ['turquoise', 'white', 'rust', 'emerald']) });
  // ---- glaciers, ice caves and turquoise lakes: great ranges and high latitudes (uncommon)
  const snowAbove = (c: number): boolean => elev[c]! > cl.snowline[c]!;
  const glacierPick = choose('glacier', scan((c) => {
    if (!snowAbove(c)) return 0;
    let edge = false;
    for (let k = 0; k < 8 && !edge; k++) { const m = nb(c, k); if (m >= 0 && land[m] && !snowAbove(m)) edge = true; }
    if (!edge) return 0;
    const great = t.range[c]! >= 0 && t.rangeCls[t.range[c]!] === RANGE_GREAT;
    return (great ? 3 : Math.abs(latC(c)) > 50 ? 1.2 : 0.5) * (elev[c]! / 3000);
  }), 50, 10, 90);
  for (const c of glacierPick) add('glacier', 'uncommon', c, 3 + 8 * jit(c, 'ext'), { lengthKm: Math.round(4 + 22 * jit(c, 'len')), headM: Math.round(elev[c]! + 300 + 900 * jit(c, 'hd')), snoutM: Math.round(Math.max(200, cl.snowline[c]! - 300 - 500 * jit(c, 'sn'))), lakeColour: 'turquoise', iceCave: jit(c, 'ic') < 0.4 });
  // ---- sea stacks, arches and caves on rocky and volcanic coasts (uncommon)
  const stackPick = choose('sea stacks', scan((c) => (h.coast[c]! >= 2 ? h.coast[c]! - 1 : 0)), 90, 8, 100);
  for (const c of stackPick) add('sea stacks', 'uncommon', c, 1 + 2 * jit(c, 'ext'), { count: 2 + Math.floor(12 * jit(c, 'n')), arch: jit(c, 'arch') < 0.5, caves: jit(c, 'cv') < 0.5, blackSand: volcanoes.some((v) => distKm(cx(c), cz(c), v.x, v.z) < 80), facingDeg: seaward(t, c) });
  // ---- atolls and lagoons: small islands in warm tropical seas (rare)
  const atollPick = choose('atoll', scan((c) => {
    const m = t.masses[t.mass[c]! - 1];
    if (!m || m.kind !== 'island' || m.cells > 10 || Math.abs(latC(c)) > 25 || cl.tmin[c]! < 21) return 0;
    return 1;
  }), 180, 1, 12);
  for (const c of atollPick) add('atoll', 'rare', c, 2 + 4 * jit(c, 'ext'), { lagoonKm: Math.round((2 + 8 * jit(c, 'lg')) * 10) / 10, islets: 3 + Math.floor(20 * jit(c, 'is')) });
  // ---- night wonders (uncommon, by the hour and season): glowing bays, firefly meadows, the aurora
  const bayPick = choose('glowing bay', scan((c) => (h.coast[c] === 1 && cl.tmax[c]! > 26 && f.seaFrac[c]! > 0.15 && f.seaFrac[c]! < 0.45 ? 1 : 0)), 150, 3, 30);
  for (const c of bayPick) add('glowing bay', 'uncommon', c, 1 + jit(c, 'ext'), { months: months((m) => tM(c, m) > 25), colour: 'blue' });
  const flyPick = choose('fireflies', scan((c) => (/^(Cfa|Cwa|Dfa|Dwa)$/.test(kg(c)) && cl.tmax[c]! > 22 && cl.rain[c]! > 800 && (bio[c] === B['temperate forest'] || bio[c] === B.prairie) ? 1 : 0)), 120, 4, 40);
  for (const c of flyPick) add('fireflies', 'uncommon', c, 0.8 + jit(c, 'ext'), { months: months((m) => tM(c, m) > Math.max(19, cl.tmax[c]! - 3) && rM(c, m) > 40) });
  const auroraPick = choose('aurora', scan((c) => (Math.abs(latC(c)) > 54 && cl.rain[c]! < 700 && bio[c] !== B.ice ? (Math.abs(latC(c)) - 50) / 20 : 0)), 160, 3, 30);
  for (const c of auroraPick) add('aurora', 'uncommon', c, 20, { months: months((m) => (south(c) ? m >= 4 && m <= 8 : m >= 10 || m <= 2)), clearNights: Math.round((0.6 - cl.rain[c]! / 2000) * 100) / 100 });
  // ---- caves: limestone (with underground rivers) and lava tubes by the volcanoes (uncommon)
  const cavePick = choose('cave', scan((c) => {
    if (lime(c) > 0.58 && cl.rain[c]! > 650) return lime(c);
    return volcanoes.some((v) => distKm(cx(c), cz(c), v.x, v.z) < 35) ? 0.5 : 0;
  }), 90, 6, 70);
  for (const c of cavePick) { const ls = lime(c) > 0.58; add('cave', 'uncommon', c, 1 + 3 * jit(c, 'ext'), { lengthKm: Math.round((1 + (ls ? 30 : 6) * jit(c, 'len')) * 10) / 10, undergroundRiver: ls && jit(c, 'ur') < 0.6, rock: ls ? 'limestone' : 'lava tube' }); }

  // the legendary ones: 1-3 on the planet (the finest blossom valley, the greatest drop, a table mountain); if a seed had
  // none, its greatest waterfall becomes one
  if (!out.some((q) => q.tier === 'legendary')) { const wf = out.find((q) => q.kind === 'waterfall'); if (wf) { wf.tier = 'legendary'; wf.fame = 'planet'; } }
  void CELL_KM; void koppenGroup;
  return out;
}

/** The way out to sea from a coast cell (degrees from north, clockwise): the mean direction to its sea neighbours. */
function seaward(t: Terrain, c: number): number {
  let sx = 0, sz = 0;
  const i0 = c % W, j0 = (c / W) | 0;
  for (let dj = -3; dj <= 3; dj++) for (let di = -3; di <= 3; di++) {
    const j = j0 + dj;
    if (j < 0 || j >= H || (!di && !dj)) continue;
    if (!t.land[j * W + (((i0 + di) % W) + W) % W]) { const l = Math.hypot(di, dj); sx += di / l; sz += dj / l; }
  }
  const d = Math.atan2(sx, -sz) * 180 / Math.PI;
  return Math.round((d + 360) % 360);
}
