import { rand } from '../sim/rng';
import { SHOP_KINDS, type ShopKind } from './catalogue';
import { validYear } from './eras';

const SHOP_ALIASES: Readonly<Record<string, ShopKind>> = {
  general_store: 'general',
  grocer: 'market',
  grocery: 'market',
  pawn_shop: 'pawn',
  clothing_store: 'clothing',
  hardware_store: 'hardware',
  drugstore: 'pharmacy',
};

export function shopKey(kind: string): ShopKind | undefined {
  const key = kind.trim().toLowerCase().replace(/[ -]+/g, '_');
  if (Object.prototype.hasOwnProperty.call(SHOP_ALIASES, key)) return SHOP_ALIASES[key];
  return SHOP_KINDS.find(shop => shop === key);
}

export type OpeningSpan = Readonly<{ open: number; close: number }>;
export type ShopDate = Readonly<{ month: number; day: number }>;

const normalHours: Record<ShopKind, readonly [open: number, close: number, rushStaff: number]> = {
  general: [8, 18, 3], market: [8, 19, 4], hardware: [8, 17, 3],
  pharmacy: [9, 18, 3], five_and_dime: [9, 18, 4], pawn: [10, 17, 2],
  gas_station: [7, 20, 3], diner: [6, 21, 4], clothing: [9, 18, 4],
};

const holidays = [
  { month: 12, day: 25, from: 1880 },
  { month: 1, day: 1, from: 1900 },
  { month: 7, day: 4, from: 1900, to: 1970 },
] as const;

export function hoursOf(
  shopKind: string, year: number, weekday: number, seed: string, date?: ShopDate,
): OpeningSpan[] {
  const shop = shopKey(shopKind);
  if (!shop || !validYear(year) || !Number.isInteger(weekday) || weekday < 0 || weekday > 6) return [];
  if (shop !== 'gas_station' && shop !== 'diner' && holidays.some(holiday =>
    holiday.month === date?.month && holiday.day === date?.day
    && year >= holiday.from && (!('to' in holiday) || year <= holiday.to))) return [];

  if (shop === 'gas_station' && year >= 1975) return [{ open: 0, close: 24 }];
  if (weekday === 0 && shop !== 'diner' && shop !== 'gas_station' && year < 1965) return [];

  const [start, end] = normalHours[shop];
  if (weekday === 0 && shop !== 'diner' && shop !== 'gas_station') return [{ open: Math.max(start, 10), close: Math.min(end, 17) }];
  const saturdayHalfDay = weekday === 6 && year < 1950 && shop !== 'diner' && shop !== 'gas_station';
  if (saturdayHalfDay) return [{ open: start, close: 13 }];

  const late = year >= 1950 && (shop === 'market' || shop === 'general' || shop === 'clothing');
  const laterEra = late && year >= 1970 ? 1 : 0;
  const outletVariation = late && rand(seed, 'shop_hours', shop, year) < .4 ? 1 : 0;
  const payday = late && weekday === 5 ? 1 : 0;
  return [{ open: start, close: end + laterEra + outletVariation + payday }];
}

export function isOpen(
  shopKind: string, year: number, weekday: number, hour: number, seed: string, date?: ShopDate,
): boolean {
  return hoursOf(shopKind, year, weekday, seed, date).some(span => hour >= span.open && hour < span.close);
}

export function staffed(
  shopKind: string, year: number, weekday: number, hour: number, seed = '', date?: ShopDate,
): number {
  const shop = shopKey(shopKind);
  if (!shop) return 0;
  const span = hoursOf(shop, year, weekday, seed, date).find(s => hour >= s.open && hour < s.close);
  if (!span) return 0;
  if (hour < span.open + 1 || hour >= 22 || hour < 6) return 1;
  if (hour >= 11 && hour < 14) return normalHours[shop][2];
  return Math.min(2, normalHours[shop][2]);
}
