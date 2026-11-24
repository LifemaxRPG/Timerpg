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
