import { fullName, isAlive, type World } from '../sim/types';
import { layoutTown, type Lot } from '../town/layout';
import { coordinateHouseNumber, formatHouseNumber, projectOntoStreet } from './numbering';
import { postalCodeFor, postalCodePlus4For } from './postal';
import { allKnownStreetNames, baseStreetNameIn, streetNameIn } from './streets';
import type {
  Address,
  BuildingEntry,
  CardinalDir,
  FindMatch,
  MatchKind,
  NearestResult,
  StreetRecord,
  TownAddressBook,
} from './types';

export interface SimOverlay {
  /** Building slot/id -> dynamic display label in this year (e.g. "Mercer's Bakery", "Sutter house"). */
  labels: Record<string, string>;
  /** Building slot/id -> extra searchable terms (resident names, business kind, owner name). */
  terms: Record<string, string[]>;
  /** Business ID (e.g. "b:0001") -> building slot ID. */
  businessToBuilding: Record<string, string>;
  /** Person ID (e.g. "p:0001") -> home building slot ID. */
  personToBuilding: Record<string, string>;
  /** Person ID -> assigned apartment/duplex unit (if any). */
  personToUnit: Record<string, string>;
  /** Building slot IDs physically standing in this World snapshot (occupied, vacant, or filler). */
  activeIds: Record<string, true>;
}

export interface LookupOptions {
  /** Optional simulation World snapshot to enrich building labels, businesses, and resident search. */
  world?: World;
  /** Pre-built SimOverlay (avoids recomputing layoutTown on repeated queries). */
  overlay?: SimOverlay;
  /** Optional apartment/suite/half unit to format into the returned Address. */
  unit?: string;
  /** When true, addressOf resolves buildings even before they were built or after demolition. */
  includeInactive?: boolean;
  /** Maximum number of results returned by find() (default 10). */
  limit?: number;
}

const overlayCache = new WeakMap<World, SimOverlay>();

/** Build a lightweight plain lookup overlay from a simulation World snapshot (cached per World reference). */
export function buildSimOverlay(book: TownAddressBook, world: World): SimOverlay {
  const cached = overlayCache.get(world);
  if (cached) return cached;

  const layout = layoutTown(world);
  const byId = new Map(book.buildings.map((b) => [b.id, b]));
  const labels: Record<string, string> = {};
  const terms: Record<string, string[]> = {};
  const businessToBuilding: Record<string, string> = {};
  const personToBuilding: Record<string, string> = {};
  const personToUnit: Record<string, string> = {};
  const activeIds: Record<string, true> = {};

  const addLot = (lot: Lot): void => {
    const entry = byId.get(lot.slot);
    if (!entry) return;
    activeIds[lot.slot] = true;
    const extra: string[] = [];

    if (lot.kind === 'shop') {
      const biz = lot.refId ? world.businesses[lot.refId] : undefined;
      if (biz && biz.closedYear === undefined) {
        labels[lot.slot] = biz.name;
        businessToBuilding[biz.id] = lot.slot;
        extra.push(biz.name, biz.kind);
        if (biz.owner && world.people[biz.owner]) {
          const owner = world.people[biz.owner]!;
          extra.push(fullName(owner), owner.surname);
        }
      } else if (lot.label) {
        labels[lot.slot] = lot.label;
        extra.push(lot.label);
      }
    } else if (lot.kind === 'house') {
      if (lot.label) {
        labels[lot.slot] = lot.label;
        extra.push(lot.label);
      }
      const heads = lot.heads;
      for (let hi = 0; hi < heads.length; hi++) {
        const headId = heads[hi]!;
        let unitLabel: string | undefined;
        if (lot.tier === 'apartment' && entry.units.length) {
          unitLabel = entry.units[hi % entry.units.length]!.label;
        } else if (heads.length > 1) {
          unitLabel = hi === 0 ? 'Unit A' : hi === 1 ? '1/2' : `Unit ${String.fromCharCode(65 + hi)}`;
        }
        const headPerson = world.people[headId];
        if (headPerson) {
          extra.push(fullName(headPerson), headPerson.surname);
          personToBuilding[headId] = lot.slot;
          if (unitLabel) personToUnit[headId] = unitLabel;
        }
      }
      for (const pid of lot.residents) {
        const p = world.people[pid];
        if (!p || !isAlive(p)) continue;
        extra.push(fullName(p), p.surname);
        personToBuilding[pid] = lot.slot;
        if (!personToUnit[pid] && heads.length > 1) {
          // Inherit household head's unit
          const matchIdx = heads.findIndex((h) => h === pid || p.parents.includes(h) || p.spouse === h);
          const idx = matchIdx >= 0 ? matchIdx : 0;
          if (lot.tier === 'apartment' && entry.units.length) {
            personToUnit[pid] = entry.units[idx % entry.units.length]!.label;
          } else {
            personToUnit[pid] = idx === 0 ? 'Unit A' : idx === 1 ? '1/2' : `Unit ${String.fromCharCode(65 + idx)}`;
          }
        }
      }
    }

    if (extra.length) terms[lot.slot] = [...new Set(extra)];
  };

  for (const lot of layout.lots) addLot(lot);
  for (const lot of layout.vacantLots) activeIds[lot.slot] = true;
  for (const lot of layout.fillers) {
    if (!labels[lot.slot]) addLot(lot);
    else activeIds[lot.slot] = true;
  }
  for (const lm of layout.landmarks) activeIds[`landmark:${lm.id}`] = true;
  for (const farm of layout.farms.farms) activeIds[farm.id] = true;

  const overlay: SimOverlay = { labels, terms, businessToBuilding, personToBuilding, personToUnit, activeIds };
  overlayCache.set(world, overlay);
  return overlay;
}

