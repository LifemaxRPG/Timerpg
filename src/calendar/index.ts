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

type Holiday = Readonly<{
  name: string;
  month: number;
  rule: { day: number } | { weekday: number; occurrence: number };
  from: number;
  through?: number;
  closes: readonly string[];
  opens: readonly string[];
}>;

const holidays: readonly Holiday[] = [
  { name: 'Hearth Day', month: 1, rule: { day: 5 }, from: 1800, closes: ['shops', 'banks'], opens: ['community supper'] },
  { name: 'Founders Day', month: 2, rule: { weekday: 1, occurrence: 3 }, from: 1880, closes: ['schools', 'banks'], opens: ['parade'] },
  { name: 'First Furrow', month: 3, rule: { day: 21 }, from: 1800, through: 1964, closes: ['schools'], opens: ['seed fair'] },
  { name: 'Bridge Day', month: 5, rule: { day: 9 }, from: 1920, closes: ['post office'], opens: ['river fair'] },
  { name: 'Longlight Fair', month: 6, rule: { day: 21 }, from: 1800, closes: [], opens: ['street fair'] },
  { name: 'Works Day', month: 8, rule: { weekday: 1, occurrence: 1 }, from: 1910, closes: ['post office', 'banks'], opens: ['bandstand concerts'] },
  { name: 'Lantern Festival', month: 9, rule: { day: 15 }, from: 1880, closes: ['banks', 'post office'], opens: ['lantern fair'] },
  { name: 'River Remembrance', month: 11, rule: { day: 8 }, from: 1947, closes: ['schools'], opens: ['river memorial'] },
  { name: 'Night of Windows', month: 12, rule: { day: 18 }, from: 1970, closes: [], opens: ['window walk'] },
];

export function holidaysOn(date: CalendarDate, year = date.year) {
  const absolute = dayNumberOf({ ...date, year });
  if (absolute === null) return [];
  const weekday = (absolute + 1) % 7;
  const occurrence = Math.ceil(date.day / 7);
  return holidays.filter(holiday => {
    const { rule } = holiday;
    return holiday.month === date.month && year >= holiday.from && year <= (holiday.through ?? 9999)
      && ('day' in rule ? date.day === rule.day : weekday === rule.weekday && occurrence === rule.occurrence);
  }).map(({ name, closes, opens }) => ({ name, closes: [...closes], opens: [...opens] }));
}

export function isPayday(date: CalendarDate): boolean {
  const absolute = dayNumberOf(date);
  if (absolute === null || (absolute + 1) % 7 !== 5) return false;
  return date.year < 1970 || Math.floor((absolute + 1) / 7) % 2 === 0;
}

export function isMarketDay(date: CalendarDate): boolean {
  const absolute = dayNumberOf(date);
  if (absolute === null) return false;
  const weekday = (absolute + 1) % 7;
  return weekday === 6 || (date.year >= 1960 && weekday === 3);
}

export function schoolTerm(date: CalendarDate): 'spring' | 'autumn' | null {
  if (dayNumberOf(date) === null) return null;
  if (date.month >= 1 && date.month <= 5) return 'spring';
  if (date.month >= 9 && date.month <= 12 && (date.month !== 12 || date.day < 20)) return 'autumn';
  return null;
}

const daylightBySeason: Record<Season, readonly [sunrise: number, sunset: number]> = {
  winter: [7.5, 16.5], spring: [6, 19], summer: [5.5, 20.5], autumn: [6.75, 18],
};

export function daylight(date: CalendarDate): { sunrise: number; sunset: number } | null {
  const absolute = dayNumberOf(date);
  const resolved = absolute === null ? null : dateOf(absolute);
  if (!resolved) return null;
  const [sunrise, sunset] = daylightBySeason[resolved.season];
  return { sunrise, sunset };
}
