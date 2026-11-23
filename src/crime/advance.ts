import type { Id, World } from '../sim/types';
import { getSocialIndex, spreadWordOfMouthForCrime } from './gossip';
import {
  createDailyDecayFactors,
  decayAccountOneDay,
  refreshAccount,
  type RefreshReason,
} from './memory';
import { ingestPoliceReport } from './police';
import { evaluateReportingForCrime } from './reporting';
import type {
  CrimeAccount,
  CrimeContext,
  CrimeEvent,
  CrimeState,
  JurisdictionState,
  PendingReport,
  PoliceReport,
  Sighting,
} from './types';

/** Creates an empty, plain-JSON serializable `CrimeState`. */
export function createCrimeState(seed: string, startDay = 0): CrimeState {
  return {
    seed,
    currentDay: startDay,
    crimes: {},
    ledger: {},
    pendingReports: [],
    jurisdictions: {},
    toldPairs: {},
  };
}

/**
 * Purely records a newly committed `CrimeEvent` and its initial first-hand `sightings`
 * (from `witnessesOf`) into `state`.
 */
export function recordCrime(
  state: CrimeState,
  event: CrimeEvent,
  sightings: readonly Sighting[],
): CrimeState {
  const byPerson: Record<Id, CrimeAccount> = {};
  const sortedSightings = [...sightings].sort((a, b) =>
    a.witnessId < b.witnessId ? -1 : a.witnessId > b.witnessId ? 1 : 0,
  );

  for (let i = 0; i < sortedSightings.length; i++) {
    const s = sortedSightings[i]!;
    const acc: CrimeAccount = {
      crimeId: event.id,
      knowerId: s.witnessId,
      source: 'first_hand',
      isVictim: s.isVictim,
      hops: 0,
      dayLearned: s.dayWitnessed,
      lastRefreshedDay: s.lastRefreshedDay,
      confidence: s.confidence,
      initialConfidence: s.initialConfidence,
      description: {
        ...s.description,
        ...(s.description.vehicle ? { vehicle: { ...s.description.vehicle } } : {}),
      },
      reported: false,
      refreshes: s.refreshes,
    };
    if (s.identifiedCulpritId !== undefined) acc.identifiedCulpritId = s.identifiedCulpritId;
    byPerson[s.witnessId] = acc;
  }

  return {
    ...state,
    crimes: {
      ...state.crimes,
      [event.id]: event,
    },
    ledger: {
      ...state.ledger,
      [event.id]: byPerson,
    },
    toldPairs: {
      ...state.toldPairs,
      [event.id]: {},
    },
  };
}

/**
 * Purely refreshes a witness's or knower's memory of `crimeId` on `day`
 * (e.g. re-spotting the culprit on the street or being interviewed by police).
 */
export function refreshWitnessMemory(
  state: CrimeState,
  crimeId: string,
  personId: Id,
  day: number,
  reason: RefreshReason,
): CrimeState {
  const byPerson = state.ledger[crimeId];
  const existing = byPerson?.[personId];
  if (!existing) return state;

  return {
    ...state,
    ledger: {
      ...state.ledger,
      [crimeId]: {
        ...byPerson,
        [personId]: refreshAccount(existing, day, reason),
      },
    },
  };
}

/** Returns everyone who currently knows about `crimeId`, sorted by person id. */
export function knowersOf(state: CrimeState, crimeId: string): CrimeAccount[] {
  const byPerson = state.ledger[crimeId];
  if (!byPerson) return [];
  return Object.keys(byPerson)
    .sort()
    .map((id) => byPerson[id]!);
}

/**
 * Advances the crime simulation by one simulated day (`day`), running deterministically:
 * 1. Memory decay (sightings & hearsay fade by severity; forgotten accounts pruned);
 * 2. Word-of-mouth gossip (within towns, plus kin & travelers across towns);
 * 3. Reporting decisions (severity, culprit shielding, victim loyalty, reputation, refusal, era delay);
 * 4. Delivery of pending reports whose `arrivalDay <= day` into their target jurisdictions.
 *
 * Pure function of `(state, world, day, ctx)`: does not mutate `state` or `world`,
 * and produces plain-JSON serializable state.
 */
