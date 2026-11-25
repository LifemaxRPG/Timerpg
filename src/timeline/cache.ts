import {
  describeDiff,
  diffWorlds,
  fullName,
  isAlive,
  runTimeline,
  worldAt,
  type Id,
  type Intervention as SimIntervention,
  type Timeline as SimTimeline,
  type World,
} from '../sim';
import type { Lot } from '../town/layout';
import { advanceSettlement } from '../town/properties';
import { compileSimInterventions } from './interventions';
import type {
  BuildingDemolishedEntry,
  BuildingNeverBuiltEntry,
  BuildingNewEntry,
  BusinessChangeEntry,
  OwnerChangeEntry,
  PersonChangeEntry,
  PresentSnapshot,
  Timeline,
  TimelineCacheStats,
  TimelineChangesReport,
  TimelineIntervention,
} from './types';

/** Maximum number of cached simulation timelines and present snapshots (LRU cap for phone RAM). */
export const TIMELINE_CACHE_LIMIT = 16;

export interface PresentAtOptions {
  /**
   * When true, asks `runTimeline` to skip intermediate snapshot clones and store only the final
   * target year snapshot (useful for one-shot jumps on tight memory budgets).
   */
  onlyFinalSnapshot?: boolean;
}

interface CachedSimEntry {
  sim: SimTimeline;
  onlyFinal: boolean;
}

const simCache = new Map<string, CachedSimEntry>();
const presentCache = new Map<string, PresentSnapshot>();

let simReplays = 0;
let simCacheHits = 0;
let presentCacheHits = 0;
let presentCacheMisses = 0;

function lruGet<K, V>(map: Map<K, V>, key: K): V | undefined {
  if (!map.has(key)) return undefined;
  const value = map.get(key)!;
  map.delete(key);
  map.set(key, value);
  return value;
}

function lruSet<K, V>(map: Map<K, V>, key: K, value: V, limit = TIMELINE_CACHE_LIMIT): void {
  if (map.has(key)) map.delete(key);
  map.set(key, value);
  while (map.size > limit) {
    const oldest = map.keys().next().value;
    if (oldest === undefined) break;
    map.delete(oldest);
  }
}

/** Clears internal replay and present caches (useful for benchmarks and isolated tests). */
export function clearTimelineCache(): void {
  simCache.clear();
  presentCache.clear();
  simReplays = 0;
  simCacheHits = 0;
  presentCacheHits = 0;
  presentCacheMisses = 0;
}

/** Returns cache hit/miss and replay counters for observability and testing. */
export function getTimelineCacheStats(): TimelineCacheStats {
  return {
    simReplays,
    simCacheHits,
    presentCacheHits,
    presentCacheMisses,
    simCacheEntries: simCache.size,
    presentCacheEntries: presentCache.size,
  };
}

function canonicalSimKey(seed: string, startYear: number, simIvs: readonly SimIntervention[]): string {
  return JSON.stringify([seed, startYear, simIvs]);
}

function canonicalPresentKey(timeline: Timeline, year: number): string {
  // Only interventions dated <= year can affect the world or building report at `year`.
  const activeIvs = timeline.interventions
    .filter((iv) => iv.year <= year)
    .map((iv) => ({
      id: iv.id,
      year: iv.year,
      placeId: iv.placeId,
      kind: iv.kind,
      targetId: iv.targetId,
      otherTargetId: iv.otherTargetId,
      businessId: iv.businessId,
      amount: iv.amount,
      crimeKind: iv.crimeKind,
      atNight: iv.atNight,
      slot: iv.slot,
      buildingLabel: iv.buildingLabel,
      replacementLotLabel: iv.replacementLotLabel,
      causeTag: iv.causeTag,
    }));
  return JSON.stringify([
    timeline.seed,
    timeline.startYear,
    timeline.placeId,
    timeline.player.anchoredPersonId ?? null,
    year,
    activeIvs,
  ]);
}

function hasSnapshotAt(entry: CachedSimEntry, year: number): boolean {
  if (!entry.onlyFinal) return entry.sim.endYear >= year;
  return entry.sim.endYear === year;
}

