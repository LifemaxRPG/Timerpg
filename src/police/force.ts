import { rand } from '../sim/rng';
import type {
  Officer,
  OfficerRole,
  OfficerShift,
  OfficerTransport,
  PoliceEraTable,
  PoliceState,
  StreetGraph,
  TownPoliceSpec,
} from './types';

/**
 * Single tunable table of historical policing milestones.
 * Adjust years or staffing ratios here (or pass `policeEra` in options) to rebalance eras.
 */
export const POLICE_ERA: Readonly<PoliceEraTable> = {
  patrolCarYear: 1928,
  radioYear: 1930,
  handheldRadioYear: 1965,
  papersCheckStartYear: 1915,
  detectiveMinPopulation: 350,
  earlyOfficersPerThousand: 1.15,
  midOfficersPerThousand: 1.7,
  modernOfficersPerThousand: 2.3,
};

/** Computes deterministic police headcount for a town of `population` in `year`. */
export function forceSizeForTown(
  population: number,
  year: number,
  era: Readonly<PoliceEraTable> = POLICE_ERA,
): number {
  const pop = Math.max(1, Math.floor(population));
  const rate =
    year < 1930
      ? era.earlyOfficersPerThousand
      : year < 1960
        ? era.midOfficersPerThousand
        : era.modernOfficersPerThousand;
  return Math.max(1, Math.round((pop / 1000) * rate));
}

/** Whether patrol cars are in service in `year`. */
export function hasPatrolCarsInEra(
  year: number,
  era: Readonly<PoliceEraTable> = POLICE_ERA,
): boolean {
  return year >= era.patrolCarYear;
}

/** Whether police vehicles have live radio dispatch in `year`. */
export function hasPoliceRadioInEra(
  year: number,
  era: Readonly<PoliceEraTable> = POLICE_ERA,
): boolean {
  return year >= era.radioYear;
}

/** Whether an officer with `transport` carries live radio in `year`. */
export function officerHasRadioInEra(
  transport: OfficerTransport,
  year: number,
  era: Readonly<PoliceEraTable> = POLICE_ERA,
): boolean {
  if (transport === 'car') return year >= era.radioYear;
  return year >= era.handheldRadioYear;
}

/**
 * Builds an adjacency list (sorted neighbor ids for determinism) from a `StreetGraph`.
 * Cached weakly per graph object so repeated queries during ticks are O(1).
 */
const adjacencyCache = new WeakMap<StreetGraph, Map<string, string[]>>();

export function getAdjacency(graph: StreetGraph): Map<string, string[]> {
  const cached = adjacencyCache.get(graph);
  if (cached) return cached;

  const adj = new Map<string, string[]>();
  const nodeIds = Object.keys(graph.nodes).sort();
  for (let i = 0; i < nodeIds.length; i++) {
    adj.set(nodeIds[i]!, []);
  }
  for (let i = 0; i < graph.edges.length; i++) {
    const e = graph.edges[i]!;
    if (!graph.nodes[e.from] || !graph.nodes[e.to]) continue;
    adj.get(e.from)!.push(e.to);
    adj.get(e.to)!.push(e.from);
  }
  for (const list of adj.values()) {
    list.sort();
  }
  adjacencyCache.set(graph, adj);
  return adj;
}

/**
 * Deterministic shortest path (fewest hops, tie-broken lexicographically) between two street nodes.
 * Optional `restrictJurisdictionId` keeps patrol routes inside one jurisdiction.
 */
export function shortestNodePath(
  graph: StreetGraph,
  fromId: string,
  toId: string,
  restrictJurisdictionId?: string,
): string[] {
  if (!graph.nodes[fromId] || !graph.nodes[toId]) return [];
  if (fromId === toId) return [fromId];

  const adj = getAdjacency(graph);
  const visited = new Set<string>([fromId]);
  const parent = new Map<string, string>();
  const queue: string[] = [fromId];
  let head = 0;

  while (head < queue.length) {
    const cur = queue[head++]!;
    const neighbors = adj.get(cur) ?? [];
    for (let i = 0; i < neighbors.length; i++) {
      const nb = neighbors[i]!;
      if (visited.has(nb)) continue;
      if (
        restrictJurisdictionId &&
        nb !== toId &&
        graph.nodes[nb]?.jurisdictionId !== restrictJurisdictionId
      ) {
        continue;
      }
      visited.add(nb);
      parent.set(nb, cur);
      if (nb === toId) {
        const path: string[] = [];
        let p: string | undefined = toId;
        while (p !== undefined) {
          path.push(p);
          p = parent.get(p);
        }
        path.reverse();
        return path;
      }
      queue.push(nb);
    }
  }
  return [fromId, toId];
}

