// Maps of Highways v2 (src/world/gen/highways.ts, WORLD_GEN.md "Highways that evolve"): the capital's continent, a bypassed
// town and the capital's beltway, as relief pictures (PNG) and the road network by year (JSON: every segment's line and its
// state in each year, the interchanges, the route numbers); kotlin-street/tools/world-highways-sheet.py draws the roads
// on the relief and lays out the sheet.
// Usage: npx tsx kotlin-street/tools/world-highways-map.ts <outdir> [seed]
import { mkdirSync, writeFileSync } from 'fs';
import { PNG } from 'pngjs';
import { CELL_KM, H, W, distKm, wrapDx } from '../../src/world/gen/grid';
import { segAt, type Seg } from '../../src/world/gen/highways';
import { PLANET_SEED } from '../../src/world/gen/hubs';
import { generateWorld } from '../../src/world/gen/world';

const out = process.argv[2] ?? 'hwy-maps';
const seed = process.argv[3] ?? PLANET_SEED;
mkdirSync(out, { recursive: true });
const w = generateWorld(seed);
const hw = w.highways;
const t = w.terrain;
const YEARS = [1900, 1930, 1960, 1990, 2020];

/** A view: centre (cells), half extent (cells), pixel size. */
interface View { name: string; cx: number; cz: number; hx: number; hz: number; px: number; years: number[]; title: string }

function height(x: number, z: number): number {
  const u = x - 0.5, v = Math.max(0, Math.min(H - 1, z - 0.5));
  const i = Math.floor(u), j = Math.min(H - 2, Math.floor(v)), fu = u - i, fv = v - j;
  const at = (ii: number, jj: number): number => Math.max(0, t.elev[jj * W + (((ii % W) + W) % W)]!);
  const a = at(i, j), b = at(i + 1, j), c = at(i, j + 1), d = at(i + 1, j + 1);
  return a + (b - a) * fu + (c + (d - c) * fu - (a + (b - a) * fu)) * fv;
}

function relief(v: View): void {
  const pw = v.px, ph = Math.round((v.px * v.hz) / v.hx);
  const png = new PNG({ width: pw, height: ph });
  const sx = (2 * v.hx) / pw;
  for (let y = 0; y < ph; y++) for (let x = 0; x < pw; x++) {
    const cx = v.cx - v.hx + x * sx, cz = v.cz - v.hz + y * sx;
    const c = Math.floor(cz) * W + ((((Math.floor(cx)) % W) + W) % W);
    let col: [number, number, number];
    if (!t.land[c] || w.hydro.lake[c]) col = w.hydro.lake[c] ? [150, 182, 214] : [168, 196, 222];
    else {
      const e = height(cx, cz);
      const k = Math.min(1, e / 2600);
      col = k < 0.25 ? [204 - 40 * k, 222 - 30 * k, 180 - 50 * k] : k < 0.6 ? [214 - 30 * (k - 0.25), 206 - 60 * (k - 0.25), 160 - 30 * (k - 0.25)] : [200 + 40 * (k - 0.6), 186 + 50 * (k - 0.6), 170 + 60 * (k - 0.6)];
      const d = sx * 0.5;
      const gx = (height(cx + d, cz) - height(cx - d, cz)) / (2 * d * 1075.2), gz = (height(cx, cz + d) - height(cx, cz - d)) / (2 * d * 1075.2);
      const sh = Math.max(0.6, Math.min(1.25, 1 + (-gx - gz) * 6));
      col = col.map((q) => Math.max(0, Math.min(255, q * sh))) as [number, number, number];
    }
    const i = (y * pw + x) * 4;
    png.data[i] = col[0]; png.data[i + 1] = col[1]; png.data[i + 2] = col[2]; png.data[i + 3] = 255;
  }
  writeFileSync(`${out}/${v.name}.png`, PNG.sync.write(png));
}

