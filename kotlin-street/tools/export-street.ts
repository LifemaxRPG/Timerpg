// Exports a real town's streets (from OUR game logic) as JSON for the Kotlin street renderer.
// Usage: npx tsx kotlin-street/tools/export-street.ts <town seed> <year> > kotlin-street/app/src/main/assets/street.json
//
// What it exports (see tools/town-streets.ts, which tools/export-dialogue.ts shares):
//  - streets[0], the main street, and streets[1], the cross street, by sprite name in order;
//  - junction: the other buildings the corner-turn scene shows;
//  - shops: every building above, once, for tools/make_buildings.py (each has its sprite name bN).
import { townStreets } from './town-streets';

const seed = process.argv[2] ?? 'alpha';
const year = Number(process.argv[3] ?? 2025);

console.log(JSON.stringify(townStreets(seed, year).json, null, 2));
