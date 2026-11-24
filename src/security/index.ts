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

const lockDifficulty: Record<Lock, number> = { none: 0, latch: .15, pin_tumbler: .3, deadbolt: .65 };
export type EntrySkills = Readonly<{ lockpicking?: number; safecracking?: number }>;
export type EntryOption = Readonly<{
  targetId: string; method: 'unlocked' | 'window' | 'pick' | 'force' | 'open_safe';
  difficulty: number; skill: keyof EntrySkills | null; level: number | null;
  noise: boolean; damage: boolean; evidence: boolean;
}>;

export function entryOptions(security: Security, skills: EntrySkills): EntryOption[] {
  const options: EntryOption[] = security.openings.flatMap<EntryOption>(opening => {
    const { id: targetId, unlocked, lock } = opening;
    const difficulty = lockDifficulty[lock];
    if (unlocked) return [{
      targetId, method: opening.kind === 'window' ? 'window' : 'unlocked', difficulty: 0,
      skill: null, level: null, noise: false, damage: false, evidence: false,
    }];
    return [
      { targetId, method: 'pick', difficulty, skill: 'lockpicking', level: skills.lockpicking ?? 0,
        noise: false, damage: false, evidence: false },
      { targetId, method: 'force', difficulty: Math.min(1, difficulty + .2), skill: null, level: null,
        noise: true, damage: true, evidence: true },
    ];
  });
  if (security.safe) options.push({
    targetId: 'safe', method: 'open_safe', difficulty: security.safe.difficulty,
    skill: 'safecracking', level: skills.safecracking ?? 0,
    noise: false, damage: false, evidence: false,
  });
  return options;
}

export function alarmResponse(alarm: Alarm, year: number) {
  if (!safeProfile(year) || alarm === 'none' || year < 1910 || (alarm === 'monitored' && year < 1970)) return [];
  return alarm === 'local_bell'
    ? [{ responder: 'neighbours', minutes: year < 1950 ? 5 : 3 }]
    : [{ responder: 'dispatch', minutes: 1 }, { responder: 'police', minutes: year < 1990 ? 8 : 5 }];
}
