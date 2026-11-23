// A world place's town recipe for the whole-town exporters (export-town.ts, export-dialogue.ts, export-interiors.ts): when
// TOWN_RECIPE names a recipe's JSON file (src/town/recipe.ts; tools/towns-bake.ts writes them), it is registered before
// anything is planned, so the exporters export that place's town under its seed. Imported first by each exporter.
import { readFileSync } from 'node:fs';
import { useRecipe, type TownRecipe } from '../../src/town/recipe';

const file = process.env.TOWN_RECIPE;
if (file) useRecipe(JSON.parse(readFileSync(file, 'utf8')) as TownRecipe);
