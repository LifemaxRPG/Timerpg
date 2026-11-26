/**
 * G020: Time machine & jump integration for the playable loop (`src/loop/`).
 *
 * Bridges the real G017/G018 timeline engine (`src/timeline/`: `createTimeline`,
 * `branch`, `presentAt`, `addCarriedItem`, `recordPlayerInteraction`,
 * `inspectCarriedItems`, `npcMemoryOfPlayer`, `recordTimelineCrime`,
 * `caseStatusAt`, `looseThreads`) and G008 cross-era forensics
 * (`reexamineColdCases`, `detectTimeTravelAnomalies`) directly into `LoopSim`.
 *
 * Plain JSON only. Deterministic. Zero edits required in `src/timeline/`.
 */

import {
  createCrimeState,
  detectTimeTravelAnomalies,
  ingestPoliceReport,
  reexamineColdCases,
  type CrimeAccount,
  type CrimeEvent,
  type CrimeKind,
  type CrimeState,
  type EvidenceTrace,
  type IdentityFileRecord,
  type PoliceCase,
  type PoliceReport,
} from '../crime';
import { fullName, isAlive, type Business, type Id, type Person, type World } from '../sim';
import {
  addCarriedItem,
  branch,
  caseStatusAt,
  createTimeline,
  inspectCarriedItems,
  looseThreads,
  npcMemoryOfPlayer,
  presentAt,
  recordPlayerInteraction,
  recordTimelineCrime,
  severityForTimelineCrime,
  toCourtCrimeKind,
  type CarriedItemStatus,
  type CaseStatusReport,
  type CausalAnchorTag,
  type LooseThread,
  type PresentSnapshot,
  type Timeline,
  type TimelineCrimeKind,
  type TimelineWitnessRecord,
} from '../timeline';
import type { Lot } from '../town/layout';
import { createIdMap, registerPlanId } from './ids';
import type {
  AttitudeStance,
  CrimeMetaRecord,
  IdMapState,
  JumpInput,
  JumpResult,
  LoopCarriedItem,
  LoopCrossEraMatch,
  LoopGazetteEntry,
  LoopOfficer,
  LoopPersonMeta,
  LoopPlaceKind,
  LoopPlaceRecord,
  LoopSim,
  LoopTimeMachineState,
  OfficerPlanDirective,
  PlayerFamiliarity,
  StolenItemRecord,
  TimeMachineViewResult,
} from './types';
import type { PoliceState } from '../police/types';

/** Starting nuclear fuel budget: 4 trips (CLAUDE.md, docs/IDEAS.md). */
export const DEFAULT_TIME_MACHINE_FUEL = 4;

export interface TimeJumpPoliceDeps {
  createLoopPolice: (params: {
    seed: string;
    year: number;
    startDay: number;
    startHour: number;
    places: Record<string, LoopPlaceRecord>;
    streetGraph: LoopSim['streetGraph'];
    peopleCount: number;
    crimeState: CrimeState;
    idMap: IdMapState;
  }) => { policeState: PoliceState; officers: Record<string, LoopOfficer> };
  stepArrivalPolice: (sim: LoopSim) => { sim: LoopSim; policePlans: OfficerPlanDirective[] };
}

function defaultJurisdictionId(sim: LoopSim): string {
  const place = Object.values(sim.places).sort((a, b) => (a.id < b.id ? -1 : 1))[0];
  return place?.jurisdictionId ?? sim.streetGraph.nodes[0]?.jurisdictionId ?? 'jur:capital';
}

function defaultTownId(sim: LoopSim): string {
  const place = Object.values(sim.places).sort((a, b) => (a.id < b.id ? -1 : 1))[0];
  return place?.townId ?? 'capital';
}

export function resolveMachinePlaceId(places: Record<string, LoopPlaceRecord>): string {
  const list = Object.values(places).sort((a, b) => (a.id < b.id ? -1 : 1));
  const explicit = list.find((p) => p.kind === 'time_machine');
  if (explicit) return explicit.id;
  const courthouse = list.find((p) => p.kind === 'courthouse');
  if (courthouse) return courthouse.id;
  const station = list.find((p) => p.kind === 'police_station');
  if (station) return station.id;
  return list[0]?.id ?? 'place:time_machine';
}

export function ensureTimeMachine(
  sim: LoopSim,
  overrides?: { initialFuel?: number; maxFuel?: number; machinePlaceId?: string },
): LoopTimeMachineState {
  if (sim.timeMachine) return sim.timeMachine;
  const fuel = Math.max(0, Math.round(overrides?.initialFuel ?? DEFAULT_TIME_MACHINE_FUEL));
  const maxFuel = Math.max(fuel, Math.round(overrides?.maxFuel ?? DEFAULT_TIME_MACHINE_FUEL));
  return {
    fuelRemaining: fuel,
    maxFuel,
    machinePlaceId: overrides?.machinePlaceId ?? resolveMachinePlaceId(sim.places),
    originYear: sim.year,
    currentYear: sim.year,
    jumpHistory: [],
    looseThreads: [],
    looseThreadFacts: [],
  };
}

function cloneIdentityFiles(
  src?: Record<Id, IdentityFileRecord[]>,
): Record<Id, IdentityFileRecord[]> {
  const out: Record<Id, IdentityFileRecord[]> = {};
  if (!src) return out;
  for (const [pid, recs] of Object.entries(src)) {
    out[pid] = recs.map((r) => ({
      ...r,
      ...(r.plateNumbers ? { plateNumbers: r.plateNumbers.slice() } : {}),
      ...(r.serialNumbers ? { serialNumbers: r.serialNumbers.slice() } : {}),
    }));
  }
  return out;
}

function mergeIdentityFiles(
  base: Record<Id, IdentityFileRecord[]>,
  incoming?: Record<Id, IdentityFileRecord[]>,
): Record<Id, IdentityFileRecord[]> {
  const out = cloneIdentityFiles(base);
  if (!incoming) return out;
  for (const [pid, recs] of Object.entries(incoming)) {
    const existing = out[pid] ? out[pid]!.slice() : [];
    for (const r of recs) {
      const idx = existing.findIndex(
        (e) =>
          e.jurisdictionId === r.jurisdictionId &&
          e.enrolledYear === r.enrolledYear &&
          e.scope === r.scope,
      );
      const copy: IdentityFileRecord = {
        ...r,
        ...(r.plateNumbers ? { plateNumbers: r.plateNumbers.slice() } : {}),
        ...(r.serialNumbers ? { serialNumbers: r.serialNumbers.slice() } : {}),
      };
      if (idx >= 0) {
        existing[idx] = {
          ...existing[idx]!,
          ...copy,
          hasPrints: existing[idx]!.hasPrints || copy.hasPrints,
          hasDna: existing[idx]!.hasDna || copy.hasDna,
        };
      } else {
        existing.push(copy);
      }
    }
    out[pid] = existing;
  }
  return out;
}

