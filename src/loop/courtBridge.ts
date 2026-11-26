import {
  chargesFromCase,
  commitTrialInterference,
  courtEraForYear,
  evaluateBail,
  hireDefenseLawyer,
  lawyerFeeForTier,
  makePleaOffer,
  runTrial,
  selectJury,
  statuteForCrime,
  type Charge,
  type LawyerTier,
  type TrialOutcome,
} from '../court';
import {
  ingestPoliceReport,
  type CrimeKind,
  type CrimeState,
  type PoliceCase,
  type PoliceReport,
} from '../crime';
import { FIRST_YEAR, LAST_YEAR, formatMoney } from '../economy/eras';
import { clamp01 } from '../sim/rng';
import type {
  BailDecisionInput,
  BailDecisionResult,
  ChooseCounselInput,
  ChooseCounselResult,
  CourtInterferenceInput,
  CourtInterferenceResult,
  CourtViewResult,
  CrimeMetaRecord,
  LoopCourtCase,
  LoopCourtSentence,
  LoopLawyerOption,
  LoopSim,
  LoopWarrantRecord,
} from './types';

const LAWYER_TIERS: readonly LawyerTier[] = [
  'public_defender',
  'local_attorney',
  'retained_counsel',
  'elite_defense',
];

function courtMoney(cents: number, year: number): string {
  const clamped = Math.max(FIRST_YEAR, Math.min(LAST_YEAR, Math.round(year)));
  return formatMoney(Math.max(0, Math.round(cents)), clamped) ?? `$${(Math.max(0, cents) / 100).toFixed(2)}`;
}

function dayNameFor(startDay: number, startDayOfWeek: number, day: number): string {
  const names = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const idx = (((startDayOfWeek + (day - startDay)) % 7) + 7) % 7;
  return names[idx] ?? 'Monday';
}

export function findCourthousePlaceId(sim: LoopSim, jurId: string): string {
  const places = Object.values(sim.places).sort((a, b) => (a.id < b.id ? -1 : 1));
  const inJur = places.filter((p) => p.jurisdictionId === jurId);
  const pool = inJur.length > 0 ? inJur : places;
  const courthouse =
    pool.find((p) => p.kind === 'courthouse') ??
    pool.find((p) => /court|hall/i.test(p.name)) ??
    pool.find((p) => p.kind === 'police_station') ??
    pool[0];
  return courthouse?.id ?? 'courthouse';
}

export function buildLawyerOptions(
  sim: LoopSim,
  caseId: string,
  playerCashCents?: number,
): LoopLawyerOption[] {
  return LAWYER_TIERS.map((tier) => {
    const lawyer = hireDefenseLawyer({
      seed: sim.seed,
      caseId,
      year: sim.year,
      tier,
    });
    const feeCents = Math.round(lawyerFeeForTier(tier, sim.year) * 100);
    const canAfford =
      tier === 'public_defender' ||
      playerCashCents === undefined ||
      playerCashCents >= feeCents;
    return {
      tier,
      name: lawyer.name,
      feeCents,
      canAfford,
      preparation: lawyer.preparation,
      crossSkill: lawyer.crossSkill,
      persuasion: lawyer.persuasion,
      peremptoryStrikes: lawyer.peremptoryStrikes,
    };
  });
}

export function collectProsecutionWitnessIds(
  sim: LoopSim,
  targetCases: readonly PoliceCase[],
): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (let i = 0; i < targetCases.length; i++) {
    const pc = targetCases[i]!;
    for (let r = 0; r < pc.reports.length; r++) {
      const repId = pc.reports[r]!.reporterId;
      if (repId !== sim.playerId && sim.world.people[repId] && !seen.has(repId)) {
        seen.add(repId);
        out.push(repId);
      }
    }
    const ledger = sim.crimeState.ledger[pc.crimeId] ?? {};
    for (const [pid, acc] of Object.entries(ledger)) {
      if (
        pid !== sim.playerId &&
        acc.source === 'first_hand' &&
        sim.world.people[pid] &&
        !seen.has(pid)
      ) {
        seen.add(pid);
        out.push(pid);
      }
    }
  }
  out.sort();
  return out;
}

