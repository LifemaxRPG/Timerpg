import { rand } from '../sim/rng';
import { townName } from '../world/names';
import { firstYearReaching } from './population';

// THE STORES: where the town buys its groceries, through the decades (kotlin-street/docs/STORES.md).
//
//  - Every town has a GENERAL STORE on its main street, standing since long before the game begins: a wooden false-front
//    or a brick store with a covered porch and a bench, a hand-painted sign, goods in the windows (barrels, crates, an
//    ice chest; a gas pump out front in the early decades). It may change hands (and its name) and it hangs on until a
//    superstore comes to town, then closes: boarded up, and some years later reused (an antiques shop, a church, a cafe,
//    a museum...) or left boarded for good.
//  - A town that grows to MARKET_POPULATION before 1970 gets a MARKET downtown, on or near the main street: a modest
//    1950s-60s supermarket (big plate-glass front, a lit sign, a small lot) on a block-sized plot.
//  - The supermarket grows with the town: once it reaches SUPERMARKET_POPULATION from 1970 on (or reaches
//    MARKET_POPULATION only after 1970), the store moves out to the main road at the edge of town, into a bigger
//    1970s-80s SUPERMARKET (a long flat-roofed glass front, a car park with cart corrals), under a new name. The old
//    market downtown closes and is reused (a thrift store, a church, a gym...) or left empty. So each era has its own
//    building, and time travel shows the step from one to the next.
//  - A town that grows to SUPERSTORE_POPULATION from 1990 on gets a SUPERSTORE at its edge, beside the main road out of
//    town: a huge big-box store (about 118 x 95 m) with a garden centre, loading docks and a car park for hundreds. A
//    year or three later the supermarket closes (and the market too, if it never moved), then the general store.
//
// Everything is a pure function of the seed (the town's natural population history, src/town/population.ts): which
// sites the plan reserves (storeNeeds), and each store's life (storeHistory: opened, renamed, expanded, closed, reused).
// Every name is made up; real chains never appear (src/town/stores.test.ts keeps a blocklist).

/** Living people (natural history) a town needs to get its first supermarket. */
export const MARKET_POPULATION = 125;
/** Living people (from EXPAND_FROM) for the supermarket to move out to the bigger 1970s-80s store. */
export const SUPERMARKET_POPULATION = 160;
/** Living people (from SUPERSTORE_FROM) for a superstore to come to town. */
export const SUPERSTORE_POPULATION = 215;
/** Markets open before this; from it on a new supermarket is the bigger 1970s-80s store at the edge of town. */
export const EXPAND_FROM = 1970;
/** The big-box era. */
export const SUPERSTORE_FROM = 1990;

/** The four sites a town can have (landmark kinds in src/town/landmarks.ts), one building (era) each. */
export type StoreSite = 'generalstore' | 'market' | 'supermarket' | 'superstore';
export const STORE_SITES: readonly StoreSite[] = ['generalstore', 'market', 'supermarket', 'superstore'];
export const isStoreSite = (id: string): id is StoreSite => (STORE_SITES as readonly string[]).includes(id);

/** The building standing on a site: the general store, the 1950s-60s market, the 1970s-80s supermarket, the big box. */
export type StoreVariant = 'general' | 'market' | 'supermarket' | 'superstore';
/** The general store's build: a wooden false-front or a brick front. */
export type StoreStyle = 'frontier' | 'brick' | 'modern';
export type StoreStatus = 'open' | 'closed' | 'reused';
export type StoreReuse = 'antiques' | 'church' | 'cafe' | 'museum' | 'feed' | 'thrift' | 'gym' | 'flea' | 'outlet' | 'community';

/** Plot sizes in tiles (along the street x deep), best first. */
export const STORE_SIZES: Record<StoreSite, readonly [number, number][]> = {
  generalstore: [[12, 15], [14, 13], [10, 18]],
  // a block-sized plot downtown (the blocks are 20-30 tiles wide)
  market: [[22, 30], [26, 26], [20, 34], [30, 22]],
  supermarket: [[52, 40], [60, 36]],
  superstore: [[128, 98]],
};
/**
 * Metres per plot tile in the 3D town (kotlin-street/tools/town-graph.ts): along the street a tile is the art's 26.29
 * street units, back from the street a tile of position is 44.5 (a figure is 36 units for 1.7 m).
 */
