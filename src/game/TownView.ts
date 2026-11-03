import Phaser from 'phaser';
import type { World } from '../sim';
import { landmarkFootprint } from '../town/landmarks';
import { lampIndexes, pickIndex, treeIndexes } from '../town/era';
import type { Rect } from '../town/kinds';
import { landmarkDescription, layoutTown, lotDescription, type LandmarkPlot, type Lot, type Prop, type Road, type TownLayout } from '../town/layout';
import type { TownPlan } from '../town/plan';
import type { Bridge } from '../town/water';
import { pickVariant, type Manifest } from './art';

export const TILE = 32;
/**
 * Layers below everything that is sorted by its y position. World coordinates can be negative (towns are
 * placed around the origin), so ground layers must sit far below any possible y.
 */
export const Z = { BG: -2e9, GROUND: -1.9e9, SIDEWALK: -1.9e9 + 1, ASPHALT: -1.9e9 + 2, LINES: -1.9e9 + 3, DECAL: -1.9e9 + 4 } as const;
const CHUNK = 64; // ground is baked into 2048px textures (safe on mobile GPUs)
const PROP_GROUP: Record<Prop['kind'], string> = {
  tree: 'tree', lamp: 'lamp', bench: 'bench', bush: 'bush', flowerBush: 'flowerBush', flowers: 'flowers',
  fountain1: 'fountain1', fountain2: 'fountain2', fence: 'fence', hedge: 'hedge', path: 'dirtPath',
};

type GO = Phaser.GameObjects.GameObject;

/**
 * One town, drawn somewhere in the world. `origin` is the world tile of the plan's top-left corner.
 * Heavy one-off work (baking the ground, creating thousands of static props) is queued and done a
 * little each frame (`tick`), so a town loading in does not freeze the game.
 */
export class TownView {
  readonly ox: number;
  readonly oy: number;
  layout: TownLayout | null = null;
  /** Urban area in world pixels (roads and lots, padded): entering it announces the town. */
  readonly bounds: Rect;
  /** The whole plan in world pixels. */
  readonly mapPx: Rect;
  private timeline: World[] | null = null;
  private year = 2025;
  private openBridges: Bridge[] = [];
  private drawn: GO[] = [];
  private fixed: GO[] = [];
  private queue: (() => void)[] = [];
  private destroyed = false;

