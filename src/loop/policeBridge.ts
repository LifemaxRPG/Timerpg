import { knowersOf, refreshWitnessMemory } from '../crime';
import type { CrimeState, Vec2 } from '../crime/types';
import type { ActionOutcome, ActorTraits, Manner, PlanWorld, ResolvedTargetRef } from '../plan/types';
import {
  POLICE_ERA,
  buildArrestPlanSource,
  buildPatrolPlanSource,
  buildQuestionPlanSource,
  compileOfficerPlan,
  createPoliceForce,
  forceSizeForTown,
  stepPoliceTick,
  type Officer,
  type PoliceState,
  type StreetEdge,
  type StreetGraph,
  type StreetNode,
  type TownPoliceSpec,
} from '../police';
import type { World } from '../sim/types';
import { fromPlanId, registerPlanId, toPlanId } from './ids';
import type {
  IdMapState,
  LoopOfficer,
  LoopPlaceRecord,
  LoopSpotAction,
  LoopStreetGraph,
  OfficerPlanDirective,
  StopPolicyTable,
} from './types';

/**
 * Single tunable policy table governing how officers watch, stop & question,
 * release after questioning, or arrest when spotting a suspect on the street.
 * Override selectively via `LoopInit.stopPolicy`.
 */
export const STOP_POLICY: Readonly<StopPolicyTable> = {
  watchRecognition: 0.14,
  questionRecognition: 0.26,
  namedQuestionRecognition: 0.20,
  directArrestRecognition: 0.72,
  directArrestStrength: 0.48,
  namedDirectArrestRecognition: 0.52,
  namedDirectArrestStrength: 0.55,
  stopArrestStrength: 0.45,
  fleeingBonus: 0.22,
  papersCheckHeat: 25,
  papersCheckSeverity: 0.35,
};

export function resolveStopPolicy(overrides?: Partial<StopPolicyTable>): StopPolicyTable {
  if (!overrides) return { ...STOP_POLICY };
  return {
    watchRecognition: overrides.watchRecognition ?? STOP_POLICY.watchRecognition,
    questionRecognition: overrides.questionRecognition ?? STOP_POLICY.questionRecognition,
    namedQuestionRecognition: overrides.namedQuestionRecognition ?? STOP_POLICY.namedQuestionRecognition,
    directArrestRecognition: overrides.directArrestRecognition ?? STOP_POLICY.directArrestRecognition,
    directArrestStrength: overrides.directArrestStrength ?? STOP_POLICY.directArrestStrength,
    namedDirectArrestRecognition:
      overrides.namedDirectArrestRecognition ?? STOP_POLICY.namedDirectArrestRecognition,
    namedDirectArrestStrength:
      overrides.namedDirectArrestStrength ?? STOP_POLICY.namedDirectArrestStrength,
    stopArrestStrength: overrides.stopArrestStrength ?? STOP_POLICY.stopArrestStrength,
    fleeingBonus: overrides.fleeingBonus ?? STOP_POLICY.fleeingBonus,
    papersCheckHeat: overrides.papersCheckHeat ?? STOP_POLICY.papersCheckHeat,
    papersCheckSeverity: overrides.papersCheckSeverity ?? STOP_POLICY.papersCheckSeverity,
  };
}

/**
 * Converts `LoopStreetGraph` (arbitrary sim node ids) into a G009 `StreetGraph`
 * whose node ids are strictly mapped through `src/loop/ids.ts`.
 */
export function toPoliceStreetGraph(
  streetGraph: LoopStreetGraph,
  idMap: IdMapState,
): StreetGraph {
  const nodes: Record<string, StreetNode> = {};
  for (let i = 0; i < streetGraph.nodes.length; i++) {
    const n = streetGraph.nodes[i]!;
    const planNodeId = toPlanId(idMap, n.id, 'node');
    nodes[planNodeId] = {
      id: planNodeId,
      position: { x: n.position.x, y: n.position.y },
      kind: n.kind,
      jurisdictionId: n.jurisdictionId,
      ...(n.name !== undefined ? { name: n.name } : {}),
      ...(n.streetLight !== undefined ? { streetLight: n.streetLight } : {}),
    };
  }
  const edges: StreetEdge[] = [];
  for (let i = 0; i < streetGraph.edges.length; i++) {
    const e = streetGraph.edges[i]!;
    const from = toPlanId(idMap, e.from, 'node');
    const to = toPlanId(idMap, e.to, 'node');
    if (!nodes[from] || !nodes[to]) continue;
    const a = nodes[from]!.position;
    const b = nodes[to]!.position;
    const distance = e.distance ?? Math.hypot(b.x - a.x, b.y - a.y);
    edges.push({ from, to, distance });
  }
  return { nodes, edges };
}

