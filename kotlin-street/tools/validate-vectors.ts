// Runs OUR validate() (src/llm/factsheet.ts) over tricky replies and writes the answers as Kotlin test data, so the app's
// port (app/src/main/kotlin/llm/Dialogue.kt) is checked against the real thing.
// Usage: npx tsx kotlin-street/tools/validate-vectors.ts > kotlin-street/app/src/test/kotlin/ValidateVectors.kt
import { validate, type Brief } from '../../src/llm/factsheet';

const SELF = 'Lois Fairchild';
/** The prompt's text, for the check on names it never mentions. */
const KNOWN = 'You are Lois Fairchild, 81. Married to Bob Lee. Children: Ann, Carl. Keep this quiet, but Walter Vance is in debt.';
type Case = [raw: string, must: string[]];
const cases: Case[] = [
  // the TypeScript tests' own cases
  ['"I\'m married to Bob." <|im_end|>', []],
  ['As an AI language model, I cannot', []],
  ['', []],
  ['x'.repeat(400), []],
  ['One. Two. Three. Four. Five.', []],
  ['A warm smile flickers in my eyes as I say yes.', []],
  ['Glad to help. Let me know if there is anything else.', []],
  [`I am ${SELF} and I live here.`, []],
  ['Between us, Ann is broke.', ['Ann']],
  ['I heard nothing.', ['Ann']],
  // think blocks, special tokens, quotes, actions, whitespace
  ['<think>Let me think.</think> Hello there.', []],
  ['<think>\nfoo\n</think>\n\n"Well, hello."', []],
  ['<think> never closed. Hello there.', []],
  ['Hi there.<|endoftext|><|im_end|>', []],
  ['<|im_end|>', []],
  ['“Good morning,” she said.', []],
  ['”Odd quotes“', []],
  ['\'Tis a fine day.\'', []],
  ['‘Morning to you.’', []],
  ['"\'"', []],
  ['*smiles* Nice to meet you.', []],
  ['**bold** text here.', []],
  ['*nods*', []],
  ['Fine. *waves* Off you go.', []],
  ['Hello there. Fine\tday.', []],
  ['﻿Hello there.﻿', []],
  ['Line one.\nLine two.\nLine three.', []],
  ['Hello.\r\nBye.', []],
  ['  " Hello. "  ', []],
  [' Hi there. ', []],
  ['A᠎B is fine.', []],
  // sentence splitting
  ['Hello there!!! How are you??? Fine.', []],
  ['Well... I suppose so. Yes.', []],
  ['No punctuation at all', []],
  ['Ends with question? and more', []],
  ['Hi.Bye.Ok.', []],
  ['Wait! Really? Yes. No.', []],
  ['Yes... no... maybe.', []],
  ['First one. Second one.', []],
  ['A. B. C', []],
  ['Mr. Vance is home. He works late. Always.', []],
  // assistant-speak and stage directions
  ['My assistant said hi.', []],
  ['Our language models are fine.', []],
  ['Assistants everywhere.', []],
  ['éassistant is a word here.', []],
  ['_assistant is not.', []],
  ['I\'m an AI, sure.', []],
  ['SORRY, I can\'t say.', []],
  ['Sorry, it is late.', []],
  ['i am an ai model.', []],
  ['Well, as an AI would say.', []],
  ['SMILES all round.', []],
  ['I\'m smiling today.', []],
  ['He nodded off.', []],
  ['She nods at you.', []],
  ['My eyes are tired.', []],
  ['How can I help you?', []],
  ['how can i assist?', []],
  ['How can I tell?', []],
  ['Let me know.', []],
  ['Anything else?', []],
  ['Anything elsewhere?', []],
  ['The flicker of a lamp.', []],
  ['Lamps flicker at night.', []],
  ['I say, what a day.', []],
  ['Hi say hello.', []],
  ['I respond to letters.', []],
  ['ſmiles are rare.', []],
  ['Kind of you.', ['kind']],
  // self and must
  [`lois fairchild here.`, []],
  ['Lois is my name.', []],
  ['walter is fine.', ['Walter']],
  ['Walt is fine.', ['Walter']],
  ['The Fairchild store is mine.', ['Fairchild']],
  ['I run the store.', ['Fairchild']],
  ['Ray and Edna are well.', ['Ray', 'Edna']],
  ['Ray is well.', ['Ray', 'Edna']],
  ['Hm.', ['']],
  // lengths
  ['Hi.', []],
  ['Hi', []],
  ['  Hi  ', []],
  ['a'.repeat(320), []],
  ['a'.repeat(321), []],
  ['Fine. ' + 'b'.repeat(330), []],
  ['🙂 Lovely day. Truly. Yes.', []],
  ['Lovely day 🙂. Truly 🙂 lovely. Yes.', []],
  // the fact sheet read back
  ['I talk curtly to strangers.', []],
  ['A curt, measured response: It is a nice day.', []],
  ['An answer: fine.', []],
  ['A fine day, Maple Hollow, 2025.', []],
  ['Maple Hollow,2025 is home.', []],
  // names the facts never mention
  ['We have two kids, Jake and Lisa.', []],
  ['Married to Bob, with Ann and Carl.', ['Bob']],
  ['Ann is grown now. Carl too.', []],
  ['Between you and Walter, money is tight.', []],
  ['On Sunday, Mr Vance came by.', []],
  ['Heard from Bobby lately?', []],
  ['Café is open. "Vernon" said so.', []],
  ['It is a nice day, Vernon.', []],
];

const brief = (must: string[]): Brief => ({ system: KNOWN, user: '', must, self: SELF });
/** A Kotlin string literal. */
const kt = (s: string): string => '"' + [...s].map((ch) => {
  const out: string[] = [];
  for (let i = 0; i < ch.length; i++) {
    const c = ch[i]!, code = ch.charCodeAt(i);
    out.push(c === '"' ? '\\"' : c === '\\' ? '\\\\' : c === '$' ? '\\$' : c === '\n' ? '\\n' : c === '\r' ? '\\r' : c === '\t' ? '\\t'
      : code < 0x20 || code > 0x7e ? `\\u${code.toString(16).padStart(4, '0')}` : c);
  }
  return out.join('');
}).join('') + '"';

const rows = cases.map(([raw, must]) => {
  const out = validate(raw, brief(must));
  return `        V(${kt(raw)}, listOf(${must.map(kt).join(', ')}), ${out === null ? 'null' : kt(out)}),`;
});
console.log(`// Generated by tools/validate-vectors.ts from validate() in src/llm/factsheet.ts. Do not edit.
package com.timerpg.street.llm

object ValidateVectors {
    const val SELF = ${kt(SELF)}
    const val KNOWN = ${kt(KNOWN)}

    class V(val raw: String, val must: List<String>, val expected: String?)

    val all = listOf(
${rows.join('\n')}
    )
}`);
