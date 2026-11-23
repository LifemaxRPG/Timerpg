import { mergeDescriptions } from './police';
import { clamp01, rand } from '../sim/rng';
import type { Id, World } from '../sim/types';
import {
  cameraTierForYear,
  classifySerialItem,
  detectTimeTravelAnomalies,
  dnaTierForYear,
  fingerprintTierForYear,
  FORENSIC_ERA,
  itemHasSerialInEra,
  plateTierForYear,
  type ForensicEraTable,
  type TimeTravelAnomaly,
} from './forensics';
import { ambientLightAt } from './witness';
import type {
  CaseStrengthPart,
  CaseStrengthResult,
  CaseVerdictTier,
  CrimeContext,
  CrimeEvent,
  CrimeState,
  CulpritDescription,
  DestroyMethod,
  EvidenceMatch,
  EvidenceMatchMethod,
  EvidenceTrace,
  IdentityFileRecord,
  JurisdictionState,
  PoliceCase,
  SecurityCamera,
  StreetLamp,
  TraceExposure,
  TraceKind,
  Vec2,
  WeatherKind,
} from './types';

export interface LeaveTracesOptions {
  seed: string;
  /** True if the culprit wore gloves (suppresses fingerprints). */
  gloved?: boolean;
  /** Indoor vs outdoor scene exposure (default `'indoor'` for burglary/shoplifting/theft, `'outdoor'` otherwise). */
  exposure?: TraceExposure;
  /** Weather at the time of the crime (wet ground increases footprint clarity). */
  weather?: WeatherKind;
  /** Caller-supplied security cameras near the scene. */
  cameras?: readonly SecurityCamera[];
  /** Optional street lamps for camera lighting calculation. */
  streetLamps?: readonly StreetLamp[];
  /** Optional line-of-sight predicate for cameras. */
  canSee?: (from: Vec2, to: Vec2, cameraId: string) => boolean;
  /** Force or suppress blood/DNA trace (otherwise probabilistic by crime kind). */
  bloodSpilled?: boolean;
  /** Explicit dropped item left at the scene (optional). */
  droppedItem?: { label: string; ownerId?: Id; serialNumber?: string };
  /** Explicit serial number for stolen goods (otherwise auto-generated when era + item class support serials). */
  stolenSerialNumber?: string;
  /** Explicit vehicle plate number (populated only when era >= plateRegistrationYear). */
  vehiclePlate?: string;
  /** Explicit footwear profile tag (e.g. `'work_boot_10'`). */
  shoeProfile?: string;
  /** Custom era table (defaults to `FORENSIC_ERA`). */
  forensicEra?: Readonly<ForensicEraTable>;
}

function defaultExposure(event: CrimeEvent): TraceExposure {
  if (event.kind === 'burglary' || event.kind === 'shoplifting' || event.kind === 'theft') {
    return 'indoor';
  }
  return 'outdoor';
}

function deterministicCode(seed: string, prefix: string, crimeId: string, tag: string): string {
  const n = Math.floor(rand(seed, 'code', crimeId, tag) * 900000 + 100000);
  return `${prefix}-${n}`;
}

/**
 * Pure, deterministic generation of physical and digital traces left by `event`.
 *
 * Returns plain-JSON `EvidenceTrace[]` (never mutates inputs; zero `Math.random`).
 */
