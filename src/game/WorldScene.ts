import Phaser from 'phaser';
import { fullName, type Intervention, type World } from '../sim';
import type { Inspection } from '../town/inspect';
import type { Rect, Road } from '../town/kinds';
import type { TownPlan } from '../town/plan';
import { linkRoad, originOf, siteAt, sitesNear, type TownSite } from '../world/map';
import { WILD_CHUNK, wildChunk } from '../world/wild';
import { pickVariant, type Manifest } from './art';
import { Generator } from './generator';
import { TILE, TownView, Z } from './TownView';

const DIRS = ['right', 'up', 'left', 'down'] as const;
/** A town starts loading when the player is within this many tiles of its center (sideways / up-down)... */
const LOAD_DX = 290, LOAD_DY = 250;
/** ...and is unloaded again beyond these. */
const UNLOAD_DX = 440, UNLOAD_DY = 390;
const WILD_PX = WILD_CHUNK * TILE;
type GO = Phaser.GameObjects.GameObject;

export interface TownInfo {
  townId: string;
  name: string;
  founded: number;
  /** Null until the town's history has arrived. */
  population: number | null;
  mayor: string | null;
  /** How this year differs from the original timeline; null if the player has not changed this town. */
  changes: string[] | null;
}

/** What the player clicked, and who/what in it they can act on. */
export interface Selection extends Inspection {
  townId: string;
  townName: string;
}

export interface ActionResult { ok: boolean; message: string }

/** An action the player can take; the year is filled in from the slider when it is performed. */
export type ActionSpec =
  | { kind: 'kill'; targetId: string }
  | { kind: 'rumor'; targetId: string; amount: number }
  | { kind: 'gift'; targetId: string; amount: number }
  | { kind: 'introduce'; a: string; b: string }
  | { kind: 'invest'; businessId: string; amount: number }
  | { kind: 'sabotage'; businessId: string };

interface Loaded { site: TownSite; view: TownView }
interface Link { key: string; rects: Road[]; objs: GO[] }
interface WildObj { go: GO; x: number; y: number }

/**
 * The endless world: towns appear around the player as they walk (each generated in a background
 * worker), are joined by roads east-west, and sit in wilderness. A banner announces each town you enter.
 */
export class WorldScene extends Phaser.Scene {
  private year = 2025;
  private manifest: Manifest = {};
  private gen!: Generator;
  private views = new Map<string, Loaded>();
  private loading = new Set<string>();
  private plans = new Map<string, TownPlan>();
  private links = new Map<string, Link>();
  private wild = new Map<string, WildObj[]>();
  private bg!: Phaser.GameObjects.TileSprite;
  player!: Phaser.GameObjects.Sprite;
  private facing = 3;
  private keys!: Record<string, Phaser.Input.Keyboard.Key>;
  private ready = false;
  private currentId: string | null = null;
  private frame = 0;
  /** The player's changes to each town's past, kept in the browser so they survive a reload. */
  private ivs = new Map<string, Intervention[]>();
  private lastClick: { x: number; y: number } | null = null;
  /** On-screen joystick, each axis -1..1 (set by the page). */
  private stick = { x: 0, y: 0 };
  private pinch = 0;
  /** What is selected, so the selection follows the same people/shop when the year changes. */
  private selection: { townId: string; ids: string[]; biz: string | null } | null = null;

  onReady: () => void = () => {};
  /** The player walked into a town (or its details changed). */
  onTown: (info: TownInfo, entered: boolean) => void = () => {};
  /** The player clicked a building or landmark (null: clicked nothing). */
  onSelect: (s: Selection | null) => void = () => {};
  /** How many towns are still being generated or drawn. */
  onLoading: (n: number) => void = () => {};

  constructor(readonly worldSeed: string) {
    super('world');
  }

  /** Loaded towns (for tests and debugging). */
  get towns(): ReadonlyMap<string, Loaded> {
    return this.views;
  }

  preload(): void {
    this.load.json('manifest', 'assets/manifest.json');
    this.load.once('filecomplete-json-manifest', () => {
      this.manifest = this.cache.json.get('manifest') as Manifest;
      for (const [group, items] of Object.entries(this.manifest)) {
        for (const it of items) {
          if (group === 'characters') this.load.spritesheet(it.key, it.path, { frameWidth: 32, frameHeight: 64 });
          else this.load.image(it.key, it.path);
        }
      }
    });
  }

