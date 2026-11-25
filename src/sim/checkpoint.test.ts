import { describe, expect, it } from 'vitest';
import {
  diffWorlds,
  isAlive,
  isEmptyDiff,
  runTimeline,
  worldAt,
  type Intervention,
} from './index';

describe('checkpoints and onlyFinalSnapshot (G018)', () => {
  it('resuming from a checkpoint produces the exact same world (empty diffWorlds) without mutating the checkpoint', () => {
    const seed = 'alpha';
    const base = runTimeline({ seed, startYear: 1950, endYear: 2025 });
    const w1974 = worldAt(base, 1974);
    const w1975 = worldAt(base, 1975);
    const victim = Object.values(w1975.people).find((p) => isAlive(p) && 1975 - p.birthYear >= 25)!;
    const store = Object.values(w1975.businesses).find((b) => b.closedYear === undefined)!;

    const interventions: Intervention[] = [
      { kind: 'kill', year: 1975, targetId: victim.id },
      { kind: 'arson', year: 1976, businessId: store.id, atNight: false },
      { kind: 'rumor', year: 1980, targetId: w1975.mayor!, amount: -0.6 },
    ];

    const checkpointSnapshotBefore = JSON.stringify(w1974);

    const withoutCheckpoint = runTimeline({
      seed,
      startYear: 1950,
      endYear: 2025,
      interventions,
    });

    const withCheckpoint = runTimeline({
      seed,
      startYear: 1950,
      endYear: 2025,
      interventions,
      checkpoint: w1974,
      checkpointYear: 1974,
    });

    const fullEnd = worldAt(withoutCheckpoint, 2025);
    const ckptEnd = worldAt(withCheckpoint, 2025);

    expect(isEmptyDiff(diffWorlds(fullEnd, ckptEnd))).toBe(true);
    expect(JSON.stringify(ckptEnd)).toBe(JSON.stringify(fullEnd));
    // Checkpoint passed in by caller must remain completely untouched
    expect(JSON.stringify(w1974)).toBe(checkpointSnapshotBefore);
  });

  it('onlyFinalSnapshot skips intermediate clones while giving the same final world (empty diffWorlds)', () => {
    const seed = 'alpha';
    const base = runTimeline({ seed, startYear: 1950, endYear: 2025 });
    const w1951 = worldAt(base, 1951);
    const store = Object.values(worldAt(base, 1952).businesses).find((b) => b.closedYear === undefined)!;
    const ivs: Intervention[] = [{ kind: 'arson', year: 1952, businessId: store.id }];

    const full = runTimeline({ seed, startYear: 1950, endYear: 2025, interventions: ivs });
    const fast = runTimeline({
      seed,
      startYear: 1950,
      endYear: 2025,
      interventions: ivs,
      checkpoint: w1951,
      checkpointYear: 1951,
      onlyFinalSnapshot: true,
      settlementBaseline: worldAt(base, 2025).settlement,
    });

    expect(fast.snapshots).toHaveLength(1);
    const fullEnd = worldAt(full, 2025);
    const fastEnd = worldAt(fast, 2025);

    expect(isEmptyDiff(diffWorlds(fullEnd, fastEnd))).toBe(true);
    expect(JSON.stringify(fastEnd.people)).toBe(JSON.stringify(fullEnd.people));
    expect(JSON.stringify(fastEnd.businesses)).toBe(JSON.stringify(fullEnd.businesses));
    expect(JSON.stringify(fastEnd.stories)).toBe(JSON.stringify(fullEnd.stories));
    expect(fastEnd.mayor).toBe(fullEnd.mayor);
    expect(() => worldAt(fast, 1960)).toThrow();
  });
});

