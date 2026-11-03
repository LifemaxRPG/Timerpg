import Phaser from 'phaser';
import { fullName, type World } from '../sim';
import { layoutTown, lotDescription, type Lot, type TownLayout } from '../town/layout';

export const TILE = 32;
const HOUSE_COLORS = [0xd9b99b, 0xc9d6c0, 0xb9c7dd, 0xe0c4c4, 0xd6d2a6, 0xc4b5d9, 0xe2cf9f, 0xa9cfc9];
const SHOP_COLORS = [0xc0392b, 0x2e86c1, 0x27ae60, 0xd68910, 0x8e44ad, 0x17a589, 0xcb4335, 0x2874a6];

export class TownScene extends Phaser.Scene {
  private worlds: { original: World[]; altered: World[] } | null = null;
  private view = { year: 2025, which: 'original' as 'original' | 'altered' };
  private layout: TownLayout | null = null;
  private drawn: Phaser.GameObjects.GameObject[] = [];
  private player!: Phaser.GameObjects.Rectangle;
  private keys!: Record<string, Phaser.Input.Keyboard.Key>;
  /** Exposed for tests/debugging. */
  public onInfo: (lines: string[] | null) => void = () => {};
  public onWorld: (w: World) => void = () => {};
  public onReady: () => void = () => {};
  public onEra: (css: string) => void = () => {};

  constructor() {
    super('town');
  }

  setWorlds(original: World[], altered: World[]): void {
    this.worlds = { original, altered };
  }

  create(): void {
    const kb = this.input.keyboard!;
    this.keys = kb.addKeys('W,A,S,D,UP,DOWN,LEFT,RIGHT') as Record<string, Phaser.Input.Keyboard.Key>;
    this.player = this.add.rectangle(48 * TILE, 35 * TILE - 16, 18, 18, 0xffe066).setStrokeStyle(2, 0x222222).setDepth(1000);
    this.cameras.main.startFollow(this.player, true, 0.12, 0.12);
    this.cameras.main.setZoom(0.75);
    this.input.on('pointerdown', (p: Phaser.Input.Pointer) => this.click(p));
    this.input.on('wheel', (_p: unknown, _o: unknown, _dx: number, dy: number) => {
      const cam = this.cameras.main;
      cam.setZoom(Phaser.Math.Clamp(cam.zoom * (dy > 0 ? 0.9 : 1.1), 0.35, 1.6));
    });
    this.refresh();
    this.onReady();
  }

  setView(year: number, which: 'original' | 'altered'): void {
    this.view = { year, which };
    if (this.sys.isActive() || this.layout) this.refresh();
  }

  private world(): World | null {
    if (!this.worlds) return null;
    const list = this.worlds[this.view.which];
    return list[this.view.year - 1950] ?? null;
  }

  private refresh(): void {
    const w = this.world();
    if (!w) return;
    this.layout = layoutTown(w);
    for (const o of this.drawn) o.destroy();
    this.drawn = [];
    this.drawTown(w, this.layout);
    this.drawOverlay();
    this.onWorld(w);
  }

  private drawOverlay(): void {
    // Older eras get a warm, faded tint (a page-level multiply overlay); the present is untinted.
    const t = (2025 - this.view.year) / 75;
    const c = Phaser.Display.Color.Interpolate.ColorWithColor(
      new Phaser.Display.Color(255, 255, 255), new Phaser.Display.Color(226, 188, 140), 100, Math.round(t * 100));
    this.onEra(`rgb(${Math.round(c.r)}, ${Math.round(c.g)}, ${Math.round(c.b)})`);
  }

  private drawTown(w: World, l: TownLayout): void {
    const px = (n: number): number => n * TILE;
    const g = this.add.graphics().setDepth(0);
    this.drawn.push(g);
    const worldW = px(l.width);
    const worldH = px(l.height);
    this.cameras.main.setBounds(0, 0, worldW, worldH);

    // grass (cheap checker)
    g.fillStyle(0x6aa84f, 1).fillRect(0, 0, worldW, worldH);
    g.fillStyle(0x62a047, 1);
    for (let y = 0; y < l.height; y++) for (let x = (y % 2); x < l.width; x += 2) g.fillRect(px(x), px(y), TILE, TILE);

    // roads
    for (const r of l.roads) {
      g.fillStyle(0x9a9a9a, 1).fillRect(px(r.x) - 4, px(r.y) - 4, px(r.w) + 8, px(r.h) + 8); // sidewalk
      g.fillStyle(r.main ? 0x3a3a3f : 0x4a4a50, 1).fillRect(px(r.x), px(r.y), px(r.w), px(r.h));
    }
    g.fillStyle(0xe8d44d, 1);
    for (const r of l.roads.filter((r) => r.horizontal)) {
      for (let x = 0; x < l.width; x += 2) g.fillRect(px(x) + 6, px(r.y) + TILE - 2, 20, 4);
    }

    for (const lot of l.lots) this.drawLot(lot);
    void w;
  }

