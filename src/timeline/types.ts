import type { CrimeKind, EvidenceTrace, IdentityFileRecord, PoliceCase, TimeTravelAnomaly } from '../crime';
import type { Id, Intervention as SimIntervention, World, WorldDiff } from '../sim';
import type { CourtEraId } from '../court';

/**
 * Plain-JSON intervention kinds supported by the timeline ledger.
 * Wraps every native `src/sim` intervention (`kill`, `invest`, `sabotage`, `introduce`, `rumor`, `gift`)
 * and adds higher-level player acts and crime-mapped interventions.
 */
export type TimelineInterventionKind =
  | SimIntervention['kind']
  | 'crime'
  | 'arson'
  | 'demolish'
  | 'prevent_building'
  | 'self_erase'
  | 'record_fire'
  | 'record_flood';

/** Optional causal anchor tag linking an intervention to a paradox / loose thread. */
export type CausalAnchorTag =
  | 'professors_lab'
  | 'time_machine'
  | 'professors_letter'
  | 'player_origin'
  | 'player_trip_cause'
  | 'custom';

/**
 * One recorded change a time traveller made to history. Plain JSON.
 */
export interface TimelineIntervention {
  /** Stable unique identifier within the timeline. */
  id: string;
  /** Historical year in which the intervention occurs. */
  year: number;
  /** Place or town identifier (e.g. `'capital'`, `'town:0,0'`). */
  placeId: string;
  /** High-level intervention kind. */
  kind: TimelineInterventionKind;
  /** Who performed it (defaults to `'player'`). */
  actorId?: Id;
  /** Person target for `kill`, `rumor`, `gift`, `self_erase`, or person-targeted `crime`. */
  targetId?: Id;
  /** Second person target for `introduce`. */
  otherTargetId?: Id;
  /** Business target for `invest`, `sabotage`, `arson`, `demolish`, or business-targeted `crime`. */
  businessId?: Id;
  /** Numeric magnitude for `invest`, `rumor` (-1..1), or `gift`. */
  amount?: number;
  /** Imported `CrimeKind` from `src/crime/types.ts` when `kind === 'crime'`. */
  crimeKind?: CrimeKind;
  /** Optional linked crime id in `timeline.crimes`. */
  crimeId?: string;
  /** Optional physical lot slot id for `arson`, `demolish`, or `prevent_building`. */
  slot?: string;
  /** Human-readable building label for `prevent_building` / `demolish` (e.g. `"Professor's lab"`). */
  buildingLabel?: string;
  /** Optional label for what replaced a burned/demolished building (e.g. `'Parking garage'`). */
  replacementLotLabel?: string;
  /** Flags that this intervention disrupts a cause of the player's own trip (produces a loose thread, never refused). */
  causeTag?: CausalAnchorTag;
  /** Plain-English note describing the act for chronicles and journal views. */
  summary?: string;
}

/** Input accepted by `branch()` — `id` is auto-derived deterministically if omitted. */
export type TimelineInterventionInput = Omit<TimelineIntervention, 'id'> & { id?: string };

/**
 * An item carried by the unanchored player (Rule 1).
 * Keeps its full provenance even if its maker or business never existed in the current timeline.
 */
export interface CarriedItem {
  id: string;
  label: string;
  acquiredYear: number;
  acquiredPlaceId: string;
  makerId?: Id;
  makerName?: string;
  businessId?: Id;
  businessName?: string;
  provenanceNote: string;
}

/** A memory carried by the unanchored player across timeline jumps. */
export interface PlayerMemory {
  id: string;
  year: number;
  placeId: string;
  subjectId?: Id;
  summary: string;
}

/** An anchored-world interaction between the player and an NPC in a specific timeline branch. */
export interface PlayerNpcInteraction {
  id: string;
  year: number;
  placeId: string;
  npcId: Id;
  summary: string;
}

