import {
  DEFAULT_SEVERITY,
  advanceDay,
  ambientLightAt,
  caseHeatOnDay,
  caseStrength,
  collectCrimeTraces,
  createCrimeState,
  enrollOnFile,
  ingestPoliceReport,
  isWanted,
  knowersOf,
  leaveTraces,
  policeKnowledge,
  recognitionChance,
  recordCrime,
  recordTraces,
  reexamineColdCases,
  refreshWitnessMemory,
  witnessesOf,
  type Bystander,
  type CrimeAccount,
  type CrimeContext,
  type CrimeEvent,
  type CrimeKind,
  type CrimeState,
  type CulpritAppearance,
  type IdentityFileRecord,
  type PoliceCase,
  type PoliceReport,
  type StreetLamp,
  type Vec2,
} from '../crime';
import { clamp01, rand } from '../sim/rng';
import { affinity, pairKey } from '../sim/social';
import type { Business, Id, Person, World } from '../sim/types';
import { cloneIdMap, createIdMap, registerPlanId, toPlanId } from './ids';
import { buildInitialOfficers, planPoliceForTick, validateLoopPlan } from './policeAdapter';
import type {
  ActResult,
  ActTraceSummary,
  AdvanceOptions,
  AdvanceResult,
  ArrestChargeSummary,
  ArrestInput,
  ArrestOutcomeResult,
  AttitudeChange,
  AttitudeResult,
  AttitudeStance,
  CrimeMetaRecord,
  LoopActionEvent,
  LoopInit,
  LoopOfficer,
  LoopPersonInit,
  LoopPersonMeta,
  LoopPlaceRecord,
  LoopSim,
  LoopSpotAction,
  OfficerPlanDirective,
  OfficerSightingInput,
  PawnLeadMatch,
  PawnRecord,
  PlayerFamiliarity,
  PoliceViewCase,
  PoliceViewResult,
  PriorOffenseRecord,
  SceneBystander,
  SpotResult,
  StolenItemRecord,
  WitnessNotice,
  WorkShiftRecord,
} from './types';

const DAY_NAMES: readonly string[] = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
];

const DEFAULT_PLAYER_APPEARANCE: CulpritAppearance = {
  height: 'average',
  build: 'average',
  hair: 'brown',
  clothingColor: 'navy',
};

function dayNameFor(startDay: number, startDayOfWeek: number, day: number): string {
  const offset = day - startDay;
  const idx = (((startDayOfWeek + offset) % 7) + 7) % 7;
  return DAY_NAMES[idx]!;
}

function splitName(p: LoopPersonInit): { first: string; surname: string; display: string } {
  if (p.first && p.surname) {
    return { first: p.first, surname: p.surname, display: p.name ?? `${p.first} ${p.surname}` };
  }
  const raw = (p.name ?? p.id).trim();
  const parts = raw.split(/\s+/);
  if (parts.length === 1) {
    return { first: parts[0]!, surname: 'Local', display: parts[0]! };
  }
  const first = parts[0]!;
  const surname = parts.slice(1).join(' ');
  return { first, surname, display: raw };
}

function areDirectKin(a: Person, b: Person): boolean {
  if (a.spouse === b.id || b.spouse === a.id) return true;
  if (a.parents.includes(b.id) || b.parents.includes(a.id)) return true;
  if (a.children.includes(b.id) || b.children.includes(a.id)) return true;
  if (a.parents.length > 0 && a.parents.some((p) => b.parents.includes(p))) return true;
  return false;
}

function cloneFamiliarity(f?: PlayerFamiliarity): PlayerFamiliarity {
  if (!f) {
    return {
      knowsPlayerName: false,
      trustDelta: 0,
      positiveFacts: [],
      bannedFromPlaceIds: [],
    };
  }
  return {
    knowsPlayerName: f.knowsPlayerName,
    ...(f.playerName !== undefined ? { playerName: f.playerName } : {}),
    trustDelta: f.trustDelta,
    positiveFacts: f.positiveFacts.slice(),
    bannedFromPlaceIds: f.bannedFromPlaceIds.slice(),
  };
}

function resolvePlaceNodeId(
  placeId: string,
  explicitNodeId: string | undefined,
  position: Vec2,
  nodes: readonly { id: string; position: Vec2 }[],
): string {
  if (explicitNodeId) return explicitNodeId;
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i]!.id === placeId) return placeId;
  }
  if (nodes.length === 0) return placeId;
  let best = nodes[0]!.id;
  let bestDist = Infinity;
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]!;
    const d = Math.hypot(n.position.x - position.x, n.position.y - position.y);
    if (d < bestDist) {
      bestDist = d;
      best = n.id;
    }
  }
  return best;
}

function buildCrimeContext(sim: LoopSim, weather?: AdvanceOptions['weather']): CrimeContext {
  const defaultTown =
    Object.values(sim.places)[0]?.townId ??
    Object.values(sim.places)[0]?.jurisdictionId ??
    'capital';
  const defaultJur = Object.values(sim.places)[0]?.jurisdictionId ?? 'capital';
  return {
    year: sim.year,
    townOf: (personId: Id) => sim.peopleMeta[personId]?.townId ?? defaultTown,
    placeTownOf: (placeId: string) => sim.places[placeId]?.townId ?? defaultTown,
    jurisdictionOf: (placeId: string) => sim.places[placeId]?.jurisdictionId ?? defaultJur,
    workplaceOf: (personId: Id) => sim.peopleMeta[personId]?.workplacePlaceId,
    escalateJurisdictionOf: (jurId: string, crime: CrimeEvent) => {
      if (crime.severity < 0.75) return undefined;
      return sim.escalateJurisdiction[jurId];
    },
    ...(weather ? { weatherOf: () => weather } : {}),
  };
}

function canSeeBystander(b: SceneBystander, targetPos: Vec2): boolean {
  if (b.hasLineOfSight === false) return false;
  if (b.activity === 'sleeping') return false;
  const dx = targetPos.x - b.position.x;
  const dy = targetPos.y - b.position.y;
  const dist = Math.hypot(dx, dy);
  if (dist > 0.35) {
    let fx: number | undefined;
    let fy: number | undefined;
    if (b.facing !== undefined) {
      const mag = Math.hypot(b.facing.x, b.facing.y);
      if (mag > 1e-6) {
        fx = b.facing.x / mag;
        fy = b.facing.y / mag;
      }
    } else if (b.facingRad !== undefined) {
      fx = Math.cos(b.facingRad);
      fy = Math.sin(b.facingRad);
    }
    if (fx !== undefined && fy !== undefined) {
      const dot = (dx / dist) * fx + (dy / dist) * fy;
      if (dot < -0.15) return false;
    }
  }
  if (b.activity === 'distracted' && dist > 6) return false;
  return true;
}

function attentionForBystander(b: SceneBystander, person: Person, sceneLight?: number): number {
  let att = 0.48 + 0.34 * person.traits.diligence;
  switch (b.activity) {
    case 'watching':
      att += 0.34;
      break;
    case 'idle':
      att += 0.14;
      break;
    case 'walking':
      att += 0.04;
      break;
    case 'working':
      att -= 0.06;
      break;
    case 'talking':
      att -= 0.10;
      break;
    case 'distracted':
      att = 0.18;
      break;
    case 'sleeping':
      att = 0;
      break;
    default:
      break;
  }
  if (sceneLight !== undefined && sceneLight < 0.25) {
    att *= 0.45 + 0.55 * (sceneLight / 0.25);
  }
  return clamp01(att);
}

function placeIsOpen(place: LoopPlaceRecord | undefined, hour: number, explicitOpen?: boolean): boolean {
  if (explicitOpen !== undefined) return explicitOpen;
  if (!place?.openHours) {
    if (place?.kind === 'home' || place?.kind === 'yard') return false;
    return true;
  }
  const [openH, closeH] = place.openHours;
  if (openH <= closeH) {
    return hour >= openH && hour < closeH;
  }
  return hour >= openH || hour < closeH;
}

function formatCrimePhrase(meta: CrimeMetaRecord): string {
  switch (meta.kind) {
    case 'shoplifting':
      return meta.itemLabel
        ? `steal ${meta.itemLabel} from ${meta.placeName}`
        : `shoplift from ${meta.placeName}`;
    case 'theft':
      return meta.itemLabel
        ? `steal ${meta.itemLabel} at ${meta.placeName}`
        : `steal property at ${meta.placeName}`;
    case 'burglary':
      if (meta.trespass) return `trespass inside ${meta.placeName}`;
      return meta.itemLabel
        ? `break into ${meta.placeName} and take ${meta.itemLabel}`
        : `break into ${meta.placeName}`;
    case 'car_theft':
      return meta.itemLabel
        ? `steal ${meta.itemLabel} from ${meta.placeName}`
        : `steal a vehicle at ${meta.placeName}`;
    default:
      return `commit ${meta.kind.replace(/_/g, ' ')} at ${meta.placeName}`;
  }
}

