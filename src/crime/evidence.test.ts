import { performance } from 'node:perf_hooks';
import { describe, expect, it } from 'vitest';
import { rand } from '../sim/rng';
import type { Id, Person, World } from '../sim/types';
import {
  FORENSIC_ERA,
  advanceDay,
  cameraTierForYear,
  caseStrength,
  classifySerialItem,
  cleanScene,
  collectCrimeTraces,
  createCrimeState,
  decayTraceOneDay,
  destroyTraces,
  dnaTierForYear,
  enrollOnFile,
  findTimeTravelAnomalies,
  fingerprintTierForYear,
  ingestPoliceReport,
  isNamedSuspect,
  isWanted,
  itemHasSerialInEra,
  leaveTraces,
  matchTrace,
  plateTierForYear,
  policeKnowledge,
  recordCrime,
  recordTraces,
  reexamineColdCases,
  witnessesOf,
  type CrimeContext,
  type CrimeEvent,
  type CulpritAppearance,
  type EvidenceTrace,
  type IdentityFileRecord,
  type PoliceReport,
  type SecurityCamera,
  type WeatherKind,
} from './index';

function makePerson(id: Id, birthYear: number, sex: 'M' | 'F' = 'M'): Person {
  return {
    id,
    first: 'Alex',
    surname: id,
    sex,
    birthYear,
    traits: { ambition: 0.5, charm: 0.5, diligence: 0.5 },
    parents: [],
    children: [],
    wealth: 1000,
    reputation: 0,
  };
}

function makeWorld(year: number, peopleList: Person[]): World {
  const people: Record<Id, Person> = {};
  for (const p of peopleList) people[p.id] = p;
  return {
    seed: `test-${year}`,
    year,
    people,
    bonds: {},
    events: [],
    nextId: peopleList.length + 1,
  } as unknown as World;
}

function buildPerfWorld(
  seed: string,
  count: number,
  year: number,
  townCount: number,
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
    const age = 18 + Math.floor(rand(seed, 'age', id) * 52);
    people[id] = {
      id,
      first: `First${i}`,
      surname: `Sur${i % 40}`,
      sex: i % 2 === 0 ? 'F' : 'M',
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
    const t = Math.min(townCount - 1, Math.floor(i / perTown));
    townByPerson[id] = `town:${t}`;
    workplaceByPerson[id] = `town:${t}:wp:${i % 15}`;
  }

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
    world: { seed, year, people, bonds: {}, events: [] } as unknown as World,
    townByPerson,
    workplaceByPerson,
  };
}

const DEFAULT_APPEARANCE: CulpritAppearance = {
  height: 'tall',
  build: 'average',
  hair: 'dark',
  clothingColor: 'red',
  vehicle: { kind: 'sedan', color: 'blue' },
};

function makeCrime(overrides: Partial<CrimeEvent> = {}): CrimeEvent {
  return {
    id: overrides.id ?? 'crime-1',
    kind: overrides.kind ?? 'burglary',
    culpritId: overrides.culpritId ?? 'culprit',
    victimId: overrides.victimId ?? 'victim',
    placeId: overrides.placeId ?? 'bank',
    townId: overrides.townId ?? 'town_a',
    position: overrides.position ?? { x: 10, y: 10 },
    when: overrides.when ?? { year: 1950, day: 10, hour: 21 },
    severity: overrides.severity ?? 0.65,
    culpritAppearance: overrides.culpritAppearance ?? DEFAULT_APPEARANCE,
    ...(overrides.stolenOrDamaged ? { stolenOrDamaged: overrides.stolenOrDamaged } : {}),
  };
}

