import { CELL_KM, H, N, W, blur, latOf } from './grid';
import { Fbm } from './noise';
import type { Terrain } from './terrain';

// THE CLIMATE (WORLD_GEN.md section 2.3, the owner's request of 2026-11-23: a realistic climate system that drives the
// world, not latitude bands). Twelve months per atlas cell of temperature and rain, from:
// - the sun's seasonal swing (axial tilt 23.4 deg: seasons flip between the hemispheres), the lapse rate (6.5 C a km),
//   continentality (the yearly swing widens with the distance from the ocean, coasts are mild);
// - ocean currents, as the real gyres on this wrapping planet: warm currents run poleward off the EAST coasts of the
//   continents in the subtropics (the western sides of the ocean basins), cold currents run equatorward off the WEST
//   coasts (cool, dry coasts and fog deserts); past 45 deg it turns round (a warm drift off the west coasts, a cold
//   current off the east coasts);
// - prevailing winds by latitude band (trade winds blowing west, westerlies blowing east, polar easterlies), the bands
//   following the sun through the year; the wind carries moisture picked up over the sea (more over warm water) inland,
//   rains it out on windward slopes and leaves rain shadows behind the ranges, losing it with distance inland; a wet
//   equatorial trough and dry subtropical highs that move with the seasons (so the summer-dry Mediterranean coasts and
//   the wet-and-dry tropics come out of it); monsoon rains where a continent meets warm water in the tropics' summer;
// - then a Koppen-style class per cell (tropical, arid, temperate, continental, polar, highland), the biomes and the
//   crop belts from it.
// Month m is 0 = January. In this grid's convention north is negative latitude (latOf), as z grows to the south.

export const MONTHS = 12;
/** Axial tilt (deg). */
export const TILT = 23.4;
/** Lapse rate (C per m). */
export const LAPSE = 0.0065;

/** The sun's declination in month [m] (deg, north positive): about -21 in January, +21 in July. */
export const declination = (m: number): number => TILT * Math.sin((2 * Math.PI * (m + 0.5 - 3.2)) / 12);
/** The latitude (this grid's convention: north negative) the wind bands are centred on in month [m]: the thermal equator
 * follows the sun by about a third of its declination. */
export const bandShift = (m: number): number => -0.35 * declination(m);
/** Wind of a latitude band (grid convention) in month [m]: +1 blows to the east (westerlies), -1 to the west (trades, polar). */
export const windAt = (lat: number, m = 3): 1 | -1 => { const a = Math.abs(lat - bandShift(m)); return a > 30 && a < 60 ? 1 : -1; };

export const KOPPEN = [
  'Af', 'Am', 'Aw', 'BWh', 'BWk', 'BSh', 'BSk', 'Csa', 'Csb', 'Csc', 'Cwa', 'Cwb', 'Cwc', 'Cfa', 'Cfb', 'Cfc',
  'Dsa', 'Dsb', 'Dsc', 'Dsd', 'Dwa', 'Dwb', 'Dwc', 'Dwd', 'Dfa', 'Dfb', 'Dfc', 'Dfd', 'ET', 'EF', 'H', 'ocean',
] as const;
export type Koppen = (typeof KOPPEN)[number];
export const KG = Object.fromEntries(KOPPEN.map((k, i) => [k, i])) as Record<Koppen, number>;
/** The main group of a class: A tropical, B arid, C temperate, D continental, E polar, H highland. */
export const koppenGroup = (k: number): 'A' | 'B' | 'C' | 'D' | 'E' | 'H' | 'ocean' => {
  const s = KOPPEN[k]!;
  return s === 'ocean' ? 'ocean' : (s[0] as 'A' | 'B' | 'C' | 'D' | 'E' | 'H');
};

export const BIOMES = [
  'ocean', 'ice', 'tundra', 'taiga', 'temperate forest', 'temperate rainforest', 'prairie', 'steppe', 'desert', 'savanna',
  'tropical forest', 'rainforest', 'alpine', 'marsh', 'lake', 'beach', 'rock', 'chaparral', 'glacier',
] as const;
export type Biome = (typeof BIOMES)[number];
export const B = Object.fromEntries(BIOMES.map((b, i) => [b, i])) as Record<Biome, number>;

export const CROPS = ['none', 'wheat', 'corn', 'cotton', 'orchards', 'rice', 'dairy', 'ranching'] as const;
export type Crop = (typeof CROPS)[number];
export const CR = Object.fromEntries(CROPS.map((b, i) => [b, i])) as Record<Crop, number>;

