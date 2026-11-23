import { clamp01, rngFor } from '../sim/rng';
import { affinity } from '../sim/social';
import { ageIn, isAlive, type Id, type Person, type World } from '../sim/types';
import {
  BUILD_BANDS,
  CLOTHING_COLORS,
  CRIME_CONSPICUOUSNESS,
  HAIR_TONES,
  HEIGHT_BANDS,
  VEHICLE_KINDS,
  type BuildBand,
  type ClothingColor,
  type CrimeEvent,
  type CulpritAppearance,
  type CulpritDescription,
  type HairTone,
  type HeightBand,
  type Sighting,
  type StreetLamp,
  type Vec2,
  type VehicleKind,
} from './types';

export interface Bystander {
  person: Person;
  position: Vec2;
  /** Optional alertness in 0..1 (defaults from `person.traits.diligence`). */
  attention?: number;
  /** Whether the bystander is awake/conscious (defaults true). */
  conscious?: boolean;
  /** Optional explicit override for whether this bystander personally knows the culprit. */
  knowsCulprit?: boolean;
}

export interface WitnessOptions {
  seed: string;
  /** Caller line-of-sight check; defaults to unobstructed (`true`). */
  canSee?: (from: Vec2, to: Vec2, bystanderId: Id) => boolean;
  streetLamps?: readonly StreetLamp[];
  /** Maximum witnessing distance in full daylight (meters, default 55). */
  maxDistance?: number;
  /** Optional world used to determine personal familiarity (`affinity` / kin) with the culprit. */
  world?: World;
  /** Optional victim `Person` if the victim is not already included in `bystanders`. */
  victim?: Person;
}

export function distance2D(a: Vec2, b: Vec2): number {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return Math.hypot(dx, dy);
}

/**
 * Ambient light in [0.06, 1.0] at `position` given `hour` (0..24) and nearby `streetLamps`.
 * Noon = 1.0, unlit 2 a.m. alley = 0.06, smoothly ramped across dawn (5–8) and dusk (17–21).
 */
export function ambientLightAt(
  hour: number,
  position: Vec2,
  streetLamps?: readonly StreetLamp[],
): number {
  const h = ((hour % 24) + 24) % 24;
  let solar: number;
  if (h >= 8 && h <= 17) {
    solar = 1.0;
  } else if (h >= 5 && h < 8) {
    solar = 0.06 + 0.94 * ((h - 5) / 3);
  } else if (h > 17 && h <= 21) {
    solar = 0.06 + 0.94 * (1 - (h - 17) / 4);
  } else {
    solar = 0.06;
  }

  let lampBoost = 0;
  if (streetLamps && streetLamps.length > 0) {
    for (let i = 0; i < streetLamps.length; i++) {
      const lamp = streetLamps[i]!;
      const radius = lamp.radius ?? 18;
      if (radius <= 0) continue;
      const d = distance2D(position, lamp.position);
      if (d < radius) {
        const falloff = 1 - d / radius;
        const contrib = (lamp.intensity ?? 0.72) * falloff * falloff;
        if (contrib > lampBoost) lampBoost = contrib;
      }
    }
  }

  return clamp01(Math.max(solar, Math.min(0.92, solar + lampBoost)));
}

/** Visual acuity factor by age: peaks at 12..55 (1.0), tapers for young children and elderly. */
export function ageVisionFactor(age: number): number {
  if (age < 4) return 0.15;
  if (age < 12) return 0.45 + 0.55 * ((age - 4) / 8);
  if (age <= 55) return 1.0;
  if (age >= 95) return 0.52;
  return 1.0 - 0.48 * ((age - 55) / 40);
}

function areDirectKin(a: Person, b: Person): boolean {
  if (a.spouse === b.id || b.spouse === a.id) return true;
  if (a.parents.includes(b.id) || b.parents.includes(a.id)) return true;
  for (let i = 0; i < a.parents.length; i++) {
    if (b.parents.includes(a.parents[i]!)) return true;
  }
  return false;
}

function pickWrongFromOrdered<T>(r: () => number, truth: T, domain: readonly T[]): T {
  const idx = domain.indexOf(truth);
  if (idx >= 0 && domain.length <= 3) {
    // Adjacent band errors for ordered scales like height/build
    const candidates: T[] = [];
    if (idx > 0) candidates.push(domain[idx - 1]!);
    if (idx + 1 < domain.length) candidates.push(domain[idx + 1]!);
    if (candidates.length > 0) return candidates[Math.floor(r() * candidates.length)]!;
  }
  const others = domain.filter((v) => v !== truth);
  return others[Math.floor(r() * others.length)]!;
}

/**
 * Generates a partial, fallible perceived description from `truth` at `confidence` (0..1).
 * Lower confidence drops more fields and increases misremembered values.
 */
