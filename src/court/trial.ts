import {
  FORENSIC_ERA,
  ingestPoliceReport,
  leaveTraces,
  recordCrime,
  recordTraces,
  witnessesOf,
  type Bystander,
  type CrimeEvent,
  type CrimeKind,
  type CulpritAppearance,
  type EvidenceMatch,
  type PoliceReport,
  type TraceKind,
} from '../crime';
import { clamp01, rand } from '../sim/rng';
import { fullName, type Id, type Person } from '../sim/types';
import { selectJury } from './jury';
import {
  chargesFromCase,
  courtEraForYear,
  evaluateBail,
  hireDefenseLawyer,
  makePleaOffer,
} from './law';
import type {
  ActiveTrialTampering,
  AppealInput,
  AppealResult,
  EraEvidenceAdmissibility,
  JurorVote,
  PretrialSummary,
  Sentence,
  TrialEvent,
  TrialInterferenceInput,
  TrialInterferenceResult,
  TrialOptions,
  TrialOutcome,
  VerdictKind,
  WitnessErrorReport,
} from './types';

function isTraceAdmissibleInEra(
  kind: TraceKind,
  admissibility: EraEvidenceAdmissibility,
): boolean {
  switch (kind) {
    case 'fingerprint':
      return admissibility.fingerprints;
    case 'dna':
      return admissibility.dna;
    case 'camera_footage':
      return admissibility.camera;
    case 'stolen_serial':
      return admissibility.serials;
    case 'vehicle_plate':
      return admissibility.plates;
    case 'dropped_item':
    case 'footprint':
      return true;
  }
}

const EVIDENCE_TRIAL_WEIGHT: Readonly<Record<TraceKind, number>> = {
  dna: 0.74,
  fingerprint: 0.50,
  camera_footage: 0.48,
  stolen_serial: 0.34,
  vehicle_plate: 0.30,
  dropped_item: 0.24,
  footprint: 0.18,
};

const EXCULPATORY_EVIDENCE_WEIGHT: Readonly<Record<TraceKind, number>> = {
  dna: 0.74,
  fingerprint: 0.46,
  camera_footage: 0.44,
  stolen_serial: 0.22,
  vehicle_plate: 0.20,
  dropped_item: 0.16,
  footprint: 0.14,
};

/**
 * Compares a witness's G005 `PoliceReport.description` against the ground-truth
 * `actualAppearance` to identify mistaken or omitted details that defense cross-examination
 * can expose in court.
 */
export function inspectWitnessErrors(
  report: PoliceReport,
  actualAppearance: CulpritAppearance | undefined,
): { mistakenFields: string[]; omittedFields: string[] } {
  const mistakenFields: string[] = [];
  const omittedFields: string[] = [];
  if (!actualAppearance) return { mistakenFields, omittedFields };

  const d = report.description;
  if (d.height === undefined) {
    omittedFields.push('height');
  } else if (d.height !== actualAppearance.height) {
    mistakenFields.push(`height: said ${d.height}, actual ${actualAppearance.height}`);
  }
  if (d.build === undefined) {
    omittedFields.push('build');
  } else if (d.build !== actualAppearance.build) {
    mistakenFields.push(`build: said ${d.build}, actual ${actualAppearance.build}`);
  }
  if (d.hair === undefined) {
    omittedFields.push('hair');
  } else if (d.hair !== actualAppearance.hair) {
    mistakenFields.push(`hair: said ${d.hair}, actual ${actualAppearance.hair}`);
  }
  if (d.clothingColor === undefined) {
    omittedFields.push('clothingColor');
  } else if (d.clothingColor !== actualAppearance.clothingColor) {
    mistakenFields.push(
      `clothingColor: said ${d.clothingColor}, actual ${actualAppearance.clothingColor}`,
    );
  }
  if (actualAppearance.vehicle) {
    if (!d.vehicle) {
      omittedFields.push('vehicle');
    } else {
      if (d.vehicle.kind !== actualAppearance.vehicle.kind) {
        mistakenFields.push(
          `vehicle.kind: said ${d.vehicle.kind}, actual ${actualAppearance.vehicle.kind}`,
        );
      }
      if (d.vehicle.color !== actualAppearance.vehicle.color) {
        mistakenFields.push(
          `vehicle.color: said ${d.vehicle.color}, actual ${actualAppearance.vehicle.color}`,
        );
      }
    }
  }

  return { mistakenFields, omittedFields };
}

