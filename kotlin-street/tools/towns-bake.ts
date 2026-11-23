// THE TOWN BAKER (kotlin-street/docs/TOWNS_EVERYWHERE.md): every place on the planet gets its own walkable town, baked
// offline into one packed file per town plus a manifest, for the app to stream in from storage when the player comes near.
//
//   npx tsx kotlin-street/tools/towns-bake.ts [--world alpha] [--out kotlin-street/build-towns/<world>] [--jobs N]
//       [--sample 30 | --places t:12,m:40 | --all] [--keep] [--resume]
//
// The main process generates the world once (src/world/gen, ~20 s), writes each chosen place's site (tools/towns-site.ts:
// its facts, the rivers, roads and rail near it, the height round it) and runs a pool of workers (one process per town,
// --jobs at a time, every core by default). A worker (`--one <site.json> <out>`) makes the town's recipe, plans the town,
// places it on the planet, fixes the roads in, and exports the town in the app's formats (street.json v2 with the yards and
// farms, dialogue.json v2, interiors.json, the buildings' pictures from make_buildings.py), then packs them into
// <out>/towns/<id>.ttwn and writes its manifest row to <out>/rows/<id>.json. The main process writes <out>/manifest.json
// (every place: the baked ones with their file, the hand-made ones with their folder, the rest with their recipe) and
// <out>/summary.json (time and size by rank).
//
// Hand-made towns replace generated ones by one entry in tools/towns-handmade.json (place id -> the town's folder): such
// a place is never baked, and its manifest row points at the hand-made folder (the capital: assets/town/gamma).
//
// The output is a build artifact (kotlin-street/build-towns/ is ignored by git).
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { cpus } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync, gunzipSync, gzipSync, inflateRawSync } from 'node:zlib';
import { useRecipe, SIZE, PEOPLE, type TownRecipe } from '../../src/town/recipe';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const argv = process.argv.slice(2);
const flag = (f: string): string | undefined => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : undefined; };
/** A place id as a file name. */
export const fileId = (id: string): string => id.replace(/[^A-Za-z0-9]+/g, '-');

// ------------------------------------------------------------------------------------------------ the pack format
// .ttwn: "TTWN", a version byte, the header's length (u32 LE), the header (UTF-8 JSON: { id, files: [{ path, offset,
// size, raw, enc }] }, offsets from the end of the header), then the files. JSON is deflated (raw deflate, level 9; the
// app inflates it with java.util.zip.Inflater(nowrap)); the pictures are PNGs already and are stored as they are. Files
// in path order, so the same files give the same bytes.
export const PACK_VERSION = 1;
export function pack(id: string, files: { path: string; data: Buffer }[]): Buffer {
  const sorted = [...files].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  const blobs: Buffer[] = [];
  const entries: { path: string; offset: number; size: number; raw: number; enc: 'deflate' | 'store' }[] = [];
  let off = 0;
  for (const f of sorted) {
    const z = f.path.endsWith('.json');
    const b = z ? deflateRawSync(f.data, { level: 9 }) : f.data;
    entries.push({ path: f.path, offset: off, size: b.length, raw: f.data.length, enc: z ? 'deflate' : 'store' });
    blobs.push(b);
    off += b.length;
  }
  const head = Buffer.from(JSON.stringify({ id, files: entries }), 'utf8');
  const pre = Buffer.alloc(9);
  pre.write('TTWN', 0, 'ascii'); pre.writeUInt8(PACK_VERSION, 4); pre.writeUInt32LE(head.length, 5);
  return Buffer.concat([pre, head, ...blobs]);
}
/** The files of a .ttwn (raw bytes, inflated). */
export function unpack(buf: Buffer): { id: string; files: Map<string, Buffer> } {
  if (buf.toString('ascii', 0, 4) !== 'TTWN') throw new Error('not a town pack');
  const n = buf.readUInt32LE(5);
  const head = JSON.parse(buf.toString('utf8', 9, 9 + n)) as { id: string; files: { path: string; offset: number; size: number; enc: string }[] };
  const base = 9 + n;
  const files = new Map<string, Buffer>();
  for (const f of head.files) {
    const b = buf.subarray(base + f.offset, base + f.offset + f.size);
    files.set(f.path, f.enc === 'deflate' ? inflateRawSync(b) : Buffer.from(b));
  }
  return { id: head.id, files };
}

