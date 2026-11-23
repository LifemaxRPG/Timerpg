import { describe, expect, it } from 'vitest';
import { act, advance, create } from './index';
import { CAPITAL_IDS, buildStubCapital } from './stubCapital';

describe.sequential('src/loop performance (300 people)', () => {
  it('runs advance(sim, 24) for 300 people well under 50 ms in Node', () => {
    let sim = create(buildStubCapital({ seed: 'perf-300-people', year: 1978, peopleCount: 300 }));
    expect(Object.keys(sim.peopleMeta)).toHaveLength(300);

    // Seed active crime + gossip + pawn trace load so advance(24) exercises every subsystem
    sim = act(sim, {
      kind: 'take',
      subkind: 'shoplift',
      placeId: CAPITAL_IDS.haleStore,
      item: { id: 'item:perf_1', label: 'a tin of peaches', value: 3 },
      bystanders: [
        { id: CAPITAL_IDS.windowWoman, position: { x: 40, y: -1 }, facing: { x: 0, y: 1 }, activity: 'watching' },
        { id: CAPITAL_IDS.neighbourEd, position: { x: 43, y: 1 }, facing: { x: -1, y: 1 }, activity: 'idle' },
      ],
    }).sim;

    sim = act(sim, {
      kind: 'take',
      subkind: 'house',
      placeId: CAPITAL_IDS.elmHouse,
      item: { id: 'item:perf_watch', label: 'a pocket watch', value: 120, serialNumber: 'PRF-90012' },
      bystanders: [],
    }).sim;

    sim = act(sim, {
      kind: 'sell',
      placeId: CAPITAL_IDS.pawnShop,
      item: { id: 'item:perf_watch', label: 'a pocket watch', value: 60, serialNumber: 'PRF-90012' },
    }).sim;

    // Warm up JIT once
    sim = advance(sim, 24).sim;

    const timingsMs: number[] = [];
    for (let i = 0; i < 5; i++) {
      const t0 = performance.now();
      const res = advance(sim, 24);
      const dt = performance.now() - t0;
      sim = res.sim;
      timingsMs.push(dt);
    }

    const sorted = timingsMs.slice().sort((a, b) => a - b);
    const median = sorted[Math.floor(sorted.length / 2)]!;
    const max = sorted[sorted.length - 1]!;
    const mean = timingsMs.reduce((a, b) => a + b, 0) / timingsMs.length;

    // Log genuine measured Node timings for DELIVERY.md
    console.log(
      `[G011 perf] 300 people, advance(sim, 24): median=${median.toFixed(2)}ms mean=${mean.toFixed(2)}ms max=${max.toFixed(2)}ms samples=[${timingsMs.map((n) => n.toFixed(2)).join(', ')}]`,
    );

    expect(median).toBeLessThan(50);
    expect(max).toBeLessThan(50);
  });
});
