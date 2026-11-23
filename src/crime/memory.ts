import { clamp01 } from '../sim/rng';
import {
  type CrimeAccount,
  type CulpritDescription,
  type Sighting,
} from './types';

/** Below this confidence floor, a memory fades away completely. */
export const MEMORY_FORGET_FLOOR = 0.06;

/** Drop thresholds aligned with `MEMORY_DROP_ORDER`: clothing -> build -> vehicle -> hair -> height. */
const DROP_THRESHOLDS: readonly number[] = [0.54, 0.42, 0.32, 0.22, 0.12];

/**
 * Half-life of a crime memory in days, scaled by severity, source (first-hand vs hearsay),
 * victimhood, and refresh count.
 *
 * Reference first-hand bystander half-lives:
 * - vandalism   (0.22): ~5.1 days  (forgotten in ~2–3 weeks)
 * - shoplifting (0.28): ~5.9 days  (forgotten in ~3–4 weeks)
 * - theft       (0.44): ~9.9 days
 * - burglary    (0.64): ~21.6 days
 * - robbery     (0.80): ~44.5 days
 * - murder      (1.00): ~122.5 days (+ permanent traumatic floor for first-hand witnesses)
 */
export function memoryHalfLifeDays(
  severity: number,
  isFirstHand: boolean,
  isVictim: boolean,
  refreshes: number,
): number {
  const s = clamp01(severity);
  let halfLife = 3.5 * Math.pow(35, Math.pow(s, 1.5));
  if (!isFirstHand) halfLife *= 0.62;
  if (isVictim) halfLife *= 1.45;
  if (refreshes > 0) halfLife *= 1 + 0.35 * Math.min(refreshes, 5);
  return halfLife;
}

/**
 * Drops details from a description as confidence decays, shedding the least striking
 * details first (`clothingColor -> build -> vehicle -> hair -> height`).
 * Returns the original object reference when no fields drop.
 */
export function shedFadedDetails(
  desc: CulpritDescription,
  confidence: number,
  initialConfidence: number,
): CulpritDescription {
  const ratio = initialConfidence > 1e-4 ? clamp01(confidence / initialConfidence) : 0;
  const effective = Math.min(confidence * 1.15, ratio * 0.85 + confidence * 0.15);
  if (effective >= DROP_THRESHOLDS[0]!) return desc;

  const keepCloth = desc.clothingColor !== undefined && effective >= DROP_THRESHOLDS[0]!;
  const keepBuild = desc.build !== undefined && effective >= DROP_THRESHOLDS[1]!;
  const keepVeh = desc.vehicle !== undefined && effective >= DROP_THRESHOLDS[2]!;
  const keepHair = desc.hair !== undefined && effective >= DROP_THRESHOLDS[3]!;
  const keepHeight = desc.height !== undefined && effective >= DROP_THRESHOLDS[4]!;

  if (
    keepCloth === (desc.clothingColor !== undefined) &&
    keepBuild === (desc.build !== undefined) &&
    keepVeh === (desc.vehicle !== undefined) &&
    keepHair === (desc.hair !== undefined) &&
    keepHeight === (desc.height !== undefined)
  ) {
    return desc;
  }

  const out: CulpritDescription = {};
  if (keepHeight) out.height = desc.height;
  if (keepBuild) out.build = desc.build;
  if (keepHair) out.hair = desc.hair;
  if (keepCloth) out.clothingColor = desc.clothingColor;
  if (keepVeh) out.vehicle = desc.vehicle;
  return out;
}

/**
 * Computes decayed confidence after `elapsedDays`. First-hand witnesses of murder
 * (`severity >= 0.95`) retain a permanent traumatic floor so it never fades to nothing.
 */
export function decayedConfidence(
  currentConfidence: number,
  initialConfidence: number,
  elapsedDays: number,
  severity: number,
  isFirstHand: boolean,
  isVictim: boolean,
  refreshes: number,
): number {
  if (elapsedDays <= 0) return currentConfidence;
  const halfLife = memoryHalfLifeDays(severity, isFirstHand, isVictim, refreshes);
  const raw = currentConfidence * Math.pow(0.5, elapsedDays / halfLife);
  if (isFirstHand && severity >= 0.95) {
    const traumaFloor = Math.max(0.18, initialConfidence * 0.24);
    return Math.max(traumaFloor, raw);
  }
  return raw;
}

