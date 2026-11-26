import type {
  CaseStrengthResult,
  ClothingColor,
  CrimeKind,
  CrimeState,
  CulpritAppearance,
  CulpritDescription,
  StreetLamp,
  TraceKind,
  Vec2,
  VehicleKind,
  WeatherKind,
} from '../crime/types';
import type { OfficerAssignment, PoliceState } from '../police/types';
import type { Fence } from '../economy/fence';
import type {
  ActiveTrialTampering,
  Charge,
  CourtEraProfile,
  DefenseLawyer,
  JurySelectionResult,
  LawyerTier,
  TrialInterferenceKind,
  VerdictKind,
} from '../court/types';
import type { Id, Person, World } from '../sim/types';

export type LoopPlaceKind =
  | 'general_store'
  | 'market'
  | 'pawn_shop'
  | 'police_station'
  | 'courthouse'
  | 'home'
  | 'yard'
  | 'street'
  | 'workplace'
  | 'other';

export interface LoopFriendLink {
  personId: string;
  /** Initial bond affinity in -1..1. */
  affinity: number;
}

export interface LoopPersonInit {
  id: string;
  /** Optional display name (derived from `first` + `surname` if omitted). */
  name?: string;
  first?: string;
  surname?: string;
  sex?: 'M' | 'F';
  age: number;
  traits?: {
    ambition?: number;
    charm?: number;
    diligence?: number;
  };
  homePlaceId: string;
  workplacePlaceId?: string;
  townId?: string;
  spouseId?: string;
  parentIds?: string[];
  childIds?: string[];
  siblingIds?: string[];
  friends?: LoopFriendLink[];
  wealth?: number;
  reputation?: number;
}

export interface LoopPlaceInit {
  id: string;
  name: string;
  kind: LoopPlaceKind;
  position: Vec2;
  ownerId?: string;
  /** Staff who work here (clerks, coworkers): see crimes while open and learn the player's name after a shift. */
  staff?: string[];
  jurisdictionId: string;
  townId?: string;
  /** Opening & closing hours in [0, 24] (e.g. `[8, 19]`). */
  openHours?: [number, number];
  /** Optional linked street-graph node id (defaults to `id` if present in graph). */
  nodeId?: string;
}

export interface LoopStreetNode {
  id: string;
  position: Vec2;
  kind: 'street' | 'intersection' | 'station' | 'building' | 'border';
  jurisdictionId: string;
  name?: string;
  streetLight?: number;
}

export interface LoopStreetEdge {
  from: string;
  to: string;
  distance?: number;
}

export interface LoopStreetGraph {
  nodes: LoopStreetNode[];
  edges: LoopStreetEdge[];
}

/**
 * Single tunable policy table governing how officers decide to watch, stop & question,
 * release after questioning, or arrest when spotting a suspect on the street.
 */
export interface StopPolicyTable {
  /** Minimum recognition chance to passively watch a person. */
  watchRecognition: number;
  /** Minimum recognition chance to stop and question an unidentified match. */
  questionRecognition: number;
  /** Minimum recognition chance to stop and question a named suspect. */
  namedQuestionRecognition: number;
  /** Minimum recognition chance to arrest immediately on sight (unidentified). */
  directArrestRecognition: number;
  /** Minimum case strength (or severity) required for immediate arrest on sight. */
  directArrestStrength: number;
  /** Minimum recognition chance to arrest immediately when suspect is named. */
  namedDirectArrestRecognition: number;
  /** Minimum case strength for immediate arrest of a named suspect. */
  namedDirectArrestStrength: number;
  /** Minimum case strength to arrest after stopping & questioning (below this -> released). */
  stopArrestStrength: number;
  /** Recognition boost when the target is actively fleeing. */
  fleeingBonus: number;
  /** Minimum jurisdiction heat to request papers during a stop (era >= papersCheckStartYear). */
  papersCheckHeat: number;
  /** Minimum case severity to request papers during a stop (era >= papersCheckStartYear). */
  papersCheckSeverity: number;
}