export const ALONG_M = (32 * 184 / 224) / (36 / 1.7);
export const DEEP_M = 44.5 / (36 / 1.7);

// ------------------------------------------------------------------------------------------------ which sites
export interface StoreNeeds {
  /** The year the downtown market opens (the natural population reaches MARKET_POPULATION before EXPAND_FROM). */
  market?: number;
  /** The year the supermarket at the edge of town opens (the market moves out, or the town's first). */
  supermarket?: number;
  /** The year the superstore opens. */
  superstore?: number;
}

/** Which store sites the town's plan must reserve, and the years they open (all by POPULATION_BY; the general store always). */
export function storeNeeds(seed: string): StoreNeeds {
  const first = firstYearReaching(seed, MARKET_POPULATION);
  if (first === undefined) return {};
  const superstore = firstYearReaching(seed, SUPERSTORE_POPULATION, SUPERSTORE_FROM);
  const market = first < EXPAND_FROM ? first : undefined;
  let supermarket = market === undefined ? first : firstYearReaching(seed, SUPERMARKET_POPULATION, Math.max(EXPAND_FROM, first + 8));
  // a store that would only move out once the superstore has come never does (the market closes instead)
  if (supermarket !== undefined && superstore !== undefined && supermarket >= superstore) supermarket = undefined;
  return { ...(market !== undefined ? { market } : {}), ...(supermarket !== undefined ? { supermarket } : {}), ...(superstore !== undefined ? { superstore } : {}) };
}

// ------------------------------------------------------------------------------------------------ names (all made up)
const SURNAMES = ['Hollis', 'Pruitt', 'Ashby', 'Calloway', 'Whitlock', 'Dunmore', 'Tolliver', 'Pickering', 'Haskell', 'Varney',
  'Eckert', 'Lindqvist', 'Oakes', 'Bramwell', 'Fenwick', 'Garrity', 'Beckwith', 'Marlow', 'Stroud', 'Quimby', 'Corliss', 'Ambler'];
const MARKET_WORDS = ['Sunnyside', 'Hilltop', 'Brookside', 'Larkspur', 'Dogwood', 'Bellwether', 'Goldenrod', 'Orchard Row', 'Bluebell', 'Meadowlark'];
/** Made-up regional supermarket chains of the 1970s-80s. */
const CHAINS_70S = ['Fairbasket', 'Larderly', 'Goodacre Foods', 'Thrifton Foods', 'Brightbasket', 'Hearthside Foods', 'Prairie Larder', 'Kettle Creek Foods'];
/** Made-up big-box chains. */
const SUPERSTORE_BRANDS = ['Bountiway', 'Everwide', 'Thriftacre', 'Plenwell', 'Allbright', 'Grandacre', 'Harvestone', 'Sunvale', 'Broadmeadow', 'Wellstock'];
const CHURCHES = ['Grace Fellowship Church', 'Cornerstone Chapel', 'Living Water Church', 'New Hope Fellowship', 'Calvary Chapel of the Valley'];

const pickOf = <T>(items: readonly T[], r: number): T => items[Math.min(items.length - 1, Math.floor(r * items.length))]!;
const R = (seed: string, ...k: (string | number)[]): number => rand(seed, 'store', ...k);

/** A family name for a store's owner ([n]th owner of [site]). */
const owner = (seed: string, site: StoreSite, n: number): string => pickOf(SURNAMES, R(seed, site, 'owner', n));

/** The general store's name under its [n]th owner. */
export function generalStoreName(seed: string, n: number): string {
  const town = townName(seed);
  const sur = owner(seed, 'generalstore', n);
  const r = R(seed, 'general-name', n);
  return r < 0.3 ? `${sur}'s General Store` : r < 0.5 ? `${sur} Mercantile` : r < 0.65 ? `${sur} & Sons General Store`
    : r < 0.8 ? `${town} Mercantile` : r < 0.92 ? `${town} General Store` : `${sur} Dry Goods & Grocery`;
}

