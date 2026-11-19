// A ROUGH PROTOTYPE of the planned town (kotlin-street/docs/TOWN_PLAN.md), as data for the map pictures: not the
// generator. It lays a town out the way TOWN_PLAN.md describes (a block grid platted round a civic square or plaza,
// Main Street running from it, the street-wall core, the old neighbourhoods, the rail side, the highway strip, the
// subdivisions that come later, farmland round it) and says what stands in a given year. It reads the real town's
// facts (its type, people by year, which hand-made models it earns and their years, its stores, civic landmarks, river)
// from src/ but changes nothing there. Phase 2 rebuilds this properly inside src/town (plan.ts and friends).
//
// Usage: npx tsx kotlin-street/tools/plan-sketch.ts <seed> <year> [--seat] [--current] > out.json
//   --seat     plan it as a county seat (a courthouse square instead of the plaza)
//   --current  dump the town as the generator builds it today (for comparison), in the same JSON shape
// Then: python3 kotlin-street/tools/plan-map.py <sheet.jpg> <a.json> <b.json> ... draws the maps.
import { rand } from '../../src/sim/rng';
import { archetypeOf } from '../../src/sim/archetype';
import { glbTenures, townPeople, type GlbTenure } from '../../src/town/glbSites';
import { glbLandmarkTiles, type GlbLandmarkKind } from '../../src/town/glbLandmarks';
import { storeNeeds } from '../../src/town/stores';
import { chooseLandmarks, LANDMARK_SPECS } from '../../src/town/landmarks';
import { hasCinema } from '../../src/town/cinema';
import { waterKind } from '../../src/town/water';
import { townName } from '../../src/world/names';
import type { Rect } from '../../src/town/kinds';

type District = 'core' | 'square' | 'civic' | 'old' | 'worker' | 'rail' | 'strip' | 'postwar' | 'late' | 'heights' | 'park' | 'river';
interface Zone { rect: Rect; d: District }
interface SRoad { rect: Rect; cls: 'main' | 'highway' | 'street' | 'lane' | 'country'; from: number }
interface SLot { rect: Rect; kind: string; d: District; label?: string; from: number; to?: number; before?: string; model?: boolean; order?: number; then?: string }
export interface Sketch {
  title: string; sub: string; year: number; width: number; height: number;
  zones: Zone[]; roads: SRoad[]; lots: SLot[]; water: Rect[]; fields: Rect[]; farms: Rect[];
  rail: { track: [number, number][]; siding: [number, number][]; from: number } | null;
  labels: { x: number; y: number; text: string; size?: number }[];
}

const argv = process.argv.slice(2);
const seed = argv.find((a) => !a.startsWith('--')) ?? 'gamma';
const year = Number(argv.filter((a) => !a.startsWith('--'))[1] ?? 2025);
const seat = argv.includes('--seat');
const R = (...k: (string | number)[]): number => rand(seed, 'plan-sketch', ...k);

// ------------------------------------------------------------------------------------------------ the town's facts
const type = archetypeOf(seed).id;
const peak = Math.max(...Array.from({ length: 76 }, (_, i) => townPeople(seed, 1950 + i)));
const size: 'S' | 'M' | 'L' = peak < 5000 ? 'S' : peak < 9000 ? 'M' : 'L';
const tenures = glbTenures(seed);
const has = (k: string): GlbTenure | undefined => tenures.find((t) => t.kind === `landmark_glb_${k}`);
const stores = storeNeeds(seed);
const civic = chooseLandmarks(seed);
const cinema = hasCinema(seed);
const river = waterKind(seed) === 'river';
const persons = (y: number): number => townPeople(seed, Math.min(2025, Math.max(1950, y))) / 36;
/** Homes the town has in [y] (a household is ~3.2 simulated people; before 1950 the town is a little smaller each decade). */
const homes = (y: number): number => Math.round((persons(y) / 3.2) * (y < 1950 ? Math.max(0.55, 1 - (1950 - y) / 120) : 1));
const shopsIn = (y: number): number => Math.max(6, Math.min(26, Math.round(persons(y) / 8.5)));

