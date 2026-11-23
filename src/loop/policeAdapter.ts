import { policeKnowledge } from '../crime/police';
import type { CrimeState, PoliceCase } from '../crime/types';
import { bind, parsePlan } from '../plan/index';
import type { PlanFacts } from '../plan/types';
import { rand } from '../sim/rng';
import { registerPlanId, toPlanId } from './ids';
import type {
  IdMapState,
  LoopOfficer,
  LoopPlaceRecord,
  LoopStreetGraph,
  OfficerPlanDirective,
} from './types';

/**
 * Era police milestones matching G009 (`src/police/force.ts`) so `src/loop/` works
 * identically before and after G009 merges into `ccr-52e43ebb-xnmj2e`.
 */
export const LOOP_POLICE_ERA = {
  patrolCarYear: 1928,
  radioYear: 1930,
  handheldRadioYear: 1965,
  detectiveMinPopulation: 350,
} as const;

export function loopForceSize(population: number, year: number): number {
  const rate = year < 1930 ? 1.15 : year < 1960 ? 1.7 : 2.3;
  return Math.max(1, Math.min(30, Math.round((Math.max(50, population) / 1000) * rate)));
}

export function isShiftActive(shift: LoopOfficer['shift'], hour: number): boolean {
  const h = ((hour % 24) + 24) % 24;
  if (shift === 'day') return h >= 6 && h < 14;
  if (shift === 'swing') return h >= 14 && h < 22;
  return h >= 22 || h < 6;
}

function buildAdjacency(graph: LoopStreetGraph): Map<string, string[]> {
  const adj = new Map<string, string[]>();
  for (let i = 0; i < graph.nodes.length; i++) {
    adj.set(graph.nodes[i]!.id, []);
  }
  for (let i = 0; i < graph.edges.length; i++) {
    const e = graph.edges[i]!;
    if (adj.has(e.from) && adj.has(e.to)) {
      adj.get(e.from)!.push(e.to);
      adj.get(e.to)!.push(e.from);
    }
  }
  for (const list of adj.values()) list.sort();
  return adj;
}

function shortestPath(graph: LoopStreetGraph, fromId: string, toId: string): string[] {
  if (fromId === toId) return [fromId];
  const adj = buildAdjacency(graph);
  if (!adj.has(fromId) || !adj.has(toId)) return [toId];
  const visited = new Set<string>([fromId]);
  const parent = new Map<string, string>();
  const q: string[] = [fromId];
  let head = 0;
  while (head < q.length) {
    const cur = q[head++]!;
    const nbs = adj.get(cur) ?? [];
    for (let i = 0; i < nbs.length; i++) {
      const nb = nbs[i]!;
      if (visited.has(nb)) continue;
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
      q.push(nb);
    }
  }
  return [fromId, toId];
}

/**
 * Compiles and validates a G007 plan string via `parsePlan` + `bind` so every plan
 * returned by `src/loop/` is guaranteed valid for `PlanWorld` execution on the phone.
 */
export function validateLoopPlan(
  officer: LoopOfficer,
  source: string,
  allPlacePlanIds: readonly string[],
  personPlanIds: readonly string[] = [],
): string {
  const parsed = parsePlan(source);
  if (!parsed.ok) {
    throw new Error(`Invalid G007 plan for ${officer.id}: ${parsed.error} in "${source}"`);
  }
  const facts: PlanFacts = {
    actorId: officer.planId,
    knownPeople: [officer.planId, ...personPlanIds],
    knownPlaces: allPlacePlanIds,
    knownItems: [`cuffs_${officer.planId}`],
    heldItems: [`cuffs_${officer.planId}`],
    knownVehicles: officer.vehiclePlanId ? [officer.vehiclePlanId] : [],
  };
  const bound = bind(parsed.plan, facts);
  if (!bound.ok) {
    const msg = bound.errors.map((e) => `${e.code}: ${e.reason}`).join('; ');
    throw new Error(`Loop plan failed bind for ${officer.id}: ${msg} in "${source}"`);
  }
  return source;
}