export function reconstructPoliceCase(sim: LoopSim, cc: LoopCourtCase): PoliceCase {
  const existing = sim.crimeState.jurisdictions[cc.jurisdictionId]?.cases[cc.primaryCrimeId];
  if (existing) return existing;
  const top = cc.charges[0]!;
  return {
    crimeId: cc.primaryCrimeId,
    kind: top?.kind ?? 'theft',
    severity: top?.severity ?? 0.5,
    placeId: top?.placeId ?? cc.courtPlaceId,
    crimeDay: top?.crimeDay ?? cc.filedDay,
    firstReportedDay: cc.filedDay,
    lastReportedDay: cc.filedDay,
    reports: [],
    merged: {
      description: {},
      fieldConfidence: {},
      namedSuspectId: cc.defendantId,
      namedSuspectConfidence: 0.65,
      overallConfidence: 0.65,
    },
    evidenceMatches: [],
    reportCount: 1,
    firstHandCount: 1,
    actualCulpritId: cc.defendantId,
  };
}

export function findTargetCourtCase(
  sim: LoopSim,
  courtCaseId?: string,
  allowedStatuses: readonly LoopCourtCase['status'][] = ['pending_trial'],
): LoopCourtCase | undefined {
  const cases = sim.courtCases;
  if (!cases) return undefined;
  if (courtCaseId) {
    const exact = cases[courtCaseId];
    return exact && allowedStatuses.includes(exact.status) ? exact : undefined;
  }
  const list = Object.values(cases)
    .filter((c) => allowedStatuses.includes(c.status))
    .sort((a, b) => a.courtDateDay - b.courtDateDay || (a.id < b.id ? -1 : 1));
  return list[0];
}

export function shouldRouteArrestToCourt(params: {
  sim: LoopSim;
  targetCases: readonly PoliceCase[];
  priorConvictions: number;
  demandTrial?: boolean;
  priorEscape: boolean;
}): boolean {
  const { sim, targetCases, priorConvictions, demandTrial, priorEscape } = params;
  if (targetCases.length === 0) return false;
  if (demandTrial || priorEscape || priorConvictions >= 1) return true;
  for (let i = 0; i < targetCases.length; i++) {
    const pc = targetCases[i]!;
    const stat = statuteForCrime(pc.kind, sim.year);
    if (stat.felony || pc.severity >= 0.35) return true;
  }
  return false;
}