// ------------------------------------------------------------------------------------------------ the grid
// Columns (west to east) and rows (north to south) of blocks, measured between street centre lines, in plan tiles
// (2.1 m of ground). Column 0 is the civic block (the square or the plaza), row -1 the block north of Main Street.
const coreHalf = size === 'S' ? 1 : 2; // core blocks each side of the civic block
const stripCols = size === 'S' ? 2 : size === 'M' ? 2 : 3;
const westCols = size === 'S' ? 1 : 2;
const north = size === 'S' ? 2 : 3; // block rows north of Main (row -1 .. -north)
const south = size === 'S' ? 2 : 3; // rows south: 0 downtown, 1 the rail band, 2.. across the tracks
const colsW: Record<number, number> = {};
for (let c = -coreHalf - westCols - (river ? 1 : 0); c <= coreHalf + stripCols + 1; c++) {
  colsW[c] = c === 0 ? (seat ? 44 : 42) : Math.abs(c) <= coreHalf ? 44 : c > coreHalf ? (c === coreHalf + stripCols + 1 ? 150 : 62) : 46;
}
const cMin = Math.min(...Object.keys(colsW).map(Number)), cMax = Math.max(...Object.keys(colsW).map(Number));
const riverCol = river ? cMin + (westCols >= 2 ? 1 : 0) : 999;
if (river) colsW[riverCol] = 30;
const rowsH = (r: number): number => r === -1 ? (seat ? 44 : 38) : r === 0 ? 33 : r === 1 ? 44 : r <= -north ? 48 : 41;
const rMin = -north, rMax = south - 1;
// boundaries: x of the street centre line west of column c; y of the street north of row r
const X = (c: number): number => { let x = -colsW[0]! / 2; if (c > 0) for (let i = 0; i < c; i++) x += colsW[i]!; else for (let i = -1; i >= c; i--) x -= colsW[i]!; return x; };
const Y = (r: number): number => { let y = -rowsH(-1); if (r >= 0) { y = 0; for (let i = 0; i < r; i++) y += rowsH(i); } else for (let i = -2; i >= r; i--) y -= rowsH(i); return y; };
const block = (c: number, r: number): Rect => ({ x: X(c) + 2, y: Y(r) + 2, w: colsW[c]! - 4, h: rowsH(r) - 4 });
const isCore = (c: number): boolean => Math.abs(c) <= coreHalf;
const isStrip = (c: number): boolean => c > coreHalf && c <= coreHalf + stripCols;
const edgeCol = cMax; // the bypass and the big boxes beyond it

function district(c: number, r: number): District {
  if (c === riverCol) return 'river';
  if (c === edgeCol) return r === -1 || r === 0 ? 'strip' : 'late';
  if (r === 1) return c < riverCol ? 'worker' : 'rail';
  if (c === 0 && r === -1) return 'square';
  if (isCore(c) && (r === -1 || r === 0)) return 'core';
  if (isStrip(c) && (r === -1 || r === 0)) return 'strip';
  if (c < riverCol) return r <= -2 ? 'heights' : 'postwar';
  if (c < -coreHalf) return r <= -north ? (type === 'suburb' ? 'heights' : 'old') : r >= 2 ? 'worker' : 'old';
  if (isCore(c) && r === -2) return c === (seat ? 0 : 1) ? 'civic' : 'old';
  if (isCore(c) && r >= 2) return 'worker';
  if (isCore(c)) return r <= -north ? (size === 'S' ? 'old' : 'postwar') : 'old';
  if (isStrip(c)) return r <= -north ? 'late' : r >= 2 ? (c > coreHalf + 1 ? 'late' : 'postwar') : 'postwar';
  return 'late';
}

// ------------------------------------------------------------------------------------------------ build the plan
const zones: Zone[] = [];
const roads: SRoad[] = [];
const lots: SLot[] = [];
const water: Rect[] = [];
const labels: Sketch['labels'] = [];
const PLAT: Record<District, number> = { core: 1885, square: 1885, civic: 1890, old: 1895, worker: 1905, rail: 1890, strip: 1950, postwar: 1947, late: 1975, heights: 1958, park: 1900, river: 0 };
for (let c = cMin; c <= cMax; c++) for (let r = rMin; r <= rMax; r++) zones.push({ rect: { x: X(c), y: Y(r), w: colsW[c]!, h: rowsH(r) }, d: district(c, r) });

