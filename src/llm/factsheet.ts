import { fullName, type Person } from '../sim/types';
import type { TalkCtx } from '../game/dialogue';
import { rand } from '../sim/rng';
import { planTown } from '../town/plan';
import { cinemaProgram } from '../town/cinema';
import { GENRE_WORDS } from '../town/films';
import { REUSE_WORDS, storeState, townStoreHistories, type StoreState } from '../town/stores';
import { heardBy, secretsOf } from '../sim/knowledge';
import { originWords } from '../sim/origin';
import {
  MAX_MEMORIES,
  memoriesFor,
  memoriesForJson,
  type MemoryLine,
  type MemoryNow,
  type MemoryOptions,
  type MemoryPersonTraits,
  type MemorySource,
  type MemoryTone,
} from './memories';

export {
  MAX_MEMORIES,
  memoriesFor,
  memoriesForJson,
  type MemoryLine,
  type MemoryNow,
  type MemoryOptions,
  type MemoryPersonTraits,
  type MemorySource,
  type MemoryTone,
};

// THE FACT SHEET. A language model only gives words to what the simulation already decided: it is
// handed a short sheet of facts about the person and what the player just did, and told to answer in
// character in a sentence or two. It never chooses outcomes. Anything it says is checked against the
// facts that must appear (`must`); if the check fails the game uses the template line instead.

export interface Brief {
  system: string;
  user: string;
  /** Words the reply must contain (e.g. a name from a secret), or it is rejected. */
  must: string[];
  /** The speaker's own name: introducing yourself by full name is rejected. */
  self: string;
  /** Their last replies in this conversation: a reply that says one of them again is rejected. */
  recent?: string[];
  /** Grounded first-person memories attached to this brief (used for epistemic checks in validate()). */
  memories?: MemoryLine[];
}

/** Constant, so it is cached between lines. */
export const RULES = 'Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences, in your own manner. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names, places, events or numbers.\nFACTS';

const wealthWord = (n: number): string => (n < 400 ? 'poor' : n < 3000 ? 'getting by' : n < 12000 ? 'comfortable' : 'wealthy');

function family(c: TalkCtx): string[] {
  const p = c.person, w = c.w;
  const out: string[] = [];
  const spouse = p.spouse ? w.people[p.spouse] : undefined;
  if (spouse) out.push(spouse.deathYear === undefined ? `Married to ${fullName(spouse)}.` : 'Widowed.');
  const kids = p.children.map((id) => w.people[id]).filter((k): k is Person => !!k && k.deathYear === undefined);
  if (kids.length) out.push(`Children: ${kids.slice(0, 5).map((k) => k.first).join(', ')}.`);
  return out;
}

/**
 * What is on at the town's cinema this week (src/town/cinema.ts), and for some people what they went to see: small talk
 * and news only. Two sentences at most, so the sheet stays short.
 */
export function cinemaFacts(c: TalkCtx): string[] {
  const w = c.w;
  const site = planTown(w.seed).landmarks.find((l) => l.id === 'cinema');
  if (!site || w.year < site.openYear) return [];
  const p = cinemaProgram(w.seed, w.year);
  const on = p.nowShowing.slice(0, 3).map((f) => `"${f.title}" (${GENRE_WORDS[f.genre] ?? f.genre})`);
  const out = [`The cinema, ${p.name}, is showing ${on.length > 1 ? `${on.slice(0, -1).join(', ')} and ${on[on.length - 1]}` : on[0]} this week.`];
  const r = rand(w.seed, w.year, 'cinema-saw', c.person.id);
  if (w.year - c.person.birthYear >= 8 && r < 0.4) {
    const f = p.nowShowing[Math.floor((r / 0.4) * p.nowShowing.length)]!;
    const liked = rand(w.seed, w.year, 'cinema-liked', c.person.id);
    out.push(`You saw "${f.title}" there and thought it was ${liked < 0.45 ? 'wonderful' : liked < 0.8 ? 'all right' : 'a waste of a ticket'}.`);
  }
  return out;
}

/** What a person does at an old store's new use (none: not somewhere people go each week). */
const REUSE_HABIT: Partial<Record<NonNullable<StoreState['reuse']>, string>> = {
  church: 'go to church at', gym: 'work out at', thrift: 'look for bargains at', flea: 'browse on weekends at', outlet: 'look for bargains at',
  cafe: 'often have coffee at', community: 'go to events at', feed: 'buy garden seed at',
};