export interface LoopInit {
  seed: string;
  year: number;
  startDay?: number;
  startHour?: number;
  /** Optional day-of-week index for `startDay` (`0=Monday .. 6=Sunday`, default `0`). */
  startDayOfWeek?: number;
  playerId?: string;
  playerName?: string;
  /** Optional short plain-English description of the player for NPC dialogue facts (e.g. `"a tall stranger in a grey coat"`). */
  playerDescription?: string;
  playerAppearance?: CulpritAppearance;
  /** Optional overrides for the street-stop & arrest decision thresholds. */
  stopPolicy?: Partial<StopPolicyTable>;
  people: LoopPersonInit[];
  places: LoopPlaceInit[];
  streetGraph: LoopStreetGraph;
  streetLamps?: StreetLamp[];
  /** Optional map of `jurisdictionId -> parentJurisdictionId` for serious-crime escalation. */
  escalateJurisdiction?: Record<string, string>;
}

/** Bijective mapping between arbitrary `src/sim/` ids (e.g. `p:0001`) and G007 plan-safe `[a-z][a-z0-9_]*` ids. */
export interface IdMapState {
  simToPlan: Record<string, string>;
  planToSim: Record<string, string>;
}

export interface LoopPlaceRecord {
  id: string;
  name: string;
  kind: LoopPlaceKind;
  position: Vec2;
  ownerId?: string;
  staff: string[];
  jurisdictionId: string;
  townId: string;
  openHours?: [number, number];
  nodeId: string;
}

export interface LoopPersonMeta {
  id: string;
  name: string;
  homePlaceId: string;
  workplacePlaceId?: string;
  townId: string;
  siblingIds?: string[];
}

export interface LoopOfficer {
  id: string;
  /** Plan-safe identifier used inside G007 plans. */
  planId: string;
  jurisdictionId: string;
  role: 'constable' | 'patrol' | 'detective';
  shift: 'day' | 'swing' | 'night';
  transport: 'foot' | 'car';
  vehiclePlanId?: string;
  hasRadio: boolean;
  stationNodeId: string;
  beatNodeIds: string[];
  assignment: OfficerAssignment;
  targetCrimeId?: string;
  targetPlaceId?: string;
  routePlaceIds: string[];
}

export interface StolenItemRecord {
  itemId: string;
  label: string;
  /** Item value in integer cents (era money). */
  value: number;
  crimeId: string;
  placeId: string;
  jurisdictionId: string;
  ownerId?: string;
  dayStolen: number;
  serialNumber?: string;
  pawnedAtPlaceId?: string;
  pawnedDay?: number;
  tracedToPlayer?: boolean;
}

export interface PawnRecord {
  id: string;
  placeId: string;
  clerkId?: string;
  itemId: string;
  label: string;
  /** Sale/item value in integer cents (era money). */
  value: number;
  day: number;
  serialNumber?: string;
  stolenFromCrimeId?: string;
  sellerId: string;
  reportedToPolice: boolean;
}

export interface WorkShiftRecord {
  id: string;
  placeId: string;
  employerId?: string;
  coworkerIds: string[];
  day: number;
  hours: number;
  /** Wages earned in integer cents (era money). */
  payEarned: number;
}

export interface PlayerFamiliarity {
  knowsPlayerName: boolean;
  playerName?: string;
  trustDelta: number;
  positiveFacts: string[];
  bannedFromPlaceIds: string[];
  /** Number of completed fence deals with this person. */
  fenceDeals?: number;
  /** Fence standing in 0..1 matching `src/economy/fence.ts` `fenceDeal` trust. */
  fenceTrust?: number;
  /** True once this person has informed on the player to police. */
  informedOnPlayer?: boolean;
  /** True if this fence recently turned away goods for being too hot. */
  fenceRefusedTooHot?: boolean;
}

/** Compact per-person memory retained for a resolved crime after pruning. */
export interface ResolvedCrimeMemory {
  /** 'f' = first-hand witness, 'h' = hearsay. */
  s: 'f' | 'h';
  /** Person id who told them (for hearsay). */
  h?: string;
  /** 1 if this person was the victim/owner. */
  v?: 1;
}

