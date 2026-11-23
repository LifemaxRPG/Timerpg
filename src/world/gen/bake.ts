import { MONTHS } from './climate';
import { H, N, W } from './grid';
import { GAMMA_PLAN_TILES } from './hubs';
import { worldPaths, type Pt } from './paths';
import type { Place } from './places';
import type { World } from './world';

// THE BAKED SKELETON (WORLD_GEN.md section 2, "The skeleton"): one binary per world seed, read whole by the app (W4's
// Kotlin reader). Layout, little-endian:
//   "TWSK" | u32 version | u32 json length | json (UTF-8) | padding to 4 | sections
// The JSON holds the vectors and facts (places, roads, rail, sea routes, rivers, lakes, dams, landmasses, the sections'
// table); each section is a typed array at an offset from the start of the sections. Cell lists of vectors live in one
// Int32 pool ("cells"), referenced as [offset, length]. Grids are per atlas cell (W x H, row-major, x east, z south);
// the monthly climate is stored for land cells only (in the order of their index), the sea's months on a 4 x 4 grid.
// The tool kotlin-street/tools/bake-world.ts gzips it.

// Version 2 (W4): every river, road and rail line has its true path (paths.ts: hub ends on their pins, the capital's
// river bent through its river pins) in the "pts" section (x, z pairs in atlas cells x 2^19, x unwrapped along the
// line), referenced as path: [first point, points]; places and pins at full precision; the capital's plan frame.
export const SKELETON_VERSION = 2;
/** Fixed point of the "pts" section: units of an atlas cell. */
export const PTS_SCALE = 524288;

interface Section { name: string; type: 'i8' | 'u8' | 'i16' | 'u16' | 'i32' | 'f32'; offset: number; length: number; note: string }
type Typed = Int8Array | Uint8Array | Int16Array | Uint16Array | Int32Array | Float32Array;

const SIZE = { i8: 1, u8: 1, i16: 2, u16: 2, i32: 4, f32: 4 } as const;