export function leaveTraces(
  event: CrimeEvent,
  options: LeaveTracesOptions,
): EvidenceTrace[] {
  const era = options.forensicEra ?? FORENSIC_ERA;
  const year = event.when.year;
  const day = event.when.day;
  const exposure = options.exposure ?? defaultExposure(event);
  const weather = options.weather ?? 'clear';
  const traces: EvidenceTrace[] = [];

  // 1. Fingerprints (on touched items/surfaces unless gloved)
  if (!options.gloved) {
    const printChance =
      event.kind === 'burglary' || event.kind === 'theft' || event.kind === 'car_theft'
        ? 0.92
        : event.kind === 'robbery' || event.kind === 'murder' || event.kind === 'vandalism'
          ? 0.80
          : 0.65;
    if (rand(options.seed, 'trace-print', event.id) < printChance) {
      const q = clamp01(
        (exposure === 'indoor' ? 0.78 : 0.62) +
          rand(options.seed, 'trace-print-q', event.id) * 0.18,
      );
      traces.push({
        id: `${event.id}:fp`,
        crimeId: event.id,
        kind: 'fingerprint',
        placeId: event.placeId,
        position: { ...event.position },
        createdYear: year,
        createdDay: day,
        quality: q,
        initialQuality: q,
        exposure,
        collected: false,
        destroyed: false,
        sourcePersonId: event.culpritId,
      });
    }
  }

  // 2. Footprints (stronger outdoors or in mud/rain/snow)
  const wetBonus = weather === 'rain' || weather === 'storm' || weather === 'snow' ? 0.28 : 0;
  const footChance = clamp01((exposure === 'outdoor' ? 0.65 : 0.35) + wetBonus);
  if (options.shoeProfile !== undefined || rand(options.seed, 'trace-foot', event.id) < footChance) {
    const q = clamp01(0.45 + wetBonus * 0.8 + rand(options.seed, 'trace-foot-q', event.id) * 0.25);
    traces.push({
      id: `${event.id}:foot`,
      crimeId: event.id,
      kind: 'footprint',
      placeId: event.placeId,
      position: { ...event.position },
      createdYear: year,
      createdDay: day,
      quality: q,
      initialQuality: q,
      exposure,
      collected: false,
      destroyed: false,
      sourcePersonId: event.culpritId,
      shoeProfile: options.shoeProfile ?? `tread_${Math.floor(rand(options.seed, 'shoe', event.culpritId) * 24) + 1}`,
    });
  }

  // 3. Blood / DNA (rare: violence, struggle, or broken glass)
  const baseDnaChance =
    event.kind === 'murder'
      ? 0.62
      : event.kind === 'assault'
        ? 0.34
        : event.kind === 'robbery'
          ? 0.18
          : event.kind === 'burglary'
            ? 0.10
            : 0.02;
  const hasDna =
    options.bloodSpilled === true ||
    (options.bloodSpilled !== false && rand(options.seed, 'trace-dna', event.id) < baseDnaChance);
  if (hasDna) {
    const q = clamp01(0.76 + rand(options.seed, 'trace-dna-q', event.id) * 0.20);
    traces.push({
      id: `${event.id}:dna`,
      crimeId: event.id,
      kind: 'dna',
      placeId: event.placeId,
      position: { ...event.position },
      createdYear: year,
      createdDay: day,
      quality: q,
      initialQuality: q,
      exposure,
      collected: false,
      destroyed: false,
      sourcePersonId: event.culpritId,
    });
  }

  // 4. Dropped item (explicit or small accidental drop chance)
  if (options.droppedItem) {
    const q = 0.88;
    traces.push({
      id: `${event.id}:drop`,
      crimeId: event.id,
      kind: 'dropped_item',
      placeId: event.placeId,
      position: { ...event.position },
      createdYear: year,
      createdDay: day,
      quality: q,
      initialQuality: q,
      exposure,
      collected: false,
      destroyed: false,
      sourcePersonId: options.droppedItem.ownerId ?? event.culpritId,
      itemLabel: options.droppedItem.label,
      ...(options.droppedItem.serialNumber ? { serialNumber: options.droppedItem.serialNumber } : {}),
    });
  } else if (rand(options.seed, 'trace-drop', event.id) < 0.08 + event.severity * 0.08) {
    const q = 0.78;
    traces.push({
      id: `${event.id}:drop`,
      crimeId: event.id,
      kind: 'dropped_item',
      placeId: event.placeId,
      position: { ...event.position },
      createdYear: year,
      createdDay: day,
      quality: q,
      initialQuality: q,
      exposure,
      collected: false,
      destroyed: false,
      sourcePersonId: event.culpritId,
      itemLabel: 'personal_item',
    });
  }

  // 5. Stolen goods with serial numbers (gated by item kind and era)
  if (event.stolenOrDamaged) {
    const cls = classifySerialItem(event.stolenOrDamaged.item);
    const hasSerial =
      options.stolenSerialNumber !== undefined || itemHasSerialInEra(cls, year, era);
    if (hasSerial) {
      const serial =
        options.stolenSerialNumber ?? deterministicCode(options.seed, 'SN', event.id, event.stolenOrDamaged.item);
      traces.push({
        id: `${event.id}:serial`,
        crimeId: event.id,
        kind: 'stolen_serial',
        placeId: event.placeId,
        position: { ...event.position },
        createdYear: year,
        createdDay: day,
        quality: 0.92,
        initialQuality: 0.92,
        exposure: 'indoor',
        collected: false,
        destroyed: false,
        sourcePersonId: event.culpritId,
        itemLabel: event.stolenOrDamaged.item,
        serialNumber: serial,
      });
    }
  }

  // 6. Vehicle seen or license plate (plate only once registration exists in that era)
  if (event.culpritAppearance.vehicle) {
    const plateTier = plateTierForYear(year, era);
    const plate =
      plateTier !== 'none'
        ? (options.vehiclePlate ?? deterministicCode(options.seed, 'PL', event.culpritId, event.culpritAppearance.vehicle.kind))
        : undefined;
    const q = plate ? 0.82 : 0.45;
    traces.push({
      id: `${event.id}:veh`,
      crimeId: event.id,
      kind: 'vehicle_plate',
      placeId: event.placeId,
      position: { ...event.position },
      createdYear: year,
      createdDay: day,
      quality: q,
      initialQuality: q,
      exposure: 'outdoor',
      collected: false,
      destroyed: false,
      sourcePersonId: event.culpritId,
      vehicleKind: event.culpritAppearance.vehicle.kind,
      vehicleColor: event.culpritAppearance.vehicle.color,
      ...(plate ? { plateNumber: plate } : {}),
    });
  }

  // 7. Security-camera footage (only when era >= cameraBwYear and a camera covers the spot)
  const camTier = cameraTierForYear(year, era);
  if (camTier !== 'none' && options.cameras && options.cameras.length > 0) {
    const light = ambientLightAt(event.when.hour, event.position, options.streetLamps);
    const sortedCams = [...options.cameras].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    for (let i = 0; i < sortedCams.length; i++) {
      const cam = sortedCams[i]!;
      const radius = cam.radius ?? 28;
      const dx = cam.position.x - event.position.x;
      const dy = cam.position.y - event.position.y;
      const dist = Math.hypot(dx, dy);
      if (dist > radius) continue;
      if (options.canSee && !options.canSee(cam.position, event.position, cam.id)) continue;

      const isColor = cam.color ?? (camTier === 'color' || camTier === 'hd');
      const isHd = camTier === 'hd';
      const eraBase = isHd ? 0.90 : isColor ? 0.72 : 0.46;
      const distFactor = clamp01(1 - (dist / radius) * 0.45);
      const lightFactor = 0.35 + 0.65 * Math.sqrt(light);
      const q = clamp01(eraBase * distFactor * lightFactor);

      const captured: CulpritDescription = {
        height: event.culpritAppearance.height,
        build: event.culpritAppearance.build,
        ...(q >= 0.42 ? { hair: event.culpritAppearance.hair } : {}),
        ...(isColor && q >= 0.38 ? { clothingColor: event.culpritAppearance.clothingColor } : {}),
        ...(event.culpritAppearance.vehicle
          ? {
              vehicle: {
                kind: event.culpritAppearance.vehicle.kind,
                color: isColor ? event.culpritAppearance.vehicle.color : 'gray',
              },
            }
          : {}),
      };

      traces.push({
        id: `${event.id}:cam:${cam.id}`,
        crimeId: event.id,
        kind: 'camera_footage',
        placeId: event.placeId,
        position: { ...event.position },
        createdYear: year,
        createdDay: day,
        quality: q,
        initialQuality: q,
        exposure: cam.exposure ?? 'indoor',
        collected: false,
        destroyed: false,
        sourcePersonId: event.culpritId,
        cameraId: cam.id,
        cameraColor: isColor,
        cameraHd: isHd,
        capturedAppearance: captured,
        retentionDays: isHd ? 30 : isColor ? 21 : 14,
      });
    }
  }

  return traces;
}

