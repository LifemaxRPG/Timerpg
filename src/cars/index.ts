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

export type Papers = Readonly<{ registration: boolean; licence: boolean; insurance: boolean }>;

export function papersFor(year: number) {
  const index = priceIndex(year);
  if (index === null || year < 1905) return null;
  return {
    registrationCents: year >= 1908 ? scale(1200, index) : null,
    licenceCents: year >= 1910 ? scale(500, index) : null,
    insuranceCents: year >= 1925 ? scale(4200, index) : null,
    insuranceRequired: year >= 1957,
  };
}

export function policeStop(papers: Papers, year: number) {
  const costs = papersFor(year);
  const index = priceIndex(year);
  if (!costs || index === null) return null;
  const missing = (['registration', 'licence', 'insurance'] as const)
    .filter(key => costs[`${key}Cents`] !== null && (key !== 'insurance' || costs.insuranceRequired) && !papers[key]);
  return {
    missing,
    fineCents: scale(1800 * missing.length, index),
    impound: missing.length >= 2,
  };
}

export function runningCosts(body: Body, year: number) {
  const spec = Object.prototype.hasOwnProperty.call(bodies, body) ? bodies[body] : null;
  const index = priceIndex(year);
  const gasolinePerGallonCents = priceIn('gasoline', year);
  if (!spec || index === null || gasolinePerGallonCents === null || year < spec.from) return null;
  const efficiency = Math.max(.48, Math.min(1.8, 1 + (year - 1952) * .011));
  const mpg = Math.round(spec.mpg * efficiency);
  return {
    mpg, gasolinePerGallonCents,
    fuelCentsPerMile: Math.round(100 * gasolinePerGallonCents / mpg) / 100,
    oilChangeCents: scale(240, index),
    tyreCents: scale(1500, index),
    serviceCents: scale(1000, index),
  };
}

export type DriveConditions = Readonly<{ year: number; temperatureC: number; seed: string }>;

export function drive(car: Car, miles: number, conditions: DriveConditions): { car: Car; milesDriven: number; breakdown: Breakdown | null } {
  const { year, temperatureC, seed } = conditions;
  if (!Number.isFinite(miles) || miles <= 0 || miles > 10000 || !Number.isFinite(temperatureC)
    || year < car.modelYear || priceIndex(year) === null || car.breakdown) {
    return { car, milesDriven: 0, breakdown: car.breakdown };
  }

  const age = Math.min(30, year - car.modelYear);
  const extreme = Math.max(0, temperatureC - 29, -temperatureC - 8);
  const end = car.mileage + miles;
  let breakdown: Breakdown | null = null;
  let distance = miles;
  for (let mark = Math.floor(car.mileage) + 1; mark <= end; mark++) {
    const progress = mark - car.mileage;
    const wear = 1 - clamp01(car.condition - progress * .000035);
    const serviceDebt = Math.min(1, (car.milesSinceService + progress) / 6000);
    const risk = .000025 + .000035 * age + .0025 * wear ** 2 + .0015 * serviceDebt + .00008 * extreme;
    if (rand(seed, 'car_failure', car.id, mark) >= risk) continue;
    const roll = rand(seed, 'car_failure_kind', car.id, mark);
    breakdown = temperatureC >= 30 && roll < .65 ? 'overheating'
      : temperatureC < -8 && roll < .55 ? 'dead_battery'
      : roll < .4 ? 'flat' : roll < .72 ? 'overheating' : roll < .9 ? 'dead_battery' : 'engine_failure';
    distance = progress;
    break;
  }

  return {
    car: { ...car, mileage: car.mileage + distance, condition: clamp01(car.condition - distance * .000035),
      milesSinceService: car.milesSinceService + distance, breakdown },
    milesDriven: distance, breakdown,
  };
}

const repairs: Record<Breakdown, readonly [cents1952: number, roadside: boolean]> = {
  flat: [1500, true], overheating: [950, false], dead_battery: [1250, true], engine_failure: [17000, false],
};

export function repairCost(breakdown: Breakdown, year: number) {
  const index = priceIndex(year);
  if (index === null || year < 1905 || !Object.prototype.hasOwnProperty.call(repairs, breakdown)) return null;
  const [cents1952, roadside] = repairs[breakdown];
  return { repairCents: scale(cents1952, index), roadside, towRequired: !roadside };
}

export function serviceCar(car: Car): Car {
  return { ...car, condition: Math.max(car.condition, .85), milesSinceService: 0, breakdown: null };
}
