"""Original 1950s Valley Radio broadcast house and freestanding lattice mast."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=2;REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];MOVING=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('valley_radio');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);MAST=empty('antenna_mast',STRUCT);parent=STRUCT

def mat(n,f,emit=False,alpha=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.82;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:
  e=t
  if n=='glass_radio':e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures/glass-emission.png'));e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.04
 return m
wall=mat('wall_brick','brick.png');oak=mat('wall_interior','interior.png');cream=mat('detail_trim','trim.png');roof=mat('roof_membrane','roof.png');steel=mat('detail_steel','steel.png');floor=mat('ground_floor','floor.png');glass=mat('glass_radio','glass.png',True,True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png',True);utility=mat('detail_panels','panels.png');paving=mat('ground_paving','paving.png');asphalt=mat('ground_asphalt','asphalt.png')
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
def enter(n,x,y,key,side='front',external=True):
 global parent
 parent=empty('panel_'+n,STRUCT);MOVING.append(parent);P(n+' panel',(x,y,18),16,32,key,utility,side);parent=STRUCT
 dx,dy={'front':(0,-2),'back':(0,2),'right':(2,0),'left':(-2,0)}[side];o=door(n,(x+dx,y+dy,F),{'front':0,'back':math.pi,'right':math.pi/2,'left':-math.pi/2}[side]);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'right':[1,0,0],'left':[-1,0,0]}[side],'floor_m':F*U,'panel':'panel_'+n,'external':external});col('Closed '+n,x,y,16 if side in ['front','back'] else 2,2 if side in ['front','back'] else 16,34,'panel_'+n,'panel_'+n)
def prism(n,xy,z0,z1,m):
 k=len(xy);v=[(x*U,y*U,z*U) for z in [z0,z1] for x,y in xy];return mesh(n,v,[tuple(range(k-1,-1,-1)),tuple(range(k,k*2))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
def pane(n,p,w,h,key,side):
 P(n,p,w,h,key,glass,side);x,y,z=p;ww=w if key=='front_window' else w+4
 if side in ['front','back']:B('Window sill',(x,y,z-h/2-1),(ww,4,2),cream);B('Window lintel',(x,y,z+h/2+1),(ww,3,2),cream)
 else:B('Window sill',(x,y,z-h/2-1),(4,w+4,2),cream);B('Window lintel',(x,y,z+h/2+1),(3,w+4,2),cream)
def lamp(x,y,z,side='front'):
 dx,dy={'front':(0,-1),'back':(0,1),'left':(-1,0),'right':(1,0)}[side];B('Wall light',(x,y,z),(5,3,5) if dy else (3,5,5),steel);B('Wall lens',(x+dx*2,y+dy*2,z),(3,.5,3) if dy else (.5,3,3),glow);light('Warm wall pool',(x+dx*4,y+dy*4,z-1),40,8)
# Main house and two proper rear rooms, with six clear door apertures.
B('Main floor',(0,64,1),(152,128,2),floor);B('Main ceiling',(0,64,51),(152,128,2),oak)
wall_run('Front brick',-76,76,0,54,0,[[-40,40,2,44],[-68,-48,10,36],[48,68,10,36]])
for x in [-58,58]:pane('Front lobby sash',(x,-1.2,23),20,26,'side_window','front')
wall_run('Left brick',0,128,0,54,-76,[[10,30,10,36],[76,100,38,50]],'y',True);pane('Lobby side pane',(-77.2,20,23),20,26,'side_window','left');pane('Control clerestory',(-77.2,88,44),24,12,'high_window','left')
wall_run('Right brick',0,128,0,54,76,[[12,32,10,36],[58,82,38,50],[98,114,2,34]],'y',False);pane('Lobby side pane',(77.2,22,23),20,26,'side_window','right');pane('Studio clerestory',(77.2,70,44),24,12,'high_window','right');enter('door_studio_exit',77.15,106,'service_door','right')
wall_run('Rear brick',-76,76,0,54,128,[[-46,-30,2,34],[26,50,38,50]],inward=False);enter('door_service',-38,129.15,'service_door','back');pane('Studio rear high pane',(38,129.2,44),24,12,'high_window','back');P('Service louver',(-5,129.2,28),24,16,'louver',utility,'back')
# Front/rear division: the lobby circulation route serves each room directly.
wall_run('Room front partition',-76,76,2,50,44,[[-46,-30,2,34],[30,46,2,34]])
enter('door_control',-38,42.85,'room_door',external=False);enter('door_studio',38,42.85,'room_door',external=False)
wall_run('Studio control partition',44,128,2,50,0,[[52,68,2,34],[76,108,18,40]],'y',True);enter('door_booth',-1.15,60,'room_door','left',False)
P('Control double glazing',(-1.15,92,29),32,22,'control_window',glass,'left');P('Studio double glazing',(1.15,92,29),32,22,'control_window',glass,'right')
for y in [75,109]:B('Control window jamb',(0,y,29),(3,2,26),cream)
for z in [17,41]:B('Control window surround',(0,92,z),(4,36,2),cream)
# Fixed architectural ledge below the control window; no console/chairs/loose gear.
B('Control observation ledge',(-5,92,16),(8,36,2),cream);col('Fixed control ledge',-5,92,8,36,17)
# Angular glazed entrance bay; its floor/roof butt the main frontage at Y=0.
xy=[(-40,0),(-20,-24),(20,-24),(40,0)];prism('Entrance floor',xy,0,2,floor);prism('Entrance roof',xy,42,44,roof)
wall_run('Entrance front',-20,20,0,42,-24,[[-8,8,2,34],[-20,-8,10,36],[8,20,10,36]])
enter('door_main',0,-25.15,'main_door')
for x in [-14,14]:pane('Entrance sash',(x,-25.2,23),12,26,'front_window','front')
for a,b in [((-40,0),(-20,-24)),((20,-24),(40,0))]:
 a=Vector(a);b=Vector(b);t=(b-a).normalized();out=Vector((t.y,-t.x));length=(b-a).length
 for z,h in [(5,10),(39,6)]:beam('Angled brick apron',(*a,z),(*b,z),2,h,wall)
 for u in [0,length]:
  p=a+t*u;beam('Entrance corner',(*p,0),(*p,42),2,3,cream)
 p=a+out*1.1;q=b+out*1.1;verts=[(p.x*U,p.y*U,10*U),(q.x*U,q.y*U,10*U),(q.x*U,q.y*U,36*U),(p.x*U,p.y*U,36*U)];mesh('Angled entrance glass',verts,[(0,1,2,3)],glass,'angled_glass')
 # Single structural centre mullion; fine framing is pixel artwork elsewhere.
 mid=(a+b)/2+out*1.2;beam('Angled mullion',(*mid,10),(*mid,36),1,1,cream)
 beam('Entrance fascia',(*a,43),(*b,43),4,6,cream)
B('Entrance front fascia',(0,-24,43),(44,4,6),cream)
# A thin swept canopy, attached to the entrance fascia and supported by two posts.
prism('Canopy roof',[(-28,-38),(28,-38),(30,-24),(-30,-24)],38,40,roof)
B('Canopy fascia',(0,-38,39),(60,3,6),steel);B('Canopy lit underside',(0,-36,37.8),(56,1,.4),glow)
for x in [-25,25]:B('Canopy post',(x,-35,19),(2,2,38),steel);col('Canopy post',x,-35,2,2,40)
# Broad roof, stepped parapet and low brick pilasters.
B('Membrane roof',(0,64,55),(156,132,2),roof)
for x,y,w,d in [(0,-1,156,3),(0,129,156,3),(-77,64,3,127),(77,64,3,127)]:B('Parapet',(x,y,59),(w,d,6),wall);B('Coping',(x,y,63),(w+2 if d==3 else 5,5 if d==3 else d,2),cream)
for x in [-74,74]:B('Front pilaster',(x,-1.6,27),(4,4,54),cream)
B('Main sign case',(0,-3,51),(100,4,22),steel);P('sign_name',(0,-5.1,51),94,18,'nameplate',sign)
# Broadcast blade sign, physically braced to the parapet.
B('Blade sign case',(-63,-4,85),(24,6,52),steel);P('radio_blade',(-63,-7.1,85),20,48,'blade',sign);P('radio_blade_back',(-63,-.9,85),20,48,'blade',sign,'back')
for x in [-72,-54]:beam('Blade mount',(x,2,54),(x,-4,108),1.5,1.5,steel)
P('Microphone emblem',(-77.15,56,35),24,24,'emblem',sign,'left')
# Honest exterior service details: vent equipment, roof hatch, drains and cable path.
B('Ventilator curb',(-36,88,57),(30,28,2),steel);B('Ventilator casing',(-36,88,67),(28,26,18),cream);P('Ventilator louver',(-36,74.8,67),24,16,'louver',utility);B('Ventilator lid',(-36,88,76.5),(32,30,1),steel)
B('Roof hatch curb',(40,38,57),(22,26,2),steel);B('Roof hatch',(40,38,58.5),(24,28,1),cream)
for x,y in [(-78.5,126),(78.5,126)]:beam('Downpipe',(x,y,61),(x,y,1),.8,.8,steel);col('Downpipe',x,y,1,1,62)
prism('Service awning',[(-68,128),(-10,128),(-10,154),(-68,154)],36,38,roof)
for x in [-64,-14]:B('Service awning post',(x,150,18),(2,2,36),steel);col('Service awning post',x,150,2,2,38)
B('Rear awning fascia',(-39,154,37),(62,3,5),cream)
for x,y,z,s in [(-16,-26,33,'front'),(16,-26,33,'front'),(-26,130,38,'back'),(78,94,36,'right')]:lamp(x,y,z,s)
# Fixed architectural acoustic lining, not loose furnishings or a measured acoustic treatment.
for x in [14,62]:B('Studio wall lining',(x,45.4,27),(16,.8,34),floor)
B('Studio rear lining',(1.4,118,27),(.8,12,34),floor)
# Clean studio/control-room shells. On-air plate is static art, never a broadcast state.
P('Static on air plate',(38,42.7,41),40,12,'on_air',sign)
for x,y,z in [(-38,20,49),(-38,84,49),(38,84,49)]:B('Ceiling light',(x,y,z),(18,8,1),glow);light('Room ceiling light',(x,y,z-1),95,14)
light('Canopy entry light',(0,-32,36),70,12)
# Four-legged self-supporting mast. No guy wires extend outside its documented compound.
parent=MAST;cx,cy=112,104
for sx in [-1,1]:
 for sy in [-1,1]:B('Mast concrete pier',(cx+sx*12,cy+sy*12,2),(8,8,4),paving)
for i in range(8):
 z0=4+i*28;z1=z0+28;r0=12-i*.75;r1=r0-.75;paint=utility if i%2==0 else cream;patch='tower_red' if i%2==0 else None
 # Segment legs make the painted red atlas region large enough without any bleed.
 for sx in [-1,1]:
  for sy in [-1,1]:
   o=beam('Mast leg',(cx+sx*r0,cy+sy*r0,z0),(cx+sx*r1,cy+sy*r1,z1),1.5,1.5,paint)
   if patch:
    # Rewrite planar physical UV origins into the red region, preserving edge lengths.
    W,H=bpy.data.images.get(utility['texture_file']).size;r=REG[patch]['rect_art']
    for u in o.data.uv_layers.active.data:u.uv+=Vector((r[0]*2/W,(H-(r[1]+r[3])*2)/H))
 corners=[(-1,-1),(1,-1),(1,1),(-1,1)]
 for j in range(4):
  a=corners[j];b=corners[(j+1)%4]
  beam('Lattice X',(cx+a[0]*r0,cy+a[1]*r0,z0),(cx+b[0]*r1,cy+b[1]*r1,z1),.65,.65,steel)
  beam('Lattice X',(cx+b[0]*r0,cy+b[1]*r0,z0),(cx+a[0]*r1,cy+a[1]*r1,z1),.65,.65,steel)
  beam('Mast horizontal tie',(cx+a[0]*r1,cy+a[1]*r1,z1),(cx+b[0]*r1,cy+b[1]*r1,z1),.8,.8,cream)
# Ladder is static geometry, with no climb interaction or operating transmitter.
for x in [110,114]:beam('Mast ladder rail',(x,92,5),(x,98,228),.6,.6,steel)
for i in range(37):
 z=6+i*6;y=92+(z-5)*6/223;beam('Ladder rung',(110,y,z),(114,y,z),.5,.5,steel)
beam('Antenna spike',(112,104,228),(112,104,252),1,1,steel)
for z in [234,246]:
 beam('Antenna cross arm',(104,104,z),(120,104,z),.7,.7,cream)
 for x in [104,120]:beam('Antenna element',(x,104,z-4),(x,104,z+4),.5,.5,steel)
cylinder('Mast cap beacon',(112,104,228.5),2,2,glow,None,8);col('Mast compound blocker',112,104,32,32,253,MAST.name)
parent=STRUCT
beam('Overhead cable conduit',(76.8,104,44),(100,104,44),1,1,steel);beam('Mast cable rise',(100,104,0),(100,104,44),1,1,steel);col('Cable riser',100,104,2,2,45)
# Optional lot: paved approaches, rear access, three parking bays and fenced mast yard.
parent=LOT
B('Public pavement',(0,-32,.3),(108,16,.6),paving);B('Main footpath',(0,-50,.3),(32,20,.6),paving)
B('West parking',(-111,44,.2),(66,176,.4),asphalt);B('East service path',(86,64,.3),(16,128,.6),paving);B('Rear apron',(0,148,.3),(152,40,.6),paving)
for y in [0,40,80,120]:B('Parking line',(-111,y,.45),(62,1,.1),cream)
for y in [20,60,100]:
 parent=lot_prop('parking_stop');B('Wheel stop',(-138,y,1.5),(3,24,3),cream);col('Wheel stop',-138,y,3,24,3,parent.name,group='lot')
parent=lot_prop('mast_fence')
for x,y in [(94,86),(130,86),(94,122),(130,122)]:B('Fence post',(x,y,10),(1.5,1.5,20),steel)
for a,b in [((94,86),(130,86)),((130,86),(130,122)),((130,122),(94,122))]:
 for z in [4,18]:beam('Fence rail',(*a,z),(*b,z),.7,.7,steel)
 # Sparse vertical slats keep the yard visually open and below prop budget.
 for k in range(1,6):p=Vector(a)+(Vector(b)-Vector(a))*k/6;beam('Fence upright',(*p,2),(*p,18),.4,.4,steel)
# 3-sided fence and a closed gate on west; blockers remain under lot.
for y in [90,96,102,108,114,120]:beam('Gate upright',(94,y,2),(94,y,18),.4,.4,steel)
for z in [4,18]:beam('West gate',(94,86,z),(94,122,z),.7,.7,steel)
# The complete open fence remains under the 500-triangle prop budget.
# Whole perimeter has conservative four box colliders; actual thin slats are visual only.
for x,y,w,d in [(112,86,38,2),(130,104,2,38),(112,122,38,2),(94,104,2,38)]:col('Mast yard fence',x,y,w,d,21,parent.name,group='lot')
parent=lot_prop('service_bins')
for x in [-65,-55]:B('Rear bin',(x,142,7),(7,8,14),steel);B('Bin lid',(x,142,14.5),(8,9,1),cream)
col('Bins',-60,142,18,9,15,parent.name,group='lot')
parent=lot_prop('public_bench')
for y in [-14,-10]:B('Bench seat',(-56,y,7),(28,2,2),cream)
for x in [-66,-46]:B('Bench support',(x,-12,3),(3,8,6),steel)
B('Bench back',(-56,-8,12),(28,2,8),cream)
for x in [-66,-46]:B('Bench back upright',(x,-9,8),(1.5,1.5,16),steel)
col('Bench',-56,-12,28,10,16,parent.name,group='lot')
# Hidden full-height box companion. Architecture retains its actual surface contact.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,steel);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in [STRUCT,MAST,*MOVING,*PROPS,LOT]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o.name not in ['sign_name']:groups.setdefault(o.data.materials[0].name,[]).append(o)
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
bpy.ops.export_scene.gltf(filepath=str(R/'radio-station.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
built=[v.co for o in [STRUCT]+list(STRUCT.children_recursive) if o.type=='MESH' for v in o.data.vertices];bmin=[min(v[i] for v in built) for i in range(3)];bmax=[max(v[i] for v in built) for i in range(3)]
info={'name':'Valley Radio','era':{'design_years':[1950,1965],'fits':[1950,2030],'notes':'Original mid-century local broadcast house. Fictional static name; simulation determines actual business and broadcast history.'},'units':'metres','axes':'glTF Y up, public entrance +Z','origin':'Ground at middle of main brick front edge; glazed bay projects toward +Z','building_m':[bmax[0]-bmin[0],bmax[1]-bmin[1]],'built_bounds_blender_m':{'min':bmin,'max':bmax},'lot_bounds_blender_art':[-144,-60,136,168],'heights_m':{'main_parapet':64*U,'blade':111*U,'mast':252*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'door_panels':[g.name for g in MOVING],'rooms':[{'id':'public_lobby','floor_m':F*U,'ceiling_m':50*U,'bounds_blender_art':[-76,0,76,44],'notes':'Open circulation shell, with a lower projecting angular entrance bay.'},{'id':'control_room','floor_m':F*U,'ceiling_m':50*U,'bounds_blender_art':[-76,44,0,128],'notes':'Clean shell with fixed observation ledge and actual double glazing into studio. No console, chairs or loose gear.'},{'id':'studio','floor_m':F*U,'ceiling_m':50*U,'bounds_blender_art':[0,44,76,128],'notes':'Unfurnished recording/broadcast shell. Fixed decorative acoustic wall lining; no loose microphone/console or measured acoustic performance.'}],'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,94,18],'text':'VALLEY RADIO','binding':'Static fictional label; edit nameplate region only. Other atlas regions contain RADIO blade, microphone emblem and static ON AIR plate.'},'collision':{'node':'collision','render':False,'use_the_walls':True,'boxes_blender_z_up':COLL,'strategy':'Actual walls/floors plus full-height boxes for posts, closed doors, fixed ledge, yard fixtures and mast compound.','runtime':'Hide collision mesh. Gate boxes follow closed panels; lot colliders follow lot visibility. Mast footprint is conservatively blocked, not climbable.'},'lot_node':'lot','antenna':{'node':'antenna_mast','type':'freestanding self-supporting lattice mast','height_m':252*U,'reference_only':True,'notes':'Static visual geometry. No RF coverage, licensing, transmission, climbing or electrical safety functionality is implemented.'},'runtime_handoff':['No residents, station ownership, schedules, dialogue, broadcasts or historical facts are generated.','ON AIR is static artwork; a runtime must bind or hide it rather than infer active broadcasting.','Transparent glass, emissive masks, doors, navigation and lighting need game integration.','Ladder, antenna and conduit are visual architecture only. No simulated signal or climb behavior.','Blender lighting, cameras and surrounding presentation terrain are excluded from the GLB.'],'swatches_used':['brick_red','wall_stucco_red','roof_membrane_grey','pavement','asphalt_worn'],'licence':'LimeZu shared swatches: Timerpg-only use; retain original credit. Supplemental artwork and all geometry original.','renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'triangles':tris,'materials':len(materials),'doors':len(DOORS),'boxes':len(COLL)}))
# Presentation only. These cameras/lights are not part of the GLB.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Valley Radio sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def shot(n,p,t,scale=32,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=22 if n=='06-studio-control' else 30;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .12*power
 for m in [glass,glow,sign]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.7 if night else .04
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-front-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'radio-station.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('01-front-day',(29,-34,26),(2,5,8),34)
shot('02-blue-hour',(29,-34,25),(2,5,8),34,True)
shot('03-rear',(-26,35,27),(2,6,8),34)
shot('04-above',(18,-14,43),(1,6,4),33)
shot('05-person-height',(-13,-18,1.7),(0,2,4),30,False,True)
shot('06-studio-control',(4.7,9.0,1.7),(-1.7,6.4,2.3),25,True,True)
