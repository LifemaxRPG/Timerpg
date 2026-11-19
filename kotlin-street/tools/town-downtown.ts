// The pack's buildings in the whole town (street.json v2, tools/town-graph.ts): the main street's shops as modular
// facade-kit blocks wearing their own storefronts, the downtown's towers, hotels and parking behind them as the town grows
// (src/town/downtown.ts), the old town hall at the back of the plaza and the chapel, the big mobile homes, and the pack's
// condos and terraces for the apartment blocks the game stood in with a modular condo front. Each is a street.json shop entry
// whose `spec` tools/buildings_pack.py compose_facade builds (its key `pk_...`), and `foot` its footprint in art pixels
// (setback, depth, width) as that builder makes it. Only the whole-town export calls this (v1 stays as it was).
import type { Lot, TownLayout } from '../../src/town/layout';
import type { TownPlan } from '../../src/town/plan';
import { rand } from '../../src/sim/rng';
import { chapel, downtownSites, downtownStanding, streetBlock, townHall, type CivicBuilding, type DowntownBuilding } from '../../src/town/downtown';
import type { Buildings } from './town-buildings';

/** A footprint in art pixels: the forecourt before the front wall, the building's depth, its width. */
export interface Foot { setback: number; depth: number; width: number }
export interface PackEntry {
  id: string; sprite: string; home: boolean; name: string; kind: string; owner: string; colour: string;
  parts: { key: string; w: number; h: number; y: number }[];
  yard?: { front: number; style: string };
  house?: { tier: string; design: number; colour: string };
  spec?: Record<string, unknown>;
  foot?: Foot;
  residents?: string[];
}

const KIT: Record<string, string> = { red: 'brick', blue: 'blue', gray: 'grey' };
/** The shop grounds with no sign of their own (the bank's, the law office's, the doctor's: condo grounds). */
const PLAIN = /^classic_(bank|law_office|doctor_s_office)_/;
/** How far behind the building line a downtown tower's front stands (street units): the shops, then a service alley. */
export const TOWER_SETBACK = 230;
/** A tower's (and its parking's) depth in art pixels. */
export const TOWER_DEPTH = 320;
/** Street units per art pixel (as town-graph's PX). */
const PX = 184 / 224;

/** Storeys of a pack spec (for the far skyline and the tests). */
export function storeysOf(spec: Record<string, unknown>): number {
  if (spec.kit === 'office') return 2 * Number(spec.blocks ?? 2) + 1;
  if (spec.kit === 'parking') return 0;
  const f = spec.floors; return 1 + (typeof f === 'number' ? f : Array.isArray(f) ? f.length : 2);
}

/**
 * What the pack makes of one of the export's entries (after `vary`, which dressed it as the game does): a shop a facade-kit
 * block of its street block's storeys over its own storefront (a corner bank or office the red-brick corner block), a
 * medium mobile home 40% of the time the big mobile home of the same design and colour, an apartment lot's condo stand-in
 * one of the pack's condos or terraces; undefined: left as it is. [foot] is known before `vary` (it does not depend on
 * the art the entry wears), [apply] rewrites the entry. Deterministic from the seed and the lot.
 */