function activeCrimeStateForPolice(sim: LoopSim): CrimeState {
  const resolvedIds = new Set<string>();
  for (const [cid, meta] of Object.entries(sim.crimeMeta)) {
    if (meta.resolved) resolvedIds.add(cid);
  }
  if (resolvedIds.size === 0) return sim.crimeState;
  const filteredJurs: CrimeState['jurisdictions'] = {};
  for (const [jurId, jur] of Object.entries(sim.crimeState.jurisdictions)) {
    const activeCases: Record<string, PoliceCase> = {};
    for (const [cid, c] of Object.entries(jur.cases)) {
      if (!resolvedIds.has(cid)) activeCases[cid] = c;
    }
    filteredJurs[jurId] = { jurisdictionId: jurId, cases: activeCases };
  }
  return {
    ...sim.crimeState,
    jurisdictions: filteredJurs,
  };
}

function openPoliceCaseIds(sim: LoopSim): Set<string> {
  const out = new Set<string>();
  for (const jur of Object.values(sim.crimeState.jurisdictions)) {
    for (const cid of Object.keys(jur.cases)) {
      if (!sim.crimeMeta[cid]?.resolved) out.add(cid);
    }
  }
  return out;
}

/**
 * Initializes the full simulation state from plain-JSON town, people, street graph, and era data.
 * Pure & deterministic — zero Node/browser APIs, zero `Math.random`, zero `Date.now`.
 */
export function create(init: LoopInit): LoopSim {
  const startDay = init.startDay ?? 0;
  const startHour = ((init.startHour ?? 8) % 24 + 24) % 24;
  const startDayOfWeek = (((init.startDayOfWeek ?? 0) % 7) + 7) % 7;
  const playerId = init.playerId ?? 'player';
  const playerName = init.playerName ?? 'Alex Mercer';
  const playerAppearance: CulpritAppearance = {
    ...(init.playerAppearance ?? DEFAULT_PLAYER_APPEARANCE),
    ...(init.playerAppearance?.vehicle ? { vehicle: { ...init.playerAppearance.vehicle } } : {}),
  };

  const idMap = createIdMap();
  registerPlanId(idMap, playerId, 'person');

  const streetGraph = {
    nodes: init.streetGraph.nodes.map((n) => ({
      ...n,
      position: { x: n.position.x, y: n.position.y },
    })),
    edges: init.streetGraph.edges.map((e) => ({ ...e })),
  };
  for (let i = 0; i < streetGraph.nodes.length; i++) {
    registerPlanId(idMap, streetGraph.nodes[i]!.id, 'node');
  }

  const places: Record<string, LoopPlaceRecord> = {};
  for (let i = 0; i < init.places.length; i++) {
    const p = init.places[i]!;
    const nodeId = resolvePlaceNodeId(p.id, p.nodeId, p.position, streetGraph.nodes);
    registerPlanId(idMap, p.id, 'place');
    registerPlanId(idMap, nodeId, 'node');
    places[p.id] = {
      id: p.id,
      name: p.name,
      kind: p.kind,
      position: { x: p.position.x, y: p.position.y },
      ...(p.ownerId !== undefined ? { ownerId: p.ownerId } : {}),
      jurisdictionId: p.jurisdictionId,
      townId: p.townId ?? p.jurisdictionId,
      ...(p.openHours !== undefined ? { openHours: [p.openHours[0], p.openHours[1]] as [number, number] } : {}),
      nodeId,
    };
  }

  const people: Record<Id, Person> = {};
  const peopleMeta: Record<string, LoopPersonMeta> = {};
  const bonds: Record<string, number> = {};
  const defaultTown =
    init.places[0]?.townId ?? init.places[0]?.jurisdictionId ?? 'capital';

  for (let i = 0; i < init.people.length; i++) {
    const raw = init.people[i]!;
    const { first, surname, display } = splitName(raw);
    const sex = raw.sex ?? (rand(init.seed, 'sex', raw.id) < 0.5 ? 'F' : 'M');
    const traits = {
      ambition: clamp01(raw.traits?.ambition ?? 0.35 + rand(init.seed, 'amb', raw.id) * 0.45),
      charm: clamp01(raw.traits?.charm ?? 0.35 + rand(init.seed, 'chm', raw.id) * 0.45),
      diligence: clamp01(raw.traits?.diligence ?? 0.35 + rand(init.seed, 'dil', raw.id) * 0.45),
    };
    const homePlace = places[raw.homePlaceId];
    const townId = raw.townId ?? homePlace?.townId ?? defaultTown;
    registerPlanId(idMap, raw.id, 'person');
    people[raw.id] = {
      id: raw.id,
      first,
      surname,
      sex,
      birthYear: init.year - Math.max(0, Math.round(raw.age)),
      traits,
      parents: raw.parentIds ? raw.parentIds.slice() : [],
      ...(raw.spouseId !== undefined ? { spouse: raw.spouseId } : {}),
      children: raw.childIds ? raw.childIds.slice() : [],
      wealth: raw.wealth ?? 50,
      reputation: raw.reputation ?? 0.1,
    };
    peopleMeta[raw.id] = {
      id: raw.id,
      name: display,
      homePlaceId: raw.homePlaceId,
      ...(raw.workplacePlaceId !== undefined ? { workplacePlaceId: raw.workplacePlaceId } : {}),
      townId,
      siblingIds: raw.siblingIds ? raw.siblingIds.slice() : [],
    };
  }

  // Normalize symmetric relationships (spouses, parents/children, siblings, friends -> bonds)
  for (let i = 0; i < init.people.length; i++) {
    const raw = init.people[i]!;
    const person = people[raw.id]!;
    if (raw.spouseId && people[raw.spouseId] && !people[raw.spouseId]!.spouse) {
      people[raw.spouseId]!.spouse = raw.id;
    }
    if (raw.parentIds) {
      for (const pid of raw.parentIds) {
        const parent = people[pid];
        if (parent && !parent.children.includes(raw.id)) parent.children.push(raw.id);
      }
    }
    if (raw.childIds) {
      for (const cid of raw.childIds) {
        const child = people[cid];
        if (child && !child.parents.includes(raw.id)) child.parents.push(raw.id);
      }
    }
    if (raw.siblingIds) {
      for (const sid of raw.siblingIds) {
        const sib = people[sid];
        if (!sib) continue;
        const pk = pairKey(raw.id, sid);
        bonds[pk] = Math.max(bonds[pk] ?? 0, 0.55);
      }
    }
    if (raw.friends) {
      for (const link of raw.friends) {
        if (!people[link.personId] || link.personId === raw.id) continue;
        const pk = pairKey(raw.id, link.personId);
        // Convert target affinity into an additive bond offset over baseline
        const baseline = rand(init.seed, 'affinity', pk) - 0.5;
        const kinBoost = areDirectKin(person, people[link.personId]!) ? 0.5 : 0;
        const needed = link.affinity - baseline - kinBoost;
        bonds[pk] = Math.round(needed * 1000) / 1000;
      }
    }
  }

  // Ensure the player exists inside `world.people` so G005/G008 can reference them cleanly
  if (!people[playerId]) {
    const { first, surname } = splitName({
      id: playerId,
      name: playerName,
      age: 26,
      homePlaceId: init.places[0]?.id ?? 'street',
    });
    people[playerId] = {
      id: playerId,
      first,
      surname,
      sex: 'M',
      birthYear: init.year - 26,
      traits: { ambition: 0.5, charm: 0.5, diligence: 0.5 },
      parents: [],
      children: [],
      wealth: 20,
      reputation: 0,
    };
  }

  const businesses: Record<Id, Business> = {};
  for (const place of Object.values(places)) {
    if (
      place.ownerId &&
      (place.kind === 'general_store' ||
        place.kind === 'market' ||
        place.kind === 'pawn_shop' ||
        place.kind === 'workplace')
    ) {
      businesses[place.id] = {
        id: place.id,
        name: place.name,
        kind: place.kind,
        foundedYear: init.year - 10,
        owner: place.ownerId,
        capital: 500,
      };
    }
  }

  const world: World = {
    seed: init.seed,
    year: init.year,
    people,
    businesses,
    bonds,
  };

  const crimeState = createCrimeState(init.seed, startDay);
  const officers = buildInitialOfficers({
    seed: init.seed,
    year: init.year,
    places,
    streetGraph,
    peopleCount: Object.keys(peopleMeta).length,
    idMap,
  });

  const lastStances: Record<string, AttitudeStance> = {};
  for (const pid of Object.keys(peopleMeta)) {
    lastStances[pid] = 'serve';
  }

  return {
    version: 1,
    seed: init.seed,
    year: init.year,
    currentDay: startDay,
    currentHour: startHour,
    startDay,
    startDayOfWeek,
    playerId,
    playerName,
    playerAppearance,
    world,
    crimeState,
    idMap,
    places,
    peopleMeta,
    streetGraph,
    streetLamps: (init.streetLamps ?? []).map((l) => ({
      ...l,
      position: { x: l.position.x, y: l.position.y },
    })),
    escalateJurisdiction: { ...(init.escalateJurisdiction ?? {}) },
    officers,
    respondedCases: {},
    crimeMeta: {},
    stolenItems: {},
    pawnRecords: [],
    workShifts: [],
    familiarity: {},
    priorOffenses: [],
    lastStances,
    nextSeq: 1,
  };
}