/**
 * The town's stores in the talk's year (src/town/stores.ts): where this person buys their groceries, and the latest
 * change they would talk about (the superstore opening, the supermarket moving out of town, the old general store
 * closing and what it is now). Two sentences at most.
 */
export function storeFacts(c: TalkCtx): string[] {
  const w = c.w, p = c.person;
  const plan = planTown(w.seed);
  const st = new Map<string, StoreState>();
  for (const h of townStoreHistories(w.seed, plan.landmarks.map((l) => l.id))) {
    const s = storeState(w.seed, h, w.year);
    if (s) st.set(s.site, s);
  }
  const open = [...st.values()].filter((s) => s.status === 'open');
  const age = w.year - p.birthYear;
  if (!open.length) {
    // every store in town has shut: the last one's closing is what people know
    const last = [...st.values()].filter((s) => s.closed !== undefined).sort((a, b) => b.closed! - a.closed!)[0];
    return last ? [`Since ${last.name} closed in ${last.closed}, ${age < 14 ? 'your family drives' : 'you drive'} to the next town over for groceries.`] : [];
  }
  const where = (s: StoreState): string => s.site === 'generalstore' ? `${s.name} on Main Street`
    : s.site === 'market' ? `${s.name} downtown` : `${s.name} out on the main road at the edge of town`;
  // the newest store most people use; the general store keeps the old and the loyal while it stands
  const r = rand(w.seed, w.year, 'store-shop', p.id);
  const general = open.find((s) => s.site === 'generalstore');
  const newest = open[open.length - 1]!;
  const mine = general && (open.length === 1 || r < (age >= 60 ? 0.55 : 0.2)) ? general : newest;
  const out = [age < 14 ? `Your family buys groceries at ${where(mine)}.` : `You buy your groceries at ${where(mine)}.`];
  // the latest change in the town's shopping, if recent, else (for some who remember) the old general store's end
  const events = [...st.values()].flatMap((s) => s.history.map((e) => ({ s, e }))).filter(({ e }) => e.year >= 1950).sort((a, b) => b.e.year - a.e.year);
  const superstore = st.get('superstore');
  const line = ({ s, e }: { s: StoreState; e: StoreState['history'][number] }): string | undefined => {
    if (e.event === 'opened' && s.site === 'superstore') return `${e.name}, a huge superstore, opened at the edge of town in ${e.year}.`;
    if (e.event === 'opened' && s.site === 'market') return `${e.name}, the town's first supermarket, opened downtown in ${e.year}.`;
    if (e.event === 'opened' && s.site === 'supermarket') return `${e.name}, the town's first supermarket, opened at the edge of town in ${e.year}.`;
    if (e.event === 'moved') return `${st.get('market')?.name ?? 'The market'} moved out of downtown in ${e.year} into a big new store at the edge of town, ${e.name}.`;
    if (e.event === 'renamed' && e.trade) return `The old general store became ${e.name} in ${e.year}.`;
    if (e.event === 'renamed') return `The general store changed hands in ${e.year} and is now ${e.name}.`;
    if (e.event === 'expanded') return `${e.name} was made twice as big in ${e.year}, with groceries and a pharmacy.`;
    if (e.event === 'closed' && e.why === 'superstore') return `${e.name} closed in ${e.year}${superstore ? ` after ${superstore.name} opened` : ''}.`;
    if (e.event === 'closed' && e.why === 'supermarket') return `${e.name} closed in ${e.year}; the supermarket had taken its trade.`;
    if (e.event === 'reused') return `The old ${s.name} building is ${REUSE_WORDS[e.reuse!]} now, ${e.name}.`;
    return undefined;
  };
  // one of the last few years' changes (each person picks up a different piece of news)
  const fresh = events.filter(({ e }) => w.year - e.year <= 4).map(line).filter((x): x is string => !!x);
  const recent = fresh.length ? fresh[Math.floor(rand(w.seed, w.year, 'store-news', p.id) * fresh.length)] : undefined;
  if (recent) out.push(recent);
  else {
    const old = st.get('generalstore');
    if (old && old.closed !== undefined && age - (w.year - old.closed) >= 12 && rand(w.seed, w.year, 'store-memory', p.id) < 0.5) {
      out.push(`You miss ${old.name}, which closed in ${old.closed}.`);
    }
  }
  // some go to what an old store became (church on Sunday, the gym, the thrift store, coffee)
  if (out.length < 2 && age >= 14) {
    const used = [...st.values()].filter((s) => s.status === 'reused' && s.reuse && REUSE_HABIT[s.reuse]);
    const u = used.length ? used[Math.floor(rand(w.seed, 'store-reuse-which', p.id) * used.length)]! : undefined;
    if (u && rand(w.seed, 'store-reuse', p.id) < 0.3) out.push(`You ${REUSE_HABIT[u.reuse!]} ${u.sign}, in the old ${u.name} building.`);
  }
  if (superstore && superstore.status === 'open' && w.year - superstore.opened <= 6 && out.length < 2) {
    // blame only once one of the old stores has actually closed after it came
    const blamed = [...st.values()].some((s) => s.why === 'superstore' && s.closed !== undefined && s.closed <= w.year);
    const v = rand(w.seed, 'store-opinion', p.id);
    out.push(v < 0.4 ? `You like ${superstore.name}'s low prices.` : v < 0.75 && blamed ? `You blame ${superstore.name} for the old stores closing.` : `You think ${superstore.name} is too big for a town like this.`);
  }
  return out.slice(0, 2);
}