export function packOf(e: PackEntry, lot: Lot, plan: TownPlan, seed: string): { foot: Foot; apply: () => void } | undefined {
  const R = (...k: (string | number)[]): number => rand(seed, 'pack', lot.slot, ...k);
  const id = `pk_${seed}_${lot.slot.replace(/[^a-z0-9]+/gi, '_')}`;
  if (!e.home) {
    const block = streetBlock(plan, lot.slot);
    if (!block) return undefined;
    const corner = block.cornerBlock && PLAIN.test(e.parts[0]?.key ?? '');
    const foot: Foot = corner ? { setback: 8, depth: 172, width: 192 } : { setback: 8, depth: 120, width: 224 };
    return { foot, apply: () => {
      const key = e.parts[0]?.key ?? '';
      e.spec = corner && PLAIN.test(key) ? { id, kit: 'c4_40', widthTiles: 6, seed: block.built }
        : { id, kit: KIT[e.colour] ?? 'brick', widthTiles: 7, ground: { picture: key }, floors: block.middles, roof: { style: 'flat' }, era: [block.built, 2100], seed: block.built };
      e.foot = foot;
      e.parts = [{ key: id, w: foot.width, h: 96 + 128 * block.middles, y: 0 }];
      e.yard = { front: 0, style: 'compact' };
    } };
  }
  const key0 = e.parts[0]?.key ?? '';
  if (lot.tier === 'medium' && /^house_medium_/.test(key0) && R('big') < 0.4) {
    return { foot: { setback: (e.yard?.front ?? 2) * 32, depth: 88, width: 320 }, apply: () => {
      const big = (e.parts[0]?.key ?? '').match(/^house_medium_(\d+)(_c\d+)?$/);
      if (!big) return;
      e.parts = [{ key: `house_big_${big[1]}${big[2] ?? ''}`, w: 320, h: Number(big[1]) <= 4 ? 192 : 256, y: 0 }];
      e.house = { tier: 'big', design: Number(big[1]), colour: (big[2] ?? '').slice(1) };
    } };
  }
  // an apartment lot: the condo stand-in always, one of the game's own blocks half the time (more variety along the streets)
  if (lot.tier === 'apartment' && (key0.startsWith('condo_ground_') || R('pack') < 0.5)) {
    const r = R('kit');
    const kit = KIT[e.colour] ?? 'brick';
    let spec: Record<string, unknown>; let w = 224;
    if (r < 0.35) {
      const tall = R('tall') < 0.5;
      spec = { id, kit: 'condo12', widthTiles: 10, colour: R('colour') < 0.5 ? 1 : 2, height: tall ? 'tall' : 'short', columns: tall ? [1, 3, 4, 5, 2] : [6, 9, 8, 10, 7] };
      w = 320;
    } else if (r < 0.55) spec = { id, kit: 'b3_46', widthTiles: 7 };
    else if (r < 0.68) { spec = { id, kit: 'b8', widthTiles: 5 }; w = 160; }
    else spec = { id, kit, widthTiles: 7, ground: { kind: 'condo' }, floors: 2 + Math.floor(R('floors') * 2), roof: { style: 'flat' }, seed: Math.floor(R('seed') * 1000) };
    const foot: Foot = { setback: 40, depth: 120, width: w };
    return { foot, apply: () => {
      e.spec = spec; e.foot = foot;
      e.yard = { front: 1, style: e.yard?.style ?? 'compact' };
      e.parts = [{ key: id, w, h: 400, y: 0 }];
    } };
  }
  return undefined;
}

/** Applies [packOf] to every lot's entry of [b] (after `vary`). */
export function packEntries(b: Buildings, plan: TownPlan, seed: string): void {
  for (const e of b.entries as PackEntry[]) {
    const lot = b.lotOf.get(e.sprite);
    if (lot) packOf(e, lot, plan, seed)?.apply();
  }
}

/** A building the export adds to a row with nobody in it (yet): its row placement comes from [rect] (tiles) as a plot's would. */
export interface ExtraBuilding {
  id: string; name: string; sprite: string; kind: 'tower' | 'parking' | 'townhall' | 'chapel';
  rect: { x: number; y: number; w: number; h: number };
  foot: Foot; built: number; demolished?: number; storeys: number;
  /** For the town hall: plaza props inside its footprint go. */
  plaza?: boolean;
}

/**
 * The downtown's buildings standing in [year] (towers, hotels, parking behind the main street's shops), the town hall and
 * the chapel, each named (the next bN) and given its street.json entry. [roadBuilt] says whether a rect fronts a street
 * built that year (the chapel waits for its street).
 */
