import { performance } from 'node:perf_hooks';
import { describe, expect, it } from 'vitest';
import {
  advanceDay,
  createCrimeState,
  ingestPoliceReport,
  policeKnowledge,
  recordCrime,
  witnessesOf,
  type CrimeEvent,
  type CulpritAppearance,
  type PoliceReport,
} from '../crime/index';
import { rand } from '../sim/rng';
import {
  StubTownPlanWorld,
  createPoliceForce,
  createStubTownGraph,
  createStubWorld,
  forceSizeForTown,
  getActiveWantedCases,
  measureSpottingRate,
  stepPoliceTick,
  type ObservedPerson,
  type PoliceTickContext,
} from './index';

const ROBBER_APPEARANCE: CulpritAppearance = {
  height: 'tall',
  build: 'heavy',
  hair: 'red',
  clothingColor: 'red',
  vehicle: { kind: 'sedan', color: 'black' },
};

const CHANGED_CLOTHES_APPEARANCE: CulpritAppearance = {
  height: 'tall',
  build: 'heavy',
  hair: 'red',
  clothingColor: 'gray',
  // ditched the getaway vehicle and changed jacket
};

function makeRobberyCrime(year = 1990, day = 10): CrimeEvent {
  return {
    id: 'crime_bank_1',
    kind: 'robbery',
    severity: 0.78,
    position: { x: 80, y: 0 },
    placeId: 'bank_a',
    townId: 'town_a',
    when: { year, day, hour: 10 },
    culpritId: 'suspect_1',
    culpritAppearance: ROBBER_APPEARANCE,
    victimId: 'witness_1',
    stolenOrDamaged: { item: 'cash_bag', value: 4500 },
  };
}

function reportCrimeToPolice(
  state: ReturnType<typeof createCrimeState>,
  crime: CrimeEvent,
  jurisdictionId: string,
  opts: { namedSuspect?: boolean; confidence?: number; day?: number } = {},
) {
  const day = opts.day ?? crime.when.day;
  const rep: PoliceReport = {
    id: `rep_${crime.id}_${jurisdictionId}`,
    crimeId: crime.id,
    reporterId: 'witness_1',
    jurisdictionId,
    filedDay: day,
    receivedDay: day,
    delayHours: 0.2,
    source: 'first_hand',
    isVictim: true,
    confidence: opts.confidence ?? 0.90,
    ...(opts.namedSuspect ? { identifiedCulpritId: crime.culpritId } : {}),
    description: {
      height: crime.culpritAppearance.height,
      build: crime.culpritAppearance.build,
      hair: crime.culpritAppearance.hair,
      clothingColor: crime.culpritAppearance.clothingColor,
      vehicle: crime.culpritAppearance.vehicle,
    },
  };
  return {
    ...state,
    jurisdictions: {
      ...state.jurisdictions,
      [jurisdictionId]: ingestPoliceReport(state.jurisdictions[jurisdictionId], crime, rep),
    },
  };
}

