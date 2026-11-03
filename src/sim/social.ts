import { rand } from './rng';
import type { Id, Person, World } from './types';

export const pairKey = (a: Id, b: Id): string => (a < b ? `${a}|${b}` : `${b}|${a}`);

const areKin = (a: Person, b: Person): boolean =>
  a.spouse === b.id || a.parents.includes(b.id) || b.parents.includes(a.id) ||
  a.parents.some((p) => b.parents.includes(p));

/**
 * How much `a` and `b` like each other, roughly -0.5..+1.5.
 * The baseline is a pure function of (seed, pair) so it needs no storage and is identical
 * in every timeline; only deliberate changes (`world.bonds`) are stored.
 */
export function affinity(w: World, aId: Id, bId: Id): number {
  const a = w.people[aId];
  const b = w.people[bId];
  if (!a || !b) return 0;
  const base = rand(w.seed, 'affinity', pairKey(aId, bId)) - 0.5;
  return base + (areKin(a, b) ? 0.5 : 0) + (w.bonds[pairKey(aId, bId)] ?? 0);
}

export const clampRep = (n: number): number => Math.min(1, Math.max(-1, n));
