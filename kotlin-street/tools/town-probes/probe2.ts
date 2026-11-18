import { runTimeline, worldAt } from '/home/user/Timerpg/src/sim';
import { layoutTown } from '/home/user/Timerpg/src/town/layout';
const seed = process.argv[2] ?? 'eta', year = Number(process.argv[3] ?? 2025);
const w = worldAt(runTimeline({ seed, endYear: year }), year);
const L = layoutTown(w);
const c: Record<string, number> = {};
for (const l of L.lots) {
  const k = l.art.layers[0]?.key ?? '?';
  const built = /^house_(small|medium)_/.test(k) ? 'mobile' : /^villa_\d+$/.test(k) ? 'villa' : /^house_terraced_/.test(k) ? 'terraced' : /^estate_/.test(k) ? 'estate' : /^condo_(3_4[45]|5_[123]|9)$/.test(k) ? 'aptblock' : l.kind === 'shop' ? 'shopfacade' : 'STANDIN';
  const key = `${l.tier}:${built}:${k.replace(/\d+/g, '#')}`; c[key] = (c[key] ?? 0) + 1;
}
console.log(c);
const shops = L.lots.filter((l) => l.kind === 'shop').map((l) => w.businesses[l.refId]?.kind);
const sk: Record<string, number> = {}; for (const k of shops) sk[k!] = (sk[k!] ?? 0) + 1; console.log(sk);
// main street order with junction xs
const main = L.roads.filter((r) => r.main && r.horizontal);
const vs = L.roads.filter((r) => !r.horizontal && !r.main);
const xs = [...new Set(vs.filter((v) => main.some((m) => Math.abs(v.y + v.h - m.y) <= 3 || Math.abs(v.y - (m.y + m.h)) <= 3)).map((v) => v.x))];
console.log('verticals meeting main at x', xs.sort((a,b)=>a-b));
const near = L.lots.filter((l) => main.some((m) => (Math.abs(l.y + l.h - m.y) <= 1 || Math.abs(l.y - (m.y + m.h)) <= 1) && l.x + l.w > m.x && l.x < m.x + m.w));
console.log('main frontage', near.sort((a,b)=>a.x-b.x).map((l) => `${l.x}${l.y + l.h <= 116 ? 'N' : 'S'}:${l.kind === 'shop' ? w.businesses[l.refId]?.kind : l.tier}`).join(' | '));
console.log('residents', L.lots.reduce((s, l) => s + l.residents.length, 0), 'people alive', Object.values(w.people).filter((p: any) => !p.died).length);
