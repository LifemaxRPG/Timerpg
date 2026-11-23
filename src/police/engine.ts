import { refreshWitnessMemory, knowersOf } from '../crime/advance';
import { caseStrength } from '../crime/evidence';
import { policeKnowledge, type PoliceKnowledgeSnapshot } from '../crime/police';
import type { CrimeState, PoliceCase } from '../crime/types';
import { step } from '../plan/executor';
import type { PlanState, Vec2 } from '../plan/types';
import { rand } from '../sim/rng';
import type { Id } from '../sim/types';
import { POLICE_ERA, heatEffortMultiplier, isOnShift } from './force';
import {
  buildArrestPlanSource,
  buildBackupCallPlanSource,
  buildBackupResponsePlanSource,
  buildChasePlanSource,
  buildPatrolPlanSource,
  buildQuestionPlanSource,
  buildRespondCrimePlanSource,
  buildSearchPlanSource,
  buildStakeoutPlanSource,
  buildTransportPlanSource,
  compileOfficerPlan,
  isPlanSafeId,
} from './plans';
import {
  SpatialPersonIndex,
  effectiveLightAtNode,
  evaluateSpotting,
  getActiveWantedCases,
  officerSightRadius,
  type ActiveWantedCase,
} from './spotting';
import type {
  ArrestMethod,
  ArrestRecord,
  BackupAlert,
  EscapeRecord,
  InterviewRecord,
  ObservedPerson,
  Officer,
  PoliceState,
  PoliceTickContext,
  PoliceTickEvents,
  PoliceTickResult,
  SpotEvent,
  StopOutcome,
} from './types';

function dist(a: Vec2, b: Vec2): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function findNearestNodeId(graph: PoliceTickContext['graph'], pos: Vec2, fallback: string): string {
  let bestId = fallback;
  let bestD2 = Infinity;
  const ids = Object.keys(graph.nodes);
  for (let i = 0; i < ids.length; i++) {
    const id = ids[i]!;
    const n = graph.nodes[id]!;
    const dx = n.position.x - pos.x;
    const dy = n.position.y - pos.y;
    const d2 = dx * dx + dy * dy;
    if (d2 < bestD2) {
      bestD2 = d2;
      bestId = id;
    }
  }
  return bestId;
}

function getOfficerNodeId(officer: Officer, ctx: PoliceTickContext): string {
  const place = ctx.planWorld.getActorPlace(officer.id);
  if (place && ctx.graph.nodes[place]) return place;
  const pos = ctx.planWorld.getActorPosition(officer.id);
  return findNearestNodeId(ctx.graph, pos, officer.stationNodeId);
}

function clonePlanState(ps: PlanState): PlanState {
  return {
    ...ps,
    stepStatuses: ps.stepStatuses.slice(),
    stepStartedAtSec: ps.stepStartedAtSec.slice(),
    observedPlaces: ps.observedPlaces.slice(),
    ...(ps.failure ? { failure: { ...ps.failure } } : {}),
  };
}

function assignPlan(
  officer: Officer,
  source: string,
  ctx: PoliceTickContext,
  extraPeople: readonly string[] = [],
): Officer {
  const compiled = compileOfficerPlan(officer, source, ctx.graph, ctx.planWorld.timeSec, extraPeople);
  return {
    ...officer,
    planSource: compiled.planSource,
    planState: compiled.planState,
  };
}

function startPatrol(
  officer: Officer,
  ctx: PoliceTickContext,
  jurHeat: number,
  hotNodeId?: string,
): Officer {
  const curNode = getOfficerNodeId(officer, ctx);
  const base: Officer = {
    ...officer,
    assignment: 'patrol',
    targetCrimeId: undefined,
    targetPersonId: undefined,
    targetNodeId: undefined,
    pendingWitnessIds: undefined,
    interviewedWitnessIds: undefined,
    targetIsLookalike: undefined,
    backupRequested: undefined,
  };
  const src = buildPatrolPlanSource(base, ctx.graph, curNode, {
    hotNodeId: jurHeat >= 35 ? hotNodeId : undefined,
    highHeat: jurHeat >= 50,
  });
  return assignPlan(base, src, ctx);
}

function isInterruptibleForCrime(o: Officer): boolean {
  return o.assignment === 'patrol' || o.assignment === 'search_suspect';
}

function isAvailableForBackup(o: Officer): boolean {
  return o.assignment === 'patrol' || o.assignment === 'search_suspect' || o.assignment === 'stakeout';
}

function canSpotInAssignment(a: Officer['assignment']): boolean {
  return a === 'patrol' || a === 'search_suspect' || a === 'stakeout' || a === 'backup';
}

