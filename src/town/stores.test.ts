import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { isAlive, runTimeline, worldAt } from '../sim';
import { brief, storeFacts, validate } from '../llm/factsheet';
import type { TalkCtx } from '../game/dialogue';
import { layoutTown } from './layout';
import { landmarkArtKey } from './landmarks';
import { planTown, type Rect } from './plan';
import { firstYearReaching, populationIn } from './population';
import {
  EXPAND_FROM, generalStoreName, STORE_FEATURES, STORE_NAMES, isStoreSite, MARKET_POPULATION, reuseOf, STORE_SITES, storeHistories, storeLayout, storeNeeds,
  storeState, STORE_SIZES, SUPERMARKET_POPULATION, supermarketName, superstoreName, SUPERSTORE_FROM, SUPERSTORE_POPULATION,
  townStoreHistories, ALONG_M, DEEP_M, PACK_HARDWARE, PACK_LOOKS, PACK_MALL, MALL_POPULATION, MALL_YEARS, type StoreSite,
} from './stores';

const SEEDS = ['alpha', 'gamma', 'eta', 'orbit', 'quartz', 'delta', 'maple', 'nova', 'zebra', 'water3', 'water9', 'water14', 'water29', 'water32'];
const overlap = (a: Rect, b: Rect, pad = 0): boolean =>
  a.x < b.x + b.w + pad && b.x - pad < a.x + a.w && a.y < b.y + b.h + pad && b.y - pad < a.y + a.h;
const sitesOf = (seed: string) => planTown(seed).landmarks.filter((l) => isStoreSite(l.id));
const historiesOf = (seed: string) => townStoreHistories(seed, planTown(seed).landmarks.map((l) => l.id));

