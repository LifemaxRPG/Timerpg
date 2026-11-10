import { rand } from '../sim/rng';

/**
 * Shared types for building art. A building is a stack of sprite layers (bottom-up), either a
 * single big sprite (a house) or modular floors (ground floor + middle floors + roof).
 * Everything here is pure: sprite keys refer to manifest entries written by
 * scripts/extract-assets.mjs, and sizes are kept here so the planner and tests do not need the PNGs.
 */
export interface Layer {
  /** Manifest key (e.g. "house_terraced_1"). */
  key: string;
  /** Sprite width and height in pixels. */
  w: number;
  h: number;
  /** Pixels from the bottom edge of the lot up to this layer's bottom. */
  y: number;
  /** Horizontal offset from the lot center, pixels. */
  dx: number;
}

export interface BuildingArt {
  /** Modular shop shell colour, retained when the tenant changes. */
  colour?: Colour;
  layers: Layer[];
  /** Overall width and height in pixels. */
  w: number;
  h: number;
  /** How many tile rows (from the bottom) are solid for walking collision. */
  footRows: number;
  /** Households that can live in the building (1 except apartments). */
  capacity: number;
}

export type Colour = 'red' | 'blue' | 'gray';
export const COLOURS: readonly Colour[] = ['red', 'blue', 'gray'];

/** Modular sprites come in thirds: first third red, then blue, then gray. n is 1-based. */
export const colourOf = (n: number, total: number): Colour => COLOURS[Math.min(2, Math.floor(((n - 1) * 3) / total))]!;

/**
 * Stable choice by "rendezvous hashing": each candidate scores a keyed hash and the best wins.
 * When the candidate set changes (an era starts) only the picks that must change do, so houses
 * do not reshuffle from one year to the next.
 */
export function pickStable<T extends { id: string }>(items: readonly T[], look: number, salt: string): T {
  let best = items[0]!;
  let bestScore = -1;
  for (const it of items) {
    const s = rand('building', salt, look.toFixed(6), it.id);
    if (s > bestScore) {
      bestScore = s;
      best = it;
    }
  }
  return best;
}

export const layer = (key: string, w: number, h: number, y = 0, dx = 0): Layer => ({ key, w, h, y, dx });

/** Stack layers bottom-up (each starts where the previous ends). */
export function stack(parts: readonly { key: string; w: number; h: number; dx?: number; rise?: number }[]): { layers: Layer[]; w: number; h: number } {
  let y = 0;
  let w = 0;
  const layers = parts.map((p) => {
    const l = layer(p.key, p.w, p.h, y, p.dx ?? 0);
    // `rise` is how far the next layer starts above this one. It is less than `h` for sprites whose
    // top is sign overhang (the sign is drawn over the layer above, which sits on the real front).
    y += p.rise ?? p.h;
    w = Math.max(w, p.w);
    return l;
  });
  return { layers, w, h: y };
}
