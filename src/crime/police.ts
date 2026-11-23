import { clamp01 } from '../sim/rng';
import { ageIn, isAlive, type Id, type World } from '../sim/types';
import { ageVisionFactor } from './witness';
import type {
  BuildBand,
  ClothingColor,
  CrimeEvent,
  CrimeState,
  CulpritAppearance,
  CulpritDescription,
  HairTone,
  HeightBand,
  JurisdictionState,
  MergedDescription,
  PoliceCase,
  PoliceReport,
  VehicleDescription,
} from './types';

function reportWeight(r: PoliceReport): number {
  const sourceMult = r.source === 'first_hand' ? 1.0 : 0.42;
  const victimMult = r.isVictim ? 1.25 : 1.0;
  return Math.max(0.02, clamp01(r.confidence) * sourceMult * victimMult);
}

function pickWeightedWinner<T extends string>(votes: Map<T, number>): { value: T; confidence: number } | undefined {
  let total = 0;
  let bestVal: T | undefined;
  let bestWeight = -1;
  const sortedKeys = [...votes.keys()].sort();
  for (let i = 0; i < sortedKeys.length; i++) {
    const k = sortedKeys[i]!;
    const w = votes.get(k)!;
    total += w;
    if (w > bestWeight) {
      bestWeight = w;
      bestVal = k;
    }
  }
  if (bestVal === undefined || total <= 0) return undefined;
  const consensus = bestWeight / total;
  const evidence = 1 - Math.exp(-bestWeight * 1.35);
  return { value: bestVal, confidence: clamp01(consensus * evidence) };
}

/**
 * Merges multiple police reports into a single consensus culprit description,
 * weighting reports by confidence and prioritizing first-hand sightings over hearsay.
 */
export function mergeDescriptions(reports: readonly PoliceReport[]): MergedDescription {
  if (reports.length === 0) {
    return {
      description: {},
      fieldConfidence: {},
      overallConfidence: 0,
      namedSuspectConfidence: 0,
    };
  }

  const heightVotes = new Map<HeightBand, number>();
  const buildVotes = new Map<BuildBand, number>();
  const hairVotes = new Map<HairTone, number>();
  const clothVotes = new Map<ClothingColor, number>();
  const vehicleVotes = new Map<string, number>();
  const suspectVotes = new Map<Id, number>();

  let totalWeight = 0;
  for (let i = 0; i < reports.length; i++) {
    const r = reports[i]!;
    const w = reportWeight(r);
    totalWeight += w;

    const d = r.description;
    if (d.height) heightVotes.set(d.height, (heightVotes.get(d.height) ?? 0) + w);
    if (d.build) buildVotes.set(d.build, (buildVotes.get(d.build) ?? 0) + w);
    if (d.hair) hairVotes.set(d.hair, (hairVotes.get(d.hair) ?? 0) + w);
    if (d.clothingColor) clothVotes.set(d.clothingColor, (clothVotes.get(d.clothingColor) ?? 0) + w);
    if (d.vehicle) {
      const vk = `${d.vehicle.kind}|${d.vehicle.color}`;
      vehicleVotes.set(vk, (vehicleVotes.get(vk) ?? 0) + w);
    }
    if (r.identifiedCulpritId) {
      suspectVotes.set(r.identifiedCulpritId, (suspectVotes.get(r.identifiedCulpritId) ?? 0) + w);
    }
  }

  const description: CulpritDescription = {};
  const fieldConfidence: Partial<Record<keyof CulpritDescription, number>> = {};

  const h = pickWeightedWinner(heightVotes);
  if (h) { description.height = h.value; fieldConfidence.height = h.confidence; }

  const b = pickWeightedWinner(buildVotes);
  if (b) { description.build = b.value; fieldConfidence.build = b.confidence; }

  const hr = pickWeightedWinner(hairVotes);
  if (hr) { description.hair = hr.value; fieldConfidence.hair = hr.confidence; }

  const cl = pickWeightedWinner(clothVotes);
  if (cl) { description.clothingColor = cl.value; fieldConfidence.clothingColor = cl.confidence; }

  const vh = pickWeightedWinner(vehicleVotes);
  if (vh) {
    const [kind, color] = vh.value.split('|') as [VehicleDescription['kind'], ClothingColor];
    description.vehicle = { kind, color };
    fieldConfidence.vehicle = vh.confidence;
  }

  const named = pickWeightedWinner(suspectVotes);
  const confs = Object.values(fieldConfidence) as number[];
  const avgFieldConf = confs.length > 0 ? confs.reduce((a, c) => a + c, 0) / confs.length : 0;
  const volumeBoost = 1 - Math.exp(-totalWeight * 0.9);
  const overallConfidence = clamp01(Math.max(named ? named.confidence : 0, avgFieldConf * 0.65 + volumeBoost * 0.35));

  return {
    description,
    fieldConfidence,
    overallConfidence,
    ...(named ? { namedSuspectId: named.value } : {}),
    namedSuspectConfidence: named ? named.confidence : 0,
  };
}

