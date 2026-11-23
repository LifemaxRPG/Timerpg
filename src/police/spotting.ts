import { appearanceMatchScore, caseHeatOnDay, recognitionChance } from '../crime/police';
import type { CrimeState, PoliceCase } from '../crime/types';
import { ambientLightAt } from '../crime/witness';
import type { Vec2 } from '../plan/types';
import { rand } from '../sim/rng';
import type { Id, World } from '../sim/types';
import { POLICE_ERA } from './force';
import type {
  ObservedPerson,
  Officer,
  PoliceEraTable,
  SpotAction,
  SpotEvent,
  StreetGraph,
} from './types';

/** Lightweight uniform spatial grid for O(local density) nearby-citizen lookups (numeric keys, zero string allocs). */
export class SpatialPersonIndex {
  private readonly cellSize: number;
  private readonly invCellSize: number;
  private readonly buckets = new Map<number, ObservedPerson[]>();

  constructor(
    people: readonly ObservedPerson[],
    cellSize = 32,
    bounds?: { minX: number; maxX: number; minY: number; maxY: number },
  ) {
    this.cellSize = cellSize;
    this.invCellSize = 1 / cellSize;
    for (let i = 0; i < people.length; i++) {
      const p = people[i]!;
      const px = p.position.x;
      const py = p.position.y;
      if (bounds && (px < bounds.minX || px > bounds.maxX || py < bounds.minY || py > bounds.maxY)) {
        continue;
      }
      const cx = Math.floor(px * this.invCellSize);
      const cy = Math.floor(py * this.invCellSize);
      const key = (cx + 32768) * 65536 + (cy + 32768);
      let list = this.buckets.get(key);
      if (!list) {
        list = [];
        this.buckets.set(key, list);
      }
      list.push(p);
    }
  }

  query(pos: Vec2, radiusM: number): ObservedPerson[] {
    const out: ObservedPerson[] = [];
    const r2 = radiusM * radiusM;
    const minX = Math.floor((pos.x - radiusM) * this.invCellSize);
    const maxX = Math.floor((pos.x + radiusM) * this.invCellSize);
    const minY = Math.floor((pos.y - radiusM) * this.invCellSize);
    const maxY = Math.floor((pos.y + radiusM) * this.invCellSize);

    for (let cx = minX; cx <= maxX; cx++) {
      const rowBase = (cx + 32768) * 65536;
      for (let cy = minY; cy <= maxY; cy++) {
        const list = this.buckets.get(rowBase + (cy + 32768));
        if (!list) continue;
        for (let i = 0; i < list.length; i++) {
          const p = list[i]!;
          const dx = p.position.x - pos.x;
          const dy = p.position.y - pos.y;
          if (dx * dx + dy * dy <= r2) out.push(p);
        }
      }
    }
    return out;
  }
}

/** Computes effective ambient light (0.08..1.0) at a street node for a given hour. */
export function effectiveLightAtNode(
  graph: StreetGraph,
  nodeId: string,
  hour: number,
  ambientOverride?: number,
): number {
  const nodePos = graph.nodes[nodeId]?.position ?? { x: 0, y: 0 };
  const skyLight = ambientOverride !== undefined ? ambientOverride : ambientLightAt(hour, nodePos);
  const localLamp = graph.nodes[nodeId]?.streetLight ?? 0.25;
  return Math.max(0.08, Math.min(1, Math.max(skyLight, skyLight + localLamp * (1 - skyLight) * 0.55)));
}

/**
 * Sight range in meters for an officer given ambient light and transport.
 * Foot officers see slightly more clearly up close; night shrinks sight radius.
 */
export function officerSightRadius(light: number, transport: Officer['transport']): number {
  const base = transport === 'car' ? 30 : 26;
  return base * (0.45 + 0.55 * Math.sqrt(Math.max(0.05, Math.min(1, light))));
}

