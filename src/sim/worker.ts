// Background worker: generates a town's plan and its whole 1950-2025 history off the main thread,
// so walking into new territory never freezes the game. Messages are plain data (structured clone).
import { generate, type GenRequest, type GenResponse } from './generate';

export type { GenRequest, GenResponse };

const ctx = self as unknown as { onmessage: ((e: MessageEvent<GenRequest>) => void) | null; postMessage: (m: GenResponse) => void };
ctx.onmessage = (e) => ctx.postMessage(generate(e.data));
