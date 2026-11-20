"""Hearthside Feed & Grain: original timber elevator, warehouse and twin grain bins."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];MOVING=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('feed_mill');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);TOWER=empty('elevator',STRUCT);LADDER=empty('elevator_ladder',STRUCT);BINS=[empty('grain_bin_01',STRUCT),empty('grain_bin_02',STRUCT)];parent=STRUCT

def mat(n,f,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if emit:m.node_tree.links.new(t.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.08
 return m
wall=mat('wall_timber_red','timber-red.png');oak=mat('detail_timber','timber-oak.png');brick=mat('wall_brick_base','brick.png');roof=mat('roof_metal','roof.png');steel=mat('detail_galvanized','steel.png');utility=mat('detail_equipment','utility.png');dark=mat('detail_dark','dark.png');glass=mat('glass_mill','glass.png',True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png');paving=mat('ground_paving','paving.png');gravel=mat('ground_gravel','gravel.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
# Centre the main front edge at asset X=0 while keeping construction coordinates readable.
_base_mesh=mesh
_base_door=door
_base_light=light
def mesh(n,vs,fs,m,patch=None):
 return _base_mesh(n,vs if n=='collision' else [(x+24*U,y,z) for x,y,z in vs],fs,m,patch)
def door(n,p,yaw=0):
 o=_base_door(n,p,yaw);o.location.x+=24*U;return o
def light(n,p,power,size,color=(1,.87,.68)):
 return _base_light(n,(p[0]+24,p[1],p[2]),power,size,color)
def B(n,p,s,m=wall,key=None):return box(n,p,s,m,patch=key)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,p,w,h,key,m,side)
def beam(n,a,b,w=1,d=None,m=dark):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d if d is not None else w)*U/2
 return mesh(n,[tuple(p+x*e+y*v) for p in (a,b) for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def col(n,x,y,w,d,top,attachment=None,gate=None,group='structure'):
 c={'source':n,'center':[(x+24)*U,y*U,top*U/2],'size':[w*U,d*U,top*U],'group':group,'attachment':attachment or parent.name}
 if gate:c['gate_node']=gate
 COLL.append(c)
def wall_run(n,a,b,z0,z1,fixed,openings=[],axis='x',inward=True):
 # Openings are [along0,along1,bottom,top]. Solid cells carry an actual inner timber face.
 coords=sorted(set([a,b]+[v for r in openings for v in r[:2] if a<v<b]))
 for l,r in zip(coords,coords[1:]):
  cuts=sorted(set([z0,z1]+[v for q in openings if q[0]<(l+r)/2<q[1] for v in q[2:] if z0<v<z1]))
  for lo,hi in zip(cuts,cuts[1:]):
   if any(q[0]<(l+r)/2<q[1] and q[2]<(lo+hi)/2<q[3] for q in openings):continue
   o=B(n,((l+r)/2,fixed,(lo+hi)/2) if axis=='x' else (fixed,(l+r)/2,(lo+hi)/2),(r-l,2,hi-lo) if axis=='x' else (2,r-l,hi-lo),brick if hi<=8 else wall)
   o.data.materials.append(oak);f=o.data.polygons[(3 if inward else 1) if axis=='x' else (2 if inward else 4)];f.material_index=1
   old=bpy.data.images.get(o.data.materials[0]['texture_file']).size;new=bpy.data.images.get(oak['texture_file']).size
   for li in f.loop_indices:o.data.uv_layers.active.data[li].uv *= Vector((old[0]/new[0],old[1]/new[1]))

def enter(n,x,y,floor,width,height,key=None,load=False,side='front',holder=STRUCT):
 global parent
 parent=empty('panel_'+n,holder);MOVING.append(parent)
 if load:
  for s in [-1,1]:P(n+' sliding leaf',(x+s*width/4,y,floor+height/2),width/2,height,key,utility,side)
 else:P(n+' closed panel',(x,y,floor+height/2),width,height,key,utility,side)
 parent=holder;o=door(n,(x,y+(-2 if side=='front' else 2),floor),0 if side=='front' else math.pi);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':[0,0,1 if side=='front' else -1],'floor_m':floor*U,'panel':'panel_'+n,'use':'Loading or maintenance' if load or holder==TOWER else 'Retail entrance'})
 col('Closed '+n,x,y,width,2,floor+height,'panel_'+n,'panel_'+n)

def slab(n,pts,m=roof,depth=2):
 vs=[(x*U,y*U,(z-d)*U) for d in [0,depth] for x,y,z in pts];k=len(pts);ob=mesh(n,vs,[tuple(range(k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m);mesh(n+' soffit',vs,[tuple(range(2*k-1,k-1,-1))],oak);return ob
# Floors, with a lower shop and three internal steps up to the warehouse.
B('Retail floor',(-72,32,7),(80,64,2),oak)
for x0,x1,y0,y1 in [(-32,64,0,88),(-112,-32,70,112),(-112,-72,64,70),(-56,-32,64,70),(-32,-24,88,112),(48,64,88,112)]:B('Warehouse floor',((x0+x1)/2,(y0+y1)/2,13),(x1-x0,y1-y0,2),oak)
for i in range(3):B('Internal step',(-64,65+i*2,9+i*2),(16,2,2),oak)
B('Retail ceiling',(-72,32,55),(80,64,2),oak)
# Front wall is actually cut around both windows, public door and wide loading entrance.
wall_run('Front wall',-112,64,0,90,0,[[-108,-84,18,42],[-71,-57,8,38],[-52,-40,18,42],[-12,48,14,62]])
P('Office display window',(-96,-1.1,30),24,24,'office_window');P('Office narrow window',(-46,-1.1,30),12,24,'narrow_window')
enter('door_main',-64,-1.15,8,14,30,'public_door')
enter('door_loading_front',18,-1.15,14,60,48,'load_front',True)
wall_run('West warehouse wall',0,112,0,90,-112,[],axis='y',inward=True)
wall_run('East warehouse wall',0,112,0,90,64,[],axis='y',inward=False)
wall_run('Rear warehouse wall',-112,-24,0,90,112,[[-108,-52,14,62]],inward=False);wall_run('Rear corner return',48,64,0,90,112,[],inward=False)
enter('door_loading_rear',-80,113.1,14,56,48,'load_rear',True,'back')
# Shop separation: a door-sized opening leads onto the three internal steps.
wall_run('Retail rear partition',-112,-32,8,56,64,[[-72,-56,8,40]],inward=False)
# The shop/warehouse divider includes a wide interior opening near the steps.
wall_run('Retail east partition',0,64,8,56,-32,[],axis='y',inward=False)
B('Fixed retail counter',(-88,42,14),(32,12,12),oak)
B('Counter top',(-88,42,20.5),(34,14,1),oak)
col('Fixed retail counter',-88,42,34,14,21)
# Gabled front and split rear gables. The roof is cut around the tall elevator shaft.
mesh('Front gable',[(-112*U,1*U,90*U),(64*U,1*U,90*U),(-24*U,1*U,118*U)],[(2,1,0)],wall)
mesh('Rear left gable',[(-112*U,111*U,90*U),(-24*U,111*U,118*U),(-24*U,111*U,90*U)],[(2,1,0)],wall)
def rz(x):return 118-abs(x+24)*28/88
mesh('Rear right gable',[(48*U,111*U,90*U),(64*U,111*U,90*U),(48*U,111*U,rz(48)*U)],[(0,2,1)],wall)
slab('Main roof west',[(-116,-4,88.73),(-24,-4,118),(-24,116,118),(-116,116,88.73)])
slab('Main roof east front',[(-24,-4,118),(68,-4,88.73),(68,88,88.73),(-24,88,118)])
slab('Main roof east rear strip',[(48,88,rz(48)),(68,88,88.73),(68,116,88.73),(48,116,rz(48))])
# Timber roof trusses, no loose furnishings or invented machinery inside the shells.
for y in [20,60,100]:
 if y<88:
  beam('Truss tie',(-109,y,87),(61,y,87),2.5,3,oak);beam('King post',(-24,y,87),(-24,y,115),2,2,oak);beam('Truss strut',(-86,y,87),(-24,y,115),2,2,oak);beam('Truss strut',(38,y,87),(-24,y,115),2,2,oak)
 else:beam('Rear shortened truss',(-109,y,87),(-26,y,87),2.5,3,oak)
# Front covered dock with shop landing; different floor levels are explicit.
B('Store porch',(-80,-12,4),(64,24,8),paving);B('Front freight dock',(24,-12,7),(80,24,14),paving)
for i in range(4):B('Public entrance step',(-64,-31+2*i,1+2*i),(24,2,2),paving)
# Ramp approaches the public porch along the west wall at 1:12.
mesh('Shop access ramp',[(x*U,y*U,z*U) for x,y,z in [(-136,0,0),(-120,0,0),(-136,0,8),(-120,0,8),(-136,96,0),(-120,96,0)]],[(0,1,3,2),(1,5,3),(0,2,4),(2,3,5,4),(0,4,5,1)],paving)
B('Ramp head landing',(-124,-8,4),(24,16,8),paving)
for x in [-136,-120]:beam('Ramp handrail',(x,0,22),(x,96,14),.8,.8,steel)
for y in [0,32,64,96]:
 z=8*(1-y/96)
 for x in [-136,-120]:beam('Ramp rail post',(x,y,z),(x,y,z+14),.8,.8,steel)
# Sloping canopy has timber braces and front posts clear of entrances.
slab('Front loading canopy',[(-116,-26,58),(68,-26,58),(68,-2,64),(-116,-2,64)],roof)
for x in [-110,-28,60]:
 B('Front canopy post',(x,-22,30),(2.5,2.5,60),oak);beam('Canopy knee brace',(x,-22,44),(x,-9,59),2,2,oak);col('Canopy post',x,-22,3,3,60)
# Rear dock, projecting grain-elevator shaft and service landing.
B('Rear freight dock',(-68,128,7),(88,32,14),paving)
slab('Rear loading canopy',[(-116,110,70),(-24,110,70),(-24,146,62),(-116,146,62)])
for x in [-108,-28]:
 B('Rear canopy post',(x,142,31),(3,3,62),oak);beam('Rear canopy brace',(x,142,45),(x,128,66),2,2,oak);col('Rear canopy post',x,142,3,3,62)
for i in range(7):B('Rear dock step',(-32,145+i*2,13-i*2),(16,2,2),paving)
# A genuine enclosed vertical shaft, with separate window openings and a rear service door.
parent=TOWER
B('Elevator floor',(12,120,13),(72,64,2),oak)
wall_run('Elevator front',-24,48,14,232,88,[[0,24,203,219]])
wall_run('Elevator rear',-24,48,0,232,152,[[5,19,14,44],[-18,-6,200,220]],inward=False)
wall_run('Elevator west',88,152,0,232,-24,[[112,128,200,220]],axis='y',inward=True)
wall_run('Elevator east',88,152,0,232,48,[[112,128,200,220]],axis='y',inward=False)
P('Headhouse front window',(12,86.8,211),24,16,'tower_window');P('Headhouse west window',(-25.1,120,210),16,20,'side_window',glass,'left');P('Headhouse east window',(49.1,120,210),16,20,'side_window',glass,'right');P('Rear ventilator',(-12,153.1,210),12,20,'louver',utility,'back')
enter('door_service',12,153.1,14,14,30,'service_door',False,'back',TOWER)
for y,side,normal in [(87,'front',True),(153,'back',False)]:
 vs=[(-24*U,y*U,232*U),(48*U,y*U,232*U),(12*U,y*U,252*U)];mesh('Elevator '+side+' gable',vs,[(2,1,0) if normal else (0,1,2)],wall)
slab('Elevator west roof',[(-28,84,229.78),(12,84,252),(12,156,252),(-28,156,229.78)])
slab('Elevator east roof',[(12,84,252),(52,84,229.78),(52,156,229.78),(12,156,252)])
B('Elevator roof vent',(12,120,254),(10,20,5),dark);B('Vent rain hood',(12,120,257),(16,24,1.5),steel)
# Front fixed business panels and vertical elevator lettering.
parent=STRUCT;P('sign_name',(-24,-1.25,76),96,18,'mill_name',sign);parent=TOWER;P('sign_tower',(12,86.6,164),12,40,'tower_sign',sign)
# Rear machinery landing with a cut-out in its guardrail for the fixed ladder.
B('Elevator landing',(12,160,231.5),(72,16,1),steel)
for x in [-20,12,44]:beam('Landing brace',(x,152,214),(x,168,231),1.5,1.5,steel)
for z in [239,246]:
 for a,b in [((-24,152,z),(-24,168,z)),((-24,168,z),(35,168,z)),((45,168,z),(48,168,z)),((48,168,z),(48,152,z))]:beam('Landing rail',a,b,.7,.7,steel)
for x,y in [(-24,152),(-24,168),(12,168),(35,168),(45,168),(48,168),(48,152)]:beam('Landing post',(x,y,232),(x,y,246),.7,.7,steel)
B('Tower service landing',(12,160,7),(24,16,14),paving)
for i in range(7):B('Tower service step',(12,169+i*2,13-i*2),(24,2,2),paving)
# Fixed maintenance ladder and stand-offs; no climbing behavior is implied.
parent=LADDER
for x in [36,44]:beam('Elevator ladder rail',(x,160,2),(x,160,247),1,1,steel)
for z in range(4,233,4):beam('Elevator ladder rung',(36,160,z),(44,160,z),.6,.6,steel)
for z in [12,74,140,205,230]:
 for x in [36,44]:beam('Elevator ladder mounting bracket',(x,152,z),(x,160,z),1,1,steel)
# Galvanized grain bins, independent landmark assemblies with roof hatches and front ladders.
for i,(group,x) in enumerate(zip(BINS,[104,170]),1):
 parent=group;B('Bin concrete foundation',(x,96,3),(60,60,6),paving)
 cylinder('Bin corrugated shell',(x,96,84),28,156,steel,None,16)
 # Cylinder side UV is physical and flat faceted; roof is a real sixteen-face cone.
 vs=[((x+29*math.cos(j*math.tau/16))*U,(96+29*math.sin(j*math.tau/16))*U,162*U) for j in range(16)]+[(x*U,96*U,180*U)]
 mesh('Bin conical roof',vs,[(j,(j+1)%16,16) for j in range(16)],steel)
 for z in [6,38,70,102,134,160]:cylinder('Bin seam ring',(x,96,z),28.6,1,steel,None,16)
 cylinder('Bin roof hatch',(x,96,181),3,2,dark,None,8);cylinder('Bin roof hatch lid',(x,96,182.5),4,1,steel,None,8)
 for xx in [x-3.5,x+3.5]:beam('Bin ladder rail',(xx,66,5),(xx,66,167),.8,.8,dark)
 for z in range(7,164,4):beam('Bin ladder rung',(x-3.5,66,z),(x+3.5,66,z),.55,.55,dark)
 for z in [8,60,110,160]:
  for xx in [x-3.5,x+3.5]:beam('Bin ladder bracket',(xx,68.2,z),(xx,66,z),.8,.8,dark)
 cylinder('Aeration inlet',(x+18,70,9),3,6,dark,None,8);B('Aeration motor',(x+18,65,8),(8,8,8),steel);col('Bin and foundation',x,96,60,60,183,group.name);col('Bin ladder and blower',x+8,65,32,8,168,group.name)
# Gravity transfer pipes leave the elevator at roof level and actually meet both hatch inlets.
parent=STRUCT
for y,x,z in [(100,104,226),(140,170,222)]:
 a=Vector((49,y,z))*U;b=Vector((x,96,183))*U;t=(b-a).normalized();e=t.cross(Vector((0,0,1))).normalized();v=t.cross(e);verts=[]
 for p in [a,b]:
  for j in range(8):verts.append(tuple(p+1.5*U*(e*math.cos(j*math.tau/8)+v*math.sin(j*math.tau/8))))
 mesh('Overhead grain pipe',verts,[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(j,(j+1)%8,(j+1)%8+8,j+8) for j in range(8)],steel)
 beam('Pipe outlet support',(48,y,218),(49,y,z),1.5,1.5,dark)
# Wall lamps have proper brackets and glowing undersides, not baked illumination.
for x,y,z,back in [(-92,-2,60,False),(50,-2,66,False),(-68,114,69,True),(12,154,52,True)]:
 dy=7 if back else -7;beam('Lamp arm',(x,y,z),(x,y+dy,z+3),.7,.7,steel);cylinder('Lamp shade',(x,y+dy,z+2),3,1,dark,None,8);cylinder('Lamp glowing underside',(x,y+dy,z+1.3),2.5,.3,glow,None,8);light('Warm dock lamp',(x,y+dy,z),55,8)
for x in [-58,10]:
 beam('Sign lamp bracket',(x,-2,88),(x,-10,91),.7,.7,steel);cylinder('Sign lamp shade',(x,-10,90),3,1,dark,None,8);cylinder('Sign lamp underside',(x,-10,89.3),2.5,.3,glow,None,8);light('Business sign wash',(x,-10,88),35,8)
# Functional-looking downpipes and steel loading tracks.
for x,y in [(-113,100),(65,100)]:beam('Rain downpipe',(x,y,90),(x,y,0),.8,.8,steel);col('Downpipe',x,y,1,1,91)
for y,z,x,w in [(-2,64,18,66),(114,64,-80,62)]:B('Sliding door track',(x,y,z),(w,1.5,1.5),steel)
P('Rear loading label',(-68,113.3,80),48,12,'dock_label',sign,'back')
# Finished corner boards and bargeboards keep the large volumes legible from every angle.
for x,y in [(-112,0),(64,0),(-112,112),(64,112)]:B('Warehouse corner board',(x,y,45),(3,3,90),oak)
for x,y in [(-24,88),(48,88),(-24,152),(48,152)]:B('Elevator corner board',(x,y,116),(2.5,2.5,232),oak)
for z in [124,192,231]:
 for x0,y0,x1,y1 in [(-25,87,49,87),(49,87,49,153),(49,153,-25,153),(-25,153,-25,87)]:beam('Elevator timber belt',(x0,y0,z),(x1,y1,z),1.5,1.5,oak)
for y in [-4,116]:
 beam('Warehouse west bargeboard',(-116,y,89),(-24,y,118),2,2,oak)
 if y<88:beam('Warehouse east bargeboard',(-24,y,118),(68,y,89),2,2,oak)
 else:beam('Warehouse rear roof edge',(48,y,rz(48)),(68,y,89),2,2,oak)
for y in [84,156]:
 beam('Elevator west bargeboard',(-28,y,230),(12,y,252),2,2,oak);beam('Elevator east bargeboard',(12,y,252),(52,y,230),2,2,oak)
for x,y in [(-8,-25),(44,-25),(-100,145),(-60,145)]:B('Dock bumper',(x,y,10),(4,2,8),dark);col('Dock bumper',x,y,4,2,14)
col('Elevator ladder and brackets',40,157,11,11,248,LADDER.name)
# Real fixed pendant fixtures illuminate the clean shells; their lights remain presentation objects.
for x,y in [(8,30),(8,70),(-76,90)]:
 beam('Pendant suspension',(x,y,rz(x)-2),(x,y,80),.5,.5,dark);cylinder('Warehouse pendant shade',(x,y,79),4,2,dark,None,8);cylinder('Warehouse pendant underside',(x,y,77.8),3.5,.3,glow,None,8);light('Warehouse warm pendant',(x,y,77),180,10)
B('Retail ceiling lamp',(-88,28,53),(10,10,1),glow);light('Retail ceiling light',(-88,28,52),65,10)
# Gravel yard, removable lot dressing and dock piles. No loose interior furniture.
parent=LOT;B('Gravel yard',(24,56,.2),(384,256,.4),gravel)
for i,(x,y,z) in enumerate([(-104,124,14),(-100,130,14),(-8,-13,14)]):
 parent=lot_prop('pallet_and_sacks_'+str(i+1));B('Pallet',(x,y,z+1),(12,12,2),oak)
 for xx in [-3,3]:B('Feed sack',(x+xx,y,z+6),(5,8,8),utility,'sack')
 col('Pallet and sacks',x,y,12,12,z+10,parent.name,group='lot')
parent=LOT
for x,y in [(72,148),(-128,116)]:
 parent=lot_prop('service_bin');B('Service bin',(x,y,6),(8,8,12),dark);B('Bin lid',(x,y,12.5),(9,9,1),steel);col('Service bin',x,y,9,9,13,parent.name,group='lot');parent=LOT
# Metadata reference for the unfinished railway, deliberately no baked track or vehicles.
anchor=empty('anchor_rail_dock',ROOT);anchor.location=((-68+24)*U,164*U,0);anchor['reference_only']=True;anchor['purpose']='Future parallel rail siding, tangent +X; runtime must resolve alignment, rail height, clearance and actual railway connection.'
# A fixed collision companion complements the architecture's actual wall surfaces.
parent=ROOT;verts=[];fs=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(verts);verts += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];fs += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',verts,fs,dark);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in [STRUCT,TOWER,LADDER,*BINS,*MOVING,*PROPS,LOT]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o.name not in ['sign_name','sign_tower']:groups.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in groups.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+n
model=[ROOT]+list(ROOT.children_recursive);tris=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=collision);materials=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=12000 and len(materials)<=12,(tris,len(materials))
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'feed-mill.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':'Hearthside Feed & Grain','era':{'design_years':[1920,1960],'fits':[1950,2030],'notes':'Original timber country elevator with a feed-store/warehouse wing and galvanized grain bins. Actual opening, expansion, closure and ownership belong to simulation facts.'},'units':'metres','axes':'glTF Y up; front public entrance +Z','origin':'Ground at midpoint of the main building front edge; front span X=-88..88 art pixels. Source construction coordinates are uniformly offset +24 art pixels on X before export.','building_m':[338*U,215*U],'lot_m':{'width':384*U,'depth':256*U},'main_block_m':[176*U,112*U],'footprint_m':{'structure_bounds_blender_art':[-113,-32,225,183],'lot_bounds_blender_art':[-144,-72,240,184]},'heights_m':{'elevator_peak':257.75*U,'warehouse_ridge':118*U,'grain_bin_hatch':183*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'door_panels':[g.name for g in MOVING],'rooms':[{'id':'retail','bounds_blender_art':[-88,0,-8,64],'floor_m':8*U,'ceiling_m':54*U,'notes':'Includes a fixed counter; game furnishes loose retail goods.'},{'id':'warehouse','floor_m':14*U,'notes':'Main room east of retail and rear loading strip; three internal steps from the shop. Clean shell with timber trusses.'},{'id':'elevator_shaft','bounds_blender_art':[0,88,72,152],'floor_m':14*U,'notes':'Enclosed maintenance shaft, closed by default; no simulated milling equipment or climbing.'}],'bins':{'nodes':[g.name for g in BINS],'centres_blender_art':[[128,96],[194,96]],'radius_m':28*U,'cylinder_top_m':162*U,'hatch_top_m':183*U},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,96,18],'text':'HEARTHSIDE / FEED & GRAIN','binding':'Static original preview name; runtime town/business name binding not implemented. Preserve the separate tower FEED region.'},'rail':{'anchor':'anchor_rail_dock','reference_only':True,'siding_tangent_gltf':[1,0,0],'platform_edge_blender_y_art':144,'dock_height_m':14*U,'notes':'No rails or trains baked in. Railway is unfinished; developer must resolve actual track centreline, loading gap, rail-head elevation, collision, operations and placement before connecting.'},'collision':{'node':'collision','render':False,'use_the_walls':True,'strategy':'Architectural walls, floors, ramps and closed door meshes provide structural contact; full-height companion boxes cover bins, ladders/blowers, posts, pipes and yard props.','boxes_blender_z_up':COLL,'runtime':'Hide collision from rendering. Opening/hiding a door panel requires disabling its gate_node box. Ladders/shaft landing remain non-climbable until navigation and height collision are integrated.'},'lot_node':'lot','interior':'Clean retail and warehouse shells, timber trusses and fixed architectural counter; no loose indoor furniture, workers or grain machinery.','runtime_handoff':['No business, jobs, grain inventory or goods are spawned.','Rail connection and utility/mill operations need simulation integration.','Door and ladder traversal require runtime handling; preview doors are closed.','Material-role lighting is previewed in Blender; no Filament/S25 validation is claimed.'],'swatches_used':['brick_red','roof_tin_blue','pavement'],'licence':'Shared LimeZu swatches: Timerpg game use only, original credit required. Original geometry and supplemental pixel art.','renders':'Blender desktop previews, not Filament or S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'triangles':tris,'materials':len(materials),'collision_boxes':len(COLL),'doors':len(DOORS)}))
# Presentation stage, excluded from the asset export.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.29,.31,.25,1);bpy.ops.mesh.primitive_plane_add(size=250,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=24;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Mill sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
# Interior review fill is a presentation light only, never exported or baked.
fill=bpy.data.lights.new('Interior review fill','AREA');fo=bpy.data.objects.new('Interior review fill',fill);bpy.context.collection.objects.link(fo);fo.location=(1.2+24*U,3,5.8);fo.rotation_euler=(Vector((0+24*U,6,6.5))-fo.location).to_track_quat('-Z','Y').to_euler();fill.shape='DISK';fill.size=4
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def shot(n,p,t,scale=40,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=22 if n=='06-warehouse-shell' else 32;cam.data.ortho_scale=scale;cam.location=(p[0]+24*U,p[1],p[2]);t=(t[0]+24*U,t[1],t[2]);cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .1*power
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .05
 fill.energy=220 if n=='06-warehouse-shell' else 0
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-front-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'feed-mill.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('01-front-day',(38,-38,29),(2,6,9),39)
shot('02-blue-hour',(38,-38,26),(2,6,9),39,True)
shot('03-rear',(-34,40,27),(2,7,9),39)
shot('04-above',(21,-22,52),(2,6,7),40)
shot('05-person-height',(-17,-17,1.7),(0,7,8),40,False,True)
shot('06-warehouse-shell',(-6.5,7.7,2.3),(1.5,2.5,4.2),40,True,True)
