import { describe, expect, it } from 'vitest';
import { runTimeline, worldAt, isAlive } from '../sim';
import { narrate, type TalkCtx } from '../game/dialogue';
import { brief, cinemaFacts, toPrompt, validate } from './factsheet';
import { cinemaProgram } from '../town/cinema';

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
    expect(validate('"Lovely day, isn\'t it?" <|im_end|>', b)).toBe("Lovely day, isn't it?");
    // a name the facts never mention is made up
    expect(validate('I\'m married to Bob.', b)).toBeNull();
    expect(validate('We have two kids, Jake and Lisa.', b)).toBeNull();
    expect(validate('It rained on Sunday, Mr Lee.', b)).toBeNull();
    expect(validate('It rained on Sunday, sadly.', b)).toBe('It rained on Sunday, sadly.');
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
  it('work answers must name the workplace, when there is one', () => {
    expect(brief(ctx, 'work', 'x').must).toEqual(['Bakery']);
    expect(brief({ ...ctx, job: 'works from home' }, 'work', 'x').must).toEqual([]);
    expect(validate('I work from home.', brief({ ...ctx, job: 'works from home' }, 'work', 'x'))).toBe('I work from home.');
  });
  it('requires the facts that must be mentioned', () => {
    const s = brief(ctx, 'rumors', 'x', ['Secret: Ann Lee is in debt.'], ['Ann']);
    expect(validate('Between us, Ann is broke.', s)).toBe('Between us, Ann is broke.');
    expect(validate('I heard nothing.', s)).toBeNull();
  });
  it('knows what is on at the cinema (alpha has one), and some people saw it', () => {
    const on = cinemaProgram('alpha', 1990).nowShowing;
    const chat = brief(ctx, 'chat', 'x');
    expect(chat.system).toContain(`"${on[0]!.title}"`);
    expect(brief(ctx, 'family', 'x').system).not.toContain(on[0]!.title);
    // the titles are in the facts, so a reply that names one passes the made-up-name check
    expect(validate(`Have you seen ${on[0]!.title}?`, chat)).toBeTruthy();
    const people = Object.values(w.people).filter((p) => isAlive(p) && w.year - p.birthYear >= 8);
    const saw = people.filter((p) => cinemaFacts({ ...ctx, person: p }).length === 2).length;
    expect(saw / people.length).toBeGreaterThan(0.25);
    expect(saw / people.length).toBeLessThan(0.55);
    // a town too small for a cinema has none to talk about
    const eta = worldAt(runTimeline({ seed: 'eta', endYear: 1990 }), 1990);
    const etaPerson = Object.values(eta.people).find(isAlive)!;
    expect(cinemaFacts({ ...ctx, w: eta, person: etaPerson })).toEqual([]);
  });
});
