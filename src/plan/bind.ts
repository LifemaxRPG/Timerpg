import {
  formatTargetRef,
  type BindError,
  type BindResult,
  type Condition,
  type ItemRef,
  type Plan,
  type PlanFacts,
  type PlanStep,
  type TargetRef,
} from './types';

function stripPrefix(id: string, prefix: string): string {
  return id.startsWith(prefix) ? id.slice(prefix.length) : id;
}

function formatKnownList(prefix: string, items: readonly string[]): string {
  if (items.length === 0) return '(none)';
  return items.map((id) => (prefix ? `${prefix}${id}` : id)).join(', ');
}

/** Verbs that require the actor to hold an item when `target` is an `item:<id>`. */
const VERBS_REQUIRING_HELD_TARGET_ITEM = new Set(['give', 'put', 'use', 'sell']);

/** Verbs that acquire their `target` item into the actor's inventory. */
const VERBS_ACQUIRING_TARGET_ITEM = new Set(['take', 'buy', 'steal', 'carry']);

/** Verbs that relinquish the `with` or `target` item from the actor's inventory. */
const VERBS_RELINQUISHING_ITEM = new Set(['give', 'put', 'sell']);

/**
 * Resolves and checks every id in `plan` against the `facts` given to the model.
 *
 * Rejects unknown people, places, items, vehicles, disallowed `nearest(<kind>)` / `heard(<signal>)`,
 * out-of-range `done(n)`, and items the actor neither currently holds nor acquires in an earlier
 * sequential (`then`) step. Every error contains a model-readable reason listing the known ids.
 */
