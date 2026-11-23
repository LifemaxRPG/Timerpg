/**
 * The entry the phone runs (kotlin-street/tools/bundle-sim.mjs bundles it into assets/sim/sim.js; SimJs.kt calls it
 * through QuickJS-ng: kotlin-street/docs/LOOP.md, "The JavaScript bridge"). Every function takes one JSON string and
 * returns one JSON string; the state lives here, inside the JS context.
 *
 * TEMPORARY: until the loop facade (src/loop/index.ts, queue task G011) arrives, this exposes a smoke API over the
 * crime core so the bridge can be built and tested end to end: simCreate, simRecordCrime, simAdvance, simKnowers.
 * G011 replaces these internals (and these names) with init / act / observe / advance / query.
 */
import { rand } from '../sim/rng';
import type { Id, Person, World } from '../sim/types';
import {
  DEFAULT_SEVERITY,
  advanceDay,
  createCrimeState,
  knowersOf,
  recordCrime,
  witnessesOf,
  type Bystander,
  type CrimeContext,
  type CrimeEvent,
  type CrimeKind,
  type CrimeState,
  type CulpritAppearance,
} from '../crime';

interface Smoke {
  world: World;
  ctx: CrimeContext;
  state: CrimeState;
}

let smoke: Smoke | undefined;

function need(): Smoke {
  if (!smoke) throw new Error('simCreate first');
  return smoke;
}

/** A small synthetic town: `people` people, couples with a child every four, workplaces, one police jurisdiction. */
function makeWorld(seed: string, count: number, year: number): { world: World; ctx: CrimeContext } {
  const people: Record<Id, Person> = {};
  const workplace: Record<Id, string> = {};
  const ids: Id[] = [];
  for (let i = 0; i < count; i++) {
    const id = `p:${String(i).padStart(4, '0')}`;
    const age = 18 + Math.floor(rand(seed, 'age', id) * 52);
    people[id] = {
      id,
      first: `First${i}`,
      surname: `Sur${i % 40}`,
      sex: i % 2 === 0 ? 'F' : 'M',
      birthYear: year - age,
      traits: { ambition: rand(seed, 'amb', id), charm: rand(seed, 'charm', id), diligence: rand(seed, 'dil', id) },
      parents: [],
      children: [],
      wealth: 500 + Math.floor(rand(seed, 'wealth', id) * 1500),
      reputation: (rand(seed, 'rep', id) - 0.5) * 1.4,
    };
    workplace[id] = `town:0:wp:${i % 15}`;
    ids.push(id);
  }
  for (let i = 0; i + 1 < count; i += 4) {
    const a = people[ids[i]!]!;
    const b = people[ids[i + 1]!]!;
    a.spouse = b.id;
    b.spouse = a.id;
    if (i + 2 < count) {
      const c = people[ids[i + 2]!]!;
      c.parents = [a.id, b.id];
      a.children.push(c.id);
      b.children.push(c.id);
    }
  }
  return {
    world: { seed, year, people, businesses: {}, bonds: {} },
    ctx: {
      townOf: () => 'town:0',
      workplaceOf: (id) => workplace[id],
      jurisdictionOf: () => 'police:town0',
    },
  };
}

/** `{seed, people, year?, day?}` -> `{people, day}` */
export function simCreate(json: string): string {
  const a = JSON.parse(json) as { seed: string; people: number; year?: number; day?: number };
  const { world, ctx } = makeWorld(a.seed, a.people, a.year ?? 1990);
  smoke = { world, ctx, state: createCrimeState(a.seed, a.day ?? 0) };
  return JSON.stringify({ people: Object.keys(world.people).length, day: smoke.state.currentDay });
}

const APPEARANCE: CulpritAppearance = { height: 'tall', build: 'heavy', hair: 'red', clothingColor: 'navy' };

/**
 * `{id, kind, hour, victimId?, culpritId?, x?, y?, appearance?, bystanders: [{id, x, y, attention?}]}` ->
 * `{witnesses: [{id, confidence, identified}]}`
 */
export function simRecordCrime(json: string): string {
  const s = need();
  const a = JSON.parse(json) as {
    id: string;
    kind: CrimeKind;
    hour: number;
    victimId?: Id;
    culpritId?: Id;
    x?: number;
    y?: number;
    appearance?: CulpritAppearance;
    bystanders: { id: Id; x: number; y: number; attention?: number }[];
  };
  const crime: CrimeEvent = {
    id: a.id,
    kind: a.kind,
    severity: DEFAULT_SEVERITY[a.kind],
    position: { x: a.x ?? 0, y: a.y ?? 0 },
    placeId: 'town:0:main_st',
    townId: 'town:0',
    when: { year: s.world.year, day: s.state.currentDay, hour: a.hour },
    culpritId: a.culpritId ?? 'player',
    culpritAppearance: a.appearance ?? APPEARANCE,
    ...(a.victimId !== undefined ? { victimId: a.victimId } : {}),
  };
  const bystanders: Bystander[] = a.bystanders.map((b) => {
    const person = s.world.people[b.id];
    if (!person) throw new Error(`no person ${b.id}`);
    return { person, position: { x: b.x, y: b.y }, ...(b.attention !== undefined ? { attention: b.attention } : {}) };
  });
  const sightings = witnessesOf(crime, bystanders, { seed: s.world.seed, world: s.world });
  s.state = recordCrime(s.state, crime, sightings);
  return JSON.stringify({
    witnesses: sightings.map((w) => ({ id: w.witnessId, confidence: w.confidence, identified: w.identifiedCulpritId ?? null })),
  });
}

/** `{days}` -> `{day}` (one advanceDay per day) */
export function simAdvance(json: string): string {
  const s = need();
  const a = JSON.parse(json) as { days: number };
  for (let i = 0; i < a.days; i++) {
    const day = s.state.currentDay + 1;
    s.state = advanceDay(s.state, s.world, day, s.ctx);
  }
  return JSON.stringify({ day: s.state.currentDay });
}

/** `{crimeId}` -> `{day, knowers: CrimeAccount[], pending: PendingReport[], reports: PoliceReport[]}` */
export function simKnowers(json: string): string {
  const s = need();
  const a = JSON.parse(json) as { crimeId: string };
  const reports = Object.keys(s.state.jurisdictions)
    .sort()
    .flatMap((j) => s.state.jurisdictions[j]!.cases[a.crimeId]?.reports ?? []);
  return JSON.stringify({
    day: s.state.currentDay,
    knowers: knowersOf(s.state, a.crimeId),
    pending: s.state.pendingReports.filter((r) => r.crimeId === a.crimeId),
    reports,
  });
}

// The bundle is an IIFE: what the phone calls is set on the global object.
const g = globalThis as Record<string, unknown>;
g.simCreate = simCreate;
g.simRecordCrime = simRecordCrime;
g.simAdvance = simAdvance;
g.simKnowers = simKnowers;
