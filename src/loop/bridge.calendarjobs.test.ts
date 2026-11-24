import { describe, expect, it } from 'vitest';
import { simCall } from './bridge';

const call = (method: string, ...args: unknown[]) => JSON.parse(simCall(JSON.stringify({ method, args })));

describe('calendar and jobs through simCall', () => {
  it('reaches dateOf, holidaysOn and toShopDate', () => {
    const d = call('calendar.dateOf', 0, { year: 2025, month: 5, day: 6 });
    expect(d).toMatchObject({ weekday: 2, weekdayName: 'Tuesday', monthName: 'Bloommonth', season: 'spring' });
    expect(call('calendar.dateOf', 8, { year: 2025, month: 5, day: 6 }).day).toBe(14);
    expect(call('calendar.holidaysOn', { year: 2025, month: 1, day: 5 })[0].closes).toContain('shops');
    expect(call('calendar.toShopDate', { year: 2025, month: 1, day: 5 })).toEqual({ month: 1, day: 5 });
    expect(call('calendar.dateOf', 0, { year: 2025, month: 13, day: 1 })).toBeNull();
  });

  it('reaches openings, apply and shift', () => {
    let opening = null;
    for (let day = 1; day < 70 && !opening; day += 7) opening = call('jobs.openings', [{ id: 'p', kind: 'general' }], 2025, day, 'seed')[0];
    expect(opening.hourlyCents).toBeGreaterThan(0);
    const app = call('jobs.apply', opening, { age: 25, references: [], reputation: 0.6, hasPapers: true });
    expect(app.status).toBe('trial');
    const s = call('jobs.shift', app.job, { day: 3, outcome: 'late' });
    expect(s.event).toBe('warning');
  });
});
