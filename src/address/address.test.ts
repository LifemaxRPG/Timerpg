import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim/simulate';
import { layoutTown } from '../town/layout';
import { planTown, setPlanVersion } from '../town/plan';
import { CAPITAL_SEED, isCountySeat } from '../town/plat';
import { PEOPLE, SIZE, type TownRecipe } from '../town/recipe';
import {
  POSTAL_CODE_INTRODUCED_YEAR,
  POSTAL_PLUS4_INTRODUCED_YEAR,
  addressOf,
  allAddresses,
  buildCapitalAddresses,
  buildDirectory,
  buildDoveBeachAddresses,
  buildTownAddresses,
  buildTownAddressesFromRecipe,
  createAddressBridge,
  find,
  formatDirectory,
  handleAddressJson,
  nearestAddress,
  parseAddressBook,
  postalEraInfo,
  serializeAddressBook,
  streetNameIn,
  type TownAddressBook,
} from './index';

function assertBookInvariants(book: TownAddressBook, years: readonly number[]): void {
  // Unique street names in every tested year
  for (const year of years) {
    const names = book.streets.map((s) => streetNameIn(s, year).toLowerCase());
    expect(new Set(names).size, `${book.townId} unique street names in ${year}`).toBe(names.length);
  }

  for (const year of years) {
    const addrs = allAddresses(book, year);
    expect(addrs.length, `${book.townId} has addresses in ${year}`).toBeGreaterThan(0);

    // No two standing buildings share an address in the same year
    const seen = new Set<string>();
    for (const a of addrs) {
      const key = `${a.streetId}|${a.houseNumber}`;
      expect(seen.has(key), `duplicate address ${a.line1} in ${book.townId} (${year})`).toBe(false);
      seen.add(key);

      // Odd on left, even on right
      expect(a.number, `positive number for ${a.buildingId}`).toBeGreaterThan(0);
      if (a.parity === 'odd') expect(a.number % 2, `odd parity for ${a.line1}`).toBe(1);
      else expect(a.number % 2, `even parity for ${a.line1}`).toBe(0);

      // Postal code era check
      if (year < POSTAL_CODE_INTRODUCED_YEAR) {
        expect(a.postalCode, `no postal code in ${year}`).toBeNull();
        expect(a.postalCodePlus4, `no ZIP+4 in ${year}`).toBeNull();
      } else {
        expect(a.postalCode, `5-digit postal code in ${year}`).toMatch(/^\d{5}$/);
        if (year < POSTAL_PLUS4_INTRODUCED_YEAR) {
          expect(a.postalCodePlus4, `no ZIP+4 in ${year}`).toBeNull();
        } else {
          expect(a.postalCodePlus4, `ZIP+4 in ${year}`).toMatch(/^\d{5}-\d{4}$/);
        }
      }
    }

    // Rising numbers along every street & side
    const bySide = new Map<string, typeof book.buildings>();
    for (const b of book.buildings) {
      if (year < b.fromYear || (b.toYear !== undefined && year >= b.toYear)) continue;
      const k = `${b.streetId}|${b.parity}`;
      const arr = bySide.get(k) ?? [];
      arr.push(b);
      bySide.set(k, arr);
    }
    for (const [k, list] of bySide) {
      list.sort((a, b) => a.along - b.along || a.number - b.number);
      for (let i = 1; i < list.length; i++) {
        const prev = list[i - 1]!;
        const cur = list[i]!;
        expect(cur.number, `rising numbers on ${k} (${year}): ${prev.houseNumber} -> ${cur.houseNumber}`).toBeGreaterThanOrEqual(prev.number);
        if (cur.along - prev.along > 0.75) {
          expect(cur.number, `strictly rising on ${k} (${year})`).toBeGreaterThan(prev.number);
        }
      }
    }
  }
}

