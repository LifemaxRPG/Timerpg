// Dumps a town's plan (src/town/plat.ts, src/town/districts.ts) as the JSON tools/plan-map.py draws: districts, streets,
// lots with the year each is built, reserved plots with their uses, the river and the rail band.
// Usage: npx tsx kotlin-street/tools/plan-dump.ts <seed> <year> > out.json
import { platTown } from '../../src/town/plat';
import { districtsOf } from '../../src/town/districts';
import { planTown } from '../../src/town/plan';
import { archetypeOf } from '../../src/sim/archetype';
import { townName } from '../../src/world/names';
import { townPeople, peakPeople } from '../../src/town/glbSites';

const seed = process.argv[2] ?? 'gamma';
const year = Number(process.argv[3] ?? 2025);
const plat = platTown(seed);
const dist = districtsOf(seed);
const plan = planTown(seed);
// the plan's frame: plan.ts crops and moves the plat; its offset is plan.shift
const sh = plan.v2?.shift ?? { dx: 0, dy: 0 };
const mv = (r: { x: number; y: number; w: number; h: number }) => ({ x: r.x + sh.dx, y: r.y + sh.dy, w: r.w, h: r.h });
const label = (id: string): string => id.replace(/^(glb|landmark|civic):/, '').replace(/_/g, ' ');
const cand = new Map(plan.candidates.map((c) => [c.id, c]));
const out = {
  title: `${townName(seed)} (${seed}), ${year}`,
  sub: `${archetypeOf(seed).label}${plat.countySeat ? ', county seat' : ''}; ${plat.form === 'T' ? 'T-plan' : 'parallel rail'}; ${Math.round(townPeople(seed, Math.min(2025, Math.max(1950, year))) / 10) * 10} people (peak ${peakPeople(seed)}); size ${plat.size}`,
  year, width: plan.width, height: plan.height,
  zones: plat.blocks.map((b) => ({ rect: mv({ x: b.x - 2, y: b.y - 2, w: b.w + 4, h: b.h + 4 }), d: b.district })),
  roads: [
    ...plan.roads.map((r, i) => ({ rect: r, cls: r.main ? 'main' : 'street', from: (plan as unknown as { roadFrom?: number[] }).roadFrom?.[i] ?? 0 })),
    ...dist.alleys.map((a) => ({ rect: mv(a), cls: 'lane', from: 1890 })),
  ],
  lots: [
    ...dist.lots.map((l) => ({ rect: mv(l), kind: l.kind, d: l.district, from: cand.get(`${l.kind}:${l.x + sh.dx},${l.y + sh.dy}`)?.from ?? 0 })),
    ...dist.reserved.map((q) => {
      const cur = q.chain.filter((u) => u.from <= year && (u.to === undefined || year < u.to)).pop();
      const first = q.chain.find((u) => !['field', 'woods', 'shops', 'reserved', 'square', 'house'].includes(u.use));
      return { rect: mv(q), kind: q.id.startsWith('glb') ? 'model' : q.id.includes('cemetery') ? 'cemetery' : q.id.includes('plaza') ? 'square' : q.id.startsWith('landmark:') && /store|market/.test(q.id) ? 'store' : 'civic',
        d: q.district, label: cur && !['field', 'woods', 'reserved'].includes(cur.use) ? label(cur.use.replace('landmark_glb_', '')) : label(q.id), from: cur && !['field', 'woods', 'reserved'].includes(cur.use) ? 0 : (first?.from ?? 0), model: true, before: 'field' };
    }),
  ],
  water: plan.water ? (() => {
    const w = plan.water; const rs: { x: number; y: number; w: number; h: number }[] = [];
    for (let y = 0; y < w.height; y++) for (let x = 0; x < w.width; ) { if (!w.raw[y * w.width + x]) { x++; continue; } let e = x; while (e < w.width && w.raw[y * w.width + e]) e++; rs.push({ x, y, w: e - x, h: 1 }); x = e; }
    return rs;
  })() : [],
  fields: [], farms: [],
  rail: (plan as unknown as { railLines?: [number, number][][] }).railLines ? { tracks: (plan as unknown as { railLines: [number, number][][] }).railLines, from: 0 } : plat.railBand ? { track: plat.railBand.horizontal ? [[0, plat.railBand.track + sh.dy], [plan.width, plat.railBand.track + sh.dy]] : [[plat.railBand.track + sh.dx, 0], [plat.railBand.track + sh.dx, plan.height]], from: 0 } : null,
  labels: [],
};
process.stdout.write(JSON.stringify(out));