export function createCourtCaseFromArrest(params: {
  sim: LoopSim;
  crimeState: CrimeState;
  jurId: string;
  targetCases: readonly PoliceCase[];
  playerCashCents?: number;
  priorEscape: boolean;
  seq: number;
}): LoopCourtCase {
  const { sim, crimeState, jurId, targetCases, playerCashCents, priorEscape, seq } = params;
  const sorted = targetCases
    .slice()
    .sort((a, b) => b.severity - a.severity || (a.crimeId < b.crimeId ? -1 : 1));
  const primary = sorted[0]!;
  const caseId = `court:${jurId}:${sim.currentDay}:${seq}`;

  const charges: Charge[] = [];
  for (let i = 0; i < sorted.length; i++) {
    const pc = sorted[i]!;
    const built = chargesFromCase(pc, sim.year, sim.currentDay);
    for (let j = 0; j < built.length; j++) {
      const ch = built[j]!;
      charges.push({
        ...ch,
        id: `charge:${caseId}:${charges.length}`,
      });
    }
  }
  charges.sort((a, b) => b.severity - a.severity || (a.crimeId < b.crimeId ? -1 : 1));

  const era = courtEraForYear(sim.year);
  const priorConvictions = sim.priorOffenses.length;
  const defendantDollars =
    playerCashCents !== undefined
      ? Math.round(playerCashCents / 100)
      : sim.world.people[sim.playerId]?.wealth ?? 20;
  const hasLocalTies = Object.values(sim.familiarity).some(
    (f) => f.positiveFacts.length > 0 || f.trustDelta > 0.05,
  );
  const bailRaw = evaluateBail({
    charges,
    priorConvictions,
    defendantWealth: defendantDollars,
    defendantReputation: sim.world.people[sim.playerId]?.reputation ?? 0,
    hasLocalTies,
    priorEscape,
  });
  const bailAmountCents = bailRaw.granted ? Math.round(bailRaw.amount * 100) : 0;
  const bailCanAfford =
    bailRaw.granted &&
    (playerCashCents !== undefined ? playerCashCents >= bailAmountCents : bailRaw.canAfford);
  const bailReasons = bailRaw.granted
    ? [
        `Bail set at ${courtMoney(bailAmountCents, sim.year)} for ${charges[0]!.statuteName}.`,
        ...bailRaw.reasons.slice(1),
      ]
    : bailRaw.reasons.slice();

  // Compute plea offer using G010/G013 makePleaOffer and guarantee a genuine plea discount
  // relative to even the most lenient judge's trial sentence on the same top charge.
  const topCharge = charges[0]!;
  const pleaRaw = makePleaOffer({ charges, priorConvictions });
  const stat = topCharge.statute;
  const minTrialFactor = clamp01(
    0.18 + topCharge.severity * 0.42 + Math.min(0.28, priorConvictions * 0.12) - 0.125,
  );
  const minExpectedTrialJailDays = Math.round(
    stat.minJailDays + (stat.maxJailDays - stat.minJailDays) * minTrialFactor,
  );
  const minExpectedTrialFineDollars = Math.round(
    stat.minFine + (stat.maxFine - stat.minFine) * clamp01(minTrialFactor * 0.9),
  );
  const pleaJailDays = Math.min(
    pleaRaw.recommendedJailDays,
    minExpectedTrialJailDays > 0 ? Math.max(0, Math.floor(minExpectedTrialJailDays * 0.72)) : 0,
  );
  const pleaFineDollars = Math.min(
    pleaRaw.recommendedFine,
    Math.max(1, Math.floor(minExpectedTrialFineDollars * 0.75)),
  );
  const pleaFineCents = Math.round(pleaFineDollars * 100);

  const lawyerOptions = buildLawyerOptions(sim, caseId, playerCashCents);
  const defaultLawyer = hireDefenseLawyer({
    seed: sim.seed,
    caseId,
    year: sim.year,
    tier: 'public_defender',
  });

  const jurySelection = selectJury({
    seed: `${sim.seed}:court:${caseId}:a1`,
    world: sim.world,
    crimeState,
    policeCase: primary,
    defendantId: sim.playerId,
    year: sim.year,
    lawyer: defaultLawyer,
    attempt: 1,
  });

  const witnessIds = collectProsecutionWitnessIds(
    { ...sim, crimeState },
    sorted,
  );

  const courtDateDay = sim.currentDay + era.arraignmentDays;
  const courtDateHour = 9;
  const courtPlaceId = findCourthousePlaceId(sim, jurId);
  const custody = bailRaw.granted ? 'awaiting_bail_decision' : 'remanded_no_bail';

  const chargeList = charges.map((c) => c.statuteName).join(', ');
  const facts: string[] = [
    `Charged with ${chargeList}.`,
    bailRaw.granted
      ? `Bail set at ${courtMoney(bailAmountCents, sim.year)} (flight risk ${Math.round(bailRaw.flightRisk * 100)}%).`
      : bailReasons[0] ?? 'Remanded without bail pending trial.',
    `Court date set for Day ${courtDateDay} at ${courtDateHour}:00.`,
    `Plea offer: ${pleaJailDays} day(s) in custody and ${courtMoney(pleaFineCents, sim.year)} fine.`,
  ];

  return {
    id: caseId,
    jurisdictionId: jurId,
    defendantId: sim.playerId,
    primaryCrimeId: primary.crimeId,
    crimeIds: sorted.map((c) => c.crimeId),
    charges,
    era,
    filedDay: sim.currentDay,
    filedHour: sim.currentHour,
    courtDateDay,
    courtDateHour,
    courtPlaceId,
    status: 'pending_trial',
    custody,
    bail: {
      granted: bailRaw.granted,
      amountCents: bailAmountCents,
      flightRisk: bailRaw.flightRisk,
      canAfford: bailCanAfford,
      paid: false,
      reasons: bailReasons,
    },
    pleaOffer: {
      offered: pleaRaw.offered,
      chargeIds: pleaRaw.chargeIds,
      reducedToMisdemeanor: pleaRaw.reducedToMisdemeanor,
      recommendedJailDays: pleaJailDays,
      recommendedFineCents: pleaFineCents,
      discountRatio: pleaRaw.discountRatio,
      caseStrengthAtOffer: pleaRaw.caseStrengthAtOffer,
      reasoning: pleaRaw.reasoning,
    },
    lawyer: defaultLawyer,
    lawyerFeeCents: 0,
    lawyerOptions,
    attempt: 1,
    jurySelection,
    witnessIds,
    tampering: [],
    facts,
  };
}

