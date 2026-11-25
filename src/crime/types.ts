import type { Id } from '../sim/types';
import type { ForensicEraTable, TimeTravelAnomaly } from './forensics';

/** 2D world coordinate in meters. */
export interface Vec2 {
  x: number;
  y: number;
}

/** Supported crime categories ordered roughly from petty to capital. */
export type CrimeKind =
  | 'vandalism'
  | 'shoplifting'
  | 'theft'
  | 'perjury'
  | 'car_theft'
  | 'bribery'
  | 'burglary'
  | 'intimidation'
  | 'assault'
  | 'robbery'
  | 'murder';

/** Default severity (0..1) per crime kind when caller does not override. */
export const DEFAULT_SEVERITY: Readonly<Record<CrimeKind, number>> = {
  vandalism: 0.22,
  shoplifting: 0.28,
  theft: 0.44,
  perjury: 0.56,
  car_theft: 0.58,
  bribery: 0.64,
  burglary: 0.64,
  intimidation: 0.70,
  assault: 0.72,
  robbery: 0.80,
  murder: 1.0,
};

/** How conspicuous / loud the act is for catching nearby attention (0..1). */
export const CRIME_CONSPICUOUSNESS: Readonly<Record<CrimeKind, number>> = {
  vandalism: 0.65,
  shoplifting: 0.40,
  theft: 0.48,
  perjury: 0.38,
  car_theft: 0.72,
  bribery: 0.30,
  burglary: 0.58,
  intimidation: 0.56,
  assault: 0.88,
  robbery: 0.90,
  murder: 0.94,
};

export type HeightBand = 'short' | 'average' | 'tall';
export type BuildBand = 'slim' | 'average' | 'heavy';
export type HairTone = 'dark' | 'brown' | 'blonde' | 'red' | 'gray' | 'bald';
export type ClothingColor = 'black' | 'navy' | 'gray' | 'brown' | 'tan' | 'white' | 'red' | 'green' | 'blue';
export type VehicleKind = 'sedan' | 'coupe' | 'pickup' | 'wagon' | 'motorcycle' | 'bicycle';

export const HEIGHT_BANDS: readonly HeightBand[] = ['short', 'average', 'tall'];
export const BUILD_BANDS: readonly BuildBand[] = ['slim', 'average', 'heavy'];
export const HAIR_TONES: readonly HairTone[] = ['dark', 'brown', 'blonde', 'red', 'gray', 'bald'];
export const CLOTHING_COLORS: readonly ClothingColor[] = [
  'black', 'navy', 'gray', 'brown', 'tan', 'white', 'red', 'green', 'blue',
];
export const VEHICLE_KINDS: readonly VehicleKind[] = [
  'sedan', 'coupe', 'pickup', 'wagon', 'motorcycle', 'bicycle',
];

export interface VehicleDescription {
  kind: VehicleKind;
  color: ClothingColor;
}

/** Ground-truth appearance of a culprit at the moment of a crime (or later when spotted). */
export interface CulpritAppearance {
  height: HeightBand;
  build: BuildBand;
  hair: HairTone;
  clothingColor: ClothingColor;
  vehicle?: VehicleDescription;
}

/** Partial, possibly mistaken description remembered by a witness, gossip, or police file. */
export interface CulpritDescription {
  height?: HeightBand;
  build?: BuildBand;
  hair?: HairTone;
  clothingColor?: ClothingColor;
  vehicle?: VehicleDescription;
}

/** Least-striking details shed first as memory fades. */
export const MEMORY_DROP_ORDER: readonly (keyof CulpritDescription)[] = [
  'clothingColor',
  'build',
  'vehicle',
  'hair',
  'height',
];

export interface CrimeTime {
  year: number;
  /** Monotonic simulated day index. */
  day: number;
  /** Hour of day in [0, 24). */
  hour: number;
}

export interface StolenOrDamaged {
  item: string;
  value?: number;
}