describe('stores: which towns, and where', () => {
  it('is deterministic', () => {
    expect(JSON.stringify(sitesOf('gamma'))).toBe(JSON.stringify(sitesOf('gamma')));
    const placed = new Set<StoreSite>(STORE_SITES);
    expect(JSON.stringify(storeHistories('gamma', placed))).toBe(JSON.stringify(storeHistories('gamma', placed)));
    expect(storeNeeds('quartz')).toEqual(storeNeeds('quartz'));
  });

  it('opens each store when the town\'s population reaches its threshold', () => {
    for (const seed of SEEDS) {
      const n = storeNeeds(seed);
      const first = firstYearReaching(seed, MARKET_POPULATION);
      if (n.market !== undefined) { expect(n.market).toBe(first); expect(n.market).toBeLessThan(EXPAND_FROM); }
      if (n.supermarket !== undefined) {
        expect(n.supermarket, seed).toBeGreaterThanOrEqual(EXPAND_FROM);
        expect(populationIn(seed, n.supermarket)).toBeGreaterThanOrEqual(n.market === undefined ? MARKET_POPULATION : SUPERMARKET_POPULATION);
      }
      if (n.superstore !== undefined) {
        expect(n.superstore).toBeGreaterThanOrEqual(SUPERSTORE_FROM);
        expect(populationIn(seed, n.superstore)).toBeGreaterThanOrEqual(SUPERSTORE_POPULATION);
        expect(populationIn(seed, n.superstore - 1) < SUPERSTORE_POPULATION || n.superstore === SUPERSTORE_FROM).toBe(true);
      }
    }
    // orbit never grows past a general store; gamma gets all four
    expect(storeNeeds('orbit')).toEqual({});
    expect(sitesOf('orbit').map((l) => l.id)).toEqual(['generalstore']);
    expect(sitesOf('gamma').map((l) => l.id).sort()).toEqual(['generalstore', 'market', 'supermarket', 'superstore']);
  });

  it('puts the general store on the main street and the market downtown, inside the streets', () => {
    for (const seed of SEEDS) {
      const p = planTown(seed);
      const g = p.landmarks.find((l) => l.id === 'generalstore');
      expect(g, seed).toBeDefined();
      const fronts = p.roads.filter((r) => r.horizontal && (r.y === g!.y + g!.h + 1 || r.y + r.h + 1 === g!.y) && r.x <= g!.x && g!.x + g!.w <= r.x + r.w);
      expect(fronts.some((r) => r.main), seed).toBe(true);
      expect(STORE_SIZES.generalstore).toContainEqual([g!.w, g!.h]);
    }
  });

  it('puts the supermarket and the superstore by the main road beyond the east end of town, at big-box scale', () => {
    let superstores = 0;
    for (const seed of SEEDS) {
      const p = planTown(seed);
      const main = p.roads.filter((r) => r.main && r.horizontal).reduce((a, b) => (b.x + b.w > a.x + a.w ? b : a));
      const town = Math.max(...p.candidates.map((c) => c.x + c.w));
      for (const l of p.landmarks.filter((x) => x.id === 'supermarket' || x.id === 'superstore')) {
        // its front on the main street's last segment, run on out of town past it
        expect(main.y === l.y + l.h + 1 || main.y + main.h + 1 === l.y, `${seed} ${l.id} front`).toBe(true);
        expect(l.x + l.w, seed).toBeLessThanOrEqual(main.x + main.w);
        expect(l.x, `${seed} ${l.id} beyond the town's plots`).toBeGreaterThanOrEqual(Math.min(town, main.x));
        if (l.id === 'superstore') superstores++;
      }
    }
    expect(superstores).toBeGreaterThanOrEqual(5);
    // ~160 x 205 m: a big box of ~118 x 95 m with its car park, garden centre and service yard
    const [[w, h]] = STORE_SIZES.superstore as [[number, number]];
    expect(w * ALONG_M).toBeGreaterThan(150);
    expect(h * DEEP_M).toBeGreaterThan(195);
    const s = storeLayout('superstore', w, h);
    expect((s.building[2] - s.building[0]) * (s.building[3] - s.building[1])).toBeGreaterThan(10000);
    expect(s.bays).toBeGreaterThan(500);
  });

  it('never overlaps a road (or its pavement), the plaza, a plot, a landmark or water', async () => {
    const { hitsWater } = await import('./water');
    for (const seed of SEEDS) {
      const p = planTown(seed);
      for (const l of p.landmarks.filter((x) => isStoreSite(x.id))) {
        expect(l.x >= 0 && l.y >= 0 && l.x + l.w <= p.width && l.y + l.h <= p.height, `${seed} ${l.id} on the map`).toBe(true);
        for (const r of p.roads) expect(overlap(l, r, 1), `${seed} ${l.id} on a road`).toBe(false);
        expect(overlap(l, p.plaza), `${seed} ${l.id} in the plaza`).toBe(false);
        for (const c of p.candidates) expect(overlap(l, c), `${seed} ${l.id} vs ${c.id}`).toBe(false);
        for (const o of p.landmarks) if (o !== l) expect(overlap(l, o), `${seed} ${l.id} vs ${o.id}`).toBe(false);
        expect(hitsWater(p.water, l, 1), `${seed} ${l.id} in water`).toBe(false);
      }
    }
  });

  it('lays each era\'s building, car park and yards inside its plot, clear of each other', () => {
    for (const site of STORE_SITES) for (const [w, h] of STORE_SIZES[site]) {
      const variant = site === 'generalstore' ? 'general' : site;
      const s = storeLayout(variant, w, h);
      const inside = (r: [number, number, number, number]): boolean => r[0] >= 0 && r[1] >= 0 && r[2] <= s.width + 0.1 && r[3] <= s.depth + 0.1 && r[0] < r[2] && r[1] < r[3];
      const parts = (['building', 'porch', 'sideYard', 'backyard', 'carPark', 'serviceYard', 'gardenCentre'] as const).map((k) => s[k]).filter((r): r is [number, number, number, number] => !!r);
      for (const r of [...parts, ...(s.shed ? [s.shed] : []), ...(s.docks ? [s.docks] : [])]) expect(inside(r), `${site} ${w}x${h} ${r}`).toBe(true);
      for (let i = 0; i < parts.length; i++) for (let j = i + 1; j < parts.length; j++) {
        const [a, b] = [parts[i]!, parts[j]!];
        expect(a[0] < b[2] - 0.01 && b[0] < a[2] - 0.01 && a[1] < b[3] - 0.01 && b[1] < a[3] - 0.01, `${site} parts ${i} ${j} overlap`).toBe(false);
      }
      if (s.carPark) expect(s.carPark[3] - s.carPark[1], `${site} car park depth`).toBeGreaterThanOrEqual(15);
    }
  });
});

