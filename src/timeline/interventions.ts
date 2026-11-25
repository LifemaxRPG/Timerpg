import { DEFAULT_SEVERITY, type CrimeEvent, type CrimeKind, type IdentityFileRecord } from '../crime';
import type { Id, Intervention as SimIntervention } from '../sim';
import { generateProfessorTrail } from './travellers';
import type {
  PlayerAnchorState,
  PlayerIdentityRecords,
  Timeline,
  TimelineCrimeKind,
  TimelineCrimeRecord,
  TimelineIntervention,
  TimelineInterventionInput,
  UnanchoredTraveller,
} from './types';

export interface CreateTimelineOptions {
  seed: string;
  startYear?: number;
  placeId?: string;
  player?: Partial<Omit<PlayerAnchorState, 'records'>> & {
    records?: Partial<PlayerIdentityRecords>;
  };
  travellers?: UnanchoredTraveller[];
  identityFiles?: Record<Id, IdentityFileRecord[]>;
}

const DEFAULT_RECORDS: PlayerIdentityRecords = {
  birthRecord: true,
  bankAccount: true,
  fingerprintsOnFile: true,
  dnaOnFile: false,
  legalIdentity: true,
  passportEligible: true,
  jobReferences: ['Capital Municipal Clerk', 'Former Workshop Supervisor'],
  knownFamilyIds: [],
};

/**
 * Creates a fresh plain-JSON `Timeline` ledger for `seed`.
 * Automatically generates the deterministic professor trail (5-9 traces across eras & continents).
 */
export function createTimeline(options: CreateTimelineOptions): Timeline {
  const startYear = options.startYear ?? 1950;
  const placeId = options.placeId ?? 'capital';
  const p = options.player ?? {};
  const records: PlayerIdentityRecords = {
    birthRecord: p.records?.birthRecord ?? DEFAULT_RECORDS.birthRecord,
    bankAccount: p.records?.bankAccount ?? DEFAULT_RECORDS.bankAccount,
    fingerprintsOnFile: p.records?.fingerprintsOnFile ?? DEFAULT_RECORDS.fingerprintsOnFile,
    dnaOnFile: p.records?.dnaOnFile ?? DEFAULT_RECORDS.dnaOnFile,
    legalIdentity: p.records?.legalIdentity ?? DEFAULT_RECORDS.legalIdentity,
    passportEligible: p.records?.passportEligible ?? DEFAULT_RECORDS.passportEligible,
    jobReferences: [...(p.records?.jobReferences ?? DEFAULT_RECORDS.jobReferences)],
    knownFamilyIds: [...(p.records?.knownFamilyIds ?? DEFAULT_RECORDS.knownFamilyIds)],
  };

  const travellers = options.travellers
    ? options.travellers.map((t) => ({
        ...t,
        traces: t.traces.map((tr) => ({
          ...tr,
          ...(tr.nextHint ? { nextHint: { ...tr.nextHint } } : {}),
        })),
      }))
    : [generateProfessorTrail(options.seed, placeId)];

  const identityFiles: Record<Id, IdentityFileRecord[]> = {};
  if (options.identityFiles) {
    for (const [k, list] of Object.entries(options.identityFiles)) {
      identityFiles[k] = list.map((rec) => ({
        ...rec,
        ...(rec.plateNumbers ? { plateNumbers: [...rec.plateNumbers] } : {}),
        ...(rec.serialNumbers ? { serialNumbers: [...rec.serialNumbers] } : {}),
      }));
    }
  }

  return {
    version: 1,
    seed: options.seed,
    startYear,
    placeId,
    interventions: [],
    player: {
      playerId: p.playerId ?? 'player',
      ...(p.anchoredPersonId !== undefined ? { anchoredPersonId: p.anchoredPersonId } : {}),
      originYear: p.originYear ?? 2025,
      birthYear: p.birthYear ?? 1995,
      homePlaceId: p.homePlaceId ?? placeId,
      selfErased: p.selfErased ?? false,
      ...(p.selfErasedYear !== undefined ? { selfErasedYear: p.selfErasedYear } : {}),
      records,
      inventory: (p.inventory ?? []).map((item) => ({ ...item })),
      memories: (p.memories ?? []).map((m) => ({ ...m })),
      interactions: (p.interactions ?? []).map((ix) => ({ ...ix })),
    },
    crimes: {},
    identityFiles,
    travellers,
  };
}

/**
 * Returns a new `Timeline` with `intervention` added, leaving the original `timeline` completely unchanged.
 */
export function branch(timeline: Timeline, intervention: TimelineInterventionInput): Timeline {
  const index = timeline.interventions.length;
  const id =
    intervention.id ??
    `iv:${index}:${intervention.year}:${intervention.kind}${intervention.crimeKind ? `:${intervention.crimeKind}` : ''}`;

  const entry: TimelineIntervention = {
    ...intervention,
    id,
    actorId: intervention.actorId ?? timeline.player.playerId,
  };

  return {
    ...timeline,
    interventions: [...timeline.interventions, entry],
  };
}

