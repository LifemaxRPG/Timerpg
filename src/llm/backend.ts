import type { Wllama } from '@wllama/wllama';
import singleWasm from '@wllama/wllama/esm/single-thread/wllama.wasm?url';
import multiWasm from '@wllama/wllama/esm/multi-thread/wllama.wasm?url';
import { RULES, toPrompt, validate, type Brief } from './factsheet';
import { bundledModel, saveModel, savedModel } from './modelStore';

/** Can this page start a module worker from a blob? (Not when opened straight from a file.) */
function workersWork(): Promise<boolean> {
  return new Promise((resolve) => {
    try {
      const url = URL.createObjectURL(new Blob(['postMessage(1)'], { type: 'text/javascript' }));
      const w = new Worker(url, { type: 'module' });
      const done = (ok: boolean): void => { w.terminate(); URL.revokeObjectURL(url); resolve(ok); };
      w.onmessage = () => done(true);
      w.onerror = () => done(false);
      setTimeout(() => done(false), 3000);
    } catch { resolve(false); }
  });
}

export type LlmState = 'off' | 'loading' | 'ready' | 'error' | 'slow';

/**
 * The on-device narrator: Qwen3-0.6B running in WebAssembly (wllama / llama.cpp). Nothing here ever
 * touches the network except an optional fetch of a model file that sits next to the game.
 */
export class Llm {
  state: LlmState = 'off';
  detail = 'Dialogue AI is off. Add the model file to turn it on.';
  onChange: (s: LlmState, detail: string) => void = () => {};
  /** Kept for the game to hook (it no longer freezes the world while the model thinks). */
  onBusy: (busy: boolean) => void = () => {};
  private w: Wllama | null = null;
  private cache = new Map<string, string>();
  private misses = 0;
  /** Is a generation still running inside the model (even if we gave up waiting for it)? */
  private inflight = false;
  /** Numbers for the panel, so a real device's speed can be read off the screen. */
  stats = { loadSecs: 0, replies: 0, lastSecs: 0, timeouts: 0, rejected: 0, skipped: 0, benchSecs: 0, benchTokens: 0 };
  /** Recent events, newest last, for the diagnostics box in the panel. */
  diag: string[] = [];
  /** Seconds a reply may take before the built-in line is used instead. */
  timeoutMs = 20000;

  private log(msg: string): void {
    this.diag.push(`${new Date().toLocaleTimeString()} ${msg}`);
    if (this.diag.length > 10) this.diag.shift();
    this.onChange(this.state, this.detail);
  }
  private set(state: LlmState, detail: string): void { this.state = state; this.detail = detail; this.log(`${state}: ${detail}`); }

  /** Turn the AI off for this session (built-in lines only). */
  disable(): void { this.state = 'off'; this.detail = 'Dialogue AI is off (using built-in lines).'; this.log('turned off by the player'); }

