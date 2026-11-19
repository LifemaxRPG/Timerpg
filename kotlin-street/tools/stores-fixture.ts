// The stores' year-by-year fixture for the Kotlin side (desktop/src/filamentTest/resources/stores-gamma-years.json):
// gamma's reserved store sites as street.json v2 exports them (`storeSites`, `storePeople`) and, for 1900-2040, what
// the generator says each site is (src/town/stores.ts storeState: kind, state, name, sign, style, features), listed only in
// the years it changes. StoreSiteInfo.at(year) in kotlin-street must give the same (StoreKitTest), and
// src/town/stores.test.ts checks the file is current. Run: npx tsx kotlin-street/tools/stores-fixture.ts [--check]
import { readFileSync, writeFileSync } from 'node:fs';
import { planTown } from '../../src/town/plan';
import { isStoreSite, storeState, townStoreHistories } from '../../src/town/stores';
import { storeSitesBlock } from './town-stores';

export const FIXTURE = new URL('../desktop/src/filamentTest/resources/stores-gamma-years.json', import.meta.url);

export function storesFixture(seed = 'gamma'): string {
  const plan = planTown(seed);
  const block = storeSitesBlock(seed, plan.landmarks, (l) => [l.x, l.y, l.x + l.w, l.y + l.h]);
  const years: Record<string, { year: number; kind?: string; state?: string; name?: string; sign?: string; style?: string; features?: string[] }[]> = {};
  for (const h of townStoreHistories(seed, plan.landmarks.map((l) => l.id))) {
    if (!isStoreSite(h.site)) continue;
    const rows: (typeof years)[string] = [];
    let last = '';
    for (let y = 1900; y <= 2040; y++) {
      const s = storeState(seed, h, y);
      const row = s ? { year: y, kind: s.variant, state: s.status, name: s.name, sign: s.sign, style: s.style, features: s.features } : { year: y };
      const key = JSON.stringify({ ...row, year: 0 });
      if (key !== last) rows.push(row);
      last = key;
    }
    years[h.site] = rows;
  }
  return `${JSON.stringify({ seed, ...block, years }, null, 1)}\n`;
}

if (process.argv[1]?.endsWith('stores-fixture.ts')) {
  const text = storesFixture();
  if (process.argv.includes('--check')) {
    if (readFileSync(FIXTURE, 'utf8') !== text) { console.error('stores fixture is stale: npx tsx kotlin-street/tools/stores-fixture.ts'); process.exit(1); }
  } else { writeFileSync(FIXTURE, text); console.log(`wrote ${FIXTURE.pathname} (${text.length} bytes)`); }
}
