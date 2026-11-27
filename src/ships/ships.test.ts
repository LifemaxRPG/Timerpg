import { describe, expect, it } from 'vitest';
import { dayNumberOf } from '../calendar';
import { applyForPassport, canBoard, dayAtSea, discoveryChance, passportOffice, passportRequired,
  timetable, unpaperedPassage } from './index';

const route = { from: { id: 'capital_port', continentId: 'west' },
  to: { id: 'eastern_port', continentId: 'east' }, nauticalMiles: 2400 };
const tuesday = { year: 1952, month: 7, day: 1 };

describe('ships and passports', () => {
  it('schedules era-appropriate long-distance services', () => {
    const liner = timetable(route, 1952).find(service => service.kind === 'liner');
    expect(liner).toMatchObject({ weekdays: [2, 5], crossingHours: 120 });
    expect(timetable(route, 1952).some(service => service.kind === 'cruise')).toBe(false);
    expect(timetable(route, 2000).some(service => service.kind === 'liner')).toBe(false);
    expect(timetable(route, 2000).some(service => service.kind === 'cruise')).toBe(true);
    expect(timetable({ ...route, nauticalMiles: 30 }, 1952).map(service => service.kind)).toEqual(['ferry']);
  });

  it('posts ascending liner fares and indexes them by year', () => {
    const fares = (year: number) => timetable(route, year).find(service => service.kind === 'liner')!.fares;
    expect(fares(1952)).toEqual([
      { travelClass: 'third', cents: 8500 }, { travelClass: 'second', cents: 14500 },
      { travelClass: 'first', cents: 29000 },
    ]);
    expect(fares(1970)[0]!.cents).toBeGreaterThan(8500);
  });

  it('requires papers across continents by era and observes document and waiting rules', () => {
    expect(passportRequired(route, 1900)).toBe(false);
    expect(passportRequired(route, 1952)).toBe(true);
    expect(passportRequired({ ...route, to: { ...route.to, continentId: 'west' } }, 1952)).toBe(false);
    expect(canBoard(route, tuesday, null)).toBe(false);
    expect(canBoard(route, tuesday, { era: 'modern', readyOn: 0 })).toBe(false);
    expect(applyForPassport(tuesday, ['identity_photo'])?.missing).toEqual(['birth_record']);
    const application = applyForPassport(tuesday, ['birth_record', 'identity_photo'])!;
    expect(passportOffice(1952)?.waitingDays).toBe(14);
    expect(application.passport?.readyOn).toBe(dayNumberOf(tuesday)! + 14);
    expect(canBoard(route, tuesday, application.passport)).toBe(false);
    expect(canBoard(route, { ...tuesday, day: 15 }, application.passport)).toBe(true);
  });

  it('increases discovery pressure by era and scopes work passage to the freighter', () => {
    expect(discoveryChance(1952, 'stowaway')).toBeGreaterThan(discoveryChance(1920, 'stowaway')!);
    expect(discoveryChance(2000, 'stowaway')).toBeGreaterThan(discoveryChance(1952, 'stowaway')!);
    expect(discoveryChance(1952, 'work_passage')).toBeLessThan(discoveryChance(1952, 'stowaway')!);
    expect(unpaperedPassage(route, tuesday, 'voyage', 'liner', 'work_passage', 'seed')).toBeNull();
    expect(unpaperedPassage(route, { ...tuesday, day: 2 }, 'voyage', 'freighter', 'work_passage', 'seed')).toBeTruthy();
  });

  it('replays both landing outcomes and day-at-sea details', () => {
    const outcomes = () => Array.from({ length: 30 }, (_, i) =>
      unpaperedPassage(route, tuesday, 'voyage', 'liner', 'stowaway', String(i)));
    const result = outcomes();
    expect(result).toEqual(outcomes());
    expect(result).toContainEqual({ discovered: true, outcome: 'held_for_port_authorities', event: null });
    expect(result).toContainEqual({ discovered: false, outcome: 'landed_without_clearance',
      event: { kind: 'illegal_landing', portId: 'eastern_port', voyageId: 'voyage' } });
    expect(dayAtSea('liner', 'third', 1952, 2, 'seed')).toEqual(dayAtSea('liner', 'third', 1952, 2, 'seed'));
    expect(dayAtSea('liner', 'third', 1952, 2, 'seed')?.berth).toBe('shared bunk cabin');
    expect(dayAtSea('ferry', 'third', 1952, 2, 'seed')).toBeNull();
  });
});
