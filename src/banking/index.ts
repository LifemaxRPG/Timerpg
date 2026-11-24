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
  const balanceCents = account.balanceCents + cents;
  if (!money(balanceCents)) return null;
  return {
    ...account, day: throughDay, balanceCents, interestRemainder: accrued % denominator,
    history: cents ? [...account.history, { day: throughDay, kind: 'interest', cents }] : account.history,
  };
}

export type LoanPurpose = 'car' | 'business' | 'personal';
export type Applicant = Readonly<{
  monthlyIncomeCents: number; employed: boolean; collateralCents: number; reputation: number; cosigner?: boolean;
}>;
type ApprovedLoan = Readonly<{
  approved: true; purpose: LoanPurpose; principalCents: number; annualBasisPoints: number;
  months: number; monthlyCents: number; totalCents: number; secured: boolean;
}>;
type RefusedLoan = Readonly<{ approved: false; reason: 'invalid' | 'income' | 'reputation' | 'collateral' | 'cosigner' }>;

export function loanOffer(applicant: Applicant, purpose: LoanPurpose, amount: number, year: number): ApprovedLoan | RefusedLoan {
  if (priceIndex(year) === null || !money(amount) || amount < 100 || amount > 100000000
    || !['car', 'business', 'personal'].includes(purpose)) return { approved: false, reason: 'invalid' };
  if (!applicant.employed || !money(applicant.monthlyIncomeCents) || applicant.monthlyIncomeCents === 0) {
    return { approved: false, reason: 'income' };
  }
  if (!Number.isFinite(applicant.reputation) || applicant.reputation < .4) return { approved: false, reason: 'reputation' };
  const secured = purpose !== 'personal';
  if (secured && (!money(applicant.collateralCents) || applicant.collateralCents < amount)) {
    return { approved: false, reason: 'collateral' };
  }
  if (purpose === 'car' && year < 1965 && !applicant.cosigner) return { approved: false, reason: 'cosigner' };
  const months = purpose === 'car' ? 24 : purpose === 'business' ? 36 : 12;
  const annualBasisPoints = (year < 1970 ? 650 : year < 1990 ? 1050 : 750) + (secured ? 0 : 250);
  const monthlyRate = annualBasisPoints / 120000;
  const monthlyCents = Math.ceil(amount * monthlyRate / (1 - (1 + monthlyRate) ** -months));
  if (monthlyCents > applicant.monthlyIncomeCents * .3) return { approved: false, reason: 'income' };
  return {
    approved: true, purpose, principalCents: amount, annualBasisPoints, months, monthlyCents,
    totalCents: monthlyCents * months, secured,
  };
}

export type Loan = Readonly<{ terms: ApprovedLoan; startDay: number; paidCents: number }>;
export function openLoan(terms: ApprovedLoan, startDay: number): Loan | null {
  return terms.approved && money(startDay) ? { terms, startDay, paidCents: 0 } : null;
}

export function repay(loan: Loan, cents: number): Loan | null {
  if (!money(cents) || cents === 0 || cents > loan.terms.totalCents - loan.paidCents) return null;
  return { ...loan, paidCents: loan.paidCents + cents };
}

export function loanStatus(loan: Loan, day: number) {
  if (!money(day) || day < loan.startDay) return null;
  const { terms, paidCents } = loan;
  const installments = Math.min(terms.months, Math.floor((day - loan.startDay) / 30));
  const overdueCents = Math.max(0, installments * terms.monthlyCents - paidCents);
  const oldestDue = loan.startDay + 30 * (Math.floor(paidCents / terms.monthlyCents) + 1);
  const daysLate = overdueCents > 0 ? day - oldestDue : 0;
  const status = paidCents >= terms.totalCents ? 'paid' : daysLate >= 60 ? 'default'
    : daysLate >= 30 ? 'collection' : overdueCents ? 'late' : 'current';
  return {
    status, overdueCents, daysLate, remainingCents: terms.totalCents - paidCents,
    seize: status === 'default' && terms.secured ? terms.purpose : null,
  };
}

function holidayClosesBank(date?: CalendarDate): boolean {
  return !!date && holidaysOn(date).some(holiday => holiday.closes.includes('banks'));
}

export function bankHours(year: number, weekday: number, seed: string, date?: CalendarDate) {
  if (weekday === 0 || (weekday === 6 && year >= 1970) || holidayClosesBank(date)) return [];
  return hoursOf('hardware', year, weekday, seed).map(span => ({
    open: Math.max(9, span.open), close: Math.min(weekday === 6 ? 12 : 15, span.close),
  }));
}

export function cashOnHand(bank: string, year: number, weekday: number, hour: number, seed: string, date?: CalendarDate) {
  const index = priceIndex(year);
  if (index === null || !Number.isFinite(hour) || hour < 0 || hour >= 24
    || !Number.isInteger(weekday) || weekday < 0 || weekday > 6) return null;
  const absolute = date ? dayNumberOf(date) : null;
  if (date && (absolute === null || date.year !== year || (absolute + 1) % 7 !== weekday)) return null;
  const next = absolute === null ? null : dateOf(absolute + 1);
  const preHoliday = next !== null && holidayClosesBank(next);
  const payday = date ? isPayday(date) : weekday === 5;
  const open = bankHours(year, weekday, seed, date).some(span => hour >= span.open && hour < span.close);
  const variation = .95 + .1 * rand(seed, 'bank_cash', bank, year, absolute ?? weekday);
  return {
    drawerCents: open ? scale(Math.round((payday ? 300000 : 165000) * variation * (preHoliday ? 1.2 : 1)), index) : 0,
    vaultCents: scale(Math.round((payday ? 2500000 : 1900000) * variation * (preHoliday ? 1.2 : 1)), index),
    security: { guards: open ? 1 : 0, alarm: year >= 1915, timeLock: year >= 1890, cameras: year >= 1965 },
  };
}
