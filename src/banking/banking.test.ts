import { expect, it } from 'vitest';
import { accountTerms, accrueInterest, bankHours, cashOnHand, deposit, loanOffer,
  loanStatus, openAccount, openLoan, repay, withdraw } from './index';

it('opens $5 savings in 1952, accrues 2% and records transactions', () => {
  expect(openAccount('savings', 1952, 0, 499)).toBeNull();
  const account = openAccount('savings', 1952, 0, 500)!;
  const later = accrueInterest(account, 365, 1952)!;
  expect(later.balanceCents).toBe(510);
  expect(accrueInterest(account, 365, 1980)!.balanceCents).toBeGreaterThan(510);
  expect(withdraw(later, 511)).toBeNull();
  expect(deposit(withdraw(later, 10)!, 50)).toMatchObject({
    balanceCents: 550, history: [
      { day: 0, kind: 'open', cents: 500 }, { day: 365, kind: 'interest', cents: 10 },
      { day: 365, kind: 'withdrawal', cents: 10 }, { day: 365, kind: 'deposit', cents: 50 },
    ],
  });
  expect(accountTerms('checking', 1889)?.cheques).toBe(false);
  expect(accountTerms('checking', 1952)?.cheques).toBe(true);
});

it('checks income, collateral and co-signer, and advances delinquency on schedule', () => {
  const applicant = { monthlyIncomeCents: 12000, employed: true, collateralCents: 45000, reputation: .7, cosigner: true };
  expect(loanOffer({ ...applicant, employed: false }, 'car', 40000, 1952)).toMatchObject({ reason: 'income' });
  expect(loanOffer({ ...applicant, collateralCents: 0 }, 'car', 40000, 1952)).toMatchObject({ reason: 'collateral' });
  expect(loanOffer({ ...applicant, cosigner: false }, 'car', 40000, 1952)).toMatchObject({ reason: 'cosigner' });
  const offer = loanOffer(applicant, 'car', 40000, 1952);
  if (!offer.approved) throw new Error('Expected approval');
  const loan = openLoan(offer, 0)!;
  expect(loanStatus(loan, 29)?.status).toBe('current');
  expect(loanStatus(loan, 30)?.status).toBe('late');
  expect(loanStatus(loan, 60)?.status).toBe('collection');
  expect(loanStatus(loan, 90)).toMatchObject({ status: 'default', seize: 'car' });
  expect(loanStatus(repay(loan, offer.monthlyCents)!, 30)?.status).toBe('current');
  expect(loanStatus(repay(loan, offer.totalCents)!, 90)?.status).toBe('paid');
});

it('raises payday cash, closes for holidays, and gates security by era', () => {
  const friday = cashOnHand('state', 1952, 5, 10, 'town')!;
  expect(friday.drawerCents).toBeGreaterThan(cashOnHand('state', 1952, 4, 10, 'town')!.drawerCents);
  expect(friday.drawerCents).toBeGreaterThanOrEqual(285000);
  expect(friday.drawerCents).toBeLessThanOrEqual(315000);
  expect(friday).toEqual(JSON.parse(JSON.stringify(cashOnHand('state', 1952, 5, 10, 'town'))));
  expect(bankHours(1952, 1, 'town', { year: 1952, month: 9, day: 15 })).toEqual([]);
  expect(cashOnHand('state', 1952, 5, 18, 'town')?.drawerCents).toBe(0);
  expect(cashOnHand('state', 1880, 1, 10, 'town')?.security).toMatchObject({ alarm: false, timeLock: false });
  expect(friday.security).toMatchObject({ alarm: true, timeLock: true, cameras: false });
  expect(cashOnHand('state', 1970, 1, 10, 'town')?.security.cameras).toBe(true);
});