export interface Climate {
  /** Monthly mean temperature (C), month-major: tempM[m * N + c]. */
  tempM: Float32Array;
  /** Monthly rain (mm), month-major. */
  rainM: Float32Array;
  /** Annual mean, coldest and warmest month (C); annual rain (mm). */
  temp: Float32Array;
  tmin: Float32Array;
  tmax: Float32Array;
  rain: Float32Array;
  /** The ocean currents' sea-surface anomaly (C): + warm current, - cold; on land, the coast's anomaly fading inland. */
  current: Float32Array;
  /** Continentality 0 (coast) to 1 (deep interior). */
  cont: Float32Array;
  /** Slope (m per km). */
  slope: Float32Array;
  /** Koppen class (index into KOPPEN). */
  koppen: Uint8Array;
  /** The snow line (m): where the warmest month is at freezing. */
  snowline: Float32Array;
}

export function makeClimate(t: Terrain): Climate {
  const { land, elev } = t;
  const slope = new Float32Array(N);
  for (let j = 0; j < H; j++) for (let i = 0; i < W; i++) {
    const c = j * W + i;
    if (!land[c]) continue;
    const ex = Math.max(0, elev[j * W + (i + 1) % W]!) - Math.max(0, elev[j * W + (i + W - 1) % W]!);
    const ez = Math.max(0, elev[Math.min(H - 1, j + 1) * W + i]!) - Math.max(0, elev[Math.max(0, j - 1) * W + i]!);
    slope[c] = Math.hypot(ex, ez) / (2 * CELL_KM);
  }
  const current = currents(t);
  const cont = new Float32Array(N);
  for (let c = 0; c < N; c++) cont[c] = land[c] ? 1 - Math.exp(-t.distSea[c]! / 140) : 0;

  // ---- temperature by month
  const tempM = new Float32Array(MONTHS * N);
  const temp = new Float32Array(N), tmin = new Float32Array(N).fill(99), tmax = new Float32Array(N).fill(-99);
  for (let j = 0; j < H; j++) {
    const lat = latOf(j + 0.5), a = Math.abs(lat);
    const north = lat < 0;
    const base = 29 - 52 * Math.pow(a / 90, 1.7);
    const swing = 1.5 + 21 * Math.pow(Math.sin((a * Math.PI) / 180), 1.25); // half the yearly range on a continent
    for (let i = 0; i < W; i++) {
      const c = j * W + i;
      const isLand = land[c] === 1;
      const amp = isLand ? swing * (0.3 + 0.7 * cont[c]!) : swing * 0.22;
      const mean = base - LAPSE * Math.max(0, elev[c]!) + current[c]! + (isLand ? -1.5 * cont[c]! : 0.5);
      let s = 0;
      for (let m = 0; m < MONTHS; m++) {
        // (the coldest month a month after the solstice; a month later still over the sea)
        const lag = isLand ? 0.6 : 1.3;
        const phase = Math.cos((2 * Math.PI * (m + 0.5 - lag - 0.5)) / 12); // 1 in January (+lag)
        const v = mean + (north ? -1 : 1) * amp * phase;
        tempM[m * N + c] = v;
        s += v;
        if (v < tmin[c]!) tmin[c] = v;
        if (v > tmax[c]!) tmax[c] = v;
      }
      temp[c] = s / MONTHS;
    }
  }
  const rainM = rainfall(t, tempM, current, cont);
  const rain = new Float32Array(N);
  for (let m = 0; m < MONTHS; m++) for (let c = 0; c < N; c++) rain[c] += rainM[m * N + c]!;
  const koppen = new Uint8Array(N);
  const snowline = new Float32Array(N);
  for (let c = 0; c < N; c++) {
    snowline[c] = Math.max(0, elev[c]!) + tmax[c]! / LAPSE;
    koppen[c] = land[c] ? classify(c, tempM, rainM, tmin[c]!, tmax[c]!, temp[c]!, rain[c]!, latOf(((c / W) | 0) + 0.5), elev[c]!) : KG.ocean;
  }
  return { tempM, rainM, temp, tmin, tmax, rain, current, cont, slope, koppen, snowline };
}

