import { performance } from 'node:perf_hooks';
import { describe, expect, it } from 'vitest';
import { rand } from '../sim/rng';
import { pairKey } from '../sim/social';
import type { Id, Person, World } from '../sim/types';
import {
  DEFAULT_SEVERITY,
  advanceDay,
  ambientLightAt,
  appearanceMatchScore,
  createCrimeState,
  decaySighting,
  isWanted,
  knowersOf,
  mergeDescriptions,
  policeKnowledge,
  recognitionChance,
  recordCrime,
  refreshSighting,
  refreshWitnessMemory,
  reportingProbability,
  witnessesOf,
  type Bystander,
  type CrimeAccount,
  type CrimeContext,
  type CrimeEvent,
  type CulpritAppearance,
  type PoliceReport,
  type Sighting,
} from './index';

const CULPRIT_APPEARANCE: CulpritAppearance = {
  height: 'tall',
  build: 'heavy',
  hair: 'red',
  clothingColor: 'navy',
  vehicle: { kind: 'sedan', color: 'green' },
};

function makePerson(seed: string, id: Id, year: number, idx: number): Person {
  const age = 18 + Math.floor(rand(seed, 'age', id) * 52);
  return {
    id,
    first: `First${idx}`,
    surname: `Sur${idx % 40}`,
    sex: idx % 2 === 0 ? 'F' : 'M',
    birthYear: year - age,
    traits: {
      ambition: rand(seed, 'amb', id),
      charm: rand(seed, 'charm', id),
      diligence: rand(seed, 'dil', id),
    },
    parents: [],
    children: [],
    wealth: 500 + Math.floor(rand(seed, 'wealth', id) * 1500),
    reputation: (rand(seed, 'rep', id) - 0.5) * 1.4,
  };
}

function buildTestWorld(
  seed: string,
  count: number,
  year = 1990,
  townCount = 1,
): {
  world: World;
  townByPerson: Record<Id, string>;
  workplaceByPerson: Record<Id, string>;
} {
  const people: Record<Id, Person> = {};
  const townByPerson: Record<Id, string> = {};
  const workplaceByPerson: Record<Id, string> = {};
  const perTown = Math.ceil(count / townCount);

  for (let i = 0; i < count; i++) {
    const id = `p:${String(i).padStart(4, '0')}`;
    const p = makePerson(seed, id, year, i);
    people[id] = p;
    const t = Math.min(townCount - 1, Math.floor(i / perTown));
    townByPerson[id] = `town:${t}`;
    workplaceByPerson[id] = `town:${t}:wp:${i % 15}`;
  }

  // Wire realistic married couples and parent/child kin within each town
  for (let t = 0; t < townCount; t++) {
    const start = t * perTown;
    const end = Math.min(count, start + perTown);
    for (let i = start; i + 1 < end; i += 4) {
      const a = people[`p:${String(i).padStart(4, '0')}`]!;
      const b = people[`p:${String(i + 1).padStart(4, '0')}`]!;
      a.spouse = b.id;
      b.spouse = a.id;
      if (i + 2 < end) {
        const child = people[`p:${String(i + 2).padStart(4, '0')}`]!;
        child.parents = [a.id, b.id];
        a.children.push(child.id);
        b.children.push(child.id);
      }
    }
  }

  return {
    world: {
      seed,
      year,
      people,
      businesses: {},
      bonds: {},
    },
    townByPerson,
    workplaceByPerson,
  };
}

function makeCrime(overrides: Partial<CrimeEvent> & { id: string }): CrimeEvent {
  const kind = overrides.kind ?? 'robbery';
  return {
    id: overrides.id,
    kind,
    severity: overrides.severity ?? DEFAULT_SEVERITY[kind],
    position: overrides.position ?? { x: 0, y: 0 },
    placeId: overrides.placeId ?? 'town:0:main_st',
    townId: overrides.townId ?? 'town:0',
    when: overrides.when ?? { year: 1990, day: 0, hour: 12 },
    culpritId: overrides.culpritId ?? 'player',
    culpritAppearance: overrides.culpritAppearance ?? CULPRIT_APPEARANCE,
    ...(overrides.victimId !== undefined ? { victimId: overrides.victimId } : {}),
    ...(overrides.victimConscious !== undefined ? { victimConscious: overrides.victimConscious } : {}),
    ...(overrides.stolenOrDamaged !== undefined ? { stolenOrDamaged: overrides.stolenOrDamaged } : {}),
  };
}

