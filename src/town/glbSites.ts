import { rand } from '../sim/rng';
import { archetypeOf } from '../sim/archetype';
import { townFounded } from '../world/names';
import { isCountySeat } from './plat';
import { GLB_LANDMARKS, glbLandmarkTiles, type GlbLandmarkKind } from './glbLandmarks';
import { POPULATION_BY, POPULATION_FROM, populationIn } from './population';
import { chapel, stripFace, townHall } from './downtown';
import { planFarms, roadRect } from './farms';
import { developedRoads, type Lot } from './layout';
import type { Candidate, TownPlan } from './plan';
import type { Rect } from './kinds';

// Where and when a town has its HAND-MADE LANDMARK MODELS (src/town/glbLandmarks.ts, kotlin-street/docs/LANDMARKS_GLB.md):
// which of them the town earns (its size, its kind, its railroad), on which plot, and the years each stands (opened,
// closed, reused, pulled down). Pure of the seed's plan and its natural history: the same town and year always give the
// same answer.
//
// The plan is finished before this runs (no lot moves for a landmark): a site takes free ground beside a street, or plots
// the town never builds on by the game's present (unused candidates: woodland plots at the town's edges), never a plot
// anyone lives or trades on. Each site fronts one street (its plot a tile off the road, as every plot's, the model's lot
// front on the building line), keeps clear of every street, the river, the plaza, the landmarks, the town hall and chapel,
// the farms, the railroad (when known) and each other, and keeps the frontage rule of the downtown (src/town/downtown.ts
// stripFace): a block face of the old core is a street wall at the pavement, so a model with its own car park in front
// (the gas stations, the motel, the drive-in, the clubs, the laundromat, the bowling alley) never stands beside one; the
// Gazette, a street-wall building, never beside a post-war strip. A plot may hold one model after another (a Mirage
// pulled down for a Pulse, a drive-in's lot redeveloped), never two at once.
//
// Plots are in tiles like the stores' (STORES.md): along the street a tile is 1.24 m (the art's scale: the row export keeps
// a building at its art size), back from it 2.1 m (the position scale), so a model's plot is glbLandmarkTiles(kind).

/** Each simulated person stands for this many inhabitants (the town sizes the models are judged by: LANDMARKS_GLB.md). */
export const INHABITANTS_PER_PERSON = 36;

/** The town's inhabitants in [year] (its natural history, src/town/population.ts). */
export const townPeople = (seed: string, year: number): number => populationIn(seed, year) * INHABITANTS_PER_PERSON;

/** The most inhabitants the town has in any year of its natural history. */
export function peakPeople(seed: string): number {
  let m = 0;
  for (let y = POPULATION_FROM; y <= POPULATION_BY; y++) m = Math.max(m, townPeople(seed, y));
  return m;
}

/** A town of 3,000 inhabitants and more (at its peak) on a railroad has the hand-made depot; smaller ones keep the halt. */
export const PREMIER_DEPOT_PEOPLE = 3000;
export const premierDepot = (seed: string): boolean => peakPeople(seed) >= PREMIER_DEPOT_PEOPLE;

/** One club a town at a time, and only in towns this big. */
export const CLUB_PEOPLE = 8000;

export type GlbLotType = 'downtown' | 'corner' | 'highway' | 'civic' | 'edge' | 'house';
const LOT_TYPE: Record<GlbLandmarkKind, GlbLotType> = {
  landmark_glb_gazette_office: 'downtown',
  landmark_glb_coin_laundromat: 'downtown',
  landmark_glb_mirage_nightclub: 'corner',
  landmark_glb_pulse_nightclub: 'corner',
  landmark_glb_foundry_nightclub: 'edge',
  landmark_glb_foundry_factory: 'edge',
  landmark_glb_funeral_home: 'house',
  // the strip centre: a post-war highway or strip plot with its own car park in front
  landmark_glb_reel_time_video_1984: 'highway',
  landmark_glb_reel_time_video_1996: 'highway',
  landmark_glb_reel_time_video_2011: 'highway',
  landmark_glb_reel_time_video_2020: 'highway',
  landmark_glb_water_tower: 'civic',
  landmark_glb_feed_mill: 'edge',
  landmark_glb_modern_gas_station: 'highway',
  landmark_glb_main_street_service_station: 'highway',
  landmark_glb_junction_diner: 'highway',
  landmark_glb_starling_lanes: 'highway',
  landmark_glb_starlight_drive_in: 'highway',
  landmark_glb_roadside_motel: 'highway',
  // (the depot is the railroad's: src/town/rail.ts puts it on the depot's straight stretch)
  landmark_glb_premier_railroad_depot: 'civic',
  landmark_glb_county_courthouse_1894: 'civic',
  landmark_glb_county_courthouse_1962: 'civic',
  landmark_glb_county_courthouse_1988: 'civic',
  landmark_glb_county_courthouse_2008: 'civic',
  landmark_glb_radio_station: 'edge',
};
/** Street-wall buildings: at the pavement in a block face of the old core, never beside a strip of car parks. */
const WALL: ReadonlySet<GlbLandmarkKind> = new Set(['landmark_glb_gazette_office']);

