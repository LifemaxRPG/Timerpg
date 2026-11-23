import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { describe, expect, it } from 'vitest';
import {
  bind,
  compileGbnf,
  createCompoundStubWorld,
  createPlanState,
  DEFAULT_TRAITS,
  matchesGbnf,
  parsePlan,
  PLAN_VERBS,
  step,
  StubWorld,
  type PlanState,
} from './index';

const HERE = dirname(fileURLToPath(import.meta.url));
const GBNF_SOURCE = readFileSync(join(HERE, 'plan.gbnf'), 'utf8');
const COMPILED_GBNF = compileGbnf(GBNF_SOURCE);

function compileAndStart(
  world: StubWorld,
  actorId: string,
  planText: string,
): PlanState {
  const parsed = parsePlan(planText);
  if (!parsed.ok) throw new Error(`parse failed: ${parsed.error} at ${parsed.offset}`);
  const bound = bind(parsed.plan, world.factsFor(actorId));
  if (!bound.ok) {
    throw new Error(`bind failed: ${bound.errors.map((e) => e.reason).join('; ')}`);
  }
  return createPlanState(bound.bound, world.timeSec);
}

describe('Plan language: parser & GBNF grammar agreement', () => {
  const VALID_SAMPLES: readonly string[] = [
    // Every verb and clause shape
    'wait',
    'go place:front_gate',
    'go here',
    'go player quietly',
    'go nearest(exit) quickly',
    'follow person:guard_2 quietly until at(person:guard_2, place:office)',
    'wait until heard(signal)',
    'wait until time(08:30)',
    'wait until time(23:59)',
    'wait until time(00:00)',
    'watch person:guard_1 carefully until alone',
    'hide place:back_alley quietly until not sees(person:guard_1)',
    'talk person:guard_1 politely',
    'ask person:guard_1 politely if sees(person:guard_1)',
    'tell player quietly',
    'lie person:guard_1 as(delivery man)',
    'warn player quickly',
    'distract person:guard_1 politely until at(player, place:back_alley)',
    'signal player',
    'take item:storage_key carefully',
    'give player with item:ledger politely',
    'put place:office with item:ledger quietly',
    'use place:storage_door with item:lockpick carefully',
    'open place:back_gate quietly',
    'close place:storage_door',
    'lock place:back_gate with item:gate_key',
    'unlock place:back_gate with item:gate_key carefully',
    'carry item:ledger quickly',
    'drive vehicle:van until at(place:back_gate)',
    'buy item:coffee politely',
    'sell item:watch rudely',
    'steal item:ledger quietly if alone',
    'attack person:guard_1 with item:bat rudely',
    'restrain person:guard_1 quickly',
    'flee nearest(guard) quickly until not sees(nearest(guard))',
    'call person:police quietly',
    'write place:office carefully',
    // Combinators (`then`, `while`, `done(n)`, multi-line, `at(here)`, `has(...)`)
    'distract person:guard_1 politely while watch person:guard_1 until at(player, place:back_alley)',
    'wait until heard(whistle) then unlock place:back_gate with item:gate_key then open place:back_gate then drive vehicle:van until at(place:back_gate)',
    'go place:office\nthen take item:storage_key\nthen unlock place:storage_door with item:storage_key if has(item:storage_key)',
    'watch person:guard_1 while hide until done(1)',
    'go place:courtyard until at(here) if not alone',
    'wait until at(vehicle:van, place:back_gate)',
    'wait until at(nearest(guard), nearest(exit))',
    'wait until at(player,place:back_alley)',
    'go place:a   then   wait until alone if alone',
  ];

  const INVALID_SAMPLES: readonly string[] = [
    '',
    '   ',
    ' go place:front_gate',
    'go place:front_gate ',
    'teleport place:front_gate',
    'fly',
    'goplace:front_gate',
    'go front_gate',
    'go person:Guard_1',
    'go person:1guard',
    'go person:',
    'go place:',
    'go item:',
    'go vehicle:',
    'go nearest()',
    'go nearest(Guard)',
    'unlock place:gate with person:guard_1',
    'unlock place:gate with gate_key',
    'talk person:guard_1 as()',
    'talk person:guard_1 as(Delivery Man)',
    'talk person:guard_1 as(delivery  man)',
    'talk person:guard_1 as(delivery man )',
    'wait until time(24:00)',
    'wait until time(12:60)',
    'wait until time(8:05)',
    'wait until done(0)',
    'wait until done(-1)',
    'wait until done(step_1)',
    'wait until at(item:key_1)',
    'wait until at(place:office, place:courtyard)',
    'wait until has(person:guard_1)',
    'wait until heard()',
    'wait until sees()',
    'wait until not',
    'go place:office quietly with item:key_1',
    'wait if alone until heard(signal)',
    'then go place:office',
    'go place:office then',
    'go place:office while',
    'go place:office and wait',
    'go place:office then then wait',
    'go place:office; wait',
    'wait until not not alone',
    'wait until time(29:00)',
  ];

  it('covers all 30 verbs in valid samples', () => {
    expect(PLAN_VERBS.length).toBe(30);
    const seen = new Set<string>();
    for (const sample of VALID_SAMPLES) {
      const res = parsePlan(sample);
      expect(res.ok, `expected valid sample to parse: "${sample}"`).toBe(true);
      if (res.ok) {
        for (const s of res.plan.steps) seen.add(s.verb);
      }
    }
    expect(seen.size).toBe(PLAN_VERBS.length);
  });

  it('parser and plan.gbnf agree on all valid samples (40+)', () => {
    expect(VALID_SAMPLES.length).toBeGreaterThanOrEqual(30);
    for (const sample of VALID_SAMPLES) {
      const parsed = parsePlan(sample);
      const gbnfOk = matchesGbnf(COMPILED_GBNF, sample);
      expect(parsed.ok, `TS parser rejected valid sample: "${sample}"`).toBe(true);
      expect(gbnfOk, `plan.gbnf rejected valid sample: "${sample}"`).toBe(true);
    }
  });

  it('parser and plan.gbnf agree on all invalid samples (40+)', () => {
    expect(INVALID_SAMPLES.length).toBeGreaterThanOrEqual(30);
    for (const sample of INVALID_SAMPLES) {
      const parsed = parsePlan(sample);
      const gbnfOk = matchesGbnf(COMPILED_GBNF, sample);
      expect(parsed.ok, `TS parser accepted invalid sample: "${sample}"`).toBe(false);
      expect(gbnfOk, `plan.gbnf accepted invalid sample: "${sample}"`).toBe(false);
    }
  });
});

