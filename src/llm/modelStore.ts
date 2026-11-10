// Keeps the model file on the device, once. The 0.4 GB GGUF is chosen by the player (or found next to
// the game as models/qwen3-0.6b-q4_k_m.gguf) and copied into the browser's private storage (OPFS), so
// after that it loads with no network at all.

const NAME = 'qwen3-0.6b.gguf';

async function dir(): Promise<FileSystemDirectoryHandle | null> {
  try { return (await navigator.storage?.getDirectory?.()) ?? null; } catch { return null; }
}

export async function savedModel(): Promise<File | null> {
  try { const d = await dir(); return d ? await (await d.getFileHandle(NAME)).getFile() : null; } catch { return null; }
}

export async function saveModel(blob: Blob): Promise<boolean> {
  try {
    const d = await dir();
    if (!d) return false;
    const w = await (await d.getFileHandle(NAME, { create: true })).createWritable();
    await blob.stream().pipeTo(w);
    return true;
  } catch { return false; }
}

export async function forgetModel(): Promise<void> {
  try { await (await dir())?.removeEntry(NAME); } catch { /* nothing saved */ }
}

/** The model shipped alongside the game (dev server, Android assets), if there is one. */
export async function bundledModel(): Promise<Blob | null> {
  try {
    const r = await fetch('models/qwen3-0.6b-q4_k_m.gguf');
    const type = r.headers.get('content-type') ?? '';
    if (!r.ok || type.includes('text/html')) return null;
    return await r.blob();
  } catch { return null; }
}