// ------------------------------------------------------------------------------------------------ one town (a worker)
export interface Row {
  id: string; name: string; rank: string; type: string; setting?: string; ore?: string; seed: string;
  /** The place's point (atlas cell units) and the town's frame on it (towns-site.ts: a turn in degrees, a plan point on a world point, m from the place). */
  at: [number, number];
  frame?: { deg: number; plan: [number, number]; world: [number, number] };
  /** The town's ground in its own frame: plan tiles (the plan and the farm ring). */
  ground?: { x0: number; y0: number; x1: number; y1: number };
  /** The built town's size (m, plan x by plan y). */
  sizeM?: [number, number];
  water?: string; river?: { id: string; offM: number } | null; rail?: boolean; countySeat?: boolean; shiftM?: number;
  height?: { m: number; min: number; max: number; relief: number };
  entries?: { kind: string; road: string; side: string; worldAt: number; at: number; spoke: string }[];
  counts?: { buildings: number; lots: number; people: number; rooms: number; pictures: number };
  file?: string; bytes?: number; raw?: Record<string, number>;
  ms?: Record<string, number>;
  handmade?: string;
  problems?: string[];
}

async function bakeOne(sitePath: string, out: string): Promise<void> {
  const { baseRecipe, placeTown, entriesOf, groundOf, groundStats } = await import('./towns-site');
  const { planTown } = await import('../../src/town/plan');
  const { platTown } = await import('../../src/town/plat');
  const s = JSON.parse(readFileSync(sitePath, 'utf8')) as import('./towns-site').Site;
  const id = fileId(s.place.id);
  const dir = join(out, 'work', id);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const ms: Record<string, number> = {};
  let t0 = Date.now();
  const lap = (k: string): void => { const n = Date.now(); ms[k] = n - t0; t0 = n; };
  const problems: string[] = [];

  // ---- the recipe, the plan, the town's place on the planet (re-planned on a river, or smaller on a steep site)
  let recipe: TownRecipe = baseRecipe(s);
  const planned = (r: TownRecipe) => { useRecipe(r); const plan = planTown(r.seed); return { plan, placed: placeTown(s, plan, platTown(r.seed).flipX) }; };
  let { plan, placed } = planned(recipe);
  if (recipe.water !== 'river' && placed.problems.some((p) => p.includes('river'))) {
    // (a river through where the town must stand: the town is on it, its river the world's)
    const r2: TownRecipe = { ...baseRecipe(s, 'river'), seed: `${recipe.seed}~river` };
    const p2 = planned(r2);
    if (p2.placed.problems.length <= placed.problems.length) { recipe = r2; ({ plan, placed } = p2); }
  }
  let height = groundStats(s, plan, placed.frame);
  if (height.relief > 90 && recipe.size !== 'H' && (recipe.setting === 'alpine' || recipe.setting === 'mining' || recipe.rank === 'village')) {
    // (a steep mountain site: the village is planned as a hamlet, two roads and the houses along them, to fit the slope)
    const r3: TownRecipe = { ...recipe, seed: `${recipe.seed}~h`, size: SIZE.hamlet, people: PEOPLE.hamlet };
    const p3 = planned(r3);
    const h3 = groundStats(s, p3.plan, p3.placed.frame);
    problems.push(`relief ${height.relief} m: planned as a hamlet (relief ${h3.relief} m)`);
    recipe = r3; ({ plan, placed } = p3); height = h3;
  }
  problems.push(...placed.problems);
  if (height.relief > 60) problems.push(`relief ${height.relief} m under the built town: its ground is levelled to ${height.m} m (cut and fill)`);
  recipe.entries = entriesOf(s, plan, placed.frame);
  useRecipe(recipe);
  writeFileSync(join(dir, 'recipe.json'), JSON.stringify(recipe));
  lap('plan');

  // ---- the town in the app's formats
  const { townGraph } = await import('./town-graph');
  const { withFarms } = await import('./town-farms');
  const { withYards } = await import('./town-yards');
  const { planFarms } = await import('../../src/town/farms');
  const g = townGraph(recipe.seed, 2025);
  for (const c of g.conflicts) problems.push(`graph: ${c}`);
  const street = withFarms(g, withYards(g)) as unknown as { shops: unknown[]; graph: { lots: unknown[] } };
  writeFileSync(join(dir, 'street.json'), JSON.stringify(street));
  const fp = planFarms(plan);
  lap('street');
  const env = { ...process.env, TOWN_RECIPE: join(dir, 'recipe.json') };
  const tsx = (script: string, file: string, extra: string[]): void => {
    const r = spawnSync('npx', ['tsx', join(here, script), recipe.seed, '2025', '--all', '--street', join(dir, 'street.json'), ...extra], { cwd: root, env, maxBuffer: 1 << 30 });
    if (r.status !== 0) throw new Error(`${script}: ${r.stderr.toString().slice(-2000)}`);
    writeFileSync(join(dir, file), r.stdout);
  };
  tsx('export-dialogue.ts', 'dialogue.json', []);
  lap('dialogue');
  tsx('export-interiors.ts', 'interiors.json', []);
  lap('interiors');
  const py = spawnSync('python3', [join(here, 'make_buildings.py'), '--jobs=1', join(dir, 'street.json'), join(dir, 'buildings')], { cwd: root, maxBuffer: 1 << 28 });
  if (py.status !== 0) throw new Error(`make_buildings: ${py.stderr.toString().slice(-2000)}`);
  lap('buildings');

  // ---- the pack
  const files: { path: string; data: Buffer }[] = [];
  for (const f of ['street.json', 'dialogue.json', 'interiors.json', 'recipe.json']) files.push({ path: f, data: readFileSync(join(dir, f)) });
  const pics = readdirSync(join(dir, 'buildings')).sort();
  for (const f of pics) files.push({ path: `buildings/${f}`, data: readFileSync(join(dir, 'buildings', f)) });
  const buf = pack(s.place.id, files);
  mkdirSync(join(out, 'towns'), { recursive: true });
  writeFileSync(join(out, 'towns', `${id}.ttwn`), buf);
  const raw: Record<string, number> = { street: 0, dialogue: 0, interiors: 0, recipe: 0, pictures: 0 };
  const packed: Record<string, number> = { json: 0, pictures: 0 };
  for (const f of files) {
    const k = f.path.startsWith('buildings/') ? (f.path.endsWith('.png') ? 'pictures' : 'street') : f.path.replace('.json', '');
    raw[k] = (raw[k] ?? 0) + f.data.length;
  }
  {
    const n = buf.readUInt32LE(5);
    const head = JSON.parse(buf.toString('utf8', 9, 9 + n)) as { files: { path: string; size: number }[] };
    for (const f of head.files) packed[f.path.endsWith('.png') ? 'pictures' : 'json'] += f.size;
  }
  // (the pictures' hashes, for the planet's shared picture pool: tools/towns-bake.ts summary)
  const hashes = pics.filter((f) => f.endsWith('.png')).map((f) => createHash('sha1').update(readFileSync(join(dir, 'buildings', f))).digest('hex').slice(0, 16) + ':' + statSync(join(dir, 'buildings', f)).size);
  const dlg = JSON.parse(readFileSync(join(dir, 'dialogue.json'), 'utf8')) as { npcs?: unknown[] };
  const rooms = Object.keys((JSON.parse(readFileSync(join(dir, 'interiors.json'), 'utf8')) as { rooms: object }).rooms).length;
  lap('pack');
  if (!argv.includes('--keep')) rmSync(join(dir, 'buildings'), { recursive: true, force: true });
  const row: Row = {
    id: s.place.id, name: s.place.name, rank: recipe.rank, type: recipe.type, ...(recipe.setting ? { setting: recipe.setting } : {}), ...(recipe.ore ? { ore: recipe.ore } : {}),
    seed: recipe.seed, at: [s.place.x, s.place.z], frame: placed.frame, ground: groundOf(plan),
    sizeM: [Math.round(plan.width * 2.1), Math.round(plan.height * 2.1)], water: recipe.water, river: placed.river, rail: recipe.rail, countySeat: recipe.countySeat,
    shiftM: placed.shiftM, height, entries: fp.entries,
    counts: { buildings: street.shops.length, lots: street.graph.lots.length, people: dlg.npcs?.length ?? 0, rooms, pictures: pics.length },
    file: `towns/${id}.ttwn`, bytes: buf.length, raw: { ...raw, packedJson: packed.json!, packedPictures: packed.pictures! }, ms, problems,
  };
  mkdirSync(join(out, 'rows'), { recursive: true });
  writeFileSync(join(out, 'rows', `${id}.json`), JSON.stringify(row));
  writeFileSync(join(out, 'rows', `${id}.pics`), hashes.join('\n'));
}

