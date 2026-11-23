import {
  formatTargetRef,
  type ActorTraits,
  type BoundPlan,
  type Condition,
  type FailureCode,
  type Manner,
  type PlanFailure,
  type PlanState,
  type PlanStep,
  type PlanVerb,
  type PlanWorld,
  type ResolvedTargetRef,
  type StepResult,
  type StepStatus,
  type TargetRef,
  type Vec2,
} from './types';

const BASE_WALK_SPEED_MPS = 3.2;
const BASE_DRIVE_SPEED_MPS = 9.0;
const INTERACT_RANGE_SQ = 2.5 * 2.5;
const TALK_RANGE_SQ = 4.0 * 4.0;
const FOLLOW_NORMAL_RANGE_SQ = 2.2 * 2.2;
const FOLLOW_QUIET_RANGE_SQ = 4.5 * 4.5;
const FLEE_SAFE_DIST_SQ = 18.0 * 18.0;

/** Continuous verbs that keep running while a sibling `until` step is active in a `while` stage. */
function isContinuousVerb(verb: PlanVerb): boolean {
  return (
    verb === 'wait' ||
    verb === 'watch' ||
    verb === 'follow' ||
    verb === 'distract' ||
    verb === 'hide' ||
    verb === 'talk'
  );
}

function speedMultiplier(manner?: Manner): number {
  if (!manner) return 1.0;
  switch (manner.kind) {
    case 'quickly':
      return 1.5;
    case 'quietly':
      return 0.72;
    case 'carefully':
      return 0.75;
    default:
      return 1.0;
  }
}

function distSq(a: Vec2, b: Vec2): number {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return dx * dx + dy * dy;
}

/** Creates an initial plain-JSON execution state for a bound plan. */
export function createPlanState(bound: BoundPlan, startTimeSec = 0): PlanState {
  const count = bound.plan.steps.length;
  const stepStatuses: StepStatus[] = new Array<StepStatus>(count);
  const stepStartedAtSec: number[] = new Array<number>(count);
  for (let i = 0; i < count; i++) {
    stepStatuses[i] = 'pending';
    stepStartedAtSec[i] = -1;
  }
  return {
    plan: bound.plan,
    actorId: bound.actorId,
    status: count === 0 ? 'completed' : 'running',
    stageIndex: 0,
    stageActive: false,
    stageHasDriver: false,
    stepStatuses,
    startedAtSec: startTimeSec,
    stageStartedAtSec: startTimeSec,
    stepStartedAtSec,
    observedPlaces: [],
    ticks: 0,
  };
}

function resolveTarget(
  ref: TargetRef,
  world: PlanWorld,
  actorId: string,
): ResolvedTargetRef | undefined {
  if (ref.kind === 'nearest') {
    return world.findNearest(actorId, ref.entityKind);
  }
  return ref;
}

