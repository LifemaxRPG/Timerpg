import { describe, expect, it } from 'vitest';
import { dateOf, dayNumberOf } from '../calendar';
import { candidatesFor, officesFor, policyFor, runElection } from './index';

const seed = 'capital';
const date = { year: 1952, month: 10, day: 7 };
const residents = ['ada', 'bill', 'casey'].map(id => ({
  id, age: 40, job: 'civil_servant', standing: .6, traits: { ambition: .7, diligence: .5 },
}));
const candidates = candidatesFor(residents, 'mayor', 1952, seed);
const districts = (crime: number) => [
  { id: 'main', eligibleVoters: 500, mood: { crime, prices: .1, unemployment: .15 } },
  { id: 'mill', eligibleVoters: 280, mood: { crime, prices: .2, unemployment: .2 } },
];
const run = (crime: number) => runElection('large', 'mayor', date, candidates, districts(crime), seed)!;

describe('town politics', () => {
  it('holds scheduled elections on the first Tuesday in Leafmonth', () => {
    expect(dateOf(dayNumberOf(date)!)?.monthName).toBe('Leafmonth');
    expect(dateOf(dayNumberOf(date)!)?.weekday).toBe(2);
    expect(officesFor('large', 1952)[0]?.electionDate).toEqual(date);
    expect(runElection('large', 'mayor', { ...date, day: 8 }, candidates, districts(.2), seed)).toBeNull();
    expect(officesFor('large', 1953)[0]?.electionDate).toBeNull();
  });

  it('increases the law-and-order vote share during a crime wave', () => {
    const law = candidates.find(candidate => candidate.platform.policing === 'expand')!;
    const share = (crime: number) => {
      const result = run(crime);
      return result.tally.find(row => row.candidateId === law.id)!.votes /
        result.districts.reduce((total, district) => total + district.turnout, 0);
    };
    expect(share(.9)).toBeGreaterThan(share(.1));
  });

  it('applies the winning mayor’s platform and doubles night patrol in a crackdown', () => {
    const result = run(.9);
    expect(result.winner?.platform.policing).toBe('expand');
    expect(result.policy).toEqual(policyFor(result.winner!.platform, districts(.9)[0]!.mood));
    expect(result.policy).toMatchObject({ nightPatrolMultiplier: 2, crackdown: true, shopHours: 'sunday_closed' });
    expect(runElection('large', 'council_1', date, candidates, districts(.9), seed)?.policy).toBeNull();
  });

  it('changes the law office, council size and mayoral term with era and size', () => {
    expect(officesFor('small', 1952).at(-1)).toMatchObject({ kind: 'sheriff', selection: 'elected' });
    expect(officesFor('small', 1980).at(-1)).toMatchObject({ kind: 'police_chief', selection: 'appointed', electionDate: null });
    expect(officesFor('large', 1980).filter(row => row.kind === 'council')).toHaveLength(9);
    expect(officesFor('large', 1980)[0]?.termYears).toBe(4);
  });

  it('replays nominations and results and accounts for every ballot', () => {
    expect(candidatesFor([...residents].reverse(), 'mayor', 1952, seed)).toEqual(candidates);
    const result = run(.6);
    expect(run(.6)).toEqual(result);
    for (const district of result.districts) {
      expect(district.votes.reduce((sum, row) => sum + row.votes, 0)).toBe(district.turnout);
      expect(district.turnout).toBeLessThanOrEqual(district.eligibleVoters);
    }
    expect(runElection('large', 'mayor', date, candidates, [], seed)?.winner).toBeNull();
  });
});
