import type { CrimeState, CulpritAppearance, PoliceCase } from '../crime/types';
import type { PlanState, PlanWorld, Vec2 } from '../plan/types';
import type { Id, World } from '../sim/types';

/** Street-graph node provided by the caller (ids must be lowercase `[a-z][a-z0-9_]*` for plan binding). */
export interface StreetNode {
  id: string;
  position: Vec2;
  kind: 'street' | 'intersection' | 'station' | 'building' | 'border';
  jurisdictionId: string;
  name?: string;
  /** Local night lighting contribution in 0..1 (e.g., bright main street vs dark alley). */
  streetLight?: number;
  /** If `kind === 'border'`, optional neighboring jurisdiction across the line. */
  leadsToJurisdictionId?: string;
}

/** Undirected edge connecting two street nodes. */
export interface StreetEdge {
  from: string;
  to: string;
  /** Optional explicit edge length in meters (defaults to Euclidean distance). */
  distance?: number;
}

/** Caller-supplied street network over one or more jurisdictions. */
export interface StreetGraph {
  nodes: Record<string, StreetNode>;
  edges: StreetEdge[];
}

/** Single tunable table of historical policing milestones. */
export interface PoliceEraTable {
  /** Year motorized patrol cars enter local service (foot/horse before). */
  patrolCarYear: number;
  /** Year patrol cars carry two-way / dispatch radio (instant backup calls). */
  radioYear: number;
  /** Year foot officers carry portable handheld radios. */
  handheldRadioYear: number;
  /** First year routine ID / papers / licence checks become common. */
  papersCheckStartYear: number;
  /** Minimum town population to staff a dedicated detective. */
  detectiveMinPopulation: number;
  /** Baseline officers per 1,000 residents in early era (< 1930). */
  earlyOfficersPerThousand: number;
  /** Officers per 1,000 residents in mid era (1930–1959). */
  midOfficersPerThousand: number;
  /** Officers per 1,000 residents in modern era (1960+). */
  modernOfficersPerThousand: number;
}

export type OfficerRole = 'constable' | 'patrol' | 'detective';
export type OfficerShift = 'day' | 'swing' | 'night';
export type OfficerTransport = 'foot' | 'car';

export type OfficerAssignment =
  | 'off_duty'
  | 'patrol'
  | 'respond_crime'
  | 'search_suspect'
  | 'stakeout'
  | 'question'
  | 'call_backup'
  | 'chase'
  | 'arrest'
  | 'transport'
  | 'backup';

/** One officer in a jurisdiction's police force. Plain JSON. */
export interface Officer {
  /** Plan-safe actor id (`[a-z][a-z0-9_]*`). */
  id: string;
  /** Corresponding person id in `World.people` (may equal `id`). */
  personId: Id;
  jurisdictionId: string;
  role: OfficerRole;
  shift: OfficerShift;
  transport: OfficerTransport;
  /** Assigned patrol vehicle id (when `transport === 'car'`). */
  vehicleId?: string;
  /** Handcuff item id carried by the officer. */
  cuffsItemId: string;
  /** Whether this officer has live radio dispatch/backup capability in this era. */
  hasRadio: boolean;
  /** Home police station node id in the street graph. */
  stationNodeId: string;
  /** Ordered street-graph node ids forming this officer's patrol beat. */
  beatNodeIds: string[];
  /** Current high-level duty assignment. */
  assignment: OfficerAssignment;
  /** Active crime case id if responding, searching, staking out, or pursuing. */
  targetCrimeId?: string;
  /** Active suspect or lookalike person id if questioning, chasing, or arresting. */
  targetPersonId?: Id;
  /** Active target place/node id (crime scene, haunt, or chase intercept node). */
  targetNodeId?: string;
  /** Witness person ids scheduled to be interviewed for the current crime response. */
  pendingWitnessIds?: Id[];
  /** Witness ids already interviewed by this officer for the current case. */
  interviewedWitnessIds?: Id[];
  /** Whether the current stop/question target is an innocent lookalike. */
  targetIsLookalike?: boolean;
  /** Whether backup was already requested for the active sighting/chase. */
  backupRequested?: boolean;
  /** Current G007 plan string generated for this assignment. */
  planSource?: string;
  /** Current G007 `PlanState` stepped through `PlanWorld`. */
  planState?: PlanState;
  /** Simulated second when the officer last ran a spotting sweep. */
  lastSpotCheckSec: number;
  /** Per-person cooldown (personId -> untilSec) so cleared citizens/lookalikes aren't immediately re-stopped. */
  clearedUntilSec: Record<Id, number>;
}