// ------------------------------------------------------------------------------------------------ the planet (main)
/** About [n] places across the ranks on the capital's continent and its neighbour (the proof's sample). */
export function sampleOf(sites: import('./towns-site').Site[], startMass: number, n = 30): string[] {
  const { rankOf } = { rankOf: (p: import('./towns-site').Site['place']) => (p.kind === 'hub' ? 'hub' : p.kind === 'hamlet' ? 'hamlet' : p.rank === 'major' ? 'major' : p.rank === 'town' ? 'town' : 'village') };
  const count = new Map<number, number>();
  for (const s of sites) count.set(s.place.mass, (count.get(s.place.mass) ?? 0) + 1);
  const other = [...count.entries()].filter(([m]) => m !== startMass).sort((a, b) => b[1] - a[1])[0]?.[0];
  const pool = sites.filter((s) => (s.place.mass === startMass || s.place.mass === other) && s.place.id !== 'h:capital');
  const key = (s: import('./towns-site').Site): string => createHash('sha1').update(s.place.seed).digest('hex');
  const want: [string, (s: import('./towns-site').Site) => boolean, number][] = [
    ['hub', (s) => rankOf(s.place) === 'hub', 2],
    ['major', (s) => rankOf(s.place) === 'major', 3],
    ['town', (s) => rankOf(s.place) === 'town' && !s.place.setting, 5],
    ['village', (s) => rankOf(s.place) === 'village' && !s.place.setting, 5],
    ['alpine village', (s) => rankOf(s.place) === 'village' && s.place.setting === 'alpine', 2],
    ['mining town', (s) => s.place.setting === 'mining', 3],
    ['hamlet', (s) => rankOf(s.place) === 'hamlet' && !s.place.setting, 6],
    ['mountain hamlet', (s) => rankOf(s.place) === 'hamlet' && !!s.place.setting, 2],
  ];
  const out: string[] = [];
  const scale = n / 28;
  for (const [, ok, k] of want) {
    const cand = pool.filter(ok).sort((a, b) => (key(a) < key(b) ? -1 : 1));
    // (both continents: alternate between them)
    const a = cand.filter((s) => s.place.mass === startMass), b = cand.filter((s) => s.place.mass !== startMass);
    const m = Math.max(1, Math.round(k * scale));
    for (let i = 0; out.length < 1e9 && i < m; i++) { const s = (i % 2 === 0 ? a : b)[Math.floor(i / 2)] ?? (i % 2 === 0 ? b : a)[Math.floor(i / 2)]; if (s && !out.includes(s.place.id)) out.push(s.place.id); }
  }
  return out;
}

