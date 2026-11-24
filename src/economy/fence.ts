import { clamp01, rand } from '../sim/rng';
import { ITEMS, type Category } from './catalogue';
import { fenceOffer, pawnOffer } from './pricing';
import { validYear } from './eras';

export type FencePerson = Readonly<{
  id: string;
  age: number;
  job: string;
  honesty: number;
  greed: number;
  sociability: number;
}>;

export type Fence = Readonly<{
  personId: string;
  cover: string;
  where: string;
  hours: readonly [start: number, end: number];
  categories: readonly Category[];
  honesty: number;
  greed: number;
}>;

const covers = [
  { cover: 'hardware clerk', where: 'behind the hardware store', categories: ['valuables', 'hand_tools', 'power_tools'] },
  { cover: 'repair clerk', where: 'at the repair shop back door', categories: ['valuables', 'electronics', 'instruments'] },
  { cover: 'secondhand dealer', where: 'at the secondhand shop back door', categories: ['valuables', 'clothing', 'instruments'] },
] as const;

export function townFences(people: readonly FencePerson[], seed: string): Fence[] {
  const size = people.length;
  if (rand(seed, 'fence_count') >= Math.min(.98, size / 120)) return [];
  const limit = size >= 80 && rand(seed, 'fence_second') < .55 ? 2 : 1;
  return people.filter(p => p.age >= 18 && p.honesty <= .45 && p.greed >= .5)
    .map(person => ({
      person,
      score: person.greed - person.honesty + .2 * person.sociability + .3 * rand(seed, 'fence_rank', person.id),
    }))
    .sort((a, b) => b.score - a.score || (a.person.id < b.person.id ? -1 : Number(a.person.id > b.person.id)))
    .slice(0, limit)
    .map(({ person }) => {
      const preferred = covers.find(cover => cover.cover === person.job.trim().toLowerCase());
      const cover = preferred ?? covers[Math.floor(rand(seed, 'fence_cover', person.id) * covers.length)]!;
      return { ...cover, personId: person.id, hours: [20, 2], honesty: person.honesty, greed: person.greed };
    });
}

export type FenceLead = Readonly<{
  sourceId: string;
  fenceId: string;
  where: string;
  hours: Fence['hours'];
  willingness: number;
}>;

export function fenceLeads(fences: readonly Fence[], people: readonly FencePerson[], seed: string): FenceLead[] {
  const fenceIds = new Set(fences.map(fence => fence.personId));
  const sources = people.filter(p => p.age >= 18 && p.sociability >= .5 && !fenceIds.has(p.id));
  return fences.flatMap(fence => sources.flatMap(person => {
    const familiarity = person.sociability * .5 + (1 - person.honesty) * .25;
    if (rand(seed, 'fence_lead', fence.personId, person.id) >= familiarity) return [];
    return [{
      sourceId: person.id, fenceId: fence.personId, where: fence.where, hours: fence.hours,
      willingness: Math.round(100 * clamp01(.6 * person.sociability + .4 * (1 - person.honesty))) / 100,
    }];
  }));
}

export type FenceDeal =
  | { accepted: true; offerCents: number; trustAfter: number }
  | { accepted: false; reason: 'category' | 'too_hot' | 'no_offer' };

export function fenceDeal(fence: Fence, itemId: string, year: number, heat: number, trust: number): FenceDeal {
  const item = ITEMS.find(entry => entry.id === itemId);
  if (!item || !fence.categories.includes(item.category)) return { accepted: false, reason: 'category' };
  const base = fenceOffer(itemId, year);
  const pawn = pawnOffer(itemId, year, false);
  if (!base || !pawn || !Number.isFinite(heat) || heat < 0 || heat > 1) return { accepted: false, reason: 'no_offer' };
  const standing = Number.isFinite(trust) ? clamp01(trust) : 0;
  if (heat > .7 + .3 * standing) return { accepted: false, reason: 'too_hot' };
  const bargaining = 1.65 - .3 * fence.greed + .35 * standing;
  const heatDiscount = 1 - heat * (.55 - .3 * standing);
  const offerCents = Math.min(pawn - 1, Math.floor(base * bargaining * heatDiscount));
  if (offerCents <= 0) return { accepted: false, reason: 'no_offer' };
  return { accepted: true, offerCents, trustAfter: Math.min(100, Math.round(standing * 100) + 10) / 100 };
}

/** Probability of a tip per completed deal. The caller owns the keyed roll and report. */
export function fenceRisk(fence: Fence, year: number): number | null {
  if (!validYear(year)) return null;
  const eraRate = year < 1930 ? .04 : year < 1960 ? .09 : year < 1990 ? .16 : .26;
  return Math.round(1000 * eraRate * clamp01(.25 + fence.honesty + .35 * fence.greed)) / 1000;
}
