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

export interface LoopInit {
  seed: string;
  year: number;
  startDay?: number;
  startHour?: number;
  /** Optional day-of-week index for `startDay` (`0=Monday .. 6=Sunday`, default `0`). */
  startDayOfWeek?: number;
  playerId?: string;
  playerName?: string;
  playerAppearance?: CulpritAppearance;
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
  siblingIds: string[];
}

export interface LoopOfficer {
  id: string;
  planId: string;
  jurisdictionId: string;
  role: 'constable' | 'patrol' | 'detective';
  shift: 'day' | 'swing' | 'night';
  transport: 'foot' | 'car';
  vehiclePlanId?: string;
  hasRadio: boolean;
  stationNodeId: string;
  beatNodeIds: string[];
  assignment: 'off_duty' | 'patrol' | 'respond_crime' | 'search_suspect' | 'stakeout';
  targetCrimeId?: string;
  targetPlaceId?: string;
  targetPersonId?: string;
  planSource?: string;
  routePlaceIds: string[];
}

export interface StolenItemRecord {
  itemId: string;
  label: string;
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
  playerAppearance: CulpritAppearance;
  world: World;
  crimeState: CrimeState;
  idMap: IdMapState;
  places: Record<string, LoopPlaceRecord>;
  peopleMeta: Record<string, LoopPersonMeta>;
  streetGraph: LoopStreetGraph;
  streetLamps: StreetLamp[];
  escalateJurisdiction: Record<string, string>;
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
  crimeId?: string;
  payEarned?: number;
  stolenSerialNumber?: string;
  witnessedBy: WitnessNotice[];
  tracesLeft: ActTraceSummary[];
  pawnTraceFlagged?: boolean;
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
  /** Short, plain-English facts ready for the dialogue model's fact sheet. */
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

export interface SpotResult {
  officerId: string;
  jurisdictionId: string;
  targetId: string;
  action: LoopSpotAction;
  recognitionChance: number;
  matchedCaseId?: string;
  reason: string;
  /** Optional bound G007 plan for the officer to act out when `action !== 'ignore'`. */
  planSource?: string;
}

export interface ArrestInput {
  jurisdictionId: string;
  officerId?: string;
  crimeIds?: string[];
  playerCash?: number;
  carriedItemIds?: string[];
  surrendered?: boolean;
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
  fineAmount: number;
  jailHours: number;
  jailNights: number;
  charges: ArrestChargeSummary[];
  priorOffenses: number;
  totalCaseStrength: number;
  confiscatedItems: string[];
  coldCasesTriggered: string[];
  summary: string;
}

export type SimPersonMap = Record<Id, Person>;