export function observeDescription(
  truth: CulpritAppearance,
  confidence: number,
  seed: string,
  crimeId: string,
  witnessId: Id,
): CulpritDescription {
  const c = clamp01(confidence);
  const r = rngFor(seed, 'observe_desc', crimeId, witnessId);
  const errProb = clamp01(0.42 * (1 - c) * (1 - 0.4 * c) + 0.02);

  const out: CulpritDescription = {};

  if (r() < clamp01(0.28 + 0.72 * c)) {
    out.height = r() < errProb ? pickWrongFromOrdered<HeightBand>(r, truth.height, HEIGHT_BANDS) : truth.height;
  }
  if (r() < clamp01(0.22 + 0.74 * c)) {
    out.build = r() < errProb ? pickWrongFromOrdered<BuildBand>(r, truth.build, BUILD_BANDS) : truth.build;
  }
  if (r() < clamp01(0.20 + 0.78 * c)) {
    out.hair = r() < errProb * 0.9 ? pickWrongFromOrdered<HairTone>(r, truth.hair, HAIR_TONES) : truth.hair;
  }
  if (r() < clamp01(0.26 + 0.72 * c)) {
    out.clothingColor =
      r() < errProb * 1.1 ? pickWrongFromOrdered<ClothingColor>(r, truth.clothingColor, CLOTHING_COLORS) : truth.clothingColor;
  }
  if (truth.vehicle && r() < clamp01(0.32 + 0.66 * c)) {
    const kind =
      r() < errProb * 0.8 ? pickWrongFromOrdered<VehicleKind>(r, truth.vehicle.kind, VEHICLE_KINDS) : truth.vehicle.kind;
    const color =
      r() < errProb ? pickWrongFromOrdered<ClothingColor>(r, truth.vehicle.color, CLOTHING_COLORS) : truth.vehicle.color;
    out.vehicle = { kind, color };
  }

  // Guarantee at least one silhouette trait if confidence is meaningful
  if (
    c >= 0.25 &&
    out.height === undefined &&
    out.build === undefined &&
    out.hair === undefined &&
    out.clothingColor === undefined &&
    out.vehicle === undefined
  ) {
    out.height = r() < errProb ? pickWrongFromOrdered<HeightBand>(r, truth.height, HEIGHT_BANDS) : truth.height;
  }

  return out;
}

/**
 * Decides which bystanders saw `event` and returns one `Sighting` per witness (sorted by witnessId).
 * Conscious victims are always witnesses regardless of lighting or `canSee`.
 */
export function witnessesOf(
  event: CrimeEvent,
  bystanders: readonly Bystander[],
  options: WitnessOptions,
): Sighting[] {
  const maxDist = options.maxDistance ?? 55;
  const light = ambientLightAt(event.when.hour, event.position, options.streetLamps);
  const conspicuousness = CRIME_CONSPICUOUSNESS[event.kind] ?? 0.6;
  const effectiveRange = maxDist * (0.18 + 0.82 * Math.sqrt(light));
  const victimConscious = event.victimConscious ?? event.kind !== 'murder';

  const candidates = new Map<Id, Bystander>();
  for (let i = 0; i < bystanders.length; i++) {
    const b = bystanders[i]!;
    if (!candidates.has(b.person.id)) candidates.set(b.person.id, b);
  }

  // Ensure a conscious victim is always evaluated even if omitted from bystanders[]
  if (event.victimId && victimConscious && !candidates.has(event.victimId)) {
    const victimPerson = options.victim ?? options.world?.people[event.victimId];
    if (victimPerson) {
      candidates.set(event.victimId, {
        person: victimPerson,
        position: event.position,
        attention: 0.95,
        conscious: true,
      });
    }
  }

  const culpritPerson = options.world?.people[event.culpritId];
  const sortedIds = [...candidates.keys()].sort();
  const sightings: Sighting[] = [];

  for (let i = 0; i < sortedIds.length; i++) {
    const id = sortedIds[i]!;
    const b = candidates.get(id)!;
    const person = b.person;
    if (id === event.culpritId) continue;
    if (!isAlive(person)) continue;

    const isVictim = event.victimId !== undefined && id === event.victimId;
    const isConscious = b.conscious ?? (isVictim ? victimConscious : true);
    if (!isConscious) continue;

    const age = Math.max(0, ageIn(person, event.when.year));
    const acuity = ageVisionFactor(age);
    const attention = clamp01(b.attention ?? 0.45 + 0.35 * person.traits.diligence);
    const dist = isVictim ? 0 : distance2D(b.position, event.position);

    let confidence: number;
    if (isVictim) {
      // Conscious victims always witness their own crime; clarity still depends on light & vision
      const lightClarity = 0.28 + 0.72 * Math.sqrt(light);
      confidence = clamp01(Math.max(0.22, lightClarity * (0.45 + 0.55 * acuity) * (0.70 + 0.30 * attention)));
    } else {
      if (dist > effectiveRange) continue;
      if (options.canSee && !options.canSee(b.position, event.position, id)) continue;

      const distFactor = Math.pow(Math.max(0, 1 - dist / effectiveRange), 1.25);
      const lightFactor = 0.14 + 0.86 * Math.sqrt(light);
      const detectProb = clamp01(
        distFactor *
          lightFactor *
          (0.32 + 0.68 * attention) *
          (0.45 + 0.55 * acuity) *
          (0.45 + 0.55 * conspicuousness),
      );

      const rDetect = rngFor(options.seed, 'witness_detect', event.id, id);
      if (rDetect() >= detectProb) continue;

      const jitter = (rDetect() - 0.5) * 0.14;
      confidence = clamp01(
        Math.max(
          0.12,
          distFactor * lightFactor * (0.45 + 0.55 * acuity) * (0.55 + 0.45 * attention) + jitter,
        ),
      );
    }

    let knowsCulprit = b.knowsCulprit ?? false;
    if (!knowsCulprit && options.world && culpritPerson) {
      if (areDirectKin(person, culpritPerson) || affinity(options.world, person.id, culpritPerson.id) > 0.25) {
        knowsCulprit = true;
      }
    }

    const idThreshold = isVictim ? 0.42 : 0.54;
    const identifiedCulpritId = knowsCulprit && confidence >= idThreshold ? event.culpritId : undefined;
    const description = observeDescription(event.culpritAppearance, confidence, options.seed, event.id, id);

    sightings.push({
      crimeId: event.id,
      witnessId: id,
      isVictim,
      confidence,
      initialConfidence: confidence,
      dayWitnessed: event.when.day,
      lastRefreshedDay: event.when.day,
      ...(identifiedCulpritId !== undefined ? { identifiedCulpritId } : {}),
      description,
      refreshes: 0,
    });
  }

  return sightings;
}
