import { dateOf, holidaysOn } from '../calendar';
import { hoursOf, openStock, priceIndex, quantityOf, restock, take, wage, type Job, type ShopKind, type ShopStock } from '../economy';
import { rand } from '../sim/rng';

const traffic: Record<ShopKind, number> = {
  general: 32, market: 42, hardware: 23, pharmacy: 30, five_and_dime: 46,
  pawn: 10, gas_station: 36, diner: 54, clothing: 22,
};

export type BusinessPlace = Readonly<{ id: string; kind: string; name: string }>;
export type BusinessOffer = Readonly<{
  place: BusinessPlace; year: number; askingCents: number; leaseMonthlyCents: number;
  stockCents: number; fixturesCents: number; goodwillCents: number;
  recentWeeklyTakingsCents: readonly number[];
}>;

const wholesale = (retail: number) => Math.ceil(retail * .55);

export function businessesForSale(places: readonly BusinessPlace[], year: number, seed: string): BusinessOffer[] {
  const index = priceIndex(year);
  if (index === null) return [];
  const cents = (value: number) => Math.round(value * index / priceIndex(1952)!);
  return places.flatMap(place => {
    const stock = openStock(place.kind, year, JSON.stringify([seed, place.id, 'offer']));
    const kind = stock.shopKind;
    if (!kind || stock.entries.length === 0 || rand(seed, 'business_listing', place.id, year) >= .6) return [];
    const stockCents = stock.entries.reduce((sum, entry) => sum + wholesale(entry.priceCents) * entry.quantity, 0);
    const fixturesCents = cents(kind === 'five_and_dime' ? 140000 : 90000 + 900 * traffic[kind]);
    const goodwillCents = kind === 'five_and_dime' ? Math.max(0, cents(100000) - stockCents) : cents(25000 + 850 * traffic[kind]);
    return [{
      place, year, askingCents: stockCents + fixturesCents + goodwillCents,
      leaseMonthlyCents: cents(kind === 'five_and_dime' ? 6000 : 4200 + 42 * traffic[kind]),
      stockCents, fixturesCents, goodwillCents,
      recentWeeklyTakingsCents: Array.from({ length: 4 }, (_, week) =>
        cents(Math.round(traffic[kind] * (600 + 150 * rand(seed, 'business_takings', place.id, year, week))))),
    }];
  });
}

export type StaffMember = Readonly<{ job: Job; hours: number }>;
export type Business = Readonly<{
  place: BusinessPlace; stock: ShopStock; staff: readonly StaffMember[];
  cashCents: number; fixturesCents: number; leaseMonthlyCents: number; bankDailyCents: number;
  rentOwedCents: number; bankOwedCents: number; operatingOwedCents: number;
  missedRentDays: number; missedBankDays: number; lastDay: number | null;
  recentProfitCents: readonly number[];
  schedule?: Readonly<{ open: number; close: number }>;
}>;

export function openBusiness(
  offer: BusinessOffer, tenure: 'buy' | 'lease', cashCents: number, seed: string, bankDailyCents = 0,
): Business {
  return {
    place: offer.place, stock: openStock(offer.place.kind, offer.year, JSON.stringify([seed, offer.place.id, 'offer'])),
    staff: [], fixturesCents: offer.fixturesCents, cashCents,
    leaseMonthlyCents: tenure === 'lease' ? offer.leaseMonthlyCents : 0, bankDailyCents,
    rentOwedCents: 0, bankOwedCents: 0, operatingOwedCents: 0,
    missedRentDays: 0, missedBankDays: 0, lastDay: null, recentProfitCents: [],
  };
}

export type BusinessEvent =
  | { kind: 'stock_theft'; itemId: string; quantity: number }
  | { kind: 'cash_theft' | 'breakage'; cents: number };

export type DayConditions = Readonly<{
  streetActivity?: number; closed?: boolean; events?: readonly BusinessEvent[];
}>;