  create(): void {
    this.gen = new Generator();
    this.keys = this.input.keyboard!.addKeys('W,A,S,D,UP,DOWN,LEFT,RIGHT') as Record<string, Phaser.Input.Keyboard.Key>;
    // The ground between towns: an endless lawn (one tile, re-centered under the camera each frame).
    const grass = this.manifest.grass?.[0]?.key ?? 'grass_22';
    this.bg = this.add.tileSprite(0, 0, 6144, 6144, grass).setOrigin(0).setDepth(Z.BG);

    // Character sheet: 56 columns of 32x64 frames; row 1 = idle, row 2 = walk; 6 frames per direction.
    const COLS = 56;
    DIRS.forEach((d, i) => {
      this.anims.create({ key: `walk-${d}`, frames: this.anims.generateFrameNumbers('char_01', { start: 2 * COLS + i * 6, end: 2 * COLS + i * 6 + 5 }), frameRate: 10, repeat: -1 });
      this.anims.create({ key: `idle-${d}`, frames: this.anims.generateFrameNumbers('char_01', { start: 1 * COLS + i * 6, end: 1 * COLS + i * 6 + 5 }), frameRate: 6, repeat: -1 });
    });
    this.player = this.add.sprite(0, 0, 'char_01', 1 * COLS + 18).setOrigin(0.5, 1).setVisible(false);
    this.player.play('idle-down');

    const cam = this.cameras.main;
    cam.setZoom(1);
    cam.roundPixels = true;
    this.input.addPointer(1); // a second finger, for pinch-to-zoom
    this.input.on('pointerdown', (p: Phaser.Input.Pointer) => { if (!this.input.pointer2.isDown) this.click(p); });
    this.input.on('wheel', (_p: unknown, _o: unknown, _dx: number, dy: number) => {
      cam.setZoom(Phaser.Math.Clamp(cam.zoom * (dy > 0 ? 0.9 : 1.1), 0.35, 2));
    });

    // Start in the home town (cell 0,0): wait for it, then put the player on its main street.
    const home = siteAt(this.worldSeed, 0, 0);
    this.ensure(home).then(() => {
      const v = this.views.get(home.id)!;
      this.player.setPosition(v.view.ox + v.view.plan.center.x * TILE, v.view.oy + (v.view.plan.mainY + 1.5) * TILE).setVisible(true);
      cam.startFollow(this.player, true, 0.12, 0.12);
      cam.centerOn(this.player.x, this.player.y);
      this.ready = true;
      this.onReady();
    });
  }

  // ---------- time ----------

  setYear(year: number): void {
    this.year = year;
    for (const { view } of this.views.values()) view.setYear(year);
    this.announce(false);
  }

  // ---------- loading and unloading towns ----------

  private async ensure(site: TownSite): Promise<void> {
    if (this.views.has(site.id) || this.loading.has(site.id)) return;
    this.loading.add(site.id);
    const east = siteAt(this.worldSeed, site.cx + 1, site.cy), west = siteAt(this.worldSeed, site.cx - 1, site.cy);
    // The neighbours' plans are needed to run the main street out to them (and keep the forest off that road).
    const [t, pe, pw] = await Promise.all([this.gen.town(site.seed), this.gen.plan(east.seed), this.gen.plan(west.seed)]);
    this.plans.set(site.id, t.plan); this.plans.set(east.id, pe); this.plans.set(west.id, pw);
    this.loading.delete(site.id);
    if (!this.scene.isActive() || !this.wanted(site)) return; // the player moved on while it was generating
    const me = { site, plan: t.plan };
    const corridors: Rect[] = [...linkRoad(me, { site: east, plan: pe }), ...linkRoad({ site: west, plan: pw }, me)];
    const view = new TownView(this, this.manifest, site.seed, t.plan, originOf(site, t.plan), corridors);
    view.setYear(this.year);
    const saved = this.loadChanges(site);
    if (saved.length) {
      this.ivs.set(site.id, saved);
      view.setHistory((await this.gen.history(site.seed, saved)).snapshots, t.snapshots);
    } else {
      view.setTimeline(t.snapshots);
    }
    this.views.set(site.id, { site, view });
    this.clearWildIn(view.mapPx);
    this.syncLinks();
  }

  private wanted(site: TownSite): boolean {
    const dx = Math.abs(this.player.x / TILE - site.center.x), dy = Math.abs(this.player.y / TILE - site.center.y);
    return (!this.ready && site.id === '0,0') || (dx < UNLOAD_DX && dy < UNLOAD_DY);
  }

