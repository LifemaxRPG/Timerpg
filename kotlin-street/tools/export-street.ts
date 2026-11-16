// Exports a real town's main-street shops (from OUR game logic) as JSON for the Kotlin street renderer.
// Usage: npx tsx kotlin-street/tools/export-street.ts <town seed> <year> > kotlin-street/app/src/main/assets/street.json
import { runTimeline, worldAt } from '../../src/sim';
import { archetypeOf } from '../../src/sim/archetype';
import { layoutTown } from '../../src/town/layout';
import { fullName } from '../../src/sim/types';
import { shopFacade } from '../../src/town/facade';

const seed = process.argv[2] ?? 'alpha';
const year = Number(process.argv[3] ?? 2025);
const w = worldAt(runTimeline({ seed, endYear: year }), year);
const layout = layoutTown(w);
const SIGN: Record<string, string> = {
  Bakery: 'FRESH BREAD', Butcher: 'FRESH MEAT', 'Ice Cream Parlor': 'ICE CREAM', 'Music Store': 'RECORDS', 'Bait Shop': 'BAIT & TACKLE',
  'Clothing Store': 'CLOTHING', 'Dress Shop': 'DRESSES', 'Toy Store': 'TOYS', Gym: 'FITNESS', Bank: '', 'Law Office': '', "Doctor's Office": '',
};
const hash = (s: string): number => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return (h >>> 0); };
const shops = layout.lots
  .filter((l) => l.kind === 'shop' && l.vacantSince === undefined)
  .sort((a, b) => a.y - b.y || a.x - b.x)
  .slice(0, 10)
  .map((l, i) => {
    const b = w.businesses[l.refId]!;
    const owner = b.owner ? w.people[b.owner] : undefined;
    // the very building our game draws for this shop: its sprite parts, bottom to top
    const art = shopFacade(b.kind, (hash(b.id) % 1000000) / 1000000, year);
    return { id: b.id, sprite: `b${i}`, name: b.name, kind: b.kind, owner: owner ? fullName(owner) : '', colour: art.colour, parts: art.layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y })) };
  });
console.log(JSON.stringify({ town: seed, year, type: archetypeOf(seed).label, shops }, null, 2));