export function applyBailDecision(
  sim: LoopSim,
  input: BailDecisionInput,
): BailDecisionResult {
  const cc = findTargetCourtCase(sim, input.courtCaseId, ['pending_trial']);
  if (!cc) {
    return {
      sim,
      ok: false,
      bailPaidCents: 0,
      inCustody: false,
      summary: 'No pending court case awaiting bail.',
    };
  }

  if (input.choice === 'pay_bail') {
    if (!cc.bail.granted) {
      const reason = cc.bail.reasons[0] ?? 'Bail was denied on this charge; remanded in custody.';
      return {
        sim,
        ok: false,
        courtCase: cc,
        bailPaidCents: 0,
        inCustody: true,
        summary: reason,
      };
    }
    if (cc.bail.paid && cc.custody === 'out_on_bail') {
      return {
        sim,
        ok: true,
        courtCase: cc,
        bailPaidCents: 0,
        inCustody: false,
        summary: `Bail was already posted; court date is Day ${cc.courtDateDay} at ${cc.courtDateHour}:00.`,
      };
    }
    if (input.playerCash !== undefined && input.playerCash < cc.bail.amountCents) {
      const updated: LoopCourtCase = {
        ...cc,
        custody: 'in_custody_bail_unpaid',
        bail: { ...cc.bail, canAfford: false },
      };
      const nextSim: LoopSim = {
        ...sim,
        courtCases: { ...(sim.courtCases ?? {}), [cc.id]: updated },
      };
      return {
        sim: nextSim,
        ok: false,
        courtCase: updated,
        bailPaidCents: 0,
        inCustody: true,
        summary: `Insufficient funds for ${courtMoney(cc.bail.amountCents, sim.year)} bail; remaining in custody until Day ${cc.courtDateDay}.`,
      };
    }

    const fact = `Posted ${courtMoney(cc.bail.amountCents, sim.year)} bail and released pending Day ${cc.courtDateDay} at ${cc.courtDateHour}:00.`;
    const updated: LoopCourtCase = {
      ...cc,
      custody: 'out_on_bail',
      bail: { ...cc.bail, paid: true, canAfford: true },
      facts: [...cc.facts, fact],
    };
    const nextSim: LoopSim = {
      ...sim,
      courtCases: { ...(sim.courtCases ?? {}), [cc.id]: updated },
    };
    return {
      sim: nextSim,
      ok: true,
      courtCase: updated,
      bailPaidCents: cc.bail.amountCents,
      inCustody: false,
      summary: fact,
    };
  }

  const nextCustody = cc.bail.granted ? 'in_custody_bail_unpaid' : 'remanded_no_bail';
  const fact = `Remaining in custody until court on Day ${cc.courtDateDay} at ${cc.courtDateHour}:00.`;
  const updated: LoopCourtCase = {
    ...cc,
    custody: nextCustody,
    facts: [...cc.facts, fact],
  };
  const nextSim: LoopSim = {
    ...sim,
    courtCases: { ...(sim.courtCases ?? {}), [cc.id]: updated },
  };
  return {
    sim: nextSim,
    ok: true,
    courtCase: updated,
    bailPaidCents: 0,
    inCustody: true,
    summary: fact,
  };
}