/** The strip centre's four states on its one plot, in date order (the model files share shell, lot, origin and doors). */
export const VIDEO_STATES: readonly GlbLandmarkKind[] = [
  'landmark_glb_reel_time_video_1984', 'landmark_glb_reel_time_video_1996', 'landmark_glb_reel_time_video_2011', 'landmark_glb_reel_time_video_2020',
];
/** Towns of this many inhabitants (at the peak) have the strip centre. */
export const VIDEO_PEOPLE = 2500;
const isVideo = (kind: GlbLandmarkKind): boolean => VIDEO_STATES.includes(kind);

/** One business's time on a site: opened, closed (still standing, dark, or reused), pulled down. */
export interface GlbTenure {
  kind: GlbLandmarkKind;
  name: string;
  opened: number;
  /** The business ends (the building stands on, dark, unless reused). */
  closed?: number;
  /** Reused from [reuse.from] as [reuse.as] ([hide]: the model's nodes not drawn then, the pumps of a repair garage). */
  reuse?: { from: number; as: string; hide?: string[] };
  /** Pulled down (or, for the works, turned into the club that follows it on its plot): from this year it is gone. */
  gone?: number;
  /** Which of the model's painted variants (the works' name: [FACTORY_NAMES]; the funeral home's family: [FUNERAL_NAMES]). */
  variant?: number;
  /** The strip centre (its first state): the year the video store opens in it (the 1984 state stands from [opened], the strip's building). */
  shopOpened?: number;
  /** The funeral home: the year its house was built (data; the plot is open ground before [opened], the conversion). */
  houseBuilt?: number;
  /** The funeral home's families, oldest first (data only: the painted sign keeps the founders' name). */
  owners?: { from: number; family: string }[];
}

/** The works' names, in the order of the model's painted variants (tools/landmarks/foundry-factory/make_art.py VARIANTS). */
export const FACTORY_NAMES = ['Hargrove Iron Works', 'Whitcomb Foundry & Machine Co.', 'Ostrander Iron Works', 'Pelham Foundry & Machine Co.'];

/** The strip centre keeps this many tiles from a coin laundry's plot (its own neighbour bay is a laundry). */
const LAUNDRY_APART = 10;

/** The funeral home's painted family names, in the order of the model's variants (tools/landmarks/funeral-home/make_variants.py SURNAMES). */
export const FUNERAL_FAMILIES = ['Hollis', 'Pruitt', 'Marlow', 'Whelan', 'Ashby', 'Kessler'];
export const FUNERAL_NAMES = FUNERAL_FAMILIES.map((f) => `${f} & Sons Funeral Home`);

export interface GlbSite {
  id: string;
  /** The plot, plan tiles. */
  rect: Rect;
  /** The street it fronts (index into plan.roads) and where that street is from the plot (N: above it, ...). */
  road: number;
  face: 'N' | 'S' | 'E' | 'W';
  lotType: GlbLotType;
  /** Its businesses, oldest first, never two at once. */
  tenures: GlbTenure[];
}

export type GlbState = 'open' | 'closed' | 'reused';
/** What stands on [site] in [year]: the tenure, its state (and a reuse's name and hidden nodes), or nothing. */
export function glbStandingOn(site: GlbSite, year: number): { tenure: GlbTenure; state: GlbState; name: string; hide: string[] } | undefined {
  const t = site.tenures.find((q) => year >= q.opened && (q.gone === undefined || year < q.gone));
  if (!t) return undefined;
  if (t.closed === undefined || year < t.closed) return { tenure: t, state: 'open', name: t.name, hide: [] };
  if (t.reuse && year >= t.reuse.from) return { tenure: t, state: 'reused', name: t.reuse.as, hide: t.reuse.hide ?? [] };
  return { tenure: t, state: 'closed', name: t.name, hide: [] };
}

/** What the generator needs besides the plan: the plots in use by the game's present, the railroad, more ground to keep off. */
export interface GlbInput {
  /** Every lot the town ever builds (its 2025 properties: slot and the year built). */
  lots: readonly Pick<Lot, 'slot' | 'builtYear'>[];
  /** The town's railroad, if known: its tracks' centre lines and its depot's, siding's and elevator's ground (plan tiles). */
  rail?: { tracks: { x: number; y: number }[][]; rects: Rect[]; elevator?: Rect | null } | null;
  /** More ground to keep off (the 3D town's yards past the plots), plan tiles. */
  keep?: readonly Rect[];
}

const hit = (a: Rect, b: Rect, pad = 0): boolean =>
  a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;

