import fs from 'node:fs';
import { describe,expect,it } from 'vitest';
import { runTimeline,worldAt } from '../sim';
import { accessibleDoor, buildingBody,buildingDoor,civicBody,civicDoor,feetHit,moveSafely,nearestFree } from './collision';
import { INTERIOR_ASSETS } from './interiorAssets';
import { interiorBlocked,planInterior,type Entrance } from './interiors';
import { layoutTown } from './layout';

const world=worldAt(runTimeline({seed:'alpha',endYear:2025}),2025);
const layout=layoutTown(world);
const entrances:Entrance[]=[...[...layout.lots,...layout.vacantLots].map(lot=>({id:lot.slot,title:lot.label,door:buildingDoor(lot),lot})),
 ...layout.landmarks.map(landmark=>({id:`civic:${landmark.id}`,title:landmark.name,door:civicDoor(landmark),landmark}))];

describe('solid buildings and movement',()=>{
 it('blocks the whole building body from every direction while keeping the entrance outside',()=>{
  for(const e of entrances){
   const r=e.lot?buildingBody(e.lot):civicBody(e.landmark!);
   for(const [x,y] of [[r.x+2,r.y+r.h/2],[r.x+r.w-2,r.y+r.h/2],[r.x+r.w/2,r.y+2],[r.x+r.w/2,r.y+r.h-2]])expect(feetHit(x!,y!,r)).toBe(true);
   expect(feetHit(e.door.x,e.door.y,r)).toBe(false);
   expect(r.w).toBeGreaterThan(0);expect(r.h).toBeGreaterThan(0);
  }
 });
 it('cannot tunnel through a thin wall on a long frame and slides along its edge',()=>{
  const wall={x:64,y:-100,w:8,h:300};
  const next=moveSafely({x:0,y:0},500,100,(x,y)=>feetHit(x,y,wall));
  expect(next.x).toBeLessThanOrEqual(57);expect(next.y).toBeCloseTo(100);
  expect(feetHit(next.x,next.y,wall)).toBe(false);
 });
 it('finds an accessible side entrance when neighboring shops block the front',()=>{
  const body={x:0,y:0,w:224,h:416},neighbor={x:0,y:416,w:224,h:416};
  const preferred={x:176,y:432};
  const blocked=(x:number,y:number)=>feetHit(x,y,body)||feetHit(x,y,neighbor);
  expect(blocked(preferred.x,preferred.y)).toBe(true);
  const door=accessibleDoor(body,preferred,blocked)!;
  expect(door).not.toBeNull();expect(blocked(door.x,door.y)).toBe(false);
 });
 it('finds a safe position when time travel creates a building around the player',()=>{
  const r={x:0,y:0,w:300,h:300};
  const safe=nearestFree({x:150,y:150},(x,y)=>feetHit(x,y,r))!;
  expect(safe).not.toBeNull();expect(feetHit(safe.x,safe.y,r)).toBe(false);
 });
});

describe('seeded enterable interiors',()=>{
 it('uses actual embedded sprites at their real dimensions',()=>{
  const manifest=JSON.parse(fs.readFileSync('public/assets/manifest.json','utf8'));
  for(const a of Object.values(INTERIOR_ASSETS)){
   const entry=manifest.interior.find((v:{key:string})=>v.key===a.key);
   expect(entry,a.key).toBeDefined();expect([entry.w,entry.h]).toEqual([a.w,a.h]);
   expect(fs.existsSync(`public/${entry.path}`)).toBe(true);
  }
 });
 it('gives every standing building a deterministic room, a clear spawn, and a reachable exit',()=>{
  for(const e of entrances){
   const r=planInterior(world,e);expect(planInterior(world,e)).toEqual(r);
   expect(interiorBlocked(r,r.spawn.x,r.spawn.y),e.title).toBe(false);
   expect(interiorBlocked(r,r.exit.x,r.exit.y),e.title).toBe(false);
   const end=moveSafely(r.spawn,r.exit.x-r.spawn.x,r.exit.y-r.spawn.y,(x,y)=>interiorBlocked(r,x,y));
   expect(end).toEqual(r.exit);
   for(const f of r.furniture){
    expect(f.x,e.title).toBeGreaterThanOrEqual(32);expect(f.y,e.title).toBeGreaterThanOrEqual(96);
    expect(f.x+f.w,e.title).toBeLessThanOrEqual(r.width-32);expect(f.y+f.h,e.title).toBeLessThanOrEqual(r.height-24);
   }
  }
 });
 it('keeps every room area reachable through its doorway',()=>{
  for(const e of entrances.filter(e=>e.lot?.kind==='house')){
   const r=planInterior(world,e),step=8;
   const start={x:Math.round(r.spawn.x/step)*step,y:Math.round(r.spawn.y/step)*step};
   const queue=[start],seen=new Set([`${start.x},${start.y}`]);
   for(let i=0;i<queue.length;i++){
    const p=queue[i]!;
    for(const [dx,dy] of [[step,0],[-step,0],[0,step],[0,-step]]){
     const x=p.x+dx!,y=p.y+dy!,k=`${x},${y}`;
     if(!seen.has(k)&&!interiorBlocked(r,x,y)){seen.add(k);queue.push({x,y});}
    }
   }
   for(const a of r.areas)expect(queue.some(p=>p.x>a.rect.x+8&&p.x<a.rect.x+a.rect.w-8&&p.y>a.rect.y+8&&p.y<a.rect.y+a.rect.h-8),`${e.title}: ${a.name}`).toBe(true);
  }
 });
 it('keeps room divisions unchanged when the occupant or wealth changes',()=>{
  const e=entrances.find(e=>e.lot?.kind==='house')!;
  const a=planInterior(world,e);
  const altered=structuredClone(world);altered.year=1950;for(const p of Object.values(altered.people))p.wealth+=10000;
  expect(planInterior(altered,e)).toEqual(a);
 });
});
