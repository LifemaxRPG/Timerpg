// THE TOWNS ON A DRIVE (kotlin-street/docs/TOWNS_EVERYWHERE.md, "Streaming"): the places a drive from the capital passes,
// for baking just the towns a drive test or a preview needs. The drive: the capital to [--to] (default Evanvale, the drive
// tests' first town), then on to the nearest [--more] places of village rank or above by road, each not yet visited (the
// world's RoadGraph, the fastest car route in [--year]). Prints the chain (stderr) and every place within [--km] (default
// 4) of the drive's cells, hamlets included (stdout, as a --places list for towns-bake.ts).
//
//   npx tsx kotlin-street/tools/towns-route.ts [--to Evanvale] [--more 2] [--km 4] [--year 2025]
import { generateWorld } from '../../src/world/gen/world';
import { W, distKm } from '../../src/world/gen/grid';
import { rankOf } from './towns-site';

const argv = process.argv.slice(2);
const flag = (f: string): string | undefined => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : undefined; };
const year = Number(flag('--year') ?? 2025);
const w = generateWorld(flag('--world') ?? 'alpha');
type P = (typeof w.places)[number];
const cap = w.places.find((p) => p.id === 'h:capital')!;
const first = w.places.find((p) => p.name === (flag('--to') ?? 'Evanvale'))!;
const chain: P[] = [cap, first];
const cells: number[] = [];
const legs: string[] = [];
const go = (a: P, b: P): void => {
  const d = w.roadGraph.drive(a.id, b.id, year);
  if (!d) throw new Error(`no road from ${a.name} to ${b.name}`);
  cells.push(...d.cells);
  legs.push(`${a.name} -> ${b.name} (${b.id}, ${rankOf(b)}): ${d.km.toFixed(1)} km by road, ${d.min.toFixed(0)} min, ${distKm(a.x, a.z, b.x, b.z).toFixed(1)} km straight`);
};
go(cap, first);
for (let k = 0; k < Number(flag('--more') ?? 2); k++) {
  const at = chain[chain.length - 1]!;
  let best: { p: P; min: number } | null = null;
  for (const p of w.places) {
    if (chain.includes(p) || p.mass !== at.mass || rankOf(p) === 'hamlet' || rankOf(p) === 'hub' || distKm(at.x, at.z, p.x, p.z) > 45) continue;
    const d = w.roadGraph.drive(at.id, p.id, year);
    if (d && (!best || d.min < best.min)) best = { p, min: d.min };
  }
  if (!best) break;
  go(at, best.p);
  chain.push(best.p);
}
for (const l of legs) console.error(l);
const R = Number(flag('--km') ?? 4);
const near = w.places.filter((p) => p.id !== cap.id && (chain.includes(p) || cells.some((c) => distKm((c % W) + 0.5, Math.floor(c / W) + 0.5, p.x, p.z) < R)));
for (const p of near) console.error(`  ${p.id}\t${p.name}\t${rankOf(p)}${chain.includes(p) ? '\t(on the chain)' : ''}`);
console.log(near.map((p) => p.id).join(','));
