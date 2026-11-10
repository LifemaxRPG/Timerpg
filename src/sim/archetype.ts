import { rand } from './rng';

// TOWN TYPES. Every town has a character chosen from its seed, and the character changes the rules of
// its history: which shops open, how fast people move in (and when), how well off they are, how much
// commerce the town supports. A mining town booms and then empties; a resort grows after the
// highways; a farm town stays small. The same seed is always the same type.

export type ArchetypeId = 'farm' | 'harbor' | 'mill' | 'mining' | 'resort' | 'suburb';

export interface Archetype {
  id: ArchetypeId;
  label: string;
  /** One line shown under the town's name on arrival. */
  blurb: string;
  /** Relative likelihood of each kind of shop (kinds not listed are rare, weight 0.3). */
  shops: Record<string, number>;
  /** Multiplier on how many people move in a given year (1 = baseline). */
  immigration: (year: number) => number;
  /** Multiplier on what people earn and arrive with. */
  wealth: number;
  /** Multiplier on how many businesses the town supports. */
  commerce: number;
  /** Words that make a name sound right for this kind of town. */
  names: { first: readonly string[]; ending: readonly string[]; second: readonly string[] };
}

export const ARCHETYPES: Record<ArchetypeId, Archetype> = {
  farm: {
    id: 'farm', label: 'Farm town', blurb: 'A quiet farming community',
    shops: { Bakery: 3, Butcher: 3, 'Clothing Store': 2, 'Bait Shop': 1.5, Bank: 1.2, "Doctor's Office": 1, 'Toy Store': 1 },
    immigration: () => 0.6, wealth: 0.85, commerce: 0.85,
    names: { first: ['Green', 'Fair', 'Meadow', 'Oak', 'Elm', 'Clear', 'Spring', 'Rose'], ending: ['field', 'dale', 'stead', 'ford', 'ton', 'vale'], second: ['Meadows', 'Corners', 'Grove', 'Crossing'] },
  },
  harbor: {
    id: 'harbor', label: 'Harbor town', blurb: 'A salt-aired harbor town',
    shops: { 'Bait Shop': 5, Butcher: 2.5, 'Ice Cream Parlor': 2, Bakery: 2, 'Clothing Store': 1.5, Bank: 1.2 },
    immigration: () => 0.9, wealth: 0.95, commerce: 1,
    names: { first: ['Salt', 'Harbor', 'Blue', 'Wolf', 'Eagle', 'Gull', 'Anchor', 'Tide'], ending: ['port', 'haven', 'point', 'stead'], second: ['Landing', 'Point', 'Bluffs', 'Harbor'] },
  },
  mill: {
    id: 'mill', label: 'Mill town', blurb: 'A mill town built around its works',
    shops: { 'Clothing Store': 3, Gym: 2, Bank: 2, Bakery: 2, Butcher: 2, "Doctor's Office": 1.5, 'Law Office': 1 },
    immigration: (y) => (y < 1980 ? 1.3 : 0.55), wealth: 1, commerce: 1.1,
    names: { first: ['Mill', 'River', 'Brook', 'Stone', 'Iron', 'Fall', 'Bridge'], ending: ['ford', 'ton', 'burg', 'ham'], second: ['Falls', 'Junction', 'Springs', 'Crossing'] },
  },
  mining: {
    id: 'mining', label: 'Mining town', blurb: 'A rough mining town, boom or bust',
    shops: { Gym: 2.5, Bank: 2, Butcher: 2.5, 'Clothing Store': 2, 'Law Office': 1.5, "Doctor's Office": 1.5 },
    immigration: (y) => (y < 1975 ? 1.7 : 0.35), wealth: 0.8, commerce: 0.9,
    names: { first: ['Iron', 'Copper', 'Rock', 'Black', 'Silver', 'Golden', 'Stone', 'Thorn'], ending: ['ridge', 'mont', 'burg', 'crest'], second: ['Heights', 'Hollow', 'Bluffs', 'Ridge'] },
  },
  resort: {
    id: 'resort', label: 'Resort town', blurb: 'A lakeside resort town',
    shops: { 'Ice Cream Parlor': 4, 'Dress Shop': 3, 'Clothing Store': 3, 'Toy Store': 2, 'Music Store': 2, Bakery: 2 },
    immigration: (y) => (y < 1965 ? 0.8 : 1.5), wealth: 1.35, commerce: 1.25,
    names: { first: ['Lake', 'Sun', 'Clear', 'Blue', 'Silver', 'Rose', 'Amber', 'Bell'], ending: ['view', 'crest', 'vale', 'haven'], second: ['Springs', 'Heights', 'Point', 'Bluffs'] },
  },
  suburb: {
    id: 'suburb', label: 'Commuter suburb', blurb: 'A tidy commuter suburb',
    shops: { Bank: 2, 'Law Office': 2, "Doctor's Office": 3, Gym: 3, 'Toy Store': 2.5, Bakery: 2, 'Music Store': 1.5 },
    immigration: (y) => (y < 1960 ? 0.7 : 1.8), wealth: 1.2, commerce: 1.1,
    names: { first: ['Maple', 'Cedar', 'Pine', 'Birch', 'Willow', 'Hill', 'Frost', 'Ash'], ending: ['wood', 'view', 'ridge', 'vale', 'ville'], second: ['Heights', 'Grove', 'Hollow', 'Meadows'] },
  },
};

const ORDER: ArchetypeId[] = ['farm', 'harbor', 'mill', 'mining', 'resort', 'suburb'];

/** The type of the town with this seed (equal chances). */
export function archetypeOf(seed: string): Archetype {
  return ARCHETYPES[ORDER[Math.floor(rand(seed, 'archetype') * ORDER.length)]!];
}

/** Pick a kind of shop for this town: its specialties are likelier. `r` is a 0..1 roll. */
export function pickShop(a: Archetype, kinds: readonly string[], r: number): string {
  const weights = kinds.map((k) => a.shops[k] ?? 0.3);
  let t = r * weights.reduce((x, y) => x + y, 0);
  for (let i = 0; i < kinds.length; i++) { t -= weights[i]!; if (t < 0) return kinds[i]!; }
  return kinds[kinds.length - 1]!;
}

/** The year the town was first settled (well before the game's 1950 start). */
export const foundedOf = (seed: string): number => 1830 + Math.floor(rand(seed, 'town-founded') * 100);
