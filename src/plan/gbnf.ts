/**
 * Minimal, general llama.cpp GBNF grammar compiler and full-string matcher.
 *
 * Used by tests to verify that `src/plan/plan.gbnf` and `src/plan/parser.ts`
 * accept and reject the exact same set of plan strings.
 */

type GbnfNode =
  | { kind: 'lit'; text: string }
  | { kind: 'class'; negated: boolean; ranges: Array<[number, number]> }
  | { kind: 'ref'; name: string }
  | { kind: 'seq'; items: GbnfNode[] }
  | { kind: 'alt'; branches: GbnfNode[] }
  | { kind: 'rep'; item: GbnfNode; min: number; max: number };

export interface CompiledGbnf {
  root: string;
  rules: ReadonlyMap<string, GbnfNode>;
}

function unescapeChar(src: string, pos: number): { code: number; next: number } {
  const ch = src[pos]!;
  if (ch !== '\\') return { code: ch.charCodeAt(0), next: pos + 1 };
  const esc = src[pos + 1];
  switch (esc) {
    case 'n':
      return { code: 10, next: pos + 2 };
    case 't':
      return { code: 9, next: pos + 2 };
    case 'r':
      return { code: 13, next: pos + 2 };
    case '"':
      return { code: 34, next: pos + 2 };
    case '\\':
      return { code: 92, next: pos + 2 };
    case ']':
      return { code: 93, next: pos + 2 };
    default:
      if (esc === undefined) throw new Error(`dangling backslash in GBNF at ${pos}`);
      return { code: esc.charCodeAt(0), next: pos + 2 };
  }
}

class GbnfParser {
  private readonly src: string;
  private pos = 0;

  constructor(src: string) {
    this.src = src;
  }

  private skipSpaceAndComments(stopAtNewline = false): void {
    while (this.pos < this.src.length) {
      const ch = this.src[this.pos]!;
      if (ch === ' ' || ch === '\t' || ch === '\r') {
        this.pos++;
      } else if (!stopAtNewline && ch === '\n') {
        this.pos++;
      } else if (ch === '#') {
        while (this.pos < this.src.length && this.src[this.pos] !== '\n') {
          this.pos++;
        }
      } else {
        break;
      }
    }
  }

  private isRuleStartAhead(): boolean {
    const save = this.pos;
    this.skipSpaceAndComments(false);
    const name = this.tryReadRuleName();
    if (!name) {
      this.pos = save;
      return false;
    }
    this.skipSpaceAndComments(true);
    const isRule = this.src.startsWith('::=', this.pos);
    this.pos = save;
    return isRule;
  }

  private tryReadRuleName(): string | undefined {
    const start = this.pos;
    while (this.pos < this.src.length && /[a-zA-Z0-9_-]/.test(this.src[this.pos]!)) {
      this.pos++;
    }
    return this.pos > start ? this.src.slice(start, this.pos) : undefined;
  }

  parse(): CompiledGbnf {
    const rules = new Map<string, GbnfNode>();
    let firstRule = '';
    while (true) {
      this.skipSpaceAndComments(false);
      if (this.pos >= this.src.length) break;
      const name = this.tryReadRuleName();
      if (!name) throw new Error(`expected GBNF rule name at ${this.pos}`);
      if (!firstRule) firstRule = name;
      this.skipSpaceAndComments(true);
      if (!this.src.startsWith('::=', this.pos)) {
        throw new Error(`expected '::=' after rule '${name}' at ${this.pos}`);
      }
      this.pos += 3;
      const expr = this.parseAlt();
      rules.set(name, expr);
    }
    const root = rules.has('root') ? 'root' : firstRule;
    if (!root) throw new Error('empty GBNF grammar');
    return { root, rules };
  }

  private parseAlt(): GbnfNode {
    const branches: GbnfNode[] = [this.parseSeq()];
    while (true) {
      const save = this.pos;
      this.skipSpaceAndComments(false);
      if (this.src[this.pos] === '|') {
        this.pos++;
        branches.push(this.parseSeq());
      } else {
        this.pos = save;
        break;
      }
    }
    return branches.length === 1 ? branches[0]! : { kind: 'alt', branches };
  }

  private parseSeq(): GbnfNode {
    const items: GbnfNode[] = [];
    while (true) {
      const save = this.pos;
      this.skipSpaceAndComments(true);
      if (this.pos >= this.src.length) break;
      const ch = this.src[this.pos]!;
      if (ch === '|' || ch === ')') break;
      if (ch === '\n') {
        // Multi-line continuation allowed only if next non-empty line is NOT a new rule definition
        if (this.isRuleStartAhead()) {
          this.pos = save;
          break;
        }
        this.skipSpaceAndComments(false);
        const nextCh = this.src[this.pos];
        if (!nextCh || nextCh === '|' || nextCh === ')') {
          this.pos = save;
          break;
        }
      }
      const atom = this.parsePostfix();
      if (!atom) {
        this.pos = save;
        break;
      }
      items.push(atom);
    }
    return items.length === 1 ? items[0]! : { kind: 'seq', items };
  }