/**
 * Replays or retrieves from cache a `SimTimeline` covering `[startYear, endYear]`.
 * - Baseline runs (`simIvs.length === 0`) keep full annual snapshots so any altered branch can
 *   resume from a baseline checkpoint at `earliestInterventionYear - 1`.
 * - Extending a shorter cached baseline resumes from its last snapshot via `checkpoint`.
 * - Altered runs resume from the baseline checkpoint at `earliestInterventionYear - 1` and
 *   defer intermediate `advanceSettlement` passes (or use `onlyFinalSnapshot` when requested).
 */
function getOrRunSim(
  seed: string,
  startYear: number,
  endYear: number,
  simIvs: SimIntervention[],
  opts?: { baselineSim?: SimTimeline; onlyFinalSnapshot?: boolean },
): SimTimeline {
  const key = canonicalSimKey(seed, startYear, simIvs);
  const existing = lruGet(simCache, key);
  if (existing && hasSnapshotAt(existing, endYear)) {
    simCacheHits += 1;
    return existing.sim;
  }

  simReplays += 1;

  // Baseline run (no interventions): keep full annual snapshots for checkpointing, and if a
  // shorter baseline is already cached, extend it forward from its last snapshot.
  if (simIvs.length === 0) {
    if (existing && !existing.onlyFinal && existing.sim.endYear < endYear) {
      const ckptYear = existing.sim.endYear;
      const ckptWorld = worldAt(existing.sim, ckptYear);
      const ext = runTimeline({
        seed,
        startYear,
        endYear,
        interventions: [],
        checkpoint: ckptWorld,
        checkpointYear: ckptYear,
      });
      const stitched: SimTimeline = {
        seed,
        startYear,
        endYear,
        snapshots: [...existing.sim.snapshots, ...ext.snapshots.slice(1)],
        chronicle: [...existing.sim.chronicle, ...ext.chronicle],
      };
      lruSet(simCache, key, { sim: stitched, onlyFinal: false });
      return stitched;
    }

    const sim = runTimeline({
      seed,
      startYear,
      endYear,
      interventions: [],
    });
    lruSet(simCache, key, { sim, onlyFinal: false });
    return sim;
  }

  // Altered run: resume from baseline snapshot at the year before the earliest intervention.
  const baseline = opts?.baselineSim ?? getOrRunSim(seed, startYear, endYear, []);
  const earliestIvYear = simIvs.reduce((min, iv) => (iv.year < min ? iv.year : min), simIvs[0]!.year);
  const checkpointYear = Math.max(startYear, Math.min(endYear, earliestIvYear - 1));
  const checkpoint = worldAt(baseline, checkpointYear);
  const settlementBaseline = worldAt(baseline, endYear).settlement;
  const onlyFinal = opts?.onlyFinalSnapshot === true;

  const resumed = runTimeline({
    seed,
    startYear,
    endYear,
    interventions: simIvs,
    checkpoint,
    checkpointYear,
    onlyFinalSnapshot: onlyFinal,
    deferSettlement: !onlyFinal,
    settlementBaseline,
  });

  const prefixChronicle = baseline.chronicle.filter((e) => e.year <= checkpointYear);
  const combined: SimTimeline = onlyFinal
    ? {
        seed,
        startYear,
        endYear,
        snapshots: resumed.snapshots,
        chronicle: [...prefixChronicle, ...resumed.chronicle],
      }
    : {
        seed,
        startYear,
        endYear,
        snapshots: [
          ...baseline.snapshots.slice(0, checkpointYear - startYear),
          ...resumed.snapshots,
        ],
        chronicle: [...prefixChronicle, ...resumed.chronicle],
      };

  lruSet(simCache, key, { sim: combined, onlyFinal });
  return combined;
}

/**
 * Lazily materializes `alteredWorld.settlement` when an earlier year is requested from a cached
 * branch whose intermediate settlements were deferred. Uses baseline's settlement for that year
 * (minus any lots cleared by active arsons <= year) plus a single `advanceSettlement` pass.
 */
