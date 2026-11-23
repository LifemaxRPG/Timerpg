import type {
  CaseStrengthResult,
  CrimeKind,
  CrimeState,
  CulpritAppearance,
  CulpritDescription,
  EvidenceMatch,
  PoliceCase,
  TraceKind,
  Vec2,
} from '../crime/types';
import type { Id, World } from '../sim/types';

export type CourtEraId = 'frontier_circuit' | 'interwar' | 'mid_century' | 'modern';

export interface EraEvidenceAdmissibility {
  fingerprints: boolean;
  automatedPrints: boolean;
  dna: boolean;
  camera: boolean;
  serials: boolean;
  plates: boolean;
}

export interface CourtEraProfile {
  eraId: CourtEraId;
  label: string;
  minYear: number;
  maxYear: number;
  /** Era multiplier for dollar fines, bail, and lawyer retainers (1978 ~= 1.0). */
  moneyScale: number;
  /** Whether indigent defendants are guaranteed funded public defense counsel. */
  publicDefenderFunded: boolean;
  /** Average wait in days from arrest to trial. */
  arraignmentDays: number;
  admissibility: EraEvidenceAdmissibility;
}

export interface StatuteRule {
  crimeKind: CrimeKind;
  statuteName: string;
  felony: boolean;
  minJailDays: number;
  maxJailDays: number;
  minFine: number;
  maxFine: number;
  baseBail: number;
  allowNoBail: boolean;
  requiresUnanimous: boolean;
  eraId: CourtEraId;
  admissibility: EraEvidenceAdmissibility;
}

export interface Charge {
  id: string;
  crimeId: string;
  kind: CrimeKind;
  statuteName: string;
  felony: boolean;
  severity: number;
  placeId: string;
  crimeDay: number;
  statute: StatuteRule;
  strength: CaseStrengthResult;
}

export type LawyerTier =
  | 'public_defender'
  | 'local_attorney'
  | 'retained_counsel'
  | 'elite_defense';

export interface DefenseLawyer {
  id: string;
  name: string;
  tier: LawyerTier;
  fee: number;
  /** Preparation & legal research quality (0..1). */
  preparation: number;
  /** Cross-examination & impeachment skill (0..1). */
  crossSkill: number;
  /** Rhetoric & closing-argument persuasion (0..1). */
  persuasion: number;
  /** Peremptory strikes allowed during voir dire. */
  peremptoryStrikes: number;
}

export interface BailDecision {
  granted: boolean;
  amount: number;
  flightRisk: number;
  canAfford: boolean;
  reasons: string[];
}

export interface PleaOffer {
  offered: boolean;
  chargeIds: string[];
  reducedToMisdemeanor: boolean;
  recommendedJailDays: number;
  recommendedFine: number;
  /** Fraction of expected trial sentence discounted (0..1). */
  discountRatio: number;
  caseStrengthAtOffer: number;
  reasoning: string;
}

export interface PretrialSummary {
  charges: Charge[];
  era: CourtEraProfile;
  bail: BailDecision;
  pleaOffer: PleaOffer;
  lawyer: DefenseLawyer;
}

export interface VoirDireFact {
  kind:
    | 'victim_kin'
    | 'victim_friend'
    | 'defendant_kin'
    | 'defendant_friend'
    | 'prior_gossip'
    | 'reputation_view'
    | 'trait_note';
  summary: string;
  /** Positive favors prosecution, negative favors defense. */
  lean: number;
}

export interface JurorCandidate {
  personId: Id;
  name: string;
  age: number;
  traits: { ambition: number; charm: number; diligence: number };
  isVictimKin: boolean;
  victimAffinity: number;
  isDefendantKin: boolean;
  defendantAffinity: number;
  knowsAboutCase: boolean;
  gossipConfidence: number;
  heardFrom?: Id;
  facts: VoirDireFact[];
}

export interface StrikeRecord {
  personId: Id;
  name: string;
  struckBy: 'for_cause' | 'defense' | 'prosecution';
  reason: string;
  facts: VoirDireFact[];
}