/**
 * Advances the police force simulation by one tick (`ctx.dt` seconds), pure and deterministic.
 *
 * Every officer's duty runs as a bound G007 plan stepped through `ctx.planWorld`:
 * 1. Dispatches on-duty officers (and detectives for serious cases) to newly reported crimes
 *    and interviews witnesses (refreshing witness memory in `crimeState` via G005).
 * 2. Allocates high-heat searches & stakeouts at last-seen locations and suspect haunts.
 * 3. Steps every active officer's G007 `PlanState`.
 * 4. Runs spatial spotting tests via G005 `recognitionChance` (including lookalike mistakes,
 *    papers checks by era, radio vs pre-radio backup, pursuit, cross-jurisdiction escapes,
 *    and arrest + search + cuff + transport to station).
 */
export function stepPoliceTick(state: PoliceState, ctx: PoliceTickContext): PoliceTickResult {
  const timeSec = ctx.planWorld.timeSec;
  const era = ctx.policeEra ?? POLICE_ERA;
  const spotIntervalSec = ctx.spotIntervalSec ?? 2.0;

  let crimeState: CrimeState = ctx.crimeState;
  let nextSeq = state.nextSeq;
  const nextId = (prefix: string): string => `${prefix}_${nextSeq++}`;

  const nextOfficers: Record<string, Officer> = {};
  const officerIds = Object.keys(state.officers).sort();
  for (let i = 0; i < officerIds.length; i++) {
    const id = officerIds[i]!;
    const o = state.officers[id]!;
    nextOfficers[id] = {
      ...o,
      ...(o.planState ? { planState: clonePlanState(o.planState) } : {}),
    };
  }

  const nextRespondedCases: Record<string, string> = { ...state.respondedCases };
  let nextBackupAlerts: BackupAlert[] = state.backupAlerts.filter((a) => a.expiresAtSec > timeSec);

  const tickSpots: SpotEvent[] = [];
  const tickStops: StopOutcome[] = [];
  const tickInterviews: InterviewRecord[] = [];
  const tickArrests: ArrestRecord[] = [];
  const tickEscapes: EscapeRecord[] = [];

  // Lazy person lookups (zero O(N) work on ticks where no officer needs it)
  let personMap: Map<Id, ObservedPerson> | undefined;
  const lookupPerson = (pid: Id): ObservedPerson | undefined => {
    if (ctx.getPerson) return ctx.getPerson(pid);
    if (!ctx.people || ctx.people.length === 0) return undefined;
    if (!personMap) {
      personMap = new Map<Id, ObservedPerson>();
      for (let i = 0; i < ctx.people.length; i++) {
        const p = ctx.people[i]!;
        personMap.set(p.personId, p);
      }
    }
    return personMap.get(pid);
  };

  let spatialIndex: SpatialPersonIndex | undefined;
  let spatialIndexBuilt = false;
  const getSpatialIndex = (): SpatialPersonIndex | undefined => {
    if (spatialIndexBuilt) return spatialIndex;
    spatialIndexBuilt = true;
    if (!ctx.people || ctx.people.length === 0 || ctx.peopleNearby) return undefined;
    // Bound grid construction to the bounding box of active officers (+ 36m sight margin)
    let minX = Infinity;
    let maxX = -Infinity;
    let minY = Infinity;
    let maxY = -Infinity;
    for (let i = 0; i < officerIds.length; i++) {
      const o = nextOfficers[officerIds[i]!]!;
      if (o.assignment === 'off_duty') continue;
      const pos = ctx.planWorld.getActorPosition(o.id);
      if (pos.x < minX) minX = pos.x;
      if (pos.x > maxX) maxX = pos.x;
      if (pos.y < minY) minY = pos.y;
      if (pos.y > maxY) maxY = pos.y;
    }
    const pad = 36;
    spatialIndex = new SpatialPersonIndex(
      ctx.people,
      32,
      minX <= maxX ? { minX: minX - pad, maxX: maxX + pad, minY: minY - pad, maxY: maxY + pad } : undefined,
    );
    return spatialIndex;
  };

  // 1. Compute policeKnowledge once per jurisdiction
  const jurIds = new Set<string>();
  for (let i = 0; i < officerIds.length; i++) {
    jurIds.add(nextOfficers[officerIds[i]!]!.jurisdictionId);
  }
  const knowledgeByJur = new Map<string, PoliceKnowledgeSnapshot>();
  const activeCasesByJur = new Map<string, ActiveWantedCase[]>();
  const hotNodeByJur = new Map<string, string | undefined>();
  const alreadyArrestedCrimes = new Set<string>();
  for (let i = 0; i < state.arrests.length; i++) {
    alreadyArrestedCrimes.add(`${state.arrests[i]!.jurisdictionId}:${state.arrests[i]!.crimeId}`);
  }

  for (const jurId of jurIds) {
    const snap = policeKnowledge(crimeState, jurId, ctx.day);
    knowledgeByJur.set(jurId, snap);
    const active = getActiveWantedCases(snap.cases, ctx.day).filter(
      (ac) => !alreadyArrestedCrimes.has(`${jurId}:${ac.policeCase.crimeId}`),
    );
    activeCasesByJur.set(jurId, active);
    if (active.length > 0) {
      let hottest = active[0]!;
      for (let i = 1; i < active.length; i++) {
        if (active[i]!.heat > hottest.heat) hottest = active[i]!;
      }
      const placeId = hottest.policeCase.placeId;
      hotNodeByJur.set(jurId, ctx.graph.nodes[placeId] ? placeId : undefined);
    }
  }

  // 2. Shift check: put off-shift officers off_duty (unless mid-chase/arrest/transport or extreme heat)
  for (let i = 0; i < officerIds.length; i++) {
    const id = officerIds[i]!;
    const o = nextOfficers[id]!;
    const jurHeat = knowledgeByJur.get(o.jurisdictionId)?.heat ?? 0;
    const busyCritical =
      o.assignment === 'chase' ||
      o.assignment === 'arrest' ||
      o.assignment === 'transport' ||
      o.assignment === 'question' ||
      o.assignment === 'call_backup';
    const onDuty =
      ctx.ignoreShifts ||
      busyCritical ||
      jurHeat >= 75 ||
      isOnShift(o.shift, ctx.hour);

    if (!onDuty && o.assignment !== 'off_duty') {
      nextOfficers[id] = {
        ...o,
        assignment: 'off_duty',
        planState: undefined,
        planSource: undefined,
      };
    } else if (onDuty && o.assignment === 'off_duty') {
      nextOfficers[id] = startPatrol(o, ctx, jurHeat, hotNodeByJur.get(o.jurisdictionId));
    }
  }

  // 3. Dispatch newly reported crimes to nearest available officer (detective preferred for serious cases)
  for (const jurId of jurIds) {
    const activeCases = activeCasesByJur.get(jurId) ?? [];
    for (let ci = 0; ci < activeCases.length; ci++) {
      const policeCase = activeCases[ci]!.policeCase;
      const key = `${jurId}:${policeCase.crimeId}`;
      if (nextRespondedCases[key]) continue;

      const sceneNodeId = ctx.graph.nodes[policeCase.placeId]
        ? policeCase.placeId
        : findNearestNodeId(
            ctx.graph,
            crimeState.crimes[policeCase.crimeId]?.position ?? { x: 0, y: 0 },
            Object.keys(ctx.graph.nodes)[0]!,
          );
      const scenePos = ctx.graph.nodes[sceneNodeId]?.position ?? { x: 0, y: 0 };

      // Collect first-hand witnesses of this crime who have plan-safe ids and exist in PlanWorld
      const knowers = knowersOf(crimeState, policeCase.crimeId);
      const witnessIds: string[] = [];
      for (let k = 0; k < knowers.length; k++) {
        const kn = knowers[k]!;
        if (kn.source !== 'first_hand') continue;
        if (!isPlanSafeId(kn.knowerId)) continue;
        if (ctx.planWorld.getEntityPosition({ kind: 'person', id: kn.knowerId })) {
          witnessIds.push(kn.knowerId);
        }
        if (witnessIds.length >= 4) break;
      }

      // Pick best available officer in this jurisdiction (prefer detective when severity >= 0.70)
      let bestOfficer: Officer | undefined;
      let bestScore = Infinity;
      for (let oi = 0; oi < officerIds.length; oi++) {
        const cand = nextOfficers[officerIds[oi]!]!;
        if (cand.jurisdictionId !== jurId || !isInterruptibleForCrime(cand)) continue;
        const d = dist(ctx.planWorld.getActorPosition(cand.id), scenePos);
        const detBonus = policeCase.severity >= 0.70 && cand.role === 'detective' ? -500 : 0;
        const score = d + detBonus;
        if (score < bestScore) {
          bestScore = score;
          bestOfficer = cand;
        }
      }

      if (bestOfficer) {
        const curNode = getOfficerNodeId(bestOfficer, ctx);
        const src = buildRespondCrimePlanSource(bestOfficer, ctx.graph, curNode, sceneNodeId, witnessIds);
        const assigned: Officer = {
          ...bestOfficer,
          assignment: 'respond_crime',
          targetCrimeId: policeCase.crimeId,
          targetNodeId: sceneNodeId,
          pendingWitnessIds: witnessIds,
          interviewedWitnessIds: [],
        };
        nextOfficers[bestOfficer.id] = assignPlan(assigned, src, ctx, witnessIds);
        nextRespondedCases[key] = bestOfficer.id;
      }
    }
  }

  // 4. Heat-driven proactive search & stakeout allocation for named/active wanted suspects
  for (const jurId of jurIds) {
    const snap = knowledgeByJur.get(jurId)!;
    const activeCases = activeCasesByJur.get(jurId) ?? [];
    if (activeCases.length === 0 || snap.heat < 25) continue;

    const topCase = activeCases[0]!.policeCase;
    const suspectId = topCase.merged.namedSuspectId;
    const haunts = suspectId && ctx.knownHauntsOf ? ctx.knownHauntsOf(suspectId) : [];
    const lastSeen = ctx.lastSeenNodeOf?.(topCase.crimeId, suspectId);
    const searchNodes: string[] = [];
    if (lastSeen && ctx.graph.nodes[lastSeen]) searchNodes.push(lastSeen);
    for (let h = 0; h < haunts.length; h++) {
      if (ctx.graph.nodes[haunts[h]!] && !searchNodes.includes(haunts[h]!)) {
        searchNodes.push(haunts[h]!);
      }
    }
    if (searchNodes.length === 0) continue;

    // Count how many officers are already searching/staking out this case
    let activeSearchers = 0;
    let activeStakeouts = 0;
    for (let oi = 0; oi < officerIds.length; oi++) {
      const o = nextOfficers[officerIds[oi]!]!;
      if (o.jurisdictionId !== jurId || o.targetCrimeId !== topCase.crimeId) continue;
      if (o.assignment === 'search_suspect') activeSearchers++;
      if (o.assignment === 'stakeout') activeStakeouts++;
    }

    const desiredSearchers = Math.min(2, Math.floor(heatEffortMultiplier(snap.heat)));
    for (let oi = 0; oi < officerIds.length; oi++) {
      const o = nextOfficers[officerIds[oi]!]!;
      if (o.jurisdictionId !== jurId || o.assignment !== 'patrol') continue;

      if (activeStakeouts === 0 && haunts.length > 0 && (o.role === 'detective' || snap.heat >= 55)) {
        const haunt = haunts[0]!;
        if (ctx.graph.nodes[haunt]) {
          const curNode = getOfficerNodeId(o, ctx);
          const src = buildStakeoutPlanSource(o, ctx.graph, curNode, haunt, timeSec + 1800);
          nextOfficers[o.id] = assignPlan(
            {
              ...o,
              assignment: 'stakeout',
              targetCrimeId: topCase.crimeId,
              targetPersonId: suspectId,
              targetNodeId: haunt,
            },
            src,
            ctx,
          );
          activeStakeouts++;
          continue;
        }
      }

      if (activeSearchers < desiredSearchers) {
        const curNode = getOfficerNodeId(o, ctx);
        const src = buildSearchPlanSource(o, ctx.graph, curNode, searchNodes);
        nextOfficers[o.id] = assignPlan(
          {
            ...o,
            assignment: 'search_suspect',
            targetCrimeId: topCase.crimeId,
            targetPersonId: suspectId,
            targetNodeId: searchNodes[0],
          },
          src,
          ctx,
        );
        activeSearchers++;
      }
    }
  }

  // 5. Dispatch active backup alerts to available officers in that jurisdiction
  for (let ai = 0; ai < nextBackupAlerts.length; ai++) {
    const alert = nextBackupAlerts[ai]!;
    if (timeSec < alert.activeAtSec) continue;
    let assignedCount = 0;
    for (let oi = 0; oi < officerIds.length; oi++) {
      const o = nextOfficers[officerIds[oi]!]!;
      if (
        o.jurisdictionId === alert.jurisdictionId &&
        o.assignment === 'backup' &&
        o.targetCrimeId === alert.crimeId
      ) {
        assignedCount++;
      }
    }
    if (assignedCount >= 2) continue;

    for (let oi = 0; oi < officerIds.length && assignedCount < 2; oi++) {
      const o = nextOfficers[officerIds[oi]!]!;
      if (o.jurisdictionId !== alert.jurisdictionId) continue;
      if (o.id === alert.requestedByOfficerId || !isAvailableForBackup(o)) continue;
      // Officers with radios receive radio alerts anywhere; pre-radio officers only if near station or alert
      const pos = ctx.planWorld.getActorPosition(o.id);
      const nearStation = dist(pos, ctx.graph.nodes[o.stationNodeId]?.position ?? pos) <= 45;
      const nearAlert = dist(pos, alert.position) <= 45;
      if (!o.hasRadio && !nearStation && !nearAlert) continue;

      const curNode = getOfficerNodeId(o, ctx);
      const src = buildBackupResponsePlanSource(o, ctx.graph, curNode, alert.nodeId, alert.suspectId);
      nextOfficers[o.id] = assignPlan(
        {
          ...o,
          assignment: 'backup',
          targetCrimeId: alert.crimeId,
          targetPersonId: alert.suspectId,
          targetNodeId: alert.nodeId,
        },
        src,
        ctx,
        [alert.suspectId],
      );
      assignedCount++;
    }
  }

  // Helper to complete an arrest (search inventory, cuff, record ArrestRecord, begin transport)
  const executeArrest = (
    officer: Officer,
    suspectId: Id,
    crimeId: string,
    method: ArrestMethod,
  ): Officer => {
    const policeCase = crimeState.jurisdictions[officer.jurisdictionId]?.cases[crimeId];
    const inv = ctx.getPersonInventory ? [...ctx.getPersonInventory(suspectId)] : [];
    const contraband: string[] = [];
    for (let k = 0; k < inv.length; k++) {
      const item = inv[k]!;
      const isBad = ctx.isContraband
        ? ctx.isContraband(item, crimeId, policeCase)
        : /stolen|loot|weapon|gun|knife|crowbar|lockpick/.test(item.toLowerCase());
      if (isBad) contraband.push(item);
    }

    const assisting: string[] = [];
    for (let oi = 0; oi < officerIds.length; oi++) {
      const other = nextOfficers[officerIds[oi]!]!;
      if (
        other.id !== officer.id &&
        other.jurisdictionId === officer.jurisdictionId &&
        (other.assignment === 'backup' || other.assignment === 'chase') &&
        other.targetCrimeId === crimeId
      ) {
        assisting.push(other.id);
      }
    }

    const pos = ctx.planWorld.getEntityPosition({ kind: 'person', id: suspectId }) ??
      ctx.planWorld.getActorPosition(officer.id);

    const arrest: ArrestRecord = {
      id: nextId('arrest'),
      crimeId,
      suspectId,
      arrestingOfficerId: officer.id,
      assistingOfficerIds: assisting,
      jurisdictionId: officer.jurisdictionId,
      stationNodeId: officer.stationNodeId,
      arrestDay: ctx.day,
      arrestYear: ctx.year,
      arrestTimeSec: timeSec,
      arrestPosition: { x: pos.x, y: pos.y },
      method,
      searchedInventory: inv,
      seizedContraband: contraband,
      cuffed: true,
      transportedToStation: false,
      ...(policeCase ? { caseStrengthAtArrest: caseStrength(policeCase, ctx.day).combinedScore } : {}),
    };
    tickArrests.push(arrest);
    alreadyArrestedCrimes.add(`${officer.jurisdictionId}:${crimeId}`);
    ctx.onSuspectCustody?.(suspectId, officer.id, 'cuffed');

    const curNode = getOfficerNodeId(officer, ctx);
    const transSrc = buildTransportPlanSource(officer, ctx.graph, curNode);
    const transporting: Officer = {
      ...officer,
      assignment: 'transport',
      targetCrimeId: crimeId,
      targetPersonId: suspectId,
      targetNodeId: officer.stationNodeId,
    };
    ctx.onSuspectCustody?.(suspectId, officer.id, 'transporting');
    return assignPlan(transporting, transSrc, ctx, [suspectId]);
  };

  // 6. Step every on-duty officer's G007 plan + handle state transitions + run spotting
  for (let oi = 0; oi < officerIds.length; oi++) {
    const id = officerIds[oi]!;
    let officer = nextOfficers[id]!;
    if (officer.assignment === 'off_duty') continue;

    const jurHeat = knowledgeByJur.get(officer.jurisdictionId)?.heat ?? 0;
    const hotNodeId = hotNodeByJur.get(officer.jurisdictionId);

    // Ensure every on-duty officer has an active bound G007 plan
    if (!officer.planState) {
      officer = startPatrol(officer, ctx, jurHeat, hotNodeId);
    }

    // Before stepping a chase: check jurisdiction border crossing & car intercept updates
    if ((officer.assignment === 'chase' || officer.assignment === 'call_backup') && officer.targetPersonId && officer.targetCrimeId) {
      const targetObs = lookupPerson(officer.targetPersonId);
      if (targetObs && targetObs.jurisdictionId !== officer.jurisdictionId) {
        const escalatedJur = ctx.escalateJurisdictionOf?.(
          officer.jurisdictionId,
          officer.targetCrimeId,
        );
        tickEscapes.push({
          id: nextId('escape'),
          crimeId: officer.targetCrimeId,
          suspectId: officer.targetPersonId,
          pursuingOfficerId: officer.id,
          fromJurisdictionId: officer.jurisdictionId,
          toJurisdictionId: targetObs.jurisdictionId,
          day: ctx.day,
          timeSec,
          ...(escalatedJur ? { escalatedToJurisdictionId: escalatedJur } : {}),
        });
        officer = startPatrol(officer, ctx, jurHeat, hotNodeId);
        nextOfficers[id] = officer;
        continue;
      }

      // If driving in pursuit and suspect moved to another street node, update vehicle intercept target
      if (
        officer.transport === 'car' &&
        targetObs &&
        targetObs.nodeId !== officer.targetNodeId &&
        ctx.graph.nodes[targetObs.nodeId]
      ) {
        const chaseSrc = buildChasePlanSource(officer, officer.targetPersonId, targetObs.nodeId);
        officer = assignPlan(
          { ...officer, targetNodeId: targetObs.nodeId },
          chaseSrc,
          ctx,
          [officer.targetPersonId],
        );
      }
    }

    // Track pre-step statuses to detect newly completed `ask` / `signal` / `restrain` steps
    const prevStatuses = officer.planState!.stepStatuses.slice();
    const planRes = step(officer.planState!, ctx.planWorld, officer.id, ctx.dt);

    // Handle witness interviews completed during `respond_crime` (integrates G005 `refreshWitnessMemory`!)
    if (officer.assignment === 'respond_crime' && officer.targetCrimeId) {
      const respondCrimeId = officer.targetCrimeId;
      const steps = officer.planState!.plan.steps;
      for (let si = 0; si < steps.length; si++) {
        const st = steps[si]!;
        if (
          st.verb === 'ask' &&
          st.target?.kind === 'person' &&
          prevStatuses[si] !== 'done' &&
          officer.planState!.stepStatuses[si] === 'done'
        ) {
          const witnessId = st.target.id;
          const prevAccount = crimeState.ledger[respondCrimeId]?.[witnessId];
          if (prevAccount) {
            crimeState = refreshWitnessMemory(
              crimeState,
              respondCrimeId,
              witnessId,
              ctx.day,
              'police_interview',
            );
          }
          const interviewed = officer.interviewedWitnessIds
            ? [...officer.interviewedWitnessIds, witnessId]
            : [witnessId];
          officer = { ...officer, interviewedWitnessIds: interviewed };
          tickInterviews.push({
            id: nextId('interview'),
            crimeId: respondCrimeId,
            officerId: officer.id,
            witnessId,
            day: ctx.day,
            timeSec,
            memoryRefreshed: prevAccount !== undefined,
          });
        }
      }
    }

    // Handle radio/pre-radio backup signal firing during `call_backup`
    if (officer.assignment === 'call_backup' && officer.targetCrimeId && officer.targetPersonId) {
      const steps = officer.planState!.plan.steps;
      const radioFired =
        officer.hasRadio &&
        steps.some(
          (st, si) =>
            st.verb === 'signal' &&
            prevStatuses[si] !== 'done' &&
            officer.planState!.stepStatuses[si] === 'done',
        );
      const stationCallFired =
        !officer.hasRadio &&
        (planRes.status === 'completed' ||
          steps.some(
            (st, si) =>
              st.verb === 'call' &&
              prevStatuses[si] !== 'done' &&
              officer.planState!.stepStatuses[si] === 'done',
          ));

      if (radioFired || stationCallFired) {
        const obs = lookupPerson(officer.targetPersonId);
        const alertNode = obs?.nodeId ?? officer.targetNodeId ?? getOfficerNodeId(officer, ctx);
        const alertPos = obs?.position ?? ctx.planWorld.getActorPosition(officer.id);
        nextBackupAlerts.push({
          id: nextId('backup'),
          crimeId: officer.targetCrimeId,
          suspectId: officer.targetPersonId,
          jurisdictionId: officer.jurisdictionId,
          nodeId: alertNode,
          position: { x: alertPos.x, y: alertPos.y },
          requestedByOfficerId: officer.id,
          requestedAtSec: timeSec,
          // Radio backup alerts immediately; pre-1930 station dispatch takes extra time
          activeAtSec: officer.hasRadio ? timeSec : timeSec + 45,
          expiresAtSec: timeSec + 300,
        });
        // Transition directly into pursuit after calling backup
        const chaseSrc = buildChasePlanSource(officer, officer.targetPersonId, alertNode);
        officer = assignPlan(
          {
            ...officer,
            assignment: 'chase',
            targetNodeId: alertNode,
            backupRequested: true,
          },
          chaseSrc,
          ctx,
          [officer.targetPersonId],
        );
        nextOfficers[id] = officer;
        continue;
      }
    }

    // Handle plan completion / failure transitions
    if (planRes.status === 'completed' || planRes.status === 'failed' || planRes.status === 'refused') {
      if (officer.assignment === 'question' && officer.targetPersonId && officer.targetCrimeId) {
        const targetId = officer.targetPersonId;
        const crimeId = officer.targetCrimeId;
        const policeCase = crimeState.jurisdictions[officer.jurisdictionId]?.cases[crimeId];
        const isLookalike =
          officer.targetIsLookalike ??
          (policeCase ? targetId !== policeCase.actualCulpritId : false);
        const inv = ctx.getPersonInventory ? [...ctx.getPersonInventory(targetId)] : [];
        const contraband = inv.filter((item) =>
          ctx.isContraband
            ? ctx.isContraband(item, crimeId, policeCase)
            : /stolen|loot|weapon|gun|knife|crowbar|lockpick/.test(item.toLowerCase()),
        );
        const papersRequested = ctx.year >= era.papersCheckStartYear;

        if (isLookalike && contraband.length === 0) {
          // Innocent lookalike stopped by mistake -> verified & released!
          tickStops.push({
            id: nextId('stop'),
            day: ctx.day,
            timeSec,
            officerId: officer.id,
            jurisdictionId: officer.jurisdictionId,
            personId: targetId,
            caseId: crimeId,
            reason: 'lookalike_cleared',
            isLookalike: true,
            papersRequested,
            contrabandFound: [],
          });
          officer = { ...officer, clearedUntilSec: { ...officer.clearedUntilSec, [targetId]: timeSec + 600 } };
          officer = startPatrol(officer, ctx, jurHeat, hotNodeId);
        } else {
          // Real wanted suspect (or caught carrying contraband)
          const obs = lookupPerson(targetId);
          const flees =
            obs?.fleeing ??
            ctx.willSuspectFlee?.(targetId, crimeId, officer.id) ??
            rand(state.seed, 'flee', crimeId, targetId, ctx.day) < 0.35;

          if (flees) {
            tickStops.push({
              id: nextId('stop'),
              day: ctx.day,
              timeSec,
              officerId: officer.id,
              jurisdictionId: officer.jurisdictionId,
              personId: targetId,
              caseId: crimeId,
              reason: 'fled',
              isLookalike: false,
              papersRequested,
              contrabandFound: contraband,
            });
            const targetNode = obs?.nodeId ?? getOfficerNodeId(officer, ctx);
            const chaseSrc = buildChasePlanSource(officer, targetId, targetNode);
            officer = assignPlan(
              {
                ...officer,
                assignment: 'chase',
                targetNodeId: targetNode,
              },
              chaseSrc,
              ctx,
              [targetId],
            );
          } else {
            tickStops.push({
              id: nextId('stop'),
              day: ctx.day,
              timeSec,
              officerId: officer.id,
              jurisdictionId: officer.jurisdictionId,
              personId: targetId,
              caseId: crimeId,
              reason: 'arrested',
              isLookalike: false,
              papersRequested,
              contrabandFound: contraband,
            });
            const arrestSrc = buildArrestPlanSource(officer, targetId);
            officer = assignPlan(
              { ...officer, assignment: 'arrest' },
              arrestSrc,
              ctx,
              [targetId],
            );
          }
        }
      } else if (
        (officer.assignment === 'chase' || officer.assignment === 'arrest') &&
        officer.targetPersonId &&
        officer.targetCrimeId
      ) {
        if (planRes.status === 'completed') {
          const method: ArrestMethod =
            officer.assignment === 'arrest'
              ? 'surrender'
              : officer.transport === 'car'
                ? 'vehicle_pursuit'
                : 'foot_pursuit';
          officer = executeArrest(officer, officer.targetPersonId, officer.targetCrimeId, method);
        } else {
          // Failed pursuit (e.g. target lost or blocked) -> return to patrol
          officer = startPatrol(officer, ctx, jurHeat, hotNodeId);
        }
      } else if (officer.assignment === 'transport' && officer.targetPersonId && officer.targetCrimeId) {
        // Arrived at station -> mark booking completed on the matching ArrestRecord
        const targetSuspect = officer.targetPersonId;
        const targetCrime = officer.targetCrimeId;
        for (let ai = state.arrests.length - 1; ai >= 0; ai--) {
          const rec = state.arrests[ai]!;
          if (rec.suspectId === targetSuspect && rec.crimeId === targetCrime && !rec.transportedToStation) {
            // Copy updated record into tickArrests (state.arrests will be updated below without mutating input)
            tickArrests.push({ ...rec, transportedToStation: true });
            break;
          }
        }
        ctx.onSuspectCustody?.(targetSuspect, officer.id, 'booked');
        officer = startPatrol(officer, ctx, jurHeat, hotNodeId);
      } else {
        // Completed patrol loop, respond_crime, search_suspect, stakeout, or backup -> resume patrol
        officer = startPatrol(officer, ctx, jurHeat, hotNodeId);
      }
    } else if (
      officer.assignment === 'transport' &&
      officer.targetPersonId
    ) {
      ctx.onSuspectCustody?.(officer.targetPersonId, officer.id, 'transporting');
    }

    // 7. Spotting sweep for officers actively watching the streets
    if (
      canSpotInAssignment(officer.assignment) &&
      timeSec - officer.lastSpotCheckSec >= spotIntervalSec
    ) {
      officer = { ...officer, lastSpotCheckSec: timeSec };
      const activeCases = activeCasesByJur.get(officer.jurisdictionId) ?? [];
      if (activeCases.length > 0) {
        const officerPos = ctx.planWorld.getActorPosition(officer.id);
        const curNode = getOfficerNodeId(officer, ctx);
        const light = effectiveLightAtNode(ctx.graph, curNode, ctx.hour, ctx.ambientLight);
        const radius = officerSightRadius(light, officer.transport);
        const nearby = ctx.peopleNearby
          ? ctx.peopleNearby(officerPos, radius, officer.id)
          : getSpatialIndex()
            ? getSpatialIndex()!.query(officerPos, radius)
            : [];

        for (let ni = 0; ni < nearby.length; ni++) {
          const seen = nearby[ni]!;
          if (seen.jurisdictionId !== officer.jurisdictionId) continue;
          if (!ctx.planWorld.canSee(officer.id, { kind: 'person', id: seen.personId })) continue;

          const spot = evaluateSpotting({
            seed: state.seed,
            officer,
            officerPos,
            seen,
            activeCases,
            jurisdictionHeat: jurHeat,
            crimeState,
            world: ctx.world,
            graph: ctx.graph,
            day: ctx.day,
            year: ctx.year,
            hour: ctx.hour,
            timeSec,
            ambientLight: ctx.ambientLight,
            era,
          });
          if (!spot) continue;
          tickSpots.push(spot);

          if (spot.action === 'ignore') continue;
          if (!isPlanSafeId(seen.personId)) continue;

          const matchedCase = crimeState.jurisdictions[officer.jurisdictionId]?.cases[spot.matchedCaseId];
          if (spot.action === 'call_backup') {
            const callSrc = buildBackupCallPlanSource(
              officer,
              ctx.graph,
              curNode,
              seen.nodeId,
              seen.personId,
            );
            officer = assignPlan(
              {
                ...officer,
                assignment: 'call_backup',
                targetCrimeId: spot.matchedCaseId,
                targetPersonId: seen.personId,
                targetNodeId: seen.nodeId,
                targetIsLookalike: spot.isLookalike,
                backupRequested: true,
              },
              callSrc,
              ctx,
              [seen.personId],
            );
          } else if (spot.action === 'pursue') {
            const chaseSrc = buildChasePlanSource(officer, seen.personId, seen.nodeId);
            officer = assignPlan(
              {
                ...officer,
                assignment: 'chase',
                targetCrimeId: spot.matchedCaseId,
                targetPersonId: seen.personId,
                targetNodeId: seen.nodeId,
                targetIsLookalike: spot.isLookalike,
              },
              chaseSrc,
              ctx,
              [seen.personId],
            );
          } else {
            // 'question' or 'ask_papers'
            const qSrc = buildQuestionPlanSource(seen.personId, {
              askPapers: spot.action === 'ask_papers',
              highSeverity: (matchedCase?.severity ?? 0) >= 0.65,
            });
            officer = assignPlan(
              {
                ...officer,
                assignment: 'question',
                targetCrimeId: spot.matchedCaseId,
                targetPersonId: seen.personId,
                targetNodeId: seen.nodeId,
                targetIsLookalike: spot.isLookalike,
              },
              qSrc,
              ctx,
              [seen.personId],
            );
          }
          break; // One active engagement per officer at a time
        }
      }
    }

    nextOfficers[id] = officer;
  }

  // Merge arrests (updating transportedToStation without duplicating arrest ids)
  let mergedArrests = state.arrests;
  if (tickArrests.length > 0) {
    const byId = new Map<string, ArrestRecord>();
    for (let i = 0; i < state.arrests.length; i++) byId.set(state.arrests[i]!.id, state.arrests[i]!);
    for (let i = 0; i < tickArrests.length; i++) byId.set(tickArrests[i]!.id, tickArrests[i]!);
    mergedArrests = Array.from(byId.values());
  }

  const nextState: PoliceState = {
    seed: state.seed,
    year: ctx.year,
    officers: nextOfficers,
    respondedCases: nextRespondedCases,
    backupAlerts: nextBackupAlerts,
    interviews: tickInterviews.length > 0 ? [...state.interviews, ...tickInterviews] : state.interviews,
    stops: tickStops.length > 0 ? [...state.stops, ...tickStops] : state.stops,
    arrests: mergedArrests,
    escapes: tickEscapes.length > 0 ? [...state.escapes, ...tickEscapes] : state.escapes,
    nextSeq,
  };

  const events: PoliceTickEvents = {
    spots: tickSpots,
    stops: tickStops,
    interviews: tickInterviews,
    arrests: tickArrests,
    escapes: tickEscapes,
  };

  return { state: nextState, crimeState, events };
}