export function applyChooseCounsel(
  sim: LoopSim,
  input: ChooseCounselInput,
): ChooseCounselResult {
  const cc = findTargetCourtCase(sim, input.courtCaseId, ['pending_trial']);
  if (!cc) {
    return {
      sim,
      ok: false,
      feeCents: 0,
      summary: 'No pending court case to retain counsel for.',
    };
  }

  const options = buildLawyerOptions(sim, cc.id, input.playerCash);
  let chosenTier: LawyerTier = input.tier ?? 'public_defender';
  if (!input.tier && input.playerCash !== undefined) {
    for (let i = options.length - 1; i >= 0; i--) {
      if (options[i]!.canAfford) {
        chosenTier = options[i]!.tier;
        break;
      }
    }
  }

  const feeCents = Math.round(lawyerFeeForTier(chosenTier, sim.year) * 100);
  if (
    chosenTier !== 'public_defender' &&
    input.playerCash !== undefined &&
    input.playerCash < feeCents
  ) {
    return {
      sim,
      ok: false,
      courtCase: { ...cc, lawyerOptions: options },
      lawyer: cc.lawyer,
      feeCents: 0,
      summary: `Cannot afford ${chosenTier.replace(/_/g, ' ')} (${courtMoney(feeCents, sim.year)}).`,
    };
  }

  const lawyer = hireDefenseLawyer({
    seed: sim.seed,
    caseId: cc.id,
    year: sim.year,
    tier: chosenTier,
  });

  const hasJurorTampering = cc.tampering.some(
    (t) => t.kind === 'bribe_juror' || t.kind === 'threaten_juror',
  );
  const nextJury = hasJurorTampering
    ? cc.jurySelection
    : selectJury({
        seed: `${sim.seed}:court:${cc.id}:a${cc.attempt}`,
        world: sim.world,
        crimeState: sim.crimeState,
        policeCase: reconstructPoliceCase(sim, cc),
        defendantId: sim.playerId,
        year: sim.year,
        lawyer,
        attempt: cc.attempt,
      });

  const feeText = feeCents > 0 ? courtMoney(feeCents, sim.year) : 'no fee';
  const fact = `Retained ${lawyer.name} (${chosenTier.replace(/_/g, ' ')}, ${feeText}).`;
  const updated: LoopCourtCase = {
    ...cc,
    lawyer,
    lawyerFeeCents: feeCents,
    lawyerOptions: options,
    jurySelection: nextJury,
    facts: [...cc.facts, fact],
  };

  const nextSim: LoopSim = {
    ...sim,
    courtCases: { ...(sim.courtCases ?? {}), [cc.id]: updated },
  };
  return {
    sim: nextSim,
    ok: true,
    courtCase: updated,
    lawyer,
    feeCents,
    summary: fact,
  };
}

