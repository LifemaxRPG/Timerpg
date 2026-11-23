import { describe, expect, it } from 'vitest';
import {
  collectCrimeTraces,
  createCrimeState,
  enrollOnFile,
  ingestPoliceReport,
  leaveTraces,
  recordCrime,
  recordTraces,
  reexamineColdCases,
  type CrimeEvent,
  type CrimeState,
  type CulpritAppearance,
  type EvidenceMatch,
  type PoliceCase,
  type PoliceReport,
} from '../crime';
import { rand } from '../sim/rng';
import { pairKey } from '../sim/social';
import type { Id, Person, World } from '../sim/types';
import {
  chargesFromCase,
  commitTrialInterference,
  evaluateBail,
  fileAppeal,
  hireDefenseLawyer,
  makePleaOffer,
  runRetrial,
  runTrial,
  selectJury,
} from './index';

const FIRST_NAMES = [
  'Arthur', 'Clara', 'George', 'Helen', 'Walter', 'Martha', 'Henry', 'Ruth',
  'Frank', 'Evelyn', 'Harold', 'Alice', 'Albert', 'Dorothy', 'Raymond', 'Frances',
  'Victor', 'Florence', 'Martin', 'Margaret', 'Samuel', 'Beatrice', 'Daniel', 'Eleanor',
  'Peter', 'Lillian', 'Thomas', 'Hazel', 'Edward', 'Nora', 'Joseph', 'Grace',
  'Louis', 'Irene', 'Charles', 'Mildred', 'Philip', 'Edith', 'Robert', 'Mabel',
];

const LAST_NAMES = [
  'Hale', 'Finch', 'Kowalski', 'Vance', 'Bell', 'Miller', 'Carter', 'Brooks',
  'Bennett', 'Hayes', 'Turner', 'Parker', 'Collins', 'Foster', 'Howard', 'Ward',
  'Cooper', 'Reed', 'Bailey', 'Price', 'Morgan', 'Graham', 'Perry', 'Ellis',
];

function makeTestTown(seed: string, year: number, count = 48): {
  world: World;
  defendantId: Id;
  realCulpritId: Id;
  victimId: Id;
  victimSpouseId: Id;
  victimFriendIds: Id[];
  witnessIds: Id[];
  bystanderId: Id;
} {
  const people: Record<Id, Person> = {};
  const bonds: Record<string, number> = {};

  const addPerson = (
    id: Id,
    first: string,
    surname: string,
    sex: 'M' | 'F',
    age: number,
    traits?: { ambition: number; charm: number; diligence: number },
    extra?: Partial<Person>,
  ) => {
    people[id] = {
      id,
      first,
      surname,
      sex,
      birthYear: year - age,
      traits: traits ?? {
        ambition: 0.25 + rand(seed, 'amb', id) * 0.55,
        charm: 0.25 + rand(seed, 'chm', id) * 0.55,
        diligence: 0.25 + rand(seed, 'dil', id) * 0.55,
      },
      parents: [],
      children: [],
      wealth: 80,
      reputation: 0.1,
      ...extra,
    };
  };

  const defendantId = 'p:defendant';
  const realCulpritId = 'p:real_culprit';
  const victimId = 'p:victim';
  const victimSpouseId = 'p:victim_spouse';
  const victimFriendIds = ['p:victim_friend_1', 'p:victim_friend_2', 'p:victim_friend_3'];
  const witnessIds = ['p:witness_1', 'p:witness_2'];
  const bystanderId = 'p:court_bailiff';

  addPerson(defendantId, 'Alex', 'Mercer', 'M', 28, { ambition: 0.5, charm: 0.5, diligence: 0.5 }, { wealth: 200, reputation: 0.0 });
  addPerson(realCulpritId, 'Victor', 'Sloane', 'M', 34, { ambition: 0.7, charm: 0.4, diligence: 0.3 }, { wealth: 120, reputation: -0.3 });
  addPerson(victimId, 'Marcus', 'Hale', 'M', 48, { ambition: 0.6, charm: 0.7, diligence: 0.8 }, { spouse: victimSpouseId });
  addPerson(victimSpouseId, 'Helen', 'Hale', 'F', 46, { ambition: 0.5, charm: 0.7, diligence: 0.8 }, { spouse: victimId });

  for (let i = 0; i < victimFriendIds.length; i++) {
    const fid = victimFriendIds[i]!;
    addPerson(fid, FIRST_NAMES[i]!, LAST_NAMES[i]!, i % 2 === 0 ? 'M' : 'F', 35 + i * 4);
    bonds[pairKey(fid, victimId)] = 0.85;
  }

  addPerson(witnessIds[0]!, 'Martha', 'Finch', 'F', 44, { ambition: 0.4, charm: 0.7, diligence: 0.8 });
  addPerson(witnessIds[1]!, 'Ed', 'Kowalski', 'M', 49, { ambition: 0.5, charm: 0.6, diligence: 0.75 });
  addPerson(bystanderId, 'Frank', 'Ward', 'M', 41, { ambition: 0.4, charm: 0.5, diligence: 0.88 });

  for (let i = 0; i < count; i++) {
    const id = `p:citizen_${String(i).padStart(3, '0')}`;
    addPerson(
      id,
      FIRST_NAMES[i % FIRST_NAMES.length]!,
      LAST_NAMES[i % LAST_NAMES.length]!,
      i % 2 === 0 ? 'M' : 'F',
      21 + (i % 45),
    );
  }

  return {
    world: { seed, year, people, businesses: {}, bonds },
    defendantId,
    realCulpritId,
    victimId,
    victimSpouseId,
    victimFriendIds,
    witnessIds,
    bystanderId,
  };
}

