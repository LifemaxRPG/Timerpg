import { rand } from '../sim/rng';
import { ageIn, fullName, isAlive, type World } from '../sim/types';
import { layoutTown } from '../town/layout';
import { buildResidents } from '../town/schedule';
import {
  buildSimOverlay,
  buildingLabelInYear,
  isStandingInYear,
  materializeAddress,
} from './lookup';
import { postalCodeFor } from './postal';
import { streetNameIn } from './streets';
import type {
  BuildingEntry,
  CityDirectory,
  DirectoryBusinessEntry,
  DirectoryCivicEntry,
  DirectoryResidentEntry,
  DirectoryStreetListing,
  DirectoryStreetSection,
  TownAddressBook,
} from './types';

const EXCHANGES = [
  'ALder', 'BElmont', 'CEdar', 'DAlton', 'ELmwood', 'FAirview', 'GRove', 'HArbor',
  'LInden', 'MAple', 'MEadow', 'OAkland', 'PIne', 'RIver', 'SPring', 'WIllow',
] as const;

export function phoneEraOf(year: number): CityDirectory['phoneEra'] {
  if (year < 1915) return 'none';
  if (year < 1936) return 'three-digit';
  if (year < 1962) return 'exchange';
  if (year < 1995) return 'seven-digit';
  return 'ten-digit';
}

function townExchangeName(seed: string, townName: string): string {
  const first = townName.split(/\s+/)[0] ?? '';
  if (first.length >= 4 && first.length <= 7 && /^[A-Za-z]+$/.test(first)) {
    return first.slice(0, 2).toUpperCase() + first.slice(2).toLowerCase();
  }
  return EXCHANGES[Math.floor(rand(seed, 'phone-exchange') * EXCHANGES.length)]!;
}

/** Deterministic, fictional, era-appropriate telephone number (never emits a real-world routable number). */
export function phoneNumberFor(
  seed: string,
  townName: string,
  entityKey: string,
  year: number,
): string | null {
  const era = phoneEraOf(year);
  if (era === 'none') return null;

  const h1 = Math.floor(rand(seed, 'phone', entityKey, 'a') * 899) + 100; // 100..998
  const h2 = Math.floor(rand(seed, 'phone', entityKey, 'b') * 9000) + 1000; // 1000..9999

  if (era === 'three-digit') return String(h1);
  if (era === 'exchange') {
    const ex = townExchangeName(seed, townName);
    const digit = 2 + Math.floor(rand(seed, 'phone-ex-d', entityKey) * 7); // 2..8
    return `${ex} ${digit}-${String(h2).padStart(4, '0')}`;
  }
  const localEx = 200 + Math.floor(rand(seed, 'phone-prefix') * 700);
  if (era === 'seven-digit') {
    return `555-${String(h2).padStart(4, '0')}`;
  }
  // 10-digit with reserved fictional 555 area code
  return `(555) ${localEx}-${String(h2).padStart(4, '0')}`;
}

function isCivicBuilding(b: BuildingEntry): boolean {
  if (b.kind === 'civic') return true;
  const civicRoles = new Set([
    'courthouse', 'post office', 'fire station', 'police station', 'school',
    'elementary school', 'library', 'church', 'chapel', 'clock tower', 'hospital', 'depot',
  ]);
  return b.roles.some((r) => civicRoles.has(r));
}

function categoryForBuilding(b: BuildingEntry): string {
  const r = b.roles[0] ?? b.kind;
  if (r.includes('diner') || r.includes('carhop')) return 'Diners & Restaurants';
  if (r.includes('gas') || r.includes('service')) return 'Service Stations & Fuel';
  if (r.includes('motel') || r.includes('hotel') || r.includes('lodge')) return 'Hotels & Lodging';
  if (r.includes('store') || r.includes('market') || r.includes('grocery') || r.includes('five')) return 'Groceries & General Stores';
  if (r.includes('bank')) return 'Banks & Finance';
  if (r.includes('drugstore') || r.includes('pharmacy')) return 'Drugstores & Pharmacies';
  if (r.includes('cinema') || r.includes('bowling') || r.includes('ballpark') || r.includes('drive')) return 'Amusements & Theaters';
  if (r.includes('barber') || r.includes('salon')) return 'Barbers & Salons';
  if (r.includes('newspaper') || r.includes('radio')) return 'Press & Broadcasting';
  if (r.includes('farm')) return 'Farms & Produce';
  return 'Businesses & Services';
}

