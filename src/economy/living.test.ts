import { expect, it } from 'vitest';
import { arrears, dailyBudget, dailyNeeds, dueOn, lodgingOf, priceIn } from './index';

it('calibrates the 1952 boarding-house encounter', () => {
  const room = lodgingOf('boarding_house', 1952)!;
  const board = lodgingOf('room_and_board', 1952)!;
  expect(room).toMatchObject({ rentCents: 800, periodDays: 7, includedMealsPerDay: 0 });
  expect(board.rentCents).toBeGreaterThanOrEqual(1150);
  expect(board.rentCents).toBeLessThanOrEqual(1400);
  expect(board.includedMealsPerDay).toBe(2);
  expect(dailyNeeds(1952)?.dinerMealsPerDayCents).toBe(200);
  expect(lodgingOf('motel', 1925)).toBeNull();
  expect(lodgingOf('motel', 1952)).toMatchObject({ periodDays: 1 });
});

it('charges a flat deposit only on the first due date', () => {
  const flat = lodgingOf('flat', 1952)!;
  expect(dueOn(flat, 10, 9)).toBe(0);
  expect(dueOn(flat, 10, 10)).toBe(10600);
  expect(dueOn(flat, 10, 39)).toBe(0);
  expect(dueOn(flat, 10, 40)).toBe(5300);
});

it('warns on day one and locks out at seven days late, with oldest charges paid first', () => {
  const ledger = { lodging: lodgingOf('boarding_house', 1952)!, startDay: 6, paidCents: 0 };
  expect(arrears({ ...ledger, day: 6 }).status).toBe('due');
  expect(arrears({ ...ledger, day: 7 }).status).toBe('warning');
  expect(arrears({ ...ledger, day: 12 }).status).toBe('warning');
  expect(arrears({ ...ledger, day: 13 })).toEqual({
    owedCents: 1600, firstUnpaidDay: 6, daysLate: 7, status: 'locked_out', belongingsHeld: true,
  });
  expect(arrears({ ...ledger, day: 13, paidCents: 900 })).toMatchObject({
    owedCents: 700, firstUnpaidDay: 13, status: 'due', belongingsHeld: false,
  });
  expect(arrears({ ...ledger, day: 13, paidCents: 1600 }).status).toBe('current');
});

it('gates vehicle charges by era and uses the current fuel price curve', () => {
  expect(dailyNeeds(1904)?.car).toBeNull();
  expect(dailyNeeds(1905)?.car).toMatchObject({
    gasPerGallonCents: priceIn('gasoline', 1905), insurancePerYearCents: null, licencePerYearCents: null,
  });
  expect(dailyNeeds(1910)?.car?.licencePerYearCents).toBeGreaterThan(0);
  expect(dailyNeeds(1925)?.car?.insurancePerYearCents).toBeGreaterThan(0);
  expect(dailyNeeds(1978)?.car?.gasPerGallonCents).toBe(priceIn('gasoline', 1978));
});

it('returns deterministic JSON budgets by bracket and year', () => {
  const poor = dailyBudget('poor', 1952)!;
  expect(dailyBudget('poor', 1952)).toEqual(JSON.parse(JSON.stringify(poor)));
  expect(poor.weeklyLivingCents).toBe(poor.spendingCents * 7);
  expect(dailyBudget('working', 1952)!.incomeCents).toBeGreaterThan(poor.incomeCents);
  expect(dailyBudget('comfortable', 1952)!.foodCents).toBeGreaterThan(poor.foodCents);
  expect(dailyBudget('poor', 2025)!.spendingCents).toBeGreaterThan(poor.spendingCents);
});
