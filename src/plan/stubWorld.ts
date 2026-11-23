import type {
  ActionOutcome,
  ActorTraits,
  FailureCode,
  Manner,
  PlanFacts,
  PlanWorld,
  ResolvedTargetRef,
  Vec2,
} from './types';

export interface StubPlace {
  id: string;
  x: number;
  y: number;
  kind: 'room' | 'door' | 'street' | 'yard';
  neighbors: string[];
  isDoor?: boolean;
  open?: boolean;
  locked?: boolean;
  requiredKey?: string;
}

export interface StubActor {
  id: string;
  kind: 'player' | 'companion' | 'guard' | 'npc';
  x: number;
  y: number;
  placeId: string;
  inventory: string[];
  money: number;
  hidden: boolean;
  role?: string;
  traits: ActorTraits;
  distractedUntilSec: number;
  distractedBy?: string;
  patrolRoute?: string[];
  patrolIndex: number;
  patrolSpeedMps: number;
  restrained?: boolean;
  hp: number;
}

export interface StubItem {
  id: string;
  kind: string;
  placeId?: string;
  holderId?: string;
  ownerId?: string;
  x: number;
  y: number;
  price?: number;
}

export interface StubVehicle {
  id: string;
  kind: string;
  placeId: string;
  x: number;
  y: number;
  driverId?: string;
}

export interface StubSignalEvent {
  senderId: string;
  signal: string;
  timeSec: number;
}

export interface StubLogEntry {
  timeSec: number;
  actorId: string;
  verb: string;
  target?: string;
  payload?: string;
}

const ZERO_VEC: Vec2 = { x: 0, y: 0 };
const MOVE_ARRIVED = { ok: true as const, arrived: true };
const MOVE_IN_PROGRESS = { ok: true as const, arrived: false };

export const DEFAULT_TRAITS: ActorTraits = {
  gentle: 0.4,
  honest: 0.4,
  lawful: 0.4,
  brave: 0.6,
};

/**
 * Deterministic stub world for plan execution tests:
 * a few connected rooms/yards, locked doors with keys, two patrolling guards,
 * a van, a player, and a companion.
 */
export class StubWorld implements PlanWorld {
  timeSec: number;
  readonly places = new Map<string, StubPlace>();
  readonly actors = new Map<string, StubActor>();
  readonly items = new Map<string, StubItem>();
  readonly vehicles = new Map<string, StubVehicle>();
  readonly signals: StubSignalEvent[] = [];
  readonly log: StubLogEntry[] = [];

  /** Fast room-pair door lookup for movement blocking checks. */
  private readonly edgeDoor = new Map<string, string>();
  /** Fast BFS path cache invalidated whenever a door changes state (zero alloc on steady ticks). */
  private pathVersion = 0;

  constructor(startTimeSec = 8 * 3600) {
    this.timeSec = startTimeSec;
  }

  addPlace(place: StubPlace): void {
    this.places.set(place.id, place);
    if (place.isDoor && place.neighbors.length === 2) {
      const [a, b] = place.neighbors as [string, string];
      this.edgeDoor.set(`${a}|${b}`, place.id);
      this.edgeDoor.set(`${b}|${a}`, place.id);
    }
    this.pathVersion++;
  }

  addActor(actor: StubActor): void {
    this.actors.set(actor.id, actor);
  }

  addItem(item: StubItem): void {
    this.items.set(item.id, item);
  }

  addVehicle(vehicle: StubVehicle): void {
    this.vehicles.set(vehicle.id, vehicle);
  }

