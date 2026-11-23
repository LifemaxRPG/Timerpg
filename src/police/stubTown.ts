import type { CulpritAppearance } from '../crime/types';
import { DEFAULT_TRAITS } from '../plan/stubWorld';
import type {
  ActionOutcome,
  ActorTraits,
  FailureCode,
  Manner,
  PlanWorld,
  ResolvedTargetRef,
  Vec2,
} from '../plan/types';
import { rand } from '../sim/rng';
import type { Id, Person, World } from '../sim/types';
import type { ObservedPerson, Officer, StreetGraph, StreetNode } from './types';

export interface StubTownActor {
  id: string;
  position: Vec2;
  nodeId: string;
  jurisdictionId: string;
  appearance?: CulpritAppearance;
  inventory: string[];
  hidden: boolean;
  role?: string;
  fleeing?: boolean;
  inCustody?: boolean;
  traits: ActorTraits;
  money: number;
}

export interface StubTownVehicle {
  id: string;
  position: Vec2;
  nodeId: string;
  driverId?: string;
}

export interface StubTownLogEntry {
  timeSec: number;
  actorId: string;
  action: string;
  target?: string;
  detail?: string;
}

/**
 * Builds a clean two-jurisdiction stub town street graph (`town_a` connected across `border_road`
 * to `town_b`) with stations, main streets, alleys, buildings, and suspect haunts.
 */
export function createStubTownGraph(): StreetGraph {
  const nodes: Record<string, StreetNode> = {
    // Jurisdiction A (main town)
    station_a: { id: 'station_a', position: { x: 0, y: 0 }, kind: 'station', jurisdictionId: 'town_a', name: 'Town A Police Station', streetLight: 0.9 },
    main_west: { id: 'main_west', position: { x: 40, y: 0 }, kind: 'intersection', jurisdictionId: 'town_a', name: 'Main & West', streetLight: 0.75 },
    bank_a: { id: 'bank_a', position: { x: 80, y: 0 }, kind: 'building', jurisdictionId: 'town_a', name: 'First National Bank', streetLight: 0.8 },
    market_sq: { id: 'market_sq', position: { x: 80, y: 45 }, kind: 'intersection', jurisdictionId: 'town_a', name: 'Market Square', streetLight: 0.7 },
    tavern_a: { id: 'tavern_a', position: { x: 40, y: 45 }, kind: 'building', jurisdictionId: 'town_a', name: 'Copper Kettle Tavern', streetLight: 0.55 },
    back_alley: { id: 'back_alley', position: { x: 115, y: 25 }, kind: 'street', jurisdictionId: 'town_a', name: 'Back Alley', streetLight: 0.08 },
    oak_homes: { id: 'oak_homes', position: { x: 40, y: 90 }, kind: 'street', jurisdictionId: 'town_a', name: 'Oak Street Homes', streetLight: 0.35 },
    east_gate: { id: 'east_gate', position: { x: 145, y: 0 }, kind: 'border', jurisdictionId: 'town_a', name: 'East County Line', streetLight: 0.3, leadsToJurisdictionId: 'town_b' },

    // Jurisdiction B (neighboring town across county line)
    west_entry_b: { id: 'west_entry_b', position: { x: 185, y: 0 }, kind: 'border', jurisdictionId: 'town_b', name: 'Town B Entry', streetLight: 0.4, leadsToJurisdictionId: 'town_a' },
    main_b: { id: 'main_b', position: { x: 225, y: 0 }, kind: 'intersection', jurisdictionId: 'town_b', name: 'Town B Main', streetLight: 0.75 },
    station_b: { id: 'station_b', position: { x: 265, y: 0 }, kind: 'station', jurisdictionId: 'town_b', name: 'Town B Police Station', streetLight: 0.9 },
  };

  const edges = [
    { from: 'station_a', to: 'main_west' },
    { from: 'main_west', to: 'bank_a' },
    { from: 'main_west', to: 'tavern_a' },
    { from: 'bank_a', to: 'market_sq' },
    { from: 'bank_a', to: 'back_alley' },
    { from: 'bank_a', to: 'east_gate' },
    { from: 'market_sq', to: 'tavern_a' },
    { from: 'market_sq', to: 'back_alley' },
    { from: 'tavern_a', to: 'oak_homes' },
    { from: 'east_gate', to: 'west_entry_b' },
    { from: 'west_entry_b', to: 'main_b' },
    { from: 'main_b', to: 'station_b' },
  ];

  return { nodes, edges };
}

