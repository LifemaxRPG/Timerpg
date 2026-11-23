import type { ArchetypeId } from '../sim/archetype';

// A TOWN'S RECIPE (kotlin-street/docs/TOWNS_EVERYWHERE.md): what the world says about the place a town stands on, given
// to the town generator as parameters. Without a recipe a seed plans its town from the seed alone, as before (gamma, the
// test seeds, every existing test); with one, the generator's seed rolls give way to the place's facts:
//
//  - archetype (src/sim/archetype.ts archetypeOf) and founding year (foundedOf): the place's type and founding;
//  - size class (src/town/plat.ts sizeOf): by rank, from a crossroads hamlet to a hub;
//  - water (src/town/water.ts waterKind): a river through the town only where the world's river runs through it, a
//    lake where the world has one by it, else none;
//  - the railroad (plat.ts): a rail band only where the world's rail line stops;
//  - the founding people (src/sim/worldgen.ts): how many families and businesses the town starts with (its size);
//  - county seat (plat.ts isCountySeat): the biggest place of its county;
//  - the roads in (src/town/farms.ts planFarms): a country road out to each point where a world road crosses the
//    town's edge, so the world's roads and the town's meet there exactly.
//
// The registry is keyed by the town's seed (every generator function takes the seed): the baker registers a place's
// recipe before it plans the town (useRecipe) and the generator's functions look it up (recipeOf). A recipe must be
// registered before anything of its seed is planned: the generator caches by seed.

export type TownRank = 'hamlet' | 'village' | 'town' | 'major' | 'hub';
/** Plat size classes: H a crossroads hamlet, V a village, then the town sizes S, M, L. */
export type RecipeSize = 'H' | 'V' | 'S' | 'M' | 'L';
export type Side = 'N' | 'S' | 'E' | 'W';

/** A road or railroad coming in: the side of the town's ground (plan frame) it crosses and where along it (plan tiles: x
 * on N and S, y on E and W, in the plan's own coordinates; the ground's edge is RING tiles past the plan). */
export interface RecipeEntry {
  /** An expressway is limited access: it crosses the town's ground (its fields) but no town road joins it there. */
  kind: 'expressway' | 'highway' | 'county' | 'local' | 'rail'; side: Side; at: number; road: string;
  /** The year the road was built (Highways v2: a track before), and its route number in the bake year. */
  year?: number; number?: string;
}

export interface TownRecipe {
  seed: string;
  /** The world place (`t:12`, `m:340`, `h:launch/port`). */
  place: string;
  name: string;
  rank: TownRank;
  size: RecipeSize;
  type: ArchetypeId;
  setting?: 'alpine' | 'pass' | 'mining';
  ore?: string;
  water: 'none' | 'river' | 'lake';
  /** A rail line stops here (a rail band in the plan). */
  rail: boolean;
  countySeat: boolean;
  founded: number;
  /** Founding families, singles and businesses (src/sim/worldgen.ts; the default town is 22, 8, 8), and the growth: a
   * multiplier on the newcomers a year and the businesses the town supports (src/sim/simulate.ts; a town 1). */
  people: { families: number; singles: number; businesses: number; growth: number };
  /** Highways v2: the year an expressway or a highway bypass took the through traffic off Main Street (the old way
   * through becomes the business route and its trade declines), and the town's interchange (its strip grows). */
  bypassed?: { year: number; by: string };
  interchange?: { year: number; km: number };
  /** The roads in (filled once the plan's size is known: the baker plans, then places, then sets these). */
  entries?: RecipeEntry[];
}

const RECIPES = new Map<string, TownRecipe>();

/** Register [r] (before its seed is planned). */
export function useRecipe(r: TownRecipe): void { RECIPES.set(r.seed, r); }
/** The recipe of [seed], if one is registered. */
export const recipeOf = (seed: string): TownRecipe | undefined => RECIPES.get(seed);
/** Forget every recipe (tests). */
export function clearRecipes(): void { RECIPES.clear(); }

/** The default founding people by rank (a town is the generator's usual 22 families). */
export const PEOPLE: Record<TownRank, TownRecipe['people']> = {
  hamlet: { families: 4, singles: 2, businesses: 2, growth: 0.2 },
  village: { families: 11, singles: 4, businesses: 4, growth: 0.5 },
  town: { families: 22, singles: 8, businesses: 8, growth: 1 },
  major: { families: 30, singles: 10, businesses: 10, growth: 1.4 },
  hub: { families: 38, singles: 12, businesses: 12, growth: 1.8 },
};
/** The plat's size class by rank. */
export const SIZE: Record<TownRank, RecipeSize> = { hamlet: 'H', village: 'V', town: 'S', major: 'M', hub: 'L' };

/**
 * The passing trade on a town's Main Street in [year] (1 the usual): a bypassed Main Street loses it over 15 years after
 * the bypass opens (to 0.6: the classic decline, shops closing and standing empty), a town with an interchange within
 * 4 km gains on its strip from the interchange's year (to 1.3 over 10 years). Read by the simulation's business rules
 * (src/sim/simulate.ts: fewer businesses supported, more failing).
 */
export function tradeOf(seed: string, year: number): number {
  const r = RECIPES.get(seed);
  if (!r) return 1;
  let t = 1;
  if (r.bypassed && year > r.bypassed.year) t -= 0.4 * Math.min(1, (year - r.bypassed.year) / 15);
  if (r.interchange && r.interchange.km <= 4 && year > r.interchange.year) t += 0.3 * Math.min(1, (year - r.interchange.year) / 10) * (r.bypassed ? 0.5 : 1);
  return t;
}