/** The supermarket's name as the downtown market ([expanded] false) and as the bigger store at the edge of town. */
export function supermarketName(seed: string, expanded: boolean): string {
  const town = townName(seed);
  const sur = owner(seed, 'market', 0);
  const r = R(seed, 'market-name');
  const base = r < 0.4 ? sur : r < 0.7 ? pickOf(MARKET_WORDS, R(seed, 'market-word')) : town;
  if (!expanded) return r < 0.4 ? `${sur}'s Super Market` : `${base} Market`;
  // moved out: the family keeps its name ("Hollis Foods"), or a regional chain buys it
  return R(seed, 'market-chain') < 0.4 ? pickOf(CHAINS_70S, R(seed, 'chain')) : r < 0.7 ? `${base} Foods` : `${base} Food Center`;
}

/** The superstore's (made-up) chain. */
export const superstoreName = (seed: string): string => pickOf(SUPERSTORE_BRANDS, R(seed, 'brand'));

const REUSE_GENERAL: readonly [StoreReuse, number][] = [['antiques', 0.34], ['church', 0.18], ['cafe', 0.2], ['museum', 0.14], ['feed', 0.14]];
const REUSE_MARKET: readonly [StoreReuse, number][] = [['thrift', 0.24], ['church', 0.2], ['gym', 0.18], ['flea', 0.14], ['outlet', 0.12], ['community', 0.12]];

/** What a closed store becomes, and the name over its door. */
export function reuseOf(seed: string, site: StoreSite): { reuse: StoreReuse; name: string } {
  const table = site === 'generalstore' ? REUSE_GENERAL : REUSE_MARKET;
  let r = R(seed, site, 'reuse');
  let reuse = table[table.length - 1]![0];
  for (const [k, p] of table) { if (r < p) { reuse = k; break; } r -= p; }
  const town = townName(seed);
  const v = R(seed, site, 'reuse-name');
  const name: Record<StoreReuse, string> = {
    antiques: v < 0.5 ? 'Old Store Antiques' : `${town} Antiques & Collectibles`,
    church: pickOf(CHURCHES, v),
    cafe: v < 0.5 ? 'The Old Store Cafe' : 'Front Porch Cafe',
    museum: `${town} Heritage Museum`,
    feed: `${town} Feed & Seed`,
    thrift: v < 0.5 ? 'Second Chance Thrift' : 'Kind Neighbor Thrift',
    gym: v < 0.5 ? 'Iron Works Fitness' : `${town} Fitness`,
    flea: `${town} Flea Market`,
    outlet: v < 0.5 ? 'Penny Pinch Outlet' : 'Bargain Loft',
    community: `${town} Community Center`,
  };
  return { reuse, name: name[reuse] };
}

/** In words, for people: "an antiques shop". */
export const REUSE_WORDS: Record<StoreReuse, string> = {
  antiques: 'an antiques shop', church: 'a church', cafe: 'a cafe', museum: 'the town museum', feed: 'a feed and seed store',
  thrift: 'a thrift store', gym: 'a gym', flea: 'a flea market', outlet: 'a discount outlet', community: 'the community center',
};

// ------------------------------------------------------------------------------------------------ a store's life
/** opened; renamed (sold to a new owner); moved (the market's business opened here, bigger, under a new name); closed; reused. */
export type StoreEventKind = 'opened' | 'renamed' | 'moved' | 'closed' | 'reused';
export interface StoreEvent {
  year: number;
  event: StoreEventKind;
  /** The name over the door from this year (a closed store keeps its old, fading sign). */
  name: string;
  /** The building from this year (opened, expanded). */
  variant?: StoreVariant;
  reuse?: StoreReuse;
  /** Why it closed: the business moved out to the bigger store, or the superstore took its trade. */
  why?: 'moved' | 'superstore';
}
export interface StoreHistory {
  site: StoreSite;
  /** The general store's build (frontier or brick); the others are 'modern'. */
  style: StoreStyle;
  events: StoreEvent[];
}

