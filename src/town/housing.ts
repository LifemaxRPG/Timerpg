import { rand } from '../sim/rng';
import { layer, pickStable, stack, type BuildingArt, type Colour } from './buildings';
import type { LotKind } from './plan';

// Housing catalogue. Wealth decides the tier (layout.ts); within a tier the plot, the era and a
// stable per-lot "look" decide which sprite stands there. Each tier has its own plot size in plan.ts.
//   small      mobile homes (modest)            6x6     medium     bigger mobile homes   8x8
//   house      terraced family houses          12x15    villa      the original villas  9x13
//   estate     country/japanese/modern houses  23x16    apartment  condo blocks, several households each  7x13

export type ResidentialKind = Exclude<LotKind, 'shop'>;
export const RESIDENTIAL: readonly ResidentialKind[] = ['estate', 'villa', 'house', 'medium', 'small', 'apartment'];

export interface Variant {
  id: string;
  /** First year this variant is available (default: always). */
  from?: number;
  art: (look: number) => BuildingArt;
}

const single = (key: string, w: number, h: number, footRows: number, capacity = 1): BuildingArt => ({
  layers: [layer(key, w, h)], w, h, footRows, capacity,
});

const range = (a: number, b: number): number[] => Array.from({ length: b - a + 1 }, (_, i) => i + a);

const mobile = (prefix: string, w: number): Variant[] =>
  range(1, 8).map((n) => ({ id: `${prefix}_${n}`, art: () => single(`${prefix}_${n}`, w, n <= 4 ? 192 : 256, 4) }));

/** Pitched roofs per colour for modular condos (all 192 tall so ground+middle+roof = 13 tiles). */
const CONDO_ROOF: Record<Colour, number[]> = { red: [2], blue: [4], gray: [6] };

const condoStack = (colour: Colour, look: number): BuildingArt => {
  const ci = ['red', 'blue', 'gray'].indexOf(colour);
  const r = (salt: string): number => rand('condo', salt, look.toFixed(6));
  const ground = ci * 4 + 1 + Math.floor(r('g') * 4);
  const middle = ci * 6 + 1 + Math.floor(r('m') * 6);
  const roofs = CONDO_ROOF[colour];
  const roof = roofs[Math.floor(r('r') * roofs.length)]!;
  const s = stack([
    { key: `condo_ground_${ground}`, w: 224, h: 96 },
    { key: `middle_${middle}`, w: 224, h: 128 },
    { key: `roof_${roof}`, w: 224, h: 192 },
  ]);
  return { ...s, footRows: 4, capacity: 4 };
};

const CATALOG: Record<ResidentialKind, Variant[]> = {
  small: mobile('house_small', 192),
  medium: mobile('house_medium', 256),
  villa: range(1, 5).map((n) => ({ id: `villa_${n}`, art: () => single(`villa_${n}`, 288, 416, 6) })),
  house: [1, 2, 4, 5, 6].map((n) => ({ id: `house_terraced_${n}`, art: () => single(`house_terraced_${n}`, 384, 480, 5) })),
  estate: [
    { id: 'estate_country_house', art: () => single('estate_country_house', 576, 512, 6) },
    { id: 'estate_japanese_house', art: () => single('estate_japanese_house', 480, 448, 6) },
    { id: 'estate_one_story_house', art: () => single('estate_one_story_house', 512, 448, 5) },
    { id: 'estate_modern_house', from: 1985, art: () => single('estate_modern_house', 736, 448, 5) },
  ],
  apartment: [
    ...(['5_1', '5_2', '5_3'] as const).map((n): Variant => ({ id: `condo_${n}`, art: () => single(`condo_${n}`, 192, 352, 4, 6) })),
    { id: 'condo_3_44', art: () => single('condo_3_44', 192, 256, 4, 3) },
    { id: 'condo_3_45', art: () => single('condo_3_45', 192, 320, 4, 4) },
    { id: 'condo_9', art: () => single('condo_9', 192, 384, 4, 6) },
    ...(['red', 'blue', 'gray'] as const).map((c): Variant => ({ id: `condo_modular_${c}`, from: 1965, art: (look) => condoStack(c, look) })),
  ],
};

export const variantsFor = (tier: ResidentialKind, year: number): Variant[] => CATALOG[tier].filter((v) => v.from === undefined || year >= v.from);

/** The sprite stack for a house plot. Stable per (tier, look, era). */
export function houseArt(tier: ResidentialKind, look: number, year: number): BuildingArt {
  return pickStable(variantsFor(tier, year), look, tier).art(look);
}

/** Households an apartment plot holds in a given year. */
export const apartmentCapacity = (look: number, year: number): number => houseArt('apartment', look, year).capacity;

/** Every sprite the housing catalogue can ask for (tests check them against the manifest and plot sizes). */
export function allHousingArt(): { key: string; w: number; h: number; tier: ResidentialKind }[] {
  const out: { key: string; w: number; h: number; tier: ResidentialKind }[] = [];
  for (const tier of RESIDENTIAL)
    for (const v of CATALOG[tier])
      for (const probe of [0.05, 0.35, 0.65, 0.95]) for (const l of v.art(probe).layers) out.push({ key: l.key, w: l.w, h: l.h, tier });
  return out;
}

/** Plot-kind choice for the two sides of a residential band; the villa district gets villas + estates. */
export function residentialBand(roll: (side: 'n' | 's') => number, villaDistrict: boolean): [LotKind, LotKind] {
  if (villaDistrict) return ['villa', 'estate'];
  const pick = (r: number): LotKind => (r < 0.36 ? 'small' : r < 0.66 ? 'medium' : r < 0.82 ? 'house' : 'apartment');
  return [pick(roll('n')), pick(roll('s'))];
}

/** Which low-wealth households go into apartments: singles and couples, below ~70% of median wealth. */
export const apartmentEligible = (residents: number, wealth: number, median: number): boolean => residents <= 2 && wealth < median * 0.7;