  /** Advance world clock and patrolling guards deterministically. */
  advance(dt: number): void {
    this.timeSec += dt;
    for (const actor of this.actors.values()) {
      if (actor.kind !== 'guard' || !actor.patrolRoute || actor.patrolRoute.length === 0) continue;
      if (actor.restrained || actor.hp <= 0) continue;
      if (this.timeSec < actor.distractedUntilSec) continue;

      const targetPlaceId = actor.patrolRoute[actor.patrolIndex]!;
      const place = this.places.get(targetPlaceId);
      if (!place) continue;
      const dx = place.x - actor.x;
      const dy = place.y - actor.y;
      const dist = Math.hypot(dx, dy);
      const stepDist = actor.patrolSpeedMps * dt;
      if (dist <= stepDist + 0.25) {
        actor.x = place.x;
        actor.y = place.y;
        actor.placeId = place.id;
        actor.patrolIndex = (actor.patrolIndex + 1) % actor.patrolRoute.length;
      } else {
        actor.x += (dx / dist) * stepDist;
        actor.y += (dy / dist) * stepDist;
        actor.placeId = this.nearestPlaceId(actor.x, actor.y);
      }
    }
  }

  /** Moves the player along open passages toward a place (helper for scenarios). */
  movePlayerTo(placeId: string): boolean {
    const p = this.actors.get('player');
    const dest = this.places.get(placeId);
    if (!p || !dest) return false;
    const blocked = this.findBlockedDoorOnPath(p.placeId, placeId);
    if (blocked) return false;
    p.x = dest.x;
    p.y = dest.y;
    p.placeId = dest.id;
    return true;
  }

  /** Builds the `PlanFacts` snapshot handed to the model for `actorId`. */
  factsFor(actorId: string): PlanFacts {
    const actor = this.actors.get(actorId);
    const knownPeople: string[] = [];
    for (const a of this.actors.values()) {
      if (a.id !== actorId && a.id !== 'player') knownPeople.push(a.id);
    }
    return {
      actorId,
      knownPeople,
      knownPlaces: Array.from(this.places.keys()),
      knownItems: Array.from(this.items.keys()),
      heldItems: actor ? [...actor.inventory] : [],
      knownVehicles: Array.from(this.vehicles.keys()),
      knownKinds: ['guard', 'person', 'door', 'gate', 'exit', 'key', 'item', 'vehicle', 'van', 'place'],
    };
  }

  private nearestPlaceId(x: number, y: number): string {
    let bestId = '';
    let bestD2 = Infinity;
    for (const p of this.places.values()) {
      const dx = p.x - x;
      const dy = p.y - y;
      const d2 = dx * dx + dy * dy;
      if (d2 < bestD2) {
        bestD2 = d2;
        bestId = p.id;
      }
    }
    return bestId;
  }

  /**
   * Checks whether reaching `destPlaceId` from `fromPlaceId` is blocked by a locked or closed door.
   * Walking *up to* a door itself (`destPlaceId === door.id`) is always allowed from an adjacent room.
   */
  private findBlockedDoorOnPath(
    fromPlaceId: string,
    destPlaceId: string,
  ): { doorId: string; locked: boolean } | undefined {
    if (fromPlaceId === destPlaceId) return undefined;
    const start = this.places.get(fromPlaceId);
    const goal = this.places.get(destPlaceId);
    if (!start || !goal) return undefined;

    // Fast path: directly adjacent open neighbor (no allocations on common single-room moves)
    if (start.neighbors.includes(destPlaceId)) {
      if (goal.isDoor) return undefined; // Walking up to the door itself is always allowed
      if (start.isDoor && (start.locked || !start.open)) {
        return { doorId: start.id, locked: Boolean(start.locked) };
      }
      return undefined;
    }

    // BFS over open edges; if unreachable, inspect doors blocking the shortest topological path.
    const openVisited = new Set<string>([fromPlaceId]);
    const openQueue: string[] = [fromPlaceId];
    for (let qi = 0; qi < openQueue.length; qi++) {
      const curId = openQueue[qi]!;
      if (curId === destPlaceId) return undefined;
      const cur = this.places.get(curId);
      if (!cur) continue;
      // Cannot pass *through* a closed/locked door to rooms beyond it.
      if (cur.isDoor && curId !== fromPlaceId && (cur.locked || !cur.open)) continue;
      for (const nextId of cur.neighbors) {
        if (openVisited.has(nextId)) continue;
        openVisited.add(nextId);
        if (nextId === destPlaceId) return undefined;
        openQueue.push(nextId);
      }
    }

    // Not reachable via open doors — find the first closed/locked door along the topological shortest path.
    const parent = new Map<string, string | null>([[fromPlaceId, null]]);
    const q: string[] = [fromPlaceId];
    let found = false;
    for (let qi = 0; qi < q.length; qi++) {
      const curId = q[qi]!;
      if (curId === destPlaceId) {
        found = true;
        break;
      }
      const cur = this.places.get(curId);
      if (!cur) continue;
      for (const nextId of cur.neighbors) {
        if (!parent.has(nextId)) {
          parent.set(nextId, curId);
          q.push(nextId);
        }
      }
    }
    if (!found) return undefined;

    const path: string[] = [];
    let cur: string | null | undefined = destPlaceId;
    while (cur) {
      path.push(cur);
      cur = parent.get(cur);
    }
    path.reverse();

    for (let i = 1; i < path.length - 1; i++) {
      const p = this.places.get(path[i]!);
      if (p?.isDoor && (p.locked || !p.open)) {
        return { doorId: p.id, locked: Boolean(p.locked) };
      }
    }
    return undefined;
  }