/** Purely attaches traces for a crime event into `CrimeState`. */
export function recordTraces(
  state: CrimeState,
  crimeId: string,
  newTraces: readonly EvidenceTrace[],
): CrimeState {
  if (newTraces.length === 0) return state;
  const prevMap = state.traces ?? {};
  const existing = prevMap[crimeId] ?? [];
  const existingIds = new Set(existing.map((t) => t.id));
  const merged = existing.slice();
  for (let i = 0; i < newTraces.length; i++) {
    const t = newTraces[i]!;
    if (!existingIds.has(t.id)) {
      merged.push(t);
      existingIds.add(t.id);
    }
  }
  return {
    ...state,
    traces: {
      ...prevMap,
      [crimeId]: merged,
    },
  };
}

/** Daily quality retention multiplier for an uncollected scene trace given exposure and weather. */
export function traceDailyRetention(
  kind: TraceKind,
  exposure: TraceExposure,
  weather: WeatherKind,
): number {
  const weatherPenalty =
    exposure === 'indoor'
      ? 1.0
      : weather === 'storm'
        ? 0.40
        : weather === 'rain'
          ? 0.62
          : weather === 'snow'
            ? 0.72
            : weather === 'heat'
              ? 0.90
              : 1.0;

  switch (kind) {
    case 'footprint':
      return clamp01((exposure === 'outdoor' ? 0.80 : 0.94) * weatherPenalty);
    case 'fingerprint':
      return clamp01((exposure === 'outdoor' ? 0.88 : 0.982) * weatherPenalty);
    case 'dna':
      return clamp01((exposure === 'outdoor' ? 0.92 : 0.996) * weatherPenalty);
    case 'dropped_item':
      return clamp01((exposure === 'outdoor' ? 0.97 : 0.995) * (weather === 'storm' ? 0.92 : 1.0));
    case 'stolen_serial':
      return 0.999; // Serial numbers on objects don't weather away
    case 'vehicle_plate':
      return 0.99; // Witness/log record of plate fades slowly if uncollected
    case 'camera_footage':
      return 0.995; // Mostly governed by retentionDays overwrite
  }
}

/**
 * Decays one trace by a single simulated day.
 * Collected traces sit in the evidence locker and do not weather away (enabling cold cases decades later).
 */
export function decayTraceOneDay(
  trace: EvidenceTrace,
  day: number,
  weather: WeatherKind = 'clear',
): EvidenceTrace {
  if (trace.destroyed || trace.collected) return trace;

  if (trace.kind === 'camera_footage') {
    const retention = trace.retentionDays ?? 14;
    if (day - trace.createdDay >= retention) {
      return {
        ...trace,
        quality: 0,
        destroyed: true,
        destroyedMethod: 'overwrite',
        destroyedDay: day,
      };
    }
  }

  const nextQ = trace.quality * traceDailyRetention(trace.kind, trace.exposure, weather);
  if (nextQ < 0.05) {
    return {
      ...trace,
      quality: 0,
      destroyed: true,
      destroyedDay: day,
    };
  }
  return {
    ...trace,
    quality: Math.round(nextQ * 10000) / 10000,
  };
}

export interface DestroyTracesOptions {
  method: DestroyMethod;
  actorId?: Id;
  day: number;
  /** Optional filter of trace kinds to target (defaults to kinds affected by `method`). */
  kinds?: readonly TraceKind[];
  /** Thoroughness in 0..1 (default `1.0` destroys affected traces; `< 1.0` degrades quality). */
  thoroughness?: number;
}

function methodAffectsKind(method: DestroyMethod, kind: TraceKind): boolean {
  switch (method) {
    case 'clean':
    case 'wash':
      return kind === 'fingerprint' || kind === 'footprint' || kind === 'dna';
    case 'wipe':
      return kind === 'fingerprint' || kind === 'dna';
    case 'burn':
      return kind === 'fingerprint' || kind === 'footprint' || kind === 'dna' || kind === 'dropped_item' || kind === 'camera_footage';
    case 'overwrite':
      return kind === 'camera_footage';
    case 'dispose':
      return kind === 'dropped_item' || kind === 'stolen_serial' || kind === 'vehicle_plate';
  }
}

