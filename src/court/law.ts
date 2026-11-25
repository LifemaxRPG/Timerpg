import { FORENSIC_ERA, caseStrength, type CrimeKind, type PoliceCase } from '../crime';
import { clamp01, rand } from '../sim/rng';
import type {
  BailDecision,
  Charge,
  CourtEraId,
  CourtEraProfile,
  DefenseLawyer,
  EraEvidenceAdmissibility,
  LawyerTier,
  PleaOffer,
  StatuteRule,
} from './types';

export const COURT_ERA_PROFILES: readonly CourtEraProfile[] = [
  {
    eraId: 'frontier_circuit',
    label: 'Circuit Court Era (pre-1915)',
    minYear: 1800,
    maxYear: 1914,
    moneyScale: 0.12,
    publicDefenderFunded: false,
    arraignmentDays: 4,
    admissibility: {
      fingerprints: false,
      automatedPrints: false,
      dna: false,
      camera: false,
      serials: true,
      plates: false,
    },
  },
  {
    eraId: 'interwar',
    label: 'Interwar Court Era (1915–1949)',
    minYear: 1915,
    maxYear: 1949,
    moneyScale: 0.30,
    publicDefenderFunded: false,
    arraignmentDays: 10,
    admissibility: {
      fingerprints: true,
      automatedPrints: false,
      dna: false,
      camera: false,
      serials: true,
      plates: true,
    },
  },
  {
    eraId: 'mid_century',
    label: 'Mid-Century Court Era (1950–1985)',
    minYear: 1950,
    maxYear: 1985,
    moneyScale: 1.0,
    publicDefenderFunded: true,
    arraignmentDays: 18,
    admissibility: {
      fingerprints: true,
      automatedPrints: true,
      dna: false,
      camera: false,
      serials: true,
      plates: true,
    },
  },
  {
    eraId: 'modern',
    label: 'Modern Court Era (1986+)',
    minYear: 1986,
    maxYear: 2200,
    moneyScale: 3.0,
    publicDefenderFunded: true,
    arraignmentDays: 30,
    admissibility: {
      fingerprints: true,
      automatedPrints: true,
      dna: true,
      camera: true,
      serials: true,
      plates: true,
    },
  },
];

interface BaseStatute {
  statuteName: string;
  felony: boolean;
  minJailDays: number;
  maxJailDays: number;
  minFine: number;
  maxFine: number;
  baseBail: number;
  allowNoBail: boolean;
}

/** Baseline mid-century (moneyScale = 1.0) statute ranges by CrimeKind. */
const BASE_STATUTES: Readonly<Record<CrimeKind, BaseStatute>> = {
  vandalism: {
    statuteName: 'Malicious Mischief & Vandalism',
    felony: false,
    minJailDays: 0,
    maxJailDays: 30,
    minFine: 25,
    maxFine: 250,
    baseBail: 150,
    allowNoBail: false,
  },
  shoplifting: {
    statuteName: 'Petty Larceny (Shoplifting)',
    felony: false,
    minJailDays: 0,
    maxJailDays: 60,
    minFine: 30,
    maxFine: 300,
    baseBail: 200,
    allowNoBail: false,
  },
  theft: {
    statuteName: 'Larceny & Grand Theft',
    felony: false,
    minJailDays: 15,
    maxJailDays: 365,
    minFine: 75,
    maxFine: 800,
    baseBail: 750,
    allowNoBail: false,
  },
  perjury: {
    statuteName: 'Perjury Under Oath',
    felony: true,
    minJailDays: 90,
    maxJailDays: 1460,
    minFine: 300,
    maxFine: 3000,
    baseBail: 2500,
    allowNoBail: false,
  },
  bribery: {
    statuteName: 'Bribery & Obstruction of Justice',
    felony: true,
    minJailDays: 180,
    maxJailDays: 1825,
    minFine: 500,
    maxFine: 5000,
    baseBail: 4000,
    allowNoBail: false,
  },
  intimidation: {
    statuteName: 'Witness & Juror Intimidation',
    felony: true,
    minJailDays: 180,
    maxJailDays: 2555,
    minFine: 500,
    maxFine: 5000,
    baseBail: 5000,
    allowNoBail: false,
  },
  car_theft: {
    statuteName: 'Grand Theft Auto',
    felony: true,
    minJailDays: 90,
    maxJailDays: 1095,
    minFine: 250,
    maxFine: 2500,
    baseBail: 2500,
    allowNoBail: false,
  },
  burglary: {
    statuteName: 'Breaking & Entering (Burglary)',
    felony: true,
    minJailDays: 120,
    maxJailDays: 1825,
    minFine: 300,
    maxFine: 3000,
    baseBail: 3500,
    allowNoBail: false,
  },
  assault: {
    statuteName: 'Aggravated Assault & Battery',
    felony: true,
    minJailDays: 90,
    maxJailDays: 1460,
    minFine: 300,
    maxFine: 3500,
    baseBail: 4000,
    allowNoBail: true,
  },
  robbery: {
    statuteName: 'Armed / Strong-Arm Robbery',
    felony: true,
    minJailDays: 365,
    maxJailDays: 3650,
    minFine: 500,
    maxFine: 5000,
    baseBail: 7500,
    allowNoBail: true,
  },
  murder: {
    statuteName: 'Murder in the First Degree',
    felony: true,
    minJailDays: 3650,
    maxJailDays: 14600,
    minFine: 1000,
    maxFine: 10000,
    baseBail: 25000,
    allowNoBail: true,
  },
};

