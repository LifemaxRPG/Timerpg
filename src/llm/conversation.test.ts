import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt } from '../sim';
import { isAlive } from '../sim/types';
import { narrate, type TalkCtx } from '../game/dialogue';
import { townName } from '../world/names';
import {
  brief,
  collectToldTopics,
  deriveMood,
  followUp,
  formatToldGuidance,
  summarizeConversation,
  validate,
  voiceOf,
  whyRejected,
  withConversation,
  withMood,
  type ConversationTurn,
  type MemoryLine,
} from './factsheet';
import { formatEvalReport, runConversationEval } from './eval';

const w = worldAt(runTimeline({ seed: 'gamma', endYear: 2025 }), 2025);
const gladys = w.people['imm:2021:1']!;
const grump = Object.values(w.people).find((p) => isAlive(p) && w.year - p.birthYear >= 20 && voiceOf(p).kind === 'grump')!;

const baseCtx: TalkCtx = {
  w,
  person: gladys,
  job: 'retired',
  townName: townName('gamma'),
  familiarity: 0,
  witnesses: 0,
  wanted: false,
  journal: [],
};

const friendCtx: TalkCtx = { ...baseCtx, familiarity: 3 };
const grumpCtx: TalkCtx = { ...baseCtx, person: grump, job: 'works at the general store', familiarity: 0 };

const makeMem = (overrides: Partial<MemoryLine> & Pick<MemoryLine, 'eventId' | 'kind' | 'line' | 'source' | 'heardOnly'>): MemoryLine => ({
  certainty: overrides.heardOnly ? 0.5 : 0.9,
  relevance: 0.8,
  ageDays: 1,
  tone: 'neutral',
  cues: overrides.line.toLowerCase().replace(/[^a-z0-9\s]+/g, ' ').split(/\s+/).filter((x) => x.length >= 4),
  ...overrides,
});

describe('G024 / conversation summary', () => {
  it('summarizes the last few turns concisely and replaces any earlier summary cleanly', () => {
    const turns: ConversationTurn[] = [
      { player: 'Hey', npc: 'Hello there!', topic: 'chat' },
      { player: 'Are you from here?', npc: 'No, I moved here in 2021.', topic: 'origin', toldFacts: ['moved here in 2021'] },
      { player: 'What do you do?', npc: 'I am retired now.', topic: 'work', toldFacts: ['retired'] },
      { player: 'Heard any gossip?', npc: 'Margaret Singh is cross with Arthur Calloway.', topic: 'rumors', toldFacts: ['rumor:Margaret Singh and Arthur Calloway'] },
      { player: 'Nice afternoon.', npc: 'Quiet enough on the street.', topic: 'chat' },
    ];

    const summary = summarizeConversation(turns, 4);
    expect(summary).toContain('Conversation so far:');
    expect(summary).toContain('Answer what the player just asked now');
    // Keeps only last 4 turns (drops turn 1 "Hey")
    expect(summary).not.toContain('Hello there!');
    expect(summary).toContain('moved here in 2021');
    expect(summary).toContain('Margaret Singh');

    const b1 = withConversation(brief(baseCtx, 'chat', narrate(baseCtx, 'chat')), turns.slice(0, 2));
    const b2 = withConversation(b1, turns);
    expect(b2.system.match(/Conversation so far:/g)?.length).toBe(1);
  });

  it('does not leak player-invented places from prior turns into the fact sheet grounding check', () => {
    const b = followUp(brief(baseCtx, 'chat', narrate(baseCtx, 'chat')), 'Anything else happening?', {
      turns: [{ player: 'Did you visit the new castle?', npc: "A new castle? I haven't heard of one.", topic: 'chat' }],
    });
    expect(b.system).toContain('Conversation so far:');
    expect(whyRejected('Folks have been flocking to the new castle all week.', b)).toMatch(/made up a place/);
    expect(validate('Nothing much, just quiet errands around town.', b)).toBeTruthy();
  });
});