export function extraBuildings(b: Buildings, plan: TownPlan, layout: TownLayout, year: number, seed: string): ExtraBuilding[] {
  const out: ExtraBuilding[] = [];
  const sites = new Map(downtownSites(plan).map((s) => [s.id, s]));
  const add = (x: Omit<ExtraBuilding, 'sprite'>, spec: Record<string, unknown>, h: number): void => {
    const sprite = b.extra({ id: `extra:${x.id}`, name: x.name, kind: 'Extra', parts: [{ key: String(spec.id), w: x.foot.width, h, y: 0 }],
      yard: { front: 1, style: 'compact' }, spec, foot: x.foot });
    out.push({ ...x, sprite });
  };
  const shopSlots = new Set(plan.candidates.filter((c) => c.kind === 'shop').map((c) => c.id));
  for (const d of downtownStanding(plan, year)) {
    const s = sites.get(d.site)!;
    if (!s.slots.every((sl) => shopSlots.has(sl))) continue;
    add(towerOf(d, s.rect, seed), towerSpec(d, seed), 400);
  }
  const hall = townHall(plan);
  if (hall && hall.built <= year) {
    // at the back of the plaza: its front ~1 tile behind the fountain's ring, its back a tile short of the plaza's
    const deep = hall.rect.h * 44.5;                               // world units (position scale)
    const setback = (deep - 300 * PX - 40) / PX;
    add({ id: 'townhall', name: hall.name, kind: 'townhall', rect: hall.rect, foot: { setback, depth: 300, width: 556 }, built: hall.built, storeys: 3, plaza: true },
      { id: `pk_${seed}_townhall`, kit: 'v4', widthTiles: 18 }, 400);
  }
  const ch: CivicBuilding | undefined = chapel(plan);
  if (ch && ch.built <= year && fronts(layout, ch.rect)) {
    add({ id: 'chapel', name: ch.name, kind: 'chapel', rect: ch.rect, foot: { setback: 48, depth: 305, width: 160 }, built: ch.built, storeys: 2 },
      { id: `pk_${seed}_chapel`, kit: rand(seed, 'pack', 'chapel', 'gate') < 0.5 ? 'chapel_gate' : 'chapel', widthTiles: 5 }, 288);
  }
  return out;
}

/**
 * The town's school as the pack's old school (the Victorian school and depot, V5) in half the towns: its street.json
 * entry rewritten (the landmark keeps its name, place and people).
 */
export function oldSchool(b: Buildings, sprite: string | undefined, seed: string): void {
  if (!sprite || rand(seed, 'pack', 'school', 'old') >= 0.5) return;
  const e = (b.entries as PackEntry[]).find((q) => q.sprite === sprite);
  if (!e) return;
  const id = `pk_${seed}_school`;
  e.spec = { id, kit: 'v5', widthTiles: 18 };
  e.foot = { setback: 48, depth: 240, width: 556 };
  e.parts = [{ key: id, w: 556, h: 400, y: 0 }];
}

/** Whether a built street of [layout] runs along [r] (within two tiles of one of its sides). */
function fronts(layout: TownLayout, r: { x: number; y: number; w: number; h: number }): boolean {
  return layout.roads.some((q) => q.x < r.x + r.w + 2 && r.x - 2 < q.x + q.w && q.y < r.y + r.h + 2 && r.y - 2 < q.y + q.h);
}

function towerOf(d: DowntownBuilding, rect: ExtraBuilding['rect'], _seed: string): Omit<ExtraBuilding, 'sprite'> {
  const width = d.kind === 'parking' ? 416 : 416;
  return { id: d.id, name: d.name, kind: d.kind === 'parking' ? 'parking' : 'tower', rect, built: d.built, demolished: d.demolished,
    foot: { setback: TOWER_SETBACK / PX, depth: TOWER_DEPTH, width }, storeys: d.floors };
}

/** The pack spec of a downtown building: an office (office kit, 13 tiles), a hotel (facade kit, condo ground), parking. */
export function towerSpec(d: DowntownBuilding, seed: string): Record<string, unknown> {
  const id = `pk_${seed}_${d.id.replace(/[^a-z0-9]+/gi, '_')}`;
  if (d.kind === 'parking') return { id, kit: 'parking', widthTiles: 13, depth: TOWER_DEPTH, seed: d.seed };
  if (d.kind === 'hotel') return { id, kit: d.style, widthTiles: 13, ground: { kind: 'condo' }, floors: d.floors - 1, roof: { style: 'flat' }, depth: TOWER_DEPTH, seed: d.seed };
  return { id, kit: 'office', style: Number(d.style), mid: 3, blocks: (d.floors - 1) / 2, depth: TOWER_DEPTH, seed: d.seed };
}

export type { Lot };