/** The distance from point (px, py) to the segment a-b. */
function segDist(px: number, py: number, a: { x: number; y: number }, b: { x: number; y: number }): number {
  const dx = b.x - a.x, dy = b.y - a.y, l = dx * dx + dy * dy;
  const t = l > 0 ? Math.max(0, Math.min(1, ((px - a.x) * dx + (py - a.y) * dy) / l)) : 0;
  return Math.hypot(px - a.x - t * dx, py - a.y - t * dy);
}
/** The distance from rect [r] to the polyline [pts] (0 if it crosses). */
function rectLineDist(r: Rect, pts: readonly { x: number; y: number }[]): number {
  let d = Infinity;
  for (let i = 0; i + 1 < pts.length; i++) {
    const a = pts[i]!, b = pts[i + 1]!;
    // sample the segment (tracks are many short pieces) against the rect's nearest point
    const n = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y)));
    for (let k = 0; k <= n; k++) {
      const x = a.x + ((b.x - a.x) * k) / n, y = a.y + ((b.y - a.y) * k) / n;
      const dx = Math.max(r.x - x, 0, x - (r.x + r.w)), dy = Math.max(r.y - y, 0, y - (r.y + r.h));
      d = Math.min(d, Math.hypot(dx, dy));
    }
  }
  return d;
}

// ------------------------------------------------------------------------------------------------ who earns what, when