/**
 * Records a player action in the current scene (`buy`, `sell`, `work_shift`, `take`,
 * `take_vehicle`, `enter`), evaluates witness sightlines via G005 `witnessesOf`,
 * records crimes & physical/serial traces via G008 `leaveTraces`, and updates
 * familiarity or pawn-shop registries.
 */
export function act(sim: LoopSim, event: LoopActionEvent): ActResult {
  const place = sim.places[event.placeId];
  const placeName = place?.name ?? event.placeId;
  const jurisdictionId = place?.jurisdictionId ?? Object.values(sim.places)[0]?.jurisdictionId ?? 'capital';
  const pos: Vec2 = event.position
    ? { x: event.position.x, y: event.position.y }
    : place
      ? { x: place.position.x, y: place.position.y }
      : { x: 0, y: 0 };

  const playerAppearance: CulpritAppearance = event.appearance
    ? {
        ...event.appearance,
        ...(event.appearance.vehicle ? { vehicle: { ...event.appearance.vehicle } } : {}),
      }
    : sim.playerAppearance;

  const dayName = dayNameFor(sim.startDay, sim.startDayOfWeek, sim.currentDay);
  const nextFamiliarity: Record<string, PlayerFamiliarity> = { ...sim.familiarity };
  let nextSeq = sim.nextSeq;

  const touchFamiliarity = (personId: string): PlayerFamiliarity => {
    const copy = cloneFamiliarity(nextFamiliarity[personId]);
    nextFamiliarity[personId] = copy;
    return copy;
  };

  // --- 1. BUY (legitimate) ---
  if (event.kind === 'buy') {
    const clerkId = event.clerkId ?? place?.ownerId;
    if (clerkId && sim.peopleMeta[clerkId]) {
      const fam = touchFamiliarity(clerkId);
      fam.trustDelta = Math.round((fam.trustDelta + 0.04) * 1000) / 1000;
    }
    const nextSim: LoopSim = {
      ...sim,
      playerAppearance,
      familiarity: nextFamiliarity,
    };
    return {
      sim: nextSim,
      kind: 'buy',
      legit: true,
      witnessedBy: [],
      tracesLeft: [],
      summary: `Bought ${event.item.label} at ${placeName}.`,
    };
  }

  // --- 2. WORK SHIFT (legitimate pay; employer & coworkers learn player's name) ---
  if (event.kind === 'work_shift') {
    const hours = Math.max(1, event.hours ?? 4);
    const payEarned = event.pay ?? Math.round(hours * 12);
    const employerId = event.employerId ?? place?.ownerId;
    const coworkerSet = new Set<string>(event.coworkerIds ?? []);
    if (event.bystanders) {
      for (const b of event.bystanders) {
        if (b.id !== employerId && b.id !== sim.playerId && sim.peopleMeta[b.id]?.workplacePlaceId === event.placeId) {
          coworkerSet.add(b.id);
        }
      }
    }
    for (const meta of Object.values(sim.peopleMeta)) {
      if (meta.workplacePlaceId === event.placeId && meta.id !== employerId && meta.id !== sim.playerId) {
        coworkerSet.add(meta.id);
      }
    }
    const coworkerIds = Array.from(coworkerSet).sort();
    const taskPhrase = event.taskLabel ? ` (${event.taskLabel})` : '';
    const factLine = `${sim.playerName} worked an honest ${hours}-hour shift${taskPhrase} at ${placeName} on ${dayName}.`;

    const acquaint = (pid: string, trustBump: number): void => {
      if (!sim.peopleMeta[pid]) return;
      const fam = touchFamiliarity(pid);
      fam.knowsPlayerName = true;
      fam.playerName = sim.playerName;
      fam.trustDelta = Math.round((fam.trustDelta + trustBump) * 1000) / 1000;
      if (!fam.positiveFacts.includes(factLine)) {
        fam.positiveFacts.push(factLine);
      }
    };

    if (employerId) acquaint(employerId, 0.25);
    for (const cid of coworkerIds) acquaint(cid, 0.16);

    const shiftRecord: WorkShiftRecord = {
      id: `shift:${sim.currentDay}:${nextSeq++}`,
      placeId: event.placeId,
      ...(employerId !== undefined ? { employerId } : {}),
      coworkerIds,
      day: sim.currentDay,
      hours,
      payEarned,
    };

    const nextSim: LoopSim = {
      ...sim,
      playerAppearance,
      workShifts: [...sim.workShifts, shiftRecord],
      familiarity: nextFamiliarity,
      nextSeq,
    };
    return {
      sim: nextSim,
      kind: 'work_shift',
      legit: true,
      payEarned,
      witnessedBy: [],
      tracesLeft: [],
      summary: `Worked a ${hours}-hour shift at ${placeName} for $${payEarned}.`,
    };
  }

  // --- 3. SELL (pawn shop tracks item, serial & seller identity; stolen items can trace back) ---
  if (event.kind === 'sell') {
    const clerkId = event.clerkId ?? place?.ownerId;
    const isPawnShop = place?.kind === 'pawn_shop';
    let matchedStolen: StolenItemRecord | undefined =
      sim.stolenItems[event.item.id];
    if (!matchedStolen && event.item.stolenFromCrimeId) {
      matchedStolen = Object.values(sim.stolenItems).find(
        (s) => s.crimeId === event.item.stolenFromCrimeId,
      );
    }
    if (!matchedStolen && event.item.serialNumber) {
      matchedStolen = Object.values(sim.stolenItems).find(
        (s) => s.serialNumber === event.item.serialNumber,
      );
    }

    const serialNumber = event.item.serialNumber ?? matchedStolen?.serialNumber;
    const stolenFromCrimeId = event.item.stolenFromCrimeId ?? matchedStolen?.crimeId;
    let nextCrimeState = sim.crimeState;
    const nextStolenItems = { ...sim.stolenItems };
    const nextPawnRecords = sim.pawnRecords.slice();
    let pawnTraceFlagged = false;

    if (isPawnShop || matchedStolen) {
      const pawnId = `pawn:${sim.currentDay}:${nextSeq++}`;
      const record: PawnRecord = {
        id: pawnId,
        placeId: event.placeId,
        ...(clerkId !== undefined ? { clerkId } : {}),
        itemId: event.item.id,
        label: event.item.label,
        value: event.item.value ?? 10,
        day: sim.currentDay,
        ...(serialNumber !== undefined ? { serialNumber } : {}),
        ...(stolenFromCrimeId !== undefined ? { stolenFromCrimeId } : {}),
        sellerId: sim.playerId,
        reportedToPolice: false,
      };
      nextPawnRecords.push(record);

      if (matchedStolen) {
        pawnTraceFlagged = true;
        nextStolenItems[matchedStolen.itemId] = {
          ...matchedStolen,
          pawnedAtPlaceId: event.placeId,
          pawnedDay: sim.currentDay,
        };
      }

      // Pawn shops log seller ID & serial numbers into the local pawn registry (G008)
      const fileRecord: IdentityFileRecord = {
        id: `file:pawn:${pawnId}`,
        personId: sim.playerId,
        jurisdictionId,
        scope: 'local',
        reason: 'pawn_registry',
        enrolledDay: sim.currentDay,
        enrolledYear: sim.year,
        hasPrints: false,
        hasDna: false,
        ...(serialNumber !== undefined ? { serialNumbers: [serialNumber] } : {}),
      };
      nextCrimeState = enrollOnFile(nextCrimeState, fileRecord);

      if (clerkId && sim.peopleMeta[clerkId]) {
        const fam = touchFamiliarity(clerkId);
        fam.knowsPlayerName = true;
        fam.playerName = sim.playerName;
        fam.trustDelta = Math.round((fam.trustDelta + 0.02) * 1000) / 1000;
      }
    }

    const nextSim: LoopSim = {
      ...sim,
      playerAppearance,
      crimeState: nextCrimeState,
      stolenItems: nextStolenItems,
      pawnRecords: nextPawnRecords,
      familiarity: nextFamiliarity,
      nextSeq,
    };
    return {
      sim: nextSim,
      kind: 'sell',
      legit: true,
      pawnTraceFlagged,
      witnessedBy: [],
      tracesLeft: [],
      summary: `Sold ${event.item.label} at ${placeName}.`,
    };
  }

  // --- 4. ENTER (legit when open and unforced; trespass / burglary otherwise) ---
  if (event.kind === 'enter') {
    const isOpen = placeIsOpen(place, sim.currentHour, event.isOpen);
    if (isOpen && !event.forcedEntry) {
      const nextSim: LoopSim = { ...sim, playerAppearance };
      return {
        sim: nextSim,
        kind: 'enter',
        legit: true,
        witnessedBy: [],
        tracesLeft: [],
        summary: `Entered ${placeName} during open hours.`,
      };
    }
  }

  // --- 5. CRIME PATH (`take`, `take_vehicle`, closed/forced `enter`) ---
  let crimeKind: CrimeKind;
  let severity: number;
  let ownerId: string | undefined;
  let itemId: string | undefined;
  let itemLabel: string | undefined;
  let itemValue: number | undefined;
  let explicitSerial: string | undefined;
  let vehiclePlate: string | undefined;
  let gloved = false;
  let eventAppearance: CulpritAppearance = playerAppearance;
  let exposure: 'indoor' | 'outdoor' = 'indoor';

  if (event.kind === 'take') {
    const subkind =
      event.subkind ??
      (place?.kind === 'general_store' || place?.kind === 'market' || place?.kind === 'pawn_shop'
        ? 'shoplift'
        : place?.kind === 'yard'
          ? 'yard'
          : place?.kind === 'home'
            ? 'house'
            : 'shoplift');
    crimeKind =
      subkind === 'shoplift'
        ? 'shoplifting'
        : subkind === 'house'
          ? 'burglary'
          : 'theft';
    const valBoost = event.item.value ? Math.min(0.14, event.item.value / 400) : 0;
    severity = clamp01(
      (subkind === 'shoplift'
        ? 0.28
        : subkind === 'yard'
          ? 0.36
          : subkind === 'pickpocket'
            ? 0.42
            : 0.60) + valBoost,
    );
    ownerId = event.ownerId ?? place?.ownerId;
    itemId = event.item.id;
    itemLabel = event.item.label;
    itemValue = event.item.value ?? 5;
    explicitSerial = event.item.serialNumber;
    gloved = Boolean(event.gloved);
    exposure = subkind === 'yard' || subkind === 'pickpocket' ? 'outdoor' : 'indoor';
  } else if (event.kind === 'take_vehicle') {
    crimeKind = 'car_theft';
    severity = DEFAULT_SEVERITY.car_theft;
    ownerId = event.ownerId ?? place?.ownerId;
    itemId = event.vehicle.id;
    itemLabel = `${event.vehicle.color} ${event.vehicle.kind}`;
    itemValue = event.vehicle.value ?? 800;
    vehiclePlate = event.vehicle.plateNumber;
    gloved = Boolean(event.gloved);
    exposure = 'outdoor';
    eventAppearance = {
      ...playerAppearance,
      vehicle: {
        kind: event.vehicle.kind,
        color: event.vehicle.color,
      },
    };
  } else {
    // Closed or forced `enter`
    crimeKind = 'burglary';
    severity = event.forcedEntry ? 0.62 : 0.26;
    ownerId = event.ownerId ?? place?.ownerId;
    gloved = Boolean(event.gloved);
    exposure = place?.kind === 'yard' ? 'outdoor' : 'indoor';
  }

  const crimeId = `crime:${sim.currentDay}:${nextSeq++}`;

  // Determine whether the victim (owner) is actually present and able to see the crime
  const sceneBystanders = event.bystanders ?? [];
  const sceneById = new Map<string, SceneBystander>();
  for (let i = 0; i < sceneBystanders.length; i++) {
    const sb = sceneBystanders[i]!;
    sceneById.set(sb.id, sb);
  }

  const ownerScene = ownerId ? sceneById.get(ownerId) : undefined;
  const victimCanSee = ownerScene !== undefined && canSeeBystander(ownerScene, pos);

  const crimeEvent: CrimeEvent = {
    id: crimeId,
    kind: crimeKind,
    culpritId: sim.playerId,
    ...(ownerId !== undefined ? { victimId: ownerId } : {}),
    victimConscious: victimCanSee,
    placeId: event.placeId,
    position: pos,
    when: {
      day: sim.currentDay,
      hour: sim.currentHour,
      year: sim.year,
    },
    severity,
    culpritAppearance: eventAppearance,
    ...(itemLabel !== undefined
      ? { stolenOrDamaged: { item: itemLabel, value: itemValue ?? 5 } }
      : {}),
  };

  // Build G005 Bystander list
  const g005Bystanders: Bystander[] = [];
  for (let i = 0; i < sceneBystanders.length; i++) {
    const sb = sceneBystanders[i]!;
    if (sb.id === sim.playerId) continue;
    const person = sim.world.people[sb.id];
    if (!person) continue;
    const isOwner = ownerId !== undefined && sb.id === ownerId;
    const canSee = canSeeBystander(sb, pos);
    const fam = sim.familiarity[sb.id];
    g005Bystanders.push({
      person,
      position: { x: sb.position.x, y: sb.position.y },
      attention: attentionForBystander(sb, person, event.light),
      // If the victim is present with their back turned (`!canSee`), mark them unaware
      // so `witnessesOf` does not auto-force them into witnessing a stealth theft.
      conscious: sb.activity === 'sleeping' ? false : isOwner ? canSee : true,
      ...(fam?.knowsPlayerName ? { knowsCulprit: true } : {}),
    });
  }

  // Respect explicit `event.light` by adding a temporary scene lamp when brighter than outdoor ambient
  let lampsForScene: readonly StreetLamp[] = sim.streetLamps;
  if (event.light !== undefined) {
    const baseAmbient = ambientLightAt(sim.currentHour, pos, sim.streetLamps);
    if (event.light > baseAmbient) {
      lampsForScene = [
        ...sim.streetLamps,
        { position: pos, radius: 50, intensity: clamp01(event.light) },
      ];
    }
  }

  const sightings = witnessesOf(crimeEvent, g005Bystanders, {
    seed: sim.seed,
    world: sim.world,
    streetLamps: lampsForScene,
    canSee: (_from, to, bystanderId) => {
      const sb = sceneById.get(bystanderId);
      if (!sb) return true;
      return canSeeBystander(sb, to);
    },
  });

  let nextCrimeState = recordCrime(sim.crimeState, crimeEvent, sightings);

  // Leave physical & serial traces via G008
  const traces = leaveTraces(crimeEvent, {
    seed: sim.seed,
    gloved,
    exposure,
    weather: event.weather ?? 'clear',
    ...(explicitSerial !== undefined ? { stolenSerialNumber: explicitSerial } : {}),
    ...(vehiclePlate !== undefined ? { vehiclePlate } : {}),
  });
  nextCrimeState = recordTraces(nextCrimeState, crimeId, traces);

  const serialTrace = traces.find((t) => t.kind === 'stolen_serial');
  const effectiveSerial = explicitSerial ?? serialTrace?.serialNumber;

  const nextStolenItems = { ...sim.stolenItems };
  if (itemId && itemLabel && (event.kind === 'take' || event.kind === 'take_vehicle')) {
    nextStolenItems[itemId] = {
      itemId,
      label: itemLabel,
      value: itemValue ?? 5,
      crimeId,
      placeId: event.placeId,
      jurisdictionId,
      ...(ownerId !== undefined ? { ownerId } : {}),
      dayStolen: sim.currentDay,
      ...(effectiveSerial !== undefined ? { serialNumber: effectiveSerial } : {}),
    };
  }

  const nextCrimeMeta: Record<string, CrimeMetaRecord> = {
    ...sim.crimeMeta,
    [crimeId]: {
      crimeId,
      kind: crimeKind,
      placeId: event.placeId,
      placeName,
      jurisdictionId,
      day: sim.currentDay,
      dayName,
      ...(itemLabel !== undefined ? { itemLabel } : {}),
      ...(itemValue !== undefined ? { itemValue } : {}),
      ...(ownerId !== undefined ? { ownerId } : {}),
      ...(event.kind === 'enter' && !event.forcedEntry ? { trespass: true } : {}),
      resolved: false,
    },
  };

  const witnessedBy: WitnessNotice[] = sightings.map((s) => ({
    personId: s.witnessId,
    name: sim.peopleMeta[s.witnessId]?.name ?? s.witnessId,
    confidence: Math.round(s.confidence * 1000) / 1000,
    recognizedPlayer: s.identifiedCulpritId === sim.playerId,
    isVictim: s.isVictim,
    description: {
      ...s.description,
      ...(s.description.vehicle ? { vehicle: { ...s.description.vehicle } } : {}),
    },
  }));

  const tracesLeft: ActTraceSummary[] = traces.map((t) => ({
    id: t.id,
    kind: t.kind,
    quality: Math.round(t.quality * 1000) / 1000,
    ...(t.serialNumber !== undefined ? { serialNumber: t.serialNumber } : {}),
  }));

  const nextSim: LoopSim = {
    ...sim,
    playerAppearance: eventAppearance,
    crimeState: nextCrimeState,
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    familiarity: nextFamiliarity,
    nextSeq,
  };

  return {
    sim: nextSim,
    kind: event.kind,
    legit: false,
    crimeId,
    ...(effectiveSerial !== undefined ? { stolenSerialNumber: effectiveSerial } : {}),
    witnessedBy,
    tracesLeft,
    summary:
      witnessedBy.length > 0
        ? `${crimeKind} at ${placeName} witnessed by ${witnessedBy.length} person(s).`
        : `${crimeKind} at ${placeName} went unseen.`,
  };
}

