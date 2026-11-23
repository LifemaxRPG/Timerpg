import {
  PLAN_VERBS,
  type Combinator,
  type Condition,
  type ConditionAtom,
  type ItemRef,
  type Manner,
  type ParseResult,
  type PlaceTargetRef,
  type Plan,
  type PlanStage,
  type PlanStep,
  type PlanVerb,
  type SubjectTargetRef,
  type TargetRef,
} from './types';

const VERB_SET: ReadonlySet<string> = new Set(PLAN_VERBS);

function isWs(ch: string): boolean {
  return ch === ' ' || ch === '\t' || ch === '\n';
}

function isLowerAlpha(ch: string): boolean {
  const c = ch.charCodeAt(0);
  return c >= 97 && c <= 122;
}

function isIdRest(ch: string): boolean {
  const c = ch.charCodeAt(0);
  return (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c === 95;
}

function isDigit(ch: string): boolean {
  const c = ch.charCodeAt(0);
  return c >= 48 && c <= 57;
}

class Parser {
  private readonly src: string;
  private readonly len: number;
  pos = 0;

  constructor(src: string) {
    this.src = src;
    this.len = src.length;
  }

  private fail(msg: string, offset = this.pos): never {
    const err = new Error(msg) as Error & { offset: number };
    err.offset = offset;
    throw err;
  }

  private eof(): boolean {
    return this.pos >= this.len;
  }

  private peek(): string {
    return this.pos < this.len ? this.src[this.pos]! : '';
  }

  private startsWithWord(word: string): boolean {
    if (!this.src.startsWith(word, this.pos)) return false;
    const after = this.pos + word.length;
    if (after >= this.len) return true;
    return isWs(this.src[after]!);
  }

  private consumeWs(): boolean {
    const start = this.pos;
    while (this.pos < this.len && isWs(this.src[this.pos]!)) {
      this.pos++;
    }
    return this.pos > start;
  }

  private expectWs(context: string): void {
    if (!this.consumeWs()) {
      this.fail(`expected whitespace ${context}`);
    }
  }

  private expectLiteral(lit: string): void {
    if (!this.src.startsWith(lit, this.pos)) {
      this.fail(`expected '${lit}'`);
    }
    this.pos += lit.length;
  }

  private parseId(label: string): string {
    const start = this.pos;
    if (this.pos >= this.len || !isLowerAlpha(this.src[this.pos]!)) {
      this.fail(`expected ${label} starting with [a-z]`);
    }
    this.pos++;
    while (this.pos < this.len && isIdRest(this.src[this.pos]!)) {
      this.pos++;
    }
    return this.src.slice(start, this.pos);
  }

  private parseRole(): string {
    const start = this.pos;
    this.parseId('role word');
    while (this.pos < this.len && this.src[this.pos] === ' ') {
      this.pos++;
      this.parseId('role word');
    }
    return this.src.slice(start, this.pos);
  }

  private parseVerb(): PlanVerb {
    const start = this.pos;
    const word = this.parseId('verb');
    if (!VERB_SET.has(word)) {
      this.fail(`unknown verb '${word}'`, start);
    }
    return word as PlanVerb;
  }

  private tryParseItemRef(): ItemRef | undefined {
    if (!this.src.startsWith('item:', this.pos)) return undefined;
    this.pos += 5;
    const id = this.parseId('item id');
    return { kind: 'item', id };
  }

  private parseItemRef(): ItemRef {
    const ref = this.tryParseItemRef();
    if (!ref) this.fail(`expected item:<id>`);
    return ref;
  }

  private tryParseTarget(): TargetRef | undefined {
    const save = this.pos;
    if (this.src.startsWith('person:', this.pos)) {
      this.pos += 7;
      return { kind: 'person', id: this.parseId('person id') };
    }
    if (this.src.startsWith('place:', this.pos)) {
      this.pos += 6;
      return { kind: 'place', id: this.parseId('place id') };
    }
    if (this.src.startsWith('item:', this.pos)) {
      this.pos += 5;
      return { kind: 'item', id: this.parseId('item id') };
    }
    if (this.src.startsWith('vehicle:', this.pos)) {
      this.pos += 8;
      return { kind: 'vehicle', id: this.parseId('vehicle id') };
    }
    if (this.src.startsWith('nearest(', this.pos)) {
      this.pos += 8;
      const entityKind = this.parseId('nearest kind');
      this.expectLiteral(')');
      return { kind: 'nearest', entityKind };
    }
    if (this.startsWithTargetBoundary('player')) {
      this.pos += 6;
      return { kind: 'player' };
    }
    if (this.startsWithTargetBoundary('here')) {
      this.pos += 4;
      return { kind: 'here' };
    }
    this.pos = save;
    return undefined;
  }

  private startsWithTargetBoundary(word: string): boolean {
    if (!this.src.startsWith(word, this.pos)) return false;
    const after = this.pos + word.length;
    if (after >= this.len) return true;
    const ch = this.src[after]!;
    return isWs(ch) || ch === ')' || ch === ',';
  }

  private parseTargetInParens(): TargetRef {
    const t = this.tryParseTarget();
    if (!t) this.fail('expected target');
    return t;
  }

  private tryParseManner(): Manner | undefined {
    if (this.startsWithWord('quietly')) {
      this.pos += 7;
      return { kind: 'quietly' };
    }
    if (this.startsWithWord('quickly')) {
      this.pos += 7;
      return { kind: 'quickly' };
    }
    if (this.startsWithWord('carefully')) {
      this.pos += 9;
      return { kind: 'carefully' };
    }
    if (this.startsWithWord('politely')) {
      this.pos += 8;
      return { kind: 'politely' };
    }
    if (this.startsWithWord('rudely')) {
      this.pos += 6;
      return { kind: 'rudely' };
    }
    if (this.src.startsWith('as(', this.pos)) {
      this.pos += 3;
      const role = this.parseRole();
      this.expectLiteral(')');
      return { kind: 'as', role };
    }
    return undefined;
  }

  private parseHhmm(): { hhmm: string; secondsOfDay: number } {
    const start = this.pos;
    if (this.pos + 5 > this.len) {
      this.fail('expected hh:mm time', start);
    }
    const h0 = this.src[this.pos]!;
    const h1 = this.src[this.pos + 1]!;
    const colon = this.src[this.pos + 2]!;
    const m0 = this.src[this.pos + 3]!;
    const m1 = this.src[this.pos + 4]!;
    if (!isDigit(h0) || !isDigit(h1) || colon !== ':' || !isDigit(m0) || !isDigit(m1)) {
      this.fail('expected hh:mm time', start);
    }
    const hh = (h0.charCodeAt(0) - 48) * 10 + (h1.charCodeAt(0) - 48);
    const mm = (m0.charCodeAt(0) - 48) * 10 + (m1.charCodeAt(0) - 48);
    if (hh > 23 || mm > 59) {
      this.fail(`invalid 24-hour clock time '${this.src.slice(start, start + 5)}'`, start);
    }
    this.pos += 5;
    return {
      hhmm: this.src.slice(start, start + 5),
      secondsOfDay: hh * 3600 + mm * 60,
    };
  }

  private parseStepRef(): number {
    const start = this.pos;
    if (this.pos >= this.len) this.fail('expected positive step number');
    const first = this.src[this.pos]!;
    if (first < '1' || first > '9') {
      this.fail('step number must start with 1-9', start);
    }
    this.pos++;
    while (this.pos < this.len && isDigit(this.src[this.pos]!)) {
      this.pos++;
    }
    return Number(this.src.slice(start, this.pos));
  }

  private parseAtPlace(): PlaceTargetRef {
    const start = this.pos;
    const t = this.tryParseTarget();
    if (!t || (t.kind !== 'place' && t.kind !== 'nearest' && t.kind !== 'here')) {
      this.fail('expected place:<id>, nearest(<kind>), or here', start);
    }
    return t;
  }

  private parseConditionAtom(): ConditionAtom {
    if (this.src.startsWith('sees(', this.pos)) {
      this.pos += 5;
      const target = this.parseTargetInParens();
      this.expectLiteral(')');
      return { kind: 'sees', target };
    }
    if (this.src.startsWith('at(', this.pos)) {
      this.pos += 3;
      const firstStart = this.pos;
      const first = this.parseTargetInParens();
      if (this.peek() === ',') {
        if (
          first.kind !== 'person' &&
          first.kind !== 'vehicle' &&
          first.kind !== 'player' &&
          first.kind !== 'nearest'
        ) {
          this.fail('at() subject must be person, vehicle, player, or nearest(<kind>)', firstStart);
        }
        this.pos++; // ','
        this.consumeWs();
        const place = this.parseAtPlace();
        this.expectLiteral(')');
        return { kind: 'at', subject: first as SubjectTargetRef, place };
      }
      if (first.kind !== 'place' && first.kind !== 'nearest' && first.kind !== 'here') {
        this.fail('at() target must be place:<id>, nearest(<kind>), or here', firstStart);
      }
      this.expectLiteral(')');
      return { kind: 'at', place: first as PlaceTargetRef };
    }
    if (this.src.startsWith('time(', this.pos)) {
      this.pos += 5;
      const { hhmm, secondsOfDay } = this.parseHhmm();
      this.expectLiteral(')');
      return { kind: 'time', hhmm, secondsOfDay };
    }
    if (this.src.startsWith('heard(', this.pos)) {
      this.pos += 6;
      const signal = this.parseId('signal');
      this.expectLiteral(')');
      return { kind: 'heard', signal };
    }
    if (this.src.startsWith('has(', this.pos)) {
      this.pos += 4;
      const item = this.parseItemRef();
      this.expectLiteral(')');
      return { kind: 'has', item };
    }
    if (this.startsWithWord('alone')) {
      this.pos += 5;
      return { kind: 'alone' };
    }
    if (this.src.startsWith('done(', this.pos)) {
      this.pos += 5;
      const stepNumber = this.parseStepRef();
      this.expectLiteral(')');
      return { kind: 'done', stepNumber };
    }
    this.fail('expected condition (sees, at, time, heard, has, alone, done)');
  }

  private parseCondition(): Condition {
    let negated = false;
    if (this.startsWithWord('not')) {
      this.pos += 3;
      this.expectWs("after 'not'");
      negated = true;
    }
    const atom = this.parseConditionAtom();
    return { negated, atom };
  }

  private parseStep(index: number): PlanStep {
    const verb = this.parseVerb();
    const step: PlanStep = { index, verb };

    // Optional target
    const beforeTarget = this.pos;
    if (this.consumeWs()) {
      const target = this.tryParseTarget();
      if (target) {
        step.target = target;
      } else {
        this.pos = beforeTarget;
      }
    }

    // Optional `with item:<id>`
    const beforeWith = this.pos;
    if (this.consumeWs()) {
      if (this.startsWithWord('with')) {
        this.pos += 4;
        this.expectWs("after 'with'");
        step.withItem = this.parseItemRef();
      } else {
        this.pos = beforeWith;
      }
    }

    // Optional manner
    const beforeManner = this.pos;
    if (this.consumeWs()) {
      const manner = this.tryParseManner();
      if (manner) {
        step.manner = manner;
      } else {
        this.pos = beforeManner;
      }
    }

    // Optional `until <cond>`
    const beforeUntil = this.pos;
    if (this.consumeWs()) {
      if (this.startsWithWord('until')) {
        this.pos += 5;
        this.expectWs("after 'until'");
        step.until = this.parseCondition();
      } else {
        this.pos = beforeUntil;
      }
    }

    // Optional `if <cond>`
    const beforeIf = this.pos;
    if (this.consumeWs()) {
      if (this.startsWithWord('if')) {
        this.pos += 2;
        this.expectWs("after 'if'");
        step.ifCond = this.parseCondition();
      } else {
        this.pos = beforeIf;
      }
    }

    return step;
  }

  parsePlan(): Plan {
    if (this.len === 0) {
      this.fail('empty plan');
    }
    if (isWs(this.src[0]!)) {
      this.fail('leading whitespace is not allowed', 0);
    }

    const steps: PlanStep[] = [];
    const combinators: Combinator[] = [];

    steps.push(this.parseStep(1));

    while (!this.eof()) {
      const wsStart = this.pos;
      if (!this.consumeWs()) {
        this.fail(`unexpected token '${this.src.slice(this.pos, this.pos + 16)}'`);
      }
      if (this.eof()) {
        this.fail('trailing whitespace is not allowed', wsStart);
      }
      let comb: Combinator;
      if (this.startsWithWord('then')) {
        comb = 'then';
        this.pos += 4;
      } else if (this.startsWithWord('while')) {
        comb = 'while';
        this.pos += 5;
      } else {
        this.fail(`expected 'then' or 'while', found '${this.src.slice(this.pos, this.pos + 16)}'`);
      }
      this.expectWs(`after '${comb}'`);
      combinators.push(comb);
      steps.push(this.parseStep(steps.length + 1));
    }

    // Group contiguous `while`-linked steps into concurrent stages separated by `then`.
    const stages: PlanStage[] = [];
    let stageStart = 0;
    for (let i = 0; i < combinators.length; i++) {
      if (combinators[i] === 'then') {
        stages.push({ start: stageStart, end: i + 1 });
        stageStart = i + 1;
      }
    }
    stages.push({ start: stageStart, end: steps.length });

    return {
      source: this.src,
      steps,
      combinators,
      stages,
    };
  }
}

/**
 * Parses a plan string using the exact syntax specified in `src/plan/plan.gbnf`.
 */
export function parsePlan(source: string): ParseResult {
  try {
    const parser = new Parser(source);
    const plan = parser.parsePlan();
    return { ok: true, plan };
  } catch (err) {
    const e = err as Error & { offset?: number };
    return {
      ok: false,
      error: e.message || 'parse error',
      offset: typeof e.offset === 'number' ? e.offset : 0,
    };
  }
}
