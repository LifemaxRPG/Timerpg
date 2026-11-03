import { rand } from '../sim/rng';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from '../sim/types';
import type { LandmarkPlot } from './landmarks';
import { shopFacade } from './facade';
import { apartmentCapacity, apartmentEligible, houseArt, type ResidentialKind } from './housing';
import type { BuildingArt } from './buildings';
import { waterNear } from './water';
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
  /** Sprite stack to draw (houses by tier/era, shop fronts by business type). */
  art: BuildingArt;
  /** Household heads living here (several in an apartment building). */
  heads: Id[];
  residents: Id[];
}

export interface TownLayout {
  width: number;
  height: number;
  /** Streets that exist in this year; the rest are still woodland (see layoutTown). */
  roads: Road[];
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
    let s = Math.floor(Math.pow(k, 2.5) * slots.length);
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

  // Housing tier follows household wealth relative to the town: estate (top), villa, house, medium,
  // small; the poorest singles and couples rent apartments (several households per building).
  const sizeOf = (h: Id): number => households.get(h)!.length;
  const byWealth = (a: Id, b: Id): number => wealthOf(b) - wealthOf(a) || (a < b ? -1 : 1);
  const claimed = new Set<Id>();
  const tierHeads = (pred: (h: Id) => boolean, limit = Infinity): Id[] => {
    const out = heads.filter((h) => !claimed.has(h) && pred(h)).sort(byWealth).slice(0, limit);
    for (const h of out) claimed.add(h);
    return out;
  };
  // The town fills outward: ordinary plots beyond a frontier (sized to the population) stay wooded
  // until needed. Only small and medium plots are rationed; estates, villas, houses and apartments are always available.
  let frontier = Infinity;
  for (let i = 0, room = 0; i < plan.candidates.length; i++) {
    const c = plan.candidates[i]!;
    if (c.kind === 'shop') continue;
    room += c.kind === 'apartment' ? 3 : 1;
    if (room >= heads.length * 1.5) { frontier = c.rank; break; }
  }
  const slotsFor = (kind: LotKind, strict = false): Candidate[] =>
    slotsOf(kind).filter((c) => !used.has(c.id) && (!strict || (kind !== 'small' && kind !== 'medium') || c.rank <= frontier));
  const estateHeads = tierHeads((h) => wealthOf(h) >= median * 5 && wealthOf(h) > 0, slotsOf('estate').length);
  const villaHeads = tierHeads((h) => wealthOf(h) >= median * 2.5 && wealthOf(h) > 0, slotsOf('villa').length);
  const houseHeads = tierHeads((h) => wealthOf(h) >= median * 1.4 && wealthOf(h) > 0);
  const mediumHeads = tierHeads((h) => wealthOf(h) >= median);
  const aptHeads = tierHeads((h) => apartmentEligible(sizeOf(h), wealthOf(h), median));
  const smallHeads = tierHeads(() => true);

  // If a tier's plots run out, the rest move down the chain to the next kind of plot.
  const FALLBACK: Record<ResidentialKind, ResidentialKind[]> = {
    estate: ['villa', 'house', 'medium', 'small'],
    villa: ['house', 'medium', 'small'],
    house: ['medium', 'small', 'villa'],
    medium: ['small', 'house'],
    small: ['medium', 'house'],
    apartment: ['small', 'medium', 'house'],
  };
  const pushHouse = (c: Candidate, tier: ResidentialKind, group: Id[]): void => {
    used.add(c.id);
    const look = rand(w.seed, 'look', tier === 'apartment' ? c.id : group[0]!);
    const first = w.people[group[0]!]!;
    lots.push({
      x: c.x, y: c.y, w: c.w, h: c.h, slot: c.id, kind: 'house', tier, refId: group[0]!, heads: group,
      label: tier === 'apartment' ? `${first.surname} Court apartments` : `${first.surname} house`,
      look, art: houseArt(tier, look, w.year), residents: group.flatMap((h) => households.get(h)!).sort(),
    });
  };
  /** Fill apartment buildings center-outward, each taking as many households as its sprite holds. */
  const fillApartments = (ids: Id[], strict = true): Id[] => {
    let i = 0;
    for (const c of slotsFor('apartment', strict)) {
      if (i >= ids.length) break;
      const group = ids.slice(i, i + apartmentCapacity(rand(w.seed, 'look', c.id), w.year));
      i += group.length;
      pushHouse(c, 'apartment', group);
    }
    return ids.slice(i);
  };
  const placeHouses = (ids: Id[], tier: ResidentialKind): void => {
    let left = ids;
    if (tier === 'apartment') left = fillApartments(left, true);
    for (const strict of [true, false]) {
      for (const t of tier === 'apartment' ? FALLBACK.apartment : [tier, ...FALLBACK[tier]]) {
        if (!left.length) return;
        const placed = assign(w.seed, left, slotsFor(t, strict), t);
        for (const [head, c] of placed) pushHouse(c, t, [head]);
        left = left.filter((id) => !placed.has(id));
      }
    }
    // Last resort (the plan grows until this is never needed): double up in apartment buildings.
    if (left.length) fillApartments(left, false);
  };
  placeHouses(estateHeads, 'estate');
  placeHouses(villaHeads, 'villa');
  placeHouses(houseHeads, 'house');
  placeHouses(mediumHeads, 'medium');
  placeHouses(aptHeads, 'apartment');
  placeHouses(smallHeads, 'small');