function resolveJudge(
  options: TrialOptions,
): { id: Id; name: string; traits: { ambition: number; charm: number; diligence: number } } {
  if (options.judgeId && options.world.people[options.judgeId]) {
    const p = options.world.people[options.judgeId]!;
    return { id: p.id, name: `Judge ${fullName(p)}`, traits: p.traits };
  }
  const surnames = ['Aldridge', 'Cromwell', 'Whitaker', 'Benson', 'Holloway', 'Sutherland'];
  const idx = Math.floor(rand(options.seed, 'judge', options.jurisdictionId) * surnames.length);
  return {
    id: `judge:${options.jurisdictionId}`,
    name: `Judge ${surnames[idx]!}`,
    traits: {
      ambition: 0.45 + rand(options.seed, 'judge_amb', options.jurisdictionId) * 0.4,
      charm: 0.35 + rand(options.seed, 'judge_chm', options.jurisdictionId) * 0.4,
      diligence: 0.55 + rand(options.seed, 'judge_dil', options.jurisdictionId) * 0.35,
    },
  };
}

/**
 * Runs a complete, deterministic jury trial for `policeCase` against `defendantId`:
 * charges, pre-trial bail/plea/lawyer, voir-dire jury selection, ordered trial events
 * (prosecution witnesses, cross-examination exposing G005 description mistakes,
 * era-admissible G008 forensics & conflicts, defense case, defendant testimony,
 * closing arguments), 3-round deliberation, verdict (`guilty | not_guilty | hung`),
 * and era-ranged sentencing.
 */