/** Sub-day arrival schedule & sighting metadata for reports queued by `civilianSpotted` or `tipOff`. */
export interface PendingReportMeta {
  /** Absolute simulation hour (`day * 24 + hour`) when the report reaches the police station. */
  arrivalTotalHours: number;
  /** Place id where the player was spotted or where the tip originated. */
  sightingPlaceId?: string;
  kind: 'civilian_sighting' | 'informant_tip';
}

export type ReportMeans = 'in_person' | 'early_telephone' | 'telephone' | 'mobile';

export interface PriorOffenseRecord {
  id: string;
  crimeId: string;
  kind: CrimeKind;
  severity: number;
  jurisdictionId: string;
  day: number;
  penalty: 'warning' | 'fine' | 'cell_night' | 'jail' | 'charged';
  /** Fine in integer cents (era money). */
  fineAmount: number;
  jailHours: number;
}

export interface CrimeMetaRecord {
  crimeId: string;
  kind: CrimeKind;
  placeId: string;
  placeName: string;
  jurisdictionId: string;
  day: number;
  dayName: string;
  itemLabel?: string;
  /** Item value in integer cents (era money). */
  itemValue?: number;
  ownerId?: string;
  trespass?: boolean;
  resolved: boolean;
}

export type AttitudeStance = 'serve' | 'wary' | 'refuse' | 'report_on_sight';

/** Complete plain-JSON simulation state owned by `src/loop/`. Safe for `JSON.stringify`/`JSON.parse`. */
export interface LoopSim {
  version: 1;
  seed: string;
  year: number;
  currentDay: number;
  currentHour: number;
  startDay: number;
  startDayOfWeek: number;
  playerId: string;
  playerName: string;
  playerDescription?: string;
  playerAppearance: CulpritAppearance;
  stopPolicy: StopPolicyTable;
  world: World;
  crimeState: CrimeState;
  /** Authoritative G009 police state (`src/police/`). */
  policeState: PoliceState;
  idMap: IdMapState;
  places: Record<string, LoopPlaceRecord>;
  peopleMeta: Record<string, LoopPersonMeta>;
  streetGraph: LoopStreetGraph;
  streetLamps: StreetLamp[];
  escalateJurisdiction: Record<string, string>;
  /** Projected officer view kept in sync with `policeState.officers` (sim-domain node ids). */
  officers: Record<string, LoopOfficer>;
  respondedCases: Record<string, string>;
  crimeMeta: Record<string, CrimeMetaRecord>;
  stolenItems: Record<string, StolenItemRecord>;
  pawnRecords: PawnRecord[];
  workShifts: WorkShiftRecord[];
  familiarity: Record<string, PlayerFamiliarity>;
  priorOffenses: PriorOffenseRecord[];
  /** Compact memories retained for pruned resolved crimes so `attitude()` stays accurate. */
  resolvedCrimeMemories?: Record<string, Record<string, ResolvedCrimeMemory>>;
  /** Sub-day arrival timestamps & sighting places for civilian/informant reports. */
  pendingReportMeta?: Record<string, PendingReportMeta>;
  /** Most recent confirmed sighting/tip place per active crime id. */
  latestSightingPlaceByCrime?: Record<string, string>;
  /** Active and historical court dockets owned by `src/loop/` (G019). */
  courtCases?: Record<string, LoopCourtCase>;
  /** Bench warrants issued for missed court dates (G019). */
  warrants?: LoopWarrantRecord[];
  /** Cached stance per personId so `advance` reports exact attitude diffs. */
  lastStances: Record<string, AttitudeStance>;
  nextSeq: number;
}

/** Bystander snapshot at the moment of an `act()` call. */
export interface SceneBystander {
  id: string;
  position: Vec2;
  /** Facing angle in radians (optional; if provided, people facing away have sharply reduced sight). */
  facingRad?: number;
  /** Optional unit facing vector (alternative to `facingRad`). */
  facing?: Vec2;
  /** Optional explicit line-of-sight flag supplied by the 3D renderer. */
  hasLineOfSight?: boolean;
  /** Activity affects attentiveness (e.g. `'idle'`, `'watching'`, `'working'`, `'distracted'`, `'sleeping'`). */
  activity?: 'idle' | 'watching' | 'walking' | 'working' | 'talking' | 'distracted' | 'sleeping';
}

