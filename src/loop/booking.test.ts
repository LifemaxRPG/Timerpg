import { describe, expect, it } from 'vitest';
import {
  act,
  advance,
  arrest,
  attitude,
  civilianSpotted,
  create,
  load,
  policeView,
  pruneResolvedState,
  save,
  tipOff,
  type LoopSim,
} from './index';
import { TimerpgSim, civilianSpottedJson, simCall, tipOffJson } from './bridge';
import { CAPITAL_IDS, CAPITAL_JURISDICTION, buildStubCapital } from './stubCapital';
import { fenceRisk, type Fence } from '../economy/fence';
import { FORENSIC_ERA } from '../crime';
import { createPlanState, parsePlan } from '../plan';

describe('G014: booking pipeline, civilians who call it in, small saves & fence informants', () => {
  describe('1. Booking pipeline (prints, DNA by era, cold cases, pawn links & plain-English findings)', () => {
    it('books a 1978 suspect with fingerprints, cracks a planted burglary cold case, links a pawned watch, and returns plain-English findings', () => {
      let sim = create(buildStubCapital({ seed: 'g014-booking-1978', year: 1978, startDay: 1, startHour: 2 }));

      // 1. Unwitnessed 2 a.m. burglary at Hale's General Store steals a serialized pocket watch
      const burg = act(sim, {
        kind: 'take',
        subkind: 'house',
        placeId: CAPITAL_IDS.haleStore,
        item: {
          id: 'item:watch_g014',
          label: 'a gold Hamilton pocket watch',
          value: 4500,
          serialNumber: 'HAM-7814',
        },
        bystanders: [],
        gloved: false,
      });
      sim = burg.sim;
      expect(burg.crimeId).toBeDefined();
      expect(burg.tracesLeft.some((t) => t.kind === 'fingerprint')).toBe(true);

      // Pawn the stolen watch at Vance's Pawn & Loan
      const pawnSale = act(sim, {
        kind: 'sell',
        placeId: CAPITAL_IDS.pawnShop,
        clerkId: CAPITAL_IDS.pawnClerk,
        item: {
          id: 'item:watch_g014',
          label: 'a gold Hamilton pocket watch',
          value: 4500,
          serialNumber: 'HAM-7814',
          stolenFromCrimeId: burg.crimeId,
        },
      });
      sim = pawnSale.sim;
      expect(pawnSale.hot).toBe(true);
      expect(pawnSale.pawnTraceFlagged).toBe(true);

      // 2. Planted burglary at the bakery: witness sees a figure break in (leaves prints, no prints on file yet!)
      const coldBurg = act(sim, {
        kind: 'enter',
        placeId: CAPITAL_IDS.bakery,
        isOpen: false,
        forcedEntry: true,
        light: 0.75,
        bystanders: [
          {
            id: CAPITAL_IDS.bakerOwner,
            position: { x: 10, y: 6 },
            facing: { x: 0, y: -1 },
            activity: 'watching',
          },
        ],
        gloved: false,
      });
      sim = coldBurg.sim;
      expect(coldBurg.crimeId).toBeDefined();
      expect(coldBurg.tracesLeft.some((t) => t.kind === 'fingerprint')).toBe(true);

      // Advance 48h so the cold burglary is reported and police collect scene fingerprints (still no prints on file)
      sim = advance(sim, 48).sim;
      const beforeView = policeView(sim, CAPITAL_JURISDICTION);
      const coldBefore = beforeView.cases.find((c) => c.crimeId === coldBurg.crimeId);
      expect(coldBefore).toBeDefined();
      expect(coldBefore!.strength.physicalSuspectId).toBeUndefined();
      expect(sim.crimeState.identityFiles?.[sim.playerId]?.some((f) => f.hasPrints)).not.toBe(true);

      // 3. Later: player gets caught in a separate minor shoplifting and arrested at the station
      const shop = act(sim, {
        kind: 'take',
        subkind: 'shoplift',
        placeId: CAPITAL_IDS.market,
        item: { id: 'item:apples_g014', label: 'a bag of apples', value: 85 },
        bystanders: [
          {
            id: CAPITAL_IDS.marketOwner,
            position: { x: 84, y: 5 },
            facing: { x: 0, y: -1 },
            activity: 'watching',
          },
        ],
      });
      sim = shop.sim;
      sim = advance(sim, 24).sim;

      const outcome = arrest(sim, {
        jurisdictionId: CAPITAL_JURISDICTION,
        crimeIds: [shop.crimeId!],
        playerCash: 10000,
      });

      // Booking enrolled fingerprints (not DNA in 1978) into G008 identityFiles
      const files = outcome.sim.crimeState.identityFiles?.[sim.playerId] ?? [];
      expect(files.some((f) => f.reason === 'arrest' && f.hasPrints && !f.hasDna)).toBe(true);

      // Cold case cracked immediately and surfaced as plain-English booking findings
      expect(outcome.coldCasesTriggered).toContain(coldBurg.crimeId);
      expect(outcome.booking).toBeDefined();
      expect(outcome.booking!.printsEnrolled).toBe(true);
      expect(outcome.booking!.dnaEnrolled).toBe(false);
      expect(outcome.booking!.coldCaseHits.some((h) => h.crimeId === coldBurg.crimeId)).toBe(true);
      expect(outcome.bookingFindings.some((f) => /Fingerprints taken at booking/i.test(f))).toBe(true);
      expect(outcome.bookingFindings.some((f) => /matched an unsolved burglary/i.test(f))).toBe(true);
      expect(outcome.bookingFindings.some((f) => /Pawn ledger at .*linked .*pocket watch/i.test(f))).toBe(true);
    });

    it('enrolls DNA in 1995 and skips prints/DNA before 1910 (1895)', () => {
      // 1995: both prints and DNA enrolled
      let sim95 = create(buildStubCapital({ seed: 'g014-dna-1995', year: 1995, startHour: 10 }));
      const sh95 = act(sim95, {
        kind: 'take',
        subkind: 'shoplift',
        placeId: CAPITAL_IDS.haleStore,
        item: { id: 'item:cd_95', label: 'a compact disc', value: 1499 },
        bystanders: [
          { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
        ],
      });
      sim95 = advance(sh95.sim, 24).sim;
      const arr95 = arrest(sim95, { jurisdictionId: CAPITAL_JURISDICTION, playerCash: 50000 });
      expect(arr95.booking?.printsEnrolled).toBe(true);
      expect(arr95.booking?.dnaEnrolled).toBe(true);
      expect(arr95.booking?.scope).toBe('national');
      expect(arr95.bookingFindings.some((f) => /Fingerprints and DNA taken at booking/i.test(f))).toBe(true);

      // 1895: before FORENSIC_ERA.fingerprintLocalYear (1910)
      let sim95old = create(buildStubCapital({ seed: 'g014-preprint-1895', year: 1895, startHour: 10 }));
      expect(1895).toBeLessThan(FORENSIC_ERA.fingerprintLocalYear);
      const shOld = act(sim95old, {
        kind: 'take',
        subkind: 'shoplift',
        placeId: CAPITAL_IDS.haleStore,
        item: { id: 'item:flour_95', label: 'a sack of flour', value: 45 },
        bystanders: [
          { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
        ],
      });
      sim95old = advance(shOld.sim, 48).sim;
      const arrOld = arrest(sim95old, { jurisdictionId: CAPITAL_JURISDICTION, playerCash: 5000 });
      expect(arrOld.booking?.printsEnrolled).toBe(false);
      expect(arrOld.booking?.dnaEnrolled).toBe(false);
      expect(arrOld.bookingFindings.some((f) => /before fingerprinting/i.test(f))).toBe(true);
    });

    it('books a suspect automatically when G009 police transport completes (transportedToStation) and cracks a cold case on the next tick', () => {
      let sim = create(buildStubCapital({ seed: 'g014-transport-book', year: 1978, startDay: 1, startHour: 10 }));

      // 1. Plant an unsolved forced-entry burglary at the bakery with collected fingerprints
      const burg = act(sim, {
        kind: 'enter',
        placeId: CAPITAL_IDS.bakery,
        isOpen: false,
        forcedEntry: true,
        light: 0.75,
        bystanders: [
          {
            id: CAPITAL_IDS.bakerOwner,
            position: { x: 10, y: 6 },
            facing: { x: 0, y: -1 },
            activity: 'watching',
          },
        ],
        gloved: false,
      });
      sim = advance(burg.sim, 48).sim;
      expect(
        policeView(sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === burg.crimeId)?.strength
          .physicalSuspectId,
      ).toBeUndefined();

      // 2. A second active crime (2 a.m. window break at Hale's) that an officer is finishing transporting to the station
      const winBreak = act(sim, {
        kind: 'enter',
        placeId: CAPITAL_IDS.haleStore,
        isOpen: false,
        forcedEntry: true,
        light: 0.75,
        bystanders: [
          {
            id: CAPITAL_IDS.marcusHale,
            position: { x: 40, y: 5 },
            facing: { x: 0, y: -1 },
            activity: 'watching',
          },
        ],
        gloved: true,
      });
      sim = advance(winBreak.sim, 24).sim;

      const officer = Object.values(sim.policeState.officers)[0]!;
      const stationNode = officer.stationNodeId;
      const stationPos = sim.streetGraph.nodes.find((n) => n.id === 'node:station')?.position ?? { x: 85, y: 42 };
      const transportSource = `go place:${stationNode} carefully`;
      const parsed = parsePlan(transportSource);
      expect(parsed.ok).toBe(true);
      const transportPlanState = parsed.ok
        ? createPlanState({ plan: parsed.plan, actorId: officer.id }, sim.currentDay * 86400 + sim.currentHour * 3600)
        : undefined;

      const simInTransport: LoopSim = {
        ...sim,
        policeState: {
          ...sim.policeState,
          officers: {
            ...sim.policeState.officers,
            [officer.id]: {
              ...officer,
              assignment: 'transport',
              targetCrimeId: winBreak.crimeId!,
              targetPersonId: sim.playerId,
              targetNodeId: stationNode,
              planSource: transportSource,
              planState: transportPlanState,
            },
          },
          arrests: [
            ...sim.policeState.arrests,
            {
              id: 'arrest_transport_test_1',
              crimeId: winBreak.crimeId!,
              suspectId: sim.playerId,
              arrestingOfficerId: officer.id,
              assistingOfficerIds: [],
              jurisdictionId: CAPITAL_JURISDICTION,
              stationNodeId: stationNode,
              arrestDay: sim.currentDay,
              arrestYear: sim.year,
              arrestTimeSec: sim.currentDay * 86400 + sim.currentHour * 3600,
              arrestPosition: { x: stationPos.x, y: stationPos.y },
              method: 'foot_pursuit',
              searchedInventory: [],
              seizedContraband: [],
              cuffed: true,
              transportedToStation: false,
            },
          ],
        },
      };

      const adv = advance(simInTransport, 1);
      const completedArrest = adv.sim.policeState.arrests.find((a) => a.id === 'arrest_transport_test_1');
      expect(completedArrest?.transportedToStation).toBe(true);
      expect(adv.bookingEvents).toBeDefined();
      expect(adv.bookingEvents!.length).toBe(1);
      expect(adv.bookingEvents![0]!.printsEnrolled).toBe(true);
      expect(adv.bookingEvents![0]!.coldCaseHits.some((h) => h.crimeId === burg.crimeId)).toBe(true);

      // Police case now names the player via fingerprint match
      const afterCase = policeView(adv.sim, CAPITAL_JURISDICTION).cases.find(
        (c) => c.crimeId === burg.crimeId,
      );
      expect(afterCase?.strength.physicalSuspectId).toBe(sim.playerId);
    });

    it("enrolls prints at booking so an unreported burglary cracks automatically on the next tick once reported", () => {
      let sim = create(buildStubCapital({ seed: "g014-next-tick-cold", year: 1978, startDay: 1, startHour: 10 }));

      // Burglary witnessed by Tom Miller at the bakery (not yet reported to police — still on Day 1)
      const burg = act(sim, {
        kind: "enter",
        placeId: CAPITAL_IDS.bakery,
        isOpen: false,
        forcedEntry: true,
        light: 0.75,
        bystanders: [
          {
            id: CAPITAL_IDS.bakerOwner,
            position: { x: 10, y: 6 },
            facing: { x: 0, y: -1 },
            activity: "watching",
          },
        ],
        gloved: false,
      });
      sim = burg.sim;

      // Arrest the player immediately for a separate shoplifting before the burglary has even reached the police
      const shop = act(sim, {
        kind: "take",
        subkind: "shoplift",
        placeId: CAPITAL_IDS.haleStore,
        item: { id: "item:soap_nt", label: "a bar of soap", value: 60 },
        bystanders: [
          { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: "watching" },
        ],
      });
      sim = shop.sim;
      const arr = arrest(sim, {
        jurisdictionId: CAPITAL_JURISDICTION,
        crimeIds: [shop.crimeId!],
        playerCash: 5000,
      });
      expect(arr.booking?.printsEnrolled).toBe(true);
      // Burglary has not reached the police yet on Day 1
      expect(policeView(arr.sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === burg.crimeId)).toBeUndefined();

      // Advance 24h ("next morning"): burglary report lands, prints are collected, and G008 matches booking prints!
      const nextMorning = advance(arr.sim, 48);
      const cracked = policeView(nextMorning.sim, CAPITAL_JURISDICTION).cases.find(
        (c) => c.crimeId === burg.crimeId,
      );
      expect(cracked).toBeDefined();
      expect(cracked!.strength.physicalSuspectId).toBe(sim.playerId);
    });
  });

  describe('2. Civilians call it in (civilianSpotted & 1925 vs 1990 era latency)', () => {
    it('shopkeeper who saw a theft spots the player two days later and calls it in, faster in 1990 than in 1925, and dispatches an officer on arrival', () => {
      const setupShopkeeper = (year: number, seed: string) => {
        const s = create(buildStubCapital({ seed, year, startDay: 1, startHour: 10 }));
        // Forced-entry theft in view of Marcus Hale -> high severity -> report_on_sight
        const theft = act(s, {
          kind: 'take',
          subkind: 'house',
          placeId: CAPITAL_IDS.haleStore,
          item: { id: `item:register_${year}`, label: 'the cash box', value: 18000 },
          bystanders: [
            {
              id: CAPITAL_IDS.marcusHale,
              position: { x: 40, y: 5 },
              facing: { x: 0, y: -1 },
              activity: 'watching',
            },
          ],
        });
        // Two days later (48h)
        const twoDaysLater = advance(theft.sim, 48).sim;
        expect(attitude(twoDaysLater, CAPITAL_IDS.marcusHale).stance).toBe('report_on_sight');
        return { sim: twoDaysLater, crimeId: theft.crimeId! };
      };

      // Verify era latency distribution across 15 seeds: 1925 is measurably slower than 1990
      let sum1925 = 0;
      let sum1990 = 0;
      let slowerCount = 0;
      const nSeeds = 15;
      for (let i = 0; i < nSeeds; i++) {
        const seed = `g014-era-sweep-${i}`;
        const r25 = civilianSpotted(setupShopkeeper(1925, seed).sim, CAPITAL_IDS.marcusHale, {
          placeId: CAPITAL_IDS.haleStore,
          distance: 6,
          light: 0.9,
        });
        const r90 = civilianSpotted(setupShopkeeper(1990, seed).sim, CAPITAL_IDS.marcusHale, {
          placeId: CAPITAL_IDS.haleStore,
          distance: 6,
          light: 0.9,
        });
        expect(r25.reported).toBe(true);
        expect(r90.reported).toBe(true);
        expect(r90.means).toBe('telephone');
        expect(['early_telephone', 'in_person']).toContain(r25.means);
        sum1925 += r25.delayHours;
        sum1990 += r90.delayHours;
        if (r25.delayHours > r90.delayHours) slowerCount++;
      }
      const avg1925 = sum1925 / nSeeds;
      const avg1990 = sum1990 / nSeeds;
      console.log(
        `[G014 civilianSpotted era latency (n=${nSeeds})] 1925 avg=${avg1925.toFixed(2)}h | 1990 avg=${avg1990.toFixed(2)}h | 1925>1990 in ${slowerCount}/${nSeeds} seeds`,
      );
      expect(avg1925).toBeGreaterThan(avg1990 * 2);
      expect(slowerCount).toBeGreaterThanOrEqual(12);

      // Detailed delivery + officer dispatch check on one seed where 1925 is slower than 1990
      const { sim: sim25, crimeId: cid25 } = setupShopkeeper(1925, 'g014-era-sweep-0');
      const { sim: sim90, crimeId: cid90 } = setupShopkeeper(1990, 'g014-era-sweep-0');

      const spot25 = civilianSpotted(sim25, CAPITAL_IDS.marcusHale, {
        placeId: CAPITAL_IDS.haleStore,
        distance: 5,
        light: 0.9,
      });
      const spot90 = civilianSpotted(sim90, CAPITAL_IDS.marcusHale, {
        placeId: CAPITAL_IDS.haleStore,
        distance: 5,
        light: 0.9,
      });
      expect(spot25.delayHours).toBeGreaterThan(spot90.delayHours);

      const reportsBefore90 =
        policeView(spot90.sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === cid90)?.reportCount ?? 0;
      const reportsBefore25 =
        policeView(spot25.sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === cid25)?.reportCount ?? 0;

      // Advance by a window just past 1990's arrival but still before 1925's arrival
      const probeHours = Math.min(spot25.delayHours - 0.1, spot90.delayHours + 0.25);
      expect(probeHours).toBeGreaterThan(spot90.delayHours);
      expect(probeHours).toBeLessThan(spot25.delayHours);

      const adv90 = advance(spot90.sim, probeHours);
      const adv25Early = advance(spot25.sim, probeHours);

      // 1990 report has arrived & dispatched an officer to respond!
      const reportsAfter90 =
        policeView(adv90.sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === cid90)?.reportCount ?? 0;
      expect(reportsAfter90).toBe(reportsBefore90 + 1);
      expect(adv90.policePlans.some((p) => p.assignment === 'respond_crime' && p.crimeId === cid90)).toBe(true);

      // 1925 report is STILL in transit at probeHours
      const reportsEarly25 =
        policeView(adv25Early.sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === cid25)?.reportCount ?? 0;
      expect(reportsEarly25).toBe(reportsBefore25);
      expect(adv25Early.sim.crimeState.pendingReports.some((r) => spot25.reportIds.includes(r.id))).toBe(true);

      // Once 1925's remaining delay elapses, the 1925 report arrives and dispatches an officer too
      const adv25Done = advance(adv25Early.sim, spot25.delayHours - probeHours + 0.5);
      const reportsAfter25 =
        policeView(adv25Done.sim, CAPITAL_JURISDICTION).cases.find((c) => c.crimeId === cid25)?.reportCount ?? 0;
      expect(reportsAfter25).toBe(reportsBefore25 + 1);
      expect(adv25Done.policePlans.some((p) => p.assignment === 'respond_crime' && p.crimeId === cid25)).toBe(true);
    });

    it('does not report when attitude is not report_on_sight or line of sight is blocked, and works through TimerpgSim / simCall', () => {
      const init = buildStubCapital({ seed: 'g014-civ-bridge', year: 1990, startDay: 1, startHour: 10 });
      const sim = create(init);

      // Friendly civilian does not call police
      const calm = civilianSpotted(sim, CAPITAL_IDS.marcusHale, { placeId: CAPITAL_IDS.haleStore });
      expect(calm.reported).toBe(false);
      expect(calm.stance).toBe('serve');

      // Commit theft via TimerpgSim bridge, advance 24h, then call TimerpgSim.civilianSpotted via simCall
      TimerpgSim.init(JSON.stringify(init));
      simCall(
        JSON.stringify({
          method: 'act',
          args: [
            {
              kind: 'take',
              subkind: 'house',
              placeId: CAPITAL_IDS.haleStore,
              item: { id: 'item:bridge_box', label: 'a cash box', value: 16000 },
              bystanders: [
                {
                  id: CAPITAL_IDS.marcusHale,
                  position: { x: 40, y: 5 },
                  facing: { x: 0, y: -1 },
                  activity: 'watching',
                },
              ],
            },
          ],
        }),
      );
      simCall(JSON.stringify({ method: 'advance', args: [24] }));

      // Blocked LOS returns reported: false
      const blocked = JSON.parse(
        simCall(
          JSON.stringify({
            method: 'civilianSpotted',
            args: [CAPITAL_IDS.marcusHale, { placeId: CAPITAL_IDS.haleStore, hasLineOfSight: false }],
          }),
        ),
      );
      expect(blocked.reported).toBe(false);

      // Clear sighting reports via TimerpgSim & stateless JSON helper
      const spottedRes = JSON.parse(
        simCall(
          JSON.stringify({
            method: 'civilianSpotted',
            args: [CAPITAL_IDS.marcusHale, { placeId: CAPITAL_IDS.haleStore, distance: 6, light: 0.9 }],
          }),
        ),
      );
      expect(spottedRes.reported).toBe(true);
      expect(spottedRes.sim).toBeUndefined();
      expect(spottedRes.reportIds.length).toBeGreaterThan(0);

      // Stateless civilianSpottedJson works too
      const saved = TimerpgSim.save();
      const stateless = JSON.parse(
        civilianSpottedJson(saved, CAPITAL_IDS.bakerOwner, JSON.stringify({ placeId: CAPITAL_IDS.bakery })),
      );
      expect(stateless.reported).toBe(false);
    });
  });

  describe('3. Small saves (pruning resolved crimes at arrest() & save(), 100-day size measurement, lossless continuation)', () => {
    it('measurably shrinks a 100-day save by pruning resolved crimes and round-trips with identical continuation', () => {
      let sim = create(buildStubCapital({ seed: 'g014-100day-save', year: 1978, startDay: 1, startHour: 10 }));

      // Run 100+ simulated days with 10 crimes across town, letting gossip spread for 8 days before each arrest
      const places = [CAPITAL_IDS.haleStore, CAPITAL_IDS.bakery, CAPITAL_IDS.market];
      const witnesses = [
        { id: CAPITAL_IDS.marcusHale, pos: { x: 40, y: 5 } },
        { id: CAPITAL_IDS.bakerOwner, pos: { x: 10, y: 6 } },
        { id: CAPITAL_IDS.marketOwner, pos: { x: 84, y: 5 } },
      ];

      // Capture the pre-arrest crimeState entries for resolved crimes to compare against an unpruned 100-day save
      const unprunedCrimes: LoopSim['crimeState']['crimes'] = {};
      const unprunedLedger: LoopSim['crimeState']['ledger'] = {};
      const unprunedToldPairs: LoopSim['crimeState']['toldPairs'] = {};
      const unprunedTraces: NonNullable<LoopSim['crimeState']['traces']> = {};
      const unprunedCases: Record<string, Record<string, any>> = {};
      const unprunedResponded: Record<string, string> = {};
      let resolvedCycles = 0;

      for (let cycle = 0; cycle < 10; cycle++) {
        const idx = cycle % 3;
        const w = witnesses[idx]!;
        const theft = act(sim, {
          kind: 'take',
          subkind: 'house',
          placeId: places[idx]!,
          item: {
            id: `item:day100_${cycle}`,
            label: `stolen item #${cycle}`,
            value: 3500 + cycle * 400,
            serialNumber: `SER-100-${cycle}`,
          },
          bystanders: [
            { id: w.id, position: w.pos, facing: { x: 0, y: -1 }, activity: 'watching' },
            {
              id: CAPITAL_IDS.windowWoman,
              position: { x: w.pos.x, y: w.pos.y - 2 },
              facing: { x: 0, y: 1 },
              activity: 'watching',
            },
          ],
          gloved: false,
        });
        sim = theft.sim;

        // Let gossip spread across town for 8 days so toldPairs, ledger, traces & police cases fill up
        sim = advance(sim, 24 * 5).sim;

        // Arrest & resolve the first 8 cycles; leave the last 2 active & unresolved!
        if (cycle < 8) {
          const cid = theft.crimeId!;
          if (sim.crimeState.crimes[cid]) unprunedCrimes[cid] = sim.crimeState.crimes[cid]!;
          if (sim.crimeState.ledger[cid]) unprunedLedger[cid] = sim.crimeState.ledger[cid]!;
          if (sim.crimeState.toldPairs[cid]) unprunedToldPairs[cid] = sim.crimeState.toldPairs[cid]!;
          if (sim.crimeState.traces?.[cid]) unprunedTraces[cid] = sim.crimeState.traces[cid]!;
          for (const [jId, jur] of Object.entries(sim.crimeState.jurisdictions)) {
            if (jur.cases[cid]) {
              unprunedCases[jId] = { ...(unprunedCases[jId] ?? {}), [cid]: jur.cases[cid] };
            }
          }
          for (const [k, v] of Object.entries(sim.policeState.respondedCases)) {
            if (k.endsWith(`:${cid}`)) unprunedResponded[k] = v;
          }

          const arr = arrest(sim, {
            jurisdictionId: CAPITAL_JURISDICTION,
            crimeIds: [cid],
            playerCash: 100000,
          });
          if (arr.charges.length > 0) resolvedCycles++;
          sim = arr.sim;
        }

        // Advance remaining 2 days of each 10-day cycle -> ~100+ days total
        sim = advance(sim, 24 * 1).sim;
      }

      expect(sim.currentDay).toBeGreaterThanOrEqual(100);
      expect(resolvedCycles).toBeGreaterThanOrEqual(7);

      // Reconstruct the exact unpruned 100-day state (what save() would have emitted before G014 pruning)
      const unprunedJurs: LoopSim['crimeState']['jurisdictions'] = {};
      for (const [jId, jur] of Object.entries(sim.crimeState.jurisdictions)) {
        unprunedJurs[jId] = {
          jurisdictionId: jId,
          cases: { ...(unprunedCases[jId] ?? {}), ...jur.cases },
        };
      }
      const unprunedSim: LoopSim = {
        ...sim,
        crimeState: {
          ...sim.crimeState,
          crimes: { ...unprunedCrimes, ...sim.crimeState.crimes },
          ledger: { ...unprunedLedger, ...sim.crimeState.ledger },
          toldPairs: { ...unprunedToldPairs, ...sim.crimeState.toldPairs },
          traces: { ...unprunedTraces, ...(sim.crimeState.traces ?? {}) },
          jurisdictions: unprunedJurs,
        },
        policeState: {
          ...sim.policeState,
          respondedCases: { ...unprunedResponded, ...sim.policeState.respondedCases },
        },
        respondedCases: { ...unprunedResponded, ...sim.respondedCases },
      };
      delete (unprunedSim as Partial<LoopSim>).resolvedCrimeMemories;

      const beforeBytes = Buffer.byteLength(JSON.stringify(unprunedSim), 'utf8');
      const prunedJson = save(unprunedSim);
      const afterBytes = Buffer.byteLength(prunedJson, 'utf8');
      const savedBytes = beforeBytes - afterBytes;
      const pctReduction = (savedBytes / beforeBytes) * 100;

      const prunedObj = pruneResolvedState(unprunedSim);
      const crimeBefore = Buffer.byteLength(JSON.stringify(unprunedSim.crimeState), 'utf8');
      const crimeAfter = Buffer.byteLength(
        JSON.stringify({
          crimeState: prunedObj.crimeState,
          resolvedCrimeMemories: prunedObj.resolvedCrimeMemories,
        }),
        'utf8',
      );
      const crimePct = ((crimeBefore - crimeAfter) / crimeBefore) * 100;

      console.log(
        `[G014 100-day save] day=${sim.currentDay} resolved=${resolvedCycles} total: ${beforeBytes}B -> ${afterBytes}B (-${savedBytes}B, -${pctReduction.toFixed(1)}%) | crimeState: ${crimeBefore}B -> ${crimeAfter}B (-${crimePct.toFixed(1)}%)`,
      );

      expect(afterBytes).toBeLessThan(beforeBytes);
      expect(pctReduction).toBeGreaterThan(35);
      expect(crimePct).toBeGreaterThan(60);

      // Resolved crimes have zero toldPairs, traces, or police cases left
      for (const [cid, meta] of Object.entries(prunedObj.crimeMeta)) {
        if (!meta.resolved) continue;
        expect(prunedObj.crimeState.toldPairs[cid]).toBeUndefined();
        expect(prunedObj.crimeState.traces?.[cid]).toBeUndefined();
        for (const jur of Object.values(prunedObj.crimeState.jurisdictions)) {
          expect(jur.cases[cid]).toBeUndefined();
        }
      }

      // Identity files and prior offenses are preserved
      expect(prunedObj.crimeState.identityFiles?.[sim.playerId]?.length).toBeGreaterThan(0);
      expect(prunedObj.priorOffenses.length).toBe(resolvedCycles);

      // Attitudes on the pruned save match the unpruned save for every single person in town
      const restored = load(prunedJson);
      for (const pid of Object.keys(sim.peopleMeta)) {
        expect(attitude(restored, pid)).toEqual(attitude(unprunedSim, pid));
      }

      // Round-tripped pruned save continues 100% identically to `sim` over the next 5 days!
      const nextA = advance(sim, 24 * 5);
      const nextB = advance(restored, 24 * 5);
      expect(save(nextB.sim)).toBe(save(nextA.sim));
      expect(nextB.policePlans).toEqual(nextA.policePlans);
      expect(nextB.attitudeChanges).toEqual(nextA.attitudeChanges);
      expect(nextB.newPoliceCases).toEqual(nextA.newPoliceCases);
    });
  });

  describe('4. A fence who informs (fence_sale, Sam fenceRisk/fenceDeal, fence stance & tipOff)', () => {
    it('builds fence trust and stance from clean fence_sale deals without flagging a pawn registry', () => {
      let sim = create(buildStubCapital({ seed: 'g014-fence-trust', year: 1978, startDay: 1, startHour: 20 }));
      const fenceId = CAPITAL_IDS.pawnClerk;
      const fenceProfile: Fence = {
        personId: fenceId,
        cover: 'secondhand dealer',
        where: 'behind the pawn shop',
        hours: [20, 2],
        categories: ['valuables', 'electronics', 'instruments'],
        honesty: 0.25,
        greed: 0.65,
      };

      // Steal a pocket watch unwitnessed
      const steal = act(sim, {
        kind: 'take',
        subkind: 'house',
        placeId: CAPITAL_IDS.haleStore,
        item: { id: 'item:fence_watch_1', label: 'a pocket watch', value: 4000, serialNumber: 'FW-01' },
        bystanders: [],
      });
      sim = steal.sim;

      // Fence it via Sam's real fenceDeal pricing (`catalogueItemId: 'pocketwatch'`) with no tip
      const deal1 = act(sim, {
        kind: 'fence_sale',
        placeId: CAPITAL_IDS.pawnShop,
        fenceId,
        fence: fenceProfile,
        catalogueItemId: 'pocketwatch',
        item: {
          id: 'item:fence_watch_1',
          label: 'a pocket watch',
          value: 4000,
          stolenFromCrimeId: steal.crimeId,
        },
        forceTip: false,
      });
      sim = deal1.sim;

      expect(deal1.fenceAccepted).toBe(true);
      expect(deal1.hot).toBe(true);
      expect(deal1.pawnTraceFlagged).toBe(false);
      expect(deal1.payEarned).toBeGreaterThan(0);
      expect(deal1.fenceTrustAfter).toBeCloseTo(0.1, 2);
      expect(deal1.tipOffQueued).toBe(false);
      expect(sim.pawnRecords).toHaveLength(0);

      // Fence's attitude reflects the deal
      const att1 = attitude(sim, fenceId);
      expect(att1.stance).toBe('serve');
      expect(att1.trust).toBeGreaterThan(0);
      expect(att1.facts.some((f) => /quiet business before \(1 deal\)/i.test(f))).toBe(true);

      // Second deal grows trust further; high heat causes a too_hot refusal and makes fence wary
      const deal2 = act(sim, {
        kind: 'fence_sale',
        placeId: CAPITAL_IDS.pawnShop,
        fenceId,
        fence: fenceProfile,
        item: { id: 'item:fence_ring_2', label: 'a gold ring', value: 6500 },
        forceTip: false,
      });
      sim = deal2.sim;
      expect(deal2.fenceTrustAfter).toBeCloseTo(0.2, 2);
      expect(attitude(sim, fenceId).facts.some((f) => /2 deals/i.test(f))).toBe(true);

      const tooHot = act(sim, {
        kind: 'fence_sale',
        placeId: CAPITAL_IDS.pawnShop,
        fenceId,
        fence: fenceProfile,
        item: { id: 'item:fence_hot_3', label: 'a stolen radio', value: 5000 },
        heat: 0.98,
      });
      expect(tooHot.fenceAccepted).toBe(false);
      expect(tooHot.fenceRefusalReason).toBe('too_hot');
      expect(attitude(tooHot.sim, fenceId).stance).toBe('wary');
    });

    it('delivers an informant fence tip to the police with era latency (both via fence_sale and tipOff / TimerpgSim)', () => {
      const runInformant = (year: number, seed: string) => {
        let s = create(buildStubCapital({ seed, year, startDay: 1, startHour: 15 }));
        const steal = act(s, {
          kind: 'take',
          subkind: 'house',
          placeId: CAPITAL_IDS.haleStore,
          item: { id: `item:tip_watch_${year}`, label: 'a silver watch', value: 5000 },
          bystanders: [],
        });
        s = steal.sim;

        const sale = act(s, {
          kind: 'fence_sale',
          placeId: CAPITAL_IDS.pawnShop,
          fenceId: CAPITAL_IDS.pawnClerk,
          item: {
            id: `item:tip_watch_${year}`,
            label: 'a silver watch',
            value: 5000,
            stolenFromCrimeId: steal.crimeId,
          },
          forceTip: true,
        });
        return { sale, crimeId: steal.crimeId! };
      };

      const r90 = runInformant(1990, 'g014-tip-era');
      expect(r90.sale.fenceAccepted).toBe(true);
      expect(r90.sale.tipOffQueued).toBe(true);
      expect(r90.sale.tipMeans).toBe('telephone');
      expect(r90.sale.tipDelayHours).toBeGreaterThan(0);

      // Fence's attitude reflects having informed on the player
      const fenceAtt = attitude(r90.sale.sim, CAPITAL_IDS.pawnClerk);
      expect(['refuse', 'report_on_sight']).toContain(fenceAtt.stance);
      expect(fenceAtt.facts.some((f) => /tipped the police off/i.test(f))).toBe(true);

      // Before tip arrives, police have no open case on the unwitnessed theft
      expect(policeView(r90.sale.sim, CAPITAL_JURISDICTION).cases).toHaveLength(0);

      // Advance past the tip's arrival delay -> case opens naming the player & an on-duty officer is dispatched!
      const adv = advance(r90.sale.sim, (r90.sale.tipDelayHours ?? 1) + 0.5);
      const pv = policeView(adv.sim, CAPITAL_JURISDICTION);
      const tippedCase = pv.cases.find((c) => c.crimeId === r90.crimeId);
      expect(tippedCase).toBeDefined();
      expect(tippedCase!.namedSuspectId).toBe(adv.sim.playerId);
      expect(adv.policePlans.some((p) => p.assignment === 'respond_crime' && p.crimeId === r90.crimeId)).toBe(true);

      // Verify statistical fenceRisk rate matches Sam's fenceRisk(fence, year) (1995 > 1925)
      const highRiskFence: Fence = {
        personId: CAPITAL_IDS.pawnClerk,
        cover: 'secondhand dealer',
        where: 'back door',
        hours: [20, 2],
        categories: ['valuables'],
        honesty: 0.45,
        greed: 0.85,
      };
      expect(fenceRisk(highRiskFence, 1995)!).toBeGreaterThan(fenceRisk(highRiskFence, 1925)!);
      let tips1925 = 0;
      let tips1995 = 0;
      for (let i = 0; i < 80; i++) {
        const s25 = create(buildStubCapital({ seed: `g014-risk-${i}`, year: 1925, startHour: 15 }));
        const d25 = act(s25, {
          kind: 'fence_sale',
          placeId: CAPITAL_IDS.pawnShop,
          fenceId: CAPITAL_IDS.pawnClerk,
          fence: highRiskFence,
          item: { id: 'item:ring', label: 'a gold ring', value: 3000 },
        });
        if (d25.tipOffQueued) tips1925++;

        const s95 = create(buildStubCapital({ seed: `g014-risk-${i}`, year: 1995, startHour: 15 }));
        const d95 = act(s95, {
          kind: 'fence_sale',
          placeId: CAPITAL_IDS.pawnShop,
          fenceId: CAPITAL_IDS.pawnClerk,
          fence: highRiskFence,
          item: { id: 'item:ring', label: 'a gold ring', value: 3000 },
        });
        if (d95.tipOffQueued) tips1995++;
      }
      expect(tips1995).toBeGreaterThan(tips1925);

      // Direct tipOff() + TimerpgSim.tipOff / simCall + tipOffJson
      const init = buildStubCapital({ seed: 'g014-direct-tip', year: 1990, startHour: 11 });
      const directSim = create(init);
      const directTip = tipOff(directSim, {
        informantId: CAPITAL_IDS.pawnClerk,
        itemId: 'item:hot_radio',
        placeId: CAPITAL_IDS.pawnShop,
      });
      expect(directTip.queued).toBe(true);
      expect(directTip.crimeId).toBeDefined();
      const afterDirect = advance(directTip.sim, directTip.delayHours + 0.5);
      expect(policeView(afterDirect.sim, CAPITAL_JURISDICTION).cases.length).toBe(1);

      TimerpgSim.init(JSON.stringify(init));
      const bridgeTip = JSON.parse(
        simCall(
          JSON.stringify({
            method: 'tipOff',
            args: [{ informantId: CAPITAL_IDS.pawnClerk, itemId: 'item:hot_watch', placeId: CAPITAL_IDS.pawnShop }],
          }),
        ),
      );
      expect(bridgeTip.queued).toBe(true);
      expect(bridgeTip.sim).toBeUndefined();

      const statelessTip = JSON.parse(
        tipOffJson(
          save(directSim),
          JSON.stringify({ informantId: CAPITAL_IDS.pawnClerk, itemId: 'item:hot_watch' }),
        ),
      );
      expect(statelessTip.queued).toBe(true);
    });
  });
});