  getActorPosition(actorId: string): Vec2 {
    return this.actors.get(actorId) ?? ZERO_VEC;
  }

  getActorPlace(actorId: string): string {
    return this.actors.get(actorId)?.placeId ?? '';
  }

  getEntityPosition(ref: ResolvedTargetRef): Vec2 | undefined {
    switch (ref.kind) {
      case 'player':
        return this.actors.get('player');
      case 'person':
        return this.actors.get(ref.id);
      case 'place':
        return this.places.get(ref.id);
      case 'item': {
        const it = this.items.get(ref.id);
        if (!it) return undefined;
        return it.holderId ? this.actors.get(it.holderId) : it;
      }
      case 'vehicle':
        return this.vehicles.get(ref.id);
      case 'here':
        return undefined;
    }
  }

  getEntityPlace(ref: ResolvedTargetRef): string | undefined {
    switch (ref.kind) {
      case 'player':
        return this.actors.get('player')?.placeId;
      case 'person':
        return this.actors.get(ref.id)?.placeId;
      case 'place':
        return this.places.has(ref.id) ? ref.id : undefined;
      case 'item': {
        const it = this.items.get(ref.id);
        if (!it) return undefined;
        return it.holderId ? this.actors.get(it.holderId)?.placeId : it.placeId;
      }
      case 'vehicle':
        return this.vehicles.get(ref.id)?.placeId;
      case 'here':
        return undefined;
    }
  }

  canSee(observerId: string, target: ResolvedTargetRef): boolean {
    const obs = this.actors.get(observerId);
    if (!obs) return false;
    if (target.kind === 'here') return true;

    let tx = 0;
    let ty = 0;
    let targetPlaceId: string | undefined;

    if (target.kind === 'person' || target.kind === 'player') {
      const other = this.actors.get(target.kind === 'player' ? 'player' : target.id);
      if (!other) return false;
      if (other.hidden && obs.kind === 'guard') {
        const dx = obs.x - other.x;
        const dy = obs.y - other.y;
        return dx * dx + dy * dy <= 4.0;
      }
      tx = other.x;
      ty = other.y;
      targetPlaceId = other.placeId;
    } else if (target.kind === 'place') {
      const pl = this.places.get(target.id);
      if (!pl) return false;
      tx = pl.x;
      ty = pl.y;
      targetPlaceId = pl.id;
    } else if (target.kind === 'vehicle') {
      const v = this.vehicles.get(target.id);
      if (!v) return false;
      tx = v.x;
      ty = v.y;
      targetPlaceId = v.placeId;
    } else {
      const it = this.items.get(target.id);
      if (!it) return false;
      if (it.holderId) {
        const h = this.actors.get(it.holderId);
        if (!h) return false;
        tx = h.x;
        ty = h.y;
        targetPlaceId = h.placeId;
      } else {
        tx = it.x;
        ty = it.y;
        targetPlaceId = it.placeId;
      }
    }

    if (!targetPlaceId) return false;
    if (obs.placeId === targetPlaceId) return true;

    const obsPlace = this.places.get(obs.placeId);
    if (!obsPlace) return false;
    if (obsPlace.neighbors.includes(targetPlaceId)) {
      const targetPlace = this.places.get(targetPlaceId);
      if (targetPlace?.isDoor && !targetPlace.open) {
        return target.kind === 'place' && target.id === targetPlaceId;
      }
      const dx = obs.x - tx;
      const dy = obs.y - ty;
      return dx * dx + dy * dy <= 484;
    }
    return false;
  }