function makeBystandersAround(
  world: World,
  ids: readonly Id[],
  salt: string,
  radius = 30,
): Bystander[] {
  return ids.map((id) => {
    const angle = rand(world.seed, 'ang', salt, id) * Math.PI * 2;
    const dist = 2 + rand(world.seed, 'dist', salt, id) * radius;
    return {
      person: world.people[id]!,
      position: { x: Math.cos(angle) * dist, y: Math.sin(angle) * dist },
    };
  });
}

describe('src/crime — witnesses, memory, word of mouth & police', () => {
  it('determinism: same seed & inputs yield identical state across runs and JSON round-trip', () => {
    const run = (roundTripAtDay?: number) => {
      const { world, townByPerson, workplaceByPerson } = buildTestWorld('det-seed', 80, 1990, 1);
      const ctx: CrimeContext = {
        townOf: (id) => townByPerson[id]!,
        workplaceOf: (id) => workplaceByPerson[id],
        jurisdictionOf: () => 'police:town0',
      };
      const ids = Object.keys(world.people).sort();
      const crime = makeCrime({
        id: 'crime:det:1',
        kind: 'robbery',
        victimId: ids[0]!,
        when: { year: 1990, day: 1, hour: 13 },
      });
      const bystanders = makeBystandersAround(world, ids.slice(0, 18), 'det', 24);
      const sightings = witnessesOf(crime, bystanders, { seed: world.seed, world });

      let state = createCrimeState(world.seed, 0);
      state = recordCrime(state, crime, sightings);
      for (let d = 2; d <= 12; d++) {
        state = advanceDay(state, world, d, ctx);
        if (roundTripAtDay === d) {
          state = JSON.parse(JSON.stringify(state));
        }
      }
      return state;
    };

    const runA = run();
    const runB = run();
    const runC = run(6);

    expect(runA).toEqual(runB);
    expect(runA).toEqual(runC);
    expect(JSON.parse(JSON.stringify(runA))).toEqual(runA);
  });

  it('witnessing: 2 a.m. unlit alley has fewer & weaker witnesses than noon Main Street, street lamps & canSee work, and conscious victims always witness', () => {
    const { world } = buildTestWorld('wit-seed', 50, 1990, 1);
    const ids = Object.keys(world.people).sort();
    const bystanders = makeBystandersAround(world, ids.slice(1, 41), 'crowd', 32);

    const alleyCrime = makeCrime({
      id: 'crime:alley',
      kind: 'robbery',
      when: { year: 1990, day: 1, hour: 2 },
      victimId: ids[0]!,
    });
    const noonCrime = makeCrime({
      id: 'crime:noon',
      kind: 'robbery',
      when: { year: 1990, day: 1, hour: 12 },
      victimId: ids[0]!,
    });

    const alleyUnlit = witnessesOf(alleyCrime, bystanders, { seed: world.seed, world });
    const alleyLit = witnessesOf(alleyCrime, bystanders, {
      seed: world.seed,
      world,
      streetLamps: [{ position: { x: 0, y: 0 }, radius: 24, intensity: 0.85 }],
    });
    const noonSightings = witnessesOf(noonCrime, bystanders, { seed: world.seed, world });

    const avgConf = (list: readonly Sighting[]) =>
      list.length === 0 ? 0 : list.reduce((sum, s) => sum + s.confidence, 0) / list.length;

    expect(ambientLightAt(2, { x: 0, y: 0 })).toBeLessThan(0.15);
    expect(ambientLightAt(12, { x: 0, y: 0 })).toBe(1);
    expect(alleyUnlit.length).toBeLessThan(noonSightings.length);
    expect(avgConf(alleyUnlit)).toBeLessThan(avgConf(noonSightings));
    expect(alleyLit.length).toBeGreaterThanOrEqual(alleyUnlit.length);
    expect(avgConf(alleyLit)).toBeGreaterThan(avgConf(alleyUnlit));

    // Line-of-sight stub blocks occluded bystanders while conscious victim still witnesses
    const blocked = witnessesOf(noonCrime, bystanders, {
      seed: world.seed,
      world,
      canSee: () => false,
    });
    expect(blocked.length).toBe(1);
    expect(blocked[0]!.witnessId).toBe(ids[0]);
    expect(blocked[0]!.isVictim).toBe(true);

    // Murdered / unconscious victim is NOT a witness
    const murder = makeCrime({
      id: 'crime:murder',
      kind: 'murder',
      when: { year: 1990, day: 1, hour: 12 },
      victimId: ids[0]!,
    });
    const murderBlocked = witnessesOf(murder, bystanders, {
      seed: world.seed,
      world,
      canSee: () => false,
    });
    expect(murderBlocked.length).toBe(0);
  });

  it('memory: shoplifting fades to nothing within weeks while murder persists, clothing drops before height, and refreshes restore confidence', () => {
    const baseSighting: Sighting = {
      crimeId: 'crime:mem',
      witnessId: 'p:0001',
      isVictim: false,
      confidence: 0.72,
      initialConfidence: 0.72,
      dayWitnessed: 0,
      lastRefreshedDay: 0,
      description: {
        height: 'tall',
        build: 'heavy',
        hair: 'red',
        clothingColor: 'navy',
        vehicle: { kind: 'sedan', color: 'green' },
      },
      refreshes: 0,
    };

    const shopDay28 = decaySighting(baseSighting, DEFAULT_SEVERITY.shoplifting, 28);
    expect(shopDay28).toBeNull();

    const murderDay90 = decaySighting(baseSighting, DEFAULT_SEVERITY.murder, 90);
    const murderDay365 = decaySighting(baseSighting, DEFAULT_SEVERITY.murder, 365);
    expect(murderDay90).not.toBeNull();
    expect(murderDay365).not.toBeNull();
    expect(murderDay365!.confidence).toBeGreaterThanOrEqual(0.18);

    // Least-striking detail (clothingColor) drops before permanent traits (height)
    const burglaryDay35 = decaySighting(baseSighting, DEFAULT_SEVERITY.burglary, 35);
    expect(burglaryDay35).not.toBeNull();
    expect(burglaryDay35!.description.clothingColor).toBeUndefined();
    expect(burglaryDay35!.description.height).toBe('tall');

    // Refreshing on re-sighting or police interview restores confidence
    const faded = decaySighting(baseSighting, DEFAULT_SEVERITY.robbery, 30)!;
    const afterInterview = refreshSighting(faded, 30, 'police_interview');
    const afterResight = refreshSighting(faded, 30, 'saw_culprit');
    expect(afterInterview.confidence).toBeGreaterThan(faded.confidence);
    expect(afterResight.confidence).toBeGreaterThan(afterInterview.confidence);
    expect(afterResight.refreshes).toBe(1);

    // State-level refreshWitnessMemory works purely
    const crime = makeCrime({ id: 'crime:mem', kind: 'robbery', when: { year: 1990, day: 0, hour: 12 } });
    const st0 = recordCrime(createCrimeState('mem-seed', 0), crime, [faded]);
    const st1 = refreshWitnessMemory(st0, 'crime:mem', 'p:0001', 30, 'saw_culprit');
    expect(st1.ledger['crime:mem']!['p:0001']!.confidence).toBeGreaterThan(faded.confidence);
    expect(st0.ledger['crime:mem']!['p:0001']!.confidence).toBe(faded.confidence);
  });

  it('gossip: 200-person town robbery curve, friend shielding, and second-hand degradation', () => {
    const { world, townByPerson, workplaceByPerson } = buildTestWorld('gossip-200', 200, 1990, 1);
    const ctx: CrimeContext = {
      townOf: (id) => townByPerson[id]!,
      workplaceOf: (id) => workplaceByPerson[id],
      jurisdictionOf: () => 'police:town0',
    };
    const ids = Object.keys(world.people).sort();
    const crime = makeCrime({
      id: 'crime:town200:robbery',
      kind: 'robbery',
      victimId: ids[0]!,
      when: { year: 1990, day: 0, hour: 13 },
    });

    const bystanders = makeBystandersAround(world, ids.slice(0, 12), 'rob200', 18);
    const sightings = witnessesOf(crime, bystanders, { seed: world.seed, world });
    let state = recordCrime(createCrimeState(world.seed, 0), crime, sightings);

    const counts: Record<number, number> = { 0: knowersOf(state, crime.id).length };
    for (let d = 1; d <= 30; d++) {
      state = advanceDay(state, world, d, ctx);
      if (d === 1 || d === 3 || d === 7 || d === 14 || d === 30) {
        counts[d] = knowersOf(state, crime.id).length;
      }
    }

    const fmt = (d: number) => `${counts[d]}/200 (${((counts[d]! / 200) * 100).toFixed(1)}%)`;
    console.log(
      `GOSSIP_CURVE_200_ROBBERY: day 0: ${fmt(0)} | day 1: ${fmt(1)} | day 3: ${fmt(3)} | day 7: ${fmt(7)} | day 14: ${fmt(14)} | day 30: ${fmt(30)}`,
    );

    expect(counts[0]!).toBeGreaterThanOrEqual(2);
    expect(counts[1]!).toBeGreaterThan(counts[0]!);
    expect(counts[7]!).toBeGreaterThan(counts[1]!);
    expect(counts[7]!).toBeGreaterThanOrEqual(15);
    expect(counts[30]!).toBeGreaterThanOrEqual(counts[7]!);
    expect(counts[30]!).toBeLessThan(195); // plausible share, not instant 100% hive mind

    // Second-hand accounts strictly have lower initialConfidence than their teller's first-hand sighting
    const allKnowers = knowersOf(state, crime.id);
    const firstHand = allKnowers.filter((k) => k.source === 'first_hand');
    const secondHand = allKnowers.filter((k) => k.source === 'second_hand');
    expect(firstHand.length).toBeGreaterThan(0);
    expect(secondHand.length).toBeGreaterThan(0);
    const avgFirstInit = firstHand.reduce((s, a) => s + a.initialConfidence, 0) / firstHand.length;
    const avgSecondInit = secondHand.reduce((s, a) => s + a.initialConfidence, 0) / secondHand.length;
    expect(avgSecondInit).toBeLessThan(avgFirstInit);
    expect(secondHand.every((a) => a.hops >= 1 && a.heardFrom !== undefined)).toBe(true);

    // Close friend of culprit shields them compared to a neutral stranger
    const culpritId = ids[10]!;
    const friendId = ids[11]!;
    const strangerId = ids[50]!;
    world.bonds[pairKey(culpritId, friendId)] = 1.2;
    // Normalize reputation/traits so comparison isolates friendship shielding
    const friend = { ...world.people[friendId]!, reputation: 0.4, traits: { ambition: 0.5, charm: 0.5, diligence: 0.9 } };
    const stranger = { ...world.people[strangerId]!, reputation: 0.4, traits: { ambition: 0.5, charm: 0.5, diligence: 0.9 } };
    world.people[friendId] = friend;
    world.people[strangerId] = stranger;

    const friendCrime = makeCrime({
      id: 'crime:friend_shield',
      kind: 'burglary',
      culpritId,
    });
    const sharedAcc: CrimeAccount = {
      crimeId: friendCrime.id,
      knowerId: friendId,
      source: 'first_hand',
      isVictim: false,
      hops: 0,
      dayLearned: 0,
      lastRefreshedDay: 0,
      confidence: 0.85,
      initialConfidence: 0.85,
      identifiedCulpritId: culpritId,
      description: CULPRIT_APPEARANCE,
      reported: false,
      refreshes: 0,
    };

    const pFriend = reportingProbability(world.seed, world, friendCrime, sharedAcc, friend, 0.99);
    const pStranger = reportingProbability(
      world.seed,
      world,
      friendCrime,
      { ...sharedAcc, knowerId: strangerId },
      stranger,
      0.99,
    );
    expect(pFriend).toBeLessThan(pStranger * 0.45);
  });

  it('jurisdiction: Town A police know and Town B do not, unless a witness travels or has kin in Town B', () => {
    const runScenario = (mode: 'isolated' | 'traveler' | 'kin') => {
      const { world, townByPerson, workplaceByPerson } = buildTestWorld(`jur-${mode}`, 80, 1990, 2);
      const ids = Object.keys(world.people).sort();
      const townAIds = ids.filter((id) => townByPerson[id] === 'town:0');
      const townBIds = ids.filter((id) => townByPerson[id] === 'town:1');

      const witnessA = townAIds[1]!;
      const kinB = townBIds[0]!;
      if (mode === 'kin') {
        // Make witnessA and kinB parent/child across towns (and cooperative with police)
        world.people[witnessA]!.children.push(kinB);
        world.people[kinB]!.parents = [witnessA];
        world.people[witnessA]!.traits.charm = 0.95;
        world.people[kinB]!.reputation = 0.9;
        world.people[kinB]!.traits.diligence = 0.95;
      }

      const ctx: CrimeContext = {
        townOf: (id) => townByPerson[id]!,
        workplaceOf: (id) => workplaceByPerson[id],
        jurisdictionOf: (placeOrTown) =>
          placeOrTown.startsWith('town:1') ? 'police:townB' : 'police:townA',
        placeTownOf: (placeId) => placeId.split(':').slice(0, 2).join(':'),
        ...(mode === 'traveler'
          ? {
              travelTownsOf: (pid, day) => (pid === witnessA && day >= 2 && day <= 6 ? ['town:1'] : []),
            }
          : {}),
      };

      const crime = makeCrime({
        id: `crime:jur:${mode}`,
        kind: 'robbery',
        placeId: 'town:0:bank',
        townId: 'town:0',
        victimId: townAIds[0]!,
        when: { year: 1990, day: 0, hour: 12 },
      });

      const bystanders: Bystander[] = townAIds.slice(0, 10).map((id, idx) => ({
        person: world.people[id]!,
        position: { x: idx * 2, y: 0 },
        attention: 0.9,
      }));
      const sightings = witnessesOf(crime, bystanders, { seed: world.seed, world });
      let state = recordCrime(createCrimeState(world.seed, 0), crime, sightings);

      for (let d = 1; d <= 14; d++) {
        state = advanceDay(state, world, d, ctx);
      }

      const knowA = policeKnowledge(state, 'police:townA', 14);
      const knowB = policeKnowledge(state, 'police:townB', 14);
      const townBKnowers = knowersOf(state, crime.id).filter((a) => townByPerson[a.knowerId] === 'town:1');
      return { state, knowA, knowB, townBKnowers };
    };

    const iso = runScenario('isolated');
    expect(iso.knowA.cases.length).toBe(1);
    expect(iso.knowA.heat).toBeGreaterThan(15);
    expect(isWanted(iso.state, 'player', 'police:townA', 14)).toBe(true);
    expect(iso.knowB.cases.length).toBe(0);
    expect(iso.knowB.heat).toBe(0);
    expect(isWanted(iso.state, 'player', 'police:townB', 14)).toBe(false);
    expect(iso.townBKnowers.length).toBe(0);

    const trav = runScenario('traveler');
    expect(trav.townBKnowers.length).toBeGreaterThan(0);
    expect(trav.knowB.cases.length).toBe(1);
    expect(isWanted(trav.state, 'player', 'police:townB', 14)).toBe(true);

    const kin = runScenario('kin');
    expect(kin.townBKnowers.length).toBeGreaterThan(0);
    expect(kin.knowB.cases.length).toBe(1);
    expect(isWanted(kin.state, 'player', 'police:townB', 14)).toBe(true);
  });

  it('era: reports arrive measurably slower in 1880 (no phones) than in 1990 (telephones)', () => {
    const runEra = (year: number) => {
      const { world, townByPerson, workplaceByPerson } = buildTestWorld('era-seed', 50, year, 1);
      const ctx: CrimeContext = {
        year,
        townOf: (id) => townByPerson[id]!,
        workplaceOf: (id) => workplaceByPerson[id],
        jurisdictionOf: () => 'police:town0',
        stationDistanceKm: () => 4.0,
      };
      const ids = Object.keys(world.people).sort();
      const crime = makeCrime({
        id: `crime:era:${year}`,
        kind: 'robbery',
        victimId: ids[0]!,
        when: { year, day: 10, hour: 12 },
      });
      const bystanders = ids.slice(0, 14).map((id, idx) => ({
        person: world.people[id]!,
        position: { x: idx * 1.5, y: 0 },
        attention: 0.9,
      }));
      const sightings = witnessesOf(crime, bystanders, { seed: world.seed, world });
      let state = recordCrime(createCrimeState(world.seed, 10), crime, sightings);

      let firstArrivalDay = -1;
      for (let d = 10; d <= 18; d++) {
        state = advanceDay(state, world, d, ctx);
        const pk = policeKnowledge(state, 'police:town0', d);
        if (firstArrivalDay === -1 && pk.cases.length > 0) {
          firstArrivalDay = d;
        }
      }

      const reports = state.jurisdictions['police:town0']?.cases[crime.id]?.reports ?? [];
      const avgDelay =
        reports.length > 0 ? reports.reduce((s, r) => s + r.delayHours, 0) / reports.length : 999;
      const sameDay = reports.filter((r) => r.receivedDay === r.filedDay).length;
      return { firstArrivalDay, avgDelay, sameDay, reportCount: reports.length };
    };

    const e1880 = runEra(1880);
    const e1990 = runEra(1990);
    console.log(
      `ERA_COMPARISON: 1880 firstArrivalDay=${e1880.firstArrivalDay} avgDelay=${e1880.avgDelay.toFixed(1)}h sameDay=${e1880.sameDay} | 1990 firstArrivalDay=${e1990.firstArrivalDay} avgDelay=${e1990.avgDelay.toFixed(2)}h sameDay=${e1990.sameDay}`,
    );

    expect(e1880.reportCount).toBeGreaterThan(0);
    expect(e1990.reportCount).toBeGreaterThan(0);
    expect(e1880.firstArrivalDay).toBeGreaterThan(e1990.firstArrivalDay);
    expect(e1880.avgDelay).toBeGreaterThan(e1990.avgDelay * 5);
    expect(e1880.sameDay).toBe(0);
    expect(e1990.sameDay).toBeGreaterThan(0);
  });

  it('police merging weights first-hand over second-hand, heat decays, and disguise lowers recognition', () => {
    const firstHand: PoliceReport = {
      id: 'r1',
      crimeId: 'c1',
      reporterId: 'p:0001',
      jurisdictionId: 'police:town0',
      filedDay: 1,
      receivedDay: 1,
      delayHours: 0.4,
      source: 'first_hand',
      isVictim: true,
      confidence: 0.90,
      description: {
        height: 'tall',
        build: 'heavy',
        hair: 'red',
        clothingColor: 'navy',
        vehicle: { kind: 'sedan', color: 'green' },
      },
    };
    const rumor1: PoliceReport = {
      ...firstHand,
      id: 'r2',
      reporterId: 'p:0002',
      source: 'second_hand',
      isVictim: false,
      confidence: 0.55,
      description: { height: 'short', build: 'slim', hair: 'blonde', clothingColor: 'white' },
    };
    const rumor2: PoliceReport = { ...rumor1, id: 'r3', reporterId: 'p:0003' };

    const merged = mergeDescriptions([firstHand, rumor1, rumor2]);
    expect(merged.description.height).toBe('tall');
    expect(merged.description.hair).toBe('red');
    expect(merged.description.clothingColor).toBe('navy');
    expect(merged.overallConfidence).toBeGreaterThan(0.5);

    const { world, townByPerson } = buildTestWorld('rec-seed', 40, 1990, 1);
    const ids = Object.keys(world.people).sort();
    const ctx: CrimeContext = {
      townOf: (id) => townByPerson[id]!,
      jurisdictionOf: () => 'police:town0',
    };
    const crime = makeCrime({
      id: 'crime:rec',
      kind: 'robbery',
      victimId: ids[0]!,
      when: { year: 1990, day: 0, hour: 12 },
    });
    const sightings = witnessesOf(
      crime,
      ids.slice(0, 6).map((id, idx) => ({
        person: world.people[id]!,
        position: { x: idx * 2, y: 0 },
        attention: 0.95,
      })),
      { seed: world.seed, world },
    );
    let st = recordCrime(createCrimeState(world.seed, 0), crime, sightings);
    for (let d = 1; d <= 3; d++) st = advanceDay(st, world, d, ctx);

    const heatDay3 = policeKnowledge(st, 'police:town0', 3).heat;
    const heatDay90 = policeKnowledge(st, 'police:town0', 90).heat;
    expect(heatDay3).toBeGreaterThan(20);
    expect(heatDay90).toBeLessThan(heatDay3 * 0.5);

    const officerId = ids[25]!;
    const sameLook = recognitionChance(
      st,
      world,
      officerId,
      { id: 'player', appearance: CULPRIT_APPEARANCE },
      3,
      { jurisdictionId: 'police:town0', isPolice: true },
    );
    const disguisedLook = recognitionChance(
      st,
      world,
      officerId,
      {
        id: 'player',
        appearance: { ...CULPRIT_APPEARANCE, clothingColor: 'tan', vehicle: undefined },
      },
      3,
      { jurisdictionId: 'police:town0', isPolice: true },
    );
    const otherTownOfficer = recognitionChance(
      st,
      world,
      'officer:other_continent',
      { id: 'player', appearance: CULPRIT_APPEARANCE },
      3,
      { jurisdictionId: 'police:other_continent', isPolice: true },
    );

    expect(sameLook).toBeGreaterThan(disguisedLook);
    expect(disguisedLook).toBeGreaterThan(0.15); // permanent traits (height/build/hair) still carry risk
    expect(otherTownOfficer).toBe(0); // fleeing to another jurisdiction works
    expect(appearanceMatchScore(CULPRIT_APPEARANCE, CULPRIT_APPEARANCE)).toBeGreaterThan(0.95);
  });

  it('jurisdiction escalation and state immutability: murder escalates to state police while shoplifting stays local', () => {
    const { world, townByPerson } = buildTestWorld('esc-seed', 30, 1990, 1);
    const ids = Object.keys(world.people).sort();
    const ctx: CrimeContext = {
      townOf: (id) => townByPerson[id]!,
      jurisdictionOf: () => 'police:town0',
      escalateJurisdictionOf: (_jur, c) => (c.severity >= 0.9 ? 'police:state' : undefined),
    };

    const murder = makeCrime({
      id: 'crime:esc:murder',
      kind: 'murder',
      victimId: ids[0]!,
      when: { year: 1990, day: 0, hour: 12 },
    });
    const shop = makeCrime({
      id: 'crime:esc:shop',
      kind: 'shoplifting',
      victimId: ids[1]!,
      when: { year: 1990, day: 0, hour: 12 },
    });

    const bystanders = ids.slice(2, 12).map((id, idx) => ({
      person: world.people[id]!,
      position: { x: idx * 1.5, y: 0 },
      attention: 0.95,
    }));

    let st = createCrimeState(world.seed, 0);
    st = recordCrime(st, murder, witnessesOf(murder, bystanders, { seed: world.seed, world }));
    st = recordCrime(st, shop, witnessesOf(shop, bystanders, { seed: world.seed, world }));
    const snapshotBefore = JSON.stringify(st);

    let next = st;
    for (let d = 1; d <= 4; d++) next = advanceDay(next, world, d, ctx);

    // Input state was never mutated
    expect(JSON.stringify(st)).toBe(snapshotBefore);

    const statePolice = policeKnowledge(next, 'police:state', 4);
    expect(statePolice.cases.some((c) => c.crimeId === murder.id)).toBe(true);
    expect(statePolice.cases.some((c) => c.crimeId === shop.id)).toBe(false);
  });

  it('spouse shielding: culprit spouse reports far less often than an unbiased stranger', () => {
    const { world } = buildTestWorld('spouse-seed', 20, 1990, 1);
    const ids = Object.keys(world.people).sort();
    const culpritId = ids[0]!;
    const spouseId = world.people[culpritId]!.spouse!;
    const strangerId = ids[12]!;

    world.people[spouseId] = {
      ...world.people[spouseId]!,
      reputation: 0.5,
      traits: { ambition: 0.5, charm: 0.5, diligence: 0.8 },
    };
    world.people[strangerId] = {
      ...world.people[strangerId]!,
      reputation: 0.5,
      traits: { ambition: 0.5, charm: 0.5, diligence: 0.8 },
    };

    const crime = makeCrime({ id: 'crime:spouse', kind: 'robbery', culpritId, victimId: ids[15]! });
    const baseAcc: CrimeAccount = {
      crimeId: crime.id,
      knowerId: spouseId,
      source: 'first_hand',
      isVictim: false,
      hops: 0,
      dayLearned: 0,
      lastRefreshedDay: 0,
      confidence: 0.85,
      initialConfidence: 0.85,
      identifiedCulpritId: culpritId,
      description: CULPRIT_APPEARANCE,
      reported: false,
      refreshes: 0,
    };

    const pSpouse = reportingProbability(world.seed, world, crime, baseAcc, world.people[spouseId]!, 0.99);
    const pStranger = reportingProbability(
      world.seed,
      world,
      crime,
      { ...baseAcc, knowerId: strangerId },
      world.people[strangerId]!,
      0.99,
    );
    expect(pSpouse).toBeLessThan(pStranger * 0.15);
  });

  it('performance: advanceDay for 2,000 people and 20 active cases runs under 5 ms', () => {
    const { world, townByPerson, workplaceByPerson } = buildTestWorld('perf-2000', 2000, 1990, 4);
    const ctx: CrimeContext = {
      townOf: (id) => townByPerson[id]!,
      workplaceOf: (id) => workplaceByPerson[id],
      jurisdictionOf: (placeId) => `police:${placeId.split(':')[0] ?? 'town0'}`,
      placeTownOf: (placeId) => placeId.split(':').slice(0, 2).join(':'),
    };
    const ids = Object.keys(world.people).sort();

    let state = createCrimeState(world.seed, 0);
    for (let c = 0; c < 20; c++) {
      const townIdx = c % 4;
      const offset = townIdx * 500 + ((c * 19) % 450);
      const victimId = ids[offset]!;
      const kind = (['robbery', 'burglary', 'theft', 'assault', 'shoplifting'] as const)[c % 5]!;
      const crime = makeCrime({
        id: `crime:perf:${c}`,
        kind,
        placeId: `town:${townIdx}:st${c}`,
        townId: `town:${townIdx}`,
        victimId,
        when: { year: 1990, day: 0, hour: 12 },
      });
      const localSlice = ids.slice(offset, Math.min(ids.length, offset + 10));
      const bystanders = makeBystandersAround(world, localSlice, `perf:${c}`, 20);
      const sightings = witnessesOf(crime, bystanders, { seed: world.seed, world });
      state = recordCrime(state, crime, sightings);
    }

    // Warm up 10 simulated days so gossip has spread across ~1,000+ ledger entries & 20 cases
    for (let d = 1; d <= 10; d++) {
      state = advanceDay(state, world, d, ctx);
    }

    // Measure 10 consecutive advanceDay ticks
    const timesMs: number[] = [];
    for (let d = 11; d <= 20; d++) {
      const t0 = performance.now();
      state = advanceDay(state, world, d, ctx);
      timesMs.push(performance.now() - t0);
    }

    const sorted = [...timesMs].sort((a, b) => a - b);
    const medianMs = sorted[Math.floor(sorted.length / 2)]!;
    const meanMs = timesMs.reduce((a, b) => a + b, 0) / timesMs.length;
    const totalKnowers = Object.values(state.ledger).reduce((s, m) => s + Object.keys(m).length, 0);

    console.log(
      `PERF_2000x20: median=${medianMs.toFixed(2)}ms mean=${meanMs.toFixed(2)}ms max=${sorted[sorted.length - 1]!.toFixed(2)}ms activeLedgerEntries=${totalKnowers}`,
    );

    expect(totalKnowers).toBeGreaterThan(200);
    expect(medianMs).toBeLessThan(5.0);
  });
});
