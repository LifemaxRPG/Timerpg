import { dateOf, dayNumberOf } from '../calendar';
import { priceIndex } from '../economy';
import { rand } from '../sim/rng';

function dateInYear(year: number, day: number) {
  const date = dateOf(day, { year, month: 1, day: 1 });
  return date?.year === year ? date : null;
}

function ticketPrice(priceIn1952Cents: number, year: number): number | null {
  const index = priceIndex(year);
  return index === null ? null : Math.round(priceIn1952Cents * index / priceIndex(1952)!);
}
