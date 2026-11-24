import { relativePrice } from './curves';
import { rand } from '../sim/rng';
import { ITEMS, SHOP_KINDS, type Item, type ShopKind } from './catalogue';
import { priceIndex } from './eras';

export { FIRST_YEAR, LAST_YEAR, REFERENCE_YEAR, formatMoney, priceIndex, wage, type Job } from './eras';
export { SHOP_KINDS, type Category, type ShopKind, type Weight } from './catalogue';

const ITEMS_BY_ID = new Map(ITEMS.map(item => [item.id, item]));
const SHOP_ALIASES: Readonly<Record<string, ShopKind>> = {
  general_store: 'general',
  grocer: 'market',
  grocery: 'market',
  pawn_shop: 'pawn',
  clothing_store: 'clothing',
  hardware_store: 'hardware',
  drugstore: 'pharmacy',
};

function forSale(item: Item, year: number): boolean {
  return year >= item.from && year <= item.to;
}

function retailPrice(item: Item, index: number, year: number): number {
  return Math.max(1, Math.round(item.cents2025 * index * relativePrice(item, year)));
}

function listedItem(itemId: string, year: number): { item: Item; price: number } | null {
  const item = ITEMS_BY_ID.get(itemId);
  const index = priceIndex(year);
  return item && index !== null && forSale(item, year) ? { item, price: retailPrice(item, index, year) } : null;
}

export function priceIn(itemId: string, year: number): number | null {
  return listedItem(itemId, year)?.price ?? null;
}

function roundOffer(cents: number): number {
  const increment = cents >= 500 ? 25 : 1;
  return Math.round(cents / increment) * increment;
}

function cleanOffer(price: number, share: number): number {
  return Math.min(price - 1, roundOffer(price * share));
}

/** What the pawn shop pays the player, in nominal cents. */
export function pawnOffer(itemId: string, year: number, hot: boolean): number | null {
  const listing = listedItem(itemId, year);
  if (!listing) return null;
  const clean = cleanOffer(listing.price, listing.item.pawnShare);
  return hot && clean > 0 ? Math.floor(clean / 2) : clean;
}

function usedAsk(price: number, share: number): number {
  return Math.min(price, Math.max(cleanOffer(price, share) + 1, Math.round(price * .65)));
}

/** What the pawn shop charges the player for a used item, in nominal cents. */
export function pawnPrice(itemId: string, year: number): number | null {
  const listing = listedItem(itemId, year);
  if (!listing || listing.item.pawnShare === 0) return null;
  return usedAsk(listing.price, listing.item.pawnShare);
}

/** What the back-room buyer pays, in nominal cents, regardless of provenance. */
export function fenceOffer(itemId: string, year: number): number | null {
  const clean = pawnOffer(itemId, year, false);
  return clean === null ? null : Math.floor(clean / 3);
}

export type StockEntry = Readonly<{
  itemId: string;
  label: string;
  category: Item['category'];
  weight: Item['weight'];
  serial: boolean;
  quantity: number;
  priceCents: number;
}>;

function shopKey(kind: string): ShopKind | undefined {
  const key = kind.trim().toLowerCase().replace(/[ -]+/g, '_');
  if (Object.prototype.hasOwnProperty.call(SHOP_ALIASES, key)) return SHOP_ALIASES[key];
  return SHOP_KINDS.find(shop => shop === key);
}

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