/**
 * Build a complete, plain-JSON city directory / phone book for a town and year.
 * When a sim [world] snapshot is supplied, includes every living resident (home + workplace + unit)
 * and every open business (owner + employees).
 */
export function buildDirectory(book: TownAddressBook, year: number, world?: World): CityDirectory {
  const streetById = new Map(book.streets.map((s) => [s.id, s]));
  const overlay = world ? buildSimOverlay(book, world) : undefined;
  const phoneEra = phoneEraOf(year);
  const exchangeName = phoneEra === 'exchange' ? townExchangeName(book.seed, book.townName) : null;

  const civic: DirectoryCivicEntry[] = [];
  const businesses: DirectoryBusinessEntry[] = [];
  const residents: DirectoryResidentEntry[] = [];

  // Track occupants per buildingId + unit for the street-by-street cross-reference section
  const streetOccupants = new Map<string, Map<string, { occupants: string[]; phone: string | null }>>();
  const recordOccupant = (buildingId: string, unit: string | undefined, text: string, phone: string | null): void => {
    const uKey = unit ?? '';
    let byUnit = streetOccupants.get(buildingId);
    if (!byUnit) {
      byUnit = new Map();
      streetOccupants.set(buildingId, byUnit);
    }
    let rec = byUnit.get(uKey);
    if (!rec) {
      rec = { occupants: [], phone };
      byUnit.set(uKey, rec);
    }
    if (!rec.occupants.includes(text)) rec.occupants.push(text);
    if (!rec.phone && phone) rec.phone = phone;
  };

  // ---- 1. If a simulation World is present, index residents, households, businesses, and workers ----
  const shopEmployees = new Map<string, string[]>(); // businessId -> employee full names
  const businessAddressById = new Map<string, { buildingId: string; formatted: string }>();

  if (world) {
    const layout = layoutTown(world);
    const simResidents = buildResidents(layout, world);
    const simResById = new Map(simResidents.map((r) => [r.id, r]));

    // Map shop business IDs to their addressed building slot
    const shopLots = layout.lots.filter((l) => l.kind === 'shop' && l.vacantSince === undefined && l.refId);
    const bizByName = new Map<string, { id: string; slot: string }>();

    for (const lot of shopLots) {
      const biz = world.businesses[lot.refId];
      if (!biz || biz.closedYear !== undefined) continue;
      const bEntry = book.buildings.find((q) => q.id === lot.slot);
      const st = bEntry ? streetById.get(bEntry.streetId) : undefined;
      if (!bEntry || !st) continue;
      const addr = materializeAddress(book, bEntry, st, year, { overlay });
      businessAddressById.set(biz.id, { buildingId: bEntry.id, formatted: addr.line1 });
      bizByName.set(biz.name, { id: biz.id, slot: bEntry.id });
    }

    // Collect residents & match workers to workplaces
    const houseLots = layout.lots.filter((l) => l.kind === 'house' && l.vacantSince === undefined);
    for (const lot of houseLots) {
      const bEntry = book.buildings.find((q) => q.id === lot.slot);
      const st = bEntry ? streetById.get(bEntry.streetId) : undefined;
      if (!bEntry || !st) continue;

      for (const pid of lot.residents) {
        const p = world.people[pid];
        if (!p || !isAlive(p)) continue;
        const unit = overlay?.personToUnit[pid];
        const addr = materializeAddress(book, bEntry, st, year, { unit, overlay });
        const simR = simResById.get(pid);
        const occupation = simR?.job ?? (ageIn(p, year) < 5 ? 'child' : 'resident');
        const phone = phoneNumberFor(book.seed, book.townName, `home:${bEntry.id}:${unit ?? 'main'}`, year);

        let workBuildingId: string | undefined;
        let workAddress: string | undefined;
        if (simR) {
          const workMatch = simR.job.match(/^works at (.+)$/) ?? simR.job.match(/^(.+) \(owner\)$/);
          if (workMatch) {
            const bInfo = bizByName.get(workMatch[1]!);
            if (bInfo) {
              workBuildingId = bInfo.slot;
              workAddress = businessAddressById.get(bInfo.id)?.formatted;
              if (simR.job.startsWith('works at ')) {
                const emps = shopEmployees.get(bInfo.id) ?? [];
                emps.push(fullName(p));
                shopEmployees.set(bInfo.id, emps);
              }
            }
          }
        }

        residents.push({
          personId: pid,
          name: fullName(p),
          surname: p.surname,
          firstName: p.first,
          age: ageIn(p, year),
          occupation,
          buildingId: bEntry.id,
          address: addr.line1,
          ...(unit ? { unit } : {}),
          streetName: addr.streetName,
          houseNumber: addr.houseNumber,
          phone,
          ...(workBuildingId ? { workBuildingId } : {}),
          ...(workAddress ? { workAddress } : {}),
        });

        recordOccupant(bEntry.id, unit, `${fullName(p)} (${occupation})`, phone);
      }
    }

    // Add sim businesses
    for (const lot of shopLots) {
      const biz = world.businesses[lot.refId];
      if (!biz || biz.closedYear !== undefined) continue;
      const bEntry = book.buildings.find((q) => q.id === lot.slot);
      const st = bEntry ? streetById.get(bEntry.streetId) : undefined;
      if (!bEntry || !st) continue;
      const addr = materializeAddress(book, bEntry, st, year, { overlay });
      const owner = biz.owner && world.people[biz.owner] ? fullName(world.people[biz.owner]!) : undefined;
      const employees = [...(shopEmployees.get(biz.id) ?? [])].sort();
      const phone = phoneNumberFor(book.seed, book.townName, `biz:${biz.id}`, year);

      businesses.push({
        id: biz.id,
        name: biz.name,
        category: biz.kind,
        ...(owner ? { owner } : {}),
        employees,
        buildingId: bEntry.id,
        address: addr.line1,
        streetName: addr.streetName,
        houseNumber: addr.houseNumber,
        phone,
        status: 'open',
      });
      recordOccupant(bEntry.id, undefined, `${biz.name} (${biz.kind})`, phone);
    }
  }

  // ---- 2. Civic, Landmark, GLB, and Farm buildings standing in [year] ----
  for (const b of book.buildings) {
    if (!isStandingInYear(b, year, overlay)) continue;
    const st = streetById.get(b.streetId);
    if (!st) continue;
    const addr = materializeAddress(book, b, st, year, { overlay });
    const label = buildingLabelInYear(b, year, overlay);

    if (b.kind === 'civic' || ((b.kind === 'glb' || b.kind === 'landmark') && isCivicBuilding(b))) {
      const phone = phoneNumberFor(book.seed, book.townName, `civic:${b.id}`, year);
      civic.push({
        buildingId: b.id,
        name: label,
        role: b.roles[0] ?? 'civic',
        address: addr.line1,
        streetName: addr.streetName,
        houseNumber: addr.houseNumber,
        phone,
      });
      recordOccupant(b.id, undefined, label, phone);
    } else if (b.kind === 'glb' || b.kind === 'landmark') {
      const phone = phoneNumberFor(book.seed, book.townName, `biz:${b.id}`, year);
      businesses.push({
        id: b.id,
        name: label,
        category: categoryForBuilding(b),
        employees: [],
        buildingId: b.id,
        address: addr.line1,
        streetName: addr.streetName,
        houseNumber: addr.houseNumber,
        phone,
      });
      recordOccupant(b.id, undefined, label, phone);
    } else if (b.kind === 'farm') {
      const phone = phoneNumberFor(book.seed, book.townName, `farm:${b.id}`, year);
      businesses.push({
        id: b.id,
        name: label,
        category: 'Farms & Produce',
        employees: [],
        buildingId: b.id,
        address: addr.line1,
        streetName: addr.streetName,
        houseNumber: addr.houseNumber,
        phone,
      });
      recordOccupant(b.id, undefined, label, phone);
    }
  }

  civic.sort((a, b) => a.name.localeCompare(b.name) || a.address.localeCompare(b.address));
  businesses.sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name) || a.address.localeCompare(b.address));
  residents.sort((a, b) => a.surname.localeCompare(b.surname) || a.firstName.localeCompare(b.firstName) || a.personId.localeCompare(b.personId));

  // ---- 3. Street-by-street cross-reference section (Polk / Criss-Cross directory) ----
  const byStreet = new Map<string, BuildingEntry[]>();
  const postalSet = new Set<string>();

  for (const b of book.buildings) {
    if (!isStandingInYear(b, year, overlay)) continue;
    const arr = byStreet.get(b.streetId) ?? [];
    arr.push(b);
    byStreet.set(b.streetId, arr);
    const pc = postalCodeFor(book, b.district, year);
    if (pc) postalSet.add(pc);
  }

  const streets: DirectoryStreetSection[] = [];
  for (const st of book.streets) {
    const items = byStreet.get(st.id);
    if (!items || !items.length) continue;
    items.sort((a, b) => a.number - b.number || (a.numberSuffix ?? '').localeCompare(b.numberSuffix ?? '') || a.id.localeCompare(b.id));

    const listings: DirectoryStreetListing[] = items.map((b) => {
      const label = buildingLabelInYear(b, year, overlay);
      const occMap = streetOccupants.get(b.id);
      const units: DirectoryStreetListing['units'] = [];
      if (occMap && occMap.size) {
        for (const [uKey, info] of [...occMap.entries()].sort((a, c) => a[0].localeCompare(c[0]))) {
          units.push({ ...(uKey ? { unit: uKey } : {}), occupants: info.occupants, phone: info.phone });
        }
      } else {
        units.push({ occupants: [label], phone: null });
      }
      return {
        buildingId: b.id,
        houseNumber: b.houseNumber,
        number: b.number,
        parity: b.parity,
        label,
        kind: b.kind,
        units,
      };
    });

    streets.push({
      streetId: st.id,
      streetName: streetNameIn(st, year),
      postalCode: postalCodeFor(book, st.district, year),
      listings,
    });
  }
  streets.sort((a, b) => a.streetName.localeCompare(b.streetName));

  const title =
    phoneEra === 'none'
      ? `${book.townName} City Directory (${year})`
      : `${book.townName} City & Telephone Directory (${year})`;

  return {
    townId: book.townId,
    townName: book.townName,
    year,
    title,
    postalCodes: [...postalSet].sort(),
    phoneEra,
    exchangeName,
    civic,
    businesses,
    residents,
    streets,
  };
}