/**
 * Destroys or degrades uncollected traces at `crimeId` (e.g. cleaning blood, wiping prints,
 * burning a room, or overwriting security footage). Already-collected police evidence is untouched.
 */
export function destroyTraces(
  state: CrimeState,
  crimeId: string,
  options: DestroyTracesOptions,
): CrimeState {
  const prevMap = state.traces;
  const list = prevMap?.[crimeId];
  if (!list || list.length === 0) return state;

  const thoroughness = clamp01(options.thoroughness ?? 1.0);
  const kindSet = options.kinds ? new Set(options.kinds) : undefined;
  let changed = false;

  const nextList = list.map((t) => {
    if (t.destroyed || t.collected) return t;
    const targeted = kindSet ? kindSet.has(t.kind) : methodAffectsKind(options.method, t.kind);
    if (!targeted) return t;
    changed = true;
    const remaining = t.quality * (1 - thoroughness);
    if (remaining < 0.08) {
      return {
        ...t,
        quality: 0,
        destroyed: true,
        destroyedMethod: options.method,
        destroyedDay: options.day,
        ...(options.actorId ? { destroyedBy: options.actorId } : {}),
      };
    }
    return {
      ...t,
      quality: Math.round(remaining * 10000) / 10000,
    };
  });

  if (!changed) return state;
  return {
    ...state,
    traces: {
      ...prevMap,
      [crimeId]: nextList,
    },
  };
}

/** Alias for `destroyTraces` with scene-cleaning semantics. */
export const cleanScene = destroyTraces;

/**
 * Purely enrolls a person's record (arrest prints, DNA sample, driver's licence, vehicle plate,
 * pawned serial, or national database entry) into `state.identityFiles`.
 */
export function enrollOnFile(state: CrimeState, record: IdentityFileRecord): CrimeState {
  const prevMap = state.identityFiles ?? {};
  const existing = prevMap[record.personId] ?? [];
  const filtered = existing.filter((r) => r.id !== record.id);
  return {
    ...state,
    identityFiles: {
      ...prevMap,
      [record.personId]: [...filtered, record],
    },
  };
}

export interface MatchOptions {
  forensicEra?: Readonly<ForensicEraTable>;
  birthYearOf?: (personId: Id) => number | undefined;
  eraArrivalYearOf?: (personId: Id) => number | undefined;
}

function recordAccessible(
  rec: IdentityFileRecord,
  jurisdictionId: string,
  year: number,
  allowCrossJurisdiction: boolean,
  requireNationalScopeForCross: boolean,
): boolean {
  if (rec.enrolledYear > year) return false;
  if (rec.jurisdictionId === jurisdictionId) return true;
  if (!allowCrossJurisdiction) return false;
  return requireNationalScopeForCross ? rec.scope === 'national' : true;
}

/**
 * Attempts to match one collected, non-destroyed trace against `state.identityFiles`
 * strictly respecting the forensic science available in `year` and the jurisdiction's access.
 *
 * Returns `null` if science is not yet capable or the source person is not on file yet
 * (leaving the trace in the case file for future cold-case matching).
 */
