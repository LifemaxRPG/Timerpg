import { rand } from '../sim/rng';
import { ageIn, fullName, isAlive, type Id, type Person, type World } from '../sim/types';

// Town map in tiles. Pure function of (world): same world -> same layout.
export const MAP_W = 96;
export const MAP_H = 72;
const LOT_W = 4;
const LOT_GAP = 1;
const STEP = LOT_W + LOT_GAP;

export interface Rect { x: number; y: number; w: number; h: number }
export interface Road extends Rect { main: boolean; horizontal: boolean }

export interface Lot extends Rect {
  /** Stable slot id, e.g. "c:12" (commercial) or "r:40" (residential). */
  slot: string;
  kind: 'house' | 'shop';
  /** Household head id (house) or business id (shop). */
  refId: Id;
  label: string;
  /** 0..1 stable value for choosing colors/variants. */
  look: number;
  residents: Id[];
}

export interface TownLayout {
  width: number;
  height: number;
  roads: Road[];
  lots: Lot[];
}

const VERTICAL_ROADS = [14, 38, 58, 80]; // x of 2-wide north-south streets
const AVENUES = [10, 22, 46, 58]; // y of 2-tall east-west residential streets
const MAIN_Y = 34;

function roads(): Road[] {
  const out: Road[] = [{ x: 0, y: MAIN_Y, w: MAP_W, h: 2, main: true, horizontal: true }];
  for (const y of AVENUES) out.push({ x: 0, y, w: MAP_W, h: 2, main: false, horizontal: true });
  for (const x of VERTICAL_ROADS) out.push({ x, y: 0, w: 2, h: MAP_H, main: false, horizontal: false });
  return out;
}

/** Candidate lots along a horizontal road: one row north (height nh) and one south. */
function lotRow(roadY: number, north: boolean, h: number): Rect[] {
  const y = north ? roadY - h : roadY + 2;
  const out: Rect[] = [];
  for (let x = 1; x + LOT_W <= MAP_W - 1; x += STEP) {
    const crossesRoad = VERTICAL_ROADS.some((vx) => x < vx + 2 && x + LOT_W > vx);
    if (!crossesRoad) out.push({ x, y, w: LOT_W, h });
  }
  return out;
}

const COMMERCIAL: Rect[] = [...lotRow(MAIN_Y, true, 5), ...lotRow(MAIN_Y, false, 5)];
const RESIDENTIAL: Rect[] = AVENUES.flatMap((y) => [...lotRow(y, true, 4), ...lotRow(y, false, 4)]);

export const commercialCapacity = COMMERCIAL.length;
export const residentialCapacity = RESIDENTIAL.length;

/**
 * Assigns each id a slot index: its hashed preferred slot, probing forward when taken.
 * Ids are placed in sorted order, so identical inputs always give identical placement and
 * most entities keep their lot as the town changes around them.
 */
function assign(seed: string, ids: Id[], capacity: number, salt: string): Map<Id, number> {
  const taken = new Set<number>();
  const result = new Map<Id, number>();
  for (const id of [...ids].sort()) {
    if (taken.size >= capacity) break;
    let s = Math.floor(rand(seed, 'slot', salt, id) * capacity);
    while (taken.has(s)) s = (s + 1) % capacity;
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
  const hSlots = assign(w.seed, [...households.keys()], RESIDENTIAL.length, 'house');
  for (const [head, slot] of hSlots) {
    const rect = RESIDENTIAL[slot]!;
    const hp = w.people[head]!;
    lots.push({
      ...rect, slot: `r:${slot}`, kind: 'house', refId: head,
      label: `${hp.surname} house`, look: rand(w.seed, 'look', head),
      residents: households.get(head)!.sort(),
    });
  }

  const open = Object.values(w.businesses).filter((b) => b.closedYear === undefined);
  const bSlots = assign(w.seed, open.map((b) => b.id), COMMERCIAL.length, 'shop');
  for (const b of open) {
    const slot = bSlots.get(b.id);
    if (slot === undefined) continue;
    lots.push({
      ...COMMERCIAL[slot]!, slot: `c:${slot}`, kind: 'shop', refId: b.id,
      label: b.name, look: rand(w.seed, 'look', b.id), residents: b.owner ? [b.owner] : [],
    });
  }
  return { width: MAP_W, height: MAP_H, roads: roads(), lots };
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