/**
 * When a first-hand witness sees someone steal from a local shopkeeper or homeowner,
 * they naturally warn the victim (`crime.victimId`) on the next day unless they are
 * shielding the culprit. This feeds directly into G005's ledger so G005's own gossip
 * and victim reporting rules drive everything downstream.
 */
function warnUnawareVictims(sim: LoopSim, crimeState: CrimeState, day: number): CrimeState {
  let nextLedger = crimeState.ledger;
  let ledgerCopied = false;

  const crimeIds = Object.keys(crimeState.crimes).sort();
  for (let i = 0; i < crimeIds.length; i++) {
    const crimeId = crimeIds[i]!;
    if (sim.crimeMeta[crimeId]?.resolved) continue;
    const crime = crimeState.crimes[crimeId]!;
    const victimId = crime.victimId;
    if (!victimId || victimId === crime.culpritId) continue;

    const accounts = nextLedger[crimeId];
    if (!accounts) continue;
    if (accounts[victimId]) continue; // victim already knows

    const victimTown = sim.peopleMeta[victimId]?.townId;
    const culprit = sim.world.people[crime.culpritId];

    // Find the best first-hand witness in the same town who would warn the victim
    const knowerIds = Object.keys(accounts).sort();
    let bestWitness: CrimeAccount | undefined;
    for (let k = 0; k < knowerIds.length; k++) {
      const acc = accounts[knowerIds[k]!]!;
      if (acc.source !== 'first_hand' || acc.confidence < 0.35) continue;
      const witness = sim.world.people[acc.knowerId];
      if (!witness) continue;
      // Do not warn if shielding the culprit (spouse, direct kin, or close friend)
      if (culprit && (areDirectKin(witness, culprit) || affinity(sim.world, witness.id, culprit.id) > 0.35)) {
        continue;
      }
      const witnessTown = sim.peopleMeta[acc.knowerId]?.townId;
      const victimPerson = sim.world.people[victimId];
      const knowsVictim =
        (victimTown !== undefined && witnessTown === victimTown) ||
        (victimPerson !== undefined &&
          (areDirectKin(witness, victimPerson) || affinity(sim.world, witness.id, victimId) > 0.1));
      if (!knowsVictim) continue;
      if (!bestWitness || acc.confidence > bestWitness.confidence) {
        bestWitness = acc;
      }
    }

    if (!bestWitness) continue;
    const witnessPerson = sim.world.people[bestWitness.knowerId]!;
    const warnProb = clamp01(0.76 + 0.20 * witnessPerson.traits.diligence);
    if (rand(sim.seed, 'warn_victim', day, crimeId, bestWitness.knowerId) >= warnProb) {
      continue;
    }

    const victimAccount: CrimeAccount = {
      crimeId,
      knowerId: victimId,
      source: 'second_hand',
      isVictim: true,
      hops: 1,
      heardFrom: bestWitness.knowerId,
      dayLearned: day,
      lastRefreshedDay: day,
      confidence: clamp01(bestWitness.confidence * 0.88),
      initialConfidence: clamp01(bestWitness.confidence * 0.88),
      description: {
        ...bestWitness.description,
        ...(bestWitness.description.vehicle ? { vehicle: { ...bestWitness.description.vehicle } } : {}),
      },
      ...(bestWitness.identifiedCulpritId ? { identifiedCulpritId: bestWitness.identifiedCulpritId } : {}),
      reported: false,
      refreshes: 0,
    };

    if (!ledgerCopied) {
      nextLedger = { ...crimeState.ledger };
      ledgerCopied = true;
    }
    nextLedger[crimeId] = {
      ...accounts,
      [victimId]: victimAccount,
    };
  }

  return ledgerCopied ? { ...crimeState, ledger: nextLedger } : crimeState;
}