/** One town/jurisdiction definition passed to `createPoliceForce`. */
export interface TownPoliceSpec {
  jurisdictionId: string;
  population: number;
  stationNodeId: string;
  /** Optional explicit headcount override (otherwise derived from `population` and `year`). */
  officerCountOverride?: number;
}

/** Visible citizen snapshot supplied to police spotting each tick. */
export interface ObservedPerson {
  personId: Id;
  position: Vec2;
  nodeId: string;
  jurisdictionId: string;
  appearance: CulpritAppearance;
  /** True if currently fleeing / running from police. */
  fleeing?: boolean;
  /** True if already cuffed/in custody. */
  inCustody?: boolean;
}

export type SpotAction = 'ignore' | 'question' | 'ask_papers' | 'call_backup' | 'pursue';

export interface SpotEvent {
  timeSec: number;
  day: number;
  officerId: string;
  jurisdictionId: string;
  seenPersonId: Id;
  matchedCaseId: string;
  recognitionChance: number;
  rolled: number;
  threshold: number;
  action: SpotAction;
  isLookalike: boolean;
  distance: number;
  light: number;
}

export type StopReason =
  | 'lookalike_cleared'
  | 'papers_checked'
  | 'questioned_released'
  | 'fled'
  | 'arrested';

export interface StopOutcome {
  id: string;
  day: number;
  timeSec: number;
  officerId: string;
  jurisdictionId: string;
  personId: Id;
  caseId: string;
  reason: StopReason;
  isLookalike: boolean;
  papersRequested: boolean;
  contrabandFound: string[];
}

export interface InterviewRecord {
  id: string;
  crimeId: string;
  officerId: string;
  witnessId: Id;
  day: number;
  timeSec: number;
  memoryRefreshed: boolean;
}

export type ArrestMethod =
  | 'surrender'
  | 'foot_pursuit'
  | 'vehicle_pursuit'
  | 'cornered'
  | 'stakeout';

/** Structured arrest outcome designed for downstream jail & G010 trial systems. Plain JSON. */
export interface ArrestRecord {
  id: string;
  crimeId: string;
  suspectId: Id;
  arrestingOfficerId: string;
  assistingOfficerIds: string[];
  jurisdictionId: string;
  stationNodeId: string;
  arrestDay: number;
  arrestYear: number;
  arrestTimeSec: number;
  arrestPosition: Vec2;
  method: ArrestMethod;
  /** Full inventory found when the officer searched the suspect. */
  searchedInventory: string[];
  /** Subset of `searchedInventory` flagged as contraband or stolen evidence. */
  seizedContraband: string[];
  cuffed: boolean;
  transportedToStation: boolean;
  /** Explainable prosecution strength (`caseStrength`) snapshot at arrest time, if available. */
  caseStrengthAtArrest?: number;
}

export interface EscapeRecord {
  id: string;
  crimeId: string;
  suspectId: Id;
  pursuingOfficerId: string;
  fromJurisdictionId: string;
  toJurisdictionId: string;
  day: number;
  timeSec: number;
  /** Populated only if the case escalated across jurisdictions (G005 escalation). */
  escalatedToJurisdictionId?: string;
}

