// Exports the rooms inside the buildings of the two streets (from OUR game logic: src/town/interiors.ts, furnished by
// Wave Function Collapse in src/town/furnish.ts for the people who live or work there), keyed by sprite name (bN) like
// street.json and dialogue.json.
// Usage: npx tsx kotlin-street/tools/export-interiors.ts <town seed> <year> > kotlin-street/app/src/main/assets/interiors.json
import { townStreets } from './town-streets';
import { planInterior } from '../../src/town/interiors';
import { buildingDoor } from '../../src/town/collision';

const seed = process.argv[2] ?? 'alpha';
const year = Number(process.argv[3] ?? 2025);
const t = townStreets(seed, year);
const rooms: Record<string, object> = {};
const r0 = (n: number): number => Math.round(n * 10) / 10;
for (const [sprite, lot] of t.lotOf) {
  const p = planInterior(t.w, { id: lot.slot, title: lot.label, door: buildingDoor(lot), lot });
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
console.log(JSON.stringify({ town: seed, year, rooms }));