const trackY = Y(1) + 26;
const lot = (l: SLot): void => { lots.push(l); };
const tiles = (k: GlbLandmarkKind): [number, number] => glbLandmarkTiles(k);
/** A model's plot facing a street on the north (face N) or south side of [b], [at] tiles from the block's west end. */
function modelLot(k: string, b: Rect, at: number, face: 'N' | 'S', label: string, d: District, extra: Partial<SLot> = {}): SLot | undefined {
  const t = has(k);
  if (!t) return undefined;
  const [w, h] = tiles(`landmark_glb_${k}` as GlbLandmarkKind);
  const rect = { x: b.x + at, y: face === 'N' ? b.y : b.y + b.h - h, w, h };
  const l: SLot = { rect, kind: 'model', d, label, from: t.opened, model: true, ...(t.gone ? { to: t.gone } : {}), before: 'reserved', ...extra };
  lot(l);
  return l;
}
/** Fill a face of a block with house lots of [kinds] (west to east), skipping [skip] rects. */
function houses(b: Rect, face: 'N' | 'S' | 'E' | 'W', d: District, kinds: { k: string; w: number; h: number }[], skip: Rect[], base: number): void {
  const along = face === 'N' || face === 'S';
  const len = along ? b.w : b.h;
  let t = 0, i = 0;
  while (t < len) {
    const pick = kinds[Math.floor(R('k', b.x, b.y, face, i) * kinds.length)]!;
    if (t + pick.w > len) break;
    const rect = along ? { x: b.x + t, y: face === 'N' ? b.y : b.y + b.h - pick.h, w: pick.w, h: pick.h }
      : { x: face === 'W' ? b.x : b.x + b.w - pick.h, y: b.y + t, w: pick.h, h: pick.w };
    if (!skip.some((s) => hit(rect, s))) {
      const cx = rect.x + rect.w / 2, cy = rect.y + rect.h / 2;
      lot({ rect, kind: pick.k, d, from: 0, order: base + Math.hypot(cx, cy * 1.2) / 10 });
    }
    t += pick.w + (d === 'heights' || d === 'late' ? 2 : d === 'worker' ? 0 : 1);
    i++;
  }
}
const hit = (a: Rect, b: Rect): boolean => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const SM = { k: 'small', w: 8, h: 14 }, MD = { k: 'medium', w: 10, h: 16 }, HS = { k: 'house', w: 14, h: 17 }, VL = { k: 'villa', w: 11, h: 18 },
  ES = { k: 'estate', w: 25, h: 19 }, MH = { k: 'mobile', w: 8, h: 12 }, AP = { k: 'apartment', w: 7, h: 14 };

// ---- streets: one between every two neighbouring blocks, with the plan's exceptions
const mainY = 0;
const xW = X(cMin), xE = X(cMax) + colsW[cMax]!;
const coreW = X(-coreHalf), coreE = X(coreHalf + 1);
roads.push({ rect: { x: xW - 30, y: mainY - 1, w: coreW - xW + 31, h: 2 }, cls: 'main', from: 1880 });
roads.push({ rect: { x: coreW, y: mainY - 1, w: coreE - coreW, h: 2 }, cls: 'main', from: 1880 });
roads.push({ rect: { x: coreE, y: mainY - 1, w: xE - coreE + 30, h: 2 }, cls: 'highway', from: 1880 });
for (let r = rMin; r <= rMax + 1; r++) {
  if (r === 0) continue;
  const y = Y(r);
  for (let c = cMin; c <= cMax; c++) {
    if (c === riverCol) continue;
    const up = r - 1 >= rMin ? district(c, r - 1) : undefined, dn = r <= rMax ? district(c, r) : undefined;
    // the street on the track's far side, and the rail band's north street (Railroad Avenue) everywhere
    const from = Math.min(PLAT[up ?? 'late'] ?? 2000, PLAT[dn ?? 'late'] ?? 2000);
    roads.push({ rect: { x: X(c) - 1, y: y - 1, w: colsW[c]! + 2, h: 2 }, cls: 'street', from });
  }
}
for (let c = cMin; c <= cMax + 1; c++) {
  if (c === riverCol || c === riverCol + 1) continue;
  const x = X(c);
  for (let r = rMin; r <= rMax; r++) {
    const left = c - 1 >= cMin ? district(c - 1, r) : undefined, right = c <= cMax ? district(c, r) : undefined;
    // streets cross the track only at a few level crossings: the depot street (the square's east side) and the core's ends
    if (r === 1 && !(c === 1 || c === -coreHalf || c === coreHalf + 1 || c === cMax)) {
      roads.push({ rect: { x: x - 1, y: Y(1), w: 2, h: 12 }, cls: 'street', from: 1890 });
      roads.push({ rect: { x: x - 1, y: Y(2) - 12, w: 2, h: 12 }, cls: 'street', from: 1905 });
      continue;
    }
    // the strip's long blocks behind it, and the late subdivisions: every other street is a cul-de-sac stub
    const late = left === 'late' || right === 'late';
    const from = Math.min(PLAT[left ?? 'late'], PLAT[right ?? 'late']);
    if (late && (r <= -north || r >= 2) && R('stub', c, r) < 0.5 && c !== cMax) {
      roads.push({ rect: { x: x - 1, y: Y(r) + (r < 0 ? rowsH(r) - 22 : 0), w: 2, h: 22 }, cls: 'lane', from });
      continue;
    }
    roads.push({ rect: { x: x - 1, y: Y(r) - 1, w: 2, h: rowsH(r) + 2 }, cls: x === X(cMax) ? 'highway' : 'street', from });
  }
}
// the river: a band down its column, with a park along the bank; Main Street and the street past the track bridge it
if (river) {
  const x0 = X(riverCol);
  for (let y = Y(rMin) - 60; y < Y(rMax + 1) + 60; y += 6) {
    const off = Math.round(Math.sin(y / 37 + R('rv') * 6) * 4);
    water.push({ x: x0 + 10 + off, y, w: 10, h: 6 });
  }
  roads.push({ rect: { x: x0 - 1, y: Y(2) - 1, w: colsW[riverCol]! + 2, h: 2 }, cls: 'street', from: 1905 });
}

