import { rand } from '../sim/rng';

// Hand-made landmark MODELS (glTF .glb, kotlin-street/docs/LANDMARKS_GLB.md): one-off buildings modelled by hand to
// the guide (docs/CHATGPT_MODEL_GUIDE.md) and drawn by the engine from kotlin-street/app/src/main/assets/landmarks/.
// This is the generator's side of the catalogue (the engine reads landmarks.json there; a test keeps the two in step).
// Nothing places them yet: a later change gives towns their sites (as the stores' storeSites.ts does) and records the
// business's history (opened, moved, closed, reused) in the simulation; the models never decide that themselves.

export type GlbLandmarkKind =
  | 'landmark_glb_modern_gas_station'
  | 'landmark_glb_main_street_service_station'
  | 'landmark_glb_junction_diner'
  | 'landmark_glb_starling_lanes';

export interface GlbLandmarkSpec {
  name: string;
  /** The business it houses. */
  role: 'gas_station' | 'diner' | 'bowling';
  /** The design's years: built from..to (it may stand long after, as the simulation decides), and its sample year. */
  era: { from: number; to: number; built: number };
  /** The lot, metres: width along the street, depth back from it; the building's walls. */
  lot: [number, number];
  building: [number, number];
}

export const GLB_LANDMARKS: Record<GlbLandmarkKind, GlbLandmarkSpec> = {
  // a highway-era station with a canopy over four pumps and a convenience store (a town's Main Street station may
  // move out to one when the simulation says so: the two may also stand together)
  landmark_glb_modern_gas_station: { name: 'Crossfield Fuel & Market', role: 'gas_station', era: { from: 2005, to: 2040, built: 2005 }, lot: [32.28, 35.08], building: [17.38, 8.69] },
  // the 1950s-60s full-service station on Main Street: office, two repair bays, two pumps
  landmark_glb_main_street_service_station: { name: 'Main Street Service', role: 'gas_station', era: { from: 1950, to: 1969, built: 1953 }, lot: [23.28, 21.73], building: [15.52, 8.07] },
  // a streamlined enamel diner; opens in the 1940s-50s
  landmark_glb_junction_diner: { name: 'Junction Diner', role: 'diner', era: { from: 1940, to: 1965, built: 1948 }, lot: [17.0, 21.0], building: [12.0, 12.3] },
  // an eight-lane 1960s bowling alley
  landmark_glb_starling_lanes: { name: 'Starling Lanes', role: 'bowling', era: { from: 1960, to: 1969, built: 1964 }, lot: [34.76, 42.21], building: [22.35, 31.04] },
};
export const GLB_LANDMARK_KINDS = Object.keys(GLB_LANDMARKS) as GlbLandmarkKind[];

/** A plot tile is 1.24 m along the street (the art's scale) and 2.10 m back (the position scale): see STORES.md. */
export const TILE_ALONG_M = 1.24;
export const TILE_BACK_M = 2.1;

/** The plot a model needs, in tiles (width along the street, depth back). */
export function glbLandmarkTiles(kind: GlbLandmarkKind): [number, number] {
  const s = GLB_LANDMARKS[kind];
  return [Math.ceil(s.lot[0] / TILE_ALONG_M), Math.ceil(s.lot[1] / TILE_BACK_M)];
}

/** The year a town's [kind] opens, drawn from its design years (deterministic in the seed). */
export function glbLandmarkOpenYear(seed: string, kind: GlbLandmarkKind): number {
  const { from, to } = GLB_LANDMARKS[kind].era;
  return from + Math.floor(rand(seed, 'glb-landmark-open', kind) * (to - from + 1));
}

/** What a gallon of regular cost (US dollars, nominal), by year: the engine's table (LandmarkKit.kt GasPrices.TABLE). */
export const GAS_PRICES: readonly [number, number][] = [
  [1935, 0.19], [1940, 0.18], [1945, 0.21], [1950, 0.27], [1955, 0.29], [1960, 0.31], [1965, 0.31], [1970, 0.36], [1973, 0.39],
  [1974, 0.53], [1975, 0.57], [1978, 0.63], [1979, 0.86], [1980, 1.19], [1981, 1.31], [1982, 1.22], [1985, 1.2], [1986, 0.93],
  [1990, 1.16], [1995, 1.15], [1998, 1.06], [2000, 1.51], [2003, 1.59], [2005, 2.3], [2006, 2.59], [2007, 2.8], [2008, 3.27],
  [2009, 2.35], [2010, 2.79], [2011, 3.53], [2012, 3.64], [2014, 3.36], [2015, 2.45], [2016, 2.14], [2018, 2.72], [2019, 2.6],
  [2020, 2.17], [2021, 3.01], [2022, 3.95], [2023, 3.52], [2024, 3.3], [2025, 3.15],
];

/** Regular in [year] (eased between the table's years; 3% a year past its end), for NPCs' small talk and the signs. */
export function gasPrice(year: number): number {
  const t = GAS_PRICES;
  if (year <= t[0][0]) return t[0][1];
  const last = t[t.length - 1];
  if (year >= last[0]) return last[1] * Math.pow(1.03, year - last[0]);
  let i = 0;
  while (t[i + 1][0] <= year) i++;
  const [y0, p0] = t[i]; const [y1, p1] = t[i + 1];
  return p0 + ((p1 - p0) * (year - y0)) / (y1 - y0);
}
