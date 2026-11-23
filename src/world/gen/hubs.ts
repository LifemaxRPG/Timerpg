// THE HUB CATALOGUE (WORLD_GEN.md section 3): the hand-made towns and cities, as data. The world seed only decides
// where each hub stands; a hub's plan is authored (the capital's is the planned town `gamma`) and brings pins the
// world must meet: where its river enters and leaves, its rail portals, its highway exits, its waterfront. Routing
// starts from the pins, so authored plans and the generated network meet exactly.

/** The capital's name: the owner's to give. One constant, so swapping it is a one-line change. */
export const CAPITAL_NAME = 'capital';
/** The capital's town plan (src/town/plat.ts via planTown; the test city, CLAUDE.md). */
export const CAPITAL_PLAN = 'gamma';

export type HubType = 'capital' | 'port' | 'river city' | 'mountain town' | 'mill town' | 'farm town';
export const HUB_TYPES: readonly HubType[] = ['capital', 'port', 'river city', 'mountain town', 'mill town', 'farm town'];

export type PinKind = 'river' | 'rail' | 'highway' | 'waterfront';
/** A pin in metres from the hub's centre (x east, z south), and the way out of the plan (a unit vector). */
export interface Pin { kind: PinKind; name: string; dx: number; dz: number; ox: number; oz: number }

export interface HubSpec {
  /** Stable id ("capital", "launch/port"...): the hub's place id is `h:<id>`. */
  key: string;
  type: HubType;
  launch: boolean;
  /** The authored plan's seed, when it has one. */
  plan?: string;
  /** Authored name, when it has one (null: a placeholder name from the registry until the hub is authored). */
  name: string | null;
  /** The plan's pad (the plan plus its farm ring) in metres, and the growth margin kept clear round it. */
  padM: { w: number; h: number };
  growthM: number;
  pins: Pin[];
}

const T = 2.1; // metres a tile
// gamma (planTown('gamma'), plan v2: 965 x 307 tiles, centre (789, 144)); pins read from the plan and checked by
// src/world/gen/gen.test.ts against planTown and townRail: the river column at x 706 runs north-south through the
// town; Main Street's ends (x 14 and 951, its middle line y 142: the road's y 141 and its 2 tiles) are where the highways
// leave west and east; the main line's portals
// are on the farm ring's edge, east (1063, 49.5) and west (-99, 40.2).
const G = { cx: 789, cy: 144 };
const gp = (kind: PinKind, name: string, x: number, y: number, ox: number, oz: number): Pin => ({ kind, name, dx: Math.round((x - G.cx) * T), dz: Math.round((y - G.cy) * T), ox, oz });
export const GAMMA_PINS: Pin[] = [
  gp('river', 'river north', 708, 0, 0, -1),
  gp('river', 'river south', 705, 307, 0, 1),
  gp('highway', 'west', 14, 142, -1, 0),
  gp('highway', 'east', 951, 142, 1, 0),
  gp('rail', 'west', -99, 40.2, -1, 0),
  gp('rail', 'east', 1063, 49.5, 1, 0),
];
export const GAMMA_PLAN_TILES = { w: 965, h: 307, cx: G.cx, cy: G.cy, ring: 100 };

/** A hub not yet authored: a generic 3 km pad with highway and rail pins on all four sides (and a waterfront for ports). */
function generic(key: string, type: HubType, launch: boolean): HubSpec {
  const pins: Pin[] = [];
  for (const [ox, oz, n] of [[1, 0, 'east'], [-1, 0, 'west'], [0, 1, 'south'], [0, -1, 'north']] as const) {
    pins.push({ kind: 'highway', name: n, dx: ox * 1400, dz: oz * 1400, ox, oz });
    pins.push({ kind: 'rail', name: n, dx: ox * 1500 + oz * 300, dz: oz * 1500 + ox * 300, ox, oz });
  }
  return { key, type, launch, name: null, padM: { w: 3000, h: 3000 }, growthM: 2000, pins };
}

export const CAPITAL: HubSpec = {
  key: 'capital', type: 'capital', launch: true, plan: CAPITAL_PLAN, name: CAPITAL_NAME,
  padM: { w: (GAMMA_PLAN_TILES.w + 2 * GAMMA_PLAN_TILES.ring) * T, h: (GAMMA_PLAN_TILES.h + 2 * GAMMA_PLAN_TILES.ring) * T }, growthM: 2000, pins: GAMMA_PINS,
};

/** The launch hubs after the capital, in placement order (WORLD_GEN.md 3, "Order and separation"). */
export const LAUNCH: HubSpec[] = [
  generic('launch/port', 'port', true),
  generic('launch/river city', 'river city', true),
  generic('launch/mountain town', 'mountain town', true),
  generic('launch/mill town', 'mill town', true),
  generic('launch/farm town', 'farm town', true),
  generic('launch/port2', 'port', true),
];

/** Later hubs (7-22) are made as they are placed: `later/<n>` of a type. */
export const laterHub = (n: number, type: HubType): HubSpec => generic(`later/${n}`, type, false);
/** Hubs in all at the planet's full build-out (WORLD_GEN.md: 20-25). */
export const HUB_COUNT = 22;

/** The planet's world seed (the baked one; kotlin-street/tools/bake-world.ts). */
export const PLANET_SEED = 'alpha';
