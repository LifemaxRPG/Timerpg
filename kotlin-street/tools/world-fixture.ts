// The cross-language fixture for W4's Kotlin skeleton reader (WORLD_GEN.md "The generator", phase W4): the integer-hashed
// noise (src/world/gen/noise.ts) at sample points, and facts of the baked skeleton (grids at sample cells, the capital,
// its pins, the bent river's path near it, a few roads' and rail lines' ends) as the TypeScript bake made them. The Kotlin
// test (desktop/src/crossroadsTest/kotlin/WorldSkeletonTest.kt) reads the same skeleton and must give the same values.
// Usage: npx tsx kotlin-street/tools/world-fixture.ts [seed] [out.json]
import { readFileSync, writeFileSync } from 'fs';
import { gunzipSync } from 'zlib';
import { PTS_SCALE, decodeSkeleton } from '../../src/world/gen/bake';
import { N, W } from '../../src/world/gen/grid';
import { PLANET_SEED } from '../../src/world/gen/hubs';
import { Fbm, Lattice, hash3, hashStr, hashU } from '../../src/world/gen/noise';
import { NearTerrain } from '../../src/world/gen/near';

const seed = process.argv[2] ?? PLANET_SEED;
const outPath = process.argv[3] ?? 'kotlin-street/desktop/src/crossroadsTest/resources/world/fixture.json';
const sk = decodeSkeleton(gunzipSync(readFileSync(`content/world/${seed}/skeleton.twsk.gz`)));
const strs = ['', 'a', seed, `${seed}|noise|hills0|16`, 'Cedar Falls', 'ünïcødé ✓ 漢字', 'x'.repeat(300)];
const h3: number[][] = [];
for (const [s, a, b, c] of [[0, 0, 0, 0], [1, 2, 3, 4], [4294967295, -1, -7, 123456], [2654435769, 1855, 927, 0], [hashStr(seed), 99999, -99999, 31]] as const) h3.push([s, a, b, c, hash3(s, a, b, c), hashU(s, a, b, c)]);
const pts: [number, number][] = [[0, 0], [0.5, 0.5], [935.661, 277.5], [1855.999, 927.9], [-3.25, 10.75], [2000.125, 400.5], [17.3, -2.0], [1200.0001, 930.4], [463.77, 211.12]];
const lat = new Lattice(seed, 'test', 64), fbm = new Fbm(seed, 'hills', 16, 7), fbm2 = new Fbm(seed, 'w4-detail', W / 2, 3);
const noise = pts.map(([x, z]) => ({ x, z, lattice: lat.at(x, z), hills: fbm.at(x, z), detail: fbm2.at(x, z) }));
const grids = ['elev', 'flags', 'biome', 'koppen', 'crop', 'flow', 'steady', 'mass', 'lake', 'snowline'];
const cells: number[] = [];
for (let c = 7; c < N; c += 104729) cells.push(c);
const json = sk.json as Record<string, any>;
const cap = json.places[0];
cells.push(Math.floor(cap.z) * W + Math.floor(cap.x));
const samples = cells.map((c) => ({ cell: c, ...Object.fromEntries(grids.map((g) => [g, sk.section(g)[c]])) }));
const P = sk.section('pts') as Int32Array;
const path = (ref: [number, number]): number[][] => Array.from({ length: ref[1] }, (_, i) => [P[(ref[0] + i) * 2]! / PTS_SCALE, P[(ref[0] + i) * 2 + 1]! / PTS_SCALE]);
const capRiver = json.rivers.findIndex((r: any) => path(r.path).some(([x, z]) => Math.hypot(x - cap.x, z - cap.z) < 0.5));
const fixture = {
  seed, version: json.version,
  hashStr: strs.map((s) => [s, hashStr(s)]), hash3: h3, noise, samples,
  counts: { places: json.places.length, roads: json.roads.length, railLines: json.railLines.length, rivers: json.rivers.length, sea: json.sea.length },
  capital: { id: cap.id, name: cap.name, x: cap.x, z: cap.z, plan: cap.plan, pins: cap.pins, planTiles: cap.planTiles },
  capRiver: { index: capRiver, path: path(json.rivers[capRiver].path) },
  roads: json.roads.slice(0, 5).map((r: any) => ({ id: r.id, path: path(r.path) })),
  rail: json.railLines.filter((l: any) => l.stations.includes(cap.id)).map((l: any) => ({ id: l.id, path: path(l.path) })),
  near: nearSamples(),
};
// W5a: the near terrain's heights (src/world/gen/near.ts) at tile indices round the capital (a tile is 2.1 m; cells
// are tile * 2.1 / 1075.2), and at the steepest of a few hundred points within 8 km (a cliff, to test the terracing)
function nearSamples() {
  const nt = new NearTerrain(seed, sk.elev);
  const tx = Math.floor((cap.x * 1075.2) / 2.1), tz = Math.floor((cap.z * 1075.2) / 2.1);
  const pts: [number, number][] = [[2400, 1450], [-2900, 960], [1430, -2860], [37, 3333], [-3100, -2500], [2950, 2980], [-1801, 2777]].map(([a, b]) => [tx + a!, tz + b!]);
  let best: [number, number] = pts[0]!, bestC = -1;
  for (let k = 0; k < 600; k++) {
    const a = tx + Math.floor((hashU(7, k, 1) - 0.5) * 7600), b = tz + Math.floor((hashU(7, k, 2) - 0.5) * 7600);
    const c = nt.cliff((a * 2.1) / 1075.2, (b * 2.1) / 1075.2);
    if (c > bestC) { bestC = c; best = [a, b]; }
  }
  // (and its neighbours a tile apart: the ledges and risers)
  for (let d = 0; d < 6; d++) pts.push([best[0] + d, best[1]]);
  return pts.map(([gx, gz]) => {
    const cx = (gx * 2.1) / 1075.2, cz = (gz * 2.1) / 1075.2;
    return { gx, gz, smooth: nt.smooth(cx, cz), slope: nt.slope(cx, cz), raw: nt.raw(cx, cz) };
  });
}

writeFileSync(outPath, JSON.stringify(fixture, null, 1));
console.log(`wrote ${outPath}: ${samples.length} cells, river ${capRiver} (${fixture.capRiver.path.length} points)`);