export function advanceDay(
  state: CrimeState,
  world: World,
  day: number,
  ctx?: CrimeContext,
): CrimeState {
  const crimeIds = Object.keys(state.crimes).sort();
  const socialIndex = crimeIds.length > 0 ? getSocialIndex(world, ctx) : undefined;

  const nextLedger: Record<string, Record<Id, CrimeAccount>> = {};
  const nextToldPairs: Record<string, Record<Id, Id[]>> = {};
  const newlyQueued: PendingReport[] = [];

  for (let i = 0; i < crimeIds.length; i++) {
    const crimeId = crimeIds[i]!;
    const crime = state.crimes[crimeId]!;
    const prevAccounts = state.ledger[crimeId];
    const prevTold = state.toldPairs[crimeId] ?? {};

    if (!prevAccounts) {
      nextLedger[crimeId] = {};
      nextToldPairs[crimeId] = prevTold;
      continue;
    }

    // 1. Memory decay for every active knower
    const decayFactors = createDailyDecayFactors(crime.severity);
    const decayedAccounts: Record<Id, CrimeAccount> = {};
    const survivingIds: Id[] = [];
    const prevIds = Object.keys(prevAccounts);
    let needsSort = false;
    for (let s = 1; s < prevIds.length; s++) {
      if (prevIds[s - 1]! > prevIds[s]!) {
        needsSort = true;
        break;
      }
    }
    if (needsSort) prevIds.sort();

    for (let k = 0; k < prevIds.length; k++) {
      const pid = prevIds[k]!;
      const faded = decayAccountOneDay(prevAccounts[pid]!, crime.severity, day, decayFactors);
      if (faded !== null) {
        decayedAccounts[pid] = faded;
        survivingIds.push(pid);
      }
    }

    // 2. Word-of-mouth spread
    const gossip = spreadWordOfMouthForCrime(
      state.seed,
      world,
      crime,
      decayedAccounts,
      prevTold,
      day,
      ctx,
      socialIndex,
      survivingIds,
    );

    let reportIds: readonly Id[] = survivingIds;
    if (gossip.newAccounts.length > 0) {
      const combinedIds = survivingIds.slice();
      for (let g = 0; g < gossip.newAccounts.length; g++) {
        const acc = gossip.newAccounts[g]!;
        if (decayedAccounts[acc.knowerId] === undefined) {
          combinedIds.push(acc.knowerId);
        }
        decayedAccounts[acc.knowerId] = acc;
      }
      combinedIds.sort();
      reportIds = combinedIds;
    }

    let updatedTold = prevTold;
    if (gossip.newTold.length > 0) {
      updatedTold = { ...prevTold };
      const copiedTellers = new Set<Id>();
      for (let t = 0; t < gossip.newTold.length; t++) {
        const entry = gossip.newTold[t]!;
        if (!copiedTellers.has(entry.tellerId)) {
          const prevArr = updatedTold[entry.tellerId];
          updatedTold[entry.tellerId] = prevArr ? prevArr.slice() : [];
          copiedTellers.add(entry.tellerId);
        }
        updatedTold[entry.tellerId]!.push(entry.listenerId);
      }
    }

    // 3. Reporting decisions
    const rep = evaluateReportingForCrime(
      state.seed,
      world,
      crime,
      decayedAccounts,
      day,
      ctx,
      socialIndex,
      reportIds,
    );
    if (rep.markedReported.length > 0) {
      for (let r = 0; r < rep.markedReported.length; r++) {
        const m = rep.markedReported[r]!;
        const cur = decayedAccounts[m.knowerId]!;
        decayedAccounts[m.knowerId] = {
          ...cur,
          reported: true,
          reportQueuedDay: day,
          reportedJurisdiction: m.jurisdictionId,
        };
      }
    }
    for (let q = 0; q < rep.queuedReports.length; q++) {
      newlyQueued.push(rep.queuedReports[q]!);
    }

    nextLedger[crimeId] = decayedAccounts;
    nextToldPairs[crimeId] = updatedTold;
  }

  // 4. Deliver all pending reports whose arrivalDay <= day into police jurisdictions
  const allPending =
    newlyQueued.length > 0 ? [...state.pendingReports, ...newlyQueued] : state.pendingReports;
  const remainingPending: PendingReport[] = [];
  let nextJurisdictions: Record<string, JurisdictionState> = state.jurisdictions;
  let jurCopied = false;

  const ensureJurCopy = (): void => {
    if (!jurCopied) {
      nextJurisdictions = { ...state.jurisdictions };
      jurCopied = true;
    }
  };

  for (let i = 0; i < allPending.length; i++) {
    const p = allPending[i]!;
    if (p.arrivalDay > day) {
      remainingPending.push(p);
      continue;
    }

    const crime = state.crimes[p.crimeId];
    if (!crime) continue;

    const delivered: PoliceReport = {
      id: p.id,
      crimeId: p.crimeId,
      reporterId: p.reporterId,
      jurisdictionId: p.jurisdictionId,
      filedDay: p.filedDay,
      receivedDay: day,
      delayHours: p.delayHours,
      source: p.source,
      isVictim: p.isVictim,
      confidence: p.confidence,
      ...(p.identifiedCulpritId ? { identifiedCulpritId: p.identifiedCulpritId } : {}),
      description: p.description,
    };

    ensureJurCopy();
    nextJurisdictions[p.jurisdictionId] = ingestPoliceReport(
      nextJurisdictions[p.jurisdictionId],
      crime,
      delivered,
    );

    if (ctx?.escalateJurisdictionOf) {
      const parentJur = ctx.escalateJurisdictionOf(p.jurisdictionId, crime);
      if (parentJur && parentJur !== p.jurisdictionId) {
        const escalated: PoliceReport = {
          ...delivered,
          id: `${delivered.id}:esc:${parentJur}`,
          jurisdictionId: parentJur,
        };
        nextJurisdictions[parentJur] = ingestPoliceReport(
          nextJurisdictions[parentJur],
          crime,
          escalated,
        );
      }
    }
  }

  return {
    seed: state.seed,
    currentDay: day,
    crimes: state.crimes,
    ledger: nextLedger,
    pendingReports: remainingPending,
    jurisdictions: nextJurisdictions,
    toldPairs: nextToldPairs,
  };
}
