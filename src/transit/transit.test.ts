import { expect, it } from 'vitest';
import { classesOf, departures, fare, travelTime } from './index';

const main = { id: 'main', mode: 'streetcar' } as const;

it('gates motorized transit by era and prices streetcars flat at a nickel in 1925', () => {
  for (const mode of ['motor_bus', 'city_bus', 'intercity_coach'] as const) {
    expect(fare(mode, 1890, 5)).toBeNull();
    expect(departures({ id: 'early', mode }, 1890, 1, 0, 'town')).toEqual([]);
    expect(travelTime(mode, 1890, 5)).toBeNull();
  }
  expect(fare('streetcar', 1925, 5)).toBe(5);
  expect(fare('streetcar', 1925, 15)).toBe(5);
  expect(fare('taxi', 1925, 10)!).toBeGreaterThan(fare('taxi', 1925, 2)!);
});

it('runs every 12 minutes at 1925 midday and keeps the last 23:40 departure', () => {
  const noon = departures(main, 1925, 1, 12, 'town');
  expect(noon[1]! - noon[0]!).toBe(12);
  expect(departures(main, 1925, 1, 23.5, 'town')).toEqual([1420]);
  expect(departures(main, 1925, 1, 23.75, 'town')).toEqual([]);
});

it('runs more frequently at rush hour than at midnight and is deterministic', () => {
  const times = departures(main, 1985, 1, 0, 'town');
  expect(times).toEqual(departures(main, 1985, 1, 0, 'town'));
  expect(times.filter(t => t >= 420 && t < 480).length).toBeGreaterThan(times.filter(t => t < 60).length);
  expect(times).toEqual([...new Set(times)].sort((a, b) => a - b));
});

it('runs fixed trains and prices rail classes in order', () => {
  expect(departures({ id: 'night', mode: 'railway' }, 1925, 1, 22, 'town')).toEqual([1335]);
  expect(departures({ id: 'custom', mode: 'railway', trainMinutes: [600, 300, 600, -1, 1440] }, 1925, 1, 0, 'town'))
    .toEqual([300, 600]);
  expect(fare('railway', 1925, 40, 'third')).toBe(185);
  expect(fare('railway', 1925, 40, 'second')!).toBeGreaterThan(185);
  expect(fare('railway', 1925, 40, 'first')!).toBeGreaterThan(fare('railway', 1925, 40, 'second')!);
  expect(classesOf(1985)).toEqual(['second', 'first']);
  expect(fare('railway', 1985, 40, 'third')).toBeNull();
  expect(travelTime('railway', 1925, 40)).toBe(59);
  expect(travelTime('railway', 1985, 40)!).toBeLessThan(59);
});
