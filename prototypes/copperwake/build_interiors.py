"""Export independent, keyed ship rooms for Timerpg's scene-transition interiors."""
import json
import sys
from pathlib import Path
from math import cos,pi,sin

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch, block, collider, export_glb, marker, save_source
from sculpture_tools import beam, group, material, mesh, oval

bpy.ops.wm.read_factory_settings(use_empty=True)
plank=material("ground_wood","teak")
ivory=material("wall_ivory","white")
oak=material("wall_oak","copper")
carpet=material("ground_carpet","carpet")
dark=material("detail_dark","rubber")
brass=material("detail_brass","brass")
teal=material("detail_teal","teal")
glass=material("glass_room","glazing")
amber=material("glow_bulbs","amber",1)
screens=material("glow_screens","signals",.75)
rooms=[]
info={}


class Room:
    def __init__(self,key,w,d,h,floor=carpet,wall=ivory,windowed=True,front_windows=False):
        self.key,self.w,self.d,self.h=key,w,d,h
        self.root=group(key.replace("-","_"))
        self.root["interior_shell"]=True
        self.structure=group("shell",self.root)
        self.details=group("fittings",self.root)
        self.markers=[]
        self.boxes=[]
        self.stairs=[]
        rooms.append(self)
        self.box("floor_"+key,0,0,0,w,d,.16,floor,True)
        self.box("Ceiling",0,0,h,w,d,.21,wall,parent=self.structure)
        for side in (-1,1):
            x=side*(w/2-.15)
            if windowed:
                self.box("Continuous window sill",x,0,.16,.30,d,.94,wall,True,self.structure)
                self.box("Window ribbon",x,0,1.1,.28,d,1.8,glass,True,self.structure)
                self.box("Upper wall",x,0,2.9,.30,d,h-2.9,wall,True,self.structure)
                for y in range(round(-d/2+2),round(d/2),3):
                    self.box("Window mullion",x,y,1.1,.34,.11,1.81,wall,parent=self.structure)
            else:
                self.box("Side wall",x,0,.16,.30,d,h-.16,wall,True,self.structure)
        for s in (-1,1):
            y=s*(d/2-.16)
            # A real 2.4 m entry and 2.55 m clear head height at either end.
            for side in (-1,1):
                x,span=side*(w+2.4)/4,(w-2.4)/2
                if front_windows and s<0:
                    self.box("Bow-facing window sill",x,y,.16,span,.32,.80,wall,True,self.structure)
                    if key=="mv-observation-lounge":
                        self.boxes.append(collider("Raked observation window","interior",(x,y+.29,2.23),
                                                   (span,.74,2.58)))
                    else:
                        self.box("Bow-facing panorama",x,y,.96,span,.29,1.88,glass,True,self.structure)
                    self.box("Bow window head",x,y,2.84,span,.32,h-2.84,wall,True,self.structure)
                    for j in range(1,5):
                        self.box("Bow mullion",x-span/2+j*span/5,y,.94,.12,.38,1.93,wall,parent=self.structure)
                else:
                    self.box("End wall",x,y,.16,span,.32,h-.16,wall,True,self.structure)
            self.box("Portal lintel",0,y,2.71,2.4,.32,h-2.71,wall,True,self.structure)
            self.markers.append(marker(self.root,"door_front" if s<0 else "door_rear",
                                (0,y-s*.32,.16),(0,0,-s)))
        if h<5:
            for y in (-d*.30,d*.27):
                self.box("Ceiling light trim",0,y,h-.18,min(w*.6,13),.32,.21,brass)
                self.box("Recessed lamp",0,y,h-.192,min(w*.6,13)-.1,.19,.018,amber)

    def box(self,name,x,y,z,w,d,h,mat,solid=False,parent=None):
        parent=parent or self.details
        ob=block(name,parent,(x,y,z),(w,d,h),mat)
        if solid:
            self.boxes.append(collider(name,"interior",(x,y,z+h/2),(w,d,h)))
        return ob

    def guard(self,name,a,b,z):
        x,y=a
        u,v=b
        beam(name,self.details,(x,y,z+1.09),(u,v,z+1.09),(.06,.06),(.06,.06),brass,n=4)
        for s in range(6):
            f=s/5
            self.box("Gallery baluster",x+(u-x)*f,y+(v-y)*f,z,.07,.07,1.08,dark)
        self.boxes.append(collider(name,"rail",((x+u)/2,(y+v)/2,z+.55),
                                   (max(abs(u-x),.13),max(abs(v-y),.13),1.10)))

    def step_run(self,name,x,y,start,end,width=2.50,run=5.9,sign=1,curve=0):
        n=round((end-start)/.175)
        pitch=run/n
        rise=(end-start)/n
        for i in range(n):
            t=(i+.5)/n
            self.box("Stair tread",x+curve*(1-t)**2,y+sign*(i+.5)*pitch,
                     start,width,pitch+.001,(i+1)*rise,oak,True)
        for s in (-1,1):
            pts=[(x+curve*(1-i/n)**2+s*(width/2-.05),y+sign*i*pitch,start+i*rise)
                 for i in range(n+1)]
            for j in range(0,n+1,4):
                u=pts[j]
                self.box("Stair baluster",*u,.075,.075,1.09,brass)
                if j<n:
                    v=pts[min(j+4,n)]
                    beam("Swept stair rail",self.details,(u[0],u[1],u[2]+1.1),
                         (v[0],v[1],v[2]+1.1),(.068,.068),(.068,.068),brass,n=4)
                    self.boxes.append(collider("Stair rail","rail",
                         ((u[0]+v[0])/2,(u[1]+v[1])/2,(u[2]+v[2])/2+.54),
                         (max(abs(v[0]-u[0]),.12),max(abs(v[1]-u[1]),.12),1.1)))
        self.stairs.append({"name":name,"treads":n,
                           "start":marker(self.root,name+"_bottom",(x+curve,y,start)),
                           "end":marker(self.root,name+"_top",(x,y+sign*run,end))})

    def export(self):
        batch(self.structure)
        batch(self.details)
        selection=[self.root,*self.root.children_recursive]
        export_glb(ROOT/"interiors"/f"{self.key}.glb",selection)
        triangles=sum(len(p.vertices)-2 for ob in selection if ob.type=="MESH" for p in ob.data.polygons)
        info[self.key]={"file":f"interiors/{self.key}.glb","floor_node":"floor_"+self.key,
            "doors":self.markers,"stairs":self.stairs,"collision_boxes_gltf_m":self.boxes,
            "dimensions_m":[self.w,self.h,self.d],"triangles":triangles}