function getOverlay(book: TownAddressBook, options?: LookupOptions): SimOverlay | undefined {
  if (options?.overlay) return options.overlay;
  if (options?.world) return buildSimOverlay(book, options.world);
  return undefined;
}

/** True if a building stands in [year]. */
export function isStandingInYear(
  b: Pick<BuildingEntry, 'id' | 'fromYear' | 'toYear'>,
  year: number,
  overlay?: SimOverlay,
): boolean {
  if (overlay?.activeIds[b.id]) return true;
  return year >= b.fromYear && (b.toYear === undefined || year < b.toYear);
}

/** Active display label for a building in [year]. */
export function buildingLabelInYear(b: BuildingEntry, year: number, overlay?: SimOverlay): string {
  if (overlay?.labels[b.id]) return overlay.labels[b.id]!;
  if (b.eraLabels && b.eraLabels.length) {
    for (let i = b.eraLabels.length - 1; i >= 0; i--) {
      const e = b.eraLabels[i]!;
      if (year >= e.from && (e.to === undefined || year < e.to)) return e.label;
    }
    return b.eraLabels[0]!.label;
  }
  return b.label;
}

/** Materialize a plain-JSON Address for a building on a street in [year]. */
export function materializeAddress(
  book: TownAddressBook,
  b: BuildingEntry,
  street: StreetRecord,
  year: number,
  options?: { unit?: string; overlay?: SimOverlay },
): Address {
  const activeStreet = streetNameIn(street, year);
  const activeBase = baseStreetNameIn(street, year);
  const postalCode = postalCodeFor(book, b.district, year);
  const postalCodePlus4 = postalCodePlus4For(book, b.district, street.id, b.number, year);
  const label = buildingLabelInYear(b, year, options?.overlay);

  const requestedUnit = options?.unit?.trim() || undefined;
  let houseNumber = b.houseNumber;
  let numberSuffix = b.numberSuffix;
  let unit = requestedUnit;

  // Classic "1/2" sub-address formats directly on the house number (e.g. "118 1/2 E. Main Street")
  if (requestedUnit === '1/2') {
    numberSuffix = '1/2';
    houseNumber = formatHouseNumber(b.number, '1/2');
    unit = undefined;
  }

  const line1 = unit ? `${houseNumber} ${activeStreet}, ${unit}` : `${houseNumber} ${activeStreet}`;
  const line2 = postalCode ? `${book.townName} ${postalCode}` : book.townName;
  const formatted = `${line1}, ${line2}`;
  const generic = label === 'Residence' || label === 'Commercial Building' || label === 'Apartment Building';
  const envelope = generic ? [line1, line2] : [label, line1, line2];

  const former = allKnownStreetNames(street).filter((n) => n !== activeStreet);
  const units = b.units.length ? b.units.map((u) => u.label) : undefined;

  return {
    buildingId: b.id,
    townId: book.townId,
    townName: book.townName,
    year,
    number: b.number,
    ...(numberSuffix ? { numberSuffix } : {}),
    houseNumber,
    streetId: street.id,
    ...(street.direction ? { direction: street.direction } : {}),
    baseStreetName: activeBase,
    streetName: activeStreet,
    ...(unit ? { unit } : {}),
    district: b.district,
    districtName: b.districtName,
    postalCode,
    postalCodePlus4,
    parity: b.parity,
    line1,
    line2,
    formatted,
    envelope,
    position: { x: b.position.x, y: b.position.y },
    label,
    kind: b.kind,
    ...(units ? { units } : {}),
    ...(former.length ? { formerStreetNames: former } : {}),
  };
}