export function courtEraForYear(year: number): CourtEraProfile {
  const base =
    COURT_ERA_PROFILES.find((e) => year >= e.minYear && year <= e.maxYear) ??
    COURT_ERA_PROFILES[COURT_ERA_PROFILES.length - 1]!;
  // Sync exact forensic admissibility gates with G008 FORENSIC_ERA so court & crime never drift
  const admissibility: EraEvidenceAdmissibility = {
    fingerprints: year >= FORENSIC_ERA.fingerprintLocalYear,
    automatedPrints: year >= FORENSIC_ERA.fingerprintAutomatedYear,
    dna: year >= FORENSIC_ERA.dnaAnalysisYear,
    camera: year >= FORENSIC_ERA.cameraBwYear,
    serials: year >= FORENSIC_ERA.serialFirearmWatchYear,
    plates: year >= FORENSIC_ERA.plateRegistrationYear,
  };
  return {
    ...base,
    admissibility,
  };
}

export function statuteForCrime(kind: CrimeKind, year: number): StatuteRule {
  const era = courtEraForYear(year);
  const base = BASE_STATUTES[kind];
  // Frontier & interwar courts imposed slightly stiffer prison minimums for property felonies
  const sentenceMult =
    era.eraId === 'frontier_circuit'
      ? 1.25
      : era.eraId === 'interwar'
        ? 1.10
        : era.eraId === 'modern'
          ? 0.95
          : 1.0;

  return {
    crimeKind: kind,
    statuteName: base.statuteName,
    felony: base.felony,
    minJailDays: Math.round(base.minJailDays * sentenceMult),
    maxJailDays: Math.max(1, Math.round(base.maxJailDays * sentenceMult)),
    minFine: Math.max(5, Math.round(base.minFine * era.moneyScale)),
    maxFine: Math.max(20, Math.round(base.maxFine * era.moneyScale)),
    baseBail: Math.max(25, Math.round(base.baseBail * era.moneyScale)),
    allowNoBail: base.allowNoBail,
    requiresUnanimous: true,
    eraId: era.eraId as CourtEraId,
    admissibility: era.admissibility,
  };
}

/** Builds formal court charges from a G005/G008 `PoliceCase`. */
export function chargesFromCase(
  policeCase: PoliceCase,
  year: number,
  day: number,
): Charge[] {
  const statute = statuteForCrime(policeCase.kind, year);
  const strength = caseStrength(policeCase, day);
  return [
    {
      id: `charge:${policeCase.crimeId}:0`,
      crimeId: policeCase.crimeId,
      kind: policeCase.kind,
      statuteName: statute.statuteName,
      felony: statute.felony || policeCase.severity >= 0.55,
      severity: policeCase.severity,
      placeId: policeCase.placeId,
      crimeDay: policeCase.crimeDay,
      statute,
      strength,
    },
  ];
}

const LAWYER_SURNAMES = [
  'Vance', 'Harlow', 'Keller', 'Bramwell', 'Whitmore', 'Calloway',
  'Delgado', 'Sloane', 'Prescott', 'Maddox', 'Kowalski', 'Mercer',
];
const LAWYER_FIRSTS = [
  'Arthur', 'Evelyn', 'Martin', 'Clara', 'Raymond', 'Nora',
  'Victor', 'Beatrice', 'Samuel', 'Ruth', 'Philip', 'Helen',
];

