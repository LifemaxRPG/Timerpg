// Maps of the grand mountains, the natural wonders and the winding roads (WORLD_GEN.md "Grand mountains", "Natural
// wonders", "Winding roads"): for a seed, (1) the planet's height with the great ranges and their highest peaks marked,
// (2) the wonders by kind (colour) and tier (size and ring), (3) a close-up of the most switchbacked road up to a
// mountain village, on the near terrain's heights. PNGs and a labels.json; tools/world-mountains-sheet.py lays them out.
// Usage: npx tsx kotlin-street/tools/world-mountains-map.ts <outdir> [seed]
import { mkdirSync, writeFileSync } from 'fs';
import { PNG } from 'pngjs';
import { CELL_M, H, N, W, wrapDx } from '../../src/world/gen/grid';
import { NearTerrain } from '../../src/world/gen/near';
import { PLANET_SEED } from '../../src/world/gen/hubs';
import { generateWorld } from '../../src/world/gen/world';
import type { WonderKind } from '../../src/world/gen/wonders';

type RGB = [number, number, number];
const out = process.argv[2] ?? 'maps';
const seed = process.argv[3] ?? PLANET_SEED;
mkdirSync(out, { recursive: true });
const w = generateWorld(seed);
const t = w.terrain;

class Img {
  readonly d: Uint8Array;
  constructor(readonly w: number, readonly h: number, bg: RGB = [255, 255, 255]) { this.d = new Uint8Array(w * h * 3); for (let i = 0; i < w * h; i++) this.d.set(bg, i * 3); }
  set(x: number, y: number, c: RGB): void { x = Math.round(x); y = Math.round(y); if (x < 0 || y < 0 || x >= this.w || y >= this.h) return; this.d.set(c, (y * this.w + x) * 3); }
  disc(x: number, y: number, r: number, c: RGB): void { for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) if (dx * dx + dy * dy <= r * r) this.set(x + dx, y + dy, c); }
  ring(x: number, y: number, r: number, c: RGB): void { for (let a = 0; a < 96; a++) { const q = (a / 96) * Math.PI * 2; this.set(x + Math.cos(q) * r, y + Math.sin(q) * r, c); } }
  line(x0: number, y0: number, x1: number, y1: number, c: RGB, wd = 1): void {
    const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0)));
    for (let k = 0; k <= n; k++) { const x = x0 + ((x1 - x0) * k) / n, y = y0 + ((y1 - y0) * k) / n; for (let dy = 0; dy < wd; dy++) for (let dx = 0; dx < wd; dx++) this.set(x + dx - (wd >> 1), y + dy - (wd >> 1), c); }
  }
  save(path: string): void { const p = new PNG({ width: this.w, height: this.h }); for (let i = 0; i < this.w * this.h; i++) { p.data[i * 4] = this.d[i * 3]!; p.data[i * 4 + 1] = this.d[i * 3 + 1]!; p.data[i * 4 + 2] = this.d[i * 3 + 2]!; p.data[i * 4 + 3] = 255; } writeFileSync(path, PNG.sync.write(p)); }
}

/** Hypsometric tint: green lowland through brown to grey rock and white snow; sea by depth. */
function tint(e: number, snow: boolean): RGB {
  if (e <= 0) { const k = Math.min(1, -e / 4000); return [Math.round(70 - 40 * k), Math.round(120 - 60 * k), Math.round(170 - 50 * k)]; }
  if (snow) return [240, 244, 250];
  const stops: [number, RGB][] = [[0, [96, 150, 82]], [400, [150, 170, 96]], [1000, [176, 156, 104]], [2000, [150, 112, 82]], [3200, [128, 116, 110]], [4500, [170, 166, 164]], [7000, [210, 210, 214]]];
  for (let k = 1; k < stops.length; k++) if (e <= stops[k]![0]) {
    const [e0, c0] = stops[k - 1]!, [e1, c1] = stops[k]!, f = (e - e0) / (e1 - e0);
    return [0, 1, 2].map((q) => Math.round(c0[q]! + (c1[q]! - c0[q]!) * f)) as RGB;
  }
  return [210, 210, 214];
}
const shade = (c: RGB, s: number): RGB => c.map((v) => Math.max(0, Math.min(255, Math.round(v * s)))) as RGB;

const labels: Record<string, unknown> = { seed };

