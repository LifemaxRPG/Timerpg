"""Classic four-leg riveted steel water tower, original Timerpg landmark."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;CY=82
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def group(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=group('water_tower');STRUCT=group('structure',ROOT);LOT=group('lot',ROOT);TANK=group('tank',STRUCT);LADDER=group('ladder',STRUCT);GATE=group('service_gate',STRUCT);parent=STRUCT
# Coordinates below are art pixels, local ground origin at the front gate.
def co(p):return (p[0]*U,(p[1]+CY)*U,p[2]*U)
def mat(n,f,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.72
 t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if emit:m.node_tree.links.new(t.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.2
 return m
steel=mat('wall_tank_steel','tank-steel.png');frame=mat('detail_frame','frame-steel.png');roof=mat('roof_steel','roof-steel.png');walk=mat('detail_catwalk','walk-steel.png');sign=mat('sign_name','sign-name.png');concrete=mat('ground_concrete','concrete.png');gravel=mat('ground_gravel','gravel.png');equipment=mat('detail_equipment','equipment.png');beacon=mat('glow_beacon','beacon.png',True)
def mesh(n,vs,fs,m,local=True):
 vs=[co(p) for p in vs] if local else vs
 me=bpy.data.meshes.new(n);me.from_pydata(vs,[],fs);me.update();o=bpy.data.objects.new(n,me);bpy.context.collection.objects.link(o);o.parent=parent;me.materials.append(m);uv=me.uv_layers.new(name='Metres per texel')
 W,H=bpy.data.images.get(m['texture_file']).size
 for f in me.polygons:
  pts=[me.vertices[me.loops[i].vertex_index].co for i in f.loop_indices];a=(pts[1]-pts[0]).normalized();b=f.normal.cross(a).normalized();xy=[((p-pts[0]).dot(a),(p-pts[0]).dot(b)) for p in pts];lx=min(p[0] for p in xy);ly=min(p[1] for p in xy)
  for li,p in zip(f.loop_indices,xy):uv.data[li].uv=((p[0]-lx)/T/W,(p[1]-ly)/T/H)
 return o
faces=[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]
def box(n,p,s,m=frame):
 return mesh(n,[(p[0]+a*s[0]/2,p[1]+b*s[1]/2,p[2]+c*s[2]/2) for a,b,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]],faces,m)
def beam(n,a,b,w=1,d=None,m=frame):
 a=Vector(co(a));b=Vector(co(b));t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d if d else w)*U/2
 return mesh(n,[tuple(p+u*e+s*v) for p in (a,b) for u,s in [(-1,-1),(1,-1),(1,1),(-1,1)]],faces,m,False)
N=16;offset=-math.pi/2-math.pi/N
def ring(n,r0,z0,r1,z1,m):
 vs=[(r*math.cos(offset+i*math.tau/N),r*math.sin(offset+i*math.tau/N),z) for r,z in [(r0,z0),(r1,z1)] for i in range(N)]
 return mesh(n,vs,[(i,(i+1)%N,(i+1)%N+N,i+N) for i in range(N)],m)
def disc(n,r,z,m,up=True):
 pts=[(r*math.cos(offset+i*math.tau/N),r*math.sin(offset+i*math.tau/N),z) for i in range(N)]
 return mesh(n,pts,[tuple(range(N)) if up else tuple(range(N-1,-1,-1))],m)
def pole(n,x,y,z,h,r,m=frame):
 global parent
 vs=[(x+r*math.cos(i*math.tau/8),y+r*math.sin(i*math.tau/8),zz) for zz in (z,z+h) for i in range(8)];return mesh(n,vs,[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)],m)
# Four battered steel legs on concrete pedestals; horizontal rings and staggered X-braces.
for leg_index,(sx,sy) in enumerate([(-1,-1),(1,-1),(1,1),(-1,1)],1):
 box('Concrete footing',(sx*54,sy*54,3),(16,16,6),concrete);box('Base plate',(sx*54,sy*54,6.5),(11,11,1),walk)
 for dx,dy in [(-4,-4),(4,-4),(4,4),(-4,4)]:pole('Anchor bolt',sx*54+dx,sy*54+dy,7,1,.6,walk)
 beam('leg_'+str(leg_index),(sx*54,sy*54,7),(sx*35,sy*35,236),5,5)
 def at(z):return 54-(z-7)*19/229
for z in [74,145,232]:
 a=at(z)
 for p,q in [((-a,-a,z),(a,-a,z)),((a,-a,z),(a,a,z)),((a,a,z),(-a,a,z)),((-a,a,z),(-a,-a,z))]:beam('Horizontal tie',p,q,3,3)
for z0,z1 in [(10,74),(74,145),(145,232)]:
 a,b=at(z0),at(z1)
 for side in range(4):
  def turn(p):
   x,y,z=p
   for _ in range(side):x,y=-y,x
   return x,y,z
  beam('X diagonal A',turn((-a,-a-1.5,z0)),turn((b,-b-1.5,z1)),1.5,1)
  beam('X diagonal B',turn((a,-a-2.8,z0)),turn((-b,-b-2.8,z1)),1.5,1)
# Central wet riser, base valve and a small locked control cabinet.
pole('Water riser',0,0,1,222,3,walk);box('Riser valve housing',(0,0,10),(9,9,8),frame);beam('Valve wheel spindle',(0,-6,11),(0,-9,11),1)
# Eight-sided valve wheel in the vertical plane.
for i in range(8):
 a,b=i*math.tau/8,(i+1)*math.tau/8;beam('Valve wheel',(4*math.cos(a),-10,11+4*math.sin(a)),(4*math.cos(b),-10,11+4*math.sin(b)),.6, .6,roof)
box('Control cabinet',(20,13,9),(12,8,18),equipment);box('Cabinet latch',(24,8.6,9),(1,1,3),frame);box('Cabinet plinth',(20,13,1),(16,12,2),concrete)
# Tank: visibly riveted cylinder, tapered lower bowl, rolled rim and shallow conical roof.
parent=TANK
ring('Lower bowl',0.9,220,54,236,steel);disc('Bowl drain closure',.9,220,steel,False)
ring('Tank shell',54,236,54,308,steel)
for z in [236,260,284,307]:ring('Rolled seam',54.8,z,54.8,z+1,walk);ring('Seam upper lip',54.8,z+1,54,z+1,walk);ring('Seam lower lip',54,z,54.8,z,walk)
# Red top and bottom trim echo ordinary municipal paint without a brand.
ring('Lower painted belt',55,237,55,241,roof);ring('Upper painted belt',55,303,55,307,roof)
ring('Conical tank roof',56,308,2,330,roof);disc('Roof closure',2,330,roof);ring('Roof eave fascia',56,306,56,308,frame)
pole('Roof vent',0,0,330,6,1.5,walk);pole('Vent cap',0,0,336,1,3,frame)
pole('Beacon stem',-7,0,329,6,.7);pole('Red marker beacon',-7,0,335,3,1.5,beacon)
# Three matching facets conform to the tank: 64 art pixels of developed chord width, never stretched.
rad=(64/3)/(2*math.sin(math.pi/N));vs=[]
for z in [262,282]:
 for i in range(4):a=-math.pi/2-3*math.pi/N+i*math.tau/N;vs.append((rad*math.cos(a),rad*math.sin(a),z))
panel=mesh('sign_name',vs,[(i,i+1,i+5,i+4) for i in range(3)],sign)
for i,f in enumerate(panel.data.polygons):
 uv=[(i/3,1-20/32),((i+1)/3,1-20/32),((i+1)/3,1),(i/3,1)]
 for li,t in zip(f.loop_indices,uv):panel.data.uv_layers.active.data[li].uv=t
panel['text']='';panel['purpose']='Blank town-name panel; no preview name baked in'
# Ring catwalk, continuous rails and narrow stanchions, including the rear ladder opening.
parent=STRUCT
ring('Catwalk surface',66,236.8,54.9,236.8,walk);ring('Catwalk underside',54.9,235.8,66,235.8,frame);ring('Catwalk outer fascia',66,235.8,66,236.8,frame)
for z in [244,251]:
 for i in range(N):
  a,b=offset+i*math.tau/N,offset+(i+1)*math.tau/N
  if i==8:
   y=66*math.cos(math.pi/N)
   for x0,x1 in [(-66*math.sin(math.pi/N),-5),(5,66*math.sin(math.pi/N))]:beam('Landing guardrail',(x0,y,z),(x1,y,z),.7,.7,walk)
   continue
  beam('Catwalk guardrail',(66*math.cos(a),66*math.sin(a),z),(66*math.cos(b),66*math.sin(b),z),.7,.7,walk)
for i in range(N):a=offset+i*math.tau/N;pole('Catwalk stanchion',66*math.cos(a),66*math.sin(a),236.8,14.2,.6,walk)
# Rear fixed ladder ends above the landing. Lower anti-climb gate stays closed; no climb logic implied.
parent=LADDER
for x in [-4,4]:beam('Lower ladder rail',(x,66,3),(x,66,252),1,1,walk)
for z in range(5,238,4):beam('Lower ladder rung',(-4,66,z),(4,66,z),.65,.65,walk)
for z in [10,74,145,230]:
 a=at(z);beam('Ladder mounting crossbar',(-a,a,z),(a,a,z),2,2)
 for x in [-4,4]:beam('Ladder stand-off',(x,a,z),(x,66,z),1.2,1.2)
box('Ladder base pad',(0,66,1),(16,10,2),concrete)
# Cage hoops every ~1.5 m above the first six metres, not gratuitously dense geometry.
for z in range(80,236,20):
 for i in range(8):
  a,b=i*math.pi/8,(i+1)*math.pi/8;beam('Safety cage hoop',(7*math.cos(a),66+7*math.sin(a),z),(7*math.cos(b),66+7*math.sin(b),z),.55,.55,walk)
for i in [0,2,4,6,8]:a=i*math.pi/8;beam('Cage upright',(7*math.cos(a),66+7*math.sin(a),80),(7*math.cos(a),66+7*math.sin(a),236),.5,.5,walk)
# Upper tank ladder is offset so it lands on the same rear catwalk.
for x in [-4,4]:beam('Tank ladder rail',(x,57,237),(x,57,312),1,1,walk)
for z in [246,276,305]:
 for x in [-4,4]:beam('Tank ladder stand-off',(x,53,z),(x,57,z),1,1)
for z in range(239,313,4):beam('Tank ladder rung',(-4,57,z),(4,57,z),.65,.65,walk)
# Pitch-following roof access rails and rungs reach the vent hatch.
for x in [-4,4]:beam('Roof ladder rail',(x,56,312),(x,4,333),1,1,walk)
for i in range(14):y=56-i*4;beam('Roof ladder rung',(-4,y,312+(56-y)*21/52),(4,y,312+(56-y)*21/52),.65,.65,walk)
# A small rear hatch sits on the roof, with a dark rim and hinge.
parent=TANK
for name,width,depth,base,thick,material in [('Roof hatch',12,10,.2,1.5,frame),('Hatch lid',11,9,1.7,.5,walk)]:
 vs=[(x,y,330-y*22/54+z) for z in [base,base+thick] for x,y in [(-width/2,18-depth/2),(width/2,18-depth/2),(width/2,18+depth/2),(-width/2,18+depth/2)]]
 mesh(name,vs,faces,material)
# Fenced maintenance compound makes the intentional non-walkable collision area visible.
parent=STRUCT
for side in range(4):
 def rotate(x,y):
  for _ in range(side):x,y=-y,x
  return x,y
 for k in range(9):
  x=-80+k*20;y=-80
  if side==0 and x==0:continue
  x,y=rotate(x,y);pole('Fence post',x,y,0,25,.8,frame);pole('Fence post cap',x,y,25,1,1.1,walk)
 for x in range(-77,80,4):
  if side==0 and -12<x<12:continue
  xx,yy=rotate(x,-80);beam('Fence bar',(xx,yy,1),(xx,yy,23),.5,.5,frame)
 for z in [4,22]:
  for x0,x1 in ([(-80,-12),(12,80)] if side==0 else [(-80,80)]):
   a,b=rotate(x0,-80),rotate(x1,-80);beam('Fence rail',(*a,z),(*b,z),.8,.8,frame)
parent=GATE
for z in [4,22]:beam('Gate rail',(-12,-80,z),(12,-80,z),.8,.8)
for x in range(-12,13,4):beam('Gate bar',(x,-80,1),(x,-80,23),.5,.5)
beam('Gate diagonal',(-12,-80,4),(12,-80,22),.7,.7);box('Gate latch',(10,-81,13),(2,2,2),walk)
# Lot remains optional; fence and footings belong to the fixed structure.
parent=LOT;box('Gravel service pad',(0,0,.2),(164,164,.4),gravel)
# Metadata-only ladder service anchor; this is not a building entrance.
anchor=group('access_ladder',ROOT);anchor.location=co((0,75,0));anchor.rotation_euler.z=math.pi;anchor['interaction']='Maintenance reference only; no Go inside or climbing implemented'
# Closed fenced site deliberately excludes walking beneath the tower.
parent=ROOT
collision=box('collision',(0,0,169),(164,164,338));collision.hide_render=True;collision['collision_only']=True;collision['render']=False
# Merge by material inside each logical assembly, preserving the exact sign panel node.
for g in [TANK,LADDER,GATE,STRUCT,LOT]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o!=panel and not o.name.startswith('leg_'):groups.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in groups.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+n
model=[ROOT]+list(ROOT.children_recursive);tris=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=collision);materials=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=12000 and len(materials)<=12
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'water-tower.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':'Classic Steel Water Tower','era':{'built_typically':[1920,1965],'fits':[1940,2030],'notes':'Riveted municipal steel tower; repainting and preservation can retain it into the present. No service date or town name is assumed.'},'units':'metres','axes':'glTF Y up; front service gate +Z','origin':'Ground at midpoint of front fence/gate edge (two art pixels ahead of gate)','footprint_m':[164*U,164*U],'heights_m':{'tank_roof':330*U,'maximum':338*U,'catwalk':236.8*U,'fence':26*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'legs':{'count':4,'nodes':['leg_1','leg_2','leg_3','leg_4'],'base_centres_blender_art':[[-54,28,3],[54,28,3],[54,136,3],[-54,136,3]],'upper_centres_z_art':236},'tank':{'radius_m':54*U,'cylinder_base_y_gltf':236*U,'cylinder_top_y_gltf':308*U,'facets':16},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/sign-name.png','rect_art':[0,0,64,20],'developed_width_m':64*U,'height_m':20*U,'facets':3,'blank':True,'runtime':'Separate curved panel; preserve face UVs and write inside the blank region without scaling pixels. No letters or generated town name baked in.'},'ladder':{'node':'ladder','anchor':'access_ladder','climbable':False,'notes':'Rear fixed ladder with cage, catwalk transition and roof ladder; service access is visual only.'},'doors':[],'door_exemption':'Uninhabitable tower: no Go inside doorway. access_ladder is a maintenance anchor, not a door. service_gate remains visibly closed.','collision':{'node':'collision','render':False,'strategy':'One full-height exclusion box for the visibly fenced closed maintenance compound. Walking under the tower and climbing are deliberately unavailable.','boxes_blender_z_up':[{'source':'Closed fenced tower compound','center':[0,CY*U,169*U],'size':[164*U,164*U,338*U],'group':'structure','attachment':'water_tower'}],'gate':'Do not open service_gate or allow entry without replacing this compound collider with detailed structural collision/navigation.'},'lot_node':'lot','lot_notes':'Only gravel pad is removable. Fence, legs, footings and collision remain part of the structure.','runtime_handoff':['No water pressure/storage/utility or climbing simulation is implemented.','Tank town-name binding remains runtime work; default panel is blank.','Red marker is a steady emissive preview; no blinking or aviation behavior is implied.','Landmark importer must hide collision from rendering.'],'swatches_used':['pavement'],'licence':'LimeZu pavement: Timerpg use only, original credit required. Original geometry and other pixel art.','renders':'Blender desktop previews, not Filament or S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'triangles':tris,'materials':len(materials)}))
# Presentation ground/cameras/lights are not part of the exported asset.
parent=None;bgmat=bpy.data.materials.new('Presentation ground');bgmat.diffuse_color=(.25,.29,.25,1)
bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(bgmat)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1200;scene.render.resolution_y=1200;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
world=bpy.data.worlds.new('Sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background')
sun=bpy.data.lights.new('Sun','SUN');o=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(o);o.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
fill=bpy.data.lights.new('Presentation moon fill','AREA');o=bpy.data.objects.new('Presentation moon fill',fill);bpy.context.collection.objects.link(o);o.location=(0,-12,20);o.rotation_euler=(Vector((0,6,14))-o.location).to_track_quat('-Z','Y').to_euler();fill.shape='DISK';fill.size=12
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def shot(n,p,t,scale=33,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=25;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler()
 bg.inputs[0].default_value=(*((.11,.18,.3) if night else (.63,.71,.76)),1);bg.inputs[1].default_value=.52 if night else .65;sun.energy=.35 if night else 2;sun.color=(.5,.64,1) if night else (1,.9,.77);fill.energy=2400 if night else 0;beacon.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=3 if night else .2
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-front-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'water-tower.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('01-front-day',(30,-40,28),(0,6,13))
shot('02-blue-hour',(30,-40,28),(0,6,13),night=True)
shot('03-rear',(-30,44,29),(0,6,13))
shot('04-above',(20,-23,47),(0,6,13),36)
shot('05-person-height',(16,-16,1.7),(0,6,14),perspective=True)
shot('06-tank-detail',(13,-17,27),(0,6,21),17)