describe('Plan language: name binding & inventory checking', () => {
  const baseFacts = {
    actorId: 'companion',
    knownPeople: ['guard_1', 'guard_2'],
    knownPlaces: ['front_gate', 'courtyard', 'office', 'storage_door', 'storage', 'back_gate'],
    knownItems: ['gate_key', 'storage_key', 'ledger'],
    heldItems: ['gate_key'],
    knownVehicles: ['van'],
    knownKinds: ['guard', 'door', 'key', 'van', 'exit'],
    knownSignals: ['signal', 'whistle'],
  };

  it('catches unknown person, place, item, vehicle, kind, signal, and done(n)', () => {
    const cases: Array<{ plan: string; code: string; substr: string }> = [
      { plan: 'talk person:guard_9', code: 'unknown_person', substr: 'person:guard_9 unknown; known people:' },
      { plan: 'go place:roof_7', code: 'unknown_place', substr: 'place:roof_7 unknown; known places:' },
      { plan: 'take item:key_7', code: 'unknown_item', substr: 'item:key_7 unknown; known items:' },
      { plan: 'drive vehicle:truck_2', code: 'unknown_vehicle', substr: 'vehicle:truck_2 unknown; known vehicles:' },
      { plan: 'go nearest(helicopter)', code: 'unknown_kind', substr: 'nearest(helicopter) unknown kind' },
      { plan: 'wait until heard(siren)', code: 'unknown_signal', substr: "signal 'siren' unknown" },
      { plan: 'wait until done(5)', code: 'invalid_step_ref', substr: 'done(5) out of range' },
      { plan: 'wait until sees(person:ghost_1)', code: 'unknown_person', substr: 'person:ghost_1 unknown' },
      { plan: 'wait if at(place:moon)', code: 'unknown_place', substr: 'place:moon unknown' },
    ];

    for (const c of cases) {
      const parsed = parsePlan(c.plan);
      expect(parsed.ok).toBe(true);
      if (!parsed.ok) continue;
      const res = bind(parsed.plan, baseFacts);
      expect(res.ok, `expected bind failure for "${c.plan}"`).toBe(false);
      if (!res.ok) {
        expect(res.errors.some((e) => e.code === c.code && e.reason.includes(c.substr))).toBe(true);
      }
    }
  });

  it('rejects using an item not held unless acquired in an earlier then-step', () => {
    // Using storage_key without taking it first must be rejected with clear reason
    const bad = parsePlan('unlock place:storage_door with item:storage_key');
    expect(bad.ok).toBe(true);
    if (bad.ok) {
      const res = bind(bad.plan, baseFacts);
      expect(res.ok).toBe(false);
      if (!res.ok) {
        expect(res.errors[0]!.code).toBe('item_not_held');
        expect(res.errors[0]!.reason).toContain('item:storage_key not held');
        expect(res.errors[0]!.reason).toContain('item:gate_key');
      }
    }

    // Taking storage_key first, then unlocking with it, must bind cleanly
    const good = parsePlan(
      'go place:office then take item:storage_key then go place:storage_door then unlock place:storage_door with item:storage_key',
    );
    expect(good.ok).toBe(true);
    if (good.ok) {
      const res = bind(good.plan, baseFacts);
      expect(res.ok).toBe(true);
    }

    // Dropping gate_key then trying to unlock with it must be rejected
    const dropped = parsePlan('put place:courtyard with item:gate_key then unlock place:back_gate with item:gate_key');
    expect(dropped.ok).toBe(true);
    if (dropped.ok) {
      const res = bind(dropped.plan, baseFacts);
      expect(res.ok).toBe(false);
      if (!res.ok) expect(res.errors[0]!.code).toBe('item_not_held');
    }
  });
});