  constructor(
    private scene: Phaser.Scene,
    private manifest: Manifest,
    readonly seed: string,
    readonly plan: TownPlan,
    origin: { x: number; y: number },
    /** Link-road corridors (world tiles) that the forest must keep clear. */
    private corridors: Rect[],
  ) {
    this.ox = origin.x * TILE;
    this.oy = origin.y * TILE;
    this.mapPx = { x: this.ox, y: this.oy, w: plan.width * TILE, h: plan.height * TILE };
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const r of [...plan.roads, ...plan.candidates, ...plan.landmarks]) {
      x0 = Math.min(x0, r.x); y0 = Math.min(y0, r.y); x1 = Math.max(x1, r.x + r.w); y1 = Math.max(y1, r.y + r.h);
    }
    const pad = 8;
    this.bounds = { x: this.ox + (x0 - pad) * TILE, y: this.oy + (y0 - pad) * TILE, w: (x1 - x0 + 2 * pad) * TILE, h: (y1 - y0 + 2 * pad) * TILE };
    this.queueGround();
    this.queueStaticProps();
  }

  // ---------- loading work, spread over frames ----------

  /** Run a little of the queued work; call once per frame. */
  tick(): void {
    // Work for a few milliseconds per frame (always at least one job), so loading never stalls walking.
    if (!this.queue.length || this.destroyed) return;
    const t0 = performance.now();
    do this.queue.shift()!(); while (this.queue.length && !this.destroyed && performance.now() - t0 < 6);
  }

  get loading(): boolean {
    return this.queue.length > 0;
  }

  private queueGround(): void {
    const { width: MAP_W, height: MAP_H } = this.plan;
    const kind = new Uint8Array(MAP_W * MAP_H); // 0 grass, 2 paved, 3 water
    const water = this.plan.water;
    if (water) for (let i = 0; i < kind.length; i++) if (water.raw[i]) kind[i] = 3;
    const wat = (x: number, y: number): boolean => x >= 0 && y >= 0 && x < MAP_W && y < MAP_H && kind[y * MAP_W + x] === 3;
    const shore = this.manifest.waterShore ?? [];
    const decor = this.manifest.waterDecor ?? [];
    /** Shore autotile (tileset family Grass_Water_2): which of its 23 tiles a grass cell next to water uses, or 0. */
    const shoreTile = (x: number, y: number): number => {
      const N = wat(x, y - 1), E = wat(x + 1, y), S = wat(x, y + 1), W = wat(x - 1, y);
      if (N && W) return 1;
      if (N && E) return 3;
      if (S && W) return 7;
      if (S && E) return 5;
      if (N) return 2;
      if (S) return 6;
      if (W) return 8;
      if (E) return 4;
      if (wat(x - 1, y - 1)) return 12;
      if (wat(x + 1, y - 1)) return 13;
      if (wat(x + 1, y + 1)) return 18;
      if (wat(x - 1, y + 1)) return 21;
      return 0;
    };
    // The plaza is paved (leaving a one-tile grass edge).
    const pz = this.plan.plaza;
    for (let y = pz.y + 1; y < pz.y + pz.h - 1; y++) for (let x = pz.x + 1; x < pz.x + pz.w - 1; x++) if (kind[y * MAP_W + x] === 0) kind[y * MAP_W + x] = 2;
    const grass = this.manifest.grass ?? [];
    const patches = this.manifest.grassPatch ?? [];
    const clutter = this.manifest.clutter ?? [];
    const sidewalk = this.manifest.sidewalk ?? [];
    const hash = (x: number, y: number): number => ((Math.imul(x, 73856093) ^ Math.imul(y, 19349663)) >>> 0) / 4294967296;
    for (let cy = 0; cy * CHUNK < MAP_H; cy++) {
      for (let cx = 0; cx * CHUNK < MAP_W; cx++) {
        const w = Math.min(CHUNK, MAP_W - cx * CHUNK);
        const h = Math.min(CHUNK, MAP_H - cy * CHUNK);
        let rt: Phaser.GameObjects.RenderTexture | undefined;
        this.queue.push(() => {
          rt = this.scene.add.renderTexture(this.ox + cx * CHUNK * TILE, this.oy + cy * CHUNK * TILE, w * TILE, h * TILE).setOrigin(0).setDepth(Z.GROUND);
          this.fixed.push(rt);
        });
        // Bake it 16 rows at a time so no single job is big.
        for (let band = 0; band < h; band += 16) this.queue.push(() => {
          if (!rt) return;
          rt.beginDraw();
          for (let y = band; y < Math.min(h, band + 16); y++)
            for (let x = 0; x < w; x++) {
              const gx = cx * CHUNK + x;
              const gy = cy * CHUNK + y;
              const k = kind[gy * MAP_W + gx];
              const n = hash(gx, gy);
              if (k === 3 && shore[21]) {
                rt.batchDrawFrame(shore[21].key, undefined, x * TILE, y * TILE);
                const inner = wat(gx - 1, gy) && wat(gx + 1, gy) && wat(gx, gy - 1) && wat(gx, gy + 1);
                const hn = hash(gy + 977, gx + 311);
                if (inner && decor.length) {
                  if (hn < 0.012) rt.batchDrawFrame(decor[5 + Math.floor(hn * 250) % 3]!.key, undefined, x * TILE, y * TILE);
                  else if (hn < 0.09) rt.batchDrawFrame(decor[Math.floor(hn * 1000) % 5]!.key, undefined, x * TILE, y * TILE);
                }
                continue;
              }
              const st = k === 0 && shore.length ? shoreTile(gx, gy) : 0;
              if (st) {
                rt.batchDrawFrame(shore[st - 1]!.key, undefined, x * TILE, y * TILE);
                continue;
              }
              const set = k === 2 ? sidewalk : grass;
              const entry = set.length ? (n < 0.97 ? set[0]! : pickVariant(set, (n - 0.97) * 33)) : undefined;
              if (entry) rt.batchDrawFrame(entry.key, undefined, x * TILE, y * TILE);
              // The pack's grass is flat; texture comes from overlays: light patches and ground clutter.
              if (k === 0) {
                const h2 = hash(gx * 3 + 11, gy * 5 + 7);
                if (patches.length && h2 < 0.085) rt.batchDrawFrame(pickVariant(patches, h2 / 0.085).key, undefined, x * TILE, y * TILE);
                else if (clutter.length && h2 > 0.955) rt.batchDrawFrame(pickVariant(clutter, (h2 - 0.955) / 0.045).key, undefined, x * TILE, y * TILE);
              }
            }
          rt.endDraw();
        });
      }
    }
  }

  private queueStaticProps(): void {
    const statics = this.plan.props.filter((p) => p.road === undefined && !this.inCorridor(p));
    for (let i = 0; i < statics.length; i += 120) {
      const batch = statics.slice(i, i + 120);
      this.queue.push(() => { for (const p of batch) this.drawProp(p, false); });
    }
  }

  /** Is this prop inside (or right beside) a link road? Local tiles -> world tiles. */
  private inCorridor(p: Prop): boolean {
    const tx = this.ox / TILE + p.x / TILE, ty = this.oy / TILE + p.y / TILE;
    return this.corridors.some((c) => tx > c.x - 3 && tx < c.x + c.w + 3 && ty > c.y - 3 && ty < c.y + c.h + 4);
  }

  // ---------- history and year ----------

  setTimeline(snapshots: World[]): void {
    this.timeline = snapshots;
    this.refresh();
  }

  setYear(year: number): void {
    if (year === this.year) return;
    this.year = year;
    this.refresh();
  }

  /** The town as it is in the current year (null until its history has arrived). */
  world(): World | null {
    return this.timeline?.[this.year - 1950] ?? null;
  }

  private refresh(): void {
    const w = this.world();
    if (!w || this.destroyed) return;
    this.layout = layoutTown(w);
    for (const o of this.drawn) o.destroy();
    this.drawn = [];
    this.drawRoads(this.layout.roads);
    for (const lot of this.layout.lots) this.drawLot(w, lot);
    for (const l of this.layout.landmarks) this.drawLandmark(l);
    for (const p of this.layout.props) this.drawProp(p, true);
  }

  // ---------- drawing ----------

  private keep<T extends GO>(o: T): T {
    this.drawn.push(o);
    return o;
  }

  /** Streets that exist this year: sidewalks, asphalt, center lines, street furniture and bridges. */
  private drawRoads(built: Road[]): void {
    const { ox, oy } = this;
    const sw = this.manifest.sidewalk?.[0], as = this.manifest.asphalt?.[0];
    if (sw && as) {
      for (const r of built) this.keep(this.scene.add.tileSprite(ox + (r.x - 1) * TILE, oy + (r.y - 1) * TILE, (r.w + 2) * TILE, (r.h + 2) * TILE, sw.key).setOrigin(0).setDepth(Z.SIDEWALK));
      for (const r of built) this.keep(this.scene.add.tileSprite(ox + r.x * TILE, oy + r.y * TILE, r.w * TILE, r.h * TILE, as.key).setOrigin(0).setDepth(Z.ASPHALT));
    }
    const g = this.keep(this.scene.add.graphics().setDepth(Z.LINES));
    g.fillStyle(0xe8d44d, 0.9);
    for (const r of built) {
      if (r.horizontal) for (let x = r.x; x < r.x + r.w; x += 2) g.fillRect(ox + x * TILE + 6, oy + (r.y + 1) * TILE - 2, 20, 4);
      else for (let y = r.y; y < r.y + r.h; y += 2) g.fillRect(ox + (r.x + 1) * TILE - 2, oy + y * TILE + 6, 4, 20);
    }
    const isBuilt = new Set(built);
    this.plan.props.forEach((p) => { if (p.road !== undefined && isBuilt.has(this.plan.roads[p.road]!)) this.drawProp(p, true); });
    this.openBridges = [];
    for (const b of this.plan.water?.bridges ?? []) {
      const road = this.plan.roads.find((r) => r.horizontal === b.horizontal && b.x < r.x + r.w + 1 && r.x - 1 < b.x + b.w && b.y < r.y + r.h + 1 && r.y - 1 < b.y + b.h);
      if (road && isBuilt.has(road)) { this.openBridges.push(b); this.drawBridgeRails(b); }
    }
  }

  private drawBridgeRails(b: Bridge): void {
    const { ox, oy } = this;
    const m = this.manifest.bridgeRail ?? [];
    if (m.length < 4) return;
    const [h1, vRight, h2, vLeft] = m as [typeof m[0], typeof m[0], typeof m[0], typeof m[0]];
    if (b.horizontal) {
      for (let tx = b.x; tx < b.x + b.w; tx++) {
        const art = (tx * 7) % 3 === 0 ? h2 : h1;
        const north = oy + (b.y + 1) * TILE - 6;
        const south = oy + (b.y + b.h) * TILE + 6;
        this.keep(this.scene.add.image(ox + tx * TILE, north, art.key).setOrigin(0, 1).setDepth(north));
        this.keep(this.scene.add.image(ox + tx * TILE, south, art.key).setOrigin(0, 1).setDepth(south));
      }
    } else {
      for (let ty = b.y; ty < b.y + b.h; ty += 2) {
        this.keep(this.scene.add.image(ox + b.x * TILE, oy + ty * TILE, vLeft.key).setOrigin(0, 0).setDepth(Z.DECAL));
        this.keep(this.scene.add.image(ox + (b.x + b.w - 1) * TILE, oy + ty * TILE, vRight.key).setOrigin(0, 0).setDepth(Z.DECAL));
      }
    }
  }

  private drawProp(p: Prop, dynamic: boolean): void {
    const group = this.manifest[PROP_GROUP[p.kind]] ?? [];
    // Street furniture and the town's own trees follow the era (the forest and plaza are fixed).
    const yr = this.year;
    const art = p.kind === 'lamp' ? group[pickIndex(lampIndexes(yr), group.length, p.look)]
      : p.kind === 'tree' && dynamic ? group[pickIndex(treeIndexes(yr), group.length, p.look)]
      : pickVariant(group, p.look);
    if (!art) return;
    const y = this.oy + p.y;
    const img = this.scene.add.image(this.ox + p.x, y, art.key).setOrigin(0.5, 1).setDepth(p.kind === 'path' ? Z.DECAL : p.kind === 'flowers' ? y - 500 : y);
    if (dynamic) this.drawn.push(img);
    else this.fixed.push(img);
  }

  private drawLandmark(l: LandmarkPlot): void {
    const art = pickVariant(this.manifest[`landmark:${l.id}`] ?? [], l.look);
    if (!art) return;
    const bottom = this.oy + (l.y + l.h) * TILE;
    const cx = this.ox + (l.x + l.w / 2) * TILE;
    this.keep(this.scene.add.image(cx, bottom, art.key).setOrigin(0.5, 1).setDepth(bottom));
    this.keep(this.scene.add.text(cx, bottom + 4, l.name, {
      fontFamily: 'system-ui, sans-serif', fontSize: '11px', color: '#fff', backgroundColor: 'rgba(0,0,0,0.6)', padding: { x: 4, y: 2 },
    }).setOrigin(0.5, 0).setDepth(bottom + 1));
  }

  private drawLot(w: World, lot: Lot): void {
    const cx = this.ox + (lot.x + lot.w / 2) * TILE;
    const bottom = this.oy + (lot.y + lot.h - lot.front) * TILE; // the building stands above the front yard
    // Houses and shop fronts are stacks of sprites (see src/town/housing.ts and facade.ts).
    lot.art.layers.forEach((l, i) => {
      if (!this.scene.textures.exists(l.key)) return;
      this.keep(this.scene.add.image(cx + l.dx, bottom - l.y, l.key).setOrigin(0.5, 1).setDepth(i === 0 ? bottom : bottom - 1 + i * 0.001));
    });
    if (lot.kind === 'house') return;
    const b = w.businesses[lot.refId]!;
    this.keep(this.scene.add.text(cx, bottom + 4, b.name, {
      fontFamily: 'system-ui, sans-serif', fontSize: '11px', color: '#fff', backgroundColor: 'rgba(0,0,0,0.6)', padding: { x: 4, y: 2 },
    }).setOrigin(0.5, 0).setDepth(bottom + 1));
  }

  // ---------- interaction (world pixel coordinates in, local logic inside) ----------

  contains(wx: number, wy: number): boolean {
    return wx >= this.mapPx.x && wx < this.mapPx.x + this.mapPx.w && wy >= this.mapPx.y && wy < this.mapPx.y + this.mapPx.h;
  }

  inUrbanArea(wx: number, wy: number): boolean {
    const b = this.bounds;
    return wx >= b.x && wx < b.x + b.w && wy >= b.y && wy < b.y + b.h;
  }

  /** Text for whatever stands at this world position (a landmark or a building), or null. */
  info(wx: number, wy: number): string[] | null {
    const w = this.world();
    if (!w || !this.layout) return null;
    const tx = (wx - this.ox) / TILE, ty = (wy - this.oy) / TILE;
    const lm = this.layout.landmarks.find((l) => tx >= l.x && tx < l.x + l.w && ty >= l.y && ty < l.y + l.h);
    if (lm) return landmarkDescription(w, lm);
    const lot = this.layout.lots.find((l) => tx >= l.x && tx < l.x + l.w && ty >= l.y && ty < l.y + l.h);
    return lot ? lotDescription(w, lot) : null;
  }

  /** Feet box vs water and building footprints (the lower rows of each building, as wide as its sprite). */
  blocked(wx: number, wy: number): boolean {
    const x = wx - this.ox, y = wy - this.oy;
    const w = this.plan.water;
    if (w) {
      for (const [px, py] of [[x - 7, y], [x + 7, y], [x - 7, y - 4], [x + 7, y - 4]] as const) {
        const tx = Math.floor(px / TILE), ty = Math.floor(py / TILE);
        if (tx < 0 || ty < 0 || tx >= w.width || ty >= w.height || !w.raw[ty * w.width + tx]) continue;
        const onOpenBridge = this.openBridges.some((b) => tx >= b.x && tx < b.x + b.w && ty >= b.y && ty < b.y + b.h);
        if (!onOpenBridge) return true;
      }
    }
    if (!this.layout) return false;
    const inLandmark = this.layout.landmarks.some((l) => {
      const f = landmarkFootprint(l);
      return x + 7 > f.x * TILE && x - 7 < (f.x + f.w) * TILE && y > f.y * TILE && y - 4 < (f.y + f.h) * TILE;
    });
    if (inLandmark) return true;
    return this.layout.lots.some((l) => {
      const art = l.art;
      const half = Math.min(art.w, l.w * TILE) / 2;
      const cx = (l.x + l.w / 2) * TILE;
      const bottom = (l.y + l.h - l.front) * TILE; // the building, not its front yard
      const footTop = bottom - Math.min(l.h - l.front, art.footRows) * TILE;
      return x + 7 > cx - half && x - 7 < cx + half && y > footTop && y - 4 < bottom;
    });
  }

  /** Remove everything this town added to the scene. */
  destroy(): void {
    this.destroyed = true;
    this.queue = [];
    for (const o of this.drawn) o.destroy();
    for (const o of this.fixed) o.destroy();
    this.drawn = [];
    this.fixed = [];
  }
}
