import { expect, it } from 'vitest';
import { alarmResponse, entryOptions, securityOf, type Building } from './index';

const farm: Building = { id: 'farm', kind: 'farmhouse', neighbourhood: 'rural', ownerWealth: .3, contentsValueCents: 5000 };
const jewellery: Building = { ...farm, id: 'jewel', kind: 'jeweller', neighbourhood: 'downtown', ownerWealth: .9 };
const hardware: Building = { ...farm, id: 'hardware', kind: 'hardware', neighbourhood: 'downtown' };
const seeds = Array.from({ length: 80 }, (_, i) => String(i));

it('offers unlocked farm doors often in 1925 and locks the 1985 jeweller', () => {
  expect(seeds.filter(seed => securityOf(farm, 1925, seed)!.openings[0]!.unlocked).length).toBeGreaterThan(50);
  expect(seeds.every(seed => securityOf(jewellery, 1985, seed)!.openings.every(opening => !opening.unlocked))).toBe(true);
  expect(securityOf(jewellery, 1985, 'town')!.openings[0]!.lock).toBe('deadbolt');
});

it('uses the skill safe profile and can put a resting dog at the 1952 hardware store', () => {
  const bank = { ...jewellery, kind: 'bank' } as const;
  expect(securityOf(bank, 1952, 'town')?.safe?.kind).toBe('combination');
  expect(securityOf(bank, 1985, 'town')?.safe?.kind).toBe('time_lock');
  const fixture = seeds.map(seed => securityOf(hardware, 1952, seed)!).find(result =>
    result.dog?.resting && !result.openings[1]!.unlocked)!;
  expect(fixture.dog?.location).toBe('storeroom');
  expect(fixture.alarm).toBe('none');
  expect(entryOptions(fixture, { lockpicking: .2 })).toContainEqual(expect.objectContaining({
    targetId: 'back', method: 'pick', difficulty: .3, skill: 'lockpicking', level: .2,
  }));
});

it('shows noise, damage and evidence for forcing but not for an unlocked entry', () => {
  const options = entryOptions({
    alarm: 'none', dog: null, safe: null,
    openings: [{ id: 'window', kind: 'window', lock: 'latch', unlocked: true },
      { id: 'door', kind: 'door', lock: 'deadbolt', unlocked: false }],
  }, {});
  expect(options.find(o => o.method === 'force')).toMatchObject({ noise: true, damage: true, evidence: true });
  expect(options.find(o => o.method === 'window')).toMatchObject({ noise: false, damage: false, difficulty: 0 });
});

it('gates monitored alarms and gives distinct response schedules', () => {
  expect(seeds.some(seed => securityOf(jewellery, 1969, seed)?.alarm === 'monitored')).toBe(false);
  expect(seeds.some(seed => securityOf(jewellery, 1970, seed)?.alarm === 'monitored')).toBe(true);
  expect(alarmResponse('monitored', 1969)).toEqual([]);
  expect(alarmResponse('local_bell', 1952)).toEqual([{ responder: 'neighbours', minutes: 3 }]);
  expect(alarmResponse('monitored', 1985)).toContainEqual({ responder: 'police', minutes: 8 });
});

it('round-trips seed output as JSON', () => {
  const model = securityOf(jewellery, 1985, 'town');
  expect(securityOf(jewellery, 1985, 'town')).toEqual(JSON.parse(JSON.stringify(model)));
  expect(securityOf(jewellery, 1800, 'town')).toBeNull();
});