/**
 * Pure, deterministic `PlanWorld` backed by a `StreetGraph` for running police plans,
 * officers, patrol cars, witnesses, suspects, lookalikes, and up to 2,000 citizens.
 */
export class StubTownPlanWorld implements PlanWorld {
  timeSec = 0;
  readonly graph: StreetGraph;
  private readonly nodeList: StreetNode[];
  readonly actors: Record<string, StubTownActor> = {};
  readonly vehicles: Record<string, StubTownVehicle> = {};
  readonly signals: Array<{ timeSec: number; actorId: string; signal: string }> = [];
  readonly log: StubTownLogEntry[] = [];
  sightRangeM = 36;

  constructor(graph: StreetGraph, startSec = 10 * 3600) {
    this.graph = graph;
    this.nodeList = Object.values(graph.nodes);
    this.timeSec = startSec;
  }

  addActor(actor: StubTownActor): void {
    this.actors[actor.id] = {
      ...actor,
      position: { ...actor.position },
      inventory: [...actor.inventory],
      traits: { ...actor.traits },
    };
  }

  addVehicle(vehicle: StubTownVehicle): void {
    this.vehicles[vehicle.id] = {
      ...vehicle,
      position: { ...vehicle.position },
    };
  }

  /** Spawns all officers (and their cuffs & patrol cars) from a `PoliceState` into this `PlanWorld`. */
  spawnOfficers(officers: Record<string, Officer>): void {
    const ids = Object.keys(officers).sort();
    for (let i = 0; i < ids.length; i++) {
      const o = officers[ids[i]!]!;
      const startNodeId = o.beatNodeIds[0] ?? o.stationNodeId;
      const startNode = this.graph.nodes[startNodeId] ?? this.graph.nodes[o.stationNodeId]!;
      this.addActor({
        id: o.id,
        position: { x: startNode.position.x, y: startNode.position.y },
        nodeId: startNode.id,
        jurisdictionId: o.jurisdictionId,
        inventory: [o.cuffsItemId],
        hidden: false,
        traits: { ...DEFAULT_TRAITS, gentle: 0.35, lawful: 0.95, brave: 0.80 },
        money: 50,
      });
      if (o.vehicleId) {
        this.addVehicle({
          id: o.vehicleId,
          position: { x: startNode.position.x, y: startNode.position.y },
          nodeId: startNode.id,
        });
      }
    }
  }

  /** Moves a non-officer actor (e.g. a walking or fleeing suspect) toward a street node or point. */
  movePersonToward(personId: string, dest: Vec2, speedMps: number, dt: number): boolean {
    const actor = this.actors[personId];
    if (!actor || actor.inCustody) return false;
    const dx = dest.x - actor.position.x;
    const dy = dest.y - actor.position.y;
    const d = Math.hypot(dx, dy);
    const stepLen = speedMps * dt;
    if (d <= stepLen || d < 0.001) {
      actor.position = { x: dest.x, y: dest.y };
      this.syncActorNode(actor);
      return true;
    }
    actor.position = {
      x: actor.position.x + (dx / d) * stepLen,
      y: actor.position.y + (dy / d) * stepLen,
    };
    this.syncActorNode(actor);
    return false;
  }