/** Evaluates a plan condition (`sees`, `at`, `time`, `heard`, `has`, `alone`, `done`, plus `not`). */
export function evaluateCondition(
  cond: Condition,
  state: PlanState,
  world: PlanWorld,
  actorId: string,
  stepIndex: number,
): boolean {
  const atom = cond.atom;
  let value = false;

  switch (atom.kind) {
    case 'sees': {
      const resolved = resolveTarget(atom.target, world, actorId);
      value = resolved !== undefined && world.canSee(actorId, resolved);
      break;
    }
    case 'at': {
      const resolvedPlace = resolveTarget(atom.place, world, actorId);
      if (resolvedPlace !== undefined) {
        const expectedPlaceId =
          resolvedPlace.kind === 'here'
            ? world.getActorPlace(actorId)
            : resolvedPlace.kind === 'place'
              ? resolvedPlace.id
              : world.getEntityPlace(resolvedPlace);
        if (expectedPlaceId !== undefined) {
          const curStep = state.plan.steps[stepIndex];
          if (!atom.subject && curStep?.verb === 'drive' && curStep.target?.kind === 'vehicle') {
            value = world.getEntityPlace(curStep.target) === expectedPlaceId;
          } else if (!atom.subject) {
            value = world.getActorPlace(actorId) === expectedPlaceId;
          } else {
            const subj = resolveTarget(atom.subject, world, actorId);
            if (subj !== undefined) {
              const subjPlace =
                subj.kind === 'player'
                  ? world.getActorPlace('player')
                  : world.getEntityPlace(subj);
              value = subjPlace === expectedPlaceId;
            }
          }
        }
      }
      break;
    }
    case 'time': {
      const t = world.timeSec;
      const daySec = t >= 0 && t < 86400 ? t : ((t % 86400) + 86400) % 86400;
      value = daySec >= atom.secondsOfDay;
      break;
    }
    case 'heard': {
      const since = state.stepStartedAtSec[stepIndex] ?? state.stageStartedAtSec;
      value = world.hasHeardSignal(actorId, atom.signal, since < 0 ? state.startedAtSec : since);
      break;
    }
    case 'has': {
      value = world.hasItem(actorId, atom.item.id);
      break;
    }
    case 'alone': {
      value = world.isAlone(actorId);
      break;
    }
    case 'done': {
      const idx = atom.stepNumber - 1;
      const st = state.stepStatuses[idx];
      value = st === 'done' || st === 'skipped';
      break;
    }
  }

  return cond.negated ? !value : value;
}

/**
 * Checks whether an actor's traits refuse the requested step before any world effect runs.
 * Returns a structured `PlanFailure` when refused, or `undefined` when permitted.
 */
export function checkTraitGate(
  step: PlanStep,
  stepIndex: number,
  traits: ActorTraits,
): PlanFailure | undefined {
  switch (step.verb) {
    case 'attack':
      if (traits.gentle >= 0.7) {
        return {
          code: 'trait_refused',
          stepIndex,
          verb: step.verb,
          target: step.target ? formatTargetRef(step.target) : undefined,
          trait: 'gentle',
          message: `refused: actor is too gentle to attack (gentle=${traits.gentle.toFixed(2)})`,
        };
      }
      return undefined;
    case 'restrain':
      if (traits.gentle >= 0.85) {
        return {
          code: 'trait_refused',
          stepIndex,
          verb: step.verb,
          target: step.target ? formatTargetRef(step.target) : undefined,
          trait: 'gentle',
          message: `refused: actor is too gentle to restrain anyone (gentle=${traits.gentle.toFixed(2)})`,
        };
      }
      return undefined;
    case 'steal':
      if (traits.lawful >= 0.75) {
        return {
          code: 'trait_refused',
          stepIndex,
          verb: step.verb,
          target: step.target ? formatTargetRef(step.target) : undefined,
          trait: 'lawful',
          message: `refused: actor is too lawful to steal (lawful=${traits.lawful.toFixed(2)})`,
        };
      }
      if (traits.honest >= 0.85) {
        return {
          code: 'trait_refused',
          stepIndex,
          verb: step.verb,
          target: step.target ? formatTargetRef(step.target) : undefined,
          trait: 'honest',
          message: `refused: actor is too honest to steal (honest=${traits.honest.toFixed(2)})`,
        };
      }
      return undefined;
    case 'lie':
      if (traits.honest >= 0.8) {
        return {
          code: 'trait_refused',
          stepIndex,
          verb: step.verb,
          target: step.target ? formatTargetRef(step.target) : undefined,
          trait: 'honest',
          message: `refused: actor is too honest to lie (honest=${traits.honest.toFixed(2)})`,
        };
      }
      return undefined;
    default:
      return undefined;
  }
}

function makeFailure(
  step: PlanStep,
  stepIndex: number,
  code: FailureCode,
  message: string,
): PlanFailure {
  return {
    code,
    stepIndex,
    verb: step.verb,
    target: step.target ? formatTargetRef(step.target) : undefined,
    message,
  };
}