/** Creates the initial era-sized police force across all jurisdictions present in `places`/`streetGraph`. */
export function buildInitialOfficers(params: {
  seed: string;
  year: number;
  places: Record<string, LoopPlaceRecord>;
  streetGraph: LoopStreetGraph;
  peopleCount: number;
  idMap: IdMapState;
}): Record<string, LoopOfficer> {
  const { seed, year, places, streetGraph, peopleCount, idMap } = params;
  const officers: Record<string, LoopOfficer> = {};
  const jurIds = new Set<string>();
  const stationByJur = new Map<string, string>();
  const nodesByJur = new Map<string, string[]>();

  for (let i = 0; i < streetGraph.nodes.length; i++) {
    const n = streetGraph.nodes[i]!;
    jurIds.add(n.jurisdictionId);
    let list = nodesByJur.get(n.jurisdictionId);
    if (!list) {
      list = [];
      nodesByJur.set(n.jurisdictionId, list);
    }
    list.push(n.id);
    if (n.kind === 'station' && !stationByJur.has(n.jurisdictionId)) {
      stationByJur.set(n.jurisdictionId, n.id);
    }
  }
  for (const p of Object.values(places)) {
    jurIds.add(p.jurisdictionId);
    if (p.kind === 'police_station' && !stationByJur.has(p.jurisdictionId)) {
      stationByJur.set(p.jurisdictionId, p.nodeId);
    }
  }

  const sortedJurs = Array.from(jurIds).sort();
  const popPerJur = Math.max(50, Math.round(peopleCount / Math.max(1, sortedJurs.length)));
  const shifts: readonly LoopOfficer['shift'][] = ['day', 'swing', 'night'];

  for (let ji = 0; ji < sortedJurs.length; ji++) {
    const jurId = sortedJurs[ji]!;
    const jurNodes = (nodesByJur.get(jurId) ?? []).sort();
    const stationNodeId = stationByJur.get(jurId) ?? jurNodes[0] ?? 'station';
    const patrolPool = jurNodes.filter((n) => n !== stationNodeId);
    const pool = patrolPool.length > 0 ? patrolPool : [stationNodeId];

    // At least 2 officers in a populated capital block so day+swing coverage works cleanly
    const count = Math.max(
      jurNodes.length >= 4 ? 2 : 1,
      loopForceSize(popPerJur, year),
    );
    const carsAllowed = year >= LOOP_POLICE_ERA.patrolCarYear;
    const carShare = !carsAllowed ? 0 : year < 1960 ? 0.45 : 0.65;
    const hasDetective = count >= 2 && popPerJur >= LOOP_POLICE_ERA.detectiveMinPopulation;

    for (let i = 0; i < count; i++) {
      const officerId = `officer:${jurId}:${i}`;
      const planId = registerPlanId(idMap, officerId, 'officer');
      const role: LoopOfficer['role'] =
        count === 1 ? 'constable' : hasDetective && i === count - 1 ? 'detective' : 'patrol';
      const shift: LoopOfficer['shift'] =
        role === 'detective' ? 'day' : count <= 2 ? (i === 0 ? 'day' : 'swing') : shifts[i % 3]!;
      const useCar = carsAllowed && role !== 'constable' && (i + 0.5) / count <= carShare;
      const transport: LoopOfficer['transport'] = useCar ? 'car' : 'foot';
      const vehiclePlanId = useCar ? registerPlanId(idMap, `patrol_car:${jurId}:${i}`, 'car') : undefined;
      const hasRadio =
        transport === 'car'
          ? year >= LOOP_POLICE_ERA.radioYear
          : year >= LOOP_POLICE_ERA.handheldRadioYear;

      // Build deterministic connected beat over the jurisdiction's street nodes
      const beatLen = Math.min(pool.length, Math.max(2, Math.min(5, pool.length)));
      const startIdx = (i * Math.max(1, Math.floor(pool.length / count))) % pool.length;
      const beat: string[] = [];
      for (let b = 0; b < beatLen; b++) {
        const pick = pool[(startIdx + b) % pool.length]!;
        if (!beat.includes(pick)) beat.push(pick);
      }
      if (beat.length === 0) beat.push(stationNodeId);

      officers[officerId] = {
        id: officerId,
        planId,
        jurisdictionId: jurId,
        role,
        shift,
        transport,
        ...(vehiclePlanId ? { vehiclePlanId } : {}),
        hasRadio,
        stationNodeId,
        beatNodeIds: beat,
        assignment: 'patrol',
        routePlaceIds: beat,
      };
    }
  }
  return officers;
}

