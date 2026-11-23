import { bind, createPlanState, parsePlan } from '../plan/index';
import type { Plan, PlanFacts, PlanState } from '../plan/types';
import { shortestNodePath } from './force';
import type { Officer, StreetGraph } from './types';

const VALID_PLAN_ID = /^[a-z][a-z0-9_]*$/;

/** Returns true if `id` satisfies the G007 plan language identifier grammar `[a-z][a-z0-9_]*`. */
export function isPlanSafeId(id: string): boolean {
  return VALID_PLAN_ID.test(id);
}

/** Formats seconds-of-day into strict `HH:MM` (`00:00`..`23:59`) for G007 `time(...)` conditions. */
export function formatPlanClock(secondsOfDay: number): string {
  const wrapped = ((Math.floor(secondsOfDay) % 86400) + 86400) % 86400;
  const hh = Math.floor(wrapped / 3600);
  const mm = Math.floor((wrapped % 3600) / 60);
  return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
}

function moveStep(officer: Officer, nodeId: string, manner?: 'quickly' | 'carefully' | 'quietly'): string {
  const m = manner ? ` ${manner}` : '';
  if (officer.transport === 'car' && officer.vehicleId) {
    return `drive vehicle:${officer.vehicleId}${m} until at(place:${nodeId})`;
  }
  return `go place:${nodeId}${m}`;
}

function dedupeConsecutive(ids: readonly string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < ids.length; i++) {
    const id = ids[i]!;
    if (out.length === 0 || out[out.length - 1] !== id) out.push(id);
  }
  return out;
}

/** Builds a patrol route plan along the officer's street beat (optionally pulling toward a hot crime node). */
export function buildPatrolPlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
  options: { hotNodeId?: string; highHeat?: boolean } = {},
): string {
  const waypoints = officer.beatNodeIds.length > 0 ? officer.beatNodeIds.slice() : [officer.stationNodeId];

  // Under high heat, weave the crime hotspot into the patrol beat
  if (options.hotNodeId && graph.nodes[options.hotNodeId] && !waypoints.includes(options.hotNodeId)) {
    waypoints.splice(Math.min(1, waypoints.length), 0, options.hotNodeId);
  }

  // Expand consecutive waypoints along street-graph edges for realistic street walking/driving
  const expanded: string[] = [];
  let cursor = currentNodeId;
  for (let i = 0; i < waypoints.length; i++) {
    const wp = waypoints[i]!;
    const path = shortestNodePath(graph, cursor, wp, officer.jurisdictionId);
    for (let k = 0; k < path.length; k++) {
      const node = path[k]!;
      if (node !== cursor || expanded.length > 0) expanded.push(node);
    }
    cursor = wp;
  }
  const route = dedupeConsecutive(expanded.length > 0 ? expanded : waypoints);
  const manner = options.highHeat ? 'carefully' : undefined;
  return route.map((n) => moveStep(officer, n, manner)).join(' then ');
}

/** Builds a crime-response plan: move quickly to the scene, then interview each witness (`ask`). */
export function buildRespondCrimePlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
  sceneNodeId: string,
  witnessIds: readonly string[],
): string {
  const path = shortestNodePath(graph, currentNodeId, sceneNodeId, officer.jurisdictionId);
  const moveNodes = dedupeConsecutive(path.length > 1 ? path.slice(1) : [sceneNodeId]);
  const steps: string[] = moveNodes.map((n) => moveStep(officer, n, 'quickly'));

  // Detective inspects the scene briefly before interviewing witnesses
  if (officer.role === 'detective') {
    steps.push(`watch place:${sceneNodeId}`);
  }

  for (let i = 0; i < witnessIds.length; i++) {
    const wid = witnessIds[i]!;
    if (isPlanSafeId(wid)) {
      steps.push(`ask person:${wid} politely`);
    }
  }
  return steps.join(' then ');
}

/** Builds a suspect search plan sweeping last-seen location and known haunts/home. */
export function buildSearchPlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
  searchNodeIds: readonly string[],
): string {
  const targets = searchNodeIds.filter((id) => graph.nodes[id] !== undefined);
  const list = targets.length > 0 ? targets : officer.beatNodeIds;
  const steps: string[] = [];
  let cursor = currentNodeId;

  for (let i = 0; i < list.length; i++) {
    const target = list[i]!;
    const path = shortestNodePath(graph, cursor, target, officer.jurisdictionId);
    const moveNodes = dedupeConsecutive(path.length > 1 ? path.slice(1) : [target]);
    for (let k = 0; k < moveNodes.length; k++) {
      steps.push(moveStep(officer, moveNodes[k]!, 'quickly'));
    }
    steps.push(`watch place:${target}`);
    cursor = target;
  }
  return steps.join(' then ');
}

/** Builds a stakeout plan: travel quietly to a haunt, hide & watch until the stakeout window ends. */
export function buildStakeoutPlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
  hauntNodeId: string,
  untilTimeSec: number,
): string {
  const path = shortestNodePath(graph, currentNodeId, hauntNodeId, officer.jurisdictionId);
  const moveNodes = dedupeConsecutive(path.length > 1 ? path.slice(1) : [hauntNodeId]);
  const steps: string[] = moveNodes.map((n) => moveStep(officer, n, 'quietly'));
  const hhmm = formatPlanClock(untilTimeSec);
  steps.push(`hide while watch place:${hauntNodeId} until time(${hhmm})`);
  return steps.join(' then ');
}

