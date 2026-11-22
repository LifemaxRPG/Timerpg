// The pack's buildings in the whole town (street.json v2, tools/town-graph.ts): the main street's shops as modular
// facade-kit blocks wearing their own storefronts, the downtown's towers, hotels and parking behind them as the town grows
// (src/town/downtown.ts), the old town hall at the back of the plaza and the chapel, the big mobile homes, and the pack's
// condos and terraces for the apartment blocks the game stood in with a modular condo front. Each is a street.json shop entry
// whose `spec` tools/buildings_pack.py compose_facade builds (its key `pk_...`), and `foot` its footprint in art pixels
// (setback, depth, width) as that builder makes it. Only the whole-town export calls this (v1 stays as it was).
import type { Lot, TownLayout } from '../../src/town/layout';
import type { TownPlan } from '../../src/town/plan';
import { rand } from '../../src/sim/rng';
import { chapel, CORE_RANK, downtownSites, downtownStanding, streetBlock, townHall, type CivicBuilding, type DowntownBuilding } from '../../src/town/downtown';
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
/** How far a post-war stand-alone shop stands back from the building line (street units): its front car park. */
export const FRONT_LOT = 210;
/** A tower's (and its parking's) depth in art pixels. */
export const TOWER_DEPTH = 320;
/** A street-wall shop's width in art pixels: its 7-tile plot at the position scale (7 x 1.693 tiles, a hair under: the
 * facade kit builds 12 tiles, its last few pixels hidden in the neighbour's wall). */
export const WALL_W = 379;
/** Street units per art pixel (as town-graph's PX). */
const PX = 184 / 224;

