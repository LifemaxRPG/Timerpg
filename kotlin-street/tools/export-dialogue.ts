// Exports what the people on a town's two streets say (from OUR game logic) for the Kotlin app's dialogue.
// Usage: npx tsx kotlin-street/tools/export-dialogue.ts <town seed> <year> > kotlin-street/app/src/main/assets/dialogue.json
//
// The streets and their buildings are exactly those of tools/export-street.ts (tools/town-streets.ts, same sprite names).
// At each building the player can talk to a shop's owner, or a home's household head and at most one other adult who
// lives there. For each, a TalkCtx as the game builds it on a first meeting (src/game/dialogue.ts: familiarity 0, nobody
// watching, not wanted, an empty notebook, the town's name as the game shows it, the job as src/town/schedule.ts describes
// it), and for each topic:
//  - template: the game's own line (narrate, or ask for rumors/secret), shown at once and whenever the model fails;
//  - prompt: for intents the model may voice (VOICED in src/llm/factsheet.ts), the full ChatML prompt
//    (toPrompt(brief(...))); null otherwise;
//  - must / self: what validate() needs to check the model's reply (words it must contain; the speaker's name).
// rumors/secret are added (asked as a friend, familiarity 3, the way the game asks them) for people who have any.
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { townStreets } from './town-streets';
import { ask, narrate, optionsFor, type TalkCtx } from '../../src/game/dialogue';
import { RULES, VOICED, brief, toPrompt } from '../../src/llm/factsheet';
import { buildResidents } from '../../src/town/schedule';
import { heardBy, secretsOf } from '../../src/sim/knowledge';
import { ageIn, fullName, isAlive, type Person } from '../../src/sim/types';
import { townName } from '../../src/world/names';

const seed = process.argv[2] ?? 'eta';
const year = Number(process.argv[3] ?? 2025);
const t = townStreets(seed, year);
const { w, layout } = t;
const residents = new Map(buildResidents(layout, w).map((r) => [r.id, r]));
const name = townName(seed);
const warn = (s: string): void => { process.stderr.write(`export-dialogue: ${s}\n`); };

/** What src/town/schedule.ts says this person does (a shop's owner, at their shop, runs that shop). */
function jobOf(p: Person, shopName?: string): string {
  if (shopName) return `${shopName} (owner)`;
  const r = residents.get(p.id);
  if (r) return r.job;
  // not placed by the schedule (no doorstep): its rules without the random part
  const age = ageIn(p, w.year);
  const job = age < 18 ? 'at school' : age >= 66 ? 'retired' : 'works from home';
  warn(`${fullName(p)} has no schedule; job "${job}"`);
  return job;
}

const ctxOf = (person: Person, job: string): TalkCtx => ({ w, person, job, townName: name, familiarity: 0, witnesses: 0, wanted: false, journal: [] });

interface Topic { intent: string; label: string; familiarity: number; template: string; prompt: string | null; must: string[]; self: string }

function topicsOf(c: TalkCtx): Topic[] {
  const self = fullName(c.person);
  const label = (cx: TalkCtx, intent: string): string => (intent === 'greet' ? 'Say hello' : optionsFor(cx).find((o) => o.id === intent)?.label ?? intent);
  const out: Topic[] = [];
  for (const intent of ['greet', 'chat', 'work', 'family', 'town']) {
    const template = narrate(c, intent);
    const b = VOICED.has(intent) ? brief(c, intent, template) : null;
    out.push({ intent, label: label(c, intent), familiarity: c.familiarity, template, prompt: b ? toPrompt(b) : null, must: b ? b.must : [], self });
  }
  // what they have heard about others, and their own secret: asked as a friend, as WorldScene.talkChoose asks them
  const friend: TalkCtx = { ...c, familiarity: 3 };
  for (const intent of ['rumors', 'secret'] as const) {
    const pool = intent === 'secret' ? secretsOf(w, c.person) : heardBy(w, c.person, 0);
    if (!pool.length) continue;
    const got = ask(friend, intent);
    const facts = got.entry ? [`You are quietly telling the player this secret: ${intent === 'secret' ? 'you yourself' : got.entry.aboutName} ${got.entry.text}. Say it in your own words and ask them to keep it quiet.`] : [];
    const must = got.entry && intent === 'rumors' ? [got.entry.aboutName.split(' ')[0]!] : [];
    const b = VOICED.has(intent) ? brief(friend, intent, got.line, facts, must) : null;
    out.push({ intent, label: label(friend, intent), familiarity: 3, template: got.line, prompt: b ? toPrompt(b) : null, must: b ? b.must : [], self });
  }
  return out;
}