export interface SeatedJuror {
  seat: number;
  personId: Id;
  name: string;
  age: number;
  traits: { ambition: number; charm: number; diligence: number };
  victimAffinity: number;
  defendantAffinity: number;
  knowsAboutCase: boolean;
  gossipConfidence: number;
  /** Initial pre-trial lean (-0.35..+0.35; positive = leans guilty). */
  initialBias: number;
  facts: VoirDireFact[];
}

export interface JurySelectionResult {
  attempt: number;
  poolSize: number;
  seated: SeatedJuror[];
  struck: StrikeRecord[];
}

export type TrialEventPhase =
  | 'opening'
  | 'prosecution'
  | 'cross_examination'
  | 'defense'
  | 'closing'
  | 'deliberation'
  | 'verdict'
  | 'sentencing';

export type TrialEventKind =
  | 'opening_prosecution'
  | 'opening_defense'
  | 'prosecution_witness'
  | 'cross_examination'
  | 'prosecution_evidence'
  | 'evidence_challenge'
  | 'defense_case'
  | 'defendant_testimony'
  | 'tampering_effect'
  | 'closing_arguments'
  | 'deliberation_round'
  | 'verdict'
  | 'sentence';

export interface WitnessErrorReport {
  witnessId: Id;
  mistakenFields: string[];
  omittedFields: string[];
  errorCount: number;
  exposedCount: number;
  credibilityDrop: number;
}

export interface TrialEvent {
  seq: number;
  phase: TrialEventPhase;
  kind: TrialEventKind;
  speakerId?: Id;
  targetId?: Id;
  summary: string;
  /** Change in perceived prosecution guilt weight from this event. */
  impactOnGuilt: number;
  details?: Record<string, string | number | boolean | string[]>;
}

export interface JurorVote {
  seat: number;
  personId: Id;
  name: string;
  beliefInGuilt: number;
  vote: 'guilty' | 'not_guilty';
  tampered?: 'bribed' | 'threatened';
}

export type VerdictKind = 'guilty' | 'not_guilty' | 'hung';

export interface Sentence {
  jailDays: number;
  fineAmount: number;
  probationDays: number;
  reputationDelta: number;
  judgeId: Id;
  judgeName: string;
  reasoning: string;
}

export type TrialInterferenceKind =
  | 'bribe_juror'
  | 'threaten_juror'
  | 'bribe_witness'
  | 'threaten_witness'
  | 'perjury';

export interface ActiveTrialTampering {
  kind: TrialInterferenceKind;
  targetId: Id;
  crimeId: string;
  succeeded: boolean;
  /** Magnitude 0..1 of how strongly this bends the target's behavior. */
  strength: number;
}

export interface TrialInterferenceInput {
  seed: string;
  world: World;
  crimeState: CrimeState;
  jurisdictionId: string;
  placeId: string;
  position: Vec2;
  year: number;
  day: number;
  hour?: number;
  culpritId: Id;
  culpritAppearance: CulpritAppearance;
  kind: TrialInterferenceKind;
  targetId: Id;
  /** Cash offered for bribery (used when `kind` is `bribe_*`). */
  bribeAmount?: number;
  /** Optional bystanders who could see the bribe/threat/perjury happen. */
  bystanders?: Array<{ personId: Id; position: Vec2; attention?: number }>;
  /** True if the courtroom/prosecution directly catches perjury on cross-examination. */
  caughtInCourt?: boolean;
}

export interface TrialInterferenceResult {
  crimeState: CrimeState;
  crimeId: string;
  kind: TrialInterferenceKind;
  targetId: Id;
  succeeded: boolean;
  targetReported: boolean;
  witnessIds: Id[];
  tampering?: ActiveTrialTampering;
  summary: string;
}