function recordObservationRef(state: PlanState, ref: ResolvedTargetRef, placeId: string | undefined): void {
  if (!placeId) return;
  const list = state.observedPlaces;
  if (list.length > 0 && list[list.length - 1] === placeId) return;
  if (!state.observedSubject) {
    state.observedSubject = formatTargetRef(ref);
  }
  list.push(placeId);
}

function buildReportPayload(state: PlanState): string | undefined {
  if (state.observedPlaces.length === 0) return undefined;
  const subj = state.observedSubject ?? 'target';
  return `${subj} went: ${state.observedPlaces.join(' -> ')}`;
}

type SingleStepTick =
  | { kind: 'running' }
  | { kind: 'done' }
  | { kind: 'failed'; failure: PlanFailure };

const TICK_RUNNING: SingleStepTick = { kind: 'running' };
const TICK_DONE: SingleStepTick = { kind: 'done' };

function executeSingleStep(
  step: PlanStep,
  stepIndex: number,
  state: PlanState,
  world: PlanWorld,
  actorId: string,
  dt: number,
  sustainContinuous: boolean,
): SingleStepTick {
  const speed = BASE_WALK_SPEED_MPS * speedMultiplier(step.manner);

  // Apply stealth / disguise manner side-effects cleanly.
  if (step.manner) {
    if (step.manner.kind === 'quietly') {
      world.setActorHidden(actorId, true);
    } else if (step.manner.kind === 'as') {
      world.setActorRole(actorId, step.manner.role);
    }
  }

  const resolvedTarget = step.target ? resolveTarget(step.target, world, actorId) : undefined;
  if (step.target && !resolvedTarget) {
    return {
      kind: 'failed',
      failure: makeFailure(
        step,
        stepIndex,
        'target_missing',
        `missing: ${formatTargetRef(step.target)} not found`,
      ),
    };
  }

  switch (step.verb) {
    case 'wait': {
      return (step.until || sustainContinuous) ? TICK_RUNNING : TICK_DONE;
    }

    case 'hide': {
      if (resolvedTarget && resolvedTarget.kind !== 'here') {
        const pos = world.getEntityPosition(resolvedTarget);
        if (!pos) {
          return {
            kind: 'failed',
            failure: makeFailure(step, stepIndex, 'target_missing', 'missing: cover not found'),
          };
        }
        const destPlace = resolvedTarget.kind === 'place' ? resolvedTarget.id : undefined;
        const mv = world.moveActorToward(actorId, pos, speed, dt, destPlace);
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived) return TICK_RUNNING;
      }
      world.setActorHidden(actorId, true);
      return (step.until || sustainContinuous) ? TICK_RUNNING : TICK_DONE;
    }

    case 'go': {
      if (!resolvedTarget || resolvedTarget.kind === 'here') return TICK_DONE;
      const pos = world.getEntityPosition(resolvedTarget);
      if (!pos) {
        return {
          kind: 'failed',
          failure: makeFailure(
            step,
            stepIndex,
            'target_missing',
            `missing: ${formatTargetRef(resolvedTarget)} has no location`,
          ),
        };
      }
      const destPlace = resolvedTarget.kind === 'place' ? resolvedTarget.id : world.getEntityPlace(resolvedTarget);
      const mv = world.moveActorToward(actorId, pos, speed, dt, destPlace);
      if (!mv.ok) {
        return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
      }
      if (!mv.arrived) return TICK_RUNNING;
      return step.until ? TICK_RUNNING : TICK_DONE;
    }

    case 'follow': {
      if (!resolvedTarget || (resolvedTarget.kind !== 'person' && resolvedTarget.kind !== 'player' && resolvedTarget.kind !== 'vehicle')) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', 'follow requires a person, player, or vehicle target'),
        };
      }
      const targetPos = world.getEntityPosition(resolvedTarget);
      if (!targetPos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_lost', `lost: ${formatTargetRef(resolvedTarget)}`),
        };
      }
      const targetPlace = world.getEntityPlace(resolvedTarget);
      recordObservationRef(state, resolvedTarget, targetPlace);

      const actorPos = world.getActorPosition(actorId);
      const keepRangeSq = step.manner?.kind === 'quietly' ? FOLLOW_QUIET_RANGE_SQ : FOLLOW_NORMAL_RANGE_SQ;
      if (distSq(actorPos, targetPos) > keepRangeSq) {
        const mv = world.moveActorToward(actorId, targetPos, speed, dt, targetPlace);
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
      }
      return (step.until || sustainContinuous) ? TICK_RUNNING : TICK_DONE;
    }

    case 'watch': {
      if (resolvedTarget && resolvedTarget.kind !== 'here' && world.canSee(actorId, resolvedTarget)) {
        recordObservationRef(state, resolvedTarget, world.getEntityPlace(resolvedTarget));
      }
      return (step.until || sustainContinuous) ? TICK_RUNNING : TICK_DONE;
    }

    case 'talk':
    case 'ask':
    case 'tell':
    case 'lie':
    case 'warn':
    case 'distract': {
      if (!resolvedTarget || (resolvedTarget.kind !== 'person' && resolvedTarget.kind !== 'player')) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', `${step.verb} requires a person or player target`),
        };
      }
      const targetId = resolvedTarget.kind === 'player' ? 'player' : resolvedTarget.id;
      const targetPos = world.getEntityPosition(resolvedTarget);
      if (!targetPos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: ${formatTargetRef(resolvedTarget)}`),
        };
      }
      const actorPos = world.getActorPosition(actorId);
      if (distSq(actorPos, targetPos) > TALK_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, targetPos, speed, dt, world.getEntityPlace(resolvedTarget));
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived && distSq(world.getActorPosition(actorId), targetPos) > TALK_RANGE_SQ) {
          return TICK_RUNNING;
        }
      }
      if (!world.canSee(actorId, resolvedTarget)) {
        return {
          kind: 'failed',
          failure: makeFailure(
            step,
            stepIndex,
            'target_not_visible',
            `cannot see ${formatTargetRef(resolvedTarget)}`,
          ),
        };
      }
      const payload = step.verb === 'tell' ? buildReportPayload(state) : undefined;
      const out = world.engagePerson(actorId, targetId, step.verb, step.manner, payload);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'unreachable', out.message ?? `${step.verb} failed`),
        };
      }
      const sustain = sustainContinuous && (step.verb === 'talk' || step.verb === 'distract');
      return (step.until || sustain) ? TICK_RUNNING : TICK_DONE;
    }

    case 'signal': {
      const sigName =
        resolvedTarget && resolvedTarget.kind === 'place'
          ? resolvedTarget.id
          : resolvedTarget && resolvedTarget.kind === 'person'
            ? resolvedTarget.id
            : 'signal';
      world.emitSignal(actorId, sigName);
      return TICK_DONE;
    }

    case 'take':
    case 'carry': {
      if (!resolvedTarget || resolvedTarget.kind !== 'item') {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', `${step.verb} requires an item:<id> target`),
        };
      }
      if (world.hasItem(actorId, resolvedTarget.id)) return TICK_DONE;
      const pos = world.getEntityPosition(resolvedTarget);
      if (!pos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: item:${resolvedTarget.id}`),
        };
      }
      if (distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, pos, speed, dt, world.getEntityPlace(resolvedTarget));
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived && distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
          return TICK_RUNNING;
        }
      }
      const owner = world.getItemOwner(resolvedTarget.id);
      if (owner !== undefined && owner !== actorId) {
        return {
          kind: 'failed',
          failure: makeFailure(
            step,
            stepIndex,
            'blocked_path',
            `cannot take item:${resolvedTarget.id}: owned by ${owner} (use buy or steal)`,
          ),
        };
      }
      const out = world.transferItem(resolvedTarget.id, undefined, actorId);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'missing_item', out.message ?? 'take failed'),
        };
      }
      return TICK_DONE;
    }

    case 'give': {
      const itemId = step.withItem?.id ?? (resolvedTarget?.kind === 'item' ? resolvedTarget.id : undefined);
      if (!itemId) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', 'give requires an item (target or with item:<id>)'),
        };
      }
      if (!world.hasItem(actorId, itemId)) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', `missing: actor does not hold item:${itemId}`),
        };
      }
      const recipientRef: ResolvedTargetRef =
        resolvedTarget && (resolvedTarget.kind === 'person' || resolvedTarget.kind === 'player')
          ? resolvedTarget
          : { kind: 'player' };
      const recipientId = recipientRef.kind === 'player' ? 'player' : recipientRef.id;
      const pos = world.getEntityPosition(recipientRef);
      if (!pos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: ${formatTargetRef(recipientRef)}`),
        };
      }
      if (distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, pos, speed, dt, world.getEntityPlace(recipientRef));
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived && distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
          return TICK_RUNNING;
        }
      }
      const out = world.transferItem(itemId, actorId, recipientId);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'missing_item', out.message ?? 'give failed'),
        };
      }
      return TICK_DONE;
    }

    case 'put': {
      const itemId = step.withItem?.id ?? (resolvedTarget?.kind === 'item' ? resolvedTarget.id : undefined);
      if (!itemId) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', 'put requires an item'),
        };
      }
      if (!world.hasItem(actorId, itemId)) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', `missing: actor does not hold item:${itemId}`),
        };
      }
      let placeId = world.getActorPlace(actorId);
      if (resolvedTarget && resolvedTarget.kind === 'place') {
        const pos = world.getEntityPosition(resolvedTarget);
        if (pos && distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
          const mv = world.moveActorToward(actorId, pos, speed, dt, resolvedTarget.id);
          if (!mv.ok) {
            return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
          }
          if (!mv.arrived) return TICK_RUNNING;
        }
        placeId = resolvedTarget.id;
      }
      const out = world.transferItem(itemId, actorId, undefined, placeId);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'missing_item', out.message ?? 'put failed'),
        };
      }
      return TICK_DONE;
    }

    case 'use': {
      const itemId = step.withItem?.id ?? (resolvedTarget?.kind === 'item' ? resolvedTarget.id : undefined);
      if (!itemId) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', 'use requires an item'),
        };
      }
      if (!world.hasItem(actorId, itemId)) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', `missing: actor does not hold item:${itemId}`),
        };
      }
      const out = world.useItem(actorId, itemId, resolvedTarget);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'invalid_target', out.message ?? 'use failed'),
        };
      }
      return step.until ? TICK_RUNNING : TICK_DONE;
    }

    case 'open':
    case 'close':
    case 'lock':
    case 'unlock': {
      if (!resolvedTarget || resolvedTarget.kind !== 'place') {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', `${step.verb} requires a place:<id> door/gate`),
        };
      }
      const placeId = resolvedTarget.id;
      const pos = world.getEntityPosition(resolvedTarget);
      if (!pos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: place:${placeId}`),
        };
      }
      if (distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, pos, speed, dt, placeId);
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived && distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
          return TICK_RUNNING;
        }
      }

      if (step.verb === 'open') {
        if (world.isLocked(placeId)) {
          return {
            kind: 'failed',
            failure: makeFailure(step, stepIndex, 'blocked_locked', `blocked: place:${placeId} locked`),
          };
        }
        const out = world.setDoorOpen(placeId, true);
        if (!out.ok) {
          return {
            kind: 'failed',
            failure: makeFailure(step, stepIndex, out.code ?? 'blocked_locked', out.message ?? `blocked: place:${placeId}`),
          };
        }
        return TICK_DONE;
      }

      if (step.verb === 'close') {
        const out = world.setDoorOpen(placeId, false);
        if (!out.ok) {
          return {
            kind: 'failed',
            failure: makeFailure(step, stepIndex, out.code ?? 'blocked_path', out.message ?? 'close failed'),
          };
        }
        return TICK_DONE;
      }

      const requiredKey = world.requiredKeyFor(placeId);
      const providedKey = step.withItem?.id ?? (requiredKey && world.hasItem(actorId, requiredKey) ? requiredKey : undefined);
      if (requiredKey) {
        if (!providedKey || !world.hasItem(actorId, providedKey)) {
          return {
            kind: 'failed',
            failure: makeFailure(
              step,
              stepIndex,
              'missing_key',
              `missing_key: place:${placeId} requires item:${requiredKey}`,
            ),
          };
        }
        if (providedKey !== requiredKey && providedKey !== 'lockpick') {
          return {
            kind: 'failed',
            failure: makeFailure(
              step,
              stepIndex,
              'wrong_key',
              `wrong_key: item:${providedKey} does not fit place:${placeId}`,
            ),
          };
        }
      }
      const out = world.setDoorLocked(placeId, step.verb === 'lock', providedKey);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'wrong_key', out.message ?? `${step.verb} failed`),
        };
      }
      return TICK_DONE;
    }

    case 'drive': {
      if (!resolvedTarget || resolvedTarget.kind !== 'vehicle') {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', 'drive requires a vehicle:<id> target'),
        };
      }
      const vehPos = world.getEntityPosition(resolvedTarget);
      if (!vehPos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: vehicle:${resolvedTarget.id}`),
        };
      }
      // Walk to vehicle first if not yet aboard.
      if (distSq(world.getActorPosition(actorId), vehPos) > INTERACT_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, vehPos, speed, dt, world.getEntityPlace(resolvedTarget));
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived && distSq(world.getActorPosition(actorId), vehPos) > INTERACT_RANGE_SQ) {
          return TICK_RUNNING;
        }
      }

      // Determine drive destination: from `until at(...)` if given, otherwise bring to player.
      let destRef: ResolvedTargetRef = { kind: 'player' };
      if (step.until && !step.until.negated && step.until.atom.kind === 'at') {
        const resolvedDest = resolveTarget(step.until.atom.place, world, actorId);
        if (resolvedDest) destRef = resolvedDest;
      }
      const destPos = world.getEntityPosition(destRef);
      if (!destPos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', 'drive destination missing'),
        };
      }
      const destPlace = destRef.kind === 'place' ? destRef.id : world.getEntityPlace(destRef);
      const driveSpeed = BASE_DRIVE_SPEED_MPS * speedMultiplier(step.manner);
      const mv = world.moveVehicleToward(resolvedTarget.id, actorId, destPos, driveSpeed, dt, destPlace);
      if (!mv.ok) {
        return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
      }
      if (!mv.arrived) return TICK_RUNNING;
      return TICK_DONE;
    }

    case 'buy':
    case 'sell': {
      const itemId = step.withItem?.id ?? (resolvedTarget?.kind === 'item' ? resolvedTarget.id : undefined);
      if (!itemId) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', `${step.verb} requires an item:<id>`),
        };
      }
      if (step.verb === 'sell' && !world.hasItem(actorId, itemId)) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'missing_item', `missing: actor does not hold item:${itemId}`),
        };
      }
      if (step.verb === 'buy') {
        const price = world.getItemPrice(itemId);
        if (price !== undefined && world.getActorMoney(actorId) < price) {
          return {
            kind: 'failed',
            failure: makeFailure(
              step,
              stepIndex,
              'insufficient_funds',
              `insufficient_funds: item:${itemId} costs ${price}, actor has ${world.getActorMoney(actorId)}`,
            ),
          };
        }
      }
      const out = world.tradeItem(actorId, itemId, step.verb, resolvedTarget);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'insufficient_funds', out.message ?? `${step.verb} failed`),
        };
      }
      return TICK_DONE;
    }

    case 'steal': {
      if (!resolvedTarget || resolvedTarget.kind !== 'item') {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', 'steal requires an item:<id> target'),
        };
      }
      const pos = world.getEntityPosition(resolvedTarget);
      if (!pos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: item:${resolvedTarget.id}`),
        };
      }
      if (distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, pos, speed, dt, world.getEntityPlace(resolvedTarget));
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived) return TICK_RUNNING;
      }
      const out = world.stealItem(actorId, resolvedTarget.id, step.manner);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'caught', out.message ?? 'steal failed'),
        };
      }
      return TICK_DONE;
    }

    case 'attack':
    case 'restrain': {
      if (!resolvedTarget || (resolvedTarget.kind !== 'person' && resolvedTarget.kind !== 'player')) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'invalid_target', `${step.verb} requires a person target`),
        };
      }
      const targetId = resolvedTarget.kind === 'player' ? 'player' : resolvedTarget.id;
      const pos = world.getEntityPosition(resolvedTarget);
      if (!pos) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, 'target_missing', `missing: ${formatTargetRef(resolvedTarget)}`),
        };
      }
      if (distSq(world.getActorPosition(actorId), pos) > INTERACT_RANGE_SQ) {
        const mv = world.moveActorToward(actorId, pos, speed, dt, world.getEntityPlace(resolvedTarget));
        if (!mv.ok) {
          return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
        }
        if (!mv.arrived) return TICK_RUNNING;
      }
      const out = world.combatAction(actorId, targetId, step.verb, step.withItem?.id);
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'unreachable', out.message ?? `${step.verb} failed`),
        };
      }
      return step.until ? TICK_RUNNING : TICK_DONE;
    }

    case 'flee': {
      const actorPos = world.getActorPosition(actorId);
      const threatRef = resolvedTarget ?? world.findNearest(actorId, 'guard');
      if (!threatRef) return TICK_DONE;
      const threatPos = world.getEntityPosition(threatRef);
      if (!threatPos) return TICK_DONE;
      if (distSq(actorPos, threatPos) >= FLEE_SAFE_DIST_SQ) {
        return step.until ? TICK_RUNNING : TICK_DONE;
      }
      const dx = actorPos.x - threatPos.x;
      const dy = actorPos.y - threatPos.y;
      const len = Math.hypot(dx, dy) || 1;
      const awayDest: Vec2 = { x: actorPos.x + (dx / len) * 10, y: actorPos.y + (dy / len) * 10 };
      const mv = world.moveActorToward(actorId, awayDest, BASE_WALK_SPEED_MPS * 1.5, dt);
      if (!mv.ok) {
        return { kind: 'failed', failure: makeFailure(step, stepIndex, mv.code, mv.message) };
      }
      return TICK_RUNNING;
    }

    case 'call': {
      const out = world.callTarget(actorId, resolvedTarget, buildReportPayload(state));
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'unreachable', out.message ?? 'call failed'),
        };
      }
      return TICK_DONE;
    }

    case 'write': {
      const out = world.writeNote(actorId, resolvedTarget, buildReportPayload(state));
      if (!out.ok) {
        return {
          kind: 'failed',
          failure: makeFailure(step, stepIndex, out.code ?? 'unreachable', out.message ?? 'write failed'),
        };
      }
      return TICK_DONE;
    }
  }
}

