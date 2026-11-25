// The second city's prototype as files (kotlin-street/docs/CITY2.md): its roads sampled, the ground as a height grid,
// the districts, landmarks, coast, river and rail, and each road's measures, for the map (tools/city2-map.py) and the
// drive check (desktop crossroadsTest City2DriveTest reads the loop from resources/city2/loop.json).
//
//   npx tsx kotlin-street/tools/city2-dump.ts <out dir> [--loop kotlin-street/desktop/src/crossroadsTest/resources/city2/loop.json]
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { corners, curvedShare, profileStats } from '../../src/town/city2/align';
import { buildRoads, JUNCTIONS, loopRoads, newTownGrid } from '../../src/town/city2/roads';
import { COAST, DISTRICTS, groundHeight, LANDMARKS, RAIL, RIVER } from '../../src/town/city2/site';

const out = process.argv[2] ?? 'build/city2';
const li = process.argv.indexOf('--loop');
mkdirSync(out, { recursive: true });
const roads = buildRoads();
const nt = newTownGrid(undefined, DISTRICTS.find((d) => d.id === 'newtown')!.poly);

// the ground: a 12 m grid over the map
const X0 = -3500, Z0 = -2500, X1 = 2700, Z1 = 3100, G = 10;
const nx = Math.round((X1 - X0) / G) + 1, nz = Math.round((Z1 - Z0) / G) + 1;
const hf = new Float32Array(nx * nz);
for (let j = 0; j < nz; j++) for (let i = 0; i < nx; i++) hf[j * nx + i] = groundHeight(X0 + i * G, Z0 + j * G);
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
  roads: roadOut, streets: nt, junctions: JUNCTIONS, districts: DISTRICTS, landmarks: LANDMARKS, coast: COAST, river: RIVER, rail: RAIL,
}));

for (const r of roadOut) {
  console.log(`${r.name.padEnd(18)} ${String(r.length).padStart(5)} m  grade ${(r.maxGrade * 100).toFixed(1)}%  crest R ${isFinite(r.minCrestR) ? Math.round(r.minCrestR) : '-'}  sag R ${isFinite(r.minSagR) ? Math.round(r.minSagR) : '-'}  curved ${(r.curved * 100).toFixed(0)}%  corners ${r.corners.map((c) => `R${c.r}/${c.deg}deg@${c.kmh}`).join(' ')}`);
}
console.log(`New Town streets: ${nt.length}`);

// the loop for the drive check: every 2 m, x z y bank (dy/m to the right), the road it is on
if (li > 0) {
  const loop = loopRoads(roads);
  const pts: number[] = [];
  const marks: { id: string; name: string; s: number }[] = [];
  let s0 = 0;
  for (const { spec, a } of loop) {
    marks.push({ id: spec.id, name: spec.name, s: Math.round(s0) });
    a.samples.forEach((q, i) => {
      if (i % 2 !== 0 && i !== a.samples.length - 1) return;
      if (i === 0 && pts.length) return;
      pts.push(r1(q.x * 10) / 10, r1(q.z * 10) / 10, Math.round(q.y * 100) / 100, Math.round(q.bank * 1000) / 1000);
    });
    s0 += a.length;
  }
  const file = process.argv[li + 1]!;
  mkdirSync(join(file, '..'), { recursive: true });
  writeFileSync(file, JSON.stringify({ note: 'kotlin-street/tools/city2-dump.ts: the second city\'s loop, x z y bank every 2 m', length: Math.round(s0), roads: marks, pts }));
  console.log(`loop ${Math.round(s0)} m -> ${file}`);
}
