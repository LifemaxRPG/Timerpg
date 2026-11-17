// Furnishes a room's areas by Wave Function Collapse (wfc.ts), from rules per kind of room and from who uses it: a home
// gets a bed for each couple, single adult and child who lives there (children's beds are the green ones, with toys
// around), a piano or a guitar where someone is musical, a desk and books where someone is studious; a shop's stock
// grows with its capital. The walls and room divisions stay keyed to the property (interiors.ts); only the furniture
// follows the people, and the same household always gets the same room.
import { INTERIOR_ASSETS } from './interiorAssets';
import { collapse, type PieceRule, type WfcRoom, type Placed } from './wfc';
import { rand } from '../sim/rng';
import type { World } from '../sim/types';
import type { Rect } from './kinds';
import type { Lot } from './layout';

export const CELL = 32;
type Asset = keyof typeof INTERIOR_ASSETS;

/** Each piece's footprint in cells: the part standing on the floor (the sprite rises above it, 3/4 view). */
export const FOOT: Partial<Record<Asset, [number, number]>> = {
  table: [2, 1], plant: [1, 1], sofa: [2, 1], wardrobe: [2, 1], cabinet: [2, 1], chair: [1, 1],
  bed: [3, 2], bedGreen: [3, 2], counter: [2, 1], sink: [2, 1], stove: [1, 1], fridge: [1, 1], shelf: [2, 1],
  desk: [2, 1], hospitalBed: [2, 2], medical: [2, 1], guitar: [1, 1], piano: [2, 1], toilet: [1, 1],
  bathroomSink: [2, 1], weights: [1, 1], treadmill: [2, 2], mannequin: [1, 1], fishingRack: [2, 1], tackle: [2, 1],
  iceDisplay: [1, 1], bakeryRack: [2, 1], meatCase: [1, 1], clothingRack: [2, 1], gift: [1, 1], toyCar: [1, 1],
  dollhouse: [2, 1], files: [2, 1], bookcase: [2, 1], teacherDesk: [2, 1], schoolDesk: [1, 1], blackboard: [2, 1],
};

/** What is drawn: the sprite, its footprint, solid or not, stock (gone when the shop stands empty). */
export interface FurnishedPiece { key: string; x: number; y: number; w: number; h: number; foot: Rect; solid: boolean; stock: boolean }

interface Rule extends PieceRule { asset: Asset; stock?: boolean; solid?: boolean }

function r(asset: Asset, place: PieceRule['place'], o: Partial<Rule> = {}): Rule {
  const [w, h] = FOOT[asset]!;
  return { name: asset, asset, w, h, place, front: true, ...o };
}

/** Who lives here, for a home's rules. */
export interface Household { couples: number; singles: number; kids: number; teens: number; musical: boolean; studious: boolean; elderly: boolean }

export function householdOf(w: World, lot: Lot | undefined): Household {
  const people = (lot?.residents ?? []).map((id) => w.people[id]).filter((p) => p && p.deathYear === undefined) as World['people'][string][];
  const age = (p: typeof people[number]): number => w.year - p.birthYear;
  const ids = new Set(people.map((p) => p.id));
  const adults = people.filter((p) => age(p) >= 18);
  const paired = adults.filter((p) => p.spouse && ids.has(p.spouse));
  return {
    couples: Math.floor(paired.length / 2),
    singles: adults.length - paired.length,
    kids: people.filter((p) => age(p) < 13).length,
    teens: people.filter((p) => age(p) >= 13 && age(p) < 18).length,
    musical: people.some((p) => p.traits.charm > 0.72),
    studious: people.some((p) => p.traits.diligence > 0.72),
    elderly: adults.some((p) => age(p) >= 68),
  };
}

const KITCHEN: Rule[] = [
  r('counter', 'back', { min: 1, max: 3, near: { stove: 40, sink: 40, fridge: 30, counter: 20 } }),
  r('sink', 'back', { min: 1, max: 1, near: { counter: 40, stove: 20, fridge: 10 } }),
  r('stove', 'back', { min: 1, max: 1, near: { counter: 40, sink: 20, fridge: 10 } }),
  r('fridge', 'back', { min: 1, max: 1, near: { counter: 30, stove: 10, sink: 10 } }),
];

