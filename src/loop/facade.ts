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
import { FIRST_YEAR, LAST_YEAR, formatMoney, priceIndex, wage } from '../economy/eras';
import {
  POLICE_ERA,
  decideSpotAction,
  getActiveWantedCases,
  selectBestMatchingCase,
} from '../police';
import {
  compileSpotPlan,
  createLoopPolice,
  resolveStopPolicy,
  stepLoopPolice,
} from './policeBridge';
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
  SpotStopOutcome,
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

function clampEconomyYear(year: number): number {
  return Math.max(FIRST_YEAR, Math.min(LAST_YEAR, Math.round(year)));
}

function eraPriceIndex(year: number): number {
  return priceIndex(clampEconomyYear(year)) ?? 1;
}

function eraFormatMoney(cents: number, year: number): string {
  const rounded = Math.round(cents);
  return formatMoney(rounded, clampEconomyYear(year)) ?? `$${(rounded / 100).toFixed(2)}`;
}

function defaultShiftPayCents(hours: number, year: number): number {
  const w = wage('shop_clerk', clampEconomyYear(year));
  const hourlyCents = w ? w.hourlyCents : Math.max(10, Math.round(1750 * eraPriceIndex(year)));
  return Math.max(1, Math.round(hours * hourlyCents));
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
      staff: p.staff ? Array.from(new Set(p.staff)) : [],
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

  // Link explicit place.staff entries into peopleMeta.workplacePlaceId when unset
  for (const place of Object.values(places)) {
    for (let si = 0; si < place.staff.length; si++) {
      const sid = place.staff[si]!;
      const meta = peopleMeta[sid];
      if (meta && meta.workplacePlaceId === undefined) {
        meta.workplacePlaceId = place.id;
      }
    }
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
  const stopPolicy = resolveStopPolicy(init.stopPolicy);
  const { policeState, officers } = createLoopPolice({
    seed: init.seed,
    year: init.year,
    startDay,
    startHour,
    places,
    streetGraph,
    peopleCount: Object.keys(peopleMeta).length,
    crimeState,
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
    ...(init.playerDescription !== undefined ? { playerDescription: init.playerDescription } : {}),
    playerAppearance,
    stopPolicy,
    world,
    crimeState,
    policeState,
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
    respondedCases: { ...policeState.respondedCases },
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

  // --- 2. WORK SHIFT (legitimate era-cent pay; advances shift hours itself; staff & employer learn player's name) ---
  if (event.kind === 'work_shift') {
    const hours = Math.max(1, event.hours ?? 4);
    const payEarned =
      event.pay !== undefined
        ? Math.max(0, Math.round(event.pay))
        : defaultShiftPayCents(hours, sim.year);
    const employerId = event.employerId ?? place?.ownerId;
    const coworkerSet = new Set<string>(event.coworkerIds ?? []);
    if (place?.staff) {
      for (const sid of place.staff) {
        if (sid !== employerId && sid !== sim.playerId && sim.peopleMeta[sid]) {
          coworkerSet.add(sid);
        }
      }
    }
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
    const factLine = `You (${sim.playerName}) worked an honest ${hours}-hour shift${taskPhrase} at ${placeName} on ${dayName}.`;

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

    const interimSim: LoopSim = {
      ...sim,
      playerAppearance,
      workShifts: [...sim.workShifts, shiftRecord],
      familiarity: nextFamiliarity,
      nextSeq,
    };

    // Clock ownership: work_shift advances the shift's hours itself and returns world changes
    const adv = advance(interimSim, hours, event.weather ? { weather: event.weather } : undefined);

    return {
      sim: adv.sim,
      kind: 'work_shift',
      legit: true,
      payEarned,
      witnessedBy: [],
      tracesLeft: [],
      day: adv.toDay,
      hour: adv.toHour,
      daysElapsed: adv.daysElapsed,
      policePlans: adv.policePlans,
      attitudeChanges: adv.attitudeChanges,
      newPoliceCases: adv.newPoliceCases,
      pawnLeadsMatched: adv.pawnLeadsMatched,
      summary: `Worked a ${hours}-hour shift at ${placeName} for ${eraFormatMoney(payEarned, sim.year)}.`,
    };
  }

  // --- 3. SELL (pawn shop tracks item, serial & seller identity; stolen items return legit: false, hot: true) ---
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
    const isHot = Boolean(matchedStolen || event.item.stolenFromCrimeId);
    const saleValueCents =
      event.item.value !== undefined
        ? Math.max(0, Math.round(event.item.value))
        : Math.max(1, Math.round(1000 * eraPriceIndex(sim.year)));

    let nextCrimeState = sim.crimeState;
    const nextStolenItems = { ...sim.stolenItems };
    const nextPawnRecords = sim.pawnRecords.slice();
    let pawnTraceFlagged = false;

    if (isPawnShop || isHot) {
      const pawnId = `pawn:${sim.currentDay}:${nextSeq++}`;
      const record: PawnRecord = {
        id: pawnId,
        placeId: event.placeId,
        ...(clerkId !== undefined ? { clerkId } : {}),
        itemId: event.item.id,
        label: event.item.label,
        value: saleValueCents,
        day: sim.currentDay,
        ...(serialNumber !== undefined ? { serialNumber } : {}),
        ...(stolenFromCrimeId !== undefined ? { stolenFromCrimeId } : {}),
        sellerId: sim.playerId,
        reportedToPolice: false,
      };
      nextPawnRecords.push(record);

      if (isHot) {
        pawnTraceFlagged = true;
        if (matchedStolen) {
          nextStolenItems[matchedStolen.itemId] = {
            ...matchedStolen,
            pawnedAtPlaceId: event.placeId,
            pawnedDay: sim.currentDay,
          };
        }
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
      legit: !isHot,
      hot: isHot,
      pawnTraceFlagged,
      witnessedBy: [],
      tracesLeft: [],
      summary: isHot
        ? `Sold stolen ${event.item.label} at ${placeName} (flagged hot).`
        : `Sold ${event.item.label} at ${placeName}.`,
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
    const idx = Math.max(0.02, eraPriceIndex(sim.year));
    const resolvedItemCents =
      event.item.value !== undefined
        ? Math.max(0, Math.round(event.item.value))
        : Math.max(1, Math.round(500 * idx));
    const normalized2025Cents = resolvedItemCents / idx;
    const valBoost = resolvedItemCents > 0 ? Math.min(0.14, normalized2025Cents / 40000) : 0;
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
    itemValue = resolvedItemCents;
    explicitSerial = event.item.serialNumber;
    gloved = Boolean(event.gloved);
    exposure = subkind === 'yard' || subkind === 'pickpocket' ? 'outdoor' : 'indoor';
  } else if (event.kind === 'take_vehicle') {
    crimeKind = 'car_theft';
    severity = DEFAULT_SEVERITY.car_theft;
    ownerId = event.ownerId ?? place?.ownerId;
    itemId = event.vehicle.id;
    itemLabel = `${event.vehicle.color} ${event.vehicle.kind}`;
    itemValue =
      event.vehicle.value !== undefined
        ? Math.max(0, Math.round(event.vehicle.value))
        : Math.max(100, Math.round(80000 * eraPriceIndex(sim.year)));
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

  // Determine bystanders present: start with explicit scene bystanders, then add on-duty place.staff
  // when the shop/place is open (unless explicitly overridden in event.bystanders).
  const sceneBystanders: SceneBystander[] = (event.bystanders ?? []).slice();
  const sceneById = new Map<string, SceneBystander>();
  for (let i = 0; i < sceneBystanders.length; i++) {
    const sb = sceneBystanders[i]!;
    sceneById.set(sb.id, sb);
  }
  const isShopOpenNow = placeIsOpen(
    place,
    sim.currentHour,
    event.kind === 'enter' ? event.isOpen : undefined,
  );
  if (place && isShopOpenNow && place.staff.length > 0) {
    for (let si = 0; si < place.staff.length; si++) {
      const staffId = place.staff[si]!;
      if (staffId === sim.playerId || sceneById.has(staffId) || !sim.world.people[staffId]) {
        continue;
      }
      const staffBystander: SceneBystander = {
        id: staffId,
        position: { x: place.position.x, y: place.position.y },
        activity: 'working',
        hasLineOfSight: true,
      };
      sceneBystanders.push(staffBystander);
      sceneById.set(staffId, staffBystander);
    }
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

  // Step real G009 police (src/police/engine.ts) for active (unresolved) cases
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
  const policeTick = stepLoopPolice({
    policeState: sim.policeState,
    activeCrimeState: activeState,
    fullCrimeState: crimeState,
    world: sim.world,
    places: sim.places,
    streetGraph: sim.streetGraph,
    day: toDay,
    hour: toHour,
    year: sim.year,
    idMap: interimSim.idMap,
  });

  const nextSim: LoopSim = {
    ...interimSim,
    crimeState: policeTick.crimeState,
    policeState: policeTick.policeState,
    officers: policeTick.officers,
    respondedCases: { ...policeTick.policeState.respondedCases },
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
    facts.push(`I know you by name as ${playerNameKnown}.`);
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
      facts.push(`I saw you ${phrase} on ${cMeta.dayName}.`);
      sawPlayerDirectly = true;
    } else if (isOwnerOrVictim) {
      facts.push(
        tellerName
          ? `${tellerName} told me you managed to ${phrase} on ${cMeta.dayName}.`
          : `I heard you managed to ${phrase} on ${cMeta.dayName}.`,
      );
    } else if (knowsPlayerName) {
      facts.push(
        tellerName
          ? `${tellerName} mentioned you tried to ${phrase} on ${cMeta.dayName}.`
          : `Word around town is you tried to ${phrase} on ${cMeta.dayName}.`,
      );
    } else if (sim.playerDescription) {
      facts.push(
        tellerName
          ? `${tellerName} mentioned someone matching ${sim.playerDescription} tried to ${phrase} on ${cMeta.dayName}.`
          : `Word around town is someone matching ${sim.playerDescription} tried to ${phrase} on ${cMeta.dayName}.`,
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
    const descSuffix = sim.playerDescription && !knowsPlayerName ? ` (${sim.playerDescription})` : '';
    facts.push(`Local police are actively looking for you${descSuffix}.`);
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

  // Compile live directives via real G009 police (non-mutating copy)
  const idMapCopy = cloneIdMap(sim.idMap);
  const tick = stepLoopPolice({
    policeState: sim.policeState,
    activeCrimeState: activeState,
    fullCrimeState: sim.crimeState,
    world: sim.world,
    places: sim.places,
    streetGraph: sim.streetGraph,
    day: sim.currentDay,
    hour: sim.currentHour,
    year: sim.year,
    idMap: idMapCopy,
    filterJurisdictionId: jurisdictionId,
  });

  return {
    jurisdictionId,
    day: sim.currentDay,
    year: sim.year,
    heat: Math.round(snap.heat * 1000) / 1000,
    wantedIds: snap.wantedIds.slice(),
    officers: tick.directives,
    cases,
  };
}

/**
 * Evaluates an officer seeing the player via G005 `recognitionChance` and G009 `src/police/spotting.ts`,
 * returning `ignore | watch | approach_and_question | arrest`.
 *
 * When `action === 'approach_and_question'`, runs a real street stop (era-gated papers check,
 * questions, then `released` or `arrested` by case strength) governed by `sim.stopPolicy` (`STOP_POLICY`).
 */
export function spotted(
  sim: LoopSim,
  officerId: string,
  sighting: OfficerSightingInput,
): SpotResult {
  const realOfficer = sim.policeState.officers[officerId];
  const officer = sim.officers[officerId];
  const jurisdictionId =
    realOfficer?.jurisdictionId ??
    officer?.jurisdictionId ??
    (sighting.placeId ? sim.places[sighting.placeId]?.jurisdictionId : undefined) ??
    Object.values(sim.places)[0]?.jurisdictionId ??
    'capital';
  const targetId = sighting.targetId ?? sim.playerId;
  const appearance = sighting.appearance ?? sim.playerAppearance;
  const policy = sim.stopPolicy;

  const activeState = activeCrimeStateForPolice(sim);
  const chance = recognitionChance(
    activeState,
    sim.world,
    realOfficer?.personId ?? officerId,
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
  const activeWanted = getActiveWantedCases(snap.cases, sim.currentDay);
  const targetPlace = sighting.placeId ? sim.places[sighting.placeId] : undefined;
  const observedTarget = {
    personId: targetId,
    position: targetPlace ? targetPlace.position : { x: 0, y: 0 },
    nodeId: targetPlace?.nodeId ?? 'street',
    jurisdictionId,
    appearance,
    fleeing: Boolean(sighting.fleeing),
  };

  // Delegate matching to real G009 selectBestMatchingCase, falling back to open cases if needed
  const bestMatch = selectBestMatchingCase(activeWanted, observedTarget);
  const openCases = snap.cases.slice().sort((a, b) => b.severity - a.severity);
  const topCase =
    bestMatch?.matchedCase.policeCase ??
    openCases.find((c) => c.merged.namedSuspectId === targetId) ??
    openCases[0];

  const topStrength = topCase ? caseStrength(topCase, sim.currentDay).combinedScore : 0;
  const named = topCase?.merged.namedSuspectId === targetId;

  let action: LoopSpotAction = 'ignore';
  let reason = 'No active interest in this person.';
  let stopOutcome: SpotStopOutcome | undefined;
  let askPapers = false;

  if (topCase) {
    const effective = clamp01(chance + (sighting.fleeing ? policy.fleeingBonus : 0));
    const g009Action = realOfficer
      ? decideSpotAction({
          officer: realOfficer,
          person: observedTarget,
          policeCase: topCase,
          recognition: effective,
          heat: snap.heat,
          year: sim.year,
          era: POLICE_ERA,
        })
      : 'question';

    if (
      (effective >= policy.directArrestRecognition &&
        (named || topStrength >= policy.directArrestStrength || topCase.severity >= 0.65)) ||
      (named &&
        effective >= policy.namedDirectArrestRecognition &&
        topStrength >= policy.namedDirectArrestStrength) ||
      (Boolean(sighting.fleeing) &&
        effective >= policy.questionRecognition &&
        (named || topStrength >= policy.stopArrestStrength))
    ) {
      action = 'arrest';
      reason = named
        ? `Officer positively identified named suspect for ${topCase.kind}.`
        : `Officer matched suspect closely for ${topCase.kind}.`;
    } else if (
      effective >= policy.questionRecognition ||
      (named && effective >= policy.namedQuestionRecognition)
    ) {
      action = 'approach_and_question';
      askPapers =
        sim.year >= POLICE_ERA.papersCheckStartYear &&
        (g009Action === 'ask_papers' ||
          snap.heat >= policy.papersCheckHeat ||
          topCase.severity >= policy.papersCheckSeverity);

      const roundedStrength = Math.round(topStrength * 1000) / 1000;
      const shouldArrest = topStrength >= policy.stopArrestStrength || Boolean(sighting.fleeing);
      stopOutcome = {
        checkedPapers: askPapers,
        questioned: true,
        outcome: shouldArrest ? 'arrested' : 'released',
        caseStrength: roundedStrength,
        thresholdUsed: policy.stopArrestStrength,
        reason: shouldArrest
          ? askPapers
            ? `Checked papers and asked questions; case strength (${roundedStrength}) meets arrest threshold (${policy.stopArrestStrength}).`
            : `Asked questions; case strength (${roundedStrength}) meets arrest threshold (${policy.stopArrestStrength}).`
          : askPapers
            ? `Checked papers and asked questions, but case strength (${roundedStrength}) is below arrest threshold (${policy.stopArrestStrength}); released.`
            : `Asked questions, but case strength (${roundedStrength}) is below arrest threshold (${policy.stopArrestStrength}); released.`,
      };
      reason = stopOutcome.reason;
    } else if (effective >= policy.watchRecognition) {
      action = 'watch';
      reason = `Officer noticed someone loosely fitting the ${topCase.kind} description.`;
    }
  }

  let planSource: string | undefined;
  if (realOfficer && action !== 'ignore') {
    const idMap = cloneIdMap(sim.idMap);
    const targetPlanId = toPlanId(idMap, targetId, 'person');
    const timeSec = sim.currentDay * 86400 + sim.currentHour * 3600;
    planSource = compileSpotPlan({
      officer: realOfficer,
      action,
      targetPlanId,
      streetGraph: sim.streetGraph,
      idMap,
      timeSec,
      askPapers,
      highSeverity: (topCase?.severity ?? 0) >= 0.65,
    });
  }

  return {
    officerId,
    jurisdictionId,
    targetId,
    action,
    recognitionChance: Math.round(chance * 1000) / 1000,
    ...(topCase && action !== 'ignore' ? { matchedCaseId: topCase.crimeId } : {}),
    reason,
    ...(stopOutcome !== undefined ? { stopOutcome } : {}),
    ...(planSource !== undefined ? { planSource } : {}),
  };
}

/**
 * Stand-in arrest resolution for the playable loop (replaced later by G010 court):
 * - When called from a street stop (`fromStop: true` or `why: 'approach_and_question'`) on a thin
 *   case below `sim.stopPolicy.stopArrestStrength`, releases the player with a warning and no booking.
 * - Otherwise imposes era-scaled integer-cent fines (via `priceIndex(year)`) or cell/jail time,
 *   confiscates stolen items, enrolls booking prints/DNA into G008 `identityFiles` (cold cases),
 *   records real G009 `ArrestRecord` entries, and runs any `jailHours` through `advance()` so
 *   gossip, reports, heat decay, and police plans progress during time served.
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

  const maxCaseStrength = charges.reduce((m, c) => Math.max(m, c.caseStrength), 0);
  const totalCaseStrength =
    charges.length > 0
      ? Math.round(
          (charges.reduce((s, c) => s + c.caseStrength, 0) / charges.length) * 1000,
        ) / 1000
      : 0;

  // Real street stop release when called from questioning on a thin case
  const isFromStop = Boolean(input.fromStop || input.why === 'approach_and_question');
  if (
    isFromStop &&
    !input.forceArrest &&
    (charges.length === 0 || maxCaseStrength < sim.stopPolicy.stopArrestStrength)
  ) {
    return {
      sim,
      jurisdictionId: jurId,
      penalty: 'warning',
      fineAmount: 0,
      jailHours: 0,
      jailNights: 0,
      day: sim.currentDay,
      hour: sim.currentHour,
      daysElapsed: 0,
      releasedAfterStop: true,
      charges: [],
      priorOffenses: sim.priorOffenses.length,
      totalCaseStrength,
      confiscatedItems: [],
      coldCasesTriggered: [],
      policePlans: [],
      attitudeChanges: [],
      newPoliceCases: [],
      summary: 'Stopped and questioned, then released — the case is too thin to arrest.',
    };
  }

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

  const priorCount = sim.priorOffenses.length;
  const maxSeverity = charges.reduce((m, c) => Math.max(m, c.severity), 0);
  const idx = eraPriceIndex(sim.year);
  const surrenderMult = input.surrendered ? 0.8 : 1;
  const minCashForFineCents = Math.max(10, Math.round(1500 * idx));

  let penalty: ArrestOutcomeResult['penalty'] = 'warning';
  let fineAmount = 0;
  let jailHours = 0;

  if (charges.length === 0) {
    penalty = 'warning';
  } else if (maxSeverity >= 0.60 || (priorCount >= 2 && maxSeverity >= 0.25)) {
    penalty = 'jail';
    const nights = Math.min(7, 2 + priorCount + Math.floor(maxSeverity * 3));
    jailHours = nights * 24;
    fineAmount = Math.max(
      25,
      Math.round((4000 + maxSeverity * 16000 + priorCount * 2500) * surrenderMult * idx),
    );
  } else if (
    maxSeverity >= 0.36 ||
    priorCount >= 1 ||
    (input.playerCash !== undefined && input.playerCash < minCashForFineCents)
  ) {
    penalty = 'cell_night';
    jailHours = 12 + priorCount * 12;
    fineAmount = Math.max(
      10,
      Math.round((1500 + maxSeverity * 6000) * surrenderMult * idx),
    );
  } else {
    penalty = 'fine';
    fineAmount = Math.max(
      10,
      Math.round((1500 + maxSeverity * 5000) * surrenderMult * idx),
    );
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

  // Mark charged crimes resolved, ban player from victimized shops, and append G009 ArrestRecord entries
  const nextCrimeMeta: Record<string, CrimeMetaRecord> = { ...sim.crimeMeta };
  const nextFamiliarity: Record<string, PlayerFamiliarity> = { ...sim.familiarity };
  const nextPriorOffenses: PriorOffenseRecord[] = sim.priorOffenses.slice();
  const nextPoliceArrests = sim.policeState.arrests.slice();
  let policeSeq = sim.policeState.nextSeq;
  let nextSeq = sim.nextSeq + 1;

  const arrestingOfficerId =
    input.officerId ??
    Object.values(sim.policeState.officers).find((o) => o.jurisdictionId === jurId)?.id ??
    'officer_unknown';
  const timeSec = sim.currentDay * 86400 + sim.currentHour * 3600;

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
    const arrestingOfficer = sim.policeState.officers[arrestingOfficerId];
    const stationNodeId = arrestingOfficer?.stationNodeId ?? 'station';
    const stationPlace = Object.values(sim.places).find(
      (p) => p.jurisdictionId === jurId && p.kind === 'police_station',
    );
    nextPoliceArrests.push({
      id: `arrest_${policeSeq++}`,
      crimeId: ch.crimeId,
      suspectId: sim.playerId,
      arrestingOfficerId,
      assistingOfficerIds: [],
      jurisdictionId: jurId,
      stationNodeId,
      arrestDay: sim.currentDay,
      arrestYear: sim.year,
      arrestTimeSec: timeSec,
      arrestPosition: stationPlace ? { x: stationPlace.position.x, y: stationPlace.position.y } : { x: 0, y: 0 },
      method: input.surrendered ? 'surrender' : 'foot_pursuit',
      searchedInventory: (input.carriedItemIds ?? []).slice(),
      seizedContraband: confiscatedItems.slice(),
      cuffed: true,
      transportedToStation: true,
    });
  }

  const bookedSim: LoopSim = {
    ...sim,
    crimeState: nextCrimeState,
    policeState: {
      ...sim.policeState,
      arrests: nextPoliceArrests,
      nextSeq: policeSeq,
    },
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    familiarity: nextFamiliarity,
    priorOffenses: nextPriorOffenses,
    nextSeq,
  };

  // Run any cell / jail time through advance() so gossip, reports, decay & police plans run normally
  let finalSim = bookedSim;
  let daysElapsed = 0;
  let policePlans: OfficerPlanDirective[] = [];
  let attitudeChanges: AttitudeChange[] = [];
  let newPoliceCases: string[] = [];

  if (jailHours > 0) {
    const adv = advance(bookedSim, jailHours);
    finalSim = adv.sim;
    daysElapsed = adv.daysElapsed;
    policePlans = adv.policePlans;
    attitudeChanges = adv.attitudeChanges;
    newPoliceCases = adv.newPoliceCases;
  }

  const fineText = eraFormatMoney(fineAmount, sim.year);
  const summary =
    penalty === 'warning'
      ? 'Released with a stern warning.'
      : penalty === 'fine'
        ? `Fined ${fineText} for ${charges.length} charge(s).`
        : penalty === 'cell_night'
          ? `Held in a cell for ${jailHours}h and fined ${fineText}.`
          : `Jailed for ${jailHours}h (${jailNights} night(s)) and fined ${fineText}.`;

  return {
    sim: finalSim,
    jurisdictionId: jurId,
    penalty,
    fineAmount,
    jailHours,
    jailNights,
    day: finalSim.currentDay,
    hour: finalSim.currentHour,
    daysElapsed,
    charges,
    priorOffenses: nextPriorOffenses.length,
    totalCaseStrength,
    confiscatedItems,
    coldCasesTriggered,
    policePlans,
    attitudeChanges,
    newPoliceCases,
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
  if (!parsed.stopPolicy) {
    parsed.stopPolicy = resolveStopPolicy();
  }
  for (const place of Object.values(parsed.places)) {
    if (!Array.isArray(place.staff)) place.staff = [];
  }
  if (!parsed.policeState && parsed.streetGraph) {
    const restored = createLoopPolice({
      seed: parsed.seed ?? 'restored',
      year: parsed.year ?? 1978,
      startDay: parsed.currentDay ?? 0,
      startHour: parsed.currentHour ?? 8,
      places: parsed.places,
      streetGraph: parsed.streetGraph,
      peopleCount: Object.keys(parsed.peopleMeta).length,
      crimeState: parsed.crimeState,
      idMap: parsed.idMap,
    });
    parsed.policeState = restored.policeState;
    parsed.officers = restored.officers;
  }
  return parsed as LoopSim;
}
