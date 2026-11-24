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
    const goodwillCents = cents(kind === 'five_and_dime' ? 85000 : 25000 + 850 * traffic[kind]);
    return [{
      place, year, askingCents: stockCents + fixturesCents + goodwillCents,
      leaseMonthlyCents: cents(kind === 'five_and_dime' ? 6000 : 4200 + 42 * traffic[kind]),
      stockCents, fixturesCents, goodwillCents,
      recentWeeklyTakingsCents: Array.from({ length: 4 }, (_, week) =>
        cents(Math.round(traffic[kind] * (600 + 150 * rand(seed, 'business_takings', place.id, year, week))))),
    }];
  });
}
