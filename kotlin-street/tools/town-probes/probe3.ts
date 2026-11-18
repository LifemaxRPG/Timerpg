import { runTimeline, worldAt } from '/home/user/Timerpg/src/sim';
import { layoutTown } from '/home/user/Timerpg/src/town/layout';
const seed = process.argv[2] ?? 'eta', year = Number(process.argv[3] ?? 2025);
const w = worldAt(runTimeline({ seed, endYear: year }), year);
const L = layoutTown(w); const R = L.roads;
const touches = (p: any, q: any) => p.x <= q.x + q.w + 1 && q.x <= p.x + p.w + 1 && p.y <= q.y + q.h + 1 && q.y <= p.y + p.h + 1;
type N = { x: number; y: number; roads: Set<number> };
const nodes: N[] = [];
const addN = (x: number, y: number, i: number) => { let n = nodes.find((n) => Math.abs(n.x - x) <= 3 && Math.abs(n.y - y) <= 3); if (!n) nodes.push(n = { x, y, roads: new Set() }); n.roads.add(i); };
R.forEach((r, i) => R.forEach((q, j) => { if (i < j && r.horizontal !== q.horizontal && touches(r, q)) { const v = r.horizontal ? q : r, h = r.horizontal ? r : q; addN(v.x, h.y, i); addN(v.x, h.y, j); } }));
// arms per node: for each road at node, does it extend both ways from node?
let deg: Record<number, number> = {};
for (const n of nodes) { let arms = 0; for (const i of n.roads) { const r = R[i]!; const lo = r.horizontal ? r.x : r.y, hi = lo + (r.horizontal ? r.w : r.h), c = r.horizontal ? n.x : n.y; if (c - lo > 3) arms++; if (hi - c > 4) arms++; } deg[arms] = (deg[arms] ?? 0) + 1; }
console.log('junction nodes', nodes.length, 'by arms', deg);
const ends = R.flatMap((r, i) => { const a = r.horizontal ? [r.x, r.y] : [r.x, r.y], b = r.horizontal ? [r.x + r.w, r.y] : [r.x, r.y + r.h]; return [a, b].filter(([x, y]) => !nodes.some((n) => Math.abs(n.x - x!) <= 4 && Math.abs(n.y - y!) <= 4)).map((p) => `${i}@${p}`); });
console.log('dead ends', ends.length, ends.join(' '));
console.log('bridges', JSON.stringify((L.plan.water as any)?.bridges ?? null));
const total = R.reduce((s, r) => s + (r.horizontal ? r.w : r.h), 0); console.log('total road tiles', total, '≈ m @ 1 tile', total);
const ctr = nodes.filter((n) => Math.abs(n.y - 116) < 6).map((n) => `${n.x},${n.y}[${[...n.roads].join(',')}]`); console.log('main street nodes', ctr.join(' '));
