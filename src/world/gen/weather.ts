import { KOPPEN, MONTHS, bandShift, declination, type Climate } from './climate';
import { CELL_KM, H, N, W, latOf, wrapX } from './grid';
import type { Hydro } from './hydro';
import type { Terrain } from './terrain';

// WEATHER STATISTICS PER REGION (the owner's request of 2026-11-23): parameters, not a simulation, for the runtime
// weather system to draw from later (WORLD_GEN.md "Runtime weather and seasons"): how often storms come, the hurricane
// and tornado belts, snow days, fog, heat waves and droughts, per region of 16 x 16 atlas cells (17.2 km, the
// streaming region of section 1). Plus the climate hooks a town's identity and NPC talk can use.

export const REGION = 16;
export const RW = W / REGION, RH = H / REGION;

export interface RegionWeather {
  /** Days a year with a thunderstorm or a gale. */
  stormDays: number;
  /** Chance a year of a hurricane coming ashore within the region (0-1). */
  hurricane: number;
  /** Tornadoes a year per 10,000 km2 (the belts where warm moist air meets dry air over flat interiors). */
  tornado: number;
  snowDays: number;
  fogDays: number;
  /** Days a year of a heat wave (well above the season's normal). */
  heatDays: number;
  /** Chance a year of a drought (0-1). */
  drought: number;
  /** The wettest and driest months (0 January), for the season's look. */
  wetMonth: number;
  dryMonth: number;
  /** Snow lies on the ground (months). */
  snowMonths: number;
}

export interface Weather { regions: RegionWeather[]; /** per land cell: hurricane exposure 0-1 and lake-effect snow 0-1 */ hurricaneCell: Float32Array; lakeSnow: Float32Array }

export function makeWeather(t: Terrain, cl: Climate, h: Hydro): Weather {
  const { land } = t;
  // ---- hurricanes: born over warm tropical water (late summer, 8-30 deg), carried west by the trades onto the EAST
  // coasts of the continents, fading over land
  const hurricaneCell = new Float32Array(N);
  for (let j = 0; j < H; j++) {
    const lat = latOf(j + 0.5), a = Math.abs(lat);
    const belt = a > 8 && a < 36 ? Math.exp(-(((a - 20) / 9) * ((a - 20) / 9))) : 0;
    if (!belt) continue;
    // sweep east to west (the storms' way), the storm energy built over warm sea and lost over land
    let e = 0;
    for (let lap = 0; lap < 2; lap++) for (let s = 0; s < W; s++) {
      const i = W - 1 - s, c = j * W + i;
      let sstMax = -99;
      for (let m = 0; m < MONTHS; m++) sstMax = Math.max(sstMax, cl.tempM[m * N + c]!);
      if (!land[c]) e += ((sstMax > 26.5 ? 1 : 0) - e) * 0.004;
      else e *= Math.exp(-CELL_KM / 90);
      if (lap) hurricaneCell[c] = land[c] ? Math.min(1, e * belt * 1.6) : 0;
    }
  }
  // ---- lake-effect snow: cold winter wind crossing a big lake (or a strait) onto land
  const lakeSnow = new Float32Array(N);
  for (let j = 0; j < H; j++) {
    const lat = latOf(j + 0.5);
    const dir = windWinter(lat);
    let fetch = 0;
    for (let lap = 0; lap < 2; lap++) for (let s = 0; s < W; s++) {
      const i = dir > 0 ? s : W - 1 - s, c = j * W + i;
      const water = !land[c] || h.lake[c]! > 0;
      if (water) fetch = Math.min(1, fetch + CELL_KM / 60); else fetch *= Math.exp(-CELL_KM / 40);
      if (lap && land[c] && !h.lake[c] && cl.tmin[c]! < -2) lakeSnow[c] = fetch * Math.min(1, (-cl.tmin[c]! - 2) / 8);
    }
  }

  const regions: RegionWeather[] = [];
  for (let rj = 0; rj < RH; rj++) for (let ri = 0; ri < RW; ri++) {
    let n = 0, landN = 0;
    const acc = { storm: 0, hur: 0, tor: 0, snow: 0, fog: 0, heat: 0, drought: 0, snowM: 0 };
    const wet = new Float64Array(MONTHS);
    for (let j = rj * REGION; j < (rj + 1) * REGION; j++) for (let i = ri * REGION; i < (ri + 1) * REGION; i++) {
      const c = j * W + i;
      n++;
      const isLand = land[c] === 1;
      if (isLand) landN++;
      const lat = latOf(j + 0.5), a = Math.abs(lat);
      let warmWet = 0, snowDays = 0, snowM = 0, heat = 0;
      for (let m = 0; m < MONTHS; m++) {
        const T = cl.tempM[m * N + c]!, P = isLand ? cl.rainM[m * N + c]! : 60;
        wet[m] += P;
        // thunderstorms: warm and wet months
        warmWet += Math.max(0, Math.min(1, (T - 12) / 14)) * Math.min(1, P / 120) * 6;
        if (T < 0.5) { snowDays += Math.min(25, P / 4) * Math.min(1, (0.5 - T) / 5 + 0.25); if (T < -2) snowM++; }
        if (T > 22) heat += (T - 22) * 0.6 * (0.4 + cl.cont[c]!);
      }
      const k = KOPPEN[cl.koppen[c]!]!;
      // gales in the storm tracks (45-62 deg) and on coasts
      const gale = 18 * Math.exp(-(((a - 53) / 9) * ((a - 53) / 9))) * (isLand ? 0.6 + 0.4 * (1 - cl.cont[c]!) : 1);
      acc.storm += warmWet + gale;
      acc.hur += isLand ? hurricaneCell[c]! : 0;
      // tornadoes: flat continental interiors of 28-50 deg with warm humid summers and dry air from the west
      if (isLand) {
        const band = Math.exp(-(((a - 38) / 8) * ((a - 38) / 8)));
        const flat = Math.exp(-cl.slope[c]! / 15);
        const humid = Math.max(0, Math.min(1, (cl.tmax[c]! - 20) / 8)) * Math.max(0, Math.min(1, (cl.rain[c]! - 400) / 500)) * Math.max(0, Math.min(1, (1500 - cl.rain[c]!) / 600));
        acc.tor += band * flat * humid * (0.2 + cl.cont[c]!) * 36;
        acc.snow += snowDays + lakeSnow[c]! * 35;
        acc.snowM += snowM;
        // fog: cold-current coasts (the fog deserts), cool marine coasts, damp valleys
        const coastal = 1 - cl.cont[c]!;
        acc.fog += coastal * Math.max(0, -cl.current[c]!) * 14 + coastal * (a > 38 ? 12 : 3) + (h.flow[c]! > 2000 ? 10 : 0) + (k === 'Cfb' || k === 'Cfc' ? 8 : 0);
        acc.heat += heat;
        // drought: dry and erratic places, and monsoon country (when the rains fail)
        const dryness = Math.max(0, Math.min(1, (900 - cl.rain[c]!) / 700));
        let mx = 0, mn = 1e9;
        for (let m = 0; m < MONTHS; m++) { mx = Math.max(mx, cl.rainM[m * N + c]!); mn = Math.min(mn, cl.rainM[m * N + c]!); }
        const seasonal = mx > 0 ? 1 - mn / mx : 0;
        acc.drought += Math.min(0.6, 0.05 + 0.35 * dryness + 0.15 * seasonal * (a < 35 ? 1 : 0.4));
      }
    }
    const L = Math.max(1, landN);
    let wm = 0, dm = 0;
    for (let m = 1; m < MONTHS; m++) { if (wet[m]! > wet[wm]!) wm = m; if (wet[m]! < wet[dm]!) dm = m; }
    regions.push({
      stormDays: Math.round(acc.storm / n), hurricane: r2(Math.min(0.6, (acc.hur / L) * 0.5)), tornado: r2(acc.tor / L),
      snowDays: Math.round(acc.snow / L), fogDays: Math.round(acc.fog / L), heatDays: Math.round(acc.heat / L), drought: r2(acc.drought / L),
      wetMonth: wm, dryMonth: dm, snowMonths: Math.round(acc.snowM / L),
    });
  }
  return { regions, hurricaneCell, lakeSnow };
}

