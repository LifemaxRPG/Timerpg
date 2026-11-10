import { rand } from '../sim/rng';
import type { Colour } from './buildings';
import type { LotKind, Rect } from './kinds';
import { hitsWater, type WaterBody } from './water';

export type Palette = 'cream' | 'blue' | 'sage' | 'stone';
export type NeighborhoodId = 'old-town' | 'gardens' | 'heights' | 'woodside' | 'waterfront';
export interface Neighborhood {
  id: NeighborhoodId;
  name: string;
  center: { x: number; y: number };
  palette: Palette;
  shopColour: Colour;
  yard: 'compact' | 'flower' | 'formal' | 'natural';
}
interface Site extends Rect { id: string; kind: LotKind; rank: number }
const point = (r: Rect) => ({ x: r.x + r.w / 2, y: r.y + r.h / 2 });
const distance = (a: { x: number; y: number }, b: { x: number; y: number }) => Math.hypot(a.x - b.x, a.y - b.y);

/** Seed-only geography and style. Does not move plots, consume sim dice, or depend on the viewed year. */
export function zoneNeighborhoods<T extends Site>(seed: string, sites: T[], center: { x: number; y: number }, water: WaterBody | null): {
  neighborhoods: Neighborhood[]; candidates: (T & { neighborhood: NeighborhoodId })[];
} {
  const palettes: Palette[] = ['cream', 'blue', 'sage', 'stone'];
  const rotation = Math.floor(rand(seed, 'neighborhoods', 'palette') * palettes.length);
  const name = ['Oak', 'Maple', 'Cedar', 'Willow', 'Birch', 'Elm'][Math.floor(rand(seed, 'neighborhoods', 'name') * 6)]!;
  const neighborhoods: Neighborhood[] = [];
  const add = (id: NeighborhoodId, title: string, anchor: { x: number; y: number }, yard: Neighborhood['yard'], index: number): Neighborhood => {
    const palette = palettes[(rotation + index) % palettes.length]!;
    const n: Neighborhood = {
      id, name: title, center: anchor, palette, yard,
      shopColour: palette === 'cream' ? 'red' : palette === 'blue' ? 'blue' : 'gray',
    };
    neighborhoods.push(n); return n;
  };
  add('old-town', 'Old Town', center, 'compact', 0);
  const villas = sites.filter((c) => c.kind === 'villa' || c.kind === 'estate');
  const hill = villas.length ? {
    x: villas.reduce((sum, c) => sum + point(c).x, 0) / villas.length,
    y: villas.reduce((sum, c) => sum + point(c).y, 0) / villas.length,
  } : center;
  add('heights', `${name} Heights`, hill, 'formal', 1);
  const residential = sites.filter((c) => c.kind !== 'shop' && c.kind !== 'villa' && c.kind !== 'estate');
  // Spread the residential anchors through actual available plots, rather than inventing a square zoning grid.
  const furthest = (): { x: number; y: number } => {
    let best = center, score = -1;
    for (const c of residential) {
      const p = point(c);
      const d = Math.min(...neighborhoods.map((n) => distance(p, n.center)));
      if (d > score) { score = d; best = p; }
    }
    return best;
  };
  add('gardens', `${name} Gardens`, furthest(), 'flower', 2);
  add('woodside', 'Woodside', furthest(), 'natural', 3);
  const wetSites = new Set(water ? residential.filter((c) => hitsWater(water, c, 16)).map((c) => c.id) : []);
  if (wetSites.size >= 3) {
    const shore = residential.filter((c) => wetSites.has(c.id));
    const anchor = point(shore[Math.floor(rand(seed, 'neighborhoods', 'shore') * shore.length)]!);
    const n = add('waterfront', water!.kind === 'river' ? 'Riverside' : 'Lakeside', anchor, 'natural', 0);
    n.palette = 'blue'; n.shopColour = 'blue';
  }
  const candidates = sites.map((c) => {
    let id: NeighborhoodId;
    if (c.kind === 'shop') id = 'old-town';
    else if (c.kind === 'villa' || c.kind === 'estate') id = 'heights';
    else if (wetSites.size >= 3 && wetSites.has(c.id)) id = 'waterfront';
    else {
      const p = point(c);
      const nearest = neighborhoods.filter((n) => n.id !== 'waterfront')
        .reduce((best, n) => distance(p, n.center) < distance(p, best.center) ? n : best);
      id = nearest.id;
    }
    return { ...c, neighborhood: id };
  });
  return { neighborhoods, candidates };
}

export const neighborhoodOf = (plan: { neighborhoods: Neighborhood[] }, id: NeighborhoodId): Neighborhood =>
  plan.neighborhoods.find((n) => n.id === id)!;

/** Curated recolors already present in the manifest; no runtime tinting or extra asset downloads. */
export const paletteColours: Record<Palette, readonly number[]> = {
  cream: [1, 5], blue: [2, 9], sage: [3, 7], stone: [0, 6, 8],
};
