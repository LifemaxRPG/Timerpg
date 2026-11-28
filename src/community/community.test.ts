import { expect, it } from 'vitest';
import { groupsFor, joining, meetingsOn, membership, tiesFrom } from './index';

const town = { id: 'capital', placeKinds: ['church', 'lodge_hall', 'town_hall', 'veterans_hall'] };
const person = { id: 'player', age: 35, job: 'mechanic', wealthCents: 5000, standing: .8,
  traits: { sociability: .7 }, religion: 'local' };
const group = (kind: string) => groupsFor(town, 1952).find(entry => entry.kind === kind)!;

it('places Sunday service and Thursday lodge on their calendar days', () => {
  expect(meetingsOn(town, { year: 1952, month: 7, day: 6 })).toContainEqual(group('congregation'));
  expect(meetingsOn(town, { year: 1952, month: 7, day: 3 })).toContainEqual(group('lodge'));
  expect(meetingsOn(town, { year: 1952, month: 7, day: 7 })).toEqual([]);
});

it('opens a veterans post after the first war and admits eligible veterans', () => {
  expect(groupsFor(town, 1918).some(entry => entry.kind === 'veterans_post')).toBe(false);
  expect(groupsFor(town, 1919).some(entry => entry.kind === 'veterans_post')).toBe(true);
  expect(joining({ group: group('veterans_post'), members: [], officers: [] }, person, 1952).canJoin).toBe(false);
  expect(joining({ group: group('veterans_post'), members: [], officers: [] }, { ...person, veteran: true }, 1952).canJoin).toBe(true);
});

it('gives frequent meetings stronger symmetric gossip ties', () => {
  const members = ['b', 'a'];
  const weekly = { group: group('congregation'), members, officers: [] };
  const yearly = { group: group('chamber'), members, officers: [] };
  expect(tiesFrom([weekly])['a|b']).toBeGreaterThan(tiesFrom([yearly])['a|b']!);
  expect(tiesFrom([weekly, yearly])).toEqual(tiesFrom([weekly]));
});

it('requires a current member to sponsor lodge applicants', () => {
  const lodge = { group: group('lodge'), members: ['sponsor'], officers: [] };
  expect(joining(lodge, person, 1952).canJoin).toBe(false);
  expect(joining(lodge, person, 1952, 'stranger').canJoin).toBe(false);
  expect(joining(lodge, person, 1952, 'sponsor').canJoin).toBe(true);
});

it('replays membership and officer selection independently of input order', () => {
  const people = Array.from({ length: 30 }, (_, index) => ({ ...person, id: String(index) }));
  const roster = membership(people, town, 1952, 'test');
  expect(roster).toEqual(membership([...people].reverse(), town, 1952, 'test'));
  const congregation = roster.find(entry => entry.group.kind === 'congregation')!;
  expect(congregation.officers[0]?.role).toBe('deacon');
  expect(congregation.members).toContain(congregation.officers[0]?.id);
});
