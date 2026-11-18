import { runTimeline, worldAt } from '/home/user/Timerpg/src/sim';
import { layoutTown } from '/home/user/Timerpg/src/town/layout';
import { archetypeOf } from '/home/user/Timerpg/src/sim/archetype';
const seeds = process.argv[2]!.split(',');
for (const seed of seeds) {
  const t0 = Date.now();
  const w = worldAt(runTimeline({ seed, endYear: 2025 }), 2025);
  const L = layoutTown(w); const p = L.plan;
  const pc = { x: p.plaza.x + p.plaza.w / 2, y: p.plaza.y + p.plaza.h / 2 };
  const lm = L.landmarks.map((l) => `${l.id}:${Math.round(Math.hypot(l.x + l.w / 2 - pc.x, l.y + l.h / 2 - pc.y))}`);
  const near = L.landmarks.filter((l) => Math.hypot(l.x + l.w / 2 - pc.x, l.y + l.h / 2 - pc.y) < 45).length;
  const byN: Record<string, number> = {}; for (const l of L.lots) byN[l.neighborhood] = (byN[l.neighborhood] ?? 0) + 1;
  console.log(seed, archetypeOf(seed).label, `roads ${L.roads.length} lots ${L.lots.length}`, `nearPlaza ${near}`, lm.join(' '), JSON.stringify(byN), p.water?.kind ?? '-', `${Date.now() - t0}ms`);
}
