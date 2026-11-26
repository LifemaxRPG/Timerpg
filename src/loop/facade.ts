import {
  DEFAULT_SEVERITY,
  FORENSIC_ERA,
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
  reportLatencyForEra,
  witnessesOf,
  type Bystander,
  type CrimeAccount,
  type CrimeContext,
  type CrimeEvent,
  type CrimeKind,
  type CrimeState,
  type CulpritAppearance,
  type IdentityFileRecord,
  type PendingReport,
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
import { fenceDeal, fenceRisk, type Fence } from '../economy/fence';
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
import {
  DEFAULT_TIME_MACHINE_FUEL,
  addLoopCarriedItem,
  buildTimeMachineView,
  ensureTimeMachine,
  ensureTimeline,
  looseThreadHooksForPerson,
  performTimeJump,
  recordArsonInTimeline,
  removeLoopCarriedItem,
  syncSimToTimeline,
} from './timeJump';
import { branch } from '../timeline';
import {
  applyBailDecision,
  applyChooseCounsel,
  applyCourtInterference,
  buildCourtView,
  createCourtCaseFromArrest,
  executeTrialOnDate,
  findTargetCourtCase,
  issueMissedDateWarrant,
  pruneResolvedCourtCases,
  shouldRouteArrestToCourt,
} from './courtBridge';
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
  BailDecisionInput,
  BailDecisionResult,
  BookingColdCaseHit,
  BookingReport,
  ChooseCounselInput,
  ChooseCounselResult,
  CivilianSightingInput,
  CivilianSpotResult,
  CourtInterferenceInput,
  CourtInterferenceResult,
  CourtViewResult,
  JumpInput,
  JumpResult,
  LoopCarriedItem,
  TimeMachineViewResult,
  CrimeMetaRecord,
  LoopActionEvent,
  LoopCourtCase,
  LoopCourtSentence,
  LoopInit,
  LoopPersonInit,
  LoopPersonMeta,
  LoopPlaceRecord,
  LoopSim,
  LoopSpotAction,
  LoopTrialEventSummary,
  LoopWarrantRecord,
  OfficerPlanDirective,
  OfficerSightingInput,
  PawnLeadMatch,
  PawnRecord,
  PendingReportMeta,
  PlayerFamiliarity,
  PoliceViewCase,
  PoliceViewResult,
  PleaInput,
  PleaResult,
  PriorOffenseRecord,
  ReportMeans,
  ResolvedCrimeMemory,
  SceneBystander,
  SpotResult,
  SpotStopOutcome,
  StolenItemRecord,
  TipOffInput,
  TipOffResult,
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
    ...(f.fenceDeals !== undefined ? { fenceDeals: f.fenceDeals } : {}),
    ...(f.fenceTrust !== undefined ? { fenceTrust: f.fenceTrust } : {}),
    ...(f.informedOnPlayer !== undefined ? { informedOnPlayer: f.informedOnPlayer } : {}),
    ...(f.fenceRefusedTooHot !== undefined ? { fenceRefusedTooHot: f.fenceRefusedTooHot } : {}),
  };
}

function stationDistanceKmForPlace(sim: LoopSim, placeId?: string): number {
  const origin = placeId ? sim.places[placeId] : undefined;
  let bestMeters = Infinity;
  if (origin) {
    for (const pl of Object.values(sim.places)) {
      if (pl.kind !== 'police_station') continue;
      const d = Math.hypot(pl.position.x - origin.position.x, pl.position.y - origin.position.y);
      if (d < bestMeters) bestMeters = d;
    }
  }
  if (!Number.isFinite(bestMeters)) return 1.5;
  return Math.max(0.5, Math.round((bestMeters / 1000) * 100) / 100);
}

function reportMeansForEra(year: number, delayHours: number): ReportMeans {
  if (year < 1900) return 'in_person';
  if (year < 1935) return delayHours >= 6 ? 'in_person' : 'early_telephone';
  if (year < 2000) return 'telephone';
  return 'mobile';
}

function formatReportMeansLabel(means: ReportMeans): string {
  switch (means) {
    case 'in_person':
      return 'in person';
    case 'early_telephone':
      return 'by early telephone';
    case 'telephone':
      return 'by telephone';
    case 'mobile':
      return 'by mobile phone';
  }
}

function formatEvidenceMethodLabel(method: string): string {
  switch (method) {
    case 'fingerprint':
      return 'fingerprints';
    case 'dna':
      return 'DNA';
    case 'serial_registry':
      return 'serial registry';
    case 'plate_registry':
      return 'licence plate registry';
    case 'camera_face':
      return 'camera footage';
    default:
      return method.replace(/_/g, ' ');
  }
}

function buildFenceProfile(
  sim: LoopSim,
  fenceId: string,
  placeName: string,
  overrides?: Partial<Fence>,
): Fence {
  const person = sim.world.people[fenceId];
  const derivedHonesty = person
    ? clamp01(0.15 + person.traits.diligence * 0.25 + ((person.reputation + 1) * 0.2))
    : 0.3;
  const derivedGreed = person
    ? clamp01(0.45 + person.traits.ambition * 0.45)
    : 0.65;
  return {
    personId: fenceId,
    cover: overrides?.cover ?? 'secondhand dealer',
    where: overrides?.where ?? placeName,
    hours: overrides?.hours ?? ([20, 2] as const),
    categories: overrides?.categories ?? [
      'valuables',
      'hand_tools',
      'power_tools',
      'electronics',
      'instruments',
      'clothing',
    ],
    honesty: clamp01(overrides?.honesty ?? derivedHonesty),
    greed: clamp01(overrides?.greed ?? derivedGreed),
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
    stationDistanceKm: (placeId: string) => stationDistanceKmForPlace(sim, placeId),
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
    // (the court's crime kinds, G013: said as a person would say them)
    case 'bribery':
      return `pay someone off at ${meta.placeName}`;
    case 'perjury':
      return `lie under oath at ${meta.placeName}`;
    case 'intimidation':
      return `threaten someone at ${meta.placeName}`;
    default:
      return `commit ${meta.kind.replace(/_/g, ' ')} at ${meta.placeName}`;
  }
}

