import type { IdMapState } from './types';

const VALID_PLAN_ID = /^[a-z][a-z0-9_]*$/;

/** Creates an empty plain-JSON bijective id map between `src/sim/` ids and G007 plan ids. */
export function createIdMap(): IdMapState {
  return {
    simToPlan: {},
    planToSim: {},
  };
}

/**
 * Sanitizes any sim id (e.g. `p:0001`, `Hale's Store`, `place:main-st`) into a strict
 * G007 plan-language identifier (`[a-z][a-z0-9_]*`), guaranteeing bijective round-tripping
 * inside `idMap` with deterministic collision suffixes when needed.
 */
export function registerPlanId(idMap: IdMapState, simId: string, prefix = 'id'): string {
  const existing = idMap.simToPlan[simId];
  if (existing !== undefined) return existing;

  let base = simId
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+/, '')
    .replace(/_+$/, '');
  if (base.length === 0) base = prefix;
  if (!/^[a-z]/.test(base)) base = `${prefix}_${base}`;
  if (!VALID_PLAN_ID.test(base)) base = `${prefix}_x`;

  let candidate = base;
  let suffix = 2;
  while (idMap.planToSim[candidate] !== undefined && idMap.planToSim[candidate] !== simId) {
    candidate = `${base}_${suffix++}`;
  }

  idMap.simToPlan[simId] = candidate;
  idMap.planToSim[candidate] = simId;
  return candidate;
}

/** Looks up the G007 plan-safe id for `simId` (registering lazily if needed). */
export function toPlanId(idMap: IdMapState, simId: string, prefix = 'id'): string {
  return idMap.simToPlan[simId] ?? registerPlanId(idMap, simId, prefix);
}

/** Maps a G007 plan-safe id back to its original `src/sim/` id. */
export function fromPlanId(idMap: IdMapState, planId: string): string {
  return idMap.planToSim[planId] ?? planId;
}

/** Clones an `IdMapState` cleanly for immutable state transitions. */
export function cloneIdMap(idMap: IdMapState): IdMapState {
  return {
    simToPlan: { ...idMap.simToPlan },
    planToSim: { ...idMap.planToSim },
  };
}