export function bind(plan: Plan, facts: PlanFacts): BindResult {
  const people = new Set(facts.knownPeople.map((id) => stripPrefix(id, 'person:')));
  const places = new Set(facts.knownPlaces.map((id) => stripPrefix(id, 'place:')));
  const items = new Set(facts.knownItems.map((id) => stripPrefix(id, 'item:')));
  const held = new Set(facts.heldItems.map((id) => stripPrefix(id, 'item:')));
  for (const h of held) items.add(h);
  const vehicles = new Set(facts.knownVehicles.map((id) => stripPrefix(id, 'vehicle:')));
  const kinds = facts.knownKinds ? new Set(facts.knownKinds) : undefined;
  const signals = facts.knownSignals ? new Set(facts.knownSignals) : undefined;

  const knownPeopleSorted = Array.from(people).sort();
  const knownPlacesSorted = Array.from(places).sort();
  const knownItemsSorted = Array.from(items).sort();
  const knownVehiclesSorted = Array.from(vehicles).sort();
  const knownKindsSorted = kinds ? Array.from(kinds).sort() : [];
  const knownSignalsSorted = signals ? Array.from(signals).sort() : [];

  const errors: BindError[] = [];

  function checkTarget(ref: TargetRef, stepIndex: number, field: BindError['field']): void {
    switch (ref.kind) {
      case 'person':
        if (!people.has(ref.id)) {
          errors.push({
            stepIndex,
            field,
            ref: formatTargetRef(ref),
            code: 'unknown_person',
            reason: `person:${ref.id} unknown; known people: ${formatKnownList('person:', knownPeopleSorted)}`,
          });
        }
        break;
      case 'place':
        if (!places.has(ref.id)) {
          errors.push({
            stepIndex,
            field,
            ref: formatTargetRef(ref),
            code: 'unknown_place',
            reason: `place:${ref.id} unknown; known places: ${formatKnownList('place:', knownPlacesSorted)}`,
          });
        }
        break;
      case 'item':
        if (!items.has(ref.id)) {
          errors.push({
            stepIndex,
            field,
            ref: formatTargetRef(ref),
            code: 'unknown_item',
            reason: `item:${ref.id} unknown; known items: ${formatKnownList('item:', knownItemsSorted)}`,
          });
        }
        break;
      case 'vehicle':
        if (!vehicles.has(ref.id)) {
          errors.push({
            stepIndex,
            field,
            ref: formatTargetRef(ref),
            code: 'unknown_vehicle',
            reason: `vehicle:${ref.id} unknown; known vehicles: ${formatKnownList('vehicle:', knownVehiclesSorted)}`,
          });
        }
        break;
      case 'nearest':
        if (kinds && !kinds.has(ref.entityKind)) {
          errors.push({
            stepIndex,
            field,
            ref: formatTargetRef(ref),
            code: 'unknown_kind',
            reason: `nearest(${ref.entityKind}) unknown kind; known kinds: ${formatKnownList('', knownKindsSorted)}`,
          });
        }
        break;
      case 'player':
      case 'here':
        break;
    }
  }

  function checkCondition(cond: Condition, stepIndex: number, field: 'until' | 'if'): void {
    const atom = cond.atom;
    switch (atom.kind) {
      case 'sees':
        checkTarget(atom.target, stepIndex, field);
        break;
      case 'at':
        if (atom.subject) checkTarget(atom.subject, stepIndex, field);
        checkTarget(atom.place, stepIndex, field);
        break;
      case 'has':
        checkTarget(atom.item, stepIndex, field);
        break;
      case 'heard':
        if (signals && !signals.has(atom.signal)) {
          errors.push({
            stepIndex,
            field,
            ref: `heard(${atom.signal})`,
            code: 'unknown_signal',
            reason: `signal '${atom.signal}' unknown; known signals: ${formatKnownList('', knownSignalsSorted)}`,
          });
        }
        break;
      case 'done':
        if (atom.stepNumber < 1 || atom.stepNumber > plan.steps.length) {
          errors.push({
            stepIndex,
            field,
            ref: `done(${atom.stepNumber})`,
            code: 'invalid_step_ref',
            reason: `done(${atom.stepNumber}) out of range; plan has ${plan.steps.length} step(s) (1..${plan.steps.length})`,
          });
        }
        break;
      case 'time':
      case 'alone':
        break;
    }
  }

  // Track projected held items stage by stage (`then` propagates acquisitions; `while` runs concurrently).
  const projectedHeld = new Set<string>(held);

  function checkHeldItem(item: ItemRef, stepIndex: number, field: 'target' | 'with'): void {
    if (!items.has(item.id)) return; // Already reported as unknown_item
    if (!projectedHeld.has(item.id)) {
      const heldList = Array.from(projectedHeld).sort();
      errors.push({
        stepIndex,
        field,
        ref: `item:${item.id}`,
        code: 'item_not_held',
        reason: `item:${item.id} not held by actor; held items: ${formatKnownList('item:', heldList)} (acquire it first)`,
      });
    }
  }

  for (const stage of plan.stages) {
    const acquiredInStage: string[] = [];
    const relinquishedInStage: string[] = [];

    for (let i = stage.start; i < stage.end; i++) {
      const step: PlanStep = plan.steps[i]!;
      const stepIndex = i;

      if (step.target) {
        checkTarget(step.target, stepIndex, 'target');
        if (step.target.kind === 'item' && VERBS_REQUIRING_HELD_TARGET_ITEM.has(step.verb)) {
          checkHeldItem(step.target, stepIndex, 'target');
        }
      }

      if (step.withItem) {
        checkTarget(step.withItem, stepIndex, 'with');
        checkHeldItem(step.withItem, stepIndex, 'with');
      }

      if (step.until) checkCondition(step.until, stepIndex, 'until');
      if (step.ifCond) checkCondition(step.ifCond, stepIndex, 'if');

      // Record inventory transitions for subsequent `then` stages.
      if (step.target?.kind === 'item' && VERBS_ACQUIRING_TARGET_ITEM.has(step.verb)) {
        if (items.has(step.target.id)) acquiredInStage.push(step.target.id);
      }
      if (VERBS_RELINQUISHING_ITEM.has(step.verb)) {
        if (step.withItem) relinquishedInStage.push(step.withItem.id);
        else if (step.target?.kind === 'item') relinquishedInStage.push(step.target.id);
      }
    }

    for (const id of acquiredInStage) projectedHeld.add(id);
    for (const id of relinquishedInStage) projectedHeld.delete(id);
  }

  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return {
    ok: true,
    bound: {
      plan,
      actorId: facts.actorId,
    },
  };
}
