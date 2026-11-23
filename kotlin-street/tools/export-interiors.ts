// Exports the rooms inside the buildings of the two streets (from OUR game logic: src/town/interiors.ts, furnished by
// Wave Function Collapse in src/town/furnish.ts for the people who live or work there), keyed by sprite name (bN) like
// street.json and dialogue.json.
// Usage: npx tsx kotlin-street/tools/export-interiors.ts <town seed> <year> > kotlin-street/app/src/main/assets/interiors.json
//        npx tsx kotlin-street/tools/export-interiors.ts <town seed> <year> --all [--street <street.json v2>]
// --street: the town's street.json v2, for its stores' sprites (graph.landmarks): each store gets its room too (the
// stockroom, aisles and checkouts of src/town/interiors.ts), so the open world's "Go inside" works at its doors.
// --all: the rooms of every building of the town (tools/town-whole.ts: the same sprite names as street.json v2 and the
// whole-town dialogue.json); the file says `version: 2`. Without it, the two streets' buildings, as before.
import './town-recipe-env';
import { townStreets } from './town-streets';
import { wholeTown } from './town-whole';
import { planInterior } from '../../src/town/interiors';
import { buildingDoor, civicDoor } from '../../src/town/collision';
import { readFileSync } from 'node:fs';
import type { Entrance } from '../../src/town/interiors';

const argv = process.argv.slice(2);
const flag = (f: string): string | undefined => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : undefined; };
const pos = argv.filter((a, i) => !a.startsWith('--') && argv[i - 1] !== '--street');
const seed = pos[0] ?? 'alpha';
const year = Number(pos[1] ?? 2025);
const all = argv.includes('--all');
const t = all ? wholeTown(seed, year) : townStreets(seed, year);
const rooms: Record<string, object> = {};
const r0 = (n: number): number => Math.round(n * 10) / 10;
const entrances: [string, Entrance][] = [...t.lotOf].map(([sprite, lot]) => [sprite, { id: lot.slot, title: lot.label, door: buildingDoor(lot), lot }]);
// the stores (their sprites from the town's export: its landmarks)
const street = flag('--street');
if (all && street) {
  const STORES = new Set(['generalstore', 'market', 'supermarket', 'superstore']);
  const lms = (JSON.parse(readFileSync(street, 'utf8')).graph?.landmarks ?? []) as { id: string; sprite: string }[];
  for (const l of t.layout.landmarks) {
    if (!STORES.has(l.id)) continue;
    const sprite = lms.find((m) => m.id === `landmark:${l.id}`)?.sprite;
    if (sprite) entrances.push([sprite, { id: `civic:${l.id}`, title: l.name, door: civicDoor(l), landmark: l }]);
  }
}
for (const [sprite, e] of entrances) {
  const p = planInterior(t.w, e);
  rooms[sprite] = {
    title: p.title, use: p.use, template: p.template, width: p.width, height: p.height,
    floor: p.floor.replace('interior_', ''),
    walls: p.walls.map((w) => [w.x, w.y, w.w, w.h]),
    areas: p.areas.map((a) => ({ name: a.name, rect: [a.rect.x, a.rect.y, a.rect.w, a.rect.h], floor: a.floor?.replace('interior_', '') ?? null })),
    furniture: p.furniture.map((f) => {
      const ft = f.foot ?? f;
      return { key: f.key.replace('interior_', ''), at: [r0(f.x), r0(f.y), f.w, f.h], foot: [ft.x, ft.y, ft.w, ft.h], solid: f.solid };
    }),
    spawn: [p.spawn.x, p.spawn.y], exit: [p.exit.x, p.exit.y],
  };
}
console.log(JSON.stringify(all ? { version: 2, town: seed, year, rooms } : { town: seed, year, rooms }));