  /** Decide which towns should be loaded around the player, and which wilderness chunks. */
  private manage(): void {
    const ptx = this.player.x / TILE, pty = this.player.y / TILE;
    let started = 0;
    for (const site of sitesNear(this.worldSeed, ptx, pty, 2)) {
      const dx = Math.abs(ptx - site.center.x), dy = Math.abs(pty - site.center.y);
      if (dx < LOAD_DX && dy < LOAD_DY && !this.views.has(site.id) && !this.loading.has(site.id) && this.loading.size + started < 2) {
        started++;
        void this.ensure(site);
      }
    }
    for (const [id, l] of this.views) {
      const dx = Math.abs(ptx - l.site.center.x), dy = Math.abs(pty - l.site.center.y);
      if (dx > UNLOAD_DX || dy > UNLOAD_DY) {
        l.view.destroy();
        this.views.delete(id);
        if (this.currentId === id) this.currentId = null;
      }
    }
    this.syncLinks();
    this.syncWild();
    this.onLoading(this.loading.size + [...this.views.values()].filter((v) => v.view.loading).length);
  }

  // ---------- roads between towns ----------

  private syncLinks(): void {
    const want = new Set<string>();
    for (const { site } of this.views.values()) {
      const east = siteAt(this.worldSeed, site.cx + 1, site.cy), west = siteAt(this.worldSeed, site.cx - 1, site.cy);
      for (const [a, b] of [[site, east], [west, site]] as const) {
        const key = `${a.id}>${b.id}`;
        const pa = this.plans.get(a.id), pb = this.plans.get(b.id);
        if (!pa || !pb) continue;
        want.add(key);
        if (!this.links.has(key)) this.drawLink(key, linkRoad({ site: a, plan: pa }, { site: b, plan: pb }));
      }
    }
    for (const [key, l] of this.links) if (!want.has(key)) { l.objs.forEach((o) => o.destroy()); this.links.delete(key); }
  }

  private drawLink(key: string, rects: Road[]): void {
    const sw = this.manifest.sidewalk?.[0], as = this.manifest.asphalt?.[0];
    const objs: GO[] = [];
    if (sw && as) {
      for (const r of rects) objs.push(this.add.tileSprite((r.x - 1) * TILE, (r.y - 1) * TILE, (r.w + 2) * TILE, (r.h + 2) * TILE, sw.key).setOrigin(0).setDepth(Z.SIDEWALK));
      for (const r of rects) objs.push(this.add.tileSprite(r.x * TILE, r.y * TILE, r.w * TILE, r.h * TILE, as.key).setOrigin(0).setDepth(Z.ASPHALT));
    }
    const g = this.add.graphics().setDepth(Z.LINES);
    g.fillStyle(0xe8d44d, 0.9);
    for (const r of rects) {
      if (r.horizontal) for (let x = r.x; x < r.x + r.w; x += 2) g.fillRect(x * TILE + 6, (r.y + 1) * TILE - 2, 20, 4);
      else for (let y = r.y; y < r.y + r.h; y += 2) g.fillRect((r.x + 1) * TILE - 2, y * TILE + 6, 4, 20);
    }
    objs.push(g);
    this.links.set(key, { key, rects, objs });
    for (const r of rects) this.clearWildIn({ x: (r.x - 3) * TILE, y: (r.y - 3) * TILE, w: (r.w + 6) * TILE, h: (r.h + 7) * TILE });
  }

  // ---------- wilderness ----------

  private syncWild(): void {
    const wv = this.cameras.main.worldView;
    const x0 = Math.floor(wv.x / WILD_PX) - 1, x1 = Math.floor(wv.right / WILD_PX) + 1;
    const y0 = Math.floor(wv.y / WILD_PX) - 1, y1 = Math.floor(wv.bottom / WILD_PX) + 1;
    let made = 0;
    for (let cy = y0; cy <= y1 && made < 3; cy++)
      for (let cx = x0; cx <= x1 && made < 3; cx++) {
        const key = `${cx},${cy}`;
        if (this.wild.has(key)) continue;
        this.wild.set(key, this.makeWildChunk(cx, cy));
        made++;
      }
    for (const [key, objs] of this.wild) {
      const [cx, cy] = key.split(',').map(Number) as [number, number];
      if (cx < x0 - 2 || cx > x1 + 2 || cy < y0 - 2 || cy > y1 + 2) { objs.forEach((o) => o.go.destroy()); this.wild.delete(key); }
    }
  }

