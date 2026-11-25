// The second city's prototype as files (kotlin-street/docs/CITY2.md): its roads sampled, the ground as a height grid,
// the districts, landmarks, coast, river and rail, and each road's measures, for the map (tools/city2-map.py) and the
// drive check (desktop crossroadsTest City2DriveTest reads the loop from resources/city2/loop.json).
//
//   npx tsx kotlin-street/tools/city2-dump.ts <out dir> [--loop kotlin-street/desktop/src/crossroadsTest/resources/city2/loop.json]
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { corners, curvedShare, profileStats } from '../../src/town/city2/align';
import { roadGround } from '../../src/town/city2/ground';
import { buildRoads, JUNCTIONS, loopRoads, newTownGrid } from '../../src/town/city2/roads';
import { COAST, CREEKS, DISTRICTS, DUNES, LANDMARKS, MARSH, MOLES, PIER, RAIL, RIVER } from '../../src/town/city2/site';
import { groundHeight } from '../../src/town/city2/site';

const out = process.argv[2] ?? 'build/city2';
const li = process.argv.indexOf('--loop');
mkdirSync(out, { recursive: true });
const roads = buildRoads();
const nt = newTownGrid(undefined, DISTRICTS.find((d) => d.id === 'newtown')!.poly);

// the ground with its roads benched in: a 10 m grid over the map
const rg = roadGround(roads);
const X0 = -4500, Z0 = -2500, X1 = 2700, Z1 = 3100, G = 10;
const nx = Math.round((X1 - X0) / G) + 1, nz = Math.round((Z1 - Z0) / G) + 1;
const hf = new Float32Array(nx * nz);
for (let j = 0; j < nz; j++) for (let i = 0; i < nx; i++) hf[j * nx + i] = rg.height(X0 + i * G, Z0 + j * G);
writeFileSync(join(out, 'ground.f32'), Buffer.from(hf.buffer));

const r1 = (v: number): number => Math.round(v * 10) / 10;
const roadOut = roads.map(({ spec, a, spans }) => {
  const ps = profileStats(a);
  const every = 4;
  const pts = a.samples.filter((_, i) => i % every === 0 || i === a.samples.length - 1).map((q) => [r1(q.x), r1(q.z), r1(q.y), r1(groundHeight(q.x, q.z))]);
  return {
    id: spec.id, name: spec.name, cls: spec.cls, width: spec.width, built: spec.built, gone: spec.gone ?? null, loop: spec.loop ?? null,
    spans, eras: spec.eras, note: spec.note,
    length: Math.round(a.length), maxGrade: ps.maxGrade, minCrestR: ps.minCrestR, minSagR: ps.minSagR, climb: ps.climb, curved: curvedShare(a),
    corners: corners(a).map((c) => ({ r: Math.round(c.radius), deg: Math.round(c.sweepDeg), len: Math.round(c.len), s: Math.round(c.s0), kmh: Math.round(c.vKmh), bank: Math.round(c.bank * 1000) / 10 })),
    pts,
  };
});
writeFileSync(join(out, 'city2.json'), JSON.stringify({
  frame: { x0: X0, z0: Z0, x1: X1, z1: Z1, grid: G, nx, nz },
  roads: roadOut, streets: nt, junctions: JUNCTIONS, districts: DISTRICTS, landmarks: LANDMARKS, coast: COAST, river: RIVER, rail: RAIL, moles: MOLES, marsh: MARSH, creeks: CREEKS, dunes: DUNES, pier: PIER,
}));

// for the Filament preview (desktop -Dmode=filament-city2): every road every 2 m (x z y bank heading), its width and spans
writeFileSync(join(out, 'render.json'), JSON.stringify({
  roads: roads.map(({ spec, a, spans }) => ({
    id: spec.id, cls: spec.cls, width: spec.width, gone: spec.gone ?? null, spans,
    pts: a.samples.filter((_, i) => i % 2 === 0 || i === a.samples.length - 1).flatMap((q) => [r1(q.x * 10) / 10, r1(q.z * 10) / 10, Math.round(q.y * 100) / 100, Math.round(q.bank * 1000) / 1000, Math.round(q.h * 1e4) / 1e4]),
  })),
  moles: MOLES,
}));

for (const r of roadOut) {
  console.log(`${r.name.padEnd(18)} ${String(r.length).padStart(5)} m  grade ${(r.maxGrade * 100).toFixed(1)}%  crest R ${isFinite(r.minCrestR) ? Math.round(r.minCrestR) : '-'}  sag R ${isFinite(r.minSagR) ? Math.round(r.minSagR) : '-'}  curved ${(r.curved * 100).toFixed(0)}%  corners ${r.corners.map((c) => `R${c.r}/${c.deg}deg@${c.kmh}`).join(' ')}`);
}
console.log(`New Town streets: ${nt.length}`);

