import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { generateWorld } from '../sim/worldgen';
import { BUSINESS_KINDS } from '../sim/names';
import { shopFacade } from './facade';
import { colourOf } from './buildings';
import { inspectAt } from './inspect';
import { layoutTown } from './layout';
import { advanceSettlement } from './properties';

const initial = () => {
  const w = generateWorld('alpha');
  advanceSettlement(w);
  return w;
};

describe('persistent properties', () => {
  it('keeps addresses and architecture through wealth changes and era boundaries', () => {
    const w = initial();
    const before = structuredClone(w.settlement!);
    w.year = 1990;
    for (const p of Object.values(w.people)) {
      p.wealth = p.id.includes(':0:') ? 100000 : 1;
      p.birthYear += 40; // Hold household ages fixed while crossing the art's era boundaries.
    }
    advanceSettlement(w);
    expect(w.settlement!.properties).toEqual(before.properties);
    expect(w.settlement!.roads).toEqual(expect.arrayContaining(before.roads));
  });

  it('keeps surviving family in their home and does not move unrelated households', () => {
    const w = initial();
    const before = structuredClone(w.settlement!.properties);
    const home = before.find((l) => l.kind === 'house' && l.heads.length === 1 && w.people[l.heads[0]!]!.spouse)!;
    const head = w.people[home.heads[0]!]!;
    const spouse = w.people[head.spouse!]!;
    w.year++;
    head.deathYear = w.year;
    spouse.spouse = undefined;
    advanceSettlement(w);
    const after = w.settlement!.properties.find((l) => l.slot === home.slot)!;
    expect(after.residents).toContain(spouse.id);
    expect(after.residents).not.toContain(head.id);
    expect(after.art).toEqual(home.art);
    for (const lot of before.filter((l) => l.kind === 'house' && l.slot !== home.slot)) {
      for (const id of lot.residents) {
        expect(w.settlement!.properties.find((l) => l.residents.includes(id))!.slot).toBe(lot.slot);
      }
    }
  });

  it('retains closed shops, blocks actions on former tenants, and reuses the property', () => {
    const w = initial();
    const old = structuredClone(w.settlement!.properties.find((l) => l.kind === 'shop')!);
    const business = w.businesses[old.refId]!;
    w.year++;
    business.closedYear = w.year;
    advanceSettlement(w);
    const vacant = layoutTown(w).vacantLots.find((l) => l.slot === old.slot)!;
    expect(vacant.art).toEqual(old.art);
    expect(vacant.vacantSince).toBe(w.year);
    const info = inspectAt(layoutTown(w), w, old.x + 1, old.y + 1)!;
    expect(info.people).toEqual([]);
    expect(info.business).toBeNull();
    w.year++;
    advanceSettlement(w);
    expect(w.settlement!.properties.find((l) => l.slot === old.slot)!.vacantSince).toBe(w.year - 1);
    w.businesses.replacement = { ...business, id: 'replacement', name: 'New bakery', kind: 'Bakery', foundedYear: w.year, closedYear: undefined };
    const count = w.settlement!.properties.length;
    advanceSettlement(w);
    const reused = w.settlement!.properties.find((l) => l.refId === 'replacement')!;
    expect(reused.slot).toBe(old.slot);
    expect(reused.vacantSince).toBeUndefined();
    expect(reused.builtYear).toBe(old.builtYear);
    expect(reused.art.layers.slice(1)).toEqual(old.art.layers.slice(1));
    expect(w.settlement!.properties.length).toBe(count);
  });

  it('matches replacement shop fronts to the existing shell for every business kind', () => {
    for (const colour of ['red', 'blue', 'gray'] as const) {
      for (const kind of BUSINESS_KINDS) {
        const art = shopFacade(kind, 0.37, 2025, colour);
        expect(art.colour).toBe(colour);
        const roof = Number(art.layers.find((l) => l.key.startsWith('roof_'))!.key.split('_').pop());
        expect({ red: [2, 8], blue: [4, 9], gray: [6, 11] }[colour]).toContain(roof);
        const middle = art.layers.find((l) => l.key.startsWith('middle_'));
        if (middle) expect(colourOf(Number(middle.key.split('_').pop()), 18)).toBe(colour);
      }
    }
  });

  it('houses everyone exactly once at every year across multiple seeds', () => {
    for (const seed of ['alpha', 'zebra', 'delta', 's6']) {
      for (const w of runTimeline({ seed, endYear: 2025 }).snapshots) {
        const properties = w.settlement!.properties;
        expect(new Set(properties.map((l) => l.slot)).size).toBe(properties.length);
        const occupied = properties.filter((l) => l.vacantSince === undefined);
        const houses = occupied.filter((l) => l.kind === 'house');
        const residents = houses.flatMap((l) => l.residents).sort();
        expect(residents).toEqual(Object.values(w.people).filter((p) => p.deathYear === undefined).map((p) => p.id).sort());
        for (const house of houses) expect(house.heads.length).toBeLessThanOrEqual(house.art.capacity);
        expect(occupied.filter((l) => l.kind === 'shop').map((l) => l.refId).sort())
          .toEqual(Object.values(w.businesses).filter((b) => b.closedYear === undefined).map((b) => b.id).sort());
      }
    }
  });

  it('preserves vacant homes and streets after depopulation', () => {
    const w = initial();
    const before = structuredClone(w.settlement!);
    w.year++;
    for (const p of Object.values(w.people)) p.deathYear = w.year;
    for (const b of Object.values(w.businesses)) b.closedYear = w.year;
    advanceSettlement(w);
    const l = layoutTown(w);
    expect(l.lots).toHaveLength(0);
    expect(l.vacantLots).toHaveLength(before.properties.length);
    expect(w.settlement!.roads).toEqual(expect.arrayContaining(before.roads));
    for (const lot of l.vacantLots) expect(lot.art).toEqual(before.properties.find((b) => b.slot === lot.slot)!.art);
  });

  it('rebuilds deterministically and renders identically regardless of year viewing order', () => {
    const options = { seed: 'alpha', endYear: 2025, interventions: [{ kind: 'kill' as const, year: 1960, targetId: 'gen:0:h' }] };
    const t = runTimeline(options);
    const before = JSON.stringify(t.snapshots);
    const last = JSON.stringify(layoutTown(worldAt(t, 2025)));
    for (const year of [1950, 2000, 1970, 2025, 1960]) layoutTown(worldAt(t, year));
    expect(JSON.stringify(layoutTown(worldAt(t, 2025)))).toBe(last);
    expect(JSON.stringify(t.snapshots)).toBe(before);
    expect(runTimeline(options).snapshots).toEqual(t.snapshots);
    for (let i = 1; i < t.snapshots.length; i++) {
      const previous = t.snapshots[i - 1]!.settlement!;
      const current = t.snapshots[i]!.settlement!;
      expect(current.roads).toEqual(expect.arrayContaining(previous.roads));
      for (const lot of previous.properties) {
        const next = current.properties.find((l) => l.slot === lot.slot)!;
        expect(next, `${t.snapshots[i]!.year} lost ${lot.slot}`).toBeDefined();
        if (lot.kind === 'house') expect(next.art).toEqual(lot.art);
        for (const head of lot.heads) {
          const retained = current.properties.find((l) => l.heads.includes(head));
          if (retained) expect(retained.slot).toBe(lot.slot);
        }
      }
    }
  });
});
