// Exports the whole town as street.json v2 (tools/town-graph.ts): the v1 fields for the spawn junction (shops, streets,
// junction) and `graph`, the walkable town (nodes, roads, rows with their buildings and transitions, landmarks, plaza,
// water, bridges, props, lots), and the yards (tools/town-yards.ts: every lot's front yard and backyard, back lanes and
// verges; props standing in them are dropped). Anything the placement had to nudge, or that does not fit, goes to stderr.
// Usage: npx tsx kotlin-street/tools/export-town.ts [seed] [year] > town.json (default: the town of tools/town-config.ts)
import { TOWN } from './town-config';
import { townGraph } from './town-graph';
import { withYards } from './town-yards';

const seed = process.argv[2] ?? TOWN.seed;
const year = Number(process.argv[3] ?? TOWN.year);
const g = townGraph(seed, year);
for (const c of g.conflicts) console.error(`town ${seed}: ${c}`);
console.log(JSON.stringify(withYards(g)));
