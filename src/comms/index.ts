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

export type Telegram = Readonly<{ senderId: string; recipientId: string; text: string }>;

export function sendTelegram(from: Place, to: Place, year: number, day: number, message: Telegram) {
  const multiplier = rate(year);
  const km = distance(from, to);
  const text = message.text.trim();
  if (multiplier === null || year > 2005 || !text || !Number.isFinite(km)
    || !Number.isSafeInteger(day) || day < 0) return null;
  const words = text.split(/\s+/u).length;
  const overseas = from.continent !== to.continent;
  const nominalPerWord = overseas ? 7.5 : km > 300 ? 4 : 2;
  const costCents = Math.max(1, Math.round(words * nominalPerWord * multiplier));
  const deliveryDay = day + (overseas && year < 1900 ? 1 : 0);
  if (!Number.isSafeInteger(deliveryDay) || !Number.isSafeInteger(costCents)) return null;
  return {
    words, costCents, deliveryDay,
    officeRecord: { ...message, text, officeId: from.id, destinationId: to.id, sentDay: day },
  };
}

const phoneEras = [
  { from: 1880, shop: .15, booth: 0, poor: .01, working: .03, comfortable: .18 },
  { from: 1900, shop: .35, booth: .14, poor: .03, working: .15, comfortable: .50 },
  { from: 1920, shop: .74, booth: .45, poor: .13, working: .39, comfortable: .78 },
  { from: 1950, shop: .92, booth: .73, poor: .39, working: .68, comfortable: .93 },
  { from: 1970, shop: .99, booth: .87, poor: .68, working: .88, comfortable: .98 },
  { from: 2000, shop: .99, booth: .40, poor: .83, working: .96, comfortable: .99 },
  { from: 2010, shop: .99, booth: .10, poor: .87, working: .98, comfortable: .99 },
] as const;

export function phoneShares(year: number) {
  if (rate(year) === null) return null;
  const era = [...phoneEras].reverse().find(row => year >= row.from)!;
  return {
    shop: era.shop, booth: era.booth,
    home: { poor: era.poor, working: era.working, comfortable: era.comfortable },
  };
}

export type PhoneEndpoint = Readonly<{ place: Place; hasPhone: boolean; partyLine?: boolean }>;

export function call(from: PhoneEndpoint, to: PhoneEndpoint, year: number, hour: number) {
  const multiplier = rate(year);
  const km = distance(from.place, to.place);
  if (multiplier === null || !Number.isFinite(km) || !Number.isFinite(hour) || hour < 0 || hour >= 24) return null;
  const local = from.place.town === to.place.town && from.place.continent === to.place.continent;
  const overseas = from.place.continent !== to.place.continent;
  const operator = year < (local ? 1940 : 1970);
  const connectionAvailable = from.hasPhone && to.hasPhone
    && (local || year >= (overseas ? 1927 : 1885)) && (!operator || (hour >= 7 && hour < 22));
  const quote = Math.round(multiplier * (local ? 5 : 25 + Math.min(250, Math.ceil(km / 100) * 5) + (overseas ? 150 : 0)));
  const partyLine = year >= 1895 && year <= 1985 && !!(from.partyLine || to.partyLine);
  return {
    costCents: connectionAvailable ? Math.max(1, quote) : 0,
    connects: connectionAvailable, operator, local,
    eavesdropChance: connectionAvailable ? partyLine ? .25 : operator ? .04 : 0 : 0,
  };
}
