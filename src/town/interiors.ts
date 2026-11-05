import { rand } from '../sim/rng';
import type { World } from '../sim/types';
import { feetHit, type Point } from './collision';
import { INTERIOR_ASSETS } from './interiorAssets';
import type { Rect } from './kinds';
import type { LandmarkPlot } from './landmarks';
import type { Lot } from './layout';

export interface Entrance {
  id: string; title: string; door: Point; lot?: Lot; landmark?: LandmarkPlot;
}
export interface Furnishing extends Rect { key: string; solid: boolean; stock?: boolean }
export interface RoomPlan {
  id: string; title: string; use: string; template: string; width: number; height: number;
  floor: string; walls: Rect[]; furniture: Furnishing[];
  areas: { name: string; rect: Rect; floor?: string }[];
  spawn: Point; exit: Point;
}

/** Persistent structure is keyed to the property, not the occupant or current year. */
export function planInterior(w: World, e: Entrance): RoomPlan {
  const lot = e.lot;
  const home = lot?.kind === 'house';
  const use = home ? (lot.tier === 'apartment' ? 'Apartment' : 'Home')
    : e.landmark?.name ?? w.businesses[lot!.refId]!.kind;
  const widths: Record<string, number> = { small:512, medium:576, house:640, villa:704, estate:768, apartment:640 };
  const W = home ? widths[lot.tier]! : e.landmark?.id === 'hotel' ? 832 : e.landmark ? 768 : 640;
  const H = home && lot.tier === 'small' ? 480 : e.landmark?.id === 'hotel' ? 704 : e.landmark ? 640 : 576;
  const template = home ? (lot.tier === 'apartment' ? 'apartment' : 'home') : e.landmark?.id ?? use;
  const floor = home ? 'interior_floorWood' : /Hospital|Ice Cream|Butcher|Gym/.test(use) ? 'interior_floorTile' : 'interior_floorStone';
  const walls: Rect[] = [
    {x:0,y:0,w:W,h:96}, {x:0,y:96,w:32,h:H-96},
    {x:W-32,y:96,w:32,h:H-96}, {x:32,y:H-24,w:W-64,h:24},
  ];
  const furniture: Furnishing[] = [];
  const areas: RoomPlan['areas'] = [];
  const put = (name: keyof typeof INTERIOR_ASSETS, x: number, bottom: number, solid = true, stock = false): void => {
    const a = INTERIOR_ASSETS[name];
    furniture.push({key:a.key,x:x-a.w/2,y:bottom-a.h,w:a.w,h:a.h,solid,stock});
  };
  const bed = rand(w.seed, 'interior', e.id, 'bed') < 0.5 ? 'bed' : 'bedGreen';
  if (home) {
    const split = Math.floor(W * .6 / 32) * 32, doorY = H-272, bathY = H-192;
    walls.push({x:split,y:96,w:16,h:doorY-96}, {x:split,y:doorY+80,w:16,h:H-24-doorY-80},
      {x:split+16,y:bathY,w:32,h:16}, {x:split+112,y:bathY,w:W-32-split-112,h:16});
    areas.push({name:'Living & kitchen',rect:{x:32,y:96,w:split-32,h:H-120}},
      {name:'Bedroom',rect:{x:split+16,y:96,w:W-split-48,h:bathY-96}},
      {name:'Bathroom',rect:{x:split+16,y:bathY+16,w:W-split-48,h:H-bathY-40},floor:'interior_floorTile'});
    put('counter',96,176); put('sink',160,176); put('stove',224,176); put('fridge',split-32,192);
    put('sofa',96,H-176); put('table',split-112,H-144); put('plant',64,H-48);
    put(bed,split+80,192); put('wardrobe',W-64,192);
    if (H >= 544) put(bed,split+80,304);
    put('toilet',W-64,H-64); put('bathroomSink',split+64,H-64);
  } else {
    const area = (name:string,x:number,y:number,width:number,height:number,floor?:string):void => {
      areas.push({name,rect:{x,y,w:width,h:height},floor});
    };
    const horizontal = (y:number,gapX:number,gap=96):void => {
      if(gapX>32)walls.push({x:32,y,w:gapX-32,h:16});
      if(gapX+gap<W-32)walls.push({x:gapX+gap,y,w:W-32-gapX-gap,h:16});
    };
    const vertical = (x:number,gapY:number,gap=80):void => {
      if(gapY>96)walls.push({x,y:96,w:16,h:gapY-96});
      walls.push({x,y:gapY+gap,w:16,h:H-24-gapY-gap});
    };
    const retail = (back:string,front:string,counters=true):void => {
      horizontal(256,W-144,112);
      area(back,32,96,W-64,160);
      area(front,32,272,W-64,H-296);
      if(counters)for(const x of [112,176,240])put('counter',x,352);
      put('plant',W-64,H-48);
    };
    switch(template) {
      case 'Bakery':
        retail('Bakehouse','Bread & pastries');
        put('stove',96,192);put('stove',144,192);put('counter',240,192);put('bakeryRack',352,208);put('fridge',W-96,208);
        for(const x of [112,176,240])put('bread',x,310,false,true);
        put('bakeryRack',W-112,432,true,true);put('table',112,448);put('chair',112,512);
        break;
      case 'Butcher':
        retail('Cold store & prep','Butcher counter');
        put('fridge',112,208);put('fridge',176,208);put('counter',288,192);put('sink',384,192);
        for(const x of [112,176,240])put('meat',x,310,false,true);
        for(const x of [W-160,W-96])put('meatCase',x,432,true,true);
        break;
      case 'Ice Cream Parlor':
        retail('Preparation','Ice cream & seating',false);
        put('fridge',112,208);put('sink',208,192);put('counter',320,192);
        for(const x of [104,152,200,248])put('iceDisplay',x,352,true,true);
        for(const x of [W-240,W-128]){put('table',x,416);put('chair',x,480);}
        break;
      case 'Bait Shop':
        retail('Tackle stockroom','Rods & fishing gear');
        for(const x of [112,240,368])put('tackle',x,208,true,true);
        for(const x of [96,208,W-112])put('fishingRack',x,448,true,true);
        break;
      case 'Music Store':
        area('Instruments',32,96,W-64,224,'interior_floorWood');
        area('Try an instrument',32,336,W-64,H-360,'interior_floorWood');
        put('piano',112,208);put('piano',240,208);put('counter',W-112,208);
        for(const x of [96,192,W-96])put('guitar',x,384,true,true);
        put('chair',112,512);put('plant',W-64,H-48);
        break;
      case 'Clothing Store':
        retail('Stock & fitting','Everyday clothing');
        put('wardrobe',112,208);put('wardrobe',272,208);put('chair',W-112,208);
        for(const x of [96,208,W-112])put('clothingRack',x,448,true,true);
        break;
      case 'Dress Shop':
        retail('Fitting room','Dress showroom');
        put('wardrobe',112,208);put('sofa',272,208);put('plant',W-112,208);
        for(const x of [112,208,W-112])put('mannequin',x,448,true,true);
        put('clothingRack',W-112,352,true,true);
        break;
      case 'Toy Store':
        retail('Toy stockroom','Toys & games');
        put('cabinet',112,208);put('cabinet',272,208);put('dollhouse',W-112,208,true,true);
        for(const x of [96,208,W-112]){put('cabinet',x,448);put('gift',x,404,false,true);}
        put('toyCar',112,310,false,true);put('toyCar',240,310,false,true);
        break;
      case 'Gym':
        vertical(W-176,352);
        area('Training floor',32,96,W-224,H-120);
        area('Changing room',W-160,96,128,H-120,'interior_floorTile');
        for(const x of [112,240,368]){put('treadmill',x,224);put('weights',x,384);}
        put('wardrobe',W-96,208);put('bathroomSink',W-96,480);put('sofa',128,512);
        break;
      case 'Bank':
        retail('Records & secure storage','Banking hall');
        for(const x of [96,160,224,288])put('files',x,160);
        put('desk',W-112,208);put('chair',W-112,448);put('sofa',112,512);
        break;
      case 'Law Office':
        vertical(W-224,320);
        area('Consultation',32,96,W-272,H-120,'interior_floorWood');
        area('Case library',W-208,96,176,H-120,'interior_floorWood');
        put('desk',160,240);put('chair',160,320);put('sofa',128,480);
        for(const y of [192,304])put('bookcase',W-112,y);
        put('files',W-112,480);put('plant',64,H-48);
        break;
      case "Doctor's Office":
        vertical(W-272,320);
        area('Waiting room',32,96,W-320,H-120);
        area('Examination',W-256,96,224,H-120,'interior_floorTile');
        put('desk',128,208);put('chair',96,352);put('chair',176,352);put('sofa',128,480);
        put('hospitalBed',W-160,240);put('medical',W-96,384);put('bathroomSink',W-160,480);
        break;
      case 'post':
        horizontal(272,W-144,112);
        area('Sorting & parcels',32,96,W-64,176);area('Postal counter',32,288,W-64,H-312);
        put('counter',112,240);put('letter',112,204,false,true);
        for(const x of [240,352,448])put('parcels',x,208,true,true);
        put('mailBox',W-96,208);
        for(const x of [112,176,240])put('counter',x,400);
        put('sofa',W-128,528);put('plant',64,H-48);
        break;
      case 'school':
        vertical(480,352);
        area('Classroom',32,96,448,H-120,'interior_floorWood');
        area('Library',496,96,W-528,H-120,'interior_floorWood');
        put('blackboard',192,160);put('teacherDesk',320,192);
        for(const x of [112,224,336])for(const y of [320,448])put('schoolDesk',x,y);
        for(const x of [576,672])for(const y of [192,288])put('bookcase',x,y);
        put('table',608,448);put('chair',608,512);
        break;
      case 'hospital':
        horizontal(432,W/2-48,96);
        walls.push({x:480,y:96,w:16,h:224},{x:480,y:400,w:16,h:32});
        area('Ward',32,96,448,336,'interior_floorTile');
        area('Treatment',496,96,W-528,336,'interior_floorTile');
        area('Reception & waiting',32,448,W-64,H-472);
        for(const x of [112,240,368])put('hospitalBed',x,256);
        put('medical',112,384);put('hospitalBed',592,256);put('medical',704,256);put('bathroomSink',592,384);
        put('desk',112,560);put('sofa',W-112,560);
        break;
      case 'hotel': {
        area('Reception',32,560,W-64,H-584);
        for(const x of [352,464]){
          walls.push({x,y:96,w:16,h:128},{x,y:304,w:16,h:112},{x,y:496,w:16,h:48});
        }
        walls.push({x:32,y:352,w:320,h:16},{x:480,y:352,w:W-512,h:16});
        for(const [x,y,n] of [[32,96,1],[480,96,2],[32,368,3],[480,368,4]]){
          area(`Guest room ${n}`,x!,y!,320,y===96?256:176,'interior_floorWood');
          put(bed,x!+112,y!+128);put('wardrobe',x!+240,y!+128);
        }
        put('counter',144,640);put('sofa',W-144,640);put('plant',64,H-48);
        break;
      }
      case 'police':
        vertical(W-240,416);
        walls.push({x:W-224,y:320,w:64,h:16},{x:W-80,y:320,w:48,h:16});
        area('Reception & interview',32,96,W-288,H-120);
        area('Holding cells',W-224,96,192,H-120);
        put('desk',112,224);put('desk',240,224);put('files',112,400);put('table',320,416);put('chair',320,480);
        for(const y of [224,448])put('hospitalBed',W-128,y);
        for(const x of [W-192,W-64])put('bars',x,160);
        break;
      case 'fire':
        vertical(480,448);
        area('Vehicle bay',32,96,448,H-120);
        area('Dispatch & crew room',496,96,W-528,H-120,'interior_floorWood');
        put('fireTruck',256,384);put('desk',592,208);put('wardrobe',672,352);put('table',592,512);put('chair',672,512);
        break;
      case 'clocktower':
        area('Clock maintenance',32,96,W-64,H-120,'interior_floorWood');
        for(const x of [112,224,W-112])put('clock',x,208);
        put('counter',128,400);put('cabinet',W-112,400);put('table',W/2,320);put('plant',64,H-48);
        break;
      default: throw new Error(`No interior template for ${template}`);
    }
  }
  // Vacant properties retain the same shell and room divisions; loose goods are absent.
  const contents = lot?.vacantSince === undefined ? furniture : furniture.filter((f) => !f.stock);
  return {id:e.id,title:e.title,use,template,width:W,height:H,floor,walls,furniture:contents,areas,
    spawn:{x:W/2,y:H-64},exit:{x:W/2,y:H-40}};
}

export function interiorBlocked(room: RoomPlan, x: number, y: number): boolean {
  if (x-7 < 0 || y-8 < 0 || x+7 > room.width || y > room.height) return true;
  return room.walls.some((r) => feetHit(x,y,r)) || room.furniture.some((f) => f.solid && feetHit(x,y,f));
}