/** Render a CityDirectory into a clean printable plain-text directory for paper-map eras. */
export function formatDirectory(dir: CityDirectory): string {
  const lines: string[] = [];
  const bar = '='.repeat(68);
  lines.push(bar);
  lines.push(dir.title.toUpperCase());
  if (dir.postalCodes.length) lines.push(`Postal Code${dir.postalCodes.length > 1 ? 's' : ''}: ${dir.postalCodes.join(', ')}`);
  if (dir.exchangeName) lines.push(`Telephone Exchange: ${dir.exchangeName}`);
  lines.push(bar);

  if (dir.civic.length) {
    lines.push('', '--- CIVIC & PUBLIC BUILDINGS ---');
    for (const c of dir.civic) {
      lines.push(`  ${c.name} — ${c.address}${c.phone ? `  [Tel: ${c.phone}]` : ''}`);
    }
  }

  if (dir.businesses.length) {
    lines.push('', '--- CLASSIFIED BUSINESS DIRECTORY ---');
    let curCat = '';
    for (const b of dir.businesses) {
      if (b.category !== curCat) {
        curCat = b.category;
        lines.push(`  [${curCat}]`);
      }
      const staff: string[] = [];
      if (b.owner) staff.push(`Prop: ${b.owner}`);
      if (b.employees.length) staff.push(`Staff: ${b.employees.join(', ')}`);
      lines.push(
        `    ${b.name} — ${b.address}${b.phone ? `  [Tel: ${b.phone}]` : ''}${staff.length ? ` (${staff.join('; ')})` : ''}`,
      );
    }
  }

  if (dir.residents.length) {
    lines.push('', '--- RESIDENTS DIRECTORY ---');
    for (const r of dir.residents) {
      const work = r.workAddress ? ` | Work: ${r.workAddress}` : '';
      lines.push(
        `  ${r.surname}, ${r.firstName} — ${r.occupation} — ${r.address}${r.phone ? `  [Tel: ${r.phone}]` : ''}${work}`,
      );
    }
  }

  if (dir.streets.length) {
    lines.push('', '--- STREET-BY-STREET DIRECTORY ---');
    for (const st of dir.streets) {
      lines.push(`  ${st.streetName}${st.postalCode ? ` (${st.postalCode})` : ''}`);
      for (const l of st.listings) {
        for (const u of l.units) {
          const uTag = u.unit ? ` ${u.unit}` : '';
          lines.push(`    ${l.houseNumber}${uTag}: ${u.occupants.join('; ')}${u.phone ? `  [${u.phone}]` : ''}`);
        }
      }
    }
  }

  return lines.join('\n');
}
