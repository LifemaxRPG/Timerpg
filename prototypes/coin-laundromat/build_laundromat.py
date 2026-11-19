"""Suds & Spin: clean-shell 1960s laundromat plus a separately exported optional machine kit."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=2;REG=json.loads((R/'art-regions.json').read_text());COLL=[];KCOLL=[];LIGHTS=[];PROPS=[];MOVING=[];DOORS=[];SLOTS=[];UNITS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('coin_laundromat');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);SIGN=empty('roadside_sign',STRUCT);KIT=empty('laundry_fixtures');parent=STRUCT

def mat(n,f,emit=False,alpha=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.45 if alpha else .78;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if emit:m.node_tree.links.new(t.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.06
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 return m
wall=mat('wall_stucco','stucco.png');coral=mat('detail_coral','coral.png');roof=mat('roof_membrane','roof.png');steel=mat('detail_steel','steel.png');enamel=mat('detail_enamel','enamel.png');floor=mat('ground_terrazzo','floor.png');utility=mat('detail_panels','panels.png');glass=mat('glass_storefront','glass.png',alpha=True);glow=mat('glow_fluorescent','glow.png',True);sign=mat('sign_name','signs.png',True);paving=mat('ground_paving','paving.png');drum=mat('detail_drum','drum.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def B(n,p,s,m=wall,key=None):return box(n,p,s,m,patch=key)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,p,w,h,key,m,side)
def beam(n,a,b,w=1,d=None,m=steel):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d if d else w)*U/2
 return mesh(n,[tuple(p+x*e+y*v) for p in (a,b) for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def col(n,x,y,w,d,top,attachment=None,gate=None,group='structure',kit=False):
 c={'source':n,'center':[x*U,y*U,top*U/2],'size':[w*U,d*U,top*U],'group':group,'attachment':attachment or parent.name}
 if gate:c['gate_node']=gate
 (KCOLL if kit else COLL).append(c)
def wall_run(n,a,b,z0,z1,fixed,openings=[],axis='x'):
 coords=sorted(set([a,b]+[v for q in openings for v in q[:2] if a<v<b]))
 for l,r in zip(coords,coords[1:]):
  cuts=sorted(set([z0,z1]+[v for q in openings if q[0]<(l+r)/2<q[1] for v in q[2:] if z0<v<z1]))
  for lo,hi in zip(cuts,cuts[1:]):
   if not any(q[0]<(l+r)/2<q[1] and q[2]<(lo+hi)/2<q[3] for q in openings):B(n,((l+r)/2,fixed,(lo+hi)/2) if axis=='x' else (fixed,(l+r)/2,(lo+hi)/2),(r-l,2,hi-lo) if axis=='x' else (2,r-l,hi-lo))
def enter(n,x,y,w,h,key,side='front',glass_door=False,external=True):
 global parent
 parent=empty('panel_'+n,STRUCT);MOVING.append(parent);P(n+' closed panel',(x,y,F+h/2),w,h,key,glass if glass_door else utility,side)
 if glass_door:
  B('Door pull',(x+6,y-1,18),(1,1,8),steel)
  for z in [14,22]:beam('Pull spacer',(x+6,y,z),(x+6,y-1,z),.6,.6)
 parent=STRUCT;o=door(n,(x,y+(-2 if side=='front' else 2),F),0 if side=='front' else math.pi);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':[0,0,1 if side=='front' else -1],'floor_m':F*U,'panel':'panel_'+n,'external':external});col('Closed '+n,x,y,w,2,F+h,'panel_'+n,'panel_'+n)
def slab(n,pts,m=roof,depth=2):
 k=len(pts);v=[(x*U,y*U,(z-d)*U) for d in [0,depth] for x,y,z in pts];mesh(n,v,[tuple(range(k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m);mesh(n+' soffit',v,[tuple(range(2*k-1,k-1,-1))],wall)
def ring(n,p,outer,inner,depth,m=steel,side='front',segments=12):
 # Basis u x v is the outward normal. All faces retain physical UV density.
 normal=Vector((0,-1,0) if side=='front' else (1,0,0));u=Vector((1,0,0) if side=='front' else (0,1,0));v=Vector((0,0,1));p=Vector(p);vs=[]
 for dd,rr in [(0,outer),(0,inner),(-depth,outer),(-depth,inner)]:
  for i in range(segments):a=i*math.tau/segments;vs.append(tuple((p+u*rr*math.cos(a)+v*rr*math.sin(a)+normal*dd)*U))
 fs=[]
 for i in range(segments):j=(i+1)%segments;fs += [(i,j,segments+j,segments+i),(2*segments+j,2*segments+i,3*segments+i,3*segments+j),(j,i,2*segments+i,2*segments+j),(segments+i,segments+j,3*segments+j,3*segments+i)]
 return mesh(n,vs,fs,m)
def disk(n,p,r,m=drum,side='front',segments=12):
 u=Vector((1,0,0) if side=='front' else (0,1,0));v=Vector((0,0,1));p=Vector(p);vs=[tuple((p+u*r*math.cos(i*math.tau/segments)+v*r*math.sin(i*math.tau/segments))*U) for i in range(segments)];return mesh(n,vs,[tuple(range(segments))],m)
# Clean architectural shell. No appliance mesh is parented to this root.
B('Public floor',(0,56,1),(128,112,2),floor);B('Service floor',(0,128,1),(128,32,2),paving)
wall_run('Front wall',-64,64,0,58,0,[[-56,4,8,36],[16,34,2,34],[40,60,8,36]])
P('Front display glazing',(-26,-1.1,22),60,28,'store_window');P('Front narrow glazing',(50,-1.1,22),20,28,'narrow_window');enter('door_main',25,-1.15,18,32,'front_door',glass_door=True)
B('Left tiled sill',(-26,-1.5,4),(60,2,8),coral);B('Right tiled sill',(50,-1.5,4),(20,2,8),coral)
for x,side in [(-64,'left'),(64,'right')]:
 wall_run('Side wall',0,144,0,58,x,[[y-10,y+10,33,51] for y in [24,56,88]],'y')
 for y in [24,56,88]:P('High side glazing',(x+(-1.1 if x<0 else 1.1),y,42),20,18,'side_window',glass,side)
wall_run('Rear wall',-64,64,0,58,144,[[37,51,2,32]])
enter('door_back',44,145.1,14,30,'service_door','back')
wall_run('Service partition',-64,64,2,44,112,[[41,55,2,32]]);enter('door_service_inside',48,110.9,14,30,'service_door',external=False)
B('Service ceiling',(0,128,43),(128,32,2),wall)
# Solid butterfly gable prisms close front/back walls up to the pitched roof.
for y in [0,144]:
 for x0,x1,h0,h1 in [(-64,0,60+64*10/68,60),(0,64,60,60+64*10/68)]:
  vs=[(x*U,yy*U,z*U) for yy in [y-1,y+1] for x,z in [(x0,58),(x1,58),(x1,h1),(x0,h0)]]
  mesh('Butterfly wall infill',vs,[tuple(reversed(f)) for f in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]],wall)
slab('Butterfly west roof',[(-68,-4,70),(0,-4,60),(0,148,60),(-68,148,70)])
slab('Butterfly east roof',[(0,-4,60),(68,-4,70),(68,148,70),(0,148,60)])
for y in [-4,148]:
 beam('Roof edge',(-68,y,70),(0,y,60),2,2,coral);beam('Roof edge',(0,y,60),(68,y,70),2,2,coral)
for x in [-68,68]:beam('Roof side cap',(x,-4,70),(x,148,70),2,2,coral)
B('Valley drain',(0,74,59.5),(2,148,1),steel)
beam('Rear rain pipe',(0,146,60),(0,146,0),1,1);col('Rear rain pipe',0,146,1.2,1.2,61)
# Folded entrance canopy follows the butterfly silhouette at a smaller scale.
slab('Canopy west',[(-72,-20,44),(0,-20,38),(0,0,42),(-72,0,48)],roof,1.5)
slab('Canopy east',[(0,-20,38),(72,-20,44),(72,0,48),(0,0,42)],roof,1.5)
beam('Canopy coral edge',(-72,-20,44),(0,-20,38),1.5,2,coral);beam('Canopy coral edge',(0,-20,38),(72,-20,44),1.5,2,coral)
for x in [-62,62]:
 B('Canopy post',(x,-16,22),(2.5,2.5,44),steel);beam('Canopy knee',(x,-16,32),(x,-5,42),1.5,1.5);col('Canopy post',x,-16,3,3,45)
B('Masthead case',(0,-2,48),(116,3,20),coral);P('sign_name',(0,-3.6,48),112,18,'nameplate',sign)
# Fixed folding ledge and architectural service connections; loose furnishings stay out of the primary model.
B('Wall folding ledge',(57,66,14),(14,40,2),enamel)
for y in [50,66,82]:beam('Ledge bracket',(64,y,3),(50,y,13),1,1)
col('Fixed folding ledge',57,66,15,40,15)
for z in [10,13]:B('Washer service rail',(-7,110.5,z),(88,1,1),steel)
for x in [-42,-28,-14,0,14,28]:
 for z in [10,13]:beam('Washer wall port',(x,111,z),(x,109.5,z),.7,.7)
for y in [24,44,64,84]:
 B('Dryer vent hood',(-66,y,21),(4,12,10),steel);col('Dryer vent hood',-66,y,4,12,26)
# Rear boiler/service enclosure, outside the staff room, with an attached flue.
B('Service enclosure',(-28,148,18),(20,8,36),enamel);P('Service louver',(-28,152.1,20),12,20,'louver',utility,'back');col('Service enclosure and flue',-28,148,20,8,79)
cylinder('Service flue',(-28,148,57),2,42,steel,None,8);cylinder('Flue cap',(-28,148,78.5),3,1,steel,None,8)
for z in [55]:beam('Flue wall bracket',(-28,144,z),(-28,148,z),1,1)
# Rear step and front/side circulation surfaces stay clear of the parking bays.
B('Rear service landing',(44,152,1),(24,16,2),paving)
# Original bubble-topped roadside pylon, double sided and finished on every edge.
parent=SIGN
for x in [-108,-80]:
 B('Pylon footing',(x,-6,2),(8,8,4),paving);B('Pylon steel post',(x,-6,51),(3,3,94),steel);col('Pylon support',x,-6,8,8,99,SIGN.name)
B('Pylon sign case',(-94,-6,78),(44,4,40),coral);P('Pylon front',(-94,-8.1,78),40,36,'pylon',sign);P('Pylon back',(-94,-3.9,78),40,36,'pylon',sign,'back')
for x,z,r in [(-111,106,5),(-94,114,7.5),(-78,106,4.5)]:
 ring('Bubble crown',(x,-6.6,z),r,r-1.2,1.2,coral);beam('Bubble stem',(x,-6,98),(x,-6,z-r+1),.8,.8)
parent=STRUCT
# Ceiling fixtures have real housings and visible light panels. Lights themselves are presentation objects.
for y in [26,62,98]:
 B('Fluorescent housing',(0,y,57),(30,6,2),enamel);B('Fluorescent panel',(0,y,55.9),(28,4,.2),glow);light('Warm fluorescent',(0,y,55.5),120,18,(1,.94,.77))
B('Service ceiling light',(20,126,41),(12,6,1),glow);light('Service-room light',(20,126,40),65,10)
B('Rear porch lamp',(44,145.5,38),(6,3,6),steel);B('Rear porch glow',(44,147.1,38),(4,.3,4),glow);light('Rear porch illumination',(44,149,37),45,8)
# Removable exterior dressing; no interior tables/chairs or machines in the clean shell.
parent=LOT;B('Front concrete court',(-16,-48,.2),(208,96,.4),paving);B('Rear concrete apron',(0,160,.2),(160,32,.4),paving)
PARKING=[]
for i,x in enumerate([-56,-24,8,40],1):
 PARKING.append({'id':i,'bounds_blender_art':[x,-88,x+32,-24]})
 for xx in ([x,x+32] if i==4 else [x]):B('Parking stripe',(xx,-56,.5),(.5,64,.1),enamel)
 B('Parking stop',(x+16,-25,1),(16,2,2),paving)
parent=lot_prop('outside_bench')
B('Bench seat',(-26,-12,7),(30,7,1.5),coral);B('Bench back',(-26,-9,12),(30,1.5,9),coral)
for x in [-36,-16]:B('Bench leg',(x,-12,3.5),(2,5,7),steel)
col('Outside bench',-26,-11,30,9,16.5,parent.name,group='lot')
parent=lot_prop('rear_bin');B('Rear bin',(-58,156,6),(8,8,12),steel);B('Bin lid',(-58,156,12.5),(9,9,1),enamel);col('Rear bin',-58,156,9,9,13,parent.name,group='lot')
# Optional machine kit: all geometry below is under a separate root and separate GLB.
def slot(n,kind,x,y,yaw):
 o=empty('slot_'+n,ROOT);o.location=(x*U,y*U,F*U);o.rotation_euler.z=yaw;o['reference_only']=True;SLOTS.append({'node':o.name,'kind':kind,'position':[x*U,F*U,-y*U],'outward':[0,0,1] if yaw==0 else [1,0,0],'fixture_node':n})
for i,x in enumerate([-42,-28,-14,0,14,28],1):
 n='washer_'+str(i).zfill(2);parent=empty(n,KIT);UNITS.append(parent);B('Washer body',(x,104,9),(12,12,14),enamel);P('Washer control face',(x,97.95,9),12,14,'washer_face',utility);ring('Washer door rim',(x,97.5,8.5),4.2,3.2,.5,steel);disk('Washer empty drum',(x,97.85,8.5),3.2,drum);col(n,x,104,12,13,16,parent.name,group='fixtures',kit=True);slot(n,'washer',x,98,0)
for i,y in enumerate([24,44,64,84],1):
 n='dryer_'+str(i).zfill(2);parent=empty(n,KIT);UNITS.append(parent);B('Dryer body',(-55,y,14),(14,16,24),enamel);P('Dryer control face',(-47.95,y,14),16,24,'dryer_face',utility,'right');ring('Dryer door rim',(-47.5,y,14),6,4.8,.5,steel,'right');disk('Dryer empty drum',(-47.85,y,14),4.8,drum,'right');col(n,-55,y,15,16,26,parent.name,group='fixtures',kit=True);slot(n,'dryer',-48,y,math.pi/2)
parent=empty('change_unit',KIT);UNITS.append(parent);B('Change cabinet',(-49,10,14),(12,10,24),enamel);P('Coin cabinet face',(-49,4.95,14),12,24,'change_face',utility);col('change_unit',-49,10,12,11,26,parent.name,group='fixtures',kit=True);slot('change_unit','change_cabinet',-49,5,0)
# Hidden box meshes for primary and optional kit, each exported under the expected name.
def collider(name,root,boxes):
 global parent
 parent=root;v=[];fs=[]
 for c in boxes:
  x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];fs += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
 o=mesh(name,v,fs,steel);o.hide_render=True;o['collision_only']=True;o['render']=False;return o
collision=collider('collision',ROOT,COLL);kit_collision=collider('collision_fixtures',KIT,KCOLL)
for g in [STRUCT,SIGN,*MOVING,*PROPS,LOT,*UNITS]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o.name!='sign_name':groups.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in groups.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+n
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
def export(root,file,hidden):
 model=[root]+list(root.children_recursive);tris=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=hidden);mats=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});bpy.ops.object.select_all(action='DESELECT')
 for o in model:o.select_set(True)
 bpy.ops.export_scene.gltf(filepath=str(R/file),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
 return tris,mats
tris,materials=export(ROOT,'coin-laundromat.glb',collision);collision.name='collision_building';kit_collision.name='collision';ktris,kmats=export(KIT,'laundry-fixtures.glb',kit_collision);kit_collision.name='collision_fixtures';collision.name='collision'
assert tris+ktris<=12000 and len(set(materials+kmats))<=12,(tris,ktris,len(set(materials+kmats)))
fixture_info={'name':'Optional Suds & Spin machine kit','file':'laundry-fixtures.glb','units':'metres','axes':'glTF Y up, aligned to the main building origin','triangles_visible':ktris,'materials':kmats,'machine_nodes':[o.name for o in UNITS],'machine_count':len(UNITS),'placement':'Already positioned in building coordinates. Import the whole kit at the same origin, or use the main slot anchors for engine furniture instead. Do not place both sets of machines.','floor_m':F*U,'collision':{'node':'collision','render':False,'boxes_blender_z_up':KCOLL},'behavior':'Static optional fixtures only. No washing/drying cycles, billing, prices, water, power, occupancy, animation or inventory are implemented.'}
(R/'fixtures-info.json').write_text(json.dumps(fixture_info,indent=2)+'\n')
info={'name':'Suds & Spin Coin Laundry','era':{'design_years':[1958,1975],'fits':[1960,2030],'notes':'Original mid-century laundromat design. Simulation decides actual opening, business history and operating state.'},'units':'metres','axes':'glTF Y up, front public entrance +Z','origin':'Ground at the middle of the front masonry edge','building_m':[128*U,144*U],'structure_bounds_blender_art':[-116,-26,74,153],'lot_m':[240*U,272*U],'lot_bounds_blender_art':[-136,-96,104,176],'heights_m':{'roof':71*U,'service_flue':79*U,'pylon_crown':121.5*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'door_panels':[o.name for o in MOVING],'rooms':[{'id':'public_laundry','floor_m':F*U,'bounds_blender_art':[-64,0,64,112],'notes':'Clean shell, fixed folding ledge and utility rails; no machine meshes in this file.'},{'id':'service','floor_m':F*U,'bounds_blender_art':[-64,112,64,144],'ceiling_m':42*U}],'clean_shell':True,'optional_fixtures':{'file':'laundry-fixtures.glb','info':'fixtures-info.json','machine_count':len(UNITS),'machine_nodes':[o.name for o in UNITS],'preview':'Default Blender review shows the separate optional kit; 07-clean-shell shows the primary building alone.','binding':'Use either this kit or engine-provided furniture at the slots. No machine/business simulation is implemented.'},'machine_slots':SLOTS,'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,112,18],'text':'SUDS & SPIN / COIN LAUNDRY','binding':'Static fictional preview name. Runtime naming must edit this region only and preserve pylon regions.'},'parking':{'bays':PARKING,'spaces':4,'notes':'Templates only; no vehicles baked in. Traffic and placement require game integration.'},'glazing':{'material':'glass_storefront','alpha':True,'runtime':'Real alpha panes in GLB and Blender. The current landmark importer may need a transparent glass material; do not assume its opaque role shader will show the kit.'},'collision':{'node':'collision','render':False,'use_the_walls':True,'boxes_blender_z_up':COLL,'strategy':'Use architectural walls/floors/closed panels; companion boxes cover canopy posts, fixed ledge, utility fittings, pylon supports and yard props. Optional machines have their own kit collider.','runtime':'Hide collision. Keep door panels and matching gate_node boxes synchronized. Furniture/kit loading must also load matching fixture boxes.'},'lot_node':'lot','runtime_handoff':['No business, residents, laundry loads, machine prices or utility operations are spawned.','Transparent storefront shader, door behavior and furniture/machine bindings require game integration.','The primary GLB is a clean architectural shell; preview kit is delivered separately and is optional.','Blender previews are not Filament/S25 captures.'],'swatches_used':['wall_stucco_red','roof_membrane_grey','pavement'],'licence':'Shared LimeZu swatches: Timerpg game use only; original credit required. Original geometry and supplemental pixel art.','renders':'Blender desktop previews, not Filament/S25 captures; images 01–06 include the separate optional machine kit.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'building_triangles':tris,'kit_triangles':ktris,'combined_materials':len(set(materials+kmats)),'machines':len(UNITS),'building_boxes':len(COLL),'kit_boxes':len(KCOLL)}))
# Presentation terrain, lights and cameras are not exported.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.27,.3,.27,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.cycles.transparent_max_bounces=12;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Laundry sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def fixtures_visible(flag):
 for o in KIT.children_recursive:
  if o.type=='MESH' and o!=kit_collision:o.hide_render=not flag

def shot(n,p,t,scale=28,night=False,perspective=False,shell=False):
 fixtures_visible(not shell and '--shell-only' not in sys.argv);scene.cycles.samples=(8 if '--review' in sys.argv else (12 if n=='07-clean-shell' else 20));cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=23 if n in ['06-interior','07-clean-shell'] else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .12*power
 for m in [glow,sign]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .06
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-front-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'coin-laundromat.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('01-front-day',(28,-34,23),(-2,2,4),30)
shot('02-blue-hour',(28,-34,20),(-2,2,4),30,True)
shot('03-rear',(-29,32,22),(-2,3,4),30)
shot('04-above',(16,-18,42),(-2,3,1),31)
shot('05-person-height',(-15,-20,1.7),(-1,4,3.5),30,False,True)
shot('06-interior',(3.2,1.1,1.7),(-1,6.1,1.4),30,True,True)
shot('07-clean-shell',(3.2,1.1,1.7),(-1,6.1,1.4),30,True,True,True)