/** Storeys of a pack spec (for the far skyline and the tests). */
export function storeysOf(spec: Record<string, unknown>): number {
  if (spec.kit === 'office') return 2 * Number(spec.blocks ?? 2) + 1;
  if (spec.kit === 'parking') return 0;
  if (spec.kit === 'b3_46' || spec.kit === 'b8') return 5;
  if (spec.kit === 'condo12') return spec.height === 'tall' ? 4 : 3;
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
    // (the planned town's core: one continuous street wall, each block as wide as its plot at the town's position scale,
    // WALL_W, so the shops of a face stand shoulder to shoulder from corner to corner; no corner block there)
    const wall = !block.standalone && !!plan.v2;
    const corner = !wall && block.cornerBlock && PLAIN.test(e.parts[0]?.key ?? '');
    // (a post-war stand-alone shop stands behind its own car park: extraBuildings lays the car park before it)
    const foot: Foot = corner ? { setback: 8, depth: 172, width: 192 } : { setback: block.standalone ? FRONT_LOT / PX : 8, depth: 120, width: wall ? WALL_W : 224 };
    return { foot, apply: () => {
      const key = e.parts[0]?.key ?? '';
      e.spec = corner && PLAIN.test(key) ? { id, kit: 'c4_40', widthTiles: 6, seed: block.built }
        : { id, kit: KIT[e.colour] ?? 'brick', widthTiles: wall ? 12 : 7, ground: { picture: key }, floors: block.middles, roof: { style: 'flat' }, era: [block.built, 2100], seed: block.built };
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
  // an apartment lot: the condo stand-in always, one of the game's own blocks half the time (more variety along the streets).
  // Heights fit the neighbourhood: by the old core (rank below CORE_RANK) up to five storeys (Condo_3_46, Condo_8, the tall
  // terrace, a facade-kit block of 3-4); out in the residential streets, among the houses and mobile homes, a low walk-up of
  // 2-3 storeys only (the short terrace, a facade-kit block of 2-3: a single middle floor is a tall storey). Never a tower
  // on a house lot: only apartment lots get here.
  // (out in the residential streets the game's own tall blocks, Condo_3_45, Condo_5 and Condo_9 at 4-6 storeys, are always replaced)
  const core = lot.tier === 'apartment' && apartmentNearCore(plan, lot.slot);
  if (lot.tier === 'apartment' && (key0.startsWith('condo_ground_') || R('pack') < 0.5 || (!core && TALL_CONDO.test(key0)))) {
    const r = R('kit');
    const kit = KIT[e.colour] ?? 'brick';
    let spec: Record<string, unknown>; let w = 224;
    if (core) {
      if (r < 0.35) {
        const tall = R('tall') < 0.5;
        // (seven tiles, the plot's width: a wider block would stand into its neighbours' along the row)
        spec = { id, kit: 'condo12', widthTiles: 7, colour: R('colour') < 0.5 ? 1 : 2, height: tall ? 'tall' : 'short', columns: tall ? [1, 4, 2] : [6, 9, 7] };
      } else if (r < 0.55) spec = { id, kit: 'b3_46', widthTiles: 7 };
      else if (r < 0.68) { spec = { id, kit: 'b8', widthTiles: 5 }; w = 160; }
      else spec = { id, kit, widthTiles: 7, ground: { kind: 'condo' }, floors: 2 + Math.floor(R('floors') * 2), roof: { style: 'flat' }, seed: Math.floor(R('seed') * 1000) };
    } else if (r < 0.45) {
      spec = { id, kit: 'condo12', widthTiles: 7, colour: R('colour') < 0.5 ? 1 : 2, height: 'short', columns: [6, 9, 7] };
    } else spec = { id, kit, widthTiles: 7, ground: { kind: 'condo' }, floors: 1 + Math.floor(R('floors') * 2), roof: { style: 'flat' }, seed: Math.floor(R('seed') * 1000) };
    const foot: Foot = { setback: 40, depth: 120, width: w };
    return { foot, apply: () => {
      e.spec = spec; e.foot = foot;
      e.yard = { front: 1, style: e.yard?.style ?? 'compact' };
      e.parts = [{ key: id, w, h: 400, y: 0 }];
    } };
  }
  return undefined;
}

/** The game's own apartment pictures taller than a residential street's 2-3 storeys. */
const TALL_CONDO = /^condo_(3_45|5_\d|9)$/;

/** Whether apartment lot [slot] stands by the old core (its plot's rank, tiles from the centre, below CORE_RANK), where taller blocks belong. */
export function apartmentNearCore(plan: TownPlan, slot: string): boolean {
  const c = plan.candidates.find((q) => q.id === slot);
  return !!c && c.rank < CORE_RANK;
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
    // (not behind plots with no shop on them this year: a tower or car park in the woods)
    if (!s.slots.some((sl) => layout.lots.some((l) => l.slot === sl))) continue;
    add(towerOf(d, s.rect, seed), towerSpec(d, seed), 400);
  }
  // the post-war stand-alone shops' front car parks (from the building line to a few units short of the shop's front)
  for (const l of layout.lots) {
    if (l.kind !== 'shop') continue;
    const block = streetBlock(plan, l.slot);
    if (!block?.standalone) continue;
    const id = `lot:${l.slot}`;
    add({ id, name: 'Parking', kind: 'parking', rect: { x: l.x, y: l.y, w: l.w, h: l.h }, foot: { setback: 0, depth: Math.floor((FRONT_LOT - 14) / PX), width: 224 },
      built: block.built, storeys: 0 },
    { id: `pk_${seed}_${id.replace(/[^a-z0-9]+/gi, '_')}`, kit: 'parking', widthTiles: 7, depth: Math.floor((FRONT_LOT - 14) / PX), seed: block.built }, 4);
  }
  // the planned town's core plots reserved for a building not standing this year (the cinema before it opens, the market
  // before the 1950s, the Gazette's before 1905-40, a model pulled down): a gravel or asphalt vacant lot, not a lawn, so
  // the street wall reads as a downtown's
  const BEFORE = new Set(['field', 'woods', 'reserved', 'square', 'shops', 'house', 'plaza']);
  for (const q of plan.v2?.reserved ?? []) {
    if (q.district !== 'core' || (q.face !== 'N' && q.face !== 'S')) continue;
    const standing = q.id.startsWith('landmark:')
      ? plan.landmarks.some((l) => `landmark:${l.id}` === q.id && l.openYear <= year)
      : q.chain.some((u) => !BEFORE.has(u.use) && u.from <= year && (u.to === undefined || year < u.to));
    if (standing) continue;
    const id = `vacant:${q.id.replace(/^[a-z]+:/, '')}`;
    const depth = Math.min(Math.floor(q.h * 44.5 / PX) - 8, 400);
    // (as wide as its plot at the position scale, as the street wall's shops: a gap in the wall, not a lawn)
    // (the pack's parking picture is at most 15 tiles: a wider plot's lot is that, in the plot's middle)
    const wT = Math.min(15, Math.floor(q.w * WALL_W / 7 / 32));
    add({ id, name: 'Vacant lot', kind: 'parking', rect: { x: q.x, y: q.y, w: q.w, h: q.h }, foot: { setback: 0, depth, width: wT === 15 ? 480 : Math.floor(q.w * WALL_W / 7) }, built: 1880, storeys: 0 },
      { id: `pk_${seed}_${id.replace(/[^a-z0-9]+/gi, '_')}`, kit: 'parking', widthTiles: wT, depth, seed: Math.floor(rand(seed, 'vacant', q.id) * 1000) }, 4);
  }
  const hall = townHall(plan);
  // (the hall is wider than the plaza at the art's scale: only where nothing stands within two tiles of the plaza's sides)
  const pz = plan.v2?.townHall ?? plan.plaza;
  // (the planned town's square: the hall's own band across its back, the post office and clock tower on its front corners)
  const band = plan.v2 && !plan.v2.townHall
    ? { x: pz.x + pz.w / 2 - 9, w: 18, y: plan.mainY >= pz.y + pz.h / 2 ? pz.y : pz.y + pz.h - 10, h: 10 } : pz;
  const crowded = [...plan.candidates, ...plan.landmarks].some((q) => q.x < band.x + band.w + 2 && band.x - 2 < q.x + q.w && q.y < band.y + band.h && band.y < q.y + q.h);
  if (hall && hall.built <= year && !crowded) {
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
