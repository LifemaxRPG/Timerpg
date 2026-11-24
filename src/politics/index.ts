import { dateOf, dayNumberOf, type CalendarDate } from '../calendar';
import { clamp01, rand } from '../sim/rng';

export type TownSize = 'small' | 'medium' | 'large';
type OfficeKind = 'mayor' | 'council' | 'sheriff' | 'police_chief';

export function officesFor(size: TownSize, year: number) {
  if (dayNumberOf({ year, month: 1, day: 1 }) === null) return [];
  const modern = year >= 1960;
  const seats = (size === 'small' ? 3 : size === 'medium' ? 5 : 7) + (modern && size !== 'small' ? 2 : 0);
  const sheriff = size === 'small' && !modern;
  const office = (id: string, kind: OfficeKind, termYears: number, elected = true) => {
    let electionDate: CalendarDate | null = null;
    if (elected && (year - 1952) % termYears === 0) {
      const first = dateOf(dayNumberOf({ year, month: 10, day: 1 })!)!;
      electionDate = { year, month: 10, day: 1 + (2 - first.weekday + 7) % 7 };
    }
    return { id, kind, termYears, selection: elected ? 'elected' as const : 'appointed' as const, electionDate };
  };
  return [
    office('mayor', 'mayor', modern ? 4 : 2),
    ...Array.from({ length: seats }, (_, i) => office(`council_${i + 1}`, 'council', 2)),
    sheriff ? office('sheriff', 'sheriff', 4) : office('police_chief', 'police_chief', 4, false),
  ];
}

export type Platform = Readonly<{
  policing: 'expand' | 'maintain';
  taxes: 'cut' | 'maintain' | 'raise';
  businessHours: 'sunday_closed' | 'local_choice';
  roads: 'improve' | 'maintain';
}>;
export type Resident = Readonly<{
  id: string; age: number; job: string; standing: number;
  traits: { ambition: number; diligence: number };
}>;
export type Candidate = Readonly<{ id: string; standing: number; platform: Platform }>;
export type Mood = Readonly<{ crime: number; prices: number; unemployment: number }>;
export type Neighbourhood = Readonly<{ id: string; eligibleVoters: number; mood: Mood }>;

const profiles: readonly Platform[] = [
  { policing: 'expand', taxes: 'maintain', businessHours: 'sunday_closed', roads: 'maintain' },
  { policing: 'maintain', taxes: 'cut', businessHours: 'local_choice', roads: 'maintain' },
  { policing: 'maintain', taxes: 'raise', businessHours: 'local_choice', roads: 'improve' },
];
const normalized = (value: number) => Number.isFinite(value) ? clamp01(value) : 0;
const byId = (a: string, b: string) => a < b ? -1 : a > b ? 1 : 0;

export function candidatesFor(residents: readonly Resident[], officeId: string, year: number, seed: string): Candidate[] {
  return residents.filter(person => person.age >= 25 && person.standing >= .25
    && Number.isFinite(person.traits.ambition) && Number.isFinite(person.traits.diligence))
    .map(person => ({
      person,
      score: clamp01(person.standing) + .5 * clamp01(person.traits.ambition)
        + .25 * clamp01(person.traits.diligence) + (person.job === 'civil_servant' ? .1 : 0)
        + .1 * rand(seed, 'politics_nomination', year, officeId, person.id),
    }))
    .sort((a, b) => b.score - a.score || byId(a.person.id, b.person.id))
    .slice(0, 3)
    .map(({ person }, index) => ({ id: person.id, standing: clamp01(person.standing), platform: profiles[index]! }));
}

export function policyFor(platform: Platform, mood: Mood) {
  const crackdown = platform.policing === 'expand' && mood.crime >= .65;
  return {
    policeHeadcountMultiplier: platform.policing === 'expand' ? 1.5 : 1,
    nightPatrolMultiplier: crackdown ? 2 : 1,
    shopHours: platform.businessHours,
    taxRate: platform.taxes === 'cut' ? .015 : platform.taxes === 'raise' ? .025 : .02,
    roads: platform.roads,
    crackdown,
  };
}

export function runElection(
  size: TownSize, officeId: string, date: CalendarDate, candidates: readonly Candidate[],
  neighbourhoods: readonly Neighbourhood[], seed: string,
) {
  const office = officesFor(size, date.year).find(entry => entry.id === officeId);
  if (office?.electionDate?.month !== date.month || office?.electionDate?.day !== date.day
    || candidates.length === 0 || new Set(candidates.map(candidate => candidate.id)).size !== candidates.length
    || new Set(neighbourhoods.map(district => district.id)).size !== neighbourhoods.length) return null;
  const totals = new Map(candidates.map(candidate => [candidate.id, 0]));
  const districts = neighbourhoods.map(({ id, eligibleVoters, mood }) => {
    const eligible = Number.isSafeInteger(eligibleVoters) && eligibleVoters > 0 ? eligibleVoters : 0;
    const crime = normalized(mood.crime);
    const prices = normalized(mood.prices);
    const unemployment = normalized(mood.unemployment);
    const turnout = Math.round(eligible * (.45 + .2 * crime + .12 * unemployment
      + .1 * rand(seed, 'politics_turnout', date.year, officeId, id)));
    const scores = candidates.map(({ id: candidateId, platform, standing }) => ({
      candidateId,
      weight: 1 + .4 * normalized(standing) + (platform.policing === 'expand' ? 1.8 * crime : 0)
        + (platform.taxes === 'cut' ? prices : 0) + (platform.roads === 'improve' ? unemployment : 0)
        + .15 * rand(seed, 'politics_vote', date.year, officeId, id, candidateId),
    })).sort((a, b) => byId(a.candidateId, b.candidateId));
    const weightTotal = scores.reduce((sum, row) => sum + row.weight, 0);
    const shares = scores.map(({ candidateId, weight }) => {
      const quota = turnout * weight / weightTotal;
      return { candidateId, votes: Math.floor(quota), remainder: quota % 1 };
    }).sort((a, b) => b.remainder - a.remainder || byId(a.candidateId, b.candidateId));
    const leftover = turnout - shares.reduce((sum, row) => sum + row.votes, 0);
    const votes = shares.map(({ candidateId, votes }, index) => {
      const count = votes + Number(index < leftover);
      totals.set(candidateId, totals.get(candidateId)! + count);
      return { candidateId, votes: count };
    });
    return { id, eligibleVoters: eligible, turnout, votes };
  });
  const tally = Array.from(totals, ([candidateId, votes]) => ({ candidateId, votes }))
    .sort((a, b) => b.votes - a.votes || byId(a.candidateId, b.candidateId));
  const winner = tally[0]!.votes > 0 ? candidates.find(candidate => candidate.id === tally[0]!.candidateId)! : null;
  const voters = districts.reduce((total, district) => total + district.eligibleVoters, 0);
  const crime = districts.reduce((sum, district, index) =>
    sum + district.eligibleVoters * normalized(neighbourhoods[index]!.mood.crime), 0) / (voters || 1);
  return { districts, tally, winner, policy: winner && officeId === 'mayor'
    ? policyFor(winner.platform, { crime, prices: 0, unemployment: 0 }) : null };
}
