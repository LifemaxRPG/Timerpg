// Maps of the real world generator (src/world/gen, WORLD_GEN.md "The generator"): for each seed, the whole planet
// (biomes, relief, rivers, roads, rail, sea routes, hubs), the capital's continent, the Koppen climate classes, and
// January and July temperatures, written as PNG panels with a labels.json; kotlin-street/tools/world-sheet.py lays them
// on one sheet.
// Usage: npx tsx kotlin-street/tools/world-map.ts <outdir> <seed> [<seed> ...]
import { mkdirSync, writeFileSync } from 'fs';
import { PNG } from 'pngjs';
import { BIOMES, KOPPEN, MONTHS, koppenGroup } from '../../src/world/gen/climate';
import { CELL_KM, H, N, W, wrapDx } from '../../src/world/gen/grid';
import { RIVER_FLOW } from '../../src/world/gen/hydro';
import { generateWorld, type World } from '../../src/world/gen/world';

type RGB = [number, number, number];
const BCOL: Record<string, RGB> = {
  ocean: [40, 80, 140], ice: [236, 242, 248], tundra: [150, 158, 130], taiga: [58, 92, 70], 'temperate forest': [66, 120, 62],
  'temperate rainforest': [40, 98, 70], prairie: [150, 172, 88], steppe: [176, 170, 110], desert: [214, 190, 132], savanna: [178, 168, 84],
  'tropical forest': [70, 140, 60], rainforest: [30, 110, 52], alpine: [140, 132, 122], marsh: [92, 124, 92], lake: [72, 124, 176],
  beach: [228, 214, 162], rock: [128, 122, 116], chaparral: [168, 156, 96], glacier: [222, 232, 244],
};
const KCOL: Record<string, RGB> = {
  Af: [0, 0, 254], Am: [0, 119, 255], Aw: [70, 169, 250], BWh: [254, 0, 0], BWk: [254, 150, 149], BSh: [245, 165, 0], BSk: [255, 220, 100],
  Csa: [255, 255, 0], Csb: [198, 199, 0], Csc: [150, 150, 0], Cwa: [150, 255, 150], Cwb: [99, 199, 100], Cwc: [50, 150, 51],
  Cfa: [198, 255, 78], Cfb: [102, 255, 51], Cfc: [51, 199, 1], Dsa: [255, 0, 254], Dsb: [198, 0, 199], Dsc: [150, 50, 149], Dsd: [150, 100, 150],
  Dwa: [171, 177, 255], Dwb: [90, 119, 219], Dwc: [76, 81, 181], Dwd: [50, 0, 135], Dfa: [0, 255, 255], Dfb: [56, 199, 255], Dfc: [0, 126, 125],
  Dfd: [0, 69, 94], ET: [178, 178, 178], EF: [104, 104, 104], H: [140, 100, 80], ocean: [235, 240, 246],
};
export const KOPPEN_LEGEND = ['Af', 'Am', 'Aw', 'BWh', 'BWk', 'BSh', 'BSk', 'Csa', 'Csb', 'Cwa', 'Cwb', 'Cfa', 'Cfb', 'Cfc', 'Dsb', 'Dwb', 'Dfb', 'Dfc', 'ET', 'EF', 'H'].map((k) => ({ k, c: KCOL[k]! }));
export const BIOME_COL = BCOL;