/** Purely ingests a delivered `PoliceReport` into a jurisdiction's case file. */
export function ingestPoliceReport(
  jur: JurisdictionState | undefined,
  crime: CrimeEvent,
  report: PoliceReport,
): JurisdictionState {
  const prevJur: JurisdictionState = jur ?? { jurisdictionId: report.jurisdictionId, cases: {} };
  const prevCase = prevJur.cases[crime.id];
  const reports = prevCase ? [...prevCase.reports, report] : [report];
  let merged = mergeDescriptions(reports);
  const firstHandCount = (prevCase?.firstHandCount ?? 0) + (report.source === 'first_hand' ? 1 : 0);

  // Preserve physical evidence suspect naming if stronger than witness consensus
  if (prevCase?.evidenceMatches && prevCase.evidenceMatches.length > 0) {
    let bestPhysSuspect: Id | undefined;
    let bestPhysConf = 0;
    for (let i = 0; i < prevCase.evidenceMatches.length; i++) {
      const m = prevCase.evidenceMatches[i]!;
      if (m.confidence > bestPhysConf) {
        bestPhysConf = m.confidence;
        bestPhysSuspect = m.suspectId;
      }
    }
    if (bestPhysSuspect && bestPhysConf >= 0.55 && bestPhysConf > merged.namedSuspectConfidence) {
      merged = {
        ...merged,
        namedSuspectId: bestPhysSuspect,
        namedSuspectConfidence: bestPhysConf,
        overallConfidence: Math.max(merged.overallConfidence, bestPhysConf),
      };
    }
  }

  const nextCase: PoliceCase = {
    crimeId: crime.id,
    kind: crime.kind,
    severity: crime.severity,
    placeId: crime.placeId,
    crimeDay: crime.when.day,
    firstReportedDay: prevCase ? Math.min(prevCase.firstReportedDay, report.receivedDay) : report.receivedDay,
    lastReportedDay: prevCase ? Math.max(prevCase.lastReportedDay, report.receivedDay) : report.receivedDay,
    reportCount: reports.length,
    firstHandCount,
    reports,
    merged,
    actualCulpritId: crime.culpritId,
    ...(prevCase?.traces !== undefined ? { traces: prevCase.traces } : {}),
    ...(prevCase?.evidenceMatches !== undefined ? { evidenceMatches: prevCase.evidenceMatches } : {}),
    ...(prevCase?.timeAnomalies !== undefined ? { timeAnomalies: prevCase.timeAnomalies } : {}),
  };

  return {
    jurisdictionId: prevJur.jurisdictionId,
    cases: {
      ...prevJur.cases,
      [crime.id]: nextCase,
    },
  };
}

/** Half-life (days) of police heat for one case, scaling steeply with severity. */
export function caseHeatHalfLifeDays(severity: number): number {
  const s = clamp01(severity);
  return 7 + 95 * s * s; // ~14 d shoplifting, ~68 d robbery, ~102 d murder
}

/** Current heat contribution (0..100) of a single `PoliceCase` on `day`. */
export function caseHeatOnDay(c: PoliceCase, day: number): number {
  let latestActivityDay = c.lastReportedDay;
  if (c.evidenceMatches) {
    for (let i = 0; i < c.evidenceMatches.length; i++) {
      const md = c.evidenceMatches[i]!.matchedDay;
      if (md > latestActivityDay) latestActivityDay = md;
    }
  }
  const elapsed = Math.max(0, day - latestActivityDay);
  const halfLife = caseHeatHalfLifeDays(c.severity);
  const decay = Math.pow(0.5, elapsed / halfLife);
  const namedBoost = c.merged.namedSuspectId ? 1.15 : 1.0;
  const baseHeat =
    (14 + 72 * Math.pow(clamp01(c.severity), 1.15)) *
    (0.45 + 0.55 * clamp01(c.merged.overallConfidence)) *
    namedBoost;
  return baseHeat * decay;
}