describe('stores: their history', () => {
  it('is consistent: opened first, then renamed, closed, reused, in order, and only what the town can see', () => {
    for (const seed of SEEDS) {
      const hs = historiesOf(seed);
      const n = storeNeeds(seed);
      const by = (s: StoreSite) => hs.find((h) => h.site === s);
      for (const h of hs) {
        const ev = h.events;
        expect(['opened', 'moved']).toContain(ev[0]!.event);
        for (let i = 1; i < ev.length; i++) expect(ev[i]!.year, `${seed} ${h.site}`).toBeGreaterThanOrEqual(ev[i - 1]!.year);
        const closed = ev.findIndex((e) => e.event === 'closed');
        const reused = ev.findIndex((e) => e.event === 'reused');
        if (reused >= 0) { expect(closed).toBeGreaterThanOrEqual(0); expect(reused).toBe(closed + 1); expect(ev[reused]!.year).toBeGreaterThan(ev[closed]!.year); }
        expect(ev.filter((e) => e.event === 'opened' || e.event === 'moved').every((e) => e.year <= 2025)).toBe(true);
      }
      const ss = by('superstore'), gs = by('generalstore')!, mk = by('market'), sm = by('supermarket');
      const S = ss?.events[0]!.year;
      // the general store closes 2-10 years after the town's first supermarket (sometimes), else soon after the superstore
      const gClosed = gs.events.find((e) => e.event === 'closed');
      const firstMarket = Math.min(...[mk, sm].filter((x) => !!x).map((x) => x!.events[0]!.year));
      if (gClosed?.why === 'supermarket') {
        expect(gClosed.year - firstMarket, seed).toBeGreaterThanOrEqual(2); expect(gClosed.year - firstMarket).toBeLessThanOrEqual(10);
        if (S !== undefined) expect(gClosed.year).toBeLessThan(S + 2);
      } else if (S === undefined) expect(gClosed, seed).toBeUndefined();
      else { expect(gClosed!.year - S).toBeGreaterThanOrEqual(0); expect(gClosed!.year - S).toBeLessThanOrEqual(2); }
      // a 1990s trade only for one still trading with no superstore in town
      for (const e of gs.events.filter((x) => x.trade)) { expect(S, seed).toBeUndefined(); expect(e.year).toBeGreaterThanOrEqual(1988); }
      // a superstore before 2005 opens as the discount store, and any extension comes from 2005
      if (ss) {
        expect(!!ss.events[0]!.discount).toBe(S! < 2005);
        for (const e of ss.events.filter((x) => x.event === 'expanded')) { expect(ss.events[0]!.discount).toBe(true); expect(e.year).toBeGreaterThanOrEqual(2005); }
      }
      // the market closes the year its business moves out to the supermarket, else after the superstore
      if (mk && sm) {
        expect(sm.events[0]!.event).toBe('moved');
        expect(mk.events.find((e) => e.event === 'closed')!.year).toBe(sm.events[0]!.year);
        expect(mk.events.find((e) => e.event === 'closed')!.why).toBe('moved');
      }
      if (sm && S !== undefined) expect(sm.events.find((e) => e.event === 'closed')!.year).toBeGreaterThan(S);
      if (n.superstore !== undefined && ss) expect(S).toBe(n.superstore);
    }
  });

  it('shows the year\'s building and state in the layout (gamma through the decades)', () => {
    const t = runTimeline({ seed: 'gamma', endYear: 2025 });
    const at = (y: number) => layoutTown(worldAt(t, y)).landmarks.filter((l) => isStoreSite(l.id))
      .map((l) => `${l.id}:${landmarkArtKey(l)}:${l.store!.status}`);
    expect(at(1955)).toEqual(['generalstore:landmark_store_general:open']);
    expect(at(1975)).toEqual(['generalstore:landmark_store_general:open', 'market:landmark_store_market:open']);
    expect(at(1995)).toEqual(['generalstore:landmark_store_general:open', 'market:landmark_store_market:reused', 'supermarket:landmark_store_supermarket:open']);
    expect(at(2025)).toEqual(['generalstore:landmark_store_general:reused', 'market:landmark_store_market:reused',
      'supermarket:landmark_store_supermarket:closed', 'superstore:landmark_store_superstore:open']);
    // a reused store wears its new use's sign
    const g = layoutTown(worldAt(t, 2025)).landmarks.find((l) => l.id === 'generalstore')!;
    expect(g.name).toBe(g.store!.sign);
    expect(g.store!.sign).not.toBe(g.store!.name);
    // the gas pump stands out front in the early decades only
    expect(storeState('gamma', historiesOf('gamma')[0]!, 1955)!.features).toContain('gasPump');
    expect(storeState('gamma', historiesOf('gamma')[0]!, 1985)!.features).not.toContain('gasPump');
    expect(storeState('gamma', historiesOf('gamma')[0]!, 1900)).toBeUndefined();
  });
});

