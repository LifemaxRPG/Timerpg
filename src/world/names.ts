import { archetypeOf } from '../sim/archetype';
import { pick, rand, rngFor } from '../sim/rng';

// Town names, pure in the town's seed: "Maplefield", "Cedar Falls", "Port Willow", "Mount Amber".
const FIRST = [
  'Maple', 'Oak', 'Cedar', 'Elm', 'Ash', 'Pine', 'Birch', 'Willow', 'Brook', 'Stone', 'River', 'Fair',
  'Green', 'Hill', 'Lake', 'Clear', 'Silver', 'Golden', 'Red', 'White', 'Black', 'Blue', 'Bell', 'Mill',
  'Rock', 'Spring', 'Sun', 'Wolf', 'Fox', 'Deer', 'Eagle', 'Crow', 'Iron', 'Copper', 'Salt', 'Harbor',
  'Frost', 'Amber', 'Rose', 'Thorn',
] as const;
const ENDINGS = ['field', 'ton', 'ford', 'dale', 'wood', 'haven', 'ville', 'burg', 'port', 'mont', 'stead', 'crest', 'view', 'ridge', 'vale', 'bury', 'ham'] as const;
const SECOND = ['Falls', 'Springs', 'Heights', 'Crossing', 'Junction', 'Hollow', 'Landing', 'Bluffs', 'Meadows', 'Corners', 'Grove', 'Point'] as const;

export function townName(seed: string): string {
  const r = rngFor(seed, 'town-name');
  const style = r();
  // A harbor town sounds like one, a mining town like another (see sim/archetype.ts).
  const t = archetypeOf(seed);
  const first = pick(r, t.names.first);
  if (style < 0.5) {
    // Avoid doubled words like "Brookbrook" or "Stonestone".
    const options = t.names.ending.filter((e) => !first.toLowerCase().includes(e));
    return first + pick(r, options.length ? options : t.names.ending);
  }
  if (style < 0.85) return `${first} ${pick(r, t.names.second)}`;
  return t.id === 'harbor' ? `Port ${first}` : t.id === 'mining' || t.id === 'resort' ? `Mount ${first}` : `${first} ${pick(r, t.names.second)}`;
}

/** The year the town was first settled (well before the game's 1950 start). */
export const townFounded = (seed: string): number => 1830 + Math.floor(rand(seed, 'town-founded') * 100);
