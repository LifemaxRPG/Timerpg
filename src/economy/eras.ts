export const FIRST_YEAR = 1880;
export const LAST_YEAR = 2030;
export const REFERENCE_YEAR = 2025;

type Anchor = readonly [year: number, value: number];

export function interpolate(anchors: readonly Anchor[], year: number): number {
  let [leftYear, leftValue] = anchors[0]!;
  if (year <= leftYear) return leftValue;
  for (const [rightYear, rightValue] of anchors.slice(1)) {
    if (year <= rightYear) {
      return leftValue + (rightValue - leftValue) * (year - leftYear) / (rightYear - leftYear);
    }
    leftYear = rightYear;
    leftValue = rightValue;
  }
  return leftValue;
}

// Authored gameplay index, with 2025 = 1. The Depression and postwar corrections are intentional.
const PRICE_ANCHORS: readonly Anchor[] = [
  [1880, .047], [1897, .041], [1914, .048], [1917, .057], [1920, .087],
  [1925, .065], [1929, .066], [1933, .050], [1939, .053], [1942, .062],
  [1946, .070], [1948, .081], [1950, .082], [1952, .085], [1960, .096],
  [1968, .113], [1973, .143], [1978, .207], [1982, .305], [1990, .385],
  [2000, .498], [2010, .630], [2020, .775], [2022, .900], [2025, 1], [2030, 1.13],
];

export function validYear(year: number): boolean {
  return Number.isInteger(year) && year >= FIRST_YEAR && year <= LAST_YEAR;
}

export function priceIndex(year: number): number | null {
  return validYear(year) ? interpolate(PRICE_ANCHORS, year) : null;
}

const WAGE_YEARS = [1880, 1925, 1929, 1933, 1946, 1952, 1968, 1978, 1990, 2010, 2025, 2030] as const;
type RatesFor<Years extends readonly number[]> = { readonly [Index in keyof Years]: number };
type JobSpec = readonly [shiftHours: number, hourlyCents: RatesFor<typeof WAGE_YEARS>];

const JOBS = {
  odd_jobs: [2, [8, 30, 33, 24, 50, 75, 145, 275, 475, 850, 1500, 1690]],
  shop_clerk: [6, [11, 39, 42, 32, 66, 90, 180, 315, 560, 1010, 1750, 1975]],
  labourer: [8, [14, 48, 52, 36, 86, 120, 250, 510, 890, 1490, 2320, 2610]],
  mechanic: [8, [22, 65, 74, 50, 114, 155, 310, 660, 1240, 2010, 3050, 3440]],
  clerk: [8, [16, 52, 56, 40, 88, 125, 230, 475, 875, 1510, 2380, 2680]],
  nurse: [8, [17, 44, 48, 36, 97, 140, 295, 730, 1520, 2760, 4050, 4575]],
  teacher: [8, [21, 57, 62, 44, 103, 155, 355, 775, 1510, 2520, 3670, 4140]],
  police_officer: [8, [20, 59, 65, 47, 108, 155, 350, 800, 1570, 2630, 3870, 4360]],
} satisfies Record<string, JobSpec>;

export type Job = keyof typeof JOBS;

export function wage(job: string, year: number): { hourlyCents: number; shiftHours: number; shiftCents: number } | null {
  const key = job.trim().toLowerCase().replace(/[ -]+/g, '_');
  const canonical = key === 'laborer' ? 'labourer' : key;
  if (!validYear(year) || !Object.prototype.hasOwnProperty.call(JOBS, canonical)) return null;
  const [shiftHours, rates] = JOBS[canonical as Job];
  const hourlyCents = Math.round(interpolate(WAGE_YEARS.map((at, i) => [at, rates[i]!] as const), year));
  return { hourlyCents, shiftHours, shiftCents: hourlyCents * shiftHours };
}

export function formatMoney(cents: number, year: number): string | null {
  if (!Number.isSafeInteger(cents) || !validYear(year)) return null;
  const sign = cents < 0 ? '-' : '';
  const amount = Math.abs(cents);
  if (amount > 0 && amount < 100 && year < 1980) return `${sign}${amount}¢`;
  return `${sign}$${Math.floor(amount / 100)}.${String(amount % 100).padStart(2, '0')}`;
}
