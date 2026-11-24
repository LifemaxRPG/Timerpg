import { describe, expect, it } from 'vitest';
import { initialNeeds } from '../needs';
import { driveTime, driverStops, encountersOn, roadsideAlong, type Road } from './index';

const road: Road = { id: 'port_road', kind: 'highway', lengthMiles: 150,
  from: 'capital', to: 'port', terrain: 'farmland' };
const clear = { sky: 'clear', temperatureC: 18, windKph: 5, visibilityM: 20000 } as const;
const fog = { ...clear, sky: 'fog', visibilityM: 75 } as const;
const seeds = Array.from({ length: 100 }, (_, i) => String(i));

describe('life on the road', () => {
  it('adds motels from 1940 and keeps rest areas on expressways', () => {
    expect(seeds.flatMap(seed => roadsideAlong(road, 1939, seed)).some(stop => stop.kind === 'motel')).toBe(false);
    expect(seeds.flatMap(seed => roadsideAlong(road, 1940, seed)).some(stop => stop.kind === 'motel')).toBe(true);
    expect(seeds.flatMap(seed => roadsideAlong(road, 1970, seed)).some(stop => stop.kind === 'rest_area')).toBe(false);
    expect(seeds.flatMap(seed => roadsideAlong({ ...road, kind: 'expressway' }, 1970, seed))
      .some(stop => stop.kind === 'rest_area')).toBe(true);
  });

  it('makes hitchhikers rarer after the 1970s', () => {
    const count = (year: number) => seeds.flatMap(seed => encountersOn(road, year, 12, clear, seed))
      .filter(event => event.kind === 'hitchhiker').length;
    expect(count(1979)).toBeGreaterThan(count(1980));
  });

  it('spawns fog banks only in fog and increases travel time', () => {
    expect(encountersOn(road, 1955, 8, fog, 'a').some(event => event.kind === 'fog_bank')).toBe(true);
    expect(encountersOn(road, 1955, 8, clear, 'a').some(event => event.kind === 'fog_bank')).toBe(false);
    const limits = { county: 35, highway: 50, expressway: 65 };
    expect(driveTime(road, limits, fog)).toBeGreaterThan(driveTime(road, limits, clear)!);
  });

  it('increases spacing on remote roads', () => {
    expect(roadsideAlong({ ...road, remote: true }, 1955, 'a').length)
      .toBeLessThan(roadsideAlong(road, 1955, 'a').length);
  });

  it('replays places, linked encounters and driver stop estimates', () => {
    const news = [{ id: 'notice', kind: 'escape_notice', roadId: road.id,
      lastSeenCoat: 'pea coat', headline: 'Escape from work farm' }] as const;
    const results = () => seeds.flatMap(seed => encountersOn(road, 1955, 12, clear, seed, news));
    expect(results()).toEqual(results());
    expect(results()).toContainEqual(expect.objectContaining({
      kind: 'hitchhiker', destination: 'port', coat: 'pea coat', newsId: 'notice',
    }));
    expect(roadsideAlong(road, 1955, 'a')).toEqual(roadsideAlong(road, 1955, 'a'));
    const stops = driverStops({ ...initialNeeds(), hunger: .72, fatigue: .66 }, 4);
    expect(stops[0]).toEqual({ kind: 'eat', afterHours: 0 });
    expect(stops[1]).toMatchObject({ kind: 'sleep' });
    expect(stops[1]!.afterHours).toBeCloseTo(.04 / .037);
  });
});