export function applyCourtInterference(
  sim: LoopSim,
  input: CourtInterferenceInput,
): CourtInterferenceResult {
  const cc = findTargetCourtCase(sim, input.courtCaseId, ['pending_trial']);
  if (!cc) {
    return {
      sim,
      ok: false,
      kind: input.kind,
      succeeded: false,
      targetReported: false,
      policeCaseOpened: false,
      witnessIds: [],
      summary: 'No pending court case for court interference.',
    };
  }

  let targetId = input.targetId;
  if (!targetId) {
    if (input.kind === 'bribe_juror' || input.kind === 'threaten_juror') {
      targetId = cc.jurySelection?.seated[0]?.personId;
    } else if (input.kind === 'bribe_witness' || input.kind === 'threaten_witness') {
      targetId = cc.witnessIds[0];
    } else {
      targetId = cc.witnessIds[0] ?? cc.jurySelection?.seated[0]?.personId;
    }
  }
  if (!targetId || !sim.world.people[targetId]) {
    return {
      sim,
      ok: false,
      courtCaseId: cc.id,
      kind: input.kind,
      succeeded: false,
      targetReported: false,
      policeCaseOpened: false,
      witnessIds: [],
      summary: 'No valid juror or witness target found for court interference.',
    };
  }

  const placeId = input.placeId ?? cc.courtPlaceId;
  const place = sim.places[placeId];
  const position = place ? { x: place.position.x, y: place.position.y } : { x: 0, y: 0 };
  const bribeDollars =
    input.bribeAmount !== undefined ? Math.max(0, Math.round(input.bribeAmount / 100)) : 0;

  const bystanders = (input.bystanders ?? []).map((b) => ({
    personId: b.id,
    position: { x: b.position.x, y: b.position.y },
    attention:
      b.activity === 'watching'
        ? 0.95
        : b.activity === 'distracted'
          ? 0.3
          : b.activity === 'sleeping'
            ? 0.05
            : 0.75,
  }));

  const res = commitTrialInterference({
    seed: `${sim.seed}:interfere:${sim.nextSeq}`,
    world: sim.world,
    crimeState: sim.crimeState,
    jurisdictionId: cc.jurisdictionId,
    placeId,
    position,
    year: sim.year,
    day: sim.currentDay,
    hour: sim.currentHour,
    culpritId: sim.playerId,
    culpritAppearance: sim.playerAppearance,
    kind: input.kind,
    targetId,
    bribeAmount: bribeDollars,
    bystanders,
    caughtInCourt: input.caughtInCourt,
  });

  const crimeKind: CrimeKind =
    input.kind === 'bribe_juror' || input.kind === 'bribe_witness'
      ? 'bribery'
      : input.kind === 'threaten_juror' || input.kind === 'threaten_witness'
        ? 'intimidation'
        : 'perjury';

  const policeCaseOpened = Boolean(
    res.crimeState.jurisdictions[cc.jurisdictionId]?.cases[res.crimeId],
  );

  const nextCrimeMeta: Record<string, CrimeMetaRecord> = {
    ...sim.crimeMeta,
    [res.crimeId]: {
      crimeId: res.crimeId,
      kind: crimeKind,
      placeId,
      placeName: place?.name ?? placeId,
      jurisdictionId: cc.jurisdictionId,
      day: sim.currentDay,
      dayName: dayNameFor(sim.startDay, sim.startDayOfWeek, sim.currentDay),
      itemLabel: input.kind.replace(/_/g, ' '),
      ...(input.bribeAmount !== undefined ? { itemValue: input.bribeAmount } : {}),
      ownerId: targetId,
      resolved: false,
    },
  };

  const nextTampering = res.tampering
    ? [...cc.tampering, res.tampering]
    : cc.tampering.slice();
  const updatedCase: LoopCourtCase = {
    ...cc,
    tampering: nextTampering,
    facts: [...cc.facts, res.summary],
  };

  const nextSim: LoopSim = {
    ...sim,
    crimeState: res.crimeState,
    crimeMeta: nextCrimeMeta,
    courtCases: { ...(sim.courtCases ?? {}), [cc.id]: updatedCase },
    nextSeq: sim.nextSeq + 1,
  };

  return {
    sim: nextSim,
    ok: true,
    courtCaseId: cc.id,
    crimeId: res.crimeId,
    crimeKind,
    kind: input.kind,
    targetId,
    succeeded: res.succeeded,
    targetReported: res.targetReported,
    policeCaseOpened,
    witnessIds: res.witnessIds,
    ...(res.tampering ? { tampering: res.tampering } : {}),
    summary: res.summary,
  };
}

