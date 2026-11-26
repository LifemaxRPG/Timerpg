import { describe, expect, it } from 'vitest';
import {
  act,
  advance,
  arrest,
  bailDecision,
  chooseCounsel,
  courtInterfere,
  courtView,
  create,
  load,
  plead,
  policeView,
  save,
  spotted,
} from './index';
import { TimerpgSim, simCall } from './bridge';
import { CAPITAL_IDS, CAPITAL_JURISDICTION, buildStubCapital } from './stubCapital';
import type { LoopSim } from './types';

function makeSolidBurglarySim(seed: string, year = 1955): { sim: LoopSim; crimeId: string } {
  let sim = create(buildStubCapital({ seed, year, startDay: 1, startHour: 10 }));
  const pos = sim.places[CAPITAL_IDS.haleStore]!.position;
  const burg = act(sim, {
    kind: 'take',
    subkind: 'house',
    placeId: CAPITAL_IDS.haleStore,
    light: 1.0,
    gloved: false,
    item: {
      id: `item:safe_${seed}`,
      label: 'a silver pocket watch',
      value: 28000,
      serialNumber: `SN-${seed}`,
    },
    bystanders: [
      {
        id: CAPITAL_IDS.marcusHale,
        position: { x: pos.x, y: pos.y + 1 },
        facing: { x: 0, y: -1 },
        activity: 'watching',
      },
      {
        id: CAPITAL_IDS.windowWoman,
        position: { x: pos.x + 1, y: pos.y },
        facing: { x: -1, y: 0 },
        activity: 'watching',
      },
      {
        id: CAPITAL_IDS.marketOwner,
        position: { x: pos.x - 1, y: pos.y },
        facing: { x: 1, y: 0 },
        activity: 'watching',
      },
    ],
  });
  sim = burg.sim;
  // Let witnesses report to police so the case has strong named first-hand evidence + prints.
  sim = advance(sim, 72).sim;
  return { sim, crimeId: burg.crimeId! };
}

