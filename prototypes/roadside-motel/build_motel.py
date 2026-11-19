"""Sundown Motel — original twelve-room L-shaped 1950s–60s motor court."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=2
NAME=next((a.split('=',1)[1] for a in sys.argv if a.startswith('--name=')),'SUNDOWN').upper().strip()
if NAME.endswith(' MOTEL'):NAME=NAME[:-6].rstrip()
from sign_art import write_name
write_name(R/'textures/sign-name.png',NAME)
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];MOVING=[];ROOMS=[];DOORS=[];PARKING=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('roadside_motel');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);POOL=empty('pool',LOT);SIGN=empty('roadside_sign',STRUCT);parent=STRUCT

def mat(n,f,alpha=False,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8
 t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:m.node_tree.links.new(t.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.12
 return m
wall=mat('wall_stucco','stucco.png');brick=mat('wall_brick_base','brick.png');roof=mat('roof_built_up','roof.png');teal=mat('trim_teal','teal.png');utility=mat('detail_utility','utility.png');glass=mat('glass_motel','glass.png',True);glow=mat('glow_motel','glow.png',emit=True);name_mat=mat('sign_name','sign-name.png',emit=True);vacancy=mat('glow_vacancy','no-vacancy.png' if '--occupied' in sys.argv else 'vacancy.png',emit=True);asphalt=mat('ground_asphalt','asphalt.png');paving=mat('ground_paving','paving.png');water=mat('water_pool','water.png',True)
water.node_tree.nodes.get('Principled BSDF').inputs['Roughness'].default_value=.35
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def B(n,p,s,m=wall,patch=None):return box(n,(p[0],p[1],p[2]+F),s,m,patch=patch)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,(p[0],p[1],p[2]+F),w,h,key,m,side)
def col(n,x,y,w,d,top,group='structure',attachment='structure',gate=None,bottom=0):
 c={'source':n,'center':[x*U,y*U,(top+bottom)/2*U],'size':[w*U,d*U,(top-bottom)*U],'group':group,'attachment':attachment}
 if gate:c['gate_node']=gate
 COLL.append(c)
def beam(n,a,b,r=.6,m=utility,patch='metal',offset=True):
 add=F if offset else 0;a=Vector((a[0],a[1],a[2]+add))*U;b=Vector((b[0],b[1],b[2]+add))*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized();v=t.cross(e);verts=[]
 for p in (a,b):
  for i in range(8):verts.append(tuple(p+U*r*(e*math.cos(i*math.tau/8)+v*math.sin(i*math.tau/8))))
 return mesh(n,verts,[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)],m,patch)
def shell(n,pts,m=roof,depth=2,offset=True):
 add=F if offset else 0;k=len(pts);v=[(x*U,y*U,(z+add-d)*U) for d in (0,depth) for x,y,z in pts]
 ob=mesh(n,v,[tuple(range(k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
 mesh(n+' cream soffit',v,[tuple(range(2*k-1,k-1,-1))],wall)
 return ob

def enter(n,x,y,w,h,key='guest_door',side='front',holder=STRUCT):
 global parent
 parent=empty('panel_'+n,holder);MOVING.append(parent)
 P('Closed '+n,(x,y,h/2),w,h,key,glass if key=='office_door' else utility,side);parent=holder
 yaw={'front':0,'back':math.pi,'left':-math.pi/2,'right':math.pi/2}[side];dx,dy={'front':(0,-2),'back':(0,2),'left':(-2,0),'right':(2,0)}[side]
 o=door(n,(x+dx,y+dy,F),yaw);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side]})
 col('Closed door',x,y,w if side in ('front','back') else 2,2 if side in ('front','back') else w,F+h,attachment='panel_'+n,gate='panel_'+n)

def piece(n,lo,hi,plane,z0,z1,side='front'):
 if hi<=lo or z1<=z0:return
 x_axis=side in ('front','back');mid=(lo+hi)/2
 if z0<10:
  cut=min(10,z1);B(n+' brick', (mid,plane,(z0+cut)/2) if x_axis else (plane,mid,(z0+cut)/2),(hi-lo,2,cut-z0) if x_axis else (2,hi-lo,cut-z0),brick)
  z0=cut
 if z1>z0:B(n,(mid,plane,(z0+z1)/2) if x_axis else (plane,mid,(z0+z1)/2),(hi-lo,2,z1-z0) if x_axis else (2,hi-lo,z1-z0))

def wall_run(n,lo,hi,plane,openings,side='front',height=40):
 global parent
 cursor=lo
 for a,b,z0,z1,key,kind,label in sorted(openings):
  piece(n,cursor,a,plane,0,height,side);piece(n,a,b,plane,0,z0,side);piece(n,a,b,plane,z1,height,side)
  x,y=((a+b)/2,plane) if side in ('front','back') else (plane,(a+b)/2)
  if kind=='glass':P(label,(x,y,(z0+z1)/2),b-a,z1-z0,key,glass,side)
  elif kind=='door':enter(label,x,y,b-a,z1,key,side)
  cursor=b
 piece(n,cursor,hi,plane,0,height,side)

def floor_rect(n,x,y,w,d):
 # Carpet atlas region is 64 art pixels wide; split long rooms rather than stretch or bleed.
 box(n+' slab',(x,y,F/2),(w,d,F),brick)
 for xs in range(0,int(w),40):
  ww=min(40,w-xs)
  for start in range(0,int(d),40):
   dd=min(40,d-start);x0=x-w/2+xs;y0=y-d/2+start
   mesh(n+' carpet',[(a*U,b*U,(F+.05)*U) for a,b in [(x0,y0),(x0+ww,y0),(x0+ww,y0+dd),(x0,y0+dd)]],[(0,1,2,3)],utility,'carpet')
 B(n+' ceiling',(x,y,39),(w,d,2))

# Eight rear rooms and four side rooms; an actual service elbow avoids inaccessible corner doors.
for i in range(8):
 n=i+1;x0=40+i*48;x=x0+24;floor_rect('Room %02d'%n,x,296,48,80)
 wall_run('Room front',x0,x0+48,256,[(x0+4,x0+16,0,28,'guest_door','door','door_room_%02d'%n),(x0+22,x0+44,12,28,'room_window','glass','room_%02d_window'%n)])
 P('Room number',(x0+10,254.9,34),14,10,'number_%02d'%n,glow)
 B('Period AC housing',(x0+33,252,7),(16,6,6),utility,'cream');P('AC grille',(x0+33,248.95,7),16,6,'ac_grille',utility)
 for xx in ([x0] if i else [40]):piece('Room partition',256,336,xx,0,40,'right')
 ROOMS.append({'id':'room_%02d'%n,'number':n,'center':[x*U,F*U,-296*U],'size_m':[48*U,80*U],'door':'door_room_%02d'%n,'window':'room_%02d_window'%n,'bathroom_zone_blender_art':[x0+24,312,x0+48,336]})
for i in range(4):
 n=9+i;y0=64+i*48;y=y0+24;floor_rect('Room %02d'%n,0,y,80,48)
 wall_run('Room front',y0,y0+48,40,[(y0+4,y0+16,0,28,'guest_door','door','door_room_%02d'%n),(y0+22,y0+44,12,28,'room_window','glass','room_%02d_window'%n)],'right')
 P('Room number',(41.1,y0+10,34),14,10,'number_%02d'%n,glow,'right')
 B('Period AC housing',(44,y0+33,7),(6,16,6),utility,'cream');P('AC grille',(47.05,y0+33,7),16,6,'ac_grille',utility,'right')
 if i:piece('Room partition',-40,40,y0,0,40)
 ROOMS.append({'id':'room_%02d'%n,'number':n,'center':[0,F*U,-y*U],'size_m':[80*U,48*U],'door':'door_room_%02d'%n,'window':'room_%02d_window'%n,'bathroom_zone_blender_art':[-40,y0+24,-16,y0+48]})
# Exterior backs have high frosted windows, service access and downpipes.
back_windows=[(40+i*48+26,40+i*48+38,26,34,'bath_window','glass','Rear frosted window %02d'%(i+1)) for i in range(8)]
wall_run('Rear stucco wall',-40,424,336,[(-6,6,0,28,'guest_door','door','door_linen')]+back_windows,'back')
west_windows=[(64+i*48+26,64+i*48+38,26,34,'bath_window','glass','Side frosted window %02d'%(9+i)) for i in range(4)]
wall_run('West outer wall',0,336,-40,[(42,54,0,28,'guest_door','door','door_office_service')]+west_windows,'left')
piece('East outer end',256,336,424,0,40,'right');piece('Office party wall',-40,40,64,0,40);piece('Linen party wall',-40,40,256,0,40)
floor_rect('Linen utility room',0,296,80,80);P('Linen sign',(0,337.1,34),32,10,'linen',glow,'back')
# Office: picture windows, fixed check-in counter and a folded entrance marquee.
floor_rect('Office',0,32,80,64)
wall_run('Office front',-40,40,0,[(-38,-10,8,32,'office_window','glass','Office picture window'),(-7,7,0,30,'office_door','door','door_main'),(10,38,8,32,'office_window','glass','Office picture window')])
wall_run('Office east wall',0,64,40,[(6,30,8,32,'office_side','glass','Office side window')],'right')
B('Fixed reception counter',(0,48,6),(56,10,12),teal);B('Counter cap',(0,48,12.5),(58,12,1),utility,'cream')
B('Office sign case',(0,-1.5,36),(40,3,12),teal);P('Office sign',(0,-3.05,36),38,10,'office',glow)
shell('Office membrane roof',[(-44,-4,44),(44,-4,44),(44,64,44),(-44,64,44)],depth=4)
for side in (-1,1):shell('Folded office entrance canopy',[(side*48,-20,48),(0,-26,36),(0,0,42),(side*44,0,42)])
# A single L-shaped roof, with no overlapping roofs at the elbow.
roof_outline=[(-44,64),(68,64),(68,232),(428,232),(428,340),(-44,340)]
shell('L-shaped room roof',[(x,y,42) for x,y in roof_outline])
# Coral fascia is divided into physical <=48-art-pixel panels to avoid atlas bleed.
for (x0,y0),(x1,y1) in zip(roof_outline,roof_outline[1:]+roof_outline[:1]):
 length=math.hypot(x1-x0,y1-y0);count=math.ceil(length/48)
 for i in range(count):
  a=i/count;b=(i+1)/count;xa=x0+(x1-x0)*a;ya=y0+(y1-y0)*a;xb=x0+(x1-x0)*b;yb=y0+(y1-y0)*b
  B('Coral fascia panel',((xa+xb)/2,(ya+yb)/2,40.5),(abs(xb-xa)+2 if y0==y1 else 2,abs(yb-ya)+2 if x0==x1 else 2,5),utility,'coral')
# Covered galleries and their slim posts.
parent=LOT
box('Side covered walkway',(52,148,F/2),(24,168,F),paving);box('Rear-wing covered walkway',(232,244,F/2),(384,24,F),paving);box('Office ice alcove apron',(60,32,F/2),(40,64,F),paving)
parent=STRUCT
columns=[(64,y) for y in (64,112,160,208,240)]+[(x,240) for x in (88,136,184,232,280,328,376,424)]
for x,y in columns:
 box('Covered-walk column',(x,y,(F+40)/2),(2,2,F+40),teal);col('Gallery column',x,y,2,2,F+40)
# Room/office fixtures are lights only; interior furniture is supplied by the game.
for room in ROOMS:
 x,z,y=room['center'];x/=U;y=-y/U
 P('Room ceiling lamp',(x,y,37.9),12,4,'lamp',glow,'down');light('Room warm light',(x,y,F+36),70,12)
for x,y in [(0,24),(0,296)]:P('Ceiling lamp',(x,y,37.9),12,4,'lamp',glow,'down');light('Office/service warm light',(x,y,F+36),110,14)
for x,y in [(64,88),(64,184),(112,240),(256,240),(400,240)]:P('Gallery downlight',(x,y,39.9),12,4,'lamp',glow,'down');light('Gallery warm light',(x,y,F+38),55,12)
# Courtyard pavement has a real opening for the sunken pool.
parent=LOT
for x0,x1,y0,y1 in [(-128,200,-48,384),(296,480,-48,384),(200,296,-48,48),(200,296,104,384)]:box('Asphalt court',((x0+x1)/2,(y0+y1)/2,-.5),(x1-x0,y1-y0,1),asphalt)
# Twelve spaces, room-facing and kept out of the pool and circulation lanes. No cars are baked in.
for i in range(8):box('Rear parking stripe',(192+i*32,200,.06),(1,60,.12),utility,patch='cream')
for i in range(7):
 x=208+i*32;box('Rear wheel stop',(x,228,1),(24,3,2),utility,patch='cream');PARKING.append({'id':'rear_%02d'%(i+1),'bounds_blender_art':[x-16,168,x+16,232]})
for i in range(4):
 y=80+i*44
 for yy in (y-18,y+18):box('Side parking stripe',(100,yy,.06),(60,1,.12),utility,patch='cream')
 box('Side wheel stop',(72,y,1),(3,24,2),utility,patch='cream');PARKING.append({'id':'side_%02d'%(i+1),'bounds_blender_art':[68,y-18,132,y+18]})
for x in (80,112):box('Check-in parking stripe',(x,16,.06),(1,60,.12),utility,patch='cream')
box('Check-in wheel stop',(96,44,1),(24,3,2),utility,patch='cream');PARKING.append({'id':'check_in','bounds_blender_art':[80,-16,112,48]})
box('Rear service apron',(192,344,.05),(496,16,.1),paving);box('West service walk',(-48,168,.05),(16,336,.1),paving)
# Covered ice alcove leaves a clear path past the machine.
parent=STRUCT
shell('Ice alcove canopy',[(40,32,36),(80,32,36),(80,60,36),(40,60,36)])
for y in (34,58):
 box('Ice alcove column',(78,y,(F+34)/2),(1.5,1.5,F+34),teal);col('Ice alcove column',78,y,1.5,1.5,F+34)
P('Ice alcove ceiling light',(60,46,33.9),12,4,'lamp',glow,'down');light('Ice alcove warm light',(60,46,F+32),65,12)
# Ice machine, independently addressable and boxed to its full height.
parent=lot_prop('ice_machine')
B('Ice cabinet',(48,46,13),(14,20,26),utility,'cream');P('Ice dispenser front',(55.1,46,13),20,26,'ice_machine',utility,'right');B('Ice top cap',(48,46,26.5),(15,21,1),teal)
col('Ice machine',48,46,15,21,F+27,'lot','ice_machine');parent=LOT
# Two small rear bins, on the actual service apron rather than floating at room-floor height.
for i,x in enumerate((-24,16),1):
 parent=lot_prop('service_bin_%02d'%i);box('Service bin',(x,344,5),(8,8,10),teal);box('Bin lid',(x,344,10.5),(9,9,1),utility,patch='metal');col('Service bin',x,344,9,9,11,'lot',parent.name);parent=LOT
# Pool coping/deck, tiled sloping basin and a separate water surface.
parent=POOL
for x0,x1,y0,y1 in [(184,200,32,120),(296,312,32,120),(200,296,32,48),(200,296,104,120)]:box('Pool deck',((x0+x1)/2,(y0+y1)/2,.5),(x1-x0,y1-y0,1),paving)
for x0,x1,y0,y1 in [(196,200,44,108),(296,300,44,108),(200,296,44,48),(200,296,104,108)]:box('Pool coping',((x0+x1)/2,(y0+y1)/2,1),(x1-x0,y1-y0,2),paving)
for xa in (200,248):
 mesh('Sloping tiled pool floor',[(xa*U,48*U,-14*U),((xa+48)*U,48*U,-14*U),((xa+48)*U,104*U,-24*U),(xa*U,104*U,-24*U)],[(0,1,2,3)],utility,'pool_tile')
 for y,bottom in [(48,-14),(104,-24)]:mesh('Tiled pool end',[(xa*U,y*U,bottom*U),((xa+48)*U,y*U,bottom*U),((xa+48)*U,y*U,2*U),(xa*U,y*U,2*U)],[(0,1,2,3) if y==104 else (3,2,1,0)],utility,'pool_tile')
for x in (200,296):mesh('Tiled pool side',[(x*U,48*U,-14*U),(x*U,104*U,-24*U),(x*U,104*U,2*U),(x*U,48*U,2*U)],[(0,1,2,3) if x==200 else (3,2,1,0)],utility,'pool_tile')
mesh('Pool water',[(200*U,48*U,-2*U),(296*U,48*U,-2*U),(296*U,104*U,-2*U),(200*U,104*U,-2*U)],[(0,1,2,3)],water)
# Fence segments are short enough for the metal atlas region, with separate closed gate.
def fence_run(x0,y0,x1,y1):
 length=math.hypot(x1-x0,y1-y0);steps=math.ceil(length/32)
 for i in range(steps+1):
  t=i/steps;x=x0+(x1-x0)*t;y=y0+(y1-y0)*t;beam('Pool fence post',(x,y,0),(x,y,16),.45,offset=False)
 for i in range(steps):
  a=i/steps;b=(i+1)/steps
  for z in (3,15):beam('Pool fence rail',(x0+(x1-x0)*a,y0+(y1-y0)*a,z),(x0+(x1-x0)*b,y0+(y1-y0)*b,z),.35,offset=False)
  for j in range(1,8):
   t=(i+j/8)/steps;box('Square pool picket',(x0+(x1-x0)*t,y0+(y1-y0)*t,9),(.36,.36,12),utility,patch='metal')
 col('Pool fence',(x0+x1)/2,(y0+y1)/2,max(1,abs(x1-x0)),max(1,abs(y1-y0)),17,'pool','pool')
for a,b in [((176,24),(320,24)),((320,24),(320,128)),((320,128),(176,128)),((176,24),(176,52)),((176,68),(176,128))]:fence_run(*a,*b)
parent=empty('pool_gate',POOL);MOVING.append(parent)
for y in (52,68):beam('Gate edge',(176,y,0),(176,y,16),.45,offset=False)
for z in (3,15):beam('Gate rail',(176,52,z),(176,68,z),.35,offset=False)
for y in range(54,68,2):box('Square gate picket',(176,y,9),(.36,.36,12),utility,patch='metal')
col('Closed pool gate',176,60,1,16,17,'pool','pool_gate','pool_gate');parent=POOL
# Pool plaque at the gate, outside the walking clearance.
panel('Pool plaque',(175.4,96,11),26,10,'pool',glow,'left')
# Curved ladder handrails and submerged rungs, kept as a separate prop.
parent=empty('pool_ladder',POOL);PROPS.append(parent)
for x in (240,248):
 pts=[(x,42,0),(x,42,10),(x,44,12),(x,48,12),(x,50,9),(x,50,-12)]
 for a,b in zip(pts,pts[1:]):beam('Pool ladder rail',a,b,.55,offset=False)
for z in (-10,-5,0):beam('Pool ladder rung',(240,50,z),(248,50,z),.45,offset=False)
col('Pool ladder',244,46,10,10,13,'pool','pool_ladder',bottom=-13);parent=POOL
# Two original outdoor loungers; interior rooms remain unfurnished.
for i,x in enumerate((220,272),1):
 parent=empty('pool_lounger_%02d'%i,POOL);PROPS.append(parent)
 for yy in (35,41):
  beam('Lounger frame',(x-13,yy,3),(x+13,yy,3),.45,offset=False)
  for xx in (x-10,x+10):beam('Lounger leg',(xx,yy,0),(xx,yy,3),.45,offset=False)
 box('Lounger webbed seat',(x+3,38,4),(20,7,1),teal)
 mesh('Lounger angled back',[((x-13)*U,34.5*U,11*U),((x-7)*U,34.5*U,4.5*U),((x-7)*U,41.5*U,4.5*U),((x-13)*U,41.5*U,11*U)],[(0,1,2,3)],teal)
 col('Pool lounger',x,38,28,9,12,'pool',parent.name);parent=POOL
# Big double-sided roadside neon: exact mutable panel names requested by the owner.
parent=SIGN
for x in (-90,-54):
 box('Sign support',(x,-8,56),(3,4,112),teal);col('Neon sign post',x,-8,3,4,112,attachment='roadside_sign')
for x in (-93,-51):box('Name cabinet',(x,-8,96),(42,6,32),utility,patch='coral')
for x in (-89,-55):box('Vacancy cabinet',(x,-8,70),(34,5,16),utility,patch='cream')
for side,y,suffix in [('front',-11.1,''),('back',-4.9,'_back')]:panel('sign_name'+suffix,(-72,y,96),80,28,'name',name_mat,side)
for side,y,suffix in [('front',-10.55,''),('back',-5.45,'_back')]:panel('glow_vacancy_panel'+suffix,(-72,y,70),64,12,'vacancy',vacancy,side)
beam('Starburst stem',(-72,-8,112),(-72,-8,124),.7,teal,None,False)
for j in range(8):
 a=j*math.tau/8;beam('Neon starburst',(-72+3*math.cos(a),-8,124+3*math.sin(a)),(-72+12*math.cos(a),-8,124+12*math.sin(a)),.45,glow,'neon',False)
light('Neon sign wash',(-72,-20,92),130,18,(1,.62,.48));light('Neon reverse wash',(-72,4,92),110,18,(.55,.9,1))
# Full-height obstructions. Architectural room walls and AC housings use their actual meshes.
for x,y in [(425.5,320),(-41.5,312),(-41.5,112)]:
 parent=STRUCT;beam('Downpipe',(x,y,0),(x,y,40),.5,utility,'metal');
for room in ROOMS:
 x,z,y=room['center'];x/=U;y=-y/U
 cylinder('Roof vent',(x,y,F+44),.7,4,utility,'metal',8);cylinder('Vent cap',(x,y,F+46.5),1.4,1,utility,'metal',8)
# Collision helper is hidden and complements (rather than replacing) the architectural wall surfaces.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];o=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(o+j for j in q) for q in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,teal);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for group in [STRUCT,LOT,POOL,SIGN]+PROPS+MOVING:
 groups={}
 for o in list(group.children):
  if o.type=='MESH' and not (o.name.startswith('room_') and o.name.endswith('_window')) and o.name not in ('sign_name','sign_name_back','glow_vacancy_panel','glow_vacancy_panel_back'):groups.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in groups.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=group.name+'__'+n
model=[ROOT]+list(ROOT.children_recursive);tris=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=collision);materials=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=12000 and len(materials)<=12,(tris,len(materials))
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'roadside-motel.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':NAME.title()+' Motel','era':{'design_years':[1950,1969],'opening':'Simulation history chooses actual opening/closure; no business or guests are spawned.'},'units':'metres','axes':'glTF Y up, main office entrance +Z','origin':'Ground level at the middle of the office front edge','building_m':[464*U,336*U],'lot_m':{'width':608*U,'depth':432*U,'bounds_blender_art':[-128,-48,480,384]},'heights_m':{'room_roof':(F+43)*U,'sign_starburst':136.5*U,'office_canopy':(F+48)*U},'room_count':12,'rooms':ROOMS,'doors':DOORS,'door_panels':[o.name for o in MOVING if o.name.startswith('panel_')],'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'collision':{'node':'collision','render':False,'strategy':'Use the walls: architectural walls, closed door meshes and wall-mounted AC housings supply structural collision. The companion boxes add full-height outdoor props, posts, gates and barriers.','use_the_walls':True,'boxes_blender_z_up':COLL,'runtime':'Existing importer can infer drawn-part walk cuts; moving a door or pool gate also requires updating its matching gate_node box. Height/swimming behavior is not implemented.'},'lot_node':'lot','pool':{'node':'pool','water_node_material':'water_pool','gate_node':'pool_gate','water_surface_y_gltf':-2*U,'terrain_cutout_blender_art':[200,48,296,104],'terrain_cutout_gltf_xz_m':[200*U,-104*U,296*U,-48*U],'water_m':[96*U,56*U],'depth_m':[12*U,22*U],'requirement':'Cut/mask town ground inside the basin before placement. The GLB cannot carve TownGround automatically. Keep the gate closed until pool traversal is supported. Dropping lot drops all pool geometry; hiding pool alone needs replacement ground.'},'sign_name':{'nodes':['sign_name','sign_name_back'],'material':'sign_name','texture':'textures/sign-name.png','rect_art':[0,0,80,28],'writer':'sign_art.write_name','preview_name':NAME,'text_rule':'Explicit supported short name; never auto-truncate or stretch.'},'vacancy':{'nodes':['glow_vacancy_panel','glow_vacancy_panel_back'],'material':'glow_vacancy','texture':'textures/no-vacancy.png' if '--occupied' in sys.argv else 'textures/vacancy.png','available_texture':'textures/vacancy.png','full_texture':'textures/no-vacancy.png','rect_art':[0,0,64,12],'writer':'sign_art.write_vacancy','occupied_preview':'--occupied' in sys.argv,'binding':'Preview artwork only. Runtime derives NO from actual occupancy/availability; no business-state binding is implemented.'},'ice_machine':{'node':'ice_machine','behavior':'Static prop; no dispensing logic implemented.'},'parking':{'spaces':12,'north_spaces':7,'side_spaces':4,'check_in_spaces':1,'bays':PARKING,'cars':'None baked into the model; vehicle spawning and traffic belong to the game.'},'interior':'Twelve separate clean room shells with carpet, high rear windows and bathroom placement zones; office with fixed reception counter, plus linen/service room. The game supplies furnishings and bathroom partitions/fixtures.','runtime_handoff':['Room numbers and door anchors are fixed architectural identifiers, not bookings or occupancy.','Individual room/window lighting needs a runtime extension; preview night lighting is staged.','Keep closed door/pool-gate visuals and gate boxes in sync.','Pool water rendering, terrain cutting and swimming need game integration; no transparency support is assumed.','sign_name and glow_vacancy are isolated panels with exact texture regions and tested writers.'],'swatches_used':['wall_stucco_red','brick_red','roof_membrane_grey','pavement','asphalt_worn'],'licence':'LimeZu swatches: Timerpg game use only, credit required. Original geometry and supplemental pixel artwork.','renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'triangles':tris,'materials':len(materials),'collision_boxes':len(COLL),'rooms':len(ROOMS)}))
if '--export-only' in sys.argv:sys.exit(0)
# Presentation terrain has the same explicit opening as the pool. It is not exported.
parent=None;bgmat=bpy.data.materials.new('Presentation terrain');bgmat.diffuse_color=(.24,.29,.27,1)
for x0,x1,y0,y1 in [(-1200,200,-1200,1200),(296,1200,-1200,1200),(200,296,-1200,48),(200,296,104,1200)]:
 bpy.ops.mesh.primitive_plane_add(size=1,location=((x0+x1)/2*U,(y0+y1)/2*U,-.02));o=bpy.context.object;o.scale=((x1-x0)*U,(y1-y0)*U,1);o.data.materials.append(bgmat)
# Low-power pool illumination is presentation lighting, not a claim of runtime water effects.
for x,y in [(202,76),(294,76)]:light('Pool blue fill',(x,y,-1),25,6,(.4,.85,1))
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=24;scene.cycles.use_denoising=False;scene.cycles.max_bounces=5;scene.cycles.transparent_max_bounces=8;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG';scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Motel sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.45,-.45,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
scene.use_nodes=True;ns=scene.node_tree.nodes;ns.clear();rl=ns.new('CompositorNodeRLayers');g=ns.new('CompositorNodeGlare');g.glare_type='FOG_GLOW';g.threshold=1.8;g.size=6;out=ns.new('CompositorNodeComposite');scene.node_tree.links.new(rl.outputs['Image'],g.inputs['Image']);scene.node_tree.links.new(g.outputs['Image'],out.inputs['Image'])
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
def shot(n,p,t,scale=67,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=26 if n=='06-room-shell' else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler()
 bg.inputs[0].default_value=(*((.12,.18,.28) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.4 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.65,1) if night else (1,.9,.75)
 for d,power in LIGHTS:d.energy=power*(1 if night else .1)
 for m in (glow,name_mat,vacancy):m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .12
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-court-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'roadside-motel.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_x=800;scene.render.resolution_y=600
shot('06-room-shell',(5.0,25.0,1.8),(5.0,20.0,1.5),66,True,True)
shot('01-court-day',(57,-42,34),(12,11,2),66)
shot('02-court-night',(56,-38,25),(12,11,2),66,True)
shot('03-rear',(47,47,28),(12,12,2),66)
shot('04-above',(12,-16,70),(12,12,0),66)
shot('05-pool-and-office',(29,-8,1.7),(2,5,3),66,True,True)
