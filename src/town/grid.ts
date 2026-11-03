import type { Rect } from './kinds';

/** A tile occupancy grid: lets generators ask "is this rectangle free?" in O(area). */
export class Grid {
  readonly cells: Uint8Array;
  constructor(readonly width: number, readonly height: number) {
    this.cells = new Uint8Array(width * height);
  }
  inBounds(r: Rect, margin = 0): boolean {
    return r.x >= margin && r.y >= margin && r.x + r.w <= this.width - margin && r.y + r.h <= this.height - margin;
  }
  free(r: Rect, margin = 1): boolean {
    if (!this.inBounds(r, margin)) return false;
    for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) if (this.cells[y * this.width + x]) return false;
    return true;
  }
  mark(r: Rect, pad = 0): void {
    for (let y = Math.max(0, r.y - pad); y < Math.min(this.height, r.y + r.h + pad); y++)
      for (let x = Math.max(0, r.x - pad); x < Math.min(this.width, r.x + r.w + pad); x++) this.cells[y * this.width + x] = 1;
  }
  at(x: number, y: number): number {
    return x < 0 || y < 0 || x >= this.width || y >= this.height ? 1 : this.cells[y * this.width + x]!;
  }
}
