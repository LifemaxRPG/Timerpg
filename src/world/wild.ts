import { rand } from '../sim/rng';

// WILDERNESS between the towns: scattered woods and meadows, generated chunk by chunk.
export const WILD_CHUNK = 32; // tiles per side

export interface WildProp { kind: 'tree' | 'bush' | 'flowers'; /** world pixels (bottom edge for y) */ x: number; y: number; look: number }

function noise(seed: string, x: number, y: number): number {
  const x0 = Math.floor(x), y0 = Math.floor(y);
  const fx = x - x0, fy = y - y0;
  const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
  const h = (ix: number, iy: number): number => rand(seed, 'wild-noise', ix, iy);
  const a = h(x0, y0), b = h(x0 + 1, y0), c = h(x0, y0 + 1), d = h(x0 + 1, y0 + 1);
  return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
}

/** Trees, bushes and wild flowers for one chunk. Pure in (worldSeed, cx, cy). */
export function wildChunk(worldSeed: string, cx: number, cy: number): WildProp[] {
  const out: WildProp[] = [];
  for (let ty = 0; ty < WILD_CHUNK; ty += 2) {
    for (let tx = 0; tx < WILD_CHUNK; tx += 2) {
      const gx = cx * WILD_CHUNK + tx, gy = cy * WILD_CHUNK + ty;
      const grove = noise(worldSeed, gx / 22, gy / 22);
      const p = grove > 0.56 ? 0.25 + (grove - 0.56) * 2.2 : 0.035;
      const r = rand(worldSeed, 'wild', gx, gy);
      if (r < p) {
        out.push({ kind: 'tree', x: Math.round((gx + 0.5 + rand(worldSeed, 'wx', gx, gy)) * 32), y: Math.round((gy + 1.5 + rand(worldSeed, 'wy', gx, gy)) * 32), look: rand(worldSeed, 'wl', gx, gy) });
      } else if (r > 0.93) {
        out.push({ kind: r > 0.97 ? 'flowers' : 'bush', x: Math.round((gx + 1) * 32), y: Math.round((gy + 1.4) * 32), look: rand(worldSeed, 'wl2', gx, gy) });
      }
    }
  }
  return out;
}
