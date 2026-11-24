import { expect, it } from 'vitest';
import { apply, openings, shift } from './index';

const hardware = { id: 'hw', kind: 'hardware' };
const applicant = { age: 23, reputation: .6, references: [], hasPapers: false };
const seeds = Array.from({ length: 20 }, (_, n) => String(n));
const first = (year: number) => seeds.flatMap(seed => openings([hardware], year, 0, seed))[0]!;

it('keeps listings stable within a week and gates jobs by place and era', () => {
  const places = [hardware, { id: 'roadside', kind: 'motel' }, { id: 'pump', kind: 'gas_station' }];
  expect(openings(places, 1952, 0, 'town')).toEqual(openings(places, 1952, 6, 'town'));
  const all = (year: number) => seeds.flatMap(seed => openings(places, year, 0, seed));
  expect(all(1925).some(row => row.role === 'night_clerk')).toBe(false);
  expect(all(1985).some(row => row.placeId === 'pump' && row.role === 'night_clerk')).toBe(true);
  expect(first(1952)).toMatchObject({ title: 'Stock boy', hourlyCents: 60, shiftHours: 6 });
});

it('offers a trial without a reference and hires a qualified applicant with one', () => {
  expect(apply(first(1952), applicant).status).toBe('trial');
  expect(apply(first(1952), { ...applicant, references: ['good'] }).status).toBe('hired');
  expect(apply(first(1990), applicant)).toEqual({ status: 'refused', reason: 'papers' });
  expect(apply(first(1952), { ...applicant, reputation: .1 })).toEqual({ status: 'refused', reason: 'record' });
});

it('warns once, ignores retries, and dismisses on the second late shift', () => {
  const hired = apply(first(1952), applicant);
  if (hired.status === 'refused') throw new Error('Expected a trial');
  const warning = shift(hired.job, { day: 1, outcome: 'late' });
  expect(warning.event).toBe('warning');
  expect(shift(warning.job, { day: 1, outcome: 'late' }).job).toBe(warning.job);
  expect(shift(warning.job, { day: 2, outcome: 'late' })).toMatchObject({
    event: 'dismissed', job: { status: 'dismissed', reference: 'poor' },
  });
  expect(shift(hired.job, { day: 1, outcome: 'caught_stealing' }).event).toBe('dismissed');
});

it('completes a steady trial, then awards one raise and a good reference', () => {
  const trial = apply(first(1952), applicant);
  if (trial.status === 'refused') throw new Error('Expected a trial');
  let job = trial.job;
  const events = [];
  for (let day = 0; day < 20; day++) {
    const result = shift(job, { day, outcome: 'on_time' });
    job = result.job;
    events.push(result.event);
  }
  expect(events[6]).toBe('hired');
  expect(events[14]).toBe('raise');
  expect(events.filter(event => event === 'raise')).toHaveLength(1);
  expect(job).toMatchObject({ hourlyCents: 66, reference: 'good' });
});