/** The ocean currents' anomaly (C) on the sea, spread a little onto the land beside it. */
function currents(t: Terrain): Float32Array {
  const { land } = t;
  // per sea cell: km to land westward and eastward along the row (x wraps)
  const toW = new Float32Array(N).fill(1e9), toE = new Float32Array(N).fill(1e9);
  for (let j = 0; j < H; j++) {
    let d = 1e9;
    for (let lap = 0; lap < 2; lap++) for (let i = 0; i < W; i++) { const c = j * W + i; d = land[c] ? 0 : d + CELL_KM; if (lap) toW[c] = d; }
    d = 1e9;
    for (let lap = 0; lap < 2; lap++) for (let i = W - 1; i >= 0; i--) { const c = j * W + i; d = land[c] ? 0 : d + CELL_KM; if (lap) toE[c] = d; }
  }
  const raw = new Float32Array(N), wt = new Float32Array(N);
  for (let j = 0; j < H; j++) {
    const a = Math.abs(latOf(j + 0.5));
    // off an east coast (land to the west): warm in the subtropics, cold past 45; off a west coast the reverse
    const sub = Math.exp(-(((a - 28) / 13) * ((a - 28) / 13)));
    const high = Math.exp(-(((a - 55) / 9) * ((a - 55) / 9)));
    for (let i = 0; i < W; i++) {
      const c = j * W + i;
      if (land[c]) continue;
      const east = Math.exp(-toW[c]! / 260), west = Math.exp(-toE[c]! / 260);
      raw[c] = east * (5 * sub - 4 * high) + west * (-5.5 * sub + 4.5 * high);
      wt[c] = 1;
    }
  }
  const sb = blur(raw, 25, 25), wb = blur(wt, 25, 25);
  const out = new Float32Array(N);
  for (let c = 0; c < N; c++) {
    const v = wb[c]! > 1e-4 ? sb[c]! / wb[c]! : 0;
    out[c] = land[c] ? v * Math.exp(-t.distSea[c]! / 120) : v;
  }
  return out;
}

/** Rain by month: moisture carried along each month's wind, rained out on rising ground and with distance inland, then the
 * season's bands (the trough, the subtropical highs, the storm tracks) and the monsoons. */
function rainfall(t: Terrain, tempM: Float32Array, current: Float32Array, cont: Float32Array): Float32Array {
  const { land, elev } = t;
  const rainM = new Float32Array(MONTHS * N);
  const vary = new Fbm(t.seed, 'rain-vary', 10, 4);
  const rec = new Float32Array(N);
  // where a continent (not an island) meets warm water in the tropics: the monsoon's reach
  const warm = new Float32Array(N);
  for (let c = 0; c < N; c++) {
    const j = (c / W) | 0, a = Math.abs(latOf(j + 0.5));
    if (!land[c] && a < 30) { let mx = -99; for (let m = 0; m < MONTHS; m++) mx = Math.max(mx, tempM[m * N + c]!); warm[c] = mx > 27 ? 1 : 0; }
  }
  const warmNear = blur(warm, 120, 120, 2);
  const bigLand = new Uint8Array(N);
  for (let c = 0; c < N; c++) { const m = t.mass[c]!; bigLand[c] = m && t.masses[m - 1]!.kind === 'continent' ? 1 : 0; }
  for (let m = 0; m < MONTHS; m++) {
    const shift = bandShift(m);
    rec.fill(0);
    for (let j = 0; j < H; j++) {
      const lat = latOf(j + 0.5);
      // (the share of the row's wind blowing east: the band edges at 30 and 60 deg blend over 12 deg, so no hard lines)
      const a = Math.abs(lat - shift);
      const sst = (v: number): number => { const x = Math.max(0, Math.min(1, v)); return x * x * (3 - 2 * x); };
      const wE = sst((a - 24) / 12) * (1 - sst((a - 54) / 12));
      for (const dir of [1, -1] as const) {
        const wgt = dir > 0 ? wE : 1 - wE;
        if (wgt < 0.005) continue;
        let q = 0;
        for (let lap = 0; lap < 2; lap++) for (let s = 0; s < W; s++) {
          const i = dir > 0 ? s : W - 1 - s;
          const ip = (i - dir + W) % W;
          const c = j * W + i;
          if (!land[c]) {
            const sstv = tempM[m * N + c]!;
            const cap = Math.min(1.3, Math.max(0.05, (sstv + 4) / 30));
            q += (cap - q) * 0.06;
            continue;
          }
          const e = elev[c]!, ep = land[j * W + ip] ? elev[j * W + ip]! : 0;
          const up = Math.min(1, Math.max(0, (e - ep) / 220));
          // (rising ground wrings it out; a little falls everywhere, more in the warm months: convection)
          const T = tempM[m * N + c]!;
          const rn = q * (0.003 + 0.0025 * Math.min(1, Math.max(0, T / 25)) + 0.35 * up);
          q -= rn;
          if (lap) rec[c] += wgt * rn;
        }
      }
    }
    const sm = blur(rec, 8, 20);
    for (let j = 0; j < H; j++) {
      const lat = latOf(j + 0.5), le = lat - shift, a = Math.abs(le);
      // the trough (wet), the subtropical highs (dry, sinking air), the storm tracks (wet)
      const band = 0.45 + 1.1 * Math.exp(-((le / 9) * (le / 9))) - 0.42 * Math.exp(-(((a - 28) / 8) * ((a - 28) / 8))) + 0.3 * Math.exp(-(((a - 52) / 10) * ((a - 52) / 10)));
      const summer = Math.max(0, (lat < 0 ? 1 : -1) * declination(m) / TILT); // 1 at midsummer in this hemisphere
      const winter = Math.max(0, (lat < 0 ? -1 : 1) * declination(m) / TILT);
      const al = Math.abs(lat);
      // (the monsoon's reach fades in and out smoothly: no hard latitude line)
      const tropic = Math.max(0, Math.min(1, (al - 4) / 6)) * Math.max(0, Math.min(1, (36 - al) / 10));
      for (let i = 0; i < W; i++) {
        const c = j * W + i;
        if (!land[c]) continue;
        let v = sm[c]! * band * (0.75 + 0.5 * vary.at(i + 0.5, j + 0.5)) * 1000;
        // cold currents dry the coast; warm ones wet it
        v *= Math.min(1.6, Math.max(0.35, 1 + 0.09 * current[c]!));
        if (tropic > 0 && bigLand[c]) {
          const mon = tropic * Math.min(1, warmNear[c]! * 4) * Math.exp(-t.distSea[c]! / 350);
          v = v * (1 - 0.5 * winter * mon + 2.2 * summer * summer * mon);
        }
        void cont;
        rainM[m * N + c] = v;
      }
    }
  }
  // scale to the planet's mean land rain (about 800 mm a year)
  const ann: number[] = [];
  for (let c = 0; c < N; c += 7) if (land[c]) { let s = 0; for (let m = 0; m < MONTHS; m++) s += rainM[m * N + c]!; ann.push(s); }
  ann.sort((a, b) => a - b);
  const k = 760 / (ann[ann.length >> 1] || 1);
  // (soft cap: the windward ranges' wettest months level off near 600 mm)
  for (let q = 0; q < rainM.length; q++) rainM[q] = 650 * (1 - Math.exp(-(rainM[q]! * k) / 650));
  return rainM;
}