  const shopSlots = slotsOf('shop');
  const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined);
  const placedShops = assign(w.seed, open.map((b) => b.id), shopSlots, 'shop');
  for (const b of open) {
    const c = placedShops.get(b.id);
    if (!c) continue;
    used.add(c.id);
    const look = rand(w.seed, 'look', b.id);
    lots.push({
      x: c.x, y: c.y, w: c.w, h: c.h, slot: c.id, kind: 'shop', tier: 'shop', refId: b.id,
      label: b.name, look, art: shopFacade(b.kind, look), heads: [], residents: b.owner ? [b.owner] : [],
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

  // ---------- which streets exist yet ----------
  // The town grows outward: a street is built once something on it is in use (a home, a shop, or an
  // open landmark), plus whatever streets link it back to the main street. The rest are woodland.
  const near = (b: { x: number; y: number; w: number; h: number }, r: Road): boolean =>
    b.x < r.x + r.w + 3 && r.x < b.x + b.w + 3 && b.y < r.y + r.h + 3 && r.y < b.y + b.h + 3;
  const touching = (a: Road, b: Road): boolean => a.x <= b.x + b.w + 1 && b.x <= a.x + a.w + 1 && a.y <= b.y + b.h + 1 && b.y <= a.y + a.h + 1;
  const roads = plan.roads;
  const wanted = new Set<number>();
  roads.forEach((r, i) => {
    if (r.main || lots.some((l) => near(l, r)) || landmarks.some((l) => near(l, r))) wanted.add(i);
  });
  // Shortest chain back to the main street for every wanted street (breadth-first from the main street).
  const parent = new Map<number, number>();
  const queue = roads.map((r, i) => (r.main ? i : -1)).filter((i) => i >= 0);
  queue.forEach((i) => parent.set(i, -1));
  for (let q = 0; q < queue.length; q++) {
    const i = queue[q]!;
    roads.forEach((r, j) => {
      if (!parent.has(j) && touching(roads[i]!, r)) { parent.set(j, i); queue.push(j); }
    });
  }
  for (const i of [...wanted]) for (let j = parent.get(i) ?? -1; j >= 0; j = parent.get(j) ?? -1) wanted.add(j);
  const builtRoads = roads.filter((_, i) => wanted.has(i));
  // Streets not built yet: woodland grows right across them.
  roads.forEach((r, i) => {
    if (wanted.has(i)) return;
    const len = r.horizontal ? r.w : r.h;
    for (let t = 0; t < len; t += 3) {
      const k = rand(w.seed, 'wild', i, t);
      if (k > 0.85) continue;
      const tx = r.horizontal ? r.x + t + 1 : r.x - 1 + Math.floor(rand(w.seed, 'wild-s', i, t) * (r.w + 2));
      const ty = r.horizontal ? r.y - 1 + Math.floor(rand(w.seed, 'wild-s', i, t) * (r.h + 2)) : r.y + t + 1;
      if (waterNear(plan.water, tx, ty, 1)) continue;
      // Never over a street that is built (unbuilt streets cross built ones at junctions).
      if (builtRoads.some((b) => tx >= b.x - 1 && tx < b.x + b.w + 1 && ty >= b.y - 1 && ty < b.y + b.h + 1)) continue;
      props.push({
        kind: k < 0.5 ? 'tree' : k < 0.75 ? 'bush' : 'flowers',
        x: Math.round((tx + 0.5) * TILE), y: Math.round((ty + 1) * TILE), look: rand(w.seed, 'wild-l', i, t),
      });
    }
  });

  return { width: plan.width, height: plan.height, roads: builtRoads, lots, landmarks, props, plan };
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
