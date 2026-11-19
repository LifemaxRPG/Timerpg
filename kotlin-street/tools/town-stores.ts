// The stores in the whole-town export (street.json v2, tools/town-graph.ts): each store landmark's entry carries the
// year's building (variant), status and plot footprint, and `graph.stores` says what each store is in the export's year
// (src/town/stores.ts): its name and sign, open / closed / reused (and as what), its history so far, what the builder
// shows (features), and where on its plot the building, car park, yards, garden centre and docks stand (layout, in
// street units). No LimeZu sprite: the engine builds every store (tools/make_buildings.py skips landmark_store_*).
// See kotlin-street/docs/STORES.md.
import type { LandmarkPlot, TownLayout } from '../../src/town/layout';
import { hash } from './town-buildings';
import { populationIn } from '../../src/town/population';
import { isStoreSite, storeDog, storeLayout, townStoreHistories, type SiteRect, type StoreEvent, type StoreLayout, type StoreSite, type StoreVariant } from '../../src/town/stores';

/** Street units per metre (a figure is 36 units for 1.7 m). */
export const UNITS_PER_METRE = 36 / 1.7;
const UNIT_PER_TILE = 32 * 184 / 224;
/** Street units per tile of position back from the street (tools/town-graph.ts SCALE: MOUTH / 4). */
const SCALE = 178 / 4;
const r3 = (v: number): number => Math.round(v * 1000) / 1000;

/** The store's plot: tiles, and street units (along the street at the art's scale, back from it at position scale). */
export interface StoreFootprint { tilesW: number; tilesD: number; width: number; depth: number }
export interface StoreJson {
  id: string; landmark: string;
  /** The site (generalstore, market, supermarket, superstore) and the building standing on it (general, market, ...). */
  site: string; kind: string; style: string;
  /** open, closed (boarded up / for lease) or reused (as reuse.kind, under reuse.name). */
  state: string;
  name: string; sign: string; reuse?: { kind: string; name: string }; why?: string; movedFrom?: string;
  opened: number; closed?: number; year: number;
  /** A stable number for the builder's seeded looks. */
  seed: number;
  features: string[];
  history: StoreEvent[];
  /** The town's living people that year (the car parks fill by it: kotlin-street StoreTraffic). */
  people: number;
  footprint: StoreFootprint;
  /** Where things stand on the plot, street units: x along the front (left to right from the street), z back from it. */
  layout: { height: number; bays?: number; corrals?: number } & Partial<Record<keyof StoreLayout, unknown>> & Record<string, unknown>;
}

const footprintOf = (l: LandmarkPlot): StoreFootprint => ({ tilesW: l.w, tilesD: l.h, width: r3(l.w * UNIT_PER_TILE), depth: r3(l.h * SCALE) });

/** The extra fields of a store's landmark entry (none for the other landmarks). */
export function storeLandmark(l: LandmarkPlot): { store?: { site: string; kind: string; state: string }; footprint?: StoreFootprint } {
  if (!isStoreSite(l.id) || !l.store) return {};
  return { store: { site: l.id, kind: l.store.variant, state: l.store.status }, footprint: footprintOf(l) };
}

const toUnits = (m: StoreLayout): StoreJson['layout'] => {
  const out: StoreJson['layout'] = { height: r3(m.height * UNITS_PER_METRE) };
  for (const [k, v] of Object.entries(m)) {
    if (k === 'height') continue;
    if (Array.isArray(v)) out[k] = (v as SiteRect).map((x) => r3(x * UNITS_PER_METRE));
    else if (k === 'width' || k === 'depth') out[k] = r3((v as number) * UNITS_PER_METRE);
    else out[k] = v;
  }
  return out;
};

/** `{ stores }` for the graph: every store standing in [year] (open, closed or reused), else nothing. */
export function storesBlock(seed: string, year: number, layout: TownLayout): { stores?: StoreJson[] } {
  const stores = layout.landmarks.filter((l) => isStoreSite(l.id) && l.store).map((l): StoreJson => {
    const s = l.store!;
    return {
      id: `store:${l.id}`, landmark: `landmark:${l.id}`, site: s.site, kind: s.variant, style: s.style, state: s.status, name: s.name, sign: s.sign,
      ...(s.reuse ? { reuse: { kind: s.reuse, name: s.sign } } : {}), ...(s.why ? { why: s.why } : {}), ...(s.movedFrom ? { movedFrom: s.movedFrom } : {}),
      opened: s.opened, ...(s.closed !== undefined ? { closed: s.closed } : {}), year, seed: hash(`${seed}:store:${l.id}`) % 100000 + 1,
      features: s.features, history: s.history, people: populationIn(seed, year), footprint: footprintOf(l), layout: toUnits(storeLayout(s.variant, l.w, l.h)),
    };
  });
  return stores.length ? { stores } : {};
}

/**
 * Every store site the town's plan reserves, with its whole life (future events included) and each era's layout, so the
 * engine can show any year from one export (kotlin-street StoreSiteInfo.at(year) mirrors storeState / storeFeatures;
 * a fixture test keeps the two equal). `rect` is the plot in world units like the landmarks' (x0, z0, x1, z1); before
 * its store opens a site is a field (the engine's placeholder), the year before it opens a building site.
 */
export interface StoreSiteJson {
  id: string; landmark: string; site: StoreSite; style: string; seed: number;
  /** A dog asleep on the general store's porch while it trades (the generator's seeded touch). */
  dog: boolean;
  events: StoreEvent[];
  footprint: StoreFootprint;
  /** The layout of each building the site has over the years (street units, as StoreJson.layout). */
  layouts: Partial<Record<StoreVariant, StoreJson['layout']>>;
  rect: [number, number, number, number];
}

/** `{ storeSites, storePeople }`: every reserved site for all years, and the town's people every 5 years 1950-2025. */
export function storeSitesBlock(seed: string, plots: readonly LandmarkPlot[], rectW: (l: LandmarkPlot) => [number, number, number, number]):
  { storeSites?: StoreSiteJson[]; storePeople?: [number, number][] } {
  const ids = plots.map((l) => l.id);
  const hs = townStoreHistories(seed, ids);
  const sites = plots.filter((l) => isStoreSite(l.id)).flatMap((l): StoreSiteJson[] => {
    const h = hs.find((x) => x.site === l.id);
    if (!h) return [];
    const variants = [...new Set(h.events.map((e) => e.variant).filter((v): v is StoreVariant => !!v))];
    return [{
      id: `store:${l.id}`, landmark: `landmark:${l.id}`, site: h.site, style: h.style, seed: hash(`${seed}:store:${l.id}`) % 100000 + 1,
      dog: storeDog(seed), events: h.events, footprint: footprintOf(l),
      layouts: Object.fromEntries(variants.map((v) => [v, toUnits(storeLayout(v, l.w, l.h))])), rect: rectW(l),
    }];
  });
  if (!sites.length) return {};
  const storePeople: [number, number][] = [];
  for (let y = 1950; y <= 2025; y += 5) storePeople.push([y, populationIn(seed, y)]);
  return { storeSites: sites, storePeople };
}
