import type { ShopDate } from '../economy';

export type CalendarDate = Readonly<{ year: number; month: number; day: number }>;
export type Season = 'winter' | 'spring' | 'summer' | 'autumn';
const months = ['Frostmonth', 'Thawmonth', 'Seedmonth', 'Rainmonth', 'Bloommonth', 'Sunmonth',
  'Highsummer', 'Fieldmonth', 'Harvestmonth', 'Leafmonth', 'Mistmonth', 'Longnight'] as const;
const weekdays = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'] as const;
const monthStarts = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334, 365] as const;

function leap(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function yearStart(year: number): number {
  const prior = year - 1;
  return 365 * prior + Math.floor(prior / 4) - Math.floor(prior / 100) + Math.floor(prior / 400);
}

function monthStart(year: number, month: number): number {
  return monthStarts[month - 1]! + (month > 2 && leap(year) ? 1 : 0);
}

export function dayNumberOf({ year, month, day }: CalendarDate): number | null {
  if (!Number.isInteger(year) || year < 1 || year > 9999 || !Number.isInteger(month)
    || month < 1 || month > 12 || !Number.isInteger(day) || day < 1) return null;
  const start = monthStart(year, month);
  if (day > monthStart(year, month + 1) - start) return null;
  return yearStart(year) + start + day - 1;
}

export function dateOf(dayNumber: number, startDate: CalendarDate = { year: 1, month: 1, day: 1 }) {
  const start = dayNumberOf(startDate);
  if (start === null || !Number.isSafeInteger(dayNumber)) return null;
  const absolute = start + dayNumber;
  if (absolute < 0 || absolute >= yearStart(10000)) return null;

  let lower = 1;
  let upper = 10000;
  while (upper - lower > 1) {
    const middle = Math.floor((lower + upper) / 2);
    if (yearStart(middle) <= absolute) lower = middle;
    else upper = middle;
  }
  const year = lower;
  const ordinal = absolute - yearStart(year);
  let month = 1;
  while (month < 12 && monthStart(year, month + 1) <= ordinal) month++;
  const day = ordinal - monthStart(year, month) + 1;
  const weekday = (absolute + 1) % 7;
  const season: Season = month <= 2 || month === 12 ? 'winter' : month <= 5 ? 'spring' : month <= 8 ? 'summer' : 'autumn';
  return {
    year, month, day, weekday, season, monthName: months[month - 1]!,
    weekdayName: weekdays[weekday]!, weekOfYear: Math.floor((ordinal + (yearStart(year) + 1) % 7) / 7) + 1,
  };
}

export function toShopDate(date: CalendarDate): ShopDate | null {
  return dayNumberOf(date) === null ? null : { month: date.month, day: date.day };
}