/**
 * Partitions a jurisdiction's street nodes into `officerCount` connected patrol beats
 * starting near `stationNodeId` and fanning out across streets, intersections, and buildings.
 */
export function buildBeats(
  graph: StreetGraph,
  jurisdictionId: string,
  stationNodeId: string,
  officerCount: number,
  seed: string,
): string[][] {
  const jurNodes = Object.values(graph.nodes)
    .filter((n) => n.jurisdictionId === jurisdictionId && n.kind !== 'border')
    .sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));

  if (jurNodes.length === 0) {
    return Array.from({ length: Math.max(1, officerCount) }, () => [stationNodeId]);
  }

  const adj = getAdjacency(graph);
  const count = Math.max(1, officerCount);
  const beats: string[][] = [];

  // Deterministic BFS ordering from station so beats radiate cleanly along streets
  const bfsOrder: string[] = [];
  const seen = new Set<string>();
  const startId = graph.nodes[stationNodeId] ? stationNodeId : jurNodes[0]!.id;
  const q: string[] = [startId];
  seen.add(startId);
  while (q.length > 0) {
    const cur = q.shift()!;
    bfsOrder.push(cur);
    const nbs = adj.get(cur) ?? [];
    for (let i = 0; i < nbs.length; i++) {
      const nb = nbs[i]!;
      if (seen.has(nb)) continue;
      const node = graph.nodes[nb];
      if (!node || node.jurisdictionId !== jurisdictionId || node.kind === 'border') continue;
      seen.add(nb);
      q.push(nb);
    }
  }
  // Include any disconnected nodes in the jurisdiction
  for (let i = 0; i < jurNodes.length; i++) {
    if (!seen.has(jurNodes[i]!.id)) bfsOrder.push(jurNodes[i]!.id);
  }

  // Build one connected loop per officer of 3–6 waypoints
  const patrolCandidates = bfsOrder.filter((id) => id !== stationNodeId);
  const pool = patrolCandidates.length > 0 ? patrolCandidates : bfsOrder;

  for (let oi = 0; oi < count; oi++) {
    const startIdx = (oi * Math.max(1, Math.floor(pool.length / count))) % pool.length;
    const anchor = pool[startIdx]!;
    const beat: string[] = [anchor];
    const used = new Set<string>([anchor]);
    const targetLen = Math.min(pool.length, Math.max(3, Math.min(6, Math.ceil(pool.length / Math.max(1, count * 0.5)))));

    let cur = anchor;
    for (let step = 1; step < targetLen; step++) {
      const nbs = (adj.get(cur) ?? []).filter((nb) => {
        const node = graph.nodes[nb];
        return node && node.jurisdictionId === jurisdictionId && node.kind !== 'border' && !used.has(nb);
      });
      let next: string | undefined;
      if (nbs.length > 0) {
        const pick = Math.floor(rand(seed, 'beat', jurisdictionId, oi, step) * nbs.length);
        next = nbs[pick]!;
      } else {
        // Fallback: next unused node in pool
        for (let k = 0; k < pool.length; k++) {
          const cand = pool[(startIdx + step + k) % pool.length]!;
          if (!used.has(cand)) {
            next = cand;
            break;
          }
        }
      }
      if (!next) break;
      beat.push(next);
      used.add(next);
      cur = next;
    }
    beats.push(beat);
  }

  return beats;
}

/** Shifts: day 06:00–14:00, swing 14:00–22:00, night 22:00–06:00. */
export function isOnShift(shift: OfficerShift, hour: number): boolean {
  const h = ((hour % 24) + 24) % 24;
  switch (shift) {
    case 'day':
      return h >= 6 && h < 14;
    case 'swing':
      return h >= 14 && h < 22;
    case 'night':
      return h >= 22 || h < 6;
  }
}

/** Maps jurisdiction heat (0..100) to an operational effort multiplier in [1.0, 2.2]. */
export function heatEffortMultiplier(heat: number): number {
  const h = Math.max(0, Math.min(100, heat));
  return 1 + (h / 100) * 1.2;
}

