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

export type MortgageApplicant = Pick<Applicant, 'monthlyIncomeCents' | 'employed' | 'reputation'>
  & Readonly<{ availableCashCents: number }>;
export type Mortgage = Readonly<{ homeId: string; downPaymentCents: number; loan: Loan }>;

export function mortgageOffer(home: Home, applicant: MortgageApplicant, year: number, startDay: number) {
  const price = valuation(home, year);
  if (price === null || !Number.isSafeInteger(startDay) || startDay < 0) return { approved: false, reason: 'invalid' } as const;
  const downPaymentCents = Math.ceil(price * (year < 1935 ? .35 : year < 1980 ? .2 : .15));
  if (!Number.isSafeInteger(applicant.availableCashCents) || applicant.availableCashCents < downPaymentCents) {
    return { approved: false, reason: 'down_payment' } as const;
  }
  if (!applicant.employed || !Number.isSafeInteger(applicant.monthlyIncomeCents) || applicant.monthlyIncomeCents <= 0) {
    return { approved: false, reason: 'income' } as const;
  }
  if (!Number.isFinite(applicant.reputation) || applicant.reputation < .4) return { approved: false, reason: 'reputation' } as const;
  const principalCents = price - downPaymentCents;
  const annualBasisPoints = year < 1935 ? 600 : year < 1970 ? 525 : year < 1990 ? 950 : 600;
  const months = year < 1935 ? 120 : year < 1970 ? 240 : 360;
  const rate = annualBasisPoints / 120000;
  const monthlyCents = Math.ceil(principalCents * rate / (1 - (1 + rate) ** -months));
  if (!Number.isSafeInteger(monthlyCents * months) || monthlyCents <= 0) return { approved: false, reason: 'invalid' } as const;
  if (monthlyCents > applicant.monthlyIncomeCents * .3) return { approved: false, reason: 'income' } as const;

  // The bank's existing secured-ledger shape uses a business label. Housing maps its collection actions.
  const loan = openLoan({
    approved: true, purpose: 'business', secured: true, principalCents, annualBasisPoints, months,
    monthlyCents, totalCents: monthlyCents * months,
  }, startDay)!;
  const mortgage: Mortgage = { homeId: home.id, downPaymentCents, loan };
  return { approved: true, mortgage } as const;
}

export function payMortgage(mortgage: Mortgage, cents: number): Mortgage | null {
  const loan = repay(mortgage.loan, cents);
  return loan ? { ...mortgage, loan } : null;
}

export function mortgageStatus(mortgage: Mortgage, day: number) {
  const status = loanStatus(mortgage.loan, day);
  if (!status) return null;
  return {
    status: status.daysLate >= 90 ? 'foreclosure' : status.status,
    overdueCents: status.overdueCents, daysLate: status.daysLate, remainingCents: status.remainingCents,
    foreclosureHomeId: status.daysLate >= 90 ? mortgage.homeId : null,
  };
}

export function propertyTax(home: Home, date: CalendarDate) {
  if (dayNumberOf(date) === null) return null;
  const value = valuation(home, date.year);
  if (value === null) return null;
  const annualCents = Math.round(value * (date.year < 1935 ? .009 : date.year < 1970 ? 96 / 7800 : .014));
  return { annualCents, dueCents: date.month === 9 && date.day === 15 ? annualCents : 0 };
}

export function upkeep(home: Home, year: number) {
  const value = valuation(home, year);
  if (value === null) return null;
  const age = Math.min(100, year - home.builtYear);
  return {
    annualReserveCents: Math.round(value * (.006 + age * .00012)),
    repairQuoteCents: Math.round(value * (1 - Math.max(0, Math.min(1, home.condition))) * (.02 + age * .0004)),
  };
}
