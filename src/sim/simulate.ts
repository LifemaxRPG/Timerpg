import { recipeOf, tradeOf } from '../town/recipe';
import { advanceSettlement } from '../town/properties';
import { archetypeOf, pickShop } from './archetype';
import { runScenarios } from './scenarios';
import { BUSINESS_KINDS } from './names';
import { pick, rand, rngFor } from './rng';
import {
  ageIn, fullName, isAlive,
  type Business, type Id, type Intervention, type Person, type SimEvent, type Timeline, type World,
} from './types';
import { affinity, clampRep, pairKey } from './social';
import { generateWorld, makePerson } from './worldgen';

const byId = (a: { id: Id }, b: { id: Id }): number => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

// Zero-allocation equivalent of `rand(...parts)` (same xmur3 + mulberry32 math as ./rng.ts)
// used in hot yearly loops (death, wages, marriages, births, votes) to avoid array/closure churn.
function randKey(str: string): number {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(h ^ str.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  h = Math.imul(h ^ (h >>> 16), 2246822507);
  h = Math.imul(h ^ (h >>> 13), 3266489909);
  const a = (h ^= h >>> 16) >>> 0;
  const a2 = (a + 0x6d2b79f5) | 0;
  let t = Math.imul(a2 ^ (a2 >>> 15), 1 | a2);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const living = (w: World): Person[] => Object.values(w.people).filter(isAlive).sort(byId);
const openBusinesses = (w: World): Business[] =>
  Object.values(w.businesses).filter((b) => b.closedYear === undefined).sort(byId);

// Bounded per-seed cache of baseline pair affinity (`rand(seed, 'affinity', pairKey) - 0.5`).
// Baseline affinity is a pure function of (seed, pair) and never changes across years or timelines.
const MAX_AFFINITY_SEEDS = 8;
const seedAffinityCache = new Map<string, Map<Id, Map<Id, number>>>();

function baseAffinity(seed: string, aId: Id, bId: Id): number {
  let bySeed = seedAffinityCache.get(seed);
  if (!bySeed) {
    bySeed = new Map();
    if (seedAffinityCache.size >= MAX_AFFINITY_SEEDS) {
      const oldest = seedAffinityCache.keys().next().value;
      if (oldest !== undefined) seedAffinityCache.delete(oldest);
    }
    seedAffinityCache.set(seed, bySeed);
  }
  const lo = aId < bId ? aId : bId;
  const hi = aId < bId ? bId : aId;
  let inner = bySeed.get(lo);
  if (!inner) {
    inner = new Map();
    bySeed.set(lo, inner);
  }
  let val = inner.get(hi);
  if (val === undefined) {
    val = randKey(`${seed}|affinity|${lo}|${hi}`) - 0.5;
    inner.set(hi, val);
  }
  return val;
}

/**
 * Fast, immutable snapshot clone of a `World` (~12x faster than `structuredClone` on large towns).
 * `settlement` is immutable between `advanceSettlement` calls (which always allocate fresh arrays/lots)
 * and `arson` replaces `w.settlement` non-destructively; `open` stories are shallow-cloned so future
 * sequels setting `pick.open = false` never mutate earlier snapshots.
 */
function cloneWorld(w: World, includeSettlement = true, copySettlementArrays = false): World {
  const people: Record<Id, Person> = {};
  for (const k in w.people) {
    const p = w.people[k]!;
    people[k] = {
      ...p,
      children: p.children.slice(),
    };
  }
  const businesses: Record<Id, Business> = {};
  for (const k in w.businesses) {
    businesses[k] = { ...w.businesses[k]! };
  }
  const out: World = {
    seed: w.seed,
    year: w.year,
    people,
    businesses,
    bonds: { ...w.bonds },
  };
  if (w.mayor !== undefined) out.mayor = w.mayor;
  if (includeSettlement && w.settlement !== undefined) {
    out.settlement = copySettlementArrays
      ? { properties: w.settlement.properties.map((l) => ({ ...l, heads: l.heads.slice(), residents: l.residents.slice() })), roads: w.settlement.roads.slice() }
      : w.settlement;
  }
  if (w.stories !== undefined) {
    out.stories = w.stories.map((st) => (st.open ? { ...st } : st));
  }
  return out;
}

interface Ctx {
  w: World;
  y: number;
  log: SimEvent[];
  burnedSlots?: Set<string>;
  burnedBusinessIds?: Set<Id>;
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
    case 'arson': {
      const b = business(iv.businessId);
      if (!b) return fail(`No open business ${iv.businessId} in ${c.y}`);
      const owner = b.owner ? person(b.owner) : undefined;
      b.closedYear = c.y;
      b.capital = 0;

      // Single chronicle event for the burned store.
      emit(c, 'business_closed', `${b.name} burned down`, b.id);

      // Leave the lot empty so normal settlement rules can rebuild on it.
      c.burnedBusinessIds?.add(b.id);
      if (c.w.settlement) {
        const burnedLots = c.w.settlement.properties.filter((l) => l.kind === 'shop' && l.refId === b.id);
        if (burnedLots.length > 0) {
          for (const l of burnedLots) c.burnedSlots?.add(l.slot);
          c.w.settlement = {
            ...c.w.settlement,
            properties: c.w.settlement.properties.filter((l) => !(l.kind === 'shop' && l.refId === b.id)),
          };
        }
      }

      // At night (default true unless explicitly set to false), people inside can be hurt or killed by seeded chance.
      const atNight = iv.atNight ?? true;
      const injured: Person[] = [];
      const killed: Person[] = [];
      if (atNight && owner) {
        const inside: Person[] = [owner];
        if (owner.spouse) {
          const sp = person(owner.spouse);
          if (sp) inside.push(sp);
        }
        for (const cid of owner.children) {
          const kid = person(cid);
          if (kid && ageIn(kid, c.y) >= 16 && !kid.spouse) inside.push(kid);
        }
        for (const p of inside) {
          if (!isAlive(p)) continue;
          const roll = rand(c.w.seed, c.y, 'arson-casualty', b.id, p.id);
          if (roll < 0.12) {
            die(c, p, 'murdered');
            killed.push(p);
          } else if (roll < 0.37) {
            p.wealth = Math.max(0, p.wealth * 0.75 - 5);
            injured.push(p);
          }
        }
      }

      // Report the fire in the town Gazette archive so the newspaper reads "<Store> burns down".
      c.w.stories ??= [];
      const storyPeople: Id[] = [];
      if (owner) storyPeople.push(owner.id);
      for (const p of [...killed, ...injured]) {
        if (!storyPeople.includes(p.id)) storyPeople.push(p.id);
      }
      const casualtyLine =
        killed.length > 0
          ? ` ${killed.map(fullName).join(' and ')} perished in the blaze.`
          : injured.length > 0
            ? ` ${injured.map(fullName).join(' and ')} escaped with burns and smoke inhalation.`
            : ' No one was hurt, though nothing could be saved.';
      c.w.stories.push({
        id: `${c.y}:arson:${b.id}`,
        year: c.y,
        template: 'arson',
        category: 'crime',
        headline: `${b.name} burns down`,
        body: `Fire gutted ${b.name} ${atNight ? 'overnight' : 'in daylight'}, leaving an empty lot where the storefront stood.${casualtyLine}`,
        people: storyPeople,
        business: b.id,
        open: true,
      });
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

function economy(c: Ctx, aliveList: Person[]): void {
  const { w, y } = c;
  const seed = w.seed;
  const tradePenalty = Math.max(0, 1 - tradeOf(seed, y)) * 0.25;
  const wealthMult = archetypeOf(seed).wealth;
  for (const b of openBusinesses(w)) {
    const owner = b.owner ? w.people[b.owner] : undefined;
    const skill = owner ? (owner.traits.diligence - 0.5) * 0.08 : 0;
    // (a bypassed Main Street's lost passing trade: tradeOf below 1 for a world place's town, src/town/recipe.ts)
    const growth = -0.06 + randKey(`${seed}|${y}|biz|${b.id}`) * 0.26 + skill - tradePenalty;
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
  for (let i = 0; i < aliveList.length; i++) {
    const p = aliveList[i]!;
    if (!isAlive(p)) continue;
    const age = ageIn(p, y);
    if (age >= 18) p.wealth = Math.max(0, p.wealth + ((age <= 65 ? 3 : 1) + (randKey(`${seed}|${y}|wage|${p.id}`) - 0.5) * 2) * wealthMult);
  }
}

function marriages(c: Ctx, aliveList: Person[]): void {
  const { w, y } = c;
  const seed = w.seed;
  const menPool: Person[] = [];
  for (let i = 0; i < aliveList.length; i++) {
    const m = aliveList[i]!;
    if (m.sex === 'M' && !m.spouse) {
      const age = ageIn(m, y);
      if (age >= 20 && age <= 45) menPool.push(m);
    }
  }
  for (let i = 0; i < aliveList.length; i++) {
    const wife = aliveList[i]!;
    if (wife.sex !== 'F' || wife.spouse) continue;
    const age = ageIn(wife, y);
    if (age < 20 || age > 38) continue;
    if (randKey(`${seed}|${y}|marry|${wife.id}`) >= 0.3) continue;
    let husband: Person | undefined;
    let bestAppeal = -Infinity;
    for (let j = 0; j < menPool.length; j++) {
      const m = menPool[j]!;
      if (m.spouse) continue;
      if (Math.abs(m.birthYear - wife.birthYear) > 12) continue;
      if (m.parents.some((pid) => wife.parents.includes(pid))) continue;
      const app =
        affinity(w, wife.id, m.id) + (m.reputation + wife.reputation) * 0.3 +
        randKey(`${seed}|${y}|suit|${wife.id}|${m.id}`) * 0.4;
      if (app > bestAppeal) {
        bestAppeal = app;
        husband = m;
      }
    }
    if (!husband) continue;
    wife.spouse = husband.id;
    husband.spouse = wife.id;
    emit(c, 'marriage', `${fullName(husband)} married ${fullName(wife)}`, wife.id);
  }
}

function births(c: Ctx, aliveList: Person[]): number {
  const { w, y } = c;
  const seed = w.seed;
  // Deliberately NOT a function of current population: global feedback would let one
  // missing person nudge every unrelated family's dice. Only a rare hard cap remains.
  const crowd = aliveList.length > 450 ? 0.3 : 1;
  let bornCount = 0;
  for (let i = 0; i < aliveList.length; i++) {
    const mom = aliveList[i]!;
    if (mom.sex !== 'F' || !mom.spouse) continue;
    const age = ageIn(mom, y);
    const dad = w.people[mom.spouse]!;
    if (age < 18 || age > 40 || !isAlive(dad)) continue;
    if (randKey(`${seed}|${y}|birth|${mom.id}`) >= 0.14 * crowd) continue;
    const id = `${mom.id}>${y}`;
    const child = makePerson(seed, id, y, dad.surname, [dad, mom]);
    w.people[id] = child;
    mom.children.push(id);
    dad.children.push(id);
    bornCount += 1;
    emit(c, 'birth', `${fullName(child)} born to ${fullName(mom)} and ${fullName(dad)}`, id);
  }
  return bornCount;
}

function immigration(c: Ctx, livingCount: number): number {
  const { w, y } = c;
  const seed = w.seed;
  if (livingCount > 500) return 0;
  // The town's character sets how many people move in (a mining town booms, then empties).
  const arch = archetypeOf(seed);
  const roll = Math.pow(rand(seed, y, 'immig-n'), 1 / arch.immigration(y));
  let n = roll < 0.5 ? 0 : roll < 0.9 ? 1 : 2;
  // (a world place's recipe scales the town's draw: a hamlet a fraction of a town's newcomers, a hub more)
  const g = recipeOf(seed)?.people.growth;
  if (g !== undefined) n = g < 1 ? (rand(seed, y, 'immig-g') < g ? n : 0) : n + (rand(seed, y, 'immig-g') < g - 1 ? 1 : 0);
  for (let i = 0; i < n; i++) {
    const id = `imm:${y}:${i}`;
    const age = 18 + Math.floor(rand(seed, id, 'age') * 28);
    const r = rngFor(seed, id, 'surname');
    const p = makePerson(seed, id, y - age, pick(r, ['Novak', 'Reyes', 'Chen', 'Haddad', 'Larsen', 'Moreau', 'Singh', 'Kowalski']));
    p.wealth = Math.round((10 + Math.floor(rand(seed, id, 'wealth') * 20)) * arch.wealth);
    w.people[id] = p;
    emit(c, 'immigration', `${fullName(p)} (${age}) moved to town`, id);
  }
  return n;
}

function foundings(c: Ctx, aliveList: Person[]): void {
  const { w, y } = c;
  const seed = w.seed;
  const open = openBusinesses(w);
  // Town business capacity grows with the calendar, not with headcount (see births()).
  const arch = archetypeOf(seed);
  const capacity = Math.round((8 + Math.floor((y - 1950) / 6)) * arch.commerce * (recipeOf(seed)?.people.growth ?? 1) * tradeOf(seed, y));
  if (open.length >= capacity) return;
  let openCount = open.length;
  const owners = new Set(open.map((b) => b.owner));
  for (let i = 0; i < aliveList.length; i++) {
    const p = aliveList[i]!;
    const age = ageIn(p, y);
    if (age < 25 || age > 60 || owners.has(p.id) || p.wealth < 20) continue;
    if (randKey(`${seed}|${y}|found|${p.id}`) >= 0.04 * p.traits.ambition * 2) continue;
    const kind = pickShop(arch, BUSINESS_KINDS, rand(seed, y, 'found-kind', p.id));
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
    openCount += 1;
    emit(c, 'business_founded', `${fullName(p)} opened ${b.name}`, b.id);
    if (openCount >= capacity) break;
  }
}

/** Mayor elections every 4 years, or immediately if the office is vacant. */
export function holdElection(c: Ctx, precomputedLiving?: Person[]): void {
  const { w, y } = c;
  const seed = w.seed;
  const allLiving = precomputedLiving ?? living(w);
  const adults: Person[] = [];
  const scored: { p: Person; score: number }[] = [];
  for (let i = 0; i < allLiving.length; i++) {
    const p = allLiving[i]!;
    const age = ageIn(p, y);
    if (age >= 18) {
      adults.push(p);
      if (age >= 30 && age <= 75) {
        const s =
          p.traits.ambition * 0.4 + p.traits.charm * 0.4 + Math.log10(1 + p.wealth) * 0.1 +
          (w.mayor === p.id ? 0.1 : 0) + randKey(`${seed}|${y}|vote|${p.id}`) * 0.2;
        scored.push({ p, score: s });
      }
    }
  }
  if (!scored.length) return;
  // Only the strongest contenders get the (costlier) popular-support calculation.
  scored.sort((a, b) => b.score - a.score || byId(a.p, b.p));
  const fieldLen = Math.min(10, scored.length);
  const denom = adults.length - 1;
  const hasBonds = Object.keys(w.bonds).length > 0;

  let winner = scored[0]!.p;
  let bestTotal = -Infinity;
  for (let fi = 0; fi < fieldLen; fi++) {
    const { p, score } = scored[fi]!;
    let sum = 0;
    if (denom > 0) {
      const pId = p.id;
      const pPar = p.parents;
      const pParLen = pPar.length;
      const pPar0 = pPar[0];
      const pPar1 = pPar[1];
      for (let ai = 0; ai < adults.length; ai++) {
        const v = adults[ai]!;
        const vId = v.id;
        if (vId === pId) continue;
        let a = baseAffinity(seed, vId, pId);
        const vPar = v.parents;
        if (
          v.spouse === pId ||
          (pParLen > 0 && (pPar0 === vId || pPar1 === vId)) ||
          (vPar.length > 0 && (
            vPar[0] === pId || vPar[1] === pId ||
            (pParLen > 0 && (
              vPar[0] === pPar0 || vPar[0] === pPar1 ||
              (vPar[1] !== undefined && (vPar[1] === pPar0 || vPar[1] === pPar1))
            ))
          ))
        ) {
          a += 0.5;
        }
        if (hasBonds) {
          a += w.bonds[pairKey(vId, pId)] ?? 0;
        }
        sum += a;
      }
    }
    const tot = score + p.reputation * 0.3 + (denom > 0 ? sum / denom : 0) * 0.5;
    if (tot > bestTotal) {
      bestTotal = tot;
      winner = p;
    }
  }
  if (winner.id !== w.mayor) {
    w.mayor = winner.id;
    emit(c, 'election', `${fullName(winner)} elected mayor`, winner.id);
  }
}

function step(c: Ctx, interventions: Intervention[]): void {
  for (const iv of interventions) applyIntervention(c, iv);
  const aliveBeforeDeaths = living(c.w);
  let diedInStep = false;
  for (let i = 0; i < aliveBeforeDeaths.length; i++) {
    const p = aliveBeforeDeaths[i]!;
    if (!isAlive(p)) continue;
    if (randKey(`${c.w.seed}|${c.y}|death|${p.id}`) < deathChance(ageIn(p, c.y))) {
      die(c, p, 'natural');
      diedInStep = true;
    }
  }
  const aliveForEcon = diedInStep ? aliveBeforeDeaths.filter(isAlive) : aliveBeforeDeaths;
  economy(c, aliveForEcon);
  let scenarioDied = false;
  runScenarios(c, (p, cause) => {
    die(c, p, cause);
    scenarioDied = true;
  });
  const aliveAfterScenarios = scenarioDied ? aliveForEcon.filter(isAlive) : aliveForEcon;
  marriages(c, aliveAfterScenarios);
  const born = births(c, aliveAfterScenarios);
  const immig = immigration(c, aliveAfterScenarios.length + born);
  const aliveAfterGrowth = born > 0 || immig > 0 ? living(c.w) : aliveAfterScenarios;
  foundings(c, aliveAfterGrowth);
  if (c.y % 4 === 0 || c.w.mayor === undefined) holdElection(c, aliveAfterGrowth);
}

// ---------- public API ----------

export interface RunOptions {
  seed: string;
  endYear: number;
  startYear?: number;
  interventions?: Intervention[];
  /**
   * Optional cached `World` snapshot to resume from instead of replaying untouched years.
   * Cloned internally so the caller's cached snapshot is never mutated.
   */
  checkpoint?: World;
  /** Year of `checkpoint` (defaults to `checkpoint.year`). */
  checkpointYear?: number;
  /**
   * When true, skips intermediate annual snapshot clones (returning only the final `endYear`
   * snapshot) and defers intermediate `advanceSettlement` passes until `endYear`.
   * Opt-in: omitting this preserves exact historical behavior.
   */
  onlyFinalSnapshot?: boolean;
  /**
   * Internal/opt-in mode: keeps lightweight per-year snapshots (people, businesses, bonds, stories)
   * while deferring `advanceSettlement` until `endYear` (or lazy materialization by callers).
   */
  deferSettlement?: boolean;
  /**
   * Optional baseline settlement at `endYear` used when `onlyFinalSnapshot` or `deferSettlement`
   * defers intermediate `advanceSettlement` passes, so untouched buildings keep their historical
   * `builtYear` while burned/altered lots rebuild via `advanceSettlement`.
   */
  settlementBaseline?: World['settlement'];
}

function applySettlementAtEnd(
  world: World,
  burnedSlots: Set<string>,
  burnedBusinessIds: Set<Id>,
  settlementBaseline?: World['settlement'],
): void {
  if (settlementBaseline) {
    world.settlement = {
      properties: settlementBaseline.properties
        .filter((l) => !(l.kind === 'shop' && (burnedSlots.has(l.slot) || burnedBusinessIds.has(l.refId))))
        .map((l) => ({ ...l, heads: l.heads.slice(), residents: l.residents.slice() })),
      roads: settlementBaseline.roads.slice(),
    };
  } else if (world.settlement && (burnedSlots.size > 0 || burnedBusinessIds.size > 0)) {
    world.settlement = {
      ...world.settlement,
      properties: world.settlement.properties.filter(
        (l) => !(l.kind === 'shop' && (burnedSlots.has(l.slot) || burnedBusinessIds.has(l.refId))),
      ),
    };
  }
  advanceSettlement(world);
}

/** Simulates the town from startYear (or checkpointYear) to endYear, applying time-traveler interventions. */
export function runTimeline(opts: RunOptions): Timeline {
  const onlyFinal = opts.onlyFinalSnapshot === true;
  const deferSettle = onlyFinal || opts.deferSettlement === true;
  const chronicle: SimEvent[] = [];
  const ivs = [...(opts.interventions ?? [])].sort((a, b) => a.year - b.year);
  const burnedSlots = new Set<string>();
  const burnedBusinessIds = new Set<Id>();

  let startYear: number;
  let resumeFromYear: number;
  let world: World;
  const snapshots: World[] = [];

  if (opts.checkpoint) {
    resumeFromYear = opts.checkpointYear ?? opts.checkpoint.year;
    startYear = opts.startYear ?? resumeFromYear;
    world = cloneWorld(opts.checkpoint, true, true);
    world.year = resumeFromYear;
    if (!onlyFinal) {
      snapshots.push(cloneWorld(world, !deferSettle));
    }
  } else {
    startYear = opts.startYear ?? 1950;
    resumeFromYear = startYear;
    world = generateWorld(opts.seed, startYear);
    holdElection({ w: world, y: startYear, log: chronicle });
    if (!deferSettle || opts.endYear <= startYear) {
      advanceSettlement(world);
    }
    if (!onlyFinal) {
      snapshots.push(cloneWorld(world, !deferSettle || opts.endYear <= startYear));
    }
  }

  for (let y = resumeFromYear + 1; y <= opts.endYear; y++) {
    world.year = y;
    const c: Ctx = { w: world, y, log: chronicle, burnedSlots, burnedBusinessIds };
    // Interventions dated before the sim starts are applied in the first simulated year.
    const todays = ivs.filter(
      (iv) => iv.year === y || (y === startYear + 1 && resumeFromYear === startYear && iv.year <= startYear),
    );
    step(c, todays);
    if (!deferSettle) {
      advanceSettlement(world);
      snapshots.push(cloneWorld(world, true));
    } else if (!onlyFinal && y < opts.endYear) {
      snapshots.push(cloneWorld(world, false));
    }
  }

  if (deferSettle) {
    if (opts.endYear > resumeFromYear || !world.settlement) {
      applySettlementAtEnd(world, burnedSlots, burnedBusinessIds, opts.settlementBaseline);
    }
    snapshots.push(onlyFinal ? world : cloneWorld(world, true));
  } else if (onlyFinal) {
    snapshots.push(world);
  }

  return { seed: opts.seed, startYear, endYear: opts.endYear, snapshots, chronicle };
}

/**
 * The living population each year of the town's natural history (no interventions), from startYear to endYear. The
 * housing never feeds back into the people (advanceSettlement only places them), so this runs without a town plan and
 * the plan can ask it (src/town/cinema.ts: only towns that grow big enough get a cinema).
 */
export function naturalPopulation(seed: string, endYear: number, startYear = 1950): number[] {
  const world = generateWorld(seed, startYear);
  const log: SimEvent[] = [];
  holdElection({ w: world, y: startYear, log });
  const alive = (): number => Object.values(world.people).filter(isAlive).length;
  const out = [alive()];
  for (let y = startYear + 1; y <= endYear; y++) {
    world.year = y;
    step({ w: world, y, log }, []);
    out.push(alive());
  }
  return out;
}

export const worldAt = (t: Timeline, year: number): World => {
  const direct = t.snapshots[year - t.startYear];
  if (direct && direct.year === year) return direct;
  const first = t.snapshots[0];
  if (first) {
    const byOffset = t.snapshots[year - first.year];
    if (byOffset && byOffset.year === year) return byOffset;
  }
  const found = t.snapshots.find((s) => s.year === year);
  if (found) return found;
  throw new Error(`year ${year} outside timeline ${t.startYear}-${t.endYear}`);
};
