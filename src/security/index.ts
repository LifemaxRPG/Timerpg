import { clamp01, rand } from '../sim/rng';
import { safeProfile } from '../skills';

export type Building = Readonly<{
  id: string;
  kind: 'farmhouse' | 'house' | 'hardware' | 'jeweller' | 'shop' | 'bank';
  ownerWealth: number;
  neighbourhood: 'rural' | 'residential' | 'downtown';
  contentsValueCents: number;
}>;
export type Lock = 'none' | 'latch' | 'pin_tumbler' | 'deadbolt';
export type Alarm = 'none' | 'local_bell' | 'monitored';
type Opening = Readonly<{ id: string; kind: 'door' | 'window'; lock: Lock; unlocked: boolean }>;
export type Security = Readonly<{
  openings: readonly Opening[];
  alarm: Alarm;
  safe: ReturnType<typeof safeProfile>;
  dog: { location: 'yard' | 'storeroom'; resting: boolean } | null;
}>;

export function securityOf(building: Building, year: number, seed: string): Security | null {
  const profile = safeProfile(year);
  if (!profile || !Number.isFinite(building.ownerWealth) || !Number.isFinite(building.contentsValueCents)) return null;
  const wealth = clamp01(building.ownerWealth);
  const highValue = building.kind === 'jeweller' || building.kind === 'bank' || building.contentsValueCents >= 250000;
  const farm = building.kind === 'farmhouse';
  const roll = (key: string) => rand(seed, 'building_security', building.id, year, key);
  const unlockedChance = highValue ? 0 : farm && year < 1940 ? .82 : building.neighbourhood === 'rural' ? .25 : .07;
  const lock: Lock = year >= 1970 ? 'deadbolt' : year >= 1930 ? 'pin_tumbler' : farm && roll('fitted_lock') < .25 ? 'none' : 'latch';
  const openings: Opening[] = [
    { id: 'front', kind: 'door', lock, unlocked: lock === 'none' || roll('front') < unlockedChance },
    { id: 'back', kind: 'door', lock, unlocked: lock === 'none' || roll('back') < unlockedChance },
    { id: 'side_window', kind: 'window', lock: 'latch', unlocked: roll('window') < unlockedChance * .35 },
  ];
  const alarm: Alarm = year >= 1970 && highValue && roll('monitor') < .65 + .25 * wealth ? 'monitored'
    : year >= 1910 && highValue && roll('bell') < .55 + .35 * wealth ? 'local_bell' : 'none';
  const dog = (farm || building.kind === 'hardware' || building.kind === 'house')
    && roll('dog') < (farm ? .65 : building.kind === 'hardware' ? .35 : .2)
    ? { location: farm || building.kind === 'house' ? 'yard' : 'storeroom', resting: roll('rest') < .7 } as const : null;
  return { openings, alarm, safe: highValue && (building.kind === 'bank' || roll('safe') < .65 + .25 * wealth) ? profile : null, dog };
}
