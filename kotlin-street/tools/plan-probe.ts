// A dump of the town as TODAY's generator builds it (plan, layout, model sites, rail, farms), for the comparison map of
// kotlin-street/docs/TOWN_PLAN.md: npx tsx kotlin-street/tools/plan-probe.ts gamma 2025 out.json
import { runTimeline, worldAt } from '../../src/sim';
import { layoutTown } from '../../src/town/layout';
import { planFarms } from '../../src/town/farms';
import { townHall, chapel, downtownStanding } from '../../src/town/downtown';
import { townGlb, glbStanding } from './town-glb';
import { archetypeOf } from '../../src/sim/archetype';
import { townPeople, peakPeople } from '../../src/town/glbSites';
import { writeFileSync } from 'node:fs';
const seed = process.argv[2] ?? 'gamma', year = Number(process.argv[3] ?? 2025);
const t0 = Date.now();
const tl = runTimeline({ seed, endYear: 2025 });
const w = worldAt(tl, year);
const L = layoutTown(w);
const plan = L.plan;
const fp = planFarms(plan);
const { sites, rail } = townGlb(seed);
const glb = glbStanding(sites, year).map((s) => ({ id: s.site.id, rect: s.site.rect, name: s.name, kind: s.tenure.kind }));
const wat: number[][] = [];
if (plan.water) for (let y = 0; y < plan.height; y++) for (let x = 0; x < plan.width; x++) if (plan.water.mask[y * plan.width + x]) wat.push([x, y]);
const out = {
  seed, year, type: archetypeOf(seed).label, people: townPeople(seed, year), peak: peakPeople(seed),
  pop: [1950,1960,1970,1980,1990,2000,2010,2025].map((y) => [y, townPeople(seed, y)]),
  width: plan.width, height: plan.height, roads: L.roads, allRoads: plan.roads, plaza: plan.plaza,
  lots: L.lots.map((l) => ({ x: l.x, y: l.y, w: l.w, h: l.h, kind: l.kind, tier: l.tier })),
  candidates: plan.candidates.map((c) => ({ x: c.x, y: c.y, w: c.w, h: c.h, kind: c.kind })),
  landmarks: L.landmarks.map((l) => ({ x: l.x, y: l.y, w: l.w, h: l.h, id: l.id })),
  hall: townHall(plan)?.rect ?? null, chapel: chapel(plan)?.rect ?? null,
  towers: downtownStanding(plan, year).map((b: any) => ({ kind: b.kind, rect: b.rect })),
  glb, water: wat,
  rail: { tracks: rail.tracks.map((t) => t.pts), depot: rail.depot?.premier ?? rail.depot?.rect ?? null, elevator: rail.elevator },
  farms: { roads: fp.roads.map((r: any) => ({ a: r.a, b: r.b })), farms: fp.farms.map((f: any) => f.yard ?? f.rect ?? null), fields: L.farms.fields?.map((f: any) => ({ x: f.x, y: f.y, w: f.w, h: f.h })) ?? [] },
};
writeFileSync(process.argv[4] ?? '/dev/stdout', JSON.stringify(out));
console.error('ms', Date.now() - t0, out.type, out.people, out.peak, out.width, out.height, 'roads', out.roads.length, 'lots', out.lots.length, 'glb', glb.map((g) => g.name).join(','));
