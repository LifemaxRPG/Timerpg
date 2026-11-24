import { expect, it } from 'vitest';
import { attempt, jobRequirements, practice, practiceShift, safeProfile, skillAvailable, trainingQuote } from './index';

it('adds skill with diminishing returns and supports the garage training path', () => {
  const first = practice('mechanics', 0, 'correspondence', 1952)!;
  const second = practice('mechanics', first, 'correspondence', 1952)!;
  expect(second - first).toBeLessThan(first);
  let level = practice('mechanics', 0, 'correspondence', 1952, 3)!;
  expect(level).toBeLessThan(jobRequirements.mechanic_helper.minimum);
  for (let i = 0; i < 5; i++) level = practiceShift('garage_sweeper', level, 1952)!.level;
  expect(level).toBeGreaterThanOrEqual(jobRequirements.mechanic_helper.minimum);
});

it('prices courses by era and applies availability gates', () => {
  expect(trainingQuote('mechanics', 'correspondence', 1952)?.costCents).toBe(45);
  expect(trainingQuote('mechanics', 'correspondence', 2025)!.costCents).toBeGreaterThan(45);
  expect(trainingQuote('mechanics', 'correspondence', 1885)).toBeNull();
  expect(trainingQuote('driving', 'driving_school', 1914)).toBeNull();
  expect(skillAvailable('hot_wiring', 1911)).toBe(false);
  expect(skillAvailable('hot_wiring', 1912)).toBe(true);
  expect(safeProfile(1952)?.kind).toBe('combination');
  expect(safeProfile(1980)?.kind).toBe('time_lock');
});

it('makes keyed attempts deterministic and more successful at higher skill', () => {
  const keys = Array.from({ length: 1000 }, (_, i) => String(i));
  const count = (level: number) => keys.filter(key => attempt('carpentry', level, .5, 'town', key)?.outcome === 'success').length;
  expect(count(.8)).toBeGreaterThan(count(.2));
  const result = attempt('mechanics', .2, .5, 'town', 'repair');
  expect(attempt('mechanics', .2, .5, 'town', 'repair')).toEqual(JSON.parse(JSON.stringify(result)));
  expect(keys.some(key => {
    const result = attempt('lockpicking', .1, .9, 'town', key);
    return result?.outcome === 'botch' && result.effect === 'noise';
  })).toBe(true);
});
