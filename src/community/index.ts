import { dayNumberOf, type CalendarDate } from '../calendar';
import { priceIndex } from '../economy';
import { rand } from '../sim/rng';
import { pairKey } from '../sim/social';

export type Cadence = 'weekly' | 'monthly' | 'yearly';
type Profile = readonly [from: number, place: string, cadence: Cadence, weekday: number,
  hour: number, dues1952: number, standing: number, sponsor: boolean];

const profiles = {
  congregation: [1880, 'church', 'weekly', 0, 11, 0, 0, false],
  lodge: [1880, 'lodge_hall', 'weekly', 4, 19, 100, .35, true],
  veterans_post: [1919, 'veterans_hall', 'monthly', 2, 19, 75, 0, false],
  union: [1890, 'union_hall', 'monthly', 3, 18, 150, 0, false],
  sports_league: [1880, 'ballpark', 'weekly', 6, 14, 50, 0, false],
  pta: [1897, 'school', 'monthly', 1, 18, 25, 0, false],
  womens_club: [1890, 'community_hall', 'monthly', 3, 15, 50, 0, false],
  chamber: [1880, 'town_hall', 'yearly', 2, 18, 1200, .25, false],
} as const satisfies Record<string, Profile>;
export type GroupKind = keyof typeof profiles;
export type Person = Readonly<{
  id: string; age: number; job: string; wealthCents: number; standing: number;
  traits: { sociability: number }; religion?: string; veteran?: boolean;
  schoolParent?: boolean; woman?: boolean; businessOwner?: boolean; unionEligible?: boolean;
}>;
export type Town = Readonly<{ id: string; placeKinds: readonly string[] }>;

export function groupsFor(town: Town, year: number) {
  const price = priceIndex(year);
  if (price === null) return [];
  return (Object.keys(profiles) as GroupKind[]).flatMap(kind => {
    const [from, placeKind, cadence, weekday, hour, dues, minStanding, sponsorRequired] = profiles[kind];
    if (year < from || !town.placeKinds.includes(placeKind)) return [];
    return [{
      id: `${town.id}:${kind}`, kind, placeKind, cadence, weekday, hour, minStanding, sponsorRequired,
      duesCents: Math.round(dues * price / priceIndex(1952)!),
    }];
  });
}
export type Group = ReturnType<typeof groupsFor>[number];

export function meetingsOn(town: Town, date: CalendarDate) {
  const day = dayNumberOf(date);
  if (day === null) return [];
  return groupsFor(town, date.year).filter(group => group.weekday === (day + 1) % 7
    && (group.cadence === 'weekly' || (date.day <= 7 && (group.cadence === 'monthly' || date.month === 10))));
}

function eligible(kind: GroupKind, person: Person) {
  if (person.age < (kind === 'sports_league' ? 16 : 18)) return false;
  switch (kind) {
    case 'veterans_post': return person.veteran === true;
    case 'pta': return person.schoolParent === true;
    case 'womens_club': return person.woman === true;
    case 'chamber': return person.businessOwner === true;
    case 'union': return person.unionEligible ?? ['factory', 'mill_worker', 'rail_worker', 'miner'].includes(person.job);
    case 'congregation': return person.religion !== 'none';
    default: return true;
  }
}

export type Roster = Readonly<{ group: Group; members: readonly string[];
  officers: readonly { id: string; role: 'deacon' | 'chair' | 'secretary' }[] }>;

export function membership(people: readonly Person[], town: Town, year: number, seed: string): Roster[] {
  return groupsFor(town, year).map(group => {
    const ranked = people.filter(person => eligible(group.kind, person) && person.wealthCents >= group.duesCents
      && person.standing >= group.minStanding
      && rand(seed, 'community_member', group.id, year, person.id)
        < (group.kind === 'congregation' && person.religion ? .55 : .15) + .35 * person.traits.sociability)
      .sort((a, b) => b.standing - a.standing || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    return { group, members: ranked.map(person => person.id), officers: ranked.slice(0, 2).map(({ id }, index) => ({
      id, role: index === 1 ? 'secretary' : group.kind === 'congregation' ? 'deacon' : 'chair',
    })) };
  });
}

const affinity = { weekly: .35, monthly: .2, yearly: .08 };

export function tiesFrom(rosters: readonly Roster[]): Record<string, number> {
  const ties: Record<string, number> = {};
  for (const { group, members } of rosters) {
    for (let first = 0; first < members.length; first++) {
      for (let second = first + 1; second < members.length; second++) {
        if (members[first] === members[second]) continue;
        const key = pairKey(members[first]!, members[second]!);
        ties[key] = Math.max(ties[key] ?? 0, affinity[group.cadence]);
      }
    }
  }
  return ties;
}

export function joining(roster: Roster, person: Person, year: number, sponsorId?: string) {
  const { group, members, officers } = roster;
  const requirements = {
    eligible: eligible(group.kind, person), minStanding: group.minStanding, duesCents: group.duesCents,
    sponsorRequired: group.sponsorRequired,
  };
  const sponsorAccepted = !group.sponsorRequired || (sponsorId !== undefined
    && sponsorId !== person.id && members.includes(sponsorId));
  const alreadyMember = members.includes(person.id);
  return {
    requirements, sponsorAccepted, alreadyMember,
    canJoin: !alreadyMember && requirements.eligible && sponsorAccepted
      && person.standing >= group.minStanding && person.wealthCents >= group.duesCents,
    benefits: {
      introductions: officers.map(officer => officer.id).filter(id => id !== person.id),
      reference: officers.find(officer => officer.id !== person.id)?.id ?? null,
      informalPoliceVouch: year < 1970 && officers.some(officer => officer.id !== person.id),
    },
  };
}
