export type OrderMethod = 'mail' | 'phone' | 'online';

import { priceIn, priceIndex } from '../economy';
import { sendLetter, type Place } from '../comms';
import { travelTime } from '../transit';

export type Payment = 'money_order' | 'cash_on_delivery' | 'cheque' | 'card';
export type Buyer = Readonly<{ givenName: string; payment: Payment }>;
export type Address = Readonly<{ label: string; destination: Place; merchant: Place;
  pickup: 'post_office' | 'station' | 'home' }>;
export type OrderLine = Readonly<{ itemId: string; quantity: number }>;
const goods = [
  ['hammer', 'Claw hammer'], ['jacket', 'Wool jacket'], ['radio', 'Table radio'],
  ['brace', 'Hand drill brace'],
] as const;

export function catalogue(year: number) {
  const index = priceIndex(year);
  if (index === null) return [];
  const items = goods.flatMap(([id, label]) => {
    const cents = priceIn(id, year);
    return cents === null ? [] : [{ id: String(id), label: String(label), cents }];
  });
  const add = (id: string, label: string, referenceYear: number, cents: number) => {
    items.push({ id, label, cents: Math.max(1, Math.round(cents * index / priceIndex(referenceYear)!)) });
  };
  if (year >= 1908 && year <= 1940) add('house_kit', 'House kit', 1925, 145000);
  if (year < 1968) add('revolver', 'Revolver', 1925, 1450);
  if (year < 1920 || year >= 1934) add('liquor', 'Bottled spirits', 1952, 300);
  return items;
}

export function placeOrder(items: readonly OrderLine[], buyer: Buyer, address: Address,
  year: number, day: number, method: OrderMethod) {
  if (priceIndex(year) === null || !Number.isSafeInteger(day) || day < 0 || !items.length
    || !buyer.givenName.trim() || !address.label.trim()) return null;
  if ((method === 'phone' && year < 1930) || (method === 'online' && year < 1995)
    || (buyer.payment === 'card' && year < 1960) || (method === 'online' && buyer.payment !== 'card')
    || (address.pickup === 'home' && year < 1930)) return null;
  const listing = catalogue(year);
  const lines = [];
  for (const item of items) {
    const entry = listing.find(row => row.id === item.itemId);
    if (!entry || !Number.isSafeInteger(item.quantity) || item.quantity <= 0
      || (item.itemId === 'house_kit' && address.pickup !== 'station')) return null;
    lines.push({ ...item, unitCents: entry.cents });
  }
  const letter = sendLetter(address.destination, address.merchant, year, day);
  const returnMail = sendLetter(address.merchant, address.destination, year, day);
  const distanceKm = Math.hypot(address.merchant.xKm - address.destination.xKm,
    address.merchant.yKm - address.destination.yKm);
  const railMinutes = travelTime('railway', year, distanceKm);
  if (!letter || !returnMail || railMinutes === null) return null;
  const processingDays = year < 1940 ? 17 : year < 1980 ? 5 : 1;
  const transitDays = address.merchant.continent === address.destination.continent
    ? Math.max(1, Math.ceil(railMinutes / 1440)) : returnMail.deliveryDay - day;
  const arrivesOn = (method === 'mail' ? letter.deliveryDay : day) + processingDays + transitDays;
  const totalCents = lines.reduce((sum, line) => sum + line.quantity * line.unitCents, 0);
  if (!Number.isSafeInteger(arrivesOn) || !Number.isSafeInteger(totalCents)) return null;
  return {
    lines, totalCents, arrivesOn, pickup: address.pickup,
    record: { givenName: buyer.givenName, address: address.label, destinationId: address.destination.id,
      merchantId: address.merchant.id, payment: buyer.payment, method, year, orderedOn: day },
  };
}