export interface CrimeEvent {
  id: string;
  kind: CrimeKind;
  /** Severity in 0..1 (defaults to `DEFAULT_SEVERITY[kind]`). */
  severity: number;
  position: Vec2;
  /** Semantic place identifier passed to `jurisdictionOf(placeId)` (e.g. `town:0:main_st`). */
  placeId: string;
  /** Optional town id for the crime scene (fallback if `placeTownOf` is omitted). */
  townId?: string;
  when: CrimeTime;
  culpritId: Id;
  culpritAppearance: CulpritAppearance;
  victimId?: Id;
  /** Whether the victim remained conscious during the crime (defaults true except murder). */
  victimConscious?: boolean;
  stolenOrDamaged?: StolenOrDamaged;
}

export interface StreetLamp {
  position: Vec2;
  /** Illuminated radius in meters (default 18 m). */
  radius?: number;
  /** Peak light contribution in 0..1 (default 0.72). */
  intensity?: number;
}

export type SightingSource = 'first_hand' | 'second_hand';

/** Output of `witnessesOf`: one first-hand sighting per witness. */
export interface Sighting {
  crimeId: string;
  witnessId: Id;
  isVictim: boolean;
  /** Current recall confidence in 0..1. */
  confidence: number;
  /** Initial confidence when witnessed (ceiling for refreshes). */
  initialConfidence: number;
  dayWitnessed: number;
  lastRefreshedDay: number;
  /** Set when the witness personally knows the culprit and saw them clearly enough. */
  identifiedCulpritId?: Id;
  description: CulpritDescription;
  refreshes: number;
}

/** Ledger entry tracking who knows about a crime, how they learned it, and what they remember. */
export interface CrimeAccount {
  crimeId: string;
  knowerId: Id;
  source: SightingSource;
  isVictim: boolean;
  /** 0 for first-hand witnesses, 1 for someone told by a witness, etc. */
  hops: number;
  heardFrom?: Id;
  dayLearned: number;
  lastRefreshedDay: number;
  confidence: number;
  initialConfidence: number;
  identifiedCulpritId?: Id;
  description: CulpritDescription;
  reported: boolean;
  reportQueuedDay?: number;
  reportedJurisdiction?: string;
  refreshes: number;
}

/** A report filed by a person and currently in transit to a police jurisdiction (era latency). */
export interface PendingReport {
  id: string;
  crimeId: string;
  reporterId: Id;
  jurisdictionId: string;
  filedDay: number;
  arrivalDay: number;
  delayHours: number;
  source: SightingSource;
  isVictim: boolean;
  confidence: number;
  identifiedCulpritId?: Id;
  description: CulpritDescription;
}

/** A report received and logged by a police jurisdiction. */
export interface PoliceReport {
  id: string;
  crimeId: string;
  reporterId: Id;
  jurisdictionId: string;
  filedDay: number;
  receivedDay: number;
  delayHours: number;
  source: SightingSource;
  isVictim: boolean;
  confidence: number;
  identifiedCulpritId?: Id;
  description: CulpritDescription;
}

export interface MergedDescription {
  description: CulpritDescription;
  fieldConfidence: Partial<Record<keyof CulpritDescription, number>>;
  overallConfidence: number;
  namedSuspectId?: Id;
  namedSuspectConfidence: number;
}

export interface PoliceCase {
  crimeId: string;
  kind: CrimeKind;
  severity: number;
  placeId: string;
  crimeDay: number;
  firstReportedDay: number;
  lastReportedDay: number;
  reportCount: number;
  firstHandCount: number;
  reports: PoliceReport[];
  merged: MergedDescription;
  /** Ground-truth culprit id linked to the underlying incident for matching wanted status. */
  actualCulpritId: Id;
  /** Physical/digital traces collected into this case file (optional; plain JSON). */
  traces?: EvidenceTrace[];
  /** Era-gated forensic matches linking collected traces to persons on file. */
  evidenceMatches?: EvidenceMatch[];
  /** Time-travel anomalies flagged on this case (e.g. prints before birth). */
  timeAnomalies?: TimeTravelAnomaly[];
}

export interface JurisdictionState {
  jurisdictionId: string;
  cases: Record<string, PoliceCase>;
}

/**
 * Plain-JSON serializable state of the entire crime, memory, gossip and police simulation.
 */