export function encodeSkeleton(w: World): Uint8Array {
  const t = w.terrain, cl = w.climate, h = w.hydro;
  const sections: Section[] = [];
  const arrays: Typed[] = [];
  let off = 0;
  const add = (name: string, type: Section['type'], a: Typed, note: string): void => {
    sections.push({ name, type, offset: off, length: a.length, note });
    arrays.push(a);
    off += a.length * SIZE[type];
    off = (off + 3) & ~3;
  };
  // ---- grids
  const elev = new Int16Array(N), flags = new Uint8Array(N), flow = new Uint16Array(N), steady = new Uint8Array(N), mass = new Uint16Array(N), snow = new Int16Array(N);
  for (let c = 0; c < N; c++) {
    elev[c] = Math.max(-32768, Math.min(32767, Math.round(t.elev[c]!)));
    flags[c] = (t.land[c]! ? 1 : 0) | (h.flow[c]! >= 120 && t.land[c] ? 2 : 0) | (h.lake[c] ? 4 : 0) | (h.coast[c]! << 3);
    flow[c] = Math.min(65535, Math.round(Math.log2(1 + h.flow[c]!) * 2048));
    steady[c] = Math.round(Math.min(1, h.steady[c]!) * 255);
    mass[c] = t.mass[c]!;
    snow[c] = Math.max(-32768, Math.min(32767, Math.round(cl.snowline[c]!)));
  }
  add('elev', 'i16', elev, 'metres above sea level');
  add('flags', 'u8', flags, 'bit 0 land, 1 river, 2 lake, 3-4 coast kind (1 sand, 2 rocky, 3 cliff)');
  add('biome', 'u8', w.biome, 'index into climate.ts BIOMES');
  add('koppen', 'u8', cl.koppen, 'index into climate.ts KOPPEN');
  add('crop', 'u8', w.crop, 'index into climate.ts CROPS');
  add('flow', 'u16', flow, 'river flow: round(log2(1 + flow) * 2048), flow in km2 x m of runoff a year');
  add('steady', 'u8', steady, 'driest month over mean month flow, x 255');
  add('mass', 'u16', mass, 'landmass id (0 sea)');
  add('lake', 'u16', Uint16Array.from(h.lake), 'lake id (0 none)');
  add('snowline', 'i16', snow, 'metres: where the warmest month is at freezing');
  add('current', 'i8', Int8Array.from(cl.current, (v) => Math.round(v * 10)), 'ocean current anomaly, C x 10');
  // ---- monthly climate: land cells (temperature C x 2, rain as round(sqrt(mm) x 10)), sea temperatures on 4 x 4 cells
  const landIdx: number[] = [];
  for (let c = 0; c < N; c++) if (t.land[c]) landIdx.push(c);
  const L = landIdx.length;
  const tM = new Int8Array(L * MONTHS), rM = new Uint8Array(L * MONTHS);
  for (let m = 0; m < MONTHS; m++) for (let k = 0; k < L; k++) {
    const c = landIdx[k]!;
    tM[m * L + k] = Math.max(-128, Math.min(127, Math.round(cl.tempM[m * N + c]! * 2)));
    rM[m * L + k] = Math.min(255, Math.round(Math.sqrt(Math.max(0, cl.rainM[m * N + c]!)) * 10));
  }
  add('landTempM', 'i8', tM, 'land cells in index order, month-major: C x 2');
  add('landRainM', 'u8', rM, 'land cells in index order, month-major: round(sqrt(mm) x 10)');
  const SW = W / 4, SH = H / 4;
  const sT = new Int8Array(SW * SH * MONTHS);
  for (let m = 0; m < MONTHS; m++) for (let j = 0; j < SH; j++) for (let i = 0; i < SW; i++) sT[m * SW * SH + j * SW + i] = Math.max(-128, Math.min(127, Math.round(cl.tempM[m * N + (j * 4 + 2) * W + i * 4 + 2]! * 2)));
  add('seaTempM', 'i8', sT, `${SW} x ${SH} cells of 4 x 4, month-major: C x 2`);
  // ---- region weather
  const rw = w.weather.regions;
  const wx = new Uint16Array(rw.length * 10);
  rw.forEach((r, k) => wx.set([r.stormDays, Math.round(r.hurricane * 100), Math.round(r.tornado * 100), r.snowDays, r.fogDays, r.heatDays, Math.round(r.drought * 100), r.wetMonth, r.dryMonth, r.snowMonths], k * 10));
  add('weather', 'u16', wx, 'per region of 16 x 16 cells: storm days, hurricane %, tornadoes x100 per 10,000 km2, snow days, fog days, heat days, drought %, wettest month, driest month, snow months');
  // ---- vectors: cell pool
  const pool: number[] = [];
  const ref = (cells: readonly number[]): [number, number] => { const o = pool.length; pool.push(...cells); return [o, cells.length]; };
  const pts: number[] = [];
  const path = (line: readonly Pt[]): [number, number] => { const o = pts.length / 2; for (const [x, z] of line) pts.push(Math.round(x * PTS_SCALE), Math.round(z * PTS_SCALE)); return [o, line.length]; };
  const paths = worldPaths(w);
  const r6 = (v: number): number => Math.round(v * 1e6) / 1e6;
  const place = (p: Place): unknown => ({
    id: p.id, kind: p.kind, type: p.type, rank: p.rank, x: p.hub ? r6(p.x) : Math.round(p.x * 1000) / 1000, z: p.hub ? r6(p.z) : Math.round(p.z * 1000) / 1000, mass: p.mass, seed: p.seed, name: p.name, founded: p.founded,
    ...(p.hub ? {
      launch: p.hub.launch, plan: p.hub.plan ?? null, padM: p.hub.padM, growthM: p.hub.growthM,
      ...(p.hub.plan === 'gamma' ? { planTiles: { ...GAMMA_PLAN_TILES, tileM: 2.1 } } : {}),
      pins: p.pins!.map((q, k) => ({ kind: q.kind, name: q.name, x: r6(q.x), z: r6(q.z), ox: q.ox, oz: q.oz, dx: p.hub!.pins[k]!.dx, dz: p.hub!.pins[k]!.dz })),
    } : {}),
    tags: w.tags.get(p.id) ?? [],
  });
  const json = {
    format: 'Timerpg world skeleton', version: SKELETON_VERSION, seed: w.seed, grid: { w: W, h: H, cellM: 1075.2, wrapsX: true }, startMass: w.startMass,
    masses: w.terrain.masses,
    places: w.places.map(place),
    roads: w.roads.map((r, k) => ({ id: r.id, cls: r.cls, a: r.a, b: r.b, km: r.km, built: r.built, paved: r.paved, ...(r.expressway !== undefined ? { expressway: r.expressway } : {}), cells: ref(r.cells), path: path(paths.roads[k]!) })),
    railLines: w.rail.lines.map((l, k) => ({ id: l.id, kind: l.kind, stations: l.stations, at: l.at, cells: ref(l.cells), path: path(paths.rail[k]!) })),
    railLinks: w.rail.links,
    sea: w.sea.routes.map((r) => ({ ...r, cells: ref(r.cells) })),
    rivers: w.hydro.rivers.map((r, k) => ({ flow: r.flow, order: r.order, end: r.end, cells: ref(r.cells), path: path(paths.rivers[k]!) })),
    lakes: w.hydro.lakes,
    dams: w.dams.map((d) => ({ ...d, cells: ref(d.cells) })),
    sections,
  };
  add('cells', 'i32', Int32Array.from(pool), 'the vectors\' cell lists (index = z * w + x)');
  add('pts', 'i32', Int32Array.from(pts), `the vectors' paths: x, z pairs in atlas cells x ${PTS_SCALE} (x unwrapped along a line)`);
  json.sections = sections;
  const head = new TextEncoder().encode(JSON.stringify(json));
  const start = (12 + head.length + 3) & ~3;
  const out = new Uint8Array(start + off);
  const dv = new DataView(out.buffer);
  out.set([84, 87, 83, 75], 0); // TWSK
  dv.setUint32(4, SKELETON_VERSION, true);
  dv.setUint32(8, head.length, true);
  out.set(head, 12);
  sections.forEach((s, k) => out.set(new Uint8Array(arrays[k]!.buffer, arrays[k]!.byteOffset, arrays[k]!.byteLength), start + s.offset));
  return out;
}