const npcs: object[] = [];
const people = new Map<string, number>();
t.streets.forEach((st, street) => st.buildings.forEach((sprite, index) => {
  const lot = t.lotOf.get(sprite)!;
  const add = (p: Person, role: string, building: string, job: string): void => {
    npcs.push({ id: p.id, name: fullName(p), first: p.first, age: ageIn(p, w.year), sex: p.sex, warmth: Math.round(p.traits.charm * 100) / 100, building: sprite, buildingName: building, street, index, role, job, topics: topicsOf(ctxOf(p, job)) });
  };
  if (lot.kind === 'shop') {
    const b = w.businesses[lot.refId]!;
    const owner = b.owner ? w.people[b.owner] : undefined;
    if (owner && isAlive(owner)) add(owner, 'owner', b.name, jobOf(owner, b.name));
    people.set(sprite, owner && isAlive(owner) ? 1 : 0);
  } else {
    const head = w.people[lot.refId];
    const living = lot.residents.map((id) => w.people[id]).filter((p): p is Person => !!p && isAlive(p));
    const adults = living.filter((p) => ageIn(p, w.year) >= 18);
    const first = head && isAlive(head) ? head : adults[0];
    // the other adult: the head's spouse if they live here, else the eldest other adult
    const others = adults.filter((p) => p !== first).sort((a, b) => (a.id === first?.spouse ? -1 : b.id === first?.spouse ? 1 : a.birthYear - b.birthYear));
    // named as street.json names it
    const surname = head ? fullName(head).split(' ').slice(-1)[0] : '';
    const building = lot.residents.length > 2 ? `${surname} House`.trim() : `The ${surname} home`.trim();
    if (first) add(first, 'head', building, jobOf(first));
    if (others[0]) add(others[0], 'resident', building, jobOf(others[0]));
    people.set(sprite, living.length);
  }
}));

// every building on the two streets that has people in it has someone to talk to
const talkable = new Set(npcs.map((n) => (n as { building: string }).building));
let bad = 0;
for (const st of t.streets) for (const sprite of st.buildings) {
  if ((people.get(sprite) ?? 0) > 0 && !talkable.has(sprite)) { warn(`${sprite} on ${st.name} has people but nobody to talk to`); bad++; }
}
// and the app's street.json, if it is this town, shows the same buildings under the same names
const here = dirname(fileURLToPath(import.meta.url));
const asset = join(here, '../app/src/main/assets/street.json');
if (existsSync(asset)) {
  const sj = JSON.parse(readFileSync(asset, 'utf8')) as { town: string; year: number; streets: { buildings: string[] }[] };
  if (sj.town === seed && sj.year === year) {
    sj.streets.forEach((st, k) => {
      if (JSON.stringify(st.buildings) !== JSON.stringify(t.streets[k]?.buildings)) { warn(`street ${k} differs from street.json`); bad++; }
      for (const sprite of st.buildings) if ((people.get(sprite) ?? 0) > 0 && !talkable.has(sprite)) { warn(`street.json ${sprite} has no entry`); bad++; }
    });
  } else warn(`street.json is ${sj.town} ${sj.year}, not ${seed} ${year}: not cross-checked`);
}
if (bad) { warn(`${bad} problem(s)`); process.exit(1); }

console.log(JSON.stringify({ town: seed, year, townName: name, streets: t.streets.map((s) => s.name), rules: RULES, npcs }, null, 1));
