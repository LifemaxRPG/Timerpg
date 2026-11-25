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
 * Computes exact BFS hop distances from `startId` to every reachable non-border node
 * inside `jurisdictionId`.
 */
function bfsHopDistances(
  adj: Map<string, string[]>,
  graph: StreetGraph,
  jurisdictionId: string,
  startId: string,
): Map<string, number> {
  const dist = new Map<string, number>();
  if (!graph.nodes[startId]) return dist;
  dist.set(startId, 0);
  const queue: string[] = [startId];
  let head = 0;
  while (head < queue.length) {
    const cur = queue[head++]!;
    const d = dist.get(cur)!;
    const nbs = adj.get(cur) ?? [];
    for (let i = 0; i < nbs.length; i++) {
      const nb = nbs[i]!;
      if (dist.has(nb)) continue;
      const node = graph.nodes[nb];
      if (!node || node.jurisdictionId !== jurisdictionId || node.kind === 'border') continue;
      dist.set(nb, d + 1);
      queue.push(nb);
    }
  }
  return dist;
}

/**
 * Partitions a jurisdiction's street nodes into `officerCount` compact patrol beats
 * built strictly by graph distance (nearby corners), independent of node ID numbering.
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
  const jurIds = jurNodes.map((n) => n.id);
  const patrolCandidates = jurIds.filter((id) => id !== stationNodeId);
  const pool = patrolCandidates.length > 0 ? patrolCandidates : jurIds;
  const poolSet = new Set<string>(pool);

  // Pick officer anchors spread across town by graph-distance farthest-point sampling
  // starting near the police station (never slicing raw sorted node IDs).
  const startId = graph.nodes[stationNodeId] ? stationNodeId : pool[0]!;
  const distFromStation = bfsHopDistances(adj, graph, jurisdictionId, startId);
  const anchors: string[] = [];
  const anchorSet = new Set<string>();
  const minDistToAnchors = new Map<string, number>();

  for (let oi = 0; oi < count; oi++) {
    let chosen: string | undefined;
    if (oi === 0) {
      // First anchor: closest patrol corner to the station by graph hops
      let bestDist = Infinity;
      let bestTie = Infinity;
      for (let i = 0; i < pool.length; i++) {
        const id = pool[i]!;
        const d = distFromStation.get(id) ?? 1e6;
        const tie = rand(seed, 'beat_anchor', jurisdictionId, oi, id);
        if (d < bestDist || (d === bestDist && tie < bestTie)) {
          bestDist = d;
          bestTie = tie;
          chosen = id;
        }
      }
    } else {
      // Subsequent anchors: farthest node by graph hops from already-chosen anchors
      const unused = pool.filter((id) => !anchorSet.has(id));
      const candidates = unused.length > 0 ? unused : pool;
      let bestDist = -1;
      let bestTie = Infinity;
      for (let i = 0; i < candidates.length; i++) {
        const id = candidates[i]!;
        const d = minDistToAnchors.get(id) ?? 1e6;
        const tie = rand(seed, 'beat_anchor', jurisdictionId, oi, id);
        if (d > bestDist || (d === bestDist && tie < bestTie)) {
          bestDist = d;
          bestTie = tie;
          chosen = id;
        }
      }
    }
    const anchor = chosen ?? pool[oi % pool.length]!;
    anchors.push(anchor);
    anchorSet.add(anchor);
    const dAnchor = bfsHopDistances(adj, graph, jurisdictionId, anchor);
    for (let i = 0; i < pool.length; i++) {
      const id = pool[i]!;
      const d = dAnchor.get(id);
      if (d !== undefined) {
        const prev = minDistToAnchors.get(id);
        if (prev === undefined || d < prev) minDistToAnchors.set(id, d);
      }
    }
  }

  // Grow each beat around its anchor using nearby graph-connected corners only
  const beats: string[][] = [];
  const claimed = new Set<string>();
  const targetLen = Math.min(
    pool.length,
    Math.max(3, Math.min(6, Math.ceil(pool.length / Math.max(1, count * 0.5)))),
  );

  for (let oi = 0; oi < count; oi++) {
    const anchor = anchors[oi]!;
    const distFromAnchor = bfsHopDistances(adj, graph, jurisdictionId, anchor);
    const beat: string[] = [anchor];
    const used = new Set<string>([anchor]);
    claimed.add(anchor);
    let cur = anchor;

    for (let step = 1; step < targetLen; step++) {
      const directNbs = (adj.get(cur) ?? []).filter(
        (nb) => poolSet.has(nb) && !used.has(nb),
      );
      let next: string | undefined;

      if (directNbs.length > 0) {
        // Prefer unclaimed direct neighbors that stay close to the beat anchor
        let bestScore = Infinity;
        let bestTie = Infinity;
        for (let i = 0; i < directNbs.length; i++) {
          const nb = directNbs[i]!;
          const dAnchor = distFromAnchor.get(nb) ?? 100;
          const claimPenalty = claimed.has(nb) ? 10 : 0;
          const score = claimPenalty + dAnchor;
          const tie = rand(seed, 'beat_step', jurisdictionId, oi, step, nb);
          if (score < bestScore || (score === bestScore && tie < bestTie)) {
            bestScore = score;
            bestTie = tie;
            next = nb;
          }
        }
      } else {
        // Dead end: step to the closest reachable unused corner by graph hops from cur
        const distFromCur = bfsHopDistances(adj, graph, jurisdictionId, cur);
        let bestHops = Infinity;
        let bestAnchorHops = Infinity;
        let bestClaimed = 2;
        let bestTie = Infinity;
        for (const [cand, hops] of distFromCur.entries()) {
          if (!poolSet.has(cand) || used.has(cand)) continue;
          const anchorHops = distFromAnchor.get(cand) ?? 100;
          const isClaimed = claimed.has(cand) ? 1 : 0;
          const tie = rand(seed, 'beat_hop', jurisdictionId, oi, step, cand);
          if (
            hops < bestHops ||
            (hops === bestHops && anchorHops < bestAnchorHops) ||
            (hops === bestHops && anchorHops === bestAnchorHops && isClaimed < bestClaimed) ||
            (hops === bestHops &&
              anchorHops === bestAnchorHops &&
              isClaimed === bestClaimed &&
              tie < bestTie)
          ) {
            bestHops = hops;
            bestAnchorHops = anchorHops;
            bestClaimed = isClaimed;
            bestTie = tie;
            next = cand;
          }
        }
      }

      if (!next) break;
      beat.push(next);
      used.add(next);
      claimed.add(next);
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
