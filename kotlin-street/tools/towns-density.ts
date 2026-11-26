// THE PLACES' DENSITY, MEASURED (kotlin-street/docs/TOWNS_EVERYWHERE.md "Density"): how far apart the places of each rank
// stand (nearest neighbour of the same rank, and of any rank: median and 90th percentile, km), and how long the drive is
// between neighbouring places of each rank at the era's speeds (the world's RoadGraph, the fastest car route that year).
// The setting that changes it is src/world/gen/density.ts (SPACING, one knob per rank). With --rail, also the train between
// neighbouring places of each rank that both have a station (src/world/rail.ts planRoute on the skeleton's network,
// leaving 08:00 on a Monday, 1930 and 1960): the time on the train and door to door, waits and changes included (from the
// unmerged towns-everywhere branch's measure, 2026-11-23).
//
//   npx tsx kotlin-street/tools/towns-density.ts [world] [--json out.json] [--rail]
import { writeFileSync } from 'node:fs';
import { CELL_KM, distKm } from '../../src/world/gen/grid';
import { generateWorld } from '../../src/world/gen/world';
import { hashStr, hashU } from '../../src/world/gen/noise';
import { rankOf } from './towns-site';
import { planRoute, type Route } from '../../src/world/rail';

const argv = process.argv.slice(2);
const seed = argv[0] && !argv[0].startsWith('--') ? argv[0] : 'alpha';
const w = generateWorld(seed);
const RANKS = ['hamlet', 'village', 'town', 'major', 'hub'] as const;
const YEARS = [1930, 1960, 1990, 2020];
const q = (a: number[], f: number): number => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(f * s.length))]! : NaN; };
const r1 = (v: number): number => Math.round(v * 10) / 10;

const byRank = new Map<string, typeof w.places>();
for (const p of w.places) { const k = rankOf(p); byRank.set(k, [...(byRank.get(k) ?? []), p]); }
void CELL_KM;
const out: Record<string, unknown> = { world: seed, places: w.places.length, ranks: {} as Record<string, unknown> };
console.log(`world ${seed}: ${w.places.length} places`);
console.log('rank      count  same-rank NN km (median / p90)  any-rank NN km (median / p90)   drive to the nearest same-rank place, median min (km) by year');
for (const k of RANKS) {
  const ps = byRank.get(k) ?? [];
  const nnSame: { d: number; p: (typeof ps)[number]; q: (typeof ps)[number] }[] = [];
  const nnAny: number[] = [];
  for (const p of ps) {
    let bs = Infinity, bq = p, ba = Infinity;
    for (const o of w.places) {
      if (o === p || o.mass !== p.mass) continue;
      const d = distKm(p.x, p.z, o.x, o.z);
      if (d < ba) ba = d;
      if (rankOf(o) === k && d < bs) { bs = d; bq = o; }
    }
    if (bs < Infinity) nnSame.push({ d: bs, p, q: bq });
    if (ba < Infinity) nnAny.push(ba);
  }
  // drives: up to 60 places of the rank (a fixed hash order), to their nearest same-rank neighbour
  const pick = [...nnSame].sort((a, b) => hashU(hashStr(a.p.id), 1) - hashU(hashStr(b.p.id), 1)).slice(0, 60);
  const drives: Record<number, { min: number; km: number }> = {};
  for (const y of YEARS) {
    const mins: number[] = [], kms: number[] = [];
    for (const s of pick) { const d = w.roadGraph.drive(s.p.id, s.q.id, y); if (d) { mins.push(d.min); kms.push(d.km); } }
    drives[y] = { min: Math.round(q(mins, 0.5)), km: r1(q(kms, 0.5)) };
  }
  // by rail (--rail): the same pairs where both places have a station
  const rail: Record<string, unknown> = {};
  if (argv.includes('--rail')) {
    const stations = new Set<string>();
    for (const l of w.rail.lines) for (const st of l.stations) stations.add(st);
    for (const y of [1930, 1960]) {
      const train: number[] = [], door: number[] = [];
      for (const sp of nnSame) {
        if (!stations.has(sp.p.id) || !stations.has(sp.q.id)) continue;
        const rt = planRoute(w.railWorld, sp.p.id, sp.q.id, y, 8 * 60) as Route;
        if (!rt.legs?.some((l) => l.mode === 'train')) continue;
        train.push(rt.legs.filter((l) => l.mode === 'train').reduce((a, l) => a + l.arrive - l.depart, 0));
        door.push(rt.arrive - rt.depart);
        if (train.length >= 60) break;
      }
      rail[y] = { pairs: train.length, trainMin: Math.round(q(train, 0.5)), doorMin: Math.round(q(door, 0.5)) };
    }
    rail.withStation = ps.filter((p) => stations.has(p.id)).length;
  }
  const row = {
    count: ps.length, sameMedianKm: r1(q(nnSame.map((s) => s.d), 0.5)), sameP90Km: r1(q(nnSame.map((s) => s.d), 0.9)),
    anyMedianKm: r1(q(nnAny, 0.5)), anyP90Km: r1(q(nnAny, 0.9)), drives, sampled: pick.length, ...(argv.includes('--rail') ? { rail } : {}),
  };
  (out.ranks as Record<string, unknown>)[k] = row;
  console.log(`${k.padEnd(8)} ${String(ps.length).padStart(6)}   ${String(row.sameMedianKm).padStart(6)} / ${String(row.sameP90Km).padEnd(6)}              ${String(row.anyMedianKm).padStart(6)} / ${String(row.anyP90Km).padEnd(6)}           ` +
    YEARS.map((y) => `${y}: ${drives[y]!.min} min (${drives[y]!.km} km)`).join('  ') + (argv.includes('--rail') ? `  rail: ${JSON.stringify(rail)}` : ''));
}
// drives between ranks: each hub to its nearest major town and town, each town to its nearest village, by year
const pairs: [string, string][] = [['hub', 'major'], ['hub', 'town'], ['major', 'town'], ['town', 'village'], ['village', 'hamlet']];
const cross: Record<string, unknown> = {};
for (const [a, b] of pairs) {
  const as = [...(byRank.get(a) ?? [])].sort((x, y) => hashU(hashStr(x.id), 2) - hashU(hashStr(y.id), 2)).slice(0, 40);
  const res: Record<number, string> = {};
  for (const y of YEARS) {
    const mins: number[] = [];
    for (const p of as) {
      let best = null as (typeof as)[number] | null, bd = Infinity;
      for (const o of byRank.get(b) ?? []) { if (o.mass !== p.mass) continue; const d = distKm(p.x, p.z, o.x, o.z); if (d < bd) { bd = d; best = o; } }
      const d = best ? w.roadGraph.drive(p.id, best.id, y) : null;
      if (d) mins.push(d.min);
    }
    res[y] = `${Math.round(q(mins, 0.5))} min (p90 ${Math.round(q(mins, 0.9))})`;
  }
  cross[`${a}->${b}`] = res;
  console.log(`${a} to its nearest ${b}: ` + YEARS.map((y) => `${y} ${res[y]}`).join(', '));
}
out.cross = cross;
const j = argv.indexOf('--json');
if (j >= 0) writeFileSync(argv[j + 1]!, JSON.stringify(out, null, 1));
