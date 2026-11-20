"""Build the Night Keeper in metres, with the south promenade facing glTF +Z."""
import sys,json,os
from math import sin,cos,pi,radians
from pathlib import Path
import bpy
from mathutils import Euler,Vector as V
R=Path(__file__).resolve().parent
sys.path.insert(0,str(R))
from sculpture_tools import group,material,mesh,loft,oval,box,beam,tube,plaque,Y0
bpy.ops.wm.read_factory_settings(use_empty=True)
early='--study' in sys.argv
body=group('night_keeper')
figure=group('keeper_figure',body)
lantern=group('lantern_pavilion',body)
lot=group('lot',body)
court=group('lantern_square',body)
clay=material('wall_fired_clay','clay');blue=material('wall_indigo','indigo')
iron=material('detail_cast_iron','iron');gold=material('detail_yellow_ochre','ochre')
skin=material('wall_limestone','sandstone');leaf=material('ground_foliage','moss')
brick=material('wall_brick','brick_red');paving=material('ground_paving','pavement')
wood=material('detail_timber','plank_horizontal')
glass=material('glass_story_panels','night-glass',.28)
letter=material('sign_name','name')
# A rectangular civic square with clipped corners.
def terrace(name,g,x,y,z,w,d,h,clip,m):
    pts=[(-w/2+clip,-d/2),(w/2-clip,-d/2),(w/2,-d/2+clip),(w/2,d/2-clip),(w/2-clip,d/2),(-w/2+clip,d/2),(-w/2,d/2-clip),(-w/2,-d/2+clip)]
    return loft(name,g,[[(x+a,y+b,k) for a,b in pts] for k in (z,z+h)],m)
terrace('City paving',court,0,0,0,62,50,.12,4,paving)
terrace('Sculpture foundation',court,-3,3,.12,32,30,.47,4.5,brick)
terrace('Foundation coping',court,-3,3,.59,32.2,30.2,.3,4.5,skin)
def joint(name,pos,rx,ry,rz,m,n=10):
    x,y,z=pos
    oval(name,figure,[(x,y,z+a*rz,rx*b,ry*b) for a,b in [(-1,.50),(-.65,.89),(.15,1),(.72,.85),(1,.46)]],m,n)
def limb(name,points,radii,m,n=10):
    pts=[V(p) for p in points];rings=[]
    tangents=[(pts[min(i+1,len(pts)-1)]-pts[max(0,i-1)]).normalized() for i in range(len(pts))]
    stable=min([V((1,0,0)),V((0,1,0)),V((0,0,1))],key=lambda axis:max(abs(t.dot(axis)) for t in tangents))
    for i,p in enumerate(pts):
        tangent=(pts[min(i+1,len(pts)-1)]-pts[max(0,i-1)]).normalized()
        ref=V((1,0,0)) if abs(tangent.x)<.88 else V((0,1,0))
        if name in ("Lantern arm","Sleeve over raised knee"):ref=stable
        v=tangent.cross(ref).normalized();u=v.cross(tangent).normalized()
        rx,ry=radii[i]
        rings.append([p+u*rx*cos((j+.5)*2*pi/n)+v*ry*sin((j+.5)*2*pi/n) for j in range(n)])
    return loft(name,figure,rings,m)
# One boot is planted ahead, the other turns up behind the grounded knee.
for x,y in [(-7,-7.3),(1.6,10.3)]:
    if x<0:
        oval('Forward hobnail sole',figure,[(x,y-.9,.89,2.55,4.8),(x,y-.9,1.55,2.61,4.85)],iron,12)
        oval('Forward boot',figure,[(x,y-.9,1.48,2.54,4.75),(x,y-1.15,2.33,2.55,4.45),(x,y-.55,3.14,2.34,3.8),(x,y+.75,4.25,1.94,2.40),(x,y+.9,6.18,2.10,2.13)],iron)
    else:
        limb('Rear boot',[(x,y-1.1,2.75),(x,y+1.6,3.02),(x,y+3.1,4.8),(x,y+3.3,7.0)],[(2.15,1.9),(2.18,2.05),(2.03,1.8),(1.8,1.25)],iron)
