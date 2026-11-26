import { describe, expect, it } from 'vitest';
import { priceIndex, wage } from '../economy/eras';
import {
  STOP_POLICY,
  act,
  advance,
  arrest,
  plead,
  attitude,
  create,
  load,
  policeView,
  save,
  spotted,
} from './index';
import { TimerpgSim } from './bridge';
import { CAPITAL_IDS, CAPITAL_JURISDICTION, buildStubCapital } from './stubCapital';
import { parsePlan } from '../plan';

describe('src/loop facade', () => {
  it('runs the first-encounter scenario end-to-end through the public API', () => {
    let sim = create(buildStubCapital({ seed: 'first-encounter-1978', year: 1978, startDayOfWeek: 1 }));

    // Initially Marcus Hale, Martha Finch, and Ed Kowalski are friendly & unaware
    expect(attitude(sim, CAPITAL_IDS.marcusHale).stance).toBe('serve');
    expect(attitude(sim, CAPITAL_IDS.neighbourEd).facts).toHaveLength(0);
    expect(policeView(sim, CAPITAL_JURISDICTION).cases).toHaveLength(0);

    // Player pockets a tin of peaches from Hale's General Store on Tuesday morning.
    // Marcus Hale is at the counter with his BACK TURNED (facing north +Y, away from shelf at (40, 4)).
    // Martha Finch is at the front window looking straight into the store (facing +Y from (40, -1)).
    const stealRes = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      position: { x: 40, y: 4 },
      light: 0.95,
      item: { id: 'item:peaches_1', label: 'a tin of peaches', value: 65 },
      bystanders: [
        {
          id: CAPITAL_IDS.marcusHale,
          position: { x: 40, y: 7 },
          facing: { x: 0, y: 1 }, // facing away (+Y) from player at y=4
          activity: 'working',
        },
        {
          id: CAPITAL_IDS.windowWoman,
          position: { x: 40, y: -1 },
          facing: { x: 0, y: 1 }, // facing directly toward player at y=4
          activity: 'watching',
        },
      ],
    });
    sim = stealRes.sim;

    expect(stealRes.legit).toBe(false);
    expect(stealRes.crimeId).toBeDefined();
    // Martha saw it; Marcus (back turned) did NOT
    const witnessIds = stealRes.witnessedBy.map((w) => w.personId);
    expect(witnessIds).toContain(CAPITAL_IDS.windowWoman);
    expect(witnessIds).not.toContain(CAPITAL_IDS.marcusHale);

    // Right after the act, Hale still doesn't know
    expect(attitude(sim, CAPITAL_IDS.marcusHale).stance).toBe('serve');
    const marthaAtt = attitude(sim, CAPITAL_IDS.windowWoman);
    expect(marthaAtt.facts.some((f) => f.includes('tin of peaches') && f.includes('Tuesday'))).toBe(true);

    // Advance 48 hours (two days later)
    const adv = advance(sim, 48);
    sim = adv.sim;

    expect(adv.daysElapsed).toBe(2);

    // 1. Store owner Marcus Hale now refuses to serve the player and knows why
    const haleAtt = attitude(sim, CAPITAL_IDS.marcusHale);
    expect(['refuse', 'report_on_sight']).toContain(haleAtt.stance);
    expect(haleAtt.facts.some((f) => f.includes('tin of peaches') && f.includes("Hale's General Store"))).toBe(true);
    expect(adv.attitudeChanges.some((c) => c.personId === CAPITAL_IDS.marcusHale)).toBe(true);

    // 2. At least one neighbour (other than Martha or Marcus) now mentions it in their dialogue fact sheet
    const otherKnowers = Object.keys(sim.peopleMeta)
      .filter((id) => id !== CAPITAL_IDS.marcusHale && id !== CAPITAL_IDS.windowWoman)
      .map((id) => attitude(sim, id))
      .filter((a) => a.facts.some((f) => f.includes('tin of peaches') || f.includes("Hale's General Store")));
    expect(otherKnowers.length).toBeGreaterThan(0);

    // 3. Police have an open case and an officer has a compiled G007 plan walking Hale's block
    const pView = policeView(sim, CAPITAL_JURISDICTION);
    expect(pView.cases.length).toBeGreaterThan(0);
    expect(pView.cases[0]!.placeId).toBe(CAPITAL_IDS.haleStore);
    expect(pView.cases[0]!.strength.combinedScore).toBeGreaterThan(0);

    const haleBlockOfficer = adv.policePlans.find((d) =>
      d.routePlaceIds.includes(CAPITAL_IDS.haleBlockNode) || d.targetPlaceId === CAPITAL_IDS.haleStore,
    );
    expect(haleBlockOfficer).toBeDefined();
    expect(parsePlan(haleBlockOfficer!.planSource).ok).toBe(true);
  });

  it('leaves nobody aware and zero police heat after an unseen theft', () => {
    let sim = create(buildStubCapital({ seed: 'unseen-theft-01', year: 1978 }));

    const res = act(sim, {
      kind: 'take',
      subkind: 'yard',
      placeId: CAPITAL_IDS.elmYard,
      item: { id: 'item:wrench_1', label: 'a brass wrench', value: 325 },
      gloved: true,
      bystanders: [
        // Nearby resident is asleep inside
        {
          id: CAPITAL_IDS.windowWoman,
          position: { x: 16, y: -44 },
          activity: 'sleeping',
        },
      ],
    });
    sim = res.sim;

    expect(res.legit).toBe(false);
    expect(res.witnessedBy).toHaveLength(0);

    const adv = advance(sim, 72);
    sim = adv.sim;

    expect(adv.attitudeChanges).toHaveLength(0);
    expect(adv.newPoliceCases).toHaveLength(0);
    expect(policeView(sim, CAPITAL_JURISDICTION).cases).toHaveLength(0);
    expect(policeView(sim, CAPITAL_JURISDICTION).heat).toBe(0);

    for (const pid of Object.keys(sim.peopleMeta)) {
      const att = attitude(sim, pid);
      expect(att.stance).toBe('serve');
      expect(att.facts).toHaveLength(0);
    }
  });

  it('pays wages on a legit work shift and leaves employer and coworkers knowing the player name with no police interest', () => {
    let sim = create(buildStubCapital({ seed: 'legit-shift-01', year: 1978, playerName: 'Sam Rivera' }));

    const shift = act(sim, {
      kind: 'work_shift',
      placeId: CAPITAL_IDS.market,
      hours: 5,
      pay: 1575,
      taskLabel: 'stocking crates',
      bystanders: [
        { id: CAPITAL_IDS.marketOwner, position: { x: 85, y: 5 }, activity: 'working' },
        { id: CAPITAL_IDS.marketCoworker, position: { x: 86, y: 6 }, activity: 'working' },
      ],
    });
    sim = shift.sim;

    expect(shift.legit).toBe(true);
    expect(shift.payEarned).toBe(1575);
    expect(shift.crimeId).toBeUndefined();

    const ownerAtt = attitude(sim, CAPITAL_IDS.marketOwner);
    expect(ownerAtt.stance).toBe('serve');
    expect(ownerAtt.knowsPlayerName).toBe(true);
    expect(ownerAtt.playerNameKnown).toBe('Sam Rivera');
    expect(ownerAtt.trust).toBeGreaterThan(0);
    expect(ownerAtt.facts.some((f) => f.includes('Sam Rivera') && f.includes('Capital Market'))).toBe(true);

    const coworkerAtt = attitude(sim, CAPITAL_IDS.marketCoworker);
    expect(coworkerAtt.knowsPlayerName).toBe(true);
    expect(coworkerAtt.playerNameKnown).toBe('Sam Rivera');

    const adv = advance(sim, 48);
    sim = adv.sim;
    expect(policeView(sim, CAPITAL_JURISDICTION).cases).toHaveLength(0);
    expect(policeView(sim, CAPITAL_JURISDICTION).heat).toBe(0);
  });

  it('traces a stolen serialized item sold at the pawn shop back to the player', () => {
    let sim = create(buildStubCapital({ seed: 'pawn-trace-1982', year: 1982 }));

    // Unseen house burglary stealing a serialized watch
    const steal = act(sim, {
      kind: 'take',
      subkind: 'house',
      placeId: CAPITAL_IDS.elmHouse,
      item: {
        id: 'item:watch_serial_1',
        label: 'a gold Hamilton pocket watch',
        value: 14000,
        serialNumber: 'HAM-448912',
      },
      bystanders: [],
    });
    sim = steal.sim;
    expect(steal.witnessedBy).toHaveLength(0);
    expect(steal.stolenSerialNumber).toBe('HAM-448912');

    // Player sells the stolen watch at Vance's Pawn & Loan
    const sell = act(sim, {
      kind: 'sell',
      placeId: CAPITAL_IDS.pawnShop,
      item: {
        id: 'item:watch_serial_1',
        label: 'a gold Hamilton pocket watch',
        value: 7000,
        serialNumber: 'HAM-448912',
      },
    });
    sim = sell.sim;
    expect(sell.legit).toBe(false);
    expect(sell.hot).toBe(true);
    expect(sell.pawnTraceFlagged).toBe(true);

    // Advance 48 hours: pawn ledger audit / G008 serial registry traces it back to the player
    const adv = advance(sim, 48);
    sim = adv.sim;

    expect(adv.pawnLeadsMatched.length).toBeGreaterThan(0);
    expect(adv.pawnLeadsMatched[0]!.suspectId).toBe(sim.playerId);
    expect(adv.pawnLeadsMatched[0]!.serialNumber).toBe('HAM-448912');

    const pView = policeView(sim, CAPITAL_JURISDICTION);
    const watchCase = pView.cases.find((c) => c.crimeId === steal.crimeId);
    expect(watchCase).toBeDefined();
    expect(watchCase!.namedSuspectId).toBe(sim.playerId);
    expect(watchCase!.strength.combinedScore).toBeGreaterThan(0.5);

    // An officer who spots the player nearby now stops or arrests them with a valid G007 plan
    const officerId = Object.keys(sim.officers)[0]!;
    const spot = spotted(sim, officerId, { distance: 5, light: 0.95 });
    expect(['approach_and_question', 'arrest']).toContain(spot.action);
    expect(spot.recognitionChance).toBeGreaterThan(0.35);
    expect(spot.planSource).toBeDefined();
    expect(parsePlan(spot.planSource!).ok).toBe(true);
  });

  it('handles open vs closed enter, vehicle theft, and arrest outcomes (fine vs cell + cold case prints)', () => {
    let sim = create(buildStubCapital({ seed: 'arrest-loop-1978', year: 1978, startHour: 10 }));

    // Open store entry is legit
    const openEnter = act(sim, { kind: 'enter', placeId: CAPITAL_IDS.haleStore });
    expect(openEnter.legit).toBe(true);

    // Unseen ungloved burglary leaves fingerprints on scene for cold-case matching later
    const unseenBurg = act(sim, {
      kind: 'enter',
      placeId: CAPITAL_IDS.elmHouse,
      isOpen: false,
      forcedEntry: true,
      gloved: false,
      bystanders: [],
    });
    sim = unseenBurg.sim;
    expect(unseenBurg.legit).toBe(false);
    expect(unseenBurg.tracesLeft.length).toBeGreaterThan(0);

    // Witnessed petty shoplifting -> fine on first offense
    const shop = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:soap_1', label: 'a bar of soap', value: 55 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
      ],
    });
    sim = shop.sim;
    sim = advance(sim, 24).sim;

    const firstArrest = arrest(sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      carriedItemIds: ['item:soap_1'],
      playerCash: 5000,
    });
    sim = firstArrest.sim;
    expect(firstArrest.penalty).toBe('fine');
    expect(firstArrest.fineAmount).toBeGreaterThan(0);
    expect(firstArrest.confiscatedItems).toContain('item:soap_1');
    expect(firstArrest.priorOffenses).toBe(1);
    expect(policeView(sim, CAPITAL_JURISDICTION).cases).toHaveLength(0);

    // Now steal a car in view of a bystander -> repeat offense + high severity -> jail time
    const carSteal = act(sim, {
      kind: 'take_vehicle',
      placeId: CAPITAL_IDS.market,
      vehicle: { id: 'veh:sedan_1', kind: 'sedan', color: 'blue', plateNumber: 'CAP-309', value: 90000 },
      bystanders: [
        { id: CAPITAL_IDS.marketOwner, position: { x: 84, y: 6 }, facing: { x: 1, y: -1 }, activity: 'watching' },
        { id: CAPITAL_IDS.marketCoworker, position: { x: 86, y: 6 }, facing: { x: -1, y: -1 }, activity: 'watching' },
      ],
    });
    sim = carSteal.sim;
    sim = advance(sim, 48).sim;

    const secondArrest = arrest(sim, { jurisdictionId: CAPITAL_JURISDICTION });
    sim = secondArrest.sim;
    expect(secondArrest.penalty).toBe('charged');
    expect(secondArrest.courtCase).toBeDefined();
    const pleaRes = plead(sim, { courtCaseId: secondArrest.courtCase!.id, plea: 'guilty' });
    sim = pleaRes.sim;
    expect(pleaRes.ok).toBe(true);
    expect(pleaRes.jailHours).toBeGreaterThan(0);
    expect(sim.priorOffenses.length).toBe(2);
  });

  it('round-trips cleanly through save/load and continues identically', () => {
    const init = buildStubCapital({ seed: 'roundtrip-seed', year: 1978 });
    let simA = create(init);
    simA = act(simA, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:tin_rt', label: 'a tin of beans', value: 65 },
      bystanders: [
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
      ],
    }).sim;

    const serialized = save(simA);
    const simB = load(serialized);

    expect(simB).toEqual(simA);

    const nextA = advance(simA, 48);
    const nextB = advance(simB, 48);

    expect(save(nextB.sim)).toBe(save(nextA.sim));
    expect(nextB.policePlans).toEqual(nextA.policePlans);
    expect(nextB.attitudeChanges).toEqual(nextA.attitudeChanges);
  });

  it('produces byte-for-byte identical results for the same seed and inputs', () => {
    const runOnce = () => {
      let s = create(buildStubCapital({ seed: 'deterministic-42', year: 1978 }));
      s = act(s, {
        kind: 'work_shift',
        placeId: CAPITAL_IDS.bakery,
        hours: 4,
        pay: 1260,
      }).sim;
      s = act(s, {
        kind: 'take',
        subkind: 'shoplift',
        placeId: CAPITAL_IDS.haleStore,
        item: { id: 'item:peaches_det', label: 'a tin of peaches', value: 65 },
        bystanders: [
          { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
        ],
      }).sim;
      const adv = advance(s, 48);
      return {
        saved: save(adv.sim),
        plans: adv.policePlans,
        changes: adv.attitudeChanges,
      };
    };

    const r1 = runOnce();
    const r2 = runOnce();
    expect(r2).toEqual(r1);
  });

  it('exposes a working JSON-only QuickJS bridge (TimerpgSim)', () => {
    const initResp = JSON.parse(
      TimerpgSim.init(JSON.stringify(buildStubCapital({ seed: 'bridge-test', year: 1978 }))),
    );
    expect(initResp.ok).toBe(true);
    expect(initResp.peopleCount).toBe(50);

    const actResp = JSON.parse(
      TimerpgSim.act(
        JSON.stringify({
          kind: 'work_shift',
          placeId: CAPITAL_IDS.market,
          hours: 4,
          pay: 1260,
        }),
      ),
    );
    expect(actResp.legit).toBe(true);
    expect(actResp.payEarned).toBe(1260);
    expect(actResp.sim).toBeUndefined();

    const attResp = JSON.parse(TimerpgSim.attitude(CAPITAL_IDS.marketOwner));
    expect(attResp.knowsPlayerName).toBe(true);

    const saved = TimerpgSim.save();
    const loadResp = JSON.parse(TimerpgSim.load(saved));
    expect(loadResp.ok).toBe(true);
    expect(JSON.parse(TimerpgSim.attitude(CAPITAL_IDS.marketOwner)).knowsPlayerName).toBe(true);
  });
  it("triggers a G008 cold-case print match when a later arrest books the player prints", () => {
    let sim = create(buildStubCapital({ seed: "cold-case-1978", year: 1978 }));

    // Burglary with ungloved prints; neighbour sees someone fleeing but cannot name them
    const burg = act(sim, {
      kind: "enter",
      placeId: CAPITAL_IDS.elmHouse,
      isOpen: false,
      forcedEntry: true,
      gloved: false,
      bystanders: [
        { id: CAPITAL_IDS.windowWoman, position: { x: 12, y: -44 }, facing: { x: -1, y: 0 }, activity: "watching" },
      ],
    });
    sim = burg.sim;
    expect(burg.tracesLeft.some((t) => t.kind === "fingerprint")).toBe(true);

    // Advance 48h so burglary is reported and police collect scene fingerprints
    sim = advance(sim, 48).sim;
    const beforeView = policeView(sim, CAPITAL_JURISDICTION);
    const burgCaseBefore = beforeView.cases.find((c) => c.crimeId === burg.crimeId);
    expect(burgCaseBefore).toBeDefined();
    // Before booking, collected prints have no matched suspect on file yet
    expect(burgCaseBefore!.strength.physicalSuspectId).toBeUndefined();

    // Later minor shoplifting arrest books the player prints -> triggers cold case match!
    const shop = act(sim, {
      kind: "take",
      subkind: "shoplift",
      placeId: CAPITAL_IDS.haleStore,
      item: { id: "item:candy", label: "a tin of mints", value: 50 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: "watching" },
      ],
    });
    sim = shop.sim;
    sim = advance(sim, 24).sim;

    const outcome = arrest(sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [shop.crimeId!],
    });
    expect(outcome.coldCasesTriggered).toContain(burg.crimeId);
  });

  it("cools police heat over time and isolates minor crimes to their jurisdiction", () => {
    let sim = create(buildStubCapital({ seed: "cooldown-jur-1978", year: 1978 }));

    sim = act(sim, {
      kind: "take",
      subkind: "shoplift",
      placeId: CAPITAL_IDS.haleStore,
      item: { id: "item:jar_1", label: "a jar of honey", value: 125 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: "watching" },
      ],
    }).sim;

    sim = advance(sim, 48).sim;
    const hotView = policeView(sim, CAPITAL_JURISDICTION);
    expect(hotView.heat).toBeGreaterThan(0);
    // County jurisdiction does NOT know about petty local shoplifting
    expect(policeView(sim, "jur:county").cases).toHaveLength(0);
    expect(policeView(sim, "jur:county").heat).toBe(0);

    // Leave for 30 days -> heat cools substantially
    sim = advance(sim, 24 * 30).sim;
    const cooledView = policeView(sim, CAPITAL_JURISDICTION);
    expect(cooledView.heat).toBeLessThan(hotView.heat);
  });
});