export function runTrial(options: TrialOptions): TrialOutcome {
  const {
    seed,
    world,
    crimeState,
    policeCase,
    defendantId,
    jurisdictionId,
    year,
    day,
  } = options;
  const attempt = options.attempt ?? 1;
  const priorConvictions = options.priorConvictions ?? 0;
  const era = courtEraForYear(year);
  const judge = resolveJudge(options);
  const prosecutorId = options.prosecutorId ?? `prosecutor:${jurisdictionId}`;

  const charges = chargesFromCase(policeCase, year, day);
  const topCharge = charges[0]!;
  const defendant: Person | undefined = world.people[defendantId];
  const defendantWealth = options.defendantWealth ?? defendant?.wealth ?? 50;
  const defendantRep = defendant?.reputation ?? 0;

  const lawyer =
    options.lawyer ??
    hireDefenseLawyer({
      seed,
      caseId: policeCase.crimeId,
      year,
      budget: options.defenseBudget,
      tier: options.lawyerTier,
    });

  const bail = evaluateBail({
    charges,
    priorConvictions,
    defendantWealth,
    defendantReputation: defendantRep,
  });

  // Collect all forensic matches (case file + any defense-submitted matches)
  const allMatches: EvidenceMatch[] = [
    ...(policeCase.evidenceMatches ?? []),
    ...(options.additionalMatches ?? []),
  ];

  // Partition evidence by era admissibility
  const admissibleMatches: EvidenceMatch[] = [];
  const admissibleKindsSet = new Set<TraceKind>();
  const ignoredKindsSet = new Set<TraceKind>();
  for (let i = 0; i < allMatches.length; i++) {
    const m = allMatches[i]!;
    if (isTraceAdmissibleInEra(m.traceKind, era.admissibility)) {
      admissibleMatches.push(m);
      admissibleKindsSet.add(m.traceKind);
    } else {
      ignoredKindsSet.add(m.traceKind);
    }
  }
  const admissibleEvidenceKinds = Array.from(admissibleKindsSet).sort();
  const ignoredEraEvidenceKinds = Array.from(ignoredKindsSet).sort();

  // Compute effective defendant-specific case strength for plea offer
  const hasExculpatoryMatch = admissibleMatches.some((m) => m.suspectId !== defendantId);
  const effectiveOfferStrength = hasExculpatoryMatch
    ? Math.max(0.05, topCharge.strength.combinedScore - 0.55)
    : topCharge.strength.combinedScore;

  const pleaOffer = makePleaOffer({
    charges,
    priorConvictions,
    effectiveCaseStrength: effectiveOfferStrength,
  });

  const pretrial: PretrialSummary = {
    charges,
    era,
    bail,
    pleaOffer,
    lawyer,
  };

  const jurySelection =
    options.jury ??
    selectJury({
      seed,
      world,
      crimeState,
      policeCase,
      defendantId,
      year,
      lawyer,
      attempt,
      judgeId: judge.id,
      prosecutorId,
      eligibleJurorIds: options.eligibleJurorIds,
    });

  // Index active successful tampering effects
  const tamperedByTarget = new Map<Id, ActiveTrialTampering>();
  for (const t of options.tampering ?? []) {
    if (t.succeeded) tamperedByTarget.set(t.targetId, t);
  }

  const events: TrialEvent[] = [];
  let seq = 1;
  const pushEvent = (ev: Omit<TrialEvent, 'seq'>): void => {
    events.push({ seq: seq++, ...ev });
  };

  // Presumption of innocence baseline
  let guiltEvidence = 0.06;

  // 1. Opening statements
  pushEvent({
    phase: 'opening',
    kind: 'opening_prosecution',
    speakerId: prosecutorId,
    summary: `Prosecution opens on ${topCharge.statuteName} (${era.label}).`,
    impactOnGuilt: 0.02,
  });
  guiltEvidence += 0.02;

  pushEvent({
    phase: 'opening',
    kind: 'opening_defense',
    speakerId: lawyer.id,
    summary: `${lawyer.name} (${lawyer.tier.replace(/_/g, ' ')}) opens for the defense, stressing reasonable doubt.`,
    impactOnGuilt: -Math.round(0.02 * lawyer.persuasion * 1000) / 1000,
  });
  guiltEvidence -= 0.02 * lawyer.persuasion;

  // 2. Prosecution witnesses & cross-examination
  const actualAppearance =
    options.actualCulpritAppearance ?? crimeState.crimes[policeCase.crimeId]?.culpritAppearance;
  const witnessErrors: WitnessErrorReport[] = [];
  let witnessAccum = 0;

  for (let i = 0; i < policeCase.reports.length; i++) {
    const rep = policeCase.reports[i]!;
    const witnessPerson = world.people[rep.reporterId];
    const witnessName = witnessPerson ? fullName(witnessPerson) : rep.reporterId;

    const witnessTamper = tamperedByTarget.get(rep.reporterId);
    if (
      witnessTamper &&
      (witnessTamper.kind === 'bribe_witness' || witnessTamper.kind === 'threaten_witness')
    ) {
      pushEvent({
        phase: 'prosecution',
        kind: 'tampering_effect',
        speakerId: rep.reporterId,
        summary: `${witnessName} suddenly softens testimony and claims uncertainty on the stand (${witnessTamper.kind.replace(/_/g, ' ')}).`,
        impactOnGuilt: -0.06,
        details: { tamperingKind: witnessTamper.kind },
      });
      guiltEvidence -= 0.06;
      continue;
    }

    const baseWeight =
      rep.confidence *
      (rep.source === 'first_hand' ? 0.44 : 0.16) *
      (rep.identifiedCulpritId === defendantId ? 1.0 : 0.72);
    // Diminishing returns across stacked witnesses
    const marginalWitness = baseWeight * Math.pow(0.68, witnessAccum);
    witnessAccum++;
    guiltEvidence += marginalWitness;

    pushEvent({
      phase: 'prosecution',
      kind: 'prosecution_witness',
      speakerId: rep.reporterId,
      summary: `${witnessName} testifies (${rep.source.replace(/_/g, ' ')}, confidence ${Math.round(rep.confidence * 100)}%)${rep.identifiedCulpritId === defendantId ? ' identifying the defendant.' : ' describing the culprit.'}`,
      impactOnGuilt: Math.round(marginalWitness * 1000) / 1000,
      details: {
        source: rep.source,
        confidence: Math.round(rep.confidence * 1000) / 1000,
        namedDefendant: rep.identifiedCulpritId === defendantId,
      },
    });

    // Cross-examination inspects G005 description mistakes
    const { mistakenFields, omittedFields } = inspectWitnessErrors(rep, actualAppearance);
    let exposedCount = 0;
    for (let e = 0; e < mistakenFields.length; e++) {
      const catchChance = clamp01(0.42 + lawyer.crossSkill * 0.56);
      if (
        rand(seed, 'cross_expose', policeCase.crimeId, attempt, rep.reporterId, e) < catchChance ||
        lawyer.crossSkill >= 0.70
      ) {
        exposedCount++;
      }
    }
    if (mistakenFields.length > 0 && exposedCount === 0 && lawyer.crossSkill >= 0.25) {
      exposedCount = 1;
    }

    const errorDrop = exposedCount * (0.025 + 0.085 * lawyer.crossSkill);
    const generalCrossDrop =
      (0.010 + 0.055 * lawyer.crossSkill) * (rep.source === 'second_hand' ? 1.35 : 1.0);
    const credibilityDrop = Math.min(marginalWitness + 0.08, errorDrop + generalCrossDrop);
    guiltEvidence -= credibilityDrop;

    witnessErrors.push({
      witnessId: rep.reporterId,
      mistakenFields,
      omittedFields,
      errorCount: mistakenFields.length,
      exposedCount,
      credibilityDrop: Math.round(credibilityDrop * 1000) / 1000,
    });

    pushEvent({
      phase: 'cross_examination',
      kind: 'cross_examination',
      speakerId: lawyer.id,
      targetId: rep.reporterId,
      summary:
        exposedCount > 0
          ? `${lawyer.name} exposes ${exposedCount} mistaken detail(s) in ${witnessName}'s memory (${mistakenFields.slice(0, exposedCount).join('; ')}).`
          : `${lawyer.name} probes lighting, distance, and memory fade in ${witnessName}'s account.`,
      impactOnGuilt: -Math.round(credibilityDrop * 1000) / 1000,
      details: {
        mistakenFields,
        exposedCount,
      },
    });
  }

  // 3. Prosecution & defense physical/forensic evidence (era-gated)
  let incriminatingPhysicalCount = 0;
  let exculpatoryPhysicalCount = 0;

  for (let i = 0; i < admissibleMatches.length; i++) {
    const m = admissibleMatches[i]!;
    if (m.suspectId === defendantId) {
      incriminatingPhysicalCount++;
      const w = (EVIDENCE_TRIAL_WEIGHT[m.traceKind] ?? 0.25) * m.confidence;
      const marginal = w * Math.pow(0.72, incriminatingPhysicalCount - 1);
      guiltEvidence += marginal;
      pushEvent({
        phase: 'prosecution',
        kind: 'prosecution_evidence',
        speakerId: prosecutorId,
        summary: `Admissible ${m.traceKind} evidence (${m.method}) links defendant (${Math.round(m.confidence * 100)}% confidence).`,
        impactOnGuilt: Math.round(marginal * 1000) / 1000,
        details: {
          traceKind: m.traceKind,
          method: m.method,
          confidence: Math.round(m.confidence * 1000) / 1000,
          impossibleTimeline: m.impossibleTimeline,
        },
      });
    } else {
      exculpatoryPhysicalCount++;
      const drop = (EXCULPATORY_EVIDENCE_WEIGHT[m.traceKind] ?? 0.25) * m.confidence;
      guiltEvidence -= drop;
      pushEvent({
        phase: 'cross_examination',
        kind: 'evidence_challenge',
        speakerId: lawyer.id,
        summary: `Exculpatory ${m.traceKind} match (${m.method}) points to ${m.suspectId}, NOT the defendant!`,
        impactOnGuilt: -Math.round(drop * 1000) / 1000,
        details: {
          traceKind: m.traceKind,
          matchedOtherSuspect: m.suspectId,
          confidence: Math.round(m.confidence * 1000) / 1000,
        },
      });
    }
  }

  // Explicitly surface G008 witness_physical_conflict in court
  const hasConflictPart = topCharge.strength.parts.some(
    (p) => p.kind === 'witness_physical_conflict',
  );
  if (hasConflictPart || (exculpatoryPhysicalCount > 0 && policeCase.reports.length > 0)) {
    const conflictDrop = 0.16 + 0.08 * lawyer.crossSkill;
    guiltEvidence -= conflictDrop;
    pushEvent({
      phase: 'cross_examination',
      kind: 'evidence_challenge',
      speakerId: lawyer.id,
      summary: `${lawyer.name} highlights direct conflict between eyewitness testimony and physical forensics.`,
      impactOnGuilt: -Math.round(conflictDrop * 1000) / 1000,
      details: { conflict: true },
    });
  }

  // Note any evidence that existed on traces but is inadmissible / unavailable in this era
  if (ignoredEraEvidenceKinds.length > 0) {
    pushEvent({
      phase: 'prosecution',
      kind: 'evidence_challenge',
      speakerId: judge.id,
      summary: `${era.label}: ${ignoredEraEvidenceKinds.join(', ')} testing is not available or admissible in ${year}.`,
      impactOnGuilt: 0,
      details: { ignoredEraEvidenceKinds },
    });
  }

  // Multi-modal forensic corroboration bonus when multiple independent physical matches incriminate defendant
  if (incriminatingPhysicalCount >= 2) {
    guiltEvidence += Math.min(0.12, (incriminatingPhysicalCount - 1) * 0.07);
  }

  // 4. Defense case (lawyer preparation & character)
  const defenseImpact =
    0.02 +
    0.17 * (0.55 * lawyer.preparation + 0.45 * lawyer.persuasion) +
    (defendantRep > 0.2 ? 0.04 : 0);
  guiltEvidence -= defenseImpact;
  pushEvent({
    phase: 'defense',
    kind: 'defense_case',
    speakerId: lawyer.id,
    summary: `${lawyer.name} presents the defense case (preparation ${Math.round(lawyer.preparation * 100)}%, persuasion ${Math.round(lawyer.persuasion * 100)}%).`,
    impactOnGuilt: -Math.round(defenseImpact * 1000) / 1000,
    details: {
      lawyerTier: lawyer.tier,
      preparation: lawyer.preparation,
    },
  });

  // 5. Defendant's own spoken testimony (input score from voice/dialogue system)
  if (options.defendantTestifies || options.defendantTestimonyScore !== undefined) {
    const score = clamp01(options.defendantTestimonyScore ?? 0.5);
    const testimonyImpact = (0.5 - score) * 0.24;
    guiltEvidence += testimonyImpact;
    pushEvent({
      phase: 'defense',
      kind: 'defendant_testimony',
      speakerId: defendantId,
      summary:
        score >= 0.65
          ? 'Defendant gives composed, convincing testimony in their own defense.'
          : score <= 0.35
            ? 'Defendant stumbles under prosecution questioning.'
            : 'Defendant testifies steadily in their own defense.',
      impactOnGuilt: Math.round(testimonyImpact * 1000) / 1000,
      details: { testimonyScore: score },
    });
  }

  // 6. Closing arguments
  const closingDelta = (0.5 - lawyer.persuasion) * 0.08;
  guiltEvidence += closingDelta;
  pushEvent({
    phase: 'closing',
    kind: 'closing_arguments',
    speakerId: lawyer.id,
    summary: 'Both sides deliver closing arguments before the jury retires to deliberate.',
    impactOnGuilt: Math.round(closingDelta * 1000) / 1000,
  });

  const perceivedGuiltScore = Math.round(clamp01(guiltEvidence) * 1000) / 1000;

  // 7. Deliberation & Verdict (12 jurors, up to 3 rounds)
  const CONVICTION_THRESHOLD = 0.58;
  interface JurorSim {
    seat: number;
    personId: Id;
    name: string;
    belief: number;
    tampered?: 'bribed' | 'threatened';
    stubbornness: number;
  }

  const jurorSims: JurorSim[] = jurySelection.seated.map((j) => {
    const tamper = tamperedByTarget.get(j.personId);
    if (tamper && (tamper.kind === 'bribe_juror' || tamper.kind === 'threaten_juror')) {
      const mode = tamper.kind === 'bribe_juror' ? 'bribed' : 'threatened';
      pushEvent({
        phase: 'deliberation',
        kind: 'tampering_effect',
        speakerId: j.personId,
        summary: `Juror #${j.seat} (${j.name}) quietly resists conviction (${mode}).`,
        impactOnGuilt: 0,
        details: { seat: j.seat, mode },
      });
      return {
        seat: j.seat,
        personId: j.personId,
        name: j.name,
        belief: 0.06,
        tampered: mode,
        stubbornness: 1.0,
      };
    }

    // Individual juror perception: traits + bias + deterministic personal variation
    const noPhysSkepticism =
      incriminatingPhysicalCount === 0 ? -0.07 * (j.traits.diligence - 0.45) : 0.04 * (j.traits.diligence - 0.5);
    const ambitionTilt = 0.05 * (j.traits.ambition - 0.5);
    const rhetoricTilt = -0.05 * (j.traits.charm - 0.5) * lawyer.persuasion;
    const personalNoise =
      (rand(seed, 'juror_belief', policeCase.crimeId, attempt, j.personId) - 0.5) * 0.18;

    const belief = clamp01(
      perceivedGuiltScore +
        j.initialBias * 0.75 +
        noPhysSkepticism +
        ambitionTilt +
        rhetoricTilt +
        personalNoise,
    );

    return {
      seat: j.seat,
      personId: j.personId,
      name: j.name,
      belief,
      stubbornness: clamp01(0.35 + j.traits.diligence * 0.45),
    };
  });

  let deliberationRounds = 0;
  for (let round = 1; round <= 3; round++) {
    deliberationRounds = round;
    const guiltyCount = jurorSims.filter((j) => j.belief >= CONVICTION_THRESHOLD).length;
    const total = jurorSims.length;
    pushEvent({
      phase: 'deliberation',
      kind: 'deliberation_round',
      summary: `Deliberation round ${round}: ${guiltyCount} guilty, ${total - guiltyCount} not guilty.`,
      impactOnGuilt: 0,
      details: { round, guiltyCount, notGuiltyCount: total - guiltyCount },
    });

    if (guiltyCount === total || guiltyCount === 0) break;

    if (round < 3) {
      const meanBelief =
        jurorSims.reduce((s, j) => s + j.belief, 0) / Math.max(1, jurorSims.length);
      const majorityStrength = Math.abs(guiltyCount / Math.max(1, total) - 0.5) * 2;
      for (let k = 0; k < jurorSims.length; k++) {
        const js = jurorSims[k]!;
        if (js.tampered) continue; // tampered holdout never flips
        const distFromThreshold = Math.abs(js.belief - CONVICTION_THRESHOLD);
        // Strongly convinced holdouts resist peer pressure, producing real hung juries
        if (distFromThreshold <= 0.14) {
          const pull = (0.28 + 0.30 * majorityStrength) * (1 - js.stubbornness * 0.45);
          js.belief = clamp01(js.belief + (meanBelief - js.belief) * pull);
        }
      }
    }
  }

  const votes: JurorVote[] = jurorSims.map((js) => ({
    seat: js.seat,
    personId: js.personId,
    name: js.name,
    beliefInGuilt: Math.round(js.belief * 1000) / 1000,
    vote: js.belief >= CONVICTION_THRESHOLD ? 'guilty' : 'not_guilty',
    ...(js.tampered ? { tampered: js.tampered } : {}),
  }));

  const guiltyVotes = votes.filter((v) => v.vote === 'guilty').length;
  const notGuiltyVotes = votes.length - guiltyVotes;

  let verdict: VerdictKind;
  if (votes.length > 0 && guiltyVotes === votes.length) {
    verdict = 'guilty';
  } else if (votes.length > 0 && notGuiltyVotes === votes.length) {
    verdict = 'not_guilty';
  } else {
    verdict = 'hung';
  }

  const retrialRecommended = verdict === 'hung';

  pushEvent({
    phase: 'verdict',
    kind: 'verdict',
    speakerId: judge.id,
    summary:
      verdict === 'guilty'
        ? `Jury returns a unanimous GUILTY verdict (${guiltyVotes}-${notGuiltyVotes}).`
        : verdict === 'not_guilty'
          ? `Jury returns a unanimous NOT GUILTY verdict (${guiltyVotes}-${notGuiltyVotes}).`
          : `Jury deadlocked (${guiltyVotes}-${notGuiltyVotes}): mistrial declared, retrial recommended.`,
    impactOnGuilt: 0,
    details: { verdict, guiltyVotes, notGuiltyVotes },
  });

  // 8. Sentencing (within era statute range by severity, record, and judge traits)
  let sentence: Sentence | undefined;
  if (verdict === 'guilty') {
    const stat = topCharge.statute;
    const judgeHarshness = clamp01(
      0.5 +
        (judge.traits.diligence - 0.5) * 0.35 +
        (judge.traits.ambition - 0.5) * 0.30 -
        (judge.traits.charm - 0.5) * 0.25,
    );
    const factor = clamp01(
      0.18 +
        topCharge.severity * 0.42 +
        Math.min(0.28, priorConvictions * 0.12) +
        (judgeHarshness - 0.5) * 0.25,
    );
    const jailDays = Math.round(
      stat.minJailDays + (stat.maxJailDays - stat.minJailDays) * factor,
    );
    const fineAmount = Math.round(
      stat.minFine + (stat.maxFine - stat.minFine) * clamp01(factor * 0.9),
    );
    const probationDays = jailDays < 60 ? Math.round(90 + topCharge.severity * 180) : 0;
    const reputationDelta = -Math.round(clamp01(0.20 + topCharge.severity * 0.45) * 1000) / 1000;

    sentence = {
      jailDays,
      fineAmount,
      probationDays,
      reputationDelta,
      judgeId: judge.id,
      judgeName: judge.name,
      reasoning: `${judge.name} sentences defendant within ${era.label} range (${stat.minJailDays}–${stat.maxJailDays} days) for ${stat.statuteName}.`,
    };

    pushEvent({
      phase: 'sentencing',
      kind: 'sentence',
      speakerId: judge.id,
      summary: `Sentenced to ${jailDays} day(s) in custody and a $${fineAmount} fine.`,
      impactOnGuilt: 0,
      details: { jailDays, fineAmount, probationDays },
    });
  }

  return {
    caseId: `trial:${policeCase.crimeId}:a${attempt}`,
    crimeId: policeCase.crimeId,
    defendantId,
    jurisdictionId,
    year,
    day,
    attempt,
    era,
    pretrial,
    jurySelection,
    events,
    witnessErrors,
    perceivedGuiltScore,
    admissibleEvidenceKinds,
    ignoredEraEvidenceKinds,
    deliberationRounds,
    votes,
    guiltyVotes,
    notGuiltyVotes,
    verdict,
    retrialRecommended,
    ...(sentence ? { sentence } : {}),
  };
}