/**
 * Converts a `CrimeEvent` from `src/crime/types.ts` into a `TimelineInterventionInput` ready for `branch()`.
 */
export function crimeEventToIntervention(
  crime: CrimeEvent,
  overrides: Partial<TimelineInterventionInput> = {},
): TimelineInterventionInput {
  return {
    year: crime.when.year,
    placeId: crime.placeId,
    kind: 'crime',
    crimeKind: crime.kind,
    crimeId: crime.id,
    actorId: crime.culpritId,
    ...(crime.victimId !== undefined ? { targetId: crime.victimId } : {}),
    summary: `Committed ${crime.kind} at ${crime.placeId} in ${crime.when.year}`,
    ...overrides,
  };
}

/** Default severity for a `TimelineCrimeKind` (uses `DEFAULT_SEVERITY` from `src/crime/types.ts`). */
export function severityForTimelineCrime(kind: TimelineCrimeKind): number {
  if (kind === 'arson') return 0.85;
  return DEFAULT_SEVERITY[kind];
}

/**
 * Maps a `TimelineCrimeKind` to the nearest `CrimeKind` recognized by `src/court`'s `statuteForCrime`.
 */
export function toCourtCrimeKind(kind: TimelineCrimeKind): CrimeKind {
  if (kind === 'arson') return 'robbery';
  return kind;
}

/**
 * Translates one high-level `TimelineIntervention` into zero or more native `src/sim` `Intervention`s.
 * Every `CrimeKind` from `src/crime/types.ts` plus `'arson'`, `'demolish'`, `'self_erase'` is mapped cleanly.
 */
export function mapToSimInterventions(
  iv: TimelineIntervention,
  fallbackAnchoredPlayerId?: Id,
): SimIntervention[] {
  const y = iv.year;

  switch (iv.kind) {
    case 'kill':
      return iv.targetId ? [{ kind: 'kill', year: y, targetId: iv.targetId }] : [];

    case 'invest':
      return iv.businessId
        ? [{ kind: 'invest', year: y, businessId: iv.businessId, amount: iv.amount ?? 100 }]
        : [];

    case 'sabotage':
      return iv.businessId ? [{ kind: 'sabotage', year: y, businessId: iv.businessId }] : [];

    case 'introduce':
      return iv.targetId && iv.otherTargetId
        ? [{ kind: 'introduce', year: y, a: iv.targetId, b: iv.otherTargetId }]
        : [];

    case 'rumor':
      return iv.targetId
        ? [{ kind: 'rumor', year: y, targetId: iv.targetId, amount: iv.amount ?? -0.3 }]
        : [];

    case 'gift':
      return iv.targetId
        ? [{ kind: 'gift', year: y, targetId: iv.targetId, amount: Math.max(0, iv.amount ?? 50) }]
        : [];

    case 'arson':
    case 'demolish': {
      // Twelve sequential sabotage hits multiply capital by 0.4^12 (~1.6e-5), driving any open
      // business below the sim's bankruptcy floor (< 5) so `economy()` closes it that very year.
      if (iv.businessId) {
        const hits: SimIntervention[] = [];
        for (let i = 0; i < 12; i++) {
          hits.push({ kind: 'sabotage', year: y, businessId: iv.businessId });
        }
        return hits;
      }
      if (iv.targetId) {
        return [{ kind: 'rumor', year: y, targetId: iv.targetId, amount: -0.4 }];
      }
      return [];
    }

    case 'self_erase': {
      const anchored = iv.targetId ?? fallbackAnchoredPlayerId;
      return anchored ? [{ kind: 'kill', year: y, targetId: anchored }] : [];
    }

    case 'crime': {
      const ck = iv.crimeKind ?? 'theft';
      return mapCrimeKindToSim(ck, y, iv.targetId, iv.businessId, iv.amount);
    }

    case 'prevent_building':
    case 'record_fire':
    case 'record_flood':
      // Handled at the timeline/building/evidence layer; no direct person/business sim mutation.
      return [];
  }
}