/** Builds a stop-and-question / papers-check plan against a spotted person. */
export function buildQuestionPlanSource(
  personId: string,
  options: { askPapers: boolean; highSeverity: boolean },
): string {
  const manner = options.highSeverity ? 'rudely' : 'politely';
  if (options.askPapers) {
    return `go person:${personId} quickly then warn person:${personId} ${manner} then ask person:${personId} ${manner}`;
  }
  return `go person:${personId} then ask person:${personId} ${manner}`;
}

/**
 * Builds an era-aware backup request plan:
 * - Radio era (`officer.hasRadio === true`): instant radio `signal place:<node>` then pursue/track suspect.
 * - Pre-radio era (`officer.hasRadio === false`): blow whistle `signal`, sprint to station/callbox to `call place:<station>`.
 */
export function buildBackupCallPlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
  sceneNodeId: string,
  suspectId: string,
): string {
  if (officer.hasRadio) {
    return `signal place:${sceneNodeId} then follow person:${suspectId} quickly`;
  }
  const path = shortestNodePath(graph, currentNodeId, officer.stationNodeId, officer.jurisdictionId);
  const moveNodes = dedupeConsecutive(path.length > 1 ? path.slice(1) : [officer.stationNodeId]);
  const steps: string[] = [
    `signal place:${sceneNodeId}`,
    ...moveNodes.map((n) => moveStep(officer, n, 'quickly')),
    `call place:${officer.stationNodeId}`,
  ];
  return steps.join(' then ');
}

/** Builds a pursuit plan (foot chase or patrol-car intercept + hands-up warning + cuffs). */
export function buildChasePlanSource(
  officer: Officer,
  suspectId: string,
  suspectNodeId: string,
): string {
  if (officer.transport === 'car' && officer.vehicleId && isPlanSafeId(suspectNodeId)) {
    return `drive vehicle:${officer.vehicleId} quickly until at(place:${suspectNodeId}) then warn person:${suspectId} rudely then restrain person:${suspectId} with item:${officer.cuffsItemId} quickly`;
  }
  return `go person:${suspectId} quickly then warn person:${suspectId} rudely then restrain person:${suspectId} with item:${officer.cuffsItemId} quickly`;
}

/** Builds an arrest plan (hands up, restrain with cuffs). */
export function buildArrestPlanSource(officer: Officer, suspectId: string): string {
  return `warn person:${suspectId} rudely then restrain person:${suspectId} with item:${officer.cuffsItemId} quickly`;
}

/** Builds a prisoner transport plan returning to the officer's home station. */
export function buildTransportPlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
): string {
  const path = shortestNodePath(graph, currentNodeId, officer.stationNodeId, officer.jurisdictionId);
  const moveNodes = dedupeConsecutive(path.length > 1 ? path.slice(1) : [officer.stationNodeId]);
  return moveNodes.map((n) => moveStep(officer, n, 'carefully')).join(' then ');
}

/** Builds a backup convergence plan to rush toward an alert location. */
export function buildBackupResponsePlanSource(
  officer: Officer,
  graph: StreetGraph,
  currentNodeId: string,
  alertNodeId: string,
  suspectId?: string,
): string {
  const path = shortestNodePath(graph, currentNodeId, alertNodeId, officer.jurisdictionId);
  const moveNodes = dedupeConsecutive(path.length > 1 ? path.slice(1) : [alertNodeId]);
  const steps = moveNodes.map((n) => moveStep(officer, n, 'quickly'));
  if (suspectId && isPlanSafeId(suspectId)) {
    steps.push(`watch person:${suspectId}`);
  } else {
    steps.push(`watch place:${alertNodeId}`);
  }
  return steps.join(' then ');
}

export interface CompiledOfficerPlan {
  planSource: string;
  planState: PlanState;
}

/**
 * Compiles a G007 plan string through `parsePlan` -> `bind` -> `createPlanState`,
 * guaranteeing every officer behaviour is a genuine, bound G007 plan.
 */
const parsedPlanCache = new Map<string, Plan>();

export function compileOfficerPlan(
  officer: Officer,
  source: string,
  graph: StreetGraph,
  timeSec: number,
  extraPeople: readonly string[] = [],
): CompiledOfficerPlan {
  let plan = parsedPlanCache.get(source);
  if (!plan) {
    const parsed = parsePlan(source);
    if (!parsed.ok) {
      throw new Error(`Invalid police plan for ${officer.id}: ${parsed.error} in "${source}"`);
    }
    plan = parsed.plan;
    if (parsedPlanCache.size >= 512) parsedPlanCache.clear();
    parsedPlanCache.set(source, plan);
  }

  const knownPeopleSet = new Set<string>([officer.id]);
  for (let i = 0; i < extraPeople.length; i++) {
    const pid = extraPeople[i]!;
    if (isPlanSafeId(pid)) knownPeopleSet.add(pid);
  }
  if (officer.targetPersonId && isPlanSafeId(officer.targetPersonId)) {
    knownPeopleSet.add(officer.targetPersonId);
  }

  const facts: PlanFacts = {
    actorId: officer.id,
    knownPeople: Array.from(knownPeopleSet),
    knownPlaces: Object.keys(graph.nodes),
    knownItems: [officer.cuffsItemId],
    heldItems: [officer.cuffsItemId],
    knownVehicles: officer.vehicleId ? [officer.vehicleId] : [],
  };

  const bound = bind(plan, facts);
  if (!bound.ok) {
    const summary = bound.errors.map((e) => `${e.code}: ${e.reason}`).join('; ');
    throw new Error(`Police plan failed bind for ${officer.id}: ${summary} in "${source}"`);
  }

  return {
    planSource: source,
    planState: createPlanState(bound.bound, timeSec),
  };
}
