import { priceIndex } from '../economy';

export type Place = Readonly<{ id: string; town: string; continent: string; xKm: number; yKm: number }>;

function distance(from: Place, to: Place): number {
  return Math.hypot(from.xKm - to.xKm, from.yKm - to.yKm);
}

function rate(year: number): number | null {
  const index = priceIndex(year);
  return index === null ? null : index / priceIndex(1925)!;
}

export function collectionHours(year: number): number[] {
  if (rate(year) === null) return [];
  return year < 1940 ? [9, 16] : [9, 13, 17];
}

export type LetterOptions = Readonly<{ grams?: number; service?: 'surface' | 'air'; hour?: number }>;

export function sendLetter(fromPlace: Place, toPlace: Place, year: number, day: number, options: LetterOptions = {}) {
  const { grams = 28, service = 'surface', hour = 9 } = options;
  const multiplier = rate(year);
  const km = distance(fromPlace, toPlace);
  const overseas = fromPlace.continent !== toPlace.continent;
  const air = service === 'air';
  if (multiplier === null || !Number.isFinite(km) || !Number.isSafeInteger(day) || day < 0
    || !Number.isFinite(grams) || grams <= 0 || !Number.isFinite(hour) || hour < 0 || hour >= 24
    || (air && year < (overseas ? 1939 : 1918))) return null;
  const collectedDay = day + (hour > collectionHours(year).at(-1)! ? 1 : 0);
  const transport = air ? 'air' : overseas ? 'ship' : fromPlace.town === toPlace.town ? 'local' : 'rail';
  const travelDays = transport === 'local' ? 1 : 1 + Math.max(1, Math.ceil(km / (
    transport === 'air' ? 3500 : transport === 'ship' ? 500 : 650
  )));
  const costCents = Math.max(1, Math.round(multiplier * Math.ceil(grams / 28)
    * (3 + (overseas ? 5 : 0) + (air ? 8 : 0))));
  const deliveryDay = collectedDay + travelDays;
  if (!Number.isSafeInteger(deliveryDay) || !Number.isSafeInteger(costCents)) return null;
  return { costCents, collectedDay, deliveryDay, transport };
}