export function ensureTimeline(
  sim: LoopSim,
  options?: { targetYearHint?: number; startYear?: number; placeId?: string; birthYear?: number },
): Timeline {
  if (sim.timeline) return sim.timeline;
  const startYear =
    options?.startYear ??
    Math.min(1950, sim.year, options?.targetYearHint ?? sim.year);
  const placeId = options?.placeId ?? defaultTownId(sim);
  const playerRec = sim.world.people[sim.playerId];
  const birthYear = options?.birthYear ?? playerRec?.birthYear ?? sim.year - 26;
  const idFiles = cloneIdentityFiles(sim.crimeState.identityFiles);
  const playerFiles = idFiles[sim.playerId] ?? [];
  const hasPrints = playerFiles.some((r) => r.hasPrints);
  const hasDna = playerFiles.some((r) => r.hasDna);
  return createTimeline({
    seed: sim.seed,
    startYear,
    placeId,
    player: {
      playerId: sim.playerId,
      originYear: sim.timeMachine?.originYear ?? sim.year,
      birthYear,
      homePlaceId: placeId,
      records: {
        fingerprintsOnFile: hasPrints,
        dnaOnFile: hasDna,
      },
    },
    identityFiles: idFiles,
  });
}

function collectCombinedTraces(
  sim: LoopSim,
  crimeId: string,
  policeCase?: PoliceCase,
): EvidenceTrace[] {
  const byId = new Map<string, EvidenceTrace>();
  const sceneTraces = sim.crimeState.traces?.[crimeId] ?? [];
  for (const t of sceneTraces) {
    byId.set(t.id, { ...t });
  }
  const caseTraces = policeCase?.traces ?? [];
  for (const t of caseTraces) {
    const prev = byId.get(t.id);
    if (!prev || (t.collected && !prev.collected) || t.quality > prev.quality) {
      byId.set(t.id, { ...t });
    }
  }
  return Array.from(byId.values()).sort((a, b) => (a.id < b.id ? -1 : 1));
}

function collectTimelineWitnesses(
  sim: LoopSim,
  crimeId: string,
  policeCase?: PoliceCase,
): TimelineWitnessRecord[] {
  const byPerson = new Map<string, TimelineWitnessRecord>();
  const ledger = sim.crimeState.ledger[crimeId] ?? {};
  for (const [pid, acc] of Object.entries(ledger)) {
    if (pid === sim.playerId) continue;
    byPerson.set(pid, {
      personId: pid,
      name: sim.peopleMeta[pid]?.name ?? (sim.world.people[pid] ? fullName(sim.world.people[pid]!) : pid),
      confidence: acc.confidence,
      identifiedCulprit: acc.identifiedCulpritId === sim.playerId,
    });
  }
  if (policeCase) {
    for (const r of policeCase.reports) {
      if (r.reporterId === sim.playerId) continue;
      const prev = byPerson.get(r.reporterId);
      if (!prev || r.confidence > prev.confidence) {
        byPerson.set(r.reporterId, {
          personId: r.reporterId,
          name:
            sim.peopleMeta[r.reporterId]?.name ??
            (sim.world.people[r.reporterId] ? fullName(sim.world.people[r.reporterId]!) : r.reporterId),
          confidence: r.confidence,
          identifiedCulprit: r.identifiedCulpritId === sim.playerId,
        });
      }
    }
  }
  return Array.from(byPerson.values()).sort((a, b) => (a.personId < b.personId ? -1 : 1));
}

/**
 * Mirrors current `LoopSim` state (identity files, carried items, NPC interactions,
 * and active crimes with their physical traces) into `timeline` non-destructively.
 */
export function syncSimToTimeline(sim: LoopSim, targetYearHint?: number): Timeline {
  let tl = ensureTimeline(sim, { targetYearHint });

  // 1. Merge forensic identity files (preserving enrolledYear across all eras)
  const mergedFiles = mergeIdentityFiles(tl.identityFiles, sim.crimeState.identityFiles);
  const playerFiles = mergedFiles[sim.playerId] ?? [];
  tl = {
    ...tl,
    identityFiles: mergedFiles,
    player: {
      ...tl.player,
      records: {
        ...tl.player.records,
        fingerprintsOnFile:
          !tl.player.selfErased &&
          (tl.player.records.fingerprintsOnFile || playerFiles.some((r) => r.hasPrints)),
        dnaOnFile:
          !tl.player.selfErased &&
          (tl.player.records.dnaOnFile || playerFiles.some((r) => r.hasDna)),
      },
    },
  };

  // 2. Sync carried items (Rule 1: unanchored inventory travels with provenance)
  const carried = sim.carriedItems ?? [];
  for (const item of carried) {
    tl = addCarriedItem(tl, {
      id: item.id,
      label: item.label,
      acquiredYear: item.acquiredYear,
      acquiredPlaceId: item.acquiredPlaceId,
      ...(item.ownerId ? { makerId: item.ownerId } : {}),
      ...(item.ownerName ? { makerName: item.ownerName } : {}),
      ...(item.businessId ? { businessId: item.businessId } : {}),
      ...(item.businessName ? { businessName: item.businessName } : {}),
      provenanceNote: item.provenanceNote,
    });
  }

  // 3. Sync positive/familiar NPC interactions into timeline branch memory (Rule 2)
  const existingInteractionIds = new Set(tl.player.interactions.map((ix) => ix.id));
  for (const [pid, fam] of Object.entries(sim.familiarity)) {
    for (let i = 0; i < fam.positiveFacts.length; i++) {
      const ixId = `ix:${sim.year}:${pid}:${i}`;
      if (existingInteractionIds.has(ixId)) continue;
      existingInteractionIds.add(ixId);
      tl = recordPlayerInteraction(tl, {
        id: ixId,
        year: sim.year,
        placeId: tl.placeId,
        npcId: pid,
        summary: fam.positiveFacts[i]!,
      });
    }
  }

  // 4. Sync all crimes currently present in `sim.crimeState.crimes`
  const jurId = defaultJurisdictionId(sim);
  for (const [crimeId, crime] of Object.entries(sim.crimeState.crimes)) {
    const existingRecord = tl.crimes[crimeId];
    const policeCase =
      sim.crimeState.jurisdictions[jurId]?.cases[crimeId] ??
      Object.values(sim.crimeState.jurisdictions).find((j) => j.cases[crimeId])?.cases[crimeId];
    const traces = collectCombinedTraces(sim, crimeId, policeCase);
    const witnesses = collectTimelineWitnesses(sim, crimeId, policeCase);
    const place = sim.places[crime.placeId];
    const kind: TimelineCrimeKind = existingRecord?.kind ?? crime.kind;
    const mergedTraces = mergeTraceLists(existingRecord?.traces ?? [], traces);
    const mergedWitnesses = mergeWitnessLists(existingRecord?.witnesses ?? [], witnesses);
    tl = recordTimelineCrime(tl, {
      id: crimeId,
      kind,
      year: existingRecord?.year ?? crime.when.year,
      day: existingRecord?.day ?? crime.when.day,
      placeId: existingRecord?.placeId ?? tl.placeId,
      culpritId: crime.culpritId,
      ...(crime.victimId !== undefined
        ? { victimId: crime.victimId }
        : existingRecord?.victimId !== undefined
          ? { victimId: existingRecord.victimId }
          : {}),
      ...(place?.businessId !== undefined
        ? { businessId: place.businessId }
        : existingRecord?.businessId !== undefined
          ? { businessId: existingRecord.businessId }
          : {}),
      severity: crime.severity,
      reported: Boolean(policeCase) || Boolean(existingRecord?.reported),
      witnesses: mergedWitnesses,
      traces: mergedTraces,
      ...(policeCase ? { policeCase } : existingRecord?.policeCase ? { policeCase: existingRecord.policeCase } : {}),
      summary:
        existingRecord?.summary ??
        sim.crimeMeta[crimeId]?.itemLabel ??
        `${kind} at ${place?.name ?? crime.placeId} in ${crime.when.year}`,
    });
  }

  return tl;
}