/**
 * Advances the simulation by `hours` simulated hours:
 * - steps whole days through G005/G008 `advanceDay` (gossip, era-delayed reporting, trace decay, cold cases);
 * - runs police trace collection & pawn-shop lead matching;
 * - compiles G007 police plans for officers;
 * - returns new police plans and people whose attitude toward the player changed.
 */
export function advance(sim: LoopSim, hours: number, options?: AdvanceOptions): AdvanceResult {
  const safeHours = Math.max(0, hours);
  const fromDay = sim.currentDay;
  const fromHour = sim.currentHour;
  const totalHours = fromHour + safeHours;
  const daysElapsed = Math.floor(totalHours / 24);
  const toDay = fromDay + daysElapsed;
  const toHour = Math.round((totalHours - daysElapsed * 24) * 1000) / 1000;

  const beforeCases = openPoliceCaseIds(sim);
  let crimeState = sim.crimeState;
  const nextPawnRecords = sim.pawnRecords.map((r) => ({ ...r }));
  const nextStolenItems = { ...sim.stolenItems };
  const pawnLeadsMatched: PawnLeadMatch[] = [];
  const ctx = buildCrimeContext(sim, options?.weather);

  // Track already-known forensic pawn matches so we only emit newly matched leads
  const seenMatchIds = new Set<string>();
  for (const jur of Object.values(crimeState.jurisdictions)) {
    for (const c of Object.values(jur.cases)) {
      if (c.evidenceMatches) {
        for (const m of c.evidenceMatches) {
          seenMatchIds.add(`${c.crimeId}:${m.traceId}`);
        }
      }
    }
  }

  for (let d = fromDay + 1; d <= toDay; d++) {
    // 1. Eyewitnesses of a local shop/home theft warn the victim
    crimeState = warnUnawareVictims(sim, crimeState, d);

    // 2. Run G005/G008 daily engine (decay, gossip, era-delayed reporting, trace decay, cold-case match)
    crimeState = advanceDay(crimeState, sim.world, d, ctx);

    // 3. Police collect scene traces on reported, unresolved cases so G008 serial/print matching fires
    const jurIds = Object.keys(crimeState.jurisdictions).sort();
    for (let j = 0; j < jurIds.length; j++) {
      const jurId = jurIds[j]!;
      const jur = crimeState.jurisdictions[jurId]!;
      const caseIds = Object.keys(jur.cases).sort();
      for (let c = 0; c < caseIds.length; c++) {
        const cid = caseIds[c]!;
        if (sim.crimeMeta[cid]?.resolved) continue;
        const sceneTraces = crimeState.traces?.[cid];
        if (sceneTraces && sceneTraces.some((t) => !t.collected && !t.destroyed)) {
          crimeState = collectCrimeTraces(crimeState, sim.world, cid, jurId, d, {
            year: sim.year,
          });
        }
      }
    }

    // 4. Re-examine cold cases after trace collection (matches pawn_registry serials & prior files)
    if (crimeState.identityFiles) {
      crimeState = reexamineColdCases(crimeState, sim.world, d, ctx);
    }

    // 5. Check pawn shop ledger leads against reported/known thefts
    for (let p = 0; p < nextPawnRecords.length; p++) {
      const pawn = nextPawnRecords[p]!;
      if (pawn.reportedToPolice || !pawn.stolenFromCrimeId) continue;
      const cid = pawn.stolenFromCrimeId;
      if (sim.crimeMeta[cid]?.resolved) continue;
      const crime = crimeState.crimes[cid];
      if (!crime) continue;
      const pawnPlace = sim.places[pawn.placeId];
      const jurId =
        sim.crimeMeta[cid]?.jurisdictionId ?? pawnPlace?.jurisdictionId ?? 'capital';
      const jurCase = crimeState.jurisdictions[jurId]?.cases[cid];

      // Check if G008 already matched the serial via pawn_registry
      const serialMatched = jurCase?.evidenceMatches?.some(
        (m) => m.method === 'serial_registry' && m.suspectId === pawn.sellerId,
      );

      // Or if police have an open case (or pawn clerk heard about the theft / routine pawn sheet check)
      const clerkKnows = pawn.clerkId !== undefined && Boolean(crimeState.ledger[cid]?.[pawn.clerkId]);
      const policeCheckingPawnLogs =
        jurCase !== undefined ||
        (d >= pawn.day + 1 && knowersOf(crimeState, cid).length > 0 && rand(sim.seed, 'pawn_audit', d, pawn.id) < 0.72) ||
        (d >= pawn.day + 1 && pawn.serialNumber !== undefined && rand(sim.seed, 'pawn_serial_check', d, pawn.id) < 0.65);

      if (serialMatched || clerkKnows || policeCheckingPawnLogs) {
        pawn.reportedToPolice = true;
        if (nextStolenItems[pawn.itemId]) {
          nextStolenItems[pawn.itemId] = {
            ...nextStolenItems[pawn.itemId]!,
            tracedToPlayer: true,
          };
        }
        // If G008 hasn't already named the seller on the police case, file the pawn ledger report
        const alreadyNamed = jurCase?.merged.namedSuspectId === pawn.sellerId;
        if (!alreadyNamed) {
          const sellerDesc = crime.culpritAppearance;
          const pawnReport: PoliceReport = {
            id: `rep:pawn:${pawn.id}:${d}`,
            crimeId: cid,
            reporterId: pawn.clerkId ?? pawn.placeId,
            jurisdictionId: jurId,
            filedDay: d,
            receivedDay: d,
            delayHours: 0.5,
            source: 'first_hand',
            isVictim: false,
            confidence: pawn.serialNumber ? 0.92 : 0.80,
            identifiedCulpritId: pawn.sellerId,
            description: {
              height: sellerDesc.height,
              build: sellerDesc.build,
              hair: sellerDesc.hair,
              clothingColor: sellerDesc.clothingColor,
            },
          };
          const prevJur = crimeState.jurisdictions[jurId];
          crimeState = {
            ...crimeState,
            jurisdictions: {
              ...crimeState.jurisdictions,
              [jurId]: ingestPoliceReport(prevJur, crime, pawnReport),
            },
          };
          // Immediately collect traces on the newly opened/updated case
          const sceneTraces = crimeState.traces?.[cid];
          if (sceneTraces && sceneTraces.some((t) => !t.collected && !t.destroyed)) {
            crimeState = collectCrimeTraces(crimeState, sim.world, cid, jurId, d, {
              year: sim.year,
            });
          }
        }

        pawnLeadsMatched.push({
          crimeId: cid,
          ...(pawn.serialNumber !== undefined ? { serialNumber: pawn.serialNumber } : {}),
          pawnShopId: pawn.placeId,
          suspectId: pawn.sellerId,
        });
      }
    }

    // Record any pure forensic pawn_registry matches not already emitted above
    for (const jur of Object.values(crimeState.jurisdictions)) {
      for (const c of Object.values(jur.cases)) {
        if (!c.evidenceMatches) continue;
        for (const m of c.evidenceMatches) {
          const key = `${c.crimeId}:${m.traceId}`;
          if (seenMatchIds.has(key)) continue;
          seenMatchIds.add(key);
          if (m.method === 'serial_registry') {
            const pawn = nextPawnRecords.find((r) => r.stolenFromCrimeId === c.crimeId);
            if (pawn && !pawn.reportedToPolice) {
              pawn.reportedToPolice = true;
              pawnLeadsMatched.push({
                crimeId: c.crimeId,
                ...(pawn.serialNumber !== undefined ? { serialNumber: pawn.serialNumber } : {}),
                pawnShopId: pawn.placeId,
                suspectId: m.suspectId,
              });
            }
          }
        }
      }
    }
  }

  // Compile G007 police plans for active (unresolved) cases
  const interimSim: LoopSim = {
    ...sim,
    currentDay: toDay,
    currentHour: toHour,
    crimeState,
    pawnRecords: nextPawnRecords,
    stolenItems: nextStolenItems,
    idMap: cloneIdMap(sim.idMap),
  };
  const activeState = activeCrimeStateForPolice(interimSim);
  const policeTick = planPoliceForTick({
    seed: sim.seed,
    day: toDay,
    hour: toHour,
    officers: sim.officers,
    respondedCases: sim.respondedCases,
    crimeState: activeState,
    places: sim.places,
    streetGraph: sim.streetGraph,
    idMap: interimSim.idMap,
  });

  // Refresh witness memory for anyone interviewed by responding officers
  for (let i = 0; i < policeTick.interviewedWitnesses.length; i++) {
    const iv = policeTick.interviewedWitnesses[i]!;
    crimeState = refreshWitnessMemory(crimeState, iv.crimeId, iv.witnessId, toDay, 'police_interview');
  }

  const nextSim: LoopSim = {
    ...interimSim,
    crimeState,
    officers: policeTick.officers,
    respondedCases: policeTick.respondedCases,
    lastStances: { ...sim.lastStances },
  };

  // Detect newly opened police cases
  const afterCases = openPoliceCaseIds(nextSim);
  const newPoliceCases: string[] = [];
  for (const cid of afterCases) {
    if (!beforeCases.has(cid)) newPoliceCases.push(cid);
  }
  newPoliceCases.sort();

  // Compute exact attitude diffs across all townsfolk (reusing precomputed police/ledger cache)
  const attitudeChanges: AttitudeChange[] = [];
  const attCache = buildAttitudeCache(nextSim);
  const personIds = Object.keys(nextSim.peopleMeta).sort();
  for (let i = 0; i < personIds.length; i++) {
    const pid = personIds[i]!;
    const att = attitude(nextSim, pid, attCache);
    const prev = sim.lastStances[pid] ?? 'serve';
    if (att.stance !== prev) {
      attitudeChanges.push({
        personId: pid,
        name: att.name,
        previousStance: prev,
        newStance: att.stance,
        reasons: att.facts,
      });
      nextSim.lastStances[pid] = att.stance;
    }
  }

  return {
    sim: nextSim,
    fromDay,
    toDay,
    fromHour,
    toHour,
    daysElapsed,
    policePlans: policeTick.directives,
    attitudeChanges,
    newPoliceCases,
    pawnLeadsMatched,
  };
}