function homeRules(area: string, hh: Household, extra: number): Rule[] {
  if (area === 'Bathroom') return [
    r('toilet', 'back', { min: 1, max: 1 }),
    r('bathroomSink', 'back', { min: 1, max: 1, apart: { toilet: 0.5 } }),
    r('plant', 'corner', { max: 1, weight: 0.3 }),
  ];
  if (area === 'Bedroom') {
    const big = hh.couples + (hh.singles > 0 && hh.couples === 0 ? 1 : 0);
    const small = hh.kids + hh.teens + Math.max(0, hh.singles - (hh.couples === 0 ? 1 : 0));
    const bedMin = Math.min(2, Math.max(1, big)), kidMin = Math.min(2, small);
    return [
      r('bed', 'wall', { min: bedMin, max: bedMin, apart: { bed: 0.3, bedGreen: 0.5 } }),
      ...(kidMin ? [r('bedGreen', 'wall', { min: kidMin, max: kidMin, apart: { bed: 0.5, bedGreen: 0.6 } })] : []),
      r('wardrobe', 'back', { min: 1, max: 2, apart: { wardrobe: 0.3 } }),
      r('cabinet', 'wall', { max: 1, weight: 0.4 }),
      r('plant', 'corner', { max: 1, weight: 0.5 }),
      ...(hh.kids ? [r('toyCar', 'any', { max: 2, weight: 0.6, front: false, solid: false }), r('dollhouse', 'wall', { max: 1, weight: 0.8 })] : []),
      ...(hh.musical ? [r('guitar', 'wall', { max: 1, weight: 1.2 })] : []),
      ...(hh.studious || hh.teens ? [r('desk', 'wall', { max: 1, weight: 1.5 }), r('chair', 'any', { max: 1, under: ['desk'], weight: 6 })] : []),
    ];
  }
  // living & kitchen
  return [
    ...KITCHEN,
    r('table', 'free', { min: 1, max: 1, front: false }),
    r('chair', 'any', { max: 2 + Math.min(2, hh.kids + hh.teens), under: ['table', 'desk'], weight: 6 }),
    r('sofa', 'wall', { min: 1, max: 1 + (extra > 0.6 ? 1 : 0), apart: { sofa: 0.5 } }),
    r('plant', 'corner', { max: 3 + (hh.elderly ? 1 : 0), weight: 0.8 }),
    r('cabinet', 'wall', { max: 2, weight: 0.6, near: { sofa: 3 } }),
    r('bookcase', 'back', { max: 1 + (hh.studious || hh.elderly ? 2 : 0), weight: hh.studious || hh.elderly ? 2 : 0.5, near: { bookcase: 10, piano: 2 } }),
    ...(hh.musical ? [r('piano', 'back', { max: 1, weight: 2 })] : []),
  ];
}

/** Rules for a shop's back room [back] and its front room (where customers stand). [stock] 0..1 from its capital. */
function shopRules(template: string, back: boolean, stock: number): Rule[] | null {
  const more = (n: number): number => n + Math.round(stock * 2);
  const counters = (n = 2): Rule => r('counter', 'back', { min: n, max: n + 1, near: { counter: 10 } });
  const plant = r('plant', 'corner', { max: 2, weight: 0.5 });
  const seat = [r('table', 'free', { max: 1, weight: 0.5, front: false }), r('chair', 'any', { max: 2, under: ['table'], weight: 6 })];
  const goods = (asset: Asset, place: PieceRule['place'], n: number, o: Partial<Rule> = {}): Rule =>
    r(asset, place, { min: n, max: more(n), stock: true, near: { [asset]: 3 }, ...o });
  switch (template) {
    case 'Bakery': return back
      ? [r('stove', 'back', { min: 2, max: 3, near: { stove: 6, counter: 3 } }), counters(1), goods('bakeryRack', 'back', 1), r('fridge', 'back', { min: 1, max: 1 }), r('sink', 'back', { max: 1 })]
      : [counters(), goods('bakeryRack', 'wall', 2), r('table', 'free', { max: 2, weight: 0.7, front: false }), r('chair', 'any', { max: 4, under: ['table'], weight: 6 }), plant];
    case 'Butcher': return back
      ? [r('fridge', 'back', { min: 2, max: 3, near: { fridge: 8 } }), counters(1), r('sink', 'back', { min: 1, max: 1, near: { counter: 4 } })]
      : [counters(), goods('meatCase', 'wall', 2), plant];
    case 'Ice Cream Parlor': return back
      ? [r('fridge', 'back', { min: 1, max: 2, near: { fridge: 6 } }), r('sink', 'back', { min: 1, max: 1 }), counters(1)]
      : [goods('iceDisplay', 'back', 3, { near: { iceDisplay: 12 } }), r('table', 'free', { min: 1, max: 3, front: false }), r('chair', 'any', { max: 5, under: ['table'], weight: 8 }), plant];
    case 'Bait Shop': return back
      ? [goods('tackle', 'back', 2), r('cabinet', 'wall', { max: 1 })]
      : [counters(1), goods('fishingRack', 'wall', 2), goods('tackle', 'free', 0, { weight: 0.5, front: false }), plant];
    case 'Music Store': return back
      ? [r('piano', 'back', { min: 1, max: 2, near: { piano: 4 } }), counters(1), goods('guitar', 'wall', 2, { near: { guitar: 6 } })]
      : [goods('guitar', 'wall', 2, { near: { guitar: 6 } }), r('piano', 'free', { max: 1, front: false, weight: 0.8 }), r('chair', 'any', { max: 2, weight: 0.4 }), plant];
    case 'Clothing Store': return back
      ? [r('wardrobe', 'back', { min: 1, max: 2 }), r('chair', 'wall', { max: 1, weight: 0.5 }), r('mannequin', 'wall', { max: 1, stock: true })]
      : [counters(1), goods('clothingRack', 'any', 2), goods('mannequin', 'wall', 1), plant];
    case 'Dress Shop': return back
      ? [r('wardrobe', 'back', { min: 1, max: 2 }), r('sofa', 'wall', { min: 1, max: 1 }), plant]
      : [counters(1), goods('mannequin', 'any', 3, { near: { mannequin: 2 } }), goods('clothingRack', 'wall', 1), plant];
    case 'Toy Store': return back
      ? [r('cabinet', 'back', { min: 2, max: 3, near: { cabinet: 4 } }), goods('dollhouse', 'wall', 1), goods('toyCar', 'any', 1, { front: false, solid: false })]
      : [counters(1), r('cabinet', 'any', { min: 2, max: 4 }), goods('gift', 'any', 2, { front: false, solid: false }), goods('toyCar', 'any', 1, { front: false, solid: false }), plant];
    case 'Bank': return back
      ? [r('files', 'back', { min: 3, max: 5, near: { files: 12 } }), r('desk', 'wall', { min: 1, max: 1 }), r('chair', 'any', { max: 1, under: ['desk'], weight: 6 })]
      : [counters(2), r('sofa', 'wall', { min: 1, max: 2 }), r('desk', 'free', { max: 1, front: false }), r('chair', 'any', { max: 2, under: ['desk'], weight: 6 }), plant];
    default: return null;
  }
}

