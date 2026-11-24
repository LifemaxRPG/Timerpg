import { expect, it } from 'vitest';
import { housesForSale, mortgageOffer, mortgageStatus, payMortgage, propertyTax, upkeep, valuation } from './index';

const home = {
  id: 'elm', street: 'Elm Street', bedrooms: 2, floorM2: 80, lotM2: 400,
  condition: 1, builtYear: 1940, neighbourhood: .5,
};
const buyer = { monthlyIncomeCents: 25000, employed: true, reputation: .7, availableCashCents: 156000 };

it('values homes by size, condition, street and era', () => {
  const original = valuation(home, 1952)!;
  expect(original).toBe(780000);
  expect(valuation({ ...home, floorM2: 100 }, 1952)!).toBeGreaterThan(original);
  expect(valuation({ ...home, neighbourhood: .8 }, 1952)!).toBeGreaterThan(original);
  expect(valuation({ ...home, condition: .6 }, 1952)!).toBeLessThan(original);
  expect(valuation(home, 2025)!).toBeGreaterThan(original);
  expect(valuation(home, 1925)).toBeNull();
  const listings = Array.from({ length: 20 }, (_, i) => housesForSale([home], 1952, String(i)));
  expect(listings.some(list => list.length === 1)).toBe(true);
  expect(listings).toEqual(Array.from({ length: 20 }, (_, i) => housesForSale([home], 1952, String(i))));
});

it('quotes a 20-year 1952 mortgage and reuses bank payment timing', () => {
  expect(mortgageOffer(home, { ...buyer, availableCashCents: 1000 }, 1952, 0))
    .toMatchObject({ approved: false, reason: 'down_payment' });
  const offer = mortgageOffer(home, buyer, 1952, 0);
  if (!offer.approved) throw new Error('Expected a mortgage');
  const { mortgage } = offer;
  expect(mortgage.downPaymentCents).toBe(156000);
  expect(mortgage.loan.terms).toMatchObject({ principalCents: 624000, months: 240, monthlyCents: 4205 });
  expect(mortgageStatus(mortgage, 30)?.status).toBe('late');
  expect(mortgageStatus(mortgage, 60)?.status).toBe('collection');
  expect(mortgageStatus(mortgage, 90)?.status).toBe('default');
  expect(mortgageStatus(mortgage, 120)).toMatchObject({ status: 'foreclosure', foreclosureHomeId: home.id });
  expect(mortgageStatus(payMortgage(mortgage, 4205)!, 30)?.status).toBe('current');
});

it('charges property tax on Harvestmonth 15 and quotes repairs for worn homes', () => {
  expect(propertyTax(home, { year: 1952, month: 9, day: 14 })).toEqual({ annualCents: 9600, dueCents: 0 });
  expect(propertyTax(home, { year: 1952, month: 9, day: 15 })).toEqual({ annualCents: 9600, dueCents: 9600 });
  expect(upkeep(home, 1952)?.repairQuoteCents).toBe(0);
  expect(upkeep({ ...home, condition: .5 }, 1952)!.repairQuoteCents).toBeGreaterThan(0);
});
