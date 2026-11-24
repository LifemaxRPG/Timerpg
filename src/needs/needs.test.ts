import { expect, it } from 'vitest';
import { eat, effects, initialNeeds, injure, sleep, tick, treat, treatmentCost, wash } from './index';

it('keeps a regular day healthy with three meals and bed rest', () => {
  let state = initialNeeds();
  state = eat(tick(state, 4, 'working'), 'diner');
  state = eat(tick(state, 5, 'working'), 'grocery_cooked');
  state = eat(tick(state, 7, 'walking'), 'boarding_house_board');
  state = sleep(state, 8, 'bed');
  expect(state).toMatchObject({ fatigue: 0, health: 1, ill: false });
  expect(effects(state).labels).toEqual([]);
  expect(wash(state).hygiene).toBe(1);
});

it('becomes hungry at 11 idle hours, starving at 23, and eventually ill', () => {
  const start = initialNeeds();
  expect(effects(tick(start, 10, 'idle')).labels).not.toContain('hungry');
  expect(effects(tick(start, 11, 'idle')).labels).toContain('hungry');
  expect(effects(tick(start, 22, 'idle')).labels).not.toContain('starving');
  const starving = tick(start, 23, 'idle');
  expect(effects(starving).labels).toContain('starving');
  expect(effects(starving).walkingSpeedMultiplier).toBeLessThan(effects(start).walkingSpeedMultiplier);
  expect(effects(starving).dozeChancePerHour).toBeGreaterThan(0);
  expect(tick(start, 48, 'idle').ill).toBe(true);
});

it('recovers fatigue fastest in a bed', () => {
  const state = tick(initialNeeds(), 16, 'working');
  expect(sleep(state, 6, 'bed').fatigue).toBeLessThan(sleep(state, 6, 'cell').fatigue);
  expect(sleep(state, 6, 'cell').fatigue).toBeLessThan(sleep(state, 6, 'bench').fatigue);
});

it('heals a sprain faster after treatment and scales the doctor bill', () => {
  const sprained = injure(initialNeeds(), 'sprain');
  expect(tick(sprained, 48, 'idle').injuries).toHaveLength(1);
  expect(tick(treat(sprained, 'sprain'), 48, 'idle').injuries).toEqual([]);
  expect(tick(sprained, 96, 'idle').injuries).toEqual([]);
  expect(treatmentCost('sprain', 1952)).toBe(300);
  expect(treatmentCost('sprain', 1925)!).toBeLessThan(300);
  expect(treatmentCost('sprain', 2025)!).toBeGreaterThan(300);
});

it('is deterministic across equivalent JSON saves', () => {
  const state = injure(initialNeeds(), 'cut');
  expect(tick(state, 5, 'running')).toEqual(tick(JSON.parse(JSON.stringify(state)), 5, 'running'));
  expect(tick(state, -1, 'idle')).toBe(state);
});
