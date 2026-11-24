import { expect, it } from 'vitest';
import { fenceDeal, fenceLeads, fenceRisk, pawnOffer, townFences, type Fence, type FencePerson } from './index';

const buyer = { id: 'vic', age: 42, job: 'hardware clerk', honesty: .2, greed: .8, sociability: .6 };
const fence: Fence = {
  personId: buyer.id, cover: buyer.job, where: 'behind the hardware store', hours: [20, 2],
  categories: ['valuables'], honesty: buyer.honesty, greed: buyer.greed,
};

it('selects at most two eligible adults deterministically and sometimes none in small towns', () => {
  const people = Array.from({ length: 150 }, (_, i) => ({ ...buyer, id: 'p_' + i }));
  const seeds = Array.from({ length: 30 }, (_, i) => 'town_' + i);
  expect(seeds.some(seed => townFences([buyer], seed).length === 0)).toBe(true);
  expect(seeds.some(seed => townFences(people, seed).length === 2)).toBe(true);
  for (const seed of seeds) {
    const result = townFences(people, seed);
    expect(result).toEqual(townFences([...people].reverse(), seed));
    expect(result.length).toBeLessThanOrEqual(2);
    expect(townFences(people.map(p => ({ ...p, age: 17 })), seed)).toEqual([]);
  }
});

it('draws leads from sociable adults outside the fence roster', () => {
  const regulars: FencePerson[] = Array.from({ length: 50 }, (_, i) => ({
    ...buyer, id: 'regular_' + i, sociability: .85, honesty: .4,
  }));
  const people = [...regulars, buyer, { ...buyer, id: 'quiet', sociability: .1 }, { ...buyer, id: 'minor', age: 16 }];
  const leads = fenceLeads([fence], people, 'diner');
  expect(leads.length).toBeGreaterThan(0);
  expect(leads).toEqual(fenceLeads([fence], people, 'diner'));
  expect(leads.every(lead => lead.sourceId.startsWith('regular_') && lead.willingness === .75)).toBe(true);
});

it('refuses off-category and hot goods until trusted, then pays less than pawn', () => {
  expect(fenceDeal(fence, 'radio', 1952, false, 1)).toEqual({ accepted: false, reason: 'category' });
  expect(fenceDeal(fence, 'pocketwatch', 1952, true, .64)).toEqual({ accepted: false, reason: 'too_hot' });
  const deal = fenceDeal(fence, 'pocketwatch', 1952, true, .7);
  expect(deal).toEqual({ accepted: true, offerCents: 206, trustAfter: .8 });
  if (deal.accepted) expect(deal.offerCents).toBeLessThan(pawnOffer('pocketwatch', 1952, true)!);
  const lowTrust = fenceDeal(fence, 'pocketwatch', 1952, false, 0);
  if (!lowTrust.accepted || !deal.accepted) throw new Error('Expected two valid offers');
  expect(lowTrust.offerCents).toBeLessThan(deal.offerCents);
});

it('gives bounded informant risk that changes by era and traits', () => {
  expect(fenceRisk(fence, 1952)).toBe(.066);
  expect(fenceRisk(fence, 2025)!).toBeGreaterThan(fenceRisk(fence, 1952)!);
  expect(fenceRisk({ ...fence, honesty: .4 }, 1952)!).toBeGreaterThan(fenceRisk(fence, 1952)!);
  expect(fenceRisk(fence, 1750)).toBeNull();
});