export function matchTrace(
  trace: EvidenceTrace,
  jurisdictionId: string,
  year: number,
  day: number,
  state: CrimeState,
  world: World,
  crimeSeverity: number,
  options: MatchOptions = {},
): EvidenceMatch | null {
  if (trace.destroyed || trace.quality < 0.08) return null;
  const era = options.forensicEra ?? FORENSIC_ERA;
  const filesByPerson = state.identityFiles;
  if (!filesByPerson) return null;

  let matchedPersonId: Id | undefined;
  let matchedRecord: IdentityFileRecord | undefined;
  let method: EvidenceMatchMethod | undefined;
  let baseConf = 0;

  const sourceId = trace.sourcePersonId;
  const sourceRecords = sourceId ? filesByPerson[sourceId] : undefined;

  switch (trace.kind) {
    case 'fingerprint': {
      const tier = fingerprintTierForYear(year, era);
      if (tier === 'none' || !sourceRecords || !sourceId) return null;
      const allowCross = tier === 'national_manual' || tier === 'automated';
      const requireNational = tier === 'national_manual';
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        if (!rec.hasPrints) continue;
        if (!recordAccessible(rec, jurisdictionId, year, allowCross, requireNational)) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method =
          tier === 'automated'
            ? 'fingerprint_automated'
            : rec.jurisdictionId === jurisdictionId
              ? 'fingerprint_local_manual'
              : 'fingerprint_national_manual';
        baseConf = tier === 'automated' ? 0.96 : tier === 'national_manual' ? 0.90 : 0.86;
        break;
      }
      break;
    }

    case 'dna': {
      const tier = dnaTierForYear(year, era);
      if (tier === 'none' || !sourceRecords || !sourceId) return null;
      // Manual DNA (1987-1994) matches local booked suspects with DNA samples;
      // DNA database (1995+) cold-hits across all jurisdictions.
      const allowCross = tier === 'database';
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        if (!rec.hasDna) continue;
        if (!recordAccessible(rec, jurisdictionId, year, allowCross, false)) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method = tier === 'database' ? 'dna_database' : 'dna_manual_suspect';
        baseConf = tier === 'database' ? 0.99 : 0.97;
        break;
      }
      break;
    }

    case 'vehicle_plate': {
      const tier = plateTierForYear(year, era);
      if (tier === 'none' || !trace.plateNumber || !sourceRecords || !sourceId) return null;
      const allowCross = tier === 'database';
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        if (!rec.plateNumbers || !rec.plateNumbers.includes(trace.plateNumber)) continue;
        if (!recordAccessible(rec, jurisdictionId, year, allowCross, false)) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method = tier === 'database' ? 'plate_database' : 'plate_local_registry';
        baseConf = tier === 'database' ? 0.86 : 0.80;
        break;
      }
      break;
    }

    case 'stolen_serial': {
      if (!trace.serialNumber || !sourceRecords || !sourceId) return null;
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        if (!rec.serialNumbers || !rec.serialNumbers.includes(trace.serialNumber)) continue;
        if (!recordAccessible(rec, jurisdictionId, year, year >= 1970, false)) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method = 'serial_registry';
        baseConf = 0.88;
        break;
      }
      break;
    }

    case 'dropped_item': {
      if (!sourceRecords || !sourceId) return null;
      // Dropped items link to someone on file locally (or via serial number if present)
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        const serialHit =
          trace.serialNumber !== undefined &&
          rec.serialNumbers !== undefined &&
          rec.serialNumbers.includes(trace.serialNumber);
        const localHit = rec.jurisdictionId === jurisdictionId && rec.enrolledYear <= year;
        if (!serialHit && !localHit) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method = 'dropped_item_ownership';
        baseConf = serialHit ? 0.85 : 0.65;
        break;
      }
      break;
    }

    case 'footprint': {
      if (!trace.shoeProfile || !sourceRecords || !sourceId) return null;
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        if (rec.shoeProfile !== trace.shoeProfile) continue;
        if (!recordAccessible(rec, jurisdictionId, year, false, false)) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method = 'footprint_comparison';
        baseConf = 0.56;
        break;
      }
      break;
    }

    case 'camera_footage': {
      const tier = cameraTierForYear(year, era);
      if (tier === 'none' || !sourceRecords || !sourceId) return null;
      const minQ = tier === 'hd' ? 0.35 : tier === 'color' ? 0.45 : 0.50;
      if (trace.quality < minQ) return null;
      const allowCross = tier === 'hd';
      for (let i = 0; i < sourceRecords.length; i++) {
        const rec = sourceRecords[i]!;
        if (!recordAccessible(rec, jurisdictionId, year, allowCross, false)) continue;
        matchedPersonId = sourceId;
        matchedRecord = rec;
        method = 'camera_identification';
        baseConf = tier === 'hd' ? 0.88 : tier === 'color' ? 0.74 : 0.58;
        break;
      }
      break;
    }
  }

  if (!matchedPersonId || !method) return null;

  const confidence = clamp01(baseConf * (0.45 + 0.55 * trace.quality));
  const yearsToMatch = Math.max(0, year - trace.createdYear);
  const coldCase = yearsToMatch >= 1 || day - trace.createdDay >= 365;

  const birthYear =
    options.birthYearOf?.(matchedPersonId) ??
    matchedRecord?.birthYear ??
    world.people[matchedPersonId]?.birthYear;
  const arrivalYear =
    options.eraArrivalYearOf?.(matchedPersonId) ?? matchedRecord?.firstEraArrivalYear;

  const anomalies = detectTimeTravelAnomalies({
    traceId: trace.id,
    crimeId: trace.crimeId,
    traceKind: trace.kind,
    personId: matchedPersonId,
    traceYear: trace.createdYear,
    birthYear,
    firstEraArrivalYear: arrivalYear,
  });

  const beforeBirth = anomalies.some((a) => a.kind === 'before_birth');
  const beforeEraArrival = anomalies.some((a) => a.kind === 'before_era_arrival');

  return {
    traceId: trace.id,
    traceKind: trace.kind,
    crimeId: trace.crimeId,
    suspectId: matchedPersonId,
    matchedYear: year,
    matchedDay: day,
    confidence: Math.round(confidence * 10000) / 10000,
    method,
    coldCase,
    yearsToMatch,
    impossibleTimeline: anomalies.length > 0,
    beforeBirth,
    beforeEraArrival,
    anomalies,
  };
}

function emptyCaseForEvidence(crime: CrimeEvent, day: number): PoliceCase {
  return {
    crimeId: crime.id,
    kind: crime.kind,
    severity: crime.severity,
    placeId: crime.placeId,
    crimeDay: crime.when.day,
    firstReportedDay: day,
    lastReportedDay: day,
    reportCount: 0,
    firstHandCount: 0,
    reports: [],
    merged: {
      description: {},
      fieldConfidence: {},
      overallConfidence: 0,
      namedSuspectConfidence: 0,
    },
    actualCulpritId: crime.culpritId,
    traces: [],
    evidenceMatches: [],
    timeAnomalies: [],
  };
}

function applyEvidenceMatchesToCase(
  prevCase: PoliceCase,
  collectedTraces: EvidenceTrace[],
  matches: EvidenceMatch[],
): PoliceCase {
  const anomalies: TimeTravelAnomaly[] = [];
  const seenAnomaly = new Set<string>();
  let bestPhysSuspect: Id | undefined;
  let bestPhysConf = 0;

  for (let i = 0; i < matches.length; i++) {
    const m = matches[i]!;
    if (m.confidence > bestPhysConf) {
      bestPhysConf = m.confidence;
      bestPhysSuspect = m.suspectId;
    }
    for (let j = 0; j < m.anomalies.length; j++) {
      const a = m.anomalies[j]!;
      const key = `${a.kind}:${a.traceId}:${a.personId}`;
      if (!seenAnomaly.has(key)) {
        seenAnomaly.add(key);
        anomalies.push(a);
      }
    }
  }

  // If physical evidence firmly names a suspect and witnesses hadn't named one with higher confidence,
  // surface the matched suspect on `merged.namedSuspectId` so existing police queries (`isWanted`, `isNamedSuspect`) work seamlessly.
  let merged = prevCase.merged;
  if (bestPhysSuspect && bestPhysConf >= 0.55 && bestPhysConf > prevCase.merged.namedSuspectConfidence) {
    merged = {
      ...prevCase.merged,
      namedSuspectId: bestPhysSuspect,
      namedSuspectConfidence: bestPhysConf,
      overallConfidence: Math.max(prevCase.merged.overallConfidence, bestPhysConf),
    };
  }

  return {
    ...prevCase,
    merged,
    traces: collectedTraces,
    evidenceMatches: matches,
    timeAnomalies: anomalies,
  };
}

