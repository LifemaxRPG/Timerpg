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
  const first = pick(r, FIRST);
  if (style < 0.5) {
    // Avoid doubled words like "Brookbrook" or "Stonestone".
    const options = ENDINGS.filter((e) => !first.toLowerCase().includes(e));
    return first + pick(r, options);
  }
  if (style < 0.8) return `${first} ${pick(r, SECOND)}`;
  return style < 0.9 ? `Port ${first}` : `Mount ${first}`;
}

/** The year the town was first settled (well before the game's 1950 start). */
export const townFounded = (seed: string): number => 1830 + Math.floor(rand(seed, 'town-founded') * 100);
