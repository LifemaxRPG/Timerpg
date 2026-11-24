import { priceIndex } from '../economy';
import { dayNumberOf, type CalendarDate } from '../calendar';
import { rand } from '../sim/rng';

export type ShipKind = 'ferry' | 'liner' | 'freighter' | 'cruise';
export type TravelClass = 'deck' | 'third' | 'second' | 'first' | 'crew';
export type Port = Readonly<{ id: string; continentId: string }>;
export type Route = Readonly<{ from: Port; to: Port; nauticalMiles: number }>;

function indexedFare(cents1952: number, year: number): number | null {
  const index = priceIndex(year);
  return index === null ? null : Math.max(1, Math.round(cents1952 * index / priceIndex(1952)!));
}