/** The businesses the town has (each with its years), before they find plots: deterministic in the seed. */
export function glbTenures(seed: string): GlbTenure[] {
  const R = (...k: (string | number)[]): number => rand(seed, 'glb', ...k);
  const yr = (lo: number, hi: number, ...k: (string | number)[]): number => lo + Math.floor(R(...k) * (hi - lo + 1));
  const peak = peakPeople(seed);
  const type = archetypeOf(seed).id;
  const out: GlbTenure[] = [];
  const add = (kind: GlbLandmarkKind, t: Omit<GlbTenure, 'kind' | 'name'>): void => { out.push({ kind, name: GLB_LANDMARKS[kind].name, ...t }); };
  const open = (kind: GlbLandmarkKind, cap = POPULATION_BY): number => {
    const { from, to } = GLB_LANDMARKS[kind].era;
    return yr(from, Math.min(to, cap), 'open', kind);
  };

  // every town's water tower (built 1920-65, repainted, never pulled down)
  add('landmark_glb_water_tower', { opened: open('landmark_glb_water_tower') });
  // the weekly paper (built 1905-40) in a town of 1,500 and more; many fold 2008-20 (the building stands on, dark)
  if (peak >= 1500 && R('has', 'gazette') < 0.85) {
    const closed = R('closes', 'gazette') < 0.5 ? yr(2008, 2020, 'closed', 'gazette') : undefined;
    add('landmark_glb_gazette_office', { opened: open('landmark_glb_gazette_office'), ...(closed ? { closed } : {}) });
  }
  // the feed mill (1920-60): every farm town, most small towns; closes 1972-95, then storage (or a brewery) or pulled down
  if (R('has', 'feed') < (type === 'farm' ? 1 : peak < 6000 ? 0.7 : 0.4)) {
    const closed = yr(1972, 1995, 'closed', 'feed');
    const k = R('after', 'feed');
    const after = k < 0.2 ? { gone: closed + yr(5, 15, 'gone', 'feed') }
      : k < 0.55 && closed + 2 <= 2018 ? { reuse: { from: Math.max(closed + 2, yr(2008, 2018, 'brew', 'feed')), as: 'Hearthside Brewing Co.' } }
      : { reuse: { from: closed + 2, as: 'Hearthside Storage' } };
    add('landmark_glb_feed_mill', { opened: open('landmark_glb_feed_mill'), closed, ...after });
  }
  // the Main Street service station (1950-69): stops selling gas 1972-85, then a repair garage (pumps gone) or shut and pulled down
  if (peak >= 1500 && R('has', 'station') < 0.85) {
    const opened = open('landmark_glb_main_street_service_station');
    const closed = Math.max(opened + 4, yr(1972, 1985, 'closed', 'station'));
    if (R('after', 'station') < 0.55) {
      const gone = R('gone?', 'station') < 0.5 ? yr(1995, 2015, 'gone', 'station') : undefined;
      add('landmark_glb_main_street_service_station', { opened, closed, reuse: { from: closed, as: 'Main Street Auto Repair', hide: ['pump_01', 'pump_02', 'roadside_price_board', 'roadside_fuel_sign'] }, ...(gone ? { gone } : {}) });
    } else add('landmark_glb_main_street_service_station', { opened, closed, gone: closed + yr(2, 8, 'gone', 'station') });
  }
  // the highway's gas station and store (2005 on)
  if (peak >= 2500 && R('has', 'gas') < 0.8) add('landmark_glb_modern_gas_station', { opened: open('landmark_glb_modern_gas_station', POPULATION_BY - 3) });
  // the diner (1940-65): most close 1978-2005 and are pulled down; some run on as a fixture
  if (peak >= 2000 && R('has', 'diner') < 0.8) {
    const opened = open('landmark_glb_junction_diner');
    if (R('closes', 'diner') < 0.65) { const closed = yr(1978, 2005, 'closed', 'diner'); add('landmark_glb_junction_diner', { opened, closed, gone: closed + yr(1, 8, 'gone', 'diner') }); }
    else add('landmark_glb_junction_diner', { opened });
  }
  // the bowling alley (1960s) in a bigger town; half close 1995-2015
  if (peak >= 4500 && R('has', 'bowl') < 0.7) {
    const opened = open('landmark_glb_starling_lanes');
    if (R('closes', 'bowl') < 0.5) { const closed = yr(1995, 2015, 'closed', 'bowl'); add('landmark_glb_starling_lanes', { opened, closed, gone: closed + yr(1, 5, 'gone', 'bowl') }); }
    else add('landmark_glb_starling_lanes', { opened });
  }
  // the drive-in (opens 1950-66), shut 1975-88 and its lot cleared within a few years
  if (peak >= 2500 && R('has', 'drivein') < 0.65) {
    const closed = yr(1975, 1988, 'closed', 'drivein');
    add('landmark_glb_starlight_drive_in', { opened: open('landmark_glb_starlight_drive_in'), closed, gone: closed + yr(0, 4, 'gone', 'drivein') });
  }
  // the motor court (1950-69): an extended-stay from the 2000s, or shut 1990-2010 and pulled down
  if (peak >= 2500 && R('has', 'motel') < 0.7) {
    const opened = open('landmark_glb_roadside_motel');
    if (R('after', 'motel') < 0.5) { const closed = yr(1998, 2008, 'closed', 'motel'); add('landmark_glb_roadside_motel', { opened, closed, reuse: { from: closed, as: 'Sundown Extended Stay' } }); }
    else { const closed = yr(1990, 2010, 'closed', 'motel'); add('landmark_glb_roadside_motel', { opened, closed, gone: closed + yr(2, 10, 'gone', 'motel') }); }
  }
  // the coin laundry (1960-75) in a town of 2,000 and more; most run to this day, older-looking (the rest shut, dark)
  if (peak >= 2000 && R('has', 'laundry') < 0.75) {
    const closed = R('closes', 'laundry') < 0.3 ? yr(1995, 2020, 'closed', 'laundry') : undefined;
    add('landmark_glb_coin_laundromat', { opened: open('landmark_glb_coin_laundromat'), ...(closed ? { closed } : {}) });
  }
  // one club at a time: Club Mirage in a town of 8,000 in the 1980s (shut in the 1990s), then a Pulse (2020s, on the
  // Mirage's plot when there was one) or The Foundry (2010s-20s, an old works turned club: likelier in a town with industry)
  const big = (lo: number, hi: number): number[] => { const ys: number[] = []; for (let y = lo; y <= Math.min(hi, POPULATION_BY); y++) if (townPeople(seed, y) >= CLUB_PEOPLE) ys.push(y); return ys; };
  const y80 = big(1980, 1989);
  let mirage: GlbTenure | undefined;
  if (y80.length) {
    const opened = y80[Math.floor(R('open', 'mirage') * y80.length)]!;
    const closed = Math.max(opened + 3, yr(1990, 1997, 'closed', 'mirage'));
    mirage = { kind: 'landmark_glb_mirage_nightclub', name: GLB_LANDMARKS.landmark_glb_mirage_nightclub.name, opened, closed, gone: closed + yr(3, 10, 'gone', 'mirage') };
    out.push(mirage);
  }
  // the town's iron works (a town with industry likelier): opens in its early industrial years (1900-45, a few years
  // after the town's founding), shut when the small-town foundries closed (1970-2005), standing dark after; when the town
  // reaches the club's size in 2010-29, at least five years after it shut, it becomes The Foundry on the same plot
  const industry = type === 'mill' || type === 'mining' ? 0.75 : type === 'harbor' ? 0.5 : 0.25;
  let works: GlbTenure | undefined;
  if (R('which', 'club') < industry) {
    const opened = Math.max(yr(1900, 1945, 'open', 'works'), Math.min(1945, townFounded(seed) + 5));
    const variant = Math.floor(R('name', 'works') * FACTORY_NAMES.length);
    works = { kind: 'landmark_glb_foundry_factory', name: FACTORY_NAMES[variant]!, opened, closed: Math.max(opened + 20, yr(1970, 2005, 'closed', 'works')), variant };
    out.push(works);
  }
  const later = works ? big(Math.max(2010, works.closed! + 5), 2029) : big(2020, 2029);
  if (later.length) {
    const kind: GlbLandmarkKind = works ? 'landmark_glb_foundry_nightclub' : 'landmark_glb_pulse_nightclub';
    const opened = later[Math.floor(R('open', 'club2') * later.length)]!;
    // (a Mirage still standing is pulled down the year before)
    if (mirage && mirage.gone! >= opened) mirage.gone = opened - 1;
    if (mirage && mirage.closed! >= opened - 1) mirage.closed = opened - 2;
    if (!mirage || mirage.closed! > mirage.opened) {
      out.push({ kind, name: GLB_LANDMARKS[kind].name, opened });
      // (the works become the club: the works' model stands until the club opens)
      if (works) works.gone = opened;
    }
  }
  // the funeral home, in a town of 1,500 and more: an early-1900s house on a house lot near the core, converted 1935-60 (from the
  // seed) and a funeral home ever since; the family that runs it may change later (data only)
  if (peak >= 1500) {
    const opened = open('landmark_glb_funeral_home');
    const hi = Math.min(1935, opened - 1);
    const houseBuilt = yr(Math.min(Math.max(1910, townFounded(seed) + 3), hi), hi, 'house', 'funeral');
    const variant = Math.floor(R('name', 'funeral') * FUNERAL_NAMES.length);
    const owners = [{ from: opened, family: FUNERAL_FAMILIES[variant]! }];
    if (R('sold', 'funeral') < 0.5) {
      const from = yr(1975, 2012, 'sold', 'funeral');
      const next = (variant + 1 + Math.floor(R('buyer', 'funeral') * (FUNERAL_FAMILIES.length - 1))) % FUNERAL_FAMILIES.length;
      owners.push({ from, family: FUNERAL_FAMILIES[next]! });
    }
    out.push({ kind: 'landmark_glb_funeral_home', name: FUNERAL_NAMES[variant]!, opened, variant, houseBuilt, owners });
  }
  // the strip centre, in a town of 2,500 and more: built 1972-82 (never before the town's early years), the video store opens 1980-86 in
  // its second bay (the 1984 state stands from the strip's building to the expansion), takes the next bay 1990-97, closes down
  // 2008-13 (the 2011 state for a year or two) and the bay is reused as a phone repair shop (the 2020 state) to the present
  if (peak >= VIDEO_PEOPLE) {
    const built = Math.max(yr(1972, 1982, 'built', 'video'), Math.min(1982, townFounded(seed) + 5));
    const shopOpened = yr(Math.max(1980, built), 1986, 'open', 'video');
    const expand = yr(Math.max(1990, shopOpened + 4), 1997, 'expand', 'video');
    const closing = yr(2008, 2013, 'closing', 'video');
    const reuse = closing + yr(1, 2, 'reuse', 'video');
    const at = [built, expand, closing, reuse];
    VIDEO_STATES.forEach((kind, i) => out.push({ kind, name: GLB_LANDMARKS[kind].name, opened: at[i]!, ...(i < 3 ? { gone: at[i + 1]! } : {}), ...(i === 0 ? { shopOpened } : {}) }));
  }
  // the radio station (opens 1946-60) in a town of 2,500 and more; from the 1990s-2000s run automated, or offices
  if (peak >= RADIO_PEOPLE && R('has', 'radio') < 0.7) {
    const opened = yr(1946, 1960, 'open', 'radio');
    const variant = Math.floor(R('name', 'radio') * RADIO_NAMES.length);
    const closed = yr(1990, 2006, 'closed', 'radio');
    const reuse = R('after', 'radio') < 0.5 ? { from: closed, as: `${RADIO_NAMES[variant]} (automated)` } : { from: closed, as: 'Radio Building Offices' };
    out.push({ kind: 'landmark_glb_radio_station', name: RADIO_NAMES[variant]!, opened, closed, reuse, variant });
  }
  // the county seat's courthouse on its square: built 1885-1905, modernised in the 1950s (the annex), neglected from the
  // 1970s (the clock stopped), restored about 2000; four states on one plot (src/town/plat.ts isCountySeat)
  if (isCountySeat(seed)) {
    const at = [Math.max(yr(1885, 1905, 'open', 'court'), Math.min(1905, townFounded(seed) + 8)), yr(1951, 1962, 'modern', 'court'), yr(1972, 1982, 'neglect', 'court'), yr(1996, 2006, 'restored', 'court')];
    COURTHOUSE_STATES.forEach((kind, i) => out.push({ kind, name: 'County Courthouse', opened: at[i]!, ...(i < 3 ? { gone: at[i + 1]! } : {}) }));
  }
  return out.sort((a, b) => a.opened - b.opened || (a.kind < b.kind ? -1 : 1));
}

