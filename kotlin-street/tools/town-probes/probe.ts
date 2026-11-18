import { runTimeline, worldAt } from '/home/user/Timerpg/src/sim';
import { layoutTown } from '/home/user/Timerpg/src/town/layout';
const seed = process.argv[2] ?? 'eta', year = Number(process.argv[3] ?? 2025);
const w = worldAt(runTimeline({ seed, endYear: year }), year);
const L = layoutTown(w);
const p = L.plan;
console.log('size', L.width, L.height, 'center', p.center, 'mainY', p.mainY, 'plaza', p.plaza, 'water', p.water?.kind ?? null);
console.log('plan roads', p.roads.length, 'built roads', L.roads.length);
L.roads.forEach((r, i) => console.log(i, r.main ? 'MAIN' : '    ', r.horizontal ? 'H' : 'V', `x${r.x} y${r.y} w${r.w} h${r.h}`, 'len', r.horizontal ? r.w : r.h));
// junctions
const touches = (p: any, q: any) => p.x <= q.x + q.w + 1 && q.x <= p.x + p.w + 1 && p.y <= q.y + q.h + 1 && q.y <= p.y + p.h + 1;
let nj = 0; const deg = new Map<number, number>();
for (let i = 0; i < L.roads.length; i++) for (let j = i + 1; j < L.roads.length; j++) if (touches(L.roads[i], L.roads[j])) { nj++; deg.set(i, (deg.get(i) ?? 0) + 1); deg.set(j, (deg.get(j) ?? 0) + 1); }
console.log('touching pairs', nj, 'dead-end-ish roads (deg1)', [...L.roads.keys()].filter((i) => (deg.get(i) ?? 0) === 1).length);
const byK: Record<string, number> = {}; const byN: Record<string, number> = {};
for (const l of L.lots) { byK[l.tier] = (byK[l.tier] ?? 0) + 1; byN[l.neighborhood] = (byN[l.neighborhood] ?? 0) + 1; }
console.log('lots', L.lots.length, 'vacant', L.vacantLots.length, byK, byN);
console.log('neighborhoods', p.neighborhoods.map((n) => `${n.id} "${n.name}" @${Math.round(n.center.x)},${Math.round(n.center.y)} ${n.palette} ${n.yard}`));
console.log('landmarks (open)', L.landmarks.map((l) => `${l.id} x${l.x} y${l.y} w${l.w} h${l.h} open${l.openYear}`));
console.log('landmarks (plan)', p.landmarks.map((l) => l.id));
// landmark -> nearest road
for (const l of L.landmarks) { const r = L.roads.findIndex((r) => touches({ x: l.x - 1, y: l.y - 1, w: l.w + 2, h: l.h + 2 }, r)); console.log(' ', l.id, 'fronts road', r, 'dist to center', Math.round(Math.hypot(l.x + l.w / 2 - p.center.x, l.y + l.h / 2 - p.center.y))); }
// lots per road (fronting)
const front = (l: any, r: any) => r.horizontal ? ((Math.abs(l.y + l.h - r.y) <= 1 || Math.abs(l.y - (r.y + r.h)) <= 1) && l.x + l.w > r.x && l.x < r.x + r.w) : ((Math.abs(l.x + l.w - r.x) <= 1 || Math.abs(l.x - (r.x + r.w)) <= 1) && l.y + l.h > r.y && l.y < r.y + r.h);
const cnt = L.roads.map((r) => L.lots.filter((l) => front(l, r)));
cnt.forEach((ls, i) => console.log('road', i, 'lots', ls.length, 'shops', ls.filter((l) => l.kind === 'shop').length, [...new Set(ls.map((l) => l.neighborhood))].join(',')));
const orphan = L.lots.filter((l) => !L.roads.some((r) => front(l, r)));
console.log('lots fronting no road (tol 1)', orphan.length, orphan.slice(0, 5).map((l) => `${l.tier} ${l.x},${l.y} ${l.w}x${l.h}`));
// shops by distance to center
const shops = L.lots.filter((l) => l.kind === 'shop');
console.log('shops', shops.length, 'x range', Math.min(...shops.map((s) => s.x)), Math.max(...shops.map((s) => s.x)));
