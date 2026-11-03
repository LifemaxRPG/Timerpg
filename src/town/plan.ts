import { rand } from '../sim/rng';
import { placeLandmarks, type LandmarkPlot } from './landmarks';
import { hitsWater, planWater, waterNear, type WaterBody } from './water';

// The TOWN PLAN is everything about the place that does not depend on the year or on
// time-travel edits: street network, building plots, plaza, forest edge, street furniture.
// It is a pure function of the seed. `layout.ts` then decides who/what occupies the plots.

export interface Rect { x: number; y: number; w: number; h: number }
export interface Road extends Rect { main: boolean; horizontal: boolean }

export type LotKind = 'villa' | 'medium' | 'small' | 'shop';
export const SIZES: Record<LotKind, { w: number; h: number }> = {
  villa: { w: 9, h: 13 },
  medium: { w: 8, h: 8 },
  small: { w: 6, h: 6 },
  shop: { w: 7, h: 13 },
};

export interface Candidate extends Rect {
  id: string;
  kind: LotKind;
  /** Lower = closer to the town center; the town fills from rank 0 outward. */
  rank: number;
}

export type PropKind = 'tree' | 'lamp' | 'bench' | 'bush' | 'flowerBush' | 'flowers' | 'fountain1' | 'fountain2';
/** A decoration. x is the center and y the bottom edge, in pixels. look picks the variant. */
export interface Prop { kind: PropKind; x: number; y: number; look: number }

export interface TownPlan {
  seed: string;
  width: number;
  height: number;
  roads: Road[];
  candidates: Candidate[];
  /** Static decoration (street furniture, plaza, forest edge). */
  props: Prop[];
  mainY: number;
  center: { x: number; y: number };
  plaza: Rect;
  /** Civic landmark plots (reserved; which are open depends on the year, see layout.ts). */
  landmarks: LandmarkPlot[];
  /** River or lake (null if this seed has none); see water.ts. Bridges are in `water.bridges`. */
  water: WaterBody | null;
}

export const TILE = 32;

const cache = new Map<string, TownPlan>();

/** Is tile (tx,ty) on a road or its 1-tile sidewalk? */
export function nearRoad(roads: readonly Road[], tx: number, ty: number): boolean {
  return roads.some((r) => tx >= r.x - 1 && tx < r.x + r.w + 1 && ty >= r.y - 1 && ty < r.y + r.h + 1);
}