  isAlone(actorId: string): boolean {
    const obs = this.actors.get(actorId);
    if (!obs) return true;
    for (const other of this.actors.values()) {
      if (other.id === actorId) continue;
      if (other.placeId === obs.placeId) return false;
    }
    return true;
  }

  isLocked(placeId: string): boolean {
    return Boolean(this.places.get(placeId)?.locked);
  }

  isOpen(placeId: string): boolean {
    const p = this.places.get(placeId);
    if (!p) return false;
    return p.isDoor ? Boolean(p.open) : true;
  }

  requiredKeyFor(placeId: string): string | undefined {
    return this.places.get(placeId)?.requiredKey;
  }

  hasItem(actorId: string, itemId: string): boolean {
    const a = this.actors.get(actorId);
    return a ? a.inventory.includes(itemId) : false;
  }

  getItemOwner(itemId: string): string | undefined {
    return this.items.get(itemId)?.ownerId;
  }

  getItemPrice(itemId: string): number | undefined {
    return this.items.get(itemId)?.price;
  }

  getActorMoney(actorId: string): number {
    return this.actors.get(actorId)?.money ?? 0;
  }

  hasHeardSignal(actorId: string, signal: string, sinceSec: number): boolean {
    if (!this.actors.has(actorId)) return false;
    for (let i = this.signals.length - 1; i >= 0; i--) {
      const ev = this.signals[i]!;
      if (ev.timeSec < sinceSec) break;
      if (ev.signal === signal) return true;
    }
    return false;
  }

  findNearest(actorId: string, kind: string): ResolvedTargetRef | undefined {
    const obs = this.actors.get(actorId);
    if (!obs) return undefined;
    let bestRef: ResolvedTargetRef | undefined;
    let bestD2 = Infinity;

    if (kind === 'guard' || kind === 'person') {
      for (const a of this.actors.values()) {
        if (a.id === actorId) continue;
        if (kind === 'guard' && a.kind !== 'guard') continue;
        const d2 = (a.x - obs.x) ** 2 + (a.y - obs.y) ** 2;
        if (d2 < bestD2) {
          bestD2 = d2;
          bestRef = a.id === 'player' ? { kind: 'player' } : { kind: 'person', id: a.id };
        }
      }
      return bestRef;
    }

    if (kind === 'vehicle' || kind === 'van') {
      for (const v of this.vehicles.values()) {
        if (kind !== 'vehicle' && v.kind !== kind) continue;
        const d2 = (v.x - obs.x) ** 2 + (v.y - obs.y) ** 2;
        if (d2 < bestD2) {
          bestD2 = d2;
          bestRef = { kind: 'vehicle', id: v.id };
        }
      }
      return bestRef;
    }

    if (kind === 'key' || kind === 'item') {
      for (const it of this.items.values()) {
        if (kind !== 'item' && it.kind !== kind) continue;
        const pos = this.getEntityPosition({ kind: 'item', id: it.id });
        if (!pos) continue;
        const d2 = (pos.x - obs.x) ** 2 + (pos.y - obs.y) ** 2;
        if (d2 < bestD2) {
          bestD2 = d2;
          bestRef = { kind: 'item', id: it.id };
        }
      }
      return bestRef;
    }

    for (const p of this.places.values()) {
      if (
        (kind === 'door' || kind === 'gate') && !p.isDoor
      ) continue;
      if (kind === 'exit' && p.kind !== 'street') continue;
      const d2 = (p.x - obs.x) ** 2 + (p.y - obs.y) ** 2;
      if (d2 < bestD2) {
        bestD2 = d2;
        bestRef = { kind: 'place', id: p.id };
      }
    }
    return bestRef;
  }

