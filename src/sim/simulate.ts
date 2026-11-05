import { advanceSettlement } from '../town/properties';
import { BUSINESS_KINDS } from './names';
import { pick, rand, rngFor } from './rng';
import {
  ageIn, fullName, isAlive,
  type Business, type Id, type Intervention, type Person, type SimEvent, type Timeline, type World,
} from './types';
import { affinity, clampRep, pairKey } from './social';
import { generateWorld, makePerson } from './worldgen';

const byId = (a: { id: Id }, b: { id: Id }): number => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
const living = (w: World): Person[] => Object.values(w.people).filter(isAlive).sort(byId);
const openBusinesses = (w: World): Business[] =>
  Object.values(w.businesses).filter((b) => b.closedYear === undefined).sort(byId);

interface Ctx {
  w: World;
  y: number;
  log: SimEvent[];
}
const emit = (c: Ctx, kind: SimEvent['kind'], text: string, subject?: Id): void => {
  c.log.push({ year: c.y, kind, text, subject });
};

// ---------- death & inheritance ----------

function deathChance(age: number): number {
  return Math.min(1, 0.0006 + 0.00002 * Math.exp(0.095 * age));
}

function die(c: Ctx, p: Person, cause: 'natural' | 'murdered'): void {
  p.deathYear = c.y;
  p.deathCause = cause;
  emit(c, 'death', `${fullName(p)} (${ageIn(p, c.y)}) ${cause === 'murdered' ? 'was murdered' : 'died'}`, p.id);

  const spouse = p.spouse ? c.w.people[p.spouse] : undefined;
  if (spouse && isAlive(spouse)) spouse.spouse = undefined;

  const kids = p.children.map((id) => c.w.people[id]!).filter(isAlive).sort(byId);

  // Estate: split among living children, else surviving spouse, else lost.
  if (p.wealth > 0) {
    const heirs = kids.length ? kids : spouse && isAlive(spouse) ? [spouse] : [];
    for (const h of heirs) h.wealth += p.wealth / heirs.length;
  }
  p.wealth = 0;

  // Businesses pass to the eldest adult child, else the spouse, else close.
  for (const b of openBusinesses(c.w).filter((b) => b.owner === p.id)) {
    const adults = kids.filter((k) => ageIn(k, c.y) >= 18).sort((a, z) => a.birthYear - z.birthYear || byId(a, z));
    const heir = adults[0] ?? (spouse && isAlive(spouse) ? spouse : undefined);
    if (heir) {
      b.owner = heir.id;
    } else {
      b.closedYear = c.y;
      b.owner = undefined;
      emit(c, 'business_closed', `${b.name} closed (no heir)`, b.id);
    }
  }
  if (c.w.mayor === p.id) c.w.mayor = undefined;
}

// ---------- interventions ----------

function applyIntervention(c: Ctx, iv: Intervention): void {
  const fail = (why: string): void => emit(c, 'intervention_failed', why);
  const person = (id: Id): Person | undefined => {
    const p = c.w.people[id];
    return p && isAlive(p) ? p : undefined;
  };
  const business = (id: Id): Business | undefined => {
    const b = c.w.businesses[id];
    return b && b.closedYear === undefined ? b : undefined;
  };

  switch (iv.kind) {
    case 'kill': {
      const t = person(iv.targetId);
      if (!t) return fail(`Nobody to kill: ${iv.targetId} is not alive in ${c.y}`);
      emit(c, 'intervention', `Time traveler murders ${fullName(t)}`, t.id);
      die(c, t, 'murdered');
      return;
    }
    case 'invest': {
      const b = business(iv.businessId);
      if (!b) return fail(`No open business ${iv.businessId} in ${c.y}`);
      b.capital += iv.amount;
      emit(c, 'intervention', `Time traveler invests ${iv.amount} in ${b.name}`, b.id);
      return;
    }
    case 'sabotage': {
      const b = business(iv.businessId);
      if (!b) return fail(`No open business ${iv.businessId} in ${c.y}`);
      b.capital *= 0.4;
      emit(c, 'intervention', `Time traveler sabotages ${b.name}`, b.id);
      return;
    }
    case 'introduce': {
      const a = person(iv.a);
      const b = person(iv.b);
      if (!a || !b || a.id === b.id) return fail(`Cannot introduce ${iv.a} and ${iv.b} in ${c.y}`);
      const k = pairKey(a.id, b.id);
      c.w.bonds[k] = (c.w.bonds[k] ?? 0) + 1;
      emit(c, 'intervention', `Time traveler introduces ${fullName(a)} to ${fullName(b)}`, a.id);
      return;
    }
    case 'rumor': {
      const t = person(iv.targetId);
      if (!t) return fail(`Nobody to talk about: ${iv.targetId} is not alive in ${c.y}`);
      t.reputation = clampRep(t.reputation + iv.amount);
      emit(c, 'intervention', `Time traveler ${iv.amount < 0 ? 'spreads rumors about' : 'sings the praises of'} ${fullName(t)}`, t.id);
      return;
    }
    case 'gift': {
      const t = person(iv.targetId);
      if (!t) return fail(`Nobody to give to: ${iv.targetId} is not alive in ${c.y}`);
      t.wealth += Math.max(0, iv.amount);
      emit(c, 'intervention', `Time traveler gives ${iv.amount} to ${fullName(t)}`, t.id);
      return;
    }
  }
}

