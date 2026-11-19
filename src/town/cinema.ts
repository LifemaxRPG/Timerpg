import { rand, rngFor } from '../sim/rng';
import { naturalPopulation } from '../sim/simulate';
import { townName } from '../world/names';
import { decadeOf, distinctTaglines, releases, type Film } from './films';

// THE CINEMA. Only a town that grows big enough has one (its natural history reaches CINEMA_POPULATION living people by
// the game's present), and then it has stood on or just off the main street since before the game begins. It is one
// building through the whole century, but its front follows the times (the era of the year you are in): a 1950s picture
// palace, a modernised 1960s-70s front, a 1980s-90s twin or triple, a 2000s multiplex. What plays is the year's made-up
// releases (src/town/films.ts); the marquee spells them and the townspeople talk about them (src/llm/factsheet.ts).

/** Living people the town must reach (at its peak, 1950 to CINEMA_BY) to have a cinema. */
export const CINEMA_POPULATION = 220;
/** The game's present: the year the natural history is run to. */
export const CINEMA_BY = 2025;

export type CinemaEra = 'palace' | 'modern' | 'twin' | 'multiplex';
export const CINEMA_ERAS: readonly CinemaEra[] = ['palace', 'modern', 'twin', 'multiplex'];

/** The front the cinema wears in [year]. */
export const cinemaEra = (year: number): CinemaEra =>
  year < 1960 ? 'palace' : year < 1980 ? 'modern' : year < 2000 ? 'twin' : 'multiplex';

const peaks = new Map<string, number>();
/** The town's largest living population, 1950 to CINEMA_BY, in its natural history (no time travel). */
export function peakPopulation(seed: string): number {
  let p = peaks.get(seed);
  if (p === undefined) { p = Math.max(...naturalPopulation(seed, CINEMA_BY)); peaks.set(seed, p); }
  return p;
}

/** Is this a town big enough for a cinema? */
export const hasCinema = (seed: string): boolean => peakPopulation(seed) >= CINEMA_POPULATION;

/** Screens in [year]: one for the palace and the modernised hall, two then three for the twin, 6-8 for the multiplex. */
export function cinemaScreens(seed: string, year: number): number {
  const era = cinemaEra(year);
  if (era === 'palace' || era === 'modern') return 1;
  if (era === 'twin') return year < 1990 ? 2 : 3;
  return 6 + Math.floor(rand(seed, 'cinema', 'screens') * 3);
}

/** The pictures showing in [year]: one (two in the 1970s, a double bill), one per screen from the twin on. */
export function showingCount(seed: string, year: number): number {
  const era = cinemaEra(year);
  return era === 'modern' && year >= 1970 ? 2 : cinemaScreens(seed, year);
}

/** Made-up house names (no real chains or studios). */
const HOUSES = ['Majestic', 'Bijou', 'Starlite', 'Lyric', 'Capitol', 'Strand', 'Empress', 'Alhambra', 'Avalon', 'Orion', 'Electra',
  'Coronet', 'Princess', 'Vogue', 'Liberty', 'Uptown', 'Gem', 'Tivoli', 'Belvedere', 'Embassy'];

/** The house's own name, kept through the century ("Majestic"; or the town's: "Cedar Falls"). */
export function cinemaHouse(seed: string): string {
  const r = rand(seed, 'cinema', 'house');
  return r < 0.25 ? townName(seed) : HOUSES[Math.floor(rand(seed, 'cinema', 'house-name') * HOUSES.length)]!;
}

/** What the sign says in [year]: "The Majestic", "Majestic Twin", "Majestic 8". */
export function cinemaName(seed: string, year: number): string {
  const house = cinemaHouse(seed);
  const own = house === townName(seed);
  const era = cinemaEra(year);
  const screens = cinemaScreens(seed, year);
  if (era === 'palace') return own ? `The ${house} Palace` : `The ${house}`;
  if (era === 'modern') return own ? `${house} Cinema` : `The ${house}`;
  if (era === 'twin') return `${house} ${screens === 2 ? 'Twin' : 'Triple'}`;
  return `${house} ${own ? 'Cinemas ' : ''}${screens}`;
}

const shuffled = <T>(items: readonly T[], ...key: (string | number)[]): T[] => {
  const r = rngFor('cinema-pick', ...key);
  return items.map((x) => [r(), x] as const).sort((a, b) => a[0] - b[0]).map(([, x]) => x);
};

export interface CinemaProgram {
  name: string;
  era: CinemaEra;
  /** The decade whose posters hang (1950..2020). */
  decade: number;
  year: number;
  screens: number;
  nowShowing: Film[];
  /** Next year's pictures on the coming-attractions cases (at least eight posters in all). */
  comingSoon: Film[];
  /** The marquee's lines, as a letter board spells them (upper case, NOW SHOWING first). */
  marquee: string[];
}

/** The cinema of [seed] in [year]: its name, front, and what plays (the same each time you visit that year). */
export function cinemaProgram(seed: string, year: number): CinemaProgram {
  const picked = shuffled(releases(year), seed, year, 'now').slice(0, showingCount(seed, year));
  const later = shuffled(releases(year + 1), seed, year, 'soon');
  // (no tagline twice on one bill: next year's pictures may share a line with this year's)
  const bill = distinctTaglines([...picked, ...later.slice(0, Math.max(4, 8 - picked.length))], seed, year);
  const now = bill.slice(0, picked.length);
  const comingSoon = bill.slice(picked.length);
  const era = cinemaEra(year);
  const marquee = [era === 'multiplex' ? 'NOW PLAYING' : 'NOW SHOWING', ...now.map((f) => f.title.toUpperCase())];
  if (era === 'palace' || (era === 'modern' && now.length === 1)) marquee.push(`STARRING ${now[0]!.star.toUpperCase()}`);
  return { name: cinemaName(seed, year), era, decade: decadeOf(year), year, screens: cinemaScreens(seed, year), nowShowing: now, comingSoon, marquee };
}