export interface SceneSnapshot {
  position?: Vec2;
  bystanders?: SceneBystander[];
  /** Ambient light in 0..1 (optional; defaults to era/hour + streetLamps). */
  light?: number;
  /** Player's appearance at this moment (updates `sim.playerAppearance` if supplied). */
  appearance?: CulpritAppearance;
  weather?: WeatherKind;
}

export interface LoopItemPayload {
  id: string;
  label: string;
  /** Item value in integer cents (era money). */
  value?: number;
  serialNumber?: string;
  stolenFromCrimeId?: string;
}

export type LoopActionEvent =
  | ({
      kind: 'buy';
      placeId: string;
      item: LoopItemPayload;
      clerkId?: string;
    } & SceneSnapshot)
  | ({
      kind: 'sell';
      placeId: string;
      item: LoopItemPayload;
      clerkId?: string;
    } & SceneSnapshot)
  | ({
      kind: 'work_shift';
      placeId: string;
      hours?: number;
      /** Shift wages in integer cents (era money). Defaults to era shop-clerk wage * hours. */
      pay?: number;
      employerId?: string;
      coworkerIds?: string[];
      taskLabel?: string;
    } & SceneSnapshot)
  | ({
      kind: 'take';
      placeId: string;
      item: LoopItemPayload;
      subkind?: 'shoplift' | 'yard' | 'house' | 'pickpocket' | 'robbery';
      ownerId?: string;
      gloved?: boolean;
    } & SceneSnapshot)
  | ({
      kind: 'take_vehicle';
      placeId: string;
      vehicle: {
        id: string;
        kind: VehicleKind;
        color: ClothingColor;
        plateNumber?: string;
        /** Vehicle value in integer cents (era money). */
        value?: number;
      };
      ownerId?: string;
      gloved?: boolean;
    } & SceneSnapshot)
  | ({
      kind: 'enter';
      placeId: string;
      isOpen?: boolean;
      forcedEntry?: boolean;
      ownerId?: string;
      gloved?: boolean;
    } & SceneSnapshot)
  | ({
      kind: 'fence_sale';
      placeId: string;
      /** Person id of the fence (a real person in `world.people` / `peopleMeta`). */
      fenceId: string;
      item: LoopItemPayload;
      /** Optional Sam `Fence` profile from `src/economy/fence.ts`; derived from the person if omitted. */
      fence?: Partial<Fence>;
      /** Optional economy catalogue item id (e.g. 'pocket_watch') for `fenceDeal` pricing. */
      catalogueItemId?: string;
      /** Optional police heat override in 0..1; defaults to current jurisdiction heat / 100. */
      heat?: number;
      /** Optional explicit override for the `fenceRisk` informant tip roll (useful in tests/scripts). */
      forceTip?: boolean;
    } & SceneSnapshot)
  | ({
      kind: 'assault';
      placeId: string;
      targetId: string;
      /** True when a weapon is used (raises severity). */
      weapon?: boolean;
      /** True when the attack is lethal (records `murder`, capital charge). */
      lethal?: boolean;
      gloved?: boolean;
    } & SceneSnapshot)
  | ({
      kind: 'court_interference';
      interferenceKind: TrialInterferenceKind;
      courtCaseId?: string;
      targetId?: string;
      placeId?: string;
      /** Bribe cash offered in integer cents (era money). */
      bribeAmount?: number;
      /** True if perjury is caught in open court. */
      caughtInCourt?: boolean;
    } & SceneSnapshot);

export interface WitnessNotice {
  personId: string;
  name: string;
  confidence: number;
  recognizedPlayer: boolean;
  isVictim: boolean;
  description: CulpritDescription;
}

export interface ActTraceSummary {
  id: string;
  kind: TraceKind;
  quality: number;
  serialNumber?: string;
}

