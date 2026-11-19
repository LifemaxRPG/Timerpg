// The shared building list (tools/town-buildings.ts): the v1 exports it feeds are byte for byte what they were before it
// was split out of tools/town-streets.ts, and landmarks are named as the game draws them.
import { describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { townStreets } from './town-streets';
import { makeBuildings } from './town-buildings';
import { landmarkArtKey } from '../../src/town/landmarks';
import { pickVariant, type Manifest } from '../../src/game/art';

const ASSETS = join(__dirname, '../app/src/main/assets');
const ROOT = join(__dirname, '../..');
const sha = (s: string): string => createHash('sha256').update(s).digest('hex');
/** What tools/export-street.ts prints (console.log adds the newline). */
const streetJson = (seed: string): string => `${JSON.stringify(townStreets(seed, 2025).json, null, 2)}\n`;

describe('town buildings', () => {
  it('leaves the committed street.json (eta) byte-identical', () => {
    expect(streetJson('eta')).toBe(readFileSync(join(ASSETS, 'street.json'), 'utf8'));
  });

  it('leaves the street.json of other towns byte-identical (recorded before the split)', () => {
    const before: Record<string, string> = {
      vale: '9c2d74fb1af31b1aeebf3a432fcff696a0a22d3ca2c8d7ba2655b5bbfd5a09d3',
      // alpha and gamma re-recorded when towns big enough got a cinema (src/town/cinema.ts): its site takes a few plots
      alpha: 'f2497c39269aa8db58c1e11f96ab36aace03912d42e2bffa909d3ba0bdfa6362',
      gamma: '0b98415abe93b1b6caaef815547a65dfd53585fd326ac7495da30f0541180988',
    };
    for (const [seed, h] of Object.entries(before)) expect(sha(streetJson(seed)), seed).toBe(h);
  });

  it('leaves the committed dialogue.json (eta) byte-identical', () => {
    const out = execFileSync('npx', ['tsx', 'kotlin-street/tools/export-dialogue.ts', 'eta', '2025'], { cwd: ROOT, encoding: 'utf8', maxBuffer: 1 << 26 });
    expect(out).toBe(readFileSync(join(ASSETS, 'dialogue.json'), 'utf8'));
  }, 60_000);

  it('names buildings b0, b1, ... once per lot, in the order they are first asked for', () => {
    const { w, layout } = townStreets('eta', 2025);
    const b = makeBuildings(w, layout, 2025);
    const lots = layout.lots.slice(0, 5);
    expect(lots.map(b.entry)).toEqual(['b0', 'b1', 'b2', 'b3', 'b4']);
    expect(b.entry(lots[2]!)).toBe('b2');
    expect(b.entries).toHaveLength(5);
    expect(b.lotOf.get('b3')).toBe(lots[3]);
  });

  it('names each landmark\'s sprite as the game draws it (TownView.drawLandmark)', () => {
    const manifest = JSON.parse(readFileSync(join(ROOT, 'public/assets/manifest.json'), 'utf8')) as Manifest;
    let n = 0;
    for (const seed of ['eta', 'gamma', 'vale', 'alpha']) {
      for (const l of townStreets(seed, 2025).layout.landmarks) {
        // the cinema has no sprite (the engine builds it): its key names the era's front
        if (l.id === 'cinema') { expect(landmarkArtKey(l)).toBe('landmark_cinema_multiplex'); continue; }
        expect(landmarkArtKey(l)).toBe(pickVariant(manifest[`landmark:${l.id}`]!, l.look).key);
        n++;
      }
    }
    expect(n).toBeGreaterThan(10);
    // every variant of every kind, across the look range
    for (const [group, items] of Object.entries(manifest)) {
      if (!group.startsWith('landmark:')) continue;
      const id = group.slice(9) as Parameters<typeof landmarkArtKey>[0]['id'];
      for (let look = 0; look < 1; look += 0.01) expect(landmarkArtKey({ id, look })).toBe(pickVariant(items, look).key);
    }
  });
});
