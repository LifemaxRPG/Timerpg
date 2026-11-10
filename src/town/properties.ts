import { neighborhoodOf } from './neighborhoods';
import { rand } from '../sim/rng';
import { isAlive, type Id, type World } from '../sim/types';
import { shopFacade } from './facade';
import { apartmentEligible, houseArt } from './housing';
import { FRONT } from './kinds';
import { developedRoads, draftLots, householdHead, type Lot } from './layout';
import { planTown, type Candidate } from './plan';

/** Serializable physical history, stored in every annual snapshot and rebuilt after interventions. */
export interface Settlement {
  properties: Lot[];
  /** Indices into the immutable seed-generated road plan. */
  roads: number[];
}

/** Advance occupancy without re-auctioning the town's buildings every year. */
export function advanceSettlement(w: World): void {
  const plan = planTown(w.seed);
  // The existing wealth/district rules remain the preference for a household's FIRST home.
  const proposed = draftLots(w, plan);
  const rank = new Map(plan.candidates.map((c, i) => [c.id, i]));
  const previous = w.settlement ?? {
    properties: proposed.map((l) => ({ ...l, builtYear: w.year })), roads: [],
  };
  const properties = previous.properties.map((l): Lot => ({
    ...l, heads: [], residents: [], vacantSince: l.vacantSince ?? w.year,
  }));
  const bySlot = new Map(properties.map((l) => [l.slot, l]));
  // The population is authoritative. The initial placement's tier fallbacks may run out of
  // slots even when another tier has room, so never derive household membership from its output.
  const members = new Map<Id, Id[]>();
  for (const p of Object.values(w.people).filter(isAlive)) {
    const head = householdHead(w, p);
    const group = members.get(head) ?? [];
    group.push(p.id); members.set(head, group);
  }
  const wealthOf = (ids: Id[]): number => ids.reduce((sum, id) => sum + w.people[id]!.wealth, 0);
  const wealths = [...members.values()].map(wealthOf).sort((a, b) => a - b);
  const median = wealths[Math.floor(wealths.length / 2)] ?? 0;
  const households = new Map<Id, { residents: Id[]; preferred: Lot }>();
  for (const [head, residents] of members) {
    let preferred = proposed.find((l) => l.kind === 'house' && l.heads.includes(head));
    if (!preferred) {
      const wealth = wealthOf(residents);
      const tier = apartmentEligible(residents.length, wealth, median) ? 'apartment'
        : wealth >= median * 1.4 && wealth > 0 ? 'house' : wealth >= median ? 'medium' : 'small';
      const template = proposed.find((l) => l.kind === 'house');
      if (!template) throw new Error(`No residential plots in ${w.seed}, ${w.year}`);
      preferred = { ...template, refId: head, heads: [head], residents, tier };
    }
    households.set(head, { residents, preferred });
  }
  const pending = new Set([...households.keys()].sort());
  const occupy = (lot: Lot, head: Id): void => {
    lot.heads.push(head);
    lot.residents.push(...households.get(head)!.residents);
    lot.residents.sort();
    lot.refId = lot.heads[0]!;
    const first = w.people[lot.refId]!;
    lot.label = lot.tier === 'apartment' ? `${first.surname} Court apartments` : `${first.surname} house`;
    delete lot.vacantSince;
    pending.delete(head);
  };
  const room = (l: Lot): boolean => l.kind === 'house' && l.heads.length < l.art.capacity;

  // Retain household heads first, so a child leaving home cannot displace their parents.
  for (const old of previous.properties.filter((l) => l.kind === 'house')) {
    const lot = bySlot.get(old.slot)!;
    for (const head of old.heads) if (pending.has(head) && room(lot)) occupy(lot, head);
  }
  // A surviving spouse/child can carry on living in the same property after the head dies.
  for (const old of previous.properties.filter((l) => l.kind === 'house')) {
    const lot = bySlot.get(old.slot)!;
    for (const head of [...pending]) {
      if (!room(lot)) break;
      if (households.get(head)!.residents.some((id) => old.residents.includes(id))) occupy(lot, head);
    }
  }

  const construct = (c: Candidate, preferred: Lot): Lot => {
    const look = rand(w.seed, 'property', c.id);
    const tier = c.kind;
    const district = neighborhoodOf(plan, c.neighborhood);
    const art = tier === 'shop'
      ? shopFacade(w.businesses[preferred.refId]!.kind, look, w.year, district.shopColour)
      : houseArt(tier, look, w.year, district.palette);
    const lot: Lot = {
      ...preferred, x: c.x, y: c.y, w: c.w, h: c.h, slot: c.id, neighborhood: c.neighborhood, tier,
      look, front: FRONT[tier], art, heads: [], residents: [], builtYear: w.year,
    };
    properties.push(lot); bySlot.set(c.id, lot);
    return lot;
  };
  for (const head of pending) {
    const preferred = households.get(head)!.preferred;
    // Reuse existing housing before clearing new land. Preserve its facade, tier and capacity.
    const available = properties.filter(room).sort((a, b) =>
      Number(b.tier === preferred.tier) - Number(a.tier === preferred.tier)
      || Number(a.vacantSince === undefined) - Number(b.vacantSince === undefined)
      || rank.get(a.slot)! - rank.get(b.slot)!);
    let lot = available[0];
    if (!lot) {
      const candidates = plan.candidates.filter((c) => c.kind !== 'shop' && !bySlot.has(c.id));
      const c = candidates.find((c) => c.kind === preferred.tier) ?? candidates[0];
      if (!c) throw new Error(`Housing capacity exhausted in ${w.seed}, ${w.year}: ${head}`);
      lot = construct(c, preferred);
    }
    occupy(lot, head);
  }

  const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined).sort((a, b) => a.id < b.id ? -1 : 1);
  const placed = new Set<Id>();
  const occupyShop = (lot: Lot, id: Id): void => {
    const b = w.businesses[id]!;
    if (lot.refId !== id) {
      // A new tenant changes the display, keeping the building's upper floors and roof.
      const ground = shopFacade(b.kind, lot.look, w.year, lot.art.colour).layers[0]!;
      const layers = [ground, ...lot.art.layers.slice(1)];
      lot.art = { ...lot.art, layers, h: Math.max(...layers.map((l) => l.y + l.h)) };
    }
    lot.refId = id; lot.label = b.name; lot.residents = b.owner ? [b.owner] : [];
    delete lot.vacantSince; placed.add(id);
  };
  for (const b of open) {
    const old = previous.properties.find((l) => l.kind === 'shop' && l.vacantSince === undefined && l.refId === b.id);
    if (old) occupyShop(bySlot.get(old.slot)!, b.id);
  }
  for (const b of open.filter((b) => !placed.has(b.id))) {
    let lot = properties.find((l) => l.kind === 'shop' && l.vacantSince !== undefined);
    if (!lot) {
      const c = plan.candidates.find((c) => c.kind === 'shop' && !bySlot.has(c.id));
      if (!c) throw new Error(`Shop capacity exhausted in ${w.seed}, ${w.year}: ${b.id}`);
      const preferred = proposed.find((l) => l.kind === 'shop' && l.refId === b.id)!;
      lot = construct(c, preferred);
    }
    occupyShop(lot, b.id);
  }
  w.settlement = {
    properties,
    roads: developedRoads(plan, properties, w.year, previous.roads).map((r) => plan.roads.indexOf(r)),
  };
}

