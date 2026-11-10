import type { Wllama } from '@wllama/wllama';
import singleWasm from '@wllama/wllama/esm/single-thread/wllama.wasm?url';
import multiWasm from '@wllama/wllama/esm/multi-thread/wllama.wasm?url';
import { toPrompt, validate, type Brief } from './factsheet';
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
  private w: Wllama | null = null;
  private cache = new Map<string, string>();
  private misses = 0;
  /** Seconds a reply may take before the template line is used instead. */
  timeoutMs = 25000;
  private busy: Promise<unknown> = Promise.resolve();

  private set(state: LlmState, detail: string): void { this.state = state; this.detail = detail; this.onChange(state, detail); }

  /** Load the model from a file the player picked (and remember it) or from storage / next to the game. */
  async load(picked?: Blob): Promise<void> {
    if (this.state === 'loading' || this.state === 'ready') return;
    this.set('loading', 'Looking for the model…');
    if (!(await workersWork())) {
      this.set('error', 'The dialogue AI cannot run when the game is opened as a plain file. Use the Android app, or serve the game over http. Built-in lines are used meanwhile.');
      return;
    }
    try {
      let blob: Blob | null = picked ?? (await savedModel()) ?? (await bundledModel());
      if (!blob) { this.set('off', 'No model file found. Choose qwen3-0.6b-q4_k_m.gguf.'); return; }
      if (picked) { this.set('loading', 'Saving the model on this device…'); await saveModel(picked); }
      this.set('loading', 'Starting the model…');
      const { Wllama } = await import('@wllama/wllama');
      const threads = typeof SharedArrayBuffer !== 'undefined' && self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 2) : 1;
      const embedded = (window as unknown as { __EMBED__?: { wasm?: string } }).__EMBED__?.wasm;
      this.w = new Wllama(embedded ? { 'single-thread/wllama.wasm': embedded } : { 'single-thread/wllama.wasm': singleWasm, 'multi-thread/wllama.wasm': multiWasm }, { allowOffline: true });
      await this.w.loadModel([blob], { n_ctx: 1024, n_batch: 256, n_threads: threads });
      blob = null;
      this.set('ready', `Dialogue AI is on (Qwen3-0.6B, ${threads} thread${threads > 1 ? 's' : ''}).`);
    } catch (e) {
      this.w = null;
      this.set('error', `Could not start the model: ${(e as Error).message}`);
    }
  }

  /** One line of dialogue, or null (not ready, too slow, or the reply failed the fact check). */
  async say(b: Brief, key: string, timeoutMs = this.timeoutMs): Promise<string | null> {
    const hit = this.cache.get(key);
    if (hit) return hit;
    if (this.state !== 'ready' || !this.w) return null;
    const w = this.w;
    const run = async (): Promise<string | null> => {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), timeoutMs);
      try {
        const raw = await w.createCompletion(toPrompt(b), {
          nPredict: 70, abortSignal: ctl.signal, useCache: true,
          sampling: { temp: 0.3, top_k: 40, top_p: 0.9, penalty_repeat: 1.15 },
        });
        const text = validate(raw, b);
        if (text) { this.cache.set(key, text); this.misses = 0; }
        return text;
      } catch {
        // Repeated timeouts mean this device is too slow: switch to template dialogue instead of making people wait.
        if (ctl.signal.aborted && ++this.misses >= 2) this.set('slow', 'The model is too slow on this device, so dialogue uses the built-in lines.');
        return null;
      } finally { clearTimeout(timer); }
    };
    // One generation at a time: the model is a single resource.
    const next = this.busy.then(run, run);
    this.busy = next.catch(() => null);
    return next;
  }
}
