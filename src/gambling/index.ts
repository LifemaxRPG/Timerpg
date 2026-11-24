import { priceIndex } from '../economy';
import { clamp01, rand } from '../sim/rng';

const games = {
  draw_poker: { stake: 500, tiers: [
    { chance: .1, multiple: 4.4, result: 'strong hand' },
    { chance: .28, multiple: 2, result: 'winning pair' },
  ] },
  dice: { stake: 100, tiers: [{ chance: .5, multiple: 2, result: 'winning roll' }] },
  wheel: { stake: 100, tiers: [{ chance: 1 / 8, multiple: 8, result: 'winning segment' }] },
  slots: { stake: 25, tiers: [{ chance: .1, multiple: 10, result: 'matching reels' }] },
} as const;
export type Game = keyof typeof games;

const validStake = (cents: number) => Number.isSafeInteger(cents) && cents > 0 && cents <= 1000000;
const validDay = (day: number) => Number.isSafeInteger(day) && day >= 0;

export function suggestedStake(game: Game, year: number): number | null {
  const index = priceIndex(year);
  if (index === null || !Object.prototype.hasOwnProperty.call(games, game)) return null;
  return Math.max(1, Math.round(games[game].stake * index / priceIndex(1952)!));
}

export function play(game: Game, stakeCents: number, crooked: boolean, skill: number, seed: string, key: string) {
  if (!validStake(stakeCents) || !Number.isFinite(skill) || !Object.prototype.hasOwnProperty.call(games, game)) return null;
  const roll = rand(seed, 'gambling_game', game, key);
  const spottedRigging = crooked && rand(seed, 'gambling_notice', game, key) < clamp01(skill) * .75;
  let ceiling = 0;
  for (const tier of games[game].tiers) {
    ceiling += tier.chance * (crooked ? .68 : 1);
    if (roll < ceiling) {
      const payoutCents = Math.round(stakeCents * tier.multiple);
      return { result: tier.result, payoutCents, netCents: payoutCents - stakeCents, spottedRigging };
    }
  }
  return { result: 'loss', payoutCents: 0, netCents: -stakeCents, spottedRigging };
}

export type Sport = 'ball_game' | 'horse_race';

export function fixture(townId: string, sport: Sport, day: number, seed: string) {
  if (!validDay(day) || (sport !== 'ball_game' && sport !== 'horse_race')) return null;
  const id = JSON.stringify([townId, sport, day]);
  const labels = sport === 'ball_game' ? ['Home nine', 'Visitors'] : ['Copper Bell', 'Field Lantern', 'Silver Oat'];
  const weights = labels.map((_, i) => .5 + rand(seed, 'sport_odds', id, i));
  const total = weights.reduce((sum, weight) => sum + weight, 0);
  const entrants = labels.map((label, i) => ({ id: String(i), label, probability: weights[i]! / total }));
  const roll = rand(seed, 'sport_result', id);
  let ceiling = 0;
  const winner = entrants.find(entrant => {
    ceiling += entrant.probability;
    return roll < ceiling;
  }) ?? entrants[entrants.length - 1]!;
  const homeRuns = Math.floor(rand(seed, 'sport_runs', id) * 7);
  const score = sport !== 'ball_game' ? null
    : winner.id === '0' ? [homeRuns + 2, homeRuns] : [homeRuns, homeRuns + 2];
  return { id, day, sport, entrants, winnerId: winner.id, score };
}

export function settleBet(event: NonNullable<ReturnType<typeof fixture>>, entrantId: string, stakeCents: number) {
  const entrant = event.entrants.find(row => row.id === entrantId);
  if (!entrant || !validStake(stakeCents)) return null;
  const cut = .1;
  const grossMultiple = (1 - cut) / entrant.probability;
  const payoutCents = entrantId === event.winnerId ? Math.round(stakeCents * grossMultiple) : 0;
  return { payoutCents, netCents: payoutCents - stakeCents, grossMultiple, cut };
}

export function dailyNumbers(townId: string, day: number, seed: string, runnerId: string) {
  if (!validDay(day)) return null;
  const result = Math.floor(rand(seed, 'daily_numbers', townId, day) * 1000);
  return { day, number: String(result).padStart(3, '0'), runnerId, payoutMultiple: 600 };
}

export function settleNumbers(draw: NonNullable<ReturnType<typeof dailyNumbers>>, pick: string, stakeCents: number) {
  if (!/^\d{3}$/.test(pick) || !validStake(stakeCents)) return null;
  const payoutCents = pick === draw.number ? stakeCents * draw.payoutMultiple : 0;
  return { payoutCents, netCents: payoutCents - stakeCents, runnerId: draw.runnerId };
}

export type Jurisdiction = 'town' | 'licensed_district';
export type Activity = Game | 'sports_betting' | 'horse_betting' | 'lottery' | 'numbers';

export function legality(activity: Activity, jurisdiction: Jurisdiction, year: number) {
  if (priceIndex(year) === null || (jurisdiction !== 'town' && jurisdiction !== 'licensed_district')) return null;
  const licensedDistrict = jurisdiction === 'licensed_district';
  const licensedFrom: Record<Activity, number> = {
    draw_poker: 1931, dice: 1931, wheel: 1931, slots: 1931,
    sports_betting: 1975, horse_betting: 1933, lottery: 1965, numbers: Infinity,
  };
  if (!Object.prototype.hasOwnProperty.call(licensedFrom, activity)) return null;
  const publicLottery = activity === 'lottery' && year >= 1965;
  const licensed = publicLottery || (licensedDistrict && year >= licensedFrom[activity]);
  return { runLegalWithLicence: licensed, joinLegalAtLicensedVenue: licensed };
}

export type Debt = Readonly<{
  debtorId: string; creditorId: string; dueDay: number; remainingCents: number;
}>;

export function debtStatus(debt: Debt, day: number) {
  if (!validDay(day) || !validDay(debt.dueDay) || !Number.isSafeInteger(debt.remainingCents) || debt.remainingCents < 0) return null;
  const daysLate = debt.remainingCents > 0 ? Math.max(0, day - debt.dueDay) : 0;
  const event = daysLate >= 14 ? 'escalation' : daysLate >= 7 ? 'visit' : daysLate >= 1 ? 'warning' : null;
  return { ...debt, daysLate, event };
}
