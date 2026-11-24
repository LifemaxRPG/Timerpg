import {
  FORENSIC_ERA,
  caseHeatOnDay,
  caseStrength,
  detectTimeTravelAnomalies,
  dnaTierForYear,
  fingerprintTierForYear,
  type TimeTravelAnomaly,
} from '../crime';
import { courtEraForYear, statuteForCrime } from '../court';
import { POLICE_ERA, forceSizeForTown } from '../police';
import { fullName, isAlive, type Id, type World } from '../sim';
import { rand } from '../sim/rng';
import { presentAt } from './cache';
import { severityForTimelineCrime, toCourtCrimeKind } from './interventions';
import type { CaseStatusReport, Timeline, TimelineCrimeKind, TimelineCrimeRecord } from './types';

/**
 * Statute of limitations in years by crime kind (`null` = no statute of limitations).
 * Grounded in `src/court/law.ts` felony/misdemeanor classifications.
 */
export const STATUTE_LIMIT_YEARS: Readonly<Record<TimelineCrimeKind, number | null>> = {
  vandalism: 2,
  shoplifting: 2,
  theft: 3,
  car_theft: 5,
  burglary: 5,
  assault: 5,
  robbery: 7,
  arson: 10,
  murder: null,
};

/** Years before an unsolved crime goes cold under mid-century baseline policing. */
const BASE_ACTIVE_YEARS: Readonly<Record<TimelineCrimeKind, number>> = {
  vandalism: 1,
  shoplifting: 1,
  theft: 2,
  car_theft: 3,
  burglary: 3,
  assault: 3,
  robbery: 5,
  arson: 5,
  murder: 10,
};

/** Year after which police records gain off-site digital redundancy against local paper fires/floods. */
const DIGITAL_ARCHIVE_YEAR = 1995;

function eraActiveMultiplier(crimeYear: number, queryYear: number): number {
  const refYear = Math.max(crimeYear, queryYear);
  if (refYear < FORENSIC_ERA.fingerprintNationalYear) return 0.7; // pre-1924 paper & local beat
  if (refYear < FORENSIC_ERA.fingerprintAutomatedYear) return 1.0; // mid-century paper archives
  return 1.35; // automated AFIS / modern investigative continuity
}

function findRecordLoss(
  timeline: Timeline,
  crime: TimelineCrimeRecord,
  queryYear: number,
  worldAtQuery: World,
): { cause: 'fire' | 'flood'; year: number; detail: string } | undefined {
  // 1. Explicit fire/flood archive hazards in timeline interventions
  for (const iv of timeline.interventions) {
    if (iv.year < crime.year || iv.year > queryYear) continue;
    if (iv.placeId !== crime.placeId) continue;

    if (iv.kind === 'record_fire' || iv.kind === 'record_flood') {
      const cause: 'fire' | 'flood' = iv.kind === 'record_fire' ? 'fire' : 'flood';
      if (crime.year < DIGITAL_ARCHIVE_YEAR || iv.year < DIGITAL_ARCHIVE_YEAR) {
        return {
          cause,
          year: iv.year,
          detail: iv.summary ?? `Local police archives lost to a ${cause} in ${iv.year}`,
        };
      }
    }

    if (
      iv.kind === 'arson' &&
      (iv.slot === 'police_station' || iv.slot === 'courthouse' || iv.slot === 'records') &&
      crime.year < DIGITAL_ARCHIVE_YEAR
    ) {
      return {
        cause: 'fire',
        year: iv.year,
        detail: `Arson destroyed the ${iv.slot.replace('_', ' ')} records in ${iv.year}`,
      };
    }
  }

  // 2. Simulated town flood / fire stories from `src/sim/scenarios.ts` (pre-digital paper archives)
  if (crime.year < DIGITAL_ARCHIVE_YEAR && worldAtQuery.stories) {
    for (const story of worldAtQuery.stories) {
      if (story.year < crime.year || story.year > queryYear) continue;
      if (story.year >= DIGITAL_ARCHIVE_YEAR) continue;
      if (story.template === 'flood') {
        if (rand(timeline.seed, 'record-loss-flood', crime.id, story.id) < 0.35) {
          return {
            cause: 'flood',
            year: story.year,
            detail: `Paper police files were ruined in the ${story.year} town flood (${story.headline})`,
          };
        }
      }
    }
  }

  return undefined;
}