async function main(): Promise<void> {
  const world = flag('--world') ?? 'alpha';
  const out = resolve(flag('--out') ?? join(root, 'kotlin-street/build-towns', world));
  const jobs = Number(flag('--jobs') ?? cpus().length);
  mkdirSync(out, { recursive: true });
  const t0 = Date.now();
  const { generateWorld } = await import('../../src/world/gen/world');
  const { worldPaths } = await import('../../src/world/gen/paths');
  const { sitesOf, baseRecipe } = await import('./towns-site');
  // (the sites are cached in the output folder: --fresh makes the world again)
  const cache = join(out, 'sites.json.gz');
  let sites: import('./towns-site').Site[], startMass: number;
  if (existsSync(cache) && !argv.includes('--fresh')) ({ sites, startMass } = JSON.parse(gunzipSync(readFileSync(cache)).toString('utf8')));
  else {
    const w = generateWorld(world);
    sites = sitesOf(w, worldPaths(w));
    startMass = w.startMass;
    writeFileSync(cache, gzipSync(JSON.stringify({ sites, startMass })));
  }
  console.error(`world ${world}: ${sites.length} places, ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  const handmade = JSON.parse(readFileSync(join(here, 'towns-handmade.json'), 'utf8')) as Record<string, { dir: string; seed: string; note?: string }>;
  const byId = new Map(sites.map((s) => [s.place.id, s]));
  let chosen: string[];
  if (argv.includes('--all')) chosen = sites.map((s) => s.place.id);
  else if (flag('--places')) chosen = flag('--places')!.split(',');
  else chosen = sampleOf(sites, startMass, Number(flag('--sample') ?? 30));
  chosen = chosen.filter((id) => byId.has(id) && !handmade[id]);
  mkdirSync(join(out, 'sites'), { recursive: true });
  const todo = chosen.filter((id) => !(argv.includes('--resume') && existsSync(join(out, 'rows', `${fileId(id)}.json`))));
  for (const id of todo) writeFileSync(join(out, 'sites', `${fileId(id)}.json`), JSON.stringify(byId.get(id)));
  console.error(`baking ${todo.length} towns on ${jobs} workers into ${out}`);
  // ---- the pool
  let next = 0, done = 0;
  const failed: string[] = [];
  const t1 = Date.now();
  await new Promise<void>((ok) => {
    const run = (): void => {
      if (next >= todo.length) { if (done === todo.length) ok(); return; }
      const id = todo[next++]!;
      const p = spawn('npx', ['tsx', join(here, 'towns-bake.ts'), '--one', join(out, 'sites', `${fileId(id)}.json`), out, ...(argv.includes('--keep') ? ['--keep'] : [])], { cwd: root, stdio: ['ignore', 'ignore', 'pipe'] });
      let err = '';
      p.stderr.on('data', (d) => { err += d; if (err.length > 20000) err = err.slice(-10000); });
      p.on('close', (code) => {
        done++;
        if (code !== 0) { failed.push(id); writeFileSync(join(out, 'sites', `${fileId(id)}.err`), err); }
        console.error(`[${done}/${todo.length}] ${id} ${code === 0 ? 'ok' : 'FAILED'} (${((Date.now() - t1) / 1000).toFixed(0)} s)`);
        run();
      });
    };
    if (!todo.length) ok();
    for (let i = 0; i < Math.min(jobs, todo.length); i++) run();
  });
  // ---- the manifest: every place
  const rows: Row[] = [];
  for (const s of sites) {
    const id = s.place.id;
    const hm = handmade[id];
    if (hm) { rows.push({ id, name: s.place.name, rank: 'hub', type: s.place.type, seed: hm.seed, at: [s.place.x, s.place.z], handmade: hm.dir }); continue; }
    const f = join(out, 'rows', `${fileId(id)}.json`);
    if (existsSync(f)) { rows.push(JSON.parse(readFileSync(f, 'utf8')) as Row); continue; }
    const r = baseRecipe(s);
    rows.push({ id, name: s.place.name, rank: r.rank, type: r.type, ...(r.setting ? { setting: r.setting } : {}), seed: r.seed, at: [s.place.x, s.place.z], water: r.water, rail: r.rail, countySeat: r.countySeat });
  }
  const manifest = { version: 1, world, pack: PACK_VERSION, places: rows.length, baked: rows.filter((r) => r.file).length, handmade: rows.filter((r) => r.handmade).length, towns: rows };
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest));
  writeFileSync(join(out, 'summary.json'), JSON.stringify(summarize(rows, out, sites.length), null, 1));
  console.error(`manifest: ${rows.length} places (${manifest.baked} baked, ${manifest.handmade} hand-made); failed: ${failed.join(', ') || 'none'}; ${((Date.now() - t0) / 1000).toFixed(0)} s`);
  if (failed.length) process.exitCode = 1;
}

/** Time and size by rank, the shared picture pool, and the planet's totals by rank counts. */
function summarize(rows: Row[], out: string, places: number): object {
  const ranks = ['hamlet', 'village', 'town', 'major', 'hub'];
  const by: Record<string, { n: number; baked: number; msMean: number; msMax: number; mbMean: number; mbMax: number; jsonMbMean: number; picMbMean: number; buildingsMean: number; peopleMean: number; sizeM: string }> = {};
  const seen = new Map<string, number>();
  let picsAll = 0;
  for (const r of rows) for (const line of existsSync(join(out, 'rows', `${fileId(r.id)}.pics`)) && r.file ? readFileSync(join(out, 'rows', `${fileId(r.id)}.pics`), 'utf8').split('\n').filter(Boolean) : []) {
    const [h, b] = line.split(':');
    picsAll += Number(b);
    seen.set(h!, Number(b));
  }
  const unique = [...seen.values()].reduce((a, b) => a + b, 0);
  let total = 0;
  for (const k of ranks) {
    const all = rows.filter((r) => r.rank === k && !r.handmade), baked = all.filter((r) => r.file);
    const mean = (f: (r: Row) => number): number => (baked.length ? baked.reduce((a, r) => a + f(r), 0) / baked.length : 0);
    const ms = (r: Row): number => Object.values(r.ms ?? {}).reduce((a, b) => a + b, 0);
    by[k] = {
      n: all.length, baked: baked.length, msMean: Math.round(mean(ms)), msMax: Math.max(0, ...baked.map(ms)),
      mbMean: +(mean((r) => r.bytes!) / 1e6).toFixed(2), mbMax: +(Math.max(0, ...baked.map((r) => r.bytes!)) / 1e6).toFixed(2),
      jsonMbMean: +(mean((r) => r.raw!.packedJson!) / 1e6).toFixed(2), picMbMean: +(mean((r) => r.raw!.packedPictures!) / 1e6).toFixed(2),
      buildingsMean: Math.round(mean((r) => r.counts!.buildings)), peopleMean: Math.round(mean((r) => r.counts!.people)),
      sizeM: baked.length ? `${Math.round(mean((r) => r.sizeM![0]))} x ${Math.round(mean((r) => r.sizeM![1]))}` : '',
    };
    total += all.length * by[k]!.mbMean;
  }
  const cpuS = ranks.reduce((a, k) => a + by[k]!.n * by[k]!.msMean, 0) / 1000;
  return {
    places, byRank: by,
    planetGb: +(total / 1000).toFixed(2),
    planetCpuHours: +(cpuS / 3600).toFixed(1),
    pictures: { sampleMb: +(picsAll / 1e6).toFixed(1), uniqueMb: +(unique / 1e6).toFixed(1), sharedPoolSaves: picsAll ? +(1 - unique / picsAll).toFixed(3) : 0 },
  };
}

if (argv[0] === '--one') bakeOne(argv[1]!, argv[2]!).catch((e) => { console.error(e); process.exit(1); });
else if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch((e) => { console.error(e); process.exit(1); });
