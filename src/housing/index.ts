import { loanStatus, openLoan, repay, type Applicant, type Loan } from '../banking';
import { dayNumberOf, type CalendarDate } from '../calendar';
import { priceIndex } from '../economy';
import { rand } from '../sim/rng';

export type Home = Readonly<{
  id: string; street: string; bedrooms: number; floorM2: number; lotM2: number;
  condition: number; builtYear: number; neighbourhood: number;
}>;

export function valuation(home: Home, year: number): number | null {
  const index = priceIndex(year);
  if (index === null || year < home.builtYear || !Number.isFinite(home.floorM2) || home.floorM2 <= 0
    || !Number.isFinite(home.lotM2) || home.lotM2 < 0 || !Number.isFinite(home.condition)
    || !Number.isFinite(home.neighbourhood) || !Number.isSafeInteger(home.bedrooms) || home.bedrooms < 0) return null;
  const baseCents = 120000 + 5000 * home.floorM2 + 400 * home.lotM2 + 50000 * home.bedrooms;
  const condition = .65 + .35 * Math.max(0, Math.min(1, home.condition));
  const street = .8 + .4 * Math.max(0, Math.min(1, home.neighbourhood));
  const price = Math.round(baseCents * condition * street * index / priceIndex(1952)!);
  return Number.isSafeInteger(price) ? price : null;
}

export function housesForSale(homes: readonly Home[], year: number, seed: string) {
  return homes.flatMap(home => {
    const askingCents = valuation(home, year);
    if (askingCents === null || rand(seed, 'house_listed', home.id, year) >= .55) return [];
    return [{ home, askingCents, daysOnMarket: 1 + Math.floor(rand(seed, 'house_days', home.id, year) * 120) }];
  });
}