/** Era-scaled retainers for each defense counsel tier. */
export function lawyerFeeForTier(tier: LawyerTier, year: number): number {
  const scale = courtEraForYear(year).moneyScale;
  switch (tier) {
    case 'public_defender':
      return 0;
    case 'local_attorney':
      return Math.max(40, Math.round(250 * scale));
    case 'retained_counsel':
      return Math.max(150, Math.round(1200 * scale));
    case 'elite_defense':
      return Math.max(500, Math.round(4500 * scale));
  }
}

/**
 * Selects or hires defense counsel by explicit tier or available money budget.
 * An expensive lawyer brings sharply higher preparation, cross-examination skill,
 * persuasion, and peremptory strikes than an overworked public defender.
 */
export function hireDefenseLawyer(params: {
  seed: string;
  caseId: string;
  year: number;
  budget?: number;
  tier?: LawyerTier;
}): DefenseLawyer {
  const { seed, caseId, year } = params;
  const era = courtEraForYear(year);
  let tier: LawyerTier = params.tier ?? 'public_defender';
  if (!params.tier && params.budget !== undefined) {
    if (params.budget >= lawyerFeeForTier('elite_defense', year)) {
      tier = 'elite_defense';
    } else if (params.budget >= lawyerFeeForTier('retained_counsel', year)) {
      tier = 'retained_counsel';
    } else if (params.budget >= lawyerFeeForTier('local_attorney', year)) {
      tier = 'local_attorney';
    } else {
      tier = 'public_defender';
    }
  }

  const firstIdx = Math.floor(rand(seed, 'lawyer_first', caseId, tier) * LAWYER_FIRSTS.length);
  const surIdx = Math.floor(rand(seed, 'lawyer_sur', caseId, tier) * LAWYER_SURNAMES.length);
  const name = `${LAWYER_FIRSTS[firstIdx]!} ${LAWYER_SURNAMES[surIdx]!}`;
  const fee = lawyerFeeForTier(tier, year);

  // Pre-1950 unpaid appointed counsel had thinner preparation than funded public defenders,
  // though funded public defenders are still experienced courtroom litigators with heavy caseloads.
  const pdPrep = era.publicDefenderFunded ? 0.42 : 0.34;
  const pdCross = era.publicDefenderFunded ? 0.46 : 0.38;

  switch (tier) {
    case 'public_defender':
      return {
        id: `counsel:${caseId}:pd`,
        name,
        tier,
        fee,
        preparation: pdPrep,
        crossSkill: pdCross,
        persuasion: 0.42,
        peremptoryStrikes: 3,
      };
    case 'local_attorney':
      return {
        id: `counsel:${caseId}:local`,
        name,
        tier,
        fee,
        preparation: 0.58,
        crossSkill: 0.60,
        persuasion: 0.56,
        peremptoryStrikes: 4,
      };
    case 'retained_counsel':
      return {
        id: `counsel:${caseId}:retained`,
        name,
        tier,
        fee,
        preparation: 0.74,
        crossSkill: 0.76,
        persuasion: 0.72,
        peremptoryStrikes: 5,
      };
    case 'elite_defense':
      return {
        id: `counsel:${caseId}:elite`,
        name,
        tier,
        fee,
        preparation: 0.88,
        crossSkill: 0.90,
        persuasion: 0.86,
        peremptoryStrikes: 6,
      };
  }
}

/**
 * Evaluates pre-trial bail from charge severity, flight risk, defendant wealth,
 * prior record, and era law.
 */