/** Advances a standalone `Sighting` to `currentDay`. Returns `null` if forgotten. */
export function decaySighting(
  sighting: Sighting,
  severity: number,
  currentDay: number,
): Sighting | null {
  const elapsed = Math.max(0, currentDay - sighting.lastRefreshedDay);
  if (elapsed === 0) return sighting;

  const conf = decayedConfidence(
    sighting.confidence,
    sighting.initialConfidence,
    elapsed,
    severity,
    true,
    sighting.isVictim,
    sighting.refreshes,
  );
  if (conf < MEMORY_FORGET_FLOOR) return null;

  const description = shedFadedDetails(sighting.description, conf, sighting.initialConfidence);
  const keepId = sighting.identifiedCulpritId !== undefined && conf >= 0.18;

  return {
    crimeId: sighting.crimeId,
    witnessId: sighting.witnessId,
    isVictim: sighting.isVictim,
    confidence: conf,
    initialConfidence: sighting.initialConfidence,
    dayWitnessed: sighting.dayWitnessed,
    lastRefreshedDay: sighting.lastRefreshedDay,
    ...(keepId ? { identifiedCulpritId: sighting.identifiedCulpritId } : {}),
    description,
    refreshes: sighting.refreshes,
  };
}

export type RefreshReason = 'saw_culprit' | 'police_interview';

/** Refreshes a standalone `Sighting` on re-sighting or police interview. */
export function refreshSighting(
  sighting: Sighting,
  day: number,
  reason: RefreshReason,
): Sighting {
  const boost = reason === 'saw_culprit' ? 0.20 : 0.12;
  const ceiling = Math.max(sighting.initialConfidence, sighting.confidence);
  return {
    ...sighting,
    lastRefreshedDay: Math.max(sighting.lastRefreshedDay, day),
    confidence: clamp01(Math.min(ceiling, sighting.confidence + boost)),
    refreshes: sighting.refreshes + 1,
  };
}

/** Precomputed per-day decay multipliers for one crime severity to avoid repeated Math.pow. */
export interface DailyDecayFactors {
  severity: number;
  firstHandNormal: number;
  secondHandNormal: number;
}

export function createDailyDecayFactors(severity: number): DailyDecayFactors {
  return {
    severity,
    firstHandNormal: Math.pow(0.5, 1 / memoryHalfLifeDays(severity, true, false, 0)),
    secondHandNormal: Math.pow(0.5, 1 / memoryHalfLifeDays(severity, false, false, 0)),
  };
}

/** Decays a ledger `CrimeAccount` by one day step. Returns `null` if forgotten. */
export function decayAccountOneDay(
  account: CrimeAccount,
  severity: number,
  currentDay: number,
  factors?: DailyDecayFactors,
): CrimeAccount | null {
  if (currentDay <= account.lastRefreshedDay) return account;
  const isFirstHand = account.source === 'first_hand';
  let conf: number;
  if (factors && !account.isVictim && account.refreshes === 0) {
    const raw = account.confidence * (isFirstHand ? factors.firstHandNormal : factors.secondHandNormal);
    conf =
      isFirstHand && severity >= 0.95
        ? Math.max(Math.max(0.18, account.initialConfidence * 0.24), raw)
        : raw;
  } else {
    conf = decayedConfidence(
      account.confidence,
      account.initialConfidence,
      1,
      severity,
      isFirstHand,
      account.isVictim,
      account.refreshes,
    );
  }
  if (conf < MEMORY_FORGET_FLOOR) return null;

  const description = shedFadedDetails(account.description, conf, account.initialConfidence);
  const keepId = account.identifiedCulpritId !== undefined && conf >= 0.18;

  const next: CrimeAccount = {
    crimeId: account.crimeId,
    knowerId: account.knowerId,
    source: account.source,
    isVictim: account.isVictim,
    hops: account.hops,
    dayLearned: account.dayLearned,
    lastRefreshedDay: account.lastRefreshedDay,
    confidence: conf,
    initialConfidence: account.initialConfidence,
    description,
    reported: account.reported,
    refreshes: account.refreshes,
  };
  if (account.heardFrom !== undefined) next.heardFrom = account.heardFrom;
  if (keepId) next.identifiedCulpritId = account.identifiedCulpritId;
  if (account.reportQueuedDay !== undefined) next.reportQueuedDay = account.reportQueuedDay;
  if (account.reportedJurisdiction !== undefined) next.reportedJurisdiction = account.reportedJurisdiction;
  return next;
}

/** Refreshes a ledger `CrimeAccount` on re-sighting or police interview. */
export function refreshAccount(
  account: CrimeAccount,
  day: number,
  reason: RefreshReason,
): CrimeAccount {
  const boost = reason === 'saw_culprit' ? 0.20 : 0.12;
  const ceiling = Math.max(account.initialConfidence, account.confidence);
  return {
    ...account,
    lastRefreshedDay: Math.max(account.lastRefreshedDay, day),
    confidence: clamp01(Math.min(ceiling, account.confidence + boost)),
    refreshes: account.refreshes + 1,
  };
}