  private drawLot(lot: Lot): void {
    const x = lot.x * TILE;
    const y = lot.y * TILE;
    const wpx = lot.w * TILE;
    const hpx = lot.h * TILE;
    const g = this.add.graphics().setDepth(y + hpx);
    this.drawn.push(g);
    if (lot.kind === 'house') {
      const body = HOUSE_COLORS[Math.floor(lot.look * HOUSE_COLORS.length)]!;
      g.fillStyle(0x000000, 0.2).fillRect(x + 6, y + 8, wpx - 8, hpx - 6); // shadow
      g.fillStyle(body, 1).fillRect(x + 4, y + 4, wpx - 8, hpx - 8);
      g.fillStyle(0x8b3a2f, 1).fillRect(x + 2, y + 2, wpx - 4, 34); // roof
      g.fillStyle(0x5a2a22, 1).fillRect(x + 2, y + 34, wpx - 4, 4);
      g.fillStyle(0x5b3a1e, 1).fillRect(x + wpx / 2 - 8, y + hpx - 34, 16, 28); // door
      g.fillStyle(0xcfe8ff, 1).fillRect(x + 14, y + 52, 22, 20).fillRect(x + wpx - 36, y + 52, 22, 20);
    } else {
      const col = SHOP_COLORS[Math.floor(lot.look * SHOP_COLORS.length)]!;
      g.fillStyle(0x000000, 0.2).fillRect(x + 6, y + 8, wpx - 8, hpx - 6);
      g.fillStyle(0xe9e2d0, 1).fillRect(x + 2, y + 2, wpx - 4, hpx - 4);
      g.fillStyle(col, 1).fillRect(x + 2, y + 2, wpx - 4, 30); // sign band
      for (let i = 0; i < wpx - 4; i += 16) g.fillStyle(i % 32 === 0 ? col : 0xffffff, 1).fillRect(x + 2 + i, y + 32, 16, 12); // awning
      g.fillStyle(0xcfe8ff, 1).fillRect(x + 12, y + 52, wpx - 24, 40);
      g.fillStyle(0x5b3a1e, 1).fillRect(x + wpx / 2 - 9, y + hpx - 40, 18, 36);
      const t = this.add.text(x + wpx / 2, y + 16, lot.label.replace("'s ", '\n'), {
        fontFamily: 'system-ui, sans-serif', fontSize: '11px', color: '#fff', align: 'center', fontStyle: 'bold',
      }).setOrigin(0.5).setDepth(y + hpx + 1);
      this.drawn.push(t);
    }
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

  private blocked(x: number, y: number): boolean {
    if (!this.layout) return false;
    const r = 9;
    return this.layout.lots.some(
      (l) => x + r > l.x * TILE && x - r < (l.x + l.w) * TILE && y + r > l.y * TILE && y - r < (l.y + l.h) * TILE);
  }

  update(_t: number, dt: number): void {
    const k = this.keys;
    if (!k) return;
    const dx = (k.D!.isDown || k.RIGHT!.isDown ? 1 : 0) - (k.A!.isDown || k.LEFT!.isDown ? 1 : 0);
    const dy = (k.S!.isDown || k.DOWN!.isDown ? 1 : 0) - (k.W!.isDown || k.UP!.isDown ? 1 : 0);
    if (!dx && !dy) return;
    const len = Math.hypot(dx, dy);
    const sp = (200 * dt) / 1000 / len;
    const nx = Phaser.Math.Clamp(this.player.x + dx * sp, 10, 96 * TILE - 10);
    const ny = Phaser.Math.Clamp(this.player.y + dy * sp, 10, 72 * TILE - 10);
    if (!this.blocked(nx, this.player.y)) this.player.x = nx;
    if (!this.blocked(this.player.x, ny)) this.player.y = ny;
    this.player.setDepth(this.player.y + 9);
  }
}

export const describeMayor = (w: World): string => (w.mayor ? `Mayor: ${fullName(w.people[w.mayor]!)}` : 'No mayor');
