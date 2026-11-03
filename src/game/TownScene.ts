import Phaser from 'phaser';
import { fullName, type World } from '../sim';
import { layoutTown, lotDescription, type Lot, type Prop, type TownLayout } from '../town/layout';
import { planTown, type TownPlan } from '../town/plan';
import { pickVariant, type Manifest } from './art';

export const TILE = 32;
const PROP_GROUP: Record<Prop['kind'], string> = {
  tree: 'tree', lamp: 'lamp', bench: 'bench', bush: 'bush', flowerBush: 'flowerBush', flowers: 'flowers',
  fountain1: 'fountain1', fountain2: 'fountain2',
};
const CHUNK = 64; // ground is baked into 2048px textures (safe on mobile GPUs)
const DIRS = ['right', 'up', 'left', 'down'] as const;

export class TownScene extends Phaser.Scene {
  private worlds: { original: World[]; altered: World[] } | null = null;
  private view = { year: 2025, which: 'original' as 'original' | 'altered' };
  private layout: TownLayout | null = null;
  private plan!: TownPlan;
  private manifest: Manifest = {};
  private drawn: Phaser.GameObjects.GameObject[] = [];
  private player!: Phaser.GameObjects.Sprite;
  private facing = 3;
  private keys!: Record<string, Phaser.Input.Keyboard.Key>;
  public onInfo: (lines: string[] | null) => void = () => {};
  public onWorld: (w: World) => void = () => {};
  public onReady: () => void = () => {};

  constructor() {
    super('town');
  }

  setWorlds(original: World[], altered: World[]): void {
    this.worlds = { original, altered };
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
    this.keys = this.input.keyboard!.addKeys('W,A,S,D,UP,DOWN,LEFT,RIGHT') as Record<string, Phaser.Input.Keyboard.Key>;
    this.plan = planTown(this.worlds!.original[0]!.seed);
    this.bakeGround();
    for (const p of this.plan.props) this.drawProp(p);

    // Character sheet: 56 columns of 32x64 frames; row 1 = idle, row 2 = walk; 6 frames per direction.
    const COLS = 56;
    DIRS.forEach((d, i) => {
      this.anims.create({ key: `walk-${d}`, frames: this.anims.generateFrameNumbers('char_01', { start: 2 * COLS + i * 6, end: 2 * COLS + i * 6 + 5 }), frameRate: 10, repeat: -1 });
      this.anims.create({ key: `idle-${d}`, frames: this.anims.generateFrameNumbers('char_01', { start: 1 * COLS + i * 6, end: 1 * COLS + i * 6 + 5 }), frameRate: 6, repeat: -1 });
    });
    this.player = this.add.sprite(this.plan.center.x * TILE, (this.plan.mainY + 1.5) * TILE, 'char_01', 1 * COLS + 18).setOrigin(0.5, 1);
    this.player.play('idle-down');

    const cam = this.cameras.main;
    cam.startFollow(this.player, true, 0.12, 0.12);
    cam.setBounds(0, 0, this.plan.width * TILE, this.plan.height * TILE);
    cam.setZoom(1);
    cam.roundPixels = true;
    this.input.on('pointerdown', (p: Phaser.Input.Pointer) => this.click(p));
    this.input.on('wheel', (_p: unknown, _o: unknown, _dx: number, dy: number) => {
      cam.setZoom(Phaser.Math.Clamp(cam.zoom * (dy > 0 ? 0.9 : 1.1), 0.3, 2));
    });
    this.refresh();
    this.onReady();
  }

  /** Grass, asphalt and sidewalks never change between years, so bake them once. */
  private bakeGround(): void {
    const { width: MAP_W, height: MAP_H, roads } = this.plan;
    const kind = new Uint8Array(MAP_W * MAP_H); // 0 grass, 1 road, 2 sidewalk
    for (const r of roads)
      for (let y = r.y - 1; y < r.y + r.h + 1; y++)
        for (let x = r.x - 1; x < r.x + r.w + 1; x++) {
          if (x < 0 || y < 0 || x >= MAP_W || y >= MAP_H) continue;
          const inside = x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
          const i = y * MAP_W + x;
          if (inside) kind[i] = 1;
          else if (kind[i] === 0) kind[i] = 2;
        }
    const grass = this.manifest.grass ?? [];
    const asphalt = this.manifest.asphalt ?? [];
    const sidewalk = this.manifest.sidewalk ?? [];
    const hash = (x: number, y: number): number => ((Math.imul(x, 73856093) ^ Math.imul(y, 19349663)) >>> 0) / 4294967296;
    for (let cy = 0; cy * CHUNK < MAP_H; cy++) {
      for (let cx = 0; cx * CHUNK < MAP_W; cx++) {
        const w = Math.min(CHUNK, MAP_W - cx * CHUNK);
        const h = Math.min(CHUNK, MAP_H - cy * CHUNK);
        const rt = this.add.renderTexture(cx * CHUNK * TILE, cy * CHUNK * TILE, w * TILE, h * TILE).setOrigin(0).setDepth(-100);
        rt.beginDraw();
        for (let y = 0; y < h; y++)
          for (let x = 0; x < w; x++) {
            const gx = cx * CHUNK + x;
            const gy = cy * CHUNK + y;
            const k = kind[gy * MAP_W + gx];
            const n = hash(gx, gy);
            // Mostly the plain tile; sprinkle in the textured variants.
            const set = k === 1 ? asphalt : k === 2 ? sidewalk : grass;
            const entry = set.length ? (n < 0.97 ? set[0]! : pickVariant(set, (n - 0.97) * 33)) : undefined;
            if (entry) rt.batchDrawFrame(entry.key, undefined, x * TILE, y * TILE);
          }
        rt.endDraw();
      }
    }
    // Dashed center lines.
    const g = this.add.graphics().setDepth(-90);
    g.fillStyle(0xe8d44d, 0.9);
    for (const r of roads) {
      if (r.horizontal) for (let x = 0; x < MAP_W; x += 2) g.fillRect(x * TILE + 6, (r.y + 1) * TILE - 2, 20, 4);
      else for (let y = 0; y < MAP_H; y += 2) g.fillRect((r.x + 1) * TILE - 2, y * TILE + 6, 4, 20);
    }
  }