describe('stores: across the seeds', () => {
  const ALL = [...SEEDS, ...Array.from({ length: 40 }, (_, i) => `water${i}`)];
  it('closes some general stores when the supermarket comes, keeps the superstore path the commonest, and turns some survivors', () => {
    let bySupermarket = 0, bySuperstore = 0, turned = 0, discount = 0;
    let early = 0;
    for (const seed of ALL) for (const h of historiesOf(seed)) {
      const c = h.events.find((e) => e.event === 'closed');
      const big = historiesOf(seed).some((x) => x.site === 'superstore');
      if (h.site === 'generalstore' && c?.why === 'supermarket') { early++; if (big) bySupermarket++; }
      if (h.site === 'generalstore' && c?.why === 'superstore') bySuperstore++;
      if (h.events.some((e) => e.trade)) turned++;
      if (h.site === 'superstore' && h.events[0]!.discount) discount++;
    }
    // some general stores go when the supermarket comes; where a superstore comes, most hang on until it does
    expect(early).toBeGreaterThan(5);
    expect(bySuperstore, `${bySuperstore} ${bySupermarket}`).toBeGreaterThan(bySupermarket);
    expect(turned).toBeGreaterThan(3);
    expect(discount).toBeGreaterThan(2);
  });

  it('emits only the features the Kotlin builder knows (StoreKit FEATURES)', async () => {
    const { readFileSync } = await import('node:fs');
    const kt = readFileSync(new URL('../../kotlin-street/shared/src/filament/kotlin/scene3d/StoreKit.kt', import.meta.url), 'utf8');
    const m = /val FEATURES\s*=\s*setOf\(([^)]*)\)/.exec(kt);
    expect(m, 'StoreKit.FEATURES').toBeTruthy();
    const known = new Set([...m![1]!.matchAll(/"([A-Za-z]+)"/g)].map((x) => x[1]!));
    expect([...STORE_FEATURES].filter((f) => !known.has(f))).toEqual([]);
    for (const seed of ALL) for (const h of historiesOf(seed)) for (let y = 1900; y <= 2040; y += 3) {
      for (const f of storeState(seed, h, y)?.features ?? []) expect(f.startsWith('reuse:') || (STORE_FEATURES as readonly string[]).includes(f), f).toBe(true);
    }
  });

  it('keeps the Kotlin fixture of gamma through the years current (StoreSiteInfo.at parity)', async () => {
    const { readFileSync } = await import('node:fs');
    const { storesFixture, FIXTURE } = await import('../../kotlin-street/tools/stores-fixture');
    expect(readFileSync(FIXTURE, 'utf8') === storesFixture(), 'npx tsx kotlin-street/tools/stores-fixture.ts').toBe(true);
  });

  it('the Kotlin preview names come from these lists', async () => {
    const { readFileSync } = await import('node:fs');
    const kt = readFileSync(new URL('../../kotlin-street/shared/src/filament/kotlin/scene3d/StoreSigns.kt', import.meta.url), 'utf8');
    const list = (name: string): string[] => [...(new RegExp(`val ${name} = listOf\\(([^)]*)\\)`).exec(kt)?.[1] ?? '').matchAll(/"([^"]+)"/g)].map((x) => x[1]!);
    expect(list('BIG').length).toBeGreaterThan(4);
    for (const n of list('BIG')) expect(STORE_NAMES.superstores, n).toContain(n);
    for (const n of list('SUPER')) expect(STORE_NAMES.chains70s, n).toContain(n);
    for (const n of list('MARKET')) expect(STORE_NAMES.marketWords, n).toContain(n);
  });
});