const near = (v: View, s: Seg): boolean => s.path.some(([x, z]) => Math.abs(wrapDx(x - v.cx)) < v.hx * 1.1 && Math.abs(z - v.cz) < v.hz * 1.1);
function data(v: View): unknown {
  const segs = hw.segs.filter((s) => near(v, s)).map((s) => ({
    id: s.id, kind: s.kind, pts: s.path.map(([x, z]) => [Math.round((v.cx + wrapDx(x - v.cx)) * 1000) / 1000, Math.round(z * 1000) / 1000]),
    at: Object.fromEntries(v.years.map((y) => [y, segAt(s, y)])),
  }));
  const ics = hw.interchanges.filter((q) => Math.abs(wrapDx(q.x - v.cx)) < v.hx && Math.abs(q.z - v.cz) < v.hz).map((q) => ({ ...q, x: v.cx + wrapDx(q.x - v.cx) }));
  const places = w.places.filter((p) => p.kind !== 'hamlet' && Math.abs(wrapDx(p.x - v.cx)) < v.hx && Math.abs(p.z - v.cz) < v.hz).map((p) => ({ id: p.id, name: p.name, kind: p.kind, rank: p.rank, x: v.cx + wrapDx(p.x - v.cx), z: p.z, bypass: hw.towns.get(p.id)?.bypassed ?? null }));
  const sites = hw.sites.filter((q) => Math.abs(wrapDx(q.x - v.cx)) < v.hx && Math.abs(q.z - v.cz) < v.hz).map((q) => ({ ...q, x: v.cx + wrapDx(q.x - v.cx) }));
  return { ...v, segs, ics, places, sites };
}

// the capital's continent
const cap = w.hubs[0]!;
const mass = t.masses[cap.mass - 1]!;
const onMass = w.places.filter((p) => p.mass === cap.mass);
const xs = onMass.map((p) => wrapDx(p.x - cap.x)), zs = onMass.map((p) => p.z);
const cx = cap.x + (Math.max(...xs) + Math.min(...xs)) / 2, cz = (Math.max(...zs) + Math.min(...zs)) / 2;
const hx = (Math.max(...xs) - Math.min(...xs)) / 2 + 12, hz = (Math.max(...zs) - Math.min(...zs)) / 2 + 12;
const views: View[] = [{ name: 'continent', cx, cz, hx, hz, px: 900, years: YEARS, title: `the capital's continent (${Math.round(mass.longKm)} km)` }];
// a bypassed town (by the expressway, with an interchange), the biggest on the capital's continent
const byp = [...hw.towns.values()].filter((q) => q.bypassed?.by === 'expressway' && w.byId.get(q.place)!.mass === cap.mass)
  .map((q) => ({ q, p: w.byId.get(q.place)! })).sort((a, b) => (b.p.rank === 'major' ? 1 : 0) - (a.p.rank === 'major' ? 1 : 0) || distKm(a.p.x, a.p.z, cap.x, cap.z) - distKm(b.p.x, b.p.z, cap.x, cap.z))[0];
if (byp) views.push({ name: 'bypass', cx: byp.p.x, cz: byp.p.z, hx: 7 / CELL_KM, hz: 5 / CELL_KM, px: 900, years: [byp.q.bypassed!.year - 1, 1990], title: `${byp.p.name}: bypassed ${byp.q.bypassed!.year}, its interchange ${byp.q.interchangeKm} km off` });
views.push({ name: 'beltway', cx: cap.x, cz: cap.z, hx: 11 / CELL_KM, hz: 8 / CELL_KM, px: 900, years: [1990], title: 'the capital and its beltway, 1990' });
for (const v of views) relief(v);
writeFileSync(`${out}/hwy.json`, JSON.stringify({ seed, stats: hw.stats, views: views.map(data), routes: hw.routes.filter((r) => r.mass === cap.mass) }));
console.log(JSON.stringify({ views: views.map((v) => v.name), bypass: byp?.p.name }));
