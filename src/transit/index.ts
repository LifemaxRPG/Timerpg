import { priceIndex } from '../economy';
import { rand } from '../sim/rng';

const modes = {
  horsecar: { from: 1880, to: 1915, speed: 10, wait: 3, pricing: [5, 0] },
  streetcar: { from: 1888, to: 2030, speed: 19, wait: 3, pricing: [5, 0] },
  motor_bus: { from: 1905, to: 1975, speed: 24, wait: 4, pricing: [7, 0] },
  taxi: { from: 1907, to: 2030, speed: 32, wait: 1, pricing: [25, 13] },
  interurban: { from: 1895, to: 1965, speed: 38, wait: 8, pricing: [6, 2] },
  railway: { from: 1880, to: 2030, speed: 52, wait: 12, pricing: [25, 4] },
  city_bus: { from: 1925, to: 2030, speed: 23, wait: 4, pricing: [8, 0] },
  intercity_coach: { from: 1930, to: 2030, speed: 49, wait: 10, pricing: [21, 3] },
} as const;

export type TransitMode = keyof typeof modes;
export type RailClass = 'third' | 'second' | 'first';
const classRate: Record<RailClass, number> = { third: 1, second: 1.45, first: 2 };

function modeFor(mode: TransitMode, year: number) {
  if (!Object.prototype.hasOwnProperty.call(modes, mode) || !Number.isInteger(year)) return null;
  const spec = modes[mode];
  return year >= spec.from && year <= spec.to ? spec : null;
}

export function classesOf(year: number): RailClass[] {
  if (!modeFor('railway', year)) return [];
  return year < 1956 ? ['third', 'second', 'first'] : ['second', 'first'];
}

export function fare(mode: TransitMode, year: number, distanceKm: number, railClass?: RailClass): number | null {
  const spec = modeFor(mode, year);
  const index = priceIndex(year);
  if (!spec || index === null || !Number.isFinite(distanceKm) || distanceKm < 0) return null;

  const classes = classesOf(year);
  const ticketClass = railClass ?? classes[0]!;
  if (railClass !== undefined && (mode !== 'railway' || !classes.includes(railClass))) return null;
  const [base, perKm] = spec.pricing;
  const relativeRate = year <= 1925 ? 1 : 1 + (year - 1925) * (mode === 'taxi' ? .004 : .0015);
  const multiplier = mode === 'railway' ? classRate[ticketClass] : 1;
  const cents = Math.max(1, Math.round((base + perKm * distanceKm) * index / .065 * relativeRate * multiplier));
  return Number.isSafeInteger(cents) ? cents : null;
}

export function travelTime(mode: TransitMode, year: number, distanceKm: number): number | null {
  const spec = modeFor(mode, year);
  if (!spec || !Number.isFinite(distanceKm) || distanceKm < 0) return null;
  if (distanceKm === 0) return 0;
  const gain = mode === 'horsecar' ? 0 : Math.max(0, year - 1925) * .004;
  const minutes = Math.ceil(spec.wait + 60 * distanceKm / (spec.speed * (1 + gain)));
  return Number.isSafeInteger(minutes) ? minutes : null;
}

export type TransitRoute = Readonly<{
  id: string;
  mode: TransitMode;
  /** Local service-day minutes for railway routes. */
  trainMinutes?: readonly number[];
}>;

const daytime = [360, 420, 540, 960, 1140, 1320, 1440] as const;
const service = {
  horsecar: [18, 24, 30, 1200],
  streetcar: [12, 12, 20, 1420],
  motor_bus: [12, 20, 30, 1360],
  city_bus: [8, 15, 24, 1420],
  interurban: [20, 30, 60, 1380],
  intercity_coach: [45, 60, 90, 1380],
} as const;

function validMinute(minute: number): boolean {
  return Number.isInteger(minute) && minute >= 0 && minute < 1440;
}

/** Remaining departure minutes on the requested local day, including the minute at fromHour. */
export function departures(route: TransitRoute, year: number, weekday: number, fromHour: number, seed: string): number[] {
  if (!modeFor(route.mode, year) || !Number.isInteger(weekday) || weekday < 0 || weekday > 6
    || !Number.isFinite(fromHour) || fromHour < 0 || fromHour >= 24) return [];

  const fromMinute = fromHour * 60;
  if (route.mode === 'railway') {
    const trains = route.trainMinutes ?? [420, 750, 1050, 1335];
    return [...new Set(trains.filter(minute => validMinute(minute) && minute >= fromMinute))].sort((a, b) => a - b);
  }
  if (route.mode === 'taxi') {
    const pickup = Math.ceil(fromMinute);
    return validMinute(pickup) ? [pickup] : [];
  }

  const [rush, midday, evening, last] = service[route.mode];
  const weekend = weekday === 0 || weekday === 6;
  const peak = weekend ? midday : rush;
  const phase = Math.floor(rand(seed, 'transit_phase', route.id, year) * 3);
  const owl = year >= 1975 && (route.mode === 'streetcar' || route.mode === 'city_bus');
  const minutes: number[] = [];
  if (owl) {
    for (let minute = 0; minute < daytime[0]; minute += 40) minutes.push(minute);
  }

  const headways = [midday, peak, midday, peak, evening, evening + 8];
  for (let band = 0; band < headways.length; band++) {
    const start = daytime[band]!;
    const end = Math.min(daytime[band + 1]!, last);
    const offset = band === 0 ? 0 : phase;
    for (let minute = start + offset; minute < end; minute += headways[band]!) minutes.push(minute);
  }
  minutes.push(last);
  return minutes.filter(minute => minute >= fromMinute);
}