/** Extracts ordered, reverse-mapped sim-domain place/node ids from a compiled G007 plan string. */
function extractRoutePlaceIds(
  planSource: string | undefined,
  officer: Officer,
  idMap: IdMapState,
): string[] {
  const out: string[] = [];
  if (planSource) {
    const regex = /place:([a-z][a-z0-9_]*)/g;
    let m: RegExpExecArray | null;
    while ((m = regex.exec(planSource)) !== null) {
      const simId = fromPlanId(idMap, m[1]!);
      if (out.length === 0 || out[out.length - 1] !== simId) {
        out.push(simId);
      }
    }
  }
  if (out.length > 0) return out;
  if (officer.targetNodeId) return [fromPlanId(idMap, officer.targetNodeId)];
  return officer.beatNodeIds.map((nid) => fromPlanId(idMap, nid));
}

function buildDirectiveReason(
  officer: Officer,
  places: Record<string, LoopPlaceRecord>,
  idMap: IdMapState,
  targetPlaceId?: string,
): string {
  const placeName = targetPlaceId
    ? places[targetPlaceId]?.name ?? targetPlaceId
    : officer.targetNodeId
      ? fromPlanId(idMap, officer.targetNodeId)
      : 'beat';
  switch (officer.assignment) {
    case 'off_duty':
      return `Off shift (${officer.shift} shift).`;
    case 'respond_crime':
      return `Responding to reported crime at ${placeName}.`;
    case 'search_suspect':
      return `Searching near ${placeName} for wanted suspect.`;
    case 'stakeout':
      return `Watching ${placeName} for suspect return.`;
    case 'question':
      return 'Stopping and questioning matching person.';
    case 'chase':
      return 'Pursuing fleeing suspect.';
    case 'arrest':
      return 'Executing arrest of identified suspect.';
    case 'transport':
      return 'Transporting arrested suspect to the station.';
    case 'call_backup':
      return 'Calling backup for suspect encounter.';
    case 'backup':
      return 'Responding to officer backup call.';
    case 'patrol':
    default:
      return `Routine ${officer.transport} patrol on assigned beat.`;
  }
}

/** Projects G009 `PoliceState.officers` into `LoopSim.officers` with sim-domain node ids. */
export function projectLoopOfficers(
  policeState: PoliceState,
  crimeState: CrimeState,
  places: Record<string, LoopPlaceRecord>,
  idMap: IdMapState,
): Record<string, LoopOfficer> {
  const out: Record<string, LoopOfficer> = {};
  const ids = Object.keys(policeState.officers).sort();
  for (let i = 0; i < ids.length; i++) {
    const id = ids[i]!;
    const o = policeState.officers[id]!;
    const crimePlaceId = o.targetCrimeId ? crimeState.crimes[o.targetCrimeId]?.placeId : undefined;
    const targetPlaceId =
      crimePlaceId ?? (o.targetNodeId ? fromPlanId(idMap, o.targetNodeId) : undefined);
    const routePlaceIds = extractRoutePlaceIds(o.planSource, o, idMap);
    out[id] = {
      id: o.id,
      planId: o.id,
      jurisdictionId: o.jurisdictionId,
      role: o.role,
      shift: o.shift,
      transport: o.transport,
      ...(o.vehicleId !== undefined ? { vehiclePlanId: o.vehicleId } : {}),
      hasRadio: o.hasRadio,
      stationNodeId: fromPlanId(idMap, o.stationNodeId),
      beatNodeIds: o.beatNodeIds.map((nid) => fromPlanId(idMap, nid)),
      assignment: o.assignment,
      ...(o.targetCrimeId !== undefined ? { targetCrimeId: o.targetCrimeId } : {}),
      ...(targetPlaceId !== undefined ? { targetPlaceId } : {}),
      routePlaceIds,
    };
  }
  return out;
}

