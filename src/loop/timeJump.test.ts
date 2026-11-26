import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TIME_MACHINE_FUEL,
  act,
  advance,
  arrest,
  attitude,
  create,
  jump,
  load,
  policeView,
  save,
  timeMachineView,
} from './index';
import { TimerpgSim, simCall } from './bridge';
import { CAPITAL_IDS, CAPITAL_JURISDICTION, buildStubCapital } from './stubCapital';
import { clearTimelineCache } from '../timeline';

describe('G020: Time machine in the playable loop', () => {
  it('burns a store in 1952, jumps to 2025: the lot is changed and the Gazette remembers', () => {
    clearTimelineCache();
    // Start in 2025 and jump back to 1952 to stand in the real 1952 capital from presentAt
    let sim = create({
      ...buildStubCapital({ seed: 'g020-arson-seed', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
    });

    const to1952 = jump(sim, { toYear: 1952 });
    expect(to1952.ok).toBe(true);
    expect(to1952.sim.year).toBe(1952);
    expect(to1952.fuelRemaining).toBe(3);
    sim = to1952.sim;

    // Pick a real open 1952 business occupying a settlement shop lot
    const targetPlace = Object.values(sim.places)
      .filter((p) => p.businessId && p.slot)
      .sort((a, b) => (a.id < b.id ? -1 : 1))[0]!;
    expect(targetPlace).toBeDefined();
    const targetBizId = targetPlace.businessId!;
    const targetSlot = targetPlace.slot!;

    const arsonRes = act(sim, {
      kind: 'arson',
      placeId: targetPlace.id,
      atNight: true,
      gloved: true,
    });
    expect(arsonRes.burnedBusinessId).toBe(targetBizId);
    expect(arsonRes.burnedSlot).toBe(targetSlot);
    sim = arsonRes.sim;

    // Jump forward to 2025
    const to2025 = jump(sim, { toYear: 2025 });
    expect(to2025.ok).toBe(true);
    expect(to2025.sim.year).toBe(2025);
    expect(to2025.fuelRemaining).toBe(2);

    // 1. The burned business is closed in 1952 and no longer open in 2025
    expect(to2025.sim.world.businesses[targetBizId]?.closedYear).toBe(1952);
    expect(
      Object.values(to2025.sim.places).some((p) => p.businessId === targetBizId),
    ).toBe(false);

    // 2. The lot is recorded as changed (burned/demolished or replaced) in the timeline diff
    expect(to2025.changes).toBeDefined();
    expect(to2025.changes!.hasChanges).toBe(true);
    const lotChange = to2025.changes!.buildings.demolishedOrReplaced.find(
      (b) => b.businessId === targetBizId || b.slot === targetSlot,
    );
    expect(lotChange).toBeDefined();
    expect(lotChange!.cause.toLowerCase()).toContain('arson');

    // 3. The Gazette remembers the 1952 fire in 2025
    const fireStory = to2025.gazette.find(
      (g) => g.year === 1952 && g.template === 'arson' && g.business === targetBizId,
    );
    expect(fireStory).toBeDefined();
    expect(fireStory!.headline.toLowerCase()).toContain('burns down');
    expect(timeMachineView(to2025.sim).gazette.some((g) => g.id === fireStory!.id)).toBe(true);

    // Also verify starting directly in 1952 on a stub town, burning Hale's Store, and jumping to 2025 works!
    let direct1952 = create({
      ...buildStubCapital({ seed: 'g020-direct-52', year: 1952, startDay: 1, startHour: 22 }),
      timelineStartYear: 1950,
    });
    const stubBurn = act(direct1952, {
      kind: 'arson',
      placeId: CAPITAL_IDS.haleStore,
      atNight: true,
    });
    expect(stubBurn.burnedBusinessId).toBeDefined();
    const direct2025 = jump(stubBurn.sim, { toYear: 2025 });
    expect(direct2025.ok).toBe(true);
    expect(
      direct2025.changes!.buildings.demolishedOrReplaced.some(
        (b) => b.businessId === stubBurn.burnedBusinessId,
      ),
    ).toBe(true);
    expect(
      direct2025.gazette.some(
        (g) => g.year === 1952 && g.template === 'arson' && g.business === stubBurn.burnedBusinessId,
      ),
    ).toBe(true);
  });

  it('resets attitudes on arrival except where the timeline carries branch memory', () => {
    clearTimelineCache();
    let sim = create({
      ...buildStubCapital({ seed: 'g020-attitudes', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
    });

    const j52 = jump(sim, { toYear: 1952 });
    sim = j52.sim;

    // Pick a shop with an alive owner in 1952 and work a shift there
    const shop = Object.values(sim.places)
      .filter((p) => p.businessId && p.ownerId && sim.peopleMeta[p.ownerId])
      .sort((a, b) => (a.id < b.id ? -1 : 1))[0]!;
    const employerId = shop.ownerId!;
    const strangerId = Object.keys(sim.peopleMeta)
      .filter((id) => id !== employerId && sim.peopleMeta[id]?.workplacePlaceId !== shop.id)
      .sort()[0]!;

    const shiftRes = act(sim, {
      kind: 'work_shift',
      placeId: shop.id,
      hours: 4,
      employerId,
      taskLabel: 'stacking crates',
    });
    sim = shiftRes.sim;
    expect(attitude(sim, employerId).knowsPlayerName).toBe(true);

    // Jump 2 years forward to 1954: if employer is alive, timeline memory preserves familiarity; stranger stays neutral
    const j54 = jump(sim, { toYear: 1954 });
    expect(j54.ok).toBe(true);
    if (j54.sim.peopleMeta[employerId]) {
      const empAtt = attitude(j54.sim, employerId);
      expect(empAtt.knowsPlayerName).toBe(true);
      expect(empAtt.trust).toBeGreaterThan(0);
    }
    if (j54.sim.peopleMeta[strangerId]) {
      const strAtt = attitude(j54.sim, strangerId);
      expect(strAtt.knowsPlayerName).toBe(false);
      expect(strAtt.stance).toBe('serve');
    }

    // Jump back to 1950 (BEFORE the 1952 work shift!): employer should NOT remember a future 1952 shift!
    const j50 = jump(j54.sim, { toYear: 1950 });
    expect(j50.ok).toBe(true);
    if (j50.sim.peopleMeta[employerId]) {
      expect(attitude(j50.sim, employerId).knowsPlayerName).toBe(false);
    }
  });

  it('carries items back and forth with history and matches prints across the years they should', () => {
    clearTimelineCache();
    // Player born in 1995 starts in 2025 with enough fuel for 4 jumps (2025->1952->1990->1995->1955)
    let sim = create({
      ...buildStubCapital({ seed: 'g020-prints-items', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
      playerBirthYear: 1995,
      initialFuel: 5,
    });

    // 1. Jump to 1952: buy a receipted pocket notebook AND steal a watch without gloves (leaves 1952 prints!)
    const j52 = jump(sim, { toYear: 1952 });
    expect(j52.ok).toBe(true);
    sim = j52.sim;

    const shop52 = Object.values(sim.places)
      .filter((p) => p.businessId)
      .sort((a, b) => (a.id < b.id ? -1 : 1))[0]!;

    const bought = act(sim, {
      kind: 'buy',
      placeId: shop52.id,
      item: { id: 'item:receipt_1952', label: 'a 1952 hardware receipt', value: 120 },
    });
    sim = bought.sim;

    const owner52 = shop52.ownerId ?? Object.keys(sim.peopleMeta)[0]!;
    const theft52 = act(sim, {
      kind: 'take',
      subkind: 'house',
      placeId: shop52.id,
      gloved: false,
      light: 0.95,
      item: {
        id: 'item:watch_1952',
        label: 'a gold Hamilton pocket watch',
        value: 25000,
        serialNumber: 'HAM-1952-X',
      },
      bystanders: [
        {
          id: owner52,
          position: { x: shop52.position.x + 1, y: shop52.position.y },
          facing: { x: -1, y: 0 },
          activity: 'watching',
        },
      ],
    });
    expect(theft52.crimeId).toBeDefined();
    expect(theft52.tracesLeft.some((t) => t.kind === 'fingerprint')).toBe(true);
    const crime1952Id = theft52.crimeId!;
    sim = advance(theft52.sim, 48).sim;

    // Confirm NO fingerprint match in 1952 (player has never been booked / no prints on file!)
    const caseIn1952 = sim.crimeState.jurisdictions[CAPITAL_JURISDICTION]?.cases[crime1952Id];
    expect(caseIn1952).toBeDefined();
    expect(caseIn1952!.evidenceMatches).toHaveLength(0);

    // 2. Jump to 1990: both items arrive intact with 1952 history; still no prints on file on arrival
    const j90 = jump(sim, { toYear: 1990 });
    expect(j90.ok).toBe(true);
    expect(j90.crossEraMatches).toHaveLength(0);
    expect(j90.sim.carriedItems?.map((i) => i.id)).toEqual([
      'item:receipt_1952',
      'item:watch_1952',
    ]);
    const watchIn90 = j90.sim.carriedItems!.find((i) => i.id === 'item:watch_1952')!;
    expect(watchIn90.acquiredYear).toBe(1952);
    expect(watchIn90.serialNumber).toBe('HAM-1952-X');
    expect(watchIn90.provenanceNote).toContain('1952');

    // 3. Get booked for a petty shoplift in 1990 -> enrolls fingerprints with enrolledYear = 1990!
    // Booking's G014 reexamineColdCases immediately matches the 1990 prints to the 1952 burglary scene!
    sim = j90.sim;
    const shop90 = Object.values(sim.places)
      .filter((p) => p.businessId)
      .sort((a, b) => (a.id < b.id ? -1 : 1))[0]!;
    const witness90 = shop90.ownerId ?? Object.keys(sim.peopleMeta)[0]!;
    const petty90 = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: shop90.id,
      light: 0.95,
      item: { id: 'item:gum_1990', label: 'a pack of gum', value: 50 },
      bystanders: [
        {
          id: witness90,
          position: { x: shop90.position.x + 1, y: shop90.position.y },
          facing: { x: -1, y: 0 },
          activity: 'watching',
        },
      ],
    });
    sim = advance(petty90.sim, 24).sim;
    const book90 = arrest(sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [petty90.crimeId!],
    });
    expect(book90.booking?.printsEnrolled).toBe(true);
    expect(book90.coldCasesTriggered).toContain(crime1952Id);
    const hit1952 = book90.booking!.coldCaseHits.find((h) => h.crimeId === crime1952Id);
    expect(hit1952).toBeDefined();
    expect(hit1952!.yearsToMatch).toBe(38);
    sim = book90.sim;

    // 4. Jump forward to 1995 (1990 <= 1995): 1990 prints STILL match the 1952 scene on arrival!
    // And since playerBirthYear === 1995 > 1952, flags a time-travel chronology anomaly!
    const j95 = jump(sim, { toYear: 1995 });
    expect(j95.ok).toBe(true);
    const match95 = j95.crossEraMatches.find((m) => m.crimeId === crime1952Id);
    expect(match95).toBeDefined();
    expect(match95!.crimeYear).toBe(1952);
    expect(match95!.matchedInYear).toBe(1995);
    expect(match95!.yearsToMatch).toBe(43);
    expect(match95!.anomalies.length).toBeGreaterThan(0);

    // 5. Jump BACK to 1955 (1990 > 1955): prints enrolled in 1990 do NOT exist in 1955!
    const j55 = jump(j95.sim, { toYear: 1955 });
    expect(j55.ok).toBe(true);
    expect(j55.crossEraMatches).toHaveLength(0);
    const caseIn55 = j55.sim.crimeState.jurisdictions[CAPITAL_JURISDICTION]?.cases[crime1952Id];
    expect(caseIn55?.evidenceMatches ?? []).toHaveLength(0);

    // And the receipted item carried back and forth (1952 -> 1990 -> 1995 -> 1955) kept its full 1952 history!
    const receiptBackIn55 = j55.sim.carriedItems!.find((i) => i.id === 'item:receipt_1952');
    expect(receiptBackIn55).toBeDefined();
    expect(receiptBackIn55!.acquiredYear).toBe(1952);
    expect(receiptBackIn55!.provenanceNote).toContain('1952');
  });

  it('exposes looseThreads as plain-English facts for HUD and NPC fact sheets', () => {
    clearTimelineCache();
    let sim = create({
      ...buildStubCapital({ seed: 'g020-loose-threads', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
    });

    const j52 = jump(sim, { toYear: 1952 });
    sim = j52.sim;

    const shop52 = Object.values(sim.places)
      .filter((p) => p.businessId)
      .sort((a, b) => (a.id < b.id ? -1 : 1))[0]!;

    // Buy a receipt from the store, then burn that store with a causal anchor tag -> produces loose threads!
    sim = act(sim, {
      kind: 'buy',
      placeId: shop52.id,
      item: { id: 'item:coffee_receipt', label: 'a dated counter receipt', value: 75 },
    }).sim;
    sim = act(sim, {
      kind: 'arson',
      placeId: shop52.id,
      causeTag: 'professors_lab',
      atNight: false,
    }).sim;

    const j2025 = jump(sim, { toYear: 2025 });
    expect(j2025.ok).toBe(true);
    expect(j2025.looseThreads.length).toBeGreaterThan(0);
    expect(j2025.looseThreadFacts.length).toBe(j2025.looseThreads.length);
    expect(j2025.looseThreadFacts.every((line) => typeof line === 'string' && line.length > 10)).toBe(true);

    // HUD view carries the same loose thread facts
    expect(timeMachineView(j2025.sim).looseThreadFacts).toEqual(j2025.looseThreadFacts);

    // Alive NPCs receive the loose-thread dialogue hooks on their attitude fact sheet
    const anyNpcId = Object.keys(j2025.sim.peopleMeta)[0]!;
    const att = attitude(j2025.sim, anyNpcId);
    expect(att.looseThreadFacts).toBeDefined();
    expect(att.looseThreadFacts!.length).toBeGreaterThan(0);
    expect(att.facts.some((f) => att.looseThreadFacts!.includes(f))).toBe(true);
  });

  it('starts with 4 nuclear fuel trips and refuses jumps once fuel runs out', () => {
    clearTimelineCache();
    let sim = create({
      ...buildStubCapital({ seed: 'g020-fuel', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
    });
    expect(DEFAULT_TIME_MACHINE_FUEL).toBe(4);
    expect(timeMachineView(sim).fuelRemaining).toBe(4);

    // Same-year jump is rejected cleanly without spending fuel
    const noop = jump(sim, { toYear: 2025 });
    expect(noop.ok).toBe(false);
    expect(noop.fuelSpent).toBe(0);
    expect(noop.fuelRemaining).toBe(4);

    const targets = [1952, 1975, 1995, 2025];
    for (let i = 0; i < targets.length; i++) {
      const res = jump(sim, { toYear: targets[i]! });
      expect(res.ok).toBe(true);
      expect(res.fuelSpent).toBe(1);
      expect(res.fuelRemaining).toBe(3 - i);
      expect(res.sim.year).toBe(targets[i]);
      sim = res.sim;
    }

    expect(timeMachineView(sim).fuelRemaining).toBe(0);
    expect(timeMachineView(sim).jumpHistory).toHaveLength(4);

    // 5th trip must fail cleanly and leave state unchanged
    const empty = jump(sim, { toYear: 1960 });
    expect(empty.ok).toBe(false);
    expect(empty.fuelSpent).toBe(0);
    expect(empty.fuelRemaining).toBe(0);
    expect(empty.reason!.toLowerCase()).toContain('fuel');
    expect(empty.sim.year).toBe(2025);
  });

  it('round-trips a save taken after a jump and continues identically', () => {
    clearTimelineCache();
    let sim = create({
      ...buildStubCapital({ seed: 'g020-save-rt', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
    });

    const j52 = jump(sim, { toYear: 1952 });
    const shop52 = Object.values(j52.sim.places).find((p) => p.businessId)!;
    let sim52 = act(j52.sim, {
      kind: 'buy',
      placeId: shop52.id,
      item: { id: 'item:rt_ticket', label: 'a 1952 ferry stub', value: 40 },
    }).sim;
    sim52 = act(sim52, {
      kind: 'arson',
      placeId: shop52.id,
      atNight: true,
    }).sim;

    const simA = jump(sim52, { toYear: 2025 }).sim;
    const serialized = save(simA);
    const simB = load(serialized);

    expect(simB).toEqual(simA);

    // Continue both with advance() + another jump() -> bit-for-bit identical!
    const advA = advance(simA, 48);
    const advB = advance(simB, 48);
    expect(save(advB.sim)).toBe(save(advA.sim));

    const nextA = jump(advA.sim, { toYear: 1988 });
    const nextB = jump(advB.sim, { toYear: 1988 });
    expect(nextB.gazette).toEqual(nextA.gazette);
    expect(nextB.looseThreadFacts).toEqual(nextA.looseThreadFacts);
    expect(save(nextB.sim)).toBe(save(nextA.sim));
  });

  it('exposes jump and timeMachineView via TimerpgSim, *Json, and simCall', () => {
    clearTimelineCache();
    const sim = create({
      ...buildStubCapital({ seed: 'g020-bridge', year: 2025, startDay: 1, startHour: 10 }),
      timelineStartYear: 1950,
    });

    TimerpgSim.load(save(sim));
    const call = (method: string, ...args: unknown[]) =>
      JSON.parse(simCall(JSON.stringify({ method, args })));

    const v0 = call('timeMachineView');
    expect(v0.fuelRemaining).toBe(4);
    expect(v0.currentYear).toBe(2025);

    const j = call('jump', { toYear: 1955 });
    expect(j.ok).toBe(true);
    expect(j.toYear).toBe(1955);
    expect(j.fuelRemaining).toBe(3);

    const v1 = JSON.parse(TimerpgSim.timeMachineView());
    expect(v1.currentYear).toBe(1955);
    expect(v1.fuelRemaining).toBe(3);

    // Stateless *Json wrappers work too
    const vStateless = JSON.parse(TimerpgSim.timeMachineViewJson(TimerpgSim.save()));
    expect(vStateless.currentYear).toBe(1955);
    const jStateless = JSON.parse(
      TimerpgSim.jumpJson(TimerpgSim.save(), JSON.stringify({ toYear: 1980 })),
    );
    expect(jStateless.ok).toBe(true);
    expect(jStateless.toYear).toBe(1980);
    expect(jStateless.fuelRemaining).toBe(2);
  });
});
