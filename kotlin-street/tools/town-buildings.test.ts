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
      // re-recorded when towns big enough got a cinema (src/town/cinema.ts), and again when every town got its stores
      // (src/town/stores.ts: the general store and the market take main-street plots, the plan grows for the rest), and again
      // for the planned town (src/town/plat.ts: the crossroads are Main Street and the civic avenue of the platted town), and
      // again for the downtown that straddles its river, the town square and the core's side-street shops, and again for the capital's
      // intake models and grounds column (gamma only: the plan is 200 tiles wider), and again for the third intake (a third south block of grounds, the rail column's far end made one block, the elevator's siding)
      vale: 'b2ed05d85e9adc16376257fd9ef8c12cd0e132958ebafaf1581bf9a07ecb4b30',
      alpha: 'cb6a1db217a8e14bac76e89373be662fbfe76b3817a1500a145554e437436cdb',
      gamma: '756af4c20d4e946442bcecd1aadf0a03007b9121b62899f600ef7503a98b79fd',
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
        // nor do the stores: the key names the building of the year
        if (l.store) { expect(landmarkArtKey(l)).toBe(`landmark_store_${l.store.variant}`); continue; }
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
