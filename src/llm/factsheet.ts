import { fullName, type Person } from '../sim/types';
import type { TalkCtx } from '../game/dialogue';

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
  if (kids.length) out.push(`Children: ${kids.slice(0, 4).map((k) => k.first).join(', ')}.`);
  return out;
}

/** Intents the model may voice. "town" is left to templates: a 0.6B model only produces filler about it. */
export const VOICED = new Set(['greet', 'chat', 'family', 'work', 'praise', 'gift', 'rumors', 'secret', 'legend', 'news']);

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
    : [];
  // The answer must contain the real fact it is about (a name or the shop), or the template is used.
  const spouse = p.spouse ? w.people[p.spouse] : undefined;
  const kid = p.children.map((id) => w.people[id]).find((k) => k && k.deathYear === undefined);
  const anchor = intent === 'family' ? (spouse && spouse.deathYear === undefined ? spouse.first : kid?.first)
    : intent === 'work' && c.job.includes(' ') && !/^(retired|at school|out about)/.test(c.job) ? c.job.replace(/^works at /, '').split(/[\s']/)[0] : undefined;
  if (anchor) must = [...must, anchor];
  const sheet = [
    `You are ${fullName(p)}, ${w.year - p.birthYear}, ${p.sex === 'F' ? 'a woman' : 'a man'} in ${c.townName}, ${w.year}. You are ${warm ? 'warm and chatty' : 'gruff and guarded'}.`,
    `The player is ${know}.`,
    ...(c.wanted ? ['The player is wanted for a crime. You are afraid of them.'] : []),
    ...relevant,
    ...facts,
  ].join('\n');
  // The rules come first and never change, so the model's prompt cache can reuse them every time.
  const system = [RULES, sheet, `Example of the kind of answer: ${template.split('\n')[0]}`].join('\n');
  return { system, user: PLAYER[intent] ?? 'Hmm.', must, self: fullName(p) };
}

/** The raw text prompt (ChatML with thinking switched off, which Qwen3 expects). */
export const toPrompt = (b: Brief): string =>
  `<|im_start|>system\n${b.system}<|im_end|>\n<|im_start|>user\n${b.user}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`;

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
  const low = t.toLowerCase();
  if (b.must.some((m) => !low.includes(m.toLowerCase()))) return null;
  return t;
}
