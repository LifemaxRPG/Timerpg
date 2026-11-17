// Wave Function Collapse over furniture placements.
//
// The room is a grid of 32 px cells. Every cell starts in a superposition of "empty" and every piece of furniture that
// could stand on it (a piece covers a block of cells, so one placement is an option of all the cells it covers). The
// solver repeatedly takes the cell with the lowest entropy (fewest, most lopsided options), collapses it to one option
// by weighted chance from the seed, and propagates: placements that would overlap it, block the walkway in front of it,
// or exceed their piece's count are removed from every cell. Weights change as the room fills (a stove wants to stand
// next to a counter, a chair only exists under a table), which is what makes rooms read as arranged rather than random.
// Pieces a room must have are collapsed first, most constrained first. A finished room is checked as a whole (every free
// cell reachable from the door, every piece reachable from the front): if it fails, the solve restarts from another
// branch of the seed.
import { rngFor } from '../sim/rng';

/** Where a piece may stand. */
export type Place =
  /** with its back to the wall behind it (tall things: wardrobes, fridges, shelves, counters along a back wall) */
  | 'back'
  /** against any wall: the back or a side (low things) */
  | 'wall'
  /** in a corner (plants) */
  | 'corner'
  /** out in the room, with a free cell all around (tables, treadmills) */
  | 'free'
  /** anywhere */
  | 'any';

export interface PieceRule {
  /** the piece's name (what the caller draws) */
  name: string;
  /** size in cells */
  w: number; h: number;
  place: Place;
  min?: number; max?: number;
  /** base weight against the other options of a cell */
  weight?: number;
  /** needs the cells in front of it (below) free to be used */
  front?: boolean;
  /** only exists directly below one of these (a chair under a table or a desk) */
  under?: string[];
  /** weight multiplied by this for each of these pieces it touches (a stove next to a counter) */
  near?: Record<string, number>;
  /** weight multiplied by this for each of these it touches (two beds side by side read badly) */
  apart?: Record<string, number>;
}

export interface WfcRoom {
  /** grid size in cells */
  cols: number; rows: number;
  /** cells the furniture may use (inside the area, clear of walls) */
  usable: (cx: number, cy: number) => boolean;
  /** cells that must stay walkable (doorways, the way to the exit); still count as floor */
  keepClear: (cx: number, cy: number) => boolean;
  /** cells anyone can walk on, the whole room (for the reachability check) */
  walkable: (cx: number, cy: number) => boolean;
  /** where people come in (a walkable cell) */
  door: { cx: number; cy: number };
  /** cells this area owns (the reachability check only asks these to be reachable) */
  owned?: (cx: number, cy: number) => boolean;
}

export interface Placed { name: string; cx: number; cy: number; w: number; h: number }

interface Option { rule: number; cx: number; cy: number; alive: boolean }

/** Furnishes [room] with [rules]; null when no arrangement within [tries] satisfies them. */
export function collapse(room: WfcRoom, rules: PieceRule[], seed: string, key: string, tries = 40, emptyWeight = 2.5): Placed[] | null {
  for (let t = 0; t < tries; t++) {
    const out = attempt(room, rules, rngFor(seed, 'wfc', key, t), emptyWeight);
    if (out && reachable(room, out, rules)) return out;
  }
  return null;
}

