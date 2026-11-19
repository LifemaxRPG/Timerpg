"""Union Depot — original modular station. Blender Z up, front -Y; glTF Y up, front +Z."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=8
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];MOVING=[];CLOCKS=[];ANCHORS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('premier_railroad_depot');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);PLATFORM=empty('rail_platform',ROOT);parent=STRUCT

def mat(n,f,alpha=False,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8
 tex=m.node_tree.nodes.new('ShaderNodeTexImage');tex.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);tex.interpolation='Closest';tex.extension='REPEAT';m.node_tree.links.new(tex.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(tex.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:m.node_tree.links.new(tex.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.12
 return m
brick=mat('wall_brick','brick_red.png');wall=brick;stone=mat('wall_stone','block_courses.png');roof=mat('roof_slate','roof_slate.png');wood=mat('trim_timber','plank_vertical.png');teal=mat('trim_teal','teal.png');glass=mat('glass_depot','glass.png',True);glow=mat('glow_depot','signs.png',emit=True);utility=mat('detail_utility','utility.png');paving=mat('ground_paving','pavement.png');asphalt=mat('ground_asphalt','asphalt_worn.png');floor=mat('floor_terrazzo','terrazzo.png');name_mat=mat('sign_station_name','station-name.png',emit=True)
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def B(n,p,s,m=brick,patch=None):return box(n,(p[0],p[1],p[2]+F),s,m,patch=patch)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,(p[0],p[1],p[2]+F),w,h,key,m,side)
def collider(n,p,s,group='structure',attachment='structure',gate=None):
 c={'source':n,'center':[v*U for v in p],'size':[v*U for v in s],'group':group,'attachment':attachment}
 if gate:c['gate_node']=gate
 COLL.append(c)
def fullbox(n,x,y,w,d,top,group='structure',attachment='structure',gate=None):collider(n,(x,y,top/2),(w,d,top),group,attachment,gate)
def prism_xz(n,pts,y0,y1,m):
 k=len(pts);v=[(x*U,y*U,(z+F)*U) for y in (y0,y1) for x,z in pts]
 return mesh(n,v,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
def shell(n,pts,m,thickness=2):
 k=len(pts);v=[(x*U,y*U,(z+F-d)*U) for d in (0,thickness) for x,y,z in pts]
 return mesh(n,v,[tuple(range(k)),tuple(range(2*k-1,k-1,-1))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
def beam(n,a,b,r=1,m=wood,patch=None):
 a=Vector((a[0],a[1],a[2]+F))*U;b=Vector((b[0],b[1],b[2]+F))*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized();v=t.cross(e);vs=[]
 for p in (a,b):
  for j in range(8):vs.append(tuple(p+U*r*(e*math.cos(j*math.tau/8)+v*math.sin(j*math.tau/8))))
 return mesh(n,vs,[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(j,(j+1)%8,(j+1)%8+8,j+8) for j in range(8)],m,patch)
def entrance(n,x,y,w,h,key,side='front'):
 global parent
 parent=empty('panel_'+n,STRUCT);MOVING.append(parent);P('Closed '+n,(x,y,h/2),w,h,key,utility if key=='freight_door' else glass,side)
 parent=STRUCT
 yaw={'front':0,'back':math.pi,'left':-math.pi/2,'right':math.pi/2}[side]
 dx,dy={'front':(0,-2),'back':(0,2),'left':(-2,0),'right':(2,0)}[side]
 node=door(n,(x+dx,y+dy,F),yaw);ANCHORS.append({'node':n,'position':[node.location.x,node.location.z,-node.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side]})
 size=(w,2,F+h) if side in ('front','back') else (2,w,F+h)
 fullbox('Closed door',x,y,size[0],size[1],size[2],attachment='panel_'+n,gate='panel_'+n)
def wall_run(n,lo,hi,plane,openings,side='front',height=64):
 # Cuts actual holes; glass/doors fill them, rather than covering solid walls.
 x_axis=side in ('front','back');cursor=lo
 def piece(a,b,z0,z1):
  if b<=a or z1<=z0:return
  p=((a+b)/2,plane,(z0+z1)/2) if x_axis else (plane,(a+b)/2,(z0+z1)/2)
  size=(b-a,2,z1-z0) if x_axis else (2,b-a,z1-z0);B(n,p,size)
 for a,b,z0,z1,key,kind,label in sorted(openings):
  piece(cursor,a,0,height);piece(a,b,0,z0);piece(a,b,z1,height)
  x,y=((a+b)/2,plane) if x_axis else (plane,(a+b)/2)
  if kind=='glass':P(label,(x,y,z0+(z1-z0)/2),b-a,z1-z0,key,glass,side)
  elif kind=='door':entrance(label,x,y,b-a,z1,key,side)
  cursor=b
 piece(cursor,hi,0,height)
# Raised foundation and fully level street-to-hall entry threshold.
box('Main stone foundation',(0,80,F/2),(448,128,F),stone)
box('Tower entry foundation',(0,8,F/2),(64,16,F),stone)
box('Operator bay foundation',(0,152,F/2),(48,16,F),stone)
B('Terrazzo hall floor',(0,80,.05),(448,128,.1),floor)
B('Entry terrazzo floor',(0,8,.05),(64,16,.1),floor)
B('Operator floor',(0,152,.05),(48,16,.1),floor)
front=[(-200,-152,0,44,'freight_door','door','door_baggage_street'),(-136,-104,12,44,'window32','glass','Baggage window'),(-80,-48,12,52,'window40','glass','Waiting window'),(-32,32,0,64,'','none','Tower passage'),(48,80,12,52,'window40','glass','Waiting window'),(112,144,12,44,'window32','glass','Office window'),(160,192,12,44,'window32','glass','Office window'),(200,216,0,36,'staff_door','door','door_staff_street')]
wall_run('Street brick wall',-224,224,16,front)
rear=[(-200,-152,0,44,'freight_door','door','door_baggage_platform'),(-136,-104,12,44,'window32','glass','Baggage window'),(-76,-52,0,40,'platform_door','door','door_platform_west'),(-24,24,0,54,'','none','Operator bay passage'),(52,76,0,40,'platform_door','door','door_platform_east'),(112,144,24,48,'window24','glass','Office high window'),(160,192,12,44,'window32','glass','Office window'),(200,216,0,36,'staff_door','door','door_staff_platform')]
wall_run('Platform brick wall',-224,224,144,rear,'back')
P('Baggage street sign',(-176,14.9,52),48,12,'baggage',glow)
P('Baggage platform sign',(-176,145.1,50),48,12,'baggage',glow,'back')
for x,side in [(-224,'left'),(224,'right')]:wall_run('End brick wall',16,144,x,[(32,64,12,44,'window32','glass','End window'),(96,128,12,44,'window32','glass','End window')],side)
# Tower portal, actual segmental arch and a tall four-face clock stage.
for x in (-26,26):B('Tower masonry pier',(x,1,56),(12,2,112))
arc=[(20*math.cos(i*math.pi/12),42+14*math.sin(i*math.pi/12)) for i in range(13)]
prism_xz('Portal arch masonry',[(20,112),(-20,112)]+list(reversed(arc)),0,2,brick)
for a,b in zip(arc,arc[1:]):
 # Radial stone band forms the real arch, separate from the painted masonry courses.
 outer_a=(a[0]*1.2,42+(a[1]-42)*1.3);outer_b=(b[0]*1.2,42+(b[1]-42)*1.3)
 prism_xz('Limestone arch voussoir',[a,b,outer_b,outer_a],-1.2,0,stone)
for x in (-18,18):B('Entry door jamb',(x,.8,20),(4,2,40),wood)
entrance('door_main',0,-.05,32,40,'main_door')
pts=[(-20,40),(20,40)]+arc
ob=mesh('Arched entrance transom',[(x*U,-.08*U,(z+F)*U) for x,z in pts],[tuple(range(len(pts)))],glass,'arch')
for x in (-31,31):B('Tower side wall',(x,32,74),(2,64,148))
B('Tower upper back wall',(0,63,106),(64,2,84))
B('Tower clock front wall',(0,1,130),(64,2,36))
for z in (68,104,146):B('Tower limestone belt',(0,32,z),(68,68,3),stone)
B('Tower cornice',(0,32,150),(74,74,5),stone)
B('Main entry name case',(0,-2,76),(68,4,16),teal)
P('sign_station_name_front',(0,-4.05,76),64,12,'station_name',name_mat)
# Public waiting hall, baggage partition and fixed ticket counter. No loose furniture inside.
wall_run('Baggage partition',16,144,-96,[(88,104,0,36,'staff_door','door','door_baggage_internal')],'right',62)
wall_run('Ticket office partition',16,144,96,[(48,96,14,38,'ticket_window','glass','Ticket counter glass')],'left',62)
B('Fixed ticket counter',(92,72,7),(8,52,14),stone);B('Timber counter cap',(90,72,14.5),(12,54,1),wood)
P('Ticket counter sign',(94.8,72,52),48,12,'tickets',glow,'left')
P('Public timetable',(-94.8,52,38),32,24,'timetable',glow,'right')
B('Public operator counter',(0,132,7),(48,8,14),wood)
# Projecting track-side operator bay with three real windows.
for x,side in [(-24,'left'),(24,'right')]:
 B('Bay lower side',(x,152,9),(2,16,18));B('Bay upper side',(x,152,51),(2,16,10),wood);P('Bay side glass',(x+(-1.05 if x<0 else 1.05),152,32),16,28,'bay_side',glass,side)
for x in (-22,22):B('Bay window pier',(x,159,28),(4,2,56),stone)
B('Bay brick sill',(0,159,9),(40,2,18));B('Bay timber header',(0,159,51),(40,2,10),wood);P('Track observation window',(0,160.05,32),40,28,'bay_front',glass,'back')
# Complete hipped roof, cut around the tower instead of running through it.
front_roof=[(-240,8,64),(-32,8,64),(-32,64,92),(32,64,92),(32,8,64),(240,8,64),(168,80,100),(-168,80,100)]
shell('Front hipped slate roof',front_roof,roof)
shell('Rear hipped slate roof',[(-240,152,64),(-168,80,100),(168,80,100),(240,152,64)],roof)
shell('West hip',[(-240,8,64),(-168,80,100),(-240,152,64)],roof)
shell('East hip',[(240,152,64),(168,80,100),(240,8,64)],roof)
# Solid under-eaves and ceilings; exposed bracket structure all round.
B('Main hall ceiling',(0,80,61),(448,128,2),wood);B('Tower vestibule ceiling',(0,8,61),(64,16,2),wood)
for y in (8,152):
 for x,w in [(-136,208),(136,208)]:B('Long eave fascia',(x,y,63),(w,3,4),teal)
for x in (-240,240):B('End roof fascia',(x,80,63),(3,144,4),teal)
for x in (-216,-160,-104,104,160,216):
 for y,dy in [(16,-1),(144,1)]:
  beam('Eave bracket',(x,y,50),(x,y+dy*10,62),1.3);B('Bracket head',(x,y+dy*5,61),(3,12,2),wood)
# Four-sided pyramidal clock roof and eight-sided finial.
for pts in [[(-40,-8,153),(40,-8,153),(0,32,184)],[(40,-8,153),(40,72,153),(0,32,184)],[(40,72,153),(-40,72,153),(0,32,184)],[(-40,72,153),(-40,-8,153),(0,32,184)]]:shell('Clock tower slate cap',pts,roof)
cylinder('Tower finial',(0,32,F+188),1,8,utility,'brass',8)
# Clock hands have genuine local pivots. Preview pose 10:10; no simulation time is invented.
for key,p,side in [('front',(0,-1.1,128),'front'),('back',(0,65.1,128),'back'),('west',(-33.1,32,128),'left'),('east',(33.1,32,128),'right')]:
 P('Clock dial '+key,p,32,32,'clock',glow,side)
 x,y,z=p;origin=Vector((x,y,z+F))*U
 right={'front':Vector((1,0,0)),'back':Vector((-1,0,0)),'left':Vector((0,-1,0)),'right':Vector((0,1,0))}[side]
 normal={'front':Vector((0,-1,0)),'back':Vector((0,1,0)),'left':Vector((-1,0,0)),'right':Vector((1,0,0))}[side];up=Vector((0,0,1))
 for hand,length,angle in [('hour',8,-55),('minute',12,60)]:
  node=empty('clock_'+key+'_'+hand,STRUCT);CLOCKS.append(node);node['clock_hand']=hand;node['clock_face']=key;node['preview_angle_clockwise_degrees']=angle;node['outward_axis_gltf']={'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side]
  a=math.radians(angle);direction=right*math.sin(a)+up*math.cos(a);cross=right*math.cos(a)-up*math.sin(a)
  parent=node;pts=[origin+normal*.02+U*(direction*d+cross*w) for d,w in [(-1,-.6),(-1,.6),(length,.4),(length+1,0),(length,-.4)]]
  ob=mesh('Clock '+hand,[tuple(v-origin) for v in pts],[tuple(range(5))],utility,'rubber');node.location=origin
 parent=STRUCT
# Modular platform: same level as the depot floor, full canopy and column supports.
parent=PLATFORM
for x,w,y,d in [(-180,312,176,64),(180,312,176,64),(0,48,184,48)]:
 box('Raised platform masonry',(x,y,F/2),(w,d,F),stone);box('Platform paving',(x,y,F+.05),(w,d,.1),paving)
# Two-art-pixel safety inset, painted cream and dark rather than a modern tactile strip.
box('Platform edge stripe',(0,206,F+.12),(672,2,.12),teal)
shell('Timber platform canopy',[(-288,144,58),(288,144,58),(288,200,48),(-288,200,48)],roof)
B('Canopy outside fascia',(0,200,47),(576,3,4),teal)
for x in (-256,-192,-128,-64,64,128,192,256):
 top=58-(188-144)*10/56-2
 B('Timber platform column',(x,188,top/2),(3,3,top),wood)
 for dx in (-10,10):beam('Platform knee brace',(x,188,top-12),(x+dx,188,top),1.2)
 beam('Sloping platform rafter',(x,146,54.24),(x,198,44.96),1.4)
for x in (-160,160):
 B('Hanging station board',(x,197,39),(68,3,16),teal);P('sign_station_name_platform_'+('west' if x<0 else 'east'),(x,198.55,39),64,12,'station_name',name_mat,'back')
 B('Board hanger',(x,197,46),(2,2,3),utility,patch='brass')
B('Platform number board',(0,197,39),(68,3,16),teal);P('Platform one sign',(0,198.55,39),64,12,'platform',glow,'back');B('Platform number hanger',(0,197,46),(2,2,3),utility,patch='brass')
for x in (-224,-112,0,112,224):
 B('Platform lamp housing',(x,176,50.2),(18,6,.4),teal)
 P('Platform ceiling lamp',(x,176,50),16,4,'lamp',glow,'down');light('Platform warm light',(x,176,F+48),110,18)
# Runtime docking anchors express station geometry only; no railroad contract is presumed.
parent=ROOT
for n,p in [('platform_surface',(0,208,F)),('platform_west_end',(-336,208,F)),('platform_east_end',(336,208,F)),('boarding_west',(-64,204,F)),('boarding_east',(64,204,F))]:
 o=empty(n,PLATFORM);o.location=Vector(p)*U;o['rail_interface']='Station-side anchor; railway alignment pending'
# Forecourt and access ramp, all paving surfaces at or above model ground zero.
parent=LOT
box('Forecourt asphalt',(0,-8,-.5),(544,176,1),asphalt)
box('Street entrance apron',(0,-48,.1),(544,64,.2),paving)
parent=STRUCT
box('Entrance landing',(0,-8,F/2),(64,16,F),stone)
for i in range(4):box('Entrance stair',(0,-30+i*4,(i+1)),(40,4,(i+1)*2),stone)
# 1:12 ramp, 1.24 m wide. Rebuild with F to adjust the station's grade.
x0=-32-12*F;x1=-32
v=[(x*U,y*U,z*U) for x,y,z in [(x0,-16,0),(x1,-16,0),(x1,-16,F),(x0,0,0),(x1,0,0),(x1,0,F)]]
mesh('Accessible entry ramp',v,[(0,2,5,3),(0,3,4,1),(0,1,2),(3,5,4),(1,4,5,2)],paving)
# Ramp rail segments stay within their utility atlas region.
for y in (-16,0):
 for x in (x0,x0+48,x1):
  z=(x-x0)/12;beam('Ramp upright',(x,y,z-F),(x,y,z-F+12),.6,utility,'metal')
 for a,b in [(x0,x0+48),(x0+48,x1)]:beam('Ramp handrail',(a,y,(a-x0)/12-F+12),(b,y,(b-x0)/12-F+12),.6,utility,'metal')
# Optional platform benches and a baggage cart, each independently droppable.
for i,x in enumerate((-128,128),1):
 parent=lot_prop('platform_bench_%02d'%i)
 for dx in (-12,12):B('Bench feet',(x+dx,172,3),(2,8,6),teal)
 B('Bench seat',(x,172,6.5),(32,8,1),wood);B('Bench back',(x,175,11),(32,2,8),wood)
 parent=LOT
parent=lot_prop('baggage_cart')
B('Cart timber deck',(-224,170,5),(24,14,2),wood)
for x in (-232,-216):
 for y in (165,175):
  # Wheel silhouette remains low-sided, not a painted card.
  beam('Cart wheel axle',(x,y-1,2.5),(x,y+1,2.5),2.5,utility,'rubber')
beam('Cart handle',(-212,170,5),(-202,170,14),.6,utility,'metal')
parent=LOT
for i,x in enumerate((-72,72),1):
 parent=lot_prop('forecourt_bin_%02d'%i);box('Lidded bin',(x,-42,6),(8,8,12),teal);box('Bin cap',(x,-42,12.5),(9,9,1),utility,patch='metal');parent=LOT
# Interior fixtures, separate from the external roof.
parent=STRUCT
for x,y in [(-64,72),(64,72),(0,112),(-176,80),(160,80),(0,8)]:
 B('Ceiling fixture housing',(x,y,59.5),(18,6,1),teal)
 P('Interior ceiling lamp',(x,y,59),16,4,'lamp',glow,'down');light('Interior warm light',(x,y,F+57),140,18)
# Complete box list: full-height obstructions, closed door gates and removable groups.
for y,openings in [(16,front),(144,rear)]:
 # Window panes block walking just like their piers; consolidate wall runs around doors.
 cursor=-224
 for a,b,z0,z1,key,kind,label in sorted(o for o in openings if o[5] in ('door','none')):
  if a>cursor:fullbox('Wall and glazing',(cursor+a)/2,y,a-cursor,2,F+64)
  cursor=b
 if cursor<224:fullbox('Wall and glazing',(cursor+224)/2,y,224-cursor,2,F+64)
for x in (-224,224):fullbox('End wall',x,80,2,128,F+64)
for x in (-31,31):fullbox('Tower side',x,32,2,64,F+148)
for x in (-26,26):fullbox('Tower front pier',x,1,12,2,F+112)
fullbox('Ticket office partition',96,80,2,128,F+62)
fullbox('Baggage partition',-96,52,2,72,F+62);fullbox('Baggage partition rear',-96,124,2,40,F+62)
fullbox('Ticket counter',90,72,12,54,F+15)
for x in (-24,24):fullbox('Operator bay side',x,152,2,16,F+56)
fullbox('Operator bay glazing',0,159,48,2,F+56)
for x in (-256,-192,-128,-64,64,128,192,256):fullbox('Platform column',x,188,3,3,F+49,'platform','rail_platform')
for i,x in enumerate((-128,128),1):fullbox('Platform bench',x,173,32,10,F+15,'lot','platform_bench_%02d'%i)
fullbox('Baggage cart',-218.7,170,34.6,14,F+15,'lot','baggage_cart')
for i,x in enumerate((-72,72),1):fullbox('Forecourt bin',x,-42,9,9,13,'lot','forecourt_bin_%02d'%i)
for y in (-16,0):fullbox('Ramp guard rail',-32-6*F,y,12*F,1.5,F+13,'structure','structure')
# Collision can use architectural wall meshes if a consumer wants more precise arch/ramp support.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];off=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(off+j for j in q) for q in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,teal);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for group in [STRUCT,LOT,PLATFORM]+PROPS+MOVING:
 by={}
 for o in list(group.children):
  if o.type=='MESH' and not o.name.startswith('sign_station_name'):by.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in by.items():
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
bpy.ops.export_scene.gltf(filepath=str(R/'premier-railroad-depot.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':'Union Depot','kind':'Premier railroad passenger depot prototype','era':{'architectural_period':[1900,1935],'usable_game_years':[1950,2030],'opening':'Chosen by simulation history, not baked into this asset'},'units':'metres','axes':'glTF Y up, entrance +Z; platform behind station on -Z','origin':'Ground level at middle of the front building edge','entrance_floor_m':F*U,'building_m':[448*U,160*U],'lot_m':{'width':672*U,'depth':304*U,'front_forecourt_depth':96*U,'rear_platform_edge_from_origin':208*U},'heights_m':{'main_ridge':(F+100)*U,'tower_finial':(F+192)*U},'triangles_visible':tris,'materials':materials,'doors':ANCHORS,'door_panels':[n.name for n in MOVING],'collision':{'node':'collision','render':False,'boxes_blender_z_up':COLL,'precision':'Walk-blocking boxes only; overhead headers/ceilings are omitted. Use architectural floor and ramp meshes for height/contact integration.'},'lot_node':'lot','platform_node':'rail_platform','platform_interface':{'status':'Railroad system still in progress; no finalized game gauge, train clearance or station API was available.','deck_height_m':F*U,'deck_width_m':64*U,'length_m':672*U,'anchors':['platform_surface','platform_west_end','platform_east_end','boarding_west','boarding_east'],'orientation':'Track runs along local X; platform faces local -Z.','height_parameter':'Rebuild with F; main floor, thresholds and platform remain level. Ramp run is 12*F.','preview_track':'Presentation only, not exported; 1.435 m reference gauge does not assert the game uses it.'},'clock':{'preview_time':'10:10','hands':[n.name for n in CLOCKS],'pivots':'Clock-face centres. Current geometry bakes preview angles; apply -(target_clockwise-preview_angle_clockwise_degrees) about outward_axis_gltf. Runtime animation is not implemented; the existing loader merges materials.'},'station_name':{'nodes':['sign_station_name_front','sign_station_name_platform_west','sign_station_name_platform_east'],'material':'sign_station_name','texture':'textures/station-name.png','rect_art':[0,0,64,12],'default':'UNION DEPOT','binding':'Generic editable station name; no generated town name or service assumed.'},'interior':'Waiting hall, baggage room, ticket office, fixed counter and three-sided operator bay. Clean shell; game supplies loose interior furniture.','access':{'front_steps':4,'ramp_gradient':'1:12','ramp_width_m':16*U,'note':'Architectural dimensions only; game navigation and railway boarding remain unimplemented.'},'runtime_handoff':['No train, route, timetable, boarding or signal behavior is implemented.','Timetable rows deliberately blank; populate only from actual railway service data.','Hide collision node from rendering. Drop matching attachment/group boxes with hidden geometry.','Door panels and their gate_node boxes open/close together; current loader may keep them closed.','Dropping rail_platform must also hide platform_bench_* and baggage_cart props under lot.','Clock materials and name sign follow material-role lighting; no lighting baked.'],'swatches_used':['brick_red','block_courses','roof_slate','plank_vertical','pavement','asphalt_worn'],'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'licence':'Shared LimeZu materials: Timerpg game use only; credit required. Original geometry and supplemental art.','renders':'Blender desktop previews, not Filament or S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
print(json.dumps({'triangles':tris,'materials':len(materials),'collision_boxes':len(COLL)}))
if '--export-only' in sys.argv:sys.exit(0)
# Presentation-only track, kept OUT of the GLB. Stages scale while the rail API is unfinished.
parent=None;ref=bpy.data.collections.new('PRESENTATION TRACK — NOT EXPORTED');bpy.context.scene.collection.children.link(ref)
def ref_box(n,p,s,m):
 o=box(n,p,s,m);bpy.context.collection.objects.unlink(o);ref.objects.link(o);return o
ref_box('Reference ballast bed',(0,232,.5),(720,40,1),asphalt)
for x in range(-352,353,8):ref_box('Reference sleeper',(x,232,1.15),(3,32,.3),wood)
# Rail width and gauge here are illustration only; no game track assets are claimed.
for y in (232-1.435/(2*U),232+1.435/(2*U)):
 for x in range(-336,337,48):ref_box('Reference rail',(x,y,1.65),(48,1,1),teal)
bgmat=bpy.data.materials.new('Presentation ground');bgmat.diffuse_color=(.24,.29,.27,1);bpy.ops.mesh.primitive_plane_add(size=250,location=(0,4,-.05));bpy.context.object.data.materials.append(bgmat)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=24;scene.cycles.use_denoising=False;scene.cycles.max_bounces=5;scene.cycles.transparent_max_bounces=6;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG';scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Depot sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background')
sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.5,-.5,-.7);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
scene.use_nodes=True;ns=scene.node_tree.nodes;ns.clear();rl=ns.new('CompositorNodeRLayers');g=ns.new('CompositorNodeGlare');g.glare_type='FOG_GLOW';g.threshold=2;g.size=6;out=ns.new('CompositorNodeComposite');scene.node_tree.links.new(rl.outputs['Image'],g.inputs['Image']);scene.node_tree.links.new(g.outputs['Image'],out.inputs['Image'])
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
def shot(n,p,t,scale=68,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=26 if n=='06-waiting-hall' else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler()
 bg.inputs[0].default_value=(*((.12,.18,.28) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.4 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.65,1) if night else (1,.9,.75)
 for d,power in LIGHTS:d.energy=power*(1 if night else .12)
 for m in (glow,name_mat):m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .12
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-street-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'premier-railroad-depot.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if '--draft' in sys.argv or '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_x=800;scene.render.resolution_y=600
if '--draft' not in sys.argv:shot('06-waiting-hall',(-5,8,2.25),(7,5.6,2.7),66,True,True)
shot('01-street-day',(-50,-46,32),(0,5,4),66)
shot('02-platform-night',(45,45,20),(0,9,4),66,True)
shot('03-platform-day',(45,42,25),(0,9,3),66)
shot('04-above',(-12,-26,70),(0,7,0),72)
if '--draft' not in sys.argv:
 shot('05-person-height',(-9,-22,1.7),(0,2,5),66,True,True)
