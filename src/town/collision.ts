import type { Lot } from './layout';
import type { LandmarkPlot } from './landmarks';
import type { Rect } from './kinds';

export const TILE = 32;
export interface Point { x: number; y: number }
/** The player's feet have area: corners cannot pass through walls diagonally. All units are pixels. */
export const feetHit = (x: number, y: number, r: Rect): boolean =>
  x + 7 > r.x && x - 7 < r.x + r.w && y > r.y && y - 8 < r.y + r.h;

/** The entire building body is solid, with its front yard left walkable. Roof overhang stays inside its reserved plot. */
export function buildingBody(l: Lot): Rect {
  const bottom = (l.y + l.h - l.front) * TILE;
  const width = Math.min(l.art.w, l.w * TILE);
  const top = Math.max(l.y * TILE, bottom - l.art.h);
  return { x: (l.x + l.w / 2) * TILE - width / 2, y: top, w: width, h: bottom - top };
}
export const civicBody = (l: LandmarkPlot): Rect => ({ x: l.x * TILE, y: l.y * TILE, w: l.w * TILE, h: l.h * TILE });

/** Door locations are on the south facade; the interaction point sits safely outside the wall. */
export function buildingDoor(l: Lot): Point {
  const key = l.art.layers[0]!.key;
  let dx = 0;
  if (l.kind === 'shop') dx = key.includes('bank') || key.includes('office') || key.includes('condo') ? 0 : 64;
  else if (key.startsWith('house_medium')) dx = -48;
  else if (key.startsWith('house_terraced')) dx = /house_terraced_[12](?:_|$)/.test(key) ? -64 : 64;
  else if (key === 'condo_9') dx = 48;
  const body = buildingBody(l);
  return { x: body.x + body.w / 2 + dx, y: body.y + body.h + 16 };
}
export const civicDoor = (l: LandmarkPlot): Point => ({ x: (l.x + l.w / 2) * TILE, y: (l.y + l.h) * TILE + 16 });

/** Bounded substeps prevent tunnelling during a slow frame while allowing sliding along walls. */
export function moveSafely(from: Point, dx: number, dy: number, blocked: (x: number, y: number) => boolean): Point {
  const n = Math.max(1, Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) / 4));
  let { x, y } = from;
  for (let i = 0; i < n; i++) {
    if (!blocked(x + dx / n, y)) x += dx / n;
    if (!blocked(x, y + dy / n)) y += dy / n;
  }
  return { x, y };
}

/** Recover from a building appearing underneath the player after time travel. */
export function nearestFree(from: Point, blocked: (x: number, y: number) => boolean): Point | null {
  if (!blocked(from.x, from.y)) return from;
  for (let radius = 16; radius <= 1024; radius += 16) {
    for (let d = -radius; d <= radius; d += 16) {
      for (const p of [{ x: from.x + d, y: from.y + radius }, { x: from.x + d, y: from.y - radius },
        { x: from.x + radius, y: from.y + d }, { x: from.x - radius, y: from.y + d }]) {
        if (!blocked(p.x, p.y)) return p;
      }
    }
  }
  return null;
}

/** Closely packed side-street shops need an accessible side entrance when the south facade abuts another building. */
export function accessibleDoor(body: Rect, preferred: Point, blocked: (x:number,y:number)=>boolean): Point | null {
  if(!blocked(preferred.x,preferred.y))return preferred;
  const candidates:Point[]=[];
  for(let t=16;t<body.h;t+=16)candidates.push({x:body.x-16,y:body.y+t},{x:body.x+body.w+16,y:body.y+t});
  for(let t=16;t<body.w;t+=16)candidates.push({x:body.x+t,y:body.y-16},{x:body.x+t,y:body.y+body.h+16});
  candidates.sort((a,b)=>Math.hypot(a.x-preferred.x,a.y-preferred.y)-Math.hypot(b.x-preferred.x,b.y-preferred.y));
  return candidates.find(p=>!blocked(p.x,p.y))??null;
}