// ---- 1. the planet's height, the great ranges outlined and their peaks marked (2 x 2 cells a pixel)
{
  const S = 2, img = new Img(W / S, H / S);
  for (let y = 0; y < H / S; y++) for (let x = 0; x < W / S; x++) {
    const c = y * S * W + x * S, e = t.elev[c]!;
    const ex = t.elev[y * S * W + ((x * S + 2) % W)]! - t.elev[y * S * W + ((x * S - 2 + W) % W)]!;
    const ez = t.elev[Math.min(H - 1, y * S + 2) * W + x * S]! - t.elev[Math.max(0, y * S - 2) * W + x * S]!;
    const lit = e > 0 ? 1 + Math.max(-0.45, Math.min(0.45, (-ex - ez) / 2500)) : 1;
    img.set(x, y, shade(tint(e, t.land[c] === 1 && e > w.climate.snowline[c]!), lit));
  }
  // the great ranges' outline (their own term over 1,500 m)
  for (let c = 0; c < N; c++) {
    const k = t.range[c]!;
    if (k < 0 || t.rangeCls[k] !== 3 || t.mount[c]! < 1500 || !t.land[c]) continue;
    const r = c + 1, d = c + W;
    const edge = (q: number): boolean => q >= N || t.range[q] !== k || t.mount[q]! < 1500 || !t.land[q];
    if (edge(r) || edge(d) || edge(c - 1) || edge(c - W)) img.set((c % W) / S, Math.floor(c / W) / S, [220, 30, 30]);
  }
  const peaks = t.ranges.filter((r) => r.cls !== 'modest').map((r) => ({ id: r.id, cls: r.cls, peakM: r.peakM, x: ((r.peakCell % W) + 0.5) / S, y: (Math.floor(r.peakCell / W) + 0.5) / S }));
  for (const p of peaks) if (p.cls === 'great') { img.disc(p.x, p.y, 3, [220, 30, 30]); img.ring(p.x, p.y, 6, [255, 255, 255]); } else img.disc(p.x, p.y, 2, [120, 40, 140]);
  const cap = w.hubs[0]!;
  img.disc(cap.x / S, cap.z / S, 3, [255, 220, 0]); img.ring(cap.x / S, cap.z / S, 5, [0, 0, 0]);
  img.save(`${out}/planet_height.png`);
  labels.planet = { scale: S, peaks, capital: { x: cap.x / S, y: cap.z / S } };
}

// ---- 2. the wonders by kind and tier, on a pale planet
const KIND_COL: Record<WonderKind, RGB> = {
  'blossom valley': [236, 100, 170], meadow: [150, 200, 60], superbloom: [250, 170, 40], waterfall: [40, 140, 240], canyon: [200, 80, 40], arches: [230, 120, 70],
  karst: [40, 160, 100], 'table mountain': [20, 110, 60], volcano: [200, 20, 20], 'hot springs': [255, 120, 120], glacier: [120, 210, 240], fjord: [30, 60, 160],
  'giant trees': [20, 90, 40], 'salt flat': [245, 245, 245], 'sea stacks': [110, 110, 120], atoll: [60, 220, 210], 'glowing bay': [90, 90, 255], fireflies: [230, 230, 60],
  aurora: [120, 255, 160], cave: [90, 60, 40],
};
{
  const S = 2, img = new Img(W / S, H / S);
  for (let y = 0; y < H / S; y++) for (let x = 0; x < W / S; x++) {
    const c = y * S * W + x * S;
    img.set(x, y, t.land[c] ? shade(tint(t.elev[c]!, false), 0.55) .map((v) => Math.round(v * 0.5 + 110)) as RGB : [205, 214, 226]);
  }
  const order = { common: 0, uncommon: 1, rare: 2, legendary: 3 };
  for (const q of [...w.wonders].sort((a, b) => order[a.tier] - order[b.tier])) {
    const x = q.x / S, y = q.z / S, col = KIND_COL[q.kind];
    if (q.tier === 'common') img.disc(x, y, 1, col);
    else if (q.tier === 'uncommon') img.disc(x, y, 2, col);
    else if (q.tier === 'rare') { img.disc(x, y, 4, [0, 0, 0]); img.disc(x, y, 3, col); }
    else { img.disc(x, y, 7, [255, 215, 0]); img.disc(x, y, 5, col); img.ring(x, y, 9, [0, 0, 0]); }
  }
  img.save(`${out}/wonders.png`);
  const counts: Record<string, Record<string, number>> = {};
  for (const q of w.wonders) { counts[q.kind] ??= {}; counts[q.kind]![q.tier] = (counts[q.kind]![q.tier] ?? 0) + 1; }
  labels.wonders = { scale: S, colours: KIND_COL, counts, legendary: w.wonders.filter((q) => q.tier === 'legendary').map((q) => ({ name: q.name, kind: q.kind, x: q.x / S, y: q.z / S })) };
}