  private makeWildChunk(cx: number, cy: number): WildObj[] {
    const groups = { tree: this.manifest.tree ?? [], bush: this.manifest.bush ?? [], flowers: this.manifest.flowers ?? [] };
    const out: WildObj[] = [];
    for (const p of wildChunk(this.worldSeed, cx, cy)) {
      if (this.insideTownOrRoad(p.x, p.y)) continue;
      const art = pickVariant(groups[p.kind], p.look);
      if (!art) continue;
      out.push({ go: this.add.image(p.x, p.y, art.key).setOrigin(0.5, 1).setDepth(p.kind === 'flowers' ? p.y - 500 : p.y), x: p.x, y: p.y });
    }
    return out;
  }

  /** Is this world pixel inside a loaded town's plan area or beside a road between towns? */
  private insideTownOrRoad(x: number, y: number): boolean {
    for (const { view } of this.views.values()) if (view.contains(x, y)) return true;
    const tx = x / TILE, ty = y / TILE;
    for (const l of this.links.values())
      for (const r of l.rects) if (tx > r.x - 3 && tx < r.x + r.w + 3 && ty > r.y - 3 && ty < r.y + r.h + 4) return true;
    return false;
  }

  private clearWildIn(r: Rect): void {
    for (const objs of this.wild.values())
      for (let i = objs.length - 1; i >= 0; i--) {
        const o = objs[i]!;
        if (o.x >= r.x && o.x < r.x + r.w && o.y >= r.y && o.y < r.y + r.h) { o.go.destroy(); objs.splice(i, 1); }
      }
  }

  // ---------- interaction ----------

  private click(p: Phaser.Input.Pointer): void {
    const wp = this.cameras.main.getWorldPoint(p.x, p.y);
    this.lastClick = { x: wp.x, y: wp.y };
    this.selection = null;
    for (const { site, view } of this.views.values()) {
      if (!view.contains(wp.x, wp.y)) continue;
      const ins = view.select(wp.x, wp.y);
      // Remember who/what was clicked so the selection follows them through the years.
      if (ins && (ins.people.length || ins.business)) this.selection = { townId: site.id, ids: ins.people.map((q) => q.id), biz: ins.business?.id ?? null };
    }
    this.reselect();
  }

  /** Inspect again whatever is selected: the same people or shop, wherever they live in the year now shown. */
  reselect(): void {
    if (this.selection) {
      const { townId, ids, biz } = this.selection;
      const l = this.views.get(townId);
      const ins = l?.view.selectRef(ids, biz) ?? null;
      this.onSelect(ins && l ? { ...ins, townId, townName: l.site.name } : null);
      return;
    }
    if (!this.lastClick) return;
    const { x, y } = this.lastClick;
    for (const { site, view } of this.views.values()) {
      if (!view.contains(x, y)) continue;
      const ins = view.select(x, y);
      this.onSelect(ins ? { ...ins, townId: site.id, townName: site.name } : null);
      return;
    }
    this.onSelect(null);
  }

  /** Adults of a town this year, for the "introduce to..." list. */
  adultsOf(townId: string, except?: string): { id: string; label: string }[] {
    return this.views.get(townId)?.view.adults(except) ?? [];
  }

  // ---------- changing the past ----------

  private storageKey(site: TownSite): string {
    return `timerpg:${this.worldSeed}:${site.id}`;
  }

  private loadChanges(site: TownSite): Intervention[] {
    try {
      const raw = JSON.parse(localStorage.getItem(this.storageKey(site)) ?? '[]') as Intervention[];
      return Array.isArray(raw) ? raw : [];
    } catch { return []; }
  }

  private saveChanges(site: TownSite, list: Intervention[]): void {
    try {
      if (list.length) localStorage.setItem(this.storageKey(site), JSON.stringify(list));
      else localStorage.removeItem(this.storageKey(site));
    } catch { /* private mode etc.: changes just will not persist */ }
  }

  /** Do something in the year the slider shows, then re-run that town's history with it. */
  async act(townId: string, spec: ActionSpec): Promise<ActionResult> {
    const l = this.views.get(townId);
    if (!l) return { ok: false, message: 'That town is not loaded.' };
    if (this.year <= 1950) return { ok: false, message: 'Travel to a later year first (use the slider): there is nothing before 1950.' };
    const iv = { ...spec, year: this.year } as Intervention;
    const list = [...(this.ivs.get(townId) ?? []), iv];
    const { snapshots, notes } = await this.gen.history(l.site.seed, list);
    const failed = notes.find((n) => n.startsWith('✗'));
    if (failed) return { ok: false, message: failed.replace('✗ ', '') };
    if (!this.views.has(townId)) return { ok: false, message: 'You left town before history finished changing.' };
    this.ivs.set(townId, list);
    this.saveChanges(l.site, list);
    l.view.setTimeline(snapshots);
    this.cameras.main.flash(600, 150, 190, 255); // history ripples
    this.announce(false);
    this.reselect();
    return { ok: true, message: notes[notes.length - 1] ?? 'Done.' };
  }