describe('Plan execution: the 5 required scenarios', () => {
  it('Scenario 1: keep the guard talking while I go round the back', () => {
    const world = createCompoundStubWorld();
    const state = compileAndStart(
      world,
      'companion',
      'distract person:guard_1 politely while watch person:guard_1 until at(player, place:back_alley)',
    );

    const guard1 = world.actors.get('guard_1')!;
    const initialGuardX = guard1.x;

    // Tick 0..9: companion keeps guard_1 distracted at front_gate while player walks via parking_lot -> back_alley
    for (let t = 0; t < 10; t++) {
      const res = step(state, world, 'companion', 0.5);
      world.advance(0.5);
      expect(res.status).toBe('running');
      // Guard 1 stays pinned at front_gate instead of walking off to courtyard
      expect(guard1.placeId).toBe('front_gate');
      expect(Math.abs(guard1.x - initialGuardX)).toBeLessThan(0.5);
      if (t === 5) {
        expect(world.movePlayerTo('parking_lot')).toBe(true);
      }
    }

    // Player arrives at back_alley; companion finishes the `while` stage cleanly
    expect(world.movePlayerTo('back_alley')).toBe(true);
    world.advance(0.5);
    const done = step(state, world, 'companion', 0.5);
    expect(done.status).toBe('completed');
    expect(state.stepStatuses).toEqual(['done', 'done']);
  });

  it('Scenario 2: when I signal, unlock the gate and bring the van', () => {
    const world = createCompoundStubWorld();
    // Place companion in courtyard near back_gate; player sneaks round to back_alley
    const comp = world.actors.get('companion')!;
    comp.x = 10;
    comp.y = 4;
    comp.placeId = 'courtyard';
    world.movePlayerTo('parking_lot');
    world.movePlayerTo('back_alley');

    const state = compileAndStart(
      world,
      'companion',
      'wait until heard(signal) then go place:back_gate then unlock place:back_gate with item:gate_key then open place:back_gate then drive vehicle:van until at(place:back_gate)',
    );

    // Before player signals, companion waits patiently and gate stays locked
    for (let i = 0; i < 6; i++) {
      world.advance(0.5);
      expect(step(state, world, 'companion', 0.5).status).toBe('running');
    }
    expect(state.stageIndex).toBe(0);
    expect(world.isLocked('back_gate')).toBe(true);

    // Player signals!
    world.emitSignal('player', 'signal');

    let guardSteps = 0;
    while (state.status === 'running' && guardSteps < 80) {
      world.advance(0.5);
      step(state, world, 'companion', 0.5);
      guardSteps++;
    }

    expect(state.status).toBe('completed');
    expect(world.isLocked('back_gate')).toBe(false);
    expect(world.isOpen('back_gate')).toBe(true);
    expect(world.vehicles.get('van')!.placeId).toBe('back_gate');
    expect(world.actors.get('companion')!.placeId).toBe('back_gate');
  });

  it('Scenario 3: follow him quietly and tell me where he goes', () => {
    const world = createCompoundStubWorld();
    const comp = world.actors.get('companion')!;
    comp.x = 8;
    comp.y = 0;
    comp.placeId = 'courtyard';

    const state = compileAndStart(
      world,
      'companion',
      'follow person:guard_2 quietly until at(person:guard_2, place:back_gate) then go player quietly then tell player',
    );

    let ticks = 0;
    while (state.status === 'running' && ticks < 160) {
      world.advance(0.5);
      step(state, world, 'companion', 0.5);
      ticks++;
    }

    expect(state.status).toBe('completed');
    expect(comp.hidden).toBe(true);
    expect(state.observedPlaces).toContain('courtyard');
    expect(state.observedPlaces).toContain('office');
    expect(state.observedPlaces).toContain('back_gate');

    const tellLog = world.log.find((e) => e.verb === 'tell' && e.target === 'player');
    expect(tellLog).toBeDefined();
    expect(tellLog!.payload).toContain('person:guard_2 went:');
    expect(tellLog!.payload).toContain('office');
    expect(tellLog!.payload).toContain('back_gate');
  });

  it('Scenario 4: plan fails on locked door and replan finds the key first and succeeds', () => {
    const world = createCompoundStubWorld();
    const comp = world.actors.get('companion')!;
    comp.x = 12;
    comp.y = 0;
    comp.placeId = 'courtyard';

    // First attempt: tries to open locked storage_door without the key
    const firstState = compileAndStart(
      world,
      'companion',
      'go place:storage_door then open place:storage_door then go place:storage then take item:ledger',
    );

    let ticks = 0;
    while (firstState.status === 'running' && ticks < 40) {
      world.advance(0.5);
      step(firstState, world, 'companion', 0.5);
      ticks++;
    }

    expect(firstState.status).toBe('failed');
    expect(firstState.failure?.code).toBe('blocked_locked');
    expect(firstState.failure?.message).toBe('blocked: place:storage_door locked');
    expect(world.hasItem('companion', 'ledger')).toBe(false);

    // Replan: go fetch storage_key from office first, then unlock, open, and take ledger
    const replanState = compileAndStart(
      world,
      'companion',
      'go place:office then take item:storage_key then go place:storage_door then unlock place:storage_door with item:storage_key then open place:storage_door then go place:storage then take item:ledger',
    );

    ticks = 0;
    while (replanState.status === 'running' && ticks < 120) {
      world.advance(0.5);
      step(replanState, world, 'companion', 0.5);
      ticks++;
    }

    expect(replanState.status).toBe('completed');
    expect(world.isLocked('storage_door')).toBe(false);
    expect(world.isOpen('storage_door')).toBe(true);
    expect(world.hasItem('companion', 'storage_key')).toBe(true);
    expect(world.hasItem('companion', 'ledger')).toBe(true);
  });

  it('Scenario 5: trait gate refuses steps that violate actor traits', () => {
    // Gentle companion refuses attack
    const gentleWorld = createCompoundStubWorld({ gentle: 0.9 });
    const attackPlan = compileAndStart(gentleWorld, 'companion', 'attack person:guard_1');
    const r1 = step(attackPlan, gentleWorld, 'companion', 0.5);
    expect(r1.status).toBe('refused');
    expect(r1.failure?.code).toBe('trait_refused');
    expect(r1.failure?.trait).toBe('gentle');
    expect(r1.failure?.message).toContain('too gentle to attack');
    expect(gentleWorld.actors.get('guard_1')!.hp).toBe(100);

    // Honest companion refuses lie
    const honestWorld = createCompoundStubWorld({ honest: 0.92 });
    const liePlan = compileAndStart(honestWorld, 'companion', 'lie person:guard_1 as(inspector)');
    const r2 = step(liePlan, honestWorld, 'companion', 0.5);
    expect(r2.status).toBe('refused');
    expect(r2.failure?.trait).toBe('honest');

    // Lawful companion refuses steal
    const lawfulWorld = createCompoundStubWorld({ lawful: 0.88 });
    const stealPlan = compileAndStart(lawfulWorld, 'companion', 'steal item:ledger quietly');
    const r3 = step(stealPlan, lawfulWorld, 'companion', 0.5);
    expect(r3.status).toBe('refused');
    expect(r3.failure?.trait).toBe('lawful');

    // Ruthless companion (gentle: 0.15) executes attack without refusal
    const toughWorld = createCompoundStubWorld({ gentle: 0.15 });
    const toughPlan = compileAndStart(toughWorld, 'companion', 'attack person:guard_1');
    let ticks = 0;
    while (toughPlan.status === 'running' && ticks < 20) {
      toughWorld.advance(0.5);
      step(toughPlan, toughWorld, 'companion', 0.5);
      ticks++;
    }
    expect(toughPlan.status).toBe('completed');
    expect(toughWorld.actors.get('guard_1')!.hp).toBeLessThan(100);
  });
});