export interface ActResult {
  sim: LoopSim;
  kind: LoopActionEvent['kind'];
  legit: boolean;
  /** True when selling a stolen ("hot") item; false for clean sales. */
  hot?: boolean;
  crimeId?: string;
  /** Wages earned on `work_shift`, in integer cents (era money). */
  payEarned?: number;
  stolenSerialNumber?: string;
  witnessedBy: WitnessNotice[];
  tracesLeft: ActTraceSummary[];
  pawnTraceFlagged?: boolean;
  /** Populated on `fence_sale`: whether the fence accepted the goods. */
  fenceAccepted?: boolean;
  /** Populated on rejected `fence_sale`. */
  fenceRefusalReason?: 'category' | 'too_hot' | 'no_offer';
  /** Updated 0..1 fence standing after a `fence_sale`. */
  fenceTrustAfter?: number;
  /** True when an informant fence decided to tip off police on this deal. */
  tipOffQueued?: boolean;
  tipDelayHours?: number;
  tipArrivalDay?: number;
  tipArrivalHour?: number;
  tipMeans?: ReportMeans;
  /** Populated on `court_interference`: whether the bribe/threat/perjury bent the target. */
  interferenceSucceeded?: boolean;
  /** Populated on `court_interference`: whether the target refused or reported to police. */
  interferenceReported?: boolean;
  /** Updated sim clock and world changes when `act` advances time (owned by `work_shift`). */
  day?: number;
  hour?: number;
  daysElapsed?: number;
  policePlans?: OfficerPlanDirective[];
  attitudeChanges?: AttitudeChange[];
  newPoliceCases?: string[];
  pawnLeadsMatched?: PawnLeadMatch[];
  summary: string;
}

export interface OfficerPlanDirective {
  officerId: string;
  planId: string;
  jurisdictionId: string;
  role: LoopOfficer['role'];
  assignment: LoopOfficer['assignment'];
  /** Compiled & bound G007 plan string ready for `PlanWorld` execution. */
  planSource: string;
  /** Sim-domain place/node ids along the officer's route (already reverse-mapped from plan ids). */
  routePlaceIds: string[];
  targetPlaceId?: string;
  targetPersonId?: string;
  crimeId?: string;
  reason: string;
}

export interface AttitudeChange {
  personId: string;
  name: string;
  previousStance: AttitudeStance;
  newStance: AttitudeStance;
  reasons: string[];
}

export interface PawnLeadMatch {
  crimeId: string;
  serialNumber?: string;
  pawnShopId: string;
  suspectId: string;
}

export interface AdvanceOptions {
  weather?: WeatherKind;
  /** Explicit court attendance flag when out on bail and `advance()` reaches a court date (`false` = missed date -> warrant). */
  attendCourt?: boolean;
  /** Optional current player place id when `advance()` runs; if set while out on bail and not at the courthouse, counts as missing the court date unless `attendCourt` is explicitly true. */
  playerPlaceId?: string;
  /** Optional flag to skip automatically serving jail days inside `advance()` after a guilty verdict. */
  skipSentenceAdvance?: boolean;
}

export interface AdvanceResult {
  sim: LoopSim;
  fromDay: number;
  toDay: number;
  fromHour: number;
  toHour: number;
  daysElapsed: number;
  policePlans: OfficerPlanDirective[];
  attitudeChanges: AttitudeChange[];
  newPoliceCases: string[];
  pawnLeadsMatched: PawnLeadMatch[];
  /** Booking reports produced when a G009 arrest completes transport to the station during `advance()`. */
  bookingEvents?: BookingReport[];
  /** Trial outcomes or missed-court-date warrants triggered when `advance()` reached a scheduled court date. */
  courtEvents?: LoopTrialEventSummary[];
}

export interface AttitudeResult {
  personId: string;
  name: string;
  stance: AttitudeStance;
  knowsPlayerName: boolean;
  playerNameKnown?: string;
  trust: number;
  /** Short, plain-English facts written for speaking to the player ("you"), ready for the dialogue model's fact sheet. */
  facts: string[];
}

export interface PoliceViewCase {
  crimeId: string;
  kind: CrimeKind;
  severity: number;
  placeId: string;
  placeName: string;
  reportCount: number;
  firstHandCount: number;
  heat: number;
  mergedDescription: CulpritDescription;
  namedSuspectId?: string;
  overallConfidence: number;
  strength: CaseStrengthResult;
}