  getActorTraits(actorId: string): ActorTraits {
    return this.actors.get(actorId)?.traits ?? DEFAULT_TRAITS;
  }

  moveActorToward(
    actorId: string,
    dest: Vec2,
    speedMps: number,
    dt: number,
    destPlaceId?: string,
  ): { ok: true; arrived: boolean } | { ok: false; code: FailureCode; message: string } {
    const actor = this.actors.get(actorId);
    if (!actor) return { ok: false, code: 'target_missing', message: `missing actor ${actorId}` };

    if (destPlaceId && destPlaceId !== actor.placeId) {
      const blocked = this.findBlockedDoorOnPath(actor.placeId, destPlaceId);
      if (blocked) {
        return {
          ok: false,
          code: blocked.locked ? 'blocked_locked' : 'blocked_closed',
          message: `blocked: place:${blocked.doorId} ${blocked.locked ? 'locked' : 'closed'}`,
        };
      }
    }

    const dx = dest.x - actor.x;
    const dy = dest.y - actor.y;
    const d2 = dx * dx + dy * dy;
    const maxStep = speedMps * dt;
    if (d2 <= (maxStep + 0.25) * (maxStep + 0.25)) {
      actor.x = dest.x;
      actor.y = dest.y;
      actor.placeId = destPlaceId ?? this.nearestPlaceId(actor.x, actor.y);
      return MOVE_ARRIVED;
    }
    const d = Math.sqrt(d2);
    actor.x += (dx / d) * maxStep;
    actor.y += (dy / d) * maxStep;
    actor.placeId = this.nearestPlaceId(actor.x, actor.y);
    return MOVE_IN_PROGRESS;
  }

  moveVehicleToward(
    vehicleId: string,
    driverId: string,
    dest: Vec2,
    speedMps: number,
    dt: number,
    destPlaceId?: string,
  ): { ok: true; arrived: boolean } | { ok: false; code: FailureCode; message: string } {
    const veh = this.vehicles.get(vehicleId);
    const driver = this.actors.get(driverId);
    if (!veh || !driver) {
      return { ok: false, code: 'target_missing', message: `missing vehicle:${vehicleId}` };
    }

    if (destPlaceId && destPlaceId !== veh.placeId) {
      const blocked = this.findBlockedDoorOnPath(veh.placeId, destPlaceId);
      if (blocked) {
        return {
          ok: false,
          code: blocked.locked ? 'blocked_locked' : 'blocked_closed',
          message: `blocked: place:${blocked.doorId} ${blocked.locked ? 'locked' : 'closed'}`,
        };
      }
    }

    veh.driverId = driverId;
    const dx = dest.x - veh.x;
    const dy = dest.y - veh.y;
    const d2 = dx * dx + dy * dy;
    const maxStep = speedMps * dt;
    if (d2 <= (maxStep + 0.35) * (maxStep + 0.35)) {
      veh.x = dest.x;
      veh.y = dest.y;
      veh.placeId = destPlaceId ?? this.nearestPlaceId(veh.x, veh.y);
      driver.x = veh.x;
      driver.y = veh.y;
      driver.placeId = veh.placeId;
      return MOVE_ARRIVED;
    }
    const d = Math.sqrt(d2);
    veh.x += (dx / d) * maxStep;
    veh.y += (dy / d) * maxStep;
    veh.placeId = this.nearestPlaceId(veh.x, veh.y);
    driver.x = veh.x;
    driver.y = veh.y;
    driver.placeId = veh.placeId;
    return MOVE_IN_PROGRESS;
  }

