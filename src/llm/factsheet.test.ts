import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt, isAlive } from '../sim';
import { narrate, type TalkCtx } from '../game/dialogue';
import { brief, toPrompt, validate } from './factsheet';

const w = worldAt(runTimeline({ seed: 'alpha', endYear: 1990 }), 1990);
const person = Object.values(w.people).find((p) => isAlive(p) && p.spouse)!;
const ctx: TalkCtx = { w, person, job: 'works at Bakery', townName: 'Testville', familiarity: 1, witnesses: 0, wanted: false, journal: [] };

describe('LLM fact sheet', () => {
  const b = brief(ctx, 'family', narrate(ctx, 'family'));
  it('puts the real facts in the prompt', () => {
    expect(b.system).toContain(person.first);
    expect(b.system).toContain('1990');
    expect(b.system).toContain('Testville');
    expect(b.system).toMatch(/Married to|Widowed/);
    expect(toPrompt(b)).toMatch(/<think>\n\n<\/think>\n\n$/);
  });
  it('cleans replies and rejects bad ones', () => {
    const b = brief(ctx, 'chat', narrate(ctx, 'chat'));
    expect(validate('"I\'m married to Bob." <|im_end|>', b)).toBe("I'm married to Bob.");
    expect(validate('As an AI language model, I cannot', b)).toBeNull();
    expect(validate('', b)).toBeNull();
    expect(validate('x'.repeat(400), b)).toBeNull();
    expect(validate('One. Two. Three. Four. Five.', b)).toBe('One. Two.');
    expect(validate('A warm smile flickers in my eyes as I say yes.', b)).toBeNull();
    expect(validate('Glad to help. Let me know if there is anything else.', b)).toBeNull();
    expect(validate(`I am ${b.self} and I live here.`, b)).toBeNull();
  });
  it('family answers must name the spouse or a child', () => {
    const spouse = w.people[person.spouse!]!;
    const f = brief(ctx, 'family', 'x');
    expect(f.must.length).toBeGreaterThan(0);
    expect(validate('I have a lovely family.', f)).toBeNull();
    expect(validate(`I'm married to ${spouse.first}.`, brief({ ...ctx, person: { ...person, children: [] } }, 'family', 'x'))).toMatch(spouse.first);
  });
  it('requires the facts that must be mentioned', () => {
    const s = brief(ctx, 'rumors', 'x', ['Secret: Ann Lee is in debt.'], ['Ann']);
    expect(validate('Between us, Ann is broke.', s)).toBe('Between us, Ann is broke.');
    expect(validate('I heard nothing.', s)).toBeNull();
  });
});