describe('src/police — patrols, searches, spotting, chases & arrests via G007 plans', () => {
  it('reported robbery brings an officer to the scene who interviews witnesses and refreshes G005 memory', () => {
    const graph = createStubTownGraph();
    const world = createStubWorld('robbery-scene', 1990, [
      'officer_town_a_0',
      'officer_town_a_1',
      'suspect_1',
      'witness_1',
      'witness_2',
    ]);
    const crime = makeRobberyCrime(1990, 10);

    // Record first-hand witness sightings via G005
    let crimeState = createCrimeState('robbery-scene', 10);
    const sightings = witnessesOf(
      crime,
      [
        { person: world.people['witness_1']!, position: { x: 82, y: 1 } },
        { person: world.people['witness_2']!, position: { x: 78, y: 2 } },
      ],
      { seed: 'robbery-scene', world, victim: world.people['witness_1']! },
    );
    crimeState = recordCrime(crimeState, crime, sightings);
    // Let memory fade for 2 days before the interview so refresh is clearly measurable
    crimeState = advanceDay(crimeState, world, 11, {
      year: 1990,
      jurisdictionOf: () => 'town_a',
    });
    crimeState = reportCrimeToPolice(crimeState, crime, 'town_a', { day: 11 });

    const beforeRefreshes = crimeState.ledger[crime.id]?.['witness_1']?.refreshes ?? 0;
    const beforeConf = crimeState.ledger[crime.id]?.['witness_1']?.confidence ?? 0;

    let policeState = createPoliceForce({
      seed: 'robbery-scene',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 1200, stationNodeId: 'station_a', officerCountOverride: 2 }],
      graph,
    });
    const planWorld = new StubTownPlanWorld(graph, 11 * 3600);
    planWorld.spawnOfficers(policeState.officers);
    planWorld.addActor({
      id: 'witness_1',
      position: { x: 81, y: 1 },
      nodeId: 'bank_a',
      jurisdictionId: 'town_a',
      inventory: [],
      hidden: false,
      traits: { gentle: 0.6, honest: 0.8, lawful: 0.8, brave: 0.4 },
      money: 100,
    });
    planWorld.addActor({
      id: 'witness_2',
      position: { x: 79, y: 1 },
      nodeId: 'bank_a',
      jurisdictionId: 'town_a',
      inventory: [],
      hidden: false,
      traits: { gentle: 0.6, honest: 0.8, lawful: 0.8, brave: 0.4 },
      money: 100,
    });

    let dispatchedPlan = '';
    for (let t = 0; t < 120; t++) {
      planWorld.timeSec += 1;
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 11,
        year: 1990,
        hour: 11,
        dt: 1,
        people: planWorld.getObservedPeople(),
        ignoreShifts: true,
      });
      policeState = res.state;
      crimeState = res.crimeState;
      const responderId = policeState.respondedCases[`town_a:${crime.id}`];
      if (responderId && !dispatchedPlan) {
        dispatchedPlan = policeState.officers[responderId]!.planSource ?? '';
      }
      if (policeState.interviews.length >= 2) break;
    }

    // Verify a real G007 plan drove the officer to the bank and asked both witnesses
    expect(dispatchedPlan).toContain('bank_a');
    expect(dispatchedPlan).toContain('ask person:witness_1');
    expect(policeState.interviews.length).toBeGreaterThanOrEqual(2);
    expect(policeState.interviews.some((iv) => iv.witnessId === 'witness_1' && iv.memoryRefreshed)).toBe(true);
    expect(policeState.interviews.some((iv) => iv.witnessId === 'witness_2' && iv.memoryRefreshed)).toBe(true);

    // G005 memory was refreshed by the police interview!
    const afterAcc = crimeState.ledger[crime.id]!['witness_1']!;
    expect(afterAcc.refreshes).toBeGreaterThan(beforeRefreshes);
    expect(afterAcc.confidence).toBeGreaterThanOrEqual(beforeConf);
  });

  it('wanted suspect walking the beat is spotted and questioned', () => {
    const graph = createStubTownGraph();
    const world = createStubWorld('beat-spot', 1990, ['officer_town_a_0', 'suspect_1', 'witness_1']);
    const crime = makeRobberyCrime(1990, 10);
    // Medium severity robbery/theft so officer approaches & questions/asks for papers
    const theftCrime: CrimeEvent = { ...crime, id: 'crime_theft_1', kind: 'burglary', severity: 0.55 };

    let crimeState = createCrimeState('beat-spot', 10);
    crimeState = recordCrime(crimeState, theftCrime, []);
    crimeState = reportCrimeToPolice(crimeState, theftCrime, 'town_a', { confidence: 0.92 });

    let policeState = createPoliceForce({
      seed: 'beat-spot',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 600, stationNodeId: 'station_a', officerCountOverride: 1 }],
      graph,
    });
    // Mark crime as already responded so officer stays on beat patrol
    policeState.respondedCases[`town_a:${theftCrime.id}`] = 'officer_town_a_0';

    const planWorld = new StubTownPlanWorld(graph, 12 * 3600);
    planWorld.spawnOfficers(policeState.officers);
    const officerPos = planWorld.getActorPosition('officer_town_a_0');
    const officerNode = planWorld.getActorPlace('officer_town_a_0');
    planWorld.addActor({
      id: 'suspect_1',
      position: { x: officerPos.x + 6, y: officerPos.y + 1 },
      nodeId: officerNode,
      jurisdictionId: 'town_a',
      appearance: ROBBER_APPEARANCE,
      inventory: ['wallet'],
      hidden: false,
      traits: { gentle: 0.5, honest: 0.2, lawful: 0.1, brave: 0.5 },
      money: 200,
    });

    let sawQuestioning = false;
    let spottedAction: string | undefined;

    for (let t = 0; t < 40; t++) {
      planWorld.timeSec += 1;
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 10,
        year: 1990,
        hour: 12,
        dt: 1,
        people: planWorld.getObservedPeople(),
        willSuspectFlee: () => false,
        ignoreShifts: true,
        spotIntervalSec: 1,
      });
      policeState = res.state;
      crimeState = res.crimeState;
      for (const s of res.events.spots) {
        if (s.seenPersonId === 'suspect_1' && s.action !== 'ignore') {
          spottedAction = s.action;
        }
      }
      if (policeState.officers['officer_town_a_0']!.assignment === 'question') {
        sawQuestioning = true;
      }
      if (policeState.stops.length > 0) break;
    }

    expect(spottedAction).toBeDefined();
    expect(sawQuestioning || policeState.stops.length > 0).toBe(true);
    expect(policeState.stops.some((st) => st.personId === 'suspect_1')).toBe(true);
    expect(planWorld.log.some((l) => l.action === 'ask' && l.target === 'suspect_1')).toBe(true);
  });

  it('same suspect in different clothes and at night is missed measurably more often (prints rates)', () => {
    const graph = createStubTownGraph();
    const world = createStubWorld('disguise-night', 1990, ['officer_town_a_0', 'suspect_1', 'witness_1']);
    const crime = makeRobberyCrime(1990, 10);

    let crimeState = createCrimeState('disguise-night', 10);
    crimeState = recordCrime(crimeState, crime, []);
    crimeState = reportCrimeToPolice(crimeState, crime, 'town_a', { namedSuspect: false, confidence: 0.92 });

    const policeState = createPoliceForce({
      seed: 'disguise-night',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 800, stationNodeId: 'station_a', officerCountOverride: 1 }],
      graph,
    });
    const officer = policeState.officers['officer_town_a_0']!;
    const snap = policeKnowledge(crimeState, 'town_a', 10);
    const activeCases = getActiveWantedCases(snap.cases, 10);

    const alleyPos = graph.nodes['back_alley']!.position; // dark alley (streetLight 0.08)
    const officerPos = { x: alleyPos.x - 10, y: alleyPos.y };

    const makeObserved = (appearance: CulpritAppearance): ObservedPerson => ({
      personId: 'suspect_1',
      position: alleyPos,
      nodeId: 'back_alley',
      jurisdictionId: 'town_a',
      appearance,
    });

    const trials = 300;
    const dayOrig = measureSpottingRate({
      baseSeed: 'rate-day-orig',
      trials,
      officer,
      officerPos,
      seen: makeObserved(ROBBER_APPEARANCE),
      activeCases,
      jurisdictionHeat: snap.heat,
      crimeState,
      world,
      graph,
      day: 10,
      year: 1990,
      hour: 13,
    });

    const dayChanged = measureSpottingRate({
      baseSeed: 'rate-day-changed',
      trials,
      officer,
      officerPos,
      seen: makeObserved(CHANGED_CLOTHES_APPEARANCE),
      activeCases,
      jurisdictionHeat: snap.heat,
      crimeState,
      world,
      graph,
      day: 10,
      year: 1990,
      hour: 13,
    });

    const nightOrig = measureSpottingRate({
      baseSeed: 'rate-night-orig',
      trials,
      officer,
      officerPos,
      seen: makeObserved(ROBBER_APPEARANCE),
      activeCases,
      jurisdictionHeat: snap.heat,
      crimeState,
      world,
      graph,
      day: 10,
      year: 1990,
      hour: 2,
    });

    const nightChanged = measureSpottingRate({
      baseSeed: 'rate-night-changed',
      trials,
      officer,
      officerPos,
      seen: makeObserved(CHANGED_CLOTHES_APPEARANCE),
      activeCases,
      jurisdictionHeat: snap.heat,
      crimeState,
      world,
      graph,
      day: 10,
      year: 1990,
      hour: 2,
    });

    console.log(
      `SPOTTING_RATES (n=${trials}): ` +
        `day_same_clothes=${(dayOrig.rate * 100).toFixed(1)}% (p=${dayOrig.meanChance.toFixed(3)}) | ` +
        `day_diff_clothes=${(dayChanged.rate * 100).toFixed(1)}% (p=${dayChanged.meanChance.toFixed(3)}) | ` +
        `night_same_clothes=${(nightOrig.rate * 100).toFixed(1)}% (p=${nightOrig.meanChance.toFixed(3)}) | ` +
        `night_diff_clothes=${(nightChanged.rate * 100).toFixed(1)}% (p=${nightChanged.meanChance.toFixed(3)})`,
    );

    expect(dayOrig.rate).toBeGreaterThan(dayChanged.rate);
    expect(dayOrig.rate).toBeGreaterThan(nightOrig.rate);
    expect(dayOrig.rate).toBeGreaterThan(nightChanged.rate + 0.25);
    expect(nightChanged.meanChance).toBeLessThan(dayOrig.meanChance * 0.55);
  });

  it('fleeing suspect is chased, caught, searched, cuffed, and transported to station (full ArrestRecord)', () => {
    const graph = createStubTownGraph();
    const world = createStubWorld('chase-arrest', 1990, ['officer_town_a_0', 'suspect_1', 'witness_1']);
    const crime = makeRobberyCrime(1990, 10);

    let crimeState = createCrimeState('chase-arrest', 10);
    crimeState = recordCrime(crimeState, crime, []);
    crimeState = reportCrimeToPolice(crimeState, crime, 'town_a', { namedSuspect: true, confidence: 0.95 });

    let policeState = createPoliceForce({
      seed: 'chase-arrest',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 600, stationNodeId: 'station_a', officerCountOverride: 1 }],
      graph,
    });
    policeState.respondedCases[`town_a:${crime.id}`] = 'officer_town_a_0';

    const planWorld = new StubTownPlanWorld(graph, 12 * 3600);
    planWorld.spawnOfficers(policeState.officers);
    // Start officer on foot at main_west so chase is pure foot pursuit, suspect running slower than sprint
    planWorld.actors['officer_town_a_0']!.position = { x: 40, y: 0 };
    planWorld.actors['officer_town_a_0']!.nodeId = 'main_west';
    policeState.officers['officer_town_a_0']!.transport = 'foot';
    policeState.officers['officer_town_a_0']!.vehicleId = undefined;

    planWorld.addActor({
      id: 'suspect_1',
      position: { x: 52, y: 0 },
      nodeId: 'main_west',
      jurisdictionId: 'town_a',
      appearance: ROBBER_APPEARANCE,
      inventory: ['stolen_cash_bag', 'house_keys', 'pocket_knife'],
      fleeing: true,
      hidden: false,
      traits: { gentle: 0.3, honest: 0.1, lawful: 0.1, brave: 0.4 },
      money: 500,
    });

    let sawChase = false;
    let sawTransport = false;

    for (let t = 0; t < 120; t++) {
      planWorld.timeSec += 1;
      // Suspect flees east at 1.4 m/s (slower than officer's 2.4 m/s sprint) until cuffed
      if (!planWorld.actors['suspect_1']!.inCustody) {
        planWorld.movePersonToward('suspect_1', graph.nodes['bank_a']!.position, 1.4, 1);
      }
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 10,
        year: 1990,
        hour: 12,
        dt: 1,
        people: planWorld.getObservedPeople(),
        getPersonInventory: (pid) => planWorld.actors[pid]?.inventory ?? [],
        onSuspectCustody: (sid, oid, phase) => planWorld.syncCustodyPosition(sid, oid, phase),
        ignoreShifts: true,
        spotIntervalSec: 1,
      });
      policeState = res.state;
      crimeState = res.crimeState;

      const assign = policeState.officers['officer_town_a_0']!.assignment;
      if (assign === 'chase') sawChase = true;
      if (assign === 'transport') sawTransport = true;
      if (policeState.arrests.some((a) => a.transportedToStation)) break;
    }

    expect(sawChase).toBe(true);
    expect(sawTransport).toBe(true);
    expect(policeState.arrests.length).toBe(1);
    const arrest = policeState.arrests[0]!;
    expect(arrest.suspectId).toBe('suspect_1');
    expect(arrest.crimeId).toBe(crime.id);
    expect(arrest.cuffed).toBe(true);
    expect(arrest.transportedToStation).toBe(true);
    expect(arrest.searchedInventory).toContain('stolen_cash_bag');
    expect(arrest.seizedContraband).toContain('stolen_cash_bag');
    expect(arrest.seizedContraband).toContain('pocket_knife');
    expect(arrest.seizedContraband).not.toContain('house_keys');
    expect(arrest.caseStrengthAtArrest).toBeGreaterThan(0);
    expect(planWorld.actors['suspect_1']!.nodeId).toBe('station_a');
    expect(planWorld.log.some((l) => l.action === 'restrain' && l.target === 'suspect_1')).toBe(true);
  });

  it('fleeing suspect escapes across jurisdiction border; heat stays zero in new jurisdiction unless escalated', () => {
    const graph = createStubTownGraph();
    const world = createStubWorld('border-escape', 1990, [
      'officer_town_a_0',
      'officer_town_b_0',
      'suspect_1',
      'witness_1',
    ]);
    const crime = makeRobberyCrime(1990, 10);

    let crimeState = createCrimeState('border-escape', 10);
    crimeState = recordCrime(crimeState, crime, []);
    crimeState = reportCrimeToPolice(crimeState, crime, 'town_a', { namedSuspect: true, confidence: 0.95 });

    let policeState = createPoliceForce({
      seed: 'border-escape',
      year: 1990,
      towns: [
        { jurisdictionId: 'town_a', population: 600, stationNodeId: 'station_a', officerCountOverride: 1 },
        { jurisdictionId: 'town_b', population: 600, stationNodeId: 'station_b', officerCountOverride: 1 },
      ],
      graph,
    });
    policeState.respondedCases[`town_a:${crime.id}`] = 'officer_town_a_0';

    const planWorld = new StubTownPlanWorld(graph, 12 * 3600);
    planWorld.spawnOfficers(policeState.officers);
    // Place officer_town_a_0 on foot near east_gate, suspect sprinting faster across county line into town_b
    policeState.officers['officer_town_a_0']!.transport = 'foot';
    policeState.officers['officer_town_a_0']!.vehicleId = undefined;
    planWorld.actors['officer_town_a_0']!.position = { x: 132, y: 0 };
    planWorld.actors['officer_town_a_0']!.nodeId = 'east_gate';
    planWorld.actors['officer_town_a_0']!.jurisdictionId = 'town_a';

    planWorld.addActor({
      id: 'suspect_1',
      position: { x: 138, y: 0 },
      nodeId: 'east_gate',
      jurisdictionId: 'town_a',
      appearance: ROBBER_APPEARANCE,
      inventory: ['stolen_cash_bag'],
      fleeing: true,
      hidden: false,
      traits: { gentle: 0.2, honest: 0.1, lawful: 0.1, brave: 0.6 },
      money: 500,
    });

    for (let t = 0; t < 40; t++) {
      planWorld.timeSec += 1;
      // Suspect sprints across border into town_b (west_entry_b at x=185) at 5 m/s
      planWorld.movePersonToward('suspect_1', graph.nodes['west_entry_b']!.position, 5.0, 1);
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 10,
        year: 1990,
        hour: 12,
        dt: 1,
        people: planWorld.getObservedPeople(),
        ignoreShifts: true,
        spotIntervalSec: 1,
      });
      policeState = res.state;
      crimeState = res.crimeState;
      if (policeState.escapes.length > 0) break;
    }

    expect(policeState.escapes.length).toBe(1);
    expect(policeState.escapes[0]!.fromJurisdictionId).toBe('town_a');
    expect(policeState.escapes[0]!.toJurisdictionId).toBe('town_b');
    expect(policeState.escapes[0]!.escalatedToJurisdictionId).toBeUndefined();
    expect(policeState.arrests.length).toBe(0);
    // Town A heat is high, but Town B has ZERO heat because the crime did not escalate!
    expect(policeKnowledge(crimeState, 'town_a', 10).heat).toBeGreaterThan(30);
    expect(policeKnowledge(crimeState, 'town_b', 10).heat).toBe(0);
  });

  it('innocent lookalike matching wanted description is stopped by mistake and released', () => {
    const graph = createStubTownGraph();
    const world = createStubWorld('lookalike-stop', 1990, [
      'officer_town_a_0',
      'suspect_1',
      'lookalike_1',
      'witness_1',
    ]);
    const crime: CrimeEvent = {
      ...makeRobberyCrime(1990, 10),
      id: 'crime_burg_lookalike',
      kind: 'burglary',
      severity: 0.55,
    };

    let crimeState = createCrimeState('lookalike-stop', 10);
    crimeState = recordCrime(crimeState, crime, []);
    // Unnamed suspect — police only have a physical description!
    crimeState = reportCrimeToPolice(crimeState, crime, 'town_a', { namedSuspect: false, confidence: 0.92 });

    let policeState = createPoliceForce({
      seed: 'lookalike-stop',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 600, stationNodeId: 'station_a', officerCountOverride: 1 }],
      graph,
    });
    policeState.respondedCases[`town_a:${crime.id}`] = 'officer_town_a_0';

    const planWorld = new StubTownPlanWorld(graph, 12 * 3600);
    planWorld.spawnOfficers(policeState.officers);
    const officerPos = planWorld.getActorPosition('officer_town_a_0');
    const officerNode = planWorld.getActorPlace('officer_town_a_0');

    // Innocent citizen wearing the exact same red jacket & matching build/hair
    planWorld.addActor({
      id: 'lookalike_1',
      position: { x: officerPos.x + 5, y: officerPos.y + 1 },
      nodeId: officerNode,
      jurisdictionId: 'town_a',
      appearance: ROBBER_APPEARANCE,
      inventory: ['groceries', 'library_card'],
      hidden: false,
      traits: { gentle: 0.8, honest: 0.9, lawful: 0.9, brave: 0.5 },
      money: 40,
    });

    for (let t = 0; t < 40; t++) {
      planWorld.timeSec += 1;
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 10,
        year: 1990,
        hour: 12,
        dt: 1,
        people: planWorld.getObservedPeople(),
        getPersonInventory: (pid) => planWorld.actors[pid]?.inventory ?? [],
        ignoreShifts: true,
        spotIntervalSec: 1,
      });
      policeState = res.state;
      crimeState = res.crimeState;
      if (policeState.stops.length > 0) break;
    }

    expect(policeState.stops.length).toBe(1);
    expect(policeState.stops[0]!.personId).toBe('lookalike_1');
    expect(policeState.stops[0]!.isLookalike).toBe(true);
    expect(policeState.stops[0]!.reason).toBe('lookalike_cleared');
    expect(policeState.arrests.length).toBe(0);
    expect(planWorld.actors['lookalike_1']!.inCustody).toBeFalsy();
  });

  it('1925 vs 1990 behaviour differs: fewer officers, foot-only, no radio in 1925 vs patrol cars & instant radio backup in 1990', () => {
    const graph = createStubTownGraph();

    expect(forceSizeForTown(3000, 1925)).toBeLessThan(forceSizeForTown(3000, 1990));

    const force1925 = createPoliceForce({
      seed: 'era-diff',
      year: 1925,
      towns: [{ jurisdictionId: 'town_a', population: 3000, stationNodeId: 'station_a' }],
      graph,
    });
    const force1990 = createPoliceForce({
      seed: 'era-diff',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 3000, stationNodeId: 'station_a' }],
      graph,
    });

    const list1925 = Object.values(force1925.officers);
    const list1990 = Object.values(force1990.officers);

    expect(list1925.length).toBeLessThan(list1990.length);
    expect(list1925.every((o) => o.transport === 'foot')).toBe(true);
    expect(list1925.every((o) => !o.hasRadio)).toBe(true);
    expect(list1990.some((o) => o.transport === 'car')).toBe(true);
    expect(list1990.every((o) => o.hasRadio)).toBe(true);
    expect(list1990.some((o) => o.role === 'detective')).toBe(true);

    // Run identical violent robbery sighting in 1925 vs 1990 and compare backup dispatch
    const runEraBackup = (year: number) => {
      let force = createPoliceForce({
        seed: 'era-backup',
        year,
        towns: [{ jurisdictionId: 'town_a', population: 3000, stationNodeId: 'station_a' }],
        graph,
      });
      const officerIds = Object.keys(force.officers).sort();
      const world = createStubWorld(`era-${year}`, year, [...officerIds, 'suspect_1', 'witness_1']);
      const crime = makeRobberyCrime(year, 10);
      let cs = createCrimeState(`era-${year}`, 10);
      cs = recordCrime(cs, crime, []);
      cs = reportCrimeToPolice(cs, crime, 'town_a', { namedSuspect: true, confidence: 0.95 });
      force.respondedCases[`town_a:${crime.id}`] = officerIds[0]!;

      const pw = new StubTownPlanWorld(graph, 12 * 3600);
      pw.spawnOfficers(force.officers);
      // Put officer 0 at bank_a, other officers further away at oak_homes & station_a
      pw.actors[officerIds[0]!]!.position = { x: 80, y: 0 };
      pw.actors[officerIds[0]!]!.nodeId = 'bank_a';
      if (force.officers[officerIds[0]!]!.vehicleId) {
        pw.vehicles[force.officers[officerIds[0]!]!.vehicleId!]!.position = { x: 80, y: 0 };
      }
      pw.addActor({
        id: 'suspect_1',
        position: { x: 85, y: 0 },
        nodeId: 'bank_a',
        jurisdictionId: 'town_a',
        appearance: ROBBER_APPEARANCE,
        inventory: ['stolen_cash_bag'],
        hidden: false,
        traits: { gentle: 0.2, honest: 0.1, lawful: 0.1, brave: 0.6 },
        money: 100,
      });

      let backupOfficersDispatched = 0;
      let radioSignals = 0;
      for (let t = 0; t < 10; t++) {
        pw.timeSec += 1;
        const res = stepPoliceTick(force, {
          world,
          planWorld: pw,
          crimeState: cs,
          graph,
          day: 10,
          year,
          hour: 12,
          dt: 1,
          people: pw.getObservedPeople(),
          ignoreShifts: true,
          spotIntervalSec: 1,
        });
        force = res.state;
        cs = res.crimeState;
        backupOfficersDispatched = Math.max(
          backupOfficersDispatched,
          Object.values(force.officers).filter((o) => o.assignment === 'backup').length,
        );
        radioSignals = pw.signals.length;
      }
      return { backupOfficersDispatched, radioSignals, alerts: force.backupAlerts.length };
    };

    const res1925 = runEraBackup(1925);
    const res1990 = runEraBackup(1990);

    // 1990 fires radio backup immediately and converges backup units within 10s; 1925 has no radio!
    expect(res1990.radioSignals).toBeGreaterThan(0);
    expect(res1990.backupOfficersDispatched).toBeGreaterThan(0);
    expect(res1925.backupOfficersDispatched).toBe(0);
  });

  it('heat drives search & stakeout plans at suspect haunts, and state survives JSON round-trip deterministically', () => {
    const run = (roundTrip: boolean): string => {
      const graph = createStubTownGraph();
      let policeState = createPoliceForce({
        seed: 'heat-det',
        year: 1990,
        towns: [{ jurisdictionId: 'town_a', population: 2000, stationNodeId: 'station_a' }],
        graph,
      });
      const officerIds = Object.keys(policeState.officers).sort();
      const world = createStubWorld('heat-det', 1990, [...officerIds, 'suspect_1', 'witness_1']);
      const crime = makeRobberyCrime(1990, 10);
      let crimeState = createCrimeState('heat-det', 10);
      crimeState = recordCrime(crimeState, crime, []);
      crimeState = reportCrimeToPolice(crimeState, crime, 'town_a', { namedSuspect: true, confidence: 0.92 });
      policeState.respondedCases[`town_a:${crime.id}`] = officerIds[0]!;

      const planWorld = new StubTownPlanWorld(graph, 10 * 3600);
      planWorld.spawnOfficers(policeState.officers);

      const ctxBase: Omit<PoliceTickContext, 'crimeState'> = {
        world,
        planWorld,
        graph,
        day: 10,
        year: 1990,
        hour: 10,
        dt: 1,
        people: [],
        knownHauntsOf: (pid) => (pid === 'suspect_1' ? ['oak_homes', 'tavern_a'] : []),
        lastSeenNodeOf: () => 'bank_a',
        ignoreShifts: true,
      };

      for (let t = 0; t < 15; t++) {
        planWorld.timeSec += 1;
        const res = stepPoliceTick(policeState, { ...ctxBase, crimeState });
        policeState = res.state;
        crimeState = res.crimeState;
        if (roundTrip && t === 5) {
          policeState = JSON.parse(JSON.stringify(policeState));
          crimeState = JSON.parse(JSON.stringify(crimeState));
        }
      }

      const assignments = Object.values(policeState.officers).map((o) => o.assignment);
      expect(assignments).toContain('stakeout');
      expect(assignments).toContain('search_suspect');
      return JSON.stringify(policeState);
    };

    const a = run(false);
    const b = run(false);
    const c = run(true);
    expect(a).toBe(b);
    expect(a).toBe(c);
  });

  it('performance: 30 officers in a 2,000-person town step in under 1 ms per tick', () => {
    const graph = createStubTownGraph();
    let policeState = createPoliceForce({
      seed: 'perf-30x2000',
      year: 1990,
      towns: [{ jurisdictionId: 'town_a', population: 15000, stationNodeId: 'station_a', officerCountOverride: 30 }],
      graph,
    });
    const officerIds = Object.keys(policeState.officers).sort();
    expect(officerIds.length).toBe(30);

    const citizenIds: string[] = [];
    for (let i = 0; i < 2000; i++) citizenIds.push(`cit_${String(i).padStart(4, '0')}`);
    const world = createStubWorld('perf-30x2000', 1990, [...officerIds, ...citizenIds]);

    const crime = makeRobberyCrime(1990, 10);
    const activeCrime: CrimeEvent = { ...crime, culpritId: 'cit_0000' };
    let crimeState = createCrimeState('perf-30x2000', 10);
    crimeState = recordCrime(crimeState, activeCrime, []);
    crimeState = reportCrimeToPolice(crimeState, activeCrime, 'town_a', { namedSuspect: true, confidence: 0.85 });
    policeState.respondedCases[`town_a:${activeCrime.id}`] = officerIds[0]!;

    const planWorld = new StubTownPlanWorld(graph, 12 * 3600);
    planWorld.spawnOfficers(policeState.officers);

    // Distribute 2,000 citizens across a 600m x 600m town with varied appearances
    const heights = ['short', 'average', 'tall'] as const;
    const builds = ['slim', 'average', 'heavy'] as const;
    const hairs = ['dark', 'brown', 'blonde', 'red', 'gray', 'bald'] as const;
    const clothes = ['black', 'navy', 'gray', 'brown', 'tan', 'white', 'red', 'green', 'blue'] as const;
    const townANodes = Object.values(graph.nodes).filter((n) => n.jurisdictionId === 'town_a');

    const observedPeople: ObservedPerson[] = [];
    for (let i = 0; i < 2000; i++) {
      const id = citizenIds[i]!;
      const x = rand('perf-pos', 'x', i) * 600 - 150;
      const y = rand('perf-pos', 'y', i) * 600 - 150;
      const node = townANodes[i % townANodes.length]!;
      const appearance: CulpritAppearance = {
        height: heights[i % heights.length]!,
        build: builds[i % builds.length]!,
        hair: hairs[i % hairs.length]!,
        clothingColor: clothes[i % clothes.length]!,
      };
      planWorld.addActor({
        id,
        position: { x, y },
        nodeId: node.id,
        jurisdictionId: 'town_a',
        appearance,
        inventory: [],
        hidden: false,
        traits: { gentle: 0.6, honest: 0.7, lawful: 0.7, brave: 0.5 },
        money: 100,
      });
      observedPeople.push({
        personId: id,
        position: { x, y },
        nodeId: node.id,
        jurisdictionId: 'town_a',
        appearance,
      });
    }

    // Warm up 15 ticks so all 30 officers have compiled patrol plans and JIT settles
    for (let t = 0; t < 15; t++) {
      planWorld.timeSec += 1;
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 10,
        year: 1990,
        hour: 12,
        dt: 1,
        people: observedPeople,
        ignoreShifts: true,
        spotIntervalSec: 2,
      });
      policeState = res.state;
      crimeState = res.crimeState;
    }

    // Measure 60 consecutive ticks
    const timesMs: number[] = [];
    for (let t = 0; t < 60; t++) {
      planWorld.timeSec += 1;
      const t0 = performance.now();
      const res = stepPoliceTick(policeState, {
        world,
        planWorld,
        crimeState,
        graph,
        day: 10,
        year: 1990,
        hour: 12,
        dt: 1,
        people: observedPeople,
        ignoreShifts: true,
        spotIntervalSec: 2,
      });
      timesMs.push(performance.now() - t0);
      policeState = res.state;
      crimeState = res.crimeState;
    }

    const sorted = [...timesMs].sort((a, b) => a - b);
    const median = sorted[Math.floor(sorted.length / 2)]!;
    const mean = timesMs.reduce((a, b) => a + b, 0) / timesMs.length;
    const max = sorted[sorted.length - 1]!;
    console.log(
      `PERF_POLICE_30x2000: median=${median.toFixed(3)}ms mean=${mean.toFixed(3)}ms max=${max.toFixed(3)}ms`,
    );

    expect(median).toBeLessThan(1.0);
  });
});
