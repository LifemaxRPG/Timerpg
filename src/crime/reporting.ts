import { clamp01, rand, rngFor } from '../sim/rng';
import { affinity } from '../sim/social';
import { ageIn, isAlive, type Id, type Person, type World } from '../sim/types';
import { cachedAffinity, getSocialIndex, type WorldSocialIndex } from './gossip';
import type { CrimeAccount, CrimeContext, CrimeEvent, CrimeKind, PendingReport } from './types';

function areDirectKin(a: Person, b: Person): boolean {
  if (a.spouse === b.id || b.spouse === a.id) return true;
  if (a.parents.includes(b.id) || b.parents.includes(a.id)) return true;
  for (let i = 0; i < a.parents.length; i++) {
    if (b.parents.includes(a.parents[i]!)) return true;
  }
  return false;
}

/**
 * Deterministic per-person "won't talk to police" check.
 * Low-reputation and less diligent citizens refuse more often; extreme crimes
 * (`severity >= 0.90`, e.g. murder) break silence for most people except a hard core.
 */
export function refusesPolice(
  seed: string,
  person: Person,
  severity: number,
  cachedRoll?: number,
): boolean {
  const rep01 = (person.reputation + 1) * 0.5; // 0..1
  let baseRefusal = 0.06 + (1 - rep01) * 0.24 + (1 - person.traits.diligence) * 0.08;
  if (severity >= 0.90) {
    baseRefusal *= 0.35;
  } else if (severity >= 0.70) {
    baseRefusal *= 0.72;
  }
  const roll = cachedRoll ?? rand(seed, 'wont_talk_police', person.id);
  return roll < clamp01(baseRefusal);
}

/**
 * Historical reporting latency (in hours and whole simulated days) from deciding to report
 * until the report actually reaches the police station.
 *
 * - Pre-1900 (no telephones): reporter walks/rides to the station (12–72 h, always >= 1 day).
 * - 1900–1934 (early party-line/shop phones): mix of shop phone and walking (1–28 h, 0–1 day).
 * - 1935–1999 (home & shop telephones): 0.15–3.0 h, arrives same day (`delayDays = 0`).
 * - 2000+ (mobile phones): 0.05–0.75 h, arrives same day (`delayDays = 0`).
 */
export function reportLatencyForEra(
  year: number,
  severity: number,
  stationDistanceKm: number,
  seed: string,
  crimeId: string,
  reporterId: Id,
): { delayHours: number; delayDays: number } {
  const dist = Math.max(0.2, stationDistanceKm);
  const urgency = 0.45 + 0.55 * clamp01(severity);
  const r = rngFor(seed, 'report_latency', year, crimeId, reporterId);

  if (year < 1900) {
    // No phones: must wait for daylight/travel and walk or ride to the constable/station
    const walkHours = (dist / 3.8) * (1.4 - 0.5 * urgency);
    const prepHours = (10 + r() * 34) * (1.25 - 0.45 * urgency);
    const delayHours = Math.round(Math.min(72, Math.max(12, walkHours + prepHours)) * 10) / 10;
    const delayDays = Math.max(1, Math.round(delayHours / 24));
    return { delayHours, delayDays };
  }

  if (year < 1935) {
    const hasPhoneAccess = r() < 0.45 + (year - 1900) * 0.012;
    if (hasPhoneAccess) {
      const delayHours = Math.round((1.0 + r() * 5.0 * (1.2 - 0.4 * urgency)) * 100) / 100;
      return { delayHours, delayDays: 0 };
    }
    const walkHours = Math.min(30, Math.max(6, (dist / 4.5) * 1.5 + 6 + r() * 14));
    const delayHours = Math.round(walkHours * 10) / 10;
    return { delayHours, delayDays: delayHours >= 14 ? 1 : 0 };
  }

  if (year < 2000) {
    const delayHours = Math.round((0.15 + r() * 2.6 * (1.15 - 0.45 * urgency)) * 100) / 100;
    return { delayHours, delayDays: 0 };
  }

  const delayHours = Math.round((0.05 + r() * 0.65 * (1.15 - 0.4 * urgency)) * 100) / 100;
  return { delayHours, delayDays: 0 };
}

/**
 * Reporting-behaviour modifiers for covert / courtroom interference crimes.
 * Ordinary street/property crimes are omitted so their reporting curves stay unchanged.
 */
export interface CrimeReportingModifier {
  /** Multiplier on reporting probability when the reporter is the targeted person. */
  victimScale: number;
  /** Multiplier on reporting probability for a first-hand third-party witness. */
  witnessScale: number;
  /** Multiplier on reporting probability for second-hand hearsay/gossip. */
  hearsayScale: number;
  /** If true, skips the >= 0.90 violent/property victim floor (e.g. scared or approached target). */
  suppressVictimFloor: boolean;
}