  /** Run `p`, but give up waiting after `ms` (the model itself may keep going; see `inflight`). */
  private race<T>(p: Promise<T>, ms: number, onTimeout: () => void): Promise<T | 'timeout'> {
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { onTimeout(); resolve('timeout'); }, ms);
      p.then((v) => { clearTimeout(t); resolve(v); }, (e) => { clearTimeout(t); reject(e); });
    });
  }

  /** Load the model from a file the player picked (and remember it) or from storage / next to the game. */
  async load(picked?: Blob): Promise<void> {
    if (this.state === 'loading' || this.state === 'ready') return;
    const t0 = performance.now();
    this.set('loading', 'Looking for the model…');
    if (!(await workersWork())) {
      this.set('error', 'The dialogue AI cannot run when the game is opened as a plain file. Use the Android app, or serve the game over http. Built-in lines are used meanwhile.');
      return;
    }
    try {
      let blob: Blob | null = picked ?? (await savedModel()) ?? (await bundledModel());
      if (!blob) { this.set('off', 'No model file found. Choose qwen3-0.6b-q4_k_m.gguf.'); return; }
      this.log(`model file ${(blob.size / 1048576).toFixed(0)} MB`);
      if (picked) { this.set('loading', 'Saving the model on this device…'); await saveModel(picked); }
      this.set('loading', 'Starting the model…');
      const { Wllama } = await import('@wllama/wllama');
      const threads = typeof SharedArrayBuffer !== 'undefined' && self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 2) : 1;
      const embedded = (window as unknown as { __EMBED__?: { wasm?: string } }).__EMBED__?.wasm;
      this.w = new Wllama(embedded ? { 'single-thread/wllama.wasm': embedded } : { 'single-thread/wllama.wasm': singleWasm, 'multi-thread/wllama.wasm': multiWasm }, { allowOffline: true });
      // (A batch smaller than the prompt makes llama.cpp abort, so keep it at 256.)
      await this.w.loadModel([blob], { n_ctx: 1024, n_batch: 256, n_threads: threads });
      blob = null;
      this.stats.loadSecs = (performance.now() - t0) / 1000;
      this.log(`loaded in ${this.stats.loadSecs.toFixed(1)} s (${threads} thread${threads > 1 ? 's' : ''})`);
      // Speed test: is this device fast enough to be worth it?
      this.set('loading', 'Testing speed…');
      const ok = await this.calibrate();
      if (ok) this.set('ready', `Dialogue AI is on (Qwen3-0.6B, ${threads} thread${threads > 1 ? 's' : ''}).`);
    } catch (e) {
      this.w = null;
      this.set('error', `Could not start the model: ${(e as Error).message}`);
    }
  }

  /** One short generation, timed. Marks the AI too slow if it takes far too long. */
  private async calibrate(): Promise<boolean> {
    const w = this.w!;
    const t = performance.now();
    let tokens = 0;
    this.inflight = true;
    const ctl = new AbortController();
    try {
      const r = await this.race(
        w.createCompletion(`<|im_start|>system\n${RULES}\n<|im_end|>\n<|im_start|>user\nHello.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`, {
          nPredict: 10, abortSignal: ctl.signal, useCache: true, sampling: { temp: 0.3 }, onNewToken: () => { tokens++; },
        }),
        45000, () => ctl.abort(),
      );
      this.stats.benchSecs = (performance.now() - t) / 1000;
      this.stats.benchTokens = tokens;
      if (r === 'timeout') { this.set('slow', `Speed test did not finish in 45 s (${tokens} words so far). This device is too slow for the AI, so built-in lines are used.`); return false; }
      this.log(`speed test: ${this.stats.benchSecs.toFixed(1)} s for ${tokens} words`);
      if (this.stats.benchSecs > 25) { this.set('slow', `Speed test took ${this.stats.benchSecs.toFixed(0)} s: too slow, so built-in lines are used.`); return false; }
      return true;
    } catch (e) {
      this.set('error', `Speed test failed: ${(e as Error).message}`);
      return false;
    } finally { this.inflight = false; }
  }

  /** One line of dialogue, or null (not ready, busy, too slow, or the reply failed the fact check). */
  async say(b: Brief, key: string, timeoutMs = this.timeoutMs): Promise<string | null> {
    const hit = this.cache.get(key);
    if (hit) return hit;
    if (this.state !== 'ready' || !this.w) return null;
    // The model is one resource: if it is still busy (even with something we stopped waiting for), do not queue behind it.
    if (this.inflight) { this.stats.skipped++; this.log('skipped: model still busy'); return null; }
    const w = this.w;
    this.inflight = true;
    this.onBusy(true);
    const started = performance.now();
    const ctl = new AbortController();
    let tokens = 0, first = 0;
    const gen = w.createCompletion(toPrompt(b), {
      nPredict: 56, abortSignal: ctl.signal, useCache: true,
      sampling: { temp: 0.3, top_k: 40, top_p: 0.9, penalty_repeat: 1.15 },
      onNewToken: () => { if (!tokens) first = performance.now() - started; tokens++; },
    });
    // Whatever happens, the model is free again only when its own promise settles.
    gen.then(() => { this.inflight = false; }, () => { this.inflight = false; });
    try {
      const raw = await this.race(gen, timeoutMs, () => ctl.abort());
      this.onBusy(false);
      this.stats.lastSecs = (performance.now() - started) / 1000;
      if (raw === 'timeout') {
        this.stats.timeouts++;
        this.log(`timed out after ${timeoutMs / 1000} s (${tokens} words, first at ${(first / 1000).toFixed(1)} s)`);
        if (++this.misses >= 2) this.set('slow', 'The model is too slow on this device, so dialogue uses the built-in lines.');
        return null;
      }
      const text = validate(raw, b);
      if (text) { this.cache.set(key, text); this.misses = 0; this.stats.replies++; } else this.stats.rejected++;
      this.log(`reply in ${this.stats.lastSecs.toFixed(1)} s, ${tokens} words, ${text ? 'used' : 'rejected by the fact check'}`);
      return text;
    } catch (e) {
      this.onBusy(false);
      this.log(`error: ${(e as Error).message}`);
      return null;
    }
  }
}
