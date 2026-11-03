// ERA: what the town's street furniture and decoration look like in a given year. Pure and tiny so
// the renderer, the facade builder and tests share one source of truth.

/** Rooftop billboards only appear once advertising took off. */
export const BILLBOARDS_FROM = 1975;

/**
 * Lamp sprites in manifest order (scripts/extract-assets.mjs, group `lamp`: Street_Lamp 1..5):
 * 0,1 ornate double-arm posts, 2,3 modern cobra heads, 4 old globe lamp.
 */
export function lampIndexes(year: number): number[] {
  if (year < 1970) return [0, 1, 4];
  if (year < 1990) return [0, 1, 2, 4];
  return [0, 1, 2, 3, 4];
}

/**
 * Tree sprites in manifest order (group `tree`: Tree 1,2,3,4,5,6,8,11,12): 0,1 and 4,5 are young/small
 * trees, 2,3,6 are full-grown, 7,8 are big spreading trees. Trees in town grow up over the decades.
 */
export function treeIndexes(year: number): number[] {
  if (year < 1965) return [0, 1, 4, 5];
  if (year < 1990) return [0, 1, 2, 3, 4, 5, 6];
  return [0, 1, 2, 3, 4, 5, 6, 7, 8];
}

/** Picks one entry of `list` (clamped to what exists) using a stable 0..1 value. */
export function pickIndex(list: number[], count: number, look: number): number {
  const ok = list.filter((i) => i < count);
  return ok[Math.min(ok.length - 1, Math.floor(look * ok.length))] ?? 0;
}