/**
 * Police collect all surviving uncollected traces from `crimeId` into `jurisdictionId`'s case file
 * on `(day, year)`, locking them in the evidence locker (stopping weather decay) and immediately
 * running era-appropriate forensic matching.
 */
export function collectCrimeTraces(
  state: CrimeState,
  world: World,
  crimeId: string,
  jurisdictionId: string,
  day: number,
  options: MatchOptions & { year?: number } = {},
): CrimeState {
  const crime = state.crimes[crimeId];
  const sceneTraces = state.traces?.[crimeId];
  if (!crime || !sceneTraces || sceneTraces.length === 0) return state;

  const year = options.year ?? world.year;
  let collectedAny = false;
  const updatedSceneTraces = sceneTraces.map((t) => {
    if (t.destroyed || t.collected) return t;
    collectedAny = true;
    return {
      ...t,
      collected: true,
      collectedDay: day,
      collectedYear: year,
      collectedJurisdiction: jurisdictionId,
    };
  });

  const jur: JurisdictionState = state.jurisdictions[jurisdictionId] ?? {
    jurisdictionId,
    cases: {},
  };
  const prevCase = jur.cases[crimeId] ?? emptyCaseForEvidence(crime, day);
  const existingTraces = prevCase.traces ?? [];
  const byId = new Map<string, EvidenceTrace>();
  for (let i = 0; i < existingTraces.length; i++) byId.set(existingTraces[i]!.id, existingTraces[i]!);
  for (let i = 0; i < updatedSceneTraces.length; i++) {
    const t = updatedSceneTraces[i]!;
    if (t.collected && !t.destroyed && t.collectedJurisdiction === jurisdictionId) {
      byId.set(t.id, t);
    }
  }
  const caseTraces = Array.from(byId.values());

  const existingMatches = prevCase.evidenceMatches ?? [];
  const matchedTraceIds = new Set(existingMatches.map((m) => m.traceId));
  const nextMatches = existingMatches.slice();

  for (let i = 0; i < caseTraces.length; i++) {
    const t = caseTraces[i]!;
    if (matchedTraceIds.has(t.id)) continue;
    const hit = matchTrace(t, jurisdictionId, year, day, state, world, crime.severity, options);
    if (hit) {
      nextMatches.push(hit);
      matchedTraceIds.add(t.id);
    }
  }

  if (!collectedAny && nextMatches.length === existingMatches.length && jur.cases[crimeId]) {
    return state;
  }

  const nextCase = applyEvidenceMatchesToCase(prevCase, caseTraces, nextMatches);

  return {
    ...state,
    traces: {
      ...state.traces,
      [crimeId]: updatedSceneTraces,
    },
    jurisdictions: {
      ...state.jurisdictions,
      [jurisdictionId]: {
        jurisdictionId,
        cases: {
          ...jur.cases,
          [crimeId]: nextCase,
        },
      },
    },
  };
}

/**
 * Re-examines all unmatched collected traces across all jurisdictions against the current `year`
 * and `state.identityFiles`. Solves cold cases when new people enter files or when new forensic
 * science (AFIS, DNA, databases) comes online.
 */
export function reexamineColdCases(
  state: CrimeState,
  world: World,
  day: number,
  ctx?: CrimeContext,
): CrimeState {
  if (!state.identityFiles) return state;
  const year = ctx?.year ?? world.year;
  const opts: MatchOptions = {
    forensicEra: ctx?.forensicEra,
    birthYearOf: ctx?.birthYearOf,
    eraArrivalYearOf: ctx?.eraArrivalYearOf,
  };

  let nextJurisdictions = state.jurisdictions;
  let jurMapCopied = false;

  const jurIds = Object.keys(state.jurisdictions);
  for (let ji = 0; ji < jurIds.length; ji++) {
    const jurId = jurIds[ji]!;
    const jur = state.jurisdictions[jurId]!;
    const caseIds = Object.keys(jur.cases);
    let nextCases = jur.cases;
    let casesCopied = false;

    for (let ci = 0; ci < caseIds.length; ci++) {
      const crimeId = caseIds[ci]!;
      const c = jur.cases[crimeId]!;
      const traces = c.traces;
      if (!traces || traces.length === 0) continue;

      const prevMatches = c.evidenceMatches ?? [];
      if (prevMatches.length >= traces.length) continue;

      let matchedSet: Set<string> | undefined;
      let addedMatches: EvidenceMatch[] | undefined;

      for (let ti = 0; ti < traces.length; ti++) {
        const t = traces[ti]!;
        if (t.destroyed) continue;
        if (!matchedSet) {
          matchedSet = new Set<string>();
          for (let m = 0; m < prevMatches.length; m++) matchedSet.add(prevMatches[m]!.traceId);
        }
        if (matchedSet.has(t.id)) continue;
        const hit = matchTrace(t, jurId, year, day, state, world, c.severity, opts);
        if (hit) {
          if (!addedMatches) addedMatches = [];
          addedMatches.push(hit);
          matchedSet.add(t.id);
        }
      }

      if (addedMatches && addedMatches.length > 0) {
        if (!casesCopied) {
          nextCases = { ...jur.cases };
          casesCopied = true;
        }
        nextCases[crimeId] = applyEvidenceMatchesToCase(
          c,
          traces,
          [...prevMatches, ...addedMatches],
        );
      }
    }

    if (casesCopied) {
      if (!jurMapCopied) {
        nextJurisdictions = { ...state.jurisdictions };
        jurMapCopied = true;
      }
      nextJurisdictions[jurId] = {
        jurisdictionId: jurId,
        cases: nextCases,
      };
    }
  }

  return jurMapCopied ? { ...state, jurisdictions: nextJurisdictions } : state;
}

