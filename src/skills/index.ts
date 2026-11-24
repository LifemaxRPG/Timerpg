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

const shiftSkills = {
  garage_sweeper: 'mechanics', mechanic_helper: 'mechanics', night_clerk: 'bookkeeping',
  shop_clerk: 'sales', stock_worker: 'sales', labourer: 'carpentry',
  dishwasher: 'cooking',
} satisfies Record<string, Skill>;

export function practiceShift(role: string, level: number, year: number) {
  if (!Object.prototype.hasOwnProperty.call(shiftSkills, role)) return null;
  const skill = shiftSkills[role as keyof typeof shiftSkills];
  const nextLevel = practice(skill, level, 'shift', year);
  return nextLevel === null ? null : { skill, level: nextLevel };
}

const botchEffects: Partial<Record<Skill, 'noise' | 'evidence'>> = {
  lockpicking: 'noise', hot_wiring: 'noise', safecracking: 'noise', forgery: 'evidence',
};

export function attempt(skill: Skill, level: number, difficulty: number, seed: string, key: string) {
  if (!skills.includes(skill) || !Number.isFinite(level) || !Number.isFinite(difficulty)) return null;
  const margin = clamp01(level) - clamp01(difficulty);
  const successChance = .05 + .9 * clamp01(.5 + margin * .65);
  const botchChance = Math.min(1 - successChance, .04 + .22 * Math.max(0, -margin));
  const roll = rand(seed, 'skill_attempt', skill, key);
  if (roll < successChance) return { outcome: 'success', effect: null } as const;
  if (roll >= 1 - botchChance) return { outcome: 'botch', effect: botchEffects[skill] ?? null } as const;
  return { outcome: 'failure', effect: null } as const;
}

export function safeProfile(year: number): { kind: 'combination' | 'time_lock'; difficulty: number } | null {
  if (priceIndex(year) === null) return null;
  return year < 1960 ? { kind: 'combination', difficulty: .55 } : { kind: 'time_lock', difficulty: .83 };
}