/** Intents the model may voice. "town" is left to templates: a 0.6B model only produces filler about it. */
export const VOICED = new Set(['chat', 'family', 'work', 'praise', 'gift', 'rumors', 'secret', 'legend', 'news']);

/** What the player says, as words, for each menu choice. */
const PLAYER: Record<string, string> = {
  greet: 'Hello there.',
  chat: 'Nice day, isn\'t it?',
  town: 'What\'s this town like?',
  family: 'Tell me about your family.',
  work: 'What do you do for work?',
  praise: 'I\'ve been saying good things about you around town.',
  gift: 'Here, take this money. It\'s a gift.',
  rumors: 'Heard anything interesting lately?',
  secret: 'Are you hiding anything?',
  legend: 'Are there any old legends around here?',
  news: 'Anything in the news lately?',
};

export type VoiceKind = 'gossip' | 'warm' | 'cheerful' | 'busy' | 'reserved' | 'shy' | 'grump';

/**
 * How a person talks, from their traits (charm, ambition, diligence): a way of speaking, not a fact to repeat. Charm
 * above 0.55 is still "warm" for the game's other uses (templates, fillers, the voice).
 */
export function voiceOf(p: Person): { kind: VoiceKind; line: string } {
  const { charm, ambition, diligence } = p.traits;
  const kind: VoiceKind = charm >= 0.7 ? (diligence < 0.45 ? 'gossip' : 'warm')
    : charm > 0.55 ? 'cheerful'
    : charm >= 0.35 ? (ambition < 0.4 ? 'shy' : diligence > 0.6 ? 'busy' : 'reserved')
    : 'grump';
  const line = {
    gossip: 'You love gossip and chatter; you talk fast and ask questions back.',
    warm: 'You are warm and kind, and like a good chat.',
    cheerful: 'You are cheerful and a little nosy.',
    busy: 'You are polite but busy, and get to the point.',
    reserved: 'You are polite but reserved with strangers.',
    shy: 'You are shy and hesitant, and say little.',
    grump: 'You are grumpy and blunt, and keep it short.',
  }[kind];
  return { kind, line };
}

/** The sheet's mark for a topic the person would rather not talk about: only then may a reply change the subject. */
export const AVOID = 'You would rather not talk about it.';

/**
 * What a person who shares nothing this time really knows, and how they keep it, by their manner: none known (say so,
 * truthfully), or known but the player not trusted yet (a gossip hints, a shy one says they couldn't say, a grump
 * refuses), never what it is.
 */
