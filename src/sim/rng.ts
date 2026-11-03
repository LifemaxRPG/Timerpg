// Keyed deterministic randomness.
//
// Instead of one global random stream (where changing one event shifts every later
// roll), every random decision is derived from (seed, ...keys), e.g.
// rand(seed, 1962, 'death', 'p:12'). Unrelated parts of the town therefore roll the
// same dice in every timeline, so a time-travel edit only changes what it actually
// causes. That is what makes the "butterfly effect" readable instead of chaotic noise.

function xmur3(str: string): () => number {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(h ^ str.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return () => {
    h = Math.imul(h ^ (h >>> 16), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return (h ^= h >>> 16) >>> 0;
  };
}

function mulberry32(a: number): () => number {
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export type Key = string | number;
export type Rng = () => number;

/** A random stream unique to (seed, keys). */
export function rngFor(seed: string, ...keys: Key[]): Rng {
  return mulberry32(xmur3([seed, ...keys].join('|'))());
}

/** One float in [0,1) unique to (seed, keys). */
export function rand(seed: string, ...keys: Key[]): number {
  return rngFor(seed, ...keys)();
}

export function pick<T>(r: Rng, items: readonly T[]): T {
  return items[Math.floor(r() * items.length)] as T;
}

export const clamp01 = (n: number): number => Math.min(1, Math.max(0, n));
