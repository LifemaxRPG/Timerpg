/** Pure, JSON-serializable types for the Timerpg plan language. */

export const PLAN_VERBS = [
  'go',
  'follow',
  'wait',
  'watch',
  'hide',
  'talk',
  'ask',
  'tell',
  'lie',
  'warn',
  'distract',
  'signal',
  'take',
  'give',
  'put',
  'use',
  'open',
  'close',
  'lock',
  'unlock',
  'carry',
  'drive',
  'buy',
  'sell',
  'steal',
  'attack',
  'restrain',
  'flee',
  'call',
  'write',
] as const;

export type PlanVerb = (typeof PLAN_VERBS)[number];

export type Combinator = 'then' | 'while';

export type TargetRef =
  | { kind: 'person'; id: string }
  | { kind: 'place'; id: string }
  | { kind: 'item'; id: string }
  | { kind: 'vehicle'; id: string }
  | { kind: 'player' }
  | { kind: 'nearest'; entityKind: string }
  | { kind: 'here' };

export type ResolvedTargetRef =
  | { kind: 'person'; id: string }
  | { kind: 'place'; id: string }
  | { kind: 'item'; id: string }
  | { kind: 'vehicle'; id: string }
  | { kind: 'player' }
  | { kind: 'here' };

export type ItemRef = { kind: 'item'; id: string };

export type PlaceTargetRef =
  | { kind: 'place'; id: string }
  | { kind: 'nearest'; entityKind: string }
  | { kind: 'here' };

export type SubjectTargetRef =
  | { kind: 'person'; id: string }
  | { kind: 'vehicle'; id: string }
  | { kind: 'player' }
  | { kind: 'nearest'; entityKind: string };

export type Manner =
  | { kind: 'quietly' }
  | { kind: 'quickly' }
  | { kind: 'carefully' }
  | { kind: 'politely' }
  | { kind: 'rudely' }
  | { kind: 'as'; role: string };

export type ConditionAtom =
  | { kind: 'sees'; target: TargetRef }
  | { kind: 'at'; subject?: SubjectTargetRef; place: PlaceTargetRef }
  | { kind: 'time'; hhmm: string; secondsOfDay: number }
  | { kind: 'heard'; signal: string }
  | { kind: 'has'; item: ItemRef }
  | { kind: 'alone' }
  | { kind: 'done'; stepNumber: number };

export interface Condition {
  negated: boolean;
  atom: ConditionAtom;
}

export interface PlanStep {
  /** 1-based index matching `done(n)`. */
  index: number;
  verb: PlanVerb;
  target?: TargetRef;
  withItem?: ItemRef;
  manner?: Manner;
  until?: Condition;
  ifCond?: Condition;
}

/** A stage is a contiguous group of steps joined by `while`. Stages execute sequentially (`then`). */
export interface PlanStage {
  /** Inclusive start index into `Plan.steps` (0-based). */
  start: number;
  /** Exclusive end index into `Plan.steps` (0-based). */
  end: number;
}

export interface Plan {
  source: string;
  steps: PlanStep[];
  /** `combinators[i]` joins `steps[i]` and `steps[i + 1]`. */
  combinators: Combinator[];
  /** Pre-grouped `then`-delimited stages of concurrent `while` steps. */
  stages: PlanStage[];
}

export type ParseResult =
  | { ok: true; plan: Plan }
  | { ok: false; error: string; offset: number };

/** Facts handed to the model for one actor at planning time. */
export interface PlanFacts {
  actorId: string;
  knownPeople: readonly string[];
  knownPlaces: readonly string[];
  knownItems: readonly string[];
  /** Items the actor currently holds in inventory. */
  heldItems: readonly string[];
  knownVehicles: readonly string[];
  /** Optional allowlist for `nearest(<kind>)`. When omitted, any syntactic kind is allowed. */
  knownKinds?: readonly string[];
  /** Optional allowlist for `heard(<signal>)`. When omitted, any syntactic signal is allowed. */
  knownSignals?: readonly string[];
}

export type BindErrorCode =
  | 'unknown_person'
  | 'unknown_place'
  | 'unknown_item'
  | 'unknown_vehicle'
  | 'unknown_kind'
  | 'unknown_signal'
  | 'item_not_held'
  | 'invalid_step_ref';

export interface BindError {
  stepIndex: number;
  field: 'target' | 'with' | 'until' | 'if';
  ref: string;
  code: BindErrorCode;
  reason: string;
}

export interface BoundPlan {
  plan: Plan;
  actorId: string;
}

export type BindResult =
  | { ok: true; bound: BoundPlan }
  | { ok: false; errors: BindError[] };

/** Behavioral traits checked by the trait gate before sensitive verbs run. All values in 0..1. */
export interface ActorTraits {
  gentle: number;   // >= 0.70 refuses attack; >= 0.85 refuses restrain
  honest: number;   // >= 0.80 refuses lie; >= 0.85 refuses steal
  lawful: number;   // >= 0.75 refuses steal
  brave: number;    // 0..1 (affects willingness / future combat risk)
  ambition?: number;
  charm?: number;
  diligence?: number;
}

export interface Vec2 {
  x: number;
  y: number;
}

export type StepStatus = 'pending' | 'running' | 'done' | 'skipped' | 'failed' | 'refused';

export type PlanStatus = 'running' | 'completed' | 'failed' | 'refused';