def staircase_hall(key,classic=False):
    r=Room(key,19 if classic else 25,23 if classic else 29,7.6 if classic else 11.1,
           plank if classic else carpet,oak if classic else ivory)
    levels=(3.55,) if classic else (3.75,7.34)
    for n,z in enumerate(levels):
        for s in (-1,1):
            x=s*(r.w/2-2.05)
            r.box("Gallery floor",x,0,z,4.1,r.d-.3,.16,plank,True)
            r.guard("Gallery handrail",(s*(r.w/2-4.15),-r.d/2+.42),
                    (s*(r.w/2-4.15),r.d/2-.42),z+.16)
        r.box("Gallery crosswalk",0,8.7 if n%2==0 else -8.7,z,r.w-8.17,3.25,.16,plank,True)
        r.guard("Crosswalk rail",(-r.w/2+4,7.13 if n%2==0 else -7.13),
                (r.w/2-4,7.13 if n%2==0 else -7.13),z+.16)
        for s in (-1,1):
            r.step_run(f"grand_stair_{n}_{s}",s*(r.w/2-5.04),2.15 if n==0 else 7.05,
                       .16 if n==0 else levels[n-1]+.16,z+.16,run=5.13 if n==0 else 13.60,
                       sign=1 if n==0 else -1,curve=-s*2.7 if n==0 else 0)
    r.box("Reception pedestal",0,-6.7,.16,5.1,1.1,1.05,oak,True)
    r.box("Reception counter",0,-6.7,1.20,5.43,1.30,.13,brass)
    for s in (-1,1):
        if not classic:
            r.box("Pale lift enclosure",s*7.1,9.7,.16,2.15,1.5,10.45,glass,True)
            for x in (s*7.1-1.08,s*7.1+1.08):
                r.box("Lift guide",x,9.7,.16,.12,1.62,10.50,brass)
        for y in (-8,-4,0,4,9):
            r.box("Panel seam",s*(r.w/2-.40),y,.16,.21,.18,r.h-.16,oak)
    beam("Chandelier suspension",r.details,(0,0,r.h),(0,0,r.h-1.47),(.09,.09),(.09,.09),brass,n=6)
    for i in range(12):
        angle=2*pi*i/12
        x,y=1.85*cos(angle),1.85*sin(angle)
        beam("Chandelier arm",r.details,(0,0,r.h-1.52),(x,y,r.h-1.68),(.044,.044),(.033,.033),brass,n=5)
        oval("Chandelier bulb",r.details,[(x,y,r.h-1.69,.10,.10),(x,y,r.h-1.43,.075,.075)],amber,n=6)
    return r