function activeCrimeStateForPolice(sim: LoopSim): CrimeState {
  const hiddenIds = new Set<string>();
  for (const [cid, meta] of Object.entries(sim.crimeMeta)) {
    if (meta.resolved) hiddenIds.add(cid);
  }
  if (sim.courtCases) {
    for (const cc of Object.values(sim.courtCases)) {
      if (cc.status === 'pending_trial' && cc.custody !== 'warrant_for_fta') {
        for (let i = 0; i < cc.crimeIds.length; i++) {
          hiddenIds.add(cc.crimeIds[i]!);
        }
      }
    }
  }
  if (hiddenIds.size === 0) return sim.crimeState;
  const filteredJurs: CrimeState['jurisdictions'] = {};
  for (const [jurId, jur] of Object.entries(sim.crimeState.jurisdictions)) {
    const activeCases: Record<string, PoliceCase> = {};
    for (const [cid, c] of Object.entries(jur.cases)) {
      if (!hiddenIds.has(cid)) activeCases[cid] = c;
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
      ...(p.businessId !== undefined ? { businessId: p.businessId } : {}),
      ...(p.slot !== undefined ? { slot: p.slot } : {}),
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

  const baseSim: LoopSim = {
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
  const hasExplicitTimeInit =
    init.timeline !== undefined ||
    init.initialFuel !== undefined ||
    init.maxFuel !== undefined ||
    init.timeMachinePlaceId !== undefined ||
    init.timelineStartYear !== undefined ||
    init.timelinePlaceId !== undefined ||
    init.playerBirthYear !== undefined;
  if (hasExplicitTimeInit) {
    baseSim.timeMachine = ensureTimeMachine(baseSim, {
      ...(init.initialFuel !== undefined ? { initialFuel: init.initialFuel } : {}),
      ...(init.maxFuel !== undefined ? { maxFuel: init.maxFuel } : {}),
      ...(init.timeMachinePlaceId !== undefined ? { machinePlaceId: init.timeMachinePlaceId } : {}),
    });
    if (
      init.timeline !== undefined ||
      init.timelineStartYear !== undefined ||
      init.timelinePlaceId !== undefined ||
      init.playerBirthYear !== undefined
    ) {
      baseSim.timeline =
        init.timeline ??
        ensureTimeline(baseSim, {
          ...(init.timelineStartYear !== undefined ? { startYear: init.timelineStartYear } : {}),
          ...(init.timelinePlaceId !== undefined ? { placeId: init.timelinePlaceId } : {}),
          ...(init.playerBirthYear !== undefined ? { birthYear: init.playerBirthYear } : {}),
        });
    }
  }
  return baseSim;
}

/**
 * Records a player action in the current scene (`buy`, `sell`, `work_shift`, `take`,
 * `take_vehicle`, `enter`), evaluates witness sightlines via G005 `witnessesOf`,
 * records crimes & physical/serial traces via G008 `leaveTraces`, and updates
 * familiarity or pawn-shop registries.
 */
export function act(sim: LoopSim, event: LoopActionEvent): ActResult {
  if (event.kind === 'court_interference') {
    const res = courtInterfere(sim, {
      courtCaseId: event.courtCaseId,
      kind: event.interferenceKind,
      targetId: event.targetId,
      placeId: event.placeId,
      bribeAmount: event.bribeAmount,
      bystanders: event.bystanders,
      caughtInCourt: event.caughtInCourt,
    });
    return {
      sim: res.sim,
      kind: 'court_interference',
      legit: false,
      ...(res.crimeId ? { crimeId: res.crimeId } : {}),
      witnessedBy: res.witnessIds.map((pid) => ({
        personId: pid,
        name: res.sim.peopleMeta[pid]?.name ?? pid,
        confidence: 0.85,
        recognizedPlayer: true,
        isVictim: pid === res.targetId,
        description: { ...res.sim.playerAppearance },
      })),
      tracesLeft: [],
      interferenceSucceeded: res.succeeded,
      interferenceReported: res.targetReported,
      summary: res.summary,
    };
  }
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
    const ownerName = clerkId ? sim.peopleMeta[clerkId]?.name : undefined;
    const boughtCarried: LoopCarriedItem = {
      id: event.item.id,
      label: event.item.label,
      value: event.item.value ?? 100,
      acquiredYear: sim.year,
      acquiredDay: sim.currentDay,
      acquiredPlaceId: event.placeId,
      acquiredPlaceName: placeName,
      stolen: false,
      ...(event.item.serialNumber ? { serialNumber: event.item.serialNumber } : {}),
      ...(clerkId ? { ownerId: clerkId } : {}),
      ...(ownerName ? { ownerName } : {}),
      ...(place?.businessId ? { businessId: place.businessId } : {}),
      businessName: placeName,
      provenanceNote: `Bought ${event.item.label} at ${placeName} in ${sim.year}.`,
    };
    const nextCarried = addLoopCarriedItem(sim.carriedItems, boughtCarried);
    const nextSim: LoopSim = {
      ...sim,
      playerAppearance,
      familiarity: nextFamiliarity,
      carriedItems: nextCarried,
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

    const nextCarried = removeLoopCarriedItem(sim.carriedItems, event.item.id);
    const nextSim: LoopSim = {
      ...sim,
      playerAppearance,
      crimeState: nextCrimeState,
      stolenItems: nextStolenItems,
      pawnRecords: nextPawnRecords,
      familiarity: nextFamiliarity,
      ...(nextCarried !== undefined ? { carriedItems: nextCarried } : {}),
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

  // --- 3b. FENCE_SALE (Sam's fences: standing from deals, fenceRisk informant tips via era latency) ---
  if (event.kind === 'fence_sale') {
    const fenceId = event.fenceId;
    const fenceMeta = sim.peopleMeta[fenceId];
    const fencePerson = sim.world.people[fenceId];
    const fenceName =
      fenceMeta?.name ?? (fencePerson ? `${fencePerson.first} ${fencePerson.surname}` : fenceId);
    const fenceProfile = buildFenceProfile(sim, fenceId, placeName, event.fence);

    const jurId =
      place?.jurisdictionId ?? Object.values(sim.places)[0]?.jurisdictionId ?? 'capital';
    const existingStolen = sim.stolenItems[event.item.id];
    const linkedCrimeId = event.item.stolenFromCrimeId ?? existingStolen?.crimeId;
    const isHot = Boolean(linkedCrimeId || existingStolen);

    const curHeat = policeKnowledge(activeCrimeStateForPolice(sim), jurId, sim.currentDay).heat;
    const heat01 = clamp01(event.heat !== undefined ? event.heat : curHeat / 100);
    const prevFam = sim.familiarity[fenceId];
    const prevTrust = clamp01(prevFam?.fenceTrust ?? 0);
    const clampedYear = clampEconomyYear(sim.year);

    let accepted = false;
    let offerCents = 0;
    let trustAfter = prevTrust;
    let refusalReason: 'category' | 'too_hot' | 'no_offer' | undefined;

    if (event.catalogueItemId) {
      const deal = fenceDeal(fenceProfile, event.catalogueItemId, clampedYear, heat01, prevTrust);
      if (deal.accepted) {
        accepted = true;
        offerCents = deal.offerCents;
        trustAfter = deal.trustAfter;
      } else {
        refusalReason = deal.reason;
      }
    } else if (heat01 > 0.7 + 0.3 * prevTrust) {
      refusalReason = 'too_hot';
    } else {
      const baseValue = Math.max(
        10,
        Math.round(event.item.value ?? existingStolen?.value ?? Math.round(2500 * eraPriceIndex(sim.year))),
      );
      const bargaining = 0.42 - 0.1 * fenceProfile.greed + 0.14 * prevTrust;
      const heatDiscount = 1 - heat01 * (0.45 - 0.25 * prevTrust);
      offerCents = Math.max(1, Math.floor(baseValue * bargaining * heatDiscount));
      trustAfter = Math.min(100, Math.round(prevTrust * 100) + 10) / 100;
      accepted = true;
    }

    const nextFamiliarity: Record<string, PlayerFamiliarity> = { ...sim.familiarity };
    const fam = cloneFamiliarity(nextFamiliarity[fenceId]);

    if (!accepted) {
      if (refusalReason === 'too_hot') {
        fam.fenceRefusedTooHot = true;
        fam.trustDelta = Math.round((fam.trustDelta - 0.08) * 1000) / 1000;
        const fact = 'I turned away your hot goods — too much police heat right now.';
        if (!fam.positiveFacts.includes(fact)) fam.positiveFacts.push(fact);
      }
      nextFamiliarity[fenceId] = fam;
      const refusedSim: LoopSim = {
        ...sim,
        playerAppearance,
        familiarity: nextFamiliarity,
      };
      const nextStance = attitude(refusedSim, fenceId).stance;
      const updatedSim: LoopSim = {
        ...refusedSim,
        lastStances: { ...sim.lastStances, [fenceId]: nextStance },
      };
      return {
        sim: updatedSim,
        kind: 'fence_sale',
        legit: false,
        hot: isHot,
        payEarned: 0,
        pawnTraceFlagged: false,
        fenceAccepted: false,
        fenceRefusalReason: refusalReason,
        fenceTrustAfter: prevTrust,
        tipOffQueued: false,
        witnessedBy: [],
        tracesLeft: [],
        summary:
          refusalReason === 'too_hot'
            ? `${fenceName} refused ${event.item.label} — too much police heat.`
            : `${fenceName} declined to buy ${event.item.label} (${refusalReason ?? 'no_offer'}).`,
      };
    }

    // Accepted fence sale: mark stolen item fenced (no pawn registry!) and grow fence standing
    const nextStolenItems: Record<string, StolenItemRecord> = { ...sim.stolenItems };
    if (existingStolen) {
      nextStolenItems[event.item.id] = {
        ...existingStolen,
        pawnedAtPlaceId: event.placeId,
      };
    }

    const dealCount = (fam.fenceDeals ?? 0) + 1;
    fam.fenceDeals = dealCount;
    fam.fenceTrust = trustAfter;
    fam.fenceRefusedTooHot = false;
    fam.trustDelta = Math.min(0.85, Math.round((fam.trustDelta + 0.08) * 1000) / 1000);
    fam.positiveFacts = fam.positiveFacts.filter(
      (f) => !f.startsWith("We've done quiet business before") && !f.startsWith("I turned away your hot goods"),
    );
    fam.positiveFacts.push(
      `We've done quiet business before (${dealCount} ${dealCount === 1 ? 'deal' : 'deals'}).`,
    );
    nextFamiliarity[fenceId] = fam;

    const nextCarriedFenced = removeLoopCarriedItem(sim.carriedItems, event.item.id);
    let nextSim: LoopSim = {
      ...sim,
      playerAppearance,
      stolenItems: nextStolenItems,
      familiarity: nextFamiliarity,
      ...(nextCarriedFenced !== undefined ? { carriedItems: nextCarriedFenced } : {}),
      nextSeq: sim.nextSeq + 1,
    };

    // Roll Sam's fenceRisk(fence, year) with keyed deterministic RNG to see if the fence informs
    const riskProb = fenceRisk(fenceProfile, clampedYear) ?? 0.12;
    const roll = rand(sim.seed, 'fence_tip', sim.currentDay, fenceId, event.item.id, sim.nextSeq);
    const shouldTip = event.forceTip !== undefined ? event.forceTip : roll < riskProb;

    let tipRes: TipOffResult | undefined;
    if (shouldTip) {
      tipRes = tipOff(nextSim, {
        informantId: fenceId,
        itemId: event.item.id,
        ...(linkedCrimeId !== undefined ? { crimeId: linkedCrimeId } : {}),
        placeId: event.placeId,
        jurisdictionId: jurId,
        reason: `${fenceName} tipped off police after buying ${event.item.label}.`,
      });
      nextSim = tipRes.sim;
    } else {
      const updatedStance = attitude(nextSim, fenceId).stance;
      nextSim = {
        ...nextSim,
        lastStances: { ...nextSim.lastStances, [fenceId]: updatedStance },
      };
    }

    const payText = eraFormatMoney(offerCents, sim.year);
    return {
      sim: nextSim,
      kind: 'fence_sale',
      legit: false,
      hot: isHot,
      payEarned: offerCents,
      pawnTraceFlagged: false,
      fenceAccepted: true,
      fenceTrustAfter: trustAfter,
      tipOffQueued: Boolean(tipRes?.queued),
      ...(tipRes?.queued
        ? {
            tipDelayHours: tipRes.delayHours,
            tipArrivalDay: tipRes.arrivalDay,
            tipArrivalHour: tipRes.arrivalHour,
            tipMeans: tipRes.means,
          }
        : {}),
      witnessedBy: [],
      tracesLeft: [],
      summary: tipRes?.queued
        ? `Fenced ${event.item.label} to ${fenceName} for ${payText} (informant tipped off police).`
        : `Fenced ${event.item.label} to ${fenceName} for ${payText}.`,
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
          : subkind === 'robbery'
            ? 'robbery'
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
            : subkind === 'robbery'
              ? 0.80
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
  } else if (event.kind === 'assault') {
    crimeKind = event.lethal ? 'murder' : 'assault';
    severity = event.lethal ? 1.0 : event.weapon ? 0.82 : 0.72;
    ownerId = event.targetId;
    gloved = Boolean(event.gloved);
    exposure = place?.kind === 'street' || place?.kind === 'yard' ? 'outdoor' : 'indoor';
  } else if (event.kind === 'arson') {
    crimeKind = 'robbery';
    severity = 0.85;
    ownerId = place?.ownerId;
    gloved = Boolean(event.gloved);
    exposure = 'indoor';
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
      year: sim.year,
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

  let nextCarried = sim.carriedItems;
  if (event.kind === 'take' && itemId && itemLabel) {
    const oName = ownerId ? sim.peopleMeta[ownerId]?.name : undefined;
    nextCarried = addLoopCarriedItem(sim.carriedItems, {
      id: itemId,
      label: itemLabel,
      value: itemValue ?? 500,
      acquiredYear: sim.year,
      acquiredDay: sim.currentDay,
      acquiredPlaceId: event.placeId,
      acquiredPlaceName: placeName,
      stolen: true,
      ...(effectiveSerial ? { serialNumber: effectiveSerial } : {}),
      ...(ownerId ? { ownerId } : {}),
      ...(oName ? { ownerName: oName } : {}),
      ...(place?.businessId ? { businessId: place.businessId } : {}),
      businessName: placeName,
      crimeId,
      provenanceNote: `Taken from ${placeName} in ${sim.year}${effectiveSerial ? ` (serial ${effectiveSerial})` : ''}.`,
    });
  }

  let nextSim: LoopSim = {
    ...sim,
    playerAppearance: eventAppearance,
    crimeState: nextCrimeState,
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    familiarity: nextFamiliarity,
    ...(nextCarried !== undefined ? { carriedItems: nextCarried } : {}),
    nextSeq,
  };

  let burnedBusinessId: string | undefined;
  let burnedSlot: string | undefined;
  if (event.kind === 'arson') {
    const arsonRes = recordArsonInTimeline({
      sim: nextSim,
      crimeId,
      placeId: event.placeId,
      businessId: event.businessId,
      atNight: event.atNight ?? true,
      causeTag: event.causeTag,
      summary: event.summary,
      traces: traces.map((t) => ({ ...t })),
      witnesses: sightings.map((s) => ({
        personId: s.witnessId,
        name: sim.peopleMeta[s.witnessId]?.name ?? s.witnessId,
        confidence: s.confidence,
        identifiedCulprit: s.identifiedCulpritId === sim.playerId,
      })),
      reported: Boolean(nextCrimeState.jurisdictions[jurisdictionId]?.cases[crimeId]),
      policeCase: nextCrimeState.jurisdictions[jurisdictionId]?.cases[crimeId],
    });
    burnedBusinessId = arsonRes.businessId;
    burnedSlot = arsonRes.slot;
    nextSim = {
      ...nextSim,
      timeline: arsonRes.timeline,
    };
  } else if (event.kind === 'assault' && event.lethal && sim.timeline) {
    const tl = syncSimToTimeline(nextSim);
    nextSim = {
      ...nextSim,
      timeline: branch(tl, {
        year: sim.year,
        placeId: tl.placeId,
        kind: 'kill',
        targetId: event.targetId,
        crimeId,
        summary: `Killed ${sim.peopleMeta[event.targetId]?.name ?? event.targetId} in ${sim.year}.`,
      }),
    };
  }

  return {
    sim: nextSim,
    kind: event.kind,
    legit: false,
    ...(burnedBusinessId !== undefined ? { burnedBusinessId } : {}),
    ...(burnedSlot !== undefined ? { burnedSlot } : {}),
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

function formatBookingColdCaseFact(kind: CrimeKind, placeName: string, method: string): string {
  const kindLabel = kind.replace(/_/g, ' ');
  const methodLabel = formatEvidenceMethodLabel(method);
  if (method === 'fingerprint') {
    return `Booking fingerprints matched an unsolved ${kindLabel} at ${placeName}.`;
  }
  if (method === 'dna') {
    return `Booking DNA matched an unsolved ${kindLabel} at ${placeName}.`;
  }
  return `Booking ${methodLabel} matched an unsolved ${kindLabel} at ${placeName}.`;
}

function collectBookingPawnLinks(
  sim: LoopSim,
  suspectId: string,
  excludedCrimeIds?: ReadonlySet<string>,
): PawnLeadMatch[] {
  const out: PawnLeadMatch[] = [];
  const seen = new Set<string>();
  for (let i = 0; i < sim.pawnRecords.length; i++) {
    const pawn = sim.pawnRecords[i]!;
    if (pawn.sellerId !== suspectId || !pawn.stolenFromCrimeId) continue;
    if (sim.crimeMeta[pawn.stolenFromCrimeId]?.resolved) continue;
    if (excludedCrimeIds?.has(pawn.stolenFromCrimeId)) continue;
    const traced =
      pawn.reportedToPolice ||
      Boolean(sim.stolenItems[pawn.itemId]?.tracedToPlayer) ||
      pawn.serialNumber !== undefined;
    if (!traced) continue;
    const key = `${pawn.stolenFromCrimeId}:${pawn.itemId}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      crimeId: pawn.stolenFromCrimeId,
      ...(pawn.serialNumber !== undefined ? { serialNumber: pawn.serialNumber } : {}),
      pawnShopId: pawn.placeId,
      suspectId,
    });
  }
  return out;
}

/**
 * Shared station booking pipeline (G008 identityFiles + cold-case re-examination + pawn links).
 * Called both by `arrest()` and when a G009 police arrest finishes transport to the station during `advance()`.
 */
function bookSuspectAtStation(params: {
  sim: LoopSim;
  crimeState: CrimeState;
  jurisdictionId: string;
  suspectId: string;
  day: number;
  seq: number;
  confiscatedItems?: readonly string[];
}): {
  crimeState: CrimeState;
  report: BookingReport;
  coldCasesTriggered: string[];
} {
  const { sim, jurisdictionId, suspectId, day, seq, confiscatedItems } = params;
  let nextCrimeState = params.crimeState;

  const beforeMatches = new Set<string>();
  for (const j of Object.values(nextCrimeState.jurisdictions)) {
    for (const c of Object.values(j.cases)) {
      for (const m of c.evidenceMatches ?? []) {
        beforeMatches.add(`${c.crimeId}:${m.traceId}`);
      }
    }
  }

  const printsEnrolled = sim.year >= FORENSIC_ERA.fingerprintLocalYear;
  const dnaEnrolled = sim.year >= FORENSIC_ERA.dnaAnalysisYear;
  const scope: 'local' | 'national' =
    sim.year >= FORENSIC_ERA.fingerprintNationalYear ? 'national' : 'local';

  nextCrimeState = enrollOnFile(nextCrimeState, {
    id: `file:arrest:${jurisdictionId}:${day}:${seq}`,
    personId: suspectId,
    jurisdictionId,
    scope,
    reason: 'arrest',
    enrolledDay: day,
    enrolledYear: sim.year,
    hasPrints: printsEnrolled,
    hasDna: dnaEnrolled,
  });

  const ctx = buildCrimeContext(sim);
  nextCrimeState = reexamineColdCases(nextCrimeState, sim.world, day, ctx);

  const coldCaseHits: BookingColdCaseHit[] = [];
  const coldCasesTriggered: string[] = [];
  const jurIds = Object.keys(nextCrimeState.jurisdictions).sort();
  for (let ji = 0; ji < jurIds.length; ji++) {
    const j = nextCrimeState.jurisdictions[jurIds[ji]!]!;
    const caseIds = Object.keys(j.cases).sort();
    for (let ci = 0; ci < caseIds.length; ci++) {
      const c = j.cases[caseIds[ci]!]!;
      if (sim.crimeMeta[c.crimeId]?.resolved) continue;
      for (const m of c.evidenceMatches ?? []) {
        const key = `${c.crimeId}:${m.traceId}`;
        if (!beforeMatches.has(key) && m.suspectId === suspectId) {
          const placeName =
            sim.crimeMeta[c.crimeId]?.placeName ?? sim.places[c.placeId]?.name ?? c.placeId;
          const crimeYear =
            sim.crimeMeta[c.crimeId]?.year ??
            sim.crimeState.crimes[c.crimeId]?.when.year ??
            sim.timeline?.crimes[c.crimeId]?.year;
          const yearsToMatch =
            crimeYear !== undefined ? Math.max(0, sim.year - crimeYear) : undefined;
          const fact = formatBookingColdCaseFact(c.kind, placeName, m.method);
          coldCaseHits.push({
            crimeId: c.crimeId,
            kind: c.kind,
            placeName,
            method: m.method,
            confidence: Math.round(m.confidence * 1000) / 1000,
            ...(crimeYear !== undefined ? { crimeYear, yearsToMatch } : {}),
            fact,
          });
          if (!coldCasesTriggered.includes(c.crimeId)) {
            coldCasesTriggered.push(c.crimeId);
          }
        }
      }
    }
  }
  coldCasesTriggered.sort();

  const pawnLinks = collectBookingPawnLinks(sim, suspectId);
  const findings: string[] = [];

  if (printsEnrolled && dnaEnrolled) {
    findings.push(`Fingerprints and DNA taken at booking and filed with ${scope} records.`);
  } else if (printsEnrolled) {
    findings.push(`Fingerprints taken at booking and filed with ${scope} records.`);
  } else {
    findings.push('Booked at the station (before fingerprinting was in use).');
  }

  for (let i = 0; i < coldCaseHits.length; i++) {
    findings.push(coldCaseHits[i]!.fact);
  }

  for (let i = 0; i < pawnLinks.length; i++) {
    const link = pawnLinks[i]!;
    const shopName = sim.places[link.pawnShopId]?.name ?? link.pawnShopId;
    const pawnRec = sim.pawnRecords.find(
      (r) => r.stolenFromCrimeId === link.crimeId && r.placeId === link.pawnShopId,
    );
    const itemDesc = pawnRec?.label ?? sim.crimeMeta[link.crimeId]?.itemLabel ?? 'stolen property';
    const serialNote = link.serialNumber ? ` (serial ${link.serialNumber})` : '';
    findings.push(`Pawn ledger at ${shopName} linked ${itemDesc}${serialNote} to you.`);
  }

  if (confiscatedItems && confiscatedItems.length > 0) {
    findings.push(
      `Confiscated ${confiscatedItems.length} stolen ${confiscatedItems.length === 1 ? 'item' : 'items'} at booking.`,
    );
  }

  return {
    crimeState: nextCrimeState,
    coldCasesTriggered,
    report: {
      jurisdictionId,
      suspectId,
      day,
      year: sim.year,
      printsEnrolled,
      dnaEnrolled,
      scope,
      coldCaseHits,
      pawnLinks,
      findings,
    },
  };
}

function deliverDueTimedReports(params: {
  sim: LoopSim;
  crimeState: CrimeState;
  pendingReportMeta: Record<string, PendingReportMeta>;
  respondedCases: Record<string, string>;
  latestSightingPlaceByCrime: Record<string, string>;
  upToTotalHours: number;
  day: number;
}): {
  crimeState: CrimeState;
  pendingReportMeta: Record<string, PendingReportMeta>;
  respondedCases: Record<string, string>;
  latestSightingPlaceByCrime: Record<string, string>;
  deliveredCount: number;
  deliveredCrimeIds: string[];
} {
  const { sim, upToTotalHours, day } = params;
  let crimeState = params.crimeState;
  const remainingPending: PendingReport[] = [];
  let nextMeta = params.pendingReportMeta;
  let metaCopied = false;
  let nextResponded = params.respondedCases;
  let respondedCopied = false;
  let nextSightings = params.latestSightingPlaceByCrime;
  let sightingsCopied = false;
  let deliveredCount = 0;
  const deliveredCrimeIds: string[] = [];

  for (let i = 0; i < crimeState.pendingReports.length; i++) {
    const rep = crimeState.pendingReports[i]!;
    const meta = nextMeta[rep.id];
    if (!meta || meta.arrivalTotalHours > upToTotalHours) {
      remainingPending.push(rep);
      continue;
    }

    if (!metaCopied) {
      nextMeta = { ...nextMeta };
      metaCopied = true;
    }
    delete nextMeta[rep.id];

    if (sim.crimeMeta[rep.crimeId]?.resolved) continue;
    const crime = crimeState.crimes[rep.crimeId];
    if (!crime) continue;

    const delivered: PoliceReport = {
      id: rep.id,
      crimeId: rep.crimeId,
      reporterId: rep.reporterId,
      jurisdictionId: rep.jurisdictionId,
      filedDay: rep.filedDay,
      receivedDay: day,
      delayHours: rep.delayHours,
      source: rep.source,
      isVictim: rep.isVictim,
      confidence: rep.confidence,
      ...(rep.identifiedCulpritId ? { identifiedCulpritId: rep.identifiedCulpritId } : {}),
      description: rep.description,
    };

    const prevJur = crimeState.jurisdictions[rep.jurisdictionId];
    crimeState = {
      ...crimeState,
      jurisdictions: {
        ...crimeState.jurisdictions,
        [rep.jurisdictionId]: ingestPoliceReport(prevJur, crime, delivered),
      },
    };

    const sceneTraces = crimeState.traces?.[rep.crimeId];
    if (sceneTraces && sceneTraces.some((t) => !t.collected && !t.destroyed)) {
      crimeState = collectCrimeTraces(crimeState, sim.world, rep.crimeId, rep.jurisdictionId, day, {
        year: sim.year,
      });
    }

    // Fresh civilian sighting or informant tip wakes police response for this case
    const respKey = `${rep.jurisdictionId}:${rep.crimeId}`;
    if (nextResponded[respKey]) {
      if (!respondedCopied) {
        nextResponded = { ...nextResponded };
        respondedCopied = true;
      }
      delete nextResponded[respKey];
    }

    if (meta.sightingPlaceId) {
      if (!sightingsCopied) {
        nextSightings = { ...nextSightings };
        sightingsCopied = true;
      }
      nextSightings[rep.crimeId] = meta.sightingPlaceId;
    }

    deliveredCrimeIds.push(rep.crimeId);
    deliveredCount++;
  }

  if (deliveredCount > 0 || remainingPending.length !== crimeState.pendingReports.length) {
    crimeState = {
      ...crimeState,
      pendingReports: remainingPending,
    };
  }

  return {
    crimeState,
    pendingReportMeta: nextMeta,
    respondedCases: nextResponded,
    latestSightingPlaceByCrime: nextSightings,
    deliveredCount,
    deliveredCrimeIds,
  };
}

/**
 * Advances the simulation by `hours` simulated hours:
 * - steps whole days through G005/G008 `advanceDay` (gossip, era-delayed reporting, trace decay, cold cases);
 * - runs police trace collection & pawn-shop lead matching;
 * - compiles G007 police plans for officers;
 * - returns new police plans and people whose attitude toward the player changed.
 */
function findEarliestDueCourtCase(
  sim: LoopSim,
  endTotalHours: number,
): LoopCourtCase | undefined {
  if (!sim.courtCases) return undefined;
  let earliest: LoopCourtCase | undefined;
  let earliestHours = Infinity;
  for (const cc of Object.values(sim.courtCases)) {
    if (cc.status !== 'pending_trial') continue;
    const dueHours = cc.courtDateDay * 24 + cc.courtDateHour;
    if (dueHours <= endTotalHours + 1e-6) {
      if (dueHours < earliestHours || (dueHours === earliestHours && earliest && cc.id < earliest.id)) {
        earliest = cc;
        earliestHours = dueHours;
      }
    }
  }
  return earliest;
}

function finalizeCourtDisposition(params: {
  sim: LoopSim;
  updatedCase: LoopCourtCase;
  convicted: boolean;
  sentence?: LoopCourtSentence;
  day: number;
}): LoopSim {
  const { sim, updatedCase, convicted, sentence, day } = params;
  const nextCrimeMeta: Record<string, CrimeMetaRecord> = { ...sim.crimeMeta };
  const nextFamiliarity: Record<string, PlayerFamiliarity> = { ...sim.familiarity };
  const nextPriorOffenses: PriorOffenseRecord[] = sim.priorOffenses.slice();
  let nextSeq = sim.nextSeq;

  for (let i = 0; i < updatedCase.crimeIds.length; i++) {
    const cid = updatedCase.crimeIds[i]!;
    const meta = nextCrimeMeta[cid];
    if (meta) {
      nextCrimeMeta[cid] = { ...meta, resolved: true };
      if (convicted && meta.ownerId && sim.peopleMeta[meta.ownerId]) {
        const fam = cloneFamiliarity(nextFamiliarity[meta.ownerId]);
        if (!fam.bannedFromPlaceIds.includes(meta.placeId)) {
          fam.bannedFromPlaceIds.push(meta.placeId);
        }
        nextFamiliarity[meta.ownerId] = fam;
      }
    }
    if (convicted) {
      const ch = updatedCase.charges.find((c) => c.crimeId === cid) ?? updatedCase.charges[0];
      const jailHours = i === 0 ? sentence?.jailHours ?? 0 : 0;
      const fineAmount = i === 0 ? sentence?.fineAmount ?? 0 : 0;
      nextPriorOffenses.push({
        id: `prior:${day}:${nextSeq++}`,
        crimeId: cid,
        kind: ch?.kind ?? meta?.kind ?? 'theft',
        severity: ch?.severity ?? 0.5,
        jurisdictionId: updatedCase.jurisdictionId,
        day,
        penalty: jailHours > 0 ? 'jail' : 'fine',
        fineAmount,
        jailHours,
      });
    }
  }

  return pruneResolvedState({
    ...sim,
    crimeMeta: nextCrimeMeta,
    familiarity: nextFamiliarity,
    priorOffenses: nextPriorOffenses,
    courtCases: {
      ...(sim.courtCases ?? {}),
      [updatedCase.id]: updatedCase,
    },
    nextSeq,
  });
}

function mergeAttitudeChanges(
  existing: AttitudeChange[],
  incoming: AttitudeChange[],
): AttitudeChange[] {
  const byId = new Map<string, AttitudeChange>();
  for (let i = 0; i < existing.length; i++) {
    byId.set(existing[i]!.personId, existing[i]!);
  }
  for (let i = 0; i < incoming.length; i++) {
    const ch = incoming[i]!;
    const prev = byId.get(ch.personId);
    if (prev) {
      if (prev.previousStance === ch.newStance) {
        byId.delete(ch.personId);
      } else {
        byId.set(ch.personId, {
          ...ch,
          previousStance: prev.previousStance,
        });
      }
    } else {
      byId.set(ch.personId, ch);
    }
  }
  return Array.from(byId.values());
}

export function advance(sim: LoopSim, hours: number, options?: AdvanceOptions): AdvanceResult {
  const safeHours = Math.max(0, hours);
  const startTotalHours = sim.currentDay * 24 + sim.currentHour;
  const endTotalHours = Math.round((startTotalHours + safeHours) * 1000) / 1000;
  const dueCourt = findEarliestDueCourtCase(sim, endTotalHours);
  if (!dueCourt) {
    return advanceCore(sim, safeHours, options);
  }

  const origFromDay = sim.currentDay;
  const origFromHour = sim.currentHour;
  const dueTotalHours = dueCourt.courtDateDay * 24 + dueCourt.courtDateHour;
  const hoursToCourt = Math.max(0, Math.round((dueTotalHours - startTotalHours) * 1000) / 1000);
  const preStep = advanceCore(sim, hoursToCourt, options);

  let workingSim = preStep.sim;
  let allPolicePlans = preStep.policePlans;
  let allAttitudeChanges = preStep.attitudeChanges.slice();
  const newCaseSet = new Set<string>(preStep.newPoliceCases);
  const allPawnLeads = preStep.pawnLeadsMatched.slice();
  const allBookings = (preStep.bookingEvents ?? []).slice();
  const courtEvents: LoopTrialEventSummary[] = [];

  const liveCase = workingSim.courtCases?.[dueCourt.id] ?? dueCourt;
  const inCustody =
    liveCase.custody === 'in_custody_bail_unpaid' ||
    liveCase.custody === 'remanded_no_bail' ||
    liveCase.custody === 'awaiting_bail_decision';
  let attended = true;
  if (!inCustody) {
    if (options?.attendCourt !== undefined) {
      attended = options.attendCourt;
    } else if (options?.playerPlaceId !== undefined) {
      attended = options.playerPlaceId === liveCase.courtPlaceId;
    }
  }

  let servedSentence = false;
  if (!attended) {
    const fta = issueMissedDateWarrant({
      sim: workingSim,
      crimeState: workingSim.crimeState,
      courtCase: liveCase,
      day: workingSim.currentDay,
      hour: workingSim.currentHour,
      seq: workingSim.nextSeq,
    });
    const nextResponded = { ...workingSim.policeState.respondedCases };
    delete nextResponded[`${liveCase.jurisdictionId}:${liveCase.primaryCrimeId}`];
    const nextOfficers = { ...workingSim.policeState.officers };
    for (const [oid, o] of Object.entries(nextOfficers)) {
      if (
        o.jurisdictionId === liveCase.jurisdictionId &&
        (o.assignment === 'patrol' ||
          (o.targetCrimeId === liveCase.primaryCrimeId &&
            (o.assignment === 'respond_crime' || o.assignment === 'stakeout')))
      ) {
        nextOfficers[oid] = {
          ...o,
          assignment: 'patrol',
          targetCrimeId: undefined,
          targetNodeId: undefined,
          planSource: undefined,
          planState: undefined,
        };
      }
    }
    const nextWarrants: LoopWarrantRecord[] = [
      ...(workingSim.warrants ?? []),
      fta.warrant,
    ];
    workingSim = {
      ...workingSim,
      crimeState: fta.crimeState,
      policeState: {
        ...workingSim.policeState,
        officers: nextOfficers,
        respondedCases: nextResponded,
      },
      respondedCases: nextResponded,
      courtCases: {
        ...(workingSim.courtCases ?? {}),
        [fta.updatedCase.id]: fta.updatedCase,
      },
      warrants: nextWarrants,
      nextSeq: workingSim.nextSeq + 1,
    };
    newCaseSet.add(liveCase.primaryCrimeId);
    courtEvents.push({
      courtCaseId: liveCase.id,
      jurisdictionId: liveCase.jurisdictionId,
      crimeIds: liveCase.crimeIds.slice(),
      day: workingSim.currentDay,
      hour: workingSim.currentHour,
      kind: 'missed_court_date',
      warrant: fta.warrant,
      bailForfeitedCents: fta.bailForfeitedCents,
      facts: fta.facts,
      summary: fta.summary,
    });
    const warrantTick = advanceCore(workingSim, 0, options);
    workingSim = warrantTick.sim;
    allPolicePlans = warrantTick.policePlans;
    allAttitudeChanges = mergeAttitudeChanges(allAttitudeChanges, warrantTick.attitudeChanges);
    for (const cid of warrantTick.newPoliceCases) newCaseSet.add(cid);
    allPawnLeads.push(...warrantTick.pawnLeadsMatched);
    if (warrantTick.bookingEvents) allBookings.push(...warrantTick.bookingEvents);
  } else {
    const trialRes = executeTrialOnDate({
      sim: workingSim,
      courtCase: liveCase,
      day: workingSim.currentDay,
    });
    if (trialRes.outcome.verdict === 'guilty') {
      workingSim = finalizeCourtDisposition({
        sim: workingSim,
        updatedCase: trialRes.updatedCase,
        convicted: true,
        sentence: trialRes.sentence,
        day: workingSim.currentDay,
      });
    } else if (trialRes.outcome.verdict === 'not_guilty') {
      workingSim = finalizeCourtDisposition({
        sim: workingSim,
        updatedCase: trialRes.updatedCase,
        convicted: false,
        day: workingSim.currentDay,
      });
    } else {
      workingSim = {
        ...workingSim,
        courtCases: {
          ...(workingSim.courtCases ?? {}),
          [trialRes.updatedCase.id]: trialRes.updatedCase,
        },
      };
    }

    courtEvents.push({
      courtCaseId: liveCase.id,
      jurisdictionId: liveCase.jurisdictionId,
      crimeIds: liveCase.crimeIds.slice(),
      day: workingSim.currentDay,
      hour: workingSim.currentHour,
      kind: 'trial_completed',
      verdict: trialRes.outcome.verdict,
      guiltyVotes: trialRes.outcome.guiltyVotes,
      notGuiltyVotes: trialRes.outcome.notGuiltyVotes,
      deliberationRounds: trialRes.outcome.deliberationRounds,
      ...(trialRes.retrialDateDay !== undefined
        ? { retrialDateDay: trialRes.retrialDateDay, retrialDateHour: trialRes.retrialDateHour }
        : {}),
      ...(trialRes.sentence ? { sentence: trialRes.sentence } : {}),
      facts: trialRes.facts,
      summary: trialRes.summary,
    });

    if (
      trialRes.outcome.verdict === 'guilty' &&
      trialRes.sentence &&
      trialRes.sentence.jailHours > 0 &&
      !options?.skipSentenceAdvance
    ) {
      servedSentence = true;
      const jailAdv = advance(workingSim, trialRes.sentence.jailHours, options);
      workingSim = pruneResolvedState(jailAdv.sim);
      allPolicePlans = jailAdv.policePlans;
      allAttitudeChanges = mergeAttitudeChanges(allAttitudeChanges, jailAdv.attitudeChanges);
      for (const cid of jailAdv.newPoliceCases) newCaseSet.add(cid);
      allPawnLeads.push(...jailAdv.pawnLeadsMatched);
      if (jailAdv.bookingEvents) allBookings.push(...jailAdv.bookingEvents);
      if (jailAdv.courtEvents) courtEvents.push(...jailAdv.courtEvents);
    }
  }

  const curTotalHours = workingSim.currentDay * 24 + workingSim.currentHour;
  const remainingHours = Math.max(0, Math.round((endTotalHours - curTotalHours) * 1000) / 1000);
  if (!servedSentence && remainingHours > 0) {
    const postStep = advance(workingSim, remainingHours, options);
    workingSim = postStep.sim;
    allPolicePlans = postStep.policePlans;
    allAttitudeChanges = mergeAttitudeChanges(allAttitudeChanges, postStep.attitudeChanges);
    for (const cid of postStep.newPoliceCases) newCaseSet.add(cid);
    allPawnLeads.push(...postStep.pawnLeadsMatched);
    if (postStep.bookingEvents) allBookings.push(...postStep.bookingEvents);
    if (postStep.courtEvents) courtEvents.push(...postStep.courtEvents);
  }

  const newPoliceCases = Array.from(newCaseSet).sort();
  return {
    sim: workingSim,
    fromDay: origFromDay,
    toDay: workingSim.currentDay,
    fromHour: origFromHour,
    toHour: workingSim.currentHour,
    daysElapsed: workingSim.currentDay - origFromDay,
    policePlans: allPolicePlans,
    attitudeChanges: allAttitudeChanges,
    newPoliceCases,
    pawnLeadsMatched: allPawnLeads,
    ...(allBookings.length > 0 ? { bookingEvents: allBookings } : {}),
    ...(courtEvents.length > 0 ? { courtEvents } : {}),
  };
}

function advanceCore(sim: LoopSim, hours: number, options?: AdvanceOptions): AdvanceResult {
  const safeHours = Math.max(0, hours);
  const fromDay = sim.currentDay;
  const fromHour = sim.currentHour;
  const totalHours = fromHour + safeHours;
  const daysElapsed = Math.floor(totalHours / 24);
  const toDay = fromDay + daysElapsed;
  const toHour = Math.round((totalHours - daysElapsed * 24) * 1000) / 1000;

  const beforeCases = openPoliceCaseIds(sim);
  let crimeState = sim.crimeState;
  let nextPendingReportMeta: Record<string, PendingReportMeta> = sim.pendingReportMeta
    ? { ...sim.pendingReportMeta }
    : {};
  let nextLatestSightings: Record<string, string> = sim.latestSightingPlaceByCrime
    ? { ...sim.latestSightingPlaceByCrime }
    : {};
  let nextPoliceResponded: Record<string, string> = { ...sim.policeState.respondedCases };
  const freshlyDeliveredCrimeIds = new Set<string>();
  const nextPawnRecords = sim.pawnRecords.map((r) => ({ ...r }));
  const nextStolenItems = { ...sim.stolenItems };
  const pawnLeadsMatched: PawnLeadMatch[] = [];
  const ctx = buildCrimeContext(sim, options?.weather);
  const endTotalHours = Math.round((toDay * 24 + toHour) * 1000) / 1000;

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

    // 1b. Hold timed civilian/informant reports aside during advanceDay so exact arrivalHour is respected
    const heldTimed: PendingReport[] = [];
    if (Object.keys(nextPendingReportMeta).length > 0 && crimeState.pendingReports.length > 0) {
      const regularPending: PendingReport[] = [];
      for (let i = 0; i < crimeState.pendingReports.length; i++) {
        const rep = crimeState.pendingReports[i]!;
        if (nextPendingReportMeta[rep.id]) {
          heldTimed.push(rep);
        } else {
          regularPending.push(rep);
        }
      }
      if (heldTimed.length > 0) {
        crimeState = { ...crimeState, pendingReports: regularPending };
      }
    }

    // 2. Run G005/G008 daily engine (decay, gossip, era-delayed reporting, trace decay, cold-case match)
    crimeState = advanceDay(crimeState, sim.world, d, ctx);

    // 2b. Restore timed reports and deliver any whose arrivalTotalHours <= end of this step
    if (heldTimed.length > 0) {
      crimeState = {
        ...crimeState,
        pendingReports: [...crimeState.pendingReports, ...heldTimed],
      };
      const dayCutoff = d === toDay ? endTotalHours : d * 24 + 24;
      const delivered = deliverDueTimedReports({
        sim,
        crimeState,
        pendingReportMeta: nextPendingReportMeta,
        respondedCases: nextPoliceResponded,
        latestSightingPlaceByCrime: nextLatestSightings,
        upToTotalHours: dayCutoff,
        day: d,
      });
      crimeState = delivered.crimeState;
      nextPendingReportMeta = delivered.pendingReportMeta;
      nextPoliceResponded = delivered.respondedCases;
      nextLatestSightings = delivered.latestSightingPlaceByCrime;
      for (const cid of delivered.deliveredCrimeIds) freshlyDeliveredCrimeIds.add(cid);
    }

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

  // Also deliver any sub-day timed civilian/informant reports when advancing within the same day
  if (daysElapsed === 0 && Object.keys(nextPendingReportMeta).length > 0) {
    const delivered = deliverDueTimedReports({
      sim,
      crimeState,
      pendingReportMeta: nextPendingReportMeta,
      respondedCases: nextPoliceResponded,
      latestSightingPlaceByCrime: nextLatestSightings,
      upToTotalHours: endTotalHours,
      day: toDay,
    });
    crimeState = delivered.crimeState;
    nextPendingReportMeta = delivered.pendingReportMeta;
    nextPoliceResponded = delivered.respondedCases;
    nextLatestSightings = delivered.latestSightingPlaceByCrime;
    for (const cid of delivered.deliveredCrimeIds) freshlyDeliveredCrimeIds.add(cid);
  }

  // Step real G009 police (src/police/engine.ts) for active (unresolved) cases
  let policeInputState =
    nextPoliceResponded !== sim.policeState.respondedCases
      ? { ...sim.policeState, respondedCases: nextPoliceResponded }
      : sim.policeState;
  if (freshlyDeliveredCrimeIds.size > 0) {
    let rearmed = false;
    const nextOfficers = { ...policeInputState.officers };
    for (const [oid, o] of Object.entries(policeInputState.officers)) {
      if (
        o.targetCrimeId &&
        freshlyDeliveredCrimeIds.has(o.targetCrimeId) &&
        (o.assignment === 'respond_crime' || o.assignment === 'stakeout')
      ) {
        rearmed = true;
        nextOfficers[oid] = {
          ...o,
          assignment: 'patrol',
          targetCrimeId: undefined,
          targetNodeId: undefined,
          planSource: undefined,
          planState: undefined,
        };
      }
    }
    if (rearmed) {
      policeInputState = { ...policeInputState, officers: nextOfficers };
    }
  }
  const hasPendingMeta = Object.keys(nextPendingReportMeta).length > 0;
  const hasLatestSightings = Object.keys(nextLatestSightings).length > 0;
  const interimSim: LoopSim = {
    ...sim,
    currentDay: toDay,
    currentHour: toHour,
    crimeState,
    pawnRecords: nextPawnRecords,
    stolenItems: nextStolenItems,
    idMap: cloneIdMap(sim.idMap),
    ...(hasPendingMeta ? { pendingReportMeta: nextPendingReportMeta } : {}),
    ...(hasLatestSightings ? { latestSightingPlaceByCrime: nextLatestSightings } : {}),
  };
  if (!hasPendingMeta && interimSim.pendingReportMeta !== undefined) {
    delete interimSim.pendingReportMeta;
  }
  if (!hasLatestSightings && interimSim.latestSightingPlaceByCrime !== undefined) {
    delete interimSim.latestSightingPlaceByCrime;
  }
  const activeState = activeCrimeStateForPolice(interimSim);
  const policeTick = stepLoopPolice({
    policeState: policeInputState,
    activeCrimeState: activeState,
    fullCrimeState: crimeState,
    world: sim.world,
    places: sim.places,
    streetGraph: sim.streetGraph,
    day: toDay,
    hour: toHour,
    year: sim.year,
    idMap: interimSim.idMap,
    latestSightingPlaceByCrime: hasLatestSightings ? nextLatestSightings : undefined,
  });

  // Booking pipeline for any G009 arrest that just completed transport to the station
  let postTickCrimeState = policeTick.crimeState;
  let nextSeq = interimSim.nextSeq;
  const bookingEvents: BookingReport[] = [];
  const previouslyTransported = new Set<string>();
  for (let i = 0; i < sim.policeState.arrests.length; i++) {
    const a = sim.policeState.arrests[i]!;
    if (a.transportedToStation) previouslyTransported.add(a.id);
  }
  for (let i = 0; i < policeTick.policeState.arrests.length; i++) {
    const a = policeTick.policeState.arrests[i]!;
    if (!a.transportedToStation || previouslyTransported.has(a.id)) continue;
    const booked = bookSuspectAtStation({
      sim: interimSim,
      crimeState: postTickCrimeState,
      jurisdictionId: a.jurisdictionId,
      suspectId: a.suspectId,
      day: toDay,
      seq: nextSeq++,
      confiscatedItems: a.seizedContraband,
    });
    postTickCrimeState = booked.crimeState;
    bookingEvents.push(booked.report);
  }

  const nextSim: LoopSim = {
    ...interimSim,
    crimeState: postTickCrimeState,
    policeState: policeTick.policeState,
    officers: policeTick.officers,
    respondedCases: { ...policeTick.policeState.respondedCases },
    lastStances: { ...sim.lastStances },
    nextSeq,
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
    ...(bookingEvents.length > 0 ? { bookingEvents } : {}),
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
  const idSet = new Set<string>(Object.keys(sim.crimeState.ledger));
  if (sim.resolvedCrimeMemories) {
    for (const cid of Object.keys(sim.resolvedCrimeMemories)) idSet.add(cid);
  }
  return {
    crimeIds: Array.from(idSet).sort(),
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

  // Gather every crime account (active or pruned resolved memory) this person holds involving the player
  const knownCrimes: Array<{
    meta: CrimeMetaRecord;
    source: 'first_hand' | 'second_hand';
    heardFrom?: string;
    isVictim: boolean;
    confidence: number;
    severity: number;
  }> = [];
  const crimeIds = _cache
    ? _cache.crimeIds
    : Array.from(
        new Set([
          ...Object.keys(sim.crimeState.ledger),
          ...(sim.resolvedCrimeMemories ? Object.keys(sim.resolvedCrimeMemories) : []),
        ]),
      ).sort();
  for (let i = 0; i < crimeIds.length; i++) {
    const cid = crimeIds[i]!;
    const cMeta = sim.crimeMeta[cid];
    if (!cMeta) continue;
    const acc = sim.crimeState.ledger[cid]?.[personId];
    if (acc) {
      const crime = sim.crimeState.crimes[cid];
      if (!crime || crime.culpritId !== sim.playerId) continue;
      knownCrimes.push({
        meta: cMeta,
        source: acc.source,
        ...(acc.heardFrom !== undefined ? { heardFrom: acc.heardFrom } : {}),
        isVictim: acc.isVictim,
        confidence: acc.confidence,
        severity: crime.severity,
      });
      continue;
    }
    const prunedMem = sim.resolvedCrimeMemories?.[cid]?.[personId];
    if (prunedMem) {
      knownCrimes.push({
        meta: cMeta,
        source: prunedMem.s === 'f' ? 'first_hand' : 'second_hand',
        ...(prunedMem.h !== undefined ? { heardFrom: prunedMem.h } : {}),
        isVictim: prunedMem.v === 1,
        confidence: 1,
        severity: 0,
      });
    }
  }

  let worstUnresolvedSeverity = 0;
  let victimizedByPlayer = false;
  let sawPlayerDirectly = false;
  let maxConfidence = 0;
  let criminalPenalty = 0;

  for (let i = 0; i < knownCrimes.length; i++) {
    const acc = knownCrimes[i]!;
    const cMeta = acc.meta;
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
      if (acc.severity > worstUnresolvedSeverity) worstUnresolvedSeverity = acc.severity;
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

  const isActiveFence = Boolean(fam?.fenceDeals && fam.fenceDeals > 0 && !fam.informedOnPlayer);
  const effectivePenalty = isActiveFence && !victimizedByPlayer ? criminalPenalty * 0.2 : criminalPenalty;
  const baseTrust = (fam?.trustDelta ?? 0) - effectivePenalty;
  const trust = Math.round(Math.max(-1, Math.min(1, baseTrust)) * 1000) / 1000;

  let stance: AttitudeStance = 'serve';
  if (fam?.informedOnPlayer) {
    stance = wantedHere ? 'report_on_sight' : 'refuse';
  } else if (isActiveFence && !victimizedByPlayer) {
    stance = fam?.fenceRefusedTooHot || trust < -0.15 ? 'wary' : 'serve';
  } else if (
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

  const looseThreadFacts = looseThreadHooksForPerson(sim, personId);
  const combinedFacts =
    looseThreadFacts.length > 0 ? [...facts, ...looseThreadFacts] : facts;
  return {
    personId,
    name,
    stance,
    knowsPlayerName,
    ...(playerNameKnown !== undefined ? { playerNameKnown } : {}),
    trust,
    facts: combinedFacts,
    ...(looseThreadFacts.length > 0 ? { looseThreadFacts } : {}),
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
 * Evaluates a civilian spotting the player in the world. If `personId`'s attitude is
 * `report_on_sight` and they can make the player out, queues report(s) to the jurisdiction's
 * police using G005 era reporting latency (`reportLatencyForEra`), so an officer can be
 * dispatched once the report reaches the station.
 */
export function civilianSpotted(
  sim: LoopSim,
  personId: string,
  sighting: CivilianSightingInput = {},
): CivilianSpotResult {
  const att = attitude(sim, personId);
  const meta = sim.peopleMeta[personId];
  const sightingPlaceId = sighting.placeId ?? meta?.workplacePlaceId ?? meta?.homePlaceId;
  const jurId =
    sighting.jurisdictionId ??
    (sightingPlaceId ? sim.places[sightingPlaceId]?.jurisdictionId : undefined) ??
    (meta?.homePlaceId ? sim.places[meta.homePlaceId]?.jurisdictionId : undefined) ??
    Object.values(sim.places)[0]?.jurisdictionId ??
    'capital';

  if (sighting.hasLineOfSight === false) {
    return {
      sim,
      personId,
      name: att.name,
      stance: att.stance,
      reported: false,
      jurisdictionId: jurId,
      crimeIds: [],
      reportIds: [],
      delayHours: 0,
      reason: 'No line of sight to the player.',
      summary: `${att.name} did not have a clear line of sight.`,
    };
  }

  const dist = sighting.distance ?? 8;
  const light =
    sighting.light ??
    ambientLightAt(
      sim.currentHour,
      sightingPlaceId && sim.places[sightingPlaceId] ? sim.places[sightingPlaceId]!.position : { x: 0, y: 0 },
      sim.streetLamps,
    );

  if (dist > 55 || light < 0.05) {
    return {
      sim,
      personId,
      name: att.name,
      stance: att.stance,
      reported: false,
      jurisdictionId: jurId,
      crimeIds: [],
      reportIds: [],
      delayHours: 0,
      reason: dist > 55 ? 'Too far away to recognize the player.' : 'Too dark to recognize the player.',
      summary: `${att.name} could not make you out in time.`,
    };
  }

  if (att.stance !== 'report_on_sight') {
    return {
      sim,
      personId,
      name: att.name,
      stance: att.stance,
      reported: false,
      jurisdictionId: jurId,
      crimeIds: [],
      reportIds: [],
      delayHours: 0,
      reason: `Attitude is "${att.stance}", not "report_on_sight".`,
      summary: `${att.name} saw you (${att.stance}) and did not call the police.`,
    };
  }

  // Gather all unresolved crimes involving the player that this civilian knows about
  const knownUnresolved: Array<{ crime: CrimeEvent; account: CrimeAccount }> = [];
  const allCrimeIds = Object.keys(sim.crimeState.ledger).sort();
  for (let i = 0; i < allCrimeIds.length; i++) {
    const cid = allCrimeIds[i]!;
    if (sim.crimeMeta[cid]?.resolved) continue;
    const acc = sim.crimeState.ledger[cid]?.[personId];
    if (!acc) continue;
    const crime = sim.crimeState.crimes[cid];
    if (!crime || crime.culpritId !== sim.playerId) continue;
    knownUnresolved.push({ crime, account: acc });
  }

  if (knownUnresolved.length === 0) {
    return {
      sim,
      personId,
      name: att.name,
      stance: att.stance,
      reported: false,
      jurisdictionId: jurId,
      crimeIds: [],
      reportIds: [],
      delayHours: 0,
      reason: 'No unresolved crimes known to report.',
      summary: `${att.name} had no open incident to report.`,
    };
  }

  knownUnresolved.sort(
    (a, b) => b.crime.severity - a.crime.severity || (a.crime.id < b.crime.id ? -1 : 1),
  );

  const primary = knownUnresolved[0]!;
  const stationDistKm = stationDistanceKmForPlace(sim, sightingPlaceId ?? primary.crime.placeId);
  const latency = reportLatencyForEra(
    sim.year,
    primary.crime.severity,
    stationDistKm,
    sim.seed,
    primary.crime.id,
    personId,
  );
  const delayHours = latency.delayHours;
  const arrivalTotalHours =
    Math.round((sim.currentDay * 24 + sim.currentHour + delayHours) * 1000) / 1000;
  const arrivalDay = Math.floor(arrivalTotalHours / 24);
  const arrivalHour = Math.round((arrivalTotalHours - arrivalDay * 24) * 1000) / 1000;
  const means = reportMeansForEra(sim.year, delayHours);

  const appearance = sighting.appearance ?? sim.playerAppearance;
  const distFactor = clamp01(1 - Math.max(0, dist - 4) / 55);
  const lightFactor = clamp01(0.45 + 0.55 * light);

  const nextPendingReports = sim.crimeState.pendingReports.slice();
  const nextPendingMeta: Record<string, PendingReportMeta> = {
    ...(sim.pendingReportMeta ?? {}),
  };
  let nextSeq = sim.nextSeq;
  const reportedCrimeIds: string[] = [];
  const reportIds: string[] = [];

  for (let i = 0; i < knownUnresolved.length; i++) {
    const { crime, account } = knownUnresolved[i]!;
    // Avoid duplicating an identical still-in-transit sighting report from the same person & crime
    const alreadyQueued = nextPendingReports.some(
      (r) => r.crimeId === crime.id && r.reporterId === personId && r.jurisdictionId === jurId,
    );
    if (alreadyQueued) {
      reportedCrimeIds.push(crime.id);
      continue;
    }

    const reportId = `rep:civ:${personId}:${crime.id}:${sim.currentDay}:${nextSeq++}`;
    const sightConf = clamp01(Math.max(account.confidence, 0.55 + 0.4 * distFactor * lightFactor));
    const identifies =
      att.knowsPlayerName ||
      Boolean(account.identifiedCulpritId) ||
      (account.source === 'first_hand' && sightConf >= 0.72);

    const pending: PendingReport = {
      id: reportId,
      crimeId: crime.id,
      reporterId: personId,
      jurisdictionId: jurId,
      filedDay: sim.currentDay,
      arrivalDay,
      delayHours,
      source: 'first_hand',
      isVictim: account.isVictim || sim.crimeMeta[crime.id]?.ownerId === personId,
      confidence: Math.round(sightConf * 1000) / 1000,
      ...(identifies ? { identifiedCulpritId: sim.playerId } : {}),
      description: {
        height: appearance.height,
        build: appearance.build,
        hair: appearance.hair,
        clothingColor: appearance.clothingColor,
        ...(appearance.vehicle ? { vehicle: { ...appearance.vehicle } } : {}),
      },
    };
    nextPendingReports.push(pending);
    nextPendingMeta[reportId] = {
      arrivalTotalHours,
      ...(sightingPlaceId !== undefined ? { sightingPlaceId } : {}),
      kind: 'civilian_sighting',
    };
    reportedCrimeIds.push(crime.id);
    reportIds.push(reportId);
  }

  const nextSim: LoopSim = {
    ...sim,
    crimeState: {
      ...sim.crimeState,
      pendingReports: nextPendingReports,
    },
    pendingReportMeta: nextPendingMeta,
    nextSeq,
  };

  const meansLabel = formatReportMeansLabel(means);
  return {
    sim: nextSim,
    personId,
    name: att.name,
    stance: att.stance,
    reported: true,
    jurisdictionId: jurId,
    crimeIds: reportedCrimeIds,
    reportIds,
    delayHours,
    arrivalDay,
    arrivalHour,
    means,
    reason: `Spotted you and sent word ${meansLabel} (${delayHours}h delay).`,
    summary: `${att.name} spotted you and reported it ${meansLabel}; expected at the station in ${delayHours}h.`,
  };
}

/**
 * Files an informant's tip to the police (for example from a fence who flipped under `fenceRisk`).
 * Travels through the exact same era-delayed reporting pipeline (`reportLatencyForEra`) as a
 * civilian report and triggers police dispatch when it arrives.
 */
export function tipOff(sim: LoopSim, input: TipOffInput): TipOffResult {
  const informantId = input.informantId;
  const infoMeta = sim.peopleMeta[informantId];
  const infoPerson = sim.world.people[informantId];
  const informantName =
    infoMeta?.name ?? (infoPerson ? `${infoPerson.first} ${infoPerson.surname}` : informantId);
  const placeId = input.placeId ?? infoMeta?.workplacePlaceId ?? infoMeta?.homePlaceId;
  const jurId =
    input.jurisdictionId ??
    (placeId ? sim.places[placeId]?.jurisdictionId : undefined) ??
    (infoMeta?.homePlaceId ? sim.places[infoMeta.homePlaceId]?.jurisdictionId : undefined) ??
    Object.values(sim.places)[0]?.jurisdictionId ??
    'capital';

  let crimeState = sim.crimeState;
  const nextCrimeMeta = { ...sim.crimeMeta };
  const nextStolenItems = { ...sim.stolenItems };
  let nextSeq = sim.nextSeq;

  const stolenRec = input.itemId ? nextStolenItems[input.itemId] : undefined;
  let crimeId = input.crimeId ?? stolenRec?.crimeId;
  if (crimeId && (!crimeState.crimes[crimeId] || nextCrimeMeta[crimeId]?.resolved)) {
    crimeId = undefined;
  }

  // Fallback to the player's most recent unresolved crime in this jurisdiction
  if (!crimeId) {
    const unresolved = Object.values(nextCrimeMeta)
      .filter((m) => !m.resolved && m.jurisdictionId === jurId && Boolean(crimeState.crimes[m.crimeId]))
      .sort((a, b) => b.day - a.day || (a.crimeId < b.crimeId ? -1 : 1));
    crimeId = unresolved[0]?.crimeId;
  }

  // If no underlying crime exists yet (e.g. direct tip on hot goods), create a stolen-goods incident
  if (!crimeId) {
    crimeId = `crime:tip:${sim.currentDay}:${nextSeq++}`;
    const tipPlace = placeId ? sim.places[placeId] : Object.values(sim.places)[0];
    const tipPlaceId = tipPlace?.id ?? placeId ?? 'town';
    const tipPlaceName = tipPlace?.name ?? tipPlaceId;
    const tipEvent: CrimeEvent = {
      id: crimeId,
      kind: 'theft',
      culpritId: sim.playerId,
      placeId: tipPlaceId,
      townId: tipPlace?.townId ?? jurId,
      position: tipPlace ? { x: tipPlace.position.x, y: tipPlace.position.y } : { x: 0, y: 0 },
      when: { year: sim.year, day: sim.currentDay, hour: sim.currentHour },
      severity: 0.48,
      culpritAppearance: { ...sim.playerAppearance },
      ...(input.itemId ? { stolenItemIds: [input.itemId] } : {}),
    };
    crimeState = recordCrime(crimeState, tipEvent, []);
    nextCrimeMeta[crimeId] = {
      crimeId,
      kind: 'theft',
      placeId: tipPlaceId,
      placeName: tipPlaceName,
      jurisdictionId: jurId,
      day: sim.currentDay,
      dayName: dayNameFor(sim.startDay, sim.startDayOfWeek, sim.currentDay),
      itemLabel: stolenRec?.label ?? input.itemId ?? 'stolen goods',
      ...(stolenRec?.value !== undefined ? { itemValue: stolenRec.value } : {}),
      resolved: false,
    };
  }

  if (input.itemId && nextStolenItems[input.itemId]) {
    nextStolenItems[input.itemId] = {
      ...nextStolenItems[input.itemId]!,
      tracedToPlayer: true,
    };
  }

  const crime = crimeState.crimes[crimeId]!;
  const stationDistKm = stationDistanceKmForPlace(sim, placeId ?? crime.placeId);
  const latency = reportLatencyForEra(
    sim.year,
    Math.max(0.4, crime.severity),
    stationDistKm,
    sim.seed,
    crimeId,
    informantId,
  );
  const delayHours = latency.delayHours;
  const arrivalTotalHours =
    Math.round((sim.currentDay * 24 + sim.currentHour + delayHours) * 1000) / 1000;
  const arrivalDay = Math.floor(arrivalTotalHours / 24);
  const arrivalHour = Math.round((arrivalTotalHours - arrivalDay * 24) * 1000) / 1000;
  const means = reportMeansForEra(sim.year, delayHours);

  const reportId = `rep:tip:${informantId}:${crimeId}:${sim.currentDay}:${nextSeq++}`;
  const confidence = clamp01(input.confidence ?? 0.84);
  const identifiesPlayer = input.identifiesPlayer ?? true;

  const pending: PendingReport = {
    id: reportId,
    crimeId,
    reporterId: informantId,
    jurisdictionId: jurId,
    filedDay: sim.currentDay,
    arrivalDay,
    delayHours,
    source: 'first_hand',
    isVictim: false,
    confidence: Math.round(confidence * 1000) / 1000,
    ...(identifiesPlayer ? { identifiedCulpritId: sim.playerId } : {}),
    description: {
      height: sim.playerAppearance.height,
      build: sim.playerAppearance.build,
      hair: sim.playerAppearance.hair,
      clothingColor: sim.playerAppearance.clothingColor,
    },
  };

  const nextPendingReports = [...crimeState.pendingReports, pending];
  const nextPendingMeta: Record<string, PendingReportMeta> = {
    ...(sim.pendingReportMeta ?? {}),
    [reportId]: {
      arrivalTotalHours,
      ...(placeId !== undefined ? { sightingPlaceId: placeId } : {}),
      kind: 'informant_tip',
    },
  };

  const nextFamiliarity: Record<string, PlayerFamiliarity> = { ...sim.familiarity };
  const fam = cloneFamiliarity(nextFamiliarity[informantId]);
  fam.informedOnPlayer = true;
  fam.trustDelta = Math.max(-1, Math.round((fam.trustDelta - 0.55) * 1000) / 1000);
  const tipFact = 'I tipped the police off about the hot goods you brought me.';
  if (!fam.positiveFacts.includes(tipFact)) fam.positiveFacts.push(tipFact);
  nextFamiliarity[informantId] = fam;

  const interimSim: LoopSim = {
    ...sim,
    crimeState: {
      ...crimeState,
      pendingReports: nextPendingReports,
    },
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    familiarity: nextFamiliarity,
    pendingReportMeta: nextPendingMeta,
    nextSeq,
  };
  const newStance = attitude(interimSim, informantId).stance;
  const nextSim: LoopSim = {
    ...interimSim,
    lastStances: { ...sim.lastStances, [informantId]: newStance },
  };

  const reason = input.reason ?? `Informant ${informantName} tipped off police.`;
  const meansLabel = formatReportMeansLabel(means);
  return {
    sim: nextSim,
    informantId,
    informantName,
    jurisdictionId: jurId,
    queued: true,
    crimeId,
    reportId,
    delayHours,
    arrivalDay,
    arrivalHour,
    means,
    reason,
    summary: `${informantName} tipped off the police ${meansLabel}; expected at the station in ${delayHours}h.`,
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
      bookingFindings: [],
      policePlans: [],
      attitudeChanges: [],
      newPoliceCases: [],
      summary: 'Stopped and questioned, then released — the case is too thin to arrest.',
    };
  }

  const priorCount = sim.priorOffenses.length;
  const chargedIdSet = new Set(charges.map((c) => c.crimeId));
  const activeMatchingWarrants = (sim.warrants ?? []).filter(
    (w) => w.active && (chargedIdSet.has(w.crimeId) || w.jurisdictionId === jurId),
  );
  const priorEscape =
    activeMatchingWarrants.length > 0 ||
    (sim.warrants ?? []).some((w) => w.reason === 'missed_court_date');
  const routeToCourt = shouldRouteArrestToCourt({
    sim,
    targetCases,
    priorConvictions: priorCount,
    demandTrial: input.demandTrial,
    priorEscape,
  });

  if (routeToCourt) {
    const carriedSet = input.carriedItemIds ? new Set(input.carriedItemIds) : undefined;
    const confiscatedItems: string[] = [];
    const nextStolenItems = { ...sim.stolenItems };
    for (const [itemId, rec] of Object.entries(sim.stolenItems)) {
      const belongs =
        chargedIdSet.has(rec.crimeId) || (carriedSet !== undefined && carriedSet.has(itemId));
      if (belongs && !rec.pawnedAtPlaceId) {
        confiscatedItems.push(itemId);
        delete nextStolenItems[itemId];
      }
    }
    confiscatedItems.sort();

    const nextPoliceArrests = sim.policeState.arrests.slice();
    let policeSeq = sim.policeState.nextSeq;
    let nextSeq = sim.nextSeq + 1;
    const arrestingOfficerId =
      input.officerId ??
      Object.values(sim.policeState.officers).find((o) => o.jurisdictionId === jurId)?.id ??
      'officer_unknown';
    const timeSec = sim.currentDay * 86400 + sim.currentHour * 3600;
    const arrestingOfficer = sim.policeState.officers[arrestingOfficerId];
    const stationNodeId = arrestingOfficer?.stationNodeId ?? 'station';
    const stationPlace = Object.values(sim.places).find(
      (p) => p.jurisdictionId === jurId && p.kind === 'police_station',
    );
    for (let i = 0; i < charges.length; i++) {
      const ch = charges[i]!;
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
        arrestPosition: stationPlace
          ? { x: stationPlace.position.x, y: stationPlace.position.y }
          : { x: 0, y: 0 },
        method: input.surrendered ? 'surrender' : 'foot_pursuit',
        searchedInventory: (input.carriedItemIds ?? []).slice(),
        seizedContraband: confiscatedItems.slice(),
        cuffed: true,
        transportedToStation: true,
      });
    }

    const preBookingSim: LoopSim = {
      ...sim,
      stolenItems: nextStolenItems,
    };
    const bookingOutcome = bookSuspectAtStation({
      sim: preBookingSim,
      crimeState: sim.crimeState,
      jurisdictionId: jurId,
      suspectId: sim.playerId,
      day: sim.currentDay,
      seq: sim.nextSeq,
      confiscatedItems,
    });
    const nextCrimeState = bookingOutcome.crimeState;
    const coldCasesTriggered = bookingOutcome.coldCasesTriggered.slice();
    const bookingReport: BookingReport = {
      ...bookingOutcome.report,
      coldCaseHits: bookingOutcome.report.coldCaseHits.slice(),
      pawnLinks: bookingOutcome.report.pawnLinks.slice(),
      findings: bookingOutcome.report.findings.slice(),
    };

    const nextWarrants = sim.warrants
      ? sim.warrants.map((w) =>
          w.active && (chargedIdSet.has(w.crimeId) || w.jurisdictionId === jurId)
            ? { ...w, active: false }
            : w,
        )
      : undefined;
    const nextCourtCases: Record<string, LoopCourtCase> = { ...(sim.courtCases ?? {}) };
    for (const [id, existing] of Object.entries(nextCourtCases)) {
      if (
        existing.status === 'missed_date' &&
        existing.crimeIds.some((cid) => chargedIdSet.has(cid))
      ) {
        nextCourtCases[id] = { ...existing, custody: 'resolved' };
      }
    }

    const courtCase = createCourtCaseFromArrest({
      sim,
      crimeState: nextCrimeState,
      jurId,
      targetCases,
      playerCashCents: input.playerCash,
      priorEscape,
      seq: nextSeq++,
    });
    nextCourtCases[courtCase.id] = courtCase;

    const confiscatedSet = new Set(confiscatedItems);
    const nextCarriedAfterConfiscation = sim.carriedItems
      ? sim.carriedItems.filter((c) => !confiscatedSet.has(c.id))
      : undefined;
    let chargedBase: LoopSim = {
      ...sim,
      crimeState: nextCrimeState,
      policeState: {
        ...sim.policeState,
        arrests: nextPoliceArrests,
        nextSeq: policeSeq,
      },
      stolenItems: nextStolenItems,
      ...(nextCarriedAfterConfiscation !== undefined
        ? { carriedItems: nextCarriedAfterConfiscation }
        : {}),
      courtCases: nextCourtCases,
      ...(nextWarrants ? { warrants: nextWarrants } : {}),
      nextSeq,
    };
    if (chargedBase.timeline) {
      chargedBase = { ...chargedBase, timeline: syncSimToTimeline(chargedBase) };
    }
    const chargedSim = pruneResolvedState(chargedBase);

    const bailLine = courtCase.bail.granted
      ? `Bail set at ${eraFormatMoney(courtCase.bail.amountCents, sim.year)}`
      : 'Held without bail';
    const summary = `Charged with ${charges.length} offense(s); ${bailLine}; court date Day ${courtCase.courtDateDay} at ${courtCase.courtDateHour}:00.`;

    return {
      sim: chargedSim,
      jurisdictionId: jurId,
      penalty: 'charged',
      fineAmount: 0,
      jailHours: 0,
      jailNights: 0,
      day: chargedSim.currentDay,
      hour: chargedSim.currentHour,
      daysElapsed: 0,
      charges,
      priorOffenses: chargedSim.priorOffenses.length,
      totalCaseStrength,
      confiscatedItems,
      coldCasesTriggered,
      booking: bookingReport,
      bookingFindings: bookingReport.findings,
      courtCase,
      policePlans: [],
      attitudeChanges: [],
      newPoliceCases: [],
      summary,
    };
  }

  const maxSeverity = charges.reduce((m, c) => Math.max(m, c.severity), 0);
  const idx = eraPriceIndex(sim.year);
  const surrenderMult = input.surrendered ? 0.8 : 1;
  const minCashForFineCents = Math.max(10, Math.round(1500 * idx));

  let penalty: PriorOffenseRecord['penalty'] = 'warning';
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

  // Run station booking pipeline (prints/DNA into G008 identityFiles, cold cases, pawn ledger links)
  const preBookingSim: LoopSim = {
    ...sim,
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    familiarity: nextFamiliarity,
    priorOffenses: nextPriorOffenses,
  };
  const bookingOutcome = bookSuspectAtStation({
    sim: preBookingSim,
    crimeState: sim.crimeState,
    jurisdictionId: jurId,
    suspectId: sim.playerId,
    day: sim.currentDay,
    seq: sim.nextSeq,
    confiscatedItems,
  });
  const nextCrimeState = bookingOutcome.crimeState;
  const coldCasesTriggered = bookingOutcome.coldCasesTriggered.slice();
  const bookingReport: BookingReport = {
    ...bookingOutcome.report,
    coldCaseHits: bookingOutcome.report.coldCaseHits.slice(),
    pawnLinks: bookingOutcome.report.pawnLinks.slice(),
    findings: bookingOutcome.report.findings.slice(),
  };

  const pettyConfiscatedSet = new Set(confiscatedItems);
  const pettyCarried = sim.carriedItems
    ? sim.carriedItems.filter((c) => !pettyConfiscatedSet.has(c.id))
    : undefined;
  let bookedBase: LoopSim = {
    ...sim,
    crimeState: nextCrimeState,
    policeState: {
      ...sim.policeState,
      arrests: nextPoliceArrests,
      nextSeq: policeSeq,
    },
    crimeMeta: nextCrimeMeta,
    stolenItems: nextStolenItems,
    ...(pettyCarried !== undefined ? { carriedItems: pettyCarried } : {}),
    familiarity: nextFamiliarity,
    priorOffenses: nextPriorOffenses,
    nextSeq,
  };
  if (bookedBase.timeline) {
    bookedBase = { ...bookedBase, timeline: syncSimToTimeline(bookedBase) };
  }
  const bookedSim = pruneResolvedState(bookedBase);

  // Run any cell / jail time through advance() so gossip, reports, decay & police plans run normally
  let finalSim = bookedSim;
  let daysElapsed = 0;
  let policePlans: OfficerPlanDirective[] = [];
  let attitudeChanges: AttitudeChange[] = [];
  let newPoliceCases: string[] = [];

  if (jailHours > 0) {
    const preAdvMatchKeys = new Set<string>();
    for (const j of Object.values(bookedSim.crimeState.jurisdictions)) {
      for (const c of Object.values(j.cases)) {
        for (const m of c.evidenceMatches ?? []) {
          preAdvMatchKeys.add(`${c.crimeId}:${m.traceId}`);
        }
      }
    }
    const adv = advance(bookedSim, jailHours);
    finalSim = pruneResolvedState(adv.sim);
    daysElapsed = adv.daysElapsed;
    policePlans = adv.policePlans;
    attitudeChanges = adv.attitudeChanges;
    newPoliceCases = adv.newPoliceCases;

    // Merge any cold-case hits or pawn links discovered overnight while in custody
    for (const j of Object.values(finalSim.crimeState.jurisdictions)) {
      for (const c of Object.values(j.cases)) {
        if (finalSim.crimeMeta[c.crimeId]?.resolved) continue;
        for (const m of c.evidenceMatches ?? []) {
          const key = `${c.crimeId}:${m.traceId}`;
          if (!preAdvMatchKeys.has(key) && m.suspectId === sim.playerId) {
            const placeName =
              finalSim.crimeMeta[c.crimeId]?.placeName ??
              finalSim.places[c.placeId]?.name ??
              c.placeId;
            const crimeYear =
              finalSim.crimeMeta[c.crimeId]?.year ??
              finalSim.crimeState.crimes[c.crimeId]?.when.year ??
              finalSim.timeline?.crimes[c.crimeId]?.year;
            const yearsToMatch =
              crimeYear !== undefined ? Math.max(0, finalSim.year - crimeYear) : undefined;
            const fact = formatBookingColdCaseFact(c.kind, placeName, m.method);
            bookingReport.coldCaseHits.push({
              crimeId: c.crimeId,
              kind: c.kind,
              placeName,
              method: m.method,
              confidence: Math.round(m.confidence * 1000) / 1000,
              ...(crimeYear !== undefined ? { crimeYear, yearsToMatch } : {}),
              fact,
            });
            bookingReport.findings.push(fact);
            if (!coldCasesTriggered.includes(c.crimeId)) {
              coldCasesTriggered.push(c.crimeId);
            }
          }
        }
      }
    }
    for (let i = 0; i < adv.pawnLeadsMatched.length; i++) {
      const link = adv.pawnLeadsMatched[i]!;
      if (link.suspectId !== sim.playerId) continue;
      if (bookingReport.pawnLinks.some((p) => p.crimeId === link.crimeId && p.pawnShopId === link.pawnShopId)) {
        continue;
      }
      bookingReport.pawnLinks.push(link);
      const shopName = finalSim.places[link.pawnShopId]?.name ?? link.pawnShopId;
      const itemDesc = finalSim.crimeMeta[link.crimeId]?.itemLabel ?? 'stolen property';
      const serialNote = link.serialNumber ? ` (serial ${link.serialNumber})` : '';
      bookingReport.findings.push(`Pawn ledger at ${shopName} linked ${itemDesc}${serialNote} to you.`);
    }
    coldCasesTriggered.sort();
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
    booking: bookingReport,
    bookingFindings: bookingReport.findings,
    policePlans,
    attitudeChanges,
    newPoliceCases,
    summary,
  };
}

export function bailDecision(sim: LoopSim, input: BailDecisionInput): BailDecisionResult {
  return applyBailDecision(sim, input);
}

export function chooseCounsel(sim: LoopSim, input: ChooseCounselInput): ChooseCounselResult {
  return applyChooseCounsel(sim, input);
}

export function plead(sim: LoopSim, input: PleaInput): PleaResult {
  const cc = findTargetCourtCase(sim, input.courtCaseId, ['pending_trial']);
  if (!cc) {
    return {
      sim,
      ok: false,
      plea: input.plea,
      fineAmount: 0,
      jailDays: 0,
      jailHours: 0,
      day: sim.currentDay,
      hour: sim.currentHour,
      daysElapsed: 0,
      policePlans: [],
      attitudeChanges: [],
      newPoliceCases: [],
      facts: [],
      summary: 'No pending court case to enter a plea on.',
    };
  }

  if (input.plea === 'not_guilty') {
    const fact = `Entered a NOT GUILTY plea; trial remains set for Day ${cc.courtDateDay} at ${cc.courtDateHour}:00.`;
    const updated: LoopCourtCase = {
      ...cc,
      facts: [...cc.facts, fact],
    };
    const nextSim: LoopSim = {
      ...sim,
      courtCases: { ...(sim.courtCases ?? {}), [cc.id]: updated },
    };
    return {
      sim: nextSim,
      ok: true,
      courtCase: updated,
      plea: 'not_guilty',
      fineAmount: 0,
      jailDays: 0,
      jailHours: 0,
      day: nextSim.currentDay,
      hour: nextSim.currentHour,
      daysElapsed: 0,
      policePlans: [],
      attitudeChanges: [],
      newPoliceCases: [],
      facts: [fact],
      summary: fact,
    };
  }

  const jailDays = cc.pleaOffer.recommendedJailDays;
  const jailHours = jailDays * 24;
  const fineAmount = cc.pleaOffer.recommendedFineCents;
  const sentence: LoopCourtSentence = {
    jailDays,
    jailHours,
    fineAmount,
    probationDays: jailDays < 60 ? 90 : 0,
    reputationDelta: -0.2,
    judgeId: 'court:plea',
    judgeName: 'Presiding Judge',
    reasoning: cc.pleaOffer.reasoning,
  };
  const fact = `Pleaded GUILTY under plea deal: ${jailDays} day(s) in custody and ${eraFormatMoney(fineAmount, sim.year)} fine (${Math.round(cc.pleaOffer.discountRatio * 100)}% plea discount).`;
  const updated: LoopCourtCase = {
    ...cc,
    status: 'plea_guilty',
    custody: 'resolved',
    sentence,
    facts: [...cc.facts, fact],
  };

  let nextSim = finalizeCourtDisposition({
    sim,
    updatedCase: updated,
    convicted: true,
    sentence,
    day: sim.currentDay,
  });

  let daysElapsed = 0;
  let policePlans: OfficerPlanDirective[] = [];
  let attitudeChanges: AttitudeChange[] = [];
  let newPoliceCases: string[] = [];

  if (input.serveSentence !== false && jailHours > 0) {
    const adv = advance(nextSim, jailHours);
    nextSim = pruneResolvedState(adv.sim);
    daysElapsed = adv.daysElapsed;
    policePlans = adv.policePlans;
    attitudeChanges = adv.attitudeChanges;
    newPoliceCases = adv.newPoliceCases;
  }

  return {
    sim: nextSim,
    ok: true,
    courtCase: nextSim.courtCases?.[cc.id] ?? updated,
    plea: 'guilty',
    fineAmount,
    jailDays,
    jailHours,
    day: nextSim.currentDay,
    hour: nextSim.currentHour,
    daysElapsed,
    policePlans,
    attitudeChanges,
    newPoliceCases,
    facts: [fact],
    summary: fact,
  };
}

export function courtInterfere(
  sim: LoopSim,
  input: CourtInterferenceInput,
): CourtInterferenceResult {
  return applyCourtInterference(sim, input);
}

export function courtView(sim: LoopSim): CourtViewResult {
  return buildCourtView(sim);
}

/**
 * Steps into the capital time machine and jumps to `input.toYear` via G017/G018 `presentAt`.
 * Spends 1 nuclear fuel unit (starting budget: 4 trips), preserves carried items and cross-era
 * forensic rules, resets attitudes except where the timeline carries memory, and returns the
 * target-year town, Gazette archive, police state, cross-era matches, and plain-English loose threads.
 */
export function jump(sim: LoopSim, input: JumpInput): JumpResult {
  return performTimeJump(sim, input, {
    createLoopPolice,
    stepArrivalPolice: (arrivedSim) => {
      const tick = stepLoopPolice({
        policeState: arrivedSim.policeState,
        activeCrimeState: activeCrimeStateForPolice(arrivedSim),
        fullCrimeState: arrivedSim.crimeState,
        world: arrivedSim.world,
        places: arrivedSim.places,
        streetGraph: arrivedSim.streetGraph,
        day: arrivedSim.currentDay,
        hour: arrivedSim.currentHour,
        year: arrivedSim.year,
        idMap: cloneIdMap(arrivedSim.idMap),
      });
      return {
        sim: {
          ...arrivedSim,
          policeState: tick.policeState,
          officers: tick.officers,
          respondedCases: { ...tick.policeState.respondedCases },
        },
        policePlans: tick.directives,
      };
    },
  });
}

export function timeMachineView(sim: LoopSim): TimeMachineViewResult {
  return buildTimeMachineView(sim);
}

export { DEFAULT_TIME_MACHINE_FUEL };

/**
 * Prunes state that resolved (or completely forgotten) crimes no longer need:
 * - `crimeState.toldPairs` for resolved crimes and forgotten crimes (zero remaining knowers)
 * - `crimeState.traces`, `crimeState.pendingReports`, and `crimeState.jurisdictions[*].cases` for resolved crimes
 * - Compacts resolved `crimeState.ledger` + `crimeState.crimes` into lightweight `resolvedCrimeMemories`
 *   so `attitude()` retains identical facts & penalties without keeping full gossip/crime graphs
 * - `policeState.respondedCases`, `sim.respondedCases`, `pawnRecords`, disposed `stolenItems`,
 *   stale `pendingReportMeta`, `latestSightingPlaceByCrime`, and expired `backupAlerts`.
 *
 * Preserves `identityFiles`, `priorOffenses`, `policeState.arrests`, and all active unresolved crimes
 * so `load(save(sim))` continues identically. Returns `sim` untouched when nothing is prunable.
 */
export function pruneResolvedState(sim: LoopSim): LoopSim {
  const resolvedIds = new Set<string>();
  for (const [cid, meta] of Object.entries(sim.crimeMeta)) {
    if (meta.resolved) resolvedIds.add(cid);
  }

  // Also identify unresolved crimes where every knower has forgotten and non-empty toldPairs remain
  const forgottenToldIds = new Set<string>();
  for (const [cid, byTeller] of Object.entries(sim.crimeState.toldPairs)) {
    if (resolvedIds.has(cid)) continue;
    if (Object.keys(byTeller).length === 0) continue;
    const led = sim.crimeState.ledger[cid];
    if (!led || Object.keys(led).length === 0) {
      forgottenToldIds.add(cid);
    }
  }

  const nowSec = sim.currentDay * 86400 + sim.currentHour * 3600;
  const activePendingIds = new Set<string>();
  let pendingChanged = false;
  for (let i = 0; i < sim.crimeState.pendingReports.length; i++) {
    const r = sim.crimeState.pendingReports[i]!;
    if (resolvedIds.has(r.crimeId)) {
      pendingChanged = true;
    } else {
      activePendingIds.add(r.id);
    }
  }

  let hasExpiredBackup = false;
  for (let i = 0; i < sim.policeState.backupAlerts.length; i++) {
    if (sim.policeState.backupAlerts[i]!.expiresAtSec <= nowSec) {
      hasExpiredBackup = true;
      break;
    }
  }

  let hasStalePendingMeta = false;
  if (sim.pendingReportMeta) {
    for (const rid of Object.keys(sim.pendingReportMeta)) {
      if (!activePendingIds.has(rid)) {
        hasStalePendingMeta = true;
        break;
      }
    }
  }

  let hasResolvedSightings = false;
  if (sim.latestSightingPlaceByCrime) {
    for (const cid of Object.keys(sim.latestSightingPlaceByCrime)) {
      if (resolvedIds.has(cid)) {
        hasResolvedSightings = true;
        break;
      }
    }
  }

  const prunedCourts = pruneResolvedCourtCases(sim.courtCases);

  // Quick check: if there are no resolved crimes and nothing else to prune, return sim untouched
  if (
    resolvedIds.size === 0 &&
    forgottenToldIds.size === 0 &&
    !pendingChanged &&
    !hasExpiredBackup &&
    !hasStalePendingMeta &&
    !hasResolvedSightings &&
    !prunedCourts.changed
  ) {
    return sim;
  }

  let changed = false;
  const nextCrimes: Record<string, CrimeEvent> = {};
  for (const [cid, c] of Object.entries(sim.crimeState.crimes)) {
    if (resolvedIds.has(cid)) {
      changed = true;
    } else {
      nextCrimes[cid] = c;
    }
  }

  const nextResolvedMemories: Record<string, Record<string, ResolvedCrimeMemory>> = {
    ...(sim.resolvedCrimeMemories ?? {}),
  };
  let memoriesChanged = false;
  const nextLedger: Record<string, Record<Id, CrimeAccount>> = {};
  for (const [cid, byPerson] of Object.entries(sim.crimeState.ledger)) {
    if (resolvedIds.has(cid)) {
      changed = true;
      const crime = sim.crimeState.crimes[cid];
      if (!crime || crime.culpritId === sim.playerId) {
        const compact: Record<string, ResolvedCrimeMemory> = {
          ...(nextResolvedMemories[cid] ?? {}),
        };
        let hasAny = Object.keys(compact).length > 0;
        for (const [pid, acc] of Object.entries(byPerson)) {
          compact[pid] = {
            s: acc.source === 'first_hand' ? 'f' : 'h',
            ...(acc.heardFrom !== undefined ? { h: acc.heardFrom } : {}),
            ...(acc.isVictim ? { v: 1 as const } : {}),
          };
          hasAny = true;
        }
        if (hasAny) {
          nextResolvedMemories[cid] = compact;
          memoriesChanged = true;
        }
      }
    } else {
      nextLedger[cid] = byPerson;
    }
  }

  const nextToldPairs: Record<string, Record<Id, Id[]>> = {};
  for (const [cid, byTeller] of Object.entries(sim.crimeState.toldPairs)) {
    if (resolvedIds.has(cid) || forgottenToldIds.has(cid)) {
      changed = true;
    } else {
      nextToldPairs[cid] = byTeller;
    }
  }

  let nextTraces = sim.crimeState.traces;
  if (sim.crimeState.traces) {
    let tracesChanged = false;
    const filteredTraces: Record<string, NonNullable<CrimeState['traces']>[string]> = {};
    for (const [cid, list] of Object.entries(sim.crimeState.traces)) {
      if (resolvedIds.has(cid)) {
        tracesChanged = true;
      } else {
        filteredTraces[cid] = list;
      }
    }
    if (tracesChanged) {
      nextTraces = filteredTraces;
      changed = true;
    }
  }

  const nextPendingReports = pendingChanged
    ? sim.crimeState.pendingReports.filter((r) => !resolvedIds.has(r.crimeId))
    : sim.crimeState.pendingReports;
  if (pendingChanged) changed = true;

  let jursChanged = false;
  const nextJurisdictions: CrimeState['jurisdictions'] = {};
  for (const [jurId, jur] of Object.entries(sim.crimeState.jurisdictions)) {
    let casesChanged = false;
    const keptCases: Record<string, PoliceCase> = {};
    for (const [cid, c] of Object.entries(jur.cases)) {
      if (resolvedIds.has(cid)) {
        casesChanged = true;
      } else {
        keptCases[cid] = c;
      }
    }
    if (casesChanged) {
      jursChanged = true;
      nextJurisdictions[jurId] = { jurisdictionId: jurId, cases: keptCases };
    } else {
      nextJurisdictions[jurId] = jur;
    }
  }
  if (jursChanged) changed = true;

  const filterResponded = (rec: Record<string, string>): { next: Record<string, string>; pruned: boolean } => {
    let pruned = false;
    const out: Record<string, string> = {};
    for (const [key, val] of Object.entries(rec)) {
      const colon = key.indexOf(':');
      const cid = colon >= 0 ? key.slice(colon + 1) : key;
      if (resolvedIds.has(cid)) {
        pruned = true;
      } else {
        out[key] = val;
      }
    }
    return { next: pruned ? out : rec, pruned };
  };

  const policeResp = filterResponded(sim.policeState.respondedCases);
  const simResp = filterResponded(sim.respondedCases);
  if (policeResp.pruned || simResp.pruned) changed = true;

  const nextBackupAlerts = hasExpiredBackup
    ? sim.policeState.backupAlerts.filter((a) => a.expiresAtSec > nowSec)
    : sim.policeState.backupAlerts;
  if (hasExpiredBackup) changed = true;

  const nextPawnRecords = sim.pawnRecords.filter(
    (r) => !r.stolenFromCrimeId || !resolvedIds.has(r.stolenFromCrimeId),
  );
  if (nextPawnRecords.length !== sim.pawnRecords.length) changed = true;

  let stolenChanged = false;
  const nextStolenItems: Record<string, StolenItemRecord> = {};
  for (const [itemId, rec] of Object.entries(sim.stolenItems)) {
    if (resolvedIds.has(rec.crimeId)) {
      stolenChanged = true;
    } else {
      nextStolenItems[itemId] = rec;
    }
  }
  if (stolenChanged) changed = true;

  let nextPendingMeta = sim.pendingReportMeta;
  if (hasStalePendingMeta && sim.pendingReportMeta) {
    const kept: Record<string, PendingReportMeta> = {};
    for (const [rid, m] of Object.entries(sim.pendingReportMeta)) {
      if (activePendingIds.has(rid)) kept[rid] = m;
    }
    nextPendingMeta = Object.keys(kept).length > 0 ? kept : undefined;
    changed = true;
  }

  let nextSightings = sim.latestSightingPlaceByCrime;
  if (hasResolvedSightings && sim.latestSightingPlaceByCrime) {
    const kept: Record<string, string> = {};
    for (const [cid, pid] of Object.entries(sim.latestSightingPlaceByCrime)) {
      if (!resolvedIds.has(cid)) kept[cid] = pid;
    }
    nextSightings = Object.keys(kept).length > 0 ? kept : undefined;
    changed = true;
  }

  if (!changed && !memoriesChanged && !prunedCourts.changed) return sim;

  const nextCrimeState: CrimeState = {
    ...sim.crimeState,
    crimes: nextCrimes,
    ledger: nextLedger,
    pendingReports: nextPendingReports,
    jurisdictions: nextJurisdictions,
    toldPairs: nextToldPairs,
    ...(nextTraces !== undefined ? { traces: nextTraces } : {}),
  };

  const nextPoliceState =
    policeResp.pruned || hasExpiredBackup
      ? {
          ...sim.policeState,
          respondedCases: policeResp.next,
          backupAlerts: nextBackupAlerts,
        }
      : sim.policeState;

  const out: LoopSim = {
    ...sim,
    crimeState: nextCrimeState,
    policeState: nextPoliceState,
    respondedCases: simResp.next,
    pawnRecords: nextPawnRecords,
    stolenItems: stolenChanged ? nextStolenItems : sim.stolenItems,
    ...(Object.keys(nextResolvedMemories).length > 0
      ? { resolvedCrimeMemories: nextResolvedMemories }
      : {}),
    ...(prunedCourts.next !== undefined ? { courtCases: prunedCourts.next } : {}),
  };
  if (nextPendingMeta !== undefined) {
    out.pendingReportMeta = nextPendingMeta;
  } else {
    delete out.pendingReportMeta;
  }
  if (nextSightings !== undefined) {
    out.latestSightingPlaceByCrime = nextSightings;
  } else {
    delete out.latestSightingPlaceByCrime;
  }
  return out;
}

/** Serializes the simulation state to a compact plain JSON string (pruning resolved-crime bloat first). */
export function save(sim: LoopSim): string {
  return JSON.stringify(pruneResolvedState(sim));
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