/** Towns of this many inhabitants (at the peak) may have the radio station. */
export const RADIO_PEOPLE = 2500;
/** The radio station's painted names, in the order of the model's variants (tools/landmarks/radio-station/make_variants.py). */
export const RADIO_NAMES = ['Valley Radio', 'Prairie 1340 AM', 'Riverbend Radio', 'Hometown 1230 AM', 'Cedar Valley Radio', 'Crossroads 1450 AM'];
/** The courthouse's four states on its one plot, in date order (shell, lot, origin and doors shared). */
export const COURTHOUSE_STATES: readonly GlbLandmarkKind[] = [
  'landmark_glb_county_courthouse_1894', 'landmark_glb_county_courthouse_1962', 'landmark_glb_county_courthouse_1988', 'landmark_glb_county_courthouse_2008',
];

// ------------------------------------------------------------------------------------------------ where

/** Every site the town's models stand on over the years (deterministic in the seed's plan and [input]). */
export function glbSites(plan: TownPlan, input: GlbInput): GlbSite[] {
  const seed = plan.seed;
  const used = new Set(input.lots.map((l) => l.slot));
  const usedC = plan.candidates.filter((c) => used.has(c.id));
  const W = plan.width, H = plan.height;
  const cx = plan.plaza.x + plan.plaza.w / 2, cy = plan.plaza.y + plan.plaza.h / 2;
  // ground to keep off: the plaza, the landmarks, the town hall and chapel, the farms, the railroad, the 3D yards
  // (a house lot may stand against a landmark's plot: [lm] is the pad a landmark's plot has for it, 0)
  const fixed: { r: Rect; pad: number; lm?: boolean; civic?: boolean }[] = [
    { r: plan.plaza, pad: 2, civic: true }, ...plan.landmarks.map((r) => ({ r, pad: 1, lm: true })),
    ...[townHall(plan)?.rect, chapel(plan)?.rect].filter((r): r is Rect => !!r).map((r) => ({ r, pad: 2, civic: true })),
    ...(input.keep ?? []).map((r) => ({ r, pad: 1 })),
    ...(input.rail?.rects ?? []).map((r) => ({ r, pad: 2 })),
  ];
  const farms = planFarms(plan);
  for (const f of farms.farms) fixed.push({ r: f.rect, pad: 4 });
  // (a field over the town's outer plots is farmed until something is built on it: a model's plot may take one, and the
  // farm export sells it that year: src/town/farms.ts fieldClear)
  for (const r of farms.roads) fixed.push({ r: roadRect(r), pad: 1 });
  const wet = (b: Rect): boolean => {
    if (!plan.water) return false;
    for (let y = Math.max(0, b.y - 2); y < Math.min(H, b.y + b.h + 2); y++)
      for (let x = Math.max(0, b.x - 2); x < Math.min(W, b.x + b.w + 2); x++) if (plan.water.raw[y * W + x]) return true;
    return false;
  };
  // the year each street is built (a model opens on a street that is there)
  const roadYear = new Map<number, number>();
  for (let y = POPULATION_FROM; y <= POPULATION_BY; y++) {
    const lots = input.lots.filter((l) => (l.builtYear ?? POPULATION_FROM) <= y) as Lot[];
    for (const r of developedRoads(plan, lots, y)) { const i = plan.roads.indexOf(r); if (!roadYear.has(i)) roadYear.set(i, y); }
  }
  const shops = usedC.filter((c) => c.kind === 'shop');
  /** The used shop plots on the same side of road [ri] as box [b], within [reach] tiles along it. */
  const faceShops = (b: Rect, ri: number, face: GlbSite['face'], reach: number): Candidate[] => {
    const r = plan.roads[ri]!;
    return shops.filter((s) => {
      if (face === 'N' || face === 'S') {
        const sameSide = face === 'N' ? s.y >= r.y + r.h : s.y + s.h <= r.y;
        return sameSide && Math.abs((face === 'N' ? s.y : s.y + s.h) - (face === 'N' ? b.y : b.y + b.h)) <= 2 && s.x < b.x + b.w + reach && b.x - reach < s.x + s.w;
      }
      const sameSide = face === 'W' ? s.x >= r.x + r.w : s.x + s.w <= r.x;
      return sameSide && Math.abs((face === 'W' ? s.x : s.x + s.w) - (face === 'W' ? b.x : b.x + b.w)) <= 2 && s.y < b.y + b.h + reach && b.y - reach < s.y + s.h;
    });
  };

  const sites: GlbSite[] = [];
  const fits = (b: Rect, ri: number, padLots: number, house = false): boolean => {
    if (b.x < 3 || b.y < 3 || b.x + b.w > W - 3 || b.y + b.h > H - 3) return false;
    for (let i = 0; i < plan.roads.length; i++) if (hit(b, plan.roads[i]!, i === ri ? 0 : 1)) return false;
    if (usedC.some((c) => hit(b, c, padLots))) return false;
    if (fixed.some((f) => hit(b, f.r, house ? (f.lm ? 0 : f.civic ? 1 : f.pad) : f.pad))) return false;
    if (sites.some((s) => hit(b, s.rect, 1))) return false;
    if (input.rail) for (const t of input.rail.tracks) if (rectLineDist(b, t) < 4) return false;
    return !wet(b);
  };

  /** The strip centre's pick among the plots that fit: 0 the best by score, 1 the first along the streets, 2 the last (the search packs the strip). */
  let videoPick = 0;
  const place = (t: GlbTenure): void => {
    const lotType = LOT_TYPE[t.kind];
    const [along, deep] = glbLandmarkTiles(t.kind);
    // first a plot an earlier model has left (a Pulse on the Mirage's, a gas station on the drive-in's cleared lot); the
    // Mirage's plot is kept for the Pulse that follows it
    // The Foundry on its works' plot (held since the works opened)
    if (t.kind === 'landmark_glb_foundry_nightclub') {
      const w = sites.find((q) => q.tenures.some((x) => x.kind === 'landmark_glb_foundry_factory'));
      // (no works plot found: no club either, as the club is that building)
      w?.tenures.push(t);
      return;
    }
    // the strip centre's later states on the first state's plot (one plot through time: the states share shell, lot and doors)
    if (isVideo(t.kind) && t.kind !== VIDEO_STATES[0]) {
      sites.find((q) => q.tenures.some((x) => x.kind === VIDEO_STATES[0]))?.tenures.push(t);
      return;
    }
    const pulse = t.kind === 'landmark_glb_pulse_nightclub';
    // (a house lot is never a pulled-down business's, nor the strip centre's: it has a plot of its own)
    for (const s of lotType === 'house' || isVideo(t.kind) ? [] : [...sites].sort((p, q) => (pulse ? clubFirst(q) - clubFirst(p) : 0))) {
      const last = s.tenures[s.tenures.length - 1]!;
      if (!pulse && last.kind === 'landmark_glb_mirage_nightclub' && pulseLater) continue;
      if (last.kind === 'landmark_glb_foundry_factory') continue;
      if (last.gone === undefined || last.gone > t.opened) continue;
      const vert = s.face === 'E' || s.face === 'W';
      const sa = vert ? s.rect.h : s.rect.w, sd = vert ? s.rect.w : s.rect.h;
      if (along > sa || deep > sd || (lotType === 'downtown') !== (s.lotType === 'downtown')) continue;
      if ((roadYear.get(s.road) ?? 9999) > Math.max(t.opened, POPULATION_FROM) && !plan.roads[s.road]!.main) continue;
      s.tenures.push(t);
      return;
    }
    const wall = WALL.has(t.kind);
    let best: { s: number; b: Rect; ri: number; face: GlbSite['face'] } | undefined;
    plan.roads.forEach((r, ri) => {
      if ((lotType === 'civic' || lotType === 'house') && r.main) return;
      // (the converted house stands from the town's early years: its street was there when the plan begins)
      if (!r.main && (roadYear.get(ri) ?? 9999) > (lotType === 'house' ? POPULATION_FROM : Math.max(t.opened, POPULATION_FROM))) return;
      const sides: GlbSite['face'][] = r.horizontal ? ['N', 'S'] : ['E', 'W'];
      for (const face of sides) {
        const len = r.horizontal ? r.w : r.h;
        for (let o = 0; o + along <= len; o += 1) {
          const b: Rect = r.horizontal
            ? { x: r.x + o, y: face === 'S' ? r.y - 1 - deep : r.y + r.h + 1, w: along, h: deep }
            : { x: face === 'E' ? r.x - 1 - deep : r.x + r.w + 1, y: r.y + o, w: deep, h: along };
          if (!fits(b, ri, wall ? 0 : 1, lotType === 'house')) continue;
          // the frontage rule: a street-wall building only beside the old core's wall, a building behind its own car park
          // never beside one
          const near = faceShops(b, ri, face, 4);
          // (its neighbour bay is a laundry: never right beside a coin laundry)
          if (isVideo(t.kind) && sites.some((q) => q.tenures.some((x) => x.kind === 'landmark_glb_coin_laundromat') && hit(b, q.rect, LAUNDRY_APART))) continue;
          if (lotType === 'house' ? near.length > 0 : wall ? near.some((s) => stripFace(plan, s)) : near.some((s) => !stripFace(plan, s))) continue;
          const mx = b.x + b.w / 2, my = b.y + b.h / 2;
          const d = Math.hypot(mx - cx, (my - cy) * 1.3);
          const jit = rand(seed, 'glb', 'site', t.kind, b.x, b.y) * 4;
          // a corner: a crossing street within 3 tiles of the plot's end
          const corner = plan.roads.some((q, qi) => qi !== ri && q.horizontal !== r.horizontal && hit(b, q, 3));
          const others = sites.filter((s) => s.lotType === 'highway');
          const cluster = others.length ? Math.min(...others.map((s) => Math.hypot(s.rect.x + s.rect.w / 2 - mx, s.rect.y + s.rect.h / 2 - my))) : 0;
          let s: number;
          switch (lotType) {
            case 'downtown': if (d > 120) continue; s = d + (r.main ? 0 : 20) - (wall && near.length ? 25 : 0) + (corner && wall ? 10 : 0); break;
            case 'corner': if (d > 140) continue; s = d * 0.6 + (r.main ? 0 : 15) - (corner ? 20 : 0); break;
            case 'highway': s = -d + cluster * 0.4 + (r.main ? 0 : 60) + (t.kind === 'landmark_glb_main_street_service_station' ? Math.abs(d - 60) * 2 : 0); break;
            // a house lot: near the core but not on it, on an old residential street among houses (never a highway strip)
            case 'house': {
              const homes = usedC.filter((c) => c.kind !== 'shop' && Math.hypot(c.x + c.w / 2 - mx, c.y + c.h / 2 - my) < 14).length;
              s = Math.abs(d - 40) * 0.5 - Math.min(homes, 8) * 3 + (roadYear.get(ri) ?? POPULATION_FROM) - POPULATION_FROM;
              break;
            }
            case 'civic': s = Math.abs(d - 45) + (usedC.some((c) => hit(b, c, 3)) ? 30 : 0); break;
            default: {
              const el = input.rail?.elevator;
              s = el ? Math.hypot(el.x + el.w / 2 - mx, el.y + el.h / 2 - my) : -d;
            }
          }
          s += jit;
          if (isVideo(t.kind) && videoPick) s = (r.main ? 0 : 1e4) + (videoPick === 1 ? 1 : -1) * (b.x + b.y);
          if (!best || s < best.s) best = { s, b, ri, face };
        }
      }
    });
    if (!best) return;
    sites.push({ id: `glb:${t.kind.slice('landmark_glb_'.length)}`, rect: best.b, road: best.ri, face: best.face, lotType, tenures: [t] });
  };
  const all = glbTenures(seed);
  const pulseLater = all.some((t) => t.kind === 'landmark_glb_pulse_nightclub');
  const clubFirst = (s: GlbSite): number => (s.tenures.some((t) => t.kind === 'landmark_glb_mirage_nightclub') ? 1 : 0);
  // The funeral home, then the strip centre, last: they take what ground is left and never move another model's plot. A town with no
  // ground left for one of them gives it the first pick instead, and the big optional models (the feed mill, the bowling alley, the
  // motel: each subset tried) go after the rest; the strip centre also tries the ends of the free ground (packing the strip tighter).
  // Of those orders the one that places the funeral home, then the most other models, then the strip centre wins, so the strip
  // centre never costs a town more models than it gains (LANDMARKS_GLB.md "The funeral home", "Reel Time Video").
  const fh = all.filter((t) => t.kind === 'landmark_glb_funeral_home'), vid = all.filter((t) => isVideo(t.kind));
  const rest = all.filter((t) => t.kind !== 'landmark_glb_funeral_home' && !isVideo(t.kind));
  const placedFh = (): boolean => !fh.length || sites.some((q) => q.tenures.includes(fh[0]!));
  const placedVid = (): boolean => !vid.length || sites.some((q) => q.tenures.includes(vid[0]!));
  rest.forEach(place); fh.forEach(place); vid.forEach(place);
  if (!placedFh() || !placedVid()) {
    const late = ['landmark_glb_feed_mill', 'landmark_glb_starling_lanes', 'landmark_glb_roadside_motel'];
    let best: { sites: GlbSite[]; score: number[] } | undefined;
    for (const fhFirst of [false, true]) for (const vidFirst of [false, true]) for (const pick of [0, 1, 2]) for (let m = 0; m < 1 << late.length; m++) {
      if (pick && !vidFirst) continue;
      const set = new Set(late.filter((_, i) => m & (1 << i)));
      sites.length = 0; videoPick = pick;
      if (fhFirst) fh.forEach(place);
      if (vidFirst) vid.forEach(place);
      rest.filter((t) => !set.has(t.kind)).forEach(place); rest.filter((t) => set.has(t.kind)).forEach(place);
      if (!fhFirst) fh.forEach(place);
      if (!vidFirst) vid.forEach(place);
      const score = [placedFh() ? 1 : 0, sites.reduce((c, q) => c + q.tenures.filter((t) => t.kind !== 'landmark_glb_funeral_home' && !isVideo(t.kind)).length, 0), placedVid() ? 1 : 0];
      if (!best || score[0]! > best.score[0]! || (score[0] === best.score[0] && (score[1]! > best.score[1]! || (score[1] === best.score[1] && score[2]! > best.score[2]!)))) best = { sites: [...sites], score };
    }
    sites.length = 0; videoPick = 0;
    if (best) sites.push(...best.sites);
  }
  return sites;
}

/** The kinds of the tenures [glbTenures] gives that found no plot in [sites] (for reports and tests). */
export function glbUnplaced(seed: string, sites: readonly GlbSite[]): GlbLandmarkKind[] {
  const placed = new Set(sites.flatMap((s) => s.tenures.map((t) => `${t.kind}@${t.opened}`)));
  return glbTenures(seed).filter((t) => !placed.has(`${t.kind}@${t.opened}`)).map((t) => t.kind);
}