describe('G019: Real court pipeline in src/loop', () => {
  it('releases a thin case, charges a solid case with era-scaled bail, and holds the worst without bail', () => {
    // 1. Thin case -> stop policy releases without charging
    let thinSim = create(buildStubCapital({ seed: 'g019-thin', year: 1955, startDay: 1, startHour: 22 }));
    thinSim = act(thinSim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      light: 0.25,
      item: { id: 'item:g019_thin_tin', label: 'a tin of peaches', value: 45 },
      bystanders: [
        {
          id: CAPITAL_IDS.windowWoman,
          position: { x: 40, y: -16 },
          facing: { x: 0, y: 1 },
          activity: 'walking',
        },
      ],
    }).sim;
    thinSim = advance(thinSim, 48).sim;
    const thinOfficer = Object.keys(thinSim.officers)[0]!;
    const thinSpot = spotted(thinSim, thinOfficer, { distance: 12, light: 0.4 });
    expect(thinSpot.action).not.toBe('arrest');
    const thinArrest = arrest(thinSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      officerId: thinOfficer,
      why: 'approach_and_question',
    });
    expect(thinArrest.releasedAfterStop).toBe(true);
    expect(thinArrest.penalty).toBe('warning');
    expect(thinArrest.courtCase).toBeUndefined();
    expect(courtView(thinArrest.sim).pending).toHaveLength(0);

    // 2. Solid burglary case -> charged with era-scaled bail and future court date
    const solid1920 = makeSolidBurglarySim('g019-solid-1920', 1920);
    const solid1985 = makeSolidBurglarySim('g019-solid-1985', 1985);
    const arr1920 = arrest(solid1920.sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [solid1920.crimeId],
    });
    const arr1985 = arrest(solid1985.sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [solid1985.crimeId],
    });

    expect(arr1920.releasedAfterStop).toBeUndefined();
    expect(arr1920.penalty).toBe('charged');
    expect(arr1920.courtCase).toBeDefined();
    expect(arr1920.courtCase!.bail.granted).toBe(true);
    expect(arr1920.courtCase!.bail.amountCents).toBeGreaterThan(0);
    expect(arr1920.courtCase!.courtDateDay).toBeGreaterThan(arr1920.sim.currentDay);
    expect(arr1920.courtCase!.courtDateHour).toBe(9);
    expect(arr1920.courtCase!.charges.length).toBeGreaterThan(0);

    expect(arr1985.penalty).toBe('charged');
    expect(arr1985.courtCase).toBeDefined();
    expect(arr1985.courtCase!.bail.granted).toBe(true);
    // 1985 era bail (moneyScale 1.0) must exceed 1920 era bail (moneyScale 0.30)
    expect(arr1985.courtCase!.bail.amountCents).toBeGreaterThan(
      arr1920.courtCase!.bail.amountCents,
    );

    // 3. Worst case (murder) -> charged & held without bail
    let worstSim = create(buildStubCapital({ seed: 'g019-murder', year: 1955, startDay: 1, startHour: 14 }));
    const murderAct = act(worstSim, {
      kind: 'assault',
      placeId: CAPITAL_IDS.market,
      targetId: CAPITAL_IDS.marketCoworker,
      lethal: true,
      weapon: true,
      light: 0.95,
      gloved: false,
      bystanders: [
        {
          id: CAPITAL_IDS.marketOwner,
          position: { x: 84, y: 6 },
          facing: { x: 1, y: -1 },
          activity: 'watching',
        },
        {
          id: CAPITAL_IDS.windowWoman,
          position: { x: 83, y: 5 },
          facing: { x: 1, y: 0 },
          activity: 'watching',
        },
      ],
    });
    expect(murderAct.crimeId).toBeDefined();
    expect(murderAct.sim.crimeState.crimes[murderAct.crimeId!]?.kind).toBe('murder');
    worstSim = advance(murderAct.sim, 24).sim;
    const worstArrest = arrest(worstSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [murderAct.crimeId!],
    });
    expect(worstArrest.penalty).toBe('charged');
    expect(worstArrest.courtCase).toBeDefined();
    expect(worstArrest.courtCase!.bail.granted).toBe(false);
    expect(worstArrest.courtCase!.bail.amountCents).toBe(0);
    expect(worstArrest.courtCase!.custody).toBe('remanded_no_bail');

    // Attempting to pay bail when held without bail must be rejected
    const bailTry = bailDecision(worstArrest.sim, {
      courtCaseId: worstArrest.courtCase!.id,
      choice: 'pay_bail',
      playerCash: 99_999_999,
    });
    expect(bailTry.ok).toBe(false);
    expect(bailTry.inCustody).toBe(true);
    expect(bailTry.bailPaidCents).toBe(0);
  });

  it('supports bail decisions, budget-aware counsel tiers, and courtView', () => {
    const { sim: baseSim, crimeId } = makeSolidBurglarySim('g019-choices', 1955);
    const charged = arrest(baseSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [crimeId],
    });
    expect(charged.penalty).toBe('charged');
    const cc = charged.courtCase!;

    // Too little cash cannot make bail
    const failBail = bailDecision(charged.sim, {
      courtCaseId: cc.id,
      choice: 'pay_bail',
      playerCash: Math.max(0, cc.bail.amountCents - 100),
    });
    expect(failBail.ok).toBe(false);
    expect(failBail.inCustody).toBe(true);

    // Staying in cell works cleanly
    const stayCell = bailDecision(charged.sim, {
      courtCaseId: cc.id,
      choice: 'stay_in_cell',
    });
    expect(stayCell.ok).toBe(true);
    expect(stayCell.inCustody).toBe(true);
    expect(stayCell.courtCase!.custody).toBe('in_custody_bail_unpaid');

    // Paying bail releases the player pending trial
    const madeBail = bailDecision(charged.sim, {
      courtCaseId: cc.id,
      choice: 'pay_bail',
      playerCash: cc.bail.amountCents + 50000,
    });
    expect(madeBail.ok).toBe(true);
    expect(madeBail.inCustody).toBe(false);
    expect(madeBail.bailPaidCents).toBe(cc.bail.amountCents);
    expect(madeBail.courtCase!.custody).toBe('out_on_bail');

    // Choosing counsel with a budget picks the highest affordable tier
    const cheapFee = cc.lawyerOptions.find((o) => o.tier === 'local_attorney')!.feeCents;
    const solidFee = cc.lawyerOptions.find((o) => o.tier === 'retained_counsel')!.feeCents;
    expect(cheapFee).toBeGreaterThan(0);
    expect(solidFee).toBeGreaterThan(cheapFee);

    const autoCounsel = chooseCounsel(madeBail.sim, {
      courtCaseId: cc.id,
      playerCash: cheapFee + 500,
    });
    expect(autoCounsel.ok).toBe(true);
    expect(autoCounsel.lawyer!.tier).toBe('local_attorney');
    expect(autoCounsel.feeCents).toBe(cheapFee);

    // Explicitly requesting an unaffordable tier fails cleanly
    const cantAffordTop = chooseCounsel(autoCounsel.sim, {
      courtCaseId: cc.id,
      tier: 'elite_defense',
      playerCash: cheapFee,
    });
    expect(cantAffordTop.ok).toBe(false);

    // courtView summarizes the pending case and options
    const view = courtView(autoCounsel.sim);
    expect(view.pending).toHaveLength(1);
    expect(view.pending[0]!.id).toBe(cc.id);
    expect(view.pending[0]!.lawyer.tier).toBe('local_attorney');
    expect(view.activeWarrants).toHaveLength(0);
    expect(view.pending[0]!.facts.length).toBeGreaterThan(0);
  });

  it('gives a lighter sentence for a guilty plea than a guilty verdict on the exact same case', () => {
    // Pick a deterministic seed where the strong-evidence trial reaches a guilty verdict.
    let verified = false;
    for (let i = 0; i < 8; i++) {
      const { sim: baseSim, crimeId } = makeSolidBurglarySim(`g019-plea-vs-trial-${i}`, 1955);
      const charged = arrest(baseSim, {
        jurisdictionId: CAPITAL_JURISDICTION,
        crimeIds: [crimeId],
      });
      const cc = charged.courtCase!;
      const postBail = bailDecision(charged.sim, {
        courtCaseId: cc.id,
        choice: 'pay_bail',
        playerCash: cc.bail.amountCents + 100000,
      }).sim;

      // Branch A: plead guilty
      const pleaRes = plead(postBail, { courtCaseId: cc.id, plea: 'guilty' });
      expect(pleaRes.ok).toBe(true);
      expect(pleaRes.jailDays).toBeGreaterThan(0);
      expect(pleaRes.daysElapsed).toBe(pleaRes.jailDays);

      // Branch B: plead not guilty and advance to trial date
      const ngRes = plead(postBail, { courtCaseId: cc.id, plea: 'not_guilty' });
      expect(ngRes.ok).toBe(true);
      const hoursToTrial =
        (cc.courtDateDay - ngRes.sim.currentDay) * 24 +
        (cc.courtDateHour - ngRes.sim.currentHour) +
        1;
      const trialAdv = advance(ngRes.sim, hoursToTrial);
      const trialEv = trialAdv.courtEvents?.find((e) => e.kind === 'trial_completed');
      expect(trialEv).toBeDefined();

      if (trialEv!.verdict === 'guilty' && trialEv!.sentence) {
        expect(pleaRes.jailDays).toBeLessThan(trialEv!.sentence.jailDays);
        expect(pleaRes.fineAmount).toBeLessThanOrEqual(trialEv!.sentence.fineAmount);
        verified = true;
        break;
      }
    }
    expect(verified).toBe(true);
  });

  it('runs the trial on its date and serves its sentence; a missed date issues a warrant police act on', () => {
    // Part A: Attend trial on its scheduled date
    let foundGuiltyTrial = false;
    for (let i = 0; i < 8; i++) {
      const { sim: baseSim, crimeId } = makeSolidBurglarySim(`g019-trial-date-${i}`, 1955);
      const charged = arrest(baseSim, {
        jurisdictionId: CAPITAL_JURISDICTION,
        crimeIds: [crimeId],
      });
      const cc = charged.courtCase!;
      const postBail = bailDecision(charged.sim, {
        courtCaseId: cc.id,
        choice: 'pay_bail',
        playerCash: cc.bail.amountCents + 100000,
      }).sim;

      // Advancing before the court date should NOT trigger the trial yet
      const earlyAdv = advance(postBail, 24);
      expect(earlyAdv.courtEvents).toBeUndefined();
      expect(courtView(earlyAdv.sim).pending).toHaveLength(1);

      // Advance past the court date attending court
      const hoursToDate =
        (cc.courtDateDay - earlyAdv.sim.currentDay) * 24 +
        (cc.courtDateHour - earlyAdv.sim.currentHour) +
        2;
      const onDateAdv = advance(earlyAdv.sim, hoursToDate, {
        playerPlaceId: cc.courtPlaceId,
      });
      const ev = onDateAdv.courtEvents?.find((e) => e.kind === 'trial_completed');
      expect(ev).toBeDefined();
      expect(ev!.day).toBe(cc.courtDateDay);
      expect(ev!.facts.length).toBeGreaterThanOrEqual(3);
      expect(ev!.deliberationRounds).toBeGreaterThanOrEqual(1);

      if (ev!.verdict === 'guilty' && ev!.sentence) {
        foundGuiltyTrial = true;
        expect(ev!.sentence.jailDays).toBeGreaterThan(0);
        expect(ev!.sentence.fineAmount).toBeGreaterThan(0);
        expect(Number.isInteger(ev!.sentence.fineAmount)).toBe(true);
        // Sentence was served through advance()
        expect(onDateAdv.toDay).toBeGreaterThanOrEqual(cc.courtDateDay + ev!.sentence.jailDays);
        expect(onDateAdv.sim.priorOffenses.length).toBe(1);
        expect(courtView(onDateAdv.sim).pending).toHaveLength(0);
        break;
      }
    }
    expect(foundGuiltyTrial).toBe(true);

    // Part B: Miss the court date -> forfeits bail, issues warrant, police act on it
    const { sim: ftaBase, crimeId: ftaCrimeId } = makeSolidBurglarySim('g019-fta', 1955);
    const ftaCharged = arrest(ftaBase, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [ftaCrimeId],
    });
    const ftaCase = ftaCharged.courtCase!;
    const outOnBail = bailDecision(ftaCharged.sim, {
      courtCaseId: ftaCase.id,
      choice: 'pay_bail',
      playerCash: ftaCase.bail.amountCents + 50000,
    }).sim;

    // While out on bail before court date, police aren't hunting the already-booked crime
    expect(policeView(outOnBail, CAPITAL_JURISDICTION).cases).toHaveLength(0);

    const hoursToFta =
      (ftaCase.courtDateDay - outOnBail.currentDay) * 24 +
      (ftaCase.courtDateHour - outOnBail.currentHour) +
      1;
    // Skip court by being at another place (or passing attendCourt: false)
    const ftaAdv = advance(outOnBail, hoursToFta, {
      playerPlaceId: CAPITAL_IDS.market,
    });

    const ftaEvent = ftaAdv.courtEvents?.find((e) => e.kind === 'missed_court_date');
    expect(ftaEvent).toBeDefined();
    expect(ftaEvent!.bailForfeitedCents).toBe(ftaCase.bail.amountCents);
    expect(ftaEvent!.warrant).toBeDefined();
    expect(ftaEvent!.warrant!.active).toBe(true);
    expect(ftaEvent!.warrant!.reason).toBe('missed_court_date');
    expect(ftaEvent!.facts.some((f) => f.toLowerCase().includes('warrant'))).toBe(true);

    // Warrant appears in courtView and re-activates police case & officer dispatch
    const postFtaView = courtView(ftaAdv.sim);
    expect(postFtaView.activeWarrants).toHaveLength(1);
    const postFtaPolice = policeView(ftaAdv.sim, CAPITAL_JURISDICTION);
    expect(postFtaPolice.cases.some((c) => c.crimeId === ftaCrimeId)).toBe(true);
    expect(
      ftaAdv.policePlans.some(
        (p) => p.assignment !== 'patrol' || p.crimeId === ftaCrimeId,
      ),
    ).toBe(true);

    // Re-arresting executes the warrant and tightens/denies bail (priorEscape)
    const rearrest = arrest(ftaAdv.sim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [ftaCrimeId],
    });
    expect(rearrest.penalty).toBe('charged');
    expect(courtView(rearrest.sim).activeWarrants).toHaveLength(0);
    expect(rearrest.sim.warrants!.every((w) => !w.active)).toBe(true);
    expect(rearrest.courtCase!.bail.flightRisk).toBeGreaterThan(ftaCase.bail.flightRisk);
  });

  it('bribing a juror opens a bribery case (plus witness intimidation and perjury)', () => {
    const { sim: baseSim, crimeId } = makeSolidBurglarySim('g019-bribe', 1955);
    const charged = arrest(baseSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [crimeId],
    });
    const cc = charged.courtCase!;
    const outOnBail = bailDecision(charged.sim, {
      courtCaseId: cc.id,
      choice: 'pay_bail',
      playerCash: cc.bail.amountCents + 50000,
    }).sim;

    expect(cc.jurySelection).toBeDefined();
    const jurorId = cc.jurySelection!.seated[0]!.personId;

    // Bribe a seated juror with a tiny offer and a bystander watching -> guaranteed bribery police case
    const bribeRes = courtInterfere(outOnBail, {
      courtCaseId: cc.id,
      kind: 'bribe_juror',
      targetId: jurorId,
      bribeAmount: 500, // $5 in cents -> low enough or witnessed to open a live bribery case
      bystanders: [
        {
          id: CAPITAL_IDS.windowWoman,
          position: { x: 40, y: 0 },
          facing: { x: 0, y: 1 },
          activity: 'watching',
        },
      ],
    });
    expect(bribeRes.ok).toBe(true);
    expect(bribeRes.crimeKind).toBe('bribery');
    expect(bribeRes.crimeId).toBeDefined();
    expect(bribeRes.sim.crimeState.crimes[bribeRes.crimeId!]?.kind).toBe('bribery');
    expect(bribeRes.policeCaseOpened).toBe(true);

    const pView = policeView(bribeRes.sim, CAPITAL_JURISDICTION);
    expect(pView.cases.some((c) => c.crimeId === bribeRes.crimeId && c.kind === 'bribery')).toBe(
      true,
    );

    // Also verify act({ kind: 'court_interference' }) opens intimidation and perjury cases
    const witnessId = cc.witnessIds[0] ?? CAPITAL_IDS.marcusHale;
    const threatAct = act(bribeRes.sim, {
      kind: 'court_interference',
      courtCaseId: cc.id,
      interferenceKind: 'threaten_witness',
      targetId: witnessId,
      bystanders: [
        {
          id: CAPITAL_IDS.marketOwner,
          position: { x: 84, y: 6 },
          facing: { x: 1, y: 0 },
          activity: 'watching',
        },
      ],
    });
    expect(threatAct.crimeId).toBeDefined();
    expect(threatAct.crimeId).toBeDefined();
    expect(threatAct.sim.crimeState.crimes[threatAct.crimeId!]?.kind).toBe('intimidation');

    const perjuryRes = courtInterfere(threatAct.sim, {
      courtCaseId: cc.id,
      kind: 'perjury',
      caughtInCourt: true,
    });
    expect(perjuryRes.ok).toBe(true);
    expect(perjuryRes.crimeKind).toBe('perjury');
    expect(perjuryRes.policeCaseOpened).toBe(true);
    expect(
      policeView(perjuryRes.sim, CAPITAL_JURISDICTION).cases.some((c) => c.kind === 'perjury'),
    ).toBe(true);
  });

  it('round-trips a save taken between arrest and trial and continues identically', () => {
    const { sim: baseSim, crimeId } = makeSolidBurglarySim('g019-save-rt', 1955);
    const charged = arrest(baseSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [crimeId],
    });
    const cc = charged.courtCase!;

    let simA = bailDecision(charged.sim, {
      courtCaseId: cc.id,
      choice: 'pay_bail',
      playerCash: cc.bail.amountCents + 100000,
    }).sim;
    simA = chooseCounsel(simA, {
      courtCaseId: cc.id,
      tier: 'local_attorney',
      playerCash: 100000,
    }).sim;
    simA = advance(simA, 48).sim; // midway between arrest and trial

    const serialized = save(simA);
    const simB = load(serialized);
    expect(simB).toEqual(simA);

    const hoursPastTrial =
      (cc.courtDateDay - simA.currentDay) * 24 + (cc.courtDateHour - simA.currentHour) + 24;
    const advA = advance(simA, hoursPastTrial);
    const advB = advance(simB, hoursPastTrial);

    expect(advA.courtEvents?.length).toBeGreaterThanOrEqual(1);
    expect(advB.courtEvents).toEqual(advA.courtEvents);
    expect(save(advB.sim)).toBe(save(advA.sim));
    expect(advB.policePlans).toEqual(advA.policePlans);
  });

  it('exposes bailDecision, chooseCounsel, plead, courtInterfere, and courtView via TimerpgSim and simCall', () => {
    const { sim: baseSim, crimeId } = makeSolidBurglarySim('g019-bridge', 1955);
    const charged = arrest(baseSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [crimeId],
    });
    const cc = charged.courtCase!;

    const call = (method: string, ...args: unknown[]) =>
      JSON.parse(simCall(JSON.stringify({ method, args })));

    TimerpgSim.load(save(charged.sim));

    const view1 = call('courtView');
    expect(view1.pending).toHaveLength(1);

    const bail1 = call('bailDecision', {
      courtCaseId: cc.id,
      choice: 'pay_bail',
      playerCash: cc.bail.amountCents + 50000,
    });
    expect(bail1.ok).toBe(true);
    expect(bail1.inCustody).toBe(false);

    const counsel1 = JSON.parse(
      TimerpgSim.chooseCounsel(
        JSON.stringify({ courtCaseId: cc.id, tier: 'local_attorney', playerCash: 50000 }),
      ),
    );
    // Also verify stateless *Json variant
    const counselStateless = JSON.parse(
      TimerpgSim.chooseCounselJson(
        TimerpgSim.save(),
        JSON.stringify({ courtCaseId: cc.id, tier: 'local_attorney', playerCash: 50000 }),
      ),
    );
    expect(counselStateless.ok).toBe(true);
    expect(counsel1.ok).toBe(true);
    expect(counsel1.lawyer.tier).toBe('local_attorney');

    const interfere1 = call('courtInterfere', {
      courtCaseId: cc.id,
      kind: 'bribe_juror',
      bribeAmount: 500,
      bystanders: [
        {
          id: CAPITAL_IDS.windowWoman,
          position: { x: 40, y: 0 },
          facing: { x: 0, y: 1 },
          activity: 'watching',
        },
      ],
    });
    expect(interfere1.ok).toBe(true);
    expect(interfere1.crimeKind).toBe('bribery');

    const plea1 = call('plead', {
      courtCaseId: cc.id,
      plea: 'guilty',
    });
    expect(plea1.ok).toBe(true);
    expect(plea1.jailDays).toBeGreaterThan(0);

    const view2 = JSON.parse(TimerpgSim.courtView());
    const view2Stateless = JSON.parse(TimerpgSim.courtViewJson(TimerpgSim.save()));
    expect(view2Stateless.pending).toHaveLength(0);
    expect(view2.pending).toHaveLength(0);
  });

  it('brings an in-custody defendant to court automatically on the court date', () => {
    const { sim: baseSim, crimeId } = makeSolidBurglarySim('g019-in-custody', 1955);
    const charged = arrest(baseSim, {
      jurisdictionId: CAPITAL_JURISDICTION,
      crimeIds: [crimeId],
    });
    const cc = charged.courtCase!;
    const stayed = bailDecision(charged.sim, {
      courtCaseId: cc.id,
      choice: 'stay_in_cell',
    });
    expect(stayed.ok).toBe(true);
    expect(stayed.inCustody).toBe(true);

    const hoursToTrial =
      (cc.courtDateDay - stayed.sim.currentDay) * 24 +
      (cc.courtDateHour - stayed.sim.currentHour) +
      2;
    const adv = advance(stayed.sim, hoursToTrial, { attendCourt: false });
    const trialEv = adv.courtEvents?.find((e) => e.kind === 'trial_completed');
    expect(trialEv).toBeDefined();
    expect(adv.courtEvents?.some((e) => e.kind === 'missed_court_date')).toBe(false);
    expect(courtView(adv.sim).activeWarrants).toHaveLength(0);
  });
});
