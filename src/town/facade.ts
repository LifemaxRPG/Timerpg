import { rand } from '../sim/rng';
import { BILLBOARDS_FROM, CLASSIC_STOREFRONTS_UNTIL } from './era';
import { BUSINESS_KINDS } from '../sim/names';
import { colourOf, pickStable, stack, type BuildingArt, type Colour } from './buildings';

// Shop fronts: a ground floor of the business's type, optionally a middle floor with windows,
// and a roof colour-matched to the ground floor, all 224px wide. The shop plot is 13 tiles
// (416px) tall; generic shops (96px ground floor) can take a middle floor, specialist ones
// (160-192px ground floors with big signs) are ground floor + roof.

/** Open-for-business ground-floor sprites per family (mirrors scripts/extract-assets.mjs SHOPS). */
const odds = (n: number): number[] => Array.from({ length: n }, (_, i) => i + 1).filter((i) => i % 2 === 1);
/** `h` is the sprite height; the real storefront is `BODY` tall, the rest is sign overhang. */
const BODY = 96;
const FAMILIES: Record<string, { total: number; nums: number[]; h: number }> = {
  Shop: { total: 36, nums: [1, 5, 7, 9, 13, 17, 19, 21, 25, 29, 31, 33], h: 96 },
  Bakery: { total: 12, nums: odds(12), h: 160 },
  Butchery: { total: 12, nums: odds(12), h: 160 },
  Music_Store: { total: 12, nums: Array.from({ length: 12 }, (_, i) => i + 1), h: 128 },
  Bait_Shop: { total: 18, nums: odds(18), h: 192 },
  Ice_Cream_Shop: { total: 6, nums: odds(6), h: 160 },
};
const SHOP_ART: Record<string, string> = {
  Bakery: 'Bakery',
  Butcher: 'Butchery',
  'Ice Cream Parlor': 'Ice_Cream_Shop',
  'Music Store': 'Music_Store',
  'Bait Shop': 'Bait_Shop',
};
export const shopFamily = (businessKind: string): string => SHOP_ART[businessKind] ?? 'Shop';
/** Matches `slug` in scripts/extract-assets.mjs (classic storefront sprite names). */
export const kindSlug = (kind: string): string => kind.toLowerCase().replace(/[^a-z]+/g, '_');
export const SHOP_PLOT_H = 13 * 32;
export const SHOP_W = 224;

/**
 * Roofs: 1-6 are 192 tall, 7-11 are 224. Only the tiled (pitched) roofs are used: the flat
 * ones are drawn from above and read as blank gray boxes. Colour-matched to the ground floor.
 */
const ROOF_H = (n: number): number => (n <= 6 ? 192 : 224);
const ROOFS: Record<Colour, number[]> = { red: [2, 8], blue: [4, 9], gray: [6, 11] };
/** Rooftop billboards (160x96) mounted on some roofs. */
const BILLBOARDS = [12, 13, 14, 15, 16];

/** Every sprite a shop front can use, with sizes (tests check them against the manifest). */
export function allFacadeArt(): { key: string; w: number; h: number }[] {
  const out: { key: string; w: number; h: number }[] = [];
  for (const [fam, f] of Object.entries(FAMILIES)) for (const n of f.nums) out.push({ key: `shop_${fam}_${n}`, w: SHOP_W, h: f.h });
  for (let n = 1; n <= 11; n++) out.push({ key: `roof_${n}`, w: SHOP_W, h: ROOF_H(n) });
  for (let n = 1; n <= 18; n++) out.push({ key: `middle_${n}`, w: SHOP_W, h: 128 });
  for (const n of BILLBOARDS) out.push({ key: `roofprop_${n}`, w: 160, h: 96 });
  // Plain pre-1975 storefronts: one per business kind and ground-floor variant, storefront only (96px).
  for (const kind of BUSINESS_KINDS) for (const n of FAMILIES[shopFamily(kind)]!.nums) out.push({ key: `classic_${kindSlug(kind)}_${n}`, w: SHOP_W, h: BODY });
  return out;
}

/** The sprite stack for a business's shop front. Deterministic in (kind, look, era); billboards only from the mid-70s. */
export function shopFacade(businessKind: string, look: number, year = 2025): BuildingArt {
  const fam = shopFamily(businessKind);
  const f = FAMILIES[fam]!;
  const n = pickStable(f.nums.map((i) => ({ id: String(i) })), look, `ground:${fam}`).id;
  const ground = Number(n);
  const colour = colourOf(ground, f.total);
  const r = (salt: string): number => rand('facade', salt, look.toFixed(6));

  // Floors stack on the storefront (BODY), not the whole sprite: ground + middle + roof must fit the plot.
  const withMiddle = SHOP_PLOT_H - BODY >= 128 + 192 && r('mid') < 0.7;
  const left = SHOP_PLOT_H - BODY - (withMiddle ? 128 : 0);
  const roofs = ROOFS[colour].filter((id) => ROOF_H(id) <= left).map((id) => ({ id: String(id) }));
  const roof = Number(pickStable(roofs, look, `roof:${colour}`).id);
  const middle = colour === 'red' ? 1 : colour === 'blue' ? 7 : 13;

  const parts = [
    // Before 1975: the plain painted-sign version (already just the 96px storefront).
    year < CLASSIC_STOREFRONTS_UNTIL
      ? { key: `classic_${kindSlug(businessKind)}_${ground}`, w: SHOP_W, h: BODY, rise: BODY }
      : { key: `shop_${fam}_${ground}`, w: SHOP_W, h: f.h, rise: BODY },
    ...(withMiddle ? [{ key: `middle_${middle + Math.floor(r('midv') * 6)}`, w: SHOP_W, h: 128 }] : []),
    { key: `roof_${roof}`, w: SHOP_W, h: ROOF_H(roof) },
  ];
  const s = stack(parts);
  const layers = [...s.layers];
  if (year >= BILLBOARDS_FROM && r('prop') < 0.3) {
    const pn = BILLBOARDS[Math.floor(r('which') * BILLBOARDS.length)]!;
    layers.push({ key: `roofprop_${pn}`, w: 160, h: 96, y: layers[layers.length - 1]!.y + 12, dx: 0 });
  }
  return { layers, w: SHOP_W, h: s.h, footRows: 5, capacity: 1 };
}
