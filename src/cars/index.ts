import { priceIn, priceIndex } from '../economy';
import { clamp01, rand } from '../sim/rng';

const bodies = {
  sedan: { from: 1905, cents1952: 100000, mpg: 19 },
  coupe: { from: 1910, cents1952: 112000, mpg: 21 },
  pickup: { from: 1915, cents1952: 105000, mpg: 15 },
  wagon: { from: 1925, cents1952: 126000, mpg: 17 },
  sports_car: { from: 1948, cents1952: 185000, mpg: 16 },
} as const;

export type Body = keyof typeof bodies;
export type Breakdown = 'flat' | 'overheating' | 'dead_battery' | 'engine_failure';
export type Car = Readonly<{
  id: string; body: Body; modelYear: number; mileage: number; condition: number;
  milesSinceService: number; breakdown: Breakdown | null;
}>;

function scale(cents1952: number, index: number): number {
  return Math.round(cents1952 * index / priceIndex(1952)!);
}

export function carsForSale(year: number, seed: string): { car: Car; askingCents: number }[] {
  const index = priceIndex(year);
  if (index === null) return [];
  return (Object.keys(bodies) as Body[]).flatMap(body => {
    const spec = bodies[body];
    if (year < spec.from) return [];
    const age = body === 'sedan' ? 6 : 2 + Math.floor(rand(seed, 'lot_age', year, body) * 9);
    const newPrice = scale(spec.cents1952, index);
    const make = (age: number, condition: number, mileage: number) => ({
      car: {
        id: JSON.stringify([seed, year, body, age]), body, modelYear: year - age, mileage, condition,
        milesSinceService: age === 0 ? 0 : 1200, breakdown: null,
      },
      askingCents: Math.round(newPrice * Math.max(.2, 1 - age / 15) * condition),
    });
    const fresh = make(0, 1, 0);
    if (year - age < spec.from) return [fresh];
    const condition = body === 'sedan' ? .75 : .60 + Math.floor(rand(seed, 'lot_condition', year, body) * 26) / 100;
    const mileage = age * (5500 + Math.floor(rand(seed, 'lot_mileage', year, body) * 5500));
    return [fresh, make(age, condition, mileage)];
  });
}
