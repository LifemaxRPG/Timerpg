import { shopKey } from './hours';
import { forSale, retailPrice, usedAsk } from './pricing';
import { rand } from '../sim/rng';
import { ITEMS, type Item, type ShopKind } from './catalogue';
import { priceIndex } from './eras';

export { FIRST_YEAR, LAST_YEAR, REFERENCE_YEAR, formatMoney, priceIndex, wage, type Job } from './eras';
export { SHOP_KINDS, type Category, type ShopKind, type Weight } from './catalogue';

export { priceIn, pawnOffer, pawnPrice, fenceOffer } from './pricing';

export type StockEntry = Readonly<{
  itemId: string;
  label: string;
  category: Item['category'];
  weight: Item['weight'];
  serial: boolean;
  quantity: number;
  priceCents: number;
}>;

/** Starting shelf stock. Give each outlet a distinct seed to vary inventory across locations. */
export function stockOf(shopKind: string, year: number, seed: string): StockEntry[] {
  const shop = shopKey(shopKind);
  const index = priceIndex(year);
  if (!shop || index === null) return [];

  const stock: StockEntry[] = [];
  for (const item of ITEMS) {
    if (!forSale(item, year)) continue;
    const primary = item.shops.includes(shop);
    const used = shop === 'pawn';
    if (!primary && (!used || item.pawnShare === 0 || rand(seed, 'economy', shop, year, item.id, 'used') >= .15)) {
      continue;
    }
    const price = retailPrice(item, index, year);
    const [min, max] = used ? [1, 2] : item.stock;
    const roll = rand(seed, 'economy', shop, year, item.id, 'quantity');
    stock.push({
      itemId: item.id,
      label: item.label,
      category: item.category,
      weight: item.weight,
      serial: item.serialFrom !== null && year >= item.serialFrom,
      quantity: min + Math.floor(roll * (max - min + 1)),
      priceCents: used ? usedAsk(price, item.pawnShare) : price,
    });
  }
  return stock;
}

export type ShopStock = Readonly<{
  shopKind: ShopKind | null;
  elapsedDays: number;
  entries: readonly StockEntry[];
}>;

const deliveryDays: Record<ShopKind, number> = {
  general: 7, market: 1, hardware: 7, pharmacy: 3, five_and_dime: 7,
  pawn: 7, gas_station: 3, diner: 1, clothing: 14,
};

export function openStock(shopKind: string, year: number, seed: string): ShopStock {
  return { shopKind: shopKey(shopKind) ?? null, elapsedDays: 0, entries: stockOf(shopKind, year, seed) };
}

export function quantityOf(stock: ShopStock, itemId: string): number {
  return stock.entries.find(entry => entry.itemId === itemId)?.quantity ?? 0;
}

/** All-or-nothing. Compare the returned quantity before moving money or recording a theft. */
export function take(stock: ShopStock, itemId: string, n: number): ShopStock {
  if (!Number.isSafeInteger(n) || n <= 0 || quantityOf(stock, itemId) < n) return stock;
  return {
    ...stock,
    entries: stock.entries.map(entry => entry.itemId === itemId
      ? { ...entry, quantity: entry.quantity - n }
      : entry),
  };
}

/** Advance by whole elapsed days, retaining the shelf until a delivery crosses the clock. */
export function restock(stock: ShopStock, days: number, year: number, seed: string): ShopStock {
  const elapsedDays = stock.elapsedDays + days;
  const shop = stock.shopKind;
  if (!shop || !Number.isSafeInteger(days) || days <= 0 || !Number.isSafeInteger(elapsedDays)
    || priceIndex(year) === null) return stock;
  const interval = deliveryDays[shop];
  const delivery = Math.floor(elapsedDays / interval);
  if (delivery === Math.floor(stock.elapsedDays / interval)) return { ...stock, elapsedDays };

  // The last delivery sets the shelf to capacity. Absolute delivery numbers keep split calls equivalent.
  const deliverySeed = JSON.stringify([seed, delivery]);
  const entries = stockOf(shop, year, deliverySeed).map(entry => {
    const adjustment = rand(seed, 'economy_delivery_price', shop, year, delivery, entry.itemId) * .08 - .03;
    return { ...entry, priceCents: Math.max(1, Math.round(entry.priceCents * (1 + adjustment))) };
  });
  return { shopKind: shop, elapsedDays, entries };
}

export { townFences, fenceLeads, fenceDeal, fenceRisk } from './fence';
export type { Fence, FenceDeal, FenceLead, FencePerson } from './fence';
export { hoursOf, isOpen, staffed, type OpeningSpan, type ShopDate } from './hours';