/** Anchored identity records the player starts with before `selfErase`. */
export interface PlayerIdentityRecords {
  birthRecord: boolean;
  bankAccount: boolean;
  fingerprintsOnFile: boolean;
  dnaOnFile: boolean;
  legalIdentity: boolean;
  passportEligible: boolean;
  jobReferences: string[];
  knownFamilyIds: Id[];
}

/** Unanchored player state stored on the timeline ledger (Rules 1-3). */
export interface PlayerAnchorState {
  playerId: Id;
  /** Optional link to the player's anchored self inside `world.people`. */
  anchoredPersonId?: Id;
  originYear: number;
  birthYear: number;
  homePlaceId: string;
  selfErased: boolean;
  selfErasedYear?: number;
  records: PlayerIdentityRecords;
  inventory: CarriedItem[];
  memories: PlayerMemory[];
  /** Interactions that occurred in THIS timeline branch (anchored NPCs remember only these). */
  interactions: PlayerNpcInteraction[];
}

/** Witness account stored on a timeline crime record. */
export interface TimelineWitnessRecord {
  personId: Id;
  name?: string;
  confidence: number;
  identifiedCulprit?: boolean;
  testimonySummary?: string;
}

/**
 * Crime kind recorded on the timeline: any `CrimeKind` from `src/crime/types.ts` plus `'arson'`.
 */
export type TimelineCrimeKind = CrimeKind | 'arson';

/**
 * A crime committed in a specific year and place whose evidence ages forward across eras.
 * Plain JSON; integrates with `src/crime`, `src/police`, and `src/court`.
 */
export interface TimelineCrimeRecord {
  id: string;
  kind: TimelineCrimeKind;
  year: number;
  /** Simulated day index within the year/era (defaults to `(year - 1950) * 365`). */
  day: number;
  placeId: string;
  culpritId: Id;
  victimId?: Id;
  businessId?: Id;
  severity?: number;
  reported: boolean;
  witnesses: TimelineWitnessRecord[];
  /** Optional physical/digital traces from `src/crime`. */
  traces: EvidenceTrace[];
  /** Optional full `PoliceCase` from `src/crime` for direct heat/strength integration. */
  policeCase?: PoliceCase;
  summary?: string;
}

/** Invented continent ids for the planet (no real-world place names). */
export type ContinentId =
  | 'Meridia'
  | 'Vespera'
  | 'Boreal Reach'
  | 'Solis Basin'
  | 'Aurelia Shelf';

export type TravellerTraceKind =
  | 'letter'
  | 'cache'
  | 'photograph'
  | 'early_patent'
  | 'newspaper_oddity'
  | 'untraced_lodger'
  | 'local_memory'
  | 'anachronism';

/**
 * One mark left deliberately (or via anachronism) by an unanchored traveller.
 * Stored outside the sim so timeline changes never remove it.
 */
export interface TravellerTrace {
  id: string;
  travellerId: string;
  sequenceIndex: number;
  year: number;
  eraLabel: string;
  continent: ContinentId;
  placeId: string;
  placeName: string;
  kind: TravellerTraceKind;
  summary: string;
  /** Loosely points toward the next trace in the trail; undefined on the final trace. */
  clueToNext?: string;
  nextHint?: {
    continent: ContinentId;
    decadeHint: string;
    themeHint: string;
  };
}

/** An unanchored person living outside the simulation (e.g. the professor). */
export interface UnanchoredTraveller {
  id: string;
  name: string;
  role: 'professor' | 'traveller';
  unanchoredSinceYear?: number;
  traces: TravellerTrace[];
}

/**
 * The plain-JSON Timeline ledger saved by the game.
 */