describe('G024 / told-topic marking and topic variety', () => {
  const rumor1 = 'You heard that Margaret Singh holds a grudge against Arthur Calloway. Tell the player quietly.';
  const rumor2 = 'You also heard that Thomas Reed fell behind on rent last winter. Tell the player quietly.';

  it('marks an already-told rumour, clears its stale must word, and rejects repeating it', () => {
    const turns: ConversationTurn[] = [
      {
        player: 'Do you know any secrets about this town?',
        npc: 'Keep it quiet, Margaret Singh holds a grudge against Arthur Calloway.',
        topic: 'rumors',
        toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
      },
    ];

    const told = collectToldTopics(turns);
    expect(told.length).toBe(1);
    expect(told[0]!.subjects).toContain('margaret');

    const exhaustedGuidance = formatToldGuidance(told, 'rumors', []);
    expect(exhaustedGuidance.exhausted).toBe(true);
    expect(exhaustedGuidance.lines.join('\n')).toContain('Already told in this talk:');
    expect(exhaustedGuidance.lines.join('\n')).toContain('nothing more');

    const b = followUp(brief(friendCtx, 'rumors', 'x', [rumor1], ['Margaret']), 'Heard anything else?', {
      turns,
      currentTopic: 'rumors',
      keepMust: true,
    });

    // Stale must word "Margaret" dropped so turn 2 is not forced into a loop
    expect(b.must).not.toContain('Margaret');
    expect(b.allowNothingMore).toBe(true);

    // Repeating the same rumour without adding anything new is rejected
    expect(whyRejected('Word is Margaret Singh cannot stand Arthur Calloway.', b)).toBe('repeated an already-told topic');

    // Honestly saying they have nothing more (even naming Margaret to close it) passes
    expect(validate("I've nothing more to tell you about Margaret Singh, honest.", b)).toBeTruthy();
    expect(validate("That's all I know for now. Let's talk about something else.", b)).toBeTruthy();
  });

  it('switches to a fresh second rumour when available instead of repeating the first', () => {
    const turns: ConversationTurn[] = [
      {
        player: 'Any gossip?',
        npc: 'Keep it quiet, Margaret Singh holds a grudge against Arthur Calloway.',
        topic: 'rumors',
        toldFacts: ['rumor:Margaret Singh and Arthur Calloway'],
      },
    ];

    const b = followUp(brief(friendCtx, 'rumors', 'x', [rumor1], ['Margaret']), 'Anything else going around?', {
      turns,
      currentTopic: 'rumors',
      freshFacts: [rumor2],
      freshMust: ['Thomas'],
      keepMust: true,
    });

    expect(b.system).toContain('share the new detail instead');
    expect(b.must).toEqual(['Thomas']);
    expect(validate('Only that Thomas Reed fell behind on his rent last winter, keep it quiet.', b)).toBeTruthy();
    expect(validate('Margaret Singh is still cross with Arthur Calloway.', b)).toBeNull();
  });
});

describe('G024 / mood that fits the facts', () => {
  it('derives mood and hostility permission strictly from facts, memories, hour, and player words', () => {
    // Stranger daytime -> polite, no hostility
    const stranger = deriveMood({ familiarity: 0, hour: 14, voiceKind: 'grump', playerSaid: 'Nice afternoon.' });
    expect(stranger.mood).toBe('polite');
    expect(stranger.allowsHostility).toBe(false);
    expect(stranger.promptLine).toContain('Stay civil');

    // Late night -> tired, still civil
    const late = deriveMood({ familiarity: 0, hour: 23, playerSaid: 'Still up?' });
    expect(late.mood).toBe('tired');
    expect(late.allowsHostility).toBe(false);

    // Friend + kindness memory -> warm, strong
    const kindMem = makeMem({ eventId: 'e1', kind: 'kindness', line: 'You helped me carry groceries.', source: 'direct', heardOnly: false });
    const warm = deriveMood({ familiarity: 3, memories: [kindMem], hour: 11, playerSaid: 'How are you?' });
    expect(warm.mood).toBe('warm');
    expect(warm.strength).toBe('strong');
    expect(warm.allowsHostility).toBe(false);

    // Heard-only crime memory -> guarded (mild), NO police threat hostility
    const heardMem = makeMem({ eventId: 'e2', kind: 'crime', line: 'Lois told me someone stole a watch.', source: 'heard_friend', heardOnly: true });
    const cautious = deriveMood({ familiarity: 1, memories: [heardMem], hour: 12, playerSaid: 'Hello.' });
    expect(cautious.mood).toBe('guarded');
    expect(cautious.allowsHostility).toBe(false);

    // Witnessed crime memory -> guarded (strong), hostility/warning allowed
    const seenMem = makeMem({ eventId: 'e3', kind: 'crime', line: 'I saw you steal a watch.', source: 'seen', heardOnly: false });
    const witness = deriveMood({ familiarity: 0, memories: [seenMem], hour: 12, playerSaid: 'Hey.' });
    expect(witness.allowsHostility).toBe(true);

    // Wanted -> afraid (strong), hostility/warning allowed
    const wanted = deriveMood({ familiarity: 2, wanted: true, hour: 15, playerSaid: 'Wait.' });
    expect(wanted.mood).toBe('afraid');
    expect(wanted.strength).toBe('strong');
    expect(wanted.allowsHostility).toBe(true);

    // Provoked by hostile player -> hostile (strong), allowed
    const provoked = deriveMood({ familiarity: 0, hour: 15, playerSaid: 'Shut up idiot, hand over your wallet!' });
    expect(provoked.mood).toBe('hostile');
    expect(provoked.allowsHostility).toBe(true);
  });

  it('rejects unprovoked "shut up before I call the police" on grumpy strangers, but allows it when grounded', () => {
    const civilBrief = followUp(brief(grumpCtx, 'chat', narrate(grumpCtx, 'chat')), 'Good morning, quiet day?', { hour: 9 });
    expect(whyRejected('Shut up before I call the police!', civilBrief)).toBe('unprovoked hostility');
    expect(validate('Quiet enough, and I have work to do.', civilBrief)).toBeTruthy();

    const provokedBrief = withMood(civilBrief, { familiarity: 0, playerSaid: 'Shut up idiot or else!' });
    expect(validate('Get out of my face before I call the police!', provokedBrief)).toBeTruthy();

    const wantedBrief = followUp(brief({ ...grumpCtx, wanted: true }, 'chat', 'x'), 'Hold on.', { wanted: true });
    expect(validate('Stay back or I will call the police!', wantedBrief)).toBeTruthy();
  });
});