/** Koppen-Geiger, simplified (summer = the 6 warmest months of the hemisphere). */
function classify(c: number, tempM: Float32Array, rainM: Float32Array, tmin: number, tmax: number, tann: number, pann: number, lat: number, elev: number): number {
  const north = lat < 0;
  const isSummer = (m: number): boolean => (north ? m >= 3 && m <= 8 : m <= 2 || m >= 9);
  let pS = 0, pdryS = Infinity, pwetS = 0, pdryW = Infinity, pwetW = 0, pdry = Infinity, warm10 = 0;
  for (let m = 0; m < MONTHS; m++) {
    const p = rainM[m * N + c]!, T = tempM[m * N + c]!;
    if (T >= 10) warm10++;
    pdry = Math.min(pdry, p);
    if (isSummer(m)) { pS += p; pdryS = Math.min(pdryS, p); pwetS = Math.max(pwetS, p); } else { pdryW = Math.min(pdryW, p); pwetW = Math.max(pwetW, p); }
  }
  if (tmax < 10) return tmax < 0 ? KG.EF : KG.ET;
  if (elev > 2300) return KG.H;
  const fs = pS / Math.max(1, pann);
  const pth = 20 * tann + (fs >= 0.7 ? 280 : fs >= 0.3 ? 140 : 0);
  if (pann < pth) {
    const hot = tann >= 18;
    return pann < pth / 2 ? (hot ? KG.BWh : KG.BWk) : hot ? KG.BSh : KG.BSk;
  }
  if (tmin >= 18) {
    if (pdry >= 60) return KG.Af;
    return pdry >= 100 - pann / 25 ? KG.Am : KG.Aw;
  }
  const dry = pdryS < 40 && pdryS < pwetW / 3 ? 's' : pdryW < pwetS / 10 ? 'w' : 'f';
  const third = tmax >= 22 ? 'a' : warm10 >= 4 ? 'b' : tmin < -38 ? 'd' : 'c';
  const g = tmin > -3 ? 'C' : 'D';
  const code = `${g}${dry}${g === 'C' && third === 'd' ? 'c' : third}` as Koppen;
  return KG[code] ?? (g === 'C' ? KG.Cfb : KG.Dfb);
}

