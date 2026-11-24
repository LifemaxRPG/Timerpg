import { ITEMS, type Item } from './catalogue';
import { relativePrice } from './curves';
import { priceIndex } from './eras';

const ITEMS_BY_ID = new Map(ITEMS.map(item => [item.id, item]));

export function forSale(item: Item, year: number): boolean {
  return year >= item.from && year <= item.to;
}

export function retailPrice(item: Item, index: number, year: number): number {
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

export function usedAsk(price: number, share: number): number {
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