def dining(key,classic=False):
    r=Room(key,23,29,6.6 if classic else 7.25,carpet,oak if classic else ivory)
    r.box("Captain table dais",0,9.2,.16,9.1,5.5,.43,plank,True)
    r.box("Captain dais step",0,6.0,.16,8,1.01,.23,plank,True)
    oval("Fixed captain table",r.details,[(0,9.7,.59,.67,.67),(0,9.7,1.36,.67,.67),
         (0,9.7,1.39,2.5,1.15),(0,9.7,1.49,2.5,1.15)],ivory,n=12)
    for s in (-1,1):
        r.box("Lowered ceiling side wing",s*8.1,0,r.h-1.41,6.76,28.52,.23,oak)
        for y in (-9,-3,3,9):
            r.box("Dining roof beam",0,y,r.h-.6,12.5,.44,.60,oak)
            oval("Downlight",r.details,[(s*4.5,y,r.h-.67,.15,.15),(s*4.5,y,r.h-.58,.22,.22)],amber,n=8)
    return r


def theatre():
    r=Room("mv-theatre",21,27,7.95,carpet,dark,False)
    r.box("Apron stage",0,9.05,.16,18.0,6.7,1.1,plank,True)
    r.box("Stage valance",0,11.9,5.93,18.5,.60,1.22,oak)
    for s in (-1,1):
        for i in range(9):
            r.box("Folded theatre curtain",s*(6.4+i*.30),11.9+(i%2)*.2,1.24,.38,.54,5.65,oak)
        for j in range(6):
            r.box("Raked seating terrace",s*5.55,-.10-j*1.27,.16,8.3,1.273,(j+1)*.24,carpet,True)
        r.box("Tiered balcony",s*6.98,-5.55,4.5,6.3,10.3,.25,plank,True)
        r.guard("Box balcony rail",(s*3.8,-10.45),(s*3.8,-.56),4.75)
        r.step_run(f"theatre_balcony_{s}",s*8.7,-12,.16,4.75,width=1.6,run=8.9)
        r.step_run(f"theatre_aisle_{s}",s*1.01,1.0,.16,1.6,width=1.2,run=8.7,sign=-1)
    for x in range(-8,9,4):
        r.box("Stage spot",x,5.65,6.4,.33,.35,.36,amber)
    return r


