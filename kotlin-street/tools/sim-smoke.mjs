#!/usr/bin/env node
// The JavaScript bridge's smoke run in Node (docs/LOOP.md, "The JavaScript bridge"): loads the same bundle the phone
// runs (assets/sim/sim.js) and makes a fixed sequence of calls, printing one JSON object: each call (fn, arg) with
// its result, and the time of advanceDay. SimJsTest (desktop) replays the same calls in QuickJS through SimJs and
// expects the same results, so the phone's engine and Node agree.
//
// node kotlin-street/tools/sim-smoke.mjs [sim.js] [--people N] [--days N]
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? Number(args[i + 1]) : d; };
const bundle = args[0] && !args[0].startsWith('--') ? args[0] : path.join(here, '../app/src/main/assets/sim/sim.js');
const people = opt('--people', 300);
const benchDays = opt('--days', 30);

// a fresh global per run, as one QuickJS context
const g = vm.createContext({});
vm.runInContext(fs.readFileSync(bundle, 'utf8'), g, { filename: 'sim.js' });

const calls = [];
const call = (fn, arg) => {
  const json = JSON.stringify(arg);
  const out = g[fn](json);
  calls.push({ fn, arg: json, out });
  return JSON.parse(out);
};

// A robbery at noon on Main Street: the victim and five bystanders, then three days of gossip and reports.
call('simCreate', { seed: 'bridge-smoke', people: 40, year: 1990, day: 0 });
const near = (i, x, y) => ({ id: `p:${String(i).padStart(4, '0')}`, x, y });
call('simRecordCrime', {
  id: 'crime:smoke:1', kind: 'robbery', hour: 13, victimId: 'p:0000',
  bystanders: [near(1, 4, 3), near(2, -6, 2), near(3, 12, -9), near(5, 20, 14), near(7, -3, -25)],
});
call('simKnowers', { crimeId: 'crime:smoke:1' });
for (let d = 0; d < 3; d++) {
  call('simAdvance', { days: 1 });
  call('simKnowers', { crimeId: 'crime:smoke:1' });
}

// The timing: a town of `people`, a robbery with 20 bystanders, `benchDays` days advanced one by one.
call('simCreate', { seed: 'bridge-bench', people, year: 1990, day: 0 });
const crowd = [];
for (let i = 1; i <= 20; i++) crowd.push(near(i, ((i * 37) % 41) - 20, ((i * 53) % 43) - 21));
call('simRecordCrime', { id: 'crime:bench:1', kind: 'robbery', hour: 13, victimId: 'p:0000', bystanders: crowd });
const times = [];
for (let d = 0; d < benchDays; d++) {
  const t0 = process.hrtime.bigint();
  call('simAdvance', { days: 1 });
  times.push(Number(process.hrtime.bigint() - t0) / 1e6);
}
call('simKnowers', { crimeId: 'crime:bench:1' });
const mean = times.reduce((a, b) => a + b, 0) / times.length;
console.log(JSON.stringify({ engine: `node ${process.version}`, people, benchDays, advanceDayMsMean: mean, advanceDayMsMax: Math.max(...times), calls }));
