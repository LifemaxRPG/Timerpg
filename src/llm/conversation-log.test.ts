import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { ask, narrate, type TalkCtx } from '../game/dialogue';
import { brief, freeQuestion, repeats, validate, whyRejected, type Brief } from './factsheet';
import { originOf } from '../sim/origin';
import { heardBy, secretsOf } from '../sim/knowledge';
import { townName } from '../world/names';

// The owner's first real conversation, on the S25 (reports branch, talk/2026-11-23/060618-642-p3.jsonl): Gladys Chen
// (imm:2021:1) in gamma, 2025. Her replies are checked against her real fact sheets, as validate() runs them in play.

const w = worldAt(runTimeline({ seed: 'gamma', endYear: 2025 }), 2025);
const gladys = w.people['imm:2021:1']!;
const town = townName('gamma');
const ctx: TalkCtx = { w, person: gladys, job: 'works at Thorne\'s Gym', townName: town, familiarity: 0, witnesses: 0, wanted: false, journal: [] };
const friend: TalkCtx = { ...ctx, familiarity: 3 };

/** The rumors sheet as the exporter writes it (tools/export-dialogue.ts): the secret she tells a friend. */
function rumors(): Brief {
  const got = ask(friend, 'rumors');
  const facts = got.entry ? [`You are quietly telling the player this secret: ${got.entry.aboutName} ${got.entry.text}. Say it in your own words and ask them to keep it quiet.`] : [];
  return brief(friend, 'rumors', got.line, facts, got.entry ? [got.entry.aboutName.split(' ')[0]!] : []);
}

const LOG = [
  'Hello there, how are you getting along? The weather is quite lovely today.',
  'Yes, I am from Mapleridge. It is a lovely place to call home.',
  'Yes, I can hear you quite clearly. It is a lovely day for it, isn\'t it?',
  'I have lived in Mapleridge all my life. It is a lovely day for it, isn\'t it?',
  'I have not shared any secrets with anyone yet. Perhaps we can talk about something else instead.',
  'I have heard whispers about the old mill, but I do not know them myself. Perhaps we could talk about the new library instead?',
  'The new library is a lovely place for reading, and I am glad you asked about it.',
];

describe('the owner\'s first conversation log (Gladys Chen)', () => {
  it('is the same person, and the town is the placeholder name made from the seed', () => {
    expect(`${gladys.first} ${gladys.surname}`).toBe('Gladys Chen');
    expect(town).toBe('Mapleridge');
  });

  it('her sheet says she moved here in 2021, and from where', () => {
    const o = originOf(w, gladys);
    expect(o).toMatchObject({ how: 'moved', year: 2021 });
    expect(o.from).toBeTruthy();
    expect(o.from).not.toBe(town);
    const chat = brief(ctx, 'chat', narrate(ctx, 'chat'));
    expect(chat.system).toContain(`who moved to Mapleridge from ${o.from} in 2021`);
  });

  it('her sheets say what she really knows: the gossip she tells, and her own secret kept', () => {
    // she has heard something about someone else, and the rumors sheet tells it (with the name it must say)
    expect(heardBy(w, gladys, 0).length).toBeGreaterThan(0);
    const r = rumors();
    expect(r.system).toContain('You are quietly telling the player this secret: Margaret Singh');
    expect(r.must).toEqual(['Margaret']);
    // her own: she has one and keeps it, the topic marked as one she would rather not talk about
    expect(secretsOf(w, gladys).length).toBeGreaterThan(0);
    const s = brief(friend, 'secret', ask(friend, 'secret').line);
    expect(s.system).toContain('You have a private matter of your own, but you do not trust the player enough yet');
    expect(s.system).toContain('You would rather not talk about it.');
    // nothing about a mill or a library anywhere in her facts
    for (const b of [r, s, brief(ctx, 'chat', 'x')]) expect(b.system).not.toMatch(/\b(mill|library)\b/i);
  });

  it('turns down the bad replies of the log and lets truthful ones through', () => {
    const chat = brief(ctx, 'chat', narrate(ctx, 'chat'));
    const said = (i: number): string[] => LOG.slice(0, i);
    // 1-3: fine on their own
    expect(validate(LOG[0]!, freeQuestion(chat, 'Hey'))).toBe(LOG[0]);
    // 2: she moved here: "I am from Mapleridge" is false
    const q2 = freeQuestion(chat, 'Yeah it is pretty nice weather are you from here', said(1));
    expect(whyRejected(LOG[1]!, q2)).toMatch(/from here/);
    expect(validate('No, I moved here from out of town in 2021. It suits me fine.', q2)).toBeTruthy();
    // 3: "it is a lovely ..." again (her line before: "It is a lovely place to call home.")
    const q3 = freeQuestion(chat, 'Yo can you hear me', said(2));
    expect(whyRejected(LOG[2]!, q3)).toBe('said it again');
    expect(validate('Yes, loud and clear, dear.', q3)).toBe('Yes, loud and clear, dear.');
    // and the prompt quotes her last replies, with the rule not to repeat them
    expect(q3.system).toContain(`You already said: "${LOG[0]}" "${LOG[1]}" Do not repeat those words.`);
    // 4: wrong origin, and the same words as her last line
    const q4 = freeQuestion(chat, 'Yeah it is a lovely day anyways I was asking are you from here or did you move here', said(3));
    expect(validate(LOG[3]!, q4)).toBeNull();
    expect(repeats('It is a lovely day for it, isn\'t it?', said(3))).toBe(true);
    // 5-7: asking what she knows about the town is gossip (the app routes it to "rumors"), and she tells the real one
    const r = rumors();
    const q5 = freeQuestion(r, 'Do you know any secrets about this town', said(4), true);
    expect(whyRejected(LOG[4]!, q5)).toMatch(/Margaret|subject/);
    expect(validate(LOG[4]!.replace('.', ', but Margaret Singh is cross with Arthur Calloway.'), q5)).toBeNull(); // still changes the subject
    const q6 = freeQuestion(r, 'I didn\'t ask if you shared any secrets I asked if you knew any', said(5), true);
    expect(validate(LOG[5]!, q6)).toBeNull();
    expect(whyRejected('Margaret Singh told me about the old mill. Keep it quiet.', q6)).toMatch(/place/);
    expect(validate('Well, keep it quiet, but Margaret Singh holds a grudge against Arthur Calloway.', q6)).toBeTruthy();
    const q7 = freeQuestion(r, 'What\'s going on with the new library', said(6), true);
    expect(validate(LOG[6]!, q7)).toBeNull();
    expect(whyRejected('The new library is lovely. Margaret Singh loves it.', q7)).toMatch(/place/);
    expect(validate('A new library? I haven\'t heard of one, but Margaret Singh is still sore at Arthur Calloway, so keep that quiet.', q7)).toBeTruthy();
  });

  it('changing the subject is only for a topic marked as one she would rather not talk about', () => {
    const s = freeQuestion(brief(friend, 'secret', 'x'), 'Are you hiding anything?');
    expect(validate('That is my own business. Let\'s talk about something else.', s)).toBeTruthy();
    expect(validate('That is my own business. Let\'s talk about something else.', freeQuestion(brief(ctx, 'chat', 'x'), 'Hi'))).toBeNull();
  });

  it('gives people different manners', () => {
    const kinds = new Set(Object.values(w.people).filter((p) => p.deathYear === undefined && w.year - p.birthYear >= 18)
      .map((p) => brief({ ...ctx, person: p }, 'chat', 'x').system.split('\n')[3]));
    expect(kinds.size).toBeGreaterThanOrEqual(6);
  });
});