/** The year the general store was built (well before the game begins). */
export const generalStoreBuilt = (seed: string): number => 1885 + Math.floor(R(seed, 'general-built') * 51);

/**
 * The life of each store site of the town, oldest first (only the sites [placed] in the plan: a town whose plan found
 * no room for a superstore never gets one, and its old stores stay open). Events may run past the game's present
 * (a store boarded up in 2024 is reused in 2027), so time travel forward finds them changed.
 */
export function storeHistories(seed: string, placed: ReadonlySet<StoreSite>): StoreHistory[] {
  const needs = storeNeeds(seed);
  const S = placed.has('superstore') ? needs.superstore : undefined;
  const out: StoreHistory[] = [];
  /** A store closed in [closed] ([why]): reused some years on, or left empty for good (chance [empty]). */
  const after = (site: StoreSite, ev: StoreEvent[], closed: number, why: StoreEvent['why'], empty: number, wait: number): void => {
    ev.push({ year: closed, event: 'closed', name: ev[ev.length - 1]!.name, why });
    if (R(seed, site, 'empty') < empty) return;
    const { reuse, name } = reuseOf(seed, site);
    ev.push({ year: closed + 1 + Math.floor(R(seed, site, 'reuse-year') * wait), event: 'reused', name, reuse });
  };

  // the general store: built, perhaps sold (renamed), closed when the superstore comes, boarded, perhaps reused
  if (placed.has('generalstore')) {
    const built = generalStoreBuilt(seed);
    const ev: StoreEvent[] = [{ year: built, event: 'opened', name: generalStoreName(seed, 0), variant: 'general' }];
    const closed = S === undefined ? undefined : S + Math.floor(R(seed, 'general-close') * 3);
    if (R(seed, 'general-sold') < 0.45) {
      const sold = 1952 + Math.floor(R(seed, 'general-sold-year') * 34);
      const name = generalStoreName(seed, 1);
      if ((closed === undefined || sold < closed) && name !== ev[0]!.name) ev.push({ year: sold, event: 'renamed', name });
    }
    if (closed !== undefined) after('generalstore', ev, closed, 'superstore', 0.22, 8);
    out.push({ site: 'generalstore', style: R(seed, 'general-style') < 0.55 ? 'frontier' : 'brick', events: ev });
  }

  const SM = placed.has('supermarket') ? needs.supermarket : undefined;
  // the market downtown: open until the store moves out to the edge of town, or until the superstore comes
  if (placed.has('market') && needs.market !== undefined) {
    const ev: StoreEvent[] = [{ year: needs.market, event: 'opened', name: supermarketName(seed, false), variant: 'market' }];
    if (SM !== undefined) after('market', ev, SM, 'moved', 0.2, 4);
    else if (S !== undefined) after('market', ev, S + 1 + Math.floor(R(seed, 'market-close') * 3), 'superstore', 0.2, 6);
    out.push({ site: 'market', style: 'modern', events: ev });
  }
  // the supermarket at the edge of town: the market moved out (renamed), or the town's first; closed after the superstore
  if (SM !== undefined) {
    const moved = placed.has('market') && needs.market !== undefined;
    const ev: StoreEvent[] = [{ year: SM, event: moved ? 'moved' : 'opened', name: supermarketName(seed, true), variant: 'supermarket' }];
    if (S !== undefined) after('supermarket', ev, S + 1 + Math.floor(R(seed, 'supermarket-close') * 3), 'superstore', 0.18, 6);
    out.push({ site: 'supermarket', style: 'modern', events: ev });
  }

  if (S !== undefined) out.push({ site: 'superstore', style: 'modern', events: [{ year: S, event: 'opened', name: superstoreName(seed), variant: 'superstore' }] });
  return out;
}

const histories = new Map<string, StoreHistory[]>();
/** storeHistories for the sites the plan placed ([ids]: the plan's landmark ids), cached per seed. */
export function townStoreHistories(seed: string, ids: readonly string[]): StoreHistory[] {
  let h = histories.get(seed);
  if (!h) { h = storeHistories(seed, new Set(ids.filter(isStoreSite))); histories.set(seed, h); }
  return h;
}