export interface PoliceViewResult {
  jurisdictionId: string;
  day: number;
  year: number;
  heat: number;
  wantedIds: Id[];
  officers: OfficerPlanDirective[];
  cases: PoliceViewCase[];
}

export interface OfficerSightingInput {
  targetId?: string;
  distance: number;
  light?: number;
  appearance?: CulpritAppearance;
  placeId?: string;
  fleeing?: boolean;
}

export type LoopSpotAction = 'ignore' | 'watch' | 'approach_and_question' | 'arrest';

/** Outcome of a real street stop (era-gated papers check, questions, then release or arrest by case strength). */
export interface SpotStopOutcome {
  checkedPapers: boolean;
  questioned: boolean;
  outcome: 'released' | 'arrested';
  reason: string;
  caseStrength: number;
  thresholdUsed: number;
}

export interface SpotResult {
  officerId: string;
  jurisdictionId: string;
  targetId: string;
  action: LoopSpotAction;
  recognitionChance: number;
  matchedCaseId?: string;
  reason: string;
  /** Populated when `action === 'approach_and_question'`: real papers/questions stop outcome. */
  stopOutcome?: SpotStopOutcome;
  /** Optional bound G007 plan for the officer to act out when `action !== 'ignore'`. */
  planSource?: string;
}

export interface ArrestInput {
  jurisdictionId: string;
  officerId?: string;
  crimeIds?: string[];
  /** Player's cash on hand in integer cents (era money). */
  playerCash?: number;
  carriedItemIds?: string[];
  surrendered?: boolean;
  /** True when initiated from an `approach_and_question` street stop; thin cases release without booking. */
  fromStop?: boolean;
  /** Optional caller reason (e.g. `'approach_and_question'`); treated as `fromStop` when equal to `'approach_and_question'`. */
  why?: string;
  /** Bypass thin-case stop release and force booking/sentencing. */
  forceArrest?: boolean;
  /** Force a petty first-offense misdemeanor onto the court docket instead of summary citation. */
  demandTrial?: boolean;
}

export interface ArrestChargeSummary {
  crimeId: string;
  kind: CrimeKind;
  severity: number;
  caseStrength: number;
  placeName: string;
}

export interface ArrestOutcomeResult {
  sim: LoopSim;
  jurisdictionId: string;
  penalty: 'warning' | 'fine' | 'cell_night' | 'jail' | 'charged';
  /** Fine in integer cents (era money, scaled by `priceIndex(year)`). */
  fineAmount: number;
  jailHours: number;
  jailNights: number;
  /** New simulation day after any jail hours are advanced. */
  day: number;
  /** New simulation hour after any jail hours are advanced. */
  hour: number;
  daysElapsed: number;
  /** True if this was a street stop released because the case was too weak to arrest. */
  releasedAfterStop?: boolean;
  charges: ArrestChargeSummary[];
  priorOffenses: number;
  totalCaseStrength: number;
  confiscatedItems: string[];
  coldCasesTriggered: string[];
  /** Structured booking result (enrolled prints/DNA, cold-case hits, pawn links). Omitted on street-stop release. */
  booking?: BookingReport;
  /** Plain-English facts found during station booking (prints/DNA, cold-case matches, pawn links, seizures). */
  bookingFindings: string[];
  /** Populated when `penalty === 'charged'`: formal court charges, bail, first court date, plea offer, and lawyer tiers. */
  courtCase?: LoopCourtCase;
  policePlans: OfficerPlanDirective[];
  attitudeChanges: AttitudeChange[];
  newPoliceCases: string[];
  summary: string;
}

export interface BookingColdCaseHit {
  crimeId: string;
  kind: CrimeKind;
  placeName: string;
  method: string;
  confidence: number;
  /** Plain-English fact describing the forensic match. */
  fact: string;
}

export interface BookingReport {
  jurisdictionId: string;
  suspectId: string;
  day: number;
  year: number;
  printsEnrolled: boolean;
  dnaEnrolled: boolean;
  scope: 'local' | 'national';
  coldCaseHits: BookingColdCaseHit[];
  pawnLinks: PawnLeadMatch[];
  /** Plain-English facts for the game to show or pass to dialogue. */
  findings: string[];
}