function mergeTraceLists(a: readonly EvidenceTrace[], b: readonly EvidenceTrace[]): EvidenceTrace[] {
  const byId = new Map<string, EvidenceTrace>();
  for (const t of a) byId.set(t.id, { ...t });
  for (const t of b) {
    const prev = byId.get(t.id);
    if (!prev || (t.collected && !prev.collected) || t.quality > prev.quality) {
      byId.set(t.id, { ...t });
    }
  }
  return Array.from(byId.values()).sort((x, y) => (x.id < y.id ? -1 : 1));
}

function mergeWitnessLists(
  a: readonly TimelineWitnessRecord[],
  b: readonly TimelineWitnessRecord[],
): TimelineWitnessRecord[] {
  const byId = new Map<string, TimelineWitnessRecord>();
  for (const w of a) byId.set(w.personId, { ...w });
  for (const w of b) {
    const prev = byId.get(w.personId);
    if (!prev || w.confidence > prev.confidence) {
      byId.set(w.personId, { ...w });
    }
  }
  return Array.from(byId.values()).sort((x, y) => (x.personId < y.personId ? -1 : 1));
}

export function addLoopCarriedItem(
  carried: readonly LoopCarriedItem[] | undefined,
  item: LoopCarriedItem,
): LoopCarriedItem[] {
  const list = (carried ?? []).filter((c) => c.id !== item.id);
  list.push({ ...item });
  return list;
}

export function removeLoopCarriedItem(
  carried: readonly LoopCarriedItem[] | undefined,
  itemId: string,
): LoopCarriedItem[] | undefined {
  if (!carried) return undefined;
  const next = carried.filter((c) => c.id !== itemId);
  return next;
}

function mapBusinessKindToPlaceKind(bizKind: string): LoopPlaceKind {
  const k = bizKind.toLowerCase();
  if (k.includes('pawn')) return 'pawn_shop';
  if (k.includes('market') || k.includes('grocer') || k.includes('farm') || k.includes('produce')) {
    return 'market';
  }
  if (
    k.includes('store') ||
    k.includes('shop') ||
    k.includes('baker') ||
    k.includes('diner') ||
    k.includes('cafe') ||
    k.includes('barber') ||
    k.includes('tailor') ||
    k.includes('hardware') ||
    k.includes('pharmacy')
  ) {
    return 'general_store';
  }
  return 'workplace';
}

/**
 * Resolves a loop `placeId` (and optional explicit `businessId`) to a concrete open
 * `src/sim` business & settlement lot in `year`, so burning a store either in a
 * `presentAt`-projected town or a stub town updates the real timeline history & Gazette.
 */
export function resolveTargetBusinessForArson(params: {
  sim: LoopSim;
  timeline: Timeline;
  placeId: string;
  businessId?: string;
}): {
  businessId?: string;
  businessName: string;
  slot?: string;
  ownerId?: string;
} {
  const { sim, timeline, placeId, businessId: explicitBizId } = params;
  const place = sim.places[placeId];
  const simYear = Math.max(timeline.startYear, sim.year);
  const snap = presentAt(timeline, simYear);
  const businesses = snap.world.businesses;
  const lots = snap.world.settlement?.properties ?? [];

  const lotForBiz = (bid: string): Lot | undefined =>
    lots.find((l) => l.kind === 'shop' && l.refId === bid && l.vacantSince === undefined) ??
    lots.find((l) => l.kind === 'shop' && l.refId === bid);

  const pickOpen = (bid?: string): Business | undefined => {
    if (!bid) return undefined;
    const b = businesses[bid];
    if (b && b.closedYear === undefined) return b;
    return undefined;
  };

  let matched = pickOpen(explicitBizId) ?? pickOpen(place?.businessId) ?? pickOpen(placeId);

  if (!matched && place) {
    const lowerName = place.name.toLowerCase();
    matched = Object.values(businesses)
      .filter((b) => b.closedYear === undefined)
      .find((b) => b.name.toLowerCase() === lowerName);
  }

  if (!matched) {
    const openBiz = Object.values(businesses)
      .filter((b) => b.closedYear === undefined)
      .sort((a, b) => (a.id < b.id ? -1 : 1));
    // Prefer an open business that occupies a real shop lot in `settlement.properties`
    const withLot = openBiz.filter((b) => lotForBiz(b.id) !== undefined);
    const candidates = withLot.length > 0 ? withLot : openBiz;
    if (candidates.length > 0) {
      // Deterministic match by placeId hash so distinct stub stores map to distinct businesses
      let hash = 0;
      for (let i = 0; i < placeId.length; i++) {
        hash = (hash * 31 + placeId.charCodeAt(i)) >>> 0;
      }
      matched = candidates[hash % candidates.length];
    }
  }

  if (matched) {
    const lot = lotForBiz(matched.id);
    return {
      businessId: matched.id,
      businessName: matched.name,
      ...(lot?.slot ? { slot: lot.slot } : place?.slot ? { slot: place.slot } : {}),
      ...(matched.owner ? { ownerId: matched.owner } : place?.ownerId ? { ownerId: place.ownerId } : {}),
    };
  }

  return {
    ...(place?.businessId ? { businessId: place.businessId } : {}),
    businessName: place?.name ?? placeId,
    ...(place?.slot ? { slot: place.slot } : {}),
    ...(place?.ownerId ? { ownerId: place.ownerId } : {}),
  };
}