export function evaluateBail(params: {
  charges: readonly Charge[];
  priorConvictions: number;
  defendantWealth: number;
  defendantReputation: number;
  hasLocalTies?: boolean;
  priorEscape?: boolean;
}): BailDecision {
  const { charges, priorConvictions, defendantWealth, defendantReputation } = params;
  const topCharge = charges.slice().sort((a, b) => b.severity - a.severity)[0];
  if (!topCharge) {
    return { granted: true, amount: 0, flightRisk: 0, canAfford: true, reasons: ['No active charges.'] };
  }

  const reasons: string[] = [];
  let flightRisk = clamp01(
    0.18 +
      topCharge.severity * 0.42 +
      Math.min(0.25, priorConvictions * 0.09) +
      (params.priorEscape ? 0.35 : 0) +
      (params.hasLocalTies === false ? 0.14 : -0.06) +
      (defendantReputation < -0.2 ? 0.10 : -0.05),
  );
  flightRisk = Math.round(flightRisk * 1000) / 1000;

  if (topCharge.statute.allowNoBail && (topCharge.kind === 'murder' || flightRisk >= 0.82)) {
    reasons.push(
      topCharge.kind === 'murder'
        ? 'Capital charge: bail denied pending trial.'
        : 'Severe charge and high flight risk: remanded without bail.',
    );
    return {
      granted: false,
      amount: 0,
      flightRisk,
      canAfford: false,
      reasons,
    };
  }

  const recordMult = 1 + priorConvictions * 0.35;
  const wealthFactor = defendantWealth > topCharge.statute.baseBail * 3 ? 1.25 : 1.0;
  const riskFactor = 0.75 + flightRisk * 0.95;
  const amount = Math.max(
    25,
    Math.round(topCharge.statute.baseBail * recordMult * wealthFactor * riskFactor),
  );
  const canAfford = defendantWealth >= amount;

  reasons.push(`Bail set at $${amount} for ${topCharge.statuteName}.`);
  if (priorConvictions > 0) reasons.push(`Increased for ${priorConvictions} prior conviction(s).`);
  if (flightRisk >= 0.55) reasons.push('Elevated flight risk raised bail requirement.');

  return {
    granted: true,
    amount,
    flightRisk,
    canAfford,
    reasons,
  };
}

/**
 * Computes the prosecutor's pre-trial plea offer based on `caseStrength`.
 * A weak case yields a much better deal; a rock-solid case yields only a modest discount.
 */
export function makePleaOffer(params: {
  charges: readonly Charge[];
  priorConvictions: number;
  effectiveCaseStrength?: number;
}): PleaOffer {
  const { charges, priorConvictions } = params;
  const topCharge = charges.slice().sort((a, b) => b.severity - a.severity)[0];
  if (!topCharge) {
    return {
      offered: false,
      chargeIds: [],
      reducedToMisdemeanor: false,
      recommendedJailDays: 0,
      recommendedFine: 0,
      discountRatio: 0,
      caseStrengthAtOffer: 0,
      reasoning: 'No charges to plea.',
    };
  }

  const strength = clamp01(params.effectiveCaseStrength ?? topCharge.strength.combinedScore);
  // Weak case -> huge discount (0.65..0.75); strong case -> modest discount (0.15..0.25)
  const discountRatio = Math.round(clamp01(0.76 - strength * 0.58) * 1000) / 1000;

  const stat = topCharge.statute;
  const midJail =
    stat.minJailDays +
    (stat.maxJailDays - stat.minJailDays) * clamp01(0.28 + topCharge.severity * 0.35 + priorConvictions * 0.12);
  const midFine =
    stat.minFine +
    (stat.maxFine - stat.minFine) * clamp01(0.30 + topCharge.severity * 0.35);

  const recommendedJailDays =
    strength < 0.38 && !topCharge.felony
      ? 0
      : Math.max(0, Math.round(midJail * (1 - discountRatio)));
  const recommendedFine = Math.max(
    Math.round(stat.minFine * 0.5),
    Math.round(midFine * (1 - discountRatio * 0.7)),
  );
  const reducedToMisdemeanor = topCharge.felony && topCharge.kind !== 'murder' && strength < 0.48;

  const reasoning =
    strength < 0.42
      ? `Prosecution case is weak (${Math.round(strength * 100)}%): generous plea discount (${Math.round(discountRatio * 100)}% off).`
      : strength < 0.70
        ? `Moderate prosecution case (${Math.round(strength * 100)}%): standard plea discount (${Math.round(discountRatio * 100)}% off).`
        : `Strong prosecution case (${Math.round(strength * 100)}%): firm offer (${Math.round(discountRatio * 100)}% off).`;

  return {
    offered: true,
    chargeIds: charges.map((c) => c.id),
    reducedToMisdemeanor,
    recommendedJailDays,
    recommendedFine,
    discountRatio,
    caseStrengthAtOffer: Math.round(strength * 1000) / 1000,
    reasoning,
  };
}