export interface TrialOptions {
  seed: string;
  world: World;
  crimeState: CrimeState;
  policeCase: PoliceCase;
  defendantId: Id;
  jurisdictionId: string;
  year: number;
  day: number;
  /** Defense budget or explicit lawyer. */
  defenseBudget?: number;
  lawyerTier?: LawyerTier;
  lawyer?: DefenseLawyer;
  /** Number of prior convictions on the defendant's record. */
  priorConvictions?: number;
  /** Optional defendant wealth (defaults from `world.people[defendantId].wealth`). */
  defendantWealth?: number;
  /** Optional spoken testimony quality in 0..1 (0.5 = neutral / declined to testify). */
  defendantTestimonyScore?: number;
  /** Whether the defendant chooses to testify in their own defense. */
  defendantTestifies?: boolean;
  /** Optional pre-selected jury (otherwise `selectJury` runs automatically). */
  jury?: JurySelectionResult;
  /** Trial attempt number (1 = first trial, 2+ = retrial after hung jury). */
  attempt?: number;
  /** Optional judge personId (defaults to deterministic court judge). */
  judgeId?: Id;
  /** Optional prosecutor personId. */
  prosecutorId?: Id;
  /** Active trial tampering effects (from `commitTrialInterference`). */
  tampering?: readonly ActiveTrialTampering[];
  /** Optional explicit ground-truth appearance of the real culprit (defaults from `crimeState.crimes[case.crimeId]`). */
  actualCulpritAppearance?: CulpritAppearance;
  /** Optional extra exculpatory or corroborating forensic matches (e.g. defense DNA test). */
  additionalMatches?: readonly EvidenceMatch[];
  /** Optional town filter for jury pool (defaults to all eligible adults in `world.people`). */
  eligibleJurorIds?: readonly Id[];
}

export interface TrialOutcome {
  caseId: string;
  crimeId: string;
  defendantId: Id;
  jurisdictionId: string;
  year: number;
  day: number;
  attempt: number;
  era: CourtEraProfile;
  pretrial: PretrialSummary;
  jurySelection: JurySelectionResult;
  events: TrialEvent[];
  witnessErrors: WitnessErrorReport[];
  perceivedGuiltScore: number;
  admissibleEvidenceKinds: TraceKind[];
  ignoredEraEvidenceKinds: TraceKind[];
  deliberationRounds: number;
  votes: JurorVote[];
  guiltyVotes: number;
  notGuiltyVotes: number;
  verdict: VerdictKind;
  retrialRecommended: boolean;
  sentence?: Sentence;
  /** True if the conviction was later overturned on appeal. */
  exonerated?: boolean;
  exonerationYear?: number;
}

export type AppealGrounds =
  | 'new_dna_match'
  | 'new_witness'
  | 'witness_recantation'
  | 'forensic_retest'
  | 'procedural_error';

export interface AppealInput {
  seed: string;
  world: World;
  crimeState: CrimeState;
  conviction: TrialOutcome;
  year: number;
  day: number;
  grounds: AppealGrounds;
  /** New forensic match (e.g. DNA matching the real culprit years later). */
  newEvidenceMatch?: EvidenceMatch;
  /** True if new forensic testing excludes the defendant even without naming someone else. */
  excludesDefendant?: boolean;
  /** Witness ID who recanted their testimony. */
  recantingWitnessId?: Id;
  /** Confidence (0..1) of a newly discovered exculpatory witness. */
  newWitnessConfidence?: number;
  /** Lawyer quality for the appeal (defaults to original lawyer or public defender). */
  appealLawyer?: DefenseLawyer;
  /** Days of sentence already served before the appeal is decided. */
  daysServed?: number;
}

export interface AppealResult {
  convictionCrimeId: string;
  defendantId: Id;
  appealYear: number;
  appealDay: number;
  grounds: AppealGrounds;
  outcome: 'exonerated' | 'new_trial_ordered' | 'denied';
  vacatedJailDays: number;
  reputationRestored: number;
  compensationAmount: number;
  trueSuspectId?: Id;
  updatedConviction: TrialOutcome;
  reasoning: string;
}
