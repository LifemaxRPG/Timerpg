// The hand-made landmark models in the whole-town export (street.json v2, tools/town-graph.ts): where the town has them and
// what stands on each site in the export's year (src/town/glbSites.ts), with each site's whole history so a later engine
// can show other years. The railroad's own depot model is the railroad's (rail.json `depot.premier`: src/town/rail.ts).
// See kotlin-street/docs/LANDMARKS_GLB.md "In the town".
import { runTimeline, worldAt } from '../../src/sim';
import { glbSites, glbStandingOn, type GlbSite, type GlbState, type GlbTenure } from '../../src/town/glbSites';
import { glbLandmarkTiles } from '../../src/town/glbLandmarks';
import { planTown } from '../../src/town/plan';
import { townRail, type TownRail } from '../../src/town/rail';
import type { Rect } from '../../src/town/kinds';
import { railSite, type RailWorld } from '../../src/world/rail';
import { yardRects } from './export-rail';
import { WORLD } from './town-config';

/** The world a town stands in for its railroad: the app's (gamma's) world, the town at its home cell. */
export const worldOf = (seed: string): RailWorld => (seed === WORLD.home ? WORLD : { seed: WORLD.seed, home: seed });

const cache = new Map<string, { sites: GlbSite[]; rail: TownRail }>();

/** The town's model sites over all its years, and the railroad they keep clear of (cached per seed). */
export function townGlb(seed: string): { sites: GlbSite[]; rail: TownRail } {
  let got = cache.get(seed);
  if (got) return got;
  const plan = planTown(seed);
  const w = worldOf(seed);
  const keep = yardRects(seed);
  const rail = townRail(w, railSite(w, 0, 0), plan, keep);
  const props = worldAt(runTimeline({ seed, endYear: 2025 }), 2025).settlement?.properties ?? [];
  const sites = glbSites(plan, {
    lots: props, keep,
    rail: { tracks: rail.tracks.map((t) => t.pts), rects: [rail.depot?.rect, rail.depot?.premier, rail.elevator].filter((r): r is Rect => !!r), elevator: rail.elevator },
  });
  got = { sites, rail };
  cache.set(seed, got);
  return got;
}

/** A site's model standing in the export's year, as street.json `graph.glbLandmarks` lists it. */
export interface GlbJson {
  id: string; landmark: string; kind: string; name: string; state: GlbState; hide?: string[];
  opened: number; closed?: number; gone?: number; reuse?: { from: number; as: string };
  /** The plot in tiles (along the street, back from it) and its world rect (x0, z0, x1, z1). */
  tiles: [number, number]; rect: [number, number, number, number];
  /** The row slot it stands in (sprite bN) and the row. */
  sprite: string; row: string;
  /** Every business the site has over the years (the generator's tenures). */
  history: GlbTenure[];
}

/** What stands on each of [sites] in [year] (the sites with nothing standing are left out). */
export function glbStanding(sites: readonly GlbSite[], year: number): { site: GlbSite; tenure: GlbTenure; state: GlbState; name: string; hide: string[]; tiles: [number, number] }[] {
  return sites.flatMap((site) => {
    const s = glbStandingOn(site, year);
    if (!s) return [];
    const [a, d] = glbLandmarkTiles(s.tenure.kind);
    return [{ site, ...s, tiles: [a, d] as [number, number] }];
  });
}