/**
 * Queries what `personId` knows about the player, how they feel (`serve | wary | refuse | report_on_sight`),
 * and plain-English `facts[]` ready for the Qwen dialogue model's fact sheet.
 */
interface AttitudeEvalCache {
  crimeIds: readonly string[];
  activePolice: CrimeState;
  wantedByJur: Record<string, boolean>;
}

function buildAttitudeCache(sim: LoopSim): AttitudeEvalCache {
  return {
    crimeIds: Object.keys(sim.crimeState.ledger).sort(),
    activePolice: activeCrimeStateForPolice(sim),
    wantedByJur: {},
  };
}

export function attitude(
  sim: LoopSim,
  personId: string,
  _cache?: AttitudeEvalCache,
): AttitudeResult {
  const meta = sim.peopleMeta[personId];
  const person = sim.world.people[personId];
  const name = meta?.name ?? (person ? `${person.first} ${person.surname}` : personId);
  const fam = sim.familiarity[personId];
  const knowsPlayerName = Boolean(fam?.knowsPlayerName);
  const playerNameKnown = knowsPlayerName ? (fam?.playerName ?? sim.playerName) : undefined;

  const facts: string[] = [];
  if (knowsPlayerName && playerNameKnown) {
    facts.push(`You know this person by name as ${playerNameKnown}.`);
  }
  if (fam?.positiveFacts) {
    for (let i = 0; i < fam.positiveFacts.length; i++) {
      facts.push(fam.positiveFacts[i]!);
    }
  }

  // Gather every crime account this person holds involving the player
  const knownCrimes: Array<{ meta: CrimeMetaRecord; account: CrimeAccount }> = [];
  const crimeIds = _cache ? _cache.crimeIds : Object.keys(sim.crimeState.ledger).sort();
  for (let i = 0; i < crimeIds.length; i++) {
    const cid = crimeIds[i]!;
    const acc = sim.crimeState.ledger[cid]?.[personId];
    if (!acc) continue;
    const cMeta = sim.crimeMeta[cid];
    if (!cMeta) continue;
    const crime = sim.crimeState.crimes[cid];
    if (!crime || crime.culpritId !== sim.playerId) continue;
    knownCrimes.push({ meta: cMeta, account: acc });
  }

  let worstUnresolvedSeverity = 0;
  let victimizedByPlayer = false;
  let sawPlayerDirectly = false;
  let maxConfidence = 0;
  let criminalPenalty = 0;

  for (let i = 0; i < knownCrimes.length; i++) {
    const { meta: cMeta, account: acc } = knownCrimes[i]!;
    const crime = sim.crimeState.crimes[cMeta.crimeId]!;
    const phrase = formatCrimePhrase(cMeta);
    const tellerName = acc.heardFrom ? sim.peopleMeta[acc.heardFrom]?.name : undefined;
    const isOwnerOrVictim = acc.isVictim || cMeta.ownerId === personId;

    if (acc.source === 'first_hand') {
      facts.push(`You saw this person ${phrase} on ${cMeta.dayName}.`);
      sawPlayerDirectly = true;
    } else if (isOwnerOrVictim) {
      facts.push(
        tellerName
          ? `${tellerName} told you this person managed to ${phrase} on ${cMeta.dayName}.`
          : `You heard this person managed to ${phrase} on ${cMeta.dayName}.`,
      );
    } else {
      facts.push(
        tellerName
          ? `${tellerName} mentioned someone tried to ${phrase} on ${cMeta.dayName}.`
          : `Word around town is someone tried to ${phrase} on ${cMeta.dayName}.`,
      );
    }

    if (!cMeta.resolved) {
      if (isOwnerOrVictim) victimizedByPlayer = true;
      if (crime.severity > worstUnresolvedSeverity) worstUnresolvedSeverity = crime.severity;
      if (acc.confidence > maxConfidence) maxConfidence = acc.confidence;
      criminalPenalty += (isOwnerOrVictim ? 0.65 : acc.source === 'first_hand' ? 0.45 : 0.25) * acc.confidence;
    } else {
      criminalPenalty += 0.12;
      facts.push(`The police already dealt with the ${cMeta.dayName} incident at ${cMeta.placeName}.`);
    }
  }

  // Check if the player is actively wanted in this person's home jurisdiction
  const homeJur = meta?.homePlaceId ? sim.places[meta.homePlaceId]?.jurisdictionId : undefined;
  let wantedHere = false;
  if (homeJur) {
    if (_cache) {
      let cached = _cache.wantedByJur[homeJur];
      if (cached === undefined) {
        cached = isWanted(_cache.activePolice, homeJur, sim.playerId, sim.currentDay);
        _cache.wantedByJur[homeJur] = cached;
      }
      wantedHere = cached;
    } else {
      wantedHere = isWanted(activeCrimeStateForPolice(sim), homeJur, sim.playerId, sim.currentDay);
    }
  }
  if (wantedHere && knownCrimes.length > 0) {
    facts.push('Local police are actively looking for this person.');
  }

  const baseTrust = (fam?.trustDelta ?? 0) - criminalPenalty;
  const trust = Math.round(Math.max(-1, Math.min(1, baseTrust)) * 1000) / 1000;

  let stance: AttitudeStance = 'serve';
  if (
    (wantedHere && (victimizedByPlayer || sawPlayerDirectly || worstUnresolvedSeverity >= 0.55)) ||
    (worstUnresolvedSeverity >= 0.55 && maxConfidence >= 0.45)
  ) {
    stance = 'report_on_sight';
  } else if (
    victimizedByPlayer ||
    (fam?.bannedFromPlaceIds && fam.bannedFromPlaceIds.length > 0) ||
    (sawPlayerDirectly && maxConfidence >= 0.40)
  ) {
    stance = 'refuse';
  } else if (knownCrimes.some((k) => !k.meta.resolved) || trust < -0.15) {
    stance = 'wary';
  }

  return {
    personId,
    name,
    stance,
    knowsPlayerName,
    ...(playerNameKnown !== undefined ? { playerNameKnown } : {}),
    trust,
    facts,
  };
}

