import { rand } from '../sim/rng';
import { Grid } from './grid';
import { SIZES, type LotKind, type Rect, type Road } from './kinds';

// BUILDING PLOTS are laid along street frontages, not on a grid: walking down each street, on both
// sides, we pick a building type for that spot (downtown shops on main street, apartments and
// houses nearby, smaller homes toward the edge, a villa district on one hill), drop it in, leave a
// small varied gap, and continue. If the chosen building does not fit, a smaller one is tried, so
// rows stay continuous instead of leaving holes.

export interface RawLot extends Rect { id: string; kind: LotKind }

export interface FrontageInput {
  seed: string;
  /** Occupancy: roads (+1 tile of sidewalk), water, plaza and landmarks are already marked. */
  grid: Grid;
  roads: readonly Road[];
  center: { x: number; y: number };
  radius: number;
}

const FALLBACK: Record<LotKind, LotKind[]> = {
  estate: ['estate', 'villa', 'house', 'medium', 'small'],
  villa: ['villa', 'house', 'medium', 'small'],
  house: ['house', 'medium', 'small'],
  apartment: ['apartment', 'medium', 'small'],
  medium: ['medium', 'small'],
  small: ['small'],
  shop: ['shop'],
};

/** A hill district (villas and estates) centered on a spot along one of the side streets. */
export function pickHill(seed: string, roads: readonly Road[], center: { x: number; y: number }, radius: number): { x: number; y: number; r: number } | null {
  const cands = roads.filter((r) => !r.main && Math.hypot(r.x + r.w / 2 - center.x, r.y + r.h / 2 - center.y) > radius * 0.4);
  if (!cands.length) return null;
  const road = cands[Math.floor(rand(seed, 'hill', 'road') * cands.length)]!;
  const t = 0.3 + rand(seed, 'hill', 't') * 0.4;
  return { x: road.x + road.w * (road.horizontal ? t : 0.5), y: road.y + road.h * (road.horizontal ? 0.5 : t), r: 34 + rand(seed, 'hill', 'r') * 10 };
}

export function placeFrontage(inp: FrontageInput): RawLot[] {
  const { seed, grid, center, radius } = inp;
  const out: RawLot[] = [];
  const hill = pickHill(seed, inp.roads, center, radius);
  const R = (...k: (string | number)[]): number => rand(seed, 'front', ...k);

  // Downtown first (main street), then streets in order of distance from the center.
  const order = inp.roads
    .map((r, i) => ({ r, i, d: r.main ? -1 : Math.hypot(r.x + r.w / 2 - center.x, r.y + r.h / 2 - center.y) }))
    .sort((a, b) => a.d - b.d || a.i - b.i);

  const kindAt = (road: Road, px: number, py: number, key: string): LotKind => {
    const roll = R('kind', key);
    if (!road.main && hill && Math.hypot(px - hill.x, py - hill.y) < hill.r) return roll < 0.74 ? 'villa' : roll < 0.82 ? 'estate' : roll < 0.94 ? 'house' : 'medium';
    const d = Math.hypot(px - center.x, (py - center.y) * 1.25) / radius;
    if (road.main) return d < 0.95 ? 'shop' : roll < 0.5 ? 'medium' : 'small';
    if (d < 0.5) return roll < 0.36 ? 'shop' : roll < 0.7 ? 'apartment' : roll < 0.85 ? 'house' : 'medium';
    if (d < 0.9) return roll < 0.18 ? 'apartment' : roll < 0.38 ? 'house' : roll < 0.68 ? 'medium' : 'small';
    return roll < 0.1 ? 'house' : roll < 0.4 ? 'medium' : 'small';
  };

  for (const { r, i } of order) {
    for (const side of [-1, 1] as const) {
      let pos = r.horizontal ? r.x : r.y;
      const end = r.horizontal ? r.x + r.w : r.y + r.h;
      while (pos < end) {
        const mid = (kind: LotKind): { x: number; y: number } => {
          const { w, h } = SIZES[kind];
          return r.horizontal ? { x: pos + w / 2, y: side < 0 ? r.y - 1 - h / 2 : r.y + r.h + 1 + h / 2 } : { x: side < 0 ? r.x - 1 - w / 2 : r.x + r.w + 1 + w / 2, y: pos + h / 2 };
        };
        const wanted = kindAt(r, mid('small').x, mid('small').y, `${i}:${side}:${pos}`);
        let placed: RawLot | undefined;
        for (const kind of FALLBACK[wanted]) {
          const { w, h } = SIZES[kind];
          const rect: Rect = r.horizontal
            ? { x: pos, y: side < 0 ? r.y - 1 - h : r.y + r.h + 1, w, h }
            : { x: side < 0 ? r.x - 1 - w : r.x + r.w + 1, y: pos, w, h };
          // Must stay beside this street (not run past its end) and on free ground.
          const along = r.horizontal ? rect.x + rect.w <= end : rect.y + rect.h <= end;
          if (along && grid.free(rect, 2)) { placed = { ...rect, id: `${kind}:${rect.x},${rect.y}`, kind }; break; }
        }
        if (!placed) { pos += 1; continue; }
        out.push(placed);
        grid.mark(placed);
        const gapRoll = R('gap', i, side, pos);
        const gap = placed.kind === 'shop' ? (gapRoll < 0.8 ? 0 : 1)
          : placed.kind === 'apartment' ? (gapRoll < 0.6 ? 0 : 1)
          : placed.kind === 'estate' || placed.kind === 'villa' ? 1 + Math.floor(gapRoll * 3)
          : gapRoll < 0.35 ? 0 : gapRoll < 0.8 ? 1 : 2;
        pos += (r.horizontal ? placed.w : placed.h) + gap;
      }
    }
  }
  return out;
}