def bridge():
    r=Room("mv-bridge",28.0,9.9,3.15,plank,ivory,front_windows=True)
    for x in (-7.5,-3.75,0,3.75,7.5):
        y=-2.9+abs(x)*.055
        r.box("Wheelhouse console",x,y,.16,3.6,.92,.98,teal,True)
        r.box("Console cap",x,y,1.14,3.58,1.05,.15,dark)
        for dx in (-.77,.43):
            r.box("Radar display",x+dx,y-.23,1.30,.45,.33,.037,screens)
    oval("Helm column",r.details,[(0,-.9,.16,.21,.21),(0,-.9,1.41,.14,.14)],brass,n=10)
    for i in range(12):
        a,b=i*pi/6,(i+1)*pi/6
        p=(.34*cos(a),-.79,1.36+.34*sin(a))
        end=(.34*cos(b),-.79,1.36+.34*sin(b))
        beam("Wooden helm rim",r.details,p,end,(.045,.045),(.045,.045),oak,n=4)
        if i%3==0:
            beam("Helm spokes",r.details,(0,-.79,1.36),p,(.033,.033),(.033,.033),brass,n=4)
    for s in (-1,1):
        r.box("Wing repeater",s*11.6,.30,.16,.67,.50,1.09,teal,True)
        r.box("Wing repeater dial",s*11.6,.3,1.25,.32,.29,.015,amber)
    r.markers.append(marker(r.root,"captain_spawn",(0,0,.16)))
    return r


def lounge(key,classic=False):
    r=Room(key,16.5,16.8,4.10 if classic else 4.55,carpet,oak if classic else ivory,front_windows=not classic)
    r.box("Fixed bar plinth",6.43,2,.16,1.6,9,1.05,oak,True)
    r.box("Bar rail",6.34,2,1.21,1.98,9.25,.16,brass)
    r.box("Bar shelf",7.54,2,1.58,.82,8.5,.16,ivory)
    for i in range(3):
        y=-.95+i*2.48
        r.box("Observation tier",0,y,.16,10.0,2.42,(i+1)*.20,plank,True)
        r.box("Fixed seat base",-2.73,y,.16+(i+1)*.2,4.3,1,.45,teal,True)
        r.box("Fixed seat back",-2.73,y+.38,.61+(i+1)*.2,4.3,.19,.54,teal)
    # A separate sloped observation sash sits inside the bow-facing wall.
    for x in (-6.6,-4.4,-2.2,2.2,4.4,6.6):
        beam("Raked window jamb",r.details,(x,-8.03,.91),(x,-7.43,3.75),
             (.075,.075),(.075,.075),brass,n=4)
    for a,b in ((-8.09,-1.2),(1.2,8.09)):
        mesh("Sloped glass sash",r.details,
             [(a,-8.02,.93),(b,-8.02,.93),(b,-7.45,3.76),(a,-7.45,3.76)],[(0,1,2,3)],glass)
    for y in (-6.0,-1.0,5.1):
        r.box("Bar and lounge lights",0,y,r.h-.033,9.4,.09,.041,amber)
    return r


def berth(suite=False):
    r=Room("mv-suite" if suite else "mv-stateroom",8.2 if suite else 4.9,8.9 if suite else 6.1,
           3.2 if suite else 2.96,carpet)
    for x in (-1.1,1.1) if suite else (-.7,):
        r.box("Built-in bunk",x,-.95,.16,1.20,2.15,.46,oak,True)
        r.box("Bunk cap",x,-.95,.62,1.18,2.1,.19,teal)
    r.box("Fixed dressing counter",r.w/2-.55,.95,.16,.66,2.12,.85,oak,True)
    if suite:
        r.box("Suite balcony floor",0,5.83,.16,7.7,3.0,.12,plank,True)
        r.guard("Suite balcony rail",(-3.71,7.20),(3.71,7.20),.28)
        for s in (-1,1):
            r.guard("Balcony return",(s*3.70,4.48),(s*3.70,7.20),.28)
    return r


staircase_hall("mv-grand-atrium")
dining("mv-main-dining")
theatre()
bridge()
lounge("mv-observation-lounge")
berth()
berth(True)
staircase_hall("ss-grand-staircase",True)
dining("ss-first-class-dining",True)
lounge("ss-smoking-room",True)

for i,r in enumerate(rooms):
    r.export()
for i,r in enumerate(rooms):
    r.root.location.x=(i%5)*37
    r.root.location.y=(i//5)*42
(ROOT/"interiors"/"room-manifest.json").write_text(json.dumps(info,indent=2)+"\n")
save_source(ROOT/"interiors"/"copperwake-rooms.blend")
