import type { Id } from '../sim/types';

/**
 * Single tunable table of historical forensic science & record-keeping milestones (calendar years).
 * Adjust any year here (or pass a custom table in options) to rebalance era gating.
 */
export interface ForensicEraTable {
  /** Manual fingerprint comparison against a local station card file. */
  fingerprintLocalYear: number;
  /** Central / national fingerprint bureau (mail-in card search across jurisdictions). */
  fingerprintNationalYear: number;
  /** Automated fingerprint database matching (AFIS). */
  fingerprintAutomatedYear: number;
  /** Manual forensic DNA profiling (comparing scene sample to a known suspect sample). */
  dnaAnalysisYear: number;
  /** Indexed DNA database enabling cross-jurisdiction cold hits. */
  dnaDatabaseYear: number;
  /** Monochrome (B&W) security cameras (CCTV). */
  cameraBwYear: number;
  /** Colour security cameras (captures clothing & vehicle colour reliably). */
  cameraColorYear: number;
  /** High-definition digital cameras (stronger facial & plate detail). */
  cameraHdYear: number;
  /** Mandatory vehicle license plate registration (local registry). */
  plateRegistrationYear: number;
  /** Computerized cross-jurisdiction license plate database. */
  plateDatabaseYear: number;
  /** Serial numbers commonly stamped on firearms & watches. */
  serialFirearmWatchYear: number;
  /** Serial numbers on bicycles, typewriters & major appliances. */
  serialApplianceBikeYear: number;
  /** Serial numbers on consumer electronics, cameras & phones. */
  serialElectronicsYear: number;
}

export const FORENSIC_ERA: Readonly<ForensicEraTable> = {
  fingerprintLocalYear: 1910,
  fingerprintNationalYear: 1924,
  fingerprintAutomatedYear: 1980,
  dnaAnalysisYear: 1987,
  dnaDatabaseYear: 1995,
  cameraBwYear: 1970,
  cameraColorYear: 1990,
  cameraHdYear: 2010,
  plateRegistrationYear: 1905,
  plateDatabaseYear: 1972,
  serialFirearmWatchYear: 1900,
  serialApplianceBikeYear: 1930,
  serialElectronicsYear: 1970,
};

export type FingerprintTier = 'none' | 'local_manual' | 'national_manual' | 'automated';
export type DnaTier = 'none' | 'manual_suspect' | 'database';
export type CameraTier = 'none' | 'bw' | 'color' | 'hd';
export type PlateTier = 'none' | 'local_registry' | 'database';

export type SerialItemClass = 'firearm' | 'watch' | 'bicycle' | 'appliance' | 'electronics' | 'vehicle' | 'other';

/** What fingerprint science can do in `year`. */
export function fingerprintTierForYear(
  year: number,
  era: Readonly<ForensicEraTable> = FORENSIC_ERA,
): FingerprintTier {
  if (year < era.fingerprintLocalYear) return 'none';
  if (year < era.fingerprintNationalYear) return 'local_manual';
  if (year < era.fingerprintAutomatedYear) return 'national_manual';
  return 'automated';
}

/** What DNA science can do in `year`. */
export function dnaTierForYear(
  year: number,
  era: Readonly<ForensicEraTable> = FORENSIC_ERA,
): DnaTier {
  if (year < era.dnaAnalysisYear) return 'none';
  if (year < era.dnaDatabaseYear) return 'manual_suspect';
  return 'database';
}

/** Security camera capability in `year`. */
export function cameraTierForYear(
  year: number,
  era: Readonly<ForensicEraTable> = FORENSIC_ERA,
): CameraTier {
  if (year < era.cameraBwYear) return 'none';
  if (year < era.cameraColorYear) return 'bw';
  if (year < era.cameraHdYear) return 'color';
  return 'hd';
}