/**
 * Advances one actor's plan by `dt` seconds against `world`.
 *
 * Mutates `planState` in-place (keeping it plain JSON) to minimize heap allocations on the
 * hot simulation path. Deterministic for identical inputs.
 */
function writeResult(
  out: StepResult | undefined,
  status: PlanState['status'],
  stageIndex: number,
  failure?: PlanFailure,
): StepResult {
  if (out) {
    out.status = status;
    out.stageIndex = stageIndex;
    out.failure = failure;
    return out;
  }
  return failure !== undefined ? { status, stageIndex, failure } : { status, stageIndex };
}

export function step(
  planState: PlanState,
  world: PlanWorld,
  actor: string,
  dt: number,
  out?: StepResult,
): StepResult {
  if (planState.status !== 'running') {
    return writeResult(out, planState.status, planState.stageIndex, planState.failure);
  }

  planState.ticks++;

  const stages = planState.plan.stages;
  if (planState.stageIndex >= stages.length) {
    planState.status = 'completed';
    return writeResult(out, 'completed', planState.stageIndex);
  }

  const stage = stages[planState.stageIndex]!;
  const steps = planState.plan.steps;
  const statuses = planState.stepStatuses;
  const startedAt = planState.stepStartedAtSec;

  // Phase 1: activate pending steps once when entering a stage (skipped on steady-state ticks).
  if (!planState.stageActive) {
    let hasDriver = false;
    const traits = world.getActorTraits(actor);
    for (let i = stage.start; i < stage.end; i++) {
      const s = steps[i]!;
      if (statuses[i] === 'pending') {
        startedAt[i] = world.timeSec;
        if (s.ifCond && !evaluateCondition(s.ifCond, planState, world, actor, i)) {
          statuses[i] = 'skipped';
          continue;
        }
        const refusal = checkTraitGate(s, i, traits);
        if (refusal) {
          statuses[i] = 'refused';
          planState.status = 'refused';
          planState.failure = refusal;
          return writeResult(out, 'refused', planState.stageIndex, refusal);
        }
        statuses[i] = 'running';
      }
      if (statuses[i] === 'running' && (s.until !== undefined || !isContinuousVerb(s.verb))) {
        hasDriver = true;
      }
    }
    planState.stageActive = true;
    planState.stageHasDriver = hasDriver;
  }

  const hasDriverStep = planState.stageHasDriver;

  // Phase 2: tick every running step in the concurrent `while` stage.
  let anyDriverRunning = false;
  let anyStepRunning = false;

  for (let i = stage.start; i < stage.end; i++) {
    if (statuses[i] !== 'running') continue;
    const s = steps[i]!;

    // If `until` is already satisfied, record final observation for follow/watch and finish cleanly.
    if (s.until !== undefined && evaluateCondition(s.until, planState, world, actor, i)) {
      if ((s.verb === 'follow' || s.verb === 'watch') && s.target) {
        const resolved = resolveTarget(s.target, world, actor);
        if (resolved && resolved.kind !== 'here') {
          recordObservationRef(planState, resolved, world.getEntityPlace(resolved));
        }
      }
      statuses[i] = 'done';
      continue;
    }

    const sustainContinuous = hasDriverStep && s.until === undefined && isContinuousVerb(s.verb);
    const tick = executeSingleStep(s, i, planState, world, actor, dt, sustainContinuous);
    if (tick.kind === 'failed') {
      statuses[i] = 'failed';
      planState.status = 'failed';
      planState.failure = tick.failure;
      return writeResult(out, 'failed', planState.stageIndex, tick.failure);
    }

    if (tick.kind === 'done') {
      statuses[i] = 'done';
    } else if (
      s.until !== undefined &&
      s.verb !== 'wait' &&
      s.verb !== 'watch' &&
      evaluateCondition(s.until, planState, world, actor, i)
    ) {
      statuses[i] = 'done';
    } else {
      anyStepRunning = true;
      if (s.until !== undefined || !isContinuousVerb(s.verb)) {
        anyDriverRunning = true;
      }
    }
  }

  // Stage completes when:
  // - no step is running, OR
  // - the stage had driver steps (finite or `until`-bounded) and all drivers finished (companion
  //   unbounded `while` actions like `while watch person:guard_1` stop cleanly alongside them).
  if (!anyStepRunning || (hasDriverStep && !anyDriverRunning)) {
    for (let i = stage.start; i < stage.end; i++) {
      if (statuses[i] === 'running') statuses[i] = 'done';
    }
    planState.stageIndex++;
    planState.stageActive = false;
    planState.stageHasDriver = false;
    planState.stageStartedAtSec = world.timeSec;
    if (planState.stageIndex >= stages.length) {
      planState.status = 'completed';
    }
  }

  return writeResult(out, planState.status, planState.stageIndex, planState.failure);
}
