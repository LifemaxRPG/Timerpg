import { describe, expect, it } from 'vitest';
import { simCall } from './bridge';

const call = (method: string, ...args: unknown[]) => JSON.parse(simCall(JSON.stringify({ method, args })));

describe('living costs and transit through simCall', () => {
  it('reaches lodgingOf, dueOn, arrears and dailyBudget', () => {
    const lodging = call('economy.lodgingOf', 'boarding_house', 2025);
    expect(lodging.rentCents).toBeGreaterThan(0);
    expect(call('economy.dueOn', lodging, 1, 1)).toBe(lodging.rentCents + lodging.depositCents);
    expect(call('economy.dueOn', lodging, 1, 4)).toBe(0);
    const late = call('economy.arrears', { lodging, startDay: 1, day: 9, paidCents: lodging.rentCents });
    expect(late.status).toBe('warning');
    expect(call('economy.dailyBudget', 'working', 2025).weeklyLivingCents).toBeGreaterThan(0);
  });

  it('reaches fare, departures, travelTime and classesOf', () => {
    expect(call('transit.fare', 'city_bus', 2025, 1.2)).toBeGreaterThan(0);
    expect(call('transit.travelTime', 'city_bus', 2025, 1.2)).toBeGreaterThan(0);
    expect(call('transit.departures', { id: 'line:x', mode: 'city_bus' }, 2025, 1, 10, 'seed').length).toBeGreaterThan(3);
    expect(call('transit.classesOf', 2025)).toEqual(['second', 'first']);
    expect(() => call('transit.nope')).toThrow();
  });
});
