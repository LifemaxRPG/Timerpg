"""Original two-storey Gazette office with a sawtooth-roof print-room wing."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];MOVING=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('gazette_office');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);STAIR=empty('stairs',STRUCT);parent=STRUCT

def mat(n,f,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if emit:m.node_tree.links.new(t.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.06
 return m
wall=mat('wall_brick','brick.png');cream=mat('detail_stone','cream.png');oak=mat('wall_interior','interior.png');roof=mat('roof_membrane','membrane.png');tin=mat('roof_metal','metal-roof.png');steel=mat('detail_steel','steel.png');utility=mat('detail_panels','panels.png');glass=mat('glass_gazette','glass.png',True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png');paving=mat('ground_paving','paving.png');asphalt=mat('ground_asphalt','asphalt.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def B(n,p,s,m=wall,key=None):return box(n,p,s,m,patch=key)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,p,w,h,key,m,side)
def beam(n,a,b,w=1,d=None,m=steel):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d if d else w)*U/2
 return mesh(n,[tuple(p+x*e+y*v) for p in (a,b) for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def col(n,x,y,w,d,top,attachment=None,gate=None,group='structure'):
 c={'source':n,'center':[x*U,y*U,top*U/2],'size':[w*U,d*U,top*U],'group':group,'attachment':attachment or parent.name}
 if gate:c['gate_node']=gate
 COLL.append(c)
def wall_run(n,a,b,z0,z1,fixed,openings=[],axis='x',inward=True):
 coords=sorted(set([a,b]+[v for q in openings for v in q[:2] if a<v<b]))
 for l,r in zip(coords,coords[1:]):
  cuts=sorted(set([z0,z1]+[v for q in openings if q[0]<(l+r)/2<q[1] for v in q[2:] if z0<v<z1]))
  for lo,hi in zip(cuts,cuts[1:]):
   if any(q[0]<(l+r)/2<q[1] and q[2]<(lo+hi)/2<q[3] for q in openings):continue
   o=B(n,((l+r)/2,fixed,(lo+hi)/2) if axis=='x' else (fixed,(l+r)/2,(lo+hi)/2),(r-l,2,hi-lo) if axis=='x' else (2,r-l,hi-lo))
   o.data.materials.append(oak);f=o.data.polygons[(3 if inward else 1) if axis=='x' else (2 if inward else 4)];f.material_index=1;old=bpy.data.images.get(wall['texture_file']).size;new=bpy.data.images.get(oak['texture_file']).size
   for li in f.loop_indices:o.data.uv_layers.active.data[li].uv*=Vector((old[0]/new[0],old[1]/new[1]))
def enter(n,x,y,w,h,key,side='front',load=False):
 global parent
 parent=empty('panel_'+n,STRUCT);MOVING.append(parent)
 if load:
  for s in [-1,1]:P(n+' leaf',(x+s*w/4,y,2+h/2),w/2,h,key,utility,side)
 else:P(n+' panel',(x,y,2+h/2),w,h,key,utility,side)
 parent=STRUCT;dx,dy={'front':(0,-2),'back':(0,2),'right':(2,0)}[side];o=door(n,(x+dx,y+dy,2),{'front':0,'back':math.pi,'right':math.pi/2}[side]);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'right':[1,0,0]}[side],'floor_m':2*U,'panel':'panel_'+n})
 col('Closed '+n,x,y,w if side!='right' else 2,2 if side!='right' else w,h+2,'panel_'+n,'panel_'+n)
def slab(n,pts,m=tin,depth=2):
 k=len(pts);v=[(x*U,y*U,(z-d)*U) for d in [0,depth] for x,y,z in pts];mesh(n,v,[tuple(range(k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m);mesh(n+' soffit',v,[tuple(range(2*k-1,k-1,-1))],oak)
# Two-storey front block, and its actual shopfront/upper-window openings.
B('Ground floor',(0,44,1),(128,88,2),paving)
front=[[-48,-32,2,34],[-26,-6,8,34],[0,48,8,34]]+[[x-12,x+12,57,87] for x in [-40,0,40]]
wall_run('Front masonry',-64,64,0,100,0,front)
P('Shop window',(-16,-1.1,21),20,26,'small_window');P('Newspaper display',(24,-1.1,21),48,26,'display_window');enter('door_main',-40,-1.15,16,32,'main_door')
for x in [-40,0,40]:
 P('Front arched window',(x,-1.15,72),24,30,'arch_window');B('Stone window sill',(x,-2,56),(28,5,2),cream);B('Arch keystone',(x,-1.7,89),(3,3,5),cream)
# Side windows on both floors, giving the whole building finished elevations.
for x,side,inward in [(-64,'left',True),(64,'right',False)]:
 openings=[]
 for y in [22,64]:
  for z in [22,74]:openings.append([y-10,y+10,z-12,z+12])
 wall_run('Side masonry',0,88,0,100,x,openings,'y',inward)
 for y in [22,64]:
  for z in [22,74]:P('Side window',(x+(-1.15 if x<0 else 1.15),y,z),20,24,'side_window',glass,side);B('Side sill',(x,y,z-13),(5,24,2),cream)
rear_open=[[-12,12,2,38]]+[[x-10,x+10,62,86] for x in [-38,0,38]]
wall_run('Main block rear',-64,64,0,100,88,rear_open,inward=False)
for x in [-38,0,38]:P('Rear upper window',(x,89.15,74),20,24,'side_window',glass,'back')
# Floors around a real U-shaped stair opening.
for x0,x1,y0,y1 in [(-64,64,0,38),(-64,-62,38,86),(-30,64,38,86),(-64,64,86,88)]:B('Upper floor',((x0+x1)/2,(y0+y1)/2,49),(x1-x0,y1-y0,2),oak)
B('Main ceiling',(0,44,97),(128,88,2),oak)
parent=STAIR
for i in range(12):
 top=4+2*i;B('Lower stair tread',(-54,41.5+3*i,top/2),(12,3,top),oak)
 top=28+2*i;B('Upper stair tread',(-38,74.5-3*i,top/2),(12,3,top),oak)
B('Half landing',(-46,81,13),(28,10,26),oak)
B('Upper stair landing',(-38,39,25),(12,2,50),oak)
for a,b in [((-61,40,16),(-61,76,40)),((-45,76,40),(-45,40,64)),((-31,40,64),(-31,76,40))]:beam('Stair handrail',a,b,.8,.8)
for x in [-62,-30]:
 beam('Upper stairwell rail',(x,40,64),(x,85,64),.8,.8)
 for y in [40,55,70,85]:beam('Upper stairwell post',(x,y,50),(x,y,64),.8,.8)
# Step to upper floor at z50: lower 2->26 and upper 26->50.
parent=STRUCT
B('Fixed subscriptions counter',(8,24,8),(36,12,12),oak);B('Counter cap',(8,24,14.5),(38,14,1),cream);col('Fixed counter',8,24,38,14,15)
# Layered stone cornices and a stepped parapet with paper/quill emblem.
for z,h,depth in [(4,4,3),(40,3,3),(51,2,3),(98,3,4),(102,3,5)]:
 for x,y,w,d in [(0,0,128+depth,depth),(0,88,128+depth,depth),(-64,44,depth,88-depth),(64,44,depth,88-depth)]:
  if z==4 and y in [0,88]:
   half=(128+depth)/2;hole=(-48,-32) if y==0 else (-12,12)
   for a,b in [(-half,hole[0]),(hole[1],half)]:B('Plinth beside doorway',((a+b)/2,y,z),(b-a,d,h),cream)
  else:B('Masonry band',(x,y,z),(w,d,h),cream)
B('Flat roof',(0,44,99),(128,88,2),roof)
for x,y,w,d in [(0,0,131,3),(0,88,131,3),(-64,44,3,85),(64,44,3,85)]:B('Parapet',(x,y,105),(w,d,10),wall)
for x,y,w,d in [(0,0,133,5),(0,88,133,5),(-64,44,5,83),(64,44,5,83)]:B('Parapet coping',(x,y,110.5),(w,d,1),cream)
B('Raised centre parapet',(0,0,113),(52,4,14),wall);B('Centre coping',(0,0,120.5),(56,6,1),cream);P('sign_emblem',(0,-2.1,112),16,16,'emblem',sign)
P('sign_name',(0,-2.0,44.5),100,14,'masthead',sign)
# Modest rooftop equipment, chimney and hatch, all properly seated.
B('Brick chimney',(44,68,111),(10,10,22));B('Chimney cap',(44,68,122.5),(14,14,1),cream);B('Chimney dark opening',(44,68,123.1),(6,6,.2),steel)
B('Roof hatch curb',(-38,68,101),(18,22,2),steel);B('Roof hatch lid',(-38,68,102.5),(20,24,1),tin)
for x,y in [(28,28),(-12,66)]:cylinder('Roof ventilator',(x,y,103),2,6,steel,None,8);cylinder('Vent cap',(x,y,106.5),4,1,steel,None,8)
# Rear print room with three genuine sawtooth roof bays and clerestories.
B('Print room floor',(0,136,1),(128,96,2),paving)
wall_run('Print room west',88,184,0,58,-64,[],'y',True)
wall_run('Print room east',88,184,0,58,64,[[123,137,2,32]],'y',False)
wall_run('Print room rear',-64,64,0,58,184,[[-4,44,2,38]],inward=False)
enter('door_back',20,185.15,48,36,'loading_leaf','back',True);enter('door_side',65.15,130,14,30,'side_door','right')
for y in [88,120,152]:
 slab('Sawtooth roof',[(-66,y,58),(66,y,58),(66,y+32,72),(-66,y+32,72)])
 for x in [-64,64]:
  sg=1 if x>0 else -1;vs=[(xx*U,yy*U,zz*U) for xx in [x+sg,x-sg] for yy,zz in [(y,58),(y+32,58),(y+32,72)]];fs=[(0,1,2),(5,4,3),(0,3,4,1),(1,4,5,2),(2,5,3,0)]
  if x<0:fs=[tuple(reversed(f)) for f in fs]
  o=mesh('Sawtooth side gable',vs,fs,wall);o.data.materials.append(oak);f=o.data.polygons[1];f.material_index=1;old=bpy.data.images.get(wall['texture_file']).size;new=bpy.data.images.get(oak['texture_file']).size
  for li in f.loop_indices:o.data.uv_layers.active.data[li].uv*=Vector((old[0]/new[0],old[1]/new[1]))
 for x in [-48,-16,16,48]:P('Northlight glazing',(x,y+32,65),32,14,'clerestory',glass,'back')
 for x in [-64,64]:beam('Roof edge',(x,y,58),(x,y+32,72),1.5,1.5)
 beam('Sawtooth gutter',(-66,y,57),(66,y,57),1,1)
 beam('Print-room tie',(-64,y+16,55),(64,y+16,55),1.2,2,oak)
# Integral press foundation marks a future machinery zone, without furnishing the shell.
B('Press foundation',(-12,136,2.5),(40,48,1),paving);col('Press foundation',-12,136,40,48,3)
anchor=empty('anchor_printing_press',ROOT);anchor.location=(-12*U,136*U,3*U);anchor['reference_only']=True
# Rear dispatch awning, cart landing and service fittings.
B('Dispatch apron',(20,198,1),(72,28,2),paving)
slab('Dispatch canopy',[(-20,184,49),(60,184,49),(60,210,43),(-20,210,43)])
for x in [-16,56]:
 B('Canopy post',(x,207,21.5),(2,2,43),steel);beam('Canopy knee',(x,207,31),(x,194,46),1.5,1.5);col('Dispatch canopy post',x,207,2,2,44)
B('Dispatch sign case',(20,210,36),(58,2,14),steel)
P('Dispatch sign',(20,211.1,36),54,12,'dispatch',sign,'back')
for x,y in [(-65.5,86),(65.5,86),(-65.5,182),(65.5,182)]:beam('Downpipe',(x,y,57),(x,y,0),.8,.8);col('Downpipe',x,y,1,1,58)
# Double-sided projecting press sign, on visible steel brackets.
blade=empty('projecting_sign',STRUCT);parent=blade
B('Blade sign case',(-66,-12,59),(2,34,30),steel)
P('Blade west',(-67.1,-12,59),32,28,'blade',sign,'left');P('Blade east',(-64.9,-12,59),32,28,'blade',sign,'right')
for z in [47,72]:beam('Sign bracket',(-64,0,z),(-66,-24,z),1,1)
parent=STRUCT
# Fixed wall and interior fixtures; illumination itself is not exported.
for x,y,z,axis,sg in [(-52,-1.8,38,'y',-1),(52,-1.8,38,'y',-1),(20,185.5,42,'y',1),(65.5,130,37,'x',1)]:
 B('Wall lamp back',(x,y,z),(5,3,8) if axis=='y' else (3,5,8),steel)
 B('Wall lamp glow',(x,y+sg*1.7,z) if axis=='y' else (x+sg*1.7,y,z),(3,1,5) if axis=='y' else (1,3,5),glow)
 light('Warm wall light',(x,y+sg*4,z) if axis=='y' else (x+sg*4,y,z),50,8)
for x in [-36,36]:beam('Masthead lamp arm',(x,-2,55),(x,-10,57),.7,.7);cylinder('Sign lamp shade',(x,-10,56),3,1,steel,None,8);cylinder('Sign lamp underside',(x,-10,55.3),2.5,.3,glow,None,8);light('Masthead wash',(x,-10,54),35,8)
for x,y,z in [(12,36,46),(12,40,94),(-20,116,52),(20,156,52)]:B('Ceiling fixture',(x,y,z),(10,10,1),glow);light('Interior fixture',(x,y,z-1),100,12)
# Optional yard dressing: no residents, operational press or invented headlines.
parent=LOT;B('Front pavement',(0,-12,.5),(152,24,1),paving);B('Rear service yard',(0,152,.2),(176,128,.4),asphalt)
for name,x,y in [('news_box',54,-14),('service_bin',-76,172),('service_bin',76,172)]:
 parent=lot_prop(name);B('Cabinet',(x,y,7),(8,8,14),steel);B('Lid',(x,y,14.5),(9,9,1),cream);col(name,x,y,9,9,15,parent.name,group='lot')
parent=lot_prop('paper_roll_cart');B('Cart pallet',(-32,197,3),(20,16,2),oak)
for x in [-37,-27]:cylinder('Wrapped paper roll',(x,197,9),4,10,utility,'paper',8)
col('Paper roll cart',-32,197,20,16,14,parent.name,group='lot')
# Hidden companion boxes; architecture and stair surfaces provide their own contact.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,steel);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in [STRUCT,STAIR,blade,*MOVING,*PROPS,LOT]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o.name!='sign_name':groups.setdefault(o.data.materials[0].name,[]).append(o)
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
bpy.ops.export_scene.gltf(filepath=str(R/'gazette-office.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':'The Gazette Office & Print Shop','era':{'design_years':[1905,1940],'fits':[1950,2030],'notes':'Original masonry newspaper office with an industrial northlight print-room extension. Simulation decides business history and actual archive content.'},'units':'metres','axes':'glTF Y up, public entrance +Z','origin':'Ground at middle of front masonry edge','building_m':[128*U,184*U],'structure_bounds_blender_art':[-68,-29,71,212],'lot_m':[176*U,246*U],'lot_bounds_blender_art':[-88,-30,88,216],'heights_m':{'main_parapet':121*U,'chimney':123.2*U,'print_roof':72*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'door_panels':[g.name for g in MOVING],'rooms':[{'id':'public_office','floor_m':2*U,'bounds_blender_art':[-64,0,64,88],'ceiling_m':48*U,'notes':'Fixed subscriptions counter, open stair flight; no loose furniture.'},{'id':'editorial','floor_m':50*U,'bounds_blender_art':[-64,0,64,88],'stair_hole_blender_art':[-62,38,-30,86],'ceiling_m':96*U},{'id':'print_room','floor_m':2*U,'bounds_blender_art':[-64,88,64,184],'press_anchor':'anchor_printing_press','press_base_top_m':3*U,'notes':'Clean shell with sawtooth northlights; actual press/furniture and operations remain runtime work.'}],'stairs':{'node':'stairs','lower_floor_m':2*U,'half_landing_m':26*U,'upper_floor_m':50*U,'risers':24,'rise_m':2*U,'tread_m':3*U,'notes':'Actual two-flight geometry; runtime must implement elevation-aware stair traversal and upper-floor navigation.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,100,14],'text':'THE GAZETTE','binding':'Static fictional preview title. Edit only masthead region; other regions carry emblem and projecting/dispatch signs.'},'collision':{'node':'collision','render':False,'use_the_walls':True,'boxes_blender_z_up':COLL,'strategy':'Use architectural walls, floors, stairs and closed panels; explicit full-height boxes cover counter, press base, canopy posts, downpipes and optional yard props.','runtime':'Hide collision mesh. Keep closed door panels synchronized with matching gate_node boxes. Stairs/upper floors need height collision and navigation integration.'},'lot_node':'lot','press':'Foundation and reference anchor only; no printing machine or newspaper generation behavior is implemented.','runtime_handoff':['No fictional headlines, residents, jobs, archive facts or publication history are created.','Actual Gazette editions and ownership must come from simulation facts.','Door, stair, upper-floor and press behavior require runtime integration.','Preview wall/window lighting is staged in Blender, not an S25 measurement.'],'swatches_used':['brick_red','roof_membrane_grey','roof_tin_blue','pavement','asphalt_worn'],'licence':'LimeZu shared swatches: Timerpg game use only, retain original credit; geometry and supplemental art original.','renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'triangles':tris,'materials':len(materials),'doors':len(DOORS),'boxes':len(COLL)}))
# Presentation only, excluded from GLB.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.25,.29,.26,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Gazette sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def shot(n,p,t,scale=26,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=24 if n=='06-print-room' else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .1*power
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .05
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-front-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'gazette-office.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('01-front-day',(26,-31,22),(0,6,4),27)
shot('02-blue-hour',(26,-31,20),(0,6,4),27,True)
shot('03-rear',(-25,37,23),(0,7,4),27)
shot('04-above',(15,-14,40),(0,7,2),28)
shot('05-person-height',(-15,-17,1.7),(0,5,4),27,False,True)
shot('06-print-room',(3.5,13,1.8),(-1,8,3.8),27,True,True)