/**
 * Triggers a retrial after a hung jury by incrementing `attempt` and selecting a fresh
 * 12-person jury panel from the jurisdiction.
 */
export function runRetrial(
  previousOutcome: TrialOutcome,
  options: Omit<TrialOptions, 'attempt' | 'jury'>,
): TrialOutcome {
  return runTrial({
    ...options,
    attempt: previousOutcome.attempt + 1,
    jury: undefined,
  });
}

/**
 * Resolves a crime around the trial (`bribe_juror`, `threaten_juror`, `bribe_witness`,
 * `threaten_witness`, or `perjury`). Always routes through G005/G008 (`witnessesOf`,
 * `recordCrime`, `leaveTraces`, `recordTraces`), creating a real new crime record (and
 * police case when reported/discovered) while optionally producing an `ActiveTrialTampering`
 * effect for `runTrial`.
 */
export function commitTrialInterference(
  input: TrialInterferenceInput,
): TrialInterferenceResult {
  const {
    seed,
    world,
    crimeState,
    jurisdictionId,
    placeId,
    position,
    year,
    day,
    culpritId,
    culpritAppearance,
    kind,
    targetId,
  } = input;
  const hour = input.hour ?? 14;
  const target = world.people[targetId];
  const bribeAmount = input.bribeAmount ?? 0;
  const era = courtEraForYear(year);

  let succeeded = false;
  let targetReported = false;
  let crimeKind: CrimeKind = 'theft';
  let severity = 0.62;
  let itemLabel = kind.replace(/_/g, ' ');

  if (kind === 'bribe_juror' || kind === 'bribe_witness') {
    crimeKind = 'theft';
    severity = 0.64;
    itemLabel = kind === 'bribe_juror' ? 'juror bribery' : 'witness bribery';
    const scaledThreshold = Math.max(80, Math.round(400 * era.moneyScale));
    const moneyRatio = clamp01(bribeAmount / (scaledThreshold * (0.7 + (target?.traits.diligence ?? 0.5))));
    const corruptibility = clamp01(
      0.22 +
        (1 - (target?.traits.diligence ?? 0.5)) * 0.45 +
        (target?.traits.ambition ?? 0.5) * 0.25 +
        moneyRatio * 0.55 -
        Math.max(0, (target?.reputation ?? 0) * 0.25),
    );
    const roll = rand(seed, 'trial_bribe', day, culpritId, targetId, bribeAmount);
    succeeded = bribeAmount > 0 && roll < corruptibility;
    targetReported = !succeeded;
  } else if (kind === 'threaten_juror' || kind === 'threaten_witness') {
    crimeKind = 'assault';
    severity = 0.70;
    itemLabel = kind === 'threaten_juror' ? 'juror intimidation' : 'witness intimidation';
    const compliance = clamp01(0.68 - (target?.traits.diligence ?? 0.5) * 0.35);
    const roll = rand(seed, 'trial_threat', day, culpritId, targetId);
    succeeded = roll < compliance;
    targetReported = !succeeded || rand(seed, 'threat_report', day, targetId) < 0.35;
  } else {
    // Perjury under oath
    crimeKind = 'theft';
    severity = 0.56;
    itemLabel = 'perjury under oath';
    succeeded = !input.caughtInCourt;
    targetReported = Boolean(input.caughtInCourt);
  }

  const crimeId = `crime:trial:${kind}:${day}:${targetId}`;
  const event: CrimeEvent = {
    id: crimeId,
    kind: crimeKind,
    severity,
    placeId,
    position: { x: position.x, y: position.y },
    when: { year, day, hour },
    culpritId,
    culpritAppearance,
    victimId: targetId,
    victimConscious: true,
    stolenOrDamaged: { item: itemLabel, value: Math.max(1, bribeAmount) },
  };

  const bystanders: Bystander[] = [];
  if (target) {
    bystanders.push({
      person: target,
      position: { x: position.x, y: position.y },
      attention: 0.95,
      conscious: true,
      knowsCulprit: true,
    });
  }
  for (const b of input.bystanders ?? []) {
    const p = world.people[b.personId];
    if (!p || p.id === culpritId || p.id === targetId) continue;
    bystanders.push({
      person: p,
      position: { x: b.position.x, y: b.position.y },
      attention: b.attention ?? 0.75,
      conscious: true,
    });
  }

  const sightings = witnessesOf(event, bystanders, { seed, world });
  let nextState = recordCrime(crimeState, event, sightings);

  const traces = leaveTraces(event, { seed, exposure: 'indoor' });
  nextState = recordTraces(nextState, crimeId, traces);

  // If the target refused, perjury was caught in court, or a third-party bystander witnessed it,
  // open a police case right away so the court interference creates a live new crime case.
  const thirdPartyWitness = sightings.find((s) => s.witnessId !== targetId);
  if (targetReported || thirdPartyWitness) {
    const reporter = targetReported
      ? sightings.find((s) => s.witnessId === targetId) ?? thirdPartyWitness
      : thirdPartyWitness;
    if (reporter) {
      const report: PoliceReport = {
        id: `rep:trial:${crimeId}:${reporter.witnessId}`,
        crimeId,
        reporterId: reporter.witnessId,
        jurisdictionId,
        filedDay: day,
        receivedDay: day,
        delayHours: 0.5,
        source: 'first_hand',
        isVictim: reporter.witnessId === targetId,
        confidence: Math.max(0.75, reporter.confidence),
        ...(reporter.identifiedCulpritId ? { identifiedCulpritId: reporter.identifiedCulpritId } : {}),
        description: reporter.description,
      };
      nextState = {
        ...nextState,
        jurisdictions: {
          ...nextState.jurisdictions,
          [jurisdictionId]: ingestPoliceReport(
            nextState.jurisdictions[jurisdictionId],
            event,
            report,
          ),
        },
      };
    }
  }

  const tampering: ActiveTrialTampering | undefined = succeeded
    ? {
        kind,
        targetId,
        crimeId,
        succeeded: true,
        strength: 1.0,
      }
    : undefined;

  return {
    crimeState: nextState,
    crimeId,
    kind,
    targetId,
    succeeded,
    targetReported,
    witnessIds: sightings.map((s) => s.witnessId),
    ...(tampering ? { tampering } : {}),
    summary: succeeded
      ? `${itemLabel} succeeded on ${target ? fullName(target) : targetId}, leaving crime record ${crimeId}.`
      : `${itemLabel} failed and was reported (${crimeId}).`,
  };
}