/** One biome per cell from the climate class, then height, slope and water (WORLD_GEN.md 2.4). */
export function makeBiomes(t: Terrain, cl: Climate, lake: Uint8Array | Int32Array, flowNear?: Float32Array): Uint8Array {
  const b = new Uint8Array(N);
  for (let c = 0; c < N; c++) {
    const lat = Math.abs(latOf(((c / W) | 0) + 0.5));
    if (!t.land[c]) { b[c] = lat >= 72 ? B.ice : B.ocean; continue; }
    if (lake[c]) { b[c] = B.lake; continue; }
    const k = KOPPEN[cl.koppen[c]!]!, R = cl.rain[c]!, e = t.elev[c]!, s = cl.slope[c]!;
    let v: number;
    if (k === 'EF') v = e > 600 && lat < 65 ? B.glacier : B.ice;
    else if (cl.tmax[c]! < 2 && e > 1200) v = B.glacier;
    else if (s > 260) v = B.rock;
    else if (k === 'ET') v = e > 1200 ? B.alpine : B.tundra;
    else if (k === 'H' || (e > 1800 && cl.tmax[c]! < 12)) v = B.alpine;
    else if (e < 80 && s < 4 && R > 900 && (flowNear ? flowNear[c]! > 0.05 : true)) v = B.marsh;
    else if (t.distSea[c]! < 1.2 && s < 25 && e < 12) v = B.beach;
    else if (k[0] === 'B') v = k[1] === 'W' ? B.desert : k === 'BSh' && R > 450 ? B.savanna : B.steppe;
    else if (k === 'Af') v = B.rainforest;
    else if (k === 'Am') v = B['tropical forest'];
    else if (k === 'Aw') v = R > 1400 ? B['tropical forest'] : B.savanna;
    else if (k[1] === 's') v = k[0] === 'C' && R < 900 ? B.chaparral : B['temperate forest'];
    else if (k[0] === 'C') v = R > 1900 && (k === 'Cfb' || k === 'Cfc') ? B['temperate rainforest'] : R < 850 ? B.prairie : B['temperate forest'];
    else v = k.endsWith('c') || k.endsWith('d') ? B.taiga : R < 700 ? B.prairie : B['temperate forest'];
    b[c] = v;
  }
  return b;
}

/** The crop belt of each farmable cell (WORLD_GEN.md 9, farmland is a land use near towns and roads): wheat on the cool
 * dry plains, corn in the warm humid ones, cotton where the summers are long and hot, orchards on the summer-dry and mild
 * coasts, rice on wet tropical lowlands, dairy in the cool wet country, ranching on the steppe and savanna. */
export function makeCrops(t: Terrain, cl: Climate, bio: Uint8Array): Uint8Array {
  const out = new Uint8Array(N);
  for (let c = 0; c < N; c++) {
    if (!t.land[c]) continue;
    const b = bio[c]!;
    if (b === B.ice || b === B.glacier || b === B.tundra || b === B.alpine || b === B.rock || b === B.lake || b === B.marsh || b === B.desert || b === B.beach || b === B.taiga) continue;
    if (cl.slope[c]! > 45 || t.elev[c]! > 1500) continue;
    const k = KOPPEN[cl.koppen[c]!]!, R = cl.rain[c]!, tx = cl.tmax[c]!;
    let frostFree = 0;
    for (let m = 0; m < MONTHS; m++) if (cl.tempM[m * N + c]! > 4) frostFree++;
    let v: Crop;
    if (k[0] === 'B') v = k[1] === 'S' && R > 380 && tx < 26 ? 'wheat' : 'ranching';
    else if (k === 'Af' || k === 'Am' || (k === 'Aw' && R > 1200) || k === 'Cwa') v = t.elev[c]! < 300 && cl.slope[c]! < 10 ? 'rice' : 'orchards';
    else if (k === 'Aw') v = 'ranching';
    else if (k[1] === 's') v = 'orchards';
    else if (tx >= 25 && frostFree >= 8 && R > 500 && R < 1500) v = 'cotton';
    else if (tx >= 21 && R > 550) v = 'corn';
    else if (R < 650) v = 'wheat';
    else if (tx < 20 && R > 800) v = 'dairy';
    else v = 'wheat';
    out[c] = CR[v];
  }
  return out;
}
