// Usage: npm run demo [seed]
// Finds the person who is mayor in the present, travels back to when they were a child,
// kills them, and prints how the present changes.
import { describeDiff, diffWorlds, fullName, runTimeline, worldAt, type Intervention } from '../src/sim';

const seed = process.argv[2] ?? 'demo';
const PRESENT = 2025;

const base = runTimeline({ seed, endYear: PRESENT });
const now = worldAt(base, PRESENT);
const mayor = now.people[now.mayor!]!;
console.log(`Seed "${seed}". In ${PRESENT} the mayor is ${fullName(mayor)} (born ${mayor.birthYear}).`);

const year = Math.max(1951, mayor.birthYear + 5);
const iv: Intervention = { kind: 'kill', year, targetId: mayor.id };
console.log(`You travel to ${year} and kill ${fullName(mayor)}...\n`);

const alt = runTimeline({ seed, endYear: PRESENT, interventions: [iv] });
const diff = diffWorlds(now, worldAt(alt, PRESENT));
for (const line of describeDiff(diff, now, worldAt(alt, PRESENT))) console.log(' -', line);

console.log('\nChronicle highlights after the murder:');
alt.chronicle
  .filter((e) => e.year >= year && ['election', 'business_closed', 'intervention'].includes(e.kind))
  .slice(0, 12)
  .forEach((e) => console.log(`  ${e.year}: ${e.text}`));
