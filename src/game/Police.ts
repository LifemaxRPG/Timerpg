import Phaser from 'phaser';

const COLS = 56;
const DIRS = ['right', 'up', 'left', 'down'] as const;

/**
 * The law. While the player is wanted in a town that has a police station, an officer leaves the station
 * and walks straight at them. Catching up is an arrest: the player is taken back to the station and the
 * heat is off (see WorldScene). Officers never decide anything about the simulation.
 */
export class Police {
  private cop: Phaser.GameObjects.Sprite | null = null;

  constructor(private scene: Phaser.Scene, private key: string) {
    for (const d of DIRS) {
      const i = DIRS.indexOf(d);
      if (!scene.anims.exists(`cop-walk-${d}`)) scene.anims.create({ key: `cop-walk-${d}`, frames: scene.anims.generateFrameNumbers(key, { start: 2 * COLS + i * 6, end: 2 * COLS + i * 6 + 5 }), frameRate: 10, repeat: -1 });
    }
  }

  get active(): boolean { return this.cop !== null; }

  /** Where the officer is (null if not out). */
  get at(): { x: number; y: number } | null { return this.cop ? { x: this.cop.x, y: this.cop.y } : null; }

  /** Spawn the officer if needed (at `start`). */
  ensure(start: { x: number; y: number }): void {
    if (!this.cop) this.cop = this.scene.add.sprite(start.x, start.y, this.key, COLS + 18).setOrigin(0.5, 1).setTint(0x8fb4ff);
  }

  /**
   * Walk the officer along `route` (world pixels, streets toward the player), then straight at the player.
   * Returns true when they have caught up.
   */
  chase(dt: number, route: { x: number; y: number }[], player: { x: number; y: number }): boolean {
    const c = this.cop;
    if (!c) return false;
    while (route.length > 1 && Math.hypot(route[0]!.x - c.x, route[0]!.y - c.y) < 10) route.shift();
    const target = route.length > 1 ? route[0]! : player;
    const dx = target.x - c.x, dy = target.y - c.y, d = Math.hypot(dx, dy) || 1;
    if (Math.hypot(player.x - c.x, player.y - c.y) < 34) return true;
    const speed = 150 * Math.min(dt, 100) / 1000;
    c.setPosition(c.x + (dx / d) * speed, c.y + (dy / d) * speed).setDepth(c.y);
    const dir = Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? 0 : 2) : dy > 0 ? 3 : 1;
    c.play(`cop-walk-${DIRS[dir]}`, true);
    return false;
  }

  dismiss(): void { this.cop?.destroy(); this.cop = null; }
}