// ---------- yearly rules ----------

function economy(c: Ctx): void {
  const { w, y } = c;
  const seed = w.seed;
  for (const b of openBusinesses(w)) {
    const owner = b.owner ? w.people[b.owner] : undefined;
    const skill = owner ? (owner.traits.diligence - 0.5) * 0.08 : 0;
    const growth = -0.06 + rand(seed, y, 'biz', b.id) * 0.26 + skill;
    const profit = b.capital * growth;
    if (profit >= 0) {
      b.capital += profit * 0.6;
      if (owner) owner.wealth += profit * 0.4;
    } else {
      b.capital += profit;
    }
    if (b.capital < 5) {
      b.closedYear = y;
      emit(c, 'business_closed', `${b.name} went bankrupt`, b.id);
    }
  }
  for (const p of living(w)) {
    const age = ageIn(p, y);
    if (age >= 18) p.wealth = Math.max(0, p.wealth + (age <= 65 ? 3 : 1) + (rand(seed, y, 'wage', p.id) - 0.5) * 2);
  }
}

function marriages(c: Ctx): void {
  const { w, y } = c;
  const seed = w.seed;
  const women = living(w).filter((p) => p.sex === 'F' && !p.spouse && ageIn(p, y) >= 20 && ageIn(p, y) <= 38);
  for (const wife of women) {
    if (rand(seed, y, 'marry', wife.id) >= 0.3) continue;
    const suitors = living(w).filter(
      (m) =>
        m.sex === 'M' && !m.spouse &&
        ageIn(m, y) >= 20 && ageIn(m, y) <= 45 &&
        Math.abs(m.birthYear - wife.birthYear) <= 12 &&
        !m.parents.some((pid) => wife.parents.includes(pid)),
    );
    if (!suitors.length) continue;
    const appeal = (m: Person): number =>
      affinity(w, wife.id, m.id) + (m.reputation + wife.reputation) * 0.3 +
      rand(seed, y, 'suit', wife.id, m.id) * 0.4;
    const husband = suitors.reduce((best, m) => (appeal(m) > appeal(best) ? m : best));
    wife.spouse = husband.id;
    husband.spouse = wife.id;
    emit(c, 'marriage', `${fullName(husband)} married ${fullName(wife)}`, wife.id);
  }
}

function births(c: Ctx): void {
  const { w, y } = c;
  const seed = w.seed;
  // Deliberately NOT a function of current population: global feedback would let one
  // missing person nudge every unrelated family's dice. Only a rare hard cap remains.
  const crowd = living(w).length > 450 ? 0.3 : 1;
  for (const mom of living(w).filter((p) => p.sex === 'F' && p.spouse)) {
    const age = ageIn(mom, y);
    const dad = w.people[mom.spouse!]!;
    if (age < 18 || age > 40 || !isAlive(dad)) continue;
    if (rand(seed, y, 'birth', mom.id) >= 0.14 * crowd) continue;
    const id = `${mom.id}>${y}`;
    const child = makePerson(seed, id, y, dad.surname, [dad, mom]);
    w.people[id] = child;
    mom.children.push(id);
    dad.children.push(id);
    emit(c, 'birth', `${fullName(child)} born to ${fullName(mom)} and ${fullName(dad)}`, id);
  }
}

function immigration(c: Ctx): void {
  const { w, y } = c;
  const seed = w.seed;
  if (living(w).length > 500) return;
  const roll = rand(seed, y, 'immig-n');
  const n = roll < 0.5 ? 0 : roll < 0.9 ? 1 : 2;
  for (let i = 0; i < n; i++) {
    const id = `imm:${y}:${i}`;
    const age = 18 + Math.floor(rand(seed, id, 'age') * 28);
    const r = rngFor(seed, id, 'surname');
    const p = makePerson(seed, id, y - age, pick(r, ['Novak', 'Reyes', 'Chen', 'Haddad', 'Larsen', 'Moreau', 'Singh', 'Kowalski']));
    p.wealth = 10 + Math.floor(rand(seed, id, 'wealth') * 20);
    w.people[id] = p;
    emit(c, 'immigration', `${fullName(p)} (${age}) moved to town`, id);
  }
}