class Img {
  readonly d: Uint8Array;
  constructor(readonly w: number, readonly h: number, bg: RGB = [255, 255, 255]) { this.d = new Uint8Array(w * h * 3); for (let i = 0; i < w * h; i++) this.d.set(bg, i * 3); }
  set(x: number, y: number, c: RGB): void { x |= 0; y |= 0; if (x < 0 || y < 0 || x >= this.w || y >= this.h) return; this.d.set(c, (y * this.w + x) * 3); }
  line(x0: number, y0: number, x1: number, y1: number, c: RGB, wd = 1): void {
    const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0)));
    for (let k = 0; k <= n; k++) {
      const x = x0 + ((x1 - x0) * k) / n, y = y0 + ((y1 - y0) * k) / n;
      for (let dy = 0; dy < wd; dy++) for (let dx = 0; dx < wd; dx++) this.set(x + dx - (wd >> 1), y + dy - (wd >> 1), c);
    }
  }
  rect(x: number, y: number, r: number, c: RGB, o: RGB = [0, 0, 0]): void { for (let dy = -r - 1; dy <= r + 1; dy++) for (let dx = -r - 1; dx <= r + 1; dx++) this.set(x + dx, y + dy, Math.abs(dx) > r || Math.abs(dy) > r ? o : c); }
  ring(x: number, y: number, r: number, c: RGB): void { for (let a = 0; a < 64; a++) { const t = (a / 64) * Math.PI * 2; this.set(x + Math.cos(t) * r, y + Math.sin(t) * r, c); this.set(x + Math.cos(t) * (r + 1), y + Math.sin(t) * (r + 1), c); } }
  save(path: string): void { const p = new PNG({ width: this.w, height: this.h }); for (let i = 0; i < this.w * this.h; i++) { p.data[i * 4] = this.d[i * 3]!; p.data[i * 4 + 1] = this.d[i * 3 + 1]!; p.data[i * 4 + 2] = this.d[i * 3 + 2]!; p.data[i * 4 + 3] = 255; } writeFileSync(path, PNG.sync.write(p)); }
}

export const HUB_FILL: Record<string, RGB> = { capital: [220, 40, 40], port: [40, 90, 210], 'river city': [30, 170, 200], 'mountain town': [120, 80, 50], 'mill town': [200, 120, 30], 'farm town': [230, 200, 40] };

function shadeAt(w: World, c: number): number {
  const e = w.terrain.elev, i = c % W, j = (c / W) | 0;
  if (!w.terrain.land[c]) return 1;
  const gx = (Math.max(0, e[j * W + (i + 1) % W]!) - Math.max(0, e[j * W + (i + W - 1) % W]!)) / (2 * CELL_KM * 1000);
  const gz = (Math.max(0, e[Math.min(H - 1, j + 1) * W + i]!) - Math.max(0, e[Math.max(0, j - 1) * W + i]!)) / (2 * CELL_KM * 1000);
  const nx = -gx * 9, nz = -gz * 9, nl = Math.hypot(nx, nz, 1);
  const l = (-0.6 * nx - 0.6 * nz + 0.55) / (nl * Math.hypot(0.6, 0.6, 0.55));
  return 0.55 + 0.75 * Math.max(0, Math.min(1, l));
}

function baseColour(w: World, c: number): RGB {
  const t = w.terrain;
  if (!t.land[c]) {
    const d = Math.min(1, -t.elev[c]! / 4500), lat = Math.abs((((c / W) | 0) + 0.5) / H * 180 - 90);
    const ice = Math.max(0, Math.min(1, (lat - 62) / 8));
    const s: RGB = [60 - 40 * d, 120 - 70 * d, 170 - 60 * d];
    return [s[0] * (1 - ice) + 214 * ice, s[1] * (1 - ice) + 226 * ice, s[2] * (1 - ice) + 236 * ice];
  }
  const b = BIOMES[w.biome[c]!]!;
  const col = t.elev[c]! > w.climate.snowline[c]! - 150 && b !== 'lake' ? [232, 236, 242] as RGB : BCOL[b]!;
  const s = shadeAt(w, c);
  return [Math.min(255, col[0] * s), Math.min(255, col[1] * s), Math.min(255, col[2] * s)];
}

function tempColour(v: number): RGB {
  // -30 (deep blue) .. 0 (white) .. +35 (dark red)
  const stops: [number, RGB][] = [[-35, [40, 0, 120]], [-20, [40, 80, 220]], [-5, [140, 200, 250]], [0, [245, 245, 245]], [10, [180, 230, 140]], [20, [250, 220, 80]], [28, [240, 120, 40]], [36, [150, 20, 20]]];
  if (v <= stops[0]![0]) return stops[0]![1];
  for (let k = 1; k < stops.length; k++) if (v <= stops[k]![0]) { const [a, ca] = stops[k - 1]!, [b, cb] = stops[k]!; const f = (v - a) / (b - a); return [ca[0] + (cb[0] - ca[0]) * f, ca[1] + (cb[1] - ca[1]) * f, ca[2] + (cb[2] - ca[2]) * f]; }
  return stops[stops.length - 1]![1];
}

