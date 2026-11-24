import { expect, it } from 'vitest';
import { dailyNumbers, debtStatus, fixture, legality, play, settleBet, settleNumbers, suggestedStake } from './index';

it('returns close to fair dice odds and makes crooked tables pay less', () => {
  const returns = [0, 0];
  for (let i = 0; i < 10000; i++) {
    returns[0]! += play('dice', 100, false, 0, String(i), 'roll')!.payoutCents;
    returns[1]! += play('dice', 100, true, 0, String(i), 'roll')!.payoutCents;
  }
  expect(returns[0]! / 1000000).toBeGreaterThan(.96);
  expect(returns[0]! / 1000000).toBeLessThan(1.04);
  expect(returns[1]!).toBeLessThan(returns[0]! * .8);
});

it('quotes the period poker stake and gives skilled players a chance to notice rigging', () => {
  expect(suggestedStake('draw_poker', 1952)).toBe(500);
  expect(suggestedStake('draw_poker', 2025)!).toBeGreaterThan(500);
  const outcomes = Array.from({ length: 40 }, (_, i) => play('draw_poker', 500, false, 1, 'town', String(i))!);
  expect(outcomes.some(row => row.payoutCents === 2200)).toBe(true);
  expect(outcomes.every(row => !row.spottedRigging)).toBe(true);
  expect(Array.from({ length: 40 }, (_, i) => play('slots', 25, true, 1, 'town', String(i)))
    .some(row => row?.spottedRigging)).toBe(true);
});

it('shares seeded sport fixtures and settles a bookie cut', () => {
  const ball = fixture('town', 'ball_game', 10, 'seed')!;
  expect(ball.score).toHaveLength(2);
  const race = fixture('town', 'horse_race', 10, 'seed')!;
  expect(race.entrants).toHaveLength(3);
  expect(race).toEqual(JSON.parse(JSON.stringify(fixture('town', 'horse_race', 10, 'seed'))));
  expect(race.entrants.reduce((sum, row) => sum + row.probability, 0)).toBeCloseTo(1);
  const quoted = settleBet(race, race.winnerId, 500)!;
  expect(quoted.cut).toBe(.1);
  expect(quoted.grossMultiple * race.entrants.find(row => row.id === race.winnerId)!.probability).toBeCloseTo(.9);
  expect(settleBet(race, race.entrants.find(row => row.id !== race.winnerId)!.id, 500)?.payoutCents).toBe(0);
});

it('settles the daily three-digit draw with its runner', () => {
  const draw = dailyNumbers('town', 20, 'seed', 'runner')!;
  expect(draw.number).toMatch(/^\d{3}$/);
  expect(draw).toEqual(dailyNumbers('town', 20, 'seed', 'runner'));
  expect(settleNumbers(draw, draw.number, 5)).toMatchObject({ payoutCents: 3000, runnerId: 'runner' });
  expect(settleNumbers(draw, 'wrong', 5)).toBeNull();
});

it('changes legal play by year and jurisdiction and escalates debts by days late', () => {
  expect(legality('slots', 'licensed_district', 1925)?.runLegalWithLicence).toBe(false);
  expect(legality('slots', 'licensed_district', 1952)?.joinLegalAtLicensedVenue).toBe(true);
  expect(legality('slots', 'town', 1952)?.joinLegalAtLicensedVenue).toBe(false);
  expect(legality('numbers', 'licensed_district', 2025)?.runLegalWithLicence).toBe(false);
  const debt = { debtorId: 'p', creditorId: 'c', dueDay: 10, remainingCents: 500 };
  expect([10, 11, 17, 24].map(day => debtStatus(debt, day)?.event))
    .toEqual([null, 'warning', 'visit', 'escalation']);
  expect(debtStatus({ ...debt, remainingCents: 0 }, 24)?.event).toBeNull();
});