export function executeTrialOnDate(params: {
  sim: LoopSim;
  courtCase: LoopCourtCase;
  day: number;
}): {
  outcome: TrialOutcome;
  updatedCase: LoopCourtCase;
  sentence?: LoopCourtSentence;
  retrialDateDay?: number;
  retrialDateHour?: number;
  facts: string[];
  summary: string;
} {
  const { sim, courtCase: cc, day } = params;
  const policeCase = reconstructPoliceCase(sim, cc);
  const outcome = runTrial({
    seed: `${sim.seed}:court:${cc.id}:a${cc.attempt}`,
    world: sim.world,
    crimeState: sim.crimeState,
    policeCase,
    defendantId: sim.playerId,
    jurisdictionId: cc.jurisdictionId,
    year: sim.year,
    day,
    lawyer: cc.lawyer,
    priorConvictions: sim.priorOffenses.length,
    jury: cc.jurySelection,
    attempt: cc.attempt,
    tampering: cc.tampering,
  });

  const chargeList = cc.charges.map((c) => c.statuteName).join(', ');
  const facts: string[] = [
    `Trial held on Day ${day} for ${chargeList} (defense counsel: ${cc.lawyer.name}).`,
    `Jury deliberated for ${outcome.deliberationRounds} round(s) (${outcome.guiltyVotes} guilty, ${outcome.notGuiltyVotes} not guilty).`,
  ];

  let sentence: LoopCourtSentence | undefined;
  let updatedCase: LoopCourtCase;
  let retrialDateDay: number | undefined;
  let retrialDateHour: number | undefined;
  let summary: string;

  if (outcome.verdict === 'guilty' && outcome.sentence) {
    const fineCents = Math.round(outcome.sentence.fineAmount * 100);
    const jailDays = outcome.sentence.jailDays;
    const jailHours = jailDays * 24;
    sentence = {
      jailDays,
      jailHours,
      fineAmount: fineCents,
      probationDays: outcome.sentence.probationDays,
      reputationDelta: outcome.sentence.reputationDelta,
      judgeId: outcome.sentence.judgeId,
      judgeName: outcome.sentence.judgeName,
      reasoning: outcome.sentence.reasoning,
    };
    const verdictFact = `Verdict: GUILTY (${outcome.guiltyVotes}-${outcome.notGuiltyVotes}).`;
    const sentenceFact = `Sentenced by ${sentence.judgeName} to ${jailDays} day(s) in custody and a ${courtMoney(fineCents, sim.year)} fine.`;
    facts.push(verdictFact, sentenceFact);
    summary = `${verdictFact} ${sentenceFact}`;
    updatedCase = {
      ...cc,
      status: 'guilty',
      custody: 'resolved',
      lastVerdict: 'guilty',
      sentence,
      facts: [...cc.facts, ...facts],
    };
  } else if (outcome.verdict === 'not_guilty') {
    const verdictFact = `Verdict: NOT GUILTY (${outcome.guiltyVotes}-${outcome.notGuiltyVotes}). Charges dismissed.`;
    facts.push(verdictFact);
    summary = verdictFact;
    updatedCase = {
      ...cc,
      status: 'not_guilty',
      custody: 'resolved',
      lastVerdict: 'not_guilty',
      facts: [...cc.facts, ...facts],
    };
  } else {
    const nextAttempt = cc.attempt + 1;
    retrialDateDay = day + Math.max(3, Math.round(cc.era.arraignmentDays * 0.5));
    retrialDateHour = 9;
    const verdictFact = `Jury deadlocked (${outcome.guiltyVotes}-${outcome.notGuiltyVotes}) after ${outcome.deliberationRounds} rounds: mistrial declared; retrial set for Day ${retrialDateDay} at ${retrialDateHour}:00.`;
    facts.push(verdictFact);
    summary = verdictFact;
    const freshJury = selectJury({
      seed: `${sim.seed}:court:${cc.id}:a${nextAttempt}`,
      world: sim.world,
      crimeState: sim.crimeState,
      policeCase,
      defendantId: sim.playerId,
      year: sim.year,
      lawyer: cc.lawyer,
      attempt: nextAttempt,
    });
    const keptTampering = cc.tampering.filter(
      (t) => t.kind !== 'bribe_juror' && t.kind !== 'threaten_juror',
    );
    updatedCase = {
      ...cc,
      status: 'pending_trial',
      lastVerdict: 'hung',
      attempt: nextAttempt,
      courtDateDay: retrialDateDay,
      courtDateHour: retrialDateHour,
      jurySelection: freshJury,
      tampering: keptTampering,
      facts: [...cc.facts, ...facts],
    };
  }

  return {
    outcome,
    updatedCase,
    ...(sentence ? { sentence } : {}),
    ...(retrialDateDay !== undefined ? { retrialDateDay, retrialDateHour } : {}),
    facts,
    summary,
  };
}

