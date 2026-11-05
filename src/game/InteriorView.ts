import Phaser from 'phaser';
import type { RoomPlan } from '../town/interiors';

/** Render only the current room. Outdoor streaming is suspended until the player leaves. */
export class InteriorView {
  readonly ox = -1000000;
  readonly oy = -1000000;
  private objects: Phaser.GameObjects.GameObject[] = [];
  constructor(scene: Phaser.Scene, readonly room: RoomPlan) {
    const {ox,oy} = this;
    const keep = <T extends Phaser.GameObjects.GameObject>(go:T): T => {this.objects.push(go);return go;};
    keep(scene.add.rectangle(ox-2000,oy-2000,room.width+4000,room.height+4000,0x11151b).setOrigin(0).setDepth(-1800000000));
    keep(scene.add.tileSprite(ox+32,oy+96,room.width-64,room.height-120,room.floor).setOrigin(0).setDepth(-1700000000));
    for (const a of room.areas) {
      if (a.floor) keep(scene.add.tileSprite(ox+a.rect.x,oy+a.rect.y,a.rect.w,a.rect.h,a.floor).setOrigin(0).setDepth(-1600000000));
      keep(scene.add.text(ox+a.rect.x+12,oy+a.rect.y+8,a.name,{fontFamily:'system-ui',fontSize:'11px',color:'#544c42'}).setDepth(-1500000000));
    }
    const walls=keep(scene.add.graphics().setDepth(oy+room.height+200));
    for (const w of room.walls) {
      walls.fillStyle(0x777b7d);walls.fillRect(ox+w.x,oy+w.y,w.w,w.h);
      walls.lineStyle(4,0x333a42);walls.strokeRect(ox+w.x,oy+w.y,w.w,w.h);
      walls.fillStyle(0xb3aa98);walls.fillRect(ox+w.x+3,oy+w.y+w.h-8,w.w-6,5);
    }
    // Back wall, trim and a visible exit threshold use the same boundaries as collision.
    keep(scene.add.text(ox+room.width/2,oy+40,room.title,{fontFamily:'system-ui',fontSize:'16px',color:'#fff'}).setOrigin(.5).setDepth(oy+room.height+201));
    keep(scene.add.rectangle(ox+room.exit.x,oy+room.exit.y,48,12,0xe8cf8e).setDepth(-1400000000));
    keep(scene.add.text(ox+room.exit.x,oy+room.exit.y+14,'EXIT',{fontFamily:'system-ui',fontSize:'10px',color:'#e8cf8e'}).setOrigin(.5,0).setDepth(oy+room.height+201));
    for (const f of room.furniture) {
      keep(scene.add.image(ox+f.x,oy+f.y,f.key).setOrigin(0).setDepth(oy+f.y+f.h+(f.solid?0:96)));
    }
  }
  destroy():void { for(const o of this.objects)o.destroy();this.objects=[]; }
}
