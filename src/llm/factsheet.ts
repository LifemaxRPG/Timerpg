import { fullName, type Person } from '../sim/types';
import type { TalkCtx } from '../game/dialogue';
import { rand } from '../sim/rng';
import { planTown } from '../town/plan';
import { cinemaProgram } from '../town/cinema';
import { GENRE_WORDS } from '../town/films';
import { REUSE_WORDS, storeState, townStoreHistories, type StoreState } from '../town/stores';

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
}

/** Constant, so it is cached between lines. */
export const RULES = 'Roleplay a resident of a small town and reply to what the player says. 1-2 short spoken sentences. Do not introduce yourself. No actions, no quotes, no modern slang. Use only the facts below; never invent names or numbers.\nFACTS';

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
  const out = [`${p.name} is showing ${on.length > 1 ? `${on.slice(0, -1).join(', ')} and ${on[on.length - 1]}` : on[0]} this week.`];
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

/**
 * Build the prompt for one line of dialogue. Only the facts that bear on what was asked go in (a small
 * model repeats everything it is given, relevant or not). `facts` are extra sentences that must be
 * true (the secret being shared) and `must` are words that must show up in the reply.
 */
export function brief(c: TalkCtx, intent: string, template: string, facts: string[] = [], must: string[] = []): Brief {
  const p = c.person, w = c.w;
  const warm = p.traits.charm > 0.55;
  const mayor = w.mayor ? w.people[w.mayor] : undefined;
  const know = c.familiarity === 0 ? 'a stranger' : c.familiarity < 3 ? 'an acquaintance' : 'a friend';
  const relevant: string[] =
    intent === 'family' ? (family(c).length ? family(c) : ['You have no spouse or children.'])
    : intent === 'work' ? [`Your job: ${c.job}.`, `You are ${wealthWord(p.wealth)}.`]
    : intent === 'town' ? [`The town is ${c.townName}.`, mayor ? `The mayor is ${fullName(mayor)}.` : 'The town has no mayor.']
    : intent === 'gift' || intent === 'praise' ? [`You are ${wealthWord(p.wealth)}.`]
    // nothing to share (the game decided): say so, or a small model happily answers "Yes, I'm hiding something."
    : (intent === 'secret' || intent === 'rumors') && !facts.length ? ['You do not share private matters with the player yet. Politely keep it to yourself.']
    : [];
  // The answer must contain the real fact it is about (a name or the shop), or the template is used.
  const spouse = p.spouse ? w.people[p.spouse] : undefined;
  const kid = p.children.map((id) => w.people[id]).find((k) => k && k.deathYear === undefined);
  const anchor = intent === 'family' ? (spouse && spouse.deathYear === undefined ? spouse.first : kid?.first)
    : intent === 'work' && c.job.includes(' ') && !/^(retired|at school|out about|works from home)/.test(c.job) ? c.job.replace(/^works at /, '').split(/[\s']/)[0] : undefined;
  if (anchor) must = [...must, anchor];
  const sheet = [
    // the personality as a way of speaking, not a fact (a small model repeats facts word for word: "I'm just gruff and guarded")
    `You are ${fullName(p)}, ${w.year - p.birthYear}, ${p.sex === 'F' ? 'a woman' : 'a man'} who lives in ${c.townName}. The year is ${w.year}.`,
    warm ? 'You talk warmly and like to chat.' : 'You talk curtly and are wary of strangers.',
    `The player is ${know}.`,
    ...(c.wanted ? ['The player is wanted for a crime. You are afraid of them.'] : []),
    ...relevant,
    ...(intent === 'chat' || intent === 'news' || intent === 'town' ? cinemaFacts(c) : []),
    ...(intent === 'news' || intent === 'town' ? storeFacts(c) : []),
    ...facts,
  ].join('\n');
  // The rules come first and never change, so the model's prompt cache can reuse them every time.
  const system = [RULES, sheet, `Example of the kind of answer: ${template.split('\n')[0]}`].join('\n');
  return { system, user: PLAYER[intent] ?? 'Hmm.', must, self: fullName(p) };
}

/** The raw text prompt (ChatML with thinking switched off, which Qwen3 expects). */
export const toPrompt = (b: Brief): string =>
  `<|im_start|>system\n${b.system}<|im_end|>\n<|im_start|>user\n${b.user}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`;

/** Capitalized words a reply may use without the facts naming them. */
const COMMON = new Set(['Mr', 'Mrs', 'Ms', 'Miss', 'Mister', 'Dr', 'Sir', 'Madam', 'God', 'Lord', 'Monday', 'Tuesday', 'Wednesday',
  'Thursday', 'Friday', 'Saturday', 'Sunday', 'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December', 'Christmas', 'Easter', 'English', 'American']);

/** Clean the model's reply, or null if it should not be used. */
export function validate(raw: string, b: Brief): string | null {
  let t = raw.replace(/<think>[\s\S]*?<\/think>/g, '').replace(/<\|[^|]*\|>/g, '').trim();
  t = t.replace(/^["“'‘]+|["”'’]+$/g, '').replace(/\*[^*]*\*/g, '').replace(/\s+/g, ' ').trim();
  if (t.length < 3 || t.length > 320) return null;
  // Keep at most the first three sentences.
  const sentences = t.match(/[^.!?]+[.!?]+(\s|$)|[^.!?]+$/g);
  if (sentences && sentences.length > 2) t = sentences.slice(0, 2).join('').trim();
  if (/^(as an ai|i am an ai|i'm an ai|sorry, i)/i.test(t) || /\b(language model|assistant)\b/i.test(t)) return null;
  // Stage directions, assistant-speak and self-introductions are not how a neighbor talks.
  if (/\b(i say|i respond|i reply|i smile|smiles?|nods?|my eyes|flickers?|let me know|how can i (help|assist)|anything else)\b/i.test(t)) return null;
  if (b.self && t.toLowerCase().includes(b.self.toLowerCase())) return null;
  // the fact sheet read back: its personality words, or its "<town>, <year>" tag
  if (/\b(warm and chatty|gruff and guarded|talk warmly|talk curtly)\b/i.test(t) || /,\s*(1[89]|20)\d\d\b/.test(t)
    || /^an? [a-z ,]*\b(response|reply|answer|tone|voice)\b/i.test(t)) return null;
  const low = t.toLowerCase();
  if (b.must.some((m) => !low.includes(m.toLowerCase()))) return null;
  // a name the facts never mention ("We have two kids, Jake and Lisa."): a capitalized word in mid-sentence the prompt lacks
  const known = b.system + '\n' + b.user;
  if (b.system) for (const m of t.matchAll(/\b[A-Z][a-z]+\b/g)) {
    const before = t.slice(0, m.index).trimEnd();
    if (!before || /[.!?"“‘(]$/.test(before) || COMMON.has(m[0])) continue;
    if (!new RegExp(`\\b${m[0]}\\b`).test(known)) return null;
  }
  return t;
}