describe('Plan language: determinism, JSON saves, and performance', () => {
  it('replays deterministically and survives JSON save/load mid-plan', () => {
    const planText =
      'follow person:guard_2 quietly until at(person:guard_2, place:back_gate) then go player quietly then tell player';

    // Run A: uninterrupted
    const worldA = createCompoundStubWorld();
    worldA.actors.get('companion')!.x = 8;
    worldA.actors.get('companion')!.placeId = 'courtyard';
    const stateA = compileAndStart(worldA, 'companion', planText);
    for (let i = 0; i < 120 && stateA.status === 'running'; i++) {
      worldA.advance(0.5);
      step(stateA, worldA, 'companion', 0.5);
    }

    // Run B: serialize PlanState to JSON at tick 15, reload via JSON.parse, and resume
    const worldB = createCompoundStubWorld();
    worldB.actors.get('companion')!.x = 8;
    worldB.actors.get('companion')!.placeId = 'courtyard';
    let stateB = compileAndStart(worldB, 'companion', planText);
    for (let i = 0; i < 120 && stateB.status === 'running'; i++) {
      if (i === 15) {
        stateB = JSON.parse(JSON.stringify(stateB)) as PlanState;
      }
      worldB.advance(0.5);
      step(stateB, worldB, 'companion', 0.5);
    }

    expect(stateA.status).toBe('completed');
    expect(stateB).toEqual(stateA);
    expect(worldB.log).toEqual(worldA.log);
    expect(worldB.actors.get('companion')).toEqual(worldA.actors.get('companion'));
  });

  it('steps 50 actors in well under 0.05 ms per 50-actor tick', () => {
    const world = createCompoundStubWorld();
    const ACTOR_COUNT = 50;
    const states: PlanState[] = [];
    const actorIds: string[] = [];

    const parsedGo = parsePlan(
      'go place:courtyard then wait until time(23:59) while watch person:guard_1',
    );
    expect(parsedGo.ok).toBe(true);
    if (!parsedGo.ok) return;

    for (let i = 0; i < ACTOR_COUNT; i++) {
      const id = `npc_${i}`;
      actorIds.push(id);
      world.addActor({
        id,
        kind: 'npc',
        x: (i % 10) * 0.5,
        y: Math.floor(i / 10) * 0.5,
        placeId: 'front_gate',
        inventory: [],
        money: 10,
        hidden: false,
        traits: { ...DEFAULT_TRAITS },
        distractedUntilSec: 0,
        patrolIndex: 0,
        patrolSpeedMps: 0,
        hp: 100,
      });
      const bound = bind(parsedGo.plan, world.factsFor(id));
      expect(bound.ok).toBe(true);
      if (bound.ok) {
        states.push(createPlanState(bound.bound, world.timeSec));
      }
    }

    const scratch = { status: 'running' as const, stageIndex: 0 };
    // Warm up V8 JIT
    for (let t = 0; t < 200; t++) {
      for (let i = 0; i < ACTOR_COUNT; i++) {
        step(states[i]!, world, actorIds[i]!, 0.1, scratch);
      }
    }

    // Measure median across 3 rounds of 1,000 ticks x 50 actors (150,000 actor-steps)
    const TICKS = 1000;
    const rounds: number[] = [];
    for (let r = 0; r < 3; r++) {
      const t0 = performance.now();
      for (let t = 0; t < TICKS; t++) {
        for (let i = 0; i < ACTOR_COUNT; i++) {
          step(states[i]!, world, actorIds[i]!, 0.1, scratch);
        }
      }
      rounds.push((performance.now() - t0) / TICKS);
    }
    rounds.sort((a, b) => a - b);
    const msPer50ActorStep = rounds[1]!;

    // G007 target: under 0.05 ms per step for 50 actors
    expect(msPer50ActorStep).toBeLessThan(0.05);
  });
});