// ------------------------------------------------------------------------------------------------ the year's state
export interface StoreState {
  site: StoreSite;
  year: number;
  status: StoreStatus;
  /** The building standing (its era). */
  variant: StoreVariant;
  style: StoreStyle;
  /** The store's own name (the last it traded under, for a closed or reused one). */
  name: string;
  /** What the sign over the door says: the store's name, or the new use's once reused. */
  sign: string;
  reuse?: StoreReuse;
  opened: number;
  /** The year it closed, once it has. */
  closed?: number;
  /** Why it closed. */
  why?: 'moved' | 'superstore';
  /** The supermarket that is the downtown market moved out (it opened as the market's bigger store). */
  movedFrom?: 'market';
  /** What stands and shows on the site this year, for the builder (kotlin-street: StoreKit). */
  features: string[];
  /** The events so far. */
  history: StoreEvent[];
}

/** The state of one store in [year] (undefined before it opens). */
export function storeState(seed: string, h: StoreHistory, year: number): StoreState | undefined {
  const past = h.events.filter((e) => e.year <= year);
  if (!past.length) return undefined;
  let variant: StoreVariant = 'general', name = '', sign = '', status: StoreStatus = 'open';
  let reuse: StoreReuse | undefined, closed: number | undefined, why: StoreEvent['why'];
  for (const e of past) {
    if (e.variant) variant = e.variant;
    if (e.event === 'opened' || e.event === 'renamed' || e.event === 'moved') { name = e.name; sign = e.name; }
    if (e.event === 'closed') { status = 'closed'; closed = e.year; why = e.why; }
    if (e.event === 'reused') { status = 'reused'; reuse = e.reuse; sign = e.name; }
  }
  return {
    site: h.site, year, status, variant, style: h.style, name, sign, ...(reuse ? { reuse } : {}), opened: h.events[0]!.year,
    ...(closed !== undefined ? { closed } : {}), ...(why ? { why } : {}), ...(h.events[0]!.event === 'moved' ? { movedFrom: 'market' as const } : {}),
    features: storeFeatures(seed, variant, status, year, reuse), history: past,
  };
}

/**
 * What the builder shows (kotlin-street docs/STORES.md): the general store's porch goods and its gas pump until the
 * 1970s, the markets' plate glass and lit signs, the car parks' cart corrals, the superstore's garden centre and docks;
 * boards over a closed store's windows, the new use's sign on a reused one.
 */
export function storeFeatures(seed: string, variant: StoreVariant, status: StoreStatus, year: number, reuse?: StoreReuse): string[] {
  const f: string[] = [];
  const open = status === 'open';
  if (variant === 'general') {
    f.push('porch', 'bench', 'paintedSign', 'backyard', 'shed');
    if (open) f.push('barrels', 'crates', 'iceChest', 'windowGoods');
    if (open && year < 1975) f.push('gasPump');
    if (open && year >= 1975) f.push('soda', 'propaneCage');
    if (status === 'closed') f.push('boarded');
  } else if (variant === 'market') {
    f.push('plateGlass', 'litSign', 'awning', 'smallLot', 'serviceYard');
    if (open) f.push('windowPosters', 'carts');
  } else if (variant === 'supermarket') {
    f.push('longGlassFront', 'flatRoof', 'pylonSign', 'carPark', 'serviceYard');
    if (open) f.push('cartCorrals', 'windowPosters');
  } else {
    f.push('flatRoof', 'pylonSign', 'carPark', 'cartCorrals', 'gardenCentre', 'loadingDocks', 'carParkLamps', 'twoEntrances');
    if (year >= 2000) f.push('pharmacy');
    if (year >= 2012) f.push('pickupBays');
    if (year >= 2020) f.push('evChargers');
  }
  if (status === 'closed') f.push(variant === 'general' ? 'fadedSign' : 'forLease', 'emptyLot');
  if (status === 'reused' && reuse) f.push(`reuse:${reuse}`);
  // seeded touches the builder may pick from
  if (variant === 'general' && rand(seed, 'store', 'general-dog') < 0.4 && open) f.push('dogOnPorch');
  return f;
}

