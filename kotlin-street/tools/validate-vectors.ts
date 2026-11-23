// Runs OUR validate() (src/llm/factsheet.ts) over tricky replies and writes the answers as Kotlin test data, so the app's
// port (app/src/main/kotlin/llm/Dialogue.kt) is checked against the real thing.
// Usage: npx tsx kotlin-street/tools/validate-vectors.ts > kotlin-street/app/src/test/kotlin/ValidateVectors.kt
import { validate, type Brief } from '../../src/llm/factsheet';

const SELF = 'Lois Fairchild';
/** The prompt's text, for the check on names it never mentions. */
const KNOWN = 'You are Lois Fairchild, 81. Married to Bob Lee. Children: Ann, Carl. Keep this quiet, but Walter Vance is in debt.';
/** The reply, the words it must name, and optionally the player's own words (the user turn), their last replies and other facts. */
type Case = [raw: string, must: string[], user?: string, recent?: string[], known?: string];
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
  // places and events: only those the facts name; the player's own words only when doubted or denied
  ['I heard whispers about the old mill.', []],
  ['The new library is lovely.', [], 'What about the new library?'],
  ['A new library? I haven\'t heard of one.', [], 'What about the new library?'],
  ['The library is not open yet.', [], 'Is the library open?'],
  ['THE OLD MILL burned down.', []],
  ['We met at the church.', []],
  ['We met at church on Sunday.', []],
  ['The fires were bad that year.', []],
  ['The fireman came.', []],
  ['An innocent man.', []],
  ['There was a scandal, I hear.', []],
  ['Our gym is busy.', [], '', [], 'You are Lois Fairchild, 81. Your job: works at Thorne\'s Gym.'],
  ['The supermarket is cheap.', [], '', [], 'You buy groceries at Pine Market downtown.'],
  ['The market is cheap.', [], '', [], 'You buy groceries at Pine Supermarket downtown.'],
  // names in the player's words only
  ['Yes, I know Tom well.', [], 'Do you know Tom?'],
  ['No, I don\'t know Tom.', [], 'Do you know Tom?'],
  ['Who, Tom? Never met him.', [], 'Do you know Tom?'],
  // where they are from
  ['I have lived in Maple Hollow all my life.', [], '', [], 'You are Lois Fairchild, 81, a woman who moved to Maple Hollow from Cedar Falls in 2021.'],
  ['Yes, I am from Maple Hollow.', [], '', [], 'You are Lois Fairchild, 81, a woman who moved to Maple Hollow from Cedar Falls in 2021.'],
  ['No, I moved here from Cedar Falls in 2021.', [], '', [], 'You are Lois Fairchild, 81, a woman who moved to Maple Hollow from Cedar Falls in 2021.'],
  ['I was born and raised in Cedar Falls.', [], '', [], 'You are Lois Fairchild, 81, a woman who moved to Maple Hollow from Cedar Falls in 2021.'],
  ['Not born here, no. I came in 2021.', [], '', [], 'You are Lois Fairchild, 81, a woman who moved to Maple Hollow from Cedar Falls in 2021.'],
  ['Born here? No, I came in 2021.', [], '', [], 'You are Lois Fairchild, 81, a woman who moved to Maple Hollow from Cedar Falls in 2021.'],
  ['I moved here in 1990.', [], '', [], 'You are Lois Fairchild, 81, a woman born and raised in Maple Hollow.'],
  ['I grew up here.', [], '', [], 'You are Lois Fairchild, 81, a woman born and raised in Maple Hollow.'],
  ['I\'m not from around here.', [], '', [], 'You are Lois Fairchild, 81, a woman born and raised in Maple Hollow.'],
  // changing the subject: only where the facts say they would rather not talk about it
  ['Perhaps we can talk about something else instead.', []],
  ['Let\'s talk about the weather instead.', []],
  ['Let\'s talk about something else.', [], '', [], 'You are Lois Fairchild. You have a private matter of your own. You would rather not talk about it.'],
  ['PERHAPS WE COULD CHAT ABOUT it.', []],
  ['Talk about luck, eh?', []],
  // their last replies said again
  ['It is a lovely day for it, isn\'t it?', [], '', ['Yes, I can hear you. It is a lovely day for it, isn\'t it?']],
  ['Lovely day for it, isn\'t it?', [], '', ['Lovely day for it, is it not?']],
  ['Yes, it is a fine day.', [], '', ['Yes, I can hear you quite clearly.']],
  ['I am fine, thank you.', [], '', ['I am fine, thanks.']],
  ['Fine.', [], '', ['Fine.']],
  ['The cinema is showing a film.', [], '', ['They say the cinema is showing a film tonight.'], 'The cinema, Avalon 8, is showing "Jax" this week.'],
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

const list = (a: string[]): string => `listOf(${a.map(kt).join(', ')})`;
const rows = cases.map(([raw, must, user = '', recent = [], known = KNOWN]) => {
  const out = validate(raw, { ...brief(must), system: known, user, recent });
  // the app's check reads the facts and the player's words from the ChatML prompt (the user turn is the player's)
  const prompt = known + (user ? `\n<|im_start|>user\n${user}<|im_end|>` : '');
  return `        V(${kt(raw)}, ${list(must)}, ${out === null ? 'null' : kt(out)}${prompt !== KNOWN || recent.length ? `, ${kt(prompt)}, ${list(recent)}` : ''}),`;
});
console.log(`// Generated by tools/validate-vectors.ts from validate() in src/llm/factsheet.ts. Do not edit.
package com.timerpg.street.llm

object ValidateVectors {
    const val SELF = ${kt(SELF)}
    const val KNOWN = ${kt(KNOWN)}

    class V(val raw: String, val must: List<String>, val expected: String?, val known: String = KNOWN, val recent: List<String> = emptyList())

    val all = listOf(
${rows.join('\n')}
    )
}`);