function makeRobberyCase(params: {
  seed: string;
  world: World;
  crimeId: string;
  year: number;
  day: number;
  culpritId: Id;
  victimId: Id;
  witnessIds: Id[];
  jurisdictionId: string;
  withPrints?: boolean;
  withDna?: boolean;
  withCamera?: boolean;
  appearance?: CulpritAppearance;
}): { crimeState: CrimeState; policeCase: PoliceCase } {
  const appearance: CulpritAppearance = params.appearance ?? {
    height: 'tall',
    build: 'average',
    hair: 'dark',
    clothingColor: 'black',
  };
  const event: CrimeEvent = {
    id: params.crimeId,
    kind: 'robbery',
    severity: 0.82,
    placeId: 'place:bank',
    position: { x: 20, y: 10 },
    when: { year: params.year, day: params.day, hour: 14 },
    culpritId: params.culpritId,
    culpritAppearance: appearance,
    victimId: params.victimId,
    victimConscious: true,
    stolenOrDamaged: { item: 'cash box', value: 1200 },
  };

  let state = createCrimeState(params.seed, params.day);
  state = recordCrime(state, event, []);

  // File witness reports
  for (let i = 0; i < params.witnessIds.length; i++) {
    const wid = params.witnessIds[i]!;
    const report: PoliceReport = {
      id: `rep:${params.crimeId}:${i}`,
      crimeId: params.crimeId,
      reporterId: wid,
      jurisdictionId: params.jurisdictionId,
      filedDay: params.day,
      receivedDay: params.day,
      delayHours: 0.5,
      source: 'first_hand',
      isVictim: wid === params.victimId,
      confidence: 0.86,
      identifiedCulpritId: params.culpritId,
      description: {
        height: appearance.height,
        build: appearance.build,
        hair: appearance.hair,
        clothingColor: appearance.clothingColor,
      },
    };
    state = {
      ...state,
      jurisdictions: {
        ...state.jurisdictions,
        [params.jurisdictionId]: ingestPoliceReport(
          state.jurisdictions[params.jurisdictionId],
          event,
          report,
        ),
      },
    };
  }

  // Leave and collect physical traces if requested
  if (params.withPrints || params.withDna || params.withCamera) {
    const traces = leaveTraces(event, {
      seed: params.seed,
      gloved: !params.withPrints,
      bloodSpilled: Boolean(params.withDna),
      cameras: params.withCamera
        ? [{ id: 'cam:bank_1', position: { x: 22, y: 11 }, radius: 20 }]
        : [],
    });
    state = recordTraces(state, params.crimeId, traces);
    state = enrollOnFile(state, {
      id: `file:${params.culpritId}`,
      personId: params.culpritId,
      jurisdictionId: params.jurisdictionId,
      scope: 'national',
      reason: 'arrest',
      enrolledYear: params.year - 1,
      enrolledDay: 1,
      hasPrints: Boolean(params.withPrints),
      hasDna: Boolean(params.withDna),
    });
    state = collectCrimeTraces(state, params.world, params.crimeId, params.jurisdictionId, params.day, {
      year: params.year,
    });
  }

  return {
    crimeState: state,
    policeCase: state.jurisdictions[params.jurisdictionId]!.cases[params.crimeId]!,
  };
}

