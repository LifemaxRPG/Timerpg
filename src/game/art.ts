/** Which sprite family draws each business kind (see scripts/extract-assets.mjs). */
const SHOP_ART: Record<string, string> = {
  Bakery: 'Bakery',
  Butcher: 'Butchery',
  'Ice Cream Parlor': 'Ice_Cream_Shop',
  'Music Store': 'Music_Store',
  'Bait Shop': 'Bait_Shop',
};
export const shopArtGroup = (kind: string): string => `shop:${SHOP_ART[kind] ?? 'Shop'}`;

export interface ManifestEntry { key: string; path: string; w: number; h: number }
export type Manifest = Record<string, ManifestEntry[]>;

/** Pick a variant by a stable 0..1 value. */
export const pickVariant = <T>(items: T[], look: number): T => items[Math.min(items.length - 1, Math.floor(look * items.length))]!;