/**
 * Scans all police case files (and optionally enrolled identity files) for time-travel anomalies
 * such as fingerprints from before a suspect's birth year or before their arrival in an era.
 */
export function findTimeTravelAnomalies(
  state: CrimeState,
  world?: World,
  options: { birthYearOf?: (personId: Id) => number | undefined } = {},
): TimeTravelAnomaly[] {
  const out: TimeTravelAnomaly[] = [];
  const seen = new Set<string>();

  const jurIds = Object.keys(state.jurisdictions).sort();
  for (let ji = 0; ji < jurIds.length; ji++) {
    const jur = state.jurisdictions[jurIds[ji]!]!;
    const caseIds = Object.keys(jur.cases).sort();
    for (let ci = 0; ci < caseIds.length; ci++) {
      const c = jur.cases[caseIds[ci]!]!;
      const list = c.timeAnomalies;
      if (!list) continue;
      for (let i = 0; i < list.length; i++) {
        const a = list[i]!;
        const key = `${a.kind}:${a.traceId}:${a.personId}`;
        if (!seen.has(key)) {
          seen.add(key);
          out.push(a);
        }
      }
    }
  }

  // Also flag identity file records themselves enrolled before a person's birth year
  if (state.identityFiles) {
    const pids = Object.keys(state.identityFiles).sort();
    for (let pi = 0; pi < pids.length; pi++) {
      const pid = pids[pi]!;
      const recs = state.identityFiles[pid]!;
      for (let ri = 0; ri < recs.length; ri++) {
        const rec = recs[ri]!;
        const birthYear =
          options.birthYearOf?.(pid) ?? rec.birthYear ?? world?.people[pid]?.birthYear;
        if (birthYear !== undefined && rec.enrolledYear < birthYear) {
          const gap = birthYear - rec.enrolledYear;
          const key = `before_birth:file:${rec.id}:${pid}`;
          if (!seen.has(key)) {
            seen.add(key);
            out.push({
              kind: 'before_birth',
              traceId: `file:${rec.id}`,
              crimeId: '',
              personId: pid,
              traceYear: rec.enrolledYear,
              referenceYear: birthYear,
              yearsImpossible: gap,
              summary: `file record ${rec.id} (${rec.reason}) from ${rec.enrolledYear} predates ${pid}'s birth in ${birthYear} (${gap} year${gap === 1 ? '' : 's'} before birth)`,
            });
          }
        }
      }
    }
  }

  return out;
}

const TRACE_EVIDENCE_WEIGHT: Readonly<Record<TraceKind, number>> = {
  dna: 0.94,
  fingerprint: 0.88,
  stolen_serial: 0.78,
  camera_footage: 0.74,
  vehicle_plate: 0.68,
  dropped_item: 0.58,
  footprint: 0.42,
};

function tierForScore(score: number): CaseVerdictTier {
  if (score < 0.20) return 'none';
  if (score < 0.45) return 'circumstantial';
  if (score < 0.70) return 'probable_cause';
  if (score < 0.88) return 'strong';
  return 'overwhelming';
}

/**
 * Combines witness descriptions and physical forensic evidence on `policeCase` into a single
 * explainable prosecution strength score (`0..1`) with itemized `parts`.
 */