export function recordArsonInTimeline(params: {
  sim: LoopSim;
  crimeId: string;
  placeId: string;
  businessId?: string;
  atNight: boolean;
  causeTag?: CausalAnchorTag;
  summary?: string;
  traces: EvidenceTrace[];
  witnesses: TimelineWitnessRecord[];
  reported: boolean;
  policeCase?: PoliceCase;
}): {
  timeline: Timeline;
  businessId?: string;
  businessName: string;
  slot?: string;
} {
  const { sim, crimeId, placeId, atNight, causeTag, traces, witnesses, reported, policeCase } = params;
  let tl = syncSimToTimeline(sim);
  const resolved = resolveTargetBusinessForArson({
    sim,
    timeline: tl,
    placeId,
    businessId: params.businessId,
  });
  const note =
    params.summary ??
    `Burned down ${resolved.businessName} in ${sim.year}${atNight ? ' at night' : ' in daylight'}.`;

  tl = branch(tl, {
    year: sim.year,
    placeId: tl.placeId,
    kind: 'arson',
    actorId: sim.playerId,
    ...(resolved.businessId ? { businessId: resolved.businessId } : {}),
    ...(resolved.slot ? { slot: resolved.slot } : {}),
    buildingLabel: resolved.businessName,
    atNight,
    crimeId,
    ...(causeTag ? { causeTag } : {}),
    summary: note,
  });

  tl = recordTimelineCrime(tl, {
    id: crimeId,
    kind: 'arson',
    year: sim.year,
    day: sim.currentDay,
    placeId: tl.placeId,
    culpritId: sim.playerId,
    ...(resolved.ownerId ? { victimId: resolved.ownerId } : {}),
    ...(resolved.businessId ? { businessId: resolved.businessId } : {}),
    severity: severityForTimelineCrime('arson'),
    reported,
    witnesses,
    traces,
    ...(policeCase ? { policeCase } : {}),
    summary: note,
  });

  return {
    timeline: tl,
    ...(resolved.businessId ? { businessId: resolved.businessId } : {}),
    businessName: resolved.businessName,
    ...(resolved.slot ? { slot: resolved.slot } : {}),
  };
}

function extractGazette(world: World): LoopGazetteEntry[] {
  const stories = world.stories ?? [];
  return stories.map((st) => ({
    id: st.id,
    year: st.year,
    template: st.template,
    category: st.category,
    headline: st.headline,
    body: st.body,
    people: st.people.slice(),
    ...(st.business !== undefined ? { business: st.business } : {}),
  }));
}

function cloneWorldForLoop(snapWorld: World, playerId: string, playerPerson: Person): World {
  const people: Record<Id, Person> = {};
  for (const [pid, p] of Object.entries(snapWorld.people)) {
    people[pid] = { ...p, children: p.children.slice(), parents: p.parents.slice() };
  }
  people[playerId] = {
    ...playerPerson,
    children: playerPerson.children.slice(),
    parents: playerPerson.parents.slice(),
  };

  const businesses: Record<Id, Business> = {};
  for (const [bid, b] of Object.entries(snapWorld.businesses)) {
    businesses[bid] = { ...b };
  }

  return {
    seed: snapWorld.seed,
    year: snapWorld.year,
    people,
    businesses,
    bonds: { ...snapWorld.bonds },
    ...(snapWorld.stories
      ? { stories: snapWorld.stories.map((st) => ({ ...st, people: st.people.slice() })) }
      : {}),
    ...(snapWorld.settlement
      ? {
          settlement: {
            properties: snapWorld.settlement.properties.map((l) => ({
              ...l,
              heads: l.heads.slice(),
              residents: l.residents.slice(),
            })),
            roads: snapWorld.settlement.roads.slice(),
          },
        }
      : {}),
  };
}

function nearestNodeId(
  pos: { x: number; y: number },
  nodes: LoopSim['streetGraph']['nodes'],
): string {
  let best = nodes[0]?.id ?? 'node:main';
  let bestDist = Infinity;
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]!;
    const d = Math.hypot(n.position.x - pos.x, n.position.y - pos.y);
    if (d < bestDist) {
      bestDist = d;
      best = n.id;
    }
  }
  return best;
}

