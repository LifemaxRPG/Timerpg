import { beforeEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_SEVERITY,
  type CrimeEvent,
  type CrimeKind,
  type EvidenceTrace,
} from '../crime';
import { fullName, isAlive } from '../sim';
import { planTown } from '../town/plan';
import {
  PROFESSOR_ID,
  addCarriedItem,
  addPlayerMemory,
  addTraveller,
  addTravellerTrace,
  branch,
  caseStatusAt,
  clearTimelineCache,
  compileSimInterventions,
  createTimeline,
  crimeEventToIntervention,
  generateProfessorTrail,
  getTimelineCacheStats,
  inspectCarriedItems,
  looseThreads,
  npcMemoryOfPlayer,
  presentAt,
  recordPlayerInteraction,
  recordTimelineCrime,
  selfErase,
  tracesAt,
  type Timeline,
} from './index';

describe('src/timeline — time travel & timeline engine (G017)', () => {
  beforeEach(() => {
    clearTimelineCache();
  });

  it('branch never mutates the original timeline, and the same timeline replays the same present (including across JSON round-trip)', () => {
    const base = createTimeline({ seed: 'alpha', startYear: 1950 });
    const snapshotBefore = JSON.stringify(base);

    const b1952 = presentAt(base, 1952);
    const openBiz = Object.values(b1952.world.businesses).find((b) => b.closedYear === undefined)!;
    expect(openBiz).toBeDefined();

    const branched = branch(base, {
      year: 1952,
      placeId: 'capital',
      kind: 'invest',
      businessId: openBiz.id,
      amount: 400,
      summary: 'Seed investment in 1952',
    });

    // Original timeline is strictly untouched
    expect(JSON.stringify(base)).toBe(snapshotBefore);
    expect(base.interventions).toHaveLength(0);
    expect(branched.interventions).toHaveLength(1);

    // Same timeline replays the exact same present, even after JSON serialization and cache clear
    const first = presentAt(branched, 1965);
    clearTimelineCache();
    const roundTripped = JSON.parse(JSON.stringify(branched)) as Timeline;
    const second = presentAt(roundTripped, 1965);

    expect(JSON.stringify(second.world)).toBe(JSON.stringify(first.world));
    expect(JSON.stringify(second.changes)).toBe(JSON.stringify(first.changes));
    expect(second.world.businesses[openBiz.id]!.capital).toBeGreaterThan(
      presentAt(base, 1965).world.businesses[openBiz.id]!.capital,
    );
  });

  it('an arson in 1952 changes the store history by 2025, and the changes report names it', () => {
    const base = createTimeline({ seed: 'alpha', startYear: 1950 });
    const base1952 = presentAt(base, 1952);
    const base2025 = presentAt(base, 2025);

    // Pick a store open in 1952 (prefer one still open in 2025 so the contrast is sharpest)
    const targetStore =
      Object.values(base1952.world.businesses).find(
        (b) => b.closedYear === undefined && base2025.world.businesses[b.id]?.closedYear === undefined,
      ) ?? Object.values(base1952.world.businesses).find((b) => b.closedYear === undefined)!;

    expect(targetStore).toBeDefined();

    const burned = branch(base, {
      year: 1952,
      placeId: 'capital',
      kind: 'arson',
      businessId: targetStore.id,
      replacementLotLabel: 'Parking garage',
      summary: `Burned down ${targetStore.name} in 1952`,
    });

    const alt2025 = presentAt(burned, 2025);

    // Store actually closed in 1952 in the real sim
    expect(alt2025.world.businesses[targetStore.id]?.closedYear).toBe(1952);
    expect(alt2025.changes.hasChanges).toBe(true);

    // Report explicitly names the store in businessesClosedEarly and buildings.demolishedOrReplaced
    const closedEntry = alt2025.changes.businessesClosedEarly.find((b) => b.id === targetStore.id);
    expect(closedEntry).toBeDefined();
    expect(closedEntry!.name).toBe(targetStore.name);
    expect(closedEntry!.reason).toMatch(/1952 arson/i);

    const lotEntry = alt2025.changes.buildings.demolishedOrReplaced.find(
      (b) => b.businessId === targetStore.id || b.beforeLabel === targetStore.name,
    );
    expect(lotEntry).toBeDefined();
    expect(lotEntry!.afterLabel).toBe('Parking garage');
    expect(alt2025.changes.summary.some((line) => line.includes(targetStore.name))).toBe(true);
  });

  it('anchoring rules 1-2: carried item survives erasure of its maker, and NPCs remember only the current timeline', () => {
    let base = createTimeline({ seed: 'alpha', startYear: 1950 });
    const base2025 = presentAt(base, 2025);

    // Find a founding mother who has a child born after 1951 (and ideally a grandchild too!)
    const ancestor = Object.values(base2025.world.people).find(
      (p) =>
        p.id.startsWith('gen:') &&
        p.sex === 'F' &&
        p.children.some((cid) => base2025.world.people[cid]!.birthYear > 1951),
    )!;
    expect(ancestor).toBeDefined();

    const descendantId = ancestor.children.find((cid) => base2025.world.people[cid]!.birthYear > 1951)!;
    const descendant = base2025.world.people[descendantId]!;
    const makerName = fullName(descendant);

    // Player gets a coffee receipt from the descendant in 2025 and remembers them
    base = addCarriedItem(base, {
      id: 'item:coffee-receipt',
      label: 'Coffee receipt',
      acquiredYear: 2025,
      acquiredPlaceId: 'capital',
      makerId: descendant.id,
      makerName,
      provenanceNote: `Sold by ${makerName} on a 2025 morning`,
    });
    base = addPlayerMemory(base, {
      id: 'mem:coffee-morning',
      year: 2025,
      placeId: 'capital',
      subjectId: descendant.id,
      summary: `Bought coffee from ${makerName} this morning`,
    });

    // Also record an interaction with an anchored bystander on branch A only
    const bystander = Object.values(base2025.world.people).find(
      (p) => isAlive(p) && p.id !== ancestor.id && p.id !== descendant.id,
    )!;
    const branchA = recordPlayerInteraction(base, {
      id: 'ix:code-given',
      year: 2024,
      placeId: 'capital',
      npcId: bystander.id,
      summary: 'Gave the player a vault code',
    });

    expect(npcMemoryOfPlayer(branchA, bystander.id, 2025).remembersPlayer).toBe(true);

    // Branch B kills the ancestor in 1951 — erasing the maker and discarding branch A's interaction
    const branchB = branch(base, {
      year: 1951,
      placeId: 'capital',
      kind: 'kill',
      targetId: ancestor.id,
    });

    const b2025 = presentAt(branchB, 2025);
    expect(b2025.changes.erasedPeople.some((p) => p.id === descendant.id)).toBe(true);

    // Rule 1: carried item survives intact with its original history even though its maker never existed
    const carried = inspectCarriedItems(branchB, 2025);
    expect(carried).toHaveLength(1);
    expect(carried[0]!.survives).toBe(true);
    expect(carried[0]!.item.label).toBe('Coffee receipt');
    expect(carried[0]!.item.makerName).toBe(makerName);
    expect(carried[0]!.makerExists).toBe(false);
    expect(carried[0]!.anachronisticProvenance).toBe(true);
    expect(branchB.player.memories).toHaveLength(1);

    // Rule 2: anchored bystander in branch B does NOT remember branch A's interaction
    const memInB = npcMemoryOfPlayer(branchB, bystander.id, 2025);
    expect(memInB.existsInTimeline).toBe(true);
    expect(memInB.remembersPlayer).toBe(false);
  });

  it('selfErase removes the player records and reports both losses and gains', () => {
    const w1955 = presentAt(createTimeline({ seed: 'alpha', startYear: 1950 }), 1955).world;
    const familyMember = Object.values(w1955.people).find((p) => isAlive(p) && p.birthYear < 1940)!;
    expect(familyMember).toBeDefined();

    const initial = createTimeline({
      seed: 'alpha',
      startYear: 1950,
      player: {
        playerId: 'player',
        birthYear: 1952,
        originYear: 2025,
        records: {
          birthRecord: true,
          bankAccount: true,
          fingerprintsOnFile: true,
          dnaOnFile: true,
          legalIdentity: true,
          passportEligible: true,
          jobReferences: ['Alderwick Foundry', 'Capital Library'],
          knownFamilyIds: [familyMember.id],
        },
      },
      identityFiles: {
        player: [
          {
            id: 'file:player:1',
            personId: 'player',
            jurisdictionId: 'capital',
            scope: 'national',
            reason: 'license',
            enrolledYear: 1975,
            enrolledDay: 100,
            hasPrints: true,
            hasDna: true,
          },
        ],
      },
    });

    // Before self-erasure, family recognizes the player
    expect(npcMemoryOfPlayer(initial, familyMember.id, 1955).knowsAsFamily).toBe(true);

    const { timeline: erased, report } = selfErase(initial, 1953, 'capital');

    // Non-mutating
    expect(initial.player.selfErased).toBe(false);
    expect(initial.player.records.birthRecord).toBe(true);

    // Records wiped
    expect(erased.player.selfErased).toBe(true);
    expect(erased.player.selfErasedYear).toBe(1953);
    expect(erased.player.records.birthRecord).toBe(false);
    expect(erased.player.records.bankAccount).toBe(false);
    expect(erased.player.records.fingerprintsOnFile).toBe(false);
    expect(erased.player.records.legalIdentity).toBe(false);
    expect(erased.player.records.knownFamilyIds).toEqual([]);
    expect(erased.player.records.jobReferences).toEqual([]);
    expect(erased.identityFiles['player']).toBeUndefined();

    // Report lists concrete losses and gains
    expect(report.alreadyErased).toBe(false);
    expect(report.erasedRecords).toContain('birth_record');
    expect(report.erasedRecords).toContain('bank_account');
    expect(report.erasedRecords).toContain('fingerprints_on_file');
    expect(report.erasedRecords).toContain('family_ties');
    expect(report.losses.some((l) => l.code === 'no_legal_identity')).toBe(true);
    expect(report.losses.some((l) => l.code === 'no_references')).toBe(true);
    expect(report.gains.some((g) => g.code === 'no_prints_on_file')).toBe(true);

    // Family member no longer remembers the player
    const famView = npcMemoryOfPlayer(erased, familyMember.id, 1955);
    expect(famView.knowsAsFamily).toBe(false);
    expect(famView.remembersPlayer).toBe(false);
  });

  it('a 1952 arson is active in 1953 and a cold case in 2025; erasing the only witness ends the testimony', () => {
    const base = createTimeline({ seed: 'alpha', startYear: 1950 });
    const w1952 = presentAt(base, 1952).world;
    const store = Object.values(w1952.businesses).find((b) => b.closedYear === undefined)!;
    const witness = Object.values(w1952.people).find(
      (p) => isAlive(p) && p.id !== store.owner && 1952 - p.birthYear >= 20 && 1952 - p.birthYear <= 40,
    )!;
    expect(store).toBeDefined();
    expect(witness).toBeDefined();

    const tl = recordTimelineCrime(
      base,
      {
        id: 'crime:arson-1952',
        kind: 'arson',
        year: 1952,
        placeId: 'capital',
        culpritId: 'player',
        businessId: store.id,
        victimId: store.owner,
        reported: true,
        witnesses: [
          {
            personId: witness.id,
            name: fullName(witness),
            confidence: 0.85,
            identifiedCulprit: false,
            testimonySummary: 'Saw a stranger carry a gas can out the back door',
          },
        ],
      },
      { alsoBranch: true, replacementLotLabel: 'Parking garage' },
    );

    // Active in 1953
    const status1953 = caseStatusAt(tl, 'crime:arson-1952', 1953);
    expect(status1953.status).toBe('active');
    expect(status1953.activelyLooking).toBe(true);
    expect(status1953.isColdCase).toBe(false);
    expect(status1953.hasTestimony).toBe(true);
    expect(status1953.activeWitnesses).toContain(witness.id);

    // 73-year-old cold case in 2025
    const status2025 = caseStatusAt(tl, 'crime:arson-1952', 2025);
    expect(status2025.status).toBe('cold');
    expect(status2025.activelyLooking).toBe(false);
    expect(status2025.isColdCase).toBe(true);
    expect(status2025.statuteExpired).toBe(true);
    expect(status2025.ageYears).toBe(73);
    expect(status2025.reasons.some((r) => /cold/i.test(r))).toBe(true);

    // Erasing the only witness before the crime ends the testimony
    const noWitnessTl = branch(tl, {
      year: 1951,
      placeId: 'capital',
      kind: 'kill',
      targetId: witness.id,
    });
    const statusAfterWitnessErased = caseStatusAt(noWitnessTl, 'crime:arson-1952', 1953);
    expect(statusAfterWitnessErased.hasTestimony).toBe(false);
    expect(statusAfterWitnessErased.activeWitnesses).toEqual([]);
    expect(statusAfterWitnessErased.erasedWitnesses).toContain(witness.id);
    expect(statusAfterWitnessErased.status).toBe('no_evidence');
    expect(statusAfterWitnessErased.activelyLooking).toBe(false);
  });

  it('erasing the victim before the crime means the crime never happened', () => {
    const base = createTimeline({ seed: 'alpha', startYear: 1950 });
    const w1954 = presentAt(base, 1954).world;
    const people = Object.values(w1954.people).filter((p) => isAlive(p) && 1954 - p.birthYear >= 20);
    const victim = people[0]!;
    const witness = people[1]!;

    const withCrime = recordTimelineCrime(
      base,
      {
        id: 'crime:robbery-1954',
        kind: 'robbery',
        year: 1954,
        placeId: 'capital',
        culpritId: 'player',
        victimId: victim.id,
        reported: true,
        witnesses: [{ personId: witness.id, confidence: 0.9 }],
      },
      { alsoBranch: true },
    );

    expect(caseStatusAt(withCrime, 'crime:robbery-1954', 1955).status).toBe('active');

    // Go back to 1951 and erase the victim before 1954
    const victimErased = branch(withCrime, {
      year: 1951,
      placeId: 'capital',
      kind: 'kill',
      targetId: victim.id,
    });

    const status = caseStatusAt(victimErased, 'crime:robbery-1954', 1955);
    expect(status.status).toBe('never_happened');
    expect(status.crimeOccurred).toBe(false);
    expect(status.activelyLooking).toBe(false);
    expect(status.reasons.some((r) => /never happened/i.test(r))).toBe(true);

    // Loose thread notes the player still remembers a crime that never happened
    const threads = looseThreads(victimErased, 1955);
    expect(threads.some((t) => t.kind === 'erased_crime_memory')).toBe(true);
  });

  it('handles fire/flood record loss, statutes of limitations, and modern forensic cold-case reopening', () => {
    const base = createTimeline({
      seed: 'alpha',
      startYear: 1950,
      player: {
        birthYear: 1995,
        originYear: 2025,
        records: { fingerprintsOnFile: true, dnaOnFile: true },
      },
    });
    const w1955 = presentAt(base, 1955).world;
    const witness = Object.values(w1955.people).find((p) => isAlive(p) && 1955 - p.birthYear >= 20)!;

    // 1. Petty shoplifting expires after statute of limitations (2 years)
    const shopTl = recordTimelineCrime(base, {
      id: 'crime:shoplift-1955',
      kind: 'shoplifting',
      year: 1955,
      placeId: 'capital',
      culpritId: 'player',
      reported: true,
      witnesses: [{ personId: witness.id, confidence: 0.8 }],
    });
    expect(caseStatusAt(shopTl, 'crime:shoplift-1955', 1955).status).toBe('active');
    const expiredStatus = caseStatusAt(shopTl, 'crime:shoplift-1955', 1960);
    expect(expiredStatus.status).toBe('expired');
    expect(expiredStatus.statuteExpired).toBe(true);
    expect(expiredStatus.activelyLooking).toBe(false);

    // 2. Flood destroys pre-1995 paper records
    const flooded = branch(shopTl, {
      year: 1956,
      placeId: 'capital',
      kind: 'record_flood',
      summary: 'Spring flood soaked the precinct basement files',
    });
    const floodStatus = caseStatusAt(flooded, 'crime:shoplift-1955', 1956);
    expect(floodStatus.status).toBe('records_lost');
    expect(floodStatus.recordsIntact).toBe(false);
    expect(floodStatus.recordsLostCause).toBe('flood');
    expect(floodStatus.activelyLooking).toBe(false);

    // 3. 1955 murder with collected DNA trace: cold in 1975, reopened in 2000 via DNA database (with time-travel anomaly!),
    //    but unmatchable after selfErase!
    const dnaTrace: EvidenceTrace = {
      id: 'trace:dna:1',
      crimeId: 'crime:murder-1955',
      kind: 'dna',
      placeId: 'capital',
      position: { x: 10, y: 12 },
      createdYear: 1955,
      createdDay: 5 * 365,
      quality: 0.9,
      initialQuality: 0.9,
      exposure: 'indoor',
      collected: true,
      collectedYear: 1955,
      destroyed: false,
      sourcePersonId: 'player',
    };
    const murderTl = recordTimelineCrime(base, {
      id: 'crime:murder-1955',
      kind: 'murder',
      year: 1955,
      placeId: 'capital',
      culpritId: 'player',
      reported: true,
      witnesses: [],
      traces: [dnaTrace],
    });
    expect(caseStatusAt(murderTl, 'crime:murder-1955', 1975).status).toBe('cold');
    const reopened2000 = caseStatusAt(murderTl, 'crime:murder-1955', 2000);
    expect(reopened2000.canReopenWithModernForensics).toBe(true);
    expect(reopened2000.activelyLooking).toBe(true);
    expect(reopened2000.forensicAnomalies.some((a) => a.kind === 'before_birth')).toBe(true);

    const { timeline: ghostMurderTl } = selfErase(murderTl, 1954);
    const ghost2000 = caseStatusAt(ghostMurderTl, 'crime:murder-1955', 2000);
    expect(ghost2000.playerIdentifiableOnFile).toBe(false);
    expect(ghost2000.canReopenWithModernForensics).toBe(false);
    expect(ghost2000.activelyLooking).toBe(false);
  });

  it('a loose thread is reported, not refused (e.g. professor lab never built)', () => {
    const base = createTimeline({ seed: 'alpha', startYear: 1950 });

    // Intervention that removes the cause of the player's own trip is allowed, not thrown/refused
    const noLab = branch(base, {
      year: 1952,
      placeId: 'capital',
      kind: 'prevent_building',
      buildingLabel: "Professor's lab",
      causeTag: 'professors_lab',
      summary: 'Town council rejected the lab permit in 1952',
    });

    const snap = presentAt(noLab, 2025);
    expect(snap.changes.buildings.neverBuilt.some((b) => b.label === "Professor's lab")).toBe(true);

    const threads = looseThreads(noLab, 2025);
    expect(threads.length).toBeGreaterThanOrEqual(1);
    const labThread = threads.find((t) => t.kind === 'cause_removed');
    expect(labThread).toBeDefined();
    expect(labThread!.allowed).toBe(true);
    expect(labThread!.fact).toMatch(/unanchored/i);
    expect(labThread!.journalLine.length).toBeGreaterThan(10);
    expect(labThread!.dialogueHook.length).toBeGreaterThan(10);
  });

  it('the professor traces survive every intervention, and the same seed gives the same trail (5-9 traces across eras & continents)', () => {
    const trailA1 = generateProfessorTrail('gamma');
    const trailA2 = generateProfessorTrail('gamma');
    const trailB = generateProfessorTrail('delta');

    expect(JSON.stringify(trailA1)).toBe(JSON.stringify(trailA2));
    expect(JSON.stringify(trailA1)).not.toBe(JSON.stringify(trailB));

    // Check 5..9 traces across multiple seeds, spanning eras and continents, each pointing loosely to the next
    for (const seed of ['alpha', 'beta', 'gamma', 'delta', 'epsilon', 'zeta']) {
      const prof = generateProfessorTrail(seed);
      expect(prof.id).toBe(PROFESSOR_ID);
      expect(prof.traces.length).toBeGreaterThanOrEqual(5);
      expect(prof.traces.length).toBeLessThanOrEqual(9);

      const distinctContinents = new Set(prof.traces.map((t) => t.continent));
      const distinctEras = new Set(prof.traces.map((t) => t.eraLabel));
      expect(distinctContinents.size).toBeGreaterThanOrEqual(3);
      expect(distinctEras.size).toBeGreaterThanOrEqual(3);

      for (let i = 0; i < prof.traces.length - 1; i++) {
        expect(prof.traces[i]!.clueToNext).toBeTruthy();
        expect(prof.traces[i]!.nextHint?.continent).toBe(prof.traces[i + 1]!.continent);
      }
      expect(prof.traces[prof.traces.length - 1]!.clueToNext).toBeUndefined();
    }

    // Traces survive EVERY intervention (murder, arson, prevent_building, selfErase)
    let tl = createTimeline({ seed: 'alpha', startYear: 1950 });
    const prof = tl.travellers.find((t) => t.id === PROFESSOR_ID)!;
    const firstTrace = prof.traces[0]!;
    const secondTrace = prof.traces[1]!;

    expect(tracesAt(tl, firstTrace.year - 1, firstTrace.placeId)).toHaveLength(0);
    expect(tracesAt(tl, firstTrace.year, firstTrace.placeId)).toHaveLength(1);

    const w1952 = presentAt(tl, 1952).world;
    const biz = Object.values(w1952.businesses)[0]!;
    const victim = Object.values(w1952.people).find(isAlive)!;

    tl = branch(tl, { year: 1951, placeId: 'capital', kind: 'kill', targetId: victim.id });
    tl = branch(tl, { year: 1952, placeId: 'capital', kind: 'arson', businessId: biz.id });
    tl = branch(tl, {
      year: 1952,
      placeId: 'capital',
      kind: 'prevent_building',
      buildingLabel: "Professor's lab",
      causeTag: 'professors_lab',
    });
    tl = selfErase(tl, 1953).timeline;

    // Every professor trace is still intact
    expect(tracesAt(tl, firstTrace.year, firstTrace.placeId, { exactYearOnly: true })[0]!.id).toBe(firstTrace.id);
    expect(tracesAt(tl, secondTrace.year, secondTrace.placeId)[0]!.id).toBe(secondTrace.id);

    // Additional unanchored travellers also work outside the sim
    tl = addTraveller(tl, { id: 'trav:mira', name: 'Mira', role: 'traveller', traces: [] });
    tl = addTravellerTrace(tl, 'trav:mira', {
      year: 1977,
      continent: 'Vespera',
      placeId: 'place:fenwick',
      placeName: 'Fenwick',
      kind: 'cache',
      summary: 'Left a brass compass inside the station locker.',
    });
    expect(tracesAt(tl, 1977, 'place:fenwick', { travellerId: 'trav:mira' })).toHaveLength(1);
  });

  it('the cache returns the same present without a second replay', () => {
    const base = createTimeline({ seed: 'alpha', startYear: 1950 });
    const w1952 = presentAt(base, 1952).world;
    const biz = Object.values(w1952.businesses)[0]!;
    const edited = branch(base, { year: 1952, placeId: 'capital', kind: 'sabotage', businessId: biz.id });

    clearTimelineCache();
    const first = presentAt(edited, 1975);
    const statsAfterFirst = getTimelineCacheStats();

    expect(first.fromCache).toBe(false);
    expect(statsAfterFirst.simReplays).toBe(2); // 1 baseline + 1 edited branch

    // Repeat call for exact same (timeline, year): zero additional sim replays
    const second = presentAt(edited, 1975);
    const statsAfterSecond = getTimelineCacheStats();

    expect(second.fromCache).toBe(true);
    expect(statsAfterSecond.simReplays).toBe(statsAfterFirst.simReplays);
    expect(statsAfterSecond.presentCacheHits).toBe(1);
    expect(second.world).toBe(first.world);
    expect(second.changes).toBe(first.changes);

    // Earlier year on the SAME branch (1960 <= 1975, no interventions between 1960 and 1975) also reuses cached sim!
    const earlier = presentAt(edited, 1960);
    const statsAfterEarlier = getTimelineCacheStats();
    expect(earlier.world.year).toBe(1960);
    expect(statsAfterEarlier.simReplays).toBe(statsAfterFirst.simReplays);
    expect(statsAfterEarlier.simCacheHits).toBeGreaterThanOrEqual(2);
  });

  it('maps every CrimeKind from src/crime/types.ts to deterministic sim interventions', () => {
    const allKinds: CrimeKind[] = [
      'vandalism',
      'shoplifting',
      'theft',
      'car_theft',
      'burglary',
      'assault',
      'robbery',
      'murder',
    ];
    let tl = createTimeline({ seed: 'alpha', startYear: 1950 });
    for (const kind of allKinds) {
      const ev: CrimeEvent = {
        id: `c:${kind}`,
        kind,
        severity: DEFAULT_SEVERITY[kind],
        position: { x: 0, y: 0 },
        placeId: 'capital',
        culpritId: 'player',
        victimId: 'gen:0:h',
        culpritAppearance: { height: 'average', build: 'average', hair: 'dark', clothingColor: 'navy' },
        when: { year: 1955, day: 1825, hour: 14 },
      };
      tl = branch(tl, crimeEventToIntervention(ev, { businessId: 'b:gen:0' }));
    }

    const compiled = compileSimInterventions(tl, 1960);
    expect(compiled.length).toBeGreaterThanOrEqual(allKinds.length);
    expect(compiled.some((iv) => iv.kind === 'kill' && iv.targetId === 'gen:0:h')).toBe(true);
    expect(compiled.some((iv) => iv.kind === 'sabotage' && iv.businessId === 'b:gen:0')).toBe(true);
    expect(compiled.some((iv) => iv.kind === 'rumor' && iv.targetId === 'gen:0:h')).toBe(true);
    expect(compiled.some((iv) => iv.kind === 'invest' && iv.businessId === 'b:gen:0')).toBe(true);
  });

  it('measures replay and cache timing on the capital-sized town (gamma)', () => {
    // Warm immutable town plan cache once (one-time per seed in the game)
    planTown('gamma');
    clearTimelineCache();

    const base = createTimeline({ seed: 'gamma', startYear: 1950, placeId: 'capital' });
    const tBase0 = performance.now();
    const base2025 = presentAt(base, 2025);
    const baseMs = performance.now() - tBase0;

    const biz = Object.values(base2025.world.businesses)[0]!;
    const burned = branch(base, {
      year: 1952,
      placeId: 'capital',
      kind: 'arson',
      businessId: biz.id,
      replacementLotLabel: 'Parking garage',
    });

    const tJump2025 = performance.now();
    const alt2025 = presentAt(burned, 2025);
    const jump2025Ms = performance.now() - tJump2025;

    const tCacheHit = performance.now();
    const hit2025 = presentAt(burned, 2025);
    const cacheHitMs = performance.now() - tCacheHit;

    const tEarlier = performance.now();
    presentAt(burned, 1953);
    const earlierCachedMs = performance.now() - tEarlier;

    console.log(
      `PERF_G017_CAPITAL(gamma, pop=${Object.values(alt2025.world.people).filter(isAlive).length}): ` +
        `baseline2025=${baseMs.toFixed(1)}ms | jump2025=${jump2025Ms.toFixed(1)}ms | ` +
        `cacheHit=${cacheHitMs.toFixed(3)}ms | earlierYearCached=${earlierCachedMs.toFixed(2)}ms`,
    );

    expect(hit2025.fromCache).toBe(true);
    expect(cacheHitMs).toBeLessThan(10);
  });
});
