import type { Id } from '../sim/types';

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
  | 'car_theft'
  | 'burglary'
  | 'assault'
  | 'robbery'
  | 'murder';

/** Default severity (0..1) per crime kind when caller does not override. */
export const DEFAULT_SEVERITY: Readonly<Record<CrimeKind, number>> = {
  vandalism: 0.22,
  shoplifting: 0.28,
  theft: 0.44,
  car_theft: 0.58,
  burglary: 0.64,
  assault: 0.72,
  robbery: 0.80,
  murder: 1.0,
};

/** How conspicuous / loud the act is for catching nearby attention (0..1). */
export const CRIME_CONSPICUOUSNESS: Readonly<Record<CrimeKind, number>> = {
  vandalism: 0.65,
  shoplifting: 0.40,
  theft: 0.48,
  car_theft: 0.72,
  burglary: 0.58,
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
}
