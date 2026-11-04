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
export interface Furnishing extends Rect { key: string; solid: boolean }
export interface RoomPlan {
  id: string; title: string; use: string; width: number; height: number;
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
  const W = home ? widths[lot.tier]! : e.landmark ? 704 : 576;
  const H = home && lot.tier === 'small' ? 480 : 576;
  const floor = home ? 'interior_floorWood' : /Hospital|Ice Cream|Butcher|Gym/.test(use) ? 'interior_floorTile' : 'interior_floorStone';
  const walls: Rect[] = [
    {x:0,y:0,w:W,h:96}, {x:0,y:96,w:32,h:H-96},
    {x:W-32,y:96,w:32,h:H-96}, {x:32,y:H-24,w:W-64,h:24},
  ];
  const furniture: Furnishing[] = [];
  const areas: RoomPlan['areas'] = [];
  const put = (name: keyof typeof INTERIOR_ASSETS, x: number, bottom: number, solid = true): void => {
    const a = INTERIOR_ASSETS[name];
    furniture.push({key:a.key,x:x-a.w/2,y:bottom-a.h,w:a.w,h:a.h,solid});
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
  } else if (e.landmark?.id === 'hospital' || e.landmark?.id === 'hotel') {
    areas.push({name:use,rect:{x:32,y:96,w:W-64,h:H-120}});
    for (const x of [112,240,W-112]) put(e.landmark.id === 'hospital' ? 'hospitalBed' : bed,x,224);
    put('desk',112,H-112); put('plant',W-80,H-64);
    if (e.landmark.id === 'hospital') put('medical',W-112,352);
  } else if (e.landmark?.id === 'school') {
    areas.push({name:'Classroom',rect:{x:32,y:96,w:W-64,h:H-120}});
    for (const x of [112,240,W-112]) for (const y of [224,352]) put('desk',x,y);
    put('cabinet',W-112,144); put('plant',64,H-48);
  } else if (use === 'Gym') {
    for (const x of [112,240,W-112]) { put('treadmill',x,240); put('weights',x,368); }
    put('plant',W-64,H-48);
  } else if (use === 'Music Store') {
    put('piano',112,208); put('piano',240,208);
    for (const x of [96,208,W-96]) put('guitar',x,352);
    put('counter',W-112,208);
  } else if (/Clothing|Dress|Toy/.test(use)) {
    for (const x of [112,240,W-112]) { put('wardrobe',x,208); put('mannequin',x,352); }
    put('counter',112,H-112);
  } else if (e.landmark || /Office|Bank/.test(use)) {
    put('desk',112,208); put('chair',112,304); put('desk',W-112,208);
    put('wardrobe',W-112,352); put('sofa',112,H-112); put('plant',W-64,H-48);
  } else {
    put('counter',112,208); put('counter',240,208); put('counter',W-112,208);
    if (use === 'Bakery') {
      for (const x of [112,240,W-112]) put('bread',x,170,false);
      put('stove',96,336); put('cabinet',W-96,336);
    } else {
      for (const x of [112,240,W-112]) put('shelf',x,352);
    }
    put('plant',W-64,H-48);
  }
  // Vacant properties retain the same shell and room divisions; loose goods are absent.
  const contents = lot?.vacantSince === undefined ? furniture : furniture.filter((f) => !['interior_bread','interior_guitar','interior_mannequin'].includes(f.key));
  return {id:e.id,title:e.title,use,width:W,height:H,floor,walls,furniture:contents,areas,
    spawn:{x:W/2,y:H-64},exit:{x:W/2,y:H-40}};
}

export function interiorBlocked(room: RoomPlan, x: number, y: number): boolean {
  if (x-7 < 0 || y-8 < 0 || x+7 > room.width || y > room.height) return true;
  return room.walls.some((r) => feetHit(x,y,r)) || room.furniture.some((f) => f.solid && feetHit(x,y,f));
}
