import { rand } from '../sim/rng';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from '../sim/types';

// Town map in tiles. Pure function of (world): same world -> same layout.

export interface Rect { x: number; y: number; w: number; h: number }
export interface Road extends Rect { main: boolean; horizontal: boolean }

export type LotKind = 'villa' | 'small' | 'shop';
const SIZES: Record<LotKind, { w: number; h: number }> = {
  villa: { w: 9, h: 13 },
  small: { w: 6, h: 6 },
  shop: { w: 7, h: 13 },
};

export interface Lot extends Rect {
  /** Stable slot id, e.g. "shop:12". */
  slot: string;
  kind: 'house' | 'shop';
  /** House size class (villas go to the wealthiest households); shops are 'shop'. */
  tier: LotKind;
  /** Household head id (house) or business id (shop). */
  refId: Id;
  label: string;
  /** 0..1 stable value for choosing sprite variants and colors. */
  look: number;
  residents: Id[];
}

export interface TownLayout {
  width: number;
  height: number;
  roads: Road[];
  lots: Lot[];
}

export const MAP_W = 152;
const VERTICAL_ROADS = [20, 60, 100, 136]; // x of 2-wide north-south streets

interface Band { north: LotKind; south: LotKind; main?: boolean }
const BANDS: Band[] = [
  { north: 'villa', south: 'small' },
  { north: 'small', south: 'small' },
  { north: 'shop', south: 'shop', main: true },
  { north: 'small', south: 'small' },
  { north: 'small', south: 'small' },
  { north: 'small', south: 'small' },
];

interface Candidate extends Rect { kind: LotKind }
const ROADS: Road[] = [];
/** Roads never change between years or timelines. */
export const TOWN_ROADS: readonly Road[] = ROADS;
const CANDIDATES: Candidate[] = [];
export let MAIN_ROAD_Y = 0;
export let MAP_H = 0;

function lotRow(kind: LotKind, y: number): Candidate[] {
  const { w, h } = SIZES[kind];
  const out: Candidate[] = [];
  for (let x = 2; x + w <= MAP_W - 2; x += w + 1) {
    if (!VERTICAL_ROADS.some((vx) => x < vx + 3 && x + w > vx - 1)) out.push({ x, y, w, h, kind });
  }
  return out;
}

(function build(): void {
  let y = 2;
  for (const band of BANDS) {
    const hn = SIZES[band.north].h;
    const hs = SIZES[band.south].h;
    const roadY = y + hn + 1; // 1 tile of sidewalk between lots and the road
    ROADS.push({ x: 0, y: roadY, w: MAP_W, h: 2, main: !!band.main, horizontal: true });
    if (band.main) MAIN_ROAD_Y = roadY;
    CANDIDATES.push(...lotRow(band.north, y), ...lotRow(band.south, roadY + 3));
    y = roadY + 3 + hs + 2;
  }
  MAP_H = y;
  for (const x of VERTICAL_ROADS) ROADS.push({ x, y: 0, w: 2, h: MAP_H, main: false, horizontal: false });
})();

const SLOTS: Record<LotKind, Candidate[]> = {
  villa: CANDIDATES.filter((c) => c.kind === 'villa'),
  small: CANDIDATES.filter((c) => c.kind === 'small'),
  shop: CANDIDATES.filter((c) => c.kind === 'shop'),
};
export const capacity = { villa: SLOTS.villa.length, small: SLOTS.small.length, shop: SLOTS.shop.length };

/**
 * Assigns each id a slot index: its hashed preferred slot, probing forward when taken.
 * Ids are placed in sorted order, so identical inputs always give identical placement and
 * most entities keep their lot as the town changes around them.
 */
function assign(seed: string, ids: Id[], cap: number, salt: string): Map<Id, number> {
  const taken = new Set<number>();
  const result = new Map<Id, number>();
  for (const id of [...ids].sort()) {
    if (taken.size >= cap) break;
    let s = Math.floor(rand(seed, 'slot', salt, id) * cap);
    while (taken.has(s)) s = (s + 1) % cap;
    taken.add(s);
    result.set(id, s);
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
  const lots: Lot[] = [];

  const households = new Map<Id, Id[]>();
  for (const p of Object.values(w.people).filter(isAlive)) {
    const head = householdHead(w, p);
    (households.get(head) ?? households.set(head, []).get(head)!).push(p.id);
  }
  const wealthOf = (head: Id): number => households.get(head)!.reduce((s, id) => s + w.people[id]!.wealth, 0);
  const heads = [...households.keys()];
  const sorted = heads.map(wealthOf).sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)] ?? 0;

  // The wealthiest households (relative to the town median) live in villas, up to capacity.
  const villaHeads = heads
    .filter((h) => wealthOf(h) >= median * 2.5 && wealthOf(h) > 0)
    .sort((a, b) => wealthOf(b) - wealthOf(a) || (a < b ? -1 : 1))
    .slice(0, capacity.villa);
  const villaSet = new Set(villaHeads);
  const placeHouses = (ids: Id[], tier: 'villa' | 'small'): void => {
    const slots = assign(w.seed, ids, SLOTS[tier].length, tier);
    for (const [head, slot] of slots) {
      const rect = SLOTS[tier][slot]!;
      lots.push({
        ...rect, slot: `${tier}:${slot}`, kind: 'house', tier, refId: head,
        label: `${w.people[head]!.surname} house`, look: rand(w.seed, 'look', head),
        residents: households.get(head)!.sort(),
      });
    }
  };
  placeHouses(villaHeads, 'villa');
  placeHouses(heads.filter((h) => !villaSet.has(h)), 'small');

  const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined);
  const bSlots = assign(w.seed, open.map((b) => b.id), SLOTS.shop.length, 'shop');
  for (const b of open) {
    const slot = bSlots.get(b.id);
    if (slot === undefined) continue;
    lots.push({
      ...SLOTS.shop[slot]!, slot: `shop:${slot}`, kind: 'shop', tier: 'shop', refId: b.id,
      label: b.name, look: rand(w.seed, 'look', b.id), residents: b.owner ? [b.owner] : [],
    });
  }
  return { width: MAP_W, height: MAP_H, roads: ROADS, lots };
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
