import { rand } from '../sim/rng';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from '../sim/types';
import type { LandmarkPlot } from './landmarks';
import { planTown, TILE, type Candidate, type LotKind, type Prop, type Road, type TownPlan } from './plan';

export { TILE };
export type { LotKind, Prop, Road, LandmarkPlot };

export interface Lot {
  x: number; y: number; w: number; h: number;
  /** Stable slot id, e.g. "small:20,34". */
  slot: string;
  kind: 'house' | 'shop';
  /** Size class: villa/medium/small houses (by household wealth) or shop. */
  tier: LotKind;
  /** Household head id (house) or business id (shop). */
  refId: Id;
  label: string;
  /** 0..1 stable value for choosing sprite variants. */
  look: number;
  residents: Id[];
}

export interface TownLayout {
  width: number;
  height: number;
  roads: readonly Road[];
  lots: Lot[];
  /** Civic landmarks that have opened by this year (world.year >= openYear). */
  landmarks: LandmarkPlot[];
  /** Year-dependent decoration (yards, trees on empty plots). Static props live in the plan. */
  props: Prop[];
  plan: TownPlan;
}

/**
 * Assigns each id a plot from `slots` (sorted center-outward). Each id prefers a plot by a
 * stable random key skewed toward the center, and probes outward when it is taken. Ids are
 * placed in sorted order, so identical inputs give identical placement and most entities keep
 * their plot as the town changes around them; the town fills from the middle out.
 */
function assign(seed: string, ids: Id[], slots: Candidate[], salt: string): Map<Id, Candidate> {
  const taken = new Set<number>();
  const result = new Map<Id, Candidate>();
  for (const id of [...ids].sort()) {
    if (taken.size >= slots.length) break;
    const k = rand(seed, 'pref', salt, id);
    let s = Math.floor(k * k * slots.length);
    while (taken.has(s)) s = (s + 1) % slots.length;
    taken.add(s);
    result.set(id, slots[s]!);
  }
  return result;
}

/** Who lives together: children and young unmarried adults stay with a living parent. */
export function householdHead(w: World, p: Person): Id {
  const age = ageIn(p, w.year);
  if (p.spouse && isAlive(w.people[p.spouse]!)) {
    const s = w.people[p.spouse]!;
    return p.sex === 'M' ? p.id : s.id;
  }
  if (age < 25 && !p.spouse) {
    const parents = p.parents.map((id) => w.people[id]!).filter(isAlive);
    const father = parents.find((q) => q.sex === 'M');
    const mom = parents.find((q) => q.sex === 'F');
    const guardian = father ?? mom;
    if (guardian) return householdHead(w, guardian);
  }
  return p.id;
}

