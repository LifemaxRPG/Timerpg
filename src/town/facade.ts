import { rand } from '../sim/rng';
import { BUSINESS_KINDS } from '../sim/names';
import { colourOf, pickStable, stack, type BuildingArt, type Colour } from './buildings';
import { BILLBOARDS_FROM, CLASSIC_STOREFRONTS_UNTIL } from './era';

// Shop fronts: a ground floor that shows the business's goods, optionally a middle floor with windows,
// and a roof colour-matched to the ground floor, all 224px wide. The shop plot is 13 tiles (416px) tall;
// floors stack on the real 96px storefront (specialist sprites have tall signs above it, drawn over the
// floor above), so every kind can take a middle floor.

/** Sprite families (source art): sprite height and how many colourways (thirds: red, blue, gray). */
const BODY = 96;
const FAMILIES: Record<string, { total: number; h: number }> = {
  Shop: { total: 36, h: 96 },
  Bakery: { total: 12, h: 160 },
  Butchery: { total: 12, h: 160 },
  Music_Store: { total: 12, h: 128 },
  Bait_Shop: { total: 18, h: 192 },
  Ice_Cream_Shop: { total: 6, h: 160 },
  Gym: { total: 12, h: 160 },
  Condo: { total: 12, h: 96 },
};

const odds = (n: number): number[] => Array.from({ length: n }, (_, i) => i + 1).filter((i) => i % 2 === 1);

/**
 * Which ground-floor sprites honestly show each kind of business. The generic shop windows display
 * gowns (Plume), casual outfits (Groove, Coolies) and animal costumes (Bim Bum), so only dress,
 * clothing and toy shops may use them; there is no art for, say, a diner, a pharmacy or a barber.
 * `plain` kinds have no sign of their own in the pack (condo ground floors), so they always get the
 * painted sign board.
 */
const KIND_ART: Record<string, { fam: string; nums: number[]; plain?: boolean }> = {
  Bakery: { fam: 'Bakery', nums: odds(12) },
  Butcher: { fam: 'Butchery', nums: odds(12) },
  'Ice Cream Parlor': { fam: 'Ice_Cream_Shop', nums: odds(6) },
  'Music Store': { fam: 'Music_Store', nums: Array.from({ length: 12 }, (_, i) => i + 1) },
  'Bait Shop': { fam: 'Bait_Shop', nums: odds(18) },
  'Clothing Store': { fam: 'Shop', nums: [5, 7, 17, 19, 29, 31] },
  'Dress Shop': { fam: 'Shop', nums: [1, 13, 25] },
  'Toy Store': { fam: 'Shop', nums: [9, 21, 33] },
  Gym: { fam: 'Gym', nums: [1, 3, 5, 7, 9, 11] },
  Bank: { fam: 'Condo', nums: [3, 7, 11], plain: true },
  'Law Office': { fam: 'Condo', nums: [4, 8, 12], plain: true },
  "Doctor's Office": { fam: 'Condo', nums: [4, 8, 12], plain: true },
};
/** Exported for tests: every business kind must appear here (see housing.test.ts). */
export const KIND_ART_KINDS = (): string[] => Object.keys(KIND_ART);
const artFor = (kind: string): { fam: string; nums: number[]; plain?: boolean } => KIND_ART[kind] ?? KIND_ART['Clothing Store']!;

export const shopFamily = (businessKind: string): string => artFor(businessKind).fam;
/** Matches `slug` in scripts/extract-assets.mjs (painted-sign storefront sprite names). */
export const kindSlug = (kind: string): string => kind.toLowerCase().replace(/[^a-z]+/g, '_');
export const SHOP_PLOT_H = 13 * 32;
export const SHOP_W = 224;

/**
 * Roofs: 1-6 are 192 tall, 7-11 are 224. Only the tiled (pitched) roofs are used: the flat
 * ones are drawn from above and read as blank gray boxes. Colour-matched to the ground floor.
 */
const ROOF_H = (n: number): number => (n <= 6 ? 192 : 224);
const ROOFS: Record<'red' | 'blue' | 'gray', number[]> = { red: [2, 8], blue: [4, 9], gray: [6, 11] };
/** Rooftop billboards (160x96) mounted on some roofs. */
const BILLBOARDS = [12, 13, 14, 15, 16];

/** Every sprite a shop front can use, with sizes (tests check them against the manifest). */
export function allFacadeArt(): { key: string; w: number; h: number }[] {
  const out: { key: string; w: number; h: number }[] = [];
  for (const kind of BUSINESS_KINDS) {
    const { fam, nums, plain } = artFor(kind);
    for (const n of nums) {
      if (!plain) out.push({ key: `shop_${fam}_${n}`, w: SHOP_W, h: FAMILIES[fam]!.h });
      out.push({ key: `classic_${kindSlug(kind)}_${n}`, w: SHOP_W, h: BODY }); // painted-sign version
    }
  }
  for (let n = 1; n <= 11; n++) out.push({ key: `roof_${n}`, w: SHOP_W, h: ROOF_H(n) });
  for (let n = 1; n <= 18; n++) out.push({ key: `middle_${n}`, w: SHOP_W, h: 128 });
  for (const n of BILLBOARDS) out.push({ key: `roofprop_${n}`, w: 160, h: 96 });
  return out;
}

/**
 * The sprite stack for a business's shop front. Deterministic in (kind, look, era): before 1975 shops
 * have painted sign boards, after that the pack's big modern signs; billboards only from the mid-70s.
 */
export function shopFacade(businessKind: string, look: number, year = 2025, shellColour?: Colour): BuildingArt {
  const { fam, nums, plain } = artFor(businessKind);
  const f = FAMILIES[fam]!;
  const compatible = nums.filter((n) => shellColour === undefined || colourOf(n, f.total) === shellColour);
  const ground = Number(pickStable(compatible.map((i) => ({ id: String(i) })), look, `ground:${businessKind}`).id);
  const colour = colourOf(ground, f.total);
  const r = (salt: string): number => rand('facade', salt, look.toFixed(6));
  const painted = plain || year < CLASSIC_STOREFRONTS_UNTIL;

  // Floors stack on the storefront (BODY), not the whole sprite: ground + middle + roof must fit the plot.
  const withMiddle = SHOP_PLOT_H - BODY >= 128 + 192 && r('mid') < 0.7;
  const left = SHOP_PLOT_H - BODY - (withMiddle ? 128 : 0);
  const roofs = ROOFS[colour].filter((id) => ROOF_H(id) <= left).map((id) => ({ id: String(id) }));
  const roof = Number(pickStable(roofs, look, `roof:${colour}`).id);
  const middle = colour === 'red' ? 1 : colour === 'blue' ? 7 : 13;

  const parts = [
    painted
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
  return { layers, colour, w: SHOP_W, h: s.h, footRows: 5, capacity: 1 };
}