// ---- the civic block: a courthouse square (a county seat) or the plaza and town hall
{
  const b = block(0, -1);
  if (seat) {
    lot({ rect: { x: b.x + 1, y: b.y + 1, w: b.w - 2, h: b.h - 2 }, kind: 'square', d: 'square', from: 1890, label: '' });
    lot({ rect: { x: b.x + b.w / 2 - 9, y: b.y + b.h / 2 - 8, w: 18, h: 16 }, kind: 'civicbig', d: 'square', from: 1894, label: 'Courthouse' });
  } else {
    const plaza = { x: b.x + b.w / 2 - 8, y: b.y + b.h - 13, w: 16, h: 13 };
    lot({ rect: plaza, kind: 'square', d: 'square', from: 1890, label: 'Plaza' });
    lot({ rect: { x: plaza.x + 1, y: b.y, w: 14, h: b.h - 15 }, kind: 'civicbig', d: 'square', from: 1895, label: 'Town hall' });
    lot({ rect: { x: b.x, y: b.y + b.h - 13, w: 9, h: 13 }, kind: 'civic', d: 'square', from: 1956, label: 'Post' });
    if (civic.includes('clocktower')) lot({ rect: { x: b.x + b.w - 8, y: b.y + b.h - 15, w: 8, h: 15 }, kind: 'civic', d: 'square', from: 1960, label: 'Clock' });
  }
}
// ---- the core: street-wall blocks on both sides of Main Street, alley behind, back lots onto the next street
const reservedCore: SLot[] = [];
let shopOrder = 0;
for (let c = -coreHalf; c <= coreHalf; c++) {
  for (const r of [-1, 0]) {
    if (c === 0 && r === -1) {
      if (seat) { // the square's other three sides: street walls facing it from the blocks round it (drawn on those blocks)
      }
      continue;
    }
    const b = block(c, r);
    const face: 'N' | 'S' = r === -1 ? 'S' : 'N'; // the face on Main Street
    const skip: Rect[] = [];
    // mid-block slots (the plan's, reserved from the start): the Gazette east of the square, the cinema opposite it
    if (r === -1 && c === 1) { const g = modelLot('gazette_office', b, Math.round(b.w / 2 - 6), 'S', 'Gazette', 'core'); if (g) skip.push(g.rect); }
    if (r === 0 && c === 0 && cinema) { const rc = { x: b.x + b.w / 2 - 15, y: b.y, w: 30, h: 26 }; lot({ rect: rc, kind: 'store', d: 'core', from: 1938, label: 'Cinema', model: true }); skip.push(rc); }
    if (r === 0 && c === -1) { const rc = { x: b.x + 4, y: b.y, w: 12, h: 15 }; lot({ rect: rc, kind: 'store', d: 'core', from: 1900, label: 'General store', model: true }); skip.push(rc); }
    if (r === 0 && c === coreHalf && stores.market !== undefined) { const rc = { x: b.x + b.w - 22, y: b.y, w: 22, h: 27 }; lot({ rect: rc, kind: 'store', d: 'core', from: stores.market, label: 'Market', model: true, before: 'shops' }); skip.push(rc); }
    if (r === -1 && c === -1 && civic.includes('hotel')) { const rc = { x: b.x + b.w - 17, y: b.y + b.h - 23, w: 17, h: 23 }; lot({ rect: rc, kind: 'civic', d: 'core', from: 1958, label: 'Hotel', before: 'shops' }); skip.push(rc); }
    // the Main Street service station on the core's last corner, where Main Street turns into the highway
    if (r === -1 && c === coreHalf) { const s = modelLot('main_street_service_station', b, b.w - 19, 'S', 'Svc stn', 'core'); if (s) skip.push(s.rect); }
    for (let t = 0, i = 0; t + 7 <= b.w; t += 7, i++) {
      const rect = { x: b.x + t, y: face === 'N' ? b.y : b.y + b.h - 13, w: 7, h: 13 };
      if (skip.some((s) => hit(rect, s))) continue;
      lot({ rect, kind: 'shop', d: 'core', from: 0, order: Math.abs(c * 44 + t - 20) + (r === 0 ? 3 : 0) });
      shopOrder++;
    }
    // the alley and the back lots (parking from the 1960s, a tower in a big town)
    const ay = face === 'N' ? b.y + 13 : b.y + b.h - 16;
    roads.push({ rect: { x: b.x, y: ay, w: b.w, h: 3 }, cls: 'lane', from: 1890 });
    const back = { x: b.x, y: face === 'N' ? ay + 3 : b.y, w: b.w, h: face === 'N' ? b.y + b.h - ay - 3 : ay - b.y };
    if (size !== 'S' && (c === 1 || c === -1) && r === -1 && !seat) lot({ rect: { x: back.x + 10, y: back.y, w: 14, h: back.h }, kind: 'tower', d: 'core', from: 1994, label: 'Tower', before: 'parking 1965' });
    else lot({ rect: { x: back.x + 2, y: back.y + 1, w: back.w - 4, h: back.h - 2 }, kind: 'parking', d: 'core', from: 1965, before: 'back yards' });
  }
}
// county seat: the square's west and east faces are street walls too (the side blocks' ends)
if (seat) {
  for (const c of [-1, 1]) {
    const b = block(c, -1);
    for (let t = 0; t + 7 <= b.h - 13; t += 7) {
      const rect = { x: c < 0 ? b.x + b.w - 13 : b.x, y: b.y + t, w: 13, h: 7 };
      lot({ rect, kind: 'shop', d: 'core', from: 0, order: 30 + t });
    }
  }
  const b = block(0, -2);
  lot({ rect: { x: b.x, y: b.y + b.h - 13, w: 9, h: 13 }, kind: 'civic', d: 'civic', from: 1956, label: 'Post' });
  lot({ rect: { x: b.x + 11, y: b.y + b.h - 14, w: 12, h: 14 }, kind: 'civic', d: 'civic', from: 1900, label: 'Town hall' });
  lot({ rect: { x: b.x + b.w - 16, y: b.y + b.h - 15, w: 16, h: 15 }, kind: 'civic', d: 'civic', from: 1955, label: 'Fire' });
}