export interface Timeline {
  version: 1;
  seed: string;
  startYear: number;
  /** Default placeId for this town's simulation (defaults to `'capital'`). */
  placeId: string;
  /** Ordered list of interventions made on this branch. */
  interventions: TimelineIntervention[];
  /** Unanchored player state, inventory, memories, and identity records. */
  player: PlayerAnchorState;
  /** Crimes recorded on this branch, keyed by `crimeId`. */
  crimes: Record<string, TimelineCrimeRecord>;
  /** Optional `src/crime` identity file records on file for forensic checks. */
  identityFiles: Record<Id, IdentityFileRecord[]>;
  /** Unanchored travellers (professor and others) stored outside the sim. */
  travellers: UnanchoredTraveller[];
}

export interface PersonChangeEntry {
  id: Id;
  name: string;
  birthYear: number;
  deathYear?: number;
  deathCause?: 'natural' | 'murdered';
}

export interface BusinessChangeEntry {
  id: Id;
  name: string;
  kind: string;
  foundedYear: number;
  closedYear?: number;
  ownerId?: Id;
  ownerName?: string;
  reason?: string;
}

export interface OwnerChangeEntry {
  businessId: Id;
  businessName: string;
  beforeOwnerId?: Id;
  beforeOwnerName: string;
  afterOwnerId?: Id;
  afterOwnerName: string;
}

export interface BuildingNeverBuiltEntry {
  slot: string;
  label: string;
  kind: 'house' | 'shop' | 'landmark';
  originalBuiltYear?: number;
  cause?: string;
}

export interface BuildingDemolishedEntry {
  slot: string;
  beforeLabel: string;
  afterLabel: string;
  kind: 'house' | 'shop' | 'landmark';
  cause: string;
  year?: number;
  businessId?: Id;
}

export interface BuildingNewEntry {
  slot: string;
  label: string;
  kind: 'house' | 'shop' | 'landmark';
  builtYear?: number;
}

/**
 * Structured report comparing the altered present against the untouched seed baseline.
 * Built directly on `diffWorlds` from `src/sim/diff.ts`.
 */
export interface TimelineChangesReport {
  year: number;
  hasChanges: boolean;
  population: { before: number; after: number };
  /** People who existed in the baseline timeline but never existed in this one. */
  erasedPeople: PersonChangeEntry[];
  /** People who died earlier than in the baseline (e.g. murdered by an intervention). */
  killedEarly: PersonChangeEntry[];
  /** People who exist only in this altered timeline. */
  newPeople: PersonChangeEntry[];
  /** Businesses that existed in the baseline but never opened in this timeline. */
  businessesNeverOpened: BusinessChangeEntry[];
  /** Businesses that closed in the baseline but stayed open in this timeline. */
  businessesNeverClosed: BusinessChangeEntry[];
  /** Businesses that stayed open in the baseline but closed (or burned down) in this timeline. */
  businessesClosedEarly: BusinessChangeEntry[];
  /** Businesses founded only in this altered timeline. */
  businessesNew: BusinessChangeEntry[];
  /** Open businesses whose owner differs from the baseline. */
  ownerChanges: OwnerChangeEntry[];
  mayorChange: {
    changed: boolean;
    beforeId?: Id;
    beforeName: string;
    afterId?: Id;
    afterName: string;
  };
  richestChange: {
    changed: boolean;
    beforeId?: Id;
    beforeName: string;
    afterId?: Id;
    afterName: string;
  };
  buildings: {
    neverBuilt: BuildingNeverBuiltEntry[];
    demolishedOrReplaced: BuildingDemolishedEntry[];
    newlyBuilt: BuildingNewEntry[];
  };
  /** Underlying `WorldDiff` produced by `src/sim/diff.ts`. */
  worldDiff: WorldDiff;
  /** Plain-English summary lines for UI, journal, and narration. */
  summary: string[];
}

/** Result returned by `presentAt(timeline, year)`. Plain JSON. */
export interface PresentSnapshot {
  seed: string;
  year: number;
  placeId: string;
  /** The replayed simulation world at `year`. */
  world: World;
  /** Diff and structured changes report relative to the untouched baseline at `year`. */
  changes: TimelineChangesReport;
  /** Whether this call was served from the replay cache without re-running `src/sim`. */
  fromCache: boolean;
}