/** Builds `OfficerPlanDirective[]` from real G009 `PoliceState` (guaranteeing every directive has a valid G007 plan). */
export function directivesFromPoliceState(
  policeState: PoliceState,
  crimeState: CrimeState,
  graph: StreetGraph,
  places: Record<string, LoopPlaceRecord>,
  idMap: IdMapState,
  timeSec: number,
  filterJurisdictionId?: string,
): OfficerPlanDirective[] {
  const directives: OfficerPlanDirective[] = [];
  const ids = Object.keys(policeState.officers).sort();
  for (let i = 0; i < ids.length; i++) {
    const id = ids[i]!;
    const o = policeState.officers[id]!;
    if (filterJurisdictionId && o.jurisdictionId !== filterJurisdictionId) continue;

    let planSource = o.planSource;
    if (!planSource) {
      if (o.assignment === 'off_duty') {
        const restSrc = `watch place:${o.stationNodeId} quietly`;
        planSource = compileOfficerPlan(o, restSrc, graph, timeSec).planSource;
      } else {
        const curNode = o.beatNodeIds[0] ?? o.stationNodeId;
        const patrolSrc = buildPatrolPlanSource(o, graph, curNode);
        planSource = compileOfficerPlan(o, patrolSrc, graph, timeSec).planSource;
      }
    }

    const crimePlaceId = o.targetCrimeId ? crimeState.crimes[o.targetCrimeId]?.placeId : undefined;
    const targetPlaceId =
      crimePlaceId ?? (o.targetNodeId ? fromPlanId(idMap, o.targetNodeId) : undefined);
    const routePlaceIds = extractRoutePlaceIds(planSource, o, idMap);
    const targetPersonId = o.targetPersonId ? fromPlanId(idMap, o.targetPersonId) : undefined;

    directives.push({
      officerId: o.id,
      planId: o.id,
      jurisdictionId: o.jurisdictionId,
      role: o.role,
      assignment: o.assignment,
      planSource,
      routePlaceIds,
      ...(targetPlaceId !== undefined ? { targetPlaceId } : {}),
      ...(targetPersonId !== undefined ? { targetPersonId } : {}),
      ...(o.targetCrimeId !== undefined ? { crimeId: o.targetCrimeId } : {}),
      reason: buildDirectiveReason(o, places, idMap, targetPlaceId),
    });
  }
  return directives;
}

/**
 * Initializes the real G009 police force (`createPoliceForce` from `src/police/force.ts`)
 * across every jurisdiction present in `places` / `streetGraph`, using graph-distance beats.
 */
export function createLoopPolice(params: {
  seed: string;
  year: number;
  startDay: number;
  startHour: number;
  places: Record<string, LoopPlaceRecord>;
  streetGraph: LoopStreetGraph;
  peopleCount: number;
  crimeState: CrimeState;
  idMap: IdMapState;
}): {
  policeState: PoliceState;
  officers: Record<string, LoopOfficer>;
} {
  const { seed, year, startDay, startHour, places, streetGraph, peopleCount, crimeState, idMap } =
    params;
  const graph = toPoliceStreetGraph(streetGraph, idMap);

  const jurIds = new Set<string>();
  const stationByJur = new Map<string, string>();
  const nodeCountByJur = new Map<string, number>();

  for (const node of Object.values(graph.nodes)) {
    jurIds.add(node.jurisdictionId);
    if (node.kind !== 'border') {
      nodeCountByJur.set(node.jurisdictionId, (nodeCountByJur.get(node.jurisdictionId) ?? 0) + 1);
    }
    if (node.kind === 'station' && !stationByJur.has(node.jurisdictionId)) {
      stationByJur.set(node.jurisdictionId, node.id);
    }
  }
  for (const place of Object.values(places)) {
    jurIds.add(place.jurisdictionId);
    if (place.kind === 'police_station' && !stationByJur.has(place.jurisdictionId)) {
      const planStation = toPlanId(idMap, place.nodeId, 'node');
      if (graph.nodes[planStation]) {
        stationByJur.set(place.jurisdictionId, planStation);
      }
    }
  }

  const sortedJurs = Array.from(jurIds).sort();
  const popPerJur = Math.max(50, Math.round(peopleCount / Math.max(1, sortedJurs.length)));
  const towns: TownPoliceSpec[] = [];

  for (let i = 0; i < sortedJurs.length; i++) {
    const jurId = sortedJurs[i]!;
    const fallbackNode =
      Object.values(graph.nodes).find((n) => n.jurisdictionId === jurId && n.kind !== 'border')
        ?.id ??
      Object.keys(graph.nodes).sort()[0] ??
      toPlanId(idMap, `${jurId}:station`, 'station');
    if (!graph.nodes[fallbackNode]) {
      graph.nodes[fallbackNode] = {
        id: fallbackNode,
        position: { x: 0, y: 0 },
        kind: 'station',
        jurisdictionId: jurId,
      };
    }
    const stationNodeId = stationByJur.get(jurId) ?? fallbackNode;
    const nodeCount = nodeCountByJur.get(jurId) ?? 1;
    // Keep at least 2 officers in populated blocks (>=4 nodes) for day+swing coverage, scaling up via real forceSizeForTown
    const officerCountOverride = Math.max(
      nodeCount >= 4 ? 2 : 1,
      forceSizeForTown(popPerJur, year, POLICE_ERA),
    );
    towns.push({
      jurisdictionId: jurId,
      population: popPerJur,
      stationNodeId,
      officerCountOverride,
    });
  }

  const baseState = createPoliceForce({
    seed,
    year,
    towns,
    graph,
    era: POLICE_ERA,
  });

  // Compile initial patrol plans for every officer and register officer ids in idMap
  const timeSec = startDay * 86400 + startHour * 3600;
  const compiledOfficers: Record<string, Officer> = {};
  for (const officer of Object.values(baseState.officers)) {
    registerPlanId(idMap, officer.id, 'officer');
    const curNode = officer.beatNodeIds[0] ?? officer.stationNodeId;
    const patrolSrc = buildPatrolPlanSource(officer, graph, curNode);
    const compiled = compileOfficerPlan(officer, patrolSrc, graph, timeSec);
    compiledOfficers[officer.id] = {
      ...officer,
      planSource: compiled.planSource,
      planState: compiled.planState,
    };
  }

  const policeState: PoliceState = {
    ...baseState,
    officers: compiledOfficers,
  };

  return {
    policeState,
    officers: projectLoopOfficers(policeState, crimeState, places, idMap),
  };
}