  /** Moves a cuffed suspect alongside their arresting officer during transport. */
  syncCustodyPosition(suspectId: Id, officerId: string, phase: 'cuffed' | 'transporting' | 'booked'): void {
    const s = this.actors[suspectId];
    const o = this.actors[officerId];
    if (!s || !o) return;
    s.inCustody = true;
    s.fleeing = false;
    s.position = { x: o.position.x, y: o.position.y };
    s.nodeId = o.nodeId;
    s.jurisdictionId = o.jurisdictionId;
    if (phase === 'booked') {
      this.log.push({ timeSec: this.timeSec, actorId: officerId, action: 'booked', target: suspectId });
    }
  }

  private syncActorNode(actor: StubTownActor): void {
    let bestId = actor.nodeId;
    let bestD2 = Infinity;
    for (let i = 0; i < this.nodeList.length; i++) {
      const n = this.nodeList[i]!;
      const id = n.id;
      const dx = n.position.x - actor.position.x;
      const dy = n.position.y - actor.position.y;
      const d2 = dx * dx + dy * dy;
      if (d2 < bestD2) {
        bestD2 = d2;
        bestId = id;
      }
    }
    actor.nodeId = bestId;
    actor.jurisdictionId = this.graph.nodes[bestId]?.jurisdictionId ?? actor.jurisdictionId;
  }

  /** Builds `ObservedPerson[]` snapshot for all citizens with appearances. */
  getObservedPeople(): ObservedPerson[] {
    const out: ObservedPerson[] = [];
    const ids = Object.keys(this.actors).sort();
    for (let i = 0; i < ids.length; i++) {
      const a = this.actors[ids[i]!]!;
      if (!a.appearance) continue;
      out.push({
        personId: a.id,
        position: { x: a.position.x, y: a.position.y },
        nodeId: a.nodeId,
        jurisdictionId: a.jurisdictionId,
        appearance: a.appearance,
        fleeing: a.fleeing,
        inCustody: a.inCustody,
      });
    }
    return out;
  }

  // ---- PlanWorld interface implementation ----

  getActorPosition(actorId: string): Vec2 {
    return this.actors[actorId]?.position ?? { x: 0, y: 0 };
  }

  getActorPlace(actorId: string): string {
    return this.actors[actorId]?.nodeId ?? '';
  }

  getEntityPosition(ref: ResolvedTargetRef): Vec2 | undefined {
    switch (ref.kind) {
      case 'person':
        return this.actors[ref.id]?.position;
      case 'player':
        return this.actors['player']?.position;
      case 'place':
        return this.graph.nodes[ref.id]?.position;
      case 'vehicle':
        return this.vehicles[ref.id]?.position;
      case 'here':
        return undefined;
      case 'item':
        return undefined;
    }
  }

  getEntityPlace(ref: ResolvedTargetRef): string | undefined {
    switch (ref.kind) {
      case 'person':
        return this.actors[ref.id]?.nodeId;
      case 'player':
        return this.actors['player']?.nodeId;
      case 'place':
        return this.graph.nodes[ref.id]?.id;
      case 'vehicle':
        return this.vehicles[ref.id]?.nodeId;
      default:
        return undefined;
    }
  }

  canSee(observerId: string, target: ResolvedTargetRef): boolean {
    const obs = this.actors[observerId];
    if (!obs) return false;
    const pos = this.getEntityPosition(target);
    if (!pos) return false;
    if (target.kind === 'person' && this.actors[target.id]?.hidden) return false;
    const dx = pos.x - obs.position.x;
    const dy = pos.y - obs.position.y;
    return dx * dx + dy * dy <= this.sightRangeM * this.sightRangeM;
  }

  isAlone(actorId: string): boolean {
    const a = this.actors[actorId];
    if (!a) return true;
    for (const id of Object.keys(this.actors)) {
      if (id === actorId) continue;
      const other = this.actors[id]!;
      const dx = other.position.x - a.position.x;
      const dy = other.position.y - a.position.y;
      if (dx * dx + dy * dy <= 16 * 16) return false;
    }
    return true;
  }