function projectPlacesForTargetYear(
  sim: LoopSim,
  world: World,
  idMap: IdMapState,
  machinePlaceId: string,
): Record<string, LoopPlaceRecord> {
  const jurId = defaultJurisdictionId(sim);
  const townId = defaultTownId(sim);
  const nodes = sim.streetGraph.nodes;
  const places: Record<string, LoopPlaceRecord> = {};

  // 1. Keep persistent civic landmarks (police station, courthouse, time machine)
  for (const p of Object.values(sim.places).sort((a, b) => (a.id < b.id ? -1 : 1))) {
    if (
      p.kind === 'police_station' ||
      p.kind === 'courthouse' ||
      p.kind === 'time_machine' ||
      p.id === machinePlaceId
    ) {
      registerPlanId(idMap, p.id, 'place');
      registerPlanId(idMap, p.nodeId, 'node');
      places[p.id] = {
        ...p,
        position: { x: p.position.x, y: p.position.y },
        staff: [],
        ownerId: undefined,
      };
    }
  }

  // Ensure a dedicated time-machine location exists in the capital if none was flagged
  if (!places[machinePlaceId]) {
    const anchor = nodes[0] ?? { id: 'node:main', position: { x: 0, y: 0 } };
    registerPlanId(idMap, machinePlaceId, 'place');
    registerPlanId(idMap, anchor.id, 'node');
    places[machinePlaceId] = {
      id: machinePlaceId,
      name: "Professor's Lab (Time Machine)",
      kind: 'time_machine',
      position: { x: anchor.position.x, y: anchor.position.y },
      staff: [],
      jurisdictionId: jurId,
      townId,
      nodeId: anchor.id,
    };
  }

  // 2. Project every open business from `presentAt(timeline, toYear).world`
  const lots = world.settlement?.properties ?? [];
  const lotByBizId = new Map<string, Lot>();
  for (const lot of lots) {
    if (lot.kind === 'shop' && lot.vacantSince === undefined) {
      lotByBizId.set(lot.refId, lot);
    }
  }

  const openBusinesses = Object.values(world.businesses)
    .filter((b) => b.closedYear === undefined)
    .sort((a, b) => (a.id < b.id ? -1 : 1));

  for (let i = 0; i < openBusinesses.length; i++) {
    const biz = openBusinesses[i]!;
    const lot = lotByBizId.get(biz.id);
    const placeId = `place:biz:${biz.id}`;
    const node = nodes[i % Math.max(1, nodes.length)];
    const pos = lot
      ? { x: Math.round(lot.x * 10) / 10, y: Math.round(lot.y * 10) / 10 }
      : node
        ? { x: node.position.x + ((i % 3) - 1) * 4, y: node.position.y + 4 }
        : { x: i * 12, y: 4 };
    const nodeId = nearestNodeId(pos, nodes);
    registerPlanId(idMap, placeId, 'place');
    registerPlanId(idMap, nodeId, 'node');
    const ownerAlive = biz.owner && world.people[biz.owner] && isAlive(world.people[biz.owner]!);
    places[placeId] = {
      id: placeId,
      name: biz.name,
      kind: mapBusinessKindToPlaceKind(biz.kind),
      position: pos,
      ...(ownerAlive && biz.owner ? { ownerId: biz.owner } : {}),
      staff: ownerAlive && biz.owner ? [biz.owner] : [],
      jurisdictionId: jurId,
      townId,
      openHours: [8, 19],
      nodeId,
      businessId: biz.id,
      ...(lot?.slot ? { slot: lot.slot } : {}),
    };
  }

  // 3. Project occupied homes from `world.settlement.properties` (up to a sensible cap) or a shared town residential block
  const homeLots = lots
    .filter((l) => l.kind === 'house' && l.vacantSince === undefined)
    .sort((a, b) => (a.slot < b.slot ? -1 : 1));
  for (let i = 0; i < Math.min(homeLots.length, 40); i++) {
    const lot = homeLots[i]!;
    const homeId = `place:home:${lot.slot}`;
    const pos = { x: Math.round(lot.x * 10) / 10, y: Math.round(lot.y * 10) / 10 };
    const nodeId = nearestNodeId(pos, nodes);
    registerPlanId(idMap, homeId, 'place');
    registerPlanId(idMap, nodeId, 'node');
    const aliveHead = lot.heads.find((h) => world.people[h] && isAlive(world.people[h]!));
    places[homeId] = {
      id: homeId,
      name: lot.label,
      kind: 'home',
      position: pos,
      ...(aliveHead ? { ownerId: aliveHead } : {}),
      staff: [],
      jurisdictionId: jurId,
      townId,
      nodeId,
      slot: lot.slot,
    };
  }

  return places;
}

function projectPeopleAndFamiliarity(params: {
  sim: LoopSim;
  world: World;
  places: Record<string, LoopPlaceRecord>;
  timeline: Timeline;
  toYear: number;
  idMap: IdMapState;
}): {
  peopleMeta: Record<string, LoopPersonMeta>;
  familiarity: Record<string, PlayerFamiliarity>;
  lastStances: Record<string, AttitudeStance>;
} {
  const { sim, world, places, timeline, toYear, idMap } = params;
  const townId = defaultTownId(sim);
  const placeList = Object.values(places);
  const defaultHome =
    placeList.find((p) => p.kind === 'home')?.id ?? placeList[0]?.id ?? 'place:street';

  const workplaceByOwner = new Map<string, string>();
  for (const p of placeList) {
    if (p.ownerId && p.kind !== 'home') {
      workplaceByOwner.set(p.ownerId, p.id);
    }
  }
  const homeByPerson = new Map<string, string>();
  for (const lot of world.settlement?.properties ?? []) {
    if (lot.kind !== 'house' || lot.vacantSince !== undefined) continue;
    const hid = `place:home:${lot.slot}`;
    if (!places[hid]) continue;
    for (const rid of [...lot.heads, ...lot.residents]) {
      homeByPerson.set(rid, hid);
    }
  }

  const peopleMeta: Record<string, LoopPersonMeta> = {};
  const familiarity: Record<string, PlayerFamiliarity> = {};
  const lastStances: Record<string, AttitudeStance> = {};

  for (const pid of Object.keys(world.people).sort()) {
    if (pid === sim.playerId) continue;
    const p = world.people[pid]!;
    if (!isAlive(p)) continue;
    registerPlanId(idMap, pid, 'person');
    const workplacePlaceId = workplaceByOwner.get(pid);
    peopleMeta[pid] = {
      id: pid,
      name: fullName(p),
      homePlaceId: homeByPerson.get(pid) ?? defaultHome,
      ...(workplacePlaceId ? { workplacePlaceId } : {}),
      townId,
      siblingIds: [],
    };
    lastStances[pid] = 'serve';

    // Attitudes reset EXCEPT where the timeline carries branch memory (Rule 2)
    const mem = npcMemoryOfPlayer(timeline, pid, toYear);
    if (mem.remembersPlayer && mem.interactions.length > 0) {
      familiarity[pid] = {
        knowsPlayerName: true,
        trustDelta: Math.min(0.45, mem.interactions.length * 0.12),
        positiveFacts: mem.interactions.map((ix) => ix.summary),
        bannedFromPlaceIds: [],
      };
    }
  }

  return { peopleMeta, familiarity, lastStances };
}

