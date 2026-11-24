import { describe, expect, it } from 'vitest';
import { simCall } from './bridge';

const call = (method: string, ...args: unknown[]) => JSON.parse(simCall(JSON.stringify({ method, args })));

describe('needs, weather and cars through simCall', () => {
  it('reaches needs: tick, eat, sleep, effects, treatmentCost', () => {
    let n = call('needs.initialNeeds');
    n = call('needs.tick', n, 9, 'walking');
    expect(call('needs.effects', n).labels).toContain('hungry');
    const fed = call('needs.eat', n, 'diner');
    expect(fed.hunger).toBeLessThan(n.hunger);
    const tired = call('needs.tick', fed, 12, 'walking');
    expect(call('needs.sleep', tired, 8, 'bench').fatigue).toBeGreaterThan(call('needs.sleep', tired, 8, 'bed').fatigue);
    expect(call('needs.treatmentCost', 'bruise', 1952)).toBe(150);
  });

  it('reaches weather: the same date gives the same sky, and the effects follow it', () => {
    const start = { year: 2025, month: 5, day: 6 };
    const a = call('weather.weatherAt', 'temperate_coast', 3, 14, 'seed', start);
    expect(a).toEqual(call('weather.weatherAt', 'temperate_coast', 3, 14, 'seed', start));
    expect(call('weather.weatherEffects', { sky: 'fog', temperatureC: 8, windKph: 4, visibilityM: 75 }).faceRecognitionM).toBe(6);
  });

  it('reaches cars: the lot, papers, running costs, a drive and a repair', () => {
    const lot = call('cars.carsForSale', 1952, 'capital');
    expect(lot.length).toBeGreaterThan(2);
    expect(call('cars.papersFor', 1952).registrationCents).toBe(1200);
    expect(call('cars.runningCosts', 'sedan', 1952).mpg).toBe(19);
    const d = call('cars.drive', lot[1].car, 3, { year: 1952, temperatureC: 15, seed: 'capital' });
    expect(d.milesDriven).toBeGreaterThan(0);
    expect(call('cars.repairCost', 'flat', 1952).roadside).toBe(true);
  });
});