/** Look up the real address of a building (or business/person ID when world is passed) in [year]. */
export function addressOf(
  book: TownAddressBook,
  buildingId: string,
  year: number,
  options?: LookupOptions,
): Address | null {
  const overlay = getOverlay(book, options);
  let targetId = buildingId;
  let inferredUnit = options?.unit;

  if (overlay) {
    if (overlay.businessToBuilding[buildingId]) {
      targetId = overlay.businessToBuilding[buildingId]!;
    } else if (overlay.personToBuilding[buildingId]) {
      targetId = overlay.personToBuilding[buildingId]!;
      if (!inferredUnit && overlay.personToUnit[buildingId]) inferredUnit = overlay.personToUnit[buildingId];
    }
  }

  const b = book.buildings.find((q) => q.id === targetId || q.aliases.includes(targetId));
  if (!b) return null;
  if (!options?.includeInactive && !isStandingInYear(b, year, overlay)) return null;

  const street = book.streets.find((s) => s.id === b.streetId);
  if (!street) return null;

  return materializeAddress(book, b, street, year, { unit: inferredUnit, overlay });
}

/** Return the address of every building standing in [year]. */
export function allAddresses(book: TownAddressBook, year: number, options?: LookupOptions): Address[] {
  const overlay = getOverlay(book, options);
  const streetById = new Map(book.streets.map((s) => [s.id, s]));
  const out: Address[] = [];
  for (const b of book.buildings) {
    if (!options?.includeInactive && !isStandingInYear(b, year, overlay)) continue;
    const street = streetById.get(b.streetId);
    if (!street) continue;
    out.push(materializeAddress(book, b, street, year, { overlay }));
  }
  return out;
}

// ---- Normalization, abbreviations, ordinals, and Damerau-Levenshtein fuzzy search ----

const SUFFIX_MAP: Record<string, string> = {
  st: 'street',
  str: 'street',
  ave: 'avenue',
  av: 'avenue',
  rd: 'road',
  dr: 'drive',
  ln: 'lane',
  ct: 'court',
  pl: 'place',
  blvd: 'boulevard',
  pkwy: 'parkway',
  cir: 'circle',
  trl: 'trail',
  ter: 'terrace',
  terr: 'terrace',
  hwy: 'highway',
  wy: 'way',
  pk: 'pike',
  aly: 'alley',
  sq: 'square',
};

const DIR_MAP: Record<string, CardinalDir> = {
  n: 'N',
  north: 'N',
  s: 'S',
  south: 'S',
  e: 'E',
  east: 'E',
  w: 'W',
  west: 'W',
};

