import { rand } from '../sim/rng';
import type { World } from '../sim/types';
import { INTERIOR_ASSETS } from './interiorAssets';
import type { Entrance, RoomPlan, InteriorConnection } from './interiors';

/** Entire building topology is derived from the property, never the viewed year or visit order. */
export function hotelSpec(seed:string,property:string): { floors:number; roomsPerFloor:number } {
  return {floors:2+Math.floor(rand(seed,'hotel',property,'floors')*2),roomsPerFloor:rand(seed,'hotel',property,'rooms')<.5?4:6};
}

function shell(e:Entrance,zoneId:string,label:string,width:number,height:number,wood=false):RoomPlan {
  return {id:e.id,title:`${e.title} · ${label}`,use:e.landmark!.name,template:e.landmark!.id,
    zoneId,outsideExit:false,connections:[],width,height,
    floor:wood?'interior_floorWood':'interior_floorStone',
    walls:[{x:0,y:0,w:width,h:96},{x:0,y:96,w:32,h:height-96},
      {x:width-32,y:96,w:32,h:height-96},{x:32,y:height-24,w:width-64,h:24}],
    furniture:[],areas:[],spawn:{x:width/2,y:height-64},exit:{x:width/2,y:height-40}};
}
function put(r:RoomPlan,name:keyof typeof INTERIOR_ASSETS,x:number,bottom:number):void {
  const a=INTERIOR_ASSETS[name];r.furniture.push({key:a.key,x:x-a.w/2,y:bottom-a.h,w:a.w,h:a.h,solid:true});
}
function area(r:RoomPlan,name:string,x:number,y:number,w:number,h:number,floor?:string):void {
  r.areas.push({name,rect:{x,y,w,h},floor});
}
const stairs=(id:string,label:string,x:number,y:number,target:string,arrival:string):InteriorConnection =>
  ({id,label,at:{x,y},target,arrival,kind:'stairs'});

export function planLargeBuilding(w:World,e:Entrance,zoneId?:string):RoomPlan|null {
  if(e.landmark?.id==='police') {
    if(!zoneId||zoneId==='main')return null;
    if(zoneId!=='police:upper')throw new Error(`Unknown police level ${zoneId}`);
    const r=shell(e,zoneId,'Offices & records',768,640);
    r.spawn={x:64,y:560};r.exit={...r.spawn};
    r.connections.push(stairs('stairs-down','Downstairs: reception & holding',64,560,'main','stairs-up'));
    r.walls.push({x:480,y:96,w:16,h:256},{x:480,y:432,w:16,h:184});
    area(r,'Investigations',32,96,448,400);
    area(r,'Records archive',496,96,240,520,'interior_floorWood');
    for(const x of [128,272,400])for(const y of [224,384])put(r,'desk',x,y);
    for(const x of [560,672])for(const y of [192,320])put(r,'bookcase',x,y);
    for(const x of [560,624,688])put(r,'files',x,480);
    put(r,'table',288,528);put(r,'chair',400,544);
    return r;
  }
  if(e.landmark?.id!=='hotel')return null;
  const spec=hotelSpec(w.seed,e.id),zone=zoneId??'lobby';
  if(zone==='lobby') {
    const r=shell(e,zone,'Lobby',896,704);
    r.outsideExit=true;
    area(r,'Reception',32,96,448,224);
    area(r,'Lounge',32,352,352,224,'interior_floorWood');
    area(r,'Breakfast room',544,352,320,224,'interior_floorWood');
    area(r,'Stair hall',608,96,256,224);
    // Staff office behind the reception desk, with a proper doorway at its right end.
    r.walls.push({x:32,y:288,w:320,h:16},{x:448,y:288,w:80,h:16},
      {x:528,y:96,w:16,h:192});
    for(const x of [112,176,240,304])put(r,'counter',x,240);
    put(r,'desk',400,192);put(r,'files',112,160);put(r,'files',176,160);
    put(r,'sofa',112,448);put(r,'sofa',272,448);put(r,'table',192,528);
    for(const x of [608,752]){put(r,'table',x,432);put(r,'chair',x,512);}
    put(r,'plant',64,640);put(r,'plant',832,640);
    r.connections.push(stairs('stairs-up','Up to guest floor 1',736,224,'floor:1','stairs-down'));
    return r;
  }
  const floorMatch=/^floor:(\d+)$/.exec(zone);
  if(floorMatch) {
    const floor=Number(floorMatch[1]);
    if(floor<1||floor>spec.floors)throw new Error(`Unknown hotel floor ${floor}`);
    const half=spec.roomsPerFloor/2,W=half*256+96,H=544;
    const r=shell(e,zone,`Guest floor ${floor}`,W,H);
    // Only the corridor is visible; guest rooms are private spaces beyond working doors.
    r.walls.push({x:32,y:96,w:W-64,h:128},{x:32,y:400,w:W-64,h:H-424});
    area(r,`Guest floor ${floor}`,32,224,W-64,176,'interior_floorWood');
    r.spawn={x:80,y:312};r.exit={...r.spawn};
    r.connections.push(stairs('stairs-down',floor===1?'Down to lobby':`Down to floor ${floor-1}`,80,312,
      floor===1?'lobby':`floor:${floor-1}`,floor===1?'stairs-up':'stairs-up'));
    if(floor<spec.floors)r.connections.push(stairs('stairs-up',`Up to floor ${floor+1}`,W-80,312,`floor:${floor+1}`,'stairs-down'));
    for(let side=0;side<2;side++)for(let i=0;i<half;i++) {
      const number=floor*100+side*half+i+1,x=176+i*256,y=side===0?240:384;
      r.connections.push({id:`guest:${number}`,label:`Enter room ${number}`,at:{x,y},target:`room:${number}`,arrival:'room-exit',kind:'door',
        doorRect:{x:x-22,y:side===0?160:400,w:44,h:64}});
    }
    return r;
  }
  const roomMatch=/^room:(\d+)$/.exec(zone);
  if(roomMatch) {
    const number=Number(roomMatch[1]),floor=Math.floor(number/100),index=number%100;
    if(floor<1||floor>spec.floors||index<1||index>spec.roomsPerFloor)throw new Error(`Unknown hotel room ${number}`);
    const r=shell(e,zone,`Room ${number}`,640,544,true);
    area(r,`Room ${number}`,32,96,576,224);
    area(r,'Private bathroom',416,336,192,184,'interior_floorTile');
    r.walls.push({x:400,y:320,w:208,h:16},{x:400,y:336,w:16,h:64},{x:400,y:480,w:16,h:40});
    const bed=rand(w.seed,'hotel',e.id,number,'bed')<.5?'bed':'bedGreen';
    put(r,bed,144,240);
    if(rand(w.seed,'hotel',e.id,number,'double')<.55)put(r,bed,272,240);
    put(r,'wardrobe',544,224);put(r,'desk',128,400);put(r,'chair',256,400);
    put(r,'toilet',544,480);put(r,'bathroomSink',464,480);put(r,'plant',64,496);
    r.connections.push({id:'room-exit',label:`Return to floor ${floor}`,at:{x:320,y:504},target:`floor:${floor}`,arrival:`guest:${number}`,kind:'door',
      doorRect:{x:296,y:520,w:48,h:24}});
    return r;
  }
  throw new Error(`Unknown hotel area ${zone}`);
}
