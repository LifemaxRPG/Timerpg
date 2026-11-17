// The two streets of a real town (from OUR game logic), shared by tools/export-street.ts (the buildings, for the renderer)
// and tools/export-dialogue.ts (the people in them, for dialogue). Both see the same buildings under the same sprite names.
//
// What it finds, all from the town's real layout (src/town/layout.ts):
//  - streets[0], the main street: the buildings standing on the north side of the main road, west of a real crossroads, in
//    order along the road (the side-scrolling view looks north at them);
//  - streets[1], the cross street: the buildings on the west side of the road that runs north from that crossroads (after
//    the corner turn the camera looks west at them), nearest the junction first;
//  - junction: what the corner-turn scene needs besides those: the first buildings east of the crossroads on the main
//    street, and the buildings on the east side of the cross street;
//  - shops: every building above, once, for tools/make_buildings.py (each has its sprite name bN).
// Shops are drawn as the game draws them (shopFacade). Homes are stood in by a modular condo front in the district's
// colour (our house sprites are drawn from above, not front on), named after the household that really lives there.
import { runTimeline, worldAt } from '../../src/sim';
import { archetypeOf } from '../../src/sim/archetype';
import { layoutTown, type Lot, type TownLayout } from '../../src/town/layout';
import { fullName, type World } from '../../src/sim/types';
import { shopFacade } from '../../src/town/facade';
import { stack, type Colour } from '../../src/town/buildings';
import { neighborhoodOf } from '../../src/town/neighborhoods';
import { rand } from '../../src/sim/rng';
import type { Road } from '../../src/town/kinds';

export interface TownStreets {
  seed: string;
  year: number;
  w: World;
  layout: TownLayout;
  /** The two streets, by sprite name, in order along each. */
  streets: { name: string; buildings: string[] }[];
  /** Which lot each sprite name stands for. */
  lotOf: Map<string, Lot>;
  /** What tools/export-street.ts writes (street.json). */
  json: object;
}

const hash = (s: string): number => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return (h >>> 0); };

