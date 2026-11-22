// The streets the Kotlin renderer is given (tools/town-streets.ts): our mobile homes and villas wear their lot's own
// sprite, no row repeats a house design, and the export is deterministic and matches what is committed.
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { townStreets } from './town-streets';
import { wholeTown } from './town-whole';
import { neighborhoodOf, paletteColours } from '../../src/town/neighborhoods';

type Entry = { id: string; sprite: string; home: boolean; name: string; parts: { key: string }[]; house?: { tier: string; design: number; colour: string }; yard?: { front: number; style: string } };

const SEED = 'eta';
const YEAR = 2025;
const ts = townStreets(SEED, YEAR);
const json = ts.json as { streets: { name: string; buildings: string[] }[]; junction: { mainEast: string[]; crossEast: string[] }; shops: Entry[] };
const bySprite = new Map(json.shops.map((e) => [e.sprite, e]));

describe('town streets', () => {
  it('dresses small and medium homes in their tier\'s mobile home, in their district\'s colours', () => {
    // (the planned town's crossroads are its Main Street and civic avenue, shops and civic buildings: the homes are the
    // whole town's, dressed by the same entries, tools/town-buildings.ts)
    let houses = 0;
    for (const seed of [SEED, 'gamma']) {
      const t = wholeTown(seed, YEAR);
      for (const e of t.buildings.entries as Entry[]) {
        if (!e.home) continue;
        const lot = t.lotOf.get(e.sprite);
        if (!lot) continue;
        if (lot.tier !== 'small' && lot.tier !== 'medium') { expect(e.house).toBeUndefined(); continue; }
        if (!/^house_(small|medium)_/.test(e.parts[0]!.key)) continue;
        houses++;
        expect(e.parts).toHaveLength(1);
        expect(e.parts[0]!.key).toMatch(new RegExp(`^house_${lot.tier}_[1-8](_c[1-9])?$`));
        expect(e.house!.tier).toBe(lot.tier);
        const c = Number(e.house!.colour.slice(1) || 0);
        expect(paletteColours[neighborhoodOf(t.layout.plan, lot.neighborhood).palette]).toContain(c);
        expect(e.yard).toEqual({ front: lot.front, style: neighborhoodOf(t.layout.plan, lot.neighborhood).yard });
      }
    }
    expect(houses).toBeGreaterThan(3);
  });

  it('dresses villa-tier homes in their own villas, with their yards', () => {
    let villas = 0;
    for (const seed of ['gamma']) {
      const v = wholeTown(seed, YEAR);
      const vj = { shops: v.buildings.entries as Entry[] };
      for (const e of vj.shops) {
        const lot = v.lotOf.get(e.sprite);
        if (!lot || !e.home || lot.tier !== 'villa') continue;
        villas++;
        expect(e.parts).toHaveLength(1);
        expect(e.parts[0]!.key).toMatch(/^villa_[1-5]$/);
        expect(e.house).toBeUndefined();
        expect(e.yard).toEqual({ front: lot.front, style: neighborhoodOf(v.layout.plan, lot.neighborhood).yard });
      }
      // the other homes there keep what they wore: mobile homes their own, the rest the condo stand-in
      for (const e of vj.shops) if (e.home && v.lotOf.get(e.sprite) && v.lotOf.get(e.sprite)!.tier !== 'villa') expect(e.parts[0]!.key).not.toMatch(/^villa_/);
    }
    expect(villas).toBeGreaterThan(0);
  });

  it('never repeats a house design within two houses along a row', () => {
    const rows = [json.streets[0]!.buildings.concat(json.junction.mainEast), json.streets[1]!.buildings, json.junction.crossEast];
    for (const row of rows) {
      const designs = row.map((s) => bySprite.get(s)!.house).filter((h) => h).map((h) => `${h!.tier}_${h!.design}`);
      for (let i = 1; i < designs.length; i++) {
        expect(designs[i]).not.toBe(designs[i - 1]);
        if (i > 1) expect(designs[i]).not.toBe(designs[i - 2]);
      }
    }
  });

  it('keeps every building\'s sprite name, id and place', () => {
    // the street's buildings and their order are the town's, whatever they wear (dialogue.json and the interiors use the names)
    const dialogue = JSON.parse(readFileSync(join(__dirname, '../app/src/main/assets/dialogue.json'), 'utf8')) as { npcs: { building: string; buildingName: string }[] };
    expect(json.shops.map((e) => e.sprite)).toEqual(json.shops.map((_, i) => `b${i}`));
    for (const e of json.shops) if (e.home) expect(e.id).toBe(`home:${ts.lotOf.get(e.sprite)!.slot}`);
    // everyone the dialogue knows lives or works in the building of that sprite name, under that name
    for (const p of dialogue.npcs) expect(bySprite.get(p.building)?.name).toBe(p.buildingName);
  });

  it('is deterministic, and the committed street.json is a fresh export', () => {
    expect(JSON.stringify(townStreets(SEED, YEAR).json)).toBe(JSON.stringify(ts.json));
    const committed = JSON.parse(readFileSync(join(__dirname, '../app/src/main/assets/street.json'), 'utf8')) as unknown;
    expect(committed).toEqual(JSON.parse(JSON.stringify(ts.json)));
  });
});
