import { hoursOf, priceIndex } from '../economy';
import { dateOf, dayNumberOf, holidaysOn, isPayday, type CalendarDate } from '../calendar';
import { rand } from '../sim/rng';

const money = (cents: number) => Number.isSafeInteger(cents) && cents >= 0;
const scale = (cents1952: number, index: number) => Math.round(cents1952 * index / priceIndex(1952)!);

export type AccountKind = 'savings' | 'checking';
type PassbookEntry = Readonly<{ day: number; kind: 'open' | 'deposit' | 'withdrawal' | 'interest'; cents: number }>;
export type Account = Readonly<{
  kind: AccountKind; balanceCents: number; day: number; interestRemainder: number; history: readonly PassbookEntry[];
}>;

export function accountTerms(kind: AccountKind, year: number) {
  const index = priceIndex(year);
  if (index === null || (kind !== 'savings' && kind !== 'checking')) return null;
  return {
    openingCents: Math.max(1, scale(kind === 'savings' ? 500 : 1500, index)),
    annualBasisPoints: kind === 'checking' ? year >= 1980 ? 75 : 0
      : year < 1940 ? 300 : year < 1970 ? 200 : year < 1990 ? 450 : year < 2010 ? 225 : 125,
    cheques: kind === 'checking' && year >= 1890,
  };
}

export function openAccount(kind: AccountKind, year: number, day: number, cents: number): Account | null {
  const terms = accountTerms(kind, year);
  if (!terms || !money(day) || !money(cents) || cents < terms.openingCents) return null;
  return { kind, day, balanceCents: cents, interestRemainder: 0, history: [{ day, kind: 'open', cents }] };
}

function post(account: Account, cents: number, kind: 'deposit' | 'withdrawal'): Account | null {
  const balanceCents = account.balanceCents + (kind === 'deposit' ? cents : -cents);
  if (!money(cents) || cents === 0 || !money(balanceCents)) return null;
  return { ...account, balanceCents, history: [...account.history, { day: account.day, kind, cents }] };
}

export function deposit(account: Account, cents: number): Account | null {
  return post(account, cents, 'deposit');
}

export function withdraw(account: Account, cents: number): Account | null {
  return post(account, cents, 'withdrawal');
}

/** Post simple interest for the interval. Round only at posting, carrying the fraction. */
export function accrueInterest(account: Account, throughDay: number, year: number): Account | null {
  const terms = accountTerms(account.kind, year);
  if (!terms || !money(throughDay) || throughDay < account.day) return null;
  if (throughDay === account.day) return account;
  const denominator = 365 * 10000;
  const accrued = account.balanceCents * (throughDay - account.day) * terms.annualBasisPoints + account.interestRemainder;
  if (!money(accrued)) return null;
  const cents = Math.floor(accrued / denominator);
  return {
    ...account, day: throughDay, balanceCents: account.balanceCents + cents, interestRemainder: accrued % denominator,
    history: cents ? [...account.history, { day: throughDay, kind: 'interest', cents }] : account.history,
  };
}