/** Rules for the rooms of the offices and the gym (areas named in interiors.ts). */
function officeRules(template: string, area: number, stock: number): Rule[] | null {
  const plant = r('plant', 'corner', { max: 2, weight: 0.5 });
  switch (template) {
    case 'Gym': return area === 0
      ? [r('treadmill', 'free', { min: 2, max: 3 + Math.round(stock), front: false, near: { treadmill: 4 } }), r('weights', 'wall', { min: 2, max: 4, near: { weights: 6 } }), r('sofa', 'wall', { max: 1 }), plant]
      : [r('wardrobe', 'back', { min: 1, max: 2, near: { wardrobe: 6 } }), r('bathroomSink', 'wall', { min: 1, max: 1 })];
    case 'Law Office': return area === 0
      ? [r('desk', 'free', { min: 1, max: 1, front: false }), r('chair', 'any', { min: 1, max: 2, under: ['desk'], weight: 6 }), r('sofa', 'wall', { min: 1, max: 1 }), r('bookcase', 'back', { max: 2, near: { bookcase: 6 } }), plant]
      : [r('bookcase', 'back', { min: 1, max: 3, near: { bookcase: 8 } }), r('files', 'wall', { min: 1, max: 2 })];
    case "Doctor's Office": return area === 0
      ? [r('desk', 'back', { min: 1, max: 1 }), r('chair', 'wall', { min: 2, max: 4, near: { chair: 6 } }), r('sofa', 'wall', { min: 1, max: 1 }), plant]
      : [r('hospitalBed', 'back', { min: 1, max: 1 }), r('medical', 'back', { min: 1, max: 2 }), r('bathroomSink', 'wall', { min: 1, max: 1 })];
    default: return null;
  }
}

export interface Shell { width: number; height: number; walls: Rect[]; areas: { name: string; rect: Rect }[]; exit: { x: number; y: number }; template: string; home: boolean }

/**
 * The furniture of [shell] by Wave Function Collapse, or null when its template has no rules (the large public buildings
 * keep their drawn layouts) or no arrangement fits (the caller falls back to the drawn layout).
 */