// the loop for the drive check: every 2 m, x z y bank (dy/m to the right), the road it is on
if (li > 0) {
  const loop = loopRoads(roads);
  const pts: number[] = [];
  const marks: { id: string; name: string; s: number; width: number; spans: { from: number; to: number; kind: string }[] }[] = [];
  const cornerMarks: { road: string; from: number; to: number; r: number; deg: number; kmh: number }[] = [];
  let s0 = 0;
  for (const { spec, a, spans } of loop) {
    marks.push({ id: spec.id, name: spec.name, s: Math.round(s0), width: spec.width, spans: spans.map((sp) => ({ from: Math.round(s0 + sp.from), to: Math.round(s0 + sp.to), kind: sp.kind })) });
    for (const c of corners(a)) if (c.radius < 1000) cornerMarks.push({ road: spec.name, from: Math.round(s0 + c.s0), to: Math.round(s0 + c.s0 + c.len), r: Math.round(c.radius), deg: Math.round(c.sweepDeg), kmh: Math.round(c.vKmh) });
    a.samples.forEach((q, i) => {
      if (i % 2 !== 0 && i !== a.samples.length - 1) return;
      if (i === 0 && pts.length) return;
      pts.push(Math.round(q.x * 100) / 100, Math.round(q.z * 100) / 100, Math.round(q.y * 100) / 100, Math.round(q.bank * 1000) / 1000);
    });
    s0 += a.length;
  }
  // (the loop closes on its first point: drop the repeat)
  pts.length -= 4;
  // the junctions have no fillet in the data (a road ends where the next begins, at an angle): the line through each is
  // rounded here, as a driver turns at a junction (kerb radius 15 m; a roundabout's own radius), heights eased across
  const n = pts.length / 4;
  const P = (i: number): number => ((i % n) + n) % n;
  for (const { spec } of loop) {
    const b = Math.round(marks.find((m) => m.id === spec.id)!.s / 2);
    const j = JUNCTIONS[spec.ends[0]]!;
    const head = (i0: number, i1: number): number => Math.atan2(pts[P(i1) * 4 + 1]! - pts[P(i0) * 4 + 1]!, pts[P(i1) * 4]! - pts[P(i0) * 4]!);
    let d = head(b, b + 3) - head(b - 3, b);
    while (d > Math.PI) d -= 2 * Math.PI;
    while (d < -Math.PI) d += 2 * Math.PI;
    if (Math.abs(d) < (10 * Math.PI) / 180) continue;
    const R = j.kind === 'roundabout' ? j.r! : 15;
    const m = Math.ceil((R * Math.tan(Math.abs(d) / 2)) / 2);
    const a0 = P(b - m), a1 = P(b + m), v = P(b);
    const ax = pts[a0 * 4]!, az = pts[a0 * 4 + 1]!, ay = pts[a0 * 4 + 2]!, vx = pts[v * 4]!, vz = pts[v * 4 + 1]!, bx = pts[a1 * 4]!, bz = pts[a1 * 4 + 1]!, by = pts[a1 * 4 + 2]!;
    for (let k = 1; k < 2 * m; k++) {
      const t = k / (2 * m), u = 1 - t, i = P(b - m + k);
      pts[i * 4] = Math.round((u * u * ax + 2 * u * t * vx + t * t * bx) * 100) / 100;
      pts[i * 4 + 1] = Math.round((u * u * az + 2 * u * t * vz + t * t * bz) * 100) / 100;
      pts[i * 4 + 2] = Math.round((ay + (by - ay) * t) * 100) / 100;
      pts[i * 4 + 3] = 0;
    }
  }
  const file = process.argv[li + 1]!;
  mkdirSync(join(file, '..'), { recursive: true });
  // the ground beside the roads (with the roads benched in) on a 20 m grid, decimetres as 16-bit integers (little-endian)
  const g20 = { x0: X0, z0: Z0, cell: 20, nx: Math.round((X1 - X0) / 20) + 1, nz: Math.round((Z1 - Z0) / 20) + 1 };
  const gb = Buffer.alloc(g20.nx * g20.nz * 2);
  for (let j = 0; j < g20.nz; j++) for (let i = 0; i < g20.nx; i++) gb.writeInt16LE(Math.round(rg.height(X0 + i * 20, Z0 + j * 20) * 10), (j * g20.nx + i) * 2);
  writeFileSync(join(file, '..', 'ground20.bin'), gb);
  writeFileSync(file, JSON.stringify({ note: 'kotlin-street/tools/city2-dump.ts: the second city\'s loop, x z y bank every 2 m; ground20.bin beside it', length: Math.round(s0), ground: g20, roads: marks, corners: cornerMarks, pts }));
  console.log(`loop ${Math.round(s0)} m -> ${file}`);
}