// ---- the civic block in the old town: school, church, fire, police
{
  const c = seat ? -1 : 1, b = block(c, -2);
  lot({ rect: { x: b.x, y: b.y, w: 24, h: 23 }, kind: 'civic', d: 'civic', from: 1925, label: 'School' });
  lot({ rect: { x: b.x + b.w - 7, y: b.y + b.h - 11, w: 7, h: 11 }, kind: 'church', d: 'civic', from: 1905, label: 'Church' });
  if (!seat && civic.includes('fire')) lot({ rect: { x: b.x + b.w - 16, y: b.y, w: 16, h: 15 }, kind: 'civic', d: 'civic', from: 1955, label: 'Fire' });
  if (civic.includes('police')) lot({ rect: { x: b.x + 26, y: b.y + b.h - 18, w: 11, h: 18 }, kind: 'civic', d: 'civic', from: 1960, label: 'Police' });
}

// ---- the rail band: Railroad Avenue on its north side, the track, the depot facing the depot street, the siding with
// the elevator and feed mill west of it, the works east, warehouses between
const railFrom = 1888;
const sidingY = trackY + 5;
for (let c = cMin; c <= cMax; c++) {
  if (district(c, 1) !== 'rail') continue;
  const b = block(c, 1);
  const nb = { x: b.x, y: b.y, w: b.w, h: trackY - 4 - b.y }; // between Railroad Ave and the track
  const sb = { x: b.x, y: trackY + (c < 1 ? 9 : 4), w: b.w, h: b.y + b.h - trackY - (c < 1 ? 9 : 4) };
  const skip: Rect[] = [];
  if (c === 0 || (c === 1 && false)) {
    const k = has('premier_railroad_depot') ? 'premier_railroad_depot' : undefined;
    const w = k ? 43 : 26;
    const rect = { x: X(1) - w / 2, y: trackY - 4 - 12, w, h: 12 };
    lot({ rect, kind: 'model', d: 'rail', from: 1900, label: k ? 'Union Depot' : 'Depot', model: !!k });
    skip.push(rect);
  }
  if (c === -1 || (c === 0 && coreHalf === 1 && false)) {
    const fm = modelLot('feed_mill', { ...sb, y: trackY + 9 }, 4, 'N', 'Feed mill', 'rail');
    if (fm) skip.push(fm.rect);
    lot({ rect: { x: sb.x + 31, y: trackY + 9, w: 9, h: 9 }, kind: 'industry', d: 'rail', from: 1925, label: 'Elevator' });
    if (type === 'farm') { const wt = modelLot('water_tower', nb, 2, 'N', 'Water tower', 'rail'); if (wt) skip.push(wt.rect); }
  }
  if (c === (type === 'mill' || type === 'mining' ? (river ? riverCol + 1 : -coreHalf - 1) : 2)) {
    const f = has('foundry_factory');
    if (f) lot({ rect: { x: sb.x + 6, y: sb.y + sb.h - 15, w: 24, h: 15 }, kind: 'model', d: 'rail', model: true, from: f.opened, label: f.gone && year >= f.gone ? 'The Foundry (club)' : 'Iron works', then: 'The Foundry' });
  }
  // warehouses and lumber yard along both sides (code-built boxes, to come)
  for (const band of [nb, sb]) {
    for (let t = 1; t + 14 <= band.w; t += 18) {
      const rect = { x: band.x + t, y: band === nb ? band.y + band.h - 10 : band.y, w: 14, h: 10 };
      if (skip.some((s) => hit(rect, { ...s, x: s.x - 2, w: s.w + 4 })) || lots.some((l) => hit(rect, l.rect)) || R('whx', c, t, band.y) < (type === 'suburb' ? 0.45 : 0.25)) continue;
      lot({ rect, kind: 'warehouse', d: 'rail', from: 1900 + Math.floor(R('wh', c, t, band.y) * 60) });
    }
  }
}