function attempt(room: WfcRoom, rules: PieceRule[], rnd: () => number, emptyWeight: number): Placed[] | null {
  const { cols, rows } = room;
  const idx = (x: number, y: number): number => y * cols + x;
  const inside = (x: number, y: number): boolean => x >= 0 && y >= 0 && x < cols && y < rows;
  const floor = (x: number, y: number): boolean => inside(x, y) && room.usable(x, y);
  // ---- every placement the rules allow on an empty room
  const options: Option[] = [];
  rules.forEach((r, ri) => {
    for (let cy = 0; cy + r.h <= rows; cy++) for (let cx = 0; cx + r.w <= cols; cx++) {
      let ok = true;
      for (let y = cy; y < cy + r.h && ok; y++) for (let x = cx; x < cx + r.w && ok; x++) ok = floor(x, y) && !room.keepClear(x, y);
      if (!ok) continue;
      const row = (y: number): boolean => { for (let x = cx; x < cx + r.w; x++) if (floor(x, y)) return false; return true; };
      const col = (x: number): boolean => { for (let y = cy; y < cy + r.h; y++) if (floor(x, y)) return false; return true; };
      const back = row(cy - 1), left = col(cx - 1), right = col(cx + r.w);
      if (r.place === 'back' && !back) continue;
      if (r.place === 'wall' && !(back || left || right)) continue;
      if (r.place === 'corner' && !(back && (left || right))) continue;
      if (r.place === 'free') {
        let ring = true;
        for (let y = cy - 1; y <= cy + r.h && ring; y++) for (let x = cx - 1; x <= cx + r.w && ring; x++) ring = floor(x, y);
        if (!ring) continue;
      }
      if (r.front) {
        let any = false;
        for (let x = cx; x < cx + r.w; x++) if (inside(x, cy + r.h) && room.walkable(x, cy + r.h)) any = true;
        if (!any) continue;
      }
      options.push({ rule: ri, cx, cy, alive: true });
    }
  });
  // ---- the wave: each cell's live options (placements covering it); a cell with none left is floor
  const cellOpts: number[][] = Array.from({ length: cols * rows }, () => []);
  options.forEach((o, oi) => {
    const r = rules[o.rule]!;
    for (let y = o.cy; y < o.cy + r.h; y++) for (let x = o.cx; x < o.cx + r.w; x++) cellOpts[idx(x, y)]!.push(oi);
  });
  const state = new Int32Array(cols * rows).fill(-2); // -2 undecided, -1 floor, >= 0 the placed option
  const count = rules.map(() => 0);
  const placed: Placed[] = [];
  const placedAt = new Int32Array(cols * rows).fill(-1); // index into placed

  const kill = (oi: number): void => { options[oi]!.alive = false; };
  const covers = (o: Option, x: number, y: number): boolean => {
    const r = rules[o.rule]!;
    return x >= o.cx && x < o.cx + r.w && y >= o.cy && y < o.cy + r.h;
  };
  /** the cells a placed piece needs kept free in front of it */
  const frontCells = (o: Option): [number, number][] => {
    const r = rules[o.rule]!;
    if (!r.front) return [];
    const out: [number, number][] = [];
    for (let x = o.cx; x < o.cx + r.w; x++) if (inside(x, o.cy + r.h)) out.push([x, o.cy + r.h]);
    return out;
  };
  const touching = (o: Option): Placed[] => {
    const r = rules[o.rule]!, seen = new Set<number>();
    for (let y = o.cy - 1; y <= o.cy + r.h; y++) for (let x = o.cx - 1; x <= o.cx + r.w; x++) {
      if (!inside(x, y) || covers(o, x, y)) continue;
      const p = placedAt[idx(x, y)]!;
      if (p >= 0) seen.add(p);
    }
    return [...seen].map((i) => placed[i]!);
  };
  /** an option's weight in the room as it is now (0: not possible yet) */
  const weight = (oi: number): number => {
    const o = options[oi]!, r = rules[o.rule]!;
    let w = r.weight ?? 1;
    if (r.max !== undefined && count[o.rule]! >= r.max) return 0;
    const near = touching(o);
    if (r.under) {
      const above = near.some((p) => r.under!.includes(p.name) && p.cy + p.h === o.cy && p.cx <= o.cx && p.cx + p.w >= o.cx + r.w);
      if (!above) return 0;
    }
    for (const p of near) {
      if (r.near?.[p.name]) w *= r.near[p.name]!;
      if (r.apart?.[p.name]) w *= r.apart[p.name]!;
    }
    // what is still missing pulls harder
    if ((r.min ?? 0) > count[o.rule]!) w *= 4;
    return w;
  };
  const place = (oi: number): void => {
    const o = options[oi]!, r = rules[o.rule]!;
    const pi = placed.push({ name: r.name, cx: o.cx, cy: o.cy, w: r.w, h: r.h }) - 1;
    count[o.rule]!++;
    for (let y = o.cy; y < o.cy + r.h; y++) for (let x = o.cx; x < o.cx + r.w; x++) {
      state[idx(x, y)] = oi; placedAt[idx(x, y)] = pi;
      for (const other of cellOpts[idx(x, y)]!) kill(other);
    }
    // propagate: nothing may stand on its walkway, and it may not stand on another's
    for (const [x, y] of frontCells(o)) { for (const other of cellOpts[idx(x, y)]!) kill(other); if (state[idx(x, y)] === -2) state[idx(x, y)] = -1; }
    for (const other of options) if (other.alive && frontCells(other).some(([x, y]) => covers(o, x, y))) other.alive = false;
    if (r.max !== undefined && count[o.rule]! >= r.max) for (const other of options) if (other.rule === o.rule) other.alive = false;
  };

  // ---- the pieces a room must have, most constrained first
  for (;;) {
    let best = -1, bestN = Infinity;
    rules.forEach((r, ri) => {
      if ((r.min ?? 0) <= count[ri]!) return;
      const n = options.filter((o, oi) => o.alive && o.rule === ri && weight(oi) > 0).length;
      if (n < bestN) { bestN = n; best = ri; }
    });
    if (best < 0) break;
    const live = options.map((o, oi) => oi).filter((oi) => options[oi]!.alive && options[oi]!.rule === best && weight(oi) > 0);
    if (!live.length) return null; // contradiction: restart
    place(pickWeighted(live, live.map(weight), rnd));
  }
  // ---- then the free collapse: the lowest-entropy undecided cell, again and again
  for (;;) {
    let bestCell = -1, bestH = Infinity, bestLive: number[] = [], bestW: number[] = [];
    for (let i = 0; i < cols * rows; i++) {
      if (state[i] !== -2) continue;
      const live = cellOpts[i]!.filter((oi) => options[oi]!.alive);
      const ws = live.map(weight);
      const sum = ws.reduce((a, b) => a + b, 0);
      if (sum <= 0) { state[i] = -1; continue; }
      // Shannon entropy of {empty, options}, with a little seeded noise to break ties
      const total = sum + emptyWeight;
      let h = -(emptyWeight / total) * Math.log(emptyWeight / total);
      for (const w of ws) if (w > 0) h -= (w / total) * Math.log(w / total);
      h += rnd() * 1e-3;
      if (h < bestH) { bestH = h; bestCell = i; bestLive = live; bestW = ws; }
    }
    if (bestCell < 0) break;
    const choice = pickWeighted([-1, ...bestLive], [emptyWeight, ...bestW], rnd);
    if (choice === -1) {
      state[bestCell] = -1;
      for (const oi of cellOpts[bestCell]!) kill(oi);
    } else place(choice);
  }
  for (let ri = 0; ri < rules.length; ri++) if ((rules[ri]!.min ?? 0) > count[ri]!) return null;
  return placed;
}