const DEFAULT_TRAITS: ActorTraits = { gentle: 0.35, honest: 0.85, lawful: 0.9, brave: 0.75, ambition: 0.5, charm: 0.5, diligence: 0.7 };
const OK_OUTCOME: ActionOutcome = { ok: true };

/**
 * Minimal pure `PlanWorld` adapter around `LoopSim`'s street graph and places so real
 * `stepPoliceTick` (`src/police/engine.ts`) can dispatch, patrol, search, stake out, and
 * step G007 officer plans with sub-millisecond overhead.
 */
function createLoopPlanWorld(
  graph: StreetGraph,
  policeState: PoliceState,
  timeSec: number,
): PlanWorld {
  const actorNode = new Map<string, string>();
  const actorPos = new Map<string, Vec2>();

  for (const officer of Object.values(policeState.officers)) {
    const nodeId =
      (officer.targetNodeId && graph.nodes[officer.targetNodeId] ? officer.targetNodeId : undefined) ??
      (officer.beatNodeIds[0] && graph.nodes[officer.beatNodeIds[0]!]
        ? officer.beatNodeIds[0]!
        : officer.stationNodeId);
    const pos = graph.nodes[nodeId]?.position ?? { x: 0, y: 0 };
    actorNode.set(officer.id, nodeId);
    actorPos.set(officer.id, { x: pos.x, y: pos.y });
  }

  return {
    timeSec,
    getActorPosition(actorId: string): Vec2 {
      return actorPos.get(actorId) ?? { x: 0, y: 0 };
    },
    getActorPlace(actorId: string): string {
      return actorNode.get(actorId) ?? '';
    },
    getEntityPosition(ref: ResolvedTargetRef): Vec2 | undefined {
      if (ref.kind === 'place') return graph.nodes[ref.id]?.position;
      if (ref.kind === 'person') return actorPos.get(ref.id);
      if (ref.kind === 'vehicle') {
        for (const o of Object.values(policeState.officers)) {
          if (o.vehicleId === ref.id) return actorPos.get(o.id);
        }
      }
      return undefined;
    },
    getEntityPlace(ref: ResolvedTargetRef): string | undefined {
      if (ref.kind === 'place') return graph.nodes[ref.id] ? ref.id : undefined;
      if (ref.kind === 'person') return actorNode.get(ref.id);
      return undefined;
    },
    canSee(observerId: string, target: ResolvedTargetRef): boolean {
      const a = actorPos.get(observerId);
      const b = this.getEntityPosition(target);
      if (!a || !b) return false;
      return Math.hypot(b.x - a.x, b.y - a.y) <= 32;
    },
    isAlone(): boolean {
      return false;
    },
    isLocked(): boolean {
      return false;
    },
    isOpen(): boolean {
      return true;
    },
    requiredKeyFor(): string | undefined {
      return undefined;
    },
    hasItem(actorId: string, itemId: string): boolean {
      const o = policeState.officers[actorId];
      return Boolean(o && o.cuffsItemId === itemId);
    },
    getItemOwner(): string | undefined {
      return undefined;
    },
    getItemPrice(): number | undefined {
      return undefined;
    },
    getActorMoney(): number {
      return 0;
    },
    hasHeardSignal(): boolean {
      return false;
    },
    findNearest(): ResolvedTargetRef | undefined {
      return undefined;
    },
    getActorTraits(): ActorTraits {
      return DEFAULT_TRAITS;
    },
    moveActorToward(
      actorId: string,
      dest: Vec2,
      speedMps: number,
      dt: number,
      destPlaceId?: string,
    ): { ok: true; arrived: boolean } {
      const cur = actorPos.get(actorId) ?? { x: 0, y: 0 };
      const dx = dest.x - cur.x;
      const dy = dest.y - cur.y;
      const d = Math.hypot(dx, dy);
      const step = Math.max(0, speedMps * dt);
      if (d <= step + 0.5) {
        actorPos.set(actorId, { x: dest.x, y: dest.y });
        if (destPlaceId && graph.nodes[destPlaceId]) actorNode.set(actorId, destPlaceId);
        return { ok: true, arrived: true };
      }
      const ratio = step / d;
      actorPos.set(actorId, { x: cur.x + dx * ratio, y: cur.y + dy * ratio });
      return { ok: true, arrived: false };
    },
    moveVehicleToward(
      _vehicleId: string,
      driverId: string,
      dest: Vec2,
      speedMps: number,
      dt: number,
      destPlaceId?: string,
    ): { ok: true; arrived: boolean } {
      return this.moveActorToward(driverId, dest, speedMps, dt, destPlaceId) as {
        ok: true;
        arrived: boolean;
      };
    },
    setActorHidden(): void {},
    setActorRole(): void {},
    engagePerson(_a: string, _t: string, _k: 'talk' | 'ask' | 'tell' | 'lie' | 'warn' | 'distract', _m?: Manner): ActionOutcome {
      return OK_OUTCOME;
    },
    emitSignal(): void {},
    transferItem(): ActionOutcome {
      return OK_OUTCOME;
    },
    setDoorOpen(): ActionOutcome {
      return OK_OUTCOME;
    },
    setDoorLocked(): ActionOutcome {
      return OK_OUTCOME;
    },
    useItem(): ActionOutcome {
      return OK_OUTCOME;
    },
    tradeItem(): ActionOutcome {
      return OK_OUTCOME;
    },
    stealItem(): ActionOutcome {
      return OK_OUTCOME;
    },
    combatAction(): ActionOutcome {
      return OK_OUTCOME;
    },
    callTarget(): ActionOutcome {
      return OK_OUTCOME;
    },
    writeNote(): ActionOutcome {
      return OK_OUTCOME;
    },
  };
}