export function furnish(w: World, id: string, lot: Lot | undefined, shell: Shell): FurnishedPiece[] | null {
  const { width: W, height: H, walls } = shell;
  const cols = Math.floor(W / CELL), rows = Math.floor(H / CELL);
  const cellRect = (cx: number, cy: number): Rect => ({ x: cx * CELL, y: cy * CELL, w: CELL, h: CELL });
  const overlap = (a: Rect, b: Rect): boolean => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
  const clearOfWalls = (cx: number, cy: number): boolean => {
    const c = cellRect(cx, cy);
    return c.x >= 32 && c.y >= 96 && c.x + CELL <= W - 32 && c.y + CELL <= H - 24 && !walls.some((wl) => overlap(c, wl));
  };
  const inArea = (a: Rect, cx: number, cy: number): boolean => {
    const c = cellRect(cx, cy);
    return c.x >= a.x && c.y >= a.y && c.x + CELL <= a.x + a.w && c.y + CELL <= a.y + a.h;
  };
  const taken = new Uint8Array(cols * rows);
  const walkable = (cx: number, cy: number): boolean => clearOfWalls(cx, cy) && !taken[cy * cols + cx];
  const door = { cx: Math.floor(shell.exit.x / CELL), cy: Math.floor((shell.exit.y - 1) / CELL) };
  // the way in stays clear: the two cells either side of the exit, two rows deep
  const exitClear = (cx: number, cy: number): boolean => (cx === door.cx || cx === door.cx - 1) && cy >= door.cy - 2;
  const hh = householdOf(w, lot);
  const business = lot?.kind === 'shop' ? w.businesses[lot.refId] : undefined;
  const stock = Math.min(1, Math.max(0, (business?.capital ?? 0) / 40000));
  const extra = rand(w.seed, 'furnish', id, 'extra');
  const out: FurnishedPiece[] = [];
  const order = shell.areas.map((a, i) => ({ a, i }));
  for (const { a, i } of order) {
    const rules = shell.home ? homeRules(a.name, hh, extra)
      : shell.areas.length === 2 && /Bakery|Butcher|Ice Cream|Bait|Music|Clothing|Dress|Toy|Bank/.test(shell.template)
        ? shopRules(shell.template, i === 0, stock) : officeRules(shell.template, i, stock);
    if (!rules) return null;
    const usable = (cx: number, cy: number): boolean => inArea(a.rect, cx, cy) && clearOfWalls(cx, cy) && !taken[cy * cols + cx];
    // doorways: a cell of the area beside a walkable cell outside it
    const keepClear = (cx: number, cy: number): boolean => {
      if (exitClear(cx, cy)) return true;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
        const nx = cx + dx, ny = cy + dy;
        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue;
        if (!inArea(a.rect, nx, ny) && clearOfWalls(nx, ny)) return true;
      }
      return false;
    };
    const room: WfcRoom = { cols, rows, usable, keepClear, walkable, door, owned: (cx, cy) => inArea(a.rect, cx, cy) && clearOfWalls(cx, cy) };
    let placed: Placed[] | null = null;
    // a household bigger than the room loses its extra beds rather than the room its furniture
    for (let shrink = 0; shrink < 3 && !placed; shrink++) {
      const rs = shrink === 0 ? rules : rules.map((x) => (x.min ?? 0) > 1 ? { ...x, min: Math.max(1, (x.min ?? 0) - shrink), max: Math.max(1, (x.max ?? 9) - shrink) } : x);
      placed = collapse(room, rs, w.seed, `${id}:${a.name}:${shrink}:${hh.couples}${hh.singles}${hh.kids}${hh.teens}`);
    }
    if (!placed) return null;
    for (const p of placed) {
      const rule = rules.find((x) => x.name === p.name)!;
      const solid = rule.solid ?? true;
      if (solid) for (let y = p.cy; y < p.cy + p.h; y++) for (let x = p.cx; x < p.cx + p.w; x++) taken[y * cols + x] = 1;
      const s = INTERIOR_ASSETS[rule.asset];
      const foot = { x: p.cx * CELL, y: p.cy * CELL, w: p.w * CELL, h: p.h * CELL };
      out.push({ key: s.key, x: foot.x + (foot.w - s.w) / 2, y: foot.y + foot.h - s.h, w: s.w, h: s.h, foot, solid, stock: rule.stock ?? false });
    }
  }
  // goods on the counters (not solid, part of the stock)
  const onTop: Partial<Record<string, Asset>> = { Bakery: 'bread', Butcher: 'meat' };
  const good = onTop[shell.template];
  if (good) for (const f of out.filter((f) => f.key === 'interior_counter')) {
    const s = INTERIOR_ASSETS[good];
    out.push({ key: s.key, x: f.foot.x + (f.foot.w - s.w) / 2, y: f.foot.y - 20, w: s.w, h: s.h, foot: { x: f.foot.x, y: f.foot.y + f.foot.h, w: 0, h: 0 }, solid: false, stock: true });
  }
  // sanity: no solid footprint in a wall (the solver works on cells, the walls on pixels)
  for (const f of out) if (f.solid && walls.some((wl) => overlap(f.foot, wl))) return null;
  return out;
}
