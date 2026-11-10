import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt, isAlive } from '../sim';
import { narrate, optionsFor, specFor, type TalkCtx } from './dialogue';

const w = worldAt(runTimeline({ seed: 'alpha', endYear: 1990 }), 1990);
const person = Object.values(w.people).find((p) => isAlive(p))!;
const ctx = (o: Partial<TalkCtx> = {}): TalkCtx => ({ w, person, job: 'works at Bakery', townName: 'Testville', familiarity: 0, witnesses: 0, wanted: false, ...o });

describe('talking to people', () => {
  it('strangers only chat: nice/gift/rumor/drastic are gated by familiarity', () => {
    const on = (c: TalkCtx) => optionsFor(c).filter((o) => o.enabled).map((o) => o.id);
    expect(on(ctx())).toEqual(['chat', 'town', 'family', 'work']);
    expect(on(ctx({ familiarity: 1 }))).toContain('gift');
    expect(on(ctx({ familiarity: 1 }))).not.toContain('drastic');
    expect(on(ctx({ familiarity: 2 }))).toContain('drastic');
  });
  it('violence is never a top-level button and warns about witnesses', () => {
    const d = optionsFor(ctx({ familiarity: 2, witnesses: 2 })).find((o) => o.id === 'drastic')!;
    expect(d.kind).toBe('danger');
    expect(d.note).toMatch(/2 people/);
  });
  it('nobody talks to a wanted player', () => {
    expect(optionsFor(ctx({ wanted: true, familiarity: 5 }))).toEqual([]);
    expect(narrate(ctx({ wanted: true }), 'greet').length).toBeGreaterThan(0);
  });
  it('maps options to simulation actions and has a line for every intent', () => {
    expect(specFor('smear')).toEqual({ kind: 'rumor', amount: -1 });
    expect(specFor('chat')).toBeNull();
    for (const i of ['greet', 'chat', 'town', 'family', 'work', 'praise', 'gift']) expect(narrate(ctx(), i).length).toBeGreaterThan(3);
  });
});
