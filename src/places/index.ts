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
const eraItems: readonly (readonly [kind: MenuKind, from: number, through: number, row: MenuRow])[] = [
  ['diner', 1880, 1940, ['oatmeal_bowl', 'Bowl of oatmeal', 'diner', 'oatmeal_bowl']],
  ['diner', 1935, 1985, ['milkshake', 'Milkshake', 'snack', 'milkshake']],
  ['diner', 1975, 2030, ['salad_plate', 'Garden salad plate', 'diner', 45]],
  ['drive_in', 1950, 2030, ['onion_rings', 'Onion rings', 'snack', 24]],
  ['bakery', 1970, 2030, ['granola_square', 'Granola square', 'snack', 15]],
];

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
  const eraRows = eraItems.filter(([venue, from, through]) => venue === kind && year >= from && year <= through)
    .map(([, , , row]) => row);
  const rows = kind === 'bar' || kind === 'tavern' || kind === 'speakeasy' ? menus[kind] : [...menus[kind], ...eraRows, special];
  return rows.flatMap(([id, label, meal, pricing]) => {
    const priceCents = typeof pricing === 'string' ? priceIn(pricing, year)
      : Math.max(1, Math.round(pricing * index / priceIndex(1952)!));
    return priceCents === null ? [] : [{ id, label, priceCents, meal }];
  });
}

const lodgingKinds = {
  grand_hotel: ['hotel', 2.4, 'private', 'porter service'],
  commercial_hotel: ['hotel', 1.15, 'private', 'writing desk'],
  station_hotel: ['hotel', 5 / 7, 'shared', 'washbasin'],
  flophouse: ['hotel', .29, 'shared', 'bunk and locker'],
  motel: ['motel', 1, 'private', 'parking'],
  boarding_house: ['room_and_board', 1, 'shared', 'two meals per day'],
} as const;

export type LodgingPlaceKind = keyof typeof lodgingKinds;

export function lodgingOffers(kind: LodgingPlaceKind, year: number, seed: string) {
  if (!Object.prototype.hasOwnProperty.call(lodgingKinds, kind)) return [];
  const [category, multiplier, bathroom, amenity] = lodgingKinds[kind];
  const lodging = lodgingOf(category, year);
  if (!lodging) return [];
  const rooms = 2 + Math.floor(rand(seed, 'lodging_rooms', kind, year) * (kind === 'grand_hotel' ? 24 : 8));
  const rateCents = Math.max(1, Math.round(lodging.rentCents * multiplier));
  return [{
    id: kind, rooms, bathroom, included: [amenity], nightlyCents: Math.round(rateCents / lodging.periodDays),
    billingDays: lodging.periodDays, rateCents, depositCents: lodging.depositCents,
    includedMealsPerDay: lodging.includedMealsPerDay,
  }];
}

export function placeHours(kind: MenuKind, year: number, weekday: number, seed: string, date?: ShopDate) {
  if (!available(kind, year)) return [];
  const retail = kind === 'bakery' || kind === 'cafeteria';
  const standard = hoursOf(retail ? 'general' : 'diner', year, weekday, seed, date);
  return standard.map(span => {
    switch (kind) {
      case 'bar': case 'tavern': case 'speakeasy': return { open: 17, close: 24 };
      case 'drive_in': return { open: 11, close: 23 };
      case 'automat': return { open: span.open, close: 23 };
      case 'lunch_counter': case 'cafeteria': return { open: Math.max(8, span.open), close: Math.min(17, span.close) };
      default: return span;
    }
  });
}
