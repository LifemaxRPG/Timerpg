import { priceIndex } from '../economy';
import { clamp01, rand } from '../sim/rng';

export const skills = ['driving', 'mechanics', 'bookkeeping', 'cooking', 'carpentry', 'sales', 'first_aid',
  'lockpicking', 'hot_wiring', 'safecracking', 'forgery'] as const;
export type Skill = typeof skills[number];

export const jobRequirements = {
  mechanic_helper: { skill: 'mechanics', minimum: .3 },
  night_clerk: { skill: 'bookkeeping', minimum: .25 },
  shop_clerk: { skill: 'sales', minimum: .1 },
  labourer: { skill: 'carpentry', minimum: .1 },
} satisfies Record<string, { skill: Skill; minimum: number }>;

const training = {
  shift: [1880, 0, .045],
  correspondence: [1890, 45, .09],
  night_school: [1880, 65, .11],
  driving_school: [1915, 125, .14],
  library_book: [1880, 0, .045],
  tutor: [1880, 90, .13],
} as const;
export type Training = keyof typeof training;

export function skillAvailable(skill: Skill, year: number): boolean {
  return priceIndex(year) !== null && skills.includes(skill)
    && (skill !== 'hot_wiring' || year >= 1912) && (skill !== 'driving' || year >= 1905);
}

export function trainingQuote(skill: Skill, method: Training, year: number) {
  const index = priceIndex(year);
  if (index === null || !skillAvailable(skill, year) || !Object.prototype.hasOwnProperty.call(training, method)) return null;
  const [from, cents, gain] = training[method];
  if (year < from || (method === 'driving_school' && skill !== 'driving')) return null;
  return { costCents: cents === 0 ? 0 : Math.max(1, Math.round(cents * index / priceIndex(1952)!)), gain };
}

export function practice(skill: Skill, level: number, method: Training, year: number, sessions = 1): number | null {
  const quote = trainingQuote(skill, method, year);
  if (!quote || !Number.isFinite(level) || !Number.isSafeInteger(sessions) || sessions < 0) return null;
  return 1 - (1 - clamp01(level)) * (1 - quote.gain) ** sessions;
}