export function runDay(business: Business, day: number, seed: string, conditions: DayConditions = {}) {
  const date = dateOf(day);
  const kind = business.stock.shopKind;
  if (!date || !kind || priceIndex(date.year) === null
    || (business.lastDay !== null && day !== business.lastDay + 1)) return null;

  const outletSeed = JSON.stringify([seed, business.place.id]);
  const closed = conditions.closed || holidaysOn(date).some(holiday => holiday.closes.includes('shops'));
  const spans = closed ? [] : hoursOf(kind, date.year, date.weekday, outletSeed);
  const openHours = spans.reduce((sum, span) => sum + Math.max(0,
    Math.min(span.close, business.schedule?.close ?? 24) - Math.max(span.open, business.schedule?.open ?? 0)), 0);
  const activity = conditions.streetActivity ?? 1;
  const demand = Number.isFinite(activity) ? Math.max(0, Math.min(2, activity)) : 1;
  const weekends = date.weekday === 0 ? .66 : date.weekday === 6 ? 1.22 : 1;
  const customers = Math.round(traffic[kind] * (openHours / 10) * demand * weekends
    * (.8 + .4 * rand(outletSeed, 'business_customers', day)));

  let stock = business.stock;
  let salesCents = 0;
  let costOfGoodsCents = 0;
  let lossCents = 0;
  let cashTheftCents = 0;
  let breakageCents = 0;
  for (const event of conditions.events ?? []) {
    if (event.kind === 'stock_theft') {
      if (!Number.isSafeInteger(event.quantity) || event.quantity <= 0) continue;
      const removed = Math.min(quantityOf(stock, event.itemId), event.quantity);
      const price = stock.entries.find(entry => entry.itemId === event.itemId)?.priceCents ?? 0;
      stock = take(stock, event.itemId, removed);
      lossCents += removed * wholesale(price);
    } else if (Number.isSafeInteger(event.cents) && event.cents > 0) {
      if (event.kind === 'breakage') breakageCents += event.cents;
      else cashTheftCents += Math.min(Math.max(0, business.cashCents - cashTheftCents), event.cents);
    }
  }
  lossCents += breakageCents + cashTheftCents;

  for (let i = 0; i < customers; i++) {
    const available = stock.entries.filter(entry => entry.quantity > 0);
    const chosen = available[Math.floor(rand(outletSeed, 'business_purchase', day, i) * available.length)];
    if (!chosen) break;
    stock = take(stock, chosen.itemId, 1);
    salesCents += chosen.priceCents;
    costOfGoodsCents += wholesale(chosen.priceCents);
  }

  const delivery = restock(stock, 1, date.year, outletSeed);
  // Keep remaining units if the new delivery's target is lower.
  const delivered = delivery.entries !== stock.entries;
  const restockCents = delivered ? delivery.entries.reduce((sum, row) =>
    sum + wholesale(row.priceCents) * Math.max(0, row.quantity - quantityOf(stock, row.itemId)), 0) : 0;
  const wagesCents = business.staff.reduce((sum, worker) =>
    sum + Math.round(Math.max(0, Math.min(openHours, worker.hours)) * (wage(worker.job, date.year)?.hourlyCents ?? 0)), 0);
  const rentDay = (stock.elapsedDays % 30) + 1;
  const rentCents = Math.floor(business.leaseMonthlyCents * rentDay / 30)
    - Math.floor(business.leaseMonthlyCents * (rentDay - 1) / 30);
  const bankCents = business.bankDailyCents;
  let cashCents = business.cashCents + salesCents - cashTheftCents;
  const settle = (owed: number) => {
    const paid = Math.min(cashCents, owed);
    cashCents -= paid;
    return owed - paid;
  };
  const rentOwedCents = settle(business.rentOwedCents + rentCents);
  const bankOwedCents = settle(business.bankOwedCents + bankCents);
  const operatingOwedCents = settle(business.operatingOwedCents + wagesCents + breakageCents);
  const paidDelivery = restockCents <= cashCents;
  if (paidDelivery) cashCents -= restockCents;
  const missedRentDays = rentOwedCents > 0 ? business.missedRentDays + 1 : 0;
  const missedBankDays = bankOwedCents > 0 ? business.missedBankDays + 1 : 0;
  const profitCents = salesCents - costOfGoodsCents - wagesCents - rentCents - bankCents - lossCents;

  const next: Business = {
    ...business, cashCents, rentOwedCents, bankOwedCents, operatingOwedCents, missedRentDays, missedBankDays,
    lastDay: day, recentProfitCents: [...business.recentProfitCents, profitCents].slice(-28),
    stock: paidDelivery && delivered ? { ...delivery, entries:
      delivery.entries.map(row => ({ ...row, quantity: Math.max(row.quantity, quantityOf(stock, row.itemId)) })),
    } : { ...stock, elapsedDays: delivery.elapsedDays },
  };
  return {
    business: next, customers, salesCents, costOfGoodsCents, restockCents: paidDelivery ? restockCents : 0, wagesCents,
    rentCents, bankCents, lossCents, profitCents,
    landlord: missedRentDays >= 7 ? 'lockout' : missedRentDays > 0 ? 'warning' : null,
    bank: missedBankDays >= 14 ? 'repossess' : missedBankDays > 0 ? 'warning' : null,
  };
}

export function valuation(business: Business, year: number): number | null {
  if (priceIndex(year) === null) return null;
  const inventory = business.stock.entries.reduce((sum, row) => sum + wholesale(row.priceCents) * row.quantity, 0);
  const profits = business.recentProfitCents;
  const goodwill = profits.length > 0 ? Math.round(Math.max(0, profits.reduce((a, b) => a + b, 0) / profits.length) * 90) : 0;
  return Math.max(0, business.fixturesCents + inventory + goodwill
    - business.rentOwedCents - business.bankOwedCents - business.operatingOwedCents);
}
