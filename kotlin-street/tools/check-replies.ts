// Runs OUR validate() (src/llm/factsheet.ts, the game's source of truth) over real model replies written by the native
// host test (llm_host_test --dialogue ... --json-out FILE) and stores each verdict in the file as "ts" (the cleaned line,
// or null when it is rejected). HostRepliesTest then checks that the app's Kotlin port gives exactly the same verdicts.
// Usage: npx tsx kotlin-street/tools/check-replies.ts replies.json   (tools/llm-dialogue-check.sh runs it)
import { readFileSync, writeFileSync } from 'node:fs';
import { validate, type Brief } from '../../src/llm/factsheet';

interface Reply { label: string; raw: string; prompt: string; must: string[]; self: string; template: string; ts?: string | null }

const file = process.argv[2];
if (!file) {
  console.error('usage: check-replies.ts replies.json');
  process.exit(2);
}
const doc = JSON.parse(readFileSync(file, 'utf8')) as { replies: Reply[] };
let passed = 0;
for (const r of doc.replies) {
  // the whole prompt as the known text, as the app passes it (validate() only searches it for names)
  const b: Brief = { system: r.prompt, user: '', must: r.must, self: r.self };
  r.ts = validate(r.raw, b);
  if (r.ts !== null) passed++;
}
writeFileSync(file, JSON.stringify(doc, null, 1) + '\n');
console.log(`validate() in src/llm/factsheet.ts: ${passed} of ${doc.replies.length} replies pass (verdicts stored in ${file} as "ts")`);