describe('src/address: real addresses for every building', () => {
  const capital = buildCapitalAddresses();
  const alpha = buildTownAddresses('alpha');
  const countySeat = buildTownAddresses('rho');
  const doveBeach = buildDoveBeachAddresses();

  const hamletRecipe: TownRecipe = {
    seed: 'g025_hamlet_seed',
    place: 'h:g025-1',
    name: 'Pine Crossing',
    rank: 'hamlet',
    size: SIZE.hamlet,
    type: 'farm',
    water: 'none',
    rail: false,
    countySeat: false,
    founded: 1872,
    people: PEOPLE.hamlet,
  };
  const riverTownRecipe: TownRecipe = {
    seed: 'g025_river_seed',
    place: 't:g025-2',
    name: 'Alderford',
    rank: 'major',
    size: SIZE.major,
    type: 'mill',
    water: 'river',
    rail: true,
    countySeat: true,
    founded: 1848,
    people: PEOPLE.major,
    bypassed: { year: 1966, by: 'hw:1' },
    interchange: { year: 1966, km: 2 },
  };
  const hamlet = buildTownAddressesFromRecipe(hamletRecipe);
  const riverTown = buildTownAddressesFromRecipe(riverTownRecipe);

  it('gives every standing building in the capital, generated towns, baked recipes, and Dove Beach a unique address per year', () => {
    const years = [1900, 1935, 1952, 1963, 1975, 1995, 2025];
    assertBookInvariants(capital, years);
    assertBookInvariants(alpha, years);
    assertBookInvariants(countySeat, years);
    assertBookInvariants(hamlet, years);
    assertBookInvariants(riverTown, years);
    assertBookInvariants(doveBeach, [1850, 1920, 1935, 1962, 1963, 1975, 2025]);
  });

  it('covers 100% of plan candidates, landmarks, and layoutTown lots with no missing buildings', () => {
    const tl = runTimeline({ seed: CAPITAL_SEED, startYear: 1950, endYear: 1965 });
    const w1952 = worldAt(tl, 1952);
    const layout = layoutTown(w1952);
    const plan = planTown(CAPITAL_SEED);

    for (const lot of [...layout.lots, ...layout.vacantLots, ...layout.fillers]) {
      const addr = addressOf(capital, lot.slot, 1952, { world: w1952 });
      expect(addr, `lot ${lot.slot} must have an address in 1952`).not.toBeNull();
    }
    for (const lm of layout.landmarks) {
      const a1 = addressOf(capital, `landmark:${lm.id}`, 1952, { world: w1952 });
      const a2 = addressOf(capital, lm.id, 1952, { world: w1952 });
      expect(a1, `landmark ${lm.id} must have an address`).not.toBeNull();
      expect(a2).toEqual(a1);
    }
    for (const farm of layout.farms.farms) {
      expect(addressOf(capital, farm.id, 1952, { world: w1952 }), `farm ${farm.id}`).not.toBeNull();
    }
    // All plan candidates exist in the address book
    const bookIds = new Set(capital.buildings.map((b) => b.id));
    for (const c of plan.candidates) expect(bookIds.has(c.id)).toBe(true);
  });

  it('keeps house numbers stable across eras for buildings that stand and leaves gaps for empty lots', () => {
    const standing1952 = allAddresses(capital, 1952);
    const map1952 = new Map(standing1952.map((a) => [a.buildingId, a]));
    const standing2025 = allAddresses(capital, 2025);

    let compared = 0;
    for (const a2025 of standing2025) {
      const a1952 = map1952.get(a2025.buildingId);
      if (!a1952) continue;
      compared++;
      expect(a2025.streetId).toBe(a1952.streetId);
      expect(a2025.number).toBe(a1952.number);
      expect(a2025.houseNumber).toBe(a1952.houseNumber);
      expect(a2025.parity).toBe(a1952.parity);
    }
    expect(compared).toBeGreaterThan(50);

    // Check realistic gaps between buildings on streets (empty lots leave numeric gaps > 2)
    let gapCount = 0;
    const bySide = new Map<string, number[]>();
    for (const a of standing1952) {
      const k = `${a.streetId}|${a.parity}`;
      (bySide.get(k) ?? bySide.set(k, []).get(k)!).push(a.number);
    }
    for (const nums of bySide.values()) {
      nums.sort((a, b) => a - b);
      for (let i = 1; i < nums.length; i++) {
        if (nums[i]! - nums[i - 1]! > 2) gapCount++;
      }
    }
    expect(gapCount).toBeGreaterThan(20);
  });

  it('records historical street renames with years and keeps house numbers unchanged across renames', () => {
    expect(capital.renames.length).toBeGreaterThan(0);
    const rename = capital.renames[0]!;
    const renamedStreet = capital.streets.find((s) => s.id === rename.streetId)!;
    expect(streetNameIn(renamedStreet, rename.year - 1)).toBe(rename.fromName);
    expect(streetNameIn(renamedStreet, rename.year)).toBe(rename.toName);

    // Pick a building on a renamed street in Dove Beach (e.g. Fellside Road / The Esplanade)
    expect(doveBeach.renames.length).toBeGreaterThanOrEqual(4);
    const esplanade = doveBeach.streets.find((s) => s.id === 'city2:esplanade')!;
    expect(streetNameIn(esplanade, 1915)).toBe('Marine Carriage Drive');
    expect(streetNameIn(esplanade, 1930)).toBe('The Esplanade');

    const pav1915 = addressOf(doveBeach, 'pavilion', 1915)!;
    const pav1975 = addressOf(doveBeach, 'pavilion', 1975)!;
    expect(pav1915.number).toBe(pav1975.number);
    expect(pav1915.streetName).not.toBe(pav1975.streetName);
    expect(pav1915.postalCode).toBeNull();
    expect(pav1975.postalCode).toMatch(/^\d{5}$/);
  });

  it('supports country lanes ("Old ... Road"), numbered grid streets, apartments, suites, and 1/2 split addresses', () => {
    const countryNames = capital.streets.filter((s) => s.kind === 'country' || s.kind === 'ring').map((s) => s.name);
    expect(countryNames.some((n) => n.startsWith('Old ') && n.endsWith(' Road'))).toBe(true);

    const numbered = capital.streets.filter((s) => /\b(First|Second|Third|Fourth)\s+Street\b/.test(s.name));
    expect(numbered.length).toBeGreaterThanOrEqual(3);

    // Apartments have real unit labels
    const apt = capital.buildings.find((b) => b.kind === 'apartment' && b.units.length >= 3)!;
    expect(apt).toBeTruthy();
    const aptAddr = addressOf(capital, apt.id, 2025, { unit: apt.units[0]!.label })!;
    expect(aptAddr.unit).toBe(apt.units[0]!.label);
    expect(aptAddr.line1).toContain(apt.units[0]!.label);

    // Strip center suites
    const stripCenter = addressOf(capital, 'glb:reel_time_video', 1996, { unit: 'Suite B' })!;
    expect(stripCenter.unit).toBe('Suite B');
    expect(stripCenter.line1).toContain('Suite B');

    // Half-number ("1/2") upstairs/split address
    const halfShop = capital.buildings.find((b) => b.units.some((u) => u.label === '1/2'))!;
    expect(halfShop).toBeTruthy();
    const halfAddr = addressOf(capital, halfShop.id, 2025, { unit: '1/2' })!;
    expect(halfAddr.houseNumber).toBe(`${halfShop.number} 1/2`);
    expect(halfAddr.line1).toContain(`${halfShop.number} 1/2`);
  });

  it('differentiates postal codes by district in multi-district towns and omits them before 1963', () => {
    expect(postalEraInfo(1952)).toEqual({ introducedYear: 1963, active: false, format: 'none' });
    expect(postalEraInfo(1963)).toEqual({ introducedYear: 1963, active: true, format: 'five-digit' });
    expect(postalEraInfo(1990)).toEqual({ introducedYear: 1963, active: true, format: 'five-digit-plus4' });

    const addrs1962 = allAddresses(capital, 1962);
    expect(addrs1962.every((a) => a.postalCode === null)).toBe(true);

    const addrs1975 = allAddresses(capital, 1975);
    const distinctZips = new Set(addrs1975.map((a) => a.postalCode));
    expect(distinctZips.size).toBeGreaterThanOrEqual(3);

    const dbZips = new Set(allAddresses(doveBeach, 1975).map((a) => a.postalCode));
    expect(dbZips.size).toBeGreaterThanOrEqual(3);
  });

  it('finds the right building for exact, partial, misspelled, relational ("the diner on Main"), and historical queries', () => {
    // Relational: "the diner on Main" -> Milo's Diner in capital
    const dinerHits = find(capital, 'the diner on Main', 2025);
    expect(dinerHits.length).toBeGreaterThan(0);
    expect(dinerHits[0]!.address.buildingId).toBe('glb:carhop_drive_in');
    expect(dinerHits[0]!.matchKind).toBe('relational');

    // Exact street address lookup
    const po = addressOf(capital, 'glb:post_office', 1975)!;
    const exactHits = find(capital, po.line1, 1975);
    expect(exactHits[0]!.address.buildingId).toBe('glb:post_office');

    // Abbreviated + typo street address lookup
    const abbrevQuery = po.line1.replace(/\bStreet\b/, 'St').replace(/\bW\./, 'West');
    const abbrevHits = find(capital, abbrevQuery, 1975);
    expect(abbrevHits[0]!.address.buildingId).toBe('glb:post_office');

    // Misspelled building names
    const typoPo = find(capital, 'Post Ofice', 1975);
    expect(typoPo[0]!.address.buildingId).toBe('glb:post_office');

    const typoDiner = find(capital, 'Milos Dinr', 2025);
    expect(typoDiner[0]!.address.buildingId).toBe('glb:carhop_drive_in');

    // County seat courthouse misspelling
    expect(isCountySeat('rho')).toBe(true);
    const typoCourt = find(countySeat, 'County Courthosue', 1975);
    expect(typoCourt[0]!.address.buildingId).toBe('glb:county_courthouse');

    // Dove Beach: partial, misspelled, relational, and historical street name
    expect(find(doveBeach, 'Observatry', 1975)[0]!.address.buildingId).toBe('observatory');
    expect(find(doveBeach, 'the observatory on Crown', 1975)[0]!.address.buildingId).toBe('observatory');
    expect(find(doveBeach, 'Custm House', 1920)[0]!.address.buildingId).toBe('customhouse');
    // Historical street name query on Dove Beach (Marine Carriage Drive -> The Esplanade)
    const histHits = find(doveBeach, 'Marine Carriage Drive', 1975);
    expect(histHits.some((h) => h.address.buildingId === 'pavilion')).toBe(true);
  });

  it('finds the nearest address to any world position plus an interpolated street address', () => {
    const po = addressOf(capital, 'glb:post_office', 1975)!;
    const exact = nearestAddress(capital, po.position, 1975);
    expect(exact.address?.buildingId).toBe('glb:post_office');
    expect(exact.distance).toBe(0);
    expect(exact.interpolated).not.toBeNull();

    const offset = nearestAddress(capital, { x: po.position.x + 0.5, y: po.position.y + 0.5 }, 1975);
    expect(offset.address?.buildingId).toBe('glb:post_office');
    expect(offset.distance).toBeLessThan(1);

    // Dove Beach nearest address near Custom House [0, 140]
    const dbNear = nearestAddress(doveBeach, { x: 5, y: 145 }, 1975);
    expect(dbNear.address?.buildingId).toBe('customhouse');
    expect(dbNear.interpolated).not.toBeNull();
  });

  it('builds era city directories & phone books from the sim with residents, workers, businesses, and printable text', () => {
    const tl = runTimeline({ seed: CAPITAL_SEED, startYear: 1950, endYear: 1955 });
    const w1952 = worldAt(tl, 1952);

    const dir1900 = buildDirectory(capital, 1900);
    expect(dir1900.phoneEra).toBe('none');
    expect(dir1900.postalCodes).toEqual([]);
    expect(dir1900.civic.every((c) => c.phone === null)).toBe(true);

    const dir1952 = buildDirectory(capital, 1952, w1952);
    expect(dir1952.phoneEra).toBe('exchange');
    expect(dir1952.postalCodes).toEqual([]);
    expect(dir1952.civic.length).toBeGreaterThan(5);
    expect(dir1952.businesses.length).toBeGreaterThan(10);
    expect(dir1952.residents.length).toBeGreaterThan(30);
    expect(dir1952.residents.some((r) => r.workAddress !== undefined)).toBe(true);
    expect(dir1952.businesses.some((b) => b.owner !== undefined)).toBe(true);

    // Sim resident search via find()
    const sampleRes = dir1952.residents[0]!;
    const resHits = find(capital, sampleRes.name, 1952, { world: w1952 });
    expect(resHits.some((h) => h.address.buildingId === sampleRes.buildingId)).toBe(true);

    // Printable format contains all major sections
    const text = formatDirectory(dir1952);
    expect(text).toContain('CIVIC & PUBLIC BUILDINGS');
    expect(text).toContain('CLASSIFIED BUSINESS DIRECTORY');
    expect(text).toContain('RESIDENTS DIRECTORY');
    expect(text).toContain('STREET-BY-STREET DIRECTORY');
  });

  it('exposes a deterministic, plain-JSON phone bridge and serialization round-trip', () => {
    const json = serializeAddressBook(capital);
    const reloaded = parseAddressBook(json);
    expect(reloaded).toEqual(capital);

    // Byte-for-byte deterministic from seed
    expect(serializeAddressBook(buildTownAddresses('alpha'))).toBe(serializeAddressBook(alpha));
    expect(buildTownAddresses('alpha').postalPrefix).not.toBe(buildTownAddresses('beta').postalPrefix);

    const bridge = createAddressBridge(reloaded);
    const r1 = JSON.parse(bridge.call(JSON.stringify({ op: 'addressOf', buildingId: 'glb:post_office', year: 1975 })));
    expect(r1.ok).toBe(true);
    expect(r1.result.buildingId).toBe('glb:post_office');
    expect(r1.result.postalCode).toMatch(/^\d{5}$/);

    const r2 = JSON.parse(handleAddressJson(reloaded, JSON.stringify({ op: 'find', query: 'the diner on Main', year: 2025 })));
    expect(r2.ok).toBe(true);
    expect(r2.result[0].address.buildingId).toBe('glb:carhop_drive_in');

    const r3 = JSON.parse(bridge.call(JSON.stringify({ op: 'postalInfo', year: 1952 })));
    expect(r3.ok).toBe(true);
    expect(r3.result.active).toBe(false);

    const bad = JSON.parse(bridge.call('{invalid'));
    expect(bad.ok).toBe(false);
  });

  it('resolves ordinals, typos, split 1/2 addresses, and apartment units in find()', () => {
    // Outward-numbered cross streets place First and Second Street in the core
    const firstHits = find(capital, '1st St', 2025);
    expect(firstHits.length).toBeGreaterThan(0);
    expect(firstHits[0]!.address.streetName).toContain('First Street');

    const secondHits = find(capital, '2nd St', 2025);
    expect(secondHits.length).toBeGreaterThan(0);
    expect(secondHits[0]!.address.streetName).toContain('Second Street');

    // Misspelled street query with exact house number
    const typoHits = find(capital, '101 W Mian St', 2025);
    expect(typoHits.length).toBeGreaterThan(0);
    expect(typoHits[0]!.address.line1).toBe('101 W. Main Street');

    // Split 1/2 upstairs flat address lookup
    const splitBldg = capital.buildings.find((b) => b.units.some((u) => u.kind === 'half'));
    expect(splitBldg).toBeDefined();
    const splitAddr = addressOf(capital, splitBldg!.id, 2025, { unit: '1/2' })!;
    expect(splitAddr.line1).toContain('1/2');
    const splitHits = find(capital, splitAddr.line1, 2025);
    expect(splitHits[0]!.address.buildingId).toBe(splitBldg!.id);
    expect(splitHits[0]!.address.numberSuffix).toBe('1/2');
    expect(splitHits[0]!.address.houseNumber).toContain('1/2');

    // Apartment unit query
    const aptBldg = capital.buildings.find((b) => b.units.some((u) => u.kind === 'apartment'));
    expect(aptBldg).toBeDefined();
    const aptUnit = aptBldg!.units.find((u) => u.kind === 'apartment')!;
    const aptAddr = addressOf(capital, aptBldg!.id, 2025, { unit: aptUnit.label })!;
    const aptHits = find(capital, aptAddr.line1, 2025);
    expect(aptHits[0]!.address.buildingId).toBe(aptBldg!.id);
    expect(aptHits[0]!.address.unit).toBe(aptUnit.label);
  });

  it('supports v1 town plans and Dove Beach extra buildings without tunnel bleed', () => {
    // Legacy planVersion: 1 compatibility
    setPlanVersion(1);
    let v1Book: TownAddressBook;
    try {
      v1Book = buildTownAddresses('alpha', { plan: planTown('alpha') });
    } finally {
      setPlanVersion(2);
    }
    assertBookInvariants(v1Book, [1920, 1985, 2025]);

    // Dove Beach surface landmarks stay off underground Headland Tunnel
    expect(addressOf(doveBeach, 'fort', 1985)?.streetName).toBe('Neck Hill');
    expect(addressOf(doveBeach, 'shrimpdock', 1985)?.streetName).toBe('Head Road');
    expect(addressOf(doveBeach, 'tunnel', 1985)?.streetName).toBe('Headland Tunnel');

    // Caller-supplied Dove Beach buildings integrate into numbering & search
    const customDb = buildDoveBeachAddresses({
      extraBuildings: [
        {
          id: 'shop:strand-surf',
          label: 'Strand Surf Shop',
          kind: 'shop',
          roles: ['surf shop', 'store'],
          at: [-1745, 1055],
          fromYear: 1962,
        },
        {
          id: 'shop:strand-bait',
          label: 'Strand Tackle Annex',
          kind: 'shop',
          roles: ['tackle', 'store'],
          at: [-1745, 1055],
          fromYear: 1968,
        },
      ],
    });
    const surfAddr = addressOf(customDb, 'shop:strand-surf', 1975);
    const annexAddr = addressOf(customDb, 'shop:strand-bait', 1975);
    expect(surfAddr).not.toBeNull();
    expect(annexAddr).not.toBeNull();
    expect(surfAddr!.streetName).toBe('The Esplanade');
    expect(surfAddr!.postalCode).toMatch(/^\d{5}$/);
    // Co-located split building receives a distinct 1/2 or unit suffix
    expect(annexAddr!.line1).not.toBe(surfAddr!.line1);
    expect(annexAddr!.houseNumber).toContain('1/2');
    const surfHits = find(customDb, 'surf shop on Esplanade', 1975);
    expect(surfHits[0]!.address.buildingId).toBe('shop:strand-surf');
  });

  it('holds all street & numbering invariants across 8 diverse generated seeds', () => {
    for (const seed of ['alpha', 'beta', 'delta', 'epsilon', 'zeta', 'eta', 'theta', 'rho']) {
      const book = buildTownAddresses(seed);
      assertBookInvariants(book, [1900, 1955, 1985, 2025]);
    }
  });
});