  setActorHidden(actorId: string, hidden: boolean): void {
    const a = this.actors.get(actorId);
    if (a) a.hidden = hidden;
  }

  setActorRole(actorId: string, role: string | undefined): void {
    const a = this.actors.get(actorId);
    if (a) a.role = role;
  }

  engagePerson(
    actorId: string,
    targetPersonId: string,
    kind: 'talk' | 'ask' | 'tell' | 'lie' | 'warn' | 'distract',
    _manner?: Manner,
    payload?: string,
  ): ActionOutcome {
    const target = this.actors.get(targetPersonId);
    if (!target) return { ok: false, code: 'target_missing', message: `missing person:${targetPersonId}` };
    if (kind === 'distract' || kind === 'talk') {
      target.distractedUntilSec = this.timeSec + 5;
      target.distractedBy = actorId;
    }
    if (kind === 'tell' || kind === 'warn' || kind === 'ask' || kind === 'lie') {
      this.log.push({
        timeSec: this.timeSec,
        actorId,
        verb: kind,
        target: targetPersonId,
        payload,
      });
    }
    return { ok: true };
  }

  emitSignal(actorId: string, signal: string): void {
    this.signals.push({ senderId: actorId, signal, timeSec: this.timeSec });
    this.log.push({ timeSec: this.timeSec, actorId, verb: 'signal', payload: signal });
  }

  transferItem(
    itemId: string,
    fromId: string | undefined,
    toId: string | undefined,
    placeId?: string,
  ): ActionOutcome {
    const it = this.items.get(itemId);
    if (!it) return { ok: false, code: 'missing_item', message: `missing item:${itemId}` };
    if (fromId) {
      const from = this.actors.get(fromId);
      if (!from) return { ok: false, code: 'target_missing', message: `missing ${fromId}` };
      const idx = from.inventory.indexOf(itemId);
      if (idx < 0) return { ok: false, code: 'missing_item', message: `${fromId} does not hold item:${itemId}` };
      from.inventory.splice(idx, 1);
    }
    if (toId) {
      const to = this.actors.get(toId);
      if (!to) return { ok: false, code: 'target_missing', message: `missing ${toId}` };
      if (!to.inventory.includes(itemId)) to.inventory.push(itemId);
      it.holderId = toId;
      it.placeId = undefined;
    } else {
      it.holderId = undefined;
      const p = placeId ? this.places.get(placeId) : undefined;
      it.placeId = placeId;
      if (p) {
        it.x = p.x;
        it.y = p.y;
      }
    }
    return { ok: true };
  }

  setDoorOpen(placeId: string, open: boolean): ActionOutcome {
    const p = this.places.get(placeId);
    if (!p || !p.isDoor) {
      return { ok: false, code: 'invalid_target', message: `place:${placeId} is not a door` };
    }
    if (open && p.locked) {
      return { ok: false, code: 'blocked_locked', message: `blocked: place:${placeId} locked` };
    }
    p.open = open;
    this.pathVersion++;
    this.log.push({ timeSec: this.timeSec, actorId: 'world', verb: open ? 'open' : 'close', target: placeId });
    return { ok: true };
  }

  setDoorLocked(placeId: string, locked: boolean, _keyItemId?: string): ActionOutcome {
    const p = this.places.get(placeId);
    if (!p || !p.isDoor) {
      return { ok: false, code: 'invalid_target', message: `place:${placeId} is not a door` };
    }
    p.locked = locked;
    if (locked) p.open = false;
    this.pathVersion++;
    this.log.push({ timeSec: this.timeSec, actorId: 'world', verb: locked ? 'lock' : 'unlock', target: placeId });
    return { ok: true };
  }