export interface CrimeState {
  seed: string;
  currentDay: number;
  crimes: Record<string, CrimeEvent>;
  /** `ledger[crimeId][personId]` = what that person knows and how they learned it. */
  ledger: Record<string, Record<Id, CrimeAccount>>;
  /** Reports filed but still walking/in-transit (e.g. pre-1900 era). */
  pendingReports: PendingReport[];
  /** Police files isolated per jurisdiction id. */
  jurisdictions: Record<string, JurisdictionState>;
  /** `toldPairs[crimeId][tellerId]` = listener ids already told by `tellerId`. */
  toldPairs: Record<string, Record<Id, Id[]>>;
  /** Physical/digital traces left at crime scenes, keyed by `crimeId` (plain JSON). */
  traces?: Record<string, EvidenceTrace[]>;
  /** People on file (arrests, licences, registrations, databases), keyed by `personId`. */
  identityFiles?: Record<Id, IdentityFileRecord[]>;
}

/** Caller-provided world/geography hooks for `advanceDay`. */
export interface CrimeContext {
  /** Override simulation year (defaults to `world.year`). */
  year?: number;
  /** Maps a place or town id to its police jurisdiction id (town police, sheriff, state). */
  jurisdictionOf?: (placeId: string) => string;
  /** Maps a person id to their home town id (defaults to a single town). */
  townOf?: (personId: Id) => string;
  /** Maps a crime `placeId` to a town id (defaults to `crime.townId ?? placeId`). */
  placeTownOf?: (placeId: string) => string;
  /** Maps a person id to their workplace id so coworkers share gossip. */
  workplaceOf?: (personId: Id) => string | undefined;
  /** Returns town ids visited by `personId` on `day` (travelers carry gossip & reports across towns). */
  travelTownsOf?: (personId: Id, day: number) => readonly string[];
  /** Optional distance in km to the nearest police station (affects pre-telephone walk latency). */
  stationDistanceKm?: (placeId: string) => number;
  /** Optional upward jurisdiction escalation hook (e.g. town -> county sheriff -> state police). */
  escalateJurisdictionOf?: (jurisdictionId: string, crime: CrimeEvent) => string | undefined;
  /** Optional daily weather at a place (affects outdoor trace decay). */
  weatherOf?: (placeId: string, day: number) => WeatherKind;
  /** Optional custom forensic era milestones table (defaults to `FORENSIC_ERA`). */
  forensicEra?: Readonly<ForensicEraTable>;
  /** Optional time-traveller arrival year per person id (for pre-arrival anomaly detection). */
  eraArrivalYearOf?: (personId: Id) => number | undefined;
  /** Optional birth year override per person id (fallback when person is not in `world.people`, e.g. unanchored player). */
  birthYearOf?: (personId: Id) => number | undefined;
}

export type TraceKind =
  | 'fingerprint'
  | 'footprint'
  | 'dna'
  | 'dropped_item'
  | 'stolen_serial'
  | 'vehicle_plate'
  | 'camera_footage';

export type TraceExposure = 'indoor' | 'outdoor';

export type WeatherKind = 'clear' | 'rain' | 'storm' | 'snow' | 'heat';

export type DestroyMethod = 'clean' | 'burn' | 'wash' | 'wipe' | 'overwrite' | 'dispose';

export interface SecurityCamera {
  id: string;
  position: Vec2;
  /** Coverage radius in meters (default 28 m). */
  radius?: number;
  /** Optional explicit colour override; otherwise derived from era (`FORENSIC_ERA.cameraColorYear`). */
  color?: boolean;
  /** Optional indoor/outdoor placement. */
  exposure?: TraceExposure;
}

