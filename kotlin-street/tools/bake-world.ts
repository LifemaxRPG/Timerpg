// Bakes the world skeleton of a seed (src/world/gen, WORLD_GEN.md) to content/world/<seed>/skeleton.twsk.gz, the asset
// W4's Kotlin reader loads (format: src/world/gen/bake.ts). Prints the sizes and the generation time.
// Usage: npx tsx kotlin-street/tools/bake-world.ts [seed] [outdir]
import { mkdirSync, writeFileSync } from 'fs';
import { gzipSync } from 'zlib';
import { encodeSkeleton } from '../../src/world/gen/bake';
import { generateWorld } from '../../src/world/gen/world';
import { PLANET_SEED } from '../../src/world/gen/hubs';

const seed = process.argv[2] ?? PLANET_SEED;
const dir = process.argv[3] ?? `content/world/${seed}`;
const t0 = Date.now();
const w = generateWorld(seed);
const gen = Date.now() - t0;
const raw = encodeSkeleton(w);
const gz = gzipSync(raw, { level: 9 });
mkdirSync(dir, { recursive: true });
writeFileSync(`${dir}/skeleton.twsk.gz`, gz);
console.log(JSON.stringify({ seed, generateMs: gen, stages: w.timing, rawBytes: raw.length, gzBytes: gz.length, places: w.places.length, roads: w.roads.length, railLinks: w.rail.links.length, seaRoutes: w.sea.routes.length }));