function wasExplicitlyKilledBefore(timeline: Timeline, personId: Id, beforeYear: number, excludeCrimeId?: string): boolean {
  return timeline.interventions.some(
    (iv) =>
      iv.year < beforeYear &&
      iv.crimeId !== excludeCrimeId &&
      ((iv.kind === 'kill' && iv.targetId === personId) ||
        (iv.kind === 'crime' && iv.crimeKind === 'murder' && iv.targetId === personId) ||
        (iv.kind === 'self_erase' && iv.targetId === personId)),
  );
}

function wasExplicitlyKilledOnOrBefore(timeline: Timeline, personId: Id, year: number, excludeCrimeId?: string): boolean {
  return timeline.interventions.some(
    (iv) =>
      iv.year <= year &&
      iv.crimeId !== excludeCrimeId &&
      ((iv.kind === 'kill' && iv.targetId === personId) ||
        (iv.kind === 'crime' && iv.crimeKind === 'murder' && iv.targetId === personId) ||
        (iv.kind === 'self_erase' && iv.targetId === personId)),
  );
}

/**
 * Answers whether police in `queryYear` are still looking for the player for `crimeId`,
 * with plain-English reasons grounded in `src/sim`, `src/crime`, `src/police`, and `src/court`.
 */
export function caseStatusAt(
  timeline: Timeline,
  crimeId: string,
  queryYear: number,
): CaseStatusReport {
  const courtEra = courtEraForYear(queryYear);
  const crime = timeline.crimes[crimeId];

  if (!crime) {
    return {
      crimeId,
      queryYear,
      eraId: courtEra.eraId,
      status: 'not_found',
      activelyLooking: false,
      crimeOccurred: false,
      isColdCase: false,
      statuteExpired: false,
      statuteLimitYears: null,
      ageYears: 0,
      recordsIntact: false,
      hasTestimony: false,
      activeWitnesses: [],
      erasedWitnesses: [],
      deceasedWitnesses: [],
      hasPhysicalEvidence: false,
      survivingTraces: 0,
      playerIdentifiableOnFile: false,
      canReopenWithModernForensics: false,
      forensicAnomalies: [],
      reasons: [`No crime with id "${crimeId}" is recorded on this timeline.`],
    };
  }

  const statuteLimitYears = STATUTE_LIMIT_YEARS[crime.kind];
  const courtStatute = statuteForCrime(toCourtCrimeKind(crime.kind), queryYear);
  const severity = crime.severity ?? severityForTimelineCrime(crime.kind);

  if (queryYear < crime.year) {
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'not_yet_committed',
      activelyLooking: false,
      crimeOccurred: false,
      isColdCase: false,
      statuteExpired: false,
      statuteLimitYears,
      ageYears: 0,
      recordsIntact: true,
      hasTestimony: false,
      activeWitnesses: [],
      erasedWitnesses: [],
      deceasedWitnesses: [],
      hasPhysicalEvidence: false,
      survivingTraces: 0,
      playerIdentifiableOnFile: false,
      canReopenWithModernForensics: false,
      forensicAnomalies: [],
      reasons: [`Crime has not occurred yet in ${queryYear} (committed in ${crime.year}).`],
    };
  }

  const simYearForCrime = Math.max(timeline.startYear, crime.year);
  const simYearForQuery = Math.max(timeline.startYear, queryYear);
  const snapAtCrime = presentAt(timeline, simYearForCrime);
  const snapAtQuery = presentAt(timeline, simYearForQuery);
  const worldAtCrime = snapAtCrime.world;
  const worldAtQuery = snapAtQuery.world;

  // Baseline at crime year (used to tell if a sim person/business was erased by timeline edits)
  const baselineTimeline: Timeline = { ...timeline, interventions: [] };
  const baseAtCrime = presentAt(baselineTimeline, simYearForCrime).world;

  // 1. Victim / target erasure check: erasing the victim BEFORE the crime makes the crime never happen.
  if (crime.victimId) {
    const vInCrimeWorld = worldAtCrime.people[crime.victimId];
    const vInBaseline = baseAtCrime.people[crime.victimId];
    const victimErasedFromExistence = Boolean(vInBaseline && !vInCrimeWorld);
    const victimDiedBeforeCrime = Boolean(
      vInCrimeWorld && vInCrimeWorld.deathYear !== undefined && vInCrimeWorld.deathYear < crime.year,
    );
    const victimExplicitlyKilledBefore = wasExplicitlyKilledBefore(
      timeline,
      crime.victimId,
      crime.year,
      crime.id,
    );

    if (victimErasedFromExistence || victimDiedBeforeCrime || victimExplicitlyKilledBefore) {
      const victimLabel = vInBaseline
        ? fullName(vInBaseline)
        : vInCrimeWorld
          ? fullName(vInCrimeWorld)
          : crime.victimId;
      return {
        crimeId,
        crimeKind: crime.kind,
        crimeYear: crime.year,
        queryYear,
        eraId: courtEra.eraId,
        status: 'never_happened',
        activelyLooking: false,
        crimeOccurred: false,
        isColdCase: false,
        statuteExpired: false,
        statuteLimitYears,
        ageYears: queryYear - crime.year,
        recordsIntact: false,
        hasTestimony: false,
        activeWitnesses: [],
        erasedWitnesses: crime.witnesses.map((w) => w.personId),
        deceasedWitnesses: [],
        hasPhysicalEvidence: false,
        survivingTraces: 0,
        playerIdentifiableOnFile: false,
        canReopenWithModernForensics: false,
        forensicAnomalies: [],
        reasons: [
          `Victim ${victimLabel} was erased or died before ${crime.year}, so the ${crime.kind} never happened.`,
        ],
      };
    }
  }

  // Also check if a business-only crime's target business never existed or closed strictly BEFORE `crime.year`
  if (crime.businessId && !crime.victimId) {
    const bAtCrime = worldAtCrime.businesses[crime.businessId];
    const bInBase = baseAtCrime.businesses[crime.businessId];
    const bizMissing = Boolean(bInBase && !bAtCrime);
    const bizClosedBefore = Boolean(bAtCrime && bAtCrime.closedYear !== undefined && bAtCrime.closedYear < crime.year);
    if (bizMissing || bizClosedBefore) {
      const bizLabel = bInBase?.name ?? bAtCrime?.name ?? crime.businessId;
      return {
        crimeId,
        crimeKind: crime.kind,
        crimeYear: crime.year,
        queryYear,
        eraId: courtEra.eraId,
        status: 'never_happened',
        activelyLooking: false,
        crimeOccurred: false,
        isColdCase: false,
        statuteExpired: false,
        statuteLimitYears,
        ageYears: queryYear - crime.year,
        recordsIntact: false,
        hasTestimony: false,
        activeWitnesses: [],
        erasedWitnesses: crime.witnesses.map((w) => w.personId),
        deceasedWitnesses: [],
        hasPhysicalEvidence: false,
        survivingTraces: 0,
        playerIdentifiableOnFile: false,
        canReopenWithModernForensics: false,
        forensicAnomalies: [],
        reasons: [
          `Target business ${bizLabel} did not exist or was already closed before ${crime.year}, so the ${crime.kind} never happened.`,
        ],
      };
    }
  }

  // 2. Witness evaluation: erasing a witness erases their testimony.
  const activeWitnesses: Id[] = [];
  const erasedWitnesses: Id[] = [];
  const deceasedWitnesses: Id[] = [];

  for (const w of crime.witnesses) {
    const pAtCrime = worldAtCrime.people[w.personId];
    const pInBase = baseAtCrime.people[w.personId];
    const witnessErased =
      (Boolean(pInBase) && !pAtCrime) ||
      Boolean(pAtCrime && pAtCrime.deathYear !== undefined && pAtCrime.deathYear < crime.year) ||
      wasExplicitlyKilledOnOrBefore(timeline, w.personId, crime.year, crime.id);

    if (witnessErased) {
      erasedWitnesses.push(w.personId);
      continue;
    }

    const pAtQuery = worldAtQuery.people[w.personId];
    if (pAtQuery && !isAlive(pAtQuery)) {
      deceasedWitnesses.push(w.personId);
    } else if (!pAtQuery && pInBase) {
      erasedWitnesses.push(w.personId);
    } else {
      activeWitnesses.push(w.personId);
    }
  }

  const reasons: string[] = [];
  const ageYears = queryYear - crime.year;

  if (erasedWitnesses.length > 0) {
    const names = erasedWitnesses.map((id) => {
      const p = baseAtCrime.people[id] ?? worldAtCrime.people[id];
      return p ? fullName(p) : id;
    });
    reasons.push(
      `Erased witness testimony: ${names.join(', ')} never witnessed the ${crime.year} ${crime.kind} in this timeline.`,
    );
  }
  if (deceasedWitnesses.length > 0) {
    reasons.push(`${deceasedWitnesses.length} witness(es) from ${crime.year} have since passed away.`);
  }

  // 3. Fire/flood record loss check
  const loss = findRecordLoss(timeline, crime, queryYear, worldAtQuery);
  if (loss) {
    reasons.push(`${loss.detail}; police no longer hold an active file for this case.`);
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'records_lost',
      activelyLooking: false,
      crimeOccurred: true,
      isColdCase: false,
      statuteExpired: statuteLimitYears !== null && ageYears >= statuteLimitYears,
      statuteLimitYears,
      ageYears,
      recordsIntact: false,
      recordsLostCause: loss.cause,
      recordsLostYear: loss.year,
      hasTestimony: activeWitnesses.length > 0,
      activeWitnesses,
      erasedWitnesses,
      deceasedWitnesses,
      hasPhysicalEvidence: false,
      survivingTraces: 0,
      playerIdentifiableOnFile: false,
      canReopenWithModernForensics: false,
      forensicAnomalies: [],
      reasons,
    };
  }

  // 4. Physical / forensic traces & self-erasure check
  const survivingTracesList = crime.traces.filter((t) => !t.destroyed && t.quality > 0.05);
  const survivingTraces = survivingTracesList.length;
  const hasPhysicalEvidence = survivingTraces > 0;
  const isCulpritPlayer = crime.culpritId === timeline.player.playerId;

  const hasPrintsOnFile = isCulpritPlayer
    ? !timeline.player.selfErased &&
      (timeline.player.records.fingerprintsOnFile ||
        (timeline.identityFiles[crime.culpritId]?.some((r) => r.hasPrints) ?? false))
    : (timeline.identityFiles[crime.culpritId]?.some((r) => r.hasPrints) ?? false);

  const hasDnaOnFile = isCulpritPlayer
    ? !timeline.player.selfErased &&
      (timeline.player.records.dnaOnFile ||
        (timeline.identityFiles[crime.culpritId]?.some((r) => r.hasDna) ?? false))
    : (timeline.identityFiles[crime.culpritId]?.some((r) => r.hasDna) ?? false);

  const playerIdentifiableOnFile = isCulpritPlayer && (hasPrintsOnFile || hasDnaOnFile);

  const forensicAnomalies: TimeTravelAnomaly[] = [];
  const culpritBirthYear = isCulpritPlayer
    ? timeline.player.selfErased
      ? undefined
      : timeline.player.birthYear
    : worldAtQuery.people[crime.culpritId]?.birthYear;

  for (const t of survivingTracesList) {
    if (t.sourcePersonId === crime.culpritId && culpritBirthYear !== undefined) {
      const found = detectTimeTravelAnomalies({
        traceId: t.id,
        crimeId: crime.id,
        traceKind: t.kind,
        personId: crime.culpritId,
        traceYear: t.createdYear,
        birthYear: culpritBirthYear,
      });
      forensicAnomalies.push(...found);
    }
  }

  if (isCulpritPlayer && timeline.player.selfErased && hasPhysicalEvidence) {
    reasons.push('Player has no fingerprints or DNA on file after self-erasure; physical traces remain unmatched.');
  }

  // Surviving testimony: either live witnesses OR filed statements from witnesses who weren't erased before the crime
  const nonErasedWitnessCount = activeWitnesses.length + deceasedWitnesses.length;
  const hasTestimony = nonErasedWitnessCount > 0;

  // If the crime had witnesses and ALL were erased, and there is no physical evidence, no case remains.
  if (!crime.reported && !hasPhysicalEvidence) {
    reasons.push(`Crime was never reported to police and left no collected physical evidence.`);
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'no_evidence',
      activelyLooking: false,
      crimeOccurred: true,
      isColdCase: false,
      statuteExpired: statuteLimitYears !== null && ageYears >= statuteLimitYears,
      statuteLimitYears,
      ageYears,
      recordsIntact: true,
      hasTestimony,
      activeWitnesses,
      erasedWitnesses,
      deceasedWitnesses,
      hasPhysicalEvidence,
      survivingTraces,
      playerIdentifiableOnFile,
      canReopenWithModernForensics: false,
      forensicAnomalies,
      reasons,
    };
  }

  if (crime.witnesses.length > 0 && !hasTestimony && !hasPhysicalEvidence) {
    reasons.push('No witness testimony or physical evidence survives; police have no leads to pursue.');
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'no_evidence',
      activelyLooking: false,
      crimeOccurred: true,
      isColdCase: false,
      statuteExpired: statuteLimitYears !== null && ageYears >= statuteLimitYears,
      statuteLimitYears,
      ageYears,
      recordsIntact: true,
      hasTestimony: false,
      activeWitnesses,
      erasedWitnesses,
      deceasedWitnesses,
      hasPhysicalEvidence: false,
      survivingTraces: 0,
      playerIdentifiableOnFile,
      canReopenWithModernForensics: false,
      forensicAnomalies,
      reasons,
    };
  }

  // 5. Era aging, statute of limitations, and cold-case determination
  const statuteExpired = statuteLimitYears !== null && ageYears >= statuteLimitYears;
  const pop = Math.max(1, Object.values(worldAtQuery.people).filter(isAlive).length);
  const officers = forceSizeForTown(pop, queryYear, POLICE_ERA);
  const detectiveBoost = pop >= POLICE_ERA.detectiveMinPopulation ? 1.15 : 1.0;
  const activeWindowYears = Math.max(
    1,
    Math.round(BASE_ACTIVE_YEARS[crime.kind] * eraActiveMultiplier(crime.year, queryYear) * detectiveBoost),
  );

  // Check if modern forensics (AFIS/DNA) can reopen a cold case that has NOT passed its statute
  const fpTier = fingerprintTierForYear(queryYear, FORENSIC_ERA);
  const dnaTier = dnaTierForYear(queryYear, FORENSIC_ERA);
  const hasCollectedPrints = survivingTracesList.some((t) => t.kind === 'fingerprint' && t.collected);
  const hasCollectedDna = survivingTracesList.some((t) => t.kind === 'dna' && t.collected);
  const canReopenWithModernForensics =
    !statuteExpired &&
    ((hasCollectedDna && dnaTier === 'database' && hasDnaOnFile) ||
      (hasCollectedPrints && fpTier === 'automated' && hasPrintsOnFile));

  // Optional direct heat/strength check when caller attached a G005/G008 `PoliceCase`
  if (crime.policeCase) {
    const queryDay = crime.day + ageYears * 365;
    const heat = caseHeatOnDay(crime.policeCase, queryDay);
    const strength = caseStrength(crime.policeCase, queryDay);
    reasons.push(
      `Police case telemetry (${courtEra.label}, ~${officers} officer(s)): heat ${heat.toFixed(1)}, strength ${strength.verdictTier}.`,
    );
  }

  const isColdByAge = ageYears >= activeWindowYears;

  // High-severity felonies (arson, robbery, murder) become cold cases once aged past the active window;
  // lower-severity crimes expire once past their statute of limitations.
  if (isColdByAge && (severity >= 0.75 || crime.kind === 'arson' || crime.kind === 'murder')) {
    if (statuteExpired) {
      reasons.push(
        `${ageYears}-year-old ${crime.kind} file from ${crime.year} is a cold case (${courtEra.label}).`,
        `Statute of limitations for ${crime.kind} (${statuteLimitYears} years) expired in ${crime.year + statuteLimitYears!}.`,
      );
      return {
        crimeId,
        crimeKind: crime.kind,
        crimeYear: crime.year,
        queryYear,
        eraId: courtEra.eraId,
        status: 'cold',
        activelyLooking: false,
        crimeOccurred: true,
        isColdCase: true,
        statuteExpired: true,
        statuteLimitYears,
        ageYears,
        recordsIntact: true,
        hasTestimony,
        activeWitnesses,
        erasedWitnesses,
        deceasedWitnesses,
        hasPhysicalEvidence,
        survivingTraces,
        playerIdentifiableOnFile,
        canReopenWithModernForensics: false,
        forensicAnomalies,
        reasons,
      };
    }

    // No statute expiry (e.g. murder): cold unless modern forensics reopened it with an on-file match
    if (canReopenWithModernForensics) {
      reasons.push(
        `${ageYears}-year-old ${crime.kind} case from ${crime.year} has no statute of limitations and can be reopened via ${dnaTier === 'database' && hasCollectedDna ? 'DNA database' : 'AFIS fingerprint'} matching in ${queryYear}.`,
      );
      return {
        crimeId,
        crimeKind: crime.kind,
        crimeYear: crime.year,
        queryYear,
        eraId: courtEra.eraId,
        status: 'active',
        activelyLooking: true,
        crimeOccurred: true,
        isColdCase: true,
        statuteExpired: false,
        statuteLimitYears,
        ageYears,
        recordsIntact: true,
        hasTestimony,
        activeWitnesses,
        erasedWitnesses,
        deceasedWitnesses,
        hasPhysicalEvidence,
        survivingTraces,
        playerIdentifiableOnFile,
        canReopenWithModernForensics: true,
        forensicAnomalies,
        reasons,
      };
    }

    reasons.push(
      `${ageYears}-year-old ${crime.kind} case from ${crime.year} has gone cold (${courtEra.label}); no active manhunt in ${queryYear}.`,
    );
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'cold',
      activelyLooking: false,
      crimeOccurred: true,
      isColdCase: true,
      statuteExpired: false,
      statuteLimitYears,
      ageYears,
      recordsIntact: true,
      hasTestimony,
      activeWitnesses,
      erasedWitnesses,
      deceasedWitnesses,
      hasPhysicalEvidence,
      survivingTraces,
      playerIdentifiableOnFile,
      canReopenWithModernForensics: false,
      forensicAnomalies,
      reasons,
    };
  }

  if (statuteExpired) {
    reasons.push(
      `Statute of limitations for ${courtStatute.statuteName} (${statuteLimitYears} years) expired in ${crime.year + statuteLimitYears!}.`,
    );
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'expired',
      activelyLooking: false,
      crimeOccurred: true,
      isColdCase: isColdByAge,
      statuteExpired: true,
      statuteLimitYears,
      ageYears,
      recordsIntact: true,
      hasTestimony,
      activeWitnesses,
      erasedWitnesses,
      deceasedWitnesses,
      hasPhysicalEvidence,
      survivingTraces,
      playerIdentifiableOnFile,
      canReopenWithModernForensics: false,
      forensicAnomalies,
      reasons,
    };
  }

  if (isColdByAge) {
    reasons.push(
      `${ageYears}-year-old ${crime.kind} case from ${crime.year} has gone cold in ${queryYear} (${courtEra.label}).`,
    );
    return {
      crimeId,
      crimeKind: crime.kind,
      crimeYear: crime.year,
      queryYear,
      eraId: courtEra.eraId,
      status: 'cold',
      activelyLooking: false,
      crimeOccurred: true,
      isColdCase: true,
      statuteExpired: false,
      statuteLimitYears,
      ageYears,
      recordsIntact: true,
      hasTestimony,
      activeWitnesses,
      erasedWitnesses,
      deceasedWitnesses,
      hasPhysicalEvidence,
      survivingTraces,
      playerIdentifiableOnFile,
      canReopenWithModernForensics,
      forensicAnomalies,
      reasons,
    };
  }

  // Active investigation!
  reasons.push(
    `Police in ${queryYear} (${courtEra.label}, ~${officers} officer(s)) are actively investigating the ${crime.year} ${crime.kind} (${ageYears} year${ageYears === 1 ? '' : 's'} old, within ${activeWindowYears}-year active window).`,
  );
  if (activeWitnesses.length > 0) {
    reasons.push(`${activeWitnesses.length} live witness(es) remain available to testify.`);
  }
  if (hasPhysicalEvidence) {
    reasons.push(`${survivingTraces} physical trace(s) remain on file.`);
  }

  return {
    crimeId,
    crimeKind: crime.kind,
    crimeYear: crime.year,
    queryYear,
    eraId: courtEra.eraId,
    status: 'active',
    activelyLooking: true,
    crimeOccurred: true,
    isColdCase: false,
    statuteExpired: false,
    statuteLimitYears,
    ageYears,
    recordsIntact: true,
    hasTestimony,
    activeWitnesses,
    erasedWitnesses,
    deceasedWitnesses,
    hasPhysicalEvidence,
    survivingTraces,
    playerIdentifiableOnFile,
    canReopenWithModernForensics,
    forensicAnomalies,
    reasons,
  };
}