export interface CivilianSightingInput {
  /** Place id where the civilian spotted the player (defaults to civilian workplace/home). */
  placeId?: string;
  /** Distance in meters between civilian and player (default 8 m). */
  distance?: number;
  /** Ambient light level in 0..1 (defaults to era/hour lighting). */
  light?: number;
  /** Line-of-sight flag supplied by the 3D renderer (default true). */
  hasLineOfSight?: boolean;
  /** Optional player appearance override (defaults to `sim.playerAppearance`). */
  appearance?: CulpritAppearance;
  /** Optional jurisdiction override (defaults to sighting place or civilian home jurisdiction). */
  jurisdictionId?: string;
}

export interface CivilianSpotResult {
  sim: LoopSim;
  personId: string;
  name: string;
  stance: AttitudeStance;
  reported: boolean;
  jurisdictionId?: string;
  crimeIds: string[];
  reportIds: string[];
  /** Reporting delay in hours from G005 `reportLatencyForEra`. */
  delayHours: number;
  arrivalDay?: number;
  arrivalHour?: number;
  means?: ReportMeans;
  reason: string;
  summary: string;
}

export interface TipOffInput {
  informantId: string;
  itemId?: string;
  crimeId?: string;
  placeId?: string;
  jurisdictionId?: string;
  /** Confidence in 0..1 (default 0.82). */
  confidence?: number;
  /** Optional plain-English reason for the tip. */
  reason?: string;
  /** Whether the informant names the player to police (defaults to true if they know/dealt with the player). */
  identifiesPlayer?: boolean;
}

export interface TipOffResult {
  sim: LoopSim;
  informantId: string;
  informantName: string;
  jurisdictionId: string;
  queued: boolean;
  crimeId?: string;
  reportId?: string;
  delayHours: number;
  arrivalDay: number;
  arrivalHour: number;
  means: ReportMeans;
  reason: string;
  summary: string;
}

export type LoopCourtCustody =
  | 'awaiting_bail_decision'
  | 'out_on_bail'
  | 'in_custody_bail_unpaid'
  | 'remanded_no_bail'
  | 'warrant_for_fta'
  | 'resolved';

export type LoopCourtStatus =
  | 'pending_trial'
  | 'plea_guilty'
  | 'guilty'
  | 'not_guilty'
  | 'hung'
  | 'missed_date';

export interface LoopLawyerOption {
  tier: LawyerTier;
  name: string;
  /** Retainer fee in integer cents (era money). */
  feeCents: number;
  canAfford: boolean;
  preparation: number;
  crossSkill: number;
  persuasion: number;
  peremptoryStrikes: number;
}

export interface LoopBailSummary {
  granted: boolean;
  /** Bail amount in integer cents (era money); 0 when denied. */
  amountCents: number;
  flightRisk: number;
  canAfford: boolean;
  paid: boolean;
  forfeited?: boolean;
  reasons: string[];
}

export interface LoopPleaOfferSummary {
  offered: boolean;
  chargeIds: string[];
  reducedToMisdemeanor: boolean;
  recommendedJailDays: number;
  /** Recommended plea fine in integer cents (era money). */
  recommendedFineCents: number;
  discountRatio: number;
  caseStrengthAtOffer: number;
  reasoning: string;
}

export interface LoopCourtSentence {
  jailDays: number;
  jailHours: number;
  /** Court fine in integer cents (era money). */
  fineAmount: number;
  probationDays: number;
  reputationDelta: number;
  judgeId: string;
  judgeName: string;
  reasoning: string;
}

