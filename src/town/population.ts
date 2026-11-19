import { naturalPopulation } from '../sim/simulate';

// The town's NATURAL HISTORY of population (no time travel, the people only: src/sim/simulate.ts naturalPopulation),
// 1950 to POPULATION_BY, cached per seed (~0.15 s each). The plan's big one-off sites (the cinema, the stores) are
// decided from it, so they are pure functions of the seed like the rest of the plan.

/** The first year of the natural history. */
export const POPULATION_FROM = 1950;
/** The game's present: the year the natural history is run to. */
export const POPULATION_BY = 2025;

const cache = new Map<string, number[]>();

/** Living people in each year POPULATION_FROM..POPULATION_BY (index 0 is 1950). */
export function naturalHistory(seed: string): readonly number[] {
  let p = cache.get(seed);
  if (!p) { p = naturalPopulation(seed, POPULATION_BY, POPULATION_FROM); cache.set(seed, p); }
  return p;
}

/** Living people in [year] of the natural history (held at the ends outside 1950..2025). */
export function populationIn(seed: string, year: number): number {
  const p = naturalHistory(seed);
  return p[Math.max(0, Math.min(p.length - 1, year - POPULATION_FROM))]!;
}

/** The first year from [from] (to POPULATION_BY) whose natural population reaches [n], or undefined. */
export function firstYearReaching(seed: string, n: number, from = POPULATION_FROM): number | undefined {
  for (let y = Math.max(from, POPULATION_FROM); y <= POPULATION_BY; y++) if (populationIn(seed, y) >= n) return y;
  return undefined;
}