/** A panel sampling the planet: [roll] cells, scale [s] px per cell, window x0..x1, z0..z1 in rolled cell units. */
function panel(w: World, x0: number, z0: number, pw: number, ph: number, s: number, roll: number, col: (c: number) => RGB): Img {
  const img = new Img(pw, ph);
  for (let py = 0; py < ph; py++) for (let px = 0; px < pw; px++) {
    const cx = x0 + (px + 0.5) / s, cz = z0 + (py + 0.5) / s;
    const i = ((Math.floor(cx - roll) % W) + W) % W, j = Math.floor(cz);
    if (j < 0 || j >= H) continue;
    img.set(px, py, col(j * W + i));
  }
  return img;
}

interface Label { x: number; y: number; text: string; launch: boolean; type: string }

function overlays(w: World, img: Img, tf: (x: number, z: number) => [number, number] | null, detail: boolean, labels: Label[]): void {
  const cellPt = (c: number): [number, number] | null => tf((c % W) + 0.5, ((c / W) | 0) + 0.5);
  const poly = (cells: readonly number[], col: RGB, wd: number): void => {
    let prev: [number, number] | null = null;
    for (const c of cells) {
      const q = cellPt(c);
      if (q && prev && Math.abs(q[0] - prev[0]) < img.w / 2) img.line(prev[0], prev[1], q[0], q[1], col, wd);
      prev = q;
    }
  };
  const h = w.hydro;
  const thr = detail ? RIVER_FLOW * 1.5 : RIVER_FLOW * 8;
  for (let c = 0; c < N; c++) {
    if (!w.terrain.land[c] || h.flow[c]! < thr || h.lake[c]) continue;
    const r = h.recv[c]!;
    if (r < 0) continue;
    const a = cellPt(c), b = cellPt(r);
    if (a && b && Math.abs(a[0] - b[0]) < img.w / 2) img.line(a[0], a[1], b[0], b[1], [46, 98, 205], detail && h.flow[c]! > 3000 ? 2 : 1);
  }
  for (const d of w.dams) { const q = cellPt(d.cell); if (q) img.rect(q[0], q[1], 1, [255, 255, 255], [20, 20, 120]); }
  if (detail) for (const r of w.roads) if (r.cls === 'county') poly(r.cells, [120, 100, 80], 1);
  for (const l of w.rail.lines) poly(l.cells, l.kind === 'main' ? [15, 15, 15] : [80, 80, 80], detail && l.kind === 'main' ? 2 : 1);
  for (const r of w.roads) if (r.cls === 'highway') poly(r.cells, r.expressway !== undefined ? [230, 60, 30] : [240, 140, 40], detail ? 2 : 1);
  for (const s of w.sea.routes) poly(s.cells, s.kind === 'ferry' ? [200, 230, 255] : [255, 255, 255], 1);
  if (detail) {
    for (const p of w.hamlets) { const q = tf(p.x, p.z); if (q) img.set(q[0], q[1], [70, 50, 40]); }
    for (const p of w.towns) { const q = tf(p.x, p.z); if (q) img.rect(q[0], q[1], p.rank === 'major' ? 2 : p.rank === 'town' ? 1 : 0, [250, 250, 250], [20, 20, 20]); }
  } else for (const p of w.towns) if (p.rank === 'major') { const q = tf(p.x, p.z); if (q) img.set(q[0], q[1], [20, 20, 20]); }
  for (const p of w.hubs) {
    const q = tf(p.x, p.z);
    if (!q) continue;
    if (p.hub?.launch) img.ring(q[0], q[1], detail ? 8 : 6, [255, 215, 0]);
    img.rect(q[0], q[1], detail ? 4 : 3, HUB_FILL[p.type]!);
    labels.push({ x: Math.round(q[0]), y: Math.round(q[1]), text: detail ? `${p.name} (${p.type})` : '', launch: !!p.hub?.launch, type: p.type });
  }
}