/**
 * Evaluates a post-conviction appeal or exoneration years later (e.g., new DNA evidence
 * matching the true culprit once DNA testing comes online, a new exculpatory witness,
 * witness recantation, or procedural error).
 */
export function fileAppeal(input: AppealInput): AppealResult {
  const { conviction, year, day, grounds } = input;
  const era = courtEraForYear(year);
  const origJail = conviction.sentence?.jailDays ?? 0;
  const daysServed = Math.max(0, Math.min(origJail, input.daysServed ?? 0));
  const remainingJail = Math.max(0, origJail - daysServed);
  const origRepLoss = Math.abs(conviction.sentence?.reputationDelta ?? 0.3);

  if (conviction.verdict !== 'guilty') {
    return {
      convictionCrimeId: conviction.crimeId,
      defendantId: conviction.defendantId,
      appealYear: year,
      appealDay: day,
      grounds,
      outcome: 'denied',
      vacatedJailDays: 0,
      reputationRestored: 0,
      compensationAmount: 0,
      updatedConviction: conviction,
      reasoning: 'Appeal denied: defendant was not convicted in this trial.',
    };
  }

  let outcome: AppealResult['outcome'] = 'denied';
  let trueSuspectId: Id | undefined;
  let reasoning = 'Insufficient new grounds to disturb the verdict.';

  if (grounds === 'new_dna_match' || grounds === 'forensic_retest') {
    if (grounds === 'new_dna_match' && year < FORENSIC_ERA.dnaAnalysisYear) {
      reasoning = `DNA testing is not yet available in ${year} (available from ${FORENSIC_ERA.dnaAnalysisYear}).`;
    } else if (
      input.newEvidenceMatch &&
      input.newEvidenceMatch.suspectId !== conviction.defendantId &&
      input.newEvidenceMatch.confidence >= 0.70
    ) {
      outcome = 'exonerated';
      trueSuspectId = input.newEvidenceMatch.suspectId;
      reasoning = `Post-conviction ${input.newEvidenceMatch.traceKind} match (${year}) identifies ${trueSuspectId} and exonerates ${conviction.defendantId}.`;
    } else if (input.excludesDefendant) {
      outcome = 'exonerated';
      reasoning = `Post-conviction forensic retest (${year}) excludes ${conviction.defendantId} from the crime scene evidence.`;
    }
  } else if (grounds === 'witness_recantation') {
    const hadStrongPhysical = conviction.admissibleEvidenceKinds.some(
      (k) => k === 'dna' || k === 'fingerprint' || k === 'camera_footage',
    );
    if (!hadStrongPhysical && input.recantingWitnessId) {
      outcome = 'exonerated';
      reasoning = `Key witness ${input.recantingWitnessId} recanted with no corroborating physical forensics; conviction vacated.`;
    } else if (input.recantingWitnessId) {
      outcome = 'new_trial_ordered';
      reasoning = `Witness ${input.recantingWitnessId} recanted; appellate court orders a new trial.`;
    }
  } else if (grounds === 'new_witness') {
    const conf = clamp01(input.newWitnessConfidence ?? 0);
    if (conf >= 0.78) {
      outcome = 'exonerated';
      reasoning = `Credible new witness (confidence ${Math.round(conf * 100)}%) clears the defendant.`;
    } else if (conf >= 0.50) {
      outcome = 'new_trial_ordered';
      reasoning = `New witness testimony (confidence ${Math.round(conf * 100)}%) warrants a retrial.`;
    }
  } else if (grounds === 'procedural_error') {
    outcome = 'new_trial_ordered';
    reasoning = 'Material procedural or jury-selection error found; conviction remanded for retrial.';
  }

  const vacatedJailDays = outcome === 'exonerated' ? remainingJail : 0;
  const reputationRestored =
    outcome === 'exonerated' ? Math.round(origRepLoss * 1000) / 1000 : 0;
  const compensationAmount =
    outcome === 'exonerated'
      ? Math.round(daysServed * 120 * era.moneyScale)
      : 0;

  const updatedConviction: TrialOutcome =
    outcome === 'exonerated'
      ? {
          ...conviction,
          exonerated: true,
          exonerationYear: year,
        }
      : conviction;

  return {
    convictionCrimeId: conviction.crimeId,
    defendantId: conviction.defendantId,
    appealYear: year,
    appealDay: day,
    grounds,
    outcome,
    vacatedJailDays,
    reputationRestored,
    compensationAmount,
    ...(trueSuspectId ? { trueSuspectId } : {}),
    updatedConviction,
    reasoning,
  };
}