/**
 * Returns what the police in `jurisdictionId` currently know: merged description,
 * heat, and G008 `caseStrength` for every open case, plus current officer directives.
 */
export function policeView(sim: LoopSim, jurisdictionId: string): PoliceViewResult {
  const activeState = activeCrimeStateForPolice(sim);
  const snap = policeKnowledge(activeState, jurisdictionId, sim.currentDay);
  const cases: PoliceViewCase[] = snap.cases
    .slice()
    .sort((a, b) => b.severity - a.severity || (a.crimeId < b.crimeId ? -1 : 1))
    .map((c) => {
      const cMeta = sim.crimeMeta[c.crimeId];
      const placeName = cMeta?.placeName ?? sim.places[c.placeId]?.name ?? c.placeId;
      return {
        crimeId: c.crimeId,
        kind: c.kind,
        severity: c.severity,
        placeId: c.placeId,
        placeName,
        reportCount: c.reportCount,
        firstHandCount: c.firstHandCount,
        heat: Math.round(caseHeatOnDay(c, sim.currentDay) * 1000) / 1000,
        mergedDescription: {
          ...c.merged.description,
          ...(c.merged.description.vehicle
            ? { vehicle: { ...c.merged.description.vehicle } }
            : {}),
        },
        ...(c.merged.namedSuspectId !== undefined
          ? { namedSuspectId: c.merged.namedSuspectId }
          : {}),
        overallConfidence: Math.round(c.merged.overallConfidence * 1000) / 1000,
        strength: caseStrength(c, sim.currentDay),
      };
    });

  // Compile current directives for officers in this jurisdiction
  const idMapCopy = cloneIdMap(sim.idMap);
  const jurOfficers: Record<string, LoopOfficer> = {};
  for (const o of Object.values(sim.officers)) {
    if (o.jurisdictionId === jurisdictionId) jurOfficers[o.id] = o;
  }
  const tick = planPoliceForTick({
    seed: sim.seed,
    day: sim.currentDay,
    hour: sim.currentHour,
    officers: jurOfficers,
    respondedCases: sim.respondedCases,
    crimeState: activeState,
    places: sim.places,
    streetGraph: sim.streetGraph,
    idMap: idMapCopy,
  });

  const directives: OfficerPlanDirective[] = tick.directives;

  return {
    jurisdictionId,
    day: sim.currentDay,
    year: sim.year,
    heat: Math.round(snap.heat * 1000) / 1000,
    wantedIds: snap.wantedIds.slice(),
    officers: directives,
    cases,
  };
}

/**
 * Evaluates an officer seeing the player (`G005 recognitionChance`) and returns what
 * the officer decides to do (`ignore | watch | approach_and_question | arrest`) plus
 * an optional compiled G007 plan string to act out.
 */
export function spotted(
  sim: LoopSim,
  officerId: string,
  sighting: OfficerSightingInput,
): SpotResult {
  const officer = sim.officers[officerId];
  const jurisdictionId =
    officer?.jurisdictionId ??
    (sighting.placeId ? sim.places[sighting.placeId]?.jurisdictionId : undefined) ??
    Object.values(sim.places)[0]?.jurisdictionId ??
    'capital';
  const targetId = sighting.targetId ?? sim.playerId;
  const appearance = sighting.appearance ?? sim.playerAppearance;
  const activeState = activeCrimeStateForPolice(sim);

  const chance = recognitionChance(
    activeState,
    sim.world,
    officerId,
    { id: targetId, appearance },
    sim.currentDay,
    {
      jurisdictionId,
      isPolice: true,
      light: sighting.light ?? 0.9,
      distance: sighting.distance,
      year: sim.year,
    },
  );

  const snap = policeKnowledge(activeState, jurisdictionId, sim.currentDay);
  const openCases = snap.cases.slice().sort((a, b) => b.severity - a.severity);
  const topCase =
    openCases.find((c) => c.merged.namedSuspectId === targetId) ??
    openCases[0];
  const topStrength = topCase ? caseStrength(topCase, sim.currentDay).combinedScore : 0;
  const named = topCase?.merged.namedSuspectId === targetId;

  let action: LoopSpotAction = 'ignore';
  let reason = 'No active interest in this person.';

  if (topCase) {
    const effective = clamp01(chance + (sighting.fleeing ? 0.22 : 0));
    if (
      (effective >= 0.72 && (named || topCase.severity >= 0.45 || topStrength >= 0.45)) ||
      (named && effective >= 0.52 && topStrength >= 0.55)
    ) {
      action = 'arrest';
      reason = named
        ? `Officer positively identified named suspect for ${topCase.kind}.`
        : `Officer matched suspect closely for ${topCase.kind}.`;
    } else if (effective >= 0.36 || (named && effective >= 0.24)) {
      action = 'approach_and_question';
      reason = `Officer recognized description matching open ${topCase.kind} case.`;
    } else if (effective >= 0.16) {
      action = 'watch';
      reason = `Officer noticed someone loosely fitting the ${topCase.kind} description.`;
    }
  }

  let planSource: string | undefined;
  if (officer && action !== 'ignore') {
    const idMap = cloneIdMap(sim.idMap);
    const targetPlanId = toPlanId(idMap, targetId, 'person');
    const allPlacePlanIds = Array.from(
      new Set([
        ...sim.streetGraph.nodes.map((n) => toPlanId(idMap, n.id, 'node')),
        ...Object.keys(sim.places).map((pid) => toPlanId(idMap, pid, 'place')),
      ]),
    );
    const rawPlan =
      action === 'arrest'
        ? `go person:${targetPlanId} quickly then restrain person:${targetPlanId} with item:cuffs_${officer.planId}`
        : action === 'approach_and_question'
          ? `go person:${targetPlanId} then ask person:${targetPlanId} politely`
          : `watch person:${targetPlanId} carefully`;
    planSource = validateLoopPlan(officer, rawPlan, allPlacePlanIds, [targetPlanId]);
  }

  return {
    officerId,
    jurisdictionId,
    targetId,
    action,
    recognitionChance: Math.round(chance * 1000) / 1000,
    ...(topCase && action !== 'ignore' ? { matchedCaseId: topCase.crimeId } : {}),
    reason,
    ...(planSource !== undefined ? { planSource } : {}),
  };
}