  useItem(actorId: string, itemId: string, target?: ResolvedTargetRef): ActionOutcome {
    this.log.push({
      timeSec: this.timeSec,
      actorId,
      verb: 'use',
      target: target ? (target.kind === 'player' ? 'player' : target.kind === 'here' ? 'here' : `${target.kind}:${target.id}`) : undefined,
      payload: itemId,
    });
    return { ok: true };
  }

  tradeItem(
    actorId: string,
    itemId: string,
    mode: 'buy' | 'sell',
    _counterparty?: ResolvedTargetRef,
  ): ActionOutcome {
    const actor = this.actors.get(actorId);
    const it = this.items.get(itemId);
    if (!actor || !it) return { ok: false, code: 'missing_item', message: `missing item:${itemId}` };
    const price = it.price ?? 10;
    if (mode === 'buy') {
      if (actor.money < price) {
        return { ok: false, code: 'insufficient_funds', message: `need ${price}, have ${actor.money}` };
      }
      actor.money -= price;
      it.ownerId = actorId;
      return this.transferItem(itemId, it.holderId, actorId);
    }
    actor.money += price;
    it.ownerId = undefined;
    return this.transferItem(itemId, actorId, undefined, actor.placeId);
  }

  stealItem(actorId: string, itemId: string, manner?: Manner): ActionOutcome {
    const actor = this.actors.get(actorId);
    const it = this.items.get(itemId);
    if (!actor || !it) return { ok: false, code: 'missing_item', message: `missing item:${itemId}` };
    // Caught if a non-distracted guard sees the actor and actor is not hidden/quiet
    if (manner?.kind !== 'quietly' && !actor.hidden) {
      for (const a of this.actors.values()) {
        if (a.kind === 'guard' && this.timeSec >= a.distractedUntilSec && this.canSee(a.id, { kind: 'person', id: actorId })) {
          return { ok: false, code: 'caught', message: `caught stealing item:${itemId} by person:${a.id}` };
        }
      }
    }
    it.ownerId = actorId;
    return this.transferItem(itemId, it.holderId, actorId);
  }

  combatAction(
    actorId: string,
    targetPersonId: string,
    mode: 'attack' | 'restrain',
    _itemId?: string,
  ): ActionOutcome {
    const target = this.actors.get(targetPersonId);
    if (!target) return { ok: false, code: 'target_missing', message: `missing person:${targetPersonId}` };
    if (mode === 'attack') {
      target.hp = Math.max(0, target.hp - 25);
    } else {
      target.restrained = true;
    }
    this.log.push({ timeSec: this.timeSec, actorId, verb: mode, target: targetPersonId });
    return { ok: true };
  }

  callTarget(actorId: string, target?: ResolvedTargetRef, payload?: string): ActionOutcome {
    this.log.push({
      timeSec: this.timeSec,
      actorId,
      verb: 'call',
      target: target && 'id' in target ? `${target.kind}:${target.id}` : target?.kind,
      payload,
    });
    return { ok: true };
  }

  writeNote(actorId: string, target?: ResolvedTargetRef, payload?: string): ActionOutcome {
    this.log.push({
      timeSec: this.timeSec,
      actorId,
      verb: 'write',
      target: target && 'id' in target ? `${target.kind}:${target.id}` : target?.kind,
      payload,
    });
    return { ok: true };
  }
}

/**
 * Builds the canonical test compound:
 * - Rooms/yards: `front_gate`, `courtyard`, `office`, `storage`, `back_alley`, `parking_lot`
 * - Locked doors: `storage_door` (`item:storage_key` in `office`), `back_gate` (`item:gate_key` held by companion)
 * - Two patrolling guards: `guard_1` (front_gate <-> courtyard) and `guard_2` (courtyard -> office -> storage)
 * - Vehicle: `vehicle:van` in `parking_lot`
 * - Player and companion with configurable traits.
 */
