import Phaser from 'phaser';
import type { TownLayout } from '../town/layout';
import { buildResidents, dayAt, type Resident } from '../town/schedule';
import type { World } from '../sim/types';
import { TILE } from './TownView';

const COLS = 56;
const DIRS = ['right', 'up', 'left', 'down'] as const;
/** Game hours per real second: a full day lasts 8 real minutes. */
export const HOURS_PER_SECOND = 24 / 480;

interface Town { ox: number; oy: number; layout: TownLayout; world: World }

/**
 * The people of the town you are in. Each is a sprite that appears when the schedule (town/schedule.ts)
 * puts them on the street and vanishes when they are indoors. Only the current town is simulated.
 */
export class Npcs {
  /** Game time of day, 0..24. */
  hour = 7.5;
  /** Whole game days since the game started (gossip spreads with it). */
  days = 0;
  private sprites = new Map<string, Phaser.GameObjects.Sprite>();
  private cast: Resident[] = [];
  private castFor: TownLayout | null = null;
  private sheets: string[];
  private made = new Set<string>();

  constructor(private scene: Phaser.Scene, charKeys: string[]) {
    this.sheets = charKeys.length ? charKeys : ['char_01'];
  }

  private ensureAnims(key: string): void {
    if (this.made.has(key)) return;
    this.made.add(key);
    DIRS.forEach((d, i) => {
      const mk = (name: string, row: number, rate: number): void => {
        if (!this.scene.anims.exists(`${key}-${name}-${d}`)) this.scene.anims.create({ key: `${key}-${name}-${d}`, frames: this.scene.anims.generateFrameNumbers(key, { start: row * COLS + i * 6, end: row * COLS + i * 6 + 5 }), frameRate: rate, repeat: -1 });
      };
      mk('walk', 2, 10); mk('idle', 1, 6);
    });
  }

  /** Person standing (or walking) at world pixel (wx, wy), within `reach` pixels, if any. */
  personAt(wx: number, wy: number, reach = 26): string | null {
    let best: string | null = null, bd = reach;
    for (const [id, s] of this.sprites) {
      const d = Math.hypot(wx - s.x, wy - (s.y - 28));
      if (d < bd) { bd = d; best = id; }
    }
    return best;
  }

  /** How many other people are within `reach` pixels of a point (they can see what happens there). */
  countNear(wx: number, wy: number, reach: number, except?: string): number {
    let n = 0;
    for (const [id, s] of this.sprites) if (id !== except && Math.hypot(wx - s.x, wy - s.y) < reach) n++;
    return n;
  }

  /** Where a person is on screen right now (null if indoors). */
  positionOf(id: string): { x: number; y: number } | null {
    const s = this.sprites.get(id);
    return s ? { x: s.x, y: s.y } : null;
  }

  /** People who stop and face the player while talking. */
  held = new Set<string>();

  residentById(id: string): Resident | undefined { return this.cast.find((r) => r.id === id); }
  get count(): number { return this.sprites.size; }

  /** Move everyone to where the clock says they are. `town` is the town the player is in (or null). */
  update(dt: number, town: Town | null, playerX: number, playerY: number): void {
    const next = this.hour + (dt / 1000) * HOURS_PER_SECOND;
    if (next >= 24) this.days++;
    this.hour = next % 24;
    if (!town) { this.clear(); return; }
    if (this.castFor !== town.layout) {
      this.clear();
      this.cast = buildResidents(town.layout, town.world);
      this.castFor = town.layout;
    }
    const seen = new Set<string>();
    for (const r of this.cast) {
      const pose = dayAt(r, this.hour).pose;
      if (!pose) continue;
      const x = town.ox + pose.x * TILE, y = town.oy + pose.y * TILE;
      if (Math.abs(x - playerX) > 900 || Math.abs(y - playerY) > 700) continue; // off screen: don't bother
      seen.add(r.id);
      let s = this.sprites.get(r.id);
      if (!s) {
        const key = this.sheets[Math.floor(r.look * this.sheets.length)]!;
        this.ensureAnims(key);
        s = this.scene.add.sprite(x, y, key, COLS + 18).setOrigin(0.5, 1);
        s.setData('key', key);
        this.sprites.set(r.id, s);
      }
      const key = s.getData('key') as string;
      if (this.held.has(r.id)) { s.play(`${key}-idle-down`, true); continue; }
      s.play(`${key}-${pose.moving ? 'walk' : 'idle'}-${DIRS[pose.dir]}`, true);
      s.setPosition(x, y).setDepth(y);
    }
    for (const [id, s] of this.sprites) if (!seen.has(id)) { s.destroy(); this.sprites.delete(id); }
  }

  clear(): void {
    for (const s of this.sprites.values()) s.destroy();
    this.sprites.clear();
    this.castFor = null;
    this.cast = [];
  }
}