  isLocked(_placeId: string): boolean {
    return false;
  }

  isOpen(_placeId: string): boolean {
    return true;
  }

  requiredKeyFor(_placeId: string): string | undefined {
    return undefined;
  }

  hasItem(actorId: string, itemId: string): boolean {
    return this.actors[actorId]?.inventory.includes(itemId) ?? false;
  }

  getItemOwner(_itemId: string): string | undefined {
    return undefined;
  }

  getItemPrice(_itemId: string): number | undefined {
    return undefined;
  }

  getActorMoney(actorId: string): number {
    return this.actors[actorId]?.money ?? 0;
  }

  hasHeardSignal(_actorId: string, signal: string, sinceSec: number): boolean {
    for (let i = this.signals.length - 1; i >= 0; i--) {
      const s = this.signals[i]!;
      if (s.timeSec < sinceSec) break;
      if (s.signal === signal) return true;
    }
    return false;
  }

  findNearest(_actorId: string, _kind: string): ResolvedTargetRef | undefined {
    return undefined;
  }

  getActorTraits(actorId: string): ActorTraits {
    return this.actors[actorId]?.traits ?? DEFAULT_TRAITS;
  }

  moveActorToward(
    actorId: string,
    dest: Vec2,
    speedMps: number,
    dt: number,
    destPlaceId?: string,
  ): { ok: true; arrived: boolean } | { ok: false; code: FailureCode; message: string } {
    const actor = this.actors[actorId];
    if (!actor) return { ok: false, code: 'target_missing', message: `missing actor ${actorId}` };
    const dx = dest.x - actor.position.x;
    const dy = dest.y - actor.position.y;
    const d = Math.hypot(dx, dy);
    const maxMove = speedMps * dt;
    if (d <= maxMove || d < 0.05) {
      actor.position = { x: dest.x, y: dest.y };
      if (destPlaceId && this.graph.nodes[destPlaceId]) {
        actor.nodeId = destPlaceId;
        actor.jurisdictionId = this.graph.nodes[destPlaceId]!.jurisdictionId;
      } else {
        this.syncActorNode(actor);
      }
      return { ok: true, arrived: true };
    }
    actor.position = {
      x: actor.position.x + (dx / d) * maxMove,
      y: actor.position.y + (dy / d) * maxMove,
    };
    this.syncActorNode(actor);
    return { ok: true, arrived: false };
  }

  moveVehicleToward(
    vehicleId: string,
    driverId: string,
    dest: Vec2,
    speedMps: number,
    dt: number,
    destPlaceId?: string,
  ): { ok: true; arrived: boolean } | { ok: false; code: FailureCode; message: string } {
    const veh = this.vehicles[vehicleId];
    const driver = this.actors[driverId];
    if (!veh || !driver) {
      return { ok: false, code: 'target_missing', message: `missing vehicle ${vehicleId}` };
    }
    const dx = dest.x - veh.position.x;
    const dy = dest.y - veh.position.y;
    const d = Math.hypot(dx, dy);
    const maxMove = speedMps * dt;
    if (d <= maxMove || d < 0.05) {
      veh.position = { x: dest.x, y: dest.y };
      driver.position = { x: dest.x, y: dest.y };
      if (destPlaceId && this.graph.nodes[destPlaceId]) {
        veh.nodeId = destPlaceId;
        driver.nodeId = destPlaceId;
        driver.jurisdictionId = this.graph.nodes[destPlaceId]!.jurisdictionId;
      } else {
        this.syncActorNode(driver);
        veh.nodeId = driver.nodeId;
      }
      return { ok: true, arrived: true };
    }
    const nx = veh.position.x + (dx / d) * maxMove;
    const ny = veh.position.y + (dy / d) * maxMove;
    veh.position = { x: nx, y: ny };
    driver.position = { x: nx, y: ny };
    this.syncActorNode(driver);
    veh.nodeId = driver.nodeId;
    return { ok: true, arrived: false };
  }