export const CRIME_REPORTING_MODIFIERS: Readonly<Partial<Record<CrimeKind, CrimeReportingModifier>>> = {
  bribery: {
    victimScale: 0.55,
    witnessScale: 0.92,
    hearsayScale: 0.60,
    suppressVictimFloor: true,
  },
  perjury: {
    victimScale: 0.72,
    witnessScale: 0.78,
    hearsayScale: 0.40,
    suppressVictimFloor: true,
  },
  intimidation: {
    victimScale: 0.42,
    witnessScale: 1.05,
    hearsayScale: 0.75,
    suppressVictimFloor: true,
  },
};

/**
 * Single-day probability that `reporter` decides to report `crime` to police,
 * factoring severity, victim loyalty, culprit shielding (spouse/kin/friends),
 * reputation, per-person police refusal, and recall confidence.
 */
export function reportingProbability(
  seed: string,
  world: World,
  crime: CrimeEvent,
  account: CrimeAccount,
  reporter: Person,
  cachedRefusalRoll?: number,
  socialIndex?: WorldSocialIndex,
): number {
  if (account.reported) return 0;
  if (reporter.id === crime.culpritId) return 0;
  if (account.confidence < 0.16) return 0;
  if (account.source === 'second_hand' && account.hops >= 2 && crime.severity < 0.70) return 0;
  if (refusesPolice(seed, reporter, crime.severity, cachedRefusalRoll)) return 0;

  let base = 0.05 + 0.72 * Math.pow(clamp01(crime.severity), 1.35);
  const kindMod = CRIME_REPORTING_MODIFIERS[crime.kind];

  if (account.isVictim) {
    if (kindMod?.suppressVictimFloor) {
      // Covert/coercive court crimes: target willingness scales with personal resolve/diligence
      const resolveBoost = 0.75 + 0.50 * clamp01(reporter.traits.diligence);
      base = (base + 0.25) * kindMod.victimScale * resolveBoost;
    } else {
      base += 0.45;
      if (crime.severity >= 0.45) base = Math.max(base, 0.90);
    }
  } else if (kindMod) {
    base *= account.source === 'second_hand' ? kindMod.hearsayScale : kindMod.witnessScale;
  }

  if (account.source === 'second_hand') {
    const teller = account.heardFrom ? world.people[account.heardFrom] : undefined;
    const fromKin = teller !== undefined && areDirectKin(reporter, teller);
    const hopScale = account.hops === 1 ? (fromKin ? 0.56 : 0.28) : 0.09;
    base *= hopScale;
  }

  if (crime.victimId && !account.isVictim) {
    const victim = world.people[crime.victimId];
    if (victim) {
      if (areDirectKin(reporter, victim)) {
        base += 0.32;
      } else {
        const vAff = socialIndex
          ? cachedAffinity(world, socialIndex, reporter.id, victim.id)
          : affinity(world, reporter.id, victim.id);
        if (vAff > 0.20) base += 0.22 * clamp01(vAff);
      }
    }
  }

  const culprit = world.people[crime.culpritId];
  const knowsOrRecognizesCulprit =
    account.identifiedCulpritId === crime.culpritId ||
    (culprit !== undefined && areDirectKin(reporter, culprit));

  if (culprit && knowsOrRecognizesCulprit) {
    if (reporter.spouse === culprit.id) {
      const aff = socialIndex
        ? cachedAffinity(world, socialIndex, reporter.id, culprit.id)
        : affinity(world, reporter.id, culprit.id);
      const shield = crime.severity >= 0.95 && aff < 0 ? 0.45 : 0.94;
      base *= 1 - shield;
    } else if (areDirectKin(reporter, culprit)) {
      const shield = crime.severity >= 0.95 ? 0.60 : 0.84;
      base *= 1 - shield;
    } else {
      const cAff = socialIndex
        ? cachedAffinity(world, socialIndex, reporter.id, culprit.id)
        : affinity(world, reporter.id, culprit.id);
      if (cAff > 0.15) {
        const shield = clamp01((cAff - 0.10) * 0.82) * (crime.severity >= 0.95 ? 0.72 : 1.0);
        base *= 1 - shield;
      } else if (cAff < -0.20) {
        base += 0.16 * clamp01(-cAff);
      }
    }
  }

  const repScale = 0.75 + 0.35 * ((reporter.reputation + 1) * 0.5);
  base *= repScale;
  base *= 0.35 + 0.65 * clamp01(account.confidence);

  return clamp01(base);
}

