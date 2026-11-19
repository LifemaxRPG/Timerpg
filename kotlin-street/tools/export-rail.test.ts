import { readFileSync } from 'fs';
import { describe, expect, it } from 'vitest';
import { railJson } from './export-rail';

// The app ships the town's railroad (assets/town/gamma/rail.json, docs/RAIL.md): it must be what the game's own rail
// modules make for the town's world now (re-export with: npx tsx kotlin-street/tools/export-rail.ts <asset> --checks
// kotlin-street/desktop/src/test/resources/rail-checks.json).
describe('rail.json', () => {
  it('is current', () => {
    const shipped = JSON.parse(readFileSync(new URL('../app/src/main/assets/town/gamma/rail.json', import.meta.url), 'utf8'));
    expect(JSON.parse(JSON.stringify(railJson()))).toEqual(shipped);
  }, 120_000);
});