describe('src/crime/evidence — physical traces, era forensics, cold cases & time anomalies', () => {
  it('era gates: 1900 print is useless; 1950 print matches local file & national bureau; 1980 AFIS crosses jurisdictions', () => {
    expect(fingerprintTierForYear(1900)).toBe('none');
    expect(fingerprintTierForYear(1915)).toBe('local_manual');
    expect(fingerprintTierForYear(1950)).toBe('national_manual');
    expect(fingerprintTierForYear(1985)).toBe('automated');
    expect(dnaTierForYear(1980)).toBe('none');
    expect(dnaTierForYear(1990)).toBe('manual_suspect');
    expect(dnaTierForYear(1998)).toBe('database');
    expect(plateTierForYear(1900)).toBe('none');
    expect(plateTierForYear(1950)).toBe('local_registry');
    expect(plateTierForYear(1980)).toBe('database');
    expect(cameraTierForYear(1965)).toBe('none');
    expect(cameraTierForYear(1975)).toBe('bw');
    expect(cameraTierForYear(1995)).toBe('color');
    expect(cameraTierForYear(2015)).toBe('hd');

    // Serial numbers by item class & era
    expect(itemHasSerialInEra(classifySerialItem('pocket watch'), 1895)).toBe(false);
    expect(itemHasSerialInEra(classifySerialItem('revolver'), 1900)).toBe(true);
    expect(itemHasSerialInEra(classifySerialItem('bicycle'), 1920)).toBe(false);
    expect(itemHasSerialInEra(classifySerialItem('bicycle'), 1935)).toBe(true);
    expect(itemHasSerialInEra(classifySerialItem('television'), 1965)).toBe(false);
    expect(itemHasSerialInEra(classifySerialItem('television'), 1975)).toBe(true);
    expect(itemHasSerialInEra(classifySerialItem('cash'), 2005)).toBe(false);

    const culprit = makePerson('culprit', 1875);
    const victim = makePerson('victim', 1870);
    const world1900 = makeWorld(1900, [culprit, victim]);
    const crime1900 = makeCrime({ id: 'c1900', when: { year: 1900, day: 10, hour: 22 } });

    let st1900 = createCrimeState('era-gate', 10);
    st1900 = recordCrime(st1900, crime1900, []);
    const print1900: EvidenceTrace = {
      id: 'c1900:fp',
      crimeId: 'c1900',
      kind: 'fingerprint',
      placeId: 'bank',
      position: { x: 10, y: 10 },
      createdYear: 1900,
      createdDay: 10,
      quality: 0.9,
      initialQuality: 0.9,
      exposure: 'indoor',
      collected: true,
      collectedDay: 10,
      collectedYear: 1900,
      collectedJurisdiction: 'town_a',
      destroyed: false,
      sourcePersonId: 'culprit',
    };
    // Even if culprit is somehow enrolled locally in 1900, 1900 has no fingerprint matching science
    st1900 = enrollOnFile(st1900, {
      id: 'rec-1900',
      personId: 'culprit',
      jurisdictionId: 'town_a',
      scope: 'local',
      reason: 'arrest',
      enrolledYear: 1898,
      enrolledDay: 1,
      hasPrints: true,
      hasDna: false,
    });
    expect(matchTrace(print1900, 'town_a', 1900, 10, st1900, world1900, 0.65)).toBeNull();

    // 1950: local file matches in town_a, does NOT match in town_b unless scope === 'national'
    const world1950 = makeWorld(1950, [makePerson('culprit', 1920), makePerson('victim', 1915)]);
    const crime1950 = makeCrime({ id: 'c1950', when: { year: 1950, day: 20, hour: 21 } });
    const print1950: EvidenceTrace = {
      ...print1900,
      id: 'c1950:fp',
      crimeId: 'c1950',
      createdYear: 1950,
      createdDay: 20,
      collectedDay: 20,
      collectedYear: 1950,
    };

    let st1950 = createCrimeState('era-1950', 20);
    st1950 = recordCrime(st1950, crime1950, []);
    st1950 = enrollOnFile(st1950, {
      id: 'rec-1950-local',
      personId: 'culprit',
      jurisdictionId: 'town_a',
      scope: 'local',
      reason: 'arrest',
      enrolledYear: 1947,
      enrolledDay: 1,
      hasPrints: true,
      hasDna: false,
    });

    const localHit = matchTrace(print1950, 'town_a', 1950, 20, st1950, world1950, 0.65);
    expect(localHit).not.toBeNull();
    expect(localHit!.suspectId).toBe('culprit');
    expect(localHit!.method).toBe('fingerprint_local_manual');
    expect(localHit!.coldCase).toBe(false);

    // Local-only record from town_a is invisible to town_b in 1950
    expect(matchTrace(print1950, 'town_b', 1950, 20, st1950, world1950, 0.65)).toBeNull();

    // Once national bureau card exists, town_b can match in 1950
    const st1950Nat = enrollOnFile(st1950, {
      id: 'rec-1950-nat',
      personId: 'culprit',
      jurisdictionId: 'town_a',
      scope: 'national',
      reason: 'arrest',
      enrolledYear: 1948,
      enrolledDay: 1,
      hasPrints: true,
      hasDna: false,
    });
    const natHit = matchTrace(print1950, 'town_b', 1950, 20, st1950Nat, world1950, 0.65);
    expect(natHit).not.toBeNull();
    expect(natHit!.method).toBe('fingerprint_national_manual');

    // In 1982 (AFIS), even a local-only record from town_a matches in town_b
    const afisHit = matchTrace(print1950, 'town_b', 1982, 11700, st1950, world1950, 0.65);
    expect(afisHit).not.toBeNull();
    expect(afisHit!.method).toBe('fingerprint_automated');
    expect(afisHit!.coldCase).toBe(true);
    expect(afisHit!.yearsToMatch).toBe(32);
  });

  it('era gates: vehicle plates, stolen serial numbers, and security cameras evolve cleanly across eras', () => {
    const cams: SecurityCamera[] = [{ id: 'cam-1', position: { x: 14, y: 12 }, radius: 25 }];

    // 1900: no plates, no cameras, no serial on bicycle
    const c1900 = makeCrime({
      id: 'veh-1900',
      kind: 'car_theft',
      when: { year: 1900, day: 1, hour: 14 },
      stolenOrDamaged: { item: 'bicycle', value: 40 },
    });
    const t1900 = leaveTraces(c1900, { seed: 's1', cameras: cams, vehiclePlate: 'OLD-1' });
    const veh1900 = t1900.find((t) => t.kind === 'vehicle_plate');
    expect(veh1900).toBeDefined();
    expect(veh1900!.plateNumber).toBeUndefined(); // pre-1905: vehicle seen, no registered plate
    expect(t1900.some((t) => t.kind === 'camera_footage')).toBe(false);
    expect(t1900.some((t) => t.kind === 'stolen_serial')).toBe(false);

    // 1965: registered plate + typewriter serial exists (1930+), still no security cameras
    const c1965 = makeCrime({
      id: 'veh-1965',
      kind: 'burglary',
      when: { year: 1965, day: 1, hour: 14 },
      stolenOrDamaged: { item: 'typewriter', value: 120 },
    });
    const t1965 = leaveTraces(c1965, { seed: 's1', cameras: cams, vehiclePlate: 'CAL-650' });
    expect(t1965.find((t) => t.kind === 'vehicle_plate')?.plateNumber).toBe('CAL-650');
    expect(t1965.find((t) => t.kind === 'stolen_serial')?.serialNumber).toBeDefined();
    expect(t1965.some((t) => t.kind === 'camera_footage')).toBe(false);

    // 1975: B&W security camera — captures height/build/hair/vehicle kind, NO clothing colour
    const c1975 = makeCrime({ id: 'cam-1975', kind: 'robbery', when: { year: 1975, day: 1, hour: 14 } });
    const t1975 = leaveTraces(c1975, { seed: 's1', cameras: cams });
    const bwCam = t1975.find((t) => t.kind === 'camera_footage');
    expect(bwCam).toBeDefined();
    expect(bwCam!.cameraColor).toBe(false);
    expect(bwCam!.cameraHd).toBe(false);
    expect(bwCam!.capturedAppearance?.clothingColor).toBeUndefined();

    // 1995: colour security camera — captures clothing colour
    const c1995 = makeCrime({ id: 'cam-1995', kind: 'robbery', when: { year: 1995, day: 1, hour: 14 } });
    const t1995 = leaveTraces(c1995, { seed: 's1', cameras: cams });
    const colorCam = t1995.find((t) => t.kind === 'camera_footage');
    expect(colorCam).toBeDefined();
    expect(colorCam!.cameraColor).toBe(true);
    expect(colorCam!.cameraHd).toBe(false);
    expect(colorCam!.capturedAppearance?.clothingColor).toBe('red');

    // 2015: HD colour security camera
    const c2015 = makeCrime({ id: 'cam-2015', kind: 'robbery', when: { year: 2015, day: 1, hour: 14 } });
    const t2015 = leaveTraces(c2015, { seed: 's1', cameras: cams });
    const hdCam = t2015.find((t) => t.kind === 'camera_footage');
    expect(hdCam).toBeDefined();
    expect(hdCam!.cameraColor).toBe(true);
    expect(hdCam!.cameraHd).toBe(true);
    expect(hdCam!.quality).toBeGreaterThan(bwCam!.quality);
  });


  it('links all physical trace kinds: plates (local vs computerized DB), stolen serials, dropped items, footprints & cameras', () => {
    const culprit = makePerson('culprit', 1945);
    const victim = makePerson('victim', 1940);
    const world1960 = makeWorld(1960, [culprit, victim]);
    const crime = makeCrime({
      id: 'multi-trace',
      kind: 'burglary',
      when: { year: 1960, day: 10, hour: 21 },
      stolenOrDamaged: { item: 'typewriter', value: 150 },
    });

    const traces = leaveTraces(crime, {
      seed: 'multi-1',
      vehiclePlate: 'NY-4421',
      stolenSerialNumber: 'SER-88012',
      shoeProfile: 'work_boot_11',
      droppedItem: { label: 'monogrammed cufflink', ownerId: 'culprit' },
    });

    let st = createCrimeState('multi-1', 10);
    st = recordCrime(st, crime, []);
    st = recordTraces(st, 'multi-trace', traces);

    // Enroll plate & shoe & pawned serial in town_a (local)
    st = enrollOnFile(st, {
      id: 'reg-1',
      personId: 'culprit',
      jurisdictionId: 'town_a',
      scope: 'local',
      reason: 'vehicle_registration',
      enrolledYear: 1958,
      enrolledDay: 1,
      hasPrints: false,
      hasDna: false,
      plateNumbers: ['NY-4421'],
      shoeProfile: 'work_boot_11',
      serialNumbers: ['SER-88012'],
    });

    const plateTrace = traces.find((x) => x.kind === 'vehicle_plate')!;
    const serialTrace = traces.find((x) => x.kind === 'stolen_serial')!;
    const droppedTrace = traces.find((x) => x.kind === 'dropped_item')!;
    const footTrace = traces.find((x) => x.kind === 'footprint')!;

    expect(plateTrace).toBeDefined();
    expect(serialTrace).toBeDefined();
    expect(droppedTrace).toBeDefined();
    expect(footTrace).toBeDefined();

    // 1960: local plate matches town_a via local_registry, NOT town_b
    const localPlate = matchTrace(plateTrace, 'town_a', 1960, 11, st, world1960, 0.65);
    expect(localPlate?.method).toBe('plate_local_registry');
    expect(localPlate?.suspectId).toBe('culprit');
    expect(matchTrace(plateTrace, 'town_b', 1960, 11, st, world1960, 0.65)).toBeNull();

    // 1975 (post-1972 computerized DB): town_b now matches NY-4421 cross-jurisdiction!
    const dbPlate = matchTrace(plateTrace, 'town_b', 1975, 5500, st, { ...world1960, year: 1975 }, 0.65);
    expect(dbPlate?.method).toBe('plate_database');
    expect(dbPlate?.coldCase).toBe(true);

    // Stolen serial, dropped item & footprint all link cleanly to culprit
    expect(matchTrace(serialTrace, 'town_a', 1960, 11, st, world1960, 0.65)?.method).toBe('serial_registry');
    expect(matchTrace(droppedTrace, 'town_a', 1960, 11, st, world1960, 0.65)?.method).toBe('dropped_item_ownership');
    expect(matchTrace(footTrace, 'town_a', 1960, 11, st, world1960, 0.65)?.method).toBe('footprint_comparison');
  });

  it('cold case: 1965 print unmatched at crime time matches 17 years later when booked in 1982; 1965 blood matches when DNA DB arrives in 1995', () => {
    const culprit = makePerson('ghost', 1940);
    const victim = makePerson('victim', 1930);
    const world1965 = makeWorld(1965, [culprit, victim]);

    const burglary1965 = makeCrime({
      id: 'cold-burg-1965',
      kind: 'burglary',
      culpritId: 'ghost',
      when: { year: 1965, day: 100, hour: 23 },
      severity: 0.65,
    });
    const murder1965 = makeCrime({
      id: 'cold-murd-1965',
      kind: 'murder',
      culpritId: 'ghost',
      when: { year: 1965, day: 105, hour: 2 },
      severity: 1.0,
    });

    const fpTrace: EvidenceTrace = {
      id: 'cold-burg-1965:fp',
      crimeId: 'cold-burg-1965',
      kind: 'fingerprint',
      placeId: 'bank',
      position: { x: 10, y: 10 },
      createdYear: 1965,
      createdDay: 100,
      quality: 0.85,
      initialQuality: 0.85,
      exposure: 'indoor',
      collected: false,
      destroyed: false,
      sourcePersonId: 'ghost',
    };
    const dnaTrace: EvidenceTrace = {
      id: 'cold-murd-1965:dna',
      crimeId: 'cold-murd-1965',
      kind: 'dna',
      placeId: 'alley',
      position: { x: 10, y: 10 },
      createdYear: 1965,
      createdDay: 105,
      quality: 0.90,
      initialQuality: 0.90,
      exposure: 'indoor',
      collected: false,
      destroyed: false,
      sourcePersonId: 'ghost',
    };

    let state = createCrimeState('cold-case', 100);
    state = recordCrime(state, burglary1965, []);
    state = recordCrime(state, murder1965, []);
    state = recordTraces(state, 'cold-burg-1965', [fpTrace]);
    state = recordTraces(state, 'cold-murd-1965', [dnaTrace]);

    // Police collect both traces in 1965 (into evidence locker) — ghost is NOT on file yet
    state = collectCrimeTraces(state, world1965, 'cold-burg-1965', 'town_a', 101, { year: 1965 });
    state = collectCrimeTraces(state, world1965, 'cold-murd-1965', 'town_a', 106, { year: 1965 });

    expect(state.jurisdictions.town_a?.cases['cold-burg-1965']?.traces?.length).toBe(1);
    expect(state.jurisdictions.town_a?.cases['cold-burg-1965']?.evidenceMatches?.length).toBe(0);
    expect(state.jurisdictions.town_a?.cases['cold-murd-1965']?.evidenceMatches?.length).toBe(0);
    expect(isNamedSuspect(state, 'ghost', 'town_a', 106)).toBe(false);

    // 17 years later (1982, day 6305): ghost is arrested in ANOTHER town (town_b) and fingerprinted
    const world1982 = { ...world1965, year: 1982 };
    state = enrollOnFile(state, {
      id: 'arrest-1982',
      personId: 'ghost',
      jurisdictionId: 'town_b',
      scope: 'local',
      reason: 'arrest',
      enrolledYear: 1982,
      enrolledDay: 6305,
      hasPrints: true,
      hasDna: false,
    });

    // Advance one day in 1982 — AFIS is online (1980+), so the 1965 print matches immediately!
    state = advanceDay(state, world1982, 6306, { year: 1982 });

    const burgCase = state.jurisdictions.town_a!.cases['cold-burg-1965']!;
    expect(burgCase.evidenceMatches!.length).toBe(1);
    expect(burgCase.evidenceMatches![0]!.suspectId).toBe('ghost');
    expect(burgCase.evidenceMatches![0]!.coldCase).toBe(true);
    expect(burgCase.evidenceMatches![0]!.yearsToMatch).toBe(17);
    expect(burgCase.evidenceMatches![0]!.method).toBe('fingerprint_automated');
    // Solving the cold case re-heats the case so police immediately know & want the suspect
    expect(isNamedSuspect(state, 'ghost', 'town_a', 6306)).toBe(true);
    expect(isWanted(state, 'ghost', 'town_a', 6306)).toBe(true);

    // Meanwhile in 1982, the 1965 blood/DNA trace STILL cannot match (pre-1987)
    expect(state.jurisdictions.town_a!.cases['cold-murd-1965']!.evidenceMatches!.length).toBe(0);

    // 1995 (day 11050): DNA database (CODIS) launches and ghost's DNA is uploaded
    const world1995 = { ...world1965, year: 1995 };
    state = enrollOnFile(state, {
      id: 'dna-1995',
      personId: 'ghost',
      jurisdictionId: 'town_b',
      scope: 'national',
      reason: 'database',
      enrolledYear: 1995,
      enrolledDay: 11050,
      hasPrints: false,
      hasDna: true,
    });

    state = reexamineColdCases(state, world1995, 11051, { year: 1995 });
    const murdCase = state.jurisdictions.town_a!.cases['cold-murd-1965']!;
    expect(murdCase.evidenceMatches!.length).toBe(1);
    expect(murdCase.evidenceMatches![0]!.suspectId).toBe('ghost');
    expect(murdCase.evidenceMatches![0]!.method).toBe('dna_database');
    expect(murdCase.evidenceMatches![0]!.coldCase).toBe(true);
    expect(murdCase.evidenceMatches![0]!.yearsToMatch).toBe(30);
  });

  it('time-travel anomalies: flags prints left before birth year and before era arrival', () => {
    // Traveller born in 1995 jumps back to 1952, leaves fingerprints, then gets booked in 2015
    const traveller = makePerson('traveller', 1995);
    const victim = makePerson('victim', 1920);
    const world1952 = makeWorld(1952, [traveller, victim]);

    const crime1952 = makeCrime({
      id: 'tt-1952',
      culpritId: 'traveller',
      when: { year: 1952, day: 40, hour: 22 },
      severity: 0.75,
    });
    const trace1952: EvidenceTrace = {
      id: 'tt-1952:fp',
      crimeId: 'tt-1952',
      kind: 'fingerprint',
      placeId: 'bank',
      position: { x: 10, y: 10 },
      createdYear: 1952,
      createdDay: 40,
      quality: 0.92,
      initialQuality: 0.92,
      exposure: 'indoor',
      collected: false,
      destroyed: false,
      sourcePersonId: 'traveller',
    };

    let state = createCrimeState('tt-seed', 40);
    state = recordCrime(state, crime1952, []);
    state = recordTraces(state, 'tt-1952', [trace1952]);
    state = collectCrimeTraces(state, world1952, 'tt-1952', 'town_a', 41, { year: 1952 });

    // Booked in 2015 (age 20)
    const world2015 = { ...world1952, year: 2015 };
    state = enrollOnFile(state, {
      id: 'tt-book-2015',
      personId: 'traveller',
      jurisdictionId: 'town_a',
      scope: 'national',
      reason: 'arrest',
      enrolledYear: 2015,
      enrolledDay: 23000,
      hasPrints: true,
      hasDna: true,
      birthYear: 1995,
      firstEraArrivalYear: 1955,
    });

    state = reexamineColdCases(state, world2015, 23001, { year: 2015 });

    const c = state.jurisdictions.town_a!.cases['tt-1952']!;
    expect(c.evidenceMatches!.length).toBe(1);
    const m = c.evidenceMatches![0]!;
    expect(m.suspectId).toBe('traveller');
    expect(m.impossibleTimeline).toBe(true);
    expect(m.beforeBirth).toBe(true);
    expect(m.beforeEraArrival).toBe(true);

    const beforeBirth = m.anomalies.find((a) => a.kind === 'before_birth');
    expect(beforeBirth).toBeDefined();
    expect(beforeBirth!.traceYear).toBe(1952);
    expect(beforeBirth!.referenceYear).toBe(1995);
    expect(beforeBirth!.yearsImpossible).toBe(43);

    const beforeArrival = m.anomalies.find((a) => a.kind === 'before_era_arrival');
    expect(beforeArrival).toBeDefined();
    expect(beforeArrival!.yearsImpossible).toBe(3);

    const allAnomalies = findTimeTravelAnomalies(state, world2015);
    expect(allAnomalies.some((a) => a.kind === 'before_birth' && a.personId === 'traveller')).toBe(true);
    expect(allAnomalies.some((a) => a.kind === 'before_era_arrival' && a.personId === 'traveller')).toBe(true);

    const strength = caseStrength(c, 23001);
    expect(strength.hasTimeAnomaly).toBe(true);
    expect(strength.timeAnomalies.length).toBe(2);
    expect(strength.parts.some((p) => p.label.includes('IMPOSSIBLE TIMELINE'))).toBe(true);
  });

  it('gloves, weather decay, tape overwrite, and cleaning/destroying traces (collected evidence immune)', () => {
    const world = makeWorld(1995, [makePerson('culprit', 1965), makePerson('victim', 1960)]);
    const crime = makeCrime({
      id: 'clean-1',
      kind: 'murder',
      when: { year: 1995, day: 10, hour: 23 },
      severity: 1.0,
    });
    const cams: SecurityCamera[] = [{ id: 'cam-a', position: { x: 12, y: 12 }, radius: 25 }];

    // Gloved culprit leaves NO fingerprints
    const glovedTraces = leaveTraces(crime, {
      seed: 'gloves',
      gloved: true,
      bloodSpilled: true,
      cameras: cams,
    });
    expect(glovedTraces.some((t) => t.kind === 'fingerprint')).toBe(false);
    expect(glovedTraces.some((t) => t.kind === 'dna')).toBe(true);
    expect(glovedTraces.some((t) => t.kind === 'camera_footage')).toBe(true);

    // Outdoor footprint decays dramatically faster in storm than in clear weather
    const footClear: EvidenceTrace = {
      id: 'fp-clear',
      crimeId: 'clean-1',
      kind: 'footprint',
      placeId: 'alley',
      position: { x: 10, y: 10 },
      createdYear: 1995,
      createdDay: 10,
      quality: 0.85,
      initialQuality: 0.85,
      exposure: 'outdoor',
      collected: false,
      destroyed: false,
      sourcePersonId: 'culprit',
    };
    const afterClear = decayTraceOneDay(footClear, 11, 'clear');
    const afterStorm = decayTraceOneDay(footClear, 11, 'storm');
    expect(afterStorm.quality).toBeLessThan(afterClear.quality * 0.5);

    // Uncollected camera footage overwrites once retentionDays elapses
    const camTrace = glovedTraces.find((t) => t.kind === 'camera_footage')!;
    const retention = camTrace.retentionDays ?? 14;
    const overwritten = decayTraceOneDay(camTrace, 10 + retention, 'clear');
    expect(overwritten.destroyed).toBe(true);
    expect(overwritten.destroyedMethod).toBe('overwrite');

    // Cleaning scene destroys uncollected DNA/footprints, leaves camera footage until overwritten
    let st = createCrimeState('clean-test', 10);
    st = recordCrime(st, crime, []);
    st = recordTraces(st, 'clean-1', [
      ...glovedTraces,
      { ...footClear, id: 'clean-1:foot' },
    ]);

    st = cleanScene(st, 'clean-1', { method: 'clean', actorId: 'culprit', day: 10 });
    const afterClean = st.traces!['clean-1']!;
    expect(afterClean.find((t) => t.kind === 'dna')!.destroyed).toBe(true);
    expect(afterClean.find((t) => t.kind === 'dna')!.destroyedBy).toBe('culprit');
    expect(afterClean.find((t) => t.id === 'clean-1:foot')!.destroyed).toBe(true);
    expect(afterClean.find((t) => t.kind === 'camera_footage')!.destroyed).toBe(false);

    // Overwriting camera footage destroys it too
    st = destroyTraces(st, 'clean-1', { method: 'overwrite', actorId: 'culprit', day: 10 });
    expect(st.traces!['clean-1']!.find((t) => t.kind === 'camera_footage')!.destroyed).toBe(true);

    // Already-collected evidence in police locker CANNOT be destroyed by cleaning the scene later
    let stCollected = createCrimeState('locked-test', 10);
    stCollected = recordCrime(stCollected, crime, []);
    stCollected = recordTraces(stCollected, 'clean-1', [
      { ...footClear, id: 'locked-dna', kind: 'dna', exposure: 'indoor' },
    ]);
    stCollected = collectCrimeTraces(stCollected, world, 'clean-1', 'town_a', 11, { year: 1995 });
    stCollected = cleanScene(stCollected, 'clean-1', { method: 'burn', actorId: 'culprit', day: 12 });
    expect(stCollected.traces!['clean-1']![0]!.destroyed).toBe(false);
    expect(stCollected.traces!['clean-1']![0]!.collected).toBe(true);
    expect(stCollected.jurisdictions.town_a!.cases['clean-1']!.traces![0]!.destroyed).toBe(false);
  });

  it('caseStrength(case, day): explains witness-only, physical-only, corroboration boost, and witness-vs-DNA conflict', () => {
    const culprit = makePerson('culprit', 1970);
    const wrongGuy = makePerson('wrong_guy', 1970);
    const witness = makePerson('wit', 1965);
    const world = makeWorld(2005, [culprit, wrongGuy, witness]);
    const crime = makeCrime({
      id: 'court-1',
      kind: 'robbery',
      culpritId: 'culprit',
      when: { year: 2005, day: 50, hour: 19 },
      severity: 0.8,
    });

    const witnessReportAgree: PoliceReport = {
      id: 'rep-1',
      crimeId: 'court-1',
      reporterId: 'wit',
      jurisdictionId: 'town_a',
      filedDay: 50,
      receivedDay: 50,
      delayHours: 0.5,
      source: 'first_hand',
      isVictim: false,
      confidence: 0.78,
      identifiedCulpritId: 'culprit',
      description: { height: 'tall', hair: 'dark', clothingColor: 'red' },
    };

    // 1. Witness-only case
    const jurWitnessOnly = ingestPoliceReport(undefined, crime, witnessReportAgree);
    const strWitnessOnly = caseStrength(jurWitnessOnly.cases['court-1']!, 51);
    expect(strWitnessOnly.witnessScore).toBeGreaterThan(0.35);
    expect(strWitnessOnly.physicalScore).toBe(0);
    expect(strWitnessOnly.parts.length).toBe(1);
    expect(strWitnessOnly.parts[0]!.source).toBe('witness');
    expect(strWitnessOnly.leadSuspectId).toBe('culprit');

    // 2. Physical-only (zero-witness) case with DNA match
    let stPhys = createCrimeState('court-phys', 50);
    stPhys = recordCrime(stPhys, crime, []);
    stPhys = recordTraces(stPhys, 'court-1', [
      {
        id: 'court-1:dna',
        crimeId: 'court-1',
        kind: 'dna',
        placeId: 'bank',
        position: { x: 10, y: 10 },
        createdYear: 2005,
        createdDay: 50,
        quality: 0.92,
        initialQuality: 0.92,
        exposure: 'indoor',
        collected: false,
        destroyed: false,
        sourcePersonId: 'culprit',
      },
    ]);
    stPhys = enrollOnFile(stPhys, {
      id: 'codis-c',
      personId: 'culprit',
      jurisdictionId: 'town_a',
      scope: 'national',
      reason: 'database',
      enrolledYear: 2003,
      enrolledDay: 1,
      hasPrints: true,
      hasDna: true,
    });
    stPhys = collectCrimeTraces(stPhys, world, 'court-1', 'town_a', 51, { year: 2005 });
    const physCase = stPhys.jurisdictions.town_a!.cases['court-1']!;
    const strPhysOnly = caseStrength(physCase, 51);
    expect(strPhysOnly.witnessScore).toBe(0);
    expect(strPhysOnly.physicalScore).toBeGreaterThan(0.75);
    expect(strPhysOnly.leadSuspectId).toBe('culprit');
    expect(strPhysOnly.parts.some((p) => p.source === 'evidence' && p.kind === 'dna')).toBe(true);

    // 3. Combined corroboration: witness + DNA both name culprit
    const jurCombined = ingestPoliceReport(stPhys.jurisdictions.town_a, crime, witnessReportAgree);
    const strCombined = caseStrength(jurCombined.cases['court-1']!, 51);
    expect(strCombined.corroborationAdjustment).toBe(0.1);
    expect(strCombined.combinedScore).toBeGreaterThan(strWitnessOnly.combinedScore);
    expect(strCombined.combinedScore).toBeGreaterThan(strPhysOnly.combinedScore);
    expect(strCombined.verdictTier).toBe('overwhelming');
    expect(strCombined.parts.some((p) => p.kind === 'witness_physical_corroboration')).toBe(true);

    // 4. Contradiction: witness wrongly names wrong_guy while DNA matches culprit
    const witnessReportWrong: PoliceReport = {
      ...witnessReportAgree,
      id: 'rep-wrong',
      identifiedCulpritId: 'wrong_guy',
    };
    const jurConflict = ingestPoliceReport(stPhys.jurisdictions.town_a, crime, witnessReportWrong);
    const strConflict = caseStrength(jurConflict.cases['court-1']!, 51);
    expect(strConflict.witnessSuspectId).toBe('wrong_guy');
    expect(strConflict.physicalSuspectId).toBe('culprit');
    expect(strConflict.corroborationAdjustment).toBe(-0.14);
    expect(strConflict.combinedScore).toBeLessThan(strCombined.combinedScore);
    expect(strConflict.parts.some((p) => p.kind === 'witness_physical_conflict')).toBe(true);
  });

  it('determinism & JSON round-trip: identical state across runs and after mid-run serialization', () => {
    const run = (doRoundTrip: boolean): string => {
      const world = makeWorld(1992, [
        makePerson('culprit', 1965),
        makePerson('victim', 1960),
        makePerson('bystander', 1970),
      ]);
      const crime = makeCrime({
        id: 'det-1',
        kind: 'burglary',
        when: { year: 1992, day: 10, hour: 21 },
        stolenOrDamaged: { item: 'television', value: 300 },
      });
      const cams: SecurityCamera[] = [{ id: 'cam-1', position: { x: 12, y: 11 }, radius: 25 }];

      let st = createCrimeState('det-evidence', 10);
      st = recordCrime(st, crime, []);
      const traces = leaveTraces(crime, {
        seed: 'det-evidence',
        cameras: cams,
        bloodSpilled: true,
        weather: 'rain',
        droppedItem: { label: 'monogrammed lighter', ownerId: 'culprit' },
      });
      st = recordTraces(st, 'det-1', traces);

      const weatherOf = (_place: string, d: number): WeatherKind => (d % 2 === 0 ? 'rain' : 'clear');
      st = advanceDay(st, world, 11, { year: 1992, weatherOf });
      st = collectCrimeTraces(st, world, 'det-1', 'town_a', 12, { year: 1992 });

      if (doRoundTrip) {
        st = JSON.parse(JSON.stringify(st));
      }

      st = enrollOnFile(st, {
        id: 'rec-det',
        personId: 'culprit',
        jurisdictionId: 'town_a',
        scope: 'national',
        reason: 'arrest',
        enrolledYear: 1996,
        enrolledDay: 1470,
        hasPrints: true,
        hasDna: true,
      });
      st = advanceDay(st, { ...world, year: 1996 }, 1471, { year: 1996, weatherOf });
      return JSON.stringify(st);
    };

    const a = run(false);
    const b = run(false);
    const c = run(true);
    expect(a).toBe(b);
    expect(a).toBe(c);
  });

  it('performance: advanceDay for 2,000 people and 20 active cases WITH traces & identity files runs under 5 ms', () => {
    const { world, townByPerson, workplaceByPerson } = buildPerfWorld('perf-g008-2000', 2000, 1985, 5);
    const livingIds = Object.keys(world.people).sort();
    expect(livingIds.length).toBe(2000);

    const townOf = (id: Id): string => townByPerson[id] ?? 'town:0';
    const placeTownOf = (placeId: string): string => placeId.replace('place:', 'town:');
    const jurisdictionOf = (placeId: string): string => `jur_${placeTownOf(placeId)}`;
    const weatherOf = (_placeId: string, day: number): WeatherKind =>
      day % 4 === 0 ? 'rain' : 'clear';

    const ctx: CrimeContext = {
      year: 1985,
      townOf,
      placeTownOf,
      jurisdictionOf,
      workplaceOf: (id) => workplaceByPerson[id],
      weatherOf,
    };

    let state = createCrimeState('perf-g008', 0);
    const cams: SecurityCamera[] = [{ id: 'cam-main', position: { x: 6, y: 6 }, radius: 30 }];

    // Enroll 200 citizens on file (licences/arrests)
    for (let i = 0; i < 200; i++) {
      const pid = livingIds[i * 5]!;
      const rec: IdentityFileRecord = {
        id: `file-${i}`,
        personId: pid,
        jurisdictionId: `jur_${townOf(pid)}`,
        scope: i % 3 === 0 ? 'national' : 'local',
        reason: i % 2 === 0 ? 'arrest' : 'license',
        enrolledYear: 1980,
        enrolledDay: 1,
        hasPrints: i % 2 === 0,
        hasDna: false,
      };
      state = enrollOnFile(state, rec);
    }

    // 20 active cases across all 5 crime severities + physical traces (half collected, half decaying at scene)
    const kinds = ['robbery', 'burglary', 'theft', 'assault', 'shoplifting'] as const;
    const severities = [0.75, 0.58, 0.40, 0.65, 0.25] as const;
    for (let c = 0; c < 20; c++) {
      const townIdx = c % 5;
      const offset = townIdx * 400 + ((c * 19) % 350);
      const culpritId = livingIds[offset]!;
      const victimId = livingIds[offset + 1]!;
      const crime: CrimeEvent = {
        id: `perf-crime-${c}`,
        kind: kinds[c % 5]!,
        culpritId,
        victimId,
        placeId: `place:${townIdx}`,
        townId: `town:${townIdx}`,
        position: { x: 0, y: 0 },
        when: { year: 1985, day: 0, hour: 15 },
        severity: severities[c % 5]!,
        culpritAppearance: DEFAULT_APPEARANCE,
        stolenOrDamaged: { item: 'television', value: 250 },
      };
      const nearby = [];
      for (let n = 1; n < 10; n++) {
        const pid = livingIds[offset + n]!;
        nearby.push({ person: world.people[pid]!, position: { x: 4 + n * 2, y: (n % 3) * 3 } });
      }
      const sightings = witnessesOf(crime, nearby, { seed: 'perf-g008', world });
      state = recordCrime(state, crime, sightings);
      const traces = leaveTraces(crime, {
        seed: 'perf-g008',
        cameras: cams,
        bloodSpilled: c % 4 === 0,
      });
      state = recordTraces(state, crime.id, traces);
      if (c < 10) {
        state = collectCrimeTraces(state, world, crime.id, `jur_town:${townIdx}`, 0, { year: 1985 });
      }
    }

    // Warm up 10 simulated days so gossip spreads to ~1,000+ active ledger entries & JIT settles
    for (let d = 1; d <= 10; d++) {
      state = advanceDay(state, world, d, ctx);
    }

    const timesMs: number[] = [];
    for (let day = 11; day <= 20; day++) {
      const t0 = performance.now();
      state = advanceDay(state, world, day, ctx);
      timesMs.push(performance.now() - t0);
    }

    const sorted = [...timesMs].sort((a, b) => a - b);
    const median = sorted[Math.floor(sorted.length / 2)]!;
    const mean = timesMs.reduce((a, b) => a + b, 0) / timesMs.length;
    const max = sorted[sorted.length - 1]!;
    const traceCount = Object.values(state.traces ?? {}).reduce((a, l) => a + l.length, 0);

    console.log(
      `PERF_G008_2000x20: median=${median.toFixed(2)}ms mean=${mean.toFixed(2)}ms max=${max.toFixed(2)}ms traces=${traceCount}`,
    );

    expect(FORENSIC_ERA.fingerprintLocalYear).toBe(1910);
    expect(median).toBeLessThan(5);
    expect(policeKnowledge(state, 'jur_town:0', 20).cases.length).toBeGreaterThan(0);
  });
});
