import { moveSafely, nearestFree } from '../town/collision';
import { interiorBlocked, planInterior, type Entrance, type InteriorConnection } from '../town/interiors';
import { InteriorView } from './InteriorView';
import Phaser from 'phaser';
import { Npcs, HOURS_PER_SECOND } from './Npcs';
import { narrate, optionsFor, specFor, type Option, type TalkCtx } from './dialogue';
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

export interface TalkCard {
  id: string;
  name: string;
  subtitle: string;
  line: string;
  options: Option[];
  wanted: boolean;
}

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
  npcs!: Npcs;
  /** Clock text changed (e.g. "7:30 AM"). */
  onClock: (text: string) => void = () => {};
  private clockText = '';
  /** Game hours of "wanted" left: while it lasts, nobody will talk to you. */
  wantedHours = 0;
  private familiar: Record<string, number> = (() => { try { return JSON.parse(localStorage.getItem('timerpg.familiar') ?? '{}'); } catch { return {}; } })();
  private talking: { townId: string; id: string } | null = null;
  /** A conversation opened (card) or closed (null). */
  onTalk: (card: TalkCard | null) => void = () => {};
  /** A short message for the player ("Walk closer to talk", "You are wanted"). */
  onNote: (text: string) => void = () => {};
  private frame = 0;
  private room: { view: InteriorView; townId: string; entranceId: string; outside: {x:number;y:number}; zoom:number } | null = null;
  private doorLabel = '';
  onDoor: (label: string, inside: boolean) => void = () => {};
  get indoors(): boolean { return this.room !== null; }
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
    // In the single-file build the manifest and every sprite are embedded in the page as data URIs
    // (a saved HTML file cannot fetch files next to it); otherwise they are loaded from assets/.
    const embed = (window as unknown as { __EMBED__?: { manifest: Manifest; files: Record<string, string> } }).__EMBED__;
    const queue = (manifest: Manifest): void => {
      this.manifest = manifest;
      for (const [group, items] of Object.entries(manifest)) {
        for (const it of items) {
          const src = embed?.files[it.path] ?? it.path;
          if (group === 'characters') this.load.spritesheet(it.key, src, { frameWidth: 32, frameHeight: 64 });
          else this.load.image(it.key, src);
        }
      }
    };
    if (embed) { queue(embed.manifest); return; }
    this.load.json('manifest', 'assets/manifest.json');
    this.load.once('filecomplete-json-manifest', () => queue(this.cache.json.get('manifest') as Manifest));
  }

  create(): void {
    this.gen = new Generator();
    this.npcs = new Npcs(this, (this.manifest.characters ?? []).map((c) => c.key));
    this.scale.on('resize', () => { if (this.room) this.fitInterior(); });
    this.keys = this.input.keyboard!.addKeys('W,A,S,D,UP,DOWN,LEFT,RIGHT,E,ESC') as Record<string, Phaser.Input.Keyboard.Key>;
    // Handle interaction on keydown so a quick press released between frames is not lost.
    this.input.keyboard!.on('keydown-E', (event: KeyboardEvent) => { if (!event.repeat) this.interact(); });
    this.input.keyboard!.on('keydown-ESC', (event: KeyboardEvent) => { if (!event.repeat) this.backInterior(); });
    // The ground between towns: an endless lawn (one tile, re-centered under the camera each frame).
    const grass = this.textures.exists('grass_base_seamless') ? 'grass_base_seamless' : (this.manifest.grass?.[0]?.key ?? 'grass_22');
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
    // Phones are narrow: start zoomed out a little so there is more street on screen.
    cam.setZoom(Phaser.Math.Clamp(this.scale.width / 640, 0.6, 1));
    cam.roundPixels = true;
    this.input.addPointer(1); // a second finger, for pinch-to-zoom
    this.input.on('pointerdown', (p: Phaser.Input.Pointer) => { if (!this.input.pointer2.isDown) this.click(p); });
    this.input.on('wheel', (_p: unknown, _o: unknown, _dx: number, dy: number) => {
      this.zoomBy(dy > 0 ? 0.9 : 1.1);
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
    this.refreshInterior();
    this.recoverPosition();
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
    if (this.ready && !this.room) this.recoverPosition();
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
    if (this.room) {
      const r = this.room.view;
      const at = r.room.connections.find(c=>Math.hypot(wp.x-r.ox-c.at.x,wp.y-r.oy-c.at.y)<40);
      if ((at && this.nearestInteriorConnection()?.id===at.id) || (r.room.outsideExit && Math.hypot(wp.x-r.ox-r.room.exit.x,wp.y-r.oy-r.room.exit.y)<40)) this.interact();
      return;
    }
    const door = this.nearestEntrance();
    if (door && Math.hypot(wp.x-door.x,wp.y-door.y)<32) { this.interact(); return; }
    this.lastClick = { x: wp.x, y: wp.y };
    this.selection = null;
    const who = this.npcs.personAt(wp.x, wp.y);
    if (who && this.currentId) {
      this.selection = null;
      this.onSelect(null);
      this.talkTo(this.currentId, who);
      return;
    }
    this.closeTalk();
    for (const { site, view } of this.views.values()) {
      if (!view.contains(wp.x, wp.y)) continue;
      const ins = view.select(wp.x, wp.y);
      // Remember who/what was clicked so the selection follows them through the years.
      if (ins && (ins.people.length || ins.business)) this.selection = { townId: site.id, ids: ins.people.map((q) => q.id), biz: ins.business?.id ?? null };
    }
    this.reselect();
  }

  // ---------- conversations ----------

  private talkCtx(townId: string, id: string): TalkCtx | null {
    const l = this.views.get(townId);
    const w = l?.view.world();
    const person = w?.people[id];
    const r = this.npcs.residentById(id);
    if (!l || !w || !person || !r) return null;
    const at = this.npcs.positionOf(id);
    return {
      w, person, job: r.job, townName: l.site.name, familiarity: this.familiar[`${l.site.id}:${id}`] ?? 0,
      witnesses: at ? this.npcs.countNear(at.x, at.y, 260, id) : 0, wanted: this.wantedHours > 0,
    };
  }

  private card(c: TalkCtx, line: string): TalkCard {
    return { id: c.person.id, name: fullName(c.person), subtitle: `${c.w.year - c.person.birthYear} · ${c.job}`, line, options: optionsFor(c), wanted: c.wanted };
  }

  /** Walk-up conversation: only works when you are standing close to them. */
  talkTo(townId: string, id: string): void {
    const at = this.npcs.positionOf(id);
    const c = this.talkCtx(townId, id);
    if (!at || !c) return;
    if (Math.hypot(at.x - this.player.x, at.y - this.player.y) > 96) { this.onNote(`Walk up to ${fullName(c.person)} to talk.`); return; }
    this.closeTalk();
    this.talking = { townId, id };
    this.npcs.held.add(id);
    this.onTalk(this.card(c, narrate(c, 'greet')));
  }

  closeTalk(): void {
    if (this.talking) this.npcs.held.delete(this.talking.id);
    this.talking = null;
    this.onTalk(null);
  }

  /** The player picked a menu option. Returns the new card, or null if the conversation ended. */
  async talkChoose(optionId: string): Promise<void> {
    const t = this.talking;
    if (!t) return;
    const c = this.talkCtx(t.townId, t.id);
    if (!c) { this.closeTalk(); return; }
    const opt = optionsFor(c).find((o) => o.id === optionId);
    if (!opt || !opt.enabled) return;
    const key = `${this.views.get(t.townId)!.site.id}:${t.id}`;
    const spec = specFor(optionId);
    if (!spec) {
      this.familiar[key] = (this.familiar[key] ?? 0) + (optionId === 'chat' ? 1 : 0);
      try { localStorage.setItem('timerpg.familiar', JSON.stringify(this.familiar)); } catch { /* private mode */ }
      this.onTalk(this.card({ ...c, familiarity: this.familiar[key] ?? 0 }, narrate(c, optionId)));
      return;
    }
    const seen = c.witnesses;
    const r = await this.act(t.townId, spec.kind === 'kill' ? { kind: 'kill', targetId: t.id } : { kind: spec.kind, targetId: t.id, amount: spec.amount });
    let line = r.message;
    if (r.ok && (spec.kind === 'kill' || (spec.kind === 'rumor' && spec.amount < 0)) && seen > 0) {
      // Caught: witnesses report you; for a while nobody will talk to you.
      const police = this.views.get(t.townId)?.view.layout?.landmarks.some((l) => l.id === 'police');
      this.wantedHours = (spec.kind === 'kill' ? 6 : 2) * (police ? 1.5 : 1);
      line = `${r.message} ${seen} ${seen === 1 ? 'person saw' : 'people saw'} it. You are wanted${police ? ' — the police are looking for you' : ''}.`;
      this.onNote('WANTED — lie low until it blows over.');
      if (spec.kind === 'kill') { this.closeTalk(); this.onNote(line); return; }
    } else if (r.ok && spec.kind === 'kill') { this.closeTalk(); this.onNote(`${r.message} Nobody saw.`); return; }
    const c2 = this.talkCtx(t.townId, t.id);
    if (!c2) { this.closeTalk(); this.onNote(line); return; }
    this.onTalk(this.card(c2, `${narrate(c2, optionId)}\n[${line}]`));
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
    this.refreshInterior();
    this.recoverPosition();
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
    this.refreshInterior();
    this.recoverPosition();
    this.cameras.main.flash(400, 255, 220, 150);
    this.announce(false);
    this.reselect();
  }

  /** The id of the town the player is in, if any. */
  get insideTown(): string | null {
    return this.currentId;
  }

  private blocked(x: number, y: number): boolean {
    if (this.room) return interiorBlocked(this.room.view.room, x-this.room.view.ox, y-this.room.view.oy);
    for (const { view } of this.views.values()) if (view.contains(x, y) && view.blocked(x, y)) return true;
    return false;
  }

  private nearestEntrance(): { townId: string; entrance: Entrance; x:number; y:number } | null {
    let nearest: { townId:string; entrance:Entrance; x:number; y:number } | null = null;
    let best=72;
    for (const [townId,{view}] of this.views) for (const entrance of view.entrances()) {
      const x=view.ox+entrance.door.x, y=view.oy+entrance.door.y;
      const d=Math.hypot(x-this.player.x,y-this.player.y);
      if (d<best && this.player.y>=y-10 && !view.blocked(x,y)) { nearest={townId,entrance,x,y};best=d; }
    }
    return nearest;
  }

  /** E or a tap enters only when standing in front of an actual door. */
  interact(): void {
    if (!this.ready) return;
    if (this.room) {
      const v=this.room.view;
      const connection=this.nearestInteriorConnection();
      if(connection)this.travelInterior(connection);
      else if(v.room.outsideExit && Math.hypot(this.player.x-v.ox-v.room.exit.x,this.player.y-v.oy-v.room.exit.y)<72)this.leaveInterior();
      return;
    }
    const nearby=this.nearestEntrance(); if(!nearby)return;
    const loaded=this.views.get(nearby.townId)!;
    const w=loaded.view.world();if(!w)return;
    const plan=planInterior(w,nearby.entrance);
    const view=new InteriorView(this,plan), cam=this.cameras.main;
    this.room={view,townId:nearby.townId,entranceId:nearby.entrance.id,outside:{x:nearby.x,y:nearby.y},zoom:cam.zoom};
    this.player.setPosition(view.ox+plan.spawn.x,view.oy+plan.spawn.y);
    this.player.setDepth(this.player.y);
    this.fitInterior();
    this.clearSelection();this.onLoading(0);this.updateDoorPrompt();
  }

  /** Escape / the touch Leave button is always available, including after time travel. */
  leaveInterior(): void {
    const old=this.room;if(!old)return;
    old.view.destroy();this.room=null;
    this.player.setPosition(old.outside.x,old.outside.y);
    this.player.setDepth(this.player.y);
    this.cameras.main.removeBounds();
    this.cameras.main.setZoom(old.zoom);
    this.cameras.main.startFollow(this.player,true,.12,.12);
    this.cameras.main.centerOn(this.player.x,this.player.y);
    this.recoverPosition();this.updateDoorPrompt();
  }

  private refreshInterior(): void {
    const old=this.room;if(!old)return;
    const loaded=this.views.get(old.townId);
    const entrance=loaded?.view.entrances().find(e=>e.id===old.entranceId);
    const w=loaded?.view.world();
    if(!entrance||!w){this.leaveInterior();return;}
    const position={x:this.player.x-old.view.ox,y:this.player.y-old.view.oy};
    const plan=planInterior(w,entrance,old.view.room.zoneId);old.view.destroy();old.view=new InteriorView(this,plan);
    const safe=nearestFree(position,(x,y)=>interiorBlocked(plan,x,y))??plan.spawn;
    this.player.setPosition(old.view.ox+safe.x,old.view.oy+safe.y);
    this.fitInterior();
  }

  private fitInterior(): void {
    if(!this.room)return;
    const v=this.room.view,plan=v.room,cam=this.cameras.main;
    cam.stopFollow();cam.removeBounds();
    cam.setZoom(Math.min(1.25,Math.max(.35,Math.min(this.scale.width/(plan.width+48),this.scale.height/(plan.height+80)))));
    cam.centerOn(v.ox+plan.width/2,v.oy+plan.height/2);
  }

  private recoverPosition(): void {
    if(!this.player)return;
    const free=nearestFree(this.player,(x,y)=>this.blocked(x,y));
    if(free)this.player.setPosition(free.x,free.y);
  }

  private nearestInteriorConnection(): InteriorConnection | null {
    if(!this.room)return null;
    const v=this.room.view;
    return [...v.room.connections].sort((a,b)=>Math.hypot(this.player.x-v.ox-a.at.x,this.player.y-v.oy-a.at.y)-Math.hypot(this.player.x-v.ox-b.at.x,this.player.y-v.oy-b.at.y))
      .find(c=>Math.hypot(this.player.x-v.ox-c.at.x,this.player.y-v.oy-c.at.y)<64)??null;
  }

  private travelInterior(connection: InteriorConnection): void {
    const state=this.room;if(!state)return;
    const loaded=this.views.get(state.townId),w=loaded?.view.world();
    const entrance=loaded?.view.entrances().find(e=>e.id===state.entranceId);
    if(!w||!entrance){this.leaveInterior();return;}
    const plan=planInterior(w,entrance,connection.target);
    const arrival=plan.connections.find(c=>c.id===connection.arrival);
    if(!arrival)throw new Error(`Missing arrival ${connection.arrival} in ${connection.target}`);
    state.view.destroy();state.view=new InteriorView(this,plan);
    this.player.setPosition(state.view.ox+arrival.at.x,state.view.oy+arrival.at.y);
    this.player.setDepth(this.player.y);this.fitInterior();this.updateDoorPrompt();
  }

  /** Escape goes back one level; upper floors never teleport directly into the street. */
  private backInterior(): void {
    if(!this.room)return;
    const plan=this.room.view.room;
    if(plan.outsideExit){this.leaveInterior();return;}
    const back=plan.connections.find(c=>c.id==='room-exit'||c.id==='stairs-down');
    if(back)this.travelInterior(back);
  }

  private updateDoorPrompt(): void {
    let label='';
    if(this.room){
      const v=this.room.view,c=this.nearestInteriorConnection();
      if(c)label=`${c.label} [E]`;
      else if(v.room.outsideExit&&Math.hypot(this.player.x-v.ox-v.room.exit.x,this.player.y-v.oy-v.room.exit.y)<72)label='Leave building [E]';
    }else{const n=this.nearestEntrance();if(n)label=`Enter ${n.entrance.title} [E]`;}
    if(label!==this.doorLabel){this.doorLabel=label;this.onDoor(label,!!this.room);}
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
    if(this.room){
      const v=this.room.view,p=v.room;
      const fit=Math.min(1.25,Math.max(.35,Math.min(this.scale.width/(p.width+48),this.scale.height/(p.height+80))));
      if(cam.zoom>fit+.02){cam.setBounds(v.ox-32,v.oy-32,p.width+64,p.height+64);cam.startFollow(this.player,true,.12,.12);}
      else{cam.stopFollow();cam.removeBounds();cam.centerOn(v.ox+p.width/2,v.oy+p.height/2);}
    }
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
    this.updateDoorPrompt();
    const here = !this.room && this.currentId ? this.views.get(this.currentId) : undefined;
    const w = here?.view.world();
    if (this.wantedHours > 0) { this.wantedHours = Math.max(0, this.wantedHours - (dt / 1000) * HOURS_PER_SECOND); if (this.wantedHours === 0) this.onNote('The heat has died down.'); }
    if (this.talking) {
      const at = this.npcs.positionOf(this.talking.id);
      if (!at || Math.hypot(at.x - this.player.x, at.y - this.player.y) > 160) this.closeTalk();
    }
    this.npcs.update(dt, here && w && here.view.layout ? { ox: here.view.ox, oy: here.view.oy, layout: here.view.layout, world: w } : null, this.player.x, this.player.y);
    const h = Math.floor(this.npcs.hour), m = Math.floor((this.npcs.hour % 1) * 60 / 10) * 10;
    const text = `${((h + 11) % 12) + 1}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
    if (text !== this.clockText) { this.clockText = text; this.onClock(text); }
    const cam = this.cameras.main;
    if (!this.room) this.bg.setPosition(Math.floor((cam.worldView.centerX - 3072) / TILE) * TILE, Math.floor((cam.worldView.centerY - 3072) / TILE) * TILE);
    if (!this.room && this.frame++ % 12 === 0) { this.manage(); this.trackTown(); }

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
    const elapsed=Math.min(dt,100);
    const next=moveSafely(this.player,dx*180*elapsed/1000,dy*180*elapsed/1000,(x,y)=>this.blocked(x,y));
    this.player.setPosition(next.x,next.y);
    this.player.setDepth(this.player.y);
  }
}
