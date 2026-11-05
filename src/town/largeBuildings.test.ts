import { describe,expect,it } from 'vitest';
import { runTimeline,worldAt } from '../sim';
import { hotelSpec } from './largeBuildings';
import { planInterior,interiorBlocked,type Entrance,type RoomPlan } from './interiors';
import { layoutTown } from './layout';
import { civicDoor } from './collision';

const base=worldAt(runTimeline({seed:'alpha',endYear:2025}),2025);
const landmark=layoutTown(base).plan.landmarks[0]!;
const hotel:Entrance={id:'civic:hotel',title:'Grand Hotel',door:civicDoor(landmark),landmark:{...landmark,id:'hotel',name:'Grand Hotel'}};
const police:Entrance={...hotel,id:'civic:police',title:'Police Station',landmark:{...landmark,id:'police',name:'Police Station'}};
function building(seed:string,e=hotel):Map<string,RoomPlan>{
 const w={...base,seed},plans=new Map<string,RoomPlan>(),queue=[e===hotel?'lobby':'main'];
 for(let i=0;i<queue.length;i++){
  const id=queue[i]!;if(plans.has(id))continue;
  const p=planInterior(w,e,id);plans.set(id,p);
  for(const c of p.connections)if(!plans.has(c.target))queue.push(c.target);
 }
 return plans;
}
function reachable(r:RoomPlan):Set<string>{
 const step=8,start={x:Math.round(r.spawn.x/step)*step,y:Math.round(r.spawn.y/step)*step};
 const queue=[start],seen=new Set([`${start.x},${start.y}`]);
 for(let i=0;i<queue.length;i++)for(const [dx,dy] of [[step,0],[-step,0],[0,step],[0,-step]]){
  const x=queue[i]!.x+dx!,y=queue[i]!.y+dy!,k=`${x},${y}`;
  if(!seen.has(k)&&!interiorBlocked(r,x,y)){seen.add(k);queue.push({x,y});}
 }
 return seen;
}

describe('multi-floor hotels',()=>{
 it('has a dedicated lobby, multiple guest floors and separate private rooms',()=>{
  for(const seed of ['alpha','demo','zebra','quartz']){
   const spec=hotelSpec(seed,hotel.id),plans=building(seed);
   expect(spec.floors).toBeGreaterThanOrEqual(2);
   expect(plans.size).toBe(1+spec.floors+spec.floors*spec.roomsPerFloor);
   const lobby=plans.get('lobby')!;
   expect(lobby.outsideExit).toBe(true);
   expect(lobby.areas.map(a=>a.name)).toEqual(expect.arrayContaining(['Reception','Lounge','Breakfast room','Stair hall']));
   for(const [id,r] of plans){
    if(id==='lobby'||id.startsWith('floor:'))expect(r.furniture.some(f=>f.key==='interior_bed'||f.key==='interior_bedGreen')).toBe(false);
    if(id.startsWith('room:')){
     expect(r.outsideExit).toBe(false);
     expect(r.areas.some(a=>a.name==='Private bathroom')).toBe(true);
     expect(r.furniture.some(f=>f.key==='interior_toilet')).toBe(true);
     expect(r.furniture.some(f=>f.key==='interior_bathroomSink')).toBe(true);
    }
   }
  }
 });
 it('has reciprocal stairs and room doors returning to the correct corridor',()=>{
  for(const seed of ['alpha','demo','zebra','quartz'])for(const [id,r] of building(seed)){
   const plans=building(seed);
   for(const c of r.connections){
    const target=plans.get(c.target)!;expect(target,`${id} -> ${c.target}`).toBeDefined();
    const arrival=target.connections.find(a=>a.id===c.arrival)!;
    expect(arrival,`${id} -> ${c.target} arrival`).toBeDefined();expect(arrival.target).toBe(id);
   }
  }
 });
 it('keeps every stair, room doorway and lobby exit reachable without crossing a wall',()=>{
  for(const seed of ['alpha','demo'])for(const [id,r] of building(seed)){
   const seen=reachable(r);
   for(const c of r.connections){
    expect(interiorBlocked(r,c.at.x,c.at.y),`${id}: ${c.id}`).toBe(false);
    expect(seen.has(`${c.at.x},${c.at.y}`),`${id}: ${c.id} unreachable`).toBe(true);
   }
   if(r.outsideExit)expect(seen.has(`${r.exit.x},${r.exit.y}`)).toBe(true);
  }
 });
 it('keeps solid furnishings clear of walls and other furnishings on every floor',()=>{
  const hit=(a:{x:number;y:number;w:number;h:number},b:typeof a)=>a.x<b.x+b.w&&b.x<a.x+a.w&&a.y<b.y+b.h&&b.y<a.y+a.h;
  for(const r of [...building('alpha').values(),...building('demo',police).values()]){
   const solid=r.furniture.filter(f=>f.solid);
   for(let i=0;i<solid.length;i++){
    for(const wall of r.walls)expect(hit(solid[i]!,wall),`${r.zoneId}: ${solid[i]!.key} in wall`).toBe(false);
    for(let j=i+1;j<solid.length;j++)expect(hit(solid[i]!,solid[j]!),`${r.zoneId}: furnishings overlap`).toBe(false);
   }
  }
 });
 it('rebuilds the same rooms across years and visit order',()=>{
  const plans=building('alpha');
  for(const [id,p] of [...plans].reverse()){
   expect(planInterior(base,hotel,id)).toEqual(p);
   expect(planInterior({...base,year:1970},hotel,id)).toEqual(p);
  }
  expect(()=>planInterior(base,hotel,'room:999')).toThrow('Unknown hotel room');
 });
});

describe('police station floors',()=>{
 it('connects reception and holding cells to a separate investigations and records floor',()=>{
  const plans=building('alpha',police);expect(plans.size).toBe(2);
  expect(plans.get('main')!.outsideExit).toBe(true);
  const upper=plans.get('police:upper')!;expect(upper.outsideExit).toBe(false);
  expect(upper.areas.map(a=>a.name)).toContain('Records archive');
  expect(upper.furniture.some(f=>f.key==='interior_hospitalBed')).toBe(false);
  for(const r of plans.values()){
   const seen=reachable(r);
   for(const c of r.connections){expect(seen.has(`${c.at.x},${c.at.y}`)).toBe(true);
    expect(plans.get(c.target)!.connections.find(a=>a.id===c.arrival)!.target).toBe(r.zoneId);}
  }
 });
});
