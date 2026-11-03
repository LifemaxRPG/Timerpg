import { rand } from '../sim/rng';
import type { Grid } from './grid';
import type { Prop } from './plan';
import { TILE } from './plan';

// GREENERY: vegetation whose density depends on distance from development, shaped by smooth noise
// so it forms groves and clearings. Near buildings it is sparse (gardens, the odd tree); farther out
// it thickens into forest, so the town edge is organic instead of a rectangle.

/** Smooth 2D value noise in [0,1). */
function noise(seed: string, salt: string, x: number, y: number): number {
  const x0 = Math.floor(x), y0 = Math.floor(y);
  const fx = x - x0, fy = y - y0;
  const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
  const h = (ix: number, iy: number): number => rand(seed, 'noise', salt, ix, iy);
  const a = h(x0, y0), b = h(x0 + 1, y0), c = h(x0, y0 + 1), d = h(x0 + 1, y0 + 1);
  return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
}

/** Distance (in tiles, 8-neighbour) from the nearest occupied cell. */
export function distanceField(grid: Grid): Uint16Array {
  const { width: W, height: H } = grid;
  const INF = 60000;
  const d = new Uint16Array(W * H).fill(INF);
  const queue: number[] = [];
  for (let i = 0; i < W * H; i++) if (grid.cells[i]) { d[i] = 0; queue.push(i); }
  for (let q = 0; q < queue.length; q++) {
    const i = queue[q]!;
    const x = i % W, y = (i / W) | 0;
    for (let dy = -1; dy <= 1; dy++)
      for (let dx = -1; dx <= 1; dx++) {
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
        const j = ny * W + nx;
        if (d[j]! > d[i]! + 1) { d[j] = d[i]! + 1; queue.push(j); }
      }
  }
  return d;
}

/**
 * `developed` marks roads (with sidewalks), lots, plaza, landmarks; `water` marks water (padded).
 * Returns trees, bushes and wild flowers.
 */
export function greenery(seed: string, developed: Grid, water: Grid): Prop[] {
  const { width: W, height: H } = developed;
  const dist = distanceField(developed);
  const props: Prop[] = [];
  const add = (kind: Prop['kind'], tx: number, ty: number, look: number): void => {
    props.push({ kind, x: Math.round(tx * TILE), y: Math.round(ty * TILE), look });
  };

  // ---- designed green spaces in the gaps between buildings: orchards (rows of trees) and gardens
  // (rows of flower beds and shrubs), placed on free ground well inside the town ----
  const taken = new Uint8Array(W * H);
  const siteFree = (x: number, y: number, w: number, h: number): boolean => {
    if (x < 1 || y < 1 || x + w >= W - 1 || y + h >= H - 1) return false;
    for (let yy = y; yy < y + h; yy++)
      for (let xx = x; xx < x + w; xx++) {
        if (developed.at(xx, yy) || water.at(xx, yy) || taken[yy * W + xx]) return false;
        if (dist[yy * W + xx]! < 2) return false;
      }
    return true;
  };
  const claim = (x: number, y: number, w: number, h: number): void => {
    for (let yy = y; yy < y + h; yy++) for (let xx = x; xx < x + w; xx++) taken[yy * W + xx] = 1;
  };
  for (let gy = 2; gy < H - 10; gy += 4) {
    for (let gx = 2; gx < W - 14; gx += 4) {
      const roll = rand(seed, 'site', gx, gy);
      if (roll > 0.35) continue;
      const orchard = rand(seed, 'site-kind', gx, gy) < 0.5;
      const w = orchard ? 12 : 8, h = orchard ? 9 : 5;
      if (!siteFree(gx, gy, w, h)) continue;
      // Only inside the town: the middle of the site must be close to development.
      if (dist[(gy + (h >> 1)) * W + gx + (w >> 1)]! > 7) continue;
      claim(gx - 1, gy - 1, w + 2, h + 2);
      if (orchard) {
        const v = 0.05 + rand(seed, 'orch-look', gx, gy) * 0.3; // one or two tree varieties per orchard
        for (let ty = gy + 2; ty < gy + h; ty += 3) for (let tx = gx + 1; tx < gx + w; tx += 3) add('tree', tx + 1, ty + 1, v + rand(seed, 'orch-j', tx, ty) * 0.08);
      } else {
        for (let ty = gy; ty < gy + h; ty += 2)
          for (let tx = gx; tx < gx + w; tx += 2) {
            const k = rand(seed, 'garden', tx, ty);
            add(k < 0.5 ? 'flowerBush' : k < 0.8 ? 'bush' : 'flowers', tx + 1, ty + 1.3, rand(seed, 'garden-look', tx, ty));
          }
      }
    }
  }
  for (let ty = 0; ty < H - 1; ty += 2) {
    for (let tx = 0; tx < W - 1; tx += 2) {
      // The whole 2x2 cell must be clear of development and water.
      let clear = true;
      for (let k = 0; k < 4 && clear; k++) {
        const cx = tx + (k & 1), cy = ty + (k >> 1);
        if (developed.at(cx, cy) || water.at(cx, cy) || taken[cy * W + cx]) clear = false;
      }
      if (!clear) continue;
      const d = dist[ty * W + tx]!;
      const n = noise(seed, 'grove', tx / 7, ty / 7);
      const n2 = noise(seed, 'grove2', tx / 3.2, ty / 3.2);
      let p: number;
      if (d <= 2) p = 0;
      else if (d <= 5) p = 0.04 + 0.55 * Math.max(0, n - 0.55) * 2 * n2;
      else if (d <= 10) p = 0.18 + 0.7 * Math.max(0, n - 0.35);
      else p = 0.78 + 0.2 * n;
      const roll = rand(seed, 'tree', tx, ty);
      if (roll < p) add('tree', tx + 0.5 + rand(seed, 'tfx', tx, ty), ty + 1.5 + rand(seed, 'tfy', tx, ty), rand(seed, 'tlook', tx, ty));
      else if (d >= 3 && d <= 9) {
        const g = rand(seed, 'ground', tx, ty);
        if (g < 0.1) add('bush', tx + 0.5 + rand(seed, 'bfx', tx, ty), ty + 1.4, rand(seed, 'blook', tx, ty));
        else if (g < 0.2) add('flowers', tx + 1, ty + 1.2, rand(seed, 'flook', tx, ty));
      }
    }
  }
  return props;
}