const ORDINAL_MAP: Record<string, string> = {
  '1st': 'first',
  '2nd': 'second',
  '3rd': 'third',
  '4th': 'fourth',
  '5th': 'fifth',
  '6th': 'sixth',
  '7th': 'seventh',
  '8th': 'eighth',
  '9th': 'ninth',
  '10th': 'tenth',
  '11th': 'eleventh',
  '12th': 'twelfth',
  '13th': 'thirteenth',
  '14th': 'fourteenth',
  '15th': 'fifteenth',
  '16th': 'sixteenth',
  '17th': 'seventeenth',
  '18th': 'eighteenth',
  '19th': 'nineteenth',
  '20th': 'twentieth',
};

const STOP_WORDS = new Set(['the', 'a', 'an', 'of', 'in', 'to', 'for']);

function normalizeText(s: string): string {
  return s
    .toLowerCase()
    .replace(/['’]/g, '')
    .replace(/[.,;:#()]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeToken(tok: string): string {
  if (ORDINAL_MAP[tok]) return ORDINAL_MAP[tok]!;
  if (SUFFIX_MAP[tok]) return SUFFIX_MAP[tok]!;
  return tok;
}

function tokenize(s: string, dropStop = false): string[] {
  const norm = normalizeText(s);
  if (!norm) return [];
  return norm
    .split(' ')
    .filter(Boolean)
    .map(normalizeToken)
    .filter((t) => !dropStop || !STOP_WORDS.has(t));
}

/** Optimal String Alignment (Damerau-Levenshtein) distance: handles typos and adjacent transpositions as cost 1. */
export function editDistance(a: string, b: string): number {
  if (a === b) return 0;
  const la = a.length;
  const lb = b.length;
  if (la === 0) return lb;
  if (lb === 0) return la;
  if (Math.abs(la - lb) > 4) return Math.abs(la - lb);

  const dp: number[][] = Array.from({ length: la + 1 }, () => new Array<number>(lb + 1).fill(0));
  for (let i = 0; i <= la; i++) dp[i]![0] = i;
  for (let j = 0; j <= lb; j++) dp[0]![j] = j;

  for (let i = 1; i <= la; i++) {
    for (let j = 1; j <= lb; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let val = Math.min(dp[i - 1]![j]! + 1, dp[i]![j - 1]! + 1, dp[i - 1]![j - 1]! + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        val = Math.min(val, dp[i - 2]![j - 2]! + 1);
      }
      dp[i]![j] = val;
    }
  }
  return dp[la]![lb]!;
}

/** Similarity score 0..1 between a query token and a target token. */
function tokenScore(q: string, target: string): number {
  if (q === target) return 1;
  if (target.startsWith(q) && q.length >= 2) return 0.88 + 0.1 * (q.length / target.length);
  if (q.startsWith(target) && target.length >= 3) return 0.82;
  const minLen = Math.min(q.length, target.length);
  const maxLen = Math.max(q.length, target.length);
  if (minLen < 3) return 0;
  const d = editDistance(q, target);
  const allowed = minLen <= 4 ? 1 : maxLen <= 8 ? 2 : 3;
  if (d <= allowed) return Math.max(0.5, 1 - d / (maxLen + 0.5));
  return 0;
}

/** Best match score 0..1 for a set of query tokens against a target phrase. */
function phraseMatchScore(queryTokens: readonly string[], targetPhrase: string): { score: number; exact: boolean; prefix: boolean } {
  const tTokens = tokenize(targetPhrase, true);
  if (!queryTokens.length || !tTokens.length) return { score: 0, exact: false, prefix: false };

  const qJoin = queryTokens.join(' ');
  const tJoin = tTokens.join(' ');
  if (qJoin === tJoin) return { score: 1, exact: true, prefix: true };
  if (tJoin.startsWith(qJoin)) return { score: 0.94, exact: false, prefix: true };

  let sum = 0;
  let allExact = true;
  let allPrefix = true;
  for (const qt of queryTokens) {
    let best = 0;
    for (const tt of tTokens) {
      const s = tokenScore(qt, tt);
      if (s > best) best = s;
    }
    if (best < 1) allExact = false;
    if (best < 0.88) allPrefix = false;
    sum += best;
  }
  const avg = sum / queryTokens.length;
  // Penalize slightly if any query token didn't match at all
  const misses = queryTokens.filter((qt) => tTokens.every((tt) => tokenScore(qt, tt) < 0.5)).length;
  const penalty = misses > 0 ? misses * 0.25 : 0;
  return { score: Math.max(0, avg - penalty), exact: allExact && queryTokens.length === tTokens.length, prefix: allPrefix };
}

interface ParsedStreetQuery {
  number?: number;
  suffix?: string;
  direction?: CardinalDir;
  streetTokens: string[];
  unit?: string;
  postal?: string;
}

function parseStreetQuery(raw: string): ParsedStreetQuery | null {
  let s = raw.trim();
  let postal: string | undefined;
  const zipM = s.match(/\b(\d{5})(?:-\d{4})?\s*$/);
  if (zipM) {
    postal = zipM[1]!;
    s = s.slice(0, zipM.index).trim();
  }

  let unit: string | undefined;
  const unitM = s.match(/(?:,|\s)+(?:apt\.?|apartment|unit|suite|ste\.?|#|rm\.?|room)\s*([a-z0-9-]+)\s*$/i);
  if (unitM) {
    unit = unitM[0]!.replace(/^[, ]+/, '').trim();
    s = s.slice(0, unitM.index).trim();
  }

  const numM = s.match(/^(\d+)\s*(1\/2|[a-f]\b)?(?:\s+|$)(.*)$/i);
  if (!numM) return null;

  const number = Number(numM[1]!);
  let suffix = numM[2] ? numM[2].toUpperCase() : undefined;
  let rest = (numM[3] ?? '').trim();

  // Handle "214A Main St" where letter is attached to the number
  if (!suffix) {
    const attached = s.match(/^(\d+)([a-f])\s+(.*)$/i);
    if (attached) {
      suffix = attached[2]!.toUpperCase();
      rest = attached[3]!.trim();
    }
  }

  const rawTokens = tokenize(rest, false);
  let direction: CardinalDir | undefined;
  let streetTokens = rawTokens;
  if (rawTokens.length >= 2 && DIR_MAP[rawTokens[0]!]) {
    direction = DIR_MAP[rawTokens[0]!];
    streetTokens = rawTokens.slice(1);
  }

  return { number, ...(suffix ? { suffix } : {}), ...(direction ? { direction } : {}), streetTokens, ...(unit ? { unit } : {}), ...(postal ? { postal } : {}) };
}

/**
 * Search for buildings and addresses in [year].
 * Handles exact addresses, partial names, typos, "the diner on Main", historical street names,
 * postal codes, and (when a World is supplied) residents and businesses.
 */
export function find(book: TownAddressBook, query: string, year: number, options?: LookupOptions): FindMatch[] {
  const trimmed = query.trim();
  if (!trimmed) return [];

  const limit = options?.limit ?? 10;
  const overlay = getOverlay(book, options);
  const streetById = new Map(book.streets.map((s) => [s.id, s]));

  // Pre-compute active street info per streetId
  const streetMeta = new Map<
    string,
    { activeFull: string; activeBase: string; historical: string[]; direction?: CardinalDir }
  >();
  for (const s of book.streets) {
    const activeFull = streetNameIn(s, year);
    const activeBase = baseStreetNameIn(s, year);
    const historical = allKnownStreetNames(s).filter((n) => n !== activeFull);
    streetMeta.set(s.id, { activeFull, activeBase, historical, direction: s.direction });
  }

  // 1. Check for relational query: "<what> on/at/near/by/off <street>"
  const relMatch = trimmed.match(/^(?:the\s+|a\s+|an\s+)?(.+?)\s+(?:on|at|near|by|off|along)\s+(.+)$/i);
  const parsedAddr = !relMatch ? parseStreetQuery(trimmed) : null;
  const isPurePostal = /^\d{5}(?:-\d{4})?$/.test(trimmed);
  const queryTokens = tokenize(trimmed, true);

  const scored: FindMatch[] = [];

  for (const b of book.buildings) {
    if (!options?.includeInactive && !isStandingInYear(b, year, overlay)) continue;
    const street = streetById.get(b.streetId);
    if (!street) continue;
    const sm = streetMeta.get(street.id)!;
    const label = buildingLabelInYear(b, year, overlay);
    const simTerms = overlay?.terms[b.id] ?? [];
    const postal = postalCodeFor(book, b.district, year);

    let bestScore = 0;
    let matchKind: MatchKind = 'fuzzy';
    const matchedOn: string[] = [];
    let resolvedUnit: string | undefined = options?.unit;

    // Helper to score a street query against this building's street (active + historical)
    const matchStreet = (tokens: readonly string[], wantDir?: CardinalDir): { score: number; exact: boolean; prefix: boolean; via: string } => {
      if (!tokens.length) return { score: 0, exact: false, prefix: false, via: '' };
      const fullRes = phraseMatchScore(tokens, sm.activeFull);
      const baseRes = phraseMatchScore(tokens, sm.activeBase);
      let best = baseRes.score >= fullRes.score ? { ...baseRes, via: 'street' } : { ...fullRes, via: 'street' };
      // Drop generic street suffix from both if user typed "Main" vs "Main Street"
      const baseNoSuffix = tokenize(sm.activeBase, true).filter((t) => !Object.values(SUFFIX_MAP).includes(t));
      const qNoSuffix = tokens.filter((t) => !Object.values(SUFFIX_MAP).includes(t));
      if (qNoSuffix.length && baseNoSuffix.length) {
        const coreRes = phraseMatchScore(qNoSuffix, baseNoSuffix.join(' '));
        if (coreRes.score * 0.96 > best.score) {
          best = { score: coreRes.score * 0.96, exact: false, prefix: coreRes.exact || coreRes.prefix, via: 'street' };
        }
      }
      // Check historical street names
      for (const oldName of sm.historical) {
        const oldBase = oldName.replace(/^(?:[NSEW]\.\s+|(?:North|South|East|West)\s+)/i, '');
        const r1 = phraseMatchScore(tokens, oldName);
        const r2 = phraseMatchScore(tokens, oldBase);
        const hr = r1.score >= r2.score ? r1 : r2;
        if (hr.score * 0.88 > best.score) {
          best = { score: hr.score * 0.88, exact: false, prefix: hr.prefix, via: 'historical-street' };
        }
      }
      // Direction match bonus / mismatch penalty
      if (wantDir) {
        if (sm.direction === wantDir) best.score = Math.min(1, best.score + 0.06);
        else if (sm.direction && sm.direction !== wantDir) best.score *= 0.65;
      }
      return best;
    };

    // Helper to score a building/business/role/resident description
    const matchThing = (tokens: readonly string[]): { score: number; via: string; isResident: boolean } => {
      if (!tokens.length) return { score: 0, via: '', isResident: false };
      let best = { score: 0, via: '', isResident: false };

      const labelRes = phraseMatchScore(tokens, label);
      if (labelRes.score > best.score) best = { score: labelRes.score, via: 'label', isResident: false };

      // Check all era labels if active label didn't fully match
      for (const el of b.eraLabels ?? []) {
        const r = phraseMatchScore(tokens, el.label);
        if (r.score * 0.85 > best.score) best = { score: r.score * 0.85, via: 'era-label', isResident: false };
      }

      // Check searchable roles
      for (const role of b.roles) {
        const r = phraseMatchScore(tokens, role);
        if (r.score * 0.94 > best.score) best = { score: r.score * 0.94, via: 'role', isResident: false };
      }

      // Check sim business & resident names
      for (const term of simTerms) {
        const r = phraseMatchScore(tokens, term);
        if (r.score * 0.97 > best.score) best = { score: r.score * 0.97, via: 'resident', isResident: true };
      }
      return best;
    };

    if (isPurePostal) {
      if (postal && postal === trimmed.slice(0, 5)) {
        bestScore = 90;
        matchKind = 'postal';
        matchedOn.push('postal');
      }
    } else if (relMatch) {
      const whatTokens = tokenize(relMatch[1]!, true);
      const rawStreetTokens = tokenize(relMatch[2]!, false);
      let wantDir: CardinalDir | undefined;
      let stTokens = rawStreetTokens;
      if (rawStreetTokens.length >= 2 && DIR_MAP[rawStreetTokens[0]!]) {
        wantDir = DIR_MAP[rawStreetTokens[0]!];
        stTokens = rawStreetTokens.slice(1);
      }
      const thingHit = matchThing(whatTokens);
      const streetHit = matchStreet(stTokens, wantDir);
      if (thingHit.score >= 0.55 && streetHit.score >= 0.55) {
        bestScore = Math.round((thingHit.score * 58 + streetHit.score * 42) * 100) / 100;
        matchKind = 'relational';
        matchedOn.push(thingHit.via, streetHit.via);
      }
    } else if (parsedAddr) {
      const streetHit = parsedAddr.streetTokens.length
        ? matchStreet(parsedAddr.streetTokens, parsedAddr.direction)
        : { score: 0.75, exact: false, prefix: false, via: 'number-only' };

      const numExact = b.number === parsedAddr.number;
      const suffixMatch =
        parsedAddr.suffix === undefined
          ? !b.numberSuffix
          : b.numberSuffix === parsedAddr.suffix ||
            (parsedAddr.suffix === '1/2' && b.units.some((u) => u.label === '1/2'));

      if (parsedAddr.suffix === '1/2' && b.units.some((u) => u.label === '1/2') && !b.numberSuffix) {
        resolvedUnit = '1/2';
      } else if (parsedAddr.unit && b.units.length) {
        const normU = normalizeText(parsedAddr.unit);
        const hitU = b.units.find((u) => normalizeText(u.label).includes(normU) || normU.includes(normalizeText(u.label)));
        if (hitU) resolvedUnit = hitU.label;
      }

      if (numExact && streetHit.score >= 0.5) {
        let sc = 50 + streetHit.score * 46;
        if (suffixMatch) sc += 4;
        if (parsedAddr.postal && postal === parsedAddr.postal) sc += 2;
        else if (parsedAddr.postal && postal && postal !== parsedAddr.postal) sc -= 12;
        bestScore = Math.min(100, Math.round(sc * 100) / 100);
        matchKind = streetHit.exact && suffixMatch ? 'exact' : streetHit.prefix ? 'prefix' : 'fuzzy';
        matchedOn.push('number', streetHit.via);
        if (parsedAddr.suffix && suffixMatch) matchedOn.push('suffix');
      } else if (parsedAddr.streetTokens.length && streetHit.score >= 0.78 && Math.abs(b.number - (parsedAddr.number ?? 0)) <= 20) {
        // Near house number on the exact queried street (helpful GPS fallback)
        const distPenalty = Math.abs(b.number - parsedAddr.number!) * 1.1;
        bestScore = Math.max(0, Math.round((streetHit.score * 68 - distPenalty) * 100) / 100);
        matchKind = 'fuzzy';
        matchedOn.push('near-number', streetHit.via);
      }
    } else {
      // General search: building name / role / resident / street name / district / building ID
      if (b.id.toLowerCase() === trimmed.toLowerCase() || b.aliases.some((a) => a.toLowerCase() === trimmed.toLowerCase())) {
        bestScore = 100;
        matchKind = 'exact';
        matchedOn.push('id');
      } else {
        const thingHit = matchThing(queryTokens);
        const dirTok = queryTokens.length >= 2 ? DIR_MAP[queryTokens[0]!] : undefined;
        const stTokens = dirTok ? queryTokens.slice(1) : queryTokens;
        const streetHit = matchStreet(stTokens, dirTok);
        const distHit = phraseMatchScore(queryTokens, b.districtName);

        if (thingHit.score >= 0.58) {
          bestScore = Math.round(thingHit.score * 96 * 100) / 100;
          matchKind = thingHit.isResident ? 'resident' : thingHit.score >= 0.99 ? 'exact' : thingHit.score >= 0.88 ? 'prefix' : 'fuzzy';
          matchedOn.push(thingHit.via);
        }
        if (streetHit.score >= 0.62) {
          const stScore = Math.round(streetHit.score * 84 * 100) / 100;
          if (stScore > bestScore) {
            bestScore = stScore;
            matchKind = streetHit.exact ? 'exact' : streetHit.prefix ? 'prefix' : 'fuzzy';
            matchedOn.length = 0;
            matchedOn.push(streetHit.via);
          }
        }
        if (distHit.score >= 0.78) {
          const dScore = Math.round(distHit.score * 65 * 100) / 100;
          if (dScore > bestScore) {
            bestScore = dScore;
            matchKind = 'prefix';
            matchedOn.length = 0;
            matchedOn.push('district');
          }
        }
      }
    }

    if (bestScore >= 45) {
      const addr = materializeAddress(book, b, street, year, { unit: resolvedUnit, overlay });
      scored.push({ address: addr, score: bestScore, matchKind, matchedOn });
    }
  }

  scored.sort(
    (a, b) =>
      b.score - a.score ||
      a.address.streetName.localeCompare(b.address.streetName) ||
      a.address.number - b.address.number ||
      a.address.buildingId.localeCompare(b.address.buildingId),
  );
  return scored.slice(0, limit);
}

/** Find the nearest standing building's address AND the interpolated street address at world/plan position (x, y). */
export function nearestAddress(
  book: TownAddressBook,
  pos: { x: number; y: number },
  year: number,
  options?: LookupOptions,
): NearestResult {
  const overlay = getOverlay(book, options);
  const streetById = new Map(book.streets.map((s) => [s.id, s]));

  let bestBuilding: BuildingEntry | null = null;
  let bestDist = Infinity;

  for (const b of book.buildings) {
    if (!options?.includeInactive && !isStandingInYear(b, year, overlay)) continue;
    const d = Math.hypot(b.position.x - pos.x, b.position.y - pos.y);
    if (d < bestDist - 1e-6 || (Math.abs(d - bestDist) <= 1e-6 && bestBuilding && b.id < bestBuilding.id)) {
      bestDist = d;
      bestBuilding = b;
    }
  }

  let address: Address | null = null;
  if (bestBuilding) {
    const st = streetById.get(bestBuilding.streetId);
    if (st) address = materializeAddress(book, bestBuilding, st, year, { overlay });
  }

  // Project onto nearest street standing in [year] for interpolated GPS address
  let bestStreet: StreetRecord | null = null;
  let bestProj: ReturnType<typeof projectOntoStreet> | null = null;
  for (const s of book.streets) {
    if (!options?.includeInactive && (year < s.builtYear || (s.goneYear !== undefined && year >= s.goneYear))) continue;
    const p = projectOntoStreet(s, pos.x, pos.y);
    if (!bestProj || p.distance < bestProj.distance - 1e-6) {
      bestStreet = s;
      bestProj = p;
    }
  }

  let interpolated: NearestResult['interpolated'] = null;
  if (bestStreet && bestProj) {
    const activeName = streetNameIn(bestStreet, year);
    const { number, parity } = coordinateHouseNumber(bestStreet, bestProj.along, bestProj.sideOffset);
    const houseNumber = String(number);
    interpolated = {
      streetId: bestStreet.id,
      streetName: activeName,
      houseNumber,
      number,
      parity,
      line1: `${houseNumber} ${activeName}`,
      distanceToStreet: Math.round(bestProj.distance * 100) / 100,
    };
  }

  return {
    address,
    distance: bestBuilding ? Math.round(bestDist * 100) / 100 : Infinity,
    interpolated,
  };
}