/**
 * Stand-in arrest resolution for the playable loop (replaced later by G010 court):
 * imposes a fine or night(s) in a cell based on severity, case strength, and prior record,
 * confiscates stolen items, enrolls booking prints/DNA into G008 `identityFiles`
 * (which can surface cold cases), and marks resolved cases closed.
 */
export function arrest(sim: LoopSim, input: ArrestInput): ArrestOutcomeResult {
  const jurId = input.jurisdictionId;
  const activeState = activeCrimeStateForPolice(sim);
  const jur = activeState.jurisdictions[jurId];

  const targetCases: PoliceCase[] = [];
  if (jur) {
    const allowed = input.crimeIds ? new Set(input.crimeIds) : undefined;
    for (const c of Object.values(jur.cases)) {
      if (allowed && !allowed.has(c.crimeId)) continue;
      targetCases.push(c);
    }
  }
  targetCases.sort((a, b) => b.severity - a.severity || (a.crimeId < b.crimeId ? -1 : 1));

  // Enroll booking prints/DNA into G008 identityFiles (and check for cold cases!)
  const beforeColdMatches = new Set<string>();
  for (const j of Object.values(sim.crimeState.jurisdictions)) {
    for (const c of Object.values(j.cases)) {
      for (const m of c.evidenceMatches ?? []) {
        beforeColdMatches.add(`${c.crimeId}:${m.traceId}`);
      }
    }
  }

  let nextCrimeState = enrollOnFile(sim.crimeState, {
    id: `file:arrest:${jurId}:${sim.currentDay}:${sim.nextSeq}`,
    personId: sim.playerId,
    jurisdictionId: jurId,
    scope: sim.year >= 1990 ? 'national' : 'local',
    reason: 'arrest',
    enrolledDay: sim.currentDay,
    enrolledYear: sim.year,
    hasPrints: sim.year >= 1905,
    hasDna: sim.year >= 1986,
  });

  const ctx = buildCrimeContext(sim);
  nextCrimeState = reexamineColdCases(nextCrimeState, sim.world, sim.currentDay, ctx);

  const coldCasesTriggered: string[] = [];
  for (const j of Object.values(nextCrimeState.jurisdictions)) {
    for (const c of Object.values(j.cases)) {
      if (sim.crimeMeta[c.crimeId]?.resolved) continue;
      for (const m of c.evidenceMatches ?? []) {
        const key = `${c.crimeId}:${m.traceId}`;
        if (!beforeColdMatches.has(key) && m.suspectId === sim.playerId) {
          if (!coldCasesTriggered.includes(c.crimeId)) {
            coldCasesTriggered.push(c.crimeId);
          }
        }
      }
    }
  }
  coldCasesTriggered.sort();

  const charges: ArrestChargeSummary[] = targetCases.map((c) => {
    const str = caseStrength(c, sim.currentDay);
    const meta = sim.crimeMeta[c.crimeId];
    return {
      crimeId: c.crimeId,
      kind: c.kind,
      severity: c.severity,
      caseStrength: Math.round(str.combinedScore * 1000) / 1000,
      placeName: meta?.placeName ?? sim.places[c.placeId]?.name ?? c.placeId,
    };
  });

  const priorCount = sim.priorOffenses.length;
  const maxSeverity = charges.reduce((m, c) => Math.max(m, c.severity), 0);
  const totalCaseStrength =
    charges.length > 0
      ? Math.round(
          (charges.reduce((s, c) => s + c.caseStrength, 0) / charges.length) * 1000,
        ) / 1000
      : 0;

  let penalty: ArrestOutcomeResult['penalty'] = 'warning';
  let fineAmount = 0;
  let jailHours = 0;

  if (charges.length === 0) {
    penalty = 'warning';
  } else if (maxSeverity >= 0.60 || (priorCount >= 2 && maxSeverity >= 0.25)) {
    penalty = 'jail';
    const nights = Math.min(7, 2 + priorCount + Math.floor(maxSeverity * 3));
    jailHours = nights * 24;
    fineAmount = Math.round((40 + maxSeverity * 160 + priorCount * 25) * (input.surrendered ? 0.8 : 1));
  } else if (maxSeverity >= 0.36 || priorCount >= 1 || (input.playerCash !== undefined && input.playerCash < 15)) {
    penalty = 'cell_night';
    jailHours = 12 + priorCount * 12;
    fineAmount = Math.round((15 + maxSeverity * 60) * (input.surrendered ? 0.8 : 1));
  } else {
    penalty = 'fine';
    fineAmount = Math.max(10, Math.round((15 + maxSeverity * 50) * (input.surrendered ? 0.8 : 1)));
    jailHours = 0;
  }

  const jailNights = jailHours > 0 ? Math.max(1, Math.round(jailHours / 12)) : 0;

  // Confiscate any carried stolen items belonging to resolved crimes
  const resolvedSet = new Set(charges.map((c) => c.crimeId));
  const carriedSet = input.carriedItemIds ? new Set(input.carriedItemIds) : undefined;
  const confiscatedItems: string[] = [];
  const nextStolenItems = { ...sim.stolenItems };
  for (const [itemId, rec] of Object.entries(sim.stolenItems)) {
    const belongs = resolvedSet.has(rec.crimeId) || (carriedSet !== undefined && carriedSet.has(itemId));
    if (belongs && !rec.pawnedAtPlaceId) {
      confiscatedItems.push(itemId);
      delete nextStolenItems[itemId];
    }
  }
  confiscatedItems.sort();

  // Mark charged crimes resolved & ban player from victimized shops
  const nextCrimeMeta: Record<string, CrimeMetaRecord> = { ...sim.crimeMeta };
  const nextFamiliarity: Record<string, PlayerFamiliarity> = { ...sim.familiarity };
  const nextPriorOffenses: PriorOffenseRecord[] = sim.priorOffenses.slice();
  let nextSeq = sim.nextSeq + 1;

  for (let i = 0; i < charges.length; i++) {
    const ch = charges[i]!;
    const meta = nextCrimeMeta[ch.crimeId];
    if (meta) {
      nextCrimeMeta[ch.crimeId] = { ...meta, resolved: true };
      if (meta.ownerId && sim.peopleMeta[meta.ownerId]) {
        const fam = cloneFamiliarity(nextFamiliarity[meta.ownerId]);
        if (!fam.bannedFromPlaceIds.includes(meta.placeId)) {
          fam.bannedFromPlaceIds.push(meta.placeId);
        }
        nextFamiliarity[meta.ownerId] = fam;
      }
    }
    nextPriorOffenses.push({
      id: `prior:${sim.currentDay}:${nextSeq++}`,
      crimeId: ch.crimeId,
      kind: ch.kind,
      severity: ch.severity,
      jurisdictionId: jurId,
      day: sim.currentDay,
      penalty,
      fineAmount: i === 0 ? fineAmount : 0,
      jailHours: i === 0 ? jailHours : 0,
    });
  }

  // Advance clock if spending night(s) in a cell
  const totalHours = sim.currentHour + jailHours;
  const extraDays = Math.floor(totalHours / 24);
  const nextDay = sim.currentDay + extraDays;
  const nextHour = Math.round((totalHours - extraDays * 24) * 1000) / 1000;

  const nextSim: LoopSim = {
    ...sim,
    currentDay: nextDay,
    currentHour: nextHour,
    crimeState: nextCrimeState,
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    familiarity: nextFamiliarity,
    priorOffenses: nextPriorOffenses,
    nextSeq,
  };

  const summary =
    penalty === 'warning'
      ? 'Released with a stern warning.'
      : penalty === 'fine'
        ? `Fined $${fineAmount} for ${charges.length} charge(s).`
        : penalty === 'cell_night'
          ? `Held in a cell for ${jailHours}h and fined $${fineAmount}.`
          : `Jailed for ${jailHours}h (${jailNights} night(s)) and fined $${fineAmount}.`;

  return {
    sim: nextSim,
    jurisdictionId: jurId,
    penalty,
    fineAmount,
    jailHours,
    jailNights,
    charges,
    priorOffenses: nextPriorOffenses.length,
    totalCaseStrength,
    confiscatedItems,
    coldCasesTriggered,
    summary,
  };
}

/** Serializes the entire simulation state to a plain JSON string. */
export function save(sim: LoopSim): string {
  return JSON.stringify(sim);
}

/** Restores a `LoopSim` from a JSON string produced by `save()`. */
export function load(serialized: string): LoopSim {
  const parsed = JSON.parse(serialized) as Partial<LoopSim>;
  if (!parsed || typeof parsed !== 'object' || parsed.version !== 1) {
    throw new Error('Invalid LoopSim save payload: expected version 1 JSON object');
  }
  if (!parsed.world || !parsed.crimeState || !parsed.idMap || !parsed.places || !parsed.peopleMeta) {
    throw new Error('Invalid LoopSim save payload: missing required state fields');
  }
  return parsed as LoopSim;
}