  setView(year: number, which: 'original' | 'altered'): void {
    this.view = { year, which };
    if (this.layout) this.refresh();
  }

  private world(): World | null {
    if (!this.worlds) return null;
    return this.worlds[this.view.which][this.view.year - 1950] ?? null;
  }

  private refresh(): void {
    const w = this.world();
    if (!w) return;
    this.layout = layoutTown(w);
    for (const o of this.drawn) o.destroy();
    this.drawn = [];
    for (const lot of this.layout.lots) this.drawLot(w, lot);
    for (const p of this.layout.props) this.drawProp(p, true);
    this.onWorld(w);
  }

  private drawProp(p: Prop, dynamic = false): void {
    const art = pickVariant(this.manifest[PROP_GROUP[p.kind]] ?? [], p.look);
    if (!art) return;
    const img = this.add.image(p.x, p.y, art.key).setOrigin(0.5, 1).setDepth(p.kind === 'flowers' ? p.y - 500 : p.y);
    if (dynamic) this.drawn.push(img);
  }

  private drawLot(w: World, lot: Lot): void {
    const cx = (lot.x + lot.w / 2) * TILE;
    const bottom = (lot.y + lot.h) * TILE;
    const add = (o: Phaser.GameObjects.GameObject): void => { this.drawn.push(o); };

    // Houses and shop fronts are stacks of sprites (see src/town/housing.ts and facade.ts).
    const { layers } = lot.art;
    layers.forEach((l, i) => {
      if (!this.textures.exists(l.key)) return;
      add(this.add.image(cx + l.dx, bottom - l.y, l.key).setOrigin(0.5, 1).setDepth(i === 0 ? bottom : bottom - 1 + i * 0.001));
    });
    if (lot.kind === 'house') return;

    const b = w.businesses[lot.refId]!;
    add(this.add.text(cx, bottom + 4, b.name, {
      fontFamily: 'system-ui, sans-serif', fontSize: '11px', color: '#fff', backgroundColor: 'rgba(0,0,0,0.6)', padding: { x: 4, y: 2 },
    }).setOrigin(0.5, 0).setDepth(bottom + 1));
  }

  private click(p: Phaser.Input.Pointer): void {
    const w = this.world();
    if (!w || !this.layout) return;
    const wp = this.cameras.main.getWorldPoint(p.x, p.y);
    const tx = wp.x / TILE;
    const ty = wp.y / TILE;
    const lot = this.layout.lots.find((l) => tx >= l.x && tx < l.x + l.w && ty >= l.y && ty < l.y + l.h);
    this.onInfo(lot ? lotDescription(w, lot) : null);
  }

  /** Feet box vs building footprints (the lower rows of each building, as wide as its sprite). */
  private blocked(x: number, y: number): boolean {
    if (!this.layout) return false;
    return this.layout.lots.some((l) => {
      const art = l.art;
      const half = Math.min(art.w, l.w * TILE) / 2;
      const cx = (l.x + l.w / 2) * TILE;
      const footTop = (l.y + l.h - Math.min(l.h, art.footRows)) * TILE;
      return x + 7 > cx - half && x - 7 < cx + half && y > footTop && y - 4 < (l.y + l.h) * TILE;
    });
  }

  update(_t: number, dt: number): void {
    const k = this.keys;
    if (!k) return;
    const dx = (k.D!.isDown || k.RIGHT!.isDown ? 1 : 0) - (k.A!.isDown || k.LEFT!.isDown ? 1 : 0);
    const dy = (k.S!.isDown || k.DOWN!.isDown ? 1 : 0) - (k.W!.isDown || k.UP!.isDown ? 1 : 0);
    if (!dx && !dy) {
      this.player.play(`idle-${DIRS[this.facing]}`, true);
      return;
    }
    this.facing = Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? 0 : 2) : dy > 0 ? 3 : 1;
    this.player.play(`walk-${DIRS[this.facing]}`, true);
    const len = Math.hypot(dx, dy);
    const sp = (180 * dt) / 1000 / len;
    const nx = Phaser.Math.Clamp(this.player.x + dx * sp, 10, this.plan.width * TILE - 10);
    const ny = Phaser.Math.Clamp(this.player.y + dy * sp, 64, this.plan.height * TILE - 4);
    if (!this.blocked(nx, this.player.y)) this.player.x = nx;
    if (!this.blocked(this.player.x, ny)) this.player.y = ny;
    this.player.setDepth(this.player.y);
  }
}

export const describeMayor = (w: World): string => (w.mayor ? `Mayor: ${fullName(w.people[w.mayor]!)}` : 'No mayor');