/** Sanitizes an arbitrary id into a plan-safe lowercase `[a-z][a-z0-9_]*` identifier. */
export function toPlanId(raw: string, prefix = 'id'): string {
  const clean = raw
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/^_+/, '')
    .replace(/_+$/, '');
  if (clean.length === 0) return prefix;
  return /^[a-z]/.test(clean) ? clean : `${prefix}_${clean}`;
}

export interface CreatePoliceForceOptions {
  seed: string;
  year: number;
  towns: readonly TownPoliceSpec[];
  graph: StreetGraph;
  era?: Readonly<PoliceEraTable>;
  /** Optional explicit mapping of `(jurisdictionId, index)` -> `World.people` id. */
  personIdFor?: (jurisdictionId: string, index: number, officerId: string) => string;
}

/**
 * Purely constructs a deterministic `PoliceState` for one or more jurisdictions
 * sized by town population and era, with connected street beats, shifts, vehicles,
 * radio capabilities, and detectives for serious cases.
 */
export function createPoliceForce(options: CreatePoliceForceOptions): PoliceState {
  const era = options.era ?? POLICE_ERA;
  const officers: Record<string, Officer> = {};
  const shifts: readonly OfficerShift[] = ['day', 'swing', 'night'];

  const sortedTowns = [...options.towns].sort((a, b) =>
    a.jurisdictionId < b.jurisdictionId ? -1 : a.jurisdictionId > b.jurisdictionId ? 1 : 0,
  );

  for (let ti = 0; ti < sortedTowns.length; ti++) {
    const town = sortedTowns[ti]!;
    const count =
      town.officerCountOverride !== undefined
        ? Math.max(1, town.officerCountOverride)
        : forceSizeForTown(town.population, options.year, era);
    const beats = buildBeats(
      options.graph,
      town.jurisdictionId,
      town.stationNodeId,
      count,
      options.seed,
    );
    const jurSlug = toPlanId(town.jurisdictionId, 'jur');
    const carsAllowed = hasPatrolCarsInEra(options.year, era);
    // Share of officers in patrol cars grows from 0 (pre-1928) -> ~45% (1930s-1950s) -> ~65% (1960+)
    const carShare = !carsAllowed ? 0 : options.year < 1960 ? 0.45 : 0.65;
    const hasDetective = count >= 2 && town.population >= era.detectiveMinPopulation;

    for (let i = 0; i < count; i++) {
      const officerId = `officer_${jurSlug}_${i}`;
      const role: OfficerRole =
        count === 1
          ? 'constable'
          : hasDetective && i === count - 1
            ? 'detective'
            : 'patrol';
      // Small towns keep their 1–2 officers on day/swing; larger forces cover all 3 shifts
      const shift: OfficerShift =
        role === 'detective'
          ? 'day'
          : count === 1
            ? 'day'
            : count === 2
              ? i === 0
                ? 'day'
                : 'swing'
              : shifts[i % 3]!;
      const useCar =
        carsAllowed &&
        role !== 'constable' &&
        (role === 'detective' ? options.year >= 1935 : (i + 0.5) / count <= carShare);
      const transport: OfficerTransport = useCar ? 'car' : 'foot';
      const vehicleId = useCar ? `patrol_car_${jurSlug}_${i}` : undefined;
      const hasRadio = officerHasRadioInEra(transport, options.year, era);
      const personId = options.personIdFor
        ? options.personIdFor(town.jurisdictionId, i, officerId)
        : officerId;

      officers[officerId] = {
        id: officerId,
        personId,
        jurisdictionId: town.jurisdictionId,
        role,
        shift,
        transport,
        ...(vehicleId ? { vehicleId } : {}),
        cuffsItemId: `cuffs_${officerId}`,
        hasRadio,
        stationNodeId: town.stationNodeId,
        beatNodeIds: beats[i] ?? [town.stationNodeId],
        assignment: 'patrol',
        lastSpotCheckSec: -999,
        clearedUntilSec: {},
      };
    }
  }

  return {
    seed: options.seed,
    year: options.year,
    officers,
    respondedCases: {},
    backupAlerts: [],
    interviews: [],
    stops: [],
    arrests: [],
    escapes: [],
    nextSeq: 1,
  };
}