export function layoutTown(w: World): TownLayout {
  const plan = planTown(w.seed);
  const slotsOf = (kind: LotKind): Candidate[] => plan.candidates.filter((c) => c.kind === kind);
  const lots: Lot[] = [];
  const used = new Set<string>();

  const households = new Map<Id, Id[]>();
  for (const p of Object.values(w.people).filter(isAlive)) {
    const head = householdHead(w, p);
    (households.get(head) ?? households.set(head, []).get(head)!).push(p.id);
  }
  const wealthOf = (head: Id): number => households.get(head)!.reduce((s, id) => s + w.people[id]!.wealth, 0);
  const heads = [...households.keys()];
  const sorted = heads.map(wealthOf).sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)] ?? 0;

  // Housing tier follows household wealth relative to the town: villa (top), medium, small.
  const villaSlots = slotsOf('villa');
  const villaHeads = heads
    .filter((h) => wealthOf(h) >= median * 2.5 && wealthOf(h) > 0)
    .sort((a, b) => wealthOf(b) - wealthOf(a) || (a < b ? -1 : 1))
    .slice(0, villaSlots.length);
  const villaSet = new Set(villaHeads);
  const rest = heads.filter((h) => !villaSet.has(h));
  const mediumHeads = rest.filter((h) => wealthOf(h) >= median);
  const mediumSet = new Set(mediumHeads);
  const smallHeads = rest.filter((h) => !mediumSet.has(h));
  const smallSlots = slotsOf('small');
  const mediumSlots = slotsOf('medium');

  const placeHouses = (ids: Id[], tier: LotKind, slots: Candidate[], overflow?: { tier: LotKind; slots: Candidate[] }): void => {
    const placed = assign(w.seed, ids, slots, tier);
    for (const [head, c] of placed) {
      used.add(c.id);
      lots.push({
        x: c.x, y: c.y, w: c.w, h: c.h, slot: c.id, kind: 'house', tier, refId: head,
        label: `${w.people[head]!.surname} house`, look: rand(w.seed, 'look', head), residents: households.get(head)!.sort(),
      });
    }
    // If a tier's plots run out, the rest move into the other size.
    if (overflow) {
      const left = ids.filter((id) => !placed.has(id));
      if (left.length) placeHouses(left, overflow.tier, overflow.slots.filter((c) => !used.has(c.id)));
    }
  };
  placeHouses(villaHeads, 'villa', villaSlots, { tier: 'medium', slots: mediumSlots });
  placeHouses(mediumHeads, 'medium', mediumSlots.filter((c) => !used.has(c.id)), { tier: 'small', slots: smallSlots });
  placeHouses(smallHeads, 'small', smallSlots.filter((c) => !used.has(c.id)), { tier: 'medium', slots: mediumSlots });

  const shopSlots = slotsOf('shop');
  const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined);
  const placedShops = assign(w.seed, open.map((b) => b.id), shopSlots, 'shop');
  for (const b of open) {
    const c = placedShops.get(b.id);
    if (!c) continue;
    used.add(c.id);
    lots.push({
      x: c.x, y: c.y, w: c.w, h: c.h, slot: c.id, kind: 'shop', tier: 'shop', refId: b.id,
      label: b.name, look: rand(w.seed, 'look', b.id), residents: b.owner ? [b.owner] : [],
    });
  }

  // ---------- year-dependent decoration ----------
  const props: Prop[] = [];
  const scatter = (c: Candidate, n: number, tag: string, edgesOnly: boolean): void => {
    for (let i = 0; i < n; i++) {
      const r1 = rand(w.seed, 'sc-x', tag, c.id, i);
      const r2 = rand(w.seed, 'sc-y', tag, c.id, i);
      const r3 = rand(w.seed, 'sc-k', tag, c.id, i);
      // Yards keep the middle (the door) clear: push x toward the left or right edge.
      const fx = edgesOnly ? (r1 < 0.5 ? 0.08 + r1 * 0.3 : 0.77 + (r1 - 0.5) * 0.3) : 0.08 + r1 * 0.84;
      const kind = r3 < (edgesOnly ? 0.25 : 0.55) ? 'tree' : r3 < 0.8 ? 'bush' : r3 < 0.9 ? 'flowerBush' : 'flowers';
      props.push({
        kind, x: Math.round((c.x + fx * c.w) * TILE),
        y: Math.round((c.y + (edgesOnly ? 0.55 + r2 * 0.42 : 0.2 + r2 * 0.75) * c.h) * TILE),
        look: rand(w.seed, 'sc-l', tag, c.id, i),
      });
    }
  };
  for (const c of plan.candidates) {
    if (!used.has(c.id)) {
      // Empty plot: a wooded lot, thicker toward the outskirts.
      scatter(c, c.kind === 'villa' || c.kind === 'shop' ? 6 : 4, 'empty', false);
    } else if (c.kind !== 'shop') {
      scatter(c, 1 + Math.floor(rand(w.seed, 'yard', c.id) * 3), 'yard', true);
    } else {
      scatter(c, 2, 'shopfront', true);
    }
  }

  // Landmark plots: wooded until the landmark opens.
  const landmarks = plan.landmarks.filter((l) => w.year >= l.openYear);
  for (const l of plan.landmarks) {
    if (w.year >= l.openYear) continue;
    const n = 6 + Math.round((l.w * l.h) / 25);
    for (let i = 0; i < n; i++) {
      const r3 = rand(w.seed, 'lm-k', l.id, i);
      props.push({
        kind: r3 < 0.6 ? 'tree' : r3 < 0.85 ? 'bush' : 'flowerBush',
        x: Math.round((l.x + 0.5 + rand(w.seed, 'lm-x', l.id, i) * (l.w - 1)) * TILE),
        y: Math.round((l.y + 1 + rand(w.seed, 'lm-y', l.id, i) * (l.h - 1.5)) * TILE),
        look: rand(w.seed, 'lm-l', l.id, i),
      });
    }
  }

  return { width: plan.width, height: plan.height, roads: plan.roads, lots, landmarks, props, plan };
}

export const lotDescription = (w: World, lot: Lot): string[] => {
  if (lot.kind === 'shop') {
    const b = w.businesses[lot.refId]!;
    const owner = b.owner ? fullName(w.people[b.owner]!) : 'nobody';
    return [b.name, `Founded ${b.foundedYear}`, `Owner: ${owner}`, `Capital: ${Math.round(b.capital)}`];
  }
  return [
    lot.label,
    ...lot.residents.map((id) => {
      const p = w.people[id]!;
      return `${fullName(p)} (${ageIn(p, w.year)})`;
    }),
  ];
};

export const landmarkDescription = (w: World, l: LandmarkPlot): string[] => [
  l.name,
  `Opened ${l.openYear} (${w.year - l.openYear} year${w.year - l.openYear === 1 ? '' : 's'} ago)`,
  l.blurb,
];