export function townStreets(seed: string, year: number): TownStreets {
  const w: World = worldAt(runTimeline({ seed, endYear: year }), year);
  const layout = layoutTown(w);

  const lots = [...layout.lots.filter((l) => l.kind === 'shop' && l.vacantSince === undefined), ...layout.lots.filter((l) => l.kind === 'house')];

  // -------------------------------------------------------------------------------------------- the main street
  const mains = layout.roads.filter((r) => r.main && r.horizontal);
  /** A lot fronting a horizontal road from either side (the street view shows one row, so both sides become that row). */
  const besideH = (l: Lot, r: Road): boolean =>
    (Math.abs(l.y + l.h - r.y) <= 1 || Math.abs(l.y - (r.y + r.h)) <= 1) && l.x + l.w > r.x && l.x < r.x + r.w;
  /** Keep one building per stretch of road: where two overlap (one on each side), the shop, else the first. */
  function oneRow(ls: Lot[], start: (l: Lot) => number, len: (l: Lot) => number): Lot[] {
    const out: Lot[] = [];
    for (const l of [...ls].sort((a, b) => start(a) - start(b) || (a.kind === 'shop' ? -1 : 1))) {
      const last = out[out.length - 1];
      if (last && start(l) < start(last) + len(last) - 1) { if (l.kind === 'shop' && last.kind !== 'shop') out[out.length - 1] = l; continue; }
      out.push(l);
    }
    return out;
  }
  const onMain = oneRow(lots.filter((l) => mains.some((r) => besideH(l, r))), (l) => l.x, (l) => l.w);
  const mainSet = new Set(lots.filter((l) => mains.some((r) => besideH(l, r))));

  // -------------------------------------------------------------------------------------------- the crossroads
  /** Vertical roads meeting the main street, from the north or from the south. */
  const verticals = layout.roads.filter((r) => !r.horizontal);
  type Arm = { roads: Road[]; north: boolean; x: number };
  const arms: Arm[] = [];
  for (const v of verticals) {
    const fromNorth = mains.some((m) => Math.abs(v.y + v.h - m.y) <= 3 && v.x >= m.x - 2 && v.x <= m.x + m.w + 2);
    const fromSouth = mains.some((m) => Math.abs(v.y - (m.y + m.h)) <= 3 && v.x >= m.x - 2 && v.x <= m.x + m.w + 2);
    if (!fromNorth && !fromSouth) continue;
    // the whole road away from the junction: contiguous vertical segments (gaps of a few tiles are crossings)
    const roads = [v];
    for (;;) {
      const end = fromNorth ? Math.min(...roads.map((r) => r.y)) : Math.max(...roads.map((r) => r.y + r.h));
      const next = verticals.find((r) => !roads.includes(r) && Math.abs(r.x - v.x) <= 1 &&
        (fromNorth ? r.y + r.h <= end && end - (r.y + r.h) <= 6 : r.y >= end && r.y - end <= 6));
      if (!next) break;
      roads.push(next);
    }
    arms.push({ roads, north: fromNorth, x: v.x });
  }
  const besideV = (l: Lot, a: Arm, side: 'w' | 'e' | 'any'): boolean => a.roads.some((r) =>
    (side !== 'e' && Math.abs(l.x + l.w - r.x) <= 1 || side !== 'w' && Math.abs(l.x - (r.x + r.w)) <= 1) && l.y + l.h > r.y && l.y < r.y + r.h + 1);
  /** Distance from the main street along the arm. */
  const away = (a: Arm) => (l: Lot): number => (a.north ? -(l.y + l.h) : l.y);
  const crossOf = (a: Arm): Lot[] => oneRow(lots.filter((l) => besideV(l, a, 'any') && !mainSet.has(l)), away(a), (l) => l.h);
  const before = (a: Arm): number => onMain.filter((l) => l.x + l.w <= a.x + 1).length;
  const score = (a: Arm): number => Math.min(crossOf(a).length, 6) * 3 + Math.min(before(a), 8) * 2 - (before(a) < 3 ? 100 : 0) - (crossOf(a).length < 2 ? 50 : 0);
  const arm = [...arms].sort((a, b) => score(b) - score(a) || a.x - b.x)[0];
  if (!arm) throw new Error(`no crossroads on the main street of ${seed}`);
  const junctionX = arm.x;
  const crossRow = crossOf(arm);
  const crossRowSet = new Set(crossRow);
  const crossOpp = lots.filter((l) => besideV(l, arm, 'any') && !mainSet.has(l) && !crossRowSet.has(l)).sort((a, b) => away(arm)(a) - away(arm)(b));
  const crossWest = crossRow;
  const crossEast = crossOpp.length ? crossOpp : crossRow.slice().reverse();
  const mainWest = onMain.filter((l) => l.x + l.w <= junctionX + 1).slice(-8);
  const mainEast = onMain.filter((l) => l.x >= junctionX + 1).slice(0, 2);

  // -------------------------------------------------------------------------------------------- street names
  const NAMES = ['Elm Street', 'Mill Road', 'Church Street', 'Station Road', 'Orchard Lane', 'Cedar Avenue', 'Union Street', 'Market Lane',
    'Willow Road', 'Bridge Street', 'Maple Avenue', 'Chapel Lane', 'Garden Street', 'Hill Road', 'Oak Street', 'Park Avenue'];
  const crossName = NAMES[hash(`${seed}:${junctionX}`) % NAMES.length]!;

  // -------------------------------------------------------------------------------------------- the buildings
  const CONDO_ROOF: Record<Colour, number[]> = { red: [2], blue: [4], gray: [6] };
  /** A home's front: the modular condo stack (as housing.ts builds it) in the district's colour, one floor per two residents. */
  function homeFacade(colour: Colour, look: number, residents: number) {
    const ci = ['red', 'blue', 'gray'].indexOf(colour);
    const r = (salt: string): number => rand('street-home', salt, look.toFixed(6));
    // of each colour's four ground floors: 1 an apartment lobby, 2 an ATM, 3 a bank's arch, 4 a front door with two windows
    const ground = ci * 4 + (residents > 2 ? 1 : 4);
    const floors = Math.max(1, Math.min(2, Math.ceil(residents / 2)));
    const middles = Array.from({ length: floors }, (_, i) => ({ key: `middle_${ci * 6 + 1 + Math.floor(r(`m${i}`) * 6)}`, w: 224, h: 128 }));
    const roofs = CONDO_ROOF[colour];
    return stack([{ key: `condo_ground_${ground}`, w: 224, h: 96 }, ...middles, { key: `roof_${roofs[Math.floor(r('r') * roofs.length)]!}`, w: 224, h: 192 }]);
  }

  let n = 0;
  const seen = new Map<Lot, string>();
  const lotOf = new Map<string, Lot>();
  const entries: object[] = [];
  /** What each building was made from, so a repeat along a street can be made again differently (see vary()). */
  const made = new Map<string, { kind: string; look: number; colour: Colour; home: boolean; residents: number; parts: { key: string; w: number; h: number; y: number }[] }>();
  function entry(l: Lot): string {
    const had = seen.get(l);
    if (had) return had;
    const sprite = `b${n++}`;
    seen.set(l, sprite);
    lotOf.set(sprite, l);
    const colour = neighborhoodOf(layout.plan, l.neighborhood).shopColour;
    if (l.kind === 'shop') {
      const b = w.businesses[l.refId]!;
      const owner = b.owner ? w.people[b.owner] : undefined;
      // the very building our game draws for this shop: its sprite parts, bottom to top
      const look = (hash(b.id) % 1000000) / 1000000;
      const art = shopFacade(b.kind, look, year, colour);
      const parts = art.layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y }));
      made.set(sprite, { kind: b.kind, look, colour, home: false, residents: 0, parts });
      entries.push({ id: b.id, sprite, home: false, name: b.name, kind: b.kind, owner: owner ? fullName(owner) : '', colour: art.colour, parts });
    } else {
      const head = w.people[l.refId];
      const surname = head ? fullName(head).split(' ').slice(-1)[0] : '';
      const art = homeFacade(colour, l.look, l.residents.length);
      const parts = art.layers.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y }));
      made.set(sprite, { kind: 'Home', look: l.look, colour, home: true, residents: l.residents.length, parts });
      entries.push({ id: `home:${l.slot}`, sprite, home: true, name: l.residents.length > 2 ? `${surname} House`.trim() : `The ${surname} home`.trim(),
        kind: 'Home', owner: head ? fullName(head) : '', residents: l.residents.map((id) => (w.people[id] ? fullName(w.people[id]!) : id)), colour,
        parts });
    }
    return sprite;
  }

  /**
   * No two buildings along a street wear the same front: a storefront that repeats one already on the street becomes another
   * of its kind's variants (or, if the kind has no other, the next colour family's); an upper floor that repeats becomes
   * another of its colour's six. The buildings, their order and their kinds stay as the town has them; only which of the art
   * pack's pictures each wears changes. Deterministic.
   */
  function vary(row: string[]): void {
    const usedGround = new Set<string>(); const usedMid = new Set<string>();
    const COLOURS: Colour[] = ['red', 'blue', 'gray'];
    const ground = (parts: { key: string }[]) => parts.find((p) => !p.key.startsWith('middle_') && !p.key.startsWith('roof'))?.key ?? '';
    for (const sprite of row) {
      const m = made.get(sprite); if (!m) continue;
      if (usedGround.has(ground(m.parts))) {
        let found: { key: string; w: number; h: number; y: number }[] | null = null;
        const order = [m.colour, ...COLOURS.filter((c) => c !== m.colour)];
        for (const c of order) {
          for (let a = 1; a <= 8 && !found; a++) {
            const look = (m.look + a * 0.6180339) % 1;
            const parts = m.home ? homeFacade(c, look, m.residents).layers : shopFacade(m.kind, look, year, c).layers;
            const ps = parts.map((ly) => ({ key: ly.key, w: ly.w, h: ly.h, y: ly.y }));
            if (!usedGround.has(ground(ps))) found = ps;
          }
          if (found) break;
        }
        if (found) m.parts.splice(0, m.parts.length, ...found);
      }
      usedGround.add(ground(m.parts));
      // upper floors: another of the same colour's six middles when this one is already on the street
      for (const p of m.parts) {
        if (!p.key.startsWith('middle_')) continue;
        if (usedMid.has(p.key)) {
          const n = Number(p.key.slice(7)); const band = Math.floor((n - 1) / 6);
          for (let k = 1; k < 6; k++) {
            const alt = `middle_${band * 6 + 1 + ((n - 1 + k) % 6)}`;
            if (!usedMid.has(alt)) { p.key = alt; break; }
          }
        }
        usedMid.add(p.key);
      }
    }
  }
  const main = mainWest.map(entry);
  const cross = crossWest.slice(0, 8).map(entry);
  const east = mainEast.map(entry);
  const crossOpposite = crossEast.slice(0, 5).map(entry);
  // the main street (with the buildings past the junction) and each side of the cross street, each without repeats
  vary([...main, ...east]); vary(cross); vary(crossOpposite);
  const streets = [{ name: 'Main Street', buildings: main }, { name: crossName, buildings: cross }];

  return {
    seed, year, w, layout, streets, lotOf,
    json: {
      town: seed, year, type: archetypeOf(seed).label,
      streets,
      junction: { x: junctionX, mainEast: east, crossEast: crossOpposite },
      shops: entries,
    },
  };
}