  /** Undo every change to a town: back to its original history. */
  reset(townId: string): void {
    const l = this.views.get(townId);
    if (!l) return;
    this.ivs.delete(townId);
    this.saveChanges(l.site, []);
    l.view.resetToOriginal();
    this.cameras.main.flash(400, 255, 220, 150);
    this.announce(false);
    this.reselect();
  }

  /** The id of the town the player is in, if any. */
  get insideTown(): string | null {
    return this.currentId;
  }

  private blocked(x: number, y: number): boolean {
    for (const { view } of this.views.values()) if (view.contains(x, y) && view.blocked(x, y)) return true;
    return false;
  }

  /** Report the town the player is standing in (and whether they just walked into it). */
  private announce(entered: boolean): void {
    const cur = this.currentId ? this.views.get(this.currentId) : undefined;
    if (!cur) return;
    const w: World | null = cur.view.world();
    this.onTown({
      townId: cur.site.id,
      name: cur.site.name,
      founded: cur.site.founded,
      population: w ? Object.values(w.people).filter((p) => p.deathYear === undefined).length : null,
      mayor: w?.mayor ? fullName(w.people[w.mayor]!) : null,
      changes: cur.view.changes(),
    }, entered);
  }

  private trackTown(): void {
    let now: string | null = null;
    for (const [id, l] of this.views) if (l.view.inUrbanArea(this.player.x, this.player.y)) { now = id; break; }
    if (now !== this.currentId) {
      this.currentId = now;
      if (now) this.announce(true);
    }
  }

  // ---------- touch and zoom helpers (called by the page) ----------

  /** Joystick input: x and y each from -1 to 1. */
  setMove(x: number, y: number): void {
    this.stick = { x, y };
  }

  zoomBy(factor: number): void {
    const cam = this.cameras.main;
    cam.setZoom(Phaser.Math.Clamp(cam.zoom * factor, 0.35, 2));
  }

  /** Close the action panel. */
  clearSelection(): void {
    this.selection = null;
    this.lastClick = null;
    this.onSelect(null);
  }

  update(_t: number, dt: number): void {
    for (const { view } of this.views.values()) view.tick();
    if (!this.ready) return;
    const cam = this.cameras.main;
    this.bg.setPosition(Math.floor((cam.worldView.centerX - 3072) / TILE) * TILE, Math.floor((cam.worldView.centerY - 3072) / TILE) * TILE);
    if (this.frame++ % 12 === 0) { this.manage(); this.trackTown(); }

    // Two fingers on the screen: pinch to zoom.
    const { pointer1, pointer2 } = this.input;
    if (pointer1.isDown && pointer2.isDown) {
      const d = Phaser.Math.Distance.Between(pointer1.x, pointer1.y, pointer2.x, pointer2.y);
      if (this.pinch > 0 && d > 0) this.zoomBy(d / this.pinch);
      this.pinch = d;
    } else this.pinch = 0;

    // Keyboard and joystick combine; the joystick is analog (push further to walk faster).
    const k = this.keys;
    let dx = (k.D!.isDown || k.RIGHT!.isDown ? 1 : 0) - (k.A!.isDown || k.LEFT!.isDown ? 1 : 0) + this.stick.x;
    let dy = (k.S!.isDown || k.DOWN!.isDown ? 1 : 0) - (k.W!.isDown || k.UP!.isDown ? 1 : 0) + this.stick.y;
    const mag = Math.hypot(dx, dy);
    if (mag < 0.12) {
      this.player.play(`idle-${DIRS[this.facing]}`, true);
      return;
    }
    if (mag > 1) { dx /= mag; dy /= mag; }
    this.facing = Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? 0 : 2) : dy > 0 ? 3 : 1;
    this.player.play(`walk-${DIRS[this.facing]}`, true);
    const nx = this.player.x + (dx * 180 * dt) / 1000, ny = this.player.y + (dy * 180 * dt) / 1000;
    if (!this.blocked(nx, this.player.y)) this.player.x = nx;
    if (!this.blocked(this.player.x, ny)) this.player.y = ny;
    this.player.setDepth(this.player.y);
  }
}