  setActorHidden(actorId: string, hidden: boolean): void {
    const a = this.actors[actorId];
    if (a) a.hidden = hidden;
  }

  setActorRole(actorId: string, role: string | undefined): void {
    const a = this.actors[actorId];
    if (a) a.role = role;
  }

  engagePerson(
    actorId: string,
    targetPersonId: string,
    kind: 'talk' | 'ask' | 'tell' | 'lie' | 'warn' | 'distract',
    manner?: Manner,
    payload?: string,
  ): ActionOutcome {
    this.log.push({
      timeSec: this.timeSec,
      actorId,
      action: kind,
      target: targetPersonId,
      detail: payload ?? manner?.kind,
    });
    return { ok: true };
  }

  emitSignal(actorId: string, signal: string): void {
    this.signals.push({ timeSec: this.timeSec, actorId, signal });
    this.log.push({ timeSec: this.timeSec, actorId, action: 'signal', detail: signal });
  }

  transferItem(): ActionOutcome {
    return { ok: true };
  }

  setDoorOpen(): ActionOutcome {
    return { ok: true };
  }

  setDoorLocked(): ActionOutcome {
    return { ok: true };
  }

  useItem(actorId: string, itemId: string, target?: ResolvedTargetRef): ActionOutcome {
    this.log.push({ timeSec: this.timeSec, actorId, action: 'use', target: itemId, detail: target?.kind });
    return { ok: true };
  }

  tradeItem(): ActionOutcome {
    return { ok: true };
  }

  stealItem(): ActionOutcome {
    return { ok: false, code: 'trait_refused', message: 'not supported' };
  }

  combatAction(
    actorId: string,
    targetPersonId: string,
    mode: 'attack' | 'restrain',
    itemId?: string,
  ): ActionOutcome {
    const target = this.actors[targetPersonId];
    if (!target) return { ok: false, code: 'target_missing', message: `missing ${targetPersonId}` };
    if (mode === 'restrain') {
      target.inCustody = true;
      target.fleeing = false;
    }
    this.log.push({ timeSec: this.timeSec, actorId, action: mode, target: targetPersonId, detail: itemId });
    return { ok: true };
  }

  callTarget(actorId: string, target?: ResolvedTargetRef, payload?: string): ActionOutcome {
    this.log.push({ timeSec: this.timeSec, actorId, action: 'call', target: target?.kind === 'place' ? target.id : undefined, detail: payload });
    return { ok: true };
  }

  writeNote(actorId: string, target?: ResolvedTargetRef, payload?: string): ActionOutcome {
    this.log.push({ timeSec: this.timeSec, actorId, action: 'write', target: target?.kind === 'place' ? target.id : undefined, detail: payload });
    return { ok: true };
  }
}

/** Helper to create a minimal deterministic `World` with plan-safe person ids for stub town tests. */
export function createStubWorld(seed: string, year: number, personIds: readonly string[]): World {
  const people: Record<Id, Person> = {};
  for (let i = 0; i < personIds.length; i++) {
    const id = personIds[i]!;
    const age = 22 + Math.floor(rand(seed, 'age', id) * 35);
    people[id] = {
      id,
      first: `Citizen`,
      surname: id,
      sex: i % 2 === 0 ? 'M' : 'F',
      birthYear: year - age,
      traits: {
        ambition: rand(seed, 'amb', id),
        charm: rand(seed, 'charm', id),
        diligence: rand(seed, 'dil', id),
      },
      parents: [],
      children: [],
      wealth: 600,
      reputation: 0.1,
    };
  }
  return {
    seed,
    year,
    people,
    bonds: {},
    events: [],
  } as unknown as World;
}