describe('native arson intervention (G018)', () => {
  it('closes the business in that year with one "<Store> burned down" event, a Gazette story, and clears the lot for normal rebuilding', () => {
    const seed = 'alpha';
    const base = runTimeline({ seed, startYear: 1950, endYear: 1970 });
    const w1951 = worldAt(base, 1951);
    const store = Object.values(w1951.businesses).find((b) => b.closedYear === undefined)!;
    const origLot = w1951.settlement!.properties.find((l) => l.kind === 'shop' && l.refId === store.id)!;
    expect(origLot).toBeDefined();

    const alt = runTimeline({
      seed,
      startYear: 1950,
      endYear: 1970,
      interventions: [{ kind: 'arson', year: 1952, businessId: store.id, atNight: false }],
    });

    const w1952 = worldAt(alt, 1952);
    expect(w1952.businesses[store.id]!.closedYear).toBe(1952);
    expect(w1952.businesses[store.id]!.capital).toBe(0);

    // Exactly one chronicle event for the burned store, no sabotage or bankruptcy spam
    const storeEvents = alt.chronicle.filter((e) => e.subject === store.id && e.year === 1952);
    expect(storeEvents).toHaveLength(1);
    expect(storeEvents[0]!.kind).toBe('business_closed');
    expect(storeEvents[0]!.text).toBe(`${store.name} burned down`);

    // Gazette story in 1952 reports "<Store> burns down"
    const gazette = (w1952.stories ?? []).find((s) => s.template === 'arson' && s.business === store.id);
    expect(gazette).toBeDefined();
    expect(gazette!.year).toBe(1952);
    expect(gazette!.headline).toBe(`${store.name} burns down`);

    // Burned lot is either empty (removed) in 1952 or newly constructed for a different business (>= 1952)
    const lot1952 = w1952.settlement!.properties.find((l) => l.slot === origLot.slot);
    if (lot1952) {
      expect(lot1952.refId).not.toBe(store.id);
      expect(lot1952.builtYear).toBe(1952);
    }

    // By 1970, normal settlement rules have either rebuilt a new storefront on that slot or left it empty (never the burned store)
    const lot1970 = worldAt(alt, 1970).settlement!.properties.find((l) => l.slot === origLot.slot);
    if (lot1970) {
      expect(lot1970.refId).not.toBe(store.id);
      expect(lot1970.builtYear!).toBeGreaterThanOrEqual(1952);
    }
  });

  it('at night, arson can hurt or kill people inside by a seeded chance (while daytime arson leaves people unharmed)', () => {
    let killedCount = 0;
    let injuredCount = 0;
    let daytimeKilled = 0;

    for (let i = 0; i < 20; i++) {
      const seed = `arson-seed-${i}`;
      const base = runTimeline({ seed, startYear: 1950, endYear: 1952 });
      const w1951 = worldAt(base, 1951);
      const store = Object.values(w1951.businesses).find((b) => b.closedYear === undefined && b.owner)!;
      const ownerId = store.owner!;

      const nightRun = runTimeline({
        seed,
        startYear: 1950,
        endYear: 1952,
        interventions: [{ kind: 'arson', year: 1952, businessId: store.id, atNight: true }],
      });
      const dayRun = runTimeline({
        seed,
        startYear: 1950,
        endYear: 1952,
        interventions: [{ kind: 'arson', year: 1952, businessId: store.id, atNight: false }],
      });

      const nightStory = (worldAt(nightRun, 1952).stories ?? []).find((s) => s.template === 'arson')!;
      if (nightStory.body.includes('perished')) killedCount += 1;
      if (nightStory.body.includes('burns and smoke inhalation')) injuredCount += 1;

      const dayOwner = worldAt(dayRun, 1952).people[ownerId]!;
      if (dayOwner.deathCause === 'murdered') daytimeKilled += 1;
    }

    expect(killedCount).toBeGreaterThan(0);
    expect(injuredCount).toBeGreaterThan(0);
    expect(daytimeKilled).toBe(0);
  });

  it('fails gracefully when targeting a missing or already-closed business', () => {
    const t = runTimeline({
      seed: 'alpha',
      startYear: 1950,
      endYear: 1953,
      interventions: [{ kind: 'arson', year: 1952, businessId: 'b:nonexistent' }],
    });
    expect(t.chronicle.some((e) => e.kind === 'intervention_failed')).toBe(true);
  });
});