describe('G024 / fewer false fallbacks without weakening quality checks', () => {
  it('accepts natural grounded speech that used to false-reject while keeping bad replies blocked', () => {
    const townBrief = followUp(brief(baseCtx, 'town', narrate(baseCtx, 'town')), "What's going on with the new library?");

    // Honest "anything else" passes; assistant sign-off stays blocked
    expect(validate("No, I haven't heard anything else.", townBrief)).toBeTruthy();
    expect(whyRejected('Glad to help. Let me know if there is anything else.', townBrief)).toBe('stage directions');

    // Honest non-knowledge idioms pass; inventing the library stays blocked
    expect(validate("First I'm hearing of the new library, friend.", townBrief)).toBeTruthy();
    expect(validate('Beats me about the new library.', townBrief)).toBeTruthy();
    expect(whyRejected('The new library is lovely.', townBrief)).toMatch(/made up a place/);

    // Grounded mid-sentence title ("the Mayor") passes when sheet mentions mayor; made-up names stay blocked
    expect(validate('Ask the Mayor if you want town news.', townBrief)).toBeTruthy();
    expect(whyRejected('Ask Bob if you want town news.', townBrief)).toMatch(/made up a name/);

    // Idiomatic "Like I say" passes; stage narration stays blocked
    expect(validate('Like I say, quiet week around town.', townBrief)).toBeTruthy();
    expect(whyRejected('A warm smile flickers in my eyes as I say yes.', townBrief)).toBe('stage directions');

    // Grounded 3-sentence reply > 320 chars trims to 2 sentences instead of failing too-long; 400-char wall stays blocked
    const threeSents =
      'It is a quiet town most days, and folks mostly keep to their errands down along the street without much fuss at all. ' +
      'You can always ask the Mayor if you want to hear about how the town is run these days and what is coming up next. ' +
      'Anyway, the weather looks mild enough for the rest of the afternoon if you are walking around seeing the sights.';
    expect(threeSents.length).toBeGreaterThan(320);
    const trimmed = validate(threeSents, townBrief);
    expect(trimmed).toBeTruthy();
    expect(trimmed!.length).toBeLessThanOrEqual(320);
    expect(whyRejected('x'.repeat(400), townBrief)).toBe('too long');
  });
});

describe('G024 / 24-conversation before-vs-after evaluation', () => {
  it('scores >= 20 multi-turn conversations and improves every metric after G024', () => {
    const summary = runConversationEval();
    expect(summary.total).toBeGreaterThanOrEqual(20);
    expect(summary.after.scenariosPassed).toBe(summary.total);
    expect(summary.after.briefOk).toBe(summary.total);
    expect(summary.after.falseFallbacks).toBe(0);
    expect(summary.after.falseFallbacks).toBeLessThan(summary.before.falseFallbacks);
    expect(summary.after.badCaught).toBe(summary.after.badChecked);
    expect(summary.after.badCaught).toBeGreaterThan(summary.before.badCaught);
    expect(formatEvalReport(summary)).toContain('24/24 (100%)');
  });
});