/** Inspection result for one carried item against a timeline's current present. */
export interface CarriedItemStatus {
  item: CarriedItem;
  /** Always `true` — Rule 1 says unanchored carried items survive every timeline edit. */
  survives: true;
  makerExists: boolean;
  businessExists: boolean;
  anachronisticProvenance: boolean;
  note: string;
}

/** What an anchored NPC remembers about the player in the current timeline (Rule 2). */
export interface NpcMemoryView {
  npcId: Id;
  npcName?: string;
  year: number;
  existsInTimeline: boolean;
  aliveInYear: boolean;
  remembersPlayer: boolean;
  knowsAsFamily: boolean;
  interactions: PlayerNpcInteraction[];
  summary: string;
}

export interface SelfEraseEffect {
  code: string;
  label: string;
  detail: string;
}

/** Report returned by `selfErase(timeline, year)` (Rule 3). */
export interface SelfEraseReport {
  year: number;
  placeId: string;
  playerId: Id;
  anchoredPersonId?: Id;
  alreadyErased: boolean;
  erasedRecords: string[];
  /** What the player loses by wiping their anchored self. */
  losses: SelfEraseEffect[];
  /** What the player gains by wiping their anchored self. */
  gains: SelfEraseEffect[];
  summary: string[];
}

export type CaseStatusState =
  | 'not_found'
  | 'not_yet_committed'
  | 'never_happened'
  | 'no_evidence'
  | 'records_lost'
  | 'expired'
  | 'cold'
  | 'active';

/**
 * Plain-JSON answer from `caseStatusAt(timeline, crimeId, year)`.
 */
export interface CaseStatusReport {
  crimeId: string;
  crimeKind?: TimelineCrimeKind;
  crimeYear?: number;
  queryYear: number;
  eraId: CourtEraId;
  status: CaseStatusState;
  /** True when police in `queryYear` are still actively looking for the player for this crime. */
  activelyLooking: boolean;
  crimeOccurred: boolean;
  isColdCase: boolean;
  statuteExpired: boolean;
  statuteLimitYears: number | null;
  ageYears: number;
  recordsIntact: boolean;
  recordsLostCause?: 'fire' | 'flood';
  recordsLostYear?: number;
  hasTestimony: boolean;
  activeWitnesses: Id[];
  erasedWitnesses: Id[];
  deceasedWitnesses: Id[];
  hasPhysicalEvidence: boolean;
  survivingTraces: number;
  playerIdentifiableOnFile: boolean;
  canReopenWithModernForensics: boolean;
  forensicAnomalies: TimeTravelAnomaly[];
  /** Plain-English reasons explaining why the police are or aren't looking for the player. */
  reasons: string[];
}

export type LooseThreadKind =
  | 'cause_removed'
  | 'self_erased'
  | 'orphan_item'
  | 'erased_crime_memory'
  | 'lineage_paradox'
  | 'chronology_anomaly';

/**
 * A flagged paradox ("loose thread") — never refused, surfaced as plain facts for dialogue & journal.
 */
export interface LooseThread {
  id: string;
  kind: LooseThreadKind;
  year: number;
  placeId?: string;
  /** Always `true`: unanchored travellers are never blocked by paradoxes. */
  allowed: true;
  fact: string;
  journalLine: string;
  dialogueHook: string;
  relatedIds: string[];
}

export interface TracesAtOptions {
  /** If true, return only traces left in the exact `year`. Default `false` (returns traces left on or before `year`). */
  exactYearOnly?: boolean;
  /** Optional filter by traveller id (e.g. `'professor'`). */
  travellerId?: string;
}

export interface TimelineCacheStats {
  simReplays: number;
  simCacheHits: number;
  presentCacheHits: number;
  presentCacheMisses: number;
  simCacheEntries: number;
  presentCacheEntries: number;
}