describe('G012: real police & wiring fixes', () => {
  it('runs on real G009 PoliceState and records ArrestRecords on booking', () => {
    let sim = create(buildStubCapital({ seed: 'g012-real-police', year: 1978, startHour: 10 }));
    expect(sim.policeState).toBeDefined();
    expect(Object.keys(sim.policeState.officers).length).toBe(Object.keys(sim.officers).length);
    for (const o of Object.values(sim.policeState.officers)) {
      expect(o.planSource).toBeDefined();
      expect(parsePlan(o.planSource!).ok).toBe(true);
    }

    sim = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:g012_1', label: 'a tin of coffee', value: 180 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
      ],
    }).sim;
    sim = advance(sim, 24).sim;

    const outcome = arrest(sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      carriedItemIds: ['item:g012_1'],
      playerCash: 5000,
    });
    expect(outcome.sim.policeState.arrests.length).toBe(1);
    expect(outcome.sim.policeState.arrests[0]!.suspectId).toBe(sim.playerId);
    expect(outcome.sim.policeState.arrests[0]!.transportedToStation).toBe(true);
  });

  it('arrest() runs skipped jailHours through advance() (gossip, reports, decay) and returns day & hour', () => {
    let sim = create(buildStubCapital({ seed: 'g012-arrest-clock', year: 1978, startDay: 1, startHour: 10 }));

    // Crime 1: forced-entry burglary (severity 0.62 -> multi-day jail >= 48h) witnessed at bakery by Tom Miller (not Marcus Hale's circle)
    const burg = act(sim, {
      kind: 'enter',
      placeId: CAPITAL_IDS.bakery,
      isOpen: false,
      forcedEntry: true,
      bystanders: [
        { id: CAPITAL_IDS.bakerOwner, position: { x: 10, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
      ],
    });
    sim = burg.sim;
    sim = advance(sim, 24).sim; // Day 2, hour 10: burglary reported to police

    // Crime 2: freshly witnessed shoplifting at Hale's (NOT yet reported or gossipped to Marcus!)
    const shop = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      light: 0.95,
      item: { id: 'item:g012_jam', label: 'a jar of jam', value: 95 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: 1 }, activity: 'working' },
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
      ],
    });
    sim = shop.sim;
    expect(attitude(sim, CAPITAL_IDS.marcusHale).stance).toBe('serve');

    // Arrest ONLY for the car theft -> jail time >= 48h must advance clock AND run gossip/reports!
    const dayBefore = sim.currentDay;
    const hourBefore = sim.currentHour;
    const charged = arrest(sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [burg.crimeId!],
    });
    expect(charged.penalty).toBe('charged');
    expect(charged.courtCase).toBeDefined();
    const res = plead(charged.sim, {
      courtCaseId: charged.courtCase!.id,
      plea: 'guilty',
    });
    expect(res.ok).toBe(true);
    expect(res.jailHours).toBeGreaterThanOrEqual(48);
    expect(res.daysElapsed).toBe(Math.floor((hourBefore + res.jailHours) / 24));
    expect(res.day).toBe(dayBefore + res.daysElapsed);
    expect(res.hour).toBe((hourBefore + res.jailHours) % 24);
    expect(res.sim.currentDay).toBe(res.day);
    expect(res.sim.currentHour).toBe(res.hour);
    // While sitting in jail, gossip spread across town, attitudes shifted, and the shoplifting case surfaced!
    expect(res.attitudeChanges.length).toBeGreaterThan(0);
    expect(res.attitudeChanges.some((c) => c.newStance !== 'serve')).toBe(true);
    expect(res.newPoliceCases).toContain(shop.crimeId);
    expect(res.policePlans.length).toBeGreaterThan(0);
  });

  it('work_shift advances the clock itself, pays era-scaled integer cents by default, and returns changes', () => {
    let sim = create(buildStubCapital({ seed: 'g012-shift-clock', year: 1952, startDay: 1, startHour: 22 }));

    // Crime witnessed before shift; shift crossing midnight advances into Day 2 and triggers gossip
    sim = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:g012_tea', label: 'a tin of tea', value: 45 },
      bystanders: [
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
      ],
    }).sim;

    // Omit pay -> defaults to 1952 shop_clerk wage * 4 hours in integer cents
    const expectedHourly = wage('shop_clerk', 1952)!.hourlyCents;
    const shift = act(sim, {
      kind: 'work_shift',
      placeId: CAPITAL_IDS.market,
      hours: 4,
      taskLabel: 'unloading produce',
    });

    expect(shift.legit).toBe(true);
    expect(shift.payEarned).toBe(expectedHourly * 4);
    expect(Number.isInteger(shift.payEarned)).toBe(true);
    expect(shift.day).toBe(2);
    expect(shift.hour).toBe(2);
    expect(shift.daysElapsed).toBe(1);
    expect(shift.sim.currentDay).toBe(2);
    expect(shift.sim.currentHour).toBe(2);
    expect(shift.policePlans).toBeDefined();
    expect(shift.policePlans!.length).toBeGreaterThan(0);
    expect(shift.attitudeChanges).toBeDefined();
  });

  it('builds patrol beats on nearby corners by graph distance even with randomly scrambled node IDs', () => {
    // Build a 5x5 grid (25 junctions) with scrambled non-Hilbert node IDs
    const scrambledNames = [
      'z99', 'a04', 'm17', 'b82', 'q11',
      'f40', 'x03', 'c55', 'w28', 'd09',
      'k71', 'e19', 't64', 'h02', 'v88',
      'n31', 'g50', 'y14', 'j93', 'p07',
      'r22', 'l60', 'u05', 's47', 'i81',
    ];
    const gridId = (r: number, c: number) => `j:${scrambledNames[r * 5 + c]}`;
    const coordOf = new Map<string, { r: number; c: number }>();
    const nodes = [];
    const edges = [];
    for (let r = 0; r < 5; r++) {
      for (let c = 0; c < 5; c++) {
        const id = gridId(r, c);
        coordOf.set(id, { r, c });
        nodes.push({
          id,
          position: { x: c * 40, y: r * 40 },
          kind: (r === 2 && c === 2 ? 'station' : 'intersection') as 'station' | 'intersection',
          jurisdictionId: 'jur:grid',
          streetLight: 0.7,
        });
        if (c + 1 < 5) edges.push({ from: id, to: gridId(r, c + 1), distance: 40 });
        if (r + 1 < 5) edges.push({ from: id, to: gridId(r + 1, c), distance: 40 });
      }
    }

    const base = buildStubCapital({ seed: 'g012-scrambled-grid', year: 1978, peopleCount: 2000 });
    const sim = create({
      ...base,
      places: [
        {
          id: 'place:grid_station',
          name: 'Central Precinct',
          kind: 'police_station',
          position: { x: 80, y: 80 },
          jurisdictionId: 'jur:grid',
          nodeId: gridId(2, 2),
        },
      ],
      streetGraph: { nodes, edges },
    });

    const officers = Object.values(sim.officers);
    expect(officers.length).toBeGreaterThanOrEqual(4);
    for (const o of officers) {
      expect(o.beatNodeIds.length).toBeGreaterThanOrEqual(3);
      // Every consecutive pair of beat corners must be adjacent (1 hop) or at most 2 hops (turning from a corner)
      for (let i = 1; i < o.beatNodeIds.length; i++) {
        const a = coordOf.get(o.beatNodeIds[i - 1]!)!;
        const b = coordOf.get(o.beatNodeIds[i]!)!;
        const manhattanHops = Math.abs(a.r - b.r) + Math.abs(a.c - b.c);
        expect(manhattanHops).toBeLessThanOrEqual(2);
      }
      // Consecutive corners stay immediately adjacent on average and entire 6-corner beat stays locally clustered
      let stepSum = 0;
      for (let i = 1; i < o.beatNodeIds.length; i++) {
        const a = coordOf.get(o.beatNodeIds[i - 1]!)!;
        const b = coordOf.get(o.beatNodeIds[i]!)!;
        stepSum += Math.abs(a.r - b.r) + Math.abs(a.c - b.c);
      }
      expect(stepSum / Math.max(1, o.beatNodeIds.length - 1)).toBeLessThanOrEqual(1.5);
      let maxPairHops = 0;
      for (let i = 0; i < o.beatNodeIds.length; i++) {
        for (let j = i + 1; j < o.beatNodeIds.length; j++) {
          const a = coordOf.get(o.beatNodeIds[i]!)!;
          const b = coordOf.get(o.beatNodeIds[j]!)!;
          maxPairHops = Math.max(maxPairHops, Math.abs(a.r - b.r) + Math.abs(a.c - b.c));
        }
      }
      expect(maxPairHops).toBeLessThanOrEqual(5);
    }
  });

  it('LoopPlaceInit.staff sees crimes in the open shop and learns the player name after a shift', () => {
    const init = buildStubCapital({ seed: 'g012-staff', year: 1978, startHour: 10, playerName: 'Jordan Ellis' });
    const clerkA = 'p:staff_clerk_a';
    const clerkB = 'p:staff_clerk_b';
    init.people.push(
      { id: clerkA, first: 'Nora', surname: 'Reed', sex: 'F', age: 27, homePlaceId: 'place:home_04', traits: { diligence: 0.9 } },
      { id: clerkB, first: 'Walter', surname: 'Price', sex: 'M', age: 31, homePlaceId: 'place:home_05', traits: { diligence: 0.85 } },
    );
    const halePlace = init.places.find((pl) => pl.id === CAPITAL_IDS.haleStore)!;
    halePlace.staff = [clerkA, clerkB];

    let sim = create(init);
    expect(sim.places[CAPITAL_IDS.haleStore]!.staff).toEqual([clerkA, clerkB]);

    // 1. Work a shift at Hale's -> both staff clerks learn the player's name & gain trust
    const shift = act(sim, {
      kind: 'work_shift',
      placeId: CAPITAL_IDS.haleStore,
      hours: 2,
    });
    sim = shift.sim;
    for (const cid of [clerkA, clerkB]) {
      const att = attitude(sim, cid);
      expect(att.knowsPlayerName).toBe(true);
      expect(att.playerNameKnown).toBe('Jordan Ellis');
      expect(att.trust).toBeGreaterThan(0);
    }

    // 2. Shoplift while the shop is open with NO explicit bystanders passed -> on-duty staff witness it!
    const openSteal = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:staff_watch', label: 'a box of matches', value: 35 },
      light: 0.95,
    });
    const witnessIds = openSteal.witnessedBy.map((w) => w.personId);
    expect(witnessIds.some((id) => id === clerkA || id === clerkB)).toBe(true);

    // 3. When the shop is closed (hour 23), staff have gone home and do NOT auto-witness
    const nightSim = create({ ...init, startHour: 23 });
    const closedSteal = act(nightSim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:night_matches', label: 'a box of matches', value: 35 },
    });
    expect(closedSteal.witnessedBy).toHaveLength(0);
  });

  it('marks hot item sales legit: false and hot: true, while clean sales return legit: true and hot: false', () => {
    let sim = create(buildStubCapital({ seed: 'g012-hot-sale', year: 1978 }));

    const cleanSell = act(sim, {
      kind: 'sell',
      placeId: CAPITAL_IDS.pawnShop,
      item: { id: 'item:clean_coat', label: 'an old wool coat', value: 800 },
    });
    sim = cleanSell.sim;
    expect(cleanSell.legit).toBe(true);
    expect(cleanSell.hot).toBe(false);
    expect(cleanSell.pawnTraceFlagged).toBe(false);

    const steal = act(sim, {
      kind: 'take',
      subkind: 'house',
      placeId: CAPITAL_IDS.elmHouse,
      item: { id: 'item:hot_radio', label: 'a transistor radio', value: 1800, serialNumber: 'RAD-771' },
      bystanders: [],
    });
    sim = steal.sim;

    const hotSell = act(sim, {
      kind: 'sell',
      placeId: CAPITAL_IDS.pawnShop,
      item: { id: 'item:hot_radio', label: 'a transistor radio', value: 900, serialNumber: 'RAD-771' },
    });
    expect(hotSell.legit).toBe(false);
    expect(hotSell.hot).toBe(true);
    expect(hotSell.pawnTraceFlagged).toBe(true);
  });

  it('writes attitude facts using "you" (zero "this person") and uses playerDescription', () => {
    const init = buildStubCapital({
      seed: 'g012-you-facts',
      year: 1978,
      startDay: 1,
      startDayOfWeek: 1,
      playerName: 'Sam Rivera',
    });
    let sim = create({
      ...init,
      playerDescription: 'a slim stranger in a green coat',
    });

    // Work shift so Clara knows player's name
    sim = act(sim, {
      kind: 'work_shift',
      placeId: CAPITAL_IDS.market,
      hours: 2,
    }).sim;

    // Witnessed theft at Hale's
    sim = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:peaches_pov', label: 'a tin of peaches', value: 65 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: 1 }, activity: 'working' },
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
      ],
    }).sim;
    sim = advance(sim, 48).sim;

    const claraAtt = attitude(sim, CAPITAL_IDS.marketOwner);
    expect(claraAtt.facts.some((f) => f.includes('you') && f.includes('Sam Rivera'))).toBe(true);

    const marthaAtt = attitude(sim, CAPITAL_IDS.windowWoman);
    expect(marthaAtt.facts.some((f) => /I saw you steal/.test(f))).toBe(true);

    const marcusAtt = attitude(sim, CAPITAL_IDS.marcusHale);
    expect(marcusAtt.facts.some((f) => f.includes('told me you managed to'))).toBe(true);

    // Every single person in town has ZERO "this person" phrasing in their fact sheet
    let sawDescriptionInHearsay = false;
    for (const pid of Object.keys(sim.peopleMeta)) {
      const att = attitude(sim, pid);
      for (const fact of att.facts) {
        expect(fact.toLowerCase()).not.toContain('this person');
        if (fact.includes('a slim stranger in a green coat')) sawDescriptionInHearsay = true;
      }
    }
    expect(sawDescriptionInHearsay).toBe(true);
  });

  it('scales arrest fines in era integer cents using economy priceIndex(year)', () => {
    const runFineForYear = (year: number) => {
      let sim = create(buildStubCapital({ seed: 'g012-era-fine', year, startHour: 10 }));
      sim = act(sim, {
        kind: 'take',
        subkind: 'shoplift',
        placeId: CAPITAL_IDS.haleStore,
        item: { id: 'item:soap_era', label: 'a bar of soap', value: Math.max(5, Math.round(250 * priceIndex(year)!)) },
        bystanders: [
          { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
        ],
      }).sim;
      sim = advance(sim, 24).sim;
      return arrest(sim, { jurisdictionId: CAPITAL_JURISDICTION, playerCash: 100000 });
    };

    const res1952 = runFineForYear(1952);
    const res1978 = runFineForYear(1978);
    const res2025 = runFineForYear(2025);

    expect(res1952.penalty).toBe('fine');
    expect(res1978.penalty).toBe('fine');
    expect(res2025.penalty).toBe('fine');

    expect(Number.isInteger(res1952.fineAmount)).toBe(true);
    expect(Number.isInteger(res1978.fineAmount)).toBe(true);
    expect(Number.isInteger(res2025.fineAmount)).toBe(true);

    // 1952 fine << 1978 fine << 2025 fine, closely proportional to priceIndex(year)
    expect(res1952.fineAmount).toBeLessThan(res1978.fineAmount);
    expect(res1978.fineAmount).toBeLessThan(res2025.fineAmount);

    const ratio52 = res1952.fineAmount / res2025.fineAmount;
    const expected52 = priceIndex(1952)! / priceIndex(2025)!;
    expect(Math.abs(ratio52 - expected52)).toBeLessThan(0.015);
  });

  it('runs a real street stop that releases on a weak case, arrests on a strong case, and obeys tunable STOP_POLICY', () => {
    // 1. Weak case: single second-hand report of petty shoplifting (unknown suspect, thin evidence)
    let weakSim = create(buildStubCapital({ seed: 'first-encounter-1978', year: 1978, startDay: 1, startHour: 10, startDayOfWeek: 1 }));
    weakSim = act(weakSim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:weak_peaches', label: 'a tin of peaches', value: 65 },
      gloved: true,
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: 1 }, activity: 'working' },
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
      ],
    }).sim;
    weakSim = advance(weakSim, 48).sim;

    const weakView = policeView(weakSim, CAPITAL_JURISDICTION);
    expect(weakView.cases.length).toBe(1);
    expect(weakView.cases[0]!.strength.combinedScore).toBeLessThan(STOP_POLICY.stopArrestStrength);

    const officerId = Object.keys(weakSim.officers)[0]!;
    const weakSpot = spotted(weakSim, officerId, { distance: 4, light: 0.95 });
    expect(weakSpot.action).toBe('approach_and_question');
    expect(weakSpot.stopOutcome).toBeDefined();
    expect(weakSpot.stopOutcome!.questioned).toBe(true);
    expect(weakSpot.stopOutcome!.outcome).toBe('released');
    expect(weakSpot.stopOutcome!.thresholdUsed).toBe(STOP_POLICY.stopArrestStrength);
    expect(weakSpot.planSource).toBeDefined();
    expect(parsePlan(weakSpot.planSource!).ok).toBe(true);

    // Routing a weak street stop into arrest() also releases with zero jail/fine/priors!
    const stopRelease = arrest(weakSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      officerId,
      why: 'approach_and_question',
    });
    expect(stopRelease.releasedAfterStop).toBe(true);
    expect(stopRelease.penalty).toBe('warning');
    expect(stopRelease.fineAmount).toBe(0);
    expect(stopRelease.jailHours).toBe(0);
    expect(stopRelease.priorOffenses).toBe(0);

    // 2. Era-gated papers check: in 1900 (pre-1915), checkedPapers is false
    let oldSim = create(buildStubCapital({ seed: 'first-encounter-1978', year: 1900, startDay: 1, startHour: 10, startDayOfWeek: 1 }));
    oldSim = act(oldSim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:old_peaches', label: 'a tin of peaches', value: 15 },
      gloved: true,
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: 1 }, activity: 'working' },
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
      ],
    }).sim;
    oldSim = advance(oldSim, 48).sim;
    const oldSpot = spotted(oldSim, Object.keys(oldSim.officers)[0]!, { distance: 4, light: 0.95 });
    if (oldSpot.stopOutcome) {
      expect(oldSpot.stopOutcome.checkedPapers).toBe(false);
    }

    // 3. Tunable policy table: lowering stopArrestStrength turns the same weak stop into an arrest
    const strictSim = {
      ...weakSim,
      stopPolicy: { ...weakSim.stopPolicy, stopArrestStrength: 0.15 },
    };
    const strictSpot = spotted(strictSim, officerId, { distance: 4, light: 0.95 });
    expect(strictSpot.action).toBe('approach_and_question');
    expect(strictSpot.stopOutcome!.outcome).toBe('arrested');

    // 4. Strong case (named suspect via pawn serial match) -> arrested on stop or immediately!
    let strongSim = create(buildStubCapital({ seed: 'g012-strong-stop', year: 1982, startHour: 10 }));
    strongSim = act(strongSim, {
      kind: 'take',
      subkind: 'house',
      placeId: CAPITAL_IDS.elmHouse,
      item: { id: 'item:strong_watch', label: 'a pocket watch', value: 14000, serialNumber: 'STR-551' },
      bystanders: [],
    }).sim;
    strongSim = act(strongSim, {
      kind: 'sell',
      placeId: CAPITAL_IDS.pawnShop,
      item: { id: 'item:strong_watch', label: 'a pocket watch', value: 7000, serialNumber: 'STR-551' },
    }).sim;
    strongSim = advance(strongSim, 48).sim;

    const strongOfficer = Object.keys(strongSim.officers)[0]!;
    const strongSpot = spotted(strongSim, strongOfficer, { distance: 5, light: 0.95 });
    expect(['arrest', 'approach_and_question']).toContain(strongSpot.action);
    if (strongSpot.action === 'approach_and_question') {
      expect(strongSpot.stopOutcome!.outcome).toBe('arrested');
    }
    const strongArrest = arrest(strongSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      officerId: strongOfficer,
      why: 'approach_and_question',
    });
    expect(strongArrest.releasedAfterStop).toBeUndefined();
    expect(strongArrest.charges.length).toBeGreaterThan(0);
    expect(strongArrest.penalty).toBe('charged');
    expect(strongArrest.courtCase).toBeDefined();
    expect(strongArrest.priorOffenses).toBe(0);
  });
});

describe('simCall economy.*', () => {
  it('dispatches to the era economy', async () => {
    const { simCall } = await import('./bridge');
    const econ = await import('../economy');
    const call = (method: string, ...args: unknown[]) => JSON.parse(simCall(JSON.stringify({ method, args })));
    expect(call('economy.stockOf', 'general', 1952, 'gamma')).toEqual(econ.stockOf('general', 1952, 'gamma'));
    expect(call('economy.priceIn', 'peaches', 1952)).toBe(23);
    expect(call('economy.wage', 'odd_jobs', 1952)).toEqual({ hourlyCents: 75, shiftHours: 2, shiftCents: 150 });
    expect(call('economy.pawnOffer', 'radio', 1952, true)).toBe(300);
    expect(call('economy.formatMoney', 2995, 1952)).toBe('$29.95');
    expect(call('economy.priceIn', 'nonsense', 1952)).toBeNull();
    expect(() => call('economy.nope')).toThrow();
  });
});