// ---- the highway strip: deep lots on Main Street east of the core, car parks in front, in the order it grew
{
  const c1 = coreHalf + 1;
  const N = (c: number): Rect => block(c, -1), S = (c: number): Rect => block(c, 0);
  const placed: Rect[] = [];
  const m = (k: string, b: Rect, at: number, face: 'N' | 'S', label: string, extra: Partial<SLot> = {}): void => {
    const l = modelLot(k, b, at, face, label, 'strip', extra);
    if (l) placed.push(l.rect);
  };
  m('junction_diner', N(c1), 1, 'S', 'Diner');
  m('roadside_motel', N(c1), 18, 'S', 'Motel', { then: 'apartments' });
  m('coin_laundromat', S(c1), 1, 'N', 'Laundry');
  // the strip centre (built in the 1970s): a row of units with its car park, the video store among them
  lot({ rect: { x: S(c1).x + 19, y: S(c1).y, w: 36, h: 12 }, kind: 'stripmall', d: 'strip', from: 1972, label: 'Strip centre + Reel Time video', before: 'field' });
  m('starling_lanes', N(c1 + 1), 2, 'S', 'Lanes', { then: 'offices' });
  const mir = has('mirage_nightclub') ?? has('pulse_nightclub');
  if (mir) lot({ rect: { x: N(c1 + 1).x + N(c1 + 1).w - 23, y: N(c1 + 1).y + N(c1 + 1).h - 15, w: 23, h: 15 }, kind: 'model', d: 'strip', model: true, from: mir.opened, label: has('mirage_nightclub') ? 'Mirage / Pulse' : 'Pulse', before: 'reserved' });
  // the drive-in on the edge (a field before), whose ground the supermarket takes when it shuts
  const di = has('starlight_drive_in');
  const sb2 = S(c1 + 1);
  if (di) lot({ rect: { x: sb2.x + 2, y: sb2.y, w: 29, h: 21 }, kind: 'model', d: 'strip', model: true, from: di.opened, to: di.gone, label: 'Drive-in', before: 'field' });
  if (stores.supermarket !== undefined) lot({ rect: { x: sb2.x + 2, y: sb2.y, w: 52, h: Math.min(40, sb2.h) }, kind: 'store', d: 'strip', from: Math.max(stores.supermarket, (di?.gone ?? 0) + 1), label: 'Supermarket', model: true, before: di ? 'drive-in' : 'field' });
  // past the strip: the bypass (the section road) with the 2000s gas station at the junction and the superstore beyond
  const eb = block(cMax, 0), en = block(cMax, -1);
  m('modern_gas_station', { ...eb, x: eb.x }, 1, 'N', 'Gas (2000s)');
  const e2 = block(cMax, -2);
  if (stores.superstore !== undefined) lot({ rect: { x: e2.x + 30, y: e2.y + 4, w: 112, h: en.y + en.h - e2.y - 4 }, kind: 'store', d: 'strip', from: stores.superstore, label: 'Superstore', model: true, before: 'field' });
  // the strip's rear: garden apartments behind the motel row
  for (const c of [c1, c1 + 1, c1 + 2].filter((c) => c < cMax)) {
    const b = N(c);
    for (let t = 0; t + 7 <= b.w; t += 9) {
      const rect = { x: b.x + t, y: b.y, w: 7, h: 13 };
      if (!placed.some((p) => hit(rect, p)) && !lots.some((l) => hit(rect, l.rect))) lot({ rect, kind: 'apartment', d: 'strip', from: 0, order: 1500 + t });
    }
    const s = S(c);
    for (let t = 0; t + 18 <= s.w && c > c1; t += 22) {
      const rect = { x: s.x + t, y: s.y, w: 18, h: 14 };
      if (!lots.some((l) => hit(rect, l.rect))) lot({ rect, kind: 'stripshop', d: 'strip', from: 1958 + Math.floor(R('ss', c, t) * 40), before: 'field' });
    }
  }
}