export function planTown(seed: string): TownPlan {
  const hit = cache.get(seed);
  if (hit) return hit;
  const R = (...k: (string | number)[]): number => rand(seed, 'plan', ...k);

  const baseWidth = 160 + Math.floor(R('w') * 24);
  const marginL = 6 + Math.floor(R('ml') * 8);
  const marginR = 6 + Math.floor(R('mr') * 8);
  const x0 = marginL + 2;
  const TOP = 4;

  // East-west bands: [lots north][road][lots south]. One main street, one villa district.
  const nBands = 5 + Math.floor(R('bands') * 3);
  const mainIdx = 1 + Math.floor(R('main') * (nBands - 2));
  let villaIdx = Math.floor(R('villa') * (nBands - 1));
  if (villaIdx >= mainIdx) villaIdx++;
  const bandKinds = (i: number): [LotKind, LotKind] => {
    if (i === mainIdx) return ['shop', 'shop'];
    const pick = (side: string): LotKind => (R('kind', i, side) < 0.5 ? 'small' : 'medium');
    return [i === villaIdx ? 'villa' : pick('n'), pick('s')];
  };

  const compose = (width: number, count: number) => {
    const x1 = width - marginR - 2;
    // North-south streets at irregular spacing.
    const vroads: number[] = [];
    for (let x = marginL + 12 + Math.floor(R('v0') * 12), i = 0; x < x1 - 14; i++) {
      vroads.push(x);
      x += 30 + Math.floor(R('vstep', i) * 18);
    }
    const roads: Road[] = [];
    const raw: Omit<Candidate, 'rank'>[] = [];
    let mainY = 0;
    const lotRow = (kind: LotKind, y: number): void => {
      const { w, h } = SIZES[kind];
      for (let x = x0; x + w <= x1; x += w + 1) {
        if (vroads.some((vx) => x < vx + 3 && x + w > vx - 1)) continue;
        raw.push({ id: `${kind}:${x},${y}`, kind, x, y, w, h });
      }
    };
    let y = TOP;
    for (let i = 0; i < count; i++) {
      const [kn, ks] = bandKinds(i);
      const roadY = y + SIZES[kn].h + 1; // 1 tile of sidewalk between lots and road
      roads.push({ x: marginL - 3, y: roadY, w: width - marginL - marginR + 6, h: 2, main: i === mainIdx, horizontal: true });
      if (i === mainIdx) mainY = roadY;
      lotRow(kn, y);
      lotRow(ks, roadY + 3);
      y = roadY + 3 + SIZES[ks].h + 2;
    }
    for (const vx of vroads) roads.push({ x: vx, y: TOP - 2, w: 2, h: y + 2 - TOP - 2, main: false, horizontal: false });
    // Water: plots near it are dropped (the growth loop below makes up the capacity).
    const water = planWater(seed, { width, height: y + 2, marginL, marginR, top: TOP, vroads, roads });
    return { width, vroads, roads, raw: raw.filter((c) => !hitsWater(water, c, 2)), mainY, height: y + 2, water };
  };
  // Grow the town (wider, then taller) until there is comfortable room for every shop and
  // household; the sim never needs more than ~20 shops and ~100 households.
  type Built = ReturnType<typeof compose>;
  const count = (b: Built, ...k: LotKind[]): number => b.raw.filter((c) => k.includes(c.kind)).length;
  let bands = nBands;
  let built = compose(baseWidth, bands);
  for (let extra = 6; count(built, 'shop') - 2 < 24 && extra <= 60; extra += 6) built = compose(baseWidth + extra, bands);
  while (count(built, 'small', 'medium') < 140 && bands < 12) built = compose(built.width, ++bands);
  const { width, vroads, roads, raw, mainY, water } = built;
  let height = built.height;

  // Plaza: replaces the two most central shop plots on the north side of main street.
  const center = { x: (marginL + width - marginR) / 2, y: mainY + 1 };
  // Adjacent plots only (neighbors are exactly one plot + gap apart), so the plaza never spans a street.
  const step = SIZES.shop.w + 1;
  const row = raw.filter((c) => c.kind === 'shop' && c.y + c.h < mainY).sort((a, b) => a.x - b.x);
  let best: [Omit<Candidate, 'rank'>, Omit<Candidate, 'rank'>] | undefined;
  for (let i = 0; i + 1 < row.length; i++) {
    if (row[i + 1]!.x - row[i]!.x !== step) continue;
    const d = Math.abs(row[i]!.x + step - center.x);
    if (!best || d < Math.abs(best[0].x + step - center.x)) best = [row[i]!, row[i + 1]!];
  }
  const [p1, p2] = best!;
  const px = Math.min(p1.x, p2.x);
  const plaza: Rect = { x: px, y: p1.y, w: Math.max(p1.x, p2.x) + SIZES.shop.w - px, h: SIZES.shop.h };
  const afterPlaza = raw.filter((c) => c !== p1 && c !== p2);

  // Civic landmarks replace the plots they cover (never roads/plaza); an extra civic street is
  // appended below the town if one does not fit between the blocks.
  const lm = placeLandmarks(seed, {
    marginL, marginR, top: TOP, width, height, roads, plaza, center, plots: afterPlaza,
    keep: [[['shop'], 22], [['villa'], 6], [['small', 'medium'], 140]],
    blocked: (box) => hitsWater(water, box, 2),
  });
  const kept = lm.plots as typeof afterPlaza;
  roads.push(...lm.roads);
  height = lm.height;
  const landmarks = lm.landmarks;

  const candidates: Candidate[] = kept.map((c) => {
    const cx = c.x + c.w / 2;
    const cy = c.y + c.h / 2;
    return { ...c, rank: Math.hypot(cx - center.x, (cy - center.y) * 1.3) + R('rank', c.id) * 8 };
  }).sort((a, b) => a.rank - b.rank || (a.id < b.id ? -1 : 1));

  // ---------- static decoration ----------
  const props: Prop[] = [];
  const look = (...k: (string | number)[]): number => R('look', ...k);
  const add = (kind: PropKind, tx: number, ty: number, l: number): void => {
    props.push({ kind, x: Math.round(tx * TILE), y: Math.round(ty * TILE), look: l });
  };

  // Street lamps along both sidewalks of each east-west street; benches along main street.
  for (const r of roads.filter((r) => r.horizontal)) {
    const spacing = r.main ? 9 : 14;
    for (const [side, rowY] of [['n', r.y - 1], ['s', r.y + 2]] as const) {
      for (let x = r.x + 2 + Math.floor(R('lamp0', r.y, side) * spacing); x < r.x + r.w - 2; x += spacing) {
        if (vroads.some((vx) => x >= vx - 1 && x < vx + 3) || waterNear(water, x, rowY, 2)) continue;
        add('lamp', x + 0.5, rowY + 1, look('lamp', r.y, side, x));
        if (r.main && look('bench', r.y, side, x) < 0.4 && !vroads.some((vx) => x + 4 >= vx - 1 && x + 4 < vx + 3) && !waterNear(water, x + 4, rowY, 2))
          add('bench', x + 4, rowY + 1, look('bench2', x, side));
      }
    }
  }

  // Plaza: fountain, benches, trees, flower beds.
  const pcx = plaza.x + plaza.w / 2;
  const pby = plaza.y + plaza.h - 1;
  add('fountain2', pcx, pby - 4, look('fountain'));
  add('bench', pcx - 4, pby - 2, 0.1);
  add('bench', pcx + 4, pby - 2, 0.1);
  for (let i = 0; i < 6; i++) {
    add('tree', plaza.x + 1.5 + (i % 3) * ((plaza.w - 3) / 2), plaza.y + 3 + Math.floor(i / 3) * 2.2, look('ptree', i));
  }
  for (let i = 0; i < 4; i++) add('flowerBush', plaza.x + 3 + i * 3.4, pby, look('pfb', i));

  // Forest edge: dense trees outside the town rectangle (but never on a road or sidewalk).
  for (let ty = 0; ty < height; ty += 2) {
    for (let tx = 0; tx < width; tx += 2) {
      const inside = tx >= marginL && tx < width - marginR && ty >= 3 && ty < height - 2;
      if (inside || nearRoad(roads, tx, ty) || nearRoad(roads, tx + 1, ty + 1) || waterNear(water, tx, ty, 3)) continue;
      const r = R('forest', tx, ty);
      if (r < 0.68) add('tree', tx + 0.5 + R('fx', tx, ty), ty + 1.5 + R('fy', tx, ty), look('ftree', tx, ty));
    }
  }

  const plan: TownPlan = { seed, width, height, roads, candidates, props, mainY, center, plaza, landmarks, water };
  cache.set(seed, plan);
  return plan;
}