export interface PoliceKnowledgeSnapshot {
  jurisdictionId: string;
  day: number;
  /** Aggregate heat in [0, 100], decaying over time. */
  heat: number;
  cases: PoliceCase[];
  /** Distinct wanted suspect ids (named or active APB with sufficient heat). */
  wantedIds: Id[];
}

/**
 * Queries what the police of `jurisdictionId` currently know on `day`:
 * their open cases, aggregate decaying heat (0..100), and wanted suspect ids.
 */
export function policeKnowledge(
  state: CrimeState,
  jurisdictionId: string,
  day: number,
): PoliceKnowledgeSnapshot {
  const jur = state.jurisdictions[jurisdictionId];
  if (!jur) {
    return { jurisdictionId, day, heat: 0, cases: [], wantedIds: [] };
  }

  const caseIds = Object.keys(jur.cases).sort();
  const cases: PoliceCase[] = [];
  const wantedSet = new Set<Id>();
  let totalHeat = 0;

  for (let i = 0; i < caseIds.length; i++) {
    const c = jur.cases[caseIds[i]!]!;
    if (c.firstReportedDay > day) continue;
    const h = caseHeatOnDay(c, day);
    if (h < 1.5 && c.severity < 0.90) continue;
    cases.push(c);
    totalHeat += h;

    const activeWanted =
      c.merged.namedSuspectId !== undefined
        ? h >= 5 || c.severity >= 0.90
        : h >= 10 && c.merged.overallConfidence >= 0.12;
    if (activeWanted) {
      wantedSet.add(c.merged.namedSuspectId ?? c.actualCulpritId);
    }
  }

  return {
    jurisdictionId,
    day,
    heat: Math.min(100, Math.round(totalHeat * 100) / 100),
    cases,
    wantedIds: [...wantedSet].sort(),
  };
}

/** Returns whether `culpritId` is actively wanted in `jurisdictionId` on `day`. */
export function isWanted(
  state: CrimeState,
  culpritId: Id,
  jurisdictionId: string,
  day: number,
): boolean {
  const snap = policeKnowledge(state, jurisdictionId, day);
  for (let i = 0; i < snap.cases.length; i++) {
    const c = snap.cases[i]!;
    const h = caseHeatOnDay(c, day);
    if (c.merged.namedSuspectId === culpritId && (h >= 5 || c.severity >= 0.90)) {
      return true;
    }
    if (
      c.actualCulpritId === culpritId &&
      c.merged.overallConfidence >= 0.12 &&
      (h >= 10 || c.severity >= 0.90)
    ) {
      return true;
    }
  }
  return false;
}

/** Returns whether police specifically know `culpritId` by name in `jurisdictionId` on `day`. */
export function isNamedSuspect(
  state: CrimeState,
  culpritId: Id,
  jurisdictionId: string,
  day: number,
): boolean {
  const snap = policeKnowledge(state, jurisdictionId, day);
  return snap.cases.some((c) => c.merged.namedSuspectId === culpritId);
}

/**
 * Compares a remembered `CulpritDescription` against a culprit's current `CulpritAppearance`
 * in [0, 1]. Permanent physical traits (height, hair, build) carry weight even if the culprit
 * changes clothes or ditches a vehicle, while clothing/vehicle provide strong extra signal.
 */