describe('Plan execution: conditions, failure codes, and verb coverage', () => {
  it('blocks direct movement through locked doors and catches wrong keys', () => {
    const world = createCompoundStubWorld();
    const comp = world.actors.get('companion')!;
    comp.x = 10;
    comp.y = 0;
    comp.placeId = 'courtyard';

    // Direct `go place:storage` through locked storage_door fails with blocked_locked
    const blocked = compileAndStart(world, 'companion', 'go place:storage');
    const r1 = step(blocked, world, 'companion', 0.5);
    expect(r1.status).toBe('failed');
    expect(r1.failure?.code).toBe('blocked_locked');
    expect(r1.failure?.message).toBe('blocked: place:storage_door locked');

    // Trying to unlock storage_door with the wrong key (gate_key) fails with wrong_key
    const wrongKey = compileAndStart(
      world,
      'companion',
      'go place:storage_door then unlock place:storage_door with item:gate_key',
    );
    let ticks = 0;
    while (wrongKey.status === 'running' && ticks < 40) {
      world.advance(0.5);
      step(wrongKey, world, 'companion', 0.5);
      ticks++;
    }
    expect(wrongKey.status).toBe('failed');
    expect(wrongKey.failure?.code).toBe('wrong_key');
  });

  it('evaluates if-guards, time(), done(), nearest(), and not conditions', () => {
    const world = createCompoundStubWorld();
    // 08:00:00 -> step 1 skipped because `if has(item:storage_key)` is false;
    // step 2 runs because `if not has(item:storage_key)` is true;
    // step 3 waits until time(08:01) while step 4 watches nearest(guard) until done(3).
    const state = compileAndStart(
      world,
      'companion',
      'signal player if has(item:storage_key) then warn player if not has(item:storage_key) then wait until time(08:01) while watch nearest(guard) until done(3)',
    );

    step(state, world, 'companion', 0.5);
    expect(state.stepStatuses[0]).toBe('skipped');
    expect(world.signals.length).toBe(0);

    step(state, world, 'companion', 0.5);
    expect(state.stepStatuses[1]).toBe('done');
    expect(world.log.some((e) => e.verb === 'warn' && e.target === 'player')).toBe(true);

    // Still waiting at 08:00:30
    world.timeSec = 8 * 3600 + 30;
    expect(step(state, world, 'companion', 0.5).status).toBe('running');

    // Cross 08:01:00 -> both wait and watch finish cleanly
    world.timeSec = 8 * 3600 + 60;
    expect(step(state, world, 'companion', 0.5).status).toBe('completed');
    expect(state.stepStatuses).toEqual(['skipped', 'done', 'done', 'done']);
  });

  it('exercises commerce, stealth theft, disguise, lock/close, flee, call, write, and restrain', () => {
    const world = createCompoundStubWorld({ gentle: 0.2, honest: 0.2, lawful: 0.2 });
    world.addItem({
      id: 'coffee',
      kind: 'item',
      placeId: 'front_gate',
      x: 1,
      y: 0,
      price: 15,
    });
    world.addItem({
      id: 'watch',
      kind: 'item',
      placeId: 'front_gate',
      ownerId: 'guard_1',
      x: 2,
      y: 0,
      price: 200,
    });

    // Insufficient funds check
    const broke = compileAndStart(world, 'companion', 'buy item:watch');
    expect(step(broke, world, 'companion', 0.5).failure?.code).toBe('insufficient_funds');

    // Buy coffee, disguise as delivery man, lie, ask, call, write, give coffee to player
    const chain = compileAndStart(
      world,
      'companion',
      'buy item:coffee politely then lie person:guard_1 as(delivery man) then ask person:guard_1 politely then call person:guard_2 then write place:front_gate then give player with item:coffee',
    );
    let ticks = 0;
    while (chain.status === 'running' && ticks < 40) {
      step(chain, world, 'companion', 0.5);
      world.advance(0.5);
      ticks++;
    }
    expect(chain.status).toBe('completed');
    expect(world.actors.get('companion')!.role).toBe('delivery man');
    expect(world.actors.get('companion')!.money).toBe(35);
    expect(world.hasItem('player', 'coffee')).toBe(true);
    expect(world.log.some((e) => e.verb === 'call')).toBe(true);
    expect(world.log.some((e) => e.verb === 'write')).toBe(true);

    // Quiet steal succeeds; restrain pins guard_1; flee moves companion away
    const heist = compileAndStart(
      world,
      'companion',
      'steal item:watch quietly then restrain person:guard_1 quickly then flee person:guard_1 quickly',
    );
    ticks = 0;
    while (heist.status === 'running' && ticks < 80) {
      step(heist, world, 'companion', 0.5);
      world.advance(0.5);
      ticks++;
    }
    expect(heist.status).toBe('completed');
    expect(world.hasItem('companion', 'watch')).toBe(true);
    expect(world.actors.get('guard_1')!.restrained).toBe(true);
  });
});
