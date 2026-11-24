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