/** One physical or digital trace left by a crime event. Plain JSON. */
export interface EvidenceTrace {
  id: string;
  crimeId: string;
  kind: TraceKind;
  placeId: string;
  position: Vec2;
  createdYear: number;
  createdDay: number;
  /** Current trace quality in 0..1; decays with time & weather until collected or destroyed. */
  quality: number;
  initialQuality: number;
  exposure: TraceExposure;
  /** True once police collected the trace into an evidence locker (stops weather decay). */
  collected: boolean;
  collectedDay?: number;
  collectedYear?: number;
  collectedJurisdiction?: string;
  destroyed: boolean;
  destroyedBy?: Id;
  destroyedMethod?: DestroyMethod;
  destroyedDay?: number;
  /** Ground-truth person who left the trace (never exposed to police until matched against files). */
  sourcePersonId?: Id;
  /** Optional shoe/tread class for footprints. */
  shoeProfile?: string;
  /** Optional item label for `dropped_item` or `stolen_serial`. */
  itemLabel?: string;
  /** Traceable serial number for `stolen_serial` or `dropped_item`. */
  serialNumber?: string;
  /** Vehicle plate (only populated when vehicle registration exists in that era). */
  plateNumber?: string;
  vehicleKind?: VehicleKind;
  vehicleColor?: ClothingColor;
  /** Camera metadata & captured appearance for `camera_footage`. */
  cameraId?: string;
  cameraColor?: boolean;
  cameraHd?: boolean;
  capturedAppearance?: CulpritDescription;
  /** Days of local tape/DVR retention before uncollected footage overwrites. */
  retentionDays?: number;
}

export type FileRecordScope = 'local' | 'national';

export type FileRecordReason =
  | 'arrest'
  | 'license'
  | 'database'
  | 'military'
  | 'vehicle_registration'
  | 'pawn_registry';

/** Record putting a person (or their prints/DNA/plate/footwear) on file in a jurisdiction & era. */
export interface IdentityFileRecord {
  id: string;
  personId: Id;
  jurisdictionId: string;
  scope: FileRecordScope;
  reason: FileRecordReason;
  enrolledYear: number;
  enrolledDay: number;
  hasPrints: boolean;
  hasDna: boolean;
  plateNumbers?: string[];
  shoeProfile?: string;
  /** Optional pawned/recovered serial numbers linked to this person. */
  serialNumbers?: string[];
  /** Optional known birth year on record (fallback when person is outside `world.people`). */
  birthYear?: number;
  /** Optional year the traveller first arrived in this era (for anomaly detection). */
  firstEraArrivalYear?: number;
}

export type EvidenceMatchMethod =
  | 'fingerprint_local_manual'
  | 'fingerprint_national_manual'
  | 'fingerprint_automated'
  | 'dna_manual_suspect'
  | 'dna_database'
  | 'plate_local_registry'
  | 'plate_database'
  | 'serial_registry'
  | 'dropped_item_ownership'
  | 'footprint_comparison'
  | 'camera_identification';

/** A forensic match linking one collected trace to a person on file. */
export interface EvidenceMatch {
  traceId: string;
  traceKind: TraceKind;
  crimeId: string;
  suspectId: Id;
  matchedYear: number;
  matchedDay: number;
  confidence: number;
  method: EvidenceMatchMethod;
  /** True when matched at least 1 year (or >= 365 days) after the crime was committed. */
  coldCase: boolean;
  yearsToMatch: number;
  /** True when the matched trace predates the suspect's birth or era arrival. */
  impossibleTimeline: boolean;
  beforeBirth: boolean;
  beforeEraArrival: boolean;
  anomalies: TimeTravelAnomaly[];
}

export interface CaseStrengthPart {
  source: 'witness' | 'evidence';
  kind: string;
  label: string;
  contribution: number;
  suspectId?: Id;
  traceId?: string;
  year?: number;
  coldCase?: boolean;
}

export type CaseVerdictTier =
  | 'none'
  | 'circumstantial'
  | 'probable_cause'
  | 'strong'
  | 'overwhelming';

export interface CaseStrengthResult {
  crimeId: string;
  day: number;
  combinedScore: number;
  witnessScore: number;
  physicalScore: number;
  corroborationAdjustment: number;
  verdictTier: CaseVerdictTier;
  leadSuspectId?: Id;
  witnessSuspectId?: Id;
  physicalSuspectId?: Id;
  hasTimeAnomaly: boolean;
  timeAnomalies: TimeTravelAnomaly[];
  parts: CaseStrengthPart[];
}