describe('src/court — trials, juries, verdicts, tampering & exoneration', () => {
  it('convicts on a strong-evidence robbery with a sentence inside the era range', () => {
    const year = 1995;
    const town = makeTestTown('strong-robbery', year);
    const { crimeState, policeCase } = makeRobberyCase({
      seed: 'strong-robbery',
      world: town.world,
      crimeId: 'crime:rob_1',
      year,
      day: 10,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: [town.victimId, ...town.witnessIds],
      jurisdictionId: 'jur:capital',
      withPrints: true,
      withDna: true,
    });

    const outcome = runTrial({
      seed: 'strong-robbery',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 25,
      lawyerTier: 'local_attorney',
      priorConvictions: 1,
    });

    expect(outcome.jurySelection.seated).toHaveLength(12);
    expect(outcome.verdict).toBe('guilty');
    expect(outcome.guiltyVotes).toBe(12);
    expect(outcome.sentence).toBeDefined();

    const statute = outcome.pretrial.charges[0]!.statute;
    expect(outcome.sentence!.jailDays).toBeGreaterThanOrEqual(statute.minJailDays);
    expect(outcome.sentence!.jailDays).toBeLessThanOrEqual(statute.maxJailDays);
    expect(outcome.sentence!.fineAmount).toBeGreaterThanOrEqual(statute.minFine);
    expect(outcome.sentence!.fineAmount).toBeLessThanOrEqual(statute.maxFine);
    expect(outcome.events.some((e) => e.kind === 'prosecution_evidence')).toBe(true);
  });

  it('acquits when a single mistaken eyewitness conflicts with exculpatory DNA', () => {
    const year = 1996;
    const town = makeTestTown('mistaken-eyewitness-dna', year);
    const actualAppearance: CulpritAppearance = {
      height: 'tall',
      build: 'heavy',
      hair: 'blonde',
      clothingColor: 'red',
    };
    const crimeEvent: CrimeEvent = {
      id: 'crime:wrong_id',
      kind: 'robbery',
      severity: 0.78,
      placeId: 'place:alley',
      position: { x: 10, y: 10 },
      when: { year, day: 5, hour: 21 },
      culpritId: town.realCulpritId,
      culpritAppearance: actualAppearance,
      victimId: town.victimId,
      victimConscious: true,
    };

    let state = createCrimeState('mistaken-eyewitness-dna', 5);
    state = recordCrime(state, crimeEvent, []);

    // Single eyewitness mistakes hair & clothingColor and wrongly names the defendant!
    const mistakenReport: PoliceReport = {
      id: 'rep:mistaken:1',
      crimeId: crimeEvent.id,
      reporterId: town.witnessIds[0]!,
      jurisdictionId: 'jur:capital',
      filedDay: 5,
      receivedDay: 5,
      delayHours: 1,
      source: 'first_hand',
      isVictim: false,
      confidence: 0.62,
      identifiedCulpritId: town.defendantId,
      description: {
        height: 'short',
        build: 'slim',
        hair: 'dark',
        clothingColor: 'blue',
      },
    };
    state = {
      ...state,
      jurisdictions: {
        'jur:capital': ingestPoliceReport(undefined, crimeEvent, mistakenReport),
      },
    };

    // Scene DNA matches the REAL culprit (not defendant), triggering G008 witness_physical_conflict
    const exculpatoryDna: EvidenceMatch = {
      traceId: 'trace:dna_real',
      traceKind: 'dna',
      crimeId: crimeEvent.id,
      suspectId: town.realCulpritId,
      matchedYear: year,
      matchedDay: 8,
      confidence: 0.96,
      method: 'dna_database',
      coldCase: false,
      yearsToMatch: 0,
      impossibleTimeline: false,
      beforeBirth: false,
      beforeEraArrival: false,
      anomalies: [],
    };
    const policeCase: PoliceCase = {
      ...state.jurisdictions['jur:capital']!.cases[crimeEvent.id]!,
      evidenceMatches: [exculpatoryDna],
    };

    const outcome = runTrial({
      seed: 'mistaken-eyewitness-dna',
      world: town.world,
      crimeState: state,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 20,
      lawyerTier: 'public_defender',
      actualCulpritAppearance: actualAppearance,
    });

    // Cross-examination exposed the witness mistakes AND the DNA conflict
    expect(outcome.witnessErrors[0]!.errorCount).toBe(4);
    expect(outcome.witnessErrors[0]!.exposedCount).toBeGreaterThan(0);
    expect(outcome.events.some((e) => e.kind === 'evidence_challenge')).toBe(true);
    expect(outcome.verdict).toBe('not_guilty');
    expect(outcome.notGuiltyVotes).toBe(12);
    expect(outcome.sentence).toBeUndefined();
  });

  it('shows an expensive lawyer measurably raises acquittal odds over many seeds', () => {
    const year = 1978;
    const SEEDS = 60;
    const stats = {
      public_defender: { acq: 0, hung: 0, guilty: 0 },
      local_attorney: { acq: 0, hung: 0, guilty: 0 },
      retained_counsel: { acq: 0, hung: 0, guilty: 0 },
      elite_defense: { acq: 0, hung: 0, guilty: 0 },
    };

    for (let s = 0; s < SEEDS; s++) {
      const seed = `lawyer-odds-seed-${s}`;
      const town = makeTestTown(seed, year, 44);
      const appearance: CulpritAppearance = {
        height: 'average',
        build: 'average',
        hair: 'brown',
        clothingColor: 'navy',
      };
      const crimeEvent: CrimeEvent = {
        id: `crime:borderline:${s}`,
        kind: 'burglary',
        severity: 0.64,
        placeId: 'place:store',
        position: { x: 30, y: 12 },
        when: { year, day: 10, hour: 22 },
        culpritId: town.defendantId,
        culpritAppearance: appearance,
        victimId: town.victimId,
      };

      let state = createCrimeState(seed, 10);
      state = recordCrime(state, crimeEvent, []);

      // Borderline case: two eyewitnesses (one with a mistaken coat color) + partial fingerprint
      const rep1: PoliceReport = {
        id: `rep:b:${s}:1`,
        crimeId: crimeEvent.id,
        reporterId: town.witnessIds[0]!,
        jurisdictionId: 'jur:capital',
        filedDay: 10,
        receivedDay: 10,
        delayHours: 1,
        source: 'first_hand',
        isVictim: false,
        confidence: 0.80,
        identifiedCulpritId: town.defendantId,
        description: {
          height: 'average',
          build: 'average',
          hair: 'brown',
          clothingColor: 'gray', // mistaken coat color!
        },
      };
      const rep2: PoliceReport = {
        id: `rep:b:${s}:2`,
        crimeId: crimeEvent.id,
        reporterId: town.witnessIds[1]!,
        jurisdictionId: 'jur:capital',
        filedDay: 11,
        receivedDay: 11,
        delayHours: 1,
        source: 'first_hand',
        isVictim: false,
        confidence: 0.72,
        identifiedCulpritId: town.defendantId,
        description: {
          height: 'average',
          build: 'slim', // mistaken build!
          hair: 'brown',
          clothingColor: 'navy',
        },
      };
      let jur = ingestPoliceReport(undefined, crimeEvent, rep1);
      jur = ingestPoliceReport(jur, crimeEvent, rep2);
      state = { ...state, jurisdictions: { 'jur:capital': jur } };

      const printMatch: EvidenceMatch = {
        traceId: `trace:fp:${s}`,
        traceKind: 'fingerprint',
        crimeId: crimeEvent.id,
        suspectId: town.defendantId,
        matchedYear: year,
        matchedDay: 12,
        confidence: 0.62,
        method: 'fingerprint_local_manual',
        coldCase: false,
        yearsToMatch: 0,
        impossibleTimeline: false,
        beforeBirth: false,
        beforeEraArrival: false,
        anomalies: [],
      };
      const policeCase: PoliceCase = {
        ...state.jurisdictions['jur:capital']!.cases[crimeEvent.id]!,
        evidenceMatches: [printMatch],
      };

      for (const tier of ['public_defender', 'local_attorney', 'retained_counsel', 'elite_defense'] as const) {
        const res = runTrial({
          seed,
          world: town.world,
          crimeState: state,
          policeCase,
          defendantId: town.defendantId,
          jurisdictionId: 'jur:capital',
          year,
          day: 22,
          lawyerTier: tier,
        });
        if (res.verdict === 'not_guilty') stats[tier].acq++;
        else if (res.verdict === 'hung') stats[tier].hung++;
        else stats[tier].guilty++;
      }
    }

    const fmt = (t: keyof typeof stats) =>
      `${t}: acq=${((stats[t].acq / SEEDS) * 100).toFixed(1)}% hung=${((stats[t].hung / SEEDS) * 100).toFixed(1)}% guilty=${((stats[t].guilty / SEEDS) * 100).toFixed(1)}%`;
    console.log(`[G010 lawyer rates over ${SEEDS} seeds] ${fmt('public_defender')} | ${fmt('local_attorney')} | ${fmt('retained_counsel')} | ${fmt('elite_defense')}`);

    expect(stats.elite_defense.acq).toBeGreaterThan(stats.public_defender.acq);
    expect(stats.elite_defense.guilty).toBeLessThan(stats.public_defender.guilty);
    expect(stats.retained_counsel.acq + stats.retained_counsel.hung).toBeGreaterThanOrEqual(
      stats.public_defender.acq + stats.public_defender.hung,
    );
  });

  it('strikes jurors who know the victim and exposes voir-dire facts', () => {
    const year = 1978;
    const town = makeTestTown('jury-strike-victim', year, 48);
    const { crimeState, policeCase } = makeRobberyCase({
      seed: 'jury-strike-victim',
      world: town.world,
      crimeId: 'crime:jury_1',
      year,
      day: 4,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: town.witnessIds,
      jurisdictionId: 'jur:capital',
    });

    const lawyer = hireDefenseLawyer({
      seed: 'jury-strike-victim',
      caseId: policeCase.crimeId,
      year,
      tier: 'retained_counsel',
    });
    const selection = selectJury({
      seed: 'jury-strike-victim',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      year,
      lawyer,
    });

    expect(selection.seated).toHaveLength(12);
    const seatedIds = new Set(selection.seated.map((j) => j.personId));
    const struckMap = new Map(selection.struck.map((s) => [s.personId, s]));

    // Victim's spouse AND all three close friends of the victim MUST be struck
    expect(seatedIds.has(town.victimSpouseId)).toBe(false);
    expect(struckMap.get(town.victimSpouseId)?.struckBy).toBe('for_cause');
    expect(struckMap.get(town.victimSpouseId)?.facts.some((f) => f.kind === 'victim_kin')).toBe(true);

    for (const fid of town.victimFriendIds) {
      expect(seatedIds.has(fid)).toBe(false);
      expect(struckMap.has(fid)).toBe(true);
      expect(struckMap.get(fid)!.facts.some((f) => f.kind === 'victim_friend')).toBe(true);
    }
  });

  it('produces a hung jury on split evidence and runs a retrial with a fresh jury', () => {
    const year = 1978;
    const town = makeTestTown('hung-jury-retrial', year, 50);
    // Genuinely contested case: two eyewitnesses with no physical forensics
    const { crimeState, policeCase } = makeRobberyCase({
      seed: 'hung-jury-retrial',
      world: town.world,
      crimeId: 'crime:hung_1',
      year,
      day: 10,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: [town.witnessIds[0]!, town.witnessIds[1]!],
      jurisdictionId: 'jur:capital',
      withPrints: false,
    });

    let hungOutcome = runTrial({
      seed: 'hung-jury-retrial',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 20,
      lawyerTier: 'public_defender',
      defendantTestifies: true,
      defendantTestimonyScore: 0.45,
    });
    for (let k = 0; k < 25 && hungOutcome.verdict !== 'hung'; k++) {
      hungOutcome = runTrial({
        seed: `hung-search-${k}`,
        world: town.world,
        crimeState,
        policeCase,
        defendantId: town.defendantId,
        jurisdictionId: 'jur:capital',
        year,
        day: 20,
        lawyerTier: 'public_defender',
        defendantTestifies: true,
        defendantTestimonyScore: 0.42,
      });
    }

    expect(hungOutcome.verdict).toBe('hung');
    expect(hungOutcome.retrialRecommended).toBe(true);
    expect(hungOutcome.guiltyVotes).toBeGreaterThan(0);
    expect(hungOutcome.notGuiltyVotes).toBeGreaterThan(0);
    expect(hungOutcome.sentence).toBeUndefined();

    const retrial = runRetrial(hungOutcome, {
      seed: 'hung-retrial-fresh',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 50,
      lawyerTier: 'local_attorney',
    });

    expect(retrial.attempt).toBe(2);
    expect(retrial.jurySelection.attempt).toBe(2);
    expect(retrial.jurySelection.seated).toHaveLength(12);
    // Fresh venire shuffle produces a different seated jury composition
    const firstSeats = hungOutcome.jurySelection.seated.map((j) => j.personId);
    const secondSeats = retrial.jurySelection.seated.map((j) => j.personId);
    expect(secondSeats).not.toEqual(firstSeats);
  });

  it('produces measurably different 1925 vs 1995 trials based on era-available evidence', () => {
    const town1925 = makeTestTown('era-compare', 1925);
    const town1995 = makeTestTown('era-compare', 1995);

    const baseAppearance: CulpritAppearance = {
      height: 'average',
      build: 'slim',
      hair: 'brown',
      clothingColor: 'gray',
    };

    const makeEraCase = (world: World, year: number, town: ReturnType<typeof makeTestTown>) => {
      const event: CrimeEvent = {
        id: `crime:era_${year}`,
        kind: 'burglary',
        severity: 0.65,
        placeId: 'place:jeweler',
        position: { x: 15, y: 15 },
        when: { year, day: 10, hour: 23 },
        culpritId: town.defendantId,
        culpritAppearance: baseAppearance,
        victimId: town.victimId,
      };
      let state = createCrimeState('era-compare', 10);
      state = recordCrime(state, event, []);
      // One uncertain nighttime witness
      const report: PoliceReport = {
        id: `rep:era:${year}`,
        crimeId: event.id,
        reporterId: town.witnessIds[0]!,
        jurisdictionId: 'jur:capital',
        filedDay: 10,
        receivedDay: 10,
        delayHours: 2,
        source: 'first_hand',
        isVictim: false,
        confidence: 0.48,
        description: { height: 'average', clothingColor: 'black' },
      };
      state = {
        ...state,
        jurisdictions: { 'jur:capital': ingestPoliceReport(undefined, event, report) },
      };

      // Attach DNA + security camera matches (unavailable/inadmissible in 1925, admissible in 1995)
      const dnaMatch: EvidenceMatch = {
        traceId: `trace:dna:${year}`,
        traceKind: 'dna',
        crimeId: event.id,
        suspectId: town.defendantId,
        matchedYear: year,
        matchedDay: 12,
        confidence: 0.94,
        method: 'dna_database',
        coldCase: false,
        yearsToMatch: 0,
        impossibleTimeline: false,
        beforeBirth: false,
        beforeEraArrival: false,
        anomalies: [],
      };
      const camMatch: EvidenceMatch = {
        traceId: `trace:cam:${year}`,
        traceKind: 'camera_footage',
        crimeId: event.id,
        suspectId: town.defendantId,
        matchedYear: year,
        matchedDay: 11,
        confidence: 0.88,
        method: 'camera_identification',
        coldCase: false,
        yearsToMatch: 0,
        impossibleTimeline: false,
        beforeBirth: false,
        beforeEraArrival: false,
        anomalies: [],
      };
      const policeCase: PoliceCase = {
        ...state.jurisdictions['jur:capital']!.cases[event.id]!,
        evidenceMatches: [dnaMatch, camMatch],
      };
      return { state, policeCase };
    };

    const c1925 = makeEraCase(town1925.world, 1925, town1925);
    const c1995 = makeEraCase(town1995.world, 1995, town1995);

    const trial1925 = runTrial({
      seed: 'era-compare',
      world: town1925.world,
      crimeState: c1925.state,
      policeCase: c1925.policeCase,
      defendantId: town1925.defendantId,
      jurisdictionId: 'jur:capital',
      year: 1925,
      day: 25,
      lawyerTier: 'local_attorney',
    });

    const trial1995 = runTrial({
      seed: 'era-compare',
      world: town1995.world,
      crimeState: c1995.state,
      policeCase: c1995.policeCase,
      defendantId: town1995.defendantId,
      jurisdictionId: 'jur:capital',
      year: 1995,
      day: 25,
      lawyerTier: 'local_attorney',
    });

    expect(trial1925.era.eraId).toBe('interwar');
    expect(trial1925.ignoredEraEvidenceKinds).toContain('dna');
    expect(trial1925.ignoredEraEvidenceKinds).toContain('camera_footage');
    expect(trial1925.admissibleEvidenceKinds).not.toContain('dna');
    expect(trial1925.verdict).toBe('not_guilty');

    expect(trial1995.era.eraId).toBe('modern');
    expect(trial1995.admissibleEvidenceKinds).toContain('dna');
    expect(trial1995.admissibleEvidenceKinds).toContain('camera_footage');
    expect(trial1995.perceivedGuiltScore).toBeGreaterThan(trial1925.perceivedGuiltScore + 0.4);
    expect(trial1995.verdict).toBe('guilty');
  });

  it('lets a bribed juror change the trial outcome while creating a real new crime case', () => {
    const year = 1995;
    const town = makeTestTown('bribe-juror-case', year);
    const { crimeState, policeCase } = makeRobberyCase({
      seed: 'bribe-juror-case',
      world: town.world,
      crimeId: 'crime:rob_bribe',
      year,
      day: 10,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: [town.victimId, ...town.witnessIds],
      jurisdictionId: 'jur:capital',
      withPrints: true,
      withDna: true,
    });

    const lawyer = hireDefenseLawyer({
      seed: 'bribe-juror-case',
      caseId: policeCase.crimeId,
      year,
      tier: 'local_attorney',
    });
    const jury = selectJury({
      seed: 'bribe-juror-case',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      year,
      lawyer,
    });

    // Baseline: without bribery, strong evidence produces unanimous GUILTY
    const cleanOutcome = runTrial({
      seed: 'bribe-juror-case',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 25,
      lawyer,
      jury,
    });
    expect(cleanOutcome.verdict).toBe('guilty');

    // Pick the most corruptible seated juror (lowest diligence) and offer a large bribe
    const targetJuror = jury.seated
      .slice()
      .sort((a, b) => a.traits.diligence - b.traits.diligence)[0]!;

    const bribe = commitTrialInterference({
      seed: 'bribe-juror-case',
      world: town.world,
      crimeState,
      jurisdictionId: 'jur:capital',
      placeId: 'place:courthouse_hall',
      position: { x: 5, y: 5 },
      year,
      day: 24,
      culpritId: town.defendantId,
      culpritAppearance: { height: 'tall', build: 'average', hair: 'dark', clothingColor: 'black' },
      kind: 'bribe_juror',
      targetId: targetJuror.personId,
      bribeAmount: 25000,
      // Court bailiff in the hallway witnesses the envelope handoff
      bystanders: [{ personId: town.bystanderId, position: { x: 7, y: 5 }, attention: 0.95 }],
    });

    expect(bribe.succeeded).toBe(true);
    expect(bribe.tampering).toBeDefined();
    // New crime exists in CrimeState AND opened a new police case in jur:capital!
    expect(bribe.crimeState.crimes[bribe.crimeId]).toBeDefined();
    expect(bribe.crimeState.jurisdictions['jur:capital']!.cases[bribe.crimeId]).toBeDefined();

    // Re-running the trial with the bribed juror flips GUILTY -> HUNG!
    const taintedOutcome = runTrial({
      seed: 'bribe-juror-case',
      world: town.world,
      crimeState: bribe.crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 25,
      lawyer,
      jury,
      tampering: [bribe.tampering!],
    });

    expect(taintedOutcome.verdict).toBe('hung');
    expect(taintedOutcome.notGuiltyVotes).toBeGreaterThanOrEqual(1);
    const bribedVote = taintedOutcome.votes.find((v) => v.personId === targetJuror.personId);
    expect(bribedVote?.vote).toBe('not_guilty');
    expect(bribedVote?.tampered).toBe('bribed');
  });

  it('exonerates a wrongfully convicted person years later when DNA matches the true culprit', () => {
    // 1978 wrongful conviction based on mistaken eyewitnesses + partial circumstantial match (pre-DNA)
    const convYear = 1978;
    const town = makeTestTown('exoneration-1978', convYear);
    const { crimeState, policeCase } = makeRobberyCase({
      seed: 'exoneration-1978',
      world: town.world,
      crimeId: 'crime:wrongful_1978',
      year: convYear,
      day: 10,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: [town.victimId, ...town.witnessIds],
      jurisdictionId: 'jur:capital',
      withPrints: true,
      withDna: false,
    });

    const conviction = runTrial({
      seed: 'exoneration-1978',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year: convYear,
      day: 30,
      lawyerTier: 'public_defender',
    });
    expect(conviction.verdict).toBe('guilty');
    expect(conviction.sentence!.jailDays).toBeGreaterThan(300);

    // 1982: DNA testing does not yet exist -> DNA appeal denied
    const premature = fileAppeal({
      seed: 'exoneration-1978',
      world: town.world,
      crimeState,
      conviction,
      year: 1982,
      day: 1470,
      grounds: 'new_dna_match',
      excludesDefendant: true,
    });
    expect(premature.outcome).toBe('denied');

    // 1998 (20 years later, or 4 years into sentence): preserved blood trace tested via G008 DNA database
    // matches the true culprit `realCulpritId`!
    const preservedEvent: CrimeEvent = {
      ...crimeState.crimes['crime:wrongful_1978']!,
      culpritId: town.realCulpritId,
    };
    let coldState = createCrimeState('exoneration-1978', 10);
    coldState = recordCrime(coldState, preservedEvent, []);
    const bloodTraces = leaveTraces(preservedEvent, {
      seed: 'exoneration-1978',
      gloved: true,
      bloodSpilled: true,
    });
    coldState = recordTraces(coldState, preservedEvent.id, bloodTraces);
    coldState = collectCrimeTraces(coldState, town.world, preservedEvent.id, 'jur:capital', 11, {
      year: convYear,
    });
    // Years later in 1998, real culprit is enrolled in national DNA database
    coldState = enrollOnFile(coldState, {
      id: 'file:real_culprit_dna_1998',
      personId: town.realCulpritId,
      jurisdictionId: 'jur:capital',
      scope: 'national',
      reason: 'database',
      enrolledYear: 1998,
      enrolledDay: 7300,
      hasPrints: false,
      hasDna: true,
    });
    coldState = reexamineColdCases(coldState, town.world, 7300, { year: 1998 });

    const dnaHit = coldState.jurisdictions['jur:capital']!.cases[preservedEvent.id]!.evidenceMatches?.find(
      (m) => m.traceKind === 'dna' && m.suspectId === town.realCulpritId,
    );
    expect(dnaHit).toBeDefined();

    const appeal = fileAppeal({
      seed: 'exoneration-1978',
      world: town.world,
      crimeState: coldState,
      conviction,
      year: 1998,
      day: 7300,
      grounds: 'new_dna_match',
      newEvidenceMatch: dnaHit,
      daysServed: 200,
    });

    expect(appeal.outcome).toBe('exonerated');
    expect(appeal.trueSuspectId).toBe(town.realCulpritId);
    expect(appeal.vacatedJailDays).toBe(conviction.sentence!.jailDays - 200);
    expect(appeal.reputationRestored).toBeGreaterThan(0);
    expect(appeal.compensationAmount).toBeGreaterThan(0);
    expect(appeal.updatedConviction.exonerated).toBe(true);
    expect(appeal.updatedConviction.exonerationYear).toBe(1998);
  });

  it('scales pre-trial plea deals with case strength, denies bail on murder, and is deterministic', () => {
    const year = 1980;
    const town = makeTestTown('plea-bail-det', year);
    const { crimeState, policeCase } = makeRobberyCase({
      seed: 'plea-bail-det',
      world: town.world,
      crimeId: 'crime:pb_1',
      year,
      day: 5,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: town.witnessIds,
      jurisdictionId: 'jur:capital',
      withPrints: true,
    });

    const charges = chargesFromCase(policeCase, year, 12);
    const weakPlea = makePleaOffer({ charges, priorConvictions: 0, effectiveCaseStrength: 0.25 });
    const strongPlea = makePleaOffer({ charges, priorConvictions: 0, effectiveCaseStrength: 0.90 });

    expect(weakPlea.discountRatio).toBeGreaterThan(strongPlea.discountRatio);
    expect(weakPlea.recommendedJailDays).toBeLessThan(strongPlea.recommendedJailDays);
    expect(weakPlea.reducedToMisdemeanor).toBe(true);
    expect(strongPlea.reducedToMisdemeanor).toBe(false);

    // Bail denied on murder
    const murderCharge = { ...charges[0]!, kind: 'murder' as const, severity: 1.0 };
    const murderBail = evaluateBail({
      charges: [murderCharge],
      priorConvictions: 0,
      defendantWealth: 100000,
      defendantReputation: 0.5,
    });
    expect(murderBail.granted).toBe(false);

    // Byte-for-byte determinism
    const r1 = runTrial({
      seed: 'plea-bail-det',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 12,
      lawyerTier: 'local_attorney',
    });
    const r2 = runTrial({
      seed: 'plea-bail-det',
      world: town.world,
      crimeState,
      policeCase,
      defendantId: town.defendantId,
      jurisdictionId: 'jur:capital',
      year,
      day: 12,
      lawyerTier: 'local_attorney',
    });
    expect(JSON.stringify(r2)).toBe(JSON.stringify(r1));
  });
  it('records perjury caught in court and witness intimidation as real crimes in CrimeState', () => {
    const year = 1985;
    const town = makeTestTown('perjury-threat', year);
    const { crimeState } = makeRobberyCase({
      seed: 'perjury-threat',
      world: town.world,
      crimeId: 'crime:pt_1',
      year,
      day: 10,
      culpritId: town.defendantId,
      victimId: town.victimId,
      witnessIds: town.witnessIds,
      jurisdictionId: 'jur:capital',
    });

    const perjuryRes = commitTrialInterference({
      seed: 'perjury-threat',
      world: town.world,
      crimeState,
      jurisdictionId: 'jur:capital',
      placeId: 'place:courtroom',
      position: { x: 0, y: 0 },
      year,
      day: 20,
      culpritId: town.defendantId,
      culpritAppearance: { height: 'average', build: 'average', hair: 'dark', clothingColor: 'black' },
      kind: 'perjury',
      targetId: town.bystanderId,
      caughtInCourt: true,
    });

    expect(perjuryRes.succeeded).toBe(false);
    expect(perjuryRes.targetReported).toBe(true);
    expect(perjuryRes.crimeState.crimes[perjuryRes.crimeId]).toBeDefined();
    expect(perjuryRes.crimeState.jurisdictions['jur:capital']!.cases[perjuryRes.crimeId]).toBeDefined();

    const threatRes = commitTrialInterference({
      seed: 'perjury-threat',
      world: town.world,
      crimeState: perjuryRes.crimeState,
      jurisdictionId: 'jur:capital',
      placeId: 'place:alley',
      position: { x: 12, y: 4 },
      year,
      day: 21,
      culpritId: town.defendantId,
      culpritAppearance: { height: 'average', build: 'average', hair: 'dark', clothingColor: 'black' },
      kind: 'threaten_witness',
      targetId: town.witnessIds[0]!,
      bystanders: [{ personId: town.bystanderId, position: { x: 14, y: 4 }, attention: 0.9 }],
    });

    expect(threatRes.crimeState.crimes[threatRes.crimeId]).toBeDefined();
    expect(threatRes.crimeState.crimes[threatRes.crimeId]!.kind).toBe('assault');
  });
});