function ensureSettlementAt(
  alteredWorld: World,
  baselineWorld: World,
  activeIvs: readonly TimelineIntervention[],
): void {
  if (alteredWorld.settlement) return;
  const burnedBizIds = new Set<Id>();
  const burnedSlots = new Set<string>();
  for (const iv of activeIvs) {
    if (iv.kind === 'arson') {
      if (iv.businessId) burnedBizIds.add(iv.businessId);
      if (iv.slot) burnedSlots.add(iv.slot);
    }
  }
  if (baselineWorld.settlement) {
    alteredWorld.settlement = {
      properties: baselineWorld.settlement.properties
        .filter((l) => !(l.kind === 'shop' && (burnedSlots.has(l.slot) || burnedBizIds.has(l.refId))))
        .map((l) => ({ ...l, heads: l.heads.slice(), residents: l.residents.slice() })),
      roads: baselineWorld.settlement.roads.slice(),
    };
  }
  advanceSettlement(alteredWorld);
}

function personName(id: Id | undefined, ...worlds: World[]): string {
  if (!id) return 'nobody';
  for (const w of worlds) {
    const p = w.people[id];
    if (p) return fullName(p);
  }
  return id;
}

function buildChangesReport(
  baseline: World,
  altered: World,
  activeIvs: readonly TimelineIntervention[],
): TimelineChangesReport {
  const worldDiff = diffWorlds(baseline, altered);

  const erasedPeople: PersonChangeEntry[] = worldDiff.erased.map((id) => {
    const p = baseline.people[id]!;
    return {
      id,
      name: fullName(p),
      birthYear: p.birthYear,
      ...(p.deathYear !== undefined ? { deathYear: p.deathYear } : {}),
      ...(p.deathCause !== undefined ? { deathCause: p.deathCause } : {}),
    };
  });

  const killedEarly: PersonChangeEntry[] = [];
  for (const id of Object.keys(baseline.people).sort()) {
    const bP = baseline.people[id]!;
    const aP = altered.people[id];
    if (!aP) continue;
    const diedEarlier =
      (isAlive(bP) && !isAlive(aP)) ||
      (bP.deathYear !== undefined && aP.deathYear !== undefined && aP.deathYear < bP.deathYear);
    if (diedEarlier) {
      killedEarly.push({
        id,
        name: fullName(aP),
        birthYear: aP.birthYear,
        ...(aP.deathYear !== undefined ? { deathYear: aP.deathYear } : {}),
        ...(aP.deathCause !== undefined ? { deathCause: aP.deathCause } : {}),
      });
    }
  }

  const newPeople: PersonChangeEntry[] = worldDiff.newcomers.map((id) => {
    const p = altered.people[id]!;
    return {
      id,
      name: fullName(p),
      birthYear: p.birthYear,
      ...(p.deathYear !== undefined ? { deathYear: p.deathYear } : {}),
    };
  });

  const bizInterventionsById = new Map<Id, TimelineIntervention[]>();
  for (const iv of activeIvs) {
    if (iv.businessId) {
      const list = bizInterventionsById.get(iv.businessId) ?? [];
      list.push(iv);
      bizInterventionsById.set(iv.businessId, list);
    }
  }

  const businessesNeverOpened: BusinessChangeEntry[] = worldDiff.businessesOnlyInOriginal.map((id) => {
    const b = baseline.businesses[id]!;
    return {
      id,
      name: b.name,
      kind: b.kind,
      foundedYear: b.foundedYear,
      ...(b.owner ? { ownerId: b.owner, ownerName: personName(b.owner, baseline) } : {}),
    };
  });

  const businessesNeverClosed: BusinessChangeEntry[] = worldDiff.closedOnlyInOriginal.map((id) => {
    const b = altered.businesses[id]!;
    const baseB = baseline.businesses[id]!;
    return {
      id,
      name: b.name,
      kind: b.kind,
      foundedYear: b.foundedYear,
      ...(baseB.closedYear !== undefined ? { closedYear: baseB.closedYear } : {}),
      ...(b.owner ? { ownerId: b.owner, ownerName: personName(b.owner, altered) } : {}),
      reason: `Would have closed in ${baseB.closedYear ?? 'baseline'}, but remained open`,
    };
  });

  const businessesClosedEarly: BusinessChangeEntry[] = worldDiff.closedOnlyInAltered.map((id) => {
    const b = altered.businesses[id]!;
    const ivs = bizInterventionsById.get(id) ?? [];
    const arson = ivs.find((iv) => iv.kind === 'arson');
    const demolish = ivs.find((iv) => iv.kind === 'demolish');
    const sabotage = ivs.find((iv) => iv.kind === 'sabotage');
    const reason = arson
      ? `Burned down in ${arson.year} arson`
      : demolish
        ? `Demolished in ${demolish.year}`
        : sabotage
          ? `Sabotaged in ${sabotage.year}`
          : `Closed in ${b.closedYear ?? altered.year}`;
    return {
      id,
      name: b.name,
      kind: b.kind,
      foundedYear: b.foundedYear,
      ...(b.closedYear !== undefined ? { closedYear: b.closedYear } : {}),
      ...(b.owner ? { ownerId: b.owner, ownerName: personName(b.owner, altered, baseline) } : {}),
      reason,
    };
  });

  // Also include businesses that closed in BOTH timelines, but closed significantly EARLIER in altered
  // due to arson/demolish/sabotage (so a 1952 arson on a store that would naturally close in 1998 still shows!).
  const closedEarlySet = new Set(businessesClosedEarly.map((b) => b.id));
  for (const id of Object.keys(baseline.businesses).sort()) {
    if (closedEarlySet.has(id)) continue;
    const bBase = baseline.businesses[id]!;
    const bAlt = altered.businesses[id];
    if (!bAlt) continue;
    if (
      bAlt.closedYear !== undefined &&
      bBase.closedYear !== undefined &&
      bAlt.closedYear < bBase.closedYear
    ) {
      const ivs = bizInterventionsById.get(id) ?? [];
      const arson = ivs.find((iv) => iv.kind === 'arson');
      const demolish = ivs.find((iv) => iv.kind === 'demolish');
      const reason = arson
        ? `Burned down in ${arson.year} arson (originally closed in ${bBase.closedYear})`
        : demolish
          ? `Demolished in ${demolish.year} (originally closed in ${bBase.closedYear})`
          : `Closed early in ${bAlt.closedYear} (originally ${bBase.closedYear})`;
      businessesClosedEarly.push({
        id,
        name: bAlt.name,
        kind: bAlt.kind,
        foundedYear: bAlt.foundedYear,
        closedYear: bAlt.closedYear,
        ...(bAlt.owner ? { ownerId: bAlt.owner, ownerName: personName(bAlt.owner, altered, baseline) } : {}),
        reason,
      });
    }
  }

  const businessesNew: BusinessChangeEntry[] = worldDiff.businessesOnlyInAltered.map((id) => {
    const b = altered.businesses[id]!;
    return {
      id,
      name: b.name,
      kind: b.kind,
      foundedYear: b.foundedYear,
      ...(b.owner ? { ownerId: b.owner, ownerName: personName(b.owner, altered) } : {}),
    };
  });

  const ownerChanges: OwnerChangeEntry[] = [];
  for (const id of Object.keys(baseline.businesses).sort()) {
    const bBase = baseline.businesses[id]!;
    const bAlt = altered.businesses[id];
    if (!bAlt) continue;
    if (bBase.closedYear !== undefined || bAlt.closedYear !== undefined) continue;
    if (bBase.owner !== bAlt.owner) {
      ownerChanges.push({
        businessId: id,
        businessName: bAlt.name,
        ...(bBase.owner ? { beforeOwnerId: bBase.owner } : {}),
        beforeOwnerName: personName(bBase.owner, baseline, altered),
        ...(bAlt.owner ? { afterOwnerId: bAlt.owner } : {}),
        afterOwnerName: personName(bAlt.owner, altered, baseline),
      });
    }
  }

  // Buildings diff: compare `world.settlement.properties` + explicit arson/demolish/prevent_building acts.
  const baseLots = new Map<string, Lot>((baseline.settlement?.properties ?? []).map((l) => [l.slot, l]));
  const altLots = new Map<string, Lot>((altered.settlement?.properties ?? []).map((l) => [l.slot, l]));

  const neverBuilt: BuildingNeverBuiltEntry[] = [];
  const demolishedOrReplaced: BuildingDemolishedEntry[] = [];
  const newlyBuilt: BuildingNewEntry[] = [];
  const reportedDemolishedSlots = new Set<string>();

  // Explicit `arson` / `demolish` interventions mapped to lots & businesses first so cleared
  // lots are reported in `demolishedOrReplaced` rather than misclassified as `neverBuilt`.
  for (const iv of activeIvs) {
    if (iv.kind !== 'arson' && iv.kind !== 'demolish') continue;
    const baseLot =
      (iv.slot ? baseLots.get(iv.slot) : undefined) ??
      (iv.businessId ? [...baseLots.values()].find((l) => l.kind === 'shop' && l.refId === iv.businessId) : undefined);
    const altLot = baseLot ? altLots.get(baseLot.slot) : iv.slot ? altLots.get(iv.slot) : undefined;
    const baseBiz = iv.businessId ? baseline.businesses[iv.businessId] : undefined;

    const slot = baseLot?.slot ?? iv.slot ?? `lot:${iv.businessId ?? iv.id}`;
    const beforeLabel = baseLot?.label ?? baseBiz?.name ?? iv.buildingLabel ?? 'Building';
    const afterLabel =
      iv.replacementLotLabel ??
      (altLot && altLot.vacantSince === undefined && altLot.refId !== iv.businessId
        ? altLot.label
        : 'Vacant / cleared lot');

    demolishedOrReplaced.push({
      slot,
      beforeLabel,
      afterLabel,
      kind: baseLot?.kind ?? (iv.businessId ? 'shop' : 'landmark'),
      cause: iv.kind === 'arson' ? `Burned down in ${iv.year} arson` : `Demolished in ${iv.year}`,
      year: iv.year,
      ...(iv.businessId ? { businessId: iv.businessId } : {}),
    });
    reportedDemolishedSlots.add(slot);
  }

  for (const [slot, lot] of [...baseLots.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    if (reportedDemolishedSlots.has(slot)) continue;
    if (!altLots.has(slot)) {
      neverBuilt.push({
        slot,
        label: lot.label,
        kind: lot.kind,
        ...(lot.builtYear !== undefined ? { originalBuiltYear: lot.builtYear } : {}),
      });
    }
  }

  for (const [slot, lot] of [...altLots.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    if (!baseLots.has(slot)) {
      newlyBuilt.push({
        slot,
        label: lot.label,
        kind: lot.kind,
        ...(lot.builtYear !== undefined ? { builtYear: lot.builtYear } : {}),
      });
    }
  }

  // Explicit `prevent_building` interventions
  for (const iv of activeIvs) {
    if (iv.kind === 'prevent_building') {
      neverBuilt.push({
        slot: iv.slot ?? `landmark:${iv.id}`,
        label: iv.buildingLabel ?? iv.summary ?? 'Prevented building',
        kind: 'landmark',
        cause: iv.summary ?? `Prevented in ${iv.year}`,
      });
    }
  }

  // Indirect lot replacements (shop lot became vacant or changed tenant compared to baseline)
  for (const [slot, bLot] of [...baseLots.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    if (reportedDemolishedSlots.has(slot)) continue;
    const aLot = altLots.get(slot);
    if (!aLot) continue;
    if (bLot.kind === 'shop' && bLot.vacantSince === undefined) {
      if (aLot.vacantSince !== undefined) {
        demolishedOrReplaced.push({
          slot,
          beforeLabel: bLot.label,
          afterLabel: `Vacant shopfront (since ${aLot.vacantSince})`,
          kind: 'shop',
          cause: 'Business closed in altered timeline',
          year: aLot.vacantSince,
          businessId: bLot.refId,
        });
      } else if (aLot.refId !== bLot.refId) {
        demolishedOrReplaced.push({
          slot,
          beforeLabel: bLot.label,
          afterLabel: aLot.label,
          kind: 'shop',
          cause: 'Replaced by another business',
          businessId: bLot.refId,
        });
      }
    }
  }

  const mayorChange = {
    changed: worldDiff.mayor.before !== worldDiff.mayor.after,
    ...(worldDiff.mayor.before ? { beforeId: worldDiff.mayor.before } : {}),
    beforeName: personName(worldDiff.mayor.before, baseline, altered),
    ...(worldDiff.mayor.after ? { afterId: worldDiff.mayor.after } : {}),
    afterName: personName(worldDiff.mayor.after, altered, baseline),
  };

  const richestChange = {
    changed: worldDiff.richest.before !== worldDiff.richest.after,
    ...(worldDiff.richest.before ? { beforeId: worldDiff.richest.before } : {}),
    beforeName: personName(worldDiff.richest.before, baseline, altered),
    ...(worldDiff.richest.after ? { afterId: worldDiff.richest.after } : {}),
    afterName: personName(worldDiff.richest.after, altered, baseline),
  };

  const summary = describeDiff(worldDiff, baseline, altered);
  for (const k of killedEarly) {
    summary.push(`${k.name} died early (${k.deathYear ?? altered.year}, ${k.deathCause ?? 'altered fate'})`);
  }
  for (const oc of ownerChanges) {
    summary.push(`${oc.businessName} owner changed: ${oc.beforeOwnerName} -> ${oc.afterOwnerName}`);
  }
  for (const b of demolishedOrReplaced) {
    summary.push(`Lot ${b.slot} (${b.beforeLabel}): ${b.cause} -> now ${b.afterLabel}`);
  }
  for (const nb of neverBuilt) {
    summary.push(`Never built: ${nb.label}${nb.cause ? ` (${nb.cause})` : ''}`);
  }

  const hasChanges =
    erasedPeople.length > 0 ||
    killedEarly.length > 0 ||
    newPeople.length > 0 ||
    businessesNeverOpened.length > 0 ||
    businessesNeverClosed.length > 0 ||
    businessesClosedEarly.length > 0 ||
    businessesNew.length > 0 ||
    ownerChanges.length > 0 ||
    mayorChange.changed ||
    richestChange.changed ||
    neverBuilt.length > 0 ||
    demolishedOrReplaced.length > 0 ||
    newlyBuilt.length > 0 ||
    worldDiff.population.before !== worldDiff.population.after;

  return {
    year: altered.year,
    hasChanges,
    population: worldDiff.population,
    erasedPeople,
    killedEarly,
    newPeople,
    businessesNeverOpened,
    businessesNeverClosed,
    businessesClosedEarly,
    businessesNew,
    ownerChanges,
    mayorChange,
    richestChange,
    buildings: {
      neverBuilt,
      demolishedOrReplaced,
      newlyBuilt,
    },
    worldDiff,
    summary,
  };
}

/**
 * Replays the simulation for `timeline` up to `year`, returning the world and a structured
 * `changes` report built on `src/sim/diff.ts`.
 *
 * Cached by `(seed, startYear, active interventions <= year, year)` so repeated jumps or queries
 * cost zero additional replays.
 */
export function presentAt(
  timeline: Timeline,
  year: number,
  options?: PresentAtOptions,
): PresentSnapshot {
  if (year < timeline.startYear) {
    throw new Error(`year ${year} is before timeline startYear ${timeline.startYear}`);
  }

  const key = canonicalPresentKey(timeline, year);
  const hit = lruGet(presentCache, key);
  if (hit) {
    presentCacheHits += 1;
    return { ...hit, fromCache: true };
  }

  presentCacheMisses += 1;
  const baselineSim = getOrRunSim(timeline.seed, timeline.startYear, year, []);
  const activeSimIvs = compileSimInterventions(timeline, year);
  const alteredSim =
    activeSimIvs.length === 0
      ? baselineSim
      : getOrRunSim(timeline.seed, timeline.startYear, year, activeSimIvs, {
          baselineSim,
          onlyFinalSnapshot: options?.onlyFinalSnapshot,
        });

  const baselineWorld = worldAt(baselineSim, year);
  const alteredWorld = worldAt(alteredSim, year);
  const activeIvs = timeline.interventions.filter((iv) => iv.year <= year);
  if (alteredSim !== baselineSim) {
    ensureSettlementAt(alteredWorld, baselineWorld, activeIvs);
  }
  const changes = buildChangesReport(baselineWorld, alteredWorld, activeIvs);

  const snapshot: PresentSnapshot = {
    seed: timeline.seed,
    year,
    placeId: timeline.placeId,
    world: alteredWorld,
    changes,
    fromCache: false,
  };

  lruSet(presentCache, key, snapshot);
  return snapshot;
}

/**
 * Internal helper returning the full simulated `SimTimeline` (including `chronicle` & all years)
 * through the same replay cache.
 */
export function getSimulatedTimeline(timeline: Timeline, endYear: number): SimTimeline {
  const baselineSim = getOrRunSim(timeline.seed, timeline.startYear, endYear, []);
  const activeSimIvs = compileSimInterventions(timeline, endYear);
  if (activeSimIvs.length === 0) return baselineSim;
  return getOrRunSim(timeline.seed, timeline.startYear, endYear, activeSimIvs, { baselineSim });
}
