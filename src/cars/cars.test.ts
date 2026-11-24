import { expect, it } from 'vitest';
import { priceIn } from '../economy';
import { carsForSale, drive, papersFor, policeStop, repairCost, runningCosts, serviceCar } from './index';

const sedans = carsForSale(1952, 'highway').filter(listing => listing.car.body === 'sedan');
const [fresh, used] = sedans;

it('lists cheaper used cars and fits the 1952 hardware of the lot', () => {
  expect(used).toMatchObject({ car: { modelYear: 1946, condition: .75 }, askingCents: 45000 });
  expect(used!.askingCents).toBeLessThan(fresh!.askingCents);
  expect(carsForSale(1900, 'highway')).toEqual([]);
  expect(carsForSale(1952, 'highway')).toEqual(JSON.parse(JSON.stringify(carsForSale(1952, 'highway'))));
});

it('gates papers by era and enforces requirements only where applicable', () => {
  const missing = { registration: false, licence: false, insurance: false };
  expect(papersFor(1924)?.insuranceCents).toBeNull();
  expect(papersFor(1952)).toMatchObject({ registrationCents: 1200, insuranceRequired: false });
  expect(policeStop(missing, 1952)?.missing).toEqual(['registration', 'licence']);
  expect(papersFor(1957)?.insuranceRequired).toBe(true);
  expect(policeStop({ ...missing, licence: true, registration: true }, 1957))
    .toMatchObject({ missing: ['insurance'], impound: false });
  expect(policeStop(missing, 1957)?.impound).toBe(true);
});

it('uses the fuel-price curve and changes cents per mile over time', () => {
  const past = runningCosts('sedan', 1952)!;
  const modern = runningCosts('sedan', 2025)!;
  expect(past.gasolinePerGallonCents).toBe(priceIn('gasoline', 1952));
  expect(modern.gasolinePerGallonCents).toBe(priceIn('gasoline', 2025));
  expect(past.fuelCentsPerMile).not.toBe(modern.fuelCentsPerMile);
});

it('breaks neglected cars more often over 1000 miles and restores service deterministically', () => {
  const newer = carsForSale(2025, 'highway')[0]!.car;
  const older = { ...used!.car, modelYear: 1990, condition: .2, milesSinceService: 9000 };
  const seeds = Array.from({ length: 40 }, (_, i) => String(i));
  const conditions = { year: 2025, temperatureC: 22 };
  const count = (car: typeof older) => seeds.filter(seed => drive(car, 1000, { ...conditions, seed }).breakdown).length;
  expect(count(older)).toBeGreaterThan(count(newer));
  const result = drive(older, 1000, { ...conditions, seed: '0' });
  expect(result).toEqual(drive(older, 1000, { ...conditions, seed: '0' }));
  if (!result.breakdown) throw new Error('Expected a breakdown in the test drive');
  expect(result.car.mileage).toBe(older.mileage + result.milesDriven);
  expect(drive(result.car, 5, { ...conditions, seed: '0' }).milesDriven).toBe(0);
  expect(serviceCar(result.car)).toMatchObject({ milesSinceService: 0, breakdown: null });
  expect(repairCost('flat', 1952)?.roadside).toBe(true);
  expect(repairCost('overheating', 1952)?.towRequired).toBe(true);
});