describe('stores: names', () => {
  // Real grocery, supermarket, variety and big-box chains (past and present, here and abroad): none may appear.
  const REAL = ['Walmart', 'Wal-Mart', 'Wal Mart', 'Kroger', 'Safeway', 'Piggly Wiggly', 'Piggly', 'A&P', 'A & P', 'Great Atlantic', 'Target',
    'Costco', "Sam's Club", 'Sams Club', 'Kmart', 'K-Mart', 'Kresge', 'Woolworth', 'Meijer', 'Albertsons', 'Publix', 'Winn-Dixie', 'Winn Dixie',
    'Food Lion', 'Wegmans', 'H-E-B', 'HEB', 'Hy-Vee', 'Giant Eagle', 'Giant Food', 'Stop & Shop', 'ShopRite', 'Shop Rite', 'Ralphs', 'Vons',
    'Jewel-Osco', 'Jewel Food', 'Acme Markets', 'Pathmark', 'Grand Union', 'Red Owl', 'National Tea', 'Bi-Lo', 'BI-LO', 'Ingles', 'Harris Teeter',
    'Food Fair', 'Big Bear', 'King Kullen', 'Save-A-Lot', 'Save A Lot', 'Aldi', 'Lidl', "Trader Joe", 'Whole Foods', 'Sprouts', 'IGA',
    'Independent Grocers', 'Fred Meyer', 'Zayre', 'Ames', 'Bradlees', 'Caldor', "Gibson's", 'Shopko', 'Pamida', 'Dollar General', 'Family Dollar',
    'Dollar Tree', 'Ben Franklin', 'Tesco', 'Sainsbury', 'Asda', 'Morrisons', 'Carrefour', 'Loblaws', 'Sobeys', 'Price Chopper', 'Hannaford',
    "Shaw's", 'Star Market', 'Market Basket', 'Lucky Stores', 'Food 4 Less', 'WinCo', 'Stater Bros', 'Schnucks', 'Dierbergs', 'Thriftway',
    'Super Valu', 'SuperValu', 'Sav-A-Center', 'Sears', 'Montgomery Ward', 'Penney', 'Spar', 'Food City', 'Tops Markets', 'Weis', 'Big Y',
    'Cub Foods', 'Fareway', 'Dillons', 'King Soopers', 'Alpha Beta', 'Skaggs', "Dominick's", 'Eagle Food', 'Clover Farm', 'Gold Circle',
    'Two Guys', 'Korvettes', 'W. T. Grant', 'McCrory', 'Newberry', 'Big Lots', 'Fedco', 'Gemco', 'FedMart', 'Value City', 'Supercenter',
    'Hypermart', 'Kwik', 'Foodland', 'Foodtown', 'Food Town', 'Key Food', 'Pantry Pride', 'Shopwell', 'Save Mart', 'Valu-Mart', 'ValuMart',
    'Mega Mart', 'Harvest Foods', 'Fairway', 'Country Mart', 'Furr', 'Kash n', 'Bargain Barn', 'Good Neighbor', 'Bi-Mart', 'Hills Department',
    // too close to a real chain's name or trade-name: Thrifty Foods, the Depot stores, the Supercenter / Neighborhood Market formats
    'Thrifton', 'Thrifty', 'Depot', 'Supercentre', 'Neighborhood Market', 'Superstore', 'Hypermarket', 'Save Money'];
  const hit = (name: string): string | undefined => REAL.find((r) => new RegExp(`(^|[^a-z])${r.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}($|[^a-z])`).test(name.toLowerCase()));

  it('are all made up: no real chain, ever', () => {
    const names = new Set<string>();
    for (let i = 0; i < 400; i++) {
      const seed = `name-${i}`;
      for (const n of [0, 1]) names.add(generalStoreName(seed, n));
      names.add(supermarketName(seed, false)); names.add(supermarketName(seed, true)); names.add(superstoreName(seed));
      for (const site of STORE_SITES) names.add(reuseOf(seed, site).name);
    }
    for (const seed of SEEDS) for (const h of historiesOf(seed)) for (const e of h.events) names.add(e.name);
    expect(names.size).toBeGreaterThan(150);
    for (const n of names) expect(hit(n), n).toBeUndefined();
  });

  it('the blocklist catches the real thing', () => {
    expect(hit('Walmart Supercenter')).toBe('Walmart');
    expect(hit('Piggly Wiggly')).toBeDefined();
    expect(hit('The A&P')).toBe('A&P');
    expect(hit('Bountiway')).toBeUndefined();
  });
});

