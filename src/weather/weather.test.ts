import { expect, it } from 'vitest';
import { dayNumberOf } from '../calendar';
import { temperatureRange, weatherAt, weatherEffects, type Climate } from './index';

const regions: Climate[] = ['temperate_coast', 'inland_plains', 'mountain', 'warm_south'];
const dayOf = (month: number, day = 1) => dayNumberOf({ year: 1952, month, day })!;

it('limits snow to cold calendar months and never snows in the warm south', () => {
  let snowDays = 0;
  for (const climate of regions) {
    for (let month = 1; month <= 12; month++) {
      for (let day = 1; day <= 28; day++) {
        const weather = weatherAt(climate, dayOf(month, day), 12, 'town')!;
        if (weather.sky !== 'snow') continue;
        expect(weather.temperatureC).toBeLessThanOrEqual(1);
        expect(climate).not.toBe('warm_south');
        expect([1, 2, 3, 11, 12]).toContain(month);
        snowDays++;
      }
    }
  }
  expect(snowDays).toBeGreaterThan(0);
});

it('rolls in evening fog and reduces face recognition distance', () => {
  const day = dayOf(11, 8);
  const before = weatherAt('temperate_coast', day, 17, '2')!;
  const fog = weatherAt('temperate_coast', day, 18, '2')!;
  expect(fog).toMatchObject({ sky: 'fog', visibilityM: 75 });
  expect(fog.visibilityM).toBeLessThan(before.visibilityM);
  expect(weatherEffects(fog).faceRecognitionM).toBeLessThan(weatherEffects(before).faceRecognitionM);
  expect(weatherAt('temperate_coast', day, 20, '2')?.sky).toBe('fog');
});

it('keeps daytime sky and wind stable while the same front passes', () => {
  const day = dayOf(7, 8);
  const morning = weatherAt('inland_plains', day, 8, 'town')!;
  for (let hour = 9; hour <= 17; hour++) {
    expect(weatherAt('inland_plains', day, hour, 'town')).toMatchObject({
      sky: morning.sky, windKph: morning.windKph,
    });
  }
});

it('stays within authored climate ranges and generates repeatable JSON', () => {
  expect(regions.map(climate => temperatureRange(climate, 1))).toEqual([
    { minC: -4, maxC: 14 }, { minC: -17, maxC: 7 }, { minC: -20, maxC: 2 }, { minC: 6, maxC: 22 },
  ]);
  for (const climate of regions) {
    for (let month = 1; month <= 12; month++) {
      const range = temperatureRange(climate, month)!;
      for (const hour of [3, 9, 15, 21]) {
        const weather = weatherAt(climate, dayOf(month), hour, 'town')!;
        expect(weather.temperatureC).toBeGreaterThanOrEqual(range.minC);
        expect(weather.temperatureC).toBeLessThanOrEqual(range.maxC);
        expect(weatherAt(climate, dayOf(month), hour, 'town')).toEqual(JSON.parse(JSON.stringify(weather)));
      }
    }
  }
});

it('has more clear skies in the warm south than on the temperate coast', () => {
  const countClear = (climate: Climate) => {
    let clear = 0;
    for (const seed of ['town', 'harbor', 'prairie']) {
      for (let day = dayOf(1); day < dayOf(1) + 366; day++) {
        if (weatherAt(climate, day, 12, seed)?.sky === 'clear') clear++;
      }
    }
    return clear;
  };
  expect(countClear('warm_south')).toBeGreaterThan(549);
  expect(countClear('warm_south')).toBeGreaterThan(countClear('temperate_coast'));
});

it('keys the same weather to a date regardless of the save start', () => {
  const date = { year: 1952, month: 11, day: 8 };
  const earlier = { ...date, month: 10 };
  const absolute = dayNumberOf(date)!;
  const expected = weatherAt('temperate_coast', absolute, 18, '2');
  expect(expected?.sky).toBe('fog');
  expect(weatherAt('temperate_coast', 0, 18, '2', date)).toEqual(expected);
  expect(weatherAt('temperate_coast', absolute - dayNumberOf(earlier)!, 18, '2', earlier)).toEqual(expected);
});