export function renderSeed(seed: string, dir: string): Record<string, unknown> {
  const t0 = Date.now();
  const w = generateWorld(seed);
  const genMs = Date.now() - t0;
  const start = w.terrain.masses[w.startMass - 1]!;
  const roll = W / 2 - start.cx;
  const out: Record<string, unknown> = { seed, genMs, timing: w.timing };
  // planet at half resolution, rolled so the capital's continent is in the middle
  const S = 0.5;
  const planetL: Label[] = [];
  const planet = panel(w, 0, 0, W * S, H * S, S, roll, (c) => baseColour(w, c));
  overlays(w, planet, (x, z) => [(((x + roll) % W + W) % W) * S, z * S], false, planetL);
  planet.save(`${dir}/${seed}-planet.png`);
  // the capital's continent
  let xmin = Infinity, xmax = -Infinity, zmin = Infinity, zmax = -Infinity;
  for (let c = 0; c < N; c++) if (w.terrain.mass[c] === w.startMass) { const x = (((c % W) + roll) % W + W) % W, z = (c / W) | 0; xmin = Math.min(xmin, x); xmax = Math.max(xmax, x); zmin = Math.min(zmin, z); zmax = Math.max(zmax, z); }
  xmin -= 10; xmax += 10; zmin -= 10; zmax += 10;
  const ZW = 1600 - W * S - 24, ZH = H * S;
  const zs = Math.min(ZW / (xmax - xmin), ZH / (zmax - zmin));
  const zw = Math.round((xmax - xmin) * zs), zh = Math.round((zmax - zmin) * zs);
  const zoomL: Label[] = [];
  const zoom = panel(w, xmin, zmin, zw, zh, zs, roll, (c) => baseColour(w, c));
  overlays(w, zoom, (x, z) => { const rx = (((x + roll) % W) + W) % W; const px = (rx - xmin) * zs, py = (z - zmin) * zs; return px < -20 || px > zw + 20 ? null : [px, py]; }, true, zoomL);
  zoom.save(`${dir}/${seed}-zoom.png`);
  // climate panels: Koppen, January, July (a third of the planet's resolution)
  const S3 = 0.28;
  const pw = Math.round(W * S3), ph = Math.round(H * S3);
  panel(w, 0, 0, pw, ph, S3, roll, (c) => (w.terrain.land[c] ? KCOL[KOPPEN[w.climate.koppen[c]!]!]! : [235, 240, 246])).save(`${dir}/${seed}-koppen.png`);
  for (const [m, name] of [[0, 'jan'], [6, 'jul']] as const) panel(w, 0, 0, pw, ph, S3, roll, (c) => { const v = tempColour(w.climate.tempM[m * N + c]!); return w.terrain.land[c] ? v : [v[0] * 0.8 + 30, v[1] * 0.8 + 30, v[2] * 0.8 + 40]; }).save(`${dir}/${seed}-${name}.png`);
  void MONTHS; void koppenGroup; void wrapDx;
  // stats for the caption
  const conts = w.terrain.masses.filter((m) => m.kind === 'continent');
  const cross = w.sea.routes.filter((r) => r.kind !== 'ferry' && w.byId.get(r.a)!.mass !== w.byId.get(r.b)!.mass);
  const landPct = (100 * w.terrain.land.reduce((a, b) => a + b, 0)) / N;
  Object.assign(out, {
    planetLabels: planetL, zoomLabels: zoomL, zoomSize: [zw, zh], zoomScaleKm: 100 / CELL_KM * zs,
    continents: conts.length, start: `${start.longKm} x ${start.shortKm} km`, landPct: Math.round(landPct * 10) / 10,
    towns: w.towns.length, hamlets: w.hamlets.length, hubs: w.hubs.length,
    crossings: cross.length ? [Math.min(...cross.map((r) => r.sailHours)), Math.max(...cross.map((r) => r.sailHours))] : null,
  });
  return out;
}

if (process.argv[1]?.endsWith('world-map.ts')) {
  const [dir, ...seeds] = process.argv.slice(2);
  if (!dir || !seeds.length) { console.error('usage: world-map.ts <outdir> <seed>...'); process.exit(1); }
  mkdirSync(dir, { recursive: true });
  const all = seeds.map((s) => { console.error('seed', s); return renderSeed(s, dir); });
  writeFileSync(`${dir}/labels.json`, JSON.stringify({ seeds: all, koppen: KOPPEN_LEGEND, biomes: BCOL, hubs: HUB_FILL }));
  for (const a of all) console.log(JSON.stringify({ seed: a.seed, genMs: a.genMs, continents: a.continents, start: a.start, towns: a.towns, crossings: a.crossings }));
}
