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
import type { Id, Person, World } from '../sim/types';

export type LoopPlaceKind =
  | 'general_store'
  | 'market'
  | 'pawn_shop'
  | 'police_station'
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
}

export interface PriorOffenseRecord {
  id: string;
  crimeId: string;
  kind: CrimeKind;
  severity: number;
  jurisdictionId: string;
  day: number;
  penalty: 'warning' | 'fine' | 'cell_night' | 'jail';
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
      subkind?: 'shoplift' | 'yard' | 'house' | 'pickpocket';
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
  penalty: 'warning' | 'fine' | 'cell_night' | 'jail';
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
  policePlans: OfficerPlanDirective[];
  attitudeChanges: AttitudeChange[];
  newPoliceCases: string[];
  summary: string;
}

export type SimPersonMap = Record<Id, Person>;
