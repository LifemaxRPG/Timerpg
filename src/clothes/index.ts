import { priceIn, priceIndex } from '../economy';
import type { ClothingColor, CulpritAppearance, CulpritDescription } from '../crime/types';

type Slot = 'outerwear' | 'hat' | 'glasses' | 'scarf' | 'workwear' | 'uniform' | 'hair_dye';
type Garment = Readonly<{
  id: string; label: string; slot: Slot; color: ClothingColor; priceCents: number; shops: readonly string[];
}>;
type Row = readonly [id: string, label: string, slot: Slot, color: ClothingColor, from: number, to: number, pricing: number | string];

const rows: readonly Row[] = [
  ['brown_coat', 'Brown wool coat', 'outerwear', 'brown', 1880, 2030, 1100],
  ['gray_coat', 'Grey wool coat', 'outerwear', 'gray', 1880, 2030, 1100],
  ['work_jacket', 'Blue work jacket', 'outerwear', 'blue', 1900, 2030, 'jacket'],
  ['windbreaker', 'Red windbreaker', 'outerwear', 'red', 1960, 2030, 900],
  ['felt_hat', 'Brown felt hat', 'hat', 'brown', 1880, 2030, 300],
  ['baseball_cap', 'Navy baseball cap', 'hat', 'navy', 1940, 2030, 200],
  ['round_glasses', 'Round wire glasses', 'glasses', 'black', 1880, 2030, 350],
  ['dark_glasses', 'Dark glasses', 'glasses', 'black', 1930, 2030, 450],
  ['wool_scarf', 'Green scarf', 'scarf', 'green', 1880, 2030, 'scarf'],
  ['overalls', 'Blue overalls', 'workwear', 'blue', 1880, 2030, 'overalls'],
  ['shop_uniform', 'Tan shop uniform', 'uniform', 'tan', 1910, 2030, 1050],
  ['brown_dye', 'Brown hair dye', 'hair_dye', 'brown', 1907, 2030, 150],
];

export function wardrobeOf(year: number, shop?: string): Garment[] {
  const index = priceIndex(year);
  if (index === null) return [];
  const key = shop?.trim().toLowerCase().replace(/[ -]+/g, '_');
  return rows.flatMap(([id, label, slot, color, from, to, pricing]) => {
    const shops = slot === 'hair_dye' ? ['pharmacy'] : slot === 'glasses' ? ['clothing', 'pharmacy'] : ['clothing'];
    if (year < from || year > to || (key && !shops.includes(key === 'clothing_store' ? 'clothing' : key))) return [];
    const priceCents = typeof pricing === 'string' ? priceIn(pricing, year)
      : Math.max(1, Math.round(pricing * index / priceIndex(1952)!));
    return priceCents === null ? [] : [{ id, label, slot, color, priceCents, shops }];
  });
}

export type Outfit = Readonly<{
  garments: readonly Garment[];
  dyedHair?: CulpritAppearance['hair'];
}>;

export function appearanceOf(base: CulpritAppearance, outfit: Outfit) {
  const worn = (slot: Slot) => outfit.garments.find(item => item.slot === slot);
  const dominant = worn('outerwear') ?? worn('uniform') ?? worn('workwear');
  const appearance: CulpritAppearance = {
    ...base, hair: outfit.dyedHair ?? base.hair, clothingColor: dominant?.color ?? base.clothingColor,
  };
  const visible: CulpritDescription = { ...appearance };
  if (worn('hat')) delete visible.hair;
  return {
    appearance, visible,
    faceRecognizability: (worn('glasses') ? .8 : 1) * (worn('scarf') ? .65 : 1),
  };
}

const weights = { height: 2, build: 2, hair: 2, clothingColor: 3 } as const;

export function matchScore(description: CulpritDescription, appearance: CulpritDescription): number {
  let possible = 0;
  let matched = 0;
  for (const key of ['height', 'build', 'hair', 'clothingColor'] as const) {
    if (description[key] === undefined) continue;
    possible += weights[key];
    if (appearance[key] === description[key]) matched += weights[key];
    else if (appearance[key] === undefined) matched += weights[key] * .5;
  }
  if (description.vehicle) {
    possible += 2;
    matched += !appearance.vehicle ? 1 : Number(description.vehicle.kind === appearance.vehicle.kind)
      + Number(description.vehicle.color === appearance.vehicle.color);
  }
  return possible ? matched / possible : 0;
}

export type PurchaseContext = Readonly<{
  paidCash?: boolean; nervous?: boolean; publicCrimeDay?: number;
}>;

export function purchaseRecord(item: Garment, day: number, shop: string, context: PurchaseContext = {}) {
  const publicCrimeDay = context.publicCrimeDay;
  return {
    itemId: item.id, slot: item.slot, color: item.color, day, shop,
    paidCash: context.paidCash ?? null, nervous: context.nervous ?? null,
    shortlyAfterPublicCrime: publicCrimeDay === undefined ? null : day >= publicCrimeDay && day - publicCrimeDay <= 2,
  };
}