export function createCompoundStubWorld(companionTraits?: Partial<ActorTraits>): StubWorld {
  const w = new StubWorld(8 * 3600); // 08:00:00

  w.addPlace({ id: 'front_gate', x: 0, y: 0, kind: 'street', neighbors: ['courtyard', 'parking_lot'] });
  w.addPlace({ id: 'courtyard', x: 10, y: 0, kind: 'yard', neighbors: ['front_gate', 'office', 'storage_door', 'back_gate'] });
  w.addPlace({ id: 'office', x: 10, y: -10, kind: 'room', neighbors: ['courtyard'] });
  w.addPlace({
    id: 'storage_door',
    x: 18,
    y: 0,
    kind: 'door',
    neighbors: ['courtyard', 'storage'],
    isDoor: true,
    open: false,
    locked: true,
    requiredKey: 'storage_key',
  });
  w.addPlace({ id: 'storage', x: 24, y: 0, kind: 'room', neighbors: ['storage_door'] });
  w.addPlace({
    id: 'back_gate',
    x: 10,
    y: 12,
    kind: 'door',
    neighbors: ['courtyard', 'back_alley'],
    isDoor: true,
    open: false,
    locked: true,
    requiredKey: 'gate_key',
  });
  w.addPlace({ id: 'back_alley', x: 10, y: 20, kind: 'street', neighbors: ['back_gate', 'parking_lot'] });
  w.addPlace({ id: 'parking_lot', x: -8, y: 12, kind: 'yard', neighbors: ['front_gate', 'back_alley'] });

  w.addActor({
    id: 'player',
    kind: 'player',
    x: -1,
    y: 0,
    placeId: 'front_gate',
    inventory: [],
    money: 100,
    hidden: false,
    traits: { ...DEFAULT_TRAITS },
    distractedUntilSec: 0,
    patrolIndex: 0,
    patrolSpeedMps: 0,
    hp: 100,
  });

  w.addActor({
    id: 'companion',
    kind: 'companion',
    x: 1,
    y: 0,
    placeId: 'front_gate',
    inventory: ['gate_key'],
    money: 50,
    hidden: false,
    traits: { ...DEFAULT_TRAITS, ...companionTraits },
    distractedUntilSec: 0,
    patrolIndex: 0,
    patrolSpeedMps: 0,
    hp: 100,
  });

  w.addActor({
    id: 'guard_1',
    kind: 'guard',
    x: 2,
    y: 0,
    placeId: 'front_gate',
    inventory: [],
    money: 20,
    hidden: false,
    traits: { gentle: 0.2, honest: 0.7, lawful: 0.9, brave: 0.8 },
    distractedUntilSec: 0,
    patrolRoute: ['front_gate', 'courtyard'],
    patrolIndex: 1,
    patrolSpeedMps: 2.0,
    hp: 100,
  });

  w.addActor({
    id: 'guard_2',
    kind: 'guard',
    x: 10,
    y: 0,
    placeId: 'courtyard',
    inventory: [],
    money: 20,
    hidden: false,
    traits: { gentle: 0.2, honest: 0.7, lawful: 0.9, brave: 0.8 },
    distractedUntilSec: 0,
    patrolRoute: ['courtyard', 'office', 'courtyard', 'back_gate'],
    patrolIndex: 1,
    patrolSpeedMps: 2.5,
    hp: 100,
  });

  w.addItem({
    id: 'gate_key',
    kind: 'key',
    holderId: 'companion',
    x: 1,
    y: 0,
  });

  w.addItem({
    id: 'storage_key',
    kind: 'key',
    placeId: 'office',
    x: 10,
    y: -10,
  });

  w.addItem({
    id: 'ledger',
    kind: 'item',
    placeId: 'storage',
    x: 24,
    y: 0,
  });

  w.addVehicle({
    id: 'van',
    kind: 'van',
    placeId: 'parking_lot',
    x: -8,
    y: 12,
  });

  return w;
}