function foundings(c: Ctx): void {
  const { w, y } = c;
  const seed = w.seed;
  const open = openBusinesses(w);
  // Town business capacity grows with the calendar, not with headcount (see births()).
  const capacity = 8 + Math.floor((y - 1950) / 6);
  if (open.length >= capacity) return;
  const owners = new Set(open.map((b) => b.owner));
  for (const p of living(w)) {
    const age = ageIn(p, y);
    if (age < 25 || age > 60 || owners.has(p.id) || p.wealth < 20) continue;
    if (rand(seed, y, 'found', p.id) >= 0.04 * p.traits.ambition * 2) continue;
    const kind = pick(rngFor(seed, y, 'found-kind', p.id), BUSINESS_KINDS);
    const b: Business = {
      id: `b:${p.id}:${y}`,
      name: `${p.surname}'s ${kind}`,
      kind,
      foundedYear: y,
      owner: p.id,
      capital: p.wealth * 0.6,
    };
    p.wealth *= 0.4;
    w.businesses[b.id] = b;
    owners.add(p.id);
    emit(c, 'business_founded', `${fullName(p)} opened ${b.name}`, b.id);
    if (openBusinesses(w).length >= capacity) break;
  }
}

/** Mayor elections every 4 years, or immediately if the office is vacant. */
export function holdElection(c: Ctx): void {
  const { w, y } = c;
  const seed = w.seed;
  const score = (p: Person): number =>
    p.traits.ambition * 0.4 + p.traits.charm * 0.4 + Math.log10(1 + p.wealth) * 0.1 +
    (w.mayor === p.id ? 0.1 : 0) + rand(seed, y, 'vote', p.id) * 0.2;
  const adults = living(w).filter((p) => ageIn(p, y) >= 18);
  // Only the strongest contenders get the (costlier) popular-support calculation.
  const field = adults
    .filter((p) => ageIn(p, y) >= 30 && ageIn(p, y) <= 75)
    .sort((a, b) => score(b) - score(a) || byId(a, b))
    .slice(0, 10);
  if (!field.length) return;
  const support = (p: Person): number => {
    const voters = adults.filter((v) => v.id !== p.id);
    return voters.length ? voters.reduce((s, v) => s + affinity(w, v.id, p.id), 0) / voters.length : 0;
  };
  const total = (p: Person): number => score(p) + p.reputation * 0.3 + support(p) * 0.5;
  const winner = field.reduce((best, p) => (total(p) > total(best) ? p : best));
  if (winner.id !== w.mayor) {
    w.mayor = winner.id;
    emit(c, 'election', `${fullName(winner)} elected mayor`, winner.id);
  }
}

function step(c: Ctx, interventions: Intervention[]): void {
  for (const iv of interventions) applyIntervention(c, iv);
  for (const p of living(c.w)) {
    if (rand(c.w.seed, c.y, 'death', p.id) < deathChance(ageIn(p, c.y))) die(c, p, 'natural');
  }
  economy(c);
  marriages(c);
  births(c);
  immigration(c);
  foundings(c);
  if (c.y % 4 === 0 || c.w.mayor === undefined) holdElection(c);
}

// ---------- public API ----------

export interface RunOptions {
  seed: string;
  endYear: number;
  startYear?: number;
  interventions?: Intervention[];
}

/** Simulates the town from startYear to endYear, applying time-traveler interventions. */
export function runTimeline(opts: RunOptions): Timeline {
  const startYear = opts.startYear ?? 1950;
  const world = generateWorld(opts.seed, startYear);
  const chronicle: SimEvent[] = [];
  const ivs = [...(opts.interventions ?? [])].sort((a, b) => a.year - b.year);

  holdElection({ w: world, y: startYear, log: chronicle });
  advanceSettlement(world);
  const snapshots: World[] = [structuredClone(world)];

  for (let y = startYear + 1; y <= opts.endYear; y++) {
    world.year = y;
    const c: Ctx = { w: world, y, log: chronicle };
    // Interventions dated before the sim starts are applied in the first simulated year.
    const todays = ivs.filter((iv) => iv.year === y || (y === startYear + 1 && iv.year <= startYear));
    step(c, todays);
    advanceSettlement(world);
    snapshots.push(structuredClone(world));
  }
  return { seed: opts.seed, startYear, endYear: opts.endYear, snapshots, chronicle };
}

export const worldAt = (t: Timeline, year: number): World => {
  const w = t.snapshots[year - t.startYear];
  if (!w) throw new Error(`year ${year} outside timeline ${t.startYear}-${t.endYear}`);
  return w;
};