// ---- the funeral home: a big old house on the corner of West Main and the first cross street west of downtown
{
  const b = block(-coreHalf - 1, -1);
  const fh = has('funeral_home');
  if (fh && district(-coreHalf - 1, -1) === 'old') lot({ rect: { x: b.x + b.w - 22, y: b.y + b.h - 11, w: 22, h: 11 }, kind: 'model', d: 'old', model: true, from: fh.houseBuilt ?? 1915, label: year >= fh.opened ? 'Funeral home' : 'Big house (funeral home later)' });
}
// ---- the water tower (not in a farm town, where it stands by the elevator): on the heights, else on the civic block
{
  const wt = has('water_tower');
  if (wt && type !== 'farm') {
    const hb = zones.find((z) => z.d === 'heights');
    const b = hb ? hb.rect : block(seat ? -1 : 1, -2);
    lot({ rect: hb ? { x: b.x + 4, y: b.y + 4, w: 11, h: 7 } : { x: b.x + 26, y: b.y + 1, w: 11, h: 7 }, kind: 'model', d: 'civic', model: true, from: wt.opened, label: 'Water tower' });
  }
}
// ---- park and cemetery: the river's banks, and a cemetery at the old town's edge
if (river) lots.push({ rect: { ...block(riverCol, rMin), h: Y(rMax + 1) - Y(rMin) - 4 }, kind: 'park', d: 'park', from: 1910, label: 'River park' });
{
  const c = -coreHalf - (river && riverCol === -coreHalf - 1 ? 2 : 1), b = block(c, -2);
  if (district(c, -2) === 'old' || district(c, -2) === 'heights') lot({ rect: { x: b.x + 2, y: b.y + 2, w: b.w - 4, h: Math.round(b.h / 2) - 2 }, kind: 'cemetery', d: 'park', from: 1885, label: 'Cemetery' });
}
// ---- homes: every residential block's faces, by district
for (const z of zones) {
  const c = cOf(z.rect.x), r = rOf(z.rect.y);
  const b = block(c, r);
  const skip = lots.filter((l) => hit(l.rect, b)).map((l) => l.rect);
  const d = z.d;
  if (d === 'old') {
    const k = c < -coreHalf && r === -1 ? [HS, HS, MD] : [MD, MD, SM, HS];
    houses(b, 'N', d, k, skip, 0); houses(b, 'S', d, k, skip, 0);
    if (!skip.length || r !== -1) roads.push({ rect: { x: b.x, y: b.y + b.h / 2 - 1.5, w: b.w, h: 3 }, cls: 'lane', from: 1900 });
  } else if (d === 'worker') {
    const k = [SM, SM, MD, MH];
    houses(b, 'N', d, k, skip, 100); houses(b, 'S', d, k, skip, 100);
  } else if (d === 'postwar') { houses(b, 'N', d, [MD, MD, HS], skip, 1000); houses(b, 'S', d, [MD, MD, HS, SM], skip, 1000); }
  else if (d === 'late') { if (c !== cMax) { houses(b, 'N', d, [HS, HS, VL, AP], skip, 2000); houses(b, 'S', d, [HS, VL, HS], skip, 2000); } }
  else if (d === 'heights') { houses(b, 'N', d, [VL, VL, ES], skip, 1200); houses(b, 'S', d, [VL, ES, VL], skip, 1200); }
}
function cOf(x: number): number { for (let c = cMin; c <= cMax; c++) if (X(c) === x) return c; return 0; }
function rOf(y: number): number { for (let r = rMin; r <= rMax; r++) if (Y(r) === y) return r; return 0; }