  private parsePostfix(): GbnfNode | undefined {
    const atom = this.parseAtom();
    if (!atom) return undefined;
    const q = this.src[this.pos];
    if (q === '*') {
      this.pos++;
      return { kind: 'rep', item: atom, min: 0, max: Infinity };
    }
    if (q === '+') {
      this.pos++;
      return { kind: 'rep', item: atom, min: 1, max: Infinity };
    }
    if (q === '?') {
      this.pos++;
      return { kind: 'rep', item: atom, min: 0, max: 1 };
    }
    return atom;
  }

  private parseAtom(): GbnfNode | undefined {
    const ch = this.src[this.pos];
    if (!ch) return undefined;
    if (ch === '"') {
      this.pos++;
      let text = '';
      while (this.pos < this.src.length && this.src[this.pos] !== '"') {
        const u = unescapeChar(this.src, this.pos);
        text += String.fromCharCode(u.code);
        this.pos = u.next;
      }
      if (this.src[this.pos] !== '"') throw new Error(`unterminated string literal in GBNF`);
      this.pos++;
      return { kind: 'lit', text };
    }
    if (ch === '[') {
      this.pos++;
      let negated = false;
      if (this.src[this.pos] === '^') {
        negated = true;
        this.pos++;
      }
      const ranges: Array<[number, number]> = [];
      while (this.pos < this.src.length && this.src[this.pos] !== ']') {
        const start = unescapeChar(this.src, this.pos);
        this.pos = start.next;
        if (this.src[this.pos] === '-' && this.src[this.pos + 1] !== ']') {
          this.pos++;
          const end = unescapeChar(this.src, this.pos);
          this.pos = end.next;
          ranges.push([start.code, end.code]);
        } else {
          ranges.push([start.code, start.code]);
        }
      }
      if (this.src[this.pos] !== ']') throw new Error(`unterminated char class in GBNF`);
      this.pos++;
      return { kind: 'class', negated, ranges };
    }
    if (ch === '(') {
      this.pos++;
      const inner = this.parseAlt();
      this.skipSpaceAndComments(false);
      if (this.src[this.pos] !== ')') throw new Error(`expected ')' in GBNF at ${this.pos}`);
      this.pos++;
      return inner;
    }
    if (/[a-zA-Z_]/.test(ch)) {
      const name = this.tryReadRuleName()!;
      return { kind: 'ref', name };
    }
    return undefined;
  }
}

export function compileGbnf(source: string): CompiledGbnf {
  return new GbnfParser(source).parse();
}

/**
 * Evaluates whether `input` matches the entire compiled GBNF grammar from `root`.
 * Uses memoization on `(ruleName, offset)` so arbitrary unambiguous/CFG grammars stay fast.
 */
export function matchesGbnf(grammar: CompiledGbnf, input: string): boolean {
  const memo = new Map<string, number[]>();

  function evalNode(node: GbnfNode, pos: number): number[] {
    switch (node.kind) {
      case 'lit':
        return input.startsWith(node.text, pos) ? [pos + node.text.length] : [];
      case 'class': {
        if (pos >= input.length) return [];
        const code = input.charCodeAt(pos);
        let hit = false;
        for (let i = 0; i < node.ranges.length; i++) {
          const [lo, hi] = node.ranges[i]!;
          if (code >= lo && code <= hi) {
            hit = true;
            break;
          }
        }
        return hit !== node.negated ? [pos + 1] : [];
      }
      case 'ref': {
        const key = `${node.name}@${pos}`;
        const cached = memo.get(key);
        if (cached) return cached;
        const target = grammar.rules.get(node.name);
        if (!target) throw new Error(`undefined GBNF rule '${node.name}'`);
        // Guard against left-recursion by inserting empty placeholder before evaluating
        memo.set(key, []);
        const res = evalNode(target, pos);
        memo.set(key, res);
        return res;
      }
      case 'seq': {
        let positions = [pos];
        for (let i = 0; i < node.items.length; i++) {
          const nextSet = new Set<number>();
          const item = node.items[i]!;
          for (let j = 0; j < positions.length; j++) {
            const ends = evalNode(item, positions[j]!);
            for (let k = 0; k < ends.length; k++) {
              nextSet.add(ends[k]!);
            }
          }
          if (nextSet.size === 0) return [];
          positions = Array.from(nextSet);
        }
        return positions;
      }
      case 'alt': {
        const out = new Set<number>();
        for (let i = 0; i < node.branches.length; i++) {
          const ends = evalNode(node.branches[i]!, pos);
          for (let j = 0; j < ends.length; j++) {
            out.add(ends[j]!);
          }
        }
        return Array.from(out);
      }
      case 'rep': {
        const results = new Set<number>();
        let frontier = new Set<number>([pos]);
        let count = 0;
        while (frontier.size > 0 && count <= node.max) {
          if (count >= node.min) {
            for (const p of frontier) results.add(p);
          }
          if (count === node.max) break;
          const nextFrontier = new Set<number>();
          for (const p of frontier) {
            const ends = evalNode(node.item, p);
            for (const e of ends) {
              // Avoid zero-length infinite loops
              if (e > p) nextFrontier.add(e);
            }
          }
          frontier = nextFrontier;
          count++;
        }
        return Array.from(results);
      }
    }
  }

  const rootNode = grammar.rules.get(grammar.root);
  if (!rootNode) throw new Error(`missing root rule '${grammar.root}'`);
  const ends = evalNode(rootNode, 0);
  return ends.includes(input.length);
}