export interface ReportingStepResult {
  queuedReports: PendingReport[];
  markedReported: Array<{ knowerId: Id; jurisdictionId: string }>;
}

/** Evaluates knowers of `crime` on `day` and emits any new `PendingReport`s. */
export function evaluateReportingForCrime(
  seed: string,
  world: World,
  crime: CrimeEvent,
  accounts: Record<Id, CrimeAccount>,
  day: number,
  ctx?: CrimeContext,
  precomputedIndex?: WorldSocialIndex,
  sortedKnowerIds?: readonly Id[],
): ReportingStepResult {
  const knowerIds = sortedKnowerIds ?? Object.keys(accounts).sort();
  if (knowerIds.length === 0) return { queuedReports: [], markedReported: [] };

  const year = ctx?.year ?? world.year;
  const jurisdictionOf = ctx?.jurisdictionOf ?? ((placeId: string) => `jur:${placeId}`);
  const townOf = ctx?.townOf;
  const placeTownOf = ctx?.placeTownOf ?? ((placeId: string) => crime.townId ?? placeId);
  const crimeTown = placeTownOf(crime.placeId);
  const crimeJurisdiction = jurisdictionOf(crime.placeId);
  const stationDist = ctx?.stationDistanceKm ? ctx.stationDistanceKm(crime.placeId) : 2.5;
  const idx = precomputedIndex ?? getSocialIndex(world, ctx);

  const queuedReports: PendingReport[] = [];
  const markedReported: Array<{ knowerId: Id; jurisdictionId: string }> = [];

  const rDay = rngFor(seed, 'report_decide_day', day, crime.id);

  for (let i = 0; i < knowerIds.length; i++) {
    const knowerId = knowerIds[i]!;
    const acc = accounts[knowerId]!;
    const roll = rDay();
    if (acc.reported) continue;
    if (acc.confidence < 0.16) continue;

    const decisionWindow = acc.source === 'first_hand' ? 6 : (acc.hops === 1 ? 5 : 3);
    if (day - acc.lastRefreshedDay > decisionWindow) continue;
    if (acc.source === 'second_hand' && acc.hops >= 2 && crime.severity < 0.70) continue;

    const person = world.people[knowerId];
    if (!person || !isAlive(person)) continue;
    if (ageIn(person, year) < 10) continue;

    // Fast upper-bound exit before affinity/refusal checks for hearsay accounts
    if (acc.source === 'second_hand' && !acc.isVictim) {
      const upper =
        (0.05 + 0.72 * crime.severity + 0.35) *
        (acc.hops === 1 ? 0.56 : 0.09) *
        (0.35 + 0.65 * acc.confidence);
      if (roll >= upper) continue;
    }

    let cachedRefusal = idx.refusalRollCache.get(knowerId);
    if (cachedRefusal === undefined) {
      cachedRefusal = rand(seed, 'wont_talk_police', knowerId);
      idx.refusalRollCache.set(knowerId, cachedRefusal);
    }

    const prob = reportingProbability(seed, world, crime, acc, person, cachedRefusal, idx);
    if (prob <= 0 || roll >= prob) continue;

    const targets: string[] = [];
    const personTown = townOf ? townOf(knowerId) : crimeTown;
    if (personTown !== crimeTown) {
      targets.push(jurisdictionOf(personTown));
    } else {
      targets.push(crimeJurisdiction);
    }

    if (ctx?.travelTownsOf) {
      const visiting = ctx.travelTownsOf(knowerId, day);
      for (let v = 0; v < visiting.length; v++) {
        const vt = visiting[v]!;
        const vJur = jurisdictionOf(vt);
        if (!targets.includes(vJur)) targets.push(vJur);
      }
    }

    const { delayHours, delayDays } = reportLatencyForEra(
      year,
      crime.severity,
      stationDist,
      seed,
      crime.id,
      knowerId,
    );

    for (let t = 0; t < targets.length; t++) {
      const jur = targets[t]!;
      queuedReports.push({
        id: `rep:${crime.id}:${knowerId}:${jur}:${day}`,
        crimeId: crime.id,
        reporterId: knowerId,
        jurisdictionId: jur,
        filedDay: day,
        arrivalDay: day + delayDays,
        delayHours,
        source: acc.source,
        isVictim: acc.isVictim,
        confidence: acc.confidence,
        ...(acc.identifiedCulpritId ? { identifiedCulpritId: acc.identifiedCulpritId } : {}),
        description: {
          ...acc.description,
          ...(acc.description.vehicle ? { vehicle: { ...acc.description.vehicle } } : {}),
        },
      });
    }

    markedReported.push({ knowerId, jurisdictionId: targets[0]! });
  }

  return { queuedReports, markedReported };
}
