import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { generateWorld } from '../../src/world/gen/world';
import { worldPaths } from '../../src/world/gen/paths';
import { CELL_M } from '../../src/world/gen/grid';
import { planTown } from '../../src/town/plan';
import { platTown } from '../../src/town/plat';
import { planFarms } from '../../src/town/farms';
import { useRecipe, type TownRecipe } from '../../src/town/recipe';
import { baseRecipe, entriesOf, groundOf, placeTown, segHitsRect, sitesOf, toPlan, toWorld, type Site } from './towns-site';
import { fileId, handmadeTowns, manifestRows, pack, sampleOf, unpack } from './towns-bake';

// Towns everywhere (kotlin-street/docs/TOWNS_EVERYWHERE.md): every world place's town planned from its recipe, placed on the
// planet, its roads in where the world's roads cross its ground's edge; the bake deterministic; the manifest complete.
// One world generation (about a minute) for all of it.

const here = dirname(fileURLToPath(import.meta.url));
let sites: Site[];
let sample: Site[];
const planned = new Map<string, { recipe: TownRecipe; plan: ReturnType<typeof planTown>; placed: ReturnType<typeof placeTown> }>();

beforeAll(() => {
  const w = generateWorld('alpha');
  sites = sitesOf(w, worldPaths(w));
  const ids = sampleOf(sites, w.startMass, 30);
  const byId = new Map(sites.map((s) => [s.place.id, s]));
  sample = ids.map((id) => byId.get(id)!);
  // (the baker's steps up to the export, for each: recipe, plan, frame, entries, the plan again with them)
  for (const s of sample) {
    const recipe = baseRecipe(s);
    useRecipe(recipe);
    const plan = planTown(recipe.seed);
    const placed = placeTown(s, plan, platTown(recipe.seed).flipX);
    recipe.entries = entriesOf(s, plan, placed.frame);
    planned.set(s.place.id, { recipe, plan, placed });
  }
}, 400_000);