// ------------------------------------------------------------------------------------------------ the site's layout
/** A rectangle on the plot in metres: x along the front (left to right as seen from the street), z back from the front. */
export type SiteRect = [number, number, number, number];
export interface StoreLayout {
  /** The plot (metres along x deep). */
  width: number;
  depth: number;
  building: SiteRect;
  /** The building's height to the roof (metres; the general store's false front stands higher). */
  height: number;
  porch?: SiteRect;
  sideYard?: SiteRect;
  backyard?: SiteRect;
  shed?: SiteRect;
  carPark?: SiteRect;
  serviceYard?: SiteRect;
  docks?: SiteRect;
  gardenCentre?: SiteRect;
  /** Parking bays and cart corrals the car park holds. */
  bays?: number;
  corrals?: number;
}

const r1 = (v: number): number => Math.round(v * 10) / 10;
const rect = (x0: number, z0: number, x1: number, z1: number): SiteRect => [r1(x0), r1(z0), r1(x1), r1(z1)];

/** Where the building, its car park, yards and docks stand on a plot of [tilesW] x [tilesD] tiles. */
export function storeLayout(variant: StoreVariant, tilesW: number, tilesD: number): StoreLayout {
  const W = tilesW * ALONG_M, D = tilesD * DEEP_M;
  const lot = (x0: number, z0: number, x1: number, z1: number, every = 1500): Pick<StoreLayout, 'carPark' | 'bays' | 'corrals'> => ({
    carPark: rect(x0, z0, x1, z1), bays: Math.floor(((x1 - x0) * (z1 - z0) * 0.62) / 12.5), corrals: Math.max(1, Math.round(((x1 - x0) * (z1 - z0)) / every)),
  });
  if (variant === 'general') {
    const bw = Math.min(9.5, W - 4.5), bd = Math.min(16, D - 13);
    return {
      width: r1(W), depth: r1(D), height: 6.5,
      porch: rect(1, 2, 1 + bw, 5), building: rect(1, 5, 1 + bw, 5 + bd),
      sideYard: rect(1.5 + bw, 1, W - 0.5, 5 + bd), backyard: rect(0.5, 5.5 + bd, W - 0.5, D - 0.5), shed: rect(W - 5, D - 5, W - 1.5, D - 1.5),
    };
  }
  if (variant === 'market') {
    // the 1950s-60s market: plate glass on the street side of a small lot, the store behind, a service yard at the back
    const bw = Math.min(26, W - 3), x0 = (W - bw) / 2, z0 = 23, bd = Math.min(32, D - 31);
    return {
      width: r1(W), depth: r1(D), height: 6, building: rect(x0, z0, x0 + bw, z0 + bd), ...lot(1, 2, W - 1, 21, 1e9),
      serviceYard: rect(x0, z0 + bd, x0 + bw, Math.min(D - 1, z0 + bd + 8)),
    };
  }
  if (variant === 'supermarket') {
    const bd = Math.min(40, D - 33), bw = Math.min(52, W - 10), x0 = (W - bw) / 2, z0 = D - 8 - bd;
    return {
      width: r1(W), depth: r1(D), height: 7.5, building: rect(x0, z0, x0 + bw, D - 8), ...lot(2, 2, W - 2, z0 - 2),
      serviceYard: rect(x0, D - 8, x0 + bw, D - 1), docks: rect(x0 + bw * 0.6, D - 8, x0 + bw - 2, D - 1),
    };
  }
  const bd = Math.min(95, D - 110), bw = Math.min(118, W - 40), x0 = 6, z0 = D - 20 - bd;
  return {
    width: r1(W), depth: r1(D), height: 10, building: rect(x0, z0, x0 + bw, z0 + bd), ...lot(2, 3, W - 2, z0 - 3, 2400),
    gardenCentre: rect(x0 + bw, z0 + 8, x0 + bw + 24, z0 + 58),
    serviceYard: rect(x0, z0 + bd, x0 + bw, D - 1), docks: rect(x0 + bw * 0.15, z0 + bd, x0 + bw * 0.6, D - 2),
  };
}
