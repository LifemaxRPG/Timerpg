import { runTimeline, worldAt } from '/home/user/Timerpg/src/sim';
import { layoutTown } from '/home/user/Timerpg/src/town/layout';
for (const seed of (process.argv[2] ?? 'eta').split(',')) {
const year = 2025;
const w = worldAt(runTimeline({ seed, endYear: year }), year);
const L = layoutTown(w); const R = L.roads;
const ys = [...new Set(R.filter((r) => r.horizontal).map((r) => r.y))], xs = [...new Set(R.filter((r) => !r.horizontal).map((r) => r.x))];
const things = [...L.lots, ...L.landmarks.map((l) => ({ ...l, kind: 'landmark' }))];
let sx = 0, sy = 0; const ex: string[] = [];
for (const l of things as any[]) {
  for (const y of ys) if (l.y < y + 2 && l.y + l.h > y) { sy++; if (ex.length < 6) ex.push(`${l.tier ?? l.id}@${l.x},${l.y} cut by y${y}`); }
  for (const x of xs) if (l.x < x + 2 && l.x + l.w > x) { sx++; if (ex.length < 6) ex.push(`${l.tier ?? l.id}@${l.x},${l.y} cut by x${x}`); }
}
const landmarksFront = L.landmarks.map((l) => l.id).join(',');
console.log(seed, 'roads', R.length, 'lots', L.lots.length, 'landmarks', landmarksFront, 'distinct x lines', xs.length, 'y lines', ys.length, 'lots cut by a foreign asphalt band: by y', sy, 'by x', sx, ex.join('; '));
}