describe('towns everywhere', () => {
  it('samples every rank on two continents, a bypassed town among them', () => {
    const ranks = new Set(sample.map((s) => baseRecipe(s).rank));
    expect([...ranks].sort()).toEqual(['hamlet', 'hub', 'major', 'town', 'village']);
    expect(new Set(sample.map((s) => s.place.mass)).size).toBe(2);
    expect(sample.some((s) => s.place.setting === 'alpine')).toBe(true);
    expect(sample.some((s) => s.place.setting === 'mining')).toBe(true);
  });

  it("meets every world road exactly where it crosses the town's ground", () => {
    let n = 0;
    for (const s of sample) {
      const { recipe, plan } = planned.get(s.place.id)!;
      const fp = planFarms(plan);
      const want = (recipe.entries ?? []).filter((e) => e.kind !== 'rail' && e.kind !== 'expressway');
      expect(fp.entries.length, s.place.id).toBe(want.length);
      for (const e of fp.entries) {
        expect(Math.abs(e.at - e.worldAt), `${s.place.id} ${e.road}`).toBeLessThan(0.051);
        // the town's road there reaches the ground's edge on that side
        const r = fp.roads.find((q) => q.id === e.spoke)!;
        const g = groundOf(plan);
        const edge = e.side === 'N' ? g.y0 : e.side === 'S' ? g.y1 : e.side === 'W' ? g.x0 : g.x1;
        const end = e.side === 'N' || e.side === 'S' ? [r.a[1], r.b[1]] : [r.a[0], r.b[0]];
        expect(end, `${s.place.id} ${e.road}`).toContain(edge);
        n++;
      }
    }
    expect(n).toBeGreaterThan(30);
  });

  it('keeps every town off other rivers, the sea and other towns', () => {
    const boxes: { id: string; pts: [number, number][] }[] = [];
    for (const s of sample) {
      const { plan, placed } = planned.get(s.place.id)!;
      // no river but its own through the built town (placeTown's check), nor any place's point but its own in it
      expect(placed.problems.filter((p) => p.includes('river') || p.includes('sea')), s.place.id).toEqual([]);
      for (const r of s.polys) {
        if (r.kind !== 'river' || r.id === placed.river?.id) continue;
        for (let i = 1; i < r.pts.length; i++) expect(segHitsRect(toPlan(placed.frame, ...r.pts[i - 1]!), toPlan(placed.frame, ...r.pts[i]!), 0, 0, plan.width, plan.height), `${s.place.id} ${r.id}`).toBe(false);
      }
      const corners: [number, number][] = [[0, 0], [plan.width, 0], [plan.width, plan.height], [0, plan.height]].map(([x, y]) => {
        const [wx, wz] = toWorld(placed.frame, x!, y!);
        return [s.place.x + wx / CELL_M, s.place.z + wz / CELL_M];
      });
      boxes.push({ id: s.place.id, pts: corners });
      for (const o of sites) {
        if (o === s || o.place.mass !== s.place.mass || Math.hypot(o.place.x - s.place.x, o.place.z - s.place.z) > 4) continue;
        const [px, py] = toPlan(placed.frame, (o.place.x - s.place.x) * CELL_M, (o.place.z - s.place.z) * CELL_M);
        expect(px > 0 && px < plan.width && py > 0 && py < plan.height, `${o.place.id} inside ${s.place.id}`).toBe(false);
      }
    }
    // the sampled towns' built areas never overlap (separating axes of the two rectangles)
    const sep = (a: [number, number][], b: [number, number][]): boolean => {
      for (const poly of [a, b]) for (let i = 0; i < 4; i++) {
        const p = poly[i]!, q = poly[(i + 1) % 4]!, n = [q[1] - p[1], p[0] - q[0]];
        const pa = a.map((v) => v[0] * n[0]! + v[1] * n[1]!), pb = b.map((v) => v[0] * n[0]! + v[1] * n[1]!);
        if (Math.max(...pa) < Math.min(...pb) || Math.max(...pb) < Math.min(...pa)) return true;
      }
      return false;
    };
    for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) expect(sep(boxes[i]!.pts, boxes[j]!.pts), `${boxes[i]!.id} ${boxes[j]!.id}`).toBe(true);
  });

  it('lists every place in the manifest, the capital as its hand-made town', () => {
    const dir = mkdtempSync(join(tmpdir(), 'towns-'));
    try {
      const rows = manifestRows(sites, dir, handmadeTowns());
      expect(rows.length).toBe(sites.length);
      expect(new Set(rows.map((r) => r.id)).size).toBe(sites.length);
      const cap = rows.find((r) => r.id === 'h:capital')!;
      expect(cap.handmade).toBe('kotlin-street/app/src/main/assets/town/gamma');
      expect(cap.seed).toBe('gamma');
      for (const r of rows) expect(r.rank).toMatch(/^(hamlet|village|town|major|hub)$/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });

  it('packs the same files to the same bytes, and unpacks them', () => {
    const files = [{ path: 'street.json', data: Buffer.from('{"a":1}') }, { path: 'buildings/x.png', data: Buffer.from([1, 2, 3]) }];
    const a = pack('t:1', files), b = pack('t:1', [...files].reverse());
    expect(a.equals(b)).toBe(true);
    const u = unpack(a);
    expect(u.id).toBe('t:1');
    expect(u.files.get('street.json')!.toString()).toBe('{"a":1}');
  });

  it('bakes a town to the same bytes twice', async () => {
    const s = sample.find((q) => baseRecipe(q).rank === 'hamlet')!;
    const dir = mkdtempSync(join(tmpdir(), 'towns-'));
    try {
      const site = join(dir, 'site.json');
      writeFileSync(site, JSON.stringify(s));
      // (each in its own process, as the baker's workers: nothing cached between them)
      for (const o of ['a', 'b']) {
        const r = spawnSync('npx', ['tsx', join(here, 'towns-bake.ts'), '--one', site, join(dir, o)], { cwd: join(here, '../..'), maxBuffer: 1 << 26 });
        expect(r.status, r.stderr.toString().slice(-2000)).toBe(0);
      }
      const f = `towns/${fileId(s.place.id)}.ttwn`;
      expect(readFileSync(join(dir, 'a', f)).equals(readFileSync(join(dir, 'b', f)))).toBe(true);
    } finally { rmSync(dir, { recursive: true, force: true }); }
  }, 300_000);
});