function rebuildCrimeStateForYear(params: {
  sim: LoopSim;
  timeline: Timeline;
  world: World;
  places: Record<string, LoopPlaceRecord>;
  toYear: number;
  arrivalDay: number;
  jurId: string;
}): {
  crimeState: CrimeState;
  crimeMeta: Record<string, CrimeMetaRecord>;
  crimeStatuses: CaseStatusReport[];
  crossEraMatches: LoopCrossEraMatch[];
} {
  const { sim, timeline, world, places, toYear, arrivalDay, jurId } = params;
  let crimeState = createCrimeState(sim.seed, arrivalDay);
  crimeState = {
    ...crimeState,
    identityFiles: cloneIdentityFiles(timeline.identityFiles),
  };
  const crimeMeta: Record<string, CrimeMetaRecord> = {};
  const crimeStatuses: CaseStatusReport[] = [];

  const placeByBiz = new Map<string, LoopPlaceRecord>();
  for (const p of Object.values(places)) {
    if (p.businessId) placeByBiz.set(p.businessId, p);
  }
  const fallbackPlace = Object.values(places)[0];

  for (const crimeId of Object.keys(timeline.crimes).sort()) {
    const rec = timeline.crimes[crimeId]!;
    const status = caseStatusAt(timeline, crimeId, toYear);
    crimeStatuses.push(status);

    if (!status.crimeOccurred || !status.recordsIntact) continue;
    // Restore active crimes, cold cases, and any crime retaining collected physical traces
    // so cross-era fingerprint / DNA matching works immediately on arrival AND at booking!
    const shouldRestore =
      status.activelyLooking ||
      status.isColdCase ||
      status.canReopenWithModernForensics ||
      status.hasPhysicalEvidence;
    if (!shouldRestore) continue;

    const mappedKind: CrimeKind = toCourtCrimeKind(rec.kind);
    const severity = rec.severity ?? severityForTimelineCrime(rec.kind);
    const linkedPlace =
      (rec.businessId ? placeByBiz.get(rec.businessId) : undefined) ??
      places[rec.placeId] ??
      fallbackPlace;
    const placeId = linkedPlace?.id ?? rec.placeId;
    const pos = linkedPlace ? { x: linkedPlace.position.x, y: linkedPlace.position.y } : { x: 0, y: 0 };

    const ageYears = Math.max(0, toYear - rec.year);
    const crimeDay = Math.max(1, arrivalDay - ageYears * 365);

    const crimeEvent: CrimeEvent = {
      id: rec.id,
      kind: mappedKind,
      severity,
      placeId,
      position: pos,
      when: { year: rec.year, day: crimeDay, hour: 12 },
      culpritId: rec.culpritId,
      culpritAppearance: { ...sim.playerAppearance },
      ...(rec.victimId ? { victimId: rec.victimId } : {}),
      victimConscious: true,
    };

    const survivingTraces: EvidenceTrace[] = rec.traces
      .filter((t) => !t.destroyed && t.quality > 0.05)
      .map((t) => ({
        ...t,
        // Evidence collected in the crime's era sits in the police archive bag
        collected: true,
        collectedDay: t.collectedDay ?? crimeDay,
      }));

    // Build ledger entries only for witnesses still alive & not erased in `toYear`
    const aliveWitnessSet = new Set(status.activeWitnesses);
    const crimeLedger: Record<string, CrimeAccount> = {};
    for (const w of rec.witnesses) {
      if (!aliveWitnessSet.has(w.personId)) continue;
      if (!world.people[w.personId] || !isAlive(world.people[w.personId]!)) continue;
      // Memory fades modestly over decades, never dropping below 0.25 if still recalled
      const decayedConf = Math.max(0.25, Math.round(w.confidence * Math.pow(0.97, ageYears) * 1000) / 1000);
      crimeLedger[w.personId] = {
        crimeId: rec.id,
        knowerId: w.personId,
        source: 'first_hand',
        isVictim: w.personId === rec.victimId,
        hops: 0,
        dayLearned: crimeDay,
        lastRefreshedDay: crimeDay,
        confidence: decayedConf,
        initialConfidence: w.confidence,
        ...(w.identifiedCulprit ? { identifiedCulpritId: rec.culpritId } : {}),
        description: {
          height: sim.playerAppearance.height,
          build: sim.playerAppearance.build,
          hair: sim.playerAppearance.hair,
          clothingColor: sim.playerAppearance.clothingColor,
        },
        reported: true,
        refreshes: 0,
      };
    }

    crimeState = {
      ...crimeState,
      crimes: { ...crimeState.crimes, [rec.id]: crimeEvent },
      ledger: { ...crimeState.ledger, [rec.id]: crimeLedger },
      traces: { ...(crimeState.traces ?? {}), [rec.id]: survivingTraces },
    };

    // Build / restore PoliceCase in `jurId` with collected traces (and clean evidenceMatches
    // so `reexamineColdCases` evaluates strictly under `toYear`'s forensic science & enrolledYears!)
    let jur = crimeState.jurisdictions[jurId] ?? { jurisdictionId: jurId, cases: {} };
    const reporters =
      rec.witnesses.filter((w) => !status.erasedWitnesses.includes(w.personId));
    if (reporters.length > 0) {
      for (const w of reporters) {
        const rep: PoliceReport = {
          id: `rep:tl:${rec.id}:${w.personId}`,
          crimeId: rec.id,
          reporterId: w.personId,
          jurisdictionId: jurId,
          filedDay: crimeDay,
          receivedDay: crimeDay,
          delayHours: 0,
          source: 'first_hand',
          isVictim: w.personId === rec.victimId,
          confidence: w.confidence,
          ...(w.identifiedCulprit ? { identifiedCulpritId: rec.culpritId } : {}),
          description: {
            height: sim.playerAppearance.height,
            build: sim.playerAppearance.build,
            hair: sim.playerAppearance.hair,
            clothingColor: sim.playerAppearance.clothingColor,
          },
        };
        jur = ingestPoliceReport(jur, crimeEvent, rep);
      }
    } else if (survivingTraces.length > 0 || rec.reported) {
      const archiveRep: PoliceReport = {
        id: `rep:tl:${rec.id}:archive`,
        crimeId: rec.id,
        reporterId: rec.victimId ?? 'archive_officer',
        jurisdictionId: jurId,
        filedDay: crimeDay,
        receivedDay: crimeDay,
        delayHours: 0,
        source: 'first_hand',
        isVictim: false,
        confidence: 0.6,
        description: {},
      };
      jur = ingestPoliceReport(jur, crimeEvent, archiveRep);
    }

    const pc = jur.cases[rec.id];
    if (pc) {
      jur = {
        ...jur,
        cases: {
          ...jur.cases,
          [rec.id]: {
            ...pc,
            traces: survivingTraces,
            evidenceMatches: [],
          },
        },
      };
      crimeState = {
        ...crimeState,
        jurisdictions: {
          ...crimeState.jurisdictions,
          [jurId]: jur,
        },
      };
    }

    crimeMeta[rec.id] = {
      crimeId: rec.id,
      kind: mappedKind,
      placeId,
      placeName: linkedPlace?.name ?? rec.placeId,
      jurisdictionId: jurId,
      day: crimeDay,
      dayName: `${rec.year}`,
      ...(rec.summary ? { itemLabel: rec.summary } : {}),
      ...(rec.victimId ? { ownerId: rec.victimId } : {}),
      resolved: false,
    };
  }

  // Run G008 `reexamineColdCases` under `toYear`'s exact forensic science & `enrolledYear <= toYear` rules!
  const playerBirthYear = timeline.player.selfErased ? undefined : timeline.player.birthYear;
  crimeState = reexamineColdCases(crimeState, world, arrivalDay, {
    year: toYear,
    birthYearOf: (pid) =>
      pid === sim.playerId ? playerBirthYear : world.people[pid]?.birthYear,
  });

  // Collect all cross-era matches produced on arrival
  const crossEraMatches: LoopCrossEraMatch[] = [];
  const jurCases = crimeState.jurisdictions[jurId]?.cases ?? {};
  for (const [crimeId, pc] of Object.entries(jurCases)) {
    const rec = timeline.crimes[crimeId];
    if (!rec) continue;
    for (const m of pc.evidenceMatches ?? []) {
      const anomalies =
        m.suspectId === sim.playerId && playerBirthYear !== undefined
          ? detectTimeTravelAnomalies({
              traceId: m.traceId,
              crimeId,
              traceKind: m.traceKind,
              personId: m.suspectId,
              traceYear: rec.year,
              birthYear: playerBirthYear,
            })
          : [];
      crossEraMatches.push({
        crimeId,
        crimeKind: rec.kind,
        crimeYear: rec.year,
        matchedInYear: toYear,
        traceKind: m.traceKind,
        method: m.method,
        confidence: m.confidence,
        yearsToMatch: Math.max(0, toYear - rec.year),
        anomalies,
        summary: `${m.traceKind} from ${rec.year} ${rec.kind} matched in ${toYear} via ${m.method} (${Math.round(m.confidence * 100)}% confidence${anomalies.length > 0 ? `; anomaly: ${anomalies[0]!.kind}` : ''}).`,
      });
    }
  }
  crossEraMatches.sort((a, b) => (a.crimeId < b.crimeId ? -1 : a.crimeId > b.crimeId ? 1 : 0));

  return { crimeState, crimeMeta, crimeStatuses, crossEraMatches };
}