export function caseStrength(policeCase: PoliceCase, day: number): CaseStrengthResult {
  const parts: CaseStrengthPart[] = [];

  // 1. Pure witness contribution (computed from reports so physical matches do not double-count or mask contradictions)
  let witnessScore = 0;
  const witnessMerged =
    policeCase.reportCount > 0 && policeCase.reports.length > 0
      ? mergeDescriptions(policeCase.reports)
      : undefined;
  const witnessSuspectId = witnessMerged?.namedSuspectId;

  if (witnessMerged && witnessMerged.overallConfidence > 0) {
    const elapsedDays = Math.max(0, day - policeCase.lastReportedDay);
    // Witness recall for court fades slowly (half-life ~180 days, floored at 0.55)
    const freshness = 0.55 + 0.45 * Math.pow(0.5, elapsedDays / 180);
    const corroboration = Math.min(1, 0.55 + 0.18 * policeCase.firstHandCount + 0.08 * policeCase.reportCount);
    const namedFactor = witnessSuspectId ? 0.85 + 0.15 * witnessMerged.namedSuspectConfidence : 0.62;
    witnessScore = clamp01(witnessMerged.overallConfidence * freshness * corroboration * namedFactor * 0.82);

    parts.push({
      source: 'witness',
      kind: witnessSuspectId ? 'witness_identification' : 'witness_description',
      label: witnessSuspectId
        ? `${policeCase.firstHandCount} first-hand / ${policeCase.reportCount} total witness report(s) naming ${witnessSuspectId}`
        : `${policeCase.firstHandCount} first-hand / ${policeCase.reportCount} total witness description(s)`,
      contribution: Math.round(witnessScore * 10000) / 10000,
      ...(witnessSuspectId ? { suspectId: witnessSuspectId } : {}),
    });
  }

  // 2. Physical & digital forensic evidence (does not decay once collected)
  const matches = policeCase.evidenceMatches ?? [];
  const traces = policeCase.traces ?? [];
  let physResidual = 1.0;
  const suspectScores: Record<Id, number> = {};
  const matchedKinds = new Set<TraceKind>();

  for (let i = 0; i < matches.length; i++) {
    const m = matches[i]!;
    const weight = TRACE_EVIDENCE_WEIGHT[m.traceKind];
    const contrib = clamp01(weight * m.confidence);
    physResidual *= 1 - contrib;
    matchedKinds.add(m.traceKind);
    suspectScores[m.suspectId] = 1 - (1 - (suspectScores[m.suspectId] ?? 0)) * (1 - contrib);

    parts.push({
      source: 'evidence',
      kind: m.traceKind,
      label: `${m.traceKind} matched ${m.suspectId} via ${m.method} (${m.matchedYear}${m.coldCase ? ', cold case' : ''}${m.impossibleTimeline ? ', IMPOSSIBLE TIMELINE' : ''})`,
      contribution: Math.round(contrib * 10000) / 10000,
      suspectId: m.suspectId,
      traceId: m.traceId,
      year: m.matchedYear,
      coldCase: m.coldCase,
    });
  }

  // Unmatched collected traces still provide minor corroborating scene evidence (proving method/presence)
  const matchedIds = new Set(matches.map((m) => m.traceId));
  let unmatchedCount = 0;
  for (let i = 0; i < traces.length; i++) {
    const t = traces[i]!;
    if (t.destroyed || matchedIds.has(t.id) || t.quality < 0.15) continue;
    unmatchedCount++;
    const minor = clamp01(0.08 * t.quality);
    physResidual *= 1 - minor;
  }
  if (unmatchedCount > 0) {
    parts.push({
      source: 'evidence',
      kind: 'unmatched_scene_traces',
      label: `${unmatchedCount} unmatched physical trace(s) preserved in case file`,
      contribution: Math.round(Math.min(0.18, unmatchedCount * 0.06) * 10000) / 10000,
    });
  }

  let physicalScore = clamp01(1 - physResidual);
  // Multi-modal physical corroboration (e.g. prints + camera or DNA + serial)
  if (matchedKinds.size >= 2) {
    physicalScore = clamp01(physicalScore + Math.min(0.08, (matchedKinds.size - 1) * 0.04));
  }

  // Determine strongest physical suspect
  let physicalSuspectId: Id | undefined;
  let bestPhysSuspectScore = 0;
  const physSuspectIds = Object.keys(suspectScores).sort();
  for (let i = 0; i < physSuspectIds.length; i++) {
    const sid = physSuspectIds[i]!;
    const sc = suspectScores[sid]!;
    if (sc > bestPhysSuspectScore) {
      bestPhysSuspectScore = sc;
      physicalSuspectId = sid;
    }
  }

  // 3. Corroboration or contradiction between witnesses and physical forensics
  let corroborationAdjustment = 0;
  if (witnessScore > 0.15 && physicalScore > 0.25) {
    if (witnessSuspectId && physicalSuspectId) {
      if (witnessSuspectId === physicalSuspectId) {
        corroborationAdjustment = 0.10;
        parts.push({
          source: 'evidence',
          kind: 'witness_physical_corroboration',
          label: `Witness identification and physical forensics independently name ${physicalSuspectId}`,
          contribution: 0.10,
          suspectId: physicalSuspectId,
        });
      } else {
        corroborationAdjustment = -0.14;
        parts.push({
          source: 'evidence',
          kind: 'witness_physical_conflict',
          label: `Conflict: witness named ${witnessSuspectId} but physical forensics matched ${physicalSuspectId}`,
          contribution: -0.14,
          suspectId: physicalSuspectId,
        });
      }
    } else if (physicalSuspectId) {
      corroborationAdjustment = 0.05;
      parts.push({
        source: 'evidence',
        kind: 'witness_physical_corroboration',
        label: `Witness account corroborated by physical match on ${physicalSuspectId}`,
        contribution: 0.05,
        suspectId: physicalSuspectId,
      });
    }
  }

  const combinedRaw = 1 - (1 - witnessScore) * (1 - physicalScore) + corroborationAdjustment;
  const combinedScore = Math.round(clamp01(combinedRaw) * 10000) / 10000;
  const leadSuspectId =
    physicalSuspectId && bestPhysSuspectScore >= witnessScore * 0.8
      ? physicalSuspectId
      : (witnessSuspectId ?? physicalSuspectId);

  const timeAnomalies = policeCase.timeAnomalies ?? [];

  return {
    crimeId: policeCase.crimeId,
    day,
    combinedScore,
    witnessScore: Math.round(witnessScore * 10000) / 10000,
    physicalScore: Math.round(physicalScore * 10000) / 10000,
    corroborationAdjustment,
    verdictTier: tierForScore(combinedScore),
    ...(leadSuspectId ? { leadSuspectId } : {}),
    ...(witnessSuspectId ? { witnessSuspectId } : {}),
    ...(physicalSuspectId ? { physicalSuspectId } : {}),
    hasTimeAnomaly: timeAnomalies.length > 0,
    timeAnomalies,
    parts,
  };
}