/**
 * Fast, pre-computed active wanted case summary for one jurisdiction so spotting 2,000 citizens
 * stays well under 1 ms per tick while still delegating the authoritative recognition calculation
 * to G005's `recognitionChance`.
 */
export interface ActiveWantedCase {
  policeCase: PoliceCase;
  heat: number;
}

export function selectBestMatchingCase(
  activeCases: readonly ActiveWantedCase[],
  person: ObservedPerson,
): { matchedCase: ActiveWantedCase; appearanceScore: number } | undefined {
  let best: ActiveWantedCase | undefined;
  let bestScore = -1;
  let bestAppearance = 0;

  for (let i = 0; i < activeCases.length; i++) {
    const ac = activeCases[i]!;
    const c = ac.policeCase;
    const idHit = c.merged.namedSuspectId === person.personId;
    const app = appearanceMatchScore(c.merged.description, person.appearance);
    // Strangers must resemble multiple traits on the wanted poster to draw police scrutiny (lookalikes pass; 1-trait coincidences do not)
    if (!idHit && app < 0.42) continue;
    const combined = idHit ? 0.65 + 0.35 * app : app;
    const weighted = combined * c.merged.overallConfidence * (0.5 + 0.5 * Math.min(1, ac.heat / 40));
    if (weighted > bestScore) {
      bestScore = weighted;
      best = ac;
      bestAppearance = app;
    }
  }

  if (!best || bestScore <= 0.04) return undefined;
  return { matchedCase: best, appearanceScore: bestAppearance };
}

/**
 * Decides how an officer responds when they recognise (or think they recognise) a person
 * above the heat-adjusted spotting threshold, respecting era and crime severity.
 */
export function decideSpotAction(params: {
  officer: Officer;
  person: ObservedPerson;
  policeCase: PoliceCase;
  recognition: number;
  heat: number;
  year: number;
  era?: Readonly<PoliceEraTable>;
}): SpotAction {
  const { officer, person, policeCase, recognition, heat, year } = params;
  const era = params.era ?? POLICE_ERA;

  // Fleeing suspect spotted -> immediate pursuit (with radio backup if available and serious)
  if (person.fleeing) {
    if (officer.hasRadio && policeCase.severity >= 0.65 && !officer.backupRequested) {
      return 'call_backup';
    }
    return 'pursue';
  }

  // Serious violent crime + strong match
  if (policeCase.severity >= 0.70 && recognition >= 0.42) {
    if (officer.hasRadio && !officer.backupRequested) {
      return 'call_backup';
    }
    return 'pursue';
  }

  // Era-gated papers / ID check (1915+ routine papers/licence check, amplified by high heat)
  if (year >= era.papersCheckStartYear && (heat >= 35 || policeCase.severity >= 0.45)) {
    return 'ask_papers';
  }

  return 'question';
}

export interface EvaluateSpottingParams {
  seed: string;
  officer: Officer;
  officerPos: Vec2;
  seen: ObservedPerson;
  activeCases: readonly ActiveWantedCase[];
  jurisdictionHeat: number;
  crimeState: CrimeState;
  world: World;
  graph: StreetGraph;
  day: number;
  year: number;
  hour: number;
  timeSec: number;
  ambientLight?: number;
  era?: Readonly<PoliceEraTable>;
}

/**
 * Evaluates a single officer-sees-person spotting test using G005 `recognitionChance`,
 * deterministic RNG roll, heat-adjusted threshold, and era-gated action choice.
 */