const r2 = (v: number): number => Math.round(v * 100) / 100;
/** The prevailing wind in January and July averaged: the winter one for the hemisphere. */
function windWinter(lat: number): 1 | -1 {
  const m = lat < 0 ? 0 : 6; // northern winter is January
  const a = Math.abs(lat - bandShift(m));
  void declination;
  return a > 30 && a < 60 ? 1 : -1;
}

export const regionOf = (x: number, z: number): number => Math.floor(Math.min(H - 1, Math.max(0, z)) / REGION) * RW + Math.floor(wrapX(x) / REGION);

/** Climate hooks for a place's identity and its NPCs' talk ("hot dry summers", "lake-effect snow", "hurricane coast"). */
export function climateTags(cl: Climate, wx: Weather, c: number, x: number, z: number): string[] {
  const k = KOPPEN[cl.koppen[c]!]!;
  const r = wx.regions[regionOf(x, z)]!;
  const tags: string[] = [];
  const tx = cl.tmax[c]!, tn = cl.tmin[c]!;
  if (k[0] === 'B' && tx > 26) tags.push('dry heat');
  if (k[1] === 's') tags.push(tx > 22 ? 'hot dry summers' : 'dry summers', 'mild wet winters');
  if ((k[0] === 'C' || k[0] === 'D') && k[1] !== 's' && tx > 23 && cl.rain[c]! > 800) tags.push('humid summers');
  if (tn < -10) tags.push('long cold winters');
  else if (tn < -2 && r.snowDays > 25) tags.push('snowy winters');
  if (wx.lakeSnow[c]! > 0.35 && tn < -2) tags.push('lake-effect snow');
  if (r.hurricane > 0.12) tags.push('hurricane coast');
  if (r.tornado > 1.2) tags.push('tornado country');
  if (r.fogDays > 40) tags.push('foggy');
  if (k === 'Am' || (k[0] === 'A' && r.drought > 0.3) || k === 'Cwa' || k === 'Cwb') tags.push('monsoon rains');
  if (r.drought > 0.35) tags.push('droughts');
  if (r.heatDays > 25) tags.push('heat waves');
  if (k === 'Af') tags.push('rain all year');
  if (k === 'Cfb' || k === 'Cfc') tags.push('mild and damp');
  if (k === 'ET' || k === 'H') tags.push('high and cold');
  return tags;
}
