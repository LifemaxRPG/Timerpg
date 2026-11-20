"""Milo's Drive-In: one original shell in operating, closed and restored states."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388
STATE=next((a.split('=',1)[1] for a in sys.argv if a.startswith('--state=')),'original');assert STATE in ['original','closed','restored'];CLOSED=STATE=='closed';RESTORED=STATE=='restored'
REG=json.loads((R/'art-regions.json').read_text());TEXMAP=json.loads((R/'texture-map.json').read_text()) if (R/'texture-map.json').exists() else {};COLL=[];LIGHTS=[];PROPS=[];DOORS=[];GLOW_AREA=0;WINDOWS=[];BAYS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('carhop_drive_in');SHELL=empty('stand',ROOT);CANOPY=empty('carhop_canopy',ROOT);PYLON=empty('roadside_pylon',ROOT);LOT=empty('lot',ROOT);GROUPS=[SHELL,CANOPY,PYLON,LOT];parent=SHELL

def mat(n,file,alpha=False,emit=False,tint=False):
 file=TEXMAP.get(file,file);m=bpy.data.materials.new(n);m.use_nodes=True;m['texture_file']=file;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8
 t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/file),check_existing=True);t.interpolation='Closest';t.extension='REPEAT'
 if tint:
  v=m.node_tree.nodes.new('ShaderNodeVertexColor');v.layer_name='Tint';mix=m.node_tree.nodes.new('ShaderNodeMixRGB');mix.blend_type='MULTIPLY';mix.inputs[0].default_value=1;m.node_tree.links.new(t.outputs['Color'],mix.inputs[1]);m.node_tree.links.new(v.outputs['Color'],mix.inputs[2]);m.node_tree.links.new(mix.outputs[0],p.inputs['Base Color'])
 else:m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color'])
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:
  e=t
  if alpha:e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures'/TEXMAP.get('glass-emission-'+STATE+'.png','glass-emission-'+STATE+'.png')),check_existing=True);e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=0 if CLOSED else .025
 return m
wall=mat('wall_block','wall-'+STATE+'.png');cream=mat('wall_enamel','cream.png');teal=mat('detail_teal','teal-'+STATE+'.png');coral=mat('detail_coral','coral-'+STATE+'.png');metal=mat('detail_metal','metal.png');roof=mat('roof_membrane','roof-'+STATE+'.png');asphalt=mat('ground_asphalt','asphalt-'+STATE+'.png');paving=mat('ground_paving','paving.png',tint=True);wood=mat('detail_wood','wood.png');glass=mat('glass_windows','glass-'+STATE+'.png',True,True);glow=mat('glow_lamps','glow-'+STATE+'.png',emit=True);sign=mat('sign_name','sign-'+STATE+'.png');utility=metal
# Twelve material roles; interior tile shares neutral paving with a vertex tint.
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
B=box;P=panel

def colour(o,c):
 cols={'cream':(.92,.89,.72,1),'stripe':(.97,.95,.81,1),'soil':(.48,.39,.26,1),'tile':(.69,.77,.68,1),'closed_stripe':(.58,.63,.53,1)};a=o.data.color_attributes.new(name='Tint',type='FLOAT_COLOR',domain='CORNER')
 for v in a.data:v.color=cols.get(c,c)
 return o
def block(n,x,y,w,d,h):COLL.append({'source':n,'center':[x*U,h*U/2,-y*U],'size':[w*U,h*U,d*U],'group':'lot' if parent in PROPS else 'structure','attachment':parent.name,'support_floor_m':0})
def prop(n):
 global parent
 parent=lot_prop(n);GROUPS.append(parent);return parent

def beam(n,a,b,w=2,d=None,m=metal):
 a=Vector(a)*U;b=Vector(b)*U;v=(b-a).normalized();ref=Vector((0,0,1)) if abs(v.z)<.9 else Vector((1,0,0));u=v.cross(ref).normalized()*w*U/2;q=v.cross(u).normalized()*(d or w)*U/2
 return mesh(n,[tuple(p+x*u+y*q) for p in [a,b] for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def plane(n,pts,z,m=paving,c=None):
 o=mesh(n,[(x*U,y*U,z*U) for x,y in pts],[tuple(range(len(pts)))],m)
 if c:colour(o,c)
 return o
def path(n,x,y,w,d,z=.65):return colour(B(n,(x,y,z/2),(w,d,z),paving),'cream')
def wallrun(n,a,b,f,z0,z1,holes=[],axis='x',m=wall):
 xs=sorted(set([a,b]+[v for h in holes for v in h[:2] if a<v<b]))
 for lo,hi in zip(xs,xs[1:]):
  zs=sorted(set([z0,z1]+[v for h in holes if h[0]<(lo+hi)/2<h[1] for v in h[2:] if z0<v<z1]))
  for l,h in zip(zs,zs[1:]):
   if any(v[0]<(lo+hi)/2<v[1] and v[2]<(l+h)/2<v[3] for v in holes):continue
   B(n,((lo+hi)/2,f,(l+h)/2) if axis=='x' else (f,(lo+hi)/2,(l+h)/2),(hi-lo,4,h-l) if axis=='x' else (4,hi-lo,h-l),m)
def window(n,x,y,b,key,side='front'):
 w,h=REG[key]['rect_art'][2:];
 if CLOSED:pass
 elif not (RESTORED and key=='front_window'):P(n,(x,y,b+h/2),w,h,key,glass,side)
 else:
  # Open serving hatch, with only a fixed upper transom retained.
  P('Hatch upper transom',(x,y,b+h-3),w,6,'transom',glass,side)
 if side in ['front','back']:B('Window sill',(x,y,b-1),(w+3,5,2),cream)
 else:B('Window sill',(x,y,b-1),(5,w+3,2),cream)
 WINDOWS.append({'name':n,'key':key,'side':side,'position_blender_art':[x,y,b],'width_art':w,'height_art':h})
 if CLOSED:
  # Full aperture plywood and diagonal braces, no closed door planes.
  B('Weathered window boarding',(x,y-.3 if side=='front' else y+.3 if side=='back' else y,b+h/2),(w,2,h) if side in ['front','back'] else (2,w,h),wood)
  if side in ['front','back']:
   yy=y-1.5 if side=='front' else y+1.5;beam('Boarding diagonal',(x-w/2+2,yy,b+2),(x+w/2-2,yy,b+h-2),1.5,1,metal)
  else:
   xx=x-1.5 if side=='left' else x+1.5;beam('Boarding diagonal',(xx,y-w/2+2,b+2),(xx,y+w/2-2,b+h-2),1.5,1,metal)
def entry(n,x,y,side='front'):
 yaw={'front':0,'back':math.pi}[side];door(n,(x,y,1),yaw);DOORS.append({'node':n,'position':[x*U,U,-y*U],'outward':[0,0,1 if side=='front' else -1],'width_m':18*U,'height_m':29*U,'floor_m':U,'external':True,'open':True})
def lens(n,x,y,z,w,d,power=18):
 global GLOW_AREA
 B(n+' case',(x,y,z+.5),(w+2,d+2,1),metal)
 mesh(n+' lens',[(a*U,b*U,z*U) for a,b in [(x-w/2,y+d/2),(x+w/2,y+d/2),(x+w/2,y-d/2),(x-w/2,y-d/2)]],[(0,1,2,3)],glow);GLOW_AREA+=w*d*U*U
 if not CLOSED:light(n,(x,y,z-1),power,max(w,d))
# Constant 14.28 x 10.24m shell, 3.8m parapet. Door anchors identical across states.
parent=SHELL;colour(B('Interior tile floor',(0,66,.5),(184,132,1),paving),'tile')
front=[[-9,9,1,30],[-76,-32,14,36],[32,76,14,36]]
wallrun('Stand front',-92,92,2,0,46,front)
for x in [-54,54]:window('Front order glass',x,-.15,14,'front_window')
entry('door_main',0,-.5)
for x,side in [(-92,'left'),(92,'right')]:
 wallrun('Stand side',2,130,x,0,46,[[24,64,14,36]],'y');window('Side glass',x+(-2.1 if x<0 else 2.1),44,14,'side_window',side)
wallrun('Stand rear',-92,92,130,0,46,[[-9,9,1,30],[40,68,16,34]])
window('Rear prep glass',54,132.1,16,'rear_window','back');entry('door_back',0,132.5,'back')
# Kitchen divider has an actual open internal door; fixed service counter at windows only.
wallrun('Prep room divider',-90,90,80,1,43,[[-12,12,1,30]],m=cream)
door('door_prep_inside',(0,78,1));
for x in [-54,54]:
 B('Fixed order counter',(x,8,6.5),(46,10,11),teal);B('Counter top',(x,7,13),(49,13,2),metal)
# The restored stand has open walk-up serving hatches above those fixed counters.
if RESTORED:
 # Upper clerestory is retained; lower pane is removed from glass atlas alpha by art generation.
 for x in [-54,54]:B('Walk-up ledge',(x,-5,14),(48,10,2),cream)
for x in [-92,92]:B('Side enamel base',(x+(.2 if x>0 else -.2),66,5),(4.5,132,10),teal)
for y in [0,132]:
 for a,b in [(-94,-10),(10,94)]:B('Front base enamel',((a+b)/2,y,5),(b-a,4.5,10),teal)
B('Ceiling',(0,66,43),(180,128,2),cream);B('Roof membrane',(0,66,46),(192,140,2),roof)
for x in [-96,96]:B('Roof side coping',(x,66,49),(3,144,5),cream)
for y in [-5,137]:B('Roof end coping',(0,y,49),(192,3,5),cream)
B('Stand coral fascia',(0,-5.8,45),(192,1.5,5),coral)
# Roof exhaust and rear services are grounded and seat visibly on the shell.
B('Exhaust curb',(-40,99,50),(18,16,6),metal);B('Exhaust hood',(-40,99,55),(22,20,4),metal)
for x in [-82,82]:
 B('Downpipe',(x,133.3,23),(1.6,1.6,46),metal);B('Downpipe shoe',(x,135,1),(2,5,2),metal);block('Downpipe',x,134,3,6,46)
for y in [26,104]:lens('Interior light',0,y,41,4,12,24)
# Wide flat canopy with a chamfered wing outline, real flat membrane, deep fascia and thin lit edge.
parent=CANOPY
outline=[(-170,-118),(170,-118),(180,-108),(180,-10),(170,0),(-170,0),(-180,-10),(-180,-108)]
def canopy_slab(n,z,h,m):
 vs=[(x*U,y*U,(z+s*h/2)*U) for s in [-1,1] for x,y in outline];k=len(outline);return mesh(n,vs,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
# One exterior skin; no full hidden slabs inside the fascia.
mesh('Canopy membrane',[(x*U,y*U,47*U) for x,y in outline],[tuple(range(len(outline)))],roof)
mesh('Canopy underside',[(x*U,y*U,38*U) for x,y in outline],[tuple(range(len(outline)-1,-1,-1))],cream)
for i,(x,y) in enumerate(outline):
 a,b=outline[(i+1)%len(outline)]
 for z0,z1,m in [(38,40,teal),(40,44,coral),(44,47,cream)]:
  mesh('Canopy fascia',[(xx*U,yy*U,z*U) for xx,yy,z in [(x,y,z0),(a,b,z0),(a,b,z1),(x,y,z1)]],[(0,1,2,3)],m)
for x in [-160,-80,80,160]:
 # A single supported front row keeps the drive aisle and centre walk clear.
 B('Canopy column',(x,-98,19),(3,3,38),teal);B('Column plinth',(x,-98,2),(6,6,4),cream);block('Canopy column',x,-98,6,6,38)
 beam('Canopy knee brace',(x,-98,30),(x,-75,38),2,2,metal)
 B('Canopy rear column',(x,-10,19),(3,3,38),teal);B('Rear column foot',(x,-10,2),(6,6,4),cream);block('Canopy rear column',x,-10,6,6,38)
 B('Canopy steel rafter',(x,-54,36.5),(2.5,91,3),metal)
for x in range(-140,141,40):
 B('Canopy underside rib',(x,-59,37.5),(2,112,2),cream);lens('Bay light',x,-64,36,2,10,20)
# Only two short strips glow; the full fascia and canopy are ordinary lit materials.
for x in [-90,90]:lens('Canopy edge light',x,-116,38,155,1,24)
# Menu/speaker at every bay; textured grille, original menu art, real tiny metal case.
for i,x in enumerate(range(-140,141,40)):
 prop('menu_speaker_'+str(i+1));bay_x=x;x+=-20 if x<0 else 20;y=-65
 B('Menu post',(x,y,8.5),(2,2,17),teal);B('Menu case',(x,y,21),(14,3,22),metal);P('Menu face',(x,y-1.6,22),12,20,'menu',sign)
 for zz in [14,15,16]:B('Speaker slit',(x,y-1.8,zz),(6,.3,.4),cream)
 B('Call button',(x+4,y-1.9,15),(1,1,1),coral);block('Menu post and case',x,y,14,4,32)
 BAYS.append({'index':i+1,'menu_node':parent.name,'centre_gltf_m':[bay_x*U,0,70*U],'width_m':40*U,'depth_m':74*U,'vehicle':'No car geometry; the game positions its own cars. Do not spawn a vehicle over a menu or column.'})
# One restrained repaintable high roadside board, plus a three-dimensional arrow.
parent=PYLON;sx,sy=-226,-139
for x in [sx-29,sx+29]:B('Pylon steel post',(x,sy,49),(4,5,98),teal)
B('Pylon border',(sx,sy,87),(88,5,40),cream);B('Pylon teal return',(sx,sy,87),(84,6,36),teal);P('sign_name',(sx,sy-3.2,87),80,32,'name',sign)
# Unbranded arrow points toward parking; extruded low-poly solid, 0.0776m pixel metre grid.
arrow=[(sx-32,58),(sx+20,58),(sx+20,65),(sx+43,52),(sx+20,39),(sx+20,46),(sx-32,46)]
vs=[(x*U,(sy+dy)*U,z*U) for dy in [-3,3] for x,z in arrow];k=len(arrow)
mesh('Roadside arrow',vs,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],coral)
for x in range(sx-26,sx+16,8):
 B('Arrow lamp bezel',(x,sy-3.3,52),(2,1,2),metal)
 # Small actual lamp quad uses tiled glow, physical density unchanged.
 mesh('Arrow lamp',[(a*U,(sy-3.9)*U,c*U) for a,c in [(x-0.5,51.5),(x+.5,51.5),(x+.5,52.5),(x-.5,52.5)]],[(0,1,2,3)],glow);GLOW_AREA+=U*U
block('Pylon posts and board',sx,sy,90,8,108)
# Two modest sign-wash lamps, mounted on the board, not a glowing billboard.
for xx in [sx-24,sx+24]:
 beam('Sign lamp arm',(xx,sy-3,106),(xx,sy-10,106),1,1,metal)
 lens('Sign wash',xx,sy-10,105,3,3,35)
 if not CLOSED:
  light_data=LIGHTS[-1][0];ob=next(o for o in bpy.data.objects if o.type=='LIGHT' and o.data==light_data);ob.rotation_euler=(Vector((xx*U,sy*U,87*U))-ob.location).to_track_quat('-Z','Y').to_euler()
# Lot surfaces; asphalt forecourt and side/rear access. No buried paving.
parent=LOT;B('Forecourt',(0,0,.18),(560,444,.36),asphalt)
prop('walks');path('Front raised pedestrian apron',0,-5,204,20,1);path('Rear staff landing',0,139,32,22,1)
path('East side walk',107,64,18,118,1);path('East picnic terrace',176,60,112,118,1)
# Clear central aisle to front entry, no parking space painted over it.
prop('parking_lines')
for x in range(-160,161,40):
 if x==0:continue
 plane('Carhop bay stripe',[(x-.6,-110),(x+.6,-110),(x+.6,-16),(x-.6,-16)],.4,paving,'closed_stripe' if CLOSED else 'stripe')
for x in [-140,-100,-60,-20,20,60,100,140]:
 colour(B('Parking stop',(x,-19,1),(22,3,2),paving),'cream');block('Wheel stop',x,-19,22,3,2)
# Rear utilities, refuse enclosure and timber fence; side access remains unobstructed.
for i,x in enumerate([-50,-70]):
 prop('service_bin_'+str(i));B('Service bin',(x,152,6),(12,10,12),metal);B('Bin lid',(x,152,12.5),(13,11,1),metal);block('Bin',x,152,13,11,13)
prop('rear_fence')
for x in range(-120,161,28):
 B('Fence post',(x,193,12),(2,2,24),wood)
for y in [193]:
 for z in [8,18]:B('Fence rail',(20,y,z),(280,1.5,2),wood)
block('Rear fence',20,193,282,3,24)
# Outdoor furniture is a removable lot prop, not baked indoor furniture.
if RESTORED:
 for i,(x,y) in enumerate([(153,34),(210,34),(153,89),(210,89)]):
  prop('picnic_table_'+str(i));B('Picnic top',(x,y,10),(32,14,2),wood)
  for yy in [y-12,y+12]:B('Picnic bench',(x,yy,6),(32,5,2),wood)
  for xx in [x-11,x+11]:
   beam('Table trestle',(xx,y-10,1),(xx,y+6,9),1.8,2,teal);beam('Table trestle',(xx,y+10,1),(xx,y-6,9),1.8,2,teal);B('Seat support',(xx,y,4),(2,29,2),teal)
  block('Picnic table',x,y,34,31,11)
# Modest landscaping from actual 3D geometry. Closed state has grounded weeds, no billboard trees.
for i,(x,y) in enumerate([(-188,104),(242,156),(-228,171)]):
 prop('planting_'+str(i));colour(B('Planting curb',(x,y,1),(28,26,2),paving),'cream')
 plane('Planting soil',[(x-12,y-11),(x+12,y-11),(x+12,y+11),(x-12,y+11)],2.1,paving,'soil')
 for dx,dy in [(-6,0),(5,5),(3,-5)]:
  # Angular low foliage, deliberately small prop with a muted vertex tint.
  ob=mesh('Shrub',[((x+dx+a)*U,(y+dy+b)*U,(3+c)*U) for a,b,c in [(0,0,10),(-7,-6,0),(7,-6,0),(7,6,0),(-7,6,0)]],[(0,1,2),(0,2,3),(0,3,4),(0,4,1),(4,3,2,1)],teal)
 block('Planting',x,y,28,26,14)
if CLOSED:
 for i,(x,y) in enumerate([(-162,-130),(-90,-167),(160,-133),(214,125),(-210,15),(38,-181)]):
  prop('weed_'+str(i))
  for dx in [-2,1,3]:beam('Dry weed',(x+dx,y,0.5),(x+dx*2,y+dx,6+abs(dx)),.65,.65,wood)
# Status board is contextual; it does not create business hours or owners in simulation.
parent=SHELL;B('Order status frame',(54,-4.5,40),(34,2,14),metal);P('order_status',(54,-5.6,40),32,12,'status',sign)
# Closed collision companion uses the same glTF-metre frame as documented boxes.
parent=ROOT;vs=[];fs=[]
for c in COLL:
 x,z,ny=c['center'];w,h,d=c['size'];y=-ny;k=len(vs);vs.extend([(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]);fs.extend([tuple(k+j for j in f) for f in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]])
collision=mesh('collision',vs,fs,metal);collision.hide_render=True;collision['render']=False;collision['collision_only']=True
for g in GROUPS:
 mats={}
 for o in list(g.children):
  if o.type=='MESH' and o.name not in ['sign_name']:mats.setdefault(o.data.materials[0].name,[]).append(o)
 for mn,obs in mats.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+mn
model=[ROOT]+list(ROOT.children_recursive)
def count(obs):return sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in obs if o.type=='MESH' and o!=collision)
tris=count(model);mats=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=12000 and len(mats)<=12,(tris,mats)
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/('carhop-'+STATE+'.glb')),use_selection=True,export_format='GLB',export_yup=True,export_extras=True,export_vertex_color='ACTIVE')
info={'name':"Milo's Drive-In",'state':STATE,'file':'carhop-'+STATE+'.glb','design_year':1954,'typical_years':[1952,1975] if STATE=='original' else [2000,2030],'selection':'Original operating carhop; later closed or restored are alternative outcomes selected from actual simulation facts, not automatic changes.','units':'metres','axes':'glTF Y up/+Z front','origin':'Grade at middle of stand front wall; canopy projects toward +Z.','building_footprint_m':[184*U,132*U],'canopy_footprint_m':[360*U,118*U],'height_m':108*U,'lot_bounds_gltf_m':{'x':[-280*U,280*U],'z':[-222*U,222*U]},'triangles_visible':tris,'materials':mats,'triangles_per_part':{g.name:count(g.children_recursive) for g in [SHELL,CANOPY,PYLON,LOT]},'prop_triangles':{g.name:count(g.children_recursive) for g in PROPS},'doors':DOORS,'internal_door':'door_prep_inside','rooms':[{'id':'front_service_room','floor_m':U,'notes':'Clean service shell with two fixed order counters, no loose interior furniture.'},{'id':'rear_prep_room','floor_m':U,'notes':'Open internal connection, own rear entry. No kitchen appliances or people are baked in.'}],'windows':WINDOWS,'parking_bays':BAYS,'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres/Y up/+Z front','boxes_gltf_y_up':COLL,'use_the_walls':True,'notes':'Use actual walls/floors/counters plus full-height listed boxes. Read boxes directly without legacy Blender rotation. Closed windows are boarded; entry anchors remain geometrically open for the engine access policy.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/'+TEXMAP.get('sign-'+STATE+'.png','sign-'+STATE+'.png'),'rect_art':[0,0,80,32],'text':"MILO'S / DRIVE-IN",'notes':'Single modest name panel; repaint using the included 5x7 pixel alphabet without stretching its fixed UV region.'},'lot_node':'lot','non_window_emissive_area_m2':GLOW_AREA,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'swatches_used':['block_courses','roof_membrane_grey','asphalt_worn','pavement','plank_horizontal'],'runtime_handoff':['Three matching shells and anchors. No cars, workers, ownership, business transitions or interactive speakers are created.','Restored variant uses walk-up counters and removable outdoor picnic tables; original has the carhop bays only.','Closed variant has faded paint, boarded windows, dry weeds and no light output. The open doorways are deliberate engine interaction portals, not a claim that a closed business admits the player.','Per-town sign repainting, terrain/navigation, collision adapter, furniture, analytic lighting, pane masks and game-state selection remain integration work.'],'renders':'Actual Blender desktop renders, not Filament/S25 captures.'}
(R/('model-info-'+STATE+'.json')).write_text(json.dumps(info,indent=2)+'\n');print('CARHOP_EXPORT',STATE,tris,len(mats),flush=True)
# Actual model renders; cameras/lighting/staging are excluded from GLBs.
parent=None;gm=bpy.data.materials.new('Presentation ground');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=600,location=(0,0,-.04));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=16;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1024;scene.render.resolution_y=640;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
if '--review' in sys.argv:scene.cycles.samples=4;scene.render.resolution_percentage=50
world=bpy.data.worlds.new('Carhop sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.45,-.45,-.65);sun.angle=.09
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam;ONLY=next((set(a.split('=')[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None);raw=Path('/tmp/carhop-raw');raw.mkdir(exist_ok=True);saved=False

def shot(n,p,target,scale,night=False,persp=False):
 global saved
 cam.data.type='PERSP' if persp else 'ORTHO';cam.data.lens=22 if persp else 35;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.13,.19,.27) if night else (.62,.70,.75)),1);bg.inputs[1].default_value=.6 if night else .9;sun.energy=.22 if night else 2;sun.color=(.5,.65,1) if night else (1,.91,.77)
 for l,power in LIGHTS:l.energy=power if night and not CLOSED else power*.1 if not CLOSED else 0
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=0 if CLOSED else .6 if night else .025
 # A camera-mounted inspection light reveals the unpowered boarded shell only in its detail view.
 if CLOSED and n=='interior':
  data=bpy.data.lights.new('Presentation inspection lamp','AREA');data.energy=90;data.shape='DISK';data.size=1.5;ob=bpy.data.objects.new('Presentation inspection lamp',data);bpy.context.collection.objects.link(ob);ob.location=cam.location;ob.rotation_euler=cam.rotation_euler
 scene.render.filepath=str(raw/(STATE+'-'+n+'.png'))
 if not saved:bpy.ops.wm.save_as_mainfile(filepath=str(R/('carhop-'+STATE+'.blend')));saved=True
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('day',(42,-54,21),(-1,-1,3),57)
shot('night',(42,-54,18),(-1,-1,3),57,True)
shot('rear',(-41,46,30),(0,0,2),59)
shot('above',(30,-30,70),(0,0,0),61)
shot('person',(10,-18,1.7),(-3,-3,2.4),59,False,True)
shot('interior',(5.4,5.3,1.8),(-3,1,1.4),59,True,True)