/**
 * Steps the real G009 police engine (`stepPoliceTick` from `src/police/engine.ts`)
 * for the loop's current day/hour and active (unresolved) police cases.
 */
export function stepLoopPolice(params: {
  policeState: PoliceState;
  activeCrimeState: CrimeState;
  fullCrimeState: CrimeState;
  world: World;
  places: Record<string, LoopPlaceRecord>;
  streetGraph: LoopStreetGraph;
  day: number;
  hour: number;
  year: number;
  idMap: IdMapState;
  filterJurisdictionId?: string;
}): {
  policeState: PoliceState;
  crimeState: CrimeState;
  officers: Record<string, LoopOfficer>;
  directives: OfficerPlanDirective[];
} {
  const {
    policeState,
    activeCrimeState,
    fullCrimeState,
    world,
    places,
    streetGraph,
    day,
    hour,
    year,
    idMap,
    filterJurisdictionId,
  } = params;

  const graph = toPoliceStreetGraph(streetGraph, idMap);
  const timeSec = day * 86400 + hour * 3600;

  // Build set of currently active (unresolved) crime ids across jurisdictions
  const activeCrimeIds = new Set<string>();
  for (const jur of Object.values(activeCrimeState.jurisdictions)) {
    for (const cid of Object.keys(jur.cases)) {
      activeCrimeIds.add(cid);
    }
  }

  // Reset any officer still assigned to a now-resolved crime back to patrol before stepping
  let officersChanged = false;
  const preOfficers: Record<string, Officer> = {};
  for (const [id, o] of Object.entries(policeState.officers)) {
    if (o.targetCrimeId && !activeCrimeIds.has(o.targetCrimeId)) {
      officersChanged = true;
      preOfficers[id] = {
        ...o,
        assignment: 'patrol',
        targetCrimeId: undefined,
        targetPersonId: undefined,
        targetNodeId: undefined,
        planSource: undefined,
        planState: undefined,
      };
    } else {
      preOfficers[id] = o;
    }
  }

  const preparedState: PoliceState = officersChanged
    ? { ...policeState, officers: preOfficers }
    : policeState;

  const planWorld = createLoopPlanWorld(graph, preparedState, timeSec);

  const tickRes = stepPoliceTick(preparedState, {
    dt: 1,
    day,
    year,
    hour,
    world,
    crimeState: activeCrimeState,
    graph,
    planWorld,
    policeEra: POLICE_ERA,
    lastSeenNodeOf: (crimeId) => {
      const crime = activeCrimeState.crimes[crimeId];
      if (!crime) return undefined;
      const place = places[crime.placeId];
      if (!place) return undefined;
      const planNode = toPlanId(idMap, place.nodeId, 'node');
      return graph.nodes[planNode] ? planNode : undefined;
    },
    knownHauntsOf: (personId) => {
      // Collect place nodes associated with crimes or workplaces of the suspect
      const out: string[] = [];
      for (const c of Object.values(activeCrimeState.crimes)) {
        if (c.culpritId !== personId) continue;
        const p = places[c.placeId];
        if (!p) continue;
        const pn = toPlanId(idMap, p.nodeId, 'node');
        if (graph.nodes[pn] && !out.includes(pn)) out.push(pn);
      }
      return out;
    },
  });

  // Refresh witness memory on fullCrimeState for any newly responded crimes whose witness ids
  // contain non-plan-safe characters (e.g. `p:martha`) as well as any interviews run by stepPoliceTick.
  let nextFullCrimeState = fullCrimeState;
  for (const iv of tickRes.events.interviews) {
    nextFullCrimeState = refreshWitnessMemory(
      nextFullCrimeState,
      iv.crimeId,
      iv.witnessId,
      day,
      'police_interview',
    );
  }
  for (const [key] of Object.entries(tickRes.state.respondedCases)) {
    if (preparedState.respondedCases[key]) continue;
    const colon = key.indexOf(':');
    const crimeId = colon >= 0 ? key.slice(colon + 1) : key;
    const knowers = knowersOf(nextFullCrimeState, crimeId);
    for (let k = 0; k < knowers.length; k++) {
      const kn = knowers[k]!;
      if (kn.source === 'first_hand') {
        nextFullCrimeState = refreshWitnessMemory(
          nextFullCrimeState,
          crimeId,
          kn.knowerId,
          day,
          'police_interview',
        );
      }
    }
  }

  const directives = directivesFromPoliceState(
    tickRes.state,
    activeCrimeState,
    graph,
    places,
    idMap,
    timeSec,
    filterJurisdictionId,
  );
  const officers = projectLoopOfficers(tickRes.state, activeCrimeState, places, idMap);

  return {
    policeState: tickRes.state,
    crimeState: nextFullCrimeState,
    officers,
    directives,
  };
}

/**
 * Compiles a bound G007 plan string for a spotting action (`watch`, `approach_and_question`,
 * or `arrest`) directly through `src/police/plans.ts`.
 */
export function compileSpotPlan(params: {
  officer: Officer;
  action: Exclude<LoopSpotAction, 'ignore'>;
  targetPlanId: string;
  streetGraph: LoopStreetGraph;
  idMap: IdMapState;
  timeSec: number;
  askPapers: boolean;
  highSeverity: boolean;
}): string {
  const { officer, action, targetPlanId, streetGraph, idMap, timeSec, askPapers, highSeverity } =
    params;
  const graph = toPoliceStreetGraph(streetGraph, idMap);
  const rawSource =
    action === 'arrest'
      ? `go person:${targetPlanId} quickly then ${buildArrestPlanSource(officer, targetPlanId)}`
      : action === 'approach_and_question'
        ? buildQuestionPlanSource(targetPlanId, { askPapers, highSeverity })
        : `watch person:${targetPlanId} carefully`;
  return compileOfficerPlan(officer, rawSource, graph, timeSec, [targetPlanId]).planSource;
}