function keeping(c: TalkCtx, intent: 'secret' | 'rumors', kind: VoiceKind): string {
  const own = intent === 'secret';
  const pool = own ? secretsOf(c.w, c.person) : heardBy(c.w, c.person, c.days ?? 0);
  if (!pool.length) return own ? 'You have no secrets of your own; say so honestly.' : 'You know no gossip or secrets about anyone; say so honestly.';
  const what = own ? 'a private matter of your own' : pool.length > 1 ? 'some private gossip about people in town' : 'a bit of private gossip about someone in town';
  const how = kind === 'gossip' || kind === 'cheerful' ? 'hint that you know something, but give no names'
    : kind === 'shy' ? 'say you couldn\'t say' : kind === 'grump' ? 'refuse bluntly' : 'keep it to yourself';
  return `You ${own ? 'have' : 'know'} ${what}, but you do not trust the player enough yet: ${how}. Never make it up. ${AVOID}`;
}

const HEARD_LINE_HINT = /\b(told me|word around town|heard (?:from|some talk|a rumou?r|that)|people .* whispering|only heard it secondhand|didn't see it myself|wasn't there to see it)\b/i;
const SEEN_LINE_HINT = /\b(i saw you|i saw the|i arrested you|i stopped you|i was in court|i was at the courthouse|you worked a shift|you bought|you sold)\b/i;
const EPISTEMIC_RULE = 'Only claim to have seen things you witnessed yourself; if you only heard about something, say you heard it.';