describe('stores: what the townspeople know', () => {
  const t = runTimeline({ seed: 'gamma', endYear: 2018 });
  const ctxAt = (year: number): TalkCtx[] => {
    const w = worldAt(t, year);
    return Object.values(w.people).filter(isAlive).map((person) => ({ w, person, job: 'works at Bakery', townName: 'Testville', familiarity: 1, witnesses: 0, wanted: false, journal: [] }));
  };

  it('know where they shop, the new superstore, and the old store that closed', () => {
    const ss = historiesOf('gamma').find((h) => h.site === 'superstore')!.events[0]!;
    const people = ctxAt(2017);
    const facts = people.map(storeFacts);
    expect(facts.every((f) => f.length >= 1 && f.length <= 2)).toBe(true);
    // the news: the superstore came, and took the old stores' trade (2017: the supermarket closed after it in 2016)
    expect(facts.some((f) => f[1]?.includes(ss.name))).toBe(true);
    expect(ctxAt(ss.year + 1).map(storeFacts).some((f) => f.some((s) => s.includes(`${ss.name}, a huge superstore`)))).toBe(true);
    expect(facts.some((f) => f[0]!.includes(ss.name))).toBe(true);
    // in the town's news and "about the town", never in family talk; a reply that names the store passes the name check
    const b = brief(people[0]!, 'news', 'x');
    expect(b.system).toContain(ss.name);
    expect(brief(people[0]!, 'family', 'x').system).not.toContain(ss.name);
    expect(validate(`Have you been out to ${ss.name} yet?`, b)).toBeTruthy();
    // in 1955 there is only the general store
    const g = historiesOf('gamma').find((h) => h.site === 'generalstore')!.events[0]!.name;
    expect(ctxAt(1955).map(storeFacts).every((f) => f[0]!.includes(g))).toBe(true);
  });
});

describe('stores: the pack\'s own buildings as variants', () => {
  const ALL = [...SEEDS, ...Array.from({ length: 40 }, (_, i) => `water${i}`)];
  const all = ALL.flatMap((seed) => historiesOf(seed).map((h) => ({ seed, h })));

  it('only uses looks the Kotlin builder has, and each where it makes era sense', () => {
    let general = 0, hardware = 0, market = 0, mall = 0;
    for (const { seed, h } of all) {
      if (h.site === 'generalstore') {
        // the original building is never a pack front (general stores are built 1885-1935); a sale from 1952 may re-fit it
        expect(h.style === 'frontier' || h.style === 'brick', `${seed} general`).toBe(true);
        for (const e of h.events) if (e.look) {
          expect(PACK_LOOKS, `${seed} ${e.look}`).toContain(e.look);
          expect(e.event).toBe('renamed'); expect(e.year).toBeGreaterThanOrEqual(1952); expect(e.year).toBeLessThanOrEqual(1985);
          if (e.look === PACK_HARDWARE) { hardware++; expect(e.year).toBeGreaterThanOrEqual(1958); } else { expect(e.look.startsWith('pack:store_s_')).toBe(true); general++; }
        }
      } else if (h.site === 'market') {
        if (h.style !== 'modern') { market++; expect(PACK_LOOKS).toContain(h.style); expect(h.style.startsWith('pack:market_')).toBe(true); }
      } else if (h.site === 'supermarket') {
        if (h.style !== 'modern') {
          mall++;
          const year = h.events[0]!.year;
          expect(h.style).toBe(PACK_MALL); expect(year).toBeGreaterThanOrEqual(1970); expect(year).toBeLessThan(1990);
          expect(firstYearReaching(seed, MALL_POPULATION), seed).toBeLessThanOrEqual(year + MALL_YEARS);
        }
      } else expect(h.style).toBe('modern');
    }
    // (counts over the 54 known seeds: each variety does turn up, none takes over)
    expect(general).toBeGreaterThan(3); expect(hardware).toBeGreaterThan(0); expect(market).toBeGreaterThan(8); expect(mall).toBeGreaterThan(2);
    expect(market).toBeLessThan(all.filter((x) => x.h.site === 'market').length);
  });

  it('names exactly the pictures the Kotlin builder packs (PackStoreData.kt)', () => {
    const kt = readFileSync(new URL('../../kotlin-street/shared/src/filament/kotlin/scene3d/PackStoreData.kt', import.meta.url), 'utf8');
    const ids = [...kt.matchAll(/Sprite\("([a-z_0-9]+)"/g)].map((m) => `pack:${m[1]}`);
    expect([...ids].sort()).toEqual([...PACK_LOOKS].sort());
  });

  it('shows the re-fit from the year of the sale, in the year\'s state', () => {
    let seen = 0;
    for (const { seed, h } of all) {
      const e = h.events.find((x) => x.look);
      if (!e) continue;
      seen++;
      expect(storeState(seed, h, e.year - 1)?.style).toBe(h.style);
      expect(storeState(seed, h, e.year)?.style).toBe(e.look);
      expect(storeState(seed, h, 2040)?.style).toBe(e.look);
    }
    expect(seen).toBeGreaterThan(3);
  });
});