function emptyJumpFailure(
  sim: LoopSim,
  tm: LoopTimeMachineState,
  toYear: number,
  reason: string,
): JumpResult {
  const tl = sim.timeline;
  const gazette = extractGazette(sim.world);
  const carriedStatuses = tl ? inspectCarriedItems(tl, Math.max(tl.startYear, sim.year)) : [];
  return {
    sim,
    ok: false,
    reason,
    fromYear: sim.year,
    toYear,
    fuelSpent: 0,
    fuelRemaining: tm.fuelRemaining,
    maxFuel: tm.maxFuel,
    alivePeopleCount: Object.keys(sim.peopleMeta).length,
    placeCount: Object.keys(sim.places).length,
    gazette,
    carriedItems: carriedStatuses,
    crimeStatuses: [],
    crossEraMatches: [],
    looseThreads: tm.looseThreads.slice(),
    looseThreadFacts: tm.looseThreadFacts.slice(),
    policePlans: [],
    summary: reason,
  };
}

/**
 * Executes `jump(sim, { toYear })`: spends 1 nuclear fuel unit, replays history via
 * G017/G018 `presentAt(timeline, toYear)`, projects the town, alive residents,
 * branch-remembered attitudes, era police force, cross-era forensic matches,
 * carried items, Gazette archive, and plain-English loose threads.
 */
export function performTimeJump(
  sim: LoopSim,
  input: JumpInput,
  deps: TimeJumpPoliceDeps,
): JumpResult {
  const tm = ensureTimeMachine(sim);
  const toYear = Math.round(input.toYear);

  if (!Number.isFinite(toYear) || toYear < 1850 || toYear > 2150) {
    return emptyJumpFailure(
      sim,
      tm,
      input.toYear,
      `Invalid target year ${input.toYear}; must be between 1850 and 2150.`,
    );
  }
  if (toYear === sim.year) {
    return emptyJumpFailure(
      sim,
      tm,
      toYear,
      `Already in ${toYear}; no jump performed and no fuel spent.`,
    );
  }
  if (input.requireAtMachine && input.playerPlaceId && input.playerPlaceId !== tm.machinePlaceId) {
    return emptyJumpFailure(
      sim,
      tm,
      toYear,
      `Must be at the time machine (${tm.machinePlaceId}) in the capital to jump.`,
    );
  }
  if (tm.fuelRemaining <= 0) {
    return emptyJumpFailure(
      sim,
      tm,
      toYear,
      `Time machine fuel depleted (0/${tm.maxFuel} nuclear fuel units remaining).`,
    );
  }

  // Sync current era crimes, identity files, carried items, and interactions into timeline
  const timeline = syncSimToTimeline(sim, toYear);
  if (toYear < timeline.startYear) {
    return emptyJumpFailure(
      sim,
      tm,
      toYear,
      `Target year ${toYear} is before this timeline's start year (${timeline.startYear}).`,
    );
  }

  const fromYear = sim.year;
  const nextFuel = tm.fuelRemaining - 1;
  const arrivalDay = input.arrivalDay ?? 1;
  const arrivalHour = ((input.arrivalHour ?? 9) % 24 + 24) % 24;

  // Replay the target year from `src/timeline` (cached via G018)
  const snap: PresentSnapshot = presentAt(timeline, toYear);
  const existingPlayer = sim.world.people[sim.playerId] ?? {
    id: sim.playerId,
    first: sim.playerName.split(' ')[0] ?? 'Alex',
    surname: sim.playerName.split(' ').slice(1).join(' ') || 'Mercer',
    sex: 'M' as const,
    birthYear: timeline.player.birthYear,
    traits: { ambition: 0.5, charm: 0.5, diligence: 0.5 },
    parents: [],
    children: [],
    wealth: 20,
    reputation: 0,
  };
  const targetWorld = cloneWorldForLoop(snap.world, sim.playerId, existingPlayer);

  // Build fresh bijective idMap for `toYear`
  const idMap = createIdMap();
  registerPlanId(idMap, sim.playerId, 'person');
  for (const n of sim.streetGraph.nodes) {
    registerPlanId(idMap, n.id, 'node');
  }

  const places = projectPlacesForTargetYear(sim, targetWorld, idMap, tm.machinePlaceId);
  const { peopleMeta, familiarity, lastStances } = projectPeopleAndFamiliarity({
    sim,
    world: targetWorld,
    places,
    timeline,
    toYear,
    idMap,
  });

  const jurId = defaultJurisdictionId(sim);
  const { crimeState, crimeMeta, crimeStatuses, crossEraMatches } = rebuildCrimeStateForYear({
    sim,
    timeline,
    world: targetWorld,
    places,
    toYear,
    arrivalDay,
    jurId,
  });

  // Rebuild real G009 police force for `toYear`
  const { policeState, officers } = deps.createLoopPolice({
    seed: sim.seed,
    year: toYear,
    startDay: arrivalDay,
    startHour: arrivalHour,
    places,
    streetGraph: sim.streetGraph,
    peopleCount: Object.keys(peopleMeta).length,
    crimeState,
    idMap,
  });

  // Keep `stolenItems` records for any stolen carried item travelling in the player's pockets
  const carriedList = (sim.carriedItems ?? []).map((c) => ({ ...c }));
  const nextStolenItems: Record<string, StolenItemRecord> = {};
  for (const ci of carriedList) {
    if (!ci.stolen) continue;
    const prev = sim.stolenItems[ci.id];
    nextStolenItems[ci.id] = prev
      ? { ...prev }
      : {
          itemId: ci.id,
          label: ci.label,
          value: ci.value,
          ...(ci.serialNumber ? { serialNumber: ci.serialNumber } : {}),
          placeId: ci.acquiredPlaceId,
          jurisdictionId: jurId,
          ...(ci.ownerId ? { ownerId: ci.ownerId } : {}),
          crimeId: ci.crimeId ?? `crime:carried:${ci.id}`,
          dayStolen: ci.acquiredDay,
        };
  }

  // Evaluate loose threads, carried-item provenance, and Gazette stories in `toYear`
  const threads = looseThreads(timeline, toYear);
  const looseThreadFacts = threads.map((t) => t.fact);
  const carriedStatuses = inspectCarriedItems(timeline, toYear);
  const gazette = extractGazette(targetWorld);

  const nextTimeMachine: LoopTimeMachineState = {
    fuelRemaining: nextFuel,
    maxFuel: tm.maxFuel,
    machinePlaceId: tm.machinePlaceId,
    originYear: tm.originYear,
    currentYear: toYear,
    jumpHistory: [
      ...tm.jumpHistory,
      {
        jumpIndex: tm.jumpHistory.length + 1,
        fromYear,
        toYear,
        fuelSpent: 1,
        fuelRemaining: nextFuel,
        looseThreadCount: threads.length,
        gazetteStoryCount: gazette.length,
      },
    ],
    looseThreads: threads,
    looseThreadFacts,
  };

  let arrivedSim: LoopSim = {
    ...sim,
    year: toYear,
    currentDay: arrivalDay,
    currentHour: arrivalHour,
    startDay: arrivalDay,
    world: targetWorld,
    crimeState,
    policeState,
    idMap,
    places,
    peopleMeta,
    officers,
    respondedCases: { ...policeState.respondedCases },
    crimeMeta,
    stolenItems: nextStolenItems,
    pawnRecords: [],
    workShifts: [],
    familiarity,
    lastStances,
    timeline,
    timeMachine: nextTimeMachine,
    carriedItems: carriedList,
    // Era-local transient structures reset cleanly on arrival
    resolvedCrimeMemories: undefined,
    pendingReportMeta: undefined,
    latestSightingPlaceByCrime: undefined,
    courtCases: undefined,
    warrants: undefined,
    nextSeq: sim.nextSeq + 1,
  };

  // Run one arrival police tick so officers respond to any active cases in `toYear`
  const policeStep = deps.stepArrivalPolice(arrivedSim);
  arrivedSim = policeStep.sim;

  const summaryParts = [
    `Jumped from ${fromYear} to ${toYear} (${nextFuel}/${tm.maxFuel} fuel remaining).`,
    `${Object.keys(peopleMeta).length} resident(s) alive; ${Object.keys(places).length} place(s); ${gazette.length} Gazette story/stories.`,
  ];
  if (crossEraMatches.length > 0) {
    summaryParts.push(`${crossEraMatches.length} cross-era forensic match(es) triggered.`);
  }
  if (threads.length > 0) {
    summaryParts.push(`${threads.length} loose thread(s) noted.`);
  }

  return {
    sim: arrivedSim,
    ok: true,
    fromYear,
    toYear,
    fuelSpent: 1,
    fuelRemaining: nextFuel,
    maxFuel: tm.maxFuel,
    alivePeopleCount: Object.keys(peopleMeta).length,
    placeCount: Object.keys(places).length,
    changes: snap.changes,
    gazette,
    carriedItems: carriedStatuses,
    crimeStatuses,
    crossEraMatches,
    looseThreads: threads,
    looseThreadFacts,
    policePlans: policeStep.policePlans,
    summary: summaryParts.join(' '),
  };
}