// ---- when each home and shop is built: the town fills its plots in plan order as it grows (old town, then the
// worker side, then the post-war, the heights and the late subdivisions), as many as it has households that year
const homeLots = lots.filter((l) => l.order !== undefined && l.kind !== 'shop').sort((a, b) => a.order! - b.order!);
const shopLots = lots.filter((l) => l.kind === 'shop').sort((a, b) => a.order! - b.order!);
const cap = (k: string): number => (k === 'apartment' ? 4 : 1);
const yearFor = (i: number, f: (y: number) => number): number | undefined => { for (let y = 1880; y <= 2025; y++) if (f(y) > i) return y; return undefined; };
// The plan is sized for the town at its peak, and a district is built out as the town grows into it: lot i is built
// the first year the town's homes, scaled so the plan is (nearly) full at the peak, pass i. The simulated households
// live in some of these houses; the rest are the town's other families (TOWN_PLAN.md "Households and filler homes").
const fill = (homeLots.reduce((n, l) => n + cap(l.kind), 0) * 0.96) / Math.max(...Array.from({ length: 76 }, (_, i) => homes(1950 + i)));
let filled = 0;
for (const l of homeLots) { const y = yearFor(filled, (yy) => homes(yy) * fill); l.from = y === undefined ? 9999 : Math.max(y, PLAT[l.d] + Math.floor(R('lag', l.rect.x, l.rect.y) * 8)); filled += cap(l.kind); if (y === undefined || y > 1950) l.before = l.d === 'old' || l.d === 'worker' ? 'woods' : 'field'; }
shopLots.forEach((l, i) => { l.from = (yearFor(i, shopsIn) ?? 9999); });

// ---- country roads out (spokes) and the farmland round the built-up area
const fields: Rect[] = [], farms: Rect[] = [];
const top = Y(rMin) - 2, bottom = Y(rMax + 1) + 2;
const M = 70;
for (let x = xW - M; x < xE + M; x += 46) for (let y = top - M; y < bottom + M; y += 34) {
  const f = { x, y, w: 42, h: 30 };
  if (hit(f, { x: xW - 4, y: top - 4, w: xE - xW + 8, h: bottom - top + 8 })) continue;
  if (water.some((w) => hit(w, f))) continue;
  if (R('farm', x, y) < 0.12) farms.push({ x: x + 6, y: y + 6, w: 28, h: 18 }); else fields.push(f);
}
roads.push({ rect: { x: X(1) - 1, y: top - M, w: 2, h: M }, cls: 'country', from: 1880 });
roads.push({ rect: { x: X(1) - 1, y: bottom, w: 2, h: M }, cls: 'country', from: 1880 });

// ---- the year's view: everything planned, with what stands this year flagged (the renderer greys out the rest)
const minX = xW - M, minY = top - M;
const mv = (r: Rect): Rect => ({ x: r.x - minX, y: r.y - minY, w: r.w, h: r.h });
const zonesOut = zones.map((z) => ({ ...z, rect: mv(z.rect) }));
// a district whose plots are all still to come shows as farmland that year
const out: Sketch = {
  title: `${townName(seed)} (${seed}), ${year}: planned`,
  sub: `${archetypeOf(seed).label}${seat ? ', county seat' : ''}; ${Math.round(townPeople(seed, Math.min(2025, Math.max(1950, year))) / 10) * 10} people (peak ${peak}); size ${size}`,
  year, width: xE - xW + 2 * M, height: bottom - top + 2 * M,
  zones: zonesOut, roads: roads.map((r) => ({ ...r, rect: mv(r.rect) })), lots: lots.map((l) => ({ ...l, rect: mv(l.rect) })),
  water: water.map(mv), fields: fields.map(mv), farms: farms.map(mv),
  rail: { track: [[0, trackY - minY], [xE - xW + 2 * M, trackY - minY]], siding: [[X(-1) - minX, sidingY - minY], [X(1) - minX + 10, sidingY - minY]], from: railFrom },
  labels: [],
};
process.stdout.write(JSON.stringify(out));
void LANDMARK_SPECS;