limb('Raised trouser leg',[(-2.8,3.5,14),(-5,-1.5,15.3),(-7,-6.2,16.3),(-7.5,-7.1,14.9),(-7.2,-6.9,10),(-7,-6.4,5.3)],[(3.15,3.5),(3.28,3.4),(3,3),(2.78,2.5),(2.61,2.55),(2.15,2.15)],blue)
limb('Kneeling trouser leg',[(-.85,3.7,14.6),(.6,2.2,10),(2,-.8,4),(2.2,.6,3.05),(1.8,5.3,2.91),(1.6,10.4,2.95)],[(3.45,3.3),(3.3,3.1),(2.7,2.95),(2.65,2.16),(2.3,2.05),(2.04,1.84)],blue)
# The hem breaks around the raised thigh and slopes toward the planted knee.
oval('Work coat',figure,[(-2.9,4.1,12,5.6,4),(-2.8,4.3,13.3,5.8,4.2),(-2.3,3.6,17.4,5.2,4),(-1.8,2.4,20.5,5.5,4),(-1.15,1.1,24.7,6.6,4.05),(-1.05,1.05,25.9,5.65,3.57),(-.75,.72,26.65,3.3,2.6)],clay,12)
limb('Sleeve over raised knee',[(-4.85,1.1,24.67),(-6.45,.83,24.57),(-8.5,-.5,23.3),(-10,-3.7,20.7),(-9.45,-5.3,19.6),(-8.66,-6.3,19.08),(-7.75,-7.65,18.35)],[(1.72,1.66),(2.78,2.75),(2.65,2.7),(2.32,2.4),(2.1,2.26),(2.07,2.08),(1.83,1.81)],lambda k,j: blue if k==5 else clay)
limb('Lantern arm',[(2.74,1.0,24.8),(4.52,1.0,24.54),(6.2,.2,23.4),(8.15,-.95,20.6),(9,-3.6,18.1),(10.13,-6.24,15.92),(10.7,-7.5,14.85)],[(1.7,1.7),(2.8,2.85),(2.7,2.78),(2.5,2.55),(2.28,2.25),(2.05,2.05),(1.84,1.80)],lambda k,j: blue if k==5 else clay)
joint('Resting palm',(-7.7,-8.25,17.85),1.8,1.65,1.45,skin)
joint('Carrying palm',(11,-8.35,14.3),1.8,1.55,1.7,skin)
neck=Euler((radians(18),radians(8),radians(30))).to_matrix()
def head(x,y,z):return neck@V((x,y,z))+V((-.7,.25,25.6))
def hoval(name,levels,m,n=12):return oval(name,figure,levels,m,n,head)
hoval('Neck',[(0,0,-.4,2.01,1.98),(0,0,2.95,2.08,1.88)],skin)
hoval('Cut limestone face',[(0,-.5,1.60,1.35,1.37),(0,-.28,2.1,2.02,1.97),(0,-.15,3.23,2.5,2.32),(0,0,4.65,2.66,2.46),(0,.04,6.40,2.55,2.32),(0,.1,7.08,2.04,2)],skin,12)
hoval('Cap band',[(0,.07,6.35,2.79,2.54),(0,.07,7.08,2.80,2.54)],iron)
hoval('Eight-panel cap',[(0,.13,6.89,2.78,2.51),(-.12,.18,7.27,3.20,2.92),(-.25,.38,7.95,2.84,2.69),(-.14,.43,8.41,1.94,1.84),(0,.40,8.58,.47,.55)],blue,8)
hoval('Forward cap bill',[(0,-1.8,6.44,2.62,2.02),(0,-1.8,6.70,2.70,2.10)],blue)
# The lantern is a hollow hexagon with open north and south portals.
lx,ly=11,-9
panelw=56*.0776;panelh=66*.0776
radius=panelw+.40
floor=.89
def lhex(name,levels,m):
    return loft(name,lantern,[[(lx+r*cos(j*pi/3),ly+r*sin(j*pi/3),z) for j in range(6)] for z,r in levels],m)
lhex('Lantern shoe',[(.12,radius+.68),(.34,radius+.68),(.69,radius+.32),(floor,radius+.32)],iron)
for k in range(6):
    a=k*pi/3;x=lx+radius*cos(a);y=ly+radius*sin(a)
    oval('Post shoe',lantern,[(x,y,floor,.48,.48),(x,y,1.65,.46,.46),(x,y,1.88,.34,.34)],gold,8)
    oval('Lantern post',lantern,[(x,y,1.58,.31,.31),(x,y,7,.31,.31)],iron,8)
    b=(k+1)*pi/3;bx=lx+radius*cos(b);by=ly+radius*sin(b)
    beam('Upper lintel',lantern,(x,y,6.69),(bx,by,6.69),(.40,.38),(.40,.38),iron)
    if k not in (1,4):
        dx,dy=(bx-x)/radius,(by-y)/radius
        ax,ay=x+.2*dx,y+.2*dy
        z=1.42
        vs=[(ax,ay,z),(ax+dx*panelw,ay+dy*panelw,z),(ax+dx*panelw,ay+dy*panelw,z+panelh),(ax,ay,z+panelh)]
        plaque('Town pane '+str(k),lantern,vs,glass,([0,2,3,5].index(k)*60,0,56,66),(256,128))
        beam('Pane sill',lantern,(x,y,1.15),(bx,by,1.15),(.35,.40),(.35,.40),iron)
lhex('Rain cap',[(6.91,radius+.5),(7.22,radius+.57),(7.4,radius+.21),(8.64,2.08),(8.76,1.67)],iron)
lhex('Crown shoulder',[(8.64,1.85),(8.92,1.85),(9.07,1.43),(9.72,1.43),(10.04,1.78),(10.34,1.08)],gold)
pts=[(lx+4.4*cos(a*pi/12),ly,7.6+6.23*sin(a*pi/12)) for a in range(13)]
tube('Forged carrying bail',lantern,pts,.32,iron,8)
# Steps come up from the south sidewalk to the open pavilion floor.
for i in range(5):
    box('Pavilion stair',lantern,(lx,ly-5.99+i*.42,.12+(i+1)*.077),(3.95,.42,(i+1)*.154),skin)
door=group('door_main',body);door.location=(lx,ly+Y0-radius*.866-.02,floor)
back=group('door_back',body);back.location=(lx,ly+Y0+radius*.866+.02,floor);back.rotation_euler.z=pi
if not early:
    exec(compile((R/'carving.py').read_text(),str(R/'carving.py'),'exec'))
exec(compile((R/'present.py').read_text(),str(R/'present.py'),'exec'))
