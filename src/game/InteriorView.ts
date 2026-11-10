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
    if(room.outsideExit){
    keep(scene.add.rectangle(ox+room.exit.x,oy+room.exit.y,48,12,0xe8cf8e).setDepth(-1400000000));
    keep(scene.add.text(ox+room.exit.x,oy+room.exit.y+14,'EXIT',{fontFamily:'system-ui',fontSize:'10px',color:'#e8cf8e'}).setOrigin(.5,0).setDepth(oy+room.height+201));
    }
    for(const c of room.connections){
      const g=keep(scene.add.graphics().setDepth(oy+room.height+202));
      if(c.kind==='stairs'){
        g.setDepth(-1300000000);
        const x=ox+c.at.x-24,y=oy+c.at.y-32;
        g.fillStyle(0x4a515b);g.fillRect(x,y,48,64);
        g.lineStyle(2,0xc5c5b5);
        for(let i=0;i<7;i++)g.lineBetween(x+4,y+6+i*8,x+44,y+6+i*8);
        g.lineStyle(3,0x343a43);g.lineBetween(x,y,x,y+64);g.lineBetween(x+48,y,x+48,y+64);
      }else if(c.doorRect){
        const d=c.doorRect,x=ox+d.x,y=oy+d.y;
        g.fillStyle(0x3e302a);g.fillRect(x-3,y-3,d.w+6,d.h+6);
        g.fillStyle(0xa77a4c);g.fillRect(x,y,d.w,d.h);
        g.lineStyle(2,0xd7ba88);g.strokeRect(x+5,y+5,d.w-10,Math.max(4,d.h-10));
        g.fillStyle(0xe8cf8e);g.fillCircle(x+d.w-8,y+d.h*.65,2);
        const number=c.id.startsWith('guest:')?c.id.slice(6):'';
        if(number)keep(scene.add.text(x+d.w/2,y+9,number,{fontFamily:'system-ui',fontSize:'12px',color:'#fff4cc'}).setOrigin(.5,0).setDepth(oy+room.height+203));
      }
      keep(scene.add.rectangle(ox+c.at.x,oy+c.at.y,24,6,0xe8cf8e,.85).setDepth(-1400000000));
    }
    for (const f of room.furniture) {
      keep(scene.add.image(ox+f.x,oy+f.y,f.key).setOrigin(0).setDepth(oy+f.y+f.h+(f.solid?0:96)));
    }
  }
  destroy():void { for(const o of this.objects)o.destroy();this.objects=[]; }
}