export function appearanceMatchScore(
  desc: CulpritDescription,
  current: CulpritAppearance,
): number {
  const weights: Array<{ present: boolean; weight: number; match: number }> = [
    {
      present: desc.height !== undefined,
      weight: 0.26,
      match: desc.height === current.height ? 1 : 0,
    },
    {
      present: desc.hair !== undefined,
      weight: 0.24,
      match: desc.hair === current.hair ? 1 : 0,
    },
    {
      present: desc.build !== undefined,
      weight: 0.18,
      match: desc.build === current.build ? 1 : 0,
    },
    {
      present: desc.clothingColor !== undefined,
      weight: 0.20,
      match: desc.clothingColor === current.clothingColor ? 1 : 0,
    },
    {
      present: desc.vehicle !== undefined,
      weight: 0.22,
      match:
        desc.vehicle && current.vehicle
          ? (desc.vehicle.kind === current.vehicle.kind ? 0.6 : 0) +
            (desc.vehicle.color === current.vehicle.color ? 0.4 : 0)
          : 0,
    },
  ];

  let totalWeight = 0;
  let matchedWeight = 0;
  let presentCount = 0;
  for (let i = 0; i < weights.length; i++) {
    const w = weights[i]!;
    if (!w.present) continue;
    presentCount++;
    totalWeight += w.weight;
    matchedWeight += w.weight * w.match;
  }
  if (presentCount === 0 || totalWeight <= 0) return 0;

  const accuracy = matchedWeight / totalWeight;
  // More corroborating traits give stronger certainty than a single trait alone
  const coverage = Math.min(1, 0.35 + 0.65 * Math.min(1, totalWeight / 0.85));
  return clamp01(accuracy * coverage);
}

export interface RecognitionTarget {
  id: Id;
  appearance: CulpritAppearance;
}

export interface RecognitionOptions {
  /** Jurisdiction where the encounter happens (gives officers/locals access to active APBs). */
  jurisdictionId?: string;
  /** Whether the NPC is a police officer actively looking for wanted descriptions. */
  isPolice?: boolean;
  /** Ambient light at encounter (0..1, default 1.0). */
  light?: number;
  /** Distance in meters at encounter (default 8 m). */
  distance?: number;
  /** Simulation year (defaults to `world.year`). */
  year?: number;
}

/**
 * Probability (0..1) that `npcId` recognizes `culprit` on `day` from:
 * 1. Personal first-hand memory or second-hand gossip in `state.ledger`;
 * 2. Active police case files / wanted descriptions in `options.jurisdictionId`.
 */
export function recognitionChance(
  state: CrimeState,
  world: World,
  npcId: Id,
  culprit: RecognitionTarget,
  day: number,
  options: RecognitionOptions = {},
): number {
  if (npcId === culprit.id) return 0;
  const npc = world.people[npcId];
  const year = options.year ?? world.year;
  if (npc && !isAlive(npc)) return 0;

  const acuity = npc ? ageVisionFactor(Math.max(0, ageIn(npc, year))) : 1.0;
  const light = clamp01(options.light ?? 1.0);
  const dist = Math.max(0, options.distance ?? 8);
  const distScale = clamp01(1 - dist / 45);
  const envScale = (0.35 + 0.65 * Math.sqrt(light)) * (0.40 + 0.60 * distScale) * (0.50 + 0.50 * acuity);

  let bestChance = 0;

  // 1. Personal memory (first-hand sighting or second-hand rumor)
  const crimeIds = Object.keys(state.ledger);
  for (let i = 0; i < crimeIds.length; i++) {
    const cid = crimeIds[i]!;
    const acc = state.ledger[cid]?.[npcId];
    if (!acc) continue;
    const match = appearanceMatchScore(acc.description, culprit.appearance);
    const idMatch = acc.identifiedCulpritId === culprit.id;
    const sourceMult = acc.source === 'first_hand' ? 1.0 : 0.62;
    let p = acc.confidence * sourceMult * (idMatch ? 0.62 + 0.38 * match : match * 0.88) * envScale;
    if (acc.isVictim) p = clamp01(p * 1.18);
    if (p > bestChance) bestChance = p;
  }

  // 2. Jurisdiction police files / wanted notices
  if (options.jurisdictionId) {
    const snap = policeKnowledge(state, options.jurisdictionId, day);
    const alertMult = options.isPolice ? 1.0 : 0.38;
    for (let i = 0; i < snap.cases.length; i++) {
      const c = snap.cases[i]!;
      const h = caseHeatOnDay(c, day);
      if (h < 3) continue;
      const heatFactor = clamp01(0.30 + 0.70 * Math.min(1, h / 45));
      const match = appearanceMatchScore(c.merged.description, culprit.appearance);
      const idMatch = c.merged.namedSuspectId === culprit.id;
      const base =
        c.merged.overallConfidence *
        (idMatch ? 0.55 + 0.45 * match : match * 0.90) *
        heatFactor *
        alertMult *
        envScale;
      if (base > bestChance) bestChance = base;
    }
  }

  return clamp01(bestChance);
}