/**
 * Plain-JSON readout of the time machine state, fuel, carried items, loose threads,
 * and current-era Gazette stories.
 */
export function buildTimeMachineView(sim: LoopSim): TimeMachineViewResult {
  const tm = ensureTimeMachine(sim);
  const tl = sim.timeline;
  const carriedItems = (sim.carriedItems ?? []).map((c) => ({ ...c }));
  const carriedItemStatuses = tl
    ? inspectCarriedItems(tl, Math.max(tl.startYear, sim.year))
    : carriedItems.map((c) => ({
        item: {
          id: c.id,
          label: c.label,
          acquiredYear: c.acquiredYear,
          acquiredPlaceId: c.acquiredPlaceId,
          ...(c.ownerId ? { makerId: c.ownerId } : {}),
          ...(c.ownerName ? { makerName: c.ownerName } : {}),
          ...(c.businessId ? { businessId: c.businessId } : {}),
          ...(c.businessName ? { businessName: c.businessName } : {}),
          provenanceNote: c.provenanceNote,
        },
        survives: true as const,
        makerExists: true,
        businessExists: true,
        anachronisticProvenance: false,
        note: c.provenanceNote,
      }));
  return {
    currentYear: sim.year,
    originYear: tm.originYear,
    fuelRemaining: tm.fuelRemaining,
    maxFuel: tm.maxFuel,
    machinePlaceId: tm.machinePlaceId,
    jumpHistory: tm.jumpHistory.map((h) => ({ ...h })),
    carriedItems,
    carriedItemStatuses,
    looseThreads: tm.looseThreads.map((t) => ({ ...t, relatedIds: t.relatedIds.slice() })),
    looseThreadFacts: tm.looseThreadFacts.slice(),
    gazette: extractGazette(sim.world),
  };
}

/**
 * Returns plain-English loose-thread dialogue hooks relevant to `personId` for NPC fact sheets.
 * Returns an empty array when no loose threads exist (leaving non-jump games untouched).
 */
export function looseThreadHooksForPerson(sim: LoopSim, personId: string): string[] {
  const threads = sim.timeMachine?.looseThreads;
  if (!threads || threads.length === 0) return [];
  const meta = sim.peopleMeta[personId];
  const isMerchant = Boolean(
    meta?.workplacePlaceId &&
      sim.places[meta.workplacePlaceId] &&
      ['general_store', 'market', 'pawn_shop'].includes(sim.places[meta.workplacePlaceId]!.kind),
  );
  const hooks: string[] = [];
  for (const t of threads) {
    if (
      t.relatedIds.includes(personId) ||
      t.kind === 'self_erased' ||
      t.kind === 'cause_removed' ||
      (t.kind === 'orphan_item' && isMerchant)
    ) {
      if (!hooks.includes(t.dialogueHook)) {
        hooks.push(t.dialogueHook);
      }
    }
  }
  return hooks;
}
