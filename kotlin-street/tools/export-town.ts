// Exports the whole town as street.json v2 (tools/town-graph.ts): the v1 fields for the spawn junction (shops, streets,
// junction) and `graph`, the walkable town (nodes, roads, rows with their buildings and transitions, landmarks, plaza,
// water, bridges, props, lots). Anything the placement had to nudge, or that does not fit, goes to stderr.
// Usage: npx tsx kotlin-street/tools/export-town.ts [seed] [year] > town.json (default: the town of tools/town-config.ts)
import { TOWN } from './town-config';
import { townGraph } from './town-graph';

const seed = process.argv[2] ?? TOWN.seed;
const year = Number(process.argv[3] ?? TOWN.year);
const g = townGraph(seed, year);
for (const c of g.conflicts) console.error(`town ${seed}: ${c}`);
console.log(JSON.stringify(g.json));