function pickWeighted<T>(items: T[], weights: number[], rnd: () => number): T {
  const sum = weights.reduce((a, b) => a + b, 0);
  let r = rnd() * sum;
  for (let i = 0; i < items.length; i++) { r -= weights[i]!; if (r <= 0 && weights[i]! > 0) return items[i]!; }
  for (let i = items.length - 1; i >= 0; i--) if (weights[i]! > 0) return items[i]!;
  return items[0]!;
}

/** Every free cell of the area reachable from the door, and every piece that needs its front reachable from there. */
function reachable(room: WfcRoom, placed: Placed[], rules: PieceRule[]): boolean {
  const { cols, rows } = room;
  const solid = new Uint8Array(cols * rows);
  for (const p of placed) for (let y = p.cy; y < p.cy + p.h; y++) for (let x = p.cx; x < p.cx + p.w; x++) solid[y * cols + x] = 1;
  const open = (x: number, y: number): boolean => x >= 0 && y >= 0 && x < cols && y < rows && room.walkable(x, y) && !solid[y * cols + x];
  const seen = new Uint8Array(cols * rows);
  const q: number[] = [];
  if (!open(room.door.cx, room.door.cy)) return false;
  q.push(room.door.cy * cols + room.door.cx); seen[q[0]!] = 1;
  for (let i = 0; i < q.length; i++) {
    const x = q[i]! % cols, y = (q[i]! / cols) | 0;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
      const nx = x + dx, ny = y + dy;
      if (open(nx, ny) && !seen[ny * cols + nx]) { seen[ny * cols + nx] = 1; q.push(ny * cols + nx); }
    }
  }
  const owned = room.owned ?? room.usable;
  for (let y = 0; y < rows; y++) for (let x = 0; x < cols; x++) if (owned(x, y) && open(x, y) && !seen[y * cols + x]) return false;
  for (const p of placed) {
    const r = rules.find((r) => r.name === p.name)!;
    if (!r.front) continue;
    let ok = false;
    for (let x = p.cx; x < p.cx + p.w; x++) if (seen[(p.cy + p.h) * cols + x]) ok = true;
    if (!ok) return false;
  }
  return true;
}
