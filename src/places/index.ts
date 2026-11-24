import { hoursOf, lodgingOf, priceIn, priceIndex, type ShopDate } from '../economy';
import type { Meal } from '../needs';
import { rand } from '../sim/rng';

const menuKinds = {
  diner: [1880, 2030], lunch_counter: [1890, 2030], automat: [1902, 1991],
  drive_in: [1921, 2030], cafeteria: [1890, 2030], bakery: [1880, 2030],
  bar: [1880, 2030], tavern: [1880, 2030], speakeasy: [1920, 1933],
} as const;

export type MenuKind = keyof typeof menuKinds;
type MenuItem = Readonly<{ id: string; label: string; priceCents: number; meal: Meal }>;
type MenuRow = readonly [id: string, label: string, meal: Meal, pricing: number | string];

const coffee = ['diner_coffee', 'Counter coffee', 'snack', 10] as const;
const pie = ['apple_pie', 'Apple pie slice', 'snack', 20] as const;
const sandwich = ['ham_sandwich', 'Ham sandwich', 'diner', 'ham_sandwich'] as const;
const barMenu = [
  ['house_beer', 'House beer', 'snack', 20], ['pretzels', 'Salted pretzels', 'snack', 12],
] as const;
const menus: Record<MenuKind, readonly MenuRow[]> = {
  diner: [coffee, ['blue_plate', 'Blue-plate special', 'diner', 65], pie, ['breakfast', 'Egg-and-toast breakfast', 'diner', 35]],
  lunch_counter: [coffee, sandwich, pie],
  automat: [coffee, pie, ['baked_mac', 'Baked macaroni', 'diner', 32]],
  drive_in: [['burger', 'Hamburger', 'diner', 'burger'], ['fries', 'Basket of fries', 'snack', 'fries'],
    ['milkshake', 'Milkshake', 'snack', 'milkshake']],
  cafeteria: [sandwich, ['soup_bowl', 'Bowl of vegetable soup', 'diner', 'soup_bowl'], pie],
  bakery: [['bread', 'Loaf of bread', 'grocery_cooked', 'bread'], ['roll', 'Sweet roll', 'snack', 12], pie],
  bar: barMenu, tavern: [...barMenu, sandwich], speakeasy: barMenu,
};
const specials: readonly MenuRow[] = [
  ['custard', 'Baked custard', 'snack', 16],
  ['ginger_cake', 'Ginger cake', 'snack', 18],
  ['rice_pudding', 'Rice pudding', 'snack', 14],
];

function available(kind: MenuKind, year: number): boolean {
  if (!Object.prototype.hasOwnProperty.call(menuKinds, kind)) return false;
  const [from, through] = menuKinds[kind];
  return Number.isInteger(year) && year >= from && year <= through
    && !((kind === 'bar' || kind === 'tavern') && year >= 1920 && year <= 1933);
}

export function menuOf(kind: MenuKind, year: number, seed: string): MenuItem[] {
  const index = priceIndex(year);
  if (index === null || !available(kind, year)) return [];
  const special = specials[Math.floor(rand(seed, 'menu_special', kind, year) * specials.length)]!;
  const rows = kind === 'bar' || kind === 'tavern' || kind === 'speakeasy' ? menus[kind] : [...menus[kind], special];
  return rows.flatMap(([id, label, meal, pricing]) => {
    const priceCents = typeof pricing === 'string' ? priceIn(pricing, year)
      : Math.max(1, Math.round(pricing * index / priceIndex(1952)!));
    return priceCents === null ? [] : [{ id, label, priceCents, meal }];
  });
}