export interface DecodedSkeleton {
  seed: string;
  places: { id: string; name: string; kind: string; x: number; z: number }[];
  roads: unknown[];
  railLinks: unknown[];
  elev: Int16Array;
  json: Record<string, unknown>;
  section(name: string): Typed;
}

export function decodeSkeleton(bin: Uint8Array): DecodedSkeleton {
  const dv = new DataView(bin.buffer, bin.byteOffset, bin.byteLength);
  if (String.fromCharCode(...bin.subarray(0, 4)) !== 'TWSK') throw new Error('not a world skeleton');
  const len = dv.getUint32(8, true);
  const json = JSON.parse(new TextDecoder().decode(bin.subarray(12, 12 + len))) as Record<string, unknown> & { sections: Section[] };
  const start = (12 + len + 3) & ~3;
  const section = (name: string): Typed => {
    const s = json.sections.find((q) => q.name === name)!;
    // (a copy of its bytes: a Node Buffer's slice is a view into a shared pool, whose .buffer is the whole pool)
    const buf = Uint8Array.prototype.slice.call(bin, start + s.offset, start + s.offset + s.length * SIZE[s.type]).buffer;
    return s.type === 'i8' ? new Int8Array(buf) : s.type === 'u8' ? new Uint8Array(buf) : s.type === 'i16' ? new Int16Array(buf) : s.type === 'u16' ? new Uint16Array(buf) : s.type === 'i32' ? new Int32Array(buf) : new Float32Array(buf);
  };
  return { seed: json.seed as string, places: json.places as DecodedSkeleton['places'], roads: json.roads as unknown[], railLinks: json.railLinks as unknown[], elev: section('elev') as Int16Array, json, section };
}
