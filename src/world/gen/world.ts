import { makeBiomes, makeClimate, makeCrops, type Climate } from './climate';
import { blur, N } from './grid';
import { makeHighways, type Highways } from './highways';
import { growthFacts, makeDams, type Dam, type GrowthFacts } from './history';
import { CAPITAL_NAME, HUB_COUNT } from './hubs';
import { RIVER_FLOW, makeHydro, type Hydro } from './hydro';
import { NameRegistry, type NameStyle } from './names';
import { foundedYear, placeHubs, placeMountainPlaces, placeTowns, siteFields, type Place, type SiteFields } from './places';
import { makeRail, skeletonRail, type RailNet } from './railnet';
import { RoadGraph, makeRoads, type Road } from './roads';
import { makeSea, type SeaNet } from './sea';
import { makeTerrain, type Terrain } from './terrain';
import { climateTags, makeWeather, type Weather } from './weather';
import { makeWonders, type Wonder } from './wonders';
import { windRoads, type WindStats } from './winding';
import type { RailWorld } from '../rail';

// THE WORLD SKELETON (WORLD_GEN.md sections 2-11, phases W1-W3): everything that becomes a fact somebody can say or
// check, pure in the world seed (and the year, for the history). generateWorld runs the pipeline in order: terrain,
// climate, hydrology, biomes and crops, weather statistics; hubs (the capital first), towns; roads and hamlets; the
// name registry; rail, sea, dams; the road graph and the rail world the planners run on.

export interface World {
  seed: string;
  terrain: Terrain;
  climate: Climate;
  hydro: Hydro;
  biome: Uint8Array;
  crop: Uint8Array;
  weather: Weather;
  fields: SiteFields;
  hubs: Place[];
  towns: Place[];
  hamlets: Place[];
  /** The natural wonders (wonders.ts), named in the same registry as the places. */
  wonders: Wonder[];
  /** Every place: hubs, towns, hamlets. */
  places: Place[];
  byId: Map<string, Place>;
  byName: Map<string, Place>;
  /** The old network (highways and county roads, roads.ts and winding.ts). */
  roads: Road[];
  /** Highways v2 (highways.ts): every segment's life by year, the expressways, interchanges, numbers, the roadside. */
  highways: Highways;
  /** The road graph over the old roads and the expressways (and their connectors). */
  roadGraph: RoadGraph;
  rail: RailNet;
  /** The skeleton's network for src/world/rail.ts (history, stations, timetables, planRoute). */
  railWorld: RailWorld;
  sea: SeaNet;
  dams: Dam[];
  growth: Map<string, GrowthFacts>;
  /** Climate hooks per place (town identity, NPC talk). */
  tags: Map<string, string[]>;
  startMass: number;
  /** The winding roads' totals (winding.ts). */
  winding: WindStats;
  /** Milliseconds per stage. */
  timing: Record<string, number>;
}

const STYLE: Record<string, NameStyle> = {
  capital: 'capital', port: 'port', 'river city': 'river', 'mountain town': 'mountain', 'mill town': 'mill', 'farm town': 'farm',
  farm: 'farm', harbor: 'harbor', mill: 'mill', mining: 'mining', resort: 'resort', suburb: 'suburb', hamlet: 'hamlet',
};

export function generateWorld(seed: string, opts: { hubs?: number } = {}): World {
  const timing: Record<string, number> = {};
  let t0 = Date.now();
  const lap = (k: string): void => { const n = Date.now(); timing[k] = n - t0; t0 = n; };
  const terrain = makeTerrain(seed); lap('terrain');
  const climate = makeClimate(terrain); lap('climate');
  const hydro = makeHydro(terrain, climate); lap('hydro');
  const riv = new Float32Array(N);
  for (let c = 0; c < N; c++) riv[c] = hydro.flow[c]! >= RIVER_FLOW || hydro.lake[c] ? 1 : 0;
  const biome = makeBiomes(terrain, climate, hydro.lake, blur(riv, 2, 2, 1));
  const crop = makeCrops(terrain, climate, biome);
  const weather = makeWeather(terrain, climate, hydro); lap('biomes+weather');
  const fields = siteFields(terrain, climate, hydro, biome, crop);
  const hp = placeHubs(terrain, climate, hydro, fields, opts.hubs ?? HUB_COUNT);
  const hubs = hp.hubs;
  for (const p of hubs) p.founded = foundedYear(terrain, p, hubs);
  const towns = placeTowns(terrain, climate, hydro, fields, biome, hubs);
  towns.push(...placeMountainPlaces(terrain, climate, hydro, fields, biome, hubs, towns));
  for (const p of towns) p.founded = foundedYear(terrain, p, hubs);
  lap('places');
  const rd = makeRoads(terrain, climate, hydro, biome, hubs, towns);
  const hamlets = rd.hamlets;
  for (const r of rd.roads) r.cellKm = r.km;
  const winding = windRoads(terrain, hydro, hubs, rd.roads);
  timing.windingMs = winding.ms;
  for (const p of hamlets) p.founded = foundedYear(terrain, p, hubs);
  lap('roads');
  // names: the capital's authored placeholder, then hubs, towns and hamlets in order (unique on the planet)
  const reg = new NameRegistry(seed);
  for (const p of [...hubs, ...towns, ...hamlets]) p.name = p.hub?.name ? reg.claim(p.hub.name === CAPITAL_NAME ? CAPITAL_NAME : p.hub.name) : reg.make(p.id, p.setting === 'alpine' || p.setting === 'pass' ? 'mountain' : STYLE[p.type] ?? 'farm');
  const places = [...hubs, ...towns, ...hamlets];
  const wonders = makeWonders(terrain, climate, hydro, biome, weather, fields, places, reg);
  lap('wonders');
  const highways = makeHighways(terrain, hydro, places, rd.roads);
  lap('highways');
  const byId = new Map(places.map((p) => [p.id, p]));
  const byName = new Map(places.map((p) => [p.name.toLowerCase(), p]));
  const railWorld: RailWorld = { seed };
  const rail = makeRail(terrain, climate, hydro, biome, places, railWorld);
  lap('rail');
  const sea = makeSea(terrain, places, hubs); lap('sea');
  const dams = makeDams(terrain, hydro, fields, places);
  const roadGraph = new RoadGraph([...rd.roads, ...highways.links], places);
  railWorld.graph = skeletonRail(seed, rail, places, roadGraph);
  const growth = new Map(places.map((p) => [p.id, growthFacts(p, rd.roads, rail.links, hubs, dams, highways.towns.get(p.id))]));
  const tags = new Map(places.map((p) => [p.id, climateTags(climate, weather, p.cell, p.x, p.z)]));
  lap('history');
  return { seed, terrain, climate, hydro, biome, crop, weather, fields, hubs, towns, hamlets, wonders, places, byId, byName, roads: rd.roads, highways, roadGraph, rail, railWorld, sea, dams, growth, tags, startMass: hp.startMass, winding, timing };
}

const cache = new Map<string, World>();
/** The world of [seed], generated once per process. */
export function worldOf(seed: string): World {
  let w = cache.get(seed);
  if (!w) { w = generateWorld(seed); cache.set(seed, w); }
  return w;
}