// ---- 3. a close-up: the road with the most hairpins up to a mountain village, on the near terrain's heights
{
  const road = [...w.roads].filter((r) => r.cls === 'county' && w.byId.get(r.a)?.setting).sort((a, b) => (b.switchbacks ?? 0) - (a.switchbacks ?? 0))[0]!;
  const village = w.byId.get(road.a)!;
  const nt = new NearTerrain(seed, Int16Array.from(t.elev, (e) => Math.round(e)));
  const R = 7.5; // km half-width round the village
  const PX = 600, mPer = (2 * R * 1000) / PX;
  const img = new Img(PX, PX);
  const toCell = (px: number, py: number): [number, number] => [village.x + ((px - PX / 2) * mPer) / CELL_M, village.z + ((py - PX / 2) * mPer) / CELL_M];
  const hs = new Float64Array(PX * PX);
  for (let py = 0; py < PX; py++) for (let px = 0; px < PX; px++) { const [x, z] = toCell(px, py); hs[py * PX + px] = nt.smooth(x, z); }
  let lo = Infinity, hi = -Infinity;
  for (const v of hs) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
  for (let py = 1; py < PX - 1; py++) for (let px = 1; px < PX - 1; px++) {
    const e = hs[py * PX + px]!, dx = hs[py * PX + px + 1]! - hs[py * PX + px - 1]!, dz = hs[(py + 1) * PX + px]! - hs[(py - 1) * PX + px]!;
    const [x, z] = toCell(px, py);
    const cell = Math.floor(z) * W + ((Math.floor(x) % W) + W) % W;
    const lit = 1 + Math.max(-0.5, Math.min(0.5, (-dx - dz) / (4 * mPer)));
    let c = shade(tint(e, e > w.climate.snowline[cell]!), lit);
    // contours every 100 m
    if (Math.floor(e / 100) !== Math.floor(hs[py * PX + px + 1]! / 100) || Math.floor(e / 100) !== Math.floor(hs[(py + 1) * PX + px]! / 100)) c = shade(c, 0.75);
    img.set(px, py, c);
  }
  const toPx = (x: number, z: number): [number, number] => [PX / 2 + (wrapDx(x - village.x) * CELL_M) / mPer, PX / 2 + ((z - village.z) * CELL_M) / mPer];
  for (const r of w.roads) {
    const line = r.path ?? [];
    for (let q = 1; q < line.length; q++) {
      const [ax, ay] = toPx(line[q - 1]![0], line[q - 1]![1]), [bx, by] = toPx(line[q]![0], line[q]![1]);
      if (Math.max(ax, bx) < -50 || Math.min(ax, bx) > PX + 50 || Math.max(ay, by) < -50 || Math.min(ay, by) > PX + 50) continue;
      img.line(ax, ay, bx, by, r.cls === 'highway' ? [40, 40, 40] : [250, 250, 240], r === road ? 3 : 2);
    }
  }
  // the rivers
  for (const rv of w.hydro.rivers) for (let q = 1; q < rv.cells.length; q++) {
    const a = rv.cells[q - 1]!, b = rv.cells[q]!;
    const [ax, ay] = toPx((a % W) + 0.5, Math.floor(a / W) + 0.5), [bx, by] = toPx((b % W) + 0.5, Math.floor(b / W) + 0.5);
    if (Math.min(ax, bx) > PX || Math.max(ax, bx) < 0 || Math.min(ay, by) > PX || Math.max(ay, by) < 0) continue;
    img.line(ax, ay, bx, by, [50, 110, 220], 2);
  }
  const places = w.places.filter((p) => Math.abs(wrapDx(p.x - village.x)) * CELL_M < R * 1000 && Math.abs(p.z - village.z) * CELL_M < R * 1000);
  for (const p of places) { const [x, y] = toPx(p.x, p.z); img.disc(x, y, 6, [0, 0, 0]); img.disc(x, y, 4, p.setting ? [255, 80, 40] : [255, 220, 0]); }
  const wons = w.wonders.filter((q) => Math.abs(wrapDx(q.x - village.x)) * CELL_M < R * 1000 && Math.abs(q.z - village.z) * CELL_M < R * 1000);
  for (const q of wons) { const [x, y] = toPx(q.x, q.z); img.disc(x, y, 5, KIND_COL[q.kind]); img.ring(x, y, 7, [0, 0, 0]); }
  img.save(`${out}/road_closeup.png`);
  const climb = Math.max(...road.cells.map((c) => t.elev[c]!)) - Math.min(...road.cells.map((c) => t.elev[c]!));
  labels.closeup = {
    road: road.id, km: road.km, switchbacks: road.switchbacks, maxGrade: road.maxGrade, climbM: Math.round(climb), village: village.name, setting: village.setting, ore: village.ore ?? null,
    villageM: Math.round(t.elev[village.cell]!), widthKm: 2 * R, lo: Math.round(lo), hi: Math.round(hi),
    places: places.map((p) => { const [x, y] = toPx(p.x, p.z); return { name: p.name, setting: p.setting ?? null, x, y }; }),
    wonders: wons.map((q) => { const [x, y] = toPx(q.x, q.z); return { name: q.name, kind: q.kind, x, y }; }),
  };
}
writeFileSync(`${out}/labels.json`, JSON.stringify(labels, null, 1));
console.log(JSON.stringify({ wonders: w.wonders.length, ranges: t.ranges.filter((r) => r.cls === 'great').map((r) => [r.id, r.peakM]), closeup: labels.closeup }));