function extractLineCues(line: string): string[] {
  const stop = new Set([
    'the', 'and', 'but', 'you', 'your', 'that', 'this', 'with', 'from', 'were', 'was',
    'told', 'heard', 'know', 'true', 'though', 'about', 'around', 'town', 'word',
    'some', 'talk', 'just', 'only', 'myself', 'didn', 'don', 'see', 'saw', 'there',
    'everyone', 'people', 'hardly', 'believe', 'sure', 'more', 'secondhand', 'gossip',
  ]);
  return Array.from(
    new Set(
      line
        .toLowerCase()
        .replace(/[^a-z0-9'\s]+/g, ' ')
        .split(/\s+/)
        .map((w) => w.replace(/^'+|'+$/g, '').replace(/'s$/, ''))
        .filter((w) => w.length >= 3 && !stop.has(w)),
    ),
  );
}

function normalizeMemoryInput(item: MemoryLine | string, index: number): MemoryLine {
  if (typeof item !== 'string') return item;
  const trimmed = item.replace(/\s+/g, ' ').trim();
  const heardOnly = HEARD_LINE_HINT.test(trimmed) && !SEEN_LINE_HINT.test(trimmed);
  return {
    eventId: `mem:${index}`,
    kind: heardOnly ? 'rumor' : 'crime',
    line: trimmed,
    source: heardOnly ? 'heard_rumor' : 'seen',
    heardOnly,
    certainty: heardOnly ? 0.5 : 0.85,
    relevance: 0.5,
    ageDays: 0,
    tone: 'neutral',
    cues: extractLineCues(trimmed),
  };
}

/**
 * [b] with grounded first-person memories added to its fact sheet (before the example answer).
 * Accepts either structured `MemoryLine[]` from `memoriesFor()` or plain strings.
 */
export function withMemories(b: Brief, memories: readonly (MemoryLine | string)[]): Brief {
  const normalized = memories
    .map((m, i) => normalizeMemoryInput(m, i))
    .filter((m) => m.line.trim().length > 0)
    .slice(0, MAX_MEMORIES);
  if (!normalized.length) return b;

  const existing = b.memories ?? [];
  const merged = [...existing];
  const addedLines: string[] = [];
  for (const m of normalized) {
    if (!b.system.includes(m.line)) {
      addedLines.push(m.line);
    }
    if (!merged.some((e) => e.eventId === m.eventId && e.line === m.line)) {
      merged.push(m);
    }
  }

  let system = b.system;
  // Direct or seen contact means the player is no longer a total stranger; mere hearsay does not.
  if (merged.some((m) => !m.heardOnly)) {
    system = system.replace(
      'The player is a stranger.',
      'The player is someone you have crossed paths with before.',
    );
  }
  const hasHeard = merged.some((m) => m.heardOnly);
  const block: string[] = [...addedLines];
  if (hasHeard && !system.includes(EPISTEMIC_RULE)) {
    block.push(EPISTEMIC_RULE);
  }
  if (block.length) {
    const snippet = block.join('\n');
    const at = system.indexOf('\nExample of the kind of answer:');
    system = at >= 0 ? system.slice(0, at) + '\n' + snippet + system.slice(at) : system + '\n' + snippet;
  }
  return { ...b, system, memories: merged.slice(0, MAX_MEMORIES) };
}

/** [b] with their last replies in this conversation (and optional memories): the prompt quotes the last 2 (prefill time matters), validate() checks the last 3. */
export function withRecent(
  b: Brief,
  recent: string[],
  memories?: readonly (MemoryLine | string)[],
): Brief {
  const base = memories?.length ? withMemories(b, memories) : b;
  const r = recent.slice(-3).filter((x) => x.trim());
  if (!r.length) return base;
  const line = `You already said: ${r.slice(-2).map((x) => `"${x}"`).join(' ')} Do not repeat those words.`;
  const at = base.system.indexOf('\nExample of the kind of answer:');
  const system = at >= 0 ? base.system.slice(0, at) + '\n' + line + base.system.slice(at) : base.system + '\n' + line;
  return { ...base, system, recent: r };
}

/**
 * [b] as a free question asks it (the app's DialogueEngine.freeTopic does the same to the exported prompt): the player's
 * own words in place of the menu's question, the example answer left out (it answers the menu's question, and a small
 * model copies it), their last replies in the facts. [keepMust]: the words to name stay (a secret being told).
 */
export function freeQuestion(b: Brief, said: string, recent: string[] = [], keepMust = false): Brief {
  const at = b.system.indexOf('\nExample of the kind of answer:');
  const system = at >= 0 ? b.system.slice(0, at) : b.system;
  return withRecent({ ...b, system, user: said, must: keepMust ? b.must : [] }, recent);
}

/**
 * Build the prompt for one line of dialogue. Only the facts that bear on what was asked go in (a small
 * model repeats everything it is given, relevant or not). `facts` are extra sentences that must be
 * true (the secret being shared) and `must` are words that must show up in the reply.
 */
export function brief(
  c: TalkCtx,
  intent: string,
  template: string,
  facts: string[] = [],
  must: string[] = [],
  memories?: readonly (MemoryLine | string)[],
): Brief {
  const p = c.person, w = c.w;
  const mayor = w.mayor ? w.people[w.mayor] : undefined;
  const know = c.familiarity === 0 ? 'a stranger' : c.familiarity < 3 ? 'an acquaintance' : 'a friend';
  const voice = voiceOf(p);
  const relevant: string[] =
    intent === 'family' ? (family(c).length ? family(c) : ['You have no spouse or children.'])
    : intent === 'work' ? [`Your job: ${c.job}.`, `You are ${wealthWord(p.wealth)}.`]
    : intent === 'town' ? [`The town is ${c.townName}.`, mayor ? `The mayor is ${fullName(mayor)}.` : 'The town has no mayor.']
    : intent === 'gift' || intent === 'praise' ? [`You are ${wealthWord(p.wealth)}.`]
    // what they really know (src/sim/knowledge.ts) and whether they trust the player with it: the game decided, the
    // model only says it in their manner (a small model otherwise answers "Yes, I'm hiding something", or makes one up)
    : (intent === 'secret' || intent === 'rumors') && !facts.length ? [keeping(c, intent, voice.kind)]
    : [];
  // The answer must contain the real fact it is about (a name or the shop), or the template is used.
  const spouse = p.spouse ? w.people[p.spouse] : undefined;
  const kid = p.children.map((id) => w.people[id]).find((k) => k && k.deathYear === undefined);
  const anchor = intent === 'family' ? (spouse && spouse.deathYear === undefined ? spouse.first : kid?.first)
    : intent === 'work' && c.job.includes(' ') && !/^(retired|at school|out about|works from home)/.test(c.job) ? c.job.replace(/^works at /, '').split(/[\s']/)[0] : undefined;
  if (anchor) must = [...must, anchor];
  const sheet = [
    // where they are from and since when (src/sim/origin.ts): "are you from here?" has a true answer
    `You are ${fullName(p)}, ${w.year - p.birthYear}, ${p.sex === 'F' ? 'a woman' : 'a man'} ${originWords(w, p, c.townName)}. The year is ${w.year}.`,
    // the personality as a way of speaking, not a fact (a small model repeats facts word for word: "I'm just gruff and guarded")
    voice.line,
    `The player is ${know}.`,
    ...(c.wanted ? ['The player is wanted for a crime. You are afraid of them.'] : []),
    ...relevant,
    ...(intent === 'chat' || intent === 'news' || intent === 'town' ? cinemaFacts(c) : []),
    ...(intent === 'news' || intent === 'town' ? storeFacts(c) : []),
    ...facts,
  ].join('\n');
  // The rules come first and never change, so the model's prompt cache can reuse them every time.
  const system = [RULES, sheet, `Example of the kind of answer: ${template.split('\n')[0]}`].join('\n');
  const out: Brief = { system, user: PLAYER[intent] ?? 'Hmm.', must, self: fullName(p) };
  return memories?.length ? withMemories(out, memories) : out;
}

/** The raw text prompt (ChatML with thinking switched off, which Qwen3 expects). */
export const toPrompt = (b: Brief): string =>
  `<|im_start|>system\n${b.system}<|im_end|>\n<|im_start|>user\n${b.user}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`;

/** Capitalized words a reply may use without the facts naming them. */
const COMMON = new Set(['Mr', 'Mrs', 'Ms', 'Miss', 'Mister', 'Dr', 'Sir', 'Madam', 'God', 'Lord', 'Monday', 'Tuesday', 'Wednesday',
  'Thursday', 'Friday', 'Saturday', 'Sunday', 'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December', 'Christmas', 'Easter', 'English', 'American']);

/**
 * Places and events a reply may only speak of when the facts do ("the old mill", "the new library", "the fire"): after
 * "the", "a", "our"... and at most two words. Kept to nouns that name a thing in town, not words like home or street.
 */
export const PLACES = ['library', 'mill', 'church', 'chapel', 'college', 'bakery', 'factory', 'museum', 'hospital', 'station',
  'depot', 'bridge', 'hotel', 'inn', 'tavern', 'pub', 'saloon', 'diner', 'cafe', 'restaurant', 'theater', 'theatre', 'cinema',
  'market', 'mall', 'plaza', 'courthouse', 'jail', 'prison', 'cemetery', 'graveyard', 'lighthouse', 'harbor', 'harbour', 'pier',
  'dock', 'quarry', 'warehouse', 'festival', 'parade', 'carnival', 'circus', 'pharmacy', 'gym', 'stadium', 'arena', 'zoo',
  'castle', 'tower', 'monument', 'statue', 'fountain', 'lake', 'pond', 'river', 'creek', 'farm', 'ranch', 'orchard', 'barn',
  'dam', 'canal', 'reservoir', 'mansion', 'manor', 'flood', 'fire', 'storm', 'robbery', 'murder', 'scandal', 'election'];
const PLACE = new RegExp(`\\b(?:the|a|an|our|that|this|its)\\s+(?:[a-z'-]+\\s+){0,2}?(${PLACES.join('|')})(?:e?s)?\\b`, 'gi');
/** Saying you are from here when the facts say you moved here (or the other way round). */
const NATIVE = /\b(all my life|my whole life|born here|grew up here|always lived here|born and raised here|i am from here|i'm from here|from around here)\b/i;
const MOVED = /\b(moved here|came here from|not from here|not from around here)\b/i;
/** Changing the subject: only for a topic the facts mark as one they would rather not talk about. */
const DEFLECT = /\b(talk about something else|chat about something else|change the subject|perhaps we (can|could) (talk|chat) about|let's (talk|chat) about|(talk|chat) about [a-z' ]+ instead)\b/i;
/** A sentence that doubts or denies: the player's own word in it is not a claim ("A library? I haven't heard of one."). */
const HEDGE = /\?|\b(no|not|never|nothing|nobody)\b|n't\b/i;
/** Claims first-hand eyewitness observation of the player or an event. */
const CLAIMED_SEEN = /\b(i saw you|i saw them|i saw him|i saw her|i saw it|i saw the police|i saw the whole thing|i saw what you did|i watched you|i watched it|i witnessed|with my own eyes|right in front of me|i was right there|i was there when|i caught you|saw you myself|saw it myself|saw you do it)\b/i;
/** Explicitly denies having seen it (e.g. "I didn't see it myself", "I never saw you do it"). */
const NEGATED_SIGHT = /\b(didn't|did not|never|haven't|have not|don't|do not|cannot|can't|not that i)\s+(?:actually\s+|even\s+|really\s+)?(see|saw|seen|watch|watched|witness|witnessed|catch|caught)\b|\b(wasn't|was not)\s+(?:even\s+|right\s+)?there\b/i;

function claimedSeenOnHearsay(reply: string, b: Brief, sheet: string): boolean {
  const mems = b.memories ?? [];
  let heardMems = mems.filter((m) => m.heardOnly);
  let seenMems = mems.filter((m) => !m.heardOnly);

  // Also support hearsay lines passed directly via brief(..., facts) without withMemories().
  if (!mems.length && sheet) {
    const lines = sheet.split('\n').filter((l) => l.trim() && l !== EPISTEMIC_RULE);
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i]!;
      if (HEARD_LINE_HINT.test(l) && !SEEN_LINE_HINT.test(l)) {
        heardMems.push(normalizeMemoryInput(l, i));
      } else if (SEEN_LINE_HINT.test(l)) {
        seenMems.push(normalizeMemoryInput(l, i));
      }
    }
  }
  if (!heardMems.length) return false;

  const heardCues = new Set<string>();
  for (const m of heardMems) {
    for (const c of m.cues) heardCues.add(c);
  }
  const seenCues = new Set<string>();
  for (const m of seenMems) {
    for (const c of m.cues) seenCues.add(c);
  }

  const sents = reply.match(/[^.!?]+[.!?]*|[^.!?]+/g) ?? [reply];
  for (const sent of sents) {
    if (!CLAIMED_SEEN.test(sent)) continue;
    if (NEGATED_SIGHT.test(sent)) continue;
    // If the NPC has zero seen memories of the player, any eyewitness claim is false.
    if (seenMems.length === 0) return true;
    // If the NPC has both seen and heard memories, reject only when the claimed-seen sentence
    // talks about a heard-only topic rather than a seen one.
    const tokens = extractLineCues(sent);
    const hitsHeard = tokens.some((tok) => heardCues.has(tok));
    const hitsSeen = tokens.some((tok) => seenCues.has(tok));
    if (hitsHeard && !hitsSeen) return true;
  }
  return false;
}

/** The sentence of [t] around index [i] (with its closing mark). */
function sentenceAt(t: string, i: number): string {
  let s = i;
  while (s > 0 && !'.!?'.includes(t[s - 1]!)) s--;
  let e = i;
  while (e < t.length && !'.!?'.includes(t[e]!)) e++;
  return t.slice(s, Math.min(t.length, e + 1));
}

const wordsOf = (s: string): string[] => s.toLowerCase().replace(/[^a-z0-9']+/g, ' ').trim().split(' ').filter((x) => x);

/** [t] says again what one of [recent] said: four words in a row the same ("a lovely day for"), or a sentence of mostly the same words. */
export function repeats(t: string, recent: string[]): boolean {
  const grams = (ws: string[]): Set<string> => {
    const out = new Set<string>();
    for (let i = 0; i + 4 <= ws.length; i++) out.add(ws.slice(i, i + 4).join(' '));
    return out;
  };
  const sents = (s: string): Set<string>[] => s.split(/[.!?]+/).map(wordsOf).filter((ws) => ws.length >= 3).map((ws) => new Set(ws));
  const mine = grams(wordsOf(t)), mySents = sents(t);
  for (const r of recent) {
    const theirs = grams(wordsOf(r));
    for (const g of mine) if (theirs.has(g)) return true;
    for (const a of mySents) for (const b of sents(r)) {
      let both = 0;
      for (const x of a) if (b.has(x)) both++;
      if (both / (a.size + b.size - both) >= 0.75) return true;
    }
  }
  return false;
}

/** Clean the model's reply, or null if it should not be used. */
export function validate(raw: string, b: Brief): string | null {
  return check(raw, b)[0];
}

/** Why validate() turns [raw] down (null: it passes). */
export function whyRejected(raw: string, b: Brief): string | null {
  return check(raw, b)[1];
}

function check(raw: string, b: Brief): [string | null, string | null] {
  let t = raw.replace(/<think>[\s\S]*?<\/think>/g, '').replace(/<\|[^|]*\|>/g, '').trim();
  t = t.replace(/^["“'‘]+|["”'’]+$/g, '').replace(/\*[^*]*\*/g, '').replace(/\s+/g, ' ').trim();
  if (t.length < 3) return [null, 'empty'];
  if (t.length > 320) return [null, 'too long'];
  // Keep at most the first two sentences.
  const sentences = t.match(/[^.!?]+[.!?]+(\s|$)|[^.!?]+$/g);
  if (sentences && sentences.length > 2) t = sentences.slice(0, 2).join('').trim();
  if (/^(as an ai|i am an ai|i'm an ai|sorry, i)/i.test(t) || /\b(language model|assistant)\b/i.test(t)) return [null, 'sounded like an AI assistant'];
  // Stage directions, assistant-speak and self-introductions are not how a neighbor talks.
  if (/\b(i say|i respond|i reply|i smile|smiles?|nods?|my eyes|flickers?|let me know|how can i (help|assist)|anything else)\b/i.test(t)) return [null, 'stage directions'];
  if (b.self && t.toLowerCase().includes(b.self.toLowerCase())) return [null, 'said their own full name'];
  // the fact sheet read back: its personality words, or its "<town>, <year>" tag
  if (/\b(warm and chatty|gruff and guarded|talk warmly|talk curtly)\b/i.test(t) || /,\s*(1[89]|20)\d\d\b/.test(t)
    || /^an? [a-z ,]*\b(response|reply|answer|tone|voice)\b/i.test(t)) return [null, 'read the fact sheet back'];
  const low = t.toLowerCase();
  const miss = b.must.find((m) => !low.includes(m.toLowerCase()));
  if (miss !== undefined) return [null, `left out "${miss}"`];
  // Everything named comes from the facts. The player's own words may be said back only to doubt or deny them
  // ("A new library? I haven't heard of one."), not as a claim ("The new library is lovely.").
  // (their own last replies, quoted in the facts so they say something new, are not facts)
  const sheet = b.system.replace(/You already said: [^\n]*/, ''), said = b.user;
  const echoed = (i: number): boolean => HEDGE.test(sentenceAt(t, i));
  // a name the facts never mention ("We have two kids, Jake and Lisa."): a capitalized word in mid-sentence the facts lack
  if (sheet) for (const m of t.matchAll(/\b[A-Z][a-z]+\b/g)) {
    const before = t.slice(0, m.index).trimEnd();
    if (!before || /[.!?"“‘(]$/.test(before) || COMMON.has(m[0])) continue;
    const name = new RegExp(`\\b${m[0]}\\b`);
    if (name.test(sheet) || (name.test(said) && echoed(m.index!))) continue;
    return [null, `made up a name ("${m[0]}")`];
  }
  if (sheet) {
    const sheetLow = sheet.toLowerCase(), saidLow = said.toLowerCase();
    for (const m of t.matchAll(PLACE)) {
      const noun = m[1]!.toLowerCase();
      if (sheetLow.includes(noun) || (saidLow.includes(noun) && echoed(m.index!))) continue;
      return [null, `made up a place or event ("${m[0]}")`];
    }
    // where they are from: the facts say it
    const town = /moved to (.+?) from /.exec(sheet)?.[1]?.toLowerCase();
    const native = NATIVE.exec(t); // (not "I wasn't born here", nor "Born here? No.")
    if (town && ((native && !echoed(native.index)) || [`i am from ${town}`, `i'm from ${town}`, `born in ${town}`, `grew up in ${town}`].some((x) => low.includes(x)))) return [null, 'said they are from here (they moved here)'];
    if (sheet.includes('born and raised in') && MOVED.test(t)) return [null, 'said they moved here (they were born here)'];
    // "Perhaps we can talk about something else": only about a topic they would rather not talk about
    if (DEFLECT.test(t) && !sheet.includes(AVOID)) return [null, 'changed the subject'];
    // Epistemic guard (G021): never claim to have seen something the NPC only heard about
    if (claimedSeenOnHearsay(t, b, sheet)) return [null, 'claimed to have seen something only heard'];
  }
  // the same words as one of their last replies ("It is a lovely day for it, isn't it?" three times)
  if (b.recent?.length && repeats(t, b.recent)) return [null, 'said it again'];
  return [t, null];
}