/** Pending radio / station backup alert in a jurisdiction. */
export interface BackupAlert {
  id: string;
  crimeId: string;
  suspectId: Id;
  jurisdictionId: string;
  nodeId: string;
  position: Vec2;
  requestedByOfficerId: string;
  requestedAtSec: number;
  /** Earliest second backup officers receive the call (instant with radio, delayed pre-1930). */
  activeAtSec: number;
  expiresAtSec: number;
}

/** Root plain-JSON police simulation state (safe for `JSON.stringify` / `JSON.parse`). */
export interface PoliceState {
  seed: string;
  year: number;
  officers: Record<string, Officer>;
  /** Crime ids that already have or completed an initial officer response (`jurId:crimeId` -> officerId). */
  respondedCases: Record<string, string>;
  /** Active backup alerts per jurisdiction. */
  backupAlerts: BackupAlert[];
  /** Accumulated historical logs (outcomes for jail, trial, and Gazette systems). */
  interviews: InterviewRecord[];
  stops: StopOutcome[];
  arrests: ArrestRecord[];
  escapes: EscapeRecord[];
  /** Monotonic sequence counter for deterministic record ids. */
  nextSeq: number;
}

/** Caller-provided runtime context for one `stepPoliceTick` call. */
export interface PoliceTickContext {
  world: World;
  planWorld: PlanWorld;
  crimeState: CrimeState;
  graph: StreetGraph;
  day: number;
  year: number;
  /** Local hour of day in [0, 24). */
  hour: number;
  /** Tick delta in seconds (typically 0.5–5.0 s). */
  dt: number;
  /**
   * Either provide a spatial query `peopleNearby(pos, radius, officerId)` OR a flat `people` array
   * (automatically indexed on a fast uniform spatial grid once per tick).
   */
  peopleNearby?: (pos: Vec2, radiusM: number, officerId: string) => readonly ObservedPerson[];
  people?: readonly ObservedPerson[];
  /** Optional fast lookup for a single person's current observed state (used during stops & chases). */
  getPerson?: (personId: Id) => ObservedPerson | undefined;
  /** Optional caller-supplied inventory lookup for arrest/stop searches. */
  getPersonInventory?: (personId: Id) => readonly string[];
  /** Optional predicate identifying stolen/illegal items during a search. */
  isContraband?: (itemId: string, crimeId: string, policeCase?: PoliceCase) => boolean;
  /** Optional override for whether a stopped suspect chooses to flee. */
  willSuspectFlee?: (personId: Id, crimeId: string, officerId: string) => boolean;
  /** Known home/haunt street-node ids for a wanted suspect (used for search & stakeout plans). */
  knownHauntsOf?: (personId: Id) => readonly string[];
  /** Last known street-node id where a crime or suspect was seen. */
  lastSeenNodeOf?: (crimeId: string, suspectId?: Id) => string | undefined;
  /** Optional escalation hook mirroring G005 (`escalateJurisdictionOf(fromJur, crimeId)`). */
  escalateJurisdictionOf?: (jurisdictionId: string, crimeId: string) => string | undefined;
  /** Optional explicit ambient light override in 0..1 (otherwise derived from `hour`). */
  ambientLight?: number;
  /** Optional custom era table (defaults to `POLICE_ERA`). */
  policeEra?: Readonly<PoliceEraTable>;
  /** Optional spotting sweep interval in seconds (default 2.0 s). */
  spotIntervalSec?: number;
  /** Optional override to keep all officers on duty regardless of shift (useful in focused tests). */
  ignoreShifts?: boolean;
  /** Optional callback when an arrest cuffs/transports a suspect (e.g. to move suspect with officer in `PlanWorld`). */
  onSuspectCustody?: (suspectId: Id, officerId: string, phase: 'cuffed' | 'transporting' | 'booked') => void;
}

export interface PoliceTickEvents {
  spots: SpotEvent[];
  stops: StopOutcome[];
  interviews: InterviewRecord[];
  arrests: ArrestRecord[];
  escapes: EscapeRecord[];
}

export interface PoliceTickResult {
  state: PoliceState;
  crimeState: CrimeState;
  events: PoliceTickEvents;
}
