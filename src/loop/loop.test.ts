import { describe, expect, it } from 'vitest';
import {
  act,
  advance,
  arrest,
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
      item: { id: 'item:peaches_1', label: 'a tin of peaches', value: 3 },
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
      item: { id: 'item:wrench_1', label: 'a brass wrench', value: 8 },
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
      pay: 45,
      taskLabel: 'stocking crates',
      bystanders: [
        { id: CAPITAL_IDS.marketOwner, position: { x: 85, y: 5 }, activity: 'working' },
        { id: CAPITAL_IDS.marketCoworker, position: { x: 86, y: 6 }, activity: 'working' },
      ],
    });
    sim = shift.sim;

    expect(shift.legit).toBe(true);
    expect(shift.payEarned).toBe(45);
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
        value: 140,
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
        value: 70,
        serialNumber: 'HAM-448912',
      },
    });
    sim = sell.sim;
    expect(sell.legit).toBe(true);
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
      item: { id: 'item:soap_1', label: 'a bar of soap', value: 2 },
      bystanders: [
        { id: CAPITAL_IDS.marcusHale, position: { x: 40, y: 5 }, facing: { x: 0, y: -1 }, activity: 'watching' },
      ],
    });
    sim = shop.sim;
    sim = advance(sim, 24).sim;

    const firstArrest = arrest(sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      carriedItemIds: ['item:soap_1'],
      playerCash: 50,
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
      vehicle: { id: 'veh:sedan_1', kind: 'sedan', color: 'blue', plateNumber: 'CAP-309', value: 900 },
      bystanders: [
        { id: CAPITAL_IDS.marketOwner, position: { x: 84, y: 6 }, facing: { x: 1, y: -1 }, activity: 'watching' },
        { id: CAPITAL_IDS.marketCoworker, position: { x: 86, y: 6 }, facing: { x: -1, y: -1 }, activity: 'watching' },
      ],
    });
    sim = carSteal.sim;
    sim = advance(sim, 48).sim;

    const secondArrest = arrest(sim, { jurisdictionId: CAPITAL_JURISDICTION });
    sim = secondArrest.sim;
    expect(['cell_night', 'jail']).toContain(secondArrest.penalty);
    expect(secondArrest.jailHours).toBeGreaterThan(0);
    expect(secondArrest.priorOffenses).toBe(2);
  });

  it('round-trips cleanly through save/load and continues identically', () => {
    const init = buildStubCapital({ seed: 'roundtrip-seed', year: 1978 });
    let simA = create(init);
    simA = act(simA, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:tin_rt', label: 'a tin of beans', value: 3 },
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
        pay: 36,
      }).sim;
      s = act(s, {
        kind: 'take',
        subkind: 'shoplift',
        placeId: CAPITAL_IDS.haleStore,
        item: { id: 'item:peaches_det', label: 'a tin of peaches', value: 3 },
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
          pay: 40,
        }),
      ),
    );
    expect(actResp.legit).toBe(true);
    expect(actResp.payEarned).toBe(40);
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
      item: { id: "item:candy", label: "a tin of mints", value: 2 },
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
      item: { id: "item:jar_1", label: "a jar of honey", value: 4 },
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
