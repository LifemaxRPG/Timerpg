import { priceIndex, wage } from './eras';
import { priceIn } from './pricing';

const lodgingKinds = {
  boarding_house: [1880, 7, 800, 0, 0],
  room_and_board: [1880, 7, 1250, 0, 2],
  hotel: [1880, 1, 350, 0, 0],
  flat: [1880, 30, 5300, 5300, 0],
  motel: [1950, 1, 475, 0, 0],
} as const;

export type LodgingKind = keyof typeof lodgingKinds;
export type Lodging = Readonly<{
  kind: LodgingKind;
  periodDays: number;
  rentCents: number;
  depositCents: number;
  includedMealsPerDay: number;
}>;

function scale(cents1952: number, index: number): number {
  return Math.round(cents1952 * index / .085);
}

export function lodgingOf(kind: LodgingKind, year: number): Lodging | null {
  const index = priceIndex(year);
  if (index === null || !Object.prototype.hasOwnProperty.call(lodgingKinds, kind)) return null;
  const [from, periodDays, rent, deposit, includedMealsPerDay] = lodgingKinds[kind];
  if (year < from) return null;
  return { kind, periodDays, rentCents: scale(rent, index), depositCents: scale(deposit, index), includedMealsPerDay };
}

export function dailyNeeds(year: number) {
  const index = priceIndex(year);
  if (index === null) return null;
  return {
    dinerMealsPerDayCents: scale(200, index),
    cookedMealsPerDayCents: scale(105, index),
    boardingMealsPerDayCents: scale(65, index),
    clothesWearPerDayCents: scale(14, index),
    laundryPerLoadCents: scale(22, index),
    carfarePerRideCents: scale(12, index),
    transitPassPerWeekCents: year >= 1900 ? scale(95, index) : null,
    telephoneLocalCallCents: year >= 1890 ? scale(5, index) : null,
    car: year < 1905 ? null : {
      gasPerGallonCents: priceIn('gasoline', year),
      repairReservePerMileCents: Math.max(1, scale(2, index)),
      insurancePerYearCents: year >= 1925 ? scale(4200, index) : null,
      licencePerYearCents: year >= 1910 ? scale(500, index) : null,
    },
  };
}

export function dueOn(lodging: Lodging, startDay: number, day: number): number {
  const elapsed = day - startDay;
  if (!Number.isSafeInteger(startDay) || !Number.isSafeInteger(day) || elapsed < 0
    || elapsed % lodging.periodDays !== 0) return 0;
  return lodging.rentCents + (elapsed === 0 ? lodging.depositCents : 0);
}

export type RentLedger = Readonly<{
  lodging: Lodging;
  startDay: number;
  day: number;
  paidCents: number;
}>;

/** Payments apply to the deposit and oldest rental periods first. */
export function arrears({ lodging, startDay, day, paidCents }: RentLedger) {
  const elapsed = day - startDay;
  const periods = elapsed >= 0 ? 1 + Math.floor(elapsed / lodging.periodDays) : 0;
  const billed = periods > 0 ? lodging.depositCents + periods * lodging.rentCents : 0;
  const owedCents = Math.max(0, billed - paidCents);
  if (owedCents === 0) {
    return { owedCents, firstUnpaidDay: null, daysLate: 0, status: 'current', belongingsHeld: false } as const;
  }

  const paidRent = Math.max(0, paidCents - lodging.depositCents);
  const firstUnpaidDay = startDay + Math.floor(paidRent / lodging.rentCents) * lodging.periodDays;
  const daysLate = day - firstUnpaidDay;
  const status = daysLate >= 7 ? 'locked_out' : daysLate >= 1 ? 'warning' : 'due';
  return { owedCents, firstUnpaidDay, daysLate, status, belongingsHeld: status === 'locked_out' } as const;
}

const brackets = {
  poor: ['odd_jobs', 4, 'boarding_house', 'cookedMealsPerDayCents'],
  working: ['labourer', 6, 'flat', 'cookedMealsPerDayCents'],
  comfortable: ['mechanic', 7, 'flat', 'dinerMealsPerDayCents'],
} as const;

export type IncomeBracket = keyof typeof brackets;

export function dailyBudget(bracket: IncomeBracket, year: number) {
  if (!Object.prototype.hasOwnProperty.call(brackets, bracket)) return null;
  const [job, paidHoursPerDay, kind, meals] = brackets[bracket];
  const pay = wage(job, year);
  const lodging = lodgingOf(kind, year);
  const needs = dailyNeeds(year);
  if (!pay || !lodging || !needs) return null;
  const incomeCents = pay.hourlyCents * paidHoursPerDay;
  const lodgingCents = Math.round(lodging.rentCents / lodging.periodDays);
  const foodCents = needs[meals];
  const otherCents = needs.clothesWearPerDayCents + Math.round(needs.laundryPerLoadCents / 7)
    + 2 * needs.carfarePerRideCents;
  const spendingCents = lodgingCents + foodCents + otherCents;
  return { incomeCents, lodgingCents, foodCents, otherCents, spendingCents, weeklyLivingCents: spendingCents * 7 };
}