function moveClause(officer: LoopOfficer, nodePlanId: string, manner?: 'quickly' | 'carefully' | 'quietly'): string {
  const m = manner ? ` ${manner}` : '';
  if (officer.transport === 'car' && officer.vehiclePlanId) {
    return `drive vehicle:${officer.vehiclePlanId}${m} until at(place:${nodePlanId})`;
  }
  return `go place:${nodePlanId}${m}`;
}

/**
 * Generates compiled, bound G007 police plans for all officers based on current
 * `policeKnowledge`, newly reported cases, heat, and time of day.
 */
export function planPoliceForTick(params: {
  seed: string;
  day: number;
  hour: number;
  officers: Record<string, LoopOfficer>;
  respondedCases: Record<string, string>;
  crimeState: CrimeState;
  places: Record<string, LoopPlaceRecord>;
  streetGraph: LoopStreetGraph;
  idMap: IdMapState;
}): {
  officers: Record<string, LoopOfficer>;
  respondedCases: Record<string, string>;
  directives: OfficerPlanDirective[];
  interviewedWitnesses: Array<{ crimeId: string; witnessId: string; officerId: string }>;
} {
  const { seed, day, hour, crimeState, places, streetGraph, idMap } = params;
  const nextOfficers: Record<string, LoopOfficer> = {};
  const nextResponded: Record<string, string> = { ...params.respondedCases };
  const directives: OfficerPlanDirective[] = [];
  const interviewedWitnesses: Array<{ crimeId: string; witnessId: string; officerId: string }> = [];

  const allPlacePlanIds = Array.from(
    new Set([
      ...streetGraph.nodes.map((n) => toPlanId(idMap, n.id, 'node')),
      ...Object.keys(places).map((pid) => toPlanId(idMap, pid, 'place')),
    ]),
  );

  const officerIds = Object.keys(params.officers).sort();
  const byJur = new Map<string, LoopOfficer[]>();
  for (let i = 0; i < officerIds.length; i++) {
    const o = { ...params.officers[officerIds[i]!]! };
    nextOfficers[o.id] = o;
    let list = byJur.get(o.jurisdictionId);
    if (!list) {
      list = [];
      byJur.set(o.jurisdictionId, list);
    }
    list.push(o);
  }

  for (const [jurId, jurOfficers] of byJur.entries()) {
    const snap = policeKnowledge(crimeState, jurId, day);
    const activeCases: PoliceCase[] = snap.cases.slice().sort((a, b) => b.severity - a.severity);
    const hotCase = activeCases[0];
    const hotPlace = hotCase ? places[hotCase.placeId] : undefined;
    const hotNodeId = hotPlace?.nodeId ?? hotCase?.placeId;

    // Pick newly reported cases needing an officer to visit & interview witnesses
    const unresponded: PoliceCase[] = activeCases.filter(
      (c) => !nextResponded[`${jurId}:${c.crimeId}`],
    );

    for (let i = 0; i < jurOfficers.length; i++) {
      const o = jurOfficers[i]!;
      const onDuty = snap.heat >= 60 || isShiftActive(o.shift, hour) || jurOfficers.length <= 2;
      if (!onDuty) {
        o.assignment = 'off_duty';
        o.planSource = undefined;
        o.routePlaceIds = [];
        continue;
      }

      // 1. Respond to an unresponded reported crime (go to scene + interview witnesses)
      if (unresponded.length > 0) {
        const c = unresponded.shift()!;
        const scenePlace = places[c.placeId];
        const sceneNode = scenePlace?.nodeId ?? o.beatNodeIds[0] ?? o.stationNodeId;
        const route = shortestPath(streetGraph, o.stationNodeId, sceneNode);
        const steps = route.map((nid) => moveClause(o, toPlanId(idMap, nid, 'node'), 'quickly'));

        // Gather first-hand witnesses of this crime to interview
        const ledgerByPerson = crimeState.ledger[c.crimeId] ?? {};
        const witnessSimIds = Object.keys(ledgerByPerson)
          .filter((pid) => ledgerByPerson[pid]?.source === 'first_hand')
          .sort()
          .slice(0, 3);
        const witnessPlanIds = witnessSimIds.map((wid) => toPlanId(idMap, wid, 'p'));
        for (let w = 0; w < witnessPlanIds.length; w++) {
          steps.push(`ask person:${witnessPlanIds[w]!} politely`);
          interviewedWitnesses.push({ crimeId: c.crimeId, witnessId: witnessSimIds[w]!, officerId: o.id });
        }

        const planSource = validateLoopPlan(o, steps.join(' then '), allPlacePlanIds, witnessPlanIds);
        o.assignment = 'respond_crime';
        o.targetCrimeId = c.crimeId;
        o.targetPlaceId = c.placeId;
        o.targetPersonId = witnessSimIds[0];
        o.planSource = planSource;
        o.routePlaceIds = route;
        nextResponded[`${jurId}:${c.crimeId}`] = o.id;

        directives.push({
          officerId: o.id,
          planId: o.planId,
          jurisdictionId: jurId,
          role: o.role,
          assignment: 'respond_crime',
          planSource,
          routePlaceIds: route,
          targetPlaceId: c.placeId,
          targetPersonId: witnessSimIds[0],
          crimeId: c.crimeId,
          reason: `Investigate reported ${c.kind} at ${scenePlace?.name ?? c.placeId} and question witnesses`,
        });
        continue;
      }

      // 2. Detective or high-heat stakeout/search near the hot crime block
      if (hotCase && hotNodeId && snap.heat >= 45 && i === jurOfficers.length - 1 && jurOfficers.length >= 2) {
        const route = shortestPath(streetGraph, o.stationNodeId, hotNodeId);
        const targetNodePlan = toPlanId(idMap, hotNodeId, 'node');
        const endHour = (Math.floor(hour) + 2) % 24;
        const hhmm = `${String(endHour).padStart(2, '0')}:00`;
        const steps = [
          ...route.map((nid) => moveClause(o, toPlanId(idMap, nid, 'node'), 'quietly')),
          `hide while watch place:${targetNodePlan} until time(${hhmm})`,
        ];
        const planSource = validateLoopPlan(o, steps.join(' then '), allPlacePlanIds);
        o.assignment = 'stakeout';
        o.targetCrimeId = hotCase.crimeId;
        o.targetPlaceId = hotCase.placeId;
        o.planSource = planSource;
        o.routePlaceIds = route;

        directives.push({
          officerId: o.id,
          planId: o.planId,
          jurisdictionId: jurId,
          role: o.role,
          assignment: 'stakeout',
          planSource,
          routePlaceIds: route,
          targetPlaceId: hotCase.placeId,
          crimeId: hotCase.crimeId,
          reason: `Stake out ${hotPlace?.name ?? hotCase.placeId} while heat is ${snap.heat.toFixed(0)}`,
        });
        continue;
      }

      // 3. Regular beat patrol — biased onto the crime block whenever any open case exists!
      const route = o.beatNodeIds.slice();
      if (hotNodeId && !route.includes(hotNodeId)) {
        route.splice(Math.min(1, route.length), 0, hotNodeId);
      }
      // Deterministic beat rotation by day so patrols vary organically
      const shiftOffset = Math.floor(rand(seed, 'patrol-rot', o.id, day) * route.length);
      const rotated =
        route.length > 1
          ? [...route.slice(shiftOffset), ...route.slice(0, shiftOffset)]
          : route;
      const manner = snap.heat >= 25 ? 'carefully' : undefined;
      const steps = rotated.map((nid) => moveClause(o, toPlanId(idMap, nid, 'node'), manner));
      const planSource = validateLoopPlan(o, steps.join(' then '), allPlacePlanIds);

      o.assignment = 'patrol';
      o.targetCrimeId = hotCase?.crimeId;
      o.targetPlaceId = hotCase?.placeId;
      o.planSource = planSource;
      o.routePlaceIds = rotated;

      directives.push({
        officerId: o.id,
        planId: o.planId,
        jurisdictionId: jurId,
        role: o.role,
        assignment: 'patrol',
        planSource,
        routePlaceIds: rotated,
        ...(hotCase ? { targetPlaceId: hotCase.placeId, crimeId: hotCase.crimeId } : {}),
        reason: hotCase
          ? `Patrol beat including ${hotPlace?.name ?? hotCase.placeId} (active ${hotCase.kind} report)`
          : 'Routine street patrol',
      });
    }
  }

  return {
    officers: nextOfficers,
    respondedCases: nextResponded,
    directives,
    interviewedWitnesses,
  };
}
