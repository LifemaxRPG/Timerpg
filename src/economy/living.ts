import { priceIndex, validYear } from './eras';
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