/** Vehicle license-plate registry capability in `year`. */
export function plateTierForYear(
  year: number,
  era: Readonly<ForensicEraTable> = FORENSIC_ERA,
): PlateTier {
  if (year < era.plateRegistrationYear) return 'none';
  if (year < era.plateDatabaseYear) return 'local_registry';
  return 'database';
}

/** Infers broad serial-number item class from an item name/description. */
export function classifySerialItem(itemName: string): SerialItemClass {
  const s = itemName.toLowerCase();
  if (/(revolver|pistol|rifle|gun|shotgun|firearm)/.test(s)) return 'firearm';
  if (/(watch|chronograph|pocketwatch|timepiece)/.test(s)) return 'watch';
  if (/(bicycle|bike|motorcycle)/.test(s)) return 'bicycle';
  if (/(radio|tv|television|camera|phone|laptop|computer|stereo|vcr|recorder|electronics)/.test(s)) {
    return 'electronics';
  }
  if (/(typewriter|sewing|appliance|fridge|safe|register|drill|saw)/.test(s)) return 'appliance';
  if (/(car|sedan|coupe|pickup|truck|van|wagon|vehicle)/.test(s)) return 'vehicle';
  return 'other';
}

/** Whether stolen goods of `itemClass` carry traceable serial numbers in `year`. */
export function itemHasSerialInEra(
  itemClass: SerialItemClass,
  year: number,
  era: Readonly<ForensicEraTable> = FORENSIC_ERA,
): boolean {
  switch (itemClass) {
    case 'firearm':
    case 'watch':
      return year >= era.serialFirearmWatchYear;
    case 'bicycle':
    case 'appliance':
      return year >= era.serialApplianceBikeYear;
    case 'electronics':
      return year >= era.serialElectronicsYear;
    case 'vehicle':
      return year >= era.plateRegistrationYear;
    case 'other':
      return false;
  }
}

export type TimeAnomalyKind = 'before_birth' | 'before_era_arrival';

/**
 * Flags an impossible chronological match produced by time travel
 * (e.g. fingerprints left in 1952 matching a person born in 1995, or left before they entered that era).
 */
export interface TimeTravelAnomaly {
  kind: TimeAnomalyKind;
  traceId: string;
  crimeId: string;
  personId: Id;
  traceYear: number;
  referenceYear: number;
  /** Positive number of years by which the trace predates birth or era arrival. */
  yearsImpossible: number;
  summary: string;
}

/**
 * Checks whether a trace left in `traceYear` matching `personId` violates chronology
 * relative to `birthYear` or `firstEraArrivalYear`.
 */
export function detectTimeTravelAnomalies(params: {
  traceId: string;
  crimeId: string;
  traceKind: string;
  personId: Id;
  traceYear: number;
  birthYear?: number;
  firstEraArrivalYear?: number;
}): TimeTravelAnomaly[] {
  const anomalies: TimeTravelAnomaly[] = [];
  const { traceId, crimeId, traceKind, personId, traceYear, birthYear, firstEraArrivalYear } = params;

  if (birthYear !== undefined && traceYear < birthYear) {
    const gap = birthYear - traceYear;
    anomalies.push({
      kind: 'before_birth',
      traceId,
      crimeId,
      personId,
      traceYear,
      referenceYear: birthYear,
      yearsImpossible: gap,
      summary: `${traceKind} from ${traceYear} matches ${personId} born in ${birthYear} (${gap} year${gap === 1 ? '' : 's'} before birth)`,
    });
  }

  if (firstEraArrivalYear !== undefined && traceYear < firstEraArrivalYear) {
    const gap = firstEraArrivalYear - traceYear;
    anomalies.push({
      kind: 'before_era_arrival',
      traceId,
      crimeId,
      personId,
      traceYear,
      referenceYear: firstEraArrivalYear,
      yearsImpossible: gap,
      summary: `${traceKind} from ${traceYear} predates ${personId}'s arrival in ${firstEraArrivalYear} (${gap} year${gap === 1 ? '' : 's'} early)`,
    });
  }

  return anomalies;
}
