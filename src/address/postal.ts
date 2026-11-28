import { rand } from '../sim/rng';
import type { PostalEraInfo, TownAddressBook } from './types';

/** Year 5-digit postal codes (ZIP codes) were introduced. None appear before this year. */
export const POSTAL_CODE_INTRODUCED_YEAR = 1963;

/** Year 9-digit postal extensions (ZIP+4) were introduced. */
export const POSTAL_PLUS4_INTRODUCED_YEAR = 1983;

/** Region prefix digit (1..9) keyed by town archetype/setting so towns in the same region share plausible postal prefixes. */
const ARCHETYPE_REGION_DIGITS: Record<string, readonly number[]> = {
  harbor: [2, 9],
  farm: [4, 5, 6],
  mill: [1, 3, 4],
  mining: [7, 8],
  resort: [2, 8, 9],
  suburb: [1, 3, 4, 6],
  coastal: [2, 9],
};

/** District -> postal zone suffix group for multi-district towns and Dove Beach. */
const DISTRICT_GROUP_LARGE: Record<string, string> = {
  core: '01',
  square: '01',
  civic: '01',
  westmain: '01',
  old: '02',
  river: '02',
  rail: '03',
  worker: '03',
  strip: '04',
  postwar: '04',
  heights: '04',
  late: '05',
  edge: '05',
  grounds: '06',
  farms: '09',
  // Dove Beach districts
  harbour: '01',
  oldtown: '01',
  mills: '01',
  newtown: '02',
  strand: '02',
  crown: '03',
  ridge: '03',
  fellside: '04',
  marsh: '04',
  uplands: '04',
  eastcove: '05',
  head: '05',
};

const DISTRICT_GROUP_SMALL: Record<string, string> = {
  farms: '02',
};

/** Describe which postal code era applies in a given year. */
export function postalEraInfo(year: number): PostalEraInfo {
  if (year < POSTAL_CODE_INTRODUCED_YEAR) {
    return { introducedYear: POSTAL_CODE_INTRODUCED_YEAR, active: false, format: 'none' };
  }
  if (year < POSTAL_PLUS4_INTRODUCED_YEAR) {
    return { introducedYear: POSTAL_CODE_INTRODUCED_YEAR, active: true, format: 'five-digit' };
  }
  return { introducedYear: POSTAL_CODE_INTRODUCED_YEAR, active: true, format: 'five-digit-plus4' };
}

/** Compute the deterministic 3-digit town postal prefix and per-district 2-digit suffixes. */
export function buildPostalTable(
  seed: string,
  archetype: string,
  districts: readonly string[],
  multiDistrict: boolean,
): { postalPrefix: string; districtPostal: Record<string, string> } {
  const regionChoices = ARCHETYPE_REGION_DIGITS[archetype] ?? [4, 5];
  const d0 = regionChoices[Math.floor(rand(seed, 'postal', 'region') * regionChoices.length)]!;
  // 2-digit sectional center (01..98), deterministic from seed
  const sc = 1 + Math.floor(rand(seed, 'postal', 'sc') * 98);
  const postalPrefix = `${d0}${String(sc).padStart(2, '0')}`;

  // Base zone offset for the town (00..70 in steps of 10 so distinct towns sharing a prefix rarely collide)
  const baseZone = Math.floor(rand(seed, 'postal', 'zone') * 8) * 10;
  const districtPostal: Record<string, string> = {};

  for (const dist of districts) {
    const rawOffset = multiDistrict
      ? Number(DISTRICT_GROUP_LARGE[dist] ?? '01')
      : Number(DISTRICT_GROUP_SMALL[dist] ?? '01');
    const code = Math.min(99, baseZone + rawOffset);
    districtPostal[dist] = String(code).padStart(2, '0');
  }
  if (!districtPostal['farms']) {
    const rawOffset = multiDistrict ? 9 : 2;
    districtPostal['farms'] = String(Math.min(99, baseZone + rawOffset)).padStart(2, '0');
  }
  return { postalPrefix, districtPostal };
}

/** Return the 5-digit postal code for a district in a given year, or null before postal codes existed. */
export function postalCodeFor(
  book: Pick<TownAddressBook, 'postalPrefix' | 'districtPostal'>,
  district: string,
  year: number,
): string | null {
  if (year < POSTAL_CODE_INTRODUCED_YEAR) return null;
  const suffix = book.districtPostal[district] ?? Object.values(book.districtPostal)[0] ?? '01';
  return `${book.postalPrefix}${suffix}`;
}

/** Return the 9-digit ZIP+4 postal code from 1983 onward, or null before then. */
export function postalCodePlus4For(
  book: Pick<TownAddressBook, 'seed' | 'postalPrefix' | 'districtPostal'>,
  district: string,
  streetId: string,
  houseNumber: number,
  year: number,
): string | null {
  if (year < POSTAL_PLUS4_INTRODUCED_YEAR) return null;
  const base = postalCodeFor(book, district, year);
  if (!base) return null;
  const sector = 10 + Math.floor(rand(book.seed, 'zip4-sector', streetId) * 89);
  const blockSegment = Math.min(99, Math.max(1, Math.floor(houseNumber / 100) * 10 + Math.floor((houseNumber % 100) / 10)));
  return `${base}-${String(sector).padStart(2, '0')}${String(blockSegment).padStart(2, '0')}`;
}