export function evaluateSpotting(params: EvaluateSpottingParams): SpotEvent | null {
  const {
    seed,
    officer,
    officerPos,
    seen,
    activeCases,
    jurisdictionHeat,
    crimeState,
    world,
    graph,
    day,
    year,
    hour,
    timeSec,
    ambientLight,
    era,
  } = params;

  if (seen.inCustody || seen.personId === officer.personId || seen.personId === officer.id) {
    return null;
  }
  if (activeCases.length === 0) return null;

  // Skip recently cleared citizens / lookalikes
  const clearedUntil = officer.clearedUntilSec[seen.personId];
  if (clearedUntil !== undefined && timeSec < clearedUntil) return null;

  const dx = seen.position.x - officerPos.x;
  const dy = seen.position.y - officerPos.y;
  const distance = Math.hypot(dx, dy);
  const light = effectiveLightAtNode(graph, seen.nodeId, hour, ambientLight);
  const maxRange = officerSightRadius(light, officer.transport);
  if (distance > maxRange) return null;

  // Quick pre-screen against active cases so non-matching citizens don't trigger full recognition scans
  const match = selectBestMatchingCase(activeCases, seen);
  if (!match) return null;

  // Authoritative G005 recognitionChance against police knowledge + officer memory
  const chance = recognitionChance(
    crimeState,
    world,
    officer.personId,
    { id: seen.personId, appearance: seen.appearance },
    day,
    {
      jurisdictionId: officer.jurisdictionId,
      isPolice: true,
      light,
      distance,
      year,
    },
  );

  // Heat makes officers more vigilant (lowers minimum floor threshold from 0.22 down to 0.14)
  const threshold = Math.max(0.14, 0.22 - (Math.min(100, jurisdictionHeat) / 100) * 0.08);
  const isLookalike = seen.personId !== match.matchedCase.policeCase.actualCulpritId;
  const tickBucket = Math.floor(timeSec);
  const rolled = rand(seed, 'spot', officer.id, seen.personId, day, tickBucket);

  let action: SpotAction = 'ignore';
  if (chance >= threshold && rolled < chance) {
    action = decideSpotAction({
      officer,
      person: seen,
      policeCase: match.matchedCase.policeCase,
      recognition: chance,
      heat: jurisdictionHeat,
      year,
      era,
    });
  }

  return {
    timeSec,
    day,
    officerId: officer.id,
    jurisdictionId: officer.jurisdictionId,
    seenPersonId: seen.personId,
    matchedCaseId: match.matchedCase.policeCase.crimeId,
    recognitionChance: Math.round(chance * 10000) / 10000,
    rolled: Math.round(rolled * 10000) / 10000,
    threshold: Math.round(threshold * 10000) / 10000,
    action,
    isLookalike,
    distance: Math.round(distance * 100) / 100,
    light: Math.round(light * 1000) / 1000,
  };
}

/** Filters a jurisdiction's police cases into active wanted cases with positive heat. */
export function getActiveWantedCases(cases: readonly PoliceCase[], day: number): ActiveWantedCase[] {
  const out: ActiveWantedCase[] = [];
  for (let i = 0; i < cases.length; i++) {
    const c = cases[i]!;
    const h = caseHeatOnDay(c, day);
    if (h >= 3 || c.severity >= 0.90) {
      out.push({ policeCase: c, heat: h });
    }
  }
  return out;
}

/**
 * Helper to measure deterministic spotting rate over `trials` independent seeds/samples
 * (useful for testing day vs night and original vs changed clothes).
 */
export function measureSpottingRate(
  params: Omit<EvaluateSpottingParams, 'seed' | 'timeSec'> & {
    baseSeed: string;
    trials: number;
  },
): { spotted: number; trials: number; rate: number; meanChance: number } {
  let spotted = 0;
  let chanceSum = 0;
  for (let i = 0; i < params.trials; i++) {
    const ev = evaluateSpotting({
      ...params,
      seed: `${params.baseSeed}:${i}`,
      timeSec: i * 10,
    });
    if (ev) {
      chanceSum += ev.recognitionChance;
      if (ev.action !== 'ignore') spotted++;
    }
  }
  return {
    spotted,
    trials: params.trials,
    rate: params.trials > 0 ? spotted / params.trials : 0,
    meanChance: params.trials > 0 ? chanceSum / params.trials : 0,
  };
}