export function issueMissedDateWarrant(params: {
  sim: LoopSim;
  crimeState: CrimeState;
  courtCase: LoopCourtCase;
  day: number;
  hour: number;
  seq: number;
}): {
  updatedCase: LoopCourtCase;
  warrant: LoopWarrantRecord;
  crimeState: CrimeState;
  bailForfeitedCents: number;
  facts: string[];
  summary: string;
} {
  const { sim, courtCase: cc, day, hour, seq } = params;
  const bailForfeitedCents = cc.bail.paid ? cc.bail.amountCents : 0;
  const warrantId = `warrant:fta:${cc.id}:${day}:${seq}`;
  const chargeNames = cc.charges.map((c) => c.statuteName).join(', ');
  const summary = `Bench warrant issued on Day ${day} for failure to appear on ${chargeNames}.`;
  const warrant: LoopWarrantRecord = {
    id: warrantId,
    courtCaseId: cc.id,
    crimeId: cc.primaryCrimeId,
    jurisdictionId: cc.jurisdictionId,
    reason: 'missed_court_date',
    issuedDay: day,
    issuedHour: hour,
    active: true,
    summary,
  };

  let nextCrimeState = params.crimeState;
  const crime = nextCrimeState.crimes[cc.primaryCrimeId];
  const courtPlace = sim.places[cc.courtPlaceId];
  const jur = nextCrimeState.jurisdictions[cc.jurisdictionId];
  const ftaReport: PoliceReport = {
    id: `rep:fta:${warrantId}`,
    crimeId: cc.primaryCrimeId,
    reporterId: courtPlace?.ownerId ?? Object.keys(sim.world.people)[0] ?? 'court_clerk',
    jurisdictionId: cc.jurisdictionId,
    filedDay: day,
    receivedDay: day,
    delayHours: 0,
    source: 'first_hand',
    isVictim: false,
    confidence: 0.98,
    identifiedCulpritId: sim.playerId,
    description: {
      height: sim.playerAppearance.height,
      build: sim.playerAppearance.build,
      hair: sim.playerAppearance.hair,
      clothingColor: sim.playerAppearance.clothingColor,
    },
  };

  if (crime) {
    nextCrimeState = {
      ...nextCrimeState,
      jurisdictions: {
        ...nextCrimeState.jurisdictions,
        [cc.jurisdictionId]: ingestPoliceReport(jur, crime, ftaReport),
      },
    };
  } else {
    const existingCase = reconstructPoliceCase(sim, cc);
    const updatedCase: PoliceCase = {
      ...existingCase,
      lastReportedDay: day,
      reports: [...existingCase.reports, ftaReport],
      merged: {
        ...existingCase.merged,
        namedSuspectId: sim.playerId,
        namedSuspectConfidence: 0.98,
        overallConfidence: 0.98,
      },
    };
    nextCrimeState = {
      ...nextCrimeState,
      jurisdictions: {
        ...nextCrimeState.jurisdictions,
        [cc.jurisdictionId]: {
          jurisdictionId: cc.jurisdictionId,
          cases: {
            ...(jur?.cases ?? {}),
            [cc.primaryCrimeId]: updatedCase,
          },
        },
      },
    };
  }

  const facts: string[] = [
    `Missed scheduled court appearance on Day ${day} at ${cc.courtDateHour}:00.`,
    summary,
  ];
  if (bailForfeitedCents > 0) {
    facts.push(`Forfeited ${courtMoney(bailForfeitedCents, sim.year)} bail.`);
  }
  facts.push('Local police have an active arrest warrant for you.');

  const updatedCase: LoopCourtCase = {
    ...cc,
    status: 'missed_date',
    custody: 'warrant_for_fta',
    bail: { ...cc.bail, forfeited: bailForfeitedCents > 0 ? true : cc.bail.forfeited },
    warrantIssued: true,
    warrantDay: day,
    facts: [...cc.facts, ...facts],
  };

  return {
    updatedCase,
    warrant,
    crimeState: nextCrimeState,
    bailForfeitedCents,
    facts,
    summary,
  };
}

export function buildCourtView(sim: LoopSim): CourtViewResult {
  const all = Object.values(sim.courtCases ?? {}).sort(
    (a, b) => a.filedDay - b.filedDay || (a.id < b.id ? -1 : 1),
  );
  const pending = all.filter((c) => c.status === 'pending_trial' || c.status === 'missed_date');
  const resolved = all.filter(
    (c) => c.status === 'plea_guilty' || c.status === 'guilty' || c.status === 'not_guilty',
  );
  const activeWarrants = (sim.warrants ?? []).filter((w) => w.active);
  return { pending, resolved, activeWarrants };
}

export function pruneResolvedCourtCases(
  courtCases: Record<string, LoopCourtCase> | undefined,
): { next: Record<string, LoopCourtCase> | undefined; changed: boolean } {
  if (!courtCases) return { next: undefined, changed: false };
  let changed = false;
  const out: Record<string, LoopCourtCase> = {};
  for (const [id, cc] of Object.entries(courtCases)) {
    const isResolved =
      cc.status === 'plea_guilty' || cc.status === 'guilty' || cc.status === 'not_guilty';
    if (isResolved && cc.jurySelection !== undefined) {
      const compact: LoopCourtCase = { ...cc };
      delete compact.jurySelection;
      out[id] = compact;
      changed = true;
    } else {
      out[id] = cc;
    }
  }
  return { next: out, changed };
}