function mapCrimeKindToSim(
  crimeKind: CrimeKind,
  year: number,
  targetId?: Id,
  businessId?: Id,
  amount?: number,
): SimIntervention[] {
  switch (crimeKind) {
    case 'murder':
      return targetId ? [{ kind: 'kill', year, targetId }] : [];

    case 'robbery':
      if (businessId) {
        return [
          { kind: 'sabotage', year, businessId },
          { kind: 'sabotage', year, businessId },
        ];
      }
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.25 }] : [];

    case 'burglary':
      if (businessId) {
        return [{ kind: 'sabotage', year, businessId }];
      }
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.2 }] : [];

    case 'assault':
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.35 }] : [];
    case 'intimidation':
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.28 }] : [];
    case 'bribery':
      if (businessId) {
        return [{ kind: 'invest', year, businessId, amount: -(Math.abs(amount ?? 25)) }];
      }
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.18 }] : [];
    case 'perjury':
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.15 }] : [];

    case 'car_theft':
      if (targetId) return [{ kind: 'rumor', year, targetId, amount: -0.18 }];
      return businessId ? [{ kind: 'invest', year, businessId, amount: -(Math.abs(amount ?? 25)) }] : [];

    case 'theft':
      if (businessId) {
        return [{ kind: 'invest', year, businessId, amount: -(Math.abs(amount ?? 25)) }];
      }
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.12 }] : [];

    case 'shoplifting':
      if (businessId) {
        return [{ kind: 'invest', year, businessId, amount: -(Math.abs(amount ?? 8)) }];
      }
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.08 }] : [];

    case 'vandalism':
      if (businessId) {
        return [{ kind: 'sabotage', year, businessId }];
      }
      return targetId ? [{ kind: 'rumor', year, targetId, amount: -0.12 }] : [];
  }
}

/**
 * Compiles all timeline interventions up to `maxYear` into sorted native `src/sim` interventions.
 */
export function compileSimInterventions(
  timeline: Timeline,
  maxYear?: number,
): SimIntervention[] {
  const out: SimIntervention[] = [];
  for (const iv of timeline.interventions) {
    if (maxYear !== undefined && iv.year > maxYear) continue;
    out.push(...mapToSimInterventions(iv, timeline.player.anchoredPersonId));
  }
  out.sort((a, b) => a.year - b.year);
  return out;
}

export interface RecordCrimeInput {
  id: string;
  kind: TimelineCrimeKind;
  year: number;
  day?: number;
  placeId?: string;
  culpritId?: Id;
  victimId?: Id;
  businessId?: Id;
  severity?: number;
  reported?: boolean;
  witnesses?: TimelineCrimeRecord['witnesses'];
  traces?: TimelineCrimeRecord['traces'];
  policeCase?: TimelineCrimeRecord['policeCase'];
  summary?: string;
}

/**
 * Immutably records a crime on the timeline ledger (for `caseStatusAt` and `looseThreads`).
 * Optionally also branches the corresponding intervention when `options.alsoBranch` is true.
 */
export function recordTimelineCrime(
  timeline: Timeline,
  input: RecordCrimeInput,
  options: { alsoBranch?: boolean; replacementLotLabel?: string } = {},
): Timeline {
  const placeId = input.placeId ?? timeline.placeId;
  const culpritId = input.culpritId ?? timeline.player.playerId;
  const day = input.day ?? Math.max(0, (input.year - timeline.startYear) * 365);

  const record: TimelineCrimeRecord = {
    id: input.id,
    kind: input.kind,
    year: input.year,
    day,
    placeId,
    culpritId,
    ...(input.victimId !== undefined ? { victimId: input.victimId } : {}),
    ...(input.businessId !== undefined ? { businessId: input.businessId } : {}),
    severity: input.severity ?? severityForTimelineCrime(input.kind),
    reported: input.reported ?? true,
    witnesses: (input.witnesses ?? []).map((w) => ({ ...w })),
    traces: (input.traces ?? []).map((t) => ({
      ...t,
      position: { ...t.position },
      ...(t.capturedAppearance ? { capturedAppearance: { ...t.capturedAppearance } } : {}),
    })),
    ...(input.policeCase ? { policeCase: JSON.parse(JSON.stringify(input.policeCase)) } : {}),
    ...(input.summary !== undefined ? { summary: input.summary } : {}),
  };

  let next: Timeline = {
    ...timeline,
    crimes: {
      ...timeline.crimes,
      [record.id]: record,
    },
  };

  if (options.alsoBranch) {
    if (input.kind === 'arson') {
      next = branch(next, {
        year: input.year,
        placeId,
        kind: 'arson',
        crimeId: record.id,
        actorId: culpritId,
        ...(input.businessId !== undefined ? { businessId: input.businessId } : {}),
        ...(input.victimId !== undefined ? { targetId: input.victimId } : {}),
        ...(options.replacementLotLabel !== undefined ? { replacementLotLabel: options.replacementLotLabel } : {}),
        summary: input.summary ?? `Arson committed in ${input.year}`,
      });
    } else {
      next = branch(next, {
        year: input.year,
        placeId,
        kind: 'crime',
        crimeKind: input.kind,
        crimeId: record.id,
        actorId: culpritId,
        ...(input.businessId !== undefined ? { businessId: input.businessId } : {}),
        ...(input.victimId !== undefined ? { targetId: input.victimId } : {}),
        summary: input.summary ?? `${input.kind} committed in ${input.year}`,
      });
    }
  }

  return next;
}