export interface LoopCourtCase {
  id: string;
  jurisdictionId: string;
  defendantId: string;
  primaryCrimeId: string;
  crimeIds: string[];
  charges: Charge[];
  era: CourtEraProfile;
  filedDay: number;
  filedHour: number;
  courtDateDay: number;
  courtDateHour: number;
  courtPlaceId: string;
  status: LoopCourtStatus;
  custody: LoopCourtCustody;
  bail: LoopBailSummary;
  pleaOffer: LoopPleaOfferSummary;
  lawyer: DefenseLawyer;
  lawyerFeeCents: number;
  lawyerOptions: LoopLawyerOption[];
  attempt: number;
  jurySelection?: JurySelectionResult;
  witnessIds: string[];
  tampering: ActiveTrialTampering[];
  warrantIssued?: boolean;
  warrantDay?: number;
  lastVerdict?: VerdictKind;
  sentence?: LoopCourtSentence;
  facts: string[];
}

export interface LoopWarrantRecord {
  id: string;
  courtCaseId: string;
  crimeId: string;
  jurisdictionId: string;
  reason: 'missed_court_date';
  issuedDay: number;
  issuedHour: number;
  active: boolean;
  summary: string;
}

export interface LoopTrialEventSummary {
  courtCaseId: string;
  jurisdictionId: string;
  crimeIds: string[];
  day: number;
  hour: number;
  kind: 'trial_completed' | 'missed_court_date';
  verdict?: VerdictKind;
  guiltyVotes?: number;
  notGuiltyVotes?: number;
  deliberationRounds?: number;
  retrialDateDay?: number;
  retrialDateHour?: number;
  sentence?: LoopCourtSentence;
  warrant?: LoopWarrantRecord;
  bailForfeitedCents?: number;
  facts: string[];
  summary: string;
}

export interface BailDecisionInput {
  courtCaseId?: string;
  choice: 'pay_bail' | 'stay_in_cell';
  /** Player cash on hand in integer cents (era money). */
  playerCash?: number;
}

export interface BailDecisionResult {
  sim: LoopSim;
  ok: boolean;
  courtCase?: LoopCourtCase;
  bailPaidCents: number;
  inCustody: boolean;
  summary: string;
}

export interface ChooseCounselInput {
  courtCaseId?: string;
  /** Desired counsel tier; if omitted, hires the best tier affordable within `playerCash`. */
  tier?: LawyerTier;
  /** Player cash on hand in integer cents (era money). */
  playerCash?: number;
}

export interface ChooseCounselResult {
  sim: LoopSim;
  ok: boolean;
  courtCase?: LoopCourtCase;
  lawyer?: DefenseLawyer;
  feeCents: number;
  summary: string;
}

export interface PleaInput {
  courtCaseId?: string;
  plea: 'guilty' | 'not_guilty';
  /** Whether a guilty plea immediately serves jail days through `advance()` (default true). */
  serveSentence?: boolean;
}

export interface PleaResult {
  sim: LoopSim;
  ok: boolean;
  courtCase?: LoopCourtCase;
  plea: 'guilty' | 'not_guilty';
  /** Fine in integer cents (era money). */
  fineAmount: number;
  jailDays: number;
  jailHours: number;
  day: number;
  hour: number;
  daysElapsed: number;
  policePlans: OfficerPlanDirective[];
  attitudeChanges: AttitudeChange[];
  newPoliceCases: string[];
  facts: string[];
  summary: string;
}

export interface CourtInterferenceInput {
  courtCaseId?: string;
  kind: TrialInterferenceKind;
  /** Target juror or witness personId (defaults to first seated juror / first witness of the active court case). */
  targetId?: string;
  placeId?: string;
  /** Cash offered for bribery in integer cents (era money). */
  bribeAmount?: number;
  bystanders?: SceneBystander[];
  /** True if perjury is caught in open court. */
  caughtInCourt?: boolean;
}

export interface CourtInterferenceResult {
  sim: LoopSim;
  ok: boolean;
  courtCaseId?: string;
  crimeId?: string;
  crimeKind?: CrimeKind;
  kind: TrialInterferenceKind;
  targetId?: string;
  succeeded: boolean;
  targetReported: boolean;
  policeCaseOpened: boolean;
  witnessIds: string[];
  tampering?: ActiveTrialTampering;
  summary: string;
}

export interface CourtViewResult {
  pending: LoopCourtCase[];
  resolved: LoopCourtCase[];
  activeWarrants: LoopWarrantRecord[];
}

export type SimPersonMap = Record<Id, Person>;