export type FailureCode =
  | 'blocked_locked'
  | 'blocked_closed'
  | 'blocked_path'
  | 'unreachable'
  | 'missing_item'
  | 'missing_key'
  | 'wrong_key'
  | 'target_missing'
  | 'target_not_visible'
  | 'target_lost'
  | 'insufficient_funds'
  | 'caught'
  | 'not_in_vehicle'
  | 'invalid_target'
  | 'trait_refused';

export interface PlanFailure {
  code: FailureCode;
  stepIndex: number;
  verb: PlanVerb;
  target?: string;
  trait?: keyof ActorTraits;
  message: string;
}

export interface ActionOutcome {
  ok: boolean;
  code?: FailureCode;
  message?: string;
}

/**
 * Plain-JSON execution state for one actor running one plan.
 * Safe to serialize with `JSON.stringify` / `JSON.parse` for saves and time travel.
 */
export interface PlanState {
  plan: Plan;
  actorId: string;
  status: PlanStatus;
  stageIndex: number;
  stageActive: boolean;
  stageHasDriver: boolean;
  stepStatuses: StepStatus[];
  startedAtSec: number;
  stageStartedAtSec: number;
  stepStartedAtSec: number[];
  /** Places observed while executing `follow` / `watch` (in chronological order, deduped consecutively). */
  observedPlaces: string[];
  /** Subject id being tracked when `observedPlaces` was recorded. */
  observedSubject?: string;
  /** Set when `status === 'failed'` or `status === 'refused'`. */
  failure?: PlanFailure;
  /** Number of `step()` ticks executed on this plan. */
  ticks: number;
}

export interface StepResult {
  status: PlanStatus;
  stageIndex: number;
  failure?: PlanFailure;
}

/** Minimal world interface that `step()` executes against. */
export interface PlanWorld {
  readonly timeSec: number;
  getActorPosition(actorId: string): Vec2;
  getActorPlace(actorId: string): string;
  getEntityPosition(ref: ResolvedTargetRef): Vec2 | undefined;
  getEntityPlace(ref: ResolvedTargetRef): string | undefined;
  canSee(observerId: string, target: ResolvedTargetRef): boolean;
  isAlone(actorId: string): boolean;
  isLocked(placeId: string): boolean;
  isOpen(placeId: string): boolean;
  requiredKeyFor(placeId: string): string | undefined;
  hasItem(actorId: string, itemId: string): boolean;
  getItemOwner(itemId: string): string | undefined;
  getItemPrice(itemId: string): number | undefined;
  getActorMoney(actorId: string): number;
  hasHeardSignal(actorId: string, signal: string, sinceSec: number): boolean;
  findNearest(actorId: string, kind: string): ResolvedTargetRef | undefined;
  getActorTraits(actorId: string): ActorTraits;

  /**
   * Moves actor toward `dest` (or `destPlaceId` if provided for doorway/room checks).
   * Returns `{ ok: true, arrived }` or `{ ok: false, code, message }` if blocked by a locked/closed door.
   */
  moveActorToward(
    actorId: string,
    dest: Vec2,
    speedMps: number,
    dt: number,
    destPlaceId?: string,
  ): { ok: true; arrived: boolean } | { ok: false; code: FailureCode; message: string };

  moveVehicleToward(
    vehicleId: string,
    driverId: string,
    dest: Vec2,
    speedMps: number,
    dt: number,
    destPlaceId?: string,
  ): { ok: true; arrived: boolean } | { ok: false; code: FailureCode; message: string };

  setActorHidden(actorId: string, hidden: boolean): void;
  setActorRole(actorId: string, role: string | undefined): void;
  engagePerson(
    actorId: string,
    targetPersonId: string,
    kind: 'talk' | 'ask' | 'tell' | 'lie' | 'warn' | 'distract',
    manner?: Manner,
    payload?: string,
  ): ActionOutcome;
  emitSignal(actorId: string, signal: string): void;
  transferItem(
    itemId: string,
    fromId: string | undefined,
    toId: string | undefined,
    placeId?: string,
  ): ActionOutcome;
  setDoorOpen(placeId: string, open: boolean): ActionOutcome;
  setDoorLocked(placeId: string, locked: boolean, keyItemId?: string): ActionOutcome;
  useItem(actorId: string, itemId: string, target?: ResolvedTargetRef): ActionOutcome;
  tradeItem(
    actorId: string,
    itemId: string,
    mode: 'buy' | 'sell',
    counterparty?: ResolvedTargetRef,
  ): ActionOutcome;
  stealItem(actorId: string, itemId: string, manner?: Manner): ActionOutcome;
  combatAction(
    actorId: string,
    targetPersonId: string,
    mode: 'attack' | 'restrain',
    itemId?: string,
  ): ActionOutcome;
  callTarget(actorId: string, target?: ResolvedTargetRef, payload?: string): ActionOutcome;
  writeNote(actorId: string, target?: ResolvedTargetRef, payload?: string): ActionOutcome;
}

export function formatTargetRef(ref: TargetRef): string {
  switch (ref.kind) {
    case 'person':
      return `person:${ref.id}`;
    case 'place':
      return `place:${ref.id}`;
    case 'item':
      return `item:${ref.id}`;
    case 'vehicle':
      return `vehicle:${ref.id}`;
    case 'player':
      return 'player';
    case 'nearest':
      return `nearest(${ref.entityKind})`;
    case 'here':
      return 'here';
  }
}
