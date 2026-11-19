"""Original Starling Lanes geometry. Blender Z up, front -Y, metres."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('starling_lanes');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);parent=STRUCT;MOVING=[];EQUIPMENT=empty('bowling_equipment',ROOT);EQUIP=[]
def gate(n):
 o=empty(n,STRUCT);MOVING.append(o);return o
def mat(n,f,alpha=False,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8
 t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:m.node_tree.links.new(t.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.12
 return m
wall=mat('wall_cream','cream.png');teal=mat('trim_teal','teal.png');brick=mat('wall_brick','brick_red.png');block=mat('wall_block','block_courses.png');roof=mat('roof_vault_blue','roof_tin_blue.png');asphalt=mat('ground_asphalt','asphalt_worn.png');paving=mat('ground_paving','pavement.png');wood=mat('floor_lanes','lane-wood.png');utility=mat('detail_utility','utility.png');glass=mat('glass_lobby','glass.png',True);glow=mat('glow_signage','signs.png',emit=True);mosaic=mat('wall_mosaic','mosaic.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def wb(n,p,s,m=block):return box(n,p,s,m,True)
def prism(n,points,y0,y1,m):
 k=len(points);v=[(x*U,y*U,z*U) for y in (y0,y1) for x,z in points]
 return mesh(n,v,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
def beam(n,a,b,r,m=teal):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));u=t.cross(ref).normalized();v=t.cross(u);vs=[]
 for p in (a,b):
  for j in range(8):vs.append(tuple(p+U*r*(u*math.cos(j*math.tau/8)+v*math.sin(j*math.tau/8))))
 return mesh(n,vs,[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(j,(j+1)%8,(j+1)%8+8,j+8) for j in range(8)],m)
box('Continuous slab',(0,200,-1),(288,400,2),paving)
# Lobby curtain wall has real openings and clean internal space.
for x in (-80,80):
 wb('Brick knee wall',(x,1,3),(120,2,6),brick);panel('Lobby glazing',(x,-.05,27),120,42,'front',glass)
for x in (-142,-18,18,142):wb('Front pier',(x,1,24),(4,2,48),mosaic)
wb('Front header',(0,1,49),(288,2,2),teal)
parent=gate('door_panel_main')
panel('Double entrance',(0,-.08,18),32,36,'entry',glass)
parent=STRUCT
panel('Entry transom',(0,-.08,42),32,12,'transom',glass)
for x,side in [(-143,'left'),(143,'right')]:
 for y in (6,66):wb('Lobby corner',(x,y,25),(2,12,50),brick)
 wb('Lobby side sill',(x,36,4),(2,48,8),brick);wb('Lobby side lintel',(x,36,49),(2,48,2),teal);panel('Lobby side window',(x+(-1.05 if x<0 else 1.05),36,28),48,40,'side',glass,side)
box('Lobby roof',(0,36,51),(288,72,2),wall)
door('door_main',(0,-2,0))
# Hall walls; rear and side exits are actual holes.
for x,side in [(-143,'left'),(143,'right')]:
 for lo,hi in [(72,120),(216,232),(248,264),(360,400)]:wb('Hall solid pier',(x,(lo+hi)/2,26),(2,hi-lo,52))
 for y in (168,312):
  wb('Hall window base',(x,y,16),(2,96,32));wb('Hall window lintel',(x,y,48),(2,96,8));panel('Hall high glazing',(x+(-1.05 if x<0 else 1.05),y,38),96,12,'high_side',glass,side)
 for y in (72,120,216,264,360,399):box('Exterior buttress',(x+(-2 if x<0 else 2),y,26),(3,4,52),teal)
 if x>0:wb('Right middle pier',(x,240,26),(2,16,52))
 else:
  wb('Side exit lintel',(x,240,42),(2,16,20));parent=gate('door_panel_side');box('Side exit door',(x,240,16),(1,16,32),teal);parent=STRUCT
 panel('BOWL side sign',(x+(-2.1 if x<0 else 2.1),166,21),48,12,'bowl',glow,side)
 for lo,hi in [(72,232),(248,400)]:box('Brick side plinth',(x+(-1.15 if x<0 else 1.15),(lo+hi)/2,5),(1,hi-lo,10),brick)
door('door_side',(-146,240,0),-math.pi/2)
for x,w in [(-24,240),(128,32)]:wb('Back wall',(x,399,26),(w,2,52))
wb('Rear exit lintel',(104,399,42),(16,2,20));parent=gate('door_panel_back');box('Rear exit door',(104,399,16),(16,1,32),teal);parent=STRUCT;door('door_back',(104,402,0),math.pi)
xs=[-144,-108,-72,-36,0,36,72,108,144];zs=[52+36*math.sqrt(max(0,1-(x/144)**2)) for x in xs]
# Closed eight-facet vault; fixed physical UV distance per face.
for i in range(8):
 x0,x1=xs[i:i+2];z0,z1=zs[i:i+2];ob=prism('Vault metal facet',[(x0,z0),(x1,z1),(x1,z1+2),(x0,z0+2)],72,400,roof)
 # Coherent metal ribs across all roof facets, without stretching texels.
 W,H=bpy.data.images.get('roof_tin_blue.png').size
 for face in ob.data.polygons:
  if face.normal.z>.1 and len(face.vertices)==4:
   for li in face.loop_indices:
    co=ob.data.vertices[ob.data.loops[li].vertex_index].co
    dist=sum(math.hypot(xs[k+1]-xs[k],zs[k+1]-zs[k]) for k in range(i))+((co.x/U-x0)*(x1-x0)+(co.z/U-z0)*(z1-z0))/math.hypot(x1-x0,z1-z0)
    ob.data.uv_layers.active.data[li].uv=(co.y/T/W,dist*U/T/H)
 beam('Vault front rim',(x0,71,z0),(x1,71,z1),1.5,teal);beam('Vault back rim',(x0,401,z0),(x1,401,z1),1.5,teal)
prism('Rear arch',list(zip(xs,zs)),398,400,block)
def z_at(x):
 for i in range(8):
  if xs[i]<=x<=xs[i+1]:return zs[i]+(zs[i+1]-zs[i])*(x-xs[i])/(xs[i+1]-xs[i])
prism('Clerestory left',[(-144,48),(-96,48),(-96,z_at(-96)),(-108,zs[1]),(-144,52)],71,73,wall)
prism('Clerestory right',[(96,48),(144,48),(144,52),(108,zs[7]),(96,z_at(96))],71,73,wall)
box('Clerestory sill',(0,72,53),(192,2,10),mosaic)
prism('Clerestory crown',[(-96,70),(96,70),(96,z_at(96)),(72,zs[6]),(36,zs[5]),(0,88),(-36,zs[3]),(-72,zs[2]),(-96,z_at(-96))],71,73,wall)
panel('High glazed ribbon',(0,70.95,64),192,12,'clerestory',glass)
# Closed folded plate canopy: raised outer tips, low central valley.
for sign in (-1,1):
 pts=[(sign*160,-36,68),(0,-44,44),(0,8,52),(sign*152,8,60)];v=[(x*U,y*U,(z-d)*U) for d in (0,2) for x,y,z in pts]
 mesh('Folded wing canopy',v,[(0,1,2),(0,2,3),(7,6,5),(7,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],wall)
 for a,b in [(pts[0],pts[1]),(pts[0],pts[3])]:beam('Canopy teal leading edge',a,b,1.4)
 beam('Canopy outer column',(sign*152,8,0),(sign*152,8,59),1.7)
 COLL.append({'center':[sign*152*U,8*U,29.5*U],'size':[4*U,4*U,59*U],'group':'structure','source':'Canopy column','attachment':'structure'})
for x in (-40,40):box('Sign support',(x,8,57),(2,2,14),teal)
box('Illuminated sign case',(0,6,64),(98,4,14),teal);panel('STARLING LANES',(0,3.95,64),96,12,'title',glow)
# Raised approach and maple beds; real lowered, faceted gutters on both sides.
LANE_Z=1.5
for x,w in [(-48,64),(16,64),(64,32)]:
 mesh('Approach entry ramp',[(a*U,b*U,z*U) for a,b,z in [(x-w/2,72,0),(x+w/2,72,0),(x+w/2,80,LANE_Z),(x-w/2,80,LANE_Z)]],[(0,1,2,3)],wood,'wood')
 mesh('Maple approach',[(a*U,b*U,LANE_Z*U) for a,b in [(x-w/2,80),(x+w/2,80),(x+w/2,132),(x-w/2,132)]],[(0,1,2,3)],wood,'wood')
def floor_patch(n,x,y,w,d,key):
 return mesh(n,[(a*U,b*U,LANE_Z*U+.002) for a,b in [(x-w/2,y),(x+w/2,y),(x+w/2,y+d),(x-w/2,y+d)]],[(0,1,2,3)],wood,key)
def small_pin(x,y,lane,slot):
 global parent
 parent=empty('lane_%02d_pin_%02d'%(lane,slot),EQUIPMENT);EQUIP.append(parent);parent['gameplay_prop']='bowling_pin';parent['lane']=lane;parent['rack_slot']=slot;parent['pivot_art']=[x,y,LANE_Z]
 profile=[(0,.35),(1.6,.8),(2.8,.28),(3.2,.28),(4,.43),(4.8,.25)]
 for (z0,r0),(z1,r1) in zip(profile,profile[1:]):
  v=[((x+rr*math.cos(j*math.tau/8))*U,(y+rr*math.sin(j*math.tau/8))*U,(LANE_Z+zz)*U) for zz,rr in [(z0,r0),(z1,r1)] for j in range(8)]
  mesh('Bowling pin body',v,[(j,(j+1)%8,(j+1)%8+8,j+8) for j in range(8)],utility,'pin_red' if z0==2.8 else 'paint')
 v=[((x+.25*math.cos(j*math.tau/8))*U,(y+.25*math.sin(j*math.tau/8))*U,(LANE_Z+4.8)*U) for j in range(8)]+[(x*U,y*U,(LANE_Z+5)*U)]
 mesh('Pin head',v,[(j,(j+1)%8,8) for j in range(8)],utility,'paint')
 v=[((x+.35*math.cos(j*math.tau/8))*U,(y+.35*math.sin(j*math.tau/8))*U,LANE_Z*U) for j in range(8)]
 mesh('Pin base',v,[tuple(range(7,-1,-1))],utility,'paint')
 parent=STRUCT
for i,x in enumerate(range(-70,71,20),1):
 # Uniform maple bed with distinct aiming and pin-deck inlay regions.
 for y,d,key in [(132,48,'lane'),(180,32,'aim'),(212,64,'lane'),(276,64,'lane'),(340,24,'lane'),(364,20,'deck')]:floor_patch('Lane %d maple bed'%i,x,y,14,d,key)
 floor_patch('Approach alignment dots',x,80,14,16,'dots')
 mesh('Black foul line',[(a*U,b*U,(LANE_Z+.08)*U) for a,b in [(x-7,131.5),(x+7,131.5),(x+7,132.5),(x-7,132.5)]],[(0,1,2,3)],utility,'rubber')
 for side in (-1,1):
  xx=x+side*8.5
  prof=[(-1.5,1.5),(-1.1,.55),(-.5,.2),(.5,.2),(1.1,.55),(1.5,1.5)]
  # Keep every face within the 64-art-pixel metal region: no atlas bleed.
  for start in (132,195,258,321):
   v=[((xx+dx)*U,y*U,z*U) for y in (start,start+63) for dx,z in prof]
   mesh('Recessed gutter',v,[(j,j+1,j+7,j+6) for j in range(5)],utility,'metal')
  box('Gutter edge cap',(x+side*10,258,1.6),(.6,252,.3),teal)
 box('Dark pin pit',(x,390,.2),(20,12,.4),utility,patch='rubber')
 box('Pinsetter masking wall',(x,386,13.5),(20,4,24),teal);panel('Lane number',(x,383.95,19.5),8,10,'lane'+str(i),glow)
 slot=0
 for row in range(4):
  for j in range(row+1):slot+=1;small_pin(x+(2*j-row)*2,368+row*3.5,i,slot)
# Optional, independently removable ball-return assemblies and loose ball props.
def ball(n,x,y,z,m):
 v=[]
 for zz,rr in [(-1.4,0),(-1,.98),(0,1.4),(1,.98),(1.4,0)]:
  if rr==0:v.append((x*U,y*U,(z+zz)*U))
  else:
   for j in range(8):v.append(((x+rr*math.cos(j*math.tau/8))*U,(y+rr*math.sin(j*math.tau/8))*U,(z+zz)*U))
 f=[(0,1+(j+1)%8,1+j) for j in range(8)]
 for a,b in [(1,9),(9,17)]:f += [(a+j,a+(j+1)%8,b+(j+1)%8,b+j) for j in range(8)]
 f += [(17+j,17+(j+1)%8,25) for j in range(8)]
 return mesh(n,v,f,m,'pin_red' if m==utility else None)
for i,x in enumerate((-60,-20,20,60),1):
 parent=empty('ball_return_%02d'%i,EQUIPMENT);EQUIP.append(parent);parent['gameplay_prop']='ball_return';parent['pivot_art']=[x,119,LANE_Z]
 box('Return pedestal',(x,119,4),(5,16,5),teal)
 box('Return hood',(x,124,7),(5,7,7),wall)
 box('Return opening',(x,120.4,7),(3,.15,3),utility,patch='rubber')
 for dx in (-1.9,1.9):box('Ball rack rail',(x+dx,113,6),(1,10,1),utility,patch='metal')
 ball('Red bowling ball',x,111,7.8,utility);ball('Teal bowling ball',x,115,7.8,teal)
 parent=STRUCT
box('Fixed shoe counter',(-96,39,8),(64,14,16),mosaic,True);box('Counter top',(-96,39,16.5),(66,16,1),teal)
for y in (110,200,290,365):
 for x in (-54,54):
  for xx in (x-6,x+6):box('Pendant suspension',(xx,y,(76+z_at(xx))/2),(1,1,z_at(xx)-76),teal)
  panel('Hall ceiling fixture',(x,y,76),16,4,'lamp',glow,'down');light('Lane area',(x,y,74),450,32)
for x in (-90,90):
 panel('Lobby ceiling light',(x,34,49.8),16,4,'lamp',glow,'down');light('Lobby light',(x,34,48),250,24)
for x in (-80,80):
 panel('Canopy opal light',(x,-17,54),16,4,'lamp',glow,'down');light('Canopy light',(x,-17,52),180,18)
# Finished rear plant, downpipes, and brick service enclosure.
for x in (-126,126):cylinder('Rear downpipe',(x,402,26),.7,52,utility,n=8)
box('Rear mechanical cabinet',(-128,414,12),(24,20,24),utility,patch='metal');box('Plant dark grille',(-128,403.9,12),(20,.2,16),utility,patch='rubber')
cylinder('Vault ventilation stack',(0,380,95),1.4,10,utility,n=8);cylinder('Vent rain cap',(0,380,100.5),3,1,utility,n=8)
parent=LOT
box('Asphalt parcel',(0,160,-3),(448,544,2),asphalt)
box('Entrance paving',(0,-12,-.5),(328,24,1),paving)
box('Side walk',(150,212,-.5),(12,424,1),paving)
box('Rear service apron',(0,416,-.5),(300,32,1),paving)
for y in range(110,351,40):box('Parking bay marking',(185,y,-1.92),(60,2,.1),utility,patch='paint')
for x in (-216,216):box('Parcel curb',(x,160,-1),(4,544,2),paving)
# Giant twelve-sided pin sculpture, red neck bands and a solid plinth.
parent=lot_prop('giant_pin')
box('Pin brick plinth',(-176,-28,8),(32,32,16),brick,True);box('Pin plinth cap',(-176,-28,17),(34,34,2),wall)
cylinder('Pin pedestal',(-176,-28,21),8,6,teal,n=12)
prof=[(0,8),(3,10),(18,12),(30,11),(40,7),(48,4),(50,3.8),(52,3.8),(54,4),(56,4.4),(58,5),(61,6.5),(66,6.8),(72,6.2),(77,4.5)]
for j,((z0,r0),(z1,r1)) in enumerate(zip(prof,prof[1:])):
 v=[((-176+r*math.cos(i*math.tau/12))*U,(-28+r*math.sin(i*math.tau/12))*U,(24+z)*U) for z,r in [(z0,r0),(z1,r1)] for i in range(12)]
 mesh('Pin ring',v,[(i,(i+1)%12,(i+1)%12+12,i+12) for i in range(12)],utility,'pin_red' if z0 in (50,54) else 'paint')
v=[((-176+4.5*math.cos(i*math.tau/12))*U,(-28+4.5*math.sin(i*math.tau/12))*U,101*U) for i in range(12)]+[(-176*U,-28*U,104*U)]
mesh('Pin crown',v,[(i,(i+1)%12,12) for i in range(12)],utility,'paint')
light('Pin sculpture presentation fill',(-176,-70,60),150,20).rotation_euler=(math.pi/2,0,0)
parent=lot_prop('service_yard')
for x in (-104,-40):box('Yard brick side',(x,416,12),(2,32,24),brick,True)
box('Yard rear wall',(-72,431,12),(66,2,24),brick,True)
for x in (-88,-65):
 box('Waste bin',(x,414,8),(12,12,16),teal,True);box('Bin lid',(x,414,16.5),(14,14,1),utility,patch='metal')
# Consolidated traversal boxes include glass and separately gated closed doors.
COLL=[]
def collider(n,p,s,attachment='structure',group='structure',gate_node=None):
 c={'source':n,'center':[a*U for a in p],'size':[a*U for a in s],'attachment':attachment,'group':group}
 if gate_node:c['gate_node']=gate_node
 COLL.append(c)
for x in (-80,80):collider('Front wall and glass',(x,1,25),(128,2,50))
collider('Entry header',(0,1,43),(32,2,14))
for x in (-143,143):collider('Lobby side and glass',(x,36,25),(2,72,50))
for y,d in [(152,160),(324,152)]:collider('Left hall wall and glass',(-143,y,26),(2,d,52))
collider('Side exit lintel',(-143,240,42),(2,16,20));collider('Right hall wall',(143,236,26),(2,328,52))
for x,w in [(-24,240),(128,32)]:collider('Back wall',(x,399,26),(w,2,52))
collider('Rear lintel',(104,399,42),(16,2,20))
for n,p,s in [('main',(0,0,18),(32,1,36)),('side',(-143,240,16),(1,16,32)),('back',(104,399,16),(16,1,32))]:collider('Closed door',p,s,'door_panel_'+n,gate_node='door_panel_'+n)
collider('Rear mechanical cabinet',(-128,414,12),(24,20,24))
collider('Slab',(0,200,-1),(288,400,2));collider('Lobby ceiling',(0,36,51),(288,72,2));collider('Fixed counter',(-96,39,8),(64,14,16));collider('Pinsetter mask',(0,386,13.5),(160,4,24))
collider('Raised approach',(0,106,.75),(160,52,1.5))
for x in range(-70,71,20):collider('Raised lane bed',(x,258,.75),(14,252,1.5))
for i,x in enumerate((-60,-20,20,60),1):collider('Ball return',(x,119,5),(5,18,10),'ball_return_%02d'%i,'equipment')
for x in (-152,152):collider('Canopy column',(x,8,29.5),(4,4,59))
collider('Pin plinth',(-176,-28,9),(34,34,18),'giant_pin','lot')
for x in (-104,-40):collider('Yard side',(x,416,12),(2,32,24),'service_yard','lot')
collider('Yard rear',(-72,431,12),(66,2,24),'service_yard','lot')
for x in (-88,-65):collider('Waste bin',(x,414,8),(14,14,17),'service_yard','lot')
parent=ROOT;v=[];f=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];o=len(v);v.extend([(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]);f.extend([tuple(o+j for j in face) for face in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]])
collision=mesh('collision',v,f,teal);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for container in [STRUCT,LOT]+PROPS+MOVING+EQUIP:
 groups={}
 for o in list(container.children):
  if o.type=='MESH':groups.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in groups.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=container.name+'__'+n
# Local prop geometry pivots at the pin's foot / return base for runtime transforms.
for node in EQUIP:
 pivot=Vector(node['pivot_art'])*U
 for child in node.children:
  if child.type=='MESH':
   for vertex in child.data.vertices:vertex.co-=pivot
 node.location=pivot
 del node['pivot_art']
model=[ROOT]+list(ROOT.children_recursive);triangles=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=collision)
materials=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert len(materials)<=12 and triangles<=12000
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'starling-lanes.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':'Starling Lanes','design_years':[1960,1969],'suggested_built':1964,'units':'metres','axes':'glTF Y up, front +Z','origin':'Ground level, middle of building front edge','building_m':[288*U,400*U],'heights_m':{'hall_apex':90*U,'lobby_roof':52*U,'pin_sculpture':104*U},'lot_m':[448*U,544*U],'triangles_visible':triangles,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'collision':{'node':'collision','render':False,'boxes_blender_z_up':COLL},'doors':['door_main','door_back','door_side'],'door_panels':[o.name for o in MOVING],'lot_node':'lot','optional_lot_props':[p.name for p in PROPS],'interior':'Maple lane beds, aiming arrows, approach dots, foul lines, recessed gutters, ten-pin spots and numbered pinsetter masks. Optional bowling_equipment group holds 80 separate pins and four returns with balls.', 'bowling_dimensions_m':{'bed_width':14*U,'foul_line_to_headpin':236*U,'pin_height':5*U,'raised_deck':1.5*U},'equipment_node':'bowling_equipment','equipment_props':[o.name for o in EQUIP],'runtime_handoff':['Hide collision node from rendering; use metadata boxes, excluding lot attachments when hidden.','Closed door_panel_* groups have gate_node collision entries. Hide or animate the matching panel and disable its gated box together only when an interaction unlocks the door. Re-enable both on closure.','Static bowling props are visual/gameplay handoff pieces; scoring, ball rolling, pin dynamics and pinsetter animation remain unimplemented. Hide bowling_equipment when the game supplies its own props; remove collision group equipment with it. Lane pin nodes carry lane and rack_slot extras. Floor controller must support the 0.1164 m raised approach and lane beds.','Landmark placement needs a suitably sized parcel. Opening, business ownership, rebranding, closure and demolition require simulation facts; age alone never removes it.','Rear plant and optional pin sculpture do not determine historical events.'],'swatches_used':['brick_red','block_courses','roof_tin_blue','asphalt_worn','pavement'],'licence':'LimeZu swatches: Timerpg game use only, credit required. Original mesh and supplemental pixel artwork.','renders':'Blender desktop previews; not Filament or S25 captures'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
if '--export-only' in sys.argv:sys.exit(0)
parent=None
bgmat=bpy.data.materials.new('presentation_ground');bgmat.diffuse_color=(.24,.29,.29,1)
bpy.ops.mesh.primitive_plane_add(size=250,location=(0,12,-.38));bpy.context.object.data.materials.append(bgmat)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=24;scene.cycles.use_denoising=False;scene.cycles.max_bounces=6;scene.cycles.transparent_max_bounces=6
scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG';scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
world=bpy.data.worlds.new('Starling sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background')
sd=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sd);bpy.context.collection.objects.link(so);so.rotation_euler=(.42,-.5,-.5);sd.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
scene.use_nodes=True;ns=scene.node_tree.nodes;ns.clear();rl=ns.new('CompositorNodeRLayers');gl=ns.new('CompositorNodeGlare');gl.glare_type='FOG_GLOW';gl.threshold=2;gl.size=6;out=ns.new('CompositorNodeComposite');scene.node_tree.links.new(rl.outputs['Image'],gl.inputs['Image']);scene.node_tree.links.new(gl.outputs['Image'],out.inputs['Image'])
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
def shot(n,p,t,scale=54,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=25 if n.startswith('06') else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler()
 bg.inputs[0].default_value=(*((.12,.18,.28) if night else (.64,.71,.75)),1);bg.inputs[1].default_value=.35 if night else .65;sd.energy=.35 if night else 2;sd.color=(.5,.65,1) if night else (1,.88,.72)
 for d,power in LIGHTS:d.energy=power*(1 if night else .12)
 glow.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .12
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-day-front':bpy.ops.wm.save_as_mainfile(filepath=str(R/'starling-lanes.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if '--draft' in sys.argv:scene.cycles.samples=12;scene.render.resolution_x=1000;scene.render.resolution_y=750
if '--draft' not in sys.argv:
 shot('06-interior',(0,5.5,2.7),(0,26,.1),54,True,True)
 shot('07-lane-detail',(-1.55,23,1.05),(-.8,29,.42),54,True,True)
shot('01-day-front',(-40,-39,28),(0,11,2),54)
if '--draft' not in sys.argv:
 shot('02-blue-hour',(-40,-39,23),(0,11,2),54,True)
 shot('03-rear',(40,61,28),(0,14,2),54)
 shot('04-above',(-12,-20,65),(0,12,0),66)
 shot('05-person-height',(-19,-24,1.7),(-1,2,3.1),54,True,True)
print(json.dumps({'triangles':triangles,'materials':len(materials),'collisions':len(COLL)}))
